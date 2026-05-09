package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Data
public class Job {

    @Id
    private UUID id;

    @Column(name = "slide_id", nullable = false)
    private Integer slideId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "patches_total")
    private Integer patchesTotal = 0;

    @Column(name = "patches_remaining")
    private Integer patchesRemaining = 0;

    // Новые поля для результатов инференса
    @Column(name = "total_eosinophil_count")
    private Integer totalEosinophilCount = 0;

    @Column(name = "max_hpf_count")
    private Integer maxHpfCount = 0;

    @Column(name = "max_hpf_x")
    private Integer maxHpfX;

    @Column(name = "max_hpf_y")
    private Integer maxHpfY;

    // POSITIVE (≥15 eos/HPF) или NEGATIVE
    @Column(name = "diagnosis")
    private String diagnosis;

    // Путь к PDF в MinIO
    @Column(name = "report_path")
    private String reportPath;

    // Путь к heatmap PNG в MinIO
    @Column(name = "heatmap_path")
    private String heatmapPath;

    // E3: количество отсеянных белых патчей (не отправлены на инференс)
    @Column(name = "skipped_white")
    private Integer skippedWhite = 0;

    // E6/E8: версия ML-модели и путь к JSON детекций в MinIO
    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "detections_path")
    private String detectionsPath;

    // Текущая фаза: QUEUED / TILING / INFERENCING / FINALIZING / REPORTING / DONE
    @Column(name = "phase")
    private String phase;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}