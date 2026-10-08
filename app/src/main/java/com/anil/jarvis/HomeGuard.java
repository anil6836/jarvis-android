package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * S14, on the old phone at home keeping guard ({@link Guard}): besides the camera, its microphone listens for house sounds
 * with the same sound model as coughs, and each goes to his Telegram with a line and the camera's picture of that moment:
 * a smoke / fire alarm (again every minute while it rings, until he answers on Telegram or it stops), glass breaking, a
 * scream, a knock or the calling bell, footsteps or voices (while nobody should be home), a dog barking a long time at
 * night. Each kind at most once in 5 minutes. Only while the guard is on (and on duty days only, if he chose that).
 * Nothing personal is asked on this phone, and nothing is recorded: the sound itself is never sent.
 */
final class HomeGuard {
    private HomeGuard() {}

    static final int SPEECH = 0, SCREAM = 11, WALK = 48, DOG = 69, BARK = 70, BOWWOW = 73, MUSIC = 132, DOOR = 348,
            DOORBELL = 349, DINGDONG = 350, KNOCK = 353, SMOKE = 393, FIRE_ALARM = 394, GLASS = 435, CHINK = 436, SHATTER = 437,
            SMASH = 463, BREAKING = 464, TV = 518, RADIO = 519;

    /** Listen for house sounds while the guard is on (default on). */
    static boolean soundsOn(Context c) { return Guard.sp(c).getBoolean("sounds", true); }

    static void setSounds(Context c, boolean on) { Guard.sp(c).edit().putBoolean("sounds", on).apply(); }

    private static volatile boolean micOk;
    private static volatile long micLookedAt;

    /** Look at the mic permission again now (it was just given). */
    static void refresh() { micLookedAt = 0; }

    /** This phone keeps guard and listens now. (Asked every 80 ms by the mic: the permission is looked at every 10 s.) */
    static boolean listening(Context c) {
        if (!Guard.running(c) || !soundsOn(c)) return false;
        long now = System.currentTimeMillis();
        if (now - micLookedAt > 10_000) {
            micOk = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            micLookedAt = now;
        }
        return micOk;
    }

    // ---------------------------------------------------------------- what the model heard

    /** {kind, how many needed, within ms, the Telegram line} for this sound, or null. */
    static String[] kindOf(float[] s, int hour) {
        float smoke = Math.max(s[SMOKE], s[FIRE_ALARM]);
        if (smoke >= 0.35f) return new String[]{"smoke", "2", "60000", "🔥 ఇంట్లో స్మోక్ / ఫైర్ అలారం మోగుతోంది!"};
        if (s[SHATTER] >= 0.35f || (s[GLASS] + s[CHINK] >= 0.5f && Math.max(s[SMASH], s[BREAKING]) >= 0.2f))
            return new String[]{"glass", "1", "0", "🪟 ఇంట్లో గాజు పగిలిన శబ్దం వినిపించింది"};
        if (SafetySounds.isScream(s, 600)) return new String[]{"scream", "1", "0", "😱 ఇంట్లో అరుపు వినిపించింది"};
        boolean music = s[MUSIC] >= 0.4f;
        if (Math.max(s[DOORBELL], s[DINGDONG]) >= (music ? 0.6f : 0.35f)) return new String[]{"door", "1", "0", "🔔 ఇంటి కాలింగ్ బెల్ మోగింది"};
        if (s[KNOCK] + 0.5f * s[DOOR] >= (music ? 0.7f : 0.45f)) return new String[]{"door", "1", "0", "🚪 ఎవరో తలుపు తడుతున్నారు"};
        boolean night = hour >= 22 || hour < 6;
        if (night && Math.max(s[BARK], Math.max(s[DOG], s[BOWWOW])) >= 0.4f) return new String[]{"dog", "4", "120000", "🐕 రాత్రి కుక్క చాలాసేపు మొరుగుతోంది"};
        boolean media = Math.max(s[TV], Math.max(s[RADIO], s[MUSIC])) >= 0.3f;
        if (s[WALK] >= 0.4f && !media) return new String[]{"steps", "2", "60000", "👣 ఇంట్లో అడుగుల శబ్దం వినిపిస్తోంది"};
        if (s[SPEECH] >= 0.6f && !media) return new String[]{"voices", "2", "60000", "🗣️ ఇంట్లో మాటలు వినిపిస్తున్నాయి"};
        return null;
    }

    private static final Map<String, ArrayDeque<Long>> seen = new HashMap<>();
    private static final Map<String, Long> sent = new HashMap<>();

    private static synchronized int mark(String kind, long within) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = seen.computeIfAbsent(kind, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > within) q.removeFirst();
        return q.size();
    }

    /** A loud sound on the guard phone. Returns what it was taken for (test screen), or null. */
    static String heard(Context c, float[] s, double loud, int loudN, boolean test) {
        String[] k = kindOf(s, java.time.LocalTime.now().getHour());
        if (k == null) return null;
        String label = "కాపలా: " + k[3].substring(k[3].indexOf(' ') + 1);
        if (test) return label;
        if (Guard.dutyOnly(c) && !onDuty(c)) return label;
        int need = Integer.parseInt(k[1]);
        if (need > 1 && mark(k[0], Long.parseLong(k[2])) < need) return label;
        if (!due(k[0], System.currentTimeMillis())) return label;
        if (k[0].equals("smoke")) smoke(c, k[3]);
        else alert(c, k[3]);
        return label;
    }

    /** Once per kind in 5 minutes (the smoke alarm every minute). */
    static synchronized boolean due(String kind, long now) {
        Long last = sent.get(kind);
        long gap = kind.equals("smoke") ? 60_000L : 5 * 60_000L;
        if (last != null && now - last < gap) return false;
        sent.put(kind, now);
        return true;
    }

    // ---------------------------------------------------------------- the smoke alarm

    private static long smokeFirst, smokeLast;
    private static int smokeSent;
    private static boolean smokeAnswered;

    /** Every minute while it rings: up to 15 times, until he answers on Telegram; quiet 10 minutes = a new one. */
    private static synchronized void smoke(Context c, String line) {
        long now = System.currentTimeMillis();
        if (now - smokeLast > 10 * 60_000L) { smokeFirst = now; smokeSent = 0; smokeAnswered = false; }
        smokeLast = now;
        if (smokeAnswered || smokeSent >= 15) return;
        final int n = ++smokeSent;
        final long first = smokeFirst;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (n > 1 && Guard.repliedSince(app, first / 1000)) { synchronized (HomeGuard.class) { smokeAnswered = true; } return; }
            send(app, line + (n > 1 ? " (" + n + "వ సారి; Telegram లో ఏదైనా జవాబిస్తే ఆపుతాను)" : " (చూశారంటే Telegram లో ఏదైనా జవాబివ్వండి)"));
        }, "jarvis-guard-smoke").start();
    }

    private static void alert(Context c, String line) {
        final Context app = c.getApplicationContext();
        new Thread(() -> send(app, line), "jarvis-guard-sound").start();
    }

    /** The line with the time, and the camera's picture of this moment when it has one. Background thread. */
    private static void send(Context c, String line) {
        String time = new java.text.SimpleDateFormat("h:mm a, d MMM", Locale.ENGLISH).format(new java.util.Date());
        String caption = "🚨 Jarvis కాపలా: " + line + " · " + time;
        byte[] photo = GuardService.snap(3000);
        boolean ok = photo != null && Guard.sendPhoto(c, photo, caption);
        if (!ok) Guard.send(c, caption);
    }

    private static boolean onDuty(Context c) {
        Duty.Roster r = Duty.load(c);
        return !Duty.ready(r) || Duty.onDutyAt(r, java.time.LocalDateTime.now()); // no calendar on this phone: always
    }
}
