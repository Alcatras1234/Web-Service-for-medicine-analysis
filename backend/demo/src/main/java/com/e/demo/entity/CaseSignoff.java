package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * E6/E8: подписанный (sign-off) результат кейса. После подписи кейс становится
 * финальным — слайды нельзя удалять, диагноз нельзя менять.
 */
@Entity
@Table(name = "case_signoffs")
@Data
public class CaseSignoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "case_id", nullable = false)
    private Integer caseId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false)
    private String diagnosis;

    /** JSON-массив версий моделей по слайдам, как строка. */
    @Column(name = "model_versions", columnDefinition = "TEXT")
    private String modelVersions;

    @Column(columnDefinition = "TEXT")
    private String comments;

    @Column(name = "signed_at")
    private Instant signedAt = Instant.now();
}
