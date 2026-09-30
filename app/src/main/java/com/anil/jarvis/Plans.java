package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Looking ahead and small daily helpers:
 *  - the coming week every Sunday evening (duties, EMIs, bills' last dates, birthdays, holidays)
 *  - handover notes for the next batch, reminded half an hour before he is relieved
 *  - a Jarvis tip a day, so he knows what else it can do
 */
final class Plans {
    private Plans() {}

    // ================================================================ the week ahead

    static JSONObject week(Context c) throws Exception {
        LocalDate from = LocalDate.now().plusDays(LocalTime.now().getHour() >= 17 ? 1 : 0), to = from.plusDays(6);
        JSONArray duty = new JSONArray(), money = new JSONArray(), dates = new JSONArray(), bdays = new JSONArray(), hols = new JSONArray();
        try {
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r)) for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
                if (Duty.startsDuty(r, d)) duty.put(Duty.day(d) + " " + r.timeOf(Duty.ME) + " (" + r.leaveTime(r.timeOf(Duty.ME)) + " కల్లా బయలుదేరాలి)");
        } catch (Exception ignored) {}
        for (JSONObject o : Debts.open(c)) {
            String k = o.optString("kind");
            if (k.equals("emi") || k.equals("chit")) {
                for (YearMonth m : new YearMonth[]{YearMonth.from(from), YearMonth.from(to)}) {
                    LocalDate d = Debts.dueThisMonth(o, m);
                    if (!d.isBefore(from) && !d.isAfter(to) && !Debts.paidFor(o, m)) { money.put(Duty.day(d) + ": " + Debts.line(o)); break; }
                }
            } else if (!o.optString("due").isEmpty()) {
                LocalDate d = Debts.parse(o.optString("due"));
                if (d != null && !d.isBefore(from) && !d.isAfter(to)) money.put(Duty.day(d) + ": " + Debts.line(o));
            }
        }
        for (JSONObject o : Expiry.soon(c, 7)) dates.put(Expiry.line(c, o));
        for (JSONObject b : Birthdays.upcoming(c, 7)) bdays.put(Birthdays.label(b) + " · " + Birthdays.when(b));
        for (Holidays.Day h : Holidays.between(c, from, to)) if (h.big()) hols.put(Duty.day(h.date) + ": " + h.name);
        return new JSONObject().put("ok", true).put("from", Duty.day(from)).put("to", Duty.day(to)).put("duty_starts", duty)
                .put("payments", money).put("last_dates", dates).put("birthdays", bdays).put("holidays", hols);
    }

    static String weekText(JSONObject w) {
        StringBuilder s = new StringBuilder();
        add(s, "🗓️ డ్యూటీ", w.optJSONArray("duty_starts"));
        add(s, "💳 కట్టాల్సినవి", w.optJSONArray("payments"));
        add(s, "📄 గడువులు", w.optJSONArray("last_dates"));
        add(s, "🎂 పుట్టినరోజులు", w.optJSONArray("birthdays"));
        add(s, "🎉 పండుగలు", w.optJSONArray("holidays"));
        return s.toString().trim();
    }

    private static void add(StringBuilder s, String head, JSONArray a) {
        if (a == null || a.length() == 0) return;
        s.append(head).append(":\n");
        for (int i = 0; i < a.length(); i++) s.append("  • ").append(a.optString(i)).append("\n");
    }

    // ================================================================ handover notes

    static final String HANDOVER = "handover";

    static JSONObject addNote(Context c, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return null;
        return Notes.add(c, HANDOVER, new JSONObject().put("t", System.currentTimeMillis()).put("text", text.trim()), 50);
    }

    static List<String> notes(Context c) {
        List<String> out = new ArrayList<>();
        for (JSONObject o : Notes.list(c, HANDOVER)) out.add(o.optString("text"));
        return out;
    }

    static void clearNotes(Context c) { Notes.save(c, HANDOVER, new ArrayList<>(), 50); }

    /** The people who relieve him at the end of this (or his next) duty: those who start on the day it ends. */
    static String nextBatch(Context c) {
        try {
            Duty.Roster r = Duty.load(c);
            if (!Duty.ready(r)) return "";
            LocalDate d = LocalDate.now();
            if (!r.isOn(Duty.ME, d) && !r.isOn(Duty.ME, d.minusDays(1))) { // at home: his next duty
                int i = 0;
                while (!r.isOn(Duty.ME, d) && i++ < 40) d = d.plusDays(1);
            }
            int i = 0;
            while (r.isOn(Duty.ME, d) && i++ < 20) d = d.plusDays(1); // the day it ends
            List<String> names = new ArrayList<>();
            for (String p : r.onDuty(d)) if (!Duty.ME.equals(p) && !r.isOn(p, d.minusDays(1))) names.add(p);
            return android.text.TextUtils.join(", ", names);
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================ a tip a day

    static final String[][] TIPS = {
            {"పాటతో అలారం", "\"రోజూ 6 కి పాటతో లేపు\" అనండి. లేచాక వాతావరణం, డ్యూటీ కూడా చెప్తాను."},
            {"డ్యూటీ రిపోర్ట్", "\"ఈ నెల నా డ్యూటీ రిపోర్ట్\" అంటే డ్యూటీలు, ఎక్స్‌ట్రాలు, సెలవులు లెక్క చెప్తాను."},
            {"బైక్ ప్రయాణం", "\"విజయవాడకి బైక్ మీద వెళ్లగలనా?\" అంటే ఛార్జ్ సరిపోతుందో, ఎక్కడ ఛార్జ్ చేయాలో చెప్తాను."},
            {"గడువులు", "బైక్ ఇన్సూరెన్స్ కాగితం ఫోటో తీస్తే గడువు తేదీ రాసుకుని ముందే గుర్తుచేస్తాను (ఫీచర్లు → కెమెరా)."},
            {"అప్పులు", "\"రవికి 5000 ఇచ్చాను, 10న ఇస్తానన్నాడు\" అంటే రాసుకుని ఆ రోజు గుర్తుచేస్తాను."},
            {"EMI", "\"బైక్ లోన్ EMI నెలకి 3000, 5వ తేదీ\" అంటే ప్రతి నెల ముందు రోజు గుర్తుచేస్తాను."},
            {"ఆరోగ్యం", "\"జలుబు చేసింది\" అంటే ముందు ఇంటి చిట్కాలు, కావాలంటే టాబ్లెట్, తగ్గకపోతే ఏ డాక్టరో చెప్తాను."},
            {"BP, షుగర్", "\"BP 130/85\" అని చెబితే రాసుకుని సాధారణమా కాదా చెప్తాను, వారం ట్రెండ్ కూడా."},
            {"ఫోన్ వెతుకు", "ఫోన్ కనిపించకపోతే \"Jarvis, ఎక్కడున్నావ్?\" అనండి, గట్టిగా మోగుతాను. వేరే ఫోన్ నుంచి కోడ్ పంపినా మోగుతాను."},
            {"లోకల్ వార్తలు", "\"మా జిల్లా వార్తలు చెప్పు\" అంటే మీ ప్రాంతాల తాజా వార్తలు తెలుగులో చదువుతాను."},
            {"డైరీ", "\"గత నెల 10న ఏం చేశాను?\" అంటే డైరీలో, డ్యూటీలో చూసి చెప్తాను."},
            {"వస్తువులు", "\"తాళాలు బీరువా పై అరలో పెట్టాను\" అని చెప్పండి. తర్వాత \"తాళాలు ఎక్కడ?\" అంటే చెప్తాను."},
            {"ఖర్చు పంచుకోవడం", "\"నేను 1200, రవి 800, సురేష్ 0 కట్టాం, ముగ్గురం సమానంగా\" అంటే ఎవరు ఎవరికి ఎంత ఇవ్వాలో చెప్తాను."},
            {"లెటర్లు", "\"సెలవు దరఖాస్తు రాయి, 3 రోజులు, కారణం పెళ్లి\" అంటే సరైన లెటర్ రాసి PDF ఇస్తాను."},
            {"విషెస్ కార్డ్", "\"అమ్మకి పుట్టినరోజు కార్డ్ తయారుచెయ్\" అంటే అందమైన శుభాకాంక్షల ఫోటో చేస్తాను."},
            {"దగ్గర్లో తెరిచి ఉన్నవి", "\"దగ్గర్లో 24 గంటల మెడికల్ షాప్\" అంటే దూరంతో సహా చెప్తాను."},
            {"నిద్ర శబ్దాలు", "\"30 నిమిషాలు వర్షం శబ్దం పెట్టు\" అంటే నిద్ర పట్టేలా మెల్లగా వినిపించి ఆపేస్తాను."},
            {"రేడియో", "\"తెలుగు రేడియో పెట్టు\" అంటే తెలుగు రేడియో స్టేషన్లు లైవ్‌గా వినిపిస్తాను."},
            {"క్విజ్", "\"క్విజ్ ఆడదాం\" అంటే తెలుగులో ప్రశ్నలు అడిగి స్కోర్ చెప్తాను. పిల్లలతో కలిసి ఆడండి."},
            {"హోంవర్క్", "పిల్లల లెక్క ఫోటో తీస్తే స్టెప్ బై స్టెప్ తెలుగులో నేర్పిస్తాను (ఫీచర్లు → కెమెరా)."},
            {"మొక్కల డాక్టర్", "ఆకు లేదా పంట ఫోటో తీస్తే ఏ తెగులో, ఏం చేయాలో చెప్తాను."},
            {"ప్రభుత్వ సేవలు", "\"ఇన్‌కమ్ సర్టిఫికెట్‌కి ఏం కావాలి?\" అంటే డాక్యుమెంట్లు, ఎక్కడ అప్లై చేయాలో చెప్తాను."},
            {"కొత్త సినిమాలు", "\"ఈ వారం OTT లో ఏం వచ్చాయి?\" అంటే కొత్త తెలుగు సినిమాలు చెప్తాను."},
            {"ధరల పోలిక", "\"ఈ ఫోన్ ఎక్కడ చౌక?\" అంటే Amazon, Flipkart లో ధరలు పోల్చి చెప్తాను."},
            {"వ్యాయామం", "\"5 నిమిషాల వ్యాయామం\" అంటే నడుము, మెడ వ్యాయామాలు గొంతుతో చెప్పి చేయిస్తాను."},
            {"అలవాట్లు", "\"రోజూ నడక అలవాటు పెట్టు\" అంటే రాత్రి చేశారా అని అడిగి వరుస రోజుల లెక్క చెప్తాను."},
            {"హ్యాండోవర్", "డ్యూటీలో \"హ్యాండోవర్ నోట్: గేట్ తాళం మార్చాలి\" అంటే రాసుకుని రిలీవ్ టైమ్‌కి గుర్తుచేస్తాను."},
            {"పొదుపు", "\"జీతం 30000, నెలకి 5000 దాచాలి\" అంటే ఖర్చులు చూసి ముందే హెచ్చరిస్తాను."},
            {"కాల్ నోట్", "ముఖ్యమైన కాల్ అయ్యాక ఏమైనా గుర్తుపెట్టుకోవాలా అని అడుగుతాను. చెబితే రాసుకుంటాను."},
            {"బంగారం ధర", "\"ఈరోజు బంగారం ధర\" అంటే 22K, 24K ధర చెప్తాను. \"7000 కి తగ్గితే చెప్పు\" అంటే కాచుకుంటాను."},
            {"అన్ని ఫీచర్లు", "\"అన్ని ఫీచర్లు చూపించు\" అంటే అన్నీ ఫోల్డర్లలో చూపిస్తాను."},
    };

    static String[] tip(Context c, boolean advance) {
        SharedPreferences s = c.getSharedPreferences("jarvis_tips", Context.MODE_PRIVATE);
        int i = s.getInt("i", 0) % TIPS.length;
        if (advance) s.edit().putInt("i", i + 1).apply();
        return TIPS[i];
    }

    // ================================================================ from Proactive

    static void tick(Context c, Prefs p, boolean quiet) {
        LocalDate today = LocalDate.now();
        int h = LocalTime.now().getHour(), m = LocalTime.now().getMinute();
        SharedPreferences s = c.getSharedPreferences("jarvis_plans", Context.MODE_PRIVATE);
        // Sunday evening: the week ahead
        if (p.weekPlan() && today.getDayOfWeek() == DayOfWeek.SUNDAY && h >= 18 && h < 21 && !today.toString().equals(s.getString("week", ""))) {
            s.edit().putString("week", today.toString()).apply();
            try {
                String text = weekText(week(c));
                if (!text.isEmpty()) {
                    Reminders.notify(c, "🗓️ వచ్చే వారం", text, 98);
                    if (!quiet) Announcer.say(c, p.name() + ", వచ్చే వారం ప్లాన్. " + text.replace("\n", " ").replace("•", "").replaceAll("[🗓️💳📄🎂🎉]", ""));
                }
            } catch (Exception ignored) {}
        }
        // handover: half an hour before he is relieved, on the day his duty ends
        try {
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r) && r.isOn(Duty.ME, today.minusDays(1)) && !r.isOn(Duty.ME, today) && !notes(c).isEmpty()
                    && !today.toString().equals(s.getString("handover", ""))) {
                String[] hm = r.timeOf(Duty.ME).split(":");
                int relieve = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]), now = h * 60 + m;
                if (now >= relieve - 40 && now < relieve + 30 && !MainActivity.busyTalking() && !CallControl.busyWithCall()) {
                    s.edit().putString("handover", today.toString()).apply();
                    List<String> n = notes(c);
                    String who = nextBatch(c);
                    if (quiet) { // Do Not Disturb / in a call: only a note
                        Reminders.notify(c, "📝 హ్యాండోవర్ నోట్స్ (" + n.size() + ")", android.text.TextUtils.join("\n", n), 102);
                        return;
                    }
                    Proactive.say(c, p.name() + ", హ్యాండోవర్ టైమ్ దగ్గర పడింది.", (who.isEmpty() ? "తర్వాతి బ్యాచ్‌కి" : who + " కి") + " చెప్పాల్సినవి "
                                    + n.size() + " ఉన్నాయి. చదవమంటారా, WhatsApp లో పంపమంటారా?",
                            " [handover notes for " + (who.isEmpty() ? "the next batch" : who) + ": " + android.text.TextUtils.join(" | ", n)
                                    + ". If he says read: read them. If send: whatsapp_message to the person / group he names (read it back, send only after he says send). "
                                    + "Afterwards ask whether to clear them (handover clear).]");
                }
            }
        } catch (Exception ignored) {}
        // a tip a day, late morning
        if (p.dailyTip() && h >= 11 && h < 13 && !today.toString().equals(s.getString("tip", ""))) {
            s.edit().putString("tip", today.toString()).apply();
            String[] t = tip(c, true);
            Reminders.notify(c, "📚 ఈరోజు Jarvis చిట్కా: " + t[0], t[1], 99);
            boolean atWork = false;
            try { Duty.Roster r = Duty.load(c); atWork = Duty.ready(r) && !Duty.homeAllDay(r, today); } catch (Exception ignored) {}
            if (!quiet && !atWork) Announcer.say(c, "ఈరోజు Jarvis చిట్కా. " + t[1]);
        }
    }
}
