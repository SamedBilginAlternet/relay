package com.relay.application.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/connections} is rendered in the settings screen, so whatever this class
 * calls "not a secret" is on a page. It called Google's {@code refreshToken} not a secret.
 */
class MaskingTest {

    @Test
    void every_spelling_of_a_token_is_a_secret() {
        assertThat(Masking.isSecret("apiToken")).isTrue();
        assertThat(Masking.isSecret("bot_token")).isTrue();
        assertThat(Masking.isSecret("refreshToken")).isTrue();
        assertThat(Masking.isSecret("accessToken")).isTrue();
        assertThat(Masking.isSecret("client-secret")).isTrue();
        assertThat(Masking.isSecret("password")).isTrue();
    }

    /** An identifier the user has to be able to read back is not a credential. */
    @Test
    void identifiers_stay_readable() {
        assertThat(Masking.isSecret("projectKey")).isFalse();
        assertThat(Masking.isSecret("baseUrl")).isFalse();
        assertThat(Masking.isSecret("email")).isFalse();
        assertThat(Masking.isSecret("defaultChannel")).isFalse();
        assertThat(Masking.isSecret("expiresAt")).isFalse();
        assertThat(Masking.isSecret("scope")).isFalse();
        assertThat(Masking.isSecret(null)).isFalse();
    }

    @Test
    void a_google_connection_leaves_with_both_tokens_masked() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("refreshToken", "1//03JpyqBHcCnptCgYIARAAGAMSNwF-secret-part");
        config.put("accessToken", "ya29.a0ARGnu0b7e--e3nMwGQlhv7wczkzv36v4p63");
        config.put("scope", "https://www.googleapis.com/auth/gmail.readonly");

        Map<String, String> masked = Masking.maskConfig(config);

        assertThat(masked.get("refreshToken")).doesNotContain("secret-part").contains("****");
        assertThat(masked.get("accessToken")).doesNotContain("ARGnu0b7").contains("****");
        assertThat(masked.get("scope")).isEqualTo(config.get("scope"));
    }

    @Test
    void a_short_value_gives_nothing_away() {
        assertThat(Masking.mask("abc")).isEqualTo("****");
        assertThat(Masking.mask("")).isEmpty();
        assertThat(Masking.mask("xoxb-2f9a-secret-4d21")).endsWith("4d21").doesNotContain("secret");
    }
}
