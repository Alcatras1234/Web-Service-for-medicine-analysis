// InferenceHttpClient.java
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

    public record InferResult(int totalCount, int validCount, int validEos, int validEosg) {}

    private final HttpClient   http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String       url;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.url = baseUrl + "/infer_raw";
    }

    public InferResult infer(
            float[] tensor,
            int patchWsiSize, int overlapPx,
            boolean edgeLeft, boolean edgeTop,
            boolean edgeRight, boolean edgeBottom
    ) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(tensor.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : tensor) buf.putFloat(v);
        String b64 = Base64.getEncoder().encodeToString(buf.array());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tensor_base64",  b64);
        json.put("patch_wsi_size", patchWsiSize);
        json.put("overlap_px",     overlapPx);
        json.put("edge_left",      edgeLeft);
        json.put("edge_top",       edgeTop);
        json.put("edge_right",     edgeRight);
        json.put("edge_bottom",    edgeBottom);

        String body = mapper.writeValueAsString(json);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Inference HTTP " + resp.statusCode() + ": " + resp.body());
        }

        Map<String, Object> result = mapper.readValue(resp.body(),
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        return new InferResult(
                ((Number) result.getOrDefault("total_count", 0)).intValue(),
                ((Number) result.getOrDefault("valid_count", 0)).intValue(),
                ((Number) result.getOrDefault("valid_eos",   0)).intValue(),
                ((Number) result.getOrDefault("valid_eosg",  0)).intValue());
    }
}