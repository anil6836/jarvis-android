package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * While he drives: watches the GPS and says, in time, "speed camera ahead", "too fast for this road",
 * and after a long stretch "take a short break". Speed limits and cameras come from OpenStreetMap.
 * Runs as a location foreground service with a small notification (speed, limit, next camera).
 */
public class DriveService extends Service implements LocationListener, android.hardware.SensorEventListener {
    private static final int NOTE = 131;
    static volatile boolean running;
    static volatile Location last;
    static volatile float heading = -1;
    static volatile double tripMeters;
    static volatile int limitKmh;
    static volatile String roadName = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Location prev;
    private long lastNote, limitAt, overAt, breakAt, movingSince, lastMoving;
    private double limitLat, limitLon, camLat, camLon;
    private int overCount;
    private boolean camLoading, limitLoading;
    private long camTriedAt, arrivedCheckAt;
    private final List<double[]> cams = new ArrayList<>(); // {lat, lon, limit, id}
    private final Map<Long, Long> warned = new HashMap<>();

    /** Starts the drive alerts; false without location permission. */
    static boolean start(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        Intent i = new Intent(c, DriveService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        return true;
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, DriveService.class)); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (!note("డ్రైవ్ అలర్ట్స్ ఆన్ · GPS కోసం చూస్తున్నాను…")) { // Android refused (started from the background)
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            tripMeters = 0;
            limitKmh = 0;
            roadName = "";
            heading = -1;
            try {
                LocationManager lm = getSystemService(LocationManager.class);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException e) {
                running = false;
                stopSelf();
            }
            startedAt = System.currentTimeMillis();
            main.postDelayed(watch, 60000);
            if (running) { try { RideCare.started(this); } catch (Exception ignored) {} } // never in the way of the alerts
            if (Drive.settings(this).getBoolean("drive_crash", true)) {
                try {
                    android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
                    android.hardware.Sensor acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                    if (acc != null) sm.registerListener(this, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME, main);
                } catch (Exception ignored) {}
            }
        }
        return START_NOT_STICKY;
    }

    private long startedAt;

    /** Once a minute: parked for 10 minutes (or no GPS for 15) and not in driving mode -> stop and keep the parking spot. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            boolean drivingMode = new Prefs(DriveService.this).sp.getBoolean("driving", false);
            long still = lastMoving > 0 ? now - lastMoving : now - startedAt;
            Location l = last;
            boolean noGps = l == null || now - l.getTime() > 15 * 60000L;
            if (!drivingMode && (still > 10 * 60000L || noGps && now - startedAt > 15 * 60000L)) {
                if (l != null && tripMeters > 1000 && now - l.getTime() < 10 * 60000L) {
                    GeoReminders.savePlace(DriveService.this, "parking", l.getLatitude(), l.getLongitude());
                    Life.markParked(DriveService.this);
                }
                Drive.clearDest(DriveService.this);
                stopSelf();
                return;
            }
            main.postDelayed(this, 60000);
        }
    };

    private boolean note(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_drive", "డ్రైవింగ్ అలర్ట్స్", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getActivity(this, 132, new Intent(this, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "డ్రైవింగ్ అయిపోయింది (driving_mode off).").putExtra(MainActivity.EXTRA_LABEL, "🚗 డ్రైవ్ ఆపు")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "jarvis_drive").setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("🚗 Jarvis డ్రైవ్").setContentText(text).setOngoing(true).setShowWhen(false)
                    .addAction(new Notification.Action.Builder(null, "⏹ డ్రైవ్ ఆపు", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTE, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- every GPS fix (about every 2 seconds)

    @Override public void onLocationChanged(Location l) {
        if (!running || l == null) return;
        long now = System.currentTimeMillis();
        double kmh = l.hasSpeed() ? l.getSpeed() * 3.6 : 0;
        if (prev != null && l.getAccuracy() < 50) {
            double m = Drive.meters(prev.getLatitude(), prev.getLongitude(), l.getLatitude(), l.getLongitude());
            if (!l.hasSpeed() && l.getTime() > prev.getTime()) kmh = m / ((l.getTime() - prev.getTime()) / 1000.0) * 3.6;
            if (m > 3 && m < 2000) tripMeters += m;
            if (m > 15) heading = (float) Drive.bearing(prev.getLatitude(), prev.getLongitude(), l.getLatitude(), l.getLongitude());
        }
        if (kmh > 8 && l.hasBearing()) heading = l.getBearing();
        speedNow = kmh;
        speedAt = now;
        afterKnock(kmh, now);
        if (prev == null || l.getAccuracy() < 50) prev = l;
        last = l;

        Prefs p = new Prefs(this);
        android.content.SharedPreferences s = Drive.settings(this);
        // driving time, for the break reminder (stops shorter than 15 minutes don't count as a break)
        if (kmh > 10) {
            if (movingSince == 0 || now - lastMoving > 15 * 60000L) { movingSince = now; breakAt = 0; }
            lastMoving = now;
        }
        int breakHours = s.getInt("drive_break_hours", 2);
        if (breakHours > 0 && movingSince > 0 && now - movingSince > breakHours * 3600000L && now - breakAt > 30 * 60000L && kmh > 10) {
            breakAt = now;
            Announcer.say(this, p.name() + ", " + ((now - movingSince) / 3600000L) + " గంటలకు పైగా ఆపకుండా డ్రైవ్ చేస్తున్నారు. "
                    + "కొంచెం బ్రేక్ తీసుకోండి, టీ తాగి కాసేపు నడవండి. దారిలో టీ షాప్ కావాలంటే అడగండి.");
        }

        if (s.getBoolean("drive_cameras", true)) cameras(l, kmh);
        speedLimit(l);
        arrived(l, s);
        // the road's limit only while he is still near where it was looked up
        int road = limitLat != 0 && Drive.meters(limitLat, limitLon, l.getLatitude(), l.getLongitude()) < 500 ? limitKmh : 0;
        int lim = road > 0 ? road : s.getInt("drive_max_kmh", 0);
        if (s.getBoolean("drive_overspeed", true) && lim > 0 && kmh > lim + 7) {
            overCount++;
            if (overCount >= 3 && now - overAt > 90000) {
                overAt = now;
                Announcer.say(this, "స్పీడ్ " + Math.round(kmh) + " ఉంది. ఇక్కడ లిమిట్ " + lim + ". కొంచెం తగ్గించండి.");
            }
        } else overCount = 0;

        if (now - lastNote > 10000) {
            lastNote = now;
            String next = nextCamera(l);
            note(Math.round(kmh) + " km/h" + (lim > 0 ? " · లిమిట్ " + lim : "") + (roadName.isEmpty() ? "" : " · " + roadName) + next);
        }
    }

    /** Within 300 m of the place he was going to: that trip is done (route answers stop using it). */
    private void arrived(Location l, android.content.SharedPreferences s) {
        long now = System.currentTimeMillis();
        if (now - arrivedCheckAt < 30000) return;
        arrivedCheckAt = now;
        String ll = s.getString("dest_ll", "");
        if (ll.isEmpty()) return;
        try {
            String[] xy = ll.split(",");
            if (Drive.meters(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]), l.getLatitude(), l.getLongitude()) < 300) Drive.clearDest(this);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- speed cameras

    private void cameras(Location l, double kmh) {
        // cameras within 20 km, fetched again after 10 km
        if (!camLoading && System.currentTimeMillis() - camTriedAt > 60000
                && (camLat == 0 || Drive.meters(camLat, camLon, l.getLatitude(), l.getLongitude()) > 10000)) {
            camLoading = true;
            camTriedAt = System.currentTimeMillis();
            final double la = l.getLatitude(), lo = l.getLongitude();
            net.execute(() -> {
                List<double[]> got = new ArrayList<>();
                boolean okFetch = false;
                try {
                    JSONArray el = Drive.overpass(String.format(Locale.ENGLISH, "[out:json][timeout:25];node(around:20000,%.6f,%.6f)[highway=speed_camera];out 300;", la, lo));
                    for (int i = 0; el != null && i < el.length(); i++) {
                        JSONObject e = el.getJSONObject(i), t = e.optJSONObject("tags");
                        got.add(new double[]{e.optDouble("lat"), e.optDouble("lon"), t == null ? 0 : Drive.speed(t.optString("maxspeed", "")), e.optLong("id")});
                    }
                    okFetch = true;
                } catch (Exception ignored) {}
                final boolean ok = okFetch;
                main.post(() -> {
                    camLoading = false;
                    if (!ok) return; // try again on a later fix
                    cams.clear();
                    cams.addAll(got);
                    camLat = la;
                    camLon = lo;
                });
            });
        }
        if (heading < 0 || kmh < 15) return;
        long now = System.currentTimeMillis();
        List<double[]> all = new ArrayList<>(cams);
        all.addAll(Drive.myCams(this)); // the ones he marked himself
        for (double[] c : all) {
            double d = Drive.meters(l.getLatitude(), l.getLongitude(), c[0], c[1]);
            if (d > 650 || d < 40) continue;
            if (Drive.angle(heading, Drive.bearing(l.getLatitude(), l.getLongitude(), c[0], c[1])) > 35) continue; // not ahead of him
            if (c.length > 4 && c[4] >= 0 && Drive.angle(heading, c[4]) > 60) continue; // his camera faces the other way
            Long when = warned.get((long) c[3]);
            if (when != null && now - when < 15 * 60000L) continue;
            warned.put((long) c[3], now);
            int lim = c[2] > 0 ? (int) c[2] : roadLimit(l);
            String say = "జాగ్రత్త, ముందు " + (Math.round(d / 50.0) * 50) + " మీటర్లలో స్పీడ్ కెమెరా ఉంది"
                    + (lim > 0 ? ", లిమిట్ " + lim : "") + (lim > 0 && kmh > lim ? ". స్పీడ్ తగ్గించండి." : ".");
            Announcer.say(this, say);
            break;
        }
    }

    /** " · 📷 1.2 కి.మీ." for the nearest camera ahead within 3 km, else "". */
    private String nextCamera(Location l) {
        if (heading < 0) return "";
        double best = Double.MAX_VALUE;
        List<double[]> all = new ArrayList<>(cams);
        all.addAll(Drive.myCams(this));
        for (double[] c : all) {
            if (c.length > 4 && c[4] >= 0 && Drive.angle(heading, c[4]) > 60) continue;
            double d = Drive.meters(l.getLatitude(), l.getLongitude(), c[0], c[1]);
            if (d < 3000 && Drive.angle(heading, Drive.bearing(l.getLatitude(), l.getLongitude(), c[0], c[1])) < 35) best = Math.min(best, d);
        }
        return best == Double.MAX_VALUE ? "" : " · 📷 " + String.format(Locale.ENGLISH, "%.1f", best / 1000) + " కి.మీ.";
    }

    /** The road's limit, only while he is still near where it was looked up (0 = not known). */
    private int roadLimit(Location l) {
        return limitLat != 0 && Drive.meters(limitLat, limitLon, l.getLatitude(), l.getLongitude()) < 500 ? limitKmh : 0;
    }

    // ---------------------------------------------------------------- the road's speed limit

    private void speedLimit(Location l) {
        long now = System.currentTimeMillis();
        if (limitLoading || now - limitAt < 20000) return;
        if (limitLat != 0 && Drive.meters(limitLat, limitLon, l.getLatitude(), l.getLongitude()) < 250) return;
        limitLoading = true;
        limitAt = now;
        final double la = l.getLatitude(), lo = l.getLongitude();
        net.execute(() -> {
            JSONObject r = null;
            try { r = Drive.road(la, lo); } catch (Exception ignored) {}
            final JSONObject road = r;
            main.post(() -> {
                limitLoading = false;
                if (road == null) return;
                limitLat = la;
                limitLon = lo;
                limitKmh = road.optInt("maxspeed", 0);
                String ref = road.optString("ref"), name = road.optString("name");
                roadName = !ref.isEmpty() ? ref : name;
            });
        });
    }

    // ---------------------------------------------------------------- a crash: a hard knock while moving, then standing still

    private volatile double speedNow;
    private volatile long speedAt, knockAt, hardAt;
    private int stillFixes;

    @Override public void onSensorChanged(android.hardware.SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z) / 9.81;
        long now = System.currentTimeMillis();
        if (g < 4.5 || knockAt != 0 || CrashAlert.active) return;
        // only while really moving (a phone dropped while standing doesn't count)
        if (now - speedAt > 4000 || speedNow < 20) return;
        // a pothole / speed breaker gives one short spike: a crash is a very hard one or a hard knock that lasts
        if (g < 6.5) {
            if (now - hardAt > 150) { hardAt = now; return; }
        }
        hardAt = 0;
        knockAt = now;
        stillFixes = 0;
        main.postDelayed(noGpsAfterKnock, 25000);
    }

    @Override public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {}

    /** After the knock: still for two fixes -> ask "బాగున్నారా?"; driving on -> it was nothing. */
    private void afterKnock(double kmh, long now) {
        if (knockAt == 0) return;
        if (now - knockAt > 40000) { knockAt = 0; main.removeCallbacks(noGpsAfterKnock); return; } // too long ago
        if (now - knockAt < 4000) return; // let it settle
        if (kmh >= 15) { knockAt = 0; main.removeCallbacks(noGpsAfterKnock); return; } // he rode on: nothing happened
        if (kmh < 5) {
            if (++stillFixes >= 5) { // about 10 seconds lying still
                knockAt = 0;
                main.removeCallbacks(noGpsAfterKnock);
                CrashAlert.start(this);
            }
        } else stillFixes = 0;
    }

    /** No GPS fix at all since the knock (the phone flew off?): ask anyway. */
    private final Runnable noGpsAfterKnock = () -> {
        boolean noFix = knockAt != 0 && speedAt < knockAt;
        if (noFix) { knockAt = 0; CrashAlert.start(this); }
    };

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    @Override public void onDestroy() {
        // a hard knock just now and the bike's Bluetooth dropped / the drive was stopped: ask rather than miss a crash
        if (knockAt != 0 && System.currentTimeMillis() - knockAt < 30000) CrashAlert.start(this);
        knockAt = 0;
        boolean was = running;
        running = false;
        heading = -1;
        try { getSystemService(LocationManager.class).removeUpdates(this); } catch (Exception ignored) {}
        try { getSystemService(android.hardware.SensorManager.class).unregisterListener(this); } catch (Exception ignored) {}
        net.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (was) { try { RideCare.ended(this); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
