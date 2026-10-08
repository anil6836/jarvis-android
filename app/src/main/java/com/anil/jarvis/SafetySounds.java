package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sounds that may mean he needs help or care, heard with the same sound model as coughs (see {@link CoughDetector}):
 * - S15 a scream, or a heavy thud like a fall: Jarvis asks "బాగున్నారా?" (the {@link CrashAlert} screen, "fall" form). His
 *   voice, the "నేను బాగున్నాను" button or calling "Jarvis" stops it; no answer in the time he chose (15 s / 30 s / 1 min) ->
 *   the SOS SMS with his location to his SOS contacts, and a call to the first (what to send is his choice too: SMS + call,
 *   SMS only, or nothing - just the question). A thud alone is asked about only when it goes quiet after it, or a groan /
 *   cry follows (a dropped pot is followed by footsteps and talk);
 * - S18 his own crying (only if he turned it on): no talking at all, only a small silent card "నేను ఉన్నాను. మాట్లాడతారా?";
 *   nothing is counted or kept;
 * - S19 a child crying while he has earphones on (only if he turned it on): the song goes down and Jarvis tells him.
 * Sounds from the phone's own speaker (a video, a song) are never taken; nothing is recorded or sent anywhere.
 */
final class SafetySounds {
    private SafetySounds() {}

    static final int SPEECH = 0, SHOUT = 6, YELL = 9, CHILD_SHOUT = 10, SCREAM = 11, CRYING = 19, BABY_CRY = 20, WHIMPER = 21,
            WAIL = 22, GROAN = 33, GASP = 39, WALK = 48, MUSIC = 132, DOOR = 348, SLAM = 352, KNOCK = 353, SHATTER = 437,
            THUD = 454, THUNK = 455, BANG = 460, SMASH = 463, TV = 518, RADIO = 519;
    static final String ACTION_CARE_NO = "com.anil.jarvis.CARE_NO";
    private static final int NOTE_CARE = 7320;
    private static Handler mainHandler;

    /** The main thread's handler, made when first needed. */
    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_safety", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- his settings

    /** S15: a scream / a heavy fall -> "బాగున్నారా?". */
    static boolean fallOn(Context c) { return sp(c).getBoolean("fall", true); }

    static void setFall(Context c, boolean on) { sp(c).edit().putBoolean("fall", on).apply(); }

    /** Seconds to wait for his answer: 15, 30 (default) or 60. */
    static int waitSeconds(Context c) {
        int s = sp(c).getInt("fall_wait", 30);
        return s == 15 || s == 60 ? s : 30;
    }

    static void setWait(Context c, int s) { sp(c).edit().putInt("fall_wait", s == 15 || s == 60 ? s : 30).apply(); }

    static String waitText(int s) { return s == 15 ? "15 సెకన్లు" : s == 60 ? "1 నిమిషం" : "30 సెకన్లు"; }

    /** No answer: "sms_call" (default: the SOS SMS to all, a call to the first), "sms" (SMS only) or "none" (only the question). */
    static String sosMode(Context c) {
        String m = sp(c).getString("fall_sos", "sms_call");
        return m.equals("sms") || m.equals("none") ? m : "sms_call";
    }

    static void setSosMode(Context c, String m) {
        sp(c).edit().putString("fall_sos", "sms".equals(m) || "none".equals(m) ? m : "sms_call").apply();
    }

    static String modeText(String m) {
        return m.equals("sms") ? "SMS మాత్రమే" : m.equals("none") ? "ఏమీ పంపొద్దు, \"బాగున్నారా?\" అని అడిగితే చాలు" : "SMS + మొదటి వ్యక్తికి కాల్";
    }

    /** S18: his crying -> a small silent card (off unless he turns it on). */
    static boolean careOn(Context c) { return sp(c).getBoolean("cry_care", false); }

    static void setCare(Context c, boolean on) { sp(c).edit().putBoolean("cry_care", on).apply(); }

    /** S19: a child crying while he has earphones on (off unless he turns it on: only if there are kids at home). */
    static boolean childOn(Context c) { return sp(c).getBoolean("child_cry", false); }

    static void setChild(Context c, boolean on) { sp(c).edit().putBoolean("child_cry", on).apply(); }

    /** The sound model has work here. */
    static boolean wanted(Context c) { return fallOn(c) || careOn(c) || childOn(c); }

    // ---------------------------------------------------------------- scores

    /** How much this sound is a scream (shouting and yelling alone are everyday: only a little). */
    static float scream(float[] s) { return s[SCREAM] + 0.3f * Math.max(s[SHOUT], s[YELL]); }

    /** How much this sound is a heavy thud (a body falling, not a knock or a slammed door). */
    static float thud(float[] s) {
        return Math.max(Math.max(s[THUD], 0.8f * s[THUNK]), Math.max(0.7f * s[BANG], 0.6f * s[SMASH]));
    }

    /** A scream: clear, his own room (not children playing, not a song, the TV or the radio). */
    static boolean isScream(float[] s, double loud) {
        return scream(s) >= 0.5f && s[SCREAM] >= 0.35f && s[CHILD_SHOUT] < s[SCREAM] && s[BABY_CRY] < s[SCREAM]
                && s[MUSIC] < 0.3f && Math.max(s[TV], s[RADIO]) < 0.3f && loud >= 500;
    }

    static final int HANDS = 56, SNAP = 57, CLAPPING = 58;

    /** A heavy thud, well above the room, that is not a knock, a door, glass breaking or a clap (two claps call Jarvis). */
    static boolean isThud(float[] s, double loud, double noise) {
        float t = thud(s);
        return t >= 0.35f && loud >= Math.max(900, noise * 8) && s[KNOCK] + 0.5f * s[DOOR] < t && s[SLAM] < t && s[SHATTER] < 0.3f
                && s[MUSIC] < 0.3f && s[CLAPPING] + s[HANDS] + s[SNAP] < 0.3f;
    }

    /** His voice telling he is fine during the check: talk near the phone, not a cry for help, not the TV / radio / a song. */
    static boolean fineVoice(float[] s, double loud) {
        return s[SPEECH] >= 0.5f && loud >= 400 && scream(s) < 0.4f && distress(s) < 0.35f && Math.max(s[SHOUT], s[YELL]) < 0.4f
                && Math.max(Math.max(s[TV], s[RADIO]), s[MUSIC]) < 0.2f;
    }

    /** Distress right after a fall: a scream, groan, wail, cry or gasp. */
    static float distress(float[] s) {
        return Math.max(Math.max(scream(s), s[GROAN]), Math.max(Math.max(s[WAIL], s[CRYING]), Math.max(s[WHIMPER], s[GASP])));
    }

    /** His own crying (not a baby), not from a song. */
    static boolean isCrying(float[] s) {
        float cry = Math.max(s[CRYING], Math.max(s[WHIMPER], 0.8f * s[WAIL]));
        return cry >= 0.4f && s[BABY_CRY] < cry && s[MUSIC] < 0.3f && Math.max(s[TV], s[RADIO]) < 0.3f;
    }

    /** A baby / small child crying. */
    static boolean isChildCrying(float[] s) { return s[BABY_CRY] >= 0.4f && s[MUSIC] < 0.4f; }

    // ---------------------------------------------------------------- what the model heard

    /**
     * One loud sound with the model's scores (all 521), its loudness and the room's. Returns what it was taken for (test
     * screen), or null when it is none of these (or that feature is off): then the house / body sounds look at it.
     */
    static String heard(Context c, float[] s, double loud, double noise, boolean test, long soundAt) {
        long now = System.currentTimeMillis();
        // the "బాగున్నారా?" check is on: his voice twice (a sound that began well after Jarvis stopped talking; not a cry for
        // help, not the TV) means he is fine
        if (!test && CrashAlert.active && "fall".equals(CrashAlert.kind)) {
            if (fineVoice(s, loud) && soundAt > CrashAlert.startedAt && Announcer.silentAt(soundAt)) {
                long first = fineAt;
                if (first > CrashAlert.startedAt && soundAt - first >= 1000 && soundAt - first <= 15_000) {
                    fineAt = 0;
                    CrashAlert.ok(c);
                    return "మాట వినిపించింది → బాగున్నారు";
                }
                if (first <= CrashAlert.startedAt || soundAt - first > 15_000) { fineAt = soundAt; CrashAlert.heardOnce(c); }
                return "మాట వినిపించింది (ఇంకోసారి వింటే ఆపుతాను)";
            }
            return "(బాగున్నారా? చెక్ నడుస్తోంది)"; // during the check nothing else is said or asked
        }
        boolean fromPhone = speakerMedia(c); // a video / song on the phone's own speaker: its screams and cries are not his
        // a heavy thud heard a moment ago: what comes next tells whether he fell
        if (!test && fallAt != 0) {
            if (distress(s) >= 0.35f && !fromPhone) { fallAt = 0; alarm(c, "fall"); return "పడిన తర్వాత మూలుగు / అరుపు"; }
            if (s[SPEECH] >= 0.5f || s[WALK] >= 0.4f) normalAfter = true; // talk, footsteps: life goes on
        }
        if (isScream(s, loud) && !fromPhone) {
            if (test) return "అరుపు";
            if (fallOn(c)) { alarm(c, "scream"); return "అరుపు"; }
        }
        if (isThud(s, loud, noise) && !fromPhone) {
            if (test) return "ధబ్ మని పడిన శబ్దం";
            // the phone itself moved then (put down on the table, or in his pocket as he fell): a quiet room after it proves
            // nothing - only a groan / cry / scream after it asks
            boolean moved = Math.abs(Motion.movedAt - soundAt) < 2000;
            if (fallOn(c) && fallAt == 0 && now - lastAlarm > COOL_MS) {
                startWatch(moved);
                return "ధబ్ మని పడిన శబ్దం (గమనిస్తున్నాను" + (moved ? "; ఫోన్ కదిలింది" : "") + ")";
            }
        }
        if (isCrying(s) && !fromPhone) {
            if (test) return "ఏడుపు";
            if (careOn(c)) { cried(c); return "ఏడుపు"; }
        }
        if (isChildCrying(s)) {
            if (test) return "పిల్లల ఏడుపు";
            if (childOn(c)) { child(c, s[BABY_CRY]); return "పిల్లల ఏడుపు"; }
        }
        return null;
    }

    // ---------------------------------------------------------------- S15: a fall or a scream

    private static final long WATCH_MS = 8000, COOL_MS = 10 * 60_000L;
    /** A heavy thud heard (0 = none): the next 8 seconds are watched. */
    private static volatile long fallAt;
    private static volatile int loudAfter;
    private static volatile boolean normalAfter;
    private static volatile long lastAlarm, fineAt;

    /** The phone moved with the thud: only distress after it asks. */
    private static volatile boolean onlyDistress;

    private static void startWatch(boolean moved) {
        loudAfter = 0;
        normalAfter = false;
        onlyDistress = moved;
        fallAt = System.currentTimeMillis();
    }

    /** The microphone (re)starts: a watch from before a pause (a talk with Jarvis) is dropped, never judged on silence. */
    static void resetWatch() { fallAt = 0; }

    /**
     * Every 80 ms piece from the microphone (CoughDetector.feed), while a thud is being watched: counts the sound after it,
     * and when the 8 seconds are over asks if it stayed quiet (no talk or steps, only a few sounds).
     */
    static void tick(Context c, double rms, double noise) {
        long at = fallAt;
        if (at == 0) return;
        long now = System.currentTimeMillis();
        if (now - at > WATCH_MS + 2000) { fallAt = 0; return; } // the mic was off meanwhile: not judged on missing sound
        if (now - at < 1200) return; // the thud itself, its echo
        if (rms > Math.max(250, noise * 3)) loudAfter++;
        if (now - at < WATCH_MS) return;
        fallAt = 0;
        int h = java.time.LocalTime.now().getHour();
        if (decideAfter(normalAfter, loudAfter, h >= 22 || h < 7 || onlyDistress)) alarm(c, "fall");
    }

    /**
     * After the watch: ask when nobody talked or walked and at most about half a second of sound came (a still, quiet room).
     * onlyDistress (at night - no loud alarm at 3 am for a falling object - or when the phone itself moved): a thud alone
     * never asks; only a groan / cry / scream after it (asked at once, in heard()).
     */
    static boolean decideAfter(boolean normal, int loudPieces, boolean onlyDistress) { return !onlyDistress && !normal && loudPieces <= 6; }

    /** The question (or nothing, if it can't be asked now). Any thread. */
    private static void alarm(Context c, String why) {
        final Context app = c.getApplicationContext();
        main().post(() -> {
            long now = System.currentTimeMillis();
            if (!fallOn(app) || now - lastAlarm < COOL_MS || CrashAlert.active) return;
            Prefs p = new Prefs(app);
            // talking to Jarvis, in a call or riding (the ride's own crash check is there): he is clearly awake / not here
            if (CallControl.busyWithCall() || MainActivity.busyTalking() || p.driving() || Bike.riding(app)) return;
            lastAlarm = now;
            String mode = sosMode(app);
            CrashAlert.startFall(app, "scream".equals(why) ? "అరుపు వినిపించింది" : "పెద్ద శబ్దం (పడినట్టు) వినిపించింది",
                    "none".equals(mode) ? -1 : waitSeconds(app), "sms_call".equals(mode));
        });
    }

    // ---------------------------------------------------------------- S18: his crying

    private static final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

    private static synchronized int mark(String kind, long within) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = seen.computeIfAbsent(kind, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > within) q.removeFirst();
        return q.size();
    }

    private static volatile long lastCare, lastChild;

    /** Crying twice within 3 minutes -> the card, at most once in 3 hours; never in a call or while he talks to Jarvis. */
    private static void cried(Context c) {
        if (mark("cry", 3 * 60_000L) < 2) return;
        long now = System.currentTimeMillis();
        if (now - lastCare < 3 * 3600_000L || CallControl.busyWithCall() || MainActivity.busyTalking()) return;
        lastCare = now;
        card(c);
    }

    /** A silent card: no sound, no vibration, no words; "మాట్లాడదాం" opens Jarvis gently, "వద్దు" just closes it. */
    private static void card(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel("jarvis_care", "నేను ఉన్నాను (నిశ్శబ్ద కార్డ్)", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            String name = new Prefs(c).name();
            Intent talk = new Intent(c, SheetActivity.class)
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE, "నేను ఉన్నాను, " + name + ".")
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, "ఏం జరిగిందో చెప్పాలనిపిస్తే చెప్పండి, వింటాను.")
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, " [care: he tapped 'let's talk' on Jarvis's quiet card (Jarvis thought he sounded upset). "
                            + "Speak softly and briefly, warm, like a friend; listen more than you talk; don't say that you heard crying unless he brings it up; "
                            + "no advice unless he asks. If he mentions hurting himself or not wanting to live, stay with him, take it seriously and gently "
                            + "suggest calling someone he trusts or a helpline right now.]")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent yes = PendingIntent.getActivity(c, 7321, talk, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent no = PendingIntent.getBroadcast(c, 7322, new Intent(c, AlarmReceiver.class).setAction(ACTION_CARE_NO),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE_CARE, new Notification.Builder(c, "jarvis_care")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("💙 నేను ఉన్నాను")
                    .setContentText("మాట్లాడతారా?")
                    .setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(20 * 60_000L)
                    .addAction(new Notification.Action.Builder(null, "మాట్లాడదాం", yes).build())
                    .addAction(new Notification.Action.Builder(null, "వద్దు", no).build()).build());
        } catch (Exception ignored) {}
    }

    /** "వద్దు" on the card. */
    static void cardNo(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_CARE); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- S19: a child crying, he has earphones on

    private static void child(Context c, float score) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (!Sounds.earphones(am)) return; // he can hear it himself
        if (score < 0.5f && mark("child", 60_000L) < 2) return; // a faint one: a second within a minute
        long now = System.currentTimeMillis();
        Prefs p = new Prefs(c);
        if (now - lastChild < 3 * 60_000L || CallControl.busyWithCall() || MainActivity.busyTalking() || p.driving() || Bike.riding(c)) return;
        lastChild = now;
        Announcer.say(c, p.name() + ", పిల్లలు ఏడుస్తున్నారు."); // the song goes down while Jarvis says it
    }

    // ---------------------------------------------------------------- helpers

    /** A song / video is playing on the phone's own speaker (with earphones on, its sound isn't in the room). */
    static boolean speakerMedia(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.isMusicActive() && !Sounds.earphones(am);
        } catch (Exception e) { return false; }
    }
}
