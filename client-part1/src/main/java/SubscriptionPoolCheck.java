import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SubscriptionPoolCheck {

    public static void main(String[] args) throws Exception {
        String wsBaseUrl = args.length > 0
                ? args[0]
                : "ws://34.219.205.130:8080/ski-servlet";

        int resortCount = 10;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<LeaderboardSubscriber> subscribers = new ArrayList<>();

        try {
            for (int resortID = 1; resortID <= resortCount; resortID++) {
                LeaderboardSubscriber subscriber =
                        new LeaderboardSubscriber(
                                client, wsBaseUrl, resortID
                        );

                subscribers.add(subscriber);
                subscriber.start();
            }

            System.out.println("Waiting for updates from all resorts...");

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

            while (!allHaveUpdates(subscribers)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "Not all subscribers received updates"
                    );
                }

                Thread.sleep(100);
            }

            System.out.println(
                    "All 10 resorts are receiving updates."
            );
            System.out.println(
                    "Keeping subscriptions active for 15 seconds..."
            );

            Thread.sleep(15_000);

        } finally {
            for (LeaderboardSubscriber subscriber : subscribers) {
                subscriber.close();
            }

            System.out.println();
            System.out.println("=== Subscription results ===");

            long totalUpdates = 0;

            for (LeaderboardSubscriber subscriber : subscribers) {
                subscriber.printSummary();
                totalUpdates += subscriber.getUpdateCount();
            }

            System.out.println(
                    "Total leaderboard updates: " + totalUpdates
            );
        }
    }

    private static boolean allHaveUpdates(
            List<LeaderboardSubscriber> subscribers) {

        for (LeaderboardSubscriber subscriber : subscribers) {
            if (subscriber.getUpdateCount() == 0) {
                return false;
            }
        }

        return true;
    }
}