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
    static final int CYAN = 0xFF74E4FF;
    static final int CYAN2 = 0xFF3BB7D6;
    static final int CYAN_DIM = 0xFF1E6A80;
    static final int GOLD = 0xFFF2B24C;
    static final int GOLD_INK = 0xFF221502;
    static final int TEXT = 0xFFDCEEF5;
    static final int MUTED = 0xFF88A5B3;
    static final int FAINT = 0xFF557584;
    static final int RED = 0xFFFF6B5E;
    static final int OK = 0xFF6BE3A4;

    // ---- the colourful "aurora" theme of the main screen
    static final int BG_TOP = 0xFF0A1030, BG_BOTTOM = 0xFF04060F;
    static final int GLASS = 0x14FFFFFF, GLASS2 = 0x1FFFFFFF, GLASS_EDGE = 0x2BFFFFFF;
    static final int C_CYAN = 0xFF22D3EE, C_BLUE = 0xFF3B82F6, C_VIOLET = 0xFF8B5CF6, C_PINK = 0xFFEC4899,
            C_AMBER = 0xFFF59E0B, C_GREEN = 0xFF10B981, C_ORANGE = 0xFFF97316, C_TEAL = 0xFF14B8A6, C_SKY = 0xFF38BDF8;

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
     * Deep night-blue background with soft glows of colour (cyan, violet, pink) like an aurora.
     * Shaders are made once per size, nothing is allocated while drawing.
     */
    static final class Aurora extends Drawable {
        private final Paint base = new Paint(), g1 = new Paint(Paint.ANTI_ALIAS_FLAG), g2 = new Paint(Paint.ANTI_ALIAS_FLAG), g3 = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float w, h;

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            w = b.width();
            h = b.height();
            if (w <= 0 || h <= 0) return;
            base.setShader(new android.graphics.LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, android.graphics.Shader.TileMode.CLAMP));
            g1.setShader(new android.graphics.RadialGradient(w * 0.08f, h * 0.02f, w * 0.85f, alpha(C_CYAN, 70), 0, android.graphics.Shader.TileMode.CLAMP));
            g2.setShader(new android.graphics.RadialGradient(w * 0.98f, h * 0.10f, w * 0.80f, alpha(C_VIOLET, 80), 0, android.graphics.Shader.TileMode.CLAMP));
            g3.setShader(new android.graphics.RadialGradient(w * 0.85f, h * 0.62f, w * 0.75f, alpha(C_PINK, 26), 0, android.graphics.Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRect(b, base);
            c.drawRect(b, g1);
            c.drawRect(b, g2);
            c.drawRect(b, g3);
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
