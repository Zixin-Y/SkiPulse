package model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class LiftVerticalLookup {

    private static final String RESOURCE_PATH = "/lift-vertical.json";
    private static final int LIFT_COUNT = 40;

    // Load the table once when this class is initialized.
    private static final int[] VERTICAL_BY_LIFT = loadTable();

    // This utility class does not need instances.
    private LiftVerticalLookup() {
    }

    // Return the vertical feet for one valid lift ID.
    public static int getVertical(int liftID) {
        if (liftID < 1 || liftID > LIFT_COUNT) {
            throw new IllegalArgumentException(
                    "liftID must be between 1 and 40"
            );
        }

        return VERTICAL_BY_LIFT[liftID];
    }

    // Read and validate the bundled table before making it available.
    private static int[] loadTable() {
        InputStream stream =
                LiftVerticalLookup.class.getResourceAsStream(RESOURCE_PATH);

        if (stream == null) {
            throw new IllegalStateException(
                    "Missing resource: " + RESOURCE_PATH
            );
        }

        try (Reader reader = new InputStreamReader(
                stream, StandardCharsets.UTF_8)) {

            JsonObject root =
                    JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject lifts = root.getAsJsonObject("lifts");

            if (lifts == null || lifts.size() != LIFT_COUNT) {
                throw new IllegalStateException(
                        "The lift table must contain exactly 40 lifts"
                );
            }

            int[] table = new int[LIFT_COUNT + 1];

            for (int liftID = 1; liftID <= LIFT_COUNT; liftID++) {
                JsonElement value = lifts.get(Integer.toString(liftID));

                if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()) {

                    throw new IllegalStateException(
                            "Missing or nonnumeric vertical for lift " + liftID
                    );
                }

                int vertical = value.getAsBigDecimal().intValueExact();

                if (vertical < 60 || vertical > 400) {
                    throw new IllegalStateException(
                            "Vertical must be between 60 and 400 for lift "
                                    + liftID
                    );
                }

                table[liftID] = vertical;
            }

            return table;

        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to load a valid lift vertical table",
                    e
            );
        }
    }
}