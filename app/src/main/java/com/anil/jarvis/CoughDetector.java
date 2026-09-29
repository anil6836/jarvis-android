package com.anil.jarvis;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hears Anil coughing (or sneezing) on the microphone the wake word already listens with, and asks if he is okay.
 * Google's YAMNet sound model (521 sounds; 42 = cough, 44 = sneeze) runs on the phone only when a short loud sound
 * happens, on its own thread; nothing is recorded or sent anywhere. A few coughs close together = he is coughing:
 * "సర్, ఏమైంది? దగ్గుతున్నారు." in different words each time (at most once per the gap he chose, 1 hour by default;
 * not at night, in a call, or while Jarvis is talking).
 */
final class CoughDetector {
    private static final int N = 15600;              // 0.975 s at 16 kHz: one YAMNet window
    private static final int COUGH = 42, THROAT = 43, SNEEZE = 44, CLASSES = 521;
    private static final float HIT = 0.4f;

    /** What happened with the model, for Settings (empty = not started yet). */
    static volatile String status = "";

    private final Context ctx;
    private final short[] ring = new short[N];
    private int pos;
    private boolean full;
    private int tail = -1;                           // chunks still to collect after a loud sound, then classify
    private long lastRun, prefsAt;
    private boolean on;
    private volatile boolean busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "jarvis-cough"));

    private org.tensorflow.lite.Interpreter tfl;
    private volatile boolean failed;
    private boolean twoDim;
    private int outIndex = -1;
    private final ArrayDeque<Long> coughs = new ArrayDeque<>(), strong = new ArrayDeque<>(), sneezes = new ArrayDeque<>();

    CoughDetector(Context c) { ctx = c.getApplicationContext(); }

    /** The microphone (re)starts: old audio from before a pause must not be mixed in. */
    void reset() {
        pos = 0;
        full = false;
        tail = -1;
        prefsAt = 0;
    }

    /** Every 80 ms chunk from the wake-word microphone, with its loudness and the background loudness. */
    void feed(short[] chunk, double rms, double noise) {
        for (short s : chunk) {
            ring[pos++] = s;
            if (pos == N) { pos = 0; full = true; }
        }
        if (tail > 0) {
            if (--tail == 0) classify();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - prefsAt > 10000) {
            prefsAt = now;
            Prefs p = new Prefs(ctx);
            // off, broken, or asked a short while ago: don't even run the model (battery)
            on = p.coughAsk() && !failed && System.currentTimeMillis() - p.sp.getLong("cough_asked", 0) > p.coughGapMinutes() * 60000L;
        }
        if (!on || !full || busy) return;
        // a cough is a short, sharp burst well above the room's background
        if (rms > Math.max(900, noise * 4) && now - lastRun > 1200) tail = 5; // ~0.4 s more so the whole cough is in the window
    }

    private void classify() {
        if (busy || failed) return;
        lastRun = SystemClock.elapsedRealtime();
        float[] x = new float[N];
        for (int i = 0; i < N; i++) x[i] = ring[(pos + i) % N] / 32768f; // oldest first
        busy = true;
        worker.execute(() -> {
            try {
                float[] s = run(x);
                if (s != null) onScores(s);
            } catch (Throwable e) {
                failed = true;
                status = "దగ్గు గుర్తింపు పనిచేయలేదు: " + e.getMessage();
            } finally {
                busy = false;
            }
        });
    }

    private float[] run(float[] x) throws Exception {
        if (tfl == null) {
            ByteBuffer model;
            try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd("yamnet.tflite");
                 java.io.FileInputStream fin = new java.io.FileInputStream(fd.getFileDescriptor())) {
                // stored uncompressed in the APK: mapped straight from the file, no copy in memory
                model = fin.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
            } catch (java.io.FileNotFoundException e) {
                failed = true;
                status = "దగ్గు గుర్తింపు మోడల్ ఈ వెర్షన్‌లో లేదు.";
                return null;
            } catch (java.io.IOException e) { // compressed after all: read it in
                try (InputStream in = ctx.getAssets().open("yamnet.tflite")) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    byte[] all = out.toByteArray();
                    model = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
                    model.put(all);
                    model.rewind();
                }
            }
            org.tensorflow.lite.Interpreter.Options o = new org.tensorflow.lite.Interpreter.Options();
            o.setNumThreads(1);
            tfl = new org.tensorflow.lite.Interpreter(model, o);
            // the model takes 15600 samples as [15600] or [1, 15600] (some exports take any length: fix it to one window)
            int[] shape = tfl.getInputTensor(0).shape();
            twoDim = shape.length == 2;
            if ((twoDim && shape[1] != N) || (!twoDim && (shape.length == 0 || shape[0] != N))) {
                tfl.resizeInput(0, twoDim ? new int[]{1, N} : new int[]{N});
            }
            tfl.allocateTensors();
            for (int i = 0; i < tfl.getOutputTensorCount(); i++) {
                int[] os = tfl.getOutputTensor(i).shape();
                if (os.length > 0 && os[os.length - 1] == CLASSES) { outIndex = i; break; }
            }
            if (outIndex < 0) throw new IllegalStateException("sound classes not found");
            status = "దగ్గు గుర్తింపు సిద్ధం ✓";
        }
        Object input = twoDim ? new float[][]{x} : x;
        int bytes = tfl.getOutputTensor(outIndex).numBytes();
        ByteBuffer out = ByteBuffer.allocateDirect(Math.max(bytes, CLASSES * 4)).order(ByteOrder.nativeOrder());
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(outIndex, out);
        tfl.runForMultipleInputsOutputs(new Object[]{input}, outputs);
        out.rewind();
        int frames = Math.max(1, out.capacity() / 4 / CLASSES);
        float[] s = new float[CLASSES];
        for (int f = 0; f < frames; f++) for (int k = 0; k < CLASSES; k++) s[k] = Math.max(s[k], out.getFloat());
        return s;
    }

    private void onScores(float[] s) {
        long now = System.currentTimeMillis();
        // clear coughs count double; a single throat-clearing doesn't count at all
        if (s[COUGH] >= 0.55f) { coughs.addLast(now); strong.addLast(now); }
        else if (s[COUGH] >= HIT || (s[THROAT] >= 0.6f && s[COUGH] >= 0.25f)) coughs.addLast(now);
        if (s[SNEEZE] >= HIT) sneezes.addLast(now);
        while (!coughs.isEmpty() && now - coughs.peekFirst() > 15 * 60000L) coughs.removeFirst();
        while (!strong.isEmpty() && now - strong.peekFirst() > 60000L) strong.removeFirst();
        while (!sneezes.isEmpty() && now - sneezes.peekFirst() > 5 * 60000L) sneezes.removeFirst();
        // a coughing fit: 2 clear coughs within 45 s, or 3 within 2 minutes, or 4 in 15 minutes
        if (count(strong, now, 45000) >= 2 || count(coughs, now, 120000) >= 3 || coughs.size() >= 4) ask("cough");
        else if (count(sneezes, now, 60000) >= 2) ask("sneeze");
    }

    private static int count(ArrayDeque<Long> q, long now, long within) {
        int n = 0;
        for (long t : q) if (now - t <= within) n++;
        return n;
    }

    private void ask(String kind) {
        coughs.clear();
        strong.clear();
        sneezes.clear();
        Prefs p = new Prefs(ctx);
        long last = p.sp.getLong("cough_asked", 0);
        if (System.currentTimeMillis() - last < p.coughGapMinutes() * 60000L) return;
        if (p.night() || CallControl.busyWithCall() || MainActivity.busyTalking() || Rest.resting(ctx)) return;
        // probably asleep: late night with the screen off, or the afternoon after coming off a 48-hour duty
        try {
            android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
            boolean screenOn = pm == null || pm.isInteractive();
            int h = java.time.LocalTime.now().getHour();
            if (!screenOn && (h >= 22 || h < 7)) return;
            Duty.Roster r = Duty.load(ctx);
            java.time.LocalDate today = java.time.LocalDate.now();
            if (!screenOn && Duty.ready(r) && r.isOn(Duty.ME, today.minusDays(1)) && !r.isOn(Duty.ME, today)) return;
        } catch (Exception ignored) {}
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return;
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am != null && am.isMusicActive()) return; // a cough in a video on the phone
        } catch (Exception ignored) {}
        p.sp.edit().putLong("cough_asked", System.currentTimeMillis()).apply();
        boolean sneeze = kind.equals("sneeze");
        String[][] lines = sneeze ? SNEEZE_ASK : (p.sp.getInt("cough_times_today", 0) > 0 && today(p) ? COUGH_AGAIN : COUGH_ASK);
        // a different one each time: never the same words twice in a row
        String key = sneeze ? "sneeze_line" : "cough_line";
        int lastLine = p.sp.getInt(key, -1), i;
        do { i = rnd.nextInt(lines.length); } while (lines.length > 1 && i == lastLine);
        int times = today(p) ? p.sp.getInt("cough_times_today", 0) + 1 : 1;
        android.content.SharedPreferences.Editor e = p.sp.edit().putInt(key, i);
        if (!sneeze) e.putInt("cough_times_today", times).putString("cough_day", java.time.LocalDate.now().toString());
        e.apply();
        String hint = sneeze
                ? " [health: Jarvis heard him sneezing again and again. If he says yes or tells what is wrong, use health_advice (cold / what he says): "
                        + "home remedies first; the tablet name only when he asks for it; which doctor if it does not settle. If he says no / వద్దు, just say okay, in a few warm words.]"
                : " [health: Jarvis heard him coughing several times" + (times > 1 ? " (Jarvis already asked " + (times - 1) + " time(s) about it today)" : "") + ". If he says yes or tells what is wrong, "
                        + "use health_advice (cough / what he says): home remedies first; the tablet name and how to take it when he asks; which doctor if it does not settle"
                        + (times > 2 ? "; as it keeps coming back today, gently suggest seeing a doctor" : "") + ". If he says no / వద్దు, just say okay, in a few warm words.]";
        Proactive.say(ctx, lines[i][0], lines[i][1], hint);
    }

    private static final java.util.Random rnd = new java.util.Random();

    private static boolean today(Prefs p) { return java.time.LocalDate.now().toString().equals(p.sp.getString("cough_day", "")); }

    /** {what Jarvis says, the question}: many ways, so it doesn't sound like a recording. */
    private static final String[][] COUGH_ASK = {
            {"సర్, ఏమైంది? దగ్గుతున్నారు.", "ఏమైనా సమస్య ఉందా? ముందు ఇంటి చిట్కాలు చెప్పనా, లేక దగ్గు టాబ్లెట్ వేసుకుంటారా?"},
            {"సర్, దగ్గు వినిపిస్తోంది.", "ఒంట్లో బాగోలేదా? గోరువెచ్చని నీళ్లు తాగుతారా, లేక ఏదైనా చిట్కా చెప్పనా?"},
            {"సర్, బాగున్నారా? కాసేపటి నుంచి దగ్గుతున్నారు.", "గొంతు ఇబ్బందిగా ఉందా? ఇంటి చిట్కా చెప్పనా, టాబ్లెట్ పేరు చెప్పనా?"},
            {"సర్, దగ్గు ఎక్కువగా ఉన్నట్టుంది.", "జలుబు కూడా ఉందా? ఏం చేస్తే తగ్గుతుందో చెప్పమంటారా?"},
            {"సర్, అంతా ఓకేనా? దగ్గుతున్నారు.", "తేనె-అల్లం లాంటి చిట్కా చెప్పనా, లేక దగ్గు సిరప్ పేరు కావాలా?"},
            {"సర్, కొంచెం దగ్గుతున్నారు.", "గొంతు గరగరగా ఉందా? ఉప్పు నీళ్లతో పుక్కిలిస్తే బాగుంటుంది. ఇంకా ఏమైనా చెప్పనా?"},
            {"సర్, జాగ్రత్త, దగ్గు వస్తోంది.", "ఏమైనా ఇబ్బందిగా ఉందా? చిట్కాలా, టాబ్లెట్టా, ఏది చెప్పమంటారు?"},
    };
    private static final String[][] COUGH_AGAIN = {
            {"సర్, మళ్లీ దగ్గుతున్నారు.", "ఇంకా తగ్గలేదా? ఇంకో చిట్కా చెప్పనా, లేక టాబ్లెట్ వేసుకుంటారా?"},
            {"సర్, దగ్గు ఇంకా ఆగలేదు.", "ఇబ్బందిగా ఉంటే టాబ్లెట్ పేరు చెప్పనా? తగ్గకపోతే ఏ డాక్టర్‌ని చూడాలో కూడా చెప్తాను."},
            {"సర్, ఇవాళ దగ్గు ఎక్కువగానే ఉంది.", "గోరువెచ్చని నీళ్లు, ఆవిరి పట్టడం చేశారా? టాబ్లెట్ కావాలా?"},
            {"సర్, మళ్లీ దగ్గు వినిపిస్తోంది.", "జ్వరం ఏమైనా ఉందా? ఏం చేయాలో చెప్పమంటారా?"},
    };
    private static final String[][] SNEEZE_ASK = {
            {"సర్, తుమ్ముతున్నారు, జలుబు చేసిందా?", "ఏమైనా సమస్య ఉందా? ముందు ఇంటి చిట్కాలు చెప్పనా, లేక జలుబు టాబ్లెట్ పేరు చెప్పనా?"},
            {"సర్, వరుసగా తుమ్ములు వస్తున్నాయి.", "జలుబా, లేక దుమ్ము అలర్జీనా? ఆవిరి పట్టడం లాంటి చిట్కా చెప్పనా?"},
            {"సర్, బాగున్నారా? తుమ్ములు వినిపిస్తున్నాయి.", "ముక్కు కారుతుందా? ఏం చేస్తే తగ్గుతుందో చెప్పమంటారా?"},
            {"సర్, జలుబు పట్టినట్టుంది.", "అల్లం టీ లాంటి ఇంటి చిట్కా చెప్పనా, లేక టాబ్లెట్ పేరు కావాలా?"},
    };

    void close() {
        worker.execute(() -> {
            try { if (tfl != null) tfl.close(); } catch (Throwable ignored) {}
            tfl = null;
        });
        worker.shutdown();
    }
}
