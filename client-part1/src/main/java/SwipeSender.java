import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

public final class SwipeSender {

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 100;

    private final HttpClient client;
    private final String baseUrl;

    private final LongAdder successfulSwipes = new LongAdder();
    private final LongAdder failedSwipes = new LongAdder();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();

    public SwipeSender(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    public boolean send(SwipeEvent event)
            throws InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + event.toPath()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        event.toJson()
                ))
                .build();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delayMs =
                        INITIAL_BACKOFF_MS * (1L << (attempt - 1));

                Thread.sleep(delayMs);
                retries.increment();
            }

            attempts.increment();

            try {
                HttpResponse<Void> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

                if (response.statusCode() == 201) {
                    successfulSwipes.increment();
                    return true;
                }

                System.err.printf(
                        "Attempt %d failed: path=%s, status=%d%n",
                        attempt + 1,
                        event.toPath(),
                        response.statusCode()
                );
            } catch (IOException e) {
                System.err.printf(
                        "Attempt %d failed: path=%s, error=%s, message=%s%n",
                        attempt + 1,
                        event.toPath(),
                        e.getClass().getSimpleName(),
                        e.getMessage()
                );
            }
        }

        failedSwipes.increment();
        return false;
    }

    public long getSuccessfulSwipes() {
        return successfulSwipes.sum();
    }

    public long getFailedSwipes() {
        return failedSwipes.sum();
    }

    public long getAttempts() {
        return attempts.sum();
    }

    public long getRetries() {
        return retries.sum();
    }
}