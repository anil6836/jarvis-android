package com.anil.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jarvis's own ears: listens on the phone's mic itself, silently (the phone's speech service beeps every time it opens
 * and closes the mic, and on his phone that beep can't be muted safely), notices when he starts and stops talking,
 * then has the words written out by the AI he chose in Settings (OpenAI or Gemini). Nothing is sent until he has
 * really spoken; noise and coughs are not sent, and the AI's made-up words for noise are thrown away. A Bluetooth
 * headset's mic (a helmet) is used when one is connected. No silent fallback: when the chosen AI can't be reached it
 * says why.
 */
final class Ears implements ListenMic {
    /** What Jarvis uses to hear him: "openai", "gemini" or "google" (the phone's own speech service, with its beeps). */
    static String mode(Prefs p) { return p.earsMode(); }

    /**
     * Sound that comes from somewhere else than the phone's mic (the watch, WatchHub): 20 ms frames of 16 kHz sound as
     * they arrive. read returns how many samples it gave (it waits for them), or -1 when that sound has ended.
     */
    interface Source { int read(short[] f, int n); }

    /** The watch's sound instead of the phone's mic (null: the phone's mic). */
    private Source source;
    /** The AI the watch's words go to ("openai" / "gemini", his watch setting); null: his phone setting. */
    private String modeOver;

    /** Hears this sound (the watch's) instead of the phone's mic, written out by mode ("openai" or "gemini"). Before start. */
    Ears from(Source s, String mode) { source = s; modeOver = mode; return this; }

    /** W37: someone else speaking (Hindi, English, Telugu...): written out in the language it is said in, not as Telugu. */
    private boolean anyLang;

    Ears anyLanguage() { anyLang = true; return this; }

    private String myMode() { return modeOver != null ? modeOver : mode(p); }

    /** Jarvis listens with its own mic (not the phone's speech service). */
    static boolean chosen(Prefs p) { String m = mode(p); return "openai".equals(m) || "gemini".equals(m); }

    /** No key for the chosen AI (his settings). */
    static final int ERROR_NO_KEY = 101;
    /** The chosen AI refused the key. */
    static final int ERROR_BAD_KEY = 102;
    /** The model name in Settings is not known to the AI. */
    static final int ERROR_MODEL = 103;
    /** The AI account has no balance / quota left. */
    static final int ERROR_QUOTA = 104;

    /** The AI's last refusal, word for word (for "Jarvis చెక్"). */
    static volatile String lastError = "";
    /** How long each step of the last listen took after he stopped talking (for "Jarvis చెక్"). */
    static volatile String lastTimes = "";
    /** When his voice last ended (elapsedRealtime), for the whole turn's time in VoiceIO. */
    static volatile long lastVoiceEnd;

    interface Callback {
        /** The mic is open: waiting for him to talk. */
        void opened();
        /** The sound level 0..1 (the orb moves with it). */
        void level(float level);
        /** He finished talking: the words are being written out. */
        void understanding();
        /** What he said (empty: nothing clear). */
        void heard(String text);
        /** It ended without words (SpeechRecognizer error codes, or the ERROR_ codes above). */
        void failed(int error);
        /** The words so far, while he is still talking (only where they are known as he talks: TeluguEars). */
        default void partial(String text) {}
    }

    private static final int RATE = 16000;
    private static final int FRAME = 320;                 // 20 ms
    private static final long WRITE_OUT_LIMIT_MS = 12_000; // the AI gets this long to write out the words (+1 s per 40 KB)

    private final Context ctx;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled, finishNow;
    private boolean longTalk;
    /** His voice going to OpenAI while he talks (null when not). */
    private volatile Upload upload;
    /** When the whole recording (sent after he stopped) had gone out. */
    private volatile long sentAt;
    /** The first result (words, failure, timeout) wins; nothing after it is reported. */
    private final AtomicBoolean reported = new AtomicBoolean();
    private Callback cb;
    /** Songs and radio stay quiet while the mic listens (Duck.hold). */
    private volatile android.media.AudioFocusRequest musicDown;

    Ears(Context c, Prefs p) {
        this.ctx = c.getApplicationContext();
        this.p = p;
    }

    /** Main thread. Waits up to waitMs for him to start talking, then hears him out (a question: at most 20 s). */
    void start(long waitMs, Callback callback) { start(waitMs, false, callback); }

    /** longTalk: a message he dictates (at most a minute) rather than a question. */
    void start(long waitMs, boolean longTalk, Callback callback) {
        this.longTalk = longTalk;
        cb = callback;
        if (source == null && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return; }
        String key = "gemini".equals(myMode()) ? p.geminiKey().trim() : p.openAiKey().trim();
        if (key.isEmpty()) { fail(ERROR_NO_KEY); return; }
        if (!Net.online(ctx)) { fail(SpeechRecognizer.ERROR_NETWORK); return; }
        new Thread(() -> run(waitMs, key), "jarvis-ears").start();
    }

    /** "Done" (he tapped): stop listening now and write out what he said so far. */
    @Override public void finishNow() { finishNow = true; }

    /** Stop; nothing more is reported (and nothing half-sent is finished). */
    @Override public void cancel() {
        cancelled = true;
        Upload u = upload;
        if (u != null) u.abort();
    }

    // ---- reporting (main thread, once)

    private void post(Runnable r) { main.post(() -> { if (!cancelled) r.run(); }); }

    private void fail(int error) {
        main.post(() -> { if (!cancelled && reported.compareAndSet(false, true)) cb.failed(error); });
    }

    private void words(String text) {
        main.post(() -> { if (!cancelled && reported.compareAndSet(false, true)) cb.heard(text); });
    }

    // ---- listening

    @SuppressLint("MissingPermission")
    private void run(long waitMs, String key) {
        AudioRecord rec = null;
        Route route = new Route(ctx);
        byte[] wav = null;
        boolean weak;
        long doneAt, voiceEnd;
        // OpenAI: his voice goes out while he talks, so after he stops only the last moment is left to send
        boolean stream = !"gemini".equals(myMode()) && p.earsStream();
        Upload up = null;
        int upTries = 0;
        try {
            final Source src = source;
            if (src == null) { // (the watch's sound needs no phone mic, headset or quiet songs)
                route.connect(() -> cancelled); // a Bluetooth headset (helmet) mic, when one is connected
                musicDown = Duck.hold(ctx); // songs and radio go quiet while Jarvis listens (as Google voice typing does)
                rec = openMic(route.routed, route.in, 0);
                if (rec == null) { SystemClock.sleep(200); rec = openMic(route.routed, route.in, 0); } // (a talk-over mic may still be letting go)
                // the home tablet (Android 8: one mic recording at a time): the wake-word mic may need a moment more to let go
                for (int i = 0; rec == null && p.homeMode() && !cancelled && i < 12; i++) {
                    SystemClock.sleep(150);
                    rec = openMic(route.routed, route.in, 0);
                }
                if (rec == null) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
            }
            post(() -> cb.opened());
            Talk talk = new Talk(longTalk ? 60_000 : 20_000);
            short[] f = new short[FRAME];
            long openedAt = SystemClock.elapsedRealtime();
            long lastLevel = 0, zeroSince = -1, frames = 0;
            boolean anySound = false;
            while (!cancelled) {
                int n = src != null ? src.read(f, FRAME) : rec.read(f, 0, FRAME);
                if (n < 0 && src != null) { // the watch stopped sending: what he said so far, or nothing
                    if (talk.started()) break;
                    fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    return;
                }
                if (n <= 0) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
                // the watch's sound comes in bursts over Bluetooth: its own clock is the sound itself (20 ms a frame)
                long now = src != null ? openedAt + 20 * frames++ : SystemClock.elapsedRealtime();
                boolean zero = src == null;
                for (int i = 0; i < n && zero; i++) if (f[i] != 0) zero = false;
                if (!zero) { zeroSince = -1; anySound = true; }
                else if (zeroSince < 0) zeroSince = now;
                // the phone gives Jarvis no sound (the mic held elsewhere): after sound went silent for 1 s, or none at all
                // since the mic opened (a headset gets longer to start)
                if (zero && (anySound ? now - zeroSince >= 1000 : now - openedAt >= (route.routed ? 4000 : 1500))) { fail(SpeechRecognizer.ERROR_AUDIO); return; }
                int state = talk.add(f, n, now);
                if (stream) {
                    if (talk.started() && up == null && upTries < 3) { // he started: the connection is made now, the sound follows as it comes
                        upTries++;
                        String boundary = "----jarvisears" + System.nanoTime();
                        up = new Upload(key, uploadHead(boundary, true), uploadTail(boundary), boundary);
                        upload = up;
                        if (cancelled) up.abort();
                    } else if (!talk.started() && up != null) { // that was only a noise: it is never finished (nothing is written out)
                        up.abort();
                        up = null;
                        upload = null;
                    }
                }
                byte[] fresh = talk.takeFresh(); // (taken every time, so it never piles up)
                if (up != null) up.feed(fresh);
                if (now - lastLevel > 60) {
                    lastLevel = now;
                    final float lv = Math.max(0f, Math.min(1f, (talk.lastDb + 60f) / 45f));
                    post(() -> cb.level(lv));
                }
                if (state == Talk.DONE || finishNow && talk.started()) break;
                if (finishNow) { words(""); return; } // "done" before he said anything
                if (!talk.started() && now - openedAt > waitMs) { fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); return; }
            }
            if (cancelled) return;
            if (!talk.enough()) { fail(SpeechRecognizer.ERROR_NO_MATCH); return; } // only a noise before "done"
            if (up != null) { up.feed(talk.takeFresh()); up.finish(); } // the last moment, and the end
            doneAt = SystemClock.elapsedRealtime();
            voiceEnd = Math.min(talk.lastVoiceAt(), doneAt);
            wav = talk.wav();
            weak = talk.voicedMs() < 800;
        } catch (Exception e) {
            fail(SpeechRecognizer.ERROR_AUDIO);
            return;
        } finally {
            if (wav == null && up != null) { up.abort(); upload = null; } // ended without his words: the half-sent one is dropped
            if (rec != null) { // the mic is let go before the words are written out
                try { rec.stop(); } catch (Exception ignored) {}
                try { rec.release(); } catch (Exception ignored) {}
            }
            Duck.release(ctx, musicDown); // (Jarvis's answer keeps them down again while it speaks)
            musicDown = null;
            route.release();
        }
        post(() -> cb.understanding());
        // never "understanding" for ever (a longer recording gets longer to upload)
        long limit = WRITE_OUT_LIMIT_MS + wav.length / 40_000 * 1000L;
        main.postDelayed(() -> fail(SpeechRecognizer.ERROR_NETWORK_TIMEOUT), limit);
        try {
            String text = null;
            boolean streamed = false, refused = false;
            long sent = 0;
            if (up != null) {
                if (!up.await(limit)) { up.abort(); return; } // (the time limit above says so)
                upload = null;
                if (cancelled) return;
                if (up.text != null) { text = up.text; streamed = true; sent = up.sentAt; }
                else if (up.error instanceof Http.ApiError) {
                    Http.ApiError ae = (Http.ApiError) up.error;
                    String body = String.valueOf(ae.getMessage());
                    int kind = classify(ae.status, body);
                    if (kind == ERROR_BAD_KEY || kind == ERROR_QUOTA || kind == ERROR_MODEL || kind == 10) { fail(kind); return; }
                    if (ae.status == 400 || ae.status == 411 || ae.status == 413 || ae.status == 415) {
                        refused = true; // OpenAI didn't take his voice sent this way: the whole recording now
                        lastError = "మాట్లాడుతుండగానే పంపడం OpenAI ఒప్పుకోలేదు (" + ae.status + "): " + (body.length() > 140 ? body.substring(0, 140) : body);
                    }
                } // (a network break while he talked: the whole recording is sent now, to the same AI)
            }
            if (text == null) {
                if (cancelled) return;
                sentAt = 0;
                text = "gemini".equals(myMode()) ? gemini(key, wav) : openAi(key, wav);
                sent = sentAt;
                if (refused) p.earsStreamOff(); // sent whole, it worked: from now on always so (a new model in Settings tries again)
            }
            long wordsAt = SystemClock.elapsedRealtime();
            if (sent < doneAt) sent = doneAt;
            if (sent > wordsAt) sent = wordsAt;
            lastVoiceEnd = voiceEnd;
            lastTimes = "మీరు ఆపారని గుర్తించడం " + sec(doneAt - voiceEnd) + " · పంపడం " + sec(sent - doneAt) + " · మాటలు రాయడం " + sec(wordsAt - sent) + " సె"
                    + (streamed ? " (మాట్లాడుతుండగానే పంపుతూ)" : "");
            text = clean(text);
            words(noiseWords(text, weak) ? "" : text);
        } catch (Http.ApiError e) {
            fail(classify(e.status, String.valueOf(e.getMessage())));
        } catch (java.io.IOException e) {
            fail(SpeechRecognizer.ERROR_NETWORK);
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            fail(SpeechRecognizer.ERROR_SERVER);
        }
    }

    static String sec(long ms) { return String.format(Locale.ROOT, "%.1f", Math.max(0, ms) / 1000.0); }

    /**
     * A Bluetooth headset's mic (a helmet), when one is connected: the phone is put in call mode with the headset as its
     * call device while Jarvis listens, and back after (never over a real call).
     */
    static final class Route {
        private final AudioManager am;
        boolean routed;
        private boolean modeChanged;
        private int oldMode = AudioManager.MODE_NORMAL;
        /** The headset's mic, to record from (null: the phone's own). */
        AudioDeviceInfo in;

        Route(Context c) { am = c.getSystemService(AudioManager.class); }

        void connect(java.util.function.BooleanSupplier cancelled) {
            if (Build.VERSION.SDK_INT < 31 || am == null) return;
            try {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) { pick = d; break; }
                }
                if (pick != null && am.getMode() == AudioManager.MODE_NORMAL) {
                    oldMode = am.getMode();
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    modeChanged = true;
                    routed = am.setCommunicationDevice(pick);
                    if (routed) {
                        // the headset's audio link takes a moment to come up: wait for it (at most 3 s)
                        long until = SystemClock.elapsedRealtime() + 3000;
                        while (!cancelled.getAsBoolean() && SystemClock.elapsedRealtime() < until) {
                            AudioDeviceInfo now = am.getCommunicationDevice();
                            if (now != null && now.getId() == pick.getId()) break;
                            SystemClock.sleep(100);
                        }
                        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                            int t = d.getType();
                            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) { in = d; break; }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        void release() {
            if (am == null || !routed && !modeChanged) return;
            try { if (routed && Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); } catch (Exception ignored) {}
            try { if (modeChanged && am.getMode() == AudioManager.MODE_IN_COMMUNICATION) am.setMode(oldMode); } catch (Exception ignored) {} // (not over a real call)
            routed = false;
            modeChanged = false;
        }
    }

    /** The mic, recording 16 kHz mono (the headset's when routed); bufferBytes 0: the usual. Null when it can't be had. */
    @SuppressLint("MissingPermission")
    static AudioRecord openMic(boolean headset, AudioDeviceInfo headsetIn, int bufferBytes) {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(headset ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Math.max(bufferBytes, FRAME * 2 * 8)));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return null; }
            if (headsetIn != null) try { rec.setPreferredDevice(headsetIn); } catch (Exception ignored) {}
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return null; }
            return rec;
        } catch (Exception e) {
            if (rec != null) try { rec.release(); } catch (Exception ignored) {}
            return null;
        }
    }

    /** Which failure an AI refusal is, from its code and words (kept for "Jarvis చెక్"). */
    static int classify(int status, String body) {
        lastError = status + ": " + (body.length() > 180 ? body.substring(0, 180) : body);
        String b = body.toLowerCase(Locale.ROOT);
        if (status == 401 || status == 403 || b.contains("api_key_invalid") || b.contains("api key not valid") || b.contains("invalid_api_key")
                || b.contains("incorrect api key")) return ERROR_BAD_KEY;
        if (b.contains("insufficient_quota") || b.contains("exceeded your current quota") || b.contains("billing")) return ERROR_QUOTA;
        if (status == 404 || b.contains("model_not_found") || b.contains("does not exist") || b.contains("is not found") || b.contains("unknown model")
                || b.contains("invalid model")) return ERROR_MODEL;
        if (status == 429 || b.contains("resource_exhausted")) return 10; // busy: a moment
        if (status == 0 || status >= 500) return SpeechRecognizer.ERROR_SERVER;
        return SpeechRecognizer.ERROR_SERVER;
    }

    /** Words only: no quotes. */
    static String clean(String t) {
        if (t == null) return "";
        String s = t.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("“") && s.endsWith("”"))) s = s.substring(1, s.length() - 1).trim();
        return s;
    }

    /**
     * What speech-to-text makes up for noise: punctuation only; or, from a weak recording (little voice), a stock phrase on
     * its own ("thank you"). A clear "థాంక్యూ" he really said is kept.
     */
    static boolean noiseWords(String t, boolean weak) {
        String s = t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s।…]+", " ").trim();
        if (s.isEmpty()) return true;
        if (!weak) return false;
        switch (s) {
            case "thank you": case "thanks for watching": case "thank you for watching": case "you": case "bye": case "bye bye":
            case "subtitles by the amara org community": case "ధన్యవాదాలు": case "ధన్యవాదములు": case "mm": case "hmm": case "um": case "uh":
                return true;
            default:
                return false;
        }
    }

    /**
     * His talk, from the sound: the room's quiet level is learnt; he has started when it is clearly louder for a bit,
     * finished when it has been quiet for a while after (the quiet level is followed during the talk too, so a bus or
     * the wind getting louder doesn't keep it going); too little voice is a noise. Pure (for tests).
     */
    static final class Talk {
        static final int WAITING = 0, TALKING = 1, DONE = 2;
        private static final int PRE_ROLL_FRAMES = 30;        // 600 ms kept from before he started (the first syllable)
        private static final long START_WINDOW_MS = 300;      // voice in this much…
        private static final long START_VOICED_MS = 120;      // …for this long: he has started
        private static final long END_SILENCE_MS = 850;       // quiet this long after a question: he has finished
        private static final long LONG_END_SILENCE_MS = 1400; // a message he dictates: he may stop to think a little longer
        private static final long MIN_TALK_MS = 400;          // less voice than this is a noise (a cough, a knock): not sent
        private static final long MAX_TALK_MS = 20_000;       // one question at most this long
        private float floor = Float.NaN, talkFloor;
        private int learnt; // real frames seen (the first ~200 ms only teach the room's level)
        float lastDb = -90f;
        private int state = WAITING;
        private final long maxTalkMs, endSilenceMs;

        Talk() { this(MAX_TALK_MS); }

        Talk(long maxTalkMs) {
            this.maxTalkMs = maxTalkMs;
            endSilenceMs = maxTalkMs > MAX_TALK_MS ? LONG_END_SILENCE_MS : END_SILENCE_MS;
        }

        long voicedMs() { return voicedMs; }

        /** When his voice was last heard. */
        long lastVoiceAt() { return lastVoiceAt; }

        /** The sound kept since the last call (sent on while he talks). */
        byte[] takeFresh() {
            byte[] b = fresh.toByteArray();
            fresh.reset();
            return b;
        }
        private long startAt, lastVoiceAt, voicedMs;
        private final ArrayDeque<short[]> pre = new ArrayDeque<>();
        private final ArrayDeque<long[]> recent = new ArrayDeque<>(); // {time, loud 0/1} over the last START_WINDOW_MS
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final ByteArrayOutputStream fresh = new ByteArrayOutputStream();

        boolean started() { return state != WAITING; }

        /** Enough voice to be his words (and not mostly noise). */
        boolean enough() {
            long span = Math.max(1, lastVoiceAt - startAt + 20);
            return voicedMs >= MIN_TALK_MS && voicedMs * 5 >= span; // at least a fifth of it voiced
        }

        int add(short[] f, int n, long now) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += (double) f[i] * f[i];
            float db = (float) (20 * Math.log10(Math.sqrt(sum / Math.max(1, n)) / 32768.0 + 1e-9));
            lastDb = db;
            short[] copy = java.util.Arrays.copyOf(f, n);
            if (state == WAITING && db > -100f && learnt < 10) { // the room's level: the quietest of the first real frames
                floor = learnt == 0 ? db : Math.min(floor, db);
                learnt++;
                pre.addLast(copy);
                while (pre.size() > PRE_ROLL_FRAMES) pre.removeFirst();
                return state;
            }
            if (state == WAITING && learnt < 10) return state; // (the mic's first silent buffers teach nothing)
            if (state == WAITING) {
                boolean loud = db > Math.max(floor + 12f, -52f);
                if (!loud) { // the room: learn its level (quickly down, slowly up)
                    if (db < floor) floor = floor * 0.7f + db * 0.3f; else floor += (db - floor) * 0.02f;
                }
                pre.addLast(copy);
                while (pre.size() > PRE_ROLL_FRAMES) pre.removeFirst();
                recent.addLast(new long[]{now, loud ? 1 : 0});
                while (!recent.isEmpty() && now - recent.peekFirst()[0] > START_WINDOW_MS) recent.removeFirst();
                long voicedRecent = 0;
                for (long[] r : recent) voicedRecent += r[1] * 20;
                if (voicedRecent >= START_VOICED_MS) {
                    state = TALKING;
                    startAt = now;
                    lastVoiceAt = now;
                    voicedMs = voicedRecent;
                    talkFloor = floor;
                    for (short[] s : pre) write(s);
                    pre.clear();
                }
                return state;
            }
            write(copy);
            // the quiet level during the talk: the gaps between words (quickly down, slowly up: a louder road or wind)
            if (db < talkFloor) talkFloor = talkFloor * 0.6f + db * 0.4f; else talkFloor += (db - talkFloor) * 0.012f;
            boolean voice = db > Math.max(Math.max(floor, talkFloor) + 7f, -55f);
            if (voice) { lastVoiceAt = now; voicedMs += 20; }
            if (now - lastVoiceAt >= endSilenceMs) {
                if (!enough()) { // only a noise: forget it and wait on
                    state = WAITING;
                    pcm.reset();
                    fresh.reset();
                    recent.clear();
                    return state;
                }
                state = DONE;
            } else if (now - startAt >= maxTalkMs) {
                state = DONE;
            }
            return state;
        }

        private void write(short[] s) {
            byte[] b = new byte[s.length * 2];
            for (int i = 0; i < s.length; i++) { b[2 * i] = (byte) (s[i] & 0xFF); b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xFF); }
            pcm.write(b, 0, b.length);
            fresh.write(b, 0, b.length);
        }

        /** What he said, as a WAV file (16 kHz mono). */
        byte[] wav() {
            byte[] data = pcm.toByteArray();
            byte[] h = wavHeader(data.length);
            byte[] all = java.util.Arrays.copyOf(h, h.length + data.length);
            System.arraycopy(data, 0, all, h.length, data.length);
            return all;
        }

        /** The 44-byte WAV header for this much sound (-1: not known yet, "to the end"). */
        static byte[] wavHeader(int dataLen) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(44);
            o.write('R'); o.write('I'); o.write('F'); o.write('F'); le(o, dataLen < 0 ? -1 : 36 + dataLen, 4);
            o.write('W'); o.write('A'); o.write('V'); o.write('E'); o.write('f'); o.write('m'); o.write('t'); o.write(' ');
            le(o, 16, 4); le(o, 1, 2); le(o, 1, 2); le(o, RATE, 4); le(o, RATE * 2, 4); le(o, 2, 2); le(o, 16, 2);
            o.write('d'); o.write('a'); o.write('t'); o.write('a'); le(o, dataLen, 4);
            return o.toByteArray();
        }

        private static void le(ByteArrayOutputStream o, int v, int bytes) {
            for (int i = 0; i < bytes; i++) o.write((v >> (8 * i)) & 0xFF);
        }
    }

    /** The language of his speech for the AI ("te-IN" → "te"). */
    private String lang() {
        String l = p.listenLang();
        int i = l.indexOf('-');
        return (i > 0 ? l.substring(0, i) : l).toLowerCase(Locale.ROOT);
    }

    private static final String TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";

    /** The form before the sound: model, language, the Telugu hint, the file's start (and, sent while he talks, its WAV header). */
    private byte[] uploadHead(String boundary, boolean wavHeader) {
        StringBuilder head = new StringBuilder();
        field(head, boundary, "model", p.earsModel());
        if (!anyLang) field(head, boundary, "language", lang()); // (any language: the AI finds it)
        if ("te".equals(lang()) && !anyLang) field(head, boundary, "prompt", "తెలుగులో మాట్లాడుతున్నారు. తెలుగు మాటలు తెలుగు లిపిలో, English మాటలు English లో.");
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
        byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
        if (!wavHeader) return h;
        byte[] w = Talk.wavHeader(-1); // (the length isn't known yet: "to the end")
        byte[] all = java.util.Arrays.copyOf(h, h.length + w.length);
        System.arraycopy(w, 0, all, h.length, w.length);
        return all;
    }

    private static byte[] uploadTail(String boundary) { return ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8); }

    private static String readBody(HttpURLConnection con, int code) throws java.io.IOException {
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        if (in != null) in.close();
        return b.toString("UTF-8");
    }

    /** OpenAI speech-to-text, the whole recording at once (the model he set; by default the one the voice-message "మాటలు" uses). */
    private String openAi(String key, byte[] wav) throws Exception {
        String boundary = "----jarvisears" + System.nanoTime();
        byte[] h = uploadHead(boundary, false);
        byte[] tail = uploadTail(boundary);
        HttpURLConnection con = (HttpURLConnection) new URL(TRANSCRIBE_URL).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(h.length + wav.length + tail.length);
            con.setRequestProperty("Authorization", "Bearer " + key);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = con.getOutputStream()) {
                out.write(h);
                out.write(wav);
                out.write(tail);
            }
            sentAt = SystemClock.elapsedRealtime();
            int code = con.getResponseCode();
            String body = readBody(con, code);
            if (code >= 400) throw new Http.ApiError(code, body);
            return new JSONObject(body).optString("text", "");
        } finally {
            con.disconnect();
        }
    }

    /**
     * His voice on its way to OpenAI while he is still talking: the connection is made when he starts, the sound goes as
     * it comes, and when he stops only the last moment and the form's end are left to send. Dropped, never finished (so
     * nothing is written out or charged), when it was only a noise or the listen is stopped.
     */
    private static final class Upload {
        private static final byte[] END = new byte[0];
        private final java.util.concurrent.LinkedBlockingQueue<byte[]> q = new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.CountDownLatch over = new java.util.concurrent.CountDownLatch(1);
        private volatile HttpURLConnection con;
        private volatile boolean aborted;
        /** The words (it worked), or why not. */
        volatile String text;
        volatile Exception error;
        /** When the last byte went out. */
        volatile long sentAt;

        Upload(String key, byte[] head, byte[] tail, String boundary) {
            new Thread(() -> send(key, head, tail, boundary), "jarvis-ears-up").start();
        }

        void feed(byte[] b) { if (b != null && b.length > 0 && !aborted) q.offer(b); }

        /** He stopped: the end goes out and the words come back. */
        void finish() { q.offer(END); }

        void abort() {
            aborted = true;
            HttpURLConnection c = con;
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            q.offer(END);
        }

        boolean await(long ms) throws InterruptedException { return over.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); }

        private void send(String key, byte[] head, byte[] tail, String boundary) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(TRANSCRIBE_URL).openConnection();
                con = c;
                if (aborted) return;
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setDoOutput(true);
                c.setChunkedStreamingMode(4096);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                OutputStream out = c.getOutputStream(); // (connects: while he is still talking)
                out.write(head);
                out.flush();
                while (true) {
                    byte[] b = q.take();
                    if (aborted) return; // never finished: the half-sent form is not written out
                    if (b == END) break;
                    out.write(b);
                    if (q.isEmpty()) out.flush();
                }
                out.write(tail);
                out.close();
                sentAt = SystemClock.elapsedRealtime();
                int code = c.getResponseCode();
                String body = readBody(c, code);
                if (code >= 400) throw new Http.ApiError(code, body);
                text = new JSONObject(body).optString("text", "");
            } catch (Exception e) {
                if (!aborted) error = e;
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                over.countDown();
            }
        }
    }

    private static void field(StringBuilder b, String boundary, String name, String value) {
        b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(value).append("\r\n");
    }

    /** Gemini (his Gemini model and key) writes out the words. */
    private String gemini(String key, byte[] wav) throws Exception {
        String sys = "You write out speech exactly as it is said. Telugu in Telugu script, English words in English. "
                + "Output only the words said, nothing else (no quotes, no notes). If nothing clear is said, output nothing.";
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", anyLang ? "Write out what is said, in the language and script it is said in (Hindi in Devanagari, English in English, Telugu in Telugu)."
                        : "Write out what is said (" + lang() + ")."))
                .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/wav")
                        .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))));
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject().put("maxOutputTokens", 600).put("temperature", 0)
                        .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
        return Brain.geminiText(Brain.geminiCall(p, key, body));
    }
}
