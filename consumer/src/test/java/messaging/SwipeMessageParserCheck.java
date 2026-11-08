package messaging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import model.SwipeMessage;

public final class SwipeMessageParserCheck {

    public static void main(String[] args) {
        Gson gson = new Gson();

        // Build a known valid event without connecting to RabbitMQ.
        SwipeMessage original = new SwipeMessage(
                "6ca7b201-c51b-4c1b-8d6b-d9799b2a4001",
                "1",
                "1",
                "1001",
                "1",
                167,
                120,
                "2026-11-07T12:00:00Z",
                "parser-check"
        );

        JsonObject valid = gson.toJsonTree(original).getAsJsonObject();

        SwipeMessage result =
                SwipeMessageParser.parse(valid.toString(), 1);

        if (!gson.toJsonTree(result).equals(valid)) {
            throw new AssertionError(
                    "Parsed event does not match the original event"
            );
        }

        System.out.println("PASS: a valid event preserves all fields.");

        JsonObject missingId = valid.deepCopy();
        missingId.remove("eventId");
        expectRejected(missingId.toString(), 1, "missing eventId");

        JsonObject invalidUuid = valid.deepCopy();
        invalidUuid.addProperty("eventId", "not-a-uuid");
        expectRejected(invalidUuid.toString(), 1, "invalid UUID");

        expectRejected(valid.toString(), 2, "resort and queue mismatch");

        JsonObject decimalVertical = valid.deepCopy();
        decimalVertical.addProperty("vertical", 167.5);
        expectRejected(decimalVertical.toString(), 1, "decimal vertical");

        JsonObject stringVertical = valid.deepCopy();
        stringVertical.addProperty("vertical", "167");
        expectRejected(stringVertical.toString(), 1, "string vertical");

        JsonObject invalidLift = valid.deepCopy();
        invalidLift.addProperty("liftID", "41");
        expectRejected(invalidLift.toString(), 1, "invalid lift ID");

        JsonObject invalidTime = valid.deepCopy();
        invalidTime.addProperty("time", 361);
        expectRejected(invalidTime.toString(), 1, "invalid swipe time");

        JsonObject invalidTimestamp = valid.deepCopy();
        invalidTimestamp.addProperty("timestamp", "yesterday");
        expectRejected(invalidTimestamp.toString(), 1, "invalid timestamp");

        expectRejected("[]", 1, "non-object message");
        expectRejected("{", 1, "malformed JSON");

        System.out.println("All swipe message parser checks passed.");
    }

    // Fail if invalid input is accepted; let unexpected exceptions surface.
    private static void expectRejected(
            String json, int resortID, String label) {

        try {
            SwipeMessageParser.parse(json, resortID);
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: rejected " + label + ".");
            return;
        }

        throw new AssertionError(
                "Expected rejection: " + label
        );
    }
}