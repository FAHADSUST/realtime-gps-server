package com.rls.gps.common.testapp;

import com.rls.gps.common.error.ApiExceptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that fail in the ways the error handler must cover. */
@RestController
public class ErrorTestController {

    @PostMapping("/api/v1/echo")
    public EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    @GetMapping("/api/v1/boom")
    public void boom() {
        throw ApiExceptions.notFound("thing_not_found", "No such thing");
    }

    @GetMapping("/api/v1/explode")
    public void explode() {
        throw new IllegalStateException("unexpected failure");
    }

    public record EchoRequest(@NotBlank String name, @Min(1) int count) {
    }
}
