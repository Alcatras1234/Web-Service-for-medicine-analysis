package com.e.demo.wsi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.*;

@Component
public class InferenceHttpClient {

    public record InferResult(
            int totalCount,
            int validCount,
            int validEos,
            int validEosg
    ) {}

    public record BatchPatchRequest(
            UUID      patchId,
            float[]   tensor,
            int       patchWsiSize,
            int       overlapPx,
            boolean   edgeLeft,
            boolean   edgeTop,
            boolean   edgeRight,
            boolean   edgeBottom
    ) {}

    private final HttpClient   http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String       baseUrl;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Одиночный патч (для тестов / совместимости) ───────────────────────────
    public InferResult infer(
            float[] tensor,
            int patchWsiSize, int overlapPx,
            boolean edgeLeft, boolean edgeTop,
            boolean edgeRight, boolean edgeBottom
    ) throws Exception {
        String b64  = encodeToBase64(tensor);
        String body = mapper.writeValueAsString(Map.of(
                "tensor_base64",  b64,
                "patch_wsi_size", patchWsiSize,
                "overlap_px",     overlapPx,
                "edge_left",      edgeLeft,
                "edge_top",       edgeTop,
                "edge_right",     edgeRight,
                "edge_bottom",    edgeBottom
        ));

        Map<String, Object> result = post(baseUrl + "/infer_raw", body);
        return mapToInferResult(result);
    }

    // ── Батч патчей — один HTTP-запрос, один GPU-прогон ───────────────────────
    public List<Map.Entry<UUID, InferResult>> inferBatch(
            List<BatchPatchRequest> patches
    ) throws Exception {
        List<Map<String, Object>> patchList = new ArrayList<>();
        for (BatchPatchRequest p : patches) {
            patchList.add(Map.of(
                    "patch_id",      p.patchId().toString(),
                    "tensor_base64", encodeToBase64(p.tensor()),
                    "patch_wsi_size", p.patchWsiSize(),
                    "overlap_px",    p.overlapPx(),
                    "edge_left",     p.edgeLeft(),
                    "edge_top",      p.edgeTop(),
                    "edge_right",    p.edgeRight(),
                    "edge_bottom",   p.edgeBottom()
            ));
        }

        String body = mapper.writeValueAsString(Map.of("patches", patchList));
        Map<String, Object> root = post(baseUrl + "/infer_batch", body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) root.get("results");

        List<Map.Entry<UUID, InferResult>> out = new ArrayList<>();
        for (Map<String, Object> r : results) {
            UUID patchId = UUID.fromString((String) r.get("patch_id"));
            out.add(Map.entry(patchId, mapToInferResult(r)));
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String encodeToBase64(float[] tensor) {
        ByteBuffer buf = ByteBuffer.allocate(tensor.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : tensor) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private Map<String, Object> post(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                "Inference HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readValue(resp.body(), mapper.getTypeFactory()
                .constructMapType(Map.class, String.class, Object.class));
    }

    private InferResult mapToInferResult(Map<String, Object> r) {
        return new InferResult(
                ((Number) r.getOrDefault("total_count", 0)).intValue(),
                ((Number) r.getOrDefault("valid_count", 0)).intValue(),
                ((Number) r.getOrDefault("valid_eos",   0)).intValue(),
                ((Number) r.getOrDefault("valid_eosg",  0)).intValue()
        );
    }
}