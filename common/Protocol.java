package common;

/**
 * Protocol defines all shared constants and helpers used by both client and server.
 *
 * Centralizing protocol constants here ensures that the client and server
 * always agree on connection parameters and command strings.
 * Any change to the protocol only needs to happen in one place.
 */
public final class Protocol {

    // ── Connection Settings ──────────────────────────────────────────

    // Server listens on this port
    public static final int PORT = 5000;

    // Client connects to this host
    public static final String HOST = "localhost";

    // ── Command Constants ────────────────────────────────────────────
    // Commands sent from client to server as the first UTF string in a request

    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_EXIT  = "EXIT";

    // ── Response Constants ───────────────────────────────────────────
    // Responses sent from server to client

    public static final String RESP_OK = "OK";

    // Error prefix — all error responses start with "ERROR:" followed by a reason
    private static final String ERROR_PREFIX = "ERROR:";

    /**
     * Builds a standardized error response string.
     * Format: "ERROR:<reason>"
     *
     * @param reason the human-readable reason for the error
     * @return the formatted error string, e.g. "ERROR:Invalid credentials"
     */
    public static String error(String reason) {
        return ERROR_PREFIX + reason;
    }

    /**
     * Checks whether a server response is an error response.
     *
     * @param response the response string from the server
     * @return true if the response starts with the error prefix
     */
    public static boolean isError(String response) {
        return response != null && response.startsWith(ERROR_PREFIX);
    }

    // Prevent instantiation — this is a constants-only utility class
    private Protocol() {
        throw new AssertionError("Protocol is a constants class and cannot be instantiated");
    }
}
