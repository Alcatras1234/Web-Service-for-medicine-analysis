package com.e.demo.repository;

import com.e.demo.entity.Slide;

import jakarta.transaction.Transactional;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlideRepository extends JpaRepository<Slide, Integer> {

    // Слайды конкретного пользователя, новые сверху
    List<Slide> findByUserIdOrderByCreatedAtDesc(Integer userId);
    
    @Modifying
    @Transactional
    @Query("UPDATE Slide s SET s.status = :status WHERE s.id = :id")
    int updateStatus(@Param("id") Integer id, @Param("status") String status);
}
