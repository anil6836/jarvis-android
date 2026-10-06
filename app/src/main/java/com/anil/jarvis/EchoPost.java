package com.anil.jarvis;

/*
 * The echo left after Jarvis's echo canceller is taken down further here: a Java port (floating point; noise and
 * residual-echo suppression only, no AGC / VAD / dereverb) of the Speex DSP preprocessor (libspeexdsp/preprocess.c and
 * filterbank.c).
 *
 * Copyright 2003 Epic Games (written by Jean-Marc Valin)
 * Copyright 2004-2006 Jean-Marc Valin
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *   disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of the Xiph.org Foundation nor the names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Based on: Y. Ephraim and D. Malah, "Speech enhancement using minimum mean-square error short-time spectral amplitude
 * estimator"; I. Cohen and B. Berdugo, "Speech enhancement for non-stationary noise environments"; and, for the echo,
 * Gustafsson, Martin, Jax and Vary, "A psychoacoustic approach to combined acoustic echo cancellation and noise
 * reduction".
 */

/** Suppresses what is left of Jarvis's echo (and, if asked, steady background noise) in the canceller's output. */
final class EchoPost {
    private static final int NB_BANDS = 24;
    private static final double[] HYPERGEOM = {
            0.82157, 1.02017, 1.20461, 1.37534, 1.53363, 1.68092, 1.81865,
            1.94811, 2.07038, 2.18638, 2.29688, 2.40255, 2.50391, 2.60144,
            2.69551, 2.78647, 2.87458, 2.96015, 3.04333, 3.12431, 3.20326};

    private final int frameSize, n, nb;
    private final EchoMdf echo;
    /** dB: how far steady noise is taken down (0: not at all), and Jarvis's echo (with and without his voice). */
    private int noiseSuppress = -15, echoSuppress = -40, echoSuppressActive = -15;

    private final int[] bankLeft, bankRight;
    private final double[] filterLeft, filterRight;
    private final double[] frame, window, ft, ps, noise, echoNoise, residualEcho, oldPs, prior, post, gain, gain2, gainFloor, zeta;
    private final double[] sS, sMin, sTmp;
    private final boolean[] updateProb;
    private final double[] inbuf, outbuf;
    private final EchoFft fft;
    private int nbAdapt, minCount;
    /** The speech probability of the last frame (his voice or Jarvis's leftover). */
    double speechProb;

    EchoPost(int frameSize, int rate, EchoMdf echo) {
        this.frameSize = frameSize;
        this.echo = echo;
        n = frameSize; // ps_size
        nb = NB_BANDS;
        bankLeft = new int[n];
        bankRight = new int[n];
        filterLeft = new double[n];
        filterRight = new double[n];
        double df = rate / (2.0 * n);
        double maxMel = toBark(rate / 2.0);
        double melInterval = maxMel / (nb - 1);
        for (int i = 0; i < n; i++) {
            double mel = toBark(i * df);
            if (mel > maxMel) break;
            int id1 = (int) Math.floor(mel / melInterval);
            double val;
            if (id1 > nb - 2) {
                id1 = nb - 2;
                val = 1;
            } else {
                val = (mel - id1 * melInterval) / melInterval;
            }
            bankLeft[i] = id1;
            filterLeft[i] = 1 - val;
            bankRight[i] = id1 + 1;
            filterRight[i] = val;
        }
        frame = new double[2 * n];
        window = new double[2 * n];
        ft = new double[2 * n];
        ps = new double[n + nb];
        noise = new double[n + nb];
        echoNoise = new double[n + nb];
        residualEcho = new double[n + nb];
        oldPs = new double[n + nb];
        prior = new double[n + nb];
        post = new double[n + nb];
        gain = new double[n + nb];
        gain2 = new double[n + nb];
        gainFloor = new double[n + nb];
        zeta = new double[n + nb];
        sS = new double[n];
        sMin = new double[n];
        sTmp = new double[n];
        updateProb = new boolean[n];
        int n3 = 2 * n - frameSize;
        inbuf = new double[n3];
        outbuf = new double[n3];
        conjWindow(window, 2 * n3);
        for (int i = 0; i < n + nb; i++) {
            noise[i] = 1;
            oldPs[i] = 1;
            gain[i] = 1;
            post[i] = 1;
            prior[i] = 1;
        }
        for (int i = 0; i < n; i++) updateProb[i] = true;
        fft = new EchoFft(2 * n);
    }

    /** Steady background noise: taken down this many dB (0 leaves it as it is; only Jarvis's echo is suppressed). */
    void setNoiseSuppress(int db) { noiseSuppress = -Math.abs(db); }

    /** Jarvis's leftover echo: taken down this many dB when he is quiet, and while he talks over it. */
    void setEchoSuppress(int db, int activeDb) {
        echoSuppress = -Math.abs(db);
        echoSuppressActive = -Math.abs(activeDb);
    }

    /** The leftover echo is counted this many times (more than the canceller's estimate: safer against its own voice). */
    private double echoOver = 1;
    void setEchoOverestimate(double k) { echoOver = Math.max(1, k); }

    private static double toBark(double f) {
        return 13.1 * Math.atan(.00074 * f) + 2.24 * Math.atan(f * f * 1.85e-8) + 1e-4 * f;
    }

    private static void conjWindow(double[] w, int len) {
        for (int i = 0; i < len; i++) {
            double x = 4.0 * i / len;
            boolean inv = false;
            if (x < 1) {
                // as it is
            } else if (x < 2) {
                x = 2 - x;
                inv = true;
            } else if (x < 3) {
                x = x - 2;
                inv = true;
            } else {
                x = 2 - x + 2; // 4 - x
            }
            x = 1.271903 * x;
            double c = .5 - .5 * Math.cos(.5 * Math.PI * x);
            double tmp = c * c;
            if (inv) tmp = 1 - tmp;
            w[i] = Math.sqrt(tmp);
        }
    }

    private void bank(double[] p, int off) { // the Bark bands of p[0..n) into p[off..off+nb)
        for (int i = 0; i < nb; i++) p[off + i] = 0;
        for (int i = 0; i < n; i++) {
            p[off + bankLeft[i]] += filterLeft[i] * p[i];
            p[off + bankRight[i]] += filterRight[i] * p[i];
        }
    }

    private void psd(double[] p, int off) { // the Bark bands p[off..off+nb) back to p[0..n)
        for (int i = 0; i < n; i++) p[i] = p[off + bankLeft[i]] * filterLeft[i] + p[off + bankRight[i]] * filterRight[i];
    }

    private static double hypergeomGain(double x) {
        double integer = Math.floor(2 * x);
        int ind = (int) integer;
        if (ind < 0) return 1;
        if (ind > 19) return 1 + .1296 / x;
        double frac = 2 * x - integer;
        return ((1 - frac) * HYPERGEOM[ind] + frac * HYPERGEOM[ind + 1]) / Math.sqrt(x + .0001);
    }

    private static double qcurve(double x) { return 1. / (1. + .15 / x); }

    private void analysis(short[] x, int off) {
        int n3 = 2 * n - frameSize, n4 = frameSize - n3;
        for (int i = 0; i < n3; i++) frame[i] = inbuf[i];
        for (int i = 0; i < frameSize; i++) frame[n3 + i] = x[off + i];
        for (int i = 0; i < n3; i++) inbuf[i] = x[off + n4 + i];
        for (int i = 0; i < 2 * n; i++) frame[i] *= window[i];
        fft.forward(frame, ft);
        ps[0] = ft[0] * ft[0];
        for (int i = 1; i < n; i++) ps[i] = ft[2 * i - 1] * ft[2 * i - 1] + ft[2 * i] * ft[2 * i];
        bank(ps, n);
    }

    private void updateNoiseProb() {
        for (int i = 1; i < n - 1; i++) sS[i] = .8 * sS[i] + .05 * ps[i - 1] + .1 * ps[i] + .05 * ps[i + 1];
        sS[0] = .8 * sS[0] + .2 * ps[0];
        sS[n - 1] = .8 * sS[n - 1] + .2 * ps[n - 1];
        if (nbAdapt == 1) {
            for (int i = 0; i < n; i++) sMin[i] = sTmp[i] = 0;
        }
        int minRange = nbAdapt < 100 ? 15 : nbAdapt < 1000 ? 50 : nbAdapt < 10000 ? 150 : 300;
        if (minCount > minRange) {
            minCount = 0;
            for (int i = 0; i < n; i++) {
                sMin[i] = Math.min(sTmp[i], sS[i]);
                sTmp[i] = sS[i];
            }
        } else {
            for (int i = 0; i < n; i++) {
                sMin[i] = Math.min(sMin[i], sS[i]);
                sTmp[i] = Math.min(sTmp[i], sS[i]);
            }
        }
        for (int i = 0; i < n; i++) updateProb[i] = .4 * sS[i] > sMin[i];
    }

    /** One frame of the canceller's output (frameSize samples), changed in place. */
    void run(short[] x, int off) {
        int m = nb;
        int n3 = 2 * n - frameSize, n4 = frameSize - n3;
        nbAdapt++;
        if (nbAdapt > 20000) nbAdapt = 20000;
        minCount++;
        double beta = Math.max(.03, 1.0 / nbAdapt), beta1 = 1 - beta;

        // the echo left after the canceller
        if (echo != null) {
            echo.residual(residualEcho);
            if (!(residualEcho[0] >= 0 && residualEcho[0] < n * 1e9)) {
                for (int i = 0; i < n; i++) residualEcho[i] = 0;
            }
            for (int i = 0; i < n; i++) echoNoise[i] = Math.max(.6 * echoNoise[i], echoOver * residualEcho[i]);
            bank(echoNoise, n);
        } else {
            for (int i = 0; i < n + m; i++) echoNoise[i] = 0;
        }
        analysis(x, off);
        updateNoiseProb();

        // the noise estimate, where it can be updated
        for (int i = 0; i < n; i++) {
            if (!updateProb[i] || ps[i] < noise[i]) noise[i] = Math.max(0, beta1 * noise[i] + beta * ps[i]);
        }
        bank(noise, n);

        if (nbAdapt == 1) System.arraycopy(ps, 0, oldPs, 0, n + m);

        // a posteriori and a priori SNR
        for (int i = 0; i < n + m; i++) {
            double totNoise = 1 + noise[i] + echoNoise[i]; // (the reverb estimate is 0)
            post[i] = ps[i] / totNoise - 1;
            post[i] = Math.min(post[i], 100);
            double r = oldPs[i] / (oldPs[i] + totNoise);
            double gamma = .1 + .89 * (r * r);
            prior[i] = gamma * Math.max(0, post[i]) + (1 - gamma) * (oldPs[i] / totNoise);
            prior[i] = Math.min(prior[i], 100);
        }

        // recursive average of the a priori SNR, a bit smoothed for the psd components
        zeta[0] = .7 * zeta[0] + .3 * prior[0];
        for (int i = 1; i < n - 1; i++) zeta[i] = .7 * zeta[i] + .15 * prior[i] + .075 * prior[i - 1] + .075 * prior[i + 1];
        for (int i = n - 1; i < n + m; i++) zeta[i] = .7 * zeta[i] + .3 * prior[i];

        // speech probability of the whole frame, from the average Bark a priori SNR
        double zframe = 0;
        for (int i = n; i < n + m; i++) zframe += zeta[i];
        double pframe = .1 + .899 * qcurve(zframe / nb);
        speechProb = pframe;

        double effectiveEchoSuppress = (1 - pframe) * echoSuppress + pframe * echoSuppressActive;
        // gain floors, different for the background noise and the residual echo
        double noiseFloor = Math.exp(.2302585 * noiseSuppress), echoFloor = Math.exp(.2302585 * effectiveEchoSuppress);
        for (int i = 0; i < m; i++) {
            double nz = noise[n + i], ec = echoNoise[n + i];
            gainFloor[n + i] = Math.sqrt(noiseFloor * nz + echoFloor * ec) / Math.sqrt(1 + nz + ec);
        }

        // Ephraim & Malah gain and speech probability for each Bark band
        for (int i = n; i < n + m; i++) {
            double priorRatio = prior[i] / (prior[i] + 1);
            double theta = priorRatio * (1 + post[i]);
            double mm = hypergeomGain(theta);
            gain[i] = Math.min(1, priorRatio * mm);
            oldPs[i] = .2 * oldPs[i] + .8 * (gain[i] * gain[i]) * ps[i];
            double p1 = .199 + .8 * qcurve(zeta[i]);
            double q = 1 - pframe * p1;
            gain2[i] = 1 / (1 + (q / (1 - q)) * (1 + prior[i]) * Math.exp(-theta));
        }
        psd(gain2, n);
        psd(gain, n);
        psd(gainFloor, n);

        // the gain at each frequency
        for (int i = 0; i < n; i++) {
            double priorRatio = prior[i] / (prior[i] + 1);
            double theta = priorRatio * (1 + post[i]);
            double mm = hypergeomGain(theta);
            double g = Math.min(1, priorRatio * mm);
            double p = gain2[i];
            if (.333 * g > gain[i]) g = 3 * gain[i]; // close to the Bark gain
            gain[i] = g;
            oldPs[i] = .2 * oldPs[i] + .8 * (gain[i] * gain[i]) * ps[i];
            if (gain[i] < gainFloor[i]) gain[i] = gainFloor[i];
            double tmp = p * Math.sqrt(gain[i]) + (1 - p) * Math.sqrt(gainFloor[i]);
            gain2[i] = tmp * tmp;
        }

        for (int i = 1; i < n; i++) {
            ft[2 * i - 1] *= gain2[i];
            ft[2 * i] *= gain2[i];
        }
        ft[0] *= gain2[0];
        ft[2 * n - 1] *= gain2[n - 1];

        fft.inverse(ft, frame);
        for (int i = 0; i < 2 * n; i++) frame[i] *= window[i];
        for (int i = 0; i < n3; i++) x[off + i] = toShort(outbuf[i] + frame[i]);
        for (int i = 0; i < n4; i++) x[off + n3 + i] = toShort(frame[n3 + i]);
        for (int i = 0; i < n3; i++) outbuf[i] = frame[frameSize + i];
    }

    private static short toShort(double v) {
        return v < -32767.5 ? -32768 : v > 32766.5 ? 32767 : (short) Math.floor(.5 + v);
    }
}
