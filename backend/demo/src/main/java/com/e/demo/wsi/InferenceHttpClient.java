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

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.url = baseUrl + "/infer_raw";
    }

    public int infer(float[] imageTensor) throws Exception {
        // float[] → base64
        ByteBuffer buf = ByteBuffer.allocate(imageTensor.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : imageTensor) buf.putFloat(v);
        String b64 = Base64.getEncoder().encodeToString(buf.array());

        String body = mapper.writeValueAsString(Map.of("tensor_base64", b64));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        // Проверяем статус ДО парсинга, чтобы не получить JsonParseException на "Internal Server Error"
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    "Inference service returned HTTP " + resp.statusCode() + ": " + resp.body()
            );
        }

        // Python возвращает уже готовый count (NMS выполнен на стороне YOLO)
        Map<String, Object> result = mapper.readValue(resp.body(), Map.class);
        return ((Number) result.get("eosinophil_count")).intValue();
    }
}