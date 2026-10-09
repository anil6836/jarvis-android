package com.anil.jarvis.watch;

import org.json.JSONObject;

import java.util.Locale;

/**
 * W31: the walking coach's counting, from the watch's step counter. A walk is steps that keep coming (no pause over
 * 75 seconds, or a long gap that still averages a walking pace); it counts when it lasted 2 minutes and 150 steps at
 * 40 steps a minute or more (so moving about the house is not a walk). Each kilometre on the way and the end are told:
 * steps, metres (under 1 km) or km, and minutes. Pure (no Android): tested on a desk. One clock throughout (ms).
 */
final class WalkCoach {
    /** Metres a step (an adult's average; the distance is "about"). */
    static final double STRIDE = 0.72;
    /** His own step length (from his height, sent by the phone), else the average. */
    static volatile double stride = STRIDE;
    static final long PAUSE = 75_000L, RESTART = 15_000L, MIN_TIME = 120_000L;
    static final int MIN_STEPS = 150;
    /** A long gap with this pace or more (50 steps a minute) was walking all along (the readings were just late). */
    private static final double PACE = 50 / 60_000.0, MIN_AVG = 40 / 60_000.0;
    /** About how long one step takes (110 a minute), to guess when a walk began. */
    private static final long STEP_MS = 550;

    interface Out {
        void km(int km, int steps, long ms);
        void ended(int steps, long ms, long endT);
    }

    private float lastCount = -1;
    private long lastT = -1;
    private float walkFrom = -1, walkTo = -1;
    private long walkStart = -1, walkLast = -1;
    private int kmTold;

    boolean active() { return walkStart >= 0; }

    /** When the walk going on had its last step (-1: none). */
    long lastStep() { return walkLast; }

    int steps() { return active() ? Math.round(walkTo - walkFrom) : 0; }

    /** A new reading of the step counter at t. */
    void sample(float count, long t, Out out) {
        if (lastCount < 0) { lastCount = count; lastT = t; return; }
        if (count < lastCount || lastT - t > 600_000L) { // the watch restarted: its counter (and its clock) began again
            if (count < lastCount && t >= lastT) finish(out);
            else drop();
            lastCount = count;
            lastT = t;
            return;
        }
        if (t < lastT) t = lastT; // (a reading a little out of order: as if at the same moment)
        float ds = count - lastCount;
        if (ds <= 0) { check(t, out); return; }
        long dt = Math.max(1, t - lastT);
        boolean steady = ds / dt >= PACE;
        // a pause: the walk before it is over (these steps may start a new one). Before it counts as a walk, a few steps
        // with a pause of 15 s start it again, so moving about the house never adds up to a "walk".
        if (active() && !steady && dt > (counts() ? PAUSE : RESTART)) finish(out);
        if (!active()) {
            if (steady || dt <= RESTART || ds <= 20) { // at a walking pace, or a few steps just now: the walk's first ones
                walkFrom = lastCount;
                walkStart = t - Math.min(dt, (long) (ds * STEP_MS));
            } else { // (steps here and there over a long pause, e.g. while the coach was off: not part of a walk)
                walkFrom = count;
                walkStart = t;
            }
            kmTold = 0;
        }
        walkTo = count;
        walkLast = t;
        lastCount = count;
        lastT = t;
        int km = (int) (steps() * stride / 1000);
        if (km > kmTold) {
            kmTold = km;
            if (counts()) out.km(km, steps(), walkLast - walkStart);
        }
    }

    /** No new steps for a while (now on the same clock): the walk is over. */
    void check(long now, Out out) {
        if (active() && now - walkLast > (counts() ? PAUSE : RESTART)) finish(out);
    }

    private boolean counts() {
        int s = steps();
        long ms = walkLast - walkStart;
        return s >= MIN_STEPS && ms >= MIN_TIME && s / (double) ms >= MIN_AVG;
    }

    private void finish(Out out) {
        if (!active()) return;
        if (counts()) out.ended(steps(), walkLast - walkStart, walkLast);
        walkStart = walkLast = -1;
        walkFrom = walkTo = -1;
        kmTold = 0;
    }

    /** Forget the walk going on and the last reading (the coach was turned off): it starts afresh from the next reading. */
    void drop() {
        walkStart = walkLast = -1;
        walkFrom = walkTo = -1;
        kmTold = 0;
        lastCount = -1;
        lastT = -1;
    }

    // ---------------------------------------------------------------- kept over a restart of the service

    JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("lc", lastCount).put("lt", lastT).put("wf", walkFrom).put("wt", walkTo).put("ws", walkStart).put("wl", walkLast).put("km", kmTold);
        } catch (Exception ignored) {}
        return o;
    }

    void load(JSONObject o) {
        if (o == null || !o.has("lc")) return;
        lastCount = (float) o.optDouble("lc", -1);
        lastT = o.optLong("lt", -1);
        walkFrom = (float) o.optDouble("wf", -1);
        walkTo = (float) o.optDouble("wt", -1);
        walkStart = o.optLong("ws", -1);
        walkLast = o.optLong("wl", -1);
        kmTold = o.optInt("km");
    }

    // ---------------------------------------------------------------- words

    /** "350 అడుగులు, 250 మీటర్లు, 4 నిమిషాలు" / "2,600 అడుగులు, 1.9 కి.మీ, 22 నిమిషాలు". */
    static String line(int steps, long ms) {
        return String.format(Locale.ENGLISH, "%,d", steps) + " అడుగులు, " + distance(steps) + ", " + minutes(ms);
    }

    static String distance(int steps) {
        double m = steps * stride;
        if (m < 995) return Math.max(10, Math.round(m / 10.0) * 10) + " మీటర్లు";
        String km = String.format(Locale.ENGLISH, "%.1f", m / 1000.0);
        if (km.endsWith(".0")) km = km.substring(0, km.length() - 2);
        return km + " కి.మీ";
    }

    static String minutes(long ms) {
        long m = Math.max(1, Math.round(ms / 60_000.0));
        if (m < 60) return m + (m == 1 ? " నిమిషం" : " నిమిషాలు");
        long h = m / 60, r = m % 60;
        return h + (h == 1 ? " గంట" : " గంటలు") + (r == 0 ? "" : " " + r + (r == 1 ? " నిమిషం" : " నిమిషాలు"));
    }
}
