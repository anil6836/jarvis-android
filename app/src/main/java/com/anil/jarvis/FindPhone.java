package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * "Jarvis, ఎక్కడున్నావ్?": rings loudly and blinks the flashlight until Anil picks the phone up.
 * Also from another phone: his secret code (e.g. "JARVIS 4827") sent by SMS or WhatsApp makes it ring, even on
 * silent (seen through the notification access used for reading messages). It only rings; nothing is sent back.
 */
final class FindPhone {
    private FindPhone() {}

    static final String ACTION_STOP = "com.anil.jarvis.FIND_STOP";
    private static final int NOTE_ID = 44;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static int oldAlarmVolume = -1;
    private static volatile boolean blinking;
    private static BroadcastReceiver unlock;

    static void start(Context c) { start(c, 40000); }

    private static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s\\p{Punct}]+", "").toUpperCase(java.util.Locale.ROOT); }

    /** True when the message is the code (alone, or "Name: code"). */
    static boolean isCode(String code, String text) {
        String c = norm(code);
        if (c.length() < 6 || text == null) return false;
        for (String line : text.split("\n")) {
            if (norm(line).equals(c)) return true;
            String l = line.contains(": ") ? line.substring(line.lastIndexOf(": ") + 2) : line;
            if (norm(l).equals(c)) return true;
        }
        return false;
    }

    /**
     * From the notification listener: the newest message in a notification; rings for 2 minutes if it is his code.
     * posted = when the notification came (a lost phone may get the message late, so that is what counts as fresh);
     * msgTime = the message's own time (0 if the app doesn't give one), so each message rings once, even after a restart.
     */
    static void check(Context c, String notificationKey, String lastMessage, long posted, long msgTime) {
        try {
            Prefs p = new Prefs(c);
            if (!p.findPhone() || lastMessage == null || lastMessage.isEmpty()) return;
            if (System.currentTimeMillis() - posted > 10 * 60000L) return; // an old one shown again after a reboot
            if (!isCode(p.findCode(), lastMessage)) return;
            android.content.SharedPreferences s = c.getSharedPreferences("jarvis_find", Context.MODE_PRIVATE);
            String key = notificationKey + "|" + msgTime + "|" + lastMessage.hashCode();
            long now = System.currentTimeMillis(), before = s.getLong(key, 0);
            // the same message again: never with its own time; without one, not within 10 minutes (re-posts, repeat alerts)
            if (before > 0 && (msgTime > 0 || now - before < 10 * 60000L)) return;
            android.content.SharedPreferences.Editor e = s.edit();
            if (s.getAll().size() > 40) e.clear();
            e.putLong(key, now).apply();
            main.post(() -> start(c, 120000));
        } catch (Exception ignored) {}
    }

    private static long startedAt;

    /** How long it has been ringing. */
    static long age() { return System.currentTimeMillis() - startedAt; }

    static synchronized void start(Context c, long ms) {
        Context app = c.getApplicationContext();
        startedAt = System.currentTimeMillis();
        stop(app);
        AudioManager am = app.getSystemService(AudioManager.class);
        try {
            if (am != null) {
                oldAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
            Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(app, tone);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {}
        blink(app);
        // Stop as soon as he unlocks the phone, from the notification, or after 40 seconds (2 minutes for the code).
        unlock = new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { stop(x); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        f.addAction(ACTION_STOP);
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(unlock, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(unlock, f);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel("jarvis_find", "ఫోన్ వెతకడం", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent stopPi = PendingIntent.getBroadcast(app, 44, new Intent(ACTION_STOP).setPackage(app.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE_ID, new Notification.Builder(app, "jarvis_find")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("ఇక్కడే ఉన్నాను!")
                    .setContentText("ఆపడానికి నొక్కండి")
                    .setContentIntent(stopPi)
                    .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "ఆపు", stopPi).build())
                    .setOngoing(true)
                    .build());
        }
        main.postDelayed(() -> stop(app), ms);
    }

    static synchronized void stop(Context c) {
        Context app = c.getApplicationContext();
        blinking = false;
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null && oldAlarmVolume >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarmVolume, 0); } catch (Exception ignored) {}
            oldAlarmVolume = -1;
        }
        if (unlock != null) {
            try { app.unregisterReceiver(unlock); } catch (Exception ignored) {}
            unlock = null;
        }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE_ID);
    }

    static boolean running() { return player != null || blinking; }

    private static void blink(Context app) {
        CameraManager cm = app.getSystemService(CameraManager.class);
        if (cm == null) return;
        String id = null;
        try {
            for (String c : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) { id = c; break; }
            }
        } catch (Exception ignored) {}
        if (id == null) return;
        final String cam = id;
        blinking = true;
        new Thread(() -> {
            boolean on = false;
            while (blinking) {
                on = !on;
                try { cm.setTorchMode(cam, on); } catch (Exception e) { break; }
                try { Thread.sleep(350); } catch (InterruptedException e) { break; }
            }
            try { cm.setTorchMode(cam, false); } catch (Exception ignored) {}
        }, "jarvis-find-blink").start();
    }
}
