package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Locale;

/**
 * "Talk over Jarvis": while Jarvis is speaking, listens on the mic (echo cancelled) and, when Anil's
 * voice is clearly louder than what Jarvis's own echo can explain for a moment, tells the caller to stop
 * speaking and listen. Energy based, so it works offline and needs no recognizer.
 *
 * The echo is judged from statistics of the last few seconds that do NOT depend on earlier decisions
 * (a percentile of the echo level), so a bad first guess can never lock it into stopping on its own voice.
 */
final class BargeIn {
    interface Callback { void onVoice(); }

    private static final int RATE = 16000;
    private static final int FRAME = 320;           // 20 ms
    private static final int HIST = 150;            // 3 s of history for the echo statistics
    private static final int SETTLE_FRAMES = 30;    // 0.6 s of Jarvis actually sounding before any decision

    // With the natural voice we know how loud Jarvis is at every moment, so its echo can be predicted.
    private static final double PLAYING = 300;      // output louder than this = Jarvis is sounding
    private static final double LOUD_OUT = 1000;    // learn the echo path only from clearly loud output
    private static final double REF_OVER = 1.8;     // his voice: this much above the predicted (strong) echo
    private static final int REF_NEED = 10;         // ~200 ms of that
    private static final double MIN_RMS = 500;      // quieter than this is never him talking

    // The phone's own TTS voice gives no reference: compare with the recent echo level itself.
    private static final double OVER_ECHO = 2.0;
    private static final int NEED_FRAMES = 12;      // ~240 ms

    /** Settings slider 0 (hard to interrupt) .. 4 (very easy); 2 is normal. */
    private static final double[] SCALE = {1.6, 1.25, 1.0, 0.8, 0.65};
    private static final int[] NEED_ADJ = {4, 2, 0, -2, -4};

    /** What the detector saw last time (shown in Settings, to tune it). */
    static volatile String lastInfo = "";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread thread;
    private volatile boolean running;

    BargeIn(Context c) { ctx = c.getApplicationContext(); }

    void start(Callback cb) {
        stop();
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        running = true;
        Thread t = new Thread(() -> run(cb), "jarvis-bargein");
        thread = t;
        t.start();
    }

    /**
     * Talk-over without the phone's call mode (Jarvis's voice stays its clear assistant voice): eg, when Jarvis speaks
     * with its natural voice, takes that voice out of the mic itself (as in Gemini Live); once that works well, his voice
     * over Jarvis stops it. Always: "Jarvis" / "stop" stops it (the small offline word model of the wake word).
     */
    void startOwn(Callback cb, EchoGuard eg) {
        stop();
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        running = true;
        Thread t = new Thread(() -> runOwn(cb, eg), "jarvis-bargein-own");
        thread = t;
        t.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    /** Rolling window of values with a percentile. */
    private static final class Window {
        final double[] v = new double[HIST];
        final double[] tmp = new double[HIST];
        int n, pos;
        void add(double x) { v[pos] = x; pos = (pos + 1) % HIST; if (n < HIST) n++; }
        double pct(double p) {
            if (n == 0) return 0;
            System.arraycopy(v, 0, tmp, 0, n);
            Arrays.sort(tmp, 0, n);
            return tmp[Math.min(n - 1, (int) Math.floor(p * (n - 1) + 0.5))];
        }
    }

    private void run(Callback cb) {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
            rec.startRecording();
            int level = Math.max(0, Math.min(4, new Prefs(ctx).bargeSens()));
            double k = SCALE[level];
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            boolean headset = CallMode.headset(am);
            boolean callPath = am != null && am.getMode() == AudioManager.MODE_IN_COMMUNICATION;
            boolean callVoice = callPath && new Prefs(ctx).bargeCallVoice();

            short[] buf = new short[FRAME];
            Window ratio = new Window();   // mic / Jarvis output, while Jarvis sounds (echo path strength)
            Window quiet = new Window();   // mic while Jarvis is silent (room noise)
            Window mic = new Window();     // mic, all frames (for the no-reference case)
            int frames = 0, sounding = 0, loud = 0;
            double peakRms = 0, peakLimit = 0;
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                double out = PlaybackLevel.now();
                boolean ref = out >= 0;
                frames++;

                double limit;
                int need;
                boolean ready;
                if (ref) {
                    double noise = quiet.n >= 5 ? quiet.pct(0.5) : 0;
                    if (out > PLAYING) sounding++;
                    if (out > LOUD_OUT) ratio.add(Math.sqrt(Math.max(0, rms * rms - noise * noise)) / out); // echo only, room noise removed
                    else if (out < PLAYING / 2) quiet.add(rms);
                    ready = sounding >= SETTLE_FRAMES && ratio.n >= 20;
                    double gain = ratio.pct(0.85);           // strong echo, not the average: safe against itself
                    limit = Math.max(Math.max(MIN_RMS, noise * 3), Math.hypot(gain * out * REF_OVER, noise)) * k;
                    need = Math.max(5, REF_NEED + NEED_ADJ[level]);
                } else {
                    mic.add(rms);
                    ready = frames >= 40;                     // Google TTS: onStart is when sound begins
                    double over = headset ? 1.6 : OVER_ECHO;
                    limit = Math.max(MIN_RMS * 1.5, mic.pct(0.85) * over) * k;
                    need = Math.max(6, NEED_FRAMES + NEED_ADJ[level]);
                }
                if (!ready) continue;
                if (rms > peakRms) { peakRms = rms; peakLimit = limit; }
                if (rms > limit) {
                    if (++loud >= need) {
                        lastInfo = info(ref, callPath, callVoice, rms, limit, peakRms, peakLimit, true);
                        running = false;
                        main.post(cb::onVoice);
                        return;
                    }
                } else {
                    loud = Math.max(0, loud - 1); // a short dip inside a word does not reset everything
                }
                if (frames % 25 == 0) lastInfo = info(ref, callPath, callVoice, rms, limit, peakRms, peakLimit, false);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rec != null) { rec.stop(); } } catch (Exception ignored) {}
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            try { if (aec != null) aec.release(); } catch (Exception ignored) {}
            try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        }
    }

    // ================================================================ talk-over without call mode

    private static final int OWN_CHUNK = 640; // 40 ms (a whole number of the echo removal's 8 ms steps)
    /** Of the last 32 steps (~0.26 s) of the cleaned mic, this many with his voice (slider 0 hard .. 4 easy). */
    private static final int[] OWN_NEED = {26, 23, 20, 17, 14};

    private void runOwn(Callback cb, EchoGuard eg) {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        org.vosk.Recognizer word = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // with Jarvis's own echo removal the plain mic (the phone's own processing would change it as it goes)
            rec = new AudioRecord(eg != null ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, OWN_CHUNK * 2 * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            if (eg == null && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            rec.startRecording();
            word = Words.recognizer(ctx);
            int level = Math.max(0, Math.min(4, new Prefs(ctx).bargeSens()));
            int need = OWN_NEED[level];
            short[] buf = new short[OWN_CHUNK];
            android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
            long framesRead = 0, startedAt = android.os.SystemClock.elapsedRealtime(), infoAt = 0;
            int over = 0;
            while (running) {
                int n = 0;
                while (n < OWN_CHUNK && running) {
                    int got = rec.read(buf, n, OWN_CHUNK - n);
                    if (got <= 0) return;
                    n += got;
                }
                if (!running) return;
                long heard = heardAt(rec, ts, framesRead, n);
                framesRead += n;
                byte[] bytes = new byte[n * 2];
                boolean clean = false;
                int voice = 0;
                if (eg != null) {
                    synchronized (eg) { // (one mic at a time: the last answer's may still be finishing)
                        short[] use = eg.process(buf, n, heard);
                        toBytes(use, n, bytes);
                        clean = eg.ready();
                        voice = eg.voiceFrames();
                    }
                } else {
                    toBytes(buf, n, bytes);
                }
                long now = android.os.SystemClock.elapsedRealtime();
                boolean fired = false;
                String how = "";
                // his voice over Jarvis's, once Jarvis's own voice is really gone from the mic (measured, as in Gemini Live)
                if (clean && now - startedAt > 400) {
                    if (voice >= need) { if (++over >= 2) { fired = true; how = "మీ గొంతు"; } }
                    else over = 0;
                } else {
                    over = 0;
                }
                if (!fired && word != null && Words.said(word, bytes)) { fired = true; how = "\"Jarvis\" / \"stop\""; }
                if (fired) {
                    lastInfo = "ఆగింది (" + how + ")" + (eg != null ? " · " + eg.info() : " · ఫోన్ గొంతు");
                    running = false;
                    main.post(cb::onVoice);
                    return;
                }
                if (now - infoAt > 1000) {
                    infoAt = now;
                    lastInfo = (eg == null ? "ఫోన్ గొంతు: \"Jarvis\" / \"stop\" అంటే ఆగుతుంది"
                            : eg.ready() ? "మీరు మాట్లాడగానే ఆగుతుంది ✓ · " + eg.info()
                            : "Jarvis తన గొంతు తీసేయడం నేర్చుకుంటోంది (అప్పటిదాకా \"Jarvis\" / \"stop\") · " + eg.info())
                            + (word == null ? " · (\"Jarvis\" పదం మోడల్ లేదు)" : "");
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            try { if (aec != null) aec.release(); } catch (Exception ignored) {}
            Words.done(word);
            if (eg != null) Echo.keep(ctx, eg);
        }
    }

    private static void toBytes(short[] s, int n, byte[] out) {
        for (int i = 0; i < n; i++) { out[2 * i] = (byte) (s[i] & 0xFF); out[2 * i + 1] = (byte) ((s[i] >> 8) & 0xFF); }
    }

    /** When the first of these n mic samples was heard (System.nanoTime()), from the mic's own time stamp if it has one. */
    private static long heardAt(AudioRecord rec, android.media.AudioTimestamp ts, long firstFrame, int n) {
        long now = System.nanoTime();
        long guess = now - (long) (n * 1e9 / RATE) - 10_000_000L;
        try {
            if (rec.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                long t = ts.nanoTime + (long) ((firstFrame - ts.framePosition) * 1e9 / RATE);
                if (t <= now && t > now - 1_000_000_000L) return t;
            }
        } catch (Exception ignored) {}
        return guess;
    }

    /**
     * Jarvis's own echo removal for the classic talk-over: one for the app's run (what it learnt carries over from
     * answer to answer, and from and to Gemini Live through the file both keep).
     */
    static final class Echo {
        private static EchoGuard guard;
        private static boolean loaded;
        private static long savedAt;

        static synchronized EchoGuard get(Context c) {
            if (!loaded) {
                loaded = true;
                try {
                    java.io.File f = new java.io.File(c.getFilesDir(), "echo-path.bin");
                    if (f.exists() && f.length() < 1_000_000) EchoGuard.importState(java.nio.file.Files.readAllBytes(f.toPath()));
                } catch (Exception ignored) {}
            }
            if (guard == null) guard = new EchoGuard(true, false);
            return guard;
        }

        /** After an answer: what was learnt is kept (in memory always; in the file at most once a minute). */
        static void keep(Context c, EchoGuard eg) {
            synchronized (eg) { try { eg.save(); } catch (Exception ignored) {} }
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (Echo.class) {
                if (now - savedAt < 60_000) return;
                savedAt = now;
            }
            Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    byte[] b = EchoGuard.exportState();
                    if (b == null) return;
                    java.io.File tmp = new java.io.File(app.getFilesDir(), "echo-path.tmp");
                    java.nio.file.Files.write(tmp.toPath(), b);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(new java.io.File(app.getFilesDir(), "echo-path.bin"));
                } catch (Exception ignored) {}
            }, "jarvis-echo-keep").start();
        }
    }

    /**
     * "Jarvis" / "stop" over Jarvis's voice: the small English word model the wake word downloaded (never downloaded
     * here). Loaded on first use, let go after a few minutes without talk-over.
     */
    static final class Words {
        private static org.vosk.Model model;
        private static int users;
        private static final Handler idle = new Handler(Looper.getMainLooper());
        private static final Runnable close = () -> new Thread(Words::closeIfIdle, "jarvis-words-close").start();

        /** A fresh recognizer for one answer, or null (no model on the phone, or it wouldn't load). */
        static org.vosk.Recognizer recognizer(Context c) {
            if (!VoskModel.ready(c)) return null;
            synchronized (Words.class) {
                try {
                    if (model == null) {
                        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                        model = new org.vosk.Model(VoskModel.dir(c).getAbsolutePath());
                    }
                    org.vosk.Recognizer r = new org.vosk.Recognizer(model, (float) RATE, "[\"jarvis\", \"hey jarvis\", \"stop\", \"[unk]\"]");
                    r.setWords(true);
                    users++;
                    return r;
                } catch (Throwable e) {
                    return null;
                }
            }
        }

        static void done(org.vosk.Recognizer r) {
            if (r == null) return;
            try { r.close(); } catch (Throwable ignored) {}
            synchronized (Words.class) { users = Math.max(0, users - 1); }
            idle.removeCallbacks(close);
            idle.postDelayed(close, 3 * 60_000L);
        }

        private static synchronized void closeIfIdle() {
            if (users > 0 || model == null) return;
            try { model.close(); } catch (Throwable ignored) {}
            model = null;
        }

        /** "Jarvis" or "stop" said, sure (Vosk's own sureness), in this piece of the cleaned mic. */
        static boolean said(org.vosk.Recognizer r, byte[] pcm) {
            try {
                if (!r.acceptWaveForm(pcm, pcm.length)) return false;
                org.json.JSONArray ws = new org.json.JSONObject(r.getResult()).optJSONArray("result");
                for (int i = 0; ws != null && i < ws.length(); i++) {
                    org.json.JSONObject w = ws.getJSONObject(i);
                    String t = w.optString("word");
                    double c = w.optDouble("conf", 0);
                    if ("jarvis".equals(t) && c >= 0.85 || "stop".equals(t) && c >= 0.9) return true;
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    private static String info(boolean ref, boolean callPath, boolean callVoice, double rms, double limit, double peak, double peakLimit, boolean fired) {
        return String.format(Locale.ROOT, "%s · %s · %s · పెద్ద శబ్దం %.0f / హద్దు %.0f",
                fired ? "ఆగింది" : "ఆగలేదు", ref ? "సహజ గొంతు" : "ఫోన్ గొంతు", callPath ? (callVoice ? "కాల్ గొంతు" : "అసలు గొంతు + echo-cancel") : "normal",
                fired ? rms : peak, fired ? limit : peakLimit);
    }
}
