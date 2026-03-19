package com.e.demo.config; // Или com.e.demo.server, не важно, главное чтобы Spring видел

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    // Клиент №1: Для генерации ссылок (фронту)
    @Bean
    public MinioClient signerClient(@Value("${minio.url}") String url) {
        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .build();
    }

    // Клиент №2: Для работы самого бэкенда (создать бакет и т.д.)
    @Bean
    public MinioClient internalClient(@Value("${minio.internalUrl}") String url) {
        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .build();
    }
}




