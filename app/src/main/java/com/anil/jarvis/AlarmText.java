package com.anil.jarvis;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⏰ Reads an alarm's time from the words of one row of the clock app's alarm list (Anil, 28 Nov 2026: "అలారం పెట్టమంటే
 * పెడుతుంది కానీ అలారం తీసేయమంటే తీసేయడం లేదు"). "6:00" + "AM", "6:00 PM", "06:00", "18:30", "సా 6:00"… Pure Java (tested on a PC).
 */
final class AlarmText {
    private AlarmText() {}

    /** {hour 0-23, minute, sure}: sure = 1 when AM / PM was on the row or the time can only be 24-hour; 0 = 6:00 could be 6 or 18. */
    static int[] parse(List<String> words) {
        if (words == null) return null;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (w == null) continue;
            Matcher m = TIME.matcher(w);
            if (!m.find()) continue;
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) continue;
            // AM / PM beside the time in the same words, else a word of its own on the row
            int half = half(w.substring(Math.max(0, m.start() - 8), m.start()) + " " + w.substring(m.end(), Math.min(w.length(), m.end() + 8)), false);
            for (int j = 0; half == 0 && j < words.size(); j++) if (j != i) half = half(words.get(j), true);
            if (half != 0 && h >= 1 && h <= 12) {
                int h24 = h % 12 + (half > 0 ? 12 : 0);
                return new int[]{h24, min, 1};
            }
            boolean only24 = h == 0 || h > 12 || m.group(1).length() == 2 && m.group(1).startsWith("0");
            return new int[]{h, min, only24 ? 1 : 0};
        }
        return null;
    }

    /** This row's time is hour:minute (24-hour). An unsure 6:00 matches both 6 and 18. */
    static boolean matches(int[] t, int hour, int minute) {
        if (t == null || t[1] != minute) return false;
        return t[2] == 1 ? t[0] == hour : t[0] % 12 == hour % 12;
    }

    /** "06:00" (unsure: "6:00?"). */
    static String show(int[] t) {
        if (t == null) return "?";
        return t[2] == 1 ? String.format(Locale.ROOT, "%02d:%02d", t[0], t[1]) : String.format(Locale.ROOT, "%d:%02d?", t[0], t[1]);
    }

    private static final Pattern TIME = Pattern.compile("(?<![\\d:])(\\d{1,2})[:.](\\d{2})(?![\\d:])");
    private static final Pattern PM = Pattern.compile("(?i)(?<![a-z])(pm|p\\.m\\.?)(?![a-z])|(^|[\\s,])సా\\.?([\\s,]|$)|సాయంత్రం|మధ్యాహ్నం|రాత్రి");
    private static final Pattern AM = Pattern.compile("(?i)(?<![a-z])(am|a\\.m\\.?)(?![a-z])|(^|[\\s,])ఉ\\.?([\\s,]|$)|ఉదయం|తెల్లవారుజామున");
    private static final java.util.Set<String> PM_WORD = new java.util.HashSet<>(java.util.Arrays.asList("pm", "p.m", "సా", "సాయంత్రం", "మధ్యాహ్నం", "రాత్రి"));
    private static final java.util.Set<String> AM_WORD = new java.util.HashSet<>(java.util.Arrays.asList("am", "a.m", "ఉ", "ఉదయం", "తెల్లవారుజామున"));

    /**
     * +1 PM, -1 AM, 0 neither. alone: a word of its own on the row must be only the mark ("AM", "సా"), never a label that
     * happens to hold one; beside: the few letters next to the time.
     */
    private static int half(String w, boolean alone) {
        if (w == null) return 0;
        String t = w.trim().toLowerCase(Locale.ROOT);
        if (alone) {
            while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
            return PM_WORD.contains(t) ? 1 : AM_WORD.contains(t) ? -1 : 0;
        }
        if (PM.matcher(t).find()) return 1;
        if (AM.matcher(t).find()) return -1;
        return 0;
    }
}
