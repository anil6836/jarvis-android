package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * O37: things he lent (a book, an umbrella, a tool; money is in Debts): "నా గొడుగు రవికి ఇచ్చాను", "రవి గొడుగు తిరిగి
 * ఇచ్చాడు", "ఎవరికి ఏమి ఇచ్చాను?". A gentle word once a month for a thing still out after 30 days. Kept on the phone.
 */
final class Lent {
    private Lent() {}

    static final String KEY = "lent_items";

    static List<JSONObject> all(Context c) { return Notes.list(c, KEY); }

    static List<JSONObject> out(Context c) {
        List<JSONObject> l = new ArrayList<>();
        for (JSONObject o : all(c)) if (!o.optBoolean("back")) l.add(o);
        return l;
    }

    static JSONObject add(Context c, String thing, String who) throws Exception {
        JSONObject o = new JSONObject().put("id", Notes.id("l")).put("thing", thing.trim()).put("who", who.trim())
                .put("at", System.currentTimeMillis()).put("back", false);
        return Notes.add(c, KEY, o, 100);
    }

    /**
     * "రవి గొడుగు" / "గొడుగు" / "రవి": the open one it is (the thing and the person both looked for), or null. By the
     * person alone only when that person has just one thing ("రవి తిరిగి ఇచ్చాడు").
     */
    static JSONObject find(Context c, String words) {
        String w = words == null ? "" : words.toLowerCase(Locale.ROOT).trim();
        if (w.isEmpty()) return null;
        JSONObject best = null;
        int bestScore = 0, sameScore = 0;
        for (JSONObject o : out(c)) {
            int s = 0;
            String thing = o.optString("thing").toLowerCase(Locale.ROOT), who = o.optString("who").toLowerCase(Locale.ROOT);
            for (String x : w.split("\\s+")) {
                if (x.length() < 2) continue;
                String y = x.replaceAll("(ని|ను|కి|కు|దగ్గర)$", "");
                if (!y.isEmpty() && (thing.contains(y) || y.contains(thing) && thing.length() > 1)) s += 2;
                if (!y.isEmpty() && (Offline.skeleton(who).equals(Offline.skeleton(y)) || who.contains(y))) s += 1;
            }
            if (s > bestScore) { bestScore = s; best = o; sameScore = 1; }
            else if (s == bestScore && s > 0) sameScore++;
        }
        if (bestScore >= 2) return sameScore == 1 ? best : null; // the thing matched (two alike: ask)
        return bestScore == 1 && sameScore == 1 ? best : null;   // the person only, with one thing out
    }

    /** It came back: kept as given back (for "ఎవరికి ఏమి ఇచ్చాను" it no longer counts). */
    static JSONObject back(Context c, String words) throws Exception {
        JSONObject o = find(c, words);
        if (o == null) return null;
        o.put("back", true).put("back_at", System.currentTimeMillis());
        Notes.update(c, KEY, o);
        return o;
    }

    static boolean remove(Context c, String words) {
        JSONObject o = find(c, words);
        return o != null && Notes.remove(c, KEY, "id", o.optString("id"));
    }

    /** "రవి దగ్గర: గొడుగు (12 రోజులు)". */
    static String line(JSONObject o) {
        long days = (System.currentTimeMillis() - o.optLong("at")) / 86400000L;
        return o.optString("who") + " దగ్గర: " + o.optString("thing") + " (" + (days == 0 ? "ఈరోజు ఇచ్చారు" : days + " రోజులు") + ")";
    }

    /** What is out, in his words: "రవి దగ్గర: గొడుగు (12 రోజులు). …" or "". */
    static String text(Context c, String who) {
        StringBuilder b = new StringBuilder();
        for (JSONObject o : out(c)) {
            if (who != null && !who.isEmpty() && Offline.fit(who, o.optString("who")) == 0 && !o.optString("who").contains(who)) continue;
            b.append(line(o)).append(". ");
        }
        return b.toString().trim();
    }

    static JSONArray json(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : out(c)) a.put(new JSONObject().put("thing", o.optString("thing")).put("who", o.optString("who")).put("line", line(o)));
        return a;
    }

    /** Once a day (the daily tick): a thing out for 30+ days is mentioned once, and again only a month later. */
    static void tick(Context c, Prefs p, boolean quiet) {
        if (quiet) return;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences st = c.getSharedPreferences("jarvis_lent", Context.MODE_PRIVATE);
        String today = java.time.LocalDate.now().toString();
        if (today.equals(st.getString("told_day", ""))) return; // one a day at most
        boolean toldOne = false;
        for (JSONObject o : out(c)) {
            long at = o.optLong("at"), told = o.optLong("told");
            if (now - at < 30L * 86400000L || now - told < 30L * 86400000L) continue;
            try {
                o.put("told", now);
                Notes.update(c, KEY, o);
            } catch (Exception ignored) {}
            Reminders.notify(c, "📦 ఇచ్చిన వస్తువు", o.optString("who") + " కి ఇచ్చిన " + o.optString("thing") + " ఇంకా తిరిగి రాలేదు ("
                    + (now - at) / 86400000L + " రోజులు). వచ్చాక \"" + o.optString("who") + " " + o.optString("thing") + " తిరిగి ఇచ్చాడు\" అనండి.", o.optString("id").hashCode());
            toldOne = true;
            break;
        }
        if (toldOne) st.edit().putString("told_day", today).apply();
    }
}
