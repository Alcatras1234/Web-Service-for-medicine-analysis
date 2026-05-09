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

        // 2) Cold-render
        Path wsiPath = ensureWsiCached(slide);
        byte[] tile = renderTile(wsiPath.toString(), slideId, level, x, y);

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

        // Коэффициент даунсэмпла (level=maxLevel — полное разрешение, level=0 — самое маленькое)
        int scale = 1 << (maxLevel - level);

        // Размер региона на исходном WSI
        long regionSize = (long) TILE_SIZE * scale;
        long sx = (long) x * regionSize;
        long sy = (long) y * regionSize;

        if (sx >= info.width() || sy >= info.height()) {
            // запрос за границы — возвращаем пустой белый тайл
            return whiteTile();
        }

        long sw = Math.min(regionSize, info.width()  - sx);
        long sh = Math.min(regionSize, info.height() - sy);

        try (BioFormatsWsiReader reader = new BioFormatsWsiReader(wsiPath)) {
            BufferedImage region = reader.readRegion((int) sx, (int) sy, (int) sw, (int) sh);

            // Уменьшаем до TILE_SIZE × TILE_SIZE
            int outW = (int) Math.max(1, Math.round(sw / (double) scale));
            int outH = (int) Math.max(1, Math.round(sh / (double) scale));

            BufferedImage out = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(region, 0, 0, outW, outH, null);
            g.dispose();

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

    /** Скачивает WSI в локальный кэш, если его там ещё нет. */
    private Path ensureWsiCached(Slide slide) throws Exception {
        // Безопасное имя — slideId + расширение из s3 ключа
        String s3Path = slide.getS3Path();
        String ext = s3Path.contains(".") ? s3Path.substring(s3Path.lastIndexOf(".")) : ".svs";
        Path target = cacheDir.resolve(slide.getId() + ext);

        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }

        // Лочим по slideId — несколько одновременных запросов не дублируют скачивание
        Object lock = downloadLocks.computeIfAbsent("slide-" + slide.getId(), k -> new Object());
        synchronized (lock) {
            if (Files.exists(target) && Files.size(target) > 0) return target;
            log.info("Downloading WSI to local cache: slide={} key={}", slide.getId(), s3Path);
            try (InputStream in = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(s3Path).build())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
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
