package server;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server listens on the configured port, accepts a single client connection,
 * reads one UTF-encoded message, prints it, sends a reply, and shuts down.
 *
 * This is the Phase 1 proof-of-concept for basic client-server communication
 * using raw TCP sockets and Java's DataInputStream/DataOutputStream.
 */
public class Server {

    public static void main(String[] args) {

        // try-with-resources ensures ServerSocket is closed even if an exception occurs
        try (ServerSocket serverSocket = new ServerSocket(Protocol.PORT)) {

            System.out.println("[Server] Listening on port " + Protocol.PORT + "...");

            // Block until a client connects
            // clientSocket is in its own try-with-resources to guarantee closure
            // even if getInputStream() or getOutputStream() throws
            try (Socket clientSocket = serverSocket.accept()) {

                System.out.println("[Server] Client connected: " + clientSocket.getRemoteSocketAddress());

                // Wrap the socket's raw byte streams with Data streams for typed I/O
                try (
                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                    DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())
                ) {
                    // Read one UTF-encoded string sent by the client
                    String clientMessage = in.readUTF();
                    System.out.println("[Server] Received: " + clientMessage);

                    // Send a reply back to the client
                    String reply = "Hello from server";
                    out.writeUTF(reply);
                    out.flush(); // Ensure the reply is pushed to the network immediately
                    System.out.println("[Server] Sent: " + reply);
                }
                // clientSocket.close() is called automatically by try-with-resources
                System.out.println("[Server] Connection closed.");
            }

        } catch (IOException e) {
            System.err.println("[Server] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
