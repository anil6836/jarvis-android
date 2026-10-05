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
 * Keeps the phone's own mic "beeps" quiet where it safely can. The phone's speech recognizer beeps when it opens and when
 * it closes the mic. Android has no setting to turn them off, so the media sound is muted just around those moments:
 * while the mic is opening, and just before Jarvis closes it, coming back a moment later. While the mic is open and
 * listening, his sound is on as usual (a navigation voice is not lost).
 *
 * Care: a mute he set himself is never touched; the sound comes back at once when Jarvis speaks; never muted for more
 * than SAFETY_MS; and if Jarvis is closed while muted, it comes back the next time Jarvis starts (JarvisApp).
 */
final class MicQuiet {
    private MicQuiet() {}

    /** After the mic opened or closed: long enough for the recognizer's beep, short enough not to be noticed. */
    private static final long BACK_AFTER_MS = 700;
    /** Jarvis starts speaking just after a mic was cancelled: its closing beep still gets this long to pass. */
    private static final long SPEAK_GAP_MS = 250;
    /** However it goes, his sound is never kept off longer than this. */
    private static final long SAFETY_MS = 30_000;
    /**
     * The channels muted: media only. (System and notification sound are tied to the ringtone on phones; muting them could
     * switch his phone to vibrate and he would miss a call, so they are never touched. Where the beep is on one of those,
     * the camera instead keeps the phone's mic closed until he calls "Jarvis".)
     */
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC};
    private static final String[] NAMES = {"మీడియా"};

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<Object> holders = new HashSet<>();
    private static Context app;
    /** The channels muted by Jarvis right now (bit per STREAMS index). */
    private static int mutedMask;
    private static long releasedAt;
    /** For "Jarvis చెక్": which channels it keeps quiet on this phone. */
    static volatile String info = "";
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

    /** The mic is opening or about to close (who: the screen listening): the beep's channels are muted. */
    static synchronized void hold(Context c, Object who) {
        app = c.getApplicationContext();
        holders.add(who);
        main.removeCallbacks(back);
        main.removeCallbacks(loud);
        main.removeCallbacks(safety);
        main.postDelayed(safety, SAFETY_MS);
        if (mutedMask != 0) return;
        AudioManager am;
        try { am = app.getSystemService(AudioManager.class); } catch (Exception e) { return; }
        if (am == null) return;
        try { if (am.getMode() != AudioManager.MODE_NORMAL) return; } catch (Exception e) { return; } // a call: leave the sound as it is
        SharedPreferences s = sp(app);
        int unsafe = s.getInt("unsafe", 0); // channels that follow the ringtone here
        int want = 0;
        for (int i = 0; i < STREAMS.length; i++) {
            if ((unsafe & (1 << i)) != 0) continue;
            try { if (!am.isStreamMute(STREAMS[i])) want |= 1 << i; } catch (Exception ignored) {} // (his own mute: not ours to undo)
        }
        if (want == 0) return;
        s.edit().putInt("mask", want).commit(); // written first, once: a crash then can't leave his sound off
        StringBuilder used = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < STREAMS.length; i++) {
            if ((want & (1 << i)) == 0) continue;
            int st = STREAMS[i];
            try {
                int ringer = am.getRingerMode();
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0);
                if (i > 0 && am.getRingerMode() != ringer) { // this channel follows the ringtone: put it back, never again
                    am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0);
                    try { if (am.getRingerMode() != ringer) am.setRingerMode(ringer); } catch (Exception ignored) {}
                    unsafe |= 1 << i;
                    changed = true;
                    continue;
                }
                mutedMask |= 1 << i;
                used.append(used.length() == 0 ? "" : ", ").append(NAMES[i]);
            } catch (SecurityException e) { // not allowed (Do Not Disturb rules): leave this channel alone from now on
                unsafe |= 1 << i;
                changed = true;
            } catch (Exception ignored) {}
        }
        if (changed || mutedMask != want) s.edit().putInt("unsafe", unsafe).putInt("mask", mutedMask).commit();
        if (used.length() > 0) info = used.toString();
    }

    /** That beep has passed (the mic is open and listening, or closed): the sound comes back in a moment. */
    static synchronized void release(Object who) {
        if (holders.remove(who)) releasedAt = SystemClock.elapsedRealtime();
        if (holders.isEmpty()) {
            main.removeCallbacks(safety);
            if (mutedMask != 0) {
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
        if (wait > 0 && mutedMask != 0) main.postDelayed(loud, wait); else loudNow();
    }

    /** His own volume command: the sound is given back at once, so that what he asks for is what stays. */
    static synchronized void giveBack() {
        main.removeCallbacks(back);
        main.removeCallbacks(loud);
        loudNow();
    }

    private static synchronized void loudNow() {
        main.removeCallbacks(safety);
        if (mutedMask == 0) return;
        int mask = mutedMask;
        mutedMask = 0;
        unmute(app, mask);
        try { sp(app).edit().remove("mask").apply(); } catch (Exception ignored) {}
    }

    private static void unmute(Context c, int mask) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am == null) return;
            for (int i = 0; i < STREAMS.length; i++) {
                if ((mask & (1 << i)) == 0) continue;
                try { am.adjustStreamVolume(STREAMS[i], AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** When Jarvis starts: a mute Jarvis left behind (closed while the mic was opening) is undone. */
    static void restore(Context c) {
        try {
            Context a = c.getApplicationContext();
            SharedPreferences s = sp(a);
            int mask = s.getInt("mask", 0);
            if (s.getBoolean("muted", false)) mask |= 1; // (older versions: media only)
            if (mask == 0) return;
            unmute(a, mask);
            s.edit().remove("mask").remove("muted").apply();
        } catch (Exception ignored) {}
    }
}
