package com.rls.gps.common.security;

/**
 * The caller identity as established by the gateway, carried on every downstream request.
 *
 * @param companyId tenant id, always present for authenticated calls
 * @param userId    authenticated user id, always present for user-scoped calls
 * @param appKey    the company app key the token was issued for, may be {@code null}
 */
public record Identity(String companyId, String userId, String appKey) {

    /** Request attribute under which {@link com.rls.gps.common.security.IdentityFilter} stores the identity. */
    public static final String REQUEST_ATTRIBUTE = "gps.identity";

    public boolean isComplete() {
        return hasText(companyId) && hasText(userId);
    }

    public boolean isAnonymous() {
        return !hasText(companyId) && !hasText(userId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
