package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A new message while he is in another app: a small card at the top of the screen (like WhatsApp's own banner) instead
 * of Jarvis's panel from the bottom. It never takes the keyboard or dims the screen; what he is doing goes on.
 * 🔊 చదువు reads it, 📝 సారాంశం sums up a busy chat, ⏰ తర్వాత puts it off (a dot on the floating button). Swiped up,
 * or left alone for a few seconds, it goes, and the messages wait under the dot: nothing is lost.
 */
final class TopCard {
    /** One chat's new messages. */
    static final class Msg {
        String app = "", from = "", pkg = "", say = "", ask = "", context = "";
        final List<String> texts = new ArrayList<>();
        boolean group, typing;
        int id;
    }

    private static TopCard showing;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final Msg m;
    private LinearLayout view, buttons;
    private TextView line2;
    private boolean touched, done, heard;
    private float downY;
    private final Runnable autoHide = () -> close(true);

    private TopCard(AccessibilityService svc, Msg m) {
        this.svc = svc;
        this.m = m;
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        d = svc.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return Math.round(v * d); }

    /** Main thread. A card already up for another chat is put off first (its messages under the dot). False: no card could be shown. */
    static boolean show(AccessibilityService svc, Msg m) {
        if (showing != null) showing.close(true);
        TopCard t = new TopCard(svc, m);
        if (!t.build()) return false;
        showing = t;
        return true;
    }

    /** Main thread. Gone at once (a screenshot is about to be taken, or the screen went off); unheard messages wait under the dot. */
    static void dismissNow() {
        TopCard t = showing;
        if (t != null) t.close(true, false);
        TopCard c = leaving; // one still sliding away
        if (c != null) c.removeView();
    }

    /** A card closed but not yet off the screen (its slide-out). */
    private static TopCard leaving;

    /** The screen went off: the card must not stay over the lock screen. */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { close(true, false); }
    };
    private boolean listeningScreen, removed;

    private boolean build() {
        view = new LinearLayout(svc);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5061424);
        bg.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(Ui.CYAN, 0x99));
        bg.setCornerRadius(dp(18));
        view.setBackground(bg);
        view.setPadding(dp(14), dp(10), dp(8), dp(6));
        TextView line1 = new TextView(svc);
        int n = m.texts.size();
        line1.setText("💬 " + m.from + (m.group ? " గ్రూప్" : "") + " · " + m.app + (n > 1 ? " · " + n + " మెసేజ్‌లు" : ""));
        line1.setTextColor(Ui.CYAN);
        line1.setTextSize(14f);
        line1.setTypeface(Typeface.DEFAULT_BOLD);
        line1.setSingleLine(true);
        line1.setEllipsize(TextUtils.TruncateAt.END);
        view.addView(line1);
        line2 = new TextView(svc);
        line2.setText(n == 0 ? "" : m.texts.get(n - 1));
        line2.setTextColor(0xFFFFFFFF);
        line2.setTextSize(14.5f);
        line2.setMaxLines(2);
        line2.setEllipsize(TextUtils.TruncateAt.END);
        line2.setPadding(0, dp(3), 0, dp(2));
        view.addView(line2);
        buttons = new LinearLayout(svc);
        buttons.setGravity(Gravity.END);
        buttons.addView(button("🔊 చదువు", v -> read()));
        if (m.group || n >= 3) buttons.addView(button("📝 సారాంశం", v -> summary()));
        buttons.addView(button("⏰ తర్వాత", v -> close(true)));
        view.addView(buttons);
        view.setOnTouchListener(this::swipe);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(svc.getResources().getDisplayMetrics().widthPixels - dp(20),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(30);
        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            return false;
        }
        view.setTranslationY(-dp(120));
        view.animate().translationY(0).setDuration(220).start();
        main.postDelayed(autoHide, 6500);
        try {
            svc.registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
            listeningScreen = true;
        } catch (Exception ignored) {}
        return true;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFD7F6FF);
        t.setTextSize(13.5f);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setOnClickListener(v -> { touched = true; main.removeCallbacks(autoHide); l.onClick(v); });
        return t;
    }

    /** Swipe it up: gone (the messages wait under the dot). */
    private boolean swipe(View v, MotionEvent e) {
        if (done) return true; // going away: a touch must not stop that
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                main.removeCallbacks(autoHide);
                return true;
            case MotionEvent.ACTION_MOVE:
                view.setTranslationY(Math.min(0, e.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getRawY() - downY < -dp(30)) { close(true); return true; }
                view.animate().translationY(0).setDuration(120).start();
                if (!touched) main.postDelayed(autoHide, 4000);
                return true;
            default:
                return false;
        }
    }

    private void read() {
        StringBuilder b = new StringBuilder(m.from).append(" నుంచి").append(m.group ? " గ్రూప్‌లో: " : ": ");
        for (String t : m.texts) b.append(t).append(". ");
        line2.setMaxLines(6);
        StringBuilder shown = new StringBuilder();
        for (String t : m.texts) shown.append(shown.length() == 0 ? "" : "\n").append(t);
        line2.setText(shown);
        Announcer.stop();
        Announcer.say(svc, b.toString());
        afterRead();
    }

    /** Read (or summed up): ↩️ జవాబు opens Jarvis's panel for his reply (sent only after he says send); ✕ closes. */
    private void afterRead() {
        heard = true; // heard now: closing no longer puts it off for later
        buttons.removeAllViews();
        if (m.id > 0) buttons.addView(button("↩️ జవాబు", v -> reply()));
        buttons.addView(button("✕", v -> close(false)));
        main.postDelayed(autoHide, 25_000);
    }

    private void summary() {
        Prefs p = new Prefs(svc);
        if (!p.hasBrain()) { line2.setText("సారాంశానికి AI key కావాలి (Jarvis సెట్టింగ్స్)."); return; }
        line2.setText("సారాంశం చేస్తున్నాను…");
        buttons.setVisibility(View.GONE);
        StringBuilder all = new StringBuilder();
        for (String t : m.texts) all.append(t).append('\n');
        final String names = p.myNames();
        new Thread(() -> {
            String out;
            try {
                out = Brain.oneShot(p, "You are Jarvis, " + p.name() + "'s assistant. Sum up these new chat messages from '" + m.from + "' ("
                        + m.app + ") in simple Telugu (Telugu script), 2-4 short lines, plain text for reading aloud. First anything addressed to him "
                        + "(his names: " + names + "), any question to him, dates, times or money; then the rest in brief. No markdown.",
                        all.toString(), null, false, 500);
            } catch (Exception e) {
                out = null;
            }
            final String o = out;
            main.post(() -> {
                if (done) return;
                buttons.setVisibility(View.VISIBLE);
                if (o == null || o.trim().isEmpty()) { line2.setText("సారాంశం రాలేదు. 🔊 చదువు నొక్కండి."); return; }
                String clean = o.replaceAll("[*#_`>]", "").trim();
                line2.setMaxLines(8);
                line2.setText(clean);
                Announcer.stop();
                Announcer.say(svc, clean);
                afterRead();
            });
        }, "jarvis-topcard-sum").start();
    }

    private void reply() {
        close(false);
        try {
            svc.startActivity(new Intent(svc, SheetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE, m.from + " కి జవాబు")
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, "ఏం చెప్పమంటారు?")
                    .putExtra(SheetActivity.EXTRA_IS_MESSAGE, true)
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, " [He already heard these messages and wants to reply now. Take his reply, read it back, "
                            + "ask 'పంపమంటారా?', and send with reply_to_notification (id " + m.id + ") only after he says send.]" + m.context));
        } catch (Exception ignored) {}
    }

    private void close(boolean later) { close(later, true); }

    /** later: not read yet, so it waits under the floating button's dot. animate: slides up (else gone at once). */
    private void close(boolean later, boolean animate) {
        if (done) { if (!animate) removeView(); return; }
        done = true;
        main.removeCallbacks(autoHide);
        if (showing == this) showing = null;
        if (listeningScreen) { listeningScreen = false; try { svc.unregisterReceiver(screenOff); } catch (Exception ignored) {} }
        if (later && !heard && !m.texts.isEmpty()) LaterMessages.add(svc, m.app, m.from, m.texts, m.id);
        if (!animate) { removeView(); return; }
        leaving = this;
        view.animate().translationY(-dp(140)).setDuration(180).withEndAction(this::removeView).start();
        main.postDelayed(this::removeView, 400); // even if the animation never ends
    }

    private void removeView() {
        if (leaving == this) leaving = null;
        if (removed) return;
        removed = true;
        try { view.animate().cancel(); } catch (Exception ignored) {}
        try { wm.removeView(view); } catch (Exception ignored) {}
    }
}
