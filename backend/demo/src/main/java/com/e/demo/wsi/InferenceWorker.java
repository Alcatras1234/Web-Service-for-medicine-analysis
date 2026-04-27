package com.e.demo.wsi;

import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.entity.PatchTask;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import com.e.demo.server.ReportService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;

@Component
@Slf4j
public class InferenceWorker {

    // ── Константы патча ──────────────────────────────────────────────────────
    /** Реальный размер патча в WSI-пикселях (что нарезается из слайда) */
    private static final int PATCH_WSI_SIZE = 448;
    /** Шаг нарезки. Нахлёст = PATCH_WSI_SIZE - PATCH_STRIDE = 64 px */
    private static final int PATCH_STRIDE   = 424;
    /** Нахлёст в WSI-пикселях */
    private static final int OVERLAP_PX     = PATCH_WSI_SIZE - PATCH_STRIDE; // 64
    /** Размер входа модели */
    private static final int MODEL_SIZE     = 448;

    // ── Параметры скользящего окна ПЗБУ (0.3 мм² при ×40, 0.25 мкм/px) ─────
    private static final int HPF_WINDOW_PX  = 2144;
    private static final int HPF_STEP_PX    = 500;

    private final MinioClient         minioClient;
    private final PatchTaskRepository patchTaskRepository;
    private final JobRepository       jobRepository;
    private final InferenceHttpClient inferenceClient;
    private final ReportService       reportService;

    @Value("${minio.bucketName}")
    private String bucket;

    public InferenceWorker(
            @Qualifier("internalClient") MinioClient minioClient,
            PatchTaskRepository patchTaskRepository,
            JobRepository jobRepository,
            InferenceHttpClient inferenceHttpClientClient,
            ReportService reportService) {
        this.minioClient        = minioClient;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository      = jobRepository;
        this.inferenceClient    = inferenceHttpClientClient;
        this.reportService      = reportService;
    }

    @RabbitListener(queues = "patches.inference", concurrency = "4")
    public void handle(PatchInferenceEvent event) {
        log.info("Inference start: job={} patch={}", event.jobId(), event.patchId());
        Path tmp = null;
        try {
            tmp = Files.createTempFile("patch-", ".png");
            downloadToFile(event.s3Path(), tmp);
            BufferedImage img = ImageIO.read(tmp.toFile());

            // Берём координаты патча из БД
            PatchTask task = patchTaskRepository.findById(event.patchId()).orElseThrow();
            int patchX = task.getX();
            int patchY = task.getY();

            // Определяем крайние патчи (левый/верхний — тривиально)
            boolean edgeLeft = patchX == 0;
            boolean edgeTop  = patchY == 0;

            // Правый/нижний: нет соседнего патча в этом джобе со сдвигом на STRIDE
            boolean edgeRight  = !patchTaskRepository
                    .existsByJobIdAndXAndY(event.jobId(), patchX + PATCH_STRIDE, patchY);
            boolean edgeBottom = !patchTaskRepository
                    .existsByJobIdAndXAndY(event.jobId(), patchX, patchY + PATCH_STRIDE);

            // Ресайз 512→448 для модели
            float[] tensor = toFloat32CHW(img, MODEL_SIZE, MODEL_SIZE);

            InferenceHttpClient.InferResult result = inferenceClient.infer(
                    tensor, PATCH_WSI_SIZE, OVERLAP_PX,
                    edgeLeft, edgeTop, edgeRight, edgeBottom);

            log.info("Patch {} offset=({},{}) → total={} valid={}",
                    event.patchId(), patchX, patchY,
                    result.totalCount(), result.validCount());

            // eosinophilCount = valid_count (без двойного счёта по нахлёсту)
            savePatchResult(event.patchId(), "DONE", result.validCount(), result.totalCount());

        } catch (Exception e) {
            log.error("Inference failed: patch={}", event.patchId(), e);
            savePatchResult(event.patchId(), "FAILED", 0, 0);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }

        checkAndFinalizeJob(event.jobId());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void savePatchResult(UUID patchId, String status,
                                 int validCount, int totalCount) {
        patchTaskRepository.findById(patchId).ifPresent(task -> {
            task.setStatus(status);
            task.setEosinophilCount(validCount);  // используется в sliding window
            task.setTotalCount(totalCount);        // raw — для отчёта
            task.setUpdatedAt(Instant.now());
            patchTaskRepository.save(task);
        });
    }

    private void checkAndFinalizeJob(UUID jobId) {
        long notDone = patchTaskRepository.countByJobIdAndStatusNotIn(
                jobId, List.of("DONE", "FAILED"));
        if (notDone != 0) return;

        int updated = jobRepository.tryFinalizeJob(
                jobId, "PROCESSING", "FINALIZING", Instant.now());
        if (updated == 0) return;

        long pending = patchTaskRepository.countByJobIdAndStatus(jobId, "PENDING");
        if (pending != 0) return;

        // Суммарный valid_count по всем патчам (без двойного счёта нахлёста)
        int totalCount = patchTaskRepository.sumEosinophilCountByJobId(jobId);
        long failed    = patchTaskRepository.countByJobIdAndStatus(jobId, "FAILED");

        log.info("Job {} — total valid eosinophils: {}", jobId, totalCount);

        // ── Sliding window для поиска пиковой области ПЗБУ ───────────────────
        // valid_count в каждом патче уже исключает дубли из нахлёста,
        // поэтому просто суммируем патчи, перекрывающиеся с окном.
        List<PatchTask> done = patchTaskRepository.findByJobIdAndStatus(jobId, "DONE");

        int maxHpfCount = 0;
        int maxHpfX     = 0;
        int maxHpfY     = 0;

        if (!done.isEmpty()) {
            int maxWSI_X = done.stream()
                    .mapToInt(p -> p.getX() + PATCH_WSI_SIZE).max().orElse(0);
            int maxWSI_Y = done.stream()
                    .mapToInt(p -> p.getY() + PATCH_WSI_SIZE).max().orElse(0);

            for (int wy = 0; wy < maxWSI_Y; wy += HPF_STEP_PX) {
                for (int wx = 0; wx < maxWSI_X; wx += HPF_STEP_PX) {
                    int wx2 = wx + HPF_WINDOW_PX;
                    int wy2 = wy + HPF_WINDOW_PX;

                    int windowCount = 0;
                    for (PatchTask p : done) {
                        int px2 = p.getX() + PATCH_WSI_SIZE;
                        int py2 = p.getY() + PATCH_WSI_SIZE;
                        if (p.getX() < wx2 && px2 > wx
                                && p.getY() < wy2 && py2 > wy) {
                            windowCount += p.getEosinophilCount();
                        }
                    }

                    if (windowCount > maxHpfCount) {
                        maxHpfCount = windowCount;
                        maxHpfX     = wx;
                        maxHpfY     = wy;
                    }
                }
            }
        }

        log.info("Job {} — peak ПЗБУ: count={} at ({},{})",
                jobId, maxHpfCount, maxHpfX, maxHpfY);

        String diagnosis = maxHpfCount >= 15 ? "POSITIVE" : "NEGATIVE";
        String status    = failed > 0 ? "DONE_WITH_ERRORS" : "DONE";

        jobRepository.updateInferenceResult(
                jobId, status, totalCount,
                maxHpfCount, maxHpfX, maxHpfY,
                diagnosis, Instant.now()
        );

        log.info("Job {} DONE — total={} peakHPF={} @ ({},{}) diagnosis={}",
                jobId, totalCount, maxHpfCount, maxHpfX, maxHpfY, diagnosis);

        reportService.generateAsync(jobId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Ресайзит BufferedImage до targetW×targetH и возвращает float32 NCHW тензор.
     * Работает для любого входного размера (512×512 → 448×448).
     */
    private float[] toFloat32CHW(BufferedImage src, int targetW, int targetH) {
        BufferedImage img = src;
        if (src.getWidth() != targetW || src.getHeight() != targetH) {
            img = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, targetW, targetH, null);
            g2d.dispose();
        }

        float[] data = new float[3 * targetW * targetH];
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int rgb = img.getRGB(x, y);
                int o   = y * targetW + x;
                data[o]                         = ((rgb >> 16) & 0xFF) / 255.0f;
                data[targetW * targetH + o]     = ((rgb >> 8)  & 0xFF) / 255.0f;
                data[2 * targetW * targetH + o] = (rgb & 0xFF)         / 255.0f;
            }
        }
        return data;
    }
}