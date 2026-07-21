package com.rls.gps.common.error;

import org.springframework.http.HttpStatus;

/** Factory for the handful of {@link ApiException} shapes the services need. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException tooManyRequests(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }

    public static ApiException unavailable(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, cause);
    }
}
