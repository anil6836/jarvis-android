package com.anil.jarvis.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * W2: Jarvis's living orb on the watch. Idle: it breathes slowly. Listening: its rings move with his voice.
 * Understanding / thinking: arcs spin round it. Speaking: it glows with Jarvis's voice. Something wrong: it turns red.
 * (Drawn shapes only: no suit, helmet or logo.)
 */
final class OrbView extends View {
    private static final int CYAN = 0xFF74E4FF, BLUE = 0xFF3B82F6, VIOLET = 0xFFA78BFA, RED = 0xFFF87171, WHITE = 0xFFFFFFFF;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float shown;       // the level as drawn (smoothed)
    private float appear = 1f; // 0..1 while the boot animation brings it in
    private final long born = SystemClock.elapsedRealtime();
    private final float dp;

    OrbView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
    }

    void setAppear(float a) { appear = Math.max(0f, Math.min(1f, a)); }

    @Override protected void onDraw(Canvas c) {
        int s = Talk.state;
        float level = s == Talk.SPEAKING ? Player.level : s == Talk.LISTENING ? Mic.level : 0f;
        shown += (level - shown) * 0.25f;
        float t = (SystemClock.elapsedRealtime() - born) / 1000f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r0 = Math.min(getWidth(), getHeight()) * 0.30f * (0.4f + 0.6f * appear);
        int main = s == Talk.ERROR ? RED : s == Talk.THINKING ? VIOLET : CYAN;
        int alphaAll = Math.round(255 * appear);

        // the glow behind (strongest while speaking)
        float glowR = r0 * (1.25f + (s == Talk.SPEAKING ? shown * 0.55f : 0.05f * (float) Math.sin(t * 2)));
        fill.setShader(new RadialGradient(cx, cy, Math.max(1f, glowR),
                new int[]{withAlpha(main, s == Talk.SPEAKING ? 0x70 : 0x38), withAlpha(main, 0x14), Color.TRANSPARENT},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        fill.setAlpha(alphaAll);
        c.drawCircle(cx, cy, glowR, fill);

        // the core: breathing, a little bigger with the voice
        float breath = (float) Math.sin(t * (s == Talk.IDLE ? 1.6f : 3.2f)) * 0.04f;
        float coreR = r0 * (0.42f + breath + shown * 0.16f);
        if (s == Talk.UNDERSTANDING || s == Talk.THINKING) coreR *= 0.9f;
        fill.setShader(new RadialGradient(cx, cy, Math.max(1f, coreR),
                new int[]{WHITE, withAlpha(main, 0xEE), withAlpha(BLUE, 0x55), Color.TRANSPARENT},
                new float[]{0f, 0.35f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        fill.setAlpha(alphaAll);
        c.drawCircle(cx, cy, coreR, fill);
        fill.setShader(null);

        // three broken rings, each turning its own way; they swell with his voice / Jarvis's voice
        for (int i = 0; i < 3; i++) {
            float wobble = shown * r0 * 0.22f * (i + 1) / 3f * (0.6f + 0.4f * (float) Math.sin(t * 9 + i * 1.7f));
            float rr = r0 * (0.68f + i * 0.17f) + wobble;
            float speed = (i % 2 == 0 ? 1 : -1) * (14f + i * 9f) * (s == Talk.IDLE ? 1f : 2.2f);
            float start = (t * speed + i * 40f) % 360f;
            ring.setStrokeWidth(dp * (2.2f - i * 0.5f));
            ring.setColor(withAlpha(main, Math.round((0xCC - i * 0x30) * appear)));
            box.set(cx - rr, cy - rr, cx + rr, cy + rr);
            int parts = 3 + i;
            float seg = 360f / parts;
            for (int k = 0; k < parts; k++) c.drawArc(box, start + k * seg, seg * 0.62f, false, ring);
        }

        // understanding / thinking: two bright arcs racing round
        if (s == Talk.UNDERSTANDING || s == Talk.THINKING) {
            float rr = r0 * 1.12f;
            box.set(cx - rr, cy - rr, cx + rr, cy + rr);
            ring.setStrokeWidth(dp * 3f);
            ring.setColor(withAlpha(s == Talk.THINKING ? VIOLET : CYAN, alphaAll));
            float a = (t * 300f) % 360f;
            c.drawArc(box, a, 70f, false, ring);
            c.drawArc(box, a + 180f, 70f, false, ring);
        }
        if (s == Talk.IDLE && appear >= 1f) postInvalidateDelayed(50); // (idle: a slower breath saves battery)
        else postInvalidateOnAnimation();
    }

    private static int withAlpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00FFFFFF);
    }
}
