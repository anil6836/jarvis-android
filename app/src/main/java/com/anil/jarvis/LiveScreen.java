package com.anil.jarvis;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The full-screen Live conversation, like ChatGPT's voice mode: the big hologram core in the middle,
 * what is being said underneath, and mute / end buttons at the bottom. The talk itself is LiveSession's.
 */
final class LiveScreen extends FrameLayout {
    interface Actions {
        void onLiveEnd();
        void onLiveMute(boolean muted);
        void onLiveMinimize();
        /** He tapped Jarvis (the orb) while it talks: stop this answer and listen. */
        default void onLiveInterrupt() {}
    }

    private static final int BLUE = 0xFF2F6BEF;

    final HoloOrb orb;
    private final TextView status, caption;
    private final FrameLayout muteBtn;
    private final IconView muteIcon;
    private final Actions actions;
    private boolean muted;
    private int lastOrb = HoloOrb.CONNECTING;

    LiveScreen(Context c, Actions a) {
        super(c);
        actions = a;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF03080E, Ui.INK, 0xFF02060A});
        setBackground(bg);
        setClickable(true); // the chat underneath must not get the touches

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(col, new LayoutParams(-1, -1));

        // top: minimise (keep talking, see the chat), title, status
        FrameLayout top = new FrameLayout(c);
        IconView down = new IconView(c, IconView.DOWN, Ui.MUTED);
        down.setContentDescription("చాట్ చూపించు (Live ఆగదు)");
        down.setOnClickListener(v -> actions.onLiveMinimize());
        top.addView(down, new LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView title = Ui.mono(c, "JARVIS", 18, Ui.CYAN);
        title.setLetterSpacing(0.4f);
        top.addView(title, new LayoutParams(-2, -2, Gravity.CENTER));
        TextView live = Ui.mono(c, "● LIVE", 11, Ui.RED);
        live.setPadding(0, 0, dp(12), 0);
        top.addView(live, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(56));
        tlp.topMargin = dp(10);
        col.addView(top, tlp);

        status = Ui.text(c, "", 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // middle: the core
        orb = new HoloOrb(c);
        orb.setContentDescription("Jarvis");
        orb.setOnClickListener(v -> actions.onLiveInterrupt()); // tap Jarvis to stop its answer
        col.addView(orb, new LinearLayout.LayoutParams(-1, 0, 1));

        // what is being said
        caption = Ui.text(c, "", 16.5f, Ui.TEXT);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(4);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setLineSpacing(0, 1.12f);
        caption.setPadding(dp(24), 0, dp(24), 0);
        col.addView(caption, new LinearLayout.LayoutParams(-1, dp(104)));

        // bottom: mute, end
        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        muteBtn = new FrameLayout(c);
        muteIcon = new IconView(c, IconView.MIC, Ui.TEXT);
        muteBtn.addView(muteIcon, new LayoutParams(-1, -1));
        muteBtn.setOnClickListener(v -> setMuted(!muted, true));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(66), dp(66));
        mlp.rightMargin = dp(44);
        controls.addView(muteBtn, mlp);
        FrameLayout end = new FrameLayout(c);
        end.setBackground(Ui.round(c, Ui.RED, 0, 33));
        end.addView(new IconView(c, IconView.CLOSE, 0xFF2A0703), new LayoutParams(-1, -1));
        end.setContentDescription("Live ఆపు");
        end.setOnClickListener(v -> actions.onLiveEnd());
        controls.addView(end, new LinearLayout.LayoutParams(dp(66), dp(66)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        clp.bottomMargin = dp(34);
        col.addView(controls, clp);
        setMuted(false, false);
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    /** A new conversation: fresh screen, mic on; shown now or later (when the live camera is open, the chat stays in view). */
    void open(boolean showNow) {
        caption.setText("");
        caption.setTextColor(Ui.TEXT);
        setMuted(false, false);
        state(OrbView.THINKING, "కనెక్ట్ అవుతున్నాను…");
        orb.setState(HoloOrb.CONNECTING);
        if (showNow) show();
    }

    private boolean wanted;

    void show() {
        if (wanted && getVisibility() == VISIBLE) return;
        wanted = true;
        animate().cancel();
        if (getVisibility() != VISIBLE) { setAlpha(0f); setVisibility(VISIBLE); }
        animate().alpha(1f).setDuration(220).start();
    }

    void hide() {
        wanted = false;
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        animate().alpha(0f).setDuration(180).withEndAction(() -> { if (!wanted) setVisibility(GONE); }).start();
    }

    boolean showing() { return wanted && getVisibility() == VISIBLE; }

    /** LiveSession's state (OrbView states) and status line. */
    void state(int orbState, String text) {
        status.setText(muted ? "మైక్ ఆఫ్ · " + text : text);
        int s;
        switch (orbState) {
            case OrbView.LISTENING: s = HoloOrb.LISTENING; break;
            case OrbView.SPEAKING: s = HoloOrb.SPEAKING; break;
            case OrbView.THINKING: s = "కనెక్ట్ అవుతున్నాను…".equals(text) ? HoloOrb.CONNECTING : HoloOrb.THINKING; break;
            default: s = HoloOrb.LISTENING;
        }
        lastOrb = s;
        orb.setState(muted && s == HoloOrb.LISTENING ? HoloOrb.MUTED : s);
    }

    /** His words (dimmer) or Jarvis's words as they are spoken. */
    void caption(String text, boolean jarvis) {
        caption.setTextColor(jarvis ? Ui.TEXT : Ui.MUTED);
        String t = text == null ? "" : text.trim();
        if (t.length() > 170) { // keep the newest words on screen
            int cut = t.indexOf(' ', t.length() - 170);
            t = "…" + t.substring(cut < 0 ? t.length() - 170 : cut + 1);
        }
        caption.setText(t);
    }

    void error(String text) {
        caption.setTextColor(Ui.RED);
        caption.setText(text);
    }

    private void setMuted(boolean m, boolean tell) {
        muted = m;
        muteIcon.setIcon(m ? IconView.MIC_OFF : IconView.MIC);
        muteIcon.setColor(m ? Ui.RED : Ui.TEXT);
        muteBtn.setBackground(m ? Ui.round(getContext(), 0x33FF6B5E, Ui.RED, 33) : Ui.round(getContext(), Ui.PANEL2, Ui.LINE2, 33));
        muteBtn.setContentDescription(m ? "మైక్ ఆన్ చేయి" : "మైక్ ఆఫ్ చేయి");
        if (m) {
            orb.setMic(0f);
            if (lastOrb == HoloOrb.LISTENING) orb.setState(HoloOrb.MUTED);
            status.setText("మైక్ ఆఫ్ · మాట్లాడాలంటే మైక్ బటన్ నొక్కండి");
        } else {
            if (lastOrb == HoloOrb.LISTENING) orb.setState(HoloOrb.LISTENING);
            if (tell) status.setText("మాట్లాడండి…");
        }
        if (tell) actions.onLiveMute(m);
    }

    static int blue() { return BLUE; }
}
