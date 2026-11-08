package aggregation;

import model.SwipeMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class LeaderboardAggregatorCheck {

    public static void main(String[] args) throws Exception {
        checkTotalsAndDuplicates();
        checkRanking();
        checkIsolation();
        checkConcurrentUpdates();

        System.out.println("All leaderboard aggregation checks passed.");
    }

    private static void checkTotalsAndDuplicates() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        SwipeMessage first = event("1", "1", "1001", 167);
        SwipeMessage second = event("1", "1", "1001", 74);

        LeaderboardAggregator.Result firstResult =
                aggregator.aggregate(first);
        aggregator.aggregate(second);

        require(firstResult.isApplied(),
                "The first event must be applied");

        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "The total must be 241");

        System.out.println("PASS: vertical totals accumulate correctly.");

        // A retry may arrive through another server at a later time.
        SwipeMessage retry = new SwipeMessage(
                second.getEventId(),
                second.getResortID(),
                second.getDayID(),
                second.getSkierID(),
                second.getLiftID(),
                second.getVertical(),
                second.getTime(),
                "2026-11-07T12:01:00Z",
                "another-ingest-server");

        LeaderboardAggregator.Result duplicate =
                aggregator.aggregate(retry);

        require(!duplicate.isApplied(),
                "A duplicate must not be applied");
        require(!duplicate.isLeaderboardChanged(),
                "A duplicate must not change the leaderboard");
        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "A duplicate must not increase the total");
        require(aggregator.getSwipesProcessed() == 2,
                "Only unique applied events must be counted");

        System.out.println("PASS: duplicate events are not counted twice.");

        SwipeMessage conflict = new SwipeMessage(
                second.getEventId(),
                "1",
                "1",
                "1001",
                "1",
                200,
                120,
                "2026-11-07T12:00:00Z",
                "aggregation-check");

        boolean rejected = false;

        try {
            aggregator.aggregate(conflict);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        require(rejected,
                "Conflicting data with the same event ID must be rejected");
        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "Rejected data must not change the total");

        System.out.println("PASS: conflicting event ID reuse is rejected.");
    }

    private static void checkRanking() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(2);

        aggregator.aggregate(event("1", "1", "1001", 167));
        aggregator.aggregate(event("1", "1", "1002", 300));

        LeaderboardAggregator.Result outsideTop =
                aggregator.aggregate(event("1", "1", "1003", 100));

        require(outsideTop.isApplied(),
                "An event outside the top N must still be applied");
        require(!outsideTop.isLeaderboardChanged(),
                "An event below the top N must not change the leaderboard");

        List<LeaderboardAggregator.Entry> ranking =
                aggregator.getLeaderboard("1", "1");

        require(ranking.size() == 2,
                "The leaderboard must contain only the top two");
        expectEntry(ranking, 0, "1002", 300);
        expectEntry(ranking, 1, "1001", 167);

        System.out.println("PASS: only the top N skiers are returned.");

        LeaderboardAggregator.Result enteredTop =
                aggregator.aggregate(event("1", "1", "1003", 250));

        require(enteredTop.isLeaderboardChanged(),
                "Entering the top N must change the leaderboard");
        require(enteredTop.getVersion() == outsideTop.getVersion() + 1,
                "A changed leaderboard must advance its version");

        ranking = enteredTop.getLeaderboard();
        expectEntry(ranking, 0, "1003", 350);
        expectEntry(ranking, 1, "1002", 300);

        System.out.println("PASS: a previously unranked skier can enter the top N.");

        // Equal totals use numeric skier ID order.
        aggregator.aggregate(event("1", "1", "1001", 183));

        ranking = aggregator.getLeaderboard("1", "1");
        expectEntry(ranking, 0, "1001", 350);
        expectEntry(ranking, 1, "1003", 350);

        System.out.println("PASS: ties use a stable skier ID order.");
    }

    private static void checkIsolation() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        aggregator.aggregate(event("1", "1", "1001", 167));
        aggregator.aggregate(event("2", "1", "1001", 300));
        aggregator.aggregate(event("1", "2", "1001", 74));

        require(aggregator.getTotalVertical("1", "1", "1001") == 167,
                "Resort 1 day 1 must remain independent");
        require(aggregator.getTotalVertical("2", "1", "1001") == 300,
                "Resort 2 must remain independent");
        require(aggregator.getTotalVertical("1", "2", "1001") == 74,
                "Day 2 must remain independent");
        require(aggregator.getLeaderboard("3", "1").isEmpty(),
                "An unused resort must have an empty leaderboard");

        System.out.println("PASS: resorts and days have independent state.");
    }

    private static void checkConcurrentUpdates() throws Exception {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        // Submit the same event many times from competing threads.
        SwipeMessage shared = event("1", "1", "1001", 167);

        try {
            for (int i = 0; i < 100; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    aggregator.aggregate(shared);
                    return null;
                }));
            }

            start.countDown();

            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }

            require(aggregator.getTotalVertical("1", "1", "1001") == 167,
                    "Concurrent duplicates must be applied only once");
            require(aggregator.getSwipesProcessed() == 1,
                    "Concurrent duplicate count must be one");

            System.out.println("PASS: concurrent duplicates are applied once.");

            tasks.clear();

            // Different event IDs must all contribute to the same total.
            for (int i = 0; i < 100; i++) {
                SwipeMessage unique = event("1", "1", "1001", 74);

                tasks.add(executor.submit(() -> {
                    aggregator.aggregate(unique);
                    return null;
                }));
            }

            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }

            require(aggregator.getTotalVertical("1", "1", "1001") == 7567,
                    "Concurrent unique events must not lose updates");
            require(aggregator.getSwipesProcessed() == 101,
                    "All unique events must be counted");

            System.out.println("PASS: concurrent unique events lose no updates.");
        } finally {
            executor.shutdownNow();
        }
    }

    private static SwipeMessage event(
            String resortID,
            String dayID,
            String skierID,
            int vertical) {

        // These fixtures test aggregation without using the JSON parser.
        return new SwipeMessage(
                UUID.randomUUID().toString(),
                resortID,
                dayID,
                skierID,
                "1",
                vertical,
                120,
                "2026-11-07T12:00:00Z",
                "aggregation-check");
    }

    private static void expectEntry(
            List<LeaderboardAggregator.Entry> ranking,
            int index,
            String skierID,
            long total) {

        LeaderboardAggregator.Entry entry = ranking.get(index);

        require(skierID.equals(entry.getSkierID())
                        && total == entry.getTotalVertical(),
                "Unexpected ranking entry at index " + index);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}