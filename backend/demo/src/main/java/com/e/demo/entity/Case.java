package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * E5: клинический случай. Группа слайдов одного пациента (биопсии разных
 * отделов пищевода/желудка) с единым диагнозом.
 */
@Entity
@Table(name = "cases")
@Data
public class Case {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    @Column
    private String name;             // отображаемое название кейса

    @Column
    private String description;

    /** OPEN / SIGNED_OFF — у подписанного кейса нельзя удалять слайды. */
    @Column(nullable = false)
    private String status = "OPEN";

    /** Итоговый диагноз кейса (агрегированный по слайдам). */
    @Column
    private String diagnosis;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
