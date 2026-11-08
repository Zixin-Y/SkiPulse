package messaging;

import model.LiftVerticalLookup;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.UUID;
import java.util.logging.Logger;

public final class ApplicationLifecycleListener
        implements ServletContextListener {

    private static final Logger LOGGER =
            Logger.getLogger(ApplicationLifecycleListener.class.getName());

    // Use shared attribute names when storing and retrieving components.
    public static final String MANAGER_ATTRIBUTE =
            RabbitMqConnectionManager.class.getName();

    public static final String PUBLISHER_ATTRIBUTE =
            SwipePublisher.class.getName();

    public static final String SUBSCRIBER_ATTRIBUTE =
            LeaderboardSubscriber.class.getName();

    public static final String SERVER_ID_ATTRIBUTE =
            ApplicationLifecycleListener.class.getName() + ".serverId";

    private RabbitMqConnectionManager manager;
    private LeaderboardSubscriber subscriber;

    // Initialize shared components once per web application startup.
    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();

        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        // Fail initialization early if the bundled lift table is invalid.
        LiftVerticalLookup.getVertical(1);

        String serverId = System.getenv("SERVER_ID");

        if (serverId == null || serverId.isBlank()) {
            serverId = "ingest-" + UUID.randomUUID();
        }

        manager = new RabbitMqConnectionManager(config);

        try {
            SwipePublisher publisher = new SwipePublisher(manager);

            context.setAttribute(MANAGER_ATTRIBUTE, manager);
            context.setAttribute(PUBLISHER_ATTRIBUTE, publisher);
            context.setAttribute(SERVER_ID_ATTRIBUTE, serverId);

            // Start initialization and recovery on the background worker.
            manager.start();
            // Subscribe to leaderboard updates using the shared connection manager.
            subscriber = new LeaderboardSubscriber(manager);
            context.setAttribute(SUBSCRIBER_ATTRIBUTE, subscriber);
            subscriber.start();

            LOGGER.info(
                    "Messaging initialization scheduled for server " + serverId
            );

        } catch (RuntimeException e) {
            if (subscriber != null) {
                subscriber.close();
            }

            removeAttributes(context);
            manager.close();
            throw e;
        }
    }

    // Release resources when the application stops or is redeployed.
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        removeAttributes(event.getServletContext());

        // Stop the subscription before closing its shared connection.
        if (subscriber != null) {
            subscriber.close();
        }

        if (manager != null) {
            manager.close();
        }

        LOGGER.info("Messaging resources closed");
    }

    // Remove references to components owned by this application.
    private static void removeAttributes(ServletContext context) {
        context.removeAttribute(PUBLISHER_ATTRIBUTE);
        context.removeAttribute(MANAGER_ATTRIBUTE);
        context.removeAttribute(SERVER_ID_ATTRIBUTE);
        context.removeAttribute(SUBSCRIBER_ATTRIBUTE);
    }
}