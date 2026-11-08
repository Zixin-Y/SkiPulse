package leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ConcurrentHashMap;

public final class LeaderboardStore {

    // Cache snapshots independently for each resort and day.
    private static final ConcurrentHashMap<String, Snapshot> snapshots =
            new ConcurrentHashMap<>();

    private LeaderboardStore() {
    }

    public static Snapshot accept(String json) {
        JsonObject incoming =
                JsonParser.parseString(json).getAsJsonObject();

        String resortID = incoming.get("resortID").getAsString();
        String dayID = incoming.get("dayID").getAsString();
        String sourceId = incoming.get("sourceId").getAsString();
        long version = incoming.get("version").getAsLong();

        JsonObject outgoing = new JsonObject();
        outgoing.addProperty("resortID", resortID);
        outgoing.addProperty("dayID", dayID);
        outgoing.addProperty("sourceId", sourceId);
        outgoing.addProperty("version", version);
        outgoing.addProperty(
                "serverTimestamp",
                incoming.get("timestamp").getAsString());

        // Preserve the WebSocket message format used by Assignment 1.
        JsonArray top = new JsonArray();
        int rank = 1;

        for (JsonElement element
                : incoming.getAsJsonArray("entries")) {

            JsonObject entry = element.getAsJsonObject();
            JsonObject item = new JsonObject();

            item.addProperty("rank", rank++);
            item.addProperty(
                    "skierID",
                    Integer.parseInt(entry.get("skierID").getAsString()));
            item.addProperty(
                    "vertical",
                    entry.get("totalVertical").getAsLong());

            top.add(item);
        }

        outgoing.add("top", top);

        Snapshot candidate = new Snapshot(
                resortID,
                dayID,
                sourceId,
                version,
                outgoing.toString());

        String key = resortID + ":" + dayID;

        // Ignore duplicate or older versions from the same source.
        return snapshots.compute(key, (ignored, existing) -> {
            if (existing != null
                    && existing.sourceId.equals(sourceId)
                    && existing.version >= version) {
                return existing;
            }

            return candidate;
        });
    }

    public static Snapshot get(String resortID, String dayID) {
        return snapshots.get(resortID + ":" + dayID);
    }

    public static void clear() {
        snapshots.clear();
    }

    public static final class Snapshot {
        private final String resortID;
        private final String dayID;
        private final String sourceId;
        private final long version;
        private final String json;

        private Snapshot(
                String resortID,
                String dayID,
                String sourceId,
                long version,
                String json) {

            this.resortID = resortID;
            this.dayID = dayID;
            this.sourceId = sourceId;
            this.version = version;
            this.json = json;
        }

        public String getResortID() {
            return resortID;
        }

        public String getDayID() {
            return dayID;
        }

        public String getSourceId() {
            return sourceId;
        }

        public long getVersion() {
            return version;
        }

        public String getJson() {
            return json;
        }
    }
}