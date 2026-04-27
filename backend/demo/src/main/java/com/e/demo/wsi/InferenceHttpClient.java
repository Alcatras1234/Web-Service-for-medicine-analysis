package com.e.demo.wsi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
public class InferenceHttpClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public InferenceHttpClient(@Value("${inference.url}") String baseUrl) {
        this.url = baseUrl + "/infer_raw";
    }

    /**
     * Результат инференса одного патча.
     *
     * totalCount  — сколько всего эозинофилов нашла модель (включая нахлёст)
     * validCount  — сколько из них в "валидной" зоне патча (без нахлёста)
     *               используется как основной счётчик чтобы не было двойного счёта
     */
    public record InferResult(int totalCount, int validCount) {}

    /**
     * @param tensor      float32 NCHW тензор [3 * W * H]
     * @param patchSize   размер патча в пикселях WSI (448)
     * @param overlapPx   нахлёст в пикселях (64)
     * @param edgeLeft    патч на левом краю слайда
     * @param edgeTop     патч на верхнем краю слайда
     * @param edgeRight   патч на правом краю слайда
     * @param edgeBottom  патч на нижнем краю слайда
     */
    public InferResult infer(float[] tensor,
                             int patchSize, int overlapPx,
                             boolean edgeLeft, boolean edgeTop,
                             boolean edgeRight, boolean edgeBottom) throws Exception {

        // float[] → base64
        ByteBuffer buf = ByteBuffer.allocate(tensor.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : tensor) buf.putFloat(v);
        String b64 = Base64.getEncoder().encodeToString(buf.array());

        String body = mapper.writeValueAsString(Map.of("tensor_base64", b64));

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

        int totalCount = ((Number) result.get("eosinophil_count")).intValue();
        int eos  = ((Number) result.getOrDefault("eos_count",  0)).intValue();
        int eosg = ((Number) result.getOrDefault("eosg_count", 0)).intValue();

        log.info("Patch inference: eos={} eosg={} total={}", eos, eosg, totalCount);

        // Вычисляем validCount — убираем детекции из зон нахлёста
        // Логика: если патч не на краю — его нахлёст с соседом уже посчитан соседом.
        // Упрощённая модель: уменьшаем счётчик пропорционально доле нахлёста.
        // Для точного подсчёта нужны координаты боксов — здесь используем
        // консервативную оценку: делим нахлёст поровну между соседями.
        int validCount = computeValidCount(
                totalCount, patchSize, overlapPx,
                edgeLeft, edgeTop, edgeRight, edgeBottom
        );

        return new InferResult(totalCount, validCount);
    }

    /**
     * Считает "валидный" счётчик: из общего count вычитаем приблизительную
     * долю детекций попавших в зоны нахлёста с соседними патчами.
     *
     * Нахлёст = 64px из 448px = ~14% площади с каждой стороны.
     * Если у патча есть сосед слева — левые 64px "принадлежат" соседу, не нам.
     * Если патч на краю — нахлёста нет, все детекции наши.
     */
    private int computeValidCount(int total, int patchSize, int overlapPx,
                                  boolean edgeLeft, boolean edgeTop,
                                  boolean edgeRight, boolean edgeBottom) {
        if (total == 0) return 0;

        // Площадь всего патча
        double fullArea = (double) patchSize * patchSize;

        // Валидная область: срезаем нахлёст с тех сторон где есть сосед
        int left   = edgeLeft   ? 0 : overlapPx;
        int top    = edgeTop    ? 0 : overlapPx;
        int right  = edgeRight  ? 0 : overlapPx;
        int bottom = edgeBottom ? 0 : overlapPx;

        int validW = patchSize - left - right;
        int validH = patchSize - top  - bottom;

        if (validW <= 0 || validH <= 0) return total; // патч целиком в нахлёсте — берём всё

        double validArea = (double) validW * validH;
        double ratio = validArea / fullArea;

        return (int) Math.round(total * ratio);
    }
}