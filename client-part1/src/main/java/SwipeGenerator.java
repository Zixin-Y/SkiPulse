import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

public final class SwipeGenerator implements Runnable {

    private final BlockingQueue<SwipeEvent> queue;
    private final int totalEvents;
    private final int resortCount;
    private final int seasonID;
    private final int dayID;

    public SwipeGenerator(BlockingQueue<SwipeEvent> queue,
                          int totalEvents,
                          int resortCount,
                          int seasonID,
                          int dayID) {

        this.queue = Objects.requireNonNull(queue, "queue");

        if (totalEvents <= 0 || resortCount <= 0
                || seasonID <= 0 || dayID <= 0) {
            throw new IllegalArgumentException(
                    "All configuration values must be positive"
            );
        }

        this.totalEvents = totalEvents;
        this.resortCount = resortCount;
        this.seasonID = seasonID;
        this.dayID = dayID;
    }

    @Override
    public void run() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        try {
            for (int i = 0; i < totalEvents; i++) {
                SwipeEvent event = new SwipeEvent(
                        random.nextInt(resortCount) + 1,
                        seasonID,
                        dayID,
                        random.nextInt(100000) + 1,
                        random.nextInt(360) + 1,
                        random.nextInt(40) + 1
                );

                queue.put(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}