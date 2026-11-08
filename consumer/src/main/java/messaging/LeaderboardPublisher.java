package messaging;

import aggregation.LeaderboardAggregator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class LeaderboardPublisher implements AutoCloseable {

    public static final String EXCHANGE = "leaderboard.fanout";

    private final Channel channel;
    private final String sourceId;
    private final Gson gson = new Gson();

    private boolean closed;

    public LeaderboardPublisher(
            Connection connection, String sourceId) throws IOException {

        Objects.requireNonNull(connection, "connection must not be null");

        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceId must not be blank");
        }

        this.sourceId = sourceId;

        Channel candidate = connection.createChannel();

        if (candidate == null) {
            throw new IOException("No publishing channel is available");
        }

        try {
            // Declare the backplane exchange and enable publisher confirms.
            candidate.exchangeDeclare(EXCHANGE, "fanout", true);
            candidate.confirmSelect();
        } catch (IOException | RuntimeException exception) {
            abortQuietly(candidate);
            throw exception;
        }

        this.channel = candidate;
    }

    public synchronized void publish(
            SwipeMessage message,
            LeaderboardAggregator.Result result)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(result, "result must not be null");

        if (closed || !channel.isOpen()) {
            throw new IOException(
                    "The leaderboard publishing channel is closed");
        }

        // Build a complete snapshot for one resort and day.
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "leaderboard");
        payload.addProperty("resortID", message.getResortID());
        payload.addProperty("dayID", message.getDayID());
        payload.addProperty("sourceId", sourceId);
        payload.addProperty("version", result.getVersion());
        payload.addProperty("timestamp", Instant.now().toString());
        payload.add(
                "entries", gson.toJsonTree(result.getLeaderboard()));

        byte[] body = gson.toJson(payload)
                .getBytes(StandardCharsets.UTF_8);

        AMQP.BasicProperties properties =
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("UTF-8")
                        .deliveryMode(2)
                        .type("leaderboard")
                        .build();

        try {
            // Fanout exchanges route by bindings rather than routing keys.
            channel.basicPublish(
                    EXCHANGE,
                    "",
                    false,
                    properties,
                    body);

            channel.waitForConfirmsOrDie(5000);
        } catch (IOException
                 | InterruptedException
                 | TimeoutException
                 | RuntimeException exception) {

            // Discard a channel whose publishing outcome is uncertain.
            closed = true;
            abortQuietly(channel);
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        abortQuietly(channel);
    }

    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (Exception ignored) {
            // The channel may already be closed.
        }
    }
}