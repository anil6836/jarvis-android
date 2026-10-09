package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2b/2c on the phone's side: Jarvis's own alerts on the watch with their own vibration (W8, W15; vibration only
 * while riding, W18), Jarvis's alarm as a silent vibration on the wrist (W19), and the news the watch keeps for its
 * face and for "phone forgotten" (W20): next duty, weather, where the phone was last, the theme colours (W5) and look (W3).
 */
final class WatchAlerts {
    private WatchAlerts() {}

    static final String P_ALERT = "/jarvis/alert", P_ALERT_GONE = "/jarvis/alert/gone", P_ALERT_ACTION = "/jarvis/alert/action",
            P_ALARM = "/jarvis/alarm", P_ALARM_ANSWER = "/jarvis/alarm/answer", P_ALARM_STOP = "/jarvis/alarm/stop", P_INFO = "/jarvis/info";
    static final String ACTION_INFO = "com.anil.jarvis.WATCH_INFO", ACTION_ALARM_LOUD = "com.anil.jarvis.WATCH_ALARM_LOUD";

    // ================================================================ W15 / W8: Jarvis's notifications on the watch

    /** Jarvis's own notification channels that are alerts for him (not progress, news or the listening note). */
    private static String kindOf(String channel, String title) {
        String t = title == null ? "" : title;
        switch (channel == null ? "" : channel) {
            case "jarvis_house_sounds":
                return t.startsWith("🍲") ? "cooker" : t.startsWith("🌧") || t.startsWith("⛈") ? "weather" : "door";
            case "jarvis_cook_timer": return "cooker";
            case "jarvis_meds": case "jarvis_cough_care": return "medicine";
            case "jarvis_reminders": case "jarvis_daily": case "jarvis_bday": case "jarvis_holidays": case "jarvis_monthly":
            case "jarvis_diary": case "jarvis_exercise": case "jarvis_charge": case "jarvis_rest": case "jarvis_duty":
            case "jarvis_reached": case "jarvis_money": case "jarvis_drive":
                return "reminder";
            case "jarvis_guard": case "jarvis_crash": case "jarvis_stop_alarm": return "sos";
            case "jarvis_find": return "phone";
            case "jarvis_sound": return "info";
            case "jarvis_care": return "care";
            case "jarvis_weather": return "weather"; // (phase 5: rain soon, great heat)
            case "jarvis_awake": return "rest"; // (the awake check on a ride after a duty: tapped on the wrist)
            default: return null; // (news, prices, progress, updates, the listening note: not on the wrist)
        }
    }

    /** The watch tells about these; their buttons press the phone's buttons. By the alert's id. */
    private static final Map<Integer, Notification.Action[]> actions = new ConcurrentHashMap<>();
    /** The alerts on the watch now (id → the notification's key), so they go when the phone's go. */
    private static final Map<Integer, String> shown = new ConcurrentHashMap<>();

    /** Danger and "find my phone" stay up until answered (ongoing) but must still reach the wrist. */
    private static boolean urgent(String kind) { return "sos".equals(kind) || "phone".equals(kind); }

    /** A notification of Jarvis's own was posted (NotifyListener, main thread). */
    static void posted(Context c, StatusBarNotification sbn) {
        try {
            if (!WatchHub.alertsOn(c) || !WatchHub.known(c) || !WatchHub.phoneIdle(c)) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if (System.currentTimeMillis() - sbn.getPostTime() > 60_000) return; // (already up when the listener reconnected)
            Bundle x = n.extras;
            String title = x == null ? "" : str(x.getCharSequence(Notification.EXTRA_TITLE));
            String text = x == null ? "" : str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty() && x != null) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
            if (title.isEmpty() && text.isEmpty()) return;
            String kind = kindOf(n.getChannelId(), title);
            if (kind == null || sbn.isOngoing() && !urgent(kind)) return;
            int id = sbn.getKey().hashCode();
            // an update of one already on the watch that the phone shows quietly (the cooker's count): no buzz again
            boolean quietUpdate = shown.containsKey(id) && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
            JSONArray acts = new JSONArray();
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a == null || a.actionIntent == null || a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) acts.put("");
                    else acts.put(str(a.title));
                }
                actions.put(id, n.actions);
            }
            shown.put(id, sbn.getKey());
            JSONObject o = new JSONObject().put("id", id).put("key", sbn.getKey()).put("kind", kind).put("title", title).put("text", text)
                    .put("acts", acts).put("quiet", WatchHub.riding(c)).put("silent", quietUpdate);
            WatchHub.send(c, P_ALERT, o, null, true); // (only to a watch near the phone)
        } catch (Exception ignored) {}
    }

    /** One of Jarvis's notifications went away on the phone (answered there, or replaced). */
    static void removed(Context c, StatusBarNotification sbn) {
        int id = sbn.getKey().hashCode();
        actions.remove(id);
        if (shown.remove(id) == null) return;
        try { WatchHub.send(c, P_ALERT_GONE, new JSONObject().put("id", id)); } catch (Exception ignored) {}
    }

    /** He tapped one of its buttons on the watch: the same button on the phone is pressed. */
    static void action(Context c, JSONObject o) {
        Notification.Action[] a = actions.get(o.optInt("id"));
        if (a == null) { // (the phone app restarted meanwhile: the notification itself, if it is still up)
            StatusBarNotification sbn = NotifyListener.active(o.optString("key"));
            if (sbn != null && sbn.getNotification() != null) a = sbn.getNotification().actions;
        }
        int i = o.optInt("i", -1);
        if (a == null || i < 0 || i >= a.length || a[i] == null || a[i].actionIntent == null) return;
        try { a[i].actionIntent.send(); } catch (Exception ignored) {}
    }

    // ================================================================ W19: Jarvis's alarm as a vibration on the wrist

    /** Rings on the watch instead (only him: no phone sound), when it is on, connected and on his wrist. */
    static boolean alarmOnWatch(Context c) {
        return WatchHub.alarmOn(c) && WatchHub.known(c) && !Boolean.FALSE.equals(WatchHub.worn(c));
    }

    /**
     * The watch vibrates; the phone rings after 3 minutes if he hasn't answered on the watch (never oversleeps), and at
     * once if the watch isn't near the phone right now (checked afresh, not from its last news).
     */
    static void alarm(Context c, String id, int count, String title) {
        final Context app = c.getApplicationContext();
        Reminders.setAlarm(app, System.currentTimeMillis() + 3 * 60_000L, loud(app, id, count));
        try {
            WatchHub.send(app, P_ALARM, new JSONObject().put("id", id).put("count", count).put("title", title).put("snooze", count < 3),
                    () -> { cancelLoud(app, id, count); SongAlarm.fire(app, id, count, true); }, true);
        } catch (Exception ignored) {}
    }

    private static void cancelLoud(Context c, String id, int count) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(loud(c, id, count));
    }

    /** The phone took over (no answer on the watch in 3 minutes): the watch stops vibrating. */
    static void loudNow(Context c, String id) {
        try { WatchHub.send(c, P_ALARM_STOP, new JSONObject().put("id", id)); } catch (Exception ignored) {}
    }

    private static PendingIntent loud(Context c, String id, int count) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM_LOUD).putExtra(SongAlarm.EXTRA_ID, id).putExtra(SongAlarm.EXTRA_COUNT, count)
                .setData(android.net.Uri.parse("jarvis-watch-alarm://" + id));
        return PendingIntent.getBroadcast(c, ("watchloud" + id).hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** He answered on the watch: stop (good morning on the watch), or 5 minutes more. */
    static void alarmAnswered(Context c, JSONObject o) {
        String id = o.optString("id");
        int count = o.optInt("count");
        cancelLoud(c, id, count);
        SongAlarm.clearRinging(c); // (the phone may have started ringing meanwhile)
        AlarmActivity.stopRinging();
        if ("snooze".equals(o.optString("act"))) {
            if (count >= 3) Reminders.setAlarm(c, System.currentTimeMillis() + 5 * 60_000L, loud(c, id, count)); // no more snoozes: the phone rings then
            else SongAlarm.snooze(c, id, 5, count);
            return;
        }
        JSONObject al = SongAlarm.find(c, id);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String say = al != null && al.optBoolean("nap")
                    ? new Prefs(app).name() + ", " + (al.optInt("nap_minutes") > 0 ? al.optInt("nap_minutes") + " నిమిషాల " : "") + "కునుకు అయిపోయింది. ఒక గ్లాసు నీళ్లు తాగండి."
                    : WatchHub.morningOn(app) && Morning.morning() ? Morning.text(app) // W23: the day in 30 seconds
                    : AlarmActivity.greeting(app);
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> WatchHub.say(app, say));
        }, "watch-greet").start();
    }

    // ================================================================ the watch's news: duty, weather, where the phone is

    /** Every half hour while the watch app is known (and at once): the watch keeps it for its face and "phone forgotten". */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null || !WatchHub.known(c)) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 4711, new Intent(c, AlarmReceiver.class).setAction(ACTION_INFO),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 30 * 60_000L, 30 * 60_000L, pi);
    }

    private static volatile long weatherAt;
    private static volatile JSONObject weather;
    private static volatile String placeKey = "", placeName = "";

    /** Sends the news (background thread). */
    static void pushInfo(Context c) {
        Context app = c.getApplicationContext();
        if (!WatchHub.known(app)) return;
        JSONObject o = new JSONObject();
        try {
            o.put("theme", Ui.theme(app)).put("look", WatchHub.look(app)).put("name", new Prefs(app).name());
            JSONObject d = duty(app);
            if (d != null) {
                try { Duty.Roster r = Duty.load(app); d.put("leave", r.leaveTime(r.timeOf(Duty.ME))); } catch (Exception ignored) {}
                o.put("duty", d);
            }
            // W21: the tile's lines
            JSONObject rem = Status.next(app);
            if (rem != null) o.put("rem", new JSONObject().put("at", rem.optLong("at")).put("text", rem.optString("text")));
            try {
                long now = System.currentTimeMillis();
                long[] sl = Sleep.between(app, now - 20 * 3600_000L, now + 1);
                if (sl[0] > 0) o.put("sleep", sl[1]);
            } catch (Exception ignored) {}
            o.put("dutyMode", DutyMode.on(app));
            Location l = Tools.lastLocation(app);
            if (l != null) {
                o.put("lat", l.getLatitude()).put("lon", l.getLongitude()).put("placeAt", l.getTime()).put("place", place(app, l));
                JSONObject w = weather(app, l);
                if (w != null) o.put("weather", w);
            }
        } catch (Exception ignored) {}
        WatchHub.send(app, P_INFO, o);
    }

    /** Next duty start (or "on duty now"): {at, text, on}. */
    static JSONObject duty(Context c) {
        try {
            Duty.Roster r = Duty.load(c);
            if (!Duty.ready(r)) return null;
            String t = r.timeOf(Duty.ME);
            String[] hm = t.split(":");
            int h = Integer.parseInt(hm[0].trim()), m = Integer.parseInt(hm[1].trim().substring(0, 2));
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();
            LocalDateTime startToday = today.atTime(h, m);
            boolean on = r.isOn(Duty.ME, today) && (!Duty.startsDuty(r, today) || !now.isBefore(startToday))
                    || r.isOn(Duty.ME, today.minusDays(1)) && !r.isOn(Duty.ME, today) && now.isBefore(startToday);
            JSONObject o = new JSONObject().put("on", on).put("time", t);
            for (int i = 0; i < 40; i++) {
                LocalDate d = today.plusDays(i);
                if (!Duty.startsDuty(r, d)) continue;
                LocalDateTime at = d.atTime(h, m);
                if (!at.isAfter(now)) continue;
                LocalDate e = d.plusDays(1); // it ends at the same time on the first day off after it
                for (int k = 0; k < 10 && r.isOn(Duty.ME, e); k++) e = e.plusDays(1);
                return o.put("at", at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                        .put("end", e.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject weather(Context c, Location l) {
        JSONObject w = weather;
        if (w != null && SystemClock.elapsedRealtime() - weatherAt < 60 * 60_000L) return w;
        if (!Net.online(c)) return w;
        try {
            JSONObject r = Http.get(String.format(Locale.ENGLISH, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                    + "&current=temperature_2m,weather_code&daily=precipitation_probability_max&forecast_days=1&timezone=auto", l.getLatitude(), l.getLongitude()));
            JSONObject cur = r.getJSONObject("current");
            w = new JSONObject().put("t", Math.round(cur.optDouble("temperature_2m"))).put("code", cur.optInt("weather_code"))
                    .put("rain", r.getJSONObject("daily").getJSONArray("precipitation_probability_max").optInt(0)).put("at", System.currentTimeMillis());
            weather = w;
            weatherAt = SystemClock.elapsedRealtime();
        } catch (Exception ignored) {}
        return w;
    }

    /** A short name for where the phone is (its area), looked up once per place. */
    private static String place(Context c, Location l) {
        String key = String.format(Locale.ENGLISH, "%.3f,%.3f", l.getLatitude(), l.getLongitude());
        if (key.equals(placeKey)) return placeName;
        String name = "";
        try {
            if (Geocoder.isPresent()) {
                @SuppressWarnings("deprecation")
                List<Address> a = new Geocoder(c, new Locale("te", "IN")).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                if (a != null && !a.isEmpty()) {
                    Address x = a.get(0);
                    name = x.getSubLocality() != null ? x.getSubLocality() : x.getLocality() != null ? x.getLocality() : x.getThoroughfare();
                }
            }
        } catch (Exception ignored) {}
        if (name == null) name = "";
        placeKey = key;
        placeName = name;
        return name;
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }
}
