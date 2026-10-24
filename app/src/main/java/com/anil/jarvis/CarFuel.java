package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ⛽ C12: how far the diesel goes, on the car's box. The car's own fuel level when the OBD adapter gives it (C11); else
 * worked out: a fill told in the car ("ఫుల్" = the tank's litres; else the litres added), minus the km the box counted
 * since, at the car's usual mileage (the phone's car account says it; until then his own number in Settings). Always
 * "సుమారు": the car's own dashboard (DTE) is the one to trust. Low → said once ("సుమారు 70 కి.మీ కే").
 */
final class CarFuel {
    private CarFuel() {}

    static final float TANK_DEFAULT = 44f, KMPL_DEFAULT = 16f; // (his Nexon diesel's tank; a first guess of mileage)

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_car_fuel", Context.MODE_PRIVATE); }

    static float tank(Context c) { return Math.max(20f, Math.min(90f, sp(c).getFloat("tank_l", TANK_DEFAULT))); }

    static void setTank(Context c, float l) { sp(c).edit().putFloat("tank_l", l).apply(); }

    /** The mileage used: the phone's car account, else his number (Settings), else 16. */
    static double kmpl(Context c) {
        double k = CarKm.kmpl(c);
        if (!Double.isNaN(k)) return k;
        float own = sp(c).getFloat("kmpl", 0f);
        return own > 3 ? own : KMPL_DEFAULT;
    }

    static void setKmpl(Context c, float k) { sp(c).edit().putFloat("kmpl", k).apply(); }

    // ================================================================ a fill told in the car

    /** A fill went from the car (CarLink, at once: online or not). The same fill told again (after the phone asked "is the
     *  meter right?") within half an hour is not added twice. */
    static synchronized void onFill(Context c, JSONObject a) {
        double l = Car.number(a, "litres"), rs = Car.number(a, "rupees");
        SharedPreferences s = sp(c);
        String key = a.optString("rupees") + "|" + a.optString("litres") + "|" + a.optString("odometer") + "|" + a.optString("date") + "|" + a.optBoolean("full");
        if (key.equals(s.getString("fill_key", "")) && System.currentTimeMillis() - s.getLong("fill_key_at", 0) < 30 * 60_000L) return;
        s.edit().putString("fill_key", key).putLong("fill_key_at", System.currentTimeMillis()).putLong("box_fill_at", System.currentTimeMillis()).apply();
        if (!Double.isNaN(l) && !Double.isNaN(rs) && l > 0) s.edit().putFloat("price", (float) (rs / l)).apply();
        if (Double.isNaN(l) && !Double.isNaN(rs) && s.getFloat("price", 0f) > 30) l = rs / s.getFloat("price", 0f);
        double est = after(estimateRaw(c), l, a.optBoolean("full"), tank(c));
        s.edit().putFloat("est_l", (float) est).putFloat("est_odo", (float) CarKm.odoKm(c)).remove("low1").remove("low2").apply();
    }

    /** Litres in the tank after a fill: full → the tank; else what was there (if known) + what went in. NaN: unknown. Pure. */
    static double after(double before, double added, boolean full, double tank) {
        if (full) return tank;
        if (Double.isNaN(before) || Double.isNaN(added) || added <= 0) return Double.NaN;
        return Math.min(tank, Math.max(0, before) + added);
    }

    /**
     * Litres now from the last estimate and the km since, at kmpl. Far below empty (over 3 litres short) means a fill was
     * missed somewhere: not known (NaN), never a "0 km" warning. Pure.
     */
    static double litresNow(double est, double kmSince, double kmpl) {
        if (Double.isNaN(est) || kmpl <= 0) return Double.NaN;
        double l = est - Math.max(0, kmSince) / kmpl;
        return l < -3 ? Double.NaN : Math.max(0, l);
    }

    /** The phone wrote this fill from the box's own words (its id): the phone's "last fill" for it is not added again. */
    static synchronized void boxFillWritten(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        sp(c).edit().putString("box_fill_ids", (id + "," + sp(c).getString("box_fill_ids", "")).replaceAll("^((?:[^,]*,){10}).*$", "$1")).apply();
    }

    /**
     * The phone's newest fill (CarKm.extras): one told on the phone itself, not through the box, is added to the tank
     * guess at its own time (the km since then from the box's own hours).
     */
    static synchronized void phoneFill(Context c, JSONObject f) {
        SharedPreferences s = sp(c);
        String id = f.optString("id");
        long at = f.optLong("at");
        if (id.isEmpty() || at <= 0 || ("," + s.getString("box_fill_ids", "")).contains("," + id + ",")) return;
        if (id.equals(s.getString("phone_fill_id", ""))) return;
        s.edit().putString("phone_fill_id", id).apply();
        if (at <= s.getLong("box_fill_at", 0) || System.currentTimeMillis() - at > 7 * 86_400_000L) return; // (older than what the box knows / too old)
        double kmSince = CarKm.kmSince(c, at), odoNow = CarKm.odoKm(c);
        double before = s.contains("est_l") ? litresNow(s.getFloat("est_l", Float.NaN), (odoNow - kmSince) - s.getFloat("est_odo", 0f), kmpl(c)) : Double.NaN;
        double est = after(before, f.optDouble("l", Double.NaN), f.optBoolean("full"), tank(c));
        s.edit().putFloat("est_l", (float) est).putFloat("est_odo", (float) (odoNow - kmSince)).putLong("box_fill_at", at).remove("low1").remove("low2").apply();
    }

    /** The car's own fuel level jumped up (OBD): a fill — the low-diesel words may come again later. */
    static void refuelSeen(Context c) { sp(c).edit().remove("low1").remove("low2").apply(); }

    /** The worked-out litres now (NaN: not known yet: no fill told in the car since Jarvis began). */
    private static double estimateRaw(Context c) {
        SharedPreferences s = sp(c);
        if (!s.contains("est_l")) return Double.NaN;
        float e = s.getFloat("est_l", Float.NaN);
        return litresNow(e, CarKm.odoKm(c) - s.getFloat("est_odo", 0f), kmpl(c));
    }

    /** Litres now: the car's own fuel level (fresh, from the OBD adapter), else worked out; NaN when not known. */
    static double litres(Context c) {
        if (ObdWatch.fuelPct >= 0 && System.currentTimeMillis() - ObdWatch.fuelAt < 3 * 60_000L) return ObdWatch.fuelPct / 100.0 * tank(c);
        return estimateRaw(c);
    }

    /** km the diesel goes (NaN: not known). */
    static double rangeKm(Context c) {
        double l = litres(c);
        return Double.isNaN(l) ? Double.NaN : l * kmpl(c);
    }

    /** The car screen's chip: "⛽ ~320 కి.మీ" ("" when not known). */
    static String chip(Context c) {
        double r = rangeKm(c);
        return Double.isNaN(r) ? "" : "⛽ ~" + Math.round(r / 10) * 10 + " కి.మీ";
    }

    /** Low diesel, said once each (under 80 km, then under 40), only while moving; null otherwise. */
    static String lowWord(Context c) {
        double r = rangeKm(c);
        if (Double.isNaN(r) || !CarBox.moving()) return null;
        SharedPreferences s = sp(c);
        String key = r < 40 ? "low2" : r < 80 ? "low1" : null;
        if (key == null || s.getBoolean(key, false)) return null;
        s.edit().putBoolean(key, true).putBoolean("low1", true).apply();
        return "డీజిల్ సుమారు " + Math.round(r / 10) * 10 + " కి.మీ కే సరిపోతుంది (డాష్‌బోర్డ్ కూడా ఒకసారి చూడండి). 'Jarvis, దగ్గర్లో డీజిల్ బంక్' అని అడగండి.";
    }

    /** car_account "range" in the car: what is known, for the AI to say. */
    static JSONObject json(Context c) throws Exception {
        double l = litres(c), r = rangeKm(c);
        boolean obd = ObdWatch.fuelPct >= 0 && System.currentTimeMillis() - ObdWatch.fuelAt < 3 * 60_000L;
        JSONObject o = new JSONObject().put("ok", true).put("kmpl_used", Math.round(kmpl(c) * 10) / 10.0).put("tank_litres", tank(c));
        if (Double.isNaN(r)) return o.put("known", false).put("say", "డీజిల్ ఎంత ఉందో ఇంకా తెలియదు: ఈసారి ట్యాంక్ ఫుల్ చేయించి 'ఫుల్' అని box కి చెప్పండి (లేదా OBD adapter పెట్టండి).");
        return o.put("known", true).put("litres_about", Math.round(l)).put("km_about", Math.round(r / 10) * 10).put("from", obd ? "the car's own fuel level (OBD)" : "worked out from the last fill and km")
                .put("next", "Say it in one short Telugu line, with 'సుమారు', and that the car's dashboard range is the one to trust.");
    }

    static String settingsLine(Context c) {
        double r = rangeKm(c);
        return String.format(Locale.ROOT, "⛽ ట్యాంక్ %.0f లీటర్లు · మైలేజ్ %.1f కి.మీ/లీ%s", tank(c), kmpl(c),
                Double.isNaN(r) ? " · ఇప్పుడు: తెలియదు (ఒకసారి ఫుల్ కొట్టించి box కి చెప్పండి)" : " · ఇప్పుడు సుమారు " + Math.round(r / 10) * 10 + " కి.మీ");
    }
}
