package com.anil.jarvis;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;

/**
 * 🥁 Is a song playing, and what is its beat? The song: the box's media session (any music app) in PLAYING, or music
 * audible on the box. The beat: Android's Visualizer on the output mix gives the loudness ~40 times a second; a jump in
 * loudness is a beat; the time between beats, steadied, is the tempo (70–170 a minute). Some boxes refuse the
 * Visualizer: then there is no beat and Jarvis dances at his usual 100 a minute. Nothing is recorded or kept — only a
 * loudness number comes out of it.
 */
final class CarBeat {
    interface Ear {
        void beat(float secondsPerBeat);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private Visualizer vis;
    private final Ear ear;
    private float avg = -1, period = 0.6f;
    private long lastBeat;
    private final float[] gaps = new float[6];
    private int gapN;
    private boolean on;

    CarBeat(Ear ear) { this.ear = ear; }

    /**
     * A song is playing on this device now (one look; the screen asks every second and waits for a few in a row). The
     * music app's own session is believed (playing or buffering between two songs); without one, music sound on the box
     * counts only when {@code trustSound} — the screen passes false while Jarvis himself talks or just stopped, so his
     * voice and the map's directions, which go out as "music" too, are not taken for a song.
     */
    static boolean songPlaying(Context c, boolean trustSound) {
        try {
            if (SoundService.radioOn && !SoundService.radioPaused) return true; // Jarvis's own radio
        } catch (Throwable ignored) {}
        try {
            MediaController mc = Music.active(c);
            if (mc != null && mc.getPlaybackState() != null) return Music.playing(mc);
        } catch (Exception ignored) {}
        if (!trustSound) return false;
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.isMusicActive() && !Announcer.audible();
        } catch (Exception e) {
            return false;
        }
    }

    /** The song's name and app for the screen ("" when none). */
    static String[] nowPlaying(Context c) {
        try {
            org.json.JSONObject o = Music.panel(c);
            if (o.optBoolean("none")) return new String[]{"", ""};
            return new String[]{o.optString("title", ""), o.optString("app", "")};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    float period() { return period; }

    // The song's loudness now (~2 s) against its own over the last ~20 s: a chorus is louder than its verse. Measured by
    // the Visualizer's own RMS meter (before its waveform is scaled to full size — that waveform shows the beat, not the
    // level). Silence (a gap between songs, a break) is skipped: the averages wait; a new song starts them again.
    private final Visualizer.MeasurementPeakRms meas = new Visualizer.MeasurementPeakRms();
    private boolean measOk;
    private float eShort = -1, eLong = -1, voiced; // (voiced: seconds of sound measured so far in this song)
    private long heardAt, sampledAt, loudAt;
    private static final float SHORT_S = 2f, LONG_S = 20f, WARM_S = 4f;
    private static final int SILENT_MB = -6000; // (-60 dB: nothing playing)

    /**
     * The loudness now against the song's own (1: as usual; 1.3: a loud part; 0.7: a quiet one), or 0 when unknown —
     * no meter on this box, the song's first seconds, or silence. Only numbers: nothing of the sound is kept.
     */
    float energy() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!on || !measOk || now - loudAt > 2000 || voiced < WARM_S || eLong <= 0) return 0;
        return eShort / eLong;
    }

    /** A new song began (the strip saw another title): its loudness is measured afresh, not against the last one's. */
    void newSong() { eShort = eLong = -1; voiced = 0; }

    /** The beat heard lately, in beats a minute (for the screen; kept 15 s through a quiet passage), or 0. */
    int bpm() {
        long now = android.os.SystemClock.uptimeMillis();
        return on && gapN >= 3 && now - heardAt < 15_000 ? Math.round(60f / period) : 0;
    }

    void start() {
        if (on) return;
        on = true;
        avg = -1;
        gapN = 0;
        eShort = eLong = -1;
        voiced = 0;
        heardAt = sampledAt = loudAt = 0;
        measOk = false;
        try {
            vis = new Visualizer(0); // (the output mix: what the car speakers get)
            vis.setEnabled(false);
            vis.setCaptureSize(256);
            try { measOk = vis.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS; } catch (Throwable ignored) {}
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 40_000); // (milli-Hz: 40 a second)
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int sr) { sample(v, wave); }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {}
            }, rate, true, false);
            vis.setEnabled(true);
        } catch (Throwable e) { // (no permission on this Android, or the box refuses it): no beat, the usual tempo
            release();
        }
    }

    void stop() {
        on = false;
        release();
    }

    private void release() {
        try { if (vis != null) { vis.setEnabled(false); vis.release(); } } catch (Throwable ignored) {}
        vis = null;
    }

    /**
     * One capture (on the main thread: the listener was set there): the song's level from the meter for the loud / quiet
     * parts; the waveform's own loudness against its running average for the beat — a clear jump after 0.3 s is one.
     */
    private void sample(Visualizer v, byte[] wave) {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = sampledAt == 0 ? 0.05f : Math.min(1f, (now - sampledAt) / 1000f); // (the box decides how often: 10–40 a second)
        sampledAt = now;
        if (measOk) {
            int mb = SILENT_MB - 1;
            try { if (v.getMeasurementPeakRms(meas) == Visualizer.SUCCESS) mb = meas.mRms; } catch (Throwable ignored) {}
            if (mb > SILENT_MB) { // (silence: the averages wait)
                float lv = (float) Math.pow(10, mb / 2000.0); // (millibels → amplitude)
                loudAt = now;
                voiced += dt;
                float aS = Math.max(1 - (float) Math.exp(-dt / SHORT_S), dt / voiced), aL = Math.max(1 - (float) Math.exp(-dt / LONG_S), dt / voiced); // (a plain average while warming up)
                if (eShort < 0) { eShort = lv; eLong = lv; }
                else { eShort += (lv - eShort) * Math.min(1, aS); eLong += (lv - eLong) * Math.min(1, aL); }
            }
        }
        double sum = 0;
        for (byte b : wave) { int x = (b & 0xFF) - 128; sum += x * x; }
        float e = (float) Math.sqrt(sum / Math.max(1, wave.length));
        if (avg < 0) { avg = e; return; }
        boolean hit = e > avg * 1.45f && e > 6;
        avg += (e - avg) * 0.08f;
        if (!hit) return;
        if (now - lastBeat < 300) return;
        float gap = (now - lastBeat) / 1000f;
        lastBeat = now;
        if (gap > 0.35f && gap < 0.9f) {
            heardAt = now;
            gaps[gapN % gaps.length] = gap;
            gapN++;
            if (gapN >= 3) {
                float[] s = java.util.Arrays.copyOf(gaps, Math.min(gapN, gaps.length));
                java.util.Arrays.sort(s);
                period = s[s.length / 2]; // (the median: one odd gap doesn't throw the dance)
            }
        } else if (gap >= 0.9f && gap < 1.8f) { // (a beat every other bar was heard: half of it)
            heardAt = now;
            float half = gap / 2;
            if (half > 0.35f) period = period * 0.7f + half * 0.3f;
        }
        final float per = period;
        main.post(() -> ear.beat(per));
    }
}
