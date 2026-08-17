package com.rls.gps.gateway;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the gateway's routing surface.
 *
 * <p>A Lua plugin only exists at runtime, but the declarative config is data - and the property that
 * matters most, "no route exposes a restricted endpoint", is exactly the kind of thing a hurried
 * edit breaks. These tests fail the build instead.
 */
class KongDeclarativeConfigTest {

    /** Served only on each service's internal port; Kong must never have a route for them. */
    private static final List<String> RESTRICTED_PREFIXES = List.of("/api/v1/company", "/api/v1/internal");

    /** Routes that are unauthenticated on purpose. Adding to this list should need a good reason. */
    private static final List<String> PUBLIC_ROUTES = List.of("user-signup", "auth-token");

    private final KongConfig config = KongConfig.load();

    @Test
    void isValidDeclarativeConfig() {
        assertThat(config.formatVersion()).isEqualTo("3.0");
        assertThat(config.services()).isNotEmpty();
    }

    @Test
    void everyServiceHasANameAUrlAndAtLeastOneRoute() {
        for (Map<String, Object> service : config.services()) {
            assertThat(service.get("name")).as("service name").isNotNull();
            assertThat((String) service.get("url")).as("service url").startsWith("http");
            assertThat((List<?>) service.get("routes")).as("routes of %s", service.get("name")).isNotEmpty();
        }
    }

    @Test
    void everyRouteHasANameAndAtLeastOnePath() {
        for (Map<String, Object> route : config.routes()) {
            assertThat(route.get("name")).as("route name").isNotNull();
            assertThat((List<?>) route.get("paths")).as("paths of %s", route.get("name")).isNotEmpty();
        }
    }

    @Test
    void noRouteExposesARestrictedEndpoint() {
        assertThat(config.allPaths())
                .allSatisfy(path -> assertThat(RESTRICTED_PREFIXES)
                        .as("route path '%s' must not expose a restricted endpoint", path)
                        .noneSatisfy(restricted -> assertThat(path).startsWith(restricted)));
    }

    @Test
    void routesDoNotStripTheApiPrefixTheServicesExpect() {
        for (Map<String, Object> route : config.routes()) {
            assertThat(route.get("strip_path"))
                    .as("strip_path of %s - services serve the full /api/v1/... path", route.get("name"))
                    .isEqualTo(false);
        }
    }

    @Test
    void clientSuppliedIdentityHeadersAreStrippedFromEveryRequest() {
        Map<?, ?> transformer = (Map<?, ?>) config.globalPlugin("request-transformer").get("config");
        Map<?, ?> remove = (Map<?, ?>) transformer.get("remove");

        assertThat((List<String>) remove.get("headers"))
                .contains("X-Company-Id", "X-User-Id", "X-App-Key", "X-Gateway-Token");
    }

    @Test
    void everyProxiedRequestCarriesTheGatewayToken() {
        Map<?, ?> transformer = (Map<?, ?>) config.globalPlugin("request-transformer").get("config");
        Map<?, ?> add = (Map<?, ?>) transformer.get("add");

        assertThat((List<String>) add.get("headers"))
                .anySatisfy(header -> assertThat(header).startsWith("X-Gateway-Token:"));
    }

    @Test
    void correlationIdsAreGeneratedAndEchoed() {
        Map<?, ?> correlation = (Map<?, ?>) config.globalPlugin("correlation-id").get("config");

        assertThat(correlation.get("header_name")).isEqualTo("X-Correlation-Id");
        assertThat(correlation.get("echo_downstream")).isEqualTo(true);
    }

    @Test
    void signupAndTokenRoutesStayPublic() {
        // These two cannot require a token: one creates the user, the other issues the token.
        assertThat(KongConfig.pluginNames(config.route("user-signup"))).doesNotContain("rls_auth");
        assertThat(KongConfig.pluginNames(config.route("auth-token"))).doesNotContain("rls_auth");
    }

    /**
     * The invariant that matters as routes are added for ping, history and metadata: a new route is
     * authenticated unless it is deliberately listed as public here.
     */
    @Test
    void everyRouteOutsideThePublicAllowlistRequiresAuthentication() {
        for (Map<String, Object> route : config.routes()) {
            String name = (String) route.get("name");
            if (PUBLIC_ROUTES.contains(name)) {
                continue;
            }
            assertThat(KongConfig.pluginNames(route))
                    .as("route '%s' must apply rls_auth or be added to the public allowlist", name)
                    .contains("rls_auth");
        }
    }

    @Test
    void authenticationCallsTheIdServicesRestrictedPort() {
        Map<?, ?> rlsAuth = KongConfig.plugin(config.route("user-resolve"), "rls_auth");
        String url = (String) ((Map<?, ?>) rlsAuth.get("config")).get("authenticate_url");

        assertThat(url).endsWith("/api/v1/internal/authenticate");
        assertThat(url).as("authenticate is served on the internal port, not the public one")
                .contains(":9081");
    }

    @Test
    void thePluginItselfIsPresent() {
        assertThat(new File("plugins/rls_auth/handler.lua")).isFile();
        assertThat(new File("plugins/rls_auth/schema.lua")).isFile();
    }
}
