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

/**
 * FileService manages file storage on the server side.
 *
 * Phase 4: Supports receiving uploaded files and sending files for download.
 * Files are stored in a configurable storage directory which is
 * created automatically if it doesn't exist.
 *
 * Design decisions:
 * - Uses buffered streams to minimize system calls during file I/O.
 * - Reads/writes exactly the declared byte count in chunks using a fixed-size buffer.
 *   This avoids loading entire files into memory at once.
 * - Uses RandomAccessFile for downloads to support seek-based offset reads,
 *   preparing for resume functionality in future phases.
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
     * Sanitizes a client-provided filename and resolves it safely under the storage directory.
     *
     * Strips any path components (e.g., "../" or "subdir/") to prevent directory
     * traversal attacks. Only the base filename is kept.
     *
     * @param filename the raw filename from the client
     * @return the resolved Path under the storage directory
     */
    private Path resolveSafePath(String filename) {
        String safeFilename = Paths.get(filename).getFileName().toString();
        return storagePath.resolve(safeFilename);
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

        Path targetPath = resolveSafePath(filename);

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
     * Returns the number of remaining bytes in a file starting from the given offset.
     *
     * This method is used to tell the client how many bytes to expect before
     * the server begins streaming file data. The offset parameter exists to
     * support resume in future phases (for now, callers pass offset=0).
     *
     * @param filename the name of the file in the storage directory
     * @param offset   the byte offset to start from (0 = beginning of file)
     * @return the number of bytes remaining from offset to end of file
     * @throws FileNotFoundException    if the file does not exist in storage
     * @throws IllegalArgumentException if offset is negative or beyond file size
     */
    public long sizeFrom(String filename, long offset) throws IOException {
        Path targetPath = resolveSafePath(filename);

        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException("File not found: " + targetPath.getFileName());
        }

        long totalSize = Files.size(targetPath);

        if (offset < 0 || offset > totalSize) {
            throw new IllegalArgumentException(
                    "Invalid offset " + offset + " for file of size " + totalSize);
        }

        return totalSize - offset;
    }

    /**
     * Sends file bytes to the client starting from the given offset.
     *
     * Uses RandomAccessFile instead of FileInputStream because:
     * 1. RandomAccessFile supports seek() — essential for resume in future phases.
     *    With FileInputStream, you'd have to read and discard bytes to reach the
     *    offset, wasting disk I/O. RandomAccessFile.seek() jumps directly.
     * 2. It provides a clear read contract: read into a byte array with an exact
     *    count, making chunked transfer straightforward.
     * 3. Even though offset is always 0 in this phase, choosing RandomAccessFile
     *    now avoids a refactor when resume support is added.
     *
     * @param filename the name of the file in the storage directory
     * @param offset   the byte offset to start reading from
     * @param out      the DataOutputStream connected to the client socket
     * @throws IOException if reading from disk or writing to the stream fails
     */
    public void sendFile(String filename, long offset, DataOutputStream out) throws IOException {
        Path targetPath = resolveSafePath(filename);

        // RandomAccessFile in read-only mode ("r")
        try (RandomAccessFile raf = new RandomAccessFile(targetPath.toFile(), "r")) {
            // Seek to the requested offset (0 for full download, >0 for resume)
            raf.seek(offset);

            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = raf.length() - offset;

            // Stream file bytes in chunks
            while (remaining > 0) {
                int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
                raf.readFully(buffer, 0, chunkSize);
                out.write(buffer, 0, chunkSize);
                remaining -= chunkSize;
            }

            out.flush();
        }

        System.out.println("[FileService] File sent: " + targetPath.getFileName()
                + " (from offset " + offset + ")");
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
