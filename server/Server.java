package server;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server listens on the configured port and handles one client at a time.
 *
 * Phase 2: Supports LOGIN and EXIT commands with real credential checking
 * via AuthService. Each successful login creates a Session object that
 * tracks the authenticated user.
 *
 * The server runs a command loop per client connection:
 *   1. Read a command string from the client
 *   2. Dispatch to the appropriate handler (LOGIN / EXIT)
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
