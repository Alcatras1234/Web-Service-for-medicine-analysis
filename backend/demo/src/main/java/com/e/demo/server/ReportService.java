package com.e.demo.server;

import com.e.demo.entity.Job;
import com.e.demo.entity.PatchTask;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.PatchTaskRepository;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.element.Image;
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
import java.util.UUID;

@Service
@Slf4j
public class ReportService {

    private static final int HPF_WINDOW_PX = 2144;

    private final JobRepository       jobRepository;
    private final PatchTaskRepository patchTaskRepository;
    private final MinioClient         minioClient;

    @Value("${minio.bucketName}")
    private String bucket;

    public ReportService(JobRepository jobRepository,
                         PatchTaskRepository patchTaskRepository,
                         @Qualifier("internalClient") MinioClient minioClient) {
        this.jobRepository       = jobRepository;
        this.patchTaskRepository = patchTaskRepository;
        this.minioClient         = minioClient;
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
        List<PatchTask> patches = patchTaskRepository.findAllByJobId(jobId);

        String heatmapPath = generateAndUploadHeatmap(job, patches);
        String reportPath  = generateAndUploadPdf(job, patches, heatmapPath);

        jobRepository.updateReportPaths(jobId, reportPath, heatmapPath, Instant.now());
        log.info("Report ready: job={} pdf={}", jobId, reportPath);
    }

    // ── Heatmap ───────────────────────────────────────────────────────────────

    private String generateAndUploadHeatmap(Job job, List<PatchTask> patches) throws Exception {
        if (patches.isEmpty()) return null;

        int slideW = patches.stream().mapToInt(p -> p.getX() + p.getWidth()).max().orElse(1000);
        int slideH = patches.stream().mapToInt(p -> p.getY() + p.getHeight()).max().orElse(1000);
        float scale = Math.min(2000f / slideW, 2000f / slideH);
        int imgW = (int) (slideW * scale);
        int imgH = (int) (slideH * scale);

        BufferedImage heatmap = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = heatmap.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 30, 30));
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
            int alpha  = count == 0 ? 40 : (int) (100 + ratio * 155);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, alpha)));
            g.fillRect(px, py, pw, ph);

            if (count > 0 && pw > 15 && ph > 15) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(7, Math.min(14, pw / 3))));
                g.drawString(String.valueOf(count), px + 2, py + ph - 3);
            }
        }

        // Красная рамка вокруг HPF-окна (2144×2144)
        if (job.getMaxHpfX() != null && job.getMaxHpfY() != null) {
            int px = (int) (job.getMaxHpfX() * scale);
            int py = (int) (job.getMaxHpfY() * scale);
            int pw = (int) (HPF_WINDOW_PX * scale);
            int ph = (int) (HPF_WINDOW_PX * scale);
            g.setColor(new Color(255, 50, 50));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(px, py, pw, ph);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.drawString("MAX HPF: " + job.getMaxHpfCount(), px + 3, Math.max(12, py - 4));
        }

        drawLegend(g, imgW, imgH, maxCount);
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

    private void drawLegend(Graphics2D g, int imgW, int imgH, int maxCount) {
        int legX = imgW - 120, legY = imgH - 160, legW = 20, legH = 120;
        for (int i = 0; i < legH; i++) {
            g.setColor(heatColor(1f - (float) i / legH));
            g.fillRect(legX, legY + i, legW, 1);
        }
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(legX, legY, legW, legH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString(maxCount + " eos", legX + legW + 4, legY + 10);
        g.drawString("0 eos",           legX + legW + 4, legY + legH);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("Плотность", legX - 10, legY - 5);
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    private String generateAndUploadPdf(Job job, List<PatchTask> patches,
                                        String heatmapMinioPath) throws Exception {
        ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
        PdfWriter   writer = new PdfWriter(pdfBaos);
        PdfDocument pdf    = new PdfDocument(writer);
        Document    doc    = new Document(pdf);

        String dateStr = LocalDateTime.now(ZoneId.of("Europe/Moscow"))
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // Заголовок
        doc.add(new Paragraph("EosinAI — Отчёт анализа препарата")
                .setFontSize(18).setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(new DeviceRgb(30, 30, 120)));
        doc.add(new Paragraph("Дата анализа: " + dateStr)
                .setFontSize(10).setTextAlignment(TextAlignment.CENTER)
                .setFontColor(ColorConstants.GRAY));
        doc.add(new Paragraph(" "));

        // Диагноз
        boolean    isPositive = "POSITIVE".equals(job.getDiagnosis());
        DeviceRgb  diagColor  = isPositive ? new DeviceRgb(180, 0, 0) : new DeviceRgb(0, 120, 0);
        String     diagText   = isPositive
                ? "ДИАГНОЗ: ПОЛОЖИТЕЛЬНЫЙ (EoE подтверждён)"
                : "ДИАГНОЗ: ОТРИЦАТЕЛЬНЫЙ (EoE не выявлен)";
        doc.add(new Paragraph(diagText)
                .setFontSize(14).setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(diagColor)
                .setBackgroundColor(isPositive
                        ? new DeviceRgb(255, 235, 235)
                        : new DeviceRgb(235, 255, 235))
                .setPadding(8));
        doc.add(new Paragraph(" "));

        // Таблица метрик
        Table table = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .useAllAvailableWidth();
        addTableRow(table, "ID задачи",                   job.getId().toString());
        addTableRow(table, "ID препарата (slide)",        String.valueOf(job.getSlideId()));
        addTableRow(table, "Всего эозинофилов на слайде",
                String.valueOf(job.getTotalEosinophilCount() != null ? job.getTotalEosinophilCount() : 0));
        addTableRow(table, "Макс. эозинофилов в одном HPF",
                String.valueOf(job.getMaxHpfCount() != null ? job.getMaxHpfCount() : 0));
        addTableRow(table, "Координаты макс. HPF (x, y)", job.getMaxHpfX() + ", " + job.getMaxHpfY());
        addTableRow(table, "Порог диагноза EoE",          "≥ 15 эозинофилов / HPF");
        addTableRow(table, "Всего патчей обработано",     String.valueOf(patches.size()));
        long failed = patches.stream().filter(p -> "FAILED".equals(p.getStatus())).count();
        addTableRow(table, "Патчей с ошибкой",            String.valueOf(failed));
        doc.add(table);
        doc.add(new Paragraph(" "));

        // Тепловая карта
        if (heatmapMinioPath != null) {
            doc.add(new Paragraph("Тепловая карта распределения эозинофилов")
                    .setFontSize(12).setBold());
            doc.add(new Paragraph(
                    "Цветовая шкала: синий = 0 клеток, красный = максимум. " +
                    "Красная рамка — зона с наибольшей плотностью (HPF 2144×2144 px).")
                    .setFontSize(9).setFontColor(ColorConstants.GRAY));
            try {
                byte[] hm = downloadBytes(heatmapMinioPath);
                Image hmImg = new Image(ImageDataFactory.create(hm));
                hmImg.setWidth(UnitValue.createPercentValue(100));
                doc.add(hmImg);
            } catch (Exception e) {
                log.warn("Heatmap unavailable: {}", e.getMessage());
                doc.add(new Paragraph("[Heatmap недоступен]").setFontColor(ColorConstants.GRAY));
            }
        }
        doc.add(new Paragraph(" "));

        // Топ-5 патчей
        doc.add(new Paragraph("Топ-5 зон с наибольшей плотностью эозинофилов")
                .setFontSize(12).setBold());
        Table topTable = new Table(UnitValue.createPercentArray(new float[]{10, 25, 25, 20, 20}))
                .useAllAvailableWidth();
        topTable.addHeaderCell(new Cell().add(new Paragraph("#").setBold()));
        topTable.addHeaderCell(new Cell().add(new Paragraph("Координата X").setBold()));
        topTable.addHeaderCell(new Cell().add(new Paragraph("Координата Y").setBold()));
        topTable.addHeaderCell(new Cell().add(new Paragraph("Эозинофилов").setBold()));
        topTable.addHeaderCell(new Cell().add(new Paragraph("Статус").setBold()));

        patches.stream()
                .filter(p -> p.getEosinophilCount() != null && p.getEosinophilCount() > 0)
                .sorted(Comparator.comparingInt(PatchTask::getEosinophilCount).reversed())
                .limit(5)
                .forEach(p -> {
                    topTable.addCell(new Cell().add(new Paragraph(String.valueOf(patches.indexOf(p) + 1))));
                    topTable.addCell(new Cell().add(new Paragraph(String.valueOf(p.getX()))));
                    topTable.addCell(new Cell().add(new Paragraph(String.valueOf(p.getY()))));
                    topTable.addCell(new Cell().add(new Paragraph(String.valueOf(p.getEosinophilCount())).setBold()));
                    topTable.addCell(new Cell().add(new Paragraph(p.getStatus())));
                });
        doc.add(topTable);
        doc.add(new Paragraph(" "));

        // Изображение патча с максимальным числом эозинофилов (FIX)
        doc.add(new Paragraph("Изображение наиболее поражённого патча")
                .setFontSize(12).setBold());
        patches.stream()
                .filter(p -> p.getEosinophilCount() != null && p.getEosinophilCount() > 0
                          && p.getMinioPath() != null)
                .max(Comparator.comparingInt(PatchTask::getEosinophilCount))
                .ifPresentOrElse(
                    maxPatch -> {
                        doc.add(new Paragraph(
                                "Координаты: x=" + maxPatch.getX() + ", y=" + maxPatch.getY() +
                                " | Эозинофилов: " + maxPatch.getEosinophilCount())
                                .setFontSize(10).setFontColor(ColorConstants.GRAY));
                        try {
                            byte[] patchBytes = downloadBytes(maxPatch.getMinioPath());
                            Image patchImg = new Image(ImageDataFactory.create(patchBytes));
                            patchImg.setWidth(UnitValue.createPercentValue(60));
                            doc.add(patchImg);
                        } catch (Exception e) {
                            log.warn("Patch image unavailable: {}", e.getMessage());
                            doc.add(new Paragraph("[Изображение недоступно]")
                                    .setFontColor(ColorConstants.GRAY));
                        }
                    },
                    () -> doc.add(new Paragraph("[Нет патчей с детекциями]")
                            .setFontColor(ColorConstants.GRAY))
                );

        // Подпись
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(
                "Отчёт сгенерирован автоматически системой EosinAI. " +
                "Результат не является медицинским заключением без подтверждения врача.")
                .setFontSize(8).setFontColor(ColorConstants.GRAY)
                .setTextAlignment(TextAlignment.CENTER));
        doc.close();

        String minioPath = "reports/" + job.getId() + "/report.pdf";
        byte[] pdfBytes  = pdfBaos.toByteArray();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(minioPath)
                .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                .contentType("application/pdf").build());
        return minioPath;
    }

    private void addTableRow(Table table, String key, String value) {
        table.addCell(new Cell().add(new Paragraph(key).setBold())
                .setBackgroundColor(new DeviceRgb(240, 240, 240)));
        table.addCell(new Cell().add(new Paragraph(value)));
    }

    private byte[] downloadBytes(String minioPath) throws Exception {
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(minioPath).build())) {
            return in.readAllBytes();
        }
    }
}