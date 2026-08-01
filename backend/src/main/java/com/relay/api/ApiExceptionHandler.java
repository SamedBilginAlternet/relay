package com.relay.api;

import com.relay.application.orchestrator.RunService;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Uniform error envelope: {@code {error, message}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = System.getLogger(ApiExceptionHandler.class.getName());

    @ExceptionHandler(RunService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(RunService.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    /**
     * The caller did nothing wrong; the resource moved. Approving a step on a run somebody
     * else has just cancelled is the case this exists for — 400 would blame the user for a
     * button that was legal when the screen drew it.
     */
    @ExceptionHandler(RunService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(RunService.Conflict e) {
        return body(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    /**
     * A rejected edit at the approval gate. The envelope keeps {@code error}/{@code message}
     * so every existing caller still reads it, and adds {@code fields} — the screen puts
     * each sentence under the box it belongs to instead of showing one line at the top.
     */
    @ExceptionHandler(RunService.InvalidParams.class)
    public ResponseEntity<Map<String, Object>> invalidParams(RunService.InvalidParams e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_params");
        body.put("message", e.getMessage());
        body.put("fields", e.fields());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    /**
     * A malformed path variable is the caller's mistake, not a server fault.
     * {@code /api/runs/not-a-uuid} used to answer 500 and hand back Spring's internal
     * conversion message.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> badParameter(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request",
                "'" + e.getName() + "' değeri geçersiz");
    }

    /**
     * A body that is missing, empty or not JSON is the caller's mistake too.
     *
     * <p>{@code POST /api/runs} with no body at all answered 500 "Beklenmeyen bir hata
     * oluştu" — which reads as "Relay is broken" for a request Relay understood perfectly
     * well and simply cannot act on. Spring's own message names the parser and the offset,
     * so it stays in the log.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        LOG.log(Level.DEBUG, "unreadable request body", e);
        return body(HttpStatus.BAD_REQUEST, "bad_request",
                "İstek gövdesi okunamadı: geçerli bir JSON gövdesi gönderin.");
    }

    /**
     * Anything unhandled is a bug on our side, so the caller gets a stable sentence and
     * the detail goes to the log. Exception messages carry whatever the failing layer put
     * in them — a provider body, a query, occasionally a credential — and none of that
     * belongs in an HTTP response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.log(Level.ERROR, "unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Beklenmeyen bir hata oluştu. Sunucu günlüklerine bakın.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}
