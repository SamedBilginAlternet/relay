package com.relay.application.port;

/**
 * Told the moment a session stops being valid.
 *
 * <p>Per-request authorisation is enough for a request. It is not enough for a connection
 * that stays open for half an hour: the SSE stream is checked once, when it is opened, and
 * from then on it keeps pushing step parameters, tool results and agent messages down a
 * line whose session may have been signed out ten minutes ago. Revocability is the entire
 * reason the sessions are rows and not signed cookies (docs/ARCHITECTURE.md §4), and it has
 * to reach whatever is still holding a connection open.
 */
public interface SessionListener {

    /** @param tokenHash the SHA-256 of the cookie value, as stored on the session row */
    void sessionEnded(String tokenHash);
}
