package com.rls.gps.common.web;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/ping} for every service, as required by the spec. Kept free of auth so Consul,
 * Kong and load balancers can probe it.
 */
@RestController
public class PingController {

    private final String serviceName;
    private final ObjectProvider<BuildProperties> buildProperties;

    public PingController(@Value("${spring.application.name:gps-service}") String serviceName,
                          ObjectProvider<BuildProperties> buildProperties) {
        this.serviceName = serviceName;
        this.buildProperties = buildProperties;
    }

    @GetMapping(path = "/api/v1/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public PingResponse ping() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return new PingResponse(serviceName, "UP", version, Instant.now());
    }
}
