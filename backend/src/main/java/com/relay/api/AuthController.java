package com.relay.api;

import com.relay.application.auth.AuthException;
import com.relay.application.auth.AuthService;
import com.relay.domain.User;
import com.relay.infrastructure.auth.AuthFilter;
import com.relay.infrastructure.auth.GoogleLogin;
import com.relay.infrastructure.auth.SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up, sign-in, session and onboarding state.
 *
 * <p>Everything under {@code /api/auth/**} is exempt from {@link AuthFilter} — you cannot
 * sign in through a door that requires being signed in — so the two endpoints that do need
 * a session ({@code /me}, {@code /onboarding/complete}) check it themselves.
 *
 * <p>Reminder on scope: Relay is one shared workspace. Signing in identifies the person,
 * it does not give them a private copy of the data.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final GoogleLogin google;
    private final SessionCookies cookies;
    private final String successRedirect;

    public AuthController(AuthService auth, GoogleLogin google, SessionCookies cookies,
                          @Value("${app.auth.success-redirect:/}") String successRedirect) {
        this.auth = auth;
        this.google = google;
        this.cookies = cookies;
        this.successRedirect = successRedirect == null || successRedirect.isBlank() ? "/" : successRedirect.trim();
    }

    public record Credentials(String email, String password, String displayName) {
    }

    // ---- e-mail + password ------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Credentials body) {
        Credentials input = body == null ? new Credentials(null, null, null) : body;
        User user = auth.register(input.email(), input.password(), input.displayName());
        return withSession(HttpStatus.CREATED, user);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Credentials body) {
        Credentials input = body == null ? new Credentials(null, null, null) : body;
        User user = auth.login(input.email(), input.password());
        return withSession(HttpStatus.OK, user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        auth.logout(SessionCookies.token(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", false);
        body.put("user", null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString())
                .body(body);
    }

    /**
     * The SPA's boot call. Answers 200 either way — "nobody is signed in" is a normal
     * answer to this question, not an error, and the app needs {@code googleLogin} to know
     * whether to draw the Google button.
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Optional<User> user = AuthFilter.current(request)
                .or(() -> auth.authenticate(SessionCookies.token(request)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", user.isPresent());
        body.put("user", user.map(AuthController::view).orElse(null));
        body.put("googleLogin", google.configured());
        return body;
    }

    /** Marks the onboarding tour as done for this account — it must not come back. */
    @PostMapping("/onboarding/complete")
    public ResponseEntity<Map<String, Object>> completeOnboarding(HttpServletRequest request) {
        User user = AuthFilter.current(request)
                .or(() -> auth.authenticate(SessionCookies.token(request)))
                .orElseThrow(() -> AuthException.unauthorized("Oturum bulunamadı. Lütfen giriş yap."));
        User updated = auth.completeOnboarding(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("user", view(updated));
        return ResponseEntity.ok(body);
    }

    // ---- Google sign-in ---------------------------------------------------

    /**
     * Identity only ({@code openid email profile}). Gmail/Calendar access is a different
     * consent on the Connections screen and is not requested here.
     */
    @GetMapping("/google/start")
    public ResponseEntity<?> googleStart(HttpServletRequest request) {
        if (!google.configured()) {
            return googleNotConfigured();
        }
        String state = UUID.randomUUID().toString();
        String url = google.authorizationUrl(state, google.redirectUri(baseUrl(request)));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookies.state(state).toString())
                .location(URI.create(url))
                .build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<?> googleCallback(HttpServletRequest request,
                                            @RequestParam(required = false) String code,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false) String error) {
        if (!google.configured()) {
            return googleNotConfigured();
        }
        if (error != null && !error.isBlank()) {
            return redirectWithError("google_denied");
        }
        String expected = SessionCookies.read(request, SessionCookies.STATE_NAME);
        if (expected == null || state == null || !expected.equals(state)) {
            // Someone replayed or forged the redirect — start over rather than sign anyone in.
            return redirectWithError("state_mismatch");
        }
        GoogleLogin.Profile profile = google.exchange(code, google.redirectUri(baseUrl(request)));
        if (!profile.emailVerified()) {
            return redirectWithError("email_not_verified");
        }
        User user = auth.loginWithGoogle(profile.email(), profile.name(), profile.picture());
        String token = auth.startSession(user);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookies.session(token).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearState().toString())
                .location(URI.create(successRedirect))
                .build();
    }

    // ---- errors -----------------------------------------------------------

    /**
     * Local handler so a bad password stays a 400/401 with a field name; the global
     * {@link ApiExceptionHandler} would turn it into a 500.
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> authError(AuthException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("field", e.field());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    // ---- helpers ----------------------------------------------------------

    private ResponseEntity<Map<String, Object>> withSession(HttpStatus status, User user) {
        String token = auth.startSession(user);
        ResponseCookie cookie = cookies.session(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("user", view(user));
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    static Map<String, Object> view(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id().toString());
        out.put("email", user.email());
        out.put("displayName", user.displayName());
        out.put("avatarUrl", user.avatarUrl());
        out.put("provider", user.provider());
        out.put("onboarded", user.onboarded());
        out.put("createdAt", user.createdAt());
        return out;
    }

    private ResponseEntity<Map<String, Object>> googleNotConfigured() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "google_not_configured");
        body.put("message", "Google ile giriş için GOOGLE_CLIENT_ID ve GOOGLE_CLIENT_SECRET tanımlı değil.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** The browser is mid-navigation: send it back to the login screen with a reason. */
    private ResponseEntity<?> redirectWithError(String reason) {
        String target = successRedirect + (successRedirect.contains("#") ? "" : "#/giris")
                + (successRedirect.contains("?") ? "&" : "?") + "hata=" + reason;
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookies.clearState().toString())
                .location(URI.create(target))
                .build();
    }

    /** Honours the proxy headers Caddy sets, so the redirect URI matches the public host. */
    private static String baseUrl(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto", request.getScheme());
        String host = header(request, "X-Forwarded-Host", null);
        if (host == null) {
            host = request.getServerName();
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443);
            if (!defaultPort) {
                host = host + ":" + port;
            }
        }
        return proto + "://" + host;
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.split(",")[0].trim();
    }
}
