package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * His phone: the home tablet's last status (from "🔄 టాబ్లెట్ స్థితి చూడు", or asked quietly when Settings opens and the last
 * one is older than 30 minutes), kept here so the "🏠 ఇంటి టాబ్లెట్" tile can show a problem only someone at home can fix
 * (location off → no weather, low battery, the tablets list empty, no answer...) without waiting a minute.
 * Only the status line the tablet sends (battery, internet, the day's ticks and its own problems) is kept; nothing she said.
 */
final class TabletWatch {
    private TabletWatch() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_tabwatch", Context.MODE_PRIVATE); }

    /** The tablet's status answer, as it came (without the "🏠 టాబ్లెట్ స్థితి: " start). */
    static void save(Context c, String status) {
        if (c == null || status == null) return;
        sp(c).edit().putString("text", status).putLong("at", System.currentTimeMillis()).putBoolean("silent", false).apply();
    }

    /** It was asked and did not answer within a minute. */
    static void noAnswer(Context c) {
        sp(c).edit().putBoolean("silent", true).putLong("silent_at", System.currentTimeMillis()).apply();
    }

    static String text(Context c) { return sp(c).getString("text", ""); }
    static long at(Context c) { return sp(c).getLong("at", 0); }

    /** The problems the tablet listed (its "⚠️ సమస్యలు: a · b" line); empty when none or when it did not say. */
    static java.util.List<String> problems(String status) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (status == null) return out;
        for (String line : status.split("\n")) {
            line = line.trim();
            if (!line.startsWith(HomeCare.PROBLEMS)) continue;
            for (String p : line.substring(HomeCare.PROBLEMS.length()).split(" · ")) if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    /** The battery and internet part of the first line ("⚡ 80% · 🟢 నెట్ ఉంది"). */
    static String firstBits(String status) {
        if (status == null || status.isEmpty()) return "";
        String first = status.split("\n")[0];
        String[] bits = first.split(" · ");
        return bits.length >= 2 ? bits[0] + " · " + bits[1] : bits[0];
    }

    static String ago(long at, long now) {
        long min = Math.max(0, (now - at) / 60_000L);
        if (min < 1) return "ఇప్పుడే";
        if (min < 60) return min + " ని. క్రితం";
        long h = min / 60;
        if (h < 24) return h + " గం. క్రితం";
        return (h / 24) + " రోజుల క్రితం";
    }

    /** {line, kind}: kind 0 = all well, 1 = something to look at (amber), 2 = a problem (red), -1 = nothing known yet. */
    static Object[] tile(Context c) {
        long now = System.currentTimeMillis(), at = at(c);
        SharedPreferences s = sp(c);
        if (!HomeLink.linked(c)) return new Object[]{"ఫోన్ ↔ టాబ్లెట్ లింక్ ఇంకా కలపలేదు", 1};
        if (s.getBoolean("silent", false) && s.getLong("silent_at", 0) >= at)
            return new Object[]{"📴 టాబ్లెట్ జవాబివ్వలేదు (" + ago(s.getLong("silent_at", 0), now) + "): నెట్ / Jarvis చూడండి", 2};
        if (at == 0) return new Object[]{"టాబ్లెట్ స్థితి: అడుగుతున్నాను…", -1};
        String t = text(c);
        java.util.List<String> bad = problems(t);
        if (!bad.isEmpty()) {
            boolean onlyList = true; // (an empty tablets list or no weather is something to look at, not an alarm: she may take none)
            for (String b : bad) if (!b.startsWith("💊") && !b.startsWith("☁️")) onlyList = false;
            return new Object[]{"⚠️ " + bad.size() + (bad.size() == 1 ? " సమస్య: " : " సమస్యలు: ") + android.text.TextUtils.join(" · ", bad), onlyList ? 1 : 2};
        }
        String bits = firstBits(t);
        return new Object[]{"✓ " + (bits.isEmpty() ? "టాబ్లెట్ బాగుంది" : bits) + " · " + ago(at, now), 0};
    }

    private static volatile boolean asking;

    /** Settings opened: the tablet is asked quietly when the last answer is older than 30 minutes (background; done on the main thread). */
    static void refreshIfOld(Context c, Runnable done) {
        final Context a = c.getApplicationContext();
        if (asking || !HomeLink.linked(a) || !Net.online(a)) return;
        SharedPreferences s = sp(a);
        long now = System.currentTimeMillis(), last = Math.max(at(a), s.getLong("asked", 0));
        if (now - last < 30 * 60_000L) return;
        asking = true;
        s.edit().putLong("asked", now).apply();
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                // the link holds one pinned message: something pinned in the last 2 minutes (a message to her, photos, a game
                // move, his 📤) may not be read yet at home, so it is never covered by this quiet ask: tried again in 5 minutes
                org.json.JSONObject m = Guard.pinned(a);
                if (m != null && System.currentTimeMillis() / 1000 - m.optLong("date") < 120) {
                    s.edit().putLong("asked", System.currentTimeMillis() - 25 * 60_000L).apply();
                    return;
                }
                HomeLink.tabletStatus(a); // (saves what it gets, or marks no answer)
            } catch (Exception ignored) {
            } finally {
                asking = false;
                if (done != null) ui.post(done);
            }
        }, "tab-watch").start();
    }
}
