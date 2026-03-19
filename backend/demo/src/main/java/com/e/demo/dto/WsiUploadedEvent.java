package com.e.demo.dto;

public record WsiUploadedEvent(
    String wsiId,
    String bucket,
    String objectKey,
    int patchSize,
    int overlap
) {}
