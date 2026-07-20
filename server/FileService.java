package server;

import common.Protocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FileService manages file storage on the server side.
 *
 * Phase 7: Thread-safe file operations with per-filename locking.
 *
 * Design decisions:
 * - Uses a Map<String, ReentrantLock> backed by ConcurrentHashMap to provide
 *   one lock per filename. This allows concurrent operations on DIFFERENT files
 *   to proceed in parallel, while operations on the SAME file are serialized.
 * - Both receiveFile and sendFile acquire the lock before I/O and release it
 *   in a finally block, guaranteeing release even if an exception occurs.
 * - Path validation uses canonical path comparison to block directory traversal
 *   attacks (e.g., filenames containing "../").
 * - Uses buffered streams for efficient I/O and RandomAccessFile for seek support.
 *
 * Why per-filename locks instead of one lock for the whole FileService:
 * - A single global lock would serialize ALL file operations. If Client A uploads
 *   "report.pdf" and Client B uploads "photo.jpg" simultaneously, Client B would
 *   have to wait for Client A to finish — even though they touch different files.
 * - Per-filename locks allow these two operations to run in parallel on separate
 *   threads, only blocking when two clients access the SAME filename.
 * - This is the same principle as database row-level locking vs table-level locking.
 */
public class FileService {

    // Directory where uploaded files are stored
    private static final String STORAGE_DIR = "storage";

    // Path object for the storage directory (resolved to absolute for path validation)
    private final Path storagePath;

    // Per-filename lock map: each filename gets its own ReentrantLock
    // ConcurrentHashMap handles thread-safe map operations;
    // computeIfAbsent atomically creates a lock on first access to a filename
    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * Constructs the FileService and ensures the storage directory exists.
     *
     * @throws IOException if the directory cannot be created
     */
    public FileService() throws IOException {
        this.storagePath = Paths.get(STORAGE_DIR).toAbsolutePath().normalize();

        // Create the storage directory if it doesn't exist
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
            System.out.println("[FileService] Created storage directory: " + storagePath);
        } else {
            System.out.println("[FileService] Storage directory ready: " + storagePath);
        }
    }

    /**
     * Sanitizes a client-provided filename and resolves it safely under the storage directory.
     *
     * Two layers of protection:
     * 1. Strip path components: Paths.get(filename).getFileName() keeps only the base name.
     * 2. Canonical path check: verify the resolved path starts with the storage directory,
     *    blocking any traversal that survives stripping (e.g., edge cases on different OS).
     *
     * @param filename the raw filename from the client
     * @return the resolved, validated Path under the storage directory
     * @throws SecurityException if the resolved path escapes the storage directory
     */
    private Path resolveSafePath(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        // Layer 1: strip directory components
        Path pathObj = Paths.get(filename).getFileName();
        if (pathObj == null) {
            throw new IllegalArgumentException("Invalid filename path: " + filename);
        }
        String safeFilename = pathObj.toString();

        // Layer 2: resolve and normalize, then verify containment
        Path targetPath = storagePath.resolve(safeFilename).toAbsolutePath().normalize();

        if (!targetPath.startsWith(storagePath)) {
            throw new SecurityException(
                    "Path traversal blocked: '" + filename + "' resolves outside storage directory");
        }

        return targetPath;
    }

    /**
     * Returns the ReentrantLock for a given filename, creating one if it doesn't exist.
     *
     * computeIfAbsent is atomic on ConcurrentHashMap: if two threads call this
     * for the same filename simultaneously, only one ReentrantLock is created
     * and both threads receive the same instance.
     *
     * @param filename the sanitized filename to get a lock for
     * @return the ReentrantLock for this filename
     */
    private ReentrantLock getLock(String filename) {
        return fileLocks.computeIfAbsent(filename, key -> new ReentrantLock());
    }

    /**
     * Receives a file from the client's input stream and writes it to disk.
     *
     * Acquires the per-filename lock before writing to prevent two concurrent
     * uploads of the same filename from interleaving their bytes on disk.
     * The lock is released in a finally block to guarantee release even if
     * an IOException occurs mid-transfer.
     *
     * @param filename the name of the file to save (stored under storage/)
     * @param fileSize the exact number of bytes to read from the stream
     * @param in       the DataInputStream connected to the client socket
     * @throws IOException if reading from the stream or writing to disk fails
     */
    public void receiveFile(String filename, long fileSize, DataInputStream in) throws IOException {
        if (fileSize < 0) {
            throw new IllegalArgumentException("File size cannot be negative: " + fileSize);
        }

        Path targetPath = resolveSafePath(filename);
        String safeFilename = targetPath.getFileName().toString();

        // Acquire the per-filename lock — blocks if another thread holds it
        ReentrantLock lock = getLock(safeFilename);
        lock.lock();
        try {
            // BufferedOutputStream wraps FileOutputStream to batch small writes
            try (BufferedOutputStream fileOut = new BufferedOutputStream(
                    new FileOutputStream(targetPath.toFile()))) {

                byte[] buffer = new byte[Protocol.BUFFER_SIZE];
                long remaining = fileSize;

                // Read in chunks until all bytes are consumed
                while (remaining > 0) {
                    int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
                    in.readFully(buffer, 0, chunkSize);
                    fileOut.write(buffer, 0, chunkSize);
                    remaining -= chunkSize;
                }

                fileOut.flush();
            }

            System.out.println("[FileService] File saved: " + targetPath
                    + " (" + fileSize + " bytes)");
        } finally {
            // Always release the lock, even if an exception occurred
            lock.unlock();
        }
    }

    /**
     * Downloads a file safely by validating path and offset, writing protocol responses,
     * and streaming file bytes, all under a single per-filename lock to prevent TOCTOU races.
     *
     * @param filename the name of the file to download
     * @param offset   the byte offset to start from
     * @param out      the client's DataOutputStream to write headers and data to
     * @return the number of remaining bytes sent to the client
     * @throws IOException             if reading from disk or writing to socket fails
     * @throws FileNotFoundException    if the file does not exist
     * @throws IllegalArgumentException if the offset is invalid
     */
    public long downloadFile(String filename, long offset, DataOutputStream out) throws IOException {
        Path targetPath = resolveSafePath(filename);
        String safeFilename = targetPath.getFileName().toString();

        ReentrantLock lock = getLock(safeFilename);
        lock.lock();
        try {
            if (!Files.exists(targetPath)) {
                throw new FileNotFoundException("File not found: " + safeFilename);
            }

            long totalSize = Files.size(targetPath);

            if (offset < 0 || offset > totalSize) {
                throw new IllegalArgumentException(
                        "Invalid offset " + offset + " for file of size " + totalSize);
            }

            long remainingBytes = totalSize - offset;

            // Write protocol headers inside the lock to prevent TOCTOU race
            out.writeUTF(Protocol.RESP_OK);
            out.writeLong(remainingBytes);
            out.flush();

            if (remainingBytes > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(targetPath.toFile(), "r")) {
                    raf.seek(offset);

                    byte[] buffer = new byte[Protocol.BUFFER_SIZE];
                    long remaining = remainingBytes;

                    while (remaining > 0) {
                        int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
                        raf.readFully(buffer, 0, chunkSize);
                        out.write(buffer, 0, chunkSize);
                        remaining -= chunkSize;
                    }
                    out.flush();
                }
            }

            System.out.println("[FileService] File sent: " + safeFilename
                    + " (from offset " + offset + ", " + remainingBytes + " bytes)");

            return remainingBytes;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains (discards) exactly {@code fileSize} bytes from the input stream.
     *
     * No lock is needed here because draining doesn't touch any file on disk.
     * It only consumes bytes from the network stream to keep the protocol in sync.
     *
     * @param fileSize the number of bytes to discard
     * @param in       the DataInputStream to drain from
     * @throws IOException if reading from the stream fails
     */
    public void drainBytes(long fileSize, DataInputStream in) throws IOException {
        if (fileSize < 0) {
            throw new IllegalArgumentException("File size cannot be negative: " + fileSize);
        }
        byte[] buffer = new byte[Protocol.BUFFER_SIZE];
        long remaining = fileSize;

        while (remaining > 0) {
            int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
            in.readFully(buffer, 0, chunkSize);
            remaining -= chunkSize;
        }

        System.out.println("[FileService] Drained " + fileSize + " bytes (upload rejected).");
    }
}
