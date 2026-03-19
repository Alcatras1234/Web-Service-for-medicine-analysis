package com.e.demo.Controllers;

import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.server.MinioService;
import com.e.demo.server.QueuePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

  private final MinioService minioService;
  private final QueuePublisher publisher;

  @Value("${minio.bucketName}")
  private String bucket;

  public FileController(MinioService minioService, QueuePublisher publisher) {
    this.minioService = minioService;
    this.publisher = publisher;
  }

  @GetMapping("/get-upload-link")
  public Map<String, String> getLink(@RequestParam String filename) {
    String objectKey = UUID.randomUUID() + "_" + filename;
    String url = minioService.generateUploadLink(bucket, objectKey);
    return Map.of("uploadUrl", url, "objectKey", objectKey);
  }


  
}
