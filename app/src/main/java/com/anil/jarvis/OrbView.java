package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** The animated HUD core. Its motion shows what Jarvis is doing. */
final class OrbView extends View {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, OFFLINE = 4;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private int state = IDLE;
    private float level = 0f; // microphone loudness 0..1 while listening
    private final long start = SystemClock.uptimeMillis();

    OrbView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setState(int s) {
        state = s;
        if (s != LISTENING) level = 0;
        invalidate();
    }

    void setLevel(float l) { level = Math.max(0f, Math.min(1f, l)); }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float c = Math.min(w, h) / 2f, cx = w / 2f, cy = h / 2f;
        float R = c * 0.84f;
        double t = (SystemClock.uptimeMillis() - start) / 1000.0;

        int rgb;
        float speed, pulse;
        switch (state) {
            case LISTENING:
                rgb = Ui.GOLD; speed = 0.8f;
                pulse = 0.35f + 0.65f * level;
                break;
            case THINKING:
                rgb = Ui.CYAN; speed = 2.8f;
                pulse = (float) (0.5 + 0.5 * Math.sin(t * 5));
                break;
            case SPEAKING:
                rgb = Ui.CYAN; speed = 0.9f;
                pulse = (float) (0.5 + 0.5 * Math.abs(Math.sin(t * 8.5) * Math.sin(t * 3.1 + 1)));
                break;
            case OFFLINE:
                rgb = Ui.RED; speed = 0.15f; pulse = 0.3f;
                break;
            default:
                rgb = Ui.CYAN; speed = 0.3f;
                pulse = (float) (0.5 + 0.5 * Math.sin(t * 1.4));
        }
        int r = Color.red(rgb), g = Color.green(rgb), b = Color.blue(rgb);

        fill.setShader(new RadialGradient(cx, cy, R * 1.15f,
                new int[]{Color.argb((int) (90 + 70 * pulse), r, g, b), Color.argb(36, r, g, b), Color.argb(0, r, g, b)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, R * 1.15f, fill);
        fill.setShader(null);

        stroke.setColor(Color.argb(230, r, g, b));
        stroke.setStrokeWidth(c * 0.07f);
        oval.set(cx - R, cy - R, cx + R, cy + R);
        int segs = 9;
        float sweep = 360f / segs;
        float rot = (float) ((t * speed * 57.3) % 360);
        for (int i = 0; i < segs; i++) canvas.drawArc(oval, rot + i * sweep, sweep * 0.55f, false, stroke);

        stroke.setStrokeWidth(Math.max(1f, c * 0.02f));
        stroke.setColor(Color.argb(110, r, g, b));
        for (int i = 0; i < 48; i++) {
            double a = -t * speed * 0.5 + i * (Math.PI / 24);
            float r1 = R * 0.7f, r2 = R * (i % 4 == 0 ? 0.84f : 0.77f);
            canvas.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, stroke);
        }
        canvas.drawCircle(cx, cy, R * 0.58f, stroke);

        fill.setColor(state == OFFLINE ? 0xE6FFBEB4 : Color.argb((int) (180 + 75 * pulse), 225, 250, 255));
        canvas.drawCircle(cx, cy, R * (0.2f + 0.12f * pulse), fill);

        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
