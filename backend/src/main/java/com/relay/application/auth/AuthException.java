package com.relay.application.auth;

/**
 * A refusal the user can act on: which field is wrong and what to do about it.
 * The message is user-facing Turkish; {@code field} lets the form highlight the input.
 */
public class AuthException extends RuntimeException {

    private final String code;
    private final String field;
    private final int status;

    public AuthException(String code, String field, String message, int status) {
        super(message);
        this.code = code;
        this.field = field;
        this.status = status;
    }

    public static AuthException invalid(String field, String message) {
        return new AuthException("invalid_input", field, message, 400);
    }

    public static AuthException conflict(String field, String message) {
        return new AuthException("already_exists", field, message, 409);
    }

    public static AuthException unauthorized(String message) {
        return new AuthException("invalid_credentials", null, message, 401);
    }

    public String code() {
        return code;
    }

    /** Null when the problem is not tied to a single input. */
    public String field() {
        return field;
    }

    public int status() {
        return status;
    }
}
