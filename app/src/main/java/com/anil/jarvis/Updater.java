package com.anil.jarvis;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvis updates. In the background it only LOOKS for a new build and tells Anil (a phone notification
 * and a note in the app); it never downloads or installs by itself. When he presses "అప్డేట్ చేయి" in
 * Settings (or taps the notification, which opens that page), the new version is downloaded inside
 * Jarvis, checked to be signed with the same key, and installed.
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "anil6836/jarvis-android";
    private static final String LATEST_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final Pattern TAG = Pattern.compile("/tag/v?1\\.0\\.(\\d+)");
    static final int NOTE_ID = 7802;
    private static final String CHANNEL = "jarvis_update";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean checking = new AtomicBoolean();
    private static final AtomicBoolean working = new AtomicBoolean();
    private static volatile long lastCheck;

    /** Latest progress / result, shown in Settings. */
    static volatile String status = "";
    /** Sent to the "Install unknown apps" page: install this build when he comes back. */
    static volatile int installWhenAllowed;

    interface Progress { void onStatus(String text, boolean done); }

    static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Newest build on GitHub known to the app (0 = none known). */
    static int knownLatest(Context c) { return new Prefs(c).latestBuild(); }

    static boolean newAvailable(Context c) { return knownLatest(c) > currentBuild(c); }

    /** Newest build number on GitHub (where releases/latest points), or -1. Network: not on the main thread. */
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

    // ------------------------------------------------------------------ looking (never downloads)

    /**
     * A new build on GitHub? Remember it (the app shows it) and notify once per build.
     * Background thread. From the wake-word service every 5 minutes, the job every 15, and on opening Jarvis.
     */
    static void backgroundCheck(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!checking.compareAndSet(false, true)) return;
        try {
            if (!Net.online(c)) return;
            int latest = latestBuild();
            lastCheck = System.currentTimeMillis();
            if (latest <= 0) return;
            Prefs p = new Prefs(c);
            p.setLatestBuild(latest);
            if (latest > currentBuild(c) && p.notifiedBuild() != latest) {
                p.setNotifiedBuild(latest);
                notifyNew(c, latest);
            }
        } catch (Exception ignored) {
        } finally {
            checking.set(false);
        }
    }

    /** On opening Jarvis: a quick look (at most every 30 minutes), then onKnown on the main thread. */
    static void lookSoon(Context ctx, Runnable onKnown) {
        Context c = ctx.getApplicationContext();
        if (System.currentTimeMillis() - lastCheck < 30 * 60 * 1000L) return;
        new Thread(() -> { backgroundCheck(c); main.post(onKnown); }, "jarvis-update-look").start();
    }

    private static void notifyNew(Context c, int build) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis అప్డేట్లు", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_UPDATE_NOW, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 79, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Jarvis కొత్త వెర్షన్ 1.0." + build + " వచ్చింది")
                    .setContentText("అప్డేట్ చేయడానికి నొక్కండి")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {}
    }

    static void cancelNotice(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ updating (only when he presses)

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File apkFor(Context c, int build) { return new File(dir(c), "Jarvis-" + build + ".apk"); }

    /** Deletes downloads that are already installed (or half-finished). */
    static void cleanup(Context c) {
        int cur = currentBuild(c);
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            Matcher m = Pattern.compile("Jarvis-(\\d+)\\.apk").matcher(f.getName());
            if (!m.matches() || Integer.parseInt(m.group(1)) <= cur) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    static boolean busy() { return working.get(); }

    /** "అప్డేట్ చేయి" pressed: look, download (with progress), verify, then install. */
    static void updateNow(Activity a, Progress p) {
        if (!working.compareAndSet(false, true)) { p.onStatus(status, false); return; }
        Context c = a.getApplicationContext();
        cancelNotice(c);
        new Thread(() -> {
            try {
                say(p, "కొత్త వెర్షన్ కోసం చూస్తున్నాను…", false);
                int cur = currentBuild(c);
                int latest = latestBuild();
                if (latest <= 0) throw new IllegalStateException("GitHub చేరలేకపోయాను, ఇంటర్నెట్ చూసి మళ్లీ నొక్కండి");
                new Prefs(c).setLatestBuild(latest);
                if (latest <= cur) { say(p, "✓ ఇదే తాజా వెర్షన్ (1.0." + cur + ")", true); return; }
                File apk = apkFor(c, latest);
                if (!apk.exists()) {
                    File part = new File(dir(c), "Jarvis-" + latest + ".part");
                    download("https://github.com/" + REPO + "/releases/download/v1.0." + latest + "/Jarvis.apk", part,
                            pct -> say(p, "1.0." + latest + " డౌన్‌లోడ్ అవుతోంది… " + pct + "%", false));
                    say(p, "సరిచూస్తున్నాను…", false);
                    String problem = verify(c, part, latest);
                    if (problem != null) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw new IllegalStateException(problem);
                    }
                    if (!part.renameTo(apk)) throw new IllegalStateException("ఫైల్ సేవ్ కాలేదు");
                }
                say(p, "1.0." + latest + " డౌన్‌లోడ్ అయింది, ఇన్‌స్టాల్ చేస్తున్నాను…", false);
                main.post(() -> install(a, latest, p));
            } catch (Exception e) {
                say(p, "అప్డేట్ కాలేదు: " + e.getMessage(), true);
            } finally {
                working.set(false);
            }
        }, "jarvis-update").start();
    }

    private static void say(Progress p, String text, boolean done) {
        status = text;
        main.post(() -> p.onStatus(text, done));
    }

    private interface Percent { void on(int pct); }

    private static void download(String url, File to, Percent pr) throws Exception {
        HttpURLConnection h = null;
        for (int hop = 0; hop < 5; hop++) { // follow the redirect to GitHub's file server by hand
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20000);
            h.setReadTimeout(30000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                url = h.getHeaderField("Location");
                h.disconnect();
                if (url == null) throw new IllegalStateException("డౌన్‌లోడ్ లింక్ దొరకలేదు");
                continue;
            }
            if (code != 200) throw new IllegalStateException("డౌన్‌లోడ్ " + code);
            break;
        }
        if (h == null) throw new IllegalStateException("డౌన్‌లోడ్ కాలేదు");
        long expect = h.getContentLengthLong(), got = 0;
        int shown = -1;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                int pct = expect > 0 ? (int) (got * 100 / expect) : 0;
                if (pct != shown) { shown = pct; pr.on(pct); }
            }
        } finally {
            h.disconnect();
        }
        if (expect > 0 && got != expect) throw new IllegalStateException("డౌన్‌లోడ్ మధ్యలో ఆగిపోయింది, మళ్లీ నొక్కండి");
        if (got < 1_000_000) throw new IllegalStateException("ఫైల్ సరిగా లేదు");
    }

    /** null when the file is the expected newer Jarvis, signed with the same key as this one. */
    @SuppressWarnings("deprecation")
    private static String verify(Context c, File apk, int build) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (got == null) return "డౌన్‌లోడ్ చేసిన ఫైల్ సరైన యాప్ కాదు";
        if (!c.getPackageName().equals(got.packageName)) return "వేరే యాప్ ఫైల్";
        long code = Build.VERSION.SDK_INT >= 28 ? got.getLongVersionCode() : got.versionCode;
        if (code != build) return "వెర్షన్ సరిపోలలేదు";
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
            if (!Arrays.equals(signatures(mine), signatures(got))) return "సంతకం (signing key) సరిపోలలేదు, ఇన్‌స్టాల్ చేయను";
        } catch (Exception e) {
            return "సంతకం చూడలేకపోయాను";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String[] signatures(PackageInfo pi) {
        Signature[] s;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            s = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
        } else {
            s = pi.signatures;
        }
        if (s == null) return new String[0];
        String[] out = new String[s.length];
        for (int i = 0; i < s.length; i++) out[i] = s[i].toCharsString();
        Arrays.sort(out);
        return out;
    }

    /** Installs the downloaded build (Android asks for confirmation the first time). */
    static void install(Activity a, int build, Progress p) {
        File apk = apkFor(a, build);
        if (!apk.exists()) { p.onStatus("అప్డేట్ ఫైల్ లేదు, మళ్లీ నొక్కండి", true); return; }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            installWhenAllowed = build; // carried on when he comes back to Settings
            status = "ఒక్కసారి: Jarvis కి \"Allow from this source\" ఆన్ చేసి వెనక్కి రండి";
            p.onStatus(status, false);
            Toast.makeText(a, status, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            }
            return;
        }
        installWhenAllowed = 0;
        status = "1.0." + build + " ఇన్‌స్టాల్ అవుతోంది… (Jarvis కొన్ని సెకన్లు మూసుకుని మళ్లీ తెరుచుకుంటుంది)";
        p.onStatus(status, false);
        new Prefs(a).setUpdateStartedAt(System.currentTimeMillis());
        Context c = a.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session s = null;
            try {
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31) {
                    // he pressed "update": after the first time Android doesn't need to ask again
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = pi.createSession(params);
                s = pi.openSession(id);
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("jarvis.apk", 0, apk.length())) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, 77, done, flags).getIntentSender());
                s.close();
                s = null;
            } catch (Exception e) {
                if (s != null) try { s.abandon(); } catch (Exception ignored) {}
                say(p, "ఇన్‌స్టాల్ కాలేదు: " + e.getMessage(), true);
            }
        }, "jarvis-install").start();
    }
}
