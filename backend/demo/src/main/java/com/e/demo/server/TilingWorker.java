package com.e.demo.server;

import com.e.demo.config.RabbitConfig;
import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.dto.WsiUploadedEvent;
import com.e.demo.wsi.BioFormatsWsiReader;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
public class TilingWorker {

  @Qualifier("internalClient")
  private final MinioClient internalClient;

  private final QueuePublisher publisher;

  private final ExecutorService pool = Executors.newFixedThreadPool(8);
  private final Semaphore readLimiter = new Semaphore(2); // ограничим чтение region

  @RabbitListener(queues = RabbitConfig.Q_WSI_UPLOADED, containerFactory = "tilingListenerFactory")
  public void handle(WsiUploadedEvent e) throws Exception {

    // 1) скачиваем WSI локально
    File tmp = Files.createTempFile("wsi-", ".wsi").toFile();
    try (InputStream in = internalClient.getObject(GetObjectArgs.builder()
             .bucket(e.bucket()).object(e.objectKey()).build());
         OutputStream out = new FileOutputStream(tmp)) {
      in.transferTo(out);
    }

    // 2) открываем Bio-Formats reader
    try (BioFormatsWsiReader reader = new BioFormatsWsiReader(tmp.getAbsolutePath())) {
      int width = reader.width();
      int height = reader.height();

      int patch = e.patchSize();      // 512
      int overlap = e.overlap();      // 64
      int stride = patch - overlap;   // 448

      CompletionService<Void> cs = new ExecutorCompletionService<>(pool);
      int tasks = 0;

      for (int y = 0; y < height; y += stride) {
        for (int x = 0; x < width; x += stride) {
          final int fx = x, fy = y;

          cs.submit(() -> {
            // 3) читаем патч (с ограничением параллельных чтений)
            BufferedImage bi;
            readLimiter.acquire();
            try {
              int w = Math.min(patch, width - fx);
              int h = Math.min(patch, height - fy);

              // читаем доступный кусок
              BufferedImage partial = reader.readRegion(fx, fy, w, h);

              // паддинг до 512x512, чтобы модель всегда видела одинаковый размер
              bi = padTo512(partial, patch, patch);
            } finally {
              readLimiter.release();
            }

            // 4) encode PNG
            byte[] png = toPng(bi);

            // 5) кладём патч в MinIO
            String patchId = UUID.randomUUID().toString();
            String patchKey = "patches/" + e.wsiId() + "/" + patchId + ".png";
            try (ByteArrayInputStream bis = new ByteArrayInputStream(png)) {
              internalClient.putObject(PutObjectArgs.builder()
                  .bucket(e.bucket())
                  .object(patchKey)
                  .stream(bis, png.length, -1)
                  .contentType("image/png")
                  .build());
            }

            // 6) публикуем задачу на инференс
            publisher.publishPatchInference(new PatchInferenceEvent(
                e.wsiId(), patchId, e.bucket(), patchKey, fx, fy, patch, patch
            ));
            return null;
          });

          tasks++;
        }
      }

      // ждём завершения всех патчей
      for (int i = 0; i < tasks; i++) cs.take().get();
    } finally {
      tmp.delete();
    }
  }

  private static byte[] toPng(BufferedImage bi) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(256 * 1024);
    ImageIO.write(bi, "png", baos);
    return baos.toByteArray();
  }

  private static BufferedImage padTo512(BufferedImage src, int W, int H) {
    if (src.getWidth() == W && src.getHeight() == H) return src;
    BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    dst.getGraphics().drawImage(src, 0, 0, null);
    dst.getGraphics().dispose();
    return dst;
  }
}
