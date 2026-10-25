package com.anil.jarvis;

/**
 * 🎚️ The song strip's equalizer, kept pure (desk-tested): Android's Visualizer FFT of what the car speakers play, into
 * bars from the bass to the treble (every bar at least one band of its own, wider bands higher up); each bar rises at
 * once and falls slowly, a cap marks its last peak and drops more slowly still; an automatic level so a quiet song and a
 * loud one both fill the strip. Only numbers: nothing of the sound is kept.
 */
final class CarEq {
    static final int BARS = 24;
    final float[] level = new float[BARS], peak = new float[BARS];
    private float top; // (the loudest bar lately: the automatic level)
    private long dataAt, tickAt;
    /** Told on every new capture (the strip's drawing wakes up). */
    Runnable onData;

    /**
     * The FFT bytes in the Visualizer's own order ([0] the steady part, [1] the highest band, then re / im of each band
     * from 1 up) into n bars of 0–1: the loudest band in each bar, on a log scale. Bars go up to ~55 % of the bands
     * (about 12 kHz: above it music is thin). Pure.
     */
    static float[] bars(byte[] fft, int n) {
        float[] out = new float[n];
        if (fft == null || fft.length < 8 || n <= 0) return out;
        int bands = fft.length / 2;
        int hi = Math.max(n + 1, Math.min(bands - 1, Math.round(bands * 0.55f)));
        int from = 1;
        for (int i = 0; i < n; i++) {
            // the bar's last band: log spaced, but never less than one band of its own
            int to = (int) Math.round(1 + (hi - 1) * (Math.pow(2, 6.0 * (i + 1) / n) - 1) / 63.0);
            to = Math.max(from + 1, Math.min(bands, to));
            float m = 0;
            for (int k = from; k < to && k < bands; k++) m = Math.max(m, (float) Math.hypot(fft[2 * k], fft[2 * k + 1]));
            out[i] = (float) (Math.log10(1 + m) / Math.log10(1 + 181)); // (181: the largest a byte pair can be)
            from = to;
        }
        return out;
    }

    /** A new capture's bars (now: uptime ms). */
    void feed(float[] b, long now) {
        float dt = dataAt == 0 ? 0.03f : Math.max(0.005f, Math.min(0.2f, (now - dataAt) / 1000f));
        dataAt = tickAt = now;
        float mx = 0;
        for (float v : b) mx = Math.max(mx, v);
        top = Math.max(mx, top * (1 - Math.min(1, dt * 0.25f))); // (the level follows a song's loudness over ~4 s)
        float g = top < 0.04f ? 0 : 0.95f / top;
        for (int i = 0; i < BARS && i < b.length; i++) {
            float t = (float) Math.pow(Math.min(1, b[i] * g), 1.6); // (more contrast: the beat stands out)
            if (t > level[i]) level[i] += (t - level[i]) * Math.min(1, dt * 30);
            else level[i] = Math.max(t, level[i] - dt * 1.8f);
            if (level[i] >= peak[i]) peak[i] = level[i];
            else peak[i] = Math.max(0, peak[i] - dt * 0.55f);
        }
        if (onData != null) onData.run();
    }

    /** Between captures (each frame): when the captures stop, the bars fall away. */
    void tick(long now) {
        if (tickAt == 0) { tickAt = now; return; }
        float dt = Math.max(0, Math.min(0.2f, (now - tickAt) / 1000f));
        tickAt = now;
        if (now - dataAt < 150) return;
        for (int i = 0; i < BARS; i++) {
            level[i] = Math.max(0, level[i] - dt * 1.8f);
            peak[i] = Math.max(0, peak[i] - dt * 0.55f);
        }
    }

    /** Still something to draw (captures coming, or bars still falling). */
    boolean alive(long now) {
        if (dataAt != 0 && now - dataAt < 1000) return true;
        for (int i = 0; i < BARS; i++) if (level[i] > 0.005f || peak[i] > 0.005f) return true;
        return false;
    }
}
