package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.orchestrator.RunService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

/**
 * The error envelope is a product surface: the status says whose fault it is, and the
 * message is read by a person. A 500 tells the user Relay is broken, so it has to be
 * reserved for the times it actually is.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }

    /** POST /api/runs with no body answered 500 "Beklenmeyen bir hata oluştu". */
    @Test
    void a_missing_or_broken_body_is_the_callers_mistake() {
        ResponseEntity<Map<String, Object>> response =
                handler.unreadableBody(new HttpMessageNotReadableException(
                        "Required request body is missing: RunController.create(CreateRunRequest)",
                        new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).get("error")).isEqualTo("bad_request");
        assertThat(body(response).get("message")).asString().contains("JSON gövdesi");
        // Spring's own message names internals; it belongs in the log, not in the response.
        assertThat(body(response).get("message")).asString().doesNotContain("RunController");
    }

    /**
     * A refused edit at the approval gate answers per field. One line at the top of the
     * screen ("parametreler geçersiz") sends the reader hunting through four text boxes for
     * the one that is wrong; the field name is the whole value of the message.
     */
    @Test
    void a_refused_parameter_edit_says_which_field_and_why() {
        ResponseEntity<Map<String, Object>> response = handler.invalidParams(
                new RunService.InvalidParams("Düzenlenen parametreler slack.postMessage şemasına uymuyor.",
                        Map.of("text", "En az 1 karakter olmalı.")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).get("error")).isEqualTo("invalid_params");
        // The two familiar keys stay put: every caller already reads them.
        assertThat(body(response).keySet()).containsExactly("error", "message", "fields");
        assertThat(body(response).get("fields")).isEqualTo(Map.of("text", "En az 1 karakter olmalı."));
    }

    @Test
    void the_envelope_is_the_same_two_keys_whatever_went_wrong() {
        assertThat(body(handler.notFound(new RunService.NotFound("run x not found"))).keySet())
                .containsExactly("error", "message");
        assertThat(body(handler.badRequest(new IllegalArgumentException("goal is required"))))
                .containsEntry("message", "goal is required");
        assertThat(handler.unexpected(new RuntimeException("secret token=ATATT-leak")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(handler.unexpected(new RuntimeException("secret token=ATATT-leak"))).get("message"))
                .asString().doesNotContain("ATATT");
    }
}
