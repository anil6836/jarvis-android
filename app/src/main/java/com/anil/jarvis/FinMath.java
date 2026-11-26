package com.anil.jarvis;

/**
 * 🧮 Money sums for Jarvis's money advisor: SIP, lump sum, FD, RD, PPF, EMI, prepaying a loan, inflation, a goal's monthly saving,
 * retirement, credit-card interest, and income tax (new and old regime). Plain maths with the rates he gives (or the dated
 * table below); the answers are estimates, never promises. Pure Java (tested on a PC).
 */
final class FinMath {
    private FinMath() {}

    /** Rates checked on 27 Nov 2026 (shown with this date; the government changes small-savings rates every 3 months). */
    static final String RATES_DATE = "27 నవంబర్ 2026";
    static final String RATES_SOURCE = "ఆర్థిక శాఖ ప్రకటన, 30 సెప్టెంబర్ 2026 (అక్టోబర్–డిసెంబర్ 2026); EPFO 2025-26";
    static final String[][] RATES = {
            {"ppf", "PPF", "7.1"}, {"ssy", "సుకన్య సమృద్ధి", "8.2"}, {"scss", "సీనియర్ సిటిజన్ (SCSS)", "8.2"}, {"nsc", "NSC", "7.7"},
            {"kvp", "కిసాన్ వికాస్ పత్ర (KVP)", "7.5"}, {"mis", "పోస్టాఫీస్ నెల ఆదాయం (MIS)", "7.4"}, {"td1", "పోస్టాఫీస్ 1 ఏడాది డిపాజిట్", "6.9"},
            {"td2", "పోస్టాఫీస్ 2 ఏళ్ళ డిపాజిట్", "7.0"}, {"td3", "పోస్టాఫీస్ 3 ఏళ్ళ డిపాజిట్", "7.1"}, {"td5", "పోస్టాఫీస్ 5 ఏళ్ళ డిపాజిట్", "7.5"},
            {"rd5", "పోస్టాఫీస్ 5 ఏళ్ళ RD", "6.7"}, {"posb", "పోస్టాఫీస్ సేవింగ్స్", "4.0"}, {"epf", "EPF (2025-26)", "8.25"},
    };

    static double rate(String id) {
        for (String[] r : RATES) if (r[0].equals(id)) return Double.parseDouble(r[2]);
        return Double.NaN;
    }

    /** SIP of {@code monthly} at the start of each month for {@code years}, at {@code ratePct} a year (monthly compounding). */
    static double sip(double monthly, double ratePct, double years) {
        int n = (int) Math.round(years * 12);
        double i = ratePct / 1200.0;
        if (i == 0) return monthly * n;
        return monthly * ((Math.pow(1 + i, n) - 1) / i) * (1 + i);
    }

    static double lumpsum(double amount, double ratePct, double years) {
        return amount * Math.pow(1 + ratePct / 100.0, years);
    }

    /** Bank FD: interest added every quarter (the usual Indian way). */
    static double fd(double amount, double ratePct, double years) {
        return amount * Math.pow(1 + ratePct / 400.0, 4 * years);
    }

    /** RD: a deposit every month, interest added every quarter (banks' and the post office's way). */
    static double rd(double monthly, double ratePct, int months) {
        double total = 0;
        for (int k = 1; k <= months; k++) total += monthly * Math.pow(1 + ratePct / 400.0, 4.0 * k / 12.0);
        return total;
    }

    /** PPF: a deposit each year before 5 April, interest once a year, 15 years. */
    static double ppf(double yearly, double ratePct, int years) {
        double bal = 0;
        for (int y = 0; y < years; y++) bal = (bal + yearly) * (1 + ratePct / 100.0);
        return bal;
    }

    static double emi(double principal, double ratePct, int months) {
        double i = ratePct / 1200.0;
        if (months <= 0) return 0;
        if (i == 0) return principal / months;
        double p = Math.pow(1 + i, months);
        return principal * i * p / (p - 1);
    }

    /**
     * Paying a loan faster: {@code extraNow} once now and / or {@code extraMonthly} each month on top of the EMI.
     * Returns {months left before, months left after, interest before, interest after}.
     */
    static double[] prepay(double outstanding, double ratePct, double emi, double extraNow, double extraMonthly) {
        double[] a = run(outstanding, ratePct, emi, 0);
        double[] b = run(Math.max(0, outstanding - extraNow), ratePct, emi + extraMonthly, 0);
        return new double[]{a[0], b[0], a[1], b[1]};
    }

    /** Months and total interest to clear a loan at this monthly payment (1200 months cap = it never clears). */
    private static double[] run(double bal, double ratePct, double pay, int unused) {
        double i = ratePct / 1200.0, interest = 0;
        int m = 0;
        while (bal > 0.5 && m < 1200) {
            double in = bal * i;
            if (pay <= in) return new double[]{1200, Double.POSITIVE_INFINITY};
            interest += in;
            bal = bal + in - pay;
            m++;
        }
        return new double[]{m, interest};
    }

    /** What today's {@code amount} will cost after {@code years} at {@code inflationPct}. */
    static double future(double amount, double inflationPct, double years) {
        return amount * Math.pow(1 + inflationPct / 100.0, years);
    }

    /** What {@code amount} after {@code years} is worth in today's rupees. */
    static double todayValue(double amount, double inflationPct, double years) {
        return amount / Math.pow(1 + inflationPct / 100.0, years);
    }

    /** The monthly SIP that reaches {@code target} (already in future rupees) after {@code years}, with {@code have} put in today. */
    static double goalMonthly(double target, double have, double ratePct, double years) {
        double left = target - lumpsum(have, ratePct, years);
        if (left <= 0) return 0;
        double one = sip(1, ratePct, years);
        return one <= 0 ? left : left / one;
    }

    /**
     * Retirement: today's monthly expense → the corpus needed at retirement (the 25-times rule of thumb on that year's
     * expense) and the monthly SIP to get there. Returns {years to go, monthly expense then, corpus, monthly SIP}.
     */
    static double[] retirement(int age, int retireAge, double monthlyExpense, double inflationPct, double ratePct, double have) {
        int years = Math.max(1, retireAge - age);
        double then = future(monthlyExpense, inflationPct, years);
        double corpus = then * 12 * 25;
        return new double[]{years, then, corpus, goalMonthly(corpus, have, ratePct, years)};
    }

    /** A credit-card balance left unpaid: about this much interest (monthly rate, e.g. 3.5) over {@code months}, nothing more spent. */
    static double cardInterest(double balance, double monthlyPct, int months) {
        return balance * (Math.pow(1 + monthlyPct / 100.0, months) - 1);
    }

    /** Years for money to double at this rate (the exact sum, not the 72 rule). */
    static double doubleYears(double ratePct) {
        return ratePct <= 0 ? Double.POSITIVE_INFINITY : Math.log(2) / Math.log(1 + ratePct / 100.0);
    }

    // ================================================================ income tax (tax year 2026-27, India)

    /** Tax in both regimes. {@code salaried}: the standard deduction applies. Deductions only count in the old regime. */
    static final class Tax {
        double grossIncome, oldTaxable, newTaxable, oldTax, newTax;
        boolean senior;
    }

    static final double NEW_STD = 75_000, OLD_STD = 50_000, NEW_REBATE_UPTO = 12_00_000, OLD_REBATE_UPTO = 5_00_000;

    /**
     * @param income       his yearly income (salary before tax plus other income)
     * @param salaried     standard deduction
     * @param d80c         80C (PF, PPF, ELSS, LIC, tuition, home-loan principal…), max 1.5 lakh (old only)
     * @param d80d         health insurance premiums (old only; he gives the allowed total)
     * @param nps          own NPS extra (old only, max 50,000)
     * @param homeInterest home-loan interest on a house he lives in (old only, max 2 lakh)
     * @param hra          HRA exempt amount (old only, he gives it)
     * @param other        other old-regime deductions he gives
     * @param age          for the old regime's senior slabs
     */
    static Tax tax(double income, boolean salaried, double d80c, double d80d, double nps, double homeInterest, double hra, double other, int age) {
        Tax t = new Tax();
        t.grossIncome = Math.max(0, income);
        t.senior = age >= 60;
        double newT = t.grossIncome - (salaried ? NEW_STD : 0);
        t.newTaxable = Math.max(0, newT);
        double ded = Math.min(1_50_000, Math.max(0, d80c)) + Math.max(0, d80d) + Math.min(50_000, Math.max(0, nps))
                + Math.min(2_00_000, Math.max(0, homeInterest)) + Math.max(0, hra) + Math.max(0, other) + (salaried ? OLD_STD : 0);
        t.oldTaxable = Math.max(0, t.grossIncome - ded);
        t.newTax = newTax(t.newTaxable);
        t.oldTax = oldTax(t.oldTaxable, age);
        return t;
    }

    /** New regime: 0–4 L nil, 4–8 5 %, 8–12 10 %, 12–16 15 %, 16–20 20 %, 20–24 25 %, above 30 %; nil up to 12 L (rebate) with marginal relief; 4 % cess. */
    static double newTax(double taxable) {
        double[][] s = {{4_00_000, 0}, {8_00_000, 5}, {12_00_000, 10}, {16_00_000, 15}, {20_00_000, 20}, {24_00_000, 25}, {Double.MAX_VALUE, 30}};
        double tax = slabs(taxable, s);
        if (taxable <= NEW_REBATE_UPTO) tax = 0;
        else tax = Math.min(tax, taxable - NEW_REBATE_UPTO); // (marginal relief just above 12 lakh)
        return Math.round(tax * 1.04);
    }

    /** Old regime: 0–2.5 L nil (3 L at 60+, 5 L at 80+), to 5 L 5 %, to 10 L 20 %, above 30 %; nil up to 5 L (rebate); 4 % cess. */
    static double oldTax(double taxable, int age) {
        double free = age >= 80 ? 5_00_000 : age >= 60 ? 3_00_000 : 2_50_000;
        double[][] s = age >= 80 ? new double[][]{{free, 0}, {10_00_000, 20}, {Double.MAX_VALUE, 30}}
                : new double[][]{{free, 0}, {5_00_000, 5}, {10_00_000, 20}, {Double.MAX_VALUE, 30}};
        double tax = slabs(taxable, s);
        if (taxable <= OLD_REBATE_UPTO) tax = 0;
        return Math.round(tax * 1.04);
    }

    private static double slabs(double x, double[][] s) {
        double tax = 0, lo = 0;
        for (double[] b : s) {
            if (x <= lo) break;
            tax += (Math.min(x, b[0]) - lo) * b[1] / 100.0;
            lo = b[0];
        }
        return tax;
    }

    /** "₹1,23,456" (Indian grouping). */
    static String rs(double v) {
        long n = Math.round(v);
        boolean neg = n < 0;
        String s = Long.toString(Math.abs(n));
        if (s.length() > 3) {
            String last = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder b = new StringBuilder();
            while (rest.length() > 2) {
                b.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            b.insert(0, rest);
            s = b + "," + last;
        }
        return (neg ? "−₹" : "₹") + s;
    }

    /** "1.2 లక్షలు" / "3.4 కోట్లు" for big sums said aloud. */
    static String words(double v) {
        double a = Math.abs(v);
        if (a >= 1_00_00_000) return trim(v / 1_00_00_000) + " కోట్లు";
        if (a >= 1_00_000) return trim(v / 1_00_000) + " లక్షలు";
        return rs(v);
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
