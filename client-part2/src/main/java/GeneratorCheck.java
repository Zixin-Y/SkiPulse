import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class GeneratorCheck {

    public static void main(String[] args)
            throws InterruptedException {

        int totalEvents = 10;

        BlockingQueue<SwipeEvent> queue =
                new ArrayBlockingQueue<>(totalEvents);

        Thread generator = new Thread(
                new SwipeGenerator(queue, totalEvents, 10, 2026, 1),
                "swipe-generator"
        );

        generator.start();
        generator.join();

        if (queue.size() != totalEvents) {
            throw new IllegalStateException(
                    "Expected " + totalEvents
                            + " events, got " + queue.size()
            );
        }

        System.out.println("Generated events: " + queue.size());

        SwipeEvent event;
        while ((event = queue.poll()) != null) {
            System.out.println(event.toPath() + " " + event.toJson());
        }
    }
}