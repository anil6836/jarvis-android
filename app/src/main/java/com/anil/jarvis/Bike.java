package com.anil.jarvis;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Anil's electric bike (Matter Aera 5000+): rides are logged by themselves while the bike's Bluetooth is
 * connected (GPS distance, time), he tells Jarvis when he charged (battery % before / after, or rupees paid),
 * and Jarvis works out the range left, charging cost and cost per km. Everything stays on the phone.
 */
final class Bike {
    private Bike() {}

    private static final int REQ_RIDE = 7501;
    /** Home charging loses about a tenth on the way into the battery. */
    private static final double CHARGER_EFFICIENCY = 0.9;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_bike", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- his numbers (Settings)

    /** Real-world km on a full battery (Aera 5000+: about 110 km mixed riding; 172 km is the lab figure). */
    static int fullRangeKm(Prefs p) { return Math.max(20, p.sp.getInt("bike_range_km", 110)); }
    static float batteryKwh(Prefs p) { return Math.max(0.5f, p.sp.getFloat("bike_kwh", 5f)); }
    /** Rupees per unit (kWh) of electricity at home. */
    static float unitRate(Prefs p) { return Math.max(0f, p.sp.getFloat("power_rate", 8f)); }
    /** For "how much did the EV save": a petrol bike's km per litre and the petrol price he uses. */
    static float petrolKmpl(Prefs p) { return Math.max(10f, p.sp.getFloat("petrol_kmpl", 45f)); }
    static float petrolPrice(Prefs p) { return Math.max(50f, p.sp.getFloat("petrol_price", 107f)); }

    /**
     * What these km would have cost on a petrol bike, what they cost on the EV (his logged charging, or the units
     * at his home rate when no charging was logged), and the difference saved.
     */
    static JSONObject savings(Context c, double km, double chargingCost) throws Exception {
        Prefs p = new Prefs(c);
        double petrol = km / petrolKmpl(p) * petrolPrice(p);
        double byRate = km / fullRangeKm(p) * batteryKwh(p) / CHARGER_EFFICIENCY * unitRate(p);
        // charging he logged can miss some charges: never less than these km would take at his home rate
        boolean estimated = chargingCost < byRate;
        double ev = Math.max(chargingCost, byRate);
        return new JSONObject().put("km", round1(km)).put("petrol_bike_would_cost_rupees", Math.round(petrol))
                .put("ev_cost_rupees", Math.round(ev)).put("ev_cost_estimated", estimated)
                .put("saved_rupees", Math.round(petrol - ev))
                .put("assumed", "petrol bike " + Math.round(petrolKmpl(p)) + " km/litre at ₹" + Math.round(petrolPrice(p)) + "/litre");
    }

    // ---------------------------------------------------------------- rides (from the bike's Bluetooth)

    static boolean riding(Context c) { return sp(c).getBoolean("riding", false); }

    /** The bike connected: start a ride (a Bluetooth drop of under 3 minutes carries on the same ride). */
    static synchronized void rideStart(Context c) {
        SharedPreferences s = sp(c);
        if (s.getBoolean("riding", false)) return;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = s.edit().putBoolean("riding", true).remove("last").remove("last_t");
        JSONArray rides = arr(s, "rides");
        JSONObject prev = rides.length() > 0 ? rides.optJSONObject(rides.length() - 1) : null;
        if (prev != null && now - prev.optLong("end") < 3 * 60_000L) {
            rides.remove(rides.length() - 1);
            e.putString("rides", rides.toString()).putLong("start", prev.optLong("start"))
                    .putFloat("km", (float) prev.optDouble("km_tracked", 0)).putString("start_loc", prev.optString("from", ""));
        } else {
            e.putLong("start", now).putFloat("km", 0).putString("start_loc", freshPlace(c));
        }
        e.apply();
        track(c, true);
    }

    /** The bike disconnected: finish the ride; returns it (null when it was only a Bluetooth flicker). */
    static synchronized JSONObject rideEnd(Context c) {
        SharedPreferences s = sp(c);
        if (!s.getBoolean("riding", false)) return null;
        track(c, false);
        long now = System.currentTimeMillis(), start = s.getLong("start", now);
        double tracked = s.getFloat("km", 0), km = tracked;
        String from = s.getString("start_loc", ""), to = freshPlace(c);
        if (to.isEmpty()) to = s.getString("last", "");
        boolean approx = false;
        double straight = distanceKm(from, to);
        // the phone may have held back GPS points (no "Allow all the time" location): roads are ~1.3x the straight line
        if (straight > 0.3 && km < straight * 1.1) { km = straight * 1.3; approx = true; }
        s.edit().putBoolean("riding", false).remove("last").remove("last_t").apply();
        int minutes = (int) ((now - start) / 60000);
        if (minutes < 2 && km < 0.3) return null;
        try {
            JSONObject r = new JSONObject().put("start", start).put("end", now).put("minutes", minutes)
                    .put("km", round1(km)).put("km_tracked", round1(tracked)).put("approx", approx).put("from", from).put("to", to);
            JSONArray rides = arr(s, "rides");
            rides.put(r);
            while (rides.length() > 500) rides.remove(0);
            s.edit().putString("rides", rides.toString()).apply();
            return r;
        } catch (Exception ex) {
            return null;
        }
    }

    /** A GPS point during a ride (from RideReceiver). Jitter while standing and impossible jumps are left out. */
    static synchronized void onLocation(Context c, Location l) {
        SharedPreferences s = sp(c);
        if (l == null || !s.getBoolean("riding", false)) return;
        if (l.hasAccuracy() && l.getAccuracy() > 60) return;
        String here = l.getLatitude() + "," + l.getLongitude();
        String last = s.getString("last", "");
        long lastT = s.getLong("last_t", 0);
        SharedPreferences.Editor e = s.edit();
        if (!last.isEmpty()) {
            double d = distanceKm(last, here);
            if (d < 0.02) return; // standing still: keep the older point
            double hours = Math.max(1000, l.getTime() - lastT) / 3600000.0;
            if (d / hours <= 160) e.putFloat("km", (float) (s.getFloat("km", 0) + d)); // faster than 160 km/h = a GPS jump
        }
        e.putString("last", here).putLong("last_t", l.getTime()).apply();
    }

    private static PendingIntent locationIntent(Context c) {
        Intent i = new Intent(c, RideReceiver.class).setAction("com.anil.jarvis.RIDE_LOCATION");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0); // the system adds the location
        return PendingIntent.getBroadcast(c, REQ_RIDE, i, flags);
    }

    private static void track(Context c, boolean on) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        try {
            if (!on) { lm.removeUpdates(locationIntent(c)); return; }
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000, 25, locationIntent(c));
        } catch (Exception ignored) {}
    }

    /** Where the phone is, if known from the last 15 minutes ("lat,lon"), else "". */
    private static String freshPlace(Context c) {
        Location l = Tools.lastLocation(c);
        if (l == null || System.currentTimeMillis() - l.getTime() > 15 * 60_000L) return "";
        return l.getLatitude() + "," + l.getLongitude();
    }

    private static double distanceKm(String a, String b) {
        try {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
            String[] p = a.split(","), q = b.split(",");
            float[] d = new float[1];
            Location.distanceBetween(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(q[0]), Double.parseDouble(q[1]), d);
            return d[0] / 1000.0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** One line for the notification after a ride. */
    static String rideLine(Context c, JSONObject r) {
        Prefs p = new Prefs(c);
        int used = (int) Math.round(r.optDouble("km") * 100.0 / fullRangeKm(p));
        long cost = Math.round(r.optDouble("km") / fullRangeKm(p) * batteryKwh(p) / CHARGER_EFFICIENCY * unitRate(p));
        int left = estimatePct(c); // (phase 5, W39: what it cost and how far the battery still goes)
        return (r.optBoolean("approx") ? "సుమారు " : "") + r.optDouble("km") + " కి.మీ, " + r.optInt("minutes") + " నిమిషాలు"
                + (used > 0 ? " · బ్యాటరీ సుమారు " + used + "% వాడారు" : "") + (cost > 0 ? " · ఖర్చు సుమారు ₹" + cost : "")
                + (left >= 0 ? " · ఇంకా సుమారు " + Math.round(fullRangeKm(p) * left / 100.0) + " కి.మీ వెళ్లొచ్చు (~" + left + "%)" : "");
    }

    // ---------------------------------------------------------------- range and charging

    /** How far he can go on this battery %. */
    static JSONObject range(Context c, int pct) throws Exception {
        if (pct < 0 || pct > 100) return new JSONObject().put("ok", false).put("error", "Ask him the battery % shown on the bike (0-100).");
        Prefs p = new Prefs(c);
        int full = fullRangeKm(p);
        JSONObject o = new JSONObject().put("ok", true).put("battery_percent", pct)
                .put("range_km", Math.round(full * pct / 100.0))
                .put("full_charge_range_km", full);
        if (pct > 10) o.put("range_keeping_10_percent_km", Math.round(full * (pct - 10) / 100.0));
        return o.put("note", "Real-world estimate from his full-charge range in Settings. Eco mode goes further, Sport less; "
                + "a pillion, hills, headwind and speeds over 60 km/h cut it. Suggest charging below 20%.");
    }

    // ---------------------------------------------------------------- charging now: when it will be full

    static final String ACTION_CHARGE_DONE = "com.anil.jarvis.BIKE_CHARGE_DONE";
    private static final int REQ_CHARGE = 251, NOTE_CHARGE = 252;

    /**
     * Hours from a% to b% by Matter's figures for the Aera 5000+ (home charger 0-80% in 5 h, 0-100% in 6 h; fast charger
     * 0-80% in 1.5 h, 0-100% in 2 h), times how his own charges went so far.
     */
    static double hoursFor(Context c, int from, int to, boolean fast) {
        double below = fast ? 80 / 1.5 : 16, above = fast ? 40 : 20; // % per hour under and over 80%
        double h = 0;
        if (from < 80) h += (Math.min(to, 80) - from) / below;
        if (to > 80) h += (to - Math.max(from, 80)) / above;
        return h * factor(c, fast);
    }

    private static float factor(Context c, boolean fast) { return sp(c).getFloat(fast ? "fast_factor" : "home_factor", 1f); }

    /** The charge he started, if it is less than a day old: {from, to, fast, t, eta, logged}. */
    static JSONObject charging(Context c) {
        try {
            String s = sp(c).getString("charging", "");
            if (s.isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            return System.currentTimeMillis() - o.optLong("t") < 24 * 3600_000L ? o : null;
        } catch (Exception e) { return null; }
    }

    private static PendingIntent doneIntent(Context c) {
        return PendingIntent.getBroadcast(c, REQ_CHARGE, new Intent(c, AlarmReceiver.class).setAction(ACTION_CHARGE_DONE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** He plugged in at pct%: when it reaches target% (100, or 80 to stop there), with a word at that time. */
    static synchronized JSONObject chargeStart(Context c, int pct, int target, boolean fast) throws Exception {
        if (pct < 0 || pct > 99) return new JSONObject().put("ok", false).put("error", "Ask him the battery % shown on the bike now (0-99).");
        int to = target <= 0 ? 100 : Math.max(pct + 1, Math.min(100, target));
        double h = hoursFor(c, pct, to, fast);
        long now = System.currentTimeMillis(), eta = now + (long) (h * 3600_000);
        sp(c).edit().putString("charging", new JSONObject().put("from", pct).put("to", to).put("fast", fast).put("t", now).put("eta", eta).toString()).apply();
        armDone(c, eta);
        int mins = (int) Math.round(h * 60);
        return new JSONObject().put("ok", true).put("from", pct + "%").put("until", to + "%").put("charger", fast ? "fast charger" : "home charger")
                .put("ready_at", new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(eta)))
                .put("takes", (mins / 60) + " h " + (mins % 60) + " min")
                .put("based_on", factor(c, fast) == 1f ? "Matter's figures (0-80% in 5 h at home); it learns from his charges"
                        : "his own past charges")
                .put("next", "Tell him the time in Telugu. Jarvis reminds him then and writes the charge down"
                        + (to < 100 ? "; " + to + "% is kinder to the battery for daily use." : "."));
    }

    private static void armDone(Context c, long eta) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, eta, doneIntent(c));
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, eta, doneIntent(c));
        } catch (Exception e) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, eta, doneIntent(c));
        }
    }

    /** After a restart the phone forgets alarms: the charge's time comes back (or, if it passed, it is said now). */
    static void rearm(Context c) {
        JSONObject s = charging(c);
        if (s == null || s.optBoolean("logged")) return;
        if (s.optLong("eta") > System.currentTimeMillis()) armDone(c, s.optLong("eta"));
        else chargeDone(c);
    }

    static synchronized JSONObject chargeCancel(Context c) throws Exception {
        JSONObject s = charging(c);
        boolean had = s != null;
        if (s != null && s.optBoolean("logged")) dropEstimate(c, s.optInt("from")); // he unplugged it: the guess written for it goes too
        sp(c).edit().remove("charging").apply();
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        if (am != null) am.cancel(doneIntent(c));
        return new JSONObject().put("ok", true).put("cancelled", had);
    }

    /** The last charge entry, if it is Jarvis's estimate for a charge from this %: removed. */
    private static synchronized boolean dropEstimate(Context c, int from) {
        JSONArray a = arr(sp(c), "charges");
        JSONObject last = a.length() == 0 ? null : a.optJSONObject(a.length() - 1);
        if (last == null || !last.optBoolean("estimate") || last.optInt("from") != from) return false;
        a.remove(a.length() - 1);
        sp(c).edit().putString("charges", a.toString()).apply();
        return true;
    }

    /** The time it should be full: a word, and the charge written down (he can correct the %). */
    static void chargeDone(Context c) {
        int from, to;
        synchronized (Bike.class) { // read and mark in one go: a "done" or "cancel" from him at the same moment wins cleanly
            JSONObject s = charging(c);
            if (s == null || s.optBoolean("logged")) return;
            from = s.optInt("from");
            to = s.optInt("to");
            try {
                addCharge(c, from, to, 0, false, true);
                s.put("logged", true);
                sp(c).edit().putString("charging", s.toString()).apply();
            } catch (Exception ignored) {}
        }
        Prefs p = new Prefs(c);
        String text = "బైక్ ఛార్జ్ దాదాపు " + to + "% అయి ఉంటుంది" + (to < 100 ? ", ఛార్జర్ తీసేయండి" : "") + ". "
                + from + "% నుంచి " + to + "% అని రాసుకున్నాను; బైక్‌లో వేరే % చూపిస్తే చెప్పండి, సరిచేస్తాను.";
        Reminders.notify(c, "🔋 బైక్ ఛార్జ్ " + to + "%", text, NOTE_CHARGE);
        int hour = java.time.LocalTime.now().getHour();
        if (!p.night() && hour >= 6 && hour < 23) Announcer.say(c, p.name() + ", " + text);
    }

    /** He charged: battery % before and after, and rupees paid (0 = at home: priced by his unit rate). */
    static JSONObject addCharge(Context c, int fromPct, int toPct, double paid) throws Exception {
        return addCharge(c, fromPct, toPct, paid, false, false);
    }

    /**
     * justNow: he says it finished just now, so the time since he plugged in shows his charger's real speed.
     * estimate: Jarvis writing it down at the expected time (replaced if he then says the real %).
     */
    static synchronized JSONObject addCharge(Context c, int fromPct, int toPct, double paid, boolean justNow, boolean estimate) throws Exception {
        JSONObject now = estimate ? null : charging(c);
        if (fromPct < 0 && now != null) fromPct = now.optInt("from"); // "ఛార్జింగ్ అయిపోయింది, 100%": from the % he plugged in at
        if (fromPct < 0 || toPct > 100 || toPct <= fromPct)
            return new JSONObject().put("ok", false).put("error", "Ask him the battery % before and after charging (e.g. 25 to 100).");
        if (now != null && justNow && fromPct == now.optInt("from")) { // learn his charger's speed (clamped, half old, half new)
            double hours = (System.currentTimeMillis() - now.optLong("t")) / 3600_000.0;
            boolean fast = now.optBoolean("fast");
            double model = hoursFor(c, fromPct, toPct, fast) / factor(c, fast);
            // only a believable "just now": far off the figures (said hours later, e.g. in the morning) teaches nothing
            if (hours >= 0.25 && model > 0.1 && hours / model >= 0.6 && hours / model <= 1.6) {
                float f = (float) Math.max(0.5, Math.min(2.5, 0.5 * factor(c, fast) + 0.5 * hours / model));
                sp(c).edit().putFloat(fast ? "fast_factor" : "home_factor", f).apply();
            }
        }
        if (now != null) { // this charge is told: no more reminder for it
            sp(c).edit().remove("charging").apply();
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            if (am != null) am.cancel(doneIntent(c));
        }
        Prefs p = new Prefs(c);
        int added = toPct - fromPct;
        double kwh = added / 100.0 * batteryKwh(p) / CHARGER_EFFICIENCY;
        double cost = paid > 0 ? paid : kwh * unitRate(p);
        double kmAdded = added / 100.0 * fullRangeKm(p);
        JSONObject ch = new JSONObject().put("t", System.currentTimeMillis()).put("from", fromPct).put("to", toPct)
                .put("kwh", round2(kwh)).put("cost", Math.round(cost)).put("paid", paid > 0);
        if (estimate) ch.put("estimate", true);
        SharedPreferences s = sp(c);
        synchronized (Bike.class) {
            JSONArray a = arr(s, "charges");
            JSONObject last = a.length() == 0 ? null : a.optJSONObject(a.length() - 1);
            // the real % after Jarvis wrote an estimate for this charge: it takes the estimate's place
            if (!estimate && now != null && now.optBoolean("logged") && last != null && last.optBoolean("estimate")
                    && last.optInt("from") == now.optInt("from")) a.remove(a.length() - 1);
            a.put(ch);
            while (a.length() > 500) a.remove(0);
            s.edit().putString("charges", a.toString()).apply();
        }
        return new JSONObject().put("ok", true).put("charged", fromPct + "% -> " + toPct + "%").put("units_kwh", round2(kwh))
                .put("cost_rupees", Math.round(cost)).put("priced_by", paid > 0 ? "what he paid" : "home rate ₹" + unitRate(p) + " per unit")
                .put("km_added_about", Math.round(kmAdded)).put("rupees_per_km", round2(cost / Math.max(1, kmAdded)));
    }

    /**
     * Battery % now, worked out from the last charge he logged minus the km ridden since; -1 when not known
     * (no charge logged, or the last one is over 10 days old, so the guess would be poor).
     */
    static int estimatePct(Context c) {
        JSONArray a = arr(sp(c), "charges");
        if (a.length() == 0) return -1;
        JSONObject last = a.optJSONObject(a.length() - 1);
        if (last == null || System.currentTimeMillis() - last.optLong("t") > 10 * 86400000L) return -1;
        // rides are only logged with the bike's Bluetooth chosen; without them only a very fresh charge says anything
        if (new Prefs(c).carBluetooth().isEmpty() && System.currentTimeMillis() - last.optLong("t") > 12 * 3600000L) return -1;
        double km = 0;
        for (JSONObject r : list(c, "rides", last.optLong("t"))) km += r.optDouble("km");
        int pct = (int) Math.round(last.optInt("to") - km * 100.0 / fullRangeKm(new Prefs(c)));
        return Math.max(0, Math.min(100, pct));
    }

    // ---------------------------------------------------------------- reports

    static List<JSONObject> list(Context c, String key, long since) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = arr(sp(c), key);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("rides".equals(key) ? "start" : "t") >= since) out.add(o);
        }
        return out;
    }

    /** Rides and charging since then: km, time, cost, cost per km, and the rides themselves (newest first, up to 10). */
    static JSONObject summary(Context c, long since) throws Exception {
        List<JSONObject> rides = list(c, "rides", since), charges = list(c, "charges", since);
        double km = 0, longest = 0, cost = 0;
        int minutes = 0;
        boolean approx = false;
        JSONArray recent = new JSONArray();
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH);
        for (int i = rides.size() - 1; i >= 0; i--) {
            JSONObject r = rides.get(i);
            km += r.optDouble("km");
            minutes += r.optInt("minutes");
            longest = Math.max(longest, r.optDouble("km"));
            approx |= r.optBoolean("approx");
            if (recent.length() < 10) recent.put(new JSONObject().put("when", f.format(new Date(r.optLong("start"))))
                    .put("km", r.optDouble("km")).put("minutes", r.optInt("minutes")).put("approx", r.optBoolean("approx")));
        }
        for (JSONObject ch : charges) cost += ch.optDouble("cost");
        JSONObject o = new JSONObject().put("ok", true).put("rides", rides.size()).put("km", round1(km)).put("minutes", minutes)
                .put("longest_ride_km", round1(longest)).put("charges", charges.size()).put("charging_cost_rupees", Math.round(cost))
                .put("recent_rides", recent);
        if (km > 0 && cost > 0) o.put("rupees_per_km", round2(cost / km));
        if (km > 0) o.put("vs_petrol_bike", savings(c, km, cost));
        if (riding(c)) o.put("riding_now", true);
        if (approx) o.put("note", "Some rides are approximate (the phone held back GPS points; 'Allow all the time' location makes them exact).");
        if (rides.isEmpty()) o.put("how", "Rides are logged by themselves once the bike's Bluetooth is chosen in Settings → కార్/బైక్.");
        return o;
    }

    // ---------------------------------------------------------------- helpers

    private static JSONArray arr(SharedPreferences s, String key) {
        try { return new JSONArray(s.getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
