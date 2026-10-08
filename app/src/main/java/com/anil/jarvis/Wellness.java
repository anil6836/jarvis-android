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
    /** Metres a step (the same as the watch's coach). */
    static final double STRIDE = 0.72;
    private static final long DAY = 86400_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_wellness", Context.MODE_PRIVATE); }

    static long dayStart(LocalDate d) { return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }

    // ================================================================ words (pure: tested on a desk)

    /** "350 అడుగులు, 250 మీటర్లు, 4 నిమిషాలు" / "2,600 అడుగులు, 1.9 కి.మీ, 22 నిమిషాలు". */
    static String walkLine(long steps, long secs) {
        return Sums.num(steps) + " అడుగులు, " + distance(steps) + ", " + minutes(secs);
    }

    static String distance(long steps) {
        double m = steps * STRIDE;
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
        return null;
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
        return bodyText(w, f);
    }

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
