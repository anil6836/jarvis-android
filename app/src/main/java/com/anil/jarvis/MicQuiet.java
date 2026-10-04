package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the phone's own mic "beeps" quiet. The phone's speech recognizer beeps when it opens and when it closes the mic;
 * those beeps sounded like the mic switching on and off. Android has no setting to turn them off, so the media sound
 * (where the recognizer plays them) is muted just around those moments: while the mic is opening, and just before Jarvis
 * closes it, coming back a moment later. While the mic is open and listening, his media sound is on as usual (a
 * navigation voice is not lost). Jarvis's own sounds (the chirp when he calls, the soft tone when the mic closes) are on
 * another sound channel and still play. A mute he set himself is never touched; the sound comes back at once when Jarvis
 * speaks; never muted for more than SAFETY_MS; and if Jarvis is closed while it is muted, it comes back the next time
 * Jarvis starts (JarvisApp).
 */
final class MicQuiet {
    private MicQuiet() {}

    /** After the mic opened or closed: long enough for the recognizer's beep, short enough not to be noticed. */
    private static final long BACK_AFTER_MS = 700;
    /** Jarvis starts speaking just after a mic was cancelled: its closing beep still gets this long to pass. */
    private static final long SPEAK_GAP_MS = 250;
    /** However it goes, his sound is never kept off longer than this. */
    private static final long SAFETY_MS = 30_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<Object> holders = new HashSet<>();
    private static Context app;
    private static boolean mutedByUs;
    private static long releasedAt;
    private static final Runnable back = () -> {
        synchronized (MicQuiet.class) {
            if (holders.isEmpty()) loudNow();
        }
    };
    private static final Runnable loud = MicQuiet::loudNow;
    private static final Runnable safety = () -> {
        synchronized (MicQuiet.class) {
            holders.clear();
            loudNow();
        }
    };

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_mic_quiet", Context.MODE_PRIVATE); }

    /** The mic is opening or about to close (who: the screen listening): the media sound is muted for its beep. */
    static synchronized void hold(Context c, Object who) {
        app = c.getApplicationContext();
        holders.add(who);
        main.removeCallbacks(back);
        main.removeCallbacks(loud);
        main.removeCallbacks(safety);
        main.postDelayed(safety, SAFETY_MS);
        if (mutedByUs) return;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null || am.isStreamMute(AudioManager.STREAM_MUSIC)) return; // his own mute: not ours to undo
            if (am.getMode() != AudioManager.MODE_NORMAL) return; // a call: leave the sound as it is
            sp(app).edit().putBoolean("muted", true).commit(); // written first: a crash then can't leave his sound off
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            mutedByUs = true;
        } catch (Exception e) {
            try { sp(app).edit().remove("muted").apply(); } catch (Exception ignored) {}
        }
    }

    /** That beep has passed (the mic is open and listening, or closed): the sound comes back in a moment. */
    static synchronized void release(Object who) {
        if (holders.remove(who)) releasedAt = SystemClock.elapsedRealtime();
        if (holders.isEmpty()) {
            main.removeCallbacks(safety);
            if (mutedByUs) {
                main.removeCallbacks(back);
                main.postDelayed(back, BACK_AFTER_MS);
            }
        }
    }

    /** Jarvis is about to speak or play something: the sound comes back now (a just-closed mic's beep let pass first). */
    static synchronized void speaking() {
        main.removeCallbacks(back);
        main.removeCallbacks(loud);
        long wait = SPEAK_GAP_MS - (SystemClock.elapsedRealtime() - releasedAt);
        if (wait > 0 && mutedByUs) main.postDelayed(loud, wait); else loudNow();
    }

    /** His own volume command: the sound is given back at once, so that what he asks for is what stays. */
    static synchronized void giveBack() {
        main.removeCallbacks(back);
        main.removeCallbacks(loud);
        loudNow();
    }

    private static synchronized void loudNow() {
        main.removeCallbacks(safety);
        if (!mutedByUs) return;
        mutedByUs = false;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {}
        try { sp(app).edit().remove("muted").apply(); } catch (Exception ignored) {}
    }

    /** When Jarvis starts: a mute Jarvis left behind (closed while the mic was opening) is undone. */
    static void restore(Context c) {
        try {
            Context a = c.getApplicationContext();
            if (!sp(a).getBoolean("muted", false)) return;
            AudioManager am = a.getSystemService(AudioManager.class);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            sp(a).edit().remove("muted").apply();
        } catch (Exception ignored) {}
    }
}
