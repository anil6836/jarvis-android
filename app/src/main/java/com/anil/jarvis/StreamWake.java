package com.anil.jarvis;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * "Hey Jarvis" / "Jarvis" in the sound from the watch (he raised his wrist): the same openWakeWord models and steps as
 * WakeEngine (a copy of them, so the phone's own wake word is never disturbed by the watch), plus the single word
 * "Jarvis" and the "only my voice" check with Vosk when those models are already on the phone (nothing is downloaded
 * from here). Everything stays on the phone. Models load on first use and are let go after a few minutes unused.
 * One user at a time (WatchHub's session thread).
 */
final class StreamWake {
    static final int CHUNK = 1280;          // 80 ms of 16 kHz sound per step
    static final int NONE = 0, CANDIDATE = 1, WOKE = 2, NOT_HIM = 3;

    private static final int CONTEXT = 480, MEL_BINS = 32, MEL_WINDOW = 76, FEATURES = 16, FEATURE_SIZE = 96;
    /** The watch's mic is not the phone's: his voice print is checked a little more loosely. */
    static final float WATCH_LOCK_SLACK = 0.08f;

    private final Context ctx;
    private OrtEnvironment env;
    private OrtSession mel, emb, ww;
    private String melIn, embIn, wwIn;
    private final ArrayDeque<float[]> melFrames = new ArrayDeque<>();
    private final ArrayDeque<float[]> features = new ArrayDeque<>();
    private final short[] previous = new short[CONTEXT];
    private int scored;

    // the "Jarvis" word and his voice (Vosk), when on the phone already
    private volatile org.vosk.Model voskModel;
    private volatile org.vosk.SpeakerModel spkModel;
    private volatile org.vosk.Recognizer vosk;
    private boolean wantWord, wantLock;
    private float[] print;
    private float threshold;
    private double voskConf, lockMax;

    private long quietUntil, pendingAt;
    private float pendingScore;
    private float[] lastSpk;
    private long lastSpkAt;
    /** The last voice check (for Settings): distance from his print, -1 none. */
    static volatile double lastDistance = -1;
    static volatile boolean lastAccepted = true;
    /** Why the word / voice check isn't working (for Settings), empty when fine. */
    static volatile String status = "";
    volatile long usedAt;

    StreamWake(Context c) { ctx = c.getApplicationContext(); }

    /** A new stretch of sound (a new wrist raise): settings read again, buffers emptied. Session thread. */
    void reset() throws Exception {
        usedAt = android.os.SystemClock.elapsedRealtime();
        Prefs p = new Prefs(ctx);
        threshold = p.wakeThreshold();
        voskConf = 0.75 + threshold * 0.3;
        wantWord = p.jarvisWord();
        print = WatchHub.lockOn(ctx) ? VoiceLock.print(ctx) : null;
        wantLock = print != null;
        lockMax = p.voiceLockMax() + WATCH_LOCK_SLACK;
        load();
        melFrames.clear();
        features.clear();
        java.util.Arrays.fill(previous, (short) 0);
        for (int i = 0; i < MEL_WINDOW; i++) {
            float[] ones = new float[MEL_BINS];
            java.util.Arrays.fill(ones, 1f);
            melFrames.addLast(ones);
        }
        scored = 0;
        // The detector needs ~1.7 s of sound before it scores anything, and he often says "Hey Jarvis" the moment the
        // wrist comes up: fill it first with a quiet room (a faint hiss), so the first real words are already scored.
        java.util.Random hiss = new java.util.Random(7);
        short[] room = new short[CHUNK];
        for (int s = 0; s < FEATURES + 6; s++) {
            for (int i = 0; i < CHUNK; i++) room[i] = (short) (hiss.nextInt(17) - 8);
            step(room);
        }
        quietUntil = 0;
        pendingAt = 0;
        lastSpk = null;
        lastSpkAt = 0;
        // "only my voice" switched on after the word model was loaded without the voice part: load both again
        if (wantLock && vosk != null && spkModel == null && new java.io.File(VoskModel.spkDir(ctx), ".ready").exists()) closeVosk();
        org.vosk.Recognizer r = vosk;
        if (r != null) try { r.reset(); } catch (Throwable ignored) {}
        loadVosk();
    }

    /** Feeds 1280 samples heard at t (ms of this sound); NONE, CANDIDATE (waiting for the voice check), WOKE or NOT_HIM. */
    int feed(short[] chunk, long t) throws Exception {
        usedAt = android.os.SystemClock.elapsedRealtime();
        float score = step(chunk);
        boolean word = jarvisHeard(chunk, t);
        int out = NONE;
        if ((score >= threshold || word) && t > quietUntil && pendingAt == 0) {
            quietUntil = t + 2000;
            if (!wantLock || spkModel == null || vosk == null) {
                lastDistance = -1;
                lastAccepted = true;
                return WOKE;
            }
            pendingAt = t;
            pendingScore = score;
            out = CANDIDATE;
        }
        if (pendingAt > 0) {
            if (lastSpkAt >= pendingAt - 400) return decide(lastSpk);
            if (t - pendingAt > 700) {
                float[] v = null;
                try { v = VoiceLock.vector(vosk.getFinalResult()); } catch (Throwable ignored) {}
                return decide(v);
            }
        }
        return out;
    }

    private int decide(float[] v) {
        pendingAt = 0;
        if (v == null) { lastDistance = -1; lastAccepted = true; return WOKE; } // too short to tell: never lock him out
        double d = VoiceLock.distance(v, print);
        lastDistance = d;
        lastAccepted = d <= lockMax;
        return lastAccepted ? WOKE : NOT_HIM;
    }

    float score() { return pendingScore; }

    /** "Jarvis" was heard and his voice is still being checked. */
    boolean pending() { return pendingAt > 0; }

    private boolean jarvisHeard(short[] chunk, long t) {
        org.vosk.Recognizer r = vosk;
        if (r == null || (!wantWord && !wantLock)) return false;
        byte[] b = new byte[chunk.length * 2];
        for (int i = 0; i < chunk.length; i++) { b[2 * i] = (byte) (chunk[i] & 0xFF); b[2 * i + 1] = (byte) ((chunk[i] >> 8) & 0xFF); }
        if (!r.acceptWaveForm(b, b.length)) return false;
        try {
            String json = r.getResult();
            if (spkModel != null) {
                float[] v = VoiceLock.vector(json);
                if (v != null) { lastSpk = v; lastSpkAt = t; }
            }
            if (!wantWord) return false;
            org.json.JSONArray words = new org.json.JSONObject(json).optJSONArray("result");
            for (int i = 0; words != null && i < words.length(); i++) {
                org.json.JSONObject w = words.getJSONObject(i);
                if ("jarvis".equals(w.optString("word")) && w.optDouble("conf", 0) >= voskConf) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * The word / voice models, only when already downloaded on the phone (Settings → వేక్ వర్డ్). Loaded here and now
     * (the watch's sound waits in its queue meanwhile), so the first wrist raise after a quiet spell is checked too.
     */
    private void loadVosk() {
        if ((!wantWord && !wantLock) || vosk != null) return;
        if (!VoskModel.ready(ctx)) {
            status = "\"Jarvis\" ఒక్క పదం / మీ గొంతు గుర్తింపు మోడల్ ఫోన్‌లో ఇంకా లేదు: వాచ్‌లో \"Hey Jarvis\" అనండి (ఫోన్ వేక్ వర్డ్ ఒకసారి ఆన్ చేస్తే మోడల్ వస్తుంది)";
            return;
        }
        org.vosk.Model m = null;
        org.vosk.Recognizer r = null;
        org.vosk.SpeakerModel spk = null;
        try {
            org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
            m = new org.vosk.Model(VoskModel.dir(ctx).getAbsolutePath());
            r = new org.vosk.Recognizer(m, 16000f, "[\"jarvis\", \"hey jarvis\", \"[unk]\"]");
            r.setWords(true);
            String st = "";
            if (wantLock) {
                if (new java.io.File(VoskModel.spkDir(ctx), ".ready").exists()) {
                    spk = new org.vosk.SpeakerModel(VoskModel.spkDir(ctx).getAbsolutePath());
                    r.setSpeakerModel(spk);
                } else {
                    st = "గొంతు గుర్తింపు మోడల్ ఫోన్‌లో లేదు: వాచ్ అందరి గొంతుకీ పలుకుతుంది (Settings → వేక్ వర్డ్ → మీ గొంతు నేర్పించండి)";
                }
            }
            synchronized (this) { voskModel = m; spkModel = spk; vosk = r; }
            m = null; r = null; spk = null;
            status = st;
        } catch (Throwable e) {
            status = "\"Jarvis\" పదం సిద్ధం కాలేదు (" + e.getMessage() + "): వాచ్‌లో \"Hey Jarvis\" అనండి";
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
            try { if (m != null) m.close(); } catch (Throwable ignored) {}
            try { if (spk != null) spk.close(); } catch (Throwable ignored) {}
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private void load() throws Exception {
        if (ww != null) return;
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);
        mel = env.createSession(asset("melspectrogram.onnx"), opts);
        emb = env.createSession(asset("embedding_model.onnx"), opts);
        ww = env.createSession(asset("hey_jarvis_v0.1.onnx"), opts);
        melIn = mel.getInputNames().iterator().next();
        embIn = emb.getInputNames().iterator().next();
        wwIn = ww.getInputNames().iterator().next();
    }

    /** Lets everything go (unused for a while, or the app going away). */
    void close() {
        try { if (mel != null) mel.close(); } catch (Exception ignored) {}
        try { if (emb != null) emb.close(); } catch (Exception ignored) {}
        try { if (ww != null) ww.close(); } catch (Exception ignored) {}
        mel = emb = ww = null;
        closeVosk();
    }

    private void closeVosk() {
        org.vosk.Recognizer r;
        org.vosk.Model m;
        org.vosk.SpeakerModel s;
        synchronized (this) { r = vosk; m = voskModel; s = spkModel; vosk = null; voskModel = null; spkModel = null; }
        try { if (r != null) r.close(); } catch (Throwable ignored) {}
        try { if (m != null) m.close(); } catch (Throwable ignored) {}
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }

    /** One 80 ms step of openWakeWord (as WakeEngine.step); the "Hey Jarvis" score. */
    private float step(short[] chunk) throws Exception {
        float[] audio = new float[CONTEXT + CHUNK];
        for (int i = 0; i < CONTEXT; i++) audio[i] = previous[i];
        for (int i = 0; i < CHUNK; i++) audio[CONTEXT + i] = chunk[i];
        System.arraycopy(chunk, CHUNK - CONTEXT, previous, 0, CONTEXT);
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), new long[]{1, audio.length});
             OrtSession.Result r = mel.run(Collections.singletonMap(melIn, t))) {
            FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
            int frames = fb.remaining() / MEL_BINS;
            for (int f = 0; f < frames; f++) {
                float[] row = new float[MEL_BINS];
                fb.get(row);
                for (int j = 0; j < MEL_BINS; j++) row[j] = row[j] / 10f + 2f;
                melFrames.addLast(row);
            }
        }
        while (melFrames.size() > MEL_WINDOW) melFrames.removeFirst();
        float[] window = new float[MEL_WINDOW * MEL_BINS];
        int k = 0;
        for (float[] row : melFrames) { System.arraycopy(row, 0, window, k, MEL_BINS); k += MEL_BINS; }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), new long[]{1, MEL_WINDOW, MEL_BINS, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, t))) {
            float[] feat = new float[FEATURE_SIZE];
            ((OnnxTensor) r.get(0)).getFloatBuffer().get(feat);
            features.addLast(feat);
        }
        while (features.size() > FEATURES) features.removeFirst();
        if (features.size() < FEATURES) return 0f;
        float[] x = new float[FEATURES * FEATURE_SIZE];
        k = 0;
        for (Iterator<float[]> it = features.iterator(); it.hasNext(); k += FEATURE_SIZE) System.arraycopy(it.next(), 0, x, k, FEATURE_SIZE);
        float score;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, FEATURES, FEATURE_SIZE});
             OrtSession.Result r = ww.run(Collections.singletonMap(wwIn, t))) {
            score = ((OnnxTensor) r.get(0)).getFloatBuffer().get(0);
        }
        return ++scored <= 5 ? 0f : score;
    }
}
