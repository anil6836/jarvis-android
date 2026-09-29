package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Expenses Anil adds himself (bills, cash), kept on the phone. */
final class Money {
    private Money() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("jarvis_money", Context.MODE_PRIVATE);
    }

    static synchronized List<JSONObject> expenses(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("expenses", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    static JSONObject add(Context c, double amount, String what) throws Exception {
        return add(c, amount, what, "", "", System.currentTimeMillis());
    }

    /** An expense with the shop, a category (food, groceries, fuel, ...) and when it was (the bill's date). */
    static synchronized JSONObject add(Context c, double amount, String what, String shop, String category, long when) throws Exception {
        JSONObject e = new JSONObject().put("t", when).put("amount", amount).put("what", what);
        if (shop != null && !shop.trim().isEmpty()) e.put("shop", shop.trim());
        if (category != null && !category.trim().isEmpty()) e.put("category", category.trim().toLowerCase(java.util.Locale.ROOT));
        JSONArray a = new JSONArray(sp(c).getString("expenses", "[]"));
        a.put(e);
        // keep about a year of entries
        while (a.length() > 1500) a.remove(0);
        sp(c).edit().putString("expenses", a.toString()).apply();
        return e;
    }

    /** Bills he added since then, totalled per category (biggest first). */
    static JSONObject byCategory(Context c, long since) throws Exception {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        for (JSONObject e : expenses(c)) {
            if (e.optLong("t") < since) continue;
            String k = e.optString("category", "");
            if (k.isEmpty()) k = "other";
            m.put(k, (m.containsKey(k) ? m.get(k) : 0) + e.optDouble("amount"));
        }
        List<java.util.Map.Entry<String, Double>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, Double> x : l) o.put(x.getKey(), Math.round(x.getValue()));
        return o;
    }

    static double totalSince(Context c, long since) {
        double t = 0;
        for (JSONObject e : expenses(c)) if (e.optLong("t") >= since) t += e.optDouble("amount");
        return t;
    }
}
