package com.rls.gps.ping.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfiguration.class);

    /**
     * A template that notices when a publish did not work.
     *
     * <p>By default a publish is fire-and-forget: an unroutable message or a broker nack disappears
     * silently, and the first sign of trouble is missing history days later. Mandatory delivery plus
     * confirm and return callbacks turn both into log lines and (from C7.4) metrics.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);

        // Without this an unroutable message is dropped by the broker without telling anyone.
        template.setMandatory(true);

        template.setConfirmCallback((correlation, acknowledged, reason) -> {
            if (!acknowledged) {
                log.error("location_batch_nacked batchId={} reason={}",
                        correlation != null ? correlation.getId() : "unknown", reason);
            }
        });

        template.setReturnsCallback(returned ->
                log.error("location_batch_unroutable exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));

        return template;
    }
}
