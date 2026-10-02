package com.anil.jarvis;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mobile data and WiFi used, measured by the phone itself (Android's own counters): today, yesterday, the last 7 days,
 * and which apps used the most. With his daily plan limit (e.g. 1.5 GB), a word at 80% and when it is used up.
 * Needs the same "Usage access" switch as screen time.
 */
final class DataUse {
    private DataUse() {}

    private static final long DAY = 86400000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_data", Context.MODE_PRIVATE); }

    /** Daily limit in MB, 0 = none. */
    static int dailyLimitMb(Context c) { return sp(c).getInt("daily_mb", 0); }

    static void setDailyLimit(Context c, double gb) {
        sp(c).edit().putInt("daily_mb", gb <= 0 ? 0 : (int) Math.round(gb * 1024)).apply();
    }

    /** Bytes on mobile (or WiFi) between the times. */
    static long bytes(Context c, int type, long from, long to) {
        try {
            NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
            NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, from, to);
            return b == null ? 0 : b.getRxBytes() + b.getTxBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    /** The apps that used the most mobile data between the times: {name, MB}. */
    static JSONArray topApps(Context c, long from, long to, int max) {
        JSONArray out = new JSONArray();
        Map<Integer, Long> byUid = new HashMap<>();
        try {
            NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
            NetworkStats st = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, from, to);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (st.hasNextBucket()) {
                st.getNextBucket(b);
                Long had = byUid.get(b.getUid());
                byUid.put(b.getUid(), (had == null ? 0 : had) + b.getRxBytes() + b.getTxBytes());
            }
            st.close();
        } catch (Exception e) {
            return out;
        }
        List<Map.Entry<Integer, Long>> l = new ArrayList<>(byUid.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        PackageManager pm = c.getPackageManager();
        for (Map.Entry<Integer, Long> e : l) {
            if (out.length() >= max || e.getValue() < 512 * 1024) break;
            String name;
            int uid = e.getKey();
            if (uid == NetworkStats.Bucket.UID_REMOVED) name = "తీసేసిన యాప్‌లు";
            else if (uid == NetworkStats.Bucket.UID_TETHERING) name = "హాట్‌స్పాట్";
            else if (uid == android.os.Process.SYSTEM_UID || uid == 0) name = "Android సిస్టమ్";
            else {
                String[] pkgs = pm.getPackagesForUid(uid);
                name = pkgs == null || pkgs.length == 0 ? "uid " + uid : pkgs[0];
                try { if (pkgs != null && pkgs.length > 0) name = pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString(); } catch (Exception ignored) {}
            }
            try { out.put(new JSONObject().put("app", name).put("mb", mb(e.getValue()))); } catch (Exception ignored) {}
        }
        return out;
    }

    static double mb(long bytes) { return Math.round(bytes / 104857.6) / 10.0; }

    /** Android 10+: the counters for "all SIMs" (an older Android needs the SIM's id, which apps can't read). */
    static boolean supported() { return android.os.Build.VERSION.SDK_INT >= 29; }

    static JSONObject report(Context c) throws Exception {
        if (!supported()) return new JSONObject().put("ok", false).put("error", "old_android").put("message", "Counting data needs Android 10 or newer.");
        long today = Life.dayStart(), now = System.currentTimeMillis();
        long mToday = bytes(c, ConnectivityManager.TYPE_MOBILE, today, now);
        if (mToday < 0) return new JSONObject().put("ok", false).put("error", "no_counter").put("message", "Android did not give the data counters.");
        JSONObject o = new JSONObject().put("ok", true)
                .put("mobile_today_mb", mb(mToday))
                .put("wifi_today_mb", mb(Math.max(0, bytes(c, ConnectivityManager.TYPE_WIFI, today, now))))
                .put("mobile_yesterday_mb", mb(Math.max(0, bytes(c, ConnectivityManager.TYPE_MOBILE, today - DAY, today))))
                .put("mobile_last_7_days_mb", mb(Math.max(0, bytes(c, ConnectivityManager.TYPE_MOBILE, today - 6 * DAY, now))))
                .put("top_apps_today_mobile", topApps(c, today, now, 5));
        int lim = dailyLimitMb(c);
        if (lim > 0) o.put("daily_limit_mb", lim).put("left_today_mb", Math.max(0, Math.round((lim - mToday / 1048576.0) * 10) / 10.0))
                .put("percent_used", Math.round(mToday / 1048576.0 * 100 / lim));
        return o.put("note", "Measured by the phone; the operator's own count can differ a little.");
    }

    /** From the regular check: 80% and 100% of his daily limit, once each a day. */
    static void tick(Context c, Prefs p, boolean quiet) {
        int lim = dailyLimitMb(c);
        if (lim <= 0 || !supported() || !Life.usageAllowed(c)) return;
        long used = bytes(c, ConnectivityManager.TYPE_MOBILE, Life.dayStart(), System.currentTimeMillis());
        if (used < 0) return;
        double pct = used / 1048576.0 * 100 / lim;
        String day = String.valueOf(Life.dayStart());
        String step = pct >= 100 ? "full" : pct >= 80 ? "80" : "";
        if (step.isEmpty() || (day + step).equals(sp(c).getString("told_" + step, ""))) return;
        sp(c).edit().putString("told_" + step, day + step).apply();
        if (step.equals("full")) sp(c).edit().putString("told_80", day + "80").apply();
        String gb = String.format(java.util.Locale.ENGLISH, "%.1f", lim / 1024.0);
        String text = step.equals("full") ? "ఈరోజు " + gb + " GB మొబైల్ డేటా అయిపోయింది. ఇక స్పీడ్ తగ్గొచ్చు; WiFi ఉంటే వాడండి."
                : "ఈరోజు మొబైల్ డేటా " + Math.round(pct) + "% అయిపోయింది (" + gb + " GB లో). వీడియోలు తగ్గించండి.";
        Reminders.notify(c, "📶 మొబైల్ డేటా", text, 221);
        if (!quiet && step.equals("full")) Announcer.say(c, p.name() + ", " + text);
    }
}
