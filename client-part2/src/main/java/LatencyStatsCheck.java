import java.util.ArrayList;
import java.util.List;

public class LatencyStatsCheck {

    public static void main(String[] args) {
        List<Double> samples = new ArrayList<>();

        for (int i = 1; i <= 100; i++) {
            samples.add((double) i);
        }

        LatencyStats.print("Known sample", samples);
    }
}