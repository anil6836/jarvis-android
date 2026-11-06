package com.anil.jarvis;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One status card of a main-screen look (🛸 tilted HUD glass, 💎 thick shiny glass, 🪐 dark space glass): its name, the
 * value, a line under, and for the phone's / bike's battery the liquid pill. KIND_HERO beside the orb, KIND_TILE the
 * liquid look's three big tiles, KIND_SMALL the row under the tabs. A tap opens its page (H1), a long press 📌 (H2).
 */
final class LookCard extends LinearLayout {
    static final int KIND_HERO = 0, KIND_TILE = 1, KIND_SMALL = 2;
    final String look;
    final int kind;
    String key = "";
    /** Which slot of his order this card shows (for 📌); -1 = the rain line. */
    int slot = -1;
    private final TextView title, value, sub;
    private final LinearLayout valueRow;
    private LiquidPill pill;
    private boolean pillStanding;
    private final int ink, inkSub, inkTitle;
    private int tint;
    private boolean short_;

    LookCard(Context c, String look, int kind, int tint) {
        super(c);
        this.look = look;
        this.kind = kind;
        this.tint = tint;
        setOrientation(VERTICAL);
        boolean liquid = MainLook.LIQUID.equals(look), holo = MainLook.HOLO.equals(look);
        ink = 0xFFFFFFFF;
        inkSub = liquid ? 0xE6FFFFFF : holo ? 0xFFA6CADB : 0xFFB3B9E0;
        inkTitle = liquid ? 0xF2FFFFFF : holo ? 0xFF7FE7FF : 0xFFCDD2FF;
        int p = dp(kind == KIND_SMALL ? 10 : 9);
        setPadding(p, dp(kind == KIND_SMALL ? 7 : 8), p, dp(kind == KIND_SMALL ? 7 : 8));
        title = text(kind == KIND_SMALL ? 0 : 11, inkTitle, false);
        if (kind != KIND_SMALL) addView(title);
        valueRow = new LinearLayout(c);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        value = text(kind == KIND_SMALL ? 13.5f : kind == KIND_TILE ? 22 : 19, ink, true);
        valueRow.addView(value, new LayoutParams(-2, -2));
        addView(valueRow, new LayoutParams(-1, -2));
        sub = text(kind == KIND_SMALL ? 11 : 10.5f, inkSub, false);
        addView(sub);
        setBackground(bg(false));
        setClickable(true);
        setFocusable(true);
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = Ui.text(getContext(), "", sp <= 0 ? 11 : sp, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (MainLook.LIQUID.equals(look)) t.setShadowLayer(dp(3), 0, dp(1), 0x55000000); // (letters stay readable over the moving colours)
        return t;
    }

    /** The card's glass; warn = the amber edge of the rain line. */
    GradientDrawable bg(boolean warn) {
        GradientDrawable g;
        if (MainLook.HOLO.equals(look)) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99103A55, 0x7706182A});
            g.setCornerRadius(dp(kind == KIND_SMALL ? 8 : 6));
            g.setStroke(dp(1), warn ? 0xCCF2B24C : 0xAA22D3EE);
        } else if (MainLook.LIQUID.equals(look)) {
            int t = tint == 0 ? 0xFFFFFFFF : tint;
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PageTheme.withAlpha(PageTheme.mix(t, 0xFFFFFFFF, 0.35f), 0x66),
                    PageTheme.withAlpha(t, 0x33)});
            g.setCornerRadius(dp(kind == KIND_SMALL ? 999 : 22));
            g.setStroke(dp(1.2f), warn ? 0xDDFFD27A : 0x88FFFFFF);
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xA61E1A48, 0x8C0E0B26});
            g.setCornerRadius(dp(kind == KIND_SMALL ? 12 : 14));
            g.setStroke(dp(1), warn ? 0xCCFFC46B : 0x669FA8FF);
        }
        return g;
    }

    /** Shows a status (MainLook.STATUS key) from the dashboard's model. */
    void show(String key, HudDashboard.Stat s, boolean motion) {
        this.key = key;
        String emoji = "", name = key;
        for (String[] st : MainLook.STATUS) if (st[0].equals(key)) { emoji = st[1]; name = st[2]; }
        if ("battery".equals(key)) name = "ఫోన్";
        set(emoji + " " + name, s.value, s.sub, s.color, false);
        boolean wantsPill = ("battery".equals(key) || "bike".equals(key)) && s.pct >= 0;
        if (wantsPill) {
            boolean standing = kind == KIND_TILE && "battery".equals(key);
            if (pill == null || pillStanding != standing) {
                if (pill != null) ((android.view.ViewGroup) pill.getParent()).removeView(pill);
                pill = new LiquidPill(getContext(), standing);
                pillStanding = standing;
                if (standing) { // the phone's tile in the liquid look: a standing pill beside the %
                    LayoutParams lp = new LayoutParams(dp(short_ ? 14 : 18), dp(short_ ? 34 : 50));
                    lp.rightMargin = dp(8);
                    valueRow.addView(pill, 0, lp);
                } else {
                    LayoutParams lp = new LayoutParams(-1, dp(kind == KIND_SMALL ? 6 : 8));
                    lp.topMargin = dp(4);
                    lp.bottomMargin = dp(2);
                    addView(pill, indexOfChild(valueRow) + 1, lp);
                }
            }
            pill.setMotion(motion);
            pill.set(s.pct, s.charging);
            pill.setVisibility(VISIBLE);
            if (s.charging && kind != KIND_SMALL) title.setText(emoji + " " + name + " ⚡");
        } else if (pill != null) pill.setVisibility(GONE);
    }

    /** The rain line (H5) or anything else with its own words. */
    void set(String head, String val, String under, int color, boolean warn) {
        String v = val == null ? "" : val;
        if (kind == KIND_SMALL && head != null && !head.isEmpty()) {
            String e = head.split(" ", 2)[0]; // the small row: "🗓️ రేపు 6:00"
            v = v.startsWith(e) ? v : e + " " + v;
        }
        if (!head.contentEquals(title.getText())) title.setText(head);
        if (!v.contentEquals(value.getText())) value.setText(v);
        String u = under == null ? "" : under;
        if (!u.contentEquals(sub.getText())) sub.setText(u);
        sub.setVisibility(u.isEmpty() ? GONE : VISIBLE);
        // the old tiles' colours tell a problem (red), good (green), keep an eye (gold); the rest white on these looks
        int ok = Ui.OK, red = Ui.RED, gold = Ui.GOLD;
        int vc = color == red ? 0xFFFF8A80 : color == ok ? 0xFF7CF2B0 : color == gold ? 0xFFFFD27A : ink;
        if (MainLook.LIQUID.equals(look)) vc = ink;
        value.setTextColor(vc);
        setBackground(bg(warn));
        setContentDescription(head + ": " + v + (u.isEmpty() ? "" : ", " + u));
    }

    /** Smaller letters when the hero is short (the face is on). */
    void compact(boolean c) {
        short_ = c;
        value.setTextSize(kind == KIND_SMALL ? 13.5f : c ? 16 : kind == KIND_TILE ? 22 : 19);
        if (kind != KIND_SMALL) sub.setMaxLines(1);
    }
}
