import com.google.gson.JsonObject;
import messaging.ApplicationLifecycleListener;
import messaging.LeaderboardSubscriber;
import messaging.RabbitMqConnectionManager;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(
            HttpServletRequest req,
            HttpServletResponse res) throws IOException {

        ServletContext context = getServletContext();

        RabbitMqConnectionManager manager =
                (RabbitMqConnectionManager) context.getAttribute(
                        ApplicationLifecycleListener.MANAGER_ATTRIBUTE);

        LeaderboardSubscriber subscriber =
                (LeaderboardSubscriber) context.getAttribute(
                        ApplicationLifecycleListener.SUBSCRIBER_ATTRIBUTE);

        // Inspect local readiness instead of opening new connections.
        boolean publishReady =
                manager != null && manager.isConnected();

        boolean backplaneReady =
                subscriber != null && subscriber.isReady();

        boolean ready = publishReady && backplaneReady;

        JsonObject body = new JsonObject();
        body.addProperty("status", ready ? "UP" : "NOT_READY");
        body.addProperty("publishReady", publishReady);
        body.addProperty("backplaneReady", backplaneReady);

        Object serverId = context.getAttribute(
                ApplicationLifecycleListener.SERVER_ID_ATTRIBUTE);

        if (serverId instanceof String) {
            body.addProperty("serverId", (String) serverId);
        }

        res.setStatus(ready
                ? HttpServletResponse.SC_OK
                : HttpServletResponse.SC_SERVICE_UNAVAILABLE);

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write(body.toString());
    }
}