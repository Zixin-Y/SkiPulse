package messaging;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public final class RabbitMqTopology {

    public static final String SWIPE_EXCHANGE = "skitrak.exchange";

    private static final int RESORT_COUNT = 10;

    private static final int MESSAGE_TTL_MS = 86_400_000;
    private static final int MAX_QUEUE_LENGTH = 100_000;

    private static final String DEAD_LETTER_EXCHANGE = "skitrak.dlx";
    private static final String EXPIRED_QUEUE = "skitrak.expired";
    private static final String EXPIRED_ROUTING_KEY = "expired";

    // This utility class does not need instances.
    private RabbitMqTopology() {
    }

    // Declare topology during initialization and connection recovery.
    public static void declare(Connection connection)
            throws IOException, TimeoutException {

        Channel created = connection.createChannel();

        if (created == null) {
            throw new IOException(
                    "Could not allocate a topology initialization channel"
            );
        }

        // Use a dedicated setup channel instead of a borrowed publishing channel.
        try (Channel channel = created) {

            channel.exchangeDeclare(
                    SWIPE_EXCHANGE,
                    BuiltinExchangeType.TOPIC,
                    true
            );

            // Keep expired swipe messages separate from normal processing.
            channel.exchangeDeclare(
                    DEAD_LETTER_EXCHANGE,
                    BuiltinExchangeType.DIRECT,
                    true
            );

            channel.queueDeclare(
                    EXPIRED_QUEUE,
                    true,
                    false,
                    false,
                    null
            );

            channel.queueBind(
                    EXPIRED_QUEUE,
                    DEAD_LETTER_EXCHANGE,
                    EXPIRED_ROUTING_KEY
            );

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("x-message-ttl", MESSAGE_TTL_MS);
            arguments.put("x-max-length", MAX_QUEUE_LENGTH);
            arguments.put("x-overflow", "reject-publish");
            arguments.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
            arguments.put("x-dead-letter-routing-key", EXPIRED_ROUTING_KEY);

            // Create one queue and one exact binding per resort.
            for (int resortID = 1; resortID <= RESORT_COUNT; resortID++) {
                String queueName = routingKey(resortID);

                channel.queueDeclare(
                        queueName,
                        true,
                        false,
                        false,
                        arguments
                );

                channel.queueBind(
                        queueName,
                        SWIPE_EXCHANGE,
                        routingKey(resortID)
                );
            }
        }
    }

    // Use the same routing key convention throughout the application.
    public static String routingKey(int resortID) {
        if (resortID < 1 || resortID > RESORT_COUNT) {
            throw new IllegalArgumentException(
                    "resortID must be between 1 and 10"
            );
        }

        return "resort." + resortID;
    }
}