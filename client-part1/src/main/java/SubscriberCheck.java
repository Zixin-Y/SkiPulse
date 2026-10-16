import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SubscriberCheck {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0
                ? args[0]
                : "ws://34.219.205.130:8080/ski-servlet/leaderboard/7";

        CountDownLatch received = new CountDownLatch(10);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(
                    WebSocket webSocket,
                    CharSequence data,
                    boolean last) {

                message.append(data);

                if (last) {
                    Instant receiveTime = Instant.now();
                    String text = message.toString();
                    message.setLength(0);

                    try {
                        JsonObject json = JsonParser.parseString(text)
                                .getAsJsonObject();

                        String resortID = json.get("resortID")
                                .getAsString();

                        Instant serverTime = Instant.parse(
                                json.get("serverTimestamp").getAsString()
                        );

                        double latencyMs = Duration.between(
                                serverTime, receiveTime
                        ).toNanos() / 1_000_000.0;

                        System.out.printf(
                                "resort=%s receivedAt=%s "
                                        + "serverTimestamp=%s "
                                        + "pushLatencyMs=%.3f%n",
                                resortID,
                                receiveTime,
                                serverTime,
                                latencyMs
                        );

                        received.countDown();
                    } catch (RuntimeException e) {
                        System.err.println(
                                "Invalid leaderboard message: " + e
                        );
                    }
                }

                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(
                    WebSocket webSocket,
                    Throwable error) {

                System.err.println("WebSocket error: " + error);
            }
        };

        long connectionStart = System.nanoTime();

        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), listener)
                .get(15, TimeUnit.SECONDS);

        double connectionMs =
                (System.nanoTime() - connectionStart) / 1_000_000.0;

        System.out.printf(
                "WebSocket establishment time: %.3f ms%n",
                connectionMs
        );

        try {
            if (!received.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out before receiving 10 valid updates"
                );
            }

            System.out.println("Received 10 valid leaderboard updates.");

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Check complete")
                    .get(5, TimeUnit.SECONDS);
        } finally {
            socket.abort();
        }
    }
}