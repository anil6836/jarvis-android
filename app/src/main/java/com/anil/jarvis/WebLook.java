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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * Things looked up on the web with the AI he chose (one small request each): this week's new Telugu films and OTT
 * releases (also every Friday evening by itself), where a product is cheapest online, and a daily fact with an English word.
 */
final class WebLook {
    private WebLook() {}

    private static JSONObject json(String ans) throws Exception {
        String j = ans == null ? "" : ans.trim();
        int a = j.indexOf('{'), b = j.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("no answer");
        return new JSONObject(j.substring(a, b + 1));
    }

    // ---------------------------------------------------------------- films

    /** This week's (or next week's) Telugu releases in theatres and on OTT. */
    static JSONObject movies(Prefs p, boolean nextWeek) throws Exception {
        LocalDate from = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (nextWeek) from = from.plusWeeks(1);
        LocalDate to = from.plusDays(6);
        String ans = Brain.oneShot(p, "You look up Telugu film releases on the web. Reply with ONLY compact JSON, no code fences.",
                "New Telugu films and Telugu series releasing between " + from + " and " + to + " (India): in theatres, and on OTT "
                        + "(Aha, Prime Video, Netflix, JioHotstar, ZEE5, SonyLIV, ETV Win...). Include Telugu-dubbed big films if notable. "
                        + "JSON: {\"theatres\":[{\"title\":\"\",\"date\":\"YYYY-MM-DD\",\"cast\":\"lead actors\",\"note\":\"genre / buzz in a few words\"}],"
                        + "\"ott\":[{\"title\":\"\",\"platform\":\"\",\"date\":\"YYYY-MM-DD\",\"type\":\"film or series\"}]}. Leave out anything you are unsure of.", null, true);
        JSONObject o = json(ans);
        return o.put("ok", true).put("from", from.toString()).put("to", to.toString())
                .put("next", "Say it in short Telugu: theatres first (title, date, lead actor), then OTT (title, platform). Offer to open the OTT app (open_app).");
    }

    // ---------------------------------------------------------------- cheapest online

    static JSONObject compare(Prefs p, String product) throws Exception {
        String ans = Brain.oneShot(p, "You compare online prices in India on the web. Reply with ONLY compact JSON, no code fences.",
                "Current prices in India for: " + product + ". Check Amazon.in, Flipkart, Meesho, JioMart, Croma, Reliance Digital, Tata CLiQ, BigBasket "
                        + "(where the product is sold). Same product and variant. JSON: {\"product\":\"exact model / variant\",\"offers\":[{\"store\":\"\",\"price\":number,"
                        + "\"note\":\"bank offer / delivery / out of stock, short\"}],\"cheapest\":\"store\"}. Rupees. Leave out what you can't find; don't guess.", null, true);
        JSONObject o = json(ans);
        return o.put("ok", true).put("next", "Say the cheapest first with the price, then 2-3 others, in short Telugu. Prices change: 'ఇప్పటి ధర'. "
                + "Offer to open it in that store's app (app_search); he buys and pays himself.");
    }

    // ---------------------------------------------------------------- a fact and a word every morning

    static JSONObject fact(Context c, Prefs p) throws Exception {
        SharedPreferences s = c.getSharedPreferences("jarvis_facts", Context.MODE_PRIVATE);
        String past = s.getString("past", "");
        String ans = Brain.oneShot(p, "You are a friendly Telugu teacher. Reply with ONLY compact JSON, no code fences.",
                "Give one surprising, true and useful fact for an adult in Telangana (science, health, history, India, space, nature, technology; vary the topic), "
                        + "in 2-3 simple Telugu sentences, and one useful English word with its Telugu meaning and a short English example sentence. "
                        + "Not these topics / words again: " + past + ". JSON: {\"topic\":\"2-3 English words\",\"fact\":\"Telugu\",\"word\":\"\",\"meaning\":\"Telugu\",\"example\":\"\"}", null, false);
        JSONObject o = json(ans);
        String add = o.optString("topic") + " / " + o.optString("word");
        String keep = (add + "; " + past);
        if (keep.length() > 900) keep = keep.substring(0, 900);
        s.edit().putString("past", keep).apply();
        return o.put("ok", true);
    }

    static String factText(JSONObject o) {
        return "💡 " + o.optString("fact") + "\n\n🔤 ఈరోజు ఇంగ్లీష్ పదం: " + o.optString("word") + " = " + o.optString("meaning")
                + (o.optString("example").isEmpty() ? "" : "\n\"" + o.optString("example") + "\"");
    }

    // ---------------------------------------------------------------- by themselves (from Proactive)

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_weblook", Context.MODE_PRIVATE); }

    static void tick(Context c, Prefs p, boolean quiet) {
        if (p.apiKey().isEmpty()) return;
        LocalDate today = LocalDate.now();
        int h = LocalTime.now().getHour();
        SharedPreferences s = st(c);
        // Friday evening: the week's films
        if (p.moviesWeekly() && today.getDayOfWeek() == DayOfWeek.FRIDAY && h >= 18 && h < 21 && tryOnce(s, "movies", today)) {
            try {
                JSONObject m = movies(p, false);
                StringBuilder t = new StringBuilder();
                JSONArray th = m.optJSONArray("theatres"), ott = m.optJSONArray("ott");
                for (int i = 0; th != null && i < th.length() && i < 6; i++) t.append(t.length() == 0 ? "" : "\n").append("🎬 ").append(th.optJSONObject(i).optString("title"))
                        .append(th.optJSONObject(i).optString("cast").isEmpty() ? "" : " (" + th.optJSONObject(i).optString("cast") + ")");
                for (int i = 0; ott != null && i < ott.length() && i < 6; i++) t.append(t.length() == 0 ? "" : "\n").append("📺 ").append(ott.optJSONObject(i).optString("title"))
                        .append(" · ").append(ott.optJSONObject(i).optString("platform"));
                if (t.length() > 0) {
                    done(s, "movies", today);
                    notify(c, 189, "🎬 ఈ వారం కొత్త తెలుగు సినిమాలు", t.toString(), "ఈ వారం కొత్త తెలుగు సినిమాలు, OTT రిలీజ్‌లు చెప్పు (new_movies).");
                    if (!quiet) Announcer.say(c, p.name() + ", ఈ వారం కొత్త తెలుగు సినిమాలు, OTT రిలీజ్‌లు వచ్చాయి. వివరాలు నోటిఫికేషన్‌లో ఉన్నాయి, కావాలంటే చదివి చెప్తాను.");
                }
            } catch (Exception ignored) {}
        }
        // every morning: a fact and a word
        if (p.dailyFact() && h >= p.factHour() && h < p.factHour() + 3 && tryOnce(s, "fact", today)) {
            try {
                JSONObject f = fact(c, p);
                done(s, "fact", today);
                String text = factText(f);
                notify(c, 190, "💡 ఈరోజు ఒక కొత్త విషయం", text, "ఈరోజు ఒక కొత్త విషయం, ఇంగ్లీష్ పదం చెప్పు (daily_fact).");
                if (!quiet) Announcer.say(c, p.name() + ", ఈరోజు ఒక కొత్త విషయం. " + f.optString("fact") + " ఈరోజు ఇంగ్లీష్ పదం: "
                        + f.optString("word") + ", అంటే " + f.optString("meaning") + ".");
            } catch (Exception ignored) {}
        }
    }

    /** At most 3 tries a day for each (a failed look-up is tried again at the next check). */
    private static boolean tryOnce(SharedPreferences s, String what, LocalDate day) {
        if (day.toString().equals(s.getString(what + "_done", ""))) return false;
        int tries = day.toString().equals(s.getString(what + "_day", "")) ? s.getInt(what + "_tries", 0) : 0;
        if (tries >= 3) return false;
        s.edit().putString(what + "_day", day.toString()).putInt(what + "_tries", tries + 1).apply();
        return true;
    }

    private static void done(SharedPreferences s, String what, LocalDate day) { s.edit().putString(what + "_done", day.toString()).apply(); }

    private static void notify(Context c, int id, String title, String text, String ask) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_daily", "సినిమాలు, రోజూ ఒక విషయం", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_ASK, ask)
                            .putExtra(MainActivity.EXTRA_LABEL, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(id, new Notification.Builder(c, "jarvis_daily").setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
