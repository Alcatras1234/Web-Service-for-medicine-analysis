package com.e.demo.Controllers;

import com.e.demo.entity.Job;
import com.e.demo.repository.JobRepository;
import com.e.demo.server.ReportService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@Slf4j
public class ReportController {

    private final JobRepository jobRepository;
    private final ReportService reportService;
    private final MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucket;

    public ReportController(JobRepository jobRepository,
                            ReportService reportService,
                            @Qualifier("internalClient") MinioClient minioClient) {
        this.jobRepository = jobRepository;
        this.reportService = reportService;
        this.minioClient = minioClient;
    }

    // GET /api/reports/{jobId}/status — статус джоба + диагноз
    @GetMapping("/{jobId}/status")
    public ResponseEntity<?> getStatus(@PathVariable UUID jobId) {
        return jobRepository.findById(jobId)
                .map(job -> ResponseEntity.ok(Map.of(
                        "jobId",              job.getId(),
                        "status",             job.getStatus(),
                        "diagnosis",          job.getDiagnosis() != null ? job.getDiagnosis() : "PENDING",
                        "totalEosinophils",   job.getTotalEosinophilCount() != null ? job.getTotalEosinophilCount() : 0,
                        "maxHpfCount",        job.getMaxHpfCount() != null ? job.getMaxHpfCount() : 0,
                        "maxHpfX",            job.getMaxHpfX() != null ? job.getMaxHpfX() : 0,
                        "maxHpfY",            job.getMaxHpfY() != null ? job.getMaxHpfY() : 0,
                        "reportReady",        job.getReportPath() != null
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/reports/{jobId}/pdf — скачать PDF
    @GetMapping("/{jobId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getReportPath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(("Report not ready yet").getBytes());
        }
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(job.getReportPath())
                        .build())) {
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"report-" + jobId + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        } catch (Exception e) {
            log.error("Failed to download PDF: job={}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/reports/{jobId}/heatmap — скачать heatmap PNG
    @GetMapping("/{jobId}/heatmap")
    public ResponseEntity<byte[]> downloadHeatmap(@PathVariable UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getHeatmapPath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(job.getHeatmapPath())
                        .build())) {
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"heatmap-" + jobId + ".png\"")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(bytes);
        } catch (Exception e) {
            log.error("Failed to download heatmap: job={}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // POST /api/reports/{jobId}/regenerate — перегенерировать отчёт вручную
    @PostMapping("/{jobId}/regenerate")
    public ResponseEntity<?> regenerate(@PathVariable UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            return ResponseEntity.notFound().build();
        }
        reportService.generateAsync(jobId);
        return ResponseEntity.accepted()
                .body(Map.of("message", "Report generation started"));
    }
}