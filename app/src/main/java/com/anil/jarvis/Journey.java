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

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * W81 "నాతో ఉండు" / the journey guard, started only by him: where he is going and about when he should be there. On
 * arrival: "చేరుకున్నారు, ఇంటికి చెప్పమంటారా?" (sent only on his tap). Late by 15 minutes, or every 20 minutes at night
 * (10 pm - 5 am): "అంతా బాగుందా?" on his wrist and phone; no answer in 5 minutes -> asked again, louder; no answer in 5
 * more -> his SOS (location SMS to his family, a call to the first), because he asked Jarvis to watch over him.
 */
final class Journey {
    private Journey() {}

    private static final long MIN = 60_000L;
    static final String ACTION_TICK = "com.anil.jarvis.JOURNEY_TICK", ACTION_OK = "com.anil.jarvis.JOURNEY_OK",
            ACTION_SEND = "com.anil.jarvis.JOURNEY_SEND", ACTION_STOP = "com.anil.jarvis.JOURNEY_STOP";
    private static final int REQ = 301, NOTE = 302, NOTE_REACHED = 303;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_journey", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return sp(c).getBoolean("on", false); }

    /** He starts it: to a place (home if none) and in about `minutes` (0 = worked out from the distance). */
    static String start(Context c, String place, int minutes) {
        JSONObject p = Travel.target(c, place == null || place.trim().isEmpty() ? "ఇల్లు" : place);
        Location l = Travel.here(c);
        long now = System.currentTimeMillis();
        int m = minutes;
        if (m <= 0 && p != null && l != null) {
            double km = Drive.meters(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon")) / 1000.0 * 1.3;
            m = (int) Math.max(10, Math.round(km / 25.0 * 60)); // (about 25 km/h with stops)
        }
        if (m <= 0) m = 30;
        SharedPreferences.Editor e = sp(c).edit().putBoolean("on", true).putLong("start", now).putLong("eta", now + m * MIN)
                .putInt("asked", 0).putLong("asked_at", 0).putLong("night_at", now);
        if (p != null) e.putString("to", p.optString("name")).putFloat("lat", (float) p.optDouble("lat")).putFloat("lon", (float) p.optDouble("lon"));
        else e.putString("to", place == null ? "" : place).remove("lat").remove("lon");
        e.apply();
        arm(c, 5);
        String to = p == null ? (place == null || place.isEmpty() ? "మీరు వెళ్లే చోటు" : place) : p.optString("name");
        String eta = Offline.sayWhen(LocalDateTime.now().plusMinutes(m), LocalDateTime.now());
        return "సరే, మీతో ఉంటాను: " + to + " కి సుమారు " + eta + " కి చేరాలి." + (p == null ? " (ఆ చోటు సేవ్ అయి లేదు, అందుకే చేరుకున్నది తెలియదు: చేరాక \"చేరుకున్నాను\" అనండి.)" : "")
                + " ఆలస్యమైతే, రాత్రి అయితే మధ్యమధ్యలో \"అంతా బాగుందా?\" అని అడుగుతాను; జవాబు లేకపోతే మీ వాళ్లకి SOS వెళ్తుంది. \"జర్నీ అయిపోయింది\" అంటే ఆపుతాను.";
    }

    static String stop(Context c, boolean arrived) {
        if (!on(c)) return "జర్నీ గార్డ్ ఆన్‌లో లేదు.";
        sp(c).edit().putBoolean("on", false).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(tickPi(c));
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
        return arrived ? "సరే, చేరుకున్నారు. జర్నీ గార్డ్ ఆపాను." : "సరే, జర్నీ గార్డ్ ఆపాను.";
    }

    private static PendingIntent tickPi(Context c) {
        return PendingIntent.getBroadcast(c, REQ, new Intent(c, AlarmReceiver.class).setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void arm(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = System.currentTimeMillis() + minutes * MIN;
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickPi(c)); } catch (Exception e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickPi(c)); }
    }

    /** Every ~5 minutes while on (its own alarm; Proactive too). Background thread. */
    static void tick(Context c) {
        if (!on(c)) return;
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis();
        if (now - s.getLong("start", now) > 12 * 60 * MIN) { stop(c, false); return; } // (forgotten: 12 hours at most)
        arm(c, 5);
        // arrived?
        if (s.contains("lat")) {
            Location l = Travel.here(c);
            if (l != null && now - l.getTime() < 10 * MIN && Drive.meters(l.getLatitude(), l.getLongitude(), s.getFloat("lat", 0), s.getFloat("lon", 0)) < 250) {
                arrived(c);
                return;
            }
        }
        int asked = s.getInt("asked", 0);
        long askedAt = s.getLong("asked_at", 0);
        if (asked > 0) { // waiting for his answer
            if (now - askedAt < 5 * MIN) return;
            if (asked == 1) { ask(c, 2); return; }
            s.edit().putInt("asked", 0).putLong("asked_at", 0).putBoolean("on", false).apply();
            try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
            CrashAlert.sosNow(c, "ప్రయాణంలో ఉన్నాను (Jarvis జర్నీ గార్డ్), 10 నిమిషాలుగా జవాబు ఇవ్వడం లేదు. ఒకసారి ఫోన్ చేసి చూడండి");
            return;
        }
        int h = LocalDateTime.now().getHour();
        boolean late = now > s.getLong("eta", now) + 15 * MIN && now - s.getLong("late_ok", 0) > 20 * MIN;
        boolean night = (h >= 22 || h < 5) && now - s.getLong("night_at", 0) > 20 * MIN;
        if (late || night) ask(c, 1);
    }

    private static void ask(Context c, int n) {
        sp(c).edit().putInt("asked", n).putLong("asked_at", System.currentTimeMillis()).apply();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_crash", "ప్రమాదం గుర్తింపు", NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            PendingIntent ok = PendingIntent.getBroadcast(c, REQ + 1, new Intent(c, AlarmReceiver.class).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_crash").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(n == 1 ? "🛡️ అంతా బాగుందా?" : "🛡️ జవాబు రాలేదు: బాగున్నారా?")
                    .setContentText("బాగుంటే నొక్కండి. 5 నిమిషాల్లో జవాబు లేకపోతే " + (n == 1 ? "మళ్లీ అడుగుతాను" : "మీ వాళ్లకి SOS వెళ్తుంది"))
                    .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setContentIntent(ok)
                    .addAction(new Notification.Action.Builder(null, "✅ బాగున్నాను", ok).build()).build());
        } catch (Exception ignored) {}
        if (!CallControl.busyWithCall()) Announcer.say(c, new Prefs(c).name() + (n == 1 ? ", అంతా బాగుందా? బాగుంటే నోటిఫికేషన్ లేదా వాచ్‌లో నొక్కండి." : ", జవాబు రాలేదు. బాగున్నారా? 5 నిమిషాల్లో నొక్కకపోతే SOS పంపుతాను."));
        if (n == 2) try { new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100).startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000); } catch (Exception ignored) {}
    }

    /** He answered "బాగున్నాను". */
    static void ok(Context c) {
        long now = System.currentTimeMillis();
        sp(c).edit().putInt("asked", 0).putLong("asked_at", 0).putLong("late_ok", now).putLong("night_at", now).apply();
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
    }

    /** At the place: the guard ends; "ఇంటికి చెప్పమంటారా?" with one tap (to the people he chose for "చేరుకున్నాను"). */
    static void arrived(Context c) {
        String to = sp(c).getString("to", "");
        stop(c, true);
        String who = Drive.settings(c).getString("reached_to", "").trim();
        String text = "✅ " + (to.isEmpty() ? "చేరుకున్నారు" : to + " చేరుకున్నారు") + (who.isEmpty() ? "" : ". " + who + " కి \"క్షేమంగా చేరుకున్నాను\" పంపనా?");
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_reached", "చేరుకున్నాను మెసేజ్", NotificationManager.IMPORTANCE_HIGH));
            Notification.Builder b = new Notification.Builder(c, "jarvis_reached").setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("🛡️ జర్నీ పూర్తి").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setTimeoutAfter(60 * MIN);
            if (!who.isEmpty()) b.addAction(new Notification.Action.Builder(null, "📩 పంపు",
                    PendingIntent.getBroadcast(c, REQ + 2, new Intent(c, AlarmReceiver.class).setAction(ACTION_SEND), PendingIntent.FLAG_IMMUTABLE)).build());
            nm.notify(NOTE_REACHED, b.build());
        } catch (Exception ignored) {}
    }

    /** His tap "📩 పంపు": "క్షేమంగా చేరుకున్నాను" by SMS to each of his chosen people. */
    static void sendReached(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_REACHED); } catch (Exception ignored) {}
        String who = Drive.settings(c).getString("reached_to", "").trim();
        if (who.isEmpty() || c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        new Thread(() -> {
            StringBuilder sent = new StringBuilder();
            for (String w : who.split("\\s*,\\s*")) {
                String[] n = Sos.number(c, w);
                if (n == null) continue;
                try {
                    android.telephony.SmsManager sm = c.getSystemService(android.telephony.SmsManager.class);
                    sm.sendTextMessage(n[1], null, "క్షేమంగా చేరుకున్నాను. – " + new Prefs(c).name(), null, null);
                    sent.append(sent.length() > 0 ? ", " : "").append(n[0]);
                } catch (Exception ignored) {}
            }
            Reminders.notify(c, "📩 చేరుకున్నాను", sent.length() == 0 ? "పంపలేకపోయాను." : sent + " కి పంపాను.", NOTE_REACHED + 1);
        }, "journey-reached").start();
    }

    /** Words (pure: tested on a desk): {"start", place, minutes} / {"stop"} / {"arrived"}; null when it isn't the journey guard. */
    static String[] asks(String bare) {
        String t = bare == null ? "" : bare.trim().toLowerCase(Locale.ROOT);
        if (t.matches("(?s).*(నాతో ఉండు|నాతో పాటు ఉండు|జర్నీ గార్డ్|journey guard|stay with me).*")) {
            if (t.matches("(?s).*(ఆపు|ఆఫ్|వద్దు|stop|off).*")) return new String[]{"stop"};
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(నిమిషాల|నిమిషాలు|ని|min|గంట|గంటల|గంటలు|hour)").matcher(t);
            int mins = 0;
            if (m.find()) { mins = Integer.parseInt(m.group(1)); if (m.group(2).startsWith("గంట") || m.group(2).startsWith("hour")) mins *= 60; }
            String place = t.contains("ఇంటికి") || t.contains("home") ? "ఇల్లు" : t.contains("డ్యూటీ") || t.contains("duty") ? "డ్యూటీ" : "";
            return new String[]{"start", place, String.valueOf(mins)};
        }
        if (t.matches("(?s).*(జర్నీ|ప్రయాణం)\\s*(అయిపోయింది|అయింది|ముగిసింది|ఆపు).*")) return new String[]{"stop"};
        if (t.matches("(?s)^(చేరుకున్నాను|ఇంటికి చేరుకున్నాను|reached)$")) return new String[]{"arrived"};
        return null;
    }

    static String answer(Context c, String[] a) {
        switch (a[0]) {
            case "start": return start(c, a[1], Integer.parseInt(a[2]));
            case "stop": return stop(c, false);
            case "arrived": if (!on(c)) return null; arrived(c); return "సరే, చేరుకున్నారు. జర్నీ గార్డ్ ఆపాను.";
            default: return null;
        }
    }
}
