package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvis without internet: his brain is out of reach, so what he says is understood here by fixed patterns (numbers,
 * times, "X కి Y అని SMS పంపు", "రాముకి 500 ఇచ్చాను", "250 ని 12 తో గుణిస్తే"...) and done with what is on the phone
 * (Tools.offlineCommand). A question that needs the internet is kept and answered when the internet is back.
 * The parsing here is plain Java (tested on the desk).
 */
final class Offline {
    private Offline() {}

    // ================================================================ "నెట్ లేదు" once per stretch without internet

    private static volatile boolean told;

    /** The first answer without internet starts with this (once, until the internet is back). */
    static String firstNote() {
        if (told) return "";
        told = true;
        return "నెట్ లేదు, ఫోన్‌లోనే వింటున్నాను. ";
    }

    /** The internet is there again: the next stretch without it is told again. */
    static void online() { told = false; }

    // ================================================================ numbers (digits or Telugu words)

    private static final Map<String, Integer> WORD = new HashMap<>();
    private static final String[] TENS_WORDS;
    static {
        String[][] w = {{"0", "సున్నా"}, {"1", "ఒకటి", "ఒక", "ఒక్క", "ఒకటే"}, {"2", "రెండు"}, {"3", "మూడు"}, {"4", "నాలుగు"},
                {"5", "ఐదు", "అయిదు"}, {"6", "ఆరు"}, {"7", "ఏడు"}, {"8", "ఎనిమిది"}, {"9", "తొమ్మిది"}, {"10", "పది"},
                {"11", "పదకొండు"}, {"12", "పన్నెండు"}, {"13", "పదమూడు"}, {"14", "పద్నాలుగు"}, {"15", "పదిహేను"},
                {"16", "పదహారు"}, {"17", "పదిహేడు"}, {"18", "పద్దెనిమిది"}, {"19", "పందొమ్మిది"},
                {"20", "ఇరవై", "ఇరువై"}, {"30", "ముప్పై", "ముప్ఫై"}, {"40", "నలభై", "నలబై"}, {"50", "యాభై", "యాబై", "ఏభై"},
                {"60", "అరవై"}, {"70", "డెబ్బై"}, {"80", "ఎనభై", "ఎనబై"}, {"90", "తొంభై", "తొంబై"}};
        List<String> tens = new ArrayList<>();
        for (String[] r : w) {
            int v = Integer.parseInt(r[0]);
            for (int i = 1; i < r.length; i++) {
                WORD.put(r[i], v);
                if (v >= 20) tens.add(r[i]);
            }
        }
        TENS_WORDS = tens.toArray(new String[0]);
    }

    /** Case endings a number word can carry ("వందకి", "ఐదుగురు" is not one). Longest first. */
    private static final String[] ENDINGS = {"రూపాయలు", "రూపాయల", "రూపాయి", "కి", "కు", "లో", "తో", "ని", "ను", "న"};
    private static final Pattern DIGITS = Pattern.compile("^(\\d[\\d,]*(?:\\.\\d+)?)");

    /** A token's value: {value, kind} kind 0 = a number, 1 = ×100 (వంద), 2 = ×1000, 3 = ×100000, 4 = +100 (నూట); null = not a number. */
    private static double[] token(String t) {
        if (t.isEmpty()) return null;
        Matcher d = DIGITS.matcher(t);
        if (d.find()) {
            try { return new double[]{Double.parseDouble(d.group(1).replace(",", "")), 0}; } catch (NumberFormatException e) { return null; }
        }
        double[] v = wordToken(t);
        if (v != null) return v;
        for (String e : ENDINGS) {
            if (t.length() > e.length() && t.endsWith(e)) {
                v = wordToken(t.substring(0, t.length() - e.length()));
                if (v != null) return v;
            }
        }
        return null;
    }

    private static double[] wordToken(String t) {
        boolean half = t.endsWith("న్నర") && t.length() > 4;
        String b = half ? t.substring(0, t.length() - 4) : t;
        Integer n = WORD.get(b);
        if (n == null && half) n = WORD.get(b + "ు"); // రెండున్నర -> రెండు
        if (n != null) return new double[]{n + (half ? .5 : 0), 0};
        if (b.equals("వంద") || b.equals("వందలు") || b.equals("వందల") || b.equals("నూరు") || b.equals("వందా")) return new double[]{half ? 1.5 : 1, 1};
        if (b.equals("నూట")) return new double[]{100, 4};
        if (b.equals("వెయ్యి") || b.equals("వేయి") || b.equals("వేలు") || b.equals("వేల") || b.equals("వెయ్య")) return new double[]{half ? 1.5 : 1, 2};
        if (b.equals("లక్ష") || b.equals("లక్షలు") || b.equals("లక్షల") || b.equals("లక్షా")) return new double[]{half ? 1.5 : 1, 3};
        for (String tw : TENS_WORDS) { // ఇరవైఐదు (written together)
            if (b.startsWith(tw) && b.length() > tw.length()) {
                Integer u = WORD.get(b.substring(tw.length()));
                if (u != null && u > 0 && u < 10) return new double[]{WORD.get(tw) + u, 0};
            }
        }
        return null;
    }

    /** All numbers in the words, in order ("2 వేల 500" = 2500, "రెండు వందల యాభై" = 250, "250 ని 12 తో" = 250, 12). */
    static List<Double> numbers(String s) {
        List<Double> out = new ArrayList<>();
        if (s == null) return out;
        String[] toks = s.replace('₹', ' ').split("[\\s?!,;:'\"“”‘’()\\[\\]]+(?!\\d)|\\s+");
        double total = 0, cur = 0, lastVal = -1;
        boolean on = false;
        int lastKind = -1;
        for (String raw : toks) {
            String t = raw.trim();
            if (t.endsWith(".") && !t.matches(".*\\d\\.\\d.*")) t = t.substring(0, t.length() - 1);
            double[] v = token(t);
            if (v == null) {
                if (on) { out.add(total + cur); total = 0; cur = 0; on = false; lastKind = -1; lastVal = -1; }
                continue;
            }
            double val = v[0];
            int kind = (int) v[1];
            if (kind == 0) {
                boolean joins = on && lastKind != 0 // after వంద / వేలు / నూట a smaller number joins on
                        || on && lastKind == 0 && lastVal >= 20 && lastVal < 100 && lastVal % 10 == 0 && val < 10 && val == Math.floor(val); // ఇరవై ఐదు
                if (on && !joins) { out.add(total + cur); total = 0; cur = 0; }
                cur += val;
                lastVal = val;
                on = true;
            } else if (kind == 1) {
                cur = (cur == 0 ? 1 : cur) * 100 * (val == 1.5 ? 1.5 : 1);
                on = true;
            } else if (kind == 4) {
                cur += 100;
                on = true;
            } else {
                double m = kind == 2 ? 1000 : 100000;
                total += (cur == 0 ? 1 : cur) * m * (val == 1.5 ? 1.5 : 1);
                cur = 0;
                on = true;
            }
            lastKind = kind;
        }
        if (on) out.add(total + cur);
        return out;
    }

    /** The first number, or -1. */
    static double number(String s) {
        List<Double> n = numbers(s);
        return n.isEmpty() ? -1 : n.get(0);
    }

    /** The sum of money in what he said: the biggest number ("ఒక టీ కి 20" = 20), or -1. */
    static double amount(String s) {
        double best = -1;
        for (double v : numbers(s)) best = Math.max(best, v);
        return best;
    }

    private static final Pattern UNITS = Pattern.compile("(\\S+)\\s*(గంటల|గంటలు|గంట|నిమిషాల|నిమిషాలు|నిమిషం|శాతం|కిలోల|కిలోలు|కిలో|లీటర్లు|లీటర్|కిలోమీటర్లు|కిలోమీటర్ల|"
            + "కి\\.మీ|km|సార్లు|రోజులు|రోజుల|ఏళ్లు|నెలలు|%)(?=\\s|$|[.,!?])", Pattern.CASE_INSENSITIVE);

    /** Money only: numbers of hours, minutes, percent, kilos... are not it ("80 శాతం అయింది" has no money). */
    static double money(String s) {
        return amount(UNITS.matcher(s == null ? "" : s).replaceAll(" "));
    }

    /** 3000 -> "3000", 2.5 -> "2.5", 3.333 -> "3.33". */
    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ENGLISH, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ================================================================ calculator

    private static final Pattern PERCENT = Pattern.compile("(శాతం|పర్సెంట్|percent|%)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMES = Pattern.compile("(గుణ|ఇంటు|ఇంటూ|\\binto\\b|\\btimes\\b|×|\\*|\\sx\\s|టైమ్స్)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIVIDE = Pattern.compile("(భాగ|డివైడ్|\\bdivided\\b|\\bdivide\\b|÷|\\s/\\s|\\bby\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUS = Pattern.compile("(ప్లస్|\\bplus\\b|\\+|కలిపితే|కూడితే|కూడిక)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUS = Pattern.compile("(మైనస్|\\bminus\\b|తీసేస్తే|తీసివేస్తే|తీసేయ్|తీసివేయ్|\\s-\\s)", Pattern.CASE_INSENSITIVE);

    /** "250 ని 12 తో గుణిస్తే", "500 ప్లస్ 300", "1000 లో 18 శాతం", "1000 by 4": the answer to say, or null. */
    static String calc(String text) {
        if (text == null) return null;
        List<Double> n = numbers(text);
        if (n.size() < 2) return null;
        double a = n.get(0), b = n.get(1);
        if (PERCENT.matcher(text).find()) return fmt(a) + " లో " + fmt(b) + " శాతం " + fmt(a * b / 100) + ".";
        if (TIMES.matcher(text).find()) return fmt(a) + " ని " + fmt(b) + " తో గుణిస్తే " + fmt(a * b) + ".";
        if (DIVIDE.matcher(text).find()) {
            if (b == 0) return "సున్నాతో భాగించలేం.";
            return fmt(a) + " ని " + fmt(b) + " తో భాగిస్తే " + fmt(a / b) + ".";
        }
        if (PLUS.matcher(text).find()) return fmt(a) + " కి " + fmt(b) + " కలిపితే " + fmt(a + b) + ".";
        if (MINUS.matcher(text).find()) return fmt(a) + " లో " + fmt(b) + " తీసేస్తే " + fmt(a - b) + ".";
        return null;
    }

    // ================================================================ when (reminders)

    private static final String[][] WEEKDAYS = {{"సోమవారం", "1"}, {"మంగళవారం", "2"}, {"బుధవారం", "3"}, {"గురువారం", "4"},
            {"శుక్రవారం", "5"}, {"శనివారం", "6"}, {"ఆదివారం", "7"}};
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})[:.](\\d{2})");
    private static final Pattern HOUR_AT = Pattern.compile(
            "(\\S+?)\\s*(గంటలకు|గంటలకి|గంటల\\s*కు|గంటల\\s*కి|గంటకు|గంటకి|గంటలు|గంట|గం\\.?)(?=\\s|$|[.,!?])(?!\\s*(తర్వాత|తరువాత))");
    private static final Pattern DIGIT_KI = Pattern.compile("(?<![\\d:.])(\\d{1,2})\\s*(కి|కు|కీ|am|pm|ఏఎం|పీఎం)(?=\\s|$|[.,!?])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HALF = Pattern.compile("(\\S+)న్నర(కి|కు|కీ)?");
    private static final Pattern MINUTES_AFTER = Pattern.compile(
            "(\\S+)\\s*(నిమిషాల్లో|నిమిషాలలో|నిమిషంలో|(?:నిమిషాల|నిమిషాలు|నిమిషం|minutes?|mins?)\\s*(?:తర్వాత|తరువాత|after))", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS_AFTER = Pattern.compile(
            "(?:(\\S+)\\s*)?(గంటల్లో|గంటలలో|గంటలో|(?:గంటల|గంట|hours?)\\s*(?:తర్వాత|తరువాత|after))", Pattern.CASE_INSENSITIVE);

    /** When a reminder is for, from his words ("రేపు ఉదయం 6 కి", "10 నిమిషాల తర్వాత", "సాయంత్రం 5:30"); null when no time is said. */
    static LocalDateTime when(String text, LocalDateTime now) { return when(text, now, false); }

    /** alarm: an hour said without the part of the day is in the morning ("5:30 కి అలారం" = 5:30 am). */
    static LocalDateTime when(String text, LocalDateTime now, boolean alarm) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT);
        // in a while
        if (t.contains("అరగంట")) return now.plusMinutes(30).withSecond(0).withNano(0);
        if (t.contains("గంటన్నర")) return now.plusMinutes(90).withSecond(0).withNano(0);
        Matcher mm = MINUTES_AFTER.matcher(t);
        if (mm.find()) {
            double n = number(mm.group(1));
            if (n > 0 && n <= 24 * 60) return now.plusMinutes((long) n).withSecond(0).withNano(0);
        }
        Matcher hm = HOURS_AFTER.matcher(t);
        if (hm.find()) {
            double n = hm.group(1) == null ? -1 : number(hm.group(1));
            if (n <= 0) n = 1; // "గంట తర్వాత"
            if (n <= 48) return now.plusMinutes(Math.round(n * 60)).withSecond(0).withNano(0);
        }
        // the day
        LocalDate day = null;
        if (t.contains("ఎల్లుండి")) day = now.toLocalDate().plusDays(2);
        else if (t.contains("రేపు") || t.contains("రేపటి")) day = now.toLocalDate().plusDays(1);
        else if (t.contains("ఈరోజు") || t.contains("ఈ రోజు") || t.contains("ఇవాళ") || t.contains("ఈవేళ")) day = now.toLocalDate();
        else {
            for (String[] w : WEEKDAYS) {
                if (t.contains(w[0])) {
                    DayOfWeek dw = DayOfWeek.of(Integer.parseInt(w[1]));
                    LocalDate d = now.toLocalDate();
                    int ahead = (dw.getValue() - d.getDayOfWeek().getValue() + 7) % 7;
                    day = d.plusDays(ahead == 0 ? 7 : ahead);
                    break;
                }
            }
        }
        // the hour
        int h = -1, m = 0;
        Matcher c = CLOCK.matcher(t);
        if (c.find()) { h = Integer.parseInt(c.group(1)); m = Integer.parseInt(c.group(2)); }
        if (h < 0) {
            Matcher half = HALF.matcher(t);
            while (half.find()) {
                double v = number(half.group(1));
                if (v < 0) v = number(half.group(1) + "ు");
                if (v >= 1 && v <= 12 && v == Math.floor(v)) { h = (int) v; m = 30; break; }
            }
        }
        if (h < 0) {
            Matcher ha = HOUR_AT.matcher(t);
            while (ha.find()) {
                double v = number(ha.group(1));
                if (v >= 0 && v <= 23 && v == Math.floor(v)) { h = (int) v; break; }
            }
        }
        if (h < 0) {
            Matcher dk = DIGIT_KI.matcher(t);
            if (dk.find()) {
                int v = Integer.parseInt(dk.group(1));
                if (v <= 23) {
                    h = v;
                    String ap = dk.group(2).toLowerCase(Locale.ROOT);
                    if ((ap.equals("pm") || ap.equals("పీఎం")) && h < 12) h += 12;
                    if ((ap.equals("am") || ap.equals("ఏఎం")) && h == 12) h = 0;
                }
            }
        }
        if (h < 0 || h > 23 || m > 59) {
            if (day == null || day.equals(now.toLocalDate())) return null; // "ఈరోజు గుర్తు చేయి": when?
            return day.atTime(9, 0); // "రేపు గుర్తు చేయి": in the morning
        }
        // the part of the day
        boolean morning = t.contains("ఉదయం") || t.contains("పొద్దున") || t.contains("తెల్లవారు") || t.contains("morning");
        boolean noon = t.contains("మధ్యాహ్నం") || t.contains("మధ్యాన్నం") || t.contains("afternoon");
        boolean evening = t.contains("సాయంత్రం") || t.contains("సాయంకాలం") || t.contains("evening");
        boolean night = t.contains("రాత్రి") || t.contains("night");
        boolean said = morning || noon || evening || night;
        if (h <= 12) {
            if (morning && h == 12) h = 0;
            else if (noon && h < 12 && h <= 6) h += 12;
            else if (evening && h < 12) h += 12;
            else if (night && h >= 6 && h < 12) h += 12;
            else if (night && h == 12) h = 0;
        }
        if (day != null) {
            if (!said && !alarm && h >= 1 && h <= 6) h += 12; // "రేపు 5 కి" a reminder: the evening (he hears the time and can say otherwise)
            LocalDateTime at = day.atTime(h, m);
            return at.isAfter(now) ? at : null;
        }
        LocalDateTime at = now.toLocalDate().atTime(h, m);
        if (!said && !alarm && h >= 1 && h < 12) { // no part of the day: the next one of the two (8:00 or 20:00)
            while (!at.isAfter(now)) at = at.plusHours(12);
            return at;
        }
        if (!at.isAfter(now)) at = at.plusDays(1);
        return at;
    }

    /** The day he means (రేపు, ఎల్లుండి, ఈరోజు, నిన్న, a weekday), or null when he says none. */
    static LocalDate dayOf(String text, LocalDate today) {
        String t = text == null ? "" : text;
        if (t.contains("ఎల్లుండి")) return today.plusDays(2);
        if (t.contains("రేపు") || t.contains("రేపటి")) return today.plusDays(1);
        if (t.contains("మొన్న")) return today.minusDays(2);
        if (t.contains("నిన్న")) return today.minusDays(1);
        if (t.contains("ఈరోజు") || t.contains("ఈ రోజు") || t.contains("ఇవాళ") || t.contains("ఈవేళ")) return today;
        for (String[] w : WEEKDAYS) {
            if (t.contains(w[0])) {
                int ahead = (Integer.parseInt(w[1]) - today.getDayOfWeek().getValue() + 7) % 7;
                return today.plusDays(ahead);
            }
        }
        return null;
    }

    private static final Pattern DAY_TIME = Pattern.compile("(ఉదయం|పొద్దున|మధ్యాహ్నం|సాయంత్రం|రాత్రి)?\\s*(?<![\\d.:])(\\d{1,2})(?:[:.](\\d{2}))?(?![\\d])");

    /** Times of day in his words ("ఉదయం 8, రాత్రి 9 కి" -> 08:00, 21:00), for medicines. */
    static List<String> dayTimes(String text) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        Matcher m = DAY_TIME.matcher(text == null ? "" : text);
        String part = "";
        while (m.find()) {
            if (m.group(1) != null) part = m.group(1);
            int h = Integer.parseInt(m.group(2)), min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (h > 23 || min > 59) continue;
            if (h < 12 && (part.equals("సాయంత్రం") || part.equals("రాత్రి") && h >= 6 || part.equals("మధ్యాహ్నం") && h <= 6)) h += 12;
            set.add(String.format(Locale.ENGLISH, "%02d:%02d", h, min));
        }
        return new ArrayList<>(set);
    }

    /** A time to say: "రేపు ఉదయం 6:00", "ఈరోజు రాత్రి 9:30", "గురువారం సాయంత్రం 5:00". */
    static String sayWhen(LocalDateTime at, LocalDateTime now) {
        long days = at.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay();
        String d = days == 0 ? "ఈరోజు" : days == 1 ? "రేపు" : days == 2 ? "ఎల్లుండి"
                : WEEKDAYS[at.getDayOfWeek().getValue() - 1][0] + " (" + at.getDayOfMonth() + "/" + at.getMonthValue() + ")";
        int h = at.getHour();
        String part = h < 5 ? "తెల్లవారుజామున" : h < 12 ? "ఉదయం" : h < 16 ? "మధ్యాహ్నం" : h < 19 ? "సాయంత్రం" : "రాత్రి";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return d + " " + part + " " + h12 + ":" + String.format(Locale.ENGLISH, "%02d", at.getMinute());
    }

    private static final Pattern REMIND_CMD = Pattern.compile(
            "(గుర్తు\\s*చేయండి|గుర్తు\\s*చెయ్యి|గుర్తు\\s*చేయి|గుర్తు\\s*చెయ్|గుర్తు\\s*చేయ్|గుర్తు\\s*చెయ్యండి|రిమైండర్\\s*పెట్టు|రిమైండర్\\s*పెట్టండి|రిమైండ్\\s*చేయి|రిమైండ్\\s*చెయ్యి|remind\\s*me)",
            Pattern.CASE_INSENSITIVE);

    /** What to remind him of: his words without the time and the command ("రేపు 6 కి పాలు తేవాలని గుర్తు చేయి" -> "పాలు తేవాలి"). */
    static String reminderText(String text) {
        String t = text == null ? "" : text;
        t = REMIND_CMD.matcher(t).replaceAll(" ");
        t = t.replaceAll("(అరగంట|గంటన్నర)\\s*(తర్వాత|తరువాత|లో)?", " ");
        t = MINUTES_AFTER.matcher(t).replaceAll(" ");
        t = HOURS_AFTER.matcher(t).replaceAll(" ");
        t = CLOCK.matcher(t).replaceAll(" ");
        t = HALF.matcher(t).replaceAll(" ");
        t = HOUR_AT.matcher(t).replaceAll(" ");
        t = DIGIT_KI.matcher(t).replaceAll(" ");
        t = t.replaceAll("(ఎల్లుండి|రేపు|ఈరోజు|ఈ రోజు|ఇవాళ|ఈవేళ|సోమవారం|మంగళవారం|బుధవారం|గురువారం|శుక్రవారం|శనివారం|ఆదివారం|"
                + "ఉదయం|పొద్దున|తెల్లవారుజామున|మధ్యాహ్నం|మధ్యాన్నం|సాయంత్రం|సాయంకాలం|రాత్రి|రోజూ|ప్రతిరోజూ|నాకు|ఒకసారి)", " ");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(కి|కు|కీ|గంటలకు|గంటలకి)(\\s+|$)", "").replaceAll("(^|\\s+)(కి|కు|కీ|గంటలకు|గంటలకి)$", "").trim(); // (left from "5:30 కి")
        t = t.replaceAll("(^అని\\s+|\\s+అని$|^అని$)", "").trim();
        if (t.endsWith("ాలని")) t = t.substring(0, t.length() - 3) + "లి"; // తేవాలని -> తేవాలి
        else if (t.endsWith("అని")) t = t.substring(0, t.length() - 3).trim();
        return t;
    }

    // ================================================================ SMS

    private static final Pattern SMS = Pattern.compile(
            "^(.+?)\\s*(?:కి|కు|కీ)\\s+(.+?)\\s+అని\\s+(?:ఒక\\s+)?(sms|ఎస్ఎంఎస్|ఎస్ ఎం ఎస్|మెసేజ్|మెసేజి|మెస్సేజ్|message|వాట్సాప్|whatsapp)\\s*"
                    + "(?:పంపు|పంపించు|పెట్టు|చెయ్యి|చేయి|చెయ్|పంపండి|పెట్టండి|చేయండి)?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);

    /** "అమ్మకి వస్తున్నా అని SMS పంపు" -> {"అమ్మ", "వస్తున్నా", "sms"}; the kind is "whatsapp" when he named it; null when it isn't that. */
    static String[] sms(String text) {
        if (text == null) return null;
        Matcher m = SMS.matcher(text.trim());
        if (!m.find()) return null;
        String who = m.group(1).trim(), msg = m.group(2).trim(), kind = m.group(3).toLowerCase(Locale.ROOT);
        if (who.isEmpty() || msg.isEmpty() || who.split("\\s+").length > 3) return null;
        return new String[]{who, msg, kind.contains("వాట్స") || kind.contains("whats") ? "whatsapp" : "sms"};
    }

    // ================================================================ money he gave / took

    private static final Pattern HE_PAID = Pattern.compile("(తిరిగి\\s*ఇచ్చాను|తిరిగి\\s*ఇచ్చేశాను|ఇచ్చేశాను|తీర్చేశాను|తీర్చాను|కట్టేశాను)");
    private static final Pattern PAID_HIM = Pattern.compile("(తిరిగి\\s*ఇచ్చాడు|తిరిగి\\s*ఇచ్చింది|తిరిగి\\s*ఇచ్చారు|ఇచ్చేశాడు|ఇచ్చేసింది|ఇచ్చేశారు|తీర్చేశాడు|తీర్చేసింది|తీర్చేశారు)");
    private static final Pattern LENT = Pattern.compile("(అప్పు\\s*ఇచ్చాను|అప్పుగా\\s*ఇచ్చాను|ఇచ్చాను)");
    private static final Pattern BORROWED = Pattern.compile("(అప్పు\\s*తీసుకున్నాను|అప్పుగా\\s*తీసుకున్నాను|తీసుకున్నాను)");

    /**
     * {action, kind, name, amount}: "రాముకి 500 ఇచ్చాను" add lent; "రాము దగ్గర 1000 తీసుకున్నాను" add borrowed;
     * "రాము 500 తిరిగి ఇచ్చాడు" paid lent; "రాముకి 500 తిరిగి ఇచ్చాను" paid borrowed. null when it isn't that.
     */
    static String[] debt(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.contains("ఖర్చు") || t.toLowerCase(Locale.ROOT).contains("expense")) return null;
        double amt = money(t);
        if (amt <= 0) return null;
        String name;
        if (HE_PAID.matcher(t).find() && hasTo(t)) {
            name = nameBefore(t, TO);
            return name == null ? null : new String[]{"paid", "borrowed", name, fmt(amt)};
        }
        if (PAID_HIM.matcher(t).find()) {
            name = firstName(t);
            return name == null ? null : new String[]{"paid", "lent", name, fmt(amt)};
        }
        if (BORROWED.matcher(t).find() && (t.contains("దగ్గర") || t.contains("నుంచి") || t.contains("నుండి"))) {
            name = nameBefore(t, "\\s*(దగ్గరి|దగ్గర|నుంచి|నుండి)");
            return name == null ? null : new String[]{"add", "borrowed", name, fmt(amt)};
        }
        if (LENT.matcher(t).find() && hasTo(t)) {
            name = nameBefore(t, TO);
            if (name != null && !t.contains("అప్పు") && category(name) != null) return null; // "ఆటోకి 50 ఇచ్చాను": spent, not lent
            return name == null ? null : new String[]{"add", "lent", name, fmt(amt)};
        }
        return null;
    }

    /** "రాముకి" / "రాము కి": the name ends before it. */
    private static final String TO = "(?:కి|కు)(?:\\s|$)";

    private static boolean hasTo(String t) {
        for (String w : t.split("\\s+")) if (w.length() > 2 && (w.endsWith("కి") || w.endsWith("కు")) && token(w) == null) return true;
        return t.contains(" కి ") || t.contains(" కు ");
    }

    private static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "నేను", "నాకు", "నా", "మా", "నిన్న", "ఈరోజు", "ఇవాళ", "రేపు", "మొన్న"));

    /** The name before the first marker ("రాముకి 500" / "రాము కి 500" / "రాము దగ్గర"), without words like నిన్న. */
    private static String nameBefore(String t, String marker) {
        Matcher m = Pattern.compile("^(.+?)(?:" + marker + ")").matcher(t);
        if (!m.find()) return null;
        return cleanName(m.group(1));
    }

    private static String firstName(String t) {
        String[] w = t.split("\\s+");
        List<String> keep = new ArrayList<>();
        for (String x : w) {
            if (token(x) != null) break; // up to the amount
            keep.add(x);
        }
        return cleanName(String.join(" ", keep));
    }

    private static String cleanName(String s) {
        List<String> keep = new ArrayList<>();
        for (String x : s.trim().split("\\s+")) if (!x.isEmpty() && !NOT_NAMES.contains(x)) keep.add(x);
        String n = String.join(" ", keep).trim();
        if (n.endsWith("కి") || n.endsWith("కు")) n = n.substring(0, n.length() - 2).trim();
        return n.isEmpty() || n.split("\\s+").length > 3 ? null : n;
    }

    // ================================================================ expenses

    private static final Pattern WRITE = Pattern.compile("(రాయి|రాసుకో|రాయండి|రాసుకోండి|రాసి\\s*పెట్టు|నోట్\\s*చేయి|నోట్\\s*చెయ్|పెట్టు|ఆడ్|యాడ్|\\badd\\b|అయింది|అయ్యింది|అయ్యాయి|పెట్టాను|చేశాను)", Pattern.CASE_INSENSITIVE);
    private static final String[][] CATEGORY = {
            {"fuel", "పెట్రోల్", "పెట్రోలు", "డీజిల్", "petrol", "diesel"},
            {"charging", "ఛార్జింగ్", "చార్జింగ్", "ఛార్జ్", "చార్జ్", "charging"},
            {"groceries", "కూరగాయ", "సరుకు", "కిరాణా", "పాలు", "పండ్లు", "బియ్యం", "గుడ్లు", "grocer"},
            {"food", "భోజనం", "టిఫిన్", "హోటల్", "టీ", "కాఫీ", "బిర్యానీ", "చికెన్", "మటన్", "food", "lunch", "dinner"},
            {"medicine", "మందు", "మాత్ర", "మెడికల్", "ఆసుపత్రి", "హాస్పిటల్", "డాక్టర్", "medicine", "hospital"},
            {"bills", "బిల్", "బిల్లు", "కరెంట్", "రీఛార్జ్", "రీచార్జ్", "అద్దె", "recharge", "bill", "rent"},
            {"travel", "బస్", "బస్సు", "ఆటో", "ట్రైన్", "రైలు", "టికెట్", "క్యాబ్", "bus", "auto", "train", "ticket"},
            {"shopping", "బట్టలు", "చెప్పులు", "షాపింగ్", "shopping"}};

    /** What a thing was spent on (fuel, food...) by its own words ("పెట్రోల్‌కి", not "గోపాలు"), or null. */
    static String category(String text) {
        for (String raw : (text == null ? "" : text.toLowerCase(Locale.ROOT)).split("[\\s,.!?]+")) {
            String w0 = raw.replace("\u200c", "");
            String w1 = w0.replaceAll("(కోసం|కి|కు|లో|తో|ని)$", ""); // పెట్రోల్‌కి, పాలకి (but సరుకు stays సరుకు)
            for (String w : new String[]{w0, w1}) {
                if (w.length() < 2) continue;
                for (String[] c : CATEGORY) {
                    for (int i = 1; i < c.length; i++) {
                        String k = c[i];
                        String stem = k.length() >= 3 && (k.endsWith("ు") || k.endsWith("ి")) ? k.substring(0, k.length() - 1) : null; // పాలు -> పాల
                        if (w.equals(k) || k.length() >= 4 && w.startsWith(k) || stem != null && w.equals(stem)) return c[0];
                    }
                }
            }
        }
        return null;
    }

    /** {amount, what, category} for "పెట్రోల్‌కి 200 ఖర్చు రాయి"; null when it isn't writing an expense. */
    static String[] expense(String text) {
        if (text == null) return null;
        String t = text.trim();
        boolean spend = t.contains("ఖర్చు") || t.toLowerCase(Locale.ROOT).contains("expense");
        String cat = category(t);
        if (t.contains("నోట్") || t.contains("డైరీ") || t.contains("లిస్ట్")) return null;
        // "పెట్రోల్‌కి 200 ఖర్చు రాయి", "ఆటోకి 50 ఇచ్చాను", "కరెంట్ బిల్ 800 కట్టాను"
        boolean moneyWord = Pattern.compile("(రూపాయ|రూ\\.|₹|\\brs\\b|\\brupees?\\b)", Pattern.CASE_INSENSITIVE).matcher(t).find();
        boolean paid = cat != null && (Pattern.compile("(ఇచ్చాను|కట్టాను|కట్టేశాను)").matcher(t).find()
                || moneyWord && Pattern.compile("(పెట్టాను|అయింది|అయ్యింది)").matcher(t).find());
        if (!(spend && WRITE.matcher(t).find()) && !paid && !(cat != null && moneyWord && t.contains("రాసుకో"))) return null;
        double amt = money(t);
        if (amt <= 0) return null;
        if (cat == null) cat = "other";
        List<String> keep = new ArrayList<>();
        for (String w : t.split("\\s+")) {
            String x = w.replaceAll("[.,!?₹]", "");
            if (x.isEmpty() || token(x) != null) continue;
            if (x.matches("(ఖర్చు|ఖర్చులో|ఖర్చుగా|రూపాయలు|రూపాయల|రూపాయి|రూ|rs|Rs|ఖర్చు.*|అని|నా|ఈరోజు|ఇవాళ|నిన్న|కి|కు|లో|ఇచ్చాను|కట్టాను|కట్టేశాను|పెట్టాను|నేను)")) continue;
            if (WRITE.matcher(x).matches()) continue;
            keep.add(x.replaceAll("\u200c?(కి|కు)$", ""));
        }
        String what = String.join(" ", keep).replaceAll("\\s+", " ").trim();
        return new String[]{fmt(amt), what, cat};
    }

    // ================================================================ timers, medicines, numbers never kept

    /** A timer's length in seconds ("2 నిమిషాల 30 సెకన్లు" = 150, "గంటన్నర" = 5400), or -1. */
    static int seconds(String text) {
        String t = text == null ? "" : text;
        if (t.contains("గంటన్నర")) return 5400;
        if (t.contains("అరగంట")) return 1800;
        double total = 0;
        boolean any = false;
        Matcher m = Pattern.compile("(\\S+)\\s*(గంటల|గంట|hours?|నిమిషాల|నిమిషాలు|నిమిషం|నిమిషాల్లో|minutes?|mins?|సెకన్ల|సెకన్లు|సెకను|సెకండ్లు|సెకన్|seconds?|secs?)",
                Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) {
            double n = number(m.group(1));
            if (n <= 0) continue;
            String u = m.group(2).toLowerCase(Locale.ROOT);
            total += n * (u.startsWith("గంట") || u.startsWith("hour") ? 3600 : u.startsWith("సెక") || u.startsWith("sec") ? 1 : 60);
            any = true;
        }
        if (!any) {
            double n = number(t); // "5 టైమర్": minutes
            if (n > 0) return (int) Math.round(n * 60);
            if (t.contains("గంట") || t.toLowerCase(Locale.ROOT).contains("hour")) return 3600; // "గంట టైమర్"
            if (t.contains("నిమిషం") || t.toLowerCase(Locale.ROOT).contains("minute")) return 60;
            return -1;
        }
        return (int) Math.round(total);
    }

    private static final java.util.Set<String> MED_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "మాత్ర", "మాత్రలు", "మాత్రను", "మాత్రల", "మందు", "మందులు", "మందుల", "మందును", "టాబ్లెట్", "టాబ్లెట్లు", "టాబ్లెట్స్",
            "tablet", "tablets", "medicine", "medicines"));

    /** A medicine word on its own ("మాత్ర", not "మాత్రమే"). */
    static boolean medicineWord(String text) {
        for (String w : (text == null ? "" : text.toLowerCase(Locale.ROOT)).split("[\\s,.!?]+")) if (MED_WORDS.contains(w)) return true;
        return false;
    }

    private static final java.util.Set<String> NOT_MED_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "కి", "కు", "కీ", "లో", "ని", "నా", "ఆ", "ఈ", "ఒక", "రేపు", "ఈరోజు", "ఇవాళ", "రోజూ", "ప్రతిరోజూ", "ఉదయం", "రాత్రి", "సాయంత్రం",
            "మధ్యాహ్నం", "పొద్దున", "నాకు", "నేను", "అని", "గుర్తు", "కొనాలి", "కొనాలని", "తేవాలి", "తేవాలని"));

    /** The medicine's name: the words before మాత్ర / మందు / టాబ్లెట్ ("BP మాత్ర", "Dolo 650 మాత్ర"), or "" when there is none. */
    static String medicineName(String text) {
        String[] w = (text == null ? "" : text).trim().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            if (!MED_WORDS.contains(w[i].toLowerCase(Locale.ROOT))) continue;
            List<String> name = new ArrayList<>();
            for (int j = i - 1; j >= 0 && name.size() < 2; j--) {
                String x = w[j];
                if (NOT_MED_NAMES.contains(x) || DIGIT_KI.matcher(x).matches() || CLOCK.matcher(x).matches()) break;
                name.add(0, x);
            }
            boolean letters = false;
            for (String x : name) if (x.matches(".*[\\p{L}].*") && token(x) == null) letters = true;
            return letters ? String.join(" ", name) : "";
        }
        return "";
    }

    private static final Pattern SECRET = Pattern.compile("(?<![\\p{L}\\p{M}])(అకౌంట్\\s*నంబర్|అకౌంట్\\s*నెంబర్|account\\s*number|కార్డ్\\s*నంబర్|card\\s*number|సీవీవీ|cvv|"
            + "పిన్\\s*నంబర్|పిన్|pin|ఓటీపీ|otp|పాస్‌వర్డ్|పాస్ వర్డ్|password|ఆధార్\\s*నంబర్|ఆధార్|aadhaar|aadhar|పాన్\\s*కార్డ్|pan\\s*card|"
            + "పాలసీ\\s*నంబర్|policy\\s*number|లైసెన్స్\\s*నంబర్|licen[cs]e\\s*number)(?![\\p{L}\\p{M}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEEP_IT = Pattern.compile("(\\d{4,}|రాసుకో|రాయి|గుర్తుంచుకో|గుర్తు\\s*పెట్టుకో|నోట్|సేవ్|save|పెట్టుకో|remember)", Pattern.CASE_INSENSITIVE);

    /**
     * An account / card / ID number, a PIN, an OTP or a password with the number itself or "write it down": Jarvis never
     * writes these down or keeps them. ("ఆధార్ సెంటర్ కి వెళ్ళాలి" and పిన్ని are not.)
     */
    static boolean secret(String text) { return text != null && SECRET.matcher(text).find() && KEEP_IT.matcher(text).find(); }

    // ================================================================ names (contacts saved in English)

    private static final Map<Character, String> CONS = new HashMap<>(), VOWEL = new HashMap<>(), SIGN = new HashMap<>();
    static {
        String[] c = {"క", "k", "ఖ", "kh", "గ", "g", "ఘ", "gh", "ఙ", "n", "చ", "ch", "ఛ", "chh", "జ", "j", "ఝ", "jh", "ఞ", "n",
                "ట", "t", "ఠ", "th", "డ", "d", "ఢ", "dh", "ణ", "n", "త", "t", "థ", "th", "ద", "d", "ధ", "dh", "న", "n",
                "ప", "p", "ఫ", "ph", "బ", "b", "భ", "bh", "మ", "m", "య", "y", "ర", "r", "ల", "l", "వ", "v", "శ", "sh",
                "ష", "sh", "స", "s", "హ", "h", "ళ", "l", "ఱ", "r"};
        for (int i = 0; i < c.length; i += 2) CONS.put(c[i].charAt(0), c[i + 1]);
        String[] v = {"అ", "a", "ఆ", "aa", "ఇ", "i", "ఈ", "ee", "ఉ", "u", "ఊ", "oo", "ఋ", "ru", "ఎ", "e", "ఏ", "e", "ఐ", "ai", "ఒ", "o", "ఓ", "o", "ఔ", "au"};
        for (int i = 0; i < v.length; i += 2) VOWEL.put(v[i].charAt(0), v[i + 1]);
        String[] s = {"ా", "aa", "ి", "i", "ీ", "ee", "ు", "u", "ూ", "oo", "ృ", "ri", "ె", "e", "ే", "e", "ై", "ai", "ొ", "o", "ో", "o", "ౌ", "au"};
        for (int i = 0; i < s.length; i += 2) SIGN.put(s[i].charAt(0), s[i + 1]);
    }

    /** Telugu letters in English letters, as a name is usually saved: "రాము" -> "raamu", "శ్రీను" -> "shreenu". */
    static String latin(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            String cons = CONS.get(ch);
            if (cons != null) {
                b.append(cons);
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (next == '్') { i++; continue; }
                String sign = SIGN.get(next);
                if (sign != null) { b.append(sign); i++; continue; }
                b.append('a');
                continue;
            }
            String vw = VOWEL.get(ch);
            if (vw != null) { b.append(vw); continue; }
            if (ch == 'ం') { // n before most letters ("వెంకటేష్" = Venkatesh), m before p / b / m and at the end
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                b.append(next == 'ప' || next == 'బ' || next == 'భ' || next == 'మ' || next == 'ఫ' || CONS.get(next) == null ? 'm' : 'n');
                continue;
            }
            if (ch == 'ః') { b.append('h'); continue; }
            if (ch == 'ఁ') { b.append('n'); continue; }
            if (ch == '్' || ch == '‌' || ch == '‍') continue;
            b.append(ch);
        }
        return b.toString();
    }

    /** A name squeezed so that spellings of the same sound meet: "Sreenu", "Srinu", "శ్రీను" -> "srinu". */
    static String skeleton(String s) {
        String t = latin(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        t = t.replace("chh", "c").replace("ch", "c").replace("sh", "s").replace("th", "t").replace("dh", "d").replace("bh", "b")
                .replace("kh", "k").replace("gh", "g").replace("jh", "j").replace("ph", "p").replace("f", "p").replace("w", "v")
                .replace("z", "j").replace("q", "k").replace("x", "ks");
        t = t.replace("ee", "i").replace("oo", "u").replace("aa", "a").replace("ai", "ay").replace("au", "av");
        t = t.replaceAll("(.)\\1+", "$1"); // doubled letters
        t = t.replaceAll("([aeiou])h$", "$1"); // "Somaiah" = "Somaya"
        return t;
    }

    /** What family words are usually saved as. */
    static String[] aliases(String who) {
        String w = who == null ? "" : who.trim();
        switch (w) {
            case "అమ్మ": return new String[]{"amma", "mom", "mummy", "mother", "maa"};
            case "నాన్న": case "నాన్నగారు": return new String[]{"nanna", "dad", "daddy", "father", "papa"};
            case "అన్న": case "అన్నయ్య": return new String[]{"anna", "annayya", "brother", "bro"};
            case "అక్క": return new String[]{"akka", "sister"};
            case "తమ్ముడు": return new String[]{"thammudu", "tammudu", "brother"};
            case "చెల్లి": case "చెల్లెలు": return new String[]{"chelli", "sister"};
            case "భార్య": case "వైఫ్": case "ఆవిడ": return new String[]{"wife"};
            case "తాత": return new String[]{"thatha", "tata", "grandpa"};
            case "అమ్మమ్మ": case "నాయనమ్మ": return new String[]{"ammamma", "nayanamma", "grandma"};
            default: return new String[0];
        }
    }

    /**
     * How well a saved contact name fits what he said (0 = not at all): the whole name sounds the same 3, its first
     * word 2, any word 1; a family word ("అమ్మ") matching what it is saved as 3.
     */
    static int fit(String said, String saved) {
        if (said == null || saved == null) return 0;
        String low = saved.toLowerCase(Locale.ROOT).trim();
        for (String a : aliases(said)) if (low.equals(a) || low.startsWith(a + " ")) return 3;
        String s = skeleton(said);
        if (s.length() < 2) return 0;
        String full = skeleton(saved);
        if (full.equals(s)) return 3;
        String[] words = saved.trim().split("\\s+");
        if (words.length > 0 && skeleton(words[0]).equals(s)) return 2;
        for (String w : words) if (skeleton(w).equals(s)) return 1;
        return 0;
    }

    // ================================================================ questions kept for when the internet is back

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_offline", Context.MODE_PRIVATE); }
    private static final long KEEP_MS = 12 * 3600_000L;

    /** Keeps a question that needs the internet (the newest 5). */
    static void keep(Context c, String q) { keep(c, q, System.currentTimeMillis(), 0); }

    /** (Again after a failed try: its first time stays, so it still runs out after 12 hours; at most 3 tries.) */
    private static synchronized void keep(Context c, String q, long t, int tries) {
        if (tries >= 3 || secret(q)) return;
        try {
            JSONArray old = new JSONArray(sp(c).getString("waiting", "[]")), out = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (now - o.optLong("t") < KEEP_MS && !o.optString("q").equals(q)) out.put(o);
            }
            out.put(new JSONObject().put("q", q).put("t", t).put("tries", tries));
            while (out.length() > 5) out.remove(0);
            sp(c).edit().putString("waiting", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The kept questions (taken out). */
    static synchronized List<JSONObject> take(Context c) {
        List<JSONObject> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("waiting", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) if (now - a.getJSONObject(i).optLong("t") < KEEP_MS) l.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        sp(c).edit().remove("waiting").apply();
        return l;
    }

    static boolean anyWaiting(Context c) { return sp(c).getString("waiting", "[]").length() > 2; }

    private static final java.util.concurrent.atomic.AtomicBoolean answering = new java.util.concurrent.atomic.AtomicBoolean();

    /** Jarvis is in a talk with him now (the app or the panel). */
    private static boolean talking() { return MainActivity.busyTalking() || SheetActivity.talkingNow() || SheetActivity.open; }
    private static volatile boolean retrySet;

    /** The internet is back (validated): his kept questions are answered by his brain and told (spoken and in a notification). */
    static void netBack(Context c) {
        online();
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!anyWaiting(app)) return;
        long wait = 10 * 60_000L - (now - sp(app).getLong("last_try", 0));
        if (wait > 0) { // tried a moment ago: once more when the wait is over (if the internet is still there)
            if (!retrySet) {
                retrySet = true;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    retrySet = false;
                    if (Net.online(app)) netBack(app);
                }, wait + 1000);
            }
            return;
        }
        if (!answering.compareAndSet(false, true)) return;
        sp(app).edit().putLong("last_try", now).apply();
        new Thread(() -> {
            try {
                Thread.sleep(3000); // (the line settles)
                Prefs p = new Prefs(app);
                if (!p.hasBrain() || !Net.online(app)) return;
                for (JSONObject o : take(app)) {
                    String q = o.optString("q");
                    String asked = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(o.optLong("t")));
                    String a;
                    try {
                        a = Brain.oneShot(p, "You are Jarvis, " + p.realName() + "'s assistant on his phone. At " + asked + " the phone had no internet and he asked "
                                + "the question below; the internet is back now, so answer it now: in Telugu as spoken to him, 2-4 short sentences, with today's "
                                + "facts (search the web for news, weather, prices or anything current). If it asks to do something on the phone, just say "
                                + "that he can ask you again now. No lists, no links.", q, null, true, 700);
                    } catch (Exception e) {
                        keep(app, q, o.optLong("t"), o.optInt("tries") + 1); // try again the next time the internet comes back
                        continue;
                    }
                    a = Emotion.strip(a == null ? "" : a).trim();
                    if (a.isEmpty()) continue;
                    String say = "నెట్ వచ్చింది. మీరు అడిగిన \"" + q + "\" కి జవాబు: " + a;
                    Store.get(app).addChat("assistant", say, false);
                    // not over a talk with Jarvis: wait for it to end (a while), else only the notification
                    long until = System.currentTimeMillis() + 120_000;
                    while (talking() && System.currentTimeMillis() < until) Thread.sleep(2000);
                    android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
                    boolean quiet = talking() || CallControl.busyWithCall()
                            || nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
                    if (quiet) Reminders.notify(app, "Jarvis", say, say.hashCode());
                    else Proactive.say(app, say, null, null); // (spoken, and a notification)
                }
            } catch (Exception ignored) {
            } finally {
                answering.set(false);
            }
        }, "jarvis-offline-later").start();
    }
}
