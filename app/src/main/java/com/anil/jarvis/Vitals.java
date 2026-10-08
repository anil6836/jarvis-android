package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * His BP, sugar, weight and oxygen (SpO2) readings: kept on the phone, with a plain Telugu word on each reading (normal / high / low)
 * and when it needs a doctor or 108, and the trend over weeks. General guidance, not a diagnosis.
 */
final class Vitals {
    private Vitals() {}

    static final String KEY = "vitals";

    static String kind(String k) {
        String s = k == null ? "" : k.toLowerCase(Locale.ROOT);
        if (s.contains("bp") || s.contains("బీపీ") || s.contains("pressure") || s.contains("రక్తపోటు")) return "bp";
        if (s.contains("sugar") || s.contains("షుగర్") || s.contains("glucose") || s.contains("చక్కెర")) return "sugar";
        if (s.contains("weight") || s.contains("బరువు")) return "weight";
        if (s.contains("spo2") || s.contains("sp02") || s.contains("oxygen") || s.contains("ఆక్సిజన్") || s.contains("saturation")) return "spo2";
        return "";
    }

    /** Adds a reading; returns it with "status" and "advice" in Telugu, or null if it doesn't make sense. */
    static JSONObject add(Context c, String kind, int sys, int dia, int pulse, double value, String when) throws Exception {
        String k = kind(kind);
        JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("kind", k);
        if (k.equals("bp")) {
            if (sys < 60 || sys > 260 || dia < 30 || dia > 180 || dia >= sys) return null;
            o.put("sys", sys).put("dia", dia);
            if (pulse >= 30 && pulse <= 220) o.put("pulse", pulse);
        } else if (k.equals("sugar")) {
            if (value < 20 || value > 700) return null;
            String w = when == null ? "" : when.toLowerCase(Locale.ROOT);
            // after food first: "after breakfast" contains "fast"
            o.put("value", value).put("when", w.contains("after") || w.contains("తిన్న") || w.contains("pp") || w.contains("భోజనం తర్వాత") ? "after_food"
                    : w.contains("fast") || w.contains("పరగడుపు") || w.contains("ఖాళీ") ? "fasting" : "random");
        } else if (k.equals("weight")) {
            if (value < 20 || value > 250) return null;
            o.put("value", value);
        } else if (k.equals("spo2")) { // W27: oxygen read on the watch (Samsung Health), told to Jarvis
            if (value < 50 || value > 100) return null;
            o.put("value", Math.round(value));
        } else return null;
        judge(o);
        Notes.add(c, KEY, o, 1000);
        return o;
    }

    /** status + advice for one reading. */
    static void judge(JSONObject o) throws Exception {
        String k = o.optString("kind"), status, advice;
        if (k.equals("bp")) {
            int s = o.optInt("sys"), d = o.optInt("dia");
            if (s >= 180 || d >= 120) { status = "చాలా ఎక్కువ"; advice = "5 నిమిషాలు కూర్చుని విశ్రాంతి తీసుకుని మళ్లీ చూడండి. తీవ్రమైన తలనొప్పి, ఛాతి నొప్పి, చూపు మసక, ఊపిరి ఇబ్బంది, కాలు-చేయి బలహీనత, మాట తడబడటం, అయోమయం ఉంటే వెంటనే 108. లక్షణాలు లేకపోయినా ఈరోజే డాక్టర్‌ని చూడండి."; }
            else if (s >= 140 || d >= 90) { status = "ఎక్కువ (హై BP)"; advice = "ఉప్పు, నూనె తగ్గించండి; మళ్లీ మళ్లీ ఇంత వస్తుంటే జనరల్ ఫిజిషియన్‌ని చూడండి. BP మందులు డాక్టర్ రాస్తేనే."; }
            else if (s >= 130 || d >= 80) { status = "కొంచెం ఎక్కువ"; advice = "నడక, ఉప్పు తగ్గించడం, సరిపడా నిద్ర; కొన్ని రోజులు చూస్తూ ఉండండి."; }
            else if (s < 90 || d < 60) { status = "తక్కువ"; advice = "నీళ్లు / ORS తాగండి, కూర్చోండి. తరచూ కళ్ళు తిరిగితే డాక్టర్. స్పృహ తప్పడం, అయోమయం, ఛాతి నొప్పి ఉంటే వెంటనే 108."; }
            else { status = "సాధారణం"; advice = "బాగుంది."; }
        } else if (k.equals("sugar")) {
            double v = o.optDouble("value");
            String w = o.optString("when");
            if (v < 54) { status = "చాలా తక్కువ"; advice = "వెంటనే 3-4 చెంచాల చక్కెర / గ్లూకోజ్ / జ్యూస్ తీసుకోండి, 15 నిమిషాల తర్వాత మళ్లీ చూడండి. మత్తు, స్పృహ తప్పుతుంటే 108 (స్పృహ లేని వాళ్ల నోట్లో ఏమీ పోయకూడదు)."; }
            else if (v < 70) { status = "తక్కువ"; advice = "ఏదైనా తీపి / జ్యూస్ తీసుకోండి, 15 నిమిషాల తర్వాత మళ్లీ చూడండి."; }
            else if (v >= 300) { status = "చాలా ఎక్కువ"; advice = "ఈరోజే డాక్టర్‌ని చూడండి. వాంతులు, బాగా దాహం, మత్తు, ఊపిరి వేగంగా ఉంటే వెంటనే ఆసుపత్రి."; }
            else if (w.equals("fasting")) {
                if (v >= 126) { status = "ఎక్కువ (పరగడుపున)"; advice = "మళ్లీ మళ్లీ ఇలా ఉంటే షుగర్ కావచ్చు: HbA1c పరీక్ష, జనరల్ ఫిజిషియన్ / డయాబెటాలజిస్ట్."; }
                else if (v >= 100) { status = "కొంచెం ఎక్కువ (ప్రీ-డయాబెటిస్ రేంజ్)"; advice = "తీపి, అన్నం తగ్గించి రోజూ నడక; 3 నెలల్లో మళ్లీ పరీక్ష."; }
                else { status = "సాధారణం"; advice = "బాగుంది."; }
            } else if (w.equals("after_food")) {
                if (v >= 200) { status = "ఎక్కువ (తిన్న తర్వాత)"; advice = "HbA1c పరీక్ష చేయించుకోండి, డాక్టర్‌ని చూడండి."; }
                else if (v >= 140) { status = "కొంచెం ఎక్కువ (తిన్న తర్వాత)"; advice = "తీపి, అన్నం తగ్గించి నడక; మళ్లీ చూడండి."; }
                else { status = "సాధారణం"; advice = "బాగుంది."; }
            } else {
                if (v >= 200) { status = "ఎక్కువ"; advice = "పరగడుపున ఒకసారి చూడండి; ఇలాగే వస్తే డాక్టర్‌ని చూడండి."; }
                else { status = "పరవాలేదు"; advice = "పరగడుపున చూసిన రీడింగ్ అయితే ఇంకా బాగా తెలుస్తుంది."; }
            }
        } else if (k.equals("spo2")) {
            long v = Math.round(o.optDouble("value"));
            if (v < 90) { status = "చాలా తక్కువ"; advice = "వాచ్ మణికట్టుకి సరిగ్గా ఉందో చూసి, కూర్చుని మళ్లీ ఒకసారి కొలవండి. మళ్లీ 90 లోపు వస్తే, లేదా ఊపిరి ఆడకపోవడం, పెదాలు నీలంగా మారడం, అయోమయం ఉంటే వెంటనే 108 / ఆసుపత్రి."; }
            else if (v < 92) { status = "తక్కువ"; advice = "ఈరోజే డాక్టర్‌ని చూడండి. ఊపిరి ఆడకపోవడం, ఛాతి నొప్పి, పెదాలు నీలంగా మారితే వెంటనే 108."; }
            else if (v < 95) { status = "కొంచెం తక్కువ"; advice = "కూర్చుని నెమ్మదిగా శ్వాస తీసుకుని 5 నిమిషాల తర్వాత మళ్లీ చూడండి. ఊపిరి ఇబ్బందిగా ఉంటే ఈరోజే డాక్టర్‌ని చూడండి."; }
            else { status = "సాధారణం"; advice = "బాగుంది."; }
        } else {
            status = ""; advice = "";
        }
        o.put("status", status).put("advice", advice);
    }

    static String line(JSONObject o) {
        String when = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date(o.optLong("t")));
        String k = o.optString("kind");
        if (k.equals("bp")) return when + " · BP " + o.optInt("sys") + "/" + o.optInt("dia") + (o.has("pulse") ? " · పల్స్ " + o.optInt("pulse") : "") + " · " + o.optString("status");
        if (k.equals("sugar")) return when + " · షుగర్ " + Math.round(o.optDouble("value")) + " (" + ("fasting".equals(o.optString("when")) ? "పరగడుపున"
                : "after_food".equals(o.optString("when")) ? "తిన్న తర్వాత" : "ఎప్పుడైనా") + ") · " + o.optString("status");
        if (k.equals("spo2")) return when + " · ఆక్సిజన్ (SpO2) " + Math.round(o.optDouble("value")) + "% · " + o.optString("status");
        return when + " · బరువు " + o.optDouble("value") + " కిలోలు";
    }

    /** Readings of the last N days (newest first), and averages this week vs the week before, weight change in 30 days. */
    static JSONObject summary(Context c, String kindFilter, int days) throws Exception {
        String kf = kind(kindFilter);
        long now = System.currentTimeMillis(), since = now - Math.max(1, days) * 86400000L;
        List<JSONObject> all = Notes.list(c, KEY);
        JSONArray lines = new JSONArray();
        double[] bpA = new double[3], bpB = new double[3], sugA = new double[2], sugB = new double[2];
        double wFirst = 0, wLast = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            JSONObject o = all.get(i);
            long t = o.optLong("t");
            String k = o.optString("kind");
            if ((kf.isEmpty() || kf.equals(k)) && t >= since && lines.length() < 30) lines.put(line(o));
            boolean thisWeek = now - t < 7 * 86400000L, lastWeek = !thisWeek && now - t < 14 * 86400000L;
            if (k.equals("bp")) {
                double[] b = thisWeek ? bpA : lastWeek ? bpB : null;
                if (b != null) { b[0] += o.optInt("sys"); b[1] += o.optInt("dia"); b[2]++; }
            } else if (k.equals("sugar") && "fasting".equals(o.optString("when"))) {
                double[] b = thisWeek ? sugA : lastWeek ? sugB : null;
                if (b != null) { b[0] += o.optDouble("value"); b[1]++; }
            } else if (k.equals("weight") && now - t < 30 * 86400000L) {
                if (wLast == 0) wLast = o.optDouble("value");
                wFirst = o.optDouble("value");
            }
        }
        JSONObject out = new JSONObject().put("ok", true).put("readings", lines);
        if (bpA[2] > 0) out.put("bp_avg_this_week", Math.round(bpA[0] / bpA[2]) + "/" + Math.round(bpA[1] / bpA[2]));
        if (bpB[2] > 0) out.put("bp_avg_last_week", Math.round(bpB[0] / bpB[2]) + "/" + Math.round(bpB[1] / bpB[2]));
        if (sugA[1] > 0) out.put("fasting_sugar_avg_this_week", Math.round(sugA[0] / sugA[1]));
        if (sugB[1] > 0) out.put("fasting_sugar_avg_last_week", Math.round(sugB[0] / sugB[1]));
        if (wLast > 0 && wFirst > 0) out.put("weight_change_30_days_kg", Math.round((wLast - wFirst) * 10) / 10.0).put("weight_now_kg", wLast);
        if (lines.length() == 0) out.put("note", "No readings saved yet. He can say e.g. 'BP 130/85', 'షుగర్ పరగడుపున 110', 'బరువు 72', 'ఆక్సిజన్ 96'.");
        return out;
    }

    static boolean removeLast(Context c) {
        List<JSONObject> l = Notes.list(c, KEY);
        if (l.isEmpty()) return false;
        l.remove(l.size() - 1);
        Notes.save(c, KEY, l, 1000);
        return true;
    }
}
