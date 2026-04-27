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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class InferenceHttpClient {

    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int NUM_CLASSES = 2;
    private static final int NUM_ANCHORS = 4116;

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

        // Python возвращает output0 как base64 float[]
        Map<?, ?> result = mapper.readValue(resp.body(), Map.class);
        String rawB64 = (String) result.get("output0_base64");
        byte[] rawBytes = Base64.getDecoder().decode(rawB64);

        // Парсим точно так же как в TritonGrpcClient
        float[] output0 = bytesToFloats(rawBytes);
        return parseAndCount(output0);
    }

    private int parseAndCount(float[] output0) {
        // layout: [ROW_SIZE=116][NUM_ANCHORS=4116]
        float[][] boxes  = new float[NUM_ANCHORS][4];
        float[][] scores = new float[NUM_ANCHORS][NUM_CLASSES];

        for (int i = 0; i < NUM_ANCHORS; i++) {
            boxes[i][0] = output0[0 * NUM_ANCHORS + i]; // cx
            boxes[i][1] = output0[1 * NUM_ANCHORS + i]; // cy
            boxes[i][2] = output0[2 * NUM_ANCHORS + i]; // w
            boxes[i][3] = output0[3 * NUM_ANCHORS + i]; // h
            for (int c = 0; c < NUM_CLASSES; c++) {
                scores[i][c] = output0[(4 + c) * NUM_ANCHORS + i];
            }
        }

        List<float[]> detections = new ArrayList<>();
        for (int i = 0; i < NUM_ANCHORS; i++) {
            int bestClass = 0;
            float bestScore = scores[i][0];
            for (int c = 1; c < NUM_CLASSES; c++) {
                if (scores[i][c] > bestScore) {
                    bestScore = scores[i][c];
                    bestClass = c;
                }
            }
            if (bestScore >= CONF_THRESHOLD) {
                float cx = boxes[i][0], cy = boxes[i][1];
                float w  = boxes[i][2], h  = boxes[i][3];
                detections.add(new float[]{
                        cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2,
                        bestScore, bestClass
                });
            }
        }

        List<float[]> kept = nms(detections, IOU_THRESHOLD);
        // Считаем оба класса (eos=0 и eosg=1 — оба эозинофилы)
        return kept.size();
    }

    private List<float[]> nms(List<float[]> dets, float iouThresh) {
        dets.sort((a, b) -> Float.compare(b[4], a[4]));
        List<float[]> result = new ArrayList<>();
        boolean[] suppressed = new boolean[dets.size()];
        for (int i = 0; i < dets.size(); i++) {
            if (suppressed[i]) continue;
            result.add(dets.get(i));
            for (int j = i + 1; j < dets.size(); j++) {
                if (!suppressed[j] && iou(dets.get(i), dets.get(j)) > iouThresh)
                    suppressed[j] = true;
            }
        }
        return result;
    }

    private float iou(float[] a, float[] b) {
        float ix1 = Math.max(a[0], b[0]), iy1 = Math.max(a[1], b[1]);
        float ix2 = Math.min(a[2], b[2]), iy2 = Math.min(a[3], b[3]);
        float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        float aA = (a[2]-a[0])*(a[3]-a[1]), bA = (b[2]-b[0])*(b[3]-b[1]);
        return inter / (aA + bA - inter + 1e-6f);
    }

    private float[] bytesToFloats(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] f = new float[bytes.length / 4];
        for (int i = 0; i < f.length; i++) f[i] = bb.getFloat();
        return f;
    }
}