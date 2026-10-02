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

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Locale;

/**
 * The month on one page, as a PDF on the phone (no AI cost): money (bank / UPI SMS and the bills he added), duty days,
 * bike km with the saving against petrol, sleep, mobile data, reminders and missions done, prayers answered and the
 * Bible plan, and the last dates coming next month. On the 1st, last month's report is made by itself (a notification).
 */
final class Monthly {
    private Monthly() {}

    private static final String[] MONTHS = {"", "జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    static String name(YearMonth ym) { return MONTHS[ym.getMonthValue()] + " " + ym.getYear(); }

    private static long ms(LocalDate d) { return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }

    private static String rs(long v) { return "₹" + String.format(Locale.ENGLISH, "%,d", v); }

    /** "this", "last", "2026-09" or "" (this month). */
    static YearMonth parse(String m) {
        String s = m == null ? "" : m.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("last") || s.contains("గత") || s.contains("పోయిన")) return YearMonth.now().minusMonths(1);
        try { if (s.matches("\\d{4}-\\d{1,2}")) return YearMonth.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5))); } catch (Exception ignored) {}
        return YearMonth.now();
    }

    /** The numbers for the month (to today, for this month). */
    static JSONObject data(Context c, YearMonth ym) throws Exception {
        long from = ms(ym.atDay(1)), now = System.currentTimeMillis();
        long to = Math.min(ms(ym.plusMonths(1).atDay(1)), now);
        boolean ended = to < now;
        JSONObject o = new JSONObject().put("ok", true).put("month", name(ym)).put("so_far", !ended);

        JSONObject money = new JSONObject();
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                JSONObject a = Tools.spendingSince(c, from, 0), b = ended ? Tools.spendingSince(c, to, 0) : null;
                // bank / UPI only: the bills he added are counted on their own below
                money.put("spent", Math.max(0, a.optLong("total_spent_sms") - (b == null ? 0 : b.optLong("total_spent_sms"))))
                        .put("received", Math.max(0, a.optLong("total_received") - (b == null ? 0 : b.optLong("total_received"))));
            } catch (Exception ignored) {}
        }
        long bills = Math.round(Money.totalSince(c, from) - (ended ? Money.totalSince(c, to) : 0));
        JSONObject cats = Money.byCategory(c, from), later = ended ? Money.byCategory(c, to) : new JSONObject(), byCat = new JSONObject();
        for (Iterator<String> it = cats.keys(); it.hasNext(); ) {
            String k = it.next();
            long v = cats.optLong(k) - later.optLong(k, 0);
            if (v > 0) byCat.put(k, v);
        }
        money.put("bills_he_added", bills).put("bills_by_category", byCat);
        o.put("money", money);

        try {
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r)) o.put("duty", Duty.report(c, r, ym));
        } catch (Exception ignored) {}

        try {
            JSONObject a = Bike.summary(c, from), b = ended ? Bike.summary(c, to) : null;
            double km = a.optDouble("km") - (b == null ? 0 : b.optDouble("km"));
            long cost = a.optLong("charging_cost_rupees") - (b == null ? 0 : b.optLong("charging_cost_rupees"));
            int rides = a.optInt("rides") - (b == null ? 0 : b.optInt("rides"));
            if (km > 0 || rides > 0) o.put("bike", new JSONObject().put("km", Math.round(km * 10) / 10.0).put("rides", rides).put("charging_cost", cost)
                    .put("vs_petrol", Bike.savings(c, km, cost)));
        } catch (Exception ignored) {}

        long[] sl = Sleep.between(c, from, to);
        if (sl[0] > 0) o.put("sleep", new JSONObject().put("sleeps", sl[0]).put("average_hours", Math.round(sl[1] / 6.0 / sl[0]) / 10.0));

        if (DataUse.supported() && Life.usageAllowed(c)) {
            long mob = DataUse.bytes(c, android.net.ConnectivityManager.TYPE_MOBILE, from, to);
            if (mob > 0) o.put("mobile_data_gb", Math.round(mob / 107374182.4) / 10.0);
        }

        int reminders = 0, missions = 0;
        Store store = Store.get(c);
        for (JSONObject r : store.reminders()) if (r.optBoolean("done") && r.optLong("at") >= from && r.optLong("at") < to) reminders++;
        for (JSONObject m : store.missions()) if (m.optBoolean("done") && m.optLong("doneAt") >= from && m.optLong("doneAt") < to) missions++;
        o.put("reminders_done", reminders).put("missions_done", missions);

        JSONArray answered = new JSONArray();
        for (JSONObject p : Notes.list(c, "prayers")) {
            String d = p.optString("answered");
            if (!d.isEmpty() && d.startsWith(ym.toString())) answered.put(p.optString("text"));
        }
        if (answered.length() > 0) o.put("prayers_answered", answered);
        if (Faith.planOn(c)) o.put("bible_plan", Faith.planDone(c) + "/" + Faith.DAYS);

        JSONArray next = new JSONArray();
        YearMonth nm = ym.plusMonths(1);
        for (JSONObject e : Expiry.all(c)) {
            LocalDate d = Debts.parse(e.optString("date"));
            if (d != null && YearMonth.from(d).equals(nm)) next.put(Expiry.line(c, e));
        }
        if (next.length() > 0) o.put("last_dates_next_month", next);
        return o;
    }

    /** The page's text (plain Telugu lines, no emoji: they don't print well). */
    static String text(JSONObject d) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(d.optBoolean("so_far") ? "(ఈరోజు వరకు)\n\n" : "\n");
        JSONObject m = d.optJSONObject("money");
        b.append("ఖర్చులు\n");
        if (m != null && m.has("spent")) b.append("  బ్యాంక్ / UPI నుంచి ఖర్చు: ").append(rs(m.optLong("spent"))).append("   వచ్చింది: ").append(rs(m.optLong("received"))).append('\n');
        if (m != null) {
            b.append("  మీరు చేర్చిన బిల్లులు: ").append(rs(m.optLong("bills_he_added"))).append('\n');
            JSONObject cat = m.optJSONObject("bills_by_category");
            if (cat != null) for (Iterator<String> it = cat.keys(); it.hasNext(); ) { String k = it.next(); b.append("    ").append(k).append(": ").append(rs(cat.optLong(k))).append('\n'); }
        }
        JSONObject duty = d.optJSONObject("duty");
        if (duty != null) {
            b.append("\nడ్యూటీ\n  ").append(duty.optInt("duty_days")).append(" రోజులు (").append(duty.optInt("hours")).append(" గంటలు)");
            if (duty.optInt("extra_days") > 0) b.append(", అదనపు రోజులు ").append(duty.optInt("extra_days"));
            if (duty.optInt("days_off_from_his_turn") > 0) b.append(", సెలవు ").append(duty.optInt("days_off_from_his_turn"));
            b.append('\n');
            JSONArray f = duty.optJSONArray("worked_on_festivals");
            if (f != null && f.length() > 0) b.append("  పండుగ రోజుల్లో డ్యూటీ: ").append(f.join(", ").replace("\"", "")).append('\n');
        }
        JSONObject bike = d.optJSONObject("bike");
        if (bike != null) {
            JSONObject v = bike.optJSONObject("vs_petrol");
            b.append("\nబైక్\n  ").append(bike.optDouble("km")).append(" కి.మీ, ").append(bike.optInt("rides")).append(" రైడ్స్, ఛార్జింగ్ ఖర్చు ")
                    .append(rs(v != null ? v.optLong("ev_cost_rupees") : bike.optLong("charging_cost"))).append(v != null && v.optBoolean("ev_cost_estimated") ? " (అంచనా)" : "").append('\n');
            if (v != null) b.append("  పెట్రోల్ బండి అయితే ").append(rs(v.optLong("petrol_bike_would_cost_rupees"))).append(" అయ్యేది; ఆదా ").append(rs(v.optLong("saved_rupees"))).append('\n');
        }
        JSONObject sl = d.optJSONObject("sleep");
        if (sl != null) b.append("\nనిద్ర\n  సగటు ").append(sl.optDouble("average_hours")).append(" గంటలు (").append(sl.optInt("sleeps")).append(" సార్లు)\n");
        if (d.has("mobile_data_gb")) b.append("\nమొబైల్ డేటా\n  ").append(d.optDouble("mobile_data_gb")).append(" GB\n");
        b.append("\nపనులు\n  పూర్తయిన రిమైండర్లు: ").append(d.optInt("reminders_done")).append("   మిషన్లు: ").append(d.optInt("missions_done")).append('\n');
        JSONArray pr = d.optJSONArray("prayers_answered");
        if (pr != null || d.has("bible_plan")) {
            b.append("\nఆధ్యాత్మికం\n");
            if (pr != null) b.append("  జవాబు వచ్చిన ప్రార్థనలు: ").append(pr.join(", ").replace("\"", "")).append('\n');
            if (d.has("bible_plan")) b.append("  బైబిల్ ప్లాన్: ").append(d.optString("bible_plan")).append(" భాగాలు\n");
        }
        JSONArray nx = d.optJSONArray("last_dates_next_month");
        if (nx != null) {
            b.append("\nవచ్చే నెల గడువులు\n");
            for (int i = 0; i < nx.length(); i++) b.append("  ").append(nx.optString(i)).append('\n');
        }
        b.append("\n\nJarvis తయారు చేసింది · ఫోన్‌లోని వివరాల నుంచి (బ్యాంక్ SMS, మీరు చెప్పినవి).");
        return b.toString();
    }

    static Coder.Made pdf(Context c, YearMonth ym) throws Exception {
        return Cards.letter(c, "నెల రిపోర్ట్ · " + name(ym), text(data(c, ym)), "Jarvis/reports");
    }

    /** On the 1st (10 am - 1 pm), once: last month's report, and a notification that opens it. */
    static void maybeMake(Context c) {
        LocalDate today = LocalDate.now();
        int h = java.time.LocalTime.now().getHour();
        if (today.getDayOfMonth() > 3 || h < 10 || h >= 13) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_monthly", Context.MODE_PRIVATE);
        YearMonth last = YearMonth.now().minusMonths(1);
        if (last.toString().equals(s.getString("made", ""))) return;
        if (System.currentTimeMillis() - s.getLong("failed_at", 0) < 3 * 3600000L) return; // tried a little while ago
        try {
            Coder.Made m = pdf(c, last);
            s.edit().putString("made", last.toString()).apply(); // made: not again this month
            if (m.uri == null) return;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_monthly", "నెల రిపోర్ట్", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 241, new Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(241, new Notification.Builder(c, "jarvis_monthly").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("📊 " + name(last) + " రిపోర్ట్ రెడీ").setContentText("ఖర్చులు, డ్యూటీ, బైక్, నిద్ర... చూడటానికి నొక్కండి (" + m.where + ")")
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception e) {
            s.edit().putLong("failed_at", System.currentTimeMillis()).apply(); // tried again in a few hours
        }
    }
}
