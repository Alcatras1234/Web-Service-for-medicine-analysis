package com.e.demo.wsi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class InferenceHttpClient {

    public record InferResult(
        int totalCount,
        int validCount,
        int validEos,
        int validEosg
    ) {}

    public record TensorRequestDto(
        String  tensor_base64,
        int     patch_wsi_size,
        int     overlap_px,
        boolean edge_left,
        boolean edge_top,
        boolean edge_right,
        boolean edge_bottom
    ) {}

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // Принимаем byte[] (RGB HWC uint8)
    public InferResult infer(
        byte[] rgbHwc,
        int patchWsiSize,
        int overlapPx,
        boolean edgeLeft,
        boolean edgeTop,
        boolean edgeRight,
        boolean edgeBottom
    ) throws Exception {
        // Кодируем байты напрямую в base64
        String b64 = Base64.getEncoder().encodeToString(rgbHwc);

        TensorRequestDto payload = new TensorRequestDto(
            b64, patchWsiSize, overlapPx,
            edgeLeft, edgeTop, edgeRight, edgeBottom
        );
        String body = mapper.writeValueAsString(payload);
        log.debug("POST /infer_raw body preview: {}",
            body.length() > 120 ? body.substring(0, 120) + "..." : body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/infer_raw"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                "Inference HTTP " + resp.statusCode() + ": " + resp.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
            (Map<String, Object>) mapper.readValue(resp.body(), Map.class);

        return new InferResult(
            ((Number) result.getOrDefault("total_count", 0)).intValue(),
            ((Number) result.getOrDefault("valid_count", 0)).intValue(),
            ((Number) result.getOrDefault("valid_eos",   0)).intValue(),
            ((Number) result.getOrDefault("valid_eosg",  0)).intValue()
        );
    }
}