package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis's own alarm: wakes him with a song from his phone (or the alarm tone), then says good morning with the weather
 * and whether today is a duty day. Days: daily, once, weekdays, weekend, duty (only his duty days), off (only days off).
 */
final class SongAlarm {
    private SongAlarm() {}

    static final String KEY = "song_alarms";
    static final String EXTRA_ID = "alarm_id";
    static final String EXTRA_COUNT = "alarm_snoozes";
    static final String ACTION_RING = "com.anil.jarvis.SONG_ALARM";
    static final String ACTION_STOP = "com.anil.jarvis.SONG_ALARM_STOP";
    static final String ACTION_SNOOZE = "com.anil.jarvis.SONG_ALARM_SNOOZE";
    static final int NOTE_ID = 93;

    static List<JSONObject> all(Context c) { return Notes.list(c, KEY); }

    static String days(String d) {
        String s = d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
        if (s.contains("once") || s.contains("ఒక్కసారి") || s.contains("రేపు")) return "once";
        if (s.contains("weekday") || s.contains("సోమ") && s.contains("శుక్ర")) return "weekdays";
        if (s.contains("weekend") || s.contains("శని") && s.contains("ఆది")) return "weekend";
        if (s.contains("off") || s.contains("సెలవు")) return "off";
        if (s.contains("duty") || s.contains("డ్యూటీ")) return "duty";
        return "daily";
    }

    static String daysTe(String d) {
        switch (d) {
            case "once": return "ఒక్కసారి";
            case "weekdays": return "సోమ-శుక్ర";
            case "weekend": return "శని, ఆది";
            case "duty": return "డ్యూటీ రోజుల్లో మాత్రమే";
            case "off": return "సెలవు రోజుల్లో మాత్రమే";
            default: return "రోజూ";
        }
    }

    /** The next time this alarm rings after now, or null. */
    static LocalDateTime next(Context c, JSONObject o, LocalDateTime now) {
        int h = o.optInt("hour"), m = o.optInt("minute");
        String d = o.optString("days", "daily");
        Duty.Roster r = null;
        if (d.equals("duty") || d.equals("off")) { r = Duty.load(c); if (!Duty.ready(r)) r = null; }
        for (int i = 0; i < 40; i++) {
            LocalDate day = now.toLocalDate().plusDays(i);
            LocalDateTime t = day.atTime(h, m);
            if (!t.isAfter(now)) continue;
            DayOfWeek w = day.getDayOfWeek();
            boolean weekend = w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
            boolean ok;
            switch (d) {
                // a 48-hour duty: only the morning he leaves home is a "duty morning"; the next morning he is at work,
                // and the morning after that he comes home at noon: neither is a morning at home
                case "duty": ok = r == null || Duty.startsDuty(r, day); break;
                case "off": ok = r == null || Duty.homeAllDay(r, day); break;
                default: ok = true;
            }
            if (d.equals("weekdays")) ok = !weekend;
            if (d.equals("weekend")) ok = weekend;
            if (ok) return t;
        }
        return null;
    }

    /** The alarm goes to the receiver, which rings (notification with a full-screen alarm, and the song screen). */
    private static PendingIntent pi(Context c, String id, boolean snooze, int count) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_RING).putExtra(EXTRA_ID, id).putExtra(EXTRA_COUNT, count)
                .setData(android.net.Uri.parse("jarvis-alarm://" + id + (snooze ? "/snooze" : "")));
        return PendingIntent.getBroadcast(c, (snooze ? "snooze" : "alarm").hashCode() ^ id.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Intent screen(Context c, String id, int count) {
        return new Intent(c, AlarmActivity.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_COUNT, count)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** It is time: set the next one, ring with a full-screen alarm notification (it keeps ringing even if the screen can't open). */
    static void fire(Context c, String id, int count) {
        if (count == 0) rang(c, id);
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm != null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel("jarvis_song_alarm", "పాట అలారం", android.app.NotificationManager.IMPORTANCE_HIGH);
                android.net.Uri tone = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
                ch.setSound(tone, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ch.enableVibration(true);
                ch.setBypassDnd(true);
                ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
                PendingIntent full = PendingIntent.getActivity(c, 94, screen(c, id, count), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent stop = PendingIntent.getBroadcast(c, 95, new Intent(c, AlarmReceiver.class).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent snooze = PendingIntent.getBroadcast(c, 96, new Intent(c, AlarmReceiver.class).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, id)
                                .putExtra(EXTRA_COUNT, count), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                android.app.Notification n = new android.app.Notification.Builder(c, "jarvis_song_alarm")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("⏰ శుభోదయం!").setContentText("ఆపడానికి / 5 నిమిషాలకి నొక్కండి")
                        .setCategory(android.app.Notification.CATEGORY_ALARM).setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .setFullScreenIntent(full, true).setContentIntent(full).setOngoing(true)
                        .addAction(new android.app.Notification.Action.Builder(null, "⏹ ఆపు", stop).build())
                        .addAction(new android.app.Notification.Action.Builder(null, "😴 5 నిమిషాలు", snooze).build()).build();
                n.flags |= android.app.Notification.FLAG_INSISTENT; // keeps ringing until he answers it
                nm.notify(NOTE_ID, n);
            }
        } catch (Exception ignored) {}
        try { c.startActivity(screen(c, id, count)); } catch (Exception ignored) {} // with "Appear on top" it opens straight away
    }

    static void clearRinging(Context c) {
        try { android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class); if (nm != null) nm.cancel(NOTE_ID); } catch (Exception ignored) {}
    }

    private static void at(Context c, long when, PendingIntent op) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent show = PendingIntent.getActivity(c, 92, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), op); // exact, even in Doze
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, op); // exact alarms not allowed: a minute or so late at worst
        }
    }

    static void schedule(Context c, JSONObject o) {
        cancelOnly(c, o.optString("id"));
        if (!o.optBoolean("on", true)) return;
        LocalDateTime t = next(c, o, LocalDateTime.now());
        if (t == null) return;
        at(c, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi(c, o.optString("id"), false, 0));
    }

    /** Snooze; count = how many times already (at most 3 in a row, then it stops). */
    static void snooze(Context c, String id, int minutes, int count) {
        if (count >= 3) return;
        at(c, System.currentTimeMillis() + minutes * 60000L, pi(c, id, true, count + 1));
    }

    private static void cancelOnly(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(pi(c, id, false, 0));
        am.cancel(pi(c, id, true, 0));
    }

    static JSONObject add(Context c, int hour, int minute, String days, String label) throws Exception {
        return add(c, hour, minute, days, label, "");
    }

    /** station: wake with this radio station of his (its name as in his list); "" = the song / alarm tone. */
    static JSONObject add(Context c, int hour, int minute, String days, String label, String station) throws Exception {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        String d = days(days);
        // the same time and days again: turn that one on instead of a second copy
        List<JSONObject> l = all(c);
        for (JSONObject x : l) {
            if (x.optInt("hour") == hour && x.optInt("minute") == minute && x.optString("days").equals(d)) {
                x.put("on", true);
                if (label != null && !label.trim().isEmpty()) x.put("label", label.trim());
                x.put("station", station == null ? "" : station);
                Notes.save(c, KEY, l, 30);
                schedule(c, x);
                return x;
            }
        }
        JSONObject o = new JSONObject().put("id", Notes.id("al")).put("hour", hour).put("minute", minute)
                .put("days", d).put("label", label == null ? "" : label.trim()).put("on", true).put("station", station == null ? "" : station);
        Notes.add(c, KEY, o, 30);
        schedule(c, o);
        return o;
    }

    static JSONObject find(Context c, String idOrTime) {
        if (idOrTime == null) return null;
        String q = idOrTime.trim();
        for (JSONObject o : all(c)) {
            String t = String.format(Locale.ENGLISH, "%d:%02d", o.optInt("hour"), o.optInt("minute"));
            if (o.optString("id").equalsIgnoreCase(q) || t.equals(q.replaceFirst("^0", "")) || o.optString("label").equalsIgnoreCase(q)) return o;
        }
        return null;
    }

    static boolean remove(Context c, String idOrTime) {
        JSONObject o = find(c, idOrTime);
        if (o == null) return false;
        cancelOnly(c, o.optString("id"));
        return Notes.remove(c, KEY, "id", o.optString("id"));
    }

    /** After it rang: the next time (or off, for a one-time alarm). */
    static void rang(Context c, String id) {
        List<JSONObject> l = all(c);
        for (int i = 0; i < l.size(); i++) {
            JSONObject o = l.get(i);
            if (!o.optString("id").equals(id)) continue;
            if ("once".equals(o.optString("days"))) {
                try { o.put("on", false); } catch (Exception ignored) {}
                Notes.save(c, KEY, l, 30);
            } else {
                schedule(c, o);
            }
            return;
        }
    }

    static void rescheduleAll(Context c) {
        for (JSONObject o : all(c)) schedule(c, o);
    }

    static JSONArray listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : all(c)) {
            LocalDateTime n = o.optBoolean("on", true) ? next(c, o, LocalDateTime.now()) : null;
            a.put(new JSONObject().put("id", o.optString("id")).put("time", String.format(Locale.ENGLISH, "%d:%02d", o.optInt("hour"), o.optInt("minute")))
                    .put("days", daysTe(o.optString("days"))).put("label", o.optString("label")).put("on", o.optBoolean("on", true))
                    .put("next", n == null ? "-" : Duty.day(n.toLocalDate()) + " " + String.format(Locale.ENGLISH, "%d:%02d", n.getHour(), n.getMinute())));
        }
        return a;
    }
}
