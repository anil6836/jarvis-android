package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Around each ride (the bike's / car's Bluetooth, or the drive alerts):
 *  - at the start, rain in the next two hours or great heat where he is;
 *  - at the end, at home / duty: offers to send "క్షేమంగా చేరుకున్నాను" to the people he chose (sent only when he taps / says send).
 * (The "మెలకువగా ఉన్నారా?" check is the car's only now: CarCare. Anil, 18 Oct: "బైక్ మీద వద్దు, కారు లో".)
 */
final class RideCare {
    private RideCare() {}

    static final String ACTION_REACHED = "com.anil.jarvis.RIDE_REACHED", ACTION_REACHED_NO = "com.anil.jarvis.RIDE_REACHED_NO";
    /** (The bike's old awake check, removed 18 Oct: an alarm or button left from before is only cleared.) */
    static final String ACTION_AWAKE = "com.anil.jarvis.RIDE_AWAKE", ACTION_AWAKE_OK = "com.anil.jarvis.RIDE_AWAKE_OK";
    private static final long MIN = 60000L;
    private static final int NOTE_REACHED = 231;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_ride_care", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- start / end

    static void started(Context c) { started(c, true); }

    /** weather: the rain / heat word at the start (not when a spoken "కారు మోడ్" is being answered by Jarvis himself). */
    static synchronized void started(Context c, boolean weather) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        SharedPreferences s = sp(app);
        // already riding (a mark left by a ride Jarvis never saw end is not a ride)
        if (s.getBoolean("on", false) && now - s.getLong("at", 0) < 8 * 60 * MIN && (DriveService.running || new Prefs(app).driving())) return;
        Location l = here(app);
        SharedPreferences.Editor e = s.edit().putBoolean("on", true).putLong("at", now);
        if (l != null) e.putString("start", l.getLatitude() + "," + l.getLongitude()); else e.remove("start");
        e.apply();
        Sleep.ride(app, true);
        if (weather) weather(app, l);
    }

    static synchronized void ended(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences s = sp(app);
        if (!s.getBoolean("on", false)) return;
        s.edit().putBoolean("on", false).apply();
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
                boolean car = CarCare.on(app); // (in the car: no raincoat words)
                double rainNow = cur == null ? 0 : cur.optDouble("precipitation", 0), temp = cur == null ? Double.NaN : cur.optDouble("temperature_2m", Double.NaN);
                if (rainNow >= 0.3) say.append(car ? "ఇక్కడ ఇప్పుడు వర్షం పడుతోంది. వైపర్లు, లైట్లు వేసుకుని నెమ్మదిగా వెళ్లండి. "
                        : "ఇక్కడ ఇప్పుడు వర్షం పడుతోంది. రెయిన్‌కోట్ వేసుకుని, నెమ్మదిగా వెళ్లండి. ");
                else if (max >= 50) say.append("రాబోయే రెండు గంటల్లో వర్షం పడే అవకాశం ").append(max).append(car ? "%. జాగ్రత్తగా వెళ్లండి. " : "%. రెయిన్‌కోట్ ఉందో చూసుకోండి. ");
                if (!Double.isNaN(temp) && temp >= 40) say.append("బయట ").append(Math.round(temp)).append(" డిగ్రీల ఎండ ఉంది. నీళ్లు తాగి బయలుదేరండి. ");
                if (say.length() > 0 && !CallControl.busyWithCall() && !CarBox.phoneQuiet(app)) Announcer.say(app, say.toString().trim()); // (the car's box says it)
            } catch (Exception ignored) {}
        }, "ride-weather").start();
    }

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
