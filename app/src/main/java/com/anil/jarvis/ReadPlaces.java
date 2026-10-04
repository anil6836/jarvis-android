package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where he stopped reading a page aloud (the words of the part being read), so the next time he asks to read the same
 * page Jarvis can offer to go on from there. The newest 30, for 30 days.
 */
final class ReadPlaces {
    private ReadPlaces() {}

    private static final long KEEP = 30L * 86_400_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_read_places", Context.MODE_PRIVATE); }

    private static synchronized List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (now - o.optLong("t") < KEEP) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void write(Context c, List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        sp(c).edit().putString("list", a.toString()).apply();
    }

    /** The snippet that finds the place again: the start of the part (enough words to be unique on the page). */
    static String snippet(String part) {
        String s = part == null ? "" : part.replaceAll("\\s+", " ").trim();
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    /** He stopped (or paused) in the middle of a page: remember where. */
    static synchronized void save(Context c, String pkg, String title, String part) {
        String snip = snippet(part);
        if (snip.length() < 12) return; // too short to find it again
        // never kept: a bank / payment app's page, or words holding a number or a code (an account, policy or ID number)
        if (Tools.isMoneyApp(c, pkg) || snip.matches("(?s).*\\d(?:[\\s-]?\\d){5,}.*") || snip.matches("(?s).*\\b[A-Z]{5}\\d{4}[A-Z]\\b.*")) return;
        try {
            List<JSONObject> l = list(c);
            l.removeIf(o -> o.optString("pkg").equals(pkg) && o.optString("snip").equals(snip));
            l.add(new JSONObject().put("pkg", pkg).put("title", title).put("snip", snip).put("t", System.currentTimeMillis()));
            while (l.size() > 30) l.remove(0);
            write(c, l);
        } catch (Exception ignored) {}
    }

    /** A place he stopped at on this page (its words are on it, past the very start): the snippet, else null. */
    static synchronized String find(Context c, String pkg, String page) {
        if (page == null) return null;
        String flat = page.replaceAll("\\s+", " ");
        List<JSONObject> l = list(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            JSONObject o = l.get(i);
            if (!o.optString("pkg").equals(pkg)) continue;
            int at = flat.indexOf(o.optString("snip"));
            if (at > 80) return o.optString("snip");
        }
        return null;
    }

    /** These places (kept while one reading went on, also of the parts that came in by scrolling) are forgotten. */
    static synchronized void forgetAll(Context c, String pkg, java.util.Collection<String> parts) {
        if (parts == null || parts.isEmpty()) return;
        java.util.Set<String> snips = new java.util.HashSet<>();
        for (String p : parts) snips.add(snippet(p));
        List<JSONObject> l = list(c);
        if (l.removeIf(o -> o.optString("pkg").equals(pkg) && snips.contains(o.optString("snip")))) write(c, l);
    }

    /** Read to the end (or he chose to start over): this page's place is forgotten. */
    static synchronized void forget(Context c, String pkg, String page) {
        if (page == null) return;
        String flat = page.replaceAll("\\s+", " ");
        List<JSONObject> l = list(c);
        if (l.removeIf(o -> o.optString("pkg").equals(pkg) && flat.contains(o.optString("snip")))) write(c, l);
    }
}
