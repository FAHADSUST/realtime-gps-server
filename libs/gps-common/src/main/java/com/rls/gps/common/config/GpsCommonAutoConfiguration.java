package com.rls.gps.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.common.error.GlobalExceptionHandler;
import com.rls.gps.common.web.ProblemWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared plumbing into every service that puts {@code gps-common} on its classpath.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GpsCommonAutoConfiguration {

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
}
