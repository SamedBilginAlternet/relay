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

/** Uniform error envelope: {@code {error, message}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = System.getLogger(ApiExceptionHandler.class.getName());

    @ExceptionHandler(RunService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(RunService.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.log(Level.ERROR, "unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}
