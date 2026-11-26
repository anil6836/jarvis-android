package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 🌱 His plant-watering board (the JARVIS PLANT ESP32 board from the PCB studio): the key it shares with this phone, the
 * locked messages to it ({@link PlantLink}), what it says in Telugu, and the phone's look at its daily schedule. The
 * safety is on the board itself (2 minutes at most, never with an empty bucket, a daily total, stop when the Wi-Fi drops);
 * Jarvis asks him before every watering, except a schedule he set himself. The key stays on this phone (never in a backup,
 * never shown); it reaches the board only over the USB cable. The board's Wi-Fi password: he types it on the board's own
 * page, Jarvis never sees it.
 */
final class Plant {
    private Plant() {}

    static final String PREFS = "plant_private";
    static final String ACTION_CHECK = "com.anil.jarvis.PLANT_CHECK";
    /** The board program in assets/plant/board (plant.py's FW). */
    static final int FW = 1;
    /** The MicroPython put on the ESP32 (assets/plant/micropython.bin, fetched when the app is built): 1.29. */
    static final int MP_VER = 129;
    static final String[] FILES = {"boot.py", "main.py", "plant.py", "plantnet.py", "plantweb.py"};
    private static final int SOAK = 15;
    private static final SecureRandom RNG = new SecureRandom();

    /** The 🌱 screen listens for news from the board (a watering finished). */
    static volatile Runnable onChange;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** The board has the program and this phone's key. */
    static boolean paired(Context c) { return sp(c).getBoolean("ready", false) && key(c) != null; }

    static byte[] key(Context c) {
        String k = sp(c).getString("k", null);
        try { return k == null ? null : Base64.getDecoder().decode(k); } catch (Exception e) { return null; }
    }

    static String apName(Context c) { return "JARVIS-PLANT-" + sp(c).getString("id", "????"); }

    static String apPassword(Context c) { return sp(c).getString("ap", ""); }

    // ================================================================ USB: the files for the board

    /** The board's program and its config (this phone's key, the setup Wi-Fi's password, the board's id): made once, kept. */
    static LinkedHashMap<String, byte[]> files(Context c) throws IOException {
        SharedPreferences s = sp(c);
        if (key(c) == null) {
            s.edit().putString("k", Base64.getEncoder().encodeToString(PlantCrypto.newKey())).putString("ap", PlantCrypto.newApPassword())
                    .putString("id", PlantCrypto.newBoardId()).putBoolean("ready", false).commit();
        }
        LinkedHashMap<String, byte[]> f = new LinkedHashMap<>();
        for (String n : FILES) f.put(n, asset(c, "plant/board/" + n));
        try {
            JSONObject cfg = new JSONObject().put("k", s.getString("k", "")).put("ap", s.getString("ap", "")).put("id", s.getString("id", "")).put("v", 1);
            f.put("config.json", cfg.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException(e);
        }
        return f;
    }

    /** MicroPython for the ESP32, or null if this Jarvis was built without it. */
    static byte[] firmware(Context c) {
        try { return asset(c, "plant/micropython.bin"); } catch (IOException e) { return null; }
    }

    static void uploaded(Context c) {
        sp(c).edit().putBoolean("ready", true).putInt("fw", FW).putLong("uploaded", System.currentTimeMillis()).apply();
    }

    /** His "మర్చిపో": the key goes; the board keeps its own button and safety, but no phone can order it until set up again. */
    static void forget(Context c) {
        sp(c).edit().clear().commit();
        rearm(c);
    }

    private static byte[] asset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] t = new byte[65536];
            int n;
            while ((n = in.read(t)) > 0) o.write(t, 0, n);
            return o.toByteArray();
        }
    }

    // ================================================================ talking to the board (never on the main thread)

    /**
     * Sends one order and waits for the board's answer to it. {@code want}: the answer kinds ("st") that count (null = any).
     * Returns null when the board didn't answer in time.
     */
    static JSONObject ask(Context c, JSONObject cmd, String[] want, int ms) throws Exception {
        byte[] k = key(c);
        if (k == null) throw new IllegalStateException("not set up");
        String rid = rid();
        long now = System.currentTimeMillis() / 1000;
        cmd.put("t", now).put("id", rid);
        PlantLink L = new PlantLink(k);
        L.send(cmd.toString());
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(1500);
            for (PlantLink.Said s : L.poll(now - 3)) {
                JSONObject o = new JSONObject(s.json);
                if (!rid.equals(o.optString("re"))) continue;
                if (want != null && !java.util.Arrays.asList(want).contains(o.optString("st"))) continue;
                remember(c, o);
                return o;
            }
        }
        return null;
    }

    static JSONObject status(Context c) throws Exception {
        return ask(c, new JSONObject().put("c", "status"), null, 25000);
    }

    /**
     * Waters now (he said yes, or tapped). Returns the board's first answer ("start" with the seconds, or "no" with why);
     * when it started, a thread waits for "done" and tells him (a notification; said aloud unless he is on duty).
     */
    static JSONObject water(Context c, int secs) throws Exception {
        long since = System.currentTimeMillis() / 1000 - 3;
        JSONObject o = ask(c, new JSONObject().put("c", "water").put("s", secs), new String[]{"start", "no"}, 30000);
        if (o != null && "start".equals(o.optString("st"))) {
            final Context app = c.getApplicationContext();
            final String re = o.optString("re");
            final int n = o.optInt("s", secs);
            new Thread(() -> waitDone(app, re, n, since), "plant-done").start();
        }
        return o;
    }

    private static void waitDone(Context c, String re, int secs, long since) {
        JSONObject done = null;
        long end = SystemClock.elapsedRealtime() + (secs + SOAK + 60) * 1000L;
        try {
            PlantLink L = new PlantLink(key(c));
            Thread.sleep(Math.min(secs + SOAK, 140) * 1000L);
            while (done == null && SystemClock.elapsedRealtime() < end) {
                for (PlantLink.Said s : L.poll(since)) {
                    JSONObject o = new JSONObject(s.json);
                    if (re.equals(o.optString("re")) && "done".equals(o.optString("st"))) { done = o; break; }
                }
                if (done == null) Thread.sleep(4000);
            }
        } catch (Exception ignored) {}
        String text;
        if (done != null) {
            remember(c, done);
            text = doneText(done);
        } else {
            text = "బోర్డ్ నుంచి “అయిపోయింది” అని రాలేదు. పంప్ దానంతట అదే " + secs + " సెకన్లలో ఆగుతుంది; 🌱 స్క్రీన్‌లో చూడండి.";
        }
        tell(c, "🌱 మొక్కలకి నీళ్ళు", text, true);
        Runnable r = onChange;
        if (r != null) r.run();
    }

    static JSONObject stop(Context c) throws Exception {
        return ask(c, new JSONObject().put("c", "stop"), null, 25000);
    }

    static JSONObject settings(Context c, JSONObject set) throws Exception {
        JSONObject o = ask(c, new JSONObject().put("c", "set").put("set", set), null, 25000);
        if (o != null) rearm(c);
        return o;
    }

    static JSONObject calibrate(Context c, int sensor, boolean dry) throws Exception {
        return ask(c, new JSONObject().put("c", "cal").put("i", sensor).put("w", dry ? "dry" : "wet"), null, 25000);
    }

    /** The newest thing the board said (for the screen when the board is away, and the schedule check). */
    static void remember(Context c, JSONObject o) {
        try {
            JSONObject k = new JSONObject(o.toString());
            k.remove("re");
            sp(c).edit().putString("last", k.toString()).putLong("last_at", System.currentTimeMillis()).apply();
            JSONObject set = o.optJSONObject("set");
            if (set != null && set.has("sched")) sp(c).edit().putString("sched", set.optJSONArray("sched").toString()).apply();
        } catch (Exception ignored) {}
    }

    static JSONObject last(Context c) {
        try { return new JSONObject(sp(c).getString("last", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static String rid() {
        byte[] b = new byte[6];
        RNG.nextBytes(b);
        return EspFlash.hex(b);
    }

    // ================================================================ in Telugu

    static String soilLine(JSONObject o) {
        JSONArray m = o.optJSONArray("m");
        JSONObject set = o.optJSONObject("set");
        int dry = set == null ? 40 : set.optInt("dry", 40);
        if (m == null || m.length() == 0) return "మట్టి: తెలియదు";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < m.length(); i++) {
            if (s.length() > 0) s.append(" · ");
            if (m.length() > 1) s.append("కుండీ ").append(i + 1).append(": ");
            if (m.isNull(i)) s.append("సెన్సార్ చదవలేదు");
            else {
                int p = m.optInt(i);
                s.append(p).append("% ").append(p < dry ? "(పొడిగా ఉంది)" : "(తడిగా ఉంది)");
            }
        }
        return (m.length() > 1 ? "మట్టి తేమ — " : "మట్టి తేమ ") + s;
    }

    static String tankLine(JSONObject o) {
        switch (o.optString("tank")) {
            case "ok": return "బకెట్‌లో నీళ్ళు ఉన్నాయి";
            case "empty": return "⚠️ బకెట్ ఖాళీ: నీళ్ళు నింపాలి";
            case "none": return "బకెట్ సెన్సార్ పెట్టలేదు";
            default: return "బకెట్: తెలియదు";
        }
    }

    static String why(String w, JSONObject o) {
        switch (w == null ? "" : w) {
            case "tank": return "బకెట్‌లో నీళ్ళు లేవు, పంప్ ఆన్ చేయలేదు (పంప్ కాలిపోకుండా)";
            case "cap": return "ఈరోజు హద్దు (" + o.optInt("cap") + " సెకన్లు) అయిపోయింది";
            case "busy": return "ఇప్పటికే పోస్తోంది";
            case "wifi": return "Wi-Fi పోయింది, పంప్ ఆపేశాను";
            case "stop": return "మీరు ఆపమన్నారు";
            case "button": return "బోర్డ్ బటన్‌తో ఆపారు";
            case "sensor": return "మట్టి సెన్సార్ చదవలేదు, పోయలేదు";
            case "wet": return "మట్టి తడిగానే ఉంది, పోయలేదు";
            case "bad": return "ఆ సెకన్లు సరిగా లేవు";
            default: return w == null ? "" : w;
        }
    }

    static String doneText(JSONObject o) {
        int ran = o.optInt("ran");
        String w = o.optString("why");
        StringBuilder s = new StringBuilder();
        if (!o.optBoolean("ok", true) && ran == 0) return "పోయలేదు: " + why(w, o) + ".";
        s.append("done".equals(w) ? "✅ " : "⚠️ ").append(ran).append(" సెకన్లు పోశాను");
        if (!"done".equals(w)) s.append(", తర్వాత ఆపాను: ").append(why(w, o));
        s.append(".");
        if (!o.isNull("before") && !o.isNull("after") && o.has("before") && o.has("after"))
            s.append(" తేమ ").append(o.optInt("before")).append("% → ").append(o.optInt("after")).append("%.");
        if ("empty".equals(o.optString("tank"))) s.append(" బకెట్ ఖాళీ అయింది: నీళ్ళు నింపండి.");
        return s.toString();
    }

    static String lastLine(JSONObject o) {
        JSONObject l = o.optJSONObject("last");
        if (l == null) return "ఇంకా పోయలేదు";
        String src;
        switch (l.optString("src")) {
            case "sched": src = "రోజూ టైమ్"; break;
            case "button": src = "బోర్డ్ బటన్"; break;
            default: src = "ఫోన్"; break;
        }
        long at = l.optLong("at");
        String when = at > 0 ? hhmm(at * 1000) + " కి " : "";
        return when + l.optInt("s") + " సె. (" + src + ")";
    }

    static String schedLine(JSONArray a) {
        if (a == null || a.length() == 0) return "రోజూ పోయడం: పెట్టలేదు";
        StringBuilder s = new StringBuilder("రోజూ: ");
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            if (i > 0) s.append("; ");
            s.append(String.format(Locale.ROOT, "%d:%02d", e.optInt("h"), e.optInt("m"))).append(" కి ").append(e.optInt("s")).append(" సె.");
            if (e.optBoolean("dry", true)) s.append(", పొడిగా ఉంటేనే");
            if (!e.optBoolean("on", true)) s.append(" (ఆపారు)");
        }
        return s.toString();
    }

    static String statusText(JSONObject o) {
        JSONObject set = o.optJSONObject("set");
        return soilLine(o) + ". " + tankLine(o) + ". ఈరోజు " + o.optInt("today") + " సె. పోశాను (హద్దు " + o.optInt("cap") + "). "
                + "చివరిసారి: " + lastLine(o) + ". " + schedLine(set == null ? null : set.optJSONArray("sched")) + "."
                + (o.optBoolean("pump") ? " ఇప్పుడు పోస్తోంది." : "");
    }

    static final String NO_ANSWER = "బోర్డ్ జవాబు ఇవ్వలేదు. బోర్డ్‌కి పవర్ ఉందా, దాని నీలం లైట్ ఆగకుండా వెలుగుతోందా (ఇంటి Wi-Fi కి కలిసినట్టు) చూడండి.";

    static String hhmm(long ms) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }

    /** What the AI may know: the board's numbers, nothing secret (no key, no Wi-Fi names). */
    static JSONObject forAi(JSONObject o) throws Exception {
        JSONObject a = new JSONObject();
        a.put("soil_percent", o.optJSONArray("m"));
        JSONObject set = o.optJSONObject("set");
        if (set != null) {
            a.put("dry_below_percent", set.optInt("dry")).put("seconds_usual", set.optInt("s")).put("seconds_max", set.optInt("max"))
                    .put("schedule", set.optJSONArray("sched"));
        }
        a.put("bucket", o.optString("tank")).put("today_seconds", o.optInt("today")).put("today_limit_seconds", o.optInt("cap"))
                .put("pumping_now", o.optBoolean("pump")).put("last", lastLine(o)).put("said", statusText(o));
        return a;
    }

    /**
     * His short yes to "N సెకన్లు పోయనా?": every word a yes / water / filler word (numbers and సెకన్లు allowed, so "30 సెకన్లు పోయి"),
     * at least one real yes, no "వద్దు" / question. Pure, for tests.
     */
    static boolean saidWater(String said) {
        String s = said == null ? "" : said.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 60 || s.contains("?")) return false;
        if (CardTalk.Words.kind(s, CardTalk.Words.CONFIRM) == CardTalk.Words.NO) return false;
        boolean yes = false;
        for (String tok : CardTalk.Words.tokens(s)) {
            if (tok.isEmpty()) continue;
            if (YES.contains(tok) || startsAny(tok, YES_STEMS)) { yes = true; continue; }
            if (WATER.contains(tok)) { yes = true; continue; }
            if (FILL.contains(tok) || tok.matches("[0-9]+")) continue;
            return false;
        }
        return yes;
    }

    private static boolean startsAny(String tok, String[] stems) {
        for (String st : stems) if (tok.startsWith(st) && !tok.endsWith("ా")) return true;
        return false;
    }

    private static final String[] YES_STEMS = {"అవున", "ఔను", "సరే", "ఓకే", "అలాగే", "కానివ్వు"};
    private static final java.util.Set<String> YES = new java.util.HashSet<>(java.util.Arrays.asList(
            "yes", "yeah", "ok", "okay", "హా", "హాం", "యెస్", "చేయి", "చెయ్", "చేయండి", "పెట్టు", "go"));
    private static final java.util.Set<String> WATER = new java.util.HashSet<>(java.util.Arrays.asList(
            "పోయి", "పోయండి", "పొయ్", "పొయ్యి", "పోసేయ్", "పోసేయి", "పోసెయ్", "పోయించు", "పోయ్", "water", "pour", "start", "స్టార్ట్", "ఆన్"));
    private static final java.util.Set<String> FILL = new java.util.HashSet<>(java.util.Arrays.asList(
            "ఆ", "అండి", "jarvis", "జార్విస్", "ఇప్పుడే", "ఇప్పుడు", "ప్లీజ్", "please", "sir", "సర్", "నీళ్ళు", "నీళ్లు", "చెట్టుకి", "చెట్లకి",
            "మొక్కలకి", "మొక్కకి", "కుండీకి", "సెకన్లు", "సెకండ్లు", "seconds", "secs", "it", "now", "ఇక", "అంతే", "అన్ని", "అవి", "the", "plants"));

    // ================================================================ his notifications, and the daily schedule's report

    static void tell(Context c, String title, String text, boolean sayIt) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_plant", "🌱 మొక్కలకి నీళ్ళు", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, PlantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 471, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(470, new Notification.Builder(c, "jarvis_plant").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) {}
        if (sayIt && !DutyMode.on(c)) Announcer.say(c, text);
    }

    /** A few minutes after each schedule time: what the board did (it waters by itself, even with this phone off). */
    static void rearm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 472, new Intent(c, AlarmReceiver.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!paired(c)) return;
        long next = nextCheck(c, System.currentTimeMillis());
        if (next <= 0) return;
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi); } catch (Exception ignored) {}
    }

    static long nextCheck(Context c, long now) {
        JSONArray a;
        try { a = new JSONArray(sp(c).getString("sched", "[]")); } catch (Exception e) { return 0; }
        long best = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || !e.optBoolean("on", true)) continue;
            Calendar k = Calendar.getInstance();
            k.setTimeInMillis(now);
            k.set(Calendar.HOUR_OF_DAY, e.optInt("h"));
            k.set(Calendar.MINUTE, e.optInt("m"));
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
            k.add(Calendar.MINUTE, 6 + (e.optInt("s") + SOAK) / 60);
            if (k.getTimeInMillis() <= now) k.add(Calendar.DAY_OF_MONTH, 1);
            if (best == 0 || k.getTimeInMillis() < best) best = k.getTimeInMillis();
        }
        return best;
    }

    /** The alarm: reads what the board said since the last look and tells him the schedule's watering and an empty bucket. */
    static void check(Context c) {
        if (!paired(c)) return;
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis() / 1000;
        long since = Math.max(s.getLong("checked", 0), now - 12 * 3600);
        try {
            List<PlantLink.Said> l = new PlantLink(key(c)).poll(since);
            List<String> seen = new ArrayList<>(java.util.Arrays.asList(s.getString("seen", "").split(",")));
            for (PlantLink.Said x : l) {
                JSONObject o = new JSONObject(x.json);
                String ev = o.optString("ev");
                String u = o.optString("u");
                if (u.isEmpty() || seen.contains(u)) continue;
                seen.add(u);
                remember(c, o);
                if ("sched".equals(ev)) {
                    JSONObject r = o.optJSONObject("res");
                    String text;
                    if (r == null) continue;
                    if (r.optBoolean("ok")) {
                        r.put("before", o.opt("before")).put("after", o.opt("after")).put("tank", o.optString("tank")).put("cap", o.optInt("cap"));
                        text = o.optString("at") + " టైమ్: " + doneText(r);
                    } else {
                        text = o.optString("at") + " టైమ్: పోయలేదు — " + why(r.optString("why"), o) + ".";
                    }
                    tell(c, "🌱 రోజూ పోయడం", text, false);
                } else if ("tank".equals(ev)) {
                    tell(c, "🌱 బకెట్ ఖాళీ", "మొక్కల బకెట్‌లో నీళ్ళు అయిపోయాయి: నింపండి. అంతవరకు బోర్డ్ పంప్ ఆన్ చేయదు.", false);
                }
            }
            while (seen.size() > 40) seen.remove(0);
            s.edit().putLong("checked", now).putString("seen", android.text.TextUtils.join(",", seen)).apply();
        } catch (Exception ignored) {}
        rearm(c);
    }
}
