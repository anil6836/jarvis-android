package com.anil.jarvis;

import android.app.Application;

/**
 * Jarvis's process starts here: the app-error note is set up (AppCrash), and a backup he chose to bring back goes in
 * place before anything reads the old data.
 */
public class JarvisApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppCrash.install(this); // first: if Jarvis closes with an error, "Jarvis చెక్" shows where
        Backup.applyPending(this);
        MicQuiet.restore(this); // closed while listening: the media sound muted for the mic's beeps comes back
        watchInternet();
        try { VoskModel.dropTelugu(this); } catch (Throwable ignored) {} // the removed offline Telugu model's files (58 MB) go
        try { WatchAlerts.schedule(this); } catch (Exception ignored) {} // the watch's half-hourly news (after a phone restart too)
        try { HomeCare.start(this); } catch (Exception ignored) {} // the home tablet: అమ్మగారు's day (only in home mode)
        try { CarBubble.hook(this); } catch (Exception ignored) {} // 🫧 the box: the small Jarvis over Google Maps while the car screen is away
    }

    /**
     * The internet coming back: questions he asked without it are answered (Offline.netBack), and Jarvis's own Telugu
     * ears let go of their model. Going: the model is loaded ahead, so the first "Jarvis" without internet is heard at once.
     */
    private void watchInternet() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            if (cm == null) return;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                private boolean up; // (signal updates come often: only the moment it becomes usable counts)
                @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities caps) {
                    boolean ok = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (ok && !up) { Offline.netBack(JarvisApp.this); try { PhoneOffline.netBack(JarvisApp.this); } catch (Exception ignored) {} }
                    up = ok;
                }
                @Override public void onLost(android.net.Network n) { up = false; }
            });
        } catch (Exception ignored) {}
    }
}
