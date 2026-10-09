package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Around each ride (the bike's / car's Bluetooth, or the drive alerts):
 *  - at the start, rain in the next two hours or great heat where he is;
 *  - just off a 48-hour duty and not slept since: "మెలకువగా ఉన్నారా?" every 15 minutes; no answer -> a loud beep and "బండి ఆపండి";
 *  - at the end, at home / duty: offers to send "క్షేమంగా చేరుకున్నాను" to the people he chose (sent only when he taps / says send).
 */
final class RideCare {
    private RideCare() {}

    static final String ACTION_AWAKE = "com.anil.jarvis.RIDE_AWAKE", ACTION_REACHED = "com.anil.jarvis.RIDE_REACHED",
            ACTION_REACHED_NO = "com.anil.jarvis.RIDE_REACHED_NO", ACTION_AWAKE_OK = "com.anil.jarvis.RIDE_AWAKE_OK";
    private static final int NOTE_AWAKE = 235;
    private static final long MIN = 60000L;
    private static final int NOTE_REACHED = 231;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_ride_care", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- start / end

    static synchronized void started(Context c) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        SharedPreferences s = sp(app);
        // already riding (a mark left by a ride Jarvis never saw end is not a ride)
        if (s.getBoolean("on", false) && now - s.getLong("at", 0) < 8 * 60 * MIN && (DriveService.running || new Prefs(app).driving())) return;
        Location l = here(app);
        SharedPreferences.Editor e = s.edit().putBoolean("on", true).putLong("at", now).remove("asked_at").putBoolean("fatigue", false);
        if (l != null) e.putString("start", l.getLatitude() + "," + l.getLongitude()); else e.remove("start");
        e.apply();
        Sleep.ride(app, true);
        weather(app, l);
        fatigue(app);
    }

    static synchronized void ended(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences s = sp(app);
        if (!s.getBoolean("on", false)) return;
        s.edit().putBoolean("on", false).putBoolean("fatigue", false).remove("asked_at").apply();
        cancelAwake(app);
        clearAwakeNote(app);
        Sleep.ride(app, false);
        new Thread(() -> { try { reached(app); } catch (Exception ignored) {} }, "jarvis-reached").start(); // waits for a fresh fix
    }

    static boolean riding(Context c) { return sp(c).getBoolean("on", false) && (new Prefs(c).driving() || DriveService.running); }

    private static Location here(Context c) {
        Location l = DriveService.last;
        if (l != null && System.currentTimeMillis() - l.getTime() < 10 * MIN) return l;
        l = Tools.lastLocation(c);
        return l != null && System.currentTimeMillis() - l.getTime() < 30 * MIN ? l : null;
    }

    // ---------------------------------------------------------------- weather at the start

    private static void weather(Context app, Location l) {
        if (!Drive.settings(app).getBoolean("ride_weather", true)) return;
        long now = System.currentTimeMillis();
        if (now - sp(app).getLong("weather_at", 0) < 3 * 60 * MIN) return;
        Location at = l; // only a recent fix: an old one may be another town
        if (at == null) return;
        sp(app).edit().putLong("weather_at", now).apply();
        new Thread(() -> {
            try {
                JSONObject w = Http.get(String.format(Locale.ENGLISH, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,precipitation&hourly=precipitation_probability&forecast_days=2&timezone=auto", at.getLatitude(), at.getLongitude()));
                JSONObject cur = w.optJSONObject("current");
                JSONObject h = w.getJSONObject("hourly");
                JSONArray times = h.getJSONArray("time"), prob = h.getJSONArray("precipitation_probability");
                LocalDateTime from = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0), to = from.plusHours(2);
                int max = -1;
                for (int i = 0; i < times.length(); i++) {
                    LocalDateTime t = LocalDateTime.parse(times.getString(i));
                    if (t.isBefore(from) || t.isAfter(to)) continue;
                    max = Math.max(max, prob.optInt(i, 0));
                }
                StringBuilder say = new StringBuilder();
                double rainNow = cur == null ? 0 : cur.optDouble("precipitation", 0), temp = cur == null ? Double.NaN : cur.optDouble("temperature_2m", Double.NaN);
                if (rainNow >= 0.3) say.append("ఇక్కడ ఇప్పుడు వర్షం పడుతోంది. రెయిన్‌కోట్ వేసుకుని, నెమ్మదిగా వెళ్లండి. ");
                else if (max >= 50) say.append("రాబోయే రెండు గంటల్లో వర్షం పడే అవకాశం ").append(max).append("%. రెయిన్‌కోట్ ఉందో చూసుకోండి. ");
                if (!Double.isNaN(temp) && temp >= 40) say.append("బయట ").append(Math.round(temp)).append(" డిగ్రీల ఎండ ఉంది. నీళ్లు తాగి బయలుదేరండి. ");
                if (say.length() > 0 && !CallControl.busyWithCall()) Announcer.say(app, say.toString().trim());
            } catch (Exception ignored) {}
        }, "ride-weather").start();
    }

    // ---------------------------------------------------------------- awake check after a duty

    private static void fatigue(Context app) {
        if (!Drive.settings(app).getBoolean("drive_fatigue", true)) return;
        long m = Duty.minutesSinceDuty(app);
        if (m < 0) return;
        if (Sleep.sleptSince(app, System.currentTimeMillis() - m * MIN) >= 180) return; // he slept after the duty
        sp(app).edit().putBoolean("fatigue", true).apply();
        Announcer.say(app, new Prefs(app).name() + ", డ్యూటీ చేసి వస్తున్నారు. నెమ్మదిగా, జాగ్రత్తగా వెళ్లండి; నిద్ర వస్తుంటే బండి ఆపి టీ తాగండి. "
                + "మధ్యలో మెలకువగా ఉన్నారా అని అడుగుతాను, 'Jarvis, ఉన్నాను' అని చెప్పండి.");
        scheduleAwake(app, 15);
    }

    private static PendingIntent awakePi(Context c) {
        return PendingIntent.getBroadcast(c, 232, new Intent(c, AlarmReceiver.class).setAction(ACTION_AWAKE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void scheduleAwake(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long when = System.currentTimeMillis() + minutes * MIN;
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, awakePi(c));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, awakePi(c));
        } catch (Exception e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, awakePi(c));
        }
    }

    private static void cancelAwake(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(awakePi(c));
    }

    /** Time to ask. */
    static void askAwake(Context c) {
        Context app = c.getApplicationContext();
        if (!sp(app).getBoolean("fatigue", false)) return;
        long now = System.currentTimeMillis();
        if (!riding(app) || now - sp(app).getLong("at", 0) > 8 * 60 * MIN) { ended(app); return; }
        scheduleAwake(app, 15);
        if (CallControl.busyWithCall()) return; // on a call he is clearly awake
        String name = new Prefs(app).name();
        boolean wrist = WatchHub.known(app) && WatchHub.watchHere(app) && !Boolean.FALSE.equals(WatchHub.worn(app)); // (he can tap "ఉన్నాను" on the watch)
        if (!WakeService.hearing && !wrist) { // "Jarvis" is not being listened for: he couldn't answer, so no test; just a word
            Announcer.say(app, name + ", డ్యూటీ అలసట ఉంటుంది. నిద్ర వస్తుంటే బండి పక్కకి ఆపి కాసేపు ఆగండి.");
            return;
        }
        sp(app).edit().putLong("asked_at", now).apply();
        Announcer.say(app, name + ", మెలకువగా ఉన్నారా? " + (WakeService.hearing ? "'Jarvis, ఉన్నాను' అనండి" : "వాచ్‌లో 'ఉన్నాను' నొక్కండి") + ".");
        awakeNote(app);
        android.os.PowerManager.WakeLock wl = null;
        try {
            android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:awake-check");
            wl.acquire(80000);
        } catch (Exception ignored) {}
        final android.os.PowerManager.WakeLock lock = wl;
        main.postDelayed(() -> {
            try {
                if (sp(app).getLong("asked_at", 0) == now && riding(app)) noAnswer(app);
            } finally {
                try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
            }
        }, 60000);
    }

    private static void noAnswer(Context app) {
        try {
            android.media.ToneGenerator g = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
            g.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2500);
            main.postDelayed(g::release, 3000);
        } catch (Exception ignored) {}
        main.postDelayed(() -> Announcer.say(app, new Prefs(app).name() + "! జవాబు రాలేదు. దయచేసి బండి పక్కకి ఆపి, కాసేపు విశ్రాంతి తీసుకోండి. టీ తాగి, ముఖం కడుక్కుని వెళ్లండి."), 3000);
        sp(app).edit().remove("asked_at").apply();
        scheduleAwake(app, 5); // asked again sooner
    }

    /** He answered (said "ఉన్నాను", called Jarvis, or tapped the button). */
    static void awake(Context c) {
        if (sp(c).getLong("asked_at", 0) == 0) return;
        sp(c).edit().remove("asked_at").apply();
        clearAwakeNote(c);
    }

    private static void awakeNote(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_awake", "మెలకువ చెక్", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent ok = PendingIntent.getBroadcast(c, 236, new Intent(c, AlarmReceiver.class).setAction(ACTION_AWAKE_OK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE_AWAKE, new Notification.Builder(c, "jarvis_awake").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("😴 మెలకువగా ఉన్నారా?").setContentText("'Jarvis, ఉన్నాను' అనండి లేదా ఇక్కడ నొక్కండి").setTimeoutAfter(2 * MIN)
                    .setAutoCancel(true).setContentIntent(ok).addAction(new Notification.Action.Builder(null, "✅ ఉన్నాను", ok).build()).build());
        } catch (Exception ignored) {}
    }

    private static void clearAwakeNote(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_AWAKE); } catch (Exception ignored) {}
    }

    static boolean asking(Context c) { return sp(c).getLong("asked_at", 0) != 0; }

    // ---------------------------------------------------------------- "I reached" message

    private static final String[] MY_HOME = {"ఇల్లు", "ఇంటి", "మా ఇల్లు", "నా ఇల్లు", "మా ఇంటి", "home", "my home", "house"};

    /** The saved place he is at now, his own home or duty (the nearest within 300 m), as {name, "ఇంటికి" / "డ్యూటీకి", "lat,lon"}, or null. */
    private static String[] atPlace(Context c, Location l) {
        String[] best = null;
        double bd = 300;
        for (JSONObject p : Places.all(c)) {
            if (!p.has("lat")) continue;
            String n = p.optString("name").trim().toLowerCase(Locale.ROOT);
            String kind = null;
            for (String h : MY_HOME) if (n.equals(h)) kind = "ఇంటికి"; // his own home, not "అమ్మ ఇల్లు"
            if (kind == null) for (String d : Duty.DUTY_PLACE) if (n.contains(d)) kind = "డ్యూటీకి";
            if (kind == null) continue;
            double d = Drive.meters(p.optDouble("lat"), p.optDouble("lon"), l.getLatitude(), l.getLongitude());
            if (d < bd) { bd = d; best = new String[]{p.optString("name"), kind, p.optDouble("lat") + "," + p.optDouble("lon")}; }
        }
        return best;
    }

    private static void reached(Context app) {
        String to = Drive.settings(app).getString("reached_to", "").trim();
        if (to.isEmpty()) return;
        Location l = Devices.freshFix(app); // where he is now, not where the ride began
        if (l == null || System.currentTimeMillis() - l.getTime() > 5 * MIN) return;
        String[] at = atPlace(app, l);
        if (at == null) return;
        String start = sp(app).getString("start", "");
        if (!start.isEmpty()) { // he set off from right there: nowhere was reached
            String[] a = start.split(","), b = at[2].split(",");
            if (Drive.meters(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(b[0]), Double.parseDouble(b[1])) < 1000) return;
        }
        String msg = at[1] + " క్షేమంగా చేరుకున్నాను. – " + new Prefs(app).name();
        sp(app).edit().putString("pending_msg", msg).putLong("pending_at", System.currentTimeMillis()).apply();
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_reached", "చేరుకున్నాను మెసేజ్", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent yes = PendingIntent.getBroadcast(app, 233, new Intent(app, AlarmReceiver.class).setAction(ACTION_REACHED), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent no = PendingIntent.getBroadcast(app, 234, new Intent(app, AlarmReceiver.class).setAction(ACTION_REACHED_NO), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE_REACHED, new Notification.Builder(app, "jarvis_reached").setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("✅ " + at[1] + " చేరుకున్నారు").setContentText(to + " కి: \"" + msg + "\" పంపనా?")
                    .setStyle(new Notification.BigTextStyle().bigText(to + " కి SMS: \"" + msg + "\" పంపనా?"))
                    .setTimeoutAfter(2 * 60 * MIN).setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, "📩 పంపు", yes).build())
                    .addAction(new Notification.Action.Builder(null, "వద్దు", no).build()).build());
        } catch (Exception ignored) {}
        if (!CallControl.busyWithCall()) Announcer.say(app, at[1] + " చేరుకున్నారు. '" + msg.substring(0, msg.indexOf('–')).trim() + "' అని " + to
                + " కి మెసేజ్ పంపనా? పంపాలంటే నోటిఫికేషన్‌లో పంపు నొక్కండి, లేదా 'Jarvis, చేరుకున్నానని పంపు' అనండి.");
    }

    static void dismissReached(Context c) {
        sp(c).edit().remove("pending_msg").remove("pending_at").apply();
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_REACHED); } catch (Exception ignored) {}
    }

    /** He said / tapped send, to the offer just made: the SMS to each chosen person. Never without that offer. */
    static synchronized JSONObject send(Context c) throws Exception {
        String to = Drive.settings(c).getString("reached_to", "").trim();
        if (to.isEmpty()) return new JSONObject().put("ok", false).put("error", "no_people").put("message", "Who should get it? (drive settings reached_to = names)");
        String msg = sp(c).getString("pending_msg", "");
        if (msg.isEmpty() || System.currentTimeMillis() - sp(c).getLong("pending_at", 0) > 2 * 60 * MIN)
            return new JSONObject().put("ok", false).put("error", "no_offer")
                    .put("message", "No 'reached' offer is waiting (it comes by itself when a ride ends at home / duty). For a message now, use send_sms / whatsapp_message.");
        if (c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return new JSONObject().put("ok", false).put("error", "no_sms_permission").put("message", "Jarvis needs the SMS permission to send it.");
        dismissReached(c); // used up before sending: a second tap / "పంపు" can't send it again
        android.telephony.SmsManager sm = android.os.Build.VERSION.SDK_INT >= 31 ? c.getSystemService(android.telephony.SmsManager.class)
                : android.telephony.SmsManager.getDefault();
        JSONArray sent = new JSONArray(), missing = new JSONArray();
        for (String who : to.split(",")) {
            String w = who.trim();
            if (w.isEmpty()) continue;
            String[] t = Sos.number(c, w);
            if (t == null) { missing.put(w); continue; }
            try { sm.sendMultipartTextMessage(t[1], null, sm.divideMessage(msg), null, null); sent.put(t[0]); }
            catch (Exception e) { missing.put(w); }
        }
        JSONObject o = new JSONObject().put("ok", sent.length() > 0).put("sent_to", sent).put("text", msg);
        if (missing.length() > 0) o.put("not_found", missing);
        return o;
    }

    /** From the notification's "పంపు" button. */
    static void sendFromNotification(Context c, Runnable done) {
        new Thread(() -> {
            try {
                JSONObject r = send(c);
                JSONArray s = r.optJSONArray("sent_to");
                Reminders.notify(c, "📩 చేరుకున్నాను మెసేజ్", s != null && s.length() > 0 ? "పంపాను: " + s.join(", ").replace("\"", "")
                        : "పంపలేకపోయాను: " + r.optString("message", "కాంటాక్ట్ దొరకలేదు"), NOTE_REACHED + 1);
            } catch (Exception ignored) {
            } finally {
                if (done != null) done.run();
            }
        }, "jarvis-reached").start();
    }
}
