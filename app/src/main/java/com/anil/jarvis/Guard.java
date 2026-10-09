package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "కాపలా మోడ్": an old phone at home with Jarvis on it watches through its camera while Anil is away on duty.
 * When something moves, Jarvis looks at the picture (a person, an animal, a vehicle?) and sends the photo with a
 * line in Telugu to his own Telegram bot, which his main phone gets as a Telegram message. The bot's token and his
 * chat id stay on that phone (never shown, logged or shared).
 */
final class Guard {
    private Guard() {}

    static final String ACTION_STOP = "com.anil.jarvis.GUARD_STOP";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_guard", Context.MODE_PRIVATE); }

    static String token(Context c) { return sp(c).getString("tg_token", "").trim(); }

    static String chat(Context c) { return sp(c).getString("tg_chat", "").trim(); }

    static boolean running(Context c) { return sp(c).getBoolean("on", false); }

    /** The camera gave a picture in the last 2 minutes: the guard is really watching (not just switched on). */
    static boolean watching(Context c) { return running(c) && System.currentTimeMillis() - sp(c).getLong("beat", 0) < 120_000L; }

    /** Only while he is on duty (by this phone's duty calendar, when it has one). */
    static boolean dutyOnly(Context c) { return sp(c).getBoolean("duty_only", false); }

    /** From the regular check on the phone at home: the guard is on but its camera stopped -> tell him once. */
    static void watch(Context c) {
        if (!running(c)) return;
        long beat = sp(c).getLong("beat", 0), told = sp(c).getLong("told_stop", 0);
        if (System.currentTimeMillis() - beat < 5 * 60_000L) { if (told != 0) sp(c).edit().putLong("told_stop", 0).apply(); return; }
        if (told != 0) return;
        sp(c).edit().putLong("told_stop", System.currentTimeMillis()).apply();
        Reminders.notify(c, "🛡️ కాపలా ఆగిపోయింది", "కెమెరా పనిచేయడం లేదు. Jarvis తెరిచి సెట్టింగ్స్ → కాపలా మోడ్ లో మళ్ళీ మొదలుపెట్టండి.", 273);
        send(c, "⚠️ Jarvis కాపలా ఆగిపోయింది: ఇంట్లో ఫోన్ కెమెరా పనిచేయడం లేదు. ఆ ఫోన్‌లో మళ్ళీ మొదలుపెట్టండి.");
    }

    /** A new bot token: the old chat no longer belongs to it. */
    static void setToken(Context c, String token) {
        String t = token == null ? "" : token.trim();
        if (!t.equals(token(c))) sp(c).edit().putString("tg_token", t).remove("tg_chat").remove("tg_name").remove("tg_bot_name").apply();
    }

    static void start(Context c) {
        sp(c).edit().putBoolean("on", true).apply();
        Intent i = new Intent(c, GuardService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        listen(c);
    }

    static void stop(Context c) {
        sp(c).edit().putBoolean("on", false).apply();
        c.stopService(new Intent(c, GuardService.class));
        // the house sounds stop too: the mic rests again, or goes away if the wake word isn't on on this phone
        if (!new Prefs(c).wakeReady() && !Sounds.holdMic(c)) WakeService.stop(c); else WakeService.recheck(c);
    }

    /** House sounds (HomeGuard): the microphone (Jarvis's wake-word listener) runs while the guard does. From a screen. */
    static void listen(Context c) {
        HomeGuard.refresh();
        if (!HomeGuard.listening(c)) return;
        if (WakeService.running) WakeService.recheck(c);
        else WakeService.start(c, false);
    }

    /** His private chat with the bot, from the last message sent to it ("hi"): the name on it, or an error text starting with "!". */
    static String findChat(Context c) {
        String t = token(c);
        if (t.isEmpty()) return "!Bot token పెట్టలేదు";
        try {
            JSONObject r = new JSONObject(get("https://api.telegram.org/bot" + t + "/getUpdates"));
            if (!r.optBoolean("ok")) return "!Token సరిగ్గా లేదు";
            JSONArray a = r.optJSONArray("result");
            for (int i = a == null ? -1 : a.length() - 1; i >= 0; i--) {
                JSONObject m = a.getJSONObject(i).optJSONObject("message");
                JSONObject ch = m == null ? null : m.optJSONObject("chat");
                if (ch == null || !"private".equals(ch.optString("type"))) continue; // never a group
                String name = (ch.optString("first_name") + " " + ch.optString("last_name")).trim();
                sp(c).edit().putString("tg_chat", String.valueOf(ch.optLong("id"))).putString("tg_name", name).apply();
                botName(c); // (W41: his main phone knows the bot's Telegram notifications by its name)
                return name.isEmpty() ? "మీ చాట్" : name;
            }
            return "!Telegram లో మీ bot కి ఒక మెసేజ్ (hi) పంపి మళ్ళీ నొక్కండి";
        } catch (Exception e) {
            return "!ఇంటర్నెట్ / Telegram చేరలేదు";
        }
    }

    /** He wrote anything to the bot since this time (Unix seconds): "I saw it" for a repeating alert. False when unknown. */
    static boolean repliedSince(Context c, long sinceSec) {
        String t = token(c), id = chat(c);
        if (t.isEmpty() || id.isEmpty()) return false;
        try {
            JSONObject r = new JSONObject(get("https://api.telegram.org/bot" + t + "/getUpdates"));
            JSONArray a = r.optJSONArray("result");
            for (int i = a == null ? -1 : a.length() - 1; i >= 0; i--) {
                JSONObject m = a.getJSONObject(i).optJSONObject("message");
                JSONObject ch = m == null ? null : m.optJSONObject("chat");
                if (ch != null && id.equals(String.valueOf(ch.optLong("id"))) && m.optLong("date") >= sinceSec) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** A text to his Telegram; false when it did not go. */
    static boolean send(Context c, String text) {
        String t = token(c), id = chat(c);
        if (t.isEmpty() || id.isEmpty()) return false;
        try {
            JSONObject body = new JSONObject().put("chat_id", id).put("text", text);
            HttpURLConnection con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + t + "/sendMessage").openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = con.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            boolean ok = con.getResponseCode() == 200;
            con.disconnect();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** A photo with a caption to his Telegram (multipart upload); false when it did not go. Phase 5: pinned for his main phone. */
    static boolean sendPhoto(Context c, byte[] jpeg, String caption) {
        long id = sendFile(c, "sendPhoto", "photo", "guard.jpg", "image/jpeg", jpeg, caption, false);
        if (id < 0) id = sendFile(c, "sendPhoto", "photo", "guard.jpg", "image/jpeg", jpeg, caption, false); // one more try on a bad connection
        // (W41: his main phone's Jarvis finds the latest picture there; but a word from his main phone waiting to be read
        // here stays pinned until it is read)
        if (id > 0 && !HomeLink.commandWaiting(c)) pin(c, id);
        return id > 0;
    }

    /** A file (photo / audio) to his chat; its message id, or -1 when it did not go. */
    static long sendFile(Context c, String method, String field, String name, String type, byte[] data, String caption, boolean silent) {
        String t = token(c), id = chat(c);
        if (t.isEmpty() || id.isEmpty()) return -1;
        String b = "----jarvis" + System.currentTimeMillis();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + t + "/" + method).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(45000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            try (OutputStream o = con.getOutputStream()) {
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + id + "\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (silent) o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"disable_notification\"\r\n\r\ntrue\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(data);
                o.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            String body = read(con);
            JSONObject r = new JSONObject(body);
            return r.optBoolean("ok") ? r.getJSONObject("result").optLong("message_id", -1) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- phase 5: the home phone and his main phone (W41 / W64 / W65)

    /** A text to his chat, quietly (no sound on his phone); its message id or -1. */
    static long sendQuiet(Context c, String text) {
        try {
            JSONObject r = post(c, "sendMessage", new JSONObject().put("chat_id", chat(c)).put("text", text).put("disable_notification", true));
            return r != null && r.optBoolean("ok") ? r.getJSONObject("result").optLong("message_id", -1) : -1;
        } catch (Exception e) { return -1; }
    }

    /** Pins a message of the bot's in his chat, quietly: the two phones' mailbox (each reads the latest pinned one). */
    static boolean pin(Context c, long messageId) {
        try {
            JSONObject r = post(c, "pinChatMessage", new JSONObject().put("chat_id", chat(c)).put("message_id", messageId).put("disable_notification", true));
            return r != null && r.optBoolean("ok");
        } catch (Exception e) { return false; }
    }

    /** The latest pinned message in his chat with the bot, or null. */
    static JSONObject pinned(Context c) {
        try {
            JSONObject r = post(c, "getChat", new JSONObject().put("chat_id", chat(c)));
            return r == null || !r.optBoolean("ok") ? null : r.getJSONObject("result").optJSONObject("pinned_message");
        } catch (Exception e) { return null; }
    }

    /** A file the bot holds (a photo, a voice), downloaded; null when it can't. */
    static byte[] file(Context c, String fileId) {
        String t = token(c);
        try {
            JSONObject r = post(c, "getFile", new JSONObject().put("file_id", fileId));
            if (r == null || !r.optBoolean("ok")) return null;
            String path = r.getJSONObject("result").optString("file_path");
            HttpURLConnection con = (HttpURLConnection) new URL("https://api.telegram.org/file/bot" + t + "/" + path).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 20_000_000) out.write(buf, 0, n);
                return out.toByteArray();
            } finally { con.disconnect(); }
        } catch (Exception e) { return null; }
    }

    /** The bot's own name (to know its Telegram notifications on his main phone). */
    static String botName(Context c) {
        String n = sp(c).getString("tg_bot_name", "");
        if (!n.isEmpty() || token(c).isEmpty()) return n;
        try {
            JSONObject r = new JSONObject(get("https://api.telegram.org/bot" + token(c) + "/getMe"));
            n = r.optBoolean("ok") ? r.getJSONObject("result").optString("first_name") : "";
            if (!n.isEmpty()) sp(c).edit().putString("tg_bot_name", n).apply();
        } catch (Exception ignored) {}
        return n;
    }

    private static JSONObject post(Context c, String method, JSONObject body) throws Exception {
        String t = token(c);
        if (t.isEmpty() || chat(c).isEmpty()) return null;
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + t + "/" + method).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = con.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        return new JSONObject(read(con));
    }

    private static String read(HttpURLConnection con) throws Exception {
        try (InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        try (InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
