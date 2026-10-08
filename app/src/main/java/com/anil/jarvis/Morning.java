package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * W23 / O42: the morning in about 30 seconds, made on the phone (no AI, so it is quick and works without internet):
 * good morning, the weather (when there is internet), today's duty with the time to leave and the bag, reminders,
 * medicines, birthdays, bills / last dates today or tomorrow, and last night's sleep. Said on the watch when he stops
 * Jarvis's alarm there in the morning, on "గుడ్ మార్నింగ్" / "ఈరోజు ఏమున్నాయి" without internet, and from the watch's
 * "☀️ ఈరోజు" button. Network: call it off the main thread.
 */
final class Morning {
    private Morning() {}

    static String text(Context c) {
        Context app = c.getApplicationContext();
        StringBuilder s = new StringBuilder(AlarmActivity.greeting(app)).append(' ');
        LocalDate today = LocalDate.now();
        try {
            Duty.Roster r = Duty.load(app);
            if (Duty.ready(r) && Duty.startsDuty(r, today)) {
                String bag = Duty.checklist(app);
                if (!bag.isEmpty()) s.append("బ్యాగ్‌లో: ").append(bag).append(". ");
            }
        } catch (Exception ignored) {}
        // today's calendar
        try {
            if (app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                long from = System.currentTimeMillis(), to = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
                android.content.ContentUris.appendId(b, from);
                android.content.ContentUris.appendId(b, to);
                StringBuilder ev = new StringBuilder();
                int n = 0;
                try (android.database.Cursor cur = app.getContentResolver().query(b.build(), new String[]{android.provider.CalendarContract.Instances.TITLE,
                        android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.ALL_DAY}, null, null,
                        android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
                    while (cur != null && cur.moveToNext() && n < 3) {
                        ev.append(n == 0 ? "" : ", ").append(cur.getInt(2) == 1 ? "" : time(cur.getLong(1)) + " ").append(cur.getString(0));
                        n++;
                    }
                }
                if (n > 0) s.append("క్యాలెండర్‌లో: ").append(ev).append(". ");
            }
        } catch (Exception ignored) {}
        // reminders today
        try {
            long now = System.currentTimeMillis(), end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            List<JSONObject> rs = Store.get(app).reminders();
            rs.sort((x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
            int n = 0;
            StringBuilder b = new StringBuilder();
            for (JSONObject r : rs) {
                long at = r.optLong("at");
                if (r.optBoolean("done") || at < now || at >= end) continue;
                if (n < 3) b.append(n == 0 ? "" : ", ").append(time(at)).append(" ").append(r.optString("text"));
                n++;
            }
            if (n > 0) s.append("ఈరోజు రిమైండర్లు: ").append(b).append(n > 3 ? " (ఇంకా " + (n - 3) + ")" : "").append(". ");
        } catch (Exception ignored) {}
        // medicines
        try {
            JSONArray ms = Medicine.listJson(app).optJSONArray("medicines");
            if (ms != null && ms.length() > 0) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < ms.length() && i < 3; i++) {
                    JSONObject m = ms.getJSONObject(i);
                    JSONArray t = m.optJSONArray("times");
                    b.append(i == 0 ? "" : ", ").append(m.optString("name")).append(t == null || t.length() == 0 ? "" : " " + t.join(", ").replace("\"", ""));
                }
                s.append("మందులు: ").append(b).append(". ");
            }
        } catch (Exception ignored) {}
        // birthdays / anniversaries today or tomorrow
        try {
            for (JSONObject b : Birthdays.upcoming(app, 1)) s.append(Birthdays.label(b)).append(" ").append(Birthdays.when(b)).append(". ");
        } catch (Exception ignored) {}
        // last dates
        try {
            for (JSONObject e : Expiry.soon(app, 1)) s.append(Expiry.line(app, e)).append(". ");
        } catch (Exception ignored) {}
        try {
            for (JSONObject d : Debts.open(app)) {
                LocalDate due = Debts.dueThisMonth(d, java.time.YearMonth.from(today));
                if (due != null && !Debts.paidThisMonth(d) && (due.equals(today) || due.equals(today.plusDays(1))))
                    s.append(Debts.line(d)).append(due.equals(today) ? " ఈరోజు" : " రేపు").append(". ");
            }
        } catch (Exception ignored) {}
        // last night's sleep
        try {
            long now = System.currentTimeMillis();
            long[] sl = Sleep.between(app, now - 14 * 3600_000L, now + 1);
            if (sl[0] > 0 && sl[1] >= 60) s.append("రాత్రి నిద్ర ").append(Status.hours(sl[1])).append(sl[1] < 300 ? ", తక్కువ. ఈరోజు జాగ్రత్తగా ఉండండి" : "").append(". ");
        } catch (Exception ignored) {}
        return s.toString().replaceAll("\\s+", " ").trim();
    }

    private static String time(long at) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault());
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return h + ":" + String.format(java.util.Locale.ENGLISH, "%02d", t.getMinute());
    }

    /** It is morning now (4:00 – 11:59). */
    static boolean morning() {
        int h = LocalDateTime.now().getHour();
        return h >= 4 && h < 12;
    }

    /** "గుడ్ మార్నింగ్", "ఈరోజు ఏమున్నాయి", "ఉదయం సమాచారం", "బ్రీఫింగ్". */
    static boolean asks(String t) {
        String x = t == null ? "" : t.trim().toLowerCase(java.util.Locale.ROOT);
        x = x.replaceAll("^(జార్విస్|jarvis)\\s*,?\\s*", "").replaceAll("[.!?]+$", "").trim();
        return x.matches("(ఈరోజు|ఈ\\s*రోజు|ఇవాళ)\\s*(ఏమున్నాయి|ఏం\\s*ఉన్నాయి|ఏం\\s*ఉంది|సంగతులు|సంగతులు\\s*చెప్పు|ప్లాన్|ప్లాన్\\s*ఏంటి|ఏమేమి\\s*ఉన్నాయి)|"
                + "ఉదయం\\s*సమాచారం|మార్నింగ్\\s*బ్రీఫింగ్|బ్రీఫింగ్|briefing|morning\\s*briefing");
    }
}
