package com.e.demo.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * E6/E8: построчное (NDJSON) хранение детекций в MinIO.
 *
 * Формат: detections/{jobId}/patch_{patchId}.json — массив объектов в WSI-координатах.
 * Сводка: detections/{jobId}/index.json — путь к каждому файлу патча.
 *
 * NDJSON-style вместо одной большой JSON-сборки — слайд на 5М детекций
 * в БД положить нельзя, и держать в памяти тоже не вариант.
 */
@Service
@Slf4j
public class DetectionStore {

    private final MinioClient minio;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${minio.bucketName}")
    private String bucket;

    public DetectionStore(@Qualifier("internalClient") MinioClient minio) {
        this.minio = minio;
    }

    /**
     * Сохраняет JSON патча. Координаты переводятся из локальных в глобальные WSI.
     * Возвращает ключ объекта в MinIO.
     */
    public String savePatchDetections(UUID jobId, UUID patchId, int patchX, int patchY,
                                      List<Map<String, Object>> dets) {
        if (dets == null || dets.isEmpty()) return null;
        try {
            List<Map<String, Object>> globalized = new ArrayList<>(dets.size());
            for (Map<String, Object> d : dets) {
                Map<String, Object> g = new LinkedHashMap<>(d);
                g.put("cx", num(d.get("cx")) + patchX);
                g.put("cy", num(d.get("cy")) + patchY);
                g.put("x1", num(d.get("x1")) + patchX);
                g.put("y1", num(d.get("y1")) + patchY);
                g.put("x2", num(d.get("x2")) + patchX);
                g.put("y2", num(d.get("y2")) + patchY);
                globalized.add(g);
            }
            String key = "detections/%s/patch_%s.json".formatted(jobId, patchId);
            byte[] bytes = mapper.writeValueAsBytes(globalized);
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/json")
                    .build());
            return key;
        } catch (Exception e) {
            log.error("DetectionStore write failed: job={} patch={}", jobId, patchId, e);
            return null;
        }
    }

    /** Записывает индексный файл с путями ко всем файлам детекций задания. */
    public String writeIndex(UUID jobId, List<String> patchKeys) {
        try {
            String key = "detections/%s/index.json".formatted(jobId);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("jobId", jobId.toString());
            doc.put("patchCount", patchKeys.size());
            doc.put("patches", patchKeys);
            byte[] bytes = mapper.writeValueAsBytes(doc);
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/json")
                    .build());
            return key;
        } catch (Exception e) {
            log.error("DetectionStore index write failed: job={}", jobId, e);
            return null;
        }
    }

    /** Читает все детекции по индексу — для viewer overlay. */
    public List<Map<String, Object>> readAll(String indexKey) {
        if (indexKey == null) return List.of();
        try {
            Map<String, Object> idx;
            try (InputStream in = minio.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(indexKey).build())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(in.readAllBytes(), Map.class);
                idx = parsed;
            }
            @SuppressWarnings("unchecked")
            List<String> patches = (List<String>) idx.getOrDefault("patches", List.of());
            List<Map<String, Object>> all = new ArrayList<>();
            for (String pk : patches) {
                try (InputStream in = minio.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(pk).build())) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> dets =
                        mapper.readValue(in.readAllBytes(), List.class);
                    all.addAll(dets);
                } catch (ErrorResponseException ignored) { /* пропускаем недостающие */ }
            }
            return all;
        } catch (Exception e) {
            log.error("DetectionStore read failed: {}", indexKey, e);
            return List.of();
        }
    }

    private double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
