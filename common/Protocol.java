package common;

/**
 * Protocol defines all shared constants used by both client and server.
 * 
 * Centralizing protocol constants here ensures that the client and server
 * always agree on connection parameters. Any change to the port or host
 * only needs to happen in one place.
 */
public final class Protocol {

    // Server listens on this port
    public static final int PORT = 5000;

    // Client connects to this host
    public static final String HOST = "localhost";

    // Prevent instantiation — this is a constants-only utility class
    private Protocol() {
        throw new AssertionError("Protocol is a constants class and cannot be instantiated");
    }
}
