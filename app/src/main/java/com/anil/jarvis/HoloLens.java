package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Jarvis camera's live HUD (our own design, in the theme's colours): a turning ring with a Telugu name on every
 * person and thing the phone finds; the lock-on (a mark, a ring that opens out and closes, a line out to a card that
 * types itself); the lens that grows when it zooms in; the wave that sweeps the picture on "స్కాన్ చేయి", with the
 * found things' small pictures after it; and the gauges at the edges (zoom, direction, tilt, time, battery). It draws
 * only; the camera screen tells it what is where (boxes 0..1 of the screen) and does the zooming and talking.
 *
 * ✨ (21 Oct, each its own switch in ⋯ → ✨, see {@link Fx}): rings that glide and fade, name tags that never overlap,
 * one design language, one strip at the top, glass pills, near things bigger, the edges' small lag when the phone
 * turns, a floor grid, a 3D lock card, two-layer glow at 60 fps, and the phone's own depth map. With all of them off
 * it draws exactly the old HUD.
 */
final class HoloLens extends View {
    interface Listener {
        /** 1: the ring opened, 2: it closed (locked), 3: the card opened. */
        void lockStep(int step);
    }

    /** The ✨ look's switches (all off = the old HUD). saver: the camera's 🔋 saver is on (no 60 fps). */
    static final class Fx {
        boolean smooth, tags, style, strip, glass, near, parallax, grid, card3d, glow, depth, saver;
    }

    private final float d;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG), haloSoft = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            soft = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            cardTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), cardText = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            mono = new TextPaint(Paint.ANTI_ALIAS_FLAG), monoSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    Listener listener;

    private int safeTop, safeBottom;
    private final List<LiveTracker.Track> tracks = new ArrayList<>();
    private boolean dim;
    /** The steadied picture's nudge (fractions of the screen): the rings follow it. */
    private float shiftX, shiftY;
    void setShift(float sx, float sy) { shiftX = sx; shiftY = sy; }

    private Fx fx = new Fx();
    /** The time since the last frame (ms; 0 for a still picture of the HUD) and the canvas's current shift (for the glass). */
    private float frameDt, curTx, curTy;
    private long lastFrame;
    private boolean snapshot;

    HoloLens(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        grid.setStyle(Paint.Style.STROKE);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeCap(Paint.Cap.ROUND);
        haloSoft.setStyle(Paint.Style.STROKE);
        haloSoft.setStrokeCap(Paint.Cap.ROUND);
        edge.setStyle(Paint.Style.STROKE);
        label.setTextSize(12.5f * d);
        label.setColor(0xFFFFFFFF);
        label.setShadowLayer(3 * d, 0, 0, 0xCC000000);
        small.setTextSize(10.5f * d);
        small.setColor(0xFFFFFFFF);
        small.setShadowLayer(3 * d, 0, 0, 0xCC000000);
        mono.setTextSize(12.5f * d);
        mono.setColor(0xFFFFFFFF);
        mono.setTypeface(Typeface.MONOSPACE);
        mono.setShadowLayer(3 * d, 0, 0, 0xCC000000);
        monoSmall.setTextSize(10.5f * d);
        monoSmall.setColor(0xFFFFFFFF);
        monoSmall.setTypeface(Typeface.MONOSPACE);
        monoSmall.setShadowLayer(3 * d, 0, 0, 0xCC000000);
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

    /** The ✨ switches (from the camera screen; again whenever one changes). */
    void setFx(Fx f) {
        fx = f == null ? new Fx() : f;
        if (!fx.glass || fx.saver) backdrop = null;
        if (!fx.depth) depthGrid = null;
        if (!fx.smooth) { disp.clear(); ghosts.clear(); }
        if (!fx.parallax) chromeX = chromeY = 0;
        // the soft outer layer of the glow (a real blur where the phone draws it on the GPU: Android 9+)
        haloSoft.setMaskFilter(fx.glow && Build.VERSION.SDK_INT >= 28 ? new BlurMaskFilter(dp(3), BlurMaskFilter.Blur.NORMAL) : null);
        invalidate();
    }

    /** Numbers in one shape when ✨ 3 is on (degrees, zoom, time, distance). */
    private TextPaint num() { return fx.style ? mono : label; }
    private TextPaint numSmall() { return fx.style ? monoSmall : small; }

    /** The top bar's and the bottom panel's heights: nothing important is drawn under them. */
    void setSafe(int top, int bottom) { safeTop = top; safeBottom = bottom; }

    // ================================================================ the live rings

    void setTracks(List<LiveTracker.Track> t, boolean dim) {
        if (fx.smooth) { // ✨ 1: the ones that went away fade out instead of vanishing
            long now = SystemClock.uptimeMillis();
            Set<Integer> keep = new HashSet<>();
            if (t != null) for (LiveTracker.Track x : t) keep.add(x.id);
            if (live) for (LiveTracker.Track o : tracks) {
                if (keep.contains(o.id) || "text".equals(o.group) || "face".equals(o.group) || lockOn && lockTrack == o.id || isExtraLock(o.id)) continue;
                Long b = born.get(o.id);
                if (b == null || now - b < 520) continue; // (never fully shown)
                float[] db = disp.get(o.id);
                ghosts.add(new Ghost(o.group, db != null ? db.clone() : o.box.clone(), now, scaleOf(o)));
            }
            while (ghosts.size() > 24) ghosts.remove(0);
            disp.keySet().retainAll(keep);
        }
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
            float cx = t.cx() * W, cy = t.cy() * H, rr = ringR(t.box) + dp(14);
            float dd = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (dd <= rr * rr && dd < bd) { bd = dd; best = t; }
        }
        return best;
    }

    private float ringR(float[] box) {
        int W = getWidth(), H = getHeight();
        float w = (box[2] - box[0]) * W, h = (box[3] - box[1]) * H;
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

    private static int groupRank(String g) {
        switch (g) {
            case "person": return 0;
            case "vehicle": return 1;
            case "animal": return 2;
            default: return 3;
        }
    }

    private static final int C_TEXT = 0xFFC7B3FF, C_FACE = 0xFF9EE7FF;

    /** ✨ 1: where a ring is drawn now (it glides to where the finder saw it, about 90 ms behind). */
    private final Map<Integer, float[]> disp = new HashMap<>();

    private float[] shownBox(LiveTracker.Track t) {
        if (!fx.smooth) return t.box;
        float[] b = disp.get(t.id);
        if (b == null) { b = t.box.clone(); disp.put(t.id, b); return b; }
        for (int k = 0; k < 4; k++) b[k] = HudMath.ease(b[k], t.box[k], frameDt, 90);
        return b;
    }

    /** ✨ 1: a ring that went away, fading out for a moment. */
    private static final class Ghost {
        final String group;
        final float[] box;
        final long at;
        final float scale;
        Ghost(String group, float[] box, long at, float scale) { this.group = group; this.box = box; this.at = at; this.scale = scale; }
    }

    private final List<Ghost> ghosts = new ArrayList<>();
    private static final long GHOST_MS = 350;

    private void drawGhosts(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            Ghost g = ghosts.get(i);
            long age = now - g.at;
            if (age > GHOST_MS || age < 0) { ghosts.remove(i); continue; }
            float k = age / (float) GHOST_MS;
            float cx = (g.box[0] + g.box[2]) / 2 * W, cy = (g.box[1] + g.box[3]) / 2 * H, rr = ringR(g.box) * (1 + 0.18f * k);
            ring.setColor(alpha(groupColor(g.group), (int) ((dim ? 90 : 200) * (1 - k))));
            ring.setStrokeWidth(dp(fx.style ? 1.1f : 1.5f) * g.scale);
            c.drawCircle(cx, cy, rr, ring);
        }
    }

    /** ✨ 6: how big this one is drawn (near bigger, far smaller). */
    private float scaleOf(LiveTracker.Track t) {
        if (!fx.near) return 1f;
        return HudMath.nearScale(t.distM, depthNear(t.box));
    }

    /** A sign / board: a rounded box with its words above it. */
    private void drawText(Canvas c, LiveTracker.Track t, float[] b, List<float[]> taken) {
        int W = getWidth(), H = getHeight();
        int a = dim ? 70 : 200;
        r.set(b[0] * W, b[1] * H, b[2] * W, b[3] * H);
        line.setColor(alpha(C_TEXT, a));
        line.setStrokeWidth(dp(fx.style ? 1f : 1.3f));
        if (fx.style) brackets(c, r, alpha(C_TEXT, a), dp(7), dp(1.3f));
        else c.drawRoundRect(r, dp(4), dp(4), line);
        if (dim) return;
        String s = "🪧 " + t.te;
        float tw = small.measureText(s) + dp(10);
        float y = Math.max(safeTop + dp(16), r.top - dp(6));
        RectF tag = new RectF(r.left, y - dp(14), Math.min(W - dp(4), r.left + tw), y + dp(4));
        pill(c, tag, dp(fx.style ? 3 : 6), 0xB3140A2A);
        small.setColor(C_TEXT);
        c.drawText(s, tag.left + dp(5), y, small);
        small.setColor(0xFFFFFFFF);
        if (taken != null) taken.add(new float[]{tag.left, tag.top, tag.right, tag.bottom});
    }

    /** A face: four corner brackets; a name only for someone Anil introduced. */
    private void drawFace(Canvas c, LiveTracker.Track t, float[] b, List<float[]> taken) {
        int W = getWidth(), H = getHeight();
        int a = dim ? 70 : 220;
        float x0 = b[0] * W, y0 = b[1] * H, x1 = b[2] * W, y1 = b[3] * H;
        float L = Math.min(dp(10), (x1 - x0) * 0.3f);
        boolean known = t.te != null && !t.te.isEmpty();
        line.setColor(alpha(known ? Ui.C_GREEN : C_FACE, a));
        line.setStrokeWidth(dp(fx.style ? 1.3f : 1.6f));
        c.drawLine(x0, y0, x0 + L, y0, line); c.drawLine(x0, y0, x0, y0 + L, line);
        c.drawLine(x1, y0, x1 - L, y0, line); c.drawLine(x1, y0, x1, y0 + L, line);
        c.drawLine(x0, y1, x0 + L, y1, line); c.drawLine(x0, y1, x0, y1 - L, line);
        c.drawLine(x1, y1, x1 - L, y1, line); c.drawLine(x1, y1, x1, y1 - L, line);
        if (dim || !known) return;
        String s = "👤 " + t.te;
        float tw = small.measureText(s) + dp(10);
        RectF tag = new RectF(x0, y1 + dp(4), x0 + tw, y1 + dp(22));
        pill(c, tag, dp(fx.style ? 3 : 6), 0xB3062A1A);
        c.drawText(s, tag.left + dp(5), tag.bottom - dp(5), small);
        if (taken != null) taken.add(new float[]{tag.left, tag.top, tag.right, tag.bottom});
    }

    /** "86% · ~12 మీ ↗": the small facts under a ring. */
    static String tag(LiveTracker.Track t) {
        StringBuilder b = new StringBuilder();
        b.append(Math.round(t.score * 100)).append('%');
        if (t.distM > 0) b.append(" · ~").append(t.distM < 10 ? String.format(java.util.Locale.US, "%.1f", t.distM) : String.valueOf(Math.round(t.distM))).append(" మీ");
        if (t.speed() > 0.05f) {
            double ang = Math.toDegrees(Math.atan2(-t.vy, t.vx));
            String[] arrows = {"→", "↗", "↑", "↖", "←", "↙", "↓", "↘"};
            int i = (int) Math.floor(((ang + 360) % 360 + 22.5) / 45) % 8;
            b.append(' ').append(arrows[i]);
        }
        if (!t.colour.isEmpty() && !"person".equals(t.group)) { String cw = ColourWords.te(t.colour); if (!cw.isEmpty()) b.append(" · ").append(cw); }
        return b.toString();
    }

    /** ✨ 2: a name tag waiting for its place. */
    private static final class TagWant {
        final String name, facts;
        final int col, rank;
        final float cx, ringTop, ringBottom, scale, size, alphaK;
        TagWant(String name, String facts, int col, int rank, float cx, float ringTop, float ringBottom, float scale, float size, float alphaK) {
            this.name = name; this.facts = facts; this.col = col; this.rank = rank; this.cx = cx; this.ringTop = ringTop;
            this.ringBottom = ringBottom; this.scale = scale; this.size = size; this.alphaK = alphaK;
        }
    }

    private final List<LiveTracker.Track> orderL = new ArrayList<>();
    private final Map<Integer, Float> scalesM = new HashMap<>();
    private final List<float[]> takenL = new ArrayList<>();
    private final List<TagWant> wantsL = new ArrayList<>();

    private void drawTracks(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        float spin = (now % 9000) / 9000f * 360f;
        List<LiveTracker.Track> order = orderL;
        order.clear();
        order.addAll(tracks);
        final Map<Integer, Float> scales = scalesM;
        scales.clear();
        for (LiveTracker.Track t : order) scales.put(t.id, scaleOf(t));
        if (fx.near) order.sort((a, b) -> Float.compare(scales.get(a.id), scales.get(b.id))); // (far ones first: the near ones on top)
        List<float[]> taken = fx.tags ? takenL : null;
        List<TagWant> wants = fx.tags ? wantsL : null;
        if (taken != null) { taken.clear(); wants.clear(); }
        for (LiveTracker.Track t : order) {
            if (lockActive() && lockTrack == t.id) continue; // (the locked one has its own ring)
            if (isExtraLock(t.id)) continue;
            float[] b = shownBox(t);
            if ("text".equals(t.group)) { drawText(c, t, b, taken); continue; }
            if ("face".equals(t.group)) { drawFace(c, t, b, taken); continue; }
            int col = groupColor(t.group);
            float s = scales.get(t.id);
            float far = fx.near ? Math.max(0f, Math.min(1f, (1.2f - s) / 0.45f)) : 0f; // 0 near … 1 far
            int a = (int) ((dim ? 90 : 230) * (1f - 0.25f * far));
            float cx = (b[0] + b[2]) / 2 * W, cy = (b[1] + b[3]) / 2 * H, R = ringR(b);
            long age = SystemClock.uptimeMillis() - bornAt(t);
            if (age < 220) { // a new one: first a small mark
                float sz = dp(5 + 6 * (age / 220f));
                diamond(c, cx, cy, sz, alpha(col, a));
                continue;
            }
            float grow = Math.min(1f, (age - 220) / 300f);
            float rr = R * (0.35f + 0.65f * ease(grow));
            if (fx.smooth && grow < 1f) a = (int) (a * (0.4f + 0.6f * ease(grow))); // ✨ 1: fades in as it grows
            float thin = fx.style ? 0.7f : 1f;
            if (fx.glow && !dim) { // ✨ 10: two soft layers under the thin bright ring (no GPU blur here: there can be many rings)
                halo.setColor(alpha(col, 26));
                halo.setStrokeWidth(dp(10f) * s);
                c.drawCircle(cx, cy, rr + dp(2), halo);
                halo.setColor(alpha(col, 55));
                halo.setStrokeWidth(dp(4.5f) * s);
                c.drawCircle(cx, cy, rr + dp(1), halo);
            } else if (!dim) { // a soft glow behind the ring
                glow.setColor(alpha(col, 40));
                glow.setStrokeWidth(dp(7) * s);
                c.drawCircle(cx, cy, rr + dp(2), glow);
            }
            ring.setColor(alpha(col, a / 2));
            ring.setStrokeWidth(dp(1.2f) * thin * s);
            c.drawCircle(cx, cy, rr, ring);
            ring.setColor(alpha(col, a));
            ring.setStrokeWidth(dp(2.4f) * thin * s);
            r.set(cx - rr - dp(4), cy - rr - dp(4), cx + rr + dp(4), cy + rr + dp(4));
            for (int k = 0; k < 3; k++) c.drawArc(r, spin + t.id * 37 + k * 120, 62, false, ring);
            ring.setStrokeWidth(dp(1.5f) * thin * s);
            for (int k = 0; k < 4; k++) { // four small ticks outside
                double ang = Math.toRadians(k * 90 + 45);
                float x0 = cx + (float) Math.cos(ang) * (rr + dp(8)), y0 = cy + (float) Math.sin(ang) * (rr + dp(8));
                float x1 = cx + (float) Math.cos(ang) * (rr + dp(13)), y1 = cy + (float) Math.sin(ang) * (rr + dp(13));
                c.drawLine(x0, y0, x1, y1, ring);
            }
            fill.setColor(alpha(col, a));
            c.drawCircle(cx, cy, dp(fx.style ? 1.8f : 2.2f) * s, fill);
            if (!dim && grow >= 1f) { // the name under it, and the small facts
                String nm = t.te, facts = tag(t);
                if (fx.tags) wants.add(new TagWant(nm, facts, col, groupRank(t.group), cx, cy - rr - dp(4), cy + rr + dp(4), s, rr, 1f - 0.25f * far));
                else drawTagOld(c, nm, facts, col, cx, Math.min(H - safeBottom - dp(22), cy + rr + dp(22)), s, 1f - 0.25f * far);
            }
            if (sweepOn(now) && Math.abs(cx - sweepFront(now)) < dp(36)) { // the wave passing: it lights up
                glow.setColor(alpha(col, 200));
                glow.setStrokeWidth(dp(5));
                c.drawCircle(cx, cy, rr + dp(6), glow);
            }
        }
        if (fx.smooth) drawGhosts(c, now);
        if (fx.tags && !wants.isEmpty()) placeAndDrawTags(c, wants, taken);
    }

    /** The old tag: a rounded box under the ring (ty = the name's line). */
    private void drawTagOld(Canvas c, String nm, String facts, int col, float cx, float ty, float s, float ak) {
        TextPaint fp = numSmall();
        float tw = Math.max(label.measureText(nm), fp.measureText(facts)) + dp(12);
        r.set(cx - tw / 2 * s, ty - dp(15) * s, cx + tw / 2 * s, ty + dp(19) * s); // (its background at its drawn size, so the glass lines up)
        pill(c, r, dp(fx.style ? 3 : 9) * s, 0x9906121E);
        c.save();
        c.scale(s, s, cx, ty);
        r.set(cx - tw / 2, ty - dp(15), cx + tw / 2, ty + dp(19));
        if (fx.style) {
            brackets(c, r, alpha(col, (int) (220 * ak)), dp(6), dp(1.3f));
        } else {
            line.setColor(alpha(col, 200));
            line.setStrokeWidth(dp(1));
            c.drawRoundRect(r, dp(9), dp(9), line);
        }
        drawTagWords(c, nm, facts, cx - tw / 2 + dp(6), ty, ak);
        c.restore();
    }

    private void drawTagWords(Canvas c, String nm, String facts, float x, float ty, float ak) {
        TextPaint fp = numSmall();
        label.setAlpha((int) (255 * ak));
        c.drawText(nm, x, ty, label);
        label.setAlpha(255);
        fp.setAlpha((int) (210 * ak));
        c.drawText(facts, x, ty + dp(14), fp);
        fp.setAlpha(255);
    }

    /** ✨ 2: the tags, most important first (people, then vehicles, animals, things; the bigger ring first), never on each other. */
    private void placeAndDrawTags(Canvas c, List<TagWant> wants, List<float[]> taken) {
        int W = getWidth(), H = getHeight();
        wants.sort((a, b) -> a.rank != b.rank ? Integer.compare(a.rank, b.rank) : Float.compare(b.size, a.size));
        TextPaint fp = numSmall();
        float[][] want = new float[wants.size()][];
        for (int i = 0; i < want.length; i++) {
            TagWant w = wants.get(i);
            float tw = (Math.max(label.measureText(w.name), fp.measureText(w.facts)) + dp(12)) * w.scale, th = dp(34) * w.scale;
            want[i] = new float[]{w.cx, w.ringTop, w.ringBottom, tw, th};
        }
        List<float[]> blocked = new ArrayList<>(taken);
        if (lockOn && card.width() > 0) blocked.add(new float[]{card.left, card.top, card.right, card.bottom}); // (the card is drawn in the same shifted space)
        float top = safeTop + dp(fx.strip ? 36 : 4) - curTy, bottom = H - safeBottom - dp(4) - curTy;
        float[][] at = HudMath.placeTags(want, blocked.toArray(new float[0][]), fx.saver ? 4 : 6, W, top, bottom, dp(6), dp(4));
        for (int i = 0; i < at.length; i++) {
            if (at[i] == null) continue;
            TagWant w = wants.get(i);
            r.set(at[i][0], at[i][1], at[i][2], at[i][3]);
            pill(c, r, dp(fx.style ? 3 : 9), 0x9906121E, w.alphaK);
            if (fx.style) brackets(c, r, alpha(w.col, (int) (220 * w.alphaK)), dp(6) * w.scale, dp(1.3f));
            else {
                line.setColor(alpha(w.col, 200));
                line.setStrokeWidth(dp(1));
                c.drawRoundRect(r, dp(9), dp(9), line);
            }
            c.save();
            c.translate(r.left, r.top);
            c.scale(w.scale, w.scale);
            drawTagWords(c, w.name, w.facts, dp(6), dp(15), w.alphaK);
            c.restore();
        }
    }

    /** When each ring first showed (to grow it in once). */
    private final Map<Integer, Long> born = new HashMap<>();

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

    /** ✨ 3: four corner brackets round a box (the HUD's one shape). */
    private void brackets(Canvas c, RectF b, int col, float L, float w) {
        L = Math.min(L, Math.min(b.width(), b.height()) * 0.45f);
        line.setColor(col);
        line.setStrokeWidth(w);
        c.drawLine(b.left, b.top, b.left + L, b.top, line); c.drawLine(b.left, b.top, b.left, b.top + L, line);
        c.drawLine(b.right, b.top, b.right - L, b.top, line); c.drawLine(b.right, b.top, b.right, b.top + L, line);
        c.drawLine(b.left, b.bottom, b.left + L, b.bottom, line); c.drawLine(b.left, b.bottom, b.left, b.bottom - L, line);
        c.drawLine(b.right, b.bottom, b.right - L, b.bottom, line); c.drawLine(b.right, b.bottom, b.right, b.bottom - L, line);
    }

    // ================================================================ ✨ 5: glass pills (the picture behind them, soft)

    private Bitmap backdrop;
    private final Matrix glassM = new Matrix(), sheenM = new Matrix();
    private Shader sheenShader;
    private final Path glassPath = new Path();

    /** A small soft copy of what the camera shows (a few times a second), for the glass behind the pills. */
    void setBackdrop(Bitmap b) { backdrop = fx.glass ? b : null; backdropAt = SystemClock.uptimeMillis(); invalidate(); }
    private long backdropAt;

    /** A pill's background: glass when ✨ 5 is on (the soft picture + a light tint, a top light, a thin edge), else the old fill. */
    private void pill(Canvas c, RectF rr, float rad, int tint) { pill(c, rr, rad, tint, 1f); }

    /** k: how faded it is (0..1), for a pill that fades in and out with its words. */
    private void pill(Canvas c, RectF rr, float rad, int tint, float k) {
        if (!fx.glass) {
            fill.setColor(tint);
            c.drawRoundRect(rr, rad, rad, fill);
            return;
        }
        Bitmap bd = SystemClock.uptimeMillis() - backdropAt < 1500 ? backdrop : null; // (an old picture would show a scene that is gone)
        int W = getWidth(), H = getHeight();
        if (bd != null && live && W > 0 && H > 0 && bd.getWidth() > 0) {
            c.save();
            glassPath.reset();
            glassPath.addRoundRect(rr, rad, rad, Path.Direction.CW);
            c.clipPath(glassPath);
            glassM.setScale(W / (float) bd.getWidth(), H / (float) bd.getHeight());
            glassM.postTranslate(-curTx, -curTy);
            soft.setAlpha(Math.round(255 * k));
            c.drawBitmap(bd, glassM, soft);
            soft.setAlpha(255);
            c.restore();
            fill.setColor(alpha(tint, Math.round(0x80 * k)));
        } else fill.setColor(alpha(tint, Math.round(0xC0 * k)));
        c.drawRoundRect(rr, rad, rad, fill);
        if (sheenShader == null) sheenShader = new LinearGradient(0, 0, 0, dp(14), 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sheenM.setScale(1f, Math.max(1f, Math.min(rr.height(), dp(14))) / dp(14));
        sheenM.postTranslate(0, rr.top);
        sheenShader.setLocalMatrix(sheenM);
        sheen.setShader(sheenShader);
        sheen.setAlpha(Math.round(255 * k));
        c.drawRoundRect(rr, rad, rad, sheen);
        sheen.setShader(null);
        sheen.setAlpha(255);
        edge.setColor(alpha(0xFFFFFFFF, Math.round(0x38 * k)));
        edge.setStrokeWidth(dp(0.8f));
        c.drawRoundRect(rr, rad, rad, edge);
    }

    // ================================================================ the lock-on

    private long lockStart;
    private int lockTrack = -1, lockSteps;
    private final float[] lockBox = new float[4], lockDisp = new float[4];
    private boolean lockOn;
    private String cardTitleS = "", cardTextS = "", waiting = "";
    private Bitmap thumb;
    private long typeStart;
    private int[] clusters = new int[0];
    private final RectF card = new RectF();
    private boolean cardLeft;
    private float lockDist;
    private final android.graphics.Camera cam3d = new android.graphics.Camera();
    private final Matrix m3d = new Matrix();

    /** Locks on the thing in this box (0..1 of the screen); trackId: the live ring it is (-1 none). */
    void startLock(float[] box, int trackId) {
        System.arraycopy(box, 0, lockBox, 0, 4);
        System.arraycopy(box, 0, lockDisp, 0, 4);
        lockTrack = trackId;
        lockStart = SystemClock.uptimeMillis();
        lockOn = true;
        lockSteps = 0;
        lockDist = 0;
        cardTitleS = "";
        cardTextS = "";
        waiting = "చూస్తున్నాను";
        thumb = null;
        clusters = new int[0];
        card.setEmpty();
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
        System.arraycopy(box, 0, lockDisp, 0, 4);
        invalidate();
    }

    void setLockTrack(int id) { lockTrack = id; }
    int lockTrack() { return lockTrack; }
    float[] lockBox() { return lockBox.clone(); }
    /** ✨ 9: about how far the locked one is (metres, 0 unknown), shown on the card's line. */
    void setLockDist(float m) { lockDist = m; }

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

    void unlock() { lockOn = false; lockTrack = -1; thumb = null; card.setEmpty(); clearExtras(); invalidate(); }

    /** One more line typed onto the card (what the locked one is doing now). */
    void appendCard(String line) {
        if (!lockOn || line == null || line.isEmpty()) return;
        String old = cardTextS;
        String t = old.isEmpty() ? line : old + "\n" + line;
        if (t.length() > 420) { int cut = t.length() - 420; t = t.substring(cut); old = old.length() > cut ? old.substring(cut) : ""; }
        setCard(cardTitleS, t, thumb);
        int k = 0;
        while (k < clusters.length && clusters[k] <= old.length()) k++;
        typeStart = SystemClock.uptimeMillis() - 28L * k; // (the old words stay; the new ones type)
    }
    boolean locked() { return lockOn; }
    private boolean lockActive() { return lockOn; }

    /** A tap on the card (it closes then). */
    boolean hitCard(float x, float y) { return lockOn && SystemClock.uptimeMillis() - lockStart > 1200 && card.contains(x - shiftX * getWidth(), y - shiftY * getHeight()); }

    // ---- more locks at once (a 2nd and 3rd ring in their own colours; the card is the first one's)
    private static final int[] EXTRA_COL = {Ui.C_GREEN, 0xFFC78BFF};
    private final List<float[]> extraBox = new ArrayList<>();
    private final List<Integer> extraTrack = new ArrayList<>();
    private final List<Long> extraAt = new ArrayList<>();

    /** Another lock (up to 2 more); false when full. */
    boolean addLock(float[] box, int trackId) {
        if (extraBox.size() >= 2) return false;
        extraBox.add(box.clone());
        extraTrack.add(trackId);
        extraAt.add(SystemClock.uptimeMillis());
        invalidate();
        return true;
    }

    int extraCount() { return extraBox.size(); }
    int extraTrack(int i) { return i < extraTrack.size() ? extraTrack.get(i) : -1; }
    float[] extraBox(int i) { return extraBox.get(i).clone(); }
    void setExtraTrack(int i, int id) { if (i < extraTrack.size()) extraTrack.set(i, id); }
    void setExtraBox(int i, float[] box) { if (i < extraBox.size()) { float[] b = extraBox.get(i); for (int k = 0; k < 4; k++) b[k] = b[k] * 0.5f + box[k] * 0.5f; invalidate(); } }
    void clearExtras() { extraBox.clear(); extraTrack.clear(); extraAt.clear(); invalidate(); }
    private boolean isExtraLock(int id) { return id >= 0 && extraTrack.contains(id); }

    private void drawExtras(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        for (int i = 0; i < extraBox.size(); i++) {
            float[] b = extraBox.get(i);
            int col = EXTRA_COL[i % EXTRA_COL.length];
            float cx = (b[0] + b[2]) / 2 * W, cy = (b[1] + b[3]) / 2 * H;
            float R = Math.max(dp(22), Math.min(Math.min(W, H) * 0.3f, 0.5f * Math.min((b[2] - b[0]) * W, (b[3] - b[1]) * H) + dp(6)));
            float k = ease((now - extraAt.get(i)) / 400f);
            float rr = R * (0.4f + 0.6f * k);
            if (fx.glow) {
                halo.setColor(alpha(col, 80));
                halo.setStrokeWidth(dp(7));
                c.drawCircle(cx, cy, rr, halo);
            }
            ring.setColor(alpha(col, 230));
            ring.setStrokeWidth(dp(fx.style ? 1.6f : 2.4f));
            c.drawCircle(cx, cy, rr, ring);
            float spin = (now % 5000) / 5000f * 360f;
            r.set(cx - rr - dp(8), cy - rr - dp(8), cx + rr + dp(8), cy + rr + dp(8));
            for (int q = 0; q < 3; q++) c.drawArc(r, spin + q * 120, 50, false, ring);
            String n = String.valueOf(i + 2);
            fill.setColor(alpha(col, 230));
            c.drawCircle(cx + rr * 0.72f, cy - rr * 0.72f, dp(9), fill);
            TextPaint np = num();
            np.setColor(0xFF06121E);
            c.drawText(n, cx + rr * 0.72f - np.measureText(n) / 2, cy - rr * 0.72f + dp(4.5f), np);
            np.setColor(0xFFFFFFFF);
        }
    }

    private void drawLock(Canvas c, long now) {
        if (!lockOn) return;
        int W = getWidth(), H = getHeight();
        long t = now - lockStart;
        float[] lb = lockBox;
        if (fx.smooth) { // ✨ 1: the lock ring glides too
            for (int k = 0; k < 4; k++) lockDisp[k] = HudMath.ease(lockDisp[k], lockBox[k], frameDt, 90);
            lb = lockDisp;
        }
        float cx = (lb[0] + lb[2]) / 2 * W, cy = (lb[1] + lb[3]) / 2 * H;
        float bw = (lb[2] - lb[0]) * W, bh = (lb[3] - lb[1]) * H;
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
        fill.setAlpha(255);
        fill.setShader(rg);
        c.drawCircle(cx, cy, rr * 1.25f, fill);
        fill.setShader(null);
        ring.setColor(alpha(acc, 220));
        ring.setStrokeWidth(dp(fx.style ? 1.1f : 1.4f));
        c.drawCircle(cx, cy, rr, ring);
        // the outer arc that closes round it
        float sweep = closed ? 360f : 360f * ease((t - 600) / 300f);
        r.set(cx - rr - dp(9), cy - rr - dp(9), cx + rr + dp(9), cy + rr + dp(9));
        if (fx.glow && sweep > 0) {
            haloSoft.setColor(alpha(closed ? lockCol : acc, 90));
            haloSoft.setStrokeWidth(dp(8));
            c.drawArc(r, -90, sweep, false, haloSoft);
        }
        ring.setColor(closed ? lockCol : alpha(acc, 240));
        ring.setStrokeWidth(dp(fx.style ? 2f : 3f));
        if (sweep > 0) c.drawArc(r, -90, sweep, false, ring);
        // turning brackets once locked
        if (closed) {
            float spin = (now % 6000) / 6000f * 360f;
            ring.setStrokeWidth(dp(fx.style ? 1.5f : 2f));
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
        float top = safeTop + dp(fx.strip ? 40 : 10), bottomLim = H - safeBottom - dp(10);
        float lay = cardHeight(cardW);
        float ay = Math.max(top, Math.min(bottomLim - lay, cy - lay / 2));
        float ex = cx + (cardLeft ? -1 : 1) * (rr + dp(9)), ey = cy;
        float k = ease((t - 900) / 250f);
        float mx = ex + (ax - ex) * 0.55f;
        Path p = new Path();
        p.moveTo(ex, ey);
        float[] mid = {mx, ay + dp(18)};
        if (k < 0.55f) { float q = k / 0.55f; p.lineTo(ex + (mid[0] - ex) * q, ey + (mid[1] - ey) * q); }
        else { p.lineTo(mid[0], mid[1]); float q = (k - 0.55f) / 0.45f; p.lineTo(mid[0] + (ax - mid[0]) * q, mid[1]); }
        if (fx.card3d || fx.glow) { // ✨ 9 / 10: the line has a soft glow under it
            halo.setColor(alpha(lockCol, 80));
            halo.setStrokeWidth(dp(5));
            c.drawPath(p, halo);
        }
        line.setColor(alpha(lockCol, 230));
        line.setStrokeWidth(dp(fx.style ? 1.2f : 1.6f));
        c.drawPath(p, line);
        fill.setColor(lockCol);
        c.drawCircle(ex, ey, dp(2.5f), fill);
        if (fx.card3d && k >= 0.55f) { // ✨ 9: a mark at the bend, and how far it is
            diamond(c, mid[0], mid[1], dp(4), lockCol);
            if (lockDist > 0) {
                TextPaint np = numSmall();
                String ds = "~" + (lockDist < 10 ? String.format(java.util.Locale.US, "%.1f", lockDist) : String.valueOf(Math.round(lockDist))) + " మీ";
                np.setColor(lockCol);
                float tx = cardLeft ? mid[0] + dp(6) : mid[0] - dp(6) - np.measureText(ds);
                c.drawText(ds, tx, mid[1] - dp(7), np);
                np.setColor(0xFFFFFFFF);
            }
        }
        if (t < 1150) return;
        step(3, true);
        // the card: opens, then types itself
        float h = lay * ease((t - 1150) / 220f);
        card.set(cardLeft ? dp(12) : W - dp(12) - cardW, ay, cardLeft ? dp(12) + cardW : W - dp(12), ay + h);
        int saved = c.save();
        if (fx.card3d) { // ✨ 9: the card turned a little toward the thing, with a shadow under it
            cam3d.save();
            cam3d.setLocation(0, 0, -18);
            cam3d.rotateY(cardLeft ? -9f : 9f);
            cam3d.getMatrix(m3d);
            cam3d.restore();
            float pcx = card.centerX(), pcy = card.centerY();
            m3d.preTranslate(-pcx, -pcy);
            m3d.postTranslate(pcx, pcy);
            c.concat(m3d);
            fill.setColor(0x59000000);
            r.set(card.left + dp(5), card.top + dp(7), card.right + dp(5), card.bottom + dp(7));
            c.drawRoundRect(r, dp(10), dp(10), fill);
        }
        if (fx.glass && !fx.card3d) pill(c, card, dp(fx.style ? 4 : 10), 0xE6061A28); // (a turned card can't line its glass up with the picture)
        else {
            fill.setAlpha(255);
            fill.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom, 0xE6061A28, 0xD9030B14, Shader.TileMode.CLAMP));
            c.drawRoundRect(card, dp(10), dp(10), fill);
            fill.setShader(null);
        }
        if (fx.style) brackets(c, card, alpha(acc, 220), dp(14), dp(1.4f));
        else {
            line.setColor(alpha(acc, 200));
            line.setStrokeWidth(dp(1.2f));
            c.drawRoundRect(card, dp(10), dp(10), line);
        }
        line.setColor(lockCol);
        line.setStrokeWidth(dp(fx.style ? 2f : 3f));
        c.drawLine(card.left + dp(10), card.top, card.left + dp(54), card.top, line); // a short bright edge on top
        if (h < lay * 0.98f) { c.restoreToCount(saved); return; }
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
        c.restoreToCount(saved);
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
        float maxH = Math.max(dp(120), getHeight() - safeTop - safeBottom - dp(fx.strip ? 50 : 20));
        return Math.min(maxH, dp(12) + head + dp(8) + bl.getHeight() + dp(14));
    }

    private final Map<String, StaticLayout> layouts = new HashMap<>(); // (the card's text is laid out once, not every frame)

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
        float lx = cx + (W / 2f - cx) * k, ly = cy + (H / 2f - cy) * k;
        if (fx.glow) {
            halo.setColor(alpha(accent(), a / 3));
            halo.setStrokeWidth(dp(3 + 10 * k) * 2.5f);
            c.drawCircle(lx, ly, rr, halo);
        }
        ring.setColor(alpha(accent(), a));
        ring.setStrokeWidth(dp(3 + 10 * k));
        c.drawCircle(lx, ly, rr, ring);
        ring.setStrokeWidth(dp(1.2f));
        ring.setColor(alpha(Ui.C_AMBER, a));
        c.drawCircle(lx, ly, rr * 0.86f, ring);
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
        grid.setAlpha(255); // (a paint's own alpha also dims its shader)
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
        glow.setAlpha(255);
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
        float s = dp(50), gap = dp(6), x = dp(34), y = safeTop + dp(fx.strip ? 40 : 10);
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
            if (fx.style) brackets(c, r, alpha(accent(), (int) (fade * k * 0.9f)), dp(8), dp(1.3f));
            else {
                line.setColor(alpha(accent(), (int) (fade * k * 0.85f)));
                line.setStrokeWidth(dp(1));
                c.drawRect(r, line);
            }
            String n = i < thumbNames.size() ? thumbNames.get(i) : "";
            small.setAlpha((int) (fade * k));
            c.drawText(n, xx, r.bottom + dp(12), small);
            small.setAlpha(255);
        }
    }

    // ================================================================ the edges: zoom, direction, tilt, time, battery

    private float zoom = 1f, optMax = 1f, heading = Float.NaN, roll = Float.NaN, pitch = Float.NaN, fov;
    private String time = "";
    private int battery = -1;
    private long bootStart;

    void setGauges(float zoom, float optMax, float heading, float roll, float pitch, String time, int battery) {
        this.zoom = zoom;
        this.optMax = optMax;
        this.heading = heading;
        this.roll = roll;
        this.pitch = pitch;
        this.time = time == null ? "" : time;
        this.battery = battery;
        invalidate();
    }

    /** How many degrees of the world the screen's height shows now (0 unknown): for the floor grid's true perspective. */
    void setFov(float deg) { fov = deg; }

    // ---- the living mesh: the picture's own corners, drifting into place
    private float[] mesh = new float[0];
    private long meshAt;

    void setPoints(float[] pts) {
        float[] nw = pts == null ? new float[0] : pts;
        if (mesh.length == 0 || nw.length == 0) { mesh = nw.clone(); meshAt = SystemClock.uptimeMillis(); invalidate(); return; }
        // each new point eases from the nearest old one (so the mesh flows instead of flickering)
        float[] out = new float[nw.length];
        for (int i = 0; i < nw.length / 2; i++) {
            float x = nw[i * 2], y = nw[i * 2 + 1], bx = x, by = y, bd = 0.0016f;
            for (int j = 0; j < mesh.length / 2; j++) {
                float dx = mesh[j * 2] - x, dy = mesh[j * 2 + 1] - y, dd = dx * dx + dy * dy;
                if (dd < bd) { bd = dd; bx = mesh[j * 2]; by = mesh[j * 2 + 1]; }
            }
            out[i * 2] = bx * 0.5f + x * 0.5f;
            out[i * 2 + 1] = by * 0.5f + y * 0.5f;
        }
        mesh = out;
        meshAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void drawMesh(Canvas c, long now) {
        if (mesh.length < 6 || dim) return;
        int W = getWidth(), H = getHeight();
        long age = now - meshAt;
        if (age > 2500) return;
        int a = age > 1800 ? (int) (90 * (1 - (age - 1800) / 700f)) : 90;
        int acc = accent();
        line.setColor(alpha(acc, a / 2));
        line.setStrokeWidth(dp(0.8f));
        int n = mesh.length / 2;
        float maxD = 0.09f * 0.09f;
        for (int i = 0; i < n; i++) {
            float x = mesh[i * 2] * W, y = mesh[i * 2 + 1] * H;
            if (y < safeTop || y > H - safeBottom) continue;
            int links = 0;
            for (int j = i + 1; j < n && links < 3; j++) {
                float dx = mesh[j * 2] - mesh[i * 2], dy = mesh[j * 2 + 1] - mesh[i * 2 + 1];
                if (dx * dx + dy * dy < maxD) { c.drawLine(x, y, mesh[j * 2] * W, mesh[j * 2 + 1] * H, line); links++; }
            }
        }
        boolean deep = depthFresh(now);
        fill.setColor(alpha(acc, a + 60));
        for (int i = 0; i < n; i++) {
            float x = mesh[i * 2] * W, y = mesh[i * 2 + 1] * H;
            if (y < safeTop || y > H - safeBottom) continue;
            if (deep) { // ✨ 11: near points warm and bigger, far ones cool and small
                float nr = depthAt(mesh[i * 2], mesh[i * 2 + 1]);
                fill.setColor(alpha(mix(acc, Ui.C_AMBER, nr), a + 60));
                c.drawCircle(x, y, dp(0.9f + 1.6f * nr), fill);
            } else c.drawCircle(x, y, dp(1.4f), fill);
        }
    }

    private static int mix(int a, int b, float k) {
        k = Math.max(0, Math.min(1, k));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- the live board at the right: what is here (bars) and what just happened
    private final List<String[]> boardRows = new ArrayList<>(); // {name, count}
    private final List<String> events = new ArrayList<>();
    private long eventAt;

    void setBoard(Map<String, Integer> counts, List<String> lastEvents) {
        boardRows.clear();
        if (counts != null) {
            int k = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (k++ >= 5) break;
                String[] te = Detector.Labels.te(e.getKey());
                boardRows.add(new String[]{e.getValue() == 1 ? te[0] : te[1], String.valueOf(e.getValue())});
            }
        }
        if (lastEvents != null && !lastEvents.equals(events)) {
            events.clear();
            events.addAll(lastEvents);
            eventAt = SystemClock.uptimeMillis();
            eventShort = events.isEmpty() ? "" : events.get(0).replaceFirst("^\\S+\\s", ""); // (the strip shows it without its time)
        }
        invalidate();
    }

    private void drawBoard(Canvas c, long now) {
        if (fx.strip) return; // ✨ 4: it is in the strip at the top
        if (dim || !live || lockOn && !cardLeft) return;
        if (boardRows.isEmpty() && events.isEmpty()) return;
        int W = getWidth();
        float x1 = W - dp(14), w = dp(132), x0 = x1 - w, y = safeTop + dp(150);
        int acc = accent();
        TextPaint np = numSmall();
        for (String[] row : boardRows) {
            int n = Integer.parseInt(row[1]);
            small.setAlpha(230);
            c.drawText(row[0], x0, y, small);
            String cnt = row[1];
            np.setAlpha(230);
            c.drawText(cnt, x1 - np.measureText(cnt), y, np);
            float bw = w * Math.min(1f, n / 6f);
            fill.setColor(alpha(acc, 60));
            r.set(x0, y + dp(3), x1, y + dp(6));
            c.drawRoundRect(r, dp(2), dp(2), fill);
            fill.setColor(alpha(acc, 200));
            r.set(x0, y + dp(3), x0 + bw, y + dp(6));
            c.drawRoundRect(r, dp(2), dp(2), fill);
            y += dp(19);
        }
        np.setAlpha(255);
        if (!events.isEmpty()) {
            y += dp(6);
            line.setColor(alpha(acc, 90));
            line.setStrokeWidth(dp(1));
            c.drawLine(x0, y - dp(10), x1, y - dp(10), line);
            long age = now - eventAt;
            for (int i = 0; i < events.size() && i < 4; i++) {
                int a = i == 0 && age < 400 ? (int) (255 * age / 400f) : 255 - i * 45;
                small.setAlpha(a);
                String e = events.get(i);
                if (small.measureText(e) > w) { while (e.length() > 2 && small.measureText(e + "…") > w) e = e.substring(0, e.length() - 1); e += "…"; }
                c.drawText(e, x0, y, small);
                y += dp(15);
            }
        }
        small.setAlpha(255);
    }

    private String stripKey = "", stripMid = "", eventShort = "";

    /** ✨ 4: one strip at the top instead of the board, the log and the heading tape: direction · what is here · what just happened · time. */
    private void drawStrip(Canvas c, long now) {
        if (!fx.strip || !live) return;
        int W = getWidth();
        TextPaint np = numSmall();
        String head = Float.isNaN(heading) ? "" : Math.round((heading % 360 + 360) % 360) + "° " + direction(heading);
        StringBuilder mid = new StringBuilder();
        for (String[] row : boardRows) { if (mid.length() > 0) mid.append("  "); mid.append(row[0]).append(' ').append(row[1]); }
        String ev = !events.isEmpty() && now - eventAt < 8000 ? eventShort : "";
        String right = time.isEmpty() ? "" : time + (battery >= 0 ? " · " + battery + "%" : "");
        float maxW = W - dp(24) - dp(20), sep = dp(16);
        String key = W + "|" + fx.style + "|" + Math.round(np.measureText(head) / dp(4)) + "|" + mid + "|" + ev + "|" + Math.round(np.measureText(right) / dp(4)); // (the heading's digits change every frame; its width hardly)
        if (!key.equals(stripKey)) { // (trimmed to fit only when something in it changed)
            stripKey = key;
            String m = mid.toString();
            float room = maxW - np.measureText(head) - np.measureText(right) - (head.isEmpty() ? 0 : sep) - (right.isEmpty() ? 0 : sep);
            if (!ev.isEmpty() && small.measureText(m) + sep + small.measureText(ev) > room) ev = "";
            if (small.measureText(m) > room) {
                while (m.length() > 2 && small.measureText(m + "…") > room) m = m.substring(0, m.length() - 1);
                m = m.length() <= 2 ? "" : m + "…";
            }
            stripMid = m + (ev.isEmpty() ? "" : (m.isEmpty() ? "" : "   ") + ev);
        }
        String midAll = stripMid;
        String[] seg = {head, midAll, right};
        TextPaint[] pt = {np, small, np};
        float total = dp(20);
        int parts = 0;
        for (int i = 0; i < 3; i++) if (!seg[i].isEmpty()) { total += pt[i].measureText(seg[i]); parts++; }
        if (parts == 0) return;
        total += sep * (parts - 1);
        float x0 = (W - total) / 2f, y0 = safeTop + dp(6);
        r.set(x0, y0, x0 + total, y0 + dp(24));
        pill(c, r, dp(fx.style ? 4 : 12), 0xA6041019);
        if (fx.style) brackets(c, r, alpha(accent(), 170), dp(7), dp(1.2f));
        float x = x0 + dp(10), base = y0 + dp(16.5f);
        for (int i = 0; i < 3; i++) {
            if (seg[i].isEmpty()) continue;
            if (i == 0) np.setColor(Ui.C_AMBER);
            if (i == 2) np.setAlpha(220);
            c.drawText(seg[i], x, base, pt[i]);
            np.setColor(0xFFFFFFFF);
            x += pt[i].measureText(seg[i]) + sep;
        }
    }

    // ---- scanlines and a soft dark edge (depth)
    private Shader scanShader;
    private void drawScreenFx(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        if (!fx.strip) { // (✨ 4: no scanlines in the clean look)
            if (scanShader == null) {
                Bitmap b = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888);
                b.setPixel(0, 0, 0x14000000);
                scanShader = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            }
            fill.setAlpha(255);
            fill.setShader(scanShader);
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(null);
        }
        fill.setAlpha(255);
        fill.setShader(new RadialGradient(W / 2f, H / 2f, Math.max(W, H) * 0.75f, new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, fill);
        fill.setShader(null);
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

    /** Where the level horizon is below the middle (pixels): the true perspective when the view is known (✨ 8), else the old gentle one. */
    private float horizonDy(int H) {
        if (Float.isNaN(pitch)) return 0;
        return fx.grid ? HudMath.horizonDy(pitch, fov, H) : Math.max(-H * 0.22f, Math.min(H * 0.22f, pitch / 45f * H * 0.22f));
    }

    private void drawGauges(Canvas c, long now) {
        int W = getWidth(), H = getHeight();
        int acc = accent();
        float top = safeTop + dp(70), bottom = H - safeBottom - dp(24);
        if (bottom - top < dp(120)) return;
        TextPaint np = num(), nps = numSmall();
        // left: the zoom ladder (1× … 100×, the phone's own part and Jarvis's part)
        float k = bootK(now, 0);
        if (k > 0) {
            float x = dp(14), y0 = bottom, y1 = top;
            line.setColor(alpha(acc, (int) (130 * k)));
            line.setStrokeWidth(dp(fx.style ? 1f : 1.2f));
            c.drawLine(x, y0, x, y0 + (y1 - y0) * k, line);
            float[] marks = {1, 3, 5, 10, 30, 100};
            for (float m : marks) {
                float y = y0 + (y1 - y0) * (float) (Math.log(m) / Math.log(100));
                c.drawLine(x, y, x + dp(6), y, line);
                nps.setAlpha((int) (200 * k));
                c.drawText((m == Math.round(m) ? String.valueOf((int) m) : String.valueOf(m)) + "×", x + dp(8), y + dp(4), nps);
            }
            nps.setAlpha(255);
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
            np.setAlpha((int) (255 * k));
            String z = zoom < 10 ? String.format(java.util.Locale.US, "%.1f×", zoom) : Math.round(zoom) + "×";
            c.drawText(z, x + dp(30), y + dp(5), np);
            np.setAlpha(255);
        }
        // right: direction and tilt
        k = bootK(now, 1);
        if (k > 0) {
            float x = W - dp(14), y = top + dp(6);
            if (!Float.isNaN(heading) && !(fx.strip && live)) { // (✨ 4: the direction is in the strip)
                String h = Math.round((heading % 360 + 360) % 360) + "° " + direction(heading);
                label.setAlpha((int) (255 * k));
                c.drawText(h, x - label.measureText(h), y, label);
                label.setAlpha(255);
            }
            if (!Float.isNaN(roll)) { // a small horizon in a circle
                float cx = x - dp(24), cy = y + dp(36), rr = dp(18);
                ring.setColor(alpha(acc, (int) (150 * k)));
                ring.setStrokeWidth(dp(fx.style ? 1f : 1.2f));
                c.drawCircle(cx, cy, rr, ring);
                c.save();
                c.rotate(-roll, cx, cy);
                boolean level = Math.abs(roll) < 2f;
                line.setColor(alpha(level ? Ui.C_GREEN : Ui.C_AMBER, (int) (230 * k)));
                line.setStrokeWidth(dp(fx.style ? 1.5f : 2f));
                c.drawLine(cx - rr + dp(4), cy, cx + rr - dp(4), cy, line);
                c.restore();
                String rs = Math.round(roll) + "°";
                nps.setAlpha((int) (220 * k));
                c.drawText(rs, cx - nps.measureText(rs) / 2, cy + rr + dp(13), nps);
                nps.setAlpha(255);
            }
        }
        // right, lower: time and battery
        k = bootK(now, 2);
        if (k > 0 && !time.isEmpty() && !(fx.strip && live)) {
            float x = W - dp(14), y = top + dp(100);
            String s = time + (battery >= 0 ? "  🔋" + battery + "%" : "");
            nps.setAlpha((int) (230 * k));
            c.drawText(s, x - nps.measureText(s), y, nps);
            nps.setAlpha(255);
        }
        // the heading tape along the top (N · NE · E …), sliding with the direction
        k = bootK(now, 1);
        if (k > 0 && !Float.isNaN(heading) && live && !fx.strip) {
            float ty = safeTop + dp(10), half = W * 0.36f, pxPerDeg = half / 45f;
            float hd = (heading % 360 + 360) % 360;
            c.save();
            c.clipRect(W / 2f - half, ty - dp(14), W / 2f + half, ty + dp(14));
            line.setColor(alpha(acc, (int) (110 * k)));
            line.setStrokeWidth(dp(1));
            for (int dgr = -50; dgr <= 50; dgr += 10) {
                float deg = Math.round(hd / 10f) * 10 + dgr;
                float x = W / 2f + (deg - hd) * pxPerDeg;
                int d360 = ((int) deg % 360 + 360) % 360;
                boolean major = d360 % 45 == 0;
                c.drawLine(x, ty + dp(major ? 2 : 6), x, ty + dp(11), line);
                if (major) {
                    String[] n = {"ఉ", "ఈ", "తూ", "ఆ", "ద", "నై", "ప", "వా"};
                    String lab = n[d360 / 45];
                    small.setAlpha((int) (230 * k));
                    c.drawText(lab, x - small.measureText(lab) / 2, ty, small);
                    small.setAlpha(255);
                }
            }
            c.restore();
            fill.setColor(alpha(Ui.C_AMBER, (int) (240 * k)));
            Path p = new Path();
            p.moveTo(W / 2f, ty + dp(13)); p.lineTo(W / 2f - dp(4), ty + dp(18)); p.lineTo(W / 2f + dp(4), ty + dp(18)); p.close();
            c.drawPath(p, fill);
        }
        // the centre: a slowly turning reticle (hidden while something is locked), and the horizon line
        k = bootK(now, 3);
        if (k > 0 && live && !lockOn && bottom - top > dp(160)) {
            float cx = W / 2f, cy = H / 2f, R = dp(26) * (0.6f + 0.4f * k);
            float spin = (now % 20000) / 20000f * 360f;
            r.set(cx - R, cy - R, cx + R, cy + R);
            if (fx.glow) {
                haloSoft.setColor(alpha(acc, (int) (60 * k)));
                haloSoft.setStrokeWidth(dp(4));
                for (int q = 0; q < 4; q++) c.drawArc(r, spin + q * 90 + 8, 74, false, haloSoft);
            }
            ring.setColor(alpha(acc, (int) (150 * k)));
            ring.setStrokeWidth(dp(fx.style ? 1f : 1.2f));
            for (int q = 0; q < 4; q++) c.drawArc(r, spin + q * 90 + 8, 74, false, ring);
            ring.setStrokeWidth(dp(1));
            for (int q = 0; q < 4; q++) {
                double ang = Math.toRadians(q * 90);
                c.drawLine(cx + (float) Math.cos(ang) * (R + dp(3)), cy + (float) Math.sin(ang) * (R + dp(3)), cx + (float) Math.cos(ang) * (R + dp(9)), cy + (float) Math.sin(ang) * (R + dp(9)), ring);
            }
            fill.setColor(alpha(acc, (int) (200 * k)));
            c.drawCircle(cx, cy, dp(1.6f), fill);
            if (!Float.isNaN(roll)) { // the horizon: a thin line that stays level while the phone tilts, up / down with the pitch
                float dy = Math.max(-H * 0.22f, Math.min(H * 0.22f, horizonDy(H)));
                c.save();
                c.rotate(-roll, cx, cy + dy);
                line.setColor(alpha(acc, (int) (70 * k)));
                line.setStrokeWidth(dp(1));
                c.drawLine(cx - W * 0.3f, cy + dy, cx - R - dp(14), cy + dy, line);
                c.drawLine(cx + R + dp(14), cy + dy, cx + W * 0.3f, cy + dy, line);
                c.restore();
            }
        }
        // the four corners light up once at the start, and a ring sweeps out from the middle
        if (bootStart > 0 && now - bootStart < 1300) {
            float bk = ease((now - bootStart) / 900f);
            ring.setColor(alpha(acc, (int) (160 * (1 - bk))));
            ring.setStrokeWidth(dp(2));
            c.drawCircle(W / 2f, H / 2f, Math.max(W, H) * 0.7f * bk, ring);
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

    // ================================================================ ✨ 7: the edges lag a little when the phone turns

    private float chromeX, chromeY;

    /**
     * The gyroscope (rad/s about the phone's X and Y, dt seconds); kH: screen heights per radian (from the view). The
     * world slides on the screen when the phone turns; the HUD's edges follow a little, then settle back.
     */
    void gyro(float wx, float wy, float dt, float kH) {
        if (!fx.parallax || !live || dt <= 0 || dt > 0.1f) return;
        int H = getHeight();
        if (H == 0) return;
        float mx = wy * dt * kH * H, my = wx * dt * kH * H;
        float ox = chromeX, oy = chromeY;
        chromeX = HudMath.parallax(chromeX, mx, 0, dp(10));
        chromeY = HudMath.parallax(chromeY, my, 0, dp(10));
        if (chromeX != ox || chromeY != oy) postInvalidateOnAnimation();
    }

    // ================================================================ ✨ 8: the floor grid

    private void drawGround(Canvas c, long now) {
        if (!fx.grid || !live || Float.isNaN(pitch) || Float.isNaN(roll)) return;
        int W = getWidth(), H = getHeight();
        float cx = W / 2f, hy = H / 2f + horizonDy(H), bottom = H - safeBottom;
        if (hy > bottom - dp(40)) return; // (looking up: no floor to show)
        float kpx = fov > 1f && fov < 179f ? (float) (H / (2 * Math.tan(Math.toRadians(fov / 2)))) : H * 0.9f;
        int acc = accent();
        float k = bootK(now, 3) * (lockOn ? 0.5f : 1f) * (dim ? 0.6f : 1f);
        if (k <= 0) return;
        c.save();
        c.rotate(-roll, cx, hy);
        c.clipRect(-W, Math.max(hy, safeTop), 2 * W, bottom + H); // (below the horizon only)
        // rows across, every so many metres away (from about 1.4 m up), fainter far away
        float[] dist = {1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f, 13f, 17f, 22f, 30f};
        float[] rows = HudMath.groundRows(hy, kpx, 1.4f, dist, H * 1.6f);
        grid.setShader(null);
        grid.setStrokeWidth(dp(0.9f));
        for (float y : rows) {
            float near = Math.max(0f, Math.min(1f, (y - hy) / Math.max(1f, bottom - hy)));
            grid.setColor(alpha(acc, (int) ((14 + 46 * near) * k)));
            c.drawLine(-W, y, 2 * W, y, grid);
        }
        // lines out to the far point, sliding as he turns (a metre apart)
        float slide = Float.isNaN(heading) ? 0 : ((heading % 8 + 8) % 8) / 8f;
        if (groundShader == null || groundCol != acc) { groundCol = acc; groundShader = new LinearGradient(0, 0, 0, 1, alpha(acc, 0), alpha(acc, 70), Shader.TileMode.CLAMP); }
        groundM.setScale(1f, Math.max(1f, bottom - hy));
        groundM.postTranslate(0, hy);
        groundShader.setLocalMatrix(groundM);
        grid.setAlpha(Math.round(255 * Math.min(1f, k)));
        grid.setShader(groundShader);
        float dNear = 1.2f, yNear = hy + kpx * 1.4f / dNear;
        for (int i = -14; i <= 14; i++) {
            float xm = i - slide;
            c.drawLine(cx, hy, cx + kpx * xm / dNear, yNear, grid);
        }
        grid.setShader(null);
        grid.setAlpha(255);
        c.restore();
    }

    private Shader groundShader;
    private int groundCol;
    private final Matrix groundM = new Matrix();

    // ================================================================ ✨ 11: the phone's own depth map

    private float[] depthGrid;
    private int depthW, depthH;
    private long depthAt;
    private Bitmap depthBmp;
    private int[] depthPx;
    private final RectF depthR = new RectF();

    /** A fresh depth map (0..1, 1 = nearest; gw×gh over the screen) from the phone's own model. */
    void setDepth(float[] g, int gw, int gh) {
        if (!fx.depth || g == null || g.length < gw * gh || gw <= 0 || gh <= 0) { depthGrid = null; invalidate(); return; }
        depthGrid = g;
        depthW = gw;
        depthH = gh;
        depthAt = SystemClock.uptimeMillis();
        if (depthBmp == null || depthBmp.getWidth() != gw || depthBmp.getHeight() != gh) {
            depthBmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            depthPx = new int[gw * gh];
        }
        for (int i = 0; i < gw * gh; i++) { // far = a light blue haze, near = clear
            float far = 1f - g[i];
            int a = (int) (Math.pow(far, 1.8) * 78);
            depthPx[i] = (a << 24) | 0x7FD8FF;
        }
        depthBmp.setPixels(depthPx, 0, gw, 0, 0, gw, gh);
        invalidate();
    }

    private boolean depthFresh(long now) { return fx.depth && depthGrid != null && now - depthAt < 2000; }

    private float depthNear(float[] box) {
        if (!depthFresh(SystemClock.uptimeMillis())) return -1;
        return HudMath.boxNear(depthGrid, depthW, depthH, box);
    }

    private float depthAt(float x, float y) {
        int gx = Math.max(0, Math.min(depthW - 1, (int) (x * depthW))), gy = Math.max(0, Math.min(depthH - 1, (int) (y * depthH)));
        return depthGrid[gy * depthW + gx];
    }

    private void drawDepth(Canvas c, long now) {
        if (!depthFresh(now) || depthBmp == null || dim) return;
        long age = now - depthAt;
        soft.setAlpha(age > 1500 ? (int) (255 * (1 - (age - 1500) / 500f)) : 255);
        depthR.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(depthBmp, null, depthR, soft);
        soft.setAlpha(255);
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
        float x = (W - lw) / 2f, y = safeTop + dp(fx.strip ? (thumbs.isEmpty() ? 42 : 118) : 16);
        r.set(x - dp(12), y - dp(6), x + lw + dp(12), y + l.getHeight() + dp(6));
        if (fx.glass) pill(c, r, dp(fx.style ? 4 : 14), 0xBE041019, a);
        else {
            fill.setColor(alpha(0xFF041019, (int) (190 * a)));
            c.drawRoundRect(r, dp(14), dp(14), fill);
        }
        if (fx.style) brackets(c, r, alpha(accent(), (int) (170 * a)), dp(8), dp(1.2f));
        else {
            line.setColor(alpha(accent(), (int) (170 * a)));
            line.setStrokeWidth(dp(1));
            c.drawRoundRect(r, dp(14), dp(14), line);
        }
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
    void setLive(boolean on) {
        if (!on && live) { ghosts.clear(); backdrop = null; depthGrid = null; chromeX = chromeY = 0; }
        live = on;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        frameDt = snapshot || lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        if (!snapshot) lastFrame = now;
        if (fx.parallax && !snapshot) { chromeX = HudMath.parallax(chromeX, 0, frameDt, dp(10)); chromeY = HudMath.parallax(chromeY, 0, frameDt, dp(10)); }
        curTx = curTy = 0;
        if (live) drawScreenFx(c);
        boolean lag = fx.parallax && (chromeX != 0 || chromeY != 0);
        if (lag) { c.save(); c.translate(chromeX, chromeY); curTx = chromeX; curTy = chromeY; }
        drawGauges(c, now);
        drawStrip(c, now);
        if (lag) { c.restore(); curTx = curTy = 0; }
        c.save();
        c.translate(shiftX * getWidth(), shiftY * getHeight()); // (the steadied picture's nudge)
        curTx = shiftX * getWidth();
        curTy = shiftY * getHeight();
        if (live) {
            drawDepth(c, now);
            drawGround(c, now);
            drawMesh(c, now);
            drawSweep(c, now);
            drawTracks(c, now);
        }
        drawLens(c, now);
        drawExtras(c, now);
        drawLock(c, now);
        c.restore();
        curTx = curTy = 0;
        if (lag) { c.save(); c.translate(chromeX, chromeY); curTx = chromeX; curTy = chromeY; }
        drawBoard(c, now);
        if (lag) { c.restore(); curTx = curTy = 0; }
        drawThumbs(c, now);
        drawRemark(c, now);
        if (snapshot) return;
        boolean moving = live && (!tracks.isEmpty() || sweepOn(now)) || lockOn || now - lensStart < 800 || !thumbs.isEmpty()
                || bootStart > 0 && now - bootStart < 1400
                || !remarkS.isEmpty() || live && mesh.length > 0 && now - meshAt < 2500 || !extraBox.isEmpty()
                || live && !Float.isNaN(heading) // (the reticle turns)
                || !ghosts.isEmpty() || lag || depthFresh(now);
        boolean typing = lockOn && waiting.isEmpty() && (now - typeStart) / 28 < clusters.length;
        boolean fast = lockOn && (now - lockStart < 1500 || typing) || sweepOn(now) || now - lensStart < 800;
        boolean smooth60 = fx.glow && !fx.saver; // ✨ 10: 60 frames a second (not in 🔋 saver)
        if (moving) postInvalidateDelayed(fast || smooth60 ? 16 : 33); // (a settled lock only turns its brackets: 30 a second is enough)
    }

    /** The HUD drawn onto a photo (for "📸 HUD తో ఫోటో"): the same as on the screen, at the photo's size. */
    void drawInto(Canvas c, int w, int h) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        c.save();
        c.scale(w / (float) W, h / (float) H);
        snapshot = true;
        try { onDraw(c); } finally { snapshot = false; }
        c.restore();
    }

}
