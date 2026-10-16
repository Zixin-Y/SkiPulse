import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;

public class WarmupCheck {

    public static void main(String[] args) throws Exception {
        int senderCount = 32;
        int eventsPerSender = 1000;
        int totalEvents = senderCount * eventsPerSender;

        String baseUrl = "http://34.219.203.115:8080/ski-servlet";

        BlockingQueue<SwipeEvent> queue =
                new ArrayBlockingQueue<>(totalEvents);

        long generationStart = System.nanoTime();

        Thread generator = new Thread(
                new SwipeGenerator(queue, totalEvents, 10, 2026, 1),
                "swipe-generator"
        );

        generator.start();
        generator.join();

        if (queue.size() != totalEvents) {
            throw new IllegalStateException(
                    "Expected " + totalEvents
                            + " events, got " + queue.size()
            );
        }

        double generationSeconds =
                (System.nanoTime() - generationStart) / 1_000_000_000.0;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        SwipeSender sender = new SwipeSender(client, baseUrl);

        List<FutureTask<Void>> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < senderCount; t++) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                for (int i = 0; i < eventsPerSender; i++) {
                    SwipeEvent event = queue.poll();

                    if (event == null) {
                        throw new IllegalStateException(
                                "Queue emptied before sender completed"
                        );
                    }

                    sender.send(event);
                }

                return null;
            });

            tasks.add(task);
            threads.add(new Thread(task, "swipe-sender-" + t));
        }

        System.out.println("Starting warmup...");
        System.out.println("Sender threads: " + senderCount);
        System.out.println("Swipes per sender: " + eventsPerSender);

        long warmupStart = System.nanoTime();

        try {
            for (Thread thread : threads) {
                thread.start();
            }

            for (FutureTask<Void> task : tasks) {
                task.get();
            }

            for (Thread thread : threads) {
                thread.join();
            }
        } finally {
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
        }

        double warmupSeconds =
                (System.nanoTime() - warmupStart) / 1_000_000_000.0;

        long successful = sender.getSuccessfulSwipes();
        long failed = sender.getFailedSwipes();

        System.out.printf(
                "Generation time: %.3f seconds%n", generationSeconds
        );
        System.out.printf(
                "Warmup time: %.3f seconds%n", warmupSeconds
        );
        System.out.println("Successful swipes: " + successful);
        System.out.println("Failed swipes: " + failed);
        System.out.println("HTTP attempts: " + sender.getAttempts());
        System.out.println("Retries: " + sender.getRetries());
        System.out.println("Remaining events: " + queue.size());

        System.out.printf(
                "Warmup throughput: %.2f successful swipes/second%n",
                successful / warmupSeconds
        );

        if (successful + failed != totalEvents || !queue.isEmpty()) {
            throw new IllegalStateException(
                    "Not all events were processed"
            );
        }

        if (failed > 0) {
            throw new IllegalStateException(
                    "Warmup completed with failed swipes"
            );
        }
    }
}