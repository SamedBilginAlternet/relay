package com.relay.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.domain.Connection;
import com.relay.support.TestDoubles;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoogleOAuthSafetyTest {

    @Test
    void credentials_alone_cannot_enable_workspace_global_google_access() {
        TestDoubles.InMemoryConnectionRepository connections =
                new TestDoubles.InMemoryConnectionRepository();
        connections.save(Connection.of("google", Map.of(
                "refreshToken", "must-never-be-used",
                "scope", GoogleOAuth.SCOPES), new TestDoubles.FixedClock().now()));
        GoogleOAuth oauth = new GoogleOAuth(
                connections,
                new TestDoubles.FixedClock(),
                "client-id",
                "client-secret",
                "https://relay.example/api/oauth/google/callback",
                false);

        assertThat(oauth.configured()).isFalse();
        assertThat(oauth.status()).containsEntry("configured", false)
                .containsEntry("connected", false)
                .containsEntry("canCompose", false)
                .containsEntry("canCreateEvent", false);
    }

    @Test
    void google_requires_both_the_explicit_gate_and_complete_credentials() {
        GoogleOAuth enabled = new GoogleOAuth(
                new TestDoubles.InMemoryConnectionRepository(),
                new TestDoubles.FixedClock(),
                "client-id",
                "client-secret",
                "https://relay.example/api/oauth/google/callback",
                true);
        GoogleOAuth missingSecret = new GoogleOAuth(
                new TestDoubles.InMemoryConnectionRepository(),
                new TestDoubles.FixedClock(),
                "client-id",
                "",
                "https://relay.example/api/oauth/google/callback",
                true);

        assertThat(enabled.configured()).isTrue();
        assertThat(missingSecret.configured()).isFalse();
    }
}
