package com.rls.gps.common.web;

import java.time.Instant;

/**
 * Payload of {@code GET /api/v1/ping} - the liveness endpoint every service in the original spec
 * exposes.
 */
public record PingResponse(String service, String status, String version, Instant timestamp) {
}
