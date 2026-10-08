package com.anil.jarvis.watch;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Small look helpers for the watch screens (Jarvis's colours, the Telugu font when the watch lacks it). */
final class WUi {
    private WUi() {}

    static final int CYAN = 0xFF74E4FF, TEXT = 0xFFDCEEF5, MUTED = 0xFF8FA9B5, FAINT = 0xFF5B7380;
    private static Typeface te;
    private static boolean teChecked;

    static Typeface telugu(Context c) {
        if (teChecked) return te;
        teChecked = true;
        try { if (new Paint().hasGlyph("తె")) return te = null; } catch (Exception ignored) {}
        try { te = Typeface.createFromAsset(c.getAssets(), "NotoSansTelugu-Regular.ttf"); } catch (Exception e) { te = null; }
        return te;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, float sp, int color, boolean center) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        Typeface f = telugu(c);
        if (f != null) t.setTypeface(f);
        if (center) t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(c, 3), 0, dp(c, 3));
        return t;
    }

    static TextView pill(Context c, String label, int color) {
        TextView t = text(c, label, 13.5f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 22));
        g.setColor(color);
        g.setStroke(dp(c, 1), (Theme.accent & 0x00FFFFFF) | 0x66000000);
        t.setBackground(g);
        return t;
    }
}
