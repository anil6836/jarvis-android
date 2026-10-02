package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Watches the phone's location on the way to his stop (see {@link StopAlarm}). Far away it looks every couple of
 * minutes; closer, every 15 seconds. A small notification shows how far the stop is, with ⏹ to cancel.
 */
public class StopAlarmService extends Service implements LocationListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject target;
    private long every = -1; // the location interval now asked for
    private long lastNote;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        target = StopAlarm.current(this);
        Notification n = note(target == null ? "స్టాప్ అలారం" : "📍 " + target.optString("place") + " కి స్టాప్ అలారం", "దూరం చూస్తున్నాను…");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(StopAlarm.NOTE_WATCH, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(StopAlarm.NOTE_WATCH, n);
        } catch (Exception e) { // Android did not allow watching the location now (e.g. a restart in the background): say so
            if (target != null) Reminders.notify(this, "📍 స్టాప్ అలారం ఆగిపోయింది", "ఫోన్ " + target.optString("place")
                    + " స్టాప్ అలారంని ఆపేసింది. Jarvis తెరిచి మళ్ళీ పెట్టండి.", StopAlarm.NOTE_INFO);
            StopAlarm.forget(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (target == null) { stopSelf(); return START_NOT_STICKY; }
        every = -1;
        listen(15_000);
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::tooLong, Math.max(60_000, target.optLong("since") + StopAlarm.MAX_MS - System.currentTimeMillis()));
        return START_STICKY;
    }

    private void tooLong() {
        Reminders.notify(this, "📍 స్టాప్ అలారం ఆపేశాను", "40 గంటలు అయింది, " + (target == null ? "" : target.optString("place") + " ") + "చేరలేదు. కావాలంటే మళ్ళీ పెట్టండి.", StopAlarm.NOTE_INFO);
        StopAlarm.off(this);
    }

    private Notification note(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel("jarvis_stop_watch", "స్టాప్ అలారం దూరం", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "jarvis_stop_watch")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title).setContentText(text).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 245, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", StopAlarm.offIntent(this)).build()).build();
    }

    /** GPS (and the network fix, which also works inside a bus) every {@code ms}. */
    private void listen(long ms) {
        if (ms == every) return;
        every = ms;
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) return;
        try {
            lm.removeUpdates(this);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
                if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, ms, 0f, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            Reminders.notify(this, "📍 స్టాప్ అలారం పనిచేయదు", "Jarvis కి లొకేషన్ అనుమతి లేదు. సెట్టింగ్స్‌లో ఇచ్చి మళ్ళీ పెట్టండి.", StopAlarm.NOTE_INFO);
            StopAlarm.off(this);
        }
    }

    @Override public void onLocationChanged(Location l) {
        if (target == null || l == null) return;
        if (l.hasAccuracy() && l.getAccuracy() > 1500) return; // a rough guess from far towers: not enough to ring on
        double m = GeoReminders.distance(l.getLatitude(), l.getLongitude(), target.optDouble("lat"), target.optDouble("lon"));
        double ring = target.optDouble("km", 2) * 1000;
        if (m <= ring) {
            String place = target.optString("place");
            target = null;
            try { getSystemService(LocationManager.class).removeUpdates(this); } catch (Exception ignored) {}
            main.removeCallbacksAndMessages(null);
            StopAlarm.ring(this, place, m);
            stopForeground(true);
            stopSelf();
            return;
        }
        // closer = more often: far away a look every 2 minutes is enough, near the stop every 15 seconds
        double left = m - ring;
        listen(left > 50_000 ? 120_000 : left > 15_000 ? 45_000 : 15_000);
        long now = System.currentTimeMillis();
        if (now - lastNote > 60_000) {
            lastNote = now;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(StopAlarm.NOTE_WATCH, note("📍 " + target.optString("place") + " కి " + StopAlarm.km(m) + " కి.మీ.",
                    StopAlarm.km(ring) + " కి.మీ. దగ్గర అలారం మోగుతుంది"));
        }
    }

    @Override public void onProviderDisabled(String p) {
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            Reminders.notify(this, "📍 లొకేషన్ ఆఫ్ అయింది", "స్టాప్ అలారం పనిచేయాలంటే ఫోన్ లొకేషన్ ఆన్ చేయండి.", StopAlarm.NOTE_INFO);
    }

    @Override public void onProviderEnabled(String p) {}

    @Override public void onStatusChanged(String p, int s, android.os.Bundle b) {}

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try { getSystemService(LocationManager.class).removeUpdates(this); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
