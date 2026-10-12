package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Jarvis camera's live HUD (our own design, in the theme's colours): a turning ring with a Telugu name on every
 * person and thing the phone finds; the lock-on (a mark, a ring that opens out and closes, a line out to a card that
 * types itself); the lens that grows when it zooms in; the wave that sweeps the picture on "స్కాన్ చేయి", with the
 * found things' small pictures after it; and the gauges at the edges (zoom, direction, tilt, time, battery). It draws
 * only; the camera screen tells it what is where (boxes 0..1 of the screen) and does the zooming and talking.
 */
final class HoloLens extends View {
    interface Listener {
        /** 1: the ring opened, 2: it closed (locked), 3: the card opened. */
        void lockStep(int step);
    }

    private final float d;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            cardTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), cardText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    Listener listener;

    private int safeTop, safeBottom;
    private final List<LiveTracker.Track> tracks = new ArrayList<>();
    private boolean dim;

    HoloLens(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        grid.setStyle(Paint.Style.STROKE);
        label.setTextSize(12.5f * d);
        label.setColor(0xFFFFFFFF);
        label.setShadowLayer(3 * d, 0, 0, 0xCC000000);
        small.setTextSize(10.5f * d);
        small.setColor(0xFFFFFFFF);
        small.setShadowLayer(3 * d, 0, 0, 0xCC000000);
        cardTitle.setTextSize(16f * d);
        cardTitle.setFakeBoldText(true);
        cardTitle.setColor(0xFFFFFFFF);
        cardText.setTextSize(14f * d);
        cardText.setColor(0xFFE8F4FA);
        setWillNotDraw(false);
    }

    private int accent() { return Ui.CYAN; }
    private static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }
    private float dp(float v) { return v * d; }

    /** The top bar's and the bottom panel's heights: nothing important is drawn under them. */
    void setSafe(int top, int bottom) { safeTop = top; safeBottom = bottom; }

    // ================================================================ the live rings

    void setTracks(List<LiveTracker.Track> t, boolean dim) {
        tracks.clear();
        if (t != null) tracks.addAll(t);
        this.dim = dim;
        invalidate();
    }

    boolean hasTracks() { return !tracks.isEmpty(); }

    /** The ring under a tap (null when none). */
    LiveTracker.Track hitTrack(float x, float y) {
        LiveTracker.Track best = null;
        float bd = Float.MAX_VALUE;
        int W = getWidth(), H = getHeight();
        for (LiveTracker.Track t : tracks) {
            float cx = t.cx() * W, cy = t.cy() * H, rr = ringR(t) + dp(14);
            float dd = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (dd <= rr * rr && dd < bd) { bd = dd; best = t; }
        }
        return best;
    }

    private float ringR(LiveTracker.Track t) {
        int W = getWidth(), H = getHeight();
        float w = (t.box[2] - t.box[0]) * W, h = (t.box[3] - t.box[1]) * H;
        return Math.max(dp(16), Math.min(Math.min(W, H) * 0.32f, 0.5f * Math.min(w, h) + dp(6)));
    }

    private int groupColor(String g) {
        switch (g) {
            case "person": return accent();
            case "vehicle": return Ui.C_AMBER;
            case "animal": return Ui.C_GREEN;
            default: return 0xFFC9D6DF;
        }
    }

    private void drawTracks(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        float spin = (now % 9000) / 9000f * 360f;
        for (LiveTracker.Track t : tracks) {
            if (lockActive() && lockTrack == t.id) continue; // (the locked one has its own ring)
            int col = groupColor(t.group);
            int a = dim ? 90 : 230;
            float cx = t.cx() * W, cy = t.cy() * H, R = ringR(t);
            long age = SystemClock.uptimeMillis() - bornAt(t);
            if (age < 220) { // a new one: first a small mark
                float s = dp(5 + 6 * (age / 220f));
                diamond(c, cx, cy, s, alpha(col, a));
                continue;
            }
            float grow = Math.min(1f, (age - 220) / 300f);
            float rr = R * (0.35f + 0.65f * ease(grow));
            ring.setColor(alpha(col, a / 2));
            ring.setStrokeWidth(dp(1.2f));
            c.drawCircle(cx, cy, rr, ring);
            ring.setColor(alpha(col, a));
            ring.setStrokeWidth(dp(2.4f));
            r.set(cx - rr - dp(4), cy - rr - dp(4), cx + rr + dp(4), cy + rr + dp(4));
            for (int k = 0; k < 3; k++) c.drawArc(r, spin + t.id * 37 + k * 120, 62, false, ring);
            ring.setStrokeWidth(dp(1.5f));
            for (int k = 0; k < 4; k++) { // four small ticks outside
                double ang = Math.toRadians(k * 90 + 45);
                float x0 = cx + (float) Math.cos(ang) * (rr + dp(8)), y0 = cy + (float) Math.sin(ang) * (rr + dp(8));
                float x1 = cx + (float) Math.cos(ang) * (rr + dp(13)), y1 = cy + (float) Math.sin(ang) * (rr + dp(13));
                c.drawLine(x0, y0, x1, y1, ring);
            }
            fill.setColor(alpha(col, a));
            c.drawCircle(cx, cy, dp(2.2f), fill);
            if (!dim && grow >= 1f) { // the name under it
                String s = t.te;
                float tw = label.measureText(s) + dp(12), ty = Math.min(H - safeBottom - dp(4), cy + rr + dp(22));
                r.set(cx - tw / 2, ty - dp(15), cx + tw / 2, ty + dp(5));
                fill.setColor(0x9906121E);
                c.drawRoundRect(r, dp(9), dp(9), fill);
                line.setColor(alpha(col, 200));
                line.setStrokeWidth(dp(1));
                c.drawRoundRect(r, dp(9), dp(9), line);
                c.drawText(s, cx - tw / 2 + dp(6), ty, label);
            }
            if (sweepOn(now) && Math.abs(cx - sweepFront(now)) < dp(36)) { // the wave passing: it lights up
                glow.setColor(alpha(col, 200));
                glow.setStrokeWidth(dp(5));
                c.drawCircle(cx, cy, rr + dp(6), glow);
            }
        }
    }

    /** When each ring first showed (to grow it in once). */
    private final java.util.Map<Integer, Long> born = new java.util.HashMap<>();

    private long bornAt(LiveTracker.Track t) {
        Long b = born.get(t.id);
        if (b == null) { b = SystemClock.uptimeMillis(); born.put(t.id, b); if (born.size() > 300) born.clear(); }
        return b;
    }

    private void diamond(Canvas c, float x, float y, float s, int col) {
        Path p = new Path();
        p.moveTo(x, y - s);
        p.lineTo(x + s, y);
        p.lineTo(x, y + s);
        p.lineTo(x - s, y);
        p.close();
        line.setColor(col);
        line.setStrokeWidth(dp(1.6f));
        c.drawPath(p, line);
        fill.setColor(alpha(col, 120));
        c.drawCircle(x, y, dp(1.8f), fill);
    }

    private static float ease(float x) { x = Math.max(0, Math.min(1, x)); return 1 - (1 - x) * (1 - x) * (1 - x); }

    // ================================================================ the lock-on

    private long lockStart;
    private int lockTrack = -1, lockSteps;
    private final float[] lockBox = new float[4];
    private boolean lockOn;
    private String cardTitleS = "", cardTextS = "", waiting = "";
    private Bitmap thumb;
    private long typeStart;
    private int[] clusters = new int[0];
    private final RectF card = new RectF();
    private boolean cardLeft;

    /** Locks on the thing in this box (0..1 of the screen); trackId: the live ring it is (-1 none). */
    void startLock(float[] box, int trackId) {
        System.arraycopy(box, 0, lockBox, 0, 4);
        lockTrack = trackId;
        lockStart = SystemClock.uptimeMillis();
        lockOn = true;
        lockSteps = 0;
        cardTitleS = "";
        cardTextS = "";
        waiting = "చూస్తున్నాను";
        thumb = null;
        clusters = new int[0];
        cardLeft = (box[0] + box[2]) / 2 > 0.5f;
        invalidate();
    }

    /** The locked thing moved (followed by its live ring). */
    void setLockBox(float[] box) {
        if (!lockOn || box == null) return;
        for (int i = 0; i < 4; i++) lockBox[i] = lockBox[i] * 0.5f + box[i] * 0.5f;
        invalidate();
    }

    /** The zoom changed: the ring goes exactly where the thing is now (no smoothing). */
    void setLockBoxNow(float[] box) {
        if (!lockOn || box == null) return;
        System.arraycopy(box, 0, lockBox, 0, 4);
        invalidate();
    }

    void setLockTrack(int id) { lockTrack = id; }
    int lockTrack() { return lockTrack; }
    float[] lockBox() { return lockBox.clone(); }

    /** The card's words (typed in) and the thing's small picture. */
    void setCard(String title, String text, Bitmap pic) {
        cardTitleS = title == null ? "" : title;
        cardTextS = text == null ? "" : text;
        thumb = pic;
        waiting = "";
        typeStart = SystemClock.uptimeMillis();
        java.text.BreakIterator bi = java.text.BreakIterator.getCharacterInstance();
        bi.setText(cardTextS);
        List<Integer> cuts = new ArrayList<>();
        for (int e = bi.next(); e != java.text.BreakIterator.DONE; e = bi.next()) cuts.add(e);
        clusters = new int[cuts.size()];
        for (int i = 0; i < clusters.length; i++) clusters[i] = cuts.get(i);
        invalidate();
    }

    /** "చూస్తున్నాను…" on the card while the AI looks. */
    void setWaiting(String s) { waiting = s == null ? "" : s; invalidate(); }

    void unlock() { lockOn = false; lockTrack = -1; thumb = null; invalidate(); }
    boolean locked() { return lockOn; }
    private boolean lockActive() { return lockOn; }

    /** A tap on the card (it closes then). */
    boolean hitCard(float x, float y) { return lockOn && SystemClock.uptimeMillis() - lockStart > 1200 && card.contains(x, y); }

    private void drawLock(Canvas c, long now) {
        if (!lockOn) return;
        int W = getWidth(), H = getHeight();
        long t = now - lockStart;
        float cx = (lockBox[0] + lockBox[2]) / 2 * W, cy = (lockBox[1] + lockBox[3]) / 2 * H;
        float bw = (lockBox[2] - lockBox[0]) * W, bh = (lockBox[3] - lockBox[1]) * H;
        float R = Math.max(dp(34), Math.min(Math.min(W, H) * 0.42f, 0.55f * (float) Math.hypot(bw, bh) * 0.85f));
        int acc = accent(), lockCol = Ui.C_AMBER;
        if (t < 220) { diamond(c, cx, cy, dp(7 + 9 * t / 220f), acc); return; }
        float open = ease((t - 220) / 380f);
        float rr = R * (0.25f + 0.75f * open);
        boolean closed = t > 900;
        step(1, t >= 220);
        step(2, closed);
        // the inner ring and a soft glow
        RadialGradient rg = new RadialGradient(cx, cy, rr * 1.25f, new int[]{alpha(closed ? lockCol : acc, 0), alpha(closed ? lockCol : acc, 46), alpha(closed ? lockCol : acc, 0)},
                new float[]{0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
        fill.setShader(rg);
        c.drawCircle(cx, cy, rr * 1.25f, fill);
        fill.setShader(null);
        ring.setColor(alpha(acc, 220));
        ring.setStrokeWidth(dp(1.4f));
        c.drawCircle(cx, cy, rr, ring);
        // the outer arc that closes round it
        float sweep = closed ? 360f : 360f * ease((t - 600) / 300f);
        ring.setColor(closed ? lockCol : alpha(acc, 240));
        ring.setStrokeWidth(dp(3f));
        r.set(cx - rr - dp(9), cy - rr - dp(9), cx + rr + dp(9), cy + rr + dp(9));
        if (sweep > 0) c.drawArc(r, -90, sweep, false, ring);
        // turning brackets once locked
        if (closed) {
            float spin = (now % 6000) / 6000f * 360f;
            ring.setStrokeWidth(dp(2f));
            r.set(cx - rr - dp(17), cy - rr - dp(17), cx + rr + dp(17), cy + rr + dp(17));
            for (int k = 0; k < 4; k++) c.drawArc(r, -spin + k * 90 + 10, 30, false, ring);
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
            fill.setColor(alpha(lockCol, (int) (150 + 100 * pulse)));
            c.drawCircle(cx, cy, dp(3 + 2 * pulse), fill);
        }
        // the line out to the card
        if (t < 900) return;
        float cardW = Math.min(W * 0.58f, dp(330));
        float ax = cardLeft ? dp(12) + cardW : W - dp(12) - cardW;
        float top = safeTop + dp(10), bottomLim = H - safeBottom - dp(10);
        float lay = cardHeight(cardW);
        float ay = Math.max(top, Math.min(bottomLim - lay, cy - lay / 2));
        float ex = cx + (cardLeft ? -1 : 1) * (rr + dp(9)), ey = cy;
        float k = ease((t - 900) / 250f);
        float mx = ex + (ax - ex) * 0.55f;
        line.setColor(alpha(lockCol, 230));
        line.setStrokeWidth(dp(1.6f));
        Path p = new Path();
        p.moveTo(ex, ey);
        float[] mid = {mx, ay + dp(18)};
        if (k < 0.55f) { float q = k / 0.55f; p.lineTo(ex + (mid[0] - ex) * q, ey + (mid[1] - ey) * q); }
        else { p.lineTo(mid[0], mid[1]); float q = (k - 0.55f) / 0.45f; p.lineTo(mid[0] + (ax - mid[0]) * q, mid[1]); }
        c.drawPath(p, line);
        fill.setColor(lockCol);
        c.drawCircle(ex, ey, dp(2.5f), fill);
        if (t < 1150) return;
        step(3, true);
        // the card: opens, then types itself
        float h = lay * ease((t - 1150) / 220f);
        card.set(cardLeft ? dp(12) : W - dp(12) - cardW, ay, cardLeft ? dp(12) + cardW : W - dp(12), ay + h);
        fill.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom, 0xE6061A28, 0xD9030B14, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, dp(10), dp(10), fill);
        fill.setShader(null);
        line.setColor(alpha(acc, 200));
        line.setStrokeWidth(dp(1.2f));
        c.drawRoundRect(card, dp(10), dp(10), line);
        line.setColor(lockCol);
        line.setStrokeWidth(dp(3f));
        c.drawLine(card.left + dp(10), card.top, card.left + dp(54), card.top, line); // a short bright edge on top
        if (h < lay * 0.98f) return;
        float x = card.left + dp(12), y = card.top + dp(12);
        float tx = x;
        if (thumb != null && !thumb.isRecycled()) {
            float s = dp(46);
            r.set(x, y, x + s, y + s);
            c.drawBitmap(thumb, null, r, img);
            line.setColor(alpha(acc, 220));
            line.setStrokeWidth(dp(1));
            c.drawRect(r, line);
            tx = x + s + dp(10);
        }
        String title = cardTitleS.isEmpty() ? "🎯 లాక్ అయింది" : "🎯 " + cardTitleS;
        StaticLayout tl = lay(title, cardTitle, Math.round(card.right - dp(12) - tx));
        c.save();
        c.translate(tx, y);
        tl.draw(c);
        c.restore();
        float textTop = Math.max(y + tl.getHeight(), thumb != null ? y + dp(46) : 0) + dp(8);
        String shown;
        if (!waiting.isEmpty()) {
            int dots = (int) ((now / 350) % 4);
            shown = waiting + "...".substring(0, dots);
        } else {
            int n = (int) Math.min(clusters.length, (now - typeStart) / 28);
            shown = n <= 0 ? "" : cardTextS.substring(0, clusters[n - 1]) + (n < clusters.length ? "▌" : "");
        }
        StaticLayout bl = lay(shown, cardText, Math.round(card.width() - dp(24)));
        c.save();
        c.translate(x, textTop);
        c.clipRect(0, 0, card.width(), card.bottom - textTop - dp(8));
        bl.draw(c);
        c.restore();
    }

    private void step(int s, boolean reached) {
        if (!reached || lockSteps >= s) return;
        lockSteps = s;
        if (listener != null) post(() -> { if (listener != null) listener.lockStep(s); });
    }

    private float cardHeight(float w) {
        float inner = w - dp(24);
        StaticLayout tl = lay(cardTitleS.isEmpty() ? "🎯 లాక్ అయింది" : "🎯 " + cardTitleS, cardTitle, Math.round(inner - (thumb != null ? dp(56) : 0)));
        String body = !waiting.isEmpty() ? waiting + "..." : cardTextS;
        StaticLayout bl = lay(body, cardText, Math.round(inner));
        float head = Math.max(tl.getHeight(), thumb != null ? dp(46) : 0);
        float maxH = Math.max(dp(120), getHeight() - safeTop - safeBottom - dp(20));
        return Math.min(maxH, dp(12) + head + dp(8) + bl.getHeight() + dp(14));
    }

    private final java.util.Map<String, StaticLayout> layouts = new java.util.HashMap<>(); // (the card's text is laid out once, not every frame)

    private StaticLayout lay(String s, TextPaint p, int w) {
        String key = System.identityHashCode(p) + "|" + w + "|" + s;
        StaticLayout l = layouts.get(key);
        if (l != null) return l;
        if (layouts.size() > 24) layouts.clear();
        l = StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(10, w)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.12f).build();
        layouts.put(key, l);
        return l;
    }

    // ================================================================ the lens (zooming in)

    private long lensStart;
    private final float[] lensBox = new float[4];

    /** The lens grows from this thing until it fills the screen (while the camera zooms in). */
    void lens(float[] box) {
        System.arraycopy(box, 0, lensBox, 0, 4);
        lensStart = SystemClock.uptimeMillis();
        invalidate();
    }

    private void drawLens(Canvas c, long now) {
        long t = now - lensStart;
        if (lensStart == 0 || t > 700) return;
        int W = getWidth(), H = getHeight();
        float cx = (lensBox[0] + lensBox[2]) / 2 * W, cy = (lensBox[1] + lensBox[3]) / 2 * H;
        float r0 = Math.max(dp(30), 0.5f * Math.max((lensBox[2] - lensBox[0]) * W, (lensBox[3] - lensBox[1]) * H));
        float r1 = (float) Math.hypot(W, H);
        float k = ease(t / 650f);
        float rr = r0 + (r1 - r0) * k;
        int a = (int) (230 * (1 - k * k));
        ring.setColor(alpha(accent(), a));
        ring.setStrokeWidth(dp(3 + 10 * k));
        c.drawCircle(cx + (W / 2f - cx) * k, cy + (H / 2f - cy) * k, rr, ring);
        ring.setStrokeWidth(dp(1.2f));
        ring.setColor(alpha(Ui.C_AMBER, a));
        c.drawCircle(cx + (W / 2f - cx) * k, cy + (H / 2f - cy) * k, rr * 0.86f, ring);
    }

    // ================================================================ the wave (స్కాన్ చేయి)

    private long sweepStart;
    private static final long SWEEP_MS = 2200;

    void sweep() { sweepStart = SystemClock.uptimeMillis(); thumbs.clear(); invalidate(); }

    private boolean sweepOn(long now) { return sweepStart > 0 && now - sweepStart < SWEEP_MS; }

    private float sweepFront(long now) {
        float k = (now - sweepStart) / (float) SWEEP_MS;
        return getWidth() * (-0.05f + 1.1f * (k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2));
    }

    private void drawSweep(Canvas c, long now) {
        if (!sweepOn(now)) return;
        int W = getWidth(), H = getHeight();
        float front = sweepFront(now), band = W * 0.38f;
        float horizon = H * 0.42f, vx = W / 2f;
        c.save();
        c.clipRect(Math.max(0, front - band), safeTop, Math.min(W, front + dp(2)), H - safeBottom);
        int acc = accent();
        grid.setStrokeWidth(dp(1));
        // the floor: lines to the far point, and across
        for (int i = -12; i <= 12; i++) {
            float bx = vx + i * W * 0.16f;
            grid.setShader(new LinearGradient(front - band, 0, front, 0, alpha(acc, 0), alpha(acc, 150), Shader.TileMode.CLAMP));
            c.drawLine(vx, horizon, bx, H, grid);
        }
        for (int j = 1; j <= 14; j++) {
            float q = j / 14f, y = horizon + (H - horizon) * q * q;
            c.drawLine(0, y, W, y, grid);
        }
        // the walls / sky: a fainter mesh of curves
        for (int j = 1; j <= 8; j++) {
            float y = horizon - (horizon - safeTop) * (j / 8f) * (j / 8f);
            Path p = new Path();
            p.moveTo(0, y);
            for (int x = 0; x <= W; x += W / 12) p.lineTo(x, y + (float) Math.sin((x / (float) W) * Math.PI * 2 + j) * dp(6));
            c.drawPath(p, grid);
        }
        grid.setShader(null);
        c.restore();
        // the bright front
        glow.setShader(new LinearGradient(front - dp(22), 0, front + dp(2), 0, alpha(acc, 0), alpha(acc, 220), Shader.TileMode.CLAMP));
        glow.setStyle(Paint.Style.FILL);
        c.drawRect(front - dp(22), safeTop, front + dp(2), H - safeBottom, glow);
        glow.setShader(null);
        glow.setStyle(Paint.Style.STROKE);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(dp(1.2f));
        c.drawLine(front, safeTop, front, H - safeBottom, line);
    }

    // ---- the found things' small pictures after the wave
    private final List<Bitmap> thumbs = new ArrayList<>();
    private final List<String> thumbNames = new ArrayList<>();
    private long thumbsAt;

    void setThumbs(List<Bitmap> pics, List<String> names) {
        thumbs.clear();
        thumbNames.clear();
        if (pics != null) thumbs.addAll(pics);
        if (names != null) thumbNames.addAll(names);
        thumbsAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void drawThumbs(Canvas c, long now) {
        if (thumbs.isEmpty()) return;
        long t = now - thumbsAt;
        if (t > 9000) { thumbs.clear(); thumbNames.clear(); return; }
        float s = dp(50), gap = dp(6), x = dp(34), y = safeTop + dp(10);
        int fade = t > 8000 ? (int) (255 * (1 - (t - 8000) / 1000f)) : 255;
        for (int i = 0; i < thumbs.size(); i++) {
            long ti = t - i * 140L;
            if (ti < 0) break;
            float k = ease(ti / 220f);
            Bitmap b = thumbs.get(i);
            if (b == null || b.isRecycled()) continue;
            float xx = x + i * (s + gap);
            r.set(xx, y - dp(10) * (1 - k), xx + s, y + s - dp(10) * (1 - k));
            img.setAlpha((int) (fade * k));
            c.drawBitmap(b, null, r, img);
            img.setAlpha(255);
            line.setColor(alpha(accent(), (int) (fade * k * 0.85f)));
            line.setStrokeWidth(dp(1));
            c.drawRect(r, line);
            String n = i < thumbNames.size() ? thumbNames.get(i) : "";
            small.setAlpha((int) (fade * k));
            c.drawText(n, xx, r.bottom + dp(12), small);
            small.setAlpha(255);
        }
    }

    // ================================================================ the edges: zoom, direction, tilt, time, battery

    private float zoom = 1f, optMax = 1f, heading = Float.NaN, roll = Float.NaN;
    private String time = "";
    private int battery = -1;
    private long bootStart;

    void setGauges(float zoom, float optMax, float heading, float roll, String time, int battery) {
        this.zoom = zoom;
        this.optMax = optMax;
        this.heading = heading;
        this.roll = roll;
        this.time = time == null ? "" : time;
        this.battery = battery;
        invalidate();
    }

    /** The camera opened: the edges light up one after another. */
    void boot() { bootStart = SystemClock.uptimeMillis(); invalidate(); }

    private float bootK(long now, int part) {
        if (bootStart == 0) return 1f;
        return ease((now - bootStart - part * 260) / 320f);
    }

    static String direction(float deg) {
        String[] n = {"ఉత్తరం", "ఈశాన్యం", "తూర్పు", "ఆగ్నేయం", "దక్షిణం", "నైరుతి", "పడమర", "వాయువ్యం"};
        int i = (int) Math.floor(((deg % 360 + 360) % 360 + 22.5f) / 45f) % 8;
        return n[i];
    }

    private void drawGauges(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        int acc = accent();
        float top = safeTop + dp(70), bottom = H - safeBottom - dp(24);
        if (bottom - top < dp(120)) return;
        // left: the zoom ladder (1× … 100×, the phone's own part and Jarvis's part)
        float k = bootK(now, 0);
        if (k > 0) {
            float x = dp(14), y0 = bottom, y1 = top;
            line.setColor(alpha(acc, (int) (130 * k)));
            line.setStrokeWidth(dp(1.2f));
            c.drawLine(x, y0, x, y0 + (y1 - y0) * k, line);
            float[] marks = {1, 3, 5, 10, 30, 100};
            for (float m : marks) {
                float y = y0 + (y1 - y0) * (float) (Math.log(m) / Math.log(100));
                c.drawLine(x, y, x + dp(6), y, line);
                small.setAlpha((int) (200 * k));
                c.drawText((m == Math.round(m) ? String.valueOf((int) m) : String.valueOf(m)) + "×", x + dp(8), y + dp(4), small);
            }
            small.setAlpha(255);
            if (optMax > 1.01f) { // where the phone's own zoom ends and Jarvis's begins
                float y = y0 + (y1 - y0) * (float) (Math.log(Math.min(100, optMax)) / Math.log(100));
                line.setColor(alpha(Ui.C_AMBER, (int) (200 * k)));
                c.drawLine(x - dp(4), y, x + dp(10), y, line);
            }
            float y = y0 + (y1 - y0) * (float) (Math.log(Math.max(1, Math.min(100, zoom))) / Math.log(100));
            Path p = new Path();
            p.moveTo(x - dp(2), y);
            p.lineTo(x - dp(9), y - dp(5));
            p.lineTo(x - dp(9), y + dp(5));
            p.close();
            fill.setColor(alpha(zoom > optMax + 0.01f ? Ui.C_AMBER : acc, (int) (255 * k)));
            c.drawPath(p, fill);
            label.setAlpha((int) (255 * k));
            String z = zoom < 10 ? String.format(java.util.Locale.US, "%.1f×", zoom) : Math.round(zoom) + "×";
            c.drawText(z, x + dp(30), y + dp(5), label);
            label.setAlpha(255);
        }
        // right: direction and tilt
        k = bootK(now, 1);
        if (k > 0) {
            float x = W - dp(14), y = top + dp(6);
            if (!Float.isNaN(heading)) {
                String h = Math.round((heading % 360 + 360) % 360) + "° " + direction(heading);
                label.setAlpha((int) (255 * k));
                c.drawText(h, x - label.measureText(h), y, label);
                label.setAlpha(255);
            }
            if (!Float.isNaN(roll)) { // a small horizon in a circle
                float cx = x - dp(24), cy = y + dp(36), rr = dp(18);
                ring.setColor(alpha(acc, (int) (150 * k)));
                ring.setStrokeWidth(dp(1.2f));
                c.drawCircle(cx, cy, rr, ring);
                c.save();
                c.rotate(-roll, cx, cy);
                boolean level = Math.abs(roll) < 2f;
                line.setColor(alpha(level ? Ui.C_GREEN : Ui.C_AMBER, (int) (230 * k)));
                line.setStrokeWidth(dp(2f));
                c.drawLine(cx - rr + dp(4), cy, cx + rr - dp(4), cy, line);
                c.restore();
                String rs = Math.round(roll) + "°";
                small.setAlpha((int) (220 * k));
                c.drawText(rs, cx - small.measureText(rs) / 2, cy + rr + dp(13), small);
                small.setAlpha(255);
            }
        }
        // right, lower: time and battery
        k = bootK(now, 2);
        if (k > 0 && !time.isEmpty()) {
            float x = W - dp(14), y = top + dp(100);
            String s = time + (battery >= 0 ? "  🔋" + battery + "%" : "");
            small.setAlpha((int) (230 * k));
            c.drawText(s, x - small.measureText(s), y, small);
            small.setAlpha(255);
        }
        // the four corners light up once at the start
        if (bootStart > 0 && now - bootStart < 1300) {
            float q = 1 - Math.abs((now - bootStart) / 650f - 1);
            line.setColor(alpha(acc, (int) (220 * q)));
            line.setStrokeWidth(dp(2.2f));
            float m = dp(10), L = dp(26), t0 = safeTop + dp(4), b0 = H - safeBottom - dp(4);
            c.drawLine(m, t0, m + L, t0, line); c.drawLine(m, t0, m, t0 + L, line);
            c.drawLine(W - m, t0, W - m - L, t0, line); c.drawLine(W - m, t0, W - m, t0 + L, line);
            c.drawLine(m, b0, m + L, b0, line); c.drawLine(m, b0, m, b0 - L, line);
            c.drawLine(W - m, b0, W - m - L, b0, line); c.drawLine(W - m, b0, W - m, b0 - L, line);
        }
    }

    // ================================================================ the remark ("👁️ 2 మనుషులు, 1 బైక్ కనిపిస్తున్నాయి"), shown for a few seconds

    private String remarkS = "";
    private long remarkAt;

    void setRemark(String s) { remarkS = s == null ? "" : s; remarkAt = SystemClock.uptimeMillis(); invalidate(); }

    private void drawRemark(Canvas c, long now) {
        if (remarkS.isEmpty()) return;
        long t = now - remarkAt;
        if (t > 6000) { remarkS = ""; return; }
        float a = t < 250 ? t / 250f : t > 5200 ? (6000 - t) / 800f : 1f;
        int W = getWidth();
        StaticLayout l = lay(remarkS, label, Math.round(W - dp(64)));
        float lw = 0;
        for (int i = 0; i < l.getLineCount(); i++) lw = Math.max(lw, l.getLineWidth(i));
        float x = (W - lw) / 2f, y = safeTop + dp(16);
        r.set(x - dp(12), y - dp(6), x + lw + dp(12), y + l.getHeight() + dp(6));
        fill.setColor(alpha(0xFF041019, (int) (190 * a)));
        c.drawRoundRect(r, dp(14), dp(14), fill);
        line.setColor(alpha(accent(), (int) (170 * a)));
        line.setStrokeWidth(dp(1));
        c.drawRoundRect(r, dp(14), dp(14), line);
        label.setAlpha((int) (255 * a));
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        label.setAlpha(255);
    }

    // ================================================================ drawing

    /** Hidden (a frozen photo, a gallery picture): nothing live is drawn. */
    private boolean live = true;
    void setLive(boolean on) { live = on; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        drawGauges(c, now);
        if (live) {
            drawSweep(c, now);
            drawTracks(c, now);
        }
        drawLens(c, now);
        drawLock(c, now);
        drawThumbs(c, now);
        drawRemark(c, now);
        boolean moving = live && (!tracks.isEmpty() || sweepOn(now)) || lockOn || now - lensStart < 800 || !thumbs.isEmpty()
                || bootStart > 0 && now - bootStart < 1400
                || !remarkS.isEmpty();
        boolean typing = lockOn && waiting.isEmpty() && (now - typeStart) / 28 < clusters.length;
        boolean fast = lockOn && (now - lockStart < 1500 || typing) || sweepOn(now) || now - lensStart < 800;
        if (moving) postInvalidateDelayed(fast ? 16 : 33); // (a settled lock only turns its brackets: 30 a second is enough)
    }

    /** The HUD drawn onto a photo (for "📸 HUD తో ఫోటో"): the same as on the screen, at the photo's size. */
    void drawInto(Canvas c, int w, int h) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        c.save();
        c.scale(w / (float) W, h / (float) H);
        onDraw(c);
        c.restore();
    }

}
