package com.e.demo.dto;

import java.util.UUID;

public record WsiUploadedEvent(
        UUID jobId,
        String s3Path
) {}
