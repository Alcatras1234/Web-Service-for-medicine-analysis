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
import java.util.Base64;
import java.util.Map;

@Component
public class InferenceHttpClient {

    // ── Result record ─────────────────────────────────────────────────────────
    public record InferResult(
            int totalCount,
            int validCount,
            int validEos,
            int validEosg
    ) {}

    // ── Fields ────────────────────────────────────────────────────────────────
    private final HttpClient   http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String       url;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.url = baseUrl + "/infer_raw";
    }

    // ── Simple infer (count only) ─────────────────────────────────────────────
    public int infer(float[] imageTensor) throws Exception {
        return infer(imageTensor, 448, 64, false, false, false, false).validCount();
    }

    // ── Full infer with overlap zone ──────────────────────────────────────────
    public InferResult infer(
            float[] imageTensor,
            int     patchWsiSize,
            int     overlapPx,
            boolean edgeLeft,
            boolean edgeTop,
            boolean edgeRight,
            boolean edgeBottom
    ) throws Exception {

        // float[] NCHW → base64 little-endian
        ByteBuffer buf = ByteBuffer.allocate(imageTensor.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : imageTensor) buf.putFloat(v);
        String b64 = Base64.getEncoder().encodeToString(buf.array());

        String body = mapper.writeValueAsString(Map.of(
                "tensor_base64",  b64,
                "patch_wsi_size", patchWsiSize,
                "overlap_px",     overlapPx,
                "edge_left",      edgeLeft,
                "edge_top",       edgeTop,
                "edge_right",     edgeRight,
                "edge_bottom",    edgeBottom
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                "Inference service returned HTTP " + resp.statusCode() + ": " + resp.body()
            );
        }

        Map<String, Object> result = mapper.readValue(resp.body(), Map.class);

        int totalCount = ((Number) result.get("total_count")).intValue();
        int validCount = ((Number) result.get("valid_count")).intValue();
        int validEos   = ((Number) result.getOrDefault("valid_eos",  0)).intValue();
        int validEosg  = ((Number) result.getOrDefault("valid_eosg", 0)).intValue();

        return new InferResult(totalCount, validCount, validEos, validEosg);
    }
}