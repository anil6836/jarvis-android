package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * O35 / W74: the phone's battery against his day. Low (20%) before or during a duty, or out on the road -> a word in
 * time; and from how fast it is going down now, whether it lasts until the duty ends -> "సరిపోదు, ఇప్పుడే N నిమిషాలు
 * ఛార్జ్ పెట్టండి". The watch's battery too, when it runs low while he wears it. (Estimates; told on his wrist as well.)
 */
final class Power {
    private Power() {}

    private static final long MIN = 60_000L, HOUR = 60 * MIN;
    static final int NOTE = 267;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_power", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return sp(c).getBoolean("on", true); }

    private static int lastPct = -1;

    /** Each battery broadcast (NotifyListener / WakeService). */
    static synchronized void onBattery(Context c, Intent i) {
        if (i == null || !on(c)) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        SharedPreferences s = sp(c);
        if (plugged) {
            if (s.contains("samples") || s.getBoolean("told20", false)) s.edit().remove("samples").putBoolean("told20", false).putBoolean("told10", false).apply();
            lastPct = pct;
            return;
        }
        if (pct == lastPct) return;
        lastPct = pct;
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(s.getString("samples", "[]")), keep = new JSONArray();
            for (int k = 0; k < a.length(); k++) { JSONObject o = a.getJSONObject(k); if (now - o.optLong("t") < 6 * HOUR) keep.put(o); }
            keep.put(new JSONObject().put("t", now).put("p", pct));
            while (keep.length() > 80) keep.remove(0);
            s.edit().putString("samples", keep.toString()).apply();
            check(c, pct, drainPerHour(keep, now), now);
        } catch (Exception ignored) {}
    }

    /** % an hour it went down over the last 3 hours (pure: tested on a desk); -1 when there is too little to tell. */
    static double drainPerHour(JSONArray a, long now) {
        JSONObject first = null, last = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || now - o.optLong("t") > 3 * HOUR) continue;
            if (first == null) first = o;
            last = o;
        }
        if (first == null || last == null) return -1;
        long ms = last.optLong("t") - first.optLong("t");
        int drop = first.optInt("p") - last.optInt("p");
        if (ms < 45 * MIN || drop < 3) return -1;
        return drop / (ms / (double) HOUR);
    }

    /** Minutes to charge so pct lasts until `until` at this rate (pure); 0 = it lasts. About 1% a minute on his charger. */
    static int chargeMinutes(int pct, double perHour, long now, long until) {
        if (perHour <= 0 || until <= now) return 0;
        double need = perHour * (until - now) / (double) HOUR + 5; // (5% spare)
        if (pct >= need) return 0;
        return (int) Math.max(10, Math.min(120, Math.ceil(need - pct)));
    }

    private static void check(Context c, int pct, double rate, long now) {
        SharedPreferences s = sp(c);
        LocalDateTime[] d = Duty.ready(Duty.load(c)) ? Duty.nowOrNext(c) : null;
        long start = d == null ? 0 : d[0].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = d == null ? 0 : d[1].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        boolean onDuty = d != null && now >= start && now < end, dutySoon = d != null && !onDuty && start - now < 4 * HOUR;
        // W74: will it last until the duty ends?
        if ((onDuty || dutySoon) && rate > 0) {
            String key = "told_duty_" + start;
            int m = chargeMinutes(pct, rate, now, end);
            if (m > 0 && !s.getBoolean(key, false)) {
                s.edit().putBoolean(key, true).apply();
                double hours = pct / rate;
                Reminders.notify(c, "🔋 ఫోన్ బ్యాటరీ డ్యూటీ వరకు సరిపోదు",
                        "ఇప్పుడు " + pct + "%. ఈ వేగంతో సుమారు " + Status.hours(Math.round(hours * 60)) + " మాత్రమే వస్తుంది, డ్యూటీ "
                                + Offline.sayWhen(d[1], LocalDateTime.now()) + " అవుతుంది. ఇప్పుడే సుమారు " + m + " నిమిషాలు ఛార్జ్ పెట్టండి (అంచనా).", NOTE);
                return;
            }
        }
        // O35: low, when he is away from his charger (a duty now or soon, on the road, out of the house)
        boolean away = onDuty || dutySoon || Bike.riding(c) || DriveService.running || awayFromHome(c);
        if (!away) return;
        if (pct <= 10 && !s.getBoolean("told10", false)) {
            s.edit().putBoolean("told10", true).putBoolean("told20", true).apply();
            Reminders.notify(c, "🪫 ఫోన్ " + pct + "% మాత్రమే", "త్వరగా ఛార్జ్ పెట్టండి. అవసరమైతే బ్యాటరీ సేవర్ ఆన్ చేయండి; SOS, కాల్స్ కోసం కొంచెం ఉంచండి.", NOTE);
        } else if (pct <= 20 && !s.getBoolean("told20", false)) {
            s.edit().putBoolean("told20", true).apply();
            String when = onDuty ? "డ్యూటీలో ఉన్నారు" : dutySoon ? "డ్యూటీ " + Offline.sayWhen(d[0], LocalDateTime.now()) : "బయట ఉన్నారు";
            Reminders.notify(c, "🔋 ఫోన్ " + pct + "%", when + ": దగ్గరలో ఛార్జ్ పెట్టండి" + (rate > 0 ? " (సుమారు " + Status.hours(Math.round(pct / rate * 60)) + " వస్తుంది)" : "") + ".", NOTE);
        }
    }

    private static boolean awayFromHome(Context c) {
        try {
            JSONObject h = Travel.home(c);
            android.location.Location l = Tools.lastLocation(c);
            if (h == null || l == null || System.currentTimeMillis() - l.getTime() > 2 * HOUR) return false;
            return Drive.meters(h.optDouble("lat"), h.optDouble("lon"), l.getLatitude(), l.getLongitude()) > 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** The watch's battery (its beat): low while he wears it -> once a day; during a duty it says so. */
    static void watchBattery(Context c, int pct, boolean charging) {
        if (pct < 0 || charging || !on(c)) return;
        SharedPreferences s = sp(c);
        String day = java.time.LocalDate.now().toString();
        if (pct > 20 || day.equals(s.getString("watch_told", ""))) return;
        if (Boolean.FALSE.equals(WatchHub.worn(c))) return;
        s.edit().putString("watch_told", day).apply();
        boolean duty = DutyMode.on(c);
        Reminders.notify(c, "⌚ వాచ్ బ్యాటరీ " + pct + "%", (duty ? "డ్యూటీ అయ్యే వరకు సరిపోకపోవచ్చు. " : "") + "వీలున్నప్పుడు వాచ్ ఛార్జ్ పెట్టండి.", NOTE + 1);
    }
}
