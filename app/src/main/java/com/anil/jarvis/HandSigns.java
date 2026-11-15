package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;

/**
 * ✋ (7) His hand in the air, read like the films' workshop (our own cyan look): an open hand moving turns the model,
 * two open hands going apart / together make it bigger / smaller, a fist that bursts open takes it apart, 👍 puts it
 * back together, ✌️ the show's next step, ☝️ spin on / off, 👎 the signs off. Pure logic (no camera, no Android), so
 * it is tested on the desk. Nothing here is ever saved or sent anywhere.
 */
final class HandSigns {
    // MediaPipe's own names for its signs
    static final String OPEN = "Open_Palm", FIST = "Closed_Fist", VICTORY = "Victory", UP = "Thumb_Up", DOWN = "Thumb_Down", POINT = "Pointing_Up";
    static final float MIN_SCORE = 0.55f;

    static final long GAP_MS = 300;        // (a hand lost longer than this: its movement starts again from where it is)
    static final long BURST_FIST_MS = 350; // ✊ held at least this long …
    static final long BURST_OPEN_MS = 600; // … then 🖐️ within this: 💥
    static final long UP_MS = 500, VICTORY_MS = 400, POINT_MS = 600, DOWN_MS = 800;
    static final long COOL_MS = 1200;      // (after one sign's action, a moment before the next)
    static final long IDLE_MS = 180_000;   // (3 minutes with no hand: the signs turn themselves off)
    static final double DEAD = 0.004, GAIN = 4.2, ZOOM_DEAD = 0.02;

    /** One hand in one picture: MediaPipe's sign and its 21 points (0..1 of the picture, mirrored like a selfie). */
    static final class Hand {
        final String sign;
        final float score;
        final float[] pts; // x0, y0, x1, y1, … (42)

        Hand(String sign, float score, float[] pts) {
            this.sign = sign == null ? "" : sign;
            this.score = score;
            this.pts = pts;
        }

        String sure() { return score >= MIN_SCORE ? sign : ""; }
    }

    /** What the workshop should do: turn (az, el radians), zoom (× the distance: below 1 = bigger), or one action. */
    static final class Act {
        final String kind; // turn, zoom, explode, assemble, next, spin, off, idle
        final double az, el, zoom;

        Act(String kind, double az, double el, double zoom) { this.kind = kind; this.az = az; this.el = el; this.zoom = zoom; }

        static Act of(String kind) { return new Act(kind, 0, 0, 1); }

        @Override public String toString() { return kind + (kind.equals("turn") ? String.format(java.util.Locale.US, "(%.3f,%.3f)", az, el) : kind.equals("zoom") ? String.format(java.util.Locale.US, "(%.3f)", zoom) : ""); }
    }

    private long startMs = -1, lastSeen, lastAct = -COOL_MS * 10, lastFrame = -1;
    // one open hand: where it was (smoothed) and where the last turn was counted from
    private boolean oneOn;
    private double smX, smY, usedX, usedY;
    // two open hands: how far apart
    private boolean twoOn;
    private double smD, usedD;
    // ✊ → 🖐️
    private long fistSince, fistLast;
    // a sign held
    private String holdSign = "";
    private long holdSince, holdLast;
    private boolean holdFired, idleSaid;
    /** For his little cyan picture: what the hand is doing now. */
    String label = "✋ చేయి చూపించండి";

    /**
     * One picture's hands. aspect = the picture's width / height (its points are 0..1 of each side). exploded / showing /
     * spinning: the workshop now (so ✊→🖐️ only takes apart what is together, ✌️ only works in the show).
     */
    List<Act> step(List<Hand> hands, long ms, double aspect, boolean exploded, boolean showing) {
        List<Act> out = new ArrayList<>();
        if (startMs < 0) { startMs = ms; lastSeen = ms; }
        boolean gap = lastFrame >= 0 && ms - lastFrame > GAP_MS;
        lastFrame = ms;
        int n = hands == null ? 0 : hands.size();
        if (n == 0) {
            oneOn = false;
            twoOn = false;
            label = "✋ చేయి చూపించండి";
            if (!idleSaid && ms - lastSeen > IDLE_MS) { idleSaid = true; out.add(Act.of("idle")); }
            return out;
        }
        lastSeen = ms;
        idleSaid = false;
        if (gap) { oneOn = false; twoOn = false; }
        double ax = aspect >= 1 ? 1 : aspect, ay = aspect >= 1 ? 1 / aspect : 1; // (both sides in one unit: the long side = 1)

        if (n >= 2) { // 👐 two hands: only the size
            oneOn = false;
            resetHold("");
            fistSince = 0;
            Hand a = hands.get(0), b = hands.get(1);
            if (OPEN.equals(a.sure()) && OPEN.equals(b.sure()) && !showing) {
                double[] pa = palm(a, ax, ay), pb = palm(b, ax, ay);
                double d = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                if (!twoOn) { twoOn = true; smD = d; usedD = d; }
                else {
                    smD += 0.6 * (d - smD);
                    if (usedD > 1e-4 && Math.abs(Math.log(smD / usedD)) > ZOOM_DEAD) {
                        double r = smD / usedD;
                        out.add(new Act("zoom", 0, 0, clamp(1 / r, 0.8, 1.25)));
                        usedD = smD;
                    }
                }
                label = "👐 దూరం చేస్తే పెద్దది, దగ్గర చేస్తే చిన్నది";
            } else {
                twoOn = false;
                label = showing ? "✋ షోలో ఒక చేయి: ✌️ తర్వాత, 👎 ఆపు" : "👐 రెండు చేతులూ తెరిచి ఉంచండి";
            }
            return out;
        }

        // ✋ one hand
        twoOn = false;
        Hand h = hands.get(0);
        String s = h.sure();
        boolean cool = ms - lastAct >= COOL_MS;

        // 🖐️ moving: turn
        if (OPEN.equals(s) && !showing) {
            double[] p = palm(h, ax, ay);
            if (!oneOn) { oneOn = true; smX = p[0]; smY = p[1]; usedX = smX; usedY = smY; }
            else {
                smX += 0.6 * (p[0] - smX);
                smY += 0.6 * (p[1] - smY);
                double dx = smX - usedX, dy = smY - usedY;
                if (Math.hypot(dx, dy) > DEAD) {
                    out.add(new Act("turn", clamp(dx * GAIN, -0.5, 0.5), clamp(dy * GAIN * 0.8, -0.4, 0.4), 1));
                    usedX = smX;
                    usedY = smY;
                }
            }
        } else oneOn = false;

        // ✊ … 🖐️: burst apart
        if (FIST.equals(s)) {
            if (fistSince == 0 || ms - fistLast > 250) fistSince = ms;
            fistLast = ms;
        } else if (OPEN.equals(s) && fistSince > 0) {
            boolean burst = fistLast - fistSince >= BURST_FIST_MS && ms - fistLast <= BURST_OPEN_MS;
            fistSince = 0;
            if (burst && !showing && !exploded && cool) { out.add(Act.of("explode")); lastAct = ms; cool = false; }
        } else if (fistSince > 0 && ms - fistLast > BURST_OPEN_MS) fistSince = 0;

        // 👍 ✌️ ☝️ 👎 held
        String raw = s;
        if (s.isEmpty() && !holdSign.isEmpty() && ms - holdLast < 200) s = holdSign; // (a blink of "not sure" does not break a hold)
        if (!s.equals(holdSign)) { resetHold(s); holdSince = ms; }
        if (!raw.isEmpty()) holdLast = ms;
        long held = ms - holdSince;
        if (!holdFired && cool) {
            String act = null;
            if (DOWN.equals(s) && held >= DOWN_MS) act = "off";
            else if (VICTORY.equals(s) && held >= VICTORY_MS && showing) act = "next";
            else if (UP.equals(s) && held >= UP_MS && exploded && !showing) act = "assemble";
            else if (POINT.equals(s) && held >= POINT_MS && !showing) act = "spin";
            if (act != null) { out.add(Act.of(act)); holdFired = true; lastAct = ms; }
        }
        label = labelFor(s, exploded, showing);
        return out;
    }

    private void resetHold(String s) {
        holdSign = s;
        holdSince = 0;
        holdFired = false;
    }

    private static String labelFor(String s, boolean exploded, boolean showing) {
        if (showing && !VICTORY.equals(s) && !DOWN.equals(s)) return "✋ షోలో: ✌️ తర్వాత, 👎 సైగలు ఆపు";
        switch (s) {
            case OPEN: return "🖐️ కదిపితే బొమ్మ తిరుగుతుంది";
            case FIST: return exploded ? "✊ (👍 చూపిస్తే కలుస్తుంది)" : "✊ … ఇప్పుడు తెరిస్తే విడిపోతుంది";
            case UP: return exploded ? "👍 కలుపుతోంది…" : "👍 ఇప్పటికే కలిసి ఉంది";
            case VICTORY: return showing ? "✌️ తర్వాతి అడుగు…" : "✌️ షోలో తర్వాతి అడుగు";
            case POINT: return "☝️ తిప్పడం…";
            case DOWN: return "👎 సైగలు ఆపుతోంది…";
            default: return "✋";
        }
    }

    /** The middle of the palm (wrist and the four knuckles), in one unit for both sides. */
    static double[] palm(Hand h, double ax, double ay) {
        int[] at = {0, 5, 9, 13, 17};
        double x = 0, y = 0;
        int k = 0;
        for (int i : at) {
            if (h.pts == null || h.pts.length < 2 * i + 2) continue;
            x += h.pts[2 * i];
            y += h.pts[2 * i + 1];
            k++;
        }
        if (k == 0) return new double[]{0.5 * ax, 0.5 * ay};
        return new double[]{x / k * ax, y / k * ay};
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** The hand's bones, for his little cyan picture (pairs of point numbers). */
    static final int[] BONES = {0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16,
            13, 17, 0, 17, 17, 18, 18, 19, 19, 20};
}
