import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

public class CsvSenderCheck {

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0
                ? args[0]
                : "http://52.27.140.75:8080/ski-servlet";

        Path output = Path.of(
                System.getProperty("user.home"),
                "Downloads",
                "6650",
                "assignment1",
                "results",
                "csv-check-" + System.currentTimeMillis() + ".csv"
        );

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try (SwipeCsvRecorder recorder =
                     new SwipeCsvRecorder(output)) {

            SwipeSender sender =
                    new SwipeSender(client, baseUrl, recorder);

            for (int skierID = 1; skierID <= 10; skierID++) {
                SwipeEvent event = new SwipeEvent(
                        7, 2026, 1, skierID, 217, 21
                );

                sender.send(event);
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
                    "CSV rows: " + recorder.getRowCount()
            );

            if (recorder.getRowCount() != sender.getAttempts()) {
                throw new IllegalStateException(
                        "CSV row count does not match attempts"
                );
            }

            if (sender.getSuccessfulSwipes() != 10) {
                throw new IllegalStateException(
                        "Some swipes failed"
                );
            }
        }

        System.out.println("CSV saved to: " + output);
    }
}