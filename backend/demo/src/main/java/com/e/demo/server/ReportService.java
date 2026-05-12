package com.e.demo.server;

import com.e.demo.entity.Case;
import com.e.demo.entity.CaseSignoff;
import com.e.demo.entity.Job;
import com.e.demo.entity.PatchTask;
import com.e.demo.entity.Slide;
import com.e.demo.entity.User;
import com.e.demo.repository.CaseRepository;
import com.e.demo.repository.CaseSignoffRepository;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import com.e.demo.repository.SlideRepository;
import com.e.demo.repository.UserRepository;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Клиническое заключение по анализу WSI-препарата.
 *
 * Структура PDF:
 *   1. Шапка с информацией о пациенте/слайде
 *   2. Диагностический блок (большой, цветной)
 *   3. Количественные показатели (px → мм через реальный MPP)
 *   4. Тепловая карта + патч HPF max
 *   5. Подпись врача либо disclaimer
 *   6. Технические параметры мелким шрифтом
 */
@Service
@Slf4j
public class ReportService {

    private static final double HPF_AREA_MM2 = 0.3;
    private static final int    EOE_THRESHOLD = 15;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final JobRepository         jobRepository;
    private final PatchTaskRepository   patchTaskRepository;
    private final SlideRepository       slideRepository;
    private final CaseRepository        caseRepository;
    private final CaseSignoffRepository signoffRepository;
    private final UserRepository        userRepository;
    private final MinioClient           minioClient;
    private final TileService           tileService;
    private final DetectionStore        detectionStore;

    @Value("${minio.bucketName}")
    private String bucket;

    public ReportService(JobRepository jobRepository,
                         PatchTaskRepository patchTaskRepository,
                         SlideRepository slideRepository,
                         CaseRepository caseRepository,
                         CaseSignoffRepository signoffRepository,
                         UserRepository userRepository,
                         @Qualifier("internalClient") MinioClient minioClient,
                         TileService tileService,
                         DetectionStore detectionStore) {
        this.jobRepository       = jobRepository;
        this.patchTaskRepository = patchTaskRepository;
        this.slideRepository     = slideRepository;
        this.caseRepository      = caseRepository;
        this.signoffRepository   = signoffRepository;
        this.userRepository      = userRepository;
        this.minioClient         = minioClient;
        this.tileService         = tileService;
        this.detectionStore      = detectionStore;
    }

    @Async
    public void generateAsync(UUID jobId) {
        try {
            generate(jobId);
        } catch (Exception e) {
            log.error("Report generation failed: job={}", jobId, e);
        }
    }

    public void generate(UUID jobId) throws Exception {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        Slide slide = slideRepository.findById(job.getSlideId())
                .orElseThrow(() -> new RuntimeException("Slide not found: " + job.getSlideId()));
        List<PatchTask> patches = patchTaskRepository.findAllByJobId(jobId);

        double mpp = (slide.getMppX() != null && slide.getMppX() > 0) ? slide.getMppX() : 0.25;
        int hpfWindowPx = (int) Math.round(Math.sqrt(HPF_AREA_MM2 * 1_000_000.0) / mpp);

        String heatmapPath = generateAndUploadHeatmap(job, patches, hpfWindowPx, mpp);
        String reportPath  = generateAndUploadPdf(job, slide, patches, heatmapPath, mpp, hpfWindowPx);

        jobRepository.updateReportPaths(jobId, reportPath, heatmapPath, Instant.now());
        log.info("Report ready: job={} pdf={}", jobId, reportPath);
    }

    // ── Heatmap ───────────────────────────────────────────────────────────────

    private String generateAndUploadHeatmap(Job job, List<PatchTask> patches, int hpfWindowPx, double mpp) throws Exception {
        if (patches.isEmpty()) return null;

        int slideW = patches.stream().mapToInt(p -> p.getX() + p.getWidth()).max().orElse(1000);
        int slideH = patches.stream().mapToInt(p -> p.getY() + p.getHeight()).max().orElse(1000);
        float scale = Math.min(2000f / slideW, 2000f / slideH);
        int imgW = (int) (slideW * scale);
        int imgH = (int) (slideH * scale);

        BufferedImage heatmap = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = heatmap.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, imgW, imgH);

        int maxCount = patches.stream()
                .mapToInt(p -> p.getEosinophilCount() == null ? 0 : p.getEosinophilCount())
                .max().orElse(1);
        if (maxCount == 0) maxCount = 1;

        for (PatchTask patch : patches) {
            int   count = patch.getEosinophilCount() == null ? 0 : patch.getEosinophilCount();
            float ratio = (float) count / maxCount;
            int   px    = (int) (patch.getX() * scale);
            int   py    = (int) (patch.getY() * scale);
            int   pw    = Math.max(1, (int) (patch.getWidth()  * scale));
            int   ph    = Math.max(1, (int) (patch.getHeight() * scale));

            Color c    = heatColor(ratio);
            int alpha  = count == 0 ? 25 : (int) (90 + ratio * 165);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, alpha)));
            g.fillRect(px, py, pw, ph);
        }

        // Красная рамка вокруг HPF max — реальный размер из MPP
        if (job.getMaxHpfX() != null && job.getMaxHpfY() != null) {
            int px = (int) (job.getMaxHpfX() * scale);
            int py = (int) (job.getMaxHpfY() * scale);
            int pw = (int) (hpfWindowPx * scale);
            int ph = (int) (hpfWindowPx * scale);
            g.setColor(new Color(220, 30, 30));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(px, py, pw, ph);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString("HPF max: " + job.getMaxHpfCount(), px + 4, Math.max(14, py - 6));
        }

        drawLegend(g, imgW, imgH, maxCount);
        // §6.2: scale bar в µm/мм слева внизу — патолог сразу видит масштаб
        drawHeatmapScaleBar(g, imgW, imgH, mpp, slideW);
        g.dispose();

        String minioPath = "reports/" + job.getId() + "/heatmap.png";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(heatmap, "png", baos);
        byte[] bytes = baos.toByteArray();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(minioPath)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("image/png").build());
        return minioPath;
    }

    private Color heatColor(float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        float r, g, b;
        if      (ratio < 0.25f) { float t = ratio / 0.25f;           r = 0;  g = t;  b = 1f; }
        else if (ratio < 0.5f)  { float t = (ratio - 0.25f) / 0.25f; r = 0;  g = 1f; b = 1f - t; }
        else if (ratio < 0.75f) { float t = (ratio - 0.5f)  / 0.25f; r = t;  g = 1f; b = 0; }
        else                    { float t = (ratio - 0.75f) / 0.25f;  r = 1f; g = 1f - t; b = 0; }
        return new Color(r, g, b);
    }

    /** Scale bar для heatmap: подбирает «круглую» длину 100/200/500 µm / 1/2/5 мм. */
    private void drawHeatmapScaleBar(Graphics2D g, int imgW, int imgH, double mpp, int slideWpx) {
        double umPerPx = mpp * slideWpx / (double) imgW;     // µm на 1 px heatmap
        int[] candidatesUm = {5000, 2000, 1000, 500, 200, 100};
        int chosenUm = 1000;
        for (int c : candidatesUm) {
            double px = c / umPerPx;
            if (px > imgW * 0.10 && px < imgW * 0.30) { chosenUm = c; break; }
        }
        int barPx = (int) Math.round(chosenUm / umPerPx);

        int padding = 14;
        int barH = 7;
        int x0 = padding;
        int y0 = imgH - padding - barH - 16;

        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x0 - 8, y0 - 6, barPx + 16, barH + 28, 6, 6);
        g.setColor(Color.WHITE);
        g.fillRect(x0, y0, barPx, barH);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x0, y0, barPx, barH);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        String label = chosenUm >= 1000
                ? (chosenUm / 1000) + " мм"
                : chosenUm + " µm";
        g.drawString(label, x0, y0 + barH + 14);
    }

    private void drawLegend(Graphics2D g, int imgW, int imgH, int maxCount) {
        int legX = imgW - 130, legY = imgH - 170, legW = 22, legH = 130;
        for (int i = 0; i < legH; i++) {
            g.setColor(heatColor(1f - (float) i / legH));
            g.fillRect(legX, legY + i, legW, 1);
        }
        g.setColor(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(legX, legY, legW, legH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString(maxCount + " эоз.", legX + legW + 4, legY + 11);
        g.drawString("0",                  legX + legW + 4, legY + legH);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("Плотность", legX - 12, legY - 6);
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    private String generateAndUploadPdf(Job job, Slide slide, List<PatchTask> patches,
                                        String heatmapMinioPath, double mpp, int hpfWindowPx) throws Exception {
        ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
        // Document.close() каскадно закроет PdfDocument и PdfWriter — поэтому держим только его в try-with-resources
        try (Document doc = new Document(new PdfDocument(new PdfWriter(pdfBaos)))) {

            doc.setMargins(40, 40, 40, 40);

            // Кириллический шрифт — иначе русский текст выпадает из PDF целиком.
            // Положи DejaVuSans.ttf в src/main/resources/fonts/.
            // Жирность .setBold() даст синтетическое утолщение — выглядит ОК.
            PdfFont cyrFont = loadCyrillicFont("/fonts/DejaVuSans.ttf");
            if (cyrFont != null) doc.setFont(cyrFont);

            // Подпись (если есть) — нужна для блоков ниже
            CaseSignoff signoff = null;
            User signer = null;
            if (slide.getCaseId() != null) {
                signoff = signoffRepository.findFirstByCaseIdOrderBySignedAtDesc(slide.getCaseId()).orElse(null);
                if (signoff != null) {
                    signer = userRepository.findById(signoff.getUserId()).orElse(null);
                }
            }
            String caseName = null;
            if (slide.getCaseId() != null) {
                Case c = caseRepository.findById(slide.getCaseId()).orElse(null);
                if (c != null) caseName = c.getName();
            }

            // 1. Шапка
            renderHeader(doc);

            // 2. Информация о пациенте/слайде
            renderPatientInfo(doc, job, slide, caseName, mpp);

            // 3. Диагностический блок (главное)
            renderDiagnosis(doc, job, signoff);

            // 4. Количественные показатели
            renderMetrics(doc, job, slide, patches, mpp, hpfWindowPx);

            // 5. Тепловая карта
            renderHeatmap(doc, heatmapMinioPath);

            // 6. §6.2: реальное окно HPF max в высоком разрешении (вместо одного 448-патча)
            renderHpfMaxRegion(doc, job, slide, mpp, hpfWindowPx);

            // 7. Подпись врача / disclaimer
            renderSignoffOrDisclaimer(doc, signoff, signer);

            // 8. Технические параметры (мелко, внизу)
            renderTechFooter(doc, job, patches);
        }

        String minioPath = "reports/" + job.getId() + "/report.pdf";
        byte[] pdfBytes  = pdfBaos.toByteArray();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(minioPath)
                .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                .contentType("application/pdf").build());
        return minioPath;
    }

    // ── Секции PDF ────────────────────────────────────────────────────────────

    private void renderHeader(Document doc) {
        doc.add(new Paragraph("ЗАКЛЮЧЕНИЕ ПО АНАЛИЗУ ГИСТОЛОГИЧЕСКОГО ПРЕПАРАТА")
                .setFontSize(14).setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(new DeviceRgb(20, 40, 90)));
        doc.add(new Paragraph("EosinAI · Автоматический подсчёт эозинофилов · Eosinophilic Esophagitis (EoE)")
                .setFontSize(9)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(ColorConstants.GRAY));
        doc.add(separator());
    }

    private void renderPatientInfo(Document doc, Job job, Slide slide, String caseName, double mpp) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{30, 70})).useAllAvailableWidth();
        t.setMarginBottom(10);
        addInfoRow(t, "Пациент",           orDash(slide.getPatientId()));
        if (caseName != null) addInfoRow(t, "Кейс",   caseName);
        addInfoRow(t, "Локация биопсии",   biopsyLocationLabel(slide.getBiopsyLocation()));
        addInfoRow(t, "Файл",              orDash(slide.getFilename()));
        addInfoRow(t, "Дата анализа",      LocalDateTime.now(ZoneId.of("Europe/Moscow")).format(DATE_FMT));
        addInfoRow(t, "Калибровка",
                String.format(Locale.US, "MPP = %.4f µm/px (источник: %s)",
                        mpp, mppSourceLabel(slide.getMppSource())));
        if (slide.getWidthPx() != null && slide.getHeightPx() != null) {
            double widthMm  = slide.getWidthPx()  * mpp / 1000.0;
            double heightMm = slide.getHeightPx() * mpp / 1000.0;
            addInfoRow(t, "Размер скана",
                    String.format(Locale.US, "%d × %d px  (%.1f × %.1f мм)",
                            slide.getWidthPx(), slide.getHeightPx(), widthMm, heightMm));
        }
        doc.add(t);
    }

    private void renderDiagnosis(Document doc, Job job, CaseSignoff signoff) {
        // Если есть подпись — берём вердикт врача, иначе — авто-диагноз
        boolean signed = signoff != null;
        String  diagText;
        boolean isPositive;
        if (signed) {
            isPositive = "POSITIVE".equals(signoff.getDiagnosis());
            diagText   = "ВЕРДИКТ ВРАЧА: " + diagnosisLabel(signoff.getDiagnosis());
        } else {
            isPositive = "POSITIVE".equals(job.getDiagnosis());
            diagText   = "АВТО-ДИАГНОЗ: " + diagnosisLabel(job.getDiagnosis());
        }

        DeviceRgb fg, bg;
        if ("INCONCLUSIVE".equals(signoff != null ? signoff.getDiagnosis() : "")) {
            fg = new DeviceRgb(150, 100, 0);
            bg = new DeviceRgb(255, 248, 230);
        } else if (isPositive) {
            fg = new DeviceRgb(180, 0, 0);
            bg = new DeviceRgb(255, 235, 235);
        } else {
            fg = new DeviceRgb(0, 110, 0);
            bg = new DeviceRgb(235, 250, 235);
        }

        doc.add(new Paragraph(diagText)
                .setFontSize(16).setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(fg)
                .setBackgroundColor(bg)
                .setPadding(12)
                .setMarginTop(8)
                .setMarginBottom(4));

        // §3.2: PEC по EoE consensus = только intact эозинофилы
        int pecIntact = job.getMaxHpfIntact() != null ? job.getMaxHpfIntact()
                : (job.getMaxHpfCount() != null ? job.getMaxHpfCount() : 0);
        int pecTotal  = job.getMaxHpfCount()  != null ? job.getMaxHpfCount()  : 0;
        String summary = String.format(
                "PEC (intact): %d / HPF · в окне всего (intact + granulated): %d · Порог EoE: ≥ %d · Площадь HPF: %.2f мм²",
                pecIntact, pecTotal, EOE_THRESHOLD, HPF_AREA_MM2);
        doc.add(new Paragraph(summary)
                .setFontSize(10)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(ColorConstants.DARK_GRAY)
                .setMarginBottom(12));
    }

    private void renderMetrics(Document doc, Job job, Slide slide,
                                List<PatchTask> patches, double mpp, int hpfWindowPx) {
        doc.add(new Paragraph("Количественные показатели").setBold().setFontSize(12).setMarginBottom(4));
        Table t = new Table(UnitValue.createPercentArray(new float[]{55, 45})).useAllAvailableWidth();

        int totalEos    = job.getTotalEosinophilCount() != null ? job.getTotalEosinophilCount() : 0;
        int peakIntact  = job.getMaxHpfIntact()         != null ? job.getMaxHpfIntact()         : 0;
        int peakSum     = job.getMaxHpfCount()          != null ? job.getMaxHpfCount()          : 0;
        addMetricRow(t, "Всего эозинофилов на препарате", String.format("%d", totalEos));
        addMetricRow(t, "PEC — intact (диагностический)",
                String.format("%d / HPF  (%s)", peakIntact,
                        peakIntact >= EOE_THRESHOLD ? "≥ порога 15" : "ниже порога 15"));
        addMetricRow(t, "В том же окне HPF — всего (intact + granulated)",
                String.format("%d / HPF", peakSum));

        if (job.getMaxHpfX() != null && job.getMaxHpfY() != null) {
            double xMm = job.getMaxHpfX() * mpp / 1000.0;
            double yMm = job.getMaxHpfY() * mpp / 1000.0;
            addMetricRow(t, "Расположение HPF max",
                    String.format(Locale.US, "x=%d, y=%d px  (%.2f, %.2f мм от верх.-лев. угла)",
                            job.getMaxHpfX(), job.getMaxHpfY(), xMm, yMm));
        }
        double sideMm = hpfWindowPx * mpp / 1000.0;
        addMetricRow(t, "Размер HPF-окна",
                String.format(Locale.US, "%d × %d px  (%.2f × %.2f мм)", hpfWindowPx, hpfWindowPx, sideMm, sideMm));

        long failed = patches.stream().filter(p -> "FAILED".equals(p.getStatus())).count();
        long skipped = patches.stream().filter(p -> "SKIPPED_WHITE".equals(p.getStatus())).count();
        addMetricRow(t, "Обработано патчей",
                String.format("%d (отклонено как фон: %d, ошибок: %d)", patches.size(), skipped, failed));

        doc.add(t.setMarginBottom(12));
    }

    private void renderHeatmap(Document doc, String heatmapMinioPath) {
        if (heatmapMinioPath == null) return;
        doc.add(new Paragraph("Тепловая карта распределения").setBold().setFontSize(12).setMarginBottom(2));
        doc.add(new Paragraph("Цвет = плотность эозинофилов (синий → красный). Красная рамка — HPF с пиковой плотностью (0.3 мм²). Линейка масштаба слева внизу.")
                .setFontSize(9).setFontColor(ColorConstants.GRAY).setMarginBottom(4));
        try {
            byte[] hm = downloadBytes(heatmapMinioPath);
            Image hmImg = new Image(ImageDataFactory.create(hm));
            hmImg.setWidth(UnitValue.createPercentValue(95));
            hmImg.setHorizontalAlignment(HorizontalAlignment.CENTER);
            doc.add(hmImg);
        } catch (Exception e) {
            log.warn("Heatmap unavailable: {}", e.getMessage());
            doc.add(new Paragraph("[Тепловая карта недоступна]").setFontColor(ColorConstants.GRAY));
        }
    }

    /**
     * §6.2: рендерит две картинки HPF-зоны:
     *   1. Overview — вся область 0.3 мм² (видна общая картина — где скопления)
     *   2. Zoom inset — фрагмент ~250×250 µm в нативном разрешении (видны клетки)
     * Обе картинки с масштабной линейкой и метками детекций (intact красным, granulated жёлтым).
     */
    private void renderHpfMaxRegion(Document doc, Job job, Slide slide, double mpp, int hpfWindowPx) {
        if (job.getMaxHpfX() == null || job.getMaxHpfY() == null
                || job.getMaxHpfCount() == null || job.getMaxHpfCount() == 0) {
            return;
        }
        doc.add(new Paragraph("Окно HPF max — зона решения (0.3 мм²)").setBold().setFontSize(12)
                .setMarginTop(12).setMarginBottom(2));
        double xMm = job.getMaxHpfX() * mpp / 1000.0;
        double yMm = job.getMaxHpfY() * mpp / 1000.0;
        Integer intactPec = job.getMaxHpfIntact() != null ? job.getMaxHpfIntact() : job.getMaxHpfCount();
        doc.add(new Paragraph(String.format(Locale.US,
                "Координаты окна: (%d, %d) px  ≈  (%.2f, %.2f) мм от верх.-лев. угла  ·  intact PEC: %d",
                job.getMaxHpfX(), job.getMaxHpfY(), xMm, yMm, intactPec))
                .setFontSize(9).setFontColor(ColorConstants.GRAY).setMarginBottom(4));

        // Загружаем детекции один раз (для overview и zoom inset)
        List<Map<String, Object>> allDetections = job.getDetectionsPath() != null
                ? detectionStore.readAll(job.getDetectionsPath())
                : List.of();

        int zoomUm = 250;
        int zoomPxOnWsi = (int) Math.round(zoomUm / mpp);
        int zoomCenterX = job.getMaxHpfX() + hpfWindowPx / 2;
        int zoomCenterY = job.getMaxHpfY() + hpfWindowPx / 2;
        int zoomX = Math.max(0, zoomCenterX - zoomPxOnWsi / 2);
        int zoomY = Math.max(0, zoomCenterY - zoomPxOnWsi / 2);

        // 1. Overview — всё HPF-окно с метками всех детекций в нём
        try {
            int outDim = 1200;
            byte[] regionBytes = tileService.renderRegion(
                    slide.getId(), job.getMaxHpfX(), job.getMaxHpfY(),
                    hpfWindowPx, hpfWindowPx, outDim);
            if (regionBytes != null) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(regionBytes));
                int[] counts = drawDetections(img, allDetections,
                        job.getMaxHpfX(), job.getMaxHpfY(), hpfWindowPx, hpfWindowPx, 6);
                BufferedImage withBar = drawScaleBar(img, mpp, hpfWindowPx);
                BufferedImage withZoomMark = drawZoomFrame(withBar, hpfWindowPx, zoomPxOnWsi);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(96 * 1024);
                ImageIO.write(withZoomMark, "jpg", baos);

                Image pdfImg = new Image(ImageDataFactory.create(baos.toByteArray()));
                pdfImg.setWidth(UnitValue.createPercentValue(85));
                pdfImg.setHorizontalAlignment(HorizontalAlignment.CENTER);
                doc.add(pdfImg);
                doc.add(new Paragraph(String.format(
                        "● intact: %d   ● granulated: %d   ·   зелёный квадрат — область, увеличенная ниже",
                        counts[0], counts[1]))
                        .setFontSize(8).setFontColor(ColorConstants.GRAY)
                        .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));
            } else {
                doc.add(new Paragraph("[Регион HPF недоступен]").setFontColor(ColorConstants.GRAY));
                return;
            }
        } catch (Exception e) {
            log.warn("HPF overview render failed: {}", e.getMessage(), e);
        }

        // 2. Zoom inset — центральный фрагмент ~250×250 µm в нативном разрешении
        try {
            int outDim = 1100;
            byte[] zoomBytes = tileService.renderRegion(
                    slide.getId(), zoomX, zoomY, zoomPxOnWsi, zoomPxOnWsi, outDim);
            if (zoomBytes != null) {
                doc.add(new Paragraph("Увеличение центральной области (~250×250 µm)")
                        .setBold().setFontSize(11).setMarginTop(4).setMarginBottom(2));
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(zoomBytes));
                int[] counts = drawDetections(img, allDetections,
                        zoomX, zoomY, zoomPxOnWsi, zoomPxOnWsi, 9);
                BufferedImage withBar = drawScaleBar(img, mpp, zoomPxOnWsi);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(96 * 1024);
                ImageIO.write(withBar, "jpg", baos);

                Image pdfImg = new Image(ImageDataFactory.create(baos.toByteArray()));
                pdfImg.setWidth(UnitValue.createPercentValue(60));
                pdfImg.setHorizontalAlignment(HorizontalAlignment.CENTER);
                doc.add(pdfImg);
                doc.add(new Paragraph(String.format(
                        "В этой области:  ● intact: %d   ● granulated: %d", counts[0], counts[1]))
                        .setFontSize(8).setFontColor(ColorConstants.GRAY)
                        .setTextAlignment(TextAlignment.CENTER));
            }
        } catch (Exception e) {
            log.warn("HPF zoom render failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Рисует на изображении кружки в местах детекций, попадающих в указанное окно WSI.
     * Координаты детекций (cx, cy) — глобальные WSI-px. Окно — [regionX, regionX+regionW] × Y.
     * Возвращает [intact_count, granulated_count] для подписи.
     */
    /**
     * Рисует кружки на изображении в местах детекций. Учитывает поле {@code cells}
     * (connected components от seg-маски): если bbox содержит N слипшихся клеток,
     * рисуется N кружков внутри bbox (а не один по центру), чтобы визуальный счёт
     * совпадал с {@code max_hpf_count}.
     *
     * Возвращает [intact_total_cells, granulated_total_cells].
     */
    private int[] drawDetections(BufferedImage img, List<Map<String, Object>> detections,
                                 int regionX, int regionY, int regionW, int regionH,
                                 int radiusPx) {
        if (detections == null || detections.isEmpty()) return new int[]{0, 0};
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2f));

        double sx = (double) img.getWidth()  / regionW;
        double sy = (double) img.getHeight() / regionH;
        int intact = 0, granulated = 0;

        for (Map<String, Object> d : detections) {
            double cx = num(d.get("cx"));
            double cy = num(d.get("cy"));
            if (cx < regionX || cx > regionX + regionW) continue;
            if (cy < regionY || cy > regionY + regionH) continue;

            String cls = String.valueOf(d.getOrDefault("cls", ""));
            boolean isIntact = "eos".equals(cls);
            int cells = Math.max(1, (int) num(d.get("cells")));

            Color fill = isIntact
                    ? new Color(255, 50, 50, 140)
                    : new Color(255, 200, 0, 140);

            // Размер bbox для распределения точек, если cells>1
            double w = Math.max(0, num(d.get("x2")) - num(d.get("x1")));
            double h = Math.max(0, num(d.get("y2")) - num(d.get("y1")));
            double bboxWpx = w * sx;
            double bboxHpx = h * sy;

            for (int i = 0; i < cells; i++) {
                double offsetX = 0, offsetY = 0;
                if (cells > 1) {
                    // Разнос точек по сетке внутри bbox (квадрат ceil(sqrt(N)))
                    int side = (int) Math.ceil(Math.sqrt(cells));
                    int row = i / side;
                    int col = i % side;
                    double stepX = bboxWpx / (side + 1);
                    double stepY = bboxHpx / (side + 1);
                    offsetX = -bboxWpx / 2 + stepX * (col + 1);
                    offsetY = -bboxHpx / 2 + stepY * (row + 1);
                }
                int px = (int) Math.round((cx - regionX) * sx + offsetX);
                int py = (int) Math.round((cy - regionY) * sy + offsetY);

                g.setColor(fill);
                g.fillOval(px - radiusPx, py - radiusPx, radiusPx * 2, radiusPx * 2);
                g.setColor(Color.WHITE);
                g.drawOval(px - radiusPx, py - radiusPx, radiusPx * 2, radiusPx * 2);
            }

            if (isIntact) intact += cells;
            else granulated += cells;
        }
        g.dispose();
        return new int[]{intact, granulated};
    }

    private double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** Рисует зелёный квадрат — где будет zoom inset (центральная область HPF). */
    private BufferedImage drawZoomFrame(BufferedImage src, int hpfPxOnWsi, int zoomPxOnWsi) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double scale = (double) Math.max(w, h) / hpfPxOnWsi;
        int zoomPx = (int) Math.round(zoomPxOnWsi * scale);
        int x0 = (w - zoomPx) / 2;
        int y0 = (h - zoomPx) / 2;

        g.setColor(new Color(0, 200, 0, 220));
        g.setStroke(new BasicStroke(3f));
        g.drawRect(x0, y0, zoomPx, zoomPx);
        g.dispose();
        return out;
    }

    /**
     * Накладывает scale bar (5 целевых длин: 50/100/200/500/1000 µm) внизу справа.
     * Длина bar'а в пикселях = (length_um / mpp) × (renderedDim / regionDim).
     */
    private BufferedImage drawScaleBar(BufferedImage src, double mpp, int regionPxOnWsi) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Сколько µm на 1 пиксель отрендеренного изображения
        double umPerPx = mpp * regionPxOnWsi / (double) Math.max(w, h);

        // Подбираем «круглую» длину линейки, чтобы она занимала ~20-30% ширины
        int[] candidates = {1000, 500, 200, 100, 50, 20, 10};
        int chosenUm = 100;
        for (int c : candidates) {
            double px = c / umPerPx;
            if (px > w * 0.18 && px < w * 0.40) { chosenUm = c; break; }
        }
        int barPx = (int) Math.round(chosenUm / umPerPx);

        int padding = 12;
        int barH = 6;
        int x0 = w - padding - barPx;
        int y0 = h - padding - barH - 14;

        // Полупрозрачный фон под линейку для контраста
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(x0 - 10, y0 - 6, barPx + 20, barH + 28, 6, 6);
        // Сама линейка — белая с чёрной обводкой
        g.setColor(Color.WHITE);
        g.fillRect(x0, y0, barPx, barH);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x0, y0, barPx, barH);
        // Подпись
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        String label = chosenUm >= 1000
                ? (chosenUm / 1000) + " мм"
                : chosenUm + " µm";
        g.drawString(label, x0, y0 + barH + 13);
        g.dispose();
        return out;
    }

    private void renderSignoffOrDisclaimer(Document doc, CaseSignoff signoff, User signer) {
        doc.add(new Paragraph(" ").setMarginTop(10));
        if (signoff != null) {
            // Подпись врача — рамка зелёная
            Paragraph block = new Paragraph()
                    .setBackgroundColor(new DeviceRgb(238, 252, 238))
                    .setBorder(new SolidBorder(new DeviceRgb(80, 160, 80), 1f))
                    .setPadding(10);
            block.add(new Text("✓ ПОДПИСАНО ВРАЧОМ\n").setBold().setFontSize(11).setFontColor(new DeviceRgb(0, 120, 0)));
            String signerName = signer != null
                    ? (signer.getFullName() != null ? signer.getFullName() : signer.getEmail())
                    : "—";
            block.add(new Text(String.format("Врач: %s\n", signerName)).setFontSize(10));
            block.add(new Text(String.format("Дата подписи: %s\n",
                    signoff.getSignedAt().atZone(ZoneId.of("Europe/Moscow")).format(DATE_FMT))).setFontSize(10));
            block.add(new Text(String.format("Вердикт: %s\n", diagnosisLabel(signoff.getDiagnosis())))
                    .setFontSize(10).setBold());
            if (signoff.getComments() != null && !signoff.getComments().isBlank()) {
                block.add(new Text("Комментарий: " + signoff.getComments()).setFontSize(10));
            }
            doc.add(block);
        } else {
            // Disclaimer
            Paragraph block = new Paragraph()
                    .setBackgroundColor(new DeviceRgb(255, 247, 220))
                    .setBorder(new SolidBorder(new DeviceRgb(220, 170, 50), 1f))
                    .setPadding(10);
            block.add(new Text("⚠ ОТЧЁТ НЕ ЯВЛЯЕТСЯ ОКОНЧАТЕЛЬНЫМ ЗАКЛЮЧЕНИЕМ\n")
                    .setBold().setFontSize(11).setFontColor(new DeviceRgb(170, 110, 0)));
            block.add(new Text(
                    "Decision-support tool. Результат автоматического анализа " +
                    "носит вспомогательный характер и требует визуальной верификации " +
                    "врачом-патоморфологом. Окончательный диагноз ставится врачом " +
                    "на основании морфологической картины и клинических данных."
            ).setFontSize(9));
            doc.add(block);
        }
    }

    private void renderTechFooter(Document doc, Job job, List<PatchTask> patches) {
        doc.add(new Paragraph(" ").setMarginTop(8));
        doc.add(separator());
        long done    = patches.stream().filter(p -> "DONE".equals(p.getStatus())).count();
        long failed  = patches.stream().filter(p -> "FAILED".equals(p.getStatus())).count();
        long skipped = patches.stream().filter(p -> "SKIPPED_WHITE".equals(p.getStatus())).count();
        String tech = String.format(
                "Модель: %s   ·   обработано %d патчей (фон отсеян: %d, ошибок: %d)",
                orDash(job.getModelVersion()), done, skipped, failed);
        doc.add(new Paragraph(tech).setFontSize(7).setFontColor(ColorConstants.GRAY)
                .setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("EosinAI · автоматическое заключение · " + job.getId().toString().substring(0, 8))
                .setFontSize(7).setFontColor(ColorConstants.GRAY)
                .setTextAlignment(TextAlignment.CENTER));
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private LineSeparator separator() {
        var line = new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(0.5f);
        line.setColor(ColorConstants.LIGHT_GRAY);
        LineSeparator sep = new LineSeparator(line);
        sep.setMarginTop(4);
        sep.setMarginBottom(8);
        return sep;
    }

    private void addInfoRow(Table t, String key, String value) {
        t.addCell(noBorderCell(new Paragraph(key).setFontSize(9).setFontColor(ColorConstants.GRAY)));
        t.addCell(noBorderCell(new Paragraph(value).setFontSize(10)));
    }

    private void addMetricRow(Table t, String key, String value) {
        t.addCell(softCell(new Paragraph(key).setFontSize(9).setFontColor(ColorConstants.DARK_GRAY)));
        t.addCell(softCell(new Paragraph(value).setFontSize(10).setBold()));
    }

    private Cell noBorderCell(Paragraph content) {
        Cell c = new Cell().add(content);
        c.setBorder(Border.NO_BORDER);
        c.setPadding(2);
        return c;
    }

    private Cell softCell(Paragraph content) {
        Cell c = new Cell().add(content);
        c.setBorder(new SolidBorder(new DeviceRgb(230, 230, 230), 0.5f));
        c.setPadding(4);
        return c;
    }

    private String diagnosisLabel(String d) {
        if (d == null) return "—";
        return switch (d) {
            case "POSITIVE"     -> "ПОЛОЖИТЕЛЬНО (EoE)";
            case "NEGATIVE"     -> "ОТРИЦАТЕЛЬНО";
            case "INCONCLUSIVE" -> "СОМНИТЕЛЬНО";
            default              -> d;
        };
    }

    private String biopsyLocationLabel(String loc) {
        if (loc == null) return "не указана";
        return switch (loc) {
            case "PROXIMAL" -> "проксимальный отдел";
            case "MID"      -> "средний отдел";
            case "DISTAL"   -> "дистальный отдел";
            case "OTHER"    -> "другая";
            default          -> loc;
        };
    }

    private String mppSourceLabel(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "METADATA" -> "OME-XML метаданные WSI";
            case "MANUAL"   -> "ручная калибровка";
            case "DEFAULT"  -> "значение по умолчанию (×40, 0.25 µm/px)";
            default          -> s;
        };
    }

    private String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /**
     * Подгружает TTF из classpath через PdfFontFactory с IDENTITY_H (full Unicode).
     * Возвращает null если файл не найден — тогда останется дефолт (Helvetica) без кириллицы.
     */
    private PdfFont loadCyrillicFont(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("Cyrillic font not found at classpath:{} — install DejaVuSans*.ttf to src/main/resources/fonts/",
                        resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        } catch (Exception e) {
            log.error("Failed to load Cyrillic font {}: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    private byte[] downloadBytes(String minioPath) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(minioPath).build())) {
            return in.readAllBytes();
        }
    }
}
