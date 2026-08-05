package com.rls.gps.id.config;

import java.time.Clock;

import com.rls.gps.common.config.GpsCommonAutoConfiguration;
import com.rls.gps.common.web.ProblemWriter;
import com.rls.gps.id.web.PortAccessFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class IdWebConfiguration {

    /** Between correlation id and the gateway check, so denials are still logged with a trace id. */
    private static final int PORT_ACCESS_FILTER_ORDER = GpsCommonAutoConfiguration.CORRELATION_FILTER_ORDER + 5;

    @Bean
    public FilterRegistrationBean<PortAccessFilter> portAccessFilter(IdProperties properties,
                                                                     ProblemWriter problemWriter) {
        FilterRegistrationBean<PortAccessFilter> registration =
                new FilterRegistrationBean<>(new PortAccessFilter(properties.internalPort(), problemWriter));
        registration.setOrder(PORT_ACCESS_FILTER_ORDER);
        return registration;
    }

    /** Cost 12: app secrets are verified rarely, so the extra work is cheap where it is spent. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Injected rather than called statically so tests can pin time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
