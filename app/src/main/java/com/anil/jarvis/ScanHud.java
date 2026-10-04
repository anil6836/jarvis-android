package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Jarvis camera's HUD over the picture: corner brackets, a scan line sweeping down while Jarvis looks, and what he
 * found: a glowing box around each thing with its number and a floating name tag (red and pulsing for a fault). The
 * boxes are in the picture's own 0..1 space and are drawn into the picture's place on the screen (content), moved by
 * the AR shift while the phone moves a little. hit() tells which thing is under a tap.
 */
final class ScanHud extends View {
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            tag = new Paint(Paint.ANTI_ALIAS_FLAG), scan = new Paint(), corner = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<ScanBrain.Item> items = new ArrayList<>();
    private final RectF content = new RectF();
    private boolean scanning, labels = true;
    private long scanStart;
    private float shiftX, shiftY, fade = 1f;
    private int selected = -1;
    private String hint = "";

    ScanHud(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(2.5f * d);
        corner.setColor(Ui.alpha(Ui.CYAN, 0xB0));
        text.setColor(0xFFFFFFFF);
        text.setTextSize(12.5f * d);
        text.setFakeBoldText(true);
        tag.setColor(0xD9061424);
        dot.setStyle(Paint.Style.FILL);
    }

    /** Where the picture is on the screen (the whole camera area, or a gallery photo fitted inside it). */
    void setContent(RectF r) { content.set(r); invalidate(); }

    void setItems(List<ScanBrain.Item> l) {
        items.clear();
        if (l != null) for (ScanBrain.Item x : l) if (x.box != null) items.add(x);
        selected = -1;
        shiftX = shiftY = 0;
        fade = 1f;
        invalidate();
    }

    boolean hasItems() { return !items.isEmpty(); }

    void clear() { setItems(null); }

    void select(int n) { selected = n; invalidate(); }

    void showLabels(boolean on) { labels = on; invalidate(); }

    /** The AR shift (pixels) and how much to show (1 = all, 0 = lost). */
    void setShift(float x, float y, float alpha) { shiftX = x; shiftY = y; fade = alpha; invalidate(); }

    void scanning(boolean on) {
        scanning = on;
        scanStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** A small line at the top centre (the mode, a guided step...). */
    void hint(String h) { hint = h == null ? "" : h; invalidate(); }

    private RectF screenBox(ScanBrain.Item x) {
        float l = content.left + x.box[0] * content.width() + shiftX, t = content.top + x.box[1] * content.height() + shiftY;
        float r = content.left + x.box[2] * content.width() + shiftX, b = content.top + x.box[3] * content.height() + shiftY;
        return new RectF(l, t, r, b);
    }

    private static int colorOf(ScanBrain.Item x) {
        if (x.fault) return 0xFFFF4D5E;
        String s = x.section == null ? "" : x.section;
        if (s.isEmpty()) return Ui.CYAN;
        int[] pal = {0xFF22D3EE, 0xFFF2B24C, 0xFF6BE3A4, 0xFFB794F6, 0xFFFF8FAB, 0xFF60A5FA, 0xFFFACC15};
        return pal[Math.abs(s.hashCode()) % pal.length];
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        // corner brackets of the viewfinder
        float m = 18 * d, L = 34 * d;
        RectF f = content.width() > 0 ? content : new RectF(0, 0, w, h);
        float top = Math.max(f.top, 90 * d), bottom = Math.min(f.bottom, h - 40 * d);
        Path p = new Path();
        p.moveTo(f.left + m, top + L); p.lineTo(f.left + m, top); p.lineTo(f.left + m + L, top);
        p.moveTo(f.right - m - L, top); p.lineTo(f.right - m, top); p.lineTo(f.right - m, top + L);
        p.moveTo(f.left + m, bottom - L); p.lineTo(f.left + m, bottom); p.lineTo(f.left + m + L, bottom);
        p.moveTo(f.right - m - L, bottom); p.lineTo(f.right - m, bottom); p.lineTo(f.right - m, bottom - L);
        c.drawPath(p, corner);
        long now = SystemClock.uptimeMillis();
        if (scanning) { // a bright line sweeping down with a fading trail, and a fine grid
            float period = 1600f, t = ((now - scanStart) % (long) period) / period;
            float y = top + (bottom - top) * t;
            scan.setShader(new LinearGradient(0, y - 120 * d, 0, y, 0x0022D3EE, Ui.alpha(Ui.CYAN, 0x66), Shader.TileMode.CLAMP));
            c.drawRect(f.left, Math.max(top, y - 120 * d), f.right, y, scan);
            line.setColor(Ui.CYAN);
            line.setStrokeWidth(2.5f * d);
            c.drawLine(f.left + m, y, f.right - m, y, line);
            Paint g = new Paint();
            g.setColor(Ui.alpha(Ui.CYAN, 0x18));
            g.setStrokeWidth(1);
            for (float gx = f.left; gx < f.right; gx += 28 * d) c.drawLine(gx, top, gx, bottom, g);
            for (float gy = top; gy < bottom; gy += 28 * d) c.drawLine(f.left, gy, f.right, gy, g);
        }
        if (fade > 0.02f) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 220.0);
            for (ScanBrain.Item x : items) {
                RectF r = screenBox(x);
                int col = colorOf(x);
                boolean sel = x.n == selected;
                int a = (int) (255 * fade * (x.fault ? 0.55f + 0.45f * pulse : 1f));
                fill.setColor(Ui.alpha(col, (int) ((sel ? 0x40 : 0x18) * fade)));
                c.drawRoundRect(r, 6 * d, 6 * d, fill);
                line.setColor(Ui.alpha(col, a));
                line.setStrokeWidth((sel ? 3.2f : 2f) * d);
                c.drawRoundRect(r, 6 * d, 6 * d, line);
                // number badge on the corner
                dot.setColor(Ui.alpha(col, a));
                float br = 9 * d;
                c.drawCircle(r.left, r.top, br, dot);
                text.setColor(Color.argb((int) (255 * fade), 2, 10, 18));
                text.setTextSize(10.5f * d);
                String num = String.valueOf(x.n);
                c.drawText(num, r.left - text.measureText(num) / 2, r.top + 3.8f * d, text);
                if (!labels && !sel) continue;
                // the floating name tag above the box (below when there is no room), with a short leader line
                String label = x.name + (x.value.isEmpty() ? "" : " · " + x.value);
                if (label.length() > 28) label = label.substring(0, 27) + "…";
                text.setTextSize((sel ? 13.5f : 12f) * d);
                float tw = text.measureText(label) + 14 * d, th = 22 * d;
                float tx = Math.max(4 * d, Math.min(w - tw - 4 * d, r.centerX() - tw / 2));
                float ty = r.top - th - 10 * d;
                if (ty < top + 4 * d) ty = r.bottom + 10 * d;
                tag.setColor(Color.argb((int) (0xD9 * fade), 6, 20, 36));
                RectF tr = new RectF(tx, ty, tx + tw, ty + th);
                c.drawRoundRect(tr, 8 * d, 8 * d, tag);
                line.setStrokeWidth(1.2f * d);
                c.drawRoundRect(tr, 8 * d, 8 * d, line);
                c.drawLine(r.centerX(), ty < r.top ? r.top : r.bottom, Math.max(tr.left + 6 * d, Math.min(tr.right - 6 * d, r.centerX())),
                        ty < r.top ? tr.bottom : tr.top, line);
                text.setColor(Color.argb((int) (255 * fade), 255, 255, 255));
                c.drawText(label, tx + 7 * d, ty + 15.5f * d, text);
            }
        }
        if (!hint.isEmpty()) {
            text.setTextSize(12.5f * d);
            text.setColor(Ui.CYAN);
            float tw = text.measureText(hint);
            c.drawText(hint, (w - tw) / 2, top + 22 * d, text);
        }
        if (scanning || (!items.isEmpty() && fade > 0.02f)) postInvalidateOnAnimation(); // the sweep and the fault pulse move
    }

    /** The thing under this point (the smallest box), or null; it is marked as chosen. */
    ScanBrain.Item hit(float px, float py) {
        if (items.isEmpty() || fade < 0.3f) return null;
        ScanBrain.Item best = null;
        float bestArea = Float.MAX_VALUE;
        for (ScanBrain.Item x : items) {
            RectF r = screenBox(x);
            r.inset(-8 * d, -8 * d);
            if (r.contains(px, py) && r.width() * r.height() < bestArea) { bestArea = r.width() * r.height(); best = x; }
        }
        if (best != null) { selected = best.n; invalidate(); }
        return best;
    }

    /** The picture with the boxes and tags drawn on it (for sharing). */
    static android.graphics.Bitmap withBoxes(android.graphics.Bitmap photo, List<ScanBrain.Item> list, float density) {
        android.graphics.Bitmap out = photo.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        float s = Math.max(1f, out.getWidth() / 400f);
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStyle(Paint.Style.STROKE);
        ln.setStrokeWidth(2f * s);
        tx.setTextSize(11f * s);
        tx.setColor(0xFFFFFFFF);
        tx.setFakeBoldText(true);
        bg.setColor(0xD9061424);
        for (ScanBrain.Item x : list) {
            if (x.box == null) continue;
            RectF r = new RectF(x.box[0] * out.getWidth(), x.box[1] * out.getHeight(), x.box[2] * out.getWidth(), x.box[3] * out.getHeight());
            ln.setColor(colorOf(x));
            c.drawRoundRect(r, 4 * s, 4 * s, ln);
            String label = x.n + ". " + x.name;
            if (label.length() > 26) label = label.substring(0, 25) + "…";
            float tw = tx.measureText(label) + 8 * s;
            float ty = r.top - 16 * s < 0 ? r.bottom + 2 * s : r.top - 16 * s;
            c.drawRect(r.left, ty, r.left + tw, ty + 15 * s, bg);
            c.drawText(label, r.left + 4 * s, ty + 11.5f * s, tx);
        }
        return out;
    }
}
