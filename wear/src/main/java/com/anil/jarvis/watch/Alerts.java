package com.anil.jarvis.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * W15 / W8: Jarvis's alerts on the wrist (cooker, door, medicine, reminders, guard / danger, a message, a call),
 * each with its own vibration so he knows which one without looking (W18: on the bike only the vibration counts).
 * Their buttons press the same buttons on the phone, or answer as if he had said the word ("చదువు", "ఎత్తు").
 * Phase 4: "care" (a gentle tap: "stressed? breathe with me") can open a watch screen.
 */
final class Alerts {
    private Alerts() {}

    static final String CH = "jarvis_alerts";
    /** The vibration language: kind, its name, its rhythm (off/on ms after a first 0). */
    static final Object[][] LANGUAGE = {
            {"call", "📞 కాల్", new long[]{600, 300, 600, 300, 600}},
            {"message", "💬 మెసేజ్", new long[]{80, 120, 80}},
            {"cooker", "🍲 కుక్కర్", new long[]{60, 80, 60, 80, 60}},
            {"door", "🚪 తలుపు / బెల్", new long[]{40, 100, 40, 300, 40, 100, 40}},
            {"medicine", "💊 మందులు", new long[]{250, 250, 250}},
            {"reminder", "⏰ రిమైండర్", new long[]{180, 120, 60}},
            {"sos", "🆘 ప్రమాదం / కాపలా", new long[]{100, 80, 100, 80, 100, 80, 100, 80, 100, 80, 100}},
            {"phone", "📱 ఫోన్ మర్చిపోయారు", new long[]{400, 150, 400, 150, 400}},
            {"weather", "🌧️ వాతావరణం", new long[]{60, 60, 120, 60, 200}},
            {"info", "ℹ️ మిగతావి", new long[]{50}},
            {"rest", "🛑 బైక్: ఆగండి / మెలకువ", new long[]{300, 200, 300, 200, 300}},
            {"left", "⬅️ ఎడమకి తిరగండి (నడక)", new long[]{70, 110, 70}},
            {"right", "➡️ కుడికి తిరగండి (నడక)", new long[]{450}},
            {"uturn", "↩️ వెనక్కి తిరగండి", new long[]{70, 90, 70, 90, 70}},
    };

    static long[] pattern(String kind) {
        for (Object[] l : LANGUAGE) if (l[0].equals(kind)) return (long[]) l[2];
        return kind.equals("care") ? new long[]{30} : new long[]{50};
    }

    /** W78: two short = left, one long = right (while he walks with Maps on the phone). */
    static void turn(Context c, String dir) {
        if (!"left".equals(dir) && !"right".equals(dir) && !"uturn".equals(dir)) return;
        Talk.buzzAs(c, android.os.VibrationAttributes.USAGE_NOTIFICATION, pattern(dir));
    }

    static void buzz(Context c, String kind) {
        int usage = kind.equals("sos") || kind.equals("phone") || kind.equals("rest") ? android.os.VibrationAttributes.USAGE_ALARM
                : kind.equals("call") ? android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST : android.os.VibrationAttributes.USAGE_NOTIFICATION;
        Talk.buzzAs(c, usage, pattern(kind));
    }

    private static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Jarvis అలర్ట్స్", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(false); // (each kind buzzes its own rhythm)
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    /** An alert from the phone (main thread). */
    static void show(Context c, JSONObject o) {
        String kind = o.optString("kind", "info");
        // (messages / calls: his phone setting decides; "phone forgotten": its own setting)
        boolean fromJarvis = !kind.equals("message") && !kind.equals("call") && !kind.equals("phone");
        if (fromJarvis && !Link.alerts(c)) return;
        if (!o.optBoolean("silent")) buzz(c, kind); // (an update the phone shows quietly: no buzz again)
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channel(c);
        int id = o.optInt("id");
        String title = o.optString("title"), text = o.optString("text");
        // phase 4: a card that opens a watch screen (the breathing after "stressed?", the body scan)
        String open = o.optString("open");
        PendingIntent screen = screen(c, open);
        Notification.Builder b = new Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(screen != null ? screen : Notes.open(c, 20)).setAutoCancel(true).setTimeoutAfter(15 * 60_000L)
                .setCategory(kind.equals("call") ? Notification.CATEGORY_CALL : kind.equals("message") ? Notification.CATEGORY_MESSAGE
                        : kind.equals("sos") ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER);
        if (screen != null) b.addAction(action(c, "breathe".equals(open) ? "🌬️ మొదలుపెట్టు" : "🩺 స్కాన్", screen));
        JSONArray acts = o.optJSONArray("acts");
        for (int i = 0; acts != null && i < acts.length() && i < 3; i++) {
            String label = acts.optString(i);
            if (label.isEmpty()) continue;
            Intent t = new Intent(c, Answer.class).putExtra("alert", id).putExtra("i", i).putExtra("key", o.optString("key"));
            b.addAction(action(c, label, PendingIntent.getBroadcast(c, (id * 31 + i), t, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)));
        }
        JSONArray says = o.optJSONArray("says");
        for (int i = 0; says != null && i < says.length() && i < 3; i++) {
            String w = says.optString(i);
            Intent t = new Intent(c, Answer.class).putExtra("say", w).putExtra("why", kind.equals("call") ? "call" : "answer").putExtra("note", id);
            b.addAction(action(c, w, PendingIntent.getBroadcast(c, (id * 31 + 10 + i), t, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)));
        }
        try { nm.notify("alert", id, b.build()); } catch (Exception ignored) {}
    }

    /** The watch screen a card opens ("breathe", "scan"), or null. */
    private static PendingIntent screen(Context c, String open) {
        Class<?> k = "breathe".equals(open) ? Breathe.class : "scan".equals(open) ? Scan.class : null;
        if (k == null) return null;
        return PendingIntent.getActivity(c, ("open" + open).hashCode(), new Intent(c, k).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action action(Context c, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_send), label, pi).build();
    }

    /** It was answered on the phone (or replaced): the card goes. */
    static void gone(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel("alert", id);
    }

    /** "📱 ఫోన్ మర్చిపోయారా?" (W20): with when the link was lost and where the phone was last. */
    static void phoneLost(Context c, long lostAt) {
        JSONObject i = Link.info(c);
        String place = i.optString("place");
        String when = new java.text.SimpleDateFormat("h:mm", java.util.Locale.ROOT).format(new java.util.Date(lostAt));
        try {
            show(c, new JSONObject().put("id", 777).put("kind", "phone").put("title", "📱 ఫోన్ మర్చిపోయారా?")
                    .put("text", "ఫోన్‌తో కనెక్షన్ " + when + " కి పోయింది." + (place.isEmpty() ? "" : " ఫోన్ చివరిగా " + place + " దగ్గర ఉంది.")));
        } catch (Exception ignored) {}
    }
}
