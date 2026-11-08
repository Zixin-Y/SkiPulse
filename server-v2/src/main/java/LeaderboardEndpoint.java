import leaderboard.LeaderboardStore;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint implements ServletContextListener {

    private static final Map<Session, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    private static ScheduledExecutorService pusher;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        pusher = Executors.newSingleThreadScheduledExecutor();

        // Check for changed snapshots without generating synthetic data.
        pusher.scheduleWithFixedDelay(
                LeaderboardEndpoint::pushUpdates,
                50,
                50,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (pusher != null) {
            pusher.shutdownNow();
        }

        subscriptions.clear();
        LeaderboardStore.clear();
    }

    @OnOpen
    public void onOpen(
            Session session,
            @PathParam("resortID") String resortID) throws IOException {

        final String normalizedResort;
        final String dayID;

        try {
            int resort = Integer.parseInt(resortID);

            if (resort < 1 || resort > 10) {
                throw new IllegalArgumentException("Invalid resort ID");
            }

            normalizedResort = Integer.toString(resort);

            List<String> days =
                    session.getRequestParameterMap().get("dayID");

            int day = days == null || days.isEmpty()
                    ? 1
                    : Integer.parseInt(days.get(0));

            if (day <= 0) {
                throw new IllegalArgumentException("Invalid day ID");
            }

            dayID = Integer.toString(day);
        } catch (IllegalArgumentException exception) {
            session.close(new CloseReason(
                    CloseReason.CloseCodes.VIOLATED_POLICY,
                    "Invalid resortID or dayID"));
            return;
        }

        session.getAsyncRemote().setSendTimeout(5000);

        subscriptions.put(
                session,
                new Subscription(normalizedResort, dayID));
    }

    @OnClose
    public void onClose(Session session) {
        subscriptions.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            disconnect(session);
        }
    }

    private static void pushUpdates() {
        for (Map.Entry<Session, Subscription> entry
                : subscriptions.entrySet()) {

            Session session = entry.getKey();
            Subscription subscription = entry.getValue();

            if (!session.isOpen()) {
                subscriptions.remove(session);
                continue;
            }

            LeaderboardStore.Snapshot snapshot =
                    LeaderboardStore.get(
                            subscription.resortID,
                            subscription.dayID);

            // Send each cached snapshot at most once after successful delivery.
            if (snapshot == null
                    || snapshot == subscription.lastSent
                    || !subscription.sending.compareAndSet(false, true)) {
                continue;
            }

            try {
                session.getAsyncRemote().sendText(
                        snapshot.getJson(),
                        result -> {
                            if (result.isOK()) {
                                subscription.lastSent = snapshot;
                            } else {
                                disconnect(session);
                            }

                            subscription.sending.set(false);
                        });
            } catch (RuntimeException exception) {
                subscription.sending.set(false);
                disconnect(session);
            }
        }
    }

    private static void disconnect(Session session) {
        subscriptions.remove(session);

        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException ignored) {
            // The connection may already be unavailable.
        }
    }

    private static final class Subscription {
        private final String resortID;
        private final String dayID;
        private final AtomicBoolean sending = new AtomicBoolean();

        private volatile LeaderboardStore.Snapshot lastSent;

        private Subscription(String resortID, String dayID) {
            this.resortID = resortID;
            this.dayID = dayID;
        }
    }
}