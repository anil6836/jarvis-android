package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;

/**
 * 🤏 (20 Nov) Pinches in the air for the workshop's stage, read from the same 21 hand points as {@link HandSigns}: thumb and
 * forefinger together catch the piece under them and move it; a twist of the wrist turns it; the other hand's pinch, drawn
 * away or closer, makes it bigger or smaller; let go to drop it, or flick it home; two quick pinches ask what it is (Jarvis
 * explains only then). A pinch on empty space turns the view. Two hands pinching and drawn apart pull the model apart.
 * A fist held still for a second, then raised or lowered, is the throttle (no burst afterwards). ✌️ outside a show puts the
 * view back. Pure logic (no camera, no Android): tested on the desk. Nothing is saved or sent anywhere but the sealed stage.
 */
final class Pinch {
    static final double CLOSE = 0.33, OPEN = 0.5;     // thumb–forefinger gap / the hand's size: pinched below, let go above
    static final long DOUBLE_MS = 480, TAP_MS = 260, FIST_MS = 1000, AFTER_MS = 900, VICTORY_MS = 450, HOVER_MS = 90;
    static final double MOVE_DEAD = 0.004, FLICK_SPEED = 1.6, TAP_MOVE = 0.03, TURN_GAIN = 4.0;

    /** One message for the stage ({t: "hand", k, x, y, v}), or one for the screen: k = "turn" (x = az, y = el), "power" (v), "reset", "explodek" (v). */
    static final class Msg {
        final String k;
        final double x, y, v;

        Msg(String k, double x, double y, double v) { this.k = k; this.x = x; this.y = y; this.v = v; }

        @Override public String toString() { return k; }
    }

    private boolean down, holding, turning, two, fisting, fistLive;
    private double px, py, sx, sy, lastX, lastY, vx, vy, rollLast, d0, k0, fistY0, power0, scaleGap;
    private long downAt, lastUp = -10_000, lastFrame = -1, prevFrame = -1, lastHover, fistSince, busyUntil, victorySince;
    private int taps;
    private double tapX, tapY;
    /** For his little cyan picture. */
    String label = "";

    /** The screen says whether the last "down" caught a piece (the stage's "grab" answer). */
    void caught(boolean yes) { holding = yes; turning = !yes && down; }

    /** True while a pinch or a fist-throttle is going on (and a moment after): the old signs rest, so a pinch is never a "burst". */
    boolean busy(long ms) { return down || two || fistLive || ms < busyUntil; }

    /**
     * One picture's hands → messages. aspect = width / height of the picture. power = the throttle now (0..1) when this
     * model has one (else NaN). showing = the 🎓 show is on (✌️ is its "next" there, not a reset).
     */
    List<Msg> step(List<HandSigns.Hand> hands, long ms, double aspect, double power, boolean showing) {
        List<Msg> out = new ArrayList<>();
        int n = hands == null ? 0 : hands.size();
        boolean gap = lastFrame >= 0 && ms - lastFrame > HandSigns.GAP_MS;
        prevFrame = lastFrame;
        lastFrame = ms;
        if (n == 0 || gap) {
            if (down) out.add(new Msg("up", px, py, 0));
            if (n == 0 && (lastHover > 0)) { out.add(new Msg("off", 0, 0, 0)); lastHover = 0; }
            reset(ms);
            if (n == 0) return out;
        }
        HandSigns.Hand a = hands.get(0);
        double ax = aspect >= 1 ? 1 : aspect, ay = aspect >= 1 ? 1 / aspect : 1;

        // 👐 two hands
        if (n >= 2) {
            HandSigns.Hand b = hands.get(1);
            boolean pa = pinched(a, down), pb = pinched(b, two);
            double[] ta = tip(a), tb = tip(b);
            double gapAB = Math.hypot((ta[0] - tb[0]) * ax, (ta[1] - tb[1]) * ay);
            if (down && holding) { // one hand holds a piece: the other's pinch sizes it
                if (pb && !two) { two = true; scaleGap = gapAB; }
                else if (pb && scaleGap > 1e-3 && Math.abs(Math.log(gapAB / scaleGap)) > 0.03) { // (the stage multiplies: each step is the change since the last)
                    out.add(new Msg("scale", 0, 0, clamp(gapAB / scaleGap, 0.7, 1.4)));
                    scaleGap = gapAB;
                } else if (!pb) two = false;
                // the holding hand still moves it
                move(a, ms, out);
                label = "🤏🤏 దూరం చేస్తే పెద్దది";
                return out;
            }
            if (pa && pb) { // both pinch on nothing: drawn apart pulls the model apart
                if (down) { out.add(new Msg("up", px, py, 0)); down = false; }
                if (!two) { two = true; d0 = gapAB; k0 = 0; }
                else out.add(new Msg("explodek", 0, 0, clamp(k0 + (gapAB - d0) / 0.35, 0, 1)));
                busyUntil = ms + AFTER_MS;
                label = "🤏 🤏 దూరం చేస్తే విడిపోతుంది";
                return out;
            }
            two = false;
        } else two = false;

        // ✊ held a second, then up / down: the throttle
        String s = a.sure();
        double[] palm = HandSigns.palm(a, ax, ay);
        if (!Double.isNaN(power) && HandSigns.FIST.equals(s) && !down) {
            if (!fisting) { fisting = true; fistSince = ms; fistY0 = palm[1]; power0 = power; }
            else if (!fistLive && ms - fistSince >= FIST_MS && Math.abs(palm[1] - fistY0) < 0.04) { fistLive = true; fistY0 = palm[1]; power0 = power; label = "✊ పైకి ఎత్తితే పవర్ ఎక్కువ"; }
            else if (fistLive) {
                double want = clamp(power0 + (fistY0 - palm[1]) * 2.2, 0, 1);
                if (Math.abs(want - power) >= 0.03) out.add(new Msg("power", 0, 0, want));
            }
            if (fistLive) busyUntil = ms + AFTER_MS;
            return out;
        }
        if (fisting) { fisting = false; if (fistLive) { fistLive = false; busyUntil = ms + AFTER_MS; } }

        // ✌️ outside the show: the view back to the start
        if (HandSigns.VICTORY.equals(s) && !showing && !down) {
            if (victorySince == 0) victorySince = ms;
            else if (victorySince > 0 && ms - victorySince >= VICTORY_MS) { out.add(new Msg("reset", 0, 0, 0)); victorySince = -1; busyUntil = ms + AFTER_MS; }
        } else victorySince = 0;

        // 🤏 one hand
        boolean p = pinched(a, down);
        double[] t = tip(a);
        double x = t[0], y = t[1];
        if (p && !down) {
            down = true; holding = false; turning = false;
            downAt = ms;
            px = sx = lastX = x; py = sy = lastY = y; vx = vy = 0;
            rollLast = roll(a, 1, 1);
            out.add(new Msg("down", x, y, 0));
            label = "🤏 పట్టుకున్నారు";
        } else if (p) {
            move(a, ms, out);
        } else if (down) { // let go
            down = false;
            busyUntil = ms + AFTER_MS;
            double speed = Math.hypot(vx * ax, vy * ay);
            boolean quick = ms - downAt <= TAP_MS && Math.hypot((px - sx) * ax, (py - sy) * ay) < TAP_MOVE;
            out.add(new Msg(holding && speed > FLICK_SPEED ? "flick" : "up", px, py, 0));
            if (quick) {
                if (taps == 1 && ms - lastUp <= DOUBLE_MS && Math.hypot((tapX - px) * ax, (tapY - py) * ay) < TAP_MOVE * 2) { out.add(new Msg("dbl", px, py, 0)); taps = 0; label = "🤏🤏 ఇది ఏంటో చెప్తాను"; }
                else { taps = 1; tapX = px; tapY = py; }
            } else taps = 0;
            lastUp = ms;
            holding = false; turning = false;
        } else if (ms - lastHover >= HOVER_MS) { // just pointing: the tip shows where (and the 🖐️ X-ray window follows it)
            lastHover = ms;
            out.add(new Msg("hover", x, y, 0));
            label = "☝️ బొటనవేలు, చూపుడువేలు కలిపితే పట్టుకుంటుంది";
        }
        return out;
    }

    private void move(HandSigns.Hand a, long ms, List<Msg> out) {
        double[] t = tip(a);
        double x = px + 0.6 * (t[0] - px), y = py + 0.6 * (t[1] - py);
        double dt = Math.max(16, prevFrame > 0 ? ms - prevFrame : 33) / 1000.0;
        if (Math.hypot(x - px, y - py) >= MOVE_DEAD) {
            if (turning) out.add(new Msg("turn", clamp((x - px) * TURN_GAIN, -0.5, 0.5), clamp((y - py) * TURN_GAIN * 0.8, -0.4, 0.4), 0));
            else out.add(new Msg("move", x, y, 0));
            vx = 0.5 * vx + 0.5 * (x - lastX) / dt;
            vy = 0.5 * vy + 0.5 * (y - lastY) / dt;
            lastX = x; lastY = y;
            px = x; py = y;
        } else { vx *= 0.6; vy *= 0.6; }
        if (holding) { // (the stage turns it by each step's change of the wrist's turn)
            double now = roll(a, 1, 1), r = now - rollLast;
            if (r > Math.PI) r -= 2 * Math.PI;
            if (r < -Math.PI) r += 2 * Math.PI;
            if (Math.abs(r) > 0.05) { out.add(new Msg("rot", x, y, clamp(r, -0.6, 0.6))); rollLast = now; }
        }
    }

    private void reset(long ms) {
        if (down || fistLive || two) busyUntil = ms + AFTER_MS;
        down = false; holding = false; turning = false; two = false; fisting = false; fistLive = false; victorySince = 0;
        label = "";
    }

    /** Thumb and forefinger together, measured against the hand's own size (near or far from the camera). */
    static boolean pinched(HandSigns.Hand h, boolean was) {
        if (h.pts == null || h.pts.length < 42) return false;
        double size = Math.hypot(h.pts[0] - h.pts[18], h.pts[1] - h.pts[19]); // wrist → middle knuckle
        if (size < 1e-4) return false;
        double gap = Math.hypot(h.pts[8] - h.pts[16], h.pts[9] - h.pts[17]) / size; // thumb tip (4) → forefinger tip (8)
        return was ? gap < OPEN : gap < CLOSE;
    }

    /** Between the thumb's and the forefinger's tips (0..1 of the picture). */
    static double[] tip(HandSigns.Hand h) {
        if (h.pts == null || h.pts.length < 42) return new double[]{0.5, 0.5};
        return new double[]{(h.pts[8] + h.pts[16]) / 2, (h.pts[9] + h.pts[17]) / 2};
    }

    /** The hand's turn in the picture: forefinger knuckle (5) → little-finger knuckle (17). */
    static double roll(HandSigns.Hand h, double ax, double ay) {
        if (h.pts == null || h.pts.length < 42) return 0;
        return Math.atan2((h.pts[35] - h.pts[11]) * ay, (h.pts[34] - h.pts[10]) * ax);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
