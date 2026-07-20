package client;

import common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

/**
 * Client connects to the server and presents a console menu for user interaction.
 *
 * Phase 2: Supports LOGIN and EXIT commands.
 * The client maintains a loop, reading user choices from the console,
 * sending the corresponding protocol commands to the server,
 * and displaying the server's response.
 */
public class Client {

    public static void main(String[] args) {

        // try-with-resources ensures the socket, streams, and scanner are closed
        try (
            Socket socket = new Socket(Protocol.HOST, Protocol.PORT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Scanner scanner = new Scanner(System.in)
        ) {
            System.out.println("[Client] Connected to server at "
                    + Protocol.HOST + ":" + Protocol.PORT);

            boolean running = true;

            while (running) {
                // ── Display Menu ─────────────────────────────────────
                System.out.println();
                System.out.println("+------------------------------+");
                System.out.println("|     FILE SHARING CLIENT      |");
                System.out.println("+------------------------------+");
                System.out.println("|  1) Login                    |");
                System.out.println("|  2) Exit                     |");
                System.out.println("+------------------------------+");
                System.out.print("Choose an option: ");

                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1":
                        // ── Login Flow ───────────────────────────────
                        System.out.print("Username: ");
                        String username = scanner.nextLine().trim();
                        System.out.print("Password: ");
                        String password = scanner.nextLine().trim();

                        if (username.isEmpty() || password.isEmpty()) {
                            System.out.println("[Client] Error: Username and password cannot be empty.");
                            break;
                        }

                        // Send LOGIN command followed by credentials
                        out.writeUTF(Protocol.CMD_LOGIN);
                        out.writeUTF(username);
                        out.writeUTF(password);
                        out.flush();

                        // Read and display server response
                        String loginResponse = in.readUTF();
                        if (Protocol.isError(loginResponse)) {
                            System.out.println("[Client] Login failed: " + loginResponse);
                        } else {
                            System.out.println("[Client] Login successful!");
                        }
                        break;

                    case "2":
                        // ── Exit Flow ────────────────────────────────
                        out.writeUTF(Protocol.CMD_EXIT);
                        out.flush();

                        // Read server acknowledgment before closing
                        String exitResponse = in.readUTF();
                        System.out.println("[Client] Server response: " + exitResponse);
                        System.out.println("[Client] Disconnected.");
                        running = false;
                        break;

                    default:
                        // Invalid menu choice — handled client-side, no server call
                        System.out.println("[Client] Invalid option. Please choose 1 or 2.");
                        break;
                }
            }

        } catch (IOException e) {
            System.err.println("[Client] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
