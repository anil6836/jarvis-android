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

    private static final int READ = 0, MEANING = 1, ABOUT = 2, TELUGU = 3, REPLY = 4, SCAM = 5;

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
    private String lastAnswer = "";
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
                closeCard();
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
        if (reader.active()) {
            item(reader.paused() ? "▶  కొనసాగించు" : "⏸  ఆపు", reader::toggle);
            item("⏹  చదవడం ఆపేయి", reader::stop);
        }
        item("📖  చదువు", () -> act(READ));
        item("🧠  అర్థం చెప్పు", () -> act(MEANING));
        item("💡  దీని గురించి", () -> act(ABOUT));
        item("🌐  తెలుగులో", () -> act(TELUGU));
        item("💬  జవాబు", () -> act(REPLY));
        item("🛡️  మోసమా?", () -> act(SCAM));
        item("🎙️  అడుగు", this::ask);
        item("✕  దాచు", () -> {
            set(svc, false);
            Toast.makeText(svc, "Jarvis బటన్ దాచాను. సెట్టింగ్స్ → ఫ్లోటింగ్ బటన్ లో మళ్ళీ ఆన్ చేయొచ్చు.", Toast.LENGTH_LONG).show();
        });
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

    private void item(String label, Runnable r) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14.5f);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(9), dp(16), dp(9));
        t.setOnClickListener(v -> { hideMenu(); r.run(); });
        items.addView(t);
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
        captureThen(cap -> {
            if (cap == null) { showCard("Jarvis", "స్క్రీన్ చూడలేకపోయాను. మళ్ళీ నొక్కండి.", false); return; }
            final String app = JarvisAccessibility.label(svc, cap.pkg);
            final boolean money = Tools.isMoneyApp(svc, cap.pkg) || Tools.isMoneyApp(svc, JarvisAccessibility.currentPackage());
            final Prefs p = new Prefs(svc);
            if (kind != READ && money) {
                showCard("🔒 " + app, "బ్యాంకింగ్ / పేమెంట్ యాప్ స్క్రీన్‌ని నేను AI కి పంపను. అది మీ చేతుల్లోనే ఉండాలి. '📖 చదువు' మాత్రం ఫోన్‌లోనే చదువుతుంది.", false);
                return;
            }
            if (kind != READ && !p.hasBrain()) { showCard("Jarvis", "నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
            busy = true;
            refresh();
            if (kind != READ) showCard(title(kind), "చూస్తున్నాను, అర్థం చేసుకుంటున్నాను…", true);
            final String shot = cap.jpeg, seen = cap.text == null ? "" : cap.text;
            new Thread(() -> {
                String pg = cap.page(6000); // the whole page (a long one takes a moment)
                final String page = pg != null && pg.length() > seen.length() ? pg : seen;
                if (kind == READ) {
                    main.post(() -> {
                        if (gone) return;
                        busy = false;
                        refresh();
                        if (page.trim().length() < 2) {
                            showCard("📖 చదువు", "ఈ స్క్రీన్‌లో చదవడానికి అక్షరాలు దొరకలేదు. ఫోటో / వీడియో అయితే '💡 దీని గురించి' నొక్కండి.", false);
                            return;
                        }
                        closeCard();
                        Announcer.stop(); // one voice at a time
                        reader.read(app, page);
                    });
                    return;
                }
                String out = null, err = null;
                try {
                    String body = page.length() > 12000 ? page.substring(0, 12000) : page;
                    out = Brain.oneShot(p, system(kind, p.name()), "App on screen: " + app + "\nText read from the screen:\n" + body, shot, false,
                            kind == MEANING || kind == TELUGU ? 3500 : 900);
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
                    if (er != null || o == null || o.trim().isEmpty()) { showCard(title(kind), er != null ? er : "జవాబు రాలేదు. మళ్ళీ ప్రయత్నించండి.", false); return; }
                    String clean = o.replaceAll("[*#_`>]", "").replaceAll("\n{3,}", "\n\n").trim();
                    lastAnswer = clean;
                    showCard(title(kind), clean, false);
                    speak(kind == REPLY ? "ఇలా జవాబు ఇవ్వొచ్చు: " + clean : clean);
                });
            }, "jarvis-bubble").start();
        });
    }

    private static String title(int kind) {
        switch (kind) {
            case MEANING: return "🧠 అర్థం";
            case ABOUT: return "💡 దీని గురించి";
            case TELUGU: return "🌐 తెలుగులో";
            case REPLY: return "💬 జవాబు (కాపీ చేసి పంపండి)";
            case SCAM: return "🛡️ మోసమా?";
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
            default: // SCAM
                return who + "Check whether this message or page is a scam, fraud or fake news: asking for OTP / PIN / passwords, KYC or account-block threats, "
                        + "lottery or prize, offers too good to be true, urgent payment requests, odd links or apps to install, fake bank or government names, "
                        + "forwarded rumours. First line: one verdict: '✅ సురక్షితంగా అనిపిస్తోంది' or '⚠️ జాగ్రత్త' or '❌ మోసం లాగా ఉంది'. Then 2-4 short reasons and "
                        + "what to do. Never tell him to tap a link, call an unknown number or share a code.";
        }
    }

    /** Short answers in Jarvis's voice (natural voice if chosen); long ones with the phone's voice, which can pause and stop. */
    private void speak(String text) {
        reader.stop();
        Announcer.stop();
        if (text.length() <= 900) Announcer.say(svc, text);
        else reader.read("Jarvis", text);
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

    // ================================================================ the answer card

    private void showCard(String title, String text, boolean working) {
        if (gone || locked()) return;
        if (card == null) buildCard();
        if (card == null) return;
        cardTitle.setText(title);
        cardText.setText(text);
        cardActions.setVisibility(working ? View.GONE : View.VISIBLE);
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
        cardText = new TextView(svc);
        cardText.setTextColor(0xFFFFFFFF);
        cardText.setTextSize(15.5f);
        cardText.setLineSpacing(0, 1.2f);
        cardText.setPadding(0, dp(6), dp(6), dp(6));
        sv.addView(cardText);
        card.addView(sv);
        LinearLayout actions = new LinearLayout(svc);
        actions.setGravity(Gravity.END);
        actions.addView(button("🔊 మళ్ళీ", v -> { if (!lastAnswer.isEmpty()) speak(lastAnswer); }));
        actions.addView(button("⏹ ఆపు", v -> { reader.stop(); Announcer.stop(); }));
        actions.addView(button("📋 కాపీ", v -> {
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            if (cm != null && !lastAnswer.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("Jarvis", lastAnswer));
                Toast.makeText(svc, "కాపీ చేశాను", Toast.LENGTH_SHORT).show();
            }
        }));
        cardActions = actions;
        card.addView(actions);
        WindowManager.LayoutParams clp = new WindowManager.LayoutParams(screenW() - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        clp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        clp.y = dp(36);
        try { wm.addView(card, clp); } catch (Exception e) { card = null; }
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFD7F6FF);
        t.setTextSize(13.5f);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private void closeCard() {
        if (card == null) return;
        try { wm.removeView(card); } catch (Exception ignored) {}
        card = null;
        cardTitle = null;
        cardText = null;
        cardActions = null;
    }
}
