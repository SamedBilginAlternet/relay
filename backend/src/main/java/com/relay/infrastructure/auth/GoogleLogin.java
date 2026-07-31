package com.relay.infrastructure.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Google ile devam et" — identity only.
 *
 * <p>Deliberately separate from {@link com.relay.infrastructure.google.GoogleOAuth}, which
 * asks for Gmail and Calendar data. Signing in must not make anyone hand over their mailbox:
 * this flow asks for {@code openid email profile} and nothing else, keeps its own redirect
 * URI ({@code /api/auth/google/callback}) and stores no tokens — the profile is read once
 * and thrown away. Granting data access stays a second, explicit consent on the
 * Connections screen.
 *
 * <p>It reuses the same OAuth client id/secret, so the only new thing to register in the
 * Google console is the extra redirect URI ({@code GOOGLE_LOGIN_REDIRECT_URI}).
 */
@Component
public class GoogleLogin {

    public static final String SCOPES = "openid email profile";

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_PATH = "/api/auth/google/callback";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final String clientId;
    private final String clientSecret;
    private final String configuredRedirectUri;

    public GoogleLogin(@Value("${app.google.client-id:}") String clientId,
                       @Value("${app.google.client-secret:}") String clientSecret,
                       @Value("${app.auth.google.redirect-uri:}") String redirectUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.configuredRedirectUri = redirectUri == null ? "" : redirectUri.trim();
    }

    /** The profile Google vouches for. Nothing else is kept. */
    public record Profile(String email, String name, String picture, boolean emailVerified) {
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /**
     * Configured value wins; otherwise the callback on the host the browser just used —
     * which is what makes this work on localhost without another env var.
     */
    public String redirectUri(String baseUrl) {
        if (!configuredRedirectUri.isBlank()) {
            return configuredRedirectUri;
        }
        return baseUrl.replaceAll("/+$", "") + DEFAULT_PATH;
    }

    public String authorizationUrl(String state, String redirectUri) {
        require();
        return AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPES)
                + "&include_granted_scopes=false"
                + "&prompt=select_account"
                + "&state=" + enc(state);
    }

    /** Authorization code → the signed-in person. No token is persisted. */
    public Profile exchange(String code, String redirectUri) {
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
        String idToken = token.path("id_token").asText("");
        if (idToken.isBlank()) {
            throw new IllegalStateException("google did not return an id_token");
        }
        // The token came straight from Google's TLS endpoint in response to our client
        // secret, so the signature adds nothing here — read the claims and move on.
        JsonNode claims = claimsOf(idToken);
        String email = claims.path("email").asText("");
        if (email.isBlank()) {
            throw new IllegalStateException("google account has no e-mail address");
        }
        return new Profile(email, claims.path("name").asText(""), claims.path("picture").asText(""),
                claims.path("email_verified").asBoolean(false));
    }

    // ---- plumbing ---------------------------------------------------------

    private void require() {
        if (!configured()) {
            throw new IllegalStateException("google sign-in is not configured — set GOOGLE_CLIENT_ID and "
                    + "GOOGLE_CLIENT_SECRET");
        }
    }

    static JsonNode claimsOf(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IllegalStateException("malformed id_token");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return Json.parse(payload);
    }

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
                // Error bodies can echo the client_secret — never log or forward them raw.
                throw new IllegalStateException("google token endpoint returned HTTP " + response.statusCode());
            }
            return Json.parse(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("google sign-in exchange failed: " + e.getClass().getSimpleName());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
