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
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineManager;

/**
 * Listens for the word "Jarvis" in the background (Picovoice Porcupine, on-device)
 * and opens Jarvis ready to listen when it hears it.
 */
public class WakeService extends Service {
    private static final String ACTION_START = "com.anil.jarvis.WAKE_START";
    private static final String ACTION_PAUSE = "com.anil.jarvis.WAKE_PAUSE";
    private static final String ACTION_RESUME = "com.anil.jarvis.WAKE_RESUME";
    private static final String ACTION_STOP = "com.anil.jarvis.WAKE_STOP";
    private static final String EXTRA_PAUSED = "paused";
    private static final String CHANNEL = "jarvis_wake";
    private static final String CHANNEL_ALERT = "jarvis_wake_alert";
    private static final int NOTE_ID = 7;
    private static final int ALERT_ID = 8;

    static volatile boolean running;
    static volatile String lastError;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PorcupineManager porcupine;
    private boolean engineOn;
    private final Runnable fallbackResume = () -> {
        if (!MainActivity.inConversation) startEngine();
    };

    // ---------- called by the screens ----------

    static void start(Context c, boolean paused) {
        Intent i = new Intent(c, WakeService.class).setAction(ACTION_START).putExtra(EXTRA_PAUSED, paused);
        try { c.startForegroundService(i); } catch (Exception e) { lastError = e.getMessage(); }
    }

    static void pause(Context c) { send(c, ACTION_PAUSE); }

    static void resume(Context c) { send(c, ACTION_RESUME); }

    static void stop(Context c) {
        if (running) c.stopService(new Intent(c, WakeService.class));
    }

    private static void send(Context c, String action) {
        if (!running) return;
        try { c.startService(new Intent(c, WakeService.class).setAction(action)); } catch (Exception ignored) {}
    }

    // ---------- lifecycle ----------

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("wake", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "మైక్ అనుమతి లేదు";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            goForeground("\"Jarvis\" అని పిలవండి");
        } catch (Exception e) {
            lastError = e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (ACTION_PAUSE.equals(action)) {
            main.removeCallbacks(fallbackResume);
            stopEngine();
        } else if (ACTION_RESUME.equals(action)) {
            main.removeCallbacks(fallbackResume);
            startEngine();
        } else {
            boolean paused = intent != null && intent.getBooleanExtra(EXTRA_PAUSED, false);
            if (paused) stopEngine(); else startEngine();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        stopEngine();
        if (porcupine != null) {
            try { porcupine.delete(); } catch (Exception ignored) {}
            porcupine = null;
        }
        super.onDestroy();
    }

    // ---------- engine ----------

    private void startEngine() {
        if (engineOn) return;
        String key = new Prefs(this).picoKey();
        if (key.isEmpty()) { lastError = "Picovoice key లేదు"; stopSelf(); return; }
        try {
            if (porcupine == null) {
                porcupine = new PorcupineManager.Builder()
                        .setAccessKey(key)
                        .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                        .setSensitivity(0.65f)
                        .build(getApplicationContext(), keywordIndex -> main.post(this::onWake));
            }
            porcupine.start();
            engineOn = true;
            lastError = null;
            goForeground("\"Jarvis\" అని పిలవండి");
        } catch (Exception e) {
            lastError = "Picovoice: " + e.getMessage();
            engineOn = false;
            goForeground("వేక్ వర్డ్ పనిచేయలేదు. Picovoice key చెక్ చేయండి.");
        }
    }

    private void stopEngine() {
        if (!engineOn || porcupine == null) { engineOn = false; return; }
        try { porcupine.stop(); } catch (Exception ignored) {}
        engineOn = false;
    }

    private void onWake() {
        stopEngine(); // free the microphone for the conversation
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_WAKE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean opened = false;
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(open); opened = true; } catch (Exception ignored) {}
        }
        if (!opened) {
            // Android blocks opening screens from the background without "display over other apps".
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "Jarvis పిలుపు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("చెప్పండి, " + new Prefs(this).name())
                    .setContentText("Jarvis తెరవడానికి నొక్కండి")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(20000)
                    .build();
            nm.notify(ALERT_ID, n);
        }
        // If nobody starts a conversation, go back to listening for the wake word.
        main.removeCallbacks(fallbackResume);
        main.postDelayed(fallbackResume, 25000);
    }

    private void goForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis వేక్ వర్డ్", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WakeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis వింటున్నాడు")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null, "ఆపు", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTE_ID, n);
    }
}
