package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ⚡ F: "ఒక్క క్షణం, చూస్తున్నాను" while a phone job runs inside an answer (weather, messages, the duty roster...), so he
 * isn't left in silence. Said in the voice he chose, made once and kept on the phone, so it plays at once. Only the
 * chosen natural voice says it (never the phone's own voice instead); until it is made, nothing is said.
 */
final class Filler {
    static final String TEXT = "ఒక్క క్షణం, చూస్తున్నాను.";
    private static final int RATE = 24000;

    /** Phone jobs that make their own sound or screen, or are quick quiet notes: no "one moment" before them. */
    private static final Set<String> QUIET = new HashSet<>(Arrays.asList(
            "jarvis_mood", "save_memory", "forget_memory", "add_mission", "complete_mission", "play_youtube", "media_control",
            "local_media", "voice_mode", "call_contact", "call_control", "find_phone", "open_app", "close_app", "flashlight",
            "set_alarm", "set_timer", "stopwatch", "phone_setting", "night_mode", "driving_mode", "sos", "sounds", "song_alarm",
            "interpreter", "english_practice", "jarvis_camera", "look_through_camera", "read_screen", "voice_recorder", "send_draft",
            "more_tools"));

    private static final Object lock = new Object();
    private static AudioTrack track;
    private static volatile long until;      // (elapsedRealtime) when the one playing ends; 0: none
    private static volatile int token;       // each play / stop (under lock): an older play's thread lets go quietly
    private static volatile boolean making;  // the file is being made now

    private Filler() {}

    /** Worth a "one moment" before this phone job (not one that talks / opens something itself). */
    static boolean before(String tool) { return tool != null && !QUIET.contains(tool); }

    /** It is sounding now: the answer's voice waits for it to end (NaturalVoice), so it is never cut in the middle. */
    static boolean playing() { return SystemClock.elapsedRealtime() < until; }

    /** The kept sound for the voice he chose now; null when the natural voice isn't in use. */
    static File file(Context c, Prefs p) {
        if (!p.naturalVoice()) return null;
        CloudVoice cv = CloudVoice.of(p);
        String who;
        if (cv == null) {
            if (p.openAiKey().trim().isEmpty()) return null;
            String model = p.ttsModel() == null || p.ttsModel().trim().isEmpty() ? NaturalVoice.DEFAULT_MODEL : p.ttsModel().trim();
            who = "openai_" + p.naturalVoiceName() + "_" + Integer.toHexString(model.hashCode());
        } else {
            if (!cv.ready()) return null;
            who = cv.provider + "_" + cv.voice + "_" + Integer.toHexString(cv.model.hashCode());
        }
        return new File(c.getApplicationContext().getFilesDir(), "filler_" + who + "_" + Integer.toHexString(TEXT.hashCode()) + ".pcm");
    }

    /** Makes the sound in the background if it isn't on the phone yet (for this or the next phone job). */
    static void prepare(Context c, Prefs p) {
        if (!p.speedF()) return;
        final File f = file(c, p);
        if (f == null || f.exists() || making || !Net.online(c)) return;
        making = true;
        final CloudVoice cv = CloudVoice.of(p);
        final String key = p.openAiKey().trim(), voice = p.naturalVoiceName(), model = p.ttsModel();
        new Thread(() -> {
            try {
                byte[] pcm = cv == null ? openAi(key, voice, model) : cv.say(TEXT);
                pcm = trim(pcm);
                if (pcm.length < 4800) throw new IllegalStateException("too short");
                File tmp = new File(f.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(pcm); }
                if (!tmp.renameTo(f)) throw new IllegalStateException("save failed");
            } catch (Throwable ignored) {
                // (tried again with the next question; a phone job meanwhile is simply not announced)
            } finally {
                making = false;
            }
        }, "jarvis-filler-make").start();
    }

    /** Says it now when it is on the phone (else makes it for next time and stays quiet). Main thread. */
    static void play(Context c, Prefs p) {
        final File f = file(c, p);
        if (f == null) return;
        if (!f.exists()) { prepare(c, p); return; }
        MicQuiet.speaking(); // a sound muted for the mic's beeps comes back first (as before any of Jarvis's talk)
        final float gain = Whisper.gain();
        final int my;
        AudioTrack old;
        synchronized (lock) {
            my = ++token;
            old = track;
            until = SystemClock.elapsedRealtime() + 3000; // (set at once: an answer arriving now already waits for it)
        }
        if (old != null) try { old.pause(); } catch (Exception ignored) {}
        new Thread(() -> {
            AudioTrack t = null;
            try {
                byte[] pcm;
                try (InputStream in = new FileInputStream(f)) {
                    pcm = new byte[(int) f.length() & ~1];
                    int off = 0, n;
                    while (off < pcm.length && (n = in.read(pcm, off, pcm.length - off)) > 0) off += n;
                }
                if (pcm.length < 2) return;
                t = new AudioTrack.Builder()
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
                t.write(pcm, 0, pcm.length);
                long ms = pcm.length / 2 * 1000L / RATE;
                synchronized (lock) {
                    if (token != my) return; // stopped (or another one started) while it was being read
                    track = t;
                    try { t.setVolume(gain); } catch (Exception ignored) {}
                    t.play();
                    until = SystemClock.elapsedRealtime() + ms + 150;
                }
                while (playing() && token == my) SystemClock.sleep(20);
            } catch (Throwable ignored) {
            } finally {
                synchronized (lock) {
                    if (track == t) track = null;
                    if (token == my) until = 0;
                }
                if (t != null) {
                    try { t.stop(); } catch (Exception ignored) {}
                    try { t.release(); } catch (Exception ignored) {}
                }
            }
        }, "jarvis-filler").start();
    }

    /** Stops it at once (⏹, a listen starting, the phone's voice about to talk). Any thread. */
    static void stop() {
        AudioTrack t;
        synchronized (lock) {
            token++;
            until = 0;
            t = track;
        }
        if (t != null) try { t.pause(); } catch (Exception ignored) {}
    }

    private static byte[] openAi(String key, String voice, String model) throws Exception {
        String m = model == null || model.trim().isEmpty() ? NaturalVoice.DEFAULT_MODEL : model.trim();
        JSONObject body = new JSONObject().put("model", m).put("voice", voice).put("input", TEXT).put("response_format", "pcm");
        if (!m.toLowerCase(Locale.ROOT).startsWith("tts-1")) body.put("instructions", NaturalVoice.STYLE + " Short and quick.");
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
            try { Usage.tts(buf.size() / 2); } catch (Throwable ignored) {} // (the cost meter)
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** The silence before the first word and after the last one taken off (a little kept, so no word is clipped). */
    static byte[] trim(byte[] pcm) {
        int n = pcm.length / 2, first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            int x = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
            if (Math.abs(x) > 400) { if (first < 0) first = i; last = i; }
        }
        if (first < 0) return pcm;
        int from = Math.max(0, first - RATE / 40), to = Math.min(n, last + RATE / 10); // 25 ms before, 100 ms after
        return Arrays.copyOfRange(pcm, from * 2, to * 2);
    }
}
