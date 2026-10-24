package com.anil.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🛣️ The car screen's road cards, the parts that need no Android (desk-tested): which arrow a maps app's turn words
 * mean, the minutes in its "15 min · 7 km · 5:42 PM" line, the drive-time chip's words, and the home-coming guess from
 * the GPS alone (the straight-line distance to home shrinking for a minute or more; nothing is sent anywhere — the
 * home card and the AC question are only on this screen).
 */
final class CarRoad {
    private CarRoad() {}

    // ================================================================ the maps app's turn

    /** The arrow for a turn's words (English or Telugu, as the maps app shows them): a CarArt.Icon kind. Pure. */
    static int turnIcon(String words) {
        String w = words == null ? "" : words.toLowerCase(Locale.ROOT);
        if (w.contains("u-turn") || w.contains("uturn") || w.contains("u turn") || w.contains("యూ-టర్న్") || w.contains("యూ టర్న్")) return CarArt.Icon.UTURN;
        if (w.contains("roundabout") || w.contains("rotary") || w.contains("traffic circle") || w.contains("రౌండ్") || w.contains("సర్కిల్")) return CarArt.Icon.ROUND;
        if (w.contains("arriv") || w.contains("destination") || w.contains("చేరుకు") || w.contains("గమ్య")) return CarArt.Icon.ARRIVE;
        boolean left = w.matches("(?s).*\\bleft\\b.*") || w.contains("ఎడమ"), right = w.matches("(?s).*\\bright\\b.*") || w.contains("కుడి");
        boolean slight = w.contains("slight") || w.contains("keep") || w.contains("bear") || w.contains("fork") || w.contains("exit") || w.contains("కొంచెం");
        if (left && !right) return slight ? CarArt.Icon.SLIGHT_LEFT : CarArt.Icon.LEFT;
        if (right && !left) return slight ? CarArt.Icon.SLIGHT_RIGHT : CarArt.Icon.RIGHT;
        return CarArt.Icon.AHEAD;
    }

    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*(?:hrs?\\b|hours?\\b|h\\b|గం)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINS = Pattern.compile("(\\d+)\\s*(?:mins?\\b|minutes?\\b|నిమి|ని(?=[\\s.,·]|$))", Pattern.CASE_INSENSITIVE);

    /** The minutes left in a maps app's trip line ("1 hr 5 min · 52 km · 5:42 PM"), or -1. Pure. */
    static int minutesIn(String trip) {
        if (trip == null || trip.isEmpty()) return -1;
        Matcher h = HOURS.matcher(trip), m = MINS.matcher(trip);
        int hours = h.find() ? Integer.parseInt(h.group(1)) : -1, mins = m.find() ? Integer.parseInt(m.group(1)) : -1;
        if (hours < 0 && mins < 0) return -1;
        return Math.max(0, hours) * 60 + Math.max(0, mins);
    }

    private static final Pattern DIST = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|కి\\.?\\s?మీ|m\\b|మీ|ft\\b(?!\\s*(?:road|rd)\\b))(?!\\s*(?:road|rd)\\b)", Pattern.CASE_INSENSITIVE); // (not "100 Feet Rd", "80 ft road")

    /** The distance to the turn in its words ("300 m", "0.4 km", "500 ft", "200 మీ"), in metres, or -1. Pure. */
    static int metersIn(String words) {
        if (words == null) return -1;
        Matcher m = DIST.matcher(words);
        if (!m.find()) return -1;
        double v;
        try { v = Double.parseDouble(m.group(1).replace(',', '.')); } catch (Exception e) { return -1; }
        String u = m.group(2).toLowerCase(Locale.ROOT);
        double metres = u.startsWith("km") || u.startsWith("కి") ? v * 1000 : u.startsWith("ft") ? v * 0.3048 : v;
        return (int) Math.round(metres);
    }

    /** Point at the turn: a left / right kind of turn, 400 m or nearer. Pure. Returns -1 left, +1 right, 0 none. */
    static int pointFor(int icon, int metres) {
        if (metres < 0 || metres > 400) return 0;
        if (icon == CarArt.Icon.LEFT || icon == CarArt.Icon.SLIGHT_LEFT) return -1;
        if (icon == CarArt.Icon.RIGHT || icon == CarArt.Icon.SLIGHT_RIGHT || icon == CarArt.Icon.UTURN) return 1; // (India drives on the left: a U-turn goes round to the right)
        return 0;
    }

    // ================================================================ parked: the trip's numbers, the games

    /**
     * The trip card's two lines: "🏁 34 కి.మీ · 52 ని" and "సగటు 39 కి.మీ/గం · డీజిల్ సుమారు 2.1 లీ" (the diesel only
     * with a mileage). Numbers only, no places. null under a kilometre. Pure.
     */
    static String[] tripLines(double km, long movingSec, long minutes, double kmpl) {
        if (km < 1) return null;
        String d = km < 10 ? String.format(Locale.ROOT, "%.1f", km) : String.valueOf(Math.round(km));
        String t = minutes < 60 ? minutes + " ని" : minutes / 60 + " గం" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " ని");
        String l2 = "";
        if (movingSec >= 120) l2 = "సగటు " + Math.round(km / (movingSec / 3600.0)) + " కి.మీ/గం";
        if (kmpl > 3 && kmpl < 40) l2 += (l2.isEmpty() ? "" : " · ") + "డీజిల్ సుమారు " + String.format(Locale.ROOT, "%.1f", km / kmpl) + " లీ";
        return new String[]{"🏁 ఈ ప్రయాణం " + d + " కి.మీ · " + t, l2};
    }

    private static final String[] PRAISE = {"సరైన జవాబు", "సరిగ్గా", "కరెక్ట్", "correct", "శభాష్", "భలే", "అద్భుతం", "గెలిచ", "సూపర్", "well done", "బాగా చెప్పారు", "బాగా చెప్పావ్"};
    private static final String[] NOT = {"కాదు", "తప్పు", "not ", "wrong", "incorrect", "లేదు"};

    /** A game's reply that says the answer was right (for Jarvis's clap) — but not "అది సరైన జవాబు కాదు". Pure. */
    static boolean praise(String reply) {
        if (reply == null) return false;
        for (String sentence : reply.toLowerCase(Locale.ROOT).split("[.!?\\n]")) {
            boolean good = false;
            for (String w : PRAISE) if (sentence.contains(w)) { good = true; break; }
            if (!good) continue;
            for (String w : NOT) if (sentence.contains(w)) { good = false; break; }
            if (good) return true;
        }
        return false;
    }

    // ================================================================ the drive-time chip

    static final int AMBER_AT = 105, BREAK_AT = 120;

    /** "⏱ 45 ని" / "⏱ 1 గం 45 ని" / "☕ 2 గం 5 ని · టీ బ్రేక్?" ("" under a minute). Pure. */
    static String driveLine(long minutes) {
        if (minutes < 1) return "";
        long h = minutes / 60, m = minutes % 60;
        String t = h == 0 ? m + " ని" : h + " గం" + (m == 0 ? "" : " " + m + " ని");
        return minutes >= BREAK_AT ? "☕ " + t + " · టీ బ్రేక్?" : "⏱ " + t + " డ్రైవ్";
    }

    /** 0: plain, 1: amber (near 2 hours), 2: break time. Pure. */
    static int driveLevel(long minutes) { return minutes >= BREAK_AT ? 2 : minutes >= AMBER_AT ? 1 : 0; }

    // ================================================================ coming home (the GPS alone)

    /**
     * The straight-line distance to home, sampled every ten seconds. Coming home = it shrank by half a kilometre or
     * more over the last one to four minutes, and he was at least 2 km away this trip. The minutes left: that distance
     * at the speed it shrinks (never faster than 70 km/h's worth, never slower than 12), a guess ("సుమారు").
     */
    static final class Home {
        private final long[] at = new long[30];
        private final double[] km = new double[30];
        private int n;
        private double farthest;

        void reset() { n = 0; farthest = 0; }

        void sample(long now, double distKm) {
            if (n > 0 && now - at[(n - 1) % at.length] < 10_000) return; // (one in ten seconds: five minutes kept)
            at[n % at.length] = now;
            km[n % km.length] = distKm;
            n++;
            farthest = Math.max(farthest, distKm);
        }

        double nowKm() { return n == 0 ? -1 : km[(n - 1) % km.length]; }

        /** km per minute that the distance shrinks (over the samples 1–4 minutes old), or 0. */
        private double rate() {
            if (n < 2) return 0;
            int last = (n - 1) % at.length;
            long tNow = at[last];
            for (int k = Math.min(n, at.length) - 1; k >= 1; k--) { // the oldest sample still inside 4 minutes
                int i = (n - 1 - k) % at.length;
                long age = tNow - at[i];
                if (age > 4 * 60_000L) continue;
                if (age < 60_000L) return 0;
                double shrank = km[i] - km[last];
                return shrank >= 0.5 ? shrank / (age / 60_000.0) : 0;
            }
            return 0;
        }

        boolean coming() { return farthest >= 2 && nowKm() >= 0 && rate() > 0; }

        /** Minutes to home (a guess), or -1 when he isn't coming home — or the last sample is over 30 s old (no fresh GPS). */
        int minutes(long now) {
            if (n == 0 || now - at[(n - 1) % at.length] > 30_000) return -1;
            double r = rate(), d = nowKm();
            if (!coming() || r <= 0) return -1;
            r = Math.max(12 / 60.0, Math.min(70 / 60.0, r));
            return (int) Math.max(1, Math.round(d / r));
        }
    }

    /** The AC question: when home is 4 to 12 minutes away. Pure. */
    static boolean acTime(int minutesHome) { return minutesHome >= 4 && minutesHome <= 12; }

    /** His own AC among the smart-home devices (not amma's): the device's base name, or null. Pure. */
    static String myAc(Iterable<String> bases) {
        String any = null;
        for (String b : bases) {
            String l = b == null ? "" : b.toLowerCase(Locale.ROOT);
            if (!l.matches(".*\\bac\\b.*")) continue;
            if (l.matches(".*\\b(amma|mom|mother|nanna|dad)\\b.*")) continue;
            if (l.matches(".*\\b(anil|my|son)\\b.*")) return b;
            if (any == null) any = b;
        }
        return any;
    }
}
