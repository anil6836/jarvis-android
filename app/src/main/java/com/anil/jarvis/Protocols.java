package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * W58 protocols: one word (or a tap on the watch) runs several things, ticked off one by one on the watch's screen.
 * Ready-made: డ్యూటీ (duty mode, the bag on the watch, the weather on the way, the bike's charge, "డ్యూటీకి బయల్దేరాను"
 * to his people — that one only after his tap), నిద్ర (night mode, the lights off, tomorrow's duty), రైడ్ (the weather,
 * the bike's charge), ఇల్లు (duty mode off, sound back, the lights on, the guard paused). He can make his own from the
 * same steps ("ప్రోటోకాల్ జిమ్: సైలెంట్, వాతావరణం"). Steps that send a message never send without his tap.
 */
final class Protocols {
    private Protocols() {}

    static final String ACTION_SEND = "com.anil.jarvis.PROTOCOL_SEND";
    private static final int NOTE_SEND = 311;

    /** Step key, its name on the watch, the words that pick it. */
    static final String[][] STEPS = {
            {"duty_on", "🛡️ డ్యూటీ మోడ్ ఆన్", "డ్యూటీ మోడ్ ఆన్|డ్యూటీ మోడ్"},
            {"duty_off", "🛡️ డ్యూటీ మోడ్ ఆఫ్", "డ్యూటీ మోడ్ ఆఫ్"},
            {"silent", "🔕 ఫోన్ వైబ్రేట్", "సైలెంట్|వైబ్రేట్|silent"},
            {"sound", "🔔 ఫోన్ సౌండ్ ఆన్", "సౌండ్ ఆన్|సౌండ్|sound"},
            {"night", "🌙 నైట్ మోడ్", "నైట్ మోడ్|నిద్ర మోడ్|night"},
            {"bag", "🎒 బ్యాగ్ లిస్ట్ (వాచ్‌లో)", "బ్యాగ్|bag"},
            {"weather", "🌦️ వాతావరణం", "వాతావరణం|వర్షం|weather"},
            {"bike", "🔋 బైక్ ఛార్జ్", "బైక్|బండి|ఛార్జ్|bike"},
            {"lights_on", "💡 లైట్లు ఆన్", "లైట్లు ఆన్|లైట్ ఆన్|lights on"},
            {"lights_off", "⚫ లైట్లు ఆఫ్", "లైట్లు ఆఫ్|లైట్ ఆఫ్|lights off"},
            {"guard_on", "🛡️ ఇంటి కాపలా ఆన్", "కాపలా ఆన్|కాపలా పెట్టు|guard on"},
            {"guard_off", "🛡️ ఇంటి కాపలా ఆపు", "కాపలా ఆపు|కాపలా ఆఫ్|guard off"},
            {"tomorrow", "📅 రేపటి డ్యూటీ", "రేపు|రేపటి|tomorrow"},
            {"status", "📊 స్టేటస్", "స్టేటస్|status"},
            {"msg_duty", "📩 \"డ్యూటీకి బయల్దేరాను\" (మీ నొక్కుతో)", "డ్యూటీకి బయల్దేరాను|బయల్దేరాను మెసేజ్"},
            {"msg_home", "📩 \"ఇంటికి బయల్దేరాను\" (మీ నొక్కుతో)", "ఇంటికి బయల్దేరాను|ఇంటికి వస్తున్నాను"},
    };

    static final String[][] BUILT_IN = {
            {"డ్యూటీ", "duty_on,bag,weather,bike,msg_duty"},
            {"నిద్ర", "night,lights_off,tomorrow"},
            {"రైడ్", "weather,bike"},
            {"ఇల్లు", "duty_off,sound,lights_on,guard_off"},
    };

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_protocols", Context.MODE_PRIVATE); }

    /** All protocols: his own and the ready-made ones {name -> steps}. */
    static JSONObject all(Context c) {
        JSONObject o = new JSONObject();
        try {
            for (String[] b : BUILT_IN) o.put(b[0], b[1]);
            JSONObject mine = new JSONObject(sp(c).getString("mine", "{}"));
            for (Iterator<String> it = mine.keys(); it.hasNext(); ) { String k = it.next(); o.put(k, mine.getString(k)); }
        } catch (Exception ignored) {}
        return o;
    }

    /** The step keys his words name, in his order (pure: tested on a desk). */
    static List<String> stepsFrom(String words) {
        List<String> out = new ArrayList<>();
        String w = words == null ? "" : words.toLowerCase(Locale.ROOT);
        for (String part : w.split("\\s*[,;]\\s*|\\s+(మరియు|and|తర్వాత|ఆ తర్వాత)\\s+")) {
            String best = null;
            int bestLen = 0;
            for (String[] s : STEPS) for (String k : s[2].split("\\|")) if (part.contains(k) && k.length() > bestLen) { best = s[0]; bestLen = k.length(); }
            if (best != null && !out.contains(best)) out.add(best);
        }
        return out;
    }

    static String save(Context c, String name, String words) {
        List<String> steps = stepsFrom(words);
        if (name == null || name.trim().isEmpty()) return "ప్రోటోకాల్‌కి పేరు చెప్పండి.";
        if (steps.isEmpty()) return "ఆ పనులు నాకు అర్థం కాలేదు. ఇవి కలపొచ్చు: సైలెంట్, సౌండ్, డ్యూటీ మోడ్, నైట్ మోడ్, బ్యాగ్, వాతావరణం, బైక్, లైట్లు ఆన్ / ఆఫ్, కాపలా ఆన్ / ఆపు, రేపటి డ్యూటీ, స్టేటస్.";
        try {
            JSONObject mine = new JSONObject(sp(c).getString("mine", "{}"));
            mine.put(name.trim(), String.join(",", steps));
            sp(c).edit().putString("mine", mine.toString()).apply();
        } catch (Exception ignored) {}
        StringBuilder b = new StringBuilder();
        for (String s : steps) b.append(label(s)).append(", ");
        return "సరే, \"" + name.trim() + " ప్రోటోకాల్\": " + b.substring(0, b.length() - 2) + ".";
    }

    static String delete(Context c, String name) {
        try {
            JSONObject mine = new JSONObject(sp(c).getString("mine", "{}"));
            if (mine.remove(name.trim()) == null) return "ఆ పేరుతో మీ ప్రోటోకాల్ లేదు.";
            sp(c).edit().putString("mine", mine.toString()).apply();
            return "\"" + name.trim() + "\" ప్రోటోకాల్ తీసేశాను.";
        } catch (Exception e) { return "తీసేయలేకపోయాను."; }
    }

    static String label(String key) {
        for (String[] s : STEPS) if (s[0].equals(key)) return s[1];
        return key;
    }

    /** The protocol he named (his own name, or a ready-made one), or null. */
    static String find(Context c, String spoken) {
        String w = spoken == null ? "" : spoken.trim().toLowerCase(Locale.ROOT);
        JSONObject a = all(c);
        for (Iterator<String> it = a.keys(); it.hasNext(); ) { String k = it.next(); if (w.contains(k.toLowerCase(Locale.ROOT))) return k; }
        if (w.contains("duty")) return "డ్యూటీ";
        if (w.contains("sleep") || w.contains("నైట్")) return "నిద్ర";
        if (w.contains("ride")) return "రైడ్";
        if (w.contains("home") || w.contains("ఇంటి")) return "ఇల్లు";
        return null;
    }

    /** Runs it (background thread): each step's line, the watch shows them ticking. The lines together. */
    static String run(Context c, String name) {
        String steps = all(c).optString(name, "");
        if (steps.isEmpty()) return "\"" + name + "\" ప్రోటోకాల్ లేదు.";
        String[] keys = steps.split(",");
        JSONArray shown = new JSONArray();
        StringBuilder say = new StringBuilder("⚡ " + name + " ప్రోటోకాల్: ");
        for (String k : keys) try { shown.put(new JSONObject().put("label", label(k)).put("done", false)); } catch (Exception ignored) {}
        panel(c, name, shown, false);
        for (int i = 0; i < keys.length; i++) {
            String line;
            try { line = step(c, keys[i]); } catch (Exception e) { line = "చేయలేకపోయాను"; }
            try { shown.getJSONObject(i).put("done", true).put("line", line); } catch (Exception ignored) {}
            panel(c, name, shown, i == keys.length - 1);
            if (line != null && !line.isEmpty()) say.append(line).append(" ");
            try { Thread.sleep(350); } catch (InterruptedException ignored) {} // (the watch ticks them one by one)
        }
        return say.toString().trim();
    }

    private static void panel(Context c, String name, JSONArray steps, boolean done) {
        try { WatchHub.send(c, WatchDo.P_PANEL, new JSONObject().put("kind", "protocol").put("name", name).put("steps", steps).put("done", done)); } catch (Exception ignored) {}
    }

    private static String step(Context c, String k) throws Exception {
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (k) {
            case "duty_on": if (DutyMode.on(c)) return "డ్యూటీ మోడ్ ఇప్పటికే ఆన్."; DutyMode.start(c, false); return "డ్యూటీ మోడ్ ఆన్: ఫోన్ వైబ్రేట్‌లో.";
            case "duty_off": if (DutyMode.on(c)) DutyMode.off(c, false); return "డ్యూటీ మోడ్ ఆఫ్.";
            case "silent": if (am != null) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); return "ఫోన్ వైబ్రేట్‌లో.";
            case "sound": if (am != null && !new Prefs(c).night()) am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); return "ఫోన్ సౌండ్ ఆన్.";
            case "night": {
                Prefs p = new Prefs(c);
                p.set("night", true);
                p.sp.edit().putLong("night_until", Life.nightEnd("")).apply();
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (nm != null && nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                else if (am != null) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                return "నైట్ మోడ్ ఆన్ (అలారాలు మోగుతాయి).";
            }
            case "bag": {
                Travel.openOnWatch(c, "duty", "");
                String bag = Duty.checklist(c);
                return bag.isEmpty() ? "" : "బ్యాగ్: " + bag + ".";
            }
            case "weather": {
                JSONObject w = new JSONObject(Tools.weatherJson(c, ""));
                if (!w.optBoolean("ok")) return Net.online(c) ? "వాతావరణం దొరకలేదు." : "";
                JSONObject now = w.optJSONObject("now");
                JSONArray f = w.optJSONArray("forecast");
                int rain = f == null || f.length() == 0 ? -1 : f.getJSONObject(0).optInt("rain_chance_pct", -1);
                return "ఇప్పుడు " + Math.round(now == null ? 0 : now.optDouble("temp_c")) + "°" + (rain >= 0 ? ", ఈరోజు వర్షం అవకాశం " + rain + "%" : "") + ".";
            }
            case "bike": {
                int pct = Bike.estimatePct(c);
                if (pct < 0) return "";
                return "బైక్ సుమారు " + pct + "%: " + Math.round(Bike.fullRangeKm(new Prefs(c)) * pct / 100.0) + " కి.మీ వెళ్లొచ్చు" + (pct < 30 ? ", ఛార్జ్ పెట్టండి" : "") + ".";
            }
            case "lights_on": case "lights_off": {
                boolean on = k.equals("lights_on");
                for (String[] s : WatchDo.smart(c)) {
                    String n = s[0].toLowerCase(Locale.ROOT);
                    boolean isOff = n.contains("ఆఫ్") || n.contains("off");
                    if (on == isOff) continue;
                    try { Http.getText(s[1]); return s[0] + "."; } catch (Exception e) { return s[0] + ": Alexa స్పందించలేదు."; }
                }
                return "";
            }
            case "guard_on": case "guard_off": return HomeLink.linked(c) ? HomeLink.guard(c, k.equals("guard_on")) : "";
            case "tomorrow": return Status.duty(c);
            case "status": return Status.text(c);
            case "msg_duty": return askSend(c, "డ్యూటీకి బయల్దేరాను. – " + new Prefs(c).name());
            case "msg_home": return askSend(c, "ఇంటికి బయల్దేరాను. – " + new Prefs(c).name());
            default: return "";
        }
    }

    /** The message to his people ("చేరుకున్నాను" people) waits for his tap on the card (phone / watch). */
    private static String askSend(Context c, String text) {
        String who = Drive.settings(c).getString("reached_to", "").trim();
        if (who.isEmpty()) return "";
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_reached", "చేరుకున్నాను మెసేజ్", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent send = PendingIntent.getBroadcast(c, NOTE_SEND, new Intent(c, AlarmReceiver.class).setAction(ACTION_SEND).putExtra("text", text),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE_SEND, new Notification.Builder(c, "jarvis_reached").setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("📩 " + who + " కి పంపనా?").setContentText("\"" + text + "\"").setAutoCancel(true).setTimeoutAfter(60 * 60_000L)
                    .addAction(new Notification.Action.Builder(null, "📩 పంపు", send).build()).build());
        } catch (Exception ignored) {}
        return who + " కి \"" + text + "\" పంపనా? (నోటిఫికేషన్ / వాచ్‌లో పంపు నొక్కండి)";
    }

    /** His tap: sent by SMS to each of his people. */
    static void send(Context c, String text) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_SEND); } catch (Exception ignored) {}
        String who = Drive.settings(c).getString("reached_to", "").trim();
        if (who.isEmpty() || text == null || c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        new Thread(() -> {
            StringBuilder sent = new StringBuilder();
            for (String w : who.split("\\s*,\\s*")) {
                String[] n = Sos.number(c, w);
                if (n == null) continue;
                try { c.getSystemService(android.telephony.SmsManager.class).sendTextMessage(n[1], null, text, null, null); sent.append(sent.length() > 0 ? ", " : "").append(n[0]); }
                catch (Exception ignored) {}
            }
            Reminders.notify(c, "📩 పంపాను", sent.length() == 0 ? "పంపలేకపోయాను." : sent + " కి: \"" + text + "\"", NOTE_SEND + 1);
        }, "protocol-send").start();
    }

    /** Words (pure): {"run", name-ish} / {"save", name, steps} / {"delete", name} / {"list"}; null otherwise. */
    static String[] asks(String bare) {
        String t = bare == null ? "" : bare.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (!low.matches("(?s).*(ప్రోటోకాల్|protocol).*")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:కొత్త\\s*)?(?:ప్రోటోకాల్|protocol)\\s+(\\S+?)\\s*[:：]\\s*(.+)$").matcher(t);
        if (m.find()) return new String[]{"save", m.group(1), m.group(2)};
        m = java.util.regex.Pattern.compile("^(\\S+)\\s*(?:ప్రోటోకాల్|protocol)\\s*(?:తీసేయ్|తీసేయి|delete|remove)").matcher(t);
        if (m.find()) return new String[]{"delete", m.group(1)};
        if (low.matches("(?s).*(ప్రోటోకాల్స్|protocols|ఏ ప్రోటోకాల్|ప్రోటోకాల్ లిస్ట్).*")) return new String[]{"list"};
        return new String[]{"run", t};
    }
}
