package com.anil.jarvis;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 🎛️ "అన్ని ఎంపికలు" over the car screen (⑪, 26 Oct; also from any shape): the parts down the left (Jarvis, the map,
 * songs, …), the chosen part's choices as cards on the right (each its own chips, the one on now lit), ↩️ back, 💾 keep
 * the look as "నా థీమ్ 1–3". 🧍 opens it straight at Jarvis's part, a card held (parked) at its own part. Only while
 * parked: the car moving closes it (his words still work any time). What he picks is written at once and the screen
 * follows it ({@link Host#changed}); nothing here is ever removed from the old ⚙️.
 */
final class CarHub extends FrameLayout {

    interface Host {
        /** A look was changed: the screen takes it now. */
        void changed(String key);
        /** A button of the hub: "g2", "photo_top", "stage", "music_app", "apps", "buttons", "photos", "fatigue", "alarm_test", "eyes", "quiet", "vol_down", "vol_up", "settings". */
        void act(String id);
        /** A word on the screen (a toast). */
        void say(String text);
        /** Closed (↩️, ✕, a tap outside, the car moved). */
        void closed();
    }

    private final Prefs prefs;
    private final Host host;
    private final LinearLayout box, cats, body, bottom;
    private final ScrollView catScroll, bodyScroll;
    private final TextView title, note, close, back, keep, voice;
    private final List<TextView> catViews = new ArrayList<>();
    private String catNow = "jarvis";
    private int textMain = 0xFFFFFFFF, textSoft = 0xFFB9CCDA, accent = 0xFF5FF3FF, fill = 0xF00A1220;
    private boolean light;
    private int W, H;
    /** Where the panel sits: false = on the right (Jarvis on the left), true = on the left. */
    private boolean leftSide;

    CarHub(Context c, Prefs prefs, Host host) {
        super(c);
        this.prefs = prefs;
        this.host = host;
        setBackgroundColor(0x99000000);
        setClickable(true);
        setVisibility(GONE);
        setOnClickListener(v -> hide());
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setClickable(true); // (taps on the panel stay in it)
        addView(box, new LayoutParams(-1, -1));
        catScroll = new ScrollView(c);
        catScroll.setVerticalScrollBarEnabled(false);
        cats = new LinearLayout(c);
        cats.setOrientation(LinearLayout.VERTICAL);
        catScroll.addView(cats, new ScrollView.LayoutParams(-1, -2));
        box.addView(catScroll, new LinearLayout.LayoutParams(0, -1, 26));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        box.addView(right, new LinearLayout.LayoutParams(0, -1, 74));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(c);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        note = new TextView(c);
        note.setText("🅿️ ఆగినప్పుడే మార్చవచ్చు · 🎙️ మాటతో ఎప్పుడైనా");
        note.setMaxLines(1);
        note.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        head.addView(note, nl);
        close = new TextView(c);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> hide());
        head.addView(close, new LinearLayout.LayoutParams(-2, -2));
        right.addView(head, new LinearLayout.LayoutParams(-1, -2));
        bodyScroll = new ScrollView(c);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        bodyScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        right.addView(bodyScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        back = new TextView(c);
        back.setText("↩️ వెనక్కి");
        back.setOnClickListener(v -> hide());
        keep = new TextView(c);
        keep.setText("💾 ఇది నా సెట్ గా దాచు");
        keep.setOnClickListener(v -> keepAsk());
        voice = new TextView(c);
        voice.setMaxLines(2);
        voice.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottom.addView(back, new LinearLayout.LayoutParams(-2, -2));
        bottom.addView(keep, new LinearLayout.LayoutParams(-2, -2));
        bottom.addView(voice, new LinearLayout.LayoutParams(0, -2, 1));
        right.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        for (CarHubSpec.Cat cat : CarHubSpec.cats()) {
            TextView t = new TextView(c);
            t.setText(cat.icon + "  " + cat.name);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            final String id = cat.id;
            t.setOnClickListener(v -> show(id));
            t.setTag(id);
            cats.addView(t);
            catViews.add(t);
        }
    }

    boolean shown() { return getVisibility() == VISIBLE; }

    /** Opens at this part (CarHubSpec ids). */
    void show(String cat) {
        catNow = CarHubSpec.cat(cat).id;
        setVisibility(VISIBLE);
        bringToFront();
        fill();
    }

    void hide() {
        if (getVisibility() != VISIBLE) return;
        setVisibility(GONE);
        body.removeAllViews();
        host.closed();
    }

    /** The screen's colours; where the panel sits (Jarvis's side stays free). */
    void setLook(int main, int soft, int acc, int sheet, boolean light, boolean onLeft) {
        textMain = main;
        textSoft = soft;
        accent = acc;
        fill = sheet;
        this.light = light;
        leftSide = onLeft;
        place();
        if (shown()) fill();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = w;
        H = h;
        post(() -> { place(); if (shown()) fill(); });
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private GradientDrawable round(int f, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(f);
        g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(Math.max(1, dp(1.2f)), stroke);
        return g;
    }

    /** The panel's place and every size from the screen's. */
    private void place() {
        if (W <= 0 || H <= 0) return;
        int m = Math.max(4, Math.min(W, H) / 40);
        LayoutParams lp = (LayoutParams) box.getLayoutParams();
        int side = Math.round(W * 0.23f); // (Jarvis's column stays in sight)
        lp.setMargins(leftSide ? m : side, m, leftSide ? side : m, m);
        box.setLayoutParams(lp);
        box.setBackground(round(fill, CarTheme.withAlpha(accent, 0x88), Math.max(12, Math.min(W, H) / 26f)));
        int p = Math.max(6, Math.min(W, H) / 60);
        box.setPadding(p, p, p, p);
        float t = Math.max(13, Math.min(W, H) / 30f);
        for (TextView c : catViews) {
            c.setTextSize(TypedValue.COMPLEX_UNIT_PX, t);
            c.setPadding(p * 2, p, p, p);
            boolean on = catNow.equals(c.getTag());
            c.setTextColor(on ? textMain : textSoft);
            c.setBackground(round(on ? CarTheme.withAlpha(accent, 0x44) : (light ? 0x22000000 : 0x18FFFFFF), on ? accent : 0, Math.max(8, t * 0.6f)));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Math.round(t * 2.6f));
            cl.setMargins(0, 0, p, p);
            c.setLayoutParams(cl);
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 1.25f);
        title.setTextColor(textMain);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.7f);
        note.setTextColor(0xFFFFD166);
        note.setPadding(p * 2, 0, p, 0);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 1.2f);
        close.setTextColor(textMain);
        close.setBackground(round(light ? 0x22000000 : 0x22FFFFFF, CarTheme.withAlpha(accent, 0x66), 999));
        int cs = Math.round(t * 2.2f);
        close.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        for (TextView b : new TextView[]{back, keep}) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.85f);
            b.setTextColor(textMain);
            b.setPadding(p * 2, p, p * 2, p);
            b.setBackground(round(b == keep ? CarTheme.withAlpha(0xFF1E6B4A, 0xDD) : (light ? 0x22000000 : 0x22FFFFFF), CarTheme.withAlpha(accent, 0x88), Math.max(8, t * 0.5f)));
            LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) b.getLayoutParams();
            bl.setMargins(0, p, p, 0);
            b.setLayoutParams(bl);
        }
        voice.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.68f);
        voice.setTextColor(textSoft);
        voice.setText("🗣️ గొంతు: మీరు ఎంచుకున్నదే (ఇక్కడ మారదు)");
    }

    // ---------------------------------------------------------------- the part's cards

    private void fill() {
        place();
        CarHubSpec.Cat cat = CarHubSpec.cat(catNow);
        title.setText(cat.icon + " " + (cat.id.equals("jarvis") ? "Jarvis సెట్టింగ్స్" : cat.name));
        body.removeAllViews();
        float t = Math.max(13, Math.min(Math.max(W, 600), Math.max(H, 300)) / 30f);
        int p = Math.max(6, Math.min(Math.max(W, 600), Math.max(H, 300)) / 60);
        LinearLayout row = null;
        int inRow = 0;
        for (CarHubSpec.Opt o : cat.opts) {
            boolean wide = o.kind == CarHubSpec.K_INFO || o.count() > 4 || o.kind == CarHubSpec.K_ACT && o.title.length() > 28;
            if (row == null || inRow == 2 || wide) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row, new LinearLayout.LayoutParams(-1, -2));
                inRow = 0;
            }
            View card = card(o, t, p);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            cl.setMargins(0, p / 2, p, p / 2);
            row.addView(card, cl);
            inRow = wide ? 2 : inRow + 1;
        }
        if (row != null && inRow == 1) row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        bodyScroll.scrollTo(0, 0);
    }

    private View card(CarHubSpec.Opt o, float t, int p) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(p * 2, p, p * 2, p + p / 2);
        card.setBackground(round(light ? 0x14000000 : 0x14FFFFFF, CarTheme.withAlpha(accent, 0x33), Math.max(8, t * 0.6f)));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tt = new TextView(c);
        tt.setText(o.title);
        tt.setTextColor(o.kind == CarHubSpec.K_INFO ? textSoft : textMain);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.8f);
        if (o.kind == CarHubSpec.K_ACT) { // (a button: the whole card is it)
            tt.setTypeface(Typeface.DEFAULT_BOLD);
            card.setOnClickListener(v -> act(o));
            card.setBackground(round(CarTheme.withAlpha(accent, 0x26), CarTheme.withAlpha(accent, 0x88), Math.max(8, t * 0.6f)));
        }
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        if (o.fresh) {
            TextView nw = new TextView(c);
            nw.setText("కొత్తది");
            nw.setTextColor(0xFFFF9BC8);
            nw.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.58f);
            nw.setPadding(p, p / 4, p, p / 4);
            nw.setBackground(round(0x33FF4F9A, 0x88FF4F9A, 999));
            head.addView(nw, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        if (o.kind == CarHubSpec.K_ACT || o.kind == CarHubSpec.K_INFO) return card;
        Flow chips = new Flow(c, p);
        int sel = CarHubSpec.selected(o, prefs.sp.getAll().get(o.key));
        for (int i = 0; i < o.count(); i++) {
            final int idx = i;
            TextView ch = new TextView(c);
            ch.setText(o.names[i]);
            ch.setMaxLines(1);
            ch.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.72f);
            boolean on = i == sel;
            ch.setTextColor(on ? textMain : textSoft);
            ch.setPadding(p * 2, p / 2 + 2, p * 2, p / 2 + 2);
            if (o.kind == CarHubSpec.K_INT && o.key.equals("car_colour") || (o.key.equals("car_jarvis_glow") || o.key.equals("car_accent")) && i > 0) { // (colours: a dot of it before the name)
                int col = o.kind == CarHubSpec.K_INT ? o.ints[i] : CarDeck.glowColour(o.values[i]);
                ch.setCompoundDrawablesRelative(dot(col, Math.round(t * 0.7f)), null, null, null);
                ch.setCompoundDrawablePadding(p / 2);
            }
            ch.setBackground(round(on ? CarTheme.withAlpha(accent, 0x40) : (light ? 0x18000000 : 0x14FFFFFF), on ? accent : CarTheme.withAlpha(textSoft, 0x44), 999));
            ch.setOnClickListener(v -> pick(o, idx));
            chips.addView(ch);
        }
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = p / 2;
        card.addView(chips, fl);
        return card;
    }

    private static GradientDrawable dot(int col, int size) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(col);
        g.setStroke(1, 0x88FFFFFF);
        g.setBounds(0, 0, size, size);
        return g;
    }

    /** A chip: written (parked only), the screen told, the cards drawn again. */
    private void pick(CarHubSpec.Opt o, int i) {
        if (CarBox.moving()) { host.say("కారు ఆపాక మార్చండి (మాటతో ఎప్పుడైనా)"); return; }
        if (!CarHubSpec.writable(o.key)) return;
        android.content.SharedPreferences.Editor e = prefs.sp.edit();
        if (o.kind == CarHubSpec.K_STR) e.putString(o.key, o.values[i]);
        else if (o.kind == CarHubSpec.K_INT) e.putInt(o.key, o.ints[i]);
        else e.putBoolean(o.key, i == 0);
        e.apply();
        if ("photo".equals(o.kind == CarHubSpec.K_STR ? o.values[i] : "") && !CarPhoto.topFile(getContext()).exists())
            host.say("మీ కారు పై నుంచి ఫోటో ఇంకా లేదు: '📷 నా కారు ఫోటో (పై నుంచి) ఎంచుకో' నొక్కండి. అప్పటిదాకా SUV బొమ్మ.");
        int y = bodyScroll.getScrollY(); // (kept: the screen's change may draw the cards again)
        host.changed(o.key);
        fill();
        bodyScroll.post(() -> bodyScroll.scrollTo(0, y));
    }

    private void act(CarHubSpec.Opt o) {
        if (CarBox.moving() && !o.key.equals("quiet") && !o.key.startsWith("vol")) { host.say("కారు ఆపాక (మాటతో ఎప్పుడైనా)"); return; }
        if (o.key.equals("mine")) { keepAsk(); return; }
        host.act(o.key);
        if (shown()) { int y = bodyScroll.getScrollY(); fill(); bodyScroll.post(() -> bodyScroll.scrollTo(0, y)); }
    }

    /** 💾 Which of his three places: the look now kept there (what each holds now, in a few words). */
    private void keepAsk() {
        if (CarBox.moving()) { host.say("కారు ఆపాక"); return; }
        body.removeAllViews();
        title.setText("💾 ఇప్పటి రూపం ఎక్కడ దాచాలి?");
        float t = Math.max(13, Math.min(Math.max(W, 600), Math.max(H, 300)) / 30f);
        int p = Math.max(6, Math.min(Math.max(W, 600), Math.max(H, 300)) / 60);
        for (int n = 1; n <= CarDeck.MINE; n++) {
            final int slot = n;
            String kept = prefs.sp.getString("car_mine_" + n, "");
            TextView b = new TextView(getContext());
            b.setText("💾 నా థీమ్ " + n + (kept.isEmpty() ? " (ఖాళీ)" : " · ఇప్పుడు: " + CarDeck.summary(kept) + " (దీని మీద దాస్తాను)"));
            b.setTextColor(textMain);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.85f);
            b.setPadding(p * 2, p, p * 2, p);
            b.setBackground(round(CarTheme.withAlpha(accent, 0x26), CarTheme.withAlpha(accent, 0x88), Math.max(8, t * 0.6f)));
            b.setOnClickListener(v -> {
                prefs.sp.edit().putString("car_mine_" + slot, CarDeck.keep(prefs.sp.getAll())).apply();
                host.say("ఇప్పటి రూపం 'నా థీమ్ " + slot + "' లో దాచాను (⚙️ లో లేదా 🎛️ → ఇంకా లో మళ్ళీ పెట్టవచ్చు)");
                fill();
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.setMargins(0, p / 2, p, p / 2);
            body.addView(b, bl);
            if (!kept.isEmpty()) {
                TextView put = new TextView(getContext());
                put.setText("📥 నా థీమ్ " + n + " పెట్టు (" + CarDeck.summary(kept) + ")");
                put.setTextColor(textMain);
                put.setTextSize(TypedValue.COMPLEX_UNIT_PX, t * 0.8f);
                put.setPadding(p * 2, p, p * 2, p);
                put.setBackground(round(light ? 0x14000000 : 0x14FFFFFF, CarTheme.withAlpha(accent, 0x55), Math.max(8, t * 0.6f)));
                put.setOnClickListener(v -> {
                    java.util.Map<String, Object> m = CarDeck.back(kept);
                    if (m == null) { host.say("ఈ థీమ్ చదవలేకపోయాను"); return; }
                    prefs.applyLook(m);
                    host.say("నా థీమ్ " + slot + " పెట్టాను");
                    host.changed("car_mine");
                    fill();
                });
                body.addView(put, bl);
            }
        }
    }

    /** Chips in rows that wrap to the card's width. */
    private static final class Flow extends ViewGroup {
        private final int gap;
        Flow(Context c, int gap) { super(c); this.gap = gap; }

        @Override protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                v.layout(x, y, x + w, y + h);
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
        }
    }

    /** A word for a toast-like line (unused when the host speaks). */
    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }
}
