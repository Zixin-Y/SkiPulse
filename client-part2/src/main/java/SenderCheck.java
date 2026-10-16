import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class SenderCheck {

    public static void main(String[] args) throws Exception {
        int totalEvents = 10;

        BlockingQueue<SwipeEvent> queue =
                new ArrayBlockingQueue<>(totalEvents);

        Thread generator = new Thread(
                new SwipeGenerator(queue, totalEvents, 10, 2026, 1),
                "swipe-generator"
        );

        generator.start();
        generator.join();

        if (queue.size() != totalEvents) {
            throw new IllegalStateException(
                    "Generation did not complete"
            );
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        SwipeSender sender = new SwipeSender(
                client,
                "http://34.219.203.115:8080/ski-servlet"
        );

        SwipeEvent event;
        while ((event = queue.poll()) != null) {
            boolean success = sender.send(event);

            System.out.println(
                    event.toPath() + " success=" + success
            );
        }

        System.out.println(
                "Successful swipes: " + sender.getSuccessfulSwipes()
        );
        System.out.println(
                "Failed swipes: " + sender.getFailedSwipes()
        );
        System.out.println(
                "HTTP attempts: " + sender.getAttempts()
        );
        System.out.println(
                "Retries: " + sender.getRetries()
        );

        if (sender.getSuccessfulSwipes() != totalEvents) {
            throw new IllegalStateException(
                    "Some swipes failed"
            );
        }
    }
}