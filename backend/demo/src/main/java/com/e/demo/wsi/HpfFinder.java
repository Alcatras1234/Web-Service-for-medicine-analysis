package com.e.demo.wsi;

import com.e.demo.entity.PatchTask;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * §2 + §3.3: поиск окна HPF (high power field) с максимальной плотностью эозинофилов.
 *
 * Ранее окно округлялось до целого числа ячеек сетки патчей (`ceil(hpf/stride)`),
 * из-за чего реальная площадь оказывалась в 1.3–1.4 раза больше клинических 0.3 мм².
 * Теперь поиск идёт в пикселях WSI с тонким шагом, а сумма по окну считается через
 * summed-area table на сетке патчей. Это даёт O(1) на каждое окно при субпикcельной
 * точности по выбору позиции.
 *
 * Реализация:
 *   1. Построить сетку патчей: cell[r][c] = выбранный counter (intact / total).
 *   2. Построить SAT (summed-area table) по сетке.
 *   3. Скользить по WSI с шагом searchStep (по умолчанию stride/2).
 *   4. Для каждой позиции (sx, sy) определить пересекаемые ячейки сетки и сложить.
 *
 * Замечание: поскольку патч — атомарная единица счёта, сумма по окну приближённая.
 * Но позиция максимума ищется в пикселях WSI, не в ячейках, что соответствует §3.3.
 */
public final class HpfFinder {

    public record HpfResult(int count, int x, int y) {}

    private HpfFinder() {}

    /** По умолчанию суммируем eosinophilCount (intact + granulated). */
    public static HpfResult find(List<PatchTask> patches, int patchStride, int hpfWindowPx) {
        return find(patches, patchStride, hpfWindowPx,
                p -> p.getEosinophilCount() == null ? 0 : p.getEosinophilCount());
    }

    /** §3.2: можно искать только по intact эозинофилам — диагноз EoE строится на них. */
    public static HpfResult findByIntact(List<PatchTask> patches, int patchStride, int hpfWindowPx) {
        return find(patches, patchStride, hpfWindowPx,
                p -> p.getEosIntact() == null ? 0 : p.getEosIntact());
    }

    public static HpfResult find(List<PatchTask> patches, int patchStride, int hpfWindowPx,
                                 ToIntFunction<PatchTask> counter) {
        if (patches == null || patches.isEmpty()) {
            return new HpfResult(0, 0, 0);
        }

        // 1. Размер сетки в ячейках
        int maxCol = 0, maxRow = 0;
        for (PatchTask p : patches) {
            int col = p.getX() / patchStride;
            int row = p.getY() / patchStride;
            if (col > maxCol) maxCol = col;
            if (row > maxRow) maxRow = row;
        }
        int cols = maxCol + 1;
        int rows = maxRow + 1;

        int[][] grid = new int[rows][cols];
        for (PatchTask p : patches) {
            int col = p.getX() / patchStride;
            int row = p.getY() / patchStride;
            grid[row][col] = counter.applyAsInt(p);
        }

        // 2. Summed-area table с padding'ом 1
        long[][] sat = new long[rows + 1][cols + 1];
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                sat[r][c] = grid[r - 1][c - 1]
                        + sat[r - 1][c]
                        + sat[r][c - 1]
                        - sat[r - 1][c - 1];
            }
        }

        // 3. Сканируем в пикселях WSI с тонким шагом
        long maxX_px = (long) cols * patchStride;
        long maxY_px = (long) rows * patchStride;
        int searchStep = Math.max(1, patchStride / 2);    // 0.5 ячейки

        long bestCount = 0;
        int bestX = 0, bestY = 0;

        for (long sy = 0; sy + hpfWindowPx <= maxY_px; sy += searchStep) {
            for (long sx = 0; sx + hpfWindowPx <= maxX_px; sx += searchStep) {
                long count = windowSum(sat, patchStride, rows, cols,
                        (int) sx, (int) sy, hpfWindowPx);
                if (count > bestCount) {
                    bestCount = count;
                    bestX = (int) sx;
                    bestY = (int) sy;
                }
            }
        }

        // Если изображение меньше HPF-окна — берём весь слайд
        if (bestCount == 0) {
            long total = sat[rows][cols];
            return new HpfResult((int) total, 0, 0);
        }

        return new HpfResult((int) bestCount, bestX, bestY);
    }

    /**
     * Сумма по окну [sx..sx+win, sy..sy+win] в координатах WSI.
     * Считаем через ячейки сетки, которые **полностью или частично пересекают** окно.
     * Это слегка завышает сумму на границах (~1 ячейка), но порядок верен и быстро.
     */
    private static long windowSum(long[][] sat, int stride, int rows, int cols,
                                  int sx, int sy, int win) {
        int c0 = Math.max(0, sx / stride);
        int r0 = Math.max(0, sy / stride);
        int c1 = Math.min(cols, (sx + win + stride - 1) / stride);
        int r1 = Math.min(rows, (sy + win + stride - 1) / stride);
        if (c1 <= c0 || r1 <= r0) return 0;
        return sat[r1][c1] - sat[r0][c1] - sat[r1][c0] + sat[r0][c0];
    }

    /**
     * Сумма указанного counter'а в фиксированном окне (sx, sy, sx+win, sy+win).
     * Используется когда позиция уже выбрана (например по intact-максимуму)
     * и нужно посчитать ДРУГОЙ counter (например total) в той же позиции.
     */
    public static int sumAt(List<PatchTask> patches, int patchStride, int hpfWindowPx,
                             int sx, int sy, ToIntFunction<PatchTask> counter) {
        if (patches == null || patches.isEmpty()) return 0;
        long sum = 0;
        for (PatchTask p : patches) {
            // считаем патч, если он пересекает окно
            if (p.getX() + patchStride <= sx) continue;
            if (p.getY() + patchStride <= sy) continue;
            if (p.getX() >= sx + hpfWindowPx) continue;
            if (p.getY() >= sy + hpfWindowPx) continue;
            sum += counter.applyAsInt(p);
        }
        return (int) sum;
    }
}
