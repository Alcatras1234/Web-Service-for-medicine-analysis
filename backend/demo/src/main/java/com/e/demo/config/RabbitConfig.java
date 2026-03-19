package com.e.demo.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

  public static final String Q_WSI_UPLOADED = "wsi.uploaded";
  public static final String Q_PATCHES_INFERENCE = "patches.inference";

  // DLX + retry (минимально)
  public static final String X_DLX = "dlx";
  public static final String Q_WSI_UPLOADED_RETRY = "wsi.uploaded.retry";

  @Bean
  public DirectExchange dlx() {
    return new DirectExchange(X_DLX);
  }

  @Bean
  public Queue wsiUploadedQueue() {
    return QueueBuilder.durable(Q_WSI_UPLOADED)
        .deadLetterExchange(X_DLX)
        .deadLetterRoutingKey(Q_WSI_UPLOADED_RETRY)
        .build();
  }

  @Bean
  public Queue wsiUploadedRetryQueue() {
    return QueueBuilder.durable(Q_WSI_UPLOADED_RETRY)
        .ttl(10_000) // 10 секунд задержки перед повтором
        .deadLetterExchange("") // default exchange
        .deadLetterRoutingKey(Q_WSI_UPLOADED) // вернём обратно в рабочую
        .build();
  }

  @Bean
  public Binding wsiUploadedRetryBinding(DirectExchange dlx) {
    return BindingBuilder.bind(wsiUploadedRetryQueue()).to(dlx).with(Q_WSI_UPLOADED_RETRY);
  }

  @Bean
  public Queue patchesInferenceQueue() {
    return QueueBuilder.durable(Q_PATCHES_INFERENCE).build();
  }

  // Listener factory для тяжёлой очереди tiling: low prefetch, controlled concurrency
  @Bean(name = "tilingListenerFactory")
  public SimpleRabbitListenerContainerFactory tilingListenerFactory(ConnectionFactory cf) {
    var factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(cf);
    factory.setPrefetchCount(1); // один WSI за раз на consumer
    factory.setConcurrentConsumers(2); // параллельно 2 WSI (подстрой под железо)
    factory.setDefaultRequeueRejected(false); // чтобы летело в DLX/retry
    return factory;
  }
}
