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
        if (!phone && n != null) { appCallFull = n.fullScreenIntent; appCallOpen = n.contentIntent; } // (an app call's own screens, for its Speaker button later)
        ringingKey = key;
        ringingIsPhone = phone;
        ringingWho = who == null ? "" : who;
        ringingAt = SystemClock.elapsedRealtime();
    }

    private static volatile long ongoingSince;
    private static volatile String ongoingWho = "";

    static void onOngoing(String key, Notification n, boolean phone) {
        if (key != null && !key.equals(ongoingKey)) {
            ongoingSince = System.currentTimeMillis();
            String who = "";
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31 && n.extras != null) {
                    Object p = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON);
                    if (p instanceof android.app.Person && ((android.app.Person) p).getName() != null) who = ((android.app.Person) p).getName().toString();
                }
                if (who.isEmpty() && n.extras != null && n.extras.getCharSequence(Notification.EXTRA_TITLE) != null)
                    who = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString();
            } catch (Exception ignored) {}
            ongoingWho = who.trim();
        }
        ongoing = n;
        ongoingKey = key;
        ongoingIsPhone = phone;
        if (key != null && key.equals(ringingKey)) ringing = null; // it was answered
    }

    /** The call in progress ended with this notification: {who, seconds}, else null. */
    static String[] ended(String key) {
        if (key == null || !key.equals(ongoingKey) || ongoingSince == 0) return null;
        return new String[]{ongoingWho, String.valueOf((System.currentTimeMillis() - ongoingSince) / 1000)};
    }

    static void onRemoved(String key) {
        if (key == null) return;
        if (key.equals(ringingKey)) ringing = null;
        if (key.equals(ongoingKey)) {
            // the dialer reuses one key for every call: forget this one completely
            ongoing = null;
            ongoingKey = null;
            ongoingSince = 0;
            ongoingWho = "";
        }
    }

    static boolean isRinging() {
        return ringing != null && SystemClock.elapsedRealtime() - ringingAt < 120000 || phoneRingingNow();
    }

    // ---------------------------------------------------------------- the phone's own "ringing" (a SIM call, even without its notification)

    private static volatile boolean phoneRinging;
    private static volatile long phoneRingingAt;

    /** The phone's state says a SIM call rings (true) / it stopped (false) (CallCare, from the system's phone-state news). */
    static void phoneRinging(boolean on) {
        phoneRinging = on;
        if (on) phoneRingingAt = SystemClock.elapsedRealtime();
    }

    static boolean phoneRingingNow() { return phoneRinging && SystemClock.elapsedRealtime() - phoneRingingAt < 120000; }

    /** The app call in progress (WhatsApp …): its own call screen opened from its notification (for its Speaker button). */
    private static volatile PendingIntent appCallFull, appCallOpen;

    /**
     * The app call in progress (WhatsApp …): its own call screen brought up (for its Speaker button): the ringing call's
     * full-screen page first (it shows over the lock screen), else the call's notification. Returns how ("" = couldn't).
     */
    static String openCallScreen(Context c) {
        Notification n = ongoing;
        PendingIntent[] ways = {appCallFull, n == null ? null : n.contentIntent, n == null ? null : n.fullScreenIntent, appCallOpen};
        String[] names = {"full-screen", "call-notification", "call-full-screen", "ring-notification"};
        for (int i = 0; i < ways.length; i++) {
            if (ways[i] == null) continue;
            try { send(c, ways[i]); return names[i]; } catch (Exception ignored) {}
        }
        return "";
    }

    /** The app call's ongoing notification has a Speaker button: pressed (true). */
    static boolean pressSpeakerButton(Context c) {
        Notification n = ongoing;
        if (n == null || n.actions == null) return false;
        for (Notification.Action a : n.actions) {
            if (a == null || a.title == null || a.actionIntent == null) continue;
            String t = a.title.toString().toLowerCase(Locale.ROOT).replace("\u200C", "");
            if (t.matches("(?s).*(speaker|స్పీకర్).*") && !t.matches("(?s).*(off|ఆఫ్).*")) {
                try { send(c, a.actionIntent); return true; } catch (Exception e) { return false; }
            }
        }
        return false;
    }

    static String ringingWho() { return ringingWho; }

    /** A call (phone or app) is in progress. */
    static boolean onCall() { return ongoing != null; }

    /** A call is ringing or in progress: don't talk over it. */
    static boolean busyWithCall() { return isRinging() || ongoing != null; }

    // ---------------------------------------------------------------- actions

    private static boolean canTelecom(Context c) {
        return c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The phone service can end a call only on Android 9+ (endCall is not there on 8, the app would close). */
    private static boolean canEndCall(Context c) { return Build.VERSION.SDK_INT >= 28 && canTelecom(c); }

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
            if (!phoneRingtone(c) && !phoneRingingNow()) return "no_call";
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
        if (n == null && !phoneRingtone(c) && !phoneRingingNow()) return "no_call";
        if (n == null || ringingIsPhone) {
            if (canEndCall(c)) {
                try {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    if (tm != null && tm.endCall()) { ringing = null; return "declined"; }
                } catch (Exception ignored) {}
            }
        }
        if (n != null && press(c, n, "android.declineIntent", DECLINE)) { ringing = null; return "declined"; }
        if (ringingIsPhone && Build.VERSION.SDK_INT >= 28 && !canTelecom(c)) return "need_permission"; // (Android 8: no permission helps)
        return n == null ? "no_call" : "failed";
    }

    /** Hangs up the call in progress. */
    @SuppressWarnings("deprecation")
    static String hangUp(Context c) {
        Notification n = ongoing;
        if (n == null || ongoingIsPhone) {
            if (canEndCall(c)) {
                try {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    if (tm != null && tm.endCall()) { ongoing = null; return "ended"; }
                } catch (Exception ignored) {}
            }
        }
        if (n != null && press(c, n, "android.hangUpIntent", HANG_UP)) { ongoing = null; return "ended"; }
        if (n != null && press(c, n, "android.declineIntent", DECLINE)) { ongoing = null; return "ended"; }
        if (Build.VERSION.SDK_INT >= 28 && !canTelecom(c)) return "need_permission";
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
