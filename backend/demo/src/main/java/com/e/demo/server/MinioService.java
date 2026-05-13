package com.e.demo.server;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MinioService {

    // Клиент для presigned URL (внешний адрес, как видит браузер) — оставлен на случай
    // если где-то ещё понадобятся пресайны.
    private final MinioClient signerClient;
    // Клиент для прямой работы из контейнера Spring → контейнер MinIO по docker-сети.
    private final MinioClient internalClient;

    public MinioService(@Qualifier("signerClient") MinioClient signerClient,
                        @Qualifier("internalClient") MinioClient internalClient) {
        this.signerClient = signerClient;
        this.internalClient = internalClient;
    }

    public String generateUploadLink(String bucketName, String objectName) {
        try {
            return signerClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucketName)
                            .object(objectName)
                            .region("us-east-1")
                            .expiry(10, TimeUnit.MINUTES)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Ошибка генерации ссылки", e);
        }
    }

    /**
     * Стриминговая загрузка: читаем InputStream от клиента → пишем в MinIO по внутренней сети.
     * Если contentLength неизвестен (-1), MinIO SDK сам разобьёт на multipart по 50MB.
     */
    public void putStream(String bucketName, String objectName,
                          InputStream in, long contentLength) {
        try {
            long size = contentLength > 0 ? contentLength : -1;
            long partSize = 50L * 1024L * 1024L; // 50 MB
            internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(in, size, partSize)
                            .contentType("application/octet-stream")
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Ошибка стриминговой загрузки в MinIO", e);
        }
    }
}
