package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

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
 * On-device "Hey Jarvis" detector using the openWakeWord models (no account or key needed).
 *
 * Pipeline, every 80 ms of 16 kHz audio (same as openWakeWord's streaming mode):
 *   1760 samples (1280 new + 480 previous) -> melspectrogram.onnx -> 8 frames x 32 bins (x/10 + 2)
 *   last 76 mel frames -> embedding_model.onnx -> one 96-value feature
 *   last 16 features -> hey_jarvis model -> score 0..1
 */
final class WakeEngine {
    interface Listener {
        void onWake(float score);
        void onError(String message);
    }

    private static final int RATE = 16000;
    private static final int CHUNK = 1280;
    private static final int CONTEXT = 480;
    private static final int MEL_BINS = 32;
    private static final int MEL_WINDOW = 76;
    private static final int FEATURES = 16;
    private static final int FEATURE_SIZE = 96;

    private final Context ctx;
    private final Listener listener;
    private final float threshold;

    private OrtEnvironment env;
    private OrtSession mel, emb, ww;
    private String melIn, embIn, wwIn;

    private volatile boolean running;
    private Thread thread;

    private final ArrayDeque<float[]> melFrames = new ArrayDeque<>();
    private final ArrayDeque<float[]> features = new ArrayDeque<>();
    private final short[] previous = new short[CONTEXT];
    private int scored;

    WakeEngine(Context c, float threshold, Listener listener) {
        this.ctx = c.getApplicationContext();
        this.threshold = threshold;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "jarvis-wake");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
    }

    void close() {
        stop();
        try { if (mel != null) mel.close(); } catch (Exception ignored) {}
        try { if (emb != null) emb.close(); } catch (Exception ignored) {}
        try { if (ww != null) ww.close(); } catch (Exception ignored) {}
        mel = emb = ww = null;
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

    private void reset() {
        melFrames.clear();
        features.clear();
        java.util.Arrays.fill(previous, (short) 0);
        // openWakeWord starts its mel buffer with 76 frames of ones.
        for (int i = 0; i < MEL_WINDOW; i++) {
            float[] ones = new float[MEL_BINS];
            java.util.Arrays.fill(ones, 1f);
            melFrames.addLast(ones);
        }
        scored = 0;
    }

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before starting
    private void run() {
        AudioRecord rec = null;
        try {
            load();
            reset();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2 * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("మైక్ తెరవలేకపోయాను");
            rec.startRecording();
            short[] chunk = new short[CHUNK];
            long quietUntil = 0;
            while (running) {
                int n = 0;
                while (n < CHUNK && running) {
                    int r = rec.read(chunk, n, CHUNK - n);
                    if (r < 0) throw new IllegalStateException("మైక్ చదవడం ఆగిపోయింది (" + r + ")");
                    n += r;
                }
                if (!running) break;
                float score = step(chunk);
                long now = SystemClock.elapsedRealtime();
                if (score >= threshold && now > quietUntil) {
                    quietUntil = now + 2000;
                    listener.onWake(score);
                }
            }
        } catch (Throwable e) {
            running = false;
            listener.onError(String.valueOf(e.getMessage()));
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
            }
        }
    }

    /** Feeds 1280 new samples; returns the wake-word score (0 while warming up). */
    float step(short[] chunk) throws Exception {
        // 1) mel spectrogram of 480 old + 1280 new samples
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

        // 2) one embedding from the last 76 mel frames
        float[] window = new float[MEL_WINDOW * MEL_BINS];
        int k = 0;
        for (float[] row : melFrames) { System.arraycopy(row, 0, window, k, MEL_BINS); k += MEL_BINS; }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), new long[]{1, MEL_WINDOW, MEL_BINS, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, t))) {
            FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
            float[] feat = new float[FEATURE_SIZE];
            fb.get(feat);
            features.addLast(feat);
        }
        while (features.size() > FEATURES) features.removeFirst();
        if (features.size() < FEATURES) return 0f;

        // 3) wake-word score from the last 16 embeddings
        float[] x = new float[FEATURES * FEATURE_SIZE];
        k = 0;
        for (Iterator<float[]> it = features.iterator(); it.hasNext(); k += FEATURE_SIZE) {
            System.arraycopy(it.next(), 0, x, k, FEATURE_SIZE);
        }
        float score;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, FEATURES, FEATURE_SIZE});
             OrtSession.Result r = ww.run(Collections.singletonMap(wwIn, t))) {
            score = ((OnnxTensor) r.get(0)).getFloatBuffer().get(0);
        }
        // openWakeWord ignores the first 5 predictions while buffers settle.
        return ++scored <= 5 ? 0f : score;
    }
}
