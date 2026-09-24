package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/** Schedules reminders and the daily morning briefing with the system alarm clock. */
final class Reminders {
    static final String ACTION_FIRE = "com.anil.jarvis.REMINDER";
    static final String ACTION_BRIEFING = "com.anil.jarvis.BRIEFING";
    private static final String CHANNEL = "jarvis_reminders";

    private Reminders() {}

    // ---------------------------------------------------------------- reminders

    static void schedule(Context c, JSONObject r) {
        long at = r.optLong("at");
        if (r.optBoolean("done") || at <= System.currentTimeMillis()) return;
        setAlarm(c, at, pending(c, ACTION_FIRE, r.optString("id")));
    }

    static void cancel(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(c, ACTION_FIRE, id));
    }

    /** After a reboot or an app update: put every future reminder back, and tell about any missed. */
    static void rescheduleAll(Context c) {
        Store s = Store.get(c);
        long now = System.currentTimeMillis();
        for (JSONObject r : s.reminders()) {
            if (r.optBoolean("done")) continue;
            long at = r.optLong("at");
            if (at > now) {
                schedule(c, r);
            } else if (now - at < 12L * 60 * 60 * 1000) {
                s.markReminderDone(r.optString("id"));
                notify(c, "తప్పిపోయిన రిమైండర్", r.optString("text"), r.optString("id").hashCode());
            }
        }
        scheduleBriefing(c);
    }

    // ---------------------------------------------------------------- morning briefing

    static void scheduleBriefing(Context c) {
        PendingIntent pi = pending(c, ACTION_BRIEFING, "daily");
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(pi);
        Prefs p = new Prefs(c);
        if (!p.briefingOn()) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, p.briefingHour());
        cal.set(Calendar.MINUTE, p.briefingMinute());
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30000) cal.add(Calendar.DAY_OF_MONTH, 1);
        setAlarm(c, cal.getTimeInMillis(), pi);
    }

    // ---------------------------------------------------------------- helpers

    private static PendingIntent pending(Context c, String action, String id) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(action)
                .putExtra("id", id)
                .setData(Uri.parse("jarvis://" + action + "/" + id));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) {
                PendingIntent show = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void notify(Context c, String title, String text, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis రిమైండర్లు", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, 4, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        nm.notify(id, n);
    }
}
