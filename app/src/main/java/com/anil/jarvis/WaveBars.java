package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The thin voice bars under the orb in the 🛸 / 🪐 looks: they rise with his voice and Jarvis's voice (when he leaves
 * "🎙️ గొంతుకి స్పందించు" on), and breathe softly otherwise. They move only while on the screen and motion is allowed.
 */
final class WaveBars extends View {
    private static final int N = 44;
    private final float[] h = new float[N];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int c1, c2;
    private float level, shown;
    boolean motion = true;
    /** Listening / speaking: a little taller even in silence. */
    boolean awake;
    private final long born = android.os.SystemClock.uptimeMillis();
    private long last;

    WaveBars(Context c, int c1, int c2) {
        super(c);
        this.c1 = c1;
        this.c2 = c2;
    }

    void level(float l) { level = Math.max(level, Math.max(0, Math.min(1, l))); }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        bar.setShader(new LinearGradient(0, 0, w, 0, new int[]{PageTheme.withAlpha(c1, 0x40), c1, c2, PageTheme.withAlpha(c2, 0x40)},
                new float[]{0, 0.35f, 0.65f, 1}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), hh = getHeight();
        if (w <= 0 || hh <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        float t = (now - born) / 1000f;
        float dt = last == 0 ? 0.033f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        shown += (level - shown) * Math.min(1, dt * 14);
        level = Math.max(0, level - dt * 1.6f);
        float base = awake ? 0.22f : 0.12f;
        float gap = w / N, bw = Math.max(1.5f, gap * 0.45f);
        for (int i = 0; i < N; i++) {
            float mid = 1 - Math.abs(i - (N - 1) / 2f) / (N / 2f); // taller in the middle
            float wave = motion ? 0.5f + 0.5f * (float) Math.sin(t * 5.2f + i * 0.55f) * (float) Math.cos(t * 2.3f + i * 0.21f) : 0.5f;
            float target = base * (0.6f + 0.4f * wave) + shown * mid * (0.45f + 0.55f * wave);
            h[i] += (target - h[i]) * Math.min(1, dt * 18);
            float bh = Math.max(bw, Math.min(1, h[i]) * hh);
            float x = i * gap + (gap - bw) / 2;
            cv.drawRoundRect(x, (hh - bh) / 2, x + bw, (hh + bh) / 2, bw / 2, bw / 2, bar);
        }
        if (motion && isShown()) postInvalidateDelayed(awake || shown > 0.02f || level > 0 ? 33 : 90); // (quiet: a slow breath, few frames)
    }
}
