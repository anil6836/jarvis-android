package com.anil.jarvis.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * Phase 5: the phone opens a screen here ({screen, target}), e.g. "బండి ఎక్కడ?" said to the phone opens the compass on
 * the wrist. Android doesn't let a screen open from the background, so it also comes as a notification: full screen when
 * the watch is asleep, a card to tap when it is awake. The screen, once open, takes the notification away.
 */
final class Screens {
    private Screens() {}

    private static final String CH = "jarvis_open";
    private static final int NOTE = 43, NOTE_ALARM = 44;

    static void open(Context c, JSONObject o) {
        String s = o.optString("screen"), t = o.optString("target");
        try {
            switch (s) {
                case "compass": launch(c, new Intent(c, Compass.class).putExtra(Compass.EXTRA_TARGET, t), "🧭 దారి", false); break;
                case "photos": launch(c, new Intent(c, Photos.class), "🖼️ ఫోటో వచ్చింది", false); break;
                case "reps": launch(c, new Intent(c, Reps.class), "🏋️ రెప్స్", false); break;
                case "record": launch(c, new Intent(c, Recorder.class), "🎙️ రికార్డ్", false); break;
                default: if (!s.isEmpty()) launch(c, new Intent(c, Panel.class).putExtra(Panel.EXTRA_KIND, s), "⌚ Jarvis", false);
            }
        } catch (Exception ignored) {}
    }

    /** A screen from the background (alarm = full screen even while he uses the watch: find the watch). */
    static void launch(Context c, Intent i, String title, boolean alarm) {
        Context app = c.getApplicationContext();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                NotificationChannel ch = new NotificationChannel(CH, "Jarvis స్క్రీన్లు", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
                PendingIntent pi = PendingIntent.getActivity(app, alarm ? NOTE_ALARM : NOTE, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                nm.notify(alarm ? NOTE_ALARM : NOTE, new Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title).setContentText("నొక్కి తెరవండి")
                        .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                        .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true)
                        .setTimeoutAfter(alarm ? 70_000L : 120_000L).build());
            } catch (Exception ignored) {}
        }
        try { app.startActivity(i); } catch (Exception ignored) {} // (works when Android allows it: e.g. a Jarvis screen is open)
    }

    /** A Jarvis screen opened: its notification goes. */
    static void seen(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.cancel(NOTE);
            nm.cancel(NOTE_ALARM);
        } catch (Exception ignored) {}
    }
}
