package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Tiny made-on-the-phone sound effects (the JARVIS "power up" chirp, and the soft "mic off" after it). */
final class Sfx {
    private Sfx() {}

    private static final int RATE = 24000;
    private static short[] chirp, micOff;

    /** A short rising two-tone chirp when Jarvis wakes. */
    static void chirp(Context c, Prefs p) {
        if (!p.sfx()) return;
        play(() -> {
            if (chirp == null) chirp = sweep(220, 900, 1500, 2000, 9000);
            return chirp;
        });
    }

    /** A soft falling tone: the mic has closed (nothing was heard, or it could not listen). */
    static void micOff(Context c, Prefs p) {
        if (!p.sfx()) return;
        play(() -> {
            if (micOff == null) micOff = sweep(200, 1400, 1000, 650, 6000);
            return micOff;
        });
    }

    /** A tone from f0 to f1 in the first 45%, then on to f2, with a quick fade in and a soft fade out. */
    private static short[] sweep(int ms, double f0, double f1, double f2, double loud) {
        int n = RATE * ms / 1000;
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) n;
            double f = x < 0.45 ? f0 + (f1 - f0) * (x / 0.45) : f1 + (f2 - f1) * ((x - 0.45) / 0.55);
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1, x * 25) * Math.pow(1 - x, 1.6);
            s[i] = (short) (Math.sin(phase) * env * loud + Math.sin(phase * 2) * env * loud * 0.28);
        }
        return s;
    }

    private interface Make { short[] get(); }

    private static void play(Make make) {
        new Thread(() -> {
            AudioTrack t = null;
            try {
                short[] s;
                synchronized (Sfx.class) { s = make.get(); }
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(s.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                t.write(s, 0, s.length);
                t.play();
                Thread.sleep(s.length * 1000L / RATE + 40);
            } catch (Exception ignored) {
            } finally {
                if (t != null) try { t.release(); } catch (Exception ignored) {}
            }
        }, "jarvis-sfx").start();
    }
}
