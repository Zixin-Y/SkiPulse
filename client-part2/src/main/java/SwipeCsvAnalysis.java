import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SwipeCsvAnalysis {

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

        double measurementSeconds = args.length > 1
                ? Double.parseDouble(args[1])
                : 85.136;

        if (!Double.isFinite(measurementSeconds)
                || measurementSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Measurement duration must be positive and finite"
            );
        }

        List<Double> allLatencies = new ArrayList<>();
        List<Double> successfulLatencies = new ArrayList<>();
        Map<Integer, Long> successesByResort = new TreeMap<>();

        long networkErrors = 0;
        long otherResponses = 0;

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
            long lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                String[] columns = line.split(",", -1);

                if (columns.length != 5
                        || !"SWIPE".equals(columns[1])) {
                    throw new IllegalArgumentException(
                            "Invalid CSV row at line " + lineNumber
                    );
                }

                long timestamp = Long.parseLong(columns[0]);
                double latencyMs = Double.parseDouble(columns[2]);
                int statusCode = Integer.parseInt(columns[3]);
                int resortID = Integer.parseInt(columns[4]);

                if (timestamp <= 0
                        || !Double.isFinite(latencyMs)
                        || latencyMs < 0
                        || resortID <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid values at line " + lineNumber
                    );
                }

                allLatencies.add(latencyMs);

                if (statusCode == 201) {
                    successfulLatencies.add(latencyMs);
                    successesByResort.merge(resortID, 1L, Long::sum);
                } else if (statusCode == 0) {
                    networkErrors++;
                } else {
                    otherResponses++;
                }
            }
        }

        System.out.println("Input CSV: " + input);
        System.out.println("HTTP attempts: " + allLatencies.size());
        System.out.println(
                "Successful responses: " + successfulLatencies.size()
        );
        System.out.println(
                "Network-error attempts: " + networkErrors
        );
        System.out.println(
                "Other HTTP responses: " + otherResponses
        );

        System.out.println();

        LatencyStats.print(
                "All HTTP attempt latencies",
                allLatencies
        );

        LatencyStats.print(
                "Successful HTTP attempt latencies",
                successfulLatencies
        );

        System.out.println();
        System.out.println("Successful swipes by resort:");

        System.out.printf(
                java.util.Locale.ROOT,
                "Measurement duration: %.3f seconds%n",
                measurementSeconds
        );

        for (Map.Entry<Integer, Long> entry
                : successesByResort.entrySet()) {

            double throughput =
                    entry.getValue() / measurementSeconds;

            System.out.printf(
                    java.util.Locale.ROOT,
                    "Resort %d: successful=%d, throughput=%.3f swipes/s%n",
                    entry.getKey(),
                    entry.getValue(),
                    throughput
            );
        }

        System.out.printf(
                java.util.Locale.ROOT,
                "Total ingest throughput: %.3f swipes/s%n",
                successfulLatencies.size() / measurementSeconds
        );
    }
}