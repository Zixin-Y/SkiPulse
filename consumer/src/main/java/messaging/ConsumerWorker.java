package messaging;

import aggregation.LeaderboardAggregator;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import monitoring.ConsumerLagMetrics;
import java.time.Instant;

public final class ConsumerWorker implements Runnable {

    @FunctionalInterface
    public interface ResultSink {
        // Complete downstream handling before acknowledging the message.
        void accept(
                SwipeMessage message,
                LeaderboardAggregator.Result result) throws Exception;
    }

    private final Connection connection;
    private final List<Integer> resorts;
    private final int prefetch;
    private final LeaderboardAggregator aggregator;
    private final ResultSink sink;

    // RabbitMQ callbacks enqueue deliveries; the worker processes them.
    private final BlockingQueue<PendingDelivery> deliveries =
            new LinkedBlockingQueue<>();

    private volatile boolean stopRequested;
    private volatile boolean failed;
    private volatile boolean terminated;

    public ConsumerWorker(
            Connection connection,
            List<Integer> resorts,
            int prefetch,
            LeaderboardAggregator aggregator,
            ResultSink sink) {

        this.connection = Objects.requireNonNull(connection);
        this.aggregator = Objects.requireNonNull(aggregator);
        this.sink = Objects.requireNonNull(sink);

        if (resorts == null || resorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "A worker must own at least one resort");
        }

        if (prefetch <= 0) {
            throw new IllegalArgumentException(
                    "prefetch must be positive");
        }

        this.resorts = new ArrayList<>(resorts);
        this.prefetch = prefetch;

        for (int resort : this.resorts) {
            if (resort < 1 || resort > 10) {
                throw new IllegalArgumentException(
                        "Resort ID must be between 1 and 10");
            }
        }

        if (this.resorts.stream().distinct().count()
                != this.resorts.size()) {
            throw new IllegalArgumentException(
                    "A worker cannot own the same resort twice");
        }
    }

    @Override
    public void run() {
        Channel channel = null;

        try {
            // All channel operations belong to this worker thread.
            channel = connection.createChannel();

            if (channel == null) {
                throw new IOException("No channel is available");
            }

            channel.basicQos(prefetch);

            for (int resort : resorts) {
                subscribe(channel, resort);
            }

            System.out.println(
                    "Worker started for resorts " + resorts);

            while (!stopRequested) {
                if (failed || !channel.isOpen()) {
                    throw new IOException(
                            "The consumer channel is unavailable");
                }

                PendingDelivery pending =
                        deliveries.poll(500, TimeUnit.MILLISECONDS);

                if (pending != null) {
                    process(channel, pending);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            if (!stopRequested) {
                failed = true;
            }
        } catch (Exception exception) {
            failed = true;

            System.err.println(
                    "Worker failed for resorts " + resorts
                            + ": " + exception.getClass().getSimpleName());
        } finally {
            // Unacknowledged deliveries return to RabbitMQ on channel closure.
            if (channel != null) {
                try {
                    channel.abort();
                } catch (Exception ignored) {
                    // The channel may already be closed.
                }
            }

            terminated = true;

            System.out.println(
                    "Worker stopped for resorts " + resorts);
        }
    }

    private void subscribe(Channel channel, int resort)
            throws IOException {

        DefaultConsumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                    String consumerTag,
                    Envelope envelope,
                    AMQP.BasicProperties properties,
                    byte[] body) {

                deliveries.add(new PendingDelivery(
                        resort,
                        envelope.getDeliveryTag(),
                        body));
            }

            @Override
            public void handleCancel(String consumerTag) {
                failed = true;
            }
        };

        // Require exclusive consumer ownership of each resort queue.
        channel.basicConsume(
                "resort." + resort,
                false,
                "",
                false,
                true,
                null,
                consumer);
    }

    private void process(Channel channel, PendingDelivery pending)
            throws Exception {

        final SwipeMessage message;

        try {
            String json = new String(
                    pending.body, StandardCharsets.UTF_8);

            message = SwipeMessageParser.parse(
                    json, pending.resort);
        } catch (IllegalArgumentException exception) {
            // Invalid messages follow the queue's dead-letter configuration.
            channel.basicReject(pending.deliveryTag, false);

            System.err.println(
                    "Rejected invalid message from resort."
                            + pending.resort);
            return;
        }

        // Measure event age before aggregation, once per delivery attempt.
        Instant processingStart = Instant.now();
        ConsumerLagMetrics.record(
                Instant.parse(message.getTimestamp()),
                processingStart
        );

        // Retry this delivery locally before processing later deliveries.
        for (int attempt = 1; ; attempt++) {
            try {
                LeaderboardAggregator.Result result =
                        aggregator.aggregate(message);

                // Duplicate deliveries also reach the sink for retry recovery.
                sink.accept(message, result);
                break;
            } catch (Exception exception) {
                if (attempt >= 3) {
                    throw exception;
                }

                Thread.sleep(100L * attempt);
            }
        }

        // Acknowledge only this delivery after successful processing.
        channel.basicAck(pending.deliveryTag, false);
    }

    public void requestStop() {
        stopRequested = true;
    }

    public boolean hasFailed() {
        return failed;
    }

    public boolean isTerminated() {
        return terminated;
    }

    private static final class PendingDelivery {
        private final int resort;
        private final long deliveryTag;
        private final byte[] body;

        private PendingDelivery(
                int resort, long deliveryTag, byte[] body) {

            this.resort = resort;
            this.deliveryTag = deliveryTag;
            this.body = body;
        }
    }
}