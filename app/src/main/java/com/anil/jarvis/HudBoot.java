package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

/**
 * The first moment Jarvis opens: rings draw themselves round the middle and "JARVIS ఆన్‌లైన్" appears, then it all
 * fades away (about 1.3 seconds). Once per app start; a tap skips it.
 */
final class HudBoot extends View {
    static boolean shown;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint();
    private final RectF oval = new RectF();
    private long start; // set at the first frame (the screen may take a moment to appear)
    private static final long TOTAL = 1300;

    HudBoot(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ui.RING);
        ring.setStrokeWidth(Ui.dp(c, 2));
        mark.setStyle(Paint.Style.STROKE);
        mark.setColor(Ui.MARK);
        mark.setStrokeWidth(Ui.dp(c, 2.5f));
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setLetterSpacing(0.4f);
        text.setTextSize(Ui.dp(c, 26));
        sub.setColor(Ui.MARK);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTextSize(Ui.dp(c, 14));
        dim.setColor(Ui.BG_BOTTOM);
        setOnClickListener(v -> remove());
    }

    private void remove() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override protected void onDraw(Canvas c) {
        if (start == 0) start = SystemClock.uptimeMillis();
        float t = (SystemClock.uptimeMillis() - start) / (float) TOTAL;
        if (t >= 1f) { post(this::remove); return; }
        float fade = t < 0.75f ? 1f : 1f - (t - 0.75f) / 0.25f;
        int a = Math.round(255 * fade);
        dim.setAlpha(Math.round(235 * fade));
        c.drawRect(0, 0, getWidth(), getHeight(), dim);
        float cx = getWidth() / 2f, cy = getHeight() / 2f, d = Math.min(getWidth(), getHeight());
        float grow = Math.min(1f, t / 0.55f);
        float[] radii = {d * 0.18f, d * 0.26f, d * 0.33f};
        for (int i = 0; i < radii.length; i++) {
            float r = radii[i];
            oval.set(cx - r, cy - r, cx + r, cy + r);
            ring.setAlpha(Math.round(a * (0.9f - i * 0.2f)));
            c.drawArc(oval, -90 + i * 40 + t * 120 * (i % 2 == 0 ? 1 : -1), 360 * grow, false, ring);
        }
        mark.setAlpha(a);
        float r = radii[2] + Ui.dp(getContext(), 8);
        for (int i = 0; i < 24 * grow; i++) {
            double rad = Math.toRadians(i * 15 - 90);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            c.drawLine(cx + cos * r, cy + sin * r, cx + cos * (r + Ui.dp(getContext(), 7)), cy + sin * (r + Ui.dp(getContext(), 7)), mark);
        }
        if (t > 0.3f) {
            float show = Math.min(1f, (t - 0.3f) / 0.25f) * fade;
            text.setAlpha(Math.round(255 * show));
            sub.setAlpha(Math.round(255 * show));
            c.drawText("JARVIS", cx, cy + text.getTextSize() * 0.35f, text);
            c.drawText("ఆన్‌లైన్", cx, cy + text.getTextSize() * 1.6f, sub);
        }
        postInvalidateOnAnimation();
    }
}
