package com.rls.gps.id.web;

import java.io.IOException;
import java.util.List;

import com.rls.gps.common.web.ProblemWriter;
import com.rls.gps.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the restricted endpoints off the port Kong can reach.
 *
 * <p>{@code /api/v1/company/**} and {@code /api/v1/internal/**} are served only on the internal
 * port; every other endpoint is served only on the public port. Mismatches get a 404 rather than a
 * 403 - an endpoint you cannot reach should not confirm that it exists.
 */
public class PortAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PortAccessFilter.class);

    private static final List<String> RESTRICTED_PREFIXES = List.of("/api/v1/company", "/api/v1/internal");
    private static final List<String> ALWAYS_ALLOWED_PREFIXES = List.of("/actuator", "/api/v1/ping", "/error");

    private final int internalPort;
    private final ProblemWriter problemWriter;

    public PortAccessFilter(int internalPort, ProblemWriter problemWriter) {
        this.internalPort = internalPort;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.withinApplication(request);

        if (startsWithAny(path, ALWAYS_ALLOWED_PREFIXES)) {
            chain.doFilter(request, response);
            return;
        }

        boolean restricted = startsWithAny(path, RESTRICTED_PREFIXES);
        boolean onInternalPort = request.getLocalPort() == internalPort;

        if (restricted != onInternalPort) {
            log.warn("port_access_denied path={} port={} restricted={}", path, request.getLocalPort(), restricted);
            problemWriter.write(response, HttpStatus.NOT_FOUND, "not_found", "No handler for " + path);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean startsWithAny(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(path::startsWith);
    }
}
