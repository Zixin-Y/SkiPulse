package app;

import aggregation.LeaderboardAggregator;
import messaging.LeaderboardPublisher;
import java.util.UUID;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import config.ConsumerConfig;
import messaging.ConsumerWorker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import monitoring.ConsumerLagMetrics;

public final class ConsumerMain {

    public static void main(String[] args) throws Exception {
        ConsumerConfig config = ConsumerConfig.fromEnvironment();

        // Preserve in-memory aggregation state across connection recovery.
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(config.getTopN());

        //Gson gson = new Gson();
        // Keep one source ID throughout this consumer process.
        String sourceId = UUID.randomUUID().toString();
        AtomicBoolean stopping = new AtomicBoolean();
        Thread mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping.set(true);
            mainThread.interrupt();
        }, "consumer-shutdown"));

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        factory.setRequestedHeartbeat(30);

        // Rebuild connections and subscriptions explicitly.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);

        while (!stopping.get()) {
            Connection connection = null;
            List<ConsumerWorker> workers = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();

            try {
                connection = factory.newConnection("skitrak-consumer");

                int activeWorkers =
                        Math.min(config.getConsumerThreads(), 10);

                List<List<Integer>> assignments = new ArrayList<>();

                for (int i = 0; i < activeWorkers; i++) {
                    assignments.add(new ArrayList<>());
                }

                // Distribute resorts evenly without overlapping ownership.
                for (int resort = 1; resort <= 10; resort++) {
                    assignments.get((resort - 1) % activeWorkers)
                            .add(resort);
                }

                for (int i = 0; i < activeWorkers; i++) {
                    // Give each worker its own publishing channel.
                    LeaderboardPublisher publisher =
                            new LeaderboardPublisher(connection, sourceId);

                    ConsumerWorker.ResultSink sink = (message, result) -> {
                        // Republish snapshots for duplicate deliveries after a failed attempt.
                        if (result.isLeaderboardChanged()
                                || !result.isApplied()) {

                            publisher.publish(message, result);

                            System.out.println(
                                    "BACKPLANE confirmed: resort="
                                            + message.getResortID()
                                            + " day=" + message.getDayID()
                                            + " version=" + result.getVersion()
                                            + " entries=" + result.getLeaderboard().size()
                                            + " applied=" + result.isApplied());
                        }
                    };

                    ConsumerWorker worker = new ConsumerWorker(
                            connection,
                            assignments.get(i),
                            config.getPrefetch(),
                            aggregator,
                            sink);

                    Thread thread = new Thread(
                            worker, "consumer-worker-" + (i + 1));

                    workers.add(worker);
                    threads.add(thread);
                    thread.start();
                }

                System.out.println(
                        "Consumer started: requestedThreads="
                                + config.getConsumerThreads()
                                + ", activeWorkers=" + activeWorkers
                                + ", prefetch=" + config.getPrefetch());

                while (!stopping.get()) {
                    if (!connection.isOpen()) {
                        throw new IllegalStateException(
                                "RabbitMQ connection was lost");
                    }

                    for (ConsumerWorker worker : workers) {
                        if (worker.hasFailed()
                                || worker.isTerminated()) {
                            throw new IllegalStateException(
                                    "A consumer worker stopped");
                        }
                    }

                    // Report unique aggregation progress without logging every swipe.
                    System.out.println(
                            "CONSUMER_METRICS sourceId=" + sourceId
                                    + " uniqueSwipesProcessed="
                                    + aggregator.getSwipesProcessed()
                                    + " " + ConsumerLagMetrics.snapshot());

                    Thread.sleep(1000);

                }
            } catch (InterruptedException exception) {
                stopping.set(true);
            } catch (Exception exception) {
                System.err.println(
                        "Consumer recovery required: "
                                + exception.getClass().getSimpleName());
            } finally {
                for (ConsumerWorker worker : workers) {
                    worker.requestStop();
                }

                // Close the old connection before creating a replacement.
                if (connection != null) {
                    connection.abort();
                }

                // Wait until all old workers stop to prevent ownership overlap.
                joinWorkers(threads);
            }

            if (!stopping.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException exception) {
                    stopping.set(true);
                }
            }
        }

        System.out.println(
                "Consumer stopped. Unique swipes processed: "
                        + aggregator.getSwipesProcessed());
    }

    private static void joinWorkers(List<Thread> threads) {
        boolean interrupted = false;

        for (Thread thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}