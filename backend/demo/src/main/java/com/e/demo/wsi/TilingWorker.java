package com.e.demo.wsi;

import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.entity.PatchTask;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class TilingWorker {

    private static final int PATCH_SIZE = 512;
    private static final int OVERLAP    = 64;
    private static final int STEP       = PATCH_SIZE - OVERLAP; // 448

    private final MinioClient minioClient;
    private final RabbitTemplate rabbitTemplate;
    private final PatchTaskRepository patchTaskRepository;
    private final JobRepository jobRepository;

    @Value("${minio.bucketName}")
    private String bucket;

    public TilingWorker(@Qualifier("internalClient") MinioClient minioClient,
                        RabbitTemplate rabbitTemplate,
                        PatchTaskRepository patchTaskRepository,
                        JobRepository jobRepository) {
        this.minioClient      = minioClient;
        this.rabbitTemplate   = rabbitTemplate;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository    = jobRepository;
    }

    @RabbitListener(queues = "wsi.uploaded")
    public void handle(WsiUploadedEvent event) {
        log.info("TilingWorker: job={} path={}", event.jobId(), event.s3Path());

        Path tmpFile = null;
        try {
            // Bio-Formats требует путь к файлу на диске — скачиваем во временный файл
            tmpFile = Files.createTempFile("wsi-", ".svs");
            downloadToFile(event.s3Path(), tmpFile);

            List<PatchInferenceEvent> patches = new ArrayList<>();

            // Используем твой готовый BioFormatsWsiReader
            try (BioFormatsWsiReader wsi = new BioFormatsWsiReader(tmpFile.toString())) {

                int imgW = wsi.width();
                int imgH = wsi.height();
                log.info("WSI size: {}x{} job={}", imgW, imgH, event.jobId());

                for (int y = 0; y < imgH; y += STEP) {
                    for (int x = 0; x < imgW; x += STEP) {

                        // Размер последнего патча у края может быть меньше 512
                        int w = Math.min(PATCH_SIZE, imgW - x);
                        int h = Math.min(PATCH_SIZE, imgH - y);

                        // Читаем регион через Bio-Formats
                        BufferedImage patch = wsi.readRegion(x, y, w, h);

                        UUID   patchId   = UUID.randomUUID();
                        String patchPath = "patches/%s/patch_%d_%d.png"
                                .formatted(event.jobId(), x, y);

                        uploadPatch(patchPath, patch);
                        savePatchTask(event.jobId(), patchId, patchPath, x, y, w, h);

                        patches.add(new PatchInferenceEvent(
                                event.jobId(), patchId, patchPath, x, y, w, h));
                    }
                }
            }

            // Обновляем счётчик патчей в job
            jobRepository.updatePatchCount(event.jobId(), patches.size(), Instant.now());

            // Публикуем все патчи в следующую очередь
            patches.forEach(p -> rabbitTemplate.convertAndSend("patches.inference", p));

            log.info("TilingWorker done: job={} patches={}", event.jobId(), patches.size());

        } catch (Exception e) {
            log.error("TilingWorker failed: job={}", event.jobId(), e);
            throw new RuntimeException(e);
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(s3Path)
                        .build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void uploadPatch(String path, BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(path)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("image/png")
                .build());
    }

    private void savePatchTask(UUID jobId, UUID patchId, String path,
                               int x, int y, int w, int h) {
        PatchTask task = new PatchTask();
        task.setId(patchId);
        task.setJobId(jobId);
        task.setMinioPath(path);
        task.setX(x);
        task.setY(y);
        task.setWidth(w);
        task.setHeight(h);
        task.setStatus("PENDING");
        task.setCreatedAt(Instant.now());
        patchTaskRepository.save(task);
    }
}
