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

        // talks with Jarvis (one pass over the archive)
        for (JSONObject o : store.searchArchiveAny(words, since, 60)) {
            String who = "assistant".equals(o.optString("role")) ? "Jarvis said" : "he said";
            add(hits, "talk with Jarvis (" + who + ")", o.optString("content"), o.optLong("t"), words);
        }
        // SMS: the phone looks for his words itself (the whole year, not only the newest ones); never codes or OTPs
        for (String[] m : sms(c, words, since)) {
            if (CODE.matcher(m[1]).find()) continue;
            long t = 0;
            try { t = Long.parseLong(m[2]); } catch (Exception ignored) {}
            add(hits, "SMS from " + m[0], m[1], t, words);
        }
        // standing records (memories, notes, debts, dates, item places...) are searched however old they are
        for (JSONObject o : store.memories()) add(hits, "saved memory", o.optString("text"), 0, words);
        for (JSONObject o : store.missions()) add(hits, o.optBoolean("done") ? "mission (done)" : "mission", o.optString("text"), 0, words);
        for (JSONObject o : store.reminders()) add(hits, o.optBoolean("done") ? "reminder (past)" : "reminder", o.optString("text"), o.optLong("at"), words);
        for (JSONObject o : Diary.all(c)) add(hits, "diary " + o.optString("date"), o.optString("text"), o.optLong("t"), words);
        for (JSONObject o : Money.expenses(c)) add(hits, "expense", flat(o), o.optLong("t"), words);
        String[][] standing = {{"notes", "note"}, {Debts.KEY, "debt / EMI / chit"}, {Expiry.KEY, "expiry / warranty date"}, {Everyday.ITEMS, "where he kept a thing"},
                {"prayers", "prayer list"}, {Automations.KEY, "automation rule"}};
        for (String[] l : standing) for (JSONObject o : Notes.list(c, l[0])) add(hits, l[1], flat(o), 0, words);
        String[][] logs = {{Vitals.KEY, "BP / sugar / weight reading"}, {RecorderService.KEY, "recording"}};
        for (String[] l : logs) for (JSONObject o : Notes.list(c, l[0])) add(hits, l[1], flat(o), o.optLong("t", o.optLong("made")), words);
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
            if (h.time > 0 && h.time < since) continue; // logs older than he asked for (standing records carry no time here)
            out.put(new JSONObject().put("from", h.source).put("when", h.time > 0 ? WHEN.format(new Date(h.time)) : "")
                    .put("text", h.text.length() > 240 ? h.text.substring(0, 240) + "…" : h.text));
        }
        return out;
    }

    /** Codes, OTPs and passwords: never searched or shown. */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile(
            "(?i)(\\botp\\b|one[ -]time|verification|security code|login code|secure code|\\bpin\\b|password|do not share|don'?t share|ఓటీపీ)");

    /** Inbox SMS since a time whose text has any of his words (asked of the phone's own SMS store). */
    private static List<String[]> sms(Context c, List<String> words, long since) {
        List<String[]> out = new ArrayList<>();
        if (c.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED || words.isEmpty()) return out;
        StringBuilder sel = new StringBuilder("date >= ? AND (");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(since));
        for (int i = 0; i < words.size() && i < 8; i++) {
            if (i > 0) sel.append(" OR ");
            sel.append("body LIKE ?");
            args.add("%" + words.get(i) + "%");
        }
        sel.append(")");
        try (android.database.Cursor cur = c.getContentResolver().query(android.net.Uri.parse("content://sms/inbox"), new String[]{"address", "body", "date"},
                sel.toString(), args.toArray(new String[0]), "date DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < 300) {
                out.add(new String[]{cur.getString(0), cur.getString(1) == null ? "" : cur.getString(1), String.valueOf(cur.getLong(2))});
            }
        } catch (Exception ignored) {}
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
