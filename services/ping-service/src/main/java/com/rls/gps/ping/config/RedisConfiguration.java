package com.rls.gps.ping.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration(proxyBeanMethods = false)
public class RedisConfiguration {

    /**
     * Loaded once and executed by SHA thereafter, so the script body is not re-sent per request.
     */
    @Bean
    public RedisScript<Long> lastLocationUpsertScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/last-location-upsert.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
