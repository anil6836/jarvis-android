package com.anil.jarvis;

/**
 * Real FFT of a power-of-two size, in the "half-complex" packing the echo canceller's maths uses (as FFTPACK):
 * [re0, re1, im1, re2, im2, ..., re(n/2)]. forward() is scaled by 1/n, inverse() is not, so inverse(forward(x)) = x.
 */
final class EchoFft {
    final int n;
    private final int h;            // n/2: the complex FFT size
    private final double[] tc, ts;  // twiddles of the n/2 complex FFT: cos / sin of 2 pi t / (n/2)
    private final double[] wc, ws;   // e^{-j 2 pi k / n}, k < n/2 (the real/complex split)
    private final int[] rev;
    private final double[] re, im;

    EchoFft(int n) {
        if (n < 4 || (n & (n - 1)) != 0) throw new IllegalArgumentException("size " + n);
        this.n = n;
        h = n / 2;
        tc = new double[h];
        ts = new double[h];
        for (int t = 0; t < h; t++) {
            tc[t] = Math.cos(2 * Math.PI * t / h);
            ts[t] = Math.sin(2 * Math.PI * t / h);
        }
        wc = new double[h + 1];
        ws = new double[h + 1];
        for (int k = 0; k <= h; k++) {
            wc[k] = Math.cos(2 * Math.PI * k / n);
            ws[k] = -Math.sin(2 * Math.PI * k / n);
        }
        rev = new int[h];
        int bits = Integer.numberOfTrailingZeros(h);
        for (int i = 0; i < h; i++) rev[i] = bits == 0 ? 0 : Integer.reverse(i) >>> (32 - bits);
        re = new double[h];
        im = new double[h];
    }

    /** In-place complex FFT of re/im (size n/2); sign -1 forward, +1 inverse (unscaled). */
    private void complexFft(int sign) {
        for (int i = 0; i < h; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= h; len <<= 1) {
            int half = len >> 1, step = h / len;
            for (int i = 0; i < h; i += len) {
                for (int k = 0; k < half; k++) {
                    int t = k * step; // twiddle e^{sign j 2 pi t / h}
                    double c = tc[t], s = sign * ts[t];
                    int a = i + k, b = a + half;
                    double xr = re[b] * c - im[b] * s, xi = re[b] * s + im[b] * c;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /** out = packed DFT(in) / n. in and out may not be the same array. */
    void forward(double[] in, double[] out) {
        for (int m = 0; m < h; m++) {
            re[m] = in[2 * m];
            im[m] = in[2 * m + 1];
        }
        complexFft(-1);
        double scale = 1.0 / n;
        for (int k = 0; k <= h / 2; k++) {
            int k2 = (h - k) % h;
            // A_k = (Z_k + conj Z_{-k}) / 2, B_k = (Z_k - conj Z_{-k}) / 2j, X_k = A_k + W^k B_k (and X_{h-k} likewise)
            double zr = re[k], zi = im[k], cr = re[k2], ci = -im[k2];
            double ar = (zr + cr) / 2, ai = (zi + ci) / 2;
            double br = (zi - ci) / 2, bi = -(zr - cr) / 2;
            double xr = ar + wc[k] * br - ws[k] * bi, xi = ai + wc[k] * bi + ws[k] * br;
            put(out, k, xr * scale, xi * scale);
            if (k != 0 && k != h - k) {
                int kk = h - k;
                double zr2 = re[kk], zi2 = im[kk], cr2 = re[k], ci2 = -im[k];
                double ar2 = (zr2 + cr2) / 2, ai2 = (zi2 + ci2) / 2;
                double br2 = (zi2 - ci2) / 2, bi2 = -(zr2 - cr2) / 2;
                double xr2 = ar2 + wc[kk] * br2 - ws[kk] * bi2, xi2 = ai2 + wc[kk] * bi2 + ws[kk] * br2;
                put(out, kk, xr2 * scale, xi2 * scale);
            }
        }
        // X_{n/2} = A_0 - B_0
        double a0 = re[0], b0 = im[0];
        out[n - 1] = (a0 - b0) * scale;
        out[0] = (a0 + b0) * scale;
    }

    private void put(double[] out, int k, double r, double i) {
        if (k == 0) { out[0] = r; return; }
        out[2 * k - 1] = r;
        out[2 * k] = i;
    }

    private double getRe(double[] x, int k) { return k == 0 ? x[0] : k == h ? x[n - 1] : x[2 * k - 1]; }
    private double getIm(double[] x, int k) { return k == 0 || k == h ? 0 : x[2 * k]; }

    /** out = sum_k X_k e^{+j 2 pi k t / n} (the full Hermitian spectrum of the packed in), unscaled. */
    void inverse(double[] in, double[] out) {
        for (int k = 0; k < h; k++) {
            // E_k = X_k + conj X_{h-k}, O_k = (X_k - conj X_{h-k}) e^{+j 2 pi k / n}, Z_k = E_k + j O_k
            double xr = getRe(in, k), xi = getIm(in, k);
            double yr = getRe(in, h - k), yi = -getIm(in, h - k);
            double er = xr + yr, ei = xi + yi;
            double dr = xr - yr, di = xi - yi;
            double c = wc[k], s = -ws[k]; // e^{+j 2 pi k / n}
            double or = dr * c - di * s, oi = dr * s + di * c;
            re[k] = er - oi;
            im[k] = ei + or;
        }
        complexFft(1);
        for (int m = 0; m < h; m++) {
            out[2 * m] = re[m];
            out[2 * m + 1] = im[m];
        }
    }
}
