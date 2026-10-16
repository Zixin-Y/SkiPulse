import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LeaderboardSubscriber implements AutoCloseable {

    private final HttpClient client;
    private final URI uri;
    private final int resortID;

    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger connectionAttempts = new AtomicInteger();

    private final List<Double> pushLatenciesMs = new ArrayList<>();
    private final List<Double> establishmentTimesMs = new ArrayList<>();

    private volatile boolean running;
    private volatile WebSocket socket;
    private Thread worker;

    public LeaderboardSubscriber(
            HttpClient client, String wsBaseUrl, int resortID) {

        this.client = client;
        this.resortID = resortID;

        String base = wsBaseUrl.endsWith("/")
                ? wsBaseUrl.substring(0, wsBaseUrl.length() - 1)
                : wsBaseUrl;

        this.uri = URI.create(base + "/leaderboard/" + resortID);
    }

    public synchronized void start() {
        if (worker != null) {
            throw new IllegalStateException(
                    "Subscriber has already been started"
            );
        }

        running = true;
        worker = new Thread(this::maintainConnection,
                "leaderboard-" + resortID);
        worker.setDaemon(true);
        worker.start();
    }

    private void maintainConnection() {
        long retryDelayMs = 1000;

        while (running) {
            CountDownLatch disconnected = new CountDownLatch(1);
            CompletableFuture<WebSocket> pending = null;
            WebSocket connected = null;

            try {
                connectionAttempts.incrementAndGet();
                long start = System.nanoTime();

                pending = client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(uri, new Listener(disconnected));

                connected = pending.get(15, TimeUnit.SECONDS);

                if (!running) {
                    break;
                }

                double elapsedMs =
                        (System.nanoTime() - start) / 1_000_000.0;

                synchronized (this) {
                    establishmentTimesMs.add(elapsedMs);
                }

                int count = connections.incrementAndGet();
                retryDelayMs = 1000;

                System.out.printf(
                        "Resort %d connected: %.3f ms, connections=%d%n",
                        resortID, elapsedMs, count
                );

                disconnected.await();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                if (running) {
                    System.err.printf(
                            "Resort %d connection failure: %s%n",
                            resortID, e
                    );
                }

            } finally {
                if (pending != null && !pending.isDone()) {
                    pending.cancel(true);
                }

                if (connected != null) {
                    connected.abort();
                }

                WebSocket current = socket;
                if (current != null) {
                    current.abort();
                }
                socket = null;
            }

            if (running) {
                try {
                    Thread.sleep(retryDelayMs);
                    retryDelayMs = Math.min(retryDelayMs * 2, 16000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final CountDownLatch disconnected;
        private final StringBuilder message = new StringBuilder();

        private Listener(CountDownLatch disconnected) {
            this.disconnected = disconnected;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;

            if (!running) {
                webSocket.abort();
                disconnected.countDown();
                return;
            }

            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {

            message.append(data);

            if (last) {
                Instant receiveTime = Instant.now();
                String text = message.toString();
                message.setLength(0);

                try {
                    JsonObject json = JsonParser.parseString(text)
                            .getAsJsonObject();

                    int receivedResort =
                            json.get("resortID").getAsInt();

                    if (receivedResort != resortID) {
                        throw new IllegalArgumentException(
                                "Unexpected resort ID"
                        );
                    }

                    Instant serverTime = Instant.parse(
                            json.get("serverTimestamp").getAsString()
                    );

                    double latencyMs = Duration.between(
                            serverTime, receiveTime
                    ).toNanos() / 1_000_000.0;

                    synchronized (LeaderboardSubscriber.this) {
                        pushLatenciesMs.add(latencyMs);
                    }

                } catch (RuntimeException e) {
                    System.err.printf(
                            "Resort %d invalid message: %s%n",
                            resortID, e
                    );
                }
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket, int statusCode, String reason) {

            disconnected.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (running) {
                System.err.printf(
                        "Resort %d WebSocket error: %s%n",
                        resortID, error
                );
            }

            disconnected.countDown();
        }
    }

    public synchronized int getUpdateCount() {
        return pushLatenciesMs.size();
    }

    public synchronized List<Double> getPushLatenciesMs() {
        return new ArrayList<>(pushLatenciesMs);
    }

    public synchronized void printSummary() {
        double averageConnectionMs = establishmentTimesMs.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);

        System.out.printf(
                "Resort %d: updates=%d, attempts=%d, "
                        + "connections=%d, reconnections=%d, "
                        + "averageEstablishmentMs=%.3f%n",
                resortID,
                pushLatenciesMs.size(),
                connectionAttempts.get(),
                connections.get(),
                Math.max(0, connections.get() - 1),
                averageConnectionMs
        );
    }

    public int getConnectionCount() {
        return connections.get();
    }

    public void disconnectForTest() throws Exception {
        WebSocket current = socket;

        if (current == null || current.isOutputClosed()) {
            throw new IllegalStateException(
                    "No active connection to disconnect"
            );
        }

        current.sendClose(
                WebSocket.NORMAL_CLOSURE,
                "Reconnect test"
        ).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
        running = false;

        WebSocket current = socket;
        if (current != null) {
            current.abort();
        }

        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            currentWorker.join();
        }
    }
}