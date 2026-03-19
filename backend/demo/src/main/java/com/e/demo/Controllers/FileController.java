package com.e.demo.Controllers;

import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.entity.Job;
import com.e.demo.entity.Slide;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.SlideRepository;
import com.e.demo.server.MinioService;
import com.e.demo.server.QueuePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @Value("${minio.bucketName}")
    private String bucket;

    public FileController(MinioService minioService,
                          QueuePublisher publisher,
                          SlideRepository slideRepository,
                          JobRepository jobRepository) {
        this.minioService = minioService;
        this.publisher = publisher;
        this.slideRepository = slideRepository;
        this.jobRepository = jobRepository;
    }

    // Шаг 1: Фронт запрашивает presigned URL
    @GetMapping("/get-upload-link")
    public Map<String, String> getLink(@RequestParam String filename) {
        String objectKey = UUID.randomUUID() + "_" + filename;
        String url = minioService.generateUploadLink(bucket, objectKey);
        return Map.of("uploadUrl", url, "objectKey", objectKey);
    }

    // Шаг 2: Фронт вызывает после того как залил файл в MinIO
    @PostMapping("/confirm-upload")
        public ResponseEntity<Map<String, Object>> confirmUpload(
                @RequestBody Map<String, String> body) {

            String objectKey  = body.get("objectKey");
            String filename   = body.get("filename");
            String patientId  = body.get("patientId");    // ← ДОБАВЛЕНО
            String description = body.get("description"); // ← ДОБАВЛЕНО
            Integer userId = (Integer)
            SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // TODO: из JWT

            Slide slide = new Slide();
            slide.setUserId(userId);
            slide.setFilename(filename);
            slide.setS3Path(objectKey);
            slide.setPatientId(patientId);       // ← ДОБАВЛЕНО
            slide.setDescription(description);   // ← ДОБАВЛЕНО
            slide.setStatus("UPLOADED");
            slideRepository.save(slide);

            Job job = new Job();
            job.setId(UUID.randomUUID());
            job.setSlideId(slide.getId());
            job.setStatus("PENDING");
            jobRepository.save(job);

            publisher.publishWsiUploaded(
                    new WsiUploadedEvent(job.getId(), objectKey));

            return ResponseEntity.accepted().body(Map.of(
                    "slideId", slide.getId(),
                    "jobId",   job.getId()
            ));
        }


    @GetMapping("/slides")
    public ResponseEntity<List<Map<String, Object>>> getSlides() {
        return ResponseEntity.ok(
            slideRepository.findAll().stream().map(s -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("filename", s.getFilename());
                m.put("patientId", s.getPatientId());
                m.put("description", s.getDescription());
                m.put("status", s.getStatus());
                m.put("createdAt", s.getCreatedAt());
                return m;
            }).toList()
        );
}
}
