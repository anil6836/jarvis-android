package com.anil.jarvis;

import android.app.AlarmManager;
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
import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 🚗 The car sleep alert (Anil, 18 Oct: "బైక్ మీద వద్దు, కారు లో ఉన్నప్పుడు"): only in the car — its own Bluetooth, or
 * "Jarvis, కారు మోడ్". At night (10 pm–5 am), after a duty with no sleep, after a short night, after 2 hours of driving,
 * or when he says "నిద్ర వస్తోంది", Jarvis asks "మెలకువగా ఉన్నారా?" every few minutes in the phone's own voice (at once,
 * also without internet). His answer: calling "Jarvis" (the wake word's mic), or a ✅ tap (phone / watch). A sound like a
 * word that isn't "Jarvis" is never taken as his answer - it only gets the question once more.
 * No answer while the car is moving -> a loud alarm, the watch buzzes hard, "కారు పక్కకి ఆపండి", and the question again
 * in 2 minutes. With the eye check (CarEyes) the camera's face finder rings the same alarm the moment his eyes stay
 * closed or his head drops. The bike has none of this (only the break word and the ride log stay there).
 */
final class CarCare {
    private CarCare() {}

    static final String ACTION_CHECK = "com.anil.jarvis.CAR_CHECK", ACTION_OK = "com.anil.jarvis.CAR_OK";
    private static final long MIN = 60000L, DAY = 24 * 60 * MIN, LONGEST = 14 * 60 * MIN, BT_GRACE = 60_000, NO_SPEED = 3 * 60 * MIN;
    private static final int NOTE_ASK = 245, NOTE_ALARM = 246, NOTE_EYES = 247, WATCH_ALARM = 24601, WATCH_ASK = 24602;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_car_care", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- his settings (Settings → 🏍️ బైక్, ఇల్లు → 🚗 కారు నిద్ర అలర్ట్)

    static SharedPreferences settings(Context c) { return Drive.settings(c); }
    static boolean enabled(Context c) { return settings(c).getBoolean("drive_fatigue", true); }
    static boolean opt(Context c, String key) { return settings(c).getBoolean(key, true); } // car_night, car_duty, car_sleep, car_long
    static int every(Context c) { return Math.max(10, Math.min(30, settings(c).getInt("car_every", 15))); }
    /** "night" (only while a reason is on: night / tiredness / long drive / sleepy), "always" (all the time in the car) or "off". */
    static String eyesMode(Context c) { return settings(c).getString("car_eyes", "night"); }
    /** "front" (the phone's front camera), "usb" (an outside camera on the phone's USB) or "url" (a Wi-Fi camera's link). */
    static String eyesSource(Context c) { return settings(c).getString("car_eyes_src", "front"); }
    static String eyesUrl(Context c) { return settings(c).getString("car_eyes_url", "").trim(); }
    static boolean eyesAgreed(Context c) { return sp(c).getBoolean("eyes_ok", false); }
    static void agreeEyes(Context c) { sp(c).edit().putBoolean("eyes_ok", true).apply(); }

    // ---------------------------------------------------------------- car mode on / off

    /** In the car now (car mode on, not left on by a lost "off"). */
    static boolean on(Context c) {
        SharedPreferences s = sp(c);
        return s.getBoolean("on", false) && System.currentTimeMillis() - s.getLong("since", 0) < LONGEST;
    }

    /**
     * The car's Bluetooth connected ("bt"), or he said "కారు మోడ్" ("voice"). speak: says it aloud now (the Bluetooth way);
     * else the words are only returned, for Jarvis's own answer. Returns those words.
     */
    static synchronized String start(Context c, String how, boolean speak) {
        final Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        SharedPreferences s = sp(app);
        long gone = s.getLong("bt_gone", 0);
        if (on(app) && gone > 0 && now - gone >= BT_GRACE) stop(app, "bt"); // (its minute passed unseen - the app was closed: that trip ends now)
        if (on(app)) {
            if ("bt".equals(how)) { s.edit().putString("how", "bt").remove("bt_gone").apply(); tripEnd = null; } // (a Bluetooth blip: the same trip)
            return "కారు మోడ్ ఇప్పటికే ఆన్‌లో ఉంది. " + statusWords(app);
        }
        s.edit().putBoolean("on", true).putLong("since", now).putString("how", how).putInt("misses", 0).remove("asked_at")
                .remove("sleepy_until").remove("tired_until").remove("told").remove("eyes_told").remove("bt_gone").apply();
        if ("voice".equals(how)) { // (the Bluetooth way starts these itself in Life.carBluetooth)
            try { RideCare.started(app, false); } catch (Exception ignored) {} // first: no weather word over Jarvis's answer
            try { Drive.startDrive(app); } catch (Exception ignored) {} // speed (moving or parked?), 2 hours, parking spot
        }
        String name = new Prefs(app).name();
        StringBuilder say = new StringBuilder("కారు మోడ్ ఆన్");
        if ("bt".equals(how)) say.append(", డ్రైవింగ్ మోడ్ కూడా. మెసేజ్‌లు, కాల్స్ నేను చెబుతాను");
        say.append(". ");
        List<String> r = reasons(app);
        WakeService.recheck(app); // (his phone with the car's own Jarvis: its "Jarvis" mic rests for the trip)
        if (CarBox.phoneQuiet(app)) { // his phone: the car's box asks the questions and hears "Jarvis" (never both)
            say.append("కారు Jarvis (box) నిద్ర అలర్ట్ చూసుకుంటాడు; ఫోన్ \"Jarvis\" వినదు. జాగ్రత్తగా వెళ్లండి, ").append(name).append(".");
            schedule(app, 10);
            String text = say.toString();
            if (speak) Announcer.sayPhone(app, text);
            if (enabled(app) && "always".equals(eyesMode(app))) main.postDelayed(() -> maybeEyes(app, false), 8000); // (the phone in its holder: its eye check, C15)
            return text;
        }
        if (!enabled(app)) {
            say.append("జాగ్రత్తగా వెళ్లండి, ").append(name).append(".");
        } else if (r.isEmpty()) {
            say.append("రాత్రి, అలసట, లేదా 2 గంటల డ్రైవింగ్ అయితే మెలకువగా ఉన్నారా అని అడుగుతాను. నిద్ర వస్తే 'Jarvis, నిద్ర వస్తోంది' అనండి.");
        } else {
            s.edit().putString("told", String.join("|", r)).apply();
            say.append(String.join(", ", r)).append(". అందుకే ప్రతి ").append(every(app))
                    .append(" నిమిషాలకి మెలకువగా ఉన్నారా అని అడుగుతాను; ").append(howToAnswer()).append(".");
        }
        schedule(app, enabled(app) && !r.isEmpty() ? every(app) : 10);
        String text = say.toString();
        if (speak) Announcer.sayPhone(app, text);
        if (enabled(app)) {
            if (speak) maybeEyes(app, false);
            else main.postDelayed(() -> maybeEyes(app, false), 8000); // (after Jarvis has said his answer)
        }
        return text;
    }

    /**
     * After the device restarted (the car's box starts with the car): Android forgot the timer, but car mode may still be
     * marked on. The questions go on: the timer is set again (a tick in a minute decides the rest).
     */
    static void rearm(Context c) {
        Context app = c.getApplicationContext();
        if (on(app)) schedule(app, 1);
    }

    /** The trip's end (Life.tripEnd: drive alerts off, the parking spot), waiting out the Bluetooth's minute. */
    private static Runnable tripEnd;

    /**
     * The car's Bluetooth went: car mode and the trip (endTrip) end after a minute, unless it comes back (a head unit's
     * blip is the same trip: no new "కారు మోడ్ ఆన్", the parking spot and the drive alerts untouched). False: car mode
     * isn't on - the caller ends the trip itself, now.
     */
    static boolean btGone(Context c, Runnable endTrip) {
        final Context app = c.getApplicationContext();
        if (!on(app)) return false;
        final long at = System.currentTimeMillis();
        sp(app).edit().putLong("bt_gone", at).apply();
        tripEnd = endTrip;
        main.postDelayed(() -> { if (sp(app).getLong("bt_gone", 0) == at) stop(app, "bt"); }, BT_GRACE);
        return true;
    }

    /** The car's Bluetooth is back within its minute. */
    static boolean btBlip(Context c) {
        long gone = sp(c).getLong("bt_gone", 0);
        return on(c) && gone > 0 && System.currentTimeMillis() - gone < BT_GRACE;
    }

    /** The car's Bluetooth went under a minute ago (he may be parking / getting out): no questions, no alarms. */
    private static boolean btWaiting(Context c) { return sp(c).getLong("bt_gone", 0) > 0; }

    /** He said "కారు మోడ్ ఆపు" ("voice"), the Bluetooth is gone ("bt"), the drive ended ("drive"), or left on too long ("old"). */
    static synchronized void stop(Context c, String why) {
        Context app = c.getApplicationContext();
        SharedPreferences s = sp(app);
        if (!s.getBoolean("on", false)) return;
        String how = s.getString("how", "");
        boolean gone = s.getLong("bt_gone", 0) > 0;
        s.edit().putBoolean("on", false).remove("asked_at").remove("sleepy_until").remove("tired_until").remove("bt_gone").apply();
        if (gone) { // the Bluetooth's trip ends with it (also when the minute's timer was lost with the app)
            Runnable e = tripEnd;
            tripEnd = null;
            final Context a = app;
            if (e == null) e = () -> Life.tripEnd(a, true, null);
            if (Looper.myLooper() == Looper.getMainLooper()) e.run(); else main.post(e); // (now: a new trip may start right after)
        }
        Announcer.hold(app, false);
        cancel(app);
        endListen();
        WakeService.recheck(app); // (his phone: "Jarvis" listens again after a quiet trip)
        clear(app, NOTE_ASK);
        clear(app, NOTE_EYES);
        WatchAlerts.directGone(app, WATCH_ASK);
        try { CarEyes.stop(app); } catch (Exception ignored) {}
        if ("voice".equals(how) && !"drive".equals(why) && !gone) { // (started by voice: its drive alerts end with it; the Bluetooth way's own end does that)
            try { Drive.stopDrive(app); } catch (Exception ignored) {}
            try { RideCare.ended(app); } catch (Exception ignored) {}
        }
    }

    /**
     * The drive alerts ended (DriveService): "parked" (stood 10 minutes), "stop" (he ended the drive), "nogps" / "refused".
     * Only a car mode he started by voice follows it, and only for a real end: the car's Bluetooth decides its own.
     */
    static void driveEnded(Context c, String why) {
        Context app = c.getApplicationContext();
        if (!on(app) || !"voice".equals(sp(app).getString("how", ""))) return;
        if ("parked".equals(why)) {
            stop(app, "drive");
            Announcer.sayPhone(app, "కారు 10 నిమిషాలుగా ఆగి ఉంది, కారు మోడ్ ఆపాను. మళ్ళీ బయలుదేరితే 'Jarvis, కారు మోడ్' అనండి.");
        } else if ("stop".equals(why)) {
            stop(app, "drive");
        } // nogps / refused: car mode goes on (the questions don't need the GPS; the speed then counts as unknown)
    }

    // ---------------------------------------------------------------- why now

    /** The reasons for questions right now (empty: none). */
    static List<String> reasons(Context c) {
        long now = System.currentTimeMillis();
        boolean dutyTired = false, shortSleep = false;
        try {
            long m = Duty.minutesSinceDuty(c);
            dutyTired = m >= 0 && Sleep.sleptSince(c, now - m * MIN) < 180;
        } catch (Exception ignored) {}
        try { // under 5 hours of real sleep in the last day (only when his sleep is known at all)
            boolean known = Sleep.between(c, now - 2 * DAY, now + MIN)[0] > 0;
            shortSleep = known && Sleep.sleptSince(c, now - DAY) < 300;
        } catch (Exception ignored) {}
        int hour = java.time.LocalTime.now().getHour();
        return CarSense.reasons(opt(c, "car_night"), hour, opt(c, "car_duty"), dutyTired, opt(c, "car_sleep"), shortSleep,
                opt(c, "car_long"), drivingMinutes(c), saidSleepy(c), tired(c));
    }

    /** He said "నిద్ర వస్తోంది" (an hour). */
    static boolean saidSleepy(Context c) { return sp(c).getLong("sleepy_until", 0) > System.currentTimeMillis(); }

    /** Jarvis saw tiredness: a missed answer, closed or heavy eyes (half an hour). */
    static boolean tired(Context c) { return sp(c).getLong("tired_until", 0) > System.currentTimeMillis(); }

    private static boolean often(Context c) { return saidSleepy(c) || tired(c); }

    /** How long he has driven without a real stop (the drive alerts' count; else since car mode began). */
    static long drivingMinutes(Context c) {
        long now = System.currentTimeMillis();
        if (DriveService.running && DriveService.movingSinceAt > 0 && now - DriveService.lastMovingAt < 15 * MIN)
            return (now - DriveService.movingSinceAt) / MIN;
        if (speedKnown()) return 0; // (stopped 15 minutes or more: a break)
        return on(c) ? (now - sp(c).getLong("since", now)) / MIN : 0;
    }

    /** The drive alerts' GPS speed is there and fresh. */
    private static boolean speedKnown() {
        return DriveService.running && DriveService.lastKmhAt > 0 && System.currentTimeMillis() - DriveService.lastKmhAt < 2 * MIN;
    }

    /** Fine to ask now: moving in the last 3 minutes (a signal, a toll), or the speed isn't known. */
    static boolean askable(Context c) {
        if (box(c)) return speedKnown() && System.currentTimeMillis() - DriveService.lastMovingAt < 3 * MIN;
        return !btWaiting(c) && (!speedKnown() || System.currentTimeMillis() - DriveService.lastMovingAt < 3 * MIN);
    }

    /** This is the car's own box (CarBox): its car mode lasts while it is on, and only its GPS says the car moves. */
    private static boolean box(Context c) { return "box".equals(sp(c).getString("how", "")) || CarBox.on(c); }

    /** Fine to ring the loud alarm: moving in the last minute, or the speed isn't known (never over a nap in a parked car). */
    static boolean alarmable(Context c) {
        if (box(c)) return speedKnown() && System.currentTimeMillis() - DriveService.lastMovingAt < 60_000;
        return !btWaiting(c) && (!speedKnown() || System.currentTimeMillis() - DriveService.lastMovingAt < 60_000);
    }

    // ---------------------------------------------------------------- the timer

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 244, new Intent(c, AlarmReceiver.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long when = System.currentTimeMillis() + minutes * MIN;
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c));
        } catch (Exception e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c)); } catch (Exception ignored) {}
        }
    }

    private static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pi(c));
    }

    /** The timer came: tell a new reason once, then ask (only while driving, never during a call or a talk with Jarvis). */
    static void tick(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences s = sp(app);
        if (!s.getBoolean("on", false)) return;
        long gone = s.getLong("bt_gone", 0);
        if (!on(app) || gone > 0 && System.currentTimeMillis() - gone > BT_GRACE) { stop(app, "old"); return; } // (a lost "off")
        if (gone > 0) { schedule(app, 1); return; } // the Bluetooth went a moment ago (parking?): no question now
        // started by voice, and no speed for 3 hours (no GPS): maybe home long ago - never questions and alarms all night
        if ("voice".equals(s.getString("how", "")) && !speedKnown()
                && System.currentTimeMillis() - Math.max(s.getLong("since", 0), DriveService.lastKmhAt) > NO_SPEED) {
            stop(app, "old");
            Announcer.sayPhone(app, new Prefs(app).name() + ", 3 గంటలుగా కారు స్పీడ్ తెలియడం లేదు (GPS లేదు), అందుకే కారు మోడ్ ఆపాను. "
                    + "ఇంకా డ్రైవ్ చేస్తుంటే 'Jarvis, కారు మోడ్' అనండి.");
            return;
        }
        if (!enabled(app)) { schedule(app, 10); return; }
        List<String> r = reasons(app);
        if (CarBox.phoneQuiet(app)) r = CarSense.phoneOwn(r); // (his phone with the car's own Jarvis: the box asks, but not about his duty / sleep)
        if (r.isEmpty()) { s.edit().remove("told").apply(); schedule(app, 10); return; }
        int next = CarSense.nextMinutes(often(app), false, every(app));
        // a reason he hasn't heard yet: said once (only the new ones), then the questions begin
        Set<String> old = new HashSet<>(Arrays.asList(s.getString("told", "").split("\\|")));
        StringBuilder fresh = new StringBuilder();
        for (String x : r) if (!old.contains(x)) fresh.append(fresh.length() == 0 ? "" : ", ").append(x);
        s.edit().putString("told", String.join("|", r)).apply();
        if (fresh.length() > 0) {
            String extra = fresh.indexOf("2 గంటలకు పైగా") >= 0 ? " ఒకసారి ఆగి టీ తాగి, కాసేపు నడవండి." : "";
            Announcer.sayPhone(app, new Prefs(app).name() + ", " + fresh + "." + extra
                    + " ఇక నుంచి " + next + " నిమిషాలకి ఒకసారి మెలకువగా ఉన్నారా అని అడుగుతాను.");
            maybeEyes(app, false);
            schedule(app, 2);
            return;
        }
        if (!askable(app)) { schedule(app, 3); return; } // standing (parked): asked when he moves again
        if (CallControl.busyWithCall() || MainActivity.busyTalking()) { schedule(app, next); return; } // talking: awake
        schedule(app, next); // (first: a lost window must never stop the questions)
        ask(app);
    }

    // ---------------------------------------------------------------- the question and his answer

    /** The question is waiting for his answer (asked under 3 minutes ago, in car mode). */
    static boolean asking(Context c) {
        long a = sp(c).getLong("asked_at", 0);
        return a != 0 && System.currentTimeMillis() - a < 3 * MIN && on(c);
    }

    /** How he answers now: "Jarvis" while the wake word's mic is open, else the ✅. */
    private static String howToAnswer() {
        if (WakeService.hearing) return "'Jarvis' అని పిలవండి";
        Context a = appCtx;
        return a != null && CarBox.on(a) ? "స్క్రీన్ మీద ✅ నొక్కండి" : "ఫోన్ నోటిఫికేషన్‌లో ✅ నొక్కండి (వాచ్‌లో కూడా)";
    }

    /** How long ago the loud alarm rang (ms; the car screen shows "కారు పక్కకి ఆపండి" for a minute after it). */
    static long alarmAgoMs() { return SystemClock.elapsedRealtime() - lastAlarm; }

    static void ask(Context c) { ask(c, false); }

    /** again: once more, after a sound like a word that wasn't "Jarvis" (maybe him, said the wrong way). */
    private static void ask(Context c, boolean again) {
        final Context app = c.getApplicationContext();
        final long at = again ? sp(app).getLong("asked_at", 0) : System.currentTimeMillis();
        if (at == 0) return;
        if (!again) sp(app).edit().putLong("asked_at", at).apply();
        appCtx = app;
        Announcer.hold(app, true); // other announcements wait until his answer window ends (nothing talks over his "Jarvis")
        final boolean voice = WakeService.hearing; // "Jarvis" can only be heard while the wake word's mic is open
        String name = new Prefs(app).name();
        Announcer.sayPhone(app, again ? name + ", వినిపించలేదు. " + howToAnswer() + "."
                : name + ", మెలకువగా ఉన్నారా? " + howToAnswer() + ".");
        if (!again) askCards(app);
        android.os.PowerManager.WakeLock wl = null;
        try {
            android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:car-check");
            wl.acquire(70_000);
        } catch (Exception ignored) {}
        final android.os.PowerManager.WakeLock lock = wl;
        final long t0 = SystemClock.elapsedRealtime();
        main.postDelayed(new Runnable() { // his window opens when the question has been said
            @Override public void run() {
                if (sp(app).getLong("asked_at", 0) != at) { release(lock); return; }
                if (Announcer.speaking() && SystemClock.elapsedRealtime() - t0 < 25_000) { main.postDelayed(this, 200); return; }
                listen(app, at, lock, voice, again);
            }
        }, 600);
    }

    private static void release(android.os.PowerManager.WakeLock l) {
        try { if (l != null && l.isHeld()) l.release(); } catch (Exception ignored) {}
    }

    /** True while the mic's sounds are looked at (the wake word's mic feeds {@link #hear}). */
    static volatile boolean listening;
    private static volatile CarSense.Answer answer;
    /** A short sound like a spoken word came in the window (maybe him, not saying "Jarvis"): never an answer by itself. */
    private static volatile boolean heard;
    private static volatile Context appCtx;
    /** Another app plays on the phone now (Maps' voice, songs): its sound is not looked at (checked often). */
    private static volatile boolean otherSound;
    private static final long WINDOW_MS = 12_000, TAP_WINDOW_MS = 20_000, AGAIN_MS = 8_000, MORE_MS = 4_000, LONGEST_WINDOW_MS = 40_000;

    /** His window: "Jarvis" / ✅ answer. Jarvis's own voice inside it (another announcement) gives him more time after it. */
    private static void listen(Context app, long at, android.os.PowerManager.WakeLock lock, boolean voice, boolean again) {
        appCtx = app;
        answer = null;
        heard = false;
        otherSound = otherPlaying(app);
        Duck.on(app); // songs on the phone go quiet while he answers
        listening = voice;
        final long start = SystemClock.elapsedRealtime();
        final long[] endAt = {start + (again ? AGAIN_MS : voice ? WINDOW_MS : TAP_WINDOW_MS)};
        main.post(new Runnable() {
            @Override public void run() {
                if (sp(app).getLong("asked_at", 0) != at) { endListen(); Duck.off(); release(lock); return; } // answered / car mode off
                long now = SystemClock.elapsedRealtime();
                if (voice) otherSound = otherPlaying(app);
                if (Announcer.audible()) endAt[0] = Math.min(start + LONGEST_WINDOW_MS, Math.max(endAt[0], now + MORE_MS));
                if (now < endAt[0]) { main.postDelayed(this, 250); return; }
                boolean maybe = heard;
                endListen();
                Duck.off();
                release(lock);
                if (MainActivity.busyTalking() || CallControl.busyWithCall()) { answered(app, "talk"); return; } // talking: awake
                if (maybe && !again && voice) { ask(app, true); return; } // a word, but not "Jarvis": asked once more
                Announcer.hold(app, false);
                noAnswer(app);
            }
        });
    }

    private static void endListen() {
        listening = false;
        answer = null;
    }

    /** One 80 ms piece of the wake word's mic (its thread): a short spoken bit over the hum is noted (a hint only). */
    static void hear(double rms, double noise) {
        if (!listening) return;
        // never Jarvis's own voice or the alarm, nor another app's sound on the phone
        if (otherSound || Announcer.audible() || SystemClock.elapsedRealtime() - lastAlarm < 3000) { answer = null; return; }
        CarSense.Answer a = answer;
        if (a == null) answer = a = new CarSense.Answer(noise);
        if (a.feed(rms)) heard = true;
    }

    /** Something else plays on the phone (not Jarvis): Maps' voice, songs. */
    private static boolean otherPlaying(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.isMusicActive() && !Announcer.audible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * He answered: "Jarvis" ("wake"), the ✅ button ("tap", phone / watch), said "ఉన్నాను" to Jarvis ("said"), or was talking
     * to Jarvis / on a call when the window ended ("talk"). False if nothing was asked.
     */
    static boolean answered(Context c, String how) {
        Context app = c.getApplicationContext();
        if (!asking(app)) return false;
        sp(app).edit().remove("asked_at").putInt("misses", 0).apply();
        endListen();
        Duck.off();
        Announcer.hold(app, false);
        clear(app, NOTE_ASK);
        WatchAlerts.directGone(app, WATCH_ASK);
        if (!"said".equals(how) && !"talk".equals(how)) Announcer.sayPhone(app, "సరే."); // (said to Jarvis: Jarvis answers it himself)
        schedule(app, CarSense.nextMinutes(often(app), false, every(app)));
        return true;
    }

    private static void noAnswer(Context app) {
        SharedPreferences s = sp(app);
        s.edit().remove("asked_at").apply();
        clear(app, NOTE_ASK);
        WatchAlerts.directGone(app, WATCH_ASK);
        if (!alarmable(app)) { schedule(app, CarSense.nextMinutes(often(app), false, every(app))); return; } // parked: no alarm over a rest
        s.edit().putInt("misses", s.getInt("misses", 0) + 1).putLong("tired_until", System.currentTimeMillis() + 30 * MIN).apply();
        s.edit().putString("told", String.join("|", reasons(app))).apply(); // (no "new reason" speech for what Jarvis saw itself)
        alarm(app, false);
        schedule(app, CarSense.nextMinutes(true, true, every(app)));
    }

    // ---------------------------------------------------------------- the alarm

    private static volatile long lastAlarm = -60_000;
    /** His own alarm volume while Jarvis has it at full (main thread; -1: not changed). */
    private static int savedVolume = -1, savedStream = AudioManager.STREAM_ALARM;
    private static final Runnable restoreVolume = () -> {
        try {
            Context app = appCtx;
            AudioManager am = app == null ? null : app.getSystemService(AudioManager.class);
            if (am != null && savedVolume >= 0) am.setStreamVolume(savedStream, savedVolume, 0);
        } catch (Exception ignored) {}
        savedVolume = -1;
    };

    /** The alarm tone for ms at full alarm volume; his own volume back once, after the last one (main thread). */
    private static void tone(Context app, int ms) {
        appCtx = app;
        main.post(() -> {
            AudioManager am = app.getSystemService(AudioManager.class);
            // (the car's box: its sound reaches the car's speakers as media; loud, never the amplifier's very top)
            final int stream = CarBox.on(app) ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_ALARM;
            try {
                if (am != null) {
                    if (savedVolume < 0) { savedVolume = am.getStreamVolume(stream); savedStream = stream; } // (his own, never the alarm's max)
                    int max = am.getStreamMaxVolume(stream);
                    am.setStreamVolume(stream, stream == AudioManager.STREAM_MUSIC ? Math.max(am.getStreamVolume(stream), Math.round(max * 0.85f)) : max, 0);
                }
            } catch (Exception ignored) {}
            try {
                android.media.ToneGenerator g = new android.media.ToneGenerator(stream, 100);
                g.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ms);
                main.postDelayed(g::release, ms + 500);
            } catch (Exception ignored) {}
            main.removeCallbacks(restoreVolume);
            // (the car's box: its words "కారు పక్కకి ఆపండి" come through the same sound, a moment after the tone: loud too)
            main.postDelayed(restoreVolume, ms + (stream == AudioManager.STREAM_MUSIC ? 15_000 : 1500));
        });
    }

    /** Loud: the alarm tone at full alarm volume (put back after), the phone and the watch buzz, then the words. */
    static void alarm(Context c, boolean eyes) {
        final Context app = c.getApplicationContext();
        lastAlarm = SystemClock.elapsedRealtime();
        tone(app, 2500);
        try {
            android.os.Vibrator v = app.getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 500, 200, 500, 200, 800}, -1));
        } catch (Exception ignored) {}
        String name = new Prefs(app).name();
        String text = (eyes ? "కళ్ళు తెరవండి! " : "జవాబు రాలేదు. ") + "వెంటనే కారు పక్కకి సురక్షితంగా ఆపండి. "
                + "15 నిమిషాలు కునుకు తీసి, టీ తాగి, ముఖం కడుక్కుని వెళ్లండి.";
        WatchAlerts.direct(app, WATCH_ALARM, "sos", "🚨 కారు ఆపండి", text, null);
        main.postDelayed(() -> WatchAlerts.directGone(app, WATCH_ALARM), 60_000);
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_car_alarm", "కారు నిద్ర అలారం", NotificationManager.IMPORTANCE_HIGH));
            nm.notify(NOTE_ALARM, new Notification.Builder(app, "jarvis_car_alarm").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🚨 కారు పక్కకి ఆపండి").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setTimeoutAfter(5 * MIN).setAutoCancel(true).build());
        } catch (Exception ignored) {}
        main.postDelayed(() -> Announcer.sayPhone(app, name + "! " + text), 2700);
        main.postDelayed(() -> Drive.offerRestStop(app), 9000); // ☕ then: the nearest open tea / coffee stop ahead (offered, never set by itself)
    }

    /** Settings → "🔔 అలారం శబ్దం టెస్ట్": the alarm's own tone, 2 seconds at full alarm volume (put back after). */
    static void testTone(Context c) { tone(c.getApplicationContext(), 2000); }

    /** The eye check saw (camera thread): eyes closed / head dropped -> the alarm at once; heavy eyes -> a word. */
    static void eyes(Context c, int event) {
        final Context app = c.getApplicationContext();
        main.post(() -> {
            if (!on(app) || !alarmable(app)) return; // parked / getting out: no alarm
            SharedPreferences s = sp(app);
            if (event == CarSense.Eyes.CLOSED || event == CarSense.Eyes.HEAD_DOWN) {
                if (SystemClock.elapsedRealtime() - lastAlarm < 15_000) return;
                s.edit().putLong("tired_until", System.currentTimeMillis() + 30 * MIN).apply();
                s.edit().putString("told", String.join("|", reasons(app))).apply();
                alarm(app, true);
                schedule(app, 2); // and asked again soon
            } else if (event == CarSense.Eyes.HEAVY) {
                s.edit().putLong("tired_until", System.currentTimeMillis() + 30 * MIN).apply();
                s.edit().putString("told", String.join("|", reasons(app))).apply();
                Announcer.sayPhone(app, new Prefs(app).name() + ", కళ్ళు బరువెక్కుతున్నాయి. దగ్గర్లో సురక్షితమైన చోట ఆగి కాసేపు కునుకు తీయండి. "
                        + "ఇక 5 నిమిషాలకి ఒకసారి అడుగుతాను.");
                schedule(app, 5);
                main.postDelayed(() -> Drive.offerRestStop(app), 9000);
            }
        });
    }

    // ---------------------------------------------------------------- "నిద్ర వస్తోంది"

    /** He said he is sleepy. Null when he isn't in the car (then it is an ordinary talk, not this). */
    static String sleepy(Context c, boolean say) {
        final Context app = c.getApplicationContext();
        String name = new Prefs(app).name();
        if (!on(app)) {
            if (Bike.riding(app) || new Prefs(app).driving()) // (the bike: only a word, no checks - his wish)
                return name + ", జాగ్రత్త! బండి పక్కకి సురక్షితంగా ఆపి, కాసేపు విశ్రాంతి తీసుకుని, టీ తాగి వెళ్లండి.";
            return null;
        }
        sp(app).edit().putLong("sleepy_until", System.currentTimeMillis() + 60 * MIN).apply();
        String text = "జాగ్రత్త, " + name + "! వీలైనంత త్వరగా సురక్షితమైన చోట కారు ఆపి, 15–20 నిమిషాలు కునుకు తీయండి. "
                + "ఆపేదాకా AC చల్లగా పెట్టి, కిటికీ కొంచెం తెరవండి. ఇక 5 నిమిషాలకి ఒకసారి మెలకువగా ఉన్నారా అని అడుగుతాను.";
        if (say) Announcer.sayPhone(app, text);
        sp(app).edit().putString("told", String.join("|", reasons(app))).apply();
        schedule(app, 5);
        if (say) maybeEyes(app, false); else main.postDelayed(() -> maybeEyes(app, false), 8000);
        return text;
    }

    // ---------------------------------------------------------------- the eye check

    /**
     * Starts the eye check when his choice says so (and he agreed once); else offers it in a notification. now: he asked
     * for it ("కళ్ళ చెక్ పెట్టు"), whatever the choice and the hour.
     */
    static void maybeEyes(Context c, boolean now) {
        Context app = c.getApplicationContext();
        if (!on(app)) return;
        if (CarEyes.running) { CarEyes.test = false; return; } // (a test left open when the car mode began: now the real one)
        if (CarBox.on(app) && "front".equals(eyesSource(app))) return; // (the car's box: no front camera; a USB / Wi-Fi one only when he set it)
        // (his phone, quiet in the car: the box is on watch; only "కారులో ఎప్పుడూ" keeps the phone's eye check — the phone
        // in a holder facing him, C15)
        if (CarBox.phoneQuiet(app) && !"always".equals(eyesMode(app))) return;
        String m = eyesMode(app);
        if (!now && ("off".equals(m) || !"always".equals(m) && reasons(app).isEmpty())) return;
        if (now) sp(app).edit().remove("eyes_told").apply();
        boolean camOk = app.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean canOpen = MainActivity.visible || android.provider.Settings.canDrawOverlays(app);
        if (eyesAgreed(app) && camOk && canOpen) {
            try {
                app.startActivity(new Intent(app, CarEyesActivity.class).putExtra(CarEyesActivity.EXTRA_AUTO, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                return;
            } catch (Exception ignored) {}
        }
        offerEyes(app, !now);
    }

    private static void offerEyes(Context app, boolean speak) {
        SharedPreferences s = sp(app);
        if (s.getBoolean("eyes_told", false)) return;
        s.edit().putBoolean("eyes_told", true).apply();
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_awake", "మెలకువ చెక్", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(app, 247, new Intent(app, CarEyesActivity.class).putExtra(CarEyesActivity.EXTRA_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE_EYES, new Notification.Builder(app, "jarvis_awake").setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("👁️ కళ్ళ చెక్ మొదలుపెట్టాలా?").setContentText("ఫోన్‌ని హోల్డర్‌లో మీ ముఖం వైపు పెట్టి ఇక్కడ నొక్కండి")
                    .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60 * MIN).build());
        } catch (Exception ignored) {}
        if (speak) Announcer.sayPhone(app, "కళ్ళ చెక్ కావాలంటే, ఫోన్‌ని హోల్డర్‌లో మీ ముఖం వైపు పెట్టి, నోటిఫికేషన్ నొక్కండి.");
    }

    // ---------------------------------------------------------------- the question's cards (phone, and straight to the watch)

    private static void askCards(Context c) {
        PendingIntent ok = PendingIntent.getBroadcast(c, 248, new Intent(c, AlarmReceiver.class).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE);
        Notification.Action yes = new Notification.Action.Builder(null, "✅ ఉన్నాను", ok).build();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            // (its own channel: not mirrored to the watch - the watch gets its card straight, even with Maps on the screen)
            nm.createNotificationChannel(new NotificationChannel("jarvis_car_ask", "కారు: మెలకువగా ఉన్నారా?", NotificationManager.IMPORTANCE_HIGH));
            nm.notify(NOTE_ASK, new Notification.Builder(c, "jarvis_car_ask").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("😴 మెలకువగా ఉన్నారా?").setContentText("'Jarvis' అని పిలవండి, లేదా ✅ నొక్కండి").setTimeoutAfter(3 * MIN)
                    .setAutoCancel(true).setContentIntent(ok).addAction(yes).build());
        } catch (Exception ignored) {}
        WatchAlerts.direct(c, WATCH_ASK, "rest", "😴 మెలకువగా ఉన్నారా?", "✅ నొక్కండి", new Notification.Action[]{yes});
    }

    private static void clear(Context c, int id) {
        try { c.getSystemService(NotificationManager.class).cancel(id); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- what to say about it

    /** One line for Jarvis చెక్ / the tool ("" when car mode is off). */
    static String statusWords(Context c) {
        if (!on(c)) return "";
        StringBuilder b = new StringBuilder("🚗 కారు మోడ్ ఆన్");
        if (!enabled(c)) return b.append(" (నిద్ర అలర్ట్ ఆఫ్)").toString();
        List<String> r = reasons(c);
        b.append(r.isEmpty() ? " · ఇప్పుడు ప్రశ్నలు లేవు (రాత్రి / అలసట / 2 గంటలకి మొదలవుతాయి)"
                : " · " + String.join(", ", r) + " · ప్రతి " + CarSense.nextMinutes(often(c), false, every(c)) + " నిమిషాలకి అడుగుతాను");
        if (!WakeService.hearing) b.append(" · \"Jarvis\" మైక్ ఆఫ్: జవాబుకి ✅ నొక్కాలి");
        if (CarEyes.running) b.append(" · కళ్ళ చెక్: ").append(CarEyes.status.isEmpty() ? "ఆన్" : CarEyes.status);
        return b.toString();
    }
}
