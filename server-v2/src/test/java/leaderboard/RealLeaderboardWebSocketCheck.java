package leaderboard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RealLeaderboardWebSocketCheck {

    public static void main(String[] args) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                System.out.println(
                        "CONNECTED: resort=1, day=80. Send test swipes now."
                );

                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(
                    WebSocket webSocket,
                    CharSequence data,
                    boolean last) {

                // Reassemble messages that arrive in multiple frames.
                message.append(data);

                if (last) {
                    System.out.println("RECEIVED: " + message);
                    message.setLength(0);
                }

                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(
                    WebSocket webSocket,
                    int statusCode,
                    String reason) {

                System.out.println(
                        "CLOSED: " + statusCode + " " + reason);
                finished.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                System.err.println("WebSocket failed: " + error);
                finished.countDown();
            }
        };

        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://cs6650-a2-alb-549848062.us-west-2.elb.amazonaws.com/"
                                        + "ski-servlet-v2/leaderboard/1?dayID=80"),
                        listener)
                .join();

        try {
            // Keep the connection open while test swipes are sent manually.
            finished.await(10, TimeUnit.MINUTES);
        } finally {
            socket.abort();
        }
    }
}