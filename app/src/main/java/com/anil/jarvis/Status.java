package com.anil.jarvis;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * W22: "Jarvis, స్టేటస్" like a suit's status: phone and watch battery, internet, how the phone rings, duty / night mode,
 * the home guard (when this phone keeps it), the next duty, reminder and alarm, and what Jarvis is counting now. All from
 * the phone itself (works without internet). Said by voice, and shown on the watch.
 */
final class Status {
    private Status() {}

    static List<String> lines(Context c) {
        List<String> l = new ArrayList<>();
        Context app = c.getApplicationContext();
        try {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                if (lvl >= 0) l.add("📱 ఫోన్ బ్యాటరీ " + Math.round(lvl * 100f / scale) + "%" + (charging ? " (ఛార్జ్ అవుతోంది)" : ""));
            }
        } catch (Exception ignored) {}
        JSONObject w = WatchHub.info;
        if (w != null && w.optInt("bat", -1) >= 0 && WatchHub.seenAt > 0) {
            long ago = (SystemClock.elapsedRealtime() - WatchHub.seenAt) / 60_000L;
            if (ago < 120) l.add("⌚ వాచ్ బ్యాటరీ " + w.optInt("bat") + "%" + (ago >= 5 ? " (" + ago + " ని క్రితం)" : "")
                    + (Boolean.FALSE.equals(WatchHub.worn(app)) ? " · చేతికి లేదు" : ""));
        }
        l.add(Net.online(app) ? "🌐 ఇంటర్నెట్ ఉంది" : "🌐 ఇంటర్నెట్ లేదు (offline పనులు మాత్రమే)");
        AudioManager am = app.getSystemService(AudioManager.class);
        android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
        boolean dnd = nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        if (am != null) {
            int m = am.getRingerMode();
            l.add("🔔 ఫోన్: " + (dnd ? "Do Not Disturb" : m == AudioManager.RINGER_MODE_SILENT ? "సైలెంట్" : m == AudioManager.RINGER_MODE_VIBRATE ? "వైబ్రేట్" : "సౌండ్ ఆన్"));
        }
        Prefs p = new Prefs(app);
        if (DutyMode.on(app)) l.add("🛡️ డ్యూటీ మోడ్ ఆన్");
        if (p.night()) l.add("🌙 నైట్ మోడ్ ఆన్");
        if (p.driving()) l.add("🚗 డ్రైవింగ్ మోడ్ ఆన్");
        try { if (Guard.running(app)) l.add(Guard.watching(app) ? "🏠 ఈ ఫోన్ ఇంటికి కాపలా కాస్తోంది" : "🏠 గార్డ్ ఆన్, కానీ కాసేపటి నుంచి స్పందన లేదు"); } catch (Exception ignored) {}
        String duty = duty(app);
        if (!duty.isEmpty()) l.add(duty);
        String r = nextReminder(app);
        if (!r.isEmpty()) l.add("⏰ " + r);
        try {
            AlarmManager a = app.getSystemService(AlarmManager.class);
            AlarmManager.AlarmClockInfo ai = a == null ? null : a.getNextAlarmClock();
            if (ai != null && ai.getTriggerTime() - System.currentTimeMillis() < 36 * 3600_000L)
                l.add("⏰ అలారం: " + Offline.sayWhen(LocalDateTime.ofInstant(Instant.ofEpochMilli(ai.getTriggerTime()), ZoneId.systemDefault()), LocalDateTime.now()));
        } catch (Exception ignored) {}
        try {
            if (Sounds.cookerActive(app)) {
                JSONObject k = Sounds.cookerStatus(app);
                l.add("🍲 కుక్కర్: " + k.optInt("whistles_heard") + " / " + k.optInt("target") + " విజిల్స్");
            }
        } catch (Exception ignored) {}
        if (Stopwatch.running(app)) l.add("⏱️ స్టాప్‌వాచ్: " + Stopwatch.say(Stopwatch.elapsed(app)));
        int out = Lent.out(app).size();
        if (out > 0) l.add("📦 ఇచ్చిన వస్తువులు ఇంకా " + out + " తిరిగి రావాలి");
        return l;
    }

    /** "🏍️ డ్యూటీలో: ఇంకా 20 గంటలు" / "🏍️ తర్వాతి డ్యూటీ: రేపు ఉదయం 11:30 (10:00 కి బయలుదేరాలి)" / "". */
    static String duty(Context c) {
        try {
            Duty.Roster r = Duty.load(c);
            if (!Duty.ready(r)) return "";
            LocalDateTime[] d = Duty.nowOrNext(c);
            if (d == null) return "";
            LocalDateTime now = LocalDateTime.now();
            if (!now.isBefore(d[0])) return "🏍️ డ్యూటీలో: ఇంకా " + hours(java.time.Duration.between(now, d[1]).toMinutes());
            return "🏍️ తర్వాతి డ్యూటీ: " + Offline.sayWhen(d[0], now) + " (" + r.leaveTime(r.timeOf(Duty.ME)) + " కి బయలుదేరాలి)";
        } catch (Exception e) {
            return "";
        }
    }

    /** 1500 -> "1 రోజు 1 గంట", 75 -> "1 గంట 15 నిమిషాలు". */
    static String hours(long minutes) {
        long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append(d == 1 ? " రోజు " : " రోజులు ");
        if (h > 0) b.append(h).append(h == 1 ? " గంట " : " గంటలు ");
        if (d == 0 && (m > 0 || h == 0)) b.append(m).append(" నిమిషాలు");
        return b.toString().trim();
    }

    /** The next reminder: "రేపు ఉదయం 6:00 – పాలు తేవాలి", or "". */
    static String nextReminder(Context c) {
        JSONObject n = next(c);
        if (n == null) return "";
        return Offline.sayWhen(LocalDateTime.ofInstant(Instant.ofEpochMilli(n.optLong("at")), ZoneId.systemDefault()), LocalDateTime.now()) + " – " + n.optString("text");
    }

    static JSONObject next(Context c) {
        JSONObject best = null;
        long now = System.currentTimeMillis();
        for (JSONObject r : Store.get(c).reminders()) {
            if (r.optBoolean("done") || r.optLong("at") <= now) continue;
            if (best == null || r.optLong("at") < best.optLong("at")) best = r;
        }
        return best;
    }

    static String text(Context c) {
        StringBuilder b = new StringBuilder();
        for (String s : lines(c)) b.append(s.replaceAll("^\\S+\\s", "")).append(". ");
        return b.toString().trim();
    }

    static JSONObject json(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (String s : lines(c)) a.put(s);
        return new JSONObject().put("kind", "status").put("lines", a).put("at", System.currentTimeMillis());
    }

    /** He asks for it ("స్టేటస్", "సిస్టమ్ స్టేటస్", "అన్నీ ఎలా ఉన్నాయి"). */
    static boolean asks(String t) {
        String x = t == null ? "" : t.trim().toLowerCase(java.util.Locale.ROOT);
        return x.matches("(?s)^(జార్విస్\\s*,?\\s*)?(స్టేటస్|స్టేటస్ చెప్పు|సిస్టమ్ స్టేటస్|సిస్టం స్టేటస్|ఫుల్ స్టేటస్|status|system status|status report|స్టేటస్ రిపోర్ట్)\\s*[?.!]?$")
                || x.contains("అన్నీ ఎలా ఉన్నాయి") || x.contains("సూట్ స్టేటస్");
    }
}
