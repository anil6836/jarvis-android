package com.anil.jarvis;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 🌧️ C17: the weather on the road ahead, on the car's box. Every 15 minutes while the car moves (over 30 km/h, with
 * net), the free forecast (Open-Meteo) for where he is and for about 15 km and 30 km ahead on his heading: rain there in
 * the next two hours that isn't here yet, or fog (under 1 km visibility). Said once per hour per kind, calmly ("ముందు సుమారు
 * 30 కి.మీ దగ్గర వర్షం"). The heading is the road's for now: on a winding road it is only "సుమారు".
 */
final class CarAhead {
    private CarAhead() {}

    private static volatile long lastCheck, rainTold, fogTold;
    private static volatile boolean busy;

    /** From the drive alerts' minute (whatever app is in front); does its work at most every 15 minutes, off the main thread. */
    static void maybe(Context c) {
        long now = System.currentTimeMillis();
        if (busy || now - lastCheck < 15 * 60_000L || !CarBox.on(c) || CarBox.speed() < 30 || !Net.online(c)) return;
        Location l = DriveService.last;
        float hd = DriveService.heading;
        if (l == null || hd < 0 || now - l.getTime() > 60_000) return;
        lastCheck = now;
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                double[] p15 = ahead(l.getLatitude(), l.getLongitude(), hd, 15), p30 = ahead(l.getLatitude(), l.getLongitude(), hd, 30);
                JSONArray all = asArray(Http.getText(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f,%.2f,%.2f&longitude=%.2f,%.2f,%.2f"
                                + "&hourly=precipitation,visibility&forecast_hours=2&timezone=auto",
                        l.getLatitude(), p15[0], p30[0], l.getLongitude(), p15[1], p30[1])));
                if (all == null || all.length() < 3) return;
                double[] here = sky(all.optJSONObject(0)), a15 = sky(all.optJSONObject(1)), a30 = sky(all.optJSONObject(2));
                String say = word(here, a15, a30, System.currentTimeMillis());
                if (say != null) Announcer.say(app, say);
            } catch (Throwable ignored) {
                // (no net / the forecast didn't come: the next check)
            } finally {
                busy = false;
            }
        }, "car-ahead").start();
    }

    /** One place's next two hours: {rain mm (most in an hour), lowest visibility m}. */
    private static double[] sky(JSONObject o) {
        double rain = 0, vis = Double.MAX_VALUE;
        JSONObject m = o == null ? null : o.optJSONObject("hourly");
        if (m == null) return new double[]{0, Double.MAX_VALUE};
        JSONArray p = m.optJSONArray("precipitation"), v = m.optJSONArray("visibility");
        for (int i = 0; p != null && i < p.length(); i++) rain = Math.max(rain, p.optDouble(i, 0));
        for (int i = 0; v != null && i < v.length(); i++) if (!v.isNull(i)) vis = Math.min(vis, v.optDouble(i, Double.MAX_VALUE));
        return new double[]{rain, vis};
    }

    private static JSONArray asArray(String body) {
        try {
            String b = body.trim();
            if (b.startsWith("[")) return new JSONArray(b);
            return new JSONArray().put(new JSONObject(b));
        } catch (Exception e) {
            return null;
        }
    }

    /** The point km ahead on a heading (degrees). Pure. */
    static double[] ahead(double lat, double lon, double headingDeg, double km) {
        double r = 6371.0, d = km / r, h = Math.toRadians(headingDeg), la = Math.toRadians(lat), lo = Math.toRadians(lon);
        double la2 = Math.asin(Math.sin(la) * Math.cos(d) + Math.cos(la) * Math.sin(d) * Math.cos(h));
        double lo2 = lo + Math.atan2(Math.sin(h) * Math.sin(d) * Math.cos(la), Math.cos(d) - Math.sin(la) * Math.sin(la2));
        return new double[]{Math.toDegrees(la2), Math.toDegrees(lo2)};
    }

    /**
     * What to say (null: nothing): rain ahead (≥ 0.5 mm in an hour there, not here), fog ahead or here (under
     * 1000 m), each at most once an hour. Not pure (remembers what it told) but needs no Android.
     */
    static String word(double[] here, double[] a15, double[] a30, long now) {
        boolean rainHere = here[0] >= 0.5;
        String where = a15[0] >= 0.5 ? "15" : a30[0] >= 0.5 ? "30" : null;
        if (!rainHere && where != null && now - rainTold > 60 * 60_000L) {
            rainTold = now;
            boolean heavy = Math.max(a15[0], a30[0]) >= 7.5;
            return "ముందు సుమారు " + where + " కి.మీ దగ్గర " + (heavy ? "గట్టి వర్షం" : "వర్షం") + " పడుతోంది / పడేలా ఉంది. వేగం తగ్గించి, దూరం పెట్టి, లైట్లు వేసుకోండి.";
        }
        double fog = Math.min(here[1], Math.min(a15[1], a30[1]));
        if (fog < 1000 && now - fogTold > 60 * 60_000L) {
            fogTold = now;
            return (here[1] < 1000 ? "ఇక్కడ" : "ముందు దారిలో") + " పొగమంచు (సుమారు " + Math.round(fog / 100) * 100 + " మీటర్ల వరకే కనిపిస్తుంది). ఫాగ్ లైట్లు, నెమ్మదిగా, ముందు వాహనానికి దూరంగా.";
        }
        return null;
    }

    /** (desk tests: start clean) */
    static void reset() { rainTold = 0; fogTold = 0; lastCheck = 0; }
}
