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
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

/**
 * Listens for "Hey Jarvis" in the background (openWakeWord, fully on the phone)
 * and opens Jarvis ready to listen when it hears it.
 */
public class WakeService extends Service {
    private static final String ACTION_START = "com.anil.jarvis.WAKE_START";
    private static final String ACTION_PAUSE = "com.anil.jarvis.WAKE_PAUSE";
    private static final String ACTION_RESUME = "com.anil.jarvis.WAKE_RESUME";
    private static final String ACTION_STOP = "com.anil.jarvis.WAKE_STOP";
    private static final String EXTRA_PAUSED = "paused";
    private static final String CHANNEL = "jarvis_wake_quiet";
    private static final String OLD_CHANNEL = "jarvis_wake";
    private static final String CHANNEL_ALERT = "jarvis_wake_alert";
    private static final int NOTE_ID = 7;
    private static final int ALERT_ID = 8;

    static volatile boolean running;
    /** Progress or problem with the "Jarvis" word detector, shown in settings; null when fine. */
    static volatile String wordStatus;
    private static final String WAKE_HINT = "\"Jarvis\" లేదా \"Hey Jarvis\" అని పిలవండి";
    static volatile String lastError;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WakeEngine engine;
    private boolean engineOn;
    /** For retrying after the engine fails: when it last started, and failures in a row. */
    private long engineStartedAt;
    private int engineErrors;
    /** Keeps the phone's processor awake while listening with the screen off. */
    private PowerManager.WakeLock cpu;
    /** Shake to call Jarvis, face down to silence. */
    private Motion motion;
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

    /** Turns the wake-word mic off when the screen goes off (or charging stops), and back on. */
    private final Runnable applyPhoneState = () -> {
        if (!running) return;
        if (allowedNow()) {
            if (!MainActivity.inConversation) startEngine();
        } else {
            stopEngine();
            sleeping();
        }
    };

    private final android.content.BroadcastReceiver phoneState = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            applyPhoneState.run();
            String a = i == null ? null : i.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a) || Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                // the battery status can lag the plug event by a moment: look again shortly
                main.removeCallbacks(applyPhoneState);
                main.postDelayed(applyPhoneState, 3000);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(phoneState, f);
        registerReceiver(battery, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        motion = new Motion(this, () -> main.post(this::onShake));
        motion.start();
    }

    // ---------- low battery warning ----------

    private int warnedAt = 101;

    private final android.content.BroadcastReceiver battery = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            if (level < 0 || scale <= 0) return;
            int pct = level * 100 / scale;
            if (plugged != 0 || pct > 20) { warnedAt = 101; return; }
            Prefs p = new Prefs(c);
            if (!p.batteryWarn()) return;
            int step = pct <= 5 ? 5 : pct <= 15 ? 15 : 101;
            if (step >= warnedAt || step == 101) return;
            warnedAt = step;
            String say = p.name() + ", బ్యాటరీ " + pct + " శాతం మాత్రమే ఉంది. ఛార్జింగ్ పెట్టండి"
                    + (step == 15 ? ", లేదా పవర్ సేవింగ్ ఆన్ చేయండి." : ".");
            if (!p.night() && !CallControl.busyWithCall()) Announcer.say(c, say);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "Jarvis పిలుపు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent saver = PendingIntent.getActivity(c, 9,
                    new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(9, new Notification.Builder(c, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentTitle("బ్యాటరీ " + pct + "%")
                    .setContentText("పవర్ సేవింగ్ ఆన్ చేయడానికి నొక్కండి")
                    .setContentIntent(saver)
                    .setAutoCancel(true)
                    .build());
        }
    };

    /** Whether the wake word may listen right now, per the "when to listen" setting. */
    private boolean allowedNow() {
        String when = new Prefs(this).wakeWhen();
        if ("always".equals(when)) return true;
        if ("charging".equals(when)) {
            // "plugged in", from the sticky battery broadcast: BatteryManager.isCharging() is still false
            // right when the charger is connected (and when full), so the mic would stay off.
            try {
                Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
            } catch (Exception e) {
                android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
                return bm != null && bm.isCharging();
            }
        }
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private void sleeping() {
        String when = new Prefs(this).wakeWhen();
        goForeground("charging".equals(when)
                ? "మైక్ ఆఫ్. ఛార్జింగ్ పెట్టినప్పుడు మళ్లీ వింటాడు"
                : "మైక్ ఆఫ్. స్క్రీన్ ఆన్ చేసినప్పుడు మళ్లీ వింటాడు");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Only a pause: the wake word stays switched on and comes back when Jarvis is opened.
            new Prefs(this).setWakePaused(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "మైక్ అనుమతి లేదు";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            goForeground(wordStatus != null ? wordStatus : WAKE_HINT);
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
        try { unregisterReceiver(phoneState); } catch (Exception ignored) {}
        try { unregisterReceiver(battery); } catch (Exception ignored) {}
        if (motion != null) motion.stop();
        running = false;
        main.removeCallbacksAndMessages(null);
        engineOn = false;
        holdCpu(false);
        if (engine != null) {
            engine.close();
            engine = null;
        }
        super.onDestroy();
    }

    // ---------- engine ----------

    private void startEngine() {
        if (engineOn) return;
        if (!allowedNow()) { sleeping(); return; }
        if (engine == null) {
            Prefs p = new Prefs(this);
            engine = new WakeEngine(this, p.wakeThreshold(), p.jarvisWord(), new WakeEngine.Listener() {
                @Override public void onWake(float score) { main.post(WakeService.this::onWake); }
                @Override public void onStatus(String text) {
                    main.post(() -> {
                        wordStatus = "ready".equals(text) ? null : text;
                        if (running) goForeground(wordStatus != null ? wordStatus : WAKE_HINT);
                    });
                }
                @Override public void onError(String message) {
                    main.post(() -> {
                        lastError = message;
                        boolean wasOn = engineOn;
                        engineOn = false;
                        holdCpu(false); // the listening thread is gone: don't keep the processor awake for nothing
                        if (!running) return;
                        goForeground("వేక్ వర్డ్ ఆగిపోయింది: " + message);
                        if (wasOn) {
                            // try again shortly (the mic may have been busy), backing off if it keeps failing
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (now - engineStartedAt > 60000) engineErrors = 0;
                            long delay = Math.min(60000L, 5000L << Math.min(engineErrors, 4));
                            engineErrors++;
                            main.removeCallbacks(fallbackResume);
                            main.postDelayed(fallbackResume, delay);
                        }
                    });
                }
            });
        }
        engine.start();
        engineOn = true;
        engineStartedAt = android.os.SystemClock.elapsedRealtime();
        holdCpu(true);
        lastError = null;
        goForeground(wordStatus != null ? wordStatus : WAKE_HINT);
    }

    private void stopEngine() {
        if (engine != null) engine.stop();
        engineOn = false;
        holdCpu(false);
    }

    private void holdCpu(boolean on) {
        try {
            if (on) {
                if (cpu == null) {
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm == null) return;
                    cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen");
                    cpu.setReferenceCounted(false);
                }
                if (!cpu.isHeld()) cpu.acquire();
            } else if (cpu != null && cpu.isHeld()) {
                cpu.release();
            }
        } catch (Exception ignored) {}
    }

    /** Lights up a dark screen when Anil calls "Jarvis" (the panel also asks for this itself). */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null || pm.isInteractive()) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "jarvis:screen");
            wl.acquire(4000);
        } catch (Exception ignored) {}
    }

    /** Two shakes: open Jarvis just like saying "Jarvis". */
    private void onShake() {
        if (MainActivity.inConversation) return;
        stopEngine();
        wakeScreen();
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        openJarvis();
    }

    private void onWake() {
        if (!engineOn) return;
        stopEngine(); // free the microphone for the conversation
        wakeScreen();
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        // Look at the screen first (for "what's on my screen?"), then open Jarvis on top.
        final boolean[] opened = {false};
        Runnable open = () -> { if (!opened[0]) { opened[0] = true; openJarvis(); } };
        main.postDelayed(open, 700);
        JarvisAccessibility.capture(() -> main.post(open));
    }

    private void openJarvis() {

        Intent open = new Prefs(this).compactPanel()
                ? new Intent(this, SheetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                : new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_WAKE, true)
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
        nm.deleteNotificationChannel(OLD_CHANNEL);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Jarvis వింటున్నాడు (నిశ్శబ్దం)", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
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
