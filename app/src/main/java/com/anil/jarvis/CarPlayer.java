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
    private final LinearLayout right, top, keys;
    private final TextView title, sub, app;
    private final Seek seek;
    private final CarArt.Icon[] keyViews;
    private static final String[] KEYS = {"shuffle", "back10", "prev", "toggle", "next", "fwd10", "repeat", "like", "queue", "volume"};
    private static final int[] KIND = {CarArt.Icon.SHUFFLE, CarArt.Icon.BACK10, CarArt.Icon.PREV, CarArt.Icon.PLAY, CarArt.Icon.NEXT, CarArt.Icon.FWD10,
            CarArt.Icon.REPEAT, CarArt.Icon.HEART, CarArt.Icon.QUEUE, CarArt.Icon.VOLUME};
    private int caps = -1, textMain = 0xFFFFFFFF, textSoft = 0xFFB9CCDA, accent = 0xFF5FF3FF;
    private int shuffle = -1, repeat = -1;
    private boolean liked, playing, hasSong, vinylOn;
    private Bitmap art;

    CarPlayer(Context c, Listener l) {
        super(c);
        this.l = l;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        artBox = new FrameLayout(c);
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
        artBox.setOnClickListener(v -> tap(l::art));
        addView(artBox, new LayoutParams(0, 0));

        right = new LinearLayout(c);
        right.setOrientation(VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        addView(right, new LayoutParams(0, -1, 1));
        top = new LinearLayout(c);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(c);
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
            if (k.equals("toggle")) ic.setRing(true);
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
        showArt();
    }

    /** 💿 the song's picture on a turning record (his switch, as on the song strip). */
    void setVinyl(boolean on, boolean lowMotion) {
        vinylOn = on;
        vinyl.setLowMotion(lowMotion);
        showArt();
    }

    private void showArt() {
        artImg.setVisibility(art != null && !vinylOn ? VISIBLE : GONE);
        vinyl.setVisibility(vinylOn ? VISIBLE : GONE);
        artIcon.setVisibility(art == null && !vinylOn ? VISIBLE : INVISIBLE);
    }

    /** The music app's numbers (Music.progress), or null. */
    void setProgress(long[] pr) {
        if (pr == null) { seek.set(-1, 0, 1, false, -1); vinyl.setTiming(-1, 0, 1, playing, -1); return; }
        seek.set(pr[0], pr[1], pr[2] / 1000f, pr[3] == 1, pr[4]);
        vinyl.setTiming(pr[0], pr[1], pr[2] / 1000f, pr[3] == 1, pr[4]);
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
            keyViews[i].setAlpha(ok ? 1f : 0.32f);
            boolean lit = k.equals("shuffle") && shuffle == 1 || k.equals("repeat") && repeat > 0 || k.equals("like") && liked;
            keyViews[i].setColour(lit ? 0xFFFFD166 : k.equals("toggle") ? accent : textMain);
        }
        seek.setAlpha(allows(Music.C_SEEK) ? 1f : 0.6f);
    }

    void setColours(int main, int soft, int acc, boolean light) {
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
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(CarTheme.withAlpha(acc, 0x22));
        ab.setCornerRadius(Ui.dp(getContext(), 10));
        artBox.setBackground(ab);
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

    private void sizes(int w, int h) {
        int inner = Math.max(1, h - getPaddingTop() - getPaddingBottom()), avail = Math.max(1, w - getPaddingLeft() - getPaddingRight());
        int a = Math.max(1, Math.min(Math.round(inner * 0.94f), Math.round(avail * 0.3f)));
        LayoutParams ap = new LayoutParams(a, a);
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
        seek.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.2f)));
        keys.setLayoutParams(new LayoutParams(-1, Math.round(inner * 0.36f)));
        for (int i = 0; i < keyViews.length; i++) keyViews[i].setFill(i == 3 ? 0.98f : 0.62f);
    }

    // ---------------------------------------------------------------- the place in the song

    /** The track with the song's place, its times at the ends; a finger (parked only) moves it: the app is asked on lifting. */
    private final class Seek extends View implements Runnable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private long pos = -1, upd, dur = -1, dragAt = -1;
        private float speed = 1;
        private boolean on;

        Seek(Context c) { super(c); }

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

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dur <= 0 || !allows(Music.C_SEEK)) {
                if (e.getAction() == MotionEvent.ACTION_UP) Toast.makeText(getContext(), "ఈ యాప్ పాటలో ముందుకు / వెనక్కి వెళ్లనివ్వదు", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (CarBox.moving()) { // (no fine finger work while he drives: ⏪10 / 10⏩, or his words)
                if (e.getAction() == MotionEvent.ACTION_UP) Toast.makeText(getContext(), "నడుపుతుండగా ⏪10 / 10⏩ వాడండి (లాగడం కారు ఆగాక)", Toast.LENGTH_SHORT).show();
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
