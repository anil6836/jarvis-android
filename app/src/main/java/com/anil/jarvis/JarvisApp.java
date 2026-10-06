package com.anil.jarvis;

import android.app.Application;

/** Jarvis's process starts here: a backup he chose to bring back goes in place before anything reads the old data. */
public class JarvisApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Backup.applyPending(this);
        MicQuiet.restore(this); // closed while listening: the media sound muted for the mic's beeps comes back
        watchInternet();
    }

    /** The internet coming back: questions he asked without it are answered (Offline.netBack). */
    private void watchInternet() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                private boolean up; // (signal updates come often: only the moment it becomes usable counts)
                @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities caps) {
                    boolean ok = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (ok && !up) Offline.netBack(JarvisApp.this);
                    up = ok;
                }
                @Override public void onLost(android.net.Network n) { up = false; }
            });
        } catch (Exception ignored) {}
    }
}
