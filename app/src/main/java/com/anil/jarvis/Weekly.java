package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The weekly report (last 7 days): money spent against last week, bills by category, steps, phone time,
 * missions done, bike km and charging cost, and the month's API cost. Put together on the phone (no AI
 * cost); every Sunday evening a notification shows the headline and tapping it lets Jarvis tell it.
 */
final class Weekly {
    private Weekly() {}

    private static final long DAY = 86400000L;

    static JSONObject report(Context c) throws Exception {
        long since = Life.dayStart() - 6 * DAY, before = since - 7 * DAY;
        SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        JSONObject o = new JSONObject().put("ok", true).put("week", f.format(new Date(since)) + " – " + f.format(new Date()));

        // money: bank / UPI SMS plus the bills he added
        JSONObject money = new JSONObject();
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                JSONObject w = Tools.spendingSince(c, since, 0), two = Tools.spendingSince(c, before, 0);
                long spent = w.optLong("total_spent");
                money.put("spent_this_week", spent).put("received_this_week", w.optLong("total_received"))
                        .put("spent_last_week", Math.max(0, two.optLong("total_spent") - spent));
            } catch (Exception e) {
                money.put("sms", "could not read bank SMS");
            }
        } else {
            money.put("sms", "no SMS permission: only the bills he added are counted");
        }
        money.put("bills_he_added", Math.round(Money.totalSince(c, since))).put("bills_by_category", Money.byCategory(c, since));
        o.put("money", money);

        // health
        try {
            JSONObject steps = Health.week(c);
            o.put("steps", steps != null ? steps : new JSONObject().put("off", "step counting (Physical activity permission) is not allowed"));
        } catch (Exception ignored) {}

        // phone time
        if (Life.usageAllowed(c)) {
            try {
                JSONObject st = Life.screenTime(c, 7);
                JSONArray top = st.optJSONArray("top_apps"), top3 = new JSONArray();
                for (int i = 0; top != null && i < Math.min(3, top.length()); i++) top3.put(top.get(i));
                long total = st.optLong("total_minutes");
                o.put("screen_time", new JSONObject().put("total_hours", Math.round(total / 6.0) / 10.0)
                        .put("per_day_hours", Math.round(total / 7 / 6.0) / 10.0).put("top_apps", top3));
            } catch (Exception ignored) {}
        }

        // work done
        Store store = Store.get(c);
        JSONArray done = new JSONArray();
        int active = 0;
        for (JSONObject m : store.missions()) {
            if (m.optBoolean("done") && m.optLong("doneAt") >= since) done.put(m.optString("text"));
            if (!m.optBoolean("done")) active++;
        }
        int reminders = 0;
        for (JSONObject r : store.reminders()) if (r.optBoolean("done") && r.optLong("at") >= since) reminders++;
        o.put("missions_done", done).put("missions_still_open", active).put("reminders_done", reminders);

        // bike and AI cost
        try { o.put("bike", Bike.summary(c, since)); } catch (Exception ignored) {}
        try { o.put("api_cost_this_month", Usage.summary()); } catch (Exception ignored) {}
        return o.put("how_to_tell", "A friendly weekly check-in in 5-7 short spoken sentences: money first (compare with last week), "
                + "then steps, phone time, missions done, bike km and charging cost; end with one small, kind tip for next week. "
                + "Skip parts with no data.");
    }

    /** The headline for the notification, e.g. "ఖర్చు ₹4,250 (గత వారం ₹3,900) · 🏍️ 86 కి.మీ · 👣 38,400 అడుగులు". */
    static String headline(JSONObject r) {
        StringBuilder b = new StringBuilder();
        JSONObject m = r.optJSONObject("money");
        if (m != null) {
            long spent = m.has("spent_this_week") ? m.optLong("spent_this_week") : m.optLong("bills_he_added");
            b.append("ఖర్చు ₹").append(String.format(Locale.ENGLISH, "%,d", spent));
            if (m.has("spent_last_week")) b.append(" (గత వారం ₹").append(String.format(Locale.ENGLISH, "%,d", m.optLong("spent_last_week"))).append(")");
        }
        JSONObject bike = r.optJSONObject("bike");
        if (bike != null && bike.optDouble("km") > 0) b.append(" · 🏍️ ").append(bike.optDouble("km")).append(" కి.మీ");
        JSONObject steps = r.optJSONObject("steps");
        if (steps != null && steps.optInt("total") > 0) b.append(" · 👣 ").append(String.format(Locale.ENGLISH, "%,d", steps.optInt("total"))).append(" అడుగులు");
        JSONObject st = r.optJSONObject("screen_time");
        if (st != null) b.append(" · 📱 రోజుకు ").append(st.optDouble("per_day_hours")).append(" గం.");
        JSONArray done = r.optJSONArray("missions_done");
        if (done != null && done.length() > 0) b.append(" · 🎯 ").append(done.length()).append(" మిషన్లు పూర్తి");
        return b.toString();
    }

    /** Sunday 8-11 pm, once a week: the report as a notification (from Proactive's regular check). */
    static void maybeNotify(Context c, Prefs p) {
        if (!p.weeklyReport()) return;
        Calendar k = Calendar.getInstance();
        int hour = k.get(Calendar.HOUR_OF_DAY);
        if (k.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || hour < 20 || hour >= 23) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_weekly", Context.MODE_PRIVATE);
        String week = k.get(Calendar.YEAR) + "-" + k.get(Calendar.WEEK_OF_YEAR);
        if (week.equals(s.getString("sent", ""))) return;
        s.edit().putString("sent", week).apply();
        String line;
        try { line = headline(report(c)); } catch (Exception e) { line = ""; }
        if (line.isEmpty()) line = "ఈ వారం ఎలా గడిచిందో వినడానికి నొక్కండి";
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_weekly", "Jarvis వారపు రిపోర్ట్", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 79, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "ఈ వారం రిపోర్ట్ చెప్పు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(79, new Notification.Builder(c, "jarvis_weekly")
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("📊 ఈ వారం Jarvis రిపోర్ట్")
                    .setContentText(line)
                    .setStyle(new Notification.BigTextStyle().bigText(line + "\n\nపూర్తిగా వినడానికి నొక్కండి."))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {}
    }
}
