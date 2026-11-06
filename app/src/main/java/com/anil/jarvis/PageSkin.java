package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Paints a page (Settings, All Features, Duty, photos) in the chosen page theme (PageTheme): its own still background,
 * its card style (soft raised cards, glass, glowing edges, HUD corners, glowing tiles), its letters, switches and boxes.
 * The old three themes are not touched at all (forPage gives null): those pages look exactly as before.
 * <p>
 * apply(root) walks a page already built with the old colours and repaints what it finds (white glass → the theme's
 * cards, dark boxes → its typing boxes, white / grey letters → its ink, coloured letters made readable on it); run it
 * again after a part of the page is rebuilt. Each device keeps its own theme (its own prefs); the orb is never touched.
 */
final class PageSkin {
    final PageTheme t;
    private final Context c;
    private final float dp;
    /** The letter colour each TextView was given last (a colour set afresh by the page is repainted again). */
    private final java.util.WeakHashMap<TextView, Integer> lastText = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<IconView, Integer> lastIcon = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<View, Boolean> tinted = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<TextView, Shader> ourShader = new java.util.WeakHashMap<>();
    /** Backgrounds already given the theme's fill (never twice). */
    private final java.util.WeakHashMap<Drawable, Boolean> doneBg = new java.util.WeakHashMap<>();
    private int tileGlow;

    private PageSkin(Context c, PageTheme t) {
        this.c = c;
        this.t = t;
        this.dp = c.getResources().getDisplayMetrics().density;
    }

    // ================================================================ which theme

    /** The page theme this device uses now (his choice in Settings; else the old theme). */
    static PageTheme current(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        Prefs p = new Prefs(c); // (the home tablet and the car box never wear the phone's look)
        String look = p.homeMode() || p.carMode() ? "old" : sp.getString("main_look", "old");
        return PageTheme.resolve(sp.getString("page_theme", ""), sp.getString("theme", "mix"), sp.getBoolean("pages_follow_look", true), look);
    }

    /** A painter for this page, or null when the page keeps its old colours (one of the old three). */
    static PageSkin forPage(Context c) {
        PageTheme t = current(c);
        return t.isNew() ? new PageSkin(c, t) : null;
    }

    static PageSkin of(Context c, PageTheme t) { return new PageSkin(c, t); }

    /** Goes up when the page theme changes (an open page rebuilds itself, like Ui.themeVersion). */
    static String stamp(Context c) { return current(c).id; }

    /** Saves his page theme: one of the new ten (pages only), or one of the old three (pages, main screen, watch). */
    static void choose(Context c, String id) {
        if (PageTheme.isOldId(id)) {
            c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("page_theme", "").apply();
            Ui.setTheme(c, id, Ui.hudMotion(c));
        } else {
            c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("page_theme", id).apply();
        }
    }

    // ================================================================ sizes

    int px(float v) { return Math.round(v * dp); }

    // ================================================================ the page background (still; drawn once per size)

    Drawable background() { return new Back(t); }

    /** A theme's background picture: gradients, soft lights and its own shapes, drawn small once and stretched. */
    static final class Back extends Drawable {
        private final PageTheme t;
        private Bitmap pic;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);

        Back(PageTheme t) { this.t = t; }

        @Override protected void onBoundsChange(Rect b) {
            int w = Math.max(1, b.width() / 2), h = Math.max(1, b.height() / 2);
            if (pic != null && pic.getWidth() == w && pic.getHeight() == h) return;
            try {
                pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                paint(new Canvas(pic), w, h, t);
            } catch (Throwable e) { pic = null; } // (out of memory: the plain gradient below)
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            if (pic != null) { cv.drawBitmap(pic, null, b, p); return; }
            Paint g = new Paint();
            g.setShader(new LinearGradient(0, b.top, 0, b.bottom, t.bgTop, t.bgBottom, Shader.TileMode.CLAMP));
            cv.drawRect(b, g);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Draws theme t's background on a w×h canvas (also the small pictures in the theme chooser). */
    static void paint(Canvas cv, int w, int h, PageTheme t) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        java.util.Random rnd = new java.util.Random(7);
        if (t.bg == PageTheme.BG_CLOUDS) { // evening sky: blue-grey, a warm band, darker below
            g.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF4D6878, 0xFF7E828C, 0xFFA8867C, 0xFFB98463, 0xFF7B676B, 0xFF2A2C34},
                    new float[]{0f, 0.3f, 0.45f, 0.5f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        } else {
            g.setShader(new LinearGradient(0, 0, 0, h, t.bgTop, t.bgBottom, Shader.TileMode.CLAMP));
        }
        cv.drawRect(0, 0, w, h, g);
        g.setShader(null);
        float d = Math.min(w, h);
        switch (t.bg) {
            case PageTheme.BG_SOFT:
                glow(cv, 0xFFFFFFFF, w * .2f, h * .05f, w * .6f, .55f);
                break;
            case PageTheme.BG_LEAVES:
                glow(cv, 0xFFFFFFFF, w * .3f, h * .1f, w * .7f, .6f);
                leaf(cv, w * 1.02f, h * .02f, d * .85f, 2.55f, .55f);
                leaf(cv, w * .98f, h * .11f, d * .62f, 2.9f, .45f);
                leaf(cv, -w * .04f, h * .47f, d * .72f, -.35f, .5f);
                leaf(cv, w * 1.03f, h * .93f, d * .8f, 3.6f, .55f);
                leaf(cv, -w * .03f, h * .98f, d * .56f, -.8f, .42f);
                float[][] drops = {{.62f, .06f, 11}, {.05f, .36f, 9}, {.93f, .42f, 13}, {.5f, .555f, 7}, {.97f, .7f, 9}, {.07f, .8f, 10}, {.36f, .985f, 8}};
                for (float[] q : drops) drop(cv, w * q[0], h * q[1], q[2] * d / 360f);
                break;
            case PageTheme.BG_CLOUDS: // soft cloud puffs (never brighter than PageTheme.pageWorst)
                for (int i = 0; i < 18; i++) glow(cv, 0xFFC8C0C6, rnd.nextFloat() * w, rnd.nextFloat() * h * .42f, d * (.18f + rnd.nextFloat() * .25f), .22f);
                float[][] puffs = {{.15f, .6f, 90}, {.85f, .66f, 110}, {.4f, .78f, 120}, {.9f, .86f, 100}, {.1f, .92f, 120}, {.6f, .97f, 130}};
                for (float[] q : puffs) {
                    float s = q[2] * d / 360f;
                    for (int k = 0; k < 16; k++) {
                        float x = w * q[0] + (rnd.nextFloat() - .5f) * s * 2.4f, y = h * q[1] + (rnd.nextFloat() - .5f) * s * .7f;
                        glow(cv, 0xFFD2CBD0, x, y, s * (.3f + rnd.nextFloat() * .35f), .8f);
                    }
                }
                break;
            case PageTheme.BG_DARK:
                glow(cv, 0xFF3A3E48, w * .5f, h * .3f, w * .9f, .6f);
                glow(cv, 0xFF8B5CF6, w * .15f, h * .2f, w * .35f, .08f);
                glow(cv, 0xFF22D3EE, w * .9f, h * .75f, w * .4f, .06f);
                break;
            case PageTheme.BG_NEON:
                glow(cv, 0xFFFF2FD0, w * .1f, h * .12f, w * .55f, .42f);
                glow(cv, 0xFF2F5BFF, w * .95f, h * .13f, w * .55f, .45f);
                glow(cv, 0xFF9DFF3A, w * .05f, h * .24f, w * .35f, .18f);
                glow(cv, 0xFF7B3BFF, w * .85f, h * .9f, w * .5f, .2f);
                glow(cv, 0xFFFF2FD0, w * .1f, h * .75f, w * .4f, .1f);
                g.setColor(0x13FFFFFF);
                float step = Math.max(6, d / 24f);
                for (float y = step / 2; y < h; y += step) for (float x = step / 2; x < w; x += step) cv.drawRect(x, y, x + 1.5f, y + 1.5f, g);
                break;
            case PageTheme.BG_LAMPS: // a lamp-lit room, out of focus
                glow(cv, 0xFFFFAA5A, w * .5f, 0, w * .9f, .32f);
                glow(cv, 0xFFFF8C32, w * .95f, h * .45f, w * .45f, .3f);
                glow(cv, 0xFFFFC882, w * .24f, h * .2f, d * .3f, .35f);
                glow(cv, 0xFFFF9646, w * .5f, h * .67f, w * .7f, .14f);
                for (int i = 0; i < 16; i++) {
                    float r = d * (.02f + rnd.nextFloat() * .06f);
                    glow(cv, 0xFFFFB050, rnd.nextFloat() * w, rnd.nextFloat() * h, r, .18f + rnd.nextFloat() * .2f);
                }
                break;
            case PageTheme.BG_WIRE: { // a HUD floor of lines towards the horizon
                g.setStyle(Paint.Style.STROKE);
                g.setStrokeWidth(Math.max(1, d / 360f));
                g.setColor(PageTheme.withAlpha(t.accent, 0x30));
                float hz = h * .55f;
                for (int k = 0; k < 12; k++) { float y = hz + (float) Math.pow(k / 11f, 1.9f) * (h - hz); cv.drawLine(0, y, w, y, g); }
                for (int k = -12; k <= 12; k++) cv.drawLine(w / 2f + k * d * .045f, hz, w / 2f + k * d * .42f, h, g);
                g.setColor(PageTheme.withAlpha(t.accent, 0x14));
                for (float x = 0; x < w; x += d / 7.5f) cv.drawLine(x, 0, x, hz, g);
                for (float y = 0; y < hz; y += d / 7.5f) cv.drawLine(0, y, w, y, g);
                g.setStyle(Paint.Style.FILL);
                break;
            }
            case PageTheme.BG_BLOBS:
                glow(cv, 0xFFB9A8FF, w * .15f, h * .15f, w * .6f, .55f);
                glow(cv, 0xFF8FE3F0, w * .9f, h * .45f, w * .6f, .5f);
                glow(cv, 0xFFFFC2E6, w * .2f, h * .85f, w * .6f, .5f);
                break;
            case PageTheme.BG_NAVY:
                glow(cv, 0xFF3B8BFF, w * .8f, h * .1f, w * .7f, .3f);
                glow(cv, 0xFF1D4ED8, w * .1f, h * .8f, w * .7f, .25f);
                break;
            case PageTheme.BG_LIQUID: // the liquid look's colours, standing still in the pages
                glow(cv, 0xFFFF3D9A, w * .15f, h * .2f, w * .8f, .45f);
                glow(cv, 0xFF7B3CFF, w * .9f, h * .45f, w * .8f, .5f);
                glow(cv, 0xFF18A6B6, w * .2f, h * .85f, w * .8f, .45f);
                break;
            case PageTheme.BG_SPACE: { // stars and a soft nebula
                glow(cv, 0xFF6A3CFF, w * .8f, h * .25f, w * .7f, .28f);
                glow(cv, 0xFF2F6BFF, w * .1f, h * .7f, w * .7f, .22f);
                g.setColor(0xFFFFFFFF);
                for (int i = 0; i < 140; i++) {
                    g.setAlpha(60 + rnd.nextInt(170));
                    float r = rnd.nextFloat() < .1f ? d / 300f : d / 700f;
                    cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, Math.max(.6f, r), g);
                }
                g.setAlpha(255);
                break;
            }
            case PageTheme.BG_TILES:
                glow(cv, 0xFF3A6BFF, w * .85f, h * .12f, w * .6f, .18f);
                glow(cv, 0xFFE0408A, w * .1f, h * .55f, w * .5f, .1f);
                glow(cv, 0xFF16A34A, w * .9f, h * .9f, w * .5f, .1f);
                break;
            default:
        }
    }

    /** A soft round light (colour at strength a in the middle, nothing at radius r). */
    private static void glow(Canvas cv, int color, float x, float y, float r, float a) {
        if (r <= 0) return;
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(x, y, r, PageTheme.withAlpha(color, Math.round(255 * a)), PageTheme.withAlpha(color, 0), Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r, g);
    }

    /** A big soft leaf (our own drawing) at x, y, length len, turned by rot radians. */
    private static void leaf(Canvas cv, float x, float y, float len, float rot, float a) {
        cv.save();
        cv.translate(x, y);
        cv.rotate((float) Math.toDegrees(rot));
        Path p = new Path();
        float wd = len * .32f;
        p.moveTo(0, 0);
        p.quadTo(len * .45f, -wd, len, 0);
        p.quadTo(len * .45f, wd, 0, 0);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new LinearGradient(0, -wd, 0, wd, PageTheme.withAlpha(0xFF7BE0BB, Math.round(255 * a)),
                PageTheme.withAlpha(0xFF2F9E78, Math.round(255 * a)), Shader.TileMode.CLAMP));
        cv.drawPath(p, g);
        g.setShader(null);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(Math.max(1, len / 160f));
        g.setColor(PageTheme.withAlpha(0xFFFFFFFF, Math.round(120 * a)));
        cv.drawLine(len * .04f, 0, len * .92f, 0, g);
        for (int i = 1; i < 7; i++) {
            float sx = len * i / 7.5f;
            cv.drawLine(sx, 0, sx + len * .07f, -wd * .55f * (1 - i / 9f), g);
            cv.drawLine(sx, 0, sx + len * .07f, wd * .55f * (1 - i / 9f), g);
        }
        cv.restore();
    }

    /** A water drop: a clear round with a bright spot. */
    private static void drop(Canvas cv, float x, float y, float r) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(x - r * .3f, y - r * .35f, r * 1.2f, 0xCCFFFFFF, 0x2296D9BE, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r, g);
        g.setShader(null);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(Math.max(1, r / 6));
        g.setColor(0x5596D9BE);
        cv.drawCircle(x, y, r, g);
    }

    // ================================================================ cards, boxes, chips

    /** A card in the theme's style (corner in dp); children of it read as "in a card". */
    Drawable card(float radiusDp) { return card(radiusDp, t.card, t.card2); }

    private Drawable card(float radiusDp, int fill, int fill2) {
        float r = px(radiusDp), rs = px(Math.min(radiusDp, 26)); // (a raised card's shadow picture stays small: pills too)
        switch (t.style) {
            case PageTheme.NEU: return new Raised(fill, t.edge, rs, px(5), px(9), t.shadowDark, t.shadowLight);
            case PageTheme.TILE: {
                int glowC = t.glows.length == 0 ? 0 : t.glows[tileGlow++ % t.glows.length];
                return new Raised(fill, 0, rs, px(7), px(12), PageTheme.withAlpha(glowC, 0x99), 0);
            }
            case PageTheme.GLOW_EDGE: return new Edged(fill, fill2, t.edge, t.edge2, r, px(1.3f), false);
            case PageTheme.CUT: return new Edged(fill, fill2, t.edge, t.edge, px(10), px(1.5f), true);
            default: { // GLASS
                if (t.shadowDark != 0) return new Raised(fill, t.edge, rs, px(4), px(10), t.shadowDark, 0);
                GradientDrawable gd = new Box(GradientDrawable.Orientation.TL_BR, new int[]{fill, fill2});
                gd.setCornerRadius(r);
                if (t.edge != 0) gd.setStroke(px(1), t.edge);
                return new Marked(gd);
            }
        }
    }

    /** A box this painter made (never repainted again). */
    static final class Box extends GradientDrawable {
        /** What is inside reads as in a card (a typing box: the card's ink, also when it sits on the page). */
        boolean inside;
        Box() { super(); }
        Box(GradientDrawable.Orientation o, int[] colors) { super(o, colors); }
    }

    /** A typing box / a pill inside a card. */
    Drawable field(float radiusDp) {
        Box gd = new Box();
        gd.inside = true;
        gd.setColor(t.field);
        gd.setCornerRadius(px(radiusDp));
        if (t.fieldEdge != 0) gd.setStroke(px(1), t.fieldEdge);
        return gd;
    }

    /** A button in a card: the theme's colour, soft. */
    Drawable button(float radiusDp) {
        GradientDrawable gd = new Box(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{PageTheme.withAlpha(t.accent, t.lightCard ? 0x24 : 0x40), PageTheme.withAlpha(t.accent2, t.lightCard ? 0x14 : 0x22)});
        gd.setCornerRadius(px(radiusDp));
        gd.setStroke(px(1), PageTheme.withAlpha(t.accent, t.lightCard ? 0x66 : 0x88));
        return gd;
    }

    /** A small round chip (filled with colour at alpha, or the inner box colour). */
    Drawable chip(int fill, int stroke, float radiusDp) {
        GradientDrawable gd = new Box();
        gd.setColor(fill);
        gd.setCornerRadius(px(radiusDp));
        if (stroke != 0) gd.setStroke(px(1), stroke);
        return gd;
    }

    /** Marks a drawable as a theme card (what is inside reads as "in a card"). */
    interface Card {}

    static final class Marked extends android.graphics.drawable.InsetDrawable implements Card {
        Marked(Drawable d) { super(d, 0); }
    }

    /**
     * A raised card: its body and soft shadows (dark one below-right, light one above-left: Soft UI; or one coloured glow
     * under it: glowing tiles). The shadow picture is made once (small, 9 pieces) and stretched; it reaches out of the
     * card's own box (the page lets it: clipChildren off).
     */
    static final class Raised extends Drawable implements Card {
        private final int fill, edge, dark, light;
        private final float r, off, blur;
        private Bitmap slice;
        private int m; // the shadow's reach beyond the card
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), body = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        Raised(int fill, int edge, float r, float off, float blur, int dark, int light) {
            this.fill = fill; this.edge = edge; this.r = r; this.off = off; this.blur = blur; this.dark = dark; this.light = light;
            body.setColor(fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, r / 14));
            line.setColor(edge);
        }

        /** The shadow pictures, shared by every card of the same shape and colours (a page has hundreds of cards). */
        private static final java.util.Map<String, Bitmap> SLICES = new java.util.HashMap<>();

        private void make() {
            m = (int) Math.ceil(off + blur * 1.4f);
            int core = (int) Math.ceil(r) + 2, s = 2 * (core + m) + 1;
            String key = r + "|" + off + "|" + blur + "|" + dark + "|" + light;
            synchronized (SLICES) {
                Bitmap had = SLICES.get(key);
                if (had != null) { slice = had; return; }
                if (SLICES.size() > 24) SLICES.clear(); // (a theme change: the old ones go)
            }
            try {
                slice = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(slice); // (a bitmap's canvas: the blur works on every Android)
                Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
                sh.setMaskFilter(new BlurMaskFilter(Math.max(1, blur), BlurMaskFilter.Blur.NORMAL));
                RectF in = new RectF(m, m, s - m, s - m);
                if (light != 0) {
                    sh.setColor(light);
                    RectF q = new RectF(in); q.offset(-off, -off);
                    cv.drawRoundRect(q, r, r, sh);
                }
                if (dark != 0) {
                    sh.setColor(dark);
                    RectF q = new RectF(in); q.offset(light != 0 ? off : 0, off);
                    cv.drawRoundRect(q, r, r, sh);
                }
                // the card's own place is cleared: its body is drawn on top, in its own (maybe see-through) colour
                Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
                clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                cv.drawRoundRect(in, r, r, clear);
                synchronized (SLICES) { SLICES.put(key, slice); }
            } catch (Throwable e) { slice = null; }
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (slice == null && m == 0) make();
            if (slice != null) nine(cv, b);
            rf.set(b);
            float rr = Math.min(r, Math.min(b.width(), b.height()) / 2f);
            cv.drawRoundRect(rf, rr, rr, body);
            if (edge != 0) { rf.inset(line.getStrokeWidth() / 2, line.getStrokeWidth() / 2); cv.drawRoundRect(rf, rr, rr, line); }
        }

        /** The shadow picture's 9 pieces round the card's box b (corners kept, sides stretched). */
        private void nine(Canvas cv, Rect b) {
            int s = slice.getWidth(), k = (s - 1) / 2; // the picture's middle line is the stretched part
            int L = b.left - m, T = b.top - m, R = b.right + m, B = b.bottom + m;
            int[] sx = {0, k, k + 1, s}, sy = {0, k, k + 1, s};
            int[] dx = {L, L + k, R - (s - k - 1), R}, dy = {T, T + k, B - (s - k - 1), B};
            if (dx[1] > dx[2]) { int mid = (L + R) / 2; dx[1] = mid; dx[2] = mid; }
            if (dy[1] > dy[2]) { int mid = (T + B) / 2; dy[1] = mid; dy[2] = mid; }
            Rect src = new Rect(), dst = new Rect();
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                if (i == 1 && j == 1) continue; // (the middle: under the card)
                src.set(sx[i], sy[j], sx[i + 1], sy[j + 1]);
                dst.set(dx[i], dy[j], dx[i + 1], dy[j + 1]);
                if (dst.width() > 0 && dst.height() > 0) cv.drawBitmap(slice, src, dst, p);
            }
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A card with a glowing two-colour edge (✨), or with cut corners (HUD). */
    static final class Edged extends Drawable implements Card {
        private final int f1, f2, e1, e2;
        private final float r, w;
        private final boolean cut;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();

        Edged(int f1, int f2, int e1, int e2, float r, float w, boolean cut) {
            this.f1 = f1; this.f2 = f2; this.e1 = e1; this.e2 = e2; this.r = r; this.w = w; this.cut = cut;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(w);
        }

        @Override protected void onBoundsChange(Rect b) {
            body.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, f1, f2, Shader.TileMode.CLAMP));
            line.setShader(new LinearGradient(b.left, b.top, b.right, b.top, e1, e2, Shader.TileMode.CLAMP));
            rf.set(b);
            rf.inset(w / 2, w / 2);
            path.reset();
            if (cut) {
                float k = Math.min(r, Math.min(rf.width(), rf.height()) / 3);
                path.moveTo(rf.left + k, rf.top);
                path.lineTo(rf.right, rf.top);
                path.lineTo(rf.right, rf.bottom - k);
                path.lineTo(rf.right - k, rf.bottom);
                path.lineTo(rf.left, rf.bottom);
                path.lineTo(rf.left, rf.top + k);
                path.close();
            } else {
                float rr = Math.min(r, Math.min(rf.width(), rf.height()) / 2);
                path.addRoundRect(rf, rr, rr, Path.Direction.CW);
            }
        }

        @Override public void draw(Canvas cv) {
            cv.drawPath(path, body);
            cv.drawPath(path, line);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ================================================================ switches, sliders, choices

    void tint(View v) {
        if (v instanceof Switch) {
            int[][] st = {{android.R.attr.state_checked}, {}};
            ((Switch) v).setThumbTintList(new android.content.res.ColorStateList(st, new int[]{t.thumb, t.thumbOff}));
            ((Switch) v).setTrackTintList(new android.content.res.ColorStateList(st, new int[]{t.swOn, t.swOff}));
            ((Switch) v).setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        } else if (v instanceof SeekBar) {
            SeekBar sb = (SeekBar) v;
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(t.swOn));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(t.swOn));
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(t.swOff | 0xFF000000));
        } else if (v instanceof CompoundButton) { // radio buttons, check boxes
            ((CompoundButton) v).setButtonTintList(android.content.res.ColorStateList.valueOf(t.accent));
        }
    }

    // ================================================================ repainting a page built with the old colours

    /** Repaints root and everything in it (again after a part of it was rebuilt). */
    void apply(View root) { walk(root, false, false); }

    /** ... for a view that is already inside a card. */
    void applyInCard(View v) { walk(v, true, false); }

    /** keep: on a solid coloured button / badge the page drew (its letters were made for it: left as they are). */
    private void walk(View v, boolean inCard, boolean keep) {
        boolean card = inCard;
        Drawable bg = v.getBackground();
        if (!keep && bg instanceof GradientDrawable && !(bg instanceof Box) && !doneBg.containsKey(bg)) {
            int f = fillOf((GradientDrawable) bg);
            keep = PageTheme.a(f) >= 0x99 && PageTheme.chroma(f) >= 70; // (a really colourful button / badge)
        }
        if (bg instanceof Box && ((Box) bg).inside) card = true;
        if (bg instanceof Card) { card = true; letDrawOut(v); }
        else if (bg != null && doneBg.containsKey(bg)) { if (Boolean.TRUE.equals(doneBg.get(bg))) card = true; }
        else if (bg instanceof GradientDrawable && !(bg instanceof Box)) {
            GradientDrawable gd = (GradientDrawable) bg;
            int fill = fillOf(gd);
            int nf = fill == 0 ? 0 : t.fill(fill, inCard);
            if (nf != 0) {
                if (nf == t.card && (fill == Ui.GLASS || fill == Ui.GLASS2)) { // the old pages' glass card: the theme's own card
                    v.setBackground(card(cornerOf(gd) / dp));
                    letDrawOut(v);
                } else { // anything else keeps its shape and its edge (a day's mark, a gold line): only its fill changes
                    gd.mutate();
                    gd.setColor(nf);
                    doneBg.put(gd, nf == t.card);
                }
                if (nf == t.card) card = true;
            }
        }
        if (keep) { /* (letters made for that colour) */ }
        else if (v instanceof TextView) paintText((TextView) v, card);
        else if (v instanceof IconView) {
            IconView iv = (IconView) v;
            Integer last = lastIcon.get(iv);
            if (last == null || last != iv.color()) { int m = t.text(iv.color(), card); iv.setColor(m); lastIcon.put(iv, m); }
        }
        if ((v instanceof Switch || v instanceof SeekBar || v instanceof CompoundButton) && !tinted.containsKey(v)) { tint(v); tinted.put(v, Boolean.TRUE); }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (card) g.setClipChildren(false);
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), card, keep);
        }
    }

    /** A card's shadow / glow reaches out of its box: its parents up to the scrolling page let it. */
    private static void letDrawOut(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof ViewGroup && !(p instanceof android.widget.ScrollView)
                && ((View) p).getId() != android.R.id.content; p = p.getParent())
            ((ViewGroup) p).setClipChildren(false);
    }

    /** A TextView's letters (and a typing box's hint) in the theme's colours; done once per colour the page gave it. */
    private void paintText(TextView tv, boolean card) {
        android.content.res.ColorStateList cs = tv.getTextColors();
        if (cs != null && !cs.isStateful()) {
            int now = tv.getCurrentTextColor();
            Integer last = lastText.get(tv);
            if (last == null || last != now) {
                int m = t.text(now, card);
                tv.setTextColor(m);
                lastText.put(tv, m);
            }
        }
        if (tv instanceof EditText && tv.getCurrentHintTextColor() != t.faint) tv.setHintTextColor(t.faint);
        Shader sh = tv.getPaint().getShader();
        if (sh != null && sh != ourShader.get(tv)) { // (a title painted in a colour gradient: the theme's)
            float w = tv.getPaint().measureText(String.valueOf(tv.getText()));
            Shader mine = new LinearGradient(0, 0, Math.max(1, w), 0, t.title1, t.title2, Shader.TileMode.CLAMP);
            tv.getPaint().setShader(mine);
            ourShader.put(tv, mine);
            tv.invalidate();
        }
        if (!card && t.bg == PageTheme.BG_CLOUDS) tv.setShadowLayer(px(3), 0, px(1), 0x99000000); // (letters over the clouds)
    }

    /** The fill colour of a GradientDrawable (a gradient: its first colour); 0 when it has none. */
    static int fillOf(GradientDrawable gd) {
        try {
            int[] cols = gd.getColors();
            if (cols != null && cols.length > 0) return cols[0];
            android.content.res.ColorStateList cl = gd.getColor();
            return cl == null ? 0 : cl.getDefaultColor();
        } catch (Throwable e) { return 0; }
    }

    static float cornerOf(GradientDrawable gd) {
        try { return gd.getCornerRadius(); } catch (Throwable e) { return 0; }
    }

    // ================================================================ the theme chooser's small pictures

    /** A small picture of theme t: its background with a little card, a line of letters and a switch. */
    static Drawable preview(Context c, PageTheme t) {
        return new Drawable() {
            private Bitmap pic;
            private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);

            @Override protected void onBoundsChange(Rect b) {
                int w = Math.max(1, b.width()), h = Math.max(1, b.height());
                try {
                    pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(pic);
                    Path clip = new Path();
                    float rr = w * .12f;
                    clip.addRoundRect(new RectF(0, 0, w, h), rr, rr, Path.Direction.CW);
                    cv.clipPath(clip);
                    if (t.bg == PageTheme.BG_AURORA) {
                        Paint g = new Paint();
                        g.setShader(new LinearGradient(0, 0, 0, h, t.bgTop, t.bgBottom, Shader.TileMode.CLAMP));
                        cv.drawRect(0, 0, w, h, g);
                        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                        ring.setStyle(Paint.Style.STROKE);
                        ring.setStrokeWidth(Math.max(1, w / 60f));
                        ring.setColor(PageTheme.withAlpha(t.accent, 0x66));
                        cv.drawCircle(w * .5f, h * .36f, w * .3f, ring);
                        ring.setColor(PageTheme.withAlpha(t.accent2, 0x88));
                        cv.drawArc(new RectF(w * .12f, h * .36f - w * .38f, w * .88f, h * .36f + w * .38f), -30, 80, false, ring);
                    } else paint(cv, w, h, t);
                    PageSkin s = new PageSkin(c, t);
                    Drawable cardD = t.isNew() ? s.card(9) : Ui.glass(c, 9);
                    int cl = Math.round(w * .1f), ct = Math.round(h * .5f), cr = Math.round(w * .9f), cb = Math.round(h * .86f);
                    cardD.setBounds(cl, ct, cr, cb);
                    cardD.draw(cv);
                    Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ln.setColor(t.ink);
                    float lh = (cb - ct) * .16f;
                    cv.drawRoundRect(new RectF(cl + w * .07f, ct + lh * 1.2f, cl + w * .45f, ct + lh * 2.2f), lh / 2, lh / 2, ln);
                    ln.setColor(PageTheme.over(t.muted, t.cardOn(t.pageBase())));
                    cv.drawRoundRect(new RectF(cl + w * .07f, ct + lh * 3f, cl + w * .35f, ct + lh * 3.8f), lh / 2, lh / 2, ln);
                    ln.setColor(t.swOn | 0xFF000000);
                    float sw = w * .2f, sh = (cb - ct) * .26f, sx = cr - w * .07f - sw, sy = ct + (cb - ct) * .3f;
                    cv.drawRoundRect(new RectF(sx, sy, sx + sw, sy + sh), sh / 2, sh / 2, ln);
                    ln.setColor(t.thumb | 0xFF000000);
                    cv.drawCircle(sx + sw - sh / 2, sy + sh / 2, sh * .38f, ln);
                    ln.setColor(t.title1 | 0xFF000000);
                    cv.drawRoundRect(new RectF(w * .1f, h * .14f, w * .62f, h * .24f), h * .05f, h * .05f, ln);
                } catch (Throwable e) { pic = null; }
            }

            @Override public void draw(Canvas cv) {
                if (pic != null) cv.drawBitmap(pic, null, getBounds(), p);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
