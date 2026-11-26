package com.anil.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 💰 Jarvis's money advisor on the phone: his details, goals, investments, loan rates and card dues (kept in jarvis_finance,
 * in his own Drive backup like his other Jarvis data); the month read from bank / UPI / card SMS (counted here, the SMS text
 * never leaves the phone); the evening check (unusual debits, salary-day "save first", low balance, a subscription renewing,
 * an FD maturing, the month's report on the 1st and, only if he switched it on, the AI's monthly review); fund NAVs (AMFI)
 * and share prices fetched for his own holdings. Jarvis never pays, buys, sells or opens a bank / investment app.
 */
final class Finance {
    private Finance() {}

    static final String PREFS = "jarvis_finance";
    static final String ACTION_DAILY = "com.anil.jarvis.FIN_DAILY";
    static final String AMFI = "https://portal.amfiindia.com/spages/NAVAll.txt";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static ZoneId zone() { return ZoneId.systemDefault(); }

    // ================================================================ his details

    static FinCoach.Profile profile(Context c) {
        Prefs p = new Prefs(c);
        SharedPreferences s = sp(c);
        FinCoach.Profile f = new FinCoach.Profile();
        f.income = p.income();
        f.saveGoal = p.savingsGoal();
        f.budget = Math.max(0, p.budget());
        f.salaryDay = s.getInt("salary_day", 0);
        f.age = s.getInt("age", 0);
        f.dependents = s.getInt("dependents", 0);
        f.risk = s.getString("risk", "mid");
        f.efMonths = s.getInt("ef_months", 6);
        f.term = s.getLong("term", 0);
        f.health = s.getLong("health", 0);
        f.healthFamily = s.getBoolean("health_family", false);
        f.lowBal = s.getLong("low_bal", 0);
        return f;
    }

    static JSONObject profileJson(Context c) throws Exception {
        FinCoach.Profile f = profile(c);
        SharedPreferences s = sp(c);
        return new JSONObject().put("income", f.income).put("save_goal", f.saveGoal).put("budget", f.budget).put("salary_day", f.salaryDay)
                .put("age", f.age).put("dependents", f.dependents).put("risk", f.risk).put("ef_months", f.efMonths).put("term", f.term)
                .put("health", f.health).put("health_family", f.healthFamily).put("low_bal", f.lowBal)
                .put("alert_unusual", s.getBoolean("alert_unusual", true)).put("alert_salary", s.getBoolean("alert_salary", true))
                .put("alert_subs", s.getBoolean("alert_subs", true)).put("alert_maturity", s.getBoolean("alert_maturity", true))
                .put("month_report", s.getBoolean("month_report", true)).put("ai_monthly", s.getBoolean("ai_monthly", false))
                .put("card_due", s.getLong("card_due", 0)).put("card_rate", s.getFloat("card_rate", 3.5f));
    }

    /** From the screen or the voice tool: only the fields given change. */
    static void saveProfile(Context c, JSONObject o) {
        SharedPreferences.Editor jp = new Prefs(c).sp.edit();
        if (o.has("income")) jp.putInt("income", clampI(o.optDouble("income"), 0, 1e8));
        if (o.has("save_goal")) jp.putInt("savings_goal", clampI(o.optDouble("save_goal"), 0, 1e8));
        if (o.has("budget")) jp.putInt("budget", clampI(o.optDouble("budget"), 0, 1e8));
        jp.apply();
        SharedPreferences.Editor e = sp(c).edit();
        if (o.has("salary_day")) e.putInt("salary_day", clampI(o.optDouble("salary_day"), 0, 31));
        if (o.has("age")) e.putInt("age", clampI(o.optDouble("age"), 0, 100));
        if (o.has("dependents")) e.putInt("dependents", clampI(o.optDouble("dependents"), 0, 20));
        if (o.has("risk")) { String r = o.optString("risk"); if (r.equals("low") || r.equals("mid") || r.equals("high")) e.putString("risk", r); }
        if (o.has("ef_months")) e.putInt("ef_months", clampI(o.optDouble("ef_months"), 3, 24));
        if (o.has("term")) e.putLong("term", (long) Math.max(0, o.optDouble("term")));
        if (o.has("health")) e.putLong("health", (long) Math.max(0, o.optDouble("health")));
        if (o.has("health_family")) e.putBoolean("health_family", o.optBoolean("health_family"));
        if (o.has("low_bal")) e.putLong("low_bal", (long) Math.max(0, o.optDouble("low_bal")));
        if (o.has("card_due")) e.putLong("card_due", (long) Math.max(0, o.optDouble("card_due")));
        if (o.has("card_rate")) e.putFloat("card_rate", (float) Math.max(0, Math.min(6, o.optDouble("card_rate"))));
        for (String k : new String[]{"alert_unusual", "alert_salary", "alert_subs", "alert_maturity", "month_report", "ai_monthly"})
            if (o.has(k)) e.putBoolean(k, o.optBoolean(k));
        e.apply();
        rearm(c);
    }

    private static int clampI(double v, double lo, double hi) { return (int) Math.round(Math.max(lo, Math.min(hi, Double.isNaN(v) ? 0 : v))); }

    // ================================================================ lists he keeps (goals, investments, loan rates, subscription marks)

    static JSONArray arr(Context c, String key) {
        try { return new JSONArray(sp(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject map(Context c, String key) {
        try { return new JSONObject(sp(c).getString(key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void put(Context c, String key, Object json) { sp(c).edit().putString(key, json.toString()).apply(); }

    /** Adds or replaces (by id) one goal / holding; returns it with its id. */
    /** (his lists are changed from the screen, the voice tool and the price refresh: one at a time) */
    private static final Object LOCK = new Object();

    static JSONObject upsert(Context c, String key, JSONObject item) throws Exception {
        synchronized (LOCK) { return upsertLocked(c, key, item); }
    }

    private static JSONObject upsertLocked(Context c, String key, JSONObject item) throws Exception {
        JSONArray a = arr(c, key), out = new JSONArray();
        String id = item.optString("id", "");
        if (id.isEmpty()) { id = Notes.id(key.substring(0, 1)); item.put("id", id); }
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (o.optString("id").equals(id)) {
                for (java.util.Iterator<String> it = item.keys(); it.hasNext(); ) { String k = it.next(); o.put(k, item.get(k)); }
                out.put(o);
                item = o;
                found = true;
            } else out.put(o);
        }
        if (!found) {
            if (out.length() >= 60) throw new IllegalStateException("too many");
            out.put(item);
        }
        put(c, key, out);
        if ("holdings".equals(key)) rearm(c);
        return item;
    }

    static boolean remove(Context c, String key, String id) {
        synchronized (LOCK) { return removeLocked(c, key, id); }
    }

    private static boolean removeLocked(Context c, String key, String id) {
        JSONArray a = arr(c, key), out = new JSONArray();
        boolean gone = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("id").equals(id)) { gone = true; continue; }
            if (o != null) out.put(o);
        }
        put(c, key, out);
        return gone;
    }

    /** One key in one of his small maps (loan rates by debt id, subscription marks, his category fixes by payee); null removes it. */
    static void mapPut(Context c, String key, String k, Object v) {
        if (k == null || k.isEmpty() || k.length() > 80) return;
        synchronized (LOCK) {
            JSONObject m = map(c, key);
            try {
                if (v == null) m.remove(k);
                else if (m.length() < 300 || m.has(k)) m.put(k, v);
            } catch (Exception ignored) {}
            put(c, key, m);
        }
    }

    private static String txt(JSONObject o, String k, int max) {
        String v = o.optString(k, "").replaceAll("[\\p{Cntrl}]", " ").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static double num(JSONObject o, String k, double max) {
        double v = o.optDouble(k, 0);
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.max(0, Math.min(max, v));
    }

    static final String[] GOAL_KINDS = {"house", "car", "bike", "education", "wedding", "retire", "travel", "emergency", "gadget", "other"};
    static final String[] HOLDING_KINDS = {"fd", "rd", "ppf", "epf", "nps", "post", "ssy", "mf", "stock", "gold", "lic", "chit", "land", "savings", "other"};

    private static String oneOf(String v, String[] ok, String def) { for (String k : ok) if (k.equals(v)) return v; return def; }

    /** A goal from the screen or the voice tool: only its own fields, numbers kept sane. */
    static JSONObject cleanGoal(JSONObject o) throws Exception {
        JSONObject g = new JSONObject();
        String id = txt(o, "id", 40);
        if (!id.isEmpty()) g.put("id", id);
        g.put("name", txt(o, "name", 60));
        g.put("kind", oneOf(o.optString("kind"), GOAL_KINDS, "other"));
        String by = txt(o, "by", 7);
        g.put("by", by.matches("20\\d\\d-(0[1-9]|1[0-2])") ? by : "");
        g.put("target", num(o, "target", 1e10));
        g.put("saved", num(o, "saved", 1e10));
        return g;
    }

    /** One of his investments from the screen or the voice tool. */
    static JSONObject cleanHolding(JSONObject o) throws Exception {
        JSONObject h = new JSONObject();
        String id = txt(o, "id", 40);
        if (!id.isEmpty()) h.put("id", id);
        String kind = oneOf(o.optString("kind"), HOLDING_KINDS, "other");
        h.put("kind", kind).put("name", txt(o, "name", 80));
        String code = txt(o, "code", 24).toUpperCase(Locale.ROOT);
        h.put("code", "mf".equals(kind) ? (code.matches("\\d{3,8}") ? code : "") : "stock".equals(kind) ? (code.matches("[A-Z0-9&\\-]{1,20}(\\.(NS|BO))?") ? code : "") : "");
        for (String k : new String[]{"start", "maturity"}) { String d = txt(o, k, 10); h.put(k, d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : ""); }
        h.put("goal", txt(o, "goal", 40));
        h.put("invested", num(o, "invested", 1e10)).put("value", num(o, "value", 1e10)).put("units", num(o, "units", 1e9)).put("rate", num(o, "rate", 30))
                .put("sip", num(o, "sip", 1e8)).put("sip_day", (int) num(o, "sip_day", 31)).put("emergency", o.optBoolean("emergency", false));
        if (o.has("price")) h.put("price", num(o, "price", 1e8)); // (typed by him when the price could not be fetched)
        return h;
    }

    static List<FinCoach.Goal> goals(Context c) {
        List<FinCoach.Goal> l = new ArrayList<>();
        JSONArray a = arr(c, "goals");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            FinCoach.Goal g = new FinCoach.Goal();
            g.id = o.optString("id");
            g.name = o.optString("name");
            g.kind = o.optString("kind", "other");
            g.by = o.optString("by");
            g.target = o.optDouble("target", 0);
            g.saved = o.optDouble("saved", 0);
            l.add(g);
        }
        return l;
    }

    static List<FinCoach.Holding> holdings(Context c) {
        List<FinCoach.Holding> l = new ArrayList<>();
        JSONArray a = arr(c, "holdings");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            FinCoach.Holding h = new FinCoach.Holding();
            h.id = o.optString("id");
            h.kind = o.optString("kind", "other");
            h.name = o.optString("name");
            h.code = o.optString("code");
            h.start = o.optString("start");
            h.maturity = o.optString("maturity");
            h.goal = o.optString("goal");
            h.invested = o.optDouble("invested", 0);
            h.value = o.optDouble("value", 0);
            h.units = o.optDouble("units", 0);
            h.price = o.optDouble("price", 0);
            h.priceAt = o.optLong("price_at", 0);
            h.rate = o.optDouble("rate", 0);
            h.sip = o.optDouble("sip", 0);
            h.sipDay = o.optInt("sip_day", 0);
            h.emergency = o.optBoolean("emergency", false);
            l.add(h);
        }
        return l;
    }

    /** His EMIs and money borrowed (from 🏦 debts) with the interest rate he gave here. */
    static List<FinCoach.Loan> loans(Context c) {
        List<FinCoach.Loan> l = new ArrayList<>();
        JSONObject rates = map(c, "loan_rates");
        for (JSONObject o : Debts.open(c)) {
            String k = o.optString("kind");
            if (!k.equals("emi") && !k.equals("borrowed")) continue;
            FinCoach.Loan x = new FinCoach.Loan();
            x.id = o.optString("id");
            x.name = o.optString("name");
            x.kind = k;
            x.rate = rates.optDouble(x.id, 0);
            if (k.equals("emi")) {
                x.emi = o.optDouble("amount");
                int months = o.optInt("months");
                x.monthsLeft = months > 0 ? Math.max(0, months - o.optInt("done_months")) : 0;
                if (x.monthsLeft > 0 && x.rate > 0) {
                    double i = x.rate / 1200.0;
                    x.outstanding = x.emi * (1 - Math.pow(1 + i, -x.monthsLeft)) / i;
                } else x.outstanding = x.emi * x.monthsLeft;
            } else {
                x.outstanding = Debts.left(o);
            }
            l.add(x);
        }
        return l;
    }

    static double lentOut(Context c) {
        double s = 0;
        for (JSONObject o : Debts.open(c)) if ("lent".equals(o.optString("kind"))) s += Debts.left(o);
        return s;
    }

    // ================================================================ his money movements

    static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Bank / UPI / card SMS of the last {@code days} days (counted here only) and the expenses he wrote himself. */
    static List<FinTxn> txns(Context c, int days) {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        JSONObject fixes = map(c, "payee_cats");
        List<FinTxn> sms = new ArrayList<>();
        for (String[] m : Life.sms(c, since, 4000)) {
            long t;
            try { t = Long.parseLong(m[2]); } catch (Exception e) { continue; }
            FinTxn x = FinTxn.parse(m[0], m[1], t);
            if (x == null) continue;
            String fix = fixes.optString(x.payee, "");
            if (!fix.isEmpty() && !x.credit) x.cat = fix; // (his own correction for this payee)
            sms.add(x);
        }
        List<FinTxn> manual = new ArrayList<>();
        for (JSONObject e : Money.expenses(c)) {
            long t = e.optLong("t");
            if (t < since) continue;
            FinTxn x = new FinTxn();
            x.t = t;
            x.amt = e.optDouble("amount");
            x.src = "manual";
            x.channel = "manual";
            x.payee = FinTxn.clean(e.optString("shop", e.optString("what", "")));
            x.note = e.optString("what", "");
            x.cat = FinTxn.fromExpense(e.optString("what"), e.optString("category"));
            if (x.amt > 0) manual.add(x);
        }
        return FinCoach.merge(sms, manual);
    }

    /** The newest balance each of his accounts showed in an SMS (bank + last 4 digits; never shown or sent with the digits). */
    static Map<String, Double> balances(List<FinTxn> l) {
        Map<String, Double> b = new LinkedHashMap<>();
        for (FinTxn x : l) { // (newest first)
            if (x.bal < 0 || !"sms".equals(x.src)) continue;
            String k = x.bank + (x.acct.isEmpty() ? "" : "·" + x.acct);
            if (!b.containsKey(k)) b.put(k, x.bal);
        }
        return b;
    }

    static double monthlySpend(List<FinTxn> l, LocalDate today) {
        return FinCoach.avgSpend(l, YearMonth.from(today), zone(), today);
    }

    static double sipTotal(List<FinCoach.Holding> hs) {
        double s = 0;
        for (FinCoach.Holding h : hs) s += Math.max(0, h.sip);
        return s;
    }

    // ================================================================ everything the 💰 screen shows

    /** One JSON text for the screen (ready-made with Jw; no SMS text, no account digits). */
    static String bundle(Context c) throws Exception {
        LocalDate today = LocalDate.now(zone());
        boolean sms = canRead(c);
        List<FinTxn> all = txns(c, 130); // (without the SMS permission: only what he wrote himself)
        FinCoach.Profile p = profile(c);
        List<FinCoach.Goal> goals = goals(c);
        List<FinCoach.Holding> hs = holdings(c);
        List<FinCoach.Loan> loans = loans(c);
        Map<String, Double> bal = balances(all);
        double spend = monthlySpend(all, today), liquid = FinCoach.liquid(bal, hs), card = sp(c).getLong("card_due", 0);
        YearMonth m = YearMonth.from(today);
        Jw months = Jw.arr();
        for (int k = 0; k < 4; k++) months.add(FinCoach.month(all, m.minusMonths(k), p, zone(), today));
        Map<String, String> marks = new HashMap<>();
        JSONObject mk = map(c, "sub_marks");
        for (java.util.Iterator<String> it = mk.keys(); it.hasNext(); ) { String k = it.next(); marks.put(k, mk.optString(k)); }
        Jw bl = Jw.arr();
        for (Map.Entry<String, Double> e : bal.entrySet()) bl.add(Jw.obj().put("bank", e.getKey().split("·")[0]).put("amount", Math.round(e.getValue())));
        Jw ln = Jw.arr();
        for (FinCoach.Loan l : loans)
            ln.add(Jw.obj().put("id", l.id).put("name", l.name).put("kind", l.kind).put("emi", Math.round(l.emi)).put("months_left", l.monthsLeft)
                    .put("outstanding", Math.round(l.outstanding)).put("rate", l.rate));
        Jw recent = Jw.arr();
        int n = 0;
        for (FinTxn x : all) {
            if (n++ >= 60) break;
            recent.add(Jw.obj().put("t", x.t).put("amt", Math.round(x.amt)).put("credit", x.credit).put("payee", x.payee).put("cat", x.cat)
                    .put("te", FinTxn.catTe(x.cat)).put("channel", x.channel).put("bank", x.bank).put("src", x.src).put("note", x.note));
        }
        Jw cats = Jw.arr();
        for (String k : FinTxn.CATS) cats.add(Jw.obj().put("id", k).put("te", FinTxn.catTe(k)));
        JSONArray rv = arr(c, "reviews");
        return Jw.obj().put("today", today.toString()).put("sms", sms).raw("profile", profileJson(c).toString()).put("months", months)
                .put("allowance", FinCoach.allowance(all, p, zone(), today)).put("leaks", FinCoach.leaks(all, p, zone(), today))
                .put("subs", FinCoach.subsJson(FinCoach.subscriptions(all, zone(), today), marks)).put("balances", bl)
                .put("protection", FinCoach.protection(p, liquid, spend, loans, card))
                .put("priorities", FinCoach.priorities(p, liquid, spend, loans, card, goals, sipTotal(hs)))
                .put("goals", FinCoach.goals(goals, hs, p, today)).raw("goals_raw", arr(c, "goals").toString())
                .put("holdings", FinCoach.holdings(hs, today)).raw("holdings_raw", arr(c, "holdings").toString())
                .put("loans", ln).put("net", FinCoach.netWorth(bal, hs, loans, card, lentOut(c)))
                .put("guide", FinCoach.guide()).put("rates", FinCoach.rates()).put("recent", recent).put("cats", cats)
                .put("monthly_spend", Math.round(spend)).put("liquid", Math.round(liquid)).put("sip_total", Math.round(sipTotal(hs)))
                .raw("reviews", rv.toString()).put("prices_at", sp(c).getLong("prices_at", 0))
                .done();
    }

    // ================================================================ the evening check (and the 1st of the month)

    /** The evening check starts once he has opened 💰 (or asked Jarvis about his money): no surprise voice on the day of the update. */
    static void started(Context c) {
        if (sp(c).getBoolean("on", false)) return;
        sp(c).edit().putBoolean("on", true).putLong("checked", System.currentTimeMillis()).apply();
        rearm(c);
    }

    static void rearm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 481, new Intent(c, AlarmReceiver.class).setAction(ACTION_DAILY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!sp(c).getBoolean("on", false) || !Workshop.here(c)) return; // (phone only)
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, 21);
        k.set(Calendar.MINUTE, 5);
        k.set(Calendar.SECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 60_000) k.add(Calendar.DAY_OF_MONTH, 1);
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, k.getTimeInMillis(), pi); } catch (Exception ignored) {}
    }

    /** Once an evening: what needs his eye today. Nothing is paid, moved or cancelled: he only gets told. */
    static void daily(Context c) {
        if (!sp(c).getBoolean("on", false) || !Workshop.here(c)) return;
        try {
            SharedPreferences s = sp(c);
            LocalDate today = LocalDate.now(zone());
            FinCoach.Profile p = profile(c);
            List<FinTxn> all = txns(c, 100);
            long last = s.getLong("checked", System.currentTimeMillis() - 86_400_000L);
            List<String> lines = new ArrayList<>();
            if (s.getBoolean("alert_unusual", true) && canRead(c)) {
                for (FinTxn x : FinCoach.unusual(all, last, zone())) {
                    String key = x.t + ":" + Math.round(x.amt);
                    if (seen(c, "seen_unusual", key)) continue;
                    tell(c, 482 + (int) (x.t % 50), "⚠️ పెద్ద డెబిట్: ఇది మీరేనా?", FinCoach.unusualText(x, zone()), true);
                }
            }
            if (s.getBoolean("alert_salary", true)) {
                FinTxn sal = FinCoach.salary(all, p, YearMonth.from(today), zone());
                if (sal != null && !YearMonth.from(today).toString().equals(s.getString("salary_told", "")) && sal.t >= last - 3 * 86_400_000L) {
                    s.edit().putString("salary_told", YearMonth.from(today).toString()).apply();
                    tell(c, 490, "💰 జీతం వచ్చింది", FinCoach.salaryText(sal, p), true);
                }
            }
            if (p.lowBal > 0) {
                for (Map.Entry<String, Double> e : balances(all).entrySet()) {
                    if (e.getValue() >= p.lowBal) continue;
                    String key = today + e.getKey();
                    if (seen(c, "seen_low", key)) continue;
                    lines.add("🔻 " + e.getKey().split("·")[0] + " ఖాతాలో " + FinMath.rs(e.getValue()) + " మాత్రమే (మీ హద్దు " + FinMath.rs(p.lowBal) + ").");
                }
            }
            if (s.getBoolean("alert_subs", true)) {
                JSONObject marks = map(c, "sub_marks");
                for (FinCoach.Sub sub : FinCoach.subscriptions(all, zone(), today)) {
                    LocalDate next = nextDay(today, sub.day);
                    long days = java.time.temporal.ChronoUnit.DAYS.between(today, next);
                    if (days != 2) continue;
                    String mark = marks.optString(sub.name);
                    if ("keep".equals(mark)) continue;
                    lines.add("🔁 " + sub.name + " " + FinMath.rs(sub.amount) + " ఎల్లుండి మళ్ళీ కట్ అవుతుంది" + ("cancel".equals(mark) ? " (మీరు ఆపాలనుకున్నారు: ఈరోజే ఆ యాప్‌లో ఆపండి)." : ". వాడుతున్నారా?"));
                }
            }
            if (s.getBoolean("alert_maturity", true)) {
                for (FinCoach.Holding h : holdings(c)) {
                    if (h.maturity.isEmpty()) continue;
                    try {
                        long d = java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(h.maturity));
                        if (d == 7 || d == 1) lines.add("📅 " + FinCoach.kindTe(h.kind) + " " + h.name + " " + (d == 1 ? "రేపు" : "7 రోజుల్లో") + " మెచ్యూర్ అవుతుంది: ఆ డబ్బు ఏ లక్ష్యానికో ముందే ఆలోచించండి.");
                    } catch (Exception ignored) {}
                }
                for (FinCoach.Holding h : holdings(c)) {
                    if (h.sip > 0 && h.sipDay > 0 && nextDay(today, h.sipDay).equals(today.plusDays(1)))
                        lines.add("📈 రేపు " + h.name + " SIP " + FinMath.rs(h.sip) + ": ఖాతాలో డబ్బు ఉందా చూడండి (బౌన్స్ అయితే ఫైన్).");
                }
            }
            if (!lines.isEmpty()) tell(c, 491, "💰 ఈరోజు డబ్బు సంగతులు", android.text.TextUtils.join("\n", lines), false);
            // the 1st: last month's report (and the AI's review only when he switched it on)
            if (today.getDayOfMonth() == 1 && !YearMonth.from(today).toString().equals(s.getString("report_told", ""))) {
                s.edit().putString("report_told", YearMonth.from(today).toString()).apply();
                YearMonth lastM = YearMonth.from(today).minusMonths(1);
                if (s.getBoolean("month_report", true)) {
                    try {
                        Coder.Made m = MoneyReport.pdf(c, lastM);
                        if (m != null) tell(c, 492, "📄 " + FinCoach.monthTe(lastM) + " డబ్బు రిపోర్ట్", m.where + " లో ఉంది. 💰 స్క్రీన్‌లో కూడా చూడొచ్చు.", false);
                    } catch (Exception ignored) {}
                }
                if (s.getBoolean("ai_monthly", false)) {
                    try {
                        String r = aiReview(c);
                        if (r != null) tell(c, 493, "🤖 " + FinCoach.monthTe(lastM) + " AI సమీక్ష", r, false);
                    } catch (Exception ignored) {}
                }
            }
            s.edit().putLong("checked", System.currentTimeMillis()).apply();
            try { refreshPrices(c, false); } catch (Exception ignored) {}
        } finally {
            rearm(c);
        }
    }

    static LocalDate nextDay(LocalDate today, int day) {
        YearMonth m = YearMonth.from(today);
        LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
        if (d.isBefore(today)) {
            YearMonth n = m.plusMonths(1);
            d = n.atDay(Math.min(day, n.lengthOfMonth()));
        }
        return d;
    }

    private static boolean seen(Context c, String key, String id) {
        JSONArray a = arr(c, key);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
        a.put(id);
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, a.length() - 80); i < a.length(); i++) keep.put(a.optString(i));
        put(c, key, keep);
        return false;
    }

    static void tell(Context c, int id, String title, String text, boolean sayIt) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_money", "💰 డబ్బు సలహాదారు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(c, 480, new Intent(c, MoneyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(id, new Notification.Builder(c, "jarvis_money").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
                    .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) {}
        if (sayIt && !DutyMode.on(c)) Announcer.say(c, text);
    }

    // ================================================================ prices of his own funds and shares

    /** NAVs from AMFI's daily file for his funds (by scheme code) and share prices for his shares; at most once in 6 hours unless he asks. */
    static int refreshPrices(Context c, boolean now) throws Exception {
        SharedPreferences s = sp(c);
        if (!now && System.currentTimeMillis() - s.getLong("prices_at", 0) < 6 * 3_600_000L) return 0;
        JSONArray a = arr(c, "holdings");
        Map<String, Double> nav = null, got = new HashMap<>();
        for (int i = 0; i < a.length(); i++) { // (the network first, without holding his lists)
            JSONObject h = a.optJSONObject(i);
            if (h == null) continue;
            String code = h.optString("code").trim(), kind = h.optString("kind");
            if (code.isEmpty() || got.containsKey(kind + ":" + code)) continue;
            double price = -1;
            if ("mf".equals(kind)) {
                if (nav == null) nav = navs(c, true);
                Double v = nav.get(code);
                if (v != null) price = v;
            } else if ("stock".equals(kind)) {
                price = sharePrice(code);
            }
            if (price > 0) got.put(kind + ":" + code, price);
        }
        int n = 0;
        synchronized (LOCK) { // (then the prices go into his list as it is now)
            JSONArray b = arr(c, "holdings");
            long at = System.currentTimeMillis();
            for (int i = 0; i < b.length(); i++) {
                JSONObject h = b.optJSONObject(i);
                if (h == null) continue;
                Double v = got.get(h.optString("kind") + ":" + h.optString("code").trim());
                if (v == null) continue;
                h.put("price", v).put("price_at", at);
                n++;
            }
            put(c, "holdings", b);
        }
        s.edit().putLong("prices_at", System.currentTimeMillis()).apply();
        return n;
    }

    private static File navFile(Context c) { return new File(c.getFilesDir(), "amfi_nav.txt"); }

    /** AMFI's daily NAV file (public; fetched at most once in 12 hours) → scheme code → NAV. */
    static Map<String, Double> navs(Context c, boolean fetch) throws Exception {
        File f = navFile(c);
        if (fetch && (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3_600_000L)) {
            HttpURLConnection h = (HttpURLConnection) new URL(AMFI).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "Jarvis");
            File part = new File(c.getFilesDir(), "amfi_nav.txt.part");
            try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] b = new byte[65536];
                int r;
                long total = 0;
                while ((r = in.read(b)) > 0) {
                    out.write(b, 0, r);
                    total += r;
                    if (total > 20_000_000) throw new IllegalStateException("too big");
                }
            } finally {
                h.disconnect();
            }
            if (!part.renameTo(f)) throw new IllegalStateException("save");
        }
        Map<String, Double> m = new HashMap<>();
        if (!f.exists()) return m;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(";");
                if (p.length < 6 || !p[0].trim().matches("\\d+")) continue;
                try { m.put(p[0].trim(), Double.parseDouble(p[4].trim())); } catch (Exception ignored) {}
            }
        }
        return m;
    }

    /** His fund by name: up to 12 {code, name, NAV} from AMFI's list. */
    static JSONArray searchFunds(Context c, String q) throws Exception {
        navs(c, true);
        JSONArray out = new JSONArray();
        String[] words = q.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(navFile(c)), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null && out.length() < 12) {
                String[] p = l.split(";");
                if (p.length < 6 || !p[0].trim().matches("\\d+")) continue;
                String name = p[3].toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String w : words) if (!name.contains(w)) { all = false; break; }
                if (all) out.put(new JSONObject().put("code", p[0].trim()).put("name", p[3].trim()).put("nav", p[4].trim()).put("date", p[5].trim()));
            }
        }
        return out;
    }

    /** A listed share's last price (NSE symbol like "TCS"; "TCS.BO" for BSE). Unofficial public chart data: may fail, then he types it. */
    static double sharePrice(String symbol) {
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (!s.matches("[A-Z0-9&\\-]{1,20}(\\.(NS|BO))?")) return -1;
        if (!s.contains(".")) s = s + ".NS";
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(s, "UTF-8")
                    + "?range=1d&interval=1d").openConnection();
            h.setConnectTimeout(12000);
            h.setReadTimeout(15000);
            h.setRequestProperty("User-Agent", "Mozilla/5.0");
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null && b.length() < 200_000) b.append(l);
            } finally {
                h.disconnect();
            }
            JSONObject meta = new JSONObject(b.toString()).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
            double p = meta.optDouble("regularMarketPrice", -1);
            return p > 0 ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ================================================================ sums he asks for (the screen and the voice tool share these)

    private static double d(JSONObject a, String k, double def) {
        double v = a.optDouble(k, def);
        return Double.isNaN(v) ? def : v;
    }

    /** One sum: {"kind": sip | lumpsum | fd | rd | ppf | emi | prepay | inflation | retire | goal | card | double | tax | canbuy | scheme, …}. */
    static String calc(Context c, JSONObject a) throws Exception {
        String k = a.optString("kind");
        String rs;
        switch (k) {
            case "sip": {
                double m = d(a, "monthly", 0), r = d(a, "rate", 10), y = d(a, "years", 10);
                double fv = FinMath.sip(m, r, y), put = m * Math.round(y * 12);
                return Jw.obj().put("kind", k).put("value", Math.round(fv)).put("put", Math.round(put))
                        .put("text", "నెలకి " + FinMath.rs(m) + " × " + FinCoach.trim(y) + " ఏళ్ళు (ఏడాదికి " + FinCoach.trim(r) + "% అనుకుంటే): మీరు పెట్టేది " + FinMath.rs(put)
                                + " → సుమారు " + FinMath.rs(fv) + " (" + FinMath.words(fv) + "). ఈరోజు విలువలో (6% ధరల పెరుగుదల): " + FinMath.rs(FinMath.todayValue(fv, 6, y))
                                + ". ఇది అంచనా: షేర్ ఫండ్‌లలో రాబడి ఏటా మారుతుంది, హామీ కాదు.").done();
            }
            case "lumpsum": {
                double m = d(a, "amount", 0), r = d(a, "rate", 7), y = d(a, "years", 5);
                double fv = FinMath.lumpsum(m, r, y);
                return Jw.obj().put("kind", k).put("value", Math.round(fv)).put("text", FinMath.rs(m) + " ఒకేసారి, " + FinCoach.trim(y) + " ఏళ్ళు, ఏడాదికి " + FinCoach.trim(r)
                        + "%: సుమారు " + FinMath.rs(fv) + ". రెట్టింపు అవ్వడానికి సుమారు " + FinCoach.trim(FinMath.doubleYears(r)) + " ఏళ్ళు.").done();
            }
            case "fd": {
                double m = d(a, "amount", 0), r = d(a, "rate", 7), y = d(a, "years", 1);
                double fv = FinMath.fd(m, r, y);
                return Jw.obj().put("kind", k).put("value", Math.round(fv)).put("text", "FD " + FinMath.rs(m) + ", " + FinCoach.trim(r) + "%, " + FinCoach.trim(y)
                        + " ఏళ్ళు (వడ్డీ ప్రతి 3 నెలలకి కలిపితే): " + FinMath.rs(fv) + " (వడ్డీ " + FinMath.rs(fv - m) + "; ఇది మీ ఆదాయంలో కలిసి పన్ను పడొచ్చు).").done();
            }
            case "rd": {
                double m = d(a, "monthly", 0), r = d(a, "rate", FinMath.rate("rd5"));
                int n = (int) d(a, "months", 60);
                double fv = FinMath.rd(m, r, n);
                return Jw.obj().put("kind", k).put("value", Math.round(fv)).put("text", "RD నెలకి " + FinMath.rs(m) + " × " + n + " నెలలు, " + FinCoach.trim(r) + "%: మీరు కట్టేది "
                        + FinMath.rs(m * n) + " → సుమారు " + FinMath.rs(fv) + ".").done();
            }
            case "ppf": {
                double m = d(a, "yearly", 150000), r = d(a, "rate", FinMath.rate("ppf"));
                int y = (int) d(a, "years", 15);
                double fv = FinMath.ppf(Math.min(150000, m), r, y);
                return Jw.obj().put("kind", k).put("value", Math.round(fv)).put("text", "PPF ఏడాదికి " + FinMath.rs(Math.min(150000, m)) + " × " + y + " ఏళ్ళు, " + FinCoach.trim(r)
                        + "% (" + FinMath.RATES_DATE + " రేటు; ప్రభుత్వం మార్చొచ్చు): సుమారు " + FinMath.rs(fv) + ", పన్ను లేదు. ఏప్రిల్ 5 లోపు కడితే ఆ ఏడాది వడ్డీ పూర్తి.").done();
            }
            case "emi": {
                double pr = d(a, "principal", 0), r = d(a, "rate", 10);
                int n = (int) d(a, "months", 60);
                double e = FinMath.emi(pr, r, n);
                return Jw.obj().put("kind", k).put("emi", Math.round(e)).put("text", FinMath.rs(pr) + " లోన్, " + FinCoach.trim(r) + "%, " + n + " నెలలు: EMI " + FinMath.rs(e)
                        + ". మొత్తం కట్టేది " + FinMath.rs(e * n) + " (వడ్డీ " + FinMath.rs(e * n - pr) + ").").done();
            }
            case "prepay": {
                double o = d(a, "outstanding", 0), r = d(a, "rate", 10), e = d(a, "emi", 0), x1 = d(a, "extra_now", 0), x2 = d(a, "extra_monthly", 0);
                if (e <= 0 || o <= 0) return Jw.obj().put("kind", k).put("text", "మిగిలిన లోన్, వడ్డీ %, EMI మూడూ ఇవ్వండి.").done();
                double[] q = FinMath.prepay(o, r, e, x1, x2);
                if (q[0] >= 1200) return Jw.obj().put("kind", k).put("text", "ఈ EMI వడ్డీకి కూడా సరిపోదు: అంకెలు మళ్ళీ చూడండి.").done();
                return Jw.obj().put("kind", k).put("months_saved", Math.round(q[0] - q[1])).put("interest_saved", Math.round(q[2] - q[3]))
                        .put("text", (x1 > 0 ? "ఇప్పుడు " + FinMath.rs(x1) + " " : "") + (x2 > 0 ? (x1 > 0 ? "+ " : "") + "నెలకి అదనంగా " + FinMath.rs(x2) + " " : "")
                                + "కడితే: లోన్ " + (long) (q[0] - q[1]) + " నెలలు ముందే అయిపోతుంది, వడ్డీ " + FinMath.rs(q[2] - q[3]) + " మిగులుతుంది. "
                                + "(బ్యాంక్‌కి \"EMI కాదు, కాలం తగ్గించండి\" అని చెప్పండి; ఫ్లోటింగ్ రేటు హోమ్ లోన్‌కి ముందే కట్టడానికి ఫీ ఉండదు.)").done();
            }
            case "inflation": {
                double m = d(a, "amount", 0), y = d(a, "years", 10), r = d(a, "rate", FinCoach.INFLATION);
                return Jw.obj().put("kind", k).put("text", "ఈరోజు " + FinMath.rs(m) + " అయ్యేది " + FinCoach.trim(y) + " ఏళ్ళ తర్వాత సుమారు " + FinMath.rs(FinMath.future(m, r, y))
                        + " అవుతుంది (ధరలు ఏడాదికి " + FinCoach.trim(r) + "% పెరిగితే). అదే " + FinMath.rs(m) + " అప్పుడు ఈరోజు " + FinMath.rs(FinMath.todayValue(m, r, y)) + " విలువ మాత్రమే.").done();
            }
            case "retire": {
                FinCoach.Profile p = profile(c);
                int age = (int) d(a, "age", p.age > 0 ? p.age : 35), ra = (int) d(a, "retire_age", 60);
                double ex = d(a, "monthly_expense", 0), inf = d(a, "inflation", FinCoach.INFLATION), r = d(a, "rate", 10), have = d(a, "have", 0);
                if (ex <= 0 || ra <= age) return Jw.obj().put("kind", k).put("text", "ఇప్పటి నెల ఖర్చు, వయసు, రిటైర్ అయ్యే వయసు ఇవ్వండి.").done();
                double[] q = FinMath.retirement(age, ra, ex, inf, r, have);
                return Jw.obj().put("kind", k).put("corpus", Math.round(q[2])).put("monthly", Math.round(q[3]))
                        .put("text", (long) q[0] + " ఏళ్ళ తర్వాత నెల ఖర్చు సుమారు " + FinMath.rs(q[1]) + " అవుతుంది. దానికి సుమారు " + FinMath.words(q[2]) + " కావాలి (ఏడాది ఖర్చుకి 25 రెట్లు: "
                                + "సాధారణ లెక్క). ఇప్పటి నుంచి నెలకి సుమారు " + FinMath.rs(q[3]) + " (ఏడాదికి " + FinCoach.trim(r) + "% అనుకుంటే; EPF / PPF / NPS కూడా ఇందులో లెక్కే). అంచనా మాత్రమే.").done();
            }
            case "goal": {
                FinCoach.Profile p = profile(c);
                double t = d(a, "target", 0), y = Math.max(0.25, d(a, "years", 5)), have = d(a, "have", 0);
                double r = a.has("rate") ? d(a, "rate", 8) : FinCoach.assumed(y, p.risk);
                double fut = FinMath.future(t, FinCoach.INFLATION, y), m = FinMath.goalMonthly(fut, have, r, y);
                String fit = FinCoach.fitTe(y, p.risk);
                return Jw.obj().put("kind", k).put("monthly", Math.round(m)).put("future", Math.round(fut))
                        .put("text", "ఈరోజు " + FinMath.rs(t) + " అయ్యేది " + FinCoach.trim(y) + " ఏళ్ళలో సుమారు " + FinMath.rs(fut) + ". నెలకి సుమారు " + FinMath.rs(m) + " పెట్టాలి (ఏడాదికి "
                                + FinCoach.trim(r) + "% అనుకుంటే). ఇంత కాలానికి సాధారణంగా సరిపోయే రకాలు: " + fit + ". (రకాలు మాత్రమే; ఏ ఫండ్ / కంపెనీ అనేది మీ నిర్ణయం.)").done();
            }
            case "card": {
                double b = d(a, "balance", 0), r = d(a, "monthly_rate", sp(c).getFloat("card_rate", 3.5f));
                int n = (int) d(a, "months", 12);
                double i = FinMath.cardInterest(b, r, n);
                return Jw.obj().put("kind", k).put("interest", Math.round(i)).put("text", "కార్డ్ బాకీ " + FinMath.rs(b) + " కట్టకుండా " + n + " నెలలు ఉంటే వడ్డీ సుమారు " + FinMath.rs(i)
                        + " (నెలకి " + FinCoach.trim(r) + "%, పైన GST, లేట్ ఫీ వేరు). మినిమం కాదు, మొత్తం కట్టండి; చేతిలో లేకపోతే తక్కువ వడ్డీ లోన్‌తో మార్చడం గురించి బ్యాంక్‌ని అడగండి.").done();
            }
            case "double": {
                double r = d(a, "rate", 7);
                return Jw.obj().put("kind", k).put("text", "ఏడాదికి " + FinCoach.trim(r) + "% తో డబ్బు రెట్టింపు అవ్వడానికి సుమారు " + FinCoach.trim(FinMath.doubleYears(r)) + " ఏళ్ళు. "
                        + "\"1–2 ఏళ్ళలో రెట్టింపు\" అంటే ఏడాదికి 40–100%: అలాంటి హామీలు మోసం.").done();
            }
            case "tax": {
                FinCoach.Profile p = profile(c);
                double inc = d(a, "income", p.income * 12);
                FinMath.Tax t = FinMath.tax(inc, a.optBoolean("salaried", true), d(a, "d80c", 0), d(a, "d80d", 0), d(a, "nps", 0), d(a, "home_interest", 0),
                        d(a, "hra", 0), d(a, "other", 0), (int) d(a, "age", p.age));
                String lower = t.newTax < t.oldTax ? "కొత్త విధానం" : t.newTax > t.oldTax ? "పాత విధానం" : "రెండూ ఒకటే";
                return Jw.obj().put("kind", k).put("new_tax", Math.round(t.newTax)).put("old_tax", Math.round(t.oldTax)).put("new_taxable", Math.round(t.newTaxable))
                        .put("old_taxable", Math.round(t.oldTaxable))
                        .put("text", "ఏడాది ఆదాయం " + FinMath.rs(inc) + " (టాక్స్ ఇయర్ 2026-27):\n• కొత్త విధానం: పన్ను పడే ఆదాయం " + FinMath.rs(t.newTaxable) + " → పన్ను " + FinMath.rs(t.newTax)
                                + "\n• పాత విధానం (మీరు ఇచ్చిన మినహాయింపులతో): " + FinMath.rs(t.oldTaxable) + " → పన్ను " + FinMath.rs(t.oldTax)
                                + "\nతక్కువ పన్ను: " + lower + (t.newTax != t.oldTax ? " (" + FinMath.rs(Math.abs(t.newTax - t.oldTax)) + " తేడా)" : "")
                                + ".\n(4% సెస్‌తో; ₹50 లక్షల పైన సర్‌చార్జ్ లెక్కలో లేదు. కొత్త చట్టంలో సెక్షన్ నంబర్లు మారాయి; ITR గడువు జీతగాళ్ళకి జూలై 31. "
                                + "అంచనా మాత్రమే: ఫారం 16 / CA తో సరిచూసుకోండి.)").done();
            }
            case "canbuy": return canBuy(c, d(a, "price", 0), a.optString("what", ""));
            case "scheme": return FinCoach.schemeCheck(a.optString("text", "")).done();
            default: return Jw.obj().put("kind", k).put("text", "ఈ లెక్క తెలియదు.").done();
        }
    }

    static String canBuy(Context c, double price, String what) {
        LocalDate today = LocalDate.now(zone());
        List<FinTxn> all = txns(c, 130);
        FinCoach.Profile p = profile(c);
        List<FinCoach.Holding> hs = holdings(c);
        double spend = monthlySpend(all, today), liquid = FinCoach.liquid(balances(all), hs);
        String al = FinCoach.allowance(all, p, zone(), today).done();
        double left = -1;
        try { left = new JSONObject(al).optDouble("left", -1); } catch (Exception ignored) {}
        double capacity = p.income > 0 ? Math.max(0, p.income - spend - sipTotal(hs)) : 0;
        return FinCoach.canBuy(price, what, p, left, liquid, spend, sp(c).getLong("card_due", 0), capacity).done();
    }

    // ================================================================ the AI's review (on his tap / ask, or the 1st when he switched it on)

    /** What the AI is told: totals only (no SMS text, no account digits, no names of people). */
    static String summaryForAi(Context c) throws Exception {
        JSONObject b = new JSONObject(bundle(c));
        JSONObject o = new JSONObject();
        JSONObject p = b.getJSONObject("profile");
        o.put("profile", new JSONObject().put("monthly_income", p.optLong("income")).put("savings_goal", p.optLong("save_goal")).put("budget", p.optLong("budget"))
                .put("age", p.optInt("age")).put("dependents", p.optInt("dependents")).put("risk_comfort", p.optString("risk"))
                .put("term_cover", p.optLong("term")).put("health_cover", p.optLong("health")).put("card_due", p.optLong("card_due")));
        JSONArray months = b.optJSONArray("months"), mo = new JSONArray();
        for (int i = 0; months != null && i < months.length(); i++) {
            JSONObject m = months.getJSONObject(i);
            JSONArray cats = m.optJSONArray("cats"), cs = new JSONArray();
            for (int k = 0; cats != null && k < cats.length(); k++) {
                JSONObject x = cats.getJSONObject(k);
                if ("people".equals(x.optString("cat"))) continue; // (who he sends money to is his own business)
                cs.put(new JSONObject().put("kind", x.optString("cat")).put("amount", x.optLong("amount")).put("times", x.optInt("n")));
            }
            mo.put(new JSONObject().put("month", m.optString("month")).put("so_far", m.optBoolean("so_far")).put("came_in", m.optLong("came_in"))
                    .put("spent", m.optLong("spent")).put("invested", m.optLong("invested")).put("fixed", m.optLong("fixed")).put("by_kind", cs));
        }
        o.put("months", mo);
        JSONArray leaks = b.optJSONArray("leaks"), lk = new JSONArray();
        for (int i = 0; leaks != null && i < leaks.length(); i++) lk.put(leaks.getJSONObject(i).optString("title"));
        o.put("waste_found", lk);
        JSONArray subs = b.optJSONArray("subs"), sb = new JSONArray();
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject x = subs.getJSONObject(i);
            // (an app's name like an OTT goes; a regular payment to anyone else goes only as its kind: no person's name to the AI)
            sb.put((x.optBoolean("ott") ? x.optString("name") : "regular " + x.optString("cat")) + " " + x.optLong("amount") + "/month");
        }
        o.put("subscriptions", sb);
        JSONArray pr = b.optJSONArray("protection"), ps = new JSONArray();
        for (int i = 0; pr != null && i < pr.length(); i++) ps.put(pr.getJSONObject(i).optString("title") + ": " + pr.getJSONObject(i).optString("st") + " — " + pr.getJSONObject(i).optString("now"));
        o.put("safety_net", ps);
        JSONObject net = b.optJSONObject("net");
        if (net != null) o.put("net_worth", new JSONObject().put("bank", net.optLong("bank")).put("investments", net.optLong("invested")).put("owe", net.optLong("owe"))
                .put("lent", net.optLong("lent")).put("total", net.optLong("total")).put("by_kind", net.optJSONArray("by_kind")));
        JSONArray gl = b.optJSONArray("goals"), gs = new JSONArray();
        for (int i = 0; gl != null && i < gl.length(); i++) gs.put(gl.getJSONObject(i).optString("te"));
        o.put("goals", gs);
        o.put("monthly_sip_total", b.optLong("sip_total")).put("avg_monthly_spend", b.optLong("monthly_spend")).put("emergency_money", b.optLong("liquid"));
        o.put("rates_table", b.optJSONObject("rates"));
        return o.toString();
    }

    static final String REVIEW_RULES = "You are Jarvis, Anil's money helper (Telugu, simple words, short). Using ONLY the numbers given: "
            + "1) how this month went (in, out, saved) in 2 lines; 2) the 3 biggest wastes and exactly how much a month he can save from each; "
            + "3) his safety net in order (emergency fund, health cover, term cover, costly debt) — what to fix first; "
            + "4) for his goals, which KINDS of options fit the time left and his comfort with ups and downs (FD/RD/post office/PPF/index fund SIP/NPS…), "
            + "with their risk — explain, never name a fund, share or company, never tell him to buy or sell a specific thing, never promise returns; "
            + "5) one small step for this week. Rates only from the table given (say its date). End with one line: this is information for his own decision, "
            + "Jarvis is not a SEBI-registered adviser; for big decisions a fee-only SEBI-registered adviser (RIA) can help. Plain text, no markdown, under 220 words.";

    static String aiReview(Context c) throws Exception {
        Prefs p = new Prefs(c);
        if (!p.hasBrain()) return null;
        String r = Brain.oneShot(p, REVIEW_RULES, summaryForAi(c), null, false, 1400);
        if (r == null || r.trim().isEmpty()) return null;
        r = r.replace("**", "").replace("##", "").trim();
        JSONArray a = arr(c, "reviews"), out = new JSONArray();
        out.put(new JSONObject().put("t", System.currentTimeMillis()).put("text", r));
        for (int i = 0; i < Math.min(5, a.length()); i++) out.put(a.optJSONObject(i));
        put(c, "reviews", out);
        return r;
    }
}
