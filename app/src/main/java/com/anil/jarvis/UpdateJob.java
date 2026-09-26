package com.anil.jarvis;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Every 15 minutes (whenever there is internet): is there a new Jarvis build? Then a notification. */
public class UpdateJob extends JobService {
    private static final int ID = 7810;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {}
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { Updater.backgroundCheck(getApplicationContext()); } finally { jobFinished(params, false); }
        }, "jarvis-update-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }
}
