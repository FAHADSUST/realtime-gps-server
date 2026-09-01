package com.rls.gps.ping.config;

import com.rls.gps.ping.metrics.PingMetrics;
import com.rls.gps.ping.publish.LocationAdmission;
import com.rls.gps.ping.publish.LocationBatchSink;
import com.rls.gps.ping.publish.LocationBuffer;
import com.rls.gps.ping.publish.LocationBufferFlusher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BufferConfiguration {

    @Bean
    public LocationBuffer locationBuffer(PingProperties properties) {
        PingProperties.Buffer buffer = properties.buffer();
        return new LocationBuffer(buffer.capacity(), buffer.maxBatchSize(), buffer.flushInterval());
    }

    @Bean
    public LocationAdmission locationAdmission(LocationBuffer buffer, PingProperties properties) {
        return new LocationAdmission(buffer, properties.buffer().overflowPolicy());
    }

    @Bean
    public LocationBufferFlusher locationBufferFlusher(LocationBuffer buffer,
                                                       LocationBatchSink sink,
                                                       PingMetrics metrics,
                                                       PingProperties properties) {
        return new LocationBufferFlusher(buffer, sink, metrics,
                properties.buffer().shutdownTimeout().toMillis());
    }
}
