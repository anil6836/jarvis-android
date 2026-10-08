package com.anil.jarvis.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The HUD around the edge of the round screen: a ticked ring and two turning arcs, the time on top and the watch's
 * battery below (they fade as he scrolls down to the text). W4: on opening, "J.A.R.V.I.S. online" with the rings
 * drawing in and a scan line passing over the screen.
 */
final class HudView extends View {
    private static final int CYAN = 0xFF74E4FF, VIOLET = 0xFFA78BFA;
    static final long BOOT_MS = 1700;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), scan = new Paint();
    private final RectF box = new RectF();
    private final float dp;
    private long bootAt = -1;
    private float fade = 1f; // the time / battery: 1 at the top, 0 once scrolled down
    private int battery = -1;
    private long batteryAt;
    private Runnable afterBoot;

    HudView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
    }

    /** The opening animation; then after runs (on the main thread). */
    void boot(Runnable after) {
        bootAt = SystemClock.elapsedRealtime();
        afterBoot = after;
        invalidate();
    }

    /** 0..1 of the boot (1 when not booting): the orb comes in with it. */
    float bootProgress() {
        if (bootAt < 0) return 1f;
        return Math.min(1f, (SystemClock.elapsedRealtime() - bootAt) / (float) BOOT_MS);
    }

    void setFade(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - fade) > 0.01f) { fade = f; invalidate(); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f;
        float t = SystemClock.elapsedRealtime() / 1000f;
        float b = bootProgress();
        boolean booting = b < 1f;
        float sweep = booting ? Math.min(1f, b / 0.55f) : 1f; // the rings draw in
        int s = Talk.state;
        int main = s == Talk.THINKING ? VIOLET : Theme.mark; // (W5: the phone's theme)
        int ring = Theme.main;

        // outer ring and ticks
        line.setColor(withAlpha(ring, 0x55));
        line.setStrokeWidth(dp * 1.2f);
        box.set(cx - R * 0.965f, cy - R * 0.965f, cx + R * 0.965f, cy + R * 0.965f);
        c.drawArc(box, -90f, 360f * sweep, false, line);
        float turn = (t * 3f) % 360f;
        int ticks = Math.round(60 * sweep);
        for (int i = 0; i < ticks; i++) {
            double a = Math.toRadians(i * 6 + turn - 90);
            float inner = R * (i % 5 == 0 ? 0.88f : 0.915f), outer = R * 0.94f;
            line.setStrokeWidth(dp * (i % 5 == 0 ? 1.6f : 0.9f));
            line.setColor(withAlpha(ring, i % 5 == 0 ? 0x70 : 0x38));
            c.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                    cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, line);
        }
        // two arcs turning opposite ways (faster while it works)
        float speed = s == Talk.IDLE ? 12f : 45f;
        line.setStrokeWidth(dp * 2.4f);
        line.setColor(withAlpha(main, 0x99));
        box.set(cx - R * 0.84f, cy - R * 0.84f, cx + R * 0.84f, cy + R * 0.84f);
        c.drawArc(box, (t * speed) % 360f, 38f * sweep, false, line);
        c.drawArc(box, (-t * speed * 0.7f + 200f) % 360f, 64f * sweep, false, line);

        // time on top, battery below
        if (!booting && fade > 0f) {
            Calendar now = Calendar.getInstance();
            txt.setColor(withAlpha(Theme.accent, Math.round(0xDD * fade)));
            txt.setTextSize(dp * 15f);
            c.drawText(String.format(Locale.ROOT, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)), cx, cy - R * 0.66f, txt);
            int bat = battery();
            if (bat >= 0) {
                txt.setColor(withAlpha(0xFFDCEEF5, Math.round(0x99 * fade)));
                txt.setTextSize(dp * 10.5f);
                c.drawText("⚡ " + bat + "%", cx, cy + R * 0.80f, txt);
            }
        }

        // W4: the boot
        if (booting) {
            float y = h * Math.min(1f, b / 0.8f);
            scan.setShader(new LinearGradient(0, y - dp * 26, 0, y, 0x0074E4FF, withAlpha(CYAN, 0x66), Shader.TileMode.CLAMP));
            c.drawRect(0, y - dp * 26, w, y, scan);
            line.setColor(withAlpha(CYAN, 0xCC));
            line.setStrokeWidth(dp * 1.2f);
            c.drawLine(cx - R, y, cx + R, y, line);
            String name = "J.A.R.V.I.S.";
            int shownChars = (int) Math.min(name.length(), Math.max(0, (b - 0.12f) / 0.45f * name.length()));
            float textFade = b > 0.85f ? (1f - b) / 0.15f : 1f;
            txt.setColor(withAlpha(0xFFFFFFFF, Math.round(255 * textFade)));
            txt.setTextSize(dp * 17f);
            txt.setFakeBoldText(true);
            c.drawText(name.substring(0, shownChars), cx, cy - dp * 4, txt);
            txt.setFakeBoldText(false);
            if (b > 0.55f) {
                txt.setColor(withAlpha(CYAN, Math.round(255 * Math.min(1f, (b - 0.55f) / 0.15f) * textFade)));
                txt.setTextSize(dp * 11f);
                c.drawText("ONLINE", cx, cy + dp * 16, txt);
            }
            postInvalidateOnAnimation();
        } else {
            if (afterBoot != null) { Runnable r = afterBoot; afterBoot = null; post(r); }
            postInvalidateDelayed(s == Talk.IDLE ? 80 : 33);
        }
    }

    private int battery() {
        long now = SystemClock.elapsedRealtime();
        if (battery < 0 || now - batteryAt > 60_000) {
            batteryAt = now;
            BatteryManager bm = getContext().getSystemService(BatteryManager.class);
            battery = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        return battery;
    }

    private static int withAlpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00FFFFFF);
    }
}
