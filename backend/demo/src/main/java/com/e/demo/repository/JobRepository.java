package com.e.demo.repository;

import com.e.demo.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Modifying
    @Transactional
    @Query("""
        UPDATE Job j
        SET j.patchesTotal = :total,
            j.patchesRemaining = :total,
            j.status = 'PROCESSING',
            j.updatedAt = now()
        WHERE j.id = :jobId
    """)
    void updatePatchCount(UUID jobId, int total);
}
