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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 🥛 The house accounts (milk) on his phone and on the home tablet (HouseBook holds the rules, HouseReport the page).
 * - His phone keeps the account: what he says ("ఈ రోజు పాలు రాలేదు", "పాల బిల్లు ఇచ్చాను"), and what అమ్మగారు tells the
 *   tablet, which comes over the home link (only the entry: numbers and dates, never her words). On the 1st (9 am - 1 pm)
 *   last month's bill is told, and sent to the tablet for her; on the 5th and the 10th a note if it is still not given.
 *   Money given goes into his month's expenses (category "house") when he wants.
 * - The tablet keeps her entries and his phone's copy (the items, the rates), answers her "బిల్లు ఎంత?", and says the
 *   month's bill to her at a good time (not at night, not while she rests, not when she is out or asked for quiet).
 * The two talk through his own Telegram bot like the rest of the home link: one pinned message at a time, never over a
 * word of his still on its way, each in one message (Telegram's 4,096 characters), the previous one deleted. A change
 * is sent once (counters, not clocks); her entries are sent again now and then until his phone's word shows it has them.
 */
final class House {
    private House() {}

    static final String PREFS = "jarvis_house";
    /** Switches (on unless he turns them off): the bill on the 1st (and the notes on the 5th / 10th), the tablet told, expenses. */
    static final String BILL_FIRST = "bill_first", TELL_AMMA = "tell_amma", TO_EXPENSE = "to_expense";
    static final String TO_HOME = "🥛 ఇంటి లెక్క (ఫోన్ నుంచి): ", FROM_HOME = "🥛 ఇంటి లెక్క (ఇంటి నుంచి): ";
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean on(Context c, String k) { return sp(c).getBoolean(k, true); }

    static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    private static boolean tablet(Context c) { return new Prefs(c).homeMode(); }

    private static String by(Context c) { return tablet(c) ? HouseBook.AMMA : HouseBook.ME; }

    static synchronized JSONObject load(Context c) { return HouseBook.parse(sp(c).getString("state", "")); }

    private static synchronized void save(Context c, JSONObject st) {
        try { HouseBook.prune(st, LocalDate.now()); } catch (Exception ignored) {}
        sp(c).edit().putString("state", st.toString()).apply();
    }

    /** Something the other device must hear of (a counter: a wrong clock never makes it send again and again). */
    private static synchronized void markDirty(Context c) { sp(c).edit().putLong("dseq", sp(c).getLong("dseq", 0) + 1).apply(); }

    private static boolean dirty(Context c) { return sp(c).getLong("dseq", 0) > sp(c).getLong("sseq", 0); }

    /** Saved as a change the other device must hear of; on the tablet it goes at once. */
    private static void changed(Context c, JSONObject st) {
        save(c, st);
        markDirty(c);
        if (tablet(c)) sendSoon(c);
    }

    /** Is there any account set (an item with its rate)? */
    static boolean isSet(Context c) { return !HouseBook.items(load(c)).isEmpty(); }

    // ================================================================ the tool (his phone, or the tablet)

    private static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }

    private static JSONObject err(String code, String detail) throws Exception { return new JSONObject().put("ok", false).put("error", code).put("detail", detail); }

    /** A number he gave, or NaN (left out / not a number). */
    private static double number(JSONObject a, String k) {
        if (!a.has(k) || a.isNull(k)) return Double.NaN;
        Object v = a.opt(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim().replace(',', '.')); } catch (Exception e) { return Double.NaN; }
    }

    /** His phone: the tablet's newest entries first (before a payment or a bill), when it is linked. Background thread. */
    static void freshen(Context c) {
        if (!tablet(c) && HomeLink.linked(c) && Net.online(c)) try { fetchFromHome(c); } catch (Throwable ignored) {}
    }

    /** house_accounts (all but report / share, which open a file: Tools). */
    static JSONObject tool(Context c, JSONObject a) throws Exception {
        String action = a.optString("action", "bill").trim().toLowerCase(Locale.ROOT);
        String key = HouseBook.key(a.optString("item"));
        LocalDate today = LocalDate.now();
        boolean tab = tablet(c);
        synchronized (House.class) {
            JSONObject st = load(c);
            JSONObject it = HouseBook.item(st, key);
            if (!HouseBook.known(st, key) && !action.equals("setup")) // (only an account his phone set, or milk: no other words are written)
                return err("not_found", "Only the milk account is kept" + (HouseBook.items(st).size() > 1 ? " (and the ones Anil set)" : "") + ". Use item milk.");
            switch (action) {
                case "setup": case "price": case "daily": case "remove": {
                    if (tab) return err("phone_only", "Only Anil sets the milk rate and the usual amount, from his phone. If she says the rate changed, "
                            + "offer to tell him (home_care tell_son).");
                    if (action.equals("remove")) {
                        if (it == null) return err("not_found", "No such account.");
                        if (!a.optBoolean("confirmed")) return err("confirm", "Ask him first: 'పాల లెక్క మొత్తం తీసేయమంటారా?' and call again with confirmed=true only on his yes.");
                        JSONArray items = new JSONArray();
                        for (JSONObject o : HouseBook.items(st)) if (!o.optString("key").equals(key)) items.put(o);
                        st.put("items", items);
                        changed(c, st);
                        return ok().put("removed", it.optString("name")).put("note", "The days and payments said stay, in case he sets it again.");
                    }
                    String fromS = a.optString("from").trim();
                    LocalDate from = fromS.isEmpty() ? null : HouseBook.date(fromS, today);
                    if (!fromS.isEmpty() && from == null) return err("bad_date", "from must be YYYY-MM-DD.");
                    double price = number(a, "price"), daily = number(a, "daily");
                    if (!Double.isNaN(price) && (price <= 0 || price > 100_000)) return err("bad_value", "That rate can't be right: ask him again (rupees per litre / packet).");
                    if (!Double.isNaN(daily) && (daily < 0 || daily > HouseBook.MAX_QTY)) return err("bad_value", "How much comes each day: 0 to 50.");
                    if (it == null) { // a new account
                        if (Double.isNaN(price)) return err("ask_setup", "Ask him once, in short Telugu: 'ఒక లీటర్ (లేదా ప్యాకెట్) ధర ఎంత? రోజూ ఎన్ని? "
                                + "ఈ నెల 1 నుంచే లెక్క వేయనా?' Then call setup with price, daily, unit and from.");
                        boolean guessed = from == null;
                        JSONObject done = HouseBook.setup(st, key, a.optString("name"), a.optString("unit"), price, Double.isNaN(daily) ? -1 : daily,
                                guessed ? today.withDayOfMonth(1) : from, a.has("vendor") ? a.optString("vendor") : null, false);
                        if (done == null) return err("ask_setup", "Need the price per unit.");
                        changed(c, st);
                        return ok().put("set", HouseBook.setupLine(done, today)).put("this_month", HouseBook.line(HouseBook.bill(st, key, YearMonth.from(today), today)))
                                .put("next", (guessed ? "Tell him the count starts from the 1st of this month (another start day later: setup from=<day> from_start=true). " : "")
                                        + "From now on he says only the days that were different ('ఈ రోజు పాలు రాలేదు', 'ఈ రోజు 2 లీటర్లు'); అమ్మగారు can tell the home tablet too. "
                                        + "On the 1st Jarvis tells last month's bill.");
                    }
                    if (a.optBoolean("from_start")) { // he said the start / the first rate was wrong from the beginning
                        HouseBook.setup(st, key, a.optString("name"), a.optString("unit"), Double.isNaN(price) ? 0 : price, Double.isNaN(daily) ? -1 : daily,
                                from, a.has("vendor") ? a.optString("vendor") : null, true);
                        changed(c, st);
                        return ok().put("set", HouseBook.setupLine(it, today)).put("this_month", HouseBook.line(HouseBook.bill(st, key, YearMonth.from(today), today)))
                                .put("note", "Changed from the start of the count: earlier months' bills change too.");
                    }
                    // an account already there: a new rate / usual amount from a day (today unless he said), the rest as said
                    HouseBook.setup(st, key, a.optString("name"), a.optString("unit"), 0, -1, null, a.has("vendor") ? a.optString("vendor") : null, false);
                    LocalDate when = from == null ? today : from;
                    boolean any = false;
                    if (!Double.isNaN(price)) any |= HouseBook.setPrice(st, key, price, when);
                    if (!Double.isNaN(daily)) any |= HouseBook.setDaily(st, key, daily, when);
                    if (!any && (action.equals("price") || action.equals("daily")))
                        return err("bad_value", action.equals("price") ? "Need the new price per unit (rupees)." : "Need how much comes each day (0-50).");
                    changed(c, st);
                    return ok().put("set", HouseBook.setupLine(it, when.isAfter(today) ? when : today)).put("from", when.toString())
                            .put("this_month", HouseBook.line(HouseBook.bill(st, key, YearMonth.from(today), today)))
                            .put("note", "Days before " + when + " keep the old rate / amount.");
                }
                case "day": case "usual": {
                    LocalDate from = HouseBook.date(a.optString("date"), today), to = a.optString("to").trim().isEmpty() ? from : HouseBook.date(a.optString("to"), today);
                    if (from == null || to == null) return err("bad_date", "date / to must be YYYY-MM-DD.");
                    if (to.isBefore(from)) return err("bad_date", "to is before date: say the first day as date and the last day as to.");
                    if (from.isBefore(today.minusDays(62)) || to.isAfter(today.plusDays(62))) return err("bad_date", "Only days within two months of today.");
                    if (from.plusDays(HouseBook.MAX_RANGE - 1).isBefore(to)) return err("bad_date", "At most " + HouseBook.MAX_RANGE + " days at once.");
                    long t = System.currentTimeMillis();
                    int n = 0;
                    if (action.equals("usual")) n = HouseBook.mark(st, key, from, to, -1, t, by(c));
                    else if (a.has("qty")) {
                        double q = number(a, "qty");
                        if (Double.isNaN(q) || q < 0 || q > HouseBook.MAX_QTY) return err("bad_value", "qty: what came that day in its unit, 0 to 50 (0 = none came).");
                        n = HouseBook.mark(st, key, from, to, q, t, by(c));
                    } else if (a.has("extra")) {
                        double x = number(a, "extra");
                        if (Double.isNaN(x)) return err("bad_value", "extra must be a number, e.g. 0.5 (half more) or -1 (one less).");
                        if (it == null) return err("ask_setup", "The usual amount isn't set yet: ask how much came that day (qty).");
                        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
                            if (HouseBook.usualOn(it, d) + x > HouseBook.MAX_QTY) return err("bad_value", "That is more than 50 in a day: ask again.");
                        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) n += HouseBook.mark(st, key, d, d, Math.max(0, HouseBook.usualOn(it, d) + x), t, by(c));
                    } else return err("missing", "Say qty (what came; 0 = none came) or extra (+ / − on the usual).");
                    if (n == 0) return err("bad_value", "Nothing written: ask again.");
                    changed(c, st);
                    JSONObject o = ok().put("days_written", n).put("from", from.toString()).put("to", to.toString());
                    if (it == null) o.put("note", tab ? "Written; Anil sets the rate on his phone." : "Written. The rate isn't set yet: ask him the price per litre / packet and how much comes each day (setup).");
                    else o.put("unit", it.optString("unit")).put("this_month", HouseBook.line(HouseBook.bill(st, key, YearMonth.from(today), today)));
                    if (tab) o.put("note2", "It goes to Anil's phone by itself. Say it is noted, in one short sentence.");
                    return o;
                }
                case "paid": case "unpaid": {
                    if (it == null) return err("not_set", tab ? "The milk account isn't on this tablet yet (Anil's phone sends it); offer to tell him (home_care tell_son)."
                            : "The milk account isn't set: ask him the rate first (setup).");
                    double amount = number(a, "amount");
                    if (!Double.isNaN(amount) && (amount < 1 || amount > 1_000_000)) return err("bad_value", "amount: the rupees given (1 or more). Leave it out for everything that is left.");
                    long t = System.currentTimeMillis();
                    if (action.equals("unpaid")) {
                        YearMonth m = a.optString("month").trim().isEmpty() ? HouseBook.lastPaidMonth(st, key) : HouseBook.month(a.optString("month"), today);
                        if (m == null) return err("nothing_given", "No money is written as given (or the month can't be read: YYYY-MM, this or last).");
                        if (m.isBefore(YearMonth.from(today).minusMonths(3)) || m.isAfter(YearMonth.from(today))) return err("bad_month", "Only the last three months.");
                        int n = HouseBook.unpay(st, key, m, Double.isNaN(amount) ? 0 : Math.round(amount), t, by(c));
                        if (n == 0) return err("not_found", "No such payment for " + HouseBook.MONTHS[m.getMonthValue() - 1] + ". Given then: " + HouseBook.paysOf(st, key, m, tab));
                        changed(c, st);
                        if (!tab) expenses(c, st);
                        return ok().put("taken_back", n).put("bill", HouseBook.line(HouseBook.bill(st, key, m, today)));
                    }
                    boolean saidMonth = !a.optString("month").trim().isEmpty();
                    YearMonth m = saidMonth ? HouseBook.month(a.optString("month"), today) : dueMonth(st, key, today);
                    if (m == null) return err("bad_month", "month: YYYY-MM, 'this' or 'last'.");
                    if (m.isBefore(YearMonth.from(today).minusMonths(3)) || m.isAfter(YearMonth.from(today))) return err("bad_month", "Only the last three months and this one.");
                    JSONObject b = HouseBook.bill(st, key, m, today);
                    if (Double.isNaN(amount)) amount = b.optLong("balance");
                    if (!(amount >= 1)) return ok().put("nothing_due", HouseBook.line(b)).put("given_then", HouseBook.paysOf(st, key, m, tab))
                            .put("note", "Nothing is left to give for " + b.optString("month_te") + (HouseBook.paysOf(st, key, m, tab).length() > 0
                                    ? ": tell who already gave (given_then), and ask if it was given twice." : "."));
                    JSONObject o = ok();
                    YearMonth thisM = YearMonth.from(today);
                    long left = b.optLong("balance");
                    if (!saidMonth && !m.equals(thisM) && left > 0 && amount > left) { // more than last month's rest: the rest goes to this month
                        HouseBook.pay(st, key, m, left, t, by(c));
                        HouseBook.pay(st, key, thisM, amount - left, t + 1, by(c));
                        o.put("split", HouseBook.rs(left) + " for " + b.optString("month_te") + ", " + HouseBook.rs(Math.round(amount - left)) + " for this month");
                    } else HouseBook.pay(st, key, m, amount, t, by(c));
                    changed(c, st);
                    if (!tab) {
                        expenses(c, st);
                        if (!sp(c).getString("say_m", "").isEmpty()) { final Context app = c.getApplicationContext(); new Thread(() -> sendToHome(app, false), "house-paid").start(); } // (the tablet mustn't tell her a bill now given)
                    }
                    b = HouseBook.bill(st, key, m, today);
                    return o.put("given", Math.round(amount)).put("month", b.optString("month_te")).put("bill", HouseBook.line(b))
                            .put("to_expenses", !tab && on(c, TO_EXPENSE))
                            .put("note", b.optBoolean("so_far") ? "Given for this month so far; the days after today still add." : "");
                }
                default: { // bill
                    if (it == null)
                        return err("not_set", tab ? "The milk account isn't on this tablet yet (Anil's phone sends it); say it simply and offer to tell him (home_care tell_son)."
                                : "The milk account isn't set yet. Ask him once: 'ఒక లీటర్ (లేదా ప్యాకెట్) ధర ఎంత? రోజూ ఎన్ని? ఈ నెల 1 నుంచే లెక్క వేయనా?' then setup.");
                    YearMonth m = a.optString("month").trim().isEmpty() ? null : HouseBook.month(a.optString("month"), today);
                    JSONObject o = ok().put("setup", HouseBook.setupLine(it, today));
                    if (m != null) return o.put("bill", HouseBook.line(HouseBook.bill(st, key, m, today)));
                    JSONObject now = HouseBook.bill(st, key, YearMonth.from(today), today), last = HouseBook.bill(st, key, YearMonth.from(today).minusMonths(1), today);
                    o.put("this_month", HouseBook.line(now));
                    if (last.optBoolean("started")) o.put("last_month", HouseBook.line(last)).put("last_month_left", last.optLong("balance"));
                    return o.put("next", tab ? "Tell her simply, in one or two short sentences (the amount and the days it didn't come)."
                            : "Say it in short spoken Telugu: the amount, the days that were different, what is left to give. Offer the month's PDF (house_accounts report).");
                }
            }
        }
    }

    /** The month money is given for when he doesn't say: last month while it is still not all given, else this one. */
    static YearMonth dueMonth(JSONObject st, String key, LocalDate today) throws Exception {
        YearMonth last = YearMonth.from(today).minusMonths(1);
        JSONObject b = HouseBook.bill(st, key, last, today);
        return b != null && b.optBoolean("started") && b.optLong("balance") > 0 ? last : YearMonth.from(today);
    }

    // ================================================================ his expenses (his phone)

    /** Each payment in his month's expenses (category house) while the switch is on; one taken back comes out again. */
    private static void expenses(Context c, JSONObject st) {
        boolean want = on(c, TO_EXPENSE), changed = false;
        JSONObject pays = st.optJSONObject("pays");
        if (pays == null) return;
        long now = System.currentTimeMillis();
        for (Iterator<String> i = pays.keys(); i.hasNext(); ) {
            try { // (one at a time: one bad payment never stops the others)
                JSONObject p = pays.optJSONObject(i.next());
                if (p == null) continue;
                boolean has = p.has("e");
                if (!p.optBoolean("x") && want && !has && now - p.optLong("t") < 40 * DAY && p.optLong("a") > 0) {
                    JSONObject it = HouseBook.item(st, p.optString("item"));
                    YearMonth m = YearMonth.parse(p.optString("m"));
                    String what = HouseBook.of(it == null ? "పాలు" : it.optString("name")) + " బిల్లు · " + HouseBook.MONTHS[m.getMonthValue() - 1];
                    JSONObject e = Money.add(c, p.optLong("a"), what, it == null ? "" : it.optString("vendor"), "house", Math.min(now, p.optLong("t")));
                    p.put("e", e.optLong("t")).put("ew", what);
                    changed = true;
                } else if (has && p.optBoolean("x")) {
                    Money.removeAt(c, p.optLong("e"), p.optString("ew"));
                    p.remove("e");
                    p.remove("ew");
                    changed = true;
                }
            } catch (Exception ignored) {}
        }
        if (changed) save(c, st);
    }

    // ================================================================ his phone: the link, the 1st, the 5th, the 10th

    /** From Proactive.tick (background thread, every ~15 minutes). */
    static void tick(Context c, Prefs p, boolean hush) {
        if (p.homeMode()) return; // (the tablet: tabletTick)
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        int h = java.time.LocalTime.now().getHour(), day = today.getDayOfMonth();
        clearSayIfDone(c, today);
        boolean linked = HomeLink.linked(c) && Net.online(c);
        if (linked) {
            if (since(c, "fetched_at") > 30 * MIN) {
                sp(c).edit().putLong("fetched_at", now).apply();
                fetchFromHome(c);
            }
            boolean sayDue = !sp(c).getString("say_m", "").isEmpty() && h >= 9 && h < 20 && since(c, "say_sent_at") > 3 * HOUR;
            boolean daily = isSet(c) && h >= 9 && h < 20 && since(c, "sent_ok_at") > DAY; // (once a day the whole account again: one may have been covered)
            if ((dirty(c) || sayDue || daily) && !backingOff(c)) sendToHome(c, false);
        }
        JSONObject st = load(c);
        if (HouseBook.items(st).isEmpty() || !on(c, BILL_FIRST)) return;
        YearMonth last = YearMonth.from(today).minusMonths(1);
        if (day <= 3 && h >= 9 && h < 13 && !hush && once(c, "bill|" + last)) {
            new Thread(() -> monthBill(c, p, last), "house-bill").start();
        } else if ((day == 5 || day == 10) && h >= 9 && h < 13 && once(c, "due|" + last + "|" + day)) {
            new Thread(() -> stillDue(c, last), "house-due").start();
        }
    }

    /** How long since that time was set (a clock gone back counts as long ago). */
    private static long since(Context c, String k) {
        long at = sp(c).getLong(k, 0), now = System.currentTimeMillis();
        return now < at ? Long.MAX_VALUE : now - at;
    }

    private static boolean backingOff(Context c) { return since(c, "fail_at") < 10 * MIN; }

    /** Once per key (key: kind|YYYY-MM[|day]); keys of months long gone are cleared. */
    private static boolean once(Context c, String k) {
        SharedPreferences s = sp(c);
        if (s.getBoolean("once|" + k, false)) return false;
        SharedPreferences.Editor e = s.edit().putBoolean("once|" + k, true);
        String cut = YearMonth.now().minusMonths(4).toString();
        java.util.regex.Pattern old = java.util.regex.Pattern.compile("once\\|\\w+\\|(\\d{4}-\\d{2}).*");
        for (String key : s.getAll().keySet()) {
            java.util.regex.Matcher m = old.matcher(key);
            if (m.matches() && m.group(1).compareTo(cut) < 0) e.remove(key);
        }
        e.apply();
        return true;
    }

    /** The month whose bill the tablet should tell her: done when she was told, it is all given, or after the 15th. */
    private static void clearSayIfDone(Context c, LocalDate today) {
        String m = sp(c).getString("say_m", "");
        if (m.isEmpty()) return;
        boolean done = today.getDayOfMonth() > 15 || !on(c, TELL_AMMA);
        try {
            if (!done) {
                JSONObject st = load(c);
                long left = 0;
                for (JSONObject it : HouseBook.items(st)) { JSONObject b = HouseBook.bill(st, it.optString("key"), YearMonth.parse(m), today); if (b != null) left += b.optLong("balance"); }
                done = left == 0;
            }
        } catch (Exception e) { done = true; }
        if (done) { sp(c).edit().remove("say_m").apply(); markDirty(c); } // (the tablet hears it no more needs telling)
    }

    /** The 1st: the tablet's newest entries first, then last month's bill told (and to the tablet for her). */
    private static void monthBill(Context c, Prefs p, YearMonth m) {
        boolean linked = HomeLink.linked(c) && Net.online(c);
        if (linked) askHome(c, 120_000L);
        JSONObject st = load(c);
        LocalDate today = LocalDate.now();
        StringBuilder say = new StringBuilder();
        boolean due = false;
        for (JSONObject it : HouseBook.items(st)) {
            try {
                JSONObject b = HouseBook.bill(st, it.optString("key"), m, today);
                if (b == null || !b.optBoolean("started") || b.optLong("amount") <= 0) continue;
                say.append(HouseBook.line(b).replace(" ✓", "")).append(' ');
                if (b.optLong("balance") > 0) {
                    due = true;
                    say.append("ఇచ్చాక '").append(HouseBook.of(it.optString("name"))).append(" బిల్లు ఇచ్చాను' అనండి. ");
                }
            } catch (Exception ignored) {}
        }
        if (say.length() == 0) return;
        String text = p.name() + ", " + say.toString().trim();
        notify(c, 1, "🥛 " + HouseBook.MONTHS[m.getMonthValue() - 1] + " బిల్లు", text);
        if (!DutyMode.on(c)) Announcer.say(c, text);
        if (due && on(c, TELL_AMMA)) {
            sp(c).edit().putString("say_m", m.toString()).remove("say_sent_at").apply();
            if (linked) sendToHome(c, false);
        }
    }

    /** The 5th / the 10th: still not all given → a note. */
    private static void stillDue(Context c, YearMonth m) {
        if (HomeLink.linked(c) && Net.online(c)) askHome(c, 90_000L);
        JSONObject st = load(c);
        StringBuilder s = new StringBuilder();
        for (JSONObject it : HouseBook.items(st)) {
            try {
                JSONObject b = HouseBook.bill(st, it.optString("key"), m, LocalDate.now());
                if (b == null || b.optLong("balance") <= 0) continue;
                s.append(HouseBook.MONTHS[m.getMonthValue() - 1]).append(' ').append(HouseBook.of(it.optString("name"))).append(" బిల్లు ")
                        .append(HouseBook.rs(b.optLong("balance"))).append(" ఇంకా ఇవ్వలేదు. ");
            } catch (Exception ignored) {}
        }
        if (s.length() == 0) return;
        notify(c, 2, "🥛 " + s.toString().trim(), "ఇచ్చి ఉంటే 'పాల బిల్లు ఇచ్చాను' అనండి (అమ్మగారు ఇచ్చి ఉంటే టాబ్లెట్‌కి చెప్పొచ్చు).");
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_house", "🥛 ఇంటి లెక్కలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 3900 + id, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "పాల బిల్లు చెప్పు: ఈ నెల, గత నెల (house_accounts bill).")
                            .putExtra(MainActivity.EXTRA_LABEL, "🥛 పాల బిల్లు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("house", id, new Notification.Builder(c, "jarvis_house").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    /** A quiet note on his phone of what was said at home (the entries only), and money that looks given twice. */
    private static void notifyNews(Context c, JSONObject st, JSONArray news) {
        JSONArray home = new JSONArray();
        for (int i = 0; i < news.length(); i++) {
            JSONObject x = news.optJSONObject(i);
            if (x != null && HouseBook.AMMA.equals(x.optString("by"))) home.put(x);
        }
        if (home.length() == 0) return;
        List<String> lines = HouseBook.newsLines(st, home, LocalDate.now());
        for (int i = 0; i < home.length(); i++) { // given again for a month that was already all given?
            JSONObject x = home.optJSONObject(i);
            if (!"pay".equals(x.optString("kind")) || x.optBoolean("x")) continue;
            try {
                JSONObject b = HouseBook.bill(st, x.optString("item"), YearMonth.parse(x.optString("m")), LocalDate.now());
                if (b != null && b.optLong("amount") > 0 && b.optLong("paid") - x.optLong("a") >= b.optLong("amount"))
                    lines.add("⚠️ " + b.optString("month_te") + " బిల్లు ఇది వరకే ఇచ్చేశారు: రెండుసార్లు రాసినట్టుంది. ఒకసారే ఇచ్చి ఉంటే 'పాల బిల్లు "
                            + HouseBook.rs(x.optLong("a")) + " తప్పు, ఇవ్వలేదు' అనండి.");
            } catch (Exception ignored) {}
        }
        if (lines.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 6; i++) b.append(i > 0 ? "\n" : "").append(lines.get(i));
        if (lines.size() > 6) b.append("\n… ఇంకా ").append(lines.size() - 6);
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_house_home", "🏠 ఇంటి నుంచి పాల లెక్క", NotificationManager.IMPORTANCE_LOW));
            nm.notify("house", 3, new Notification.Builder(c, "jarvis_house_home").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("🏠 ఇంటి నుంచి పాల లెక్క").setContentText(b.toString()).setStyle(new Notification.BigTextStyle().bigText(b.toString()))
                    .setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ the home link (both ways)

    /** One send over the home link at a time (never inside the account's own lock: a send can take seconds). */
    private static final Object SEND = new Object();

    /** The JSON after the first line (the first line is only for the eye, in his Telegram). */
    private static JSONObject body(String text) {
        int i = text == null ? -1 : text.indexOf('{');
        try { return i < 0 ? null : new JSONObject(text.substring(i)); } catch (Exception e) { return null; }
    }

    /**
     * One message: sent, pinned, the previous one of ours deleted (the chat keeps only the newest). A message that went
     * but couldn't be pinned is deleted again (it would never be read) and the next try waits 10 minutes. Returns its id or -1.
     */
    private static long post(Context c, String prefix, String firstLine, JSONObject body) {
        String text = prefix + firstLine + "\n" + body;
        if (text.length() > 4096) { sp(c).edit().putLong("fail_at", System.currentTimeMillis()).apply(); return -1; }
        long id = Guard.sendQuiet(c, text);
        if (id < 0) { sp(c).edit().putLong("fail_at", System.currentTimeMillis()).apply(); return -1; }
        if (!Guard.pin(c, id)) {
            Guard.delete(c, id);
            sp(c).edit().putLong("fail_at", System.currentTimeMillis()).apply();
            return -1;
        }
        long prev = sp(c).getLong("own_id", 0);
        sp(c).edit().putLong("own_id", id).putLong("sent_ok_at", System.currentTimeMillis()).apply();
        if (prev > 0 && prev != id && !Guard.delete(c, prev)) Guard.unpin(c, prev); // (too old to delete: at least off the pins)
        return id;
    }

    /** Room for the JSON in one message, after the prefix and the first line. */
    private static int room(String prefix) { return 4096 - prefix.length() - 80; }

    /**
     * His phone → the tablet: the account, the newest of her words it holds (so she needn't send them again), the
     * month to tell her if any, and ask (send me yours now). Background thread.
     */
    static boolean sendToHome(Context c, boolean ask) {
        if (tablet(c) || !HomeLink.linked(c) || !Net.online(c)) return false;
        synchronized (SEND) {
            JSONObject pm = Guard.pinned(c);
            if (pm != null && pm.optString("text").startsWith(FROM_HOME)) fromHome(c, pm.optString("text"), pm.optLong("message_id")); // (hers first: never covered unread)
            if (HomeLink.phoneWordWaiting(pm)) return false; // (another word of his to home still on its way: next time)
            if (pm != null && !pm.optString("text").startsWith(TO_HOME) && !pm.optString("text").startsWith(FROM_HOME)
                    && System.currentTimeMillis() / 1000 - pm.optLong("date") < 60) return false; // (the tablet's fresh answer / photo his phone may still be fetching)
            try {
                long seq = sp(c).getLong("dseq", 0);
                JSONObject st = load(c);
                LocalDate today = LocalDate.now();
                long ack = HouseBook.newestBy(st, HouseBook.AMMA);
                String say = sp(c).getString("say_m", "");
                JSONObject o = HouseBook.fitting(st, true, today, room(TO_HOME) - 120, System.currentTimeMillis()).put("ack", ack);
                if (!say.isEmpty()) o.put("say_m", say);
                if (ask) o.put("ask", true);
                long id = post(c, TO_HOME, "పాల లెక్క టాబ్లెట్‌కి" + (say.isEmpty() ? "" : " (అమ్మగారికి బిల్లు చెప్పమని)"), o);
                if (id < 0) return false;
                SharedPreferences.Editor e = sp(c).edit().putLong("sseq", Math.max(seq, sp(c).getLong("sseq", 0))).putLong("sent_id", id).putLong("acked", ack);
                if (!say.isEmpty()) e.putLong("say_sent_at", System.currentTimeMillis());
                e.apply();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** His phone: asks the tablet for its newest entries and waits for them (up to ms). Background thread. */
    static boolean askHome(Context c, long ms) {
        if (!sendToHome(c, true)) return false;
        long asked = sp(c).getLong("sent_id", 0), until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            try { Thread.sleep(4000); } catch (InterruptedException e) { return false; }
            JSONObject m = Guard.pinned(c);
            if (m != null && m.optLong("message_id") > asked && m.optString("text").startsWith(FROM_HOME)) {
                fromHome(c, m.optString("text"), m.optLong("message_id"));
                return true;
            }
        }
        return false;
    }

    /** His phone: the pinned word, if it is the tablet's account not taken yet. Background thread. */
    static void fetchFromHome(Context c) {
        JSONObject m = Guard.pinned(c);
        if (m != null && m.optString("text").startsWith(FROM_HOME)) fromHome(c, m.optString("text"), m.optLong("message_id"));
    }

    /**
     * His phone: the tablet's entries taken in (each message once; only accounts his phone set, and milk). What was new
     * is noted for him and given money goes into expenses; his phone answers (so the tablet knows it has them).
     */
    static void fromHome(Context c, String text, long id) {
        if (tablet(c) || id <= sp(c).getLong("home_id", 0)) return;
        JSONObject o = body(text);
        synchronized (House.class) {
            sp(c).edit().putLong("home_id", id).apply();
            if (o == null) return;
            try {
                JSONObject st = load(c);
                JSONArray news = HouseBook.merge(st, o, false);
                String said = o.optString("said_m");
                if (!said.isEmpty() && said.equals(sp(c).getString("say_m", ""))) sp(c).edit().remove("say_m").apply(); // (she was told)
                boolean answer = o.optBoolean("need_items") || HouseBook.newestBy(st, HouseBook.AMMA) > sp(c).getLong("acked", 0)
                        || (o.has("got") && o.optLong("got") < sp(c).getLong("sent_id", 0)) // (his last word didn't reach the tablet: again)
                        || o.optLong("ack") < HouseBook.newestBy(st, HouseBook.AMMA);
                if (news.length() > 0) {
                    save(c, st);
                    expenses(c, st);
                    notifyNews(c, st, news);
                }
                if (answer) markDirty(c);
            } catch (Exception ignored) {}
        }
    }

    // ================================================================ the tablet

    /**
     * The tablet: his phone's word (the items, his days, how far it has hers, an ask, the month to tell her). Each word
     * is the whole truth: a month to tell her that isn't in it any more (given, or he switched it off) is not told.
     */
    static void fromPhone(Context c, String text, long id) {
        if (!tablet(c)) return;
        JSONObject o = body(text);
        if (o == null) return;
        boolean answer = false;
        synchronized (House.class) {
            try {
                JSONObject st = load(c);
                HouseBook.merge(st, o, true);
                save(c, st);
                SharedPreferences.Editor e = sp(c).edit().putLong("ack", Math.max(sp(c).getLong("ack", 0), o.optLong("ack")))
                        .putLong("got", Math.max(sp(c).getLong("got", 0), id)).putInt("resend_n", 0);
                String m = o.optString("say_m");
                if (m.isEmpty()) e.remove("say_m"); // (nothing to tell her now)
                else if (m.equals(sp(c).getString("said_m", ""))) answer = true; // (already told her: say so again, his phone still waits)
                else e.putString("say_m", m);
                e.apply();
                if (o.optBoolean("ask")) answer = true;
            } catch (Exception ignored) {}
        }
        if (answer) { markDirty(c); sendSoon(c); }
    }

    /** The tablet: her words not yet known to have reached his phone, and the wait before sending them again (45 min, doubling to a day). */
    private static boolean resendDue(Context c, JSONObject st) {
        if (HouseBook.newestBy(st, HouseBook.AMMA) <= sp(c).getLong("ack", 0)) return false;
        int n = Math.min(5, sp(c).getInt("resend_n", 0));
        return since(c, "sent_ok_at") > Math.min(DAY, 45 * MIN << n);
    }

    private static boolean needItems(Context c, JSONObject st) {
        return HouseBook.items(st).isEmpty() && HouseBook.newestBy(st, HouseBook.AMMA) > 0
                && !LocalDate.now().toString().equals(sp(c).getString("need_day", ""));
    }

    /** The tablet: from HomeCare.tick (the care thread, every minute; the sending itself on its own thread). */
    static void tabletTick(Context c) {
        if (!tablet(c) || Guard.token(c).isEmpty() || Guard.chat(c).isEmpty()) return;
        LocalDate today = LocalDate.now();
        int h = java.time.LocalTime.now().getHour();
        JSONObject st = load(c);
        if ((dirty(c) || resendDue(c, st) || needItems(c, st)) && Net.online(c) && !backingOff(c)) sendSoon(c);
        String m = sp(c).getString("say_m", "");
        if (m.isEmpty()) return;
        YearMonth ym;
        try { ym = YearMonth.parse(m); } catch (Exception e) { sp(c).edit().remove("say_m").apply(); return; }
        if (today.isAfter(ym.plusMonths(1).atDay(15))) { sp(c).edit().remove("say_m").apply(); return; } // (too late to tell her now)
        if (h >= 9 && h < 20 && !HomeCare.night() && !HomeCare.out(c) && !HomeCare.resting(c) && !HomeCare.quiet(c) && !MainActivity.busyTalking()) {
            sp(c).edit().remove("say_m").putString("said_m", m).apply();
            markDirty(c); // (his phone hears she was told)
            try {
                for (JSONObject it : HouseBook.items(st)) {
                    JSONObject b = HouseBook.bill(st, it.optString("key"), ym, today);
                    if (b != null && b.optBoolean("started") && b.optLong("balance") > 0) HomeCare.say(c, HouseBook.ammaLine(b, HomeCare.who(c)), "calm");
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * The tablet → his phone: her entries (and his copy: the merge keeps the later word), that she was told, which word
     * of his it last took, and how far his phone has hers. Only when something is to be said, and never over a word of
     * his not read yet or a game move under way.
     */
    private static void sendFromHome(Context c) {
        synchronized (SEND) {
            try {
                JSONObject st = load(c);
                boolean resend = resendDue(c, st), need = needItems(c, st);
                if (!dirty(c) && !resend && !need) return; // (another thread said it a moment ago)
                if (HomeLink.commandWaiting(c)) return;
                JSONObject pm = Guard.pinned(c);
                if (pm != null && pm.optString("text").startsWith(GameLink.TAG) && System.currentTimeMillis() / 1000 - pm.optLong("date") < 10 * 60) return; // (a game being played)
                long seq = sp(c).getLong("dseq", 0);
                JSONObject o = HouseBook.fitting(st, false, LocalDate.now(), room(FROM_HOME) - 120, System.currentTimeMillis())
                        .put("got", sp(c).getLong("got", 0)).put("ack", sp(c).getLong("ack", 0));
                String said = sp(c).getString("said_m", "");
                if (!said.isEmpty()) o.put("said_m", said);
                if (HouseBook.items(st).isEmpty()) o.put("need_items", true);
                if (post(c, FROM_HOME, "పాల లెక్క అబ్బాయి ఫోన్‌కి", o) > 0) {
                    SharedPreferences.Editor e = sp(c).edit().putLong("sseq", Math.max(seq, sp(c).getLong("sseq", 0)));
                    if (resend) e.putInt("resend_n", sp(c).getInt("resend_n", 0) + 1);
                    if (need) e.putString("need_day", LocalDate.now().toString());
                    e.apply();
                }
            } catch (Exception ignored) {}
        }
    }

    /** The tablet: sent in the background (after her word, an ask, or when the tick finds something to say). */
    static void sendSoon(Context c) {
        if (!tablet(c)) return;
        final Context a = c.getApplicationContext();
        new Thread(() -> {
            try { if (Net.online(a) && !backingOff(a)) sendFromHome(a); } catch (Throwable ignored) {}
        }, "house-send").start();
    }

    // ================================================================ for the month's report

    /** Lines for the month's PDF (Monthly): one per account; empty when none. */
    static String monthLines(Context c, YearMonth ym) {
        StringBuilder s = new StringBuilder();
        try {
            JSONObject st = load(c);
            for (JSONObject it : HouseBook.items(st)) {
                JSONObject b = HouseBook.bill(st, it.optString("key"), ym, LocalDate.now());
                if (b != null && b.optBoolean("started")) s.append("  ").append(HouseBook.line(b).replace(" ✓", "")).append('\n');
            }
        } catch (Exception ignored) {}
        return s.toString();
    }

    static Coder.Made pdf(Context c, String key, YearMonth ym) throws Exception { return HouseReport.pdf(c, key, ym); }
}
