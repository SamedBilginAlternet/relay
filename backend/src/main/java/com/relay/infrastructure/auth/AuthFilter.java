package com.relay.infrastructure.auth;

import com.relay.application.auth.AuthService;
import com.relay.application.port.UserScope;
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
 * <p>Exempt: {@code /api/health} exactly (uptime checks), {@code /api/auth/**} (you cannot
 * sign in through a door that requires being signed in). Everything
 * else — including the SSE stream and {@code /api/health/details} — needs a session.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "relay.user";

    /**
     * {@code /api/health} is exempt exactly, never as a prefix — {@code /api/health/details}
     * is the operator's view and carries what the provider is running on. A prefix here
     * would hand that to anyone who guessed the path.
     */
    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/api/auth/");

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"unauthorized\",\"message\":\"Oturum bulunamadı. Lütfen giriş yap.\"}";

    private final AuthService auth;
    private final boolean enabled;
    private final UserScope users;

    public AuthFilter(AuthService auth, boolean enabled) {
        this(auth, enabled, new UserScope());
    }

    public AuthFilter(AuthService auth, boolean enabled, UserScope users) {
        this.auth = auth;
        this.enabled = enabled;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enabled || !path.startsWith("/api") || isExempt(path) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // Attach the user when there is one even on exempt paths: /api/auth/me needs it.
            Optional<User> user = attachIfPresent(request);
            if (user.isPresent()) {
                withUser(user.get(), request, response, chain);
            } else {
                chain.doFilter(request, response);
            }
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
        withUser(user.get(), request, response, chain);
    }

    private Optional<User> attachIfPresent(HttpServletRequest request) {
        String token = SessionCookies.token(request);
        if (token != null && !token.isBlank()) {
            Optional<User> user = auth.authenticate(token);
            user.ifPresent(value -> request.setAttribute(USER_ATTRIBUTE, value));
            return user;
        }
        return Optional.empty();
    }

    private void withUser(User user, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute(USER_ATTRIBUTE, user);
        try (UserScope.Scope ignored = users.enter(user.id())) {
            chain.doFilter(request, response);
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
