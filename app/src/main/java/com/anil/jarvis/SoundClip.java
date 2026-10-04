package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * A few seconds of sound from the mic as a WAV file (16 kHz mono), for "🎤 శబ్దం వినిపించు": a machine's noise, an engine's
 * knock, a fan's rattle. Any thread except the main one (it waits while recording).
 */
final class SoundClip {
    private SoundClip() {}

    private static final int RATE = 16000;
    static volatile boolean stop;

    /** Records up to ms (stop = true ends it early); null when the mic could not be opened. */
    @SuppressLint("MissingPermission")
    static byte[] record(long ms) {
        stop = false;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
            }
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
        } catch (Exception e) {
            return null;
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buf = new byte[min];
        long end = System.currentTimeMillis() + ms;
        try {
            r.startRecording();
            while (!stop && System.currentTimeMillis() < end) {
                int n = r.read(buf, 0, buf.length);
                if (n > 0) pcm.write(buf, 0, n);
                else if (n < 0) break;
            }
        } catch (Exception ignored) {
        } finally {
            try { r.stop(); } catch (Exception ignored) {}
            r.release();
        }
        byte[] data = pcm.toByteArray();
        return data.length < RATE ? null : wav(data); // less than half a second: nothing
    }

    private static byte[] wav(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = RATE * 2;
        writeStr(o, "RIFF"); writeInt(o, 36 + pcm.length); writeStr(o, "WAVE");
        writeStr(o, "fmt "); writeInt(o, 16); writeShort(o, 1); writeShort(o, 1); writeInt(o, RATE); writeInt(o, byteRate); writeShort(o, 2); writeShort(o, 16);
        writeStr(o, "data"); writeInt(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) { for (char ch : s.toCharArray()) o.write(ch); }
    private static void writeInt(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void writeShort(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
}
