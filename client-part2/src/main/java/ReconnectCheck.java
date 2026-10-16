import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ReconnectCheck {

    public static void main(String[] args) throws Exception {
        String wsBaseUrl = args.length > 0
                ? args[0]
                : "ws://34.219.205.130:8080/ski-servlet";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try (LeaderboardSubscriber subscriber =
                     new LeaderboardSubscriber(
                             client, wsBaseUrl, 7
                     )) {

            subscriber.start();

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

            while (subscriber.getConnectionCount() < 1
                    || subscriber.getUpdateCount() < 3) {

                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "Initial subscription did not become ready"
                    );
                }

                Thread.sleep(100);
            }

            int connectionsBefore =
                    subscriber.getConnectionCount();

            System.out.println(
                    "Closing the current connection for testing..."
            );

            subscriber.disconnectForTest();

            deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

            while (subscriber.getConnectionCount()
                    <= connectionsBefore) {

                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "Subscriber did not reconnect"
                    );
                }

                Thread.sleep(100);
            }

            int updatesAfterReconnect =
                    subscriber.getUpdateCount();

            while (subscriber.getUpdateCount()
                    < updatesAfterReconnect + 3) {

                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "No updates after reconnection"
                    );
                }

                Thread.sleep(100);
            }

            System.out.println(
                    "Reconnect check passed: updates resumed."
            );

            subscriber.printSummary();
        }
    }
}