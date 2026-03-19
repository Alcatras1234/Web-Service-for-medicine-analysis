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

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
