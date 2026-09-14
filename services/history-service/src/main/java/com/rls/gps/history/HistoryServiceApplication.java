package com.rls.gps.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * History service - the queue's only consumer, and the platform's durable record of where everyone
 * has been.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HistoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryServiceApplication.class, args);
    }
}
