package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jarvis's own Telugu ears without internet. This phone's voice typing has no Telugu offline, so Jarvis hears him with
 * its own small Telugu model (Vosk; downloaded once from Settings, about 58 MB), all on the phone: nothing is sent
 * anywhere. Two listeners hear the same sound: the model's own Telugu (any words) and one that knows only what Jarvis
 * can do offline; their words are put together (TeluguWords.pick) and numbers are written as digits (Offline.digits).
 * The mic, the helmet's mic, music going quiet and noticing when he stops are as in Ears.
 */
final class TeluguEars implements ListenMic {
    /** The Telugu model isn't on the phone (Settings → Offline వాయిస్). */
    static final int ERROR_NO_MODEL = 121;
    /** The Telugu model on the phone couldn't be opened (Settings offers the download again). */
    static final int ERROR_BROKEN = 122;
    /** Hearing went wrong this once (the model is fine): he can say it again. */
    static final int ERROR_FAILED = 123;

    /** The last listen, for "Jarvis చెక్": what was used, and what each listener heard. */
    static volatile String lastHeard = "";
    /** How long it took after he stopped talking (for "Jarvis చెక్"). */
    static volatile String lastTimes = "";
    static volatile long lastVoiceEnd;
    /** Offline command words this model doesn't know (they can't be heard as such), for "Jarvis చెక్". */
    static volatile String missingWords = "";

    /** What the last listen heard and the words the model lacks, kept for "Jarvis చెక్" (also after the app restarts). */
    static String note(Context c, String what) { return c.getSharedPreferences("jarvis_offline_kit", Context.MODE_PRIVATE).getString(what, ""); }

    static void keepNote(Context c, String what, String text) {
        try { c.getSharedPreferences("jarvis_offline_kit", Context.MODE_PRIVATE).edit().putString(what, text).apply(); } catch (Exception ignored) {}
    }

    private static final int RATE = 16000, FRAME = 320;
    private static final long WRITE_OUT_LIMIT_MS = 15_000;

    // ---- the model: loaded while there is no internet; let go when it is back, or after a while unused (it is big in memory)
    private static final long IDLE_MS = 10 * 60_000L;
    private static final Object LOCK = new Object();
    private static org.vosk.Model model;
    private static String grammar;
    private static int users;
    private static boolean dropLater;
    private static long lastUsed;
    private static final Handler idle = new Handler(Looper.getMainLooper());
    private static final Runnable idleDrop = () -> new Thread(TeluguEars::dropIfIdle, "jarvis-te-idle").start();

    /** Jarvis can hear Telugu itself now (its model is downloaded). */
    static boolean ready(Context c) { return VoskModel.teReady(c); }

    /** The model on the phone wouldn't open last time: Settings offers the download again. */
    static boolean broken(Context c) { return "1".equals(note(c, "te_broken")); }

    /** Without internet, in this language: Jarvis's own Telugu ears (the phone's own Telugu offline pack, if it ever has one, first). */
    static boolean use(Context c, String lang) {
        return lang != null && lang.startsWith("te") && ready(c) && !OfflineKit.useOnDevice(c, lang);
    }

    /** No internet now: the model is loaded ahead (background), so the first "Jarvis" isn't kept waiting. */
    static void warm(Context c) {
        Context app = c.getApplicationContext();
        if (!ready(app)) return;
        new Thread(() -> {
            try { take(app); give(); } catch (Throwable ignored) {}
            if (Net.online(app)) drop(); // (the internet came back while it loaded)
        }, "jarvis-te-warm").start();
    }

    /** The internet is back: the model's memory is let go (after the listen using it, if one is). Any thread. */
    static void drop() {
        new Thread(() -> {
            synchronized (LOCK) {
                if (users > 0) dropLater = true;
                else close();
            }
        }, "jarvis-te-drop").start();
    }

    private static void dropIfIdle() {
        synchronized (LOCK) {
            if (users == 0 && model != null && SystemClock.elapsedRealtime() - lastUsed >= IDLE_MS - 1000) close();
        }
    }

    private static void close() {
        if (model != null) try { model.close(); } catch (Throwable ignored) {}
        model = null;
        grammar = null;
        dropLater = false;
    }

    private static org.vosk.Model take(Context app) throws Exception {
        synchronized (LOCK) {
            users++;
            if (!Net.online(app)) dropLater = false; // (no internet again)
            if (model != null) return model;
            File dir = VoskModel.teDir(app);
            try {
                org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                org.vosk.Model m = new org.vosk.Model(dir.getAbsolutePath());
                grammar = commands(m, dir);
                model = m;
            } catch (Throwable e) {
                users--;
                keepNote(app, "te_broken", "1"); // (the files stay: Settings offers the download again, he chooses)
                throw e;
            }
            keepNote(app, "te_missing", missingWords);
            keepNote(app, "te_broken", "");
            return model;
        }
    }

    private static void give() {
        synchronized (LOCK) {
            users = Math.max(0, users - 1);
            lastUsed = SystemClock.elapsedRealtime();
            if (users > 0) return;
            if (dropLater) { close(); return; }
            idle.removeCallbacks(idleDrop);
            idle.postDelayed(idleDrop, IDLE_MS);
        }
    }

    /** The command listener's phrases (the words this model knows), or null when the model can't take them. */
    private static String commands(org.vosk.Model m, File dir) {
        try { return commandsOf(m, dir); } catch (Throwable e) { missingWords = "(పనుల మాటలు చేయలేకపోయాను: " + e + ")"; return null; }
    }

    private static String commandsOf(org.vosk.Model m, File dir) {
        boolean changeable = new File(dir, "graph/HCLr.fst").exists() || new File(dir, "HCLr.fst").exists();
        if (!changeable) { missingWords = "(ఈ మోడల్‌కి పనుల మాటలు ఇవ్వలేము)"; return null; }
        List<String> missing = new ArrayList<>();
        String g = TeluguWords.grammar(w -> knows(m, w) != 0, missing);
        StringBuilder b = new StringBuilder();
        for (String w : missing) {
            if (b.length() > 300) { b.append(" …"); break; }
            b.append(b.length() == 0 ? "" : ", ").append(w);
        }
        missingWords = b.toString();
        return g;
    }

    private static com.sun.jna.Function findWord;
    private static boolean noFind;

    /** 1: the model knows the word, 0: it doesn't, -1: can't tell (then it is kept; Vosk skips what it doesn't know). Any Vosk model. */
    static int knows(org.vosk.Model m, String w) {
        if (noFind) return -1;
        try {
            if (findWord == null) findWord = com.sun.jna.Function.getFunction("vosk", "vosk_model_find_word");
            byte[] b = (w + "\0").getBytes(StandardCharsets.UTF_8);
            return findWord.invokeInt(new Object[]{m.getPointer(), b}) >= 0 ? 1 : 0;
        } catch (Throwable e) {
            noFind = true;
            return -1;
        }
    }

    // ---- one listen

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled, finishNow;
    /** The listeners couldn't start (the model): the mic stops too. */
    private volatile boolean micStop;
    private final AtomicBoolean reported = new AtomicBoolean();
    private Ears.Callback cb;
    private boolean longTalk;
    /** His voice from the mic to the listeners (they may lag the mic a moment); RESET: it was only a noise; END, STOP. */
    private final LinkedBlockingQueue<byte[]> sound = new LinkedBlockingQueue<>();
    private static final byte[] RESET = new byte[0], END = new byte[0], STOP = new byte[0];
    private volatile long doneAt, voiceEnd;

    TeluguEars(Context c) { ctx = c.getApplicationContext(); }

    /** Main thread. Waits up to waitMs for him to start talking, then hears him out (a message he dictates: up to a minute). */
    void start(long waitMs, boolean longTalk, Ears.Callback callback) {
        this.longTalk = longTalk;
        cb = callback;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return; }
        if (!ready(ctx)) { fail(ERROR_NO_MODEL); return; }
        new Thread(this::decode, "jarvis-te-words").start();
        new Thread(() -> record(waitMs), "jarvis-te-ears").start();
    }

    @Override public void finishNow() { finishNow = true; }

    @Override public void cancel() {
        cancelled = true;
        sound.offer(STOP);
    }

    private void post(Runnable r) { main.post(() -> { if (!cancelled && !reported.get()) r.run(); }); }

    private void fail(int error) {
        main.post(() -> { if (!cancelled && reported.compareAndSet(false, true)) cb.failed(error); });
    }

    private void words(String text) {
        main.post(() -> { if (!cancelled && reported.compareAndSet(false, true)) cb.heard(text); });
    }

    /** The mic: his talk (Ears.Talk) goes to the listeners as it comes. */
    private void record(long waitMs) {
        AudioRecord rec = null;
        Ears.Route route = new Ears.Route(ctx);
        android.media.AudioFocusRequest musicDown = null;
        boolean ended = false;
        try {
            route.connect(() -> cancelled);
            musicDown = Duck.hold(ctx); // songs and radio go quiet while Jarvis listens
            rec = Ears.openMic(route.routed, route.in, RATE * 2); // (a second of sound kept: the listeners may lag a moment)
            if (rec == null) { SystemClock.sleep(200); rec = Ears.openMic(route.routed, route.in, RATE * 2); }
            if (rec == null) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
            post(() -> cb.opened());
            Ears.Talk talk = new Ears.Talk(longTalk ? 60_000 : 20_000);
            short[] f = new short[FRAME];
            long openedAt = SystemClock.elapsedRealtime(), lastLevel = 0, zeroSince = -1;
            boolean anySound = false, was = false;
            while (!cancelled && !micStop) {
                int n = rec.read(f, 0, FRAME);
                if (n <= 0) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
                long now = SystemClock.elapsedRealtime();
                boolean zero = true;
                for (int i = 0; i < n && zero; i++) if (f[i] != 0) zero = false;
                if (!zero) { zeroSince = -1; anySound = true; }
                else if (zeroSince < 0) zeroSince = now;
                // the phone gives Jarvis no sound (the mic held elsewhere)
                if (zero && (anySound ? now - zeroSince >= 1000 : now - openedAt >= (route.routed ? 4000 : 1500))) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
                int state = talk.add(f, n, now);
                if (was && !talk.started()) sound.offer(RESET); // only a noise: the listeners forget it
                was = talk.started();
                byte[] fresh = talk.takeFresh();
                if (fresh.length > 0) sound.offer(fresh);
                if (now - lastLevel > 60) {
                    lastLevel = now;
                    final float lv = Math.max(0f, Math.min(1f, (talk.lastDb + 60f) / 45f));
                    post(() -> cb.level(lv));
                }
                if (state == Ears.Talk.DONE || finishNow && talk.started()) break;
                if (finishNow) { words(""); return; } // "done" before he said anything
                if (!talk.started() && now - openedAt > waitMs) { fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); return; }
            }
            if (cancelled || micStop) return;
            if (!talk.enough()) { fail(SpeechRecognizer.ERROR_NO_MATCH); return; } // only a noise before "done"
            byte[] last = talk.takeFresh();
            if (last.length > 0) sound.offer(last);
            doneAt = SystemClock.elapsedRealtime();
            voiceEnd = Math.min(talk.lastVoiceAt(), doneAt);
            talkedMs = talk.voicedMs();
            sound.offer(END);
            ended = true;
        } catch (Exception e) {
            fail(SpeechRecognizer.ERROR_AUDIO);
        } finally {
            if (!ended) sound.offer(STOP); // (the listeners stop too)
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) {}
                try { rec.release(); } catch (Exception ignored) {}
            }
            Duck.release(ctx, musicDown);
            route.release();
        }
        post(() -> cb.understanding());
        // never "understanding" for ever (a long message he dictated takes longer to write out)
        main.postDelayed(() -> fail(VoiceIO.ERROR_STUCK), WRITE_OUT_LIMIT_MS + talkedMs);
    }

    private volatile long talkedMs;

    /** The two listeners: his words as they come (shown while he talks), and in the end what he said. */
    private void decode() {
        org.vosk.Recognizer free = null, cmd = null;
        boolean took = false;
        try {
            org.vosk.Model m;
            String g;
            try {
                m = take(ctx);
                took = true;
                synchronized (LOCK) { g = grammar; }
            } catch (Throwable e) {
                micStop = true;
                fail(ERROR_BROKEN);
                return;
            }
            free = new org.vosk.Recognizer(m, RATE);
            free.setWords(true);
            if (g != null && !longTalk) { // (a message he dictates is his own words, not commands)
                try {
                    cmd = new org.vosk.Recognizer(m, RATE, g);
                    cmd.setWords(true);
                } catch (Throwable e) { // the command listener only helps: the model's own Telugu goes on alone
                    cmd = null;
                    missingWords = "(పనుల మాటలు మోడల్ తీసుకోలేదు: " + e.getMessage() + ")";
                }
            }
            List<TeluguWords.W> fw = new ArrayList<>(), cw = new ArrayList<>();
            long lastShown = 0;
            String shown = "";
            List<byte[]> got = new ArrayList<>();
            java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
            boolean over = false;
            while (!over) {
                got.clear();
                got.add(sound.take());
                sound.drainTo(got); // (what came meanwhile, fed at once: catching up with the mic is quicker)
                chunk.reset();
                for (byte[] b : got) {
                    if (b == STOP || cancelled) return;
                    if (b == END || b == RESET) {
                        feed(free, cmd, chunk, fw, cw);
                        if (b == END) { over = true; break; }
                        free.reset();
                        if (cmd != null) cmd.reset();
                        fw.clear();
                        cw.clear();
                        if (!shown.isEmpty()) { shown = ""; post(() -> cb.partial("")); }
                        continue;
                    }
                    chunk.write(b, 0, b.length);
                }
                if (over) break;
                feed(free, cmd, chunk, fw, cw);
                long now = SystemClock.elapsedRealtime();
                if (now - lastShown > 250 && sound.isEmpty()) { // (only when it has caught up with the mic)
                    lastShown = now;
                    String p = (TeluguWords.text(fw) + " " + TeluguWords.partial(free.getPartialResult())).trim();
                    if (!p.equals(shown)) {
                        shown = p;
                        String d = Offline.digits(p);
                        post(() -> cb.partial(d));
                    }
                }
            }
            fw.addAll(TeluguWords.parse(free.getFinalResult()));
            if (cmd != null) cw.addAll(TeluguWords.parse(cmd.getFinalResult()));
            String text = Offline.digits(TeluguWords.pick(fw, cw));
            long wordsAt = SystemClock.elapsedRealtime();
            long d = doneAt;
            lastVoiceEnd = voiceEnd;
            lastTimes = "మీరు ఆపారని గుర్తించడం " + Ears.sec(d - voiceEnd) + " · మాటలు రాయడం (ఫోన్‌లోనే) " + Ears.sec(wordsAt - d) + " సె";
            lastHeard = Offline.secret(text) || Offline.secret(TeluguWords.text(fw)) ? "(రహస్య నంబర్లు: ఇక్కడ రాయలేదు)" // (never kept)
                    : "\"" + text + "\" ← మామూలు: \"" + TeluguWords.text(fw) + "\""
                    + (cmd == null ? "" : " · పనుల మాటలు: \"" + TeluguWords.text(cw) + (hasUnk(cw) ? " [తెలియనివి]" : "") + "\"");
            keepNote(ctx, "te_heard", lastHeard);
            keepNote(ctx, "te_times", lastTimes);
            words(text);
        } catch (InterruptedException e) {
            // stopped
        } catch (Throwable e) {
            micStop = true;
            fail(ERROR_FAILED);
        } finally {
            if (free != null) try { free.close(); } catch (Throwable ignored) {}
            if (cmd != null) try { cmd.close(); } catch (Throwable ignored) {}
            if (took) give();
        }
    }

    /** The sound gathered so far to both listeners; a stretch they finished (a pause) is kept. */
    private static void feed(org.vosk.Recognizer free, org.vosk.Recognizer cmd, java.io.ByteArrayOutputStream chunk,
                             List<TeluguWords.W> fw, List<TeluguWords.W> cw) {
        if (chunk.size() == 0) return;
        byte[] b = chunk.toByteArray();
        chunk.reset();
        if (free.acceptWaveForm(b, b.length)) fw.addAll(TeluguWords.parse(free.getResult()));
        if (cmd != null && cmd.acceptWaveForm(b, b.length)) cw.addAll(TeluguWords.parse(cmd.getResult()));
    }

    private static boolean hasUnk(List<TeluguWords.W> l) {
        for (TeluguWords.W w : l) if (TeluguWords.unk(w.word)) return true;
        return false;
    }
}
