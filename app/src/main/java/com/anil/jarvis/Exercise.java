package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Five minutes of gentle neck, shoulder and back stretches for a bike rider, spoken one by one with the time to hold;
 * a morning reminder on days off, and an evening nudge when his steps are far short of his goal.
 */
final class Exercise {
    private Exercise() {}

    static final String ACTION_STOP = "com.anil.jarvis.EXERCISE_STOP";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static int step;
    private static android.os.PowerManager.WakeLock lock;

    /** {what to do, seconds to hold / do it}. */
    private static final Object[][] ROUTINE = {
            {"నిటారుగా నిలబడండి లేదా కూర్చోండి, భుజాలు వదులుగా. నెమ్మదిగా 3 సార్లు లోతుగా శ్వాస తీసుకోండి.", 15},
            {"తలని నెమ్మదిగా కిందకి వంచి, గడ్డం ఛాతికి దగ్గరగా. తర్వాత నెమ్మదిగా పైకి. ఇలా 5 సార్లు.", 25},
            {"తలని కుడి భుజం వైపు నెమ్మదిగా వంచి అలాగే ఉంచండి.", 15},
            {"ఇప్పుడు ఎడమ భుజం వైపు.", 15},
            {"భుజాలని వెనక్కి గుండ్రంగా తిప్పండి, 10 సార్లు.", 20},
            {"రెండు చేతులూ పైకెత్తి, వేళ్లు కలిపి, పైకి సాగదీయండి.", 15},
            {"చేతులు నడుము మీద పెట్టుకుని, నెమ్మదిగా వెనక్కి వంగి మళ్లీ నిటారుగా. 5 సార్లు. నొప్పి ఉంటే కొంచెమే వంగండి.", 25},
            {"కుడి చేయి పైకెత్తి ఎడమ వైపు వంగండి.", 15},
            {"ఇప్పుడు ఎడమ చేయి పైకెత్తి కుడి వైపు.", 15},
            {"కుర్చీలో కూర్చుని, ఒక కాలు ముందుకు చాచి, నడుము నిటారుగా ఉంచి కొంచెం ముందుకు వంగండి. తొడ వెనక సాగుతుంది.", 20},
            {"ఇప్పుడు ఇంకో కాలు.", 20},
            {"చివరగా, కళ్ళు మూసుకుని నెమ్మదిగా 5 సార్లు లోతుగా శ్వాస.", 20},
    };

    static boolean running() { return running; }

    static void start(Context c) {
        Context app = c.getApplicationContext();
        stop(app);
        running = true;
        step = 0;
        try { // the steps must keep coming even if the screen goes off
            android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
            lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:exercise");
            lock.acquire(7 * 60000L);
        } catch (Exception ignored) {}
        Announcer.say(app, "సరే, 5 నిమిషాల వ్యాయామం మొదలుపెడదాం. ఎక్కడైనా నొప్పి ఎక్కువైతే అక్కడ ఆపేయండి.");
        main.postDelayed(() -> next(app), 7000);
    }

    private static void next(Context c) {
        if (!running) return;
        if (step >= ROUTINE.length) {
            running = false;
            cancelNote(c);
            release();
            Announcer.say(c, "అయిపోయింది, బాగా చేశారు! రోజూ ఇలా చేస్తే నడుము, మెడ నొప్పి తగ్గుతుంది.");
            c.getSharedPreferences("jarvis_exercise", Context.MODE_PRIVATE).edit().putString("done", LocalDate.now().toString()).apply();
            return;
        }
        Object[] s = ROUTINE[step++];
        int hold = (Integer) s[1];
        note(c, step + " / " + ROUTINE.length + ": " + s[0]);
        Announcer.say(c, (String) s[0] + (hold >= 15 ? " " + hold + " సెకన్లు." : ""));
        // the words take about 6-8 seconds, then the hold
        main.postDelayed(() -> next(c), 7000L + hold * 1000L);
    }

    static void stop(Context c) {
        boolean was = running;
        running = false;
        main.removeCallbacksAndMessages(null);
        cancelNote(c);
        release();
        if (was) Announcer.stop();
    }

    private static void release() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        lock = null;
    }

    private static void note(Context c, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_exercise", "వ్యాయామం", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getBroadcast(c, 91, new Intent(c, AlarmReceiver.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(91, new Notification.Builder(c, "jarvis_exercise").setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("🧘 వ్యాయామం").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", stop).build()).build());
        } catch (Exception ignored) {}
    }

    private static void cancelNote(Context c) {
        try { NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm != null) nm.cancel(91); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- reminders (from Proactive)

    static void tick(Context c, Prefs p, boolean quiet) {
        if (quiet || running) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_exercise", Context.MODE_PRIVATE);
        String today = LocalDate.now().toString();
        int h = LocalTime.now().getHour();
        boolean onDuty = false; // at work today, or coming home from a 48-hour duty
        try { Duty.Roster r = Duty.load(c); onDuty = Duty.ready(r) && !Duty.homeAllDay(r, LocalDate.now()); } catch (Exception ignored) {}
        // morning on days off: offer the stretches
        int eh = p.exerciseHour();
        if (eh >= 0 && !onDuty && h >= eh && h < eh + 2 && !today.equals(s.getString("asked", "")) && !today.equals(s.getString("done", ""))
                && !MainActivity.busyTalking() && !CallControl.busyWithCall()) {
            s.edit().putString("asked", today).apply();
            Proactive.say(c, p.name() + ", శుభోదయం.", "5 నిమిషాలు నడుము, మెడ వ్యాయామం చేద్దామా?",
                    " [exercise: if he says yes, call exercise with action start; if no, just say సరే.]");
            return;
        }
        // evening: far short of the step goal
        int goal = p.stepGoal();
        if (goal > 0 && h >= 19 && h < 21 && !today.equals(s.getString("steps", ""))) {
            int steps = Health.stepsToday(c);
            if (steps >= 0 && steps < goal * 0.7) {
                s.edit().putString("steps", today).apply();
                int left = goal - steps;
                Announcer.say(c, p.name() + ", ఈరోజు " + steps + " అడుగులు నడిచారు, లక్ష్యం " + goal + ". ఇంకా సుమారు " + Math.max(10, left / 100)
                        + " నిమిషాలు నడిస్తే సరిపోతుంది.");
            }
        }
    }
}
