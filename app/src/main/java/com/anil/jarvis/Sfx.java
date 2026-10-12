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

    // ---- the Jarvis camera's own HUD sounds (made here, nothing copied): scan, marker, lock, zoom, card, count
    private static final java.util.Map<String, short[]> hudCache = new java.util.HashMap<>();

    /** The camera's HUD sounds on (his "Jarvis శబ్దాలు" and the camera's own 🔊 switch). */
    static boolean hudOn(Prefs p) { return p.sfx() && p.sp.getBoolean("cam_hud_sfx", true); }

    /** kind: scan, mark, tick, lock, whoosh, card, count. */
    static void hud(Context c, Prefs p, String kind) {
        if (!hudOn(p)) return;
        play(() -> {
            short[] s = hudCache.get(kind);
            if (s == null) { s = makeHud(kind); hudCache.put(kind, s); }
            return s;
        });
    }

    static short[] makeHud(String kind) {
        switch (kind) {
            case "scan": return shimmer(900, 380, 1250, 3600);              // a soft rising shimmer while the wave crosses
            case "mark": return blip(70, 2200, 2600);                       // a tiny ping: something found
            case "tick": return join(blip(35, 3200, 3400), silence(55), blip(35, 3200, 3400)); // the ring closing
            case "lock": return join(blip(90, 1320, 4200), silence(20), blip(140, 1980, 4200)); // locked: two bright notes up
            case "whoosh": return whoosh(520);                             // the lens going in
            case "card": return sweep(160, 900, 1500, 1700, 3200);         // the card opening
            case "count": return join(blip(60, 1600, 3000), silence(40), blip(60, 2000, 3000));
            default: return blip(60, 1800, 3000);
        }
    }

    private static short[] blip(int ms, double f, double loud) {
        int n = RATE * ms / 1000;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double x = i / (double) n, env = Math.min(1, x * 30) * Math.pow(1 - x, 2.2);
            double ph = 2 * Math.PI * f * i / RATE;
            s[i] = (short) ((Math.sin(ph) + 0.25 * Math.sin(ph * 2.01)) * env * loud);
        }
        return s;
    }

    /** A breathy rising sweep with a few bright partials (the scan wave). */
    private static short[] shimmer(int ms, double f0, double f1, double loud) {
        int n = RATE * ms / 1000;
        short[] s = new short[n];
        double ph = 0;
        java.util.Random r = new java.util.Random(7);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) n, f = f0 * Math.pow(f1 / f0, x);
            ph += 2 * Math.PI * f / RATE;
            lp += (r.nextGaussian() - lp) * 0.08; // soft noise
            double env = Math.min(1, x * 6) * Math.pow(1 - x, 1.3);
            double v = Math.sin(ph) * 0.55 + Math.sin(ph * 2.5) * 0.18 + Math.sin(ph * 4.02) * 0.08 + lp * 0.35;
            s[i] = (short) (v * env * loud);
        }
        return s;
    }

    /** Noise through a sweeping filter: air rushing in. */
    private static short[] whoosh(int ms) {
        int n = RATE * ms / 1000;
        short[] s = new short[n];
        java.util.Random r = new java.util.Random(11);
        double y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) n;
            double f = 300 + 2600 * Math.sin(Math.PI * x * 0.9); // up and a little down
            double w = 2 * Math.PI * f / RATE, q = 0.85;
            double in = r.nextGaussian();
            double y = in * (1 - q) + 2 * q * Math.cos(w) * y1 - q * q * y2; // a simple resonator
            y2 = y1;
            y1 = y;
            double env = Math.sin(Math.PI * Math.min(1, x * 1.05));
            s[i] = (short) Math.max(-32000, Math.min(32000, y * env * 5200));
        }
        return s;
    }

    private static short[] silence(int ms) { return new short[RATE * ms / 1000]; }

    private static short[] join(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] s = new short[n];
        int at = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, s, at, p.length); at += p.length; }
        return s;
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
