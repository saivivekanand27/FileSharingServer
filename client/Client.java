package client;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Client connects to the server, sends a single UTF-encoded message,
 * reads the server's reply, prints it, and disconnects.
 *
 * This is the Phase 1 proof-of-concept for basic client-server communication
 * using raw TCP sockets and Java's DataInputStream/DataOutputStream.
 */
public class Client {

    public static void main(String[] args) {

        // try-with-resources ensures the socket and streams are closed automatically
        try (
            Socket socket = new Socket(Protocol.HOST, Protocol.PORT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream())
        ) {
            System.out.println("[Client] Connected to server at " + Protocol.HOST + ":" + Protocol.PORT);

            // Send a greeting to the server
            String message = "Hello from client";
            out.writeUTF(message);
            out.flush(); // Ensure the message is pushed to the network immediately
            System.out.println("[Client] Sent: " + message);

            // Read the server's reply
            String serverReply = in.readUTF();
            System.out.println("[Client] Received: " + serverReply);

            System.out.println("[Client] Disconnected.");

        } catch (IOException e) {
            System.err.println("[Client] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
