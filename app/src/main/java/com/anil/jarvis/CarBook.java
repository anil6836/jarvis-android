package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 🚗 The car's account, with nothing of Android in it (desk-tested: CarBookTest). Anil, 22 Oct: diesel, told by voice or
 * typed in Settings; km from his meter (odometer) when he says it, else the trip diary's GPS car trips (approx only).
 *
 * State (one JSON): car {name, fuel, every_km, every_months, svc0_date, svc0_odo}, fills [{id, d, at, t, rs, l, le, odo,
 * full}], odos [{id, d, at, t, odo}] (a meter reading alone), svcs [{id, d, at, t, rs, odo, what}]. "at" orders the
 * entries (today: when told; an earlier day: that day's noon), "t" is when he told it (undo takes the newest).
 *
 * Mileage: the full-tank way when he said "ఫుల్" at least twice (km between two full fills ÷ the litres put in after the
 * first, up to and with the second: part fills between are counted); otherwise between any two fills (the tank may not
 * be at the same level: "సుమారు"). km from the two meter readings, else GPS trips between the two fills (approx; only
 * the trips driven while the drive alerts ran). A mileage drop is told only from meter readings (GPS can miss trips).
 */
final class CarBook {
    private CarBook() {}

    static final double MIN_RATE = 40, MAX_RATE = 250, MAX_RS = 50_000, MAX_L = 200, MAX_ODO = 2_000_000;
    /** A part of the drive between two fills is used when it is at least this long and the km per litre is believable. */
    static final double MIN_SEG_KM = 20, MIN_KMPL = 3, MAX_KMPL = 40;
    /** Mileage drop: the newest (meter) part below this share of the usual (at least 100 km, 2 parts before it). */
    static final double DROP = 0.85;
    /** Service "soon": this many days or km before it is due. */
    static final int SOON_DAYS = 15, SOON_KM = 500;
    static final int DEFAULT_EVERY_KM = 10_000, DEFAULT_EVERY_MONTHS = 12;
    static final String[] MONTHS = HouseBook.MONTHS;

    // ================================================================ state

    static JSONObject parse(String s) {
        JSONObject st;
        try { st = s == null || s.trim().isEmpty() ? new JSONObject() : new JSONObject(s); } catch (Exception e) { st = new JSONObject(); }
        try {
            for (String k : new String[]{"fills", "odos", "svcs"}) if (st.optJSONArray(k) == null) st.put(k, new JSONArray());
        } catch (Exception ignored) {}
        return st;
    }

    static JSONObject car(JSONObject st) { return st.optJSONObject("car"); }

    static boolean isSet(JSONObject st) { return car(st) != null; }

    static String fuel(JSONObject st) {
        JSONObject c = car(st);
        String f = c == null ? "" : c.optString("fuel");
        return f.isEmpty() ? "diesel" : f;
    }

    /** "డీజిల్", "పెట్రోల్", "CNG". */
    static String fuelWord(String f) {
        if ("petrol".equals(f)) return "పెట్రోల్";
        if ("cng".equals(f)) return "CNG";
        return "డీజిల్";
    }

    /** His word for the fuel ("డీజిల్", "petrol"...) → diesel / petrol / cng, or null. */
    static String fuelKey(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (t.contains("cng") || t.contains("సీఎన్‌జీ") || t.contains("గ్యాస్")) return "cng";
        if (t.contains("petrol") || t.contains("పెట్రోల్")) return "petrol";
        if (t.contains("diesel") || t.contains("డీజిల్") || t.contains("డీజల్")) return "diesel";
        return null;
    }

    static String name(JSONObject st) {
        JSONObject c = car(st);
        String n = c == null ? "" : c.optString("name").trim();
        return n.isEmpty() ? "కారు" : n;
    }

    static int everyKm(JSONObject st) { JSONObject c = car(st); return c == null ? DEFAULT_EVERY_KM : c.optInt("every_km", DEFAULT_EVERY_KM); }

    static int everyMonths(JSONObject st) { JSONObject c = car(st); return c == null ? DEFAULT_EVERY_MONTHS : c.optInt("every_months", DEFAULT_EVERY_MONTHS); }

    /** When an entry of that day happened: today = now; an earlier day = its noon (the order among a day's entries: as told). */
    static long at(LocalDate d, LocalDate today, long now, ZoneId z) {
        if (d.equals(today)) return now;
        return d.atTime(12, 0).atZone(z).toInstant().toEpochMilli();
    }

    private static int seq;

    private static synchronized String newId(long t) { seq = (seq + 1) % 1296; return Long.toString(t, 36) + Integer.toString(seq, 36); }

    /**
     * The car's details (the first time, or a change): only the fields given change. lastDate / lastOdo: the last service
     * before Jarvis kept the account (from his service book). Returns null when fine, else what is wrong (for him).
     */
    static String setup(JSONObject st, String nm, String fuel, int everyKm, int everyMonths, LocalDate lastDate, double lastOdo, LocalDate today, long now, ZoneId z) throws Exception {
        if (everyKm != 0 && (everyKm < 1000 || everyKm > 60_000)) return "సర్వీస్ ఎన్ని కి.మీ కి: 1,000 నుంచి 60,000 మధ్య చెప్పండి.";
        if (everyMonths != 0 && (everyMonths < 1 || everyMonths > 36)) return "సర్వీస్ ఎన్ని నెలలకి: 1 నుంచి 36 మధ్య చెప్పండి.";
        if (lastDate != null && (lastDate.isAfter(today) || lastDate.isBefore(today.minusYears(5)))) return "చివరి సర్వీస్ తేదీ ఈరోజు కంటే ముందుది, 5 ఏళ్ల లోపుది ఉండాలి.";
        if (!Double.isNaN(lastOdo) && (lastOdo < 0 || lastOdo > MAX_ODO)) return "చివరి సర్వీస్ మీటర్ రీడింగ్ సరిగ్గా లేదు.";
        JSONObject c = car(st);
        if (c == null) {
            c = new JSONObject().put("name", "కారు").put("fuel", "diesel").put("every_km", DEFAULT_EVERY_KM).put("every_months", DEFAULT_EVERY_MONTHS)
                    .put("since", today.toString());
            st.put("car", c);
        }
        if (nm != null && !nm.trim().isEmpty()) c.put("name", nm.trim().length() > 30 ? nm.trim().substring(0, 30) : nm.trim());
        if (fuel != null) c.put("fuel", fuel);
        if (everyKm != 0) c.put("every_km", everyKm);
        if (everyMonths != 0) c.put("every_months", everyMonths);
        if (lastDate != null && !lastDate.toString().equals(c.optString("svc0_date"))) {
            c.remove("svc0_odo"); // (a reading of another day's service no longer fits)
            c.put("svc0_date", lastDate.toString()).put("svc0_at", at(lastDate, today, now, z));
        }
        if (!Double.isNaN(lastOdo)) {
            if (c.optString("svc0_date").isEmpty()) return "చివరి సర్వీస్ మీటర్ రీడింగ్‌తో పాటు ఆ సర్వీస్ తేదీ కూడా చెప్పండి.";
            Object old = c.opt("svc0_odo"), oldAt = c.opt("svc0_at");
            c.remove("svc0_odo");
            long at = place(st, c.optString("svc0_date"), at(LocalDate.parse(c.optString("svc0_date")), today, now, z), lastOdo);
            JSONObject bad = odoCheck(st, c.optString("svc0_date"), lastOdo, true);
            if (bad != null) { if (old != null) c.put("svc0_odo", old); return bad.optString("say"); }
            c.put("svc0_odo", Math.round(lastOdo)).put("svc0_at", at);
            if (oldAt == null) c.put("svc0_at", at);
        }
        return null;
    }

    // ================================================================ entries

    static List<JSONObject> list(JSONObject st, String k) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = st.optJSONArray(k);
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(o); }
        out.sort((x, y) -> {
            int c = Long.compare(x.optLong("at"), y.optLong("at"));
            if (c == 0) c = Double.compare(x.optDouble("odo", Double.MAX_VALUE), y.optDouble("odo", Double.MAX_VALUE)); // (one moment: by the meter, none last)
            return c != 0 ? c : Long.compare(x.optLong("t"), y.optLong("t"));
        });
        return out;
    }

    static List<JSONObject> fills(JSONObject st) { return list(st, "fills"); }

    /** A meter reading: when (for the order), the km, its day. */
    static final class Reading {
        final long at;
        final double odo;
        final String d;

        Reading(long at, double odo, String d) { this.at = at; this.odo = odo; this.d = d; }
    }

    /** Every meter reading (fills, readings alone, services, the last service from setup), oldest first (one moment: by the km). */
    static List<Reading> readings(JSONObject st) {
        List<Reading> out = new ArrayList<>();
        JSONObject c = car(st);
        if (c != null && c.has("svc0_odo") && c.has("svc0_at")) out.add(new Reading(c.optLong("svc0_at"), c.optDouble("svc0_odo"), c.optString("svc0_date")));
        for (String k : new String[]{"fills", "odos", "svcs"})
            for (JSONObject o : list(st, k)) if (o.has("odo")) out.add(new Reading(o.optLong("at"), o.optDouble("odo"), o.optString("d")));
        out.sort((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Double.compare(a.odo, b.odo));
        return out;
    }

    /** Most km a car goes in a day: a bigger jump from the reading before is asked about (an extra digit said). */
    static final double MAX_KM_A_DAY = 1500;

    /**
     * A meter reading must fit between the readings of the days before and after (he may have misspoken), and not jump
     * more than a car can go (unless he said it is right: sure). Readings of the same day are put in order by their km
     * (place), never refused. Null: fits; else {error odo / big_jump, say}.
     */
    static JSONObject odoCheck(JSONObject st, String d, double odo, boolean sure) throws Exception {
        if (Double.isNaN(odo)) return null;
        if (odo <= 0 || odo > MAX_ODO) return new JSONObject().put("error", "odo").put("say", "మీటర్ రీడింగ్ సరిగ్గా లేదు (" + km(odo) + ").");
        Reading before = null, after = null;
        for (Reading r : readings(st)) {
            int c = r.d.compareTo(d);
            if (c < 0) before = r;
            else if (c > 0 && after == null) after = r;
        }
        if (before != null && odo < before.odo)
            return new JSONObject().put("error", "odo").put("say", "మీటర్ " + km(odo) + " అంటున్నారు, కానీ " + dayName(before.d) + " నాటికే " + km(before.odo)
                    + " చెప్పారు. మొత్తం మీటర్ (ODO) ఒకసారి చూసి చెప్పండి.");
        if (after != null && odo > after.odo)
            return new JSONObject().put("error", "odo").put("say", "మీటర్ " + km(odo) + " అంటున్నారు, కానీ తర్వాత " + dayName(after.d) + " కి " + km(after.odo)
                    + " ఉంది. ఒకసారి చూసి చెప్పండి.");
        if (!sure) {
            for (Reading r : readings(st)) { // (the same day: never more than a day's driving apart)
                if (r.d.equals(d) && Math.abs(odo - r.odo) > MAX_KM_A_DAY) return jump(odo, r, Math.abs(odo - r.odo));
            }
            if (before != null && odo - before.odo > MAX_KM_A_DAY * daysBetween(before.d, d)) return jump(odo, before, odo - before.odo);
            if (after != null && after.odo - odo > MAX_KM_A_DAY * daysBetween(d, after.d)) return jump(odo, after, after.odo - odo); // (a digit missing)
        }
        return null;
    }

    private static long daysBetween(String a, String b) { return Math.max(1, ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b))); }

    private static JSONObject jump(double odo, Reading other, double gap) throws Exception {
        return new JSONObject().put("error", "big_jump").put("say", "మీటర్ " + km(odo) + " అంటున్నారు; " + dayName(other.d) + " కి " + km(other.odo)
                + " చెప్పారు. అంటే మధ్యలో " + km(gap) + " కి.మీ. ఇది సరైనదేనా? (ఒక అంకె ఎక్కువ / తక్కువ పడిందేమో చూడండి)");
    }

    /** Where in its day an entry goes: after the same day's readings with fewer km, before those with more. */
    static long place(JSONObject st, String d, long at, double odo) {
        if (Double.isNaN(odo)) return at;
        long low = Long.MIN_VALUE, up = Long.MAX_VALUE;
        for (Reading r : readings(st)) {
            if (!r.d.equals(d)) continue;
            if (r.odo <= odo) low = Math.max(low, r.at);
            else up = Math.min(up, r.at);
        }
        if (low != Long.MIN_VALUE && at <= low) at = low + 1;
        if (up != Long.MAX_VALUE && at >= up) at = low != Long.MIN_VALUE && low >= up ? up : up - 1; // (no room: the same moment, ordered by km)
        return at;
    }

    /** The newest fill before (or at) that time whose litres he told: its ₹ per litre, or NaN. */
    static double lastRate(JSONObject st, long at) {
        double r = Double.NaN;
        for (JSONObject f : fills(st)) {
            if (f.optLong("at") > at) break;
            if (!f.optBoolean("le") && f.optDouble("l", 0) > 0 && f.optDouble("rs", 0) > 0) r = f.optDouble("rs") / f.optDouble("l");
        }
        if (Double.isNaN(r)) for (JSONObject f : fills(st)) // (none before: the first one after it)
            if (!f.optBoolean("le") && f.optDouble("l", 0) > 0 && f.optDouble("rs", 0) > 0) { r = f.optDouble("rs") / f.optDouble("l"); break; }
        return r;
    }

    /** A date he may give: not ahead of today, not more than 400 days back. Null: fine. */
    private static String dayFits(LocalDate d, LocalDate today) {
        if (d == null) return "తేదీ అర్థం కాలేదు (YYYY-MM-DD).";
        if (d.isAfter(today)) return "ఆ తేదీ ఇంకా రాలేదు.";
        if (d.isBefore(today.minusDays(400))) return "అంత పాత తేదీ రాయలేను (ఏడాది లోపువే).";
        return null;
    }

    /**
     * A fill: rupees, litres, rate (any two; rupees alone = litres from his last rate, "సుమారు"), the meter (optional),
     * full tank or not. Returns the entry, or {error} with what to ask him.
     */
    static JSONObject addFill(JSONObject st, LocalDate d, LocalDate today, long now, ZoneId z, double rs, double l, double rate, double odo, boolean full,
                              boolean sure) throws Exception {
        String bad = dayFits(d, today);
        if (bad != null) return new JSONObject().put("error", "bad_date").put("say", bad);
        long at = place(st, d.toString(), at(d, today, now, z), odo);
        boolean le = false;
        if (!Double.isNaN(rate) && (rate < MIN_RATE || rate > MAX_RATE)) rate = Double.NaN; // (a rate that can't be: worked out from the others)
        if (Double.isNaN(rs) || rs <= 0) {
            if (!Double.isNaN(l) && l > 0 && !Double.isNaN(rate)) rs = l * rate;
            else return new JSONObject().put("error", "need_rupees").put("say", "ఎన్ని రూపాయల " + fuelWord(fuel(st)) + " కొట్టించారు?");
        }
        if (Double.isNaN(l) || l <= 0) {
            if (!Double.isNaN(rate)) l = rs / rate;
            else {
                double lr = lastRate(st, at);
                if (Double.isNaN(lr)) return new JSONObject().put("error", "need_litres").put("say", "ఎన్ని లీటర్లు వచ్చాయి? (లేదా లీటర్ రేటు చెప్పండి)");
                l = rs / lr;
                le = true;
            }
        }
        if (rs > MAX_RS) return new JSONObject().put("error", "too_much").put("say", "₹" + km(rs) + " ఒక్కసారికి చాలా ఎక్కువ. ఒకసారి చూసి చెప్పండి.");
        if (l > MAX_L) return new JSONObject().put("error", "too_much").put("say", km(l) + " లీటర్లు ఒక్కసారికి చాలా ఎక్కువ. ఒకసారి చూసి చెప్పండి.");
        double r = rs / l;
        if (r < MIN_RATE || r > MAX_RATE)
            return new JSONObject().put("error", "odd_rate").put("say", "₹" + km(rs) + ", " + litres(l) + " లీటర్లు అంటే లీటర్ " + HouseBook.rate(round2(r))
                    + " అవుతోంది: ఇది సరిగ్గా లేదు. రూపాయలు, లీటర్లు ఒకసారి చూసి చెప్పండి.");
        JSONObject od = odoCheck(st, d.toString(), odo, sure);
        if (od != null) return od;
        JSONObject f = new JSONObject().put("id", newId(now)).put("d", d.toString()).put("at", at).put("t", now)
                .put("rs", round2(rs)).put("l", round2(l)).put("full", full);
        if (le) f.put("le", true);
        if (!Double.isNaN(odo)) f.put("odo", Math.round(odo));
        st.getJSONArray("fills").put(f);
        return f;
    }

    /**
     * A meter reading alone ("మీటర్ 45,500"). Said within the hour after a fill of that day told without its meter, it
     * becomes that fill's meter (returned with "attached": the fill's id), so the mileage can use it.
     */
    static JSONObject addOdo(JSONObject st, LocalDate d, LocalDate today, long now, ZoneId z, double odo, boolean sure) throws Exception {
        String bad = dayFits(d, today);
        if (bad != null) return new JSONObject().put("error", "bad_date").put("say", bad);
        if (Double.isNaN(odo)) return new JSONObject().put("error", "need_odo").put("say", "మీటర్ రీడింగ్ ఎంత?");
        JSONObject od = odoCheck(st, d.toString(), odo, sure);
        if (od != null) return od;
        JSONObject newest = null;
        for (JSONObject f : fills(st)) if (newest == null || f.optLong("t") > newest.optLong("t")) newest = f;
        if (newest != null && !newest.has("odo") && d.toString().equals(newest.optString("d")) && now - newest.optLong("t") >= 0 && now - newest.optLong("t") < 3_600_000L) {
            newest.put("at0", newest.optLong("at")).put("odo_t", now).put("odo", Math.round(odo)).put("at", place(st, d.toString(), newest.optLong("at"), odo));
            return new JSONObject().put("id", newest.optString("id")).put("d", d.toString()).put("odo", Math.round(odo)).put("attached", newest.optString("id"));
        }
        long at = place(st, d.toString(), at(d, today, now, z), odo);
        JSONObject o = new JSONObject().put("id", newId(now)).put("d", d.toString()).put("at", at).put("t", now).put("odo", Math.round(odo));
        st.getJSONArray("odos").put(o);
        return o;
    }

    /** A service done (rupees and the meter if he says them). */
    static JSONObject addService(JSONObject st, LocalDate d, LocalDate today, long now, ZoneId z, double rs, double odo, String what, boolean sure) throws Exception {
        String bad = dayFits(d, today);
        if (bad != null) return new JSONObject().put("error", "bad_date").put("say", bad);
        if (!Double.isNaN(rs) && (rs < 0 || rs > 5 * MAX_RS)) return new JSONObject().put("error", "too_much").put("say", "సర్వీస్ ఖర్చు సరిగ్గా లేదు. ఒకసారి చూసి చెప్పండి.");
        long at = place(st, d.toString(), at(d, today, now, z), odo);
        JSONObject od = odoCheck(st, d.toString(), odo, sure);
        if (od != null) return od;
        JSONObject s = new JSONObject().put("id", newId(now)).put("d", d.toString()).put("at", at).put("t", now);
        if (!Double.isNaN(rs) && rs > 0) s.put("rs", round2(rs));
        if (!Double.isNaN(odo)) s.put("odo", Math.round(odo));
        if (what != null && !what.trim().isEmpty()) s.put("what", what.trim().length() > 80 ? what.trim().substring(0, 80) : what.trim());
        st.getJSONArray("svcs").put(s);
        return s;
    }

    /**
     * The newest thing he told, taken back: an entry (kind "fills", "odos", "svcs": removed), or a meter said after a fill
     * and attached to it (kind "attached": only that meter comes off the fill). Returned with the entry, or null.
     */
    static JSONObject undo(JSONObject st) throws Exception {
        String bestK = null;
        int bestI = -1;
        long bestT = Long.MIN_VALUE;
        boolean meterOnly = false;
        for (String k : new String[]{"fills", "odos", "svcs"}) {
            JSONArray a = st.optJSONArray(k);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (o.optLong("t") >= bestT) { bestT = o.optLong("t"); bestK = k; bestI = i; meterOnly = false; }
                if (o.has("odo_t") && o.optLong("odo_t") >= bestT) { bestT = o.optLong("odo_t"); bestK = k; bestI = i; meterOnly = true; }
            }
        }
        if (bestK == null) return null;
        JSONObject o = st.getJSONArray(bestK).getJSONObject(bestI);
        if (meterOnly) {
            JSONObject was = new JSONObject(o.toString());
            o.remove("odo");
            o.remove("odo_t");
            o.put("at", o.optLong("at0", o.optLong("at")));
            o.remove("at0");
            return new JSONObject().put("kind", "attached").put("entry", was).put("id", was.optString("id") + "~m");
        }
        st.getJSONArray(bestK).remove(bestI);
        return new JSONObject().put("kind", bestK).put("entry", o).put("id", o.optString("id"));
    }

    /** Old entries out (a fill list of ~3 years is plenty). */
    static void prune(JSONObject st) throws Exception {
        int[] keep = {400, 200, 60};
        String[] ks = {"fills", "odos", "svcs"};
        for (int k = 0; k < ks.length; k++) {
            List<JSONObject> l = list(st, ks[k]);
            if (l.size() <= keep[k]) continue;
            JSONArray a = new JSONArray();
            for (JSONObject o : l.subList(l.size() - keep[k], l.size())) a.put(o);
            st.put(ks[k], a);
        }
    }

    // ================================================================ km: the meter, else GPS trips

    /** The GPS car trips' km that ended in (from, to]: trips are {end ms, km}. */
    static double gpsKm(List<double[]> trips, double from, double to) {
        double s = 0;
        if (trips != null) for (double[] t : trips) if (t[0] > from && t[0] <= to) s += t[1];
        return s;
    }

    /** A reading this close in time is the meter itself; farther, the meter then is a guess ("సుమారు"). */
    private static final long NEAR = 12 * 3_600_000L;

    /**
     * {the meter at that time, 1 when it is a guess, 1 when GPS km went into it}, or null when he never told a reading.
     * Between two readings: the GPS car trips up to then (never more than the readings' difference), the rest shared out
     * by time; after the last reading: it plus the GPS trips since; before the first: it minus the trips between.
     */
    static double[] odoAt(JSONObject st, List<double[]> trips, long t) {
        Reading before = null, after = null;
        for (Reading r : readings(st)) {
            if (r.at <= t) before = r;
            else { after = r; break; }
        }
        if (before != null && after != null && after.at > before.at) {
            double diff = Math.max(0, after.odo - before.odo);
            double gAll = Math.min(gpsKm(trips, before.at, after.at), diff), g = Math.min(gpsKm(trips, before.at, t), gAll);
            double v = before.odo + g + (diff - gAll) * (double) (t - before.at) / (after.at - before.at);
            boolean near = t - before.at <= NEAR || after.at - t <= NEAR || diff == 0;
            return new double[]{v, near && g <= 0 ? 0 : 1, g > 0 ? 1 : 0};
        }
        if (before != null) {
            double g = gpsKm(trips, before.at, t);
            return new double[]{before.odo + g, g > 0 || t - before.at > NEAR ? 1 : 0, g > 0 ? 1 : 0};
        }
        if (after != null) {
            double g = gpsKm(trips, t, after.at);
            return new double[]{Math.max(0, after.odo - g), g > 0 || after.at - t > NEAR ? 1 : 0, g > 0 ? 1 : 0};
        }
        return null;
    }

    /** km driven from a to b: {km, approx (1/0), how: 0 the meter, 1 GPS trips only (no reading ever), 2 the meter and GPS trips}. */
    static double[] kmBetween(JSONObject st, List<double[]> trips, long a, long b) {
        double[] x = odoAt(st, trips, a), y = odoAt(st, trips, b);
        if (x != null && y != null) return new double[]{Math.max(0, y[0] - x[0]), x[1] > 0 || y[1] > 0 ? 1 : 0, x[2] > 0 || y[2] > 0 ? 2 : 0};
        return new double[]{gpsKm(trips, a, b), 1, 1};
    }

    // ================================================================ mileage

    /** The parts between fills: {from, to, at, end_id, km, l, rs, kmpl, src meter / gps, exact, valid}, oldest first. */
    static List<JSONObject> segments(JSONObject st, List<double[]> trips) throws Exception {
        List<JSONObject> fs = fills(st), out = new ArrayList<>();
        int fullN = 0;
        for (JSONObject f : fs) if (f.optBoolean("full")) fullN++;
        boolean useFull = fullN >= 2;
        int prev = -1;
        for (int i = 0; i < fs.size(); i++) {
            JSONObject b = fs.get(i);
            if (useFull && !b.optBoolean("full")) continue;
            if (prev < 0) { prev = i; continue; }
            JSONObject a = fs.get(prev);
            double l = 0, rs = 0;
            boolean le = false;
            for (int k = prev + 1; k <= i; k++) { l += fs.get(k).optDouble("l"); rs += fs.get(k).optDouble("rs"); le |= fs.get(k).optBoolean("le"); }
            prev = i;
            double km;
            String src;
            if (a.has("odo") && b.has("odo")) { km = b.optDouble("odo") - a.optDouble("odo"); src = "meter"; }
            else { km = gpsKm(trips, a.optLong("at"), b.optLong("at")); src = "gps"; }
            if (km <= 0 || l <= 0) continue;
            double kmpl = km / l;
            boolean valid = km >= MIN_SEG_KM && l >= 1 && kmpl >= MIN_KMPL && kmpl <= MAX_KMPL;
            out.add(new JSONObject().put("from", a.optString("d")).put("to", b.optString("d")).put("at", b.optLong("at")).put("end_id", b.optString("id"))
                    .put("km", Math.round(km)).put("l", round2(l)).put("rs", Math.round(rs)).put("kmpl", Math.round(kmpl * 10) / 10.0)
                    .put("src", src).put("exact", useFull && "meter".equals(src) && !le).put("valid", valid));
        }
        return out;
    }

    /** The usual km per litre: the last (up to) 5 good parts, the meter's first (GPS ones only when there are none). Null: none yet. */
    static JSONObject usual(List<JSONObject> segs) throws Exception {
        for (String src : new String[]{"meter", "gps"}) {
            List<JSONObject> l = new ArrayList<>();
            for (JSONObject s : segs) if (s.optBoolean("valid") && src.equals(s.optString("src"))) l.add(s);
            if (l.isEmpty()) continue;
            List<JSONObject> last = l.subList(Math.max(0, l.size() - 5), l.size());
            double km = 0, lit = 0;
            boolean exact = true;
            for (JSONObject s : last) { km += s.optDouble("km"); lit += s.optDouble("l"); exact &= s.optBoolean("exact"); }
            return new JSONObject().put("kmpl", Math.round(km / lit * 10) / 10.0).put("n", last.size()).put("src", src).put("exact", exact);
        }
        return null;
    }

    /** The newest exact part (full to full, by the meter) clearly below the usual of the exact parts before it: {kmpl, usual, pct, end_id}, else null. */
    static JSONObject drop(List<JSONObject> segs) throws Exception {
        List<JSONObject> m = new ArrayList<>();
        for (JSONObject s : segs) if (s.optBoolean("valid") && s.optBoolean("exact")) m.add(s); // (full to full by the meter: a part fill isn't a drop)
        if (m.size() < 3 || segs.isEmpty() || segs.get(segs.size() - 1) != m.get(m.size() - 1)) return null; // (the newest part itself)
        JSONObject last = m.get(m.size() - 1);
        if (last.optDouble("km") < 100) return null;
        double km = 0, l = 0;
        for (JSONObject s : m.subList(Math.max(0, m.size() - 6), m.size() - 1)) { km += s.optDouble("km"); l += s.optDouble("l"); }
        double usual = km / l, now = last.optDouble("km") / last.optDouble("l");
        if (now >= usual * DROP) return null;
        return new JSONObject().put("kmpl", Math.round(now * 10) / 10.0).put("usual", Math.round(usual * 10) / 10.0)
                .put("pct", Math.round((1 - now / usual) * 100)).put("end_id", last.optString("end_id"));
    }

    /** ₹ per litre of the newest fill whose litres he told, else NaN. */
    static double latestRate(JSONObject st) {
        List<JSONObject> fs = fills(st);
        for (int i = fs.size() - 1; i >= 0; i--) {
            JSONObject f = fs.get(i);
            if (!f.optBoolean("le") && f.optDouble("l", 0) > 0) return f.optDouble("rs") / f.optDouble("l");
        }
        return Double.NaN;
    }

    // ================================================================ service

    /** The last service: the newer of his newest "సర్వీస్ అయింది" and the one from setup. {d, at, odo?} or null. */
    static JSONObject lastService(JSONObject st, ZoneId z) throws Exception {
        List<JSONObject> s = list(st, "svcs");
        JSONObject told = s.isEmpty() ? null : s.get(s.size() - 1);
        JSONObject c = car(st);
        if (c == null || c.optString("svc0_date").isEmpty()) return told;
        LocalDate d = LocalDate.parse(c.optString("svc0_date"));
        JSONObject o = new JSONObject().put("d", d.toString()).put("at", c.optLong("svc0_at", d.atTime(12, 0).atZone(z).toInstant().toEpochMilli())).put("from_setup", true);
        if (c.has("svc0_odo")) o.put("odo", c.optLong("svc0_odo"));
        return told != null && told.optLong("at") >= o.optLong("at") ? told : o;
    }

    /**
     * {known, last, due_date, days_left, km_since, km_left, km_known, km_meter, approx, state ok / soon / due}. The km since
     * the service come from the meter: the service's own reading, else a reading before it and one after it (shared out by
     * time between readings: "సుమారు"). With no meter reading before the service the km are not known; GPS car trips are
     * then only shown (a cab ride with navigation is a "car" trip too), never make it due.
     */
    static JSONObject service(JSONObject st, List<double[]> trips, LocalDate today, long now, ZoneId z) throws Exception {
        JSONObject last = lastService(st, z), o = new JSONObject();
        if (last == null) return o.put("known", false);
        LocalDate d = LocalDate.parse(last.optString("d")), due = d.plusMonths(everyMonths(st));
        long days = ChronoUnit.DAYS.between(today, due);
        long la = last.optLong("at");
        double gpsSince = gpsKm(trips, la, now);
        Reading newest = null;
        boolean before = false;
        for (Reading r : readings(st)) { if (r.at > la) newest = r; else before = true; }
        double kmSince = Double.NaN;
        boolean approx = false, meter = false;
        if (newest != null) { // (the meter he read after the service: GPS trips after it can be anyone's car, so not counted here)
            if (last.has("odo")) { kmSince = newest.odo - last.optDouble("odo"); meter = true; }
            else if (before) {
                double[] b = odoAt(st, trips, la);
                kmSince = newest.odo - b[0];
                approx = b[1] > 0;
                meter = true;
            }
        }
        if (!meter && gpsSince > 0) { kmSince = gpsSince; approx = true; }
        boolean kmKnown = !Double.isNaN(kmSince);
        if (!kmKnown) kmSince = 0;
        kmSince = Math.max(0, kmSince);
        long kmLeft = everyKm(st) - Math.round(kmSince);
        String state = days <= 0 || (meter && kmLeft <= 0) ? "due" : days <= SOON_DAYS || (meter && kmLeft <= SOON_KM) ? "soon" : "ok";
        o.put("known", true).put("last", last.optString("d")).put("last_at", la).put("last_odo", last.has("odo")).put("due_date", due.toString())
                .put("days_left", days).put("km_since", Math.round(kmSince)).put("km_left", kmLeft).put("km_known", kmKnown).put("km_meter", meter)
                .put("approx", approx).put("state", state);
        if (meter && newest != null) o.put("km_as_of", newest.d);
        return o;
    }

    // ================================================================ a month

    static JSONObject month(JSONObject st, List<double[]> trips, YearMonth ym, LocalDate today, long now, ZoneId z) throws Exception {
        long a = ym.atDay(1).atStartOfDay(z).toInstant().toEpochMilli();
        long b = Math.min(now, ym.plusMonths(1).atDay(1).atStartOfDay(z).toInstant().toEpochMilli());
        String from = ym.atDay(1).toString(), to = ym.atEndOfMonth().toString();
        JSONArray fl = new JSONArray(), sv = new JSONArray();
        double lit = 0, rs = 0, srs = 0;
        for (JSONObject f : fills(st)) {
            String d = f.optString("d");
            if (d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
            fl.put(f);
            lit += f.optDouble("l");
            rs += f.optDouble("rs");
        }
        for (JSONObject s : list(st, "svcs")) {
            String d = s.optString("d");
            if (d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
            sv.put(s);
            srs += s.optDouble("rs", 0);
        }
        JSONObject o = new JSONObject().put("month", ym.toString()).put("so_far", YearMonth.from(today).equals(ym)).put("fills", fl).put("n", fl.length())
                .put("litres", round2(lit)).put("rupees", Math.round(rs)).put("services", sv).put("service_rs", Math.round(srs));
        if (b > a) {
            double[] k = kmBetween(st, trips, a, b);
            o.put("km", Math.round(k[0])).put("km_approx", k[1] > 0).put("km_gps", k[2] == 1).put("km_mixed", k[2] == 2);
        }
        double km = 0, l = 0, r = 0;
        int n = 0;
        String src = "";
        List<JSONObject> segs = segments(st, trips);
        for (String want : new String[]{"meter", "gps"}) {
            for (JSONObject s : segs) {
                String d = s.optString("to");
                if (!s.optBoolean("valid") || !want.equals(s.optString("src")) || d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
                km += s.optDouble("km"); l += s.optDouble("l"); r += s.optDouble("rs"); n++;
            }
            if (n > 0) { src = want; break; }
        }
        if (n > 0) o.put("kmpl", Math.round(km / l * 10) / 10.0).put("kmpl_n", n).put("kmpl_src", src).put("rs_per_km", round2(r / km));
        return o;
    }

    // ================================================================ his words (Telugu)

    /** 45230 → "45,230"; 31.5 → "31.5". */
    static String km(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.format(Locale.ENGLISH, "%,d", Math.round(v));
        return String.format(Locale.ENGLISH, "%,.1f", v);
    }

    /** 32 → "32", 31.5 → "31.5", 31.25 → "31.25". */
    static String litres(double l) {
        double r = round2(l);
        if (Math.abs(r - Math.rint(r)) < 1e-9) return String.valueOf((long) Math.rint(r));
        String s = String.format(Locale.ENGLISH, "%.2f", r);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    static String dayName(String d) {
        try {
            LocalDate x = LocalDate.parse(d);
            return MONTHS[x.getMonthValue() - 1] + " " + x.getDayOfMonth();
        } catch (Exception e) {
            return d;
        }
    }

    /** "₹3,000 డీజిల్ · 32 లీటర్లు (లీటర్ ₹93.75) · మీటర్ 45,230 · ఫుల్ ట్యాంక్". */
    static String fillText(JSONObject f, String fuel) {
        StringBuilder s = new StringBuilder(HouseBook.rs(Math.round(f.optDouble("rs")))).append(' ').append(fuelWord(fuel)).append(" · ")
                .append(f.optBoolean("le") ? "సుమారు " : "").append(litres(f.optDouble("l"))).append(" లీటర్లు");
        if (!f.optBoolean("le") && f.optDouble("l") > 0) s.append(" (లీటర్ ").append(HouseBook.rate(round2(f.optDouble("rs") / f.optDouble("l")))).append(")");
        if (f.has("odo")) s.append(" · మీటర్ ").append(km(f.optDouble("odo")));
        if (f.optBoolean("full")) s.append(" · ఫుల్ ట్యాంక్");
        return s.toString();
    }

    /** What he is told after a fill was written. */
    static String fillSaid(JSONObject f, String fuel, double lastRate) {
        String s = "రాశాను: " + fillText(f, fuel) + ".";
        if (f.optBoolean("le") && !Double.isNaN(lastRate)) s += " లీటర్లు చెప్పలేదు కాబట్టి చివరి రేటు " + HouseBook.rate(round2(lastRate)) + " తో లెక్క వేశాను.";
        return s;
    }

    /** "మైలేజ్: లీటర్‌కి 16.4 కి.మీ (చివరి 3 సార్ల సగటు) · కి.మీ.కి సుమారు ₹5.70", or how to get it. */
    static String mileageLine(JSONObject st, List<double[]> trips) throws Exception {
        JSONObject u = usual(segments(st, trips));
        if (u == null) return "మైలేజ్ ఇంకా లెక్కకి రాలేదు: మీటర్ రీడింగ్‌తో (వీలైతే ఫుల్ ట్యాంక్ చేయించి) రెండుసార్లు " + fuelWord(fuel(st)) + " కొట్టించినట్టు చెబితే లెక్క వేస్తాను.";
        boolean gps = "gps".equals(u.optString("src"));
        String s = "మైలేజ్: లీటర్‌కి " + (u.optBoolean("exact") ? "" : "సుమారు ") + u.optDouble("kmpl") + " కి.మీ"
                + (u.optInt("n") > 1 ? " (చివరి " + u.optInt("n") + " సార్ల సగటు)" : "");
        double rate = latestRate(st);
        if (!Double.isNaN(rate) && u.optDouble("kmpl") > 0) s += " · కి.మీ.కి సుమారు " + HouseBook.rate(round2(rate / u.optDouble("kmpl")));
        if (gps) s += " · GPS ట్రిప్పుల లెక్క: డ్రైవ్ అలర్ట్స్ ఆన్‌లో ఉన్న ట్రిప్పులే లెక్కలోకి వస్తాయి, అందుకే సుమారు మాత్రమే. మీటర్ రీడింగ్ చెబితే కచ్చితంగా వస్తుంది.";
        else if (!u.optBoolean("exact")) s += " · ట్యాంక్ ఫుల్ చేయించినప్పుడు 'ఫుల్' అని చెబితే ఇంకా కచ్చితంగా వస్తుంది.";
        return s;
    }

    /** The service, in words. */
    static String serviceLine(JSONObject st, JSONObject s) {
        String car = name(st);
        if (!s.optBoolean("known"))
            return "చివరి " + car + " సర్వీస్ ఎప్పుడో నాకు తెలియదు. Settings → 🚗 కారు లెక్కలు లో చివరి సర్వీస్ తేదీ పెట్టండి, లేదా తర్వాతి సర్వీస్ అయ్యాక '" + car + " సర్వీస్ అయింది' అనండి.";
        long days = s.optLong("days_left"), left = s.optLong("km_left");
        boolean meter = s.optBoolean("km_meter");
        String approx = s.optBoolean("approx") ? "సుమారు " : "";
        String since = s.optBoolean("km_known") && s.optLong("km_since") > 0 ? " చివరి సర్వీస్ (" + dayName(s.optString("last")) + ") తర్వాత " + approx
                + km(s.optLong("km_since")) + " కి.మీ తిరిగింది" + (meter ? s.has("km_as_of") ? " (" + dayName(s.optString("km_as_of")) + " మీటర్ ప్రకారం)" : "" : " (GPS ట్రిప్పుల ప్రకారం)") + "." : "";
        String hint = meter ? "" : s.optBoolean("last_odo") ? " (తర్వాత మీటర్ రీడింగ్ చెప్పినప్పటి నుంచి కి.మీ లెక్క కూడా వస్తుంది.)"
                : " (కి.మీ తో కూడా గుర్తుచేయాలంటే సర్వీస్ అప్పుడు మీటర్ రీడింగ్ చెప్పండి.)";
        if ("due".equals(s.optString("state"))) {
            List<String> why = new ArrayList<>();
            if (days <= 0) why.add(days == 0 ? "ఈరోజుతో " + everyMonths(st) + " నెలలు అయ్యాయి" : everyMonths(st) + " నెలలు దాటి " + (-days) + " రోజులు అయింది");
            if (meter && left <= 0) why.add(left == 0 ? approx + km(everyKm(st)) + " కి.మీ పూర్తయింది" : approx + km(-left) + " కి.మీ దాటింది");
            return car + " సర్వీస్ టైమ్ అయింది (" + String.join(", ", why) + ")." + since + " సర్వీస్ అయ్యాక '" + car + " సర్వీస్ అయింది' అనండి.";
        }
        return "తర్వాతి " + car + " సర్వీస్ " + days + " రోజుల్లో (" + dayName(s.optString("due_date")) + ")"
                + (meter ? " లేదా ఇంకా " + approx + km(Math.max(0, left)) + " కి.మీ తర్వాత (ఏది ముందైతే అది)" : "") + "." + since + hint;
    }

    /** "అక్టోబర్: డీజిల్ 3 సార్లు · 95 లీటర్లు · ₹8,900 · సుమారు 1,520 కి.మీ · మైలేజ్ 16.0 · సర్వీస్ ₹4,500"; "" when nothing. */
    static String monthLine(JSONObject st, JSONObject m) {
        YearMonth ym = YearMonth.parse(m.optString("month"));
        int n = m.optInt("n");
        long km = m.optLong("km", 0);
        JSONArray sv = m.optJSONArray("services");
        if (n == 0 && km == 0 && (sv == null || sv.length() == 0)) return "";
        StringBuilder s = new StringBuilder(MONTHS[ym.getMonthValue() - 1]).append(m.optBoolean("so_far") ? " (ఇప్పటివరకు)" : "").append(": ");
        if (n > 0) s.append(fuelWord(fuel(st))).append(' ').append(n).append(n == 1 ? " సారి" : " సార్లు").append(" · ").append(litres(m.optDouble("litres")))
                .append(" లీటర్లు · ").append(HouseBook.rs(m.optLong("rupees")));
        else s.append(fuelWord(fuel(st))).append(" కొట్టించలేదు");
        if (km > 0) s.append(" · ").append(m.optBoolean("km_approx") ? "సుమారు " : "").append(km(km)).append(" కి.మీ")
                .append(m.optBoolean("km_gps") ? " (GPS)" : m.optBoolean("km_mixed") ? " (మీటర్ + GPS)" : "");
        if (m.has("kmpl")) s.append(" · మైలేజ్ ").append(m.optDouble("kmpl"));
        if (sv != null && sv.length() > 0) s.append(" · సర్వీస్").append(m.optLong("service_rs") > 0 ? " " + HouseBook.rs(m.optLong("service_rs")) : " అయింది");
        return s.toString();
    }

    /** The mileage drop, gently (never a guess at the cause). */
    static String dropLine(JSONObject d) {
        return "ఈసారి మైలేజ్ లీటర్‌కి " + d.optDouble("kmpl") + " కి.మీ వచ్చింది, మామూలుగా " + d.optDouble("usual") + " వస్తుంది (" + d.optLong("pct")
                + "% తక్కువ). టైర్లలో గాలి ఒకసారి చూడండి; ఇలాగే ఉంటే సర్వీస్‌లో చూపించండి.";
    }

    static LocalDate today(ZoneId z, long now) { return Instant.ofEpochMilli(now).atZone(z).toLocalDate(); }

    static double round2(double v) { return Math.round(v * 100) / 100.0; }

    /** For the PDF: the month's fills, oldest first. */
    static List<JSONObject> asList(JSONArray a) {
        List<JSONObject> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) l.add(a.optJSONObject(i));
        return Collections.unmodifiableList(l);
    }
}
