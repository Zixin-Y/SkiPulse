import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ThroughputBuckets {

    private static final double BUCKET_MS = 10_000.0;

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                System.getProperty("user.home"),
                "Downloads",
                "6650",
                "assignment1",
                "results",
                "swipes-1792091656923.csv"
        );

        List<Double> successfulCompletionTimes = new ArrayList<>();

        long firstSendTimestamp = Long.MAX_VALUE;
        double lastAttemptEnd = 0;
        long attempts = 0;

        try (BufferedReader reader = Files.newBufferedReader(
                input, StandardCharsets.UTF_8)) {

            String header = reader.readLine();

            if (!"timestamp,requestType,latency,statusCode,resortID"
                    .equals(header)) {
                throw new IllegalArgumentException(
                        "Unexpected CSV header"
                );
            }

            String line;

            while ((line = reader.readLine()) != null) {
                String[] columns = line.split(",", -1);

                if (columns.length != 5
                        || !"SWIPE".equals(columns[1])) {
                    throw new IllegalArgumentException(
                            "Invalid CSV row"
                    );
                }

                long timestamp = Long.parseLong(columns[0]);
                double latencyMs = Double.parseDouble(columns[2]);
                int statusCode = Integer.parseInt(columns[3]);

                if (timestamp <= 0
                        || !Double.isFinite(latencyMs)
                        || latencyMs < 0) {
                    throw new IllegalArgumentException(
                            "Invalid timestamp or latency"
                    );
                }

                double completionTime = timestamp + latencyMs;

                firstSendTimestamp =
                        Math.min(firstSendTimestamp, timestamp);

                lastAttemptEnd =
                        Math.max(lastAttemptEnd, completionTime);

                attempts++;

                if (statusCode == 201) {
                    successfulCompletionTimes.add(completionTime);
                }
            }
        }

        if (attempts == 0) {
            throw new IllegalStateException("CSV contains no attempts");
        }

        double observationMs =
                lastAttemptEnd - firstSendTimestamp;

        if (observationMs <= 0) {
            throw new IllegalStateException(
                    "Observation duration must be positive"
            );
        }

        int bucketCount =
                (int) Math.ceil(observationMs / BUCKET_MS);

        long[] counts = new long[bucketCount];

        for (double completionTime : successfulCompletionTimes) {
            int index = (int) (
                    (completionTime - firstSendTimestamp) / BUCKET_MS
            );

            index = Math.min(index, bucketCount - 1);
            counts[index]++;
        }

        String filename = input.getFileName().toString();
        String stem = filename.endsWith(".csv")
                ? filename.substring(0, filename.length() - 4)
                : filename;

        Path output = input.resolveSibling(
                stem + "-throughput.csv"
        );

        long countedSuccesses = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {

            writer.write(
                    "startSeconds,endSeconds,durationSeconds,"
                            + "successfulSwipes,throughput"
            );
            writer.newLine();

            for (int i = 0; i < bucketCount; i++) {
                double startSeconds = i * BUCKET_MS / 1000.0;

                double endSeconds = Math.min(
                        (i + 1) * BUCKET_MS,
                        observationMs
                ) / 1000.0;

                double durationSeconds = endSeconds - startSeconds;
                double throughput = counts[i] / durationSeconds;

                String row = String.format(
                        Locale.ROOT,
                        "%.3f,%.3f,%.3f,%d,%.3f",
                        startSeconds,
                        endSeconds,
                        durationSeconds,
                        counts[i],
                        throughput
                );

                writer.write(row);
                writer.newLine();

                System.out.println(row);
                countedSuccesses += counts[i];
            }
        }

        if (countedSuccesses != successfulCompletionTimes.size()) {
            throw new IllegalStateException(
                    "Bucket counts do not match successful responses"
            );
        }

        System.out.println("Successful swipes counted: " + countedSuccesses);
        System.out.println("Throughput CSV saved to: " + output);
    }
}