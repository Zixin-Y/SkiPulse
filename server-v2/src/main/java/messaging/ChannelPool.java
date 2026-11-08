package messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class ChannelPool implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(ChannelPool.class.getName());

    // Wait for an available channel for at most five seconds.
    private static final long BORROW_TIMEOUT_SECONDS = 5;

    // Every channel in this pool belongs to the same connection.
    private final Connection connection;
    private final BlockingQueue<Channel> available;
    private final List<Channel> allChannels = new ArrayList<>();

    // Track borrowed channels by object identity.
    private final Set<Channel> borrowed =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean closed;

    // Pre-create channels once instead of creating one per publish.
    public ChannelPool(Connection connection, int poolSize)
            throws IOException {

        this.connection = Objects.requireNonNull(
                connection, "connection must not be null"
        );

        if (poolSize <= 0) {
            throw new IllegalArgumentException(
                    "poolSize must be positive"
            );
        }

        available = new ArrayBlockingQueue<>(poolSize);

        try {
            for (int i = 0; i < poolSize; i++) {
                Channel channel = connection.createChannel();

                if (channel == null) {
                    throw new IOException(
                            "RabbitMQ could not allocate another channel"
                    );
                }

                allChannels.add(channel);

                // Enable publisher confirms before the channel is borrowed.
                channel.confirmSelect();

                available.add(channel);
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // Remove one idle channel so another borrower cannot receive it.
    public Channel borrowChannel()
            throws IOException, InterruptedException, TimeoutException {

        synchronized (this) {
            ensureAvailable();
        }

        // Do not hold the pool lock while waiting for a returned channel.
        Channel channel = available.poll(
                BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS
        );

        synchronized (this) {
            ensureAvailable();

            if (channel == null) {
                throw new TimeoutException(
                        "Timed out waiting for a RabbitMQ channel"
                );
            }

            if (!channel.isOpen()) {
                throw new IOException(
                        "Borrowed channel is closed; pool repair is required"
                );
            }

            borrowed.add(channel);
            return channel;
        }
    }

    // Return a channel only after its publish operation has completed.
    public synchronized void returnChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel must not be null");

        if (!borrowed.remove(channel)) {
            throw new IllegalArgumentException(
                    "Channel was not borrowed from this pool or was already returned"
            );
        }

        if (closed || !connection.isOpen() || !channel.isOpen()) {
            abortQuietly(channel);
            return;
        }

        if (!available.offer(channel)) {
            abortQuietly(channel);

            throw new IllegalStateException(
                    "Channel pool capacity invariant was violated"
            );
        }
    }

    // Check lifecycle state before lending a channel.
    private void ensureAvailable() throws IOException {
        if (closed) {
            throw new IllegalStateException(
                    "Channel pool has already been closed"
            );
        }

        if (!connection.isOpen()) {
            throw new IOException(
                    "RabbitMQ connection is not available"
            );
        }
    }

    // Check every owned channel, including channels currently borrowed.
    public synchronized boolean isHealthy() {
        if (closed || !connection.isOpen()) {
            return false;
        }

        for (Channel channel : allChannels) {
            if (!channel.isOpen()) {
                return false;
            }
        }

        return true;
    }

    // Close all channels owned by this pool, but keep connection ownership separate.
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        available.clear();

        for (Channel channel : allChannels) {
            abortQuietly(channel);
        }
    }

    // Release a channel without preventing cleanup of other channels.
    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Could not abort a RabbitMQ channel");
        }
    }
}