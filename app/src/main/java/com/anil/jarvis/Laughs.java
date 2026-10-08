package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * S17: when Anil laughs right after Jarvis said something (a joke, a story, a line), the start of what Jarvis said is kept
 * (the last 15, on the phone), and his brain is told the kind of thing he enjoys, so it brings more of that when a joke fits.
 * His laugh itself is never recorded.
 */
final class Laughs {
    private Laughs() {}

    private static final int KEEP = 15;
    /** A laugh this soon after Jarvis spoke is a laugh at what Jarvis said. */
    static final long WITHIN_MS = 60_000;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_laughs", Context.MODE_PRIVATE); }

    /** He laughed now: what did Jarvis just say? */
    static void heard(Context c) {
        try {
            List<JSONObject> chat = Store.get(c).chat();
            for (int i = chat.size() - 1; i >= 0 && i >= chat.size() - 4; i--) {
                JSONObject m = chat.get(i);
                if (!"assistant".equals(m.optString("role"))) continue;
                if (System.currentTimeMillis() - m.optLong("t") > WITHIN_MS) return;
                keep(c, m.optString("content"), System.currentTimeMillis());
                return;
            }
        } catch (Exception ignored) {}
    }

    /** Kept: the first line of what Jarvis said (once; the newest last). */
    static synchronized void keep(Context c, String said, long now) {
        String line = first(said);
        if (line.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!line.equals(a.getJSONObject(i).optString("what"))) out.put(a.getJSONObject(i));
            out.put(new JSONObject().put("what", line).put("t", now));
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, out.length() - KEEP); i < out.length(); i++) trimmed.put(out.get(i));
            sp(c).edit().putString("list", trimmed.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The first sentence, at most 120 characters. */
    static String first(String said) {
        if (said == null) return "";
        String t = said.replaceAll("\\s+", " ").trim();
        int cut = -1;
        for (String end : new String[]{". ", "! ", "? ", "। "}) {
            int k = t.indexOf(end);
            if (k > 15 && (cut < 0 || k < cut)) cut = k + 1;
        }
        if (cut > 0) t = t.substring(0, cut).trim();
        return t.length() > 120 ? t.substring(0, 117).trim() + "…" : t;
    }

    static int count(Context c) {
        try { return new JSONArray(sp(c).getString("list", "[]")).length(); } catch (Exception e) { return 0; }
    }

    /** For the brain's prompt: what made him laugh lately (empty when nothing). */
    static String line(Context c) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            if (a.length() == 0) return "";
            StringBuilder b = new StringBuilder("- He laughed lately when you said these (when a joke, story or light line fits, more of this kind; "
                    + "never say you noticed): ");
            for (int i = Math.max(0, a.length() - 8); i < a.length(); i++) b.append(i > Math.max(0, a.length() - 8) ? " | " : "").append('"').append(a.getJSONObject(i).optString("what")).append('"');
            return b.append('\n').toString();
        } catch (Exception e) {
            return "";
        }
    }
}
