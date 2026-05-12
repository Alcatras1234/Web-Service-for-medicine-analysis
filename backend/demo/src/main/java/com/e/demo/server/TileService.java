package com.e.demo.server;

import com.e.demo.entity.Slide;
import com.e.demo.repository.SlideRepository;
import com.e.demo.wsi.BioFormatsWsiReader;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E4: сервис рендера IIIF-тайлов.
 *
 * Двухуровневое кэширование:
 *   1) Тайл (256×256 JPEG) хранится в MinIO по ключу tiles/{slideId}/{level}/{x}_{y}.jpg
 *   2) Сам WSI-файл — в локальной директории wsi.cache-dir, чтобы BioFormats
 *      мог быстро открывать его при cache-miss тайлов.
 *
 * BioFormats не thread-safe, поэтому на каждый запрос — отдельный reader.
 * Это медленно, но кэш в MinIO амортизирует cold-render.
 */
@Service
@Slf4j
public class TileService {

    private static final int TILE_SIZE = 256;
    private static final String IMAGES_BUCKET = "wsi-bucket";

    private final MinioClient minioClient;
    private final SlideRepository slideRepo;
    private final Path cacheDir;

    @Value("${minio.bucketName}")
    private String bucket;

    /** Защита от одновременного скачивания одного и того же WSI. */
    private final ConcurrentHashMap<String, Object> downloadLocks = new ConcurrentHashMap<>();

    /** На каждый slideId — свой ReentrantLock. BioFormats не thread-safe при параллельном
     *  чтении одного и того же файла, поэтому рендер тайлов одного слайда идёт последовательно. */
    private final ConcurrentHashMap<Integer, java.util.concurrent.locks.ReentrantLock> readLocks =
            new ConcurrentHashMap<>();

    public TileService(@Qualifier("internalClient") MinioClient minioClient,
                       SlideRepository slideRepo,
                       @Value("${wsi.cache-dir}") String cacheDir) {
        this.minioClient = minioClient;
        this.slideRepo = slideRepo;
        this.cacheDir = Paths.get(cacheDir);
    }

    @PostConstruct
    public void init() throws Exception {
        Files.createDirectories(cacheDir);
        log.info("TileService cache dir: {}", cacheDir.toAbsolutePath());
    }

    public record SlideInfo(int width, int height, int tileSize, int maxLevel,
                            Double mppX, Double mppY) {}

    public Optional<SlideInfo> getInfo(int slideId) {
        return slideRepo.findActiveById(slideId).map(s -> {
            int w = s.getWidthPx() != null ? s.getWidthPx() : 0;
            int h = s.getHeightPx() != null ? s.getHeightPx() : 0;
            int maxDim = Math.max(w, h);
            int maxLevel = maxDim <= TILE_SIZE ? 0
                    : (int) Math.ceil(Math.log(maxDim / (double) TILE_SIZE) / Math.log(2));
            return new SlideInfo(w, h, TILE_SIZE, maxLevel, s.getMppX(), s.getMppY());
        });
    }

    /**
     * Возвращает байты тайла (JPEG). Если в MinIO кэше нет — генерирует и кэширует.
     */
    public byte[] getTile(int slideId, int level, int x, int y) throws Exception {
        Slide slide = slideRepo.findActiveById(slideId).orElseThrow(
                () -> new IllegalArgumentException("Slide not found: " + slideId));

        String tileKey = "tiles/%d/%d/%d_%d.jpg".formatted(slideId, level, x, y);

        // 1) Кэш в MinIO
        byte[] cached = readFromMinIO(tileKey);
        if (cached != null) return cached;

        // 2) Cold-render. Берём lock на чтение этого WSI — BioFormats не thread-safe.
        Path wsiPath = ensureWsiCached(slide);
        var lock = readLocks.computeIfAbsent(slideId, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        byte[] tile;
        try {
            // повторно проверим кэш — могли отрендерить пока ждали lock
            byte[] cachedAgain = readFromMinIO(tileKey);
            if (cachedAgain != null) return cachedAgain;
            tile = renderTile(wsiPath.toString(), slideId, level, x, y);
        } finally {
            lock.unlock();
        }

        // Кладём обратно в MinIO (best-effort)
        try {
            uploadToMinIO(tileKey, tile, "image/jpeg");
        } catch (Exception e) {
            log.warn("Tile cache write failed: {}", tileKey, e);
        }
        return tile;
    }

    private byte[] renderTile(String wsiPath, int slideId, int level, int x, int y) throws Exception {
        SlideInfo info = getInfo(slideId).orElseThrow();
        int maxLevel = info.maxLevel();

        // Желаемый downsample относительно full-res
        int desiredScale = 1 << (maxLevel - level);

        // Координаты тайла в координатах full-res
        long fullRegionSize = (long) TILE_SIZE * desiredScale;
        long fullX = (long) x * fullRegionSize;
        long fullY = (long) y * fullRegionSize;

        if (fullX >= info.width() || fullY >= info.height()) {
            return whiteTile();
        }

        try (BioFormatsWsiReader reader = new BioFormatsWsiReader(wsiPath)) {
            // Подбираем pyramid level в SVS такой, чтобы его downsample был
            // ≤ нашего desiredScale (т.е. достаточно крупный, чтобы не потерять детали).
            // Затем уже от него досжимаем в Java — это даёт регион всегда < TILE_SIZE * 2 пикселей.
            int bestRes = 0;
            int bestDownsample = 1;
            int fullW = info.width();
            for (int r = 0; r < reader.resolutionCount(); r++) {
                reader.setResolution(r);
                int wAtR = reader.width();
                if (wAtR <= 0) continue;
                int downsample = Math.max(1, (int) Math.round(fullW / (double) wAtR));
                if (downsample <= desiredScale && downsample > bestDownsample) {
                    bestDownsample = downsample;
                    bestRes = r;
                }
                if (downsample == 1 && bestDownsample == 1) {
                    bestRes = 0; // full-res по умолчанию
                }
            }
            reader.setResolution(bestRes);

            // Координаты и размер региона на выбранном уровне
            int resW = reader.width();
            int resH = reader.height();
            long sx = fullX / bestDownsample;
            long sy = fullY / bestDownsample;
            long sw = Math.min(fullRegionSize / bestDownsample, resW - sx);
            long sh = Math.min(fullRegionSize / bestDownsample, resH - sy);
            if (sw <= 0 || sh <= 0) return whiteTile();

            // Защита: даже на выбранном уровне не должно быть больше ~2K на сторону
            // (BioFormats имеет 2GB лимит на openBytes, ~16K x 16K x 3 байта = опасно)
            if (sw * sh > 8_000_000L) {
                log.warn("Region too large after pyramid pick: slide={} level={} {}x{} (downsample={} res={})",
                        slideId, level, sw, sh, bestDownsample, bestRes);
                return whiteTile();
            }

            BufferedImage region = reader.readRegion((int) sx, (int) sy, (int) sw, (int) sh);

            // Финальный downscale до TILE_SIZE × TILE_SIZE
            int extraScale = desiredScale / bestDownsample;
            int outW = (int) Math.max(1, sw / Math.max(1, extraScale));
            int outH = (int) Math.max(1, sh / Math.max(1, extraScale));

            BufferedImage out = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(region, 0, 0, outW, outH, null);
            g.dispose();
            region.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
            ImageIO.write(out, "jpg", baos);
            return baos.toByteArray();
        }
    }

    private byte[] whiteTile() throws Exception {
        BufferedImage out = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        ImageIO.write(out, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * §6.2: рендер произвольного региона WSI в JPEG для встраивания в PDF.
     * Использует pyramid-уровень с downsample ≤ требуемого, чтобы не превысить 2GB лимит BioFormats.
     */
    public byte[] renderRegion(int slideId, int wsiX, int wsiY, int wsiW, int wsiH,
                               int outMaxDim) throws Exception {
        Slide slide = slideRepo.findActiveById(slideId).orElseThrow(
                () -> new IllegalArgumentException("Slide not found: " + slideId));
        Path wsiPath = ensureWsiCached(slide);

        // Сериализуем по slideId — BioFormats не thread-safe.
        var lock = readLocks.computeIfAbsent(slideId, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try (BioFormatsWsiReader reader = new BioFormatsWsiReader(wsiPath.toString())) {
            int fullW = reader.width();

            // Подбираем pyramid-уровень
            int desiredScale = Math.max(1, Math.max(wsiW, wsiH) / outMaxDim);
            int bestRes = 0, bestDownsample = 1;
            for (int r = 0; r < reader.resolutionCount(); r++) {
                reader.setResolution(r);
                int wAtR = reader.width();
                if (wAtR <= 0) continue;
                int downsample = Math.max(1, (int) Math.round(fullW / (double) wAtR));
                if (downsample <= desiredScale && downsample > bestDownsample) {
                    bestDownsample = downsample;
                    bestRes = r;
                }
            }
            reader.setResolution(bestRes);

            int sx = wsiX / bestDownsample;
            int sy = wsiY / bestDownsample;
            int sw = Math.min(wsiW / bestDownsample, reader.width()  - sx);
            int sh = Math.min(wsiH / bestDownsample, reader.height() - sy);
            if (sw <= 0 || sh <= 0) return null;
            if ((long) sw * sh > 16_000_000L) {
                log.warn("renderRegion too large after pyramid pick: {}x{}", sw, sh);
                return null;
            }

            BufferedImage region = reader.readRegion(sx, sy, sw, sh);
            int outW = Math.min(outMaxDim, sw);
            int outH = Math.min(outMaxDim, sh);
            BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, outW, outH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(region, 0, 0, outW, outH, null);
            g.dispose();
            region.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            ImageIO.write(out, "jpg", baos);
            return baos.toByteArray();
        } finally {
            lock.unlock();
        }
    }

    /** Скачивает WSI в локальный кэш, если его там ещё нет. Atomic — частично записанные
     *  файлы не видны другим потокам (через .part → ATOMIC_MOVE). */
    private Path ensureWsiCached(Slide slide) throws Exception {
        String s3Path = slide.getS3Path();
        String ext = s3Path.contains(".") ? s3Path.substring(s3Path.lastIndexOf(".")) : ".svs";
        Path target = cacheDir.resolve(slide.getId() + ext);

        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }

        Object lock = downloadLocks.computeIfAbsent("slide-" + slide.getId(), k -> new Object());
        synchronized (lock) {
            if (Files.exists(target) && Files.size(target) > 0) return target;
            log.info("Downloading WSI to local cache: slide={} key={}", slide.getId(), s3Path);
            // Качаем в temp, потом atomic move — другие потоки видят либо ничего, либо готовый файл.
            Path tmp = cacheDir.resolve(slide.getId() + ext + ".part-" + Thread.currentThread().getId());
            try {
                try (InputStream in = minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // редкий случай (например cross-device) — fallback на обычный move
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
        return target;
    }

    private byte[] readFromMinIO(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            return null; // нет в кэше
        } catch (Exception e) {
            return null;
        }
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private void uploadToMinIO(String key, byte[] bytes, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType(contentType)
                .build());
    }
}
