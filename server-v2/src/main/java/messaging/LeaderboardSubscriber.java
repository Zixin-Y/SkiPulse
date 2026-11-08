package messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import leaderboard.LeaderboardStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class LeaderboardSubscriber implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(LeaderboardSubscriber.class.getName());

    private static final String EXCHANGE = "leaderboard.fanout";

    private final RabbitMqConnectionManager manager;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private volatile Channel channel;
    private boolean started;
    private volatile boolean closed;
    private volatile boolean subscriptionFailed;

    public LeaderboardSubscriber(RabbitMqConnectionManager manager) {
        this.manager = Objects.requireNonNull(manager);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "The leaderboard subscriber is closed");
        }

        if (started) {
            return;
        }

        started = true;

        executor.scheduleWithFixedDelay(
                this::maintainSubscription,
                0,
                5,
                TimeUnit.SECONDS);
    }

    private synchronized void maintainSubscription() {
        if (closed) {
            return;
        }

        if (channel != null
                && channel.isOpen()
                && !subscriptionFailed) {
            return;
        }

        closeChannel();
        subscriptionFailed = true;

        try {
            Channel candidate = manager.createSubscriptionChannel();
            channel = candidate;

            candidate.exchangeDeclare(EXCHANGE, "fanout", true);

            // Create a separate temporary queue for this server connection.
            String queue = candidate.queueDeclare(
                    "",
                    false,
                    true,
                    true,
                    null).getQueue();

            candidate.queueBind(queue, EXCHANGE, "");
            candidate.basicQos(50);

            DefaultConsumer consumer = new DefaultConsumer(candidate) {
                @Override
                public void handleDelivery(
                        String consumerTag,
                        Envelope envelope,
                        AMQP.BasicProperties properties,
                        byte[] body) throws IOException {

                    receive(
                            candidate,
                            envelope.getDeliveryTag(),
                            body);
                }

                @Override
                public void handleCancel(String consumerTag) {
                    markSubscriptionFailed();
                }
            };

            candidate.basicConsume(queue, false, consumer);
            subscriptionFailed = false;

            LOGGER.info(
                    "Leaderboard backplane subscribed: queue=" + queue);
        } catch (IOException | RuntimeException exception) {
            closeChannel();

            LOGGER.warning(
                    "Leaderboard subscription is not ready: "
                            + exception.getClass().getSimpleName()
                            + "; another attempt will follow");
        }
    }

    private synchronized void receive(
            Channel source,
            long deliveryTag,
            byte[] body) throws IOException {

        // Ignore callbacks from a replaced subscription.
        if (closed || source != channel || !source.isOpen()) {
            return;
        }

        final LeaderboardStore.Snapshot snapshot;

        try {
            String json = new String(body, StandardCharsets.UTF_8);
            snapshot = LeaderboardStore.accept(json);
        } catch (RuntimeException exception) {
            LOGGER.warning(
                    "Rejected malformed leaderboard update: "
                            + exception.getClass().getSimpleName());

            source.basicReject(deliveryTag, false);
            return;
        }

        source.basicAck(deliveryTag, false);

        LOGGER.info(
                "Leaderboard cached: resort=" + snapshot.getResortID()
                        + " day=" + snapshot.getDayID()
                        + " version=" + snapshot.getVersion());
    }

    private synchronized void markSubscriptionFailed() {
        subscriptionFailed = true;
    }

    public boolean isReady() {
        // Read local state without waiting for subscription network operations.
        Channel snapshot = channel;

        return !closed
                && snapshot != null
                && snapshot.isOpen()
                && !subscriptionFailed;
    }

    private void closeChannel() {
        Channel previous = channel;
        channel = null;

        if (previous != null) {
            try {
                previous.abort();
            } catch (Exception ignored) {
                // The old channel may already be closed.
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        executor.shutdownNow();
        closeChannel();
    }
}