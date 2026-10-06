package com.anil.jarvis;

/*
 * Jarvis's own echo canceller: a Java port (floating point, one mic, one speaker) of the MDF echo canceller of
 * Speex DSP (libspeexdsp/mdf.c).
 *
 * Copyright (C) 2003-2008 Jean-Marc Valin
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The echo canceller is based on the MDF algorithm described in:
 * J. S. Soo, K. K. Pang, Multidelay block frequency adaptive filter, IEEE Trans. Acoust. Speech Signal Process.,
 * Vol. ASSP-38, No. 2, February 1990.
 * It uses the Alternatively Updated MDF (AUMDF) variant. Robustness to double-talk comes from a variable learning rate
 * as described in: Valin, J.-M., On Adjusting the Learning Rate in Frequency Domain Echo Cancellation With Double-Talk.
 * IEEE Transactions on Audio, Speech and Language Processing, Vol. 15, No. 3, pp. 1030-1034, 2007.
 * A foreground filter and a background filter (TWO_PATH) make it robust to double-talk and difficult signals.
 */

/**
 * Takes Jarvis's own voice (the far end, as played) out of the mic. One frame at a time: the mic frame and the far-end
 * frame that was playing at the same moment (the far end must lead the echo).
 */
final class EchoMdf {
    private static final double MIN_LEAK = .005;
    private static final double VAR1_SMOOTH = .36, VAR2_SMOOTH = .7225, VAR1_UPDATE = .5, VAR2_UPDATE = .25, VAR_BACKTRACK = 4.;

    final int frameSize, windowSize, m, rate;
    private int cancelCount;
    private boolean adapted;
    private int saturated, screwedUp;
    private final double specAverage, beta0, betaMax;
    private double sumAdapt;
    private double leakEstimate;

    private final double[] e, x, X, input, y, lastY, Y, E, PHI, W, foreground;
    private double davg1, davg2, dvar1, dvar2;
    private final double[] power, power1, wtmp, Rf, Yf, Xf, Eh, Yh;
    private double pey, pyy;
    private final double[] window, prop;
    private final EchoFft fft;
    private double memX, memD, memE;
    private final double preemph, notchRadius;
    private final double[] notchMem = new double[2];

    /** Statistics of the last frame (for Jarvis's own checks): energies of the mic, the output, the far end. */
    double lastSdd, lastSff, lastSxx;
    /** The canceller started over (it was adding echo instead of removing it). */
    int resets;

    EchoMdf(int frameSize, int filterLength, int rate) {
        this.frameSize = frameSize;
        this.rate = rate;
        windowSize = 2 * frameSize;
        int n = windowSize;
        m = (filterLength + frameSize - 1) / frameSize;
        specAverage = (double) frameSize / rate;
        beta0 = (2.0 * frameSize) / rate;
        betaMax = (.5 * frameSize) / rate;
        fft = new EchoFft(n);
        e = new double[n];
        x = new double[n];
        input = new double[frameSize];
        y = new double[n];
        lastY = new double[n];
        Yf = new double[frameSize + 1];
        Rf = new double[frameSize + 1];
        Xf = new double[frameSize + 1];
        Yh = new double[frameSize + 1];
        Eh = new double[frameSize + 1];
        X = new double[(m + 1) * n];
        Y = new double[n];
        E = new double[n];
        W = new double[m * n];
        foreground = new double[m * n];
        PHI = new double[n];
        power = new double[frameSize + 1];
        power1 = new double[frameSize + 1];
        window = new double[n];
        prop = new double[m];
        wtmp = new double[n];
        for (int i = 0; i < n; i++) window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / n);
        for (int i = 0; i <= frameSize; i++) power1[i] = 1;
        double decay = Math.exp(-2.4 / m); // ratio of ~10 between the adaptation rate of the first and last block
        prop[0] = .7;
        double sum = prop[0];
        for (int i = 1; i < m; i++) {
            prop[i] = prop[i - 1] * decay;
            sum += prop[i];
        }
        for (int i = m - 1; i >= 0; i--) prop[i] = .8 * prop[i] / sum;
        preemph = .9;
        notchRadius = rate < 12000 ? .9 : rate < 24000 ? .982 : .992;
        pey = pyy = 1;
    }

    /** Starts over (the echo path is learnt again). */
    void reset() {
        cancelCount = 0;
        screwedUp = 0;
        java.util.Arrays.fill(W, 0);
        java.util.Arrays.fill(foreground, 0);
        java.util.Arrays.fill(X, 0);
        java.util.Arrays.fill(power, 0);
        java.util.Arrays.fill(power1, 1);
        java.util.Arrays.fill(Eh, 0);
        java.util.Arrays.fill(Yh, 0);
        java.util.Arrays.fill(lastY, 0);
        java.util.Arrays.fill(E, 0);
        java.util.Arrays.fill(x, 0);
        notchMem[0] = notchMem[1] = 0;
        memD = memE = memX = 0;
        saturated = 0;
        adapted = false;
        sumAdapt = 0;
        pey = pyy = 1;
        davg1 = davg2 = 0;
        dvar1 = dvar2 = 0;
    }

    boolean adapted() { return adapted; }
    double leak() { return leakEstimate; }

    private void dcNotch(short[] in, int off, double[] out) {
        double radius = notchRadius;
        double den2 = radius * radius + .7 * (1 - radius) * (1 - radius);
        for (int i = 0; i < frameSize; i++) {
            double vin = in[off + i];
            double vout = notchMem[0] + vin;
            notchMem[0] = notchMem[1] + 2 * (-vin + radius * vout);
            notchMem[1] = vin - den2 * vout;
            out[i] = radius * vout;
        }
    }

    private static double innerProd(double[] a, int ao, double[] b, int bo, int len) {
        double sum = 0;
        for (int i = 0; i < len; i++) sum += a[ao + i] * b[bo + i];
        return sum;
    }

    /** Power spectrum of a packed vector, added to ps (frameSize + 1 bins). */
    private void powerSpectrumAccum(double[] v, int off, double[] ps) {
        int n = windowSize;
        ps[0] += v[off] * v[off];
        int i, j;
        for (i = 1, j = 1; i < n - 1; i += 2, j++) ps[j] += v[off + i] * v[off + i] + v[off + i + 1] * v[off + i + 1];
        ps[j] += v[off + i] * v[off + i];
    }

    /** acc = sum over the m blocks of X(block) * w(block) (complex products of packed vectors). */
    private void spectralMulAccum(double[] xs, double[] w, double[] acc) {
        int n = windowSize;
        java.util.Arrays.fill(acc, 0);
        for (int j = 0; j < m; j++) {
            int xo = j * n, wo = j * n;
            acc[0] += xs[xo] * w[wo];
            int i;
            for (i = 1; i < n - 1; i += 2) {
                acc[i] += xs[xo + i] * w[wo + i] - xs[xo + i + 1] * w[wo + i + 1];
                acc[i + 1] += xs[xo + i + 1] * w[wo + i] + xs[xo + i] * w[wo + i + 1];
            }
            acc[i] += xs[xo + i] * w[wo + i];
        }
    }

    /** prod = p * w[bin] * conj(X) * Y (packed). */
    private void weightedSpectralMulConj(double[] w, double p, double[] xs, int xo, double[] ys, double[] prod) {
        int n = windowSize;
        double ww = p * w[0];
        prod[0] = ww * (xs[xo] * ys[0]);
        int i, j;
        for (i = 1, j = 1; i < n - 1; i += 2, j++) {
            ww = p * w[j];
            prod[i] = ww * (xs[xo + i] * ys[i] + xs[xo + i + 1] * ys[i + 1]);
            prod[i + 1] = ww * (-xs[xo + i + 1] * ys[i] + xs[xo + i] * ys[i + 1]);
        }
        ww = p * w[j];
        prod[i] = ww * (xs[xo + i] * ys[i]);
    }

    private void adjustProp() {
        int n = windowSize;
        double maxSum = 1, propSum = 1;
        for (int i = 0; i < m; i++) {
            double tmp = 1;
            for (int j = 0; j < n; j++) tmp += W[i * n + j] * W[i * n + j];
            prop[i] = Math.sqrt(tmp);
            if (prop[i] > maxSum) maxSum = prop[i];
        }
        for (int i = 0; i < m; i++) {
            prop[i] += .1 * maxSum;
            propSum += prop[i];
        }
        for (int i = 0; i < m; i++) prop[i] = .99 * prop[i] / propSum;
    }

    private static short toShort(double v) {
        return v < -32767.5 ? -32768 : v > 32766.5 ? 32767 : (short) Math.floor(.5 + v);
    }

    /**
     * One frame: in (the mic) and far (Jarvis's voice that was playing then), frameSize samples each; out gets the mic
     * without Jarvis's echo.
     */
    void cancel(short[] in, int inOff, short[] far, int farOff, short[] out, int outOff) {
        int n = windowSize, fs = frameSize;
        cancelCount++;
        double ss = .35 / m, ss1 = 1 - ss;

        // a notch filter so DC doesn't cause problems, and pre-emphasis
        dcNotch(in, inOff, input);
        for (int i = 0; i < fs; i++) {
            double tmp = input[i] - preemph * memD;
            memD = input[i];
            input[i] = tmp;
        }
        for (int i = 0; i < fs; i++) {
            x[i] = x[i + fs];
            double tmp = far[farOff + i] - preemph * memX;
            x[i + fs] = tmp;
            memX = far[farOff + i];
        }
        // shift the far-end spectra and add the new one
        System.arraycopy(X, 0, X, n, m * n);
        fft.forward(x, wtmp);
        System.arraycopy(wtmp, 0, X, 0, n);

        double sxx = innerProd(x, fs, x, fs, fs);
        java.util.Arrays.fill(Xf, 0);
        powerSpectrumAccum(X, 0, Xf);

        // foreground filter
        spectralMulAccum(X, foreground, Y);
        fft.inverse(Y, e);
        for (int i = 0; i < fs; i++) e[i] = input[i] - e[i + fs];
        double sff = innerProd(e, 0, e, 0, fs);

        // proportional adaptation rate
        if (adapted) adjustProp();
        // weight gradient
        if (saturated == 0) {
            for (int j = m - 1; j >= 0; j--) {
                weightedSpectralMulConj(power1, prop[j], X, (j + 1) * n, E, PHI);
                int wo = j * n;
                for (int i = 0; i < n; i++) W[wo + i] += PHI[i];
            }
        } else {
            saturated--;
        }
        // constrain the weights against circular convolution (AUMDF: block 0 and one other block each frame)
        for (int j = 0; j < m; j++) {
            if (j == 0 || (m > 1 && cancelCount % (m - 1) == j - 1)) {
                int wo = j * n;
                System.arraycopy(W, wo, PHI, 0, n);
                fft.inverse(PHI, wtmp);
                for (int i = fs; i < n; i++) wtmp[i] = 0;
                fft.forward(wtmp, PHI);
                System.arraycopy(PHI, 0, W, wo, n);
            }
        }

        java.util.Arrays.fill(Rf, 0);
        java.util.Arrays.fill(Yf, 0);
        java.util.Arrays.fill(Xf, 0);

        // background filter: difference in response (for the variance of the residual power estimate)
        spectralMulAccum(X, W, Y);
        fft.inverse(Y, y);
        for (int i = 0; i < fs; i++) e[i] = e[i + fs] - y[i + fs];
        double dbf = 10 + innerProd(e, 0, e, 0, fs);
        for (int i = 0; i < fs; i++) e[i] = input[i] - y[i + fs];
        double see = innerProd(e, 0, e, 0, fs);

        // updating the foreground filter: two time windows, mean of the energy difference and its variance
        davg1 = .6 * davg1 + .4 * (sff - see);
        davg2 = .85 * davg2 + .15 * (sff - see);
        dvar1 = VAR1_SMOOTH * dvar1 + (.4 * sff) * (.4 * dbf);
        dvar2 = VAR2_SMOOTH * dvar2 + (.15 * sff) * (.15 * dbf);
        boolean updateForeground = false;
        if ((sff - see) * Math.abs(sff - see) > sff * dbf) updateForeground = true;
        else if (davg1 * Math.abs(davg1) > VAR1_UPDATE * dvar1) updateForeground = true;
        else if (davg2 * Math.abs(davg2) > VAR2_UPDATE * dvar2) updateForeground = true;
        if (updateForeground) {
            davg1 = davg2 = 0;
            dvar1 = dvar2 = 0;
            System.arraycopy(W, 0, foreground, 0, m * n);
            // a smooth transition, no blocking artifacts
            for (int i = 0; i < fs; i++) e[i + fs] = window[i + fs] * e[i + fs] + window[i] * y[i + fs];
        } else {
            boolean resetBackground = false;
            if (-(sff - see) * Math.abs(sff - see) > VAR_BACKTRACK * (sff * dbf)) resetBackground = true;
            if (-davg1 * Math.abs(davg1) > VAR_BACKTRACK * dvar1) resetBackground = true;
            if (-davg2 * Math.abs(davg2) > VAR_BACKTRACK * dvar2) resetBackground = true;
            if (resetBackground) {
                System.arraycopy(foreground, 0, W, 0, m * n);
                for (int i = 0; i < fs; i++) y[i + fs] = e[i + fs];
                for (int i = 0; i < fs; i++) e[i] = input[i] - y[i + fs];
                see = sff;
                davg1 = davg2 = 0;
                dvar1 = dvar2 = 0;
            }
        }

        // the output (with de-emphasis)
        boolean clipped = false;
        for (int i = 0; i < fs; i++) {
            double tmpOut = input[i] - e[i + fs];
            tmpOut = tmpOut + preemph * memE;
            int raw = in[inOff + i];
            if (raw <= -32000 || raw >= 32000) clipped = true;
            out[outOff + i] = toShort(tmpOut);
            memE = tmpOut;
        }
        if (clipped && saturated == 0) saturated = 1;

        // the error for the filter update
        for (int i = 0; i < fs; i++) {
            e[i + fs] = e[i];
            e[i] = 0;
        }
        double sey = innerProd(e, fs, y, fs, fs);
        double syy = innerProd(y, fs, y, fs, fs);
        double sdd = innerProd(input, 0, input, 0, fs);

        fft.forward(e, E);
        for (int i = 0; i < fs; i++) y[i] = 0;
        fft.forward(y, Y);
        powerSpectrumAccum(E, 0, Rf);
        powerSpectrumAccum(Y, 0, Yf);

        lastSdd = sdd;
        lastSff = sff;

        // sanity checks
        if (!(syy >= 0 && sxx >= 0 && see >= 0) || !(sff < n * 1e9 && syy < n * 1e9 && sxx < n * 1e9)) {
            screwedUp += 50; // things have gone really bad
            for (int i = 0; i < fs; i++) out[outOff + i] = 0;
        } else if (sff > sdd + n * 10000.0) {
            screwedUp++; // adding lots of echo instead of removing it: see if it improves
        } else {
            screwedUp = 0;
        }
        if (screwedUp >= 50) {
            resets++;
            reset();
            return;
        }

        see = Math.max(see, n * 100.0);
        sxx += innerProd(x, fs, x, fs, fs);
        powerSpectrumAccum(X, 0, Xf);
        lastSxx = sxx;

        // far-end energy smoothed over time
        for (int j = 0; j <= fs; j++) power[j] = ss1 * power[j] + 1 + ss * Xf[j];

        // filtered spectra and (cross-)correlations
        double pEy = 1, pYy = 1;
        for (int j = fs; j >= 0; j--) {
            double eh = Rf[j] - Eh[j];
            double yh = Yf[j] - Yh[j];
            pEy += eh * yh;
            pYy += yh * yh;
            Eh[j] = (1 - specAverage) * Eh[j] + specAverage * Rf[j];
            Yh[j] = (1 - specAverage) * Yh[j] + specAverage * Yf[j];
        }
        pYy = Math.sqrt(pYy);
        pEy = pEy / pYy;

        // correlation update rate
        double tmp32 = beta0 * syy;
        if (tmp32 > betaMax * see) tmp32 = betaMax * see;
        double alpha = tmp32 / see, alpha1 = 1 - alpha;
        pey = alpha1 * pey + alpha * pEy;
        pyy = alpha1 * pyy + alpha * pYy;
        if (pyy < 1) pyy = 1;
        if (pey < MIN_LEAK * pyy) pey = MIN_LEAK * pyy; // no hope of better than 33 dB (MIN_LEAK - 3 dB) anyway
        if (pey > pyy) pey = pyy;
        leakEstimate = pey / pyy; // the linear regression result

        // residual to error ratio
        double rer = (.0001 * sxx + 3. * leakEstimate * syy) / see;
        if (rer < sey * sey / (1 + see * syy)) rer = sey * sey / (1 + see * syy); // y in e: lower bound on RER
        if (rer > .5) rer = .5;

        // minimal adaptation reached
        if (!adapted && sumAdapt > m && leakEstimate * syy > .03 * syy) adapted = true;

        if (adapted) {
            for (int i = 0; i <= fs; i++) {
                double r = leakEstimate * Yf[i];
                double ee = Rf[i] + 1;
                if (r > .5 * ee) r = .5 * ee;
                r = .7 * r + .3 * (rer * ee);
                power1[i] = r / (ee * (power[i] + 10));
            }
        } else {
            double adaptRate = 0; // a temporary rate while the filter isn't adapted enough
            if (sxx > n * 1000.0) {
                double t = .25 * sxx;
                if (t > .25 * see) t = .25 * see;
                adaptRate = t / see;
            }
            for (int i = 0; i <= fs; i++) power1[i] = adaptRate / (power[i] + 10);
            sumAdapt += adaptRate;
        }

        System.arraycopy(lastY, fs, lastY, 0, fs);
        if (adapted) {
            for (int i = 0; i < fs; i++) lastY[fs + i] = in[inOff + i] - out[outOff + i]; // the filtered echo
        }
    }

    /** The power spectrum of the echo left after cancelling (frameSize + 1 bins), for the suppressor. */
    void residual(double[] residualEcho) {
        int n = windowSize;
        for (int i = 0; i < n; i++) y[i] = window[i] * lastY[i];
        fft.forward(y, Y);
        java.util.Arrays.fill(residualEcho, 0, frameSize + 1, 0);
        powerSpectrumAccum(Y, 0, residualEcho);
        double leak2 = leakEstimate > .5 ? 1 : 2 * leakEstimate;
        for (int i = 0; i <= frameSize; i++) residualEcho[i] = leak2 * residualEcho[i];
    }

    /** Energy of the foreground filter in each block of frameSize taps (where the echo sits in time). */
    double[] blockEnergy() {
        int n = windowSize;
        double[] out = new double[m];
        double[] tmp = new double[n], t = new double[n];
        for (int j = 0; j < m; j++) {
            System.arraycopy(foreground, j * n, tmp, 0, n);
            fft.inverse(tmp, t);
            double s = 0;
            for (int i = 0; i < frameSize; i++) s += t[i] * t[i];
            out[j] = s;
        }
        return out;
    }

    /** The learnt echo path (both filters and how much leaks through), to start the next talk with it. */
    double[] saveWeights() {
        double[] s = new double[2 * W.length + 2 + power.length];
        System.arraycopy(W, 0, s, 0, W.length);
        System.arraycopy(foreground, 0, s, W.length, W.length);
        s[2 * W.length] = pey;
        s[2 * W.length + 1] = pyy;
        System.arraycopy(power, 0, s, 2 * W.length + 2, power.length);
        return s;
    }

    /** Starts with an echo path learnt before (same sizes); it keeps learning from there. */
    void loadWeights(double[] s) {
        if (s == null || s.length != 2 * W.length + 2 + power.length) return;
        System.arraycopy(s, 0, W, 0, W.length);
        System.arraycopy(s, W.length, foreground, 0, W.length);
        pey = s[2 * W.length];
        pyy = s[2 * W.length + 1];
        if (!(pyy >= 1) || !(pey > 0) || pey > pyy) { pey = MIN_LEAK; pyy = 1; }
        leakEstimate = pey / pyy;
        System.arraycopy(s, 2 * W.length + 2, power, 0, power.length);
        for (int i = 0; i < power.length; i++) if (!(power[i] >= 0)) power[i] = 0;
        adapted = true; // (it is past its first learning: the leftover echo is estimated for the suppressor at once)
        sumAdapt = m + 1;
    }
}
