package client;

import common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

/**
 * Client connects to the server and presents a console menu for user interaction.
 *
 * Phase 4: Supports LOGIN, UPLOAD, DOWNLOAD, and EXIT commands.
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
                System.out.println("|  2) Upload File              |");
                System.out.println("|  3) Download File            |");
                System.out.println("|  4) Exit                     |");
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
                        // ── Upload Flow ──────────────────────────────
                        System.out.print("Enter file path: ");
                        String filePath = scanner.nextLine().trim();

                        if (filePath.isEmpty()) {
                            System.out.println("[Client] Error: File path cannot be empty.");
                            break;
                        }

                        File file = new File(filePath);

                        // Validate the file exists and is readable
                        if (!file.exists() || !file.isFile()) {
                            System.out.println("[Client] Error: File not found: " + filePath);
                            break;
                        }

                        if (!file.canRead()) {
                            System.out.println("[Client] Error: Cannot read file: " + filePath);
                            break;
                        }

                        // Send UPLOAD command with filename and file size
                        String fileName = file.getName();
                        long fileSize = file.length();

                        out.writeUTF(Protocol.CMD_UPLOAD);
                        out.writeUTF(fileName);
                        out.writeLong(fileSize);

                        // Stream file bytes to the server in chunks
                        // BufferedInputStream reduces disk read syscalls
                        try (BufferedInputStream fileIn = new BufferedInputStream(
                                new FileInputStream(file))) {
                            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
                            long remaining = fileSize;

                            while (remaining > 0) {
                                int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, remaining);
                                int bytesRead = fileIn.read(buffer, 0, chunkSize);
                                if (bytesRead == -1) {
                                    // File was shorter than expected (e.g., truncated during read)
                                    throw new IOException("Unexpected end of file while reading: " + filePath);
                                }
                                out.write(buffer, 0, bytesRead);
                                remaining -= bytesRead;
                            }
                        }
                        out.flush();

                        System.out.println("[Client] Sent file: " + fileName
                                + " (" + fileSize + " bytes)");

                        // Read server response
                        String uploadResponse = in.readUTF();
                        if (Protocol.isError(uploadResponse)) {
                            System.out.println("[Client] Upload failed: " + uploadResponse);
                        } else {
                            System.out.println("[Client] Upload successful!");
                        }
                        break;

                    case "3":
                        // ── Download Flow ────────────────────────────
                        System.out.print("Enter filename to download: ");
                        String dlName = scanner.nextLine().trim();

                        if (dlName.isEmpty()) {
                            System.out.println("[Client] Error: Filename cannot be empty.");
                            break;
                        }

                        File dlFile = new File(dlName);
                        long offset = 0;
                        boolean append = false;

                        // Check if a partial file already exists locally
                        if (dlFile.exists() && dlFile.isFile()) {
                            offset = dlFile.length();
                            append = true;
                            System.out.println("[Client] Local partial file found (" + offset + " bytes). Resuming download...");
                        }

                        // Send DOWNLOAD command with filename and current offset
                        out.writeUTF(Protocol.CMD_DOWNLOAD);
                        out.writeUTF(dlName);
                        out.writeLong(offset);
                        out.flush();

                        // Read server response
                        String dlResponse = in.readUTF();
                        if (Protocol.isError(dlResponse)) {
                            System.out.println("[Client] Download failed: " + dlResponse);
                            break;
                        }

                        // Server responded OK — read the remaining byte count
                        long dlSize = in.readLong();
                        
                        if (dlSize == 0) {
                            System.out.println("[Client] File is already fully downloaded (" + offset + " bytes).");
                            break;
                        }

                        System.out.println("[Client] Downloading remaining " + dlSize + " bytes for " + dlName + "...");

                        // Read exactly dlSize bytes and append/write to the local file
                        try (BufferedOutputStream dlOut = new BufferedOutputStream(
                                new FileOutputStream(dlFile, append))) {
                            byte[] dlBuffer = new byte[Protocol.BUFFER_SIZE];
                            long dlRemaining = dlSize;

                            while (dlRemaining > 0) {
                                int chunkSize = (int) Math.min(Protocol.BUFFER_SIZE, dlRemaining);
                                in.readFully(dlBuffer, 0, chunkSize);
                                dlOut.write(dlBuffer, 0, chunkSize);
                                dlRemaining -= chunkSize;
                            }
                            dlOut.flush();
                        }

                        System.out.println("[Client] Download successful! Saved to: "
                                + dlFile.getAbsolutePath());
                        break;

                    case "4":
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
                        System.out.println("[Client] Invalid option. Please choose 1, 2, 3, or 4.");
                        break;
                }
            }

        } catch (IOException e) {
            System.err.println("[Client] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
