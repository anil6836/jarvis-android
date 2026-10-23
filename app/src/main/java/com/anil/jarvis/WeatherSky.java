package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The home tablet's stage background: the navy, the soft round light behind Jarvis and the weather outside (SkyScene)
 * as a calm animation — clouds drifting, rain, snow, fog, a soft glow in a storm — plus the day's occasion. About 25
 * frames a second, 10 when dimmed, nothing while hidden; one still frame when nothing moves. Gradients are made only
 * when the scene or the size changes; a frame allocates nothing.
 */
final class WeatherSky extends View {
    private final SkyScene sky = new SkyScene();
    private final Pen pen = new Pen();
    private final Runnable tick = this::invalidate;
    private long last;
    private boolean dim;

    WeatherSky(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // (only a picture)
    }

    /** WMO weather code from Open-Meteo (-1 unknown), temperature °C (NaN unknown), day or night. */
    void set(int wmoCode, float tempC, boolean isDay) {
        sky.set(wmoCode, tempC, isDay);
        invalidate();
    }

    /** Night screen / dimmed: slower frames, softer colours. */
    void setDim(boolean d) {
        dim = d;
        sky.setDim(d);
        invalidate();
    }

    /** "none", "christmas", "newyear", "sankranti", "easter" or "birthday": drawn over the weather. */
    void setOccasion(String o) {
        sky.setOccasion(o);
        invalidate();
    }

    /** Morning birds across the sky (day, fair weather only; SkyScene decides when they show). */
    void setBirds(boolean on) {
        sky.setBirds(on);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        sky.resize(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.04f : Math.min(0.25f, (now - last) / 1000f);
        last = now;
        sky.step(dt);
        pen.cv = canvas;
        try { sky.draw(pen); } finally { pen.cv = null; }
        removeCallbacks(tick);
        if (sky.moving() && isShown() && getWindowVisibility() == VISIBLE) postDelayed(tick, dim ? 100 : 40);
        else last = 0;
    }

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (tick == null) return; // (during construction)
        if (isShown()) { last = 0; invalidate(); } else removeCallbacks(tick);
    }

    @Override protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        if (tick == null) return;
        if (vis == VISIBLE) { last = 0; invalidate(); } else removeCallbacks(tick);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    // ================================================================ SkyScene.Brush on an Android canvas
    /** SkyScene.Brush on an Android canvas (the garden in the corner draws with one too). */
    static final class Pen implements SkyScene.Brush {
        Canvas cv;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grad = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        // the gradient cache: one shader per slot, remade only when its colours, stops or geometry change
        private final Shader[] shaders = new Shader[SkyScene.SLOTS];
        private final int[][] keyColors = new int[SkyScene.SLOTS][];
        private final float[][] keyStops = new float[SkyScene.SLOTS][];
        private final float[] keyGeo = new float[SkyScene.SLOTS * 4];

        Pen() {
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            grad.setStyle(Paint.Style.FILL);
        }

        @Override public void rect(float l, float t, float r, float b, int argb) {
            fill.setColor(argb);
            cv.drawRect(l, t, r, b, fill);
        }

        @Override public void circle(float cx, float cy, float r, int argb) {
            if (r <= 0 || (argb >>> 24) == 0) return;
            fill.setColor(argb);
            cv.drawCircle(cx, cy, r, fill);
        }

        @Override public void oval(float cx, float cy, float rx, float ry, int argb) {
            if (rx <= 0 || ry <= 0 || (argb >>> 24) == 0) return;
            fill.setColor(argb);
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            cv.drawOval(oval, fill);
        }

        @Override public void ring(float cx, float cy, float rx, float ry, float width, int argb) {
            if (rx <= 0 || ry <= 0 || (argb >>> 24) == 0) return;
            stroke.setColor(argb);
            stroke.setStrokeWidth(width);
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            cv.drawOval(oval, stroke);
        }

        @Override public void line(float x0, float y0, float x1, float y1, float width, int argb) {
            if ((argb >>> 24) == 0) return;
            stroke.setColor(argb);
            stroke.setStrokeWidth(width);
            cv.drawLine(x0, y0, x1, y1, stroke);
        }

        @Override public void polyline(float[] xy, int n, float width, int argb) {
            if (n < 2 || (argb >>> 24) == 0) return;
            path.rewind();
            path.moveTo(xy[0], xy[1]);
            for (int i = 1; i < n; i++) path.lineTo(xy[2 * i], xy[2 * i + 1]);
            stroke.setColor(argb);
            stroke.setStrokeWidth(width);
            cv.drawPath(path, stroke);
        }

        @Override public void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
            if ((argb >>> 24) == 0) return;
            path.rewind();
            path.moveTo(x0, y0);
            path.lineTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
            fill.setColor(argb);
            cv.drawPath(path, fill);
        }

        @Override public void linear(int slot, float l, float t, float r, float b, float x0, float y0, float x1, float y1,
                                     int[] colors, float[] stops, float alpha) {
            if (alpha <= 0f) return;
            if (stale(slot, colors, stops, x0, y0, x1, y1))
                shaders[slot] = new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP);
            grad.setShader(shaders[slot]);
            grad.setAlpha(Math.min(255, Math.round(alpha * 255)));
            cv.drawRect(l, t, r, b, grad);
            grad.setShader(null);
        }

        @Override public void radial(int slot, float cx, float cy, float r, float ry, int[] colors, float[] stops, float alpha) {
            if (alpha <= 0f || r <= 0f || ry <= 0f) return;
            if (stale(slot, colors, stops, r, 0, 0, 0))
                shaders[slot] = new RadialGradient(0, 0, r, colors, stops, Shader.TileMode.CLAMP);
            grad.setShader(shaders[slot]);
            grad.setAlpha(Math.min(255, Math.round(alpha * 255)));
            cv.save();
            cv.translate(cx, cy);
            if (ry != r) cv.scale(1f, ry / r);
            cv.drawCircle(0, 0, r, grad);
            cv.restore();
            grad.setShader(null);
        }

        /** True (and the key kept) when the slot's gradient must be made again. */
        private boolean stale(int slot, int[] colors, float[] stops, float a, float b, float c, float d) {
            int k = slot * 4;
            if (shaders[slot] != null && keyColors[slot] == colors && keyStops[slot] == stops
                    && keyGeo[k] == a && keyGeo[k + 1] == b && keyGeo[k + 2] == c && keyGeo[k + 3] == d) return false;
            keyColors[slot] = colors;
            keyStops[slot] = stops;
            keyGeo[k] = a; keyGeo[k + 1] = b; keyGeo[k + 2] = c; keyGeo[k + 3] = d;
            return true;
        }
    }
}
