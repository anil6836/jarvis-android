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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Festivals and holidays: the Telangana government's general holidays (built in), festivals from Google's public
 * Indian holiday calendar (fetched once a week, in Telugu when it has them), and days he adds himself.
 * Shown on the duty calendar; said the evening before (with "you have duty tomorrow") and in the morning.
 */
final class Holidays {
    private Holidays() {}

    static final class Day {
        final LocalDate date;
        final String name, kind; // mine, govt, optional, festival, observance
        Day(LocalDate date, String name, String kind) { this.date = date; this.name = name; this.kind = kind; }
        /** Worth a mark on the calendar and a reminder: his own days, government holidays, and the big festivals. */
        boolean big() { return kind.equals("mine") || kind.equals("govt") || major(name); }
        String kindTe() {
            switch (kind) {
                case "govt": return "ప్రభుత్వ సెలవు";
                case "optional": return "ఐచ్ఛిక సెలవు";
                case "mine": return "మీరు చేర్చింది";
                default: return "పండుగ";
            }
        }
    }

    /** Telangana general holidays (G.O. for 2026), and the fixed-date ones of 2027 until its list comes out. */
    private static final String[][] BUILT_IN = {
            {"2026-01-14", "భోగి"}, {"2026-01-15", "సంక్రాంతి"}, {"2026-01-26", "గణతంత్ర దినోత్సవం"}, {"2026-02-15", "మహా శివరాత్రి"},
            {"2026-03-03", "హోలీ"}, {"2026-03-19", "ఉగాది"}, {"2026-03-21", "రంజాన్"}, {"2026-03-22", "రంజాన్ తర్వాతి రోజు"},
            {"2026-03-27", "శ్రీరామ నవమి"}, {"2026-04-03", "గుడ్ ఫ్రైడే"}, {"2026-04-05", "బాబు జగ్జీవన్ రామ్ జయంతి"},
            {"2026-04-14", "అంబేద్కర్ జయంతి"}, {"2026-05-27", "బక్రీద్"}, {"2026-06-26", "మొహర్రం"}, {"2026-08-10", "బోనాలు"},
            {"2026-08-15", "స్వాతంత్ర్య దినోత్సవం"}, {"2026-08-26", "మిలాద్ ఉన్ నబీ"}, {"2026-09-04", "శ్రీకృష్ణాష్టమి"},
            {"2026-09-14", "వినాయక చవితి"}, {"2026-10-02", "గాంధీ జయంతి"}, {"2026-10-18", "సద్దుల బతుకమ్మ"},
            {"2026-10-20", "విజయ దశమి (దసరా)"}, {"2026-10-21", "దసరా తర్వాతి రోజు"}, {"2026-11-08", "దీపావళి"},
            {"2026-11-24", "కార్తీక పౌర్ణమి / గురునానక్ జయంతి"}, {"2026-12-25", "క్రిస్మస్"}, {"2026-12-26", "క్రిస్మస్ తర్వాతి రోజు"},
            {"2027-01-26", "గణతంత్ర దినోత్సవం"}, {"2027-04-14", "అంబేద్కర్ జయంతి"}, {"2027-08-15", "స్వాతంత్ర్య దినోత్సవం"},
            {"2027-10-02", "గాంధీ జయంతి"}, {"2027-12-25", "క్రిస్మస్"}, {"2027-12-26", "క్రిస్మస్ తర్వాతి రోజు"},
    };
    private static final String[][] OPTIONAL = {
            {"2026-10-19", "మహర్నవమి"}, {"2026-11-08", "నరక చతుర్దశి"}, {"2026-12-24", "క్రిస్మస్ ఈవ్"}, {"2027-01-01", "నూతన సంవత్సరం"},
            {"2027-12-24", "క్రిస్మస్ ఈవ్"},
    };

    private static final String[] MAJOR = {"సంక్రాంతి", "ఉగాది", "దీపావళి", "దసరా", "దశమి", "వినాయక", "క్రిస్మస్", "ఈస్టర్", "గుడ్ ఫ్రైడే",
            "శివరాత్రి", "హోలీ", "శ్రీరామ", "బతుకమ్మ", "బోనాలు", "రంజాన్", "బక్రీద్", "గణతంత్ర", "స్వాతంత్ర్య", "గాంధీ", "అంబేద్కర్", "భోగి",
            "కృష్ణాష్టమి", "జన్మాష్టమి", "నూతన సంవత్సర"};

    static boolean major(String name) {
        String n = name == null ? "" : name;
        for (String m : MAJOR) if (n.contains(m)) return true;
        return false;
    }

    /** English names from the English calendar, in Telugu. */
    private static final String[][] TE = {
            {"christmas eve", "క్రిస్మస్ ఈవ్"}, {"christmas", "క్రిస్మస్"}, {"good friday", "గుడ్ ఫ్రైడే"}, {"easter", "ఈస్టర్"},
            {"makar sankranti", "సంక్రాంతి"}, {"sankranti", "సంక్రాంతి"}, {"pongal", "పొంగల్"}, {"lohri", "లోహ్రీ"}, {"republic day", "గణతంత్ర దినోత్సవం"},
            {"maha shivaratri", "మహా శివరాత్రి"}, {"maha shivratri", "మహా శివరాత్రి"}, {"holi", "హోలీ"}, {"ugadi", "ఉగాది"},
            {"rama navami", "శ్రీరామ నవమి"}, {"ram navami", "శ్రీరామ నవమి"}, {"ambedkar", "అంబేద్కర్ జయంతి"}, {"independence day", "స్వాతంత్ర్య దినోత్సవం"},
            {"janmashtami", "శ్రీకృష్ణాష్టమి"}, {"ganesh chaturthi", "వినాయక చవితి"}, {"vinayaka chaturthi", "వినాయక చవితి"},
            {"gandhi jayanti", "గాంధీ జయంతి"}, {"dussehra", "దసరా"}, {"vijaya dashami", "విజయ దశమి"}, {"maha navami", "మహా నవమి"},
            {"diwali", "దీపావళి"}, {"deepavali", "దీపావళి"}, {"naraka chaturdasi", "నరక చతుర్దశి"}, {"new year's day", "నూతన సంవత్సరం"},
            {"raksha bandhan", "రాఖీ పౌర్ణమి"}, {"id-ul-fitr", "రంజాన్"}, {"eid al-fitr", "రంజాన్"}, {"ramzan", "రంజాన్"},
            {"bakrid", "బక్రీద్"}, {"eid al-adha", "బక్రీద్"}, {"id-ul-zuha", "బక్రీద్"}, {"muharram", "మొహర్రం"}, {"milad", "మిలాద్ ఉన్ నబీ"},
            {"guru nanak", "గురునానక్ జయంతి"}, {"bathukamma", "బతుకమ్మ"}, {"bonalu", "బోనాలు"}, {"vasant panchami", "వసంత పంచమి"},
            {"mahavir", "మహావీర్ జయంతి"}, {"buddha purnima", "బుద్ధ పూర్ణిమ"}, {"onam", "ఓనం"}, {"karva chauth", "కర్వా చౌత్"},
    };

    static String telugu(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] t : TE) if (n.contains(t[0])) return t[1];
        return name;
    }

    // ---------------------------------------------------------------- all days

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_holidays", Context.MODE_PRIVATE); }

    static List<Day> between(Context c, LocalDate from, LocalDate to) {
        List<Day> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JSONObject o : Notes.list(c, "holidays")) addIf(out, seen, o.optString("date"), o.optString("name"), "mine", from, to);
        for (String[] h : BUILT_IN) addIf(out, seen, h[0], h[1], "govt", from, to);
        for (String[] h : OPTIONAL) addIf(out, seen, h[0], h[1], "optional", from, to);
        try {
            JSONArray a = new JSONArray(st(c).getString("ics", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                addIf(out, seen, o.optString("date"), o.optString("name"), o.optString("kind", "festival"), from, to);
            }
        } catch (Exception ignored) {}
        out.sort((x, y) -> x.date.compareTo(y.date) != 0 ? x.date.compareTo(y.date) : Integer.compare(rank(x.kind), rank(y.kind)));
        return out;
    }

    private static int rank(String k) {
        switch (k) { case "mine": return 0; case "govt": return 1; case "festival": return 2; case "optional": return 3; default: return 4; }
    }

    private static void addIf(List<Day> out, Set<String> seen, String date, String name, String kind, LocalDate from, LocalDate to) {
        LocalDate d = Debts.parse(date);
        if (d == null || name == null || name.trim().isEmpty() || d.isBefore(from) || d.isAfter(to)) return;
        String n = name.trim();
        // the same festival under two names on one day ("విజయ దశమి (దసరా)" and "దసరా") is kept once
        for (Day x : out) if (x.date.equals(d) && (x.name.contains(n) || n.contains(x.name))) return;
        if (!seen.add(d + "|" + n)) return;
        out.add(new Day(d, n, kind));
    }

    static List<Day> on(Context c, LocalDate d) { return between(c, d, d); }

    /** The one to show on a calendar date (his own or a government holiday or a big festival), or null. */
    static Day shown(List<Day> days) {
        for (Day d : days) if (d.big()) return d;
        return null;
    }

    static JSONObject add(Context c, String date, String name) throws Exception {
        LocalDate d = Debts.parse(date);
        if (d == null || name == null || name.trim().isEmpty()) return null;
        JSONObject o = new JSONObject().put("id", Notes.id("h")).put("date", d.toString()).put("name", name.trim());
        Notes.add(c, "holidays", o, 300);
        return o;
    }

    /** One of his own days: that date and that name only. */
    static void removeMine(Context c, LocalDate d, String name) {
        List<JSONObject> l = Notes.list(c, "holidays");
        l.removeIf(o -> o.optString("date").equals(d.toString()) && o.optString("name").trim().equals(name));
        Notes.save(c, "holidays", l, 300);
    }

    static boolean remove(Context c, String dateOrName) {
        if (dateOrName == null || dateOrName.trim().isEmpty()) return false;
        LocalDate d = Debts.parse(dateOrName);
        if (d != null) return Notes.remove(c, "holidays", "date", d.toString());
        return Notes.remove(c, "holidays", "name", dateOrName.trim());
    }

    // ---------------------------------------------------------------- Google's public holiday calendar

    private static final String ICS = "https://calendar.google.com/calendar/ical/%s.indian%%23holiday%%40group.v.calendar.google.com/public/basic.ics";

    /** Once a week (from Proactive's background check): festivals for the coming year, in Telugu if Google has them. */
    static void refresh(Context c) {
        SharedPreferences s = st(c);
        if (System.currentTimeMillis() - s.getLong("fetched", 0) < 7 * 86400000L) return;
        s.edit().putLong("fetched", System.currentTimeMillis()).apply();
        for (String lang : new String[]{"te", "en"}) {
            try {
                List<JSONObject> ev = parseIcs(Http.getText(String.format(Locale.ENGLISH, ICS, lang)), LocalDate.now().minusDays(30), LocalDate.now().plusDays(420));
                if (ev.isEmpty()) continue;
                JSONArray a = new JSONArray();
                for (JSONObject o : ev) a.put(o);
                s.edit().putString("ics", a.toString()).putString("ics_lang", lang).apply();
                return;
            } catch (Exception ignored) {}
        }
        s.edit().putLong("fetched", System.currentTimeMillis() - 6 * 86400000L).apply(); // no internet: try again tomorrow
    }

    /** VEVENTs of an iCalendar file: {date, name (Telugu where known), kind festival/observance}. */
    static List<JSONObject> parseIcs(String ics, LocalDate from, LocalDate to) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (ics == null) return out;
        String text = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
        for (String block : text.split("BEGIN:VEVENT")) {
            int end = block.indexOf("END:VEVENT");
            if (end < 0) continue;
            String date = null, name = null, desc = "";
            for (String line : block.substring(0, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon), val = line.substring(colon + 1).trim();
                if (key.startsWith("DTSTART")) {
                    String digits = val.replaceAll("[^0-9]", "");
                    if (digits.length() >= 8) date = digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
                } else if (key.equals("SUMMARY") || key.startsWith("SUMMARY;")) {
                    name = val.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").trim();
                } else if (key.equals("DESCRIPTION") || key.startsWith("DESCRIPTION;")) {
                    desc = val.toLowerCase(Locale.ROOT);
                }
            }
            if (date == null || name == null || name.isEmpty()) continue;
            LocalDate d;
            try { d = LocalDate.parse(date); } catch (Exception e) { continue; }
            if (d.isBefore(from) || d.isAfter(to)) continue;
            boolean observance = desc.contains("observance") || desc.contains("ఆచరణ");
            out.add(new JSONObject().put("date", d.toString()).put("name", telugu(name)).put("kind", observance ? "observance" : "festival"));
        }
        return out;
    }

    // ---------------------------------------------------------------- the evening before and the morning

    static void tick(Context c, Prefs p, boolean quiet) {
        try { refresh(c); } catch (Exception ignored) {}
        if (!p.holidayRemind()) return;
        int h = java.time.LocalTime.now().getHour();
        LocalDate today = LocalDate.now();
        SharedPreferences s = st(c);
        if (h >= 19 && h < 22 && !s.getBoolean("eve_" + today, false)) {
            List<Day> tm = big(on(c, today.plusDays(1)));
            if (!tm.isEmpty()) {
                s.edit().putBoolean("eve_" + today, true).apply();
                String names = names(tm);
                String duty = "";
                try {
                    Duty.Roster r = Duty.load(c);
                    if (Duty.ready(r)) duty = r.isOn(Duty.ME, today.plusDays(1)) ? " రేపు మీకు డ్యూటీ ఉంది." : " రేపు మీకు డ్యూటీ లేదు, ఇంట్లోనే.";
                } catch (Exception ignored) {}
                String kind = tm.get(0).kind.equals("govt") ? " (ప్రభుత్వ సెలవు)" : "";
                notify(c, ("h" + today).hashCode(), "🎉 రేపు " + names + kind, (duty.trim().isEmpty() ? Duty.day(today.plusDays(1)) : duty.trim()));
                if (!quiet) Announcer.say(c, p.name() + ", రేపు " + names + kind + "." + duty);
            }
        }
        if (h >= 7 && h < 10 && !s.getBoolean("day_" + today, false)) {
            List<Day> td = big(on(c, today));
            if (!td.isEmpty()) {
                s.edit().putBoolean("day_" + today, true).apply();
                String names = names(td);
                notify(c, ("d" + today).hashCode(), "🎉 ఈరోజు " + names, "శుభాకాంక్షలు! ఎవరికైనా విషెస్ పంపాలంటే Jarvis ని అడగండి.");
                if (!quiet) Announcer.say(c, p.name() + ", ఈరోజు " + names + ". శుభాకాంక్షలు!");
            }
        }
    }

    private static List<Day> big(List<Day> l) {
        List<Day> out = new ArrayList<>();
        for (Day d : l) if (d.big()) out.add(d);
        return out;
    }

    private static String names(List<Day> l) {
        List<String> n = new ArrayList<>();
        for (Day d : l) if (!n.contains(d.name)) n.add(d.name);
        return android.text.TextUtils.join(", ", n);
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_holidays", "పండుగలు, సెలవులు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, DutyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("holiday", id, new Notification.Builder(c, "jarvis_holidays").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
