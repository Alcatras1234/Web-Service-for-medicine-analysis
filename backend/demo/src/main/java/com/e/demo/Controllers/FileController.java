package com.e.demo.Controllers;

import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.entity.Case;
import com.e.demo.entity.Job;
import com.e.demo.entity.Slide;
import com.e.demo.repository.CaseRepository;
import com.e.demo.repository.CaseSignoffRepository;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.SlideRepository;
import com.e.demo.server.AuditService;
import com.e.demo.server.MinioService;
import com.e.demo.server.QueuePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final MinioService minioService;
    private final QueuePublisher publisher;
    private final SlideRepository slideRepository;
    private final JobRepository jobRepository;
    private final CaseRepository caseRepository;
    private final CaseSignoffRepository signoffRepository;
    private final AuditService audit;

    @Value("${minio.bucketName}")
    private String bucket;

    public FileController(MinioService minioService,
                          QueuePublisher publisher,
                          SlideRepository slideRepository,
                          JobRepository jobRepository,
                          CaseRepository caseRepository,
                          CaseSignoffRepository signoffRepository,
                          AuditService audit) {
        this.minioService = minioService;
        this.publisher = publisher;
        this.slideRepository = slideRepository;
        this.jobRepository = jobRepository;
        this.caseRepository = caseRepository;
        this.signoffRepository = signoffRepository;
        this.audit = audit;
    }

    private Integer currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("Not authenticated");
        }
        return (Integer) auth.getPrincipal();
    }

    // Шаг 1: получить presigned URL для загрузки в MinIO
    @GetMapping("/get-upload-link")
    public Map<String, String> getLink(@RequestParam String filename) {
        currentUserId(); // проверка аутентификации
        String objectKey = UUID.randomUUID() + "_" + filename;
        String url = minioService.generateUploadLink(bucket, objectKey);
        return Map.of("uploadUrl", url, "objectKey", objectKey);
    }

    // Шаг 2: подтвердить загрузку и запустить обработку
    @PostMapping("/confirm-upload")
    public ResponseEntity<Map<String, Object>> confirmUpload(
            @RequestBody Map<String, String> body) {

        Integer userId = currentUserId();
        String objectKey   = body.get("objectKey");
        String filename    = body.get("filename");
        String patientId   = body.get("patientId");
        String description = body.get("description");
        // E5: новые опциональные поля
        String biopsyLocation = body.get("biopsyLocation");
        String caseIdRaw     = body.get("caseId");
        Integer caseId = (caseIdRaw == null || caseIdRaw.isBlank()) ? null : Integer.parseInt(caseIdRaw);

        // Валидация case: должен принадлежать пользователю и быть открытым
        if (caseId != null) {
            Case c = caseRepository.findActiveById(caseId).orElse(null);
            if (c == null || !c.getUserId().equals(userId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Case not found"));
            }
            if ("SIGNED_OFF".equals(c.getStatus())) {
                return ResponseEntity.status(409).body(Map.of("error", "Cannot add slides to signed-off case"));
            }
        }

        Slide slide = new Slide();
        slide.setUserId(userId);
        slide.setFilename(filename);
        slide.setS3Path(objectKey);
        slide.setPatientId(patientId);
        slide.setDescription(description);
        slide.setCaseId(caseId);
        slide.setBiopsyLocation(biopsyLocation);
        slide.setStatus("UPLOADED");
        slideRepository.save(slide);

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setSlideId(slide.getId());
        job.setStatus("PENDING");
        job.setPhase("QUEUED");
        jobRepository.save(job);

        publisher.publishWsiUploaded(
                new WsiUploadedEvent(job.getId(), objectKey));

        audit.log(userId, "UPLOAD", "SLIDE", slide.getId(),
            Map.of(
                "filename", String.valueOf(filename),
                "patientId", String.valueOf(patientId),
                "caseId", String.valueOf(caseId),
                "biopsyLocation", String.valueOf(biopsyLocation)
            ));

        return ResponseEntity.accepted().body(Map.of(
                "slideId", slide.getId(),
                "jobId", job.getId()
        ));
    }

    /** E8: soft-delete слайда. 409 если кейс уже подписан. */
    @DeleteMapping("/slides/{id}")
    public ResponseEntity<?> deleteSlide(@PathVariable Integer id) {
        Integer userId = currentUserId();
        Slide slide = slideRepository.findActiveById(id).orElse(null);
        if (slide == null || !slide.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        if (slide.getCaseId() != null && signoffRepository.existsByCaseId(slide.getCaseId())) {
            return ResponseEntity.status(409)
                .body(Map.of("error", "Cannot delete slide from signed-off case"));
        }
        slideRepository.softDelete(id, Instant.now());
        audit.log(userId, "DELETE_SLIDE", "SLIDE", id, null);
        return ResponseEntity.noContent().build();
    }

    // Список слайдов текущего пользователя с актуальным статусом и jobId
    @GetMapping("/slides")
    public ResponseEntity<List<Map<String, Object>>> getSlides() {
        Integer userId = currentUserId();

        List<Slide> slides = slideRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> result = slides.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("filename", s.getFilename());
            m.put("patientId", s.getPatientId());
            m.put("description", s.getDescription());
            m.put("caseId", s.getCaseId());
            m.put("biopsyLocation", s.getBiopsyLocation());
            m.put("mppX", s.getMppX());
            m.put("mppSource", s.getMppSource());
            m.put("createdAt", s.getCreatedAt());

            // Тянем последний job этого slide и берём из него статус и диагностику
            Job job = jobRepository.findFirstBySlideIdOrderByCreatedAtDesc(s.getId())
                    .orElse(null);
            if (job != null) {
                m.put("jobId", job.getId());
                m.put("status", job.getStatus());            // PENDING / PROCESSING / FINALIZING / DONE / DONE_WITH_ERRORS / FAILED
                m.put("diagnosis", job.getDiagnosis());
                m.put("totalEosinophils", job.getTotalEosinophilCount());
                m.put("maxHpfCount", job.getMaxHpfCount());
                m.put("reportReady", job.getReportPath() != null);
            } else {
                m.put("jobId", null);
                m.put("status", s.getStatus());
                m.put("diagnosis", null);
                m.put("totalEosinophils", 0);
                m.put("maxHpfCount", 0);
                m.put("reportReady", false);
            }
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }
}