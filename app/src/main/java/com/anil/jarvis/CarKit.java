package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 🎛️ The 3D shapes' own parts (① ② ③, our own designs): a card that leans (a real perspective; a finger's touch is
 * taken back through it, so every button works exactly where it is seen), ②'s chrome dials, and the chrome round
 * buttons.
 */
final class CarKit {
    private CarKit() {}

    /** Chrome: light and dark bands round the ring, like polished metal under a roof light. */
    private static final int[] CHROME = {0xFFF4F7FA, 0xFF8C96A1, 0xFFE9EEF3, 0xFF4E5761, 0xFFDCE3EA, 0xFF7D8894, 0xFFF4F7FA};

    // ================================================================ a leaning card

    /**
     * Its child drawn through a perspective ({@link CarDeck#lean}: a wing leaning in, the middle card leaning back);
     * touches are mapped back through the same perspective. Flat (no change at all) when the amount is 0.
     */
    static final class Tilt extends FrameLayout {
        private int kind = CarDeck.FLAT;
        private float amt;
        private final Matrix m = new Matrix();
        private float[] fwd, inv;

        Tilt(Context c) { super(c); }

        /** The perspective its child is drawn through (9 numbers), or null when flat. */
        float[] forward() { return fwd; }

        void setTilt(int kind, float amt) {
            if (kind == this.kind && amt == this.amt) return;
            this.kind = kind;
            this.amt = amt;
            remake(getWidth(), getHeight());
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            remake(w, h);
        }

        private void remake(int w, int h) {
            fwd = inv = null;
            if (w <= 0 || h <= 0 || amt <= 0 || kind == CarDeck.FLAT) return;
            float[] f = CarDeck.persp(w, h, CarDeck.lean(kind, w, h, amt));
            float[] i = CarDeck.invert(f);
            if (f == null || i == null) return;
            fwd = f;
            inv = i;
            m.setValues(f);
        }

        @Override protected void dispatchDraw(Canvas c) {
            if (fwd == null) { super.dispatchDraw(c); return; }
            int s = c.save();
            c.concat(m);
            super.dispatchDraw(c);
            c.restoreToCount(s);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            if (inv == null) return super.dispatchTouchEvent(ev);
            MotionEvent e = mapped(ev);
            try { return super.dispatchTouchEvent(e); } finally { e.recycle(); }
        }

        /** The same touch with every finger's spot taken back to the flat card (our own math: no guess about the system's). */
        private MotionEvent mapped(MotionEvent ev) {
            int n = ev.getPointerCount();
            MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
            MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
            for (int i = 0; i < n; i++) {
                pp[i] = new MotionEvent.PointerProperties();
                ev.getPointerProperties(i, pp[i]);
                pc[i] = new MotionEvent.PointerCoords();
                ev.getPointerCoords(i, pc[i]);
                float[] q = CarDeck.apply(inv, pc[i].x, pc[i].y);
                pc[i].x = q[0];
                pc[i].y = q[1];
            }
            return MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), ev.getAction(), n, pp, pc, ev.getMetaState(), ev.getButtonState(),
                    ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(), ev.getEdgeFlags(), ev.getSource(), ev.getFlags());
        }
    }

    // ================================================================ ②'s chrome dial

    /**
     * A round chrome dial: a polished ring, a dark face with a thin lit ring in the theme's colour, the sign on top, the
     * number big in the middle, the words under it (red when it warns). Drawn again only when what it says changes.
     */
    static final class Dial extends View {
        private CarTheme.Look look;
        private String sign = "", value = "–", words = "";
        private boolean warn;
        private float shift; // (−1 … 1: towards the left / right of its room: the three stand in a zigzag)
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader ring, face, shadow;
        private float shCx, shCy, shR;
        private boolean shLight;

        Dial(Context c) {
            super(c);
            t.setTextAlign(Paint.Align.CENTER);
        }

        void setLook(CarTheme.Look l) { look = l; ring = null; invalidate(); }

        void setShift(float s) { if (s != shift) { shift = s; ring = null; invalidate(); } }

        /** Where its circle is (the zigzag moves it): centre x, centre y, radius, in its own coordinates. */
        float[] circle() {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.47f;
            return new float[]{w / 2f + shift * Math.max(0, w / 2f - r), h / 2f, r};
        }

        /** {sign, number, words, "!" when it warns} (CarDeck.dial). */
        void set(String[] v) {
            if (v == null || v.length < 4) return;
            boolean w = "!".equals(v[3]);
            if (v[0].equals(sign) && v[1].equals(value) && v[2].equals(words) && w == warn) return;
            sign = v[0]; value = v[1]; words = v[2]; warn = w;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (look == null) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(w, h) * 0.47f;
            float room = Math.max(0, w / 2f - r);
            float cx = w / 2f + shift * room, cy = h / 2f;
            if (ring == null || shCx != cx || shCy != cy || shR != r || shLight != look.light) {
                shCx = cx; shCy = cy; shR = r; shLight = look.light;
                ring = new SweepGradient(cx, cy, CHROME, null);
                face = new RadialGradient(cx, cy - r * 0.35f, r * 1.1f, new int[]{look.light ? 0xFF3A4350 : 0xFF232A33, 0xFF06090D}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                shadow = new RadialGradient(cx, cy + r * 0.08f, r * 1.12f, new int[]{0x88000000, 0x00000000}, new float[]{0.82f, 1}, Shader.TileMode.CLAMP);
            }
            int warnCol = 0xFFFF6B5E;
            p.setStyle(Paint.Style.FILL);
            p.setShader(shadow);
            cv.drawCircle(cx, cy + r * 0.08f, r * 1.12f, p);
            p.setShader(ring);
            cv.drawCircle(cx, cy, r, p);
            p.setShader(face);
            cv.drawCircle(cx, cy, r * 0.86f, p);
            p.setShader(null);
            // the lit ring inside (a glow, then the line)
            p.setStyle(Paint.Style.STROKE);
            int lit = warn ? warnCol : look.accent;
            p.setStrokeWidth(r * 0.07f);
            p.setColor(CarTheme.withAlpha(lit, 0x33));
            cv.drawCircle(cx, cy, r * 0.79f, p);
            p.setStrokeWidth(Math.max(1, r * 0.022f));
            p.setColor(CarTheme.withAlpha(lit, 0xCC));
            cv.drawCircle(cx, cy, r * 0.79f, p);
            p.setStyle(Paint.Style.FILL);
            // the sign, the number, the words
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(0xFFFFFFFF);
            t.setTextSize(r * 0.28f);
            cv.drawText(sign, cx, cy - r * 0.30f, t);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            fit(value, r * 0.44f, r * 1.25f);
            t.setColor(warn ? warnCol : 0xFFF2F6FA);
            cv.drawText(value, cx, cy + r * 0.16f, t);
            t.setTypeface(Typeface.DEFAULT);
            fit(words, r * 0.15f, r * 1.3f);
            t.setColor(warn ? warnCol : 0xFFB9C6D2);
            cv.drawText(words, cx, cy + r * 0.45f, t);
        }

        private void fit(String s, float size, float maxW) {
            t.setTextSize(size);
            float tw = t.measureText(s);
            if (tw > maxW && tw > 0) t.setTextSize(size * maxW / tw);
        }
    }

    // ================================================================ 🎚️ the song strip's equalizer

    /**
     * Colourful bars behind the song strip, from the bass (left) to the treble, with the music (CarEq: what the car
     * speakers play). Each bar its own colour, the rainbow drifting slowly; a light cap on each peak. Calm (fainter) while
     * the car moves; nothing at all when off or when no song is heard (never made up).
     */
    static final class Spectrum extends Drawable implements Runnable {
        private final CarEq eq;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = new float[3];
        private final android.graphics.RectF r = new android.graphics.RectF();
        private boolean on = true, calm, running;
        private float reach = 0.85f, hue, left;
        private long last;

        Spectrum(CarEq eq) {
            this.eq = eq;
            eq.onData = this::kick;
        }

        void setOn(boolean b) { if (b != on) { on = b; invalidateSelf(); } }
        void setCalm(boolean b) { calm = b; }
        /** How high the bars may reach (part of the strip's height), and where they start (the song's picture covers the left). */
        void setReach(float f, float leftPx) { reach = f; left = leftPx; invalidateSelf(); }

        private void kick() {
            if (running || !on) return;
            running = true;
            invalidateSelf();
        }

        @Override public void run() { invalidateSelf(); }

        @Override public void draw(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.03f : Math.min(0.2f, (now - last) / 1000f);
            last = now;
            eq.tick(now);
            if (!on) { running = false; return; }
            Rect b = getBounds();
            float pad = Math.max(2, b.height() * 0.08f);
            float x0 = b.left + Math.max(pad, left), x1 = b.right - pad, base = b.bottom - pad, hgt = (b.height() - pad * 2) * reach;
            int n = CarEq.BARS;
            float slot = (x1 - x0) / n, bw = slot * 0.66f;
            if (slot <= 1 || hgt <= 2) { running = false; return; }
            hue = (hue + dt * 14) % 360; // (the rainbow drifts slowly)
            int a = calm ? 0x48 : 0x8C, capA = calm ? 0x70 : 0xD8;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float lv = eq.level[i], pk = eq.peak[i];
                float x = x0 + slot * i + (slot - bw) / 2;
                hsv[0] = (hue + i * (300f / n)) % 360; hsv[1] = 0.78f; hsv[2] = 1f;
                if (lv > 0.01f) {
                    p.setColor(android.graphics.Color.HSVToColor(a, hsv));
                    r.set(x, base - Math.max(bw * 0.5f, hgt * lv), x + bw, base);
                    c.drawRoundRect(r, bw * 0.35f, bw * 0.35f, p);
                }
                if (pk > 0.02f) {
                    hsv[1] = 0.25f;
                    p.setColor(android.graphics.Color.HSVToColor(capA, hsv));
                    float y = base - hgt * pk - bw * 0.45f;
                    r.set(x, y, x + bw, y + Math.max(1.5f, bw * 0.28f));
                    c.drawRoundRect(r, bw * 0.14f, bw * 0.14f, p);
                }
            }
            if (eq.alive(now)) scheduleSelf(this, now + 33); // (~30 frames a second while there is music)
            else running = false;
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ================================================================ 💡 beat lights

    /**
     * Parked with a song: the cards' edges light up on every beat, each card its own colour, the colours moving on with
     * each beat, then fading — over the whole screen, taking no touches. Leaning cards' edges follow their lean.
     */
    static final class BeatLights extends View implements Runnable {
        /** The cards' outlines now, in this view's own coordinates (one path each). */
        interface Shapes { void outlines(java.util.List<android.graphics.Path> into); }

        private final Shapes shapes;
        private final java.util.List<android.graphics.Path> paths = new java.util.ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = {0, 0.8f, 1};
        private final float d;
        private boolean active;
        private float pulse, hue;
        private long last;

        BeatLights(Context c, Shapes shapes) {
            super(c);
            this.shapes = shapes;
            d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setActive(boolean a) {
            if (a == active) return;
            active = a;
            removeCallbacks(this);
            invalidate();
        }

        /** A beat: full light, the next colours. */
        void beat() {
            if (!active) return;
            pulse = 1;
            hue = (hue + 47) % 360;
            removeCallbacks(this);
            invalidate();
        }

        @Override public void run() { invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (!active) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.03f : Math.min(0.2f, (now - last) / 1000f);
            last = now;
            pulse = Math.max(0, pulse - dt * 2.8f); // (gone in about a third of a second)
            float glow = 0.16f + 0.84f * pulse;
            paths.clear();
            shapes.outlines(paths);
            for (int i = 0; i < paths.size(); i++) {
                hsv[0] = (hue + i * 29) % 360;
                hsv[1] = 0.8f;
                p.setStrokeWidth(9 * d);
                p.setColor(android.graphics.Color.HSVToColor(Math.round(255 * 0.30f * glow), hsv));
                c.drawPath(paths.get(i), p);
                hsv[1] = 0.55f;
                p.setStrokeWidth(2.2f * d);
                p.setColor(android.graphics.Color.HSVToColor(Math.round(255 * 0.95f * glow), hsv));
                c.drawPath(paths.get(i), p);
            }
            postOnAnimationDelayed(this, 33);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(this);
        }
    }

    // ================================================================ ⑧'s light

    /** ⑧: a soft cone of the theme's colour from the top onto Jarvis, and a lit floor under him. */
    static Drawable spotlight(final CarTheme.Look l) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path cone = new android.graphics.Path();
            private Shader coneSh, floorSh;
            private Rect made;

            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float w = b.width(), h = b.height();
                if (w <= 0 || h <= 0) return;
                float cx = b.exactCenterX(), top = b.top, floorY = top + h * 0.86f;
                if (made == null || !made.equals(b)) {
                    made = new Rect(b);
                    cone.rewind();
                    cone.moveTo(cx - w * 0.07f, top); cone.lineTo(cx + w * 0.07f, top); cone.lineTo(cx + w * 0.40f, floorY); cone.lineTo(cx - w * 0.40f, floorY); cone.close();
                    coneSh = new android.graphics.LinearGradient(0, top, 0, floorY, new int[]{CarTheme.withAlpha(l.accent, 0x50), CarTheme.withAlpha(l.accent, 0x0A)}, null, Shader.TileMode.CLAMP);
                    floorSh = new RadialGradient(cx, floorY, Math.max(1, w * 0.40f), new int[]{CarTheme.withAlpha(l.accent, 0x70), CarTheme.withAlpha(l.accent, 0x00)}, null, Shader.TileMode.CLAMP);
                }
                p.setStyle(Paint.Style.FILL);
                p.setShader(coneSh);
                cv.drawPath(cone, p);
                int s = cv.save();
                cv.scale(1, 0.16f, cx, floorY);
                p.setShader(floorSh);
                cv.drawCircle(cx, floorY, w * 0.40f, p);
                cv.restoreToCount(s);
                p.setShader(null);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    // ================================================================ the chrome round button (behind a tile's sign)

    /**
     * A round chrome button in the top part of a tile (topFrac of its height: the sign sits there, its word under it):
     * a soft shadow, the polished ring, a dark face, a thin ring in the theme's colour.
     */
    static Drawable chromeButton(final CarTheme.Look l, final float topFrac) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader ring, face, shadow;
            private Rect made;

            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                float top = b.height() * topFrac;
                float r = Math.min(b.width() * 0.47f, top * 0.47f);
                float cx = b.exactCenterX(), cy = b.top + top / 2f;
                if (made == null || !made.equals(b)) {
                    made = new Rect(b);
                    ring = new SweepGradient(cx, cy, CHROME, null);
                    face = new RadialGradient(cx, cy - r * 0.35f, r * 1.1f, new int[]{l.light ? 0xFF3A4350 : 0xFF252C35, 0xFF080B10}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                    shadow = new RadialGradient(cx, cy + r * 0.1f, r * 1.15f, new int[]{0x99000000, 0x00000000}, new float[]{0.8f, 1}, Shader.TileMode.CLAMP);
                }
                p.setStyle(Paint.Style.FILL);
                p.setShader(shadow);
                cv.drawCircle(cx, cy + r * 0.1f, r * 1.15f, p);
                p.setShader(ring);
                cv.drawCircle(cx, cy, r, p);
                p.setShader(face);
                cv.drawCircle(cx, cy, r * 0.84f, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, r * 0.03f));
                p.setColor(CarTheme.withAlpha(l.accent, 0x99));
                cv.drawCircle(cx, cy, r * 0.84f, p);
                p.setStrokeWidth(Math.max(1, r * 0.02f));
                p.setColor(0x55FFFFFF); // (a bright edge on top of the face: depth)
                cv.drawArc(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f, 200, 140, false, p);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
