package com.anil.jarvis;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everyday helpers that work without an open screen: bill due dates and parcels from SMS,
 * the monthly budget, air quality and severe weather, screen time and app limits,
 * cricket score watching, and the car's Bluetooth.
 */
final class Life {
    private Life() {}

    private static SharedPreferences st(Context c) {
        return c.getSharedPreferences("jarvis_life", Context.MODE_PRIVATE);
    }

    private static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static long dayStart() {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static long monthStart() {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(dayStart());
        k.set(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    /** Inbox SMS since a time: {address, body, date}. */
    static List<String[]> sms(Context c, long since, int max) {
        List<String[]> out = new ArrayList<>();
        if (!has(c, Manifest.permission.READ_SMS)) return out;
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body", "date"},
                "date >= ?", new String[]{String.valueOf(since)}, "date DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < max) {
                out.add(new String[]{cur.getString(0), cur.getString(1), String.valueOf(cur.getLong(2))});
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ================================================================ bills due

    private static final String MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";
    private static final Pattern NUM_DATE = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})");
    private static final Pattern WORD_DATE = Pattern.compile("(\\d{1,2})(?:st|nd|rd|th)?[-\\s]?(" + MONTHS + ")[a-z]*[-,\\s']*(\\d{2,4})?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_DATE2 = Pattern.compile("(" + MONTHS + ")[a-z]*\\s+(\\d{1,2}),?\\s*(\\d{4})?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    private static int month(String m) {
        return MONTHS.indexOf(m.substring(0, 3).toLowerCase(Locale.ROOT)) / 4;
    }

    private static long date(int d, int m0, int y) {
        if (y < 100) y += 2000;
        if (d < 1 || d > 31 || m0 < 0 || m0 > 11) return -1;
        Calendar k = Calendar.getInstance();
        k.clear();
        k.set(y, m0, d, 12, 0);
        return k.getTimeInMillis();
    }

    private static long findDate(String s, long sentAt) {
        Matcher m = NUM_DATE.matcher(s);
        if (m.find()) return date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
        m = WORD_DATE.matcher(s);
        if (m.find()) {
            Calendar k = Calendar.getInstance();
            k.setTimeInMillis(sentAt);
            int y = m.group(3) != null ? Integer.parseInt(m.group(3)) : k.get(Calendar.YEAR);
            return date(Integer.parseInt(m.group(1)), month(m.group(2)), y);
        }
        m = WORD_DATE2.matcher(s);
        if (m.find()) {
            Calendar k = Calendar.getInstance();
            k.setTimeInMillis(sentAt);
            int y = m.group(3) != null ? Integer.parseInt(m.group(3)) : k.get(Calendar.YEAR);
            return date(Integer.parseInt(m.group(2)), month(m.group(1)), y);
        }
        return -1;
    }

    /** Bills with a due date from today on, found in SMS of the last 45 days. */
    static JSONArray billsDue(Context c) {
        JSONArray out = new JSONArray();
        Map<String, Boolean> seen = new HashMap<>();
        long today = dayStart();
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH);
        for (String[] m : sms(c, System.currentTimeMillis() - 45 * 86400000L, 800)) {
            String body = m[1] == null ? "" : m[1];
            String low = body.toLowerCase(Locale.ROOT);
            int k = -1;
            for (String w : new String[]{"due date", "due on", "due by", "payment due", "pay by", "last date", "is due", "due"}) {
                k = low.indexOf(w);
                if (k >= 0) break;
            }
            if (k < 0 || low.contains("otp")) continue;
            if (low.contains("paid") && !low.contains("unpaid") && !low.contains("not paid")) continue; // already paid
            long due = findDate(body.substring(k), Long.parseLong(m[2]));
            if (due < today) continue;
            String who = m[0] == null ? "" : m[0].replaceAll("^[A-Z]{2}-", "");
            String key = who + "|" + due;
            if (seen.containsKey(key)) continue;
            seen.put(key, true);
            Matcher a = AMOUNT.matcher(body);
            try {
                out.put(new JSONObject().put("from", who).put("due", f.format(new Date(due))).put("due_ms", due)
                        .put("amount", a.find() ? a.group(1) : "")
                        .put("sms", body.replaceAll("\\s+", " ").replaceAll("[0-9Xx*]{6,}", "…").substring(0, Math.min(140, body.length()))));
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ================================================================ parcels

    private static final String[] SHOPS = {"amazon", "amzn", "flipkart", "fkrt", "meesho", "myntra", "ajio", "nykaa", "delhivery",
            "ekart", "bluedart", "dtdc", "xpressbees", "shadowfax", "ecom", "shiprocket", "india post", "indpost", "jiomart", "tata"};
    private static final String[] STATUS = {"out for delivery", "delivered", "shipped", "dispatched", "arriving", "will be delivered",
            "in transit", "order placed", "order confirmed", "picked up", "attempted"};

    static JSONArray parcels(Context c) {
        JSONArray out = new JSONArray();
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH);
        List<String[]> all = new ArrayList<>(sms(c, System.currentTimeMillis() - 15 * 86400000L, 600));
        for (NotifyListener.Item it : NotifyListener.recent("", 150)) all.add(new String[]{it.app, it.from + ": " + it.text, String.valueOf(it.when)});
        for (String[] m : all) {
            String body = m[1] == null ? "" : m[1];
            String low = (m[0] + " " + body).toLowerCase(Locale.ROOT);
            boolean shop = false, status = false;
            for (String s : SHOPS) if (low.contains(s)) { shop = true; break; }
            for (String s : STATUS) if (low.contains(s)) { status = true; break; }
            if (!shop || !status || low.contains("otp is") || out.length() >= 25) continue;
            try {
                out.put(new JSONObject().put("when", f.format(new Date(Long.parseLong(m[2])))).put("from", m[0])
                        .put("text", body.replaceAll("\\s+", " ").substring(0, Math.min(200, body.length()))));
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ================================================================ budget

    static void budgetCheck(Context c, Prefs p) {
        int budget = p.budget();
        if (budget <= 0 || !has(c, Manifest.permission.READ_SMS)) return;
        try {
            long spent = Tools.spendingSince(c, monthStart(), 0).optLong("total_spent");
            int pct = (int) (spent * 100 / budget);
            String month = new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
            int warned = st(c).getString("budget_month", "").equals(month) ? st(c).getInt("budget_warned", 0) : 0;
            int level = pct >= 100 ? 100 : pct >= 80 ? 80 : 0;
            if (level == 0 || level <= warned) return;
            st(c).edit().putString("budget_month", month).putInt("budget_warned", level).apply();
            Proactive.say(c, p.name() + ", ఈ నెల ఇప్పటికే " + spent + " రూపాయలు ఖర్చయ్యాయి. మీ బడ్జెట్ " + budget + " లో "
                    + pct + " శాతం" + (level == 100 ? " దాటేశారు." : ". జాగ్రత్తగా ఖర్చు పెట్టండి."), null, null);
        } catch (Exception ignored) {}
    }

    // ================================================================ air quality and severe weather

    static JSONObject air(Context c) {
        try {
            Location l = Tools.lastLocation(c);
            if (l == null) return null;
            JSONObject r = Http.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + l.getLatitude()
                    + "&longitude=" + l.getLongitude() + "&current=us_aqi,pm2_5,pm10");
            JSONObject cur = r.optJSONObject("current");
            if (cur == null) return null;
            int aqi = cur.optInt("us_aqi", -1);
            String level = aqi < 0 ? "unknown" : aqi <= 50 ? "good" : aqi <= 100 ? "moderate" : aqi <= 150 ? "unhealthy for sensitive people"
                    : aqi <= 200 ? "unhealthy" : aqi <= 300 ? "very unhealthy" : "hazardous";
            return new JSONObject().put("us_aqi", aqi).put("level", level).put("pm2_5", cur.opt("pm2_5")).put("pm10", cur.opt("pm10"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Heavy rain, strong wind or extreme heat today, as one Telugu sentence, or null. */
    static String severeToday(Context c) {
        try {
            Location l = Tools.lastLocation(c);
            if (l == null) return null;
            JSONObject r = Http.get("https://api.open-meteo.com/v1/forecast?latitude=" + l.getLatitude() + "&longitude=" + l.getLongitude()
                    + "&daily=precipitation_sum,wind_gusts_10m_max,temperature_2m_max&forecast_days=1&timezone=auto");
            JSONObject d = r.optJSONObject("daily");
            if (d == null) return null;
            double rain = d.optJSONArray("precipitation_sum").optDouble(0, 0);
            double gust = d.optJSONArray("wind_gusts_10m_max").optDouble(0, 0);
            double hot = d.optJSONArray("temperature_2m_max").optDouble(0, 0);
            if (rain >= 50) return "ఈరోజు భారీ వర్షం (" + Math.round(rain) + " mm) పడే అవకాశం ఉంది. జాగ్రత్తగా ఉండండి.";
            if (gust >= 60) return "ఈరోజు గంటకు " + Math.round(gust) + " కిలోమీటర్ల వేగంతో బలమైన గాలులు వీచొచ్చు.";
            if (hot >= 42) return "ఈరోజు ఎండ " + Math.round(hot) + " డిగ్రీలు దాటొచ్చు. నీళ్లు ఎక్కువ తాగండి, మధ్యాహ్నం బయట తిరగకండి.";
        } catch (Exception ignored) {}
        return null;
    }

    // ================================================================ screen time and app limits

    static boolean usageAllowed(Context c) {
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Minutes in front per app since a time (from open/close events, so it is exact). */
    static Map<String, Long> usage(Context c, long since) {
        Map<String, Long> total = new HashMap<>();
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return total;
        UsageEvents ev = um.queryEvents(since, System.currentTimeMillis());
        Map<String, Long> openAt = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            String pkg = e.getPackageName();
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                openAt.put(pkg, e.getTimeStamp());
            } else if (e.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                Long t = openAt.remove(pkg);
                if (t != null) total.put(pkg, total.getOrDefault(pkg, 0L) + (e.getTimeStamp() - t));
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> o : openAt.entrySet()) total.put(o.getKey(), total.getOrDefault(o.getKey(), 0L) + (now - o.getValue()));
        return total;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    static JSONObject screenTime(Context c, int days) throws Exception {
        long since = dayStart() - (Math.max(1, days) - 1) * 86400000L;
        Map<String, Long> u = usage(c, since);
        PackageManager pm = c.getPackageManager();
        List<Map.Entry<String, Long>> list = new ArrayList<>(u.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long sum = 0;
        JSONArray top = new JSONArray();
        for (Map.Entry<String, Long> x : list) {
            if (pm.getLaunchIntentForPackage(x.getKey()) == null) continue; // system parts, launcher
            sum += x.getValue();
            if (top.length() < 8) top.put(new JSONObject().put("app", label(c, x.getKey())).put("minutes", x.getValue() / 60000));
        }
        return new JSONObject().put("ok", true).put("days", days).put("total_minutes", sum / 60000).put("top_apps", top);
    }

    static JSONObject limits(Context c) {
        try { return new JSONObject(st(c).getString("limits", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void setLimit(Context c, String pkg, int minutes) throws Exception {
        JSONObject l = limits(c);
        if (minutes <= 0) l.remove(pkg); else l.put(pkg, minutes);
        st(c).edit().putString("limits", l.toString()).apply();
    }

    static void limitTick(Context c, Prefs p) {
        JSONObject l = limits(c);
        if (l.length() == 0 || !usageAllowed(c)) return;
        Map<String, Long> u = usage(c, dayStart());
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        java.util.Iterator<String> it = l.keys();
        while (it.hasNext()) {
            String pkg = it.next();
            long used = u.getOrDefault(pkg, 0L) / 60000;
            int max = l.optInt(pkg);
            String key = "limit_" + pkg;
            if (used >= max && !day.equals(st(c).getString(key, ""))) {
                st(c).edit().putString(key, day).apply();
                Proactive.say(c, p.name() + ", ఈరోజు " + label(c, pkg) + " " + used + " నిమిషాలు వాడారు. మీరు పెట్టుకున్న పరిమితి "
                        + max + " నిమిషాలు దాటింది.", null, null);
            }
        }
    }

    // ================================================================ cricket

    static void cricketTick(Context c, Prefs p) {
        SharedPreferences s = st(c);
        String team = s.getString("cricket_team", "");
        if (team.isEmpty() || p.apiKey().isEmpty()) return;
        if (System.currentTimeMillis() - s.getLong("cricket_since", 0) > 12 * 3600000L) { stopCricket(c); return; }
        try {
            String ans = Brain.oneShot(p, "You check live cricket scores on the web. Reply with ONLY compact JSON, no other text.",
                    "Is a cricket match involving " + team + " live right now or finished in the last 2 hours? JSON: "
                            + "{\"live\":true|false,\"finished\":true|false,\"match\":\"A vs B\",\"batting\":\"team batting now\","
                            + "\"score\":\"e.g. 145/3 (18.2)\",\"wickets\":number,\"result\":\"result if finished\"}", null, true);
            int a = ans.indexOf('{'), b = ans.lastIndexOf('}');
            if (a < 0 || b <= a) return;
            JSONObject j = new JSONObject(ans.substring(a, b + 1));
            if (j.optBoolean("finished") && !j.optString("result").isEmpty()) {
                if (!j.optString("result").equals(s.getString("cricket_result", ""))) {
                    s.edit().putString("cricket_result", j.optString("result")).apply();
                    Proactive.say(c, "మ్యాచ్ అయిపోయింది: " + j.optString("result"), null, null);
                    stopCricket(c);
                }
                return;
            }
            if (!j.optBoolean("live")) return;
            String batting = j.optString("batting");
            int w = j.optInt("wickets", -1);
            String lastBat = s.getString("cricket_batting", "");
            int lastW = s.getInt("cricket_wickets", -1);
            s.edit().putString("cricket_batting", batting).putInt("cricket_wickets", w).apply();
            if (!batting.equals(lastBat) && !lastBat.isEmpty()) {
                Proactive.say(c, "ఇన్నింగ్స్ మారింది. " + batting + " బ్యాటింగ్. స్కోర్ " + j.optString("score"), null, null);
            } else if (lastW >= 0 && w > lastW) {
                Proactive.say(c, "వికెట్! " + batting + " " + j.optString("score"), null, null);
            } else if (lastBat.isEmpty()) {
                Proactive.say(c, j.optString("match") + " లైవ్: " + batting + " " + j.optString("score"), null, null);
            }
        } catch (Exception ignored) {}
    }

    static void watchCricket(Context c, String team) {
        st(c).edit().putString("cricket_team", team).putLong("cricket_since", System.currentTimeMillis())
                .remove("cricket_batting").remove("cricket_wickets").remove("cricket_result").apply();
    }

    static void stopCricket(Context c) {
        st(c).edit().remove("cricket_team").apply();
    }

    // ================================================================ car Bluetooth

    /** The car/bike connected or disconnected: driving mode, and remember where it was parked. */
    static void carBluetooth(Context c, String address, boolean connected) {
        Prefs p = new Prefs(c);
        if (address == null || !address.equalsIgnoreCase(p.carBluetooth())) return;
        if (connected) {
            if (p.driving()) return;
            p.set("driving", true);
            p.set("night", false);
            Announcer.say(c, p.name() + ", డ్రైవింగ్ మోడ్ ఆన్. మెసేజ్‌లు, కాల్స్ నేను చెబుతాను. జాగ్రత్తగా వెళ్లండి.");
        } else {
            p.set("driving", false);
            Location l = Tools.lastLocation(c);
            if (l != null) {
                GeoReminders.savePlace(c, "parking", l.getLatitude(), l.getLongitude());
                st(c).edit().putLong("parked_at", System.currentTimeMillis()).apply();
                Reminders.notify(c, "🅿️ బండి ఇక్కడ పెట్టారు", "\"Jarvis, నా బండి ఎక్కడ?\" అంటే దారి చూపిస్తాను.", 61);
            }
        }
    }

    static long parkedAt(Context c) { return st(c).getLong("parked_at", 0); }

    static void markParked(Context c) { st(c).edit().putLong("parked_at", System.currentTimeMillis()).apply(); }

    static Intent walkTo(double lat, double lon) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=w"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
