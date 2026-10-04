package com.anil.jarvis;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * The backup to his Google Drive by itself: once a day, and a little while after he changes something important (so a
 * phone lost in the evening does not lose the day's settings). Nothing runs until he has set the backup up.
 */
public class BackupJob extends JobService {
    private static final int DAILY = 7820, SOON = 7821;
    private static final long DAY = 24 * 60 * 60 * 1000L;

    /** The daily backup (kept scheduled; called when Jarvis opens). Also catches up when the last one is old. */
    static void schedule(Context c) {
        if (!Backup.configured(c)) return;
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            if (js.getPendingJob(DAILY) == null) {
                js.schedule(new JobInfo.Builder(DAILY, new ComponentName(c, BackupJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setRequiresBatteryNotLow(true)
                        .setPeriodic(DAY, 4 * 60 * 60 * 1000L)
                        .setPersisted(true)
                        .build());
            }
            long last = Backup.lastOk(c);
            if (last > 0 && System.currentTimeMillis() - last > DAY + 6 * 60 * 60 * 1000L) soon(c, 60_000L);
        } catch (Exception ignored) {}
    }

    /** He changed something important: back up in about 15 minutes (more changes meanwhile wait for the same one). */
    static void soon(Context c) { soon(c, 15 * 60 * 1000L); }

    private static void soon(Context c, long after) {
        if (!Backup.configured(c)) return;
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(SOON, new ComponentName(c, BackupJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(after)
                    .setOverrideDeadline(after + 6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {}
    }

    /** Backup switched off (set up again later): no more runs. */
    static void cancel(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js != null) { js.cancel(DAILY); js.cancel(SOON); }
        } catch (Exception ignored) {}
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Backup.run(getApplicationContext(), null);
            } catch (Exception e) {
                // tell him once a day at most; the daily job tries again by itself
                Context c = getApplicationContext();
                long told = Backup.sp(c).getLong("told_fail", 0);
                if (System.currentTimeMillis() - told > 20 * 60 * 60 * 1000L) {
                    Backup.sp(c).edit().putLong("told_fail", System.currentTimeMillis()).apply();
                    Reminders.notify(c, "Jarvis బ్యాకప్ కాలేదు",
                            "Google Drive కి బ్యాకప్ పంపలేకపోయాను: " + e.getMessage() + ". సెట్టింగ్స్ → బ్యాకప్ లో చూడండి.", 7820);
                }
                // no quick retries: the daily one tries again (a lost permission would only fail again and again)
            } finally {
                jobFinished(params, false);
            }
        }, "jarvis-backup-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; } // the backup finishes on its own thread anyway
}
