package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
 * Jarvis updates itself from the GitHub releases: checks for a newer build, downloads the APK in the
 * background, makes sure it is signed with the same key, and installs it with one tap. From Android 12,
 * once Jarvis has installed itself one time, later updates need no system confirmation at all.
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "anil6836/jarvis-android";
    private static final String LATEST_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern TAG = Pattern.compile("/tag/v?1\\.0\\.(\\d+)");
    private static final long AUTO_EVERY_MS = 60 * 60 * 1000L;

    /** Latest state, shown in Settings. */
    static volatile String status = "";
    private static final AtomicBoolean working = new AtomicBoolean();
    /** "తర్వాత" for this build: not asked again until Jarvis is opened fresh. */
    private static volatile int laterFor;
    private static final Handler main = new Handler(Looper.getMainLooper());

    interface Callback {
        /** A newer build is downloaded and verified. */
        void onReady(int build);
        /** Nothing to do (already newest) or it failed; message says which. */
        void onNothing(String message);
    }

    static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File apkFor(Context c, int build) { return new File(dir(c), "Jarvis-" + build + ".apk"); }

    /** The downloaded, verified newer build waiting to be installed, or 0. */
    static int readyBuild(Context c) {
        int cur = currentBuild(c), best = 0;
        File[] files = dir(c).listFiles();
        if (files == null) return 0;
        for (File f : files) {
            Matcher m = Pattern.compile("Jarvis-(\\d+)\\.apk").matcher(f.getName());
            if (!m.matches()) continue;
            int b = Integer.parseInt(m.group(1));
            if (b <= cur) { //noinspection ResultOfMethodCallIgnored
                f.delete(); continue; } // already installed: tidy up
            best = Math.max(best, b);
        }
        return best;
    }

    /** Removes downloaded files (after an update went in). */
    static void cleanup(Context c) {
        readyBuild(c);
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".part")) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** On opening Jarvis: at most once an hour, quietly look for and download a newer build. */
    static void autoCheck(Context c, Callback cb) {
        Prefs p = new Prefs(c);
        if (!p.autoUpdate() || !Net.online(c)) return;
        long now = System.currentTimeMillis();
        if (now - p.updateCheckedAt() < AUTO_EVERY_MS && readyBuild(c) == 0) return;
        p.setUpdateCheckedAt(now);
        check(c, cb);
    }

    /** Looks for a newer build and downloads it (background thread); the callback runs on the main thread. */
    static void check(Context ctx, Callback cb) {
        Context c = ctx.getApplicationContext();
        int ready = readyBuild(c);
        if (ready > 0) { main.post(() -> cb.onReady(ready)); return; }
        if (!working.compareAndSet(false, true)) { main.post(() -> cb.onNothing("ఇప్పటికే చూస్తున్నాను…")); return; }
        new Thread(() -> {
            try {
                int cur = currentBuild(c);
                status = "కొత్త వెర్షన్ కోసం చూస్తున్నాను…";
                int latest = latestBuild();
                if (latest <= 0) throw new IllegalStateException("GitHub నుంచి వెర్షన్ తెలియలేదు");
                if (latest <= cur) {
                    status = "✓ తాజా వెర్షన్ 1.0." + cur + " ఉంది";
                    main.post(() -> cb.onNothing("Jarvis ఇప్పటికే తాజా వెర్షన్ (1.0." + cur + ")"));
                    return;
                }
                status = "కొత్త వెర్షన్ 1.0." + latest + " డౌన్‌లోడ్ అవుతోంది…";
                File part = new File(dir(c), "Jarvis-" + latest + ".part");
                download("https://github.com/" + REPO + "/releases/download/v1.0." + latest + "/Jarvis.apk", part);
                String problem = verify(c, part, latest);
                if (problem != null) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IllegalStateException(problem);
                }
                File apk = apkFor(c, latest);
                if (!part.renameTo(apk)) throw new IllegalStateException("ఫైల్ సేవ్ కాలేదు");
                status = "కొత్త వెర్షన్ 1.0." + latest + " ఇన్‌స్టాల్‌కి సిద్ధం";
                main.post(() -> cb.onReady(latest));
            } catch (Exception e) {
                String msg = "అప్డేట్ కుదరలేదు: " + e.getMessage();
                status = msg;
                main.post(() -> cb.onNothing(msg));
            } finally {
                working.set(false);
            }
        }, "jarvis-update").start();
    }

    /** Newest build number on GitHub (from the releases/latest redirect; the API as a fallback). */
    private static int latestBuild() {
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
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(LATEST_API).openConnection();
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setConnectTimeout(15000);
            h.setReadTimeout(15000);
            if (h.getResponseCode() != 200) return -1;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            JSONObject r = new JSONObject(bo.toString("UTF-8"));
            Matcher m = TAG.matcher("/tag/" + r.optString("tag_name"));
            if (m.find()) {
                JSONArray assets = r.optJSONArray("assets");
                if (assets != null && assets.length() > 0) return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection h = null;
        // follow the redirect to GitHub's file server by hand (keeps working on every Android version)
        for (int hop = 0; hop < 5; hop++) {
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
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) { out.write(b, 0, n); got += n; }
        } finally {
            h.disconnect();
        }
        if (expect > 0 && got != expect) throw new IllegalStateException("డౌన్‌లోడ్ మధ్యలో ఆగిపోయింది");
        if (got < 1_000_000) throw new IllegalStateException("ఫైల్ సరిగా లేదు");
    }

    /** null when the file is a newer Jarvis signed with the same key as this one; otherwise the problem. */
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

    /** Asks once (one tap) and installs the ready build. Only call while Jarvis is on screen and idle. */
    static void offer(Activity a) {
        int build = readyBuild(a);
        if (build <= 0 || build == laterFor || a.isFinishing()) return;
        laterFor = build; // one question per build per opening of Jarvis
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Jarvis కొత్త వెర్షన్ 1.0." + build)
                .setMessage("కొత్త వెర్షన్ డౌన్‌లోడ్ అయి సిద్ధంగా ఉంది. ఇప్పుడే అప్డేట్ చేయాలా?\n(Jarvis కొన్ని సెకన్లు మూసుకుని, మళ్లీ తెరుచుకుంటుంది.)")
                .setPositiveButton("అప్డేట్ చేయి", (d, w) -> install(a, build))
                .setNegativeButton("తర్వాత", null)
                .show();
    }

    static void install(Activity a, int build) {
        File apk = apkFor(a, build);
        if (!apk.exists()) { Toast.makeText(a, "అప్డేట్ ఫైల్ లేదు, మళ్లీ ప్రయత్నించండి", Toast.LENGTH_LONG).show(); return; }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            laterFor = 0; // ask again when he comes back from the settings page
            Toast.makeText(a, "ఒక్కసారి: Jarvis కి \"Allow from this source\" ఆన్ చేసి వెనక్కి రండి", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            }
            return;
        }
        Toast.makeText(a, "Jarvis అప్డేట్ అవుతోంది…", Toast.LENGTH_LONG).show();
        status = "1.0." + build + " ఇన్‌స్టాల్ అవుతోంది…";
        Context c = a.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session s = null;
            try {
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31) {
                    // After the first time, Jarvis is its own installer and updates go in without a prompt.
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
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(c, 77, done, piFlags);
                s.commit(pend.getIntentSender());
                s.close();
                s = null;
            } catch (Exception e) {
                if (s != null) try { s.abandon(); } catch (Exception ignored) {}
                String msg = "అప్డేట్ ఇన్‌స్టాల్ కాలేదు: " + e.getMessage();
                status = msg;
                main.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
            }
        }, "jarvis-install").start();
    }
}
