package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * After a 48-hour duty: the afternoon he comes home, Jarvis reminds him to sleep if he keeps using the phone, and while
 * he sleeps (screen off a while) Jarvis stays quiet: messages wait and are told when he wakes, no cough / call-note questions.
 * With the watch (W28), a nap on any afternoon is seen too (still on his wrist, the heart lower than his normal).
 */
final class Rest {
    private Rest() {}

    static volatile long screenOffAt, screenOnAt;
    /** He fell asleep after duty: stays true until he unlocks the phone (a lit screen or 10 pm doesn't end it). */
    private static volatile boolean asleep;

    /** Screen on / off (from the notification listener, which always runs). */
    static void screen(boolean on) {
        long now = System.currentTimeMillis();
        if (on) screenOnAt = now; else screenOffAt = now;
    }

    /** He unlocked the phone or talked to Jarvis: awake. */
    static void awake() { asleep = false; }

    /** Today is the day his duty ended (he was on duty yesterday, not today). */
    static boolean postDuty(Context c) {
        try {
            Duty.Roster r = Duty.load(c);
            LocalDate today = LocalDate.now();
            return Duty.ready(r) && r.isOn(Duty.ME, today.minusDays(1)) && !r.isOn(Duty.ME, today);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean screenOn(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    /**
     * Sleeping after duty: that afternoon / evening the screen went off for 15 minutes or more; then it lasts until he
     * unlocks the phone. Never while driving (riding home).
     */
    static boolean resting(Context c) {
        try {
            Prefs p = new Prefs(c);
            if (!p.restMode() || p.driving()) { asleep = false; return false; }
            if (asleep) {
                if (System.currentTimeMillis() - screenOffAt > 16 * 3600000L) asleep = false; // a day later: surely awake
                return asleep;
            }
            int h = LocalTime.now().getHour();
            if (h < 12 || h >= 22) return false;
            if (screenOn(c)) return false;
            if (screenOffAt == 0 || System.currentTimeMillis() - screenOffAt < 15 * 60000L) return false;
            // W28: the watch says he is asleep (still on his wrist 40 minutes, heart lower than his normal): any afternoon /
            // evening nap, not only after duty; it ends as soon as he moves (the watch tells)
            if (HeartLog.asleep(c)) return true;
            asleep = postDuty(c);
            return asleep;
        } catch (Exception e) {
            return false;
        }
    }

    /** From Proactive: on that afternoon, 40 minutes on the phone -> "కొంచెం పడుకోండి" (once). */
    static void tick(Context c, Prefs p, boolean quiet) {
        if (!p.restMode()) return;
        int h = LocalTime.now().getHour();
        if (h < 14 || h >= 20) return;
        if (!screenOn(c) || screenOnAt == 0 || System.currentTimeMillis() - screenOnAt < 40 * 60000L) return;
        if (MainActivity.busyTalking() || CallControl.busyWithCall()) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_rest", Context.MODE_PRIVATE);
        String today = LocalDate.now().toString();
        if (today.equals(s.getString("reminded", ""))) return;
        if (!postDuty(c)) return;
        s.edit().putString("reminded", today).apply();
        String[] lines = {
                "సర్, 48 గంటల డ్యూటీ చేశారు. కొంచెం పడుకోండి, నేను నిశ్శబ్దంగా ఉంటాను. మెసేజ్‌లు వస్తే లేచాక చెప్తాను.",
                "సర్, రెండు రోజుల డ్యూటీ తర్వాత శరీరానికి విశ్రాంతి కావాలి. ఫోన్ పక్కన పెట్టి కాసేపు నిద్రపోండి.",
                "సర్, బాగా అలసిపోయి ఉంటారు. ఒక గంటైనా నిద్రపోండి, ముఖ్యమైనవి ఏమైనా వస్తే లేచాక చెప్తాను.",
        };
        String text = lines[(int) (System.currentTimeMillis() / 1000 % lines.length)];
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel("jarvis_rest", "విశ్రాంతి", NotificationManager.IMPORTANCE_DEFAULT));
                nm.notify(87, new Notification.Builder(c, "jarvis_rest").setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                        .setContentTitle("😴 కొంచెం విశ్రాంతి తీసుకోండి").setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());
            }
        } catch (Exception ignored) {}
        if (!quiet) Announcer.say(c, text);
    }
}
