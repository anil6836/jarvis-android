package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/**
 * ✨ The newer looks' drawings (25 Oct; his pictures were the idea, every line here is ours — no picture from anywhere):
 * the eight new speedometers, the eight moving pictures, the HUD and glowing card frames, the turning record and the
 * little waves of the song, the neon car of the welcome, and the map's limit sign with the speed. All of it follows
 * the theme's colours; every moving part rests with "fewer movements".
 */
final class CarLooks {
    private CarLooks() {}

    // ================================================================ 🔢 the numbers' fonts (free fonts, OFL; their licences in assets/fonts)

    private static final java.util.Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** The font for the numbers (null: the phone's own bold). Read once. */
    static Typeface font(Context c, String key) {
        String f = CarStyle.fontFile(key);
        if (f == null || c == null) return null;
        synchronized (fonts) {
            if (fonts.containsKey(f)) return fonts.get(f);
            Typeface t = null;
            try {
                if (f.contains("Orbitron")) { // (a font with a weight dial: its bold)
                    t = new Typeface.Builder(c.getAssets(), f).setFontVariationSettings("'wght' 700").build();
                } else t = Typeface.createFromAsset(c.getAssets(), f);
            } catch (Throwable ignored) {}
            fonts.put(f, t);
            return t;
        }
    }

    static int hueShift(int c, float deg) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[0] = ((hsv[0] + deg) % 360 + 360) % 360;
        return Color.HSVToColor(Color.alpha(c), hsv);
    }

    private static float px(float cx, float r, double deg) { return (float) (cx + r * Math.cos(Math.toRadians(deg))); }
    private static float py(float cy, float r, double deg) { return (float) (cy + r * Math.sin(Math.toRadians(deg))); }

    // ================================================================ 🧭 the speedometers 1–8

    /** Everything a speedometer needs to be drawn (the Gauge view keeps one). */
    static final class G {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF r = new RectF();
        Typeface num;
        // a gradient is made again only when its size or colour changed
        Shader sh1, sh2;
        int shKey1, shKey2, style = -1;

        G() { p.setStrokeCap(Paint.Cap.ROUND); t.setTextAlign(Paint.Align.CENTER); }
    }

    /**
     * One of the eight new speedometers (CarTheme.G_TICK … G_SCOPE). drawn: the eased speed; speed &lt; 0: no GPS yet;
     * over: past the limit (red); engine: the OBD chip under it.
     */
    static void gauge(Canvas cv, G g, int style, int w, int h, float drawn, int speed, int limit, boolean over, boolean hot, String engine, CarTheme.Look look) {
        if (w <= 0 || h <= 0 || look == null) return;
        int warn = look.light ? 0xFFC62828 : 0xFFFF8A80;
        int ac = over ? 0xFFFF5A4E : look.accent, dim = look.light ? 0x26000000 : 0x2EFFFFFF;
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f, r = s * 0.42f;
        String num = speed < 0 ? "–" : String.valueOf(Math.round(drawn));
        float kmh = speed < 0 ? 0 : drawn;
        Paint p = g.p, t = g.t;
        if (g.style != style) { g.style = style; g.sh1 = g.sh2 = null; } // (another style's gradients are never reused)
        p.setShader(null);
        p.setMaskFilter(null);
        p.setPathEffect(null);
        switch (style) {
            case CarTheme.G_TICK: { // 1: sixty ticks round the number, lit up to the speed
                int n = 60, lit = CarStyle.lit(kmh, n);
                for (int i = 0; i <= n; i++) {
                    double a = 135 + 270.0 * i / n;
                    boolean big = i % 5 == 0, on = i < lit;
                    float r1 = r, r2 = r * (big ? 0.80f : 0.87f);
                    if (on) {
                        p.setStrokeWidth(s * (big ? 0.03f : 0.022f));
                        p.setColor(CarTheme.withAlpha(ac, 0x44));
                        cv.drawLine(px(cx, r1, a), py(cy, r1, a), px(cx, r2, a), py(cy, r2, a), p);
                    }
                    p.setStrokeWidth(s * (big ? 0.014f : 0.009f));
                    p.setColor(on ? ac : dim);
                    cv.drawLine(px(cx, r1, a), py(cy, r1, a), px(cx, r2, a), py(cy, r2, a), p);
                }
                limitTick(cv, g, cx, cy, r * 1.04f, r * 0.76f, limit, s);
                number(cv, g, look, num, over, warn, cx, cy + r * 0.16f, r * 0.62f, w);
                unit(cv, g, look, speed, cx, cy + r * 0.40f, r * 0.15f);
                limitSign(cv, g, cx, cy + r * 0.72f, r * 0.15f, limit);
                break;
            }
            case CarTheme.G_FAN: { // 2: thick blades fanning round, brighter towards the speed
                int n = 36, lit = CarStyle.lit(kmh, n);
                float span = 240f / n, a0 = 150;
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    double a = a0 + span * (i + 0.5f), half = span * 0.36f;
                    float ri = r * 0.66f, ro = r * (i % 6 == 5 ? 1.02f : 0.98f);
                    g.path.rewind();
                    g.path.moveTo(px(cx, ri, a - half), py(cy, ri, a - half));
                    g.path.lineTo(px(cx, ro, a - half * 1.25), py(cy, ro, a - half * 1.25));
                    g.path.lineTo(px(cx, ro, a + half * 1.25), py(cy, ro, a + half * 1.25));
                    g.path.lineTo(px(cx, ri, a + half), py(cy, ri, a + half));
                    g.path.close();
                    boolean on = i < lit;
                    p.setColor(on ? CarDeck.blend(CarTheme.shade(ac, 0.5f), ac, lit <= 1 ? 1 : i / (float) (lit - 1)) : dim);
                    cv.drawPath(g.path, p);
                }
                p.setStyle(Paint.Style.STROKE);
                limitTick(cv, g, cx, cy, r * 1.06f, r * 0.62f, limit, s, 150, 240);
                number(cv, g, look, num, over, warn, cx, cy + r * 0.14f, r * 0.56f, w);
                unit(cv, g, look, speed, cx, cy + r * 0.38f, r * 0.15f);
                limitSign(cv, g, cx, cy + r * 0.82f, r * 0.15f, limit);
                break;
            }
            case CarTheme.G_BLOCKS: { // 3: a racing bar of blocks over seven-segment digits
                int n = 20, lit = CarStyle.lit(kmh, n);
                float R = Math.min(w * 0.48f, h * 0.62f), ox = cx, oy = h * 0.66f;
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    double a = 205 + 130.0 * i / (n - 1), half = 2.4;
                    float ri = R * 0.80f, ro = R * (0.96f + 0.04f * i / n);
                    g.path.rewind();
                    g.path.moveTo(px(ox, ri, a - half), py(oy, ri, a - half));
                    g.path.lineTo(px(ox, ro, a - half + 1.2), py(oy, ro, a - half + 1.2));
                    g.path.lineTo(px(ox, ro, a + half + 1.2), py(oy, ro, a + half + 1.2));
                    g.path.lineTo(px(ox, ri, a + half), py(oy, ri, a + half));
                    g.path.close();
                    boolean on = i < lit;
                    int col = over && on ? 0xFFFF4D4D : CarStyle.blockColour(i, n);
                    p.setColor(on ? col : CarTheme.withAlpha(col, look.light ? 0x30 : 0x22));
                    cv.drawPath(g.path, p);
                }
                float dh = Math.min(h * 0.30f, w * 0.18f);
                segDigits(cv, g, num, cx, oy - dh * 0.15f, dh, over ? warn : ac, look.light);
                unit(cv, g, look, speed, cx, oy + dh * 0.22f, Math.max(10, dh * 0.22f));
                if (limit > 0) limitSign(cv, g, w * 0.12f, h * 0.84f, Math.min(w, h) * 0.07f, limit);
                break;
            }
            case CarTheme.G_RING: { // 4: a thick ring in two colours, a bright dot where the speed is
                int ac2 = hueShift(ac, -55);
                float sw = s * 0.075f, a0 = 120, sweep = 300;
                g.r.set(cx - r, cy - r, cx + r, cy + r);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(dim);
                cv.drawArc(g.r, a0, sweep, false, p);
                float f = CarStyle.frac(kmh);
                if (speed >= 0 && f > 0.003f) {
                    int key = (ac * 31 + ac2) * 31 + Math.round(cx * 7 + cy);
                    if (g.sh1 == null || g.shKey1 != key) { // (from the ring's start: the cool colour, warming to the theme's)
                        g.shKey1 = key;
                        Shader sg = new SweepGradient(cx, cy, new int[]{ac2, ac, ac}, new float[]{0, sweep / 360f, 1f});
                        Matrix m = new Matrix();
                        m.setRotate(a0 - 1, cx, cy);
                        sg.setLocalMatrix(m);
                        g.sh1 = sg;
                    }
                    p.setColor(CarTheme.withAlpha(ac, 0x40));
                    p.setStrokeWidth(sw * 2f);
                    cv.drawArc(g.r, a0, sweep * f, false, p);
                    p.setColor(0xFFFFFFFF);
                    p.setShader(g.sh1);
                    p.setStrokeWidth(sw);
                    cv.drawArc(g.r, a0, sweep * f, false, p);
                    p.setShader(null);
                    double ea = a0 + sweep * f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(CarTheme.withAlpha(ac, 0x66));
                    cv.drawCircle(px(cx, r, ea), py(cy, r, ea), sw * 0.95f, p);
                    p.setColor(0xFFFFFFFF);
                    cv.drawCircle(px(cx, r, ea), py(cy, r, ea), sw * 0.38f, p);
                    p.setStyle(Paint.Style.STROKE);
                }
                limitTick(cv, g, cx, cy, r + sw * 0.9f, r - sw * 0.9f, limit, s, a0, sweep);
                number(cv, g, look, num, over, warn, cx, cy + r * 0.18f, r * 0.68f, w);
                unit(cv, g, look, speed, cx, cy + r * 0.44f, r * 0.15f);
                limitSign(cv, g, cx, cy + r * 0.74f, r * 0.14f, limit);
                break;
            }
            case CarTheme.G_NEON: { // 5: a chrome rim, a neon band round the scale, a glowing needle
                float rr = r * 0.98f;
                p.setStyle(Paint.Style.FILL);
                int key = Math.round(cx * 7 + cy * 13 + rr);
                if (g.sh1 == null || g.shKey1 != key) {
                    g.shKey1 = key;
                    g.sh1 = new SweepGradient(cx, cy, new int[]{0xFFF4F7FA, 0xFF8C96A1, 0xFFE9EEF3, 0xFF4E5761, 0xFFDCE3EA, 0xFF7D8894, 0xFFF4F7FA}, null);
                    g.sh2 = new RadialGradient(cx, cy - rr * 0.3f, rr * 1.1f, new int[]{0xFF1A222C, 0xFF04070B}, null, Shader.TileMode.CLAMP);
                }
                p.setColor(0xFFFFFFFF);
                p.setShader(g.sh1);
                cv.drawCircle(cx, cy, rr * 1.08f, p);
                p.setShader(g.sh2);
                cv.drawCircle(cx, cy, rr, p);
                p.setShader(null);
                // the neon band along the scale (a glow, then the tube)
                g.r.set(cx - rr * 0.86f, cy - rr * 0.86f, cx + rr * 0.86f, cy + rr * 0.86f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(rr * 0.12f);
                p.setColor(CarTheme.withAlpha(ac, 0x30));
                cv.drawArc(g.r, 135, 270, false, p);
                p.setStrokeWidth(rr * 0.035f);
                p.setColor(CarTheme.withAlpha(ac, 0xDD));
                cv.drawArc(g.r, 135, 270, false, p);
                if (limit > 0) {
                    float from = 135 + 270 * CarStyle.frac(limit);
                    p.setColor(0xCCFF5252);
                    cv.drawArc(g.r, from, 135 + 270 - from, false, p);
                }
                // ticks and the numbers 0–160
                for (int i = 0; i <= 32; i++) {
                    double a = 135 + 270.0 * i / 32;
                    boolean big = i % 4 == 0;
                    p.setStrokeWidth(rr * (big ? 0.025f : 0.012f));
                    p.setColor(big ? 0xFFEAF2FA : 0x99EAF2FA);
                    float r1 = rr * 0.78f, r2 = rr * (big ? 0.68f : 0.73f);
                    cv.drawLine(px(cx, r1, a), py(cy, r1, a), px(cx, r2, a), py(cy, r2, a), p);
                }
                t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
                t.setColor(0xFFDCE6F0);
                t.setTextSize(rr * 0.11f);
                for (int i = 0; i <= 8; i++) {
                    double a = 135 + 270.0 * i / 8;
                    cv.drawText(String.valueOf(i * 20), px(cx, rr * 0.56f, a), py(cy, rr * 0.56f, a) + rr * 0.04f, t);
                }
                // the needle: its glow, its line, a red tip; the chrome cap
                if (speed >= 0) {
                    double a = 135 + 270.0 * CarStyle.frac(kmh);
                    float nx = px(cx, rr * 0.80f, a), ny = py(cy, rr * 0.80f, a);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(rr * 0.07f);
                    p.setColor(CarTheme.withAlpha(ac, 0x55));
                    cv.drawLine(cx, cy, nx, ny, p);
                    p.setStrokeWidth(rr * 0.025f);
                    p.setColor(0xFFF2F8FF);
                    cv.drawLine(cx, cy, nx, ny, p);
                    p.setColor(0xFFFF4D4D);
                    cv.drawLine(px(cx, rr * 0.66f, a), py(cy, rr * 0.66f, a), nx, ny, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setShader(g.sh1);
                cv.drawCircle(cx, cy, rr * 0.09f, p);
                p.setShader(null);
                // the number in a small dark window under the cap
                float by = cy + rr * 0.56f, bw2 = rr * 0.27f, bh2 = rr * 0.14f;
                g.r.set(cx - bw2, by - bh2, cx + bw2, by + bh2);
                p.setColor(0xCC000000);
                cv.drawRoundRect(g.r, bh2 * 0.4f, bh2 * 0.4f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, rr * 0.01f));
                p.setColor(CarTheme.withAlpha(ac, 0x99));
                cv.drawRoundRect(g.r, bh2 * 0.4f, bh2 * 0.4f, p);
                t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
                t.setColor(over ? 0xFFFF8A80 : 0xFFF2F8FF);
                fit(t, num, bh2 * 1.5f, bw2 * 1.7f);
                cv.drawText(num, cx, by + bh2 * 0.52f, t);
                t.setTypeface(Typeface.DEFAULT);
                t.setColor(0xFF9FB6C9);
                t.setTextSize(rr * 0.09f);
                cv.drawText(speed < 0 ? "GPS…" : "km/h", cx, by + bh2 * 1.9f, t);
                break;
            }
            case CarTheme.G_HALF: { // 6: a half ring over the number, its colours from cool to warm
                float R = Math.min(w * 0.44f, h * 0.62f), ox = cx, oy = h * 0.60f, sw = R * 0.17f;
                g.r.set(ox - R, oy - R, ox + R, oy + R);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setStrokeWidth(sw);
                p.setColor(dim);
                cv.drawArc(g.r, 180, 180, false, p);
                float f = CarStyle.frac(kmh);
                if (speed >= 0 && f > 0.003f) {
                    int key = Math.round(ox * 3 + R * 11) ^ ac;
                    if (g.sh1 == null || g.shKey1 != key) {
                        g.shKey1 = key;
                        g.sh1 = new LinearGradient(ox - R, 0, ox + R, 0, new int[]{hueShift(ac, -70), ac, over ? 0xFFFF4D4D : hueShift(ac, 40)}, null, Shader.TileMode.CLAMP);
                    }
                    p.setColor(0xFFFFFFFF); // (a shader takes the paint's alpha: full)
                    p.setShader(g.sh1);
                    cv.drawArc(g.r, 180, 180 * f, false, p);
                    p.setShader(null);
                }
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i <= 8; i++) { // (a tick every 20)
                    double a = 180 + 180.0 * i / 8;
                    p.setStrokeWidth(Math.max(1, R * 0.012f));
                    p.setColor(look.light ? 0x66000000 : 0x88FFFFFF);
                    cv.drawLine(px(ox, R - sw * 0.75f, a), py(oy, R - sw * 0.75f, a), px(ox, R - sw * 1.0f, a), py(oy, R - sw * 1.0f, a), p);
                }
                limitTick(cv, g, ox, oy, R + sw * 0.6f, R - sw * 0.6f, limit, s, 180, 180);
                t.setTypeface(Typeface.DEFAULT);
                t.setColor(look.textSoft);
                t.setTextSize(R * 0.09f);
                cv.drawText("0", ox - R, oy + R * 0.16f, t);
                cv.drawText("160", ox + R, oy + R * 0.16f, t);
                number(cv, g, look, num, over, warn, ox, oy - R * 0.06f, R * 0.5f, w);
                unit(cv, g, look, speed, ox, oy + R * 0.18f, R * 0.11f);
                limitSign(cv, g, ox, oy + R * 0.42f, R * 0.11f, limit);
                break;
            }
            case CarTheme.G_CYBER: { // 7: a cut-corner frame round a ring of blocks
                float c8 = r * 0.36f, e = r * 1.1f;
                g.path.rewind();
                g.path.moveTo(cx - e + c8, cy - e); g.path.lineTo(cx + e - c8, cy - e); g.path.lineTo(cx + e, cy - e + c8); g.path.lineTo(cx + e, cy + e - c8);
                g.path.lineTo(cx + e - c8, cy + e); g.path.lineTo(cx - e + c8, cy + e); g.path.lineTo(cx - e, cy + e - c8); g.path.lineTo(cx - e, cy - e + c8); g.path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(look.light ? 0x10000000 : 0x1AFFFFFF);
                cv.drawPath(g.path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, s * 0.006f));
                p.setColor(CarTheme.withAlpha(ac, 0x88));
                cv.drawPath(g.path, p);
                int n = 24, lit = CarStyle.lit(kmh, n);
                float span = 270f / n;
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    double a = 135 + span * (i + 0.5f), half = span * 0.34f;
                    float ri = r * 0.70f, ro = r * 0.92f;
                    g.path.rewind();
                    g.path.moveTo(px(cx, ri, a - half), py(cy, ri, a - half));
                    g.path.lineTo(px(cx, ro, a - half * 1.15), py(cy, ro, a - half * 1.15));
                    g.path.lineTo(px(cx, ro, a + half * 1.15), py(cy, ro, a + half * 1.15));
                    g.path.lineTo(px(cx, ri, a + half), py(cy, ri, a + half));
                    g.path.close();
                    boolean on = i < lit;
                    if (on) { p.setColor(CarTheme.withAlpha(ac, 0x40)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.02f); cv.drawPath(g.path, p); p.setStyle(Paint.Style.FILL); }
                    p.setColor(on ? ac : dim);
                    cv.drawPath(g.path, p);
                }
                t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
                t.setColor(look.textSoft);
                t.setTextSize(r * 0.13f);
                cv.drawText("SPEED", cx, cy - r * 0.36f, t);
                number(cv, g, look, num, over, warn, cx, cy + r * 0.18f, r * 0.56f, w);
                unit(cv, g, look, speed, cx, cy + r * 0.42f, r * 0.14f);
                limitSign(cv, g, cx, cy + r * 0.72f, r * 0.12f, limit);
                break;
            }
            default: { // 8 (G_SCOPE): thin rings, the cross's marks, an outer ring turning with the speed, corner brackets
                p.setStyle(Paint.Style.STROKE);
                float[] rings = {1f, 0.78f, 0.5f};
                int[] al = {0x99, 0x55, 0x33};
                for (int k = 0; k < rings.length; k++) {
                    p.setStrokeWidth(Math.max(1, s * (k == 0 ? 0.008f : 0.005f)));
                    p.setColor(CarTheme.withAlpha(look.accent, al[k]));
                    cv.drawCircle(cx, cy, r * rings[k], p);
                }
                for (int k = 0; k < 4; k++) { // (the four marks of a sight)
                    double a = 90 * k - 90;
                    p.setStrokeWidth(Math.max(1.5f, s * 0.012f));
                    p.setColor(CarTheme.withAlpha(look.accent, 0xCC));
                    cv.drawLine(px(cx, r * 1.04f, a), py(cy, r * 1.04f, a), px(cx, r * 1.16f, a), py(cy, r * 1.16f, a), p);
                }
                p.setStrokeWidth(Math.max(1, s * 0.01f));
                p.setColor(CarTheme.withAlpha(look.accent, 0x66));
                float dash = r * 0.12f;
                p.setPathEffect(new DashPathEffect(new float[]{dash, dash * 0.7f}, -kmh * r * 0.02f)); // (it turns as the speed goes up)
                cv.drawCircle(cx, cy, r * 1.1f, p);
                p.setPathEffect(null);
                // the speed: a bright arc on the inner ring
                float f = CarStyle.frac(kmh);
                g.r.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f);
                p.setStrokeWidth(s * 0.03f);
                p.setColor(CarTheme.withAlpha(ac, 0x33));
                cv.drawArc(g.r, 135, 270, false, p);
                if (speed >= 0 && f > 0.003f) {
                    p.setColor(ac);
                    cv.drawArc(g.r, 135, 270 * f, false, p);
                }
                limitTick(cv, g, cx, cy, r * 0.98f, r * 0.82f, limit, s);
                // the corner brackets
                float e = Math.min(r * 1.18f, Math.min(w, h) / 2f - Math.max(2, s * 0.01f)), L = r * 0.2f;
                p.setStrokeWidth(Math.max(1.5f, s * 0.012f));
                p.setColor(CarTheme.withAlpha(look.accent, 0xAA));
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                    float x = cx + sx * e, y = cy + sy * e;
                    cv.drawLine(x, y, x - sx * L, y, p);
                    cv.drawLine(x, y, x, y - sy * L, p);
                }
                number(cv, g, look, num, over, warn, cx, cy + r * 0.18f, r * 0.56f, w);
                unit(cv, g, look, speed, cx, cy + r * 0.42f, r * 0.14f);
                limitSign(cv, g, cx, cy + r * 0.70f, r * 0.13f, limit);
            }
        }
        p.setStrokeCap(Paint.Cap.ROUND);
        if (engine != null && !engine.isEmpty()) {
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(hot ? warn : look.textSoft);
            t.setTextSize(Math.max(10, s * 0.06f));
            cv.drawText(engine, cx, h - s * 0.03f, t);
        }
    }

    private static void fit(Paint t, String s, float size, float maxW) {
        t.setTextSize(size);
        float tw = t.measureText(s);
        if (tw > maxW && tw > 0) t.setTextSize(size * maxW / tw);
    }

    /** The big number (his font), red past the limit, a soft glow in the theme's colour. */
    private static void number(Canvas cv, G g, CarTheme.Look look, String num, boolean over, int warn, float x, float base, float size, int w) {
        Paint t = g.t;
        t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
        fit(t, num, size, w * 0.86f);
        t.setColor(over ? warn : look.textMain);
        t.setShadowLayer(size * 0.08f, 0, 0, CarTheme.withAlpha(over ? warn : look.accent, look.light ? 0x33 : 0x88));
        cv.drawText(num, x, base, t);
        t.setShadowLayer(0, 0, 0, 0);
    }

    private static void unit(Canvas cv, G g, CarTheme.Look look, int speed, float x, float base, float size) {
        Paint t = g.t;
        t.setTypeface(Typeface.DEFAULT);
        t.setColor(look.textSoft);
        t.setTextSize(size);
        cv.drawText(speed < 0 ? "GPS…" : "km/h", x, base, t);
    }

    /** The limit sign: a white disc with a red ring and the number. */
    static void limitSign(Canvas cv, G g, float x, float y, float lr, int limit) {
        if (limit <= 0 || lr <= 1) return;
        Paint p = g.p, t = g.t;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        cv.drawCircle(x, y + lr * 0.12f, lr, p);
        p.setColor(0xFFFFFFFF);
        cv.drawCircle(x, y, lr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(lr * 0.24f);
        p.setColor(0xFFE53935);
        cv.drawCircle(x, y, lr * 0.86f, p);
        t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
        t.setColor(0xFF111111);
        fit(t, String.valueOf(limit), lr * 0.95f, lr * 1.3f);
        cv.drawText(String.valueOf(limit), x, y + t.getTextSize() * 0.36f, t);
    }

    private static void limitTick(Canvas cv, G g, float cx, float cy, float r1, float r2, int limit, float s) { limitTick(cv, g, cx, cy, r1, r2, limit, s, 135, 270); }

    /** A red mark on the scale where the limit is. */
    private static void limitTick(Canvas cv, G g, float cx, float cy, float r1, float r2, int limit, float s, float a0, float sweep) {
        if (limit <= 0) return;
        double a = a0 + sweep * CarStyle.frac(limit);
        Paint p = g.p;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, s * 0.018f));
        p.setColor(0xFFFF4D4D);
        cv.drawLine(px(cx, r1, a), py(cy, r1, a), px(cx, r2, a), py(cy, r2, a), p);
    }

    /** 3's digits: seven segments each, the unlit ones faint (a real LED's ghost 8). */
    private static void segDigits(Canvas cv, G g, String num, float cx, float base, float dh, int col, boolean light) {
        float dw = dh * 0.56f, gap = dh * 0.14f, th = dh * 0.12f;
        float total = num.length() * dw + (num.length() - 1) * gap;
        float x0 = cx - total / 2f, top = base - dh;
        Paint p = g.p;
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < num.length(); k++) {
            int bits = CarStyle.segments(num.charAt(k));
            float x = x0 + k * (dw + gap);
            float[][] seg = { // a b c d e f g (x, y, w, h) in the digit's box
                    {th * 0.6f, 0, dw - th * 1.2f, th}, {dw - th, th * 0.6f, th, dh / 2 - th * 0.9f}, {dw - th, dh / 2 + th * 0.3f, th, dh / 2 - th * 0.9f},
                    {th * 0.6f, dh - th, dw - th * 1.2f, th}, {0, dh / 2 + th * 0.3f, th, dh / 2 - th * 0.9f}, {0, th * 0.6f, th, dh / 2 - th * 0.9f},
                    {th * 0.6f, dh / 2 - th / 2, dw - th * 1.2f, th}};
            for (int i = 0; i < 7; i++) {
                boolean on = (bits >> i & 1) == 1;
                float[] q = seg[i];
                float skew = dh * 0.08f * (1 - (q[1] + q[3] / 2) / dh); // (a little italic, like a dashboard's)
                g.r.set(x + q[0] + skew, top + q[1], x + q[0] + q[2] + skew, top + q[1] + q[3]);
                if (on) { p.setColor(CarTheme.withAlpha(col, 0x55)); g.r.inset(-th * 0.35f, -th * 0.35f); cv.drawRoundRect(g.r, th, th, p); g.r.inset(th * 0.35f, th * 0.35f); }
                p.setColor(on ? col : (light ? 0x14000000 : 0x14FFFFFF));
                cv.drawRoundRect(g.r, th * 0.45f, th * 0.45f, p);
            }
        }
    }

    // ================================================================ 🌌 the moving pictures 9–16

    /**
     * A whole picture behind the cards: the still part drawn once into a picture of the screen's size, then only the few
     * moving things each frame (stars, water, a comet, the grid, light streaks…). Our own drawings in the spirit of his
     * pictures (a sunset over a lake, a waterfall, planets, a neon grid, a HUD room, a racing mesh, speed lines, a glow).
     */
    static final class Scene {
        private Bitmap base;
        private String baseKind = "";
        private int bw, bh;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final float[] a = new float[64], b = new float[64], c = new float[64];
        private Shader meshTile, glowSh;

        Scene() {
            java.util.Random rnd = new java.util.Random(13);
            for (int i = 0; i < a.length; i++) { a[i] = rnd.nextFloat(); b[i] = rnd.nextFloat(); c[i] = rnd.nextFloat(); }
        }

        void release() {
            if (base != null) base.recycle();
            base = null;
            baseKind = "";
        }

        /** The picture for this frame (t: seconds; move: false with "fewer movements" — the still picture only). */
        void draw(Canvas cv, String kind, int w, int h, float t, boolean light) {
            if (w <= 0 || h <= 0) return;
            if (base == null || !kind.equals(baseKind) || bw != w || bh != h) {
                release();
                try {
                    base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas bc = new Canvas(base);
                    frame(bc, w, h);
                    still(bc, kind);
                    baseKind = kind;
                    bw = w;
                    bh = h;
                } catch (Throwable e) { // (no memory for it: nothing drawn, the theme's background shows)
                    release();
                    return;
                }
            }
            p.setShader(null);
            cv.drawBitmap(base, 0, 0, null);
            int s = cv.save();
            frame(cv, w, h);
            moving(cv, kind, t);
            cv.restoreToCount(s);
            if (light) cv.drawColor(0x66FFFFFF); // (the white theme's dark words stay readable)
            else if (CarStyle.brightScene(kind)) cv.drawColor(0x40000000);
        }

        /** The 300 × 170 drawing fills the screen (the middle kept, the edges cut: never stretched). */
        private static void frame(Canvas cv, int w, int h) {
            float k = Math.max(w / 300f, h / 170f);
            cv.translate((w - 300 * k) / 2f, (h - 170 * k) / 2f);
            cv.scale(k, k);
        }

        private LinearGradient vg(float y0, float y1, int[] cols) { p.setColor(0xFFFFFFFF); return new LinearGradient(0, y0, 0, y1, cols, null, Shader.TileMode.CLAMP); } // (a shader takes the paint's alpha: full)

        private void fill(Canvas cv, int col, String d) { p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col); poly(d); cv.drawPath(path, p); }

        /** "x,y x,y …" → a closed path. */
        private void poly(String d) {
            path.rewind();
            String[] pts = d.trim().split("\\s+");
            for (int i = 0; i < pts.length; i++) {
                String[] xy = pts[i].split(",");
                float x = Float.parseFloat(xy[0]), y = Float.parseFloat(xy[1]);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
        }

        private void still(Canvas cv, String kind) {
            p.setStyle(Paint.Style.FILL);
            switch (kind) {
                case "sunset": {
                    p.setShader(vg(0, 170, new int[]{0xFF5E3366, 0xFF8A4377, 0xFFB2557A}));
                    cv.drawRect(-200, -100, 500, 300, p);
                    p.setShader(null);
                    p.setColor(0x14FFFFFF); cv.drawCircle(150, 104, 58, p);
                    p.setColor(0x1FFFFFFF); cv.drawCircle(150, 104, 46, p);
                    p.setColor(0xFFF6F2F6); cv.drawCircle(150, 104, 34, p);
                    fill(cv, 0xFF8A3567, "-100,112 0,112 40,92 80,104 120,80 170,100 210,84 260,98 300,86 400,90 400,130 -100,130");
                    fill(cv, 0xFF6E2A55, "-100,120 0,120 50,104 100,116 150,100 200,114 250,104 300,112 400,110 400,134 -100,134");
                    p.setShader(vg(128, 170, new int[]{0xFFE8738E, 0xFFC95A7E}));
                    cv.drawRect(-200, 128, 500, 300, p);
                    p.setShader(null);
                    p.setColor(0xFF4A1636);
                    float[][] trees = {{20, 26}, {60, 18}, {250, 30}, {285, 20}};
                    for (float[] tr : trees) { cv.drawRect(tr[0] - 1.5f, 150 - tr[1], tr[0] + 1.5f, 150, p); cv.drawCircle(tr[0], 150 - tr[1], tr[1] * 0.45f, p); }
                    p.setColor(0xFF3E1230);
                    path.rewind(); path.moveTo(-100, 170); path.lineTo(-100, 148); path.lineTo(0, 148); path.quadTo(30, 140, 60, 152); path.lineTo(80, 300); path.lineTo(-100, 300); path.close(); cv.drawPath(path, p);
                    path.rewind(); path.moveTo(400, 170); path.lineTo(400, 146); path.lineTo(300, 146); path.quadTo(270, 140, 240, 154); path.lineTo(230, 300); path.lineTo(400, 300); path.close(); cv.drawPath(path, p);
                    break;
                }
                case "waterfall": {
                    p.setShader(vg(0, 70, new int[]{0xFF7A2A7A, 0xFFFF7A3C, 0xFFFFCF6A}));
                    cv.drawRect(-200, -100, 500, 70, p);
                    p.setShader(null);
                    p.setColor(0xFF7B2A62); cv.drawRect(-200, 60, 500, 130, p);
                    p.setColor(0xFF8E3270); cv.drawRect(40, 56, 130, 126, p); cv.drawRect(170, 52, 270, 126, p);
                    p.setShader(vg(62, 132, new int[]{0xE6C78BFF, 0xE67EE8FF}));
                    cv.drawRect(80, 62, 120, 132, p); cv.drawRect(190, 62, 250, 132, p);
                    p.setShader(vg(126, 170, new int[]{0xFF1AA3C9, 0xFF137FA0}));
                    cv.drawRect(-200, 126, 500, 300, p);
                    p.setShader(null);
                    p.setColor(0xFF3B1453);
                    float[] xs = {0, 9, 18, 27, 36, 45, 255, 264, 273, 282, 291, 300, 140, 150, 160};
                    java.util.Random rnd = new java.util.Random(9);
                    for (float x : xs) {
                        float hh = 30 + rnd.nextFloat() * 30, y = x < 60 || x > 240 ? 130 : 66;
                        path.rewind(); path.moveTo(x, y - hh); path.lineTo(x - 8, y); path.lineTo(x + 8, y); path.close(); cv.drawPath(path, p);
                    }
                    p.setColor(0xFF2A0F3A);
                    path.rewind(); path.moveTo(-100, 170); path.lineTo(-100, 140); path.lineTo(0, 140); path.quadTo(20, 132, 40, 146); path.lineTo(60, 300); path.lineTo(-100, 300); path.close(); cv.drawPath(path, p);
                    p.setColor(0xFF1F6B55); cv.drawCircle(270, 152, 14, p);
                    p.setColor(0xFF2A8A6A); cv.drawCircle(285, 146, 10, p);
                    break;
                }
                case "space": {
                    p.setShader(vg(0, 170, new int[]{0xFF0F3A4A, 0xFF1B2348, 0xFF241046}));
                    cv.drawRect(-200, -100, 500, 300, p);
                    p.setColor(0xFFFFFFFF);
                    p.setShader(new RadialGradient(198, 106, 40, new int[]{0xFFFFD1FF, 0xFFB05CFF, 0xFF4B1A8F}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
                    cv.drawCircle(210, 118, 34, p);
                    p.setShader(new RadialGradient(115, 135, 16, new int[]{0xFFD6FFF0, 0xFF2FD6A8, 0xFF145A6B}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
                    cv.drawCircle(120, 140, 14, p);
                    p.setShader(new RadialGradient(265, 37, 11, new int[]{0xFFD6FFF0, 0xFF2FD6A8, 0xFF145A6B}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
                    cv.drawCircle(268, 40, 9, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.5f);
                    p.setColor(0xFFFFCF6A);
                    cv.save(); cv.rotate(-12, 210, 120); r.set(156, 108, 264, 132); cv.drawOval(r, p); cv.restore();
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "synth": {
                    p.setShader(vg(0, 96, new int[]{0xFF1A0B3A, 0xFF6A1A6A, 0xFFFF3FA4}));
                    cv.drawRect(-200, -100, 500, 96, p);
                    p.setShader(vg(46, 114, new int[]{0xFFFFD166, 0xFFFF3FA4}));
                    cv.drawCircle(150, 80, 34, p);
                    p.setShader(null);
                    p.setColor(0xFF2A0F50);
                    for (int k = 0; k < 5; k++) cv.drawRect(110, 70 + k * 6, 190, 70 + k * 6 + 1 + k * 0.6f, p);
                    java.util.Random rnd = new java.util.Random(5);
                    for (float x = -100; x < 400; ) {
                        float ww = 10 + rnd.nextInt(13), hh = 14 + rnd.nextInt(31);
                        if (!(x > 105 && x < 190)) {
                            p.setColor(0xFF140A2E); cv.drawRect(x, 96 - hh, x + ww, 96, p);
                            p.setColor(0xB35FF3FF);
                            for (int k = 0; k < Math.min(6, (int) (hh / 7)); k++) cv.drawRect(x + 3 + (k % 2) * 6, 96 - hh + 4 + (k / 2) * 7, x + 5 + (k % 2) * 6, 96 - hh + 6 + (k / 2) * 7, p);
                        }
                        x += ww + 2;
                    }
                    p.setColor(0xFF12062A); cv.drawRect(-200, 96, 500, 300, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0xCCFF3FA4);
                    for (int i = -14; i <= 14; i++) cv.drawLine(150 + i * 8, 96, 150 + i * 60, 170 + 30, p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "hud": {
                    p.setColor(0xFFFFFFFF);
                    p.setShader(new RadialGradient(150, 94, 190, new int[]{0xFF0E3A6A, 0xFF04101F}, null, Shader.TileMode.CLAMP));
                    cv.drawRect(-200, -100, 500, 300, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.4f); p.setColor(0x801F6FB0);
                    for (float x = -96; x < 400; x += 12) cv.drawLine(x, -100, x, 300, p);
                    for (float y = -96; y < 300; y += 12) cv.drawLine(-200, y, 500, y, p);
                    p.setStyle(Paint.Style.FILL);
                    fill(cv, 0x261E78C8, "60,30 240,30 252,42 252,70 240,82 60,82 48,70 48,42");
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0xFF4FD2FF); cv.drawPath(path, p); p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "sport": {
                    p.setColor(0xFF1A2533); cv.drawRect(-200, -100, 500, 300, p);
                    if (meshTile == null) {
                        Bitmap tile = Bitmap.createBitmap(16, 14, Bitmap.Config.ARGB_8888);
                        Canvas tc = new Canvas(tile);
                        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        tp.setColor(0xFF05080D);
                        tc.drawOval(new RectF(2.8f, 2.6f, 13.2f, 11.4f), tp);
                        meshTile = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                        Matrix m = new Matrix(); m.setScale(0.5f, 0.5f); meshTile.setLocalMatrix(m);
                    }
                    p.setColor(0xFFFFFFFF); p.setShader(meshTile); cv.drawRect(-200, -100, 500, 300, p); p.setShader(null);
                    fill(cv, 0xFF1F3C6E, "-100,-50 120,-50 120,0 95,30 -100,30");
                    fill(cv, 0xFFE8343A, "-100,30 95,30 80,44 -100,44");
                    fill(cv, 0xFFFFFFFF, "130,-50 170,-50 170,0 150,24 110,24");
                    fill(cv, 0xFF244A86, "170,-50 400,-50 400,16 184,16");
                    fill(cv, 0xFF1F3C6E, "400,250 170,250 170,170 196,140 400,140");
                    fill(cv, 0xFFE8343A, "400,140 196,140 210,126 400,126");
                    fill(cv, 0xFFFFFFFF, "160,250 120,250 120,170 142,146 182,146");
                    fill(cv, 0xFF244A86, "-100,250 -100,150 110,150 96,170 96,250");
                    p.setColor(0xFFE8343A); cv.drawRect(292, 20, 400, 120, p); cv.drawRect(-100, 50, 6, 140, p);
                    break;
                }
                case "streaks": {
                    p.setColor(0xFF070B24); cv.drawRect(-200, -100, 500, 300, p);
                    break;
                }
                default: { // "teal"
                    p.setColor(0xFFFFFFFF);
                    p.setShader(new LinearGradient(0, 0, 300, 170, new int[]{0xFF2FD6C8, 0xFF3A6FD8, 0xFF7B3FD1}, null, Shader.TileMode.CLAMP));
                    cv.drawRect(-200, -100, 500, 300, p);
                    p.setShader(null);
                }
            }
            p.setShader(null);
        }

        private void stars(Canvas cv, float t, int n, float maxY, float skipX, float skipY, float skipR) {
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n && i < a.length; i++) {
                float x = -40 + a[i] * 380, y = -20 + b[i] * (maxY + 20);
                if (skipR > 0 && Math.hypot(x - skipX, y - skipY) < skipR) continue;
                float tw = 0.5f + 0.5f * (float) Math.sin(t * (1.2f + c[i] * 2.4f) + c[i] * 6.28f);
                p.setColor(CarTheme.withAlpha(0xFFFFFFFF, Math.round(60 + 170 * tw * (0.4f + 0.6f * c[i]))));
                cv.drawCircle(x, y, 0.45f + c[i] * 0.8f, p);
            }
        }

        private void moving(Canvas cv, String kind, float t) {
            p.setShader(null);
            switch (kind) {
                case "sunset": {
                    stars(cv, t, 40, 66, 150, 104, 40);
                    p.setColor(0xCCF8C9D6); // the moon's light dancing on the lake
                    for (int k = 0; k < 9; k++) {
                        float wd = 6 + 10 * (0.5f + 0.5f * (float) Math.sin(t * 1.3f + k * 1.7f)) * (1 - k / 12f), x = 150 + (float) Math.sin(t * 0.7f + k) * 6;
                        cv.drawRect(x - wd, 132 + k * 4, x + wd, 133.6f + k * 4, p);
                    }
                    p.setStyle(Paint.Style.STROKE); // birds crossing slowly
                    p.setStrokeWidth(1.2f);
                    p.setColor(0xFF5A2147);
                    for (int k = 0; k < 4; k++) {
                        float x = ((t * (6 + k) + k * 90) % 420) - 60, y = 62 + k * 5 + (float) Math.sin(t * 0.8f + k) * 3, wing = 2.4f + 1.4f * (float) Math.sin(t * 6 + k * 2);
                        path.rewind(); path.moveTo(x - 4, y); path.quadTo(x - 2, y - wing, x, y); path.quadTo(x + 2, y - wing, x + 4, y);
                        cv.drawPath(path, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "waterfall": {
                    float[][] falls = {{80, 40}, {190, 60}};
                    p.setColor(0x73FFFFFF);
                    for (float[] f : falls) {
                        int sv = cv.save();
                        cv.clipRect(f[0], 62, f[0] + f[1], 132);
                        for (int k = 0; k < (int) (f[1] / 5); k++) {
                            float x = f[0] + 2 + k * 5 + (k % 2), y = 62 + ((t * (38 + (k % 3) * 9) + k * 23) % 84) - 14;
                            cv.drawRect(x, y, x + 1.6f, y + 12 + (k % 4) * 3, p);
                        }
                        cv.restoreToCount(sv);
                    }
                    for (int k = 0; k < 2; k++) { // the mist breathing at the foot of the falls
                        float cx = k == 0 ? 100 : 220, rx = (k == 0 ? 40 : 50) + 4 * (float) Math.sin(t * 1.1f + k), ry = 6 + 1.5f * (float) Math.sin(t * 1.4f + k);
                        p.setColor(0xA6E8FBFF);
                        r.set(cx - rx, 128 - ry, cx + rx, 128 + ry);
                        cv.drawOval(r, p);
                    }
                    p.setColor(0xCCFFFFFF);
                    for (int k = 0; k < 14; k++) { // glints on the pool
                        float tw = (float) Math.sin(t * 2 + k * 1.9f);
                        if (tw > 0.6f) cv.drawCircle(20 + a[k] * 260, 138 + b[k] * 28, 0.8f, p);
                    }
                    break;
                }
                case "space": {
                    stars(cv, t, 60, 170, 210, 118, 38);
                    float cyc = t % 14f; // a comet now and then
                    if (cyc < 3.2f) {
                        float k = cyc / 3.2f, x = -10 + 210 * k, y = 34 + 40 * k;
                        int al = Math.round(255 * Math.min(1, Math.min(k * 5, (1 - k) * 4)));
                        p.setColor(0xFFFFFFFF);
                        p.setShader(new LinearGradient(x - 46, y - 10, x, y, new int[]{0x00FF4FD8, CarTheme.withAlpha(0xFFFFD1FF, al)}, null, Shader.TileMode.CLAMP));
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(4);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        cv.drawLine(x - 46, y - 10, x, y, p);
                        p.setShader(null);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(CarTheme.withAlpha(0xFFFFE6A0, al));
                        cv.drawCircle(x, y, 2.2f, p);
                    }
                    cv.save(); // a rock turning as it drifts
                    float ax = 40 + (float) Math.sin(t * 0.05f) * 14, ay = 118 + (float) Math.cos(t * 0.07f) * 6;
                    cv.rotate(t * 8, ax, ay);
                    path.rewind();
                    path.moveTo(ax - 10, ay); path.lineTo(ax + 10, ay - 10); path.lineTo(ax + 16, ay + 2); path.lineTo(ax + 4, ay + 14); path.lineTo(ax - 12, ay + 10); path.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xD95A3AD1);
                    cv.drawPath(path, p);
                    cv.restore();
                    break;
                }
                case "synth": {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1);
                    p.setColor(0xB35FF3FF);
                    float ph = (t * 0.55f) % 1f;
                    for (int k = 0; k < 8; k++) { // the grid's lines coming at us
                        float q = (k + ph) / 8f, y = 96 + 104 * q * q;
                        cv.drawLine(-200, y, 500, y, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "hud": {
                    p.setStyle(Paint.Style.STROKE);
                    float pulse = 0.5f + 0.5f * (float) Math.sin(t * 1.6f);
                    float[] rs = {60, 44, 28};
                    for (int k = 0; k < 3; k++) {
                        float rr = rs[k] * (1 + 0.05f * pulse);
                        p.setStrokeWidth(1.6f);
                        p.setColor(CarTheme.withAlpha(0xFF4FD2FF, Math.round(255 * (0.3f + 0.25f * k + 0.2f * pulse))));
                        r.set(150 - rr, 140 - rr * 0.22f, 150 + rr, 140 + rr * 0.22f);
                        cv.drawOval(r, p);
                    }
                    for (int k = 0; k < 3; k++) { // little round meters turning
                        p.setStrokeWidth(2.5f);
                        p.setColor(0xFF4FD2FF);
                        p.setPathEffect(new DashPathEffect(new float[]{20 - k * 5, 40}, -t * (12 + k * 5)));
                        cv.drawCircle(250 + k * 16, 150, 6, p);
                    }
                    p.setPathEffect(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xCC4FA8FF);
                    for (int k = 0; k < 6; k++) { // a small graph breathing
                        float hh = 6 + k * 4 + 5 * (float) Math.sin(t * 2.2f + k);
                        cv.drawRect(12 + k * 6, 150 - hh, 16 + k * 6, 150, p);
                    }
                    p.setColor(0x334FD2FF); // a slow scan line
                    float sy = ((t * 14) % 200) - 15;
                    cv.drawRect(-200, sy, 500, sy + 2, p);
                    break;
                }
                case "sport": {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2.1f);
                    p.setColor(0xFFFFFFFF);
                    p.setShader(new RadialGradient(150, 88, 40 + 6 * pulse, new int[]{0xFFBFE8FF, 0xCC2F8BFF, 0x002F8BFF}, new float[]{0, 0.22f, 1}, Shader.TileMode.CLAMP));
                    cv.drawCircle(150, 88, 46 + 6 * pulse, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1);
                    p.setColor(CarTheme.withAlpha(0xFFD9F2FF, Math.round(150 + 90 * pulse)));
                    cv.save(); cv.rotate(t * 10, 150, 88);
                    cv.drawLine(150, 58, 150, 118, p); cv.drawLine(120, 88, 180, 88, p);
                    cv.drawLine(130, 68, 170, 108, p); cv.drawLine(170, 68, 130, 108, p);
                    cv.restore();
                    p.setStyle(Paint.Style.FILL);
                    float sx = ((t * 40) % 520) - 110; // a light sweeping over the mesh
                    p.setColor(0xFFFFFFFF);
                    p.setShader(new LinearGradient(sx - 30, 0, sx + 30, 0, new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                    path.rewind(); path.moveTo(sx - 30, -60); path.lineTo(sx + 30, -60); path.lineTo(sx - 10, 240); path.lineTo(sx - 70, 240); path.close();
                    cv.drawPath(path, p);
                    p.setShader(null);
                    break;
                }
                case "streaks": {
                    int[] cols = {0xFF2F6BFF, 0xFFFF2F5F, 0xFF22C8FF, 0xFF7B5CFF};
                    for (int i = 0; i < 26; i++) {
                        float v = 30 + c[i] * 80, len = 60 + b[i] * 100, x = ((a[i] * 520 + t * v) % 520) - 120 - len, y = 6 + b[(i + 7) % 64] * 158;
                        p.setColor(CarTheme.withAlpha(cols[i % 4], Math.round(90 + 130 * c[(i + 3) % 64])));
                        r.set(x, y, x + len, y + 1.5f + c[i] * 2.5f);
                        cv.drawRoundRect(r, 2, 2, p);
                    }
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(5);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    float sl = (t * 22) % 16;
                    for (int g2 = 0; g2 < 2; g2++) for (int k = 0; k < 6; k++) {
                        float x = (g2 == 0 ? 20 : 190) + k * 16 + sl, y = g2 == 0 ? 70 : 120;
                        p.setColor(CarTheme.withAlpha(g2 == 0 ? 0xFF2F6BFF : 0xFFFF2F5F, Math.round(255 * (0.2f + 0.12f * k))));
                        path.rewind(); path.moveTo(x, y); path.lineTo(x + 10, y + 10); path.lineTo(x, y + 20);
                        cv.drawPath(path, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    stars(cv, t, 30, 170, 0, 0, 0);
                    break;
                }
                default: { // teal: soft lights drifting
                    if (glowSh == null) glowSh = new RadialGradient(0, 0, 70, new int[]{0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
                    p.setColor(0xFFFFFFFF);
                    p.setShader(glowSh);
                    for (int k = 0; k < 3; k++) {
                        float x = 150 + 120 * (float) Math.sin(t * 0.07f + k * 2.1f), y = 85 + 60 * (float) Math.cos(t * 0.05f + k * 1.3f);
                        int sv = cv.save();
                        cv.translate(x, y);
                        cv.drawCircle(0, 0, 70, p);
                        cv.restoreToCount(sv);
                    }
                    p.setShader(null);
                }
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
        }
    }

    // ================================================================ 🃏 the card frames (17 HUD, 18 glowing edge)

    /** 17: a card with its corners cut, a bright bracket at two corners, a row of fine marks on top (the bigger cards). */
    static Drawable hud(final CarTheme.Look l, final float rad, final boolean tile) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private Shader fill;
            private final Rect made = new Rect();

            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                if (b.width() <= 2 || b.height() <= 2) return;
                float L = b.left + 1, T = b.top + 1, R = b.right - 1, B = b.bottom - 1, c = Math.min(Math.max(6, rad * 0.75f), Math.min(R - L, B - T) * 0.3f);
                path.rewind();
                path.moveTo(L + c, T); path.lineTo(R - c, T); path.lineTo(R, T + c); path.lineTo(R, B - c);
                path.lineTo(R - c, B); path.lineTo(L + c, B); path.lineTo(L, B - c); path.lineTo(L, T + c); path.close();
                if (fill == null || !made.equals(b)) {
                    made.set(b);
                    int base = tile ? l.tileFill : l.glassFill;
                    fill = new LinearGradient(L, T, L, B, new int[]{CarDeck.blend(base | 0xFF000000, l.accent, 0.10f) & 0x00FFFFFF | (base & 0xFF000000), CarTheme.withAlpha(base, 0x12)}, null, Shader.TileMode.CLAMP);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF); // (a shader takes the paint's alpha: full)
                p.setShader(fill);
                cv.drawPath(path, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f);
                p.setColor(CarTheme.withAlpha(l.accent, l.light ? 0x88 : 0x99));
                cv.drawPath(path, p);
                // the brackets: top-left and bottom-right, along the cut corners
                float k = Math.min(R - L, B - T) * 0.22f;
                p.setStrokeWidth(3f);
                p.setStrokeCap(Paint.Cap.SQUARE);
                p.setColor(CarTheme.withAlpha(l.accent, 0xEE));
                path.rewind();
                path.moveTo(L - 2, T + c + k); path.lineTo(L - 2, T + c - 1); path.lineTo(L + c - 1, T - 2); path.lineTo(L + c + k, T - 2);
                path.moveTo(R + 2, B - c - k); path.lineTo(R + 2, B - c + 1); path.lineTo(R - c + 1, B + 2); path.lineTo(R - c - k, B + 2);
                cv.drawPath(path, p);
                if (!tile && R - L > 120) { // fine marks along the top, a small label bar
                    p.setStrokeWidth(1);
                    p.setColor(CarTheme.withAlpha(l.accent, 0x77));
                    float y = T + c * 0.55f, x0 = R - c - (R - L) * 0.32f;
                    for (int i = 0; i < 10; i++) { float x = x0 + i * (R - L) * 0.028f; cv.drawLine(x, y - c * 0.22f, x, y + c * 0.22f, p); }
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(CarTheme.withAlpha(l.accent, 0x30));
                    cv.drawRect(L + c * 1.4f, T + c * 0.35f, L + c * 1.4f + (R - L) * 0.16f, T + c * 0.75f, p);
                }
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter f) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * 18: a card with a coloured edge in three of the theme's colours, a soft glow round it; on the bigger cards a bright
     * light runs round the edge (resting with "fewer movements").
     */
    static Drawable glow(final CarTheme.Look l, final float rad, final boolean tile) {
        return new GlowCard(l, rad, tile);
    }

    private static final class GlowCard extends Drawable implements Runnable {
        private final CarTheme.Look l;
        private final float rad;
        private final boolean tile;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), seg = new Path();
        private final RectF r = new RectF();
        private final PathMeasure pm = new PathMeasure();
        private final Rect made = new Rect();
        private Shader edge, fill;
        private float len;

        GlowCard(CarTheme.Look l, float rad, boolean tile) {
            this.l = l; this.rad = rad; this.tile = tile;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void run() { invalidateSelf(); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            if (b.width() <= 4 || b.height() <= 4) return;
            if (!made.equals(b) || edge == null) {
                made.set(b);
                r.set(b);
                r.inset(3, 3);
                path.rewind();
                path.addRoundRect(r, rad, rad, Path.Direction.CW);
                pm.setPath(path, true);
                len = pm.getLength();
                edge = new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{l.accent, hueShift(l.accent, 70), hueShift(l.accent, -60)}, null, Shader.TileMode.CLAMP);
                int base = tile ? l.tileFill : l.glassFill;
                fill = new LinearGradient(r.left, r.top, r.left, r.bottom, new int[]{base, CarTheme.withAlpha(base, 0x10)}, null, Shader.TileMode.CLAMP);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF); // (a shader takes the paint's alpha: full)
            p.setShader(fill);
            cv.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setShader(edge);
            p.setAlpha(0x50);
            p.setStrokeWidth(7);
            cv.drawRoundRect(r, rad, rad, p);
            p.setAlpha(0xFF);
            p.setStrokeWidth(2.2f);
            cv.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            if (tile || l.lowMotion || len <= 0) return;
            long now = SystemClock.uptimeMillis();
            float at = (now % 5200) / 5200f * len, piece = len * 0.12f;
            seg.rewind();
            pm.getSegment(at, Math.min(len, at + piece), seg, true);
            if (at + piece > len) pm.getSegment(0, at + piece - len, seg, true);
            int hl = l.light ? l.accent : 0xFFFFFFFF; // (white on the white theme would not show)
            p.setColor(CarTheme.withAlpha(hl, 0x55));
            p.setStrokeWidth(8);
            cv.drawPath(seg, p);
            p.setColor(CarTheme.withAlpha(hl, 0xF2));
            p.setStrokeWidth(2.6f);
            cv.drawPath(seg, p);
            unscheduleSelf(this);
            scheduleSelf(this, now + 66); // (15 frames a second are plenty for a light running round)
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ================================================================ 💿 23: the turning record

    /** The song's picture as the label of a record that turns while the song plays, a ring round it for how far. */
    static final class Vinyl extends View {
        private Bitmap art;
        private BitmapShader artSh;
        private final Matrix am = new Matrix();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int accent = 0xFFFF7AB0;
        private boolean playing, low;
        private float angle;
        private long last, pos = -1, upd, dur = -1;
        private float speed = 1;
        private Shader disc, label;
        private int discFor, labelFor;
        private final Runnable frame = this::invalidate;

        Vinyl(Context c) { super(c); p.setStrokeCap(Paint.Cap.ROUND); }

        void setArt(Bitmap b) {
            if (b == art) return;
            art = b;
            artSh = b == null || b.isRecycled() ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            invalidate();
        }

        void setAccent(int c) { if (c != accent) { accent = c; invalidate(); } }
        void setLowMotion(boolean b) { low = b; invalidate(); }

        /** The music app's numbers (Music.progress): position, its time, speed, playing, length. */
        void setTiming(long pos, long upd, float speed, boolean playing, long dur) {
            this.pos = pos; this.upd = upd; this.speed = speed; this.dur = dur;
            if (playing != this.playing) { this.playing = playing; last = 0; }
            invalidate();
        }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(frame); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h), cx = w / 2f, cy = h / 2f, ring = s * 0.045f, R = s / 2f - ring;
            long now = SystemClock.uptimeMillis();
            if (playing && !low) { if (last != 0) angle = (angle + (now - last) * 0.09f) % 360; last = now; } else last = 0; // (a turn in 4 s)
            float f = CarStyle.songFraction(pos, upd, speed, playing, SystemClock.elapsedRealtime(), dur);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ring);
            r.set(cx - R, cy - R, cx + R, cy + R);
            p.setColor(0x40FFFFFF);
            cv.drawArc(r, 0, 360, false, p);
            if (f >= 0) { p.setColor(accent); cv.drawArc(r, -90, Math.max(1, 360 * f), false, p); }
            float rr = R - ring * 1.1f;
            if (disc == null || discFor != Math.round(rr * 10 + cx)) {
                discFor = Math.round(rr * 10 + cx);
                disc = new RadialGradient(cx, cy, Math.max(1, rr), new int[]{0xFF2A2A2A, 0xFF050505}, null, Shader.TileMode.CLAMP);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(disc);
            cv.drawCircle(cx, cy, rr, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(0.6f, s * 0.004f));
            p.setColor(0x26FFFFFF);
            for (float k = 0.48f; k < 0.97f; k += 0.07f) cv.drawCircle(cx, cy, rr * k, p);
            p.setStrokeWidth(rr * 0.14f); // the light on the grooves (it stays: the record turns under it)
            p.setColor(0x14FFFFFF);
            r.set(cx - rr * 0.74f, cy - rr * 0.74f, cx + rr * 0.74f, cy + rr * 0.74f);
            cv.drawArc(r, 200, 45, false, p);
            cv.drawArc(r, 20, 45, false, p);
            // the label: the song's picture, turning
            float lr = rr * 0.44f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            if (artSh != null && art != null && !art.isRecycled()) {
                float sc = 2 * lr / Math.min(art.getWidth(), art.getHeight());
                am.setScale(sc, sc);
                am.postTranslate(cx - art.getWidth() * sc / 2f, cy - art.getHeight() * sc / 2f);
                am.postRotate(angle, cx, cy);
                artSh.setLocalMatrix(am);
                p.setShader(artSh);
            } else {
                int key = accent * 31 + Math.round(lr * 10 + cx);
                if (label == null || labelFor != key) { labelFor = key; label = new LinearGradient(cx - lr, cy - lr, cx + lr, cy + lr, accent, hueShift(accent, -60), Shader.TileMode.CLAMP); }
                p.setShader(label);
            }
            cv.drawCircle(cx, cy, lr, p);
            p.setShader(null);
            p.setColor(0xFF111111);
            cv.drawCircle(cx, cy, Math.max(2, rr * 0.05f), p);
            removeCallbacks(frame);
            if (playing && !low && isShown()) postOnAnimationDelayed(frame, 40);
            else if (playing && f >= 0 && isShown()) postDelayed(frame, 1000); // (resting: only the ring moves on)
        }
    }

    // ================================================================ 〰️ 24: the little waves of the song

    /** Waves under the song's name (a decoration made from its name), the played part in colour, a dot where it is. */
    static final class WaveBar extends View {
        private String seed = "";
        private float[] hs = new float[0];
        private int hsFor = -1, accent = 0xFF2FE0FF, rest = 0x66FFFFFF;
        private boolean playing;
        private long pos = -1, upd, dur = -1;
        private float speed = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Shader sh;
        private int shFor;
        private final Runnable frame = this::invalidate;

        WaveBar(Context c) { super(c); }

        void setSeed(String s) { if (!s.equals(seed)) { seed = s; hsFor = -1; invalidate(); } }
        void setColours(int accent, int rest) { if (accent != this.accent || rest != this.rest) { this.accent = accent; this.rest = rest; sh = null; invalidate(); } }
        void setTiming(long pos, long upd, float speed, boolean playing, long dur) {
            this.pos = pos; this.upd = upd; this.speed = speed; this.playing = playing; this.dur = dur;
            invalidate();
        }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(frame); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 8 || h <= 4) return;
            float d = getResources().getDisplayMetrics().density, slot = Math.max(3 * d, 5.5f * d);
            int n = Math.max(12, (int) (w / slot));
            if (hsFor != n) { hs = CarStyle.waves(seed, n); hsFor = n; }
            slot = w / (float) n;
            float bw = Math.max(1.5f, slot * 0.55f), mid = h / 2f;
            float f = CarStyle.songFraction(pos, upd, speed, playing, SystemClock.elapsedRealtime(), dur);
            if (sh == null || shFor != w) { shFor = w; sh = new LinearGradient(0, 0, w, 0, accent, hueShift(accent, -60), Shader.TileMode.CLAMP); }
            for (int i = 0; i < n; i++) {
                float x = i * slot + (slot - bw) / 2, hh = Math.max(bw, h * 0.92f * hs[i]);
                boolean done = f >= 0 && (i + 0.5f) / n <= f;
                p.setShader(done ? sh : null);
                p.setColor(done ? 0xFFFFFFFF : rest);
                r.set(x, mid - hh / 2, x + bw, mid + hh / 2);
                cv.drawRoundRect(r, bw / 2, bw / 2, p);
            }
            p.setShader(null);
            if (f >= 0) {
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(Math.max(bw, Math.min(w - bw, f * w)), mid, Math.min(h * 0.32f, bw * 1.6f), p);
            }
            removeCallbacks(frame);
            if (playing && f >= 0 && isShown()) postDelayed(frame, 700);
        }
    }

    // ================================================================ ✨ 28: the neon car of the welcome

    /** The car's outline drawing itself in neon (our car drawing's own outline), speed lines behind it, then gone. */
    static final class NeonCar extends View {
        private final Path body = new Path(), glass = new Path(), seg = new Path();
        private final PathMeasure pm = new PathMeasure();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int ac = 0xFF2FE0FF, ac2 = 0xFFFF3FA4;
        private long t0;
        private Runnable after;
        private final Runnable frame = this::invalidate;

        NeonCar(Context c) {
            super(c);
            CarArt.Hero.outline(body, glass);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setVisibility(GONE);
        }

        /** Draw it now (colours: the theme's), then run after. */
        void play(int accent, Runnable after) {
            ac = accent;
            ac2 = hueShift(accent, 140);
            this.after = after;
            t0 = SystemClock.uptimeMillis();
            setAlpha(1f);
            setClickable(true); // (while it draws, a touch never reaches the hidden buttons under it)
            setVisibility(VISIBLE);
            invalidate();
        }

        void stop() { removeCallbacks(frame); setVisibility(GONE); after = null; }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(frame); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float t = (SystemClock.uptimeMillis() - t0) / 1000f;
            float k = Math.min(1, t / 1.5f), fade = t > 2.1f ? Math.max(0, 1 - (t - 2.1f) / 0.5f) : 1;
            cv.drawColor(CarTheme.withAlpha(0xFF02040A, Math.round(0xE6 * fade)));
            float s = Math.min(w * 0.64f / 400f, h * 0.5f / 150f);
            int sv = cv.save();
            cv.translate((w - 400 * s) / 2f, (h - 150 * s) / 2f);
            cv.scale(s, s);
            p.setAlpha(255);
            for (int i = 0; i < 9; i++) { // the speed lines behind it
                float y = 70 + i * 7, x1 = -60 - ((t * 260 + i * 37) % 120), len = 60 + (i * 23) % 70;
                p.setStrokeWidth(2.4f);
                p.setColor(CarTheme.withAlpha(i % 2 == 0 ? ac : ac2, Math.round((60 + i * 18) * fade)));
                cv.drawLine(x1 + 120, y, x1 + 120 + len, y, p);
            }
            outline(cv, body, k, ac, fade);
            if (k > 0.45f) outline(cv, glass, Math.min(1, (k - 0.45f) / 0.55f), ac2, fade);
            p.setColor(CarTheme.withAlpha(ac2, Math.round(255 * fade))); // the wheels drawn round
            p.setStrokeWidth(3);
            for (float x : new float[]{118, 292}) cv.drawArc(x - 23, 88, x + 23, 134, -90, 360 * k, false, p);
            p.setColor(CarTheme.withAlpha(ac, Math.round(150 * fade)));
            p.setStrokeWidth(1.6f);
            cv.drawLine(-80, 137, 480 * k, 137, p);
            cv.restoreToCount(sv);
            removeCallbacks(frame);
            if (t < 2.6f) postOnAnimationDelayed(frame, 33);
            else {
                setVisibility(GONE);
                Runnable a = after;
                after = null;
                if (a != null) a.run();
            }
        }

        private void outline(Canvas cv, Path path, float k, int col, float fade) {
            pm.setPath(path, true);
            seg.rewind();
            float L = 0;
            do { L += pm.getLength(); } while (pm.nextContour());
            pm.setPath(path, true);
            float left = L * k;
            do {
                float l = pm.getLength();
                if (left <= 0) break;
                pm.getSegment(0, Math.min(l, left), seg, true);
                left -= l;
            } while (pm.nextContour());
            p.setStrokeWidth(9);
            p.setColor(CarTheme.withAlpha(col, Math.round(0x50 * fade)));
            cv.drawPath(seg, p);
            p.setStrokeWidth(2.6f);
            p.setColor(CarTheme.withAlpha(col, Math.round(255 * fade)));
            cv.drawPath(seg, p);
        }
    }

    // ================================================================ 🔴 21: the limit sign with the speed (the map's corner)

    /** The road's limit sign (when known) beside his speed in a box; past the limit the box turns red and glows softly. */
    static final class SpeedBadge extends View {
        private int kmh = -1, limit;
        private final G g = new G();
        private boolean light;
        private int fill = 0xE6061420, ink = 0xFFFFFFFF;
        private final Runnable frame = this::invalidate;

        SpeedBadge(Context c) { super(c); }

        void setFont(Typeface t) { g.num = t; invalidate(); }
        void setColours(int fill, int ink, boolean light) { this.fill = fill; this.ink = ink; this.light = light; invalidate(); }

        void set(int kmh, int limit) {
            if (kmh == this.kmh && limit == this.limit) return;
            this.kmh = kmh; this.limit = limit;
            invalidate();
        }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(frame); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            boolean over = kmh >= 0 && limit > 0 && kmh > limit + 5;
            float lr = h * 0.46f, x = 0;
            if (limit > 0) { limitSign(cv, g, lr + 1, h / 2f, lr, limit); x = lr * 2 + h * 0.16f; }
            float bw = Math.max(h * 1.3f, w - x - 2);
            g.r.set(x, h * 0.12f, x + bw, h * 0.92f);
            Paint p = g.p;
            p.setStyle(Paint.Style.FILL);
            if (over) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 260.0);
                p.setColor(CarTheme.withAlpha(0xFFFF5A4E, Math.round(0x40 + 0x50 * pulse)));
                g.r.inset(-h * 0.07f, -h * 0.07f);
                cv.drawRoundRect(g.r, h * 0.3f, h * 0.3f, p);
                g.r.inset(h * 0.07f, h * 0.07f);
            }
            p.setColor(over ? 0xF23A1210 : fill);
            cv.drawRoundRect(g.r, h * 0.24f, h * 0.24f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, h * 0.04f));
            p.setColor(over ? 0xFFFF6B5E : CarTheme.withAlpha(ink, 0x44));
            cv.drawRoundRect(g.r, h * 0.24f, h * 0.24f, p);
            Paint t = g.t;
            t.setTypeface(g.num != null ? g.num : Typeface.DEFAULT_BOLD);
            String s = kmh < 0 ? "–" : String.valueOf(kmh);
            fit(t, s, h * 0.52f, bw * 0.86f);
            t.setColor(over ? 0xFFFF8A80 : ink);
            cv.drawText(s, x + bw / 2f, h * 0.52f + t.getTextSize() * 0.36f, t);
            removeCallbacks(frame);
            if (over && isShown()) postOnAnimationDelayed(frame, 66);
        }
    }
}
