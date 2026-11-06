package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A battery drawn as liquid in a glass pill (his charging idea, 6 Nov: the liquid is only for the charging status, never a
 * switch): red at 15 % or less, amber, green; while charging its surface waves, bubbles rise and a shine passes. It moves
 * only while charging, on the screen and with motion allowed; otherwise it stands still. Lying (a bar) or standing.
 */
final class LiquidPill extends View {
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            bubble = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path(), wave = new Path();
    private final boolean standing;
    private int pct = -1;
    private boolean charging;
    /** Motion allowed (off in battery saver / when he turned motion off). */
    private boolean motion = true;
    private final long born = android.os.SystemClock.uptimeMillis();

    LiquidPill(Context c, boolean standing) {
        super(c);
        this.standing = standing;
        glass.setColor(0x33FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(c, 1));
        edge.setColor(0x66FFFFFF);
        bubble.setColor(0x88FFFFFF);
        shine.setColor(0x33FFFFFF);
    }

    void setMotion(boolean m) {
        if (m == motion) return;
        motion = m;
        invalidate();
    }

    void set(int pct, boolean charging) {
        if (pct == this.pct && charging == this.charging) return;
        this.pct = pct;
        this.charging = charging;
        invalidate();
    }

    private boolean moving() { return charging && motion && pct >= 0 && isShown(); }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = Math.min(w, h) / 2f;
        r.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        cv.drawRoundRect(r, rad, rad, glass);
        if (pct >= 0) {
            float t = (android.os.SystemClock.uptimeMillis() - born) / 1000f;
            int col = MainLook.liquidColour(pct);
            cv.save();
            cv.clipPath(clip);
            float level = Math.max(0.04f, Math.min(1f, pct / 100f));
            wave.reset();
            float amp = moving() ? Math.min(w, h) * 0.06f : 0;
            if (standing) { // the liquid's top line, waving while charging
                float top = h * (1 - level);
                fill.setShader(new LinearGradient(0, top, 0, h, lighter(col), col, Shader.TileMode.CLAMP));
                wave.moveTo(0, h);
                for (int i = 0; i <= 12; i++) {
                    float x = w * i / 12f;
                    wave.lineTo(x, top + (float) Math.sin(t * 3 + i * 0.9) * amp);
                }
                wave.lineTo(w, h);
            } else { // lying: its right edge waves
                float right = w * level;
                fill.setShader(new LinearGradient(0, 0, right, 0, col, lighter(col), Shader.TileMode.CLAMP));
                wave.moveTo(0, 0);
                for (int i = 0; i <= 8; i++) {
                    float y = h * i / 8f;
                    wave.lineTo(right + (float) Math.sin(t * 3 + i * 1.1) * amp, y);
                }
                wave.lineTo(0, h);
            }
            wave.close();
            cv.drawPath(wave, fill);
            if (moving()) { // bubbles rising in the liquid, a shine passing
                for (int i = 0; i < 5; i++) {
                    float ph = (t * (0.35f + i * 0.07f) + i * 0.23f) % 1f;
                    float br = Math.min(w, h) * (0.05f + (i % 3) * 0.025f);
                    float bx, by;
                    if (standing) { bx = w * (0.25f + 0.5f * ((i * 37) % 10) / 10f); by = h - ph * h * level; }
                    else { bx = ph * w * level; by = h * (0.25f + 0.5f * ((i * 37) % 10) / 10f); }
                    cv.drawCircle(bx, by, br, bubble);
                }
                float sx = ((t * 0.4f) % 1.4f - 0.2f) * (standing ? h : w);
                shine.setShader(standing
                        ? new LinearGradient(0, sx - h * 0.12f, 0, sx + h * 0.12f, new int[]{0, 0x44FFFFFF, 0}, null, Shader.TileMode.CLAMP)
                        : new LinearGradient(sx - w * 0.12f, 0, sx + w * 0.12f, 0, new int[]{0, 0x44FFFFFF, 0}, null, Shader.TileMode.CLAMP));
                cv.drawRect(r, shine);
            }
            cv.restore();
        }
        cv.drawRoundRect(r, rad, rad, edge);
        if (moving()) postInvalidateDelayed(33); // (~30 a second, not every screen refresh)
    }

    private static int lighter(int c) { return PageTheme.mix(c, 0xFFFFFFFF, 0.35f); }
}
