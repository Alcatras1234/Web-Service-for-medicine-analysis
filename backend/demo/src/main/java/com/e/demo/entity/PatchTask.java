package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patch_tasks")
@Data
public class PatchTask {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "minio_path")
    private String minioPath;

    private int x;
    private int y;
    private int width;
    private int height;

    @Column(nullable = false)
    private String status = "PENDING";

    // Результат инференса. eosinophilCount = intact + granulated (для обратной совместимости).
    @Column(name = "eosinophil_count")
    private Integer eosinophilCount = 0;

    /** §3.2: intact (целые) эозинофилы — только эти учитываются в PEC по EoE consensus. */
    @Column(name = "eos_intact")
    private Integer eosIntact = 0;

    /** §3.2: granulated / разрушенные — служебная метрика, в PEC не входит. */
    @Column(name = "eos_granulated")
    private Integer eosGranulated = 0;

    private int attempts;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "total_count")
    private int totalCount;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}