package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Result of an update install; and after the update went in, opens Jarvis again. */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "com.anil.jarvis.UPDATE_STATUS";
    private static final String CHANNEL = "jarvis_update";

    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) { updated(c); return; }
        if (!ACTION.equals(i.getAction())) return;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // First time only (Jarvis wasn't its own installer yet): Android shows "Update this app?".
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception e) { Updater.status = "అప్డేట్ నిర్ధారణ చూపలేకపోయాను"; }
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            Updater.status = "✓ అప్డేట్ అయింది";
        } else {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.status = "అప్డేట్ కాలేదు" + (st == PackageInstaller.STATUS_FAILURE_ABORTED ? " (రద్దు చేశారు)" : msg != null ? ": " + msg : "");
            Toast.makeText(c, Updater.status, Toast.LENGTH_LONG).show();
        }
    }

    /** The new version is in: tidy up, tell him, and bring Jarvis back on screen. */
    private static void updated(Context c) {
        Updater.cleanup(c);
        Updater.cancelNotice(c);
        String v = "1.0." + Updater.currentBuild(c);
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis అప్డేట్లు", NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent open = PendingIntent.getActivity(c, 78, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(7801, new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Jarvis " + v + " కి అప్డేట్ అయింది ✓")
                        .setContentText("తెరవడానికి నొక్కండి")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
            }
        } catch (Exception ignored) {}
        // Reopen Jarvis (allowed because Jarvis may draw over other apps); the notification covers it otherwise.
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }
}
