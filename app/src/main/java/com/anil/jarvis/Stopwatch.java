package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * O24: a stopwatch by voice, without internet: "స్టాప్‌వాచ్ మొదలుపెట్టు", "ఆపు", "ఎంత అయింది", "లాప్", "రీసెట్". While it runs a
 * notification shows it ticking (Android's own clock in the notification, no battery cost). Kept over a restart of the app.
 */
final class Stopwatch {
    private Stopwatch() {}

    private static final String CH = "jarvis_stopwatch";
    private static final int NOTE = 7311;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_stopwatch", Context.MODE_PRIVATE); }

    /** Running since (wall clock) or 0; plus what was counted before a pause. */
    static boolean running(Context c) { return sp(c).getLong("since", 0) > 0; }

    static long elapsed(Context c) {
        SharedPreferences s = sp(c);
        long since = s.getLong("since", 0), before = s.getLong("before", 0);
        return before + (since > 0 ? Math.max(0, System.currentTimeMillis() - since) : 0);
    }

    /** "1 గంట 5 నిమిషాల 30 సెకన్లు" / "45 సెకన్లు". */
    static String say(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(h == 1 ? " గంట " : " గంటల ");
        if (m > 0) b.append(m).append(m == 1 ? " నిమిషం " : " నిమిషాల ");
        if (sec > 0 || b.length() == 0) b.append(sec).append(sec == 1 ? " సెకను" : " సెకన్లు");
        return b.toString().trim();
    }

    static String start(Context c) {
        if (running(c)) return "స్టాప్‌వాచ్ ఇప్పటికే నడుస్తోంది: " + say(elapsed(c)) + ".";
        boolean resume = sp(c).getLong("before", 0) > 0;
        sp(c).edit().putLong("since", System.currentTimeMillis()).apply();
        note(c);
        return resume ? "స్టాప్‌వాచ్ మళ్లీ మొదలుపెట్టాను (" + say(elapsed(c)) + " నుంచి)." : "స్టాప్‌వాచ్ మొదలుపెట్టాను.";
    }

    static String stop(Context c) {
        if (!running(c)) {
            long e = elapsed(c);
            return e > 0 ? "స్టాప్‌వాచ్ ఆగే ఉంది: " + say(e) + "." : "స్టాప్‌వాచ్ నడవడం లేదు.";
        }
        long e = elapsed(c);
        sp(c).edit().putLong("before", e).putLong("since", 0).apply();
        cancel(c);
        return "స్టాప్‌వాచ్ ఆపాను: " + say(e) + ".";
    }

    static String read(Context c) {
        long e = elapsed(c);
        if (e == 0) return "స్టాప్‌వాచ్ నడవడం లేదు. \"స్టాప్‌వాచ్ మొదలుపెట్టు\" అనండి.";
        return (running(c) ? "ఇప్పటికి " : "ఆగింది, ") + say(e) + ".";
    }

    /** A lap: the time now, the counting goes on. */
    static String lap(Context c) {
        if (!running(c)) return read(c);
        SharedPreferences s = sp(c);
        long e = elapsed(c), last = s.getLong("lap", 0);
        s.edit().putLong("lap", e).apply();
        return "లాప్: " + say(e) + (last > 0 ? " (ఈ రౌండ్ " + say(e - last) + ")" : "") + ".";
    }

    static String reset(Context c) {
        sp(c).edit().clear().apply();
        cancel(c);
        return "స్టాప్‌వాచ్ సున్నా చేశాను.";
    }

    /** "స్టాప్‌వాచ్ …": what he wants of it. */
    static String command(Context c, String t) {
        if (t.matches("(?s).*(రీసెట్|reset|సున్నా|క్లియర్|clear|మొదటి\\s*నుంచి).*")) return reset(c);
        if (t.matches("(?s).*(లాప్|lap|రౌండ్).*")) return lap(c);
        if (t.matches("(?s).*(ఆపు|ఆపేయ్|ఆపండి|స్టాప్\\s*చెయ్|స్టాప్\\s*చేయి|pause|పాజ్|ఆఫ్).*") && !t.matches("(?s).*(స్టార్ట్|మొదలు).*")) return stop(c);
        if (t.matches("(?s).*(ఎంత|ఎన్ని|టైమ్|time|చెప్పు|అయింది).*") && !t.matches("(?s).*(స్టార్ట్|మొదలు|పెట్టు|ఆన్).*")) return read(c);
        return start(c);
    }

    private static void note(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH, "Jarvis స్టాప్‌వాచ్", NotificationManager.IMPORTANCE_LOW));
        long start = System.currentTimeMillis() - elapsed(c);
        PendingIntent open = PendingIntent.getActivity(c, 7312, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("⏱️ స్టాప్‌వాచ్").setContentText("\"స్టాప్‌వాచ్ ఆపు\" అంటే ఆగుతుంది")
                .setWhen(start).setUsesChronometer(true).setShowWhen(true).setOngoing(true).setContentIntent(open).build();
        try { nm.notify(NOTE, n); } catch (Exception ignored) {}
    }

    private static void cancel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE);
    }

    /** After a restart of the phone: the running one's notification comes back. */
    static void restore(Context c) { if (running(c)) note(c); }
}
