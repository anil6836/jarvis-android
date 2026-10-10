package com.anil.jarvis;

import android.content.Context;

/**
 * "Jarvis" said in a whisper (at night, on duty, someone sleeping nearby): for the next 3 minutes Jarvis answers softly
 * (about a third of the volume), on both voices. The whisper is told by the sound model from the wake word itself.
 */
final class Whisper {
    private Whisper() {}

    private static volatile long softUntil;
    /** The last call's whisper score, for "Jarvis చెక్" (-1 = none yet). */
    static volatile int lastPct = -1;

    static boolean enabled(Context c) { return c.getSharedPreferences("jarvis_sounds", Context.MODE_PRIVATE).getBoolean("whisper_soft", true); }

    static void setEnabled(Context c, boolean on) { c.getSharedPreferences("jarvis_sounds", Context.MODE_PRIVATE).edit().putBoolean("whisper_soft", on).apply(); }

    static void heard(Context c, float whispering, float speech) {
        lastPct = Math.round(whispering * 100);
        if (!enabled(c)) return;
        if (whispering >= 0.25f || (whispering >= 0.12f && whispering > speech * 0.5f)) softUntil = System.currentTimeMillis() + 3 * 60_000L;
        else softUntil = 0; // called in a normal voice: normal volume again
    }

    static boolean soft() { return System.currentTimeMillis() < softUntil; }

    /** The home tablet in her bedroom at night (HomeCare sets it every minute): Jarvis speaks at about half volume. */
    static volatile boolean nightSoft;
    /** Until then the voice is full (a second "బాగున్నారా?" after no answer). */
    static volatile long fullUntil;

    /** The voice's volume now: about a third after a whisper, about half in her bedroom at night, else full. */
    static float gain() {
        if (System.currentTimeMillis() < fullUntil) return 1f;
        float g = soft() ? 0.35f : 1f;
        return nightSoft ? Math.min(g, 0.55f) : g;
    }
}
