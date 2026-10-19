package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis's voice from Google Gemini or Sarvam Bulbul, when he chooses one of them in Settings → సహజ గొంతు ("Jarvis గొంతు
 * ఎవరిది"); OpenAI stays NaturalVoice's own way, untouched. The words go in short pieces (the first one small, so he
 * hears it soon, the next asked for while that one plays); each comes back whole and is turned into 24 kHz mono 16-bit
 * sound for NaturalVoice's speaker path (⏸/▶, the talk-over echo removal, the face's lips). It never switches to another
 * company by itself: if the chosen one fails before any sound, the phone's own voice speaks and Jarvis చెక్ says why.
 */
final class CloudVoice {
    static final String OPENAI = "openai", GEMINI = "gemini", SARVAM = "sarvam";

    static final String GEMINI_DEFAULT_MODEL = "gemini-3.8-flash-lite-tts";
    static final String GEMINI_DEFAULT_VOICE = "Fenrir";
    static final String SARVAM_DEFAULT_MODEL = "bulbul:v3";
    /** Sarvam's speaking models, newest first (they have no list to ask for: this is the list in their documents). */
    static final String[][] SARVAM_MODELS = {
            {"bulbul:v3", "Bulbul v3 · 30+ గొంతులు, తెలుగు బాగా (సిఫార్సు)"},
            {"bulbul:v4-flash", "Bulbul v4 Flash · అన్నింటికంటే వేగం, తెలుగుకి 4 గొంతులు"},
            {"bulbul:v2", "Bulbul v2 · పాతది, 7 గొంతులు"}};
    /** Each model's voices: {name, మగ / ఆడ}. */
    static final String[][] SARVAM_V3 = {
            {"shubh", "మగ"}, {"tarun", "మగ"}, {"aditya", "మగ"}, {"rahul", "మగ"}, {"rohan", "మగ"}, {"amit", "మగ"}, {"dev", "మగ"},
            {"ratan", "మగ"}, {"varun", "మగ"}, {"manan", "మగ"}, {"sumit", "మగ"}, {"kabir", "మగ"}, {"aayan", "మగ"}, {"ashutosh", "మగ"},
            {"advait", "మగ"}, {"anand", "మగ"}, {"sunny", "మగ"}, {"mani", "మగ"}, {"gokul", "మగ"}, {"vijay", "మగ"}, {"mohit", "మగ"},
            {"rehan", "మగ"}, {"soham", "మగ"},
            {"ritu", "ఆడ"}, {"priya", "ఆడ"}, {"neha", "ఆడ"}, {"pooja", "ఆడ"}, {"simran", "ఆడ"}, {"kavya", "ఆడ"}, {"ishita", "ఆడ"},
            {"shreya", "ఆడ"}, {"roopa", "ఆడ"}, {"tanya", "ఆడ"}, {"shruti", "ఆడ"}, {"suhani", "ఆడ"}, {"kavitha", "ఆడ"}, {"rupali", "ఆడ"}};
    static final String[][] SARVAM_V4 = {
            {"tarun_te_narration", "మగ"}, {"kavitha_te_narration", "ఆడ"}, {"kavitha_te_conversation", "ఆడ"}, {"pooja_te_conversation", "ఆడ"}};
    static final String[][] SARVAM_V2 = {
            {"abhilash", "మగ"}, {"karun", "మగ"}, {"hitesh", "మగ"}, {"anushka", "ఆడ"}, {"manisha", "ఆడ"}, {"vidya", "ఆడ"}, {"arya", "ఆడ"}};

    /** The first piece is about a sentence, so the voice starts soon; later pieces are longer (fewer requests). */
    static final int FIRST_PIECE = 160, PIECE = 350;

    final String provider, key, model, voice;

    CloudVoice(String provider, String key, String model, String voice) {
        this.provider = provider;
        this.key = key == null ? "" : key.replaceAll("[^\\x21-\\x7E]", ""); // (a pasted key can bring spaces or invisible marks)
        this.model = model;
        this.voice = voice;
    }

    /** His choice in Settings, or null for OpenAI (NaturalVoice's own way). */
    static CloudVoice of(Prefs p) {
        String who = p.voiceProvider();
        if (GEMINI.equals(who)) return new CloudVoice(GEMINI, p.geminiKey(), p.geminiTtsModel(), p.geminiTtsVoice());
        if (SARVAM.equals(who)) {
            String m = p.sarvamTtsModel();
            return new CloudVoice(SARVAM, p.sarvamKey(), m, sarvamVoiceFor(m, p.sarvamVoice()));
        }
        return null;
    }

    boolean ready() { return !key.isEmpty(); }

    /** "Google Gemini · gemini-3.8-flash-lite-tts · Fenrir" (for Jarvis చెక్ and the test button). */
    String label() { return company(provider) + " · " + model + " · " + voice; }

    static String company(String provider) {
        return GEMINI.equals(provider) ? "Google Gemini" : SARVAM.equals(provider) ? "Sarvam Bulbul" : "OpenAI";
    }

    /** No key for the chosen company, in plain words. */
    String noKey() {
        return GEMINI.equals(provider) ? "Gemini key లేదు (సెట్టింగ్స్ → 🧠 మెదడు, ఖర్చు → Jarvis మెదడు లో పెట్టండి)"
                : "Sarvam key లేదు (సెట్టింగ్స్ → 🧠 మెదడు, ఖర్చు → Jarvis మెదడు → Sarvam API key)";
    }

    // ---------------------------------------------------------------- Sarvam's voices per model

    static String[][] sarvamVoices(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("v4")) return SARVAM_V4;
        if (m.contains("v2")) return SARVAM_V2;
        return SARVAM_V3;
    }

    /** The voice he chose if this model has it, else the model's first (male) voice. */
    static String sarvamVoiceFor(String model, String wanted) {
        String[][] list = sarvamVoices(model);
        for (String[] v : list) if (v[0].equals(wanted)) return wanted;
        return list[0][0];
    }

    /** Sarvam's language for this text ("te-IN"...); Telugu when unsure. */
    static String sarvamLang(String text) {
        String tag = Lang.of(text).toLanguageTag();
        switch (tag) {
            case "hi-IN": case "ta-IN": case "kn-IN": case "ml-IN": case "en-IN": return tag;
            default: return "te-IN";
        }
    }

    // ---------------------------------------------------------------- the pieces

    /**
     * The text in pieces at sentence ends (a long sentence at a comma or space): the first up to firstMax letters, the rest
     * up to max. Nothing is lost or reordered; empty pieces are dropped.
     */
    static List<String> pieces(String text, int firstMax, int max) {
        List<String> out = new ArrayList<>();
        String s = text == null ? "" : text.trim();
        while (!s.isEmpty()) {
            int limit = out.isEmpty() ? firstMax : max;
            if (s.length() <= limit) { out.add(s); break; }
            int cut = lastEnd(s, limit);
            if (cut <= 0) cut = lastIn(s, limit, ",;:،");
            if (cut <= 0) cut = lastIn(s, limit, " \n");
            if (cut <= 0) cut = limit;
            String piece = s.substring(0, cut).trim();
            if (!piece.isEmpty()) out.add(piece);
            s = s.substring(cut).trim();
        }
        return out;
    }

    /** Just after the last sentence end (. ? ! । or a line break) at or before limit, or -1. */
    private static int lastEnd(String s, int limit) {
        for (int i = Math.min(limit, s.length()) - 1; i > 0; i--) {
            char c = s.charAt(i);
            if (c == '\n' || c == '।') return i + 1;
            if ((c == '.' || c == '?' || c == '!') && (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)))) return i + 1;
        }
        return -1;
    }

    private static int lastIn(String s, int limit, String marks) {
        for (int i = Math.min(limit, s.length()) - 1; i > 0; i--) if (marks.indexOf(s.charAt(i)) >= 0) return i + 1;
        return -1;
    }

    // ---------------------------------------------------------------- asking

    /** One piece as 24 kHz mono 16-bit little-endian sound. Thrown errors carry the company's own words. */
    byte[] say(String piece) throws Exception {
        return GEMINI.equals(provider) ? gemini(piece) : sarvam(piece);
    }

    private byte[] gemini(String piece) throws Exception {
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", piece)))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))));
        JSONObject r = post("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", body, "x-goog-api-key", key);
        JSONArray cands = r.optJSONArray("candidates");
        JSONObject content = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        JSONObject data = null;
        for (int i = 0; parts != null && i < parts.length() && data == null; i++) data = parts.getJSONObject(i).optJSONObject("inlineData");
        if (data == null) {
            String why = cands != null && cands.length() > 0 ? cands.getJSONObject(0).optString("finishReason", "") : "";
            throw new IllegalStateException("Gemini గొంతు రాలేదు" + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        byte[] raw = java.util.Base64.getDecoder().decode(data.optString("data").replaceAll("\\s", ""));
        return to24k(raw, data.optString("mimeType"));
    }

    private byte[] sarvam(String piece) throws Exception {
        JSONObject body = new JSONObject()
                .put("text", piece)
                .put("language_code", sarvamLang(piece))
                .put("speaker", voice)
                .put("model", model)
                .put("speech_sample_rate", 24000);
        JSONObject r = post("https://api.sarvam.ai/text-to-speech", body, "api-subscription-key", key);
        JSONArray audios = r.optJSONArray("audios");
        if (audios == null || audios.length() == 0) throw new IllegalStateException("Sarvam గొంతు రాలేదు");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = 0; i < audios.length(); i++) {
            byte[] pcm = to24k(java.util.Base64.getDecoder().decode(audios.getString(i).replaceAll("\\s", "")), "audio/wav");
            all.write(pcm, 0, pcm.length);
        }
        return all.toByteArray();
    }

    /** The request on its way now (stop() cuts it, so a stopped reply isn't paid for or waited on). */
    private volatile HttpURLConnection live;
    private volatile boolean cancelled;

    /** Stops the request on its way (any thread); nothing more is asked for. */
    void cancel() {
        cancelled = true;
        final HttpURLConnection c = live;
        // (off the calling thread: on the main thread closing the line may be refused as network work, and silently not happen)
        if (c != null) new Thread(() -> { try { c.disconnect(); } catch (Throwable ignored) {} }, "jarvis-voice-cut").start();
    }

    /** Worth one more try: busy (429), the company's server (5xx), or the line (timeout, reset). Never a wrong key / request. */
    static boolean retryable(Throwable e) {
        if (e instanceof Http.ApiError) { int st = ((Http.ApiError) e).status; return st == 429 || st >= 500; }
        return e instanceof java.io.IOException;
    }

    /** JSON over HTTPS with the voice's own times: the same as OpenAI's voice (15 s to connect, 20 s for the sound). */
    private JSONObject post(String url, JSONObject body, String headerName, String headerValue) throws Exception {
        if (cancelled) throw new java.io.InterruptedIOException("stopped");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        live = c;
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty(headerName, headerValue);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            c.connect();
            if (cancelled) throw new java.io.InterruptedIOException("stopped"); // (stopped before the line was open)
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
            }
            String text = b.toString("UTF-8");
            if (status >= 400) throw new Http.ApiError(status, shortError(text));
            return new JSONObject(text);
        } finally {
            live = null;
            c.disconnect();
        }
    }

    /** The company's error message, short (never the whole reply). */
    static String shortError(String text) {
        String msg = text == null ? "" : text;
        try {
            JSONObject j = new JSONObject(msg);
            Object e = j.opt("error");
            if (e instanceof JSONObject) msg = ((JSONObject) e).optString("message", msg);
            else if (e instanceof String) msg = (String) e;
            else if (j.has("message")) msg = j.optString("message");
        } catch (Exception ignored) {}
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 180 ? msg.substring(0, 180) + "…" : msg;
    }

    // ---------------------------------------------------------------- the sound

    /**
     * Sound as it came (a WAV file, or headerless 16-bit sound with its rate in the type, like "audio/L16;rate=24000")
     * turned into 24 kHz mono 16-bit little-endian.
     */
    static byte[] to24k(byte[] raw, String mime) {
        if (raw == null || raw.length == 0) return new byte[0];
        int rate = 24000, channels = 1, bits = 16, from = 0, len = raw.length;
        if (raw.length >= 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F' && raw[8] == 'W' && raw[9] == 'A') {
            int pos = 12;
            boolean found = false;
            while (pos + 8 <= raw.length) {
                String id = new String(raw, pos, 4, StandardCharsets.US_ASCII);
                long size = le32(raw, pos + 4);
                int body = pos + 8;
                if (id.equals("fmt ") && body + 16 <= raw.length) {
                    channels = Math.max(1, le16(raw, body + 2));
                    rate = (int) le32(raw, body + 4);
                    bits = le16(raw, body + 14);
                } else if (id.equals("data")) {
                    from = body;
                    // (a streamed file says "to the end" with a huge size, or with 0)
                    len = (int) (size <= 0 ? raw.length - body : Math.min(size, raw.length - body));
                    found = true;
                    break;
                }
                if (size < 0 || size > raw.length) break;
                pos = body + (int) size + (int) (size & 1);
            }
            if (!found) { from = Math.min(44, raw.length); len = raw.length - from; }
        } else {
            rate = rateOf(mime);
        }
        if (bits != 16) throw new IllegalStateException("గొంతు " + bits + "-bit లో వచ్చింది (16-bit కావాలి)");
        int frames = len / (2 * channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int at = from + (i * channels + ch) * 2;
                sum += (short) ((raw[at] & 0xFF) | (raw[at + 1] << 8));
            }
            mono[i] = (short) (sum / channels);
        }
        short[] out = rate == 24000 || rate <= 0 ? mono : resample(mono, rate, 24000);
        byte[] b = new byte[out.length * 2];
        for (int i = 0; i < out.length; i++) { b[2 * i] = (byte) (out[i] & 0xFF); b[2 * i + 1] = (byte) ((out[i] >> 8) & 0xFF); }
        return b;
    }

    /** "audio/L16;codec=pcm;rate=24000" → 24000 (24000 when it doesn't say). */
    static int rateOf(String mime) {
        try {
            int i = mime == null ? -1 : mime.toLowerCase(Locale.ROOT).indexOf("rate=");
            if (i >= 0) {
                String s = mime.substring(i + 5).replaceAll("[^0-9].*$", "");
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignored) {}
        return 24000;
    }

    /** Straight-line resampling (speech only: good enough, and never a gap or a click at the ends). */
    static short[] resample(short[] in, int fromRate, int toRate) {
        if (in.length == 0 || fromRate == toRate) return in;
        int n = (int) ((long) in.length * toRate / fromRate);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double src = (double) i * fromRate / toRate;
            int a = (int) src;
            double f = src - a;
            int b = Math.min(a + 1, in.length - 1);
            out[i] = (short) Math.round(in[Math.min(a, in.length - 1)] * (1 - f) + in[b] * f);
        }
        return out;
    }

    private static int le16(byte[] b, int at) { return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8); }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    static boolean known(String[][] list, String name) {
        return Arrays.stream(list).anyMatch(v -> v[0].equals(name));
    }
}
