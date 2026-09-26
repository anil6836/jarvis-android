package com.anil.jarvis;

import android.media.AudioTrack;

/**
 * How loud Jarvis's own voice is at this very moment. NaturalVoice feeds every PCM chunk it writes;
 * the level is kept per 20 ms block and looked up by the track's playback position, so the talk-over
 * detector knows exactly how much echo to expect (and that there is none in Jarvis's pauses).
 */
final class PlaybackLevel {
    private PlaybackLevel() {}

    private static final int SIZE = 8192;           // ring of 20 ms blocks (~160 s)
    private static final float[] ring = new float[SIZE];
    private static volatile AudioTrack track;
    private static volatile int blockSamples = 480;
    private static volatile long written;           // complete blocks written so far
    private static double acc;
    private static int accN;

    static synchronized void begin(AudioTrack t, int rate) {
        blockSamples = Math.max(1, rate / 50);
        written = 0;
        acc = 0;
        accN = 0;
        track = t;
    }

    /** 16-bit little-endian mono PCM, exactly as written to the track. */
    static synchronized void feed(byte[] b, int off, int len) {
        int bs = blockSamples;
        for (int i = off; i + 1 < off + len; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            acc += (double) s * s;
            if (++accN >= bs) {
                ring[(int) (written % SIZE)] = (float) Math.sqrt(acc / accN);
                written++;
                acc = 0;
                accN = 0;
            }
        }
    }

    static synchronized void end(AudioTrack t) { if (track == t) track = null; }

    /**
     * Loudest output level from ~200 ms ago up to ~40 ms ahead of what is playing now (covers room
     * echo and timing slack), or -1 when there is no reference (the phone's own TTS voice).
     */
    static double now() {
        AudioTrack t = track;
        if (t == null) return -1;
        long head;
        try { head = (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) / blockSamples; } catch (Exception e) { return -1; }
        long w = written;
        long from = Math.max(Math.max(0, w - SIZE + 1), head - 10), to = Math.min(w - 1, head + 2);
        double max = 0;
        for (long i = from; i <= to; i++) max = Math.max(max, ring[(int) (i % SIZE)]);
        return max;
    }
}
