package com.rls.gps.id.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opens a second HTTP port for the restricted endpoints.
 *
 * <p>The spec calls {@code /api/v1/company/signup} and {@code /api/v1/internal/authenticate}
 * restricted: they must not be reachable through the gateway. Kong only ever proxies to the public
 * port, and {@link com.rls.gps.id.web.PortAccessFilter} makes the split enforceable rather than
 * merely conventional.
 */
@Configuration(proxyBeanMethods = false)
public class InternalConnectorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalConnectorConfiguration.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalConnectorCustomizer(
            IdProperties properties) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(properties.internalPort());
            connector.setProperty("maxThreads", "20");
            factory.addAdditionalTomcatConnectors(connector);
            log.info("internal_connector_enabled port={}", properties.internalPort());
        };
    }
}
