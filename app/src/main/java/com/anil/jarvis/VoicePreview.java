package com.anil.jarvis;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * "▶ విను" for a Gemini Live voice in Settings: one short Telugu line in that voice (Gemini's speech model, his Gemini
 * key; a few paise), so he can choose by ear. Each voice is made once and kept while Jarvis runs.
 */
final class VoicePreview {
    private VoicePreview() {}

    static final String MODEL = "gemini-3.8-flash-tts";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    interface Done {
        /** null: it played; otherwise why not (Telugu, with Gemini's words). */
        void done(String problem);
    }

    private static final Map<String, byte[]> made = new HashMap<>();
    private static final Map<String, Integer> rates = new HashMap<>();
    private static Handler main; // (made on first use)
    private static volatile AudioTrack playing;
    private static volatile int turn;

    /** Plays the voice's line (stops one that is playing). Done is called on the main thread. */
    static void play(Prefs p, String voice, Done done) {
        stop();
        final int my = ++turn;
        String key = p.geminiKey().replaceAll("[^\\x21-\\x7E]", "");
        if (key.isEmpty()) { done.done("Gemini key లేదు: సెట్టింగ్స్ → Jarvis మెదడు లో పెట్టండి."); return; }
        String name = p.name();
        new Thread(() -> {
            String problem = null;
            try {
                byte[] pcm;
                int rate;
                synchronized (made) {
                    pcm = made.get(voice);
                    rate = rates.containsKey(voice) ? rates.get(voice) : 24000;
                }
                if (pcm == null) {
                    JSONObject body = new JSONObject()
                            .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                    .put("text", "Say warmly, in natural Telugu: నమస్కారం " + name + ", నేను Jarvis. ఈ గొంతు మీకు నచ్చిందా?")))))
                            .put("generationConfig", new JSONObject()
                                    .put("responseModalities", new JSONArray().put("AUDIO"))
                                    .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                            .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))));
                    JSONObject r = Http.post(URL, body, "x-goog-api-key", key);
                    JSONObject data = null;
                    JSONArray cands = r.optJSONArray("candidates");
                    JSONObject content = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0).optJSONObject("content");
                    JSONArray parts = content == null ? null : content.optJSONArray("parts");
                    for (int i = 0; parts != null && i < parts.length() && data == null; i++) data = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (data == null) throw new IllegalStateException("Gemini sent no audio");
                    pcm = android.util.Base64.decode(data.optString("data"), android.util.Base64.DEFAULT);
                    rate = rateOf(data.optString("mimeType"));
                    synchronized (made) { made.put(voice, pcm); rates.put(voice, rate); }
                }
                if (my != turn) return; // another voice was tapped meanwhile
                playPcm(pcm, rate, my);
            } catch (Http.ApiError e) {
                problem = "Gemini గొంతు వినిపించలేదు (" + MODEL + "): " + e.status + " " + e.getMessage();
            } catch (Exception e) {
                problem = "గొంతు వినిపించలేదు: " + e.getMessage();
            }
            final String pr = problem;
            if (my == turn) handler().post(() -> done.done(pr));
        }, "jarvis-voice-preview").start();
    }

    private static synchronized Handler handler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** "audio/L16;codec=pcm;rate=24000" → 24000. */
    static int rateOf(String mime) {
        try {
            int i = mime == null ? -1 : mime.indexOf("rate=");
            if (i >= 0) {
                String s = mime.substring(i + 5).replaceAll("[^0-9].*$", "");
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignored) {}
        return 24000;
    }

    private static void playPcm(byte[] pcm, int rate, int my) throws InterruptedException {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, 9600))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        playing = t;
        try {
            t.play();
            for (int off = 0; off < pcm.length && my == turn; off += 4800) {
                int len = Math.min(4800, pcm.length - off);
                if (t.write(pcm, off, len) < 0) break;
            }
            long frames = pcm.length / 2;
            long until = System.currentTimeMillis() + frames * 1000 / rate + 1500;
            while (my == turn && t.getPlaybackHeadPosition() < frames && System.currentTimeMillis() < until) Thread.sleep(50);
        } finally {
            if (playing == t) playing = null;
            try { t.pause(); t.flush(); } catch (Exception ignored) {}
            t.release();
        }
    }

    /** Stops a line that is playing (and one still being made). */
    static void stop() {
        turn++;
        AudioTrack t = playing;
        if (t != null) try { t.pause(); t.flush(); } catch (Exception ignored) {}
    }
}
