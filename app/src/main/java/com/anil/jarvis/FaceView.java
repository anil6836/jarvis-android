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
 * Jarvis's face on the home screen (drawn by FaceRig): it talks with his voice, shows the reply's feeling, listens,
 * thinks, and looks at Anil when the front camera sees him. About 30 frames a second while it is on the screen
 * (15 in battery saver); nothing runs when it is hidden.
 */
final class FaceView extends View implements FaceRig.Pen {
    private final FaceRig rig = new FaceRig();
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Runnable tick = this::invalidate;
    private FaceRig.Look look;
    private boolean holo;
    private int lookColor;
    private Canvas cv;
    private float ox, oy, u;
    private long last;

    FaceView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setStyle(false);
    }

    /** A person (false) or a hologram in the theme colour (true). */
    void setStyle(boolean hologram) {
        holo = hologram;
        lookColor = Ui.CYAN;
        look = hologram ? FaceRig.hologram(Ui.CYAN) : FaceRig.human(Ui.CYAN);
        invalidate();
    }

    void setState(int s) {
        if (s == FaceRig.SPEAKING && rig.mode() != FaceRig.SPEAKING) lastWord = null;
        rig.setMode(s);
    }
    void setMic(float l) { rig.setMic(l); }
    void setVoice(float l) { rig.setVoice(l); }
    void setFeeling(String f) { rig.setFeeling(f); }
    void look(boolean present, float x, float y, float smile) { rig.look(present, x, y, smile); }
    void greet() { rig.greet(); }

    private String lastWord;
    private int lastStart = -1;

    /** The word being spoken now (start..end inside the shown text); the same word reported again is ignored. */
    void word(String text, int start, int end) {
        if (text == null || start < 0 || end > text.length() || start >= end) return;
        if (start == lastStart && text.equals(lastWord)) return; // the natural voice repeats the current word every 120 ms
        lastWord = text;
        lastStart = start;
        rig.word(text.substring(start, end));
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 1 / 30f : (now - last) / 1000f;
        last = now;
        if (lookColor != Ui.CYAN) setStyle(holo); // the theme changed
        if (rig.mode() == FaceRig.SPEAKING) {
            double lv = PlaybackLevel.current();
            if (lv >= 0) rig.setVoice(loud(lv));
        }
        rig.update(dt);
        cv = canvas;
        try { rig.draw(this, getWidth(), getHeight(), look); } finally { cv = null; }
        removeCallbacks(tick);
        if (isShown()) postDelayed(tick, Ui.animate ? 33 : 66);
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

    // ================================================================ FaceRig.Pen on an Android canvas

    private float X(float x) { return ox + x * u; }
    private float Y(float y) { return oy + y * u; }

    @Override public void frame(float ox, float oy, float unit) { this.ox = ox; this.oy = oy; this.u = unit; }
    @Override public void begin() { path.rewind(); }
    @Override public void moveTo(float x, float y) { path.moveTo(X(x), Y(y)); }
    @Override public void lineTo(float x, float y) { path.lineTo(X(x), Y(y)); }
    @Override public void quadTo(float x1, float y1, float x, float y) { path.quadTo(X(x1), Y(y1), X(x), Y(y)); }
    @Override public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { path.cubicTo(X(x1), Y(y1), X(x2), Y(y2), X(x), Y(y)); }
    @Override public void addOval(float cx, float cy, float rx, float ry) {
        oval.set(X(cx - rx), Y(cy - ry), X(cx + rx), Y(cy + ry));
        path.addOval(oval, Path.Direction.CW);
    }
    @Override public void close() { path.close(); }

    @Override public void fill(int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        cv.drawPath(path, fill);
    }

    @Override public void fillLinear(float x0, float y0, float x1, float y1, int c0, int c1) {
        if (x0 == x1 && y0 == y1) { fill(c0); return; }
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(X(x0), Y(y0), X(x1), Y(y1), c0, c1, Shader.TileMode.CLAMP));
        cv.drawPath(path, fill);
        fill.setShader(null);
    }

    @Override public void fillRadial(float cx, float cy, float r, int[] colors, float[] stops) {
        if (r * u < 1f) return; // too small to see (and a radius of 0 would crash)
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new RadialGradient(X(cx), Y(cy), r * u, colors, stops, Shader.TileMode.CLAMP));
        cv.drawPath(path, fill);
        fill.setShader(null);
    }

    @Override public void stroke(int argb, float width) {
        line.setColor(argb);
        line.setStrokeWidth(Math.max(1f, width * u));
        cv.drawPath(path, line);
    }

    @Override public void save() { cv.save(); }
    @Override public void restore() { cv.restore(); }
    @Override public void clip() { cv.clipPath(path); }
    @Override public void translate(float dx, float dy) { cv.translate(dx * u, dy * u); }
    @Override public void rotate(float degrees, float px, float py) { cv.rotate(degrees, X(px), Y(py)); }
}
