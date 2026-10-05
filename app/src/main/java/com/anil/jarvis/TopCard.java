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
 * With talk on, Jarvis tells it on the card the way the panel used to (CardTalk: "చదవమంటారా?", reads it, takes his
 * reply and sends it only after he says so), in the background. Without (typing, by his setting): 🔊 చదువు starts that
 * talk, 📝 సారాంశం sums up a busy chat, ⏰ తర్వాత puts it off (a dot on the floating button). Swiped up, or left alone,
 * it goes, and unheard messages wait under the dot: nothing is lost.
 */
final class TopCard {
    /** One chat's new messages. */
    static final class Msg {
        String app = "", from = "", pkg = "", say = "", ask = "", context = "";
        /** A WhatsApp voice note / audio / photo / video (one message), else null. */
        String media;
        /** When the message came (the media file must not be older). */
        long postedAt;
        final List<String> texts = new ArrayList<>();
        /** talk: Jarvis tells it by voice on the card (else the card only waits for a tap). */
        boolean group, typing, talk;
        int id;
    }

    private static TopCard showing;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final Msg m;
    private LinearLayout view, buttons;
    private TextView line2, status;
    private boolean touched, done, heard;
    private float downY;
    private final Runnable autoHide = () -> close(true);
    /** The talk on this card (null: none yet). */
    private CardTalk talk;
    /** A hide the talk asked for (and whether the messages then wait under the dot). */
    private boolean laterOnHide;
    private final Runnable hide = () -> close(laterOnHide);

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
        if (m.talk) t.startTalk(CardTalk.START_ASK); else t.main.postDelayed(t.autoHide, 6500);
        return true;
    }

    /** Main thread. Gone at once (a screenshot is about to be taken, or the screen went off); unheard messages wait under the dot. */
    static void dismissNow() {
        TopCard t = showing;
        if (t != null) t.close(true, false);
        TopCard c = leaving; // one still sliding away
        if (c != null) c.removeView();
    }

    /**
     * Main thread. Another Jarvis screen (the panel, a call, the app, the camera) is starting: the card steps aside before
     * it talks (unheard messages under the dot), leaving the talk state to that screen.
     */
    static void stepAside() {
        TopCard t = showing;
        if (t == null) return;
        CardTalk c = t.talk;
        t.talk = null;
        if (c != null) c.stop(CardTalk.YIELD_BEFORE);
        t.close(!t.heard);
    }

    /** Jarvis is talking or listening on a card right now. */
    static boolean talkingNow() {
        TopCard t = showing;
        CardTalk c = t == null ? null : t.talk;
        return c != null && c.talking();
    }

    /** A card is busy with him (talking, or waiting for his tap mid-reply): a new message's card must wait. */
    static boolean busy() {
        TopCard t = showing;
        CardTalk c = t == null ? null : t.talk;
        return c != null && c.busy();
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
        view.addView(line2); // (scrolled by code to follow the reading; not by touch, so a swipe up anywhere closes the card)
        status = new TextView(svc);
        status.setTextColor(0xFF8FE9FF);
        status.setTextSize(12.5f);
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(View.GONE);
        view.addView(status);
        buttons = new LinearLayout(svc);
        buttons.setGravity(Gravity.END);
        if (!m.talk) { // the talk sets its own buttons
            buttons.addView(button("🔊 చదువు", v -> startTalk(CardTalk.START_READ)));
            if (m.group || n >= 3) buttons.addView(button("📝 సారాంశం", v -> startTalk(CardTalk.START_SUMMARY)));
            buttons.addView(button("⏰ తర్వాత", v -> close(true)));
        }
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
        t.setOnClickListener(v -> { if (done) return; touched = true; main.removeCallbacks(autoHide); main.removeCallbacks(hide); l.onClick(v); });
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
                if (!touched && talk == null) main.postDelayed(autoHide, 4000);
                return true;
            default:
                return false;
        }
    }

    /** 🔊 / 📝 tapped on a card without talk: Jarvis reads it (or sums it up) on the card, then asks about a reply. */
    private void startTalk(int how) {
        if (talk != null || done) return;
        main.removeCallbacks(autoHide);
        talk = new CardTalk(svc, this, m);
        talk.start(how);
    }

    // ---------------------------------------------------------------- for CardTalk (main thread)

    /** The small line under the message: what Jarvis is doing ("🎙️ వింటున్నాను…"); empty hides it. */
    void status(String s) {
        if (done || status == null) return;
        status.setText(s == null ? "" : s);
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The message area: the messages being read, a summary, or his reply. */
    void body(CharSequence s, int maxLines) {
        if (done || line2 == null) return;
        line2.setEllipsize(maxLines <= 2 ? TextUtils.TruncateAt.END : null);
        line2.setMaxLines(maxLines);
        line2.setText(s);
        line2.scrollTo(0, 0);
    }

    /** The word being read now, highlighted, and scrolled into view. */
    void highlight(String text, int start, int end) {
        if (done || line2 == null || text == null) return;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int a = Math.max(0, Math.min(start, text.length())), b = Math.max(a, Math.min(end, text.length()));
        if (b > a) sp.setSpan(new android.text.style.BackgroundColorSpan(0x6622D3EE), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line2.setText(sp);
        android.text.Layout l = line2.getLayout();
        if (l != null && line2.getHeight() > 0) {
            int y = l.getLineTop(l.getLineForOffset(a)) - line2.getHeight() / 3;
            int max = Math.max(0, l.getHeight() - line2.getHeight() + line2.getTotalPaddingTop() + line2.getTotalPaddingBottom());
            line2.scrollTo(0, Math.max(0, Math.min(y, max)));
        }
    }

    /** The buttons for this step: label, Runnable, label, Runnable… */
    void buttons(Object... labelThenAction) {
        if (done || buttons == null) return;
        buttons.removeAllViews();
        for (int i = 0; i + 1 < labelThenAction.length; i += 2) {
            final Runnable r = (Runnable) labelThenAction[i + 1];
            buttons.addView(button((String) labelThenAction[i], v -> r.run()));
        }
    }

    /** Heard now (read, summed up, played or declined): closing no longer puts it off for later. */
    void heardNow() { heard = true; }

    boolean heard() { return heard; }

    /** Jarvis is talking or listening: the card stays. */
    void stayOpen() {
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
    }

    /** The card goes in ms (later: unheard messages wait under the dot). */
    void hideIn(long ms, boolean later) {
        if (done) return;
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
        laterOnHide = later;
        main.postDelayed(hide, ms);
    }

    /** The talk ended itself and closes the card. */
    void closeFromTalk(boolean later) {
        talk = null;
        close(later);
    }

    private void close(boolean later) { close(later, true); }

    /** later: not read yet, so it waits under the floating button's dot. animate: slides up (else gone at once). */
    private void close(boolean later, boolean animate) {
        if (done) { if (!animate) removeView(); return; }
        done = true;
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
        CardTalk t = talk; // whatever Jarvis was saying or hearing on it stops
        talk = null;
        if (t != null) t.stop();
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
