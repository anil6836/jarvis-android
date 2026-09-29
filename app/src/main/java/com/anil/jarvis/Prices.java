package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Calendar;
import java.util.Locale;

/**
 * Today's prices: gold and silver in his city, crops at the market yard (mirchi, cotton, paddy...), petrol.
 * Looked up on the web by the AI he chose in Settings (one small request), answered as numbers.
 * Optionally every morning at 10 (Settings / by voice). "Tell me when gold falls to X" stays with price_alert.
 */
final class Prices {
    private Prices() {}

    private static final String[] STATES = {"తెలంగాణ", "ఆంధ్రప్రదేశ్", "ఆంధ్ర", "telangana", "andhra"};

    /** The market he most likely means for crops: his first local-news place that isn't a state. */
    static String homeMarket(Prefs p) {
        for (String s : LocalNews.places(p)) {
            boolean state = false;
            for (String x : STATES) if (s.toLowerCase(Locale.ROOT).contains(x)) state = true;
            if (!state) return s.replace("జిల్లా", "").trim();
        }
        return "";
    }

    private static boolean crop(String what) {
        String w = what.toLowerCase(Locale.ROOT);
        for (String k : new String[]{"మిర్చి", "మిరప", "పత్తి", "వడ్లు", "ధాన్యం", "వరి", "మొక్కజొన్న", "కంది", "పెసర", "పసుపు", "వేరుశనగ",
                "chilli", "chili", "mirchi", "cotton", "paddy", "maize", "turmeric", "groundnut", "quintal", "క్వింటా"})
            if (w.contains(k)) return true;
        return false;
    }

    /** {ok, place, items:[{name, price, unit, change, date, source}]} from a web look-up. */
    static JSONObject lookup(Prefs p, String what, String place) throws Exception {
        String w = what == null || what.trim().isEmpty() ? "బంగారం 22 క్యారెట్, 24 క్యారెట్ (10 గ్రాములు), వెండి (1 కిలో)" : what.trim();
        String pl = place == null ? "" : place.trim();
        if (pl.isEmpty()) pl = crop(w) && !homeMarket(p).isEmpty() ? homeMarket(p) : p.priceCity();
        String today = LocalDate.now().toString();
        String system = "You look up today's prices in India on the web. Reply with ONLY compact JSON, no words around it, no code fences.";
        String prompt = "Date: " + today + ". Place: " + pl + ". Prices wanted: " + w + ".\n"
                + "Gold and silver: retail rate in that city today (22K and 24K per 10 grams, silver per kg). "
                + "Crops (mirchi/chilli varieties like Teja, cotton, paddy...): the latest modal price at that place's agricultural market yard "
                + "(or the nearest big market yard in Telangana/Andhra Pradesh if it has none), per quintal, with min-max if known. Fuel: price per litre in that city.\n"
                + "JSON: {\"items\":[{\"name\":\"in Telugu, e.g. బంగారం 22 క్యారెట్\",\"price\":number,\"range\":\"min-max or empty\",\"unit\":\"10 గ్రాములు / కిలో / క్వింటా / లీటర్\","
                + "\"change\":\"vs yesterday, e.g. +250 or -100 or empty\",\"date\":\"YYYY-MM-DD of the price\",\"market\":\"city or market yard used\",\"source\":\"website\"}]}. "
                + "If a price cannot be found, leave it out rather than guess.";
        String ans = Brain.oneShot(p, system, prompt, null, true);
        String j = ans == null ? "" : ans.trim();
        int a = j.indexOf('{'), b = j.lastIndexOf('}');
        JSONObject out = new JSONObject().put("ok", true).put("place", pl).put("asked", w);
        try {
            JSONArray items = new JSONObject(j.substring(a, b + 1)).optJSONArray("items");
            out.put("items", items == null ? new JSONArray() : items);
            if (items == null || items.length() == 0) out.put("note", "The web search found no price for this. Say so honestly.");
        } catch (Exception e) {
            out.put("items", new JSONArray()).put("raw", j.length() > 800 ? j.substring(0, 800) : j);
        }
        return out.put("next", "Say each in short Telugu: name, ₹ price per unit, change vs yesterday, and the date if it is not today. "
                + "Retail gold differs a little from shop to shop. For 'tell me when it falls to X' use price_alert.");
    }

    /** "బంగారం 22 క్యారెట్: ₹71,500 / 10 గ్రాములు (+250)" lines. */
    static String lines(JSONObject r) {
        StringBuilder s = new StringBuilder();
        JSONArray items = r.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null || o.optDouble("price", 0) <= 0) continue;
            if (s.length() > 0) s.append("\n");
            s.append(o.optString("name")).append(": ").append(Debts.money(o.optDouble("price"))).append(" / ").append(o.optString("unit"));
            String ch = o.optString("change").trim();
            if (!ch.isEmpty() && !ch.equals("0")) s.append(" (").append(ch).append(")");
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- every morning, if he asked for it

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_prices", Context.MODE_PRIVATE); }

    static void tick(Context c, Prefs p, boolean quiet) {
        String want = p.dailyPrices();
        if (want.isEmpty() || p.apiKey().isEmpty()) return;
        Calendar k = Calendar.getInstance();
        int h = k.get(Calendar.HOUR_OF_DAY);
        if (h < 10 || h >= 12) return;
        String day = LocalDate.now().toString();
        SharedPreferences s = st(c);
        if (day.equals(s.getString("day", ""))) return;
        int tries = day.equals(s.getString("try_day", "")) ? s.getInt("tries", 0) : 0;
        if (tries >= 3) return; // no luck this morning; tomorrow again
        s.edit().putString("try_day", day).putInt("tries", tries + 1).apply();
        try {
            JSONObject r = lookup(p, want, "");
            String text = lines(r);
            if (text.isEmpty()) return;
            s.edit().putString("day", day).apply();
            notify(c, "💰 ఈరోజు ధరలు · " + r.optString("place"), text);
            if (!quiet) Announcer.say(c, p.name() + ", ఈరోజు ధరలు. " + text.replace("\n", ". ").replace("₹", "") + " రూపాయలు.");
        } catch (Exception ignored) {}
    }

    private static void notify(Context c, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_prices", "ధరలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 84, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "ఈరోజు బంగారం, వెండి ధరలు చెప్పు (market_prices).")
                            .putExtra(MainActivity.EXTRA_LABEL, "💰 ఈరోజు ధరలు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(84, new Notification.Builder(c, "jarvis_prices").setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

}
