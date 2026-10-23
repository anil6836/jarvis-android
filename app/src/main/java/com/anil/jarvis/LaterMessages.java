package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 📬 Messages put off for later: "తర్వాత" on the top card, a card left alone, ones that waited too long while he was
 * busy, and chats he set to "🔕 కార్డ్‌లో వద్దు". One entry per chat (its app, who, the notification it came on, its
 * texts, a voice note / photo / video, a group, his name in it, ⭐). Kept on the phone for 12 or 24 hours (his choice);
 * what ran out is told once in the 📬 list. A chat he opened or cleared in its own app leaves the list by itself. A
 * one-time code is never kept. (Anil, 23 Oct: the 📬 box, 1-9.)
 */
final class LaterMessages {
    private LaterMessages() {}

    interface Listener { void changed(int count); }

    /** The floating button's dot. */
    static volatile Listener listener;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_later_msgs", Context.MODE_PRIVATE); }

    /** How long a chat stays (hours): 12 or 24, his choice in Settings. */
    static int keepHours(Context c) { return new Prefs(c).sp.getInt("later_keep_h", 12) == 24 ? 24 : 12; }

    /** All entries still kept (older ones dropped here, and counted for the list's "పాతవి తీసేశాను" line). */
    static synchronized List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<>();
        long now = System.currentTimeMillis(), keep = keepHours(c) * 3600_000L;
        int dropped = 0;
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (now - o.optLong("t") < keep) out.add(o); else dropped++;
            }
        } catch (Exception ignored) {}
        if (dropped > 0) { // (written back, so they are counted once; the dot and the shade note follow)
            sp(c).edit().putInt("expired", sp(c).getInt("expired", 0) + dropped).apply();
            writeQuiet(c, out);
            changed(c, out.size());
        }
        return out;
    }

    private static void writeQuiet(Context c, List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        sp(c).edit().putString("list", a.toString()).apply();
    }

    private static void write(Context c, List<JSONObject> l) {
        writeQuiet(c, l);
        changed(c, l.size());
    }

    private static void changed(Context c, int n) {
        Listener li = listener;
        if (li != null) li.changed(n);
        try { LaterBox.noteChanged(c.getApplicationContext()); } catch (Exception ignored) {} // (the 📬 note in the shade)
        LaterBox.listChanged(); // (and the list, if it is up)
    }

    /** How many chats ran out of time since the list last said so (then 0). */
    static synchronized int takeExpired(Context c) {
        list(c);
        int n = sp(c).getInt("expired", 0);
        if (n > 0) sp(c).edit().putInt("expired", 0).apply();
        return n;
    }

    // ---------------------------------------------------------------- adding

    /** One chat's messages put off (the old way: no notification / media details). */
    static void add(Context c, String app, String from, List<String> texts, int id) {
        add(c, app, from, "", "", id, texts, false, null, 0);
    }

    /**
     * One chat's messages put off for later; the same chat again: added to it. key: the notification they came on (it
     * going away = read in its own app); media: "voice" / "audio" / "photo" / "video" for a single such message;
     * postedAt: when it came (to find that file).
     */
    static synchronized void add(Context c, String app, String from, String pkg, String key, int id, List<String> texts, boolean group,
                                 String media, long postedAt) {
        List<String> keep = new ArrayList<>();
        for (String t : texts) if (t != null && !Otp.has(t)) keep.add(t); // (a code: never kept; it is in the SMS app)
        if (keep.isEmpty()) return;
        if (key != null && !key.isEmpty() && seenAlready(key)) return; // (he read it in its own app meanwhile)
        try {
            List<JSONObject> l = list(c);
            JSONObject hit = null;
            for (JSONObject o : l) if (o.optString("app").equals(app) && o.optString("from").equals(from)) hit = o;
            long now = System.currentTimeMillis();
            if (hit == null) {
                hit = new JSONObject().put("app", app).put("from", from).put("texts", new JSONArray()).put("first", now);
                l.add(hit);
            }
            JSONArray t = hit.getJSONArray("texts");
            // A notification shown again (a restart, the app refreshing it) repeats what is here already: only texts
            // beyond those count as new ("సరే" twice in a row stays twice), and a pure repeat changes nothing.
            Map<String, Integer> had = new HashMap<>();
            for (int i = 0; i < t.length(); i++) had.merge(t.optString(i), 1, Integer::sum);
            List<String> fresh = new ArrayList<>();
            for (String s : keep) {
                Integer n = had.get(s);
                if (n != null && n > 0) had.put(s, n - 1);
                else fresh.add(s);
            }
            if (fresh.isEmpty()) {
                if (t.length() == 0) l.remove(hit);
                return;
            }
            int before = t.length();
            for (String s : fresh) t.put(s);
            while (t.length() > 15) t.remove(0);
            hit.put("id", id).put("t", now);
            if (pkg != null && !pkg.isEmpty()) hit.put("pkg", pkg);
            if (key != null && !key.isEmpty()) hit.put("key", key);
            hit.put("group", group || hit.optBoolean("group"));
            hit.put("media", before == 0 && fresh.size() == 1 && t.length() == 1 && media != null ? media : ""); // (one voice note / photo alone)
            hit.put("posted", postedAt > 0 ? postedAt : now);
            Prefs p = new Prefs(c);
            if (!NotifyListener.naming(fresh, p.myNames()).isEmpty() && (group || hit.optBoolean("group"))) hit.put("mention", true);
            hit.put("vip", !from.isEmpty() && !hit.optBoolean("group") && CallWords.vip(from, CallCare.vipList(c)));
            trim(c, l);
            write(c, l);
        } catch (Exception ignored) {}
    }

    /** Over 20 chats: the least important, oldest one goes (counted in the list's "పాత చాట్‌లు" line). */
    private static void trim(Context c, List<JSONObject> l) {
        int dropped = 0;
        while (l.size() > 20) {
            int worst = 0;
            for (int i = 1; i < l.size(); i++) {
                JSONObject a = l.get(i), w = l.get(worst);
                if (rank(a) < rank(w) || rank(a) == rank(w) && a.optLong("t") < w.optLong("t")) worst = i;
            }
            l.remove(worst);
            dropped++;
        }
        if (dropped > 0) sp(c).edit().putInt("expired", sp(c).getInt("expired", 0) + dropped).apply();
    }

    // ---------------------------------------------------------------- taking out

    /** The chat's entry as it is now (texts that came meanwhile too), else null. */
    static synchronized JSONObject find(Context c, String app, String from) {
        for (JSONObject o : list(c)) if (o.optString("app").equals(app) && o.optString("from").equals(from)) return o;
        return null;
    }

    /**
     * These texts of a chat were read out in full: only they leave 📬 (texts that came while it was read stay). Nothing
     * is taken out before it is read, so a call, ✕, the voice failing or the app being killed loses nothing.
     */
    static synchronized void consume(Context c, String app, String from, List<String> read) {
        if (read == null || read.isEmpty()) return;
        List<JSONObject> l = list(c);
        for (int k = 0; k < l.size(); k++) {
            JSONObject o = l.get(k);
            if (!o.optString("app").equals(app) || !o.optString("from").equals(from)) continue;
            JSONArray t = o.optJSONArray("texts"), left = new JSONArray();
            List<String> todo = new ArrayList<>(read);
            for (int i = 0; t != null && i < t.length(); i++) {
                String x = t.optString(i);
                if (todo.remove(x)) continue; // (read: out)
                left.put(x);
            }
            if (left.length() == 0) l.remove(k);
            else try { o.put("texts", left); o.put("media", ""); } catch (Exception ignored) {}
            write(c, l);
            return;
        }
    }

    static synchronized void clear(Context c) { write(c, new ArrayList<>()); }

    static int count(Context c) { return list(c).size(); }

    /** One chat out of the list (read, or 🗑 / swiped away). */
    static synchronized JSONObject remove(Context c, String app, String from) {
        List<JSONObject> l = list(c);
        JSONObject gone = null;
        for (int i = l.size() - 1; i >= 0; i--) {
            JSONObject o = l.get(i);
            if (o.optString("app").equals(app) && o.optString("from").equals(from)) { gone = o; l.remove(i); }
        }
        if (gone != null) write(c, l);
        return gone;
    }

    // ---------------------------------------------------------------- read in its own app: gone by itself

    /** Notification keys that went away as read (opened / cleared / the app's own "read"), and when. */
    private static final Map<String, Long> seenKeys = Collections.synchronizedMap(new HashMap<>());

    /** The chat's notification went because he opened it or swiped it away: read (out of the list at once). */
    static void seen(Context c, String key) {
        if (key == null || key.isEmpty()) return;
        seenKeys.put(key, System.currentTimeMillis());
        synchronized (LaterMessages.class) {
            List<JSONObject> l = list(c);
            boolean hit = false;
            for (int i = l.size() - 1; i >= 0; i--) if (key.equals(l.get(i).optString("key"))) { l.remove(i); hit = true; }
            if (hit) write(c, l);
        }
    }

    /** Cancels by apps, per package: when (a burst of several = the app was just opened, not each chat read). */
    private static final Map<String, List<Long>> appCancels = new HashMap<>();
    /** Packages Jarvis itself just touched (his reply sent, the app opened from 📬): their cancels mean nothing. */
    private static final Map<String, Long> holds = Collections.synchronizedMap(new HashMap<>());
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Jarvis replied in / opened this app: its own notification changes for a little while are not "read". */
    static void hold(String pkg) { if (pkg != null && !pkg.isEmpty()) holds.put(pkg, System.currentTimeMillis()); }

    /**
     * The app itself cleared a chat's notification (often: read there). Counted after 3 s: a post on the same key meanwhile
     * (an app refreshing its note), a burst of clears from the same app (its main screen opened), or Jarvis's own reply /
     * opening of that app just before: not read.
     */
    static void appCleared(Context c, String pkg, String key) {
        if (key == null || key.isEmpty() || pkg == null) return;
        final long now = System.currentTimeMillis();
        Long h = holds.get(pkg);
        if (h != null && now - h < 10_000) return;
        synchronized (appCancels) {
            List<Long> l = appCancels.get(pkg);
            if (l == null) appCancels.put(pkg, l = new ArrayList<>());
            l.add(now);
            while (!l.isEmpty() && now - l.get(0) > 5000) l.remove(0);
        }
        pendingClear.put(key, now);
        final Context app = c.getApplicationContext();
        main.postDelayed(() -> {
            Long at = pendingClear.remove(key);
            if (at == null || at != now) return; // (posted again meanwhile)
            int burst;
            synchronized (appCancels) {
                List<Long> l = appCancels.get(pkg);
                burst = 0;
                if (l != null) for (long t : l) if (Math.abs(t - now) < 2500) burst++;
            }
            if (burst >= 3) return; // (several chats cleared at once: the app was opened, not each one read)
            seen(app, key);
        }, 3000);
    }

    private static final Map<String, Long> pendingClear = Collections.synchronizedMap(new HashMap<>());

    /** Per package: when he himself tapped / swiped a group's summary line away (the app clearing it doesn't count). */
    private static final Map<String, Long> summaryByHim = Collections.synchronizedMap(new HashMap<>());

    /** A group's summary line went: by his tap / swipe, or by the app. */
    static void summaryGone(String pkg, boolean byHim) {
        if (pkg == null) return;
        if (byHim) summaryByHim.put(pkg, System.currentTimeMillis());
        else summaryByHim.remove(pkg);
    }

    /**
     * A chat's notification went with its group: read only when he swiped / tapped that group (its summary, within 2 s,
     * either order); the app clearing the group: as the app clearing it (checked the same careful way).
     */
    static void groupChildGone(Context c, String pkg, String key) {
        if (key == null || key.isEmpty() || pkg == null) return;
        final long now = System.currentTimeMillis();
        final Context app = c.getApplicationContext();
        pendingClear.put(key, now);
        main.postDelayed(() -> {
            Long was = pendingClear.get(key);
            if (was == null || was != now) return; // (posted again meanwhile: not gone)
            pendingClear.remove(key);
            Long at = summaryByHim.get(pkg);
            if (at != null && Math.abs(at - now) < 2000) seen(app, key);
            else appCleared(app, pkg, key);
        }, 800);
    }

    /** A new message on that notification: it isn't "read" any more (and an app's clear just before was a refresh). */
    static void posted(String key) { if (key != null) { seenKeys.remove(key); pendingClear.remove(key); } }

    private static boolean seenAlready(String key) {
        Long at = seenKeys.get(key);
        return at != null && System.currentTimeMillis() - at < 6 * 3600_000L;
    }

    // ---------------------------------------------------------------- order and the one-line overview

    /** ⭐ first, then a direct chat or his name in a group, then other groups; newest first within each. */
    static List<JSONObject> sorted(Context c) {
        List<JSONObject> l = list(c);
        Collections.sort(l, (a, b) -> {
            int d = rank(b) - rank(a);
            return d != 0 ? d : Long.compare(b.optLong("t"), a.optLong("t"));
        });
        return l;
    }

    static int rank(JSONObject o) {
        if (o.optBoolean("vip")) return 3;
        if (!o.optBoolean("group") || o.optBoolean("mention")) return 2;
        return 0;
    }

    static int messages(JSONObject o) {
        JSONArray t = o.optJSONArray("texts");
        return t == null ? 0 : t.length();
    }

    /** "అమ్మ (2), రవి (1) · ఆఫీస్ గ్రూప్‌లో మీ పేరు · ఇంకా 2 గ్రూప్‌లు" — no AI, nothing leaves the phone. */
    static String overview(Context c) {
        List<JSONObject> l = sorted(c);
        if (l.isEmpty()) return "";
        List<String> people = new ArrayList<>(), named = new ArrayList<>(), groups = new ArrayList<>();
        int others = 0, msgs = 0;
        for (JSONObject o : l) {
            msgs += messages(o);
            String who = o.optString("from");
            if (!o.optBoolean("group")) { if (people.size() < 3) people.add((o.optBoolean("vip") ? "⭐" : "") + who + " (" + messages(o) + ")"); else others++; }
            else if (o.optBoolean("mention")) { if (named.size() < 2) named.add(who); else others++; }
            else if (groups.size() < 2) groups.add(who + " (" + messages(o) + ")");
            else others++;
        }
        StringBuilder b = new StringBuilder();
        b.append(l.size() == 1 ? "ఒక చాట్" : l.size() + " చాట్‌లు").append(", ").append(msgs == 1 ? "ఒక మెసేజ్" : msgs + " మెసేజ్‌లు").append(": ");
        boolean any = false;
        if (!people.isEmpty()) { b.append(String.join(", ", people)); any = true; }
        if (!named.isEmpty()) { b.append(any ? " · " : "").append(String.join(", ", named)).append(named.size() == 1 ? " గ్రూప్‌లో మీ పేరు" : " గ్రూప్‌లలో మీ పేరు"); any = true; }
        if (!groups.isEmpty()) { b.append(any ? " · " : "").append(String.join(", ", groups)).append(groups.size() == 1 ? " గ్రూప్" : " గ్రూప్‌లు"); any = true; }
        if (others > 0) b.append(any ? " · ఇంకా " : "").append(others).append(others == 1 ? " చాట్" : " చాట్‌లు");
        return b.toString();
    }

    /** All of them, to read aloud in order: who, where, and each message (a voice note / photo said as what it is). */
    static String spoken(List<JSONObject> l) {
        StringBuilder b = new StringBuilder();
        for (JSONObject o : l) {
            JSONArray t = o.optJSONArray("texts");
            int n = t == null ? 0 : t.length();
            b.append(o.optString("from")).append(o.optBoolean("group") ? " గ్రూప్‌లో " : " నుంచి ").append(o.optString("app")).append(" లో ")
                    .append(n == 1 ? "మెసేజ్" : n + " మెసేజ్‌లు").append(":\n");
            for (int i = 0; i < n; i++) b.append("• ").append(t.optString(i)).append('\n');
            b.append('\n');
        }
        return b.toString().trim();
    }

    static String spoken(Context c) { return spoken(sorted(c)); }

    // ---------------------------------------------------------------- 🔕 chats that go straight here (no card, no voice)

    private static String chatId(String pkg, String from) { return (pkg == null ? "" : pkg) + "|" + (from == null ? "" : from).toLowerCase(Locale.ROOT); }

    static boolean quiet(Context c, String pkg, String from) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("quiet", "[]"));
            String id = chatId(pkg, from);
            for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).optString("id"))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** 🔕 on (this chat's messages go straight to 📬) or 🔔 back (told on the card again). */
    static synchronized void setQuiet(Context c, String pkg, String from, String app, boolean on) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("quiet", "[]")), out = new JSONArray();
            String id = chatId(pkg, from);
            for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) out.put(a.getJSONObject(i));
            if (on) out.put(new JSONObject().put("id", id).put("from", from).put("app", app == null ? "" : app));
            sp(c).edit().putString("quiet", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The 🔕 chats, for Settings: "Family గ్రూప్ (WhatsApp)". */
    static List<String> quietNames(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("quiet", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(o.optString("from") + (o.optString("app").isEmpty() ? "" : " (" + o.optString("app") + ")"));
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void clearQuiet(Context c) { sp(c).edit().putString("quiet", "[]").apply(); }

    // ---------------------------------------------------------------- ⏰ one gentle reminder

    /** The oldest entry's time, and the newest (a reminder is given once per set of messages). */
    static long oldest(Context c) {
        long t = Long.MAX_VALUE;
        for (JSONObject o : list(c)) t = Math.min(t, o.optLong("first", o.optLong("t")));
        return t == Long.MAX_VALUE ? 0 : t;
    }

    static long newest(Context c) {
        long t = 0;
        for (JSONObject o : list(c)) t = Math.max(t, o.optLong("t"));
        return t;
    }

    /** The newest message from a chat that isn't 🔕 (a busy quiet group must not bring reminders). */
    static long newestTold(Context c) {
        long t = 0;
        for (JSONObject o : list(c)) if (!quiet(c, o.optString("pkg"), o.optString("from"))) t = Math.max(t, o.optLong("t"));
        return t;
    }

    /** A reminder is due: none in the last 3 hours, and something new (not 🔕) came after the last one. */
    static boolean remindDue(Context c) {
        long last = sp(c).getLong("reminded_at", 0), now = System.currentTimeMillis();
        return now - last >= 3 * 3600_000L && newestTold(c) > last;
    }

    static void reminded(Context c) { sp(c).edit().putLong("reminded_at", System.currentTimeMillis()).apply(); }
}
