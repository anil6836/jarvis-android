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
 * The new Jarvis (BodyRig) on the screen: about 30 frames a second while he talks or moves, fewer when he rests or the
 * screen is dimmed, nothing when hidden. The 680 × 720 picture is fitted to the view, standing on its bottom edge.
 */
final class BodyView extends View implements BodyRig.Pen {
    final BodyRig rig = new BodyRig();
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Runnable tick = this::invalidate;
    private Canvas cv;
    private long last;
    private boolean slow;

    BodyView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setFakeBoldText(true);
    }

    /** Fewer frames (night, dimmed screen). */
    void setSlow(boolean s) { slow = s; }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 1 / 30f : (now - last) / 1000f;
        last = now;
        if (rig.mode() == BodyRig.SPEAKING) {
            double lv = PlaybackLevel.current();
            if (lv >= 0) rig.setVoice(loud(lv));
        }
        rig.update(dt);
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            float sc = Math.min(w / BodyRig.W, h / BodyRig.H);
            canvas.save();
            canvas.translate((w - BodyRig.W * sc) / 2f - BodyRig.LEFT * sc, h - BodyRig.H * sc);
            canvas.scale(sc, sc);
            cv = canvas;
            try { rig.draw(this); } finally { cv = null; canvas.restore(); }
        }
        removeCallbacks(tick);
        if (isShown()) {
            boolean busy = rig.mode() == BodyRig.SPEAKING || rig.mode() == BodyRig.LISTENING;
            postDelayed(tick, slow ? 100 : busy && Ui.animate ? 33 : 50);
        }
    }

    /** RMS of 16-bit audio -> 0..1 (about -50 dB .. -18 dB). */
    private static float loud(double rms) {
        if (rms < 1) return 0f;
        double db = 20 * Math.log10(rms / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 50) / 32));
    }

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) { last = 0; invalidate(); } else removeCallbacks(tick);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    // ================================================================ BodyRig.Pen on an Android canvas
    @Override public void begin() { path.rewind(); }
    @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
    @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
    @Override public void quadTo(float x1, float y1, float x, float y) { path.quadTo(x1, y1, x, y); }
    @Override public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { path.cubicTo(x1, y1, x2, y2, x, y); }
    @Override public void close() { path.close(); }
    @Override public void addCircle(float cx, float cy, float r) { if (r > 0) path.addCircle(cx, cy, r, Path.Direction.CW); }
    @Override public void addOval(float cx, float cy, float rx, float ry) {
        if (rx <= 0 || ry <= 0) return;
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
        path.addOval(oval, Path.Direction.CW);
    }

    @Override public void fill(int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        cv.drawPath(path, fill);
    }

    @Override public void fillLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops) {
        if (x0 == x1 && y0 == y1) { fill(colors[0]); return; }
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP));
        cv.drawPath(path, fill);
        fill.setShader(null);
    }

    @Override public void fillRadial(float cx, float cy, float r, int[] colors, float[] stops) {
        if (r < 0.5f) return; // (a radius of 0 would crash)
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new RadialGradient(cx, cy, r, colors, stops, Shader.TileMode.CLAMP));
        cv.drawPath(path, fill);
        fill.setShader(null);
    }

    @Override public void stroke(int argb, float width) {
        line.setShader(null);
        line.setColor(argb);
        line.setStrokeWidth(width);
        cv.drawPath(path, line);
    }

    @Override public void strokeLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops, float width) {
        if (x0 == x1 && y0 == y1) return;
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(width);
        line.setShader(new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP));
        cv.drawPath(path, line);
        line.setShader(null);
    }

    @Override public void save() { cv.save(); }
    @Override public void restore() { cv.restore(); }
    @Override public void clip() { cv.clipPath(path); }
    @Override public void translate(float dx, float dy) { cv.translate(dx, dy); }
    @Override public void rotate(float degrees) { cv.rotate(degrees); }
    @Override public void scale(float s) { cv.scale(s, s); }

    @Override public void text(String s, float x, float y, float size, int argb) {
        text.setColor(argb);
        text.setTextSize(size);
        cv.drawText(s, x, y, text);
    }
}
