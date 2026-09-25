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
 * "ఇంటికి చేరగానే గుర్తుచేయి": reminders that fire when Anil arrives at or leaves a place.
 * Uses Android's own proximity alerts (no Google services needed). Saved places ("home",
 * "office") are remembered on the phone.
 */
final class GeoReminders {
    static final String ACTION_GEO = "com.anil.jarvis.GEO";
    static final float RADIUS_M = 200f;

    private GeoReminders() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("jarvis_geo", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- places

    static void savePlace(Context c, String name, double lat, double lon) {
        try {
            JSONObject places = new JSONObject(sp(c).getString("places", "{}"));
            places.put(key(name), new JSONObject().put("name", name).put("lat", lat).put("lon", lon));
            sp(c).edit().putString("places", places.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** A saved place by name ("home", "office"), or null. */
    static double[] place(Context c, String name) {
        try {
            JSONObject places = new JSONObject(sp(c).getString("places", "{}"));
            JSONObject p = places.optJSONObject(key(name));
            if (p != null) return new double[]{p.getDouble("lat"), p.getDouble("lon")};
        } catch (Exception ignored) {}
        return null;
    }

    static JSONArray places(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONObject places = new JSONObject(sp(c).getString("places", "{}"));
            java.util.Iterator<String> it = places.keys();
            while (it.hasNext()) out.put(places.getJSONObject(it.next()).optString("name"));
        } catch (Exception ignored) {}
        return out;
    }

    private static String key(String name) {
        String k = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (k.equals("ఇల్లు") || k.equals("ఇంటికి") || k.equals("ఇంట్లో") || k.equals("house") || k.equals("my home")) return "home";
        if (k.equals("ఆఫీస్") || k.equals("office") || k.equals("work") || k.equals("my office")) return "office";
        return k;
    }

    // ---------------------------------------------------------------- reminders

    static List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("reminders", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveAll(Context c, List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        sp(c).edit().putString("reminders", a.toString()).apply();
    }

    /** Adds and arms a reminder. insideNow: Anil is already there, so the first "arrived" is ignored. */
    static JSONObject add(Context c, String place, double lat, double lon, String text, boolean arrive, boolean insideNow) throws Exception {
        return add(c, place, lat, lon, text, arrive, insideNow, false);
    }

    /** command = true: "text" is something for Jarvis to do there (silent, lights on...), not a reminder. */
    static JSONObject add(Context c, String place, double lat, double lon, String text, boolean arrive, boolean insideNow, boolean command) throws Exception {
        JSONObject r = new JSONObject().put("command", command)
                .put("id", "g" + Long.toString(System.currentTimeMillis() % 100000000L, 36))
                .put("place", place).put("lat", lat).put("lon", lon)
                .put("text", text).put("arrive", arrive).put("skip_first", insideNow);
        List<JSONObject> list = all(c);
        list.add(r);
        saveAll(c, list);
        arm(c, r);
        return r;
    }

    static boolean cancel(Context c, String id) {
        List<JSONObject> list = all(c);
        boolean found = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).optString("id").equals(id)) {
                disarm(c, list.get(i));
                list.remove(i);
                found = true;
            }
        }
        saveAll(c, list);
        return found;
    }

    private static PendingIntent intent(Context c, JSONObject r) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_GEO).putExtra("id", r.optString("id"));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0); // Android adds "entering"
        return PendingIntent.getBroadcast(c, r.optString("id").hashCode(), i, flags);
    }

    static boolean canWatch(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static void arm(Context c, JSONObject r) {
        if (!canWatch(c)) return;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        try {
            lm.addProximityAlert(r.optDouble("lat"), r.optDouble("lon"), RADIUS_M, -1, intent(c, r));
        } catch (SecurityException ignored) {}
    }

    private static void disarm(Context c, JSONObject r) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        try { lm.removeProximityAlert(intent(c, r)); } catch (Exception ignored) {}
    }

    /** After a reboot or update the phone forgets proximity alerts; set them again. */
    static void rearmAll(Context c) {
        for (JSONObject r : all(c)) arm(c, r);
    }

    /** Called by AlarmReceiver when Anil enters or leaves a watched place. */
    static void fired(Context c, String id, boolean entering) {
        List<JSONObject> list = all(c);
        for (int i = 0; i < list.size(); i++) {
            JSONObject r = list.get(i);
            if (!r.optString("id").equals(id)) continue;
            if (r.optBoolean("skip_first") && entering) {
                // It was set while he was already there; wait until he leaves and comes back.
                try { r.put("skip_first", false); } catch (Exception ignored) {}
                saveAll(c, list);
                return;
            }
            if (entering != r.optBoolean("arrive")) {
                if (!entering) try { r.put("skip_first", false); } catch (Exception ignored) {}
                saveAll(c, list);
                return;
            }
            String where = r.optString("place");
            String text = r.optString("text");
            if (r.optBoolean("command")) {
                // an automatic mode for this place: keep it, and let Jarvis carry it out
                saveAll(c, list);
                Proactive.run(c, "(" + where + (entering ? " చేరారు" : " నుంచి బయలుదేరారు") + ", ఆటోమేటిక్‌గా) " + text);
                return;
            }
            disarm(c, r);
            list.remove(i);
            saveAll(c, list);
            Reminders.notify(c, "📍 " + where + (entering ? " చేరారు" : " నుంచి బయలుదేరారు"), text, id.hashCode());
            Announcer.say(c, new Prefs(c).name() + ", " + where + (entering ? " చేరారు. " : " నుంచి బయలుదేరారు. ") + "గుర్తుచేస్తున్నాను: " + text);
            return;
        }
    }

    static float distance(double lat1, double lon1, double lat2, double lon2) {
        float[] d = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, d);
        return d[0];
    }
}
