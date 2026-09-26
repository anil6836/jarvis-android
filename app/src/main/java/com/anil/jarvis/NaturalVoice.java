package com.anil.jarvis;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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

    static final String[] VOICES = {"cedar", "marin", "ash", "ballad", "verse", "echo", "sage", "coral", "alloy", "shimmer"};
    static final int RATE = 24000;
    private static final String STYLE =
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
    /** Paused by the ⏸ button: sound holds, the rest keeps downloading, ▶ carries on from the same word. */
    private volatile boolean paused;

    void pause() {
        paused = true;
        AudioTrack t = track;
        if (t != null) try { t.pause(); } catch (Exception ignored) {}
    }

    void resume() {
        paused = false;
        AudioTrack t = track;
        if (t != null) try { t.play(); } catch (Exception ignored) {}
    }

    boolean isPaused() { return paused; }

    // For the word highlight: how much sound has arrived / played so far.
    private volatile long arrived;
    private volatile boolean complete;
    long totalFrames() { return arrived; }
    boolean downloaded() { return complete; }
    long playedFrames() {
        AudioTrack t = track;
        if (t == null) return 0;
        try { return t.getPlaybackHeadPosition() & 0xFFFFFFFFL; } catch (Exception e) { return 0; }
    }

    /** Speaks text; any earlier speech stops. Callbacks arrive on the main thread. */
    void speak(String apiKey, String voice, String text, Callback cb) {
        speak(apiKey, voice, text, Emotion.CALM, cb);
    }

    /** Speaks with a feeling (Emotion names: happy, laugh, sad...). */
    void speak(String apiKey, String voice, String text, String emotion, Callback cb) {
        final int gen = ++generation;
        paused = false;
        arrived = 0;
        complete = false;
        stopTrack();
        final String style = STYLE + Emotion.style(emotion);
        new Thread(() -> run(gen, apiKey, voice, text, style, cb), "jarvis-tts").start();
    }

    void stop() {
        generation++;
        paused = false;
        stopTrack();
    }

    private void stopTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            PlaybackLevel.end(t);
            try { t.pause(); t.flush(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void flushHeld(AudioTrack t, ByteArrayOutputStream held, int gen) {
        if (paused || held.size() == 0) return;
        byte[] h = held.toByteArray();
        held.reset();
        play(t, h, 0, h.length, held, gen);
    }

    /** Writes sound to the speaker; while paused it is kept in held instead (the download never stalls). */
    private void play(AudioTrack t, byte[] b, int off, int len, ByteArrayOutputStream held, int gen) {
        while (len > 0 && gen == generation) {
            if (paused) { held.write(b, off, len); return; }
            int w = t.write(b, off, len, AudioTrack.WRITE_NON_BLOCKING);
            if (w < 0) throw new IllegalStateException("audio " + w);
            if (w == 0) { SystemClock.sleep(10); continue; }
            PlaybackLevel.feed(b, off, w);
            off += w;
            len -= w;
        }
    }

    private void run(int gen, String apiKey, String voice, String text, String style, Callback cb) {
        HttpURLConnection c = null;
        AudioTrack t = null;
        boolean started = false;
        try {
            JSONObject body = new JSONObject()
                    .put("model", "gpt-4o-mini-tts")
                    .put("voice", voice)
                    .put("input", text)
                    .put("instructions", style)
                    .put("response_format", "pcm");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
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
            track = t;
            PlaybackLevel.begin(t, RATE);
            t.play();
            if (paused) t.pause();

            long frames = 0;
            ByteArrayOutputStream held = new ByteArrayOutputStream(); // sound that arrived while paused
            byte[] buf = new byte[8192];
            int carry = -1; // an odd byte left over from the previous read
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buf, carry >= 0 ? 1 : 0, buf.length - (carry >= 0 ? 1 : 0))) > 0) {
                    if (gen != generation) return;
                    int len = n;
                    if (carry >= 0) { buf[0] = (byte) carry; len++; carry = -1; }
                    if ((len & 1) == 1) { carry = buf[len - 1] & 0xFF; len--; }
                    if (len == 0) continue;
                    if (!started) {
                        started = true;
                        main.post(() -> { if (gen == generation) cb.onStart(); });
                    }
                    frames += len / 2;
                    arrived = frames;
                    flushHeld(t, held, gen);
                    play(t, buf, 0, len, held, gen);
                }
            }
            complete = true;
            // The download is complete; if paused, wait for ▶ and then play what was held back.
            while (gen == generation && (paused || held.size() > 0)) {
                if (paused) { SystemClock.sleep(50); continue; }
                flushHeld(t, held, gen);
            }
            // Wait until everything written has actually been played (pauses don't count).
            long deadline = SystemClock.elapsedRealtime() + frames * 1000 / RATE + 1500;
            while (gen == generation && t.getPlaybackHeadPosition() < frames) {
                if (paused) { SystemClock.sleep(40); deadline += 40; continue; }
                if (SystemClock.elapsedRealtime() > deadline) break;
                SystemClock.sleep(40);
            }
            if (gen == generation) main.post(() -> { if (gen == generation) cb.onDone(); });
        } catch (Exception e) {
            if (gen == generation) {
                final boolean playedSome = started;
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
}
