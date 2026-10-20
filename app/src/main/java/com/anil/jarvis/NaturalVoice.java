package com.anil.jarvis;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Natural-sounding voice from OpenAI text-to-speech, streamed as raw 24 kHz PCM
 * straight into the speaker so Jarvis starts talking before the whole answer is downloaded.
 */
final class NaturalVoice {
    interface Callback {
        void onStart();
        void onDone();
        void onError(String message);
        /** Google Gemini / Sarvam: the reply stopped part-way (a later piece didn't come); onDone follows. */
        default void onCut(String message) {}
        /** The first sound really came out of the speaker, at this moment (SystemClock.elapsedRealtime) — Jarvis చెక్. */
        default void onAudible(long at) {}
        /**
         * ♻️ (guard on): this reply was given up — why (Telugu, for Jarvis చెక్), heard: the sound frame he had heard up to
         * (-1: none of it was heard). Neither onDone nor onError follows for it.
         */
        default void onStalled(String why, long heard) { if (heard >= 0) onDone(); else onError(why); }
    }

    /** The speaking model when he hasn't chosen one. */
    static final String DEFAULT_MODEL = "gpt-4o-mini-tts";
    /** The older models (tts-1, tts-1-hd) have only these voices, and no feelings (instructions). */
    private static final java.util.Set<String> OLD_VOICES = new java.util.HashSet<>(java.util.Arrays.asList(
            "alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer"));

    /** The model to speak with (Settings → సహజ గొంతు → మోడల్); set before speak(). */
    volatile String model = DEFAULT_MODEL;
    /**
     * Google Gemini or Sarvam Bulbul instead of OpenAI (his choice "Jarvis గొంతు ఎవరిది"); null: OpenAI as always.
     * Set before speak(); the key, model and voice passed to speak() are then not used.
     */
    volatile CloudVoice cloud;

    static final String[] VOICES = {"cedar", "marin", "ash", "ballad", "verse", "echo", "sage", "coral", "alloy", "shimmer"};
    static final int RATE = 24000;
    /** The home tablet talks with అమ్మగారు: a little slower, very clear, warm and respectful (HomeCare sets it). */
    static volatile boolean homeStyle;
    static final String HOME_STYLE = " You are speaking to an elderly Telugu mother at home: speak a little slower and very clearly, warm and respectful, with gentle pauses between sentences.";

    static final String STYLE =
            "Voice: calm, refined and quietly warm, like JARVIS the British butler AI from the Iron Man films. "
            + "Language: the text is Telugu. Speak ONLY Telugu, with a native Andhra/Telangana Telugu accent and pronunciation. "
            + "Never switch to Tamil, Kannada, Malayalam or Hindi pronunciation, not even for single words; similar-looking words must still sound Telugu. "
            + "English words in the text are said the way Telugu speakers say them. "
            + "Pace: natural and unhurried. Tone: polite, confident, with a hint of dry wit.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile AudioTrack track;
    /** Play through the call path (talk-over on), so the phone's echo canceller can remove this voice. */
    volatile boolean voiceCall;
    /**
     * ⚡ G (set before speak()): the speaker starts after 0.1-0.25 s of sound instead of filling half a second first (Android 12+),
     * and the silence before the first word is skipped. Off: exactly as before.
     */
    volatile boolean quick;

    /**
     * ♻️ గొంతు కాపలా (set before speak(); the OpenAI voice only): the reply must really be heard. No words coming in 6 s,
     * the sound stopping for 4 s (before he heard any of it, or part-way once all that came has played), the speaker not
     * starting, or the line breaking: the reply is given up and onStalled says why and how far he heard (VoiceIO asks once
     * more). And a reply he never heard is never "done". Off: exactly as before.
     */
    volatile boolean guard;
    /** ♻️ The next speak() asks on a line of its own (not one kept open from before); speak() clears it. */
    volatile boolean fresh;

    static final long NO_VOICE_MS = 6000, NO_VOICE_FRESH_MS = 8000, STALL_MS = 4000;
    static final String WHY_NOTHING = "6 సెకన్లలో గొంతు రాలేదు";
    static final String WHY_SILENT = "6 సెకన్లలో మాటలు రాలేదు (నిశ్శబ్దం మాత్రమే)";
    static final String WHY_STALL = "గొంతు వస్తూ 4 సెకన్లు ఆగిపోయింది";
    static final String WHY_SPEAKER = "స్పీకర్ మొదలవలేదు";
    static final String WHY_PART = "జవాబు మధ్యలో గొంతు 4 సెకన్లు ఆగిపోయింది";
    static final String WHY_EMPTY = "గొంతు ఖాళీగా వచ్చింది (మాటలు లేవు)";

    /** ♻️ One reply's watch. */
    private static final class Watch {
        final long askAt = SystemClock.elapsedRealtime();
        final long noVoiceMs;
        Watch(boolean fresh) { noVoiceMs = fresh ? NO_VOICE_FRESH_MS : NO_VOICE_MS; }
        volatile long lastByteAt, voiceAt, readyAt;  // last sound bytes came / words first seen / the speaker was let start (0: not yet)
        volatile boolean heard, over;
        volatile String why;                          // set: this reply is being given up
        private final java.util.concurrent.atomic.AtomicBoolean told = new java.util.concurrent.atomic.AtomicBoolean();
        /** Only the first of the watch / the speaking thread tells VoiceIO how this reply ended. */
        boolean tell() { return told.compareAndSet(false, true); }
        boolean told() { return told.get(); }
    }

    /** ♻️ Thrown inside the speaking thread when the watch has given the reply up. */
    private static final class Stalled extends Exception {}
    /** The speaker refused the sound (not the line): no asking again for that. */
    private static final class SpeakerFail extends Exception { SpeakerFail(String m) { super(m); } }
    /** The download broke (the line / OpenAI): worth asking again. Same message as before. */
    private static final class LineFail extends Exception { LineFail(String m) { super(m); } }

    /**
     * ♻️ The guard's rule (no Android in it, for the desk test): why this reply should be given up now, or null. calm: when
     * a ⏸ last ended (time paused never counts); caughtUp: the speaker has played all the sound that came; noVoiceMs: 6 s
     * (8 s for the one asked again: a line of its own takes a moment to open).
     */
    static String stallWhy(long now, long askAt, long calm, long lastByteAt, boolean finished, long voiceAt, long readyAt,
                           boolean heard, boolean caughtUp, long noVoiceMs) {
        if (!heard) {
            if (voiceAt == 0 && now - Math.max(askAt, calm) >= noVoiceMs) return lastByteAt == 0 ? WHY_NOTHING : WHY_SILENT;
            if (lastByteAt > 0 && !finished && now - Math.max(lastByteAt, calm) >= STALL_MS) return WHY_STALL;
            if (voiceAt > 0 && readyAt > 0 && now - Math.max(Math.max(voiceAt, readyAt), calm) >= STALL_MS) return WHY_SPEAKER;
            return null;
        }
        if (!finished && caughtUp && lastByteAt > 0 && now - Math.max(lastByteAt, calm) >= STALL_MS) return WHY_PART;
        return null;
    }

    /** ♻️ The reason for a broken line / an OpenAI fault (Telugu, short). */
    static String lineWhy(String msg) {
        String m = msg == null ? "" : msg.trim();
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("^TTS (5\\d\\d)").matcher(m);
        if (x.find()) return "OpenAI గొంతు " + x.group(1) + " (వాళ్ళ వైపు సమస్య)";
        if (m.length() > 60) m = m.substring(0, 60);
        return "గొంతు లైన్ తెగింది" + (m.isEmpty() || "null".equals(m) ? "" : " (" + m + ")");
    }

    /** A line for the voice; fresh: one of its own, not one the phone kept open (when no proxy is set: the same way out). */
    private static HttpURLConnection open(String url, boolean fresh) throws java.io.IOException {
        URL u = new URL(url);
        if (fresh) {
            try {
                boolean direct = true;
                for (java.net.Proxy p : java.net.ProxySelector.getDefault().select(u.toURI()))
                    if (p.type() != java.net.Proxy.Type.DIRECT) direct = false;
                if (direct) return (HttpURLConnection) u.openConnection(java.net.Proxy.NO_PROXY);
            } catch (Exception ignored) {}
        }
        return (HttpURLConnection) u.openConnection();
    }

    private static volatile long warmedAt;

    /**
     * ⚡ G: while the brain thinks, the voice's internet line is opened (a tiny request without the key, which the server
     * just refuses), so the answer's voice needn't wait for a new connection. At most every 20 s; nothing is sent about him.
     */
    static void warm(android.content.Context c, Prefs p) {
        if (!p.speedG() || !p.voiceReplies() || !p.naturalVoice() || !Net.online(c)) return;
        CloudVoice cv = CloudVoice.of(p);
        if (cv == null ? p.openAiKey().trim().isEmpty() : !cv.ready()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - warmedAt < 20_000) return;
        warmedAt = now;
        final String url = cv == null ? "https://api.openai.com/v1/models"
                : CloudVoice.SARVAM.equals(cv.provider) ? "https://api.sarvam.ai/" : "https://generativelanguage.googleapis.com/";
        new Thread(() -> {
            try {
                HttpURLConnection c2 = (HttpURLConnection) new URL(url).openConnection();
                c2.setConnectTimeout(8000);
                c2.setReadTimeout(8000);
                int code = c2.getResponseCode();
                InputStream in = code >= 400 ? c2.getErrorStream() : c2.getInputStream();
                if (in != null) try (InputStream r = in) { byte[] b = new byte[4096]; while (r.read(b) > 0) { /* read to the end: the line goes back to the pool */ } }
                // (no disconnect(): that could close the line the voice is about to use)
            } catch (Throwable ignored) {}
        }, "jarvis-tts-warm").start();
    }
    /**
     * Jarvis's own echo removal for the talk-over (BargeIn): told what this voice plays and when it leaves the speaker,
     * so the mic can be cleaned of it (no phone-call mode needed). Null: not used. Set before speak().
     */
    volatile EchoGuard echo;
    /** The echo removal this reply feeds; the speaker's position after its last start and the frames handed since. */
    private volatile EchoGuard curEcho;
    private long echoBase, echoWritten;
    private final AudioTimestamp echoStamp = new AudioTimestamp();
    private long outLatNs = 60_000_000L;
    /** Paused by ⏸ / "ఆపు": the speaker is stopped and emptied; ▶ plays again from where it was heard. */
    private volatile boolean paused;
    private final Object lock = new Object();   // pause/resume vs. writing to the speaker
    /** Sound frame the speaker's position 0 stands for (moves when the speaker is emptied at a pause). */
    private volatile long base;
    /** Where the writer must continue after a pause (bytes into pcm), or -1. */
    private volatile long seekTo = -1;

    /** The reply's sound as it downloads: append on one thread, read from any position on another. */
    private static final class Pcm {
        private byte[] data = new byte[1 << 20];
        private int size;
        private boolean finished;
        private String error;
        synchronized void reset() { size = 0; finished = false; error = null; }
        synchronized void add(byte[] b, int len) {
            if (size + len > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + len));
            System.arraycopy(b, 0, data, size, len);
            size += len;
        }
        synchronized void finish(String err) { finished = true; error = err; }
        synchronized int size() { return size; }
        /** Copies up to out.length bytes from pos; -1 = no more will come; 0 = wait. */
        synchronized int read(long pos, byte[] out) {
            int n = (int) Math.min(out.length, size - pos);
            if (n <= 0) return finished ? -1 : 0;
            n &= ~1;
            System.arraycopy(data, (int) pos, out, 0, n);
            return n;
        }
        synchronized boolean finished() { return finished; }
        synchronized String error() { return error; }
    }

    void pause() {
        synchronized (lock) {
            if (paused) return;
            AudioTrack t = track;
            long heard = t == null ? 0 : playedFrames(t, false); // what has really come out of the speaker
            paused = true;
            if (t == null) return;
            try {
                long headBefore = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                t.pause();
                t.flush();                                   // drop what was queued: we play it again from our copy
                long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                EchoGuard eg = curEcho;
                if (eg != null) { // (what was queued never sounds: the echo removal forgets it)
                    eg.flushed(headBefore, head);
                    echoBase = head;
                    echoWritten = 0;
                }
                long from = restartPoint(heard);
                base = from - head;
                seekTo = from * 2;
                PlaybackLevel.begin(t, RATE, head);
            } catch (Exception ignored) {}
        }
    }

    void resume() {
        synchronized (lock) {
            paused = false;
            AudioTrack t = track;
            if (t != null) try { t.play(); } catch (Exception ignored) {}
        }
    }

    boolean isPaused() { return paused; }

    /** Carry on from the start of the phrase he was hearing (the last pause within 3 s), else 0.3 s back. */
    private long restartPoint(long heard) {
        long best = Math.max(0, heard - RATE * 3 / 10);
        for (long[] p : pauses()) {
            if (p[1] <= heard && heard - p[1] <= RATE * 3L) best = p[1] - RATE / 20; // just before the phrase starts
        }
        return Math.max(0, Math.min(best, heard));
    }

    // For the word highlight: how much sound has arrived / played so far, and where the pauses are.
    private volatile long arrived;
    private volatile boolean complete;
    long totalFrames() { return arrived; }
    boolean downloaded() { return complete; }
    private final AudioTimestamp stamp = new AudioTimestamp();

    /** The sound frame (from the start of the reply) coming out of the speaker right now. */
    long playedFrames() {
        AudioTrack t = track;
        return t == null ? 0 : playedFrames(t, true);
    }

    private long playedFrames(AudioTrack t, boolean extrapolate) {
        try {
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long pos = head;
            if (!paused && t.getTimestamp(stamp)) {
                long f = stamp.framePosition;
                if (extrapolate) f += (System.nanoTime() - stamp.nanoTime) * RATE / 1_000_000_000L;
                pos = Math.max(0, Math.min(f, head));   // output delay included
            }
            return Math.max(0, base + pos);
        } catch (Exception e) {
            return Math.max(0, base);
        }
    }

    // Pauses in the voice (between sentences / at commas): [start, end] frames, in order.
    private static final int BLOCK = RATE / 50;          // 20 ms
    private static final int MIN_PAUSE_BLOCKS = 8;       // 160 ms or longer counts as a pause
    private final java.util.ArrayList<long[]> pauses = new java.util.ArrayList<>();
    private double blockSum, loudLevel;
    private int blockN, quietRun;
    private long blockIndex, quietFrom = -1;

    private volatile long firstSound = -1;

    synchronized java.util.List<long[]> pauses() { return new java.util.ArrayList<>(pauses); }

    /** Frame where the voice actually starts (after the short silence at the beginning), or -1. */
    long firstSound() { return firstSound; }

    private synchronized void resetPauses() {
        pauses.clear(); blockSum = 0; blockN = 0; quietRun = 0; blockIndex = 0; quietFrom = -1; firstSound = -1; loudLevel = 0;
    }

    /** Finds pauses in the arriving sound (16-bit little-endian mono); "quiet" is judged against the voice's own loudness. */
    private synchronized void scanPauses(byte[] b, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            int x = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            blockSum += (double) x * x;
            if (++blockN < BLOCK) continue;
            double rms = Math.sqrt(blockSum / blockN);
            blockSum = 0;
            blockN = 0;
            double quiet = Math.max(250, loudLevel * 0.12);
            if (rms < quiet) {
                if (quietRun++ == 0) quietFrom = blockIndex * BLOCK;
            } else {
                loudLevel = loudLevel == 0 ? rms : loudLevel * 0.98 + rms * 0.02;
                if (firstSound < 0) firstSound = blockIndex * BLOCK;
                else if (quietRun >= MIN_PAUSE_BLOCKS && quietFrom > 0) pauses.add(new long[]{quietFrom, blockIndex * BLOCK});
                quietRun = 0;
            }
            blockIndex++;
        }
    }

    /** Speaks text; any earlier speech stops. Callbacks arrive on the main thread. */
    void speak(String apiKey, String voice, String text, Callback cb) {
        speak(apiKey, voice, text, Emotion.CALM, cb);
    }

    /** Speaks with a feeling (Emotion names: happy, laugh, sad...). */
    void speak(String apiKey, String voice, String text, String emotion, Callback cb) {
        final int gen = ++generation;
        stopTrack();
        synchronized (lock) { paused = false; base = 0; seekTo = -1; }
        arrived = 0;
        complete = false;
        resetPauses();
        final String style = STYLE + (homeStyle ? HOME_STYLE : "") + Emotion.style(emotion);
        final String m = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
        final EchoGuard eg = echo;
        final CloudVoice cv = cloud;
        final boolean q = quick;
        final boolean g = guard && cv == null; // ♻️ (Gemini / Sarvam keep their own way of trying again)
        final boolean f = fresh;
        fresh = false;
        CloudVoice was = fetching;
        if (was != null) was.cancel(); // (the last reply's request, still on its way)
        cutAsking();
        new Thread(() -> run(gen, apiKey, m, voice, text, style, eg, cv, cb, q, g, f), "jarvis-tts").start();
    }

    /**
     * When the first sound of this reply was really heard (the speaker's own time stamp of frame 0; else the moment the
     * speaker took the first frames): for Jarvis చెక్'s "స్పీకర్" part. Watches up to 4 s on its own thread.
     */
    private void watchAudible(int gen, AudioTrack t, Callback cb) {
        android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
        long until = SystemClock.elapsedRealtime() + 4000;
        while (gen == generation && SystemClock.elapsedRealtime() < until) {
            long at = -1;
            try {
                if (!paused) {
                    if (t.getTimestamp(ts) && ts.framePosition > 0) {
                        long firstNs = ts.nanoTime - ts.framePosition * 1_000_000_000L / RATE; // frame 0 came out then
                        at = SystemClock.elapsedRealtime() - Math.max(0, System.nanoTime() - firstNs) / 1_000_000L;
                    } else if ((t.getPlaybackHeadPosition() & 0xFFFFFFFFL) > 0 && !stampWorks) {
                        at = SystemClock.elapsedRealtime(); // (no time stamps on this phone: when the speaker took them)
                    }
                    if (ts.framePosition > 0) stampWorks = true;
                }
            } catch (Exception e) {
                return; // (the speaker was let go: stopped)
            }
            if (at > 0) {
                final long when = at;
                main.post(() -> { if (gen == generation) cb.onAudible(when); });
                return;
            }
            SystemClock.sleep(10);
        }
    }

    /** This phone's speaker gives real time stamps (learnt on the first reply). */
    private static volatile boolean stampWorks;

    void stop() {
        generation++;
        CloudVoice was = fetching;
        if (was != null) was.cancel();
        cutAsking();
        paused = false;
        stopTrack();
    }

    /** The OpenAI voice request now on its way (null: none). */
    private volatile HttpURLConnection asking;

    /** ⏹ / a new reply: the OpenAI request still on its way is cut at once (off the main thread: closing may touch the net). */
    private void cutAsking() {
        final HttpURLConnection a = asking;
        asking = null;
        if (a != null) new Thread(() -> { try { a.disconnect(); } catch (Throwable ignored) {} }, "jarvis-tts-cut").start();
    }

    private void stopTrack() {
        AudioTrack t;
        synchronized (lock) { // not while the speaker thread is writing to it or just putting it in place
            t = track;
            track = null;
        }
        if (t != null) {
            PlaybackLevel.end(t);
            try { t.pause(); t.flush(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void run(int gen, String apiKey, String model, String voice, String text, String style, EchoGuard eg, CloudVoice cv, Callback cb,
                     boolean q, boolean g, boolean f) {
        HttpURLConnection c = null;
        AudioTrack t = null;
        final boolean[] started = {false};
        final Watch w = g ? new Watch(f) : null; // ♻️ (null: guard off, exactly as before)
        final Pcm pcm = new Pcm();
        final long[] wrote = {0};               // bytes of the reply handed to the speaker
        int stage = 0;                          // ♻️ 0: not asked yet · 1: asked (a failure now is the line's / OpenAI's) · 2: OpenAI said no (4xx)
        try {
          if (cv == null) { // OpenAI (as always): the request is made, and answered, before the speaker opens
            boolean old = model.toLowerCase(java.util.Locale.ROOT).startsWith("tts-1");
            if (old && !OLD_VOICES.contains(voice)) // (said plainly, before OpenAI refuses it)
                throw new IllegalStateException(model + " లో \"" + voice + "\" గొంతు లేదు: alloy, ash, coral, echo, sage, shimmer లో ఒకటి ఎంచుకోండి");
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("voice", voice)
                    .put("input", text)
                    .put("response_format", "pcm");
            if (!old) body.put("instructions", style); // (the older models take no feelings)
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c = open("https://api.openai.com/v1/audio/speech", f);
            asking = c;
            if (gen != generation) { if (asking == c) asking = null; return; } // (stopped while the request was being made)
            if (w != null) { // ♻️ the watch, from the moment it is asked
                final HttpURLConnection mine = c;
                new Thread(() -> watch(gen, w, pcm, mine, cb), "jarvis-tts-guard").start();
            }
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000); // a stalled stream must not leave Jarvis "speaking" in silence (and the mic shut) for a minute
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json");
            if (f) c.setRequestProperty("Connection", "close"); // ♻️ (this line isn't kept for later either)
            c.setFixedLengthStreamingMode(bytes.length);
            stage = 1;
            c.connect();
            if (gen != generation) return; // (stopped before the line was open: disconnect couldn't cut it; finally closes it)
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            if (status >= 400) {
                if (status < 500) stage = 2;
                String err = "";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) {
                        byte[] b = new byte[2048];
                        int n = es.read(b);
                        if (n > 0) err = new String(b, 0, n, StandardCharsets.UTF_8);
                    }
                }
                throw new IllegalStateException("TTS " + status + " " + err);
            }
            stage = 3; // ♻️ answered: from here a failure is the phone's own (the speaker), unless the download breaks (LineFail)
          }

            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(voiceCall ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, RATE))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (q && android.os.Build.VERSION.SDK_INT >= 31) {
                // ⚡ G: sound after 0.1 s (Gemini / Sarvam: the piece is all there) or 0.25 s (OpenAI streams it: a little kept
                // in hand for a slow line), not after the whole half second
                try { t.setStartThresholdInFrames(cv != null ? RATE / 10 : RATE / 4); } catch (Exception ignored) {}
            }
            if (gen != generation) { t.release(); return; }
            synchronized (lock) {
                if (gen != generation) { t.release(); t = null; return; } // stopped just now: never keep (or play) it
                track = t;
                try { t.setVolume(Whisper.gain()); } catch (Exception ignored) {} // he whispered "Jarvis": answer softly
                PlaybackLevel.begin(t, RATE, 0);
                curEcho = eg;
                echoBase = 0;
                echoWritten = 0;
                if (eg != null) eg.flushed(Long.MAX_VALUE / 8, 0); // a new speaker: its frames count from 0 (nothing dropped)
                if (!paused) t.play();
            }

            // Download on its own thread (never stalls, also while paused); this thread feeds the speaker.
            // All sound of this reply is kept, so ▶ can carry on from exactly where it stopped.
            final HttpURLConnection conn = c;
            Thread reader = new Thread(() -> {
                if (cv != null) { readCloud(gen, cv, text, pcm); return; }
                String err = null;
                byte[] buf = new byte[8192];
                int carry = -1; // an odd byte left over from the previous read
                long frames = 0;
                try (InputStream in = conn.getInputStream()) {
                    int n;
                    while ((n = in.read(buf, carry >= 0 ? 1 : 0, buf.length - (carry >= 0 ? 1 : 0))) > 0) {
                        if (gen != generation) break;
                        int len = n;
                        if (carry >= 0) { buf[0] = (byte) carry; len++; carry = -1; }
                        if ((len & 1) == 1) { carry = buf[len - 1] & 0xFF; len--; }
                        if (len == 0) continue;
                        pcm.add(buf, len);
                        if (gen != generation) break;
                        scanPauses(buf, len);
                        frames += len / 2;
                        arrived = frames;
                        if (w != null) w.lastByteAt = SystemClock.elapsedRealtime();
                    }
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                try { Usage.tts(frames); } catch (Throwable ignored) {} // the cost meter never stops the voice
                if (err == null && gen == generation) complete = true;
                pcm.finish(err);
            }, "jarvis-tts-download");
            reader.start();

            byte[] chunk = new byte[8192];
            long pos = 0; // bytes of pcm handed to the speaker
            // ⚡ F: a "ఒక్క క్షణం, చూస్తున్నాను" still sounding: this answer starts right after it (never over it, never cutting it)
            long fillerUntil = SystemClock.elapsedRealtime() + 3000;
            while (gen == generation && (w == null || w.why == null) && Filler.playing() && SystemClock.elapsedRealtime() < fillerUntil)
                SystemClock.sleep(20);
            if (q) { // ⚡ G: the silence before the first word is skipped (a little kept, so the word isn't clipped)
                long until = SystemClock.elapsedRealtime() + 1500;
                while (gen == generation && (w == null || w.why == null) && firstSound < 0 && !pcm.finished() && arrived < RATE
                        && SystemClock.elapsedRealtime() < until)
                    SystemClock.sleep(5);
                long fs = firstSound;
                if (fs > BLOCK * 2 && fs < RATE) {
                    long skip = fs - BLOCK * 2;
                    synchronized (lock) { if (seekTo < 0 && !paused) { base = skip; pos = skip * 2; } }
                }
            }
            if (w != null) w.readyAt = SystemClock.elapsedRealtime();
            while (gen == generation) {
                if (w != null && w.why != null) throw new Stalled();
                if (paused) { SystemClock.sleep(20); continue; }
                long seek = seekTo;
                if (seek >= 0) { pos = seek; seekTo = -1; }
                int n = pcm.read(pos, chunk);
                if (n < 0) break;                                   // all written
                if (n == 0) {
                    if (pcm.finished() && pcm.error() != null) throw new LineFail(pcm.error());
                    SystemClock.sleep(10);
                    continue;
                }
                int wr;
                synchronized (lock) {
                    if (paused || seekTo >= 0 || gen != generation) continue; // paused meanwhile: don't queue stale sound
                    wr = writeOut(t, chunk, n);
                }
                if (wr < 0) throw new SpeakerFail("audio " + wr);
                if (wr == 0) { SystemClock.sleep(10); continue; }
                if (!started[0]) {
                    started[0] = true;
                    main.post(() -> { if (gen == generation) cb.onStart(); });
                    final AudioTrack heardOn = t;
                    new Thread(() -> watchAudible(gen, heardOn, cb), "jarvis-tts-heard").start();
                }
                pos += wr;
                wrote[0] = pos;
            }
            if (pcm.error() != null && !started[0]) throw new LineFail(pcm.error());
            // ♻️ the download broke part-way (all that came has been handed over): carry on, not "done" (pcm.read gives
            // -1 once the download ends, error or not)
            if (w != null && pcm.error() != null) throw new LineFail(pcm.error());
            // Wait until everything has actually been heard (pauses don't count; ▶ may seek back).
            long total = pcm.size() / 2;
            long deadline = SystemClock.elapsedRealtime() + total * 1000 / RATE + 1500;
            while (gen == generation && playedFrames(t, false) < total) {
                if (w != null && w.why != null) throw new Stalled();
                if (paused || seekTo >= 0) {
                    SystemClock.sleep(40);
                    deadline = SystemClock.elapsedRealtime() + (total - base) * 1000 / RATE + 1500;
                    if (!paused && seekTo >= 0) { // ▶ after the download finished: play the rest again from the seek point
                        long from = seekTo;
                        seekTo = -1;
                        writeRest(t, gen, from, pcm);
                    }
                    continue;
                }
                if (SystemClock.elapsedRealtime() > deadline) break;
                SystemClock.sleep(40);
            }
            if (w != null && gen == generation) { // ♻️ never "done" with a reply he didn't hear
                if (!heardNow(t, w)) { tellStalled(gen, w, firstSound < 0 ? WHY_EMPTY : WHY_SPEAKER, -1, cb); return; }
                if (!w.tell()) return; // (the watch has said it already)
            }
            final String cut = cv != null && started[0] ? pcm.error() : null; // (a later piece didn't come: said in Jarvis చెక్)
            if (gen == generation) main.post(() -> {
                if (gen != generation) return;
                if (cut != null) cb.onCut(cut);
                cb.onDone();
            });
        } catch (Exception e) {
            if (gen == generation) {
                final String msg = String.valueOf(e.getMessage());
                if (w != null) { // ♻️
                    if (!w.told()) {
                        final boolean heard = heardNow(t, w);
                        String why = w.why;
                        if (why == null && (stage == 1 || e instanceof LineFail) && !(e instanceof SpeakerFail) && !(e instanceof Stalled))
                            why = lineWhy(msg);
                        if (why != null) {
                            tellStalled(gen, w, why, heard ? drain(gen, t, wrote[0]) : -1, cb);
                        } else if (w.tell()) {
                            main.post(() -> {
                                if (gen != generation) return;
                                if (heard) cb.onDone(); else cb.onError(msg); // (unheard: the phone's voice says it)
                            });
                        }
                    }
                } else {
                    final boolean playedSome = started[0];
                    main.post(() -> {
                        if (gen != generation) return;
                        if (playedSome) cb.onDone(); else cb.onError(msg);
                    });
                }
            }
        } finally {
            if (w != null) w.over = true;
            if (c != null && asking == c) asking = null;
            if (c != null) c.disconnect();
            if (t != null) PlaybackLevel.end(t);
            if (t != null && track == t && gen == generation) {
                track = null;
                try { t.stop(); } catch (Exception ignored) {}
                t.release();
            }
        }
    }

    /** ♻️ Some of the voice (past its first words) has really come out of the speaker. */
    private boolean heardNow(AudioTrack t, Watch w) {
        if (w.heard) return true;
        long fs = firstSound;
        return t != null && fs >= 0 && playedFrames(t, false) > fs + RATE / 10;
    }

    /** ♻️ Part-way: what had come plays out first (so the next part starts where he really stopped hearing); where that is. */
    private long drain(int gen, AudioTrack t, long wroteBytes) {
        long end = wroteBytes / 2, until = SystemClock.elapsedRealtime() + 3000;
        while (gen == generation && !paused && playedFrames(t, false) < end - BLOCK && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(20);
        return playedFrames(t, false);
    }

    /** ♻️ Tells VoiceIO this reply was given up (once; the watch or the speaking thread, whichever comes first). */
    private void tellStalled(int gen, Watch w, String why, long heard, Callback cb) {
        w.why = why;
        if (!w.tell()) return;
        main.post(() -> { if (gen == generation) cb.onStalled(why, heard); });
    }

    /**
     * ♻️ Watches one OpenAI reply every 0.1 s until it ends: the words must come, keep coming, and be heard (stallWhy).
     * When not, the line is cut and VoiceIO told at once (a line stuck opening can't hold it up for 15 s).
     */
    private void watch(int gen, Watch w, Pcm pcm, HttpURLConnection conn, Callback cb) {
        long calm = 0;
        boolean held = false;
        while (!w.over && gen == generation && !w.told()) {
            SystemClock.sleep(100);
            if (w.over || gen != generation || w.told()) return;
            long now = SystemClock.elapsedRealtime();
            if (paused) { held = true; continue; }        // (⏸: the time paused doesn't count)
            if (held) { held = false; calm = now; }
            AudioTrack t = track;
            long fs = firstSound;
            if (fs >= 0 && w.voiceAt == 0) w.voiceAt = now;
            long played = t == null ? 0 : playedFrames(t, false);
            if (!w.heard && t != null && fs >= 0 && played > fs + RATE / 10) w.heard = true;
            String why = stallWhy(now, w.askAt, calm, w.lastByteAt, pcm.finished(), w.voiceAt, w.readyAt, w.heard,
                    played >= arrived - RATE / 20, w.noVoiceMs);
            if (why == null) continue;
            w.why = why;
            if (!w.tell()) return;
            final long heardAt = w.heard ? played : -1;
            if (asking == conn) asking = null;
            new Thread(() -> { try { conn.disconnect(); } catch (Throwable ignored) {} }, "jarvis-tts-cut").start();
            main.post(() -> { if (gen == generation) cb.onStalled(why, heardAt); });
            return;
        }
    }

    /**
     * Google Gemini / Sarvam: the words in pieces, each asked for as soon as the one before it has arrived (so the next is
     * coming while this one plays); the sound joins the same reply sound as OpenAI's stream does.
     */
    private volatile CloudVoice fetching;

    private void readCloud(int gen, CloudVoice cv, String text, Pcm pcm) {
        String err = null;
        long frames = 0;
        fetching = cv;
        try {
            for (String piece : CloudVoice.pieces(text, CloudVoice.FIRST_PIECE, CloudVoice.PIECE)) {
                if (gen != generation) break;
                byte[] b;
                try {
                    b = cv.say(piece);
                } catch (Exception first) { // busy / the line: once more, after a moment (the same company, never another)
                    if (gen != generation || !CloudVoice.retryable(first)) throw first;
                    SystemClock.sleep(1200);
                    if (gen != generation) break;
                    b = cv.say(piece);
                }
                if (gen != generation) break;
                if (b.length < 2) continue;
                int len = b.length & ~1;
                pcm.add(b, len);
                scanPauses(b, len);
                frames += len / 2;
                arrived = frames;
            }
            if (frames == 0 && gen == generation) err = CloudVoice.company(cv.provider) + ": గొంతు ఖాళీగా వచ్చింది";
        } catch (Http.ApiError e) {
            err = CloudVoice.company(cv.provider) + " " + e.status + " " + e.getMessage();
        } catch (Throwable e) { // (also out of memory: the speaker side must never wait for ever)
            err = CloudVoice.company(cv.provider) + ": " + e.getMessage();
        } finally {
            if (fetching == cv) fetching = null;
            if (err == null && gen == generation) complete = true;
            pcm.finish(err);
        }
    }

    /** Hands sound to the speaker (under lock): the face's level and the echo removal are told what was handed over. */
    private int writeOut(AudioTrack t, byte[] chunk, int n) {
        EchoGuard eg = curEcho;
        boolean dry = eg != null && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) >= echoBase + echoWritten; // it had run dry
        int w = t.write(chunk, 0, n, AudioTrack.WRITE_NON_BLOCKING);
        if (w > 0) {
            PlaybackLevel.feed(chunk, 0, w);
            if (eg != null) {
                eg.written(chunk, 0, w);
                echoWritten += w / 2;
                mapEcho(t, eg, dry);
            }
        }
        return w;
    }

    /**
     * Tells the echo removal when the speaker plays which frame: from the speaker's own time stamp while it plays on,
     * else (it had run dry: the stamp is from before the gap) from its position and the latency learnt (as Gemini Live).
     */
    private void mapEcho(AudioTrack t, EchoGuard eg, boolean afterDry) {
        long now = System.nanoTime();
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        boolean ok = false;
        if (!afterDry) {
            try { ok = t.getTimestamp(echoStamp); } catch (Exception ignored) {}
        }
        if (ok && echoStamp.nanoTime > now - 80_000_000L && echoStamp.nanoTime < now + 100_000_000L
                && echoStamp.framePosition >= echoBase && echoStamp.framePosition <= echoBase + echoWritten) {
            eg.presented(echoStamp.nanoTime, echoStamp.framePosition);
            long lat = echoStamp.nanoTime + (long) ((head - echoStamp.framePosition) * 1e9 / RATE) - now;
            if (lat > 0 && lat < 400_000_000L) outLatNs = (long) (.8 * outLatNs + .2 * lat);
        } else {
            eg.presented(now + outLatNs, head);
        }
    }

    /** After a pause late in the reply: writes the sound again from byte position from to the end. */
    private void writeRest(AudioTrack t, int gen, long from, Pcm pcm) {
        byte[] chunk = new byte[8192];
        long pos = from;
        while (gen == generation) {
            if (paused || seekTo >= 0) return; // paused again: the wait loop handles it
            int n = pcm.read(pos, chunk);
            if (n <= 0) return;
            int w;
            synchronized (lock) {
                if (paused || seekTo >= 0) return;
                w = writeOut(t, chunk, n);
            }
            if (w < 0) return;
            if (w == 0) { SystemClock.sleep(10); continue; }
            pos += w;
        }
    }
}
