package com.anil.jarvis;

import java.util.Random;

/**
 * 💃 Which step Jarvis dances next on the car screen (no Android here: desk-tested). Anil: "ఒకటే స్టెప్ వేస్తే బాగాలేదు —
 * పాటకు, బీట్‌కు తగ్గట్టు మార్చాలి". So the step changes every phrase (16 beats; 8 in a slow song), and with the song's
 * loudness against its own ({@link CarBeat#energy()}): a quiet part gets the calm steps, a loud part (a chorus) the big
 * ones — and when the song turns loud, a cheer first. Never the same step twice in a row, nor the one before it.
 * The change itself lands on the next bar ({@link IdlePlay#queueDanceStyle}).
 */
final class CarDance {
    static final int CALM = 0, MID = 1, HIGH = 2;
    static final int PHRASE = 16, SLOW_PHRASE = 8, MIN_BEATS = 4;
    static final float SLOW_SONG = 0.75f; // (seconds a beat: slower than 80 a minute)

    private static final int[][] POOL = {
            {IdlePlay.D_SLOW, IdlePlay.D_SWAY, IdlePlay.D_SHOULDER},
            {IdlePlay.D_SHIMMY, IdlePlay.D_CLAP, IdlePlay.D_ROLL, IdlePlay.D_POINT, IdlePlay.D_SHOULDER, IdlePlay.D_ROBOT},
            {IdlePlay.D_STEPS, IdlePlay.D_DRUMS, IdlePlay.D_PUMP, IdlePlay.D_HANDS_UP, IdlePlay.D_LASSO, IdlePlay.D_POINT, IdlePlay.D_ROBOT}};
    /** Unknown loudness (the box gives no sound to measure): all the lively steps, in turn. */
    private static final int[] LIVELY = {IdlePlay.D_SHIMMY, IdlePlay.D_CLAP, IdlePlay.D_ROLL, IdlePlay.D_POINT, IdlePlay.D_ROBOT,
            IdlePlay.D_STEPS, IdlePlay.D_DRUMS, IdlePlay.D_PUMP, IdlePlay.D_HANDS_UP, IdlePlay.D_LASSO, IdlePlay.D_SHOULDER};

    /** The steps of a level (a copy). */
    static int[] pool(int level) { return (level < 0 ? LIVELY : POOL[Math.min(HIGH, level)]).clone(); }

    /**
     * The loudness level from {@link CarBeat#energy()} (an amplitude ratio; 0 = unknown → -1: all the lively steps; a
     * slow song: calm), with a margin so a level doesn't flicker: loud from 1.25 (+2 dB; back below 1.10), quiet below
     * 0.75 (back above 0.86). A slow song is one level lower: calm, and only its loud part gets the middle steps. Pure.
     */
    static int level(float ratio, float secondsPerBeat, int was) {
        boolean slowSong = secondsPerBeat > SLOW_SONG;
        if (ratio <= 0) return slowSong ? CALM : -1;
        if (slowSong) return ratio >= (was == MID ? 1.10f : 1.25f) ? MID : CALM;
        if (was == HIGH) return ratio >= 1.10f ? HIGH : ratio < 0.75f ? CALM : MID;
        if (was == CALM) return ratio <= 0.86f ? CALM : ratio >= 1.25f ? HIGH : MID;
        return ratio >= 1.25f ? HIGH : ratio < 0.75f ? CALM : MID;
    }

    /** A step from the level's steps that is neither the one now nor the one before it (when the pool allows). Pure. */
    static int pick(int level, int now, int before, Random rnd) {
        int[] p = level < 0 ? LIVELY : POOL[Math.min(HIGH, level)];
        int n = 0;
        int[] ok = new int[p.length];
        for (int s : p) if (s != now && s != before) ok[n++] = s;
        if (n == 0) for (int s : p) if (s != now) ok[n++] = s;
        if (n == 0) return p[0];
        return ok[rnd.nextInt(n)];
    }

    // ---- the running dance (one per screen: "the one before" carries over from the last song)
    private int style = -1, before = -1, level = MID, heldLevel = MID;
    private float beats, held, heldSec, songBeats;
    private boolean slow;

    int style() { return style; }
    int level() { return level; }

    /** A song's dance starts: a slow song begins with the slow sway, any other with a lively step. */
    int start(float secondsPerBeat, float ratio, Random rnd) {
        slow = secondsPerBeat > SLOW_SONG;
        level = level(ratio, secondsPerBeat, slow ? CALM : MID);
        heldLevel = level;
        beats = 0;
        songBeats = 0;
        held = 0;
        heldSec = 0;
        int s = slow ? (style == IdlePlay.D_SLOW ? pick(CALM, style, before, rnd) : IdlePlay.D_SLOW) : pick(level == CALM ? CALM : -1, style, before, rnd);
        before = style;
        style = s;
        return s;
    }

    /**
     * Once a second or so while he dances: {@code dt} seconds went by. Returns the next step, or -1 to keep this one;
     * {@code cheer[0]} is set when the song just turned loud (a cheer, then a big step). A louder part counts after a bar
     * (4 beats); a quieter one only after 8 beats and 4 seconds (a break, a map's voice ducking the song, the gap between
     * songs are not "quiet parts"); the first phrase of a song always plays out; unknown ↔ known loudness changes no step.
     */
    int tick(float dt, float secondsPerBeat, float ratio, Random rnd, boolean[] cheer) {
        if (cheer != null && cheer.length > 0) cheer[0] = false;
        float spb = secondsPerBeat < 0.3f || secondsPerBeat > 1.2f ? 0.6f : secondsPerBeat;
        slow = spb > SLOW_SONG;
        float sec = Math.max(0, dt), add = sec / spb;
        beats += add;
        songBeats += add;
        int raw = level(ratio, spb, level);
        boolean change = false;
        if (raw == -1 || level == -1) { // (the measure came or went: nothing about the song changed)
            level = raw;
            heldLevel = raw;
            held = heldSec = 0;
        } else if (raw != level) {
            if (raw != heldLevel) { heldLevel = raw; held = heldSec = 0; }
            held += add;
            heldSec += sec;
            boolean up = raw > level;
            if (up ? held >= MIN_BEATS : held >= 2 * MIN_BEATS && heldSec >= 4) { // (the new loudness held long enough: it is real)
                boolean firstPhrase = songBeats < (slow ? SLOW_PHRASE : PHRASE);
                if (raw == HIGH && !firstPhrase && cheer != null && cheer.length > 0) cheer[0] = true;
                level = raw;
                held = heldSec = 0;
                if (beats >= MIN_BEATS && !firstPhrase) change = true;
            }
        } else { heldLevel = level; held = heldSec = 0; }
        if (beats >= (slow ? SLOW_PHRASE : PHRASE)) change = true;
        if (!change) return -1;
        beats = 0;
        int s = pick(level, style, before, rnd);
        before = style;
        style = s;
        return s;
    }

}
