package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jarvis's instant reply when called: "చెప్పండి, Anil?".
 * With the natural voice it is downloaded once and kept on the phone, so it plays with no delay.
 */
final class Greeting {
    private static final int RATE = 24000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static TextToSpeech tts;

    private Greeting() {}

    static String text(Prefs p) { return "చెప్పండి, " + p.name() + "?"; }

    /** Plays the greeting, then runs done on the main thread (always, even on failure). */
    static void play(Context c, Prefs p, Runnable done) {
        Context app = c.getApplicationContext();
        // Once done has run (normally, or by the 6 s fallback) the mic may be open: never play after that.
        final AtomicBoolean fired = new AtomicBoolean(false);
        Runnable once = () -> { if (fired.compareAndSet(false, true)) done.run(); };
        main.postDelayed(once, 6000); // never leave Anil waiting
        String key = p.openAiKey().trim();
        if (p.naturalVoice() && !key.isEmpty()) {
            new Thread(() -> {
                try {
                    File f = new File(app.getFilesDir(), "greet_" + p.naturalVoiceName() + "_" + Integer.toHexString(text(p).hashCode()) + ".pcm");
                    if (!f.exists()) download(key, p.naturalVoiceName(), text(p), f);
                    if (!fired.get()) playPcm(f, fired); // a slow first download: the fallback already started listening
                    main.post(once);
                } catch (Exception e) {
                    main.post(() -> google(app, p, once, fired));
                }
            }, "jarvis-greet").start();
        } else {
            google(app, p, once, fired);
        }
    }

    private static void download(String key, String voice, String text, File out) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", "gpt-4o-mini-tts")
                .put("voice", voice)
                .put("input", text)
                .put("instructions", "Calm, warm, attentive British-butler tone, like JARVIS answering his master. Telugu pronounced naturally. Short and quick.")
                .put("response_format", "pcm");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            if (c.getResponseCode() >= 400) throw new IllegalStateException("tts " + c.getResponseCode());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            }
            if (buf.size() < 2000) throw new IllegalStateException("too short");
            File tmp = new File(out.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { buf.writeTo(o); }
            if (!tmp.renameTo(out)) throw new IllegalStateException("save failed");
        } finally {
            c.disconnect();
        }
    }

    private static void playPcm(File f, AtomicBoolean fired) throws Exception {
        byte[] pcm;
        try (InputStream in = new FileInputStream(f)) {
            pcm = new byte[(int) f.length() & ~1];
            int off = 0, n;
            while (off < pcm.length && (n = in.read(pcm, off, pcm.length - off)) > 0) off += n;
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(pcm.length, AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        try {
            t.write(pcm, 0, pcm.length);
            if (fired.get()) return;
            t.play();
            long ms = pcm.length / 2 * 1000L / RATE;
            long end = SystemClock.elapsedRealtime() + ms + 120;
            // Stop at once if the fallback opens the mic meanwhile.
            while (SystemClock.elapsedRealtime() < end && !fired.get()) SystemClock.sleep(20);
            t.stop();
        } finally {
            t.release();
        }
    }

    private static void google(Context app, Prefs p, Runnable done, AtomicBoolean fired) {
        if (fired.get()) return; // already listening: stay quiet
        if (tts != null) {
            speak(p, done, fired);
            return;
        }
        tts = new TextToSpeech(app, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                // Drop the broken engine so the next greeting tries again.
                TextToSpeech dead = tts;
                tts = null;
                if (dead != null) try { dead.shutdown(); } catch (Exception ignored) {}
                done.run();
                return;
            }
            tts.setLanguage(Locale.forLanguageTag("te-IN"));
            speak(p, done, fired);
        }));
    }

    private static void speak(Prefs p, Runnable done, AtomicBoolean fired) {
        if (fired.get()) return;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(done); }
            @Override public void onError(String id) { main.post(done); }
        });
        tts.setSpeechRate(Math.max(1.0f, p.speechRate()));
        if (tts.speak(text(p), TextToSpeech.QUEUE_FLUSH, null, "greet") != TextToSpeech.SUCCESS) main.post(done);
    }
}
