package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The floating Jarvis button: a small round Jarvis globe over every app (it lives in the "Jarvis స్క్రీన్" accessibility
 * switch, so it needs nothing else). Drag it anywhere; it rests at the side. Tap: a few small options for what is on
 * the screen right now: read it aloud, tell its meaning in Telugu, what this is, translate, suggest a reply, check for a
 * scam, or ask Jarvis by voice. Hold it to talk to Jarvis. Hidden on the lock screen, over Jarvis's own screens and
 * while Jarvis is using the phone. Screens of banking / payment apps are never sent to the AI.
 */
final class FloatBubble implements ScreenReader.Listener {
    static final String KEY = "bubble_on";

    static boolean on(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean(KEY, true); }

    static void set(Context c, boolean v) {
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply();
        JarvisAccessibility.syncBubble();
    }

    private static final int READ = 0, MEANING = 1, ABOUT = 2, TELUGU = 3, REPLY = 4, SCAM = 5, PHOTO = 6, REMIND = 7, PRICE = 8, VIDEO = 9,
            WORDS = 10, SAVE = 11, ASK = 12, HIDE = 13;
    /** {kind, label} of every option; the order changes with the app in front. */
    private static final Object[][] OPTIONS = {
            {READ, "📖 చదువు"}, {MEANING, "🧠 అర్థం చెప్పు"}, {ABOUT, "💡 దీని గురించి"}, {TELUGU, "🌐 తెలుగులో"}, {PHOTO, "🖼️ ఫోటో చదువు"},
            {REPLY, "💬 జవాబు"}, {REMIND, "⏰ గుర్తుపెట్టు"}, {SCAM, "🛡️ మోసమా?"}, {PRICE, "🛒 ధర పోలిక"}, {VIDEO, "🎬 వీడియో"},
            {WORDS, "📚 పదాలు"}, {SAVE, "💾 తర్వాత చదువు"}, {ASK, "🎙️ అడుగు"}, {HIDE, "✕ దాచు"}};

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final int size;
    private final ScreenReader reader;
    private FrameLayout bubble;
    private HoloOrb orb;
    private WindowManager.LayoutParams blp;
    private LinearLayout menu;
    private LinearLayout card;
    private TextView cardTitle, cardText;
    private View cardActions;
    private boolean away, held, busy, longPressed, dragging, gone, menuWasOpen;
    private long menuClosedAt;
    private String lastAnswer = "", lastTitle = "", replyPkg = "";
    // the talk about this screen: what was read, the picture, and what was said (for 🎙️ follow-up questions)
    private String ctxApp = "", ctxPage = "", ctxShot;
    private final java.util.ArrayDeque<String[]> turns = new java.util.ArrayDeque<>();
    private android.speech.SpeechRecognizer sr;
    private boolean listening;
    private TextView micBtn, extraBtn, playBtn;
    private ScrollView cardScroll;
    private WindowManager.LayoutParams cardLp;
    private float cardDownX, cardDownY;
    private int cardStartX, cardStartY;
    private LinearLayout cardRow2;
    private float downX, downY;
    private int startX, startY;

    FloatBubble(AccessibilityService svc) {
        this.svc = svc;
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        d = svc.getResources().getDisplayMetrics().density;
        size = dp(50);
        reader = ScreenReader.get(svc);
    }

    private int dp(float v) { return Math.round(v * d); }
    private int screenW() { return svc.getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return svc.getResources().getDisplayMetrics().heightPixels; }

    // ================================================================ the button

    boolean show() {
        if (bubble != null) return true;
        bubble = new FrameLayout(svc);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE6061424);
        bg.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(Ui.CYAN, 0x99));
        bubble.setBackground(bg);
        orb = new HoloOrb(svc, 36);
        orb.setPace(160, 50); // a calm globe that does not eat the battery; livelier while working
        orb.setContentDescription("Jarvis");
        bubble.addView(orb, new FrameLayout.LayoutParams(-1, -1));
        blp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.TOP | Gravity.LEFT;
        boolean right = svc.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("bubble_right", true);
        blp.x = right ? screenW() - size - dp(4) : dp(4);
        blp.y = clampY(svc.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getInt("bubble_y", (int) (screenH() * 0.38f)));
        bubble.setOnTouchListener(this::touch);
        try { wm.addView(bubble, blp); } catch (Exception e) { bubble = null; orb = null; return false; }
        reader.listener = this;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            svc.registerReceiver(screen, f);
        } catch (Exception ignored) {}
        fadeSoon();
        away = true; // until checked: maybe Jarvis's own screen (Settings) or the lock screen is in front
        refresh();
        main.post(recheck);
        return true;
    }

    /** Screen off: hidden (and its card closed) before anyone else picks up the phone; unlocked: back. */
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                away = true;
                closeCard(); // (also stops listening)
                refresh();
                main.removeCallbacks(recheck);
                main.postDelayed(recheck, 2000);
            } else {
                main.removeCallbacks(recheck);
                main.post(recheck);
            }
        }
    };

    private boolean locked() {
        KeyguardManager km = svc.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    void remove() {
        gone = true;
        hideMenu();
        closeCard();
        if (reader.listener == this) reader.listener = null;
        try { if (sr != null) sr.destroy(); } catch (Exception ignored) {}
        sr = null;
        try { svc.unregisterReceiver(screen); } catch (Exception ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        bubble = null;
        orb = null;
        main.removeCallbacksAndMessages(null); // fade, recheck, hold-to-talk, a capture on its way
    }

    /** The app in front changed: hidden on the lock screen and over Jarvis's own screens. */
    void onWindow(String pkg, String cls) {
        boolean jarvis = pkg != null && pkg.equals(svc.getPackageName()) && cls != null && cls.endsWith("Activity");
        if (pkg != null && pkg.equals(svc.getPackageName()) && !jarvis) return; // our own floating windows
        boolean lock = locked();
        // a keyboard or the notification shade opening says nothing about the app under it
        if (!lock && (JarvisAccessibility.isKeyboard(pkg) || "com.android.systemui".equals(pkg))) return;
        away = jarvis || lock;
        refresh();
        main.removeCallbacks(recheck);
        if (away) main.postDelayed(recheck, 2000);
    }

    /** While hidden: back as soon as Jarvis's screen is gone and the phone is unlocked. */
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            if (bubble == null || !away) return;
            boolean locked = locked();
            boolean jarvis = false;
            try {
                android.view.accessibility.AccessibilityNodeInfo r = svc.getRootInActiveWindow();
                jarvis = r != null && r.getPackageName() != null && svc.getPackageName().contentEquals(r.getPackageName());
            } catch (Exception ignored) {}
            away = locked || jarvis;
            refresh();
            if (away) main.postDelayed(this, 2000);
        }
    };

    /** Out of the way while Jarvis is using the phone (taps and screenshots must not hit it). */
    void hold(boolean h) {
        held = h;
        refresh();
    }

    private void refresh() {
        if (bubble == null) return;
        boolean hide = away || held;
        bubble.setVisibility(hide ? View.GONE : View.VISIBLE);
        if (card != null) card.setVisibility(hide ? View.GONE : View.VISIBLE); // never on the lock screen or under Jarvis's own taps
        if (hide) hideMenu();
        if (orb != null) orb.setState(busy ? HoloOrb.THINKING : reader.active() && !reader.paused() ? HoloOrb.SPEAKING : HoloOrb.IDLE);
        if (playBtn != null) playBtn.setText(cardSpeaking() ? "⏸ ఆపు" : "▶ ప్లే");
    }

    @Override public void onReaderState() { main.post(this::refresh); }

    void onScreenTurned() {
        if (bubble == null) return;
        boolean right = blp.x + size / 2 > screenW() / 2;
        blp.x = right ? screenW() - size - dp(4) : dp(4);
        blp.y = clampY(blp.y);
        try { wm.updateViewLayout(bubble, blp); } catch (Exception ignored) {}
        hideMenu();
        closeCard(); // its width was for the old screen
    }

    private int clampY(int y) { return Math.max(dp(40), Math.min(screenH() - size - dp(60), y)); }

    private final Runnable fade = () -> { if (bubble != null && menu == null) bubble.animate().alpha(0.62f).setDuration(400).start(); };

    private void fadeSoon() {
        main.removeCallbacks(fade);
        main.postDelayed(fade, 3500);
    }

    private final Runnable holdToTalk = () -> {
        if (dragging) return;
        longPressed = true;
        hideMenu();
        ask();
    };

    private boolean touch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (locked()) { away = true; refresh(); main.postDelayed(recheck, 2000); return false; }
                menuWasOpen = menu != null || SystemClock.elapsedRealtime() - menuClosedAt < 120;
                downX = e.getRawX();
                downY = e.getRawY();
                startX = blp.x;
                startY = blp.y;
                dragging = false;
                longPressed = false;
                bubble.animate().cancel();
                bubble.setAlpha(1f);
                main.postDelayed(holdToTalk, 650);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(svc).getScaledTouchSlop()) {
                    dragging = true;
                    main.removeCallbacks(holdToTalk);
                    hideMenu();
                }
                if (dragging) {
                    blp.x = Math.max(0, Math.min(screenW() - size, startX + Math.round(dx)));
                    blp.y = clampY(startY + Math.round(dy));
                    try { wm.updateViewLayout(bubble, blp); } catch (Exception ignored) {}
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                main.removeCallbacks(holdToTalk);
                if (dragging) snap();
                else if (!longPressed) { if (menuWasOpen) hideMenu(); else showMenu(); }
                fadeSoon();
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(holdToTalk);
                if (dragging) snap();
                fadeSoon();
                return true;
            default:
                return false;
        }
    }

    /** Back to the nearest side, where it stays. */
    private void snap() {
        final boolean right = blp.x + size / 2 > screenW() / 2;
        final int from = blp.x, to = right ? screenW() - size - dp(4) : dp(4);
        ValueAnimator a = ValueAnimator.ofInt(from, to).setDuration(180);
        a.addUpdateListener(an -> {
            if (bubble == null) return;
            blp.x = (int) an.getAnimatedValue();
            try { wm.updateViewLayout(bubble, blp); } catch (Exception ignored) {}
        });
        a.start();
        svc.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("bubble_right", right).putInt("bubble_y", blp.y).apply();
    }

    // ================================================================ the small options

    private LinearLayout items;
    private int chipW;

    /** What kind of app is in front: the options that fit it come first. */
    private static int[] firstFor(String pkg) {
        String p = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("whatsapp") || p.contains("telegram") || p.contains("messag") || p.contains("mms") || p.contains("instagram")
                || p.contains("signal") || p.contains("sms") || p.contains("orca")) return new int[]{REPLY, MEANING, SCAM, REMIND, PHOTO};
        if (p.contains("amazon") || p.contains("flipkart") || p.contains("meesho") || p.contains("myntra") || p.contains("ajio") || p.contains("jiomart")
                || p.contains("bigbasket") || p.contains("grofers") || p.contains("zepto") || p.contains("nykaa") || p.contains("tatacliq")
                || p.contains("croma") || p.contains("reliance") || p.contains("snapdeal")) return new int[]{PRICE, ABOUT, SCAM};
        if (p.contains("youtube") || p.contains("mxtech") || p.contains("hotstar") || p.contains("netflix") || p.contains("primevideo")
                || p.contains("jiocinema") || p.contains("video")) return new int[]{VIDEO, ABOUT, MEANING};
        if (p.contains("gallery") || p.contains("photos") || p.contains("camera")) return new int[]{PHOTO, ABOUT, MEANING};
        if (p.contains("chrome") || p.contains("browser") || p.contains("firefox") || p.contains("opera") || p.contains("emmx") || p.contains("brave")
                || p.contains("news") || p.contains("eterno") || p.contains("inshorts") || p.contains("eenadu") || p.contains("sakshi") || p.contains("way2"))
            return new int[]{READ, MEANING, ABOUT, TELUGU, WORDS, SAVE};
        return new int[]{READ, MEANING, ABOUT};
    }

    private void showMenu() {
        if (bubble == null || menu != null) return;
        menu = new LinearLayout(svc);
        menu.setOrientation(LinearLayout.VERTICAL);
        items = new LinearLayout(svc);
        items.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2061424);
        bg.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(Ui.CYAN, 0x88));
        bg.setCornerRadius(dp(16));
        menu.setBackground(bg);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (reader.active()) { // reading: ⏮ ⏸ ⏭ 🐢 ⏩ ⏹
            LinearLayout row = new LinearLayout(svc);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(control("⏮", () -> reader.skip(-1)));
            row.addView(control(reader.paused() ? "▶" : "⏸", reader::toggle));
            row.addView(control("⏭", () -> reader.skip(1)));
            row.addView(control("🐢", () -> reader.faster(false)));
            row.addView(control("⏩", () -> reader.faster(true)));
            row.addView(control("⏹", reader::stop));
            TextView sp = new TextView(svc);
            sp.setText(String.format(java.util.Locale.ENGLISH, "%.1fx", reader.speed()));
            sp.setTextColor(Ui.CYAN);
            sp.setTextSize(12f);
            sp.setPadding(dp(4), 0, dp(6), 0);
            row.addView(sp);
            items.addView(row);
        }
        // the options: what fits this app first (lit), then the rest; two to a row
        String front = JarvisAccessibility.frontPackage();
        if (JarvisAccessibility.isKeyboard(front) || "com.android.systemui".equals(front)) front = JarvisAccessibility.currentPackage();
        int[] first = firstFor(front);
        int room = screenW() - size - dp(26); // beside the button
        chipW = Math.min(dp(134), room / 2 - dp(6));
        int cols = chipW < dp(96) ? 1 : 2;
        if (cols == 1) chipW = Math.min(dp(200), room - dp(12));
        java.util.List<Object[]> order = new java.util.ArrayList<>();
        for (int k : first) for (Object[] o : OPTIONS) if ((int) o[0] == k) order.add(o);
        for (Object[] o : OPTIONS) if (!order.contains(o)) order.add(o);
        LinearLayout row = null;
        for (int i = 0; i < order.size(); i++) {
            if (i % cols == 0) { row = new LinearLayout(svc); items.addView(row); }
            final int kind = (int) order.get(i)[0];
            row.addView(chip((String) order.get(i)[1], i < first.length, () -> option(kind)));
        }
        final int maxH = screenH() - dp(80);
        ScrollView sc = new ScrollView(svc) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)); }
        };
        sc.addView(items);
        menu.addView(sc);
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = menu.getMeasuredWidth(), h = Math.min(menu.getMeasuredHeight(), maxH + dp(12));
        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.LEFT;
        boolean right = blp.x + size / 2 > screenW() / 2;
        mlp.x = right ? Math.max(dp(4), blp.x - w - dp(6)) : Math.min(screenW() - w - dp(4), blp.x + size + dp(6));
        mlp.y = Math.max(dp(30), Math.min(screenH() - h - dp(40), blp.y + size / 2 - h / 2));
        menu.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; }
            return false;
        });
        try { wm.addView(menu, mlp); } catch (Exception e) { menu = null; }
        main.removeCallbacks(fade);
    }

    private TextView chip(String label, boolean lit, Runnable r) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13.5f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(10), dp(9), dp(8), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(lit ? FaceRig.withAlpha(Ui.CYAN, 0x30) : 0x14FFFFFF);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setOnClickListener(v -> { hideMenu(); r.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(chipW, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView control(String icon, Runnable r) {
        TextView t = new TextView(svc);
        t.setText(icon);
        t.setTextSize(17f);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(6), dp(8), dp(8));
        t.setOnClickListener(v -> {
            r.run();
            if (icon.equals("⏹")) { hideMenu(); return; }
            main.postDelayed(() -> { if (menu != null && !away && !held) { hideMenu(); showMenu(); } }, 150); // stays open with the new state
        });
        return t;
    }

    private void option(int kind) {
        switch (kind) {
            case ASK: ask(); break;
            case HIDE:
                set(svc, false);
                Toast.makeText(svc, "Jarvis బటన్ దాచాను. సెట్టింగ్స్ → ఫ్లోటింగ్ బటన్ లో మళ్ళీ ఆన్ చేయొచ్చు.", Toast.LENGTH_LONG).show();
                break;
            default: act(kind);
        }
    }

    private void hideMenu() {
        if (menu == null) return;
        try { wm.removeView(menu); } catch (Exception ignored) {}
        menu = null;
        menuClosedAt = SystemClock.elapsedRealtime();
        fadeSoon();
    }

    // ================================================================ doing it

    /** Hides Jarvis's own windows for a moment, captures the screen, then shows them again. */
    private void captureThen(java.util.function.Consumer<JarvisAccessibility.Capture> then) {
        if (locked() || gone) return;
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        if (card != null) card.setVisibility(View.INVISIBLE);
        final long asked = SystemClock.elapsedRealtime();
        main.postDelayed(() -> JarvisAccessibility.capture(() -> {
            if (gone) return;
            refresh();
            JarvisAccessibility.Capture c = JarvisAccessibility.recent(5000);
            then.accept(c != null && c.time >= asked ? c : null); // an older capture is not this screen
        }), 160);
    }

    private void act(int kind) {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        lastAnswer = "";
        lastTitle = "";
        captureThen(cap -> {
            if (cap == null) { showCard("Jarvis", "స్క్రీన్ చూడలేకపోయాను. మళ్ళీ నొక్కండి.", false); return; }
            if (kind == PHOTO && cap.jpeg == null) { showCard(title(PHOTO), "ఈ ఫోన్‌లో స్క్రీన్‌షాట్ తీయలేకపోయాను (Android 11 పైన కావాలి).", false); return; }
            final String app = JarvisAccessibility.label(svc, cap.pkg);
            final boolean money = Tools.isMoneyApp(svc, cap.pkg) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage());
            final Prefs p = new Prefs(svc);
            if (kind != READ && money) {
                showCard("🔒 " + app, "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని నేను AI కి పంపను, సేవ్ కూడా చేయను. అది మీ చేతుల్లోనే ఉండాలి. '📖 చదువు' మాత్రం ఫోన్‌లోనే చదువుతుంది.", false);
                return;
            }
            boolean ai = kind != READ && kind != SAVE;
            if (ai && !p.hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
            busy = true;
            refresh();
            if (ai) showCard(title(kind), kind == PRICE || kind == VIDEO ? "చూస్తున్నాను, వెబ్‌లో వెతుకుతున్నాను…" : "చూస్తున్నాను, అర్థం చేసుకుంటున్నాను…", true);
            final String shot = cap.jpeg, seen = cap.text == null ? "" : cap.text;
            new Thread(() -> {
                String pg = cap.page(6000); // the whole page (a long one takes a moment)
                final String page = pg != null && pg.length() > seen.length() ? pg : seen;
                final JarvisAccessibility.Page pobj = cap.pageObj;
                final ScreenReader.Follow follow = pobj != null && pobj.text.equals(page) && !pobj.nodes.isEmpty() ? new PageMark(svc, pobj) : null;
                if (kind == READ || kind == SAVE) { pageDone(kind, app, page, shot, p, money, follow); return; }
                work(kind, app, page, shot, p);
            }, "jarvis-bubble").start();
        });
    }

    /** 📖 / 💾 with the page's own text (a picture with no text is read by the AI instead, outside money apps). */
    private void pageDone(int kind, String app, String page, String shot, Prefs p, boolean money, ScreenReader.Follow follow) {
        main.post(() -> {
            if (gone) return;
            busy = false;
            refresh();
            if (kind == READ && page.trim().length() < 60 && shot != null && !money && p.hasBrain()) { // a poster, a news cutting, a photo: ask first
                showCard("📖 చదువు", "ఈ స్క్రీన్‌లో అక్షరాలు చాలా తక్కువ. ఫోటోలోని అక్షరాలు చదవనా? (స్క్రీన్ ఫోటో మీ AI కి వెళ్తుంది)",
                        false, "🖼️ ఫోటో చదువు", () -> act(PHOTO));
                return;
            }
            if (page.trim().length() < 2) {
                showCard("📖 చదువు", "ఈ స్క్రీన్‌లో చదవడానికి అక్షరాలు దొరకలేదు.", false);
                return;
            }
            if (kind == SAVE) { saveLater(null, app, page); return; }
            closeCard();
            Announcer.stop(); // one voice at a time
            reader.read(app, page, follow); // the paragraph being read glows on the page, which scrolls along
        });
    }

    /** The AI part of an option, on a worker thread; the answer goes to the card and is spoken. */
    private void work(int kind, String app, String page, String shot, Prefs p) {
        String out = null, err = null;
        final String body = page.length() > 12000 ? page.substring(0, 12000) : page;
        try {
            boolean web = (kind == PRICE || kind == VIDEO) && p.webSearch();
            int max = kind == MEANING || kind == TELUGU || kind == PHOTO ? 3500 : kind == PRICE || kind == VIDEO ? 1500 : 900;
            String extra = kind == REMIND ? "\nNow: " + new java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(new java.util.Date()) : "";
            out = Brain.oneShot(p, system(kind, p.name()), "App on screen: " + app + extra + "\nText read from the screen:\n" + body, shot, web, max);
        } catch (Http.ApiError e) {
            err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
        } catch (Exception e) {
            err = "చేయలేకపోయాను: నెట్ / సమయం సమస్య.";
        }
        final String o = out, er = err;
        main.post(() -> {
            if (gone) return;
            busy = false;
            refresh();
            ctxApp = app; // a new talk about this screen (🎙️ asks more about it)
            ctxPage = body;
            ctxShot = shot;
            turns.clear();
            if (er != null || o == null || o.trim().isEmpty()) { showCard(title(kind), er != null ? er : "జవాబు రాలేదు. మళ్ళీ ప్రయత్నించండి.", false); return; }
            if (kind == REMIND) { offerReminders(o, app); return; }
            String clean = (kind == REPLY || kind == PHOTO ? o : o.replaceAll("[*#_`>]", "")).replaceAll("\n{3,}", "\n\n").trim();
            turns.add(new String[]{"Jarvis", clean});
            lastAnswer = clean;
            lastTitle = title(kind) + " · " + app;
            if (kind == REPLY) {
                replyPkg = JarvisAccessibility.currentPackage(); // ✍️ types only into this chat app
                showCard(title(kind), clean, false, "✍️ టైప్ చేయి", () -> typeReply(clean));
                speak("ఇలా జవాబు ఇవ్వొచ్చు: " + clean);
            } else if (kind == PHOTO) {
                showCard(title(kind), clean, false);
                speak(clean); // the picture's own words, each line in its language
            } else {
                showCard(title(kind), clean, false);
                speak(clean);
            }
        });
    }

    /** ⏰: the dates found on the screen, set only when he taps ✅. */
    private void offerReminders(String json, String app) {
        final java.util.List<Object[]> found = new java.util.ArrayList<>(); // {text, event label, alert ms}
        long now = System.currentTimeMillis();
        try {
            String j = json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1);
            org.json.JSONArray a = new org.json.JSONObject(j).optJSONArray("items");
            for (int i = 0; a != null && i < a.length() && found.size() < 6; i++) {
                org.json.JSONObject it = a.getJSONObject(i);
                String what = it.optString("what").trim(), date = it.optString("date").trim(), time = it.optString("time").trim();
                if (what.isEmpty() || !date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                boolean timed = time.matches("\\d{1,2}:\\d{2}");
                long event = Tools.parseLocal(date + " " + (timed ? time : "21:00")); // a day's event lasts the day
                if (event <= now) continue;
                long alert;
                if (timed) alert = event - 3600_000L; // an hour before
                else {
                    long day = Tools.parseLocal(date + " 09:00");
                    alert = it.optBoolean("due") ? day - 86_400_000L : day; // a due date: the morning before
                }
                if (alert <= now + 60_000L) alert = Math.max(now + 60_000L, Math.min(event - 10 * 60_000L, now + 5 * 60_000L)); // soon, still before it
                String label = date + (timed ? " " + time : "");
                found.add(new Object[]{what + " (" + label + ")", label, alert});
            }
        } catch (Exception ignored) {}
        if (found.isEmpty()) {
            showCard(title(REMIND), "ఈ స్క్రీన్‌లో ముందు రాబోయే తేదీ / టైమ్ ఏదీ దొరకలేదు.", false);
            return;
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.ENGLISH);
        StringBuilder b = new StringBuilder("ఇవి దొరికాయి. ✅ నొక్కితే రిమైండర్లు పెడతాను:\n");
        for (Object[] x : found) b.append("\n• ").append(x[0]).append("\n   గుర్తు: ").append(f.format(new java.util.Date((long) x[2])));
        lastAnswer = b.toString();
        lastTitle = title(REMIND) + " · " + app;
        showCard(title(REMIND), lastAnswer, false, "✅ రిమైండర్ పెట్టు", () -> {
            int n = 0;
            long t = System.currentTimeMillis();
            for (Object[] x : found) {
                long at = Math.max((long) x[2], t + 60_000L); // still ahead when he taps
                try {
                    org.json.JSONObject r = Store.get(svc).addReminder((String) x[0], at);
                    if (r != null) { Reminders.schedule(svc, r); n++; }
                } catch (Exception ignored) {}
            }
            JarvisWidget.refresh(svc);
            showCard(title(REMIND), n + " రిమైండర్లు పెట్టాను ✓\n" + lastAnswer.substring(lastAnswer.indexOf('\n') + 1), false);
            Announcer.say(svc, n + " రిమైండర్లు పెట్టాను.");
        });
        speak(found.size() + " తేదీలు దొరికాయి. రిమైండర్ పెట్టమంటే ✅ నొక్కండి.");
    }

    /** 💾 in the background (a file on the phone). */
    private void saveLater(String title, String app, String text) {
        new Thread(() -> {
            boolean ok;
            try { ReadLater.save(svc, title, app, text); ok = true; } catch (Exception e) { ok = false; }
            final boolean done = ok;
            main.post(() -> Toast.makeText(svc, done ? "💾 సేవ్ చేశాను. 'సేవ్ చేసినవి చదువు' అంటే చదువుతాను." : "సేవ్ చేయలేకపోయాను",
                    done ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
        }, "jarvis-save").start();
    }

    /** ✍️: the suggested reply goes into the chat box; he reads it and presses send himself. */
    private void typeReply(String text) {
        final String pkg = replyPkg;
        String now = JarvisAccessibility.currentPackage();
        if (pkg.isEmpty() || !pkg.equals(now) || Tools.isMoneyApp(svc, now)) {
            Toast.makeText(svc, "జవాబు అడిగిన చాట్ యాప్ ఇప్పుడు ముందు లేదు. అక్కడికి వెళ్ళి ✍️ నొక్కండి (లేదా 📋 కాపీ).", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            int r = JarvisAccessibility.typeInChat(text, pkg);
            main.post(() -> {
                if (gone) return;
                String msg = r == 1 ? "మెసేజ్ బాక్సులో పెట్టాను. చూసి మీరే పంపండి."
                        : r == -1 ? "మెసేజ్ బాక్సులో మీరు రాసింది ఉంది, దాన్ని మార్చలేదు. 📋 కాపీ చేసి పేస్ట్ చేయండి."
                        : r == -2 ? "జవాబు అడిగిన చాట్ యాప్ ఇప్పుడు ముందు లేదు."
                        : "మెసేజ్ బాక్స్ దొరకలేదు. 📋 కాపీ చేసి పేస్ట్ చేయండి.";
                Toast.makeText(svc, msg, Toast.LENGTH_LONG).show();
                if (r == 1) closeCard();
            });
        }, "jarvis-type").start();
    }

    private static String title(int kind) {
        switch (kind) {
            case MEANING: return "🧠 అర్థం";
            case ABOUT: return "💡 దీని గురించి";
            case TELUGU: return "🌐 తెలుగులో";
            case REPLY: return "💬 జవాబు సూచన";
            case SCAM: return "🛡️ మోసమా?";
            case PHOTO: return "🖼️ ఫోటోలోని అక్షరాలు";
            case REMIND: return "⏰ గుర్తుపెట్టు";
            case PRICE: return "🛒 ధర పోలిక";
            case VIDEO: return "🎬 ఈ వీడియో";
            case WORDS: return "📚 కష్టమైన పదాలు";
            default: return "Jarvis";
        }
    }

    private static String system(int kind, String name) {
        String who = "You are Jarvis, " + name + "'s assistant. He is looking at this screen on his phone (screenshot and its text are given). "
                + "Write in simple, natural Telugu (Telugu script), as plain text for reading aloud: no markdown, no lists of symbols. ";
        switch (kind) {
            case MEANING:
                return who + "He wants to truly understand it: read all of the content, understand what it means, then tell it to him in Telugu like a "
                        + "knowledgeable friend explaining it, not a word-for-word translation. Keep every important point, fact, number, date and name, in a "
                        + "natural order; explain hard words, ideas and the background simply, and say what it means for him if that matters. Skip menus, ads and "
                        + "buttons. For a long article give the full meaning in about 12-25 sentences.";
            case ABOUT:
                return who + "He asked 'దీని గురించి చెప్పు'. Say briefly what this is and what it says or shows (an article: the key points; a message: "
                        + "what it means; a bill or form: the important amounts and dates; a photo or video: what is in it), and anything he should know or do. 4-8 short sentences.";
            case TELUGU:
                return who + "Translate the main content (not menus, buttons or ads) into Telugu faithfully, sentence by sentence, keeping names and numbers. Only the translation.";
            case REPLY:
                return who + "This is a chat, message or email. Suggest ONE short, polite, natural reply he could send, in the same language the other person used "
                        + "(Telugu, English or mixed). Output only the reply text, nothing else.";
            case PHOTO:
                return "You read pictures aloud for Jarvis, " + name + "'s assistant. Read out all the text in the picture on his screen (posters, newspaper "
                        + "cuttings, images in a chat, screenshots), in reading order, exactly as written, in its own language (Telugu or English). Leave out the phone's "
                        + "status bar, the app's buttons and menus. Output only that text, one line per line of the picture, no comments. "
                        + "If the picture has no text, write one Telugu sentence saying what the picture shows.";
            case REMIND:
                return "You help Jarvis, " + name + "'s assistant, set reminders from his screen. Find upcoming dates and times on it worth a reminder: meetings, "
                        + "appointments, bill / EMI / fee due dates, journeys and tickets, events, deliveries, exams. Use the 'Now' line for the year and for "
                        + "'tomorrow' / weekdays. Reply with JSON only: {\"items\":[{\"what\":\"short Telugu text of what it is\",\"date\":\"yyyy-MM-dd\","
                        + "\"time\":\"HH:mm, or empty when no time is given\",\"due\":true when it is a last date / deadline}]}: the event's own date and time "
                        + "(not when to remind). Only things still ahead. Nothing found: {\"items\":[]}.";
            case PRICE:
                return who + "He is looking at a product in a shopping app. Identify the exact product (brand, model, size or variant) and its price here. "
                        + "Then search the web for its current price at other Indian stores (Amazon, Flipkart, Croma, Reliance Digital, JioMart, the brand's own site...) "
                        + "and tell him: the price here, the prices found elsewhere with store names, whether this is a good deal, and what to check (seller rating, "
                        + "return policy, an inflated MRP 'discount'). Say clearly when you could not find a price; never invent one. 5-9 sentences.";
            case VIDEO:
                return who + "He is watching or looking at a video (YouTube or another app). From the title, channel and description on the screen, and the web "
                        + "if needed, tell him what the video is about, its main points if known, who made it, and for news, health or money claims whether it "
                        + "seems trustworthy. 5-10 sentences.";
            case WORDS:
                return who + "List 8-12 English words or phrases on this screen that may be hard for him, one per line: the word, then ' — ', then its simple "
                        + "Telugu meaning, and if useful a very short example in English. Only the list.";
            default: // SCAM
                return who + "Check whether this message or page is a scam, fraud or fake news: asking for OTP / PIN / passwords, KYC or account-block threats, "
                        + "lottery or prize, offers too good to be true, urgent payment requests, odd links or apps to install, fake bank or government names, "
                        + "forwarded rumours. First line: one verdict: '✅ సురక్షితంగా అనిపిస్తోంది' or '⚠️ జాగ్రత్త' or '❌ మోసం లాగా ఉంది'. Then 2-4 short reasons and "
                        + "what to do. Never tell him to tap a link, call an unknown number or share a code.";
        }
    }

    /** Short answers in Jarvis's voice (natural voice if chosen); long ones with the phone's voice, which can pause and stop. */
    /** The card's answer read aloud (the phone voice, so ⏸ can pause it and ▶ go on from there). */
    private void speak(String text) {
        reader.stop();
        Announcer.stop();
        cardSpoken = text;
        boolean shown = cardText != null && cardText.getText().toString().equals(text);
        reader.read(CARD, text, shown ? cardFollow : null);
    }

    private String cardSpoken = "";

    /** In the card: the sentence being read is lit, the word being said brighter, and the card scrolls along. */
    private final ScreenReader.Follow cardFollow = new ScreenReader.Follow() {
        private int ps = -1, pe = -1;
        @Override public void onPart(int line, int start, int end) { ps = start; pe = end; paint(ps, pe, -1, -1, true); }
        @Override public void onWord(int start, int end) { paint(ps, pe, start, end, false); }
        @Override public void onQuiet() { ps = pe = -1; paint(-1, -1, -1, -1, false); }
    };

    private void paint(int ps, int pe, int ws, int we, boolean scroll) {
        if (cardText == null) return;
        String t = cardText.getText().toString();
        if (!t.equals(cardSpoken)) return; // the card shows something else now
        android.text.SpannableString sp = new android.text.SpannableString(t);
        int flag = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        if (ps >= 0 && ps < pe && pe <= t.length()) sp.setSpan(new android.text.style.BackgroundColorSpan(FaceRig.withAlpha(Ui.CYAN, 0x3A)), ps, pe, flag);
        if (ws >= 0 && ws < we && we <= t.length()) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFE27A), ws, we, flag);
            sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), ws, we, flag);
        }
        cardText.setText(sp);
        if (scroll && ps >= 0) cardText.post(() -> scrollCardTo(ps));
    }

    private void scrollCardTo(int offset) {
        if (cardText == null || cardScroll == null) return;
        android.text.Layout l = cardText.getLayout();
        if (l == null || offset > cardText.length()) return;
        int top = l.getLineTop(l.getLineForOffset(offset)) + cardText.getPaddingTop();
        int target = Math.max(0, top - dp(28));
        if (Math.abs(cardScroll.getScrollY() - target) > dp(10)) cardScroll.smoothScrollTo(0, target);
    }

    private static final String CARD = "Jarvis జవాబు";

    /** The card's answer is being read right now. */
    private boolean cardSpeaking() { return reader.active() && !reader.paused() && CARD.equals(reader.title()); }

    private void playPause() {
        if (lastAnswer.isEmpty()) return;
        if (cardSpeaking()) reader.pause();
        else if (reader.active() && reader.paused() && CARD.equals(reader.title())) reader.resume();
        else speak(lastAnswer); // finished or stopped: from the start
    }

    /** Hold the globe (or 🎙️): Jarvis's small panel opens and listens, with this screen already seen. */
    private void ask() {
        if (locked()) return;
        ScreenReader.pauseIfReading(svc); // the mic must not hear the page being read
        captureThen(cap -> {
            try {
                svc.startActivity(new Intent(svc, SheetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception e) {
                Toast.makeText(svc, "Jarvis తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================================================================ asking more about it (🎙️ in the card)

    private void listenFollowUp() {
        if (listening) { try { sr.stopListening(); } catch (Exception ignored) {} return; } // "అయిపోయింది": take what was said
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (ctxPage.isEmpty() && turns.isEmpty()) { Toast.makeText(svc, "ముందు '🧠 అర్థం చెప్పు' లాంటి ఆప్షన్ ఒకటి నొక్కండి", Toast.LENGTH_SHORT).show(); return; }
        if (svc.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showCard("🎙️ అడుగు", "మైక్ అనుమతి లేదు. Jarvis యాప్ తెరిచి మైక్ అనుమతి (Allow) ఇవ్వండి.", false);
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(svc)) {
            showCard("🎙️ అడుగు", "ఈ ఫోన్‌లో మాటలు వినే Google సేవ దొరకలేదు.", false);
            return;
        }
        reader.stop();
        Announcer.stop();
        talkSet = !MainActivity.inConversation; // the wake word and Jarvis's remarks wait while he asks
        if (talkSet) MainActivity.talking(true);
        WakeService.pause(svc); // "Hey Jarvis" listening gives the mic to this question
        releaseMic();
        {
            final android.speech.SpeechRecognizer r = android.speech.SpeechRecognizer.createSpeechRecognizer(svc);
            sr = r;
            r.setRecognitionListener(new android.speech.RecognitionListener() {
                private boolean mine() { return sr == r && listening; } // a released recognizer may still call late
                @Override public void onReadyForSpeech(android.os.Bundle b) { if (mine() && cardTitle != null) cardTitle.setText("🎙️ వింటున్నాను… అడగండి"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float db) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) {
                    if (!mine()) return;
                    doneListening();
                    if (error == android.speech.SpeechRecognizer.ERROR_AUDIO || error == android.speech.SpeechRecognizer.ERROR_CLIENT
                            || error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { askInPanel(); return; } // the mic is not ours from here
                    boolean net = error == android.speech.SpeechRecognizer.ERROR_NETWORK || error == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
                    if (cardTitle != null) cardTitle.setText(net ? "🎙️ నెట్ లేదు: మాట అర్థం చేసుకోలేకపోయాను" : "🎙️ వినిపించలేదు. మళ్ళీ 🎙️ నొక్కండి");
                    if (cardText != null && !lastAnswer.isEmpty()) cardText.setText(lastAnswer);
                }
                @Override public void onResults(android.os.Bundle b) {
                    if (!mine()) return;
                    String q = heard(b);
                    if (q.isEmpty()) { onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return; }
                    doneListening();
                    followUp(q);
                }
                @Override public void onPartialResults(android.os.Bundle b) {
                    if (!mine()) return;
                    String q = heard(b);
                    if (!q.isEmpty() && cardText != null) cardText.setText("“" + q + "”");
                }
                @Override public void onEvent(int t, android.os.Bundle b) {}
            });
        }
        String lang = new Prefs(svc).listenLang();
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, svc.getPackageName());
        listening = true;
        if (micBtn != null) micBtn.setText("✋ అయిపోయింది");
        if (cardTitle != null) cardTitle.setText("🎙️ ఒక్క క్షణం…");
        try {
            sr.startListening(i);
        } catch (Exception e) {
            doneListening();
            if (cardTitle != null) cardTitle.setText("🎙️ మైక్ తెరవలేకపోయాను");
        }
    }

    /** When this phone keeps the mic from the floating card: Jarvis's panel opens with this screen and the answer in mind. */
    private void askInPanel() {
        String page = ctxPage.length() > 3000 ? ctxPage.substring(0, 3000) + "…" : ctxPage;
        String ctx = "\n[About his screen (" + ctxApp + "): " + page + "\nJarvis told him: " + lastAnswer + "]";
        try {
            svc.startActivity(new Intent(svc, SheetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE, "ఈ పేజీ గురించి ఇంకా ఏం అడగాలనుకుంటున్నారు?")
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, "")
                    .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, ctx));
        } catch (Exception e) {
            if (cardTitle != null) cardTitle.setText("🎙️ మైక్ తెరవలేకపోయాను");
        }
    }

    private static String heard(android.os.Bundle r) {
        java.util.ArrayList<String> l = r == null ? null : r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() || l.get(0) == null ? "" : l.get(0).trim();
    }

    /** Set when this card's question marked the talk (so only this card clears it). */
    private boolean talkSet;

    private void doneListening() {
        boolean was = listening;
        listening = false;
        if (micBtn != null) micBtn.setText("🎙️ అడుగు");
        main.post(this::releaseMic); // let the phone's voice service go (not from inside its own call)
        if (talkSet) { talkSet = false; MainActivity.talking(false); }
        if (was && new Prefs(svc).wakeReady()) WakeService.resume(svc);
    }

    /** Frees the recognizer between questions, so it never holds the voice service the Jarvis panel needs. */
    private void releaseMic() {
        if (listening) return;
        android.speech.SpeechRecognizer r = sr;
        sr = null;
        if (r == null) return;
        try { r.cancel(); } catch (Exception ignored) {}
        try { r.destroy(); } catch (Exception ignored) {}
    }

    private void stopListening() {
        if (!listening) return;
        try { if (sr != null) sr.cancel(); } catch (Exception ignored) {}
        doneListening();
    }

    /** His next question about the same screen: answered with the page, the picture and what was said so far. */
    private void followUp(String q) {
        final Prefs p = new Prefs(svc);
        if (!p.hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
        busy = true;
        refresh();
        showCard("💬 " + q, "ఆలోచిస్తున్నాను…", true);
        StringBuilder talk = new StringBuilder();
        for (String[] t : turns) talk.append(t[0].equals("Jarvis") ? "Jarvis: " : "Anil: ").append(t[1]).append("\n");
        final String prompt = "App on screen: " + ctxApp + "\nText read from the screen:\n" + ctxPage
                + "\n\nWhat was said about it so far:\n" + talk + "\nAnil now asks: " + q;
        final String shot = ctxShot;
        final String system = "You are Jarvis, " + p.name() + "'s assistant. He is looking at this screen on his phone (screenshot and its text are given) "
                + "and asks a follow-up question about it, or about what you already told him. Answer in simple, natural Telugu (Telugu script) as plain "
                + "text for reading aloud, like a friend: direct and clear, short unless he asks for detail. Use the screen's content and your general "
                + "knowledge; if the screen does not say and you are not sure, say so honestly. No markdown.";
        new Thread(() -> {
            String out = null, err = null;
            try {
                out = Brain.oneShot(p, system, prompt, shot, p.webSearch(), 1500);
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చేయలేకపోయాను: నెట్ / సమయం సమస్య.";
            }
            final String o = out, er = err;
            main.post(() -> {
                if (gone) return;
                busy = false;
                refresh();
                if (er != null || o == null || o.trim().isEmpty()) { showCard("💬 " + q, er != null ? er : "జవాబు రాలేదు. మళ్ళీ 🎙️ నొక్కండి.", false); return; }
                String clean = o.replaceAll("[*#_`>]", "").replaceAll("\n{3,}", "\n\n").trim();
                turns.add(new String[]{"Anil", q});
                turns.add(new String[]{"Jarvis", clean});
                while (turns.size() > 10) turns.poll(); // the last few exchanges are enough
                lastAnswer = clean;
                lastTitle = "💬 " + q + " · " + ctxApp;
                showCard("💬 " + q, clean, false);
                speak(clean);
            });
        }, "jarvis-bubble-ask").start();
    }

    // ================================================================ the answer card

    private void showCard(String title, String text, boolean working) { showCard(title, text, working, null, null); }

    /** The card; extra = one more button for this answer (✍️ type the reply, ✅ set the reminders), or none. */
    private void showCard(String title, String text, boolean working, String extra, Runnable extraDo) {
        if (gone || locked()) return;
        if (card == null) buildCard();
        if (card == null) return;
        cardTitle.setText(title);
        cardText.setText(text);
        cardActions.setVisibility(working ? View.GONE : View.VISIBLE);
        cardRow2.setVisibility(lastAnswer.isEmpty() && extra == null ? View.GONE : View.VISIBLE); // copy / save / share need an answer
        for (int i = 1; i < cardRow2.getChildCount(); i++) cardRow2.getChildAt(i).setVisibility(lastAnswer.isEmpty() ? View.GONE : View.VISIBLE);
        if (extra == null) extraBtn.setVisibility(View.GONE);
        else {
            extraBtn.setText(extra);
            extraBtn.setOnClickListener(v -> extraDo.run());
            extraBtn.setVisibility(View.VISIBLE);
        }
    }

    private void buildCard() {
        card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5061424);
        bg.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(Ui.CYAN, 0x88));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(10), dp(10), dp(10));
        LinearLayout head = new LinearLayout(svc);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = new TextView(svc);
        grip.setText("⠿ ");
        grip.setTextColor(FaceRig.withAlpha(Ui.CYAN, 0xAA));
        grip.setTextSize(16f);
        head.addView(grip);
        head.setOnTouchListener(this::dragCard);
        cardTitle = new TextView(svc);
        cardTitle.setTextColor(Ui.CYAN);
        cardTitle.setTextSize(14f);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(cardTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = button("✕", v -> closeCard());
        head.addView(x);
        card.addView(head);
        final int maxH = (int) (screenH() * 0.42f);
        ScrollView sv = new ScrollView(svc) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)); }
        };
        cardScroll = sv;
        cardText = new TextView(svc);
        cardText.setTextColor(0xFFFFFFFF);
        cardText.setTextSize(15.5f);
        cardText.setLineSpacing(0, 1.2f);
        cardText.setPadding(0, dp(6), dp(6), dp(6));
        sv.addView(cardText);
        card.addView(sv);
        LinearLayout actions = new LinearLayout(svc);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(svc), row2 = new LinearLayout(svc);
        row1.setGravity(Gravity.END);
        row2.setGravity(Gravity.END);
        micBtn = button("🎙️ అడుగు", v -> listenFollowUp());
        micBtn.setTextColor(Ui.CYAN);
        row1.addView(micBtn);
        playBtn = button("▶ ప్లే", v -> playPause());
        playBtn.setTextColor(0xFFFFFFFF);
        row1.addView(playBtn);
        row1.addView(button("🔁 మళ్ళీ", v -> { if (!lastAnswer.isEmpty()) speak(lastAnswer); }));
        row1.addView(button("⏹", v -> { reader.stop(); Announcer.stop(); }));
        extraBtn = button("", v -> {});
        extraBtn.setTextColor(0xFF7CF5B0);
        extraBtn.setVisibility(View.GONE);
        row2.addView(extraBtn);
        row2.addView(button("📋 కాపీ", v -> {
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            if (cm != null && !lastAnswer.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("Jarvis", lastAnswer));
                Toast.makeText(svc, "కాపీ చేశాను", Toast.LENGTH_SHORT).show();
            }
        }));
        row2.addView(button("💾 సేవ్", v -> {
            if (!lastAnswer.isEmpty()) saveLater(lastTitle, ctxApp, lastAnswer);
        }));
        row2.addView(button("📤 షేర్", v -> { // he picks the person and sends it himself
            if (lastAnswer.isEmpty()) return;
            try {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lastAnswer);
                svc.startActivity(Intent.createChooser(send, "ఎవరికి పంపాలి?").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                closeCard(); // out of the way of the share screen
            } catch (Exception e) {
                Toast.makeText(svc, "షేర్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
            }
        }));
        actions.addView(row1);
        actions.addView(row2);
        cardRow2 = row2;
        cardActions = actions;
        card.addView(actions);
        final WindowManager.LayoutParams clp = new WindowManager.LayoutParams(screenW() - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        clp.gravity = Gravity.TOP | Gravity.LEFT;
        android.content.SharedPreferences sp = svc.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        clp.x = sp.getInt("card_x", dp(12));
        clp.y = sp.getInt("card_y", dp(36));
        clampCard(clp);
        cardLp = clp;
        try { wm.addView(card, clp); } catch (Exception e) { card = null; cardLp = null; }
    }

    /** Moves the card with the finger on its top bar (like the floating button). */
    private boolean dragCard(View v, MotionEvent e) {
        if (card == null || cardLp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cardDownX = e.getRawX();
                cardDownY = e.getRawY();
                cardStartX = cardLp.x;
                cardStartY = cardLp.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                cardLp.x = cardStartX + Math.round(e.getRawX() - cardDownX);
                cardLp.y = cardStartY + Math.round(e.getRawY() - cardDownY);
                clampCard(cardLp);
                try { wm.updateViewLayout(card, cardLp); } catch (Exception ignored) {}
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                svc.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putInt("card_x", cardLp.x).putInt("card_y", cardLp.y).apply();
                return true;
            default:
                return false;
        }
    }

    /** At least its top bar and a good part of it stay on the screen. */
    private void clampCard(WindowManager.LayoutParams lp) {
        int w = lp.width > 0 ? lp.width : screenW();
        int h = card != null && card.getHeight() > 0 ? card.getHeight() : dp(160);
        lp.x = Math.max(-w * 6 / 10, Math.min(screenW() - w * 4 / 10, lp.x));
        lp.y = Math.max(0, Math.min(screenH() - Math.min(h, dp(120)), lp.y));
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFD7F6FF);
        t.setTextSize(13.5f);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private void closeCard() {
        stopListening();
        if (card == null) return;
        try { wm.removeView(card); } catch (Exception ignored) {}
        card = null;
        cardTitle = null;
        cardText = null;
        cardActions = null;
        micBtn = null;
        extraBtn = null;
        cardRow2 = null;
        playBtn = null;
        cardLp = null;
        cardScroll = null;
    }
}
