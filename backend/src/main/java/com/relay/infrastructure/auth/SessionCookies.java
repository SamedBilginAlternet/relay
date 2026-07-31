package com.relay.infrastructure.auth;

import com.relay.application.auth.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie.
 *
 * <p>{@code HttpOnly} keeps it away from JavaScript (so an XSS cannot read it),
 * {@code Secure} keeps it off plain HTTP, and {@code SameSite=Lax} still lets it ride
 * along on the top-level redirect back from Google's consent screen — {@code Strict}
 * would drop the cookie exactly there.
 *
 * <p>It must be a cookie and not a header: {@code EventSource} cannot send custom
 * headers, and {@code GET /api/runs/{id}/stream} is an EventSource.
 */
public class SessionCookies {

    public static final String NAME = "relay_session";
    /** Short-lived cookie that carries the OAuth {@code state} across the redirect. */
    public static final String STATE_NAME = "relay_oauth_state";

    private final boolean secure;

    public SessionCookies(boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie session(String token) {
        return base(NAME, token)
                .maxAge(AuthService.SESSION_TTL)
                .build();
    }

    public ResponseCookie clearSession() {
        return base(NAME, "").maxAge(0).build();
    }

    public ResponseCookie state(String value) {
        return ResponseCookie.from(STATE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(600)
                .build();
    }

    public ResponseCookie clearState() {
        return ResponseCookie.from(STATE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static String token(HttpServletRequest request) {
        return read(request, NAME);
    }
}
