package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Tiny made-on-the-phone sound effects (the JARVIS "power up" chirp). */
final class Sfx {
    private Sfx() {}

    private static short[] chirp;

    /** A short rising two-tone chirp when Jarvis wakes. */
    static void chirp(Context c, Prefs p) {
        if (!p.sfx()) return;
        new Thread(() -> {
            try {
                int rate = 24000;
                if (chirp == null) {
                    int n = rate * 22 / 100; // 220 ms
                    short[] s = new short[n];
                    double phase = 0;
                    for (int i = 0; i < n; i++) {
                        double x = i / (double) n;
                        double f = x < 0.45 ? 900 + 700 * (x / 0.45) : 1500 + 500 * ((x - 0.45) / 0.55);
                        phase += 2 * Math.PI * f / rate;
                        double env = Math.min(1, x * 25) * Math.pow(1 - x, 1.6);
                        s[i] = (short) (Math.sin(phase) * env * 9000 + Math.sin(phase * 2) * env * 2500);
                    }
                    chirp = s;
                }
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(chirp.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                t.write(chirp, 0, chirp.length);
                t.play();
                Thread.sleep(260);
                t.release();
            } catch (Exception ignored) {}
        }, "jarvis-sfx").start();
    }
}
