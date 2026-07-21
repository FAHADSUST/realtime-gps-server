package com.rls.gps.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathsTest {

    @Test
    void returnsTheUriWhenThereIsNoContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");

        assertThat(RequestPaths.withinApplication(request)).isEqualTo("/api/v1/ping");
    }

    @Test
    void stripsTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/id/api/v1/ping");
        request.setContextPath("/id");

        assertThat(RequestPaths.withinApplication(request)).isEqualTo("/api/v1/ping");
    }

    @Test
    void returnsRootWhenThePathIsExactlyTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/id");
        request.setContextPath("/id");

        assertThat(RequestPaths.withinApplication(request)).isEqualTo("/");
    }
}
