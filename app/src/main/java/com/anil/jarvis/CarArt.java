package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * 🎨 The car screen's drawn parts (no pictures from anywhere: everything is drawn here, so every theme and every box
 * screen size gets sharp lines): the themed backdrop with its pattern, the speed gauge with depth, the thin glowing line
 * icons of the six buttons, and glass panels. Each view invalidates only when its numbers change.
 */
final class CarArt {
    private CarArt() {}

    // ================================================================ the backdrop
    /** The theme's background: a deep gradient with a faint pattern (grid, carbon, hex, wireframe, horizon…). */
    static final class Backdrop extends View {
        private CarTheme.Look look;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private android.graphics.BitmapShader grain;

        Backdrop(Context c) { super(c); ink.setStyle(Paint.Style.STROKE); }

        void setLook(CarTheme.Look l) { look = l; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            if (look == null) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            p.setShader(new RadialGradient(w * 0.25f, 0, Math.max(w, h) * 1.1f, new int[]{look.bgTop, look.bgBottom}, new float[]{0, 1}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
            ink.setColor(look.patternInk);
            ink.setStrokeWidth(1.2f);
            float u = Math.max(18, Math.min(w, h) / 16f);
            switch (look.pattern) {
                case CarTheme.P_GRID:
                case CarTheme.P_WIRE:
                    if (look.pattern == CarTheme.P_WIRE) ink.setColor(CarTheme.withAlpha(look.accent, look.night ? 0x18 : 0x26));
                    for (float x = 0; x < w; x += u) cv.drawLine(x, 0, x, h, ink);
                    for (float y = 0; y < h; y += u) cv.drawLine(0, y, w, y, ink);
                    if (look.pattern == CarTheme.P_WIRE) { // scan lines
                        ink.setColor(CarTheme.withAlpha(look.accent, 0x10));
                        for (float y = 0; y < h; y += 4) cv.drawLine(0, y, w, y, ink);
                    }
                    break;
                case CarTheme.P_CARBON: {
                    ink.setStrokeWidth(2);
                    float s = Math.max(5, u / 6);
                    for (float d = -h; d < w; d += s * 2) cv.drawLine(d, 0, d + h, h, ink);
                    ink.setColor(CarTheme.withAlpha(Color.WHITE, 0x08));
                    for (float d = 0; d < w + h; d += s * 2) cv.drawLine(d, 0, d - h, h, ink);
                    break;
                }
                case CarTheme.P_HEX: {
                    float r = u / 2.4f;
                    for (int row = 0; row * r * 1.5f < h + r; row++)
                        for (float x = (row % 2 == 0 ? 0 : r * 0.87f); x < w + r; x += r * 1.74f) hex(cv, x, row * r * 1.5f, r * 0.92f, ink);
                    break;
                }
                case CarTheme.P_LEATHER: { // (a 12 px tile of four grains, repeated: one draw, not thousands of dots)
                    if (grain == null) {
                        android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888);
                        Canvas tc = new Canvas(tile);
                        ink.setColor(CarTheme.withAlpha(Color.WHITE, 0x0A));
                        ink.setStyle(Paint.Style.FILL);
                        tc.drawCircle(0, 0, 0.9f, ink); tc.drawCircle(6, 0, 0.9f, ink); tc.drawCircle(12, 0, 0.9f, ink);
                        tc.drawCircle(3, 6, 0.9f, ink); tc.drawCircle(9, 6, 0.9f, ink);
                        tc.drawCircle(0, 12, 0.9f, ink); tc.drawCircle(6, 12, 0.9f, ink); tc.drawCircle(12, 12, 0.9f, ink);
                        ink.setStyle(Paint.Style.STROKE);
                        grain = new android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                    }
                    p.setShader(grain);
                    cv.drawRect(0, 0, w, h, p);
                    p.setShader(null);
                    break;
                }
                case CarTheme.P_HORIZON: { // the sun low over a road running to the horizon
                    float hy = h * 0.62f;
                    p.setShader(new LinearGradient(0, h * 0.2f, 0, hy, new int[]{0x00000000, CarTheme.withAlpha(look.accent, 0x55), CarTheme.withAlpha(0xFFFF6AD5, 0x33)}, new float[]{0, 0.8f, 1}, Shader.TileMode.CLAMP));
                    cv.drawRect(0, h * 0.2f, w, hy, p);
                    p.setShader(null);
                    p.setColor(CarTheme.withAlpha(look.accent, 0x99));
                    cv.drawCircle(w * 0.5f, hy - 2, h * 0.09f, p);
                    ink.setColor(CarTheme.withAlpha(look.accent, 0x2A));
                    for (int i = 1; i <= 8; i++) { float y = hy + (h - hy) * (i * i) / 64f; cv.drawLine(0, y, w, y, ink); }
                    for (int i = -6; i <= 6; i++) cv.drawLine(w * 0.5f + i * w * 0.04f, hy, w * 0.5f + i * w * 0.28f, h, ink);
                    break;
                }
                case CarTheme.P_STARS: {
                    p.setColor(CarTheme.withAlpha(Color.WHITE, 0x66));
                    java.util.Random r = new java.util.Random(7);
                    for (int i = 0; i < 90; i++) cv.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.7f, r.nextFloat() * 1.6f + 0.4f, p);
                    break;
                }
                default: break;
            }
            // a soft vignette so the edges fall away (depth)
            p.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f, new int[]{0, 0, look.light ? 0x22000000 : 0x99000000}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        private void hex(Canvas cv, float cx, float cy, float r, Paint pt) {
            path.rewind();
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(60 * i + 30);
                float x = (float) (cx + r * Math.cos(a)), y = (float) (cy + r * Math.sin(a));
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            cv.drawPath(path, pt);
        }
    }

    // ================================================================ the gauge
    /** The speed: an arc that fills and glows (red past the limit), or a classic dial with a needle; the limit sign under it. */
    static final class Gauge extends View {
        private CarTheme.Look look;
        private int speed = -1, limit, shown = -1;
        private float drawn; // the eased speed
        private boolean over, hot;
        private String engine = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private long lastFrame;
        private final Runnable ease = this::invalidate;
        private static final float A0 = 135, SWEEP = 270, MAX = 160;

        Gauge(Context c) {
            super(c);
            p.setStrokeCap(Paint.Cap.ROUND);
            t.setTextAlign(Paint.Align.CENTER);
        }

        void setLook(CarTheme.Look l) { look = l; invalidate(); }

        /** speed < 0: no GPS yet. */
        void set(int speed, int limit, boolean over, boolean hot, String engine) {
            boolean change = speed != this.speed || limit != this.limit || over != this.over || hot != this.hot || !engine.equals(this.engine);
            this.speed = speed; this.limit = limit; this.over = over; this.hot = hot; this.engine = engine == null ? "" : engine;
            if (change) invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(ease);
        }

        @Override protected void onDraw(Canvas cv) {
            if (look == null) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h * 1.0f);
            float cx = w / 2f, cy = s * 0.5f, r = s * 0.40f, sw = s * 0.06f;
            // ease the number towards the real speed (never a jumpy needle)
            long now = android.os.SystemClock.uptimeMillis();
            float target = Math.max(0, speed);
            if (Math.abs(drawn - target) > 0.5f) {
                float dt = lastFrame == 0 ? 0.03f : Math.min(0.1f, (now - lastFrame) / 1000f);
                drawn += (target - drawn) * Math.min(1, dt * 6);
                removeCallbacks(ease);
                postOnAnimationDelayed(ease, 33); // (30 frames a second are plenty for a number)
            } else drawn = target;
            lastFrame = now;
            boolean needle = look.gauge == CarTheme.G_NEEDLE;
            int accent = over ? 0xFFFF5A4E : look.accent;
            int warn = look.light ? 0xFFC62828 : 0xFFFF8A80; // (the warning red readable on the white theme too)
            if (needle) dial(cv, cx, cy, r, accent);
            else arcGauge(cv, cx, cy, r, sw, accent);
            // the speed
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setColor(over ? warn : look.textMain);
            t.setTextSize(needle ? r * 0.42f : r * 0.78f);
            t.setShadowLayer(needle ? 0 : r * 0.06f, 0, r * 0.03f, 0x66000000);
            String num = speed < 0 ? "–" : String.valueOf(Math.round(drawn));
            cv.drawText(num, cx, needle ? cy + r * 0.62f : cy + r * 0.12f, t);
            t.setShadowLayer(0, 0, 0, 0);
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(look.textSoft);
            t.setTextSize(r * 0.17f);
            cv.drawText(speed < 0 ? "GPS…" : "km/h", cx, needle ? cy + r * 0.82f : cy + r * 0.38f, t);
            // the limit sign (a white disc with a red ring)
            if (limit > 0) {
                float ly = cy + r * (needle ? 1.12f : 0.82f), lr = r * 0.2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x55000000);
                cv.drawCircle(cx, ly + lr * 0.12f, lr, p);
                p.setColor(Color.WHITE);
                cv.drawCircle(cx, ly, lr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(lr * 0.26f);
                p.setColor(0xFFE53935);
                cv.drawCircle(cx, ly, lr * 0.86f, p);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setColor(0xFF111111);
                t.setTextSize(lr * 0.95f);
                cv.drawText(String.valueOf(limit), cx, ly + lr * 0.34f, t);
                t.setTypeface(Typeface.DEFAULT);
            }
            // the engine's chip (OBD) under everything
            if (!engine.isEmpty()) {
                t.setColor(hot ? warn : look.textSoft);
                t.setTextSize(r * 0.16f);
                cv.drawText(engine, cx, h - r * 0.08f, t);
            }
        }

        private void arcGauge(Canvas cv, float cx, float cy, float r, float sw, int accent) {
            arc.set(cx - r, cy - r, cx + r, cy + r);
            // depth: a dark groove behind the track
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw * 1.9f);
            p.setColor(look.light ? 0x14000000 : 0x55000000);
            cv.drawArc(arc, A0, SWEEP, false, p);
            p.setStrokeWidth(sw);
            p.setColor(look.light ? 0x1A000000 : 0x22FFFFFF);
            cv.drawArc(arc, A0, SWEEP, false, p);
            float frac = Math.min(1, drawn / MAX);
            if (speed >= 0 && frac > 0.002f) {
                // the glow (wide, faint) then the lit arc then a thin highlight: reads as a lit tube
                p.setColor(CarTheme.withAlpha(accent, 0x44));
                p.setStrokeWidth(sw * 2.2f);
                cv.drawArc(arc, A0, SWEEP * frac, false, p);
                p.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{CarTheme.shade(accent, 0.55f), accent, accent}, new float[]{0.37f, 0.6f, 1}));
                p.setStrokeWidth(sw);
                cv.drawArc(arc, A0, SWEEP * frac, false, p);
                p.setShader(null);
                p.setColor(CarTheme.withAlpha(Color.WHITE, 0x55));
                p.setStrokeWidth(sw * 0.22f);
                arc.inset(sw * 0.3f, sw * 0.3f);
                cv.drawArc(arc, A0 + 1, Math.max(0, SWEEP * frac - 2), false, p);
                arc.inset(-sw * 0.3f, -sw * 0.3f);
            }
            // ticks
            p.setStrokeWidth(Math.max(1, sw * 0.1f));
            p.setColor(look.light ? 0x33000000 : 0x40FFFFFF);
            for (int i = 0; i <= 16; i++) {
                double a = Math.toRadians(A0 + SWEEP * i / 16f);
                float r1 = r - sw * 0.95f, r2 = r - sw * (i % 4 == 0 ? 1.6f : 1.3f);
                cv.drawLine((float) (cx + r1 * Math.cos(a)), (float) (cy + r1 * Math.sin(a)), (float) (cx + r2 * Math.cos(a)), (float) (cy + r2 * Math.sin(a)), p);
            }
            // the limit's dot on the ring
            if (limit > 0) {
                double a = Math.toRadians(A0 + SWEEP * Math.min(1, limit / MAX));
                float dx = (float) (cx + r * Math.cos(a)), dy = (float) (cy + r * Math.sin(a));
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFF5252);
                cv.drawCircle(dx, dy, sw * 0.42f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw * 0.12f);
                p.setColor(Color.WHITE);
                cv.drawCircle(dx, dy, sw * 0.42f, p);
            }
        }

        private void dial(Canvas cv, float cx, float cy, float r, int accent) {
            // a chrome rim, a dark face, numbers, a red band past the limit, a needle with a shadow
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy - r * 0.6f, r * 1.6f, new int[]{0xFFE6ECF2, 0xFF8A97A6, 0xFF2B323B}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r * 1.08f, p);
            p.setShader(new RadialGradient(cx, cy, r, new int[]{look.light ? 0xFFFBF7EE : 0xFF161C24, look.light ? 0xFFE8E0CF : 0xFF05080C}, new float[]{0.7f, 1}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r * 0.98f, p);
            p.setShader(null);
            int ink = look.light ? 0xFF2A3440 : 0xFFE6ECF2;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            for (int i = 0; i <= 32; i++) {
                double a = Math.toRadians(A0 + SWEEP * i / 32f);
                boolean big = i % 4 == 0;
                p.setStrokeWidth(big ? r * 0.03f : r * 0.012f);
                float r1 = r * 0.92f, r2 = r * (big ? 0.80f : 0.86f);
                cv.drawLine((float) (cx + r1 * Math.cos(a)), (float) (cy + r1 * Math.sin(a)), (float) (cx + r2 * Math.cos(a)), (float) (cy + r2 * Math.sin(a)), p);
            }
            t.setColor(ink);
            t.setTextSize(r * 0.13f);
            for (int i = 0; i <= 8; i++) {
                double a = Math.toRadians(A0 + SWEEP * i / 8f);
                cv.drawText(String.valueOf(i * 20), (float) (cx + r * 0.66f * Math.cos(a)), (float) (cy + r * 0.66f * Math.sin(a)) + r * 0.045f, t);
            }
            if (limit > 0) {
                arc.set(cx - r * 0.95f, cy - r * 0.95f, cx + r * 0.95f, cy + r * 0.95f);
                p.setColor(0xCCFF5252);
                p.setStrokeWidth(r * 0.05f);
                float from = A0 + SWEEP * Math.min(1, limit / MAX);
                cv.drawArc(arc, from, A0 + SWEEP - from, false, p);
            }
            if (speed >= 0) {
                double a = Math.toRadians(A0 + SWEEP * Math.min(1, drawn / MAX));
                float nx = (float) (cx + r * 0.84f * Math.cos(a)), ny = (float) (cy + r * 0.84f * Math.sin(a));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(r * 0.045f);
                p.setColor(0x66000000);
                cv.drawLine(cx + r * 0.02f, cy + r * 0.03f, nx + r * 0.02f, ny + r * 0.03f, p);
                p.setColor(accent);
                cv.drawLine(cx, cy, nx, ny, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2B323B);
            cv.drawCircle(cx, cy, r * 0.09f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.02f);
            p.setColor(0xFFC8D0D8);
            cv.drawCircle(cx, cy, r * 0.09f, p);
        }
    }

    // ================================================================ the icons
    /**
     * A thin glowing line icon: the tiles (mic, way, music, fuel, home, sleep, games, quiz, story, apps), the road card
     * (the turn's arrow, arrival, rain, fog, the road, a snowflake for the AC, a clock, parking, a trophy) and the song's
     * keys (previous, play / pause, next).
     */
    static final class Icon extends View {
        static final int MIC = 0, WAY = 1, MUSIC = 2, FUEL = 3, HOME = 4, SLEEP = 5, GAMES = 6, QUIZ = 7, STORY = 8, APPS = 9,
                LEFT = 10, RIGHT = 11, AHEAD = 12, UTURN = 13, ROUND = 14, ARRIVE = 15, SLIGHT_LEFT = 16, SLIGHT_RIGHT = 17,
                RAIN = 18, FOG = 19, ROAD = 20, SNOW = 21, CLOCK = 22, PARK = 23, PREV = 24, PLAY = 25, NEXT = 26, CUP = 27, STAR = 28;
        private int kind;
        private int colour = 0xFF5FF3FF;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColour(int c) { if (colour != c) { colour = c; invalidate(); } }
        void setKind(int k) { if (kind != k) { kind = k; invalidate(); } }
        int kind() { return kind; }

        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            float u = s / 52f; // the icons are drawn on a 52-unit box
            cv.save();
            cv.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            cv.scale(u, u);
            p.setStrokeWidth(2.6f);
            // the glow, then the line
            p.setColor(CarTheme.withAlpha(colour, 0x55));
            p.setMaskFilter(null);
            p.setStrokeWidth(5.5f);
            shape();
            cv.drawPath(path, p);
            p.setColor(colour);
            p.setStrokeWidth(2.6f);
            cv.drawPath(path, p);
            cv.restore();
        }

        private void shape() {
            Path q = path;
            q.rewind();
            switch (kind) {
                case MIC:
                    q.addRoundRect(19, 6, 33, 30, 7, 7, Path.Direction.CW);
                    q.moveTo(12, 24); q.arcTo(new RectF(12, 10, 40, 38), 180, -180, false);
                    q.moveTo(26, 38); q.lineTo(26, 46); q.moveTo(18, 46); q.lineTo(34, 46);
                    break;
                case WAY:
                    q.addCircle(26, 26, 19, Path.Direction.CW);
                    q.moveTo(26, 12); q.lineTo(32, 28); q.lineTo(26, 25); q.lineTo(20, 28); q.close();
                    break;
                case MUSIC:
                    q.moveTo(20, 38); q.lineTo(20, 12); q.lineTo(40, 8); q.lineTo(40, 34);
                    q.addCircle(15, 38, 5, Path.Direction.CW); q.addCircle(35, 34, 5, Path.Direction.CW);
                    break;
                case FUEL:
                    q.addRoundRect(9, 8, 29, 44, 3, 3, Path.Direction.CW);
                    q.moveTo(13, 16); q.lineTo(25, 16);
                    q.moveTo(29, 18); q.lineTo(36, 23); q.lineTo(36, 38); q.arcTo(new RectF(36, 35, 42, 41), 180, -180, false); q.lineTo(42, 22); q.lineTo(37, 16);
                    break;
                case HOME:
                    q.moveTo(7, 25); q.lineTo(26, 9); q.lineTo(45, 25);
                    q.moveTo(12, 22); q.lineTo(12, 42); q.lineTo(40, 42); q.lineTo(40, 22);
                    q.moveTo(22, 42); q.lineTo(22, 31); q.lineTo(30, 31); q.lineTo(30, 42);
                    break;
                case SLEEP:
                    q.moveTo(36, 33); q.arcTo(new RectF(7, 11, 37, 41), -20, -250, false); q.quadTo(20, 34, 36, 33);
                    q.moveTo(33, 9); q.lineTo(40, 9); q.lineTo(33, 17); q.lineTo(40, 17);
                    break;
                case GAMES:
                    q.addRoundRect(6, 16, 46, 40, 10, 10, Path.Direction.CW);
                    q.moveTo(16, 24); q.lineTo(16, 32); q.moveTo(12, 28); q.lineTo(20, 28);
                    q.addCircle(33, 26, 1.2f, Path.Direction.CW); q.addCircle(38, 30, 1.2f, Path.Direction.CW);
                    break;
                case QUIZ:
                    q.addCircle(26, 26, 19, Path.Direction.CW);
                    q.moveTo(20, 21); q.arcTo(new RectF(20, 15, 32, 27), 180, 200, false); q.quadTo(26, 28, 26, 32);
                    q.addCircle(26, 37, 1.2f, Path.Direction.CW);
                    break;
                case STORY:
                    q.moveTo(8, 10); q.lineTo(22, 10); q.quadTo(26, 10, 26, 14); q.lineTo(26, 42); q.quadTo(26, 38, 22, 38); q.lineTo(8, 38); q.close();
                    q.moveTo(44, 10); q.lineTo(30, 10); q.quadTo(26, 10, 26, 14); q.lineTo(26, 42); q.quadTo(26, 38, 30, 38); q.lineTo(44, 38); q.close();
                    break;
                case LEFT: // up, then a bend to the left with the arrow's head
                    q.moveTo(32, 46); q.lineTo(32, 26); q.quadTo(32, 18, 24, 18); q.lineTo(12, 18);
                    q.moveTo(19, 10); q.lineTo(11, 18); q.lineTo(19, 26);
                    break;
                case RIGHT:
                    q.moveTo(20, 46); q.lineTo(20, 26); q.quadTo(20, 18, 28, 18); q.lineTo(40, 18);
                    q.moveTo(33, 10); q.lineTo(41, 18); q.lineTo(33, 26);
                    break;
                case SLIGHT_LEFT:
                    q.moveTo(30, 46); q.lineTo(30, 30); q.lineTo(16, 14);
                    q.moveTo(15, 24); q.lineTo(15, 13); q.lineTo(26, 13);
                    break;
                case SLIGHT_RIGHT:
                    q.moveTo(22, 46); q.lineTo(22, 30); q.lineTo(36, 14);
                    q.moveTo(37, 24); q.lineTo(37, 13); q.lineTo(26, 13);
                    break;
                case UTURN:
                    q.moveTo(34, 46); q.lineTo(34, 20); q.arcTo(new RectF(16, 8, 34, 26), 0, -180, false); q.lineTo(16, 36);
                    q.moveTo(9, 29); q.lineTo(16, 37); q.lineTo(23, 29);
                    break;
                case ROUND:
                    q.addCircle(26, 24, 9, Path.Direction.CW);
                    q.moveTo(26, 46); q.lineTo(26, 33); q.moveTo(33, 18); q.lineTo(42, 10);
                    q.moveTo(35, 9); q.lineTo(43, 9); q.lineTo(43, 17);
                    break;
                case ARRIVE: // a map pin
                    q.moveTo(26, 46); q.quadTo(12, 30, 12, 21); q.arcTo(new RectF(12, 7, 40, 35), 180, 180, false); q.quadTo(40, 30, 26, 46); q.close();
                    q.addCircle(26, 21, 5, Path.Direction.CW);
                    break;
                case RAIN:
                    q.moveTo(14, 28); q.arcTo(new RectF(8, 16, 20, 28), 90, 180, false); q.arcTo(new RectF(15, 6, 35, 26), 200, 140, false);
                    q.arcTo(new RectF(32, 14, 44, 28), 270, 180, false); q.close();
                    q.moveTo(17, 34); q.lineTo(14, 42); q.moveTo(26, 34); q.lineTo(23, 44); q.moveTo(35, 34); q.lineTo(32, 42);
                    break;
                case FOG:
                    q.moveTo(8, 16); q.lineTo(44, 16); q.moveTo(12, 24); q.lineTo(40, 24); q.moveTo(8, 32); q.lineTo(44, 32); q.moveTo(14, 40); q.lineTo(38, 40);
                    break;
                case ROAD: // a road running to the horizon with its centre line
                    q.moveTo(20, 8); q.lineTo(8, 46); q.moveTo(32, 8); q.lineTo(44, 46);
                    q.moveTo(26, 10); q.lineTo(26, 16); q.moveTo(26, 22); q.lineTo(26, 30); q.moveTo(26, 36); q.lineTo(26, 46);
                    break;
                case SNOW:
                    for (int i = 0; i < 3; i++) {
                        double a = Math.toRadians(90 + 60 * i);
                        float dx = (float) (18 * Math.cos(a)), dy = (float) (18 * Math.sin(a));
                        q.moveTo(26 - dx, 26 - dy); q.lineTo(26 + dx, 26 + dy);
                    }
                    q.moveTo(21, 11); q.lineTo(26, 15); q.lineTo(31, 11); q.moveTo(21, 41); q.lineTo(26, 37); q.lineTo(31, 41);
                    break;
                case CLOCK:
                    q.addCircle(26, 26, 18, Path.Direction.CW);
                    q.moveTo(26, 15); q.lineTo(26, 26); q.lineTo(34, 31);
                    break;
                case PARK:
                    q.addRoundRect(8, 8, 44, 44, 9, 9, Path.Direction.CW);
                    q.moveTo(21, 38); q.lineTo(21, 15); q.lineTo(28, 15); q.arcTo(new RectF(22, 15, 36, 29), 270, 180, false); q.lineTo(21, 29);
                    break;
                case PREV:
                    q.moveTo(14, 14); q.lineTo(14, 38); q.moveTo(38, 14); q.lineTo(19, 26); q.lineTo(38, 38); q.close();
                    break;
                case PLAY: // play and pause together: the strip doesn't guess which one the app is in
                    q.moveTo(10, 13); q.lineTo(28, 26); q.lineTo(10, 39); q.close();
                    q.moveTo(34, 14); q.lineTo(34, 38); q.moveTo(42, 14); q.lineTo(42, 38);
                    break;
                case NEXT:
                    q.moveTo(38, 14); q.lineTo(38, 38); q.moveTo(14, 14); q.lineTo(33, 26); q.lineTo(14, 38); q.close();
                    break;
                case CUP: // a tea cup with steam
                    q.moveTo(10, 24); q.lineTo(36, 24); q.lineTo(34, 38); q.quadTo(33, 42, 29, 42); q.lineTo(17, 42); q.quadTo(13, 42, 12, 38); q.close();
                    q.moveTo(36, 27); q.quadTo(44, 27, 42, 33); q.quadTo(40, 36, 35, 35);
                    q.moveTo(18, 18); q.quadTo(16, 14, 19, 10); q.moveTo(26, 18); q.quadTo(24, 14, 27, 10);
                    break;
                case STAR:
                    for (int i = 0; i < 10; i++) {
                        double a = Math.toRadians(-90 + 36 * i);
                        float rr = i % 2 == 0 ? 19 : 8;
                        float x = (float) (26 + rr * Math.cos(a)), y = (float) (27 + rr * Math.sin(a));
                        if (i == 0) q.moveTo(x, y); else q.lineTo(x, y);
                    }
                    q.close();
                    break;
                case AHEAD:
                    q.moveTo(26, 46); q.lineTo(26, 10); q.moveTo(17, 19); q.lineTo(26, 9); q.lineTo(35, 19);
                    break;
                default: // APPS: four rounded squares
                    q.addRoundRect(9, 9, 23, 23, 4, 4, Path.Direction.CW); q.addRoundRect(29, 9, 43, 23, 4, 4, Path.Direction.CW);
                    q.addRoundRect(9, 29, 23, 43, 4, 4, Path.Direction.CW); q.addRoundRect(29, 29, 43, 43, 4, 4, Path.Direction.CW);
            }
        }
    }

    // ================================================================ glass
    /** A glass panel: a translucent fill, a thin lit edge, a soft shadow (drawn, so it works on every Android). */
    static android.graphics.drawable.Drawable glass(final CarTheme.Look l, final float radiusPx, final boolean tile) {
        return new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            @Override public void draw(Canvas cv) {
                r.set(getBounds());
                r.inset(1, 1);
                // the shadow
                p.setStyle(Paint.Style.FILL);
                p.setColor(l.light ? 0x1A000000 : 0x66000000);
                r.offset(0, radiusPx * 0.25f);
                cv.drawRoundRect(r, radiusPx, radiusPx, p);
                r.offset(0, -radiusPx * 0.25f);
                // the fill with a light from the top
                p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{tile ? l.tileFill : l.glassFill, CarTheme.withAlpha(tile ? l.tileFill : l.glassFill, 0x10)},
                        new float[]{0, 1}, Shader.TileMode.CLAMP));
                cv.drawRoundRect(r, radiusPx, radiusPx, p);
                p.setShader(null);
                // the edge, brighter on top
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                p.setColor(tile ? l.tileStroke : l.glassStroke);
                cv.drawRoundRect(r, radiusPx, radiusPx, p);
                p.setColor(l.light ? 0x66FFFFFF : 0x30FFFFFF);
                cv.drawLine(r.left + radiusPx, r.top + 1.5f, r.right - radiusPx, r.top + 1.5f, p);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
}
