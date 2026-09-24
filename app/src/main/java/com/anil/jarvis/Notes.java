package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Small JSON lists kept on the phone: notes/diary, routines, habits and price alerts.
 * Each list lives in its own key of one preferences file.
 */
final class Notes {
    private Notes() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("jarvis_lists", Context.MODE_PRIVATE);
    }

    static synchronized List<JSONObject> list(Context c, String key) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    static synchronized void save(Context c, String key, List<JSONObject> list, int max) {
        while (list.size() > max) list.remove(0);
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        sp(c).edit().putString(key, a.toString()).apply();
    }

    static synchronized JSONObject add(Context c, String key, JSONObject o, int max) {
        List<JSONObject> l = list(c, key);
        l.add(o);
        save(c, key, l, max);
        return o;
    }

    static synchronized boolean remove(Context c, String key, String field, String value) {
        List<JSONObject> l = list(c, key);
        boolean found = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).optString(field).equalsIgnoreCase(value)) { l.remove(i); found = true; }
        }
        if (found) save(c, key, l, 100000);
        return found;
    }

    static String id(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis() % 1000000000L, 36);
    }
}
