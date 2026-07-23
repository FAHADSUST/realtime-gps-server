package com.rls.gps.common.config;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.common.error.GlobalExceptionHandler;
import com.rls.gps.common.security.IdentityArgumentResolver;
import com.rls.gps.common.security.IdentityFilter;
import com.rls.gps.common.web.CorrelationIdFilter;
import com.rls.gps.common.web.PingController;
import com.rls.gps.common.web.ProblemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the shared plumbing into every service that puts {@code gps-common} on its classpath.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GpsCommonAutoConfiguration {

    /** Ordered first so every later filter and the error handler can log with a trace id. */
    public static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int IDENTITY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler gpsGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemWriter gpsProblemWriter(ObjectMapper objectMapper) {
        return new ProblemWriter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> gpsCorrelationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(CORRELATION_FILTER_ORDER);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public PingController gpsPingController(ObjectProvider<BuildProperties> buildProperties,
                                            Environment environment) {
        String serviceName = environment.getProperty("spring.application.name", "gps-service");
        return new PingController(serviceName, buildProperties);
    }

    @Bean
    public FilterRegistrationBean<IdentityFilter> gpsIdentityFilter() {
        FilterRegistrationBean<IdentityFilter> registration = new FilterRegistrationBean<>(new IdentityFilter());
        registration.setOrder(IDENTITY_FILTER_ORDER);
        return registration;
    }

    @Bean
    public WebMvcConfigurer gpsIdentityWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new IdentityArgumentResolver());
            }
        };
    }
}
