package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A case board: the case's cards floating on a dark board (our own look), with Jarvis's links drawn between them. Drag a
 * card to move it, flick it off to hide it, pinch to zoom the whole board, drag the empty board to pan, tap a card to
 * open it, hold for its menu. Cards outside the filter fade.
 */
final class BoardView extends View {
    interface Listener {
        void open(JSONObject card);
        void menu(JSONObject card);
        void hidden(JSONObject card);
        void moved();
    }

    Listener listener;
    private final float d;
    private JSONArray cards = new JSONArray(), links = new JSONArray();
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private Set<String> pass; // null = all
    private float scale = 1f, panX, panY;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG), icon = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private JSONObject drag;
    private float dragDx, dragDy;
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;
    private static final float CARD_W = 150, CARD_H = 112; // dp, before the board's zoom

    BoardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        title.setTextSize(13 * d);
        title.setColor(0xFFFFFFFF);
        title.setFakeBoldText(true);
        small.setTextSize(11 * d);
        small.setColor(0xFFC9D6DF);
        icon.setTextSize(16 * d);
        setBackgroundColor(0xFF04101B);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                float ns = Math.max(0.5f, Math.min(2.2f, scale * g.getScaleFactor()));
                // zoom around the fingers
                float fx = g.getFocusX(), fy = g.getFocusY();
                panX = fx - (fx - panX) * ns / scale;
                panY = fy - (fy - panY) * ns / scale;
                scale = ns;
                invalidate();
                return true;
            }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                drag = hit(e.getX(), e.getY());
                if (drag != null) { float[] p = pos(drag); dragDx = e.getX() - p[0]; dragDy = e.getY() - p[1]; }
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (drag != null) {
                    float[] bp = boardOf(b.getX() - dragDx, b.getY() - dragDy);
                    try { drag.put("x", Math.max(-0.2, Math.min(1.2, bp[0]))).put("y", Math.max(-0.2, Math.min(1.2, bp[1]))); } catch (Exception ignored) {}
                } else { panX -= dx; panY -= dy; }
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (drag != null && Math.hypot(vx, vy) > 2600 * d / 2) { // flicked off the board
                    JSONObject c = drag;
                    drag = null;
                    try { c.put("hidden", true); } catch (Exception ignored) {}
                    if (listener != null) listener.hidden(c);
                    invalidate();
                    return true;
                }
                return false;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                JSONObject c = hit(e.getX(), e.getY());
                drag = null;
                if (c != null && listener != null) listener.open(c);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                JSONObject c = hit(e.getX(), e.getY());
                if (c != null && listener != null) { drag = null; listener.menu(c); }
            }
        });
    }

    void setCase(JSONArray cards, JSONArray links) {
        this.cards = cards == null ? new JSONArray() : cards;
        this.links = links == null ? new JSONArray() : links;
        invalidate();
    }

    void setThumb(String cardId, Bitmap b) { if (b != null) thumbs.put(cardId, b); invalidate(); }

    /** Which cards pass the filter (null: all). */
    void setFilter(Set<String> ids) { pass = ids; invalidate(); }

    void reset() { scale = 1f; panX = panY = 0; invalidate(); }

    // ---- places: a card's (x, y) are fractions of a board twice the screen's size
    private float[] pos(JSONObject c) {
        float bw = getWidth() * 2f, bh = getHeight() * 2f;
        return new float[]{panX + ((float) c.optDouble("x", 0.5) * bw - getWidth() / 2f) * scale, panY + ((float) c.optDouble("y", 0.5) * bh - getHeight() / 2f) * scale};
    }

    private float[] boardOf(float sx, float sy) {
        float bw = getWidth() * 2f, bh = getHeight() * 2f;
        return new float[]{((sx - panX) / scale + getWidth() / 2f) / bw, ((sy - panY) / scale + getHeight() / 2f) / bh};
    }

    private JSONObject hit(float x, float y) {
        float w = CARD_W * d * scale, h = CARD_H * d * scale;
        for (int i = cards.length() - 1; i >= 0; i--) {
            JSONObject c = cards.optJSONObject(i);
            if (c == null || c.optBoolean("hidden")) continue;
            float[] p = pos(c);
            if (x >= p[0] - w / 2 && x <= p[0] + w / 2 && y >= p[1] - h / 2 && y <= p[1] + h / 2) return c;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) taps.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (drag != null && listener != null) listener.moved();
            drag = null;
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        // a faint grid that moves with the board
        line.setColor(0x12FFFFFF);
        line.setStrokeWidth(1);
        float g = 48 * d * scale;
        for (float x = panX % g; x < W; x += g) c.drawLine(x, 0, x, H, line);
        for (float y = panY % g; y < H; y += g) c.drawLine(0, y, W, y, line);
        float w = CARD_W * d * scale, h = CARD_H * d * scale;
        // links
        for (int i = 0; i < links.length(); i++) {
            JSONArray l = links.optJSONArray(i);
            if (l == null) continue;
            JSONObject a = cards.optJSONObject(l.optInt(0)), b = cards.optJSONObject(l.optInt(1));
            if (a == null || b == null || a.optBoolean("hidden") || b.optBoolean("hidden")) continue;
            boolean dimmed = pass != null && (!pass.contains(a.optString("id")) || !pass.contains(b.optString("id")));
            float[] pa = pos(a), pb = pos(b);
            line.setColor(dimmed ? 0x33FFB84D : 0xCCFFB84D);
            line.setStrokeWidth(1.6f * d);
            Path p = new Path();
            p.moveTo(pa[0], pa[1]);
            float mx = (pa[0] + pb[0]) / 2, my = (pa[1] + pb[1]) / 2 - 30 * d * scale;
            p.quadTo(mx, my, pb[0], pb[1]);
            c.drawPath(p, line);
            String why = l.optString(2, "");
            if (!why.isEmpty() && !dimmed && scale > 0.7f) {
                if (why.length() > 26) why = why.substring(0, 25) + "…";
                float tw = small.measureText(why) + 10 * d;
                float lx = (pa[0] + 2 * mx + pb[0]) / 4, ly = (pa[1] + 2 * my + pb[1]) / 4;
                r.set(lx - tw / 2, ly - 9 * d, lx + tw / 2, ly + 7 * d);
                fill.setColor(0xE6122033);
                c.drawRoundRect(r, 6 * d, 6 * d, fill);
                small.setColor(0xFFFFD08A);
                c.drawText(why, lx - tw / 2 + 5 * d, ly + 3 * d, small);
                small.setColor(0xFFC9D6DF);
            }
        }
        // cards
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null || card.optBoolean("hidden")) continue;
            boolean dimmed = pass != null && !pass.contains(card.optString("id"));
            float[] p = pos(card);
            r.set(p[0] - w / 2, p[1] - h / 2, p[0] + w / 2, p[1] + h / 2);
            if (r.right < -w || r.left > W + w || r.bottom < -h || r.top > H + h) continue;
            int alpha = dimmed ? 60 : 255;
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, Ui.alpha(0xFF0E2238, alpha), Ui.alpha(0xFF081626, alpha), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 10 * d, 10 * d, fill);
            fill.setShader(null);
            boolean fault = card.optBoolean("fault");
            line.setColor(Ui.alpha(fault ? Ui.C_AMBER : card == drag ? 0xFFFFFFFF : Ui.CYAN, dimmed ? 50 : 190));
            line.setStrokeWidth((card == drag ? 2.2f : 1.2f) * d);
            c.drawRoundRect(r, 10 * d, 10 * d, line);
            // picture or icon
            Bitmap th = thumbs.get(card.optString("id"));
            float top = r.top + 6 * d * scale;
            if (th != null && !th.isRecycled()) {
                RectF pr = new RectF(r.left + 6 * d * scale, top, r.right - 6 * d * scale, top + 48 * d * scale);
                img.setAlpha(alpha);
                c.save();
                Path clip = new Path();
                clip.addRoundRect(pr, 6 * d, 6 * d, Path.Direction.CW);
                c.clipPath(clip);
                float s = Math.max(pr.width() / th.getWidth(), pr.height() / th.getHeight());
                float dw = th.getWidth() * s, dh = th.getHeight() * s;
                c.drawBitmap(th, null, new RectF(pr.centerX() - dw / 2, pr.centerY() - dh / 2, pr.centerX() + dw / 2, pr.centerY() + dh / 2), img);
                c.restore();
                img.setAlpha(255);
                top = pr.bottom + 4 * d * scale;
            } else {
                icon.setTextSize(20 * d * scale);
                icon.setAlpha(alpha);
                c.drawText(CaseStore.icon(card.optString("kind")), r.left + 8 * d * scale, top + 20 * d * scale, icon);
                icon.setAlpha(255);
                top += 26 * d * scale;
            }
            title.setTextSize(12.5f * d * scale);
            title.setAlpha(alpha);
            String t = card.optString("title");
            if (t.isEmpty()) t = CaseStore.icon(card.optString("kind"));
            c.save();
            c.translate(r.left + 8 * d * scale, top);
            StaticLayout tl = StaticLayout.Builder.obtain(t, 0, t.length(), title, Math.max(10, (int) (w - 16 * d * scale)))
                    .setMaxLines(1).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
            tl.draw(c);
            c.restore();
            top += tl.getHeight() + 2 * d * scale;
            small.setTextSize(10.5f * d * scale);
            small.setAlpha(alpha);
            String br = card.optString("brief");
            if (!br.isEmpty() && top < r.bottom - 10 * d * scale) {
                c.save();
                c.translate(r.left + 8 * d * scale, top);
                StaticLayout bl = StaticLayout.Builder.obtain(br, 0, br.length(), small, Math.max(10, (int) (w - 16 * d * scale)))
                        .setMaxLines(th != null ? 1 : 3).setEllipsize(android.text.TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                bl.draw(c);
                c.restore();
            }
            String when = new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(new java.util.Date(card.optLong("t")));
            small.setAlpha(dimmed ? 40 : 150);
            c.drawText(when, r.right - small.measureText(when) - 8 * d * scale, r.bottom - 6 * d * scale, small);
            small.setAlpha(255);
            title.setAlpha(255);
        }
        if (cards.length() == 0) {
            small.setTextSize(13 * d);
            String s = "ఇంకా కార్డులు లేవు: ➕ నొక్కి ఫోటో, స్కాన్, నోట్, PDF కలపండి";
            c.drawText(s, Math.max(8 * d, (W - small.measureText(s)) / 2), H / 2f, small);
        }
    }
}
