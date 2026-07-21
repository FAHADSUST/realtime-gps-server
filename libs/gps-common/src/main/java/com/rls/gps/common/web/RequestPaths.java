package com.rls.gps.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/** Small helpers shared by the servlet filters. */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** Request path relative to the application root, suitable for Ant pattern matching. */
    public static String withinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            String remaining = uri.substring(contextPath.length());
            return remaining.isEmpty() ? "/" : remaining;
        }
        return uri;
    }
}
