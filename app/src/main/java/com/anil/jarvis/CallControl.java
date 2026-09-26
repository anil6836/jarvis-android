package com.anil.jarvis;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.TelecomManager;

import java.util.Locale;

/**
 * Answers, declines and hangs up calls by voice: phone calls through the phone's call service,
 * and WhatsApp / Telegram / other app calls by pressing the buttons on their call notification.
 */
final class CallControl {
    private CallControl() {}

    private static volatile Notification ringing, ongoing;
    private static volatile String ringingKey, ongoingKey, ringingWho = "";
    private static volatile boolean ringingIsPhone, ongoingIsPhone;
    private static volatile long ringingAt;

    private static final String[] ANSWER = {"answer", "accept", "ఎత్తు", "జవాబు", "సమాధానం", "స్వీకరించు", "उत्तर", "स्वीकार"};
    private static final String[] DECLINE = {"decline", "reject", "dismiss", "తిరస్కరించు", "తిరస్కరణ", "అస్వీకరించు", "अस्वीकार"};
    private static final String[] HANG_UP = {"hang up", "end call", "end", "ముగించు", "కాల్ ముగించు", "समाप्त"};

    // ---------------------------------------------------------------- fed by NotifyListener

    static void onIncoming(String key, Notification n, boolean phone, String who) {
        ringing = n;
        ringingKey = key;
        ringingIsPhone = phone;
        ringingWho = who == null ? "" : who;
        ringingAt = SystemClock.elapsedRealtime();
    }

    static void onOngoing(String key, Notification n, boolean phone) {
        ongoing = n;
        ongoingKey = key;
        ongoingIsPhone = phone;
        if (key != null && key.equals(ringingKey)) ringing = null; // it was answered
    }

    static void onRemoved(String key) {
        if (key == null) return;
        if (key.equals(ringingKey)) ringing = null;
        if (key.equals(ongoingKey)) ongoing = null;
    }

    static boolean isRinging() {
        return ringing != null && SystemClock.elapsedRealtime() - ringingAt < 120000;
    }

    static String ringingWho() { return ringingWho; }

    /** A call is ringing or in progress: don't talk over it. */
    static boolean busyWithCall() { return isRinging() || ongoing != null; }

    // ---------------------------------------------------------------- actions

    private static boolean canTelecom(Context c) {
        return c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The phone's own ringtone is playing (an incoming phone call), readable without phone-state permission. */
    private static boolean phoneRingtone(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.getMode() == AudioManager.MODE_RINGTONE;
        } catch (Exception e) { return false; }
    }

    /** Returns "answered", "no_call", "need_permission" or "failed". */
    @SuppressWarnings("deprecation")
    static String answer(Context c) {
        Notification n = ringing;
        if (n == null) {
            // Maybe a phone call Jarvis did not see: try the phone service only if something is really ringing,
            // since acceptRingingCall() gives no result and would otherwise report "answered" for nothing.
            if (!phoneRingtone(c)) return "no_call";
            if (!canTelecom(c)) return "need_permission";
        }
        if (n == null || ringingIsPhone) {
            if (canTelecom(c)) {
                try {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    if (tm != null) { tm.acceptRingingCall(); ringing = null; return "answered"; }
                } catch (Exception ignored) {}
            }
        }
        if (n != null && press(c, n, "android.answerIntent", ANSWER)) { ringing = null; return "answered"; }
        if (ringingIsPhone && !canTelecom(c)) return "need_permission";
        return n == null ? "no_call" : "failed";
    }

    /** Rejects the ringing call. */
    @SuppressWarnings("deprecation")
    static String decline(Context c) {
        Notification n = ringing;
        // nothing seen ringing and no ringtone: endCall() would hang up the call in progress instead
        if (n == null && !phoneRingtone(c)) return "no_call";
        if (n == null || ringingIsPhone) {
            if (canTelecom(c)) {
                try {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    if (tm != null && tm.endCall()) { ringing = null; return "declined"; }
                } catch (Exception ignored) {}
            }
        }
        if (n != null && press(c, n, "android.declineIntent", DECLINE)) { ringing = null; return "declined"; }
        if (ringingIsPhone && !canTelecom(c)) return "need_permission";
        return n == null ? "no_call" : "failed";
    }

    /** Hangs up the call in progress. */
    @SuppressWarnings("deprecation")
    static String hangUp(Context c) {
        Notification n = ongoing;
        if (n == null || ongoingIsPhone) {
            if (canTelecom(c)) {
                try {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    if (tm != null && tm.endCall()) { ongoing = null; return "ended"; }
                } catch (Exception ignored) {}
            }
        }
        if (n != null && press(c, n, "android.hangUpIntent", HANG_UP)) { ongoing = null; return "ended"; }
        if (n != null && press(c, n, "android.declineIntent", DECLINE)) { ongoing = null; return "ended"; }
        if (!canTelecom(c)) return "need_permission";
        return n == null ? "no_call" : "failed";
    }

    /** Presses a call-notification button: the call-style intent first, then a button with a matching name. */
    private static boolean press(Context c, Notification n, String extraKey, String[] words) {
        try {
            if (n.extras != null) {
                Object pi = n.extras.getParcelable(extraKey);
                if (pi instanceof PendingIntent) { send(c, (PendingIntent) pi); return true; }
            }
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a == null || a.title == null || a.actionIntent == null) continue;
                    String t = a.title.toString().toLowerCase(Locale.ROOT);
                    for (String w : words) {
                        if (t.contains(w)) { send(c, a.actionIntent); return true; }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Android 14+ does not pass our background-start allowance to the app's call screen unless we opt in. */
    private static void send(Context c, PendingIntent pi) throws PendingIntent.CanceledException {
        if (Build.VERSION.SDK_INT >= 34) {
            Bundle opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle();
            pi.send(c, 0, null, null, null, null, opts);
        } else {
            pi.send();
        }
    }
}
