package messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class RabbitMqConnectionManager implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(RabbitMqConnectionManager.class.getName());

    private static final long CHECK_DELAY_SECONDS = 5;

    private final ConnectionFactory factory;
    private final int poolSize;

    // Use one background worker to prevent concurrent recovery attempts.
    private final ScheduledExecutorService recoveryExecutor =
            Executors.newSingleThreadScheduledExecutor();

    // Publish the connection and its pool together as one resource group.
    private volatile Resources current;
    private volatile boolean closed;
    private boolean started;

    // Configure connection settings without opening a connection.
    public RabbitMqConnectionManager(RabbitMqConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        poolSize = config.getChannelPoolSize();

        factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());

        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        factory.setRequestedHeartbeat(30);

        // Let this manager coordinate connection and pool recovery.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
    }

    // Start background initialization and subsequent recovery checks.
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Connection manager has already been closed"
            );
        }

        if (started) {
            return;
        }

        started = true;

        recoveryExecutor.scheduleWithFixedDelay(
                this::maintainResources,
                0,
                CHECK_DELAY_SECONDS,
                TimeUnit.SECONDS
        );
    }

    // Return one specific pool so channels can be returned to that same pool.
    public ChannelPool getChannelPool() {
        Resources snapshot = current;

        if (closed || snapshot == null || !snapshot.pool.isHealthy()) {
            throw new IllegalStateException(
                    "RabbitMQ resources are not ready"
            );
        }

        return snapshot.pool;
    }

    // Create a long-lived subscription channel on the shared connection.
    public Channel createSubscriptionChannel() throws IOException {
        Resources snapshot = current;

        if (closed || snapshot == null || !snapshot.pool.isHealthy()) {
            throw new IOException("RabbitMQ resources are not ready");
        }

        Channel channel = snapshot.connection.createChannel();

        if (channel == null) {
            throw new IOException("No subscription channel is available");
        }

        return channel;
    }

    // Report readiness of both the connection and its channel pool.
    public boolean isConnected() {
        Resources snapshot = current;

        return !closed
                && snapshot != null
                && snapshot.pool.isHealthy();
    }

    // Keep network operations outside the manager's lifecycle lock.
    private void maintainResources() {
        if (closed) {
            return;
        }

        Resources snapshot = current;

        if (snapshot != null && snapshot.pool.isHealthy()) {
            return;
        }

        synchronized (this) {
            if (closed) {
                return;
            }

            current = null;
        }

        if (snapshot != null) {
            snapshot.close();
        }

        Connection candidateConnection = null;
        ChannelPool candidatePool = null;
        boolean installed = false;

        try {
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            // Declare topology before making the connection and pool available.
            RabbitMqTopology.declare(candidateConnection);

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);

            Resources replacement =
                    new Resources(candidateConnection, candidatePool);

            synchronized (this) {
                if (!closed) {
                    current = replacement;
                    installed = true;
                }
            }

            if (installed) {
                LOGGER.info("RabbitMQ connection and channel pool are ready");
            }

        } catch (IOException | TimeoutException | RuntimeException e) {
            if (!closed) {
                LOGGER.warning(
                        "RabbitMQ initialization or recovery failed: "
                                + e.getClass().getSimpleName()
                                + "; another attempt will follow"
                );
            }
        } finally {
            // Clean up partial initialization or a shutdown race.
            if (!installed) {
                if (candidatePool != null) {
                    candidatePool.close();
                }

                abortConnection(candidateConnection);
            }
        }
    }

    // Stop future recovery and detach the current resource group.
    @Override
    public void close() {
        Resources detached;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
            detached = current;
            current = null;
        }

        recoveryExecutor.shutdownNow();

        if (detached != null) {
            detached.close();
        }
    }

    // Release a connection even when normal communication is unavailable.
    private static void abortConnection(Connection connection) {
        if (connection == null) {
            return;
        }

        try {
            connection.abort();
        } catch (RuntimeException e) {
            LOGGER.warning("Could not abort a RabbitMQ connection");
        }
    }

    // Keep each connection paired with the pool created from it.
    private static final class Resources {
        private final Connection connection;
        private final ChannelPool pool;

        private Resources(Connection connection, ChannelPool pool) {
            this.connection = connection;
            this.pool = pool;
        }

        private void close() {
            pool.close();
            abortConnection(connection);
        }
    }
}