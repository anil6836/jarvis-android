package com.anil.jarvis.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The watch's line to the phone's Jarvis: messages over the watch's Bluetooth link (Wear Data Layer). Paths and their
 * meaning are the same as in the phone app's WatchHub. Also his settings, which the phone sends (Settings → ⌚ వాచ్).
 */
final class Link {
    private Link() {}

    // watch -> phone
    static final String P_HELLO = "/jarvis/hello", P_MIC_START = "/jarvis/mic/start", P_MIC_DATA = "/jarvis/mic/data",
            P_MIC_END = "/jarvis/mic/end", P_TEXT = "/jarvis/text", P_STOP = "/jarvis/stop", P_PLAYED = "/jarvis/played",
            P_CONFIRM_ANSWER = "/jarvis/confirm/answer", P_DONE = "/jarvis/done", P_BEAT = "/jarvis/beat",
            P_ALERT_ACTION = "/jarvis/alert/action", P_ALARM_ANSWER = "/jarvis/alarm/answer",
            P_ASK = "/jarvis/ask", P_DO = "/jarvis/do", P_HEALTH = "/jarvis/health";
    // phone -> watch
    static final String P_SETTINGS = "/jarvis/settings", P_STATE = "/jarvis/state", P_MIC_STOP = "/jarvis/mic/stop",
            P_AUDIO_START = "/jarvis/audio/start", P_AUDIO_DATA = "/jarvis/audio/data", P_AUDIO_END = "/jarvis/audio/end",
            P_LISTEN = "/jarvis/listen", P_CONFIRM = "/jarvis/confirm", P_CONFIRM_DONE = "/jarvis/confirm/done",
            P_PING = "/jarvis/ping", P_ALERT = "/jarvis/alert", P_ALERT_GONE = "/jarvis/alert/gone", P_ALARM = "/jarvis/alarm",
            P_INFO = "/jarvis/info", P_ALARM_STOP = "/jarvis/alarm/stop", P_PANEL = "/jarvis/panel", P_TIMER = "/jarvis/timer",
            P_OPEN = "/jarvis/open", P_BUZZ = "/jarvis/buzz", // phase 5: open a screen here; a turn while walking
            P_RADIO = "/jarvis/radio", P_FALL_END = "/jarvis/fall/end", P_PHOTO = "/jarvis/photo",
            P_FIND = "/jarvis/find", P_SMART = "/jarvis/smart", P_REC_OK = "/jarvis/rec/ok";

    private static volatile String phone;
    private static volatile long phoneAt;
    /** The phone last wrote (elapsedRealtime), 0 never. */
    static volatile long heardAt;
    /** The last send worked (false: the phone wasn't reachable). */
    static volatile boolean ok = true;
    static volatile String lastError = "";
    private static final ExecutorService out = Executors.newSingleThreadExecutor();
    /** SOS, fall and crash messages: never behind a long upload (a recording, a photo). */
    private static final ExecutorService fast = Executors.newSingleThreadExecutor();

    static void heard(String node) {
        phone = node;
        phoneAt = heardAt = SystemClock.elapsedRealtime();
    }

    static void send(Context c, String path, JSONObject o) { send(c, path, o.toString().getBytes(StandardCharsets.UTF_8), null); }

    /** failed: runs on the main thread when the phone couldn't be reached. */
    static void send(Context c, String path, JSONObject o, Runnable failed) { send(c, path, o.toString().getBytes(StandardCharsets.UTF_8), failed); }

    static void send(Context c, String path, byte[] data) { send(c, path, data, null); }

    /** At once, ahead of everything waiting (SOS, a fall, a knock); failed runs on the main thread. */
    static void urgent(Context c, String path, JSONObject o, Runnable failed) {
        send(fast, c, path, o.toString().getBytes(StandardCharsets.UTF_8), failed);
    }

    /** In order, one at a time, off the main thread. */
    static void send(Context c, String path, byte[] data, Runnable failed) { send(out, c, path, data, failed); }

    private static void send(ExecutorService q, Context c, String path, byte[] data, Runnable failed) {
        final Context app = c.getApplicationContext();
        q.execute(() -> {
            try {
                String n = phoneId(app);
                if (n == null) {
                    ok = false;
                    lastError = "ఫోన్ కనెక్ట్ అయి లేదు";
                    if (failed != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(failed);
                    return;
                }
                Tasks.await(Wearable.getMessageClient(app).sendMessage(n, path, data), 4, TimeUnit.SECONDS);
                ok = true;
            } catch (Exception e) {
                phone = null;
                ok = false;
                lastError = "ఫోన్‌కి పంపలేకపోయాను: " + e.getMessage();
                if (failed != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(failed);
            }
        });
    }

    /** Waits (background thread) until what was sent so far has gone, or up to ms: before the watch may sleep again. False = not all gone yet. */
    static boolean flush(long ms) {
        try { out.submit(() -> {}).get(ms, TimeUnit.MILLISECONDS); return true; } catch (Exception e) { return false; }
    }

    /** True when a phone is connected right now (background thread; waits up to 3 s). */
    static boolean phoneThere(Context c) {
        try { return phoneId(c.getApplicationContext()) != null; } catch (Exception e) { return false; }
    }

    /** The phone is near (over Bluetooth, not through the internet) right now (background thread). */
    static boolean phoneNear(Context c) {
        try {
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(c.getApplicationContext()).getConnectedNodes(), 3, TimeUnit.SECONDS);
            for (Node x : nodes) if (x.isNearby()) return true;
            return false;
        } catch (Exception e) {
            return true; // (couldn't tell: never a false "phone forgotten")
        }
    }

    private static String phoneId(Context app) throws Exception {
        String n = phone;
        if (n != null && SystemClock.elapsedRealtime() - phoneAt < 10 * 60_000L) return n;
        List<Node> nodes = Tasks.await(Wearable.getNodeClient(app).getConnectedNodes(), 3, TimeUnit.SECONDS);
        Node pick = null;
        for (Node x : nodes) if (x.isNearby()) { pick = x; break; }
        if (pick == null && !nodes.isEmpty()) pick = nodes.get(0);
        if (pick == null) return null;
        phone = pick.getId();
        phoneAt = SystemClock.elapsedRealtime();
        return phone;
    }

    // ---------------------------------------------------------------- his settings (sent by the phone)

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch", Context.MODE_PRIVATE); }

    static JSONObject cfg(Context c) {
        try { return new JSONObject(sp(c).getString("cfg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void saveCfg(Context c, JSONObject o) { sp(c).edit().putString("cfg", o.toString()).apply(); }

    static boolean raise(Context c) { return cfg(c).optBoolean("raise", true); }
    static boolean hours(Context c) { return cfg(c).optBoolean("hours", false); }
    static int from(Context c) { return cfg(c).optInt("from", 7); }
    static int to(Context c) { return cfg(c).optInt("to", 9); }
    /** "openai" / "gemini" (the phone writes his words out), "watch" (this watch's own voice typing); "" not known yet. */
    static String ears(Context c) { return cfg(c).optString("ears", ""); }
    static boolean speak(Context c) { return cfg(c).optBoolean("speak", true); }
    static boolean stopVoice(Context c) { return cfg(c).optBoolean("stopVoice", true); }
    static boolean openListen(Context c) { return cfg(c).optBoolean("openListen", true); }
    static String lang(Context c) { return cfg(c).optString("lang", "te-IN"); }
    static String name(Context c) { return cfg(c).optString("name", "Anil"); }
    /** W15: Jarvis's alerts shown here (with their own vibration). */
    static boolean alerts(Context c) { return cfg(c).optBoolean("alerts", true); }
    /** W20: tell him when he walks away from the phone. */
    static boolean lost(Context c) { return cfg(c).optBoolean("lost", true); }
    /** W31: after each walk, its steps and metres / km (and a word at each km). */
    static boolean walk(Context c) { return cfg(c).optBoolean("walk", true); }
    /** Metres a step, from his height on the phone (0.72 when not known). */
    static double stride(Context c) {
        double v = cfg(c).optDouble("stride", WalkCoach.STRIDE);
        return v >= 0.5 && v <= 0.95 ? v : WalkCoach.STRIDE;
    }
    /** W42: feel a hard fall here and ask "బాగున్నారా?". */
    static boolean fall(Context c) { return cfg(c).optBoolean("fall", true); }
    /** W46 / W26 / W43: the heart rate about every 15 minutes while he sits (his normal is learnt on the phone). */
    static boolean hr(Context c) { return cfg(c).optBoolean("hr", true); }
    /** W3: "orb", "holo" or "human". */
    static String look(Context c) { return cfg(c).optString("look", "orb"); }
    /** W5: the phone's theme: "mix", "blue" or "gold". */
    static String theme(Context c) { return cfg(c).optString("theme", info(c).optString("theme", "mix")); }

    // ---------------------------------------------------------------- the phone's news (duty, weather, where it is)

    static JSONObject info(Context c) {
        try { return new JSONObject(sp(c).getString("info", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void saveInfo(Context c, JSONObject o) {
        try { o.put("got", System.currentTimeMillis()); } catch (Exception ignored) {}
        sp(c).edit().putString("info", o.toString()).apply();
    }

    /** Now inside his always-listening hours (from..to, across midnight too). */
    static boolean inHours(Context c) {
        if (!hours(c)) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), f = from(c), t = to(c);
        return f < t ? h >= f && h < t : h >= f || h < t;
    }
}
