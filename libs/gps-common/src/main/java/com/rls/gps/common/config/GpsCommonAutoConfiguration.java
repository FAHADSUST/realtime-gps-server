package com.rls.gps.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.common.error.GlobalExceptionHandler;
import com.rls.gps.common.web.CorrelationIdFilter;
import com.rls.gps.common.web.ProblemWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Wires the shared plumbing into every service that puts {@code gps-common} on its classpath.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GpsCommonAutoConfiguration {

    /** Ordered first so every later filter and the error handler can log with a trace id. */
    public static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

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
}
