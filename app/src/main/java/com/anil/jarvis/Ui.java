package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Palette, sizes and small view helpers shared by every screen. */
final class Ui {
    static final int INK = 0xFF050D14;
    static final int DEEP = 0xFF081620;
    static final int PANEL = 0xFF0C1D29;
    static final int PANEL2 = 0xFF10263A;
    static final int LINE = 0xFF16303F;
    static final int LINE2 = 0xFF23495E;
    static int CYAN = 0xFF74E4FF; // follows the theme
    static final int CYAN2 = 0xFF3BB7D6;
    static final int CYAN_DIM = 0xFF1E6A80;
    static final int GOLD = 0xFFF2B24C;
    static final int GOLD_INK = 0xFF221502;
    static final int TEXT = 0xFFDCEEF5;
    static final int MUTED = 0xFF88A5B3;
    static final int FAINT = 0xFF557584;
    static final int RED = 0xFFFF6B5E;
    static final int OK = 0xFF6BE3A4;

    // ---- the colourful "aurora" theme of the main screen (BG_*, C_CYAN, C_VIOLET follow the chosen theme)
    static int BG_TOP = 0xFF061428, BG_BOTTOM = 0xFF02050C;
    static final int GLASS = 0x14FFFFFF, GLASS2 = 0x1FFFFFFF, GLASS_EDGE = 0x2BFFFFFF;
    static int C_CYAN = 0xFF22D3EE, C_VIOLET = 0xFFF2B24C;
    static final int C_BLUE = 0xFF3B82F6, C_PINK = 0xFFEC4899,
            C_AMBER = 0xFFF59E0B, C_GREEN = 0xFF10B981, C_ORANGE = 0xFFF97316, C_TEAL = 0xFF14B8A6, C_SKY = 0xFF38BDF8;

    // ---- themes: {id, name, what it looks like}
    static final String[][] THEMES = {
            {"mix", "నీలం + బంగారం", "నీలం వలయాలు, బంగారు గుర్తులు"},
            {"blue", "ఐస్ బ్లూ", "చల్లని నీలం వెలుగు"},
            {"gold", "బంగారు అంబర్", "వెచ్చని బంగారు వెలుగు"}};
    /** The HUD's ring and mark colours. */
    static int RING = 0xFF38BDF8, MARK = 0xFFF2B24C;
    /** Rings and the scan line move (off in battery saver, or when he turned it off). */
    static boolean animate = true;
    /** Goes up each time the theme changes: an open screen built with the old colours rebuilds itself. */
    static int themeVersion;
    private static String loaded = "";

    static String theme(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("theme", "mix"); }

    static boolean hudMotion(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("hud_motion", true); }

    static void setTheme(Context c, String id, boolean motion) {
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("theme", id).putBoolean("hud_motion", motion).apply();
        loadTheme(c);
    }

    /** The chosen theme's colours, set before a screen is built. */
    static void loadTheme(Context c) {
        String t = theme(c);
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        animate = hudMotion(c) && (pm == null || !pm.isPowerSaveMode());
        if (t.equals(loaded)) return;
        switch (t) {
            case "blue":
                BG_TOP = 0xFF0A1030; BG_BOTTOM = 0xFF04060F; C_CYAN = 0xFF22D3EE; C_VIOLET = 0xFF8B5CF6; CYAN = 0xFF74E4FF;
                RING = 0xFF22D3EE; MARK = 0xFF74E4FF;
                break;
            case "gold":
                BG_TOP = 0xFF1A1206; BG_BOTTOM = 0xFF060402; C_CYAN = 0xFFFBBF24; C_VIOLET = 0xFFF97316; CYAN = 0xFFFFD27A;
                RING = 0xFFF2B24C; MARK = 0xFFFDE68A;
                break;
            default: // mix
                BG_TOP = 0xFF061428; BG_BOTTOM = 0xFF02050C; C_CYAN = 0xFF22D3EE; C_VIOLET = 0xFFF2B24C; CYAN = 0xFF74E4FF;
                RING = 0xFF38BDF8; MARK = 0xFFF2B24C;
        }
        if (!loaded.isEmpty()) themeVersion++;
        loaded = t;
    }

    private Ui() {}

    /** The colour with a new alpha (0-255). */
    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    /** A rounded gradient (left to right unless told otherwise). */
    static GradientDrawable grad(Context c, int[] colors, float radiusDp, GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o == null ? GradientDrawable.Orientation.LEFT_RIGHT : o, colors);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** Rounded corners one by one (top-left, top-right, bottom-right, bottom-left), for chat bubbles. */
    static GradientDrawable corners(Context c, GradientDrawable g, float tl, float tr, float br, float bl) {
        float a = dp(c, tl), b = dp(c, tr), d = dp(c, br), e = dp(c, bl);
        g.setCornerRadii(new float[]{a, a, b, b, d, d, e, e});
        return g;
    }

    /** Frosted-glass card: faint white fill, hairline white edge. */
    static GradientDrawable glass(Context c, float radiusDp) { return round(c, GLASS, GLASS_EDGE, radiusDp); }

    /** Touch feedback for rows and cards. */
    static Drawable ripple(Context c, float radiusDp) {
        GradientDrawable mask = round(c, 0xFFFFFFFF, 0, radiusDp);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    /** Text painted with a left-to-right colour gradient. */
    static void gradientText(TextView t, int from, int to) {
        float w = t.getPaint().measureText(String.valueOf(t.getText()));
        t.getPaint().setShader(new android.graphics.LinearGradient(0, 0, Math.max(1, w), 0, from, to, android.graphics.Shader.TileMode.CLAMP));
    }

    /**
     * The hologram HUD background in the theme's colours: a deep gradient with two soft glows, a faint grid, slowly
     * turning rings with tick marks, a scan line passing down now and then, and corner brackets. Shaders are made once
     * per size and nothing is allocated while drawing; it moves (15 frames a second) only while it is on screen.
     */
    static final class Aurora extends Drawable {
        private final Paint base = new Paint(), g1 = new Paint(Paint.ANTI_ALIAS_FLAG), g2 = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(), ring = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG),
                scan = new Paint(), corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF oval = new android.graphics.RectF();
        private float w, h, step, cx, cy, r1, r2, r3, len;
        private final long born = android.os.SystemClock.uptimeMillis();
        // the theme's colours when this screen was made (a page open during a theme change keeps one look)
        private final int top = BG_TOP, bottom = BG_BOTTOM, glow1 = C_CYAN, glow2 = C_VIOLET, ringC = RING, markC = MARK;
        private boolean ticking;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!ticking || !animate) { ticking = false; return; }
                invalidateSelf();
                scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 66);
            }
        };

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            w = b.width();
            h = b.height();
            if (w <= 0 || h <= 0) return;
            float d = Math.min(w, h);
            base.setShader(new android.graphics.LinearGradient(0, 0, 0, h, top, bottom, android.graphics.Shader.TileMode.CLAMP));
            g1.setShader(new android.graphics.RadialGradient(w * 0.08f, h * 0.02f, w * 0.85f, alpha(glow1, 60), 0, android.graphics.Shader.TileMode.CLAMP));
            g2.setShader(new android.graphics.RadialGradient(w * 0.98f, h * 0.10f, w * 0.80f, alpha(glow2, 52), 0, android.graphics.Shader.TileMode.CLAMP));
            step = d / 11f;
            grid.setColor(alpha(ringC, 12));
            grid.setStrokeWidth(1);
            cx = w * 0.5f;
            cy = h * 0.36f;
            r1 = d * 0.30f; r2 = d * 0.39f; r3 = d * 0.46f;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(alpha(ringC, 34));
            ring.setStrokeWidth(Math.max(1.5f, d / 400f));
            mark.setStyle(Paint.Style.STROKE);
            mark.setColor(alpha(markC, 46));
            mark.setStrokeWidth(Math.max(2f, d / 260f));
            scan.setShader(new android.graphics.LinearGradient(0, 0, 0, d * 0.05f, 0, alpha(ringC, 30), android.graphics.Shader.TileMode.CLAMP));
            corner.setStyle(Paint.Style.STROKE);
            corner.setColor(alpha(markC, 70));
            corner.setStrokeWidth(Math.max(2f, d / 300f));
            len = d * 0.06f;
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRect(b, base);
            c.drawRect(b, g1);
            c.drawRect(b, g2);
            if (w <= 0 || h <= 0) return;
            for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, grid);
            for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, grid);
            float t = (android.os.SystemClock.uptimeMillis() - born) / 1000f;
            float a = animate ? t * 6f : 0, a2 = animate ? -t * 4f : 0; // degrees: slow, the middle ring the other way
            oval.set(cx - r1, cy - r1, cx + r1, cy + r1);
            for (int i = 0; i < 4; i++) c.drawArc(oval, a + i * 90, 62, false, ring);
            oval.set(cx - r2, cy - r2, cx + r2, cy + r2);
            for (int i = 0; i < 3; i++) c.drawArc(oval, a2 + i * 120 + 20, 84, false, ring);
            oval.set(cx - r3, cy - r3, cx + r3, cy + r3);
            c.drawArc(oval, 0, 360, false, ring);
            for (int i = 0; i < 72; i++) { // tick marks round the outer ring, every fifth longer
                double rad = Math.toRadians(i * 5 + a * 0.5);
                float in = r3 - (i % 6 == 0 ? len * 0.55f : len * 0.25f);
                float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                c.drawLine(cx + cos * in, cy + sin * in, cx + cos * r3, cy + sin * r3, mark);
            }
            if (animate) { // a scan line down the screen every 7 seconds
                float y = (t % 7f) / 7f * (h + step) - step;
                c.save();
                c.translate(0, y);
                c.drawRect(0, 0, w, Math.min(w, h) * 0.05f, scan);
                c.restore();
            }
            float m = len * 0.5f;
            c.drawLine(m, m, m + len, m, corner); c.drawLine(m, m, m, m + len, corner);
            c.drawLine(w - m, m, w - m - len, m, corner); c.drawLine(w - m, m, w - m, m + len, corner);
            c.drawLine(m, h - m, m + len, h - m, corner); c.drawLine(m, h - m, m, h - m - len, corner);
            c.drawLine(w - m, h - m, w - m - len, h - m, corner); c.drawLine(w - m, h - m, w - m, h - m - len, corner);
            if (animate && !ticking && isVisible()) { ticking = true; scheduleSelf(tick, android.os.SystemClock.uptimeMillis() + 66); }
        }

        @Override public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (!visible) { ticking = false; unscheduleSelf(tick); }
            else if (animate && !ticking) invalidateSelf(); // draw() starts the frames again
            return changed;
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView mono(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.14f);
        return t;
    }

    static TextView pill(Context c, String s) {
        TextView t = text(c, s, 14.5f, TEXT);
        t.setBackground(round(c, DEEP, LINE2, 999));
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }

    /** Two HUD corner brackets (top-left and bottom-right) around Jarvis's messages. */
    static final class Brackets extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float len;

        Brackets(Context c) {
            p.setColor(CYAN_DIM);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 1.5f));
            len = dp(c, 12);
        }

        @Override public void draw(Canvas canvas) {
            float l = getBounds().left + 1, t = getBounds().top + 1, r = getBounds().right - 1, b = getBounds().bottom - 1;
            canvas.drawLine(l, t, l + len, t, p);
            canvas.drawLine(l, t, l, t + len, p);
            canvas.drawLine(r, b, r - len, b, p);
            canvas.drawLine(r, b, r, b - len, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
