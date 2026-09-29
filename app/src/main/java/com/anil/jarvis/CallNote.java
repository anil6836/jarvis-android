package com.anil.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * After a real call (a minute or longer): "ఈ కాల్‌లో ఏమైనా గుర్తుపెట్టుకోవాలా?" He says what to remember and Jarvis
 * writes it as a note (and a reminder when it has a time). Not at night, not when he is still busy.
 */
final class CallNote {
    private CallNote() {}

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long lastAsked;
    private static final java.util.Random rnd = new java.util.Random();

    private static final String[][] LINES = {
            {"%s తో కాల్ అయింది.", "ఈ కాల్‌లో ఏమైనా గుర్తుపెట్టుకోవాలా? చెప్పండి, రాసుకుంటాను."},
            {"%s తో మాట్లాడారు.", "ఏమైనా నోట్ చేయాలా, లేక రిమైండర్ పెట్టాలా?"},
            {"కాల్ అయిపోయింది.", "%s చెప్పిన వాటిలో ఏమైనా గుర్తుంచుకోవాలా?"},
            {"%s తో కాల్ ముగిసింది.", "ఏదైనా పని, తేదీ, డబ్బు విషయం ఉంటే చెప్పండి, రాసుకుంటాను."},
    };

    /** Seconds talked in the latest phone call if it ended in the last 2 minutes, -1 if not known (WhatsApp calls, no permission). */
    private static long talkSeconds(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return -1;
        try (android.database.Cursor cur = c.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI,
                new String[]{android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION}, null, null,
                android.provider.CallLog.Calls.DATE + " DESC")) {
            if (cur != null && cur.moveToFirst()) {
                long date = cur.getLong(0), dur = cur.getLong(1);
                if (System.currentTimeMillis() - (date + dur * 1000) < 2 * 60000L) return dur;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static void after(Context c, String who, long seconds) {
        Context app = c.getApplicationContext();
        Prefs p = new Prefs(app);
        if (!p.callNote() || seconds < p.callNoteSeconds() || p.night()) return;
        if (System.currentTimeMillis() - lastAsked < 5 * 60000L) return;
        String name = who == null || who.trim().isEmpty() ? "ఆ వ్యక్తి" : who.trim();
        // give the call screen a moment to close; don't ask if another call started
        main.postDelayed(() -> {
            if (CallControl.busyWithCall() || MainActivity.busyTalking() || Rest.resting(app)) return;
            try {
                android.media.AudioManager am = app.getSystemService(android.media.AudioManager.class);
                if (am != null && am.getMode() != android.media.AudioManager.MODE_NORMAL) return; // still in a call
                android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
                if (nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL) return; // Do Not Disturb
            } catch (Exception ignored) {}
            // a phone call: the real talk time from the call log (an unanswered call that rang a minute doesn't count)
            long talk = talkSeconds(app);
            if (talk >= 0 && talk < p.callNoteSeconds()) return;
            lastAsked = System.currentTimeMillis();
            String[] l = LINES[rnd.nextInt(LINES.length)];
            Proactive.say(app, p.name() + ", " + String.format(l[0], name), String.format(l[1], name),
                    " [call note: the call with " + name + " (" + (seconds / 60) + " min) just ended. If he says what to remember, save it with notes "
                            + "(action add, text starting 'కాల్ - " + name + ": ...'); if it has a date / time or a task, also set_reminder; money given or owed -> debts. "
                            + "Say in one short line what you saved. If he says nothing / వద్దు, just say సరే.]");
        }, 4000);
    }
}
