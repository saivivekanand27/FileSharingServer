package server;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server listens on the configured port and handles one client at a time.
 *
 * Phase 4: Supports LOGIN, UPLOAD, DOWNLOAD, and EXIT commands.
 * - LOGIN: authenticates via AuthService, creates a Session on success.
 * - UPLOAD: receives a file from the client and saves to disk via FileService.
 * - DOWNLOAD: sends a previously uploaded file back to the client.
 * - EXIT: closes the connection gracefully.
 *
 * The server runs a command loop per client connection:
 *   1. Read a command string from the client
 *   2. Dispatch to the appropriate handler
 *   3. Send back a response (OK or ERROR:<reason>)
 *   4. Loop until EXIT or client disconnects
 */
public class Server {

    public static void main(String[] args) {

        // Initialize the authentication service (loads users.txt)
        AuthService authService;
        try {
            authService = new AuthService();
        } catch (IOException e) {
            System.err.println("[Server] Failed to initialize AuthService: " + e.getMessage());
            e.printStackTrace();
            return; // Cannot start server without auth service
        }

        // Initialize the file service (creates storage/ directory if needed)
        FileService fileService;
        try {
            fileService = new FileService();
        } catch (IOException e) {
            System.err.println("[Server] Failed to initialize FileService: " + e.getMessage());
            e.printStackTrace();
            return; // Cannot start server without file service
        }

        // try-with-resources ensures ServerSocket is closed on shutdown
        try (ServerSocket serverSocket = new ServerSocket(Protocol.PORT)) {

            System.out.println("[Server] Listening on port " + Protocol.PORT + "...");

            // Accept one client connection at a time
            // (threading will be added in a future phase)
            try (Socket clientSocket = serverSocket.accept()) {

                System.out.println("[Server] Client connected: "
                        + clientSocket.getRemoteSocketAddress());

                // Wrap the socket's raw byte streams with Data streams for typed I/O
                try (
                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                    DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())
                ) {
                    // Session is null until the client successfully logs in
                    Session session = null;

                    // ── Command Loop ─────────────────────────────────────
                    // Keep reading commands until EXIT or client disconnects
                    boolean running = true;
                    while (running) {
                        try {
                            // Read the command string from the client
                            String command = in.readUTF();
                            System.out.println("[Server] Command received: " + command);

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
                                        System.out.println("[Server] Rejected duplicate login from "
                                                + username + " (already logged in as "
                                                + session.getUsername() + ")");
                                    } else if (authService.authenticate(username, password)) {
                                        // Credentials valid — create session
                                        session = new Session(username);
                                        out.writeUTF(Protocol.RESP_OK);
                                        out.flush();
                                        System.out.println("[Server] User authenticated: " + session);
                                    } else {
                                        // Invalid credentials
                                        String error = Protocol.error("Invalid credentials");
                                        out.writeUTF(error);
                                        out.flush();
                                        System.out.println("[Server] Authentication failed for user: "
                                                + username);
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
                                        System.out.println("[Server] Rejected invalid file size: " + fileSize);
                                        break;
                                    }

                                    if (session == null) {
                                        // Not authenticated — must still drain the file bytes
                                        // to keep the protocol in sync before sending error
                                        fileService.drainBytes(fileSize, in);
                                        String uploadError = Protocol.error("Not authenticated");
                                        out.writeUTF(uploadError);
                                        out.flush();
                                        System.out.println("[Server] Upload rejected (not authenticated): "
                                                + filename + " (" + fileSize + " bytes drained)");
                                    } else {
                                        // Authenticated — receive and save the file
                                        try {
                                            fileService.receiveFile(filename, fileSize, in);
                                            out.writeUTF(Protocol.RESP_OK);
                                            out.flush();
                                            System.out.println("[Server] Upload complete: " + filename
                                                    + " (" + fileSize + " bytes) by " + session.getUsername());
                                        } catch (IOException e) {
                                            String writeError = Protocol.error("Failed to write file to disk: " + e.getMessage());
                                            out.writeUTF(writeError);
                                            out.flush();
                                            System.out.println("[Server] File write error: " + e.getMessage());
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
                                        System.out.println("[Server] Download rejected (not authenticated): "
                                                + dlFilename);
                                    } else {
                                        try {
                                            // Calculate remaining bytes from offset
                                            long remainingBytes = fileService.sizeFrom(dlFilename, dlOffset);

                                            // Send OK, then the byte count, then the file bytes
                                            out.writeUTF(Protocol.RESP_OK);
                                            out.writeLong(remainingBytes);
                                            fileService.sendFile(dlFilename, dlOffset, out);

                                            System.out.println("[Server] Download complete: " + dlFilename
                                                    + " (" + remainingBytes + " bytes, offset "
                                                    + dlOffset + ") to " + session.getUsername());
                                        } catch (FileNotFoundException e) {
                                            String notFound = Protocol.error("File not found: " + dlFilename);
                                            out.writeUTF(notFound);
                                            out.flush();
                                            System.out.println("[Server] File not found: " + dlFilename);
                                        } catch (IllegalArgumentException e) {
                                            String badOffset = Protocol.error(e.getMessage());
                                            out.writeUTF(badOffset);
                                            out.flush();
                                            System.out.println("[Server] Invalid offset: " + e.getMessage());
                                        }
                                    }
                                    break;

                                case Protocol.CMD_EXIT:
                                    // Client requested disconnect
                                    out.writeUTF(Protocol.RESP_OK);
                                    out.flush();
                                    running = false;
                                    System.out.println("[Server] Client requested exit."
                                            + (session != null
                                            ? " User was: " + session.getUsername()
                                            : " (not authenticated)"));
                                    break;

                                default:
                                    // Unknown command — send error, continue loop
                                    String error = Protocol.error("Unknown command: " + command);
                                    out.writeUTF(error);
                                    out.flush();
                                    System.out.println("[Server] Unknown command: " + command);
                                    break;
                            }
                        } catch (EOFException e) {
                            // Client disconnected without sending EXIT at any read step
                            System.out.println("[Server] Client disconnected unexpectedly.");
                            break;
                        }
                    }
                }
                System.out.println("[Server] Connection closed.");
            }

        } catch (IOException e) {
            System.err.println("[Server] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
