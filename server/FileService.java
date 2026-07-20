package server;

import common.Protocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileService manages file storage on the server side.
 *
 * Phase 3: Supports receiving uploaded files from clients.
 * Files are stored in a configurable storage directory which is
 * created automatically if it doesn't exist.
 *
 * Design decisions:
 * - Uses buffered streams to minimize system calls during file writes.
 *   Without buffering, each write() call would trigger an OS-level syscall,
 *   making large file transfers orders of magnitude slower.
 * - Reads exactly fileSize bytes in chunks using a fixed-size buffer.
 *   This avoids loading the entire file into memory at once.
 * - No locking is needed yet since only one client connects at a time.
 */
public class FileService {

    // Directory where uploaded files are stored
    private static final String STORAGE_DIR = "storage";

    // Path object for the storage directory
    private final Path storagePath;

    /**
     * Constructs the FileService and ensures the storage directory exists.
     *
     * @throws IOException if the directory cannot be created
     */
    public FileService() throws IOException {
        this.storagePath = Paths.get(STORAGE_DIR);

        // Create the storage directory if it doesn't exist
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
            System.out.println("[FileService] Created storage directory: " + storagePath.toAbsolutePath());
        } else {
            System.out.println("[FileService] Storage directory ready: " + storagePath.toAbsolutePath());
        }
    }

    /**
     * Receives a file from the client's input stream and writes it to disk.
     *
     * The method reads exactly {@code fileSize} bytes from the stream in chunks
     * using a fixed-size buffer (Protocol.BUFFER_SIZE). This approach:
     * - Never loads the entire file into memory (supports large files)
     * - Uses BufferedOutputStream to batch disk writes (fewer syscalls)
     * - Reads exactly the declared number of bytes (protocol stays in sync)
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

        // Sanitize the filename to prevent directory traversal attacks (e.g. "../myfile.txt")
        // This strips any leading path segments and keeps only the base file name
        String safeFilename = Paths.get(filename).getFileName().toString();
        Path targetPath = storagePath.resolve(safeFilename);

        // BufferedOutputStream wraps FileOutputStream to batch small writes
        // into larger disk I/O operations, dramatically improving performance
        try (BufferedOutputStream fileOut = new BufferedOutputStream(
                new FileOutputStream(targetPath.toFile()))) {

            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = fileSize;

            // Read in chunks until all bytes are consumed
            // Each iteration reads min(bufferSize, remaining) bytes
            while (remaining > 0) {
                // Cast is safe: Math.min guarantees result <= BUFFER_SIZE (an int)
                int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
                in.readFully(buffer, 0, chunkSize);
                fileOut.write(buffer, 0, chunkSize);
                remaining -= chunkSize;
            }

            // Ensure all buffered data is flushed to disk
            fileOut.flush();
        }

        System.out.println("[FileService] File saved: " + targetPath.toAbsolutePath()
                + " (" + fileSize + " bytes)");
    }

    /**
     * Drains (discards) exactly {@code fileSize} bytes from the input stream.
     *
     * This is critical when the server must reject an upload (e.g., client
     * not authenticated) but the client has already started streaming bytes.
     * Without draining, the next readUTF() would read file bytes as a
     * command string, permanently desyncing the protocol.
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
