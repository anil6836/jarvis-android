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
    }

    /** The speaking model when he hasn't chosen one. */
    static final String DEFAULT_MODEL = "gpt-4o-mini-tts";
    /** The older models (tts-1, tts-1-hd) have only these voices, and no feelings (instructions). */
    private static final java.util.Set<String> OLD_VOICES = new java.util.HashSet<>(java.util.Arrays.asList(
            "alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer"));

    /** The model to speak with (Settings → సహజ గొంతు → మోడల్); set before speak(). */
    volatile String model = DEFAULT_MODEL;

    static final String[] VOICES = {"cedar", "marin", "ash", "ballad", "verse", "echo", "sage", "coral", "alloy", "shimmer"};
    static final int RATE = 24000;
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
        final String style = STYLE + Emotion.style(emotion);
        final String m = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
        final EchoGuard eg = echo;
        new Thread(() -> run(gen, apiKey, m, voice, text, style, eg, cb), "jarvis-tts").start();
    }

    void stop() {
        generation++;
        paused = false;
        stopTrack();
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

    private void run(int gen, String apiKey, String model, String voice, String text, String style, EchoGuard eg, Callback cb) {
        HttpURLConnection c = null;
        AudioTrack t = null;
        final boolean[] started = {false};
        try {
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
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000); // a stalled stream must not leave Jarvis "speaking" in silence (and the mic shut) for a minute
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            if (status >= 400) {
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
            final Pcm pcm = new Pcm();
            final HttpURLConnection conn = c;
            Thread reader = new Thread(() -> {
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
            while (gen == generation) {
                if (paused) { SystemClock.sleep(20); continue; }
                long seek = seekTo;
                if (seek >= 0) { pos = seek; seekTo = -1; }
                int n = pcm.read(pos, chunk);
                if (n < 0) break;                                   // all written
                if (n == 0) {
                    if (pcm.finished() && pcm.error() != null) throw new IllegalStateException(pcm.error());
                    SystemClock.sleep(10);
                    continue;
                }
                int w;
                synchronized (lock) {
                    if (paused || seekTo >= 0 || gen != generation) continue; // paused meanwhile: don't queue stale sound
                    w = writeOut(t, chunk, n);
                }
                if (w < 0) throw new IllegalStateException("audio " + w);
                if (w == 0) { SystemClock.sleep(10); continue; }
                if (!started[0]) {
                    started[0] = true;
                    main.post(() -> { if (gen == generation) cb.onStart(); });
                }
                pos += w;
            }
            if (pcm.error() != null && !started[0]) throw new IllegalStateException(pcm.error());
            // Wait until everything has actually been heard (pauses don't count; ▶ may seek back).
            long total = pcm.size() / 2;
            long deadline = SystemClock.elapsedRealtime() + total * 1000 / RATE + 1500;
            while (gen == generation && playedFrames(t, false) < total) {
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
            if (gen == generation) main.post(() -> { if (gen == generation) cb.onDone(); });
        } catch (Exception e) {
            if (gen == generation) {
                final boolean playedSome = started[0];
                final String msg = String.valueOf(e.getMessage());
                main.post(() -> {
                    if (gen != generation) return;
                    if (playedSome) cb.onDone(); else cb.onError(msg);
                });
            }
        } finally {
            if (c != null) c.disconnect();
            if (t != null) PlaybackLevel.end(t);
            if (t != null && track == t && gen == generation) {
                track = null;
                try { t.stop(); } catch (Exception ignored) {}
                t.release();
            }
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
