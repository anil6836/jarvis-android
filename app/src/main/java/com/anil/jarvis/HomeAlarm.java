package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * The home tablet's loud alarm for help (🆘 with no internet to reach Anil, or "అలారం మోగించు"): the alarm sound on the
 * alarm volume at full for up to 3 minutes, so a neighbour hears it. A touch on the screen, 🆘 again or "ఆపు" stops it,
 * and the alarm volume goes back to what it was.
 */
final class HomeAlarm {
    private HomeAlarm() {}

    private static volatile MediaPlayer player;
    private static int volumeBefore = -1;
    /** The app context from start(): the volume goes back even when the care engine never started. */
    private static Context appCtx;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable timeUp = HomeAlarm::stop;

    static boolean ringing() { return player != null; }

    /** Starts the alarm (any thread). */
    static void start(Context c) {
        Context app = c.getApplicationContext();
        main.post(() -> {
            if (player != null) return;
            appCtx = app;
            MediaPlayer p = null;
            try {
                AudioManager am = app.getSystemService(AudioManager.class);
                if (am != null) {
                    volumeBefore = am.getStreamVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                }
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                p.setDataSource(app, u);
                p.setLooping(true);
                p.prepare();
                p.start();
                player = p;
                main.postDelayed(timeUp, 3 * 60_000L);
                MainActivity.homeWake();
            } catch (Exception e) {
                if (p != null && player != p) try { p.release(); } catch (Exception ignored) {} // (a player that never started)
                stop();
                restore(app);
            }
        });
    }

    /** Stops it (any thread); the alarm volume goes back. */
    static void stop() {
        main.post(() -> {
            main.removeCallbacks(timeUp);
            MediaPlayer p = player;
            player = null;
            if (p != null) {
                try { p.stop(); } catch (Exception ignored) {}
                try { p.release(); } catch (Exception ignored) {}
            }
            Context app = appCtx != null ? appCtx : HomeCare.app();
            if (app != null) restore(app);
        });
    }

    private static void restore(Context app) {
        if (volumeBefore < 0) return;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, volumeBefore, 0);
        } catch (Exception ignored) {}
        volumeBefore = -1;
    }
}
