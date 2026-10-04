package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages he put off with "తర్వాత" on the top card (or that came and went while he was busy in another app): a count
 * dot on the floating button until he has them read ("📬" in its menu). Kept on the phone for 12 hours.
 */
final class LaterMessages {
    private LaterMessages() {}

    interface Listener { void changed(int count); }

    static volatile Listener listener;
    private static final long KEEP = 12 * 3600_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_later_msgs", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> list(Context c) {
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
        Listener li = listener;
        if (li != null) li.changed(l.size());
    }

    /** A one-time code: never kept (it is in the SMS app if he needs it). */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile(
            "(?is).*(otp|one[ -]?time|verification|passcode|security code|code\\s*(is|:)|ఓటీపీ).*\\b\\d{4,8}\\b.*"
                    + "|(?is).*\\b\\d{4,8}\\b.*(otp|one[ -]?time|verification|passcode|ఓటీపీ).*");

    /** One chat's messages, put off for later (the same chat again: its messages are added to it). */
    static synchronized void add(Context c, String app, String from, List<String> texts, int id) {
        List<String> keep = new ArrayList<>();
        for (String t : texts) if (t != null && !CODE.matcher(t).matches()) keep.add(t);
        if (keep.isEmpty()) return;
        texts = keep;
        try {
            List<JSONObject> l = list(c);
            JSONObject hit = null;
            for (JSONObject o : l) if (o.optString("app").equals(app) && o.optString("from").equals(from)) hit = o;
            if (hit == null) {
                hit = new JSONObject().put("app", app).put("from", from).put("texts", new JSONArray());
                l.add(hit);
            }
            JSONArray t = hit.getJSONArray("texts");
            for (String s : texts) t.put(s);
            while (t.length() > 15) t.remove(0);
            hit.put("id", id).put("t", System.currentTimeMillis());
            while (l.size() > 20) l.remove(0);
            write(c, l);
        } catch (Exception ignored) {}
    }

    static synchronized void clear(Context c) { write(c, new ArrayList<>()); }

    static int count(Context c) { return list(c).size(); }

    /** All of them, to read aloud: who, where, and each message. */
    static String spoken(Context c) {
        StringBuilder b = new StringBuilder();
        for (JSONObject o : list(c)) {
            JSONArray t = o.optJSONArray("texts");
            int n = t == null ? 0 : t.length();
            b.append(o.optString("from")).append(" నుంచి ").append(o.optString("app")).append(" లో ")
                    .append(n == 1 ? "మెసేజ్" : n + " మెసేజ్‌లు").append(":\n");
            for (int i = 0; i < n; i++) b.append("• ").append(t.optString(i)).append('\n');
            b.append('\n');
        }
        return b.toString().trim();
    }
}
