package com.e.demo.repository;

import com.e.demo.entity.Slide;

import jakarta.transaction.Transactional;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlideRepository extends JpaRepository<Slide, Integer> {

    // Слайды конкретного пользователя, новые сверху (только активные)
    @Query("SELECT s FROM Slide s WHERE s.userId = :uid AND s.deletedAt IS NULL ORDER BY s.createdAt DESC")
    List<Slide> findByUserIdOrderByCreatedAtDesc(@Param("uid") Integer userId);

    @Query("SELECT s FROM Slide s WHERE s.id = :id AND s.deletedAt IS NULL")
    java.util.Optional<Slide> findActiveById(@Param("id") Integer id);

    @Query("SELECT s FROM Slide s WHERE s.caseId = :caseId AND s.deletedAt IS NULL ORDER BY s.createdAt ASC")
    List<Slide> findActiveByCaseId(@Param("caseId") Integer caseId);

    @Modifying
    @Transactional
    @Query("UPDATE Slide s SET s.status = :status WHERE s.id = :id")
    int updateStatus(@Param("id") Integer id, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("UPDATE Slide s SET s.deletedAt = :now WHERE s.id = :id AND s.deletedAt IS NULL")
    int softDelete(@Param("id") Integer id, @Param("now") java.time.Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE Slide s SET s.caseId = :caseId, s.biopsyLocation = :loc WHERE s.id = :id")
    int assignToCase(@Param("id") Integer id,
                     @Param("caseId") Integer caseId,
                     @Param("loc") String biopsyLocation);

    @Modifying
    @Transactional
    @Query("""
        UPDATE Slide s
        SET s.mppX = :mppX, s.mppY = :mppY, s.mppSource = :source,
            s.widthPx = :widthPx, s.heightPx = :heightPx
        WHERE s.id = :id
    """)
    int updateCalibration(@Param("id") Integer id,
                          @Param("mppX") Double mppX,
                          @Param("mppY") Double mppY,
                          @Param("source") String source,
                          @Param("widthPx") Integer widthPx,
                          @Param("heightPx") Integer heightPx);
}
