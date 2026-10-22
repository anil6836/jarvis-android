package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 🚗 The car's diesel, mileage and service on his phone (CarBook holds the rules, CarReport the month's page). He tells
 * Jarvis a fill by voice ("కారుకి ₹3000 డీజిల్, 32 లీటర్లు, మీటర్ 45,230, ఫుల్") or types it in Settings; km come from
 * his meter readings, else the trip diary's car trips (GPS, approx). Each fill / service goes into his month's expenses
 * (fuel / car) while that switch is on; the service is reminded before it is due (by date or km), a mileage drop is told
 * once (only from meter readings), and on the 1st a note of last month. Jarvis never pays. Everything stays on the phone.
 */
final class Car {
    private Car() {}

    static final String PREFS = "jarvis_car";
    /** Switches (on unless he turns them off). */
    static final String TO_EXPENSE = "to_expense", SERVICE = "svc_remind", DROP_TELL = "drop_tell";
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final int NOTE_SVC = 1, NOTE_MONTH = 2;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean on(Context c, String k) { return sp(c).getBoolean(k, true); }

    static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    static synchronized JSONObject load(Context c) { return CarBook.parse(sp(c).getString("state", "")); }

    private static synchronized void save(Context c, JSONObject st) {
        try { CarBook.prune(st); } catch (Exception ignored) {}
        sp(c).edit().putString("state", st.toString()).apply();
    }

    static boolean isSet(Context c) { return CarBook.isSet(load(c)); }

    private static ZoneId zone() { return ZoneId.systemDefault(); }

    private static List<double[]> tripsCache;
    private static long tripsMod = -1, tripsLen = -1;

    /** The trip diary's car trips: {end ms, km} (the bike's rides are not the car's); read again only when the file changed. */
    static List<double[]> trips(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), TripLog.FILE);
        long m = f.lastModified(), n = f.length();
        synchronized (Car.class) {
            if (tripsCache != null && m == tripsMod && n == tripsLen) return new ArrayList<>(tripsCache);
        }
        List<double[]> out = new ArrayList<>();
        JSONArray a = TripLog.all(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t != null && "car".equals(t.optString("by")) && t.optDouble("km", 0) > 0) out.add(new double[]{t.optLong("end"), t.optDouble("km")});
        }
        synchronized (Car.class) {
            tripsCache = out;
            tripsMod = m;
            tripsLen = n;
        }
        return new ArrayList<>(out);
    }

    // ================================================================ the tool

    private static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }

    private static JSONObject err(String code, String say) throws Exception { return new JSONObject().put("ok", false).put("error", code).put("say", say); }

    /** A number he gave ("45,230", "1,00,000", "93.75", "₹3000", "45230 km"), or NaN when left out / not a number. */
    static double number(JSONObject a, String k) {
        if (!a.has(k) || a.isNull(k)) return Double.NaN;
        Object v = a.opt(k);
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return Double.isFinite(d) ? d : Double.NaN; }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT)
                .replaceAll("(₹|rs\\.?|inr|రూపాయలు|రూ\\.?|/\\s*(l|lt|ltr|litre|liter|లీటర్)\\b.*|kms?\\b|కి\\.?\\s*మీ\\.?|litres?|liters?|ltrs?|లీటర్లు|లీటర్|odo|మీటర్)", "")
                .replace(" ", "").trim();
        if (s.isEmpty()) return Double.NaN;
        // "45,230" / "1,00,000" are thousands commas; "93,75" (one or two digits after a comma) a decimal one
        if (s.matches("\\d{1,3}(,\\d{2,3})*,\\d{3}(\\.\\d+)?")) s = s.replace(",", "");
        else if (s.matches("\\d+,\\d{1,2}")) s = s.replace(',', '.');
        if (!s.matches("\\d+(\\.\\d+)?")) return Double.NaN;
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }

    /** Given but not a number he could have meant (then he is asked, never a silent "not given"). */
    static boolean unreadable(JSONObject a, String k) {
        if (!a.has(k) || a.isNull(k)) return false;
        Object v = a.opt(k);
        if (v instanceof Number) return !Double.isFinite(((Number) v).doubleValue());
        return !String.valueOf(v).trim().isEmpty() && Double.isNaN(number(a, k));
    }

    /** A meter / km the AI sent as 0 for "not said" is not a reading. */
    private static double reading(JSONObject a, String k) {
        double v = number(a, k);
        return v > 0 ? v : Double.NaN;
    }

    /** car_account (all but report / share, which open a file: Tools). */
    static JSONObject tool(Context c, JSONObject a) throws Exception {
        String action = a.optString("action", "").trim().toLowerCase(Locale.ROOT);
        String[][] nums = {{"rupees", "రూపాయలు"}, {"litres", "లీటర్లు"}, {"rate", "లీటర్ రేటు"}, {"odometer", "మీటర్ రీడింగ్"}, {"last_service_km", "సర్వీస్ మీటర్"}};
        for (String[] n : nums) if (unreadable(a, n[0])) return err("bad_number", n[1] + " అర్థం కాలేదు (\"" + a.opt(n[0]) + "\"). అంకెల్లో మళ్ళీ చెప్పండి.");
        double rs = number(a, "rupees"), l = number(a, "litres"), rate = number(a, "rate"), odo = reading(a, "odometer");
        if (action.isEmpty()) action = !Double.isNaN(rs) || !Double.isNaN(l) ? "fill" : !Double.isNaN(odo) ? "meter" : "status";
        if (action.equals("diesel") || action.equals("petrol") || action.equals("add")) action = "fill";
        if (action.equals("odometer") || action.equals("reading")) action = "meter";
        if (action.equals("service_done") || action.equals("serviced")) action = "service";
        boolean sure = a.optBoolean("meter_ok");
        long now = System.currentTimeMillis();
        ZoneId z = zone();
        LocalDate today = LocalDate.now(z);
        LocalDate day = HouseBook.date(a.optString("date"), today);
        String jump = "If the error is big_jump, read him the say line; only if he says the reading is right, call again with meter_ok = true.";
        synchronized (Car.class) {
            JSONObject st = load(c);
            switch (action) {
                case "setup": {
                    String fuel = a.optString("fuel").trim().isEmpty() ? null : CarBook.fuelKey(a.optString("fuel"));
                    if (!a.optString("fuel").trim().isEmpty() && fuel == null) return err("bad_fuel", "ఇంధనం: డీజిల్, పెట్రోల్ లేదా CNG.");
                    LocalDate last = a.optString("last_service_date").trim().isEmpty() ? null : HouseBook.date(a.optString("last_service_date"), today);
                    if (!a.optString("last_service_date").trim().isEmpty() && last == null) return err("bad_date", "చివరి సర్వీస్ తేదీ అర్థం కాలేదు (YYYY-MM-DD).");
                    boolean was = CarBook.isSet(st);
                    String bad = CarBook.setup(st, a.optString("name"), fuel, (int) Math.round(nz(number(a, "every_km"))), (int) Math.round(nz(number(a, "every_months"))),
                            last, reading(a, "last_service_km"), today, now, z);
                    if (bad != null) return err("bad", bad);
                    save(c, st);
                    JSONObject s = CarBook.service(st, trips(c), today, now, z);
                    return ok().put("said", (was ? "మార్చాను: " : "కారు లెక్క మొదలుపెట్టాను: ") + CarBook.name(st) + " · " + CarBook.fuelWord(CarBook.fuel(st))
                                    + " · సర్వీస్ ప్రతి " + CarBook.km(CarBook.everyKm(st)) + " కి.మీ లేదా " + CarBook.everyMonths(st) + " నెలలకి.")
                            .put("service", CarBook.serviceLine(st, s))
                            .put("next", "Say it in one or two short Telugu lines. If the last service is not known, ask once when it was and at what meter reading.");
                }
                case "fill": {
                    boolean fresh = !CarBook.isSet(st);
                    if (fresh) CarBook.setup(st, null, CarBook.fuelKey(a.optString("fuel")), 0, 0, null, Double.NaN, today, now, z);
                    if (day == null) return err("bad_date", "ఏ రోజు కొట్టించారో అర్థం కాలేదు.");
                    double lr = CarBook.lastRate(st, CarBook.at(day, today, now, z));
                    JSONObject f = CarBook.addFill(st, day, today, now, z, rs, l, rate, odo, a.optBoolean("full"), sure);
                    if (f.has("error")) return err(f.optString("error"), f.optString("say")).put("next", jump);
                    expense(c, st, f, true);
                    save(c, st);
                    List<double[]> tr = trips(c);
                    JSONObject o = ok().put("id", f.optString("id")).put("said", CarBook.fillSaid(f, CarBook.fuel(st), lr)).put("mileage", CarBook.mileageLine(st, tr));
                    JSONObject dr = CarBook.drop(CarBook.segments(st, tr));
                    if (dr != null && on(c, DROP_TELL) && dr.optString("end_id").equals(f.optString("id")) && !dr.optString("end_id").equals(sp(c).getString("drop_told", ""))) {
                        sp(c).edit().putString("drop_told", dr.optString("end_id")).apply();
                        o.put("mileage_drop", CarBook.dropLine(dr));
                    }
                    o.put("expense", expenseNote(f));
                    return o.put("next", "Say it back in short spoken Telugu (the amount, litres, meter; the mileage only if it is known). Do not also call add_expense"
                            + (o.has("mileage_drop") ? ". Then gently the mileage_drop line (never guess a cause)" : "")
                            + (fresh ? ". This was the first fill: the car account starts now (" + CarBook.fuelWord(CarBook.fuel(st))
                                    + ", service every 10,000 km / 12 months unless he says): ask once when the last service was and the meter then" : "")
                            + ". Never pay or open a payment app.");
                }
                case "meter": {
                    if (!CarBook.isSet(st)) CarBook.setup(st, null, null, 0, 0, null, Double.NaN, today, now, z);
                    if (day == null) return err("bad_date", "ఏ రోజు రీడింగో అర్థం కాలేదు.");
                    if (Double.isNaN(odo)) return err("need_odo", "మీటర్ రీడింగ్ ఎంత? (మొత్తం ODO, ట్రిప్ మీటర్ కాదు)");
                    JSONObject r = CarBook.addOdo(st, day, today, now, z, odo, sure);
                    if (r.has("error")) return err(r.optString("error"), r.optString("say")).put("next", jump);
                    save(c, st);
                    List<double[]> tr = trips(c);
                    JSONObject o = ok().put("id", r.optString("id")).put("said", "రాశాను: మీటర్ " + CarBook.km(odo) + (r.has("attached") ? " (ఇందాక చెప్పిన " + CarBook.fuelWord(CarBook.fuel(st)) + "కి కలిపాను)" : "") + ".")
                            .put("service", CarBook.serviceLine(st, CarBook.service(st, tr, today, now, z)));
                    if (r.has("attached")) { // (that fill's mileage can be worked out now)
                        o.put("mileage", CarBook.mileageLine(st, tr));
                        JSONObject dr = CarBook.drop(CarBook.segments(st, tr));
                        if (dr != null && on(c, DROP_TELL) && dr.optString("end_id").equals(r.optString("attached")) && !dr.optString("end_id").equals(sp(c).getString("drop_told", ""))) {
                            sp(c).edit().putString("drop_told", dr.optString("end_id")).apply();
                            o.put("mileage_drop", CarBook.dropLine(dr)).put("next", "Say it back briefly, then gently the mileage_drop line (never guess a cause).");
                        }
                    }
                    return o;
                }
                case "service": {
                    if (!CarBook.isSet(st)) CarBook.setup(st, null, null, 0, 0, null, Double.NaN, today, now, z);
                    if (day == null) return err("bad_date", "ఏ రోజు సర్వీస్ అయిందో అర్థం కాలేదు.");
                    JSONObject s = CarBook.addService(st, day, today, now, z, rs, odo, a.optString("what"), sure);
                    if (s.has("error")) return err(s.optString("error"), s.optString("say")).put("next", jump);
                    expense(c, st, s, false);
                    save(c, st);
                    sp(c).edit().remove("svc_told").remove("svc_told_at").apply();
                    return ok().put("id", s.optString("id")).put("said", "రాశాను: " + CarBook.name(st) + " సర్వీస్ " + CarBook.dayName(s.optString("d"))
                                    + (s.has("rs") ? ", " + HouseBook.rs(Math.round(s.optDouble("rs"))) : "") + (s.has("odo") ? ", మీటర్ " + CarBook.km(s.optDouble("odo")) : "") + ".")
                            .put("service", CarBook.serviceLine(st, CarBook.service(st, trips(c), today, now, z))).put("expense", expenseNote(s))
                            .put("next", "Say it back briefly with when the next service is. Do not also call add_expense."
                                    + (s.has("rs") ? "" : " The cost was not said: it is fine without it.") + (s.has("odo") ? "" : " Ask once for the meter reading at the service (it helps the km reminder)."));
                }
                case "month": {
                    if (!CarBook.isSet(st)) return err("not_set", "కారు లెక్క ఇంకా మొదలవలేదు: డీజిల్ కొట్టించినప్పుడు 'కారుకి ₹3000 డీజిల్, 32 లీటర్లు, మీటర్ …' అని చెప్పండి.");
                    YearMonth ym = HouseBook.month(a.optString("month"), today);
                    if (ym == null) return err("bad_month", "ఏ నెలో అర్థం కాలేదు.");
                    List<double[]> tr = trips(c);
                    JSONObject m = CarBook.month(st, tr, ym, today, now, z);
                    String line = CarBook.monthLine(st, m);
                    JSONObject o = ok().put("month_line", line.isEmpty() ? CarBook.MONTHS[ym.getMonthValue() - 1] + ": ఏమీ రాయలేదు." : line)
                            .put("mileage", CarBook.mileageLine(st, tr));
                    if (m.has("rs_per_km")) o.put("rs_per_km_that_month", m.optDouble("rs_per_km"));
                    return o.put("next", "Say it in short spoken Telugu. Offer the month's PDF (car_account report).");
                }
                case "undo": {
                    JSONObject peek = CarBook.undo(CarBook.parse(st.toString()));
                    if (peek == null) return err("nothing", "తీసేయడానికి ఏమీ లేదు.");
                    String id = peek.optString("id");
                    if (!a.optBoolean("confirmed") || !id.equals(a.optString("id").trim()))
                        return ok().put("id", id).put("ask", "చివరిగా రాసింది: " + entryText(st, peek) + ". ఇది తీసేయమంటారా?")
                                .put("next", "Ask him this; only after his yes call car_account undo with confirmed = true and id = \"" + id + "\".");
                    JSONObject u = CarBook.undo(st);
                    JSONObject e = u.optJSONObject("entry");
                    boolean money = e != null && e.has("e") && !e.optBoolean("linked") && !"attached".equals(u.optString("kind")); // (a meter alone: the fill and its money stay)
                    if (money) Money.removeAt(c, e.optLong("e"), e.optString("ew"));
                    save(c, st);
                    if ("svcs".equals(u.optString("kind"))) sp(c).edit().remove("svc_told").remove("svc_told_at").apply();
                    return ok().put("said", "తీసేశాను: " + entryText(st, u) + (money ? " (నెల ఖర్చుల్లో నుంచి కూడా)" : "") + ".");
                }
                case "remove": {
                    if (!a.optBoolean("confirmed")) return ok().put("ask", "కారు లెక్క మొత్తం (డీజిల్, మీటర్, సర్వీస్) తీసేయమంటారా? నెల ఖర్చుల్లో రాసినవి అలాగే ఉంటాయి.")
                            .put("next", "Ask him; only after his clear yes call car_account remove with confirmed = true.");
                    sp(c).edit().remove("state").remove("drop_told").remove("svc_told").remove("svc_told_at").apply();
                    return ok().put("said", "కారు లెక్క మొత్తం తీసేశాను.");
                }
                default: { // status
                    if (!CarBook.isSet(st)) return err("not_set", "కారు లెక్క ఇంకా మొదలవలేదు: డీజిల్ కొట్టించినప్పుడు 'కారుకి ₹3000 డీజిల్, 32 లీటర్లు, మీటర్ …' అని చెప్పండి, "
                            + "లేదా Settings → 🚗 కారు లెక్కలు లో సెట్ చేయండి.");
                    List<double[]> tr = trips(c);
                    JSONObject o = ok().put("car", CarBook.name(st) + " · " + CarBook.fuelWord(CarBook.fuel(st))).put("mileage", CarBook.mileageLine(st, tr));
                    String m = CarBook.monthLine(st, CarBook.month(st, tr, YearMonth.from(today), today, now, z));
                    if (!m.isEmpty()) o.put("this_month", m);
                    List<JSONObject> fs = CarBook.fills(st);
                    if (!fs.isEmpty()) o.put("last_fill", CarBook.dayName(fs.get(fs.size() - 1).optString("d")) + ": " + CarBook.fillText(fs.get(fs.size() - 1), CarBook.fuel(st)));
                    double[] cur = CarBook.odoAt(st, tr, now);
                    if (cur != null) o.put("meter_now", (cur[1] > 0 ? "సుమారు " : "") + CarBook.km(cur[0]));
                    return o.put("service", CarBook.serviceLine(st, CarBook.service(st, tr, today, now, z)))
                            .put("next", "Answer only what he asked, in short spoken Telugu (mileage / this month / service).");
                }
            }
        }
    }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    private static String expenseNote(JSONObject e) {
        if (!e.has("e")) return "not in his expenses (the expenses switch is off, or no amount)";
        return e.optBoolean("linked") ? "it was already in his expenses (the same amount he wrote): not added twice" : "added to his month's expenses";
    }

    /** "అక్టోబర్ 22: ₹3,000 డీజిల్ · 32 లీటర్లు …" / "మీటర్ 45,500" / "సర్వీస్ …". */
    private static String entryText(JSONObject st, JSONObject u) {
        JSONObject e = u.optJSONObject("entry");
        if (e == null) return "";
        String day = CarBook.dayName(e.optString("d")) + ": ";
        switch (u.optString("kind")) {
            case "attached": return day + "మీటర్ " + CarBook.km(e.optDouble("odo")) + " (" + CarBook.fuelWord(CarBook.fuel(st)) + "కి కలిపినది; " + CarBook.fuelWord(CarBook.fuel(st)) + " అలాగే ఉంటుంది)";
            case "fills": return day + CarBook.fillText(e, CarBook.fuel(st));
            case "odos": return day + "మీటర్ " + CarBook.km(e.optDouble("odo"));
            default: return day + "సర్వీస్" + (e.has("rs") ? " " + HouseBook.rs(Math.round(e.optDouble("rs"))) : "") + (e.has("odo") ? ", మీటర్ " + CarBook.km(e.optDouble("odo")) : "");
        }
    }

    // ================================================================ his expenses

    /** The expense categories a fill / a service may already sit under when he wrote it himself (or from a bill photo). */
    private static boolean sameKind(String cat, boolean fill) {
        if (fill) return "fuel".equals(cat);
        return "car".equals(cat) || "bills".equals(cat) || "other".equals(cat) || "travel".equals(cat) || cat.isEmpty();
    }

    /**
     * A fill (category fuel) or a service (category car) into his month's expenses while the switch is on. The same
     * amount already written by him within a day and a half (by hand or from a bill photo) is taken as this one (linked,
     * the nearest in time), never written twice; a linked one stays when the entry is taken back (it was his own).
     */
    private static void expense(Context c, JSONObject st, JSONObject e, boolean fill) {
        if (!on(c, TO_EXPENSE)) return;
        double rs = e.optDouble("rs", 0);
        if (rs <= 0) return;
        try {
            Set<Long> used = new HashSet<>();
            for (String k : new String[]{"fills", "svcs"}) for (JSONObject o : CarBook.list(st, k)) if (o.has("e")) used.add(o.optLong("e"));
            JSONObject best = null;
            long bestGap = 36 * HOUR;
            for (JSONObject x : Money.expenses(c)) {
                if (!sameKind(x.optString("category"), fill) || Math.abs(x.optDouble("amount") - rs) >= 1 || used.contains(x.optLong("t"))) continue;
                long gap = Math.abs(x.optLong("t") - e.optLong("at"));
                if (gap < bestGap) { bestGap = gap; best = x; }
            }
            if (best != null) {
                e.put("e", best.optLong("t")).put("ew", best.optString("what")).put("linked", true);
                return;
            }
            String what = fill ? CarBook.name(st) + " " + CarBook.fuelWord(CarBook.fuel(st)) + " · " + CarBook.litres(e.optDouble("l")) + " లీటర్లు"
                    : CarBook.name(st) + " సర్వీస్" + (e.optString("what").isEmpty() ? "" : " · " + e.optString("what"));
            JSONObject m = Money.add(c, rs, what, "", fill ? "fuel" : "car", Math.min(System.currentTimeMillis(), e.optLong("at")));
            e.put("e", m.optLong("t")).put("ew", what);
        } catch (Exception ignored) {}
    }

    /**
     * add_expense: is this amount a fill / service the car account already wrote into his expenses (within a day and a
     * half)? Then it is not written a second time (e.g. the bunk bill's photo after he told the fill).
     */
    static boolean covers(Context c, double amount, long when, String category) {
        try {
            String cat = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
            JSONObject st = load(c);
            for (String k : new String[]{"fills", "svcs"}) {
                boolean fill = k.equals("fills");
                if (!sameKind(cat, fill)) continue;
                for (JSONObject o : CarBook.list(st, k))
                    if (o.has("e") && Math.abs(o.optDouble("rs", 0) - amount) < 1 && Math.abs(o.optLong("at") - when) < 36 * HOUR) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ================================================================ reminders (Proactive.tick, background, ~15 minutes)

    static void tick(Context c, Prefs p, boolean hush) {
        if (p.homeMode()) return;
        JSONObject st = load(c);
        if (!CarBook.isSet(st)) return;
        long now = System.currentTimeMillis();
        ZoneId z = zone();
        LocalDate today = LocalDate.now(z);
        int h = java.time.LocalTime.now(z).getHour();
        SharedPreferences s = sp(c);
        try {
            if (on(c, SERVICE) && h >= 9 && h < 20 && since(c, "svc_checked") > 6 * HOUR) {
                s.edit().putLong("svc_checked", now).apply();
                JSONObject sv = CarBook.service(st, trips(c), today, now, z);
                if (sv.optBoolean("known")) {
                    String key = sv.optLong("last_at") + "|", told = s.getString("svc_told", "");
                    String state = sv.optString("state");
                    if ("due".equals(state) && (!told.equals(key + "due") || since(c, "svc_told_at") > 7 * DAY)) {
                        s.edit().putString("svc_told", key + "due").putLong("svc_told_at", now).apply();
                        notify(c, NOTE_SVC, "🔧 " + CarBook.name(st) + " సర్వీస్ టైమ్", CarBook.serviceLine(st, sv), "కారు సర్వీస్ ఎప్పుడు? (car_account status)");
                    } else if ("soon".equals(state) && !told.startsWith(key)) {
                        s.edit().putString("svc_told", key + "soon").putLong("svc_told_at", now).apply();
                        notify(c, NOTE_SVC, "🔧 " + CarBook.name(st) + " సర్వీస్ దగ్గర పడింది", CarBook.serviceLine(st, sv), "కారు సర్వీస్ ఎప్పుడు? (car_account status)");
                    }
                }
            }
            YearMonth last = YearMonth.from(today).minusMonths(1);
            if (today.getDayOfMonth() <= 3 && h >= 9 && h < 13 && !hush && !s.getString("month_told", "").equals(last.toString())) {
                s.edit().putString("month_told", last.toString()).apply();
                String line = CarBook.monthLine(st, CarBook.month(st, trips(c), last, today, now, z));
                if (!line.isEmpty()) notify(c, NOTE_MONTH, "🚗 " + CarBook.MONTHS[last.getMonthValue() - 1] + " కారు లెక్క", line + "\nనొక్కితే నెల రిపోర్ట్ PDF.",
                        CarBook.MONTHS[last.getMonthValue() - 1] + " నెల కారు లెక్క చెప్పు, ఆ నెల రిపోర్ట్ PDF కూడా తెరువు (car_account report month=" + last + ").");
            }
        } catch (Exception ignored) {}
    }

    /** How long since that time was set (a clock gone back counts as long ago). */
    private static long since(Context c, String k) {
        long d = System.currentTimeMillis() - sp(c).getLong(k, 0);
        return d < 0 ? Long.MAX_VALUE : d;
    }

    private static void notify(Context c, int id, String title, String text, String ask) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_car", "🚗 కారు లెక్కలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 3950 + id, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, ask).putExtra(MainActivity.EXTRA_LABEL, "🚗 కారు లెక్క").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("car", id, new Notification.Builder(c, "jarvis_car").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ the month

    /** For the month's report (Monthly): one line, or "" when nothing. */
    static String monthLines(Context c, YearMonth ym) {
        try {
            JSONObject st = load(c);
            if (!CarBook.isSet(st)) return "";
            long now = System.currentTimeMillis();
            return CarBook.monthLine(st, CarBook.month(st, trips(c), ym, LocalDate.now(zone()), now, zone()));
        } catch (Exception e) {
            return "";
        }
    }

    static Coder.Made pdf(Context c, YearMonth ym) throws Exception { return CarReport.pdf(c, ym); }
}
