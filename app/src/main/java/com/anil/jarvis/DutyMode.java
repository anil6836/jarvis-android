package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * O40: duty mode ("డ్యూటీ మోడ్ ఆన్", "డ్యూటీకి వచ్చాను"): the phone on vibrate, and Jarvis keeps its own remarks and reminders
 * to notifications (no speaking aloud at work); calls and alarms ring as usual. It ends by itself when the duty in his
 * duty calendar ends (or after 24 hours without a calendar), or on "డ్యూటీ మోడ్ ఆఫ్". Optional: on by itself when a duty
 * starts (Settings → డ్యూటీ). The ringer is put back the way it was.
 */
final class DutyMode {
    private DutyMode() {}

    static final String ACTION_END = "com.anil.jarvis.DUTY_MODE_END";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_duty_mode", Context.MODE_PRIVATE); }

    static boolean on(Context c) {
        SharedPreferences s = sp(c);
        if (!s.getBoolean("on", false)) return false;
        long until = s.getLong("until", 0);
        if (until > 0 && System.currentTimeMillis() > until + 60_000L) { off(c, true); return false; }
        return true;
    }

    /** Starts by itself when a duty starts (his choice; off at first). */
    static boolean auto(Context c) { return sp(c).getBoolean("auto", false); }

    static void setAuto(Context c, boolean v) { sp(c).edit().putBoolean("auto", v).apply(); }

    /** When the duty going on now ends (his calendar), else in 24 hours. */
    private static long endOfDuty(Context c) {
        try {
            LocalDateTime[] d = Duty.nowOrNext(c);
            LocalDateTime now = LocalDateTime.now();
            // on duty now, or it starts within 3 hours ("డ్యూటీకి వచ్చాను" a little early): its end
            if (d != null && !now.isBefore(d[0].minusHours(3)) && now.isBefore(d[1])) return d[1].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {}
        return System.currentTimeMillis() + 24 * 3600_000L;
    }

    static String start(Context c, boolean byItself) {
        Context app = c.getApplicationContext();
        long until = endOfDuty(app);
        SharedPreferences.Editor e = sp(app).edit().putBoolean("on", true).putLong("until", until).putLong("block", until);
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null && !sp(app).getBoolean("on_before", false)) {
            int mode = am.getRingerMode();
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                try { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); e.putBoolean("ringer_changed", true); } catch (Exception ignored) {}
            } else if (mode == AudioManager.RINGER_MODE_VIBRATE && new Prefs(app).night()) {
                e.putBoolean("ringer_changed", true); // (on vibrate for the night: when the duty ends the sound comes back, unless it is night again)
            }
        }
        e.putBoolean("on_before", true).apply();
        schedule(app, until);
        String end = Offline.sayWhen(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(until), ZoneId.systemDefault()), LocalDateTime.now());
        String s = "డ్యూటీ మోడ్ ఆన్: ఫోన్ వైబ్రేట్‌లో, నా మాటలు నోటిఫికేషన్లుగా మాత్రమే. " + end + " కి ఆఫ్ అవుతుంది (\"డ్యూటీ మోడ్ ఆఫ్\" అంటే ఇప్పుడే).";
        if (byItself) Reminders.notify(app, "🛡️ డ్యూటీ మోడ్ ఆన్", s, 7321);
        return s;
    }

    static String off(Context c, boolean byItself) {
        Context app = c.getApplicationContext();
        SharedPreferences s = sp(app);
        boolean was = s.getBoolean("on", false);
        if (s.getBoolean("ringer_changed", false)) {
            AudioManager am = app.getSystemService(AudioManager.class);
            try { // (night mode on: it stays quiet)
                if (am != null && am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE && !new Prefs(app).night()) am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            } catch (Exception ignored) {}
        }
        // turned off by hand during a duty: it doesn't come back on by itself for the rest of that duty
        s.edit().putBoolean("on", false).putBoolean("ringer_changed", false).putBoolean("on_before", false).remove("until")
                .putLong("off_block", byItself ? 0 : s.getLong("block", 0)).apply();
        AlarmManager a = app.getSystemService(AlarmManager.class);
        if (a != null) a.cancel(pending(app));
        if (byItself && was) Reminders.notify(app, "డ్యూటీ మోడ్ ఆఫ్", "డ్యూటీ అయిపోయింది: ఫోన్ సౌండ్ మళ్లీ ఆన్. విశ్రాంతి తీసుకోండి.", 7321);
        if (!was) return "డ్యూటీ మోడ్ ఆన్‌లో లేదు.";
        AudioManager am2 = app.getSystemService(AudioManager.class);
        boolean loud = am2 != null && am2.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
        return "డ్యూటీ మోడ్ ఆఫ్ చేశాను" + (loud ? ", ఫోన్ సౌండ్ మళ్లీ ఆన్." : " (ఫోన్ ఇంకా వైబ్రేట్‌లోనే ఉంది).");
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 7322, new Intent(c, AlarmReceiver.class).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, long at) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        if (a == null) return;
        try { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)); } catch (Exception ignored) {}
    }

    /** After the phone restarts: still on (and not over), its end is set again; over: off now (the ringer back). */
    static void restore(Context c) {
        if (!on(c)) return; // (on() turns an old one off)
        long until = sp(c).getLong("until", 0);
        if (until > 0) schedule(c.getApplicationContext(), until);
    }

    /** The regular check (Proactive): an old one ends; a duty has started and he wants it by itself. */
    static void tick(Context c) {
        if (on(c) || !auto(c)) return;
        try {
            LocalDateTime[] d = Duty.nowOrNext(c);
            LocalDateTime now = LocalDateTime.now();
            if (d == null || now.isBefore(d[0]) || !now.isBefore(d[1])) return;
            long block = d[1].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (sp(c).getLong("off_block", 0) == block) return; // he turned it off for this duty
            start(c, true);
        } catch (Exception ignored) {}
    }

    /** What he said: on, off, or how it is. */
    static String command(Context c, String t) {
        if (t.matches("(?s).*(ఆఫ్|off|ఆపు|ఆపేయ్|వద్దు|తీసేయ్|అయిపోయింది|ముగిసింది).*")) return off(c, false);
        if (t.matches("(?s).*(ఉందా|ఆన్‌లో\\s*ఉందా|status|స్టేటస్).*") && !t.matches("(?s).*(ఆన్\\s*చెయ్|ఆన్\\s*చేయి|పెట్టు).*"))
            return on(c) ? "డ్యూటీ మోడ్ ఆన్‌లో ఉంది." : "డ్యూటీ మోడ్ ఆఫ్‌లో ఉంది.";
        return start(c, false);
    }
}
