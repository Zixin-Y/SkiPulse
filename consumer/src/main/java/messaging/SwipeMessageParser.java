package messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import model.SwipeMessage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class SwipeMessageParser {

    private SwipeMessageParser() {
    }

    // Parse and validate an event delivered by a specific resort queue.
    public static SwipeMessage parse(String json, int expectedResortID) {
        if (expectedResortID < 1 || expectedResortID > 10) {
            throw new IllegalArgumentException(
                    "Expected resort ID must be between 1 and 10"
            );
        }

        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }

        JsonElement parsed;

        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid JSON message", e);
        }

        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Message must be a JSON object"
            );
        }

        JsonObject root = parsed.getAsJsonObject();

        String suppliedId = requiredString(root, "eventId");
        String eventId = UUID.fromString(suppliedId).toString();

        if (!eventId.equalsIgnoreCase(suppliedId)) {
            throw new IllegalArgumentException(
                    "eventId must use the standard UUID format"
            );
        }

        String resortID = numericId(root, "resortID", 10);
        String dayID = numericId(root, "dayID", Integer.MAX_VALUE);
        String skierID = numericId(root, "skierID", Integer.MAX_VALUE);
        String liftID = numericId(root, "liftID", 40);

        if (Integer.parseInt(resortID) != expectedResortID) {
            throw new IllegalArgumentException(
                    "Message resortID does not match its queue"
            );
        }

        int vertical = requiredInt(root, "vertical", 60, 400);
        int time = requiredInt(root, "time", 1, 360);

        String timestamp = requiredString(root, "timestamp");

        try {
            Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "timestamp must be a valid UTC instant", e
            );
        }

        String serverId = requiredString(root, "serverId");

        return new SwipeMessage(
                eventId, resortID, dayID, skierID, liftID,
                vertical, time, timestamp, serverId
        );
    }

    // Reject missing, blank, or non-string fields.
    private static String requiredString(JsonObject root, String name) {
        JsonElement value = root.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {

            throw new IllegalArgumentException(
                    name + " must be a nonblank string"
            );
        }

        return value.getAsString();
    }

    // Validate numeric string IDs and normalize their representation.
    private static String numericId(
            JsonObject root, String name, int maximum) {

        String value = requiredString(root, name);
        int number = Integer.parseInt(value);

        if (number < 1 || number > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside the supported range"
            );
        }

        return Integer.toString(number);
    }

    // Require an exact integer rather than silently truncating decimals.
    private static int requiredInt(
            JsonObject root, String name, int minimum, int maximum) {

        JsonElement value = root.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {

            throw new IllegalArgumentException(
                    name + " must be a JSON number"
            );
        }

        int number;

        try {
            number = value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " must be an exact integer", e
            );
        }

        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside the supported range"
            );
        }

        return number;
    }
}