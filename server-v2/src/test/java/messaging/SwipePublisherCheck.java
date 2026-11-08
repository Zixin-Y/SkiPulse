package messaging;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import model.LiftVerticalLookup;
import model.SwipeMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SwipePublisherCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();
        Gson gson = new Gson();
        String queueName = RabbitMqTopology.routingKey(1);

        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            manager.start();

            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

            while (!manager.isConnected()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "RabbitMQ did not become ready within 60 seconds"
                    );
                }

                Thread.sleep(200);
            }

            // Require an idle, empty queue before this controlled check.
            ChannelPool initialPool = manager.getChannelPool();
            Channel initialChannel = initialPool.borrowChannel();

            try {
                AMQP.Queue.DeclareOk state =
                        initialChannel.queueDeclarePassive(queueName);

                if (state.getMessageCount() != 0
                        || state.getConsumerCount() != 0) {

                    throw new IllegalStateException(
                            "This check requires an empty resort.1 queue "
                                    + "with no active consumers"
                    );
                }
            } finally {
                initialPool.returnChannel(initialChannel);
            }

            // Construct a known event using the supplied lift table.
            SwipeMessage message = new SwipeMessage(
                    UUID.randomUUID().toString(),
                    "1",
                    "1",
                    "1001",
                    "1",
                    LiftVerticalLookup.getVertical(1),
                    120,
                    Instant.now().toString(),
                    "publisher-check"
            );

            SwipePublisher publisher = new SwipePublisher(manager);
            publisher.publish(message);

            System.out.println("PASS: publisher received broker confirmation.");

            ChannelPool readPool = manager.getChannelPool();
            Channel readChannel = readPool.borrowChannel();

            try {
                // Read without automatic acknowledgement.
                GetResponse received =
                        readChannel.basicGet(queueName, false);

                if (received == null) {
                    throw new AssertionError(
                            "No message was found in resort.1"
                    );
                }

                long deliveryTag =
                        received.getEnvelope().getDeliveryTag();

                String payload = new String(
                        received.getBody(), StandardCharsets.UTF_8
                );

                try {
                    // Compare JSON structures rather than property order.
                    if (!gson.toJsonTree(message).equals(
                            JsonParser.parseString(payload))) {

                        throw new AssertionError(
                                "Received JSON does not match the sent event"
                        );
                    }

                    if (!message.getEventId().equals(
                            received.getProps().getMessageId())) {

                        throw new AssertionError(
                                "Message ID does not match eventId"
                        );
                    }

                    if (!Integer.valueOf(2).equals(
                            received.getProps().getDeliveryMode())) {

                        throw new AssertionError(
                                "Message is not marked persistent"
                        );
                    }

                    if (!"application/json".equals(
                            received.getProps().getContentType())) {

                        throw new AssertionError(
                                "Unexpected message content type"
                        );
                    }

                } catch (RuntimeException | AssertionError e) {
                    // Preserve unexpected data instead of deleting it.
                    readChannel.basicNack(deliveryTag, false, true);
                    throw e;
                }

                // Remove the verified test message from the queue.
                readChannel.basicAck(deliveryTag, false);

                System.out.println("PASS: event arrived in resort.1.");
                System.out.println("PASS: complete JSON and message ID match.");
                System.out.println("PASS: persistent JSON properties are correct.");
                System.out.println("Received JSON: " + payload);

            } finally {
                readPool.returnChannel(readChannel);
            }

            System.out.println("All swipe publisher checks passed.");
        }
    }
}