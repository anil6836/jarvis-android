package com.anil.jarvis;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The moving background of a main-screen look (our own drawings): 🛸 a glowing HUD floor with the projector's light up to
 * the orb, 💎 slow liquid colours, 🪐 stars, a planet's edge and orbits round the orb. The orb itself is the real Jarvis
 * orb on top (never drawn or recoloured here). It moves about 30 times a second only while on the screen and motion is
 * allowed (battery saver / his switch stop it: one still picture); his voice makes the light a little brighter, and a
 * tilt of the phone moves the layers a little (parallax), both only when he leaves those switches on.
 */
final class LookBg extends Drawable {
    final String look;
    /** Motion allowed now. */
    boolean animate = true;
    private float level, tiltX, tiltY;
    /** The orb's centre and radius on this background (the layout says where it sits). */
    private float ax = -1, ay = -1, ar;
    private float w, h;
    private final long born = android.os.SystemClock.uptimeMillis();
    private boolean ticking;

    private final Paint base = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), cone = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] blobs = new Paint[4];
    private final Matrix m = new Matrix();
    private final RectF oval = new RectF();
    private final Path path = new Path();
    private float[] sx = new float[0], sy = new float[0], ss = new float[0], sp = new float[0];
    // made once per size / orb place (moved with a matrix each frame, not made again)
    private Shader coneSh, padSh, neb1, neb2, planet;
    private float shAx = -9, shAy = -9, shAr = -9;
    private final Matrix m2 = new Matrix();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking || !animate) { ticking = false; return; }
            invalidateSelf();
            scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 33);
        }
    };

    LookBg(String look) { this.look = look; }

    void setAnchor(float x, float y, float r) {
        if (Math.abs(x - ax) < 0.5f && Math.abs(y - ay) < 0.5f && Math.abs(r - ar) < 0.5f) return;
        ax = x; ay = y; ar = r;
        invalidateSelf();
    }

    /** His voice / Jarvis's voice level (0..1). */
    void setLevel(float l) { level = Math.max(level * 0.85f, Math.max(0, Math.min(1, l))); }

    /** The phone's tilt (-1..1 each way). */
    void setTilt(float x, float y) { tiltX = x; tiltY = y; }

    @Override protected void onBoundsChange(Rect b) {
        w = b.width();
        h = b.height();
        if (w <= 0 || h <= 0) return;
        shAx = -9; // (the orb's light is made again for this size)
        if (MainLook.HOLO.equals(look)) {
            base.setShader(new LinearGradient(0, 0, 0, h, 0xFF06142A, 0xFF01050C, Shader.TileMode.CLAMP));
        } else if (MainLook.LIQUID.equals(look)) {
            base.setShader(new LinearGradient(0, 0, w, h, 0xFF1C0838, 0xFF081C34, Shader.TileMode.CLAMP));
            int[] cols = {0xFFFF3D9A, 0xFF7B3CFF, 0xFF16C2C2, 0xFFFF8A3C};
            float d = Math.max(w, h);
            for (int i = 0; i < 4; i++) {
                blobs[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                blobs[i].setShader(new RadialGradient(0, 0, d * (i == 3 ? 0.42f : 0.55f), PageTheme.withAlpha(cols[i], i == 3 ? 0x99 : 0xCC),
                        PageTheme.withAlpha(cols[i], 0), Shader.TileMode.CLAMP));
            }
        } else {
            base.setShader(new LinearGradient(0, 0, 0, h, 0xFF0D0A2A, 0xFF02010A, Shader.TileMode.CLAMP));
            neb1 = new RadialGradient(w * 0.85f, h * 0.2f, w * 0.7f, 0x406A3CFF, 0, Shader.TileMode.CLAMP);
            neb2 = new RadialGradient(w * 0.1f, h * 0.55f, w * 0.7f, 0x332F6BFF, 0, Shader.TileMode.CLAMP);
            float pr = h * 0.75f;
            planet = new RadialGradient(w / 2, h * 0.66f + pr, pr * 1.08f, new int[]{0xFF04030C, 0xFF04030C, 0x664F7BFF, 0}, new float[]{0, 0.9f, 0.93f, 1f}, Shader.TileMode.CLAMP);
            java.util.Random r = new java.util.Random(11);
            int n = 160;
            sx = new float[n]; sy = new float[n]; ss = new float[n]; sp = new float[n];
            for (int i = 0; i < n; i++) {
                sx[i] = r.nextFloat() * w; sy[i] = r.nextFloat() * h;
                ss[i] = (r.nextFloat() < .12f ? 1.6f : 0.8f) * Math.max(1, w / 540f);
                sp[i] = r.nextFloat() * 6.28f;
            }
        }
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, base);
        if (w <= 0 || h <= 0) return;
        float t = (android.os.SystemClock.uptimeMillis() - born) / 1000f;
        if (!animate) t = 4f;
        level *= 0.9f; // (the voice's light fades when the voice stops)
        boolean orbOn = ar > 0; // (the stage hidden: in the other tabs, while he types: no light to an orb that isn't there)
        float cx = ax >= 0 ? ax : w / 2, cy = ay >= 0 ? ay : h * 0.2f, r = ar > 0 ? ar : Math.min(w, h) * 0.18f;
        if (MainLook.HOLO.equals(look)) holo(c, t, cx, cy, r, orbOn);
        else if (MainLook.LIQUID.equals(look)) liquid(c, t);
        else orbit(c, t, cx, cy, r, orbOn);
        if (animate && !ticking && isVisible()) { ticking = true; scheduleSelf(tick, android.os.SystemClock.uptimeMillis() + 33); }
    }

    // ---- 🛸 the HUD floor, the projector's light, rings on the pad, motes rising
    private void holo(Canvas c, float t, float cx, float cy, float r, boolean orbOn) {
        float floor = orbOn ? Math.min(h * 0.92f, cy + r * 2.65f) : h * 0.3f;
        float px = -tiltX * w * 0.015f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, w / 540f));
        line.setColor(0x2622D3EE);
        for (float x = w * 0.08f; x < w; x += w * 0.12f) c.drawLine(x + px, h * 0.18f, x + px, floor, line); // the wall's faint lines
        for (int k = 0; k < 14; k++) { // the floor: lines nearer apart towards the horizon
            float y = floor + (float) Math.pow(k / 13f, 1.8f) * (h - floor);
            line.setColor(PageTheme.withAlpha(0xFF22D3EE, 0x50 - k * 2));
            c.drawLine(0, y, w, y, line);
        }
        line.setColor(0x3022D3EE);
        for (int k = -10; k <= 10; k++) c.drawLine(cx + k * w * 0.03f + px, floor, cx + k * w * 0.32f + px * 2, h, line);
        line.setColor(0x8822D3EE);
        line.setStrokeWidth(Math.max(1.5f, w / 360f));
        c.drawLine(0, floor, w, floor, line); // the horizon line
        if (!orbOn) return;
        // the projector's light from the pad up to the orb
        float boost = 0.55f + 0.45f * level;
        if (shAx != cx || shAy != cy || shAr != r || coneSh == null) {
            shAx = cx; shAy = cy; shAr = r;
            coneSh = new LinearGradient(0, floor, 0, cy, 0x7022D3EE, 0, Shader.TileMode.CLAMP);
            padSh = new RadialGradient(cx, floor, r * 1.4f, 0x905FE9FF, 0, Shader.TileMode.CLAMP);
        }
        cone.setShader(coneSh);
        cone.setAlpha(Math.round(255 * boost));
        path.reset();
        path.moveTo(cx - r * 1.25f, floor);
        path.lineTo(cx + r * 1.25f, floor);
        path.lineTo(cx + r * 0.75f, cy);
        path.lineTo(cx - r * 0.75f, cy);
        path.close();
        c.drawPath(path, cone);
        // rings on the pad, their dashes turning
        ring.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float rx = r * (1.05f + i * 0.42f), ry = rx * 0.2f;
            oval.set(cx - rx, floor - ry, cx + rx, floor + ry);
            ring.setStrokeWidth(Math.max(1.5f, w / (i == 0 ? 220f : 400f)));
            ring.setColor(PageTheme.withAlpha(0xFF5FE9FF, i == 0 ? 0xDD : 0x77 - i * 0x14));
            ring.setPathEffect(i == 1 ? new DashPathEffect(new float[]{w / 60f, w / 90f}, t * 40) : i == 2 ? new DashPathEffect(new float[]{w / 120f, w / 50f}, -t * 25) : null);
            c.drawOval(oval, ring);
        }
        ring.setPathEffect(null);
        glow.setShader(padSh);
        glow.setAlpha(Math.round(255 * boost));
        oval.set(cx - r * 1.4f, floor - r * 0.3f, cx + r * 1.4f, floor + r * 0.3f);
        c.drawOval(oval, glow);
        glow.setAlpha(255);
        dot.setColor(0xCC9FF4FF); // motes rising in the light
        for (int i = 0; i < 16; i++) {
            float ph = (t * (0.08f + (i % 5) * 0.02f) + i * 0.137f) % 1f;
            float x = cx + (float) Math.sin(i * 2.4 + t * 0.6) * r * 0.9f * (1 - ph * 0.4f);
            float y = floor - ph * (floor - cy);
            dot.setAlpha(Math.round(200 * (1 - ph)));
            c.drawCircle(x, y, Math.max(1, w / 500f) * (1 + i % 2), dot);
        }
    }

    // ---- 💎 slow liquid colours (four soft lights wandering)
    private void liquid(Canvas c, float t) {
        float[][] path = {{.2f, .25f, .23f, .17f}, {.8f, .4f, .19f, .21f}, {.3f, .78f, .21f, .15f}, {.75f, .9f, .25f, .19f}};
        for (int i = 0; i < 4; i++) {
            if (blobs[i] == null) continue;
            float x = w * (path[i][0] + 0.18f * (float) Math.sin(t * path[i][2] + i)) - tiltX * w * 0.03f;
            float y = h * (path[i][1] + 0.12f * (float) Math.cos(t * path[i][3] + i * 1.7f)) - tiltY * h * 0.02f;
            m.setTranslate(x, y);
            float s = 1 + 0.08f * level;
            m.preScale(s, s);
            blobs[i].getShader().setLocalMatrix(m);
            c.drawRect(getBounds(), blobs[i]);
        }
    }

    // ---- 🪐 stars, a planet's edge below, orbits round the orb
    private void orbit(Canvas c, float t, float cx, float cy, float r, boolean orbOn) {
        float px = -tiltX * w * 0.02f, py = -tiltY * h * 0.012f;
        m2.setTranslate(px, 0);
        if (neb1 != null) { neb1.setLocalMatrix(m2); glow.setShader(neb1); c.drawRect(getBounds(), glow); }
        if (neb2 != null) { neb2.setLocalMatrix(m2); glow.setShader(neb2); c.drawRect(getBounds(), glow); }
        for (int i = 0; i < sx.length; i++) {
            float tw = animate ? 0.55f + 0.45f * (float) Math.sin(t * 1.3f + sp[i]) : 0.8f;
            dot.setColor(0xFFFFFFFF);
            dot.setAlpha(Math.round(220 * tw));
            c.drawCircle(sx[i] + px * (ss[i] > 1.2f ? 1.6f : 1), sy[i] + py, ss[i], dot);
        }
        // the planet's lit edge, low on the screen
        float pr = h * 0.75f, pcx = w / 2 + px * 0.5f, pcy = h * 0.66f + pr;
        if (planet != null) { m2.setTranslate(px * 0.5f, 0); planet.setLocalMatrix(m2); glow.setShader(planet); c.drawCircle(pcx, pcy, pr * 1.08f, glow); }
        ring.setStyle(Paint.Style.STROKE);
        ring.setPathEffect(null);
        ring.setStrokeWidth(Math.max(2, w / 200f));
        ring.setColor(0xCC9FC0FF);
        c.drawCircle(pcx, pcy, pr, ring);
        if (!orbOn) return;
        // orbits round the orb, a light on each going round
        float boost = 0.6f + 0.4f * level;
        float[][] orbs = {{2.15f, 0.62f, -14f, 0.25f}, {2.7f, 0.82f, 9f, -0.17f}};
        for (float[] o : orbs) {
            c.save();
            c.rotate(o[2], cx, cy);
            float rx = r * o[0], ry = r * o[1];
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            ring.setStrokeWidth(Math.max(1, w / 450f));
            ring.setColor(PageTheme.withAlpha(0xFFA8B4FF, Math.round(0x60 * boost)));
            c.drawOval(oval, ring);
            double a = t * o[3] * 6.28;
            dot.setColor(0xFFE0E6FF);
            dot.setAlpha(230);
            c.drawCircle(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry, Math.max(2, w / 220f), dot);
            c.restore();
        }
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) { ticking = false; unscheduleSelf(tick); }
        else if (animate && !ticking) invalidateSelf();
        return changed;
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
