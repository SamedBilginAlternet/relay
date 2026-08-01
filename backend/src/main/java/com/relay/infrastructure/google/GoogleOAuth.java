package com.relay.infrastructure.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.domain.Connection;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The whole Google seam: authorization-code exchange, refresh-token storage and
 * access-token renewal. Gmail and Calendar tools only ask it for a bearer token.
 *
 * <p>Configuration comes from the environment ({@code GOOGLE_CLIENT_ID},
 * {@code GOOGLE_CLIENT_SECRET}, {@code GOOGLE_REDIRECT_URI}). When any of them is
 * missing the seam reports {@link #configured()} {@code false} — the app still boots
 * and every other integration keeps working; the Google tools simply fall back to
 * replay/unavailable.
 *
 * <p>The refresh token lives inside the encrypted {@code google} connection, next to
 * the Jira and Slack secrets. It is never logged.
 */
@Component
public class GoogleOAuth {

    public static final String PROVIDER = "google";

    /**
     * The one write Relay asks Google for: a draft in the user's own Drafts folder.
     * {@code gmail.compose} cannot send — it can only create and edit drafts — so the
     * grant itself, not just our code, rules out mail leaving on the user's behalf.
     */
    public static final String COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";

    /** Reading is what the daily brief needs; composing is what answering a mail needs. */
    public static final String SCOPES = String.join(" ",
            "https://www.googleapis.com/auth/gmail.readonly",
            COMPOSE_SCOPE,
            "https://www.googleapis.com/auth/calendar.readonly",
            "openid", "email");

    /**
     * Does the grant behind this connection cover {@code scope}?
     *
     * <p>Widening {@link #SCOPES} does not revoke anything: a connection made before
     * {@link #COMPOSE_SCOPE} was asked for keeps reading mail and calendar exactly as
     * before. It simply cannot write, and the honest place to say so is before the call,
     * in a sentence naming the button to press — not as a provider 403 halfway through.
     *
     * <p>A connection with no recorded {@code scope} is treated as unknown rather than
     * empty: those tokens predate the field, and refusing them here would invent a
     * permission problem the provider may not have.
     */
    public static boolean granted(Connection connection, String scope) {
        if (connection == null || scope == null || scope.isBlank()) {
            return false;
        }
        String recorded = connection.get("scope");
        if (recorded == null || recorded.isBlank()) {
            return true;
        }
        for (String one : recorded.trim().split("\\s+")) {
            if (one.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final Logger LOG = System.getLogger(GoogleOAuth.class.getName());

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ConnectionRepository connections;
    private final Clock clock;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleOAuth(ConnectionRepository connections, Clock clock,
                       @Value("${app.google.client-id:}") String clientId,
                       @Value("${app.google.client-secret:}") String clientSecret,
                       @Value("${app.google.redirect-uri:}") String redirectUri) {
        this.connections = connections;
        this.clock = clock;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        LOG.log(Level.INFO, "google oauth configured: {0}", configured());
    }

    /** False when the env vars are absent — the tools then report unavailable. */
    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public boolean connected() {
        return connections.findByProvider(PROVIDER)
                .map(c -> notBlank(c.get("refreshToken")) || notBlank(c.get("accessToken")))
                .orElse(false);
    }

    public String authorizationUrl(String state) {
        require();
        return AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPES)
                + "&access_type=offline"
                + "&include_granted_scopes=true"
                + "&prompt=consent"
                + (state == null || state.isBlank() ? "" : "&state=" + enc(state));
    }

    /** Authorization code → tokens, stored on the encrypted {@code google} connection. */
    public Map<String, Object> exchangeCode(String code) {
        require();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("authorization code is required");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");

        JsonNode token = post(form);
        Connection connection = connections.findByProvider(PROVIDER)
                .orElseGet(() -> Connection.of(PROVIDER, new LinkedHashMap<>(), clock.now()));

        Map<String, String> config = new LinkedHashMap<>(connection.config());
        String refresh = token.path("refresh_token").asText("");
        if (!refresh.isBlank()) {
            config.put("refreshToken", refresh);
        }
        config.put("accessToken", token.path("access_token").asText(""));
        config.put("expiresAt", String.valueOf(expiryOf(token)));
        config.put("scope", token.path("scope").asText(SCOPES));
        connection.config(config);
        connections.save(connection);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", PROVIDER);
        out.put("connected", true);
        out.put("hasRefreshToken", config.containsKey("refreshToken"));
        out.put("scope", config.get("scope"));
        out.put("expiresAt", config.get("expiresAt"));
        return out;
    }

    /**
     * A bearer token that is valid right now. Refreshes (and persists) when the stored
     * one is within a minute of expiry.
     */
    public String accessToken(Connection connection) {
        require();
        if (connection == null) {
            throw new IllegalStateException("google connection is not configured");
        }
        long expiresAt = parseLong(connection.get("expiresAt"));
        String access = connection.get("accessToken");
        if (notBlank(access) && expiresAt - 60 > clock.now().getEpochSecond()) {
            return access;
        }
        String refresh = connection.get("refreshToken");
        if (!notBlank(refresh)) {
            if (notBlank(access)) {
                return access; // no refresh token — ride the current one until it dies
            }
            throw new IllegalStateException("google connection has no refresh token — reconnect at "
                    + "/api/oauth/google/start");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refresh);
        form.put("grant_type", "refresh_token");
        JsonNode token = post(form);

        Map<String, String> config = new LinkedHashMap<>(connection.config());
        String fresh = token.path("access_token").asText("");
        config.put("accessToken", fresh);
        config.put("expiresAt", String.valueOf(expiryOf(token)));
        connection.config(config);
        connections.save(connection);
        return fresh;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", PROVIDER);
        out.put("configured", configured());
        out.put("connected", connected());
        out.put("scopes", SCOPES);
        // Whether the *stored* grant still matches the asked-for one. A connection made
        // before gmail.compose reads connected=true and canCompose=false — which is the
        // whole difference between "reconnect" and "nothing to do".
        out.put("canCompose", connections.findByProvider(PROVIDER)
                .map(connection -> granted(connection, COMPOSE_SCOPE))
                .orElse(false));
        out.put("redirectUri", redirectUri);
        out.put("startUrl", "/api/oauth/google/start");
        return out;
    }

    // ---- plumbing ---------------------------------------------------------

    private void require() {
        if (!configured()) {
            throw new IllegalStateException("google oauth is not configured — set GOOGLE_CLIENT_ID, "
                    + "GOOGLE_CLIENT_SECRET and GOOGLE_REDIRECT_URI");
        }
    }

    /** Google's token endpoint speaks form-urlencoded, not JSON. */
    private JsonNode post(Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(enc(key)).append('=').append(enc(value));
        });
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // The body can echo the client_secret back on some errors — never log it raw.
                throw new IllegalStateException("google token endpoint returned HTTP " + response.statusCode());
            }
            return Json.parse(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("google token exchange failed: " + e.getClass().getSimpleName());
        }
    }

    private long expiryOf(JsonNode token) {
        long expiresIn = token.path("expires_in").asLong(3600);
        return clock.now().getEpochSecond() + expiresIn;
    }

    private static long parseLong(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
