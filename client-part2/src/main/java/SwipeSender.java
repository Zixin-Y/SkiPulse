import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final SwipeCsvRecorder recorder;

    private final LongAdder successfulSwipes = new LongAdder();
    private final LongAdder failedSwipes = new LongAdder();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final String runId =
            "part2-" + java.util.UUID.randomUUID().toString();

    public SwipeSender(HttpClient client, String baseUrl) {
        this(client, baseUrl, null);
    }

    public SwipeSender(
            HttpClient client,
            String baseUrl,
            SwipeCsvRecorder recorder) {

        this.client = client;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.recorder = recorder;
        System.out.println("HTTP run ID: " + runId);
    }

    public boolean send(SwipeEvent event)
            throws InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + event.toPath()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Run-ID", runId)
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

            int statusCode = 0;
            IOException networkError = null;

            long timestamp = System.currentTimeMillis();
            long start = System.nanoTime();

            try {
                HttpResponse<Void> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

                statusCode = response.statusCode();

            } catch (IOException e) {
                networkError = e;
            }

            double latencyMs =
                    (System.nanoTime() - start) / 1_000_000.0;

            if (recorder != null) {
                try {
                    recorder.record(
                            timestamp,
                            latencyMs,
                            statusCode,
                            event.getResortID()
                    );
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Failed to write swipe metrics", e
                    );
                }
            }

            if (statusCode == 201) {
                successfulSwipes.increment();
                return true;
            }

            if (networkError != null) {
                System.err.printf(
                        "Attempt %d failed: path=%s, "
                                + "error=%s, message=%s%n",
                        attempt + 1,
                        event.toPath(),
                        networkError.getClass().getSimpleName(),
                        networkError.getMessage()
                );
            } else {
                System.err.printf(
                        "Attempt %d failed: path=%s, status=%d%n",
                        attempt + 1,
                        event.toPath(),
                        statusCode
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