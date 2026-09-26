package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells Anil when a new Jarvis build is out on GitHub (a notification, once per build). Nothing is
 * downloaded or installed by the app: tapping the notification opens the APK link, and he installs it
 * himself as always.
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "anil6836/jarvis-android";
    private static final String LATEST_PAGE = "https://github.com/" + REPO + "/releases/latest";
    static final String APK_LINK = "https://github.com/" + REPO + "/releases/latest/download/Jarvis.apk";
    private static final Pattern TAG = Pattern.compile("/tag/v?1\\.0\\.(\\d+)");
    static final int NOTE_ID = 7802;
    private static final String CHANNEL = "jarvis_update";
    private static final AtomicBoolean busy = new AtomicBoolean();

    static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Newest build number on GitHub (from where releases/latest points), or -1. Network: call off the main thread. */
    static int latestBuild() {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(LATEST_PAGE).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setRequestMethod("HEAD");
            h.setConnectTimeout(15000);
            h.setReadTimeout(15000);
            String loc = h.getHeaderField("Location");
            h.disconnect();
            if (loc != null) {
                Matcher m = TAG.matcher(loc);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * In the background (every 5 minutes from the wake-word service, every 15 minutes otherwise):
     * a new build? Notify once for it. Call on a background thread.
     */
    static void backgroundCheck(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!busy.compareAndSet(false, true)) return;
        try {
            if (!Net.online(c)) return;
            int latest = latestBuild();
            if (latest <= currentBuild(c)) return;
            Prefs p = new Prefs(c);
            if (p.notifiedBuild() == latest) return;
            p.setNotifiedBuild(latest);
            notifyNew(c, latest);
        } catch (Exception ignored) {
        } finally {
            busy.set(false);
        }
    }

    /** "New version" notification; tapping it opens the APK download link (he installs it himself). */
    static void notifyNew(Context c, int build) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis అప్డేట్లు", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(APK_LINK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 79, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Jarvis కొత్త వెర్షన్ 1.0." + build + " వచ్చింది")
                    .setContentText("డౌన్‌లోడ్ చేసి ఇన్‌స్టాల్ చేయడానికి నొక్కండి")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {}
    }

    /** After he installed the new version: the notification is no longer needed. */
    static void cancelNotice(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {}
    }

    /** Files an earlier version (1.0.41-1.0.44) may have downloaded for its own updater. */
    static void cleanupOld(Context c) {
        File d = new File(c.getCacheDir(), "update");
        File[] files = d.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }
}
