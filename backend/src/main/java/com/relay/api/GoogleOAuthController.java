package com.relay.api;

import com.relay.infrastructure.google.GoogleOAuth;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google authorization-code flow for Gmail + Calendar.
 *
 * <p>Both endpoints are safe to call on an installation with no Google credentials: they
 * answer {@code 503 google_not_configured} instead of throwing, so the rest of the app —
 * and the brief — keeps working.
 */
@RestController
@RequestMapping("/api/oauth/google")
public class GoogleOAuthController {

    private final GoogleOAuth oauth;
    private final String successRedirect;

    public GoogleOAuthController(GoogleOAuth oauth,
                                 @Value("${app.google.success-redirect:}") String successRedirect) {
        this.oauth = oauth;
        this.successRedirect = successRedirect == null ? "" : successRedirect.trim();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return oauth.status();
    }

    /** Sends the browser to Google's consent screen. */
    @GetMapping("/start")
    public ResponseEntity<?> start() {
        if (!oauth.configured()) {
            return notConfigured();
        }
        String url = oauth.authorizationUrl(UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** Google redirects here with {@code ?code=…}; the refresh token lands in the connection. */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error) {
        if (!oauth.configured()) {
            return notConfigured();
        }
        if (error != null && !error.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "google_denied");
            body.put("message", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Map<String, Object> result = oauth.exchangeCode(code);
        result.put("state", state);
        if (!successRedirect.isBlank()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(successRedirect)).build();
        }
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> notConfigured() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "google_not_configured");
        body.put("message", "GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET ve GOOGLE_REDIRECT_URI tanımlı değil");
        body.put("status", "unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
