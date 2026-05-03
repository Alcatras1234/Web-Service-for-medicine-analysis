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

import javax.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class InferenceWorker {

    private static final int PATCH_WSI_SIZE = 448;
    private static final int PATCH_STRIDE   = 424;
    private static final int OVERLAP_PX     = PATCH_WSI_SIZE - PATCH_STRIDE;
    private static final int MODEL_SIZE     = 448;
    private static final int HPF_WINDOW_PX  = 2144;
    private static final int HPF_STEP_PX    = 500;
    private static final int BATCH_SIZE     = 8;

    private final MinioClient          minioClient;
    private final PatchTaskRepository  patchTaskRepository;
    private final JobRepository        jobRepository;
    private final InferenceHttpClient  inferenceClient;
    private final ReportService        reportService;

    @Value("${minio.bucketName}")
    private String bucket;

    private final LinkedBlockingQueue<PatchInferenceEvent> pendingBatch =
            new LinkedBlockingQueue<>();

    private final ExecutorService drainExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "batch-drain");
                t.setDaemon(true);
                return t;
            });

    public InferenceWorker(
            @Qualifier("internalClient") MinioClient minioClient,
            PatchTaskRepository patchTaskRepository,
            JobRepository jobRepository,
            InferenceHttpClient inferenceClient,
            ReportService reportService) {
        this.minioClient         = minioClient;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository       = jobRepository;
        this.inferenceClient     = inferenceClient;
        this.reportService       = reportService;
    }

    @RabbitListener(queues = "patches.inference", concurrency = "4")
    public void handle(PatchInferenceEvent event) {
        pendingBatch.offer(event);
        if (pendingBatch.size() >= BATCH_SIZE) {
            drainExecutor.submit(this::drainBatch);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down drainExecutor...");
        drainExecutor.shutdown();
        try {
            if (!drainExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                drainExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            drainExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void drainBatch() {
        List<PatchInferenceEvent> batch = new ArrayList<>(BATCH_SIZE);
        pendingBatch.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return;

        log.info("Processing batch of {} patches", batch.size());

        for (PatchInferenceEvent event : batch) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("patch-", ".png");
                downloadToFile(event.s3Path(), tmp);
                BufferedImage img = ImageIO.read(tmp.toFile());

                PatchTask task = patchTaskRepository.findById(event.patchId()).orElseThrow();
                int patchX = task.getX();
                int patchY = task.getY();

                boolean edgeLeft   = patchX == 0;
                boolean edgeTop    = patchY == 0;
                boolean edgeRight  = !patchTaskRepository
                        .existsByJobIdAndXAndY(event.jobId(), patchX + PATCH_STRIDE, patchY);
                boolean edgeBottom = !patchTaskRepository
                        .existsByJobIdAndXAndY(event.jobId(), patchX, patchY + PATCH_STRIDE);

                float[] tensor = toFloat32CHW(img, MODEL_SIZE, MODEL_SIZE);

                InferenceHttpClient.InferResult result = inferenceClient.infer(
                        tensor, PATCH_WSI_SIZE, OVERLAP_PX,
                        edgeLeft, edgeTop, edgeRight, edgeBottom);

                log.info("Patch {} ({},{}) → total={} valid={}",
                        event.patchId(), patchX, patchY,
                        result.totalCount(), result.validCount());

                savePatchResult(event.patchId(), "DONE", result.validCount(), result.totalCount());

            } catch (Exception e) {
                log.error("Inference failed: patch={}", event.patchId(), e);
                savePatchResult(event.patchId(), "FAILED", 0, 0);
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }

            checkAndFinalizeJob(event.jobId());
        }
    }

    private void savePatchResult(UUID patchId, String status,
                                 int validCount, int totalCount) {
        patchTaskRepository.findById(patchId).ifPresent(task -> {
            task.setStatus(status);
            task.setEosinophilCount(validCount);
            task.setTotalCount(totalCount);
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

        int  totalCount = patchTaskRepository.sumEosinophilCountByJobId(jobId);
        long failed    = patchTaskRepository.countByJobIdAndStatus(jobId, "FAILED");

        List<PatchTask> done = patchTaskRepository.findByJobIdAndStatus(jobId, "DONE");

        int maxHpfCount = 0, maxHpfX = 0, maxHpfY = 0;

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

        String diagnosis = maxHpfCount >= 15 ? "POSITIVE" : "NEGATIVE";
        String status    = failed > 0 ? "DONE_WITH_ERRORS" : "DONE";

        jobRepository.updateInferenceResult(
                jobId, status, totalCount,
                maxHpfCount, maxHpfX, maxHpfY,
                diagnosis, Instant.now());

        log.info("Job {} DONE — total={} peakHPF={} @ ({},{}) diagnosis={}",
                jobId, totalCount, maxHpfCount, maxHpfX, maxHpfY, diagnosis);

        reportService.generateAsync(jobId);
    }

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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