package com.anil.jarvis.watch;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Jarvis's voice on the watch (W11): the phone sends it as it is made (16 kHz mu-law); it plays through the watch's
 * speaker, or its Bluetooth earbuds when those are connected (Android sends media there by itself). Music on the watch
 * goes quieter meanwhile. When all of it has played, the phone is told (it may then listen for his reply).
 */
final class Player {
    private Player() {}

    private static final int RATE = 16000;
    private static final short[] END = new short[0];
    /** The voice's level 0..1 (the orb glows with it). */
    static volatile float level;
    private static volatile Play cur;

    private static final class Play {
        final int id;
        final LinkedBlockingQueue<short[]> q = new LinkedBlockingQueue<>();
        final TreeMap<Integer, byte[]> early = new TreeMap<>();
        int nextSeq;
        volatile boolean stopped;
        Play(int id) { this.id = id; }
    }

    static boolean playing() { Play p = cur; return p != null && !p.stopped; }

    static void start(Context c, int id) {
        stop();
        Play p = new Play(id);
        cur = p;
        final Context app = c.getApplicationContext();
        new Thread(() -> run(app, p), "jarvis-watch-voice").start();
    }

    /** A message of the voice (out-of-order ones wait a moment; a lost one is skipped). */
    static void data(byte[] packet) {
        Play p = cur;
        if (p == null || p.id != Ulaw.id(packet)) return;
        synchronized (p) {
            int seq = Ulaw.seq(packet);
            if (seq < p.nextSeq) return;
            p.early.put(seq, packet);
            if (p.early.size() > 6) p.nextSeq = p.early.firstKey();
            while (!p.early.isEmpty() && p.early.firstKey() == p.nextSeq) {
                byte[] b = p.early.remove(p.nextSeq++);
                p.q.offer(Ulaw.decode(b, 8, b.length - 8));
            }
        }
    }

    /** All of it has been sent (drop: the phone stopped it). */
    static void end(int id, boolean drop) {
        Play p = cur;
        if (p == null || p.id != id) return;
        if (drop) { stop(); return; }
        synchronized (p) {
            for (byte[] b : p.early.values()) p.q.offer(Ulaw.decode(b, 8, b.length - 8)); // whatever came late
            p.early.clear();
        }
        p.q.offer(END);
    }

    static void stop() {
        Play p = cur;
        cur = null;
        if (p != null) { p.stopped = true; p.q.offer(END); }
        level = 0;
    }

    private static void run(Context app, Play p) {
        AudioTrack t = null;
        AudioManager am = app.getSystemService(AudioManager.class);
        AudioFocusRequest focus = null;
        boolean finished = false;
        try {
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder().setAudioAttributes(aa)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, RATE)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (am != null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(aa).build();
                try { am.requestAudioFocus(focus); } catch (Exception ignored) {}
            }
            // a little sound first, so a slow moment on Bluetooth doesn't break the voice
            java.util.ArrayList<short[]> first = new java.util.ArrayList<>();
            int have = 0;
            long waitUntil = SystemClock.elapsedRealtime() + 1500;
            boolean ended = false;
            while (!p.stopped && have < RATE * 3 / 10 && SystemClock.elapsedRealtime() < waitUntil) {
                short[] s = p.q.poll(100, TimeUnit.MILLISECONDS);
                if (s == null) continue;
                if (s == END) { ended = true; break; }
                first.add(s);
                have += s.length;
            }
            if (p.stopped) return;
            t.play();
            long written = 0;
            for (short[] s : first) { write(t, s, p); written += s.length; }
            while (!ended && !p.stopped) {
                short[] s = p.q.poll(10, TimeUnit.SECONDS);
                if (s == null) break; // the phone went quiet: what came is played
                if (s == END) break;
                write(t, s, p);
                written += s.length;
            }
            if (p.stopped) return;
            long until = SystemClock.elapsedRealtime() + written * 1000 / RATE + 2000;
            while (!p.stopped && SystemClock.elapsedRealtime() < until) {
                try { if (t.getPlaybackHeadPosition() >= written) break; } catch (Exception e) { break; }
                SystemClock.sleep(40);
            }
            finished = !p.stopped;
        } catch (Exception ignored) {
            finished = !p.stopped;
        } finally {
            if (t != null) {
                try { t.pause(); t.flush(); } catch (Exception ignored) {}
                try { t.release(); } catch (Exception ignored) {}
            }
            if (am != null && focus != null) try { am.abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
            level = 0;
            if (cur == p) cur = null;
            if (finished) {
                try { Link.send(app, Link.P_PLAYED, new JSONObject().put("id", p.id)); } catch (Exception ignored) {}
                Talk.played(app);
            }
        }
    }

    private static void write(AudioTrack t, short[] s, Play p) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        double rms = Math.sqrt(sum / Math.max(1, s.length));
        level = (float) Math.max(0, Math.min(1, (20 * Math.log10(rms / 32768.0 + 1e-9) + 50) / 40));
        int at = 0;
        while (at < s.length && !p.stopped) {
            int w = t.write(s, at, s.length - at);
            if (w <= 0) break;
            at += w;
        }
    }
}
