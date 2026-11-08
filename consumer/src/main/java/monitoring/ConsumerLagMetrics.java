package monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public final class ConsumerLagMetrics {

    private static long samples;
    private static long negativeSamples;
    private static double totalMs;
    private static double minMs = Double.POSITIVE_INFINITY;
    private static double maxMs = Double.NEGATIVE_INFINITY;
    private static long atLeast100Ms;

    private ConsumerLagMetrics() {
    }

    public static synchronized void record(
            Instant eventTime, Instant processingStart) {

        double lagMs = Duration.between(
                eventTime, processingStart
        ).toNanos() / 1_000_000.0;

        // Preserve negative observations for clock diagnostics.
        samples++;
        totalMs += lagMs;
        minMs = Math.min(minMs, lagMs);
        maxMs = Math.max(maxMs, lagMs);

        if (lagMs < 0) {
            negativeSamples++;
        }

        if (lagMs >= 100) {
            atLeast100Ms++;
        }
    }

    public static synchronized String snapshot() {
        // Report cumulative observations for this JVM process.
        return String.format(
                Locale.ROOT,
                "lagSamples=%d lagMeanMs=%.3f lagMinMs=%.3f "
                        + "lagMaxMs=%.3f lagAtLeast100Ms=%d "
                        + "lagNegativeSamples=%d",
                samples,
                samples == 0 ? Double.NaN : totalMs / samples,
                samples == 0 ? Double.NaN : minMs,
                samples == 0 ? Double.NaN : maxMs,
                atLeast100Ms,
                negativeSamples
        );
    }
}