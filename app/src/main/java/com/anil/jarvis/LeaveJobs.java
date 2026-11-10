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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🗓️ సెలవు రోజు పనులు (B5, 10 Nov): jobs that need an office's working hours (bank, post office, the MRO office, gas, the
 * electricity office…) said any time ("సెలవు రోజు బ్యాంక్‌లో పాస్‌బుక్ అప్‌డేట్ చేయాలి") wait for his next day fully at
 * home (from the duty roster: not on duty and not just back from one; without a roster, Sunday). That morning, at his
 * time, one list: the usual opening hours (said as "usually"), a warning when the place is shut that day (Sunday, a bank's
 * second / fourth Saturday), jobs at a place he saved in the order of the way from home. That evening: "అయ్యాయా?" — what
 * he says is done goes; the rest waits for the next leave day. Kept on the phone (his own backup carries it, like notes).
 */
final class LeaveJobs {
    private LeaveJobs() {}

    static final String SP = "jarvis_leave_jobs";
    static final int MAX = 40;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(SP, Context.MODE_PRIVATE); }

    static boolean on(Context c) { return sp(c).getBoolean("on", true); }
    static int morningMin(Context c) { return Math.max(0, Math.min(14 * 60, sp(c).getInt("morning_min", 9 * 60))); }
    static void setOn(Context c, boolean on) { sp(c).edit().putBoolean("on", on).apply(); }
    /** The morning list's time: up to 14:00 (the evening's "అయ్యాయా?" comes at 19:00, hours later). */
    static void setMorningMin(Context c, int m) { sp(c).edit().putInt("morning_min", Math.max(0, Math.min(14 * 60, m))).apply(); }

    // ================================================================ the list

    static synchronized List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void save(Context c, List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        sp(c).edit().putString("list", a.toString()).apply();
    }

    static List<JSONObject> pending(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(c)) if (!o.optBoolean("done")) out.add(o);
        return out;
    }

    /** A new job (the same words again: the old one, not a second). Null for an empty one or a full list. */
    static synchronized JSONObject add(Context c, String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        List<JSONObject> list = all(c);
        for (JSONObject o : list) if (!o.optBoolean("done") && o.optString("text").equalsIgnoreCase(t)) return o;
        list.removeIf(o -> o.optBoolean("done") && System.currentTimeMillis() - o.optLong("done_at") > 30L * 86400000L); // (done a month ago: gone)
        if (pendingCount(list) >= MAX) return null;
        JSONObject o = new JSONObject().put("id", Notes.id("j")).put("text", t).put("at", System.currentTimeMillis());
        list.add(o);
        save(c, list);
        return o;
    }

    private static int pendingCount(List<JSONObject> l) { int n = 0; for (JSONObject o : l) if (!o.optBoolean("done")) n++; return n; }

    /** What done / remove did: the jobs it acted on, or (his words fit more than one equally) the ones to ask him about. */
    static final class Picked {
        final List<String> acted = new ArrayList<>(), which = new ArrayList<>();
    }

    /**
     * The jobs named: by ids ("j1a2,j3b4"); else by his words, only the one job they fit best (a tie: none, the tied ones to ask
     * about — one shared word like "బ్యాంక్" or "card" never closes two jobs). Pure.
     */
    static List<Integer> pick(List<JSONObject> jobs, String said, List<Integer> tied) {
        List<Integer> out = new ArrayList<>();
        String s0 = said == null ? "" : said.trim();
        for (String id : s0.split("[,\\s]+")) for (int i = 0; i < jobs.size(); i++) if (!id.isEmpty() && id.equals(jobs.get(i).optString("id")) && !out.contains(i)) out.add(i);
        if (!out.isEmpty()) return out;
        int best = 0;
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            int sc = matchScore(jobs.get(i).optString("text"), s0);
            if (sc > best) { best = sc; top.clear(); top.add(i); }
            else if (sc == best && sc > 0) top.add(i);
        }
        if (top.size() == 1) out.add(top.get(0));
        else if (tied != null) tied.addAll(top);
        return out;
    }

    static synchronized Picked done(Context c, String idsOrWords) throws Exception {
        Picked r = new Picked();
        List<JSONObject> list = all(c), open = new ArrayList<>();
        for (JSONObject o : list) if (!o.optBoolean("done")) open.add(o);
        List<Integer> tied = new ArrayList<>();
        for (int i : pick(open, idsOrWords, tied)) { open.get(i).put("done", true).put("done_at", System.currentTimeMillis()); r.acted.add(open.get(i).optString("text")); }
        for (int i : tied) r.which.add(open.get(i).optString("id") + " " + open.get(i).optString("text"));
        if (!r.acted.isEmpty()) save(c, list);
        return r;
    }

    /** Takes a job away (not done: he doesn't need it any more) — ids, or the one job his words fit best. */
    static synchronized Picked remove(Context c, String idsOrWords) {
        Picked r = new Picked();
        List<JSONObject> list = all(c), open = new ArrayList<>();
        for (JSONObject o : list) if (!o.optBoolean("done")) open.add(o);
        List<Integer> tied = new ArrayList<>();
        List<JSONObject> gone = new ArrayList<>();
        for (int i : pick(open, idsOrWords, tied)) { gone.add(open.get(i)); r.acted.add(open.get(i).optString("text")); }
        for (int i : tied) r.which.add(open.get(i).optString("id") + " " + open.get(i).optString("text"));
        if (!gone.isEmpty()) { list.removeAll(gone); save(c, list); }
        return r;
    }

    // ================================================================ which day

    /** His next day fully at home from {@code from} (inclusive) on which this job's kind of place is open (the roster; else the weekend). */
    static LocalDate nextLeaveDay(Context c, LocalDate from, String kind) {
        Duty.Roster r = Duty.load(c);
        return nextLeaveDay(Duty.ready(r) ? r : null, from, kind);
    }

    /**
     * Pure: the next day fully at home (the roster's; a null roster: Saturday and Sunday) on which the kind of place is not shut
     * (a bank job never lands on a Sunday or a bank's 2nd / 4th Saturday). Null when none in two months.
     */
    static LocalDate nextLeaveDay(Duty.Roster r, LocalDate from, String kind) {
        for (int i = 0; i < 60; i++) {
            LocalDate d = from.plusDays(i);
            if (home(r, d) && shut(kind, d) == null) return d;
        }
        return null;
    }

    private static boolean home(Duty.Roster r, LocalDate d) {
        return r == null ? d.getDayOfWeek() == DayOfWeek.SUNDAY || d.getDayOfWeek() == DayOfWeek.SATURDAY : Duty.homeAllDay(r, d);
    }

    static boolean leaveDay(Context c, LocalDate d) {
        Duty.Roster r = Duty.load(c);
        return home(Duty.ready(r) ? r : null, d);
    }

    // ================================================================ the places' usual hours (pure)

    /** What kind of office a job needs (for the usual hours and the days it is shut), or null. Pure. */
    static String kind(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (has(t, "బ్యాంక్", "బ్యాంకు", "bank", "పాస్‌బుక్", "passbook", "sbi", "hdfc", "icici", "ఎస్బీఐ")) return "bank";
        if (has(t, "పోస్ట్", "post office", "పోస్టాఫీసు", "speed post", "స్పీడ్ పోస్ట్")) return "post";
        if (has(t, "గ్యాస్", "gas agency", "సిలిండర్", "cylinder")) return "gas";
        if (has(t, "rto", "ఆర్టీఓ", "లైసెన్స్", "licence", "license")) return "rto";
        if (has(t, "కరెంట్ ఆఫీస్", "విద్యుత్ ఆఫీస్", "electricity office", "ఎలక్ట్రిసిటీ ఆఫీస్", "ఈబీ ఆఫీస్")) return "power";
        if (has(t, "mro", "ఎమ్మార్వో", "తహసీల్దార్", "తహశీల్దార్", "మున్సిపల్", "municipal", "పంచాయతీ", "panchayat", "రిజిస్ట్రేషన్", "registration",
                "మీసేవ", "meeseva", "mee seva", "ఆఫీస్‌కి", "ఆఫీసుకి", "government office", "ప్రభుత్వ ఆఫీస్")) return "govt";
        return null;
    }

    private static boolean has(String t, String... w) { for (String x : w) if (t.contains(x)) return true; return false; }

    /** "సాధారణంగా 10:00–4:00" for the kind (said as usual — the place's own times may differ), or null. Pure. */
    static String hours(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "bank": return "సాధారణంగా 10:00–4:00";
            case "post": return "సాధారణంగా 9:00–5:00";
            case "gas": return "సాధారణంగా 9:30–6:00";
            case "rto": return "సాధారణంగా 10:30–5:00";
            case "power": case "govt": return "సాధారణంగా 10:30–5:00";
            default: return null;
        }
    }

    /** Which Saturday of its month this is (1..5), 0 when not a Saturday. Pure. */
    static int saturdayNo(LocalDate d) { return d.getDayOfWeek() == DayOfWeek.SATURDAY ? (d.getDayOfMonth() - 1) / 7 + 1 : 0; }

    /** "⚠️ ఈ రోజు ఆదివారం: బ్యాంకులు మూసి ఉంటాయి" when the kind of place is usually shut that day, else null. Pure. */
    static String shut(String kind, LocalDate d) {
        if (kind == null) return null;
        boolean sunday = d.getDayOfWeek() == DayOfWeek.SUNDAY;
        int sat = saturdayNo(d);
        switch (kind) {
            case "bank":
                if (sunday) return "⚠️ ఆదివారం: బ్యాంకులు మూసి ఉంటాయి";
                if (sat == 2 || sat == 4) return "⚠️ " + (sat == 2 ? "రెండో" : "నాలుగో") + " శనివారం: బ్యాంకులు మూసి ఉంటాయి";
                return null;
            case "post": return sunday ? "⚠️ ఆదివారం: పోస్టాఫీసు మూసి ఉంటుంది" : null;
            case "gas": return sunday ? "⚠️ ఆదివారం: గ్యాస్ ఏజెన్సీ మూసి ఉండొచ్చు" : null;
            case "rto": case "power": case "govt":
                if (sunday) return "⚠️ ఆదివారం: ఆఫీసులు మూసి ఉంటాయి";
                if (sat == 2) return "⚠️ రెండో శనివారం: ప్రభుత్వ ఆఫీసులు మూసి ఉంటాయి";
                return null;
            default: return null;
        }
    }

    // ================================================================ the way from home (pure)

    /**
     * The order to do them: the jobs at a known place (lat / lon) by the nearest next from home (each from the one before),
     * then the rest in the order he said them. pts.get(i) = {lat, lon} or null; home = {lat, lon} or null (then in his order).
     * Pure.
     */
    static List<Integer> order(List<double[]> pts, double[] home) {
        List<Integer> out = new ArrayList<>(), known = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) (pts.get(i) != null && home != null ? known : rest).add(i);
        double[] at = home;
        while (!known.isEmpty()) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int k = 0; k < known.size(); k++) {
                double[] p = pts.get(known.get(k));
                double d = km(at[0], at[1], p[0], p[1]);
                if (d < bd) { bd = d; best = k; }
            }
            int i = known.remove(best);
            out.add(i);
            at = pts.get(i);
        }
        out.addAll(rest);
        return out;
    }

    static double km(double la1, double lo1, double la2, double lo2) {
        double r = 6371, dla = Math.toRadians(la2 - la1), dlo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dla / 2) * Math.sin(dla / 2) + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** His words name this job at all (see {@link #matchScore}). Pure. */
    static boolean matches(String job, String said) { return matchScore(job, said) > 0; }

    /**
     * How well his words fit a job: the whole job in what he said (or his whole words in the job) 100; else the job's words of 3+
     * letters he said, each counted when one starts with the other ("బ్యాంక్" / "బ్యాంక్‌లో"). Pure.
     */
    static int matchScore(String job, String said) {
        String j = job == null ? "" : job.toLowerCase(Locale.ROOT).trim(), s = said == null ? "" : said.toLowerCase(Locale.ROOT).trim();
        if (j.isEmpty() || s.isEmpty()) return 0;
        if (s.codePointCount(0, s.length()) >= 3 && j.contains(s) || j.codePointCount(0, j.length()) >= 3 && s.contains(j)) return 100;
        String[] sw = s.split("[^\\p{L}\\p{M}\\p{N}]+");
        int n = 0;
        for (String w : j.split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.codePointCount(0, w.length()) < 3 || STOP.contains(w)) continue;
            for (String x : sw) {
                if (x.codePointCount(0, x.length()) < 3 || STOP.contains(x)) continue;
                if (x.startsWith(w) || w.startsWith(x)) { n++; break; }
            }
        }
        return n;
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "చేయాలి", "వెళ్ళాలి", "వెళ్లాలి", "కట్టాలి", "తీసుకోవాలి", "ఇవ్వాలి", "పని", "పనులు", "రోజు", "సెలవు", "అయింది", "అయ్యింది", "అయిపోయింది",
            "చేశాను", "వద్దు", "తీసేయి", "the", "and", "for", "with", "done"));

    /** The morning's lines, in the order to do them: "1. బ్యాంక్‌లో పాస్‌బుక్ (సాధారణంగా 10:00–4:00) ⚠️ …". Pure but for the places given. */
    static List<String> lines(List<JSONObject> jobs, List<double[]> pts, double[] home, LocalDate d) {
        List<String> out = new ArrayList<>();
        int n = 1;
        for (int i : order(pts, home)) {
            String t = jobs.get(i).optString("text"), k = kind(t), h = hours(k), sh = shut(k, d);
            out.add(n++ + ". " + t + (h == null ? "" : " (" + h + ")") + (sh == null ? "" : " " + sh));
        }
        return out;
    }

    // ================================================================ the day itself (Proactive's regular check)

    private static boolean once(Context c, String key) {
        SharedPreferences s = c.getSharedPreferences("jarvis_leave_told", Context.MODE_PRIVATE);
        if (s.getBoolean(key, false)) return false;
        s.edit().putBoolean(key, true).apply();
        return true;
    }

    private static boolean told(Context c, String key) { return c.getSharedPreferences("jarvis_leave_told", Context.MODE_PRIVATE).getBoolean(key, false); }

    /** The once-a-day marks older than a month go (once a day). */
    private static void pruneTold(Context c) {
        SharedPreferences s = c.getSharedPreferences("jarvis_leave_told", Context.MODE_PRIVATE);
        String today = LocalDate.now().toString();
        if (today.equals(s.getString("pruned", ""))) return;
        SharedPreferences.Editor e = s.edit().putString("pruned", today);
        LocalDate old = LocalDate.now().minusDays(30);
        for (String k : s.getAll().keySet()) {
            int bar = k.indexOf('|');
            if (bar < 0) continue;
            try { if (LocalDate.parse(k.substring(bar + 1)).isBefore(old)) e.remove(k); } catch (Exception ignored) {}
        }
        e.apply();
    }

    /** A saved place named in the job ("SBI బ్యాంక్" → his saved "SBI"), as {lat, lon}, or null. */
    private static double[] placeOf(Context c, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        try {
            for (JSONObject p : Places.all(c)) {
                String n = p.optString("name").toLowerCase(Locale.ROOT).trim();
                if (n.codePointCount(0, n.length()) >= 3 && p.has("lat") && t.contains(n)) return new double[]{p.optDouble("lat"), p.optDouble("lon")};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static double[] home(Context c) {
        JSONObject h = Duty.placeLike(c, Duty.HOME_PLACE);
        return h == null ? null : new double[]{h.optDouble("lat"), h.optDouble("lon")};
    }

    /** The waiting jobs that can be done on this day (their kind of place is open). */
    static List<JSONObject> doable(Context c, LocalDate d) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : pending(c)) if (shut(kind(o.optString("text")), d) == null) out.add(o);
        return out;
    }

    /** The list for a day (as said that morning, and shown when he asks): the jobs doable then, in the way's order. */
    static List<String> todayLines(Context c, LocalDate d) {
        List<JSONObject> jobs = doable(c, d);
        List<double[]> pts = new ArrayList<>();
        for (JSONObject o : jobs) pts.add(placeOf(c, o.optString("text")));
        return lines(jobs, pts, home(c), d);
    }

    /** "బ్యాంక్‌లో పాస్‌బుక్: శనివారం 21 నవంబర్" for the jobs that can't be done on this day (their place is shut). */
    static List<String> laterLines(Context c, LocalDate d) {
        List<String> out = new ArrayList<>();
        for (JSONObject o : pending(c)) {
            String k = kind(o.optString("text"));
            if (shut(k, d) == null) continue;
            LocalDate n = nextLeaveDay(c, d.plusDays(1), k);
            out.add(o.optString("text") + ": " + (n == null ? "రెండు నెలల్లో తెరిచి ఉండే సెలవు రోజు లేదు" : Duty.day(n)) + " (" + shut(k, d).replace("⚠️ ", "") + ")");
        }
        return out;
    }

    static void tick(Context c, Prefs p, boolean hush) {
        if (!on(c) || p.homeMode()) return;
        pruneTold(c);
        LocalDate day0 = LocalDate.now();
        List<JSONObject> jobs = doable(c, day0); // (only what can be done today: a bank job waits past a bank holiday)
        if (jobs.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        if (!today.equals(day0) || !leaveDay(c, today)) return;
        int m = now.getHour() * 60 + now.getMinute(), from = morningMin(c);
        if (!hush && m >= from && m < Math.min(from + 150, 24 * 60) && once(c, "am|" + today)) {
            List<String> l = todayLines(c, today);
            List<String> later = laterLines(c, today);
            String body = String.join("\n", l) + (later.isEmpty() ? "" : "\n\nఈ రోజు కాదు (మూసి ఉంటాయి):\n" + String.join("\n", later));
            notify(c, ("leave" + today).hashCode(), "🗓️ ఈ రోజు సెలవు: పనులు " + jobs.size(), body + "\n\nఅయిపోయినవి \"X అయింది\" అని చెప్పండి.");
            StringBuilder say = new StringBuilder(p.name() + ", ఈ రోజు సెలవు. " + jobs.size() + " పనులు ఉన్నాయి: ");
            for (int i = 0; i < Math.min(4, jobs.size()); i++) say.append(i == 0 ? "" : ", ").append(l.get(i).replaceFirst("^\\d+\\. ", "").replaceAll(" \\(.*?\\)", "").replaceAll(" ⚠️.*", ""));
            say.append(jobs.size() > 4 ? " ఇంకా కొన్ని." : ".");
            Announcer.say(c, say.toString());
        }
        // the evening: "అయ్యాయా?" — only on a day the list was given
        if (!hush && now.getHour() >= 19 && now.getHour() < 21 && told(c, "am|" + today) && !MainActivity.busyTalking() && once(c, "pm|" + today)) {
            StringBuilder ctx = new StringBuilder(" [leave-day jobs today:");
            StringBuilder names = new StringBuilder();
            for (JSONObject o : jobs) {
                ctx.append(" ").append(o.optString("id")).append(" '").append(o.optString("text")).append("';");
                names.append(names.length() == 0 ? "" : ", ").append(o.optString("text"));
            }
            ctx.append(" when he says which are done, call leave_jobs action done with those ids; the rest wait for his next leave day (say which day). "
                    + "If he says none / later, say సరే, they wait for the next leave day.]");
            Proactive.say(c, p.name() + ", ఈ రోజు సెలవు పనులు: " + names + ".", "ఏవి అయ్యాయి?", ctx.toString());
        }
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_leave_jobs", "సెలవు రోజు పనులు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_ASK, "నా సెలవు రోజు పనులు చూపించు"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("leave_jobs", id, new Notification.Builder(c, "jarvis_leave_jobs").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title).setContentText(text.split("\n")[0]).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
