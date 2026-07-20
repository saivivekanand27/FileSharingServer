package server;

import java.time.LocalDateTime;

/**
 * Session holds the state of one authenticated client connection.
 *
 * Why Session instead of a plain boolean `authenticated` flag?
 *
 * 1. A boolean only tells you "logged in or not". A Session object carries
 *    rich context: WHO is logged in and WHEN they logged in.
 *
 * 2. In future phases, Session can be extended with additional state
 *    (e.g., current directory, permissions, upload/download progress)
 *    without modifying the Server's main loop logic.
 *
 * 3. When threading is added, each client handler thread gets its own Session
 *    instance — clean per-connection state with no shared mutable flags.
 *
 * 4. Makes logging and auditing trivial: you can print session details
 *    for every action a user takes.
 */
public class Session {

    // The authenticated username for this connection
    private final String username;

    // Timestamp when the user successfully logged in
    private final LocalDateTime loginTime;

    /**
     * Creates a new session for an authenticated user.
     *
     * @param username the username of the authenticated user
     */
    public Session(String username) {
        this.username = username;
        this.loginTime = LocalDateTime.now();
    }

    /**
     * @return the username associated with this session
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return the time when this session was created (user logged in)
     */
    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    @Override
    public String toString() {
        return "Session{user='" + username + "', loginTime=" + loginTime + "}";
    }
}
