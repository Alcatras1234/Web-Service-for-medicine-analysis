package com.e.demo.Controllers;

import com.e.demo.entity.Job;
import com.e.demo.repository.JobRepository;
import com.e.demo.server.DetectionStore;
import com.e.demo.server.TileService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E4: endpoints для OpenSeadragon-вьюера.
 *  - GET /api/iiif/{slideId}/info.json — метаданные изображения для tileSource
 *  - GET /api/iiif/{slideId}/tile/{level}/{x}_{y}.jpg — байты тайла
 *  - GET /api/slides/{slideId}/detections — детекции для overlay (заглушка → job result)
 */
@RestController
@RequestMapping("/api")
public class TileController {

    private final TileService tileService;
    private final JobRepository jobRepository;
    private final DetectionStore detectionStore;

    public TileController(TileService tileService,
                          JobRepository jobRepository,
                          DetectionStore detectionStore) {
        this.tileService = tileService;
        this.jobRepository = jobRepository;
        this.detectionStore = detectionStore;
    }

    @GetMapping("/iiif/{slideId}/info.json")
    public ResponseEntity<?> info(@PathVariable Integer slideId) {
        return tileService.getInfo(slideId)
                .<ResponseEntity<?>>map(info -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("slideId", slideId);
                    body.put("width", info.width());
                    body.put("height", info.height());
                    body.put("tileSize", info.tileSize());
                    body.put("maxLevel", info.maxLevel());
                    body.put("mppX", info.mppX());
                    body.put("mppY", info.mppY());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/iiif/{slideId}/tile/{level}/{coords}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> tile(@PathVariable Integer slideId,
                                       @PathVariable Integer level,
                                       @PathVariable String coords) {
        // coords = "{x}_{y}.jpg"
        String trimmed = coords.endsWith(".jpg") ? coords.substring(0, coords.length() - 4) : coords;
        String[] parts = trimmed.split("_");
        if (parts.length != 2) return ResponseEntity.badRequest().build();
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        try {
            byte[] tile = tileService.getTile(slideId, level, x, y);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(tile);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /** Сводка детекций — координаты HPF max, agg counts, diagnosis. */
    @GetMapping("/slides/{slideId}/detections")
    public ResponseEntity<?> detections(@PathVariable Integer slideId) {
        Job job = jobRepository.findFirstBySlideIdOrderByCreatedAtDesc(slideId).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId());
        body.put("totalEosinophils", job.getTotalEosinophilCount());
        body.put("maxHpfCount", job.getMaxHpfCount());
        body.put("maxHpfX", job.getMaxHpfX());
        body.put("maxHpfY", job.getMaxHpfY());
        body.put("diagnosis", job.getDiagnosis());
        body.put("modelVersion", job.getModelVersion());
        body.put("detectionsPath", job.getDetectionsPath());
        return ResponseEntity.ok(body);
    }

    /**
     * E6: полный список координат детекций (для overlay в OpenSeadragon).
     * Читает из MinIO по {@code job.detections_path}. На больших слайдах ответ
     * может быть тяжёлым — фронту стоит брать его лениво/по запросу.
     */
    @GetMapping("/slides/{slideId}/detections/full")
    public ResponseEntity<?> detectionsFull(@PathVariable Integer slideId) {
        Job job = jobRepository.findFirstBySlideIdOrderByCreatedAtDesc(slideId).orElse(null);
        if (job == null || job.getDetectionsPath() == null) {
            return ResponseEntity.ok(Map.of("detections", List.of()));
        }
        return ResponseEntity.ok(Map.of(
            "jobId", job.getId(),
            "modelVersion", job.getModelVersion(),
            "detections", detectionStore.readAll(job.getDetectionsPath())
        ));
    }
}
