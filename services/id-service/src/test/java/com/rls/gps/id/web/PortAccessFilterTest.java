package com.rls.gps.id.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.common.web.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PortAccessFilterTest {

    private static final int INTERNAL_PORT = 9081;
    private static final int PUBLIC_PORT = 8081;

    private final PortAccessFilter filter =
            new PortAccessFilter(INTERNAL_PORT, new ProblemWriter(new ObjectMapper()));

    @Test
    void restrictedPathsPassOnTheInternalPort() throws Exception {
        MockFilterChain chain = invoke("POST", "/api/v1/company/signup", INTERNAL_PORT);

        assertThat(chain.getRequest()).as("request should reach the controller").isNotNull();
    }

    @Test
    void restrictedPathsAreInvisibleOnTheGatewayFacingPort() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/company/signup");
        request.setLocalPort(PUBLIC_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("request must not reach the controller").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"not_found\"");
    }

    @Test
    void internalOnlyEndpointsAreAlsoHiddenOnThePublicPort() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/authenticate");
        request.setLocalPort(PUBLIC_PORT);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void publicEndpointsAreNotServedOnTheInternalPort() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/user/signup");
        request.setLocalPort(INTERNAL_PORT);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void healthAndProbePathsWorkOnBothPorts() throws Exception {
        assertThat(invoke("GET", "/api/v1/ping", PUBLIC_PORT).getRequest()).isNotNull();
        assertThat(invoke("GET", "/api/v1/ping", INTERNAL_PORT).getRequest()).isNotNull();
        assertThat(invoke("GET", "/actuator/health", PUBLIC_PORT).getRequest()).isNotNull();
        assertThat(invoke("GET", "/actuator/health", INTERNAL_PORT).getRequest()).isNotNull();
    }

    private MockFilterChain invoke(String method, String path, int port) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setLocalPort(port);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
