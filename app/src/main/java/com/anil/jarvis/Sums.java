package com.anil.jarvis;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sums Jarvis does on the phone, with or without internet (plain Java, tested on the desk):
 *   O24 dates: "40 రోజుల తర్వాత ఏ తేదీ?", "దీపావళికి ఇంకా ఎన్ని రోజులు?" (dates said as words or numbers), "15 ఆగస్టు 2027 ఏ వారం?",
 *       "జనవరి 1 నుంచి ఎన్ని రోజులు అయింది?", "1 తారీఖుకి ఇంకా ఎన్ని రోజులు?"
 *   O33 money: EMI ("5 లక్షలకు 10 శాతం వడ్డీ 3 ఏళ్లకు EMI ఎంత?"), interest the village way ("లక్షకు 2 రూపాయల వడ్డీ 6 నెలలకు")
 *       or in percent (simple / చక్రవడ్డీ), GST added or taken out ("1180 లో GST ఎంత?")
 *   O33 units: km / miles, feet + inches / cm, kg / pounds, °C / °F (fever), land (ఎకరం, సెంటు, గుంట, గజాలు, sq ft).
 * Each returns what to say, or null when the words aren't that sum.
 */
final class Sums {
    private Sums() {}

    // ================================================================ numbers to say

    /** Indian grouping: 112000 -> "1,12,000"; 2.5 -> "2.5". */
    static String num(double v) {
        boolean neg = v < 0;
        v = Math.abs(v);
        long whole = (long) Math.floor(v + 1e-9);
        double frac = v - whole;
        String w = String.valueOf(whole);
        StringBuilder b = new StringBuilder();
        if (w.length() > 3) {
            String head = w.substring(0, w.length() - 3), tail = w.substring(w.length() - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) h.insert(0, (i - 2 > 0 ? "," : "") + head.substring(Math.max(0, i - 2), i));
            b.append(h).append(',').append(tail);
        } else b.append(w);
        if (frac >= 0.005) {
            String f = String.format(Locale.ENGLISH, "%.2f", frac).substring(1).replaceAll("0+$", "");
            if (f.equals(".")) f = "";
            if (f.equals(".1") && frac >= 0.995) f = ""; // (rounding)
            b.append(f);
        }
        return (neg ? "-" : "") + b;
    }

    /** Rupees, rounded: "₹1,12,000". */
    static String rupees(double v) { return "₹" + num(Math.round(v)); }

    // ================================================================ dates

    private static final String[][] MONTHS = {
            {"జనవరి", "january", "jan"}, {"ఫిబ్రవరి", "ఫిబ్రవరీ", "february", "feb"}, {"మార్చి", "మార్చ్", "march", "mar"},
            {"ఏప్రిల్", "ఏప్రిల", "april", "apr"}, {"మే", "may"}, {"జూన్", "june", "jun"}, {"జూలై", "జులై", "july", "jul"},
            {"ఆగస్టు", "ఆగష్టు", "ఆగస్ట్", "august", "aug"}, {"సెప్టెంబర్", "సెప్టెంబరు", "september", "sept", "sep"},
            {"అక్టోబర్", "అక్టోబరు", "october", "oct"}, {"నవంబర్", "నవంబరు", "november", "nov"}, {"డిసెంబర్", "డిసెంబరు", "december", "dec"}};
    static final String[] MONTH_TE = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};
    static final String[] WEEKDAY_TE = {"సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం", "ఆదివారం"};
    /** Endings a month or a day can carry ("డిసెంబర్‌లో", "25న", "జనవరి 1 నుంచి"). Longest first. */
    private static final String[] D_ENDINGS = {"నుంచి", "నుండి", "వరకు", "దాకా", "లోపు", "కల్లా", "కి", "కు", "కీ", "న", "లో", "తో", "ని", "ది"};

    /** "డిసెంబర్‌లో" -> 12; 0 when it isn't a month. */
    private static int month(String tok) {
        String t = tok.toLowerCase(Locale.ROOT);
        for (int pass = 0; pass < 2; pass++) {
            for (int m = 0; m < 12; m++) for (String n : MONTHS[m]) if (t.equals(n)) return m + 1;
            if (pass == 0) { // with an ending
                String base = strip(t);
                if (base.equals(t)) break;
                t = base;
            }
        }
        return 0;
    }

    /** "డిసెంబర్‌లో" is a month's name ("మేనేజర్" is not). */
    static boolean isMonth(String tok) { return tok != null && month(tok.replace("\u200c", "")) != 0; }

    private static String strip(String t) {
        for (String e : D_ENDINGS) if (t.length() > e.length() + 1 && t.endsWith(e)) return t.substring(0, t.length() - e.length());
        return t;
    }

    /** "25న" / "25" / "1st" -> 25 / 1; -1 when it isn't a day of the month or a year. */
    private static int plain(String tok) {
        Matcher m = Pattern.compile("^(\\d{1,4})(st|nd|rd|th|వ|వది|వ తేదీ)?$").matcher(strip(tok.toLowerCase(Locale.ROOT)));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static final Pattern NUMERIC = Pattern.compile("(?<![\\d:.])(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?(?![\\d:])|(?<![\\d:.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)");
    private static final Pattern DOM = Pattern.compile("(?<![\\d:.])(\\d{1,2})\\s*(?:వ\\s*)?(తారీఖు|తారీకు|తారీఖ|తేదీ)");

    /** A date he named: day, month (0: not said, "1 తారీఖు"), year (0: not said). */
    static final class Said {
        final int d, m, y;
        Said(int d, int m, int y) { this.d = d; this.m = m; this.y = y; }
    }

    /** The dates in his words, in order: "25/12/2026", "25 డిసెంబర్", "డిసెంబర్ 25, 2026", "2027 జనవరి 10", "1 తారీఖు". */
    static List<Said> dates(String text) {
        List<Said> out = new ArrayList<>();
        String t = norm(text);
        Matcher n = NUMERIC.matcher(t);
        StringBuffer rest = new StringBuffer();
        while (n.find()) {
            // "1-2 రోజుల్లో", "10/15 నిమిషాలు": counts, not dates
            if (n.group(1) != null && n.group(3) == null
                    && t.substring(n.end()).matches("\\s*(రోజు|నిమిష|గంట|వార|నెల|ఏళ్|సార్లు|కిలో|లీటర్|రూపా|మంది|days|mins|hours)(?s).*")) continue;
            int d, m, y;
            if (n.group(1) != null) { d = Integer.parseInt(n.group(1)); m = Integer.parseInt(n.group(2)); y = n.group(3) == null ? 0 : year(n.group(3)); }
            else { d = Integer.parseInt(n.group(4)); m = Integer.parseInt(n.group(5)); y = Integer.parseInt(n.group(6)); }
            if (d >= 1 && d <= 31 && m >= 1 && m <= 12) out.add(new Said(d, m, y));
            n.appendReplacement(rest, " ");
        }
        n.appendTail(rest);
        String[] w = rest.toString().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            int mo = month(w[i]);
            if (mo == 0) continue;
            int d = -1, y = 0;
            int before = i > 0 ? plain(w[i - 1]) : -1, after = i + 1 < w.length ? plain(w[i + 1]) : -1;
            int after2 = i + 2 < w.length ? plain(w[i + 2]) : -1, before2 = i > 1 ? plain(w[i - 2]) : -1;
            if (before >= 1 && before <= 31) { d = before; if (after >= 1900 && after <= 2100) y = after; else if (before2 >= 1900 && before2 <= 2100) y = before2; }
            else if (after >= 1 && after <= 31) { d = after; if (after2 >= 1900 && after2 <= 2100) y = after2; else if (before >= 1900 && before <= 2100) y = before; }
            if (d < 1) continue; // ("మే" alone, a month without a day: not a date)
            out.add(new Said(d, mo, y));
        }
        if (out.isEmpty()) {
            Matcher dm = DOM.matcher(t);
            if (dm.find()) {
                int d = Integer.parseInt(dm.group(1));
                if (d >= 1 && d <= 31) out.add(new Said(d, 0, 0));
            }
        }
        return out;
    }

    private static int year(String s) {
        int y = Integer.parseInt(s);
        return y < 100 ? 2000 + y : y;
    }

    private static final String[][] ORDINALS = {{"ఒకటో", "1"}, {"మొదటి", "1"}, {"రెండో", "2"}, {"రెండవ", "2"}, {"మూడో", "3"}, {"మూడవ", "3"},
            {"నాలుగో", "4"}, {"నాలుగవ", "4"}, {"ఐదో", "5"}, {"ఐదవ", "5"}, {"ఆరో", "6"}, {"ఆరవ", "6"}, {"ఏడో", "7"}, {"ఏడవ", "7"},
            {"ఎనిమిదో", "8"}, {"ఎనిమిదవ", "8"}, {"తొమ్మిదో", "9"}, {"తొమ్మిదవ", "9"}, {"పదో", "10"}, {"పదవ", "10"}};

    private static String norm(String text) {
        String s = text == null ? "" : text;
        for (String[] o : ORDINALS) s = s.replaceAll("(^|\\s)" + o[0] + "(?=\\s*(తారీఖు|తారీకు|తేదీ))", "$1" + o[1] + " ");
        String t = Offline.digits(s).replace("‌", "").replace("‍", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("(\\d)(st|nd|rd|th)\\b", "$1");
        return t.replaceAll("[,!?।]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * The real day: a year not said is the next such day (dir > 0, "ఇంకా ఎన్ని రోజులు") or the last one (dir < 0,
     * "నుంచి ఎన్ని రోజులు అయింది"); "1 తారీఖు" is this month's or the next / last month's. null for a day that doesn't exist.
     */
    static LocalDate resolve(Said s, LocalDate today, int dir) {
        try {
            if (s.m == 0) {
                LocalDate d = safe(today.getYear(), today.getMonthValue(), s.d);
                if (dir >= 0 && d.isBefore(today)) d = safe(today.plusMonths(1).getYear(), today.plusMonths(1).getMonthValue(), s.d);
                if (dir < 0 && d.isAfter(today)) d = safe(today.minusMonths(1).getYear(), today.minusMonths(1).getMonthValue(), s.d);
                return d;
            }
            if (s.y > 0) return LocalDate.of(s.y, s.m, s.d);
            LocalDate d = LocalDate.of(today.getYear(), s.m, Math.min(s.d, java.time.YearMonth.of(today.getYear(), s.m).lengthOfMonth()));
            if (s.d > java.time.YearMonth.of(today.getYear(), s.m).lengthOfMonth() && !(s.m == 2 && s.d == 29)) return null;
            if (dir >= 0 && d.isBefore(today)) d = LocalDate.of(today.getYear() + 1, s.m, Math.min(s.d, java.time.YearMonth.of(today.getYear() + 1, s.m).lengthOfMonth()));
            if (dir < 0 && d.isAfter(today)) d = LocalDate.of(today.getYear() - 1, s.m, Math.min(s.d, java.time.YearMonth.of(today.getYear() - 1, s.m).lengthOfMonth()));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** The day of a month (31 in a 30-day month is its last day). */
    private static LocalDate safe(int y, int m, int d) {
        return LocalDate.of(y, m, Math.min(d, java.time.YearMonth.of(y, m).lengthOfMonth()));
    }

    /** "గురువారం 25 డిసెంబర్ 2026". */
    static String say(LocalDate d) {
        return WEEKDAY_TE[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + " " + MONTH_TE[d.getMonthValue() - 1] + " " + d.getYear();
    }

    /** 400 -> "400 రోజులు (1 ఏడాది 1 నెల 4 రోజులు)" — the long form only when it helps. */
    private static String span(LocalDate from, LocalDate to) {
        long days = Math.abs(ChronoUnit.DAYS.between(from, to));
        String s = days + (days == 1 ? " రోజు" : " రోజులు");
        if (days < 14) return s;
        LocalDate a = from.isBefore(to) ? from : to, b = from.isBefore(to) ? to : from;
        if (days < 60) return s + " (" + days / 7 + " వారాలు" + (days % 7 == 0 ? "" : " " + days % 7 + " రోజులు") + ")";
        Period p = Period.between(a, b);
        StringBuilder x = new StringBuilder();
        if (p.getYears() > 0) x.append(p.getYears()).append(p.getYears() == 1 ? " ఏడాది " : " ఏళ్లు ");
        if (p.getMonths() > 0) x.append(p.getMonths()).append(p.getMonths() == 1 ? " నెల " : " నెలలు ");
        if (p.getDays() > 0) x.append(p.getDays()).append(p.getDays() == 1 ? " రోజు" : " రోజులు");
        return s + " (" + x.toString().trim() + ")";
    }

    private static final Pattern AFTER = Pattern.compile(
            "(\\S+)\\s*(రోజుల్లో|రోజులలో|వారాల్లో|వారాలలో|నెలల్లో|నెలలలో|ఏళ్లలో|ఏళ్ళలో|సంవత్సరాల్లో|"
                    + "(?:రోజుల|రోజులు|రోజు|వారాల|వారాలు|వారం|నెలల|నెలలు|నెల|ఏళ్ల|ఏళ్ళ|ఏళ్లు|ఏళ్ళు|సంవత్సరాల|సంవత్సరాలు|సంవత్సరం|days?|weeks?|months?|years?)"
                    + "\\s*(?:తర్వాత|తరువాత|after|later|ముందు|క్రితం|కిందట|before|ago))", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHICH_DAY = Pattern.compile("(ఏ\\s*తేదీ|ఏ\\s*తారీఖు|ఏం\\s*తేదీ|ఏమి\\s*తేదీ|తేదీ\\s*ఎంత|ఏ\\s*రోజు|ఏ\\s*వారం|ఏం\\s*వారం|ఏమి\\s*వారం|ఎప్పుడు|what\\s*date|which\\s*date|what\\s*day|which\\s*day)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOW_MANY_DAYS = Pattern.compile("(ఎన్ని\\s*రోజులు|ఎన్ని\\s*రోజుల|ఎన్నిరోజులు|ఎంత\\s*కాలం|ఎన్ని\\s*వారాలు|ఎన్ని\\s*నెలలు|ఎన్నేళ్లు|ఎన్ని\\s*ఏళ్లు|ఎన్ని\\s*సంవత్సరాలు|how\\s*many\\s*days|how\\s*long)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINCE = Pattern.compile("(నుంచి|నుండి|since|అయింది|అయ్యింది|అయ్యాయి|అయింది\\?|గడిచింది|గడిచాయి)", Pattern.CASE_INSENSITIVE);

    /** He asks about days (how many until / since, which day of the week, N days after): the answer, or null. */
    static String dates(String text, LocalDate today) {
        if (text == null) return null;
        String t = norm(text);
        boolean howMany = HOW_MANY_DAYS.matcher(t).find(), which = WHICH_DAY.matcher(t).find();
        // N days / weeks / months / years from now (or from a date he names)
        Matcher a = AFTER.matcher(t);
        if (a.find() && (which || t.contains("వస్తుంది") || t.contains("అవుతుంది"))) {
            double n = Offline.number(a.group(1));
            if (n > 0 && n <= 100000 && n == Math.floor(n)) {
                String u = a.group(2).toLowerCase(Locale.ROOT);
                boolean back = u.contains("ముందు") || u.contains("క్రితం") || u.contains("కిందట") || u.contains("before") || u.contains("ago");
                List<Said> ds = dates(t.substring(0, a.start()));
                LocalDate base = ds.isEmpty() ? today : resolve(ds.get(0), today, 0);
                if (base == null) base = today;
                long k = (long) n * (back ? -1 : 1);
                LocalDate d = u.startsWith("వార") || u.startsWith("week") ? base.plusWeeks(k)
                        : u.startsWith("నెల") || u.startsWith("month") ? base.plusMonths(k)
                        : u.startsWith("ఏళ్") || u.startsWith("సంవ") || u.startsWith("year") ? base.plusYears(k) : base.plusDays(k);
                String unit = u.startsWith("వార") || u.startsWith("week") ? "వారాల" : u.startsWith("నెల") || u.startsWith("month") ? "నెలల"
                        : u.startsWith("ఏళ్") || u.startsWith("సంవ") || u.startsWith("year") ? "ఏళ్ల" : "రోజుల";
                return (base.equals(today) ? "ఈరోజు నుంచి " : say(base) + " నుంచి ") + (long) n + " " + unit + (back ? " ముందు: " : " తర్వాత: ") + say(d) + ".";
            }
        }
        List<Said> ds = dates(t);
        if (ds.isEmpty()) return null;
        // between two dates
        if (ds.size() >= 2 && howMany) {
            LocalDate x = resolve(ds.get(0), today, 0), y = resolve(ds.get(1), today, 0);
            if (x == null || y == null) return "ఆ తేదీ సరిగ్గా లేదు.";
            if (ds.get(1).y == 0 && y.isBefore(x)) y = y.plusYears(1);
            return say(x) + " నుంచి " + say(y) + " వరకు " + span(x, y) + ".";
        }
        Said s = ds.get(0);
        boolean since = SINCE.matcher(t).find() && !t.contains("ఇంకా");
        if (howMany && since) {
            LocalDate d = resolve(s, today, -1);
            if (d == null) return "ఆ తేదీ సరిగ్గా లేదు.";
            if (d.isAfter(today)) return say(d) + " ఇంకా రాలేదు: ఇంకా " + span(today, d) + " ఉంది.";
            return say(d) + " నుంచి ఈరోజుకి " + span(d, today) + ".";
        }
        if (howMany || t.contains("ఇంకా") && (t.contains("ఎన్ని") || t.contains("ఎంత"))) {
            LocalDate d = resolve(s, today, 1);
            if (d == null) return "ఆ తేదీ సరిగ్గా లేదు.";
            if (d.equals(today)) return "అది ఈరోజే: " + say(d) + ".";
            if (d.isBefore(today)) return say(d) + " అయిపోయింది, " + span(d, today) + " క్రితం.";
            return say(d) + " కి ఇంకా " + span(today, d) + ".";
        }
        if (which) {
            LocalDate d = resolve(s, today, 0);
            if (d == null) return "ఆ తేదీ సరిగ్గా లేదు.";
            return d.getDayOfMonth() + " " + MONTH_TE[d.getMonthValue() - 1] + " " + d.getYear() + " " + WEEKDAY_TE[d.getDayOfWeek().getValue() - 1] + ".";
        }
        return null;
    }

    /** "ఇంకా ఎన్ని రోజులు" for a day found elsewhere (a festival by its name). */
    static boolean asksDaysLeft(String text) {
        String t = norm(text);
        return HOW_MANY_DAYS.matcher(t).find() && !SINCE.matcher(t).find() || t.contains("ఇంకా") && (t.contains("ఎన్ని") || t.contains("ఎంత"));
    }

    static String daysLeft(LocalDate d, LocalDate today) {
        if (d.equals(today)) return "ఈరోజే";
        return "ఇంకా " + span(today, d);
    }

    /** His age from the day / year he was born ("1995 లో పుట్టాను, నా వయసు ఎంత"). Nothing is kept. */
    static String age(String text, LocalDate today) {
        String t = norm(text);
        if (!t.contains("వయసు") && !t.contains("వయస్సు") && !t.contains("age")) return null;
        List<Said> ds = dates(t);
        if (!ds.isEmpty() && ds.get(0).y > 0) {
            LocalDate b = resolve(ds.get(0), today, -1);
            if (b == null || b.isAfter(today)) return null;
            Period p = Period.between(b, today);
            return "వయసు " + p.getYears() + " ఏళ్లు" + (p.getMonths() > 0 ? " " + p.getMonths() + " నెలలు" : "") + ".";
        }
        Matcher y = Pattern.compile("(?<!\\d)(19\\d\\d|20\\d\\d)(?!\\d)").matcher(t);
        if (y.find()) {
            int yr = Integer.parseInt(y.group(1));
            if (yr > today.getYear()) return null;
            int a = today.getYear() - yr;
            return "ఈ ఏడాది పుట్టినరోజుకి " + a + " ఏళ్లు (పుట్టినరోజు ఇంకా రాకపోతే " + (a - 1) + ").";
        }
        return null;
    }

    // ================================================================ EMI, interest, GST

    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(శాతం|%|పర్సెంట్|పెర్సెంట్|percent)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEARS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ఏళ్లు|ఏళ్ళు|ఏళ్ల|ఏళ్ళ|ఏళ్లకు|ఏళ్లకి|ఏడాదికి|ఏడాది|ఏడాదులు|సంవత్సరాలు|సంవత్సరాల|సంవత్సరం|సంవత్సరానికి|years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTHS_N = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(నెలలు|నెలల|నెలలకు|నెలలకి|నెల(?!కు|కి|వారీ)|months?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAYS_N = Pattern.compile("(\\d+)\\s*(రోజులు|రోజుల|రోజులకు|రోజులకి|days?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMI_WORD = Pattern.compile("(ఈఎంఐ|ఇఎంఐ|ఈ\\s*ఎం\\s*ఐ|\\bemi\\b|కిస్తీ|వాయిదా)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUPEE_RATE = Pattern.compile("(?:(\\d+(?:\\.\\d+)?)\\s*)?(రూపాయల|రూపాయలు|రూపాయి|రూపాయిల|రూపాయిన్నర|రూపాయన్నర)\\s*(?:పావలా\\s*)?వడ్డీ");

    /** {years or months -> months}, or -1. */
    private static double tenureMonths(String t) {
        Matcher y = YEARS.matcher(t);
        if (y.find()) return Double.parseDouble(y.group(1)) * 12;
        Matcher m = MONTHS_N.matcher(t);
        if (m.find()) return Double.parseDouble(m.group(1));
        Matcher d = DAYS_N.matcher(t);
        if (d.find()) return Integer.parseInt(d.group(1)) / 30.0;
        return -1;
    }

    /** The money sum: the biggest number that isn't the rate or the time. */
    private static double principal(String t) {
        String rest = PCT.matcher(t).replaceAll(" ");
        rest = YEARS.matcher(rest).replaceAll(" ");
        rest = MONTHS_N.matcher(rest).replaceAll(" ");
        rest = DAYS_N.matcher(rest).replaceAll(" ");
        rest = RUPEE_RATE.matcher(rest).replaceAll(" ");
        double best = -1;
        for (double v : Offline.numbers(rest)) best = Math.max(best, v);
        return best;
    }

    private static String years(double months) {
        if (months % 12 == 0) return (long) (months / 12) + (months == 12 ? " ఏడాది" : " ఏళ్లు");
        return Offline.fmt(Math.round(months * 10) / 10.0) + " నెలలు";
    }

    /** "5 లక్షలకు 10 శాతం వడ్డీ 3 ఏళ్లకు EMI ఎంత?": the EMI with the total and the interest; a question back when a part is missing. */
    static String emi(String text) {
        String t = norm(text);
        if (!EMI_WORD.matcher(t).find()) return null;
        if (t.contains("ఈఎంఐ") && !t.matches(".*\\d.*")) return null; // ("EMI కట్టాను", no sum)
        Matcher r = PCT.matcher(t);
        double rate = r.find() ? Double.parseDouble(r.group(1)) : -1;
        double n = tenureMonths(t), p = principal(t);
        if (p <= 0) return null;
        if (rate < 0) return "వడ్డీ ఏడాదికి ఎంత శాతం? ఉదాహరణకు \"" + num(p) + " కి 10 శాతం 3 ఏళ్లకు EMI ఎంత\".";
        if (n <= 0) return "ఎన్ని నెలలు / ఏళ్లు కట్టాలి?";
        double i = rate / 12 / 100;
        double emi = i == 0 ? p / n : p * i * Math.pow(1 + i, n) / (Math.pow(1 + i, n) - 1);
        double total = emi * n;
        return rupees(p) + " కి ఏడాదికి " + Offline.fmt(rate) + "% వడ్డీతో " + years(n) + ": నెలకు EMI " + rupees(emi) + ". మొత్తం కట్టేది "
                + rupees(total) + ", అందులో వడ్డీ " + rupees(total - p) + ".";
    }

    /**
     * Interest: "లక్షకు 2 రూపాయల వడ్డీ 6 నెలలకు" (₹2 a month for every ₹100, the village way), "50 వేలకు 12 శాతం వడ్డీ 2 ఏళ్లకు"
     * (a year), "నెలకు 2 శాతం", చక్రవడ్డీ (compound). Without a time: a month's and a year's interest.
     */
    static String interest(String text) {
        String t = norm(text);
        if (!t.contains("వడ్డీ") && !t.contains("interest")) return null;
        if (EMI_WORD.matcher(t).find()) return null;
        boolean compound = t.contains("చక్రవడ్డీ") || t.contains("చక్ర వడ్డీ") || t.contains("compound");
        double monthly; // % a month
        String rateSaid;
        Matcher rr = RUPEE_RATE.matcher(t);
        if (rr.find()) {
            String w = rr.group(2);
            double v = w.contains("న్నర") ? 1.5 : rr.group(1) == null ? 1 : Offline.number(rr.group(1));
            if (v <= 0 || v > 20) v = w.contains("న్నర") ? 1.5 : 1;
            if (t.contains("పావలా")) v += 0.25;
            monthly = v;
            rateSaid = "నెలకు వందకు " + Offline.fmt(v) + " రూపాయల వడ్డీ (ఏడాదికి " + Offline.fmt(v * 12) + "%)";
        } else {
            Matcher r = PCT.matcher(t);
            if (!r.find()) return null;
            double v = Double.parseDouble(r.group(1));
            boolean perMonth = t.contains("నెలకు") || t.contains("నెలకి") || t.contains("per month") || t.contains("monthly");
            monthly = perMonth ? v : v / 12;
            rateSaid = perMonth ? "నెలకు " + Offline.fmt(v) + "% (ఏడాదికి " + Offline.fmt(v * 12) + "%)" : "ఏడాదికి " + Offline.fmt(v) + "%";
        }
        double p = principal(t.replaceAll("(నెలకు|నెలకి)", " "));
        if (p <= 0) return null;
        double n = tenureMonths(t.replaceAll("(నెలకు|నెలకి|ఏడాదికి|సంవత్సరానికి)\\s*(\\d+(?:\\.\\d+)?)\\s*(శాతం|%)", " $2 శాతం"));
        double perMonth = p * monthly / 100;
        if (n <= 0) {
            return rupees(p) + " కి " + rateSaid + ": నెలకు వడ్డీ " + rupees(perMonth) + ", ఏడాదికి " + rupees(perMonth * 12) + ".";
        }
        double interest;
        if (compound) {
            boolean yearly = !rr.find(0) && !(t.contains("నెలకు") || t.contains("నెలకి"));
            interest = yearly ? p * (Math.pow(1 + monthly * 12 / 100, n / 12) - 1) : p * (Math.pow(1 + monthly / 100, n) - 1);
        } else interest = perMonth * n;
        return rupees(p) + " కి " + rateSaid + ", " + years(n) + (compound ? " చక్రవడ్డీ" : " వడ్డీ") + " " + rupees(interest) + ". అసలుతో కలిపి " + rupees(p + interest) + ".";
    }

    private static final Pattern GST_WORD = Pattern.compile("(జీఎస్టీ|జిఎస్టి|జీ\\s*ఎస్\\s*టీ|జి\\s*ఎస్\\s*టి|\\bgst\\b)", Pattern.CASE_INSENSITIVE);

    /** "1000 కి 18 శాతం GST" (added) / "1180 లో GST ఎంత" (in it, taken out). 18% when no rate is said. */
    static String gst(String text) {
        String t = norm(text);
        if (!GST_WORD.matcher(t).find()) return null;
        Matcher r = PCT.matcher(t);
        boolean saidRate = r.find();
        double rate = saidRate ? Double.parseDouble(r.group(1)) : 18;
        String rest = PCT.matcher(t).replaceAll(" ");
        double a = -1;
        for (double v : Offline.numbers(rest)) a = Math.max(a, v);
        if (a <= 0) return null;
        boolean inside = t.matches(".*(కలిపి|కలుపుకుని|including|inclusive|తీసేస్తే|తీసివేస్తే|లేకుండా|without|లో\\s*(జీఎస్టీ|జిఎస్టి|gst)\\s*ఎంత).*")
                && !t.matches(".*(కలిపితే|కలుపితే|add|ఎంత\\s*అవుతుంది).*");
        String head = saidRate ? "" : "(18% అనుకుని) ";
        if (inside) {
            double base = a / (1 + rate / 100), g = a - base;
            return head + rupees(a) + " లో " + Offline.fmt(rate) + "% GST " + rupees(g) + ", GST లేకుండా " + rupees(base) + ".";
        }
        double g = a * rate / 100;
        return head + rupees(a) + " పై " + Offline.fmt(rate) + "% GST " + rupees(g) + ". మొత్తం " + rupees(a + g)
                + " (CGST " + rupees(g / 2) + " + SGST " + rupees(g / 2) + ").";
    }

    // ================================================================ units

    /** {stem, kind, factor to the kind's base, name to say}. Longer stems first where one starts another. */
    private static final Object[][] UNITS = {
            {"కిలోమీటర్", "len", 1000.0, "కిలోమీటర్లు"}, {"కిలోమీటరు", "len", 1000.0, "కిలోమీటర్లు"}, {"కి.మీ", "len", 1000.0, "కిలోమీటర్లు"}, {"km", "len", 1000.0, "కిలోమీటర్లు"},
            {"kilomet", "len", 1000.0, "కిలోమీటర్లు"},
            {"మైళ్", "len", 1609.344, "మైళ్లు"}, {"మైలు", "len", 1609.344, "మైళ్లు"}, {"mile", "len", 1609.344, "మైళ్లు"},
            {"సెంటీమీటర్", "len", 0.01, "సెంటీమీటర్లు"}, {"సెంటిమీటర్", "len", 0.01, "సెంటీమీటర్లు"}, {"సెం.మీ", "len", 0.01, "సెంటీమీటర్లు"}, {"cm", "len", 0.01, "సెంటీమీటర్లు"},
            {"centimet", "len", 0.01, "సెంటీమీటర్లు"},
            {"మీటర్", "len", 1.0, "మీటర్లు"}, {"మీటరు", "len", 1.0, "మీటర్లు"}, {"metre", "len", 1.0, "మీటర్లు"}, {"meter", "len", 1.0, "మీటర్లు"},
            {"అడుగు", "len", 0.3048, "అడుగులు"}, {"అడుగుల", "len", 0.3048, "అడుగులు"}, {"ఫీట్", "len", 0.3048, "అడుగులు"}, {"feet", "len", 0.3048, "అడుగులు"},
            {"foot", "len", 0.3048, "అడుగులు"}, {"ft", "len", 0.3048, "అడుగులు"},
            {"అంగుళ", "len", 0.0254, "అంగుళాలు"}, {"ఇంచ", "len", 0.0254, "అంగుళాలు"}, {"inch", "len", 0.0254, "అంగుళాలు"},
            {"కిలో", "kg", 1.0, "కిలోలు"}, {"కేజీ", "kg", 1.0, "కిలోలు"}, {"kg", "kg", 1.0, "కిలోలు"}, {"kilo", "kg", 1.0, "కిలోలు"},
            {"పౌండ్", "kg", 0.45359237, "పౌండ్లు"}, {"pound", "kg", 0.45359237, "పౌండ్లు"}, {"lb", "kg", 0.45359237, "పౌండ్లు"},
            {"గ్రాము", "kg", 0.001, "గ్రాములు"}, {"గ్రాముల", "kg", 0.001, "గ్రాములు"}, {"gram", "kg", 0.001, "గ్రాములు"},
            {"సెల్సియస్", "temp", 1.0, "°C"}, {"సెంటీగ్రేడ్", "temp", 1.0, "°C"}, {"celsius", "temp", 1.0, "°C"}, {"°c", "temp", 1.0, "°C"},
            {"ఫారెన్‌హీట్", "temp", 2.0, "°F"}, {"ఫారెన్హీట్", "temp", 2.0, "°F"}, {"ఫారన్‌హీట్", "temp", 2.0, "°F"}, {"ఫారన్హీట్", "temp", 2.0, "°F"},
            {"fahrenheit", "temp", 2.0, "°F"}, {"°f", "temp", 2.0, "°F"},
            {"ఎకరా", "area", 43560.0, "ఎకరాలు"}, {"ఎకర", "area", 43560.0, "ఎకరాలు"}, {"acre", "area", 43560.0, "ఎకరాలు"},
            {"సెంటు", "area", 435.6, "సెంట్లు"}, {"సెంట్", "area", 435.6, "సెంట్లు"}, {"cent", "area", 435.6, "సెంట్లు"},
            {"గుంట", "area", 1089.0, "గుంటలు"}, {"gunt", "area", 1089.0, "గుంటలు"},
            {"చదరపు గజ", "area", 9.0, "చదరపు గజాలు"}, {"గజాల", "area", 9.0, "చదరపు గజాలు"}, {"గజాలు", "area", 9.0, "చదరపు గజాలు"}, {"గజం", "area", 9.0, "చదరపు గజాలు"},
            {"square yard", "area", 9.0, "చదరపు గజాలు"}, {"sq yard", "area", 9.0, "చదరపు గజాలు"}, {"yard", "area", 9.0, "చదరపు గజాలు"},
            {"చదరపు అడుగ", "area", 1.0, "చదరపు అడుగులు"}, {"స్క్వేర్ ఫీట్", "area", 1.0, "చదరపు అడుగులు"}, {"square feet", "area", 1.0, "చదరపు అడుగులు"},
            {"sq ft", "area", 1.0, "చదరపు అడుగులు"}, {"sqft", "area", 1.0, "చదరపు అడుగులు"},
            {"చదరపు మీటర", "area", 10.7639104, "చదరపు మీటర్లు"}, {"square met", "area", 10.7639104, "చదరపు మీటర్లు"}, {"sq m", "area", 10.7639104, "చదరపు మీటర్లు"},
            {"హెక్టార్", "area", 107639.104, "హెక్టార్లు"}, {"hectare", "area", 107639.104, "హెక్టార్లు"},
            {"లీటర్", "vol", 1.0, "లీటర్లు"}, {"లీటరు", "vol", 1.0, "లీటర్లు"}, {"litre", "vol", 1.0, "లీటర్లు"}, {"liter", "vol", 1.0, "లీటర్లు"},
            {"గ్యాలన్", "vol", 3.785411784, "గ్యాలన్లు"}, {"gallon", "vol", 3.785411784, "గ్యాలన్లు"}};

    /** A unit mention: where it starts and ends in the text, and its row. */
    private static final class U { int at, end; Object[] row; }

    private static List<U> mentions(String t) {
        List<U> out = new ArrayList<>();
        // the longest stem at each place (చదరపు అడుగులు before అడుగులు, కిలోమీటర్ before కిలో)
        int i = 0;
        while (i < t.length()) {
            boolean start = i == 0 || Character.isWhitespace(t.charAt(i - 1)) || Character.isDigit(t.charAt(i - 1));
            Object[] best = null;
            if (start) for (Object[] r : UNITS) {
                String s = (String) r[0];
                if (t.startsWith(s, i) && (best == null || s.length() > ((String) best[0]).length())) best = r;
            }
            if (best == null) { i++; continue; }
            String s = (String) best[0];
            int end = i + s.length();
            // short English stems must be whole words ("km", not "kms" is fine; "ft" not "after")
            if (s.matches("[a-z]{1,3}") && end < t.length() && Character.isLetter(t.charAt(end)) && !t.startsWith(s + "s", i)) { i++; continue; }
            while (end < t.length() && !Character.isWhitespace(t.charAt(end))) end++;
            U u = new U();
            u.at = i;
            u.end = end;
            u.row = best;
            out.add(u);
            i = end;
        }
        return out;
    }

    private static final Pattern LAST_NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*$");

    /** "10 కిలోమీటర్లు ఎన్ని మైళ్లు?", "5 అడుగుల 8 అంగుళాలు ఎన్ని సెంటీమీటర్లు?", "2 ఎకరాలు ఎన్ని గుంటలు?", "102 డిగ్రీల జ్వరం సెల్సియస్‌లో". */
    static String units(String text) {
        String t = norm(text);
        // fever said in °F ("102 డిగ్రీల జ్వరం")
        Matcher fever = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(డిగ్రీల|డిగ్రీలు|డిగ్రీ)").matcher(t);
        if (fever.find() && (t.contains("జ్వరం") || t.contains("టెంపరేచర్") || t.contains("ఉష్ణోగ్రత"))) {
            double f = Double.parseDouble(fever.group(1));
            if (f >= 93 && f <= 110) {
                double c = (f - 32) * 5 / 9;
                return Offline.fmt(f) + "°F అంటే " + Offline.fmt(Math.round(c * 10) / 10.0) + "°C." + (f >= 100.4 ? " ఇది జ్వరమే." : "");
            }
            if (f >= 34 && f <= 43) {
                double ff = f * 9 / 5 + 32;
                return Offline.fmt(f) + "°C అంటే " + Offline.fmt(Math.round(ff * 10) / 10.0) + "°F." + (f >= 38 ? " ఇది జ్వరమే." : "");
            }
        }
        List<U> us = mentions(t);
        if (us.size() < 2) return null;
        if (!t.matches(".*(ఎన్ని|ఎంత|అంటే|లో|కి|into|to|in|=|మార్చు|convert).*")) return null;
        U target = us.get(us.size() - 1);
        String kind = (String) target.row[1];
        // what he gives: every number + unit of the same kind before the target ("5 అడుగుల 8 అంగుళాలు")
        double base = 0;
        int got = 0;
        String said = "";
        for (U u : us) {
            if (u == target) break;
            if (!kind.equals(u.row[1]) && !("len".equals(kind) && "area".equals(u.row[1]))) continue;
            Matcher m = LAST_NUM.matcher(t.substring(0, u.at));
            if (!m.find()) continue;
            double v = Double.parseDouble(m.group(1));
            Object[] row = u.row;
            // గజాలు next to a length unit is a yard of length
            if ("area".equals(row[1]) && "len".equals(kind)) row = new Object[]{"yard", "len", 0.9144, "గజాలు"};
            if (!kind.equals(row[1])) continue;
            said += (said.isEmpty() ? "" : " ") + Offline.fmt(v) + " " + row[3];
            if ("temp".equals(kind)) base = (double) row[2] == 2.0 ? (v - 32) * 5 / 9 : v;
            else base += v * (double) row[2];
            got++;
        }
        if (got == 0) return null;
        Object[] tr = target.row;
        if ("area".equals(tr[1]) && !"area".equals(kind)) return null;
        double out;
        if ("temp".equals(kind)) out = (double) tr[2] == 2.0 ? base * 9 / 5 + 32 : base;
        else out = base / (double) tr[2];
        String res;
        if ("len".equals(kind) && "అడుగులు".equals(tr[3]) && base < 3) { // a height: feet and inches
            double inches = base / 0.0254;
            long ft = (long) Math.floor(inches / 12);
            double in = inches - ft * 12;
            res = ft + " అడుగుల " + Offline.fmt(Math.round(in * 10) / 10.0) + " అంగుళాలు";
        } else {
            double r = Math.abs(out) >= 1000 ? Math.round(out) : Math.abs(out) >= 1 ? Math.round(out * 100) / 100.0 : Math.round(out * 10000) / 10000.0;
            res = num(r) + ("°C".equals(tr[3]) || "°F".equals(tr[3]) ? "" : " ") + tr[3];
        }
        return said + " = " + res + ".";
    }

    // ================================================================ one call for all

    /** Any of the sums above, or null. */
    static String any(String text, LocalDate today) {
        String r = emi(text);
        if (r == null) r = interest(text);
        if (r == null) r = gst(text);
        if (r == null) r = units(text);
        if (r == null) r = age(text, today);
        if (r == null) r = dates(text, today);
        return r;
    }

    static DayOfWeek weekday(LocalDate d) { return d.getDayOfWeek(); }
}
