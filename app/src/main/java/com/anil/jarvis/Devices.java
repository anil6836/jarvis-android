package com.anil.jarvis;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * "నా ఇయర్‌బడ్స్ ఎక్కడ?": every Bluetooth thing (earbuds, headset, watch, speaker, car) is noted when it connects and
 * disconnects; on a disconnect the phone's place then (a fix of the last few minutes) is kept, so Jarvis can say
 * where it was last with him. Kept on the phone, the newest 100 devices.
 */
final class Devices {
    private Devices() {}

    private static final long MIN = 60000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_bt_seen", Context.MODE_PRIVATE); }

    /** From the Bluetooth broadcast: noted at once (no waiting); a disconnect gets its place a moment later, on its own thread. */
    static void seen(Context c, BluetoothDevice d, boolean connected) {
        if (d == null || d.getAddress() == null) return;
        String name = null;
        try { name = d.getName(); } catch (SecurityException ignored) {}
        String addr = d.getAddress();
        long at = System.currentTimeMillis();
        if (!note(c, addr, name, connected, at, null) || connected) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Location l = freshFix(app);
            if (l != null && Math.abs(l.getTime() - at) < 10 * MIN) note(app, addr, null, false, at, l);
        }, "jarvis-bt-place").start();
    }

    /** Writes one event; an older event never overwrites a newer one (a reconnect a second later). */
    private static synchronized boolean note(Context c, String addr, String name, boolean connected, long at, Location l) {
        try {
            JSONObject o = new JSONObject(sp(c).getString(addr, "{}"));
            if (o.optLong("at") > at) return false;
            if (l != null && (o.optLong("at") != at || o.optBoolean("connected"))) return false; // the place is for this very disconnect only
            if (name != null && !name.trim().isEmpty()) o.put("name", name.trim());
            if (!o.has("name")) o.put("name", addr);
            o.put("connected", connected).put("at", at);
            if (l != null) o.put("lat", l.getLatitude()).put("lon", l.getLongitude()).put("acc", Math.round(l.getAccuracy())).put("loc_at", l.getTime());
            else if (!connected) o.remove("lat");
            SharedPreferences.Editor e = sp(c).edit().putString(addr, o.toString());
            Map<String, ?> all = sp(c).getAll();
            if (all.size() > 100) { // the oldest go
                List<Object[]> ages = new ArrayList<>();
                for (Map.Entry<String, ?> en : all.entrySet()) {
                    try { ages.add(new Object[]{en.getKey(), new JSONObject(String.valueOf(en.getValue())).optLong("at")}); } catch (Exception x) { ages.add(new Object[]{en.getKey(), 0L}); }
                }
                ages.sort((a, b) -> Long.compare((long) a[1], (long) b[1]));
                for (int i = 0; i < ages.size() - 100; i++) if (!ages.get(i)[0].equals(addr)) e.remove((String) ages.get(i)[0]);
            }
            e.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A place fix now if possible (up to 8 s; never on the main thread), else the last one known. */
    static Location freshFix(Context c) {
        Location last = Tools.lastLocation(c);
        if (last != null && System.currentTimeMillis() - last.getTime() < 2 * MIN) return last;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.CancellationSignal stop = new android.os.CancellationSignal();
            try {
                LocationManager lm = c.getSystemService(LocationManager.class);
                String provider = android.os.Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                        : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
                final Location[] got = {null};
                CountDownLatch done = new CountDownLatch(1);
                lm.getCurrentLocation(provider, stop, c.getMainExecutor(), l -> { got[0] = l; done.countDown(); });
                done.await(8, TimeUnit.SECONDS);
                if (got[0] != null) return got[0];
            } catch (Exception ignored) {
            } finally {
                stop.cancel(); // no fix in time: don't keep the radio busy
            }
        }
        return last;
    }

    /** Kinds of Bluetooth things and the words for them (the first word is the kind's name). */
    private static final String[][] KINDS = {
            {"earbuds", "ఇయర్‌బడ్స్", "ఇయర్ బడ్స్", "ఇయర్బడ్స్", "earbud", "earphone", "ఇయర్‌ఫోన్", "airdopes", "airpods", "buds", "tws"},
            {"headset", "హెడ్‌సెట్", "హెడ్‌ఫోన్", "headphone", "headset", "neckband", "నెక్‌బ్యాండ్", "rockerz"},
            {"watch", "వాచ్", "watch", "smartwatch"},
            {"speaker", "స్పీకర్", "speaker", "soundbar"}};

    private static String plain(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\u200c\\u200d]", ""); }

    /** Which kind his words ask for (earbuds, headset...), or null. */
    private static String[] kindOf(String q) {
        for (String[] k : KINDS) for (int i = 1; i < k.length; i++) if (q.contains(plain(k[i]))) return k;
        return null;
    }

    /** Is this a question about a Bluetooth thing at all (for the "where is it?" fallback)? */
    static boolean isGadget(String what) { return kindOf(plain(what)) != null; }

    /** The devices that fit his words ("ఇయర్‌బడ్స్", "boAt", "watch"), newest first; all of them when nothing fits. */
    static JSONObject find(Context c, String what) throws Exception {
        String q = plain(what).trim();
        String[] kind = q.isEmpty() ? null : kindOf(q);
        JSONArray hit = new JSONArray(), all = new JSONArray();
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH);
        Location now = Tools.lastLocation(c);
        if (now != null && System.currentTimeMillis() - now.getTime() > 10 * MIN) now = null; // "metres from here" needs a recent here
        List<JSONObject> list = new ArrayList<>();
        for (Object v : sp(c).getAll().values()) {
            try { list.add(new JSONObject(String.valueOf(v))); } catch (Exception ignored) {}
        }
        list.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        for (JSONObject o : list) {
            String n = plain(o.optString("name"));
            JSONObject x = new JSONObject().put("device", o.optString("name")).put("connected_now", o.optBoolean("connected"))
                    .put(o.optBoolean("connected") ? "connected_since" : "last_seen", f.format(new Date(o.optLong("at"))));
            if (!o.optBoolean("connected") && o.has("lat")) {
                x.put("map", "https://maps.google.com/?q=" + o.optDouble("lat") + "," + o.optDouble("lon")).put("accuracy_m", o.optInt("acc"));
                if (now != null) x.put("meters_from_here_now", Math.round(Drive.meters(now.getLatitude(), now.getLongitude(), o.optDouble("lat"), o.optDouble("lon"))));
                JSONObject place = nearPlace(c, o.optDouble("lat"), o.optDouble("lon"));
                if (place != null) x.put("near_saved_place", place.optString("name"));
            } else if (!o.optBoolean("connected")) x.put("place", "not known (no location fix when it left)");
            all.put(x);
            boolean match = n.length() >= 3 && q.length() >= 3 && (n.contains(q) || q.contains(n));
            if (!match && kind != null) for (int i = 1; i < kind.length; i++) if (n.contains(plain(kind[i]))) match = true;
            if (!match) for (String w : q.split("\\s+")) if (w.length() >= 4 && n.contains(w)) match = true;
            if (match) hit.put(x);
        }
        if (all.length() == 0) return new JSONObject().put("ok", false).put("error", "none_yet")
                .put("message", "No Bluetooth device noted yet: Jarvis notes each one when it disconnects from now on.");
        return new JSONObject().put("ok", true).put("matches", hit.length() > 0 ? hit : all).put("exact", hit.length() > 0)
                .put("next", "Say where it was when it last left the phone (time, and the saved place / metres from here). If it is connected now, say so. "
                        + "Offer to open the map spot (open_maps with that link) if it is far.");
    }

    private static JSONObject nearPlace(Context c, double lat, double lon) {
        JSONObject best = null;
        double bd = 150;
        for (JSONObject p : Places.all(c)) {
            if (!p.has("lat")) continue;
            double d = Drive.meters(lat, lon, p.optDouble("lat"), p.optDouble("lon"));
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }
}
