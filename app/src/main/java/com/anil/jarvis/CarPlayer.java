package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 🎵 ⑪'s player (26 Oct; the big player is the same, bigger): the song's picture, its name, the singer, the music app
 * (a tap: another music app), the place in the song with its times (dragged only while parked), and the keys 🔀 ⏪10 ⏮
 * ⏯ ⏭ 10⏩ 🔁 ❤️ 📃 🔊. Shuffle, repeat, like and "up next" only where the music app allows them: else their key is dim,
 * and a tap says so honestly. The colourful equalizer is the card's own background (the screen's one).
 * His word (26 Oct): every key a big raised 3D button; ⑪'s own player shows only 🔀 ⏮ ⏯ ⏭ 🔁 ({@link #setFew}), the big
 * player (a tap on the picture) keeps them all. ⑫ (27 Oct) stands it up ({@link #setRound}): the picture round with its
 * ring and the music all round it on top, the name, the place, the keys under it.
 * The new looks (31 Oct, the players' sheet; {@link #setLook}): "cover" — the picture in the middle with two leaning
 * beside it and its colour glowing under it; "warm" — the song's own colours softly behind the whole card, the name and
 * the keys on a glass bar; "stack" — the picture on a little stack with the songs that come next beside it (when the
 * app tells them); "wave" — the place in the song drawn as the song's wave. Standing in a tall card, lying in a wide one.
 */
final class CarPlayer extends LinearLayout {

    interface Listener {
        /** A key: "prev", "toggle", "next", "shuffle", "repeat", "like", "back10", "fwd10", "seek:<ms>", "queue", "volume". */
        void key(String what);
        /** The app chip: another music app. */
        void apps();
        /** The picture: the big player (or back). */
        void art();
    }

    private final Listener l;
    private final FrameLayout artBox;
    private final ImageView artImg;
    private final CarArt.Icon artIcon;
    private final CarLooks.Vinyl vinyl;
    private final CarLooks.RoundArt roundArt;
    private boolean round;
    private final LinearLayout right, top, keys, words;
    private final TextView title, sub, app;
    private final Seek seek;
    private final CarArt.Icon[] keyViews;
    private static final String[] KEYS = {"shuffle", "back10", "prev", "toggle", "next", "fwd10", "repeat", "like", "queue", "volume"};
    private static final int[] KIND = {CarArt.Icon.SHUFFLE, CarArt.Icon.BACK10, CarArt.Icon.PREV, CarArt.Icon.PLAY, CarArt.Icon.NEXT, CarArt.Icon.FWD10,
            CarArt.Icon.REPEAT, CarArt.Icon.HEART, CarArt.Icon.QUEUE, CarArt.Icon.VOLUME};
    private int caps = -1, textMain = 0xFFFFFFFF, textSoft = 0xFFB9CCDA, accent = 0xFF5FF3FF;
    private int shuffle = -1, repeat = -1;
    private boolean liked, playing, hasSong, vinylOn, few, light;
    /** The keys ⑪'s own player keeps (his word, 26 Oct): shuffle, the song before, play / pause, the next song, repeat. */
    static final String[] FEW = {"shuffle", "prev", "toggle", "next", "repeat"};
    private Bitmap art, warmArt;
    /** The look (CarStyle.PLAYERS but ""): old, round, cover, warm, stack, wave. */
    private String look = "old";
    private boolean standing;
    private final CarGlass.Covers covers;
    private long[] nextIds = new long[0];
    private int waveRest;
    private final RectF warmBox = new RectF();
    private final Paint warmPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Path warmClip = new android.graphics.Path();

    CarPlayer(Context c, Listener l) {
        super(c);
        this.l = l;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        artBox = new FrameLayout(c);
        roundArt = new CarLooks.RoundArt(c); // (⑫: standing, round)
        roundArt.setVisibility(GONE);
        artBox.addView(roundArt, new FrameLayout.LayoutParams(-1, -1));
        artIcon = new CarArt.Icon(c, CarArt.Icon.MUSIC);
        artIcon.setFill(0.6f);
        artBox.addView(artIcon, new FrameLayout.LayoutParams(-1, -1));
        artImg = new ImageView(c);
        artImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artImg.setClipToOutline(true);
        artImg.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(v.getWidth(), v.getHeight()) * 0.12f); }
        });
        artImg.setVisibility(GONE);
        artBox.addView(artImg, new FrameLayout.LayoutParams(-1, -1));
        vinyl = new CarLooks.Vinyl(c);
        vinyl.setVisibility(GONE);
        artBox.addView(vinyl, new FrameLayout.LayoutParams(-1, -1));
        covers = new CarGlass.Covers(c); // (the new looks' pictures: cover / stack)
        covers.setVisibility(GONE);
        covers.setTap(new CarGlass.Covers.Tap() {
            @Override public void art() { tap(l::art); }
            @Override public void next(int i) { if (i < nextIds.length) tap(() -> l.key("next:" + nextIds[i])); }
        });
        artBox.addView(covers, new FrameLayout.LayoutParams(-1, -1));
        artBox.setOnClickListener(v -> tap(l::art));
        addView(artBox, new LayoutParams(0, 0));

        right = new LinearLayout(c);
        right.setOrientation(VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        addView(right, new LayoutParams(0, -1, 1));
        top = new LinearLayout(c);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        title = new TextView(c);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        title.setShadowLayer(3, 0, 1, 0xCC000000);
        sub = new TextView(c);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setIncludeFontPadding(false);
        sub.setShadowLayer(3, 0, 1, 0xCC000000);
        words.addView(title, new LayoutParams(-1, -2));
        words.addView(sub, new LayoutParams(-1, -2));
        words.setOnClickListener(v -> tap(l::art));
        top.addView(words, new LayoutParams(0, -2, 1));
        app = new TextView(c);
        app.setMaxLines(1);
        app.setEllipsize(TextUtils.TruncateAt.END);
        app.setGravity(Gravity.CENTER);
        app.setOnClickListener(v -> tap(l::apps));
        top.addView(app, new LayoutParams(-2, -2));
        right.addView(top, new LayoutParams(-1, -2));
        seek = new Seek(c);
        right.addView(seek, new LayoutParams(-1, 0));
        keys = new LinearLayout(c);
        keys.setOrientation(HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keyViews = new CarArt.Icon[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            final String k = KEYS[i];
            CarArt.Icon ic = new CarArt.Icon(c, KIND[i]);
            ic.setClickable(true);
            ic.setOnClickListener(v -> tap(() -> l.key(k)));
            keyViews[i] = ic;
            keys.addView(ic, new LayoutParams(0, -1, k.equals("toggle") ? 1.5f : 1));
        }
        right.addView(keys, new LayoutParams(-1, 0));
        setSong("🎵 పాట లేదు", "ఇక్కడ నొక్కండి", "", false, false);
    }

    private void tap(Runnable r) {
        try { r.run(); } catch (Exception e) { Toast.makeText(getContext(), "అది ఇప్పుడు కాలేదు", Toast.LENGTH_SHORT).show(); }
    }

    // ---------------------------------------------------------------- what plays

    void setSong(String t, String s, String appName, boolean playing, boolean keysOn) {
        this.playing = playing;
        hasSong = keysOn;
        if (!t.contentEquals(title.getText())) title.setText(t);
        if (!s.contentEquals(sub.getText())) sub.setText(s);
        String a = appName == null || appName.isEmpty() ? "🎵 యాప్ ⌄" : appName + " ⌄";
        if (!a.contentEquals(app.getText())) app.setText(a);
        keyViews[3].setKind(playing ? CarArt.Icon.PAUSE : CarArt.Icon.PLAY);
        seek.setVisibility(keysOn ? VISIBLE : INVISIBLE);
        dims();
    }

    void setArt(Bitmap b) {
        art = b;
        artImg.setImageBitmap(b);
        vinyl.setArt(b);
        roundArt.setArt(b);
        covers.setArt(b);
        warmArt = null;
        if (b != null) try { warmArt = Bitmap.createScaledBitmap(b, 24, 24, true); } catch (Throwable ignored) {} // (a tiny copy drawn big: the song's colours, soft)
        showArt();
        invalidate();
    }

    /** ⑫: standing and round (true), or lying as everywhere else. */
    void setRound(boolean on) { setLook(on ? "round" : "old"); }

    boolean isRound() { return round; }

    /** The look (CarStyle.PLAYERS): "old", "round" (⑫), "cover", "warm", "stack", "wave". */
    void setLook(String lk) {
        String l = lk == null || lk.isEmpty() ? "old" : lk;
        if (l.equals(look)) return;
        look = l;
        round = "round".equals(l);
        covers.setMode("stack".equals(l) ? "stack" : "cover");
        seek.setWave("wave".equals(l));
        stand(round);
        showArt();
        invalidate();
        if (sw > 0) post(() -> sizes(sw, sh));
    }

    String look() { return look; }

    /** The old lying player (its card carries the screen's equalizer behind the keys). */
    boolean lyingOld() { return "old".equals(look) && !standing; }

    /** Standing (the picture on top) or lying (the picture on the left). */
    private void stand(boolean on) {
        if (on == standing && getOrientation() == (on ? VERTICAL : HORIZONTAL)) return;
        standing = on;
        setOrientation(on ? VERTICAL : HORIZONTAL);
        setGravity(on ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        right.setLayoutParams(on ? new LayoutParams(-1, 0, 1) : new LayoutParams(0, -1, 1));
    }

    /** The songs that come next (the music app's list from Music.queue: the ones after the one playing), for the stack. */
    void setNext(org.json.JSONArray q) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (q != null) {
            int start = -1;
            for (int i = 0; i < q.length(); i++) { JSONObject it = q.optJSONObject(i); if (it != null && it.optBoolean("now")) { start = i; break; } }
            for (int i = start + 1; i < q.length() && rows.size() < 3; i++) {
                JSONObject it = q.optJSONObject(i);
                if (it == null) continue;
                String t = it.optString("title");
                rows.add(new String[]{t.isEmpty() ? "పాట" : t, it.optString("sub")});
                ids.add(it.optLong("id"));
            }
        }
        nextIds = new long[ids.size()];
        for (int i = 0; i < nextIds.length; i++) nextIds[i] = ids.get(i);
        covers.setNext(rows);
    }

    /** The wave's colour after the place in the song (⑲: its yellow; 0: soft). */
    void setWaveRest(int c) { waveRest = c; seek.invalidate(); }

    @Override protected void dispatchDraw(Canvas c) {
        if ("warm".equals(look) && warmArt != null && getWidth() > 0) { // (the song's own colours softly behind the whole card)
            float in = Math.max(3, Math.min(getWidth(), getHeight()) * 0.035f), rr = Math.max(8, Math.min(getWidth(), getHeight()) * 0.08f);
            warmBox.set(in, in, getWidth() - in, getHeight() - in * 1.4f);
            warmClip.reset();
            warmClip.addRoundRect(warmBox, rr, rr, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(warmClip);
            float sc = Math.max(warmBox.width(), warmBox.height()) / 24f;
            c.translate(warmBox.centerX() - 12 * sc, warmBox.centerY() - 12 * sc);
            c.scale(sc, sc);
            warmPaint.setAlpha(light ? 150 : 210);
            c.drawBitmap(warmArt, 0, 0, warmPaint);
            c.restore();
            warmPaint.setAlpha(255);
            warmPaint.setShader(new android.graphics.LinearGradient(0, warmBox.top, 0, warmBox.bottom, light ? new int[]{0x22FFFFFF, 0xAAFFFFFF} : new int[]{0x22000000, 0xAA000000},
                    null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(warmBox, rr, rr, warmPaint);
            warmPaint.setShader(null);
        }
        super.dispatchDraw(c);
    }

    /** ⑫'s music all round the picture: the car speakers' equalizer, as he set it (🎚️ on / dim / off, fewer movements). */
    void setEq(CarEq eq, boolean on, boolean calm, boolean low) {
        roundArt.setEq(eq);
        roundArt.setEqOn(on, calm);
        roundArt.setLowMotion(low);
    }

    /** 💿 the song's picture on a turning record (his switch, as on the song strip). */
    void setVinyl(boolean on, boolean lowMotion) {
        vinylOn = on;
        vinyl.setLowMotion(lowMotion);
        roundArt.setShowArt(!on);
        showArt();
    }

    private void showArt() {
        boolean cv = "cover".equals(look) || "stack".equals(look);
        roundArt.setVisibility(round ? VISIBLE : GONE);
        covers.setVisibility(cv ? VISIBLE : GONE);
        artImg.setVisibility(!round && !cv && art != null && !vinylOn ? VISIBLE : GONE);
        vinyl.setVisibility(vinylOn && !cv ? VISIBLE : GONE);
        artIcon.setVisibility(!round && !cv && art == null && !vinylOn ? VISIBLE : round || cv ? GONE : INVISIBLE);
    }

    /** The music app's numbers (Music.progress), or null. */
    void setProgress(long[] pr) {
        if (pr == null) { seek.set(-1, 0, 1, false, -1); vinyl.setTiming(-1, 0, 1, playing, -1); roundArt.setTiming(-1, 0, 1, playing, -1); return; }
        seek.set(pr[0], pr[1], pr[2] / 1000f, pr[3] == 1, pr[4]);
        vinyl.setTiming(pr[0], pr[1], pr[2] / 1000f, pr[3] == 1, pr[4]);
        roundArt.setTiming(pr[0], pr[1], pr[2] / 1000f, pr[3] == 1, pr[4]);
    }

    /** What the app allows and what we last asked it (Music.extras), or null (none / Jarvis's radio). */
    void setExtras(JSONObject x) {
        caps = x == null ? 0 : x.optInt("caps");
        shuffle = x == null ? -1 : x.optInt("shuffle", -1);
        repeat = x == null ? -1 : x.optInt("repeat", -1);
        liked = x != null && x.optBoolean("liked");
        keyViews[6].setKind(repeat == Music.R_ONE ? CarArt.Icon.REPEAT_ONE : CarArt.Icon.REPEAT);
        dims();
    }

    /** Only 🔀 ⏮ ⏯ ⏭ 🔁, bigger (⑪'s own player); false: all ten (the big player). */
    void setFew(boolean on) {
        if (few == on) return;
        few = on;
        for (int i = 0; i < KEYS.length; i++) keyViews[i].setVisibility(!on || java.util.Arrays.asList(FEW).contains(KEYS[i]) ? VISIBLE : GONE);
        if (sw > 0) post(() -> sizes(sw, sh));
    }

    /** Where the picture ends (the equalizer's bars start after it). */
    int artRight() { return artBox.getRight(); }

    boolean allows(int cap) { return caps < 0 || (caps & cap) != 0; }

    /** The keys the app doesn't allow (or with no song): dim; on ones (shuffle, repeat, liked): lit. */
    private void dims() {
        for (int i = 0; i < KEYS.length; i++) {
            String k = KEYS[i];
            boolean ok = hasSong;
            if (k.equals("shuffle")) ok &= allows(Music.C_SHUFFLE);
            else if (k.equals("repeat")) ok &= allows(Music.C_REPEAT);
            else if (k.equals("like")) ok &= allows(Music.C_LIKE);
            else if (k.equals("back10") || k.equals("fwd10")) ok &= allows(Music.C_SEEK);
            else if (k.equals("queue")) ok &= allows(Music.C_QUEUE);
            else if (k.equals("volume")) ok = true;
            keyViews[i].setAlpha(ok ? 1f : 0.38f);
            boolean lit = k.equals("shuffle") && shuffle == 1 || k.equals("repeat") && repeat > 0 || k.equals("like") && liked;
            knob(keyViews[i], k.equals("toggle"), lit, accent, light);
        }
        seek.setAlpha(allows(Music.C_SEEK) ? 1f : 0.6f);
    }

    /**
     * A key as a 3D button (his word, 26 Oct; ⑪'s player and ⑪ ⤢'s song card the same): ⏯ in the theme's colour, the rest
     * dark (light on a light theme), a lit one (shuffle / repeat on) with a yellow sign and rim.
     */
    static void knob(CarArt.Icon k, boolean play, boolean lit, int accent, boolean light) {
        k.setKnob(play ? accent : light ? 0xFFE3E9EF : 0xFF26303B, lit ? 0xFFFFD166 : accent);
        k.setColour(play ? (bright(accent) ? 0xFF0B1118 : 0xFFFFFFFF) : lit ? (light ? 0xFFC77700 : 0xFFFFD166) : light ? 0xFF0B1118 : 0xFFFFFFFF);
    }

    /** A light colour (a dark sign reads better on it). */
    static boolean bright(int c) { return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f > 0.62f; }

    void setColours(int main, int soft, int acc, boolean light) {
        this.light = light;
        textMain = main;
        textSoft = soft;
        accent = acc;
        title.setTextColor(main);
        sub.setTextColor(soft);
        app.setTextColor(main);
        GradientDrawable g = new GradientDrawable();
        g.setColor(light ? 0xCCFFFFFF : 0x99060A10);
        g.setCornerRadius(999);
        g.setStroke(Math.max(1, Ui.dp(getContext(), 1)), CarTheme.withAlpha(acc, 0x99));
        app.setBackground(g);
        artIcon.setColour(acc);
        vinyl.setAccent(acc);
        roundArt.setLook(acc, light);
        covers.setColours(main, soft, acc, light);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(CarTheme.withAlpha(acc, 0x22));
        ab.setCornerRadius(Ui.dp(getContext(), 10));
        artBox.setBackground(round || "cover".equals(look) || "stack".equals(look) ? null : ab); // (⑫'s round picture, the covers: their own)
        artBg = ab;
        seek.invalidate();
        dims();
    }

    // ---------------------------------------------------------------- sizes from its own box

    private int sw, sh;

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == sw && h == sh) return;
        sw = w;
        sh = h;
        post(() -> sizes(w, h));
    }

    private GradientDrawable artBg;

    private void sizes(int w, int h) {
        int inner = Math.max(1, h - getPaddingTop() - getPaddingBottom()), avail = Math.max(1, w - getPaddingLeft() - getPaddingRight());
        boolean tallLook = "cover".equals(look) || "warm".equals(look) || "stack".equals(look);
        stand(round || tallLook && inner >= avail * 0.72f); // (the new looks stand in a tall card, lie in a wide one)
        right.setBackground(null);
        right.setPadding(0, 0, 0, 0); // (warm's glass bar only while it stands)
        if (round) { roundSizes(inner, avail); return; }
        if (standing) { standSizes(inner, avail); return; }
        boolean cv = "cover".equals(look) || "stack".equals(look);
        artBox.setBackground(cv ? null : artBg);
        vinyl.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        words.setGravity(Gravity.START);
        title.setGravity(Gravity.START);
        sub.setGravity(Gravity.START);
        int a = Math.max(1, Math.min(Math.round(inner * 0.94f), Math.round(avail * 0.3f)));
        LayoutParams ap = new LayoutParams(cv ? Math.round(a * ("stack".equals(look) ? 1.15f : 1.5f)) : a, a); // (the covers: a little wider, for the leaning ones)
        ap.rightMargin = Math.round(a * 0.1f);
        ap.gravity = Gravity.CENTER_VERTICAL;
        artBox.setLayoutParams(ap);
        float t = Math.max(12, Math.min(inner * 0.15f, avail * 0.035f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, t);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.72f));
        sub.setPadding(0, Math.round(t * 0.18f), 0, 0);
        app.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.66f));
        int pp = Math.round(t * 0.35f);
        app.setPadding(pp * 2, pp, pp * 2, pp);
        boolean wave = "wave".equals(look);
        seek.setLayoutParams(new LayoutParams(-1, Math.round(inner * (wave ? 0.34f : few ? 0.16f : 0.2f))));
        keys.setLayoutParams(new LayoutParams(-1, Math.round(inner * (wave ? 0.36f : few ? 0.48f : 0.4f)))); // (his word: the keys big)
        for (int i = 0; i < keyViews.length; i++) keyViews[i].setFill(i == 3 ? 1f : 0.86f);
    }

    /** The new looks standing: the picture(s) on top as big as the card allows, the name, the place, the keys (warm: on a glass bar). */
    private void standSizes(int inner, int avail) {
        boolean warm = "warm".equals(look), stack = "stack".equals(look);
        int ah = Math.max(1, Math.round(inner * (warm ? 0.5f : 0.52f)));
        int aw = warm ? Math.min(ah, avail) : avail;
        if (warm) ah = aw;
        LayoutParams ap = new LayoutParams(aw, ah);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.bottomMargin = Math.round(inner * 0.02f);
        artBox.setLayoutParams(ap);
        artBox.setBackground(warm ? artBg : null);
        float t = Math.max(12, Math.min(inner * 0.055f, avail * 0.065f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, t);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.74f));
        sub.setPadding(0, Math.round(t * 0.18f), 0, 0);
        app.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.66f));
        int pp = Math.round(t * 0.35f);
        app.setPadding(pp * 2, pp, pp * 2, pp);
        int g = stack ? Gravity.START : Gravity.CENTER_HORIZONTAL; // (the stack's name on the left, as on his sheet)
        words.setGravity(g);
        title.setGravity(g);
        sub.setGravity(g);
        seek.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.09f)));
        keys.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.2f)));
        for (int i = 0; i < keyViews.length; i++) keyViews[i].setFill(i == 3 ? 1f : 0.86f);
        if (warm) { // (the name, the place and the keys on a glass bar over the song's colours)
            GradientDrawable bar = new GradientDrawable();
            bar.setColor(light ? 0x8CFFFFFF : 0x40FFFFFF);
            bar.setCornerRadius(Math.max(8, t * 0.9f));
            bar.setStroke(Math.max(1, Ui.dp(getContext(), 1)), light ? 0x66FFFFFF : 0x55FFFFFF);
            right.setBackground(bar);
            int bp = Math.round(t * 0.5f);
            right.setPadding(bp, bp / 2, bp, bp / 2);
        } else right.setPadding(0, 0, 0, 0);
    }

    /** ⑫ standing: the round picture as big as the card allows (its top half or so), the name and the place, the keys. */
    private void roundSizes(int inner, int avail) {
        int a = Math.max(1, Math.min(Math.round(avail * 0.94f), Math.round(inner * 0.56f)));
        LayoutParams ap = new LayoutParams(a, a);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        artBox.setLayoutParams(ap);
        artBox.setBackground(null);
        int in = Math.round(a * 0.185f); // (💿 the record over the middle, inside the ring)
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(-1, -1);
        vl.setMargins(in, in, in, in);
        vinyl.setLayoutParams(vl);
        float t = Math.max(12, Math.min(inner * 0.05f, avail * 0.065f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, t);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.74f));
        sub.setPadding(0, Math.round(t * 0.18f), 0, 0);
        app.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, t * 0.66f));
        int pp = Math.round(t * 0.35f);
        app.setPadding(pp * 2, pp, pp * 2, pp);
        words.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        seek.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.085f)));
        keys.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.2f)));
        for (int i = 0; i < keyViews.length; i++) keyViews[i].setFill(i == 3 ? 1f : 0.86f);
    }

    // ---------------------------------------------------------------- the place in the song

    /** The track with the song's place, its times at the ends; a finger (parked only) moves it: the app is asked on lifting. */
    private final class Seek extends View implements Runnable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private long pos = -1, upd, dur = -1, dragAt = -1;
        private float speed = 1;
        private boolean on;

        private boolean wave;

        Seek(Context c) { super(c); }

        /** The place in the song drawn as the song's wave (the "wave" look). */
        void setWave(boolean on) { wave = on; invalidate(); }

        void set(long pos, long upd, float speed, boolean playing, long dur) {
            this.pos = pos; this.upd = upd; this.speed = speed; this.on = playing; this.dur = dur;
            invalidate();
        }

        private long now() { return CarDeck.songAt(pos, upd, speed, on, dur, android.os.SystemClock.elapsedRealtime()); }

        @Override public void run() { invalidate(); }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(this); }

        private float x0, x1;

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (wave) { // (the wave look: its own drawing)
                long at0 = dragAt >= 0 ? dragAt : now();
                float f0 = dur > 0 && at0 >= 0 ? Math.min(1f, at0 / (float) dur) : 0;
                if (drawWave(c, w, h, f0)) { removeCallbacks(this); if (on && dragAt < 0 && isShown()) postDelayed(this, 500); return; }
            }
            float ts = Math.max(10, h * 0.5f);
            p.setTextSize(ts);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            long at = dragAt >= 0 ? dragAt : now();
            String a = CarDeck.songTime(at), b = CarDeck.songTime(dur);
            float aw = p.measureText("00:00"), cy = h / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(textSoft);
            c.drawText(a, 0, cy + ts * 0.36f, p);
            c.drawText(b, w - p.measureText(b), cy + ts * 0.36f, p);
            x0 = aw + ts * 0.6f;
            x1 = w - aw - ts * 0.6f;
            if (x1 <= x0) return;
            float th = Math.max(3, h * 0.14f);
            r.set(x0, cy - th / 2, x1, cy + th / 2);
            p.setColor(CarTheme.withAlpha(textSoft, 0x55));
            c.drawRoundRect(r, th / 2, th / 2, p);
            float f = dur > 0 && at >= 0 ? Math.min(1f, at / (float) dur) : 0;
            float xk = x0 + (x1 - x0) * f;
            r.set(x0, cy - th / 2, xk, cy + th / 2);
            p.setColor(accent);
            c.drawRoundRect(r, th / 2, th / 2, p);
            if (dur > 0) {
                p.setColor(0xFFFFFFFF);
                c.drawCircle(xk, cy, th * 1.7f, p);
            }
            removeCallbacks(this);
            if (on && dragAt < 0 && isShown()) postDelayed(this, 500);
        }

        /**
         * The wave: bars across the card (their heights from the song's name: the same song, the same wave), the part
         * already played in the card's colour, the rest soft (⑲: yellow); the times at the ends under it.
         */
        private boolean drawWave(Canvas c, int w, int h, float f) {
            float ts = Math.max(9, Math.min(h * 0.2f, 14 * getResources().getDisplayMetrics().density));
            p.setTextSize(ts);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setStyle(Paint.Style.FILL);
            p.setColor(textSoft);
            String a = CarDeck.songTime(dragAt >= 0 ? dragAt : now()), b = CarDeck.songTime(dur);
            c.drawText(a, 0, h - ts * 0.25f, p);
            c.drawText(b, w - p.measureText(b), h - ts * 0.25f, p);
            float top = h * 0.04f, bottom = h - ts * 1.45f;
            if (bottom - top < 6) return false;
            x0 = 0;
            x1 = w;
            float bw = Math.max(2, Math.min(6 * getResources().getDisplayMetrics().density, w / 70f)), step = bw * 1.75f;
            int n = Math.max(8, (int) (w / step));
            int seed = title.getText().toString().hashCode();
            float mid = (top + bottom) / 2, half = (bottom - top) / 2;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(bw);
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < n; i++) {
                float x = (i + 0.5f) * (w / (float) n);
                float k = CarStyle.waveBar(seed, i, n);
                float hh = Math.max(bw * 0.6f, half * k);
                p.setColor(x / w <= f ? accent : waveRest != 0 ? waveRest : CarTheme.withAlpha(textSoft, 0x66));
                c.drawLine(x, mid - hh, x, mid + hh, p);
            }
            p.setStyle(Paint.Style.FILL);
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dur <= 0 || !allows(Music.C_SEEK)) {
                if (e.getAction() == MotionEvent.ACTION_UP) Toast.makeText(getContext(), "ఈ యాప్ పాటలో ముందుకు / వెనక్కి వెళ్లనివ్వదు", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (CarBox.moving()) { // (no fine finger work while he drives: ⏪10 / 10⏩, or his words)
                if (e.getAction() == MotionEvent.ACTION_UP) Toast.makeText(getContext(), few ? "పాటలో ముందుకు / వెనక్కి లాగడం కారు ఆగాక" : "నడుపుతుండగా ⏪10 / 10⏩ వాడండి (లాగడం కారు ఆగాక)", Toast.LENGTH_SHORT).show();
                if (dragAt >= 0) { dragAt = -1; invalidate(); } // (a drag the car's start cut short: the song's own place again)
                return true;
            }
            float f = Math.max(0, Math.min(1, (e.getX() - x0) / Math.max(1, x1 - x0)));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragAt = Math.round(dur * f);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    long to = Math.round(dur * f);
                    dragAt = -1;
                    pos = to; upd = android.os.SystemClock.elapsedRealtime(); // (shown there at once; the app's own numbers follow)
                    l.key("seek:" + to);
                    invalidate();
                    return true;
                default:
                    dragAt = -1;
                    invalidate();
                    return true;
            }
        }
    }
}
