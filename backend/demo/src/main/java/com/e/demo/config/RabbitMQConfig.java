package com.e.demo.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // --- Очереди ---

    @Bean
    public Queue wsiUploadedQueue() {
        return QueueBuilder.durable("wsi.uploaded")
                .withArgument("x-dead-letter-exchange", "dlx")
                .withArgument("x-dead-letter-routing-key", "wsi.uploaded.dlq")
                .build();
    }

    @Bean
    public Queue patchesInferenceQueue() {
        return QueueBuilder.durable("patches.inference")
                .withArgument("x-dead-letter-exchange", "dlx")
                .withArgument("x-dead-letter-routing-key", "patches.inference.dlq")
                .build();
    }

    // --- Dead Letter Queue (куда падают сообщения при ошибке) ---

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange("dlx");
    }

    @Bean
    public Queue wsiDlq() {
        return QueueBuilder.durable("wsi.uploaded.dlq").build();
    }

    @Bean
    public Queue patchesDlq() {
        return QueueBuilder.durable("patches.inference.dlq").build();
    }

    @Bean
    public Binding wsiDlqBinding() {
        return BindingBuilder.bind(wsiDlq())
                .to(dlxExchange())
                .with("wsi.uploaded.dlq");
    }

    @Bean
    public Binding patchesDlqBinding() {
        return BindingBuilder.bind(patchesDlq())
                .to(dlxExchange())
                .with("patches.inference.dlq");
    }

    // --- JSON сериализация вместо Java serialization ---

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
