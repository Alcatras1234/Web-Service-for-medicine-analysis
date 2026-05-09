package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "slides")
@Data
public class Slide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "s3_path", nullable = false)
    private String s3Path;

    @Column(name = "patient_id")
    private String patientId;       // ← ДОБАВЛЕНО

    @Column
    private String description;     // ← ДОБАВЛЕНО

    @Column(nullable = false)
    private String status = "UPLOADED";

    // MPP (microns per pixel) — извлекается из OME-метаданных при чтении WSI
    @Column(name = "mpp_x")
    private Double mppX;

    @Column(name = "mpp_y")
    private Double mppY;

    // METADATA / MANUAL / DEFAULT — источник калибровки
    @Column(name = "mpp_source")
    private String mppSource;

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** E5: к какому case относится слайд (NULL — слайд без кейса). */
    @Column(name = "case_id")
    private Integer caseId;

    /** PROXIMAL / MID / DISTAL / OTHER — анатомическая локация биопсии. */
    @Column(name = "biopsy_location")
    private String biopsyLocation;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
