package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * "నా స్టాప్ వచ్చేముందు లేపు": on a bus or a train, a loud alarm a couple of km before his stop.
 * The phone's own GPS is watched by {@link StopAlarmService} (no internet needed once it is set);
 * when he is that close, the alarm screen rings with his alarm song until he stops it.
 */
final class StopAlarm {
    private StopAlarm() {}

    static final String ACTION_OFF = "com.anil.jarvis.STOP_ALARM_OFF";
    static final int NOTE_WATCH = 247, NOTE_RING = 248, NOTE_INFO = 249;
    static final long MAX_MS = 40 * 3600_000L; // a journey longer than this (the longest trains): the watch ends by itself

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_stop_alarm", Context.MODE_PRIVATE); }

    /** The stop being watched: {place, lat, lon, km, since}, or null. */
    static JSONObject current(Context c) {
        try {
            String s = sp(c).getString("target", "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    static void start(Context c, String place, double lat, double lon, double km) throws Exception {
        JSONObject t = new JSONObject().put("place", place).put("lat", lat).put("lon", lon).put("km", km).put("since", System.currentTimeMillis());
        sp(c).edit().putString("target", t.toString()).apply();
        Intent i = new Intent(c, StopAlarmService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { // Android did not let it start now: nothing is watching, so nothing is saved
            sp(c).edit().remove("target").apply();
            throw e;
        }
    }

    /** The saved stop only (the service could not run). */
    static void forget(Context c) { sp(c).edit().remove("target").apply(); }

    /** Switched off (by him, after it rang, or after 40 hours). */
    static void off(Context c) {
        sp(c).edit().remove("target").apply();
        try { c.stopService(new Intent(c, StopAlarmService.class)); } catch (Exception ignored) {}
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) { nm.cancel(NOTE_WATCH); nm.cancel(NOTE_RING); }
    }

    static String km(double meters) {
        return meters >= 10_000 ? String.valueOf(Math.round(meters / 1000)) : String.format(Locale.ENGLISH, "%.1f", meters / 1000);
    }

    /** He is near his stop: a ringing full-screen alarm (and the alarm screen, which plays his alarm song). */
    static void ring(Context c, String place, double meters) {
        sp(c).edit().remove("target").apply();
        String label = "📍 " + place + " దగ్గరకి వచ్చారు · " + km(meters) + " కి.మీ.";
        Intent screen = new Intent(c, AlarmActivity.class).putExtra(AlarmActivity.EXTRA_STOP, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("jarvis_stop_alarm", "స్టాప్ అలారం", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                        new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ch.enableVibration(true);
                ch.setBypassDnd(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
                PendingIntent full = PendingIntent.getActivity(c, 243, screen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = new Notification.Builder(c, "jarvis_stop_alarm")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(label)
                        .setContentText("మీ స్టాప్ వస్తోంది. సామాను తీసుకుని సిద్ధంగా ఉండండి.")
                        .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setFullScreenIntent(full, true).setContentIntent(full).setOngoing(true)
                        .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", offIntent(c)).build()).build();
                n.flags |= Notification.FLAG_INSISTENT; // rings until he answers it
                nm.notify(NOTE_RING, n);
            }
        } catch (Exception ignored) {}
        try { c.startActivity(screen); } catch (Exception ignored) {} // with "Appear on top" it opens straight away
    }

    /** The alarm screen is up and playing: the notification stays (for ⏹) but stops ringing. */
    static void quiet(Context c, String label) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_stop_alarm_quiet", "స్టాప్ అలారం (మోగుతోంది)", NotificationManager.IMPORTANCE_LOW));
            nm.notify(NOTE_RING, new Notification.Builder(c, "jarvis_stop_alarm_quiet")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(label).setContentText("ఆపడానికి నొక్కండి")
                    .setOngoing(true).setContentIntent(offIntent(c))
                    .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", offIntent(c)).build()).build());
        } catch (Exception ignored) {}
    }

    static PendingIntent offIntent(Context c) {
        return PendingIntent.getBroadcast(c, 244, new Intent(c, AlarmReceiver.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** ⏹ on the notification: the ringing stops everywhere. */
    static void stoppedFromNotification(Context c) {
        off(c);
        AlarmActivity.stopRinging();
    }
}
