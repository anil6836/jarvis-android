package com.anil.jarvis.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * The watch's notifications: the quiet one that keeps the wrist-raise listening alive, the talk (when the Jarvis
 * screen isn't open: he raised his wrist and talked, the screen comes up with it), and "are you sure?" with two taps.
 */
final class Notes {
    private Notes() {}

    static final String CH_EAR = "jarvis_ear", CH_TALK = "jarvis_talk";
    static final int ID_EAR = 1, ID_TALK = 2, ID_CONFIRM = 3, ID_BROKEN = 4;

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_EAR, "Jarvis వింటోంది", NotificationManager.IMPORTANCE_MIN));
        NotificationChannel t = new NotificationChannel(CH_TALK, "Jarvis తో మాట్లాడటం", NotificationManager.IMPORTANCE_HIGH);
        t.enableVibration(false); // (the talk buzzes on its own)
        t.setSound(null, null);
        nm.createNotificationChannel(t);
    }

    static PendingIntent open(Context c, int code) {
        return PendingIntent.getActivity(c, code, new Intent(c, WatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification ear(Context c) { return ear(c, true); }

    /** mic false: only the steps / heart rate run (after a restart, until Jarvis is opened). */
    static Notification ear(Context c, boolean mic) {
        channels(c);
        String what = !mic ? "🚶 అడుగులు, గుండె వేగం చూస్తోంది (వినడానికి Jarvis ఒకసారి తెరవండి)"
                : Link.inHours(c) ? "మీ సమయం: ఎప్పుడూ వింటోంది" : "చేయి ఎత్తి \"Hey Jarvis\" అనండి";
        return new Notification.Builder(c, CH_EAR).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis").setContentText(what).setOngoing(true).setContentIntent(open(c, 1))
                .setCategory(Notification.CATEGORY_SERVICE).build();
    }

    /** The talk, while the Jarvis screen isn't open (it comes up with the first step). */
    static void talk(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Talk.screen != null) { nm.cancel(ID_TALK); return; }
        int s = Talk.state;
        boolean going = s != Talk.IDLE && s != Talk.ERROR;
        if (!going && Talk.reply.isEmpty() && Talk.status.isEmpty()) { nm.cancel(ID_TALK); return; }
        channels(c);
        String title = going ? (s == Talk.LISTENING ? "🎙️ వింటున్నాను…" : s == Talk.SPEAKING ? "🔊 Jarvis" : "⏳ ఆలోచిస్తున్నాను…") : "Jarvis";
        String text = !Talk.reply.isEmpty() ? Talk.reply : !Talk.heard.isEmpty() ? Talk.heard : Talk.status;
        Notification.Builder b = new Notification.Builder(c, CH_TALK).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(c, 2)).setAutoCancel(true).setOnlyAlertOnce(true).setOngoing(going)
                .setCategory(Notification.CATEGORY_MESSAGE).setTimeoutAfter(going ? 120_000 : 90_000);
        if (going && s == Talk.LISTENING) b.setFullScreenIntent(open(c, 3), true); // the Jarvis screen comes up
        try { nm.notify(ID_TALK, b.build()); } catch (Exception ignored) {}
    }

    static void confirm(Context c, JSONObject q) {
        if (Talk.screen != null) return; // the screen asks
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channels(c);
        int id = q.optInt("id");
        Notification n = new Notification.Builder(c, CH_TALK).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(q.optString("title", "Jarvis")).setContentText(q.optString("msg"))
                .setStyle(new Notification.BigTextStyle().bigText(q.optString("msg")))
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_send),
                        "✓ " + q.optString("yes", "సరే"), answer(c, id, true)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel),
                        "✗ వద్దు", answer(c, id, false)).build())
                .setContentIntent(open(c, 4)).setAutoCancel(true).setTimeoutAfter(60_000)
                .setCategory(Notification.CATEGORY_REMINDER).build();
        try { nm.notify(ID_CONFIRM, n); } catch (Exception ignored) {}
    }

    private static PendingIntent answer(Context c, int id, boolean yes) {
        Intent i = new Intent(c, Answer.class).putExtra("id", id).putExtra("yes", yes);
        return PendingIntent.getBroadcast(c, yes ? 10 : 11, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelConfirm(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_CONFIRM);
    }

    /** Wrist-raise listening stopped working: the app must be opened once (Android allows the mic only so). */
    static void broken(Context c, String why) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channels(c);
        Notification n = new Notification.Builder(c, CH_TALK).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis వినడం ఆగింది").setContentText("ఇక్కడ నొక్కి Jarvis ఒకసారి తెరవండి" + (why == null || why.isEmpty() ? "" : " (" + why + ")"))
                .setContentIntent(open(c, 5)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).build();
        try { nm.notify(ID_BROKEN, n); } catch (Exception ignored) {}
    }

    static void cancelBroken(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_BROKEN);
    }
}
