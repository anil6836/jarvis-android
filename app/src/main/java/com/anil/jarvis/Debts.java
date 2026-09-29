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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Money between Anil and others, and what he pays every month:
 *   lent      he gave someone money (they owe him)
 *   borrowed  he took money from someone (he owes them)
 *   emi       a loan EMI on a day of the month
 *   chit      a chit fund (చిట్టీ) instalment on a day of the month
 * Reminders the evening before and on the morning of each EMI / chit day (until he says he paid),
 * and on the day someone promised to give money back. Everything stays on the phone.
 */
final class Debts {
    private Debts() {}

    static final String KEY = "debts";

    // ---------------------------------------------------------------- storage

    static List<JSONObject> all(Context c) { return Notes.list(c, KEY); }

    static List<JSONObject> open(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(c)) if (!o.optBoolean("closed")) out.add(o);
        return out;
    }

    private static void put(Context c, JSONObject item) {
        List<JSONObject> l = all(c);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).optString("id").equals(item.optString("id"))) { l.set(i, item); found = true; }
        if (!found) l.add(item);
        // keep closed ones for a while (history), drop the oldest closed first when it gets long
        while (l.size() > 300) {
            int drop = -1;
            for (int i = 0; i < l.size(); i++) if (l.get(i).optBoolean("closed")) { drop = i; break; }
            l.remove(drop < 0 ? 0 : drop);
        }
        Notes.save(c, KEY, l, 100000);
    }

    static String kind(String k) {
        String s = k == null ? "" : k.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("lent") || s.startsWith("gave") || s.startsWith("ఇచ్చా") || s.contains("రావాల")) return "lent";
        if (s.startsWith("borrow") || s.startsWith("took") || s.startsWith("తీసుక") || s.contains("ఇవ్వాల")) return "borrowed";
        if (s.contains("chit") || s.contains("చిట్")) return "chit";
        if (s.contains("emi") || s.contains("loan") || s.contains("లోన్") || s.contains("ఈఎంఐ")) return "emi";
        return "";
    }

    static String kindTe(String k) {
        switch (k) {
            case "lent": return "మీరు ఇచ్చింది";
            case "borrowed": return "మీరు తీసుకున్నది";
            case "chit": return "చిట్టీ";
            case "emi": return "EMI";
            default: return k;
        }
    }

    /**
     * New entry. lent/borrowed: amount is the whole sum, due = the date it should come back (optional).
     * emi/chit: amount is the monthly instalment, day = day of the month, months = how many in all (0 = not known),
     * paidMonths = how many are already paid.
     */
    static JSONObject add(Context c, String kind, String name, double amount, String date, String due, int day, int months, int paidMonths, String note) throws Exception {
        String k = kind(kind);
        if (k.isEmpty() || name == null || name.trim().isEmpty() || amount <= 0) return null;
        JSONObject o = new JSONObject().put("id", Notes.id("d")).put("kind", k).put("name", name.trim()).put("amount", amount)
                .put("t", System.currentTimeMillis()).put("note", note == null ? "" : note.trim());
        LocalDate given = parse(date);
        o.put("date", (given == null ? LocalDate.now() : given).toString());
        if (k.equals("lent") || k.equals("borrowed")) {
            o.put("paid", 0);
            LocalDate d = parse(due);
            if (d != null) o.put("due", d.toString());
        } else {
            if (day < 1 || day > 31) return null;
            o.put("day", day).put("months", Math.max(0, months)).put("done_months", Math.max(0, paidMonths));
        }
        put(c, o);
        return o;
    }

    /** The open entry he means: by id, or the name (and kind if given); the newest wins. */
    static JSONObject find(Context c, String nameOrId, String kind) {
        if (nameOrId == null || nameOrId.trim().isEmpty()) return null;
        String q = nameOrId.trim().toLowerCase(Locale.ROOT), k = kind(kind);
        JSONObject best = null;
        for (JSONObject o : open(c)) {
            if (o.optString("id").equalsIgnoreCase(q)) return o;
            if (!k.isEmpty() && !k.equals(o.optString("kind"))) continue;
            String n = o.optString("name").toLowerCase(Locale.ROOT);
            if (n.equals(q) || n.contains(q) || q.contains(n)) best = o;
        }
        return best;
    }

    /**
     * He paid, or got money back. lent/borrowed: amount (0 = all of it). emi/chit: one instalment (this month).
     * Returns the updated entry, or null if not found.
     */
    static JSONObject pay(Context c, String nameOrId, String kind, double amount) throws Exception {
        JSONObject o = find(c, nameOrId, kind);
        if (o == null) return null;
        String k = o.optString("kind");
        if (k.equals("lent") || k.equals("borrowed")) {
            double total = o.optDouble("amount"), paid = o.optDouble("paid", 0);
            paid = amount <= 0 ? total : Math.min(total, paid + amount);
            o.put("paid", paid);
            if (paid >= total - 0.5) o.put("closed", true).put("closed_on", LocalDate.now().toString());
        } else {
            if (System.currentTimeMillis() - o.optLong("paid_t") < 10 * 60000L) return o; // the same "కట్టాను" twice
            YearMonth ym = nextUnpaid(o, LocalDate.now());
            o.put("done_months", o.optInt("done_months") + 1).put("last_paid", ym.toString()).put("paid_t", System.currentTimeMillis());
            int months = o.optInt("months");
            if (months > 0 && o.optInt("done_months") >= months) o.put("closed", true).put("closed_on", LocalDate.now().toString());
        }
        put(c, o);
        return o;
    }

    static boolean remove(Context c, String nameOrId, String kind) {
        JSONObject o = find(c, nameOrId, kind);
        return o != null && Notes.remove(c, KEY, "id", o.optString("id"));
    }

    // ---------------------------------------------------------------- reading out

    static double left(JSONObject o) {
        String k = o.optString("kind");
        if (k.equals("lent") || k.equals("borrowed")) return Math.max(0, o.optDouble("amount") - o.optDouble("paid", 0));
        int months = o.optInt("months");
        return months > 0 ? Math.max(0, months - o.optInt("done_months")) * o.optDouble("amount") : 0;
    }

    /** This month's instalment date for an EMI / chit. */
    static LocalDate dueThisMonth(JSONObject o, YearMonth ym) {
        int d = Math.min(Math.max(1, o.optInt("day", 1)), ym.lengthOfMonth());
        return ym.atDay(d);
    }

    /** Is the instalment that falls in this month paid? */
    static boolean paidFor(JSONObject o, YearMonth ym) {
        try { return !o.optString("last_paid").isEmpty() && !YearMonth.parse(o.optString("last_paid")).isBefore(ym); } catch (Exception e) { return false; }
    }

    static boolean paidThisMonth(JSONObject o) { return paidFor(o, YearMonth.now()); }

    /**
     * The instalment a "కట్టాను" is for: the one after the last paid; when nothing is recorded yet, the one whose date is
     * nearest today (due on the 30th, paid on the 1st = last month's; due on the 5th, paid on the 29th = next month's).
     */
    static YearMonth nextUnpaid(JSONObject o, LocalDate today) {
        try {
            if (!o.optString("last_paid").isEmpty()) return YearMonth.parse(o.optString("last_paid")).plusMonths(1);
        } catch (Exception ignored) {}
        YearMonth best = YearMonth.from(today);
        long bestGap = Long.MAX_VALUE;
        for (int k = -1; k <= 1; k++) {
            YearMonth m = YearMonth.from(today).plusMonths(k);
            long gap = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, dueThisMonth(o, m)));
            if (gap < bestGap) { bestGap = gap; best = m; }
        }
        return best;
    }

    static String money(double v) { return "₹" + String.format(Locale.ENGLISH, "%,d", Math.round(v)); }

    /** One line in Telugu for a list. */
    static String line(JSONObject o) {
        String k = o.optString("kind"), n = o.optString("name");
        if (k.equals("lent")) {
            String s = n + " మీకు " + money(left(o)) + " ఇవ్వాలి";
            if (o.optDouble("paid", 0) > 0) s += " (" + money(o.optDouble("amount")) + " లో " + money(o.optDouble("paid")) + " ఇచ్చారు)";
            if (!o.optString("due").isEmpty()) s += " · " + Duty.day(LocalDate.parse(o.optString("due"))) + " కి ఇస్తానన్నారు";
            return s;
        }
        if (k.equals("borrowed")) {
            String s = "మీరు " + n + " కి " + money(left(o)) + " ఇవ్వాలి";
            if (o.optDouble("paid", 0) > 0) s += " (" + money(o.optDouble("amount")) + " లో " + money(o.optDouble("paid")) + " ఇచ్చేశారు)";
            if (!o.optString("due").isEmpty()) s += " · " + Duty.day(LocalDate.parse(o.optString("due"))) + " లోపు";
            return s;
        }
        String s = (k.equals("chit") ? "చిట్టీ " : "EMI ") + n + ": నెలకు " + money(o.optDouble("amount")) + ", ప్రతి నెల " + o.optInt("day") + " న";
        int months = o.optInt("months");
        if (months > 0) s += " · " + o.optInt("done_months") + "/" + months + " కట్టారు";
        s += paidThisMonth(o) ? " · ఈ నెల కట్టారు ✓" : " · ఈ నెల ఇంకా కట్టలేదు";
        return s;
    }

    static JSONObject summary(Context c, String kindFilter) throws Exception {
        String kf = kind(kindFilter);
        double owedToHim = 0, heOwes = 0, monthly = 0;
        int nLent = 0, nBorrowed = 0;
        JSONArray items = new JSONArray();
        for (JSONObject o : open(c)) {
            String k = o.optString("kind");
            if (k.equals("lent")) { owedToHim += left(o); nLent++; }
            else if (k.equals("borrowed")) { heOwes += left(o); nBorrowed++; }
            else monthly += o.optDouble("amount");
            if (!kf.isEmpty() && !kf.equals(k)) continue;
            items.put(new JSONObject().put("id", o.optString("id")).put("kind", k).put("name", o.optString("name"))
                    .put("line", line(o)).put("left", Math.round(left(o))));
        }
        return new JSONObject().put("ok", true).put("items", items)
                .put("others_owe_him", money(owedToHim) + " (" + nLent + ")")
                .put("he_owes_others", money(heOwes) + " (" + nBorrowed + ")")
                .put("emi_and_chits_per_month", money(monthly));
    }

    // ---------------------------------------------------------------- reminders (from Proactive, every ~15 min)

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_debts", Context.MODE_PRIVATE); }

    private static boolean once(Context c, String key) {
        SharedPreferences s = st(c);
        if (s.contains(key)) return false;
        SharedPreferences.Editor e = s.edit().putLong(key, System.currentTimeMillis());
        if (s.getAll().size() > 400) e.clear().putLong(key, System.currentTimeMillis());
        e.apply();
        return true;
    }

    static void tick(Context c, Prefs p, boolean quiet) {
        List<JSONObject> list = open(c);
        if (list.isEmpty()) return;
        LocalDate today = LocalDate.now();
        int h = java.time.LocalTime.now().getHour();
        StringBuilder say = new StringBuilder();
        for (JSONObject o : list) {
            String k = o.optString("kind"), n = o.optString("name"), id = o.optString("id");
            if (k.equals("emi") || k.equals("chit")) {
                YearMonth ym = YearMonth.from(today);
                LocalDate due = dueThisMonth(o, ym);
                String what = (k.equals("chit") ? "చిట్టీ " : "EMI ") + n + " " + money(o.optDouble("amount"));
                // tomorrow's instalment may be next month's (due on the 1st, today is the 30th)
                YearMonth tm = YearMonth.from(today.plusDays(1));
                if (dueThisMonth(o, tm).equals(today.plusDays(1)) && !paidFor(o, tm)
                        && h >= 19 && h < 22 && once(c, "eve|" + id + "|" + today)) {
                    notify(c, id.hashCode() + 1, "💳 రేపు " + what + " కట్టాలి", "కట్టాక \"" + n + " కట్టాను\" అని చెప్పండి, రాసుకుంటాను.");
                    say.append("రేపు ").append(what).append(" కట్టాలి. ");
                }
                // two days late and still not marked paid (this month's, or last month's when it fell on the 30th / 31st)
                for (YearMonth m : new YearMonth[]{ym, ym.minusMonths(1)}) {
                    LocalDate d = dueThisMonth(o, m);
                    if (today.equals(d.plusDays(2)) && !paidFor(o, m) && h >= 9 && h < 12 && once(c, "late|" + id + "|" + today)) {
                        notify(c, id.hashCode() + 3, "💳 " + what + " కట్టారా?", Duty.day(d) + " న కట్టాల్సింది. కట్టి ఉంటే \"" + n + " కట్టాను\" అనండి.");
                    }
                }
                if (paidThisMonth(o)) continue;
                if (due.equals(today) && h >= 8 && h < 12 && once(c, "day|" + id + "|" + today)) {
                    notify(c, id.hashCode() + 2, "💳 ఈరోజు " + what + " కట్టే రోజు", "కట్టాక \"" + n + " కట్టాను\" అని చెప్పండి.");
                    say.append("ఈరోజు ").append(what).append(" కట్టే రోజు. ");
                }
            } else if (!o.optString("due").isEmpty()) {
                LocalDate due = parse(o.optString("due"));
                if (due == null) continue;
                String text = k.equals("lent") ? n + " మీకు " + money(left(o)) + " తిరిగి ఇవ్వాల్సిన రోజు"
                        : "మీరు " + n + " కి " + money(left(o)) + " ఇవ్వాల్సిన రోజు";
                if (due.equals(today.plusDays(1)) && k.equals("borrowed") && h >= 19 && h < 22 && once(c, "eve|" + id + "|" + today)) {
                    notify(c, id.hashCode() + 1, "🤝 రేపు " + text, "ఇచ్చాక \"" + n + " కి ఇచ్చేశాను\" అని చెప్పండి.");
                    say.append("రేపు ").append(text).append(". ");
                }
                if (due.equals(today) && h >= 9 && h < 12 && once(c, "day|" + id + "|" + today)) {
                    notify(c, id.hashCode() + 2, "🤝 ఈరోజు " + text, k.equals("lent") ? "ఇచ్చాక \"" + n + " ఇచ్చాడు\" అనండి. గుర్తుచేస్తూ WhatsApp మెసేజ్ కావాలంటే అడగండి."
                            : "ఇచ్చాక \"" + n + " కి ఇచ్చేశాను\" అనండి.");
                    say.append("ఈరోజు ").append(text).append(". ");
                }
            }
        }
        if (say.length() > 0 && !quiet) Announcer.say(c, p.name() + ", " + say.toString().trim());
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_money", "EMI, అప్పులు, గడువులు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "నా అప్పులు, EMI లు, చిట్టీలు చెప్పు (debts list).")
                            .putExtra(MainActivity.EXTRA_LABEL, "🤝 అప్పులు, EMI").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("debts", id, new Notification.Builder(c, "jarvis_money").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- dates

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d-M-yyyy");

    /** YYYY-MM-DD, D-M-YYYY / D/M/YYYY, or MM-DD (this year, or next year if already past). */
    static LocalDate parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String t = s.trim().replace('/', '-').replace('.', '-');
        try { return LocalDate.parse(t); } catch (Exception ignored) {}
        try { return LocalDate.parse(t, DMY); } catch (Exception ignored) {}
        try {
            String[] md = t.split("-");
            if (md.length == 2) {
                LocalDate d = LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(md[0]), Integer.parseInt(md[1]));
                return d.isBefore(LocalDate.now()) ? d.plusYears(1) : d;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
