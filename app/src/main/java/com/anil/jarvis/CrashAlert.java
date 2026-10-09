package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * "బాగున్నారా?" with a loud beep and a full-screen "నేను బాగున్నాను" button, then the SOS SMS with his location to his SOS
 * contacts (and a call to the first) when he doesn't answer:
 * - "crash": a hard knock while moving, then standing still (60 seconds);
 * - "fall": a scream or a heavy fall heard at home ({@link SafetySounds}); the wait (15 s / 30 s / 1 min) and what to send
 *   (SMS + call, SMS only, or nothing) are his choices; his voice or calling "Jarvis" also stops it.
 */
final class CrashAlert {
    private CrashAlert() {}

    static final String ACTION_OK = "com.anil.jarvis.CRASH_OK", ACTION_SEND = "com.anil.jarvis.CRASH_SEND";
    /** phone -> watch: the fall question was answered here (the wrist's question closes). */
    static final String P_FALL_END = "/jarvis/fall/end";
    static final int CRASH_SECONDS = 60;
    private static final int NOTE = 151;
    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean active;
    /** When the countdown started (after the question was spoken). */
    static volatile long startedAt;
    /** Seconds before the SOS goes; 0 or less = it goes only when he taps "ఇప్పుడే పంపు". */
    static volatile int seconds = CRASH_SECONDS;
    /** "crash" or "fall". */
    static volatile String kind = "crash";
    private static volatile boolean callFirst = true;
    private static volatile String heard = "";
    /** Set by {@link #sosNow}: the words of that SOS. */
    private static volatile String sosReason = "";
    private static ToneGenerator beeper;
    private static Context app;

    private static final Runnable beep = new Runnable() {
        @Override public void run() {
            if (!active) return;
            // "only the question": half a minute of beeps is enough (the screen stays)
            if (seconds <= 0 && System.currentTimeMillis() - startedAt > 30_000) return;
            try {
                if (beeper == null) beeper = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                beeper.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600);
            } catch (Exception ignored) {}
            main.postDelayed(this, 2500);
        }
    };
    private static final Runnable timeUp = () -> { if (app != null) send(app, false); };
    /** "Only the question" (no SOS by itself): the screen goes away after a while. */
    private static final Runnable giveUp = () -> { if (app != null && active && seconds <= 0) stopAll(app, true); };
    /** The countdown begins once the question has been said (at most 25 s of talking). */
    private static long askedAt;
    private static final Runnable countdown = new Runnable() {
        @Override public void run() {
            if (!active || app == null) return;
            if (Announcer.speaking() && System.currentTimeMillis() - askedAt < 25_000) { main.postDelayed(this, 300); return; }
            startedAt = System.currentTimeMillis();
            main.post(beep);
            if (seconds > 0) main.postDelayed(timeUp, seconds * 1000L);
            else main.postDelayed(giveUp, 3 * 60_000L);
        }
    };

    /** When the watch last felt a hard knock (W80), wall clock; 0 never. */
    static volatile long watchImpactAt;
    /** The fall was felt by the watch (it shows the question itself, so the phone's alert isn't sent there as well). */
    static volatile boolean fromWatch;
    private static volatile long softAt;
    static final String ACTION_SOFT_OK = "com.anil.jarvis.CRASH_SOFT_OK";
    private static final int NOTE_SOFT = 156;

    /**
     * W80: the ride's crash check. With the watch on his wrist and near, a knock the watch didn't feel too (the phone fell,
     * a pothole) first asks quietly on the wrist for 20 seconds; no answer -> the full check as before. A knock both felt
     * (or no watch) -> the full check at once. A real crash is never dropped. knockAt = when the phone felt the knock (wall
     * clock); the watch's knock counts when it was felt within 15 seconds of it.
     */
    static void start(Context c, long knockAt) {
        Context a = c.getApplicationContext();
        boolean wrist = WatchHub.known(a) && WatchHub.watchHere(a) && Boolean.TRUE.equals(WatchHub.worn(a)) && doubleCheck(a);
        boolean watchFelt = watchImpactAt > 0 && Math.abs(watchImpactAt - (knockAt > 0 ? knockAt : System.currentTimeMillis())) < 15_000L;
        if (wrist && !watchFelt && !active) { soft(a); return; }
        startNow(a);
    }

    static boolean doubleCheck(Context c) { return Travel.sp(c).getBoolean("crash_double", true); }

    private static final Runnable softUp = () -> { if (app != null && softAt != 0) { softAt = 0; clearSoft(app); startNow(app); } };

    private static void soft(Context c) {
        app = c;
        main.post(() -> {
            if (active || softAt != 0) return;
            softAt = System.currentTimeMillis();
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel("jarvis_awake", "మెలకువ చెక్", NotificationManager.IMPORTANCE_HIGH));
                PendingIntent ok = PendingIntent.getBroadcast(c, 157, new Intent(c, AlarmReceiver.class).setAction(ACTION_SOFT_OK), PendingIntent.FLAG_IMMUTABLE);
                PendingIntent send = PendingIntent.getBroadcast(c, 158, new Intent(c, AlarmReceiver.class).setAction(ACTION_SEND), PendingIntent.FLAG_IMMUTABLE);
                nm.notify(NOTE_SOFT, new Notification.Builder(c, "jarvis_awake").setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("🆘 దెబ్బ తగిలిందా?").setContentText("ఫోన్‌కి గట్టి దెబ్బ తగిలింది. బాగుంటే నొక్కండి (20 సెకన్లు)")
                        .setCategory(Notification.CATEGORY_ALARM).setTimeoutAfter(60_000L).setAutoCancel(true).setContentIntent(ok)
                        .addAction(new Notification.Action.Builder(null, "✅ బాగున్నాను", ok).build())
                        .addAction(new Notification.Action.Builder(null, "🆘 సహాయం", send).build()).build());
            } catch (Exception ignored) {}
            main.postDelayed(softUp, 20_000L);
        });
    }

    /** He tapped "బాగున్నాను" on the quiet check. */
    static void softOk(Context c) {
        main.post(() -> { main.removeCallbacks(softUp); softAt = 0; clearSoft(c); });
    }

    private static void clearSoft(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE_SOFT); } catch (Exception ignored) {}
    }

    /** W40: the 🆘 held on the watch (after its own 5 seconds): the SOS and the call, now. */
    static void sosNow(Context c, String what) {
        Context a = c.getApplicationContext();
        main.post(() -> {
            main.removeCallbacks(softUp);
            if (softAt != 0) { softAt = 0; clearSoft(a); }
            if (!active) { begin(a, "fall", 0, true, what); sosReason = what == null ? "" : what; }
            send(a, true);
        });
    }

    /**
     * W42: the watch felt a hard fall and no movement after: asked on the wrist and here, then the SOS (his fall settings).
     * On a ride it is the ride's crash check instead (a minute, then SMS + call): unless the bike is still moving (then it
     * was the road, and the wrist's question is closed).
     */
    static void fallFromWatch(Context c) {
        Context a = c.getApplicationContext();
        if (!WatchHub.fallOn(a)) return;
        if (active) { fromWatch = true; return; } // (the phone is asking already: the wrist's question closes with its answer)
        if (DriveService.running) {
            boolean moving = System.currentTimeMillis() - DriveService.lastKmhAt < 6000 && DriveService.lastKmh >= 12;
            if (moving) {
                try { WatchHub.send(a, P_FALL_END, new org.json.JSONObject().put("end", true)); } catch (Exception ignored) {}
                return;
            }
            fromWatch = true;
            main.post(() -> { main.removeCallbacks(softUp); if (softAt != 0) { softAt = 0; clearSoft(a); } });
            startNow(a);
            return;
        }
        String mode = SafetySounds.sosMode(a);
        fromWatch = true;
        startFall(a, "వాచ్‌కి గట్టి దెబ్బ తగిలి, తర్వాత కదలిక లేదు", "none".equals(mode) ? -1 : SafetySounds.waitSeconds(a), "sms_call".equals(mode));
    }

    /** A ride check the watch started, and he rides on (20 km/h+ for a few fixes): he is fine; it stops. */
    static void ridingOn(Context c) {
        main.post(() -> {
            if (!active || !fromWatch || !"crash".equals(kind)) return;
            stopAll(c, true);
            Announcer.say(c, "మీరు మళ్లీ బండి నడుపుతున్నారు: ప్రమాదం చెక్ ఆపాను.");
        });
    }

    /** The crash check proper. */
    private static void startNow(Context c) {
        main.post(() -> {
            if (active) return;
            if (new Prefs(c).sosContacts().trim().isEmpty()) {
                Announcer.say(c, "పెద్ద దెబ్బ తగిలినట్టు అనిపించింది. బాగున్నారా? మీ SOS కాంటాక్ట్స్ సెట్టింగ్స్‌లో లేరు, అవసరమైతే 112 కి కాల్ చేయండి.");
                if (fromWatch) { // (no SOS can go: the wrist's question doesn't promise one)
                    fromWatch = false;
                    try { WatchHub.send(c, P_FALL_END, new org.json.JSONObject().put("end", true)); } catch (Exception ignored) {}
                }
                return;
            }
            begin(c, "crash", CRASH_SECONDS, true, "");
            Announcer.say(app, new Prefs(app).name() + ", ప్రమాదం జరిగినట్టు అనిపిస్తోంది. బాగున్నారా? స్క్రీన్ మీద 'నేను బాగున్నాను' నొక్కండి. "
                    + "ఒక్క నిమిషంలో నొక్కకపోతే మీ వాళ్లకి మీ లొకేషన్‌తో SOS పంపుతాను.");
            notifyAsk(app);
            main.postDelayed(beep, 6000); // after the words
            main.postDelayed(timeUp, CRASH_SECONDS * 1000L);
            openScreen();
        });
    }

    /**
     * A scream or a heavy fall was heard at home: asks; after `secs` without an answer sends the SOS (and calls the first
     * contact if `withCall`). secs <= 0: only asks (he can still tap "ఇప్పుడే పంపు").
     */
    static void startFall(Context c, String what, int secs, boolean withCall) {
        main.post(() -> {
            if (active) return;
            Prefs p = new Prefs(c);
            boolean contacts = !p.sosContacts().trim().isEmpty();
            int s = contacts ? secs : 0; // no SOS contacts: it can only ask
            begin(c, "fall", s, withCall, what);
            String name = p.name();
            String say = name + ", బాగున్నారా? " + what + ". బాగుంటే \"నేను బాగున్నాను, ఏమీ కాలేదు\" అని చెప్పండి, లేదా స్క్రీన్ మీద నొక్కండి.";
            if (s > 0) say += " " + (s >= 60 ? "ఒక్క నిమిషంలో" : s + " సెకన్లలో") + " జవాబు రాకపోతే మీ వాళ్లకి SOS పంపుతాను.";
            else if (!contacts) say += " అవసరమైతే 112 కి కాల్ చేయండి.";
            Announcer.say(app, say);
            notifyAsk(app);
            askedAt = System.currentTimeMillis();
            main.postDelayed(countdown, 1500); // (the voice starts a moment later; then the countdown waits for it to finish)
            openScreen();
        });
    }

    /**
     * A fall check: his voice was heard once. The beeps rest a moment, Jarvis asks him to say it once more, and he gets at
     * least 10 more seconds before the SOS.
     */
    static void heardOnce(Context c) {
        main.post(() -> {
            if (!active || !"fall".equals(kind)) return;
            main.removeCallbacks(beep);
            main.postDelayed(beep, 6000);
            Announcer.say(c, "సరే, ఇంకోసారి చెప్పండి.");
            if (seconds > 0) {
                long left = seconds * 1000L - (System.currentTimeMillis() - startedAt);
                long extra = Math.max(0, 10_000 - left);
                startedAt += extra; // (the screen's seconds follow)
                main.removeCallbacks(timeUp);
                main.postDelayed(timeUp, Math.max(0, left) + extra);
            }
        });
    }

    private static void begin(Context c, String k, int secs, boolean call, String what) {
        active = true;
        kind = k;
        seconds = secs;
        callFirst = call;
        heard = what == null ? "" : what;
        app = c.getApplicationContext();
        // a fall: the countdown starts once the question is said (until then the screen shows the full time)
        startedAt = System.currentTimeMillis() + ("fall".equals(k) ? 60_000 : 0);
    }

    private static void openScreen() {
        try { app.startActivity(new Intent(app, CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    /** Seconds left before the SOS (for the screen). */
    static long secondsLeft() {
        long passed = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
        return Math.max(0, seconds - passed);
    }

    /** "నేను బాగున్నాను" (or his voice / "Jarvis" during a fall check). */
    static void ok(Context c) {
        main.post(() -> {
            if (!active) { stopAll(c, true); return; } // e.g. after Jarvis restarted: just clear the screen / notification
            boolean fall = "fall".equals(kind);
            stopAll(c, true);
            Announcer.say(c, fall ? "సరే, మంచిది. జాగ్రత్తగా ఉండండి." : "సరే, మంచిది. జాగ్రత్తగా వెళ్లండి.");
        });
    }

    /** Time up (or "ఇప్పుడే పంపు"): the SOS SMS; then the first contact is called (if that is wanted). */
    static void send(Context c, boolean byHim) {
        main.post(() -> {
            if (!active) return;
            boolean fall = "fall".equals(kind), call = callFirst || byHim;
            String what = byHim ? (!sosReason.isEmpty() ? sosReason // (sosNow: the reason it was sent)
                            : (fall ? "సహాయం కావాలి" : "ప్రమాదం జరిగింది") + (heard.isEmpty() ? "" : " (" + heard + ")"))
                    : fall ? (fromWatch ? "" : "ఇంట్లో ") + heard + " (Jarvis గుర్తించింది), " + seconds + " సెకన్లు జవాబు ఇవ్వలేదు. ఒకసారి ఫోన్ చేసి చూడండి"
                    : "బైక్ / కారు ప్రమాదం జరిగి ఉండొచ్చు (Jarvis గుర్తించింది), ఒక నిమిషం జవాబు ఇవ్వలేదు";
            stopAll(c, false); // the screen stays up: it can call the first contact
            new Thread(() -> {
                List<String[]> sent = Sos.send(c, what);
                StringBuilder names = new StringBuilder();
                for (String[] s : sent) names.append(names.length() > 0 ? ", " : "").append(s[0]);
                String line = sent.isEmpty() ? "SOS పంపలేకపోయాను (SMS అనుమతి / కాంటాక్ట్స్ చూడండి). 112 కి కాల్ చేయండి." : "SOS పంపాను: " + names;
                boolean called = !sent.isEmpty() && call && callFirst(c, sent.get(0)[1]);
                if (!sent.isEmpty() && call && !called) line += ". " + sent.get(0)[0] + " కి కాల్ చేయలేకపోయాను, మీరే చేయండి";
                Reminders.notify(c, "🆘 SOS", line, NOTE + 1);
                Announcer.say(c, line);
            }, "jarvis-sos").start();
        });
    }

    /**
     * The call to the first SOS contact: straight away when Android lets Jarvis start it ("display over other apps"), else from
     * the alert screen if it is up. False when no call could be started (he is told).
     */
    private static boolean callFirst(Context c, String number) {
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false;
        if (android.provider.Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {}
        }
        return CrashActivity.callIfShowing(number);
    }

    private static void stopAll(Context c, boolean closeScreen) {
        active = false;
        sosReason = "";
        if (fromWatch) { // the question on the wrist goes too
            fromWatch = false;
            try { WatchHub.send(c, P_FALL_END, new org.json.JSONObject().put("end", true)); } catch (Exception ignored) {}
        }
        main.removeCallbacks(beep);
        main.removeCallbacks(timeUp);
        main.removeCallbacks(giveUp);
        main.removeCallbacks(countdown);
        try { if (beeper != null) { beeper.stopTone(); beeper.release(); } } catch (Exception ignored) {}
        beeper = null;
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
        if (closeScreen) CrashActivity.closeIfShowing();
    }

    private static void notifyAsk(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_crash", "ప్రమాదం గుర్తింపు", NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            PendingIntent screen = PendingIntent.getActivity(c, 152, new Intent(c, CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent ok = PendingIntent.getBroadcast(c, 153, new Intent(c, AlarmReceiver.class).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent send = PendingIntent.getBroadcast(c, 154, new Intent(c, AlarmReceiver.class).setAction(ACTION_SEND), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_crash").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🆘 బాగున్నారా?")
                    .setContentText(seconds > 0 ? seconds + " సెకన్లలో జవాబు లేకపోతే SOS వెళ్తుంది" : "సహాయం కావాలంటే 'ఇప్పుడే పంపు' నొక్కండి")
                    .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
                    .setFullScreenIntent(screen, true).setContentIntent(screen)
                    .addAction(new Notification.Action.Builder(null, "✅ నేను బాగున్నాను", ok).build())
                    .addAction(new Notification.Action.Builder(null, "🆘 ఇప్పుడే పంపు", send).build()).build());
        } catch (Exception ignored) {}
    }
}
