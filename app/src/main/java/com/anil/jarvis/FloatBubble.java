package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The floating Jarvis button: a small round Jarvis globe over every app (it lives in the "Jarvis స్క్రీన్" accessibility
 * switch, so it needs nothing else). Drag it anywhere; it rests at the side. Tap: the five options that fit the app in
 * front (and the ones he uses most there), then "▾ ఇంకా" for all of them in groups: read it aloud (from where he taps,
 * or from where he stopped), its meaning in Telugu, write his message better, save a date / bill / contact, the way
 * there or a call, is it true, one part only, compare two, help with a form, a voice message's meaning, a group's
 * summary, share with private details hidden, search a picture... Double tap: read / pause. Hold: ask by voice.
 * Hidden on the lock screen, over Jarvis's own screens, in a full-screen video or game, in apps he hid it in, and while
 * Jarvis is using the phone. Screens of banking / payment apps are never sent to the AI.
 */
final class FloatBubble implements ScreenReader.Listener {
    static final String KEY = "bubble_on";

    static boolean on(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean(KEY, true); }

    static void set(Context c, boolean v) {
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply();
        JarvisAccessibility.syncBubble();
    }

    private static final int READ = 0, MEANING = 1, ABOUT = 2, TELUGU = 3, REPLY = 4, SCAM = 5, PHOTO = 6, REMIND = 7, PRICE = 8, VIDEO = 9,
            WORDS = 10, SAVE = 11, ASK = 12, HIDE = 13, WRITE = 14, SAVE_THIS = 15, ROUTE = 16, TRUTH = 17, READ_HERE = 18, PART = 19,
            COMPARE = 20, FORM = 21, VOICE_MSG = 22, GROUP_SUM = 23, BLUR = 24, SEARCH = 25, HIDE_APP = 26, LATER = 27, MORE = 28,
            SCAN_SCREEN = 29, CAMERA = 30;
    /** {kind, label} of every option. */
    private static final Object[][] OPTIONS = {
            {READ, "📖 చదువు"}, {READ_HERE, "👆 ఇక్కడి నుంచి చదువు"}, {MEANING, "🧠 అర్థం చెప్పు"}, {ABOUT, "💡 దీని గురించి"},
            {TELUGU, "🌐 తెలుగులో"}, {WORDS, "📚 కష్టమైన పదాలు"}, {PART, "✂️ ఈ భాగం మాత్రమే"}, {PHOTO, "🖼️ ఫోటో చదువు"},
            {SEARCH, "🔎 ఇది ఏంటి? వెతుకు"}, {SAVE, "💾 తర్వాత చదువు"},
            {REPLY, "💬 జవాబు సూచన"}, {WRITE, "✍️ రాసిపెట్టు"}, {VOICE_MSG, "🎧 వాయిస్ మెసేజ్"}, {GROUP_SUM, "👥 గ్రూప్ సారాంశం"},
            {SCAM, "🛡️ మోసమా?"}, {TRUTH, "✅ నిజమా?"}, {BLUR, "🕶️ దాచి షేర్"},
            {SAVE_THIS, "📥 దీన్ని సేవ్ చేయి"}, {ROUTE, "📍 దారి / కాల్"}, {REMIND, "⏰ గుర్తుపెట్టు"}, {FORM, "📋 ఫారమ్ సహాయం"},
            {SCAN_SCREEN, "🔬 Jarvis స్కాన్ (3D)"}, {CAMERA, "📷 Jarvis కెమెరా"},
            {PRICE, "🛒 ధర పోలిక"}, {COMPARE, "⚖️ రెండు పోల్చు"}, {VIDEO, "🎬 ఈ వీడియో"},
            {ASK, "🎙️ అడుగు"}, {HIDE_APP, "🙈 ఈ యాప్‌లో దాచు"}, {HIDE, "✕ బటన్ దాచు"}};
    /** "▾ ఇంకా": every option, in four groups (and the button's own). */
    private static final Object[][] GROUPS = {
            {"📖 చదువు, అర్థం", new int[]{READ, READ_HERE, MEANING, ABOUT, TELUGU, WORDS, PART, PHOTO, SEARCH, SAVE}},
            {"💬 మెసేజ్‌లు, జాగ్రత్త", new int[]{REPLY, WRITE, VOICE_MSG, GROUP_SUM, SCAM, TRUTH, BLUR}},
            {"🛠️ పనులు", new int[]{SAVE_THIS, ROUTE, REMIND, FORM, SCAN_SCREEN, CAMERA}},
            {"🛒 షాపింగ్, వీడియో", new int[]{PRICE, COMPARE, VIDEO}},
            {"⚙️ బటన్", new int[]{ASK, HIDE_APP, HIDE}}};

    /** For Settings: the options he can switch off. */
    static Object[][] choosable() { return OPTIONS; }

    private static String label(int kind) {
        for (Object[] o : OPTIONS) if ((int) o[0] == kind) return (String) o[1];
        return "";
    }

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
    private LinearLayout cardRow2, cardExtras;
    private ImageView cardPic;
    private float downX, downY;
    private int startX, startY;
    private TextView badge;               // 📬 messages put off for later
    private boolean fullHide;             // a full-screen video or game: out of the way
    private long lastTapUp;               // for the double tap
    private TextView extraBtn2;
    private java.util.function.Consumer<String> heardTo; // what a spoken answer is for (else a follow-up question)
    private String writePkg = "";         // ✍️: the chat whose box gets the message
    private String cmpText, cmpShot, cmpApp; // ⚖️: the first of the two
    private long cmpAt;

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
        badge = new TextView(svc);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(0xFFFF3B5C);
        badge.setBackground(bb);
        badge.setVisibility(View.GONE);
        bubble.addView(badge, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP | Gravity.END));
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
        LaterMessages.listener = n -> main.post(this::showBadge);
        showBadge();
        main.postDelayed(fullCheck, 2000);
        return true;
    }

    /** The count of messages put off for later, on the globe. */
    private void showBadge() {
        if (badge == null) return;
        int n = LaterMessages.count(svc);
        badge.setText(n > 9 ? "9+" : String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    /** Every 2 s: a full-screen video or game hides the button; it comes back with the status bar. */
    private final Runnable fullCheck = new Runnable() {
        @Override public void run() {
            if (bubble == null || gone) return;
            boolean f = !away && JarvisAccessibility.barsHidden();
            if (f != fullHide) { fullHide = f; refresh(); }
            main.postDelayed(this, 2000);
        }
    };

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
        BoxPicker.cancel();
        if (LaterMessages.listener != null) LaterMessages.listener = null;
        hideMenu();
        closeCard();
        if (reader.listener == this) reader.listener = null;
        try { if (sr != null) sr.destroy(); } catch (Exception ignored) {}
        sr = null;
        try { svc.unregisterReceiver(screen); } catch (Exception ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        bubble = null;
        orb = null;
        blurBmp = null;
        main.removeCallbacksAndMessages(null); // fade, recheck, hold-to-talk, a capture on its way
    }

    /** The app in front changed: hidden on the lock screen and over Jarvis's own screens. */
    void onWindow(String pkg, String cls) {
        boolean jarvis = pkg != null && pkg.equals(svc.getPackageName()) && cls != null && cls.endsWith("Activity");
        if (pkg != null && pkg.equals(svc.getPackageName()) && !jarvis) return; // our own floating windows
        boolean lock = locked();
        // a keyboard or the notification shade opening says nothing about the app under it
        if (!lock && (JarvisAccessibility.isKeyboard(pkg) || "com.android.systemui".equals(pkg))) return;
        away = jarvis || lock || (pkg != null && new Prefs(svc).bubbleHiddenApps().contains(pkg)); // (🙈 hidden in this app)
        refresh();
        showBadge(); // (old ones expire)
        main.removeCallbacks(recheck);
        if (away) main.postDelayed(recheck, 2000);
    }

    /** While hidden: back as soon as Jarvis's screen is gone and the phone is unlocked. */
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            if (bubble == null || !away) return;
            boolean locked = locked();
            boolean jarvis = false, hidden = false;
            try {
                android.view.accessibility.AccessibilityNodeInfo r = svc.getRootInActiveWindow();
                jarvis = r != null && r.getPackageName() != null && svc.getPackageName().contentEquals(r.getPackageName());
                hidden = r != null && r.getPackageName() != null && new Prefs(svc).bubbleHiddenApps().contains(r.getPackageName().toString());
            } catch (Exception ignored) {}
            away = locked || jarvis || hidden;
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
        boolean hide = away || held || fullHide;
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
                else if (!longPressed) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastTapUp < 300) { // double tap: read this page / pause / go on (the single tap waits as long)
                        lastTapUp = 0;
                        main.removeCallbacks(singleTap);
                        hideMenu();
                        if (reader.active()) reader.toggle(); else option(READ);
                    } else {
                        lastTapUp = now;
                        final boolean wasOpen = menuWasOpen;
                        singleTapOpen = wasOpen;
                        main.removeCallbacks(singleTap);
                        main.postDelayed(singleTap, 300);
                    }
                }
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

    private boolean singleTapOpen;
    /** One tap (no second one came): the menu opens, or closes if it was open. */
    private final Runnable singleTap = () -> { if (singleTapOpen) hideMenu(); else showMenu(); };

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

    /** What kind of app is in front. */
    private static String category(String pkg) {
        String p = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("whatsapp")) return "whatsapp";
        if (p.contains("telegram") || p.contains("signal") || p.contains("orca") || p.contains("mlite") || p.contains("discord")) return "chat";
        if (p.contains("android.gm") || p.contains("mail") || p.contains("outlook")) return "mail";
        if (p.contains("messag") || p.contains("mms") || p.contains("sms")) return "sms";
        if (p.contains("amazon") || p.contains("flipkart") || p.contains("meesho") || p.contains("myntra") || p.contains("ajio") || p.contains("jiomart")
                || p.contains("bigbasket") || p.contains("grofers") || p.contains("zepto") || p.contains("nykaa") || p.contains("tatacliq")
                || p.contains("croma") || p.contains("reliance") || p.contains("snapdeal") || p.contains("swiggy") || p.contains("zomato")) return "shop";
        if (p.contains("youtube") || p.contains("mxtech") || p.contains("hotstar") || p.contains("netflix") || p.contains("primevideo")
                || p.contains("jiocinema") || p.contains("zee5") || p.contains("sonyliv") || p.contains("video")) return "video";
        if (p.contains("gallery") || p.contains("photos") || p.contains("camera")) return "photo";
        if (p.contains("facebook") || p.contains("instagram") || p.contains("twitter") || p.contains("com.x.") || p.contains("sharechat")
                || p.contains("moj") || p.contains("josh") || p.contains("linkedin") || p.contains("reddit") || p.contains("threads")
                || p.contains("snapchat")) return "social";
        if (p.contains("chrome") || p.contains("browser") || p.contains("firefox") || p.contains("opera") || p.contains("emmx") || p.contains("brave")
                || p.contains("news") || p.contains("eterno") || p.contains("inshorts") || p.contains("eenadu") || p.contains("sakshi") || p.contains("way2")
                || p.contains("dailyhunt")) return "web";
        return "other";
    }

    /** The five that fit each kind of app, best first. */
    private static int[] defaults(String cat) {
        switch (cat) {
            case "whatsapp": return new int[]{REPLY, WRITE, VOICE_MSG, GROUP_SUM, TRUTH};
            case "chat": return new int[]{REPLY, WRITE, MEANING, TELUGU, SCAM};
            case "mail": return new int[]{WRITE, REPLY, MEANING, SAVE_THIS, SCAM};
            case "sms": return new int[]{SCAM, SAVE_THIS, REPLY, WRITE, MEANING};
            case "shop": return new int[]{PRICE, COMPARE, ABOUT, SCAM, SEARCH};
            case "video": return new int[]{VIDEO, SEARCH, ABOUT, MEANING, HIDE_APP};
            case "photo": return new int[]{SEARCH, PHOTO, PART, ABOUT, BLUR};
            case "social": return new int[]{SEARCH, TRUTH, MEANING, TELUGU, VIDEO};
            case "web": return new int[]{READ, READ_HERE, MEANING, PART, TELUGU};
            default: return new int[]{READ, MEANING, ABOUT, SAVE_THIS, PART};
        }
    }

    private android.content.SharedPreferences uses() { return svc.getSharedPreferences("jarvis_bubble_use", Context.MODE_PRIVATE); }

    /** He used this option in this kind of app (the ones he uses most come up to the top five). */
    private void used(String cat, int kind) {
        if (kind == MORE || kind == LATER || kind == HIDE || kind == HIDE_APP) return;
        android.content.SharedPreferences sp = uses();
        String k = cat + ":" + kind;
        sp.edit().putInt(k, Math.min(50, sp.getInt(k, 0) + 1)).apply();
    }

    /** The top five here: the defaults for this kind of app, overtaken by what he really uses; switched-off ones left out. */
    private int[] topFor(String cat, java.util.Set<String> off) {
        int[] def = defaults(cat);
        android.content.SharedPreferences sp = uses();
        java.util.List<int[]> scored = new java.util.ArrayList<>(); // {kind, score, order}
        for (int i = 0; i < OPTIONS.length; i++) {
            int k = (int) OPTIONS[i][0];
            if (off.contains(String.valueOf(k)) || k == HIDE) continue;
            int score = 0;
            for (int j = 0; j < def.length; j++) if (def[j] == k) score += def.length - j + 1; // 6, 5, 4, 3, 2
            if (k != HIDE_APP) score += 2 * sp.getInt(cat + ":" + k, 0);
            if (score > 0) scored.add(new int[]{k, score, i});
        }
        scored.sort((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[2], b[2]));
        java.util.List<Integer> top = new java.util.ArrayList<>();
        for (int[] s : scored) if (top.size() < 5) top.add(s[0]);
        for (int k : new int[]{READ, MEANING, ABOUT, SAVE_THIS, PART}) // some switched off: filled up
            if (top.size() < 5 && !top.contains(k) && !off.contains(String.valueOf(k))) top.add(k);
        int[] out = new int[top.size()];
        for (int i = 0; i < out.length; i++) out[i] = top.get(i);
        return out;
    }

    /** The app the options are for (not the keyboard or the notification shade over it). */
    private static String frontApp() {
        String front = JarvisAccessibility.frontPackage();
        if (JarvisAccessibility.isKeyboard(front) || "com.android.systemui".equals(front)) front = JarvisAccessibility.currentPackage();
        return front;
    }

    private boolean menuMore;

    private void showMenu() { showMenu(false); }

    /** more: every option, in groups ("▾ ఇంకా"); else the five that fit this app. */
    private void showMenu(boolean more) {
        if (bubble == null || menu != null) return;
        menuMore = more;
        showBadge();
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
            LinearLayout row2 = new LinearLayout(svc); // ⏲️ the sleep timer: off → 15 → 30 → 60 minutes → off
            row2.setGravity(Gravity.CENTER_VERTICAL);
            int left = reader.sleepLeft();
            TextView timer = control(left > 0 ? "⏲️ " + left + " ని. (మార్చు)" : "⏲️ నిద్ర టైమర్", () -> {
                int now = reader.sleepLeft();
                int next = now <= 0 ? 15 : now <= 15 ? 30 : now <= 30 ? 60 : 0;
                reader.sleepIn(next);
                Toast.makeText(svc, next == 0 ? "నిద్ర టైమర్ ఆఫ్" : next + " నిమిషాల తర్వాత చదవడం ఆపేస్తాను", Toast.LENGTH_SHORT).show();
            });
            timer.setTextSize(13f);
            row2.addView(timer);
            items.addView(row2);
        }
        // the options: the five that fit this app (lit), then "▾ ఇంకా" for all of them in groups; two to a row
        Prefs p = new Prefs(svc);
        java.util.Set<String> off = p.bubbleOff();
        boolean all = more || p.bubbleShowAll();
        String cat = category(frontApp());
        int room = screenW() - size - dp(26); // beside the button
        chipW = Math.min(dp(134), room / 2 - dp(6));
        int cols = chipW < dp(96) ? 1 : 2;
        if (cols == 1) chipW = Math.min(dp(200), room - dp(12));
        int wide = cols * chipW + (cols - 1) * dp(6);
        int later = LaterMessages.count(svc);
        if (later > 0) {
            TextView c = chip("📬 " + later + (later == 1 ? " చాట్ మెసేజ్‌లు" : " చాట్‌ల మెసేజ్‌లు") + " చదువు", true, () -> option(LATER));
            c.getLayoutParams().width = wide;
            items.addView(c);
        }
        int[] top = topFor(cat, off);
        addChips(top, true, cols);
        if (!all) {
            TextView m = chip("▾ ఇంకా (అన్ని ఆప్షన్లు)", false, () -> showMenu(true));
            m.getLayoutParams().width = wide;
            m.setGravity(Gravity.CENTER);
            m.setTextColor(Ui.CYAN);
            items.addView(m);
        } else {
            java.util.Set<Integer> shown = new java.util.HashSet<>();
            for (int k : top) shown.add(k);
            for (Object[] g : GROUPS) {
                java.util.List<Integer> ks = new java.util.ArrayList<>();
                for (int k : (int[]) g[1]) if (!shown.contains(k) && !off.contains(String.valueOf(k))) ks.add(k);
                if (ks.isEmpty()) continue;
                TextView h = new TextView(svc);
                h.setText((String) g[0]);
                h.setTextColor(FaceRig.withAlpha(Ui.CYAN, 0xCC));
                h.setTextSize(12f);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(dp(8), dp(8), dp(4), dp(1));
                items.addView(h);
                int[] arr = new int[ks.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = ks.get(i);
                addChips(arr, false, cols);
            }
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

    /** These options as chips, cols to a row. */
    private void addChips(int[] kinds, boolean lit, int cols) {
        LinearLayout row = null;
        for (int i = 0; i < kinds.length; i++) {
            if (i % cols == 0) { row = new LinearLayout(svc); items.addView(row); }
            final int kind = kinds[i];
            String l = kind == COMPARE && cmpText != null ? "⚖️ రెండోది పోల్చు" : label(kind);
            row.addView(chip(l, lit, () -> option(kind)));
        }
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
            main.postDelayed(() -> { if (menu != null && !away && !held) { boolean m = menuMore; hideMenu(); showMenu(m); } }, 150); // stays open with the new state
        });
        return t;
    }

    private void option(int kind) {
        used(category(frontApp()), kind);
        if (kind != ASK && kind != HIDE && kind != HIDE_APP) heardTo = null; // a new job: a spoken answer is no longer for the old one
        if (kind != BLUR) blurBmp = null;
        switch (kind) {
            case ASK: ask(); break;
            case HIDE:
                set(svc, false);
                Toast.makeText(svc, "Jarvis బటన్ దాచాను. సెట్టింగ్స్ → ఫ్లోటింగ్ బటన్ లో మళ్ళీ ఆన్ చేయొచ్చు.", Toast.LENGTH_LONG).show();
                break;
            case HIDE_APP: hideHere(); break;
            case LATER: readLaterMessages(); break;
            case READ_HERE: readHere(); break;
            case PART:
            case SEARCH: boxThen(kind); break;
            case WRITE: write(); break;
            case SCAN_SCREEN: scanScreen(); break;
            case CAMERA: JarvisCamera.open(svc, null, null); break;
            case COMPARE: compare(); break;
            case VOICE_MSG: voiceMsg(); break;
            case BLUR: blurShare(); break;
            default: act(kind);
        }
    }

    /** 🙈: never over this app again (Settings → ఫ్లోటింగ్ బటన్ → 🙈 brings it back). */
    private void hideHere() {
        String pkg = frontApp();
        if (pkg == null || pkg.isEmpty() || pkg.equals(svc.getPackageName())) return;
        Prefs p = new Prefs(svc);
        java.util.Set<String> h = p.bubbleHiddenApps();
        h.add(pkg);
        p.setBubbleHiddenApps(h);
        Toast.makeText(svc, "🙈 " + JarvisAccessibility.label(svc, pkg) + " లో బటన్ దాచాను. సెట్టింగ్స్ → ఫ్లోటింగ్ బటన్ → 🙈 లో మళ్ళీ చూపించొచ్చు.",
                Toast.LENGTH_LONG).show();
        away = true;
        closeCard();
        refresh();
        main.removeCallbacks(recheck);
        main.postDelayed(recheck, 2000);
    }

    /** 📬: the messages put off on the top card, read out (then the dot goes). */
    private void readLaterMessages() {
        String all = LaterMessages.spoken(svc);
        LaterMessages.clear(svc);
        if (all.isEmpty()) { Toast.makeText(svc, "తర్వాత చదవాల్సిన మెసేజ్‌లు ఏవీ లేవు", Toast.LENGTH_SHORT).show(); return; }
        lastAnswer = all;
        lastTitle = "📬 తర్వాత చదవమన్న మెసేజ్‌లు";
        ctxApp = "మెసేజ్‌లు";
        ctxPage = all;
        ctxShot = null;
        turns.clear();
        showCard("📬 మెసేజ్‌లు", all, false);
        speak(all);
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
        TopCard.dismissNow(); // someone's message on the top card is not part of this screen
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
            if (ai) showCard(title(kind), web(kind) ? "చూస్తున్నాను, వెబ్‌లో వెతుకుతున్నాను…" : "చూస్తున్నాను, అర్థం చేసుకుంటున్నాను…", true);
            final String shot = cap.jpeg, seen = cap.text == null ? "" : cap.text;
            new Thread(() -> {
                String pg = cap.page(6000); // the whole page (a long one takes a moment)
                final String page = pg != null && pg.length() > seen.length() ? pg : seen;
                final JarvisAccessibility.Page pobj = cap.pageObj;
                final ScreenReader.Follow follow = pobj != null && pobj.text.equals(page) && !pobj.nodes.isEmpty() ? new PageMark(svc, pobj) : null;
                if (kind == READ || kind == SAVE) { pageDone(kind, app, cap.pkg, page, shot, p, money, follow); return; }
                work(kind, app, page, shot, p);
            }, "jarvis-bubble").start();
        });
    }

    /** 📖 / 💾 with the page's own text (a picture with no text is read by the AI instead, outside money apps). */
    private void pageDone(int kind, String app, String pkg, String page, String shot, Prefs p, boolean money, ScreenReader.Follow follow) {
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
            String place = ReadPlaces.find(svc, pkg, page);
            if (place != null) { // he stopped in the middle of this page before
                showCard("📖 చదువు", "ఈ పేజీని ఇంతకు ముందు మధ్యలో ఆపారు, ఇక్కడ:\n“" + place + "…”\n\nఅక్కడి నుంచి చదవనా?", false,
                        "▶ అక్కడి నుంచి", () -> readPage(app, pkg, page, follow, 0, place),
                        "⏮ మొదటి నుంచి", () -> { ReadPlaces.forget(svc, pkg, page); readPage(app, pkg, page, follow, 0, null); });
                return;
            }
            readPage(app, pkg, page, follow, 0, null);
        });
    }

    /** Reads the page aloud from this line (or this place), remembering where it stops. */
    private void readPage(String app, String pkg, String page, ScreenReader.Follow follow, int fromLine, String fromPlace) {
        closeCard();
        Announcer.stop(); // one voice at a time
        // the paragraph being read glows on the page, which scrolls along; where it stops is kept (never for a bank / payment app)
        reader.readAt(app, page, follow, fromLine, fromPlace, Tools.isMoneyApp(svc, pkg) ? null : pkg);
    }

    private static boolean web(int kind) { return kind == PRICE || kind == VIDEO || kind == TRUTH || kind == SEARCH; }

    /** The AI part of an option, on a worker thread; the answer goes to the card and is spoken. */
    private void work(int kind, String app, String page, String shot, Prefs p) {
        String out = null, err = null;
        final String body = page.length() > 12000 ? page.substring(0, 12000) : page;
        try {
            boolean web = web(kind) && p.webSearch();
            int max = kind == MEANING || kind == TELUGU || kind == PHOTO || kind == FORM ? 3500
                    : kind == PRICE || kind == VIDEO || kind == TRUTH || kind == SEARCH || kind == COMPARE || kind == GROUP_SUM || kind == PART ? 1800 : 900;
            String extra = kind == REMIND || kind == SAVE_THIS ? "\nNow: " + new java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(new java.util.Date()) : "";
            String what = kind == PART ? "\nText in the part he marked:\n" : kind == COMPARE ? "\nThe two things:\n" : "\nText read from the screen:\n";
            out = Brain.oneShot(p, system(kind, p), "App on screen: " + app + extra + what + body, shot, web, max);
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
            if (kind == SAVE_THIS) { offerSave(o, app); return; }
            if (kind == ROUTE) { offerRoute(o, app); return; }
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

    // ================================================================ 📥 save this, 📍 the way / a call

    /**
     * ID-like numbers never get saved: any run of 8+ digits (Aadhaar, account, policy, card numbers), PAN, passport and voter
     * ID shapes, and whatever follows words like policy / account / customer / card.
     */
    static String noIds(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?i)\\b(policy|pol|a/c|acct|account|customer|cust|aadhaa?r|pan|passport|voter|epic|card|cif|crn|uan|member|consumer|ref)\\b"
                + "(\\s*(no\\.?|number|num|id|#))?\\s*[:.#-]?\\s*[A-Za-z0-9/-]*\\d[A-Za-z0-9/-]*", "$1 ••••");
        java.util.regex.Matcher m = LONG_DIGITS.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) { // a date or a mobile number stays; any other long number goes
            String g = m.group(), d = g.replaceAll("\\D", "");
            boolean keep = DATE.matcher(g.trim()).matches() || d.matches("(?:91|0)?[6-9]\\d{9}");
            m.appendReplacement(b, keep ? java.util.regex.Matcher.quoteReplacement(g) : "••••");
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\b[A-Z]{5}\\d{4}[A-Z]\\b|\\b[A-Z]{3}\\d{7}\\b|\\b[A-Z]\\d{7}\\b", "••••").trim();
    }

    private static final java.util.regex.Pattern LONG_DIGITS = java.util.regex.Pattern.compile("\\d(?:[\\s/-]?\\d){7,}");

    /** 📥: what the AI found, saved only when he taps ✅ (a date or a contact opens the Calendar / Contacts app to save there). */
    private void offerSave(String json, String app) {
        final java.util.List<org.json.JSONObject> found = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1)).optJSONArray("items");
            for (int i = 0; a != null && i < a.length() && found.size() < 3; i++) found.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        StringBuilder b = new StringBuilder();
        org.json.JSONObject opens = null; // the one that opens another app to save
        int direct = 0;
        for (org.json.JSONObject it : found) {
          try {
            String t = it.optString("type");
            switch (t) {
                case "event": {
                    String date = it.optString("date"), time = it.optString("time");
                    if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                    b.append("\n📅 ").append(noIds(it.optString("title"))).append(" · ").append(date).append(time.matches("\\d{1,2}:\\d{2}") ? " " + time : "")
                            .append(it.optString("place").isEmpty() ? "" : " · " + noIds(it.optString("place"))).append(opens == null ? "  (క్యాలెండర్‌లో)" : "");
                    if (opens == null) opens = it;
                    break;
                }
                case "contact": {
                    String ph = it.optString("phone").replaceAll("[^0-9+]", "");
                    if (ph.replace("+", "").length() > 13) ph = ""; // not a phone number
                    it.put("phone", ph);
                    if (it.optString("name").isEmpty() || (ph.isEmpty() && it.optString("email").isEmpty())) continue;
                    b.append("\n👤 ").append(it.optString("name")).append(ph.isEmpty() ? "" : " · " + ph)
                            .append(it.optString("email").isEmpty() ? "" : " · " + it.optString("email")).append(opens == null ? "  (కాంటాక్ట్స్‌లో)" : "");
                    if (opens == null) opens = it;
                    break;
                }
                case "expense": {
                    double amt = it.optDouble("amount", 0);
                    if (!(amt > 0) || amt > 10_000_000) continue;
                    b.append("\n💰 ₹").append(amt == Math.rint(amt) ? String.valueOf((long) amt) : String.format(java.util.Locale.ENGLISH, "%.2f", amt))
                            .append(" · ").append(noIds(it.optString("what"))).append(it.optString("shop").isEmpty() ? "" : " · " + noIds(it.optString("shop")))
                            .append("  (ఖర్చుల్లో)");
                    direct++;
                    break;
                }
                case "parcel": {
                    String date = it.optString("date");
                    b.append("\n📦 ").append(noIds(it.optString("what"))).append(date.matches("\\d{4}-\\d{2}-\\d{2}") ? " · " + date + "  (ఆ రోజు ఉదయం గుర్తు)" : "  (నోట్‌గా)");
                    direct++;
                    break;
                }
                default: {
                    if (noIds(it.optString("text")).isEmpty()) continue;
                    it.put("type", "note");
                    b.append("\n📝 ").append(noIds(it.optString("text"))).append("  (నోట్స్‌లో)");
                    direct++;
                }
            }
          } catch (Exception ignored) {}
        }
        if (b.length() == 0) { showCard(title(SAVE_THIS), "ఈ స్క్రీన్‌లో సేవ్ చేయదగ్గది (తేదీ, బిల్లు, కాంటాక్ట్, పార్సెల్) ఏదీ దొరకలేదు.", false); return; }
        lastAnswer = "ఇవి సేవ్ చేయొచ్చు. ✅ నొక్కితే చేస్తాను:\n" + b;
        lastTitle = title(SAVE_THIS) + " · " + app;
        final org.json.JSONObject open = opens;
        final int directN = direct;
        showCard(title(SAVE_THIS), lastAnswer, false, "✅ సేవ్ చేయి", () -> {
            int n = 0;
            for (org.json.JSONObject it : found) {
                try {
                    switch (it.optString("type")) {
                        case "expense": {
                            double amt = it.optDouble("amount", 0);
                            if (!(amt > 0) || amt > 10_000_000) break;
                            String d = it.optString("date");
                            long when = d.matches("\\d{4}-\\d{2}-\\d{2}") ? Tools.parseLocal(d + " 12:00") : System.currentTimeMillis();
                            String cat = it.optString("category").toLowerCase(java.util.Locale.ROOT);
                            if (!cat.matches("food|groceries|fuel|bills|shopping|travel|health|other")) cat = "other";
                            Money.add(svc, amt, noIds(it.optString("what")), noIds(it.optString("shop")), cat, when > 0 ? when : System.currentTimeMillis());
                            n++;
                            break;
                        }
                        case "parcel": {
                            String d = it.optString("date"), what = "📦 " + noIds(it.optString("what")) + " డెలివరీ";
                            long at = d.matches("\\d{4}-\\d{2}-\\d{2}") ? Tools.parseLocal(d + " 09:00") : 0;
                            if (at > System.currentTimeMillis() + 60_000L) {
                                org.json.JSONObject r = Store.get(svc).addReminder(what, at);
                                if (r != null) { Reminders.schedule(svc, r); n++; }
                            } else {
                                Notes.add(svc, "notes", new org.json.JSONObject().put("id", Notes.id("n")).put("text", what).put("t", System.currentTimeMillis()), 2000);
                                n++;
                            }
                            break;
                        }
                        case "note": {
                            String text = noIds(it.optString("text"));
                            if (text.isEmpty()) break;
                            Notes.add(svc, "notes", new org.json.JSONObject().put("id", Notes.id("n")).put("text", text + " (" + app + ")").put("t", System.currentTimeMillis()), 2000);
                            n++;
                            break;
                        }
                        default: break;
                    }
                } catch (Exception ignored) {}
            }
            JarvisWidget.refresh(svc);
            String done = directN > 0 ? n + " సేవ్ చేశాను ✓" : "";
            if (open != null && openToSave(open)) {
                Toast.makeText(svc, (done.isEmpty() ? "" : done + ". ") + "అక్కడ 'Save' నొక్కండి.", Toast.LENGTH_LONG).show();
                closeCard();
            } else {
                showCard(title(SAVE_THIS), (done.isEmpty() ? "సేవ్ చేయలేకపోయాను." : done) + "\n" + b, false);
            }
        });
        speak(found.size() == 1 ? "ఇది సేవ్ చేయొచ్చు. ✅ నొక్కండి." : "ఇవి సేవ్ చేయొచ్చు. ✅ నొక్కండి.");
    }

    /** A date in the Calendar app, a person in the Contacts app: filled in there; he saves it himself. */
    private boolean openToSave(org.json.JSONObject it) {
        try {
            Intent i;
            if ("event".equals(it.optString("type"))) {
                String date = it.optString("date"), time = it.optString("time");
                boolean timed = time.matches("\\d{1,2}:\\d{2}");
                long begin = Tools.parseLocal(date + " " + (timed ? time : "00:00"));
                i = new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, noIds(it.optString("title")));
                if (begin > 0) i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + (timed ? 3600_000L : 86_400_000L))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, !timed);
                if (!it.optString("place").isEmpty()) i.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, noIds(it.optString("place")));
            } else {
                i = new Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, it.optString("name"));
                if (!it.optString("phone").isEmpty()) i.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it.optString("phone"));
                if (!it.optString("email").isEmpty()) i.putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it.optString("email"));
                if (!it.optString("address").isEmpty()) i.putExtra(android.provider.ContactsContract.Intents.Insert.POSTAL, noIds(it.optString("address")));
            }
            svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 📍: the first place opens in Maps (the way there), the first number in the dialer (he presses call himself). */
    private void offerRoute(String json, String app) {
        String place = null, placeName = null, number = null, who = null;
        StringBuilder b = new StringBuilder();
        try {
            org.json.JSONObject o = new org.json.JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1));
            org.json.JSONArray pl = o.optJSONArray("places"), ph = o.optJSONArray("phones");
            for (int i = 0; pl != null && i < pl.length() && i < 3; i++) {
                org.json.JSONObject x = pl.getJSONObject(i);
                String a = x.optString("address").trim(), n = x.optString("name").trim();
                if (a.isEmpty() && n.isEmpty()) continue;
                b.append("\n📍 ").append(n.isEmpty() ? a : n + (a.isEmpty() ? "" : ": " + a));
                if (place == null) { place = (n + " " + a).trim(); placeName = n.isEmpty() ? a : n; }
            }
            for (int i = 0; ph != null && i < ph.length() && i < 3; i++) {
                org.json.JSONObject x = ph.getJSONObject(i);
                String num = x.optString("number").replaceAll("[^0-9+]", "");
                if (num.replace("+", "").length() < 3 || num.replace("+", "").length() > 13) continue;
                b.append("\n📞 ").append(x.optString("who").trim().isEmpty() ? "" : x.optString("who").trim() + ": ").append(num);
                if (number == null) { number = num; who = x.optString("who").trim(); }
            }
        } catch (Exception ignored) {}
        if (place == null && number == null) { showCard(title(ROUTE), "ఈ స్క్రీన్‌లో చిరునామా గానీ ఫోన్ నంబర్ గానీ దొరకలేదు.", false); return; }
        lastAnswer = "దొరికినవి:" + b;
        lastTitle = title(ROUTE) + " · " + app;
        final String go = place, call = number;
        String goLabel = go == null ? null : "🧭 దారి" + (placeName.length() <= 14 ? ": " + placeName : "");
        String callLabel = call == null ? null : "📞 కాల్" + (who != null && !who.isEmpty() && who.length() <= 12 ? ": " + who : "");
        Runnable goDo = go == null ? null : () -> {
            Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(go))).setPackage("com.google.android.apps.maps");
            try { svc.startActivity(nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeCard(); return; } catch (Exception ignored) {}
            try { svc.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(go))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeCard(); }
            catch (Exception e) { Toast.makeText(svc, "మ్యాప్స్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); }
        };
        Runnable callDo = call == null ? null : () -> { // the dialer opens with the number; he presses call
            try { svc.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + call)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeCard(); }
            catch (Exception e) { Toast.makeText(svc, "డయలర్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); }
        };
        if (go != null) showCard(title(ROUTE), lastAnswer, false, goLabel, goDo, callLabel, callDo);
        else showCard(title(ROUTE), lastAnswer, false, callLabel, callDo);
    }

    // ================================================================ 👆 read from here, ✂️ one part, 🔎 what is this

    /** 👆: he taps the paragraph; the page is read aloud from there. */
    private void readHere() {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        captureThen(cap -> {
            if (cap == null) { showCard("Jarvis", "స్క్రీన్ చూడలేకపోయాను. మళ్ళీ నొక్కండి.", false); return; }
            final String app = JarvisAccessibility.label(svc, cap.pkg);
            closeCard();
            BoxPicker.pick(svc, true, "ఎక్కడి నుంచి చదవాలో అక్కడ నొక్కండి", null, r -> {
                if (r == null || gone || locked()) return;
                busy = true;
                refresh();
                new Thread(() -> {
                    String pg = cap.page(6000);
                    final JarvisAccessibility.Page pobj = cap.pageObj;
                    final String page = pg == null || pg.isEmpty() ? cap.text : pg;
                    int line = 0;
                    if (pobj != null && pobj.text.equals(page)) {
                        int best = -1, below = -1;
                        long bestArea = Long.MAX_VALUE;
                        int belowTop = Integer.MAX_VALUE;
                        Rect b = new Rect();
                        for (int i = 0; i < pobj.nodes.size(); i++) {
                            try { pobj.nodes.get(i).getBoundsInScreen(b); } catch (Exception e) { continue; }
                            if (b.contains(r.left, r.top)) {
                                long area = (long) b.width() * b.height();
                                if (area < bestArea) { bestArea = area; best = i; }
                            } else if (b.top >= r.top && b.top < belowTop) { belowTop = b.top; below = i; }
                        }
                        line = best >= 0 ? best : Math.max(0, below);
                    }
                    final int from = line;
                    final ScreenReader.Follow follow = pobj != null && pobj.text.equals(page) && !pobj.nodes.isEmpty() ? new PageMark(svc, pobj) : null;
                    main.post(() -> {
                        busy = false;
                        refresh();
                        if (gone || locked()) return;
                        if (page == null || page.trim().length() < 2) { showCard("📖 చదువు", "ఈ స్క్రీన్‌లో చదవడానికి అక్షరాలు దొరకలేదు.", false); return; }
                        readPage(app, cap.pkg, page, follow, from, null);
                    });
                }, "jarvis-read-here").start();
            });
        });
    }

    /**
     * 🔬: the screen as it is (an endoscope's or a thermal camera's app, a photo, a video paused on a board) goes into the
     * Jarvis camera, where everything works on it: asking, boxes, the 3D hologram, the PDF. Never a bank / payment app's screen.
     */
    private void scanScreen() {
        final String pkg = JarvisAccessibility.appPackage();
        if (pkg.isEmpty()) { // (a banking app may hide itself from screen reading): unknown is not sent
            showCard("🔬 Jarvis స్కాన్", "ఈ స్క్రీన్ ఏ యాప్‌దో తెలియలేదు, అందుకే సురక్షితం కోసం పంపను.", false);
            return;
        }
        if (Tools.isMoneyApp(svc, pkg) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage())) {
            showCard("🔒", "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని నేను AI కి పంపను. అది మీ చేతుల్లోనే ఉండాలి.", false);
            return;
        }
        closeCard();
        hideOwnThen(() -> JarvisAccessibility.shot(null, 2000, bmp -> {
            refresh();
            String now = JarvisAccessibility.appPackage(); // the same app still in front (never a bank's screen that came up meanwhile)
            if (!now.equals(pkg) || Tools.isMoneyApp(svc, now)) {
                if (bmp != null) bmp.recycle();
                showCard("🔬 Jarvis స్కాన్", "స్క్రీన్ మారిపోయింది. మళ్ళీ నొక్కండి.", false);
                return;
            }
            if (bmp == null) { showCard("🔬 Jarvis స్కాన్", "స్క్రీన్‌షాట్ తీయలేకపోయాను (Android 11 పైన కావాలి).", false); return; }
            if (dark(bmp)) { bmp.recycle(); showCard("🔬 Jarvis స్కాన్", "ఈ యాప్ స్క్రీన్‌షాట్ ఇవ్వదు (నల్లగా వచ్చింది).", false); return; }
            java.io.File f = new java.io.File(svc.getCacheDir(), "screen_scan.jpg");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, o);
            } catch (Exception e) {
                showCard("🔬 Jarvis స్కాన్", "సేవ్ చేయలేకపోయాను.", false);
                return;
            } finally {
                bmp.recycle();
            }
            try {
                svc.startActivity(new Intent(svc, JarvisCamera.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(JarvisCamera.EXTRA_IMAGE, f.getPath()));
            } catch (Exception e) {
                Toast.makeText(svc, "Jarvis కెమెరా తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /** Hides Jarvis's own windows for a moment (for a clean screenshot), then runs this. */
    private void hideOwnThen(Runnable then) {
        if (locked() || gone) { busy = false; refresh(); return; }
        TopCard.dismissNow(); // someone's message on the top card is not part of this screen
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        if (card != null) card.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> { if (!gone) then.run(); }, 200);
    }

    /** ✂️ / 🔎: he draws a box around the part he means; only that part (its picture and its words) goes to the AI. */
    private void boxThen(int kind) {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        final Prefs p = new Prefs(svc);
        final String pkg0 = JarvisAccessibility.appPackage();
        final String app = JarvisAccessibility.label(svc, pkg0);
        if (Tools.isMoneyApp(svc, pkg0) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage())) {
            showCard("🔒 " + app, "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని నేను AI కి పంపను. అది మీ చేతుల్లోనే ఉండాలి.", false);
            return;
        }
        if (!p.hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
        lastAnswer = "";
        lastTitle = "";
        closeCard();
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        BoxPicker.pick(svc, false, kind == SEARCH ? "వెతకాల్సిన దాని చుట్టూ బాక్స్ గీయండి" : "కావాల్సిన భాగం చుట్టూ బాక్స్ గీయండి",
                kind == SEARCH ? "మొత్తం స్క్రీన్" : null, r -> {
                    refresh();
                    if (r == null || gone || locked()) return;
                    busy = true;
                    hideOwnThen(() -> JarvisAccessibility.shot(r, 1100, bmp -> {
                        refresh();
                        final String pkg = JarvisAccessibility.appPackage();
                        if (!pkg.equals(pkg0) || Tools.isMoneyApp(svc, pkg)) { // another app came in front meanwhile
                            busy = false;
                            refresh();
                            if (bmp != null) bmp.recycle();
                            showCard(title(kind), "స్క్రీన్ మారిపోయింది. మళ్ళీ నొక్కండి.", false);
                            return;
                        }
                        refresh();
                        showCard(title(kind), kind == SEARCH && p.webSearch() ? "చూస్తున్నాను, వెబ్‌లో వెతుకుతున్నాను…" : "చూస్తున్నాను, అర్థం చేసుకుంటున్నాను…", true);
                        new Thread(() -> {
                            StringBuilder words = new StringBuilder();
                            for (Object[] t : JarvisAccessibility.visibleTexts(pkg)) {
                                Rect b = (Rect) t[1];
                                Rect in = new Rect(b);
                                if (!in.intersect(r)) continue;
                                if ((long) in.width() * in.height() * 2 >= (long) b.width() * b.height() || r.contains(b.centerX(), b.centerY()))
                                    words.append(((String) t[0]).replaceAll("\\s+", " ").trim()).append('\n');
                            }
                            boolean black = bmp != null && dark(bmp);
                            final String jpeg = bmp == null || black ? null : encode(bmp, 85);
                            if (bmp != null) bmp.recycle();
                            if (jpeg == null && words.toString().trim().isEmpty()) {
                                main.post(() -> {
                                    busy = false;
                                    refresh();
                                    showCard(title(kind), black ? "ఈ యాప్ స్క్రీన్‌షాట్ ఇవ్వదు (సినిమా / వీడియో యాప్‌లు నల్లగా ఇస్తాయి), అందుకే చూడలేకపోయాను."
                                            : "ఆ భాగాన్ని చూడలేకపోయాను (ఈ ఫోన్‌లో స్క్రీన్‌షాట్‌కి Android 11 పైన కావాలి).", false);
                                });
                                return;
                            }
                            work(kind, app, words.toString().trim(), jpeg, p);
                        }, "jarvis-box").start();
                    }));
                });
    }

    /** The screenshot is (almost) all black: a protected video app gave nothing. */
    private static boolean dark(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), bright = 0, n = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int c = b.getPixel(Math.min(w - 1, x * w / 16 + w / 32), Math.min(h - 1, y * h / 16 + h / 32));
            int l = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
            if (l > 24) bright++;
            n++;
        }
        return bright * 50 < n; // under 2% of the samples show anything
    }

    private static String encode(Bitmap b, int quality) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static Bitmap decode(String b64) {
        try {
            byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ ⚖️ compare two

    /** First press: this one is kept (3 hours). Second press, on the other one: the two compared. */
    private void compare() {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (cmpText != null && System.currentTimeMillis() - cmpAt > 3 * 3600_000L) { cmpText = null; cmpShot = null; }
        lastAnswer = "";
        lastTitle = "";
        captureThen(cap -> {
            if (cap == null) { showCard("Jarvis", "స్క్రీన్ చూడలేకపోయాను. మళ్ళీ నొక్కండి.", false); return; }
            final String app = JarvisAccessibility.label(svc, cap.pkg);
            if (Tools.isMoneyApp(svc, cap.pkg) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage())) {
                showCard("🔒 " + app, "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని నేను AI కి పంపను.", false);
                return;
            }
            final Prefs p = new Prefs(svc);
            if (!p.hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
            busy = true;
            refresh();
            new Thread(() -> {
                String pg = cap.page(6000);
                String page = pg != null && pg.length() > cap.text.length() ? pg : cap.text;
                final String text = page.length() > 5500 ? page.substring(0, 5500) : page;
                main.post(() -> {
                    if (gone) return;
                    busy = false;
                    refresh();
                    if (text.trim().length() < 2 && cap.jpeg == null) { showCard(title(COMPARE), "ఈ స్క్రీన్‌లో ఏమీ చూడలేకపోయాను.", false); return; }
                    if (cmpText == null) { // the first one
                        cmpText = text;
                        cmpShot = cap.jpeg;
                        cmpApp = app;
                        cmpAt = System.currentTimeMillis();
                        showCard(title(COMPARE), "మొదటిది గుర్తుపెట్టుకున్నాను (" + app + "). ఇప్పుడు రెండోది తెరిచి మళ్ళీ బటన్ → '⚖️ రెండోది పోల్చు' నొక్కండి.",
                                false, "✕ మర్చిపో", () -> { cmpText = null; cmpShot = null; closeCard(); });
                        return;
                    }
                    if (cmpText.equals(text)) {
                        showCard(title(COMPARE), "ఇది మొదటిదే. రెండో దాన్ని తెరిచి నొక్కండి.", false, "✕ మర్చిపో", () -> { cmpText = null; cmpShot = null; closeCard(); });
                        return;
                    }
                    final String both = "FIRST (" + cmpApp + "):\n" + cmpText + "\n\nSECOND (" + app + "):\n" + text;
                    final String s1 = cmpShot, s2 = cap.jpeg;
                    cmpText = null;
                    cmpShot = null;
                    busy = true;
                    refresh();
                    showCard(title(COMPARE), "రెండూ పోల్చి చూస్తున్నాను…", true);
                    new Thread(() -> work(COMPARE, app, both, sideBySide(s1, s2), p), "jarvis-compare").start();
                });
            }, "jarvis-compare-page").start();
        });
    }

    /** The two screenshots side by side in one picture (the first on the left). */
    private static String sideBySide(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        Bitmap x = decode(a), y = decode(b);
        if (x == null || y == null) return x != null ? a : y != null ? b : null;
        int h = Math.min(1100, Math.min(x.getHeight(), y.getHeight()));
        int wx = Math.round(x.getWidth() * h / (float) x.getHeight()), wy = Math.round(y.getWidth() * h / (float) y.getHeight());
        float sc = Math.min(1f, 1600f / (wx + wy + 12));
        int H = Math.max(1, Math.round(h * sc)), WX = Math.max(1, Math.round(wx * sc)), WY = Math.max(1, Math.round(wy * sc));
        Bitmap out = Bitmap.createBitmap(WX + WY + 12, H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFFFFFFFF);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(x, null, new Rect(0, 0, WX, H), f);
        c.drawBitmap(y, null, new Rect(WX + 12, 0, WX + 12 + WY, H), f);
        x.recycle();
        y.recycle();
        String s = encode(out, 80);
        out.recycle();
        return s;
    }

    // ================================================================ ✍️ write it for me

    /** ✍️: what he typed (or says) becomes a clear message, in English and in Telugu; the one he picks goes in the box. He sends it. */
    private void write() {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        final String pkg = JarvisAccessibility.appPackage();
        if (pkg.isEmpty() || locked()) return;
        if (Tools.isMoneyApp(svc, pkg)) { showCard("🔒", "బ్యాంకింగ్ / పేమెంట్ యాప్‌లో నేను రాయను. అది మీ చేతుల్లోనే ఉండాలి.", false); return; }
        if (!new Prefs(svc).hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
        lastAnswer = "";
        lastTitle = "";
        new Thread(() -> {
            final String draft = JarvisAccessibility.draft(pkg);
            StringBuilder seen = new StringBuilder(); // the chat above, for the tone (the last part)
            for (Object[] t : JarvisAccessibility.visibleTexts(pkg)) seen.append(((String) t[0]).replaceAll("\\s+", " ").trim()).append('\n');
            final String chat = seen.length() > 2500 ? seen.substring(seen.length() - 2500) : seen.toString();
            main.post(() -> {
                if (gone) return;
                if (draft == null) { showCard(title(WRITE), "ఈ స్క్రీన్‌లో మెసేజ్ రాసే బాక్స్ దొరకలేదు. చాట్ / మెయిల్ తెరిచి మళ్ళీ నొక్కండి.", false); return; }
                writePkg = pkg;
                if (!draft.trim().isEmpty()) { writeFrom(draft, null, chat); return; }
                showCard(title(WRITE), "ఏం రాయాలో చెప్పండి (తెలుగులో చెప్పినా సరే). బాగా రాసి బాక్సులో పెడతాను; పంపేది మీరే.", false);
                listenThen(said -> writeFrom("", said, chat));
            });
        }, "jarvis-write").start();
    }

    private void writeFrom(String draft, String said, String chat) {
        final Prefs p = new Prefs(svc);
        final String app = JarvisAccessibility.label(svc, writePkg);
        busy = true;
        refresh();
        showCard(title(WRITE), "రాస్తున్నాను…", true);
        final String system = "You write messages for " + p.name() + ". He is about to send a message in " + app + ". From his rough draft or what he "
                + "said aloud (speech to text, may have mistakes), write the message he means: clear, polite, natural and short, keeping his meaning, names, "
                + "numbers and dates; add nothing he did not mean. Match the chat's tone (the chat on the screen is given for context). Reply in exactly "
                + "this form and nothing else:\nENGLISH: <the message in English>\nTELUGU: <the same message in Telugu script>";
        final String prompt = (draft.isEmpty() ? "What he said: " + said : "His draft: " + draft) + "\n\nThe chat on the screen:\n" + chat;
        new Thread(() -> {
            String out = null, err = null;
            try {
                out = Brain.oneShot(p, system, prompt, null, false, 900);
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చేయలేకపోయాను: నెట్ / సమయం సమస్య.";
            }
            String en = "", te = "";
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)ENGLISH:\\s*(.*?)\\s*TELUGU:\\s*(.*)").matcher(out.trim());
                if (m.find()) { en = m.group(1).trim(); te = m.group(2).trim(); }
            }
            final String e1 = en, t1 = te, er = err;
            main.post(() -> {
                if (gone) return;
                busy = false;
                refresh();
                if (er != null || (e1.isEmpty() && t1.isEmpty())) { showCard(title(WRITE), er != null ? er : "రాయలేకపోయాను. మళ్ళీ ప్రయత్నించండి.", false); return; }
                lastAnswer = "English:\n" + e1 + "\n\nతెలుగు:\n" + t1;
                lastTitle = title(WRITE) + " · " + app;
                showCard(title(WRITE), lastAnswer + "\n\nఏది బాక్సులో పెట్టాలి? (పంపేది మీరే)", false,
                        e1.isEmpty() ? null : "✍️ English", e1.isEmpty() ? null : () -> putDraft(draft, e1),
                        t1.isEmpty() ? null : "✍️ తెలుగు", t1.isEmpty() ? null : () -> putDraft(draft, t1));
            });
        }, "jarvis-write-ai").start();
    }

    /** The chosen message into the box, only if it still holds what he wrote (or nothing). */
    private void putDraft(String old, String text) {
        final String pkg = writePkg;
        new Thread(() -> {
            int r = JarvisAccessibility.replaceDraft(pkg, old, text);
            main.post(() -> {
                if (gone) return;
                String msg = r == 1 ? "బాక్సులో పెట్టాను. చూసి మీరే పంపండి."
                        : r == -1 ? "మీరు ఈలోగా బాక్సులో మార్చారు, దాన్ని మార్చలేదు. 📋 కాపీ చేసి పేస్ట్ చేయండి."
                        : r == -2 ? "ఆ చాట్ ఇప్పుడు ముందు లేదు. అక్కడికి వెళ్ళి మళ్ళీ నొక్కండి (లేదా 📋 కాపీ)."
                        : "మెసేజ్ బాక్స్ దొరకలేదు. 📋 కాపీ చేసి పేస్ట్ చేయండి.";
                Toast.makeText(svc, msg, Toast.LENGTH_LONG).show();
                if (r == 1) closeCard();
            });
        }, "jarvis-put").start();
    }

    // ================================================================ 🎧 a WhatsApp voice message

    /** 🎧: the newest WhatsApp voice message, in words and its meaning in Telugu (needs his OpenAI key for the words). */
    private void voiceMsg() {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        final Prefs p = new Prefs(svc);
        final String key = p.openAiKey().trim();
        lastAnswer = "";
        lastTitle = "";
        if (key.isEmpty()) {
            showCard(title(VOICE_MSG), "వాయిస్ మెసేజ్‌ని మాటలుగా మార్చడానికి OpenAI key కావాలి (Jarvis సెట్టింగ్స్ → Jarvis మెదడు). అది లేకపోతే WhatsApp లోనే ప్లే చేసి వినండి.", false);
            return;
        }
        if (WaMedia.tree(svc).isEmpty()) {
            showCard(title(VOICE_MSG), "WhatsApp వాయిస్ మెసేజ్‌లు చూడటానికి ఒక్కసారి అనుమతి కావాలి: Jarvis సెట్టింగ్స్ → 'WhatsApp మీడియా' → ఫోల్డర్ బటన్ → Use this folder → Allow.", false);
            return;
        }
        busy = true;
        refresh();
        showCard(title(VOICE_MSG), "కొత్త వాయిస్ మెసేజ్ వింటున్నాను…", true);
        new Thread(() -> {
            String out = null, said = null, when = "";
            boolean old = false;
            try {
                java.util.List<WaMedia.Found> f = WaMedia.newest(svc, "voice", 1);
                if (f.isEmpty()) out = "ఫోన్‌లో వాయిస్ మెసేజ్ దొరకలేదు (WhatsApp ఇంకా డౌన్‌లోడ్ చేయకపోవచ్చు: ఆ మెసేజ్ మీద ఒకసారి ప్లే / డౌన్‌లోడ్ నొక్కండి).";
                else {
                    WaMedia.Found x = f.get(0);
                    when = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date(x.modified));
                    old = System.currentTimeMillis() - x.modified > 2 * 3600_000L;
                    said = WaMedia.transcribe(svc, key, x.uri).trim();
                    if (said.isEmpty()) out = "అందులో మాటలు వినిపించలేదు.";
                    else if (p.hasBrain()) {
                        out = Brain.oneShot(p, "You are Jarvis, " + p.name() + "'s assistant. Below is a WhatsApp voice message someone sent him, turned into text "
                                + "(speech to text, may have small mistakes). Tell him in simple, natural Telugu (Telugu script) what they said: the whole message, "
                                + "then in one line any question or request to him. Plain text for reading aloud, no markdown.", said, null, false, 900);
                    }
                }
            } catch (Http.ApiError e) {
                out = "మాటలుగా మార్చలేకపోయాను: " + Models.explain(p, e);
            } catch (Exception e) {
                out = "మాటలుగా మార్చలేకపోయాను: నెట్ / సమయం సమస్య.";
            }
            final String o = out == null ? null : out.replaceAll("[*#_`>]", "").trim(), s = said, w = when;
            final boolean older = old;
            main.post(() -> {
                if (gone) return;
                busy = false;
                refresh();
                if (s == null || s.isEmpty()) { showCard(title(VOICE_MSG), o == null ? "చేయలేకపోయాను." : o, false); return; }
                String head = "🕐 " + w + (older ? " (ఇదే ఫోన్‌లో ఉన్న కొత్తది; తాజాది కాకపోవచ్చు)" : "");
                String text = head + "\n\nవాళ్ళు అన్నది:\n“" + s + "”" + (o == null || o.isEmpty() ? "" : "\n\nఅర్థం:\n" + o);
                lastAnswer = text;
                lastTitle = title(VOICE_MSG);
                ctxApp = "WhatsApp";
                ctxPage = s;
                ctxShot = null;
                turns.clear();
                turns.add(new String[]{"Jarvis", text});
                showCard(title(VOICE_MSG), text, false, "💬 జవాబు సూచన", () -> followUp("దీనికి నేను ఏం జవాబు ఇవ్వొచ్చు? ఒక చిన్న జవాబు సూచించు, అవతలివాళ్ళు మాట్లాడిన భాషలో."));
                speak(o == null || o.isEmpty() ? s : o);
            });
        }, "jarvis-voice-msg").start();
    }

    // ================================================================ 🕶️ share with private details hidden

    private Bitmap blurBmp;
    private float blurScale = 1f;

    /** 🕶️: a screenshot with phone numbers, emails, UPI IDs, codes and addresses blurred; he looks at it and shares it himself. */
    private void blurShare() {
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        final String pkg = JarvisAccessibility.appPackage();
        if (Tools.isMoneyApp(svc, pkg) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage())) {
            showCard("🔒", "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని షేర్ చేయడానికి నేను తయారు చేయను. అది మీ చేతుల్లోనే ఉండాలి.", false);
            return;
        }
        lastAnswer = "";
        lastTitle = "";
        closeCard();
        busy = true;
        hideOwnThen(() -> JarvisAccessibility.shot(null, 2400, bmp -> {
            refresh();
            if (bmp == null) { busy = false; refresh(); showCard(title(BLUR), "స్క్రీన్‌షాట్ తీయలేకపోయాను (Android 11 పైన కావాలి).", false); return; }
            final String now = JarvisAccessibility.appPackage();
            if (!now.equals(pkg) || locked()) { busy = false; refresh(); bmp.recycle(); showCard(title(BLUR), "స్క్రీన్ మారిపోయింది. మళ్ళీ నొక్కండి.", false); return; }
            // the words and where they are, right as the picture was taken (before anything can scroll)
            final java.util.List<Object[]> texts = JarvisAccessibility.visibleTexts(pkg);
            final java.util.List<Rect> others = JarvisAccessibility.otherWindows(); // the keyboard, a pop-up notification
            new Thread(() -> {
                Bitmap m = bmp.copy(Bitmap.Config.ARGB_8888, true);
                bmp.recycle();
                boolean black = dark(m);
                android.graphics.Point real = new android.graphics.Point();
                wm.getDefaultDisplay().getRealSize(real);
                final float sc = m.getWidth() / (float) Math.max(1, real.x);
                int[] counts = new int[5]; // numbers, email / UPI, bank / PAN codes, addresses, vehicles
                for (Object[] t : texts) {
                    int kind = privateKind((String) t[0]);
                    if (kind < 0) continue;
                    counts[kind]++;
                    pixelate(m, scaled((Rect) t[1], sc));
                }
                for (Rect o : others) pixelate(m, scaled(o, sc)); // not the app's: covered whole
                final boolean saved = saveShare(m);
                main.post(() -> {
                    busy = false;
                    refresh();
                    if (gone) return;
                    blurBmp = m; // (the old one is left to the garbage collector: a card may still be drawing it)
                    blurScale = sc;
                    if (black) { showCard(title(BLUR), "ఈ యాప్ స్క్రీన్‌షాట్ ఇవ్వదు (నల్లగా వచ్చింది).", false); return; }
                    if (!saved) { showCard(title(BLUR), "బొమ్మని సేవ్ చేయలేకపోయాను.", false); return; }
                    showBlurCard(counts);
                });
            }, "jarvis-blur").start();
        }));
    }

    private void showBlurCard(int[] counts) {
        String[] names = {"నంబర్లు", "ఈమెయిల్ / UPI", "బ్యాంక్ / PAN కోడ్‌లు", "చిరునామాలు", "వాహన నంబర్లు"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) b.append(b.length() == 0 ? "" : ", ").append(counts[i]).append(" ").append(names[i]);
        String text = (b.length() == 0 ? "నాకు ప్రైవేట్ వివరాలు ఏవీ కనిపించలేదు." : "దాచాను: " + b + ".")
                + "\nపేర్లు, ముఖాలు, ఫోటోలు నేను దాచను: ఇంకా ఏదైనా దాచాలంటే '✂️ ఇంకా దాచు' నొక్కి దాని చుట్టూ బాక్స్ గీయండి. చూసుకుని షేర్ చేయండి.";
        showCard(title(BLUR), text, false, "📤 షేర్", this::shareBlurred, "✂️ ఇంకా దాచు", this::blurMore);
        showPic(blurBmp);
    }

    /** ✂️ ఇంకా దాచు: he boxes one more thing (a name, a face, a photo); it is blurred too. */
    private void blurMore() {
        if (blurBmp == null) return;
        closeCard();
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        BoxPicker.pick(svc, false, "దాచాల్సిన దాని చుట్టూ బాక్స్ గీయండి", null, r -> {
            refresh();
            if (gone || blurBmp == null || locked()) return;
            if (r != null) {
                pixelate(blurBmp, scaled(r, blurScale));
                saveShare(blurBmp);
            }
            showCard(title(BLUR), r == null ? "సరే. చూసుకుని షేర్ చేయండి." : "అది కూడా దాచాను ✓ ఇంకా ఉంటే మళ్ళీ '✂️ ఇంకా దాచు'. చూసుకుని షేర్ చేయండి.", false,
                    "📤 షేర్", this::shareBlurred, "✂️ ఇంకా దాచు", this::blurMore);
            showPic(blurBmp);
        });
    }

    /** The share sheet with the hidden-details picture; he picks the person and sends it. */
    private void shareBlurred() {
        try {
            Uri u = PhotoProvider.shareUri();
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("Jarvis", u));
            svc.startActivity(Intent.createChooser(send, "ఎవరికి పంపాలి?").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            closeCard();
        } catch (Exception e) {
            Toast.makeText(svc, "షేర్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean saveShare(Bitmap b) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(PhotoProvider.shareFile(svc))) {
            return b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (Exception e) {
            return false;
        }
    }

    private static Rect scaled(Rect r, float sc) {
        return new Rect(Math.round(r.left * sc), Math.round(r.top * sc), Math.round(r.right * sc), Math.round(r.bottom * sc));
    }

    /** Coarse blocks over this part (nothing readable is left), with a grey veil. */
    private static void pixelate(Bitmap m, Rect r0) {
        Rect r = new Rect(r0);
        r.inset(-4, -4);
        if (!r.intersect(0, 0, m.getWidth(), m.getHeight()) || r.width() < 2 || r.height() < 2) return;
        int block = Math.max(12, Math.min(r.width(), r.height()) / 2);
        Bitmap part = Bitmap.createBitmap(m, r.left, r.top, r.width(), r.height());
        Bitmap small = Bitmap.createScaledBitmap(part, Math.max(1, r.width() / block), Math.max(1, r.height() / block), true);
        Bitmap big = Bitmap.createScaledBitmap(small, r.width(), r.height(), false);
        Canvas c = new Canvas(m);
        c.drawBitmap(big, r.left, r.top, null);
        Paint veil = new Paint();
        veil.setColor(0x66808080);
        c.drawRect(r, veil);
        if (part != m) part.recycle();
        small.recycle();
        if (big != small) big.recycle();
    }

    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("(?:\\d[\\s-]?){6,}");
    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}");
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[\\w.+-]{2,}@[\\w-]{2,}(\\.[\\w.-]+)?");
    private static final java.util.regex.Pattern CODES = java.util.regex.Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b|\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    private static final java.util.regex.Pattern VEHICLE = java.util.regex.Pattern.compile("\\b[A-Z]{2}[\\s-]?\\d{1,2}[\\s-]?[A-Z]{1,3}[\\s-]?\\d{4}\\b");
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
            "(?i)\\b(h\\.?\\s?no|d\\.?\\s?no|flat|plot|door|street|road|nagar|colony|layout|apartments?|sector|village|mandal|district|dist|pin\\s?code|pincode)\\b"
                    + "|చిరునామా|నగర్|కాలనీ|రోడ్|వీధి|మండలం|జిల్లా|గ్రామం");

    /** What private thing this text holds: 0 numbers, 1 email / UPI, 2 IFSC / PAN, 3 an address, 4 a vehicle number; -1 none. */
    private static int privateKind(String t) {
        if (t == null) return -1;
        if (EMAIL.matcher(t).find()) return 1;
        if (CODES.matcher(t).find()) return 2;
        if (VEHICLE.matcher(t).find()) return 4;
        java.util.regex.Matcher m = DIGITS.matcher(t);
        while (m.find()) {
            String g = m.group().trim();
            if (DATE.matcher(g).matches()) continue; // a date is not private
            if (g.replaceAll("\\D", "").length() >= 6) return 0;
        }
        if (ADDRESS.matcher(t).find() && t.matches("(?s).*\\d.*")) return 3;
        return -1;
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
            case WRITE: return "✍️ రాసిపెట్టు";
            case SAVE_THIS: return "📥 సేవ్ చేయి";
            case ROUTE: return "📍 దారి / కాల్";
            case TRUTH: return "✅ నిజమా?";
            case PART: return "✂️ ఈ భాగం";
            case COMPARE: return "⚖️ పోలిక";
            case FORM: return "📋 ఫారమ్ సహాయం";
            case VOICE_MSG: return "🎧 వాయిస్ మెసేజ్";
            case GROUP_SUM: return "👥 గ్రూప్ సారాంశం";
            case BLUR: return "🕶️ దాచి షేర్";
            case SEARCH: return "🔎 ఇది ఏంటి?";
            default: return "Jarvis";
        }
    }

    /** Every prompt that may carry a picture: a person is never named from their face. */
    private static final String NO_FACES = "Never identify a real person from their face or body: describe people only by what is visible, and name "
            + "someone only when the text on the screen says who it is.";

    private static String system(int kind, Prefs p) {
        String name = p.name();
        String who = "You are Jarvis, " + name + "'s assistant. He is looking at this screen on his phone (screenshot and its text are given). "
                + "Write in simple, natural Telugu (Telugu script), as plain text for reading aloud: no markdown, no lists of symbols. " + NO_FACES + " ";
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
                        + "If the picture has no text, write one Telugu sentence saying what the picture shows. " + NO_FACES;
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
            case TRUTH:
                return who + "He asks 'is this true?' about the claim, news, forward or post on his screen. Find the main claim(s), search the web for reliable "
                        + "sources (news agencies, official sites, fact-checkers like PIB Fact Check, Alt News, Boom, Factly) and judge. First line: one verdict: "
                        + "'✅ నిజమే', '⚠️ కొంత నిజం / తప్పుదారి పట్టించేది', '❌ తప్పు / ఫేక్' or '❓ ఇంకా తేలలేదు'. Then 3-6 short sentences: what is actually true, "
                        + "the evidence, and the names of the sources you found (no links). Health or money claims: say clearly what is risky. If you could not find "
                        + "anything reliable, say so honestly; never guess a verdict.";
            case FORM:
                return who + "He is filling in a form (an app, a website or a government / bank / insurance form) and wants help. Go through the fields on the "
                        + "screen in order: for each, its name, what to write there in simple Telugu, the format if it matters (date as dd/mm/yyyy, the name as on "
                        + "the ID card, capital letters...), and whether it looks required. Then any checkbox, declaration or terms worth knowing before he submits, "
                        + "and common mistakes to avoid. Never ask him for his password, OTP, PIN or ID numbers and never fill anything; you only explain. "
                        + "If this is not a form, say so in one sentence.";
            case GROUP_SUM:
                return who + "This is a group chat. Sum up the messages on the screen for him: first anything addressed to him or mentioning him (his names: "
                        + p.myNames() + "), any question or request to him, dates, times, places or money; then what the group talked about, briefly, with who "
                        + "said what when it matters. 4-10 short sentences. If it is not a group chat, sum up the chat the same way.";
            case PART:
                return who + "He marked only one part of the screen (the picture is just that part, and its text is given). Tell him what that part says "
                        + "and means, in Telugu: translate or explain it, keep numbers, names and dates, and say what he should know or do. 3-10 sentences.";
            case SEARCH:
                return "You are Jarvis, " + name + "'s assistant. He marked a picture or a part of his screen (a photo, a frame of a video, a product, a "
                        + "plant, an animal, a place, a dish, a vehicle, a gadget, a logo...) and asks 'what is this?'. Identify it as exactly as you can, then search "
                        + "the web to tell him: what it is, the key facts, and if it is a product its name, model and usual price in India; a place, where it is; "
                        + "a plant or food, what it is and anything to be careful about. " + NO_FACES + " If the picture is too unclear, say so. Simple, natural "
                        + "Telugu (Telugu script), plain text for reading aloud, 4-9 sentences.";
            case COMPARE:
                return who + "He opened two things to compare: FIRST and SECOND below (the picture shows the first on the left and the second on the right). "
                        + "Compare them for him: what each is, the price, the key features, specs or terms, and the differences that matter. Then a clear "
                        + "suggestion: which is better for what, and why. If they are not really comparable, say so. Only facts from the screens; when something is "
                        + "missing, say it is not shown. 6-12 sentences.";
            case SAVE_THIS:
                return "You help Jarvis, " + name + "'s assistant, save the useful thing on his screen. Find up to 3 items, most useful first: an event "
                        + "(meeting, appointment, journey, function, exam), a bill or expense he paid, a contact (a person's or shop's name with a phone, email or "
                        + "address), a parcel / order delivery, or else one short note of what matters on the screen. Use the 'Now' line for the year and for "
                        + "'tomorrow' / weekdays. NEVER include Aadhaar, PAN, passport, policy, account, card, ID or customer numbers, passwords, OTPs or PINs. "
                        + "Reply with JSON only: {\"items\":[{\"type\":\"event\",\"title\":\"..\",\"date\":\"yyyy-MM-dd\",\"time\":\"HH:mm or empty\",\"place\":\"..\"},"
                        + "{\"type\":\"expense\",\"amount\":123.5,\"what\":\"..\",\"shop\":\"..\",\"category\":\"food|groceries|fuel|bills|shopping|travel|health|other\","
                        + "\"date\":\"yyyy-MM-dd\"},{\"type\":\"contact\",\"name\":\"..\",\"phone\":\"..\",\"email\":\"..\",\"address\":\"..\"},"
                        + "{\"type\":\"parcel\",\"what\":\"..\",\"date\":\"yyyy-MM-dd expected delivery, or empty\"},{\"type\":\"note\",\"text\":\"..\"}]}. "
                        + "Short texts in Telugu (names, shops and places as written). Nothing useful: {\"items\":[]}.";
            case ROUTE:
                return "You help Jarvis, " + name + "'s assistant. Find on his screen the places he may want to go to (a full address, a shop, hospital, "
                        + "office or venue with its area and town) and the phone numbers he may want to call (with whose number it is). Reply with JSON only: "
                        + "{\"places\":[{\"name\":\"..\",\"address\":\"the address as on the screen, with area and town if shown\"}],"
                        + "\"phones\":[{\"who\":\"..\",\"number\":\"..\"}]}, at most 3 of each, most useful first. Never invent an address or a number. "
                        + "None found: empty lists.";
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

    /** The card's 🎙️: stop and take what was said, or listen (for what the card asked, else a question about this screen). */
    private void micPressed() {
        if (listening) { endListen(); return; } // "అయిపోయింది": take what was said
        if (heardTo != null) { startListening(); return; }
        listenFollowUp();
    }

    private void listenFollowUp() {
        if (listening) { endListen(); return; }
        if (busy) { Toast.makeText(svc, "ఇంకా ఆలోచిస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (ctxPage.isEmpty() && turns.isEmpty()) { Toast.makeText(svc, "ముందు '🧠 అర్థం చెప్పు' లాంటి ఆప్షన్ ఒకటి నొక్కండి", Toast.LENGTH_SHORT).show(); return; }
        heardTo = null;
        startListening();
    }

    /** Listens once; what he says goes to this (✍️: what to write), not to a question about the screen. */
    private void listenThen(java.util.function.Consumer<String> to) {
        stopListening();
        heardTo = to;
        startListening();
    }

    private void startListening() {
        if (listening) return;
        if (svc.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showCard("🎙️ అడుగు", "మైక్ అనుమతి లేదు. Jarvis యాప్ తెరిచి మైక్ అనుమతి (Allow) ఇవ్వండి.", false);
            return;
        }
        if (!Ears.chosen(new Prefs(svc)) && !android.speech.SpeechRecognizer.isRecognitionAvailable(svc)) {
            showCard("🎙️ అడుగు", "ఈ ఫోన్‌లో మాటలు వినే Google సేవ దొరకలేదు.", false);
            return;
        }
        reader.stop();
        Announcer.stop();
        talkSet = !MainActivity.inConversation; // the wake word and Jarvis's remarks wait while he asks
        if (talkSet) MainActivity.talking(true);
        WakeService.pause(svc); // "Hey Jarvis" listening gives the mic to this question
        releaseMic();
        if (Ears.chosen(new Prefs(svc))) { listenWithEars(); return; } // Jarvis's own mic: no beeps
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
                    boolean noMic = error == android.speech.SpeechRecognizer.ERROR_AUDIO || error == android.speech.SpeechRecognizer.ERROR_CLIENT
                            || error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
                    if (noMic && heardTo == null) { askInPanel(); return; } // the mic is not ours from here
                    if (noMic) { if (cardTitle != null) cardTitle.setText("🎙️ మైక్ దొరకలేదు. కాసేపాగి 🎙️ నొక్కండి"); return; }
                    boolean net = error == android.speech.SpeechRecognizer.ERROR_NETWORK || error == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
                    if (cardTitle != null) cardTitle.setText(net ? "🎙️ నెట్ లేదు: మాట అర్థం చేసుకోలేకపోయాను" : "🎙️ వినిపించలేదు. మళ్ళీ 🎙️ నొక్కండి");
                    if (cardText != null && !lastAnswer.isEmpty()) cardText.setText(lastAnswer);
                }
                @Override public void onResults(android.os.Bundle b) {
                    if (!mine()) return;
                    String q = heard(b);
                    if (q.isEmpty()) { onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return; }
                    doneListening();
                    java.util.function.Consumer<String> to = heardTo;
                    heardTo = null;
                    if (to != null) to.accept(q); else followUp(q);
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
        MicQuiet.hold(svc, this); // the phone's mic beeps stay quiet while this question is heard
        if (micBtn != null) micBtn.setText("✋ అయిపోయింది");
        if (cardTitle != null) cardTitle.setText("🎙️ ఒక్క క్షణం…");
        try {
            sr.startListening(i);
        } catch (Exception e) {
            doneListening();
            if (cardTitle != null) cardTitle.setText("🎙️ మైక్ తెరవలేకపోయాను");
        }
    }

    /** Jarvis's own ears for the card's question (no beeps); the words come once he stops talking. */
    private Ears ears;

    private void listenWithEars() {
        final Ears e = new Ears(svc, new Prefs(svc));
        ears = e;
        listening = true;
        if (micBtn != null) micBtn.setText("✋ అయిపోయింది");
        if (cardTitle != null) cardTitle.setText("🎙️ ఒక్క క్షణం…");
        e.start(8000, heardTo != null, new Ears.Callback() { // (✍️ "what to write" may be long)
            private boolean mine() { return ears == e && listening; }
            @Override public void opened() { if (mine() && cardTitle != null) cardTitle.setText("🎙️ వింటున్నాను… అడగండి"); }
            @Override public void level(float level) {}
            @Override public void understanding() { if (mine() && cardTitle != null) cardTitle.setText("🎙️ అర్థం చేసుకుంటున్నాను…"); }
            @Override public void heard(String text) {
                if (!mine()) return;
                ears = null;
                String q = text == null ? "" : text.trim();
                doneListening();
                if (q.isEmpty()) { earsFailed(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return; }
                if (cardText != null) cardText.setText("“" + q + "”");
                java.util.function.Consumer<String> to = heardTo;
                heardTo = null;
                if (to != null) to.accept(q); else followUp(q);
            }
            @Override public void failed(int error) {
                if (!mine()) return;
                ears = null;
                doneListening();
                earsFailed(error);
            }
        });
    }

    /** Jarvis's own ears ended without words: why, on the card (the mic not to be had here: the panel asks instead). */
    private void earsFailed(int error) {
        boolean noMic = error == android.speech.SpeechRecognizer.ERROR_AUDIO || error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
        if (noMic && heardTo == null) { askInPanel(); return; }
        boolean silence = error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (cardTitle != null) cardTitle.setText(silence ? "🎙️ వినిపించలేదు. మళ్ళీ 🎙️ నొక్కండి" : "🎙️ " + VoiceIO.failText(error));
        if (cardText != null && !lastAnswer.isEmpty()) cardText.setText(lastAnswer);
    }

    /** ✋ అయిపోయింది: what he said so far is taken. */
    private void endListen() {
        if (ears != null) { ears.finishNow(); return; }
        try { if (sr != null) sr.stopListening(); } catch (Exception ignored) {}
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
        MicQuiet.release(this);
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
        if (ears != null) { ears.cancel(); ears = null; }
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
                + "knowledge; if the screen does not say and you are not sure, say so honestly. " + NO_FACES + " No markdown.";
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

    private void showCard(String title, String text, boolean working) { showCard(title, text, working, null, null, null, null); }

    private void showCard(String title, String text, boolean working, String extra, Runnable extraDo) {
        showCard(title, text, working, extra, extraDo, null, null);
    }

    /** The card; extra / extra2 = buttons for this answer (✍️ type the reply, ✅ set the reminders, 🧭 the way...), or none. */
    private void showCard(String title, String text, boolean working, String extra, Runnable extraDo, String extra2, Runnable extra2Do) {
        if (gone || locked()) return;
        if (card == null) buildCard();
        if (card == null) return;
        cardTitle.setText(title);
        cardText.setText(text);
        cardPic.setImageDrawable(null);
        cardPic.setVisibility(View.GONE);
        cardActions.setVisibility(working ? View.GONE : View.VISIBLE);
        cardRow2.setVisibility(lastAnswer.isEmpty() ? View.GONE : View.VISIBLE); // copy / save / share need an answer
        boolean one = extra != null && extraDo != null, two = extra2 != null && extra2Do != null;
        cardExtras.setVisibility(one || two ? View.VISIBLE : View.GONE);
        extraBtn.setVisibility(one ? View.VISIBLE : View.GONE);
        if (one) { extraBtn.setText(extra); extraBtn.setOnClickListener(v -> extraDo.run()); }
        extraBtn2.setVisibility(two ? View.VISIBLE : View.GONE);
        if (two) { extraBtn2.setText(extra2); extraBtn2.setOnClickListener(v -> extra2Do.run()); }
    }

    /** A picture under the card's text (🕶️ the screenshot with details hidden, to look at before sharing). */
    private void showPic(Bitmap b) {
        if (cardPic == null || b == null) return;
        cardPic.setImageBitmap(b);
        cardPic.setVisibility(View.VISIBLE);
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
        cardPic = new ImageView(svc);
        cardPic.setAdjustViewBounds(true);
        cardPic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cardPic.setMaxHeight((int) (screenH() * 0.3f));
        cardPic.setVisibility(View.GONE);
        card.addView(cardPic, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(svc);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(svc), row2 = new LinearLayout(svc), rowX = new LinearLayout(svc);
        row1.setGravity(Gravity.END);
        row2.setGravity(Gravity.END);
        rowX.setGravity(Gravity.END);
        micBtn = button("🎙️ అడుగు", v -> micPressed());
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
        rowX.addView(extraBtn);
        extraBtn2 = button("", v -> {});
        extraBtn2.setTextColor(0xFF7CF5B0);
        extraBtn2.setVisibility(View.GONE);
        rowX.addView(extraBtn2);
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
        actions.addView(rowX);
        actions.addView(row1);
        actions.addView(row2);
        cardRow2 = row2;
        cardExtras = rowX;
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
        extraBtn2 = null;
        cardExtras = null;
        cardPic = null;
        cardRow2 = null;
        heardTo = null; // a spoken answer was for this card
        playBtn = null;
        cardLp = null;
        cardScroll = null;
    }
}
