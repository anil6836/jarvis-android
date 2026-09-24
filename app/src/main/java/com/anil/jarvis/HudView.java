package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** JARVIS heads-up rings: slowly rotating arcs and tick marks around the arc-reactor orb. */
final class HudView extends View {
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final long born = System.nanoTime();

    HudView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(1.6f * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Ui.CYAN2);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(1f * d);
        tick.setColor(Ui.CYAN_DIM);
        gold.setStyle(Paint.Style.STROKE);
        gold.setStrokeWidth(2f * d);
        gold.setStrokeCap(Paint.Cap.ROUND);
        gold.setColor(Ui.GOLD);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - arc.getStrokeWidth() * 2;
        float t = (System.nanoTime() - born) / 1e9f;

        // outer tick ring
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6);
            float len = i % 5 == 0 ? r * 0.10f : r * 0.05f;
            c.drawLine(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                    cx + (float) Math.cos(a) * (r - len), cy + (float) Math.sin(a) * (r - len), tick);
        }
        // rotating arcs
        float r1 = r * 0.86f;
        box.set(cx - r1, cy - r1, cx + r1, cy + r1);
        float a1 = (t * 40) % 360;
        c.drawArc(box, a1, 70, false, arc);
        c.drawArc(box, a1 + 120, 70, false, arc);
        c.drawArc(box, a1 + 240, 70, false, arc);
        float r2 = r * 0.74f;
        box.set(cx - r2, cy - r2, cx + r2, cy + r2);
        float a2 = 360 - (t * 25) % 360;
        c.drawArc(box, a2, 30, false, gold);
        c.drawArc(box, a2 + 180, 30, false, gold);
        postInvalidateOnAnimation();
    }
}
