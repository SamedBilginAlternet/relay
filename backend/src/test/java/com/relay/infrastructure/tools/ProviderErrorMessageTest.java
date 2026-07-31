package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A provider error is quoted into the run timeline, so it is a piece of product copy.
 *
 * <p>Two things have to hold: it must name what is actually wrong (Atlassian puts the field
 * in the body — throwing that away and printing the JSON helps nobody), and it must never
 * carry a credential, which a 401 body is entirely capable of echoing back.
 */
class ProviderErrorMessageTest {

    @Test
    void a_field_rejection_is_told_field_by_field() {
        String message = JiraTool.explain(400, """
                {"errorMessages":[],"errors":{"issuetype":"Geçerli bir konu türü belirtin"}}""");

        assertThat(message).contains("konu türü").contains("Geçerli bir konu türü belirtin");
        assertThat(message).doesNotContain("{").doesNotContain("errorMessages");
    }

    @Test
    void a_general_rejection_keeps_atlassians_own_sentence() {
        String message = JiraTool.explain(400, """
                {"errorMessages":["Şu anda bu işlemi gerçekleştiremezsiniz."],"errors":{}}""");

        assertThat(message).contains("Şu anda bu işlemi gerçekleştiremezsiniz.");
    }

    @Test
    void a_missing_project_reads_as_not_found() {
        assertThat(JiraTool.explain(404, "{\"errorMessages\":[\"Issue does not exist\"],\"errors\":{}}"))
                .contains("bulamadı").contains("Issue does not exist");
    }

    /** The one body that must never be repeated: it can contain the token it just refused. */
    @Test
    void an_auth_failure_never_repeats_the_body() {
        String body = "{\"message\":\"Basic ATATT3xFfGF0dGVtcHRlZHRva2VuMTIzNDU2 rejected\"}";
        String message = JiraTool.explain(401, body);

        assertThat(message).doesNotContain("ATATT").doesNotContain("Basic");
        assertThat(message).contains("kimlik doğrulaması").contains("API token");
        assertThat(JiraTool.explain(403, body)).doesNotContain("ATATT");
    }

    @Test
    void an_unparseable_body_still_produces_a_sentence() {
        assertThat(JiraTool.explain(500, "<html>Gateway error</html>"))
                .isEqualTo("Jira isteği reddetti (HTTP 500).");
        assertThat(JiraTool.explain(429, null)).contains("istek sınırına");
    }

    // ---- redaction --------------------------------------------------------

    @Test
    void credential_shaped_strings_are_blanked_out_of_any_body() {
        assertThat(HttpJson.redact("token=ATATT3xFfGF0dGVtcHRlZHRva2Vu bad"))
                .isEqualTo("token=**** bad");
        assertThat(HttpJson.redact("xoxb-1234567890-abcdefghij")).isEqualTo("****");
        assertThat(HttpJson.redact("ya29.a0ARGnu0bAAAAAAAAAAAA")).isEqualTo("****");
        assertThat(HttpJson.redact("1//03JpyqBHcCnptCgYIARAAGAM")).isEqualTo("****");
        assertThat(HttpJson.redact("ghp_abcdefghijklmnop")).isEqualTo("****");
        assertThat(HttpJson.redact("Authorization: Bearer abcdefghijklmnop"))
                .isEqualTo("Authorization: ****");
    }

    /** Redaction that also eats issue keys and ids would make every error useless. */
    @Test
    void ordinary_error_text_survives_untouched() {
        String text = "KAN-12 not found for user a@b.c (request id 4f3c-9a)";
        assertThat(HttpJson.redact(text)).isEqualTo(text);
    }

    @Test
    void the_default_envelope_hides_an_auth_body_and_redacts_every_other_one() {
        assertThat(HttpJson.failure(401, "x.atlassian.net", "{\"token\":\"ATATT3xFfGF0dGVtcHRlZA\"}")
                .getMessage()).doesNotContain("ATATT").contains("kimlik doğrulama reddedildi");
        assertThat(HttpJson.failure(400, "x.atlassian.net", "leaked xoxb-1234567890-abcdefghij")
                .getMessage()).contains("leaked ****");
    }

    @Test
    void the_status_and_the_body_travel_with_the_exception() {
        HttpJson.ToolCallException failure = HttpJson.failure(400, "x.atlassian.net", "{\"a\":1}");
        assertThat(failure.status()).isEqualTo(400);
        assertThat(failure.body()).isEqualTo("{\"a\":1}");
        assertThat(new HttpJson.ToolCallException("plain").status()).isZero();
    }
}
