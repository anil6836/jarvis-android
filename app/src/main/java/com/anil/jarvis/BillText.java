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
        /** A guess at the shop's name from the bill's top lines (shown to him to change), or null. */
        String shop;
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

    /** From an upright picture (the 🧾 warranty form's own reading); background thread only. Null when nothing could be read. */
    static Found read(Bitmap b) {
        try {
            String t = b == null ? "" : OfflineRead.text(b);
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

    /** 🧾 "బిల్లు → ఖర్చు" (11 Nov, his "ఫోన్‌లోనే"): what an expense bill says — the total paid, the shop, the day, a guessed kind. */
    static final class Spend {
        double total;
        String shop, category;
        LocalDate date;
    }

    /** An expense bill read from an upright picture on the phone; background thread only. Null when nothing could be read. */
    static Spend readSpend(Bitmap b) {
        try {
            String t = b == null ? "" : OfflineRead.text(b);
            return t.isEmpty() ? null : spend(t, LocalDate.now());
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ parsing (pure)

    static Spend spend(String text, LocalDate today) {
        Spend s = new Spend();
        if (text == null) return s;
        s.total = total(text);
        s.shop = shop(text);
        s.date = date(text, today);
        s.category = category(text);
        return s;
    }

    private static final Pattern AMOUNT = Pattern.compile("(?i)(?<![\\d.,])(?:₹|rs\\.?|inr)?\\s*(\\d{1,3}(?:,\\d{2,3})+|\\d{1,7})(?:\\.(\\d{1,2}))?(?![\\d,%])(?!\\s*%)");
    /** The paid total's words, surest first. */
    private static final String[][] TOTAL_WORDS = {
            {"grand total", "net payable", "amount payable", "total payable", "net amount", "amount to pay", "to pay", "net total", "bill amount",
                    "total amount", "invoice total", "amount paid", "paid amount", "total due", "amount due"},
            {"total"}};
    /** A "total" line that isn't the bill's total. */
    private static final Pattern NOT_TOTAL = Pattern.compile("(?i)(sub\\s*-?\\s*total|qty|quantity|items?\\b|tax|gst|cgst|sgst|igst|cess|sav(ed|ing)|discount|mrp|round|tender|change|weight|kg)");

    /**
     * The total he paid: the number on (or, when the reader split the columns, just under) a "Grand Total / Net Payable /
     * Amount Paid …" line, else a plain "Total" line that isn't a sub-total / tax / items / savings line; the biggest of the surest
     * kind found. 0 when none (he types it). Pure.
     */
    static double total(String text) {
        if (text == null) return 0;
        String[] lines = text.split("\\r?\\n");
        double best = 0;
        for (String[] words : TOTAL_WORDS) {
            for (int i = 0; i < lines.length; i++) {
                String low = lines[i].toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                boolean hit = false;
                for (String w : words) if (low.matches(".*(?<![a-z])" + Pattern.quote(w) + "(?![a-z]).*")) { hit = true; break; }
                if (!hit || words.length == 1 && NOT_TOTAL.matcher(low).find()) continue;
                double v = lastAmount(lines[i]);
                for (int k = 1; v <= 0 && k <= 2 && i + k < lines.length; k++) // (the amount in its own column: the next line or two, numbers only)
                    if (!lines[i + k].replaceAll("(?i)₹|rs\\.?|inr", "").matches(".*\\p{L}.*")) v = lastAmount(lines[i + k]);
                if (v > best) best = v;
            }
            if (best > 0) return best;
        }
        return 0;
    }

    private static double lastAmount(String line) {
        Matcher m = AMOUNT.matcher(line);
        double v = 0;
        while (m.find()) {
            try {
                double x = Double.parseDouble(m.group(1).replace(",", "") + (m.group(2) == null ? "" : "." + m.group(2)));
                if (x >= 1 && x <= 1_000_000) v = x;
            } catch (Exception ignored) {}
        }
        return v;
    }

    /** Words → the expense kinds Jarvis already counts by (food, groceries, fuel, …); the first kind whose word is on the bill. */
    private static final String[][] KINDS = {
            {"charging", "ev charging", "charging station", "kwh", "chargezone", "statiq", "tata power ez"},
            {"bills", "electricity", "tsspdcl", "apspdcl", "tgspdcl", "water bill", "broadband", "fibernet", "recharge", "postpaid", "lpg", "indane",
                    "bharatgas", "bharat gas", "hp gas", "cylinder"},
            {"fuel", "petrol", "diesel", "fuel", "filling station", "hpcl", "bpcl", "iocl", "indian oil", "bharat petroleum", "hindustan petroleum",
                    "nayara", "fuel station"},
            {"medicine", "pharmacy", "pharma", "medical", "medicals", "chemist", "chemists", "drug", "apollo", "medplus", "tablet", "tablets", "syrup"},
            {"groceries", "supermarket", "super market", "dmart", "d mart", "d-mart", "ratnadeep", "reliance fresh", "reliance smart", "kirana",
                    "general store", "general stores", "provision", "provisions", "vegetables", "fruits", "grocery", "groceries", "bigbasket", "zepto",
                    "blinkit", "instamart", "vijetha", "heritage fresh", "spencer", "hypermarket", "mart"},
            {"food", "restaurant", "cafe", "café", "bakery", "bakers", "biryani", "kitchen", "dhaba", "tiffin", "tiffins", "sweets", "foods", "swiggy",
                    "zomato", "pizza", "burger", "kfc", "mcdonald", "domino", "dominos", "juice", "mess", "bar", "hotel"},
            {"travel", "irctc", "railway", "train", "tsrtc", "apsrtc", "tgsrtc", "redbus", "uber", "ola", "rapido", "toll", "fastag", "travels",
                    "flight", "airlines", "metro", "bus"},
            {"car", "car wash", "car care", "car service", "car spa", "tyres", "tyre", "wheel alignment"},
            {"shopping", "electronics", "mobiles", "fashion", "textiles", "garments", "footwear", "trends", "lifestyle", "mall", "amazon",
                    "flipkart", "myntra", "croma", "reliance digital", "bazaar", "store", "stores", "shop"}};

    /** The bill's kind of expense, guessed from its words ("other" when none fits). Pure. */
    static String category(String text) {
        String low = " " + (text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9é ]+", " ").replaceAll("\\s+", " ")) + " ";
        for (String[] k : KINDS)
            for (int i = 1; i < k.length; i++) if (low.contains(" " + k[i] + " ")) return k[0];
        return "other";
    }

    static Found parse(String text, LocalDate today) {
        Found f = new Found();
        if (text == null) return f;
        f.date = date(text, today);
        f.months = warrantyMonths(text);
        f.service.addAll(serviceNumbers(text));
        f.shop = shop(text);
        return f;
    }

    private static final java.util.Set<String> HEAD = new java.util.HashSet<>(java.util.Arrays.asList(
            "invoice", "receipt", "memo", "bill", "original", "duplicate", "triplicate", "recipient", "estimate", "quotation", "welcome",
            "thank", "thanks", "copy", "date", "address", "email", "www", "http", "gstin", "gst", "cin", "fssai", "tax", "cash", "credit",
            "total", "subtotal", "amount", "qty", "item", "items", "description", "name", "rate", "price", "discount", "net", "paid", "balance",
            "customer", "mobile", "phone", "ph", "tel", "no", "sl", "sno", "hsn", "sac", "state", "code", "place", "supply", "time", "payment",
            "mode", "upi", "card", "terms", "conditions"));

    /**
     * The shop's name, guessed: the first of the bill's top six lines with 4+ letters, no long number, that isn't a heading
     * ("Tax Invoice", "Cash Memo", "Original for Recipient"), an address / web / GST line, or about the buyer. Null when none. Pure.
     */
    static String shop(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0, seen = 0; i < lines.length && seen < 6; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            seen++;
            if (l.replaceAll("[^\\p{L}]", "").length() < 4 || l.matches(".*\\d{5,}.*") || l.contains("@") || NOT_CARE.matcher(l).find()) continue;
            boolean head = false;
            for (String w : l.toLowerCase(Locale.ROOT).split("[^a-z]+")) if (HEAD.contains(w)) { head = true; break; }
            if (head) continue;
            return l.length() > 40 ? l.substring(0, 40).trim() : l;
        }
        return null;
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

    private static final Pattern TOLL = Pattern.compile("(?<![\\d])(1800|1860)[\\s\\-]?(\\d{3,4})[\\s\\-]?(\\d{3,4})(?![\\d])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d+])(\\+?91[\\s\\-]?)?(0\\d{2,4}[\\s\\-]?\\d{6,8}|[6-9]\\d{4}[\\s\\-]?\\d{5})(?![\\d])");
    /** A line about calling the shop / the company (whole words only: "Hotel", "PhonePe", "Telangana" are not "tel" / "ph"). */
    private static final Pattern CARE = Pattern.compile("(?i)(?<![a-z])(customer\\s*care|care|service|services|helpline|help\\s*line|toll\\s*free|support|call|contact|phone|ph|tel|telephone|landline)(?![a-z])");
    /** A line about him, the payment or a document: never a number to call from there. */
    private static final Pattern NOT_CARE = Pattern.compile("(?i)(?<![a-z])(ref|utr|upi|txn|pan|mob|auth|gst|gstin|acct|imei|a/c|s/n|dl|rc)(?![a-z])"
            + "|(?i)(serial|sr\\.?\\s*no|ac\\s*no|account|transaction|order|invoice|bill\\s*no|receipt\\s*no|policy|card|aadhaar|aadhar|cust\\s*(name|id|no)"
            + "|customer\\s*(name|id|no)|bill\\s*to|ship\\s*to|buyer|consignee|mobile\\s*no|approval)");

    /**
     * The numbers to call for service: toll-free 1800 / 1860 numbers and phone numbers (a mobile, or a landline written with its
     * 0 STD code) on a line that says customer care / service / helpline / call / phone — never from a line about him, the
     * payment or the bill (invoice, order, account, UPI / UTR / ref, card, policy, IMEI / serial, GST, bill to / buyer, his mobile).
     * Up to three, as printed with spaces. Pure.
     */
    static List<String> serviceNumbers(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            if (out.size() >= 3) break;
            if (NOT_CARE.matcher(l).find()) continue;
            Matcher t = TOLL.matcher(l);
            while (t.find() && out.size() < 3) add(out, t.group(1) + " " + t.group(2) + " " + t.group(3));
            if (!CARE.matcher(l).find()) continue;
            Matcher p = PHONE.matcher(l);
            while (p.find() && out.size() < 3) {
                String digits = p.group(2).replaceAll("[\\s\\-]", "");
                boolean mobile = digits.length() == 10 && digits.charAt(0) >= '6', land = digits.charAt(0) == '0' && digits.length() >= 10 && digits.length() <= 12;
                if (!mobile && !land) continue;
                if (digits.startsWith("1800") || digits.startsWith("1860")) continue;
                add(out, mobile ? spaced(digits) : p.group(2).trim().replaceAll("[\\s\\-]+", " ")); // (a landline as printed: its STD code's own length)
            }
        }
        return out;
    }

    private static String spaced(String d) { return d.substring(0, 5) + " " + d.substring(5); } // a mobile: 98765 43210

    private static void add(List<String> out, String n) {
        String key = n.replaceAll("\\D", "");
        for (String o : out) if (o.replaceAll("\\D", "").equals(key)) return;
        out.add(n);
    }
}
