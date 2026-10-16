import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class SwipeCsvRecorder implements AutoCloseable {

    private final BufferedWriter writer;
    private long rowCount;

    public SwipeCsvRecorder(Path outputPath) throws IOException {
        Path absolutePath = outputPath.toAbsolutePath();
        Path parent = absolutePath.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        writer = Files.newBufferedWriter(
                absolutePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );

        writer.write(
                "timestamp,requestType,latency,statusCode,resortID"
        );
        writer.newLine();
    }

    public synchronized void record(
            long timestamp,
            double latencyMs,
            int statusCode,
            int resortID) throws IOException {

        writer.write(String.format(
                Locale.ROOT,
                "%d,SWIPE,%.3f,%d,%d",
                timestamp,
                latencyMs,
                statusCode,
                resortID
        ));

        writer.newLine();
        rowCount++;
    }

    public synchronized long getRowCount() {
        return rowCount;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}