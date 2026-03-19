package com.e.demo.server;

import io.minio.MinioClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MinioService {



    // Этот клиент настроен на ВНЕШНИЙ адрес (как его видит браузер)
    // Например: endpoint = "http://localhost:9000" или "https://s3.yourdomain.com"
    // accessKey и secretKey те же самые.
    private final MinioClient signerClient; 

    public MinioService(@Qualifier("signerClient") MinioClient minioClient) {
        this.signerClient = minioClient;
    }


    public String generateUploadLink(String bucketName, String objectName) {
        try {
            return signerClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT) // Важно: метод PUT для загрузки
                            .bucket(bucketName)
                            .object(objectName)
                            .region("us-east-1")
                            .expiry(10, TimeUnit.MINUTES) // Срок жизни ссылки
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Ошибка генерации ссылки", e);
        }
    }
}
