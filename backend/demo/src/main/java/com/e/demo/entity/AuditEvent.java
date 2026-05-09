package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * E6: запись журнала аудита. Любое значимое действие пользователя/системы
 * (загрузка, удаление, подпись, ручная корректировка MPP) пишется сюда.
 */
@Entity
@Table(name = "audit_events")
@Data
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false)
    private String action;          // UPLOAD / DELETE_SLIDE / DELETE_CASE / SIGNOFF / ...

    @Column(name = "entity_type", nullable = false)
    private String entityType;      // SLIDE / CASE / JOB

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
