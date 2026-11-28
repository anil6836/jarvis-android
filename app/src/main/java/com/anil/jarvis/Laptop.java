package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 💻 This phone ↔ Jarvis PC on his laptop (phase 1, 28 Nov): the pairing from the laptop's QR (hello → hello_ok), "is the
 * laptop on?" (ping → pong) and unpairing (bye, either side). Phase 2 (W83): jobs for the laptop from the phone / watch:
 * cmd {cid, action, args, confirmed?} and ask {cid, text} → the laptop's result {cid, ok, te, code?, data?} (25 s).
 * Sleep / shutdown / restart go only with his yes here first (confirmed); the laptop keeps its own rules too. The key stays in this phone's own private settings, never in
 * the Drive backup or Google's backup (a new phone scans a new QR), never shown, never sent to any AI. ₹0, no AI.
 * Network calls block: call them off the main thread.
 */
final class Laptop {
    private Laptop() {}

    static final String PREFS = "pc_link_private";
    static final int HELLO_WAIT_S = 25, PING_WAIT_S = 20, RESULT_WAIT_S = 25;
    /** The laptop's results by cid, for whoever waits for them (the phone screen, the voice tool and the watch may wait at once). */
    private static final Map<String, JSONObject> RESULTS = new ConcurrentHashMap<>();

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

    /** The answer of one try (code / data: the laptop's, for jobs). */
    static final class Result {
        final boolean ok;
        final String line, code;
        final JSONObject data;
        Result(boolean ok, String line) { this(ok, line, "", null); }
        Result(boolean ok, String line, String code, JSONObject data) { this.ok = ok; this.line = line; this.code = code == null ? "" : code; this.data = data; }
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

    // ================================================================ phase 2: jobs for the laptop

    /** The jobs the laptop knows (jarvis-pc-plan.md "Phase-2 kinds"). */
    static final String[] ACTIONS = {"status", "lock", "sleep", "shutdown", "restart", "cancel_shutdown", "volume", "media", "open_app", "close_app", "youtube", "open_site", "say"};

    /** Jobs that wait for his yes on the phone / watch first. */
    static boolean needsYes(String action) { return "sleep".equals(action) || "shutdown".equals(action) || "restart".equals(action); }

    static boolean known(String action) {
        for (String a : ACTIONS) if (a.equals(action)) return true;
        return false;
    }

    /** One job (cmd): the laptop's answer within 25 s, or an honest "no answer". */
    static Result cmd(Context c, String action, JSONObject args, boolean confirmed) {
        if (!known(action)) return new Result(false, "ఆ పని ల్యాప్‌టాప్‌కి ఇంకా రాదు.", "unknown", null);
        if (needsYes(action) && !confirmed) return new Result(false, "ముందు మిమ్మల్ని అడగాలి.", "need_confirm", null);
        return job(c, "cmd", action, args, confirmed, null);
    }

    /** A free sentence for the laptop's own brain ("ఈ స్క్రీన్‌లో ఏముంది?", his yes to its question). */
    static Result ask(Context c, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return new Result(false, "ల్యాప్‌టాప్‌కి ఏం చెప్పాలో చెప్పండి.", "empty", null);
        return job(c, "ask", null, null, false, t.length() > 500 ? t.substring(0, 500) : t);
    }

    private static Result job(Context c, String kind, String action, JSONObject args, boolean confirmed, String text) {
        byte[] k = key(c);
        if (k == null || !paired(c)) return new Result(false, NO_KEY, "not_paired", null);
        PcLink link = new PcLink(k);
        String cid = PcCrypto.newId();
        long start = now() - 5;
        try {
            link.send(jobJson(kind, cid, action, args, confirmed, text, PcCrypto.newId(), now()));
        } catch (IOException e) {
            return new Result(false, "పంపలేకపోయాను: ఫోన్‌లో నెట్ ఉందా చూడండి. (" + PcLink.clean(e.getMessage(), 60) + ")", "no_net", null);
        } catch (JSONException e) {
            return new Result(false, "ఆ పని తయారు చేయలేకపోయాను.", "failed", null);
        }
        JSONObject r = waitResult(c, link, start, cid, RESULT_WAIT_S);
        if (!hasKey(c)) return new Result(false, "ల్యాప్‌టాప్ జత తీసేసింది. కొత్త QR తో మళ్లీ జత చేయండి.", "unpaired", null);
        if (r == null) return new Result(false, "❌ " + label(c) + " నుంచి " + RESULT_WAIT_S + " సెకన్లలో జవాబు రాలేదు: లాప్‌టాప్ ఆఫ్‌లో / నిద్రలో ఉంది, "
                + "దానికి నెట్ లేదు, Jarvis PC మూసి ఉంది, లేదా Jarvis PC పాతది (ఈ పనులు v1.0.2 నుంచే).", "no_answer", null);
        boolean ok = r.optBoolean("ok", false);
        String code = r.optString("code", "");
        JSONObject data = r.optJSONObject("data");
        String te = te(r.optString("te", ""));
        if (te.isEmpty()) te = ok ? "✅ అయింది." : "❌ కాలేదు" + (code.isEmpty() ? "." : " (" + code + ").");
        if (ok && "status".equals(action) && data != null) saveStatus(c, data);
        if (ok) sp(c).edit().putLong("seen", now()).apply();
        return new Result(ok, te, code, data);
    }

    /** The words of a job: {"v":1,"id","t","k":"cmd"|"ask","cid",…}. */
    static String jobJson(String kind, String cid, String action, JSONObject args, boolean confirmed, String text, String id, long t) throws JSONException {
        JSONObject o = new JSONObject().put("v", 1).put("id", id).put("t", t).put("k", kind).put("cid", cid);
        if ("cmd".equals(kind)) {
            o.put("action", action).put("args", args == null ? new JSONObject() : args);
            if (confirmed) o.put("confirmed", true);
        } else o.put("text", text);
        return o.toString();
    }

    /** The laptop's Telugu line: at most 600 characters, line breaks kept, other control characters out. */
    static String te(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 600; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch >= ' ' && ch != 0x7F) b.append(ch);
        }
        return b.toString().trim();
    }

    private static void saveStatus(Context c, JSONObject d) {
        JSONObject b = d.optJSONObject("battery"), snd = d.optJSONObject("sound");
        SharedPreferences.Editor e = sp(c).edit().putLong("st_at", now());
        if (b != null && b.optBoolean("present", true) && b.has("percent")) e.putInt("st_batt", b.optInt("percent", -1)).putBoolean("st_chg", b.optBoolean("charging"));
        else e.remove("st_batt").remove("st_chg");
        if (snd != null && snd.has("level")) e.putInt("st_vol", snd.optInt("level", -1)).putBoolean("st_mute", snd.optBoolean("muted"));
        e.apply();
    }

    /** "🔋 82% ⚡ · 🔊 40%" from the last status (empty when none). */
    static String statusLine(Context c) {
        SharedPreferences s = sp(c);
        if (!s.contains("st_at")) return "";
        StringBuilder b = new StringBuilder();
        if (s.contains("st_batt")) b.append("🔋 ").append(s.getInt("st_batt", 0)).append('%').append(s.getBoolean("st_chg", false) ? " ⚡" : "");
        if (s.contains("st_vol")) {
            if (b.length() > 0) b.append(" · ");
            b.append(s.getBoolean("st_mute", false) ? "🔇 " : "🔊 ").append(s.getInt("st_vol", 0)).append('%');
        }
        return b.toString();
    }

    /** The watch's 💻 screen: paired?, the laptop's name, the last status (no network). */
    static JSONObject panel(Context c) throws JSONException {
        JSONObject o = new JSONObject().put("kind", "laptop").put("paired", paired(c));
        if (!paired(c)) return o;
        SharedPreferences s = sp(c);
        o.put("name", name(c));
        if (s.contains("st_batt")) o.put("batt", s.getInt("st_batt", 0)).put("charging", s.getBoolean("st_chg", false));
        if (s.contains("st_vol")) o.put("vol", s.getInt("st_vol", 0)).put("muted", s.getBoolean("st_mute", false));
        if (s.contains("st_at")) o.put("at", s.getLong("st_at", 0));
        return o;
    }

    private static JSONObject waitResult(Context c, PcLink link, long since, String cid, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(1500); } catch (InterruptedException e) { return null; }
            JSONObject r = RESULTS.remove(cid);
            if (r != null) return r;
            try {
                take(c, link.poll(since), null);
                if (!hasKey(c)) return null; // (a bye came)
            } catch (IOException ignored) { /* (the next round tries again) */ }
            r = RESULTS.remove(cid);
            if (r != null) return r;
        }
        return null;
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
                case "result": { // (for whoever waits for this cid)
                    try {
                        JSONObject o = new JSONObject(m.json);
                        String cid = o.optString("cid", "");
                        if (cid.matches("^[0-9a-f]{16}$")) {
                            if (RESULTS.size() > 50) RESULTS.clear();
                            RESULTS.put(cid, o);
                        }
                    } catch (JSONException ignored) {}
                    e.putLong("seen", now);
                    break;
                }
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
