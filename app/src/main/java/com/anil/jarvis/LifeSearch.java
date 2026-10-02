package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Life search": one question across everything Jarvis keeps on the phone, old talks, SMS, notes, diary, expenses,
 * debts, expiry and warranty dates, reminders, memories, missions, where things were kept, BP / sugar readings,
 * bike charges and recordings. Plain word matching (any of his words, in Telugu or English); the best matches come
 * back with where they were found and the date, and Jarvis answers from them. Nothing leaves the phone except
 * these few lines to the AI he chose.
 */
final class LifeSearch {
    private LifeSearch() {}

    private static final SimpleDateFormat WHEN = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH);
    /** Fields that are not words to search (ids, files, pictures). */
    private static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList("id", "t", "at", "made", "last", "bill_file", "photo", "file", "uri", "checked"));

    private static final class Hit {
        final String source, text;
        final long time;
        final int score;
        Hit(String source, String text, long time, int score) { this.source = source; this.text = text; this.time = time; this.score = score; }
    }

    static JSONArray search(Context c, Store store, String query, int days) throws Exception {
        List<String> words = words(query);
        long since = System.currentTimeMillis() - Math.max(1, days <= 0 ? 365 : days) * 86400000L;
        List<Hit> hits = new ArrayList<>();
        if (words.isEmpty()) return new JSONArray();

        // talks with Jarvis
        Set<Long> seen = new HashSet<>();
        for (String w : words) {
            for (JSONObject o : store.searchArchive(w, since, 20)) {
                if (!seen.add(o.optLong("t") * 31 + o.optString("content").hashCode())) continue;
                String who = "assistant".equals(o.optString("role")) ? "Jarvis said" : "he said";
                add(hits, "talk with Jarvis (" + who + ")", o.optString("content"), o.optLong("t"), words);
            }
        }
        // SMS (never OTPs)
        for (String[] m : Life.sms(c, since, 3000)) {
            if (m[1] == null) continue;
            String low = m[1].toLowerCase(Locale.ROOT);
            if (low.contains("otp") || low.contains("one time password")) continue;
            long t = 0;
            try { t = Long.parseLong(m[2]); } catch (Exception ignored) {}
            add(hits, "SMS from " + m[0], m[1], t, words);
        }
        for (JSONObject o : store.memories()) add(hits, "saved memory", o.optString("text"), o.optLong("t"), words);
        for (JSONObject o : store.missions()) add(hits, o.optBoolean("done") ? "mission (done)" : "mission", o.optString("text"), o.optLong("t"), words);
        for (JSONObject o : store.reminders()) add(hits, o.optBoolean("done") ? "reminder (past)" : "reminder", o.optString("text"), o.optLong("at"), words);
        for (JSONObject o : Diary.all(c)) add(hits, "diary " + o.optString("date"), o.optString("text"), o.optLong("t"), words);
        for (JSONObject o : Money.expenses(c)) add(hits, "expense", flat(o), o.optLong("t"), words);
        String[][] lists = {{"notes", "note"}, {Debts.KEY, "debt / EMI / chit"}, {Expiry.KEY, "expiry / warranty date"}, {Everyday.ITEMS, "where he kept a thing"},
                {Vitals.KEY, "BP / sugar / weight reading"}, {RecorderService.KEY, "recording"}, {"prayers", "prayer list"}, {Automations.KEY, "automation rule"}};
        for (String[] l : lists) for (JSONObject o : Notes.list(c, l[0])) add(hits, l[1], flat(o), o.optLong("t", o.optLong("made")), words);
        try { // bike charges (kept with the bike)
            JSONArray a = new JSONArray(c.getSharedPreferences("jarvis_bike", Context.MODE_PRIVATE).getString("charges", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                add(hits, "bike charge", "bike charged " + o.optInt("from") + "% to " + o.optInt("to") + "%, ₹" + o.optLong("cost")
                        + (o.optBoolean("paid") ? " paid at a public charger" : " at home") + " బైక్ ఛార్జ్", o.optLong("t"), words);
            }
        } catch (Exception ignored) {}

        hits.sort((x, y) -> x.score != y.score ? Integer.compare(y.score, x.score) : Long.compare(y.time, x.time));
        JSONArray out = new JSONArray();
        for (Hit h : hits) {
            if (out.length() >= 30) break;
            if (h.time > 0 && h.time < since && !h.source.startsWith("expiry")) continue; // dates of things to come are always kept
            out.put(new JSONObject().put("from", h.source).put("when", h.time > 0 ? WHEN.format(new Date(h.time)) : "")
                    .put("text", h.text.length() > 240 ? h.text.substring(0, 240) + "…" : h.text));
        }
        return out;
    }

    /** His words: split on commas / spaces, lower case, 2+ letters; the whole phrase too. */
    static List<String> words(String q) {
        List<String> w = new ArrayList<>();
        if (q == null) return w;
        String all = q.toLowerCase(Locale.ROOT).trim();
        for (String part : all.split("[,;/|]+")) {
            String p = part.trim();
            if (p.length() >= 2 && !w.contains(p)) w.add(p);
            for (String x : p.split("\\s+")) if (x.length() >= 3 && !w.contains(x)) w.add(x);
        }
        return w;
    }

    private static void add(List<Hit> hits, String source, String text, long time, List<String> words) {
        if (text == null || text.isEmpty()) return;
        String low = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String w : words) if (low.contains(w)) score += w.contains(" ") ? 3 : 1; // a whole phrase counts more
        if (score > 0) hits.add(new Hit(source, text.replaceAll("\\s+", " ").trim(), time, score));
    }

    /** All of an entry's words in one line ("what: petrol, amount: 500, shop: HP"). */
    private static String flat(JSONObject o) {
        StringBuilder b = new StringBuilder();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (SKIP.contains(k)) continue;
            Object v = o.opt(k);
            if (v instanceof String && ((String) v).length() > 400) continue; // pictures and other long data
            if (v instanceof String || v instanceof Number || v instanceof Boolean) b.append(k).append(": ").append(v).append(", ");
        }
        return b.length() > 2 ? b.substring(0, b.length() - 2) : "";
    }
}
