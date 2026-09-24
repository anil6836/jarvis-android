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

    static synchronized JSONObject add(Context c, double amount, String what) throws Exception {
        JSONObject e = new JSONObject().put("t", System.currentTimeMillis()).put("amount", amount).put("what", what);
        JSONArray a = new JSONArray(sp(c).getString("expenses", "[]"));
        a.put(e);
        // keep about a year of entries
        while (a.length() > 1500) a.remove(0);
        sp(c).edit().putString("expenses", a.toString()).apply();
        return e;
    }

    static double totalSince(Context c, long since) {
        double t = 0;
        for (JSONObject e : expenses(c)) if (e.optLong("t") >= since) t += e.optDouble("amount");
        return t;
    }
}
