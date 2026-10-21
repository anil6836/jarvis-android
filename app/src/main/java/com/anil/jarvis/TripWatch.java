package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🚗 While he drives to a place Jarvis knows (the drive alerts on): 🚦 the traffic checked every 20 minutes (at most 8
 * times a trip) — a delay of 10 minutes or more, or a quicker road, said in Telugu; 👵 amma's tablet told when he will
 * reach home (only with its switch on); 🅿️ parking near the destination 2 km before it; ⌚ a quiet trip card on the
 * watch (time left, arrival); 🧾 the trip kept in the diary. Never over his talk (then a notification), never a way
 * set by itself. The drive service calls fix() on every GPS fix and end() when it stops.
 */
final class TripWatch {
    private TripWatch() {}

    private static final int NOTE_TRAFFIC = 7081, NOTE_PARK = 7082, WATCH_CARD = 7090;
    private static final ExecutorService work = Executors.newSingleThreadExecutor();

    // this trip (the worker thread; volatile where the main thread reads)
    private static volatile long lastTick, startedAt, lastCheckAt, cardAt;
    private static volatile int checks;
    private static volatile long etaArrive, toldDelayMin, homeSentArrive;
    private static volatile int homeTries;
    private static volatile boolean altTold, parkTold, cardShown, busy;
    private static volatile String tripDest = "";

    static SharedPreferences prefs(Context c) { return new Prefs(c).sp; }

    /** Every GPS fix (main thread): the diary point; once a minute the checks, off the main thread. */
    static void fix(Context svc, Location l, double kmh) {
        final Context c = svc.getApplicationContext();
        try { TripLog.fix(c, l, kmh); } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (startedAt == 0) startedAt = now;
        if (now - lastTick < 60_000 || busy) return;
        lastTick = now;
        busy = true;
        final Location at = new Location(l);
        try {
            work.execute(() -> {
                try { tick(c, at); } catch (Exception ignored) {} finally { busy = false; }
            });
        } catch (Exception e) { busy = false; }
    }

    /** The drive alerts ended: the diary saves the trip, the watch card goes, everything starts afresh next time. */
    static void end(Context svc, double meters) {
        Context c = svc.getApplicationContext();
        try { TripLog.end(c, meters, Bike.riding(c)); } catch (Exception ignored) {}
        try {
            work.execute(() -> { // (after a check still on its way: the card goes last, and the next trip starts clean)
                if (cardShown) { try { WatchAlerts.directGone(c, WATCH_CARD); } catch (Exception ignored) {} }
                reset();
            });
        } catch (Exception e) { reset(); }
    }

    private static void reset() {
        lastTick = startedAt = lastCheckAt = cardAt = 0;
        checks = 0;
        homeTries = 0;
        etaArrive = toldDelayMin = homeSentArrive = 0;
        altTold = parkTold = cardShown = false;
        tripDest = "";
    }

    private static void tick(Context c, Location l) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject d = Drive.dest(c);
        String name = d == null ? "" : d.optString("name");
        if (!name.equals(tripDest)) { // a new destination: its own checks
            tripDest = name;
            checks = 0;
            lastCheckAt = 0;
            homeTries = 0;
            etaArrive = toldDelayMin = homeSentArrive = 0;
            altTold = parkTold = false;
        }
        if (!name.isEmpty()) TripLog.destination(name);
        boolean known = d != null && d.has("lat");
        double left = known ? MapMath.meters(l.getLatitude(), l.getLongitude(), d.optDouble("lat"), d.optDouble("lon")) : -1;
        boolean home = known && isHome(c, d.optDouble("lat"), d.optDouble("lon"));
        // 🚦 the traffic on the way (Google, with his key; 20 minutes apart, the first after 5 minutes of driving)
        if (known && left > 10_000 && GMaps.has(c) && Net.online(c) && prefs(c).getBoolean("maps_traffic_watch", true) && checks < 8
                && now - startedAt > 5 * 60_000L && now - lastCheckAt > 20 * 60_000L) {
            lastCheckAt = now;
            checks++;
            traffic(c, l, d);
        }
        // 👵 amma's tablet: when he reaches home (only with its switch on; again when it moves 15 minutes)
        if (home && etaArrive > now && prefs(c).getBoolean("maps_home_eta", false) && HomeLink.linked(c) && Net.online(c) && homeTries < 4
                && (homeSentArrive == 0 || Math.abs(etaArrive - homeSentArrive) >= 15 * 60_000L)) {
            int min = (int) Math.max(1, (etaArrive - now) / 60_000);
            homeTries++;
            HomeLink.eta(c, min, clock(etaArrive));
            homeSentArrive = etaArrive; // (sent or not, the next try only when the time moves 15 minutes: never a message a minute)
        }
        // 🅿️ parking near where he is going (once, 2.5 km before; not for home)
        if (known && !home && !parkTold && left > 0 && left < 2500 && GMaps.has(c) && Net.online(c) && prefs(c).getBoolean("maps_parking", true)) {
            parkTold = true;
            parking(c, d);
        }
        // ⌚ the quiet trip card (every 5 minutes; no buzz)
        if (prefs(c).getBoolean("maps_watch_trip", true) && WatchHub.known(c) && now - cardAt > 5 * 60_000L) {
            String text = cardText(c, d, left, now);
            if (text != null) {
                cardAt = now;
                cardShown = true;
                WatchAlerts.directQuiet(c, WATCH_CARD, "🚗 " + (name.isEmpty() ? "ప్రయాణం" : name), text);
            }
        }
    }

    private static boolean isHome(Context c, double lat, double lon) {
        try {
            JSONObject h = Travel.home(c);
            return h != null && h.has("lat") && MapMath.meters(lat, lon, h.optDouble("lat"), h.optDouble("lon")) < 300; // (a shop near home is not home)
        } catch (Exception e) { return false; }
    }

    private static String clock(long ms) { return new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(ms)); }

    /** "45 ని · 5:30 PM కి · 32 కి.మీ" from the maps app's own words when it navigates, else Jarvis's last traffic check. */
    private static String cardText(Context c, JSONObject d, double left, long now) {
        JSONObject nav = Drive.navState();
        if (nav != null && !nav.optString("trip").isEmpty()) return nav.optString("trip") + (nav.optString("next").isEmpty() ? "" : "\n" + nav.optString("next"));
        if (d == null) return null;
        StringBuilder b = new StringBuilder();
        if (etaArrive > now) b.append(MapMath.duration((etaArrive - now) / 1000)).append(" · ").append(clock(etaArrive)).append(" కి");
        if (left > 0) b.append(b.length() > 0 ? " · " : "").append("సుమారు ").append(MapMath.distance(left)).append(" (నేరుగా)");
        return b.length() == 0 ? null : b.toString();
    }

    private static void traffic(Context c, Location l, JSONObject d) {
        try {
            List<GMaps.Route> rs = GMaps.routesAt(c, l.getLatitude(), l.getLongitude(), d.optDouble("lat"), d.optDouble("lon"), Bike.riding(c), 0, false, true);
            if (rs.isEmpty()) return;
            long now = System.currentTimeMillis();
            GMaps.Route main = rs.get(0);
            long arrive = now + main.seconds * 1000;
            etaArrive = arrive;
            // the traffic's own delay on the way still ahead (Google: with traffic vs without), never his stops or slow riding
            long lateMin = main.staticSeconds > 0 ? (main.seconds - main.staticSeconds) / 60 : 0;
            String name = d.optString("name");
            if (lateMin >= 10 && lateMin >= toldDelayMin + 10) { // (10, 20, 30… minutes of traffic: each said once)
                toldDelayMin = lateMin;
                tell(c, NOTE_TRAFFIC, "🚦 ట్రాఫిక్: " + lateMin + " ని ఆలస్యం", "ముందు ట్రాఫిక్ వల్ల " + name + " కి సుమారు " + lateMin + " నిమిషాలు ఎక్కువ పడుతుంది. "
                        + clock(arrive) + " కి చేరతారు.", null);
            }
            if (!altTold && rs.size() > 1) {
                GMaps.Route best = rs.get(1);
                for (GMaps.Route r : rs) if (r.seconds < best.seconds && r != main) best = r;
                long save = (main.seconds - best.seconds) / 60;
                if (save >= 8) {
                    altTold = true;
                    tell(c, NOTE_TRAFFIC, "🚦 వేరే దారిలో " + save + " ని తక్కువ", "Google ప్రకారం " + (best.via.isEmpty() ? "వేరే దారిలో" : best.via + " మీదుగా") + " సుమారు " + save
                            + " నిమిషాలు తక్కువ. మారాలంటే Google Maps లో ఆ దారి నొక్కండి, లేదా 'Jarvis, ఇంకో దారి చూపించు' అనండి.", null);
                }
            }
        } catch (Exception ignored) {} // (Google not answering: the trip goes on quietly)
    }

    private static void parking(Context c, JSONObject d) {
        try {
            List<GMaps.Place> ps = GMaps.search(c, "parking", d.optDouble("lat"), d.optDouble("lon"), 1500, null, false, 5, true, false);
            if (ps.isEmpty()) return;
            GMaps.Place best = ps.get(0);
            for (GMaps.Place p : ps) if (p.aheadM < best.aheadM) best = p;
            String ll = best.lat + "," + best.lon;
            Intent nav = Drive.navigate(ll, "", Bike.riding(c));
            tell(c, NOTE_PARK, "🅿️ " + best.name, d.optString("name") + " దగ్గర పార్కింగ్: " + best.name + ", గమ్యం నుంచి " + MapMath.distance(best.aheadM)
                    + ". అక్కడికి దారి కావాలంటే నోటిఫికేషన్ నొక్కండి.", nav);
        } catch (Exception ignored) {}
    }

    /** Said aloud when he isn't talking with Jarvis (else only the notification); the notification's tap = nav (if any). */
    private static void tell(Context c, int id, String title, String text, Intent nav) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_trip", "ప్రయాణం (ట్రాఫిక్, పార్కింగ్)", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = new Notification.Builder(c, "jarvis_trip").setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setTimeoutAfter(30 * 60_000L);
            if (nav != null) b.setContentIntent(PendingIntent.getActivity(c, id, nav, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(id, b.build());
        } catch (Exception ignored) {}
        if (!MainActivity.busyTalking() && !CarCare.asking(c)) Announcer.say(c, text);
    }
}
