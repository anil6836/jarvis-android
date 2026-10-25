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
