package aggregation;

import model.SwipeMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LeaderboardAggregator {

    // Rank by total vertical descending, then skier ID ascending.
    private static final Comparator<Entry> RANKING =
            Comparator.comparingLong(Entry::getTotalVertical)
                    .reversed()
                    .thenComparingLong(
                            entry -> Long.parseLong(entry.getSkierID()));

    private final int topN;

    // Keep independent aggregation state for each resort and day.
    private final ConcurrentHashMap<String, State> states =
            new ConcurrentHashMap<>();

    // Detect duplicate event IDs and conflicting business data.
    private final ConcurrentHashMap<String, String> processedEvents =
            new ConcurrentHashMap<>();

    private final AtomicLong swipesProcessed = new AtomicLong();

    public LeaderboardAggregator(int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be positive");
        }
        this.topN = topN;
    }

    public Result aggregate(SwipeMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        String key = message.getResortID() + ":" + message.getDayID();
        String fingerprint = fingerprint(message);
        State state = states.computeIfAbsent(key, ignored -> new State());

        // Serialize updates within one resort and day.
        synchronized (state) {
            String existing =
                    processedEvents.get(message.getEventId());

            if (existing != null) {
                verifyFingerprint(existing, fingerprint);
                return new Result(
                        false, false, state.version, snapshot(state));
            }

            long oldTotal =
                    state.totals.getOrDefault(message.getSkierID(), 0L);
            long newTotal =
                    Math.addExact(oldTotal, message.getVertical());

            // Claim the event ID before applying its business data.
            existing = processedEvents.putIfAbsent(
                    message.getEventId(), fingerprint);

            if (existing != null) {
                verifyFingerprint(existing, fingerprint);
                return new Result(
                        false, false, state.version, snapshot(state));
            }

            List<Entry> before = snapshot(state);

            // Replace the skier's previous ranking entry.
            if (oldTotal != 0L) {
                state.ranking.remove(
                        new Entry(message.getSkierID(), oldTotal));
            }

            state.totals.put(message.getSkierID(), newTotal);
            state.ranking.add(
                    new Entry(message.getSkierID(), newTotal));

            List<Entry> after = snapshot(state);
            boolean changed = !before.equals(after);

            if (changed) {
                state.version++;
            }

            swipesProcessed.incrementAndGet();

            return new Result(
                    true, changed, state.version, after);
        }
    }

    public long getTotalVertical(
            String resortID, String dayID, String skierID) {

        State state = states.get(resortID + ":" + dayID);

        if (state == null) {
            return 0L;
        }

        synchronized (state) {
            return state.totals.getOrDefault(skierID, 0L);
        }
    }

    public List<Entry> getLeaderboard(String resortID, String dayID) {
        State state = states.get(resortID + ":" + dayID);

        if (state == null) {
            return Collections.emptyList();
        }

        synchronized (state) {
            return snapshot(state);
        }
    }

    public long getSwipesProcessed() {
        return swipesProcessed.get();
    }

    private List<Entry> snapshot(State state) {
        List<Entry> entries = new ArrayList<>();

        for (Entry entry : state.ranking) {
            if (entries.size() == topN) {
                break;
            }
            entries.add(entry);
        }

        return Collections.unmodifiableList(entries);
    }

    private static String fingerprint(SwipeMessage message) {
        // Retry metadata may change, so exclude timestamp and server ID.
        return String.join(":",
                message.getResortID(),
                message.getDayID(),
                message.getSkierID(),
                message.getLiftID(),
                Integer.toString(message.getVertical()),
                Integer.toString(message.getTime()));
    }

    private static void verifyFingerprint(
            String existing, String incoming) {

        if (!existing.equals(incoming)) {
            throw new IllegalArgumentException(
                    "The event ID was reused with different business data");
        }
    }

    private static final class State {
        private final Map<String, Long> totals = new HashMap<>();
        private final TreeSet<Entry> ranking = new TreeSet<>(RANKING);
        private long version;
    }

    public static final class Entry {
        private final String skierID;
        private final long totalVertical;

        public Entry(String skierID, long totalVertical) {
            this.skierID = skierID;
            this.totalVertical = totalVertical;
        }

        public String getSkierID() {
            return skierID;
        }

        public long getTotalVertical() {
            return totalVertical;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) other;
            return totalVertical == entry.totalVertical
                    && skierID.equals(entry.skierID);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skierID, totalVertical);
        }
    }

    public static final class Result {
        private final boolean applied;
        private final boolean leaderboardChanged;
        private final long version;
        private final List<Entry> leaderboard;

        private Result(
                boolean applied,
                boolean leaderboardChanged,
                long version,
                List<Entry> leaderboard) {

            this.applied = applied;
            this.leaderboardChanged = leaderboardChanged;
            this.version = version;
            this.leaderboard = leaderboard;
        }

        public boolean isApplied() {
            return applied;
        }

        public boolean isLeaderboardChanged() {
            return leaderboardChanged;
        }

        public long getVersion() {
            return version;
        }

        public List<Entry> getLeaderboard() {
            return leaderboard;
        }
    }
}