package com.relay.infrastructure.tools;

import com.relay.domain.Connection;
import com.relay.infrastructure.google.GoogleOAuth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared base for the Google-backed tools (Gmail, Calendar).
 *
 * <p>They are named {@code gmail.*} / {@code calendar.*} because that is what the LLM
 * and the policy engine address, but they all share one {@code google} connection —
 * hence the {@link #provider()} override.
 *
 * <p>Without {@code GOOGLE_CLIENT_ID}/{@code GOOGLE_CLIENT_SECRET}/{@code GOOGLE_REDIRECT_URI}
 * the connection is not usable, {@link AbstractTool} falls back to the recorded fixture and
 * the brief marks the section unavailable. Nothing else breaks.
 */
public abstract class GoogleTool extends AbstractTool {

    protected final GoogleOAuth oauth;

    protected GoogleTool(ToolsMode mode, FixtureStore fixtures, GoogleOAuth oauth) {
        super(mode, fixtures);
        this.oauth = oauth;
    }

    @Override
    public String provider() {
        return GoogleOAuth.PROVIDER;
    }

    @Override
    protected boolean usable(Connection connection) {
        return oauth != null && oauth.configured()
                && (notBlank(connection.get("refreshToken")) || notBlank(connection.get("accessToken")));
    }

    protected Map<String, String> headers(Connection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + oauth.accessToken(connection));
        return headers;
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
