package com.e.demo.dto;

import java.util.UUID;

public record PatchInferenceEvent(
        UUID jobId,
        UUID patchId,
        String s3Path,
        int x,
        int y,
        int width,
        int height
) {}
