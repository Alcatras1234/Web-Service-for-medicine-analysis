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

    private int attempts;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
