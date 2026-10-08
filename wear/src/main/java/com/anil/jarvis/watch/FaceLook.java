package com.anil.jarvis.watch;

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
 * W3: Jarvis's talking face on the watch instead of the orb (his choice on the phone: a hologram in the theme colour
 * or the phone's own face): it listens, thinks, and moves its lips with Jarvis's voice. Drawn by the same FaceRig as
 * the phone's face. ~20 frames a second while shown.
 */
final class FaceLook extends View implements FaceRig.Pen {
    private final FaceRig rig = new FaceRig();
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private FaceRig.Look look;
    private String lookFor = "";
    private Canvas cv;
    private float ox, oy, u;
    private long last;
    private int shownState = -1;
    private long lookAt;
    private String lookName = "holo";

    FaceLook(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 1 / 20f : Math.min(0.2f, (now - last) / 1000f);
        last = now;
        if (now - lookAt > 2000) { lookAt = now; lookName = Link.look(getContext()); } // (not every frame)
        String want = lookName + Theme.accent;
        if (!want.equals(lookFor)) {
            lookFor = want;
            look = "human".equals(lookName) ? FaceRig.human(Theme.accent) : FaceRig.hologram(Theme.accent);
        }
        int s = Talk.state;
        if (s != shownState) {
            shownState = s;
            rig.setMode(s == Talk.LISTENING ? FaceRig.LISTENING : s == Talk.SPEAKING ? FaceRig.SPEAKING
                    : s == Talk.UNDERSTANDING || s == Talk.THINKING ? FaceRig.THINKING : FaceRig.IDLE);
        }
        if (s == Talk.LISTENING) rig.setMic(Mic.level);
        if (s == Talk.SPEAKING) rig.setVoice(Player.level);
        rig.update(dt);
        cv = canvas;
        try { rig.draw(this, getWidth(), getHeight(), look); } finally { cv = null; }
        postInvalidateDelayed(s == Talk.IDLE ? 66 : 50);
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
        if (r * u < 1f) return;
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
