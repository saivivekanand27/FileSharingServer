package server;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;

/**
 * ClientHandler processes all commands for a single client connection.
 *
 * Phase 6: Implements Runnable so it can be submitted to an ExecutorService
 * thread pool. Each connected client gets its own ClientHandler instance
 * running on a pool thread, enabling multiple simultaneous connections.
 *
 * Design decisions:
 * - Implements Runnable (not extends Thread) because we want the thread
 *   lifecycle managed by the ExecutorService, not by us. Runnable represents
 *   a unit of work; Thread represents a thread of execution. Separating
 *   them follows the single-responsibility principle.
 * - Owns its own Session (starts null, set on LOGIN success). Each client
 *   has independent authentication state — no shared session state between
 *   connections.
 * - Shares AuthService, FileService, and LoggerService with all other
 *   handlers. These services are designed for concurrent access:
 *     - AuthService uses ConcurrentHashMap (thread-safe reads)
 *     - LoggerService uses synchronized log() (atomic writes)
 *     - FileService has no locking yet — this is a known gap fixed in Phase 7
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final AuthService authService;
    private final FileService fileService;
    private final LoggerService logger;

    // Unique identifier for this client, used in all log entries
    private final String clientId;

    /**
     * Constructs a handler for a single client connection.
     *
     * @param clientSocket the accepted client socket
     * @param authService  shared authentication service
     * @param fileService  shared file storage service
     * @param logger       shared logging service
     */
    public ClientHandler(Socket clientSocket, AuthService authService,
                         FileService fileService, LoggerService logger) {
        this.clientSocket = clientSocket;
        this.authService = authService;
        this.fileService = fileService;
        this.logger = logger;
        this.clientId = clientSocket.getRemoteSocketAddress().toString();
    }

    /**
     * Runs the command loop for this client connection.
     *
     * This method is called by a thread pool worker thread when the handler
     * is submitted via ExecutorService.submit(). It owns the full lifecycle
     * of the client connection: stream setup, command dispatch, and cleanup.
     */
    @Override
    public void run() {
        logger.log(clientId, "Connected");

        // try-with-resources ensures the socket and its streams are closed
        // when the handler finishes, even if an exception occurs
        try (
            clientSocket;  // Include socket in try-with-resources for auto-close
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())
        ) {
            // Session is null until the client successfully logs in
            // Each handler has its own session — no sharing between clients
            Session session = null;

            // ── Command Loop ─────────────────────────────────────
            // Keep reading commands until EXIT or client disconnects
            boolean running = true;
            while (running) {
                try {
                    // Read the command string from the client
                    String command = in.readUTF();

                    switch (command) {
                        case Protocol.CMD_LOGIN:
                            // Read username and password as separate UTF strings
                            String username = in.readUTF();
                            String password = in.readUTF();

                            if (session != null) {
                                // Already logged in — reject duplicate login
                                String error = Protocol.error("Already logged in as " + session.getUsername());
                                out.writeUTF(error);
                                out.flush();
                                logger.log(clientId, "LOGIN FAILED: duplicate login attempt as '"
                                        + username + "' (already logged in as '"
                                        + session.getUsername() + "')");
                            } else if (authService.authenticate(username, password)) {
                                // Credentials valid — create session
                                session = new Session(username);
                                out.writeUTF(Protocol.RESP_OK);
                                out.flush();
                                logger.log(clientId, "LOGIN SUCCESS: user '" + username + "'");
                            } else {
                                // Invalid credentials
                                String error = Protocol.error("Invalid credentials");
                                out.writeUTF(error);
                                out.flush();
                                logger.log(clientId, "LOGIN FAILED: invalid credentials for user '"
                                        + username + "'");
                            }
                            break;

                        case Protocol.CMD_UPLOAD:
                            // Read file metadata: name and size
                            String filename = in.readUTF();
                            long fileSize = in.readLong();

                            if (fileSize < 0) {
                                String sizeError = Protocol.error("Invalid file size: " + fileSize);
                                out.writeUTF(sizeError);
                                out.flush();
                                logger.log(clientId, "UPLOAD FAILED: invalid file size "
                                        + fileSize + " for '" + filename + "'");
                                break;
                            }

                            if (session == null) {
                                // Not authenticated — must still drain the file bytes
                                // to keep the protocol in sync before sending error
                                fileService.drainBytes(fileSize, in);
                                String uploadError = Protocol.error("Not authenticated");
                                out.writeUTF(uploadError);
                                out.flush();
                                logger.log(clientId, "UPLOAD FAILED: not authenticated — '"
                                        + filename + "' (" + fileSize + " bytes drained)");
                            } else {
                                // Authenticated — receive and save the file
                                try {
                                    logger.log(clientId, "UPLOAD START: '" + filename + "' (" + fileSize + " bytes)");
                                    fileService.receiveFile(filename, fileSize, in);
                                    out.writeUTF(Protocol.RESP_OK);
                                    out.flush();
                                    logger.log(clientId, "UPLOAD SUCCESS: '" + filename
                                            + "' (" + fileSize + " bytes) by '"
                                            + session.getUsername() + "'");
                                } catch (SecurityException e) {
                                    String securityError = Protocol.error("Access denied: " + e.getMessage());
                                    out.writeUTF(securityError);
                                    out.flush();
                                    logger.log(clientId, "UPLOAD FAILED: access denied for '"
                                            + filename + "' — " + e.getMessage());
                                } catch (IOException e) {
                                    String writeError = Protocol.error("Failed to write file to disk: " + e.getMessage());
                                    out.writeUTF(writeError);
                                    out.flush();
                                    logger.log(clientId, "UPLOAD FAILED: disk write error for '"
                                            + filename + "' — " + e.getMessage());
                                }
                            }
                            break;

                        case Protocol.CMD_DOWNLOAD:
                            // Read the requested filename and offset
                            String dlFilename = in.readUTF();
                            long dlOffset = in.readLong();

                            if (session == null) {
                                // Not authenticated — no bytes to drain (server is sender)
                                String dlError = Protocol.error("Not authenticated");
                                out.writeUTF(dlError);
                                out.flush();
                                logger.log(clientId, "DOWNLOAD FAILED: not authenticated — '"
                                        + dlFilename + "'");
                            } else {
                                try {
                                    logger.log(clientId, "DOWNLOAD START: '" + dlFilename + "' (offset " + dlOffset + ")");
                                    long bytesSent = fileService.downloadFile(dlFilename, dlOffset, out);
                                    logger.log(clientId, "DOWNLOAD SUCCESS: '" + dlFilename
                                            + "' (" + bytesSent + " bytes, offset "
                                            + dlOffset + ") to '" + session.getUsername() + "'");
                                } catch (SecurityException e) {
                                    String securityError = Protocol.error("Access denied: " + e.getMessage());
                                    out.writeUTF(securityError);
                                    out.flush();
                                    logger.log(clientId, "DOWNLOAD FAILED: access denied — "
                                            + e.getMessage());
                                } catch (FileNotFoundException e) {
                                    String notFound = Protocol.error("File not found: " + dlFilename);
                                    out.writeUTF(notFound);
                                    out.flush();
                                    logger.log(clientId, "DOWNLOAD FAILED: file not found — '"
                                            + dlFilename + "'");
                                } catch (IllegalArgumentException e) {
                                    String badOffset = Protocol.error(e.getMessage());
                                    out.writeUTF(badOffset);
                                    out.flush();
                                    logger.log(clientId, "DOWNLOAD FAILED: invalid offset — "
                                            + e.getMessage());
                                }
                            }
                            break;

                        case Protocol.CMD_LIST:
                            if (session == null) {
                                String listError = Protocol.error("Not authenticated");
                                out.writeUTF(listError);
                                out.flush();
                                logger.log(clientId, "LIST FAILED: not authenticated");
                            } else {
                                try {
                                    String[] files = fileService.listFiles();
                                    out.writeUTF(Protocol.RESP_OK);
                                    out.writeInt(files.length);
                                    for (String f : files) {
                                        out.writeUTF(f);
                                    }
                                    out.flush();
                                    logger.log(clientId, "LIST SUCCESS: " + files.length
                                            + " file(s) by '" + session.getUsername() + "'");
                                } catch (IOException e) {
                                    String ioError = Protocol.error("Failed to list files: " + e.getMessage());
                                    out.writeUTF(ioError);
                                    out.flush();
                                    logger.log(clientId, "LIST FAILED: " + e.getMessage());
                                }
                            }
                            break;

                        case Protocol.CMD_EXIT:
                            // Client requested disconnect
                            out.writeUTF(Protocol.RESP_OK);
                            out.flush();
                            running = false;
                            logger.log(clientId, "EXIT"
                                    + (session != null
                                    ? " (user: '" + session.getUsername() + "')"
                                    : " (not authenticated)"));
                            break;

                        default:
                            // Unknown command — send error, continue loop
                            String error = Protocol.error("Unknown command: " + command);
                            out.writeUTF(error);
                            out.flush();
                            logger.log(clientId, "UNKNOWN COMMAND: '" + command + "'");
                            break;
                    }
                } catch (EOFException e) {
                    // Client disconnected without sending EXIT
                    logger.log(clientId, "Disconnected unexpectedly");
                    break;
                }
            }
        } catch (IOException e) {
            // Socket-level or stream-level error (e.g., broken pipe, reset)
            logger.log(clientId, "Connection error: " + e.getMessage());
        }

        logger.log(clientId, "Connection closed");
    }
}
