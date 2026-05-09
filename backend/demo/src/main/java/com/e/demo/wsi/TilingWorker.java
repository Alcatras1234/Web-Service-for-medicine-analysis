package com.e.demo.wsi;

import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.entity.PatchTask;
import com.e.demo.entity.Job;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import com.e.demo.repository.SlideRepository;
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

    private static final int PATCH_SIZE    = 448;
    private static final int OVERLAP       = 24;
    private static final int STEP          = PATCH_SIZE - OVERLAP; // 424

    // Сколько патчей обрабатываем одновременно в памяти
    private static final int BATCH_SIZE    = 50;
    // Параллельные загрузки в MinIO внутри одного батча
    private static final int UPLOAD_THREADS = 8;
    // E3: порог белизны (среднее по каналам). Чем выше — тем меньше патчей отсеется.
    private static final double WHITE_THRESHOLD = 240.0;

    private final ExecutorService uploadExecutor =
            Executors.newFixedThreadPool(UPLOAD_THREADS);

    private final MinioClient minioClient;
    private final RabbitTemplate rabbitTemplate;
    private final PatchTaskRepository patchTaskRepository;
    private final JobRepository jobRepository;
    private final SlideRepository slideRepository;

    @Value("${minio.bucketName}")
    private String bucket;

    public TilingWorker(@Qualifier("internalClient") MinioClient minioClient,
                        RabbitTemplate rabbitTemplate,
                        PatchTaskRepository patchTaskRepository,
                        JobRepository jobRepository,
                        SlideRepository slideRepository) {
        this.minioClient = minioClient;
        this.rabbitTemplate = rabbitTemplate;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository = jobRepository;
        this.slideRepository = slideRepository;
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

                // E1: извлекаем MPP из OME-метаданных и сохраняем в slide
                Double mppX = wsi.mppX();
                Double mppY = wsi.mppY();
                String source;
                if (mppX != null && mppY != null && mppX > 0 && mppY > 0) {
                    source = "METADATA";
                } else {
                    mppX = BioFormatsWsiReader.DEFAULT_MPP;
                    mppY = BioFormatsWsiReader.DEFAULT_MPP;
                    source = "DEFAULT";
                    log.warn("MPP not found in metadata for job={}, using DEFAULT={}", event.jobId(), mppX);
                }

                Job job = jobRepository.findById(event.jobId()).orElseThrow();
                slideRepository.updateCalibration(job.getSlideId(), mppX, mppY, source, imgW, imgH);
                log.info("Calibration: job={} mppX={} mppY={} source={}",
                        event.jobId(), mppX, mppY, source);

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

                int skippedWhite = 0;

                // Обрабатываем батчами — в памяти только BATCH_SIZE патчей одновременно
                for (int batchStart = 0; batchStart < coords.size(); batchStart += BATCH_SIZE) {
                    int batchEnd = Math.min(batchStart + BATCH_SIZE, coords.size());
                    List<TileCoord> batch = coords.subList(batchStart, batchEnd);

                    List<PatchTask> dbBatch = new ArrayList<>(batch.size());
                    List<PatchInferenceEvent> mqBatch = new ArrayList<>(batch.size());
                    List<Future<?>> uploads = new ArrayList<>(batch.size());

                    for (TileCoord c : batch) {
                        BufferedImage img = wsi.readRegion(c.x(), c.y(), c.w(), c.h());

                        // E3: белые патчи отсекаем здесь — не грузим в MinIO, не шлём в очередь
                        if (isMostlyWhite(img)) {
                            img.flush();
                            UUID patchId = UUID.randomUUID();
                            PatchTask task = buildPatchTask(event.jobId(), patchId, null,
                                    c.x(), c.y(), c.w(), c.h());
                            task.setStatus("SKIPPED_WHITE");
                            dbBatch.add(task);
                            skippedWhite++;
                            continue;
                        }

                        byte[] bytes = toBytes(img);
                        img.flush();

                        UUID patchId = UUID.randomUUID();
                        String patchPath = "patches/%s/patch_%d_%d.png"
                                .formatted(event.jobId(), c.x(), c.y());

                        dbBatch.add(buildPatchTask(event.jobId(), patchId, patchPath,
                                c.x(), c.y(), c.w(), c.h()));
                        mqBatch.add(new PatchInferenceEvent(
                                event.jobId(), patchId, patchPath,
                                c.x(), c.y(), c.w(), c.h()));

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

                    // Батчевый INSERT в БД (включая skipped_white записи)
                    patchTaskRepository.saveAll(dbBatch);

                    // Публикуем в RabbitMQ только не-белые патчи
                    mqBatch.forEach(p -> rabbitTemplate.convertAndSend("patches.inference", p));

                    totalPatches += batch.size();

                    if (totalPatches % 500 == 0 || batchEnd == coords.size()) {
                        log.info("TilingWorker progress: {}/{} patches (skipped_white={}), job={}",
                                totalPatches, coords.size(), skippedWhite, event.jobId());
                    }
                }

                if (skippedWhite > 0) {
                    jobRepository.incrementSkippedWhite(event.jobId(), skippedWhite);
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

    /**
     * E3: быстрая проверка «патч почти белый» — сэмплируем каждый 16-й пиксель.
     * Если средняя яркость по RGB > WHITE_THRESHOLD — патч считается фоном.
     */
    private boolean isMostlyWhite(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        long sum = 0;
        long count = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb        & 0xFF;
                sum += (r + g + b);
                count += 3;
            }
        }
        if (count == 0) return false;
        double mean = (double) sum / count;
        return mean > WHITE_THRESHOLD;
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