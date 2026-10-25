package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * 🪟 Jarvis's window on the car screen: the real Jarvis ({@link BodyView}, the same young man as on the home tablet) in
 * a rounded glass window, two thin rings orbiting it like a planet's, a bright segment that turns while he listens or
 * thinks, the glow in the mode's colour that beats with a song, sound bars under him while he speaks, and a seat belt
 * across his chest while the car moves. Nothing here is a picture: all drawn, every size.
 */
final class CarPortal extends FrameLayout {
    final BodyView body;
    private CarTheme.Look look;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path belt = new Path();
    private final DashPathEffect stitch = new DashPathEffect(new float[]{10, 14}, 0);
    private float beatPulse, pulseAt, orbit;
    private boolean belted, lowMotion;
    private int state = BodyRig.IDLE;
    private final float[] bars = new float[11];
    private long lastFrame;
    private final Runnable frame = this::invalidate;
    // the glow and the glass are gradients: made again only when the size, the colour or the beat's swell changed
    private RadialGradient glow, glass;
    private int glowW, glowH, glowAc, glowStep = -1;
    private boolean glowLight;

    CarPortal(Context c) {
        super(c);
        setWillNotDraw(false);
        body = new BodyView(c);
        addView(body, new LayoutParams(-1, -1));
        body.setClipToOutline(true);
        body.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                int w = v.getWidth(), h = v.getHeight();
                float rad = Math.min(w, h) * 0.42f;
                o.setRoundRect(0, 0, w, h, rad);
            }
        });
    }

    void setLook(CarTheme.Look l) {
        boolean recolour = holo && (look == null || look.accent != l.accent);
        look = l;
        if (recolour) holoLayer();
        invalidate();
    }

    // ---- 29: the hologram look — Jarvis in the theme's light (his own drawing, tinted), fine lines over him, a ring stage
    private boolean holo;
    private final Paint holoPaint = new Paint();
    private RadialGradient cone;
    private int coneW, coneH, coneAc;

    void setHolo(boolean on) {
        if (on == holo) return;
        holo = on;
        if (on) holoLayer(); else body.setLayerType(LAYER_TYPE_NONE, null);
        invalidate();
    }

    /** Jarvis drawn through a filter: his light and shade kept, every colour turned into the theme's light, a little see-through. */
    private void holoLayer() {
        int ac = look == null ? 0xFF4FD2FF : look.accent;
        float r = Color.red(ac) / 255f, g = Color.green(ac) / 255f, b = Color.blue(ac) / 255f;
        float k = 1.25f;
        holoPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                0.30f * r * k, 0.59f * r * k, 0.11f * r * k, 0, 18 * r,
                0.30f * g * k, 0.59f * g * k, 0.11f * g * k, 0, 18 * g,
                0.30f * b * k, 0.59f * b * k, 0.11f * b * k, 0, 18 * b,
                0, 0, 0, 0.82f, 0}));
        body.setLayerType(LAYER_TYPE_HARDWARE, holoPaint);
    }
    void setState(int s) { if (state != s) { state = s; invalidate(); } }
    void setBelted(boolean b) { if (belted != b) { belted = b; invalidate(); } }
    void setLowMotion(boolean b) { lowMotion = b; }

    /** A beat of the song: the glow swells once. */
    void beat() { beatPulse = 1; pulseAt = SystemClock.uptimeMillis(); invalidate(); }

    /** Jarvis's voice level now (0..1): the bars under him. */
    void voice(float level) {
        System.arraycopy(bars, 1, bars, 0, bars.length - 1);
        bars[bars.length - 1] = level;
    }

    private static int inset(int w, int h) { return Math.round(Math.min(w, h) * 0.085f); }

    // Jarvis sits inside the window's edge: measured and laid out with that inset (no second layout pass)
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs), in = inset(w, h);
        body.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - 2 * in), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, h - 2 * in), MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int rt, int bt) {
        int w = rt - l, h = bt - t, in = inset(w, h);
        body.layout(in, in, Math.max(in, w - in), Math.max(in, h - in));
    }

    @Override protected void onDraw(Canvas cv) {
        if (look == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.03f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (!lowMotion) orbit = (orbit + dt * 7.5f) % 3600; // (degrees of the rings' turn; a long wrap: no visible jump)
        int ac = look.accent;
        if (beatPulse > 0) beatPulse = Math.max(0, 1 - (now - pulseAt) / 420f);
        int step = Math.round(beatPulse * 12); // (the swell in 13 steps: a gradient per step, not per frame)
        if (glow == null || glowW != w || glowH != h || glowAc != ac || glowStep != step || glowLight != look.light) {
            float pulse = step / 12f, glowA = 0.35f + 0.65f * pulse;
            glow = new RadialGradient(cx, cy, s * (0.56f + 0.08f * pulse), new int[]{CarTheme.withAlpha(ac, (int) (0x70 * glowA)), CarTheme.withAlpha(ac, (int) (0x30 * glowA)), 0}, new float[]{0.55f, 0.8f, 1}, Shader.TileMode.CLAMP);
            if (glass == null || glowW != w || glowH != h || glowAc != ac || glowLight != look.light)
                glass = new RadialGradient(cx, cy - s * 0.1f, s * 0.6f, new int[]{CarTheme.withAlpha(ac, 0x33), look.light ? 0xFFDCE6F0 : 0xFF07121E}, new float[]{0, 1}, Shader.TileMode.CLAMP);
            glowW = w; glowH = h; glowAc = ac; glowStep = step; glowLight = look.light;
        }
        // the glow behind the window
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255); p.setShader(glow);
        cv.drawRect(0, 0, w, h, p);
        // the window's dark glass (behind Jarvis); the hologram has none: a cone of light from his stage instead
        float inset = s * 0.085f, rad = (s - 2 * inset) * 0.42f;
        if (holo) {
            if (cone == null || coneW != w || coneH != h || coneAc != ac) {
                coneW = w; coneH = h; coneAc = ac;
                cone = new RadialGradient(cx, h - inset, Math.max(1, h * 0.9f), new int[]{CarTheme.withAlpha(ac, 0x55), CarTheme.withAlpha(ac, 0x14), 0}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP);
            }
            p.setAlpha(255);
            p.setShader(cone);
            belt.rewind();
            belt.moveTo(cx - w * 0.12f, inset); belt.lineTo(cx + w * 0.12f, inset); belt.lineTo(cx + w * 0.40f, h - inset); belt.lineTo(cx - w * 0.40f, h - inset); belt.close();
            cv.drawPath(belt, p);
            p.setShader(null);
            super.onDraw(cv);
            return;
        }
        r.set(inset, inset, w - inset, h - inset);
        p.setAlpha(255); p.setShader(glass);
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        super.onDraw(cv);
    }

    @Override protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv); // (Jarvis)
        if (look == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f, inset = s * 0.085f, rad = (s - 2 * inset) * 0.42f;
        int ac = look.accent;
        // the seat belt over his chest (the body's own coordinates: rig units -> this view's pixels)
        if (belted && body.getWidth() > 0 && body.getHeight() > 0) {
            float bw = body.getWidth(), bh = body.getHeight();
            float sc = Math.min(bw / BodyRig.W, bh / BodyRig.H);
            float ox = body.getLeft() + (bw - BodyRig.W * sc) / 2f - BodyRig.LEFT * sc, oy = body.getTop() + bh - BodyRig.H * sc;
            cv.save();
            r.set(body.getLeft(), body.getTop(), body.getRight(), body.getBottom());
            belt.rewind();
            belt.addRoundRect(r, rad, rad, Path.Direction.CW);
            cv.clipPath(belt);
            cv.translate(ox, oy);
            cv.scale(sc, sc);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(34);
            p.setColor(0xF22A3340);
            cv.drawLine(150, 470, 470, 760, p);
            p.setStrokeWidth(3);
            p.setColor(0x80A9C4D8);
            p.setPathEffect(stitch);
            cv.drawLine(150, 470, 470, 760, p);
            p.setPathEffect(null);
            cv.restore();
        }
        long now0 = SystemClock.uptimeMillis();
        if (holo) holoOver(cv, w, h, s, cx, inset, ac, now0);
        // the window's lit edge
        r.set(inset, inset, w - inset, h - inset);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, s * 0.006f));
        p.setColor(CarTheme.withAlpha(ac, 0x66));
        if (!holo) cv.drawRoundRect(r, rad, rad, p);
        // the orbit rings (two tilted ellipses, one in front, one behind — drawn here as thin lines over the glass edge)
        for (int k = 0; !holo && k < 2; k++) {
            cv.save();
            cv.rotate(k == 0 ? -18 + 14 * (float) Math.sin(Math.toRadians(orbit * 0.5)) : 24 - 10 * (float) Math.sin(Math.toRadians(orbit * 0.3)), cx, cy); // (a slow wobble, whole cycles inside the 3600° wrap)
            r.set(cx - s * 0.53f, cy - s * (k == 0 ? 0.13f : 0.2f), cx + s * 0.53f, cy + s * (k == 0 ? 0.13f : 0.2f));
            p.setStrokeWidth(Math.max(1, s * (k == 0 ? 0.006f : 0.004f)));
            p.setColor(CarTheme.withAlpha(ac, k == 0 ? 0x66 : 0x33));
            cv.drawOval(r, p);
            cv.restore();
        }
        // the state segment: an arc that turns while he listens / thinks, a full faint ring at rest, red when offline
        float seg = state == BodyRig.LISTENING ? 110 : state == BodyRig.THINKING ? 60 : state == BodyRig.SPEAKING ? 220 : 0;
        r.set(inset * 0.35f, inset * 0.35f, w - inset * 0.35f, h - inset * 0.35f);
        p.setStrokeWidth(Math.max(2, s * 0.012f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(CarTheme.withAlpha(state == BodyRig.OFFLINE ? 0xFFFF5A4E : ac, 0x33));
        cv.drawArc(r, 0, 360, false, p);
        if (seg > 0) {
            p.setColor(state == BodyRig.THINKING ? 0xFFFFC66D : state == BodyRig.LISTENING ? 0xFF7DFFC8 : Color.WHITE);
            cv.drawArc(r, -90 + (orbit * (state == BodyRig.THINKING ? 6 : 3)) % 360, seg, false, p);
        }
        // the sound bars under him while he speaks
        if (state == BodyRig.SPEAKING) {
            float bw = s * 0.022f, gap = s * 0.012f, total = bars.length * bw + (bars.length - 1) * gap, x = cx - total / 2, base = h - inset * 0.55f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(CarTheme.withAlpha(ac, 0xCC));
            for (int i = 0; i < bars.length; i++) {
                float hgt = s * (0.012f + 0.08f * bars[i]);
                r.set(x, base - hgt, x + bw, base);
                cv.drawRoundRect(r, bw / 2, bw / 2, p);
                x += bw + gap;
            }
        }
        boolean moving = state != BodyRig.IDLE || beatPulse > 0 || !lowMotion;
        removeCallbacks(frame); // (one loop only: an outside invalidate never starts a second one)
        if (moving && isShown()) postOnAnimationDelayed(frame, state == BodyRig.IDLE && beatPulse == 0 ? 66 : 33);
    }

    /** Over the hologram: fine lines across him, a brighter band sliding down now and then, the ring stage under him (brighter while he speaks). */
    private void holoOver(Canvas cv, int w, int h, float s, float cx, float inset, int ac, long now) {
        if (body.getWidth() > 0) {
            int sv = cv.save();
            cv.clipRect(body.getLeft(), body.getTop(), body.getRight(), body.getBottom());
            p.setStyle(Paint.Style.FILL);
            p.setColor(CarTheme.withAlpha(0xFF000000, 0x30));
            float step = Math.max(3, s / 90f);
            for (float y = body.getTop(); y < body.getBottom(); y += step) cv.drawRect(body.getLeft(), y, body.getRight(), y + step * 0.35f, p);
            if (!lowMotion) {
                float cyc = (now % 3600) / 3600f, by = body.getTop() + (body.getBottom() - body.getTop()) * cyc * 1.2f;
                p.setColor(CarTheme.withAlpha(ac, 0x28));
                cv.drawRect(body.getLeft(), by, body.getRight(), by + s * 0.05f, p);
            }
            cv.restoreToCount(sv);
        }
        float gy = h - inset * 0.9f, rx = w * 0.36f;
        float talk = state == BodyRig.SPEAKING ? 1 : 0, pulse = lowMotion ? 0 : (float) Math.sin(now / 700.0);
        p.setStyle(Paint.Style.STROKE);
        float[] ks = {1f, 0.72f, 0.45f};
        int[] al = {0x55, 0x8C, 0xE6};
        for (int i = 0; i < 3; i++) {
            float k = ks[i] * (1 + 0.04f * pulse);
            p.setStrokeWidth(Math.max(1.5f, s * 0.008f));
            p.setColor(CarTheme.withAlpha(ac, Math.min(255, al[i] + Math.round(0x30 * talk))));
            r.set(cx - rx * k, gy - rx * k * 0.2f, cx + rx * k, gy + rx * k * 0.2f);
            cv.drawOval(r, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(CarTheme.withAlpha(0xFFFFFFFF, 0x66));
        r.set(cx - rx * 0.36f, gy - rx * 0.05f, cx + rx * 0.36f, gy + rx * 0.05f);
        cv.drawOval(r, p);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(frame);
    }
}
