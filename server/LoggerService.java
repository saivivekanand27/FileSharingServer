package server;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * LoggerService provides centralized, thread-safe logging for the server.
 *
 * Phase 5: Every server action is recorded with a timestamp and client identifier
 * to both a log file (logs/server.log) and the console (System.out).
 *
 * Design decisions:
 * - The log() method is synchronized even before multithreading is added.
 *   This is intentional: when the thread pool is introduced in a later phase,
 *   LoggerService will already be safe to share across handler threads without
 *   any refactoring. Retroactively adding synchronization after concurrency bugs
 *   appear is far harder than designing for it upfront.
 * - Uses PrintWriter with auto-flush disabled. We flush manually after each
 *   write to guarantee that log entries are persisted immediately (no buffered
 *   entries lost if the server crashes).
 * - Timestamp format is HH:mm:ss for compact, human-readable log lines.
 */
public class LoggerService implements AutoCloseable {

    // Directory and file for the log output
    private static final String LOG_DIR  = "logs";
    private static final String LOG_FILE = "server.log";

    // Formatter for the timestamp portion of each log line
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Writer for the log file — opened once at construction, kept open for the
    // lifetime of the server to avoid repeated file open/close overhead
    private final PrintWriter logWriter;

    /**
     * Constructs the LoggerService, creating the logs directory and opening
     * the log file in append mode.
     *
     * Append mode ensures that restarting the server does not overwrite
     * previous log entries — the full history is preserved.
     *
     * @throws IOException if the directory cannot be created or the file cannot be opened
     */
    public LoggerService() throws IOException {
        Path logDirPath = Paths.get(LOG_DIR);

        // Create the logs directory if it doesn't exist
        if (!Files.exists(logDirPath)) {
            Files.createDirectories(logDirPath);
        }

        Path logFilePath = logDirPath.resolve(LOG_FILE);

        // Open the log file in append mode (true) so previous entries are preserved
        // BufferedWriter wraps FileWriter for efficient disk writes
        this.logWriter = new PrintWriter(
                new BufferedWriter(new FileWriter(logFilePath.toFile(), true)),
                false  // auto-flush disabled — we flush manually for control
        );

        System.out.println("[LoggerService] Logging to: " + logFilePath.toAbsolutePath());
    }

    /**
     * Writes a timestamped log entry to both the log file and the console.
     *
     * Format: [HH:mm:ss] <clientId>: <message>
     *
     * This method is synchronized to prevent interleaved writes when multiple
     * threads log concurrently. Without synchronization, two threads calling
     * log() simultaneously could produce garbled output like:
     *
     *   [12:00:01] client-1: Up[12:00:01] client-2: Downloload complete
     *   ad complete
     *
     * The synchronized keyword ensures that only one thread can execute the
     * method at a time, producing clean, atomic log lines:
     *
     *   [12:00:01] client-1: Upload complete
     *   [12:00:01] client-2: Download complete
     *
     * @param clientId identifies which client triggered the action
     * @param message  describes the action that occurred
     */
    public synchronized void log(String clientId, String message) {
        // Build the formatted log line
        String timestamp = LocalTime.now().format(TIME_FMT);
        String logLine = "[" + timestamp + "] " + clientId + ": " + message;

        // Write to the log file and flush immediately to guarantee persistence
        logWriter.println(logLine);
        logWriter.flush();

        // Also print to console for real-time observability
        System.out.println(logLine);
    }

    /**
     * Closes the underlying log file writer.
     *
     * Should be called when the server shuts down to release the file handle.
     * PrintWriter.close() also flushes any remaining buffered data.
     */
    public void close() {
        logWriter.close();
    }
}
