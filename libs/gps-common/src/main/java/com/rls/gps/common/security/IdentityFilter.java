package com.rls.gps.common.security;

import java.io.IOException;

import com.rls.gps.common.web.GpsHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the identity headers injected by the gateway into a {@link Identity} request attribute
 * and the MDC. It does not authorise anything - {@link CurrentIdentity} decides whether a
 * particular endpoint requires a complete identity.
 */
public class IdentityFilter extends OncePerRequestFilter {

    private static final String MDC_COMPANY = "companyId";
    private static final String MDC_USER = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Identity identity = new Identity(
                trimToNull(request.getHeader(GpsHeaders.COMPANY_ID)),
                trimToNull(request.getHeader(GpsHeaders.USER_ID)),
                trimToNull(request.getHeader(GpsHeaders.APP_KEY)));

        request.setAttribute(Identity.REQUEST_ATTRIBUTE, identity);
        if (identity.companyId() != null) {
            MDC.put(MDC_COMPANY, identity.companyId());
        }
        if (identity.userId() != null) {
            MDC.put(MDC_USER, identity.userId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_COMPANY);
            MDC.remove(MDC_USER);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
