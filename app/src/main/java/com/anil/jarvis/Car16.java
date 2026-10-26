package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ⑯ (stage 3, 27 Oct): the 3D chase screen's own parts over the 3D view — the big speed arc round his car with the
 * speed above it (red over the limit, the limit's sign at its end), and the yellow turn banner (our own way's turn, or
 * the Google Maps app's turn in its own card — never drawn on the OSM view) with what is left and ✖.
 */
final class Car16 {
    private Car16() {}

    /** The speed arc: over the car, from its left round the top to its right; the number big above the car. */
    static final class SpeedArc extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG),
                num = new Paint(Paint.ANTI_ALIAS_FLAG), unit = new Paint(Paint.ANTI_ALIAS_FLAG), sign = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private int kmh = -1, limit, accent = 0xFF5FF3FF;
        private float shown;
        private boolean low;

        SpeedArc(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
            bar.setStyle(Paint.Style.STROKE);
            bar.setStrokeCap(Paint.Cap.ROUND);
            tick.setStyle(Paint.Style.STROKE);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            num.setColor(Color.WHITE);
            num.setShadowLayer(8, 0, 2, 0xCC000000);
            unit.setTextAlign(Paint.Align.CENTER);
            unit.setColor(0xFFDDE8F2);
            unit.setShadowLayer(6, 0, 1, 0xCC000000);
            sign.setTextAlign(Paint.Align.CENTER);
            sign.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        void setFont(Typeface t) { if (t != null) num.setTypeface(t); invalidate(); }

        void setAccent(int a) { if (a != accent) { accent = a; invalidate(); } }

        void setLow(boolean on) { low = on; }

        void set(int kmh, int limit) {
            if (kmh == this.kmh && limit == this.limit) return;
            this.kmh = kmh;
            this.limit = limit;
            invalidate();
        }

        /** The arc's place in a box w × h: {centre x, centre y, radius}. Pure. */
        static float[] place(int w, int h) {
            float r = Math.min(w * 0.46f, h * 0.6f);
            return new float[]{w / 2f, h * 0.74f, r};
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w < 20 || h < 20) return;
            float[] p = place(w, h);
            float cx = p[0], cy = p[1], r = p[2], sw = Math.max(4, r * 0.06f);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            boolean over = limit > 0 && kmh > limit + 5;
            float target = kmh < 0 ? 0 : Math.min(1f, kmh / 160f);
            shown += (target - shown) * (low ? 1f : 0.35f);
            if (Math.abs(target - shown) > 0.003f && !low) postInvalidateOnAnimation();
            track.setStrokeWidth(sw);
            track.setColor(0x40FFFFFF);
            c.drawArc(oval, 200, 140, false, track);
            bar.setStrokeWidth(sw);
            int a = over ? 0xFFFF4A3D : accent;
            bar.setShader(new LinearGradient(cx - r, 0, cx + r, 0, new int[]{withAlpha(a, 0x55), a, over ? 0xFFFFB02E : 0xFF7DFFC8}, null, Shader.TileMode.CLAMP));
            bar.setShadowLayer(sw, 0, 0, withAlpha(a, 0x99));
            if (shown > 0.004f) c.drawArc(oval, 200, 140 * shown, false, bar);
            // ticks every 20 km/h just outside
            tick.setStrokeWidth(Math.max(1.5f, sw * 0.18f));
            tick.setColor(0x99FFFFFF);
            for (int k = 0; k <= 8; k++) {
                double ang = Math.toRadians(200 + 140 * k / 8.0);
                float r0 = r + sw * 0.9f, r1 = r + sw * (k % 2 == 0 ? 2.0f : 1.5f);
                c.drawLine(cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0, cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, tick);
            }
            // the speed above the car
            num.setTextSize(r * 0.3f);
            num.setColor(over ? 0xFFFF6B5E : Color.WHITE);
            float ny = cy - r * 0.5f;
            c.drawText(kmh < 0 ? "--" : String.valueOf(kmh), cx, ny, num);
            unit.setTextSize(r * 0.085f);
            c.drawText(kmh < 0 ? "GPS…" : "KM/H", cx, ny + r * 0.14f, unit);
            // the limit's sign at the arc's right end
            if (limit > 0) {
                double ang = Math.toRadians(340);
                float sx = cx + (float) Math.cos(ang) * (r + sw * 3.6f), sy = cy + (float) Math.sin(ang) * (r + sw * 3.6f), sr = Math.max(14, r * 0.11f);
                sign.setStyle(Paint.Style.FILL);
                sign.setColor(Color.WHITE);
                c.drawCircle(sx, sy, sr, sign);
                sign.setStyle(Paint.Style.STROKE);
                sign.setStrokeWidth(sr * 0.2f);
                sign.setColor(0xFFE53935);
                c.drawCircle(sx, sy, sr * 0.9f, sign);
                sign.setStyle(Paint.Style.FILL);
                sign.setColor(0xFF111111);
                sign.setTextSize(sr * 0.9f);
                c.drawText(String.valueOf(limit), sx, sy + sr * 0.32f, sign);
            }
        }

        static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }
    }

    /**
     * The yellow turn banner: the turn's sign, its words and how far, and a second line (what is left, the trip's
     * time); ✖ ends our own way (his tap; Google's trip is ended in Google Maps).
     */
    static final class Banner extends LinearLayout {
        final CarArt.Icon icon;
        final TextView words, dist, left, stop;

        Banner(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFD23A, 0xFFF5B400});
            g.setCornerRadius(Ui.dp(c, 16));
            g.setStroke(Math.max(2, Ui.dp(c, 2)), 0xFFFFE89A);
            setBackground(g);
            setElevation(Ui.dp(c, 6));
            int pad = Ui.dp(c, 10);
            setPadding(pad, pad / 2, pad, pad / 2);
            icon = new CarArt.Icon(c, CarArt.Icon.AHEAD);
            icon.setColour(0xFF1A1405);
            addView(icon, new LayoutParams(0, -1, 18));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            words = line(c, true, 0xFF1A1405);
            dist = line(c, true, 0xFF3A2C05);
            left = line(c, false, 0xFF3A2C05);
            col.addView(words);
            LinearLayout r2 = new LinearLayout(c);
            r2.setOrientation(HORIZONTAL);
            r2.addView(dist, new LayoutParams(-2, -2));
            r2.addView(left, new LayoutParams(0, -2, 1));
            col.addView(r2);
            addView(col, new LayoutParams(0, -1, 68));
            stop = line(c, true, 0xFF1A1405);
            stop.setText("✖");
            stop.setGravity(Gravity.CENTER);
            GradientDrawable s = new GradientDrawable();
            s.setShape(GradientDrawable.OVAL);
            s.setColor(0x33000000);
            stop.setBackground(s);
            addView(stop, new LayoutParams(0, -1, 14));
        }

        private static TextView line(Context c, boolean bold, int col) {
            TextView t = new TextView(c);
            t.setTextColor(col);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setIncludeFontPadding(false);
            return t;
        }

        /** The banner's words big enough for its height (h px). */
        void sizes(int h) {
            words.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(14, h * 0.3f));
            dist.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12, h * 0.22f));
            left.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11, h * 0.19f));
            stop.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12, h * 0.26f));
            left.setPadding(Math.round(h * 0.2f), 0, 0, 0);
        }

        void set(String w, String d, String l, boolean canStop) {
            if (!w.contentEquals(words.getText())) words.setText(w);
            if (!d.contentEquals(dist.getText())) dist.setText(d);
            if (!l.contentEquals(left.getText())) left.setText(l);
            icon.setKind(CarRoad.turnIcon(w));
            stop.setVisibility(canStop ? VISIBLE : INVISIBLE);
        }
    }
}
