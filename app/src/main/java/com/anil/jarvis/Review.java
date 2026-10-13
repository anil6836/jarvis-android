package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V16: a performance review in plain words.
 * <p>
 * workout: the last 7 days of walks (steps, minutes, days) and the exercises counted on the watch, against the week
 * before, with one verdict and one tip. Counted from his own notes on the phone; nothing is sent anywhere.
 * <p>
 * service: the bike's service checklist (tyre air, brakes, chain / belt, lights, horn, battery, bolts): he ticks what he
 * checked ("టైర్ గాలి చూశాను"), Jarvis says what is left and reads it against the service due date. Ticks older than
 * 45 days drop off by themselves.
 */
final class Review {
    private Review() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_review", Context.MODE_PRIVATE); }

    // ================================================================ workout

    static String workout(Context c) {
        LocalDate today = LocalDate.now();
        int[] steps = new int[7], mins = new int[7], prevSteps = new int[7];
        for (int i = 0; i < 7; i++) {
            for (JSONObject w : Wellness.walksOn(c, today.minusDays(i))) { steps[i] += w.optInt("steps"); mins[i] += (int) (w.optLong("secs") / 60); }
            for (JSONObject w : Wellness.walksOn(c, today.minusDays(7 + i))) prevSteps[i] += w.optInt("steps");
        }
        Map<String, Integer> reps = new LinkedHashMap<>();
        long since = System.currentTimeMillis() - 7L * 86_400_000L;
        for (JSONObject r : Notes.list(c, "reps")) if (r.optLong("t") >= since) reps.merge(r.optString("name", "వ్యాయామం"), r.optInt("count"), Integer::sum);
        long todaySteps = -1;
        try { todaySteps = Wellness.stepsToday(c); } catch (Throwable ignored) {}
        return workoutText(steps, mins, prevSteps, reps, todaySteps);
    }

    /** (pure) the week's review: counts, a verdict, a tip. Index 0 = today. */
    static String workoutText(int[] steps, int[] mins, int[] prevSteps, Map<String, Integer> reps, long todaySteps) {
        int days = 0, total = 0, minutes = 0, prevDays = 0, prevTotal = 0, longest = 0;
        for (int i = 0; i < 7; i++) {
            if (steps[i] > 0 || mins[i] > 0) days++;
            total += steps[i];
            minutes += mins[i];
            longest = Math.max(longest, mins[i]);
            if (prevSteps[i] > 0) prevDays++;
            prevTotal += prevSteps[i];
        }
        StringBuilder b = new StringBuilder("📊 ఈ వారం వ్యాయామ సమీక్ష (7 రోజులు):\n");
        if (days == 0 && reps.isEmpty()) {
            b.append("నడకలు, వ్యాయామాలు ఏవీ నోట్ కాలేదు").append(todaySteps > 0 ? " (ఈరోజు అడుగులు " + Sums.num(todaySteps) + ")" : "").append(".\n");
            b.append("వర్డిక్ట్: ఈ వారం విశ్రాంతి వారం అయింది. చిట్కా: రేపు \"నడక మొదలు\" అని చెప్పి 15 నిమిషాలు నడవండి; Jarvis లెక్కపెడతాడు.");
            return b.toString();
        }
        b.append("🚶 నడకలు: ").append(days).append(" రోజులు, మొత్తం ").append(Sums.num(total)).append(" అడుగులు, ").append(Status.hours(minutes));
        if (longest > 0) b.append(" (పొడవైనది ").append(longest).append(" ని.)");
        b.append("\n");
        if (!reps.isEmpty()) {
            b.append("🏋️ వాచ్ లెక్కపెట్టినవి: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : reps.entrySet()) { b.append(first ? "" : ", ").append(e.getKey()).append(" ").append(e.getValue()); first = false; }
            b.append("\n");
        }
        if (prevDays > 0 || prevTotal > 0) {
            int diff = prevTotal == 0 ? 100 : (int) Math.round((total - prevTotal) * 100.0 / prevTotal);
            b.append("📈 గత వారంతో పోలిస్తే: ").append(prevDays).append(" రోజులు → ").append(days).append(" రోజులు, అడుగులు ")
                    .append(diff >= 0 ? "+" : "").append(diff).append("%\n");
        }
        String verdict = days >= 5 ? "బాగుంది: వారంలో " + days + " రోజులు కదిలారు" : days >= 3 ? "పరవాలేదు: " + days + " రోజులు; ఇంకో 2 రోజులు కలపండి" : "తక్కువ: " + days + " రోజులే; వారానికి కనీసం 3 రోజులు ఉండాలి";
        String tip;
        if (longest > 0 && longest < 15) tip = "ఒక నడకను 20 నిమిషాల వరకు పెంచండి; చిన్న నడకల కంటే ఒక పొడవైనది గుండెకు మంచిది";
        else if (reps.isEmpty()) tip = "నడకతో పాటు వారానికి 2 సార్లు వాచ్‌లో స్క్వాట్స్ / పుష్-అప్స్ లెక్కపెట్టించండి";
        else if (reps.size() == 1) tip = "ఒకే వ్యాయామం చేస్తున్నారు; ఇంకో రకం (స్క్వాట్స్ ↔ పుష్-అప్స్) కలపండి";
        else if (days >= 5 && prevDays >= 5) tip = "రెండు వారాలుగా బాగా చేస్తున్నారు; ఇలాగే ఉంచండి, ఒక రోజు పూర్తి విశ్రాంతి ఇవ్వండి";
        else tip = "డ్యూటీ లేని రోజుల్లో ఉదయం నడక పెట్టుకోండి; Jarvis గుర్తు చేస్తాడు (Settings → వ్యాయామం)";
        b.append("వర్డిక్ట్: ").append(verdict).append(".\nచిట్కా: ").append(tip).append(".");
        return b.toString();
    }

    // ================================================================ the bike's service checklist

    static final String[][] ITEMS = {
            {"tyre", "టైర్ గాలి", "టైర్|గాలి|tyre|tire|air"}, {"brakes", "బ్రేకులు", "బ్రేక|brake"},
            {"chain", "చైన్ / బెల్ట్", "చైన్|బెల్ట్|chain|belt"}, {"lights", "లైట్లు, ఇండికేటర్లు", "లైట్|ఇండికేటర్|light|indicator"},
            {"horn", "హారన్", "హారన్|horn"}, {"battery", "బ్యాటరీ, ఛార్జింగ్ పోర్ట్", "బ్యాటరీ|ఛార్జ|battery|charg"},
            {"bolts", "బోల్టులు, అద్దాలు", "బోల్ట్|బోల్టు|అద్ద|మిర్రర్|bolt|mirror|screw"}};

    private static final long KEEP = 45L * 86_400_000L;

    static Map<String, Long> ticks(Context c) {
        Map<String, Long> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject(sp(c).getString("svc_ticks", "{}"));
            for (String[] it : ITEMS) { long t = o.optLong(it[0], 0); if (t > 0 && now - t < KEEP) out.put(it[0], t); }
        } catch (Exception ignored) {}
        return out;
    }

    /** Ticks the items his words name ("టైర్ గాలి, బ్రేకులు చూశాను"); returns the ids ticked. */
    static List<String> tick(Context c, String words) {
        List<String> ids = match(words);
        if (ids.isEmpty()) return ids;
        try {
            JSONObject o = new JSONObject(sp(c).getString("svc_ticks", "{}"));
            for (String id : ids) o.put(id, System.currentTimeMillis());
            sp(c).edit().putString("svc_ticks", o.toString()).apply();
        } catch (Exception ignored) {}
        return ids;
    }

    static void clearTicks(Context c) { sp(c).edit().remove("svc_ticks").apply(); }

    /** (pure) which checklist items the words name ("all" / "అన్నీ" = every one). */
    static List<String> match(String words) {
        List<String> out = new ArrayList<>();
        String t = words == null ? "" : words.toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return out;
        if (t.matches(".*(అన్నీ|మొత్తం|all|everything).*")) { for (String[] it : ITEMS) out.add(it[0]); return out; }
        for (String[] it : ITEMS) if (t.matches(".*(" + it[2] + ").*")) out.add(it[0]);
        return out;
    }

    static String service(Context c) {
        long[] due = null;
        try { due = Travel.serviceDue(c); } catch (Throwable ignored) {}
        return serviceText(ticks(c), due, System.currentTimeMillis());
    }

    /** (pure) the checklist with ticks, what's left, and the verdict against the due date. */
    static String serviceText(Map<String, Long> ticks, long[] due, long now) {
        StringBuilder b = new StringBuilder("🧰 బైక్ సర్వీస్ చెక్‌లిస్ట్:\n");
        int done = 0;
        List<String> left = new ArrayList<>();
        for (String[] it : ITEMS) {
            Long t = ticks.get(it[0]);
            if (t != null) { done++; b.append("✅ ").append(it[1]).append(" (").append(daysAgo(now - t)).append(")\n"); }
            else { left.add(it[1]); b.append("⬜ ").append(it[1]).append("\n"); }
        }
        b.append(done).append(" / ").append(ITEMS.length).append(" చూశారు. ");
        if (due == null) b.append("చివరి సర్వీస్ తేదీ తెలియదు (\"బైక్ సర్వీస్ చేయించాను\" అనండి). ");
        else if (due[0] < 0 || due[2] <= 0) b.append("సర్వీస్ టైమ్ దాటింది: వీటిని మీరు చూసినా సర్వీస్ సెంటర్‌కి తీసుకెళ్ళండి. ");
        else if (due[0] <= 14) b.append("సర్వీస్ ").append(due[0]).append(" రోజుల్లో: మిగిలినవి ఈ వారం చూడండి. ");
        else b.append("సర్వీస్ ఇంకా ").append(due[0]).append(" రోజులు ఉంది. ");
        if (left.isEmpty()) b.append("వర్డిక్ట్: అన్నీ చూశారు, బండి సిద్ధం.");
        else if (done >= 4) b.append("వర్డిక్ట్: బాగానే చూశారు; మిగిలినవి: ").append(String.join(", ", left)).append(".");
        else b.append("వర్డిక్ట్: ముందు ఈ మూడు చూడండి: ").append(String.join(", ", left.subList(0, Math.min(3, left.size())))).append(". చూశాక \"టైర్ గాలి చూశాను\" అని చెప్పండి.");
        return b.toString();
    }

    private static String daysAgo(long ms) {
        long d = ms / 86_400_000L;
        return d == 0 ? "ఈరోజు" : d == 1 ? "నిన్న" : d + " రోజుల క్రితం";
    }
}
