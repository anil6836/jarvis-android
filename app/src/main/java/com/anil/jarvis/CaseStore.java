package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cases: everything Jarvis has about one problem (a bike noise, a leaking roof, a document job), kept together as cards
 * on a board: scans, photos, notes, PDFs, expenses, "what happened" timelines, evidence cards. Stored as JSON in
 * prefs "jarvis_cases" (about 40 cases, 60 cards each). The board's AI links and summary are kept with the case.
 */
final class CaseStore {
    private CaseStore() {}

    static final String[] KINDS = {"scan", "photo", "note", "pdf", "expense", "timeline", "evidence", "ai"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_cases", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("cases", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        out.sort((x, y) -> Long.compare(y.optLong("updated"), x.optLong("updated")));
        return out;
    }

    private static synchronized void write(Context c, List<JSONObject> cases) {
        while (cases.size() > 40) cases.remove(cases.size() - 1);
        JSONArray a = new JSONArray();
        for (JSONObject o : cases) a.put(o);
        sp(c).edit().putString("cases", a.toString()).apply();
    }

    static synchronized JSONObject get(Context c, String id) {
        for (JSONObject o : all(c)) if (id != null && id.equals(o.optString("id"))) return o;
        return null;
    }

    /** A new case with this title (or "కేస్ N"). */
    static synchronized JSONObject create(Context c, String title) {
        List<JSONObject> cases = all(c);
        try {
            String t = title == null || title.trim().isEmpty() ? "కేస్ " + (cases.size() + 1) : title.trim();
            JSONObject o = new JSONObject().put("id", "c" + Long.toString(System.currentTimeMillis(), 36)).put("title", t)
                    .put("t", System.currentTimeMillis()).put("updated", System.currentTimeMillis())
                    .put("cards", new JSONArray()).put("links", new JSONArray()).put("summary", "");
            cases.add(0, o);
            write(c, cases);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void save(Context c, JSONObject kase) {
        List<JSONObject> cases = all(c);
        long loadedAt = kase.optLong("updated", 0);
        boolean found = false;
        for (int i = 0; i < cases.size(); i++) {
            if (!cases.get(i).optString("id").equals(kase.optString("id"))) continue;
            // cards a worker added (photos importing, a story) after this copy was loaded are kept, not written over
            try {
                JSONArray mine = kase.optJSONArray("cards"), theirs = cases.get(i).optJSONArray("cards");
                if (mine != null && theirs != null) {
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (int k = 0; k < mine.length(); k++) ids.add(mine.optJSONObject(k) == null ? "" : mine.optJSONObject(k).optString("id"));
                    for (int k = 0; k < theirs.length(); k++) {
                        JSONObject o = theirs.optJSONObject(k);
                        if (o != null && !ids.contains(o.optString("id")) && o.optLong("t") > loadedAt) mine.put(o);
                    }
                }
            } catch (Exception ignored) {}
            cases.set(i, kase);
            found = true;
        }
        try { kase.put("updated", System.currentTimeMillis()); } catch (Exception ignored) {}
        if (!found) cases.add(0, kase);
        write(c, cases);
    }

    static synchronized void delete(Context c, String id) {
        List<JSONObject> cases = all(c);
        cases.removeIf(o -> o.optString("id").equals(id));
        write(c, cases);
    }

    static synchronized void rename(Context c, String id, String title) {
        JSONObject k = get(c, id);
        if (k == null || title == null || title.trim().isEmpty()) return;
        try { k.put("title", title.trim()); } catch (Exception ignored) {}
        save(c, k);
    }

    /** The last case he worked on (for "add to case" with one tap), or null. */
    static JSONObject latest(Context c) {
        List<JSONObject> l = all(c);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * A card: kind (scan / photo / note / pdf / expense / timeline / evidence / ai), ref (scan id, uri, text, file path),
     * title and a brief line. The same ref is not added twice. Returns the card.
     */
    static synchronized JSONObject addCard(Context c, String caseId, String kind, String ref, String title, String brief) {
        JSONObject k = get(c, caseId);
        if (k == null) return null;
        try {
            JSONArray cards = k.optJSONArray("cards");
            if (cards == null) { cards = new JSONArray(); k.put("cards", cards); }
            for (int i = 0; i < cards.length(); i++) {
                JSONObject o = cards.optJSONObject(i);
                if (o != null && kind.equals(o.optString("kind")) && ref != null && ref.equals(o.optString("ref"))) { o.put("hidden", false); save(c, k); return o; }
            }
            if (cards.length() >= 60) return null;
            int n = cards.length();
            // laid out in a loose spiral around the middle; he can drag them anywhere
            double ang = n * 2.399, rad = 0.12 + 0.045 * Math.sqrt(n);
            JSONObject card = new JSONObject().put("id", "k" + Long.toString(System.currentTimeMillis(), 36) + n).put("kind", kind)
                    .put("ref", ref == null ? "" : ref).put("title", FloatBubble.noIds(title == null ? "" : title)).put("brief", FloatBubble.noIds(brief == null ? "" : brief))
                    .put("t", System.currentTimeMillis()).put("x", Math.max(0.08, Math.min(0.92, 0.5 + rad * Math.cos(ang))))
                    .put("y", Math.max(0.1, Math.min(0.9, 0.5 + rad * Math.sin(ang) * 0.8)));
            cards.put(card);
            save(c, k);
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void setLinks(Context c, String caseId, JSONArray links, String summary) {
        JSONObject k = get(c, caseId);
        if (k == null) return;
        try {
            k.put("links", links == null ? new JSONArray() : links);
            if (summary != null) k.put("summary", FloatBubble.noIds(summary));
        } catch (Exception ignored) {}
        save(c, k);
    }

    // ================================================================ words (no Android)

    /** The emoji for a kind of card. */
    static String icon(String kind) {
        switch (kind == null ? "" : kind) {
            case "scan": return "📷";
            case "photo": return "🖼️";
            case "note": return "📝";
            case "pdf": return "📄";
            case "expense": return "💰";
            case "timeline": return "🕵️";
            case "evidence": return "📦";
            case "ai": return "🧠";
            default: return "▫️";
        }
    }

    /**
     * His filter words → which cards show. "fault" / "సమస్య" → cards whose brief mentions a problem; "గత వారం" / "ఈరోజు" /
     * "నిన్న" / "ఈ నెల" → by time; a kind word (ఫోటో, స్కాన్, నోట్, PDF, ఖర్చు) → that kind; other words → in title / brief.
     * Returns the card ids that pass (all when the filter is empty).
     */
    static List<String> filter(JSONArray cards, String words, long now) {
        List<String> out = new ArrayList<>();
        if (cards == null) return out;
        String w = words == null ? "" : words.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]", " ").replaceAll("\\s+", " ").trim();
        long since = 0, until = Long.MAX_VALUE;
        long day = 86_400_000L;
        if (w.matches(".*(ఈరోజు|today).*")) since = startOfDay(now);
        else if (w.matches(".*(నిన్న|yesterday).*")) { since = startOfDay(now) - day; until = startOfDay(now); }
        else if (w.matches(".*(గత వారం|ఈ వారం|వారం|week).*")) since = now - 7 * day;
        else if (w.matches(".*(గత నెల|ఈ నెల|నెల|month).*")) since = now - 31 * day;
        boolean faults = w.matches(".*(fault|faults|సమస్య|సమస్యలు|పాడ|తప్పు|problem|broken|లోపం).*");
        String kind = w.matches(".*(ఫోటో|photo|బొమ్మ).*") ? "photo" : w.matches(".*(స్కాన్|scan).*") ? "scan" : w.matches(".*(నోట్|note).*") ? "note"
                : w.matches(".*(pdf|పిడిఎఫ్|పీడీఎఫ్|కాగితం).*") ? "pdf" : w.matches(".*(ఖర్చు|బిల్లు|expense|bill).*") ? "expense" : "";
        String rest = w.replaceAll("ఈరోజు|today|నిన్న|yesterday|గత వారం|ఈ వారం|వారం|week|గత నెల|ఈ నెల|నెల|month|fault|faults|సమస్యలు|సమస్య|పాడైనవి|పాడ|తప్పు|problem|broken|లోపం|ఉన్నవి|చూపించు|చూపు|show|మాత్రమే|only|ఫోటోలు|ఫోటో|photo|బొమ్మ|స్కాన్లు|స్కాన్|scan|నోట్స్|నోట్|note|pdf|ఖర్చులు|ఖర్చు|బిల్లు|expense|bill", " ")
                .replaceAll("\\s+", " ").trim();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject o = cards.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("t");
            if (t < since || t >= until) continue;
            if (!kind.isEmpty() && !kind.equals(o.optString("kind"))) continue;
            String text = (o.optString("title") + " " + o.optString("brief")).toLowerCase(Locale.ROOT);
            if (faults && !(o.optBoolean("fault") || text.matches(".*(సమస్య|పాడ|పగుల|తుప్పు|లీక|కాల|విరిగ|fault|broken|crack|leak|rust|burn|damage|తప్పు|లోపం).*"))) continue;
            if (!rest.isEmpty()) {
                boolean hit = false;
                for (String word : rest.split(" ")) if (word.length() >= 2 && text.contains(word)) { hit = true; break; }
                if (!hit) continue;
            }
            out.add(o.optString("id"));
        }
        return out;
    }

    static long startOfDay(long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** The cards, numbered, as text for the AI (titles and briefs only; never pictures, never ID numbers). */
    static String outline(JSONArray cards) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject o = cards.optJSONObject(i);
            if (o == null || o.optBoolean("hidden")) continue;
            b.append(i + 1).append(". [").append(o.optString("kind")).append("] ").append(o.optString("title"));
            String br = o.optString("brief");
            if (!br.isEmpty()) b.append(" — ").append(br.length() > 220 ? br.substring(0, 220) + "…" : br);
            b.append(" (").append(new java.text.SimpleDateFormat("d MMM", Locale.ENGLISH).format(new java.util.Date(o.optLong("t")))).append(")\n");
        }
        return b.toString();
    }

    /** The AI's reply read: {"links":[[a,b,"why"]...], "summary":"…", "next":["…"]} with a and b 1-based card numbers. */
    static JSONObject parseLinks(String reply, int cardCount) {
        JSONObject out = new JSONObject();
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            JSONObject o = a >= 0 && z > a ? new JSONObject(s.substring(a, z + 1)) : new JSONObject();
            JSONArray links = new JSONArray(), in = o.optJSONArray("links");
            for (int i = 0; in != null && i < in.length() && links.length() < 24; i++) {
                JSONArray l = in.optJSONArray(i);
                if (l == null || l.length() < 2) continue;
                int x = l.optInt(0) - 1, y = l.optInt(1) - 1;
                if (x < 0 || y < 0 || x >= cardCount || y >= cardCount || x == y) continue;
                links.put(new JSONArray().put(x).put(y).put(l.optString(2, "")));
            }
            out.put("links", links);
            out.put("summary", o.optString("summary", s.startsWith("{") ? "" : s));
            JSONArray nx = o.optJSONArray("next");
            out.put("next", nx == null ? new JSONArray() : nx);
        } catch (Exception ignored) {}
        return out;
    }
}
