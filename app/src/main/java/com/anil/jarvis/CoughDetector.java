package com.anil.jarvis;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hears Anil coughing or sneezing on the microphone the wake word already listens with, and asks if he is okay.
 * Google's YAMNet sound model (521 sounds; 42 = cough, 43 = throat clearing, 44 = sneeze) runs on the phone only after a
 * short loud sound, on its own thread; nothing is recorded or sent anywhere.
 *
 * How (tuned on real cough / sneeze recordings, loud and soft, in quiet and noisy rooms):
 * - a soft cough a metre away is enough to start it (it used to need a loud one);
 * - three 1-second windows ending 0.24 / 0.48 / 0.72 s after the sound start, so the whole cough or the
 *   "ఆ...ఛీ" of a sneeze is inside one of them; a soft one is also heard a second time with the room hiss
 *   pushed down and the level raised (the model misses soft sounds over hiss);
 * - cough or sneeze is decided on the same sound by which is stronger (throat clearing counts for cough), so a
 *   cough is no longer turned into a sneeze (before, a sneeze needed 2 sounds and a cough 3);
 * - a clear one -> asks at once (about a second after); a faint one -> asks when a second comes within 90 s;
 * - cough and sneeze each keep their own "asked" time (asking about one doesn't make Jarvis deaf to the other);
 * - when he says Jarvis got it wrong ("అది దగ్గు, తుమ్ము కాదు"), that sound is kept and the next one like it is
 *   taken as what he said.
 * Not at night, in a call, while Jarvis is talking or while the phone plays a video.
 */
final class CoughDetector {
    private static final int N = 15600;              // 0.975 s at 16 kHz: one YAMNet window
    private static final int COUGH = 42, THROAT = 43, SNEEZE = 44, CLASSES = 521;
    /** Windows end this many 80 ms chunks after the loud chunk. */
    private static final int[] AFTER = {3, 6, 9};
    private static final float FOUND = 0.3f, FOUND_SNEEZE = 0.25f, CLEAR = 0.5f;
    /** Loudness (16-bit RMS of an 80 ms chunk) a soft cough about a metre from the phone still reaches. */
    private static final int MIN_LOUD = 220;
    /** Sounds kept from his corrections: speech, laugh, sigh, groan, grunt, breath, wheeze, snore, gasp, pant, snort, cough, throat, sneeze, sniff, burp, hiccup. */
    private static final int[] FEAT = {0, 13, 23, 33, 34, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 53, 54};

    /** What happened with the model, for Settings (empty = not started yet). */
    static volatile String status = "";
    /** The last sound it judged (time, what, how sure), for Settings and "Jarvis చెక్". */
    static volatile String lastHeard = "";
    /** The sound Jarvis last asked about, so his "అది దగ్గు / తుమ్ము" can teach it. */
    private static volatile float[] askedFeat;
    private static volatile long askedAt;
    private static volatile String askedKind = "";

    private final Context ctx;
    private final short[] ring = new short[N];
    private int pos;
    private boolean full;
    private int since = -1;                          // chunks since the loud chunk (-1 = waiting for one)
    private final float[][] wins = new float[AFTER.length][];
    private double eventNoise, eventLoud;
    private int loudChunks;                          // how many of the event's 80 ms pieces stayed loud (a whistle stays, a cough doesn't)
    private long eventAt;                            // when the loud sound began (wall clock)
    private final float[] eventRms = new float[10];  // each piece's loudness (two claps = two peaks)
    private long lastSample;                         // the last steady sound sampled (snoring at night, rain by day)
    private int sampleIn = -1;                       // pieces to wait before sampling (a burst starting meanwhile cancels it)
    private boolean nightNow;                        // 10 pm - 7 am (looked at every 10 s)
    private long lastRun, prefsAt;
    private boolean on;
    private volatile boolean busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "jarvis-cough"));

    private org.tensorflow.lite.Interpreter tfl;
    private volatile boolean missing;                // no model in this build: nothing to do
    private volatile int failures;
    private volatile long failedAt;
    private boolean twoDim;
    private int outIndex = -1;
    /** The last sound judged was a cough / sneeze (then it isn't looked at as anything else). */
    private boolean coughOrSneeze;
    /** A faint cough / sneeze heard: when (a second one within 90 s makes it sure). */
    private long faintCough, faintSneeze;

    CoughDetector(Context c) { ctx = c.getApplicationContext(); }

    /** The microphone (re)starts: old audio from before a pause must not be mixed in. */
    void reset() {
        pos = 0;
        full = false;
        since = -1;
        prefsAt = 0;
        SafetySounds.resetWatch(); // a fall watch from before the pause is not judged on the missing sound
    }

    /** Every 80 ms chunk from the wake-word microphone, with its loudness and the background loudness. */
    void feed(short[] chunk, double rms, double noise) {
        for (short s : chunk) {
            ring[pos++] = s;
            if (pos == N) { pos = 0; full = true; }
        }
        try { SafetySounds.tick(ctx, rms, noise); } catch (Throwable ignored) {} // a heavy thud a moment ago: is it quiet after it?
        if (since >= 0) { // collecting the windows after a loud sound
            since++;
            if (since < eventRms.length) eventRms[since] = (float) rms;
            eventLoud = Math.max(eventLoud, rms);
            if (rms > Math.max(MIN_LOUD * 0.7, eventNoise * 3)) loudChunks++;
            for (int i = 0; i < AFTER.length; i++) if (since == AFTER[i]) wins[i] = window();
            if (since >= AFTER[AFTER.length - 1]) {
                since = -1;
                classify(wins.clone(), eventNoise, eventLoud, loudChunks, eventRms.clone(), nightNow, eventAt);
                java.util.Arrays.fill(wins, null);
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - prefsAt > 10000) {
            prefsAt = now;
            Prefs p = new Prefs(ctx);
            // off and nothing else wants it (house sounds), or no model: don't even run the model (battery).
            // Coughs are counted all day (the daily count), so asking a short while ago no longer stops it.
            on = !missing && failures < 5 && (p.coughAsk() || Sounds.wanted(ctx) || BodySounds.callOn(ctx) || BodySounds.asksOn(ctx)
                    || BodySounds.tiredOn(ctx) || SafetySounds.wanted(ctx) || HomeGuard.listening(ctx));
            int h = java.time.LocalTime.now().getHour();
            nightNow = h >= 22 || h < 7;
        }
        boolean live = Sounds.holdMic(ctx); // a test, counting whistles, learning a sound: at once, not after 10 s
        if (!(on || (live && !missing)) || !full || busy) return;
        if (failures > 0 && now - failedAt < 120_000) return; // something went wrong a moment ago: rest a little
        // a cough / sneeze / knock starts as a short burst well above the room's background
        if (rms > Math.max(MIN_LOUD, noise * 4) && now - lastRun > 1000) {
            sampleIn = -1; // a burst: the steady-sound look waits
            since = 0;
            eventAt = System.currentTimeMillis();
            eventNoise = noise;
            eventLoud = rms;
            loudChunks = 1;
            java.util.Arrays.fill(eventRms, 0f);
            eventRms[0] = (float) rms;
            lastRun = now;
            return;
        }
        // steady sounds have no burst: at night a snore in progress (a little above the room) every 15 s at most;
        // by day, when the room is noisy (rain), one look every 30 s
        boolean sampleNow = nightNow ? (rms > Math.max(120, noise * 2.0) && now - lastSample > 15_000)
                : (noise > 35 && now - lastSample > 30_000 && Sounds.rainOn(ctx));
        if (sampleIn > 0) {
            if (--sampleIn == 0) { sampleIn = -1; lastSample = now; sample(window(), nightNow); }
        } else if (sampleNow) {
            lastSample = now;   // (so it isn't armed again meanwhile)
            sampleIn = 2;       // two more pieces: if it was the start of a cough, the burst takes it instead
        }
    }

    /** One window of a steady sound (snoring, rain): a single quick look, no asking about coughs. */
    private void sample(float[] w, boolean night) {
        if (busy || missing) return;
        busy = true;
        worker.execute(() -> {
            try {
                float[] s = run(w);
                if (s == null) return;
                boolean test = testing();
                if (HomeGuard.listening(ctx) && !test) return; // the phone at home keeping guard: no snoring / rain talk there
                String what = BodySounds.sampled(ctx, s, night, test);
                String rain = Sounds.sampled(ctx, s, night, test);
                if (test && (what != null || rain != null)) testLine(java.time.LocalTime.now().withNano(0) + "  ➜ " + (what != null ? what : rain) + "\n" + top3(s));
            } catch (Throwable ignored) {
            } finally {
                busy = false;
            }
        });
    }

    /** The last second now (the moment "Jarvis" was heard), to look at a little later. */
    float[] snapshot() { return full ? window() : null; }

    /** "Jarvis" was just heard: was it whispered? (the last second has the word in it). Answers softly if so. */
    void wakeHeard() { wakeHeard(snapshot()); }

    void wakeHeard(float[] w) {
        if (missing || w == null) return;
        worker.execute(() -> {
            try {
                float[] s = run(w);
                if (s != null) Whisper.heard(ctx, s[12], s[0]);
            } catch (Throwable ignored) {}
        });
    }

    // ---------------------------------------------------------------- the test screen (Settings)

    private static volatile long testUntil;
    private static final java.util.ArrayDeque<String> testLines = new java.util.ArrayDeque<>();
    /** The last sound's scores (for "the last sound was my bell"). */
    static volatile float[] lastScores;

    /** For the next seconds: every loud sound is shown with what Jarvis made of it, and nothing is asked or counted. */
    static void startTest(int seconds) {
        synchronized (testLines) { testLines.clear(); }
        lastScores = null; // "the last one was my bell" must be a sound from this test
        testUntil = System.currentTimeMillis() + seconds * 1000L;
    }

    static void stopTest() { testUntil = 0; }

    static boolean testing() { return System.currentTimeMillis() < testUntil; }

    static long testLeftMs() { return Math.max(0, testUntil - System.currentTimeMillis()); }

    static String testText() {
        synchronized (testLines) {
            StringBuilder b = new StringBuilder();
            for (String s : testLines) b.append(s).append("\n\n");
            return b.toString().trim();
        }
    }

    private static void testLine(String s) {
        synchronized (testLines) {
            testLines.addFirst(s);
            while (testLines.size() > 8) testLines.removeLast();
        }
    }

    /** Sounds worth naming on the test screen, by the model's number. */
    private static final int[] SHOWN = {COUGH, THROAT, SNEEZE, 45, 54, 53, 51, 0, 13, 23, 36, 37, 38, 39, 132, 349, 350, 353, 348, 396, 397, 290, 58, 48, 395, 393, 494,
            11, 6, 19, 20, 22, 33, 454, 460, 437, 70, 394, 518};
    private static final String[] SHOWN_TE = {"దగ్గు", "గొంతు సవరణ", "తుమ్ము", "ముక్కు ఎగబీల్చడం", "ఎక్కిళ్లు", "త్రేన్పు", "పుక్కిలించడం", "మాటలు", "నవ్వు", "నిట్టూర్పు",
            "ఊపిరి", "గురగుర", "గురక", "ఉలిక్కిపాటు", "సంగీతం", "కాలింగ్ బెల్", "డింగ్-డాంగ్", "తలుపు కొట్టడం", "తలుపు", "విజిల్", "కుక్కర్/ఆవిరి విజిల్", "ఆవిరి",
            "చప్పట్లు", "అడుగులు", "ఫోగ్ హార్న్", "స్మోక్ అలారం", "నిశ్శబ్దం",
            "అరుపు", "కేక", "ఏడుపు", "పిల్లల ఏడుపు", "మూలుగు/ఏడ్పు", "మూలుగు", "ధబ్ శబ్దం", "ఢాం శబ్దం", "గాజు పగలడం", "కుక్క మొరుగు", "ఫైర్ అలారం", "టీవీ"};

    private static String top3(float[] s) {
        StringBuilder b = new StringBuilder();
        boolean[] used = new boolean[SHOWN.length];
        for (int n = 0; n < 3; n++) {
            int bi = -1;
            for (int i = 0; i < SHOWN.length; i++) if (!used[i] && (bi < 0 || s[SHOWN[i]] > s[SHOWN[bi]])) bi = i;
            if (bi < 0 || s[SHOWN[bi]] < 0.05f) break;
            used[bi] = true;
            b.append(n > 0 ? ", " : "").append(SHOWN_TE[bi]).append(' ').append(Math.round(s[SHOWN[bi]] * 100)).append('%');
        }
        return b.length() == 0 ? "తెలియని శబ్దం" : b.toString();
    }

    /** The last 0.975 s, oldest first, as the model wants it (-1..1). */
    private float[] window() {
        float[] x = new float[N];
        for (int i = 0; i < N; i++) x[i] = ring[(pos + i) % N] / 32768f;
        return x;
    }

    /**
     * The same window with the room's hiss between the sounds pushed down and its level raised (at most 8x):
     * the model misses soft coughs over hiss, and hears them well like this.
     */
    private static float[] cleaned(float[] w, double noise) {
        float[] y = w.clone();
        float hiss = (float) (Math.max(noise, 8) / 32768.0) * 3f;
        for (int a = 0; a + 120 <= N; a += 120) { // 7.5 ms pieces
            double e = 0;
            for (int i = a; i < a + 120; i++) e += (double) y[i] * y[i];
            if (Math.sqrt(e / 120) < hiss) for (int i = a; i < a + 120; i++) y[i] *= 0.1f;
        }
        float peak = 1e-6f;
        for (float v : y) peak = Math.max(peak, Math.abs(v));
        float g = Math.min(8f, 0.3f / peak);
        for (int i = 0; i < N; i++) y[i] *= g;
        return y;
    }

    private void classify(float[][] ws, double noise, double loud, int loudN, float[] rmsSeq, boolean night, long soundAt) {
        if (busy || missing) return;
        busy = true;
        worker.execute(() -> {
            try {
                float[] best = new float[CLASSES];
                boolean any = false;
                for (float[] w : ws) {
                    if (w == null) continue;
                    float[] raw = run(w);
                    if (raw == null) return; // no model
                    any = true;
                    max(best, raw);
                    // not clear as heard: listen again with the hiss pushed down
                    if (Math.max(raw[COUGH], raw[SNEEZE]) < CLEAR) max(best, run(cleaned(w, noise)));
                }
                if (any) {
                    lastScores = best;
                    boolean test = testing();
                    if (HomeGuard.listening(ctx)) { // the phone at home keeping guard: house sounds go to his Telegram, nothing personal is asked here
                        String g = null;
                        try { g = HomeGuard.heard(ctx, best, loud, loudN, test); } catch (Throwable ignored) {}
                        if (test) testLine(java.time.LocalTime.now().withNano(0) + "  ➜ " + (g != null ? g : "కాపలా: ఏమీ కాదు")
                                + "\n" + top3(best) + " · శబ్దం " + Math.round(loud));
                        failures = 0;
                        return;
                    }
                    // a scream / a fall / crying first (S15, S18, S19); then the house sounds (door, cooker, rain)
                    String house = null;
                    try { house = SafetySounds.heard(ctx, best, loud, noise, test, soundAt); } catch (Throwable ignored) {}
                    if (house == null) try { house = Sounds.heard(ctx, best, loud, loudN); } catch (Throwable ignored) {}
                    String verdict = decide(best, loud);
                    if (!coughOrSneeze && house == null) { // nothing yet: a loud snore at night, another sound of his own (sniff, hiccup...) or a call (two claps)
                        try {
                            String body = BodySounds.sampled(ctx, best, night, testing()); // a loud snore comes as a burst too
                            if (body == null) body = BodySounds.heard(ctx, best, noise, loudN, rmsSeq, testing());
                            house = body;
                        } catch (Throwable ignored) {}
                    }
                    if (testing()) testLine(java.time.LocalTime.now().withNano(0) + "  ➜ " + (house != null ? house : verdict)
                            + "\n" + top3(best) + " · శబ్దం " + Math.round(loud) + (loudN >= 7 ? " · పొడవైన శబ్దం" : ""));
                }
                failures = 0;
            } catch (Throwable e) {
                failures++;
                failedAt = SystemClock.elapsedRealtime();
                status = "దగ్గు గుర్తింపు పనిచేయలేదు" + (failures >= 5 ? " (ఆపేశాను; Jarvis మళ్లీ తెరిస్తే మొదలవుతుంది)" : " (2 నిమిషాల్లో మళ్లీ ప్రయత్నిస్తాను)") + ": " + e.getMessage();
                try { if (tfl != null) tfl.close(); } catch (Throwable ignored) {}
                tfl = null; // made fresh on the next try
            } finally {
                busy = false;
            }
        });
    }

    private static void max(float[] into, float[] s) {
        if (s == null) return;
        for (int k = 0; k < CLASSES; k++) into[k] = Math.max(into[k], s[k]);
    }

    private float[] run(float[] x) throws Exception {
        if (tfl == null) {
            ByteBuffer model;
            try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd("yamnet.tflite");
                 java.io.FileInputStream fin = new java.io.FileInputStream(fd.getFileDescriptor())) {
                // stored uncompressed in the APK: mapped straight from the file, no copy in memory
                model = fin.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
            } catch (java.io.FileNotFoundException e) {
                missing = true;
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
            outIndex = -1;
            for (int i = 0; i < tfl.getOutputTensorCount(); i++) {
                int[] os = tfl.getOutputTensor(i).shape();
                if (os.length > 0 && os[os.length - 1] == CLASSES) { outIndex = i; break; }
            }
            if (outIndex < 0) throw new IllegalStateException("sound classes not found");
            status = "దగ్గు / తుమ్ము గుర్తింపు సిద్ధం ✓";
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

    /** Cough, sneeze or neither for one sound; counts it, and asks when it should. Returns the verdict in Telugu (for the test screen). */
    private String decide(float[] s, double loud) {
        float cough = s[COUGH], sneeze = s[SNEEZE];
        String kind = null;
        float sure = 0;
        // one sound, one answer: whichever is stronger (throat clearing goes with a cough)
        if (sneeze > cough + 0.5f * s[THROAT] && sneeze >= FOUND_SNEEZE) { kind = "sneeze"; sure = sneeze; }
        else if (cough >= FOUND) { kind = "cough"; sure = cough; }
        float[] f = feat(s);
        String taught = kind == null ? null : taught(f);
        if (taught != null && !taught.equals(kind)) { // he told Jarvis what a sound just like this one is
            if (taught.equals("none")) kind = null;
            else { kind = taught; sure = Math.max(sure, CLEAR); }
        }
        coughOrSneeze = kind != null;
        String verdict = (kind == null ? "దగ్గు / తుమ్ము కాదు" : kind.equals("cough") ? "దగ్గు" : "తుమ్ము")
                + (kind != null && sure < CLEAR ? " (మెల్లగా: ఇంకోటి వస్తే అడుగుతాను)" : "") + (taught != null ? " · మీరు నేర్పినట్టు" : "");
        if (testing()) return verdict; // the test only shows what was heard: nothing counted or asked
        lastHeard = java.time.LocalTime.now().withNano(0) + " " + (kind == null ? "ఏమీ కాదు" : kind.equals("cough") ? "దగ్గు" : "తుమ్ము")
                + " (దగ్గు " + Math.round(cough * 100) + "%, తుమ్ము " + Math.round(sneeze * 100) + "%, శబ్దం " + Math.round(loud) + ")"
                + (taught != null ? " · మీరు నేర్పినట్టు" : "");
        if (kind == null) return verdict;
        boolean video = false;
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            video = am != null && am.isMusicActive(); // a cough in a video / song on the phone: not his
        } catch (Exception ignored) {}
        if (!video) CoughLog.add(ctx, kind); // the daily count (also with the questions turned off)
        if (!new Prefs(ctx).coughAsk()) return verdict;
        long now = System.currentTimeMillis();
        if (sure < CLEAR) { // faint: wait for a second one
            boolean c = kind.equals("cough");
            long before = c ? faintCough : faintSneeze;
            if (c) faintCough = now; else faintSneeze = now;
            if (now - before > 90_000) return verdict;
        }
        faintCough = faintSneeze = 0;
        ask(kind, f);
        return verdict;
    }

    private static float[] feat(float[] s) {
        float[] f = new float[FEAT.length];
        for (int i = 0; i < FEAT.length; i++) f[i] = s[FEAT[i]];
        return f;
    }

    private static double similar(float[] a, float[] b) {
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length && i < b.length; i++) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : ab / Math.sqrt(aa * bb);
    }

    /** What he said a sound very like this one was ("cough" / "sneeze"), or null. */
    private String taught(float[] f) {
        try {
            JSONArray a = new JSONArray(new Prefs(ctx).sp.getString("cough_taught", "[]"));
            double bestSim = 0.93;
            String best = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray v = o.getJSONArray("f");
                float[] g = new float[v.length()];
                for (int k = 0; k < g.length; k++) g[k] = (float) v.getDouble(k);
                double sim = similar(f, g);
                if (sim > bestSim) { bestSim = sim; best = o.getString("k"); }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * He says what the sound Jarvis just asked about really was ("cough", "sneeze" or "none"): kept, so the next sound
     * like it is taken as that. Returns what to tell him.
     */
    static String learn(Context c, String what) {
        String k = what == null ? "" : what.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.startsWith("cou") || k.contains("దగ్గు")) k = "cough";
        else if (k.startsWith("sne") || k.contains("తుమ్ము")) k = "sneeze";
        else k = "none";
        float[] f = askedFeat;
        if (f == null || System.currentTimeMillis() - askedAt > 30 * 60000L) return "no recent sound to learn from";
        try {
            Prefs p = new Prefs(c);
            JSONArray a = new JSONArray(p.sp.getString("cough_taught", "[]"));
            JSONArray v = new JSONArray();
            for (float x : f) v.put(Math.round(x * 1000) / 1000.0);
            a.put(new JSONObject().put("k", k).put("f", v).put("t", System.currentTimeMillis()));
            // keep the last 12 of each kind
            JSONArray keep = new JSONArray();
            Map<String, Integer> left = new HashMap<>();
            for (int i = a.length() - 1; i >= 0; i--) {
                String kk = a.getJSONObject(i).optString("k");
                int n = left.getOrDefault(kk, 0);
                if (n < 12) { left.put(kk, n + 1); keep.put(a.getJSONObject(i)); }
            }
            JSONArray ordered = new JSONArray();
            for (int i = keep.length() - 1; i >= 0; i--) ordered.put(keep.get(i));
            android.content.SharedPreferences.Editor e = p.sp.edit().putString("cough_taught", ordered.toString());
            // asked about the wrong one: that one may be asked about later; the real one is being talked about now
            if (!k.equals(askedKind)) {
                e.remove(askedKind.equals("sneeze") ? "sneeze_asked" : "cough_asked");
                if (!k.equals("none")) e.putLong(k.equals("sneeze") ? "sneeze_asked" : "cough_asked", askedAt);
            }
            e.apply();
            askedFeat = null;
            return k.equals("none") ? "learnt: that sound was neither" : "learnt: that sound was a " + k;
        } catch (Exception e) {
            return "could not save";
        }
    }

    /** How many sounds he has taught, for Settings. */
    static int taughtCount(Context c) {
        try { return new JSONArray(new Prefs(c).sp.getString("cough_taught", "[]")).length(); } catch (Exception e) { return 0; }
    }

    private void ask(String kind, float[] f) {
        Prefs p = new Prefs(ctx);
        boolean sneeze = kind.equals("sneeze");
        long now = System.currentTimeMillis(), gap = p.coughGapMinutes() * 60000L;
        if (now - p.sp.getLong(sneeze ? "sneeze_asked" : "cough_asked", 0) < gap) return;
        // the other one was asked about just now (the same cold): not two questions in a row
        if (now - p.sp.getLong(sneeze ? "cough_asked" : "sneeze_asked", 0) < 3 * 60000L) return;
        if (p.night() || CallControl.busyWithCall() || MainActivity.busyTalking() || Rest.resting(ctx) || CrashAlert.active) return;
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
        askedFeat = f;
        askedAt = now;
        askedKind = kind;
        String name = p.name().trim().isEmpty() ? "Anil" : p.name().trim();
        String timesKey = sneeze ? "sneeze_times_today" : "cough_times_today", dayKey = sneeze ? "sneeze_day" : "cough_day";
        boolean sameDay = java.time.LocalDate.now().toString().equals(p.sp.getString(dayKey, ""));
        int times = sameDay ? p.sp.getInt(timesKey, 0) + 1 : 1;
        int streak = sneeze ? 0 : CoughLog.streak(ctx), today = CoughLog.count(ctx, java.time.LocalDate.now(), kind);
        // coughing for days: say so and offer the doctor's summary
        String[][] lines = sneeze ? (times > 1 ? SNEEZE_AGAIN : SNEEZE_ASK) : streak >= 3 ? COUGH_DAYS : (times > 1 ? COUGH_AGAIN : COUGH_ASK);
        // a different one each time: never the same words twice in a row
        String key = sneeze ? "sneeze_line" : "cough_line";
        int lastLine = p.sp.getInt(key, -1), i;
        do { i = rnd.nextInt(lines.length); } while (lines.length > 1 && i == lastLine);
        p.sp.edit().putLong(sneeze ? "sneeze_asked" : "cough_asked", now).putInt(key, i)
                .putInt(timesKey, times).putString(dayKey, java.time.LocalDate.now().toString()).apply();
        String what = sneeze ? "sneezing" : "coughing";
        String hint = " [health: Jarvis's microphone just heard him " + what + (times > 1 ? " (Jarvis already asked " + (times - 1) + " time(s) about it today)" : "")
                + " (heard " + today + " " + (sneeze ? "sneezes" : "coughs") + " today" + (streak > 1 ? "; coughing " + streak + " days in a row" : "") + ")"
                + ". If he says he took a tablet or syrup -> cough_log took_medicine (name; every_hours only if he wants the next dose reminded). "
                + "Other symptoms he mentions (fever, phlegm, throat pain) -> also cough_log note, for the doctor's summary. "
                + (streak >= 3 ? "As it is " + streak + " days, gently suggest a doctor and offer the doctor's summary PDF (cough_log report). " : "")
                + (sneeze ? sneezeInsight() : "")
                + ". If he says yes or tells what is wrong, use health_advice (" + (sneeze ? "cold" : "cough") + " / what he says): home remedies first; "
                + "the tablet name and how to take it when he asks; which doctor if it does not settle"
                + (times > 2 ? "; as it keeps coming back today, gently suggest seeing a doctor" : "")
                + ". If he says it was not a " + (sneeze ? "sneeze but a cough" : "cough but a sneeze") + " (or neither), call health_advice with heard='"
                + (sneeze ? "cough" : "sneeze") + "' (or 'none') so Jarvis learns his sound, say sorry in a few words, then ask about what it really was. "
                + "If he says no / వద్దు / I'm fine, just say okay, in a few warm words.]";
        Proactive.say(ctx, String.format(lines[i][0], name), lines[i][1], hint);
    }

    private static final java.util.Random rnd = new java.util.Random();

    /** When / where his sneezes come, if a pattern is clear (said once, kindly, if it helps). */
    private String sneezeInsight() {
        try {
            org.json.JSONObject p = CoughLog.sneezePattern(ctx);
            if (p.has("insight_telugu")) return "Pattern from the last 2 weeks (mention once, kindly, only if it helps): " + p.getString("insight_telugu") + " ";
        } catch (Exception ignored) {}
        return "";
    }

    /** {what Jarvis says (%s = his name), the question}: many ways, so it doesn't sound like a recording. */
    private static final String[][] COUGH_ASK = {
            {"%s, ఏమైంది? దగ్గుతున్నారు.", "ఏమైనా సమస్య ఉందా? ముందు ఇంటి చిట్కాలు చెప్పనా, లేక దగ్గు టాబ్లెట్ వేసుకుంటారా?"},
            {"%s, దగ్గు వినిపిస్తోంది.", "ఒంట్లో బాగోలేదా? గోరువెచ్చని నీళ్లు తాగుతారా, లేక ఏదైనా చిట్కా చెప్పనా?"},
            {"%s, బాగున్నారా? దగ్గుతున్నారు.", "గొంతు ఇబ్బందిగా ఉందా? ఇంటి చిట్కా చెప్పనా, టాబ్లెట్ పేరు చెప్పనా?"},
            {"%s, దగ్గు వచ్చినట్టుంది.", "జలుబు కూడా ఉందా? ఏం చేస్తే తగ్గుతుందో చెప్పమంటారా?"},
            {"%s, అంతా ఓకేనా? దగ్గుతున్నారు.", "తేనె-అల్లం లాంటి చిట్కా చెప్పనా, లేక దగ్గు సిరప్ పేరు కావాలా?"},
            {"%s, కొంచెం దగ్గుతున్నారు.", "గొంతు గరగరగా ఉందా? ఉప్పు నీళ్లతో పుక్కిలిస్తే బాగుంటుంది. ఇంకా ఏమైనా చెప్పనా?"},
            {"%s, జాగ్రత్త, దగ్గు వస్తోంది.", "ఏమైనా ఇబ్బందిగా ఉందా? చిట్కాలా, టాబ్లెట్టా, ఏది చెప్పమంటారు?"},
    };
    private static final String[][] COUGH_AGAIN = {
            {"%s, మళ్లీ దగ్గుతున్నారు.", "ఇంకా తగ్గలేదా? ఇంకో చిట్కా చెప్పనా, లేక టాబ్లెట్ వేసుకుంటారా?"},
            {"%s, దగ్గు ఇంకా ఆగలేదు.", "ఇబ్బందిగా ఉంటే టాబ్లెట్ పేరు చెప్పనా? తగ్గకపోతే ఏ డాక్టర్‌ని చూడాలో కూడా చెప్తాను."},
            {"%s, ఇవాళ దగ్గు ఎక్కువగానే ఉంది.", "గోరువెచ్చని నీళ్లు, ఆవిరి పట్టడం చేశారా? టాబ్లెట్ కావాలా?"},
            {"%s, మళ్లీ దగ్గు వినిపిస్తోంది.", "జ్వరం ఏమైనా ఉందా? ఏం చేయాలో చెప్పమంటారా?"},
    };
    /** Coughing 3 days or more. */
    private static final String[][] COUGH_DAYS = {
            {"%s, కొన్ని రోజులుగా దగ్గు తగ్గట్లేదు.", "ఒకసారి డాక్టర్‌కి చూపిస్తే మంచిది. డాక్టర్ కోసం దగ్గు రిపోర్ట్ తయారు చేయనా?"},
            {"%s, దగ్గు ఇంకా వస్తూనే ఉంది.", "రోజులు గడుస్తున్నాయి, డాక్టర్‌ని చూస్తారా? రోజువారీ దగ్గు లెక్కతో రిపోర్ట్ ఇవ్వనా?"},
    };
    private static final String[][] SNEEZE_ASK = {
            {"%s, తుమ్మారు, జలుబు చేసిందా?", "ఏమైనా సమస్య ఉందా? ముందు ఇంటి చిట్కాలు చెప్పనా, లేక జలుబు టాబ్లెట్ పేరు చెప్పనా?"},
            {"%s, తుమ్ము వినిపించింది. బాగున్నారా?", "జలుబా, లేక దుమ్ము అలర్జీనా? ఆవిరి పట్టడం లాంటి చిట్కా చెప్పనా?"},
            {"%s, తుమ్ముతున్నారు.", "ముక్కు కారుతుందా? ఏం చేస్తే తగ్గుతుందో చెప్పమంటారా?"},
            {"%s, జలుబు పట్టినట్టుంది.", "అల్లం టీ లాంటి ఇంటి చిట్కా చెప్పనా, లేక టాబ్లెట్ పేరు కావాలా?"},
    };
    private static final String[][] SNEEZE_AGAIN = {
            {"%s, మళ్లీ తుమ్ముతున్నారు.", "జలుబు ఇంకా తగ్గలేదా? ఆవిరి పట్టడం లాంటి చిట్కా చెప్పనా, లేక టాబ్లెట్ పేరు కావాలా?"},
            {"%s, తుమ్ములు ఇంకా వస్తున్నాయి.", "దుమ్ము అలర్జీ ఏమో? ఏం చేస్తే తగ్గుతుందో చెప్పమంటారా?"},
            {"%s, ఇవాళ తుమ్ములు ఎక్కువగానే ఉన్నాయి.", "ముక్కు దిబ్బడగా ఉందా? ఇంటి చిట్కా చెప్పనా?"},
    };

    void close() {
        worker.execute(() -> {
            try { if (tfl != null) tfl.close(); } catch (Throwable ignored) {}
            tfl = null;
        });
        worker.shutdown();
    }
}
