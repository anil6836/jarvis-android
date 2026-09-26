package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * How far a long job has got (writing a website or app, running code, building an APK on GitHub):
 * a silent notification with a progress bar and the time left, the same line in Jarvis's status while
 * he waits, and in the app preview screen.
 */
final class JobProgress {
    private static final String CHANNEL = "jarvis_progress";
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Jarvis's status line for the question being answered (set by the brain before each tool). */
    static volatile Brain.Status ui;
    /** The app preview screen listening for build progress, or null. */
    static volatile java.util.function.Consumer<String> screen;

    private final Context c;
    private final int id;
    private final String title;
    private volatile boolean useUi = true;
    private volatile Thread ticker;
    private volatile int pct;

    JobProgress(Context ctx, int id, String title) {
        this.c = ctx.getApplicationContext();
        this.id = id;
        this.title = title;
    }

    /** Stop writing to the question's status line (the answer is back; the job goes on in the background). */
    void leaveUi() { useUi = false; }

    /** percent 0-100, what is happening now, and seconds left (-1 = unknown). */
    void set(int percent, String stage, long secondsLeft) {
        pct = Math.max(pct, Math.min(100, percent)); // never goes backwards
        String left = secondsLeft < 0 ? "" : " · " + left(secondsLeft);
        String line = pct + "% · " + stage + left;
        Brain.Status u = ui;
        if (useUi && u != null) u.update(title + " · " + line);
        java.util.function.Consumer<String> s = screen;
        if (s != null) main.post(() -> s.accept(title + " · " + line));
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis పనుల పురోగతి", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(id, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(title + " · " + pct + "%")
                    .setContentText(stage + left)
                    .setProgress(100, pct, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open)
                    .build());
        } catch (Exception ignored) {}
    }

    /**
     * Moves the bar smoothly from 'from' towards 'to' while a slow step runs whose length is only known
     * roughly (about expectMs); it never reaches 'to' until the next set().
     */
    void tick(int from, int to, long expectMs, String stage) {
        stopTick();
        long start = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            while (Thread.currentThread() == ticker) {
                long el = System.currentTimeMillis() - start;
                double f = Math.min(0.95, el / (double) expectMs);
                long left = el < expectMs ? (expectMs - el) / 1000 : -2; // -2: "ఇంకొంచెం సేపు"
                set(from + (int) Math.round((to - from) * f), stage, left == -2 ? -2 : Math.max(2, left));
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
        }, "jarvis-progress");
        ticker = t;
        t.start();
    }

    void stopTick() {
        Thread t = ticker;
        ticker = null;
        if (t != null) t.interrupt();
    }

    /** Finished (or failed): the progress notification goes away. */
    void end() {
        stopTick();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(id);
        } catch (Exception ignored) {}
        java.util.function.Consumer<String> s = screen;
        if (s != null) main.post(() -> s.accept(null));
    }

    private static String left(long s) {
        if (s == -2) return "ఇంకొంచెం సేపు";
        if (s < 60) return "సుమారు " + s + " సెకన్లు మిగిలాయి";
        long m = (s + 59) / 60;
        return "సుమారు " + m + " నిమిషం" + (m > 1 ? "లు" : "") + " మిగిలింది";
    }
}
