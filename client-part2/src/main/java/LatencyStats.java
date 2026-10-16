import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

public final class LatencyStats {

    private LatencyStats() {
    }

    public static void print(
            String label,
            Collection<Double> samples) {

        if (samples.isEmpty()) {
            System.out.println(label + ": no samples");
            return;
        }

        double[] sorted = samples.stream()
                .mapToDouble(Double::doubleValue)
                .toArray();

        for (double value : sorted) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Latency samples must be finite"
                );
            }
        }

        Arrays.sort(sorted);

        double sum = 0;
        for (double value : sorted) {
            sum += value;
        }

        double mean = sum / sorted.length;

        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];

        System.out.printf(
                Locale.ROOT,
                "%s: count=%d, meanMs=%.3f, medianMs=%.3f, "
                        + "p95Ms=%.3f, p99Ms=%.3f, "
                        + "minMs=%.3f, maxMs=%.3f%n",
                label,
                sorted.length,
                mean,
                median,
                nearestRank(sorted, 0.95),
                nearestRank(sorted, 0.99),
                sorted[0],
                sorted[sorted.length - 1]
        );
    }

    private static double nearestRank(
            double[] sorted,
            double percentile) {

        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[index];
    }
}