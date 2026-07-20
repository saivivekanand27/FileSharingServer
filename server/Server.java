package server;

import common.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Server listens on the configured port and handles multiple clients concurrently.
 *
 * Phase 6: Uses an ExecutorService with a fixed thread pool of 8 worker threads.
 * Each accepted client connection is wrapped in a ClientHandler (Runnable) and
 * submitted to the pool, allowing up to 8 clients to be served simultaneously.
 *
 * Why a thread pool instead of new Thread() per client:
 * - Thread creation is expensive: each new Thread() allocates ~1MB of stack memory
 *   and requires an OS-level kernel thread to be created.
 * - Under heavy load (e.g., 1000 rapid connections), spawning unbounded threads
 *   would exhaust memory and crash the JVM with OutOfMemoryError.
 * - A fixed pool caps resource usage at exactly 8 threads regardless of load.
 *   If all 8 are busy, new connections wait in the pool's internal queue until
 *   a thread becomes available — graceful degradation instead of a crash.
 * - Thread reuse: when a client disconnects, the pool thread is returned to the
 *   pool and immediately picks up the next queued connection, avoiding the
 *   overhead of destroying and recreating a thread.
 *
 * The accept loop runs on the main thread and never blocks the pool:
 *   1. Main thread calls serverSocket.accept() (blocks until a client connects)
 *   2. accept() returns a Socket — main thread creates a ClientHandler
 *   3. Main thread submits the handler to the pool (non-blocking enqueue)
 *   4. Main thread loops back to accept() immediately
 *   5. A pool worker thread picks up the handler and runs the command loop
 */
public class Server {

    // Number of worker threads in the pool
    private static final int THREAD_POOL_SIZE = 8;

    public static void main(String[] args) {

        // Use try-with-resources on LoggerService to ensure the log file writer is closed
        try (LoggerService logger = new LoggerService()) {

            // Initialize the authentication service (loads users.txt)
            AuthService authService;
            try {
                authService = new AuthService();
            } catch (IOException e) {
                logger.log("SERVER", "Failed to initialize AuthService: " + e.getMessage());
                e.printStackTrace();
                return;
            }

            // Initialize the file service (creates storage/ directory if needed)
            FileService fileService;
            try {
                fileService = new FileService();
            } catch (IOException e) {
                logger.log("SERVER", "Failed to initialize FileService: " + e.getMessage());
                e.printStackTrace();
                return;
            }

            // Create a fixed-size thread pool for handling client connections
            ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

            try (ServerSocket serverSocket = new ServerSocket(Protocol.PORT)) {

                logger.log("SERVER", "Listening on port " + Protocol.PORT
                        + " (thread pool size: " + THREAD_POOL_SIZE + ")");

                // ── Accept Loop ─────────────────────────────────────────
                // Runs on the main thread. For each accepted connection,
                // create a ClientHandler and submit it to the thread pool.
                // The main thread never processes commands itself — it only
                // accepts connections and delegates them.
                while (true) {
                    Socket clientSocket = null;
                    try {
                        // Block until a client connects
                        clientSocket = serverSocket.accept();

                        // Wrap in a ClientHandler and submit to the pool
                        ClientHandler handler = new ClientHandler(
                                clientSocket, authService, fileService, logger);
                        threadPool.submit(handler);

                    } catch (RejectedExecutionException e) {
                        logger.log("SERVER", "Task submission rejected: " + e.getMessage());
                        if (clientSocket != null) {
                            try {
                                clientSocket.close();
                            } catch (IOException ex) {
                                // ignore
                            }
                        }
                    } catch (IOException e) {
                        // Accept failed (e.g., server socket closed)
                        logger.log("SERVER", "Accept error: " + e.getMessage());
                        break;
                    }
                }

            } catch (IOException e) {
                logger.log("SERVER", "Failed to start server: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Gracefully shut down the thread pool:
                // 1. Stop accepting new tasks
                threadPool.shutdown();
                try {
                    // 2. Wait up to 30 seconds for running handlers to finish
                    if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                        // 3. Force-kill any remaining tasks if they haven't finished
                        threadPool.shutdownNow();
                        logger.log("SERVER", "Thread pool forcibly shut down");
                    }
                } catch (InterruptedException e) {
                    threadPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                logger.log("SERVER", "Shutdown complete");
            }

        } catch (Exception e) {
            System.err.println("[Server] Fatal runtime exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
