package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * D (his charging idea, 6 Nov): when the charger goes in or out on the phone, a picture of it for a few seconds —
 * Jarvis open: a big liquid card in the middle that shrinks away up to the 🔋 card (~4 s); another app open: a small
 * strip at the top over it (needs "Jarvis స్క్రీన్"; never over the lock screen). The liquid is only for the charging
 * status (never a switch). Not during a call; "⚡ చార్జింగ్ బొమ్మ" off in Settings: none.
 */
final class ChargeShow {
    private ChargeShow() {}

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View strip;
    private static WindowManager stripWm;
    private static final Runnable hide = ChargeShow::hideStrip;

    /** Main thread or not: shows the picture for the charger's change. */
    static void show(Context c, boolean on, int pct, int from) {
        main.post(() -> {
            try {
                MainActivity a = MainActivity.front;
                if (a != null && !a.isFinishing()) { card(a, on, pct, from); a.chargeChanged(); return; }
                JarvisAccessibility s = JarvisAccessibility.service();
                if (s != null && TopCard.screenUsable(s)) strip(s, on, pct, from);
            } catch (Throwable ignored) {}
        });
    }

    static String words(boolean on, int pct, int from) {
        if (on) return "చార్జింగ్ ఎక్కుతోంది";
        return from >= 0 && pct > from ? "చార్జర్ తీశారు · " + from + "% → " + pct + "%" : "చార్జర్ తీశారు";
    }

    // ---- in Jarvis: the big card, shrinking away to the top

    private static void card(Activity a, boolean on, int pct, int from) {
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) content;
        View old = host.findViewWithTag("jarvis_charge_card");
        if (old != null) host.removeView(old);
        boolean motion = LookScreen.motionAllowed(a);
        LinearLayout card = new LinearLayout(a);
        card.setTag("jarvis_charge_card");
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(a, 0xEE0B1222, 0x66FFFFFF, 26));
        int p = Ui.dp(a, 20);
        card.setPadding(p, p, p, Ui.dp(a, 16));
        card.setElevation(Ui.dp(a, 12));
        LiquidPill pill = new LiquidPill(a, true);
        pill.setMotion(motion);
        pill.set(pct, on);
        card.addView(pill, new LinearLayout.LayoutParams(Ui.dp(a, 62), Ui.dp(a, 136)));
        TextView big = Ui.text(a, (on ? "⚡ " : "🔌 ") + pct + "%", 34, 0xFFFFFFFF);
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = Ui.dp(a, 10);
        card.addView(big, bl);
        TextView w = Ui.text(a, words(on, pct, from), 15, 0xFFD6E4F0);
        w.setGravity(Gravity.CENTER);
        card.addView(w);
        card.setContentDescription(big.getText() + ", " + w.getText());
        card.setOnClickListener(v -> host.removeView(v));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 230), -2, Gravity.CENTER);
        host.addView(card, lp);
        if (!motion) { // (no motion: there and gone)
            card.postDelayed(() -> host.removeView(card), 3500);
            return;
        }
        card.setAlpha(0f);
        card.setScaleX(0.85f);
        card.setScaleY(0.85f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).withEndAction(() ->
                card.animate().setStartDelay(2600).translationY(-host.getHeight() * 0.38f).translationX(-host.getWidth() * 0.22f)
                        .scaleX(0.22f).scaleY(0.22f).alpha(0f).setDuration(700)
                        .withEndAction(() -> host.removeView(card)).start()).start();
    }

    // ---- over another app: a small strip at the top

    private static void strip(JarvisAccessibility s, boolean on, int pct, int from) {
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        hideStrip();
        LinearLayout row = new LinearLayout(s);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(s, 0xF00B1222, 0x66FFFFFF, 22));
        int p = Ui.dp(s, 14);
        row.setPadding(p, Ui.dp(s, 10), p, Ui.dp(s, 10));
        LiquidPill pill = new LiquidPill(s, false);
        pill.setMotion(LookScreen.motionAllowed(s));
        pill.set(pct, on);
        row.addView(pill, new LinearLayout.LayoutParams(Ui.dp(s, 64), Ui.dp(s, 16)));
        TextView t = Ui.text(s, (on ? "⚡ " : "🔌 ") + pct + "%  ·  " + words(on, pct, from), 14.5f, 0xFFFFFFFF);
        t.setPadding(Ui.dp(s, 10), 0, 0, 0);
        t.setSingleLine(true);
        row.addView(t);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.dp(s, new Prefs(s).cardLow() ? 110 : 30);
        try {
            wm.addView(row, lp);
            strip = row;
            stripWm = wm;
            main.postDelayed(hide, 4000);
        } catch (Exception ignored) {}
    }

    private static void hideStrip() {
        main.removeCallbacks(hide);
        View v = strip;
        WindowManager wm = stripWm;
        strip = null;
        stripWm = null;
        if (v != null && wm != null) try { wm.removeView(v); } catch (Exception ignored) {}
    }
}
