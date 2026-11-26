package com.anil.jarvis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 💰 Jarvis's money advisor: his month (in, out, by kind), the money that leaks away (food delivery, small UPI, forgotten
 * subscriptions, bank fines, card interest, cash), today's spending room, salary-day "save first", unusual debits, the
 * safety net (emergency fund, insurance, costly debt), goals, his investments and net worth, the order to do things in,
 * an investment guide in Telugu (facts and fit, never a "buy this"), "can I buy this?" and a scheme / scam check.
 * All counted on the phone (₹0). Pure Java (tested on a PC); it writes JSON with {@link Jw}.
 */
final class FinCoach {
    private FinCoach() {}

    static final double INFLATION = 6.0;

    // ================================================================ what he told Jarvis

    static final class Profile {
        double income, budget, saveGoal, term, health, lowBal;
        int salaryDay, age, dependents, efMonths = 6;
        String risk = "mid";
        boolean healthFamily;
    }

    static final class Goal {
        String id = "", name = "", kind = "other", by = "";
        double target, saved;
    }

    static final class Holding {
        String id = "", kind = "other", name = "", code = "", start = "", maturity = "", goal = "";
        double invested, value, units, price, rate, sip;
        long priceAt;
        int sipDay;
        boolean emergency;

        double now() {
            if (units > 0 && price > 0) return units * price;
            if (value > 0) return value;
            // an FD / post-office deposit with its rate and start date: the interest so far (to maturity at most), quarterly like the bank
            if (rate > 0 && invested > 0 && ("fd".equals(kind) || "post".equals(kind)) && start.matches("\\d{4}-\\d{2}-\\d{2}")) {
                try {
                    LocalDate from = LocalDate.parse(start), to = LocalDate.now();
                    if (maturity.matches("\\d{4}-\\d{2}-\\d{2}") && to.isAfter(LocalDate.parse(maturity))) to = LocalDate.parse(maturity);
                    double yrs = Math.max(0, ChronoUnit.DAYS.between(from, to) / 365.25);
                    return FinMath.fd(invested, rate, yrs);
                } catch (Exception ignored) {}
            }
            return invested;
        }
    }

    static final class Loan {
        String id = "", name = "", kind = "loan";
        double outstanding, rate, emi;
        int monthsLeft;
    }

    // ================================================================ joining SMS and his own entries

    /** His own entries: one already seen in an SMS (same amount, within 2 days) gives that SMS its kind instead of counting twice. */
    static List<FinTxn> merge(List<FinTxn> sms, List<FinTxn> manual) {
        List<FinTxn> out = new ArrayList<>(sms);
        for (FinTxn m : manual) {
            FinTxn hit = null;
            for (FinTxn s : sms) {
                if (s.credit || Math.abs(s.amt - m.amt) > 1 || Math.abs(s.t - m.t) > 2 * 86_400_000L) continue;
                hit = s;
                break;
            }
            if (hit != null) {
                if (!"other".equals(m.cat)) hit.cat = m.cat;
                if (!m.note.isEmpty()) hit.note = m.note;
            } else {
                out.add(m);
            }
        }
        out.sort((a, b) -> Long.compare(b.t, a.t));
        markSelf(out);
        return out;
    }

    /** The same amount out of one of his accounts and into another within 15 minutes: his own transfer, neither spent nor earned. */
    static void markSelf(List<FinTxn> l) {
        for (FinTxn d : l) {
            if (d.credit || !"sms".equals(d.src) || "self".equals(d.cat)) continue;
            for (FinTxn c : l) {
                if (!c.credit || !"sms".equals(c.src) || "self".equals(c.cat)) continue;
                if (Math.abs(c.amt - d.amt) > 0.5 || Math.abs(c.t - d.t) > 15 * 60_000L) continue;
                boolean other = !c.acct.equals(d.acct) || !c.bank.equals(d.bank);
                if (!other) continue;
                d.cat = "self";
                c.cat = "self";
                break;
            }
        }
    }

    static YearMonth ym(long t, ZoneId z) { return YearMonth.from(Instant.ofEpochMilli(t).atZone(z)); }

    static List<FinTxn> in(List<FinTxn> l, YearMonth m, ZoneId z) {
        List<FinTxn> o = new ArrayList<>();
        for (FinTxn x : l) if (ym(x.t, z).equals(m)) o.add(x);
        return o;
    }

    static double spent(List<FinTxn> l) {
        double s = 0;
        for (FinTxn x : l) if (x.spending()) s += x.amt;
        return s;
    }

    /** The average spending of the last full months (up to 3) before {@code m}; this month's pace when there is no history. */
    static double avgSpend(List<FinTxn> all, YearMonth m, ZoneId z, LocalDate today) {
        double s = 0;
        int n = 0;
        for (int k = 1; k <= 3; k++) {
            List<FinTxn> l = in(all, m.minusMonths(k), z);
            if (l.isEmpty()) continue;
            s += spent(l);
            n++;
        }
        if (n > 0) return s / n;
        double cur = spent(in(all, m, z));
        int d = Math.max(1, today.getDayOfMonth());
        return cur / d * m.lengthOfMonth();
    }

    // ================================================================ the month

    static Jw month(List<FinTxn> all, YearMonth m, Profile p, ZoneId z, LocalDate today) {
        List<FinTxn> l = in(all, m, z);
        double in = 0, sal = 0, out = 0, inv = 0, fixed = 0, refunds = 0;
        Map<String, double[]> cats = new HashMap<>();
        Map<String, double[]> payees = new HashMap<>();
        for (FinTxn x : l) {
            if (x.income()) {
                in += x.amt;
                if ("salary".equals(x.cat)) sal += x.amt;
                continue;
            }
            if (x.credit && "refund".equals(x.cat)) { refunds += x.amt; continue; }
            if (x.credit) continue;
            if ("invest".equals(x.cat)) { inv += x.amt; continue; }
            if (!x.spending()) continue;
            out += x.amt;
            if (FinTxn.fixed(x.cat)) fixed += x.amt;
            double[] c = cats.computeIfAbsent(x.cat, k -> new double[2]);
            c[0] += x.amt;
            c[1]++;
            if (!x.payee.isEmpty() && !"UPI వ్యక్తి".equals(x.payee)) {
                double[] q = payees.computeIfAbsent(x.payee, k -> new double[2]);
                q[0] += x.amt;
                q[1]++;
            }
        }
        out = Math.max(0, out - refunds);
        double income = p.income > 0 ? p.income : (sal > 0 ? sal : in);
        Jw o = Jw.obj().put("month", m.toString()).put("label", monthTe(m)).put("so_far", m.equals(YearMonth.from(today)))
                .put("came_in", Math.round(in)).put("salary", Math.round(sal)).put("income", Math.round(income)).put("spent", Math.round(out))
                .put("invested", Math.round(inv)).put("fixed", Math.round(fixed)).put("flexible", Math.round(out - fixed)).put("refunds", Math.round(refunds))
                .put("left", Math.round(income - out - inv)).put("count", l.size());
        if (income > 0) o.put("save_rate", Math.round((income - out) * 100 / income));
        List<Map.Entry<String, double[]>> cl = new ArrayList<>(cats.entrySet());
        cl.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        Jw ca = Jw.arr();
        for (Map.Entry<String, double[]> e : cl)
            ca.add(Jw.obj().put("cat", e.getKey()).put("te", FinTxn.catTe(e.getKey())).put("amount", Math.round(e.getValue()[0])).put("n", (long) e.getValue()[1])
                    .put("fixed", FinTxn.fixed(e.getKey())));
        o.put("cats", ca);
        List<Map.Entry<String, double[]>> pl = new ArrayList<>(payees.entrySet());
        pl.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        Jw pa = Jw.arr();
        for (int i = 0; i < Math.min(8, pl.size()); i++)
            pa.add(Jw.obj().put("name", pl.get(i).getKey()).put("amount", Math.round(pl.get(i).getValue()[0])).put("n", (long) pl.get(i).getValue()[1]));
        o.put("payees", pa);
        return o;
    }

    static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    static String monthTe(YearMonth m) { return MONTHS[m.getMonthValue() - 1] + " " + m.getYear(); }

    // ================================================================ today's room to spend

    /**
     * What he can still spend this month without missing his savings: (his budget, or income − savings goal) − spent so far,
     * shared over the days left (today included). Null-like (left = −1) when nothing to go on.
     */
    static Jw allowance(List<FinTxn> all, Profile p, ZoneId z, LocalDate today) {
        YearMonth m = YearMonth.from(today);
        double spent = spent(in(all, m, z));
        double plan = p.budget > 0 ? p.budget : p.income > 0 ? p.income - Math.max(0, p.saveGoal) : -1;
        Jw o = Jw.obj().put("spent", Math.round(spent));
        if (plan <= 0) return o.put("left", -1).put("te", "రోజూ ఎంత ఖర్చు పెట్టొచ్చో చెప్పాలంటే: నెల జీతం (లేదా బడ్జెట్), పొదుపు లక్ష్యం ⚙️ నా వివరాలు లో పెట్టండి.");
        int daysLeft = m.lengthOfMonth() - today.getDayOfMonth() + 1;
        double left = plan - spent;
        double perDay = left / Math.max(1, daysLeft);
        o.put("plan", Math.round(plan)).put("left", Math.round(left)).put("days_left", daysLeft).put("per_day", Math.round(Math.max(0, perDay)));
        String te;
        if (left <= 0) te = "ఈ నెల ప్లాన్ (" + FinMath.rs(plan) + ") అయిపోయింది: " + FinMath.rs(-left) + " ఎక్కువ. ఇక అవసరమైనవి మాత్రమే.";
        else te = "ఈరోజు " + FinMath.rs(perDay) + " వరకు ఖర్చు పెట్టొచ్చు (నెలలో ఇంకా " + FinMath.rs(left) + ", " + daysLeft + " రోజులు).";
        return o.put("te", te);
    }

    // ================================================================ money that leaks away

    private static final Pattern DELIVERY = Pattern.compile("swiggy|zomato|eatsure|domino|box8|faasos|behrouz|magicpin|ubereats");
    private static final Pattern OTT = Pattern.compile("netflix|hotstar|disney|prime video|amazon prime|spotify|youtube|zee5|sonyliv|sony liv|\\baha\\b|gaana|wynk|apple|google one|"
            + "icloud|linkedin|openai|chatgpt|anthropic|claude|microsoft|swiggy one|zomato gold|jiosaavn|discovery|hoichoi|sun nxt|eros|alt balaji");

    static Jw leaks(List<FinTxn> all, Profile p, ZoneId z, LocalDate today) {
        YearMonth m = YearMonth.from(today);
        List<FinTxn> cur = in(all, m, z);
        double income = p.income > 0 ? p.income : 0;
        int day = Math.max(1, today.getDayOfMonth());
        double scale = (double) m.lengthOfMonth() / day; // (a month's worth at this pace)
        Jw a = Jw.arr();
        double delTotal = 0, smallTotal = 0, chargeTotal = 0, cash = 0, night = 0;
        int delN = 0, smallN = 0, cashN = 0, nightN = 0;
        Map<String, Double> chargeKinds = new LinkedHashMap<>();
        for (FinTxn x : cur) {
            if (!x.spending()) continue;
            String pl = x.payee.toLowerCase(Locale.ROOT);
            if ("food".equals(x.cat) && DELIVERY.matcher(pl).find()) { delTotal += x.amt; delN++; }
            if (x.amt < 200 && !"cash".equals(x.cat) && !"auto".equals(x.channel) && !"charges".equals(x.cat)) { smallTotal += x.amt; smallN++; }
            if ("charges".equals(x.cat)) { chargeTotal += x.amt; chargeKinds.merge(x.payee.isEmpty() ? "బ్యాంక్ చార్జీ" : x.payee, x.amt, Double::sum); }
            if ("cash".equals(x.cat)) { cash += x.amt; cashN++; }
            int h = Instant.ofEpochMilli(x.t).atZone(z).getHour();
            if ((h >= 23 || h < 4) && "shopping".equals(x.cat)) { night += x.amt; nightN++; }
        }
        double spentNow = spent(cur);
        if (delN >= 4 || (income > 0 && delTotal * scale > income * 0.06)) {
            double half = delTotal * scale / 2;
            a.add(leak("delivery", "🍔", "ఫుడ్ డెలివరీ: ఈ నెల " + delN + " సార్లు, " + FinMath.rs(delTotal),
                    "డెలివరీలో ధర + డెలివరీ ఫీ + ప్యాకింగ్ కలిపి ఇంటి వంట కంటే 2–3 రెట్లు. సగం తగ్గిస్తే నెలకి సుమారు " + FinMath.rs(half)
                            + ", ఏడాదికి " + FinMath.rs(half * 12) + " మిగులుతుంది.", delTotal, half * 12));
        }
        if (smallN >= 20) {
            a.add(leak("small", "☕", "చిన్న UPI ఖర్చులు (₹200 లోపు): " + smallN + " సార్లు, " + FinMath.rs(smallTotal),
                    "చిన్నవి కనబడవు కానీ కలిస్తే పెద్దది. రోజుకి ఒక చిన్న ఖర్చు తగ్గిస్తే నెలకి సుమారు " + FinMath.rs(smallTotal / Math.max(1, smallN) * 30 / 2) + ".",
                    smallTotal, smallTotal / Math.max(1, smallN) * 30 / 2 * 12));
        }
        if (chargeTotal > 0) {
            StringBuilder k = new StringBuilder();
            for (Map.Entry<String, Double> e : chargeKinds.entrySet()) {
                if (k.length() > 0) k.append(", ");
                k.append(e.getKey()).append(" ").append(FinMath.rs(e.getValue()));
            }
            a.add(leak("charges", "⚠️", "బ్యాంక్ చార్జీలు / ఫైన్లు: " + FinMath.rs(chargeTotal),
                    "ఇవి పూర్తిగా వృథా (" + k + "). మినిమం బ్యాలెన్స్ ఉంచడం, బిల్లులు గడువులోగా కట్టడం (రిమైండర్‌లు), వాడని కార్డ్ / SMS సేవలు ఆపడంతో ఆపొచ్చు.",
                    chargeTotal, chargeTotal * 12));
        }
        if (cashN > 0 && spentNow > 0 && cash > spentNow * 0.15) {
            a.add(leak("cash", "💵", "ATM క్యాష్: " + cashN + " సార్లు, " + FinMath.rs(cash) + " (ఖర్చులో " + Math.round(cash * 100 / spentNow) + "%)",
                    "క్యాష్ ఎక్కడ పోయిందో లెక్క ఉండదు. క్యాష్‌తో పెద్దవి కొంటే \"Jarvis, … రాసుకో\" అని చెప్పండి; వీలైతే UPI వాడండి.", cash, 0));
        }
        if (nightN >= 2) {
            a.add(leak("night", "🌙", "రాత్రి 11 తర్వాత షాపింగ్: " + nightN + " సార్లు, " + FinMath.rs(night),
                    "అలసటలో కొనేవి చాలాసార్లు అవసరం లేనివి. కార్ట్‌లో పెట్టి, మరుసటి రోజు చూసి కొనండి.", night, night * 12 / 2));
        }
        // a kind that jumped against the last two months
        Map<String, Double> now = new HashMap<>(), before = new HashMap<>();
        for (FinTxn x : cur) if (x.spending()) now.merge(x.cat, x.amt, Double::sum);
        int months = 0;
        for (int k = 1; k <= 2; k++) {
            List<FinTxn> l = in(all, m.minusMonths(k), z);
            if (l.isEmpty()) continue;
            months++;
            for (FinTxn x : l) if (x.spending()) before.merge(x.cat, x.amt, Double::sum);
        }
        if (months > 0 && day >= 10) {
            for (Map.Entry<String, Double> e : now.entrySet()) {
                String c = e.getKey();
                if (FinTxn.fixed(c) || "people".equals(c) || "other".equals(c) || "charges".equals(c) || "self".equals(c)) continue;
                double avg = before.getOrDefault(c, 0.0) / months, proj = e.getValue() * scale;
                if (avg > 500 && proj > avg * 1.4 && proj - avg > 1000)
                    a.add(leak("jump_" + c, "📈", FinTxn.catTe(c) + ": ఈ వేగంతో నెలకి " + FinMath.rs(proj) + " (మామూలుగా " + FinMath.rs(avg) + ")",
                            "ఈ నెల ఈ ఖర్చు " + Math.round((proj / avg - 1) * 100) + "% ఎక్కువ వేగంతో పోతోంది. అవసరమైనదేనా ఒకసారి చూడండి.", e.getValue(), 0));
            }
        }
        if (income > 0) {
            double shop = now.getOrDefault("shopping", 0.0) * scale;
            if (shop > income * 0.15)
                a.add(leak("shopping", "🛍️", "షాపింగ్: ఈ వేగంతో నెలకి " + FinMath.rs(shop) + " (జీతంలో " + Math.round(shop * 100 / income) + "%)",
                        "కావాల్సినవి / కోరికలు వేరు చేయండి. ₹2,000 పైన ఏది కొనేముందైనా 24 గంటలు ఆగండి; \"Jarvis, ఇది కొనొచ్చా?\" అని అడగండి.", now.getOrDefault("shopping", 0.0), 0));
        }
        return a;
    }

    private static Jw leak(String id, String icon, String title, String tip, double amount, double yearly) {
        return Jw.obj().put("id", id).put("icon", icon).put("title", title).put("tip", tip).put("amount", Math.round(amount)).put("yearly", Math.round(yearly));
    }

    // ================================================================ subscriptions and auto-debits

    static final class Sub {
        String name;
        double amount;
        String cat = "other";
        int day, months;
        long last;
        boolean ott;
    }

    /** The same payee about the same amount in 2 or more of the last 3–4 months (or a known subscription even once). */
    static List<Sub> subscriptions(List<FinTxn> all, ZoneId z, LocalDate today) {
        long since = today.minusDays(125).atStartOfDay(z).toInstant().toEpochMilli();
        Map<String, List<FinTxn>> by = new HashMap<>();
        for (FinTxn x : all) {
            if (x.t < since || x.credit || x.payee.isEmpty() || "UPI వ్యక్తి".equals(x.payee) || "self".equals(x.cat) || "card_bill".equals(x.cat)
                    || "cash".equals(x.cat) || "people".equals(x.cat) && !"auto".equals(x.channel)) continue;
            by.computeIfAbsent(x.payee, k -> new ArrayList<>()).add(x);
        }
        List<Sub> out = new ArrayList<>();
        for (Map.Entry<String, List<FinTxn>> e : by.entrySet()) {
            List<FinTxn> l = e.getValue();
            l.sort((a, b) -> Long.compare(b.t, a.t));
            boolean ott = OTT.matcher(e.getKey().toLowerCase(Locale.ROOT)).find();
            Map<YearMonth, FinTxn> per = new LinkedHashMap<>();
            for (FinTxn x : l) per.putIfAbsent(ym(x.t, z), x);
            FinTxn newest = l.get(0);
            int same = 0;
            for (FinTxn x : per.values()) if (Math.abs(x.amt - newest.amt) <= Math.max(20, newest.amt * 0.15)) same++;
            boolean regular = same >= 2 && per.size() >= 2;
            if (!regular && !(ott && newest.amt < 2000)) continue;
            if ("food".equals(newest.cat) || "groceries".equals(newest.cat) || "fuel".equals(newest.cat) || "transport".equals(newest.cat)
                    || "shopping".equals(newest.cat) && !ott || "health".equals(newest.cat)) continue; // (shops he goes to often, not subscriptions)
            if ("rent".equals(newest.cat) || "emi".equals(newest.cat) || "invest".equals(newest.cat) || "insurance".equals(newest.cat) || "bills".equals(newest.cat)
                    || "education".equals(newest.cat) || "salary".equals(newest.cat)) continue; // (rent, loans, SIPs, insurance, bills: needed, never "stop it?")
            Sub s = new Sub();
            s.name = e.getKey();
            s.amount = newest.amt;
            s.day = Instant.ofEpochMilli(newest.t).atZone(z).getDayOfMonth();
            s.months = same;
            s.last = newest.t;
            s.ott = ott;
            s.cat = newest.cat;
            out.add(s);
        }
        out.sort((a, b) -> Double.compare(b.amount, a.amount));
        return out;
    }

    static Jw subsJson(List<Sub> l, Map<String, String> marks) {
        Jw a = Jw.arr();
        for (Sub s : l)
            a.add(Jw.obj().put("name", s.name).put("amount", Math.round(s.amount)).put("day", s.day).put("months", s.months).put("ott", s.ott).put("cat", s.cat)
                    .put("yearly", Math.round(s.amount * 12)).put("mark", marks == null ? "" : marks.getOrDefault(s.name, "")));
        return a;
    }

    // ================================================================ alerts

    /** A debit much bigger than his usual (or a big one in the night) since {@code since}: ask "was this you?". */
    static List<FinTxn> unusual(List<FinTxn> all, long since, ZoneId z) {
        List<Double> past = new ArrayList<>();
        long from = since - 60L * 86_400_000L;
        for (FinTxn x : all) if (!x.credit && x.t >= from && x.t < since && x.spending()) past.add(x.amt);
        Collections.sort(past);
        double p90 = past.isEmpty() ? 0 : past.get(Math.min(past.size() - 1, (int) Math.floor(past.size() * 0.9)));
        double bar = Math.max(5000, p90 * 3);
        List<FinTxn> out = new ArrayList<>();
        for (FinTxn x : all) {
            if (x.t < since || x.credit || !"sms".equals(x.src)) continue;
            if ("emi".equals(x.cat) || "rent".equals(x.cat) || "invest".equals(x.cat) || "card_bill".equals(x.cat) || "self".equals(x.cat)
                    || "insurance".equals(x.cat)) continue;
            int h = Instant.ofEpochMilli(x.t).atZone(z).getHour();
            boolean night = h < 5 && x.amt >= 2000;
            if (x.amt >= bar || night) out.add(x);
        }
        return out;
    }

    static String unusualText(FinTxn x, ZoneId z) {
        LocalDateTime d = LocalDateTime.ofInstant(Instant.ofEpochMilli(x.t), z);
        String where = x.payee.isEmpty() ? "" : x.payee + " కి ";
        return FinMath.rs(x.amt) + " " + where + "వెళ్ళింది" + (x.bank.isEmpty() ? "" : " (" + x.bank + ")") + ", "
                + String.format(Locale.ROOT, "%d:%02d", d.getHour(), d.getMinute()) + " కి. ఇది మీరే చేశారా? కాకపోతే వెంటనే బ్యాంక్ యాప్‌లో కార్డ్ / UPI ఆపి, "
                + "బ్యాంక్‌కి, 1930 (సైబర్ క్రైమ్) కి కాల్ చేయండి.";
    }

    /** This month's salary credit (his "salary" SMS, or a credit near his salary day close to his income). */
    static FinTxn salary(List<FinTxn> all, Profile p, YearMonth m, ZoneId z) {
        FinTxn best = null;
        for (FinTxn x : in(all, m, z)) {
            if (!x.income()) continue;
            boolean word = "salary".equals(x.cat);
            boolean near = p.income > 0 && x.amt >= p.income * 0.7 && x.amt <= p.income * 1.5
                    && (p.salaryDay <= 0 || Math.abs(Instant.ofEpochMilli(x.t).atZone(z).getDayOfMonth() - p.salaryDay) <= 4);
            if ((word || near) && (best == null || x.amt > best.amt)) best = x;
        }
        return best;
    }

    static String salaryText(FinTxn s, Profile p) {
        double save = p.saveGoal > 0 ? p.saveGoal : Math.round(s.amt * 0.2 / 500) * 500;
        return "💰 జీతం వచ్చింది: " + FinMath.rs(s.amt) + ". ముందు పొదుపు: ఈరోజే " + FinMath.rs(save) + " వేరుగా పెట్టండి (RD / SIP / వేరే ఖాతా)"
                + (p.saveGoal > 0 ? " (మీ లక్ష్యం)" : " (జీతంలో 20%)") + ". మిగిలిన " + FinMath.rs(s.amt - save) + " తో ఈ నెల గడపండి.";
    }

    // ================================================================ the safety net

    static Jw protection(Profile p, double liquid, double monthlySpend, List<Loan> loans, double cardDue) {
        Jw a = Jw.arr();
        double months = monthlySpend > 0 ? liquid / monthlySpend : -1;
        String st;
        if (monthlySpend <= 0) st = "unknown";
        else if (months >= p.efMonths) st = "ok";
        else if (months >= 3) st = "warn";
        else st = "bad";
        double need = Math.max(0, monthlySpend * p.efMonths - liquid);
        a.add(item("ef", "🧯", "అత్యవసర నిధి", st,
                monthlySpend <= 0 ? "నెల ఖర్చు ఇంకా తెలియదు (బ్యాంక్ SMS 1 నెల తర్వాత)."
                        : "చేతిలో / సేవింగ్స్‌లో / FD లో " + FinMath.rs(liquid) + " = సుమారు " + String.format(Locale.ROOT, "%.1f", months) + " నెలల ఖర్చు. లక్ష్యం "
                        + p.efMonths + " నెలలు (" + FinMath.rs(monthlySpend * p.efMonths) + ")" + (need > 0 ? "; ఇంకా " + FinMath.rs(need) + "." : ". ✅"),
                "ఉద్యోగం ఆగినా, ఆసుపత్రి ఖర్చు వచ్చినా అప్పు చేయకుండా గడపడానికి. సేవింగ్స్ / స్వీప్ FD / లిక్విడ్ ఫండ్‌లో, షేర్లలో కాదు."));
        double annual = p.income * 12;
        if (p.dependents > 0) {
            double want = annual * 10;
            String s2 = p.term <= 0 ? "bad" : p.term >= want ? "ok" : "warn";
            a.add(item("term", "🛡️", "టర్మ్ లైఫ్ ఇన్సూరెన్స్", annual <= 0 ? (p.term > 0 ? "ok" : "unknown") : s2,
                    (p.term > 0 ? "ఉన్న కవర్ " + FinMath.words(p.term) + ". " : "టర్మ్ ఇన్సూరెన్స్ లేదు. ")
                            + (annual > 0 ? "మీ మీద " + p.dependents + " మంది ఆధారపడితే సాధారణంగా ఏడాది ఆదాయానికి 10–15 రెట్లు: సుమారు " + FinMath.words(want) + "." : ""),
                    "స్వచ్ఛమైన టర్మ్ ప్లాన్ (డబ్బు తిరిగి ఇవ్వనిది) తక్కువ ప్రీమియంతో పెద్ద కవర్ ఇస్తుంది. ఇన్సూరెన్స్ + పెట్టుబడి కలిపిన పాలసీలు సాధారణంగా రెండింటిలోనూ బలహీనం."));
        } else {
            a.add(item("term", "🛡️", "టర్మ్ లైఫ్ ఇన్సూరెన్స్", p.term > 0 ? "ok" : "unknown",
                    p.term > 0 ? "ఉన్న కవర్ " + FinMath.words(p.term) + "." : "మీ మీద ఎవరైనా ఆధారపడితే (కుటుంబం, లోన్) టర్మ్ కవర్ కావాలి. ⚙️ లో ఎంతమంది ఆధారపడతారో పెట్టండి.",
                    "ఎవరూ ఆధారపడకపోతే, పెద్ద లోన్ లేకపోతే ఇప్పుడే అవసరం తక్కువ."));
        }
        String hs = p.health <= 0 ? "bad" : p.health >= 10_00_000 ? "ok" : "warn";
        a.add(item("health", "🏥", "హెల్త్ ఇన్సూరెన్స్", hs,
                p.health > 0 ? "ఉన్న కవర్ " + FinMath.words(p.health) + (p.healthFamily ? " (కుటుంబానికి)" : " (మీకు మాత్రమే)") + "." : "హెల్త్ ఇన్సూరెన్స్ లేదని పెట్టారు.",
                "ఒక్క ఆసుపత్రి బిల్లు ఏళ్ళ పొదుపుని తినేస్తుంది. కుటుంబానికి సాధారణంగా ₹10 లక్షల పైన; తల్లిదండ్రులకి (60+) వేరే పాలసీ మంచిది. "
                        + "ఆఫీస్ ఇన్సూరెన్స్ ఉన్నా ఉద్యోగం మారితే పోతుంది: సొంతది ఒకటి ఉంచండి."));
        double costly = cardDue;
        StringBuilder names = new StringBuilder();
        for (Loan l : loans) {
            if (l.rate >= 14 && l.outstanding > 0) {
                costly += l.outstanding;
                if (names.length() > 0) names.append(", ");
                names.append(l.name).append(" ").append(trim(l.rate)).append("%");
            }
        }
        if (cardDue > 0) names.insert(0, "క్రెడిట్ కార్డ్ బాకీ " + FinMath.rs(cardDue) + (names.length() > 0 ? ", " : ""));
        a.add(item("debt", "💳", "ఎక్కువ వడ్డీ అప్పులు", costly > 0 ? "bad" : "ok",
                costly > 0 ? "తీర్చాల్సినవి: " + names + " (మొత్తం " + FinMath.rs(costly) + ")." : "ఎక్కువ వడ్డీ (14% పైన) అప్పులు లేవు ✅",
                "క్రెడిట్ కార్డ్ వడ్డీ నెలకి సుమారు 3–3.75% (ఏడాదికి 40% పైన). ఏ పెట్టుబడీ దీన్ని దాటదు: ముందు ఇవి తీర్చండి. కార్డ్ బిల్లు ఎప్పుడూ పూర్తిగా కట్టండి, మినిమం కాదు."));
        return a;
    }

    private static Jw item(String id, String icon, String title, String st, String now, String why) {
        return Jw.obj().put("id", id).put("icon", icon).put("title", title).put("st", st).put("now", now).put("why", why);
    }

    // ================================================================ what to do first

    static Jw priorities(Profile p, double liquid, double monthlySpend, List<Loan> loans, double cardDue, List<Goal> goals, double sipTotal) {
        Jw a = Jw.arr();
        int n = 0;
        double months = monthlySpend > 0 ? liquid / monthlySpend : -1;
        if (p.income <= 0) a.add(step(++n, "⚙️ నా వివరాలు లో నెల జీతం (చేతికి వచ్చేది), జీతం రోజు పెట్టండి: అప్పుడే మిగతా లెక్కలు సరిగా వస్తాయి."));
        if (cardDue > 0) a.add(step(++n, "💳 క్రెడిట్ కార్డ్ బాకీ " + FinMath.rs(cardDue) + " ముందు పూర్తిగా తీర్చండి (వడ్డీ ఏడాదికి 40% పైన)."));
        for (Loan l : loans) if (l.rate >= 14 && l.outstanding > 0)
            a.add(step(++n, "🏦 " + l.name + " (" + trim(l.rate) + "%) త్వరగా తీర్చే ప్లాన్: 💳 అప్పులు లో \"ముందే కడితే\" చూడండి."));
        if (months >= 0 && months < 3) a.add(step(++n, "🧯 అత్యవసర నిధి ముందు 3 నెలల ఖర్చు (" + FinMath.rs(monthlySpend * 3) + ") వరకు: ప్రతి నెల జీతం రాగానే కొంత."));
        if (p.health <= 0) a.add(step(++n, "🏥 హెల్త్ ఇన్సూరెన్స్ (కుటుంబానికి) తీసుకోండి."));
        if (p.dependents > 0 && p.term <= 0) a.add(step(++n, "🛡️ టర్మ్ ఇన్సూరెన్స్ (ఆధారపడేవాళ్ళ కోసం)."));
        if (months >= 3 && months < p.efMonths) a.add(step(++n, "🧯 అత్యవసర నిధి " + p.efMonths + " నెలల వరకు పెంచండి."));
        if (goals.isEmpty()) a.add(step(++n, "🎯 మీ లక్ష్యాలు (ఇల్లు, బండి, పిల్లల చదువు, రిటైర్మెంట్) రాయండి: ఒక్కోదానికి నెలకి ఎంత పెట్టాలో Jarvis లెక్కిస్తుంది."));
        if (p.income > 0 && sipTotal < p.income * 0.1) a.add(step(++n, "📈 నెల జీతంలో కనీసం 10–20% దీర్ఘకాల పెట్టుబడికి (జీతం రోజే, ఆటోమేటిక్‌గా)."));
        a.add(step(++n, "📅 ప్రతి నెల 1న ఈ స్క్రీన్‌లో నెల రిపోర్ట్ చూడండి; వేస్ట్ ఖర్చుల లిస్ట్ తగ్గుతుందా చూడండి."));
        return a;
    }

    private static Jw step(int n, String te) { return Jw.obj().put("n", n).put("te", te); }

    // ================================================================ goals

    /** Assumed yearly returns by how far away a goal is (shown as assumptions, never promises). */
    static double assumed(double years, String risk) {
        double r = years <= 3 ? 6.5 : years <= 7 ? 8.5 : 10.0;
        if ("low".equals(risk)) r -= years <= 3 ? 0 : 1.0;
        if ("high".equals(risk) && years > 7) r += 1.0;
        return r;
    }

    static Jw goals(List<Goal> goals, List<Holding> hs, Profile p, LocalDate today) {
        Jw a = Jw.arr();
        for (Goal g : goals) {
            double have = g.saved;
            for (Holding h : hs) if (g.id.equals(h.goal)) have += h.now();
            double years = yearsTo(g.by, today);
            double r = assumed(years, p.risk);
            double fut = FinMath.future(g.target, INFLATION, years);
            double monthly = FinMath.goalMonthly(fut, have, r, years);
            double pct = fut > 0 ? Math.min(100, have * 100 / fut) : 0;
            a.add(Jw.obj().put("id", g.id).put("name", g.name).put("kind", g.kind).put("by", g.by).put("target", Math.round(g.target))
                    .put("future", Math.round(fut)).put("have", Math.round(have)).put("years", Math.round(years * 10) / 10.0).put("assumed", r)
                    .put("monthly", Math.round(monthly)).put("pct", Math.round(pct)).put("fit", fitTe(years, p.risk))
                    .put("te", g.name + ": ఈరోజు ధర " + FinMath.rs(g.target) + " → " + trim(years) + " ఏళ్ళలో సుమారు " + FinMath.rs(fut) + " (ధరలు ఏడాదికి "
                            + trim(INFLATION) + "% పెరిగితే). ఇప్పటికి " + FinMath.rs(have) + ". నెలకి సుమారు " + FinMath.rs(monthly)
                            + " (ఏడాదికి " + trim(r) + "% రాబడి అనుకుంటే; ఇది అంచనా, హామీ కాదు)."));
        }
        return a;
    }

    /** The kinds of options that usually suit this much time (names of kinds only, never a fund or company). */
    static String fitTe(double years, String risk) {
        StringBuilder b = new StringBuilder();
        for (String g : fit(years, risk)) for (String[] x : GUIDE) if (x[0].equals(g)) { if (b.length() > 0) b.append(", "); b.append(x[1]); }
        return b.toString();
    }

    static double yearsTo(String by, LocalDate today) {
        try {
            YearMonth m = YearMonth.parse(by.trim());
            double months = ChronoUnit.MONTHS.between(YearMonth.from(today), m);
            return Math.max(0.25, months / 12.0);
        } catch (Exception e) {
            return 5;
        }
    }

    // ================================================================ investments and net worth

    static String kindTe(String k) {
        switch (k == null ? "" : k) {
            case "fd": return "🏦 బ్యాంక్ FD";
            case "rd": return "🏦 RD";
            case "ppf": return "🏛️ PPF";
            case "epf": return "🏛️ EPF / PF";
            case "nps": return "🏛️ NPS";
            case "post": return "📮 పోస్టాఫీస్ స్కీమ్";
            case "ssy": return "👧 సుకన్య సమృద్ధి";
            case "mf": return "📈 మ్యూచువల్ ఫండ్";
            case "stock": return "📊 షేర్లు";
            case "gold": return "🪙 బంగారం";
            case "lic": return "🛡️ LIC / ఇన్సూరెన్స్ పాలసీ (డబ్బు తిరిగి వచ్చేది)";
            case "chit": return "🤝 చిట్టీ";
            case "land": return "🏡 స్థలం / ఇల్లు";
            case "savings": return "💵 సేవింగ్స్ / క్యాష్";
            default: return "📦 ఇతరం";
        }
    }

    static Jw holdings(List<Holding> hs, LocalDate today) {
        Jw a = Jw.arr();
        for (Holding h : hs) {
            double now = h.now();
            Jw o = Jw.obj().put("id", h.id).put("kind", h.kind).put("kind_te", kindTe(h.kind)).put("name", h.name).put("invested", Math.round(h.invested))
                    .put("now", Math.round(now)).put("gain", Math.round(now - h.invested)).put("sip", Math.round(h.sip)).put("sip_day", h.sipDay)
                    .put("rate", h.rate).put("units", h.units).put("price", h.price).put("price_at", h.priceAt).put("code", h.code)
                    .put("start", h.start).put("maturity", h.maturity).put("goal", h.goal).put("emergency", h.emergency);
            if (h.invested > 0) o.put("gain_pct", Math.round((now - h.invested) * 1000 / h.invested) / 10.0);
            if (!h.maturity.isEmpty()) {
                try {
                    long d = ChronoUnit.DAYS.between(today, LocalDate.parse(h.maturity));
                    o.put("days_to_maturity", d);
                } catch (Exception ignored) {}
            }
            a.add(o);
        }
        return a;
    }

    /** Savings-account balances + emergency-marked holdings (and FDs / RDs / liquid money). */
    static double liquid(Map<String, Double> balances, List<Holding> hs) {
        double s = 0;
        for (double v : balances.values()) s += Math.max(0, v);
        for (Holding h : hs) if (h.emergency || "savings".equals(h.kind)) s += h.now();
        return s;
    }

    static Jw netWorth(Map<String, Double> balances, List<Holding> hs, List<Loan> loans, double cardDue, double lentOut) {
        double bank = 0;
        for (double v : balances.values()) bank += Math.max(0, v);
        double inv = 0;
        Map<String, Double> by = new LinkedHashMap<>();
        for (Holding h : hs) {
            inv += h.now();
            by.merge(h.kind, h.now(), Double::sum);
        }
        double owe = cardDue;
        for (Loan l : loans) owe += Math.max(0, l.outstanding);
        double total = bank + inv + lentOut - owe;
        Jw k = Jw.arr();
        for (Map.Entry<String, Double> e : by.entrySet())
            k.add(Jw.obj().put("kind", e.getKey()).put("te", kindTe(e.getKey())).put("amount", Math.round(e.getValue()))
                    .put("pct", inv > 0 ? Math.round(e.getValue() * 100 / inv) : 0));
        return Jw.obj().put("bank", Math.round(bank)).put("invested", Math.round(inv)).put("lent", Math.round(lentOut)).put("owe", Math.round(owe))
                .put("total", Math.round(total)).put("by_kind", k);
    }

    // ================================================================ the investment guide (facts and fit, never "buy this")

    /** {id, Telugu name, what it is, risk 1–5, when he can take it out, tax, good for, careful, rate id or "" } */
    static final String[][] GUIDE = {
            {"savings", "సేవింగ్స్ ఖాతా / స్వీప్ FD", "రోజువారీ డబ్బు, అత్యవసర నిధి. స్వీప్-ఇన్ FD పెడితే ఎక్కువ డబ్బుకి FD వడ్డీ, అవసరమైనప్పుడు వెంటనే తీసుకోవచ్చు.",
                    "1", "ఎప్పుడైనా", "వడ్డీ మీ ఆదాయంలో కలుస్తుంది", "అత్యవసర నిధి, వచ్చే కొన్ని నెలల ఖర్చులు", "ద్రవ్యోల్బణం కంటే తక్కువ రాబడి: ఎక్కువ డబ్బు ఇక్కడే ఉంచకండి", "posb"},
            {"fd", "బ్యాంక్ FD", "నిర్ణీత కాలానికి నిర్ణీత వడ్డీ. బ్యాంక్‌కి ₹5 లక్షల వరకు DICGC బీమా.", "1", "కాలం ముందే తీస్తే కొంచెం వడ్డీ కోత",
                    "వడ్డీ మీ ఆదాయంలో కలుస్తుంది (స్లాబ్ ప్రకారం); 5 ఏళ్ళ ట్యాక్స్-సేవర్ FD పాత విధానంలో 80C", "1–3 ఏళ్ళ లక్ష్యాలు, అత్యవసర నిధిలో కొంత",
                    "రేటు బ్యాంక్ బట్టి మారుతుంది: మీ బ్యాంక్ యాప్‌లో చూడండి", "td1"},
            {"rd", "RD (బ్యాంక్ / పోస్టాఫీస్)", "ప్రతి నెల కొంత, నిర్ణీత వడ్డీ. పొదుపు అలవాటుకి సులభం.", "1", "కాలం అయ్యాక (ముందే తీస్తే కోత)",
                    "వడ్డీ ఆదాయంలో కలుస్తుంది", "1–5 ఏళ్ళ చిన్న లక్ష్యాలు (బైక్, పండుగ, ఫీజులు)", "ఒక నెల కట్టకపోతే ఫైన్", "rd5"},
            {"ppf", "PPF", "ప్రభుత్వ పథకం, 15 ఏళ్ళు. ఏడాదికి ₹500 నుంచి ₹1.5 లక్షల వరకు.", "1", "15 ఏళ్ళు (7వ ఏడాది నుంచి కొంత తీసుకోవచ్చు)",
                    "వడ్డీ, మెచ్యూరిటీ పన్ను లేదు; పాత విధానంలో 80C", "దీర్ఘకాలం సురక్షిత భాగం (రిటైర్మెంట్, పిల్లల చదువు)",
                    "డబ్బు చాలా ఏళ్ళు ఇరుక్కుంటుంది; ప్రతి ఏప్రిల్ 5 లోపు కడితే ఆ ఏడాది వడ్డీ పూర్తి", "ppf"},
            {"epf", "EPF / VPF", "జీతం నుంచి PF. VPF తో మీ వాటా పెంచొచ్చు.", "1", "ఉద్యోగం / రిటైర్మెంట్ (కొన్ని అవసరాలకి ముందే)",
                    "ఏడాదికి ₹2.5 లక్షల పైన మీ వాటా వడ్డీకి పన్ను", "రిటైర్మెంట్ సురక్షిత భాగం", "ఉద్యోగం మారినప్పుడు PF బదిలీ చేయించండి, తీసేయకండి", "epf"},
            {"nps", "NPS", "రిటైర్మెంట్ కోసం ప్రభుత్వ పెన్షన్ పథకం: షేర్లు + బాండ్ల మిశ్రమం, చాలా తక్కువ ఖర్చు.", "3", "60 ఏళ్ళకి (అప్పుడు కొంత పెన్షన్‌గా తప్పనిసరి)",
                    "పాత విధానంలో అదనంగా ₹50,000 మినహాయింపు; కంపెనీ వాటాకి రెండు విధానాల్లో", "రిటైర్మెంట్ మాత్రమే", "60 వరకు తీయలేరు", ""},
            {"post", "పోస్టాఫీస్ పథకాలు (TD, MIS, NSC, KVP)", "ప్రభుత్వ హామీతో నిర్ణీత వడ్డీ. MIS = ప్రతి నెల వడ్డీ, NSC = 5 ఏళ్ళు, KVP = డబ్బు రెట్టింపు (సుమారు 9 ఏళ్ళ 7 నెలలు).",
                    "1", "పథకం బట్టి 1–10 ఏళ్ళు", "వడ్డీ ఆదాయంలో కలుస్తుంది (NSC, 5 ఏళ్ళ TD పాత విధానంలో 80C)", "3–5 ఏళ్ళ సురక్షిత లక్ష్యాలు, నెల ఆదాయం (MIS)",
                    "రేట్లు ప్రతి 3 నెలలకి ప్రభుత్వం మారుస్తుంది", "td5"},
            {"scss", "సీనియర్ సిటిజన్ సేవింగ్స్ (SCSS)", "60 ఏళ్ళు పైబడినవాళ్ళకి: ప్రతి 3 నెలలకి వడ్డీ, ప్రభుత్వ హామీ.", "1", "5 ఏళ్ళు",
                    "వడ్డీ ఆదాయంలో కలుస్తుంది", "తల్లిదండ్రుల (60+) రిటైర్మెంట్ ఆదాయం", "60 ఏళ్ళ లోపు వారికి కాదు", "scss"},
            {"ssy", "సుకన్య సమృద్ధి", "ఆడపిల్ల (10 ఏళ్ళ లోపు) పేరు మీద: ఎక్కువ వడ్డీ, ప్రభుత్వ హామీ.", "1", "21 ఏళ్ళు (18 తర్వాత చదువుకి కొంత)",
                    "పన్ను లేదు; పాత విధానంలో 80C", "ఆడపిల్ల చదువు / పెళ్ళి", "ఆడపిల్ల ఉంటేనే", "ssy"},
            {"liquid", "లిక్విడ్ / ఓవర్‌నైట్ ఫండ్", "చాలా తక్కువ రిస్క్ డెట్ ఫండ్: 1–2 రోజుల్లో డబ్బు తిరిగి.", "1", "1–2 పని దినాల్లో",
                    "లాభం ఆదాయంలో కలుస్తుంది", "అత్యవసర నిధిలో ఒక భాగం", "హామీ రాబడి కాదు (చాలా స్థిరంగా ఉంటుంది)", ""},
            {"hybrid", "హైబ్రిడ్ / బ్యాలెన్స్‌డ్ అడ్వాంటేజ్ ఫండ్", "షేర్లు + బాండ్ల కలయిక: షేర్ల కంటే తక్కువ ఊగిసలాట.", "3", "ఎప్పుడైనా (1 ఏడాది లోపు తీస్తే చిన్న ఫీ ఉండొచ్చు)",
                    "ఫండ్ రకం బట్టి", "3–7 ఏళ్ళ లక్ష్యాలు", "నష్టం కూడా రావచ్చు; 3 ఏళ్ళకి తక్కువ డబ్బు పెట్టకండి", ""},
            {"index", "ఇండెక్స్ ఫండ్ (Nifty 50 / Sensex) SIP", "దేశంలో పెద్ద 50 కంపెనీలన్నీ కొద్దికొద్దిగా: తక్కువ ఖర్చు, ఫండ్ మేనేజర్ ఊహ అక్కర్లేదు.",
                    "4", "ఎప్పుడైనా అమ్మొచ్చు; కానీ 7+ ఏళ్ళు ఉంచేందుకే", "ఏడాది పైగా ఉంచితే ₹1.25 లక్షల పైన లాభానికి 12.5%; ఏడాది లోపు 20%",
                    "7+ ఏళ్ళ లక్ష్యాలు, రిటైర్మెంట్", "కొన్ని ఏళ్ళు 20–40% పడిపోవచ్చు: పడినప్పుడు ఆపకండి, అమ్మకండి; \"Direct\" ప్లాన్ ఖర్చు తక్కువ", ""},
            {"elss", "ELSS (ట్యాక్స్ సేవర్ ఫండ్)", "షేర్ ఫండ్: 3 ఏళ్ళ లాక్.", "4", "ప్రతి SIP కి 3 ఏళ్ళు",
                    "పాత విధానంలో 80C; కొత్త విధానంలో పన్ను లాభం లేదు", "పాత విధానం ఎంచుకుంటే 80C + దీర్ఘకాలం", "కొత్త పన్ను విధానంలో ప్రత్యేక లాభం లేదు", ""},
            {"stocks", "నేరుగా షేర్లు", "ఒక్కో కంపెనీ షేర్: ఎక్కువ లాభం / నష్టం రెండూ.", "5", "ఎప్పుడైనా అమ్మొచ్చు",
                    "ఇండెక్స్ ఫండ్ లాగే", "నేర్చుకున్న తర్వాత, పెట్టుబడిలో చిన్న భాగం మాత్రమే",
                    "టిప్స్ / టెలిగ్రామ్ గ్రూప్‌లు / F&O వద్దు: చాలామంది నష్టపోతారని SEBI చెప్పింది", ""},
            {"gold", "బంగారం (Gold ETF / Gold ఫండ్ / నగలు)", "ధరల పెరుగుదలకి రక్షణ. ETF / ఫండ్‌లో మజూరీ, తరుగు ఉండదు.", "3", "ఎప్పుడైనా",
                    "రకం బట్టి", "పెట్టుబడిలో 5–10% వరకు", "నగలలో మజూరీ / తరుగు నష్టం; బంగారం కొన్ని ఏళ్ళు పెరగకపోవచ్చు", ""},
            {"insplan", "LIC / ఎండోమెంట్ / మనీ-బ్యాక్ / ULIP", "ఇన్సూరెన్స్ + పెట్టుబడి కలిపినవి.", "2", "పాలసీ కాలం (మధ్యలో ఆపితే నష్టం)",
                    "రకం బట్టి", "సాధారణంగా రెండు పనులూ బలహీనంగా చేస్తాయి", "టర్మ్ ఇన్సూరెన్స్ + వేరుగా పెట్టుబడి సాధారణంగా మెరుగు; ఉన్న పాలసీ ఆపేముందు నష్టం లెక్కించండి", ""},
            {"chit", "చిట్టీ", "గుంపు పొదుపు + అవసరానికి అప్పు.", "3", "చిట్టీ కాలం",
                    "లాభం ఆదాయంలో కలుస్తుంది", "తెలిసిన, రిజిస్టర్ అయిన చిట్ కంపెనీతో మాత్రమే", "రిజిస్టర్ కాని చిట్టీలు పారిపోయే ప్రమాదం: రిజిస్ట్రేషన్ చూపించమనండి", ""},
    };

    /** Which kinds fit a goal this far away at his comfort with ups and downs (education only, never a product). */
    static String[] fit(double years, String risk) {
        if (years < 1) return new String[]{"savings", "fd", "liquid"};
        if (years <= 3) return new String[]{"fd", "rd", "post", "liquid"};
        if (years <= 7) return "low".equals(risk) ? new String[]{"post", "fd", "ppf", "hybrid"} : new String[]{"hybrid", "post", "index", "ppf"};
        return "low".equals(risk) ? new String[]{"ppf", "epf", "index", "nps"} : new String[]{"index", "ppf", "nps", "epf", "gold"};
    }

    static Jw guide() {
        Jw a = Jw.arr();
        for (String[] g : GUIDE) {
            Jw o = Jw.obj().put("id", g[0]).put("name", g[1]).put("what", g[2]).put("risk", Integer.parseInt(g[3])).put("out", g[4]).put("tax", g[5])
                    .put("for", g[6]).put("careful", g[7]);
            if (!g[8].isEmpty()) o.put("rate", FinMath.rate(g[8]));
            a.add(o);
        }
        return a;
    }

    static Jw rates() {
        Jw a = Jw.arr();
        for (String[] r : FinMath.RATES) a.add(Jw.obj().put("id", r[0]).put("name", r[1]).put("rate", Double.parseDouble(r[2])));
        return Jw.obj().put("date", FinMath.RATES_DATE).put("source", FinMath.RATES_SOURCE).put("list", a);
    }

    // ================================================================ "can I buy this?"

    static Jw canBuy(double price, String what, Profile p, double leftThisMonth, double liquid, double monthlySpend, double cardDue, double monthlySaveCapacity) {
        List<String> why = new ArrayList<>();
        String verdict;
        double ef = monthlySpend > 0 ? (liquid - price) / monthlySpend : 99;
        if (cardDue > 0) why.add("క్రెడిట్ కార్డ్ బాకీ " + FinMath.rs(cardDue) + " ఉంది: ముందు అది (40%+ వడ్డీ).");
        if (leftThisMonth >= 0 && price <= leftThisMonth * 0.5 && cardDue <= 0) {
            verdict = "ok";
            why.add("ఈ నెల ప్లాన్‌లో ఇంకా " + FinMath.rs(leftThisMonth) + " ఉంది: ఇది సరిపోతుంది.");
        } else if (leftThisMonth >= 0 && price <= leftThisMonth && cardDue <= 0) {
            verdict = "careful";
            why.add("ఈ నెల మిగిలిన " + FinMath.rs(leftThisMonth) + " లో ఇది " + Math.round(price * 100 / Math.max(1, leftThisMonth)) + "%: కొంటే మిగతా రోజులు గట్టిగా ఉండాలి.");
        } else if (monthlySaveCapacity > 0) {
            verdict = "wait";
            double months = Math.ceil(price / monthlySaveCapacity);
            why.add("ఈ నెల ప్లాన్‌లో సరిపోదు. నెలకి " + FinMath.rs(monthlySaveCapacity) + " దాస్తే " + (long) months + " నెలల్లో కొనొచ్చు (RD / వేరే ఖాతాలో పెట్టి).");
        } else {
            verdict = "wait";
            why.add("ఈ నెల ప్లాన్ ఇప్పటికే అయిపోయింది / తెలియదు: వచ్చే జీతం తర్వాత చూడండి.");
        }
        if (monthlySpend > 0 && ef < 3) {
            if (!"wait".equals(verdict)) verdict = "careful";
            why.add("కొంటే అత్యవసర నిధి " + String.format(Locale.ROOT, "%.1f", Math.max(0, ef)) + " నెలలకి పడిపోతుంది (కనీసం 3 నెలలు ఉండాలి).");
        }
        if (price >= 5000) why.add("₹5,000 పైన కొనేముందు 24 గంటలు ఆగండి; 2–3 చోట్ల ధర పోల్చండి (\"Jarvis, … ధర ఎక్కడ తక్కువ\").");
        if (price >= 10000) why.add("\"నో-కాస్ట్ EMI\" లో ప్రాసెసింగ్ ఫీ, GST, డిస్కౌంట్ పోవడం దాగి ఉంటాయి: మొత్తం ఎంత కడతారో చూడండి.");
        Jw w = Jw.arr();
        for (String s : why) w.add(s);
        String head = "ok".equals(verdict) ? "✅ కొనొచ్చు" : "careful".equals(verdict) ? "🟡 కొనొచ్చు, కానీ జాగ్రత్త" : "⏳ ఇప్పుడు వద్దు, కొంచెం ఆగండి";
        return Jw.obj().put("what", what == null ? "" : what).put("price", Math.round(price)).put("verdict", verdict).put("head", head).put("why", w);
    }

    // ================================================================ is this scheme real?

    private static final String[][] FLAGS = {
            {"(guarantee|guaranteed|assured|fixed returns?|హామీ|గ్యారంటీ|risk[- ]?free|no risk|100% safe)", "\"హామీ లాభం / రిస్క్ లేదు\": మార్కెట్ పెట్టుబడిలో ఎవరూ హామీ ఇవ్వలేరు."},
            {"(double|triple|2x|3x|రెట్టింపు|డబుల్)", "\"డబ్బు రెట్టింపు\" త్వరగా: ఇది మోసాల ముఖ్య గుర్తు."},
            {"(\\d+(\\.\\d+)?\\s*%\\s*(per|a|every|/)\\s*(month|week|day)|నెలకి\\s*\\d+\\s*%|daily (income|profit|return)|రోజుకి\\s*₹?\\d+)", "నెల / వారం / రోజు లెక్కన లాభం: నిజమైన పెట్టుబడులు ఇలా ఉండవు."},
            {"(today only|limited (seats|time|slots)|last chance|hurry|urgent|ఈరోజే|త్వరగా చేరండి|offer ends)", "\"ఈరోజే చేరండి\" తొందర: ఆలోచించే సమయం ఇవ్వకపోవడం మోసగాళ్ళ పద్ధతి."},
            {"(refer|referral|join (members|team)|downline|level income|binary|chain|mlm|network marketing|members join)", "మనుషుల్ని చేర్పిస్తే లాభం: ఇది చైన్ / పోంజీ పద్ధతి."},
            {"(crypto|bitcoin|usdt|forex|binary option|trading signal|vip group|telegram group|whatsapp group|tips group|copy trad)", "క్రిప్టో / ఫారెక్స్ / సిగ్నల్ గ్రూప్‌లు: భారత్‌లో అనుమతి లేని ఫారెక్స్ ప్లాట్‌ఫారమ్‌లు చాలా (RBI హెచ్చరిక జాబితా)."},
            {"(registration fee|joining fee|processing fee|security deposit|unlock (your )?(money|withdrawal)|tax to withdraw|withdrawal fee)", "డబ్బు తీసుకోవడానికి ముందు ఫీ కట్టమనడం: మోసం."},
            {"(\\.apk|download (the )?app|bit\\.ly|tinyurl|t\\.me/|wa\\.me/)", "తెలియని యాప్ / లింక్: Play Store లో లేని APK ఎప్పుడూ ఇన్‌స్టాల్ చేయకండి."},
            {"(sebi approved|rbi approved|government approved|ప్రభుత్వ ఆమోదం|celebrity|endorsed by)", "\"SEBI / RBI ఆమోదం\" అని చెప్పుకోవడం: నిజంగా రిజిస్టర్ అయిందా మీరే చెక్ చేయండి."},
            {"(like (videos|posts)|rate (hotels|movies)|part[- ]?time (job|work)|work from home.*(earn|income)|task)", "\"లైక్ / రేటింగ్ టాస్క్‌లతో సంపాదన\": ఇది టాస్క్ మోసం."},
            {"(chit|చిట్టీ)(?!.*regist)", "చిట్టీ: రిజిస్టర్ అయిన చిట్ కంపెనీయేనా అడగండి (రిజిస్ట్రేషన్ నంబర్)."},
            {"(otp|upi pin|pin number|password|cvv)", "OTP / UPI PIN / పాస్‌వర్డ్ అడిగితే: ఎప్పుడూ చెప్పకండి."},
    };
    private static final Pattern[] FLAG_P = new Pattern[FLAGS.length];
    static {
        for (int i = 0; i < FLAGS.length; i++) FLAG_P[i] = Pattern.compile(FLAGS[i][0], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static Jw schemeCheck(String text) {
        String t = text == null ? "" : text;
        Jw f = Jw.arr();
        int n = 0;
        for (int i = 0; i < FLAGS.length; i++)
            if (FLAG_P[i].matcher(t).find()) { f.add(FLAGS[i][1]); n++; }
        // a yearly return way above safe rates
        java.util.regex.Matcher m = Pattern.compile("(\\d{2,3}(?:\\.\\d+)?)\\s*%\\s*(?:per annum|p\\.?a\\.?|a year|yearly|annual|ఏడాదికి)", Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.find()) {
            try {
                double r = Double.parseDouble(m.group(1));
                if (r >= 15) { f.add("ఏడాదికి " + trim(r) + "% \"స్థిరంగా\": సురక్షిత పథకాలు 7–8.5% దగ్గర ఉంటాయి; ఇంత ఎక్కువ హామీ అసాధ్యం."); n++; }
            } catch (Exception ignored) {}
        }
        String verdict = n >= 3 ? "bad" : n >= 1 ? "warn" : "none";
        String head = n >= 3 ? "🚨 మోసం అయ్యే అవకాశం చాలా ఎక్కువ: డబ్బు పెట్టకండి." : n >= 1 ? "⚠️ జాగ్రత్త: ప్రమాద సంకేతాలు ఉన్నాయి." :
                "పెద్ద ప్రమాద సంకేతాలు కనిపించలేదు: అయినా కింది చెక్‌లు చేయండి.";
        Jw how = Jw.arr()
                .add("SEBI సైట్ (sebi.gov.in → Intermediaries → Recognised Intermediaries → Investment Adviser / Research Analyst / Stock Broker): ఆ పేరు, నంబర్ ఉందా")
                .add("RBI సచేత్ (sachet.rbi.org.in): అనుమతి లేని డిపాజిట్ పథకాల గురించి చూడటం / ఫిర్యాదు")
                .add("మోసం జరిగితే వెంటనే 1930 కి కాల్ / cybercrime.gov.in లో ఫిర్యాదు")
                .add("ఎవరైనా సలహాదారు: SEBI రిజిస్ట్రేషన్ నంబర్ (INA…) అడగండి; ఫీ తీసుకునే RIA కమిషన్ తీసుకోకూడదు");
        return Jw.obj().put("verdict", verdict).put("head", head).put("flags", f).put("count", n).put("how", how);
    }

    static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
