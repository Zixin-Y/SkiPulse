import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import messaging.ApplicationLifecycleListener;
import messaging.RabbitMqConnectionManager;
import messaging.SwipePublisher;
import model.LiftRide;
import model.LiftVerticalLookup;
import model.ResponseMsg;
import model.SwipeMessage;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public class SkierServlet extends HttpServlet {

    private static final Logger LOGGER =
            Logger.getLogger(SkierServlet.class.getName());

    private final Gson gson = new Gson();

    private RabbitMqConnectionManager manager;
    private SwipePublisher publisher;
    private String serverId;

    // Retrieve the components created by the application listener.
    @Override
    public void init() throws ServletException {
        ServletContext context = getServletContext();

        manager = (RabbitMqConnectionManager) context.getAttribute(
                ApplicationLifecycleListener.MANAGER_ATTRIBUTE
        );

        publisher = (SwipePublisher) context.getAttribute(
                ApplicationLifecycleListener.PUBLISHER_ATTRIBUTE
        );

        serverId = (String) context.getAttribute(
                ApplicationLifecycleListener.SERVER_ID_ATTRIBUTE
        );

        if (manager == null || publisher == null || serverId == null) {
            throw new ServletException(
                    "Shared messaging components were not initialized"
            );
        }
    }

    // Keep the existing informational GET endpoint.
    @Override
    protected void doGet(
            HttpServletRequest req,
            HttpServletResponse res) throws IOException {

        String path = req.getPathInfo();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            writeMessage(res, 404, "missing parameters");
            return;
        }

        String[] parts = path.split("/");

        if (!isUrlValid(parts)) {
            writeMessage(res, 400, "invalid url");
            return;
        }

        writeMessage(
                res,
                200,
                String.format(
                        "GET request processed for skier %s at resort %s, "
                                + "season %s, day %s",
                        parts[7], parts[1], parts[3], parts[5]
                )
        );
    }

    // Validate a swipe and acknowledge HTTP success only after publishing.
    @Override
    protected void doPost(
            HttpServletRequest req,
            HttpServletResponse res) throws IOException {

        String path = req.getPathInfo();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            writeMessage(res, 400, "missing parameters");
            return;
        }

        String[] parts = path.split("/");

        if (!isUrlValid(parts)) {
            writeMessage(res, 400, "invalid url");
            return;
        }

        // Require a stable UUID across retries of the same logical event.
        String suppliedId = req.getHeader("X-Event-ID");
        String eventId;

        if (suppliedId == null || suppliedId.isBlank()) {
            writeMessage(res, 400, "missing X-Event-ID header");
            return;
        }

        suppliedId = suppliedId.trim();

        try {
            eventId = UUID.fromString(suppliedId).toString();

            if (!eventId.equalsIgnoreCase(suppliedId)) {
                throw new IllegalArgumentException(
                        "Noncanonical UUID format"
                );
            }
        } catch (IllegalArgumentException e) {
            writeMessage(res, 400, "X-Event-ID must be a valid UUID");
            return;
        }

        LiftRide ride;

        try {
            ride = gson.fromJson(req.getReader(), LiftRide.class);
        } catch (JsonSyntaxException e) {
            writeMessage(res, 400, "invalid JSON body");
            return;
        }

        if (ride == null || !isLiftRideValid(ride)) {
            writeMessage(res, 400, "invalid JSON body");
            return;
        }

        res.setHeader("X-Event-ID", eventId);

        if (!manager.isConnected()) {
            serviceUnavailable(res);
            return;
        }

        // Normalize numeric IDs before placing them in the event.
        String resortID = Integer.toString(Integer.parseInt(parts[1]));
        String dayID = Integer.toString(Integer.parseInt(parts[5]));
        String skierID = Integer.toString(Integer.parseInt(parts[7]));

        SwipeMessage message = new SwipeMessage(
                eventId,
                resortID,
                dayID,
                skierID,
                Integer.toString(ride.getLiftID()),
                LiftVerticalLookup.getVertical(ride.getLiftID()),
                ride.getTime(),
                Instant.now().toString(),
                serverId
        );

        try {
            publisher.publish(message);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Swipe publishing was interrupted");
            serviceUnavailable(res);
            return;

        } catch (IOException | TimeoutException | IllegalStateException e) {
            LOGGER.warning(
                    "Swipe publishing failed: "
                            + e.getClass().getSimpleName()
            );
            serviceUnavailable(res);
            return;
        }

        writeMessage(
                res,
                201,
                "Swipe accepted for processing; eventId=" + eventId
        );
    }

    // Check path structure and the supported resort range.
    private boolean isUrlValid(String[] parts) {
        if (parts.length != 8) {
            return false;
        }

        if (!"seasons".equals(parts[2])
                || !"days".equals(parts[4])
                || !"skiers".equals(parts[6])) {
            return false;
        }

        try {
            int resortID = Integer.parseInt(parts[1]);
            int seasonID = Integer.parseInt(parts[3]);
            int dayID = Integer.parseInt(parts[5]);
            int skierID = Integer.parseInt(parts[7]);

            return resortID >= 1
                    && resortID <= 10
                    && seasonID > 0
                    && dayID > 0
                    && skierID > 0;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Preserve the original request-body limits.
    private boolean isLiftRideValid(LiftRide ride) {
        return ride.getTime() >= 1
                && ride.getTime() <= 360
                && ride.getLiftID() >= 1
                && ride.getLiftID() <= 40;
    }

    // Tell clients that this request could not be confirmed successfully.
    private void serviceUnavailable(HttpServletResponse res)
            throws IOException {

        res.setHeader("Retry-After", "1");

        writeMessage(
                res,
                503,
                "Publishing unavailable; retry with the same X-Event-ID"
        );
    }

    // Produce consistent JSON responses.
    private void writeMessage(
            HttpServletResponse res,
            int status,
            String message) throws IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setStatus(status);
        res.getWriter().write(gson.toJson(new ResponseMsg(message)));
    }
}