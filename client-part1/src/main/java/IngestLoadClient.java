import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class IngestLoadClient {

    private static final int TOTAL_EVENTS = 500_000;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_PER_THREAD = 1000;
    private static final int MAIN_THREADS = 64;

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0
                ? args[0]
                : "http://34.219.205.130:8080/ski-servlet";

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String wsBaseUrl;

        if (baseUrl.startsWith("https://")) {
            wsBaseUrl = "wss://" + baseUrl.substring(8);
        } else if (baseUrl.startsWith("http://")) {
            wsBaseUrl = "ws://" + baseUrl.substring(7);
        } else {
            throw new IllegalArgumentException(
                    "Base URL must start with http:// or https://"
            );
        }

        long totalStart = System.nanoTime();

        BlockingQueue<SwipeEvent> queue =
                new ArrayBlockingQueue<>(TOTAL_EVENTS);

        long generationStart = System.nanoTime();

        Thread generator = new Thread(
                new SwipeGenerator(queue, TOTAL_EVENTS, 10, 2026, 1),
                "swipe-generator"
        );

        generator.start();
        generator.join();

        if (queue.size() != TOTAL_EVENTS) {
            throw new IllegalStateException(
                    "Generation did not complete"
            );
        }

        double generationSeconds = elapsedSeconds(generationStart);

        HttpClient ingestClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpClient subscriptionClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        SwipeSender sender = new SwipeSender(ingestClient, baseUrl);

        List<LeaderboardSubscriber> subscribers = new ArrayList<>();

        double ingestSeconds = 0;
        boolean ingestCompleted = false;

        try {
            for (int resortID = 1; resortID <= 10; resortID++) {
                LeaderboardSubscriber subscriber =
                        new LeaderboardSubscriber(
                                subscriptionClient, wsBaseUrl, resortID
                        );

                subscribers.add(subscriber);
                subscriber.start();
            }

            System.out.println(
                    "Waiting for all leaderboard subscriptions..."
            );

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

            while (true) {
                boolean allReady = true;

                for (LeaderboardSubscriber subscriber : subscribers) {
                    if (subscriber.getUpdateCount() == 0) {
                        allReady = false;
                        break;
                    }
                }

                if (allReady) {
                    break;
                }

                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "Not all leaderboard subscriptions became ready"
                    );
                }

                Thread.sleep(100);
            }

            System.out.println(
                    "All leaderboard subscriptions are receiving updates."
            );

            long ingestStart = System.nanoTime();

            int warmupEvents = WARMUP_THREADS * WARMUP_PER_THREAD;

            runPhase(
                    "Warmup",
                    WARMUP_THREADS,
                    warmupEvents,
                    queue,
                    sender
            );

            runPhase(
                    "Main",
                    MAIN_THREADS,
                    TOTAL_EVENTS - warmupEvents,
                    queue,
                    sender
            );

            ingestSeconds = elapsedSeconds(ingestStart);
            ingestCompleted = true;

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

        double totalSeconds = elapsedSeconds(totalStart);

        long successful = sender.getSuccessfulSwipes();
        long failed = sender.getFailedSwipes();

        System.out.println();
        System.out.println("=== Overall ingest results ===");
        System.out.println("Ingest completed: " + ingestCompleted);
        System.out.printf(
                "Generation time: %.3f seconds%n",
                generationSeconds
        );
        System.out.printf(
                "Ingest time including warmup: %.3f seconds%n",
                ingestSeconds
        );
        System.out.printf(
                "Total wall-clock time: %.3f seconds%n",
                totalSeconds
        );
        System.out.println("Successful swipes: " + successful);
        System.out.println("Failed swipes: " + failed);
        System.out.println("HTTP attempts: " + sender.getAttempts());
        System.out.println("Retries: " + sender.getRetries());
        System.out.println("Remaining events: " + queue.size());

        System.out.printf(
                "Overall throughput: %.2f successful swipes/second%n",
                successful / totalSeconds
        );
        System.out.printf(
                "Ingest throughput including warmup: "
                        + "%.2f successful swipes/second%n",
                successful / ingestSeconds
        );

        if (successful + failed != TOTAL_EVENTS || !queue.isEmpty()) {
            throw new IllegalStateException(
                    "Event accounting does not match total"
            );
        }

        if (failed > 0) {
            throw new IllegalStateException(
                    "Run completed with failed swipes"
            );
        }
    }

    private static void runPhase(
            String name,
            int threadCount,
            int eventCount,
            BlockingQueue<SwipeEvent> queue,
            SwipeSender sender) throws Exception {

        long successBefore = sender.getSuccessfulSwipes();
        long failureBefore = sender.getFailedSwipes();
        long retriesBefore = sender.getRetries();

        ExecutorService pool =
                Executors.newFixedThreadPool(threadCount);

        List<Future<Void>> tasks = new ArrayList<>();

        System.out.println();
        System.out.println("Starting " + name + "...");
        System.out.println("Sender threads: " + threadCount);
        System.out.println("Planned swipes: " + eventCount);

        long phaseStart = System.nanoTime();

        try {
            for (int t = 0; t < threadCount; t++) {
                final int assignedEvents =
                        eventCount / threadCount
                                + (t < eventCount % threadCount ? 1 : 0);

                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < assignedEvents; i++) {
                        SwipeEvent event = queue.poll();

                        if (event == null) {
                            throw new IllegalStateException(
                                    "Queue emptied unexpectedly"
                            );
                        }

                        sender.send(event);
                    }

                    return null;
                }));
            }

            pool.shutdown();

            for (Future<Void> task : tasks) {
                task.get();
            }

            while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                // Wait for all sender threads to terminate.
            }
        } finally {
            pool.shutdownNow();
        }

        double seconds = elapsedSeconds(phaseStart);
        long successful =
                sender.getSuccessfulSwipes() - successBefore;
        long failed =
                sender.getFailedSwipes() - failureBefore;
        long retries =
                sender.getRetries() - retriesBefore;

        System.out.printf("%s time: %.3f seconds%n", name, seconds);
        System.out.println("Successful swipes: " + successful);
        System.out.println("Failed swipes: " + failed);
        System.out.println("Retries: " + retries);
        System.out.printf(
                "%s throughput: %.2f successful swipes/second%n",
                name, successful / seconds
        );
    }

    private static double elapsedSeconds(long start) {
        return (System.nanoTime() - start) / 1_000_000_000.0;
    }
}