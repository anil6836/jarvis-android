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

    static void start(Context c) {
        sp(c).edit().putBoolean("on", true).apply();
        Intent i = new Intent(c, GuardService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        sp(c).edit().putBoolean("on", false).apply();
        c.stopService(new Intent(c, GuardService.class));
    }

    /** His chat with the bot, from the last message he sent it ("hi"): the chat id, or an error text starting with "!". */
    static String findChat(Context c) {
        String t = token(c);
        if (t.isEmpty()) return "!Bot token పెట్టలేదు";
        try {
            JSONObject r = new JSONObject(get("https://api.telegram.org/bot" + t + "/getUpdates"));
            if (!r.optBoolean("ok")) return "!Token సరిగ్గా లేదు";
            JSONArray a = r.optJSONArray("result");
            for (int i = a == null ? -1 : a.length() - 1; i >= 0; i--) {
                JSONObject m = a.getJSONObject(i).optJSONObject("message");
                if (m == null || m.optJSONObject("chat") == null) continue;
                String id = String.valueOf(m.getJSONObject("chat").optLong("id"));
                sp(c).edit().putString("tg_chat", id).apply();
                return id;
            }
            return "!Telegram లో మీ bot కి ఒక మెసేజ్ (hi) పంపి మళ్ళీ నొక్కండి";
        } catch (Exception e) {
            return "!ఇంటర్నెట్ / Telegram చేరలేదు";
        }
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

    /** A photo with a caption to his Telegram (multipart upload); false when it did not go. */
    static boolean sendPhoto(Context c, byte[] jpeg, String caption) {
        String t = token(c), id = chat(c);
        if (t.isEmpty() || id.isEmpty()) return false;
        String b = "----jarvis" + System.currentTimeMillis();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + t + "/sendPhoto").openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            try (OutputStream o = con.getOutputStream()) {
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + id + "\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption + "\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"guard.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(jpeg);
                o.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            boolean ok = con.getResponseCode() == 200;
            con.disconnect();
            return ok;
        } catch (Exception e) {
            return false;
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
