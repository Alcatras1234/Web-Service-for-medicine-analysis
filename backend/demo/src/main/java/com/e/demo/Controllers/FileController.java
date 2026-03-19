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
import org.springframework.web.bind.annotation.*;

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

        String objectKey = body.get("objectKey");
        String filename  = body.get("filename");

        // TODO: заменить на userId из JWT когда добавишь Security
        Integer userId = 1;

        // Записываем слайд в БД
        Slide slide = new Slide();
        slide.setUserId(userId);
        slide.setFilename(filename);
        slide.setS3Path(objectKey);
        slide.setStatus("UPLOADED");
        slideRepository.save(slide);

        // Создаём job
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setSlideId(slide.getId());
        job.setStatus("PENDING");
        jobRepository.save(job);

        // Публикуем событие → TilingWorker стартует автоматически
        publisher.publishWsiUploaded(
                new WsiUploadedEvent(job.getId(), objectKey));

        return ResponseEntity.accepted().body(Map.of(
                "slideId", slide.getId(),
                "jobId",   job.getId()
        ));
    }
}
