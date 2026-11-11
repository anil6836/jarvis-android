package com.anil.jarvis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📸 C (Anil, 12 Nov "A,B,C,D,E చేయి"): what a fresh screenshot's printed text offers — a ticket / show / function / bill
 * due with its day and time (→ a reminder), an address (→ the map), a shop's or person's number (→ save as a contact).
 * Never anything from a screen with an OTP, PIN, password, card, bank balance or ID number. Pure; desk-tested.
 */
final class ShotRead {
    private ShotRead() {}

    static final class Offer {
        String kind = "", title = "";
        LocalDateTime when;
        String address, phone, name;

        boolean any() { return when != null || address != null || phone != null; }
    }

    private static final Pattern SECRET = Pattern.compile("(?i)(?<![a-z])(otp|one[- ]?time pass(word|code)?|cvv|cvc|upi pin|m-?pin|pin code is|password|passcode|net ?banking"
            + "|account balance|available balance|avl bal|a/c no|card number|card no|aadhaar|aadhar|pan card|ifsc)(?![a-z])");
    private static final String[][] KINDS = {
            {"flight", "✈️ ఫ్లైట్", "(?i)(?<![a-z])(flight|airlines?|boarding pass|terminal|gate no)(?![a-z])"},
            {"train", "🚆 ట్రైన్", "(?i)(?<![a-z])(pnr|irctc|train|coach|berth|boarding station|chart prepared)(?![a-z])"},
            {"bus", "🚌 బస్", "(?i)(?<![a-z])(bus|redbus|abhibus|tsrtc|apsrtc|tgsrtc|boarding point|seat no)(?![a-z])"},
            {"show", "🎬 షో", "(?i)(?<![a-z])(bookmyshow|district|show ?time|screen ?\\d|audi ?\\d|cinema|movie|concert|event ticket)(?![a-z])"},
            {"bill", "🧾 బిల్ కట్టాల్సిన తేదీ", "(?i)(?<![a-z])(due date|pay by|payable by|last date|bill due|amount due|due on)(?![a-z])"},
            {"function", "💐 ఫంక్షన్", "(?i)(?<![a-z])(invitation|wedding|marriage|reception|ceremony|muhurtham|house ?warming|birthday party)(?![a-z])|ముహూర్తం|వివాహ|ఆహ్వానం"}};
    /** The words on a line that tell its date / time is the one that matters. */
    private static final Pattern KEY_LINE = Pattern.compile("(?i)(depart|dep\\b|journey|boarding|date|show|starts?|due|on\\b|time|ముహూర్తం)");

    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})(?!\\d)");
    private static final Pattern YMD = Pattern.compile("(?<!\\d)(20\\d{2})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})(?!\\d)");
    private static final Pattern DMON = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?(?:[\\s\\-/.,']*(\\d{2,4}))?(?!\\d)");
    private static final Pattern MOND = Pattern.compile("(?i)(?<![a-z])(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?i)(?<![\\d:])([01]?\\d|2[0-3])[:.]([0-5]\\d)(?![\\d])\\s*(a\\.?m\\.?|p\\.?m\\.?)?");
    private static final Pattern TIME_AP = Pattern.compile("(?i)(?<![\\d:])(1[0-2]|0?[1-9])\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])");
    private static final Pattern PIN = Pattern.compile("(?<!\\d)[1-9]\\d{2}\\s?\\d{3}(?!\\d)");
    private static final Pattern ADDR = Pattern.compile("(?i)(?<![a-z])(road|rd|street|st|nagar|colony|near|opp|opposite|cross|main|lane|apartments?|flat|plot|h\\.? ?no|"
            + "sector|village|mandal|district|dist|layout|enclave|towers?|complex|circle|junction|x roads)(?![a-z])");
    private static final Pattern MOBILE = Pattern.compile("(?<![\\d+])(?:\\+?91[\\s\\-]?)?([6-9]\\d{4}[\\s\\-]?\\d{5}|0\\d{2,4}[\\s\\-]?\\d{6,8})(?!\\d)");

    /** What the screenshot offers, or null (nothing, or a screen with secrets on it). now: when it was taken. */
    static Offer parse(String text, LocalDateTime now) {
        if (text == null || text.trim().isEmpty() || SECRET.matcher(text).find()) return null;
        String[] lines = text.split("\\r?\\n");
        Offer o = new Offer();
        for (String[] k : KINDS) if (Pattern.compile(k[2]).matcher(text).find()) { o.kind = k[0]; o.title = k[1]; break; }
        o.when = when(lines, now, o.kind);
        if (o.when != null && o.kind.isEmpty()) { o.kind = "event"; o.title = "📅 తేదీ, టైమ్"; }
        if (o.when == null && !o.kind.isEmpty()) { o.kind = ""; o.title = ""; } // (a ticket word but no day ahead: nothing to remind)
        o.address = address(lines);
        if (o.kind.isEmpty() || "show".equals(o.kind) || "function".equals(o.kind)) contact(lines, o); // (a ticket's helpline is no contact to save)
        return o.any() ? o : null;
    }

    /** The day and time ahead (today … a year), the one on a "departure / date / show / due" line first. */
    static LocalDateTime when(String[] lines, LocalDateTime now, String kind) {
        LocalDate best = null;
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            LocalDate d = date(lines[i], now.toLocalDate());
            if (d == null) continue;
            if (best == null || KEY_LINE.matcher(lines[i]).find() && !KEY_LINE.matcher(lines[at]).find()) { best = d; at = i; }
        }
        if (best == null) return null;
        LocalTime t = null;
        for (int k = 0; k <= 2 && t == null; k++) { // the same line, then the next two, then the one before
            if (at + k < lines.length) t = time(lines[at + k]);
        }
        if (t == null && at > 0) t = time(lines[at - 1]);
        if (t == null) for (String l : lines) if ((t = time(l)) != null && KEY_LINE.matcher(l).find()) break;
        if (t == null) t = "bill".equals(kind) ? LocalTime.of(9, 0) : null;
        if (t == null) return best.isBefore(now.toLocalDate()) ? null : best.atTime(9, 0).isAfter(now) ? best.atTime(9, 0) : null;
        LocalDateTime w = best.atTime(t);
        return w.isAfter(now) ? w : null;
    }

    private static LocalDate date(String l, LocalDate today) {
        Matcher m = YMD.matcher(l);
        if (m.find()) { LocalDate d = make(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), today); if (d != null) return d; }
        m = DMON.matcher(l);
        if (m.find()) {
            LocalDate d = dayMonth(Integer.parseInt(m.group(1)), m.group(2), m.group(3), today);
            if (d != null) return d;
        }
        m = MOND.matcher(l);
        if (m.find()) {
            LocalDate d = dayMonth(Integer.parseInt(m.group(2)), m.group(1), m.group(3), today);
            if (d != null) return d;
        }
        m = DMY.matcher(l);
        while (m.find()) {
            int y = Integer.parseInt(m.group(3));
            LocalDate d = make(y < 100 ? 2000 + y : y, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), today); // (India: day first)
            if (d != null) return d;
        }
        return null;
    }

    private static LocalDate dayMonth(int day, String mon, String year, LocalDate today) {
        int mi = java.util.Arrays.asList(MON).indexOf(mon.toLowerCase(Locale.ROOT).substring(0, 3)) + 1;
        if (mi <= 0) return null;
        if (year != null && !year.isEmpty()) { int y = Integer.parseInt(year); return make(y < 100 ? 2000 + y : y, mi, day, today); }
        LocalDate d = make(today.getYear(), mi, day, today);
        return d != null ? d : make(today.getYear() + 1, mi, day, today);
    }

    /** A real day from today to a year ahead, else null. */
    private static LocalDate make(int y, int m, int d, LocalDate today) {
        try {
            LocalDate x = LocalDate.of(y, m, d);
            return x.isBefore(today) || x.isAfter(today.plusDays(366)) ? null : x;
        } catch (Exception e) {
            return null;
        }
    }

    static LocalTime time(String l) {
        Matcher m = TIME.matcher(l);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
            String ap = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT).replace(".", "");
            if (ap.equals("pm") && h < 12) h += 12;
            if (ap.equals("am") && h == 12) h = 0;
            return LocalTime.of(h, mi);
        }
        m = TIME_AP.matcher(l);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            boolean pm = m.group(2).toLowerCase(Locale.ROOT).startsWith("p");
            return LocalTime.of(pm ? (h % 12) + 12 : h % 12, 0);
        }
        return null;
    }

    /** An address: a line with a PIN code or two address words, with the line before it; at most 120 letters. */
    static String address(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.matches(".*\\p{L}.*")) continue;
            Matcher w = ADDR.matcher(l);
            int words = 0;
            while (w.find()) words++;
            boolean pin = PIN.matcher(l).find() && words >= 1 || PIN.matcher(l).find() && i > 0 && ADDR.matcher(lines[i - 1]).find();
            if (!pin && words < 2) continue;
            String a = (i > 0 && ADDR.matcher(lines[i - 1]).find() ? lines[i - 1].trim() + ", " : "") + l;
            if (i + 1 < lines.length && PIN.matcher(lines[i + 1]).find() && !pin) a += ", " + lines[i + 1].trim();
            a = a.replaceAll("\\s+", " ");
            return a.length() > 120 ? a.substring(0, 120).trim() : a;
        }
        return null;
    }

    /** One phone number with a name next to it (a shop's card, a visiting card): both, or nothing. */
    static void contact(String[] lines, Offer o) {
        List<String> found = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MOBILE.matcher(lines[i]);
            while (m.find()) { String n = m.group(1).replaceAll("[\\s\\-]", ""); if (!found.contains(n)) { found.add(n); at = i; } }
        }
        if (found.size() != 1) return; // (none, or a list of many: not a card)
        String name = null;
        for (int k : new int[]{at, at - 1, at - 2, at + 1}) {
            if (k < 0 || k >= lines.length) continue;
            String l = lines[k].replaceAll("(?i)(mob(ile)?|ph(one)?|cell|tel|call|contact|whatsapp)\\s*(no\\.?)?\\s*[:.\\-]?", "").replaceAll("[+\\d\\s\\-:]{6,}", " ").trim();
            if (l.replaceAll("[^\\p{L}]", "").length() >= 3 && l.length() <= 40 && !ADDR.matcher(l).find()) { name = l; break; }
        }
        if (name == null) return;
        o.phone = found.get(0);
        o.name = name;
    }
}
