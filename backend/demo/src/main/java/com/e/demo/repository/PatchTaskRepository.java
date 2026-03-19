package com.e.demo.repository;

import com.e.demo.entity.PatchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PatchTaskRepository extends JpaRepository<PatchTask, UUID> {}
