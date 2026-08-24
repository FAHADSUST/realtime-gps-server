package com.rls.gps.ping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ping service - the platform's write path. Every location a device reports arrives here.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PingServiceApplication.class, args);
    }
}
