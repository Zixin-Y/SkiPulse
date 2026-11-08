import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadEchoClient {

    public static void main(String[] args) throws InterruptedException {
        int numThreads = 128;
        int messagesPerThread = 100;

        String url = "ws://35.91.255.103:8080/ski-servlet/echo";

        CountDownLatch threadsDone = new CountDownLatch(numThreads);
        AtomicInteger successfulConnections = new AtomicInteger();
        AtomicInteger receivedMessages = new AtomicInteger();

        long start = System.nanoTime();

        for (int t = 0; t < numThreads; t++) {
            final int clientId = t;

            new Thread(() -> {
                Session session = null;
                CountDownLatch echoes =
                        new CountDownLatch(messagesPerThread);

                try {
                    WebSocketContainer container =
                            ContainerProvider.getWebSocketContainer();

                    session = container.connectToServer(
                            new EchoClient(echoes),
                            URI.create(url)
                    );

                    for (int i = 0; i < messagesPerThread; i++) {
                        session.getBasicRemote().sendText(
                                "client-" + clientId + "-msg-" + i
                        );
                    }

                    boolean complete = echoes.await(30, TimeUnit.SECONDS);

                    if (complete) {
                        successfulConnections.incrementAndGet();
                    } else {
                        System.err.println(
                                "Client " + clientId + ": reply timeout"
                        );
                    }

                } catch (Exception e) {
                    System.err.println(
                            "Client " + clientId + ": " + e
                    );
                } finally {
                    if (session != null && session.isOpen()) {
                        try {
                            session.close();
                        } catch (Exception e) {
                            System.err.println("Close failed: " + e);
                        }
                    }

                    receivedMessages.addAndGet(
                            messagesPerThread - (int) echoes.getCount()
                    );
                    threadsDone.countDown();
                }
            }).start();
        }

        threadsDone.await();

        double elapsedSeconds =
                (System.nanoTime() - start) / 1_000_000_000.0;

        System.out.println("Connections: " + numThreads);
        System.out.println(
                "Successful connections: "
                        + successfulConnections.get() + "/" + numThreads
        );
        System.out.println(
                "Replies received: " + receivedMessages.get()
                        + "/" + (numThreads * messagesPerThread)
        );
        System.out.printf("Total time: %.3f seconds%n", elapsedSeconds);
        System.out.printf(
                "Throughput: %.2f replies/second%n",
                receivedMessages.get() / elapsedSeconds
        );
    }
}