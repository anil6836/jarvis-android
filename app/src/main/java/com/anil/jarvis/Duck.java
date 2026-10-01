package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * While Jarvis speaks (a message read out, an announcement, a reply), music and radio playing on the phone go quiet
 * and come back up by themselves afterwards: Jarvis asks Android for "may duck" audio focus, and Android lowers the
 * other sound (his radio here, the Telugu Radios app, YouTube...) until Jarvis lets go.
 */
final class Duck {
    private Duck() {}

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static AudioFocusRequest req;
    private static AudioManager am;
    private static final Runnable release = Duck::letGo;
    /** Never keep the music down for long if a "done" got lost. */
    private static final Runnable safety = Duck::letGo;

    /** Jarvis starts speaking. Safe from any thread. */
    static void on(Context c) {
        final Context app = c.getApplicationContext();
        main.post(() -> {
            main.removeCallbacks(release);
            main.removeCallbacks(safety);
            main.postDelayed(safety, 3 * 60000L);
            if (req != null) return;
            try {
                am = app.getSystemService(AudioManager.class);
                req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(change -> {}, main).build();
                am.requestAudioFocus(req);
            } catch (Exception e) {
                req = null;
            }
        });
    }

    /** Jarvis stopped speaking: the music comes back up shortly (not between two messages read one after another). */
    static void off() {
        main.post(() -> {
            main.removeCallbacks(release);
            if (req != null) main.postDelayed(release, 700);
        });
    }

    private static void letGo() {
        main.removeCallbacks(release);
        main.removeCallbacks(safety);
        if (req == null) return;
        try { am.abandonAudioFocusRequest(req); } catch (Exception ignored) {}
        req = null;
    }
}
