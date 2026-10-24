package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The home Jarvis's screen (a tablet in the hall, landscape): the new Jarvis (BodyView) on the left with what he says
 * in a bubble; on the right the clock with the part of the day, the Telugu date, the weather, what comes next, the
 * smart-home buttons (his Alexa links) and big buttons to talk. At night the screen dims and Jarvis rests (sleepy); it
 * may go dark and lights up again when someone says "Jarvis" or touches it.
 */
final class HomeScreen extends FrameLayout {
    interface Host {
        void talk();
        void openChat();
        void openSettings();
        /** అమ్మగారు's big buttons: tablet, ate, bible, songs, son, help. */
        void care(String what);
        /** Jarvis says this aloud (after a smart-home button). */
        void speak(String text);
        /** Jarvis asks this aloud and listens for her answer. */
        void ask(String text);
    }

    static final int NIGHT_FROM = 22, NIGHT_TO = 6;
    private static final String SP = "home_screen";
    private static final String[] DAYS = {"ఆదివారం", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం"};
    private static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు",
            "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    private final Activity act;
    private final Prefs prefs;
    private final Host host;
    final BodyView body;
    private final TextView bubble, clock, part, date, weather, next, net, batt;
    /** The bubble's box: a few lines tall; a long reply scrolls in it, following the word being spoken. */
    private final CapScroll bubbleBox;
    private String bubbleText = "";
    private final android.text.style.ForegroundColorSpan saidSpan = new android.text.style.ForegroundColorSpan(0xFFFFFFFF),
            nowSpan = new android.text.style.ForegroundColorSpan(0xFF10213A);
    private final android.text.style.BackgroundColorSpan nowBg = new android.text.style.BackgroundColorSpan(0xFFFFD166);
    private static final int BUBBLE_TEXT = 0xFFF4F8FC, BUBBLE_AHEAD = 0xFFAFC3DA;
    private final LinearLayout smart;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running, night, weatherBusy, nightSet;
    /** The last time someone was about or Jarvis was busy (the screen's brightness follows it). */
    private long activeAt = System.currentTimeMillis();
    private float lightNow = -2f;
    /** A warm glass over the screen at night (easier on the eyes). */
    private final View warm;
    /** "అబ్బాయి AC ఆఫ్ చేయమంటారా?" over everything (T2). */
    private final FrameLayout ask;
    /** The photo frame (family photos when no one has been about for a while, in the day). */
    private final HomeFrame frame;
    private long frameCountAt;
    private int frameCount;
    private long bubbleAt, weatherTry;
    private String lastWord, smartShown;
    private int lastStart = -1;
    /** The weather (and festival decorations) behind Jarvis. */
    private final WeatherSky sky;
    /** Jarvis's place on the home screen (he moves into the games panel while a game is open, and back). */
    private final FrameLayout stage;
    private final LayoutParams blp, bb;
    /** The games (made the first time they are opened). */
    private GamePanel games;
    private GamePanel.Outer gamesOuter;

    HomeScreen(Activity a, Prefs prefs, Host host) {
        super(a);
        this.act = a;
        this.prefs = prefs;
        this.host = host;
        setBackgroundColor(0xFF0E1A2C);
        setClickable(true); // (touches never reach the chat screen under it)
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(-1, -1));

        // ---- left: Jarvis
        stage = new FrameLayout(a);
        row.addView(stage, new LinearLayout.LayoutParams(0, -1, 58));
        sky = new WeatherSky(a); // (the weather behind Jarvis: clouds drift, rain falls, snow, sun or moon — and the day's decorations)
        stage.addView(sky, new LayoutParams(-1, -1));
        body = new BodyView(a);
        body.rig.setLook(prefs.bodyLook());
        body.rig.setSkin(prefs.bodySkin());
        body.setContentDescription("Jarvis: మాట్లాడటానికి నొక్కండి");
        body.setOnClickListener(v -> host.talk());
        body.setOnLongClickListener(v -> { body.rig.poke(); body.rig.tickle(); active(); return true; }); // (held: he giggles)
        blp = new LayoutParams(-1, -1);
        blp.topMargin = dp(84);
        stage.addView(body, blp);
        bubble = new TextView(a);
        bubble.setTextColor(BUBBLE_TEXT);
        bubble.setTextSize(23);
        bubble.setLineSpacing(0, 1.15f);
        bubble.setGravity(Gravity.CENTER_HORIZONTAL);
        bubble.setMaxWidth(dp(620));
        bubble.setPadding(dp(22), dp(12), dp(22), dp(12));
        bubbleBox = new CapScroll(a);
        bubbleBox.cap = Math.round(bubble.getLineHeight() * 4.3f) + dp(24); // about 4 lines show; the rest scrolls along
        bubbleBox.setVerticalScrollBarEnabled(false);
        bubbleBox.setBackground(round(0xFF1B3050, 0xFF2E4C74, 18));
        bubbleBox.addView(bubble, new LayoutParams(-2, -2));
        bubbleBox.setVisibility(GONE);
        bb = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bb.setMargins(dp(24), dp(18), dp(24), 0);
        stage.addView(bubbleBox, bb);

        // ---- right: the day — always all of it on the screen: when it doesn't fit (big font, the night "more" open)
        // everything shrinks together a little instead of scrolling, so the clock and "మాట్లాడు" are never cut (Anil, 24 Oct)
        FitBox sideFit = new FitBox(a);
        sideFit.setBackgroundColor(0xFF13233A);
        row.addView(sideFit, new LinearLayout.LayoutParams(0, -1, 42));
        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(26), dp(16), dp(26), dp(16));
        sideFit.addView(side, new LayoutParams(-1, -1));

        LinearLayout chips = new LinearLayout(a);
        chips.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // 🪴 Jarvis's garden: a plant a day, growing as she takes her tablets, meals and water (F7, Anil 24 Oct)
        garden = new GardenView(a);
        garden.setContentDescription("Jarvis తోట: ఈరోజు మొక్క ఎలా ఉందో వినడానికి నొక్కండి");
        garden.setOnClickListener(v -> gardenSay());
        LinearLayout.LayoutParams gdl = new LinearLayout.LayoutParams(0, dp(52), 1);
        gdl.rightMargin = dp(10);
        chips.addView(garden, gdl);
        net = chip(a);
        batt = chip(a);
        chips.addView(net);
        LinearLayout.LayoutParams cg = new LinearLayout.LayoutParams(-2, -2);
        cg.leftMargin = dp(8);
        chips.addView(batt, cg);
        side.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout time = new LinearLayout(a);
        time.setGravity(Gravity.BOTTOM);
        clock = text(a, "", 72, 0xFFFFFFFF, true);
        clock.setIncludeFontPadding(false);
        part = text(a, "", 24, 0xFFF2B544, true);
        part.setPadding(dp(12), 0, 0, dp(12));
        time.addView(clock);
        time.addView(part);
        side.addView(time);
        date = text(a, "", 23, 0xFFB4C5D8, false);
        side.addView(date);
        weather = text(a, "", 21, 0xFFDCE7F3, false);
        weather.setPadding(0, dp(6), 0, 0);
        side.addView(weather);

        View line = new View(a);
        line.setBackgroundColor(0xFF2A4468);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(1));
        ll.setMargins(0, dp(12), 0, dp(10));
        side.addView(line, ll);
        side.addView(text(a, "తర్వాత", 15, 0xFF8FB4D9, true));
        next = text(a, "", 21, 0xFFF4F8FC, false);
        next.setMaxLines(2);
        next.setEllipsize(TextUtils.TruncateAt.END);
        side.addView(next);

        // అమ్మగారు's big buttons (🆘 సహాయం has its own place at the bottom, next to మాట్లాడు)
        String[][] care = {{"tablet", "💊 వేసుకున్నాను"}, {"ate", "🍽️ తిన్నాను"}, {"bible", "🙏 వాక్యం"},
                {"read", "📖 బైబిల్ చదువు"}, {"songs", "🎵 పాటలు"}, {"game", "🎲 ఆట"},
                {"photos", "🖼️ ఫోటోలు"}, {"shop", "🛒 సరుకులు"}, {"readpaper", "📄 చదివి చెప్పు"},
                {"son", "🎤 అబ్బాయికి చెప్పు"}, {"video", "📹 వీడియో కాల్"}};
        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(12);
        side.addView(grid, gl);
        LinearLayout gr = null;
        for (int i = 0; i < care.length; i++) {
            if (i % 3 == 0) {
                gr = new LinearLayout(a);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? 0 : dp(8);
                grid.addView(gr, rl);
            }
            final String what = care[i][0];
            TextView b = button(a, care[i][1], "video".equals(what) ? 0xFF1E5A4A : 0xFF1F3B5C, 0xFFFFFFFF, 16);
            fitText(b, 16); // (a long label shrinks a little, never cut)
            if ("shop".equals(what)) shopBtn = b;
            b.setOnClickListener(v -> {
                if ("photos".equals(what)) { showFrame(true); return; }
                if ("shop".equals(what)) { showShop(); return; }
                if ("ate".equals(what) || "tablet".equals(what)) { body.rig.poke(); body.rig.cheer(); gardenSoon(); } // (he claps for her)
                host.care(what);
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(54), 1);
            if (i % 3 != 0) bl.leftMargin = dp(8);
            gr.addView(b, bl);
        }
        // at her bedtime only the few she needs show; the rest wait behind "⋯ మరిన్ని" (T4)
        nightRow = new LinearLayout(a);
        moon = button(a, "🌙 పడుకుంటాను", 0xFF2A2F6B, 0xFFFFFFFF, 19);
        fitText(moon, 19);
        moon.setOnClickListener(v -> { body.rig.poke(); host.care("night"); }); // (F2: the good-night scene)
        nightRow.addView(moon, new LinearLayout.LayoutParams(0, dp(54), 1.4f));
        more = button(a, "⋯ మరిన్ని", 0xFF1B3050, 0xFFDCE7F3, 17);
        fitText(more, 17);
        more.setOnClickListener(v -> { moreUntil = moreOpen() ? 0 : System.currentTimeMillis() + 2 * 60_000L; nightLayout(); });
        LinearLayout.LayoutParams mol = new LinearLayout.LayoutParams(0, dp(54), 1);
        mol.leftMargin = dp(8);
        nightRow.addView(more, mol);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(12);
        nightRow.setVisibility(GONE);
        side.addView(nightRow, ml);

        smart = new LinearLayout(a);
        smart.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(12);
        side.addView(smart, sl);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, 0, 1);
        gap.topMargin = dp(10);
        side.addView(new View(a), gap); // (the buttons stay at the bottom)

        LinearLayout buttons = new LinearLayout(a);
        TextView talk = button(a, "🎤  మాట్లాడు", 0xFFF2B544, 0xFF1A1206, 22);
        fitText(talk, 22);
        talk.setOnClickListener(v -> host.talk());
        buttons.addView(talk, new LinearLayout.LayoutParams(0, dp(64), 1.5f));
        TextView help = button(a, "🆘 సహాయం", 0xFF8B1E1E, 0xFFFFFFFF, 20);
        fitText(help, 20);
        help.setOnClickListener(v -> host.care("help"));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(64), 1);
        hl.leftMargin = dp(10);
        buttons.addView(help, hl);
        TextView chat = button(a, "💬", 0xFF1B3050, 0xFFDCE7F3, 24);
        chat.setContentDescription("చాట్ తెరువు");
        chat.setOnClickListener(v -> host.openChat());
        LinearLayout.LayoutParams sq = new LinearLayout.LayoutParams(dp(64), dp(64));
        sq.leftMargin = dp(10);
        buttons.addView(chat, sq);
        TextView set = button(a, "⚙️", 0xFF1B3050, 0xFFDCE7F3, 24);
        set.setContentDescription("సెట్టింగ్స్");
        // T6 + PIN: a stray tap never opens Settings (his keys are there): the PIN when he set one, else a long press
        set.setOnClickListener(v -> {
            if (TabletLock.has(getContext())) askPin();
            else show("⚙️ సెట్టింగ్స్ కోసం ఈ బటన్‌ని నొక్కి పట్టుకోండి");
        });
        set.setOnLongClickListener(v -> {
            if (TabletLock.has(getContext())) askPin(); else host.openSettings();
            return true;
        });
        LinearLayout.LayoutParams sq2 = new LinearLayout.LayoutParams(dp(64), dp(64));
        sq2.leftMargin = dp(10);
        buttons.addView(set, sq2);
        side.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        // the photo frame over everything (hidden), then the warm night glass (never takes a touch)
        frame = new HomeFrame(a);
        frame.onTap = () -> { hideFrame(); active(); };
        frame.setVisibility(GONE);
        addView(frame, new LayoutParams(-1, -1));
        warm = new View(a);
        warm.setBackgroundColor(0x30FF8A1E);
        warm.setVisibility(GONE);
        addView(warm, new LayoutParams(-1, -1));
        ask = new FrameLayout(a);
        ask.setBackgroundColor(0xB0050B14);
        ask.setClickable(true); // (nothing under it is pressed while it asks)
        ask.setVisibility(GONE);
        addView(ask, new LayoutParams(-1, -1));
        shopPanel = new FrameLayout(a);
        shopPanel.setBackgroundColor(0xC8050B14);
        shopPanel.setClickable(true);
        shopPanel.setVisibility(GONE);
        addView(shopPanel, new LayoutParams(-1, -1));
        body.rig.setIdlePlays(true); // (when no one talks for a while he plays by himself: a kitten, a ball, bubbles…)
    }

    // ================================================================ the Settings PIN (TabletLock)
    private void askPin() {
        PinPad.check(act, "సెట్టింగ్స్ PIN", ok -> {
            if (!ok) return;
            TabletLock.opened();
            host.openSettings();
        });
    }

    // ================================================================ "⋯ మరిన్ని" at her bedtime (T4)
    private LinearLayout grid, nightRow;
    private TextView more, moon, shopBtn;
    private long moreUntil;
    /** After the good-night scene: until the morning the screen is her bedtime screen and dims sooner. */
    private long sleepUntil;

    private boolean moreOpen() { return System.currentTimeMillis() < moreUntil; }

    /** Her bedtime (the time set from his phone, 21:30 at first) until 6 in the morning. */
    private boolean bedtime() {
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), bed = 21 * 60 + 30;
        try {
            String[] p = HomeCare.bedTime(getContext()).trim().split(":");
            bed = Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception ignored) {}
        int wake = NIGHT_TO * 60;
        return bed > wake ? now >= bed || now < wake : now >= bed && now < wake;
    }

    /** At bedtime: clock, the AC cards, 🌙, సహాయం and మాట్లాడు only; "⋯ మరిన్ని" shows the rest for two minutes. */
    private void nightLayout() {
        boolean sleeping = System.currentTimeMillis() < sleepUntil, bed = bedtime() || sleeping, open = moreOpen();
        int g = !bed || open ? VISIBLE : GONE;
        if (grid.getVisibility() != g) grid.setVisibility(g);
        int m = bed ? VISIBLE : GONE;
        if (nightRow.getVisibility() != m) nightRow.setVisibility(m);
        int mn = bed && !sleeping ? VISIBLE : GONE; // (once she said good night: no second AC question by a stray tap)
        if (moon.getVisibility() != mn) moon.setVisibility(mn);
        more.setText(open ? "⌃ తక్కువ" : "⋯ మరిన్ని");
    }

    /** 🌙 The good-night scene ran (HomeNight): Jarvis lies down under his blanket, the bedtime screen until the morning. */
    void goodNight() {
        Calendar k = Calendar.getInstance();
        if (k.get(Calendar.HOUR_OF_DAY) >= NIGHT_TO) k.add(Calendar.DAY_OF_MONTH, 1);
        k.set(Calendar.HOUR_OF_DAY, NIGHT_TO);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        sleepUntil = k.getTimeInMillis();
        moreUntil = 0;
        hideFrame();
        body.rig.play("snore"); // (after his good-night words: he snores under his blanket)
        nightLayout();
        activeAt = System.currentTimeMillis() - 60_000L; // (the screen goes soft a little after he has said it)
        applyLight();
    }

    // ================================================================ games

    /** The games panel over the home screen, Jarvis moved into it; id: a game to go to (null: the list). */
    void openGames(GamePanel.Outer outer, String id, boolean quiet) {
        gamesOuter = outer;
        if (games == null) {
            games = new GamePanel(act, new GamePanel.Outer() {
                @Override public void gameSay(String text, String feeling) { if (gamesOuter != null) gamesOuter.gameSay(text, feeling); }
                @Override public void talkGame(String gid) { if (gamesOuter != null) gamesOuter.talkGame(gid); }
                @Override public void gamesClosed() { closeGames(); }
                @Override public BodyRig rig() { return body.rig; }
                @Override public void help() { host.care("help"); }
            });
            addView(games, 1, new LayoutParams(-1, -1)); // (over the home screen; under the photo frame and the night glass)
        }
        hideFrame();
        if (games.getVisibility() != VISIBLE || body.getParent() != games.jarvisSlot) {
            move(body, games.jarvisSlot, new LayoutParams(-1, -1));
            LayoutParams top = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            top.setMargins(dp(4), dp(6), dp(4), 0);
            move(bubbleBox, games.jarvisSlot, top);
        }
        games.setVisibility(VISIBLE);
        sky.setVisibility(INVISIBLE); // (hidden under the games: it stops drawing)
        GamePanel.shown = games;
        games.touched();
        if (id == null) games.showPicker(quiet);
        else games.choose(id);
        active();
    }

    /** Jarvis back to his place on the home screen. */
    private void closeGames() {
        if (games == null) return;
        games.setVisibility(GONE);
        sky.setVisibility(VISIBLE);
        if (body.getParent() != stage) move(body, stage, blp);
        if (bubbleBox.getParent() != stage) move(bubbleBox, stage, bb);
    }

    boolean gamesOpen() { return games != null && games.getVisibility() == VISIBLE; }
    GamePanel gamesPanel() { return games; }

    private static void move(View v, android.view.ViewGroup to, LayoutParams lp) {
        android.view.ViewParent p = v.getParent();
        if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(v);
        to.addView(v, lp);
    }

    // ================================================================ what Jarvis does (from MainActivity)
    void setState(int s) {
        if (s != BodyRig.IDLE) body.rig.poke();
        body.rig.setMode(s);
        if (s != BodyRig.IDLE) active();
        if (s == BodyRig.LISTENING) show(LISTENING_TEXT);
        else if (s == BodyRig.THINKING) show(THINKING_TEXT);
        else if (s != BodyRig.SPEAKING) {
            settleBubble();
            // the mic is off / nothing more is coming: "వింటున్నాను…" / "ఆలోచిస్తున్నాను…" go at once (his words stay to read)
            if (LISTENING_TEXT.equals(bubbleText) || THINKING_TEXT.equals(bubbleText)) { bubbleBox.setVisibility(GONE); bubbleText = ""; }
        }
    }

    static final String LISTENING_TEXT = "వింటున్నాను…", THINKING_TEXT = "ఆలోచిస్తున్నాను…";

    /** He finished saying it: the whole text bright again, no mark (the bubble stays a while to read). */
    private void settleBubble() {
        CharSequence cs = bubble.getText();
        if (cs instanceof android.text.Spannable) {
            android.text.Spannable sp = (android.text.Spannable) cs;
            sp.removeSpan(saidSpan);
            sp.removeSpan(nowSpan);
            sp.removeSpan(nowBg);
        }
        bubble.setTextColor(BUBBLE_TEXT);
        lastStart = -1;
    }

    /** What he says (kept on the screen ~30 s). */
    void say(String text) { if (text != null && !text.trim().isEmpty()) { active(); show(text.trim()); } }

    void setMic(float l) { body.rig.setMic(l); }
    void setVoice(float l) { body.rig.setVoice(l); }
    void setFeeling(String f) { body.rig.setFeeling(f); }
    void look(boolean present, float x, float y) { body.rig.look(present, x, y); }
    void greet() { body.rig.greet(); }

    /** The word being spoken now (start..end inside the text); the same word again is ignored. */
    void word(String text, int start, int end) {
        if (text == null || start < 0 || end > text.length() || start >= end) return;
        if (start == lastStart && text.equals(lastWord)) return;
        lastWord = text;
        lastStart = start;
        body.rig.word(text.substring(start, end));
        follow(text, start, end);
    }

    private void show(String s) {
        bubbleText = s;
        bubble.setTextColor(BUBBLE_TEXT);
        bubble.setText(s, TextView.BufferType.SPANNABLE);
        bubbleBox.scrollTo(0, 0);
        bubbleBox.setVisibility(VISIBLE);
        bubbleAt = System.currentTimeMillis();
    }

    /**
     * The bubble follows his voice: the words said so far bright, the word now on a yellow mark, the rest a little
     * dimmer; a long text scrolls so the line being said stays in sight (one line above it shows too).
     */
    private void follow(String text, int start, int end) {
        try {
            if (!text.equals(bubbleText)) show(text); // (the words he is saying now: the bubble shows those)
            CharSequence cs = bubble.getText();
            if (!(cs instanceof android.text.Spannable)) { bubble.setText(bubbleText, TextView.BufferType.SPANNABLE); cs = bubble.getText(); }
            android.text.Spannable sp = (android.text.Spannable) cs;
            if (end > sp.length()) return;
            bubble.setTextColor(BUBBLE_AHEAD);
            sp.removeSpan(saidSpan);
            sp.removeSpan(nowSpan);
            sp.removeSpan(nowBg);
            if (start > 0) sp.setSpan(saidSpan, 0, start, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(nowBg, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(nowSpan, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bubbleAt = System.currentTimeMillis();
            android.text.Layout lay = bubble.getLayout();
            if (lay == null) return;
            int line = lay.getLineForOffset(start);
            int target = Math.max(0, bubble.getPaddingTop() + lay.getLineTop(line) - bubble.getLineHeight());
            if (Math.abs(bubbleBox.getScrollY() - target) > 4) bubbleBox.smoothScrollTo(0, target);
        } catch (Exception ignored) {
            // (a text that changed under the word: the next word puts it right)
        }
    }

    /** A ScrollView no taller than cap (the bubble's few lines). */
    private static final class CapScroll extends android.widget.ScrollView {
        int cap;
        CapScroll(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            if (cap > 0) {
                int max = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? cap : Math.min(cap, MeasureSpec.getSize(h));
                h = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
            }
            super.onMeasure(w, h);
        }
    }

    private long touchedAt;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        long now = System.currentTimeMillis();
        if (now - touchedAt > 60_000L) { touchedAt = now; HomeCare.life(getContext()); } // someone is about
        if (night) HomeEyes.nightStir(getContext(), now); // (up at night: a soft light and "walk carefully")
        if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            if (HomeAlarm.ringing()) HomeAlarm.stop(); // (the help alarm: any touch stops it)
            body.rig.poke(); // (someone is here: a play he is doing by himself ends)
            active();
        }
        return super.dispatchTouchEvent(e);
    }

    /** The home screen never goes dark (his wish): it dims when no one is about, and Jarvis stays in sight. */
    boolean keepOn() { return true; }

    private long lampUntil;

    /** At night: the screen at its night brightness for a while (someone got up), then dim again. */
    void nightLamp(long ms) {
        lampUntil = System.currentTimeMillis() + ms;
        applyLight();
    }

    /** Someone is about / Jarvis is busy: the screen comes up to its brightness for the hour. */
    void active() {
        activeAt = System.currentTimeMillis();
        if (frame.getVisibility() == VISIBLE && body.rig.mode() != BodyRig.IDLE) hideFrame();
        applyLight();
    }

    /**
     * The screen's brightness: day, someone about or Jarvis busy → the tablet's own brightness; day, no one for 5 minutes
     * → dimmed; night, someone about / Jarvis busy / the night lamp → soft (enough to see, easy on the eyes); night,
     * no one for 90 s → very low, Jarvis still in sight. A warm glass is over the screen at night.
     */
    private void applyLight() {
        long now = System.currentTimeMillis();
        boolean dark = night || now < sleepUntil; // (after her good night, also before 10 pm: the night screen)
        boolean busy = body.rig.mode() != BodyRig.IDLE, recent = now - activeAt < (dark ? 90_000L : 5 * 60_000L);
        float level = dark ? (busy || recent || now < lampUntil ? 0.16f : 0.03f)
                : (busy || recent || frame.getVisibility() == VISIBLE ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : 0.30f);
        warm.setVisibility(dark ? VISIBLE : GONE);
        try {
            act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (level == lightNow) return;
            lightNow = level;
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            lp.screenBrightness = level;
            act.getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    // ---- the photo frame
    private void showFrame(boolean asked) {
        if (night && !asked) return;
        if (asked ? HomeFrame.count(getContext()) == 0 : frameCount == 0) {
            if (asked) say(HomeFrame.status(getContext()));
            return;
        }
        frame.setVisibility(VISIBLE);
        frame.start();
        stage.setVisibility(INVISIBLE); // (the sky and Jarvis stop drawing under the photos)
        applyLight();
    }

    private void hideFrame() {
        if (frame.getVisibility() != VISIBLE) return;
        frame.stop();
        frame.setVisibility(GONE);
        stage.setVisibility(VISIBLE);
        applyLight();
    }

    /** No one about for 5 minutes in the day, Jarvis quiet: the family photos (when there are any). */
    private void frameCheck() {
        long now = System.currentTimeMillis();
        if (night || gamesOpen() || body.rig.mode() != BodyRig.IDLE || now - activeAt < 5 * 60_000L || frame.getVisibility() == VISIBLE
                || !HomeCare.sp(getContext()).getBoolean("frame_on", true)) return;
        if (now - frameCountAt > 5 * 60_000L) { // (the gallery is counted off the main thread; used from the next check)
            frameCountAt = now;
            final Context app = getContext().getApplicationContext();
            new Thread(() -> { int n = HomeFrame.count(app); post(() -> frameCount = n); }, "frame-count").start();
        }
        if (frameCount > 0) showFrame(false);
    }

    // ================================================================ running
    void start() {
        if (running) return;
        running = true;
        shown = this;
        refreshSmart();
        shopCount();
        gardenRefresh();
        main.post(tick);
        main.post(quickTick);
    }

    void stop() {
        running = false;
        if (shown == this) shown = null;
        main.removeCallbacks(tick);
        main.removeCallbacks(quickTick);
        hideFrame();
    }

    /** The home screen on show now (main thread), for news from elsewhere (his phone: coming / gone). */
    private static volatile HomeScreen shown;

    /** Any thread. Her son: coming (minutes 0 = arriving now; else in that many minutes) → Jarvis glances at the door; gone → bye. */
    static void son(boolean coming, int minutes) {
        new Handler(Looper.getMainLooper()).post(() -> {
            HomeScreen h = shown;
            if (h == null) return;
            if (coming) {
                h.body.rig.waitDoor(true);
                h.doorUntil = System.currentTimeMillis() + (minutes > 0 ? minutes + 15 : 30) * 60_000L;
            } else {
                h.doorUntil = 0;
                h.body.rig.waitDoor(false);
                h.body.rig.poke();
                h.body.rig.waveBye();
            }
        });
    }

    private long doorUntil;

    /** Every 2 s: dance while her songs play; the door watch ends by itself. */
    private boolean dancing;
    private final Runnable quickTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                boolean d = HomeSongs.playing();
                if (d != dancing) { dancing = d; body.rig.setDance(d); if (d) body.rig.poke(); }
                if (doorUntil > 0 && System.currentTimeMillis() > doorUntil) { doorUntil = 0; body.rig.waitDoor(false); }
            } catch (Exception ignored) {}
            main.postDelayed(this, 2000);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { update(); } catch (Exception ignored) {}
            main.postDelayed(this, 15_000);
        }
    };

    private void update() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        clock.setText(String.format(Locale.US, "%d:%02d", h12, m));
        part.setText(partOfDay(h));
        date.setText(DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]);
        setNight(h >= NIGHT_FROM || h < NIGHT_TO);
        nightLayout();
        occasion(c);
        mealTime();
        sky.setBirds(h >= 6 && h < 10 && !night); // (F6: birds cross the morning sky)
        morning(c);
        if (System.currentTimeMillis() - gardenAt > 60_000L) gardenRefresh();
        if (night) hideFrame(); else frameCheck();
        applyLight();
        if (bubbleBox.getVisibility() == VISIBLE && System.currentTimeMillis() - bubbleAt > 30_000
                && body.rig.mode() != BodyRig.SPEAKING && body.rig.mode() != BodyRig.LISTENING) bubbleBox.setVisibility(GONE);
        next.setText(nextThing(getContext()));
        boolean online = online(getContext());
        net.setText(online ? "🟢 ఆన్‌లైన్" : "📴 ఆఫ్‌లైన్");
        batt.setText(battery(getContext()));
        showWeather();
        fetchWeather();
        if (!smartKey().equals(smartShown)) refreshSmart();
        if (gamesOpen()) {
            games.farTick();
            if (games.idleTooLong() && body.rig.mode() == BodyRig.IDLE) games.close(false); // (the game stays saved)
        }
    }

    static String partOfDay(int h) {
        if (h >= 4 && h < 12) return "ఉదయం";
        if (h >= 12 && h < 16) return "మధ్యాహ్నం";
        if (h >= 16 && h < 19) return "సాయంత్రం";
        return "రాత్రి";
    }

    private void setNight(boolean n) {
        if (nightSet && n == night) return;
        nightSet = true;
        night = n;
        body.setSlow(n);
        body.rig.setBase(n ? "sleepy" : "smile");
        body.rig.setNightPlays(n); // (night: only calm plays — a firefly, a sleeping kitten, a dream)
        sky.setDim(n);
        garden.setDim(n);
        lightNow = -2f; // (the brightness is set again for the new part of the day)
        applyLight();
    }

    // ---- what comes next: the nearest reminder
    static String nextThing(Context c) {
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
        String what = null;
        try {
            for (JSONObject r : Store.get(c).reminders()) {
                if (r.optBoolean("done")) continue;
                long at = r.optLong("at");
                if (at > now && at < best) { best = at; what = r.optString("text"); }
            }
        } catch (Exception ignored) {}
        try { // her tablets
            Object[] d = Medicine.nextDose(c);
            if (d != null && (Long) d[1] > now && (Long) d[1] < best) { best = (Long) d[1]; what = "💊 " + d[0]; }
        } catch (Exception ignored) {}
        for (String[] m : HomeCare.MEALS) { // her meals still to come today
            long at = todayAt(HomeCare.time(c, m[0], m[2]));
            if (at > now && at < best && !HomeCare.ate(c, m[0])) { best = at; what = "🍽️ " + m[1]; }
        }
        if (what == null) return "ఇప్పుడు ఏమీ లేవు";
        return what + " · " + when(best, now);
    }

    /** Today at HH:mm in millis (-1 when it can't be read). */
    private static long todayAt(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            Calendar k = Calendar.getInstance();
            k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
            k.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
            return k.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    static String when(long at, long now) {
        Calendar a = Calendar.getInstance(), n = Calendar.getInstance();
        a.setTimeInMillis(at);
        n.setTimeInMillis(now);
        int h = a.get(Calendar.HOUR_OF_DAY), h12 = h % 12 == 0 ? 12 : h % 12;
        String t = partOfDay(h) + " " + String.format(Locale.US, "%d:%02d", h12, a.get(Calendar.MINUTE));
        int days = (int) ((startOfDay(a) - startOfDay(n)) / 86_400_000L);
        if (days == 0) return t;
        if (days == 1) return "రేపు " + t;
        return a.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[a.get(Calendar.MONTH)] + " " + t;
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    static String battery(Context c) {
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "";
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            return (plugged ? "⚡ " : "🔋 ") + (pct < 0 ? "" : pct + "%");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- the weather (Open-Meteo, every ~30 minutes; the last one is kept for offline)
    private void showWeather() {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long at = sp.getLong("w_at", 0);
        if (at == 0) { weather.setText(""); return; }
        boolean stale = System.currentTimeMillis() - at > 3 * 3600_000L;
        String old = stale ? " (పాతది)" : "";
        int code = sp.getInt("w_code", -1), dayFlag = sp.getInt("w_day", -1);
        float temp = sp.getFloat("w_temp", Float.NaN);
        weather.setText(Math.round(sp.getFloat("w_temp", 0)) + "° · " + skyTe(code) + old);
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean fresh = System.currentTimeMillis() - at < 45 * 60_000L;
        boolean day = fresh && dayFlag >= 0 ? dayFlag == 1 : hr >= 6 && hr < 18;
        if (stale) { code = -1; temp = Float.NaN; } // (an old reading: only the calm sky, no rain that stopped hours ago)
        sky.set(code, temp, day);
        // ☔ what Jarvis wears: an umbrella in the rain, a sweater when it is cold, a hand fan on a hot day
        String outfit = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95 ? "umbrella"
                : !Float.isNaN(temp) && temp < 20 ? "sweater" : !Float.isNaN(temp) && temp > 34 && day ? "fan" : "none";
        if (!outfit.equals(outfitNow)) { outfitNow = outfit; body.rig.setOutfit(outfit); }
    }

    private String outfitNow = "";

    // ================================================================ 🎄 festivals, 🎂 birthdays, 🍽️ meals
    private String occasionNow = "";

    /** Today's occasion: a birthday first, then New Year, Sankranti, Easter, the Christmas month. */
    private void occasion(Calendar c) {
        String bday = birthdaysToday(getContext(), c);
        int mo = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
        String o = !bday.isEmpty() ? "birthday"
                : mo == Calendar.JANUARY && d == 1 ? "newyear"
                : mo == Calendar.JANUARY && d >= 13 && d <= 15 ? "sankranti"
                : isEaster(c) ? "easter"
                : mo == Calendar.DECEMBER ? "christmas" : "none";
        if (!o.equals(occasionNow)) {
            occasionNow = o;
            body.rig.setOccasion(o);
            sky.setOccasion(o);
        }
        // 🎂 once on the day, in the morning or later (never at night): the cake and a wish, aloud
        if (!bday.isEmpty() && c.get(Calendar.HOUR_OF_DAY) >= 8 && !bedtime() && !night && !HomeCare.resting(getContext())
                && body.rig.mode() == BodyRig.IDLE) {
            android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
            String key = c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
            if (!key.equals(sp.getString("bday_said", ""))) {
                sp.edit().putString("bday_said", key).apply();
                body.rig.poke();
                body.rig.play("cake");
                host.speak("ఈ రోజు " + bday + " పుట్టినరోజు! 🎂 పుట్టినరోజు శుభాకాంక్షలు!");
            }
        }
    }

    /** Names whose birthday is today, from his phone's "🎂 పుట్టినరోజులు" ("అమ్మగారు = 12-03, అనిల్ = 05-07"). */
    static String birthdaysToday(Context ctx, Calendar c) {
        String all = HomeCare.sp(ctx).getString("birthdays", "");
        StringBuilder out = new StringBuilder();
        for (String item : all.split("[,;\\n]")) {
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String name = item.substring(0, eq).trim();
            String[] dm = item.substring(eq + 1).trim().split("[-/.\\s]+");
            try {
                if (dm.length >= 2 && Integer.parseInt(dm[0].trim()) == c.get(Calendar.DAY_OF_MONTH)
                        && Integer.parseInt(dm[1].trim()) == c.get(Calendar.MONTH) + 1 && !name.isEmpty()) {
                    if (out.length() > 0) out.append(", ");
                    out.append(name);
                }
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    /** Easter Sunday (HomeCare's date). */
    static boolean isEaster(Calendar c) {
        int[] e = HomeCare.easter(c.get(Calendar.YEAR));
        return c.get(Calendar.MONTH) + 1 == e[0] && c.get(Calendar.DAY_OF_MONTH) == e[1];
    }

    /** 🍽️ At her meal times (until 20 minutes after) Jarvis eats too — once per meal, when he is free. */
    private void mealTime() {
        if (bedtime() || body.rig.mode() != BodyRig.IDLE || body.rig.playing() != null) return;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        Calendar c = Calendar.getInstance();
        for (String[] m : HomeCare.MEALS) {
            long at = todayAt(HomeCare.time(getContext(), m[0], m[2]));
            if (at < 0 || now < at || now > at + 20 * 60_000L) continue;
            String key = m[0] + c.get(Calendar.DAY_OF_YEAR);
            if (key.equals(sp.getString("meal_ate", ""))) continue;
            sp.edit().putString("meal_ate", key).apply();
            body.rig.play("eat");
            return;
        }
    }

    /** The sky in Telugu only (T3: no "clear" / "rain" in English on her screen). */
    static String skyTe(int code) {
        if (code == 0) return "ఆకాశం నిర్మలం";
        String s = HudDashboard.sky(code);
        int dot = s.indexOf(" · ");
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** The first number of a daily array (NaN when missing). */
    private static double arr(JSONObject d, String k) {
        org.json.JSONArray a = d.optJSONArray(k);
        return a == null || a.length() == 0 || a.isNull(0) ? Double.NaN : a.optDouble(0, Double.NaN);
    }

    private void fetchWeather() { fetchWeather(false); }

    /** now: the morning greeting waits for today's forecast (asked at once, at most every 2 minutes). */
    private void fetchWeather(boolean now0) {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (weatherBusy || !now0 && (now - sp.getLong("w_at", 0) < 30 * 60_000L || now - weatherTry < 10 * 60_000L)
                || now0 && now - weatherTry < 2 * 60_000L) return;
        weatherBusy = true;
        weatherTry = now;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                Location l = Devices.freshFix(app); // (a SIM-less tablet may have no last place yet: a fix is asked for)
                if (l == null) return;
                JSONObject all = Http.get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day"
                                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=1&timezone=auto",
                        l.getLatitude(), l.getLongitude()));
                JSONObject cur = all.optJSONObject("current");
                if (cur == null || !cur.has("temperature_2m")) return;
                android.content.SharedPreferences.Editor e = app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                        .putFloat("w_temp", (float) cur.optDouble("temperature_2m"))
                        .putInt("w_code", cur.optInt("weather_code", -1)).putInt("w_day", cur.optInt("is_day", -1))
                        .putLong("w_at", System.currentTimeMillis());
                JSONObject d = all.optJSONObject("daily"); // (today's: for the morning greeting, F6)
                if (d != null && d.optJSONArray("time") != null && d.optJSONArray("time").length() > 0) {
                    e.putString("d_day", d.optJSONArray("time").optString(0))
                            .putFloat("d_max", (float) arr(d, "temperature_2m_max")).putFloat("d_min", (float) arr(d, "temperature_2m_min"))
                            .putInt("d_rain", (int) Math.round(arr(d, "precipitation_probability_max"))).putInt("d_code", (int) Math.round(arr(d, "weather_code")));
                }
                e.apply();
                main.post(() -> { if (running) showWeather(); });
            } catch (Throwable ignored) {
                // offline / no location: the last weather stays
            } finally {
                weatherBusy = false;
            }
        }, "home-weather").start();
    }

    // ---- smart-home buttons: his saved Alexa links ("ac on = https://…")
    /** The smart-home links + the room (the buttons follow both). */
    private String smartKey() { return prefs.smartUrls() + "|" + HomeEyes.room(getContext()); }

    /**
     * The smart-home cards (T1, Anil 24 Oct): his Alexa links grouped per device — "amma ac on" + "amma ac off" make one
     * card "నా AC" with a little AC picture and big ఆన్ / ఆఫ్; a line without on / off (a scene) gets one button. At most
     * four cards, two in a row. By the room he set: the hall shows the TV's first; her bedroom the AC / fan / light ones and
     * no TV (the TV is in the hall).
     */
    private void refreshSmart() {
        smartShown = smartKey();
        smart.removeAllViews();
        boolean hall = HomeEyes.inHall(getContext());
        java.util.LinkedHashMap<String, String[]> devs = new java.util.LinkedHashMap<>(); // base -> {on, off, plain}
        for (String line : prefs.smartUrls().split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim(), url = line.substring(eq + 1).trim(), low = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (low.startsWith("charger")) continue; // (the battery-care links work by themselves)
            if (low.startsWith("tv quiet") || low.startsWith("tv back")) continue; // (Jarvis's own TV-volume links)
            if (name.isEmpty() || !url.startsWith("http")) continue;
            int kind = low.endsWith(" on") ? 0 : low.endsWith(" off") ? 1 : 2;
            String base = kind == 0 ? low.substring(0, low.length() - 3).trim() : kind == 1 ? low.substring(0, low.length() - 4).trim() : low;
            if (base.isEmpty()) continue;
            String[] d = devs.get(base);
            if (d == null) devs.put(base, d = new String[3]);
            if (d[kind] == null) d[kind] = url;
        }
        List<String> first = new ArrayList<>(), rest = new ArrayList<>();
        for (String base : devs.keySet()) {
            boolean tv = base.matches(".*\\btv\\b.*|.*టీవీ.*");
            if (!hall && tv) continue;
            boolean cool = base.matches(".*\\b(ac|fan|light|lamp)\\b.*|.*(ఫ్యాన్|లైట్).*");
            if (hall ? tv : cool) first.add(base); else rest.add(base);
        }
        List<String> show = new ArrayList<>(first);
        show.addAll(rest);
        while (show.size() > 4) show.remove(show.size() - 1);
        LinearLayout r = null;
        for (int i = 0; i < show.size(); i++) {
            if (i % 2 == 0) {
                r = new LinearLayout(getContext());
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? 0 : dp(8);
                smart.addView(r, rl);
            }
            String base = show.get(i);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) cl.leftMargin = dp(8);
            r.addView(card(base, devs.get(base)), cl);
        }
        if (show.size() % 2 == 1 && r != null) r.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
    }

    /** One device: its picture and name, then ఆన్ / ఆఫ్ (or one button for a scene), then what was done last. */
    private View card(String base, String[] urls) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(round(0xFF1B3050, 0xFF2A4468, 18));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        DeviceIcon icon = new DeviceIcon(c, DeviceIcon.kind(base));
        top.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(40)));
        final String name = deviceName(base);
        TextView title = text(c, name, 19, 0xFFF4F8FC, true);
        title.setPadding(dp(8), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);
        LinearLayout btns = new LinearLayout(c);
        LinearLayout.LayoutParams bl0 = new LinearLayout.LayoutParams(-1, -2);
        bl0.topMargin = dp(6);
        card.addView(btns, bl0);
        final TextView last = text(c, lastLine(base), 13, 0xFF8FB4D9, false);
        last.setSingleLine(true);
        last.setEllipsize(TextUtils.TruncateAt.END);
        last.setPadding(0, dp(4), 0, 0);
        final boolean his = base.matches(".*\\b(anil|son)\\b.*"); // (her son's AC: asked first — never switched by a stray tap)
        if (urls[0] != null || urls[1] != null) {
            if (urls[0] != null) {
                TextView on = button(c, "ఆన్", 0xFF1E6B4A, 0xFFFFFFFF, 19);
                on.setOnClickListener(v -> press(base, name, true, urls[0], his, last));
                btns.addView(on, new LinearLayout.LayoutParams(0, dp(50), 1));
            }
            if (urls[1] != null) {
                TextView off = button(c, "ఆఫ్", 0xFF3A4658, 0xFFFFFFFF, 19);
                off.setOnClickListener(v -> press(base, name, false, urls[1], his, last));
                LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, dp(50), 1);
                if (urls[0] != null) ol.leftMargin = dp(8);
                btns.addView(off, ol);
            }
        } else {
            TextView go = button(c, "▶ నొక్కు", 0xFF1F3B5C, 0xFFFFFFFF, 18);
            go.setOnClickListener(v -> press(base, name, null, urls[2], his, last));
            btns.addView(go, new LinearLayout.LayoutParams(-1, dp(50)));
        }
        card.addView(last);
        return card;
    }

    /**
     * The name on her screen, in Telugu (only the screen: his box keeps English names): "amma ac" → "నా AC" (it is her
     * tablet), "anil ac" → "అబ్బాయి AC", "fan" → "ఫ్యాన్", "hall light" → "హాల్ లైట్".
     */
    static String deviceName(String base) {
        StringBuilder out = new StringBuilder();
        for (String w : base.trim().split("\\s+")) {
            String l = w.toLowerCase(Locale.ROOT), s;
            switch (l) {
                case "amma": case "mom": case "mother": s = "నా"; break;
                case "anil": case "son": s = "అబ్బాయి"; break;
                case "ac": s = "AC"; break;
                case "tv": s = "TV"; break;
                case "fan": s = "ఫ్యాన్"; break;
                case "light": case "lamp": s = "లైట్"; break;
                case "hall": s = "హాల్"; break;
                case "bedroom": s = "బెడ్‌రూమ్"; break;
                case "kitchen": s = "వంటగది"; break;
                case "good": s = "గుడ్"; break;
                case "night": s = "నైట్"; break;
                default: s = l.length() <= 2 ? w.toUpperCase(Locale.ROOT) : Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            if (out.length() > 0) out.append(' ');
            out.append(s);
        }
        return out.toString();
    }

    /** "చివరిసారి: ఆన్ · రాత్రి 10:15" (the link can't tell whether it is on now: only what was pressed last). */
    private String lastLine(String base) {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        String what = sp.getString("dev_" + base, "");
        long at = sp.getLong("dev_at_" + base, 0);
        if (what.isEmpty() || at == 0) return " ";
        return "చివరిసారి: " + what + " · " + when(at, System.currentTimeMillis());
    }

    /** A card's button: her son's device asks first (T2), then the link; "✓ … చేశాను" said and shown. */
    private void press(String base, String name, Boolean on, String url, boolean his, TextView last) {
        active();
        body.rig.poke();
        String what = on == null ? "" : on ? "ఆన్" : "ఆఫ్";
        if (his) { confirm(name + " " + what + " చేయమంటారా?", () -> run(base, name, on, url, last)); return; }
        run(base, name, on, url, last);
    }

    /** The question over the screen with big అవును / వద్దు (goes by itself after 15 s, as a no). */
    private void confirm(String question, Runnable yes) {
        askYesNo(question, yes, () -> show("సరే, ఏమీ చేయలేదు"), null, 15_000);
    }

    private Runnable askGone;

    /** The question over everything: ✓ → yes, ✕ → no, nothing for timeoutMs → timeout (may be null: then just gone). */
    private void askYesNo(String question, Runnable yes, Runnable no, Runnable timeout, long timeoutMs) {
        Context c = getContext();
        hideShop(); // (the question is always on top: never hidden behind the list)
        ask.removeAllViews();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(22), dp(28), dp(22));
        box.setBackground(round(0xFF13233A, 0xFF2E4C74, 24));
        TextView q = text(c, question, 26, 0xFFF4F8FC, true);
        q.setGravity(Gravity.CENTER);
        box.addView(q);
        LinearLayout row = new LinearLayout(c);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = dp(18);
        box.addView(row, rl);
        if (askGone != null) main.removeCallbacks(askGone);
        final Runnable hide = () -> ask.setVisibility(GONE);
        final Runnable gone = askGone = () -> { hide.run(); if (timeout != null) timeout.run(); };
        TextView y = button(c, "✓ అవును", 0xFF1E6B4A, 0xFFFFFFFF, 24);
        y.setOnClickListener(v -> { main.removeCallbacks(gone); hide.run(); active(); yes.run(); });
        row.addView(y, new LinearLayout.LayoutParams(dp(170), dp(70)));
        TextView n = button(c, "✕ వద్దు", 0xFF3A4658, 0xFFFFFFFF, 24);
        n.setOnClickListener(v -> { main.removeCallbacks(gone); hide.run(); active(); no.run(); });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(170), dp(70));
        nl.leftMargin = dp(16);
        row.addView(n, nl);
        ask.addView(box, new LayoutParams(-2, -2, Gravity.CENTER));
        ask.setVisibility(VISIBLE);
        active();
        main.postDelayed(gone, timeoutMs);
    }

    /** The question answered another way (her voice): it goes, nothing more is done. */
    void hideAsk() {
        if (askGone != null) main.removeCallbacks(askGone);
        askGone = null;
        ask.setVisibility(GONE);
    }

    /** 🌙 "AC ఆన్ చేయమంటారా?" (HomeNight): her ✓ / ✕ here, or her voice. */
    void askNight(String q) {
        final Context app = getContext().getApplicationContext();
        askYesNo(q, () -> nightAnswer(app, true), () -> nightAnswer(app, false), null, 40_000);
    }

    private void nightAnswer(Context app, boolean ac) {
        MainActivity.homeAnswered(); // (her ✓ / ✕: Jarvis stops waiting for a spoken answer)
        new Thread(() -> {
            String line = HomeNight.answer(app, ac);
            if (line != null) HomeCare.say(app, line, "calm");
        }, "home-night").start();
    }

    /** 📹 "అబ్బాయికి వీడియో కాల్ చేయనా?": her ✓ starts it, ✕ is a no. */
    void askVideo(String q) {
        askYesNo(q, () -> { String r = MainActivity.homeVideoAnswer(true); if (r != null && !r.isEmpty()) host.speak(r); },
                () -> { String r = MainActivity.homeVideoAnswer(false); if (r != null && !r.isEmpty()) show(r); },
                () -> { if ("video".equals(HomeCare.pending())) HomeCare.clearPending(); }, 90_000); // (no answer: no call later)
    }

    private void run(String base, String name, Boolean on, String url, TextView last) {
        final String label = on == null ? name : name + " " + (on ? "ఆన్" : "ఆఫ్");
        show(label + "…");
        new Thread(() -> {
            boolean ok;
            try { Http.getText(url); ok = true; } catch (Exception e) { ok = false; }
            final boolean done = ok;
            main.post(() -> {
                if (done) {
                    show("✓ " + label + " చేశాను");
                    body.rig.show("happy", 2500);
                    body.rig.gesture("thumb", 2500);
                    getContext().getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                            .putString("dev_" + base, on == null ? "నొక్కాను" : on ? "ఆన్" : "ఆఫ్").putLong("dev_at_" + base, System.currentTimeMillis()).apply();
                    last.setText(lastLine(base));
                    if (!night && DeviceIcon.kind(base) != DeviceIcon.TV) host.speak(label + " చేశాను."); // (night: the ✓ only)
                } else {
                    show("⚠️ " + label + ": " + (online(getContext()) ? "లింక్ జవాబివ్వలేదు" : "నెట్ లేదు"));
                    body.rig.show("worried", 2500);
                }
            });
        }, "home-smart").start();
    }

    /** A small picture of the device (an AC unit with cool air, a fan, a bulb, a TV, a plug), so she knows it at a glance. */
    static final class DeviceIcon extends View {
        static final int AC = 0, FAN = 1, BULB = 2, TV = 3, PLUG = 4, SCENE = 5;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final android.graphics.Path path = new android.graphics.Path();

        DeviceIcon(Context c, int kind) { super(c); this.kind = kind; }

        static int kind(String base) {
            if (base.matches(".*\\bac\\b.*")) return AC;
            if (base.matches(".*\\bfan\\b.*|.*ఫ్యాన్.*")) return FAN;
            if (base.matches(".*\\b(light|lamp|bulb)\\b.*|.*లైట్.*")) return BULB;
            if (base.matches(".*\\btv\\b.*|.*టీవీ.*")) return TV;
            if (base.matches(".*\\b(night|scene|good|morning)\\b.*")) return SCENE;
            return PLUG;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), u = Math.min(w / 46f, h / 40f);
            cv.save();
            cv.translate((w - 46 * u) / 2f, (h - 40 * u) / 2f);
            cv.scale(u, u);
            p.setStyle(Paint.Style.FILL);
            switch (kind) {
                case AC: {
                    p.setColor(0xFFEAF2FA); // the indoor unit
                    r.set(3, 4, 43, 19);
                    cv.drawRoundRect(r, 5, 5, p);
                    p.setColor(0xFF9FB4CC); // its air slot
                    r.set(7, 14.5f, 39, 16.5f);
                    cv.drawRoundRect(r, 1, 1, p);
                    p.setColor(0xFF2ECC71); // the little light
                    cv.drawCircle(37, 8.5f, 1.6f, p);
                    p.setStyle(Paint.Style.STROKE); // cool air coming out
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(2.2f);
                    p.setColor(0xFF7FD3FF);
                    for (int i = 0; i < 3; i++) {
                        float x = 12 + i * 11;
                        path.rewind();
                        path.moveTo(x, 22);
                        path.cubicTo(x - 3, 26, x + 3, 29, x, 33);
                        cv.drawPath(path, p);
                    }
                    p.setStrokeWidth(1.6f); // a small snowflake
                    float sx = 40, sy = 31;
                    for (int i = 0; i < 3; i++) {
                        double t = Math.PI * i / 3;
                        float dx = (float) Math.cos(t) * 4.5f, dy = (float) Math.sin(t) * 4.5f;
                        cv.drawLine(sx - dx, sy - dy, sx + dx, sy + dy, p);
                    }
                    break;
                }
                case FAN: {
                    p.setColor(0xFF7FD3FF);
                    for (int i = 0; i < 3; i++) {
                        cv.save();
                        cv.rotate(i * 120, 23, 20);
                        r.set(20, 2, 30, 19);
                        cv.drawOval(r, p);
                        cv.restore();
                    }
                    p.setColor(0xFFEAF2FA);
                    cv.drawCircle(23, 20, 4.2f, p);
                    break;
                }
                case BULB: {
                    p.setColor(0xFFFFD166);
                    cv.drawCircle(23, 16, 11, p);
                    p.setColor(0xFF9FB4CC);
                    r.set(18, 26, 28, 33);
                    cv.drawRoundRect(r, 2, 2, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.6f);
                    p.setColor(0x99FFD166);
                    cv.drawCircle(23, 16, 15, p);
                    break;
                }
                case TV: {
                    p.setColor(0xFF9FB4CC);
                    r.set(4, 4, 42, 29);
                    cv.drawRoundRect(r, 4, 4, p);
                    p.setColor(0xFF2E6FB7);
                    r.set(7, 7, 39, 26);
                    cv.drawRoundRect(r, 2, 2, p);
                    p.setColor(0xFF9FB4CC);
                    r.set(15, 31, 31, 34);
                    cv.drawRoundRect(r, 1.5f, 1.5f, p);
                    break;
                }
                case SCENE: {
                    p.setColor(0xFFFFD166); // a moon and a star
                    cv.drawCircle(20, 19, 12, p);
                    p.setColor(0xFF1B3050);
                    cv.drawCircle(25, 15, 10, p);
                    p.setColor(0xFFFFE9A8);
                    cv.drawCircle(36, 9, 2.4f, p);
                    break;
                }
                default: {
                    p.setColor(0xFFEAF2FA); // a plug
                    r.set(13, 12, 33, 30);
                    cv.drawRoundRect(r, 5, 5, p);
                    p.setColor(0xFF9FB4CC);
                    r.set(17, 5, 20, 13);
                    cv.drawRect(r, p);
                    r.set(26, 5, 29, 13);
                    cv.drawRect(r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.4f);
                    cv.drawLine(23, 30, 23, 37, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            cv.restore();
        }
    }

    /**
     * Always all of its one child on the screen: when the child is taller than the room (a big font, the night "more"
     * open), it is laid out a little wider and taller and shrunk to fit, everything together — nothing scrolls away.
     */
    static final class FitBox extends FrameLayout {
        private float s = 1f;

        FitBox(Context c) { super(c); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            View ch = getChildCount() > 0 ? getChildAt(0) : null;
            if (ch == null || w <= 0 || h <= 0) { super.onMeasure(ws, hs); return; }
            float sc = 1f;
            for (int i = 0; i < 6; i++) {
                ch.measure(MeasureSpec.makeMeasureSpec(Math.round(w / sc), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int need = Math.max(1, ch.getMeasuredHeight());
                if (need * sc <= h + 1) break;
                sc = Math.max(0.55f, Math.min(sc * 0.98f, h / (float) need));
                if (sc <= 0.55f) break;
            }
            s = sc;
            ch.measure(MeasureSpec.makeMeasureSpec(Math.round(w / s), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(h / s), MeasureSpec.EXACTLY));
            setMeasuredDimension(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            View ch = getChildCount() > 0 ? getChildAt(0) : null;
            if (ch == null) return;
            ch.layout(0, 0, ch.getMeasuredWidth(), ch.getMeasuredHeight());
            ch.setPivotX(0);
            ch.setPivotY(0);
            ch.setScaleX(s);
            ch.setScaleY(s);
        }
    }


    // ================================================================ 🛒 the shopping list (F1)
    private final FrameLayout shopPanel;
    private final Runnable shopIdle = this::hideShop;

    /** The list changed (any thread): the 🛒 button's count, and the panel when it is open. */
    static void shopChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            HomeScreen h = shown;
            if (h == null) return;
            h.shopCount();
            if (h.shopPanel.getVisibility() == VISIBLE) h.showShop();
        });
    }

    private void shopCount() {
        if (shopBtn == null) return;
        int n = HomeShop.items(getContext()).size();
        shopBtn.setText(n == 0 ? "🛒 సరుకులు" : "🛒 సరుకులు (" + n + ")");
    }

    /** The list, big: each item with "✓ తెచ్చారు"; "🎤 ఇంకోటి చెప్పండి", "📤 అబ్బాయికి పంపు", "✕ మూసేయి". */
    void showShop() {
        Context c = getContext();
        shopPanel.removeAllViews();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(26), dp(20), dp(26), dp(20));
        box.setBackground(round(0xFF13233A, 0xFF2E4C74, 24));
        box.addView(text(c, "🛒 సరుకుల లిస్ట్", 28, 0xFFF2B544, true));
        List<String> items = HomeShop.items(c);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        if (items.isEmpty()) {
            TextView e = text(c, "లిస్ట్ ఖాళీగా ఉంది.\nఏదైనా అయిపోతే \"Jarvis, బియ్యం అయిపోయాయి\" అని చెప్పండి, లేదా కింద 🎤 నొక్కండి.", 21, 0xFFDCE7F3, false);
            e.setPadding(0, dp(12), 0, dp(12));
            list.addView(e);
        }
        for (String it : items) {
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            TextView name = text(c, "• " + it, 25, 0xFFF4F8FC, true);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView got = button(c, "✓ తెచ్చారు", 0xFF1E6B4A, 0xFFFFFFFF, 19);
            got.setOnClickListener(v -> {
                java.util.List<String> one = new ArrayList<>();
                one.add(it);
                HomeShop.remove(getContext(), one); // (the panel and the button refresh through shopChanged)
                body.rig.poke();
                body.rig.show("happy", 2000);
            });
            r.addView(got, new LinearLayout.LayoutParams(dp(150), dp(56)));
            list.addView(r);
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(c);
        sc.addView(list);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, 0, 1);
        scl.topMargin = dp(8);
        box.addView(sc, scl);
        final TextView note = text(c, "", 17, 0xFF8FB4D9, false);
        note.setPadding(0, dp(6), 0, dp(6));
        box.addView(note);
        LinearLayout row = new LinearLayout(c);
        TextView tell = button(c, "🎤 ఇంకోటి చెప్పండి", 0xFFF2B544, 0xFF1A1206, 20);
        fitText(tell, 20);
        tell.setOnClickListener(v -> {
            hideShop();
            HomeCare.setPending("shop"); // (what she says now goes on the list)
            host.ask(HomeCare.who(getContext()) + ", ఏమి కావాలో చెప్పండి.");
        });
        row.addView(tell, new LinearLayout.LayoutParams(0, dp(64), 1.3f));
        TextView send = button(c, "📤 అబ్బాయికి పంపు", 0xFF1F3B5C, 0xFFFFFFFF, 20);
        fitText(send, 20);
        send.setEnabled(!items.isEmpty());
        send.setAlpha(items.isEmpty() ? 0.45f : 1f);
        send.setOnClickListener(v -> {
            note.setText("పంపుతున్నాను…");
            final Context app = getContext().getApplicationContext();
            new Thread(() -> {
                boolean ok = HomeShop.sendNow(app, true);
                post(() -> note.setText(ok ? "✓ అబ్బాయి ఫోన్‌కి పంపాను" : "ఇప్పుడు పంపలేకపోయాను: కాసేపట్లో నేనే మళ్ళీ పంపుతాను"));
            }, "home-shop").start();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(64), 1.2f);
        sl.leftMargin = dp(10);
        row.addView(send, sl);
        TextView close = button(c, "✕", 0xFF3A4658, 0xFFFFFFFF, 24);
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> hideShop());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(72), dp(64));
        cl.leftMargin = dp(10);
        row.addView(close, cl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(8);
        box.addView(row, rl);
        LayoutParams bl = new LayoutParams(Math.min(dp(760), Math.round(getWidth() * 0.9f) > 0 ? Math.round(getWidth() * 0.9f) : dp(760)), -1, Gravity.CENTER);
        bl.topMargin = dp(30);
        bl.bottomMargin = dp(30);
        shopPanel.addView(box, bl);
        shopPanel.setVisibility(VISIBLE);
        active();
        main.removeCallbacks(shopIdle);
        main.postDelayed(shopIdle, 90_000L);
    }

    private void hideShop() {
        main.removeCallbacks(shopIdle);
        shopPanel.setVisibility(GONE);
        shopPanel.removeAllViews();
    }

    // ================================================================ 🌅 the morning greeting (F6)
    private long morningSeenAt;

    /**
     * Once a morning (6–11), the first time someone is about: Jarvis stretches, then "శుభోదయం" with today's weather
     * (an umbrella on a rainy day, water and shade on a hot one, a sweater when it is cold). Waits a few minutes for
     * today's forecast; never while she asked for quiet, is out, or Jarvis is busy.
     */
    private void morning(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 6 || h >= 11) return;
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        String day = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (day.equals(sp.getString("morning_said", ""))) return;
        long now = System.currentTimeMillis(), life = HomeCare.sp(getContext()).getLong("life", 0);
        boolean about = now - activeAt < 3 * 60_000L || now - life < 3 * 60_000L && life > startOfDay(c) + NIGHT_TO * 3600_000L;
        if (!about || HomeCare.quiet(getContext()) || HomeCare.out(getContext()) || body.rig.mode() != BodyRig.IDLE || gamesOpen()) return;
        boolean forecast = day.equals(sp.getString("d_day", ""));
        if (!forecast && online(getContext())) {
            if (morningSeenAt == 0) morningSeenAt = now;
            if (now - morningSeenAt < 4 * 60_000L) { fetchWeather(true); return; } // (a few minutes for today's weather)
        }
        sp.edit().putString("morning_said", day).apply();
        morningSeenAt = 0;
        String line = morningLine(HomeCare.who(getContext()), DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], forecast,
                sp.getFloat("d_max", Float.NaN), sp.getFloat("d_min", Float.NaN), sp.getInt("d_rain", -1), sp.getInt("d_code", -1));
        hideFrame();
        body.rig.poke();
        body.rig.play("stretch"); // (he stretches and yawns first, then the greeting)
        main.postDelayed(() -> { if (running) host.speak(line); }, 5500);
    }

    /** "శుభోదయం అమ్మగారు! ఈరోజు శనివారం. ఈరోజు వర్షం పడేలా ఉంది…". Pure. */
    static String morningLine(String w, String dayName, boolean forecast, float max, float min, int rain, int code) {
        StringBuilder b = new StringBuilder("శుభోదయం " + w + "! ఈరోజు " + dayName + ". ");
        if (forecast) {
            boolean storm = code >= 95, wet = rain >= 50 || code >= 51 && code <= 67 || code >= 80 && code <= 82;
            long hi = Float.isNaN(max) ? -100 : Math.round(max), lo = Float.isNaN(min) ? -100 : Math.round(min);
            if (storm) b.append("ఈరోజు ఉరుములతో వర్షం రావచ్చు, వీలైనంత వరకు ఇంట్లోనే ఉండండి. ");
            else if (wet) b.append("ఈరోజు వర్షం పడేలా ఉంది, బయటికి వెళ్తే గొడుగు తీసుకెళ్ళండి. ");
            if (hi >= 37) b.append("ఎండ బాగా ఉంటుంది, ").append(hi).append(" డిగ్రీల వరకు. మధ్యాహ్నం బయటికి వెళ్ళకండి, నీళ్ళు ఎక్కువ తాగండి. ");
            else if (lo > -100 && lo <= 18) b.append("చలిగా ఉంది, ").append(lo).append(" డిగ్రీలు. స్వెటర్ వేసుకోండి. ");
            else if (!storm && !wet && hi > -100 && lo > -100) b.append("వాతావరణం బాగుంది, ").append(lo).append(" నుంచి ").append(hi).append(" డిగ్రీలు. ");
        }
        b.append("దేవుడు ఈ రోజంతా మిమ్మల్ని దీవించాలి!");
        return b.toString();
    }

    // ================================================================ 🪴 Jarvis's garden (F7)
    private final GardenView garden;
    private long gardenAt;
    private volatile boolean gardenBusy;

    /** A care button was pressed: the garden looks again in a moment (the tick is written by then). */
    private void gardenSoon() { main.postDelayed(this::gardenRefresh, 1500); }

    /** The last seven days' plants (oldest first), from her ticks: tablets, meals, water. Off the main thread. */
    private void gardenRefresh() {
        gardenAt = System.currentTimeMillis();
        if (gardenBusy) return;
        gardenBusy = true;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                final int[] st = new int[7];
                Calendar k = Calendar.getInstance();
                k.add(Calendar.DAY_OF_MONTH, -6);
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
                for (int i = 0; i < 7; i++) {
                    int[] g = gardenCounts(app, f.format(k.getTime()));
                    st[i] = stage(g[0], g[1], g[2], g[3], g[4]);
                    k.add(Calendar.DAY_OF_MONTH, 1);
                }
                post(() -> garden.scene.set(st));
            } catch (Throwable ignored) {
            } finally {
                gardenBusy = false;
            }
        }, "home-garden").start();
    }

    /** One day's {tablets taken, tablets due, meals ticked, water, water times}. */
    static int[] gardenCounts(Context c, String day) {
        int[] d = {0, 0};
        try { d = Medicine.dayDoses(c, day); } catch (Throwable ignored) {}
        JSONObject l = HomeCare.dayLog(c, day);
        int meals = 0;
        for (String[] m : HomeCare.MEALS) if (l.has("meal_" + m[0])) meals++;
        int waterTimes = Math.max(1, HomeCare.waterTimes(c).split(",").length);
        return new int[]{d[0], d[1], meals, l.optInt("water"), waterTimes};
    }

    /**
     * A day's plant (0 seed … 4 flower) from the share of her day that was done: tablets taken of those due, meals (4),
     * water (as often as asked). Never a dead plant: a quiet day is just a small one. Pure.
     */
    static int stage(int taken, int due, int meals, int water, int waterTimes) {
        int all = Math.max(0, due) + HomeCare.MEALS.length + Math.max(1, waterTimes);
        int done = Math.min(taken, Math.max(0, due)) + Math.min(meals, HomeCare.MEALS.length) + Math.min(water, Math.max(1, waterTimes));
        float s = done / (float) all;
        return s >= 0.85f ? 4 : s >= 0.6f ? 3 : s >= 0.35f ? 2 : s >= 0.1f ? 1 : 0;
    }

    /** Her tap on the garden: today's plant in words, and what makes it flower. */
    private void gardenSay() {
        active();
        body.rig.poke();
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new java.util.Date());
            int[] g = gardenCounts(app, day);
            String line = gardenLine(HomeCare.who(app), g, stage(g[0], g[1], g[2], g[3], g[4]));
            post(() -> host.speak(line));
        }, "home-garden-say").start();
    }

    /** What Jarvis says about today's plant. Pure. */
    static String gardenLine(String w, int[] g, int stage) {
        StringBuilder b = new StringBuilder(w + ", ఇది మన తోట. రోజుకి ఒక మొక్క. ");
        if (stage >= 4) return b.append("ఈరోజు మొక్కకి పువ్వు పూసింది! మాత్రలు, భోజనం, నీళ్ళు అన్నీ బాగా చూసుకున్నారు. చాలా సంతోషం!").toString();
        b.append("ఈరోజు మొక్క ఇంకా పెరుగుతోంది. ");
        java.util.List<String> left = new ArrayList<>();
        if (g[1] > g[0]) left.add("మాత్రలు");
        if (g[2] < HomeCare.MEALS.length) left.add("భోజనం");
        if (g[3] < g[4]) left.add("నీళ్ళు");
        if (!left.isEmpty()) b.append(String.join(", ", left)).append(" టైమ్‌కి అయితే పువ్వు పూస్తుంది.");
        return b.toString();
    }

    /** The garden in the corner (GardenScene through the sky's canvas pen); a gentle sway, ~12 frames a second. */
    static final class GardenView extends View {
        final GardenScene scene = new GardenScene();
        private final WeatherSky.Pen pen = new WeatherSky.Pen();
        private long last;

        GardenView(Context c) { super(c); }

        void setDim(boolean d) { scene.setDim(d); invalidate(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            scene.resize(w, h);
        }

        @Override protected void onDraw(Canvas cv) {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.08f : Math.min(0.25f, (now - last) / 1000f);
            last = now;
            scene.step(dt);
            pen.cv = cv;
            try { scene.draw(pen); } finally { pen.cv = null; }
            if (scene.moving() && isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(80);
            else last = 0;
        }

        @Override protected void onWindowVisibilityChanged(int vis) {
            super.onWindowVisibilityChanged(vis);
            last = 0;
            if (vis == VISIBLE) invalidate();
        }
    }

    // ================================================================ small parts
    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** One line that shrinks to fit its button (a big font never cuts "మాట్లాడు"). */
    private static void fitText(TextView t, int maxSp) {
        t.setSingleLine(true);
        t.setPadding(t.getPaddingLeft() + 4, 0, t.getPaddingRight() + 4, 0);
        try { t.setAutoSizeTextTypeUniformWithConfiguration(11, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP); } catch (Exception ignored) {}
    }

    private TextView chip(Context c) {
        TextView t = text(c, "", 15, 0xFFDCE7F3, false);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setBackground(round(0xFF1B3050, 0xFF2A4468, 14));
        return t;
    }

    private TextView button(Context c, String s, int bg, int fg, float sp) {
        TextView t = text(c, s, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, bg == 0xFFF2B544 ? bg : 0xFF2A4468, 16));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), stroke);
        return g;
    }
}
