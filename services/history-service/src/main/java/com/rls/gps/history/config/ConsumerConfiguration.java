package com.rls.gps.history.config;

import com.rls.gps.messaging.LocationTopology;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConsumerConfiguration {

    /**
     * Where a batch goes once retries are exhausted.
     *
     * <p>Republishing rather than plain rejection is the difference between a dead-letter queue you
     * can act on and one you can only stare at: the recoverer copies the failure's message and stack
     * trace into headers, so triage starts with "this batch failed because X" instead of a
     * base64 blob and a shrug.
     */
    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate,
                LocationTopology.DEAD_LETTER_EXCHANGE, LocationTopology.DEAD_LETTER_ROUTING_KEY);
    }
}
