package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small everyday helpers: where he kept things, habits with streaks, splitting a bill fairly, and a thunderstorm warning.
 */
final class Everyday {
    private Everyday() {}

    // ================================================================ where things are kept

    static final String ITEMS = "items";

    private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    private static JSONObject findItem(List<JSONObject> l, String thing) {
        String k = key(thing);
        if (k.isEmpty()) return null;
        for (JSONObject o : l) if (key(o.optString("thing")).equals(k)) return o;
        JSONObject best = null;
        for (JSONObject o : l) {
            String t = key(o.optString("thing"));
            if (t.contains(k) || k.contains(t)) { if (best != null) return null; best = o; } // two matches: ask
        }
        return best;
    }

    static JSONObject put(Context c, String thing, String place) throws Exception {
        if (key(thing).isEmpty() || place == null || place.trim().isEmpty()) return null;
        List<JSONObject> l = Notes.list(c, ITEMS);
        JSONObject o = findItem(l, thing);
        if (o == null) { o = new JSONObject().put("thing", thing.trim()); l.add(o); }
        else if (!o.optString("place").isEmpty()) o.put("before", o.optString("place")); // where it was until now
        o.put("place", place.trim()).put("t", System.currentTimeMillis());
        Notes.save(c, ITEMS, l, 300);
        return o;
    }

    static JSONObject where(Context c, String thing) { return findItem(Notes.list(c, ITEMS), thing); }

    static boolean forget(Context c, String thing) {
        List<JSONObject> l = Notes.list(c, ITEMS);
        JSONObject o = findItem(l, thing);
        if (o == null) return false;
        l.remove(o);
        Notes.save(c, ITEMS, l, 300);
        return true;
    }

    // ================================================================ habits and streaks

    static final String HABITS = "habit_track";

    private static TreeSet<String> dates(JSONObject o) {
        TreeSet<String> s = new TreeSet<>();
        JSONArray a = o.optJSONArray("done");
        for (int i = 0; a != null && i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    /** Days in a row it was done, ending today (or yesterday, if today isn't marked yet). */
    static int streak(JSONObject o) {
        TreeSet<String> s = dates(o);
        LocalDate d = LocalDate.now();
        if (!s.contains(d.toString())) d = d.minusDays(1);
        int n = 0;
        while (s.contains(d.toString())) { n++; d = d.minusDays(1); }
        return n;
    }

    static int best(JSONObject o) {
        int best = 0, run = 0;
        LocalDate prev = null;
        for (String x : dates(o)) {
            LocalDate d = LocalDate.parse(x);
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        return best;
    }

    static JSONObject habit(Context c, String name, boolean create) throws Exception {
        List<JSONObject> l = Notes.list(c, HABITS);
        String k = key(name);
        for (JSONObject o : l) if (key(o.optString("name")).equals(k)) return o;
        if (!create) for (JSONObject o : l) if (!k.isEmpty() && key(o.optString("name")).contains(k)) return o;
        if (!create || k.isEmpty()) return null;
        JSONObject o = new JSONObject().put("name", name.trim()).put("done", new JSONArray()).put("since", LocalDate.now().toString());
        l.add(o);
        Notes.save(c, HABITS, l, 30);
        return o;
    }

    /** Marks it done (or not) for a day; returns the habit with its streak. */
    static JSONObject mark(Context c, String name, LocalDate day, boolean done) throws Exception {
        List<JSONObject> l = Notes.list(c, HABITS);
        String k = key(name);
        for (JSONObject o : l) {
            String n = key(o.optString("name"));
            if (!(n.equals(k) || (!k.isEmpty() && (n.contains(k) || k.contains(n))))) continue;
            TreeSet<String> s = dates(o);
            if (done) s.add(day.toString()); else s.remove(day.toString());
            while (s.size() > 400) s.pollFirst();
            o.put("done", new JSONArray(s));
            o.put("asked", LocalDate.now().toString());
            Notes.save(c, HABITS, l, 30);
            return o;
        }
        return null;
    }

    static JSONArray habitsJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        String today = LocalDate.now().toString();
        for (JSONObject o : Notes.list(c, HABITS))
            a.put(new JSONObject().put("name", o.optString("name")).put("streak_days", streak(o)).put("best_streak", best(o))
                    .put("done_today", dates(o).contains(today)).put("days_done_last_30", countSince(o, LocalDate.now().minusDays(29))));
        return a;
    }

    private static int countSince(JSONObject o, LocalDate from) {
        int n = 0;
        for (String x : dates(o)) if (!LocalDate.parse(x).isBefore(from)) n++;
        return n;
    }

    // ================================================================ splitting a bill

    private static final Pattern PAIR = Pattern.compile("([^,;:=\\d]+?)\\s*[:=-]?\\s*(\\d+(?:\\.\\d+)?)\\s*(వేలు|వేల)?");

    /** "నేను 1,200, రవి Rs.800, సురేష్ 0" -> {name: amount} in order (names as first written, matched without case). */
    static Map<String, Double> pairs(String text) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (text == null) return m;
        String t = text.replaceAll("(?<=\\d),(?=\\d)", "")                              // 1,200 -> 1200
                .replaceAll("(?i)\\brs\\.?|₹|/-|రూపాయలు|రూ\\.?", " ");               // currency words
        Matcher x = PAIR.matcher(t);
        while (x.find()) {
            String n = x.group(1).trim().replaceAll("^(and|మరియు|,|;)\\s*", "").trim();
            if (n.isEmpty()) continue;
            double v = Double.parseDouble(x.group(2)) * (x.group(3) == null ? 1 : 1000);
            String same = null;
            for (String k : m.keySet()) if (k.equalsIgnoreCase(n)) same = k;
            m.merge(same != null ? same : n, v, Double::sum);
        }
        return m;
    }

    /**
     * Who pays whom so that everyone ends up paying their share (equal, or by the weights given), fewest payments.
     * Returns {total, share per person, transfers: ["సురేష్ → నేను ₹667", ...]}.
     */
    static JSONObject split(Map<String, Double> paid, Map<String, Double> weights) throws Exception {
        double total = 0, wsum = 0;
        for (double v : paid.values()) total += v;
        List<String> people = new ArrayList<>(paid.keySet());
        // shares are matched to the payers without case; anyone not given a share counts 1, unknown names are ignored
        Map<String, Double> wt = new LinkedHashMap<>();
        for (String p : people) {
            double w = 1.0;
            for (Map.Entry<String, Double> e : weights.entrySet()) if (e.getKey().equalsIgnoreCase(p)) w = e.getValue();
            wt.put(p, w);
        }
        Map<String, Double> share = new LinkedHashMap<>();
        for (String p : people) wsum += wt.get(p);
        if (wsum <= 0) { wsum = people.size(); for (String p : people) wt.put(p, 1.0); }
        JSONObject shares = new JSONObject();
        Map<String, Double> bal = new LinkedHashMap<>();
        for (String p : people) {
            double w = wt.get(p);
            double sh = total * w / wsum;
            share.put(p, sh);
            shares.put(p, Math.round(sh));
            bal.put(p, paid.get(p) - sh);
        }
        JSONArray moves = new JSONArray();
        for (int guard = 0; guard < 50; guard++) {
            String debtor = null, creditor = null;
            for (Map.Entry<String, Double> e : bal.entrySet()) {
                if (debtor == null || e.getValue() < bal.get(debtor)) debtor = e.getKey();
                if (creditor == null || e.getValue() > bal.get(creditor)) creditor = e.getKey();
            }
            double amt = Math.min(-bal.get(debtor), bal.get(creditor));
            if (amt < 1) break;
            moves.put(debtor + " → " + creditor + " ₹" + Math.round(amt));
            bal.put(debtor, bal.get(debtor) + amt);
            bal.put(creditor, bal.get(creditor) - amt);
        }
        return new JSONObject().put("ok", true).put("total", Math.round(total)).put("each_share", shares).put("transfers", moves);
    }

    // ================================================================ thunderstorm within the hour

    static void storm(Context c, Prefs p, boolean quiet) {
        if (!p.stormAlert()) return;
        int h = LocalTime.now().getHour();
        if ((h < 6 || h >= 22) && !p.driving()) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_storm", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - s.getLong("checked", 0) < 30 * 60000L) return;
        s.edit().putLong("checked", now).apply();
        if (now - s.getLong("warned", 0) < 3 * 3600000L) return;
        try {
            Location l = Tools.lastLocation(c);
            if (l == null) return;
            JSONObject w = Http.get(String.format(Locale.ENGLISH, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                    + "&hourly=weather_code,precipitation_probability,precipitation&forecast_hours=2&timezone=auto", l.getLatitude(), l.getLongitude()));
            JSONObject hr = w.getJSONObject("hourly");
            JSONArray code = hr.getJSONArray("weather_code"), prob = hr.getJSONArray("precipitation_probability"), mm = hr.getJSONArray("precipitation");
            boolean thunder = false, heavy = false;
            for (int i = 0; i < code.length(); i++) {
                int cd = code.optInt(i);
                if (cd >= 95 && prob.optInt(i) >= 50) thunder = true;
                if ((cd == 65 || cd == 82) && prob.optInt(i) >= 60 || mm.optDouble(i, 0) >= 8) heavy = true;
            }
            if (!thunder && !heavy) return;
            s.edit().putLong("warned", now).apply();
            String text = thunder ? "గంటలోపు ఉరుములు, మెరుపులతో వర్షం వచ్చేలా ఉంది. బైక్ మీద ఉంటే సురక్షితమైన భవనంలో ఆగండి; చెట్ల కింద, ఖాళీ మైదానంలో నిలబడకండి."
                    : "గంటలోపు భారీ వర్షం వచ్చేలా ఉంది. బయట ఉంటే రెయిన్‌కోట్ వేసుకోండి, నెమ్మదిగా వెళ్లండి.";
            Reminders.notify(c, thunder ? "⛈️ పిడుగుల హెచ్చరిక" : "🌧️ భారీ వర్షం", text, 100);
            if (!quiet || p.driving()) Announcer.say(c, p.name() + ", " + text);
        } catch (Exception ignored) {}
    }

    // ================================================================ habits: ask at night

    static void tick(Context c, Prefs p, boolean quiet) {
        storm(c, p, quiet);
        int hh = p.habitHour();
        if (hh < 0 || quiet) return;
        int h = LocalTime.now().getHour();
        if (h < hh || h > hh + 1) return;
        List<JSONObject> l = Notes.list(c, HABITS);
        if (l.isEmpty()) return;
        String today = LocalDate.now().toString();
        SharedPreferences s = c.getSharedPreferences("jarvis_habits", Context.MODE_PRIVATE);
        if (today.equals(s.getString("asked", ""))) return;
        List<String> left = new ArrayList<>();
        for (JSONObject o : l) if (!dates(o).contains(today)) left.add(o.optString("name"));
        if (left.isEmpty() || MainActivity.busyTalking() || CallControl.busyWithCall()) return;
        s.edit().putString("asked", today).apply();
        Proactive.say(c, p.name() + ", అలవాట్ల చెక్.", "ఈరోజు " + android.text.TextUtils.join(", ", left) + " చేశారా?",
                " [habits: for each one he says he did, call habit_track action done (name); not done -> nothing. Then tell the streaks in one short line, warmly.]");
    }
}
