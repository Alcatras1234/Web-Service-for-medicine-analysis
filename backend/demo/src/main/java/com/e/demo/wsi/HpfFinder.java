package com.e.demo.wsi;

import com.e.demo.entity.PatchTask;

import java.util.List;

/**
 * E2: поиск окна HPF (high power field) с максимальной плотностью эозинофилов.
 *
 * Старая реализация — двойной цикл по сетке (W/step × H/step) и для каждого окна
 * линейный проход по всем патчам. На WSI 100k×100k с шагом 500px и ~2000 патчей
 * это ~10⁸ операций.
 *
 * Здесь мы пользуемся тем, что патчи уложены на регулярную решётку с шагом
 * patchStride пикселей: маппим каждый патч в ячейку (col, row) = (x/stride, y/stride),
 * строим summed-area table и сканируем окна за O(1) на штуку.
 * Итог — O(N) по числу патчей.
 */
public final class HpfFinder {

    public record HpfResult(int count, int x, int y) {}

    private HpfFinder() {}

    public static HpfResult find(List<PatchTask> patches, int patchStride, int hpfWindowPx) {
        if (patches == null || patches.isEmpty()) {
            return new HpfResult(0, 0, 0);
        }

        // Размер сетки в ячейках
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
            grid[row][col] = p.getEosinophilCount();
        }

        // Summed-area table с padding'ом на 1 для удобства формулы
        long[][] sat = new long[rows + 1][cols + 1];
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                sat[r][c] = grid[r - 1][c - 1]
                          + sat[r - 1][c]
                          + sat[r][c - 1]
                          - sat[r - 1][c - 1];
            }
        }

        // Окно в ячейках сетки. Минимум 1, чтобы не вырождалось.
        int win = Math.max(1, (int) Math.ceil((double) hpfWindowPx / patchStride));
        // Если окно больше всей сетки — берём всю сетку
        int winR = Math.min(win, rows);
        int winC = Math.min(win, cols);

        long bestCount = 0;
        int bestX = 0, bestY = 0;

        // Скан с шагом 1 ячейка — стало возможно благодаря O(1) суммам
        for (int r = 0; r + winR <= rows; r++) {
            for (int c = 0; c + winC <= cols; c++) {
                long sum = sat[r + winR][c + winC]
                         - sat[r][c + winC]
                         - sat[r + winR][c]
                         + sat[r][c];
                if (sum > bestCount) {
                    bestCount = sum;
                    bestX = c * patchStride;
                    bestY = r * patchStride;
                }
            }
        }

        return new HpfResult((int) bestCount, bestX, bestY);
    }
}
