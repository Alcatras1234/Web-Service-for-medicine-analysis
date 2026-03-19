package com.e.demo.dto;

public record PatchInferenceEvent(
    String wsiId,
    String patchId,
    String bucket,
    String patchObjectKey,
    int x, int y, int w, int h
) {}
