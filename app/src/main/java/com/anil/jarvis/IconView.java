package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line icons drawn in code so the app needs no image files. */
final class IconView extends View {
    static final int MIC = 0, SEND = 1, STOP = 2, CAMERA = 3, GEAR = 4, TRASH = 5, CHECK = 6, CLOSE = 7, SPEAKER = 8;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int icon;

    IconView(Context c, int icon, int color) {
        super(c);
        this.icon = icon;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setIcon(int icon) { this.icon = icon; invalidate(); }
    void setColor(int color) { p.setColor(color); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f + s * 0.25f, oy = (getHeight() - s) / 2f + s * 0.25f;
        float u = s * 0.5f / 24f; // icon drawn on a 24-unit grid inside the middle half
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(u, u);
        p.setStrokeWidth(2f);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (icon) {
            case MIC:
                r.set(9, 2, 15, 14);
                canvas.drawRoundRect(r, 3, 3, p);
                r.set(5, 6, 19, 17);
                canvas.drawArc(r, 0, 180, false, p);
                canvas.drawLine(12, 17, 12, 21, p);
                canvas.drawLine(8.5f, 21, 15.5f, 21, p);
                break;
            case SEND:
                p.setStrokeWidth(2.4f);
                canvas.drawLine(4, 12, 19, 12, p);
                path.moveTo(13, 6); path.lineTo(19, 12); path.lineTo(13, 18);
                canvas.drawPath(path, p);
                break;
            case STOP:
                p.setStyle(Paint.Style.FILL);
                r.set(6.5f, 6.5f, 17.5f, 17.5f);
                canvas.drawRoundRect(r, 2, 2, p);
                break;
            case CAMERA:
                path.moveTo(4, 8); path.lineTo(7, 8); path.lineTo(9, 5); path.lineTo(15, 5); path.lineTo(17, 8);
                path.lineTo(20, 8); path.lineTo(20, 19); path.lineTo(4, 19); path.close();
                canvas.drawPath(path, p);
                canvas.drawCircle(12, 13.5f, 3.5f, p);
                break;
            case GEAR:
                canvas.drawCircle(12, 12, 3, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    canvas.drawLine(12 + (float) Math.cos(a) * 6.5f, 12 + (float) Math.sin(a) * 6.5f,
                            12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, p);
                }
                canvas.drawCircle(12, 12, 6.5f, p);
                break;
            case TRASH:
                canvas.drawLine(4, 7, 20, 7, p);
                path.moveTo(6, 7); path.lineTo(7, 20); path.lineTo(17, 20); path.lineTo(18, 7);
                canvas.drawPath(path, p);
                canvas.drawLine(10, 11, 10, 17, p);
                canvas.drawLine(14, 11, 14, 17, p);
                path.reset(); path.moveTo(9, 7); path.lineTo(9, 4); path.lineTo(15, 4); path.lineTo(15, 7);
                canvas.drawPath(path, p);
                break;
            case CHECK:
                p.setStrokeWidth(3f);
                path.moveTo(5, 12.5f); path.lineTo(9.5f, 17); path.lineTo(19, 7.5f);
                canvas.drawPath(path, p);
                break;
            case CLOSE:
                canvas.drawLine(6, 6, 18, 18, p);
                canvas.drawLine(18, 6, 6, 18, p);
                break;
            case SPEAKER:
                path.moveTo(11, 5); path.lineTo(6, 9); path.lineTo(3, 9); path.lineTo(3, 15); path.lineTo(6, 15); path.lineTo(11, 19); path.close();
                canvas.drawPath(path, p);
                r.set(9.5f, 8.5f, 16.5f, 15.5f);
                canvas.drawArc(r, -60, 120, false, p);
                r.set(7, 5, 20, 19);
                canvas.drawArc(r, -55, 110, false, p);
                break;
            default:
                break;
        }
        canvas.restore();
    }
}
