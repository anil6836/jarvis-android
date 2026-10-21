package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 🥛 The house accounts (milk first), pure (no Android: desk-tested by HouseTest). One JSON, the same shape on his phone
 * and on the home tablet:
 * <pre>
 *   items: [{key, name, unit, vendor, since, price: {date: ₹ per unit}, daily: {date: the usual per day}}]  (set from his phone)
 *   days:  {key: {date: {q, t, by}}}   a day that was not the usual: q = what came (0 = none); q &lt; 0 = back to the usual
 *   pays:  {id: {item, m, a, t, by, x}} money given for a month (x: taken back); a month's payments are added up
 * </pre>
 * Two devices merge by time: the later word about a day wins; payments are kept by their id (a newer copy of the same
 * id wins, so a payment taken back on one side is taken back on the other too). Prices and the usual amount change
 * from a date, so a month with a new rate is counted right on both sides of the change. Only the phone's items count:
 * what the tablet sends is kept only for items his phone knows (and milk), so no other words travel.
 */
final class HouseBook {
    private HouseBook() {}

    static final String MILK = "milk";
    static final String ME = "me", AMMA = "amma";
    static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};
    /** How far a word about days may reach (one "these days no milk" at most). */
    static final int MAX_RANGE = 62;
    /** The most of one day (litres / packets). */
    static final double MAX_QTY = 50;
    /** Days and payments kept (a little over a year). */
    static final int KEEP_MONTHS = 14;

    // ================================================================ the state

    static JSONObject empty() {
        try { return new JSONObject().put("items", new JSONArray()).put("days", new JSONObject()).put("pays", new JSONObject()); }
        catch (Exception e) { return new JSONObject(); }
    }

    static JSONObject parse(String s) {
        try {
            JSONObject o = new JSONObject(s == null || s.trim().isEmpty() ? "{}" : s);
            if (!(o.opt("items") instanceof JSONArray)) o.put("items", new JSONArray());
            if (!(o.opt("days") instanceof JSONObject)) o.put("days", new JSONObject());
            if (!(o.opt("pays") instanceof JSONObject)) o.put("pays", new JSONObject());
            return o;
        } catch (Exception e) {
            return empty();
        }
    }

    /** "పాలు" / "పాల" / "milk" / "పాల ప్యాకెట్" (or nothing said) → milk; any other name as it is (lower case, short). */
    static String key(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (n.isEmpty() || n.startsWith("పాల") || n.contains(" పాల") || n.contains("milk")) return MILK;
        return n.length() > 30 ? n.substring(0, 30).trim() : n;
    }

    static List<JSONObject> items(JSONObject st) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = st.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }

    static JSONObject item(JSONObject st, String key) {
        for (JSONObject o : items(st)) if (o.optString("key").equals(key)) return o;
        return null;
    }

    /** An account this device may write a day / payment for: one his phone set, or milk. */
    static boolean known(JSONObject st, String key) { return MILK.equals(key) || item(st, key) != null; }

    static String unitDefault(String key) { return MILK.equals(key) ? "లీటర్" : ""; }

    /**
     * A new item; or for one already there: its unit, vendor, name. Its start (since) and its first rate / usual amount
     * change only with fromStart (he said they were wrong from the beginning; later changes from a day stay as they are).
     * price &lt;= 0, daily &lt; 0, null: as it was. A new item needs a price and a start; returns null then.
     */
    static JSONObject setup(JSONObject st, String key, String name, String unit, double price, double daily, LocalDate since, String vendor, boolean fromStart) throws Exception {
        JSONObject it = item(st, key);
        if (it == null) {
            if (!(price > 0) || since == null) return null;
            it = new JSONObject().put("key", key).put("name", name == null || name.trim().isEmpty() ? (MILK.equals(key) ? "పాలు" : key) : name.trim())
                    .put("unit", unit == null || unit.trim().isEmpty() ? unitDefault(key) : unit.trim()).put("vendor", vendor == null ? "" : vendor.trim())
                    .put("since", since.toString()).put("price", new JSONObject()).put("daily", new JSONObject());
            it.getJSONObject("price").put(since.toString(), price);
            it.getJSONObject("daily").put(since.toString(), daily >= 0 ? daily : (MILK.equals(key) ? 1 : 0));
            st.getJSONArray("items").put(it);
            return it;
        }
        if (name != null && !name.trim().isEmpty() && !MILK.equals(key)) it.put("name", name.trim());
        if (unit != null && !unit.trim().isEmpty()) it.put("unit", unit.trim());
        if (vendor != null) it.put("vendor", vendor.trim());
        if (!fromStart) return it;
        if (since != null) it.put("since", since.toString());
        String from = it.optString("since");
        rebase(it.getJSONObject("price"), from); // (the value from the start replaced; later changes stay)
        rebase(it.getJSONObject("daily"), from);
        if (price > 0) it.getJSONObject("price").put(from, price);
        if (daily >= 0) it.getJSONObject("daily").put(from, daily);
        return it;
    }

    /** A new start: the value on that day becomes its first (nothing before it is needed any more). */
    private static void rebase(JSONObject map, String from) throws Exception {
        if (map.length() == 0) return;
        double first = valueOn(map, LocalDate.parse(from));
        List<String> before = new ArrayList<>();
        for (Iterator<String> k = map.keys(); k.hasNext(); ) { String d = k.next(); if (d.compareTo(from) < 0) before.add(d); }
        for (String d : before) map.remove(d);
        if (!map.has(from)) map.put(from, first);
    }

    /** The value from that day on (not before the count's start: a day before it would never count). */
    private static void putFrom(JSONObject it, String which, LocalDate from, double v) throws Exception {
        LocalDate since = LocalDate.parse(it.optString("since"));
        if (from.isBefore(since)) from = since;
        it.getJSONObject(which).put(from.toString(), v);
    }

    /** A new price from that day (the days before keep theirs). False: no such item, or a price that can't be. */
    static boolean setPrice(JSONObject st, String key, double price, LocalDate from) throws Exception {
        JSONObject it = item(st, key);
        if (it == null || !(price > 0) || price > 100_000 || from == null) return false;
        putFrom(it, "price", from, price);
        return true;
    }

    /** A new usual amount per day from that day (0 = none comes now unless said). */
    static boolean setDaily(JSONObject st, String key, double daily, LocalDate from) throws Exception {
        JSONObject it = item(st, key);
        if (it == null || !(daily >= 0) || daily > MAX_QTY || from == null) return false;
        putFrom(it, "daily", from, daily);
        return true;
    }

    /** The value for that day: the latest one from on or before it (before the first: the first). NaN: none. */
    static double valueOn(JSONObject map, LocalDate d) {
        if (map == null || map.length() == 0) return Double.NaN;
        TreeMap<String, Double> t = new TreeMap<>();
        for (Iterator<String> k = map.keys(); k.hasNext(); ) { String s = k.next(); t.put(s, map.optDouble(s)); }
        Map.Entry<String, Double> e = t.floorEntry(d.toString());
        return (e != null ? e : t.firstEntry()).getValue();
    }

    static double usualOn(JSONObject it, LocalDate d) {
        double v = valueOn(it.optJSONObject("daily"), d);
        return Double.isNaN(v) ? 0 : v;
    }

    static double priceOn(JSONObject it, LocalDate d) {
        double v = valueOn(it.optJSONObject("price"), d);
        return Double.isNaN(v) ? 0 : v;
    }

    /** The word about that day ({q, t, by}), or null (q &lt; 0 = back to the usual: also null). */
    static JSONObject said(JSONObject st, String key, LocalDate d) {
        JSONObject k = st.optJSONObject("days") == null ? null : st.optJSONObject("days").optJSONObject(key);
        JSONObject e = k == null ? null : k.optJSONObject(d.toString());
        return e == null || e.optDouble("q", -1) < 0 ? null : e;
    }

    /** What came that day: what was said about it, else the usual. */
    static double qtyOn(JSONObject st, JSONObject it, LocalDate d) {
        JSONObject e = said(st, it.optString("key"), d);
        return e != null ? e.optDouble("q") : usualOn(it, d);
    }

    /**
     * What came on these days (from..to, at most MAX_RANGE days): q &gt;= 0 the amount (0 = none came / none wanted),
     * q &lt; 0 back to the usual. Returns how many days were written (0: a range or an amount that can't be).
     */
    static int mark(JSONObject st, String key, LocalDate from, LocalDate to, double q, long t, String by) throws Exception {
        if (from == null || Double.isNaN(q) || q > MAX_QTY) return 0;
        if (to == null) to = from;
        if (to.isBefore(from) || from.plusDays(MAX_RANGE - 1).isBefore(to)) return 0;
        JSONObject days = st.getJSONObject("days");
        JSONObject k = days.optJSONObject(key);
        if (k == null) { k = new JSONObject(); days.put(key, k); }
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            k.put(d.toString(), new JSONObject().put("q", q < 0 ? -1 : round2(q)).put("t", t).put("by", by));
            n++;
        }
        return n;
    }

    // ================================================================ money given

    /** Money given for that month (adds to what was given before). Returns its id. */
    static String pay(JSONObject st, String key, YearMonth m, double amount, long t, String by) throws Exception {
        String id = (AMMA.equals(by) ? "a" : "p") + t;
        while (st.getJSONObject("pays").has(id)) id += "_";
        st.getJSONObject("pays").put(id, new JSONObject().put("item", key).put("m", m.toString()).put("a", Math.round(amount)).put("t", t).put("by", by).put("x", false));
        return id;
    }

    /**
     * Money written as given for that month taken back (it wasn't given) by `by`: amount &gt; 0 only the newest payment
     * of that amount, else all of the month's. Returns how many.
     */
    static int unpay(JSONObject st, String key, YearMonth m, long amount, long t, String by) throws Exception {
        JSONObject pays = st.getJSONObject("pays");
        List<JSONObject> hit = new ArrayList<>();
        for (Iterator<String> it = pays.keys(); it.hasNext(); ) {
            JSONObject p = pays.optJSONObject(it.next());
            if (p == null || p.optBoolean("x") || !key.equals(p.optString("item")) || !m.toString().equals(p.optString("m"))) continue;
            if (amount > 0 && p.optLong("a") != amount) continue;
            hit.add(p);
        }
        if (amount > 0 && hit.size() > 1) { // the newest one only
            JSONObject newest = hit.get(0);
            for (JSONObject p : hit) if (p.optLong("t") > newest.optLong("t")) newest = p;
            hit.clear();
            hit.add(newest);
        }
        for (JSONObject p : hit) p.put("x", true).put("xby", by).put("t", Math.max(t, p.optLong("t") + 1));
        return hit.size();
    }

    /** Who did the last thing to a payment: who took it back, else who gave it. */
    static String actor(JSONObject p) { return p.optBoolean("x") && !p.optString("xby").isEmpty() ? p.optString("xby") : p.optString("by"); }

    /** The month of the newest money still written as given (for "it wasn't given" without a month); null: none. */
    static YearMonth lastPaidMonth(JSONObject st, String key) {
        JSONObject pays = st.optJSONObject("pays");
        String best = null;
        long bt = -1;
        if (pays != null) for (Iterator<String> it = pays.keys(); it.hasNext(); ) {
            JSONObject p = pays.optJSONObject(it.next());
            if (p == null || p.optBoolean("x") || !key.equals(p.optString("item"))) continue;
            if (p.optLong("t") > bt) { bt = p.optLong("t"); best = p.optString("m"); }
        }
        try { return best == null ? null : YearMonth.parse(best); } catch (Exception e) { return null; }
    }

    static long paid(JSONObject st, String key, YearMonth m) {
        long sum = 0;
        JSONObject pays = st.optJSONObject("pays");
        if (pays == null) return 0;
        for (Iterator<String> it = pays.keys(); it.hasNext(); ) {
            JSONObject p = pays.optJSONObject(it.next());
            if (p != null && !p.optBoolean("x") && key.equals(p.optString("item")) && m.toString().equals(p.optString("m"))) sum += p.optLong("a");
        }
        return sum;
    }

    /** The month's payments still given, as "₹1,000 (ఇంటి నుంచి చెప్పారు)" pieces (on the tablet: "మీరు" is her). */
    static JSONArray paysOf(JSONObject st, String key, YearMonth m, boolean onTablet) throws Exception {
        JSONArray out = new JSONArray();
        JSONObject pays = st.optJSONObject("pays");
        if (pays != null) for (Iterator<String> it = pays.keys(); it.hasNext(); ) {
            JSONObject p = pays.optJSONObject(it.next());
            if (p != null && !p.optBoolean("x") && key.equals(p.optString("item")) && m.toString().equals(p.optString("m")))
                out.put(rs(p.optLong("a")) + (AMMA.equals(p.optString("by")) ? (onTablet ? " (మీరు చెప్పారు)" : " (ఇంటి నుంచి చెప్పారు)")
                        : (onTablet ? " (అబ్బాయి చెప్పాడు)" : " (మీరు చెప్పారు)")));
        }
        return out;
    }

    // ================================================================ the bill

    /**
     * The month's bill (this month: up to today). Null: no such item. Fields: key, name, unit, vendor, month, month_te,
     * so_far, started, days, delivered_days, qty, amount, usual_now, price_now, prices [[day, ₹]], missed [days],
     * changed [[day, qty]], paid, balance, settled.
     */
    static JSONObject bill(JSONObject st, String key, YearMonth ym, LocalDate today) throws Exception {
        JSONObject it = item(st, key);
        if (it == null) return null;
        LocalDate since = LocalDate.parse(it.optString("since"));
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        LocalDate from = since.isAfter(first) ? since : first, to = today.isBefore(last) ? today : last;
        JSONObject b = new JSONObject().put("key", key).put("name", it.optString("name")).put("unit", it.optString("unit")).put("vendor", it.optString("vendor"))
                .put("month", ym.toString()).put("month_te", MONTHS[ym.getMonthValue() - 1]).put("so_far", !today.isAfter(last));
        double qty = 0, amount = 0;
        int days = 0, delivered = 0;
        JSONArray missed = new JSONArray(), changed = new JSONArray(), prices = new JSONArray();
        double lastPrice = -1;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            double usual = usualOn(it, d), price = priceOn(it, d), q = qtyOn(st, it, d);
            days++;
            qty += q;
            amount += q * price;
            if (q > 0) delivered++;
            if (q == 0 && usual > 0) missed.put(d.getDayOfMonth());
            else if (Math.abs(q - usual) > 1e-9) changed.put(new JSONArray().put(d.getDayOfMonth()).put(round2(q)));
            if (price != lastPrice) { prices.put(new JSONArray().put(d.getDayOfMonth()).put(price)); lastPrice = price; }
        }
        long amt = Math.round(amount), given = paid(st, key, ym);
        LocalDate now = today.isBefore(since) ? since : today;
        b.put("started", days > 0).put("days", days).put("delivered_days", delivered).put("qty", round2(qty)).put("amount", amt)
                .put("usual_now", round2(usualOn(it, now))).put("price_now", priceOn(it, now)).put("prices", prices)
                .put("missed", missed).put("changed", changed).put("paid", given).put("balance", Math.max(0, amt - given))
                .put("settled", amt > 0 && given >= amt);
        return b;
    }

    // ================================================================ in Telugu

    static String rs(long v) { return "₹" + String.format(Locale.ENGLISH, "%,d", v); }

    /** A rate: "₹60", "₹27.50". */
    static String rate(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return rs((long) Math.rint(v));
        return "₹" + String.format(Locale.ENGLISH, "%,.2f", v);
    }

    private static final String[] HALVES = {"", "ఒకటిన్నర", "రెండున్నర", "మూడున్నర", "నాలుగున్నర", "ఐదున్నర", "ఆరున్నర", "ఏడున్నర", "ఎనిమిదిన్నర", "తొమ్మిదిన్నర"};

    /** How much, as said: "అర", "పావు", "ముప్పావు", "ఒకటిన్నర", "2", "1.2". */
    static String num(double q) {
        if (Math.abs(q - 0.5) < 1e-9) return "అర";
        if (Math.abs(q - 0.25) < 1e-9) return "పావు";
        if (Math.abs(q - 0.75) < 1e-9) return "ముప్పావు";
        long w = (long) Math.floor(q);
        if (w >= 1 && w < HALVES.length && Math.abs(q - w - 0.5) < 1e-9) return HALVES[(int) w];
        return plain(q);
    }

    /** How much, in digits ("0.5", "2"): for a box he types in. */
    static String plain(double q) {
        if (Math.abs(q - Math.rint(q)) < 1e-9) return String.valueOf((long) Math.rint(q));
        return String.format(Locale.ENGLISH, "%.2f", q).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** "లీటర్" → 2 "లీటర్లు" (a word ending in ్ takes లు). */
    static String unitWord(String unit, double q) {
        if (unit == null || unit.isEmpty()) return "";
        return q <= 1 + 1e-9 || unit.charAt(unit.length() - 1) != '్' ? unit : unit + "లు";
    }

    static String qtyText(String unit, double q) { String u = unitWord(unit, q); return num(q) + (u.isEmpty() ? "" : " " + u); }

    /** "పాలు" → "పాల" (పాల బిల్లు); another name as it is. */
    static String of(String name) { return "పాలు".equals(name) ? "పాల" : name; }

    private static String join(JSONArray a, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length() && i < max; i++) s.append(i > 0 ? ", " : "").append(a.opt(i));
        if (a.length() > max) s.append(" …");
        return s.toString();
    }

    private static String daysWord(int n) { return n == 1 ? "ఒక రోజు" : n + " రోజులు"; }

    /** "12న రాలేదు" / "2 రోజులు రాలేదు (12, 25 తేదీల్లో)". */
    private static String missedText(JSONArray missed) {
        if (missed.length() == 1) return missed.opt(0) + "న రాలేదు";
        return daysWord(missed.length()) + " రాలేదు (" + join(missed, 10) + " తేదీల్లో)";
    }

    /** For him: "అక్టోబర్ పాల బిల్లు ₹1,740: 29 లీటర్లు (లీటర్ ₹60 చొప్పున). 2 రోజులు రాలేదు (12, 25 తేదీల్లో); 18న 2 లీటర్లు. …" */
    static String line(JSONObject b) {
        String what = of(b.optString("name")), unit = b.optString("unit");
        boolean soFar = b.optBoolean("so_far");
        if (!b.optBoolean("started")) return b.optString("month_te") + " " + what + " లెక్క ఇంకా మొదలవలేదు.";
        StringBuilder s = new StringBuilder(b.optString("month_te")).append(soFar ? " ఇప్పటివరకు " : " ").append(what).append(" బిల్లు ")
                .append(rs(b.optLong("amount"))).append(": ").append(qtyText(unit, b.optDouble("qty")));
        JSONArray pr = b.optJSONArray("prices");
        if (pr != null && pr.length() == 1) s.append(" (").append(unit.isEmpty() ? "" : unit + " ").append(rate(pr.optJSONArray(0).optDouble(1))).append(" చొప్పున)");
        else if (pr != null && pr.length() > 1) {
            s.append(" (రేటు");
            for (int i = 0; i < pr.length() && i < 4; i++)
                s.append(i > 0 ? "," : "").append(' ').append(pr.optJSONArray(i).optInt(0)).append("వ తేదీ నుంచి ").append(rate(pr.optJSONArray(i).optDouble(1)));
            s.append(")");
        }
        JSONArray missed = b.optJSONArray("missed"), changed = b.optJSONArray("changed");
        String sep = ". ";
        if (missed != null && missed.length() > 0) { s.append(sep).append(missedText(missed)); sep = "; "; }
        if (changed != null && changed.length() > 0) {
            s.append(sep);
            for (int i = 0; i < changed.length() && i < 6; i++) {
                JSONArray c = changed.optJSONArray(i);
                s.append(i > 0 ? ", " : "").append(c.optInt(0)).append("న ").append(qtyText(unit, c.optDouble(1)));
            }
            if (changed.length() > 6) s.append(" …");
        }
        long paid = b.optLong("paid"), bal = b.optLong("balance"), amt = b.optLong("amount");
        if (paid > 0) s.append(". ").append(bal == 0 ? rs(paid) + " ఇచ్చేశారు ✓" : rs(paid) + " ఇచ్చారు, ఇంకా " + rs(bal) + " ఇవ్వాలి");
        if (!soFar && paid > amt && amt > 0) s.append(" (").append(rs(paid - amt)).append(" ఎక్కువ ఇచ్చారు)");
        return s.append('.').toString();
    }

    /** Whom the money goes to: the name he gave, else "పాలతని" for milk. */
    static String payee(JSONObject b) {
        String v = b.optString("vendor").trim();
        if (!v.isEmpty()) return v;
        return MILK.equals(b.optString("key")) ? "పాలతని" : "";
    }

    /** For అమ్మగారు on the tablet: the month's bill, simply. */
    static String ammaLine(JSONObject b, String who) {
        String what = of(b.optString("name"));
        StringBuilder s = new StringBuilder(who).append(", ").append(b.optString("month_te")).append(" నెల ").append(what).append(" బిల్లు ")
                .append(rs(b.optLong("amount"))).append(" అయింది");
        JSONArray missed = b.optJSONArray("missed");
        if (missed != null && missed.length() > 0) s.append(". ").append(daysWord(missed.length())).append(' ').append(b.optString("name")).append(" రాలేదు");
        long paid = b.optLong("paid");
        if (paid > 0) s.append(". ").append(rs(paid)).append(" ఇచ్చారు, ఇంకా ").append(rs(b.optLong("balance"))).append(" ఇవ్వాలి");
        String to = payee(b);
        s.append(". ").append(to.isEmpty() ? "" : to + "కి ").append("డబ్బులు ఇచ్చాక '").append(what).append(" డబ్బులు ఇచ్చాను' అని నాకు చెప్పండి.");
        return s.toString();
    }

    /** "పాలు: రోజూ 1 లీటర్, లీటర్ ₹60 చొప్పున · అక్టోబర్ 1 నుంచి లెక్క · రవి". */
    static String setupLine(JSONObject it, LocalDate today) {
        LocalDate since = LocalDate.parse(it.optString("since"));
        LocalDate now = today.isBefore(since) ? since : today;
        String unit = it.optString("unit");
        double daily = usualOn(it, now);
        StringBuilder s = new StringBuilder(it.optString("name")).append(": ")
                .append(daily > 0 ? "రోజూ " + qtyText(unit, daily) : "రోజూ రావు (వచ్చిన రోజు చెప్పాలి)")
                .append(", ").append(unit.isEmpty() ? "" : unit + " ").append(rate(priceOn(it, now))).append(" చొప్పున")
                .append(" · ").append(MONTHS[since.getMonthValue() - 1]).append(' ').append(since.getDayOfMonth()).append(" నుంచి లెక్క");
        if (!it.optString("vendor").trim().isEmpty()) s.append(" · ").append(it.optString("vendor").trim());
        return s.toString();
    }

    /**
     * What came from the other device, for a quiet note: days in a row with the same word go together
     * ("అక్టోబర్ 23 – 25: పాలు వద్దు"), days ahead are told as ahead, money given as given.
     */
    static List<String> newsLines(JSONObject st, JSONArray news, LocalDate today) {
        List<String> out = new ArrayList<>();
        List<JSONObject> days = new ArrayList<>();
        for (int i = 0; i < news.length(); i++) {
            JSONObject n = news.optJSONObject(i);
            if (n == null) continue;
            if ("pay".equals(n.optString("kind"))) {
                try {
                    String key = n.optString("item");
                    JSONObject it = item(st, key);
                    String name = it == null ? (MILK.equals(key) ? "పాలు" : key) : it.optString("name");
                    YearMonth m = YearMonth.parse(n.optString("m"));
                    out.add(MONTHS[m.getMonthValue() - 1] + " " + of(name) + " బిల్లు " + rs(n.optLong("a")) + (n.optBoolean("x") ? " ఇవ్వలేదని తీసేశారు" : " ఇచ్చారు"));
                } catch (Exception ignored) {}
            } else if (validDay(n.optString("date"))) days.add(n);
        }
        days.sort((a, b) -> (a.optString("item") + a.optString("date")).compareTo(b.optString("item") + b.optString("date")));
        int i = 0;
        while (i < days.size()) {
            JSONObject a = days.get(i);
            int j = i;
            LocalDate start = LocalDate.parse(a.optString("date")), end = start;
            while (j + 1 < days.size()) {
                JSONObject b = days.get(j + 1);
                LocalDate bd = LocalDate.parse(b.optString("date"));
                if (!b.optString("item").equals(a.optString("item")) || b.optDouble("q", -1) != a.optDouble("q", -1) || !bd.equals(end.plusDays(1))) break;
                end = bd;
                j++;
            }
            out.add(dayLine(st, a.optString("item"), start, end, a.optDouble("q", -1), today));
            i = j + 1;
        }
        return out;
    }

    private static String dayLine(JSONObject st, String key, LocalDate start, LocalDate end, double q, LocalDate today) {
        JSONObject it = item(st, key);
        String name = it == null ? (MILK.equals(key) ? "పాలు" : key) : it.optString("name"), unit = it == null ? unitDefault(key) : it.optString("unit");
        String when = MONTHS[start.getMonthValue() - 1] + " " + start.getDayOfMonth()
                + (end.equals(start) ? "" : " – " + (end.getMonthValue() == start.getMonthValue() ? "" : MONTHS[end.getMonthValue() - 1] + " ") + end.getDayOfMonth()) + ": ";
        boolean ahead = start.isAfter(today);
        if (q < 0) return when + name + " మామూలుగానే";
        if (q == 0) return when + name + (ahead ? " వద్దు" : " రాలేదు");
        return when + qtyText(unit, q) + " " + name + (ahead ? " కావాలి" : " వచ్చాయి");
    }

    // ================================================================ two devices

    /**
     * Takes the other device's news: its items (only when takeItems: his phone's word), the later word about each day,
     * each payment (a newer copy of the same id wins). Without takeItems only accounts this device knows are taken
     * (no other words travel). Bad dates are left out. Returns what was new: [{kind: day|pay, item, date|m, q|a, by, x}].
     */
    static JSONArray merge(JSONObject st, JSONObject other, boolean takeItems) throws Exception {
        JSONArray news = new JSONArray();
        if (other == null) return news;
        if (takeItems && other.opt("items") instanceof JSONArray) {
            JSONArray in = other.getJSONArray("items"), ok = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject o = in.optJSONObject(i);
                if (o != null && validDay(o.optString("since")) && o.optJSONObject("price") != null && o.optJSONObject("daily") != null) ok.put(new JSONObject(o.toString()));
            }
            st.put("items", ok);
        }
        JSONObject od = other.optJSONObject("days");
        if (od != null) {
            JSONObject days = st.getJSONObject("days");
            for (Iterator<String> ki = od.keys(); ki.hasNext(); ) {
                String key = ki.next();
                JSONObject src = od.optJSONObject(key);
                if (src == null || (!takeItems && !known(st, key))) continue;
                JSONObject mine = days.optJSONObject(key);
                for (Iterator<String> di = src.keys(); di.hasNext(); ) {
                    String dk = di.next();
                    JSONObject o = entry(src.opt(dk));
                    if (o == null || o.optDouble("q", -1) > MAX_QTY) continue;
                    String[] ends = dk.split("\\.\\.", 2);
                    if (!validDay(ends[0]) || (ends.length == 2 && !validDay(ends[1]))) continue;
                    LocalDate a = LocalDate.parse(ends[0]), b = ends.length == 2 ? LocalDate.parse(ends[1]) : a;
                    if (b.isBefore(a) || a.plusDays(MAX_RANGE - 1).isBefore(b)) continue;
                    for (LocalDate day = a; !day.isAfter(b); day = day.plusDays(1)) {
                        String d = day.toString();
                        JSONObject m = mine == null ? null : mine.optJSONObject(d);
                        if (m == null || o.optLong("t") > m.optLong("t")) {
                            if (mine == null) { mine = new JSONObject(); days.put(key, mine); }
                            mine.put(d, new JSONObject(o.toString()));
                            news.put(new JSONObject().put("kind", "day").put("item", key).put("date", d).put("q", o.optDouble("q", -1)).put("by", o.optString("by")));
                        }
                    }
                }
            }
        }
        JSONObject op = other.optJSONObject("pays");
        if (op != null) {
            JSONObject pays = st.getJSONObject("pays");
            for (Iterator<String> pi = op.keys(); pi.hasNext(); ) {
                String id = pi.next();
                JSONObject o = op.optJSONObject(id), m = pays.optJSONObject(id);
                if (o == null || !validMonth(o.optString("m")) || (!takeItems && !known(st, o.optString("item"))) || o.optLong("a") < 0 || o.optLong("a") > 1_000_000) continue;
                if (m == null || o.optLong("t") > m.optLong("t")) {
                    JSONObject copy = new JSONObject().put("item", o.optString("item")).put("m", o.optString("m")).put("a", o.optLong("a"))
                            .put("t", o.optLong("t")).put("by", who(o.optString("by"))).put("x", o.optBoolean("x"));
                    if (o.optBoolean("x")) copy.put("xby", who(o.optString("xby", o.optString("by"))));
                    if (m != null && m.has("e")) copy.put("e", m.opt("e")).put("ew", m.opt("ew")); // (his phone's own note of the expense: kept)
                    pays.put(id, copy);
                    news.put(new JSONObject().put("kind", "pay").put("id", id).put("item", o.optString("item")).put("m", o.optString("m")).put("a", o.optLong("a"))
                            .put("by", actor(copy)).put("x", o.optBoolean("x")));
                }
            }
        }
        return news;
    }

    /** A day's word as it came: {q, t, by}, or the short [q, t, "m" | "a"] the devices send each other. */
    private static JSONObject entry(Object v) {
        try {
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                return new JSONObject().put("q", o.optDouble("q", -1)).put("t", o.optLong("t")).put("by", who(o.optString("by")));
            }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                if (a.length() < 3) return null;
                return new JSONObject().put("q", a.optDouble(0, -1)).put("t", a.optLong(1)).put("by", "a".equals(a.optString(2)) ? AMMA : ME);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Only "me" / "amma" travel (anything else: me). */
    private static String who(String by) { return AMMA.equals(by) || "a".equals(by) ? AMMA : ME; }

    static boolean validDay(String d) {
        if (d == null || !d.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try { LocalDate.parse(d); return true; } catch (Exception e) { return false; }
    }

    static boolean validMonth(String m) {
        if (m == null || !m.matches("\\d{4}-\\d{2}")) return false;
        try { YearMonth.parse(m); return true; } catch (Exception e) { return false; }
    }

    /**
     * What the other device needs, in the short form: the days from `since` to `until` and the payments of those
     * months (and the items, from his phone), and every word said since recentFrom whatever day or month it is about.
     * An old "back to the usual" word is left out (the other side has long had it).
     */
    static JSONObject snapshot(JSONObject st, LocalDate since, LocalDate until, boolean withItems, long recentFrom) throws Exception {
        JSONObject o = new JSONObject().put("v", 1);
        if (withItems) o.put("items", new JSONArray(st.getJSONArray("items").toString()));
        JSONObject days = new JSONObject(), src = st.getJSONObject("days");
        String from = since.toString(), to = until.toString();
        for (Iterator<String> ki = src.keys(); ki.hasNext(); ) {
            String key = ki.next();
            JSONObject k = src.optJSONObject(key), out = new JSONObject();
            if (k == null) continue;
            TreeMap<String, JSONArray> chosen = new TreeMap<>();
            for (Iterator<String> di = k.keys(); di.hasNext(); ) {
                String d = di.next();
                JSONObject e = k.optJSONObject(d);
                if (e == null || !validDay(d)) continue;
                double q = e.optDouble("q", -1);
                boolean recent = e.optLong("t") >= recentFrom, inside = d.compareTo(from) >= 0 && d.compareTo(to) <= 0;
                if (!recent && !(inside && q >= 0)) continue; // (a word said lately always goes, whatever its day; an old "usual" word no more)
                chosen.put(d, new JSONArray().put(q < 0 ? -1 : q).put(e.optLong("t")).put(AMMA.equals(e.optString("by")) ? "a" : "m"));
            }
            // days in a row said in one word ("3 రోజులు పాలు వద్దు") go as one range: "2026-10-22..2026-10-24"
            String start = null, prev = null;
            JSONArray run = null;
            for (Map.Entry<String, JSONArray> en : chosen.entrySet()) {
                String d = en.getKey();
                boolean joins = run != null && run.toString().equals(en.getValue().toString()) && LocalDate.parse(prev).plusDays(1).toString().equals(d);
                if (!joins) {
                    if (run != null) out.put(start.equals(prev) ? start : start + ".." + prev, run);
                    start = d;
                    run = en.getValue();
                }
                prev = d;
            }
            if (run != null) out.put(start.equals(prev) ? start : start + ".." + prev, run);
            if (out.length() > 0) days.put(key, out);
        }
        JSONObject pays = new JSONObject(), ps = st.getJSONObject("pays");
        String fromM = YearMonth.from(since).toString();
        for (Iterator<String> pi = ps.keys(); pi.hasNext(); ) {
            String id = pi.next();
            JSONObject p = ps.optJSONObject(id);
            if (p == null || (p.optString("m").compareTo(fromM) < 0 && p.optLong("t") < recentFrom)) continue;
            JSONObject q = new JSONObject().put("item", p.optString("item")).put("m", p.optString("m")).put("a", p.optLong("a"))
                    .put("t", p.optLong("t")).put("by", p.optString("by")).put("x", p.optBoolean("x"));
            if (p.optBoolean("x")) q.put("xby", p.optString("xby", p.optString("by")));
            pays.put(id, q);
        }
        return o.put("days", days).put("pays", pays);
    }

    /**
     * The snapshot that fits one message (Telegram: 4,096 characters; room kept for the first line): 70 days back and
     * two months ahead, else fewer days back / ahead until it fits.
     */
    static JSONObject fitting(JSONObject st, boolean withItems, LocalDate today, int room, long now) throws Exception {
        int[][] windows = {{70, 62, 30}, {45, 45, 14}, {30, 31, 7}, {14, 14, 3}, {7, 7, 1}, {2, 2, 0}};
        JSONObject o = null;
        for (int[] w : windows) {
            o = snapshot(st, today.minusDays(w[0]), today.plusDays(w[1]), withItems, w[2] == 0 ? Long.MAX_VALUE : now - w[2] * 86_400_000L);
            if (o.toString().length() <= room) return o;
        }
        return o;
    }

    /** The newest time anything was said by `by` (a day or a payment) for an account kept here; 0: never. */
    static long newestBy(JSONObject st, String by) {
        long t = 0;
        JSONObject days = st.optJSONObject("days");
        if (days != null) for (Iterator<String> ki = days.keys(); ki.hasNext(); ) {
            String key = ki.next();
            JSONObject k = known(st, key) ? days.optJSONObject(key) : null;
            if (k != null) for (Iterator<String> di = k.keys(); di.hasNext(); ) {
                JSONObject e = k.optJSONObject(di.next());
                if (e != null && by.equals(e.optString("by"))) t = Math.max(t, e.optLong("t"));
            }
        }
        JSONObject pays = st.optJSONObject("pays");
        if (pays != null) for (Iterator<String> pi = pays.keys(); pi.hasNext(); ) {
            JSONObject p = pays.optJSONObject(pi.next());
            if (p != null && by.equals(actor(p)) && known(st, p.optString("item"))) t = Math.max(t, p.optLong("t"));
        }
        return t;
    }

    /** Days and payments older than KEEP_MONTHS go. */
    static void prune(JSONObject st, LocalDate today) throws Exception {
        String cut = today.minusMonths(KEEP_MONTHS).withDayOfMonth(1).toString(), cutM = cut.substring(0, 7);
        JSONObject days = st.getJSONObject("days");
        for (Iterator<String> ki = days.keys(); ki.hasNext(); ) {
            JSONObject k = days.optJSONObject(ki.next());
            if (k == null) continue;
            List<String> old = new ArrayList<>();
            for (Iterator<String> di = k.keys(); di.hasNext(); ) { String d = di.next(); if (d.compareTo(cut) < 0) old.add(d); }
            for (String d : old) k.remove(d);
        }
        JSONObject pays = st.getJSONObject("pays");
        List<String> old = new ArrayList<>();
        for (Iterator<String> pi = pays.keys(); pi.hasNext(); ) { String id = pi.next(); JSONObject p = pays.optJSONObject(id); if (p == null || p.optString("m").compareTo(cutM) < 0) old.add(id); }
        for (String id : old) pays.remove(id);
    }

    // ================================================================ his words for days and months

    /** "", "today", "ఈ రోజు" → today; "yesterday" / "నిన్న"; "tomorrow" / "రేపు"; YYYY-MM-DD. Null: can't be read. */
    static LocalDate date(String s, LocalDate today) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("today") || t.contains("ఈ రోజు") || t.contains("ఈరోజు")) return today;
        if (t.equals("yesterday") || t.contains("నిన్న")) return today.minusDays(1);
        if (t.equals("tomorrow") || t.contains("రేపు")) return today.plusDays(1);
        try { return LocalDate.parse(t); } catch (Exception e) { return null; }
    }

    /** "", "this" → this month; "last" / "గత" / "పోయిన" → last month; YYYY-MM. Null: can't be read. */
    static YearMonth month(String s, LocalDate today) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("this") || t.contains("ఈ నెల")) return YearMonth.from(today);
        if (t.startsWith("last") || t.contains("గత") || t.contains("పోయిన")) return YearMonth.from(today).minusMonths(1);
        try { return YearMonth.parse(t.length() > 7 ? t.substring(0, 7) : t); } catch (Exception e) { return null; }
    }

    static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
