package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * W49: the week's health on one picture, every Sunday evening (and on "ఈ వారం ఆరోగ్యం"): steps, sleep, resting heart
 * rate, stress and coughs for the last 7 days, each as bars with its number, plus a short line. Saved in
 * Downloads/Jarvis/health; the notification shows it. Made on the phone (no AI). Background thread.
 */
final class HealthWeek {
    private HealthWeek() {}

    static final String[] DAYS_TE = {"", "సో", "మం", "బు", "గు", "శు", "శ", "ఆ"};

    /** Per day, oldest first: {day labels, steps, sleep hours, resting heart rate, stress %, coughs} (-1 unknown). */
    static JSONObject data(Context c) throws Exception {
        LocalDate today = LocalDate.now();
        JSONArray days = new JSONArray(), steps = new JSONArray(), sleep = new JSONArray(), rest = new JSONArray(), stress = new JSONArray(), cough = new JSONArray();
        boolean hc = HealthData.connected(c);
        JSONArray sleeps = hc ? HealthData.sleeps(c, 8) : new JSONArray();
        List<JSONObject> hr = HeartLog.all(c);
        int[] base = HeartLog.baseline(hr, System.currentTimeMillis());
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long from = Wellness.dayStart(d), to = Wellness.dayStart(d.plusDays(1));
            days.put(DAYS_TE[d.getDayOfWeek().getValue()] + " " + d.getDayOfMonth());
            long st = hc ? HealthData.steps(c, from, Math.min(to, System.currentTimeMillis())) : -1;
            st = Math.max(st, WatchHealth.daySteps(c, d));
            steps.put(st);
            long sl = -1; // the night's sleep that ended that day (two apps writing the same night are not counted twice)
            List<JSONObject> ended = new java.util.ArrayList<>();
            for (int k = 0; k < sleeps.length(); k++) {
                JSONObject o = sleeps.optJSONObject(k);
                if (o != null && o.optLong("end") >= from && o.optLong("end") < to) ended.add(o);
            }
            JSONObject main = HealthData.mainSleep(ended);
            if (main != null) sl = main.optLong("minutes");
            if (sl < 0) {
                long[] ph = Sleep.between(c, from, to);
                if (ph[0] > 0) sl = ph[1];
            }
            sleep.put(sl < 0 ? -1 : Math.round(sl / 6.0) / 10.0);
            int r = hc ? HealthData.restingHr(c, from, to) : -1;
            if (r <= 0) r = HeartLog.low(hr, from, to);
            rest.put(r);
            stress.put(HeartLog.stressPct(hr, base, from, to));
            cough.put(CoughLog.count(c, d, "cough"));
        }
        long exSecs = 0, exM = 0;
        JSONArray ex = hc ? HealthData.exercises(c, Wellness.dayStart(today.minusDays(6)), System.currentTimeMillis() + 1) : new JSONArray();
        for (int i = 0; i < ex.length(); i++) {
            JSONObject x = ex.optJSONObject(i);
            exSecs += (x.optLong("end") - x.optLong("start")) / 1000;
            exM += Math.max(0, x.optLong("m"));
        }
        int breaths = 0;
        long weekFrom = Wellness.dayStart(today.minusDays(6));
        for (JSONObject b : Notes.list(c, Wellness.BREATHS)) if (b.optLong("t") >= weekFrom) breaths++;
        return new JSONObject().put("days", days).put("steps", steps).put("sleep", sleep).put("rest", rest).put("stress", stress).put("cough", cough)
                .put("breaths", breaths).put("ex_n", ex.length()).put("ex_min", exSecs / 60).put("ex_m", exM);
    }

    /** The week in one line: "👣 రోజుకు సగటు 4,200 అడుగులు · 🛌 6.6 గం · ❤️ 68 · 😮‍💨 ఒత్తిడి తక్కువ · 🤧 దగ్గు 12". */
    static String line(JSONObject d) {
        StringBuilder b = new StringBuilder();
        double st = avg(d.optJSONArray("steps")), sl = avg(d.optJSONArray("sleep")), r = avg(d.optJSONArray("rest")), s = avg(d.optJSONArray("stress"));
        if (st >= 0) b.append("👣 రోజుకు సగటు ").append(Sums.num(Math.round(st))).append(" అడుగులు");
        if (sl >= 0) b.append(b.length() > 0 ? " · " : "").append("🛌 నిద్ర సగటు ").append(String.format(Locale.ENGLISH, "%.1f", sl)).append(" గం");
        if (r >= 0) b.append(b.length() > 0 ? " · " : "").append("❤️ విశ్రాంతి ").append(Math.round(r));
        if (s >= 0) b.append(b.length() > 0 ? " · " : "").append("ఒత్తిడి ").append(HeartLog.stressWord((int) Math.round(s)));
        int coughs = 0;
        JSONArray cg = d.optJSONArray("cough");
        for (int i = 0; cg != null && i < cg.length(); i++) coughs += Math.max(0, cg.optInt(i));
        if (coughs > 0) b.append(b.length() > 0 ? " · " : "").append("🤧 దగ్గు ").append(coughs);
        if (d.optInt("ex_n") > 0) {
            b.append(b.length() > 0 ? " · " : "").append("🏃 వ్యాయామం ").append(d.optLong("ex_min")).append(" ని");
            long m = d.optLong("ex_m");
            if (m > 0) b.append(", ").append(String.format(Locale.ENGLISH, "%.1f", m / 1000.0)).append(" కి.మీ").append(d.optInt("ex_n") > 10 ? " (చివరి 10)" : "");
        }
        int breaths = d.optInt("breaths");
        if (breaths > 0) b.append(b.length() > 0 ? " · " : "").append("🌬️ శ్వాస వ్యాయామం ").append(breaths).append(" సార్లు");
        return b.toString();
    }

    private static boolean coughsThisWeek(Context c) {
        for (int i = 0; i < 7; i++) if (CoughLog.count(c, LocalDate.now().minusDays(i), "cough") > 0) return true;
        return false;
    }

    /** The average of the known values (-1 if none). */
    static double avg(JSONArray a) {
        double sum = 0;
        int n = 0;
        for (int i = 0; a != null && i < a.length(); i++) { double v = a.optDouble(i, -1); if (v >= 0) { sum += v; n++; } }
        return n == 0 ? -1 : sum / n;
    }

    // ---------------------------------------------------------------- the picture

    static Bitmap draw(JSONObject d) {
        int w = 1080, h = 1500;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.drawColor(0xFF05080F);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF74E4FF);
        p.setTextSize(54);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        JSONArray days = d.optJSONArray("days");
        cv.drawText("❤️ ఈ వారం ఆరోగ్యం", 60, 100, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(30);
        p.setColor(0xFF8FA9B5);
        if (days != null && days.length() > 0) cv.drawText(days.optString(0) + " – " + days.optString(days.length() - 1), 60, 150, p);
        Object[][] rows = {
                {"👣 అడుగులు", "steps", 0xFF74E4FF, "%,.0f"},
                {"🛌 నిద్ర (గంటలు)", "sleep", 0xFFA78BFA, "%.1f"},
                {"❤️ విశ్రాంతి గుండె వేగం", "rest", 0xFFFF6B8A, "%.0f"},
                {"😮‍💨 ఒత్తిడి (% సమయం)", "stress", 0xFFFBBF24, "%.0f"},
                {"🤧 దగ్గు", "cough", 0xFF34D399, "%.0f"},
        };
        float top = 200, rowH = 250;
        for (Object[] r : rows) {
            panel(cv, p, (String) r[0], d.optJSONArray((String) r[1]), days, (int) r[2], (String) r[3], new RectF(60, top, w - 60, top + rowH - 30));
            top += rowH;
        }
        p.setTextSize(26);
        p.setColor(0xFF5B7380);
        cv.drawText("Jarvis · Samsung Health (Health Connect), వాచ్, ఫోన్ మైక్ లెక్కలు · వైద్య పరీక్ష కాదు", 60, h - 40, p);
        return bm;
    }

    private static void panel(Canvas cv, Paint p, String title, JSONArray v, JSONArray days, int color, String fmt, RectF box) {
        p.setColor(0xFF0B2230);
        cv.drawRoundRect(box, 24, 24, p);
        p.setColor(0xFFDCEEF5);
        p.setTextSize(32);
        cv.drawText(title, box.left + 24, box.top + 46, p);
        double max = 0;
        for (int i = 0; v != null && i < v.length(); i++) max = Math.max(max, v.optDouble(i, -1));
        int n = v == null ? 0 : v.length();
        if (n == 0) return;
        float gap = 18, bw = (box.width() - 48 - gap * (n - 1)) / n, base = box.bottom - 44, room = box.height() - 130;
        for (int i = 0; i < n; i++) {
            double x = v.optDouble(i, -1);
            float left = box.left + 24 + i * (bw + gap);
            p.setColor(0xFF8FA9B5);
            p.setTextSize(24);
            String day = days == null ? "" : days.optString(i);
            cv.drawText(day, left + (bw - p.measureText(day)) / 2, box.bottom - 12, p);
            if (x < 0) { cv.drawText("–", left + bw / 2 - 6, base - 8, p); continue; }
            float bh = max <= 0 ? 0 : (float) (x / max * room);
            p.setColor(color);
            cv.drawRoundRect(new RectF(left, base - Math.max(4, bh), left + bw, base), 10, 10, p);
            String label = String.format(Locale.ENGLISH, fmt, x);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(24);
            cv.drawText(label, left + (bw - p.measureText(label)) / 2, base - Math.max(4, bh) - 8, p);
        }
    }

    /** The picture saved (Downloads/Jarvis/health) and its line. */
    static Object[] make(Context c) throws Exception {
        JSONObject d = data(c);
        Bitmap bm = draw(d);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        String name = "Health_week_" + LocalDate.now() + ".png";
        Coder.Made m = Coder.save(c, "Jarvis/health", name, "image/png", out.toByteArray());
        return new Object[]{m, line(d), bm, d};
    }

    /** Sunday 7–10 pm, once a week (his choice in Settings): the picture as a notification. From Proactive (background). */
    static void tick(Context c) {
        if (!WatchHub.healthWeekOn(c)) return;
        Calendar k = Calendar.getInstance();
        int hour = k.get(Calendar.HOUR_OF_DAY);
        if (k.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || hour < 19 || hour >= 22) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_wellness", Context.MODE_PRIVATE);
        String week = k.get(Calendar.YEAR) + "-" + k.get(Calendar.WEEK_OF_YEAR);
        if (week.equals(s.getString("week_sent", ""))) return;
        // only with something to show: the watch, Health Connect or the cough listening
        if (!HealthData.connected(c) && !WatchHub.known(c) && !coughsThisWeek(c)) return;
        s.edit().putString("week_sent", week).apply();
        try {
            Object[] r = make(c);
            Coder.Made m = (Coder.Made) r[0];
            String line = (String) r[1];
            Bitmap bm = (Bitmap) r[2];
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_weekly", "Jarvis వారపు రిపోర్ట్", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = new Notification.Builder(c, "jarvis_weekly").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("❤️ ఈ వారం ఆరోగ్యం").setContentText(line.isEmpty() ? "గ్రాఫ్ చూడటానికి నొక్కండి" : line)
                    .setStyle(new Notification.BigPictureStyle().bigPicture(Bitmap.createScaledBitmap(bm, 720, 1000, true)).setSummaryText(line))
                    .setAutoCancel(true);
            if (m.uri != null) {
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(c, 4608, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(4608, b.build());
        } catch (Exception ignored) {}
    }
}
