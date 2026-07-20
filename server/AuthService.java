package server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthService manages user credentials for authentication.
 *
 * On construction, it loads username:password pairs from a flat file (users.txt)
 * into a ConcurrentHashMap. If the file doesn't exist, it creates one with a
 * default user.
 *
 * Design decisions:
 * - ConcurrentHashMap is used even before threading is introduced, because this
 *   service will be shared across client-handler threads in future phases.
 *   Designing for thread-safety upfront avoids a risky refactor later.
 * - Passwords are stored in plaintext for simplicity in this learning project.
 *   In production, you would hash passwords with bcrypt/scrypt/argon2.
 */
public class AuthService {

    // File where credentials are stored (one "username:password" per line)
    private static final String CREDENTIALS_FILE = "users.txt";

    // Default user created when users.txt doesn't exist
    private static final String DEFAULT_USER = "alice";
    private static final String DEFAULT_PASS = "password123";

    // Thread-safe map: username -> password
    // ConcurrentHashMap chosen for future multi-threaded access safety
    private final ConcurrentHashMap<String, String> credentials;

    /**
     * Constructs the AuthService by loading credentials from users.txt.
     * If the file doesn't exist, creates it with a default user.
     *
     * @throws IOException if the file cannot be read or created
     */
    public AuthService() throws IOException {
        this.credentials = new ConcurrentHashMap<>();
        loadCredentials();
    }

    /**
     * Loads credentials from users.txt into memory.
     * If the file doesn't exist, creates it with a default entry.
     *
     * File format: one "username:password" pair per line.
     * Lines that are blank or malformed (no colon) are silently skipped.
     */
    private void loadCredentials() throws IOException {
        Path path = Paths.get(CREDENTIALS_FILE);

        // If users.txt doesn't exist, create it with default credentials
        if (!Files.exists(path)) {
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                writer.write(DEFAULT_USER + ":" + DEFAULT_PASS);
                writer.newLine();
            }
            System.out.println("[AuthService] Created " + CREDENTIALS_FILE
                    + " with default user: " + DEFAULT_USER);
        }

        // Read all credential lines into the map
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip blank lines
                if (line.isEmpty()) {
                    continue;
                }

                // Split on the FIRST colon only — passwords may contain colons
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    System.err.println("[AuthService] Skipping malformed line "
                            + lineNumber + " in " + CREDENTIALS_FILE);
                    continue;
                }

                String username = line.substring(0, colonIndex).trim();
                String password = line.substring(colonIndex + 1).trim();

                // Reject entries with empty username (e.g. "  :password")
                if (username.isEmpty()) {
                    System.err.println("[AuthService] Skipping entry with empty username at line "
                            + lineNumber + " in " + CREDENTIALS_FILE);
                    continue;
                }

                credentials.put(username, password);
            }
        }

        System.out.println("[AuthService] Loaded " + credentials.size() + " user(s).");
    }

    /**
     * Authenticates a user by checking the provided credentials against stored ones.
     *
     * This is pure authentication ("who are you?"), NOT authorization ("what can you do?").
     *
     * @param username the username to check
     * @param password the password to verify
     * @return true if the username exists and the password matches, false otherwise
     */
    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        String storedPassword = credentials.get(username);

        // Use constant-time comparison style: check stored != null first,
        // then compare. In production you'd use MessageDigest.isEqual()
        // on hashed values to prevent timing attacks.
        return storedPassword != null && storedPassword.equals(password);
    }
}
