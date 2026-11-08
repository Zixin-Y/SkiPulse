package messaging;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class SwipePublisher {

    private static final Logger LOGGER =
            Logger.getLogger(SwipePublisher.class.getName());

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitMqConnectionManager manager;
    private final Gson gson = new Gson();

    // Reuse the application's shared connection manager.
    public SwipePublisher(RabbitMqConnectionManager manager) {
        this.manager = Objects.requireNonNull(
                manager, "manager must not be null"
        );
    }

    // Return normally only after a routed publish is confirmed.
    public void publish(SwipeMessage message)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(message, "message must not be null");

        int resortID = Integer.parseInt(message.getResortID());
        String routingKey = RabbitMqTopology.routingKey(resortID);

        byte[] body = gson.toJson(message)
                .getBytes(StandardCharsets.UTF_8);

        AMQP.BasicProperties properties =
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("UTF-8")
                        .deliveryMode(2)
                        .messageId(message.getEventId())
                        .build();

        // Keep this pool reference even if recovery installs a new pool.
        ChannelPool pool = manager.getChannelPool();
        Channel channel = pool.borrowChannel();

        AtomicReference<String> returnedReason = new AtomicReference<>();

        ReturnListener listener =
                (replyCode, replyText, exchange, key, props, returnedBody) ->
                        returnedReason.set(replyCode + " " + replyText);

        boolean successful = false;

        try {
            // Detect messages that cannot be routed to any queue.
            channel.addReturnListener(listener);

            channel.basicPublish(
                    RabbitMqTopology.SWIPE_EXCHANGE,
                    routingKey,
                    true,
                    properties,
                    body
            );

            // Channels enter confirm mode during pool initialization.
            channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);

            if (returnedReason.get() != null) {
                throw new IOException(
                        "RabbitMQ returned an unroutable message: "
                                + returnedReason.get()
                );
            }

            successful = true;

        } finally {
            // Do not reuse a channel with a failed or uncertain publish.
            if (!successful) {
                abortQuietly(channel);
            }

            channel.removeReturnListener(listener);
            pool.returnChannel(channel);
        }
    }

    // Preserve the original publish failure if channel cleanup also fails.
    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Could not abort a publishing channel");
        }
    }
}