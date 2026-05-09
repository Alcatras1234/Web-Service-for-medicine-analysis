package com.e.demo.repository;

import com.e.demo.entity.Case;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CaseRepository extends JpaRepository<Case, Integer> {

    /** Только активные (не удалённые) кейсы пользователя. */
    @Query("SELECT c FROM Case c WHERE c.userId = :uid AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Case> findActiveByUser(@Param("uid") Integer userId);

    @Query("SELECT c FROM Case c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Case> findActiveById(@Param("id") Integer id);

    @Modifying
    @Transactional
    @Query("UPDATE Case c SET c.deletedAt = :now WHERE c.id = :id AND c.deletedAt IS NULL")
    int softDelete(@Param("id") Integer id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE Case c SET c.status = :status, c.diagnosis = :diagnosis, c.updatedAt = :now WHERE c.id = :id")
    int updateStatus(@Param("id") Integer id,
                     @Param("status") String status,
                     @Param("diagnosis") String diagnosis,
                     @Param("now") Instant now);
}
