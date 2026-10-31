package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * ✨ The new looks' own drawing (31 Oct; his pictures' styles, our own designs): the cards of ⑰ liquid glass (pastel glass,
 * a rainbow edge), ⑱ dark glass (navy, a lit edge, a glow line under the dock's buttons), ⑲ soft white (raised cards
 * with a light and a dark shadow) and ⑳ glow tiles (white tiles, coloured light under them); the 🎙️ orb that shows what
 * Jarvis is doing (listening: rings, thinking: a turning ring, talking: a wave, resting: a grey mic); ⑳'s day / night
 * switch; ⑲'s week; the covers of the new players (the cover's glow with two more beside it, or a stack with the songs
 * that come next). Everything drawn in code, nothing copied: no logos, no album covers but his own song's picture.
 */
final class CarGlass {
    private CarGlass() {}

    /** A rainbow for ⑰'s edges: pink, lilac, sky, mint. */
    static final int[] RAINBOW = {0xFFFF9AD5, 0xFFB69CFF, 0xFF7FD8FF, 0xFF9BF0D0, 0xFFFF9AD5};
    /** ⑳'s lights under the six (blue, red, green, sky, orange, violet), then round again. */
    static final int[] GLOWS = {0xFF3B82F6, 0xFFEF4444, 0xFF22C55E, 0xFF06B6D4, 0xFFF59E0B, 0xFFA855F7};
    /** ⑲'s second colour (the yellow of his navy-and-yellow pictures). */
    static final int YELLOW = 0xFFF5B800;
    /** ⑳'s navy (the tiles' names, the pills). */
    static final int NAVY = 0xFF141B3C;

    static int glow(int i) { return GLOWS[((i % GLOWS.length) + GLOWS.length) % GLOWS.length]; }

    static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    // ================================================================ the cards

    /** A card frame drawn by hand (any Android), made again only when its size changes. */
    private abstract static class Frame extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), q = new RectF();
        private final Rect made = new Rect();

        abstract void paint(Canvas cv, RectF b, boolean fresh);

        @Override public void draw(Canvas cv) {
            boolean fresh = !made.equals(getBounds());
            if (fresh) made.set(getBounds());
            r.set(getBounds());
            if (r.width() < 4 || r.height() < 4) return;
            paint(cv, r, fresh);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** ⑰: pastel glass, a soft shadow, a rainbow edge and a white light along the top (dark: smoky glass, the same edge). */
    static Drawable liquid(final CarTheme.Look l, final float rad, final boolean tile) {
        return new Frame() {
            private Shader fill, edge, sheen, top;

            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                boolean lt = l.light;
                float in = Math.max(2, rad * 0.12f), rr = Math.min(rad, Math.min(b.width(), b.height()) / 2.2f);
                q.set(b.left + in, b.top + in, b.right - in, b.bottom - in * 1.7f);
                if (fresh || fill == null) {
                    fill = new LinearGradient(q.left, q.top, q.right, q.bottom, lt ? (tile ? new int[]{0xF2FFFFFF, 0xC4FFFFFF} : new int[]{0xD9FFFFFF, 0x9EFFFFFF})
                            : (tile ? new int[]{0x3DFFFFFF, 0x17FFFFFF} : new int[]{0x2EFFFFFF, 0x10FFFFFF}), null, Shader.TileMode.CLAMP);
                    int[] rb = new int[RAINBOW.length];
                    for (int i = 0; i < rb.length; i++) rb[i] = alpha(RAINBOW[i], lt ? 0xD0 : 0xA8);
                    edge = new LinearGradient(q.left, q.top, q.right, q.bottom, rb, null, Shader.TileMode.CLAMP);
                    sheen = new RadialGradient(q.left + q.width() * 0.15f, q.top, Math.max(q.width(), q.height()) * 0.9f,
                            new int[]{alpha(0xFFFFB3E6, lt ? 0x38 : 0x22), alpha(0xFF9FE6FF, lt ? 0x24 : 0x16), 0x00FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
                    top = new LinearGradient(0, q.top, 0, q.top + q.height() * 0.5f, new int[]{lt ? 0xCCFFFFFF : 0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
                }
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                for (int k = 3; k >= 1; k--) { // (a soft shadow: three layers)
                    p.setColor(lt ? alpha(0xFF6B5BB5, 7 * k) : alpha(0xFF000000, 26 * k));
                    cv.drawRoundRect(q.left + k, q.top + in * 0.35f * k, q.right - k, q.bottom + in * 0.35f * k, rr, rr, p);
                }
                p.setShader(fill);
                cv.drawRoundRect(q, rr, rr, p);
                p.setShader(sheen);
                cv.drawRoundRect(q, rr, rr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, rad * 0.075f));
                p.setShader(edge);
                cv.drawRoundRect(q, rr, rr, p);
                p.setStrokeWidth(Math.max(1f, rad * 0.05f));
                p.setShader(top);
                float i2 = Math.max(1.5f, rad * 0.08f);
                cv.drawRoundRect(q.left + i2, q.top + i2, q.right - i2, q.bottom - i2, rr - i2, rr - i2, p);
                p.setShader(null);
            }
        };
    }

    /** ⑱: navy glass with a lit edge and a faint glow round it; a tile has its glow line at the foot. */
    static Drawable navy(final CarTheme.Look l, final float rad, final boolean tile) {
        return new Frame() {
            private Shader fill, line;

            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                float in = Math.max(2, rad * 0.1f), rr = Math.min(rad, Math.min(b.width(), b.height()) / 2.2f);
                q.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
                int a = l.accent;
                if (fresh || fill == null) {
                    fill = new LinearGradient(0, q.top, 0, q.bottom, tile ? new int[]{0xF21B3468, 0xF20D1D44} : new int[]{0xEB142A5E, 0xEB0A1736}, null, Shader.TileMode.CLAMP);
                    line = new LinearGradient(q.centerX() - q.width() * 0.25f, 0, q.centerX() + q.width() * 0.25f, 0, new int[]{alpha(a, 0), a, alpha(a, 0)}, null, Shader.TileMode.CLAMP);
                }
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(in * 1.6f);
                p.setColor(alpha(a, 0x26));
                cv.drawRoundRect(q, rr, rr, p); // (the glow round it)
                p.setStyle(Paint.Style.FILL);
                p.setShader(fill);
                cv.drawRoundRect(q, rr, rr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                p.setColor(alpha(0xFF5B9BFF, 0x80));
                cv.drawRoundRect(q, rr, rr, p);
                p.setColor(0x40FFFFFF);
                cv.drawLine(q.left + rr, q.top + 1.5f, q.right - rr, q.top + 1.5f, p);
                if (tile) { // (the dock's button: a lit line at its foot)
                    p.setShader(line);
                    p.setStrokeWidth(Math.max(2.5f, q.height() * 0.035f));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    float y = q.bottom - Math.max(3, q.height() * 0.06f);
                    cv.drawLine(q.centerX() - q.width() * 0.25f, y, q.centerX() + q.width() * 0.25f, y, p);
                    p.setShader(null);
                }
            }
        };
    }

    /** ⑲: a raised soft card (the screen's own colour, a light shadow up-left, a dark one down-right). */
    static Drawable soft(final CarTheme.Look l, final float rad, final boolean tile) {
        return new Frame() {
            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                boolean lt = l.light;
                float d = Math.max(1.5f, rad * (tile ? 0.13f : 0.17f)), rr = Math.min(rad, Math.min(b.width(), b.height()) / 2.2f);
                q.set(b.left + d * 2.2f, b.top + d * 2.2f, b.right - d * 2.6f, b.bottom - d * 2.6f);
                int base = lt ? 0xFFEAEEF4 : 0xFF1F2534;
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                int[] dark = lt ? new int[]{0x0E, 0x18, 0x26} : new int[]{0x22, 0x40, 0x66};
                int[] light = lt ? new int[]{0x55, 0x99, 0xEE} : new int[]{0x05, 0x08, 0x0C};
                for (int k = 3; k >= 1; k--) {
                    float o = d * k * 0.75f;
                    p.setColor(alpha(lt ? 0xFF8E9BB3 : 0xFF000000, dark[3 - k]));
                    cv.drawRoundRect(q.left + o, q.top + o, q.right + o, q.bottom + o, rr, rr, p);
                    p.setColor(alpha(0xFFFFFFFF, light[3 - k]));
                    cv.drawRoundRect(q.left - o * 0.8f, q.top - o * 0.8f, q.right - o * 0.8f, q.bottom - o * 0.8f, rr, rr, p);
                }
                p.setColor(base);
                cv.drawRoundRect(q, rr, rr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1f);
                p.setColor(lt ? 0x66FFFFFF : 0x14FFFFFF);
                cv.drawRoundRect(q, rr, rr, p);
            }
        };
    }

    /** ⑳: a white tile (navy at night) with its coloured light glowing on the floor under it; a button has its colour line too. */
    static Drawable tile(final CarTheme.Look l, final float rad, final boolean button, final int glow) {
        return new Frame() {
            private Shader under;

            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                boolean lt = l.light;
                float gh = Math.max(4, b.height() * 0.12f), rr = Math.min(rad, Math.min(b.width(), b.height()) / 2.2f);
                q.set(b.left + 2, b.top + 2, b.right - 2, b.bottom - gh * 0.6f);
                if (fresh || under == null)
                    under = new RadialGradient(q.centerX(), q.bottom, Math.max(4, q.width() * 0.48f), new int[]{alpha(glow, 0xB0), alpha(glow, 0x30), alpha(glow, 0)},
                            new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
                cv.save(); // (the light under it: a flat oval on the floor)
                cv.scale(1f, 0.22f, q.centerX(), q.bottom);
                p.setShader(under);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(q.centerX(), q.bottom, Math.max(4, q.width() * 0.48f), p);
                cv.restore();
                p.setShader(null);
                p.setColor(lt ? 0xFFF7F9FD : 0xFF161D31);
                cv.drawRoundRect(q, rr, rr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(lt ? 0x1A0B1530 : alpha(glow, 0x55));
                cv.drawRoundRect(q, rr, rr, p);
                if (button) {
                    p.setColor(glow);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(Math.max(3, q.height() * 0.03f));
                    float y = q.bottom - Math.max(6, q.height() * 0.08f);
                    cv.drawLine(q.centerX() - q.width() * 0.17f, y, q.centerX() + q.width() * 0.17f, y, p);
                }
            }
        };
    }

    /** A pill (⑳'s names on the tiles, ⑳'s top buttons): a solid colour, round ends. */
    static Drawable pill(final int fill, final int stroke) {
        return new Frame() {
            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                float rr = b.height() / 2f;
                q.set(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(fill);
                cv.drawRoundRect(q, rr, rr, p);
                if (stroke != 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    p.setColor(stroke);
                    cv.drawRoundRect(q, rr, rr, p);
                }
            }
        };
    }

    /** ⑳'s top buttons: a navy pill with its own colour glowing on the floor under it. */
    static Drawable glowPill(final int glow) {
        return new Frame() {
            private Shader under;

            @Override void paint(Canvas cv, RectF b, boolean fresh) {
                float gh = Math.max(4, b.height() * 0.22f);
                q.set(b.left + 2, b.top + 2, b.right - 2, b.bottom - gh);
                float rr = Math.min(q.height() / 2f, q.width() / 2f);
                if (fresh || under == null)
                    under = new RadialGradient(q.centerX(), q.bottom + gh * 0.4f, Math.max(4, q.width() * 0.46f), new int[]{alpha(glow, 0xC0), alpha(glow, 0)}, null, Shader.TileMode.CLAMP);
                cv.save();
                cv.scale(1f, 0.25f, q.centerX(), q.bottom + gh * 0.4f);
                p.setShader(under);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(q.centerX(), q.bottom + gh * 0.4f, Math.max(4, q.width() * 0.46f), p);
                cv.restore();
                p.setShader(null);
                p.setColor(0xF2182040);
                cv.drawRoundRect(q, rr, rr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(0x33FFFFFF);
                cv.drawRoundRect(q, rr, rr, p);
            }
        };
    }

    // ================================================================ 🎙️ the orb

    /** The orb's colour for what Jarvis is doing (OrbView's states). Pure. */
    static int orbColour(int state) {
        switch (state) {
            case OrbView.LISTENING: return 0xFF22B8F0;
            case OrbView.THINKING: return 0xFF8B5CF6;
            case OrbView.SPEAKING: return 0xFF10B981;
            default: return 0xFF5B6478;
        }
    }

    /** The words beside the orb (OrbView's states). Pure. */
    static String orbWords(int state) {
        switch (state) {
            case OrbView.LISTENING: return "వింటున్నాను…";
            case OrbView.THINKING: return "ఆలోచిస్తున్నాను…";
            case OrbView.SPEAKING: return "మాట్లాడుతున్నాను…";
            default: return "Jarvis, మాట్లాడు";
        }
    }

    /**
     * 🎙️ The orb in "Jarvis, మాట్లాడు" (the players' sheet, E): listening — a blue ball with rings going out; thinking — a
     * violet ball with a turning rainbow ring and three dots; talking — a green ball with a moving wave; resting — a grey
     * mic. Moves only while it is on the screen ("fewer movements": it stands still in its colour).
     */
    static final class Orb extends Drawable implements Runnable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF o = new RectF();
        private final long t0 = SystemClock.uptimeMillis();
        private int state = OrbView.IDLE;
        private boolean low, on = true;

        void setState(int s) { if (s == state) return; state = s; invalidateSelf(); }
        int state() { return state; }
        void setLowMotion(boolean b) { low = b; invalidateSelf(); }

        @Override public boolean setVisible(boolean visible, boolean restart) {
            on = visible;
            if (!visible) unscheduleSelf(this);
            else invalidateSelf();
            return super.setVisible(visible, restart);
        }

        @Override public void run() { invalidateSelf(); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), R = Math.min(b.width(), b.height()) / 2f;
            if (R < 3) return;
            float t = low ? 0 : (SystemClock.uptimeMillis() - t0) / 1000f;
            int c = orbColour(state);
            float ball = R * 0.66f;
            p.setStyle(Paint.Style.STROKE);
            p.setShader(null);
            if (state == OrbView.LISTENING) { // (rings going out)
                for (int k = 0; k < 2; k++) {
                    float ph = (t * 0.7f + k * 0.5f) % 1f;
                    p.setStrokeWidth(Math.max(1.5f, R * 0.06f));
                    p.setColor(alpha(c, Math.round((1 - ph) * 0xB0)));
                    cv.drawCircle(cx, cy, ball + (R - ball) * ph, p);
                }
            } else if (state == OrbView.THINKING) { // (a turning rainbow ring)
                cv.save();
                cv.rotate(t * 220f % 360f, cx, cy);
                p.setShader(new SweepGradient(cx, cy, new int[]{0xFFFF7AC6, 0xFFFFD166, 0xFF5EEAD4, 0xFF60A5FA, 0xFFA78BFA, 0xFFFF7AC6}, null));
                p.setStrokeWidth(Math.max(2, R * 0.13f));
                cv.drawCircle(cx, cy, R * 0.86f, p);
                p.setShader(null);
                cv.restore();
            } else if (state == OrbView.SPEAKING) {
                p.setStrokeWidth(Math.max(1.2f, R * 0.04f));
                p.setColor(alpha(c, 0x66));
                cv.drawCircle(cx, cy, R * 0.9f, p);
            }
            p.setStyle(Paint.Style.FILL);
            float breathe = state == OrbView.IDLE && !low ? 1f + 0.03f * (float) Math.sin(t * 2.1f) : 1f;
            float br = ball * breathe;
            p.setShader(new RadialGradient(cx - br * 0.3f, cy - br * 0.35f, br * 1.4f, new int[]{lighten(c, 0.55f), c, darken(c, 0.55f)}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, br, p);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
            if (state == OrbView.THINKING) { // (three dots)
                for (int i = 0; i < 3; i++) {
                    float k = low ? 1 : 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 4 - i * 0.7f));
                    cv.drawCircle(cx + (i - 1) * br * 0.38f, cy, br * 0.11f * k, p);
                }
            } else if (state == OrbView.SPEAKING) { // (a wave of five bars)
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, br * 0.13f));
                for (int i = 0; i < 5; i++) {
                    float hgt = low ? 0.5f : 0.3f + 0.7f * (float) Math.abs(Math.sin(t * 7 + i * 1.3f));
                    float x = cx + (i - 2) * br * 0.27f, hh = br * 0.55f * hgt * (i == 2 ? 1 : i == 1 || i == 3 ? 0.8f : 0.55f);
                    cv.drawLine(x, cy - hh, x, cy + hh, p);
                }
                p.setStyle(Paint.Style.FILL);
            } else mic(cv, cx, cy, br * 0.5f);
            if (!low && on) scheduleSelf(this, SystemClock.uptimeMillis() + (state == OrbView.IDLE ? 90 : 40));
        }

        private void mic(Canvas cv, float cx, float cy, float s) {
            p.setStyle(Paint.Style.FILL);
            o.set(cx - s * 0.32f, cy - s * 0.95f, cx + s * 0.32f, cy + s * 0.25f);
            cv.drawRoundRect(o, s * 0.32f, s * 0.32f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, s * 0.14f));
            p.setStrokeCap(Paint.Cap.ROUND);
            o.set(cx - s * 0.58f, cy - s * 0.5f, cx + s * 0.58f, cy + s * 0.55f);
            cv.drawArc(o, 10, 160, false, p);
            cv.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.9f, p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return 0xFF000000 | (Math.round(r + (255 - r) * f) << 16) | (Math.round(g + (255 - g) * f) << 8) | Math.round(b + (255 - b) * f);
    }

    static int darken(int c, float f) { return CarTheme.shade(c | 0xFF000000, 1 - f); }

    // ================================================================ ⑳'s day / night switch

    /** ⑳'s switch (his pictures' sun / moon toggle): a tap changes ⑰ ⑲ ⑳ between day and night (only his tap does). */
    static final class DayNight extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF q = new RectF();
        private boolean dark;

        DayNight(Context c) {
            super(c);
            setClickable(true);
            setContentDescription("పగలు / రాత్రి");
        }

        void setDark(boolean d) { if (d != dark) { dark = d; invalidate(); } }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float ph = Math.min(h * 0.86f, w * 0.48f), pw = Math.min(w * 0.94f, ph * 2.1f);
            q.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2);
            float rr = ph / 2;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0D1222);
            cv.drawRoundRect(q, rr, rr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2, ph * 0.05f));
            p.setShader(new LinearGradient(q.left, 0, q.right, 0, new int[]{dark ? 0x66FFA62B : 0xFFFFA62B, dark ? 0xFF3B82F6 : 0x663B82F6}, null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(q, rr, rr, p);
            p.setShader(null);
            float kr = ph * 0.42f, sunX = q.left + rr, moonX = q.right - rr;
            // the sun (bright by day)
            sun(cv, sunX, q.centerY(), kr * 0.42f, dark ? 0x88FFB020 : 0xFFFFB020);
            // the knob on the side that is on
            float kx = dark ? moonX : sunX;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(kx - kr * 0.3f, q.centerY() - kr * 0.3f, kr * 1.3f, dark ? new int[]{0xFF3D5BD6, 0xFF14215C} : new int[]{0xFFFFE7A8, 0xFFFFA62B},
                    null, Shader.TileMode.CLAMP));
            cv.drawCircle(kx, q.centerY(), kr, p);
            p.setShader(null);
            if (dark) moon(cv, moonX, q.centerY(), kr * 0.5f, 0xFFEAF2FF);
            else { sun(cv, sunX, q.centerY(), kr * 0.42f, 0xFFFFFFFF); moon(cv, moonX, q.centerY(), kr * 0.45f, 0x88A9C1FF); }
        }

        private void sun(Canvas cv, float x, float y, float s, int col) {
            p.setShader(null);
            p.setColor(col);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(x, y, s * 0.55f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, s * 0.16f));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                cv.drawLine(x + (float) Math.cos(a) * s * 0.8f, y + (float) Math.sin(a) * s * 0.8f, x + (float) Math.cos(a) * s * 1.15f, y + (float) Math.sin(a) * s * 1.15f, p);
            }
        }

        private void moon(Canvas cv, float x, float y, float s, int col) {
            Path m = new Path();
            m.addCircle(x, y, s, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(x + s * 0.5f, y - s * 0.35f, s * 0.85f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            cv.drawPath(m, p);
        }
    }

    // ================================================================ ⑲'s week

    static final String[] DAYS = {"ఆది", "సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని"};

    /** Five days round today (today in the middle): {day of the week 0 = Sunday, the date}. Pure. */
    static int[][] week(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day);
        c.add(Calendar.DAY_OF_MONTH, -2);
        int[][] out = new int[5][2];
        for (int i = 0; i < 5; i++) {
            out[i][0] = c.get(Calendar.DAY_OF_WEEK) - 1;
            out[i][1] = c.get(Calendar.DAY_OF_MONTH);
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /** ⑲'s week card: five days, today a navy ball with its date in white. */
    static final class Week extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[][] days = new int[0][];
        private int main = 0xFF17232F, soft = 0xFF6B7A90, acc = 0xFF1E2A78;
        private boolean light = true;
        private int dayNow = -1;

        Week(Context c) {
            super(c);
            t.setTextAlign(Paint.Align.CENTER);
        }

        void setColours(int main, int soft, int acc, boolean light) { this.main = main; this.soft = soft; this.acc = acc; this.light = light; invalidate(); }

        /** Today again (each minute: a new day shows itself). */
        void today() {
            Calendar c = Calendar.getInstance();
            int d = c.get(Calendar.DAY_OF_YEAR);
            if (d == dayNow) return;
            dayNow = d;
            days = week(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (days.length == 0) today();
            float w = getWidth(), h = getHeight(), cw = w / 5f;
            float ball = Math.min(cw * 0.36f, h * 0.27f);
            t.setTextSize(Math.max(9, Math.min(h * 0.17f, cw * 0.24f)));
            for (int i = 0; i < days.length; i++) {
                float x = cw * (i + 0.5f);
                t.setColor(soft);
                t.setTypeface(Typeface.DEFAULT);
                cv.drawText(DAYS[days[i][0]], x, h * 0.27f, t);
                float y = h * 0.64f;
                boolean now = i == 2;
                p.setStyle(Paint.Style.FILL);
                if (now) {
                    p.setColor(0x33000000);
                    cv.drawCircle(x + ball * 0.08f, y + ball * 0.12f, ball, p);
                    p.setColor(light ? acc : 0xFFF5C518);
                } else p.setColor(light ? 0xFFF2F5F9 : 0xFF283043);
                cv.drawCircle(x, y, ball, p);
                if (!now) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1f);
                    p.setColor(light ? 0x22000000 : 0x22FFFFFF);
                    cv.drawCircle(x, y, ball, p);
                }
                t.setColor(now ? (light ? 0xFFFFFFFF : 0xFF141925) : main);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                cv.drawText(String.valueOf(days[i][1]), x, y - (t.descent() + t.ascent()) / 2, t);
            }
        }
    }

    // ================================================================ the new players' covers

    /**
     * The player's picture in its new looks (the players' sheet): "cover" — his song's picture in the middle with two more
     * leaning beside it (the same picture, tinted: the app gives no other songs' pictures) and its colour glowing under it;
     * "stack" — the picture on a little stack, the songs that come next beside it (only when his music app tells them).
     * A tap on the picture: the big player; on a song that comes next: that song (parked only — the screen checks).
     */
    static final class Covers extends View {
        interface Tap { void art(); void next(int i); }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF q = new RectF();
        private final Matrix m = new Matrix();
        private Bitmap art;
        private String mode = "cover";
        private int acc = 0xFF5FF3FF, main = 0xFFFFFFFF, soft = 0xFFB9CCDA;
        private boolean light;
        private final List<String[]> next = new ArrayList<>();
        private final List<RectF> nextAt = new ArrayList<>();
        private float splitX;
        private Tap tap;

        Covers(Context c) {
            super(c);
            setClickable(true);
        }

        void setTap(Tap t) { tap = t; }
        void setArt(Bitmap b) { art = b; invalidate(); }
        void setMode(String md) { mode = md; invalidate(); }
        void setColours(int main, int soft, int acc, boolean light) { this.main = main; this.soft = soft; this.acc = acc; this.light = light; invalidate(); }

        /** The songs that come next (title, singer), up to three; empty: none told. */
        void setNext(List<String[]> n) {
            next.clear();
            if (n != null) for (String[] s : n) { if (next.size() >= 3) break; next.add(s); }
            invalidate();
        }

        boolean listShown() { return "stack".equals(mode) && getWidth() > getHeight() * 1.25f; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP && tap != null) {
                if (listShown() && e.getX() > splitX) {
                    for (int i = 0; i < nextAt.size(); i++) if (nextAt.get(i).contains(e.getX(), e.getY())) { tap.next(i); return true; }
                    return true;
                }
                tap.art();
                return true;
            }
            return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w < 8 || h < 8) return;
            if ("stack".equals(mode)) stack(cv, w, h);
            else cover(cv, w, h);
        }

        private void cover(Canvas cv, float w, float h) {
            float s = Math.min(h * 0.86f, w * 0.52f), cx = w / 2f, cy = h * 0.47f;
            float side = s * 0.74f, off = s * 0.5f + side * 0.36f;
            if (off + side * 0.5f > w / 2f) off = w / 2f - side * 0.5f - 2;
            // the glow under it, in its colour
            p.setShader(new RadialGradient(cx, cy + s * 0.5f, s * 0.75f, new int[]{alpha(acc, 0xAA), alpha(acc, 0)}, null, Shader.TileMode.CLAMP));
            cv.save();
            cv.scale(1f, 0.45f, cx, cy + s * 0.5f);
            cv.drawCircle(cx, cy + s * 0.5f, s * 0.75f, p);
            cv.restore();
            p.setShader(null);
            lean(cv, cx - off, cy, side, true, 0xFF3B82F6);
            lean(cv, cx + off, cy, side, false, 0xFFEC4899);
            q.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            picture(cv, q, s * 0.1f, 0, 255);
        }

        /** A picture beside the middle one, leaning back (its near edge taller), tinted. */
        private void lean(Canvas cv, float cx, float cy, float s, boolean left, int tint) {
            float near = s * 0.5f, far = s * 0.38f;
            float xn = left ? cx + s / 2 : cx - s / 2, xf = left ? cx - s / 2 : cx + s / 2;
            Path path = new Path();
            path.moveTo(xf, cy - far);
            path.lineTo(xn, cy - near);
            path.lineTo(xn, cy + near);
            path.lineTo(xf, cy + far);
            path.close();
            p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.08f));
            if (art != null) {
                float[] src = {0, 0, art.getWidth(), 0, art.getWidth(), art.getHeight(), 0, art.getHeight()};
                float[] dst = left ? new float[]{xf, cy - far, xn, cy - near, xn, cy + near, xf, cy + far}
                        : new float[]{xn, cy - near, xf, cy - far, xf, cy + far, xn, cy + near};
                m.reset();
                m.setPolyToPoly(src, 0, dst, 0, 4);
                BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bs.setLocalMatrix(m);
                p.setShader(bs);
                p.setAlpha(170);
                cv.drawPath(path, p);
                p.setShader(null);
                p.setAlpha(255);
                p.setColor(alpha(tint, 0x55));
            } else p.setShader(new LinearGradient(xf, cy - near, xn, cy + near, new int[]{alpha(tint, 0xCC), alpha(acc, 0x66)}, null, Shader.TileMode.CLAMP));
            cv.drawPath(path, p);
            p.setShader(null);
            p.setColor(0x33000000); // (darker away from the light)
            cv.drawPath(path, p);
            p.setPathEffect(null);
        }

        /** His song's picture in a box with round corners (none: a soft colour with a note). */
        private void picture(Canvas cv, RectF box, float rr, int tint, int a) {
            p.setColor(0x55000000);
            cv.drawRoundRect(box.left + rr * 0.15f, box.top + rr * 0.35f, box.right + rr * 0.15f, box.bottom + rr * 0.35f, rr, rr, p);
            if (art != null) {
                BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                m.reset();
                float sc = Math.max(box.width() / art.getWidth(), box.height() / art.getHeight());
                m.postScale(sc, sc);
                m.postTranslate(box.centerX() - art.getWidth() * sc / 2, box.centerY() - art.getHeight() * sc / 2);
                bs.setLocalMatrix(m);
                p.setShader(bs);
                p.setAlpha(a);
                cv.drawRoundRect(box, rr, rr, p);
                p.setShader(null);
                p.setAlpha(255);
                if (tint != 0) { p.setColor(tint); cv.drawRoundRect(box, rr, rr, p); }
            } else {
                p.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, new int[]{lighten(acc, 0.35f), darken(acc, 0.35f)}, null, Shader.TileMode.CLAMP));
                cv.drawRoundRect(box, rr, rr, p);
                p.setShader(null);
                if (tint != 0) { p.setColor(tint); cv.drawRoundRect(box, rr, rr, p); }
                note(cv, box.centerX(), box.centerY(), box.width() * 0.22f, 0xEEFFFFFF);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(0x33FFFFFF);
            cv.drawRoundRect(box, rr, rr, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void note(Canvas cv, float x, float y, float s, int col) {
            p.setColor(col);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(x - s * 0.35f, y + s * 0.55f, s * 0.32f, p);
            cv.drawCircle(x + s * 0.55f, y + s * 0.35f, s * 0.32f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, s * 0.14f));
            cv.drawLine(x - s * 0.08f, y + s * 0.55f, x - s * 0.08f, y - s * 0.7f, p);
            cv.drawLine(x + s * 0.82f, y + s * 0.35f, x + s * 0.82f, y - s * 0.9f, p);
            cv.drawLine(x - s * 0.08f, y - s * 0.7f, x + s * 0.82f, y - s * 0.9f, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void stack(Canvas cv, float w, float h) {
            boolean list = listShown();
            float area = list ? w * 0.52f : w;
            splitX = area;
            float s = Math.min(h * 0.86f, area * 0.8f), cx = area / 2f - s * 0.06f, cy = h / 2f + s * 0.04f;
            for (int k = 2; k >= 1; k--) { // (the stack behind: smaller, up and to the right, darker)
                float ss = s * (1 - 0.07f * k), dx = s * 0.07f * k, dy = -s * 0.05f * k;
                q.set(cx - ss / 2 + dx, cy - ss / 2 + dy, cx + ss / 2 + dx, cy + ss / 2 + dy);
                picture(cv, q, ss * 0.09f, alpha(0xFF000000, 0x55 + 0x22 * k), 255);
            }
            q.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            picture(cv, q, s * 0.09f, 0, 255);
            nextAt.clear();
            if (!list) return;
            float x = area + w * 0.03f, rowH = Math.min(h / 4.2f, (w - x) * 0.32f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(Math.max(9, rowH * 0.3f));
            t.setColor(soft);
            t.setTextAlign(Paint.Align.LEFT);
            float y = h * 0.12f + rowH * 0.3f;
            cv.drawText("తర్వాత", x, y, t);
            y += rowH * 0.25f;
            if (next.isEmpty()) {
                t.setTypeface(Typeface.DEFAULT);
                t.setTextSize(Math.max(9, rowH * 0.24f));
                cv.drawText(ellipsize("ఈ యాప్ తర్వాతి", w - x), x, y + rowH * 0.5f, t);
                cv.drawText(ellipsize("పాటలు చెప్పడం లేదు", w - x), x, y + rowH * 0.85f, t);
                return;
            }
            for (int i = 0; i < next.size(); i++) {
                float top = y + i * rowH;
                float sq = rowH * 0.78f;
                q.set(x, top + (rowH - sq) / 2, x + sq, top + (rowH + sq) / 2);
                p.setShader(new LinearGradient(q.left, q.top, q.right, q.bottom, new int[]{lighten(glow(i + 3), 0.3f), darken(glow(i + 3), 0.3f)}, null, Shader.TileMode.CLAMP));
                cv.drawRoundRect(q, sq * 0.18f, sq * 0.18f, p);
                p.setShader(null);
                note(cv, q.centerX(), q.centerY(), sq * 0.2f, 0xDDFFFFFF);
                float tx = q.right + sq * 0.3f, room = w - tx - 4;
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextSize(Math.max(9, rowH * 0.28f));
                t.setColor(main);
                cv.drawText(ellipsize(next.get(i)[0], room), tx, top + rowH * 0.45f, t);
                t.setTypeface(Typeface.DEFAULT);
                t.setTextSize(Math.max(8, rowH * 0.22f));
                t.setColor(soft);
                cv.drawText(ellipsize(next.get(i)[1], room), tx, top + rowH * 0.78f, t);
                nextAt.add(new RectF(x, top, w, top + rowH));
            }
        }

        private String ellipsize(String s, float room) {
            if (s == null) return "";
            if (t.measureText(s) <= room) return s;
            int n = t.breakText(s, true, Math.max(0, room - t.measureText("…")), null);
            return s.substring(0, Math.max(0, n)) + "…";
        }
    }

    // ================================================================ ⑰'s switches

    /** A small on / off switch (⑰'s card of three: the equalizer, the beat lights, quiet). */
    static final class Switch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF q = new RectF();
        private boolean on, light = true;
        private int acc = 0xFF22B8CF;

        Switch(Context c) { super(c); }

        void set(boolean on, int acc, boolean light) { this.on = on; this.acc = acc; this.light = light; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float th = Math.min(h * 0.7f, w * 0.5f), tw = Math.min(w, th * 1.9f);
            q.set(w - tw, (h - th) / 2, w, (h + th) / 2);
            p.setStyle(Paint.Style.FILL);
            p.setShader(on ? new LinearGradient(q.left, 0, q.right, 0, new int[]{lighten(acc, 0.25f), acc}, null, Shader.TileMode.CLAMP) : null);
            if (!on) p.setColor(light ? 0xFFE3E8F0 : 0xFF2A3245);
            cv.drawRoundRect(q, th / 2, th / 2, p);
            p.setShader(null);
            float kr = th * 0.4f, kx = on ? q.right - th / 2 : q.left + th / 2;
            p.setColor(0x33000000);
            cv.drawCircle(kx + 1, q.centerY() + 1.5f, kr, p);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(kx, q.centerY(), kr, p);
        }
    }
}
