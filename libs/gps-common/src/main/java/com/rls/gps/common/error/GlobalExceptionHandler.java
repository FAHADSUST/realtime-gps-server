package com.rls.gps.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} body with a stable
 * {@code code} and the request's {@code traceId}, so clients and logs can be correlated.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** MDC key populated by {@link com.rls.gps.common.web.CorrelationIdFilter}. */
    public static final String TRACE_ID = "correlationId";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail body = problem(ex.status(), ex.code(), ex.getMessage());
        if (ex.status().is5xxServerError()) {
            log.error("api_error code={} status={}", ex.code(), ex.status().value(), ex);
        } else {
            log.debug("api_error code={} status={} message={}", ex.code(), ex.status().value(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .sorted()
                .toList();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("unhandled_exception", ex);
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "The service failed to process the request");
        return ResponseEntity.internalServerError().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                        : error.getObjectName() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    /** Enriches the problem bodies produced by the superclass (405, 415, malformed JSON, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            decorate(problem, defaultCode(statusCode));
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://docs.rls.gps/errors/" + code));
        decorate(problem, code);
        return problem;
    }

    private static void decorate(ProblemDetail problem, String code) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code);
        }
        problem.setProperty("timestamp", Instant.now().toString());
        String traceId = MDC.get(TRACE_ID);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
    }

    private static String defaultCode(HttpStatusCode status) {
        return status.is4xxClientError() ? "bad_request" : "internal_error";
    }

    private static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
