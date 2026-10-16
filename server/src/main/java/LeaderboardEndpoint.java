import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint implements ServletContextListener {

    private static final Map<String, Set<Session>> subscribers =
            new ConcurrentHashMap<>();

    private static ScheduledExecutorService pusher;

    // Start the timer when Tomcat loads this web application.
    @Override
    public void contextInitialized(ServletContextEvent event) {
        pusher = Executors.newSingleThreadScheduledExecutor();

        pusher.scheduleAtFixedRate(
                LeaderboardEndpoint::pushAll,
                500,
                500,
                TimeUnit.MILLISECONDS
        );
    }

    // Stop the timer when the application is stopped or redeployed.
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (pusher != null) {
            pusher.shutdownNow();
        }
        subscribers.clear();
    }

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("resortID") String resortID) {

        subscribers.computeIfAbsent(
                resortID,
                key -> ConcurrentHashMap.newKeySet()
        ).add(session);
    }

    @OnClose
    public void onClose(Session session) {
        removeSession(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        System.err.println("Leaderboard error: " + error.getMessage());
        removeSession(session);

        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (java.io.IOException e) {
                System.err.println("Close failed: " + e.getMessage());
            }
        }
    }

    private static void removeSession(Session session) {
        if (session == null) {
            return;
        }

        String resortID = session.getPathParameters().get("resortID");
        Set<Session> sessions = subscribers.get(resortID);

        if (sessions != null) {
            sessions.remove(session);
        }
    }

    private static void pushAll() {
        for (Map.Entry<String, Set<Session>> entry
                : subscribers.entrySet()) {

            if (entry.getValue().isEmpty()) {
                continue;
            }

            String json = syntheticLeaderboard(entry.getKey());

            for (Session session : entry.getValue()) {
                if (!session.isOpen()) {
                    removeSession(session);
                    continue;
                }

                try {
                    session.getAsyncRemote().sendText(json, result -> {
                        if (!result.isOK()) {
                            removeSession(session);
                        }
                    });
                } catch (RuntimeException e) {
                    removeSession(session);
                }
            }
        }
    }

    private static String syntheticLeaderboard(String resortID) {
        JsonObject message = new JsonObject();
        message.addProperty("resortID", resortID);
        message.addProperty("serverTimestamp", Instant.now().toString());

        JsonArray top = new JsonArray();

        JsonObject first = new JsonObject();
        first.addProperty("rank", 1);
        first.addProperty("skierID", 40213);
        first.addProperty("vertical", 44120);
        top.add(first);

        JsonObject second = new JsonObject();
        second.addProperty("rank", 2);
        second.addProperty("skierID", 11872);
        second.addProperty("vertical", 43880);
        top.add(second);

        message.add("top", top);
        return message.toString();
    }
}