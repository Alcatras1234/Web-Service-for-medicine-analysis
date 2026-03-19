package com.e.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "slides")
@Data
public class Slide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "s3_path", nullable = false)
    private String s3Path;

    @Column(nullable = false)
    private String status = "UPLOADED";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
