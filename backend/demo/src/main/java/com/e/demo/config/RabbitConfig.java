package com.e.demo.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

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
    public Binding wsiDlqBinding(Queue wsiDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(wsiDlq).to(dlxExchange).with("wsi.uploaded.dlq");
    }

    @Bean
    public Binding patchesDlqBinding(Queue patchesDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(patchesDlq).to(dlxExchange).with("patches.inference.dlq");
    }
}