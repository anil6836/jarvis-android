package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 📏 C13 / C14: the car's own km, counted by the car's box GPS (it is always in the car, every trip), go to the car
 * account on his phone once a day — the km of each hour only, never where he went. The phone uses them instead of its
 * trip diary's car trips for those days (better mileage), and answers with the mileage and the service's state, which
 * the box keeps for the fuel range (C12) and says at a trip's start when the service is near (C14).
 */
final class CarKm {
    private CarKm() {}

    private static final long HOUR = 3_600_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_car_km", Context.MODE_PRIVATE); }

    // ================================================================ the box: counting

    private static double pendingM, odoM = -1;
    private static long pendingHour = -1, savedAt;

    /** The start of the local hour holding t (the phone's day: an hour never straddles midnight). Pure. */
    static long hourOf(long t, ZoneId z) {
        long off = z.getRules().getOffset(Instant.ofEpochMilli(t)).getTotalSeconds() * 1000L;
        return t - Math.floorMod(t + off, HOUR);
    }

    /**
     * One GPS step of the car (metres, from DriveService; the box only). Counted only while the car really moves (5 km/h
     * or more: a parked car's GPS wander is never km), and only while the box's clock agrees with the GPS (a box that
     * booted with a wrong time never files km under a wrong hour).
     */
    static synchronized void add(Context c, double m, long now, double kmh, long fixTime) {
        if (!(m > 3 && m < 2000) || kmh < 5 || Math.abs(now - fixTime) > 10 * 60_000L) return;
        long hour = hourOf(now, ZoneId.systemDefault());
        if (odoM < 0) odoM = sp(c).getFloat("odo_m", 0f);
        if (pendingHour >= 0 && hour != pendingHour) save(c); // (a new hour: the last one is written)
        pendingHour = hour;
        pendingM += m;
        odoM += m;
        if (now - savedAt > 60_000) save(c); // (at most a minute is lost when the car is switched off)
    }

    private static synchronized void save(Context c) {
        savedAt = System.currentTimeMillis();
        if (pendingHour < 0) return;
        try {
            JSONObject h = new JSONObject(sp(c).getString("hours", "{}"));
            String k = String.valueOf(pendingHour);
            h.put(k, Math.round((h.optDouble(k, 0) + pendingM / 1000.0) * 1000) / 1000.0);
            pendingM = 0;
            sp(c).edit().putString("hours", prune(h, savedAt, sp(c).getLong("sent_upto", 0)).toString()).putFloat("odo_m", (float) odoM).apply();
        } catch (Exception ignored) {}
    }

    /** km the box counted since this time (its hours from then on; the hour holding it counted whole). */
    static synchronized double kmSince(Context c, long t) {
        save(c);
        double km = 0;
        try {
            JSONObject h = new JSONObject(sp(c).getString("hours", "{}"));
            long from = hourOf(t, ZoneId.systemDefault());
            Iterator<String> it = h.keys();
            while (it.hasNext()) { String k = it.next(); if (Long.parseLong(k) >= from) km += h.optDouble(k, 0); }
        } catch (Exception ignored) {}
        return km;
    }

    /** The box's own odometer (km since Jarvis began counting in this car). */
    static synchronized double odoKm(Context c) {
        if (odoM < 0) odoM = sp(c).getFloat("odo_m", 0f);
        return odoM / 1000.0;
    }

    /** Old hours are dropped: sent ones after 10 days; ones the phone never got after 60 days. Pure. */
    static JSONObject prune(JSONObject h, long now, long sentUpto) {
        JSONObject out = new JSONObject();
        Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                long t = Long.parseLong(k);
                if (now - t < (t > sentUpto ? 60 : 10) * 24 * HOUR) out.put(k, h.optDouble(k));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** The finished hours not sent yet: {hourStartMs: km}. Pure. */
    static JSONObject unsent(JSONObject hours, long sentUpto, long now) {
        JSONObject out = new JSONObject();
        long thisHour = hourOf(now, ZoneId.systemDefault());
        Iterator<String> it = hours.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                long h = Long.parseLong(k);
                if (h > sentUpto && h < thisHour && hours.optDouble(k) > 0.05) out.put(k, hours.optDouble(k));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Once a day (the trip's start, with net and the phone linked): yesterday's and older hours go to the phone (background). */
    static void sendDaily(Context c) {
        final Context app = c.getApplicationContext();
        if (!CarBox.on(app) || !HomeLink.linked(app) || !Net.online(app)) return;
        if (System.currentTimeMillis() - sp(app).getLong("sent_try", 0) < 20 * HOUR) return; // (once a day: one message to his phone)
        sp(app).edit().putLong("sent_try", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                save(app);
                JSONObject hours = new JSONObject(sp(app).getString("hours", "{}"));
                JSONObject send = unsent(hours, sp(app).getLong("sent_upto", 0), System.currentTimeMillis());
                if (send.length() == 0) { CarLink.request(app, new JSONObject().put("action", "status")); return; } // (no km: still the mileage / service)
                // (marked sent only when the phone has them: CarLink keeps the request and tries again meanwhile; an hour
                // sent twice is only written again on the phone, never added twice)
                JSONObject r = CarLink.request(app, new JSONObject().put("action", "km").put("hours", send));
                if (r.optBoolean("ok") && !r.optBoolean("queued")) delivered(app, send);
            } catch (Throwable ignored) {}
        }, "car-km").start();
    }

    /** These hours reached the phone: marked sent (the next day's sending starts after them). */
    static synchronized void delivered(Context c, JSONObject hours) {
        long upto = sp(c).getLong("sent_upto", 0);
        Iterator<String> it = hours == null ? new ArrayList<String>().iterator() : hours.keys();
        while (it.hasNext()) try { upto = Math.max(upto, Long.parseLong(it.next())); } catch (Exception ignored) {}
        sp(c).edit().putLong("sent_upto", upto).apply();
    }

    // ================================================================ the box: what the phone said back (mileage, service)

    /** The phone's answer to any car request: its mileage and the service's state are kept on the box. */
    static void fromPhone(Context c, JSONObject r) {
        if (r == null) return;
        SharedPreferences.Editor e = sp(c).edit();
        if (r.optDouble("kmpl", 0) > 3) e.putFloat("kmpl", (float) r.optDouble("kmpl"));
        JSONObject s = r.optJSONObject("svc");
        if (s != null) e.putString("svc", s.toString()).putLong("svc_at", System.currentTimeMillis());
        e.apply();
        JSONObject lf = r.optJSONObject("last_fill");
        if (lf != null) CarFuel.phoneFill(c, lf); // (a fill told on the phone: the box's tank guess hears it too)
    }

    /** His car's mileage as the phone last said (km per litre), or NaN. */
    static double kmpl(Context c) {
        float k = sp(c).getFloat("kmpl", 0f);
        return k > 3 ? k : Double.NaN;
    }

    /** C14: the service's word for the trip's start, once a day, when it is near or due (null otherwise). */
    static String serviceWord(Context c) {
        SharedPreferences s = sp(c);
        if (System.currentTimeMillis() - s.getLong("svc_at", 0) > 20 * 24 * HOUR) return null; // (too old to trust)
        JSONObject svc;
        try { svc = new JSONObject(s.getString("svc", "{}")); } catch (Exception e) { return null; }
        LocalDate today = LocalDate.now();
        String line = serviceShort(svc, today);
        if (line == null) return null;
        if (today.toString().equals(s.getString("svc_told", ""))) return null;
        s.edit().putString("svc_told", today.toString()).apply();
        return line;
    }

    // ================================================================ the phone: the box's km in the car account

    /** The box's hours on his phone (CarLink "km"): kept, and the car account reads them as its car trips. */
    static synchronized JSONObject toPhone(Context c, JSONObject hours) throws Exception {
        JSONObject all = new JSONObject(c.getSharedPreferences(Car.PREFS, Context.MODE_PRIVATE).getString("box_hours", "{}"));
        int n = 0;
        double km = 0;
        Iterator<String> it = hours == null ? new ArrayList<String>().iterator() : hours.keys();
        while (it.hasNext()) {
            String k = it.next();
            double v = hours.optDouble(k, 0);
            if (v <= 0 || v > 200) continue; // (an hour of more than 200 km is a GPS jump, never the car)
            all.put(k, v);
            km += v;
            n++;
        }
        c.getSharedPreferences(Car.PREFS, Context.MODE_PRIVATE).edit().putString("box_hours", all.toString()).putLong("box_hours_at", System.currentTimeMillis()).apply();
        return new JSONObject().put("ok", true).put("said", n == 0 ? "" : String.format(Locale.ROOT, "కారు box కి.మీ %.1f (%d గంటలు) లెక్కలో చేర్చాను.", km, n));
    }

    /**
     * The car trips the car account reads: the trip diary's car trips, except on days the car's box counted (its hours
     * are the whole day's car km, never a cab ride); then the box's hours ({hour's end, km}). Pure.
     */
    static List<double[]> merge(List<double[]> diary, JSONObject boxHours, ZoneId z) {
        List<double[]> out = new ArrayList<>();
        Set<LocalDate> boxDays = new HashSet<>();
        List<double[]> box = new ArrayList<>();
        if (boxHours != null) {
            Iterator<String> it = boxHours.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    long h = Long.parseLong(k);
                    double v = boxHours.optDouble(k, 0);
                    if (v <= 0) continue;
                    boxDays.add(Instant.ofEpochMilli(h).atZone(z).toLocalDate());
                    box.add(new double[]{h + HOUR - 1, v});
                } catch (Exception ignored) {}
            }
        }
        if (diary != null) for (double[] t : diary) if (!boxDays.contains(Instant.ofEpochMilli((long) t[0]).atZone(z).toLocalDate())) out.add(t);
        out.addAll(box);
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        return out;
    }

    /** The phone's extra words for the box: the usual mileage and the service's state, short. */
    static JSONObject extras(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONObject st = Car.load(c);
            if (!CarBook.isSet(st)) return o;
            List<double[]> tr = Car.trips(c);
            JSONObject u = CarBook.usual(CarBook.segments(st, tr));
            if (u != null && u.optDouble("kmpl", 0) > 0) o.put("kmpl", u.optDouble("kmpl"));
            ZoneId z = ZoneId.systemDefault();
            JSONObject s = CarBook.service(st, tr, LocalDate.now(z), System.currentTimeMillis(), z);
            if (s.optBoolean("known")) o.put("svc", new JSONObject().put("state", s.optString("state")).put("due_date", s.optString("due_date"))
                    .put("km_meter", s.optBoolean("km_meter")).put("km_left", s.optLong("km_left")).put("approx", s.optBoolean("approx")));
            List<JSONObject> fs = CarBook.fills(st); // (the newest fill, wherever he told it: the box's tank guess, C12)
            if (!fs.isEmpty()) {
                JSONObject f = fs.get(fs.size() - 1);
                o.put("last_fill", new JSONObject().put("id", f.optString("id")).put("at", f.optLong("at")).put("l", f.optDouble("l", 0)).put("full", f.optBoolean("full")));
            }
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * The service's short word on the box's own day (its days worked out from the due date, never an old count):
     * "కారు సర్వీస్ టైమ్ అయింది" / "కారు సర్వీస్ 6 రోజుల్లో లేదా ఇంకా సుమారు 420 కి.మీ"; null when not near (over 15 days, 500 km). Pure.
     */
    static String serviceShort(JSONObject s, LocalDate today) {
        LocalDate due;
        try { due = LocalDate.parse(s.optString("due_date")); } catch (Exception e) { return null; }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, due);
        boolean meter = s.optBoolean("km_meter");
        long left = s.optLong("km_left");
        if (days <= 0 || meter && left <= 0 || "due".equals(s.optString("state"))) return "కారు సర్వీస్ టైమ్ అయింది";
        if (days > CarBook.SOON_DAYS && !(meter && left <= CarBook.SOON_KM)) return null;
        String km = meter ? " లేదా ఇంకా " + (s.optBoolean("approx") ? "సుమారు " : "") + left + " కి.మీ" : "";
        return "కారు సర్వీస్ " + days + " రోజుల్లో" + km;
    }
}
