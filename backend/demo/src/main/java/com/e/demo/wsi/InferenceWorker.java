package com.e.demo.wsi;

import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.entity.Job;
import com.e.demo.entity.PatchTask;
import com.e.demo.entity.Slide;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import com.e.demo.repository.SlideRepository;
import com.e.demo.server.DetectionStore;
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

    private static final int PATCH_WSI_SIZE = 448;
    private static final int PATCH_STRIDE   = 424;
    private static final int OVERLAP_PX     = PATCH_WSI_SIZE - PATCH_STRIDE;
    private static final int MODEL_SIZE     = 448;
    // HPF (high power field) по клиническому стандарту EoE: площадь 0.3 мм².
    // Сторона квадратного окна = sqrt(0.3) ≈ 0.5477 мм.
    private static final double HPF_AREA_MM2 = 0.3;

    private final MinioClient         minioClient;
    private final PatchTaskRepository patchTaskRepository;
    private final JobRepository       jobRepository;
    private final SlideRepository     slideRepository;
    private final InferenceHttpClient inferenceClient;
    private final DetectionStore      detectionStore;
    private final ReportService       reportService;

    @Value("${minio.bucketName}")
    private String bucket;

    public InferenceWorker(
        @Qualifier("internalClient") MinioClient minioClient,
        PatchTaskRepository patchTaskRepository,
        JobRepository jobRepository,
        SlideRepository slideRepository,
        InferenceHttpClient inferenceClient,
        DetectionStore detectionStore,
        ReportService reportService
    ) {
        this.minioClient         = minioClient;
        this.patchTaskRepository = patchTaskRepository;
        this.jobRepository       = jobRepository;
        this.slideRepository     = slideRepository;
        this.inferenceClient     = inferenceClient;
        this.detectionStore      = detectionStore;
        this.reportService       = reportService;
    }

    /** HPF-окно в пикселях по реальному MPP слайда (E1). */
    private int hpfWindowPx(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return 2191; // safe fallback ≈ 0.3 мм² при mpp=0.25
        Slide slide = slideRepository.findById(job.getSlideId()).orElse(null);
        double mpp = (slide != null && slide.getMppX() != null && slide.getMppX() > 0)
                ? slide.getMppX()
                : BioFormatsWsiReader.DEFAULT_MPP;
        double sideMm = Math.sqrt(HPF_AREA_MM2);            // мм
        double sideUm = sideMm * 1000.0;                    // µm
        return (int) Math.round(sideUm / mpp);              // px
    }

    /**
     * E3: батч-листенер. Получает до 16 событий за раз (см. RabbitMQConfig.batchInferenceFactory),
     * скачивает все патчи, шлёт ОДИН HTTP /infer_batch вместо N.
     */
    @RabbitListener(queues = "patches.inference", containerFactory = "batchInferenceFactory")
    public void handleBatch(List<PatchInferenceEvent> events) {
        if (events == null || events.isEmpty()) return;

        // jobId одинаковый для всех патчей одного слайда — но на всякий случай группируем
        Map<UUID, List<PatchInferenceEvent>> byJob = new HashMap<>();
        for (PatchInferenceEvent e : events) {
            byJob.computeIfAbsent(e.jobId(), k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<UUID, List<PatchInferenceEvent>> entry : byJob.entrySet()) {
            processJobBatch(entry.getKey(), entry.getValue());
        }

        // Финализация — один раз на каждый затронутый job
        byJob.keySet().forEach(this::checkAndFinalizeJob);
    }

    private void processJobBatch(UUID jobId, List<PatchInferenceEvent> events) {
        List<Path> tmps = new ArrayList<>(events.size());
        List<InferenceHttpClient.PatchItemDto> items = new ArrayList<>(events.size());
        Map<String, UUID> patchIdMap = new HashMap<>();   // patch_id -> task.id
        List<UUID> failed = new ArrayList<>();

        try {
            for (PatchInferenceEvent ev : events) {
                Path tmp = null;
                try {
                    tmp = Files.createTempFile("patch-", ".png");
                    downloadToFile(ev.s3Path(), tmp);
                    BufferedImage img = ImageIO.read(tmp.toFile());

                    PatchTask task = patchTaskRepository.findById(ev.patchId()).orElseThrow();
                    int patchX = task.getX();
                    int patchY = task.getY();

                    boolean edgeLeft   = patchX == 0;
                    boolean edgeTop    = patchY == 0;
                    boolean edgeRight  = !patchTaskRepository
                        .existsByJobIdAndXAndY(ev.jobId(), patchX + PATCH_STRIDE, patchY);
                    boolean edgeBottom = !patchTaskRepository
                        .existsByJobIdAndXAndY(ev.jobId(), patchX, patchY + PATCH_STRIDE);

                    byte[] tensor = toRgbHWC(img, MODEL_SIZE, MODEL_SIZE);
                    String b64 = Base64.getEncoder().encodeToString(tensor);
                    String pid = ev.patchId().toString();

                    items.add(new InferenceHttpClient.PatchItemDto(
                        pid, b64, PATCH_WSI_SIZE, OVERLAP_PX,
                        edgeLeft, edgeTop, edgeRight, edgeBottom
                    ));
                    patchIdMap.put(pid, ev.patchId());
                    tmps.add(tmp);
                    tmp = null;
                } catch (Exception e) {
                    log.error("Patch prep failed: {}", ev.patchId(), e);
                    failed.add(ev.patchId());
                } finally {
                    if (tmp != null) {
                        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    }
                }
            }

            if (!items.isEmpty()) {
                try {
                    InferenceHttpClient.BatchResponse resp = inferenceClient.inferBatch(items);
                    // E6: фиксируем версию модели (только если ещё не записана)
                    Job j = jobRepository.findById(jobId).orElse(null);
                    if (j != null && j.getModelVersion() == null) {
                        jobRepository.updateModelVersion(jobId, resp.modelVersion());
                    }
                    Set<UUID> handled = new HashSet<>();
                    for (InferenceHttpClient.InferResult r : resp.results()) {
                        UUID pid = patchIdMap.get(r.patchId());
                        if (pid == null) continue;
                        // §3.2: сохраняем раздельно intact и granulated
                        savePatchResult(pid, "DONE", r.validCount(), r.totalCount(),
                                r.validEos(), r.validEosg());
                        // E6: пишем глобальные координаты детекций в MinIO
                        if (r.detections() != null && !r.detections().isEmpty()) {
                            PatchTask pt = patchTaskRepository.findById(pid).orElse(null);
                            if (pt != null) {
                                detectionStore.savePatchDetections(
                                    jobId, pid, pt.getX(), pt.getY(), r.detections());
                            }
                        }
                        handled.add(pid);
                    }
                    // Те, что отправили, но в ответе не пришли — помечаем FAILED
                    for (UUID pid : patchIdMap.values()) {
                        if (!handled.contains(pid)) failed.add(pid);
                    }
                    log.info("Batch inference: job={} model={} sent={} ok={} failed={}",
                        jobId, resp.modelVersion(), items.size(), handled.size(),
                        items.size() - handled.size());
                } catch (Exception e) {
                    log.error("Batch inference HTTP failed: job={} size={}", jobId, items.size(), e);
                    failed.addAll(patchIdMap.values());
                }
            }

            for (UUID pid : failed) {
                savePatchResult(pid, "FAILED", 0, 0, 0, 0);
            }
        } finally {
            for (Path p : tmps) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
        }
    }

    private void savePatchResult(UUID patchId, String status, int validCount, int totalCount,
                                 int eosIntact, int eosGranulated) {
        patchTaskRepository.findById(patchId).ifPresent(task -> {
            task.setStatus(status);
            task.setEosinophilCount(validCount);
            task.setTotalCount(totalCount);
            task.setEosIntact(eosIntact);
            task.setEosGranulated(eosGranulated);
            task.setUpdatedAt(Instant.now());
            patchTaskRepository.save(task);
        });
    }

    private void checkAndFinalizeJob(UUID jobId) {
        long notDone = patchTaskRepository.countByJobIdAndStatusNotIn(
            jobId, List.of("DONE", "FAILED", "SKIPPED_WHITE"));
        if (notDone != 0) return;

        int updated = jobRepository.tryFinalizeJob(
            jobId, "PROCESSING", "FINALIZING", Instant.now());
        if (updated == 0) return;

        long pending = patchTaskRepository.countByJobIdAndStatus(jobId, "PENDING");
        if (pending != 0) return;

        int totalCount       = patchTaskRepository.sumEosinophilCountByJobId(jobId);
        long failed          = patchTaskRepository.countByJobIdAndStatus(jobId, "FAILED");
        List<PatchTask> done = patchTaskRepository.findByJobIdAndStatus(jobId, "DONE");

        // §2 + §3.3: тонкий sliding-window поиск окна максимальной плотности.
        // §3.2: диагноз ставится только по INTACT эозинофилам, как в EoE consensus.
        int hpfWindow = hpfWindowPx(jobId);
        long t0 = System.currentTimeMillis();

        // 1) Находим окно, где максимум INTACT — это диагностическая позиция
        HpfFinder.HpfResult hpfIntact = HpfFinder.findByIntact(done, PATCH_STRIDE, hpfWindow);
        int maxHpfIntact = hpfIntact.count();
        int maxHpfX      = hpfIntact.x();
        int maxHpfY      = hpfIntact.y();

        // 2) Считаем total (intact+granulated) В ТОЙ ЖЕ ПОЗИЦИИ через агрегацию патчей.
        //    Это приближённая оценка — патчи на краях окна считаются целиком.
        int approxTotal = HpfFinder.sumAt(done, PATCH_STRIDE, hpfWindow, maxHpfX, maxHpfY,
                p -> p.getEosinophilCount() == null ? 0 : p.getEosinophilCount());

        // E6/E8: индекс детекций. Записываем СНАЧАЛА — нужен для точного пересчёта PEC.
        List<String> detectionKeys = new ArrayList<>();
        for (PatchTask p : done) {
            if (p.getEosinophilCount() != null && p.getEosinophilCount() > 0) {
                detectionKeys.add("detections/%s/patch_%s.json".formatted(jobId, p.getId()));
            }
        }
        String idxKey = null;
        if (!detectionKeys.isEmpty()) {
            idxKey = detectionStore.writeIndex(jobId, detectionKeys);
            if (idxKey != null) jobRepository.updateDetectionsPath(jobId, idxKey);
        }

        // 3) ТОЧНЫЙ пересчёт PEC по координатам детекций в HPF-окне.
        //    sumAt брал целые патчи на краях — поэтому завышал. Здесь же — каждая
        //    детекция учитывается только если её (cx,cy) реально внутри окна.
        int maxHpfCount = approxTotal;
        if (idxKey != null) {
            int[] exact = exactCountInWindow(idxKey, maxHpfX, maxHpfY, hpfWindow);
            // exact[0] = intact, exact[1] = granulated
            int exactIntact = exact[0];
            int exactTotal  = exact[0] + exact[1];
            log.info("HPF exact recount: approx_intact={} → exact_intact={}, approx_total={} → exact_total={}",
                    maxHpfIntact, exactIntact, approxTotal, exactTotal);
            maxHpfIntact = exactIntact;
            maxHpfCount  = exactTotal;
        }

        long dt = System.currentTimeMillis() - t0;
        log.info("HPF search: job={} patches={} window={}px intact={} total={} @ ({},{}) in {}ms",
            jobId, done.size(), hpfWindow, maxHpfIntact, maxHpfCount, maxHpfX, maxHpfY, dt);

        // §3.2: порог 15 применяется к INTACT (теперь точный)
        String diagnosis = maxHpfIntact >= 15 ? "POSITIVE" : "NEGATIVE";
        String status    = failed > 0 ? "DONE_WITH_ERRORS" : "DONE";

        jobRepository.updateInferenceResult(
            jobId, status, totalCount, maxHpfCount, maxHpfIntact,
            maxHpfX, maxHpfY, diagnosis, Instant.now());

        log.info("Job {} DONE — total={} peakHPF(intact)={} (sum={}) @ ({},{}) diagnosis={} detections={}",
            jobId, totalCount, maxHpfIntact, maxHpfCount, maxHpfX, maxHpfY, diagnosis, detectionKeys.size());

        reportService.generateAsync(jobId);
    }

    /**
     * Точный подсчёт клеток в HPF-окне по координатам детекций.
     * Возвращает [intact_cells, granulated_cells] — суммы поля {@code cells}
     * (CC от seg-маски) для детекций чьи (cx,cy) попадают в окно.
     */
    private int[] exactCountInWindow(String detectionsIndexKey, int sx, int sy, int win) {
        try {
            List<Map<String, Object>> dets = detectionStore.readAll(detectionsIndexKey);
            int intact = 0, granulated = 0;
            for (Map<String, Object> d : dets) {
                double cx = ((Number) d.getOrDefault("cx", 0)).doubleValue();
                double cy = ((Number) d.getOrDefault("cy", 0)).doubleValue();
                if (cx < sx || cx > sx + win) continue;
                if (cy < sy || cy > sy + win) continue;
                int cells = Math.max(1, ((Number) d.getOrDefault("cells", 1)).intValue());
                String cls = String.valueOf(d.getOrDefault("cls", ""));
                if ("eos".equals(cls)) intact += cells;
                else granulated += cells;
            }
            return new int[]{intact, granulated};
        } catch (Exception e) {
            log.error("exactCountInWindow failed for {}: {}", detectionsIndexKey, e.getMessage());
            return new int[]{0, 0};
        }
    }

    private void downloadToFile(String s3Path, Path dest) throws Exception {
        try (InputStream in = minioClient.getObject(
            GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Конвертируем BufferedImage → RGB HWC uint8 байты.
     * Формат: [H * W * 3], порядок R, G, B для каждого пикселя.
     * Питон принимает этот формат и делает нормализацию сам.
     */
    private byte[] toRgbHWC(BufferedImage src, int targetW, int targetH) {
        BufferedImage img = src;
        if (src.getWidth() != targetW || src.getHeight() != targetH) {
            img = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                 RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, targetW, targetH, null);
            g2d.dispose();
        }

        byte[] data = new byte[targetH * targetW * 3];
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int rgb = img.getRGB(x, y);
                int idx = (y * targetW + x) * 3;
                data[idx]     = (byte) ((rgb >> 16) & 0xFF); // R
                data[idx + 1] = (byte) ((rgb >>  8) & 0xFF); // G
                data[idx + 2] = (byte) ( rgb        & 0xFF); // B
            }
        }
        return data;
    }
}