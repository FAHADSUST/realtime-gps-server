package com.rls.gps.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.common.web.ProblemWriter;
import com.rls.gps.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that did not come through Kong.
 *
 * <p>Identity headers are only meaningful because the gateway verified them. If a service port were
 * ever reachable directly, anyone could forge {@code X-User-Id}. This filter requires a shared secret
 * that only Kong knows (seeded in Consul KV), so a direct call cannot impersonate a user.
 *
 * <p>Disabled automatically when no token is configured - convenient for local runs and tests.
 */
public class GatewayTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayTokenFilter.class);

    private final String expectedToken;
    private final List<String> skipPaths;
    private final ProblemWriter problemWriter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public GatewayTokenFilter(String expectedToken, List<String> skipPaths, ProblemWriter problemWriter) {
        this.expectedToken = expectedToken;
        this.skipPaths = skipPaths == null ? List.of() : List.copyOf(skipPaths);
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!StringUtils.hasText(expectedToken)) {
            return true;
        }
        String path = RequestPaths.withinApplication(request);
        return skipPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(GpsHeaders.GATEWAY_TOKEN);
        if (!matches(presented)) {
            log.warn("gateway_token_rejected path={} remote={}",
                    RequestPaths.withinApplication(request), request.getRemoteAddr());
            problemWriter.write(response, HttpStatus.UNAUTHORIZED, "gateway_token_invalid",
                    "Requests must be made through the API gateway");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String presented) {
        if (!StringUtils.hasText(presented)) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }

}
