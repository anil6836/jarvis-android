package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The shopping list: "పాలు, గుడ్లు చేర్చు", tick off what he bought, send it to someone on WhatsApp. */
final class Shopping {
    private Shopping() {}

    static final String LIST = "shopping";

    static List<JSONObject> items(Context c) { return Notes.list(c, LIST); }

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    /** Adds items ("పాలు 2 ప్యాకెట్లు, గుడ్లు, బ్రెడ్"); one already there comes back to "to buy". Returns what was added. */
    static synchronized JSONArray add(Context c, String text) throws Exception {
        JSONArray added = new JSONArray();
        if (text == null) return added;
        List<JSONObject> l = items(c);
        for (String raw : text.split("\\s*(?:,|،|\\n|;| మరియు | and | & )\\s*")) {
            String item = raw.trim().replaceAll("^[-•*·]+\\s*", "");
            if (item.isEmpty()) continue;
            JSONObject same = null;
            for (JSONObject o : l) if (norm(o.optString("item")).equals(norm(item))) same = o;
            if (same != null) { same.put("done", false); continue; }
            l.add(new JSONObject().put("id", Notes.id("s") + l.size()).put("item", item).put("done", false).put("t", System.currentTimeMillis()));
            added.put(item);
        }
        Notes.save(c, LIST, l, 300);
        return added;
    }

    /** The item he means: the same words, or one inside the other. */
    private static JSONObject find(List<JSONObject> l, String what) {
        String w = norm(what);
        if (w.isEmpty()) return null;
        for (JSONObject o : l) if (norm(o.optString("item")).equals(w)) return o;
        for (JSONObject o : l) {
            String n = norm(o.optString("item"));
            if (n.contains(w) || w.contains(n)) return o;
        }
        return null;
    }

    /** Ticks off (bought = true) or back to "to buy"; returns the items it changed. */
    static synchronized JSONArray mark(Context c, String text, boolean bought) throws Exception {
        JSONArray changed = new JSONArray();
        List<JSONObject> l = items(c);
        for (String what : (text == null ? "" : text).split("\\s*(?:,| మరియు | and | & )\\s*")) {
            JSONObject o = find(l, what);
            if (o == null) continue;
            o.put("done", bought);
            changed.put(o.optString("item"));
        }
        Notes.save(c, LIST, l, 300);
        return changed;
    }

    static synchronized JSONArray remove(Context c, String text) {
        JSONArray gone = new JSONArray();
        List<JSONObject> l = items(c);
        for (String what : (text == null ? "" : text).split("\\s*(?:,| మరియు | and | & )\\s*")) {
            JSONObject o = find(l, what);
            if (o == null) continue;
            l.remove(o);
            gone.put(o.optString("item"));
        }
        Notes.save(c, LIST, l, 300);
        return gone;
    }

    /** Clears the bought ones (all = everything). Returns how many went. */
    static synchronized int clear(Context c, boolean all) {
        List<JSONObject> l = items(c), keep = new ArrayList<>();
        for (JSONObject o : l) if (!all && !o.optBoolean("done")) keep.add(o);
        Notes.save(c, LIST, keep, 300);
        return l.size() - keep.size();
    }

    static synchronized void toggle(Context c, String id) {
        List<JSONObject> l = items(c);
        for (JSONObject o : l) {
            if (!o.optString("id").equals(id)) continue;
            try { o.put("done", !o.optBoolean("done")); } catch (Exception ignored) {}
        }
        Notes.save(c, LIST, l, 300);
    }

    static synchronized void removeId(Context c, String id) { Notes.remove(c, LIST, "id", id); }

    /** The list to send: what is still to buy. */
    static String shareText(Context c) {
        StringBuilder b = new StringBuilder("🛒 షాపింగ్ లిస్ట్:");
        int n = 0;
        for (JSONObject o : items(c)) {
            if (o.optBoolean("done")) continue;
            b.append("\n• ").append(o.optString("item"));
            n++;
        }
        return n == 0 ? "" : b.toString();
    }

    static JSONObject listJson(Context c) throws Exception {
        JSONArray toBuy = new JSONArray(), bought = new JSONArray();
        for (JSONObject o : items(c)) (o.optBoolean("done") ? bought : toBuy).put(o.optString("item"));
        return new JSONObject().put("ok", true).put("to_buy", toBuy).put("bought", bought);
    }
}
