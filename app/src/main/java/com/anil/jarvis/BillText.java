package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🧾 B6 (10 Nov): a bill photo read on the phone (ML Kit, nothing sent anywhere — his choice "ఫోన్‌లోనే"): the bought date and
 * the warranty months when he didn't say them, and the service / customer-care numbers to call when the thing breaks. Only
 * these few things are kept; the bill's text itself is not (no invoice, serial, card or account numbers). The parsing is
 * pure and desk-tested.
 */
final class BillText {
    private BillText() {}

    static final class Found {
        LocalDate date;
        int months;
        final List<String> service = new ArrayList<>();
    }

    // ================================================================ reading (Android)

    /** Reads a bill photo (JPEG bytes) on the phone; background thread only. Null when nothing could be read. */
    static Found read(byte[] jpg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            if (b == null) return null;
            String t = OfflineRead.text(b);
            return t.isEmpty() ? null : parse(t, LocalDate.now());
        } catch (Throwable e) {
            return null;
        }
    }

    /** The same from a kept bill photo (its content address); background thread only. */
    static Found read(Context c, android.net.Uri uri) {
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) { buf.write(b, 0, n); if (buf.size() > 20 * 1024 * 1024) return null; }
            return read(buf.toByteArray());
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ parsing (pure)

    static Found parse(String text, LocalDate today) {
        Found f = new Found();
        if (text == null) return f;
        f.date = date(text, today);
        f.months = warrantyMonths(text);
        f.service.addAll(serviceNumbers(text));
        return f;
    }

    private static final Pattern YEARS = Pattern.compile("(?i)(\\d{1,2})\\s*(?:\\+\\s*\\d{1,2}\\s*)?(years?|yrs?|yr\\.?)\\b");
    private static final Pattern MONTHS = Pattern.compile("(?i)(\\d{1,2})\\s*(months?|mths?|mos?)\\b");
    private static final Pattern WARRANTY = Pattern.compile("(?i)(warranty|warrantee|guarantee|గ్యారంటీ|వారంటీ)");

    /**
     * The warranty in months from the bill's words: "1 Year Warranty", "Warranty: 24 months", "2 yrs guarantee" — only a number
     * on the same line as (or right next to) the word warranty / guarantee; 0 when none. Pure.
     */
    static int warrantyMonths(String text) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!WARRANTY.matcher(lines[i]).find()) continue;
            // the word's own line first, then the line after it, then the one before ("1 Year" above "Warranty card")
            String[] near = {lines[i], i + 1 < lines.length ? lines[i + 1] : "", i > 0 ? lines[i - 1] : ""};
            for (String n0 : near) {
                Matcher y = YEARS.matcher(n0), m = MONTHS.matcher(n0);
                int best = 0;
                if (y.find()) { int n = Integer.parseInt(y.group(1)); if (n >= 1 && n <= 10) best = n * 12; }
                if (best == 0 && m.find()) { int n = Integer.parseInt(m.group(1)); if (n >= 1 && n <= 120) best = n; }
                if (best > 0) return best;
            }
        }
        return 0;
    }

    private static final Pattern DMY = Pattern.compile("\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})\\b");
    private static final Pattern YMD = Pattern.compile("\\b(20\\d{2})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})\\b");
    private static final Pattern DMONY = Pattern.compile("(?i)\\b(\\d{1,2})[\\s\\-/.]*(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[\\s\\-/.,]*(\\d{2,4})\\b");
    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /**
     * The bill's date: on a line with "date / dated / bill / invoice" first, else the first date at all; only a real day in the
     * last ten years and not after today (a warranty end printed on it is never taken for the bought date). Pure.
     */
    static LocalDate date(String text, LocalDate today) {
        String[] lines = text.split("\\r?\\n");
        LocalDate any = null;
        for (String l : lines) {
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains("valid") || low.contains("expir") || low.contains("warranty till") || low.contains("warranty upto") || low.contains("due")) continue;
            LocalDate d = firstDate(l, today);
            if (d == null) continue;
            if (low.contains("date") || low.contains("dated") || low.contains("bill") || low.contains("invoice") || low.contains("dt")) return d;
            if (any == null) any = d;
        }
        return any;
    }

    private static LocalDate firstDate(String l, LocalDate today) {
        Matcher m = YMD.matcher(l);
        if (m.find()) { LocalDate d = make(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), today); if (d != null) return d; }
        m = DMONY.matcher(l);
        if (m.find()) {
            String mon = m.group(2).toLowerCase(Locale.ROOT).substring(0, 3);
            int mi = java.util.Arrays.asList(MON).indexOf(mon) + 1;
            LocalDate d = make(year(m.group(3)), mi, Integer.parseInt(m.group(1)), today);
            if (d != null) return d;
        }
        m = DMY.matcher(l);
        while (m.find()) {
            LocalDate d = make(year(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), today); // (India: day first)
            if (d != null) return d;
        }
        return null;
    }

    private static int year(String y) { int n = Integer.parseInt(y); return n < 100 ? 2000 + n : n; }

    private static LocalDate make(int y, int m, int d, LocalDate today) {
        try {
            LocalDate x = LocalDate.of(y, m, d);
            return x.isAfter(today) || x.isBefore(today.minusYears(10)) ? null : x;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern TOLL = Pattern.compile("\\b(1[89]00|1860)[\\s\\-]?(\\d{3,4})[\\s\\-]?(\\d{3,4})\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d])(?:\\+?91[\\s\\-]?)?(0?\\d{2,4}[\\s\\-]?\\d{6,8}|[6-9]\\d{4}[\\s\\-]?\\d{5})(?![\\d])");
    private static final Pattern CARE = Pattern.compile("(?i)(customer\\s*care|care|service|helpline|help\\s*line|toll\\s*free|support|call|contact|ph\\.?|phone|mob|tel)");

    /**
     * The numbers to call for service: toll-free 1800 / 1860 numbers anywhere, and phone numbers on a line that says customer
     * care / service / helpline / call / phone. Never a long run of digits that is not a phone number (invoice, serial, IMEI,
     * card, account). Up to three, as printed with spaces. Pure.
     */
    static List<String> serviceNumbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher t = TOLL.matcher(text);
        while (t.find() && out.size() < 3) add(out, t.group(1) + " " + t.group(2) + " " + t.group(3));
        for (String l : text.split("\\r?\\n")) {
            if (out.size() >= 3) break;
            if (!CARE.matcher(l).find()) continue;
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains("imei") || low.contains("serial") || low.contains("s/n") || low.contains("gst") || low.contains("a/c") || low.contains("account")
                    || low.contains("invoice") || low.contains("card")) continue;
            Matcher p = PHONE.matcher(l);
            while (p.find() && out.size() < 3) {
                String digits = p.group(1).replaceAll("[\\s\\-]", "");
                if (digits.length() < 10 || digits.length() > 12) continue;
                add(out, spaced(digits));
            }
        }
        return out;
    }

    private static String spaced(String d) {
        if (d.length() == 10 && d.charAt(0) >= '6') return d.substring(0, 5) + " " + d.substring(5); // a mobile: 98765 43210
        return d.length() > 7 ? d.substring(0, d.length() - 7) + " " + d.substring(d.length() - 7) : d; // a landline: 040 2345678
    }

    private static void add(List<String> out, String n) {
        String key = n.replaceAll("\\D", "");
        for (String o : out) if (o.replaceAll("\\D", "").equals(key)) return;
        out.add(n);
    }
}
