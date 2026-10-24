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

    void start() {
        if (on) return;
        on = true;
        avg = -1;
        gapN = 0;
        try {
            vis = new Visualizer(0); // (the output mix: what the car speakers get)
            vis.setEnabled(false);
            vis.setCaptureSize(256);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 40_000); // (milli-Hz: 40 a second)
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int sr) { sample(wave); }
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

    /** One capture: its loudness against the running average; a clear jump after at least 0.3 s is a beat. */
    private void sample(byte[] wave) {
        double sum = 0;
        for (byte b : wave) { int v = (b & 0xFF) - 128; sum += v * v; }
        float e = (float) Math.sqrt(sum / Math.max(1, wave.length));
        if (avg < 0) { avg = e; return; }
        boolean hit = e > avg * 1.45f && e > 6;
        avg += (e - avg) * 0.08f;
        if (!hit) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBeat < 300) return;
        float gap = (now - lastBeat) / 1000f;
        lastBeat = now;
        if (gap > 0.35f && gap < 0.9f) {
            gaps[gapN % gaps.length] = gap;
            gapN++;
            if (gapN >= 3) {
                float[] s = java.util.Arrays.copyOf(gaps, Math.min(gapN, gaps.length));
                java.util.Arrays.sort(s);
                period = s[s.length / 2]; // (the median: one odd gap doesn't throw the dance)
            }
        } else if (gap >= 0.9f && gap < 1.8f) { // (a beat every other bar was heard: half of it)
            float half = gap / 2;
            if (half > 0.35f) period = period * 0.7f + half * 0.3f;
        }
        final float per = period;
        main.post(() -> ear.beat(per));
    }
}
