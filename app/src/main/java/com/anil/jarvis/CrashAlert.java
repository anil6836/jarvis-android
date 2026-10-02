package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * A hard knock while moving, then standing still: "బాగున్నారా?" with a loud beep and a full-screen
 * "నేను బాగున్నాను" button. No answer in 60 seconds: the SOS SMS with his location goes to his SOS contacts.
 */
final class CrashAlert {
    private CrashAlert() {}

    static final String ACTION_OK = "com.anil.jarvis.CRASH_OK", ACTION_SEND = "com.anil.jarvis.CRASH_SEND";
    static final int SECONDS = 60;
    private static final int NOTE = 151;
    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean active;
    static volatile long startedAt;
    private static ToneGenerator beeper;
    private static Context app;

    private static final Runnable beep = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                if (beeper == null) beeper = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                beeper.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600);
            } catch (Exception ignored) {}
            main.postDelayed(this, 2500);
        }
    };
    private static final Runnable timeUp = () -> { if (app != null) send(app, false); };

    static void start(Context c) {
        main.post(() -> {
            if (active) return;
            if (new Prefs(c).sosContacts().trim().isEmpty()) {
                Announcer.say(c, "పెద్ద దెబ్బ తగిలినట్టు అనిపించింది. బాగున్నారా? మీ SOS కాంటాక్ట్స్ సెట్టింగ్స్‌లో లేరు, అవసరమైతే 112 కి కాల్ చేయండి.");
                return;
            }
            active = true;
            startedAt = System.currentTimeMillis();
            app = c.getApplicationContext();
            Announcer.say(app, new Prefs(app).name() + ", ప్రమాదం జరిగినట్టు అనిపిస్తోంది. బాగున్నారా? స్క్రీన్ మీద 'నేను బాగున్నాను' నొక్కండి. "
                    + "ఒక్క నిమిషంలో నొక్కకపోతే మీ వాళ్లకి మీ లొకేషన్‌తో SOS పంపుతాను.");
            notifyAsk(app);
            main.postDelayed(beep, 6000); // after the words
            main.postDelayed(timeUp, SECONDS * 1000L);
            try { app.startActivity(new Intent(app, CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        });
    }

    /** "నేను బాగున్నాను". */
    static void ok(Context c) {
        main.post(() -> {
            if (!active) { stopAll(c, true); return; } // e.g. after Jarvis restarted: just clear the screen / notification
            stopAll(c, true);
            Announcer.say(c, "సరే, మంచిది. జాగ్రత్తగా వెళ్లండి.");
        });
    }

    /** Time up (or "ఇప్పుడే పంపు"): the SOS SMS; the first contact is called if the screen is up. */
    static void send(Context c, boolean byHim) {
        main.post(() -> {
            if (!active) return;
            stopAll(c, false); // the screen stays up: it can call the first contact
            new Thread(() -> {
                List<String[]> sent = Sos.send(c, byHim ? "ప్రమాదం జరిగింది" : "బైక్ / కారు ప్రమాదం జరిగి ఉండొచ్చు (Jarvis గుర్తించింది), ఒక నిమిషం జవాబు ఇవ్వలేదు");
                StringBuilder names = new StringBuilder();
                for (String[] s : sent) names.append(names.length() > 0 ? ", " : "").append(s[0]);
                String line = sent.isEmpty() ? "SOS పంపలేకపోయాను (SMS అనుమతి / కాంటాక్ట్స్ చూడండి). 112 కి కాల్ చేయండి." : "SOS పంపాను: " + names;
                Reminders.notify(c, "🆘 SOS", line, NOTE + 1);
                Announcer.say(c, line);
                if (!sent.isEmpty()) CrashActivity.callIfShowing(sent.get(0)[1]);
            }, "jarvis-sos").start();
        });
    }

    private static void stopAll(Context c, boolean closeScreen) {
        active = false;
        main.removeCallbacks(beep);
        main.removeCallbacks(timeUp);
        try { if (beeper != null) { beeper.stopTone(); beeper.release(); } } catch (Exception ignored) {}
        beeper = null;
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
        if (closeScreen) CrashActivity.closeIfShowing();
    }

    private static void notifyAsk(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_crash", "ప్రమాదం గుర్తింపు", NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            PendingIntent screen = PendingIntent.getActivity(c, 152, new Intent(c, CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent ok = PendingIntent.getBroadcast(c, 153, new Intent(c, AlarmReceiver.class).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent send = PendingIntent.getBroadcast(c, 154, new Intent(c, AlarmReceiver.class).setAction(ACTION_SEND), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_crash").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🆘 బాగున్నారా?").setContentText(SECONDS + " సెకన్లలో జవాబు లేకపోతే SOS వెళ్తుంది")
                    .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
                    .setFullScreenIntent(screen, true).setContentIntent(screen)
                    .addAction(new Notification.Action.Builder(null, "✅ నేను బాగున్నాను", ok).build())
                    .addAction(new Notification.Action.Builder(null, "🆘 ఇప్పుడే పంపు", send).build()).build());
        } catch (Exception ignored) {}
    }
}
