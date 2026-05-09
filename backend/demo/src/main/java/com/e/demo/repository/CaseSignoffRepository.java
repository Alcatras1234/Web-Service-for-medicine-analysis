package com.e.demo.repository;

import com.e.demo.entity.CaseSignoff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseSignoffRepository extends JpaRepository<CaseSignoff, Integer> {
    List<CaseSignoff> findByCaseIdOrderBySignedAtDesc(Integer caseId);
    Optional<CaseSignoff> findFirstByCaseIdOrderBySignedAtDesc(Integer caseId);
    boolean existsByCaseId(Integer caseId);
}
