package com.e.demo.repository;

import com.e.demo.entity.PatchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface PatchTaskRepository extends JpaRepository<PatchTask, UUID> {

    long countByJobIdAndStatus(UUID jobId, String status);

    // Сумма всех эозинофилов по джобу
    @Query("SELECT COALESCE(SUM(p.eosinophilCount), 0) FROM PatchTask p WHERE p.jobId = :jobId")
    int sumEosinophilCountByJobId(@Param("jobId") UUID jobId);

    // Патч с максимальным кол-вом клеток (для PDF отчёта)
    PatchTask findTopByJobIdOrderByEosinophilCountDesc(UUID jobId);

    // Все патчи джоба для heatmap
    List<PatchTask> findAllByJobId(UUID jobId);
}