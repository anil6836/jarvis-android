package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * W62 "📱 ఫోన్‌లో చూపించు": what he saw on the watch opens on the phone (the full answer in the chat, a map, a route).
 * A locked phone can't open a screen by itself: then a notification that opens it with one tap (not sent to the watch).
 */
final class ShowOnPhone {
    private ShowOnPhone() {}

    static final int NOTE = 266;

    /** Opens it now if the phone may, else the notification. True when it opened. */
    static boolean open(Context c, Intent i, String title) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!WatchHub.phoneIdle(c) || android.provider.Settings.canDrawOverlays(c)) {
            try { c.startActivity(i); return !WatchHub.phoneIdle(c); } catch (Exception ignored) {}
        }
        note(c, title, "నొక్కితే ఫోన్‌లో తెరుస్తుంది", i);
        return false;
    }

    static void note(Context c, String title, String text, Intent open) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_show", "వాచ్ నుంచి ఫోన్‌లో చూపించు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, NOTE, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_show").setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(30 * 60_000L).build());
        } catch (Exception ignored) {}
    }

    /** The last answer: a link or place in it opens that; else the chat with it. */
    static String lastAnswer(Context c) {
        String last = "";
        try {
            java.util.List<JSONObject> chat = Store.get(c).chat();
            for (int i = chat.size() - 1; i >= 0; i--) {
                JSONObject m = chat.get(i);
                if ("assistant".equals(m.optString("role"))) { last = m.optString("text", m.optString("content")); break; }
            }
        } catch (Exception ignored) {}
        String url = Places.firstUrl(last);
        Intent i = url != null ? new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)) : new Intent(c, MainActivity.class);
        boolean opened = open(c, i, url != null ? "🔗 వాచ్‌లో చూసిన లింక్" : "💬 వాచ్‌లో చూసిన జవాబు");
        return opened ? "📱 ఫోన్‌లో తెరిచాను" : "📱 ఫోన్ లాక్‌లో ఉంది: నోటిఫికేషన్ నొక్కితే తెరుస్తుంది";
    }
}
