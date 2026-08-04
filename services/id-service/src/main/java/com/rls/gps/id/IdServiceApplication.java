package com.rls.gps.id;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Id service - companies, users, tokens, and the gateway's authentication hook.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IdServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdServiceApplication.class, args);
    }
}
