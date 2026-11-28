package com.anil.jarvis;

/**
 * How several photos sit in the one numbered picture the AI gets (no Android here: desk-tested). Each cell has the
 * photos' own shape — a tall phone photo gets a tall cell, not a small strip in the middle of a wide one with black
 * bars round it — and the columns are chosen so every photo gets as many pixels as the picture's size allows.
 */
final class GridPlan {
    private GridPlan() {}

    static final int GAP = 10;

    /** {columns, rows, cell width, cell height} for n photos of shape aspect (width / height) inside maxSide × maxSide. */
    static int[] plan(int n, float aspect, int maxSide, int maxCell) {
        n = Math.max(1, n);
        if (!(aspect > 0.1f && aspect < 10f)) aspect = 0.75f;
        int[] best = null;
        float bestArea = -1;
        for (int cols = 1; cols <= n; cols++) {
            int rows = (n + cols - 1) / cols;
            float cw = (maxSide - (cols + 1) * GAP) / (float) cols, ch = cw / aspect;
            if (rows * ch + (rows + 1) * GAP > maxSide) { ch = (maxSide - (rows + 1) * GAP) / (float) rows; cw = ch * aspect; }
            float big = Math.max(cw, ch);
            if (big > maxCell) { float s = maxCell / big; cw *= s; ch *= s; }
            if (cw < 8 || ch < 8) continue;
            float area = cw * ch - (cols * rows - n) * 0.01f; // (the same size: fewer empty cells)
            if (area > bestArea + 1) { bestArea = area; best = new int[]{cols, rows, Math.round(cw), Math.round(ch)}; }
        }
        return best == null ? new int[]{1, n, 64, 64} : best;
    }
}
