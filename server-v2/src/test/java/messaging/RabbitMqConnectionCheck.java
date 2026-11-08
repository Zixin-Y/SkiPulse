package messaging;

import com.rabbitmq.client.Channel;

import java.util.concurrent.TimeUnit;

public final class RabbitMqConnectionCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        // Close the manager and its resources when the check finishes.
        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            // Initialization runs on the manager's background worker.
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

            System.out.println("PASS: connection and channel pool are ready.");

            // Keep the pool reference so the channel returns to the same pool.
            ChannelPool pool = manager.getChannelPool();
            Channel channel = pool.borrowChannel();

            try {
                // Verify that initialization created every resort queue.
                for (int resortID = 1; resortID <= 10; resortID++) {
                    String queueName =
                            RabbitMqTopology.routingKey(resortID);

                    channel.queueDeclarePassive(queueName);

                    System.out.println("PASS: queue exists: " + queueName);
                }
            } finally {
                pool.returnChannel(channel);
            }

            System.out.println("All RabbitMQ connection checks passed.");
        }
    }
}