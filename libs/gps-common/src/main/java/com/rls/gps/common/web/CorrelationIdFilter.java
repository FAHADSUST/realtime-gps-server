package com.rls.gps.common.web;

import java.io.IOException;
import java.util.UUID;

import com.rls.gps.common.error.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adopts the correlation id Kong sends (or mints one), publishes it to the MDC for logging and
 * echoes it back so a client can quote it in a bug report.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = firstNonBlank(
                request.getHeader(GpsHeaders.CORRELATION_ID),
                request.getHeader(GpsHeaders.REQUEST_ID));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(GlobalExceptionHandler.TRACE_ID, correlationId);
        response.setHeader(GpsHeaders.CORRELATION_ID, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(GlobalExceptionHandler.TRACE_ID);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
