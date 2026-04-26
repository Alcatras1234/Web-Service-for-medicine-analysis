package com.e.demo.wsi;

import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.entity.PatchTask;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
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
import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j
public class TilingWorker {

    private static final int PATCH_SIZE    = 512;
    private static final int OVERLAP       = 64;
    private static final int STEP          = PATCH_SIZE - OVERLAP; // 448

    // Сколько патчей обрабатываем одновременно в памяти
    private static final int BATCH_SIZE    = 50;
    // Параллельные загрузки в MinIO внутри одного батча
    private static final int UPLOAD_THREADS = 8;

    private final ExecutorService uploadExecutor =
            Executors.newFixedThreadPool(UPLOAD_THREADS);

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
        this.minioClient = minioClient;
        this.rabbitTemplate = rabbitTemplate;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository = jobRepository;
    }

    record TileCoord(int x, int y, int w, int h) {}

    @RabbitListener(queues = "wsi.uploaded")
    public void handle(WsiUploadedEvent event) {
        log.info("TilingWorker: job={} path={}", event.jobId(), event.s3Path());
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("wsi-", ".svs");
            downloadToFile(event.s3Path(), tmpFile);

            int totalPatches = 0;

            try (BioFormatsWsiReader wsi = new BioFormatsWsiReader(tmpFile.toString())) {
                int imgW = wsi.width();
                int imgH = wsi.height();
                log.info("WSI size: {}x{} job={}", imgW, imgH, event.jobId());

                // Собираем координаты (только int-ы — памяти почти не занимает)
                List<TileCoord> coords = new ArrayList<>();
                for (int y = 0; y < imgH; y += STEP) {
                    for (int x = 0; x < imgW; x += STEP) {
                        coords.add(new TileCoord(x, y,
                                Math.min(PATCH_SIZE, imgW - x),
                                Math.min(PATCH_SIZE, imgH - y)));
                    }
                }
                log.info("Total patches: {} job={}", coords.size(), event.jobId());

                // Обрабатываем батчами — в памяти только BATCH_SIZE патчей одновременно
                for (int batchStart = 0; batchStart < coords.size(); batchStart += BATCH_SIZE) {
                    int batchEnd = Math.min(batchStart + BATCH_SIZE, coords.size());
                    List<TileCoord> batch = coords.subList(batchStart, batchEnd);

                    List<PatchTask> dbBatch = new ArrayList<>(batch.size());
                    List<PatchInferenceEvent> mqBatch = new ArrayList<>(batch.size());
                    List<Future<?>> uploads = new ArrayList<>(batch.size());

                    // Читаем патчи батча (BioFormats не thread-safe — читаем в основном потоке)
                    // Сериализуем в bytes и отдаём загрузку в пул
                    List<String> paths = new ArrayList<>(batch.size());
                    List<UUID> ids = new ArrayList<>(batch.size());

                    for (TileCoord c : batch) {
                        BufferedImage img = wsi.readRegion(c.x(), c.y(), c.w(), c.h());
                        byte[] bytes = toBytes(img);
                        img.flush(); // явно освобождаем BufferedImage

                        UUID patchId = UUID.randomUUID();
                        String patchPath = "patches/%s/patch_%d_%d.png"
                                .formatted(event.jobId(), c.x(), c.y());

                        paths.add(patchPath);
                        ids.add(patchId);

                        // Загрузка в MinIO параллельно
                        uploads.add(uploadExecutor.submit(() -> {
                            try {
                                uploadBytes(patchPath, bytes);
                            } catch (Exception e) {
                                throw new RuntimeException("MinIO upload failed: " + patchPath, e);
                            }
                        }));
                    }

                    // Ждём завершения загрузок этого батча
                    for (Future<?> f : uploads) {
                        f.get();
                    }

                    // Готовим DB и MQ записи (bytes уже не нужны — GC соберёт)
                    for (int i = 0; i < batch.size(); i++) {
                        TileCoord c = batch.get(i);
                        String path = paths.get(i);
                        UUID patchId = ids.get(i);

                        dbBatch.add(buildPatchTask(event.jobId(), patchId, path,
                                c.x(), c.y(), c.w(), c.h()));
                        mqBatch.add(new PatchInferenceEvent(
                                event.jobId(), patchId, path,
                                c.x(), c.y(), c.w(), c.h()));
                    }

                    // Батчевый INSERT в БД
                    patchTaskRepository.saveAll(dbBatch);

                    // Публикуем в RabbitMQ
                    mqBatch.forEach(p -> rabbitTemplate.convertAndSend("patches.inference", p));

                    totalPatches += batch.size();

                    if (totalPatches % 500 == 0 || batchEnd == coords.size()) {
                        log.info("TilingWorker progress: {}/{} patches, job={}",
                                totalPatches, coords.size(), event.jobId());
                    }
                }
            }

            // Обновляем счётчик в job
            jobRepository.updatePatchCount(event.jobId(), totalPatches, Instant.now());

            log.info("TilingWorker done: job={} patches={}", event.jobId(), totalPatches);

        } catch (Exception e) {
            log.error("TilingWorker failed: job={}", event.jobId(), e);
            throw new RuntimeException(e);
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    private byte[] toBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512 * 512 * 3);
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private void uploadBytes(String path, byte[] bytes) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(path)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("image/png")
                .build());
    }

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private PatchTask buildPatchTask(UUID jobId, UUID patchId, String path,
                                     int x, int y, int w, int h) {
        PatchTask task = new PatchTask();
        task.setId(patchId);
        task.setJobId(jobId);
        task.setMinioPath(path);
        task.setX(x); task.setY(y);
        task.setWidth(w); task.setHeight(h);
        task.setStatus("PENDING");
        task.setCreatedAt(Instant.now());
        return task;
    }
}