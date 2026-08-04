package com.rls.gps.id.config;

import com.rls.gps.common.config.GpsCommonAutoConfiguration;
import com.rls.gps.common.web.ProblemWriter;
import com.rls.gps.id.web.PortAccessFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
