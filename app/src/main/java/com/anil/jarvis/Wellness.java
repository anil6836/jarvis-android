package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Phase 4 on the phone: the words for his health from the watch (its own sensors) and from Samsung Health (through
 * Health Connect). Kept on the phone; no AI is needed for any of it.
 *   W31 walking: each walk, today's steps / metres / km.   W25 the body scan.   W43 an energy estimate (Samsung keeps
 *   its own energy score to itself): last night's sleep, the resting heart rate against his normal, yesterday's stress
 *   and steps.   W28 / W48 last night: sleep with its stages, night coughs / snoring, night oxygen.   W45 the month's
 *   weight and body fat with Telugu tips.   W29 the watch's part of the doctor PDF.   W30 breathing done.
 * General guidance, never a diagnosis. Health Connect reads block: background threads only.
 */
final class Wellness {
    private Wellness() {}

    static final String WALKS = "walks", BREATHS = "breathing", FILES = "health_files";
    /** Metres a step: from his height when known (0.415 × height), else an adult's average (the watch's coach uses the same). */
    static volatile double stride = 0.72;
    private static final long DAY = 86400_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_wellness", Context.MODE_PRIVATE); }

    static long dayStart(LocalDate d) { return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }

    // ================================================================ words (pure: tested on a desk)

    /** "350 అడుగులు, 250 మీటర్లు, 4 నిమిషాలు" / "2,600 అడుగులు, 1.9 కి.మీ, 22 నిమిషాలు". */
    static String walkLine(long steps, long secs) {
        return Sums.num(steps) + " అడుగులు, " + distance(steps) + ", " + minutes(secs);
    }

    static String distance(long steps) {
        double m = steps * stride;
        if (m < 995) return Math.max(10, Math.round(m / 10.0) * 10) + " మీటర్లు";
        String km = String.format(Locale.ENGLISH, "%.1f", m / 1000.0);
        if (km.endsWith(".0")) km = km.substring(0, km.length() - 2);
        return km + " కి.మీ";
    }

    static String minutes(long secs) {
        long m = Math.max(1, Math.round(secs / 60.0));
        if (m < 60) return m + (m == 1 ? " నిమిషం" : " నిమిషాలు");
        long h = m / 60, r = m % 60;
        return h + (h == 1 ? " గంట" : " గంటలు") + (r == 0 ? "" : " " + r + (r == 1 ? " నిమిషం" : " నిమిషాలు"));
    }

    /** A km on the way: a short coach's word. */
    static String kmLine(int km, long secs) {
        String[] cheer = {"అలాగే కొనసాగించండి!", "బాగుంది!", "సూపర్, ఇలాగే!"};
        return km + " కి.మీ నడిచారు (" + minutes(secs) + "). " + cheer[Math.abs(km) % cheer.length];
    }

    /** "రాత్రి నిద్ర 6 గంటలు 40 నిమిషాలు (గాఢ నిద్ర 1 గంట 5 నిమిషాలు, REM 1 గంట 20 నిమిషాలు)". */
    static String sleepLine(JSONObject sl) {
        long m = sl.optLong("minutes"), deep = sl.optLong("deep"), rem = sl.optLong("rem");
        StringBuilder b = new StringBuilder("రాత్రి నిద్ర ").append(Status.hours(m));
        if (deep > 0 || rem > 0) {
            b.append(" (");
            if (deep > 0) b.append("గాఢ నిద్ర ").append(Status.hours(deep));
            if (rem > 0) b.append(deep > 0 ? ", " : "").append("REM ").append(Status.hours(rem));
            b.append(")");
        }
        if (m < 300) b.append(", తక్కువ. ఈరోజు జాగ్రత్తగా ఉండండి");
        return b.toString();
    }

    /** Night oxygen {min, avg, n} in words ("" if none). */
    static String spo2Night(JSONObject o) {
        if (o == null || o.optInt("n") <= 0) return "";
        int min = o.optInt("min"), avg = o.optInt("avg");
        if (min < 88 || avg < 92)
            return "రాత్రి ఆక్సిజన్ తక్కువగా ఉంది (సగటు " + avg + "%, తక్కువలో తక్కువ " + min + "%). గురక కూడా ఉంటే స్లీప్ అప్నియా కావచ్చు: డాక్టర్‌ని అడగండి";
        if (min < 90) return "రాత్రి ఆక్సిజన్ సగటు " + avg + "%, ఒకసారి " + min + "% కి తగ్గింది; ఇలా తరచూ వస్తే డాక్టర్‌కి చెప్పండి";
        return "రాత్రి ఆక్సిజన్ బాగుంది (సగటు " + avg + "%)";
    }

    /**
     * W43: the energy estimate, 5–100 (-1 without last night's sleep). From 70: sleep 7–10 h +15 (6–7 h +5, 5–6 h −10,
     * under 5 h −25, over 10 h −5), deep sleep 15%+ +5; resting heart rate against his normal (same or lower +5, 5+ higher
     * −10, 10+ −20); yesterday's stress (low +5, medium −5, high −10); yesterday's steps (6,000+ +5, under 1,000 −5,
     * over 20,000 −5). Unknown parts (-1) are left out.
     */
    static int energy(long sleepMin, long deepMin, int restNow, int restBase, int stressPct, long stepsYday) {
        if (sleepMin < 0) return -1;
        int s = 70;
        if (sleepMin > 600) s -= 5;
        else if (sleepMin >= 420) s += 15;
        else if (sleepMin >= 360) s += 5;
        else if (sleepMin >= 300) s -= 10;
        else s -= 25;
        if (deepMin >= 0 && sleepMin > 0 && deepMin * 100 / sleepMin >= 15) s += 5;
        if (restNow > 0 && restBase > 0) {
            int d = restNow - restBase;
            if (d <= 0) s += 5;
            else if (d >= 10) s -= 20;
            else if (d >= 5) s -= 10;
        }
        if (stressPct >= 0) s += stressPct >= 30 ? -10 : stressPct >= 15 ? -5 : 5;
        if (stepsYday >= 0) s += stepsYday > 20000 ? -5 : stepsYday >= 6000 ? 5 : stepsYday < 1000 ? -5 : 0;
        return Math.max(5, Math.min(100, s));
    }

    static String energyWord(int score) {
        return score >= 80 ? "బాగా శక్తి ఉంది" : score >= 60 ? "పరవాలేదు" : score >= 40 ? "కొంచెం అలసట, మధ్యలో విశ్రాంతి తీసుకోండి"
                : "బాగా అలసట: ఈరోజు ఎక్కువ శ్రమ వద్దు, త్వరగా పడుకోండి";
    }

    /** W45 (pure): weight {t, kg} and body fat {t, pct}, oldest first, in words with a tip; "" if none. */
    static String bodyText(List<double[]> w, List<double[]> f) {
        StringBuilder b = new StringBuilder();
        double dw = Double.NaN, df = Double.NaN, fat = Double.NaN;
        if (!w.isEmpty()) {
            double[] last = w.get(w.size() - 1), ref = monthBefore(w);
            b.append("బరువు ").append(fmt1(last[1])).append(" కిలోలు");
            if (ref != null) {
                dw = last[1] - ref[1];
                b.append(" (నెల క్రితం ").append(fmt1(ref[1])).append(", ").append(signed(dw)).append(")");
            }
        }
        if (!f.isEmpty()) {
            double[] last = f.get(f.size() - 1), ref = monthBefore(f);
            fat = last[1];
            b.append(b.length() > 0 ? ", " : "").append("కొవ్వు ").append(fmt1(fat)).append("%");
            if (ref != null) {
                df = last[1] - ref[1];
                b.append(" (").append(signed(df)).append("%)");
            }
        }
        if (b.length() == 0) return "";
        b.append(". ");
        if (!Double.isNaN(dw)) {
            if (dw >= 1.0) b.append("నెలలో బరువు ").append(fmt1(dw)).append(" కిలోలు పెరిగింది: అన్నం, తీపి, నూనె కొంచెం తగ్గించి రోజూ 30 నిమిషాలు నడవండి. ");
            else if (dw <= -2.0) b.append("నెలలో ").append(fmt1(-dw)).append(" కిలోలు తగ్గారు. కావాలని తగ్గించకపోతే, ఆకలి లేకపోవడం / నీరసం ఉంటే డాక్టర్‌ని చూడండి. ");
            else if (dw <= -1.0) b.append("నెలలో ").append(fmt1(-dw)).append(" కిలోలు తగ్గారు, బాగుంది. ఇలాగే కొనసాగించండి. ");
            else b.append("బరువు దాదాపు అలాగే ఉంది. ");
        }
        if (!Double.isNaN(fat)) {
            if (fat > 25) b.append("కొవ్వు శాతం ఎక్కువ (మగవారికి 25% లోపు మంచిది): రోజూ నడక, పప్పు / గుడ్లు లాంటి ప్రోటీన్ పెంచి, తీపి, వేపుళ్లు తగ్గించండి. ");
            else if (fat > 20) b.append("కొవ్వు శాతం కొంచెం ఎక్కువ: నడక కొనసాగించండి, తీపి తగ్గించండి. ");
            else b.append("కొవ్వు శాతం బాగుంది. ");
            if (!Double.isNaN(df) && df <= -0.5) b.append("కొవ్వు తగ్గుతోంది, బాగుంది! ");
        }
        return b.toString().trim();
    }

    /** The reading closest to 30 days before the last one (at least 20 days before it); null if none. */
    private static double[] monthBefore(List<double[]> l) {
        if (l.size() < 2) return null;
        double[] last = l.get(l.size() - 1), best = null;
        for (int i = 0; i < l.size() - 1; i++) {
            double[] x = l.get(i);
            double ago = last[0] - x[0];
            if (ago < 20 * DAY) continue;
            if (best == null || Math.abs(ago - 30 * DAY) < Math.abs(last[0] - best[0] - 30 * DAY)) best = x;
        }
        return best;
    }

    private static String fmt1(double v) {
        String s = String.format(Locale.ENGLISH, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String signed(double v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + fmt1(Math.abs(v)); }

    /** A short health question (said without internet): the health_watch action, or null. */
    static String asks(String t) {
        String x = t == null ? "" : t.trim().toLowerCase(Locale.ROOT).replaceAll("[?.!]+$", "").trim();
        if (x.isEmpty() || x.split("\\s+").length > 8) return null;
        if (x.matches("(?s).*(గుర్తు|రిమైండ్|remind|నోట్|రాయి|రాసుకో|అలారం|టైమర్|పంపు|మెసేజ్).*")) return null; // (a reminder / note about it, not a question)
        // (money, a bill, a tyre's air pressure, a drink: not his health; any number too, e.g. "energy drink 40")
        if (x.matches("(?s).*(\\d|₹|రూపాయ|రూ\\.|ఖర్చు|కట్టా|కొన్నా|ఇన్సూరెన్స్|insurance|టైర్|tyre|tire|గాలి|డ్రింక్|drink|బిల్లు|bill).*")) return null;
        if (x.matches("(?s).*(బాడీ\\s*స్కాన్|body\\s*scan|హెల్త్\\s*స్కాన్|ఆరోగ్య\\s*స్కాన్).*")) return "scan";
        if (x.matches("(?s).*(శ్వాస\\s*వ్యాయామం|బ్రీతింగ్|breathing\\s*exercise).*")) return "breathe";
        if (x.matches("(?s).*(ఎంత|ఎన్ని).*(నడిచాను|నడిచా|నడక).*") || x.matches("(?s).*(ఎన్ని\\s*అడుగులు|అడుగులు\\s*ఎన్ని|ఎన్ని\\s*స్టెప్స్).*")) return "walk_today";
        if (x.matches("(?s).*(ఎనర్జీ|energy|బాడీ\\s*బ్యాటరీ).*")) return "energy";
        if (x.matches("(?s).*(ఒత్తిడి|స్ట్రెస్|stress).*")) return "stress";
        if (x.matches("(?s).*(వారం|week).*(ఆరోగ్యం|హెల్త్|health).*") || x.matches("(?s).*(ఆరోగ్య|హెల్త్)\\s*(గ్రాఫ్|రిపోర్ట్).*")) return "week";
        if (x.matches("(?s).*(బాడీ\\s*రిపోర్ట్|కొవ్వు\\s*శాతం|బాడీ\\s*ఫ్యాట్|body\\s*(fat|composition)).*")) return "body";
        // the rest only about HIM ("నా", "ఈరోజు", "తిన్నాను"...), not a general question ("ఇడ్లీలో ఎన్ని కేలరీలు?", "ఏం చేయాలి"): those wait for the internet
        boolean mine = x.matches("(?s).*(నేను|నా\\s|నాకు|నా$|తిన్నాను|తిన్నా|చేశాను|ఈరోజు|ఇవాళ|ఈ\\s*వారం|\\bmy\\b|today|this\\s*week).*")
                && !x.matches("(?s).*(లో\\s*ఎన్ని|ఉంటాయి|చేయాలి|చెయ్యాలి|లెక్కపెడ|ఎలా\\s*లెక్క|అంటే).*");
        if (!mine) return null;
        if (x.matches("(?s).*(bmi|బీఎంఐ).*")) return "body";
        if (x.matches("(?s).*(కేలరీ|కాలరీ|calorie).*") && x.matches("(?s).*(తిన్న|తిన్నా|ఆహారం|భోజనం|food).*")) return "food";
        // (a question about it: "వ్యాయామం మొదలుపెట్టు" / "ఆపు" stay the exercise routine's)
        if (x.matches("(?s).*(కేలరీ|కాలరీ|calorie|వ్యాయామం|వర్కౌట్|workout|ఫిట్‌నెస్|ఫిట్నెస్|fitness|vo2).*")
                && x.matches("(?s).*(ఎంత|ఎన్ని|ఎలా|ఏమి|ఏం|చెప్పు|రిపోర్ట్|report|how).*") && !x.matches("(?s).*(మొదలు|ఆపు|ఆపేయ్|start|stop).*")) return "fitness";
        return null;
    }

    // ================================================================ height, BMI, fitness, food, sugar ("అన్నీ కలుపు", pure parts)

    /** A step's length from his height: 0.415 × height (between 120 and 220 cm), else 0.72 m. */
    static double strideFor(double cm) { return cm >= 120 && cm <= 220 ? Math.round(cm * 0.415) / 100.0 : 0.72; }

    static double bmi(double kg, double cm) { return kg <= 0 || cm <= 0 ? -1 : Math.round(kg / Math.pow(cm / 100.0, 2) * 10) / 10.0; }

    /** For Indians (lower cut-offs than the world's): 23+ is over. */
    static String bmiWord(double b) {
        return b < 0 ? "" : b < 18.5 ? "తక్కువ బరువు" : b < 23 ? "సాధారణం" : b < 25 ? "కొంచెం ఎక్కువ (మన దేశం వారికి 23 దాటితే)"
                : "ఎక్కువ: నడక పెంచి, అన్నం, తీపి, నూనె తగ్గించండి";
    }

    /** VO2 max for a grown man, roughly. */
    static String vo2Word(double v) {
        return v >= 45 ? "చాలా బాగుంది" : v >= 40 ? "బాగుంది" : v >= 35 ? "సగటు" : "మెరుగుపడాలి: రోజూ కొంచెం వేగంగా నడవండి";
    }

    /** A workout's name in Telugu (Health Connect's numbers). */
    static String exerciseName(int type) {
        switch (type) {
            case 53: return "నడక";
            case 33: case 34: return "పరుగు";
            case 4: case 5: return "సైక్లింగ్";
            case 21: return "ట్రెక్కింగ్";
            case 57: return "యోగా";
            case 45: case 55: return "బరువుల వ్యాయామం";
            case 46: return "స్ట్రెచింగ్";
            case 48: case 49: return "ఈత";
            case 10: return "డాన్స్";
            case 9: return "క్రికెట్";
            case 1: return "బ్యాడ్మింటన్";
            default: return "వ్యాయామం";
        }
    }

    /** One workout: "9:10 కి నడక 2.1 కి.మీ, 26 నిమిషాలు, 140 కేలరీలు". */
    static String exerciseLine(JSONObject x) {
        long secs = (x.optLong("end") - x.optLong("start")) / 1000;
        StringBuilder b = new StringBuilder(time(x.optLong("start"))).append(" కి ").append(exerciseName(x.optInt("type")));
        long m = x.optLong("m", -1);
        if (m > 0) b.append(" ").append(m < 995 ? Math.max(10, Math.round(m / 10.0) * 10) + " మీటర్లు" : fmt1(m / 1000.0) + " కి.మీ").append(",");
        b.append(" ").append(minutes(secs));
        if (x.optLong("kcal", -1) > 0) b.append(", ").append(Sums.num(x.optLong("kcal"))).append(" కేలరీలు");
        return b.toString();
    }

    /**
     * What he ate (logged in Samsung Health) against about what his body needs today: BMR × 1.2 (daily work) + the
     * workouts' calories. Pure; -1 for unknown parts.
     */
    static String foodText(long kcal, long protein, long carbs, long fat, double bmr, double exKcal, double weightKg, boolean dayDone) {
        StringBuilder b = new StringBuilder("ఈరోజు Samsung Health లో రాసిన ఆహారం: ").append(Sums.num(kcal)).append(" కేలరీలు");
        List<String> parts = new ArrayList<>();
        if (protein >= 0) parts.add("ప్రోటీన్ " + protein + " గ్రా");
        if (carbs >= 0) parts.add("కార్బ్స్ " + carbs + " గ్రా");
        if (fat >= 0) parts.add("కొవ్వు " + fat + " గ్రా");
        if (!parts.isEmpty()) b.append(" (").append(String.join(", ", parts)).append(")");
        b.append(". ");
        if (bmr > 0) {
            long need = Math.round(bmr * 1.2 + Math.max(0, exKcal));
            b.append("మీ శరీరానికి ఈరోజు సుమారు ").append(Sums.num(need)).append(" కేలరీలు కావాలి");
            if (kcal > need + 300) b.append(": ఇప్పటికే కొంచెం ఎక్కువ తిన్నారు. ");
            else if (!dayDone) b.append(kcal < need ? " (ఇంకా సుమారు " + Sums.num(need - kcal) + " తినొచ్చు). " : ". "); // (the day isn't over: no "too little" yet)
            else b.append(kcal < need - 500 ? ": ఈరోజు తక్కువ తిన్నారు, భోజనం మానకండి. " : ": సరిపోయింది. ");
        }
        if (protein >= 0 && weightKg > 0 && protein < Math.round(weightKg * 0.8))
            b.append("ప్రోటీన్ తక్కువ (మీకు రోజుకు సుమారు ").append(Math.round(weightKg * 0.8)).append(" గ్రా): పప్పు, గుడ్లు, పెరుగు, శనగలు చేర్చండి. ");
        return b.toString().trim();
    }

    // ================================================================ height (kept for the step length)

    private static SharedPreferences hp(Context c) { return c.getSharedPreferences("jarvis_height", Context.MODE_PRIVATE); }

    /** His height in cm (what he said, else Samsung Health's), -1 if not known. Cheap. */
    static double heightCm(Context c) { return hp(c).getFloat("cm", -1); }

    static String heightFrom(Context c) { return hp(c).getString("from", ""); }

    /** Height set (he said it: "said"; from Samsung Health: "samsung"); the step length follows, on the watch too. */
    static void setHeight(Context c, double cm, String from) {
        if (cm < 120 || cm > 220) return;
        boolean changed = Math.abs(heightCm(c) - cm) >= 0.5;
        hp(c).edit().putFloat("cm", (float) cm).putString("from", from).apply();
        stride = strideFor(cm);
        if (changed) WatchHub.pushSettings(c);
    }

    /** The step length from what is known (call before saying distances). */
    static void useStride(Context c) { stride = strideFor(heightCm(c)); }

    /** Samsung Health's height, looked at once a day (background); what he said himself is kept. */
    static void refreshHeight(Context c, boolean now) {
        SharedPreferences s = hp(c);
        if ("said".equals(s.getString("from", ""))) { useStride(c); return; }
        if (!now && System.currentTimeMillis() - s.getLong("checked", 0) < 20 * 3600_000L) { useStride(c); return; }
        s.edit().putLong("checked", System.currentTimeMillis()).apply();
        double cm = HealthData.heightCm(c);
        if (cm > 0) setHeight(c, Math.round(cm * 10) / 10.0, "samsung");
        else useStride(c);
    }

    // ================================================================ steps and walks (W31)

    /** Today's steps: the most of Samsung Health (watch + phone), the watch's own count and the phone's (-1 if none). */
    static long stepsToday(Context c) {
        long now = System.currentTimeMillis(), best = -1;
        try { if (HealthData.connected(c)) best = HealthData.steps(c, dayStart(LocalDate.now()), now); } catch (Exception ignored) {}
        best = Math.max(best, WatchHealth.daySteps(c, LocalDate.now()));
        try { if (Health.canCount(c)) best = Math.max(best, Health.stepsToday(c)); } catch (Exception ignored) {}
        return best;
    }

    /** "ఈరోజు ఎంత నడిచాను?": steps, about how far, and today's walks. */
    static String walkToday(Context c) {
        useStride(c);
        long steps = stepsToday(c);
        StringBuilder b = new StringBuilder();
        if (steps < 0) b.append("ఈరోజు అడుగులు చూడలేకపోయాను: వాచ్‌లో Jarvis తెరిచి \"శారీరక కదలిక\" అనుమతి ఇవ్వండి, లేదా ఫోన్ Settings → ⌚ వాచ్ → Health Connect కలపండి. ");
        else b.append("ఈరోజు ").append(Sums.num(steps)).append(" అడుగులు, సుమారు ").append(distance(steps)).append(". ");
        List<JSONObject> walks = walksOn(c, LocalDate.now());
        if (!walks.isEmpty()) {
            b.append(walks.size() == 1 ? "ఒకసారి నడిచారు: " : walks.size() + " సార్లు నడిచారు: ");
            int from = Math.max(0, walks.size() - 3);
            for (int i = from; i < walks.size(); i++) {
                JSONObject w = walks.get(i);
                b.append(i > from ? "; " : "").append(time(w.optLong("t") - w.optLong("secs") * 1000)).append(" కి ")
                        .append(walkLine(w.optLong("steps"), w.optLong("secs")));
            }
            b.append(". ");
        }
        JSONArray ex = HealthData.exercises(c, dayStart(LocalDate.now()), System.currentTimeMillis() + 1);
        if (ex.length() > 0) { // (workouts the watch recorded: Samsung's own distance)
            b.append("Samsung Health లో రికార్డ్ అయిన వ్యాయామం (Samsung కొలత): ");
            for (int i = Math.max(0, ex.length() - 3); i < ex.length(); i++)
                b.append(i > Math.max(0, ex.length() - 3) ? "; " : "").append(exerciseLine(ex.optJSONObject(i)));
            b.append(". ");
        }
        return b.toString().trim();
    }

    static List<JSONObject> walksOn(Context c, LocalDate d) {
        long from = dayStart(d), to = dayStart(d.plusDays(1));
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : Notes.list(c, WALKS)) if (o.optLong("t") >= from && o.optLong("t") < to) out.add(o);
        return out;
    }

    static String time(long at) { return new SimpleDateFormat("h:mm", Locale.ENGLISH).format(new Date(at)); }

    // ================================================================ energy (W43)

    /** {score, sleep, deep, rest_now, rest_normal, stress_yesterday, steps_yesterday}; score -1 without last night's sleep. */
    static JSONObject energyData(Context c) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis(), today = dayStart(LocalDate.now()), yday = dayStart(LocalDate.now().minusDays(1));
            long sleep = -1, deep = -1;
            JSONObject sl = HealthData.lastSleep(c);
            if (sl != null) { sleep = sl.optLong("minutes"); deep = sl.optLong("deep"); }
            else {
                long[] ph = Sleep.between(c, now - 14 * 3600_000L, now + 1);
                if (ph[0] > 0 && ph[1] >= 60) sleep = ph[1];
            }
            int restNow = HealthData.restingHr(c, today, now), restBase = restNow > 0 ? HealthData.restingHr(c, today - 14 * DAY, today) : -1;
            List<JSONObject> l = HeartLog.all(c);
            if (restNow <= 0 || restBase <= 0) { // (his sitting readings from the watch instead)
                restNow = HeartLog.low(l, now - DAY, now);
                restBase = HeartLog.low(l, today - 14 * DAY, today);
            }
            int[] base = HeartLog.baseline(l, now);
            int stress = HeartLog.stressPct(l, base, yday, today);
            long steps = -1;
            if (HealthData.connected(c)) steps = HealthData.steps(c, yday, today);
            steps = Math.max(steps, WatchHealth.daySteps(c, LocalDate.now().minusDays(1)));
            o.put("score", energy(sleep, deep, restNow, restBase, stress, steps)).put("sleep", sleep).put("deep", deep).put("rest_now", restNow)
                    .put("rest_normal", restBase).put("stress_yesterday", stress).put("steps_yesterday", steps);
        } catch (Exception e) {
            try { o.put("score", -1); } catch (Exception ignored) {}
        }
        return o;
    }

    /** "ఈరోజు ఎనర్జీ 72/100 (Jarvis అంచనా): పరవాలేదు." with the main reason when it is low; "" without last night's sleep. */
    static String energyLine(Context c) {
        JSONObject e = energyData(c);
        int s = e.optInt("score", -1);
        if (s < 0) return "";
        StringBuilder b = new StringBuilder("ఈరోజు ఎనర్జీ ").append(s).append("/100 (Jarvis అంచనా): ").append(energyWord(s));
        if (s < 60) {
            List<String> why = new ArrayList<>();
            if (e.optLong("sleep") >= 0 && e.optLong("sleep") < 360) why.add("నిద్ర తక్కువ");
            if (e.optInt("rest_now") > 0 && e.optInt("rest_normal") > 0 && e.optInt("rest_now") - e.optInt("rest_normal") >= 5) why.add("విశ్రాంతి గుండె వేగం మామూలు కంటే ఎక్కువ");
            if (e.optInt("stress_yesterday", -1) >= 30) why.add("నిన్న ఒత్తిడి ఎక్కువ");
            if (!why.isEmpty()) b.append(" (").append(String.join(", ", why)).append(")");
        }
        return b.append(".").toString();
    }

    // ================================================================ last night (W28, W48): for the morning

    /** Sleep with its stages, the night's coughs / snoring, night oxygen; "" when Health Connect has no sleep for last night. */
    static String night(Context c) {
        JSONObject sl = HealthData.lastSleep(c);
        if (sl == null) return "";
        StringBuilder b = new StringBuilder(sleepLine(sl)).append(". ");
        int coughs = CoughLog.night(c, LocalDate.now()), snore = CoughLog.nightSnore(c, LocalDate.now());
        if (coughs >= 3 || snore >= 10) b.append(CoughLog.nightLine(coughs, snore)).append(" ");
        String ox = spo2Night(HealthData.spo2(c, sl.optLong("start"), sl.optLong("end") + 1));
        if (!ox.isEmpty()) b.append(ox).append(". ");
        return b.toString().trim();
    }

    // ================================================================ the body scan (W25)

    /** The scan's answer: the heart rate just read (0: the last one within 30 minutes) with his day. */
    static String scan(Context c, int bpm) {
        long now = System.currentTimeMillis();
        List<JSONObject> l = HeartLog.all(c);
        int[] base = HeartLog.baseline(l, now);
        boolean fresh = bpm > 0;
        if (!fresh) {
            JSONObject last = HeartLog.last(l);
            if (last != null && now - last.optLong("t") <= 30 * 60_000L) bpm = last.optInt("bpm");
        }
        StringBuilder b = new StringBuilder("బాడీ స్కాన్: ");
        if (bpm > 0) {
            b.append("గుండె వేగం ").append(bpm);
            if (bpm > 120) b.append("; కూర్చున్నప్పుడు ఇది చాలా ఎక్కువ. ఛాతి నొప్పి, కళ్లు తిరగడం, ఊపిరి ఆడకపోవడం ఉంటే వెంటనే 108");
            else if (bpm < 45) b.append("; ఇది తక్కువ. కళ్లు తిరగడం, నీరసం ఉంటే డాక్టర్‌ని చూడండి");
            else if (base != null) b.append(" (మీ మామూలు ").append(base[0]).append(bpm >= HeartLog.threshold(base) ? "; ఇప్పుడు ఎక్కువ, 2 నిమిషాలు శ్వాస వ్యాయామం చేయండి"
                    : bpm > base[0] + 5 ? "; కొంచెం ఎక్కువ" : "; సరిగ్గా ఉంది").append(")");
            else b.append(" (మీ మామూలు గుండె వేగం ఇంకా నేర్చుకుంటున్నాను)");
            b.append(". ");
            if (fresh) HeartLog.add(c, bpm, false, now); // (kept, but not in his sitting normal: he may have just walked)
        } else b.append("గుండె వేగం చూడలేకపోయాను. ");
        int pct = HeartLog.stressPct(l, base, dayStart(LocalDate.now()), now + 1);
        if (pct >= 0) b.append("ఈరోజు ఒత్తిడి ").append(HeartLog.stressWord(pct)).append(". ");
        JSONObject sl = HealthData.lastSleep(c);
        if (sl != null) b.append("రాత్రి నిద్ర ").append(Status.hours(sl.optLong("minutes"))).append(". ");
        else {
            long[] ph = Sleep.between(c, now - 14 * 3600_000L, now + 1);
            if (ph[0] > 0 && ph[1] >= 60) b.append("రాత్రి నిద్ర ").append(Status.hours(ph[1])).append(" (ఫోన్ లెక్క). ");
        }
        long steps = stepsToday(c);
        if (steps >= 0) b.append("ఈరోజు ").append(Sums.num(steps)).append(" అడుగులు. ");
        int coughs = CoughLog.count(c, LocalDate.now(), "cough");
        if (coughs > 0) b.append("ఈరోజు దగ్గు ").append(coughs).append(" సార్లు. ");
        JSONObject ox = HealthData.spo2(c, now - DAY, now + 1);
        if (ox != null) b.append("ఆక్సిజన్ ").append(ox.optInt("last")).append("% (").append(time(ox.optLong("at"))).append(" కి). ");
        JSONObject bp = lastVital(c, "bp", 7);
        if (bp == null) { // (a BP the Samsung Health app has, e.g. from a BP machine it connects to)
            JSONArray hb = HealthData.bloodPressure(c, 7);
            JSONObject x = hb.length() == 0 ? null : hb.optJSONObject(hb.length() - 1);
            if (x != null) try { bp = new JSONObject().put("kind", "bp").put("sys", x.optInt("sys")).put("dia", x.optInt("dia")); Vitals.judge(bp); } catch (Exception ignored) {}
        }
        if (bp != null) b.append("చివరి BP ").append(bp.optInt("sys")).append("/").append(bp.optInt("dia")).append(" (").append(bp.optString("status")).append("). ");
        JSONObject sg = lastVital(c, "sugar", 7);
        if (sg == null) { // (a sugar reading Samsung Health has)
            JSONArray g = HealthData.glucose(c, 7);
            if (g.length() > 0) sg = sugarJudged(g.optJSONObject(g.length() - 1));
        }
        if (sg != null) b.append("చివరి షుగర్ ").append(Math.round(sg.optDouble("value"))).append(" (").append(whenWord(sg.optString("when"))).append("): ")
                .append(sg.optString("status")).append(". ");
        String en = energyLine(c);
        if (!en.isEmpty()) b.append(en);
        return b.toString().trim();
    }

    /** His last reading of a kind (Vitals) within N days, or null. */
    static JSONObject lastVital(Context c, String kind, int days) {
        List<JSONObject> l = Notes.list(c, Vitals.KEY);
        long since = System.currentTimeMillis() - days * DAY;
        for (int i = l.size() - 1; i >= 0; i--) {
            JSONObject o = l.get(i);
            if (o.optLong("t") < since) break;
            if (kind.equals(o.optString("kind"))) return o;
        }
        return null;
    }

    // ================================================================ stress (W46) in words

    static String stress(Context c) {
        long now = System.currentTimeMillis();
        List<JSONObject> l = HeartLog.all(c);
        int[] base = HeartLog.baseline(l, now);
        int n = 0;
        for (JSONObject o : l) if (o.optBoolean("still") && now - o.optLong("t") <= 14 * DAY) n++;
        if (base == null)
            return "మీ మామూలు గుండె వేగం ఇంకా నేర్చుకుంటున్నాను (ఇప్పటికి " + n + " రీడింగ్స్; సుమారు ఒక రోజు వాచ్ పెట్టుకుంటే చెప్పగలను). "
                    + "వాచ్‌లో Jarvis తెరిచి \"శరీర సెన్సార్లు\" అనుమతి ఇచ్చారో చూడండి.";
        int today = HeartLog.stressPct(l, base, dayStart(LocalDate.now()), now + 1),
                yday = HeartLog.stressPct(l, base, dayStart(LocalDate.now().minusDays(1)), dayStart(LocalDate.now()));
        StringBuilder b = new StringBuilder();
        b.append(today >= 0 ? "ఈరోజు ఒత్తిడి " + HeartLog.stressWord(today) + " (" + today + "% సమయం గుండె వేగం మామూలు కంటే ఎక్కువ)" : "ఈరోజు ఇంకా సరిపడా రీడింగ్స్ లేవు");
        if (yday >= 0) b.append("; నిన్న ").append(HeartLog.stressWord(yday));
        b.append(". కూర్చున్నప్పుడు మీ మామూలు గుండె వేగం ").append(base[0]).append(". ఇది Jarvis అంచనా (Samsung స్ట్రెస్ నంబర్ వేరే యాప్‌లకు ఇవ్వదు).");
        if (today >= 30) b.append(" 2 నిమిషాలు శ్వాస వ్యాయామం చేద్దామా? (వాచ్‌లో 🌬️ శ్వాస)");
        return b.toString();
    }

    // ================================================================ weight and body fat (W45)

    /** This month's body in words ("" if no weight / body fat in the last 70 days). */
    static String body(Context c) {
        List<double[]> w = new ArrayList<>(), f = new ArrayList<>();
        JSONArray hw = HealthData.weights(c, 70), hf = HealthData.bodyFat(c, 70);
        for (int i = 0; i < hw.length(); i++) { JSONObject o = hw.optJSONObject(i); if (o != null) w.add(new double[]{o.optLong("t"), o.optDouble("kg")}); }
        for (int i = 0; i < hf.length(); i++) { JSONObject o = hf.optJSONObject(i); if (o != null) f.add(new double[]{o.optLong("t"), o.optDouble("pct")}); }
        long since = System.currentTimeMillis() - 70 * DAY;
        for (JSONObject o : Notes.list(c, Vitals.KEY)) // (weights he told Jarvis, too)
            if ("weight".equals(o.optString("kind")) && o.optLong("t") >= since) w.add(new double[]{o.optLong("t"), o.optDouble("value")});
        w.sort((x, y) -> Double.compare(x[0], y[0]));
        StringBuilder b = new StringBuilder(bodyText(w, f));
        double cm = heightCm(c);
        if (!w.isEmpty() && cm > 0) {
            double bm = bmi(w.get(w.size() - 1)[1], cm);
            b.append(b.length() > 0 ? " " : "").append("BMI ").append(fmt1(bm)).append(": ").append(bmiWord(bm)).append(".");
        }
        double bmr = HealthData.bmrKcal(c);
        if (bmr > 0) b.append(b.length() > 0 ? " " : "").append("విశ్రాంతిలో కూడా మీ శరీరం రోజుకు సుమారు ").append(Sums.num(Math.round(bmr))).append(" కేలరీలు ఖర్చు చేస్తుంది (BMR).");
        JSONObject vo = HealthData.vo2max(c, 90);
        if (vo != null) b.append(b.length() > 0 ? " " : "").append("ఫిట్‌నెస్ (VO2 max) ").append(fmt1(vo.optDouble("v"))).append(": ").append(vo2Word(vo.optDouble("v"))).append(".");
        return b.toString().trim();
    }

    // ================================================================ workouts, food, sugar (from Samsung Health)

    /** This week's workouts the watch recorded (Samsung's distance, minutes, calories) and VO2 max. */
    static String fitness(Context c) {
        String why = HealthData.notReady(c, 10);
        if (why != null) return why;
        long now = System.currentTimeMillis(), from = dayStart(LocalDate.now().minusDays(6));
        HealthData.lastError = "";
        JSONArray ex = HealthData.exercises(c, from, now + 1);
        if (ex.length() == 0 && !HealthData.lastError.isEmpty()) return "Samsung Health నుంచి చదవలేకపోయాను (" + HealthData.lastError + ").";
        StringBuilder b = new StringBuilder();
        if (ex.length() == 0) b.append("ఈ వారం Samsung Health లో వ్యాయామం ఏదీ రికార్డ్ కాలేదు. వాచ్‌లో Samsung Health → Exercise → Walk నొక్కి నడిస్తే, Samsung కొలిచిన దూరం, కేలరీలు చెప్తాను. ");
        else {
            long secs = 0, m = 0, k = 0;
            for (int i = 0; i < ex.length(); i++) {
                JSONObject x = ex.optJSONObject(i);
                secs += (x.optLong("end") - x.optLong("start")) / 1000;
                m += Math.max(0, x.optLong("m"));
                k += Math.max(0, x.optLong("kcal"));
            }
            b.append("ఈ వారం ").append(ex.length()).append(" సార్లు వ్యాయామం, మొత్తం ").append(minutes(secs));
            if (ex.length() > 10) b.append(" (దూరం, కేలరీలు చివరి 10 వాటికే)");
            if (m > 0) b.append(", ").append(m < 995 ? Math.round(m / 10.0) * 10 + " మీటర్లు" : fmt1(m / 1000.0) + " కి.మీ");
            if (k > 0) b.append(", ").append(Sums.num(k)).append(" కేలరీలు");
            b.append(". చివరిది: ").append(exerciseLine(ex.optJSONObject(ex.length() - 1))).append(". ");
            if (secs < 150 * 60) b.append("వారానికి 150 నిమిషాలు నడక మంచిది; ఇంకా ").append(minutes(150 * 60 - secs)).append(" మిగిలింది. ");
        }
        JSONObject vo = HealthData.vo2max(c, 90);
        if (vo != null) b.append("ఫిట్‌నెస్ (VO2 max) ").append(fmt1(vo.optDouble("v"))).append(": ").append(vo2Word(vo.optDouble("v"))).append(". ");
        return b.toString().trim();
    }

    /** Today's food logged in Samsung Health, against about what his body needs. */
    static String food(Context c) {
        String why = HealthData.notReady(c, 14);
        if (why != null) return why;
        long from = dayStart(LocalDate.now()), now = System.currentTimeMillis();
        HealthData.lastError = "";
        JSONObject f = HealthData.food(c, from, now + 1);
        if (f == null && !HealthData.lastError.isEmpty()) return "Samsung Health నుంచి చదవలేకపోయాను (" + HealthData.lastError + ").";
        if (f == null) return "ఈరోజు Samsung Health లో ఆహారం ఏదీ రాయలేదు. అక్కడ Food లో రాస్తే ఎన్ని కేలరీలు తిన్నారో, సరిపోయిందో చెప్తాను.";
        double kg = -1;
        JSONArray w = HealthData.weights(c, 60);
        if (w.length() > 0) kg = w.optJSONObject(w.length() - 1).optDouble("kg", -1);
        else { JSONObject v = lastVital(c, "weight", 60); if (v != null) kg = v.optDouble("value", -1); }
        double ex = 0; // (only the workouts' calories: the day's total burned already has his resting calories in it)
        JSONArray w2 = HealthData.exercises(c, from, now + 1);
        for (int i = 0; i < w2.length(); i++) ex += Math.max(0, w2.optJSONObject(i).optLong("kcal"));
        return foodText(f.optLong("kcal"), f.optLong("protein", -1), f.optLong("carbs", -1), f.optLong("fat", -1),
                HealthData.bmrKcal(c), ex, kg, java.time.LocalTime.now().getHour() >= 20);
    }

    /** Sugar readings in Samsung Health (30 days) with the same words as his own readings. */
    static String sugar(Context c) {
        String why = HealthData.notReady(c, 13);
        if (why != null) return why;
        HealthData.lastError = "";
        JSONArray g = HealthData.glucose(c, 30);
        if (g.length() == 0 && !HealthData.lastError.isEmpty()) return "Samsung Health నుంచి చదవలేకపోయాను (" + HealthData.lastError + ").";
        int own = 0;
        for (JSONObject o : Notes.list(c, Vitals.KEY)) if ("sugar".equals(o.optString("kind"))) own++;
        if (g.length() == 0)
            return "Samsung Health లో షుగర్ రీడింగ్స్ లేవు." + (own > 0 ? " మీరు నాకు చెప్పినవి " + own + " ఉన్నాయి (\"నా షుగర్ రీడింగ్స్\")." : " \"షుగర్ పరగడుపున 110\" అని చెప్పినా రాసుకుంటాను.");
        JSONObject last = g.optJSONObject(g.length() - 1);
        JSONObject j = sugarJudged(last);
        StringBuilder b = new StringBuilder("Samsung Health లో గత 30 రోజుల్లో ").append(g.length()).append(" షుగర్ రీడింగ్స్. చివరిది ")
                .append(last.optInt("mgdl")).append(" (").append(whenWord(last.optString("when"))).append(", ").append(date(last.optLong("t"))).append("): ")
                .append(j.optString("status")).append(". ");
        int n = 0;
        long sum = 0;
        for (int i = 0; i < g.length(); i++) { JSONObject x = g.optJSONObject(i); if ("fasting".equals(x.optString("when"))) { n++; sum += x.optInt("mgdl"); } }
        if (n > 0) b.append("పరగడుపు సగటు ").append(Math.round(sum / (double) n)).append(". ");
        if (!"సాధారణం".equals(j.optString("status")) && !"పరవాలేదు".equals(j.optString("status"))) b.append(j.optString("advice"));
        return b.toString().trim();
    }

    /** A Samsung Health sugar reading judged like his own (Vitals). */
    static JSONObject sugarJudged(JSONObject x) {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "sugar").put("value", x.optInt("mgdl")).put("when", x.optString("when", "random"));
            Vitals.judge(o);
        } catch (Exception ignored) {}
        return o;
    }

    static String whenWord(String w) { return "fasting".equals(w) ? "పరగడుపున" : "after_food".equals(w) ? "తిన్న తర్వాత" : "ఎప్పుడైనా"; }

    /** On the 1st–5th of a month, once: the month's body as a notification (from Proactive). */
    static void monthlyBodyTick(Context c) {
        LocalDate d = LocalDate.now();
        int h = java.time.LocalTime.now().getHour();
        if (d.getDayOfMonth() > 5 || h < 10 || h >= 20) return;
        String month = d.getYear() + "-" + d.getMonthValue();
        if (month.equals(sp(c).getString("body_month", ""))) return;
        if (!HealthData.connected(c) && Notes.list(c, Vitals.KEY).isEmpty()) return;
        HealthData.lastError = "";
        String t = body(c);
        if (t.isEmpty() && !HealthData.lastError.isEmpty()) return; // (couldn't read now: again later)
        sp(c).edit().putString("body_month", month).apply();
        if (t.isEmpty()) return;
        Reminders.notify(c, "⚖️ ఈ నెల బాడీ రిపోర్ట్", t, 4605);
    }

    // ================================================================ breathing (W30), files (W47)

    static void breathed(Context c, JSONObject o) {
        try {
            Notes.add(c, BREATHS, new JSONObject().put("t", System.currentTimeMillis()).put("secs", o.optLong("secs"))
                    .put("before", o.optInt("before")).put("after", o.optInt("after")), 300);
        } catch (Exception ignored) {}
    }

    // ================================================================ the doctor's PDF (W29)

    /** The watch's part of the doctor PDF (Telugu / English lines); "" if nothing. */
    static String doctor(Context c, int days) {
        StringBuilder b = new StringBuilder();
        long now = System.currentTimeMillis(), today = dayStart(LocalDate.now());
        try {
            int rest7 = HealthData.restingHr(c, today - 7 * DAY, now), restBefore = HealthData.restingHr(c, today - 21 * DAY, today - 7 * DAY);
            if (rest7 > 0) b.append("• విశ్రాంతి గుండె వేగం / Resting heart rate (Samsung): ").append(rest7).append(" (last 7 days)")
                    .append(restBefore > 0 ? ", " + restBefore + " (the 2 weeks before)" : "").append("\n");
            List<JSONObject> l = HeartLog.all(c);
            int[] base = HeartLog.baseline(l, now);
            if (base != null) {
                b.append("• కూర్చున్నప్పుడు గుండె వేగం / Sitting heart rate (watch, every ~15 min): usual ").append(base[0])
                        .append(" (").append(base[2]).append(" readings, 14 days)");
                StringBuilder high = new StringBuilder();
                for (int i = Math.min(days, 14) - 1; i >= 0; i--) {
                    LocalDate d = LocalDate.now().minusDays(i);
                    int p = HeartLog.stressPct(l, base, dayStart(d), dayStart(d.plusDays(1)));
                    if (p >= 30) high.append(high.length() > 0 ? ", " : "").append(d.getDayOfMonth()).append("/").append(d.getMonthValue());
                }
                if (high.length() > 0) b.append("; often high on / తరచూ ఎక్కువ: ").append(high);
                b.append("\n");
            }
            JSONArray sl = HealthData.sleeps(c, 7);
            if (sl.length() > 0) {
                long sum = 0, min = Long.MAX_VALUE, max = 0;
                for (int i = 0; i < sl.length(); i++) { long m = sl.optJSONObject(i).optLong("minutes"); sum += m; min = Math.min(min, m); max = Math.max(max, m); }
                b.append("• నిద్ర / Sleep (").append(sl.length()).append(" nights): average ").append(hm(sum / sl.length()))
                        .append(", shortest ").append(hm(min)).append(", longest ").append(hm(max)).append("\n");
            }
            JSONObject ox = HealthData.spo2(c, now - 7 * DAY, now + 1);
            if (ox != null) b.append("• ఆక్సిజన్ / SpO2 (7 days): average ").append(ox.optInt("avg")).append("%, lowest ").append(ox.optInt("min")).append("%\n");
            if (HealthData.connected(c)) {
                long st = HealthData.steps(c, today - 7 * DAY, today);
                if (st > 0) b.append("• అడుగులు / Steps a day (7 days): ").append(Sums.num(st / 7)).append("\n");
            }
            JSONArray hb = HealthData.bloodPressure(c, 30);
            if (hb.length() > 0) {
                b.append("• BP (Samsung Health, 30 days): ").append(hb.length()).append(" readings, last ");
                for (int i = Math.max(0, hb.length() - 5); i < hb.length(); i++) {
                    JSONObject x = hb.optJSONObject(i);
                    b.append(i > Math.max(0, hb.length() - 5) ? ", " : "").append(x.optInt("sys")).append("/").append(x.optInt("dia")).append(" (").append(date(x.optLong("t"))).append(")");
                }
                b.append("\n");
            }
            JSONArray gl = HealthData.glucose(c, 30);
            if (gl.length() > 0) {
                b.append("• షుగర్ / Blood glucose (Samsung Health, 30 days): ");
                for (int i = Math.max(0, gl.length() - 8); i < gl.length(); i++) {
                    JSONObject x = gl.optJSONObject(i);
                    b.append(i > Math.max(0, gl.length() - 8) ? ", " : "").append(x.optInt("mgdl")).append(" mg/dL ")
                            .append("fasting".equals(x.optString("when")) ? "fasting" : "after_food".equals(x.optString("when")) ? "after food" : "random")
                            .append(" (").append(date(x.optLong("t"))).append(")");
                }
                b.append("\n");
            }
            double cm = heightCm(c);
            JSONArray wt = HealthData.weights(c, 60);
            if (cm > 0 && wt.length() > 0) {
                double kg = wt.optJSONObject(wt.length() - 1).optDouble("kg");
                b.append("• ఎత్తు, BMI / Height, BMI: ").append(fmt1(cm)).append(" cm, ").append(fmt1(kg)).append(" kg, BMI ").append(fmt1(bmi(kg, cm))).append("\n");
            }
            JSONObject vo = HealthData.vo2max(c, 90);
            if (vo != null) b.append("• VO2 max (Samsung): ").append(fmt1(vo.optDouble("v"))).append(" ml/kg/min (").append(date(vo.optLong("t"))).append(")\n");
            JSONArray ex = HealthData.exercises(c, now - 7 * DAY, now + 1);
            if (ex.length() > 0) {
                long secs = 0;
                for (int i = 0; i < ex.length(); i++) secs += (ex.optJSONObject(i).optLong("end") - ex.optJSONObject(i).optLong("start")) / 1000;
                b.append("• వ్యాయామం / Workouts recorded (7 days): ").append(ex.length()).append(", ").append(secs / 60).append(" min\n");
            }
            List<JSONObject> ir = Notes.list(c, HeartLog.IRREGULAR);
            if (!ir.isEmpty()) {
                b.append("• గుండె లయ హెచ్చరికలు / Irregular rhythm notifications (watch): ").append(ir.size()).append(" — ");
                for (int i = Math.max(0, ir.size() - 6); i < ir.size(); i++) b.append(i > Math.max(0, ir.size() - 6) ? ", " : "").append(date(ir.get(i).optLong("t")));
                b.append("\n");
            }
            List<JSONObject> files = Notes.list(c, FILES);
            if (!files.isEmpty()) {
                b.append("• ECG / రిపోర్టులు దాచినవి / Saved ECG / reports:\n");
                for (int i = Math.max(0, files.size() - 6); i < files.size(); i++) {
                    JSONObject o = files.get(i);
                    b.append("   ").append(date(o.optLong("t"))).append(" — ").append("ecg".equals(o.optString("kind")) ? "ECG" : "Report").append(": ").append(o.optString("where")).append("\n");
                }
            }
        } catch (Exception ignored) {}
        return b.length() == 0 ? "" : "\n⌚ వాచ్ / Watch (Samsung Health via Health Connect, and Jarvis):\n" + b;
    }

    private static String hm(long minutes) { return minutes / 60 + "h " + minutes % 60 + "m"; }

    static String date(long t) { return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(new Date(t)); }
}
