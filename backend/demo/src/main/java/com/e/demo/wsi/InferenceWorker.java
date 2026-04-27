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
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.awt.Graphics2D;
import java.awt.RenderingHints;  

@Component
@Slf4j
public class InferenceWorker {

    private final MinioClient minioClient;
    private final PatchTaskRepository patchTaskRepository;
    private final JobRepository jobRepository;
    private final InferenceHttpClient inferenceClient;
    private final ReportService reportService;

    @Value("${minio.bucketName}")
    private String bucket;

    public InferenceWorker(@Qualifier("internalClient") MinioClient minioClient,
                           PatchTaskRepository patchTaskRepository,
                           JobRepository jobRepository,
                           InferenceHttpClient inferenceHttpClientClient,
                           ReportService reportService) {
        this.minioClient = minioClient;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository = jobRepository;
        this.inferenceClient = inferenceHttpClientClient;
        this.reportService = reportService;
    }

    // 4 патча параллельно из очереди
    @RabbitListener(queues = "patches.inference", concurrency = "4")
    public void handle(PatchInferenceEvent event) {
        log.info("Inference start: job={} patch={}", event.jobId(), event.patchId());
        Path tmp = null;
        try {
            tmp = Files.createTempFile("patch-", ".png");
            downloadToFile(event.s3Path(), tmp);

            BufferedImage img = ImageIO.read(tmp.toFile());
            float[] tensor = toFloat32CHW(img, 448, 448);

            int count = inferenceClient.infer(tensor);
            log.info("Patch {} → {} eos", event.patchId(), count);

            savePatchResult(event.patchId(), "DONE", count);
        } catch (Exception e) {
            log.error("Inference failed: patch={}", event.patchId(), e);
            savePatchResult(event.patchId(), "FAILED", 0);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }

        // Проверяем — завершён ли весь джоб
        checkAndFinalizeJob(event.jobId());
    }

    private void savePatchResult(UUID patchId, String status, int count) {
        patchTaskRepository.findById(patchId).ifPresent(task -> {
            task.setStatus(status);
            task.setEosinophilCount(count);
            task.setUpdatedAt(Instant.now());
            patchTaskRepository.save(task);
        });
    }

    private void checkAndFinalizeJob(UUID jobId) {
        // Считаем именно IN_PROGRESS — патчи которые ещё не DONE и не FAILED
        long notDone = patchTaskRepository.countByJobIdAndStatusNotIn(jobId, List.of("DONE", "FAILED"));
        if (notDone != 0) return;

        // Атомарно помечаем job как FINALIZING чтобы только один поток прошёл дальше
        int updated = jobRepository.tryFinalizeJob(jobId, "PROCESSING", "FINALIZING", Instant.now());
        if (updated == 0) return; // другой воркер уже взял финализацию
        
        long pending = patchTaskRepository.countByJobIdAndStatus(jobId, "PENDING");
        if (pending != 0) return;

        // Все патчи обработаны — считаем итог
        int totalCount = patchTaskRepository.sumEosinophilCountByJobId(jobId);
        long failed    = patchTaskRepository.countByJobIdAndStatus(jobId, "FAILED");

        // Патч с максимумом — для PDF и координат на слайде
        PatchTask maxPatch = patchTaskRepository
                .findTopByJobIdOrderByEosinophilCountDesc(jobId);

        int maxHpfCount = maxPatch != null ? maxPatch.getEosinophilCount() : 0;
        int maxHpfX     = maxPatch != null ? maxPatch.getX() : 0;
        int maxHpfY     = maxPatch != null ? maxPatch.getY() : 0;

        // Диагноз по стандарту EoE: ≥15 эозинофилов в одном HPF
        String diagnosis = maxHpfCount >= 15 ? "POSITIVE" : "NEGATIVE";
        String status    = failed > 0 ? "DONE_WITH_ERRORS" : "DONE";

        jobRepository.updateInferenceResult(
                jobId, status, totalCount,
                maxHpfCount, maxHpfX, maxHpfY,
                diagnosis, Instant.now()
        );

        log.info("Job {} DONE: total={} maxHpf={} diagnosis={}",
                jobId, totalCount, maxHpfCount, diagnosis);

        // Асинхронно генерируем heatmap + PDF
        reportService.generateAsync(jobId);
    }

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private float[] toFloat32CHW(BufferedImage img, int W, int H) {
        if (img.getWidth() != W || img.getHeight() != H) {
            BufferedImage r = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = r.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(img, 0, 0, W, H, null);
            g2d.dispose();
            img = r;
        }
        float[] data = new float[3 * W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = img.getRGB(x, y);
                int o = y * W + x;
                data[o]         = ((rgb >> 16) & 0xFF) / 255.0f;
                data[W*H + o]   = ((rgb >>  8) & 0xFF) / 255.0f;
                data[2*W*H + o] = ( rgb        & 0xFF) / 255.0f;
            }
        }
        return data;
    }
}