package com.rls.gps.ping.config;

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
    public LocationBufferFlusher locationBufferFlusher(LocationBuffer buffer,
                                                       LocationBatchSink sink,
                                                       PingProperties properties) {
        return new LocationBufferFlusher(buffer, sink, properties.buffer().shutdownTimeout().toMillis());
    }
}
