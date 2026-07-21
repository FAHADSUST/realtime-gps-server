package com.rls.gps.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, client-visible failures. Rendered as RFC 7807 {@code ProblemDetail}
 * by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable, machine-readable error code (snake_case), e.g. {@code company_not_found}. */
    public String code() {
        return code;
    }
}
