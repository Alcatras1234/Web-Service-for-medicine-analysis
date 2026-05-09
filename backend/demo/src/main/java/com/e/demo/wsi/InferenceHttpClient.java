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
        String  patchId,
        int     totalCount,
        int     validCount,
        int     validEos,
        int     validEosg,
        List<Map<String, Object>> detections
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

    /** E3: элемент батч-запроса. */
    public record PatchItemDto(
        String  patch_id,
        String  tensor_base64,
        int     patch_wsi_size,
        int     overlap_px,
        boolean edge_left,
        boolean edge_top,
        boolean edge_right,
        boolean edge_bottom
    ) {}

    public record BatchRequestDto(List<PatchItemDto> patches) {}

    /** E6: ответ батч-инференса вместе с версией модели. */
    public record BatchResponse(String modelVersion, List<InferResult> results) {}

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

    /** Старый одиночный инференс — оставляем для совместимости / отладки. */
    public InferResult infer(
        byte[] rgbHwc,
        int patchWsiSize,
        int overlapPx,
        boolean edgeLeft,
        boolean edgeTop,
        boolean edgeRight,
        boolean edgeBottom
    ) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(rgbHwc);
        TensorRequestDto payload = new TensorRequestDto(
            b64, patchWsiSize, overlapPx,
            edgeLeft, edgeTop, edgeRight, edgeBottom
        );
        String body = mapper.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/infer_raw"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Inference HTTP " + resp.statusCode() + ": " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result =
            (Map<String, Object>) mapper.readValue(resp.body(), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dets =
            (List<Map<String, Object>>) result.getOrDefault("detections", List.of());
        return new InferResult(
            (String) result.getOrDefault("patch_id", "single"),
            ((Number) result.getOrDefault("total_count", 0)).intValue(),
            ((Number) result.getOrDefault("valid_count", 0)).intValue(),
            ((Number) result.getOrDefault("valid_eos",   0)).intValue(),
            ((Number) result.getOrDefault("valid_eosg",  0)).intValue(),
            dets
        );
    }

    /**
     * E3: батчевый инференс. Один HTTP вместо N — минус overhead, минус Base64-разогрев.
     */
    public BatchResponse inferBatch(List<PatchItemDto> patches) throws Exception {
        BatchRequestDto payload = new BatchRequestDto(patches);
        String body = mapper.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/infer_batch"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("BatchInference HTTP " + resp.statusCode() + ": " + resp.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) mapper.readValue(resp.body(), Map.class);
        String modelVersion = String.valueOf(root.getOrDefault("model_version", "unknown"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> arr = (List<Map<String, Object>>) root.getOrDefault("results", List.of());

        List<InferResult> out = new ArrayList<>(arr.size());
        for (Map<String, Object> r : arr) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dets =
                (List<Map<String, Object>>) r.getOrDefault("detections", List.of());
            out.add(new InferResult(
                String.valueOf(r.getOrDefault("patch_id", "")),
                ((Number) r.getOrDefault("total_count", 0)).intValue(),
                ((Number) r.getOrDefault("valid_count", 0)).intValue(),
                ((Number) r.getOrDefault("valid_eos",   0)).intValue(),
                ((Number) r.getOrDefault("valid_eosg",  0)).intValue(),
                dets
            ));
        }
        return new BatchResponse(modelVersion, out);
    }
}
