package com.relay.infrastructure.auth;

import com.relay.application.auth.AuthService;
import com.relay.domain.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/**} with the session cookie.
 *
 * <p>Unauthenticated requests get {@code 401} with a JSON body — never a redirect to a
 * login page. The frontend is a single-page app: an HTML redirect would land inside a
 * {@code fetch()} and show up as an unreadable parse error instead of "sign in".
 *
 * <p>Exempt: {@code /api/health} (uptime checks), {@code /api/auth/**} (you cannot sign
 * in through a door that requires being signed in) and {@code /api/oauth/google/callback}
 * (Google redirects the browser there and cannot carry our cookie policy). Everything
 * else — including the SSE stream — needs a session.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "relay.user";

    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/api/health",
            "/api/auth/",
            "/api/oauth/google/callback");

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"unauthorized\",\"message\":\"Oturum bulunamadı. Lütfen giriş yap.\"}";

    private final AuthService auth;
    private final boolean enabled;

    public AuthFilter(AuthService auth, boolean enabled) {
        this.auth = auth;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enabled || !path.startsWith("/api") || isExempt(path) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // Attach the user when there is one even on exempt paths: /api/auth/me needs it.
            attachIfPresent(request);
            chain.doFilter(request, response);
            return;
        }

        Optional<User> user = auth.authenticate(SessionCookies.token(request));
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user.get());
        chain.doFilter(request, response);
    }

    private void attachIfPresent(HttpServletRequest request) {
        String token = SessionCookies.token(request);
        if (token != null && !token.isBlank()) {
            auth.authenticate(token).ifPresent(user -> request.setAttribute(USER_ATTRIBUTE, user));
        }
    }

    static boolean isExempt(String path) {
        if ("/api/auth".equals(path) || "/api/health".equals(path)) {
            return true;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The signed-in user for this request, if the filter put one there. */
    public static Optional<User> current(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ATTRIBUTE);
        return value instanceof User user ? Optional.of(user) : Optional.empty();
    }
}
