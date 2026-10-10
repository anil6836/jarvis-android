package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * When the Jarvis app itself closes with an error (on any thread): the time, the app version and the first lines of the
 * error's trace are kept, then Android's own handling goes on as always (the app still closes). "Jarvis చెక్" shows it,
 * so he can send a screenshot. Only the trace is kept: links, numbers, e-mails, keys and quoted text in the error's
 * words are blanked out. (A road crash is CrashAlert, not this.)
 */
final class AppCrash {
    private AppCrash() {}

    static final String PREFS = "jarvis_crash";
    private static final int LINES = 25;
    private static boolean installed;

    /** Once per process, as early as possible (JarvisApp). */
    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        String v = "";
        try { v = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        final String version = v == null ? "" : v;
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { save(app, version, e); } catch (Throwable ignored) {} // (never in the way of the real handling)
            if (before != null) {
                before.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private static void save(Context c, String version, Throwable e) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("t", System.currentTimeMillis())
                .putString("v", version)
                .putString("trace", lines(e))
                .commit(); // (written now: the app is closing)
    }

    /** The trace's first lines, each cleaned of anything that could be his (pure, for the desk tests too). */
    static String lines(Throwable e) {
        StringWriter w = new StringWriter();
        e.printStackTrace(new PrintWriter(w));
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : w.toString().split("\n")) {
            String l = line.replace("\r", "").replace("\t", "  ");
            if (l.trim().isEmpty()) continue;
            if (n++ == LINES) break;
            if (out.length() > 0) out.append('\n');
            out.append(clean(l));
        }
        return out.toString();
    }

    /** A trace line without his data: code places and error names stay; in the error's words, links, e-mails, long
     *  numbers, keys and "quoted" text go. */
    static String clean(String l) {
        String t = l.trim();
        if (t.startsWith("at ") || t.startsWith("...")) return l.length() > 220 ? l.substring(0, 220) : l; // a code place
        // "[Caused by: | Suppressed: ]java.lang.SomeError: its words"
        int from = t.startsWith("Caused by: ") ? l.indexOf("Caused by: ") + 11 : t.startsWith("Suppressed: ") ? l.indexOf("Suppressed: ") + 12 : 0;
        int k = l.indexOf(": ", from);
        if (k < 0) return l.length() > 220 ? l.substring(0, 220) : l;
        String words = l.substring(k + 2)
                .replaceAll("\"[^\"]*\"", "\"…\"")
                .replaceAll("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+", "<link>")
                .replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.-]+", "<email>")
                .replaceAll("(?=[A-Za-z_-]*\\d)[A-Za-z0-9_-]{20,}", "<…>")
                .replaceAll("\\+?\\d[\\d\\s-]{4,}\\d", "<number>");
        String s = l.substring(0, k + 2) + words;
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    /** For "Jarvis చెక్": the last time Jarvis closed with an error and its first line, or null when none is kept. */
    static String line(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long t = sp.getLong("t", 0);
        String tr = sp.getString("trace", "");
        if (t == 0 || tr == null || tr.isEmpty()) return null;
        String v = sp.getString("v", "");
        return "⚠️ చివరి సారి Jarvis ఆగిపోయింది (" + when(t) + (v == null || v.isEmpty() ? "" : " · " + v) + "): " + tr.split("\n", 2)[0];
    }

    /** The whole kept trace with its time and version (to copy and send), or "" when none. */
    static String details(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long t = sp.getLong("t", 0);
        String tr = sp.getString("trace", "");
        if (t == 0 || tr == null || tr.isEmpty()) return "";
        return "Jarvis " + sp.getString("v", "") + " · Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ") · "
                + when(t) + "\n" + tr;
    }

    /** He has seen it (or sent it): forgotten. */
    static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String when(long t) {
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new Date(t));
    }
}
