package com.e.demo.repository;

import com.e.demo.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Modifying
    @Transactional
    @Query("""
        UPDATE Job j
        SET j.patchesTotal = :total,
            j.patchesRemaining = :total,
            j.status = 'PROCESSING',
            j.updatedAt = :now
        WHERE j.id = :jobId
    """)
    void updatePatchCount(@Param("jobId") UUID jobId,
                          @Param("total") int total,
                          @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE Job j
        SET j.status = :status,
            j.totalEosinophilCount = :totalCount,
            j.maxHpfCount = :maxHpfCount,
            j.maxHpfX = :maxHpfX,
            j.maxHpfY = :maxHpfY,
            j.diagnosis = :diagnosis,
            j.updatedAt = :now
        WHERE j.id = :jobId
    """)
    void updateInferenceResult(@Param("jobId") UUID jobId,
                               @Param("status") String status,
                               @Param("totalCount") int totalCount,
                               @Param("maxHpfCount") int maxHpfCount,
                               @Param("maxHpfX") int maxHpfX,
                               @Param("maxHpfY") int maxHpfY,
                               @Param("diagnosis") String diagnosis,
                               @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE Job j
        SET j.reportPath = :reportPath,
            j.heatmapPath = :heatmapPath,
            j.updatedAt = :now
        WHERE j.id = :jobId
    """)
    void updateReportPaths(@Param("jobId") UUID jobId,
                           @Param("reportPath") String reportPath,
                           @Param("heatmapPath") String heatmapPath,
                           @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE Job j SET j.status = :newStatus, j.updatedAt = :now
        WHERE j.id = :jobId AND j.status = :expectedStatus
        """)
    int tryFinalizeJob(@Param("jobId") UUID jobId,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("newStatus") String newStatus,
                    @Param("now") Instant now);
}