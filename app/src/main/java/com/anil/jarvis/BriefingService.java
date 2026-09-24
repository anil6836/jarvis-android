package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Prepares the daily morning briefing in the background, then shows and (optionally) speaks it. */
public class BriefingService extends Service {
    private static final String CHANNEL = "jarvis_briefing";
    private static final int NOTE_WORKING = 21, NOTE_RESULT = 22;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, BriefingService.class));
        } catch (Exception e) {
            // Android may refuse background starts; offer a tap-to-hear notification instead.
            tapToHear(c);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = base(this, "ఉదయం బ్రీఫింగ్ సిద్ధం చేస్తున్నాను…", null).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_WORKING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTE_WORKING, n);
        } catch (Exception e) {
            tapToHear(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                String text = compose(this);
                Store.get(this).addChat("assistant", text, false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTE_RESULT, base(this, "శుభోదయం, " + new Prefs(this).name() + " ☀️", text).setAutoCancel(true).build());
                if (new Prefs(this).briefingSpeak()) {
                    Announcer.say(this, text);
                    SystemClock.sleep(Math.min(90000, 4000 + text.length() * 90L)); // keep running while it is spoken
                }
            } catch (Exception e) {
                tapToHear(this);
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "jarvis-briefing").start();
        return START_NOT_STICKY;
    }

    /** Collects today's facts and asks the model for a short spoken Telugu briefing. */
    static String compose(Context c) throws Exception {
        Prefs p = new Prefs(c);
        Store s = Store.get(c);
        StringBuilder data = new StringBuilder();
        data.append("Now: ").append(new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(new Date())).append('\n');
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { data.append("Weather: ").append(Tools.weatherJson(c, "")).append('\n'); } catch (Exception ignored) {}
        }
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            try { data.append("Calendar today: ").append(Tools.calendarJson(c, 1)).append('\n'); } catch (Exception ignored) {}
        }
        long now = System.currentTimeMillis(), endOfDay = now + 18L * 60 * 60 * 1000;
        StringBuilder rem = new StringBuilder();
        for (JSONObject r : s.reminders()) {
            long at = r.optLong("at");
            if (!r.optBoolean("done") && at >= now && at <= endOfDay) {
                rem.append("- ").append(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(at))).append(' ').append(r.optString("text")).append('\n');
            }
        }
        data.append("Reminders today:\n").append(rem.length() == 0 ? "(none)\n" : rem);
        StringBuilder mis = new StringBuilder();
        List<JSONObject> ms = s.missions();
        int k = 0;
        for (JSONObject m : ms) if (!m.optBoolean("done") && k++ < 8) mis.append("- ").append(m.optString("text")).append('\n');
        data.append("Active missions:\n").append(mis.length() == 0 ? "(none)\n" : mis);

        String system = new Brain(p, s, null).systemPrompt();
        String prompt = "Give " + p.name() + " his spoken morning briefing now, in natural Telugu, 5-7 short sentences, no lists or markdown: "
                + "a warm greeting, today's date, today's weather, what is on his calendar and reminders today, the 2-3 most important missions, "
                + (p.webSearch() ? "and 2 short top news headlines for India / Telangana / Andhra Pradesh from a quick web search. " : "")
                + "End with one encouraging line.\n\nData:\n" + data;
        return Brain.oneShot(p, system, prompt, null, p.webSearch());
    }

    private static Notification.Builder base(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis ఉదయం బ్రీఫింగ్", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 5, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle(title)
                .setContentIntent(open);
        if (text != null) b.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text));
        return b;
    }

    private static void tapToHear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        PendingIntent pi = PendingIntent.getActivity(c, 6,
                new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_BRIEF, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTE_RESULT, base(c, "శుభోదయం, " + new Prefs(c).name() + " ☀️", "మీ ఉదయం బ్రీఫింగ్ వినడానికి నొక్కండి")
                .setContentIntent(pi).setAutoCancel(true).build());
    }
}
