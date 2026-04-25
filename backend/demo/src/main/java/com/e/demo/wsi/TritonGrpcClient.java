package com.e.demo.wsi;

import inference.GRPCInferenceServiceGrpc;
import inference.GrpcService.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class TritonGrpcClient {

    private final ManagedChannel channel;
    private final GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub stub;

    @Value("${triton.modelName:eosin_yolo}")
    private String modelName;

    public TritonGrpcClient(
            @Value("${triton.grpc.host:triton}") String host,
            @Value("${triton.grpc.port:8001}") int port) {

        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(64 * 1024 * 1024) // 64MB
                .build();

        this.stub = GRPCInferenceServiceGrpc
                .newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);

        log.info("TritonGrpcClient → {}:{}", host, port);
    }

    /**
     * Запускает инференс патча [1,3,448,448].
     * Возвращает количество эозинофилов после NMS.
     */
    public int infer(float[] inputData) {
        // float[] → bytes (little-endian)
        ByteBuffer buf = ByteBuffer.allocate(inputData.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : inputData) buf.putFloat(v);

        ModelInferRequest.InferInputTensor inputTensor =
                ModelInferRequest.InferInputTensor.newBuilder()
                        .setName("images")
                        .setDatatype("FP32")
                        .addShape(1).addShape(3).addShape(448).addShape(448)
                        .build();

        ModelInferRequest request = ModelInferRequest.newBuilder()
                .setModelName(modelName)
                .setModelVersion("")
                .addInputs(inputTensor)
                .addRawInputContents(ByteString.copyFrom(buf.array()))
                .build();

        ModelInferResponse response = stub.modelInfer(request);

        // output0: raw bytes float32, layout [1, 38, 8400]
        byte[] rawBytes = response.getRawOutputContents(0).toByteArray();
        float[] output = bytesToFloats(rawBytes);

        return countEosinophils(output);
    }

    /**
     * YOLO11-seg output layout: [1, 38, 8400]
     * row 0..3 = xc, yc, w, h
     * row 4    = objectness/conf
     * row 5    = score cls0 (eos)
     * row 6    = score cls1 (eosg)
     * row 7..38= mask coefficients (игнорируем)
     */
    private int countEosinophils(float[] data) {
        final int COLS = 8400;
        final float CONF_THRESH = 0.25f;
        final float IOU_THRESH  = 0.45f;

        List<float[]> candidates = new ArrayList<>();

        for (int col = 0; col < COLS; col++) {
            float conf = data[4 * COLS + col];
            if (conf < CONF_THRESH) continue;

            float cls0 = data[5 * COLS + col]; // eos
            float cls1 = data[6 * COLS + col]; // eosg
            if (cls0 <= cls1) continue;        // только eos

            float xc = data[0 * COLS + col];
            float yc = data[1 * COLS + col];
            float w  = data[2 * COLS + col];
            float h  = data[3 * COLS + col];

            candidates.add(new float[]{
                    xc - w / 2f, yc - h / 2f,  // x1, y1
                    xc + w / 2f, yc + h / 2f,  // x2, y2
                    conf * cls0                  // score
            });
        }

        return nms(candidates, IOU_THRESH).size();
    }

        private List<float[]> nms(List<float[]> boxes, float iouThresh) {
        if (boxes.isEmpty()) return boxes;
        boxes.sort((a, b) -> Float.compare(b[4], a[4]));
        boolean[] suppressed = new boolean[boxes.size()];
        List<float[]> kept = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) continue;
            kept.add(boxes.get(i));
            for (int j = i + 1; j < boxes.size(); j++) {
                if (!suppressed[j] && iou(boxes.get(i), boxes.get(j)) > iouThresh)
                    suppressed[j] = true;
            }
        }
        return kept;
    }

    private float iou(float[] a, float[] b) {
        float ix1 = Math.max(a[0], b[0]), iy1 = Math.max(a[1], b[1]);
        float ix2 = Math.min(a[2], b[2]), iy2 = Math.min(a[3], b[3]);
        float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        float areaA = (a[2] - a[0]) * (a[3] - a[1]);
        float areaB = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        buf.asFloatBuffer().get(floats);
        return floats;
    }

    @PreDestroy
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}