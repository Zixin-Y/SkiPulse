import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

@ClientEndpoint
public class EchoClient {

    private final CountDownLatch responsesRemaining;

    public EchoClient(CountDownLatch latch) {
        this.responsesRemaining = latch;
    }

    @OnMessage
    public void onMessage(String message) {
        responsesRemaining.countDown();   // one echo received
    }

    public static void main(String[] args) throws Exception {
        int messages = 100;
        CountDownLatch latch = new CountDownLatch(messages);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        // Note: the client injects its own instance; pass state via a static or singleton
        Session session = container.connectToServer(
                new EchoClient(latch),
                URI.create("ws://35.91.255.103:8080/ski-servlet/echo"));

        long start = System.currentTimeMillis();
        for (int i = 0; i < messages; i++) {
            session.getBasicRemote().sendText("msg-" + i);
        }
        latch.await();                    // wait for all echoes
        long elapsed = System.currentTimeMillis() - start;

        System.out.println(messages + " round-trips in " + elapsed + " ms");
        session.close();
    }
}