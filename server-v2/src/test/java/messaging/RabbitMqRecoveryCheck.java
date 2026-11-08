package messaging;

import com.rabbitmq.client.Channel;

import java.util.concurrent.TimeUnit;

public final class RabbitMqRecoveryCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            manager.start();
            awaitState(manager, true, 60);

            ChannelPool originalPool = manager.getChannelPool();

            System.out.println("PASS: initial connection is ready.");
            System.out.println("ACTION: stop the RabbitMQ container now.");

            // Require an observed outage before testing recovery.
            awaitState(manager, false, 120);

            System.out.println("PASS: connection loss was detected.");
            System.out.println("ACTION: start the RabbitMQ container now.");

            // Keep the same manager and wait for automatic recovery.
            awaitState(manager, true, 120);

            ChannelPool recoveredPool = manager.getChannelPool();

            if (recoveredPool == originalPool) {
                throw new AssertionError(
                        "Recovery did not replace the channel pool"
                );
            }

            System.out.println("PASS: a replacement channel pool is ready.");

            Channel channel = recoveredPool.borrowChannel();

            try {
                // Verify that the recovered resources can perform broker operations.
                for (int resortID = 1; resortID <= 10; resortID++) {
                    String queueName =
                            RabbitMqTopology.routingKey(resortID);

                    channel.queueDeclarePassive(queueName);
                }
            } finally {
                recoveredPool.returnChannel(channel);
            }

            System.out.println("PASS: all 10 queues are accessible after recovery.");
            System.out.println("All RabbitMQ recovery checks passed.");
        }
    }

    // Wait for the requested readiness state with a bounded timeout.
    private static void awaitState(
            RabbitMqConnectionManager manager,
            boolean expectedConnected,
            int timeoutSeconds) throws InterruptedException {

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while (manager.isConnected() != expectedConnected) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for connected="
                                + expectedConnected
                );
            }

            Thread.sleep(200);
        }
    }
}