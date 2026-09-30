package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/**
 * "ఈ ఊరికి బైక్ మీద వెళ్లగలనా?": road distance (free OSRM router, else straight line x 1.3) against the bike's charge,
 * and chargers on the way when it isn't enough. Also a 9 pm nudge to charge when it is low before a duty or a low battery.
 */
final class Ride {
    private Ride() {}

    /** Road km and minutes, or straight line x 1.3 when the router can't be reached: {km, minutes, estimated}. */
    static double[] road(double la1, double lo1, double la2, double lo2) {
        try {
            JSONObject r = Http.get(String.format(Locale.ENGLISH, "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=false",
                    lo1, la1, lo2, la2));
            JSONObject route = r.getJSONArray("routes").getJSONObject(0);
            return new double[]{route.optDouble("distance") / 1000.0, route.optDouble("duration") / 60.0, 0};
        } catch (Exception e) {
            double km = GeoReminders.distance(la1, lo1, la2, lo2) / 1000.0 * 1.3;
            return new double[]{km, km / 40 * 60, 1};
        }
    }

    static JSONObject plan(Context c, double la1, double lo1, double la2, double lo2, String to, int pct, boolean roundTrip) throws Exception {
        Prefs p = new Prefs(c);
        int full = Bike.fullRangeKm(p);
        if (pct < 0) pct = Bike.estimatePct(c);
        double[] rd = road(la1, lo1, la2, lo2);
        double need = rd[0] * (roundTrip ? 2 : 1);
        JSONObject o = new JSONObject().put("ok", true).put("to", to).put("one_way_km", Math.round(rd[0])).put("ride_minutes_one_way", Math.round(rd[1]))
                .put("round_trip", roundTrip).put("km_needed", Math.round(need)).put("distance_estimated", rd[2] > 0)
                .put("full_charge_real_range_km", full);
        if (pct < 0) return o.put("battery", "unknown").put("next", "Ask him the battery % on the dashboard and call ride_plan again with it.");
        long usable = Math.round(full * (pct - 10) / 100.0); // keep 10% in hand
        o.put("battery_percent", pct).put("range_now_km", Math.round(full * pct / 100.0)).put("usable_keeping_10pct_km", Math.max(0, usable));
        boolean enough = usable >= need;
        o.put("enough", enough);
        if (enough) {
            o.put("margin_km", usable - Math.round(need));
            if (usable < need * 1.2) o.put("tip", "Only a small margin: ride in Eco, steady 40-50 km/h, no pillion if possible.");
        } else {
            o.put("short_by_km", Math.round(need - usable));
            // chargers about where the charge runs low, and near the destination
            double f = Math.max(0.15, Math.min(0.85, usable * 0.85 / rd[0]));
            double mla = la1 + (la2 - la1) * f, mlo = lo1 + (lo2 - lo1) * f;
            JSONArray way = new JSONArray(), there = new JSONArray();
            try { for (JSONObject x : Nearby.find(mla, mlo, "amenity=charging_station", 12000, 3)) way.put(x); } catch (Exception ignored) {}
            try { for (JSONObject x : Nearby.find(la2, lo2, "amenity=charging_station", 10000, 3)) there.put(x); } catch (Exception ignored) {}
            o.put("chargers_on_the_way", way).put("chargers_near_destination", there)
                    .put("charge_needed_percent", Math.min(100, (int) Math.ceil((need / full) * 100 + 10)));
        }
        return o.put("next", "Say in short Telugu: distance and time, whether the charge is enough (with the margin), and if not, how much more "
                + "charge he needs and the chargers on the way / near there (km, name). Charger apps show live availability. To go: open_maps navigate.");
    }

    // ---------------------------------------------------------------- 9 pm: charge tonight?

    static void tick(Context c, Prefs p, boolean quiet) {
        if (!p.chargeRemind()) return;
        int h = LocalTime.now().getHour();
        if (h < 21 || h >= 23) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_ride", Context.MODE_PRIVATE);
        String today = LocalDate.now().toString();
        if (today.equals(s.getString("charge", ""))) return;
        int pct = Bike.estimatePct(c);
        if (pct < 0) return;
        boolean dutyTomorrow = false;
        int trip = 0;
        try {
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r)) { dutyTomorrow = Duty.startsDuty(r, LocalDate.now().plusDays(1)); trip = Duty.tripKm(c, r); }
        } catch (Exception ignored) {}
        int full = Bike.fullRangeKm(p);
        long usable = Math.round(full * (pct - 10) / 100.0);
        String text = null;
        if (dutyTomorrow && trip > 0 && usable < trip * 2) text = "రేపు డ్యూటీ. బైక్‌లో సుమారు " + pct + "% మాత్రమే ఉంది, వెళ్లి రావడానికి " + trip * 2 + " కి.మీ కావాలి. ఈ రాత్రే ఛార్జ్ పెట్టండి.";
        else if (dutyTomorrow && trip == 0 && pct < 50) text = "రేపు డ్యూటీ. బైక్‌లో సుమారు " + pct + "% ఉంది, ఈ రాత్రే ఛార్జ్ పెట్టడం మంచిది.";
        else if (pct < 25) text = "బైక్‌లో సుమారు " + pct + "% మాత్రమే ఉంది. ఈ రాత్రి ఛార్జ్ పెట్టండి.";
        if (text == null) return;
        s.edit().putString("charge", today).apply();
        Reminders.notify(c, "🔋 బైక్ ఛార్జ్", text, 97);
        if (!quiet) Announcer.say(c, p.name() + ", " + text);
    }

}
