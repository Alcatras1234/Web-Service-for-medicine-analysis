package com.e.demo.wsi;

import inference.GRPCInferenceServiceGrpc;
import inference.GrpcService.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

@Component
public class TritonGrpcClient {

    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD  = 0.45f;
    private static final int   NUM_CLASSES    = 2;     // eos, eosg
    private static final int   NUM_ANCHORS    = 4116;
    private static final int   ROW_SIZE       = 116;   // 4 + 2cls + 110mask_coeffs

    private final GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub stub;
    private final ManagedChannel channel;
    private final String modelName;

    public TritonGrpcClient(
            @Value("${triton.grpc.host}") String host,
            @Value("${triton.grpc.port}") int port,
            @Value("${triton.modelName}") String modelName) {
        this.modelName = modelName;
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build();
        this.stub = GRPCInferenceServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Запускает инференс и возвращает количество эозинофилов (класс 0 = eos).
     */
    public int infer(float[] imageTensor) {
        // Сериализуем float[] → bytes
        ByteBuffer buf = ByteBuffer.allocate(imageTensor.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : imageTensor) buf.putFloat(v);

        InferInputTensor input = InferInputTensor.newBuilder()
                .setName("images")
                .setDatatype("FP32")
                .addShape(1).addShape(3).addShape(448).addShape(448)
                .setContents(InferTensorContents.newBuilder()
                        .setRawContents(
                                com.google.protobuf.ByteString.copyFrom(buf.array())))
                .build();

        InferOutputTensor reqOut0 = InferOutputTensor.newBuilder().setName("output0").build();

        ModelInferRequest request = ModelInferRequest.newBuilder()
                .setModelName(modelName)
                .addInputs(input)
                .addOutputs(reqOut0)
                .build();

        ModelInferResponse response = stub.modelInfer(request);

        // Читаем output0: [1, 116, 4116]
        byte[] raw = response.getRawOutputContentsList().isEmpty()
                ? response.getOutputs(0).getContents().getRawContents().toByteArray()
                : response.getRawOutputContents(0).toByteArray();

        float[] output0 = bytesToFloats(raw);
        // output0 имеет форму [116, 4116] (batch=1 убрали)
        // Транспонируем: для каждого anchor [4116] берём row [116]
        return parseAndCount(output0);
    }

    private int parseAndCount(float[] output0) {
        // output0 layout: [ROW_SIZE=116][NUM_ANCHORS=4116]
        // Для каждого anchor i: row = output0[j * NUM_ANCHORS + i]
        // cx=row[0], cy=row[1], w=row[2], h=row[3]
        // class scores: row[4]..row[4+NUM_CLASSES-1]

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

        // Фильтрация по confidence + NMS
        List<float[]> detections = new java.util.ArrayList<>();
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

        // Считаем только eos (class 0)
        return (int) kept.stream().filter(d -> d[5] == 0).count();
    }

    private List<float[]> nms(List<float[]> dets, float iouThresh) {
        dets.sort((a, b) -> Float.compare(b[4], a[4]));
        List<float[]> result = new java.util.ArrayList<>();
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

    @PreDestroy
    public void shutdown() {
        channel.shutdown();
    }
}