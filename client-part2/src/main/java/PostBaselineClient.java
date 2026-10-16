import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class PostBaselineClient {

    public static void main(String[] args) throws Exception {

        // Create once and reuse it for all subsequent requests.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        double totalMeasuredMs = 0;

        for (int i = 0; i < 21; i++) {
            String url =
                    "http://44.244.115.175:8080/ski-servlet"
                            + "/skiers/1/seasons/2026/days/1/skiers/"
                            + (i + 1);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"time\":217,\"liftID\":21}"
                    ))
                    .build();

            long start = System.nanoTime();

            HttpResponse<Void> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            double elapsedMs =
                    (System.nanoTime() - start) / 1_000_000.0;

            System.out.printf(
                    "Request %d: status=%d, latency=%.3f ms%n",
                    i + 1, response.statusCode(), elapsedMs
            );

            if (response.statusCode() != 201) {
                throw new IllegalStateException(
                        "Expected 201, got " + response.statusCode()
                );
            }

            // Report the first request separately; average the remaining 20.
            if (i > 0) {
                totalMeasuredMs += elapsedMs;
            }
        }

        double averageMs = totalMeasuredMs / 20;

        System.out.printf(
                "Average of last 20 requests: %.3f ms%n",
                averageMs
        );

        System.out.printf(
                "Predicted throughput with 32 senders: %.2f swipes/s%n",
                32 * 1000.0 / averageMs
        );
    }
}