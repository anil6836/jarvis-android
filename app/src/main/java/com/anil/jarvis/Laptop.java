package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 💻 This phone ↔ Jarvis PC on his laptop (phase 1, 28 Nov): the pairing from the laptop's QR (hello → hello_ok), "is the
 * laptop on?" (ping → pong) and unpairing (bye, either side). The key stays in this phone's own private settings, never in
 * the Drive backup or Google's backup (a new phone scans a new QR), never shown, never sent to any AI. ₹0, no AI.
 * Network calls block: call them off the main thread.
 */
final class Laptop {
    private Laptop() {}

    static final String PREFS = "pc_link_private";
    static final int HELLO_WAIT_S = 25, PING_WAIT_S = 20;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static byte[] key(Context c) {
        String k = sp(c).getString("k", null);
        try { return k == null ? null : Base64.getDecoder().decode(k); } catch (Exception e) { return null; }
    }

    /** A laptop's key is here (scanned), whether or not it has answered yet. */
    static boolean hasKey(Context c) { return key(c) != null; }

    /** The laptop answered the hello: paired. */
    static boolean paired(Context c) { return hasKey(c) && sp(c).getBoolean("ok", false); }

    static String name(Context c) {
        String n = sp(c).getString("name", "");
        return n.isEmpty() ? sp(c).getString("qr_name", "") : n;
    }

    static String pcApp(Context c) { return sp(c).getString("app", ""); }
    static long pairedAt(Context c) { return sp(c).getLong("at", 0); }
    static long lastSeen(Context c) { return sp(c).getLong("seen", 0); }

    /** The QR was read: this laptop's key is kept (the old laptop's pairing, if any, is replaced: it gets a quiet bye). Not yet paired. */
    static void adopt(Context c, PcLink.Qr q) {
        final byte[] old = key(c);
        if (old != null && paired(c) && !java.util.Arrays.equals(old, q.key))
            new Thread(() -> { try { new PcLink(old).send(PcLink.message("bye")); } catch (Exception ignored) {} }, "pc-bye").start();
        sp(c).edit().clear().putString("k", Base64.getEncoder().encodeToString(q.key)).putString("qr_name", q.name)
                .putBoolean("ok", false).putLong("since", now() - 10).commit();
    }

    /** Everything about the laptop is forgotten on this phone. */
    static void forget(Context c) { sp(c).edit().clear().commit(); }

    /** The answer of one try. */
    static final class Result {
        final boolean ok;
        final String line;
        Result(boolean ok, String line) { this.ok = ok; this.line = line; }
    }

    static final String NO_KEY = "ల్యాప్‌టాప్ ఇంకా జత కాలేదు. ⚙️ → 💻 ల్యాప్‌టాప్ జత లో లాప్‌టాప్ QR స్కాన్ చేయండి.";

    /** Right after scanning (or again): hello, then up to 25 s for the laptop's hello_ok. */
    static Result hello(Context c) {
        byte[] k = key(c);
        if (k == null) return new Result(false, NO_KEY);
        PcLink link = new PcLink(k);
        long start = now() - 5;
        try {
            link.send(PcLink.message("hello", "name", phoneName(c), "app", appVersion(c)));
        } catch (IOException e) {
            return new Result(false, "పంపలేకపోయాను: ఫోన్‌లో నెట్ ఉందా చూడండి. (" + PcLink.clean(e.getMessage(), 60) + ")");
        }
        PcLink.Msg m = waitFor(c, link, start, "hello_ok", HELLO_WAIT_S);
        if (!hasKey(c)) return new Result(false, "ల్యాప్‌టాప్ జత తీసేసింది. కొత్త QR తో మళ్లీ జత చేయండి.");
        if (m == null) return new Result(false, "ల్యాప్‌టాప్ నుంచి జవాబు రాలేదు. లాప్‌టాప్‌లో Jarvis PC తెరిచి QR ఇంకా స్క్రీన్ మీద ఉందా, "
                + "లాప్‌టాప్‌కి నెట్ ఉందా చూసి \"🔁 మళ్లీ ప్రయత్నించు\" నొక్కండి.");
        return new Result(true, "✅ జత అయింది: " + name(c));
    }

    /** "Is the laptop on?": ping, then up to 20 s for its pong. */
    static Result ping(Context c) {
        byte[] k = key(c);
        if (k == null || !paired(c)) return new Result(false, NO_KEY);
        PcLink link = new PcLink(k);
        long start = now() - 5;
        try {
            link.send(PcLink.message("ping"));
        } catch (IOException e) {
            return new Result(false, "అడగలేకపోయాను: ఫోన్‌లో నెట్ ఉందా చూడండి. (" + PcLink.clean(e.getMessage(), 60) + ")");
        }
        PcLink.Msg m = waitFor(c, link, start, "pong", PING_WAIT_S);
        if (!hasKey(c)) return new Result(false, "ల్యాప్‌టాప్ జత తీసేసింది. కొత్త QR తో మళ్లీ జత చేయండి.");
        if (m == null) return new Result(false, "❌ " + label(c) + " నుంచి " + PING_WAIT_S + " సెకన్లలో జవాబు రాలేదు: లాప్‌టాప్ ఆఫ్‌లో / నిద్రలో ఉంది, "
                + "దానికి నెట్ లేదు, లేదా Jarvis PC మూసి ఉంది. (లాప్‌టాప్‌లో జత తీసేసి ఉంటే, కొత్త QR తో మళ్లీ జత చేయండి.)");
        return new Result(true, "✅ " + label(c) + " ఆన్‌లో ఉంది" + (m.app.isEmpty() ? "." : " (Jarvis PC " + m.app + ")."));
    }

    /** Unpair from this phone: bye to the laptop (if it can't be sent, the pairing is still removed here). */
    static Result unpair(Context c) {
        byte[] k = key(c);
        boolean sent = false;
        if (k != null) {
            try { new PcLink(k).send(PcLink.message("bye")); sent = true; } catch (IOException ignored) {}
        }
        forget(c);
        return new Result(true, sent ? "జత తీసేశాను. లాప్‌టాప్‌కి కూడా చెప్పాను." : "ఈ ఫోన్‌లో జత తీసేశాను. (లాప్‌టాప్‌కి చెప్పలేకపోయాను: నెట్ లేదు. లాప్‌టాప్‌లో కూడా \"జత తీసేయి\" నొక్కండి.)");
    }

    /** When the 💻 screen opens: anything the laptop said meanwhile (a bye) is taken. Quiet; no sending. */
    static void check(Context c) {
        byte[] k = key(c);
        if (k == null) return;
        try {
            long since = Math.max(sp(c).getLong("since", 0), now() - 12 * 3600);
            take(c, new PcLink(k).poll(since), null);
        } catch (IOException ignored) {}
    }

    private static PcLink.Msg waitFor(Context c, PcLink link, long since, String kind, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(2500); } catch (InterruptedException e) { return null; }
            try {
                PcLink.Msg m = take(c, link.poll(since), kind);
                if (m != null) return m;
                if (!hasKey(c)) return null; // (a bye came)
            } catch (IOException ignored) { /* (the next round tries again) */ }
        }
        return null;
    }

    /** The laptop's messages, each once: hello_ok pairs, pong notes the time, bye forgets. Returns the first of the kind wanted. */
    private static synchronized PcLink.Msg take(Context c, List<PcLink.Raw> got, String want) {
        SharedPreferences s = sp(c);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String id : s.getString("ids", "").split(",")) if (!id.isEmpty()) seen.add(id);
        PcLink.Msg found = null;
        long now = now();
        for (PcLink.Raw r : got) {
            PcLink.Msg m = PcLink.accept(r.json, r.time, now, seen);
            if (m == null) continue;
            SharedPreferences.Editor e = s.edit();
            switch (m.kind) {
                case "hello_ok":
                    e.putBoolean("ok", true).putLong("seen", now);
                    if (!m.name.isEmpty()) e.putString("name", m.name);
                    if (!m.app.isEmpty()) e.putString("app", m.app);
                    if (s.getLong("at", 0) == 0) e.putLong("at", now);
                    break;
                case "pong":
                    e.putLong("seen", now);
                    if (!m.app.isEmpty()) e.putString("app", m.app);
                    if (!m.name.isEmpty()) e.putString("name", m.name);
                    break;
                case "bye":
                    s.edit().clear().commit();
                    return m.kind.equals(want) ? m : null;
                default:
                    continue; // (other kinds belong to later phases)
            }
            e.putLong("since", Math.max(s.getLong("since", 0), r.time - 1)).commit();
            if (found == null && m.kind.equals(want)) found = m;
        }
        StringBuilder ids = new StringBuilder();
        for (String id : seen) { if (ids.length() > 0) ids.append(','); ids.append(id); }
        if (s.contains("k")) s.edit().putString("ids", ids.toString()).commit();
        return found;
    }

    static String label(Context c) {
        String n = name(c);
        return n.isEmpty() ? "ల్యాప్‌టాప్" : "ల్యాప్‌టాప్ (" + n + ")";
    }

    /** This phone's own name ("Galaxy S25 Ultra"), at most 40 characters. */
    static String phoneName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception ignored) {}
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return PcLink.clean(n, 40);
    }

    static String appVersion(Context c) {
        try { return PcLink.clean(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName, 24); } catch (Exception e) { return ""; }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
}
