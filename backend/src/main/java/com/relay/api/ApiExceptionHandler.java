package com.relay.api;

import com.relay.application.orchestrator.RunService;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Uniform error envelope: {@code {error, message}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = System.getLogger(ApiExceptionHandler.class.getName());

    /**
     * {@code RunService} phrases these for a log — "run 2f1c… not found", with an id the
     * reader cannot do anything with. On screen it is one sentence in the language the rest
     * of the product is written in; the original goes to the log, where it was useful (#81).
     */
    @ExceptionHandler(RunService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(RunService.NotFound e) {
        LOG.log(Level.DEBUG, "not found: {0}", e.getMessage());
        return body(HttpStatus.NOT_FOUND, "not_found",
                "Bu kayıt bulunamadı — bağlantı hatalı olabilir.");
    }

    /**
     * The caller did nothing wrong; the resource moved. Approving a step on a run somebody
     * else has just cancelled is the case this exists for — 400 would blame the user for a
     * button that was legal when the screen drew it.
     *
     * <p>Which is also what the sentence says: the screen is out of date, and reloading it
     * is the thing to do. The internal phrasing ("step 8c1f… already finished as done")
     * stays in the log.
     */
    @ExceptionHandler(RunService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(RunService.Conflict e) {
        LOG.log(Level.DEBUG, "conflict: {0}", e.getMessage());
        return body(HttpStatus.CONFLICT, "conflict",
                "Bu işlem artık yapılamıyor — akış bu arada değişmiş. Ekranı yenile.");
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
     *
     * <p>It then answered "'id' değeri geçersiz", which is the same problem one layer up:
     * the reader is on the Geçmiş screen after following a broken link, and {@code id} is
     * the name of a Java parameter they have never seen. Every UUID in this API is a run or
     * a step, so that case gets a sentence about the thing they were looking for; anything
     * else names the query parameter they themselves typed (#81).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> badParameter(MethodArgumentTypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        String message = type != null && UUID.class.isAssignableFrom(type)
                ? "Bu akış bulunamadı — bağlantı hatalı olabilir."
                : "Geçersiz istek parametresi: " + e.getName() + ".";
        return body(HttpStatus.BAD_REQUEST, "bad_request", message);
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
     * A method this endpoint does not serve.
     *
     * <p>Without this it fell through to the catch-all below and answered 500 "Beklenmeyen
     * bir hata oluştu" — Relay reporting itself broken for a request it understood well
     * enough to know it was wrong. {@code DELETE /api/connections/jira} and
     * {@code PATCH /api/runs} both did it.
     *
     * <p>The {@code Allow} header comes back with the answer, because a 405 that does not
     * say what is allowed leaves the caller guessing.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(new HttpMethod[0]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "method_not_allowed");
        body.put("message", "Bu uç bu metodu desteklemiyor.");
        return response.body(body);
    }

    /**
     * Anything unhandled is a bug on our side, so the caller gets a stable sentence and
     * the detail goes to the log. Exception messages carry whatever the failing layer put
     * in them — a provider body, a query, occasionally a credential — and none of that
     * belongs in an HTTP response.
     *
     * <p>It used to end with "Sunucu günlüklerine bakın". The person reading it is a user
     * of a hosted product; they have no log to look at, so the sentence only told them the
     * fault was somewhere they could not go.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.log(Level.ERROR, "unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Beklenmeyen bir hata oluştu. Sorun sürerse tekrar deneyin.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}
