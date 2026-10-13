package com.anil.jarvis;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "ఇక్కడ గుంత ఉంది": places Anil marked as dangerous (a pothole, a broken speed breaker, a flooded stretch, a loose wire).
 * Kept on the phone (prefs "jarvis_hazards", 200 at most); when he comes within about 90 m again Jarvis says so (once,
 * then not again for 20 minutes) and the watch buzzes. Android's own proximity alerts, re-armed after a reboot.
 */
final class Hazards {
    private Hazards() {}

    static final String ACTION = "com.anil.jarvis.HAZARD";
    static final float RADIUS_M = 90f;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_hazards", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void write(Context c, List<JSONObject> l) {
        while (l.size() > 200) l.remove(0);
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        sp(c).edit().putString("list", a.toString()).apply();
    }

    /** Marks a danger here (or at the given place). scanId: the camera's picture of it, if any. Returns what was saved, or null. */
    static JSONObject add(Context c, double lat, double lon, String what, String scanId) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        List<JSONObject> l = all(c);
        try {
            for (JSONObject o : l) { // the same spot again: the words are updated
                float[] d = new float[1];
                Location.distanceBetween(lat, lon, o.optDouble("lat"), o.optDouble("lon"), d);
                if (d[0] < 40) { o.put("what", what).put("t", System.currentTimeMillis()); if (scanId != null) o.put("scan", scanId); write(c, l); arm(c, o); return o; }
            }
            JSONObject o = new JSONObject().put("id", "h" + Long.toString(System.currentTimeMillis(), 36)).put("lat", lat).put("lon", lon)
                    .put("what", what == null || what.trim().isEmpty() ? "ప్రమాదం" : what.trim()).put("t", System.currentTimeMillis());
            if (scanId != null) o.put("scan", scanId);
            l.add(o);
            write(c, l);
            arm(c, o);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized boolean remove(Context c, String id) {
        List<JSONObject> l = all(c);
        boolean ok = false;
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).optString("id").equals(id)) { disarm(c, l.get(i)); l.remove(i); ok = true; }
        if (ok) write(c, l);
        return ok;
    }

    /** The hazards within `km` of a place, nearest first. */
    static List<JSONObject> near(Context c, double lat, double lon, double km) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(c)) {
            float[] d = new float[1];
            Location.distanceBetween(lat, lon, o.optDouble("lat"), o.optDouble("lon"), d);
            if (d[0] <= km * 1000) { try { o.put("m", Math.round(d[0])); } catch (Exception ignored) {} out.add(o); }
        }
        out.sort((x, y) -> x.optInt("m") - y.optInt("m"));
        return out;
    }

    /** The kind of danger in his words → a short label ("గుంత", "స్పీడ్ బ్రేకర్", "నీళ్లు", "వైరు", or his words). */
    static String label(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.matches(".*(గుంత|గోతు|గొయ్యి|pothole|hole).*")) return "గుంత";
        if (t.matches(".*(స్పీడ్ బ్రేకర్|బ్రేకర్|bump|breaker|హంప్).*")) return "స్పీడ్ బ్రేకర్";
        if (t.matches(".*(నీళ్లు|నీరు|మునిగ|water|flood|వరద).*")) return "రోడ్డు మీద నీళ్లు";
        if (t.matches(".*(వైరు|కరెంట్|wire|electric|స్తంభం).*")) return "వేలాడే వైరు / కరెంట్";
        if (t.matches(".*(కుక్క|dog).*")) return "కుక్కలు";
        if (t.matches(".*(ఇసుక|కంకర|జారు|oil|sand|gravel|slippery).*")) return "జారే రోడ్డు";
        if (t.matches(".*(పని|construction|తవ్వ|dig).*")) return "రోడ్డు పని";
        String w = t.replaceAll("ఇక్కడ|అక్కడ|ఉంది|ఉన్నాయి|గుర్తుపెట్టు|గుర్తు పెట్టు|ప్రమాదం|జాగ్రత్త|mark|here|danger|hazard|జార్విస్|jarvis", " ").replaceAll("\\s+", " ").trim();
        return w.isEmpty() ? "ప్రమాదం" : w.length() > 30 ? w.substring(0, 30) : w;
    }

    /** "ఇక్కడ గుంత ఉంది", "ఇక్కడ ప్రమాదం గుర్తుపెట్టు", "mark a pothole here". */
    static boolean isMark(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return t.matches(".*(ఇక్కడ|అక్కడ|here).*") && t.matches(".*(గుంత|గోతు|గొయ్యి|స్పీడ్ బ్రేకర్|బ్రేకర్|నీళ్లు|మునిగ|వరద|వైరు|ప్రమాదం|జాగ్రత్త|జారు|రోడ్డు పని|pothole|bump|breaker|flood|hazard|danger|wire).*")
                && !t.matches(".*(ఎక్కడ|where|ఉందా\\?|ఉన్నాయా).*");
    }

    // ---- the phone watches the spots

    static boolean canWatch(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private static PendingIntent intent(Context c, JSONObject o) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION).putExtra("id", o.optString("id"));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, ("hz" + o.optString("id")).hashCode(), i, flags);
    }

    private static void arm(Context c, JSONObject o) {
        if (!canWatch(c)) return;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        try { lm.addProximityAlert(o.optDouble("lat"), o.optDouble("lon"), RADIUS_M, -1, intent(c, o)); } catch (SecurityException ignored) {}
    }

    private static void disarm(Context c, JSONObject o) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        try { lm.removeProximityAlert(intent(c, o)); } catch (Exception ignored) {}
    }

    static void rearmAll(Context c) { for (JSONObject o : all(c)) arm(c, o); }

    /** He came near a marked spot: said once, then quiet for 20 minutes for that spot. */
    static void fired(Context c, String id, boolean entering) {
        if (!entering) return;
        List<JSONObject> l = all(c);
        for (JSONObject o : l) {
            if (!o.optString("id").equals(id)) continue;
            long last = o.optLong("said", 0);
            if (System.currentTimeMillis() - last < 20 * 60_000L) return;
            try { o.put("said", System.currentTimeMillis()); } catch (Exception ignored) {}
            write(c, l);
            String when = new java.text.SimpleDateFormat("d MMM", Locale.ENGLISH).format(new java.util.Date(o.optLong("t")));
            String text = "⚠️ ముందు " + o.optString("what") + " (మీరు " + when + " న గుర్తుపెట్టినది). జాగ్రత్త.";
            Reminders.notify(c, "⚠️ ముందు ప్రమాదం", text, ("hz" + id).hashCode());
            Announcer.say(c, text);
            return;
        }
    }

    /** The list, in words. */
    static String listText(Context c, Location here) {
        List<JSONObject> l = here == null ? all(c) : near(c, here.getLatitude(), here.getLongitude(), 50);
        if (l.isEmpty()) return "మీరు గుర్తుపెట్టిన ప్రమాద చోట్లు ఏవీ లేవు.";
        StringBuilder b = new StringBuilder(l.size() + " చోట్లు: ");
        for (int i = 0; i < l.size() && i < 8; i++) {
            JSONObject o = l.get(i);
            b.append(i == 0 ? "" : "; ").append(o.optString("what"));
            if (o.has("m")) b.append(" (").append(o.optInt("m") < 1000 ? o.optInt("m") + " మీ" : String.format(Locale.US, "%.1f కి.మీ", o.optInt("m") / 1000.0)).append(")");
        }
        return b.toString();
    }
}
