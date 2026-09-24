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

    private Ui() {}

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
