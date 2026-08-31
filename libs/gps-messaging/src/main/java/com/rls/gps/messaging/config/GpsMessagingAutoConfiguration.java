package com.rls.gps.messaging.config;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.messaging.LocationTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the location pipeline for any service that has Spring AMQP on its classpath.
 *
 * <p>Both the publisher and the consumer get these definitions from here, so the two can never
 * disagree about a queue argument.
 */
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
public class GpsMessagingAutoConfiguration {

    /**
     * A direct exchange: there is exactly one consumer and one routing key, and a topic exchange
     * would only add pattern matching nobody uses.
     */
    @Bean
    public Declarables locationTopology() {
        DirectExchange exchange = new DirectExchange(LocationTopology.EXCHANGE, true, false);
        DirectExchange deadLetterExchange =
                new DirectExchange(LocationTopology.DEAD_LETTER_EXCHANGE, true, false);

        // Durable: a broker restart must not lose batches that have been accepted but not consumed.
        Queue queue = QueueBuilder.durable(LocationTopology.QUEUE)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", LocationTopology.DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", LocationTopology.DEAD_LETTER_ROUTING_KEY))
                .build();

        Queue deadLetterQueue = QueueBuilder.durable(LocationTopology.DEAD_LETTER_QUEUE).build();

        Binding binding = BindingBuilder.bind(queue).to(exchange).with(LocationTopology.ROUTING_KEY);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with(LocationTopology.DEAD_LETTER_ROUTING_KEY);

        return new Declarables(exchange, deadLetterExchange, queue, deadLetterQueue,
                binding, deadLetterBinding);
    }

    /**
     * JSON on the wire, using the application's own ObjectMapper - so a timestamp is serialised the
     * same way in a message as it is in an HTTP response, and both sides share one configuration.
     */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
