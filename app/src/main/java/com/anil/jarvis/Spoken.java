package com.anil.jarvis;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers as Telugu words, just before they are spoken: "₹1,200" -> "వెయ్యి రెండు వందల రూపాయలు", "10:30" -> "పదిన్నర",
 * "19:00" -> "రాత్రి ఏడు గంటలు", "45%" -> "నలభై ఐదు శాతం", "2026లో" -> "రెండు వేల ఇరవై ఆరులో", phone numbers digit by
 * digit, "యోహాను 3:16" -> "యోహాను మూడు, పదహారు". The screen keeps the digits; only the voice gets the words.
 * src maps every spoken character back to the text it came from, so the word highlight still lands on the number.
 * Text with no Telugu in it (English practice) is left as it is.
 */
final class Spoken {
    private Spoken() {}

    static final class Out {
        final String text;
        /** For each character of text: its position in the original. */
        final int[] src;

        Out(String text, int[] src) { this.text = text; this.src = src; }

        /** At most max characters (the voice's limit), still mapped. */
        Out cut(int max) {
            return text.length() <= max ? this : new Out(text.substring(0, max), Arrays.copyOf(src, max));
        }

        /** Original [start, end) for spoken [a, b). */
        int[] range(int a, int b) {
            if (src.length == 0) return new int[]{0, 0};
            int s = src[Math.max(0, Math.min(a, src.length - 1))];
            int e = src[Math.max(0, Math.min(b - 1, src.length - 1))] + 1;
            return new int[]{Math.min(s, e - 1), e};
        }
    }

    static String say(String s) { return of(s).text; }

    static Out of(String s) {
        if (s == null) s = "";
        boolean te = false, digit = false;
        for (int i = 0; i < s.length() && !(te && digit); i++) {
            char c = s.charAt(i);
            if (telugu(c)) te = true;
            else if (c >= '0' && c <= '9') digit = true;
        }
        if (te && digit) {
            try { return new Run(s).go(); } catch (RuntimeException ignored) {} // never let a number stop the voice: then as written
        }
        int[] id = new int[s.length()];
        for (int i = 0; i < id.length; i++) id[i] = i;
        return new Out(s, id);
    }

    // ---------------------------------------------------------------- number words

    private static final String[] ONES = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది",
            "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు", "పద్దెనిమిది", "పందొమ్మిది"};
    private static final String[] TENS = {"", "", "ఇరవై", "ముప్పై", "నలభై", "యాభై", "అరవై", "డెబ్బై", "ఎనభై", "తొంభై"};
    private static final String[] MONTHS = {"", "జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    private static String below100(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
    }

    private static String below1000(int n) {
        int h = n / 100, r = n % 100;
        if (h == 0) return below100(r);
        if (r == 0) return h == 1 ? "వంద" : ONES[h] + " వందలు";
        return (h == 1 ? "నూట" : ONES[h] + " వందల") + " " + below100(r);
    }

    private static String belowLakh(int n) {
        int t = n / 1000, r = n % 1000;
        if (t == 0) return below1000(r);
        if (r == 0) return t == 1 ? "వెయ్యి" : below100(t) + " వేలు";
        return (t == 1 ? "వెయ్యి" : below100(t) + " వేల") + " " + below1000(r);
    }

    private static String belowCrore(int n) {
        int l = n / 100000, r = n % 100000;
        if (l == 0) return belowLakh(r);
        if (r == 0) return l == 1 ? "లక్ష" : below100(l) + " లక్షలు";
        return (l == 1 ? "లక్షా" : below100(l) + " లక్షల") + " " + belowLakh(r);
    }

    /** 2026 -> "రెండు వేల ఇరవై ఆరు" (Indian lakhs and crores). */
    static String words(long n) {
        if (n < 0) return "మైనస్ " + words(-n);
        if (n < 10000000L) return belowCrore((int) n);
        long c = n / 10000000L, r = n % 10000000L;
        if (r == 0) return c == 1 ? "కోటి" : words(c) + " కోట్లు";
        return (c == 1 ? "కోటి" : words(c) + " కోట్ల") + " " + belowCrore((int) r);
    }

    /** "రెండు వేలు" -> "రెండు వేల" (before a noun or a suffix: "రెండు వేల రూపాయలు", "రెండు వేలలో"). */
    static String oblique(String w) {
        return w.endsWith("లు") ? w.substring(0, w.length() - 2) + "ల" : w;
    }

    /** "రెండు" -> "రెండో", "పది" -> "పదో", "ఇరవై" -> "ఇరవయ్యో", "వంద" -> "వందో". */
    static String ordinal(long n) {
        String w = words(n);
        if (w.endsWith("ు") || w.endsWith("ి")) return w.substring(0, w.length() - 1) + "ో";
        if (w.endsWith("ై")) return w.substring(0, w.length() - 1) + "య్యో";
        return w + "ో";
    }

    private static String digits(String d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < '0' || c > '9') continue;
            if (b.length() > 0) b.append(' ');
            b.append(ONES[c - '0']);
        }
        return b.toString();
    }

    /** Whole part and the digits after the point: 2.5 -> "రెండున్నర", 0.5 -> "అర", 4.75 -> "నాలుగు పాయింట్ ఏడు ఐదు". */
    private static String decimal(long whole, String frac) {
        String f = frac == null ? "" : frac.replaceAll("0+$", "");
        if (f.isEmpty()) return words(whole);
        if (f.equals("5") && whole < 100) return whole == 0 ? "అర" : words(whole) + "న్నర";
        return words(whole) + " పాయింట్ " + digits(f);
    }

    /** The time of day, from the 24-hour clock: "ఉదయం", "సాయంత్రం"... */
    private static String partOfDay(int h24) {
        if (h24 == 0) return "అర్ధరాత్రి";
        if (h24 < 4) return "రాత్రి";
        if (h24 < 6) return "తెల్లవారుజామున";
        if (h24 < 12) return "ఉదయం";
        if (h24 < 16) return "మధ్యాహ్నం";
        if (h24 < 19) return "సాయంత్రం";
        return "రాత్రి";
    }

    /** 10:30 -> "పదిన్నర", 7:00 -> "ఏడు గంటలు", 1:15 -> "ఒంటి గంట పదిహేను నిమిషాలు". */
    private static String clock(int h12, int m) {
        if (m == 0) return h12 == 1 ? "ఒంటి గంట" : below100(h12) + " గంటలు";
        if (m == 30) return below100(h12) + "న్నర";
        return (h12 == 1 ? "ఒంటి గంట" : below100(h12) + " గంటల") + " " + (m == 1 ? "ఒక నిమిషం" : below100(m) + " నిమిషాలు");
    }

    static boolean telugu(char c) { return c >= 0x0C00 && c <= 0x0C7F; }

    private static boolean latin(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    // ---------------------------------------------------------------- the pass over the text

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final String NUM = "(\\d{1,3}(?:,\\d{2,3})*,\\d{3}|\\d+)";
    private static final Pattern MONEY = Pattern.compile("(?:₹|rs\\.?|inr|రూ\\.?)\\s?" + NUM + "(?:\\.(\\d{1,2}))?(?!\\d)(\\s*/-)?", CI);
    private static final Pattern MULT = Pattern.compile("^\\s*(లక్షలు|లక్షల|లక్ష|కోట్లు|కోట్ల|కోటి|వేలు|వేల|వెయ్యి|lakhs?|crores?|thousand|l|k|cr)(?![a-z])", CI);
    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d:])");
    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})([-/.])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d.])");
    private static final Pattern COLON = Pattern.compile("(\\d{1,3}):(\\d{1,3})(?::\\d{2})?(?:\\s*[-–]\\s*(\\d{1,3})(?![\\d:]))?(?:\\s?(a\\.?m\\.?|p\\.?m\\.?)(?![a-z]))?(?!\\d)", CI);
    private static final Pattern AMPM = Pattern.compile("(\\d{1,2})\\s?(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])", CI);
    private static final Pattern HOURS_AFTER = Pattern.compile("^\\s*గంట(లకు|లకి|కు|కి|లు|ల)?(?![\\u0C00-\\u0C7F])");
    private static final Pattern FRACTION = Pattern.compile("(\\d{1,4})/(\\d{1,4})(?![\\d/])");
    private static final Pattern NUMBER = Pattern.compile(NUM + "(?:\\.(\\d+))?(?!\\d)");
    private static final Pattern SPEED = Pattern.compile("^\\s?(km/h|kmph|kmh|km/hr)(?![a-z])", CI);
    private static final Pattern UNIT = Pattern.compile("^\\s?(kms?|kgs?|kwh|kw|mins?|minutes?|hrs?|hours?|h|ltrs?|litres?|liters?|ml|gb|mb|cm|mm|m|కి\\.మీ\\.?|కిమీ)(?![a-z\\u0C00-\\u0C7F])", CI);
    private static final Pattern PERCENT = Pattern.compile("^\\s?%");
    private static final Pattern DEGREE = Pattern.compile("^\\s?°\\s?[cf]?(?![a-z])", CI);
    private static final Pattern EN_ORDINAL = Pattern.compile("^(st|nd|rd|th)(?![a-z])", CI);
    private static final Pattern RANGE = Pattern.compile("^\\s?[-–]\\s?(?=\\d)");
    private static final Pattern RUPEE_AFTER = Pattern.compile("^\\s*(రూపాయ|rupee|rs\\b)", CI);
    /** "7:00 కి", "₹500 కు": the suffix written apart goes onto the number ("ఏడు గంటలకి"). */
    private static final Pattern SPLIT_SFX = Pattern.compile("^ (కి|కు)(?![\\u0C00-\\u0C7F])");
    private static final String[] CODE_WORDS = {"otp", "ఓటీపీ", "pin", "పిన్", "code", "కోడ్", "pnr", "నంబర్", "నెంబర్", "నంబరు", "no", "id", "ఐడీ"};
    private static final String[] UNIT_NOUNS = {"దూరం", "దూరంలో", "వేగం", "వేగంతో", "ప్రయాణం", "రేంజ్", "దారిలో", "మేర"};
    private static final String[] PART_WORDS = {"ఉదయం", "పొద్దున", "పొద్దున్న", "పొద్దున్నే", "సాయంత్రం", "రాత్రి", "మధ్యాహ్నం", "తెల్లవారుజామున",
            "అర్ధరాత్రి", "morning", "evening", "night", "afternoon"};
    private static final String[] NOT_NOUN = {"నుంచి", "నుండి", "వరకు", "వరకూ", "లేదా", "కాదు", "అంటే", "కంటే", "కన్నా", "మాత్రమే", "కూడా", "అయితే",
            "అని", "ఉంది", "ఉన్నాయి", "అయింది", "అయ్యింది", "ఇచ్చారు", "ఇవ్వాలి", "కావాలి", "చాలు", "పైన", "పైగా", "లోపు", "లోపల"};
    private static final String[][] UNITS = {
            {"km|kms|కి.మీ|కి.మీ.|కిమీ", "కిలోమీటర్లు", "కిలోమీటరు"}, {"kg|kgs", "కిలోలు", "కిలో"}, {"kwh", "యూనిట్లు", "యూనిట్"},
            {"kw", "కిలోవాట్లు", "కిలోవాట్"}, {"min|mins|minute|minutes", "నిమిషాలు", "నిమిషం"}, {"h|hr|hrs|hour|hours", "గంటలు", "గంట"},
            {"ltr|ltrs|litre|litres|liter|liters", "లీటర్లు", "లీటరు"}, {"ml", "మిల్లీలీటర్లు", "మిల్లీలీటరు"}, {"gb", "జీబీ", "జీబీ"},
            {"mb", "ఎంబీ", "ఎంబీ"}, {"cm", "సెంటీమీటర్లు", "సెంటీమీటరు"}, {"mm", "మిల్లీమీటర్లు", "మిల్లీమీటరు"}, {"m", "మీటర్లు", "మీటరు"}};

    private static final class Run {
        final String in;
        final int n;
        final StringBuilder out = new StringBuilder();
        int[] src = new int[64];

        Run(String in) { this.in = in; this.n = in.length(); }

        void put(int at) {
            if (out.length() >= src.length) src = Arrays.copyOf(src, src.length * 2);
            src[out.length()] = at;
        }

        void copy(int i) { put(i); out.append(in.charAt(i)); }

        /** Words standing for the original [a, b). */
        void emit(String s, int a, int b) {
            int len = s.length(), span = Math.max(1, b - a);
            for (int k = 0; k < len; k++) {
                put(Math.min(b - 1, a + (int) ((long) k * span / len)));
                out.append(s.charAt(k));
            }
        }

        Out go() {
            int i = 0;
            while (i < n) {
                char c = in.charAt(i);
                int next = -1;
                if (digit(c) || c == '₹' || c == '+' || c == 'ర' || c == 'R' || c == 'r' || c == 'I' || c == 'i') next = at(i);
                if (next > i) { i = next; continue; }
                if (c == '-' && i + 1 < n && digit(in.charAt(i + 1)) && (i == 0 || in.charAt(i - 1) == ' ' || in.charAt(i - 1) == '(')) {
                    emit("మైనస్ ", i, i + 1); // "-3°C"
                    i++;
                    continue;
                }
                if (latin(c)) { // a word or code with digits in it (TS09AB1234, v1.0, 5G) stays as it is
                    int j = i;
                    while (j < n && (latin(in.charAt(j)) || digit(in.charAt(j))
                            || (in.charAt(j) == '.' && j + 1 < n && (latin(in.charAt(j + 1)) || digit(in.charAt(j + 1)))))) j++;
                    for (int k = i; k < j; k++) copy(k);
                    i = j;
                    continue;
                }
                copy(i);
                i++;
            }
            return new Out(out.toString(), Arrays.copyOf(src, out.length()));
        }

        Matcher look(Pattern p, int i) {
            Matcher m = p.matcher(in);
            m.region(i, n);
            m.useTransparentBounds(true);
            return m.lookingAt() ? m : null;
        }

        String after(int i) { return in.substring(i, Math.min(n, i + 40)); }

        boolean gluedTelugu(int i) { return i < n && telugu(in.charAt(i)); }

        /** The word right after position i (past one space), or "". */
        String nextWord(int i) {
            int j = i;
            if (j < n && in.charAt(j) == ' ') j++;
            else return "";
            int s = j;
            while (j < n && (telugu(in.charAt(j)) || latin(in.charAt(j)) || in.charAt(j) == '‌' || in.charAt(j) == '‍')) j++;
            return in.substring(s, j);
        }

        /** The word just before position i (past spaces). */
        String prevWord(int i) {
            int j = i;
            while (j > 0 && in.charAt(j - 1) == ' ') j--;
            int e = j;
            while (j > 0 && (telugu(in.charAt(j - 1)) || latin(in.charAt(j - 1)) || in.charAt(j - 1) == '‌' || in.charAt(j - 1) == '‍')) j--;
            return in.substring(j, e);
        }

        boolean bibleBefore(int i) {
            String w = prevWord(i);
            if (w.isEmpty()) return false;
            if (Bible.isBook(w)) return true;
            int j = i;
            while (j > 0 && in.charAt(j - 1) == ' ') j--;
            int ws = j - w.length(); // where that word starts
            String w2 = prevWord(ws); // two-word names: "యోహాను సువార్త", "మొదటి యోహాను"
            if (!w2.isEmpty() && Bible.isBook(w2 + " " + w)) return true;
            if (w2.equals("మొదటి") && Bible.isBook("1 " + w) || w2.equals("రెండవ") && Bible.isBook("2 " + w) || w2.equals("మూడవ") && Bible.isBook("3 " + w)) return true;
            int k = ws; // "2 తిమోతికి 3:16", "1కొరింథీయులకు"
            while (k > 0 && in.charAt(k - 1) == ' ') k--;
            if (k > 0 && in.charAt(k - 1) >= '1' && in.charAt(k - 1) <= '3' && (k == 1 || !digit(in.charAt(k - 2))))
                return Bible.isBook(in.charAt(k - 1) + " " + w);
            return false;
        }

        /** Tries every kind of number at i; returns where the text carries on, or -1. */
        int at(int i) {
            char c = in.charAt(i);
            if (i > 0 && (latin(in.charAt(i - 1)) || digit(in.charAt(i - 1)))) return -1;
            Matcher m;
            if (!digit(c)) {
                if (c == '+') return digit(i + 1 < n ? in.charAt(i + 1) : ' ') ? phone(i) : -1;
                if ((m = look(MONEY, i)) != null) return money(m, i);
                return -1;
            }
            int p = phone(i);
            if (p > i) return p;
            if ((m = look(DATE_ISO, i)) != null && date(m.group(3), m.group(2), m.group(1), i, m.end())) return m.end();
            if ((m = look(DATE_DMY, i)) != null && date(m.group(1), m.group(3), m.group(4), i, m.end())) return m.end();
            if ((m = look(COLON, i)) != null) return colon(m, i);
            if ((m = look(AMPM, i)) != null) {
                int h = Integer.parseInt(m.group(1));
                if (h >= 1 && h <= 12) return time(h, 0, m.group(2), i, m.end());
            }
            if ((m = look(FRACTION, i)) != null && (i == 0 || in.charAt(i - 1) != '/')) {
                String a = m.group(1), b = m.group(2), w;
                if (a.equals("1") && b.equals("2")) w = "అర";
                else if (a.equals("1") && b.equals("4")) w = "పావు";
                else if (a.equals("3") && b.equals("4")) w = "ముప్పావు";
                else w = words(Long.parseLong(a)) + " బై " + words(Long.parseLong(b));
                emit(w, i, m.end());
                return m.end();
            }
            if ((m = look(NUMBER, i)) != null) return number(m, i);
            return -1;
        }

        /** 10 to 13 digits in groups (98480 12345, +91 98480-12345): said digit by digit. */
        int phone(int i) {
            int k = i;
            boolean plus = in.charAt(k) == '+';
            if (plus) k++;
            int digitsSeen = 0, end = -1, last = k;
            java.util.List<int[]> groups = new java.util.ArrayList<>(), taken = new java.util.ArrayList<>();
            while (k < n) {
                int s = k;
                while (k < n && digit(in.charAt(k))) k++;
                if (k == s) break;
                groups.add(new int[]{s, k});
                if (k + 1 < n && (in.charAt(k) == ' ' || in.charAt(k) == '-') && digit(in.charAt(k + 1))) { k++; continue; }
                break;
            }
            for (int g = 0; g < groups.size(); g++) {
                int[] gr = groups.get(g);
                int len = gr[1] - gr[0];
                // phone groups are 3+ digits ("98480 12345", "040-2345 6789"); only a country code is shorter ("+91", "91")
                if (len < 3 && !(g == 0 && (plus || in.startsWith("91", gr[0]) && len == 2))) break;
                digitsSeen += len;
                if (digitsSeen > 13) break;
                if (digitsSeen >= 10) { end = gr[1]; taken = new java.util.ArrayList<>(groups.subList(0, g + 1)); }
            }
            if (end < 0) return -1;
            // a phone number starts like one (+91, 0..., 1800, a mobile 6-9) and has at most three groups: "100 200 300 400" is a list
            char first = in.charAt(plus ? i + 1 : i);
            if (!plus && !(first >= '6' && first <= '9') && first != '0' && !in.startsWith("1800", i) && !in.startsWith("91", i)) return -1;
            if (taken.size() > (plus ? 4 : 3)) return -1;
            if (end < n && (in.charAt(end) == '.' && end + 1 < n && digit(in.charAt(end + 1)) || latin(in.charAt(end)) || in.charAt(end) == ',' && end + 1 < n && digit(in.charAt(end + 1)))) return -1;
            StringBuilder w = new StringBuilder(plus ? "ప్లస్ " : "");
            for (int g = 0; g < taken.size(); g++) {
                String d = in.substring(taken.get(g)[0], taken.get(g)[1]);
                if (taken.size() == 1 && d.length() >= 10) d = d.substring(0, 5) + " " + d.substring(5);
                for (String part : d.split(" ")) {
                    if (w.length() > (plus ? 6 : 0)) w.append(", ");
                    w.append(digits(part));
                }
            }
            emit(w.toString(), i, end);
            return end;
        }

        boolean date(String d, String mo, String y, int a, int b) {
            int day = Integer.parseInt(d), month = Integer.parseInt(mo), year = Integer.parseInt(y);
            if (day < 1 || day > 31 || month < 1 || month > 12) return false;
            if (y.length() == 2) year += 2000;
            emit(words(day) + " " + MONTHS[month] + ", " + words(year), a, b);
            return true;
        }

        int colon(Matcher m, int i) {
            int a = Integer.parseInt(m.group(1));
            String bs = m.group(2);
            int b = Integer.parseInt(bs);
            boolean clock = a <= 23 && bs.length() == 2 && b <= 59 && m.group(3) == null;
            // "మార్కు 5:30కి వస్తాడు": a name before, but the "కి" / "గంటలకు" after says it is a time
            boolean timeAfter = gluedTelugu(m.end()) || look(HOURS_AFTER, m.end()) != null || look(SPLIT_SFX, m.end()) != null;
            if (!clock || (m.group(4) == null && !timeAfter && bibleBefore(i))) { // a Bible reference / a ratio: "3:16" -> "మూడు, పదహారు"
                int end = m.group(4) == null ? m.end() : m.group(3) != null ? m.end(3) : m.end(2); // a stray "am" is not part of it
                String w = words(a) + ", " + words(b) + (m.group(3) != null ? " నుంచి " + words(Integer.parseInt(m.group(3))) : "");
                emit(w, i, end);
                return end;
            }
            return time(a, b, m.group(4), i, m.end());
        }

        /** A time: "ఉదయం" etc. only when the text gives it (am/pm or the 24-hour clock) and doesn't already say it. */
        int time(int h, int min, String ampm, int a, int b) {
            int h24 = h;
            boolean known = ampm != null || h >= 13 || h == 0;
            if (ampm != null) {
                boolean pm = Character.toLowerCase(ampm.charAt(0)) == 'p';
                h24 = h == 12 ? (pm ? 12 : 0) : (pm ? h + 12 : h);
            }
            int h12 = known ? (h24 % 12 == 0 ? 12 : h24 % 12) : (h == 0 ? 12 : h);
            String w = clock(h12, min);
            if (known) {
                String before = prevWord(a);
                boolean said = false;
                for (String p : PART_WORDS) if (before.equalsIgnoreCase(p)) said = true;
                if (!said) w = partOfDay(h24) + " " + w;
            }
            int end = b;
            Matcher g = look(HOURS_AFTER, end); // "7:00 గంటలకు": the hours word is already in the time
            if (g != null) {
                String s = g.group(1) == null ? "" : g.group(1);
                String sfx = s.endsWith("కు") ? "కు" : s.endsWith("కి") ? "కి" : "";
                w = sfx.isEmpty() ? w : oblique(w) + sfx;
                end = g.end();
            } else if ((g = look(SPLIT_SFX, end)) != null) {
                w = oblique(w) + g.group(1);
                end = g.end();
            } else if (gluedTelugu(end)) w = oblique(w);
            Matcher r = look(RANGE, end); // "10:00-11:00" -> "పది గంటల నుంచి పదకొండు గంటలు"
            if (r != null) { emit(oblique(w) + " నుంచి ", a, r.end()); return r.end(); }
            emit(w, a, end);
            return end;
        }

        int money(Matcher m, int i) {
            String raw = m.group(1).replace(",", "");
            if (raw.length() > 15) { emit(digits(raw), i, m.end()); return m.end(); } // too big to be money: as digits
            long rupees = Long.parseLong(raw);
            String paise = m.group(2);
            int end = m.end();
            Matcher x = look(MULT, end);
            String w;
            if (x != null) { // "₹1.5 లక్షలు", "₹2L"
                String u = x.group(1).toLowerCase(Locale.ROOT);
                w = decimal(rupees, paise);
                if (rupees == 1 && (paise == null || paise.replaceAll("0", "").isEmpty())) w = "ఒక";
                if (u.equals("l") || u.startsWith("lakh")) { w += " లక్షల రూపాయలు"; end = x.end(); }
                else if (u.equals("cr") || u.startsWith("crore")) { w += " కోట్ల రూపాయలు"; end = x.end(); }
                else if (u.equals("k") || u.equals("thousand")) { w += " వేల రూపాయలు"; end = x.end(); }
                emit(w, i, end);
                return end;
            }
            int p = paise == null ? 0 : Integer.parseInt(paise.length() == 1 ? paise + "0" : paise);
            if (p == 0) w = rupees == 1 ? "ఒక రూపాయి" : oblique(words(rupees)) + " రూపాయలు";
            else w = (rupees > 0 ? oblique(words(rupees)) + " రూపాయల " : "") + words(p) + " పైసలు";
            Matcher sf = look(SPLIT_SFX, end);
            if (sf != null) { w = oblique(w) + sf.group(1); end = sf.end(); }
            else if (gluedTelugu(end)) w = oblique(w);
            emit(w, i, end);
            return end;
        }

        int number(Matcher m, int i) {
            String whole = m.group(1), frac = m.group(2);
            int end = m.end();
            boolean commas = whole.contains(",");
            String plain = whole.replace(",", "");
            if (plain.length() > 15) { emit(digits(plain), i, end); return end; }
            long v = Long.parseLong(plain);
            String rest = after(end);
            Matcher x;

            // 1 కొరింథీయులకు, 2 రాజులు: the book's number
            if (frac == null && v >= 1 && v <= 3 && !commas) {
                String nw = nextWord(end);
                String glued = "";
                int j = end;
                while (j < n && telugu(in.charAt(j))) j++;
                glued = in.substring(end, j);
                if ((!nw.isEmpty() && Bible.isBook(plain + " " + nw)) || (!glued.isEmpty() && Bible.isBook(plain + glued))) {
                    String o = v == 1 ? "మొదటి" : v == 2 ? "రెండవ" : "మూడవ";
                    emit(glued.isEmpty() ? o : o + " ", i, end);
                    return end;
                }
            }
            boolean code = false; // "OTP 4827", "PNR 4521876": digit by digit
            if (frac == null && !commas && plain.length() >= 4) {
                String pw = prevWord(i).toLowerCase(Locale.ROOT);
                for (String cw : CODE_WORDS) if (pw.equals(cw)) code = true;
            }
            boolean countable = false; // "125000 మంది": a number of something, not a code
            if (!code && plain.length() <= 9) {
                String nw = nextWord(end);
                countable = !nw.isEmpty() && telugu(nw.charAt(0));
                for (String s : NOT_NOUN) if (nw.equals(s)) countable = false;
            }
            if (frac == null && !commas && (code || (plain.length() >= 6 && !countable) || (plain.length() > 1 && plain.charAt(0) == '0'))
                    && look(RUPEE_AFTER, end) == null) { // pin codes, long codes, "05": digit by digit
                emit(digits(plain), i, end);
                return end;
            }
            String w = decimal(v, frac);
            boolean one = v == 1 && frac == null;
            if ((x = look(SPEED, end)) != null) {
                String nw = nextWord(x.end());
                emit("గంటకు " + w + (nw.startsWith("వేగ") ? " కిలోమీటర్ల" : " కిలోమీటర్లు"), i, x.end());
                return x.end();
            }
            if ((x = look(PERCENT, end)) != null) { emit(w + " శాతం", i, x.end()); return x.end(); }
            if ((x = look(DEGREE, end)) != null) { emit(w + " డిగ్రీలు", i, x.end()); return x.end(); }
            if ((x = look(UNIT, end)) != null) {
                String u = x.group(1).toLowerCase(Locale.ROOT);
                for (String[] un : UNITS) {
                    for (String k : un[0].split("\\|")) {
                        if (!k.equals(u)) continue;
                        String said = one ? "ఒక " + un[2] : w + " " + un[1];
                        String nw = nextWord(x.end());
                        boolean nounAfter = false;
                        for (String un2 : UNIT_NOUNS) if (nw.equals(un2)) nounAfter = true;
                        if (gluedTelugu(x.end()) || nounAfter) said = oblique(said);
                        emit(said, i, x.end());
                        return x.end();
                    }
                }
            }
            if (frac == null && (x = look(EN_ORDINAL, end)) != null) { emit(ordinal(v), i, x.end()); return x.end(); }
            if (frac == null && end < n && in.charAt(end) == 'వ') { // 2వ -> రెండో, 3వది -> మూడోది
                char after = end + 1 < n ? in.charAt(end + 1) : ' ';
                if (!telugu(after) || (end + 2 < n && after == 'ద' && in.charAt(end + 2) == 'ి')) {
                    emit(ordinal(v), i, end + 1);
                    return end + 1;
                }
            }
            if (end < n && latin(in.charAt(end))) return -1; // 5G, 4K: as it is
            if (look(RANGE, end) != null) { // 10-15 -> పది నుంచి పదిహేను
                Matcher r = look(RANGE, end);
                emit(w + " నుంచి ", i, r.end());
                return r.end();
            }
            Matcher sf = look(SPLIT_SFX, end);
            if (sf != null) { emit(oblique(w) + sf.group(1), i, sf.end()); return sf.end(); }
            if (gluedTelugu(end)) w = oblique(w);
            else if (frac == null) {
                String nw = nextWord(end);
                boolean noun = !nw.isEmpty() && telugu(nw.charAt(0));
                for (String s : NOT_NOUN) if (nw.equals(s)) noun = false;
                if (noun) w = one ? "ఒక" : oblique(w);
            }
            emit(w, i, end);
            return end;
        }
    }
}
