package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * The home tablet's Settings lock (Anil, 24 Oct): a 4-digit PIN he sets on the tablet, asked before Settings opens there,
 * so అమ్మగారు never lands in a screen with his keys by a stray tap. Only a salted hash is kept (never the digits), five
 * wrong tries wait a minute (longer each time). Forgotten: his phone clears it through the home link.
 */
final class TabletLock {
    private TabletLock() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("tablet_lock", Context.MODE_PRIVATE); }

    /** A PIN is set on this device. */
    static boolean has(Context c) { return !sp(c).getString("h", "").isEmpty(); }

    /** The home tablet with a PIN: Settings asks for it. */
    static boolean active(Context c) { return new Prefs(c).homeMode() && has(c); }

    static boolean valid(String pin) { return pin != null && pin.matches("\\d{4}"); }

    static void set(Context c, String pin) {
        if (!valid(pin)) return;
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        String s = hex(salt);
        sp(c).edit().putString("s", s).putString("h", hash(s, pin)).putInt("bad", 0).putLong("wait_until", 0).apply();
    }

    static void clear(Context c) { sp(c).edit().clear().apply(); openUntil = 0; }

    /** Seconds still to wait after too many wrong tries (0: may try). */
    static long waitLeft(Context c) {
        long until = sp(c).getLong("wait_until", 0), now = System.currentTimeMillis();
        if (until - now > 30 * 60_000L) { // (the clock went back: never more than the longest wait)
            until = now + 30 * 60_000L;
            sp(c).edit().putLong("wait_until", until).apply();
        }
        return until > now ? (until - now + 999) / 1000 : 0;
    }

    /** The PIN is right (wrong tries counted; five in a row → a wait of 1, 2, 4… minutes, at most 30). */
    static boolean check(Context c, String pin) {
        SharedPreferences p = sp(c);
        if (!has(c) || waitLeft(c) > 0 || !valid(pin)) return false;
        if (hash(p.getString("s", ""), pin).equals(p.getString("h", ""))) {
            p.edit().putInt("bad", 0).putInt("rounds", 0).apply();
            return true;
        }
        int bad = p.getInt("bad", 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt("bad", bad);
        if (bad >= 5) {
            int rounds = p.getInt("rounds", 0);
            long mins = Math.min(30, 1L << Math.min(5, rounds));
            e.putInt("bad", 0).putInt("rounds", rounds + 1).putLong("wait_until", System.currentTimeMillis() + mins * 60_000L);
        }
        e.apply();
        return false;
    }

    // ---- unlocked for a little while (Settings rebuilding itself, its own sub-screens)
    private static volatile long openUntil;

    static void opened() { openUntil = SystemClock.elapsedRealtime() + 3 * 60_000L; }

    static boolean isOpen() { return SystemClock.elapsedRealtime() < openUntil; }

    static void close() { openUntil = 0; }

    private static String hash(String salt, String pin) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + ":" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < 2000; i++) h = md.digest(h); // (slow enough to make guessing from the file pointless)
            return hex(h);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x));
        return s.toString();
    }
}
