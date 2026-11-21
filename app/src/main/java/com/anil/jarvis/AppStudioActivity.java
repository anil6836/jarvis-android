package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * 📱 The app studio screen (Anil, 18 Nov, stage 1). He says or types the app he wants → 🧠 the workshop's AI (his choice)
 * writes a short Telugu plan with what making it will cost → "✅ ఇలాగే తయారు చేయి" → the app is written part by part (a tick
 * for each) into a sealed preview, the kit taps through every screen by itself and a failing part is sent back for repair
 * (2 tries a part, 3 an app, only that part) → he tries the app right here → "📲 ఫోన్‌లో పెట్టు" builds the APK in the app's
 * own private GitHub repo (a notification when it is ready to install). "📱 నా యాప్‌లు" lists every app he made; any of them
 * can be opened and changed again (only the changed parts are written). The preview is a WebView sealed off from
 * everything like the game studio's: only the studio's own files load, the app runs in a sandboxed frame with no network
 * and no storage, keys never reach it, and one message port carries "setup / part / start / test" in and "ok / error /
 * tested" out. Phone only.
 * (20 Nov, stages 2 and 3) 📸 a photo of an app or a paper drawing can start a plan (the photo goes only to his AI for that
 * plan, never kept); 📦 an idea with a ready starter makes the app for ₹0; ↩️ every version is kept and any can come back;
 * 📤 a built app's APK can be sent to family from the share screen, on his tap.
 */
public class AppStudioActivity extends Activity {
    static final String EXTRA_WHAT = "what", EXTRA_HEARD = "heard", EXTRA_SAID = "said", EXTRA_ACTION = "action", EXTRA_SLUG = "slug",
            EXTRA_CHANGE = "change";
    /** The studio is in front: "Jarvis" opens no panel over it (its own ears hear him). */
    static volatile boolean front;
    private static volatile String openTitle; // (read through openTitle())
    private static volatile long stoppedAt;

    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/apps/";
    private static final String HARNESS = "https://" + HOST + PATH + "harness.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("harness.html", "harness.js", "stage.html", "stage.js", "kit.js", "kit.css"));
    private static final long ACK_WAIT_MS = 20000, TEST_WAIT_MS = 45000, READY_WAIT_MS = 15000;
    private static final String[] WAITING = {
            "యాప్ ఎలా ఉండాలో ఆలోచిస్తున్నాను Sir.", "స్క్రీన్లు ఒక్కొక్కటిగా సిద్ధం చేస్తున్నాను.", "రంగులు, బటన్లు ఎంచుకుంటున్నాను.",
            "ఇంకొంచెం సేపు Sir, మొదటి భాగం దగ్గర్లోనే ఉంది.", "మీ డేటా ఎలా దాచాలో చూస్తున్నాను."};
    /** Ready ideas: the chip and the fuller request it puts in the box (he can change it before 🧠). */
    private static final String[][] IDEAS = {
            {"💰 ఖర్చుల లెక్క", "ఖర్చుల లెక్క యాప్: రోజూ ఖర్చులు రాసుకోవాలి (ఎంత, దేనికి, తేదీ, రకం: ఇల్లు, పెట్రోల్, భోజనం, సరుకులు, ఇతర), ఈ నెల మొత్తం, రకాల వారీగా పై చార్ట్, నెలల వారీగా బార్ చార్ట్, పాత నెలలు చూడాలి"},
            {"⚡ ఛార్జింగ్ లాగ్", "బైక్ ఛార్జింగ్ లాగ్ యాప్: ప్రతిసారి ఛార్జ్ పెట్టినప్పుడు తేదీ, ఎన్ని యూనిట్లు, ఎంత ఖర్చు, బ్యాటరీ ఎంత % నుంచి ఎంత % వరకు, ఓడోమీటర్ km రాసుకోవాలి; నెల ఖర్చు, ఒక్కో km కి ఖర్చు, నెలల వారీగా చార్ట్"},
            {"✅ పనుల లిస్ట్", "పనుల లిస్ట్ యాప్: పనులు రాసుకోవాలి, ముఖ్యమైనవి పైన, ఏ రోజుకి చేయాలో తేదీ, పూర్తయినవి టిక్, ఈరోజు / రాబోయేవి / పూర్తయినవి అని విడిగా"},
            {"🧾 షాప్ బిల్లు", "షాప్ బిల్లు యాప్: వస్తువు పేరు, ధర, ఎన్ని అని రాస్తే బిల్లు లెక్క, మొత్తం, తగ్గింపు, పాత బిల్లుల లిస్ట్ తేదీతో"},
            {"🏏 క్రికెట్ స్కోర్", "క్రికెట్ స్కోర్ యాప్: రెండు టీముల పేర్లు, ఓవర్లు, బంతి బంతికి రన్స్ (0 1 2 3 4 6), వైడ్, నో బాల్, వికెట్, రన్ రేట్, చివర ఎవరు గెలిచారో"},
            {"📚 క్విజ్", "తెలుగు క్విజ్ యాప్: సాధారణ జ్ఞానం 20 ప్రశ్నలు, 4 జవాబుల్లో ఒకటి ఎంచుకోవాలి, సరైనదా కాదా వెంటనే చూపాలి, చివర స్కోర్, బెస్ట్ స్కోర్ దాచాలి"}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs p;
    private FrameLayout stageBox;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private TextView titleView, chip, status, go, micBtn, cancelBtn, stopBtn, listBtn, newBtn, buildBtn, installBtn, fixBtn, versionsBtn, sendBtn;
    private int versionCount;
    private static final int REQ_CAM = 41, REQ_GAL = 42, REQ_CAM_PERM = 43;
    private LinearLayout list;
    private ScrollView listScroll;
    private HorizontalScrollView ideas;
    /** 🧠 Before a plan is asked for: which AI writes it, about what a plan costs, and a tap for every model's plan cost. */
    private TextView planAi;
    private EditText input;
    // the card over the preview: the plan, or the list of his apps
    private ScrollView overlay;
    private LinearLayout overlayBox;
    private String overlayKind = ""; // "" | plan | list

    /** The plan on the card (null = none), the words it was made from, and what it cost. */
    private AppStudio.Plan plan;
    private String planWhat, planHeard;
    /** The app in the preview now (null = none). */
    private AppStudio.Made app;
    private volatile Job job;
    private boolean publishing;
    private final Map<String, String[]> acks = new HashMap<>();
    /** The self-test's answer: {"ok", taps} or {"err", part id, message} (under {@link #acks}). */
    private String[] testAns;
    private volatile boolean stageReady, startedOk;
    private final Map<String, TextView> rows = new LinkedHashMap<>();

    // 🎙️ the studio's own ears (the workshop's), ✋ a paid job heard by voice waits 3 seconds
    private WorkshopVoice ear;
    private final Runnable wakeR = () -> { if (ear != null && front && !destroyed) ear.wake(); };
    private String waiting;
    private Runnable waitGo;
    private long waitSince;

    /** One paid job: a plan, a new plan, an app, a change, a repair. */
    private static final class Job {
        final String kind, what, heard;
        final long at = System.currentTimeMillis();
        final Http.Cancel cancel = new Http.Cancel();
        final Workshop.Engine engine;
        volatile boolean stopped;
        String say, refused, badId, problem, failure, photo;
        /** Why the answer stopped early (cut at its size, or the connection), when the parts that came are still used. */
        String note;
        /** The app being made or changed (a working copy: the saved one changes only when this one works). */
        AppStudio.Made work;
        /** (a change) parts that came back; parts taken out. */
        final Set<String> changed = new HashSet<>(), removed = new HashSet<>();
        final Map<String, Integer> tries = new HashMap<>();
        double rupees;
        boolean estimated, tested, froze, cut;
        /** The preview page itself would not open (not the app's fault): the app is kept and tested again for free later. */
        boolean stageTrouble;
        int repairs, waitLine;
        long nextLine = 10;
        Job(String kind, String what, String heard, Workshop.Engine engine) { this.kind = kind; this.what = what; this.heard = heard; this.engine = engine; }
    }

    // ================================================================ the screen

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        Usage.init(this);
        getWindow().setStatusBarColor(0xFF07080B);
        getWindow().setNavigationBarColor(0xFF07080B);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "📱 యాప్ స్టూడియో ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        build();
        newStage();
        ear = new WorkshopVoice(owner(), p);
        Intent i = getIntent();
        // brought back after Android closed it, or opened again from recent apps: never plan or make (and pay) a second time
        if (i != null && (b != null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0)) { i.removeExtra(EXTRA_WHAT); i.removeExtra(EXTRA_CHANGE); }
        handle(i);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String what = i.getStringExtra(EXTRA_WHAT), change = i.getStringExtra(EXTRA_CHANGE), slug = i.getStringExtra(EXTRA_SLUG),
                action = i.getStringExtra(EXTRA_ACTION);
        i.removeExtra(EXTRA_WHAT);
        i.removeExtra(EXTRA_CHANGE);
        i.removeExtra(EXTRA_ACTION);
        if (what != null && !what.trim().isEmpty()) {
            String w0 = what.trim(), h0 = i.getStringExtra(EXTRA_HEARD);
            boolean s0 = i.getBooleanExtra(EXTRA_SAID, false);
            if (front && ear != null && ear.voiceFlow) waitThen(w0, () -> startPlan(w0, h0, s0)); else startPlan(w0, h0, s0);
            return;
        }
        if (change != null && !change.trim().isEmpty()) {
            AppStudio.Made a = slug == null ? null : AppStudio.bySlug(this, slug);
            if (a == null) a = app;
            if (a == null) { setStatus("ఏ యాప్ మార్చాలో దొరకలేదు Sir. 📱 నా యాప్‌లు లో తెరిచి చెప్పండి."); return; }
            if (app == null || !app.slug.equals(a.slug)) show(a);
            String c0 = change.trim();
            boolean s0 = i.getBooleanExtra(EXTRA_SAID, false);
            if (front && ear != null && ear.voiceFlow) waitThen(c0, () -> startChange(c0, s0)); else startChange(c0, s0);
            return;
        }
        if (job != null) return;
        if (slug != null && !slug.isEmpty()) {
            AppStudio.Made a = AppStudio.bySlug(this, slug);
            if (a != null && (app == null || !app.slug.equals(a.slug))) show(a);
            if ("build".equals(action)) ui.postDelayed(this::publish, 500);
            else if ("install".equals(action)) install();
            return;
        }
        if ("list".equals(action)) { showList(); return; }
        if (app == null && plan == null) {
            List<AppStudio.Made> all = AppStudio.list(this);
            if (!all.isEmpty() && "open".equals(action)) show(all.get(0));
            else setStatus("ఎలాంటి యాప్ కావాలో కింద రాయండి Sir, లేదా ఒక ఐడియా తాకండి. ముందు ప్లాన్ చూపిస్తాను, మీరు సరే అన్నాకే తయారు చేస్తాను.");
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF07080B);

        // the top: the app's name, which AI makes it, close
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(10), dp(10), dp(8));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "📱 యాప్ స్టూడియో", 13, Ui.MUTED));
        titleView = Ui.text(this, "", 18, Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(titleView);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        chip = Ui.text(this, "", 11.5f, Ui.CYAN);
        chip.setBackground(Ui.round(this, 0x2216303F, Ui.CYAN_DIM, 999));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        chip.setOnClickListener(v -> pickModel()); // 💰 every model with its cost for one job; his tap picks this studio's AI
        TextView close = Ui.text(this, "✕", 22, Ui.TEXT);
        close.setPadding(dp(16), dp(6), dp(8), dp(6));
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> leave());
        top.addView(close);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // the middle: the preview (a phone-like frame), with the plan or the list over it
        FrameLayout middle = new FrameLayout(this);
        stageBox = new FrameLayout(this);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF0F1318);
        frame.setCornerRadius(dp(18));
        frame.setStroke(dp(1), Ui.LINE2);
        stageBox.setBackground(frame);
        stageBox.setClipToOutline(true);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1);
        sl.leftMargin = sl.rightMargin = dp(10);
        middle.addView(stageBox, sl);
        overlay = new ScrollView(this);
        overlay.setBackground(Ui.round(this, 0xF20B1118, Ui.CYAN_DIM, 18));
        overlayBox = new LinearLayout(this);
        overlayBox.setOrientation(LinearLayout.VERTICAL);
        overlayBox.setPadding(dp(16), dp(14), dp(16), dp(16));
        overlay.addView(overlayBox);
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-1, -1);
        ol.leftMargin = ol.rightMargin = dp(10);
        middle.addView(overlay, ol);
        root.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));

        // the bottom: what Jarvis is doing, the parts with their ticks, ideas, the buttons, the request box
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(8), dp(14), dp(10));
        status = Ui.text(this, "", 14.5f, Ui.TEXT);
        status.setPadding(dp(2), 0, dp(2), dp(4));
        status.setMaxLines(6);
        bottom.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.16f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        listScroll.addView(list);
        bottom.addView(listScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ideaRow = new LinearLayout(this);
        ideaRow.setOrientation(LinearLayout.HORIZONTAL);
        ideaRow.setPadding(0, dp(4), 0, dp(2));
        ideaRow.addView(pill("📸 ఫోటో నుంచి", v -> photoPick()));
        for (String[] idea : IDEAS) ideaRow.addView(pill(idea[0], v -> ideaTapped(idea)));
        ideas = new HorizontalScrollView(this);
        ideas.setHorizontalScrollBarEnabled(false);
        ideas.addView(ideaRow);
        planAi = Ui.text(this, "", 13.5f, Ui.CYAN);
        planAi.setPadding(dp(2), dp(4), 0, dp(2));
        planAi.setOnClickListener(v -> pickModel());
        bottom.addView(planAi);
        bottom.addView(ideas);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        cancelBtn = pill("✋ వద్దు", v -> cancelWait(true));
        stopBtn = pill("⏹ ఆపు", v -> askStop());
        buildBtn = pill("📲 ఫోన్‌లో పెట్టు", v -> publish());
        installBtn = pill("⬇️ ఇన్‌స్టాల్", v -> install());
        fixBtn = pill("🔧 సరిచేయి", v -> fix(false));
        listBtn = pill("📱 నా యాప్‌లు", v -> showList());
        TextView cost = pill("💰 ఖర్చు", v -> { String s = AppStudio.costSaid(this, p); setStatus(s); speak(s, false); });
        newBtn = pill("🆕 కొత్తది", v -> command("new", false));
        versionsBtn = pill("↩️ వెర్షన్లు", v -> showVersions());
        sendBtn = pill("📤 పంపు", v -> sendApp());
        for (TextView t : new TextView[]{cancelBtn, stopBtn, buildBtn, installBtn, fixBtn, listBtn, versionsBtn, sendBtn, cost, newBtn}) actions.addView(t);
        buildBtn.setBackground(Ui.round(this, Ui.CYAN, Ui.CYAN, 999));
        buildBtn.setTextColor(0xFF04131A);
        fixBtn.setTextColor(Ui.GOLD);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        bottom.addView(hs);

        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(15.5f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 14));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
        input.addTextChangedListener(new android.text.TextWatcher() { // (the line above the ideas: this plan's ₹ as he writes)
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { planAiText(); }
        });
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { typed(); return true; }
            return false;
        });
        ask.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        micBtn = Ui.text(this, "🎙️", 19, Ui.TEXT);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setContentDescription("మాట్లాడండి");
        micBtn.setOnClickListener(v -> { if (ear != null) ear.tap(); });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(46), dp(46));
        ml.leftMargin = dp(8);
        ask.addView(micBtn, ml);
        micLook(WorkshopVoice.IDLE);
        go = Ui.text(this, "", 15, 0xFF04131A);
        go.setBackground(Ui.round(this, Ui.CYAN, Ui.CYAN, 14));
        go.setPadding(dp(14), dp(11), dp(14), dp(11));
        go.setOnClickListener(v -> typed());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.leftMargin = dp(8);
        ask.addView(go, gl);
        bottom.addView(ask);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        showChip();
        refresh();
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 13.5f, Ui.TEXT);
        t.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 999));
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 💰 The AI for this studio, picked by his tap from every model his keys can use, each with its cost for what is on the
     * screen: the plan being shown (its size), a change to the open app (its code), else one plan + one usual app.
     */
    private void pickModel() { pickModel(false, null, null); }

    /**
     * (photo: the 📸 dialog's — a plan with a photo and photoWords; after: run once his pick is kept.) With nothing on the card
     * yet, the ₹ is for a plan of what he has written in the box (its size), so each model's cost is for THIS plan.
     */
    private void pickModel(boolean photo, String photoWords, Runnable after) {
        final AppStudio.Plan pl = !photo && "plan".equals(overlayKind) ? plan : null;
        final AppStudio.Made a = !photo && pl == null ? app : null;
        String what = "🧠 ఒక ప్లాన్‌కి ఖర్చు (కింద యాప్ గురించి రాస్తే, దానికి తగ్గట్టు చూపిస్తాను) · తర్వాత మామూలు సైజు యాప్ తయారీ";
        Models.Cost cost = Workshop.costOf(Workshop.APPS);
        final String typed = input == null ? "" : input.getText().toString().trim();
        if (photo) {
            final String w = photoWords == null ? "" : photoWords.trim();
            final String size = w.isEmpty() ? "" : AppStudio.sizeFor(w);
            what = w.isEmpty() ? "📸 ఈ ఫోటోతో ఒక ప్లాన్‌కి ఖర్చు · తర్వాత మామూలు సైజు యాప్ తయారీ"
                    : "📸 ఈ ఫోటో + \"" + Workshop.cap(w, 50) + "\" ప్లాన్‌కి ఖర్చు · తర్వాత యాప్ తయారీ (సైజు అంచనా: " + AppStudio.sizeSaid(size) + ")";
            cost = mm -> "ఈ ప్లాన్ " + Workshop.fine(AppStudio.planRupeesFor(mm, w, true)) + " · యాప్ " + Workshop.fine(AppStudio.buildRupees(mm, size));
        } else if (pl != null) {
            what = "ఈ ప్లాన్ (\"" + pl.title + "\") యాప్ తయారీకి ఖర్చు, ఎక్కువలో ఎక్కువ కూడా";
            cost = mm -> "తయారీ " + Workshop.fine(AppStudio.buildRupees(mm, pl.size)) + " (ఎక్కువలో " + AppStudio.mostMoney(mm) + ")";
        } else if (a != null) {
            what = "తెరిచి ఉన్న యాప్ (\"" + a.title + "\") లో ఒక మార్పుకి ఖర్చు";
            cost = mm -> "మార్పు " + Workshop.fine(AppStudio.changeRupees(mm, a.codeSize()));
        } else if (!typed.isEmpty()) {
            final String size = AppStudio.sizeFor(typed);
            what = "🧠 ఈ ప్లాన్‌కి ఖర్చు: \"" + Workshop.cap(typed, 60) + "\" (" + AppStudio.asks(typed) + " విషయాలు అడిగారు) · తర్వాత యాప్ తయారీ (సైజు అంచనా: "
                    + AppStudio.sizeSaid(size) + "; అసలు సైజు ప్లాన్ చెబుతుంది)";
            cost = mm -> "ఈ ప్లాన్ " + Workshop.fine(AppStudio.planRupeesFor(mm, typed, false)) + " · యాప్ " + Workshop.fine(AppStudio.buildRupees(mm, size));
        }
        Models.pick(this, Workshop.studioName(Workshop.APPS) + " AI ఎంచుకోండి", new String[]{Prefs.OPENAI, Prefs.ANTHROPIC, Prefs.GEMINI},
                new String[]{p.openAiKey(), p.anthropicKey(), p.geminiKey()}, Workshop.modelFor(p, Workshop.APPS), what, cost, it -> {
                    p.setStudioModel(Workshop.APPS, it.id);
                    showChip();
                    if (pl != null && pl == plan && "plan".equals(overlayKind)) showPlan(pl, planCostShown); // (the card's ₹ for the new AI)
                    refresh();
                    if (after != null) after.run();
                    android.widget.Toast.makeText(this, "✓ " + Workshop.studioName(Workshop.APPS) + " AI: " + it.id + " (వర్క్‌షాప్ AI మారలేదు)", android.widget.Toast.LENGTH_LONG).show();
                });
    }

    private void showChip() {
        String m = Workshop.modelFor(p, Workshop.APPS);
        chip.setText(m.isEmpty() ? "AI ఎంచుకోలేదు" : WorkshopActivity.label(m));
    }

    /** 🧠 The AI and about what a plan of what is in the box costs (or any plan, with the box empty). */
    private void planAiText() {
        if (planAi == null || planAi.getVisibility() != View.VISIBLE) return;
        String pm = Workshop.modelFor(p, Workshop.APPS);
        String w = input == null ? "" : input.getText().toString().trim();
        planAi.setText(pm.isEmpty() ? "🤖 AI ఎంచుకోలేదు · ఎంచుకో (ప్రతి మోడల్‌తో ప్లాన్ ఖర్చు) →"
                : "🤖 AI: " + WorkshopActivity.label(pm) + " · " + (w.isEmpty() ? "ప్లాన్‌కి సుమారు " + Workshop.fine(AppStudio.planRupees(pm, false))
                : "ఈ ప్లాన్‌కి సుమారు " + Workshop.fine(AppStudio.planRupeesFor(pm, w, false))) + " · మార్చు (ప్రతి మోడల్‌తో " + (w.isEmpty() ? "" : "ఈ ") + "ప్లాన్ ఖర్చు) →");
    }

    private String planEstimate() {
        String m = Workshop.modelFor(p, Workshop.APPS);
        String w = input == null ? "" : input.getText().toString().trim();
        return m.isEmpty() ? "AI ఎంచుకోలేదు" : "సుమారు " + (w.isEmpty() ? AppStudio.planMoney(m) : Workshop.fine(AppStudio.planRupeesFor(m, w, false)));
    }

    /** The buttons and the box follow what is happening: a job, a plan on the card, an app in the preview, nothing yet. */
    private void refresh() {
        if (go == null) return;
        boolean busy = job != null, hasPlan = plan != null && "plan".equals(overlayKind), hasApp = app != null;
        ideas.setVisibility(!busy && !hasApp && plan == null ? View.VISIBLE : View.GONE);
        if (planAi != null) { planAi.setVisibility(ideas.getVisibility()); planAiText(); }
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancelBtn.setVisibility(waiting != null ? View.VISIBLE : View.GONE);
        newBtn.setVisibility(!busy && (hasApp || plan != null) ? View.VISIBLE : View.GONE);
        boolean ready = hasApp && !busy && app.tested && app.brokenId == null;
        buildBtn.setVisibility(ready && !publishing && !"building".equals(app.buildState) ? View.VISIBLE : View.GONE);
        buildBtn.setText(hasApp && "ok".equals(app.buildState) && app.builtVersion > 0 ? "📲 ఫోన్‌లో మళ్ళీ పెట్టు (v" + app.version + ")" : "📲 ఫోన్‌లో పెట్టు");
        installBtn.setVisibility(hasApp && !busy && "ok".equals(app.buildState) && !app.repo.isEmpty() ? View.VISIBLE : View.GONE);
        String m = Workshop.modelFor(p, Workshop.APPS);
        fixBtn.setVisibility(hasApp && !busy && app.brokenId != null ? View.VISIBLE : View.GONE);
        versionsBtn.setVisibility(hasApp && !busy && versionCount >= 2 ? View.VISIBLE : View.GONE);
        sendBtn.setVisibility(hasApp && !busy && "ok".equals(app.buildState) && !app.repo.isEmpty() ? View.VISIBLE : View.GONE);
        if (hasApp && app.brokenId != null) fixBtn.setText("🔧 సరిచేయి" + (m.isEmpty() ? "" : " (" + AppStudio.repairMoney(m, app.codeSize()) + ")"));
        go.setText(hasPlan ? "✏️ ప్లాన్ మార్చు" : hasApp ? "✏️ మార్చు" : "🧠 ప్లాన్ చూపించు");
        input.setHint(hasPlan ? "ప్లాన్‌లో మార్పు? ఉదా: ఇంకో స్క్రీన్ కావాలి" : hasApp ? "ఈ యాప్‌లో మార్పు? ఉదా: ఒక సెర్చ్ బాక్స్ పెట్టు"
                : "ఏ యాప్ కావాలి? ఉదా: ఖర్చుల లెక్క");
        go.setEnabled(!busy);
        go.setAlpha(busy ? 0.4f : 1f);
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

    private void typed() {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) { input.requestFocus(); return; }
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.setText("");
        if (ear != null) ear.typed();
        if (waiting != null) cancelWait(false);
        said(s, false);
    }

    // ================================================================ what he says (typed here, or said to Jarvis over this screen)

    private void said(String text, boolean byVoice) {
        String cmd = AppStudio.command(text);
        if (cmd != null) { command(cmd, byVoice); return; }
        if (job != null) { say0("ఒక పని ఇంకా జరుగుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.", byVoice); return; }
        Runnable r;
        if (plan != null && "plan".equals(overlayKind)) r = () -> startReplan(text);
        else if (app != null) r = () -> startChange(text, false);
        else r = () -> startPlan(text, text, false);
        if (byVoice) waitThen(text, r); // (heard by voice: ✋ 3 seconds to say "వద్దు" before any money is spent)
        else r.run();
    }

    /** A ₹0 word: done at once, no AI (except "go": the plan on the card is made, after ✋ 3 seconds when heard by voice). */
    private void command(String cmd, boolean byVoice) {
        switch (cmd) {
            case "list": showList(); return;
            case "cost": { String s = AppStudio.costSaid(this, p); setStatus(s); speak(s, byVoice); return; }
            case "build": publish(); return;
            case "fix": fix(byVoice); return;
            case "versions": showVersions(); return;
            case "send": sendApp(); return;
            case "go":
                if (plan == null) { say0(app != null ? "ఈ యాప్ ఇప్పటికే తయారైంది Sir. ఫోన్‌లో కావాలంటే 📲 ఫోన్‌లో పెట్టు అనండి." : "ముందు ఏ యాప్ కావాలో చెప్పండి Sir.", byVoice); return; }
                if (byVoice) waitThen("ఇలాగే తయారు చేయి", this::startMake); else startMake();
                return;
            case "new":
                if (job != null) return;
                clearAll();
                say0("ఏ కొత్త యాప్ కావాలి Sir? కింద రాయండి, లేదా ఒక ఐడియా తాకండి.", byVoice);
                return;
            case "close": leave(); return;
            default:
        }
    }

    private void say0(String s, boolean byVoice) { setStatus(s); if (byVoice) speak(s, true); }

    private void clearAll() {
        plan = null;
        app = null;
        openTitle = "";
        rows.clear();
        list.removeAllViews();
        titleView.setText("");
        hideOverlay();
        blankStage();
        refresh();
    }

    // ================================================================ ✋ a paid job heard by voice waits 3 seconds

    private void waitThen(String words, Runnable then) {
        cancelWait(false);
        waiting = words;
        waitSince = System.currentTimeMillis();
        waitGo = new Runnable() {
            @Override public void run() {
                if (waiting == null || destroyed) return;
                if (ear != null && ear.hearing() && System.currentTimeMillis() - waitSince < 8000) { ui.postDelayed(this, 400); return; }
                waiting = null;
                refresh();
                then.run();
            }
        };
        refresh();
        setStatus("✏️ “" + words + "” · 3 సెకన్లలో మొదలుపెడతాను · వద్దంటే ✋ వద్దు");
        ui.postDelayed(waitGo, 3000);
        if (ear != null && front) ui.postDelayed(() -> { if (waiting != null && ear != null) ear.listenShort(3); }, 300);
    }

    private boolean waitHeard(String words) {
        if (waiting == null) return false;
        if (Workshop.isCancel(words)) { cancelWait(true); return true; }
        cancelWait(false);
        return false;
    }

    private void cancelWait(boolean said) {
        if (waitGo != null) ui.removeCallbacks(waitGo);
        boolean was = waiting != null;
        waiting = null;
        waitGo = null;
        refresh();
        if (said && was) {
            setStatus("✋ సరే Sir, చేయలేదు · ఖర్చు ₹0");
            speak("సరే Sir, చేయలేదు.", true);
        }
    }

    // ================================================================ 🎙️ the studio's own ears

    private WorkshopVoice.Owner owner() {
        return new WorkshopVoice.Owner() {
            @Override public Activity screen() { return AppStudioActivity.this; }
            @Override public boolean take(String words) { return tookByVoice(words); }
            @Override public void line(String text) { setStatus(text); }
            @Override public void mic(int state) { micLook(state); }
            @Override public boolean front() { return front && !destroyed; }
            @Override public boolean waitWords(String words) { return waitHeard(words); }
        };
    }

    /** Heard by voice here: a ₹0 word, a new app, or plainly a change to the plan / app on the screen. Anything else goes to Jarvis's brain. */
    private boolean tookByVoice(String words) {
        String cmd = AppStudio.command(words);
        if (cmd != null) { command(cmd, true); return true; }
        if (job != null) return false;
        if (AppStudio.wantsApp(words) && !AppStudio.wantsChange(words)) { waitThen(words, () -> startPlan(words, words, false)); return true; }
        if ((app != null || (plan != null && "plan".equals(overlayKind))) && AppStudio.wantsChange(words)) { said(words, true); return true; }
        return false;
    }

    private void micLook(int state) {
        if (micBtn == null) return;
        int bg = state == WorkshopVoice.LISTENING ? Ui.CYAN : state == WorkshopVoice.THINKING ? 0x33F5C451 : state == WorkshopVoice.SPEAKING ? 0x334FD2FF : 0x1AFFFFFF;
        micBtn.setBackground(Ui.round(this, bg, state == WorkshopVoice.LISTENING ? Ui.CYAN : Ui.LINE2, 999));
        micBtn.setText(state == WorkshopVoice.LISTENING ? "👂" : "🎙️");
    }

    private void speak(String s, boolean thenListen) {
        if (s == null || s.trim().isEmpty()) return;
        if (ear != null && ear.voiceFlow && front && !destroyed) ear.say(s, thenListen);
        else Announcer.say(this, s);
    }

    // ================================================================ the card over the preview: the plan, his apps

    private void hideOverlay() {
        overlayKind = "";
        overlay.setVisibility(View.GONE);
        overlayBox.removeAllViews();
    }

    private TextView cardButton(String s, int bg, int ink, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15.5f, ink);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, bg, bg == 0 ? Ui.LINE2 : bg, 14));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setOnClickListener(l);
        return t;
    }

    /** The plan's own cost line as last shown (kept when the card is drawn again for another AI). */
    private String planCostShown;

    private void showPlan(AppStudio.Plan pl, String cost) {
        planCostShown = cost;
        overlayKind = "plan";
        overlayBox.removeAllViews();
        TextView h = Ui.text(this, "📋 " + pl.title, 20, Ui.TEXT);
        overlayBox.addView(h);
        TextView body = Ui.text(this, AppStudio.planText(pl), 14.5f, Ui.TEXT);
        body.setLineSpacing(0, 1.18f);
        body.setPadding(0, dp(8), 0, dp(10));
        overlayBox.addView(body);
        String m = Workshop.modelFor(p, Workshop.APPS);
        TextView money = Ui.text(this, "💰 తయారీ సుమారు " + (m.isEmpty() ? "?" : AppStudio.buildMoney(m, pl.size)) + " (ఎక్కువలో ఎక్కువ "
                + (m.isEmpty() ? "?" : AppStudio.mostMoney(m)) + ")" + (cost == null ? "" : " · ఈ ప్లాన్‌కి " + cost)
                + "\n📲 ఫోన్‌లో పెట్టడం, ప్రివ్యూ చూడటం ₹0", 13.5f, Ui.GOLD);
        money.setPadding(0, 0, 0, dp(4));
        overlayBox.addView(money);
        // 🤖 which AI makes it, and every model's cost for THIS plan (his tap picks; the workshop's AI stays)
        TextView ai = Ui.text(this, "🤖 AI: " + (m.isEmpty() ? "ఎంచుకోలేదు" : WorkshopActivity.label(m)) + " · మార్చు (ఈ ప్లాన్‌కి ప్రతి మోడల్ ఖర్చు) →", 13.5f, Ui.CYAN);
        ai.setPadding(0, dp(6), 0, dp(12));
        ai.setOnClickListener(v -> pickModel());
        overlayBox.addView(ai);
        TextView ok = cardButton("✅ ఇలాగే తయారు చేయి", Ui.CYAN, 0xFF04131A, v -> startMake());
        overlayBox.addView(ok, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView edit = cardButton("✏️ మార్చు", 0x1AFFFFFF, Ui.TEXT, v -> { input.requestFocus(); setStatus("ప్లాన్‌లో ఏం మార్చాలో కింద రాయండి Sir (ఉదా: \"ఇంకో స్క్రీన్: నెలల వారీ లెక్క\")."); });
        TextView no = cardButton("✕ వద్దు", 0x1AFFFFFF, Ui.TEXT, v -> { plan = null; hideOverlay(); refresh(); setStatus("సరే Sir, ఈ ప్లాన్ వదిలేశాను."); });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1);
        a.rightMargin = dp(8);
        row.addView(edit, a);
        row.addView(no, new LinearLayout.LayoutParams(0, -2, 1));
        overlayBox.addView(row);
        overlay.setVisibility(View.VISIBLE);
        overlay.scrollTo(0, 0);
        refresh();
    }

    private void showList() {
        if (job != null) return;
        overlayKind = "list";
        overlayBox.removeAllViews();
        overlayBox.addView(Ui.text(this, "📱 నా యాప్‌లు", 20, Ui.TEXT));
        List<AppStudio.Made> all = AppStudio.list(this);
        List<String> old = AppStudio.oldApps(this);
        if (all.isEmpty() && old.isEmpty()) {
            TextView e = Ui.text(this, "ఇంకా యాప్‌లు లేవు Sir. కింద ఏ యాప్ కావాలో రాయండి.", 14.5f, Ui.MUTED);
            e.setPadding(0, dp(12), 0, dp(12));
            overlayBox.addView(e);
        }
        for (AppStudio.Made a : all) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(this, 0x14FFFFFF, Ui.LINE2, 14));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.addView(Ui.text(this, a.title, 16.5f, Ui.TEXT));
            String state = "ok".equals(a.buildState) ? (a.needsBuild() ? "📲 ఫోన్‌లో v" + a.builtVersion + " · మారింది, మళ్ళీ పెట్టాలి" : "📲 ఫోన్‌కి సిద్ధం (v" + a.builtVersion + ")")
                    : "building".equals(a.buildState) ? "⏳ GitHub లో బిల్డ్ అవుతోంది" : "failed".equals(a.buildState) ? "❌ చివరి బిల్డ్ కాలేదు" : "ఇంకా ఫోన్‌లో పెట్టలేదు";
            TextView line = Ui.text(this, "v" + a.version + " · " + a.parts.size() + " భాగాలు · " + Workshop.money(a.cost) + (a.estimated ? " (సుమారు)" : "")
                    + "\n" + state + (a.brokenId != null ? " · ⚠️ ఒక తప్పు ఉంది" : ""), 13, Ui.MUTED);
            line.setPadding(0, dp(3), 0, dp(8));
            card.addView(line);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(pill("▶️ తెరువు", v -> { hideOverlay(); show(a); }));
            row.addView(pill("🗑️", v -> askDelete(a)));
            card.addView(row);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = dp(10);
            overlayBox.addView(card, cl);
        }
        if (!old.isEmpty()) {
            TextView h = Ui.text(this, "పాత పద్ధతిలో చేసినవి (ప్రివ్యూ లేదు):", 13.5f, Ui.MUTED);
            h.setPadding(0, dp(14), 0, dp(4));
            overlayBox.addView(h);
            for (String slug : old) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                row.addView(Ui.text(this, slug, 14, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(pill("⬇️ ఇన్‌స్టాల్", v -> installOld(slug)));
                overlayBox.addView(row);
            }
        }
        TextView close = cardButton("మూసేయి", 0x1AFFFFFF, Ui.TEXT, v -> { hideOverlay(); refresh(); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(14);
        overlayBox.addView(close, cl);
        overlay.setVisibility(View.VISIBLE);
        overlay.scrollTo(0, 0);
        setStatus(all.isEmpty() ? "" : "తెరిస్తే ప్రివ్యూలో వాడి చూడొచ్చు, మార్చొచ్చు, ఫోన్‌లో పెట్టొచ్చు · ఖర్చు లేదు");
        refresh();
    }

    private void askDelete(AppStudio.Made a) {
        new AlertDialog.Builder(this).setTitle("\"" + a.title + "\" తీసేయాలా?")
                .setMessage("ఈ లిస్ట్ నుంచి మాత్రమే తీసేస్తాను. మీ ఫోన్‌లో పెట్టిన యాప్, మీ GitHub లోని దాని ప్రైవేట్ repo అలాగే ఉంటాయి "
                        + "(కావాలంటే ఫోన్ సెట్టింగ్స్‌లో యాప్‌ని, github.com లో repo ని మీరే తీసేయవచ్చు).")
                .setPositiveButton("తీసేయి", (d, w) -> {
                    AppStudio.delete(this, a.slug);
                    if (app != null && app.slug.equals(a.slug)) { app = null; titleView.setText(""); blankStage(); }
                    showList();
                    setStatus("\"" + a.title + "\" లిస్ట్ నుంచి తీసేశాను Sir.");
                }).setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ the sealed preview

    private void newStage() {
        if (web != null) {
            stageBox.removeView(web);
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        port = null;
        stageReady = false;
        WebView w = new WebView(this);
        web = w;
        w.setBackgroundColor(0xFF0F1318);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setSaveFormData(false);
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl(); // (only the preview frame's own page may load; nothing navigates anywhere else)
                return !(u != null && HOST.equals(u.getHost()) && (PATH + "stage.html").equals(u.getPath()));
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (v == web && url != null && url.startsWith(HARNESS)) connect(v);
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> restartStage("ఆగిపోయిన ప్రివ్యూని మళ్ళీ మొదలుపెట్టాను Sir."));
                return true; // (never takes Jarvis down with it)
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) { cb.invoke(o, false, false); }
            @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
            @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
            @Override public boolean onJsPrompt(WebView v, String u, String m, String d, JsPromptResult r) { r.cancel(); return true; }
            @Override public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });
        stageBox.addView(w, new FrameLayout.LayoutParams(-1, -1));
        w.loadUrl(HARNESS);
    }

    /** Only the studio's own files, from the app; anything else is answered "blocked" here and never leaves the phone. */
    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            if (FILES.contains(name)) {
                try {
                    InputStream in = getAssets().open("apps/" + name);
                    String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css" : "text/javascript";
                    return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
                } catch (Exception ignored) {}
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", h, new ByteArrayInputStream(new byte[0]));
    }

    private void connect(WebView v) {
        if (port != null) return;
        try {
            WebMessagePort[] ch = v.createWebMessageChannel();
            port = ch[0];
            port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                @Override public void onMessage(WebMessagePort pt, WebMessage m) { fromStage(m == null ? null : m.getData()); }
            }, ui);
            v.postWebMessage(new WebMessage("jarvis-port", new WebMessagePort[]{ch[1]}), Uri.parse("https://" + HOST));
            List<String> q = new ArrayList<>(outbox);
            outbox.clear();
            for (String s : q) port.postMessage(new WebMessage(s));
        } catch (Throwable e) {
            port = null;
            setStatus("ప్రివ్యూ పేజీ తెరవలేకపోయాను Sir: " + e.getMessage());
        }
    }

    private static JSONObject json(String t) {
        try { return new JSONObject().put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    private void send(JSONObject m) {
        if (m == null) return;
        Runnable r = () -> {
            if (destroyed) return;
            String s = m.toString();
            if (port == null) outbox.add(s);
            else { try { port.postMessage(new WebMessage(s)); } catch (Throwable e) { outbox.add(s); } }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    /** What the preview says (on the main thread). */
    private void fromStage(String data) {
        if (data == null) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        String t = m.optString("t"), id = m.optString("id");
        switch (t) {
            case "ready":
                synchronized (acks) { stageReady = true; acks.notifyAll(); }
                return;
            case "ok":
                synchronized (acks) { acks.put(id, new String[]{"ok", ""}); acks.notifyAll(); }
                return;
            case "started":
                synchronized (acks) { startedOk = true; acks.notifyAll(); }
                return;
            case "tested":
                synchronized (acks) { testAns = new String[]{"ok", String.valueOf(m.optInt("taps")), ""}; acks.notifyAll(); }
                return;
            case "err": {
                String st = m.optString("state"), msg = m.optString("msg", "error");
                if ("test".equals(st)) synchronized (acks) { testAns = new String[]{"err", id, msg}; acks.notifyAll(); }
                else if ("load".equals(st)) synchronized (acks) { acks.put(id, new String[]{"err", msg}); acks.notifyAll(); }
                else if ("start".equals(st)) synchronized (acks) { testAns = new String[]{"err", "", msg}; startedOk = false; acks.notifyAll(); }
                else runErr(id, msg);
                return;
            }
            default:
        }
    }

    private void restartStage(String why) {
        if (destroyed) return;
        newStage();
        synchronized (acks) { acks.notifyAll(); } // (a job waiting on the old page: it sees no answer and goes on)
        if (job == null && app != null) loadPreviewLater();
        setStatus(why);
    }

    /** A stuck page: stopped (onRenderProcessGone then starts a fresh one), or started again. */
    private void killStage() {
        if (Build.VERSION.SDK_INT >= 29 && web != null) {
            try {
                Object rp = WebView.class.getMethod("getWebViewRenderProcess").invoke(web);
                if (rp != null && Boolean.TRUE.equals(rp.getClass().getMethod("terminate").invoke(rp))) return;
            } catch (Throwable ignored) {}
        }
        restartStage("ప్రివ్యూని మళ్ళీ మొదలుపెట్టాను Sir.");
    }

    private void blankStage() {
        send(json("reload"));
    }

    /** (job thread) A fresh preview page with this app's parts in order: null when all loaded, else {part id, error}. */
    private String[] loadAll(AppStudio.Made a, Job j) {
        synchronized (acks) { acks.clear(); testAns = null; startedOk = false; stageReady = false; }
        send(json("reload"));
        long until = System.currentTimeMillis() + READY_WAIT_MS;
        synchronized (acks) {
            while (!stageReady && System.currentTimeMillis() < until && !destroyed && (j == null || !j.stopped)) {
                try { acks.wait(Math.max(50, until - System.currentTimeMillis())); } catch (InterruptedException e) { return new String[]{"", "stopped"}; }
            }
        }
        if (!stageReady) { ui.post(this::killStage); return new String[]{"", "the preview page did not open"}; }
        try {
            JSONObject su = json("setup").put("name", a.title).put("color", a.color).put("theme", a.theme);
            if (a.plan != null) { JSONObject ic = AppStudio.cleanIcon(a.plan.optJSONObject("icon")); if (ic != null) su.put("icon", ic); su.put("jarvis", AppStudio.cleanJarvis(a.plan.optJSONArray("jarvis"))); }
            send(su);
        } catch (Exception ignored) {}
        List<AppStudio.Part> ps = new ArrayList<>(a.parts.values());
        for (AppStudio.Part pt : ps) {
            try { send(json("part").put("id", pt.id).put("css", pt.css).put("html", pt.html).put("js", pt.js)); } catch (Exception ignored) {}
        }
        for (AppStudio.Part pt : ps) {
            String[] r = awaitAck(pt.id, j);
            if (r == null) { ui.post(this::killStage); return new String[]{pt.id, "the preview took too long loading this part (an endless loop?)"}; }
            if ("err".equals(r[0])) return new String[]{pt.id, r[1].isEmpty() ? "error" : r[1]};
            if (j != null) ui.post(() -> row(pt.id, "✓ " + pt.status, Ui.OK));
        }
        return null;
    }

    private String[] awaitAck(String id, Job j) {
        long until = System.currentTimeMillis() + ACK_WAIT_MS;
        synchronized (acks) {
            String[] a;
            while ((a = acks.get(id)) == null && System.currentTimeMillis() < until && !destroyed && (j == null || !j.stopped)) {
                try { acks.wait(Math.max(50, until - System.currentTimeMillis())); } catch (InterruptedException e) { return null; }
            }
            return a;
        }
    }

    /** (job thread) 🧪 the kit taps through every screen: {"ok", taps} / {"err", part, message} / null (frozen: the page is started again). */
    private String[] selfTest(Job j) {
        synchronized (acks) { testAns = null; }
        ui.post(() -> setStatus("🧪 యాప్ తనంతట తానే అన్ని స్క్రీన్లూ తెరిచి, బటన్లు నొక్కి చూస్తోంది (తప్పులు ఉంటే ఇప్పుడే సరిచేస్తాను)…"));
        try { send(json("test").put("ms", 20000)); } catch (Exception ignored) {}
        long until = System.currentTimeMillis() + TEST_WAIT_MS;
        String[] a;
        synchronized (acks) {
            while ((a = testAns) == null && System.currentTimeMillis() < until && !destroyed && !j.stopped) {
                try { acks.wait(Math.max(50, until - System.currentTimeMillis())); } catch (InterruptedException e) { return null; }
            }
            testAns = null;
        }
        if (a == null && !j.stopped) ui.post(this::killStage);
        return a;
    }

    /** The app on the preview, ready to use by hand (₹0); one never tested (the preview would not open then) is tested first, for free. */
    private void loadPreviewLater() {
        AppStudio.Made a = app;
        if (a == null) return;
        new Thread(() -> {
            String[] bad = loadAll(a, null);
            if (bad == null && !a.tested && a.brokenId == null) {
                Job probe = new Job("probe", "", "", null);
                String[] t = selfTest(probe);
                if (t != null && "ok".equals(t[0])) a.tested = true;
                else if (t != null) bad = new String[]{t[1] == null || t[1].isEmpty() || !a.parts.containsKey(t[1]) ? guessPart(a, t[2]) : t[1], t[2]};
                if (t != null) AppStudio.save(this, a);
                if (bad == null) bad = loadAll(a, null);
                ui.post(this::refresh);
            }
            final String[] badNow = bad;
            if (badNow == null) {
                send(json("start"));
                ui.post(() -> { if (app == a && job == null) setStatus(readyLine(a)); });
            } else {
                ui.post(() -> {
                    if (app != a) return;
                    a.brokenId = badNow[0].isEmpty() ? lastPartId(a) : badNow[0];
                    a.brokenMsg = badNow[1];
                    AppStudio.save(this, a);
                    refresh();
                    setStatus("⚠️ ప్రివ్యూలో ఒక తప్పు: " + Workshop.cap(badNow[1], 140) + " · 🔧 సరిచేయి నొక్కండి");
                });
            }
        }, "jarvis-app-preview").start();
    }

    private String readyLine(AppStudio.Made a) {
        String s = "✓ \"" + a.title + "\" ప్రివ్యూలో వాడి చూడండి (ఇక్కడ దాచినవి ప్రివ్యూ మూస్తే పోతాయి; ఫోన్ యాప్‌లో దాచుకుంటుంది).";
        if (a.brokenId != null) return "⚠️ \"" + a.title + "\": ఒక తప్పు మిగిలింది · 🔧 సరిచేయి";
        if ("building".equals(a.buildState)) return s + " ⏳ GitHub లో బిల్డ్ అవుతోంది.";
        if ("ok".equals(a.buildState) && !a.needsBuild()) return s + " ⬇️ ఇన్‌స్టాల్ నొక్కితే ఫోన్‌లో పెడతాను.";
        return s + " నచ్చితే 📲 ఫోన్‌లో పెట్టు.";
    }

    /** An error while he uses the preview: kept on the app, 🔧 repairs that part. */
    private void runErr(String id, String msg) {
        AppStudio.Made a = app;
        if (a == null || job != null) return;
        String pid = id == null || id.isEmpty() || !a.parts.containsKey(id) ? lastPartId(a) : id;
        a.brokenId = pid;
        a.brokenMsg = msg;
        AppStudio.save(this, a);
        refresh();
        setStatus("⚠️ ప్రివ్యూలో ఒక తప్పు కనిపించింది (" + pid + "): " + Workshop.cap(msg, 120) + " · 🔧 సరిచేయి నొక్కితే ఆ భాగం ఒక్కటే సరిచేస్తాను.");
    }

    private static String lastPartId(AppStudio.Made a) {
        String id = null;
        for (String k : a.parts.keySet()) id = k;
        return id == null ? "" : id;
    }

    /** Opens a saved app in the preview (₹0). */
    private void show(AppStudio.Made a) {
        app = a;
        versionCount = AppStudio.versions(this, a.slug).size();
        plan = null;
        openTitle = a.title;
        hideOverlay();
        titleView.setText(a.title);
        rows.clear();
        list.removeAllViews();
        refresh();
        setStatus("ప్రివ్యూ సిద్ధం చేస్తున్నాను…");
        loadPreviewLater();
    }

    // ================================================================ 📦 ideas and ready starters (₹0), 📸 a photo to plan from

    /** An idea chip: with a ready starter, the choice of ₹0 now or a new plan by the AI; else its words go into the box. */
    private void ideaTapped(String[] idea) {
        String starter = null;
        for (String[] s : AppStudio.STARTERS) if (s[1].equals(idea[0])) { starter = s[0]; break; }
        Runnable words = () -> {
            input.setText(idea[1]);
            input.setSelection(input.getText().length());
            input.requestFocus();
            setStatus("✏️ కావాలంటే మార్చి, 🧠 ప్లాన్ చూపించు నొక్కండి · ప్లాన్‌కి " + planEstimate());
        };
        if (starter == null) { words.run(); return; }
        String id = starter;
        new AlertDialog.Builder(this).setTitle(idea[0])
                .setItems(new String[]{"📦 రెడీగా ఉన్నది తెరువు (₹0, వెంటనే)", "🧠 AI తో కొత్తగా ప్లాన్ (" + planEstimate() + ")"}, (d, w) -> {
                    if (w == 0) openStarter(id); else words.run();
                }).setNegativeButton("వద్దు", null).show();
    }

    private void openStarter(String id) {
        if (job != null) return;
        AppStudio.Made a;
        try { a = AppStudio.starter(asset("apps/starters/" + id + ".json"), AppStudio.slugs(this)); }
        catch (Exception e) { a = null; }
        if (a == null) { setStatus("ఆ రెడీ యాప్ తెరవలేకపోయాను Sir."); return; }
        AppStudio.save(this, a);
        AppStudio.saveVersion(this, a);
        show(a);
        setStatus("📦 \"" + a.title + "\" రెడీ (₹0). ఉచితంగా పరీక్షిస్తున్నాను… తర్వాత ప్రివ్యూలో వాడి చూడండి; ఏమైనా మార్చాలంటే కింద రాయండి.");
        speak(a.title + " రెడీ Sir, ఖర్చు లేదు.", false);
    }

    private void photoPick() {
        if (job != null) return;
        new AlertDialog.Builder(this).setTitle("📸 ఏ యాప్ లాంటిది?")
                .setMessage("ఒక యాప్ స్క్రీన్‌షాట్, లేదా పేపర్ మీద మీరు గీసిన యాప్ బొమ్మ. ఫోటో ఈ ప్లాన్ కోసం మీ AI కి మాత్రమే వెళ్తుంది; ఎక్కడా దాచను. "
                        + "యాప్ మన సొంత డిజైన్‌లో వస్తుంది (వాళ్ళ పేరు, లోగో, మాటలు కాపీ చేయదు).")
                .setPositiveButton("📷 కెమెరా", (d, w) -> camPick()).setNeutralButton("🖼️ గ్యాలరీ", (d, w) -> galPick()).setNegativeButton("వద్దు", null).show();
    }

    private void camPick() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        java.io.File f = PhotoProvider.file(this);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
        Uri out = PhotoProvider.uri();
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(android.content.ClipData.newRawUri("photo", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i, REQ_CAM); } catch (Exception e) { setStatus("కెమెరా తెరవలేకపోయాను Sir; గ్యాలరీ నుంచి ఎంచుకోండి."); }
    }

    private void galPick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "ఫోటో ఎంచుకోండి"), REQ_GAL); } catch (Exception e) { setStatus("గ్యాలరీ తెరవలేకపోయాను Sir."); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_CAM_PERM) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) camPick();
        else setStatus("📷 కెమెరా అనుమతి లేకుండా ఫోటో తీయలేను Sir. 🖼️ గ్యాలరీ నుంచి ఎంచుకోవచ్చు.");
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAM && req != REQ_GAL) return;
        if (result != RESULT_OK) { if (req == REQ_CAM) //noinspection ResultOfMethodCallIgnored
            PhotoProvider.file(this).delete(); return; }
        Uri u = req == REQ_CAM ? PhotoProvider.uri() : data != null ? data.getData() : null;
        if (u == null) return;
        new Thread(() -> {
            android.graphics.Bitmap bm = FacePhotos.upright(this, u, 1600);
            if (req == REQ_CAM) //noinspection ResultOfMethodCallIgnored
                PhotoProvider.file(this).delete(); // (his photo is not kept here)
            if (bm == null) { ui.post(() -> setStatus("ఫోటో తెరవలేకపోయాను Sir.")); return; }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            final String b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            int side = Math.max(bm.getWidth(), bm.getHeight());
            final android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(bm, Math.max(1, bm.getWidth() * 480 / side), Math.max(1, bm.getHeight() * 480 / side), true);
            ui.post(() -> { if (!destroyed) photoReady(b64, thumb); });
        }, "jarvis-app-photo").start();
    }

    private static String photoGo(String m, String words) {
        return "🧠 ప్లాన్ చూపించు" + (m.isEmpty() ? "" : " (" + (words == null || words.trim().isEmpty() ? AppStudio.planMoney(m, true)
                : Workshop.fine(AppStudio.planRupeesFor(m, words, true))) + ")");
    }

    /** The photo, his words (optional), and his tap: the photo goes to his AI with the plan request, and is not kept. */
    private void photoReady(String b64, android.graphics.Bitmap thumb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(dp(240));
        box.addView(iv);
        EditText words = new EditText(this);
        words.setHint("ఏమైనా చెప్పాలా? ఉదా: ఇలాంటి పాల లెక్క యాప్ (ఖాళీ అయినా సరే)");
        words.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(words);
        String m = Workshop.modelFor(p, Workshop.APPS);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("📸 ఈ ఫోటోతో ప్లాన్")
                .setView(box)
                .setPositiveButton(photoGo(m, ""), (d, w) -> {
                    String s = words.getText().toString().trim();
                    startPlan(s, s, false, b64);
                }).setNeutralButton("🤖 మోడల్", null).setNegativeButton("వద్దు", null).show();
        // (🤖: every model's cost for a plan with this photo; the dialog stays, its button's ₹ follows the pick)
        android.widget.Button nb = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (nb != null) nb.setOnClickListener(v -> pickModel(true, words.getText().toString(), () -> {
            android.widget.Button go2 = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (go2 != null) go2.setText(photoGo(Workshop.modelFor(p, Workshop.APPS), words.getText().toString()));
        }));
    }

    // ================================================================ ↩️ versions, 📤 send to family

    private void showVersions() {
        AppStudio.Made a = app;
        if (a == null || job != null) return;
        List<AppStudio.Made> vs = AppStudio.versions(this, a.slug);
        if (vs.size() < 2) { setStatus("ఈ యాప్‌కి ఇంకా ఒక్క వెర్షనే ఉంది Sir."); return; }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", new java.util.Locale("te", "IN"));
        String[] items = new String[vs.size()];
        for (int i = 0; i < vs.size(); i++) {
            AppStudio.Made v = vs.get(i);
            items[i] = "v" + v.version + " · " + f.format(new java.util.Date(v.changedAt)) + " · " + v.parts.size() + " భాగాలు" + (v.version == a.version ? "  (ఇప్పుడు ఇదే)" : "");
        }
        new AlertDialog.Builder(this).setTitle("↩️ \"" + a.title + "\" వెర్షన్లు").setItems(items, (d, w) -> {
            AppStudio.Made v = vs.get(w);
            if (v.version == a.version) return;
            new AlertDialog.Builder(this).setTitle("v" + v.version + " కి తిరిగి వెళ్ళాలా?")
                    .setMessage("ఇప్పటి v" + a.version + " కూడా దాచి ఉంటుంది; కావాలంటే మళ్ళీ దానికి రావచ్చు. ఖర్చు ₹0. ఫోన్‌లో కావాలంటే తర్వాత 📲 నొక్కండి.")
                    .setPositiveButton("↩️ తిరిగి వెళ్ళు", (d2, w2) -> restore(a, v)).setNegativeButton("వద్దు", null).show();
        }).setNegativeButton("మూసేయి", null).show();
    }

    /** An old version comes back as the newest one (its parts, plan and look; this app's build state, repo and key stay). */
    private void restore(AppStudio.Made now, AppStudio.Made old) {
        AppStudio.Made r = copyOf(old);
        r.version = now.version + 1;
        r.changedAt = System.currentTimeMillis();
        r.cost = now.cost;
        r.estimated = now.estimated;
        r.repo = now.repo;
        r.builtCommit = now.builtCommit;
        r.buildState = now.buildState;
        r.builtVersion = now.builtVersion;
        r.buildAt = now.buildAt;
        r.key = now.key;
        AppStudio.save(this, r);
        AppStudio.saveVersion(this, r);
        show(r);
        setStatus("↩️ v" + old.version + " ని తిరిగి తెచ్చాను (ఇప్పుడు v" + r.version + "). ఫోన్‌లో కావాలంటే 📲 ఫోన్‌లో పెట్టు.");
    }

    /** 📤 The app's latest APK to family (WhatsApp etc.), from the share screen: only on his tap, after he says yes. */
    private void sendApp() {
        AppStudio.Made a = app;
        if (a == null || a.repo.isEmpty() || !"ok".equals(a.buildState)) { setStatus("ముందు 📲 ఫోన్‌లో పెట్టు; బిల్డ్ అయ్యాక పంపొచ్చు Sir."); return; }
        new AlertDialog.Builder(this).setTitle("📤 \"" + a.title + "\" పంపాలా?")
                .setMessage("యాప్ ఫైల్ (APK) Downloads/Jarvis/apps లో సేవ్ చేసి, మీరు ఎంచుకున్న చోటికి (WhatsApp…) పంపుతాను. "
                        + "అందుకున్నవాళ్ళు \"Install\" నొక్కాలి. వాళ్ళ డేటా వాళ్ళ ఫోన్‌లోనే ఉంటుంది; మీ డేటా వెళ్ళదు.")
                .setPositiveButton("📤 పంపు", (d, w) -> {
                    setStatus("📤 యాప్ ఫైల్ తెస్తున్నాను…");
                    Context c = getApplicationContext();
                    new Thread(() -> {
                        try {
                            byte[] apk = AppMaker.apkBytes(c, a.repo);
                            String name = (a.name == null || a.name.isEmpty() ? a.slug : a.name).replaceAll("[\\\\/:*?\"<>|]", " ").trim() + ".apk";
                            Coder.Made m = Coder.save(c, "Jarvis/apps", name, "application/vnd.android.package-archive", apk);
                            ui.post(() -> {
                                if (destroyed) return;
                                setStatus("💾 సేవ్ అయింది: " + m.where);
                                if (m.uri == null) return;
                                try {
                                    Intent s = new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, m.uri)
                                            .putExtra(Intent.EXTRA_TEXT, "📱 " + a.title + " (Jarvis చేసిన యాప్)").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
                                } catch (Exception e) { setStatus("💾 సేవ్ అయింది: " + m.where + " (షేర్ తెరవలేకపోయాను)"); }
                            });
                        } catch (Exception e) {
                            ui.post(() -> setStatus("📤 యాప్ ఫైల్ తేలేకపోయాను Sir: " + e.getMessage()));
                        }
                    }, "jarvis-app-send").start();
                }).setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ the paid jobs: a plan, an app, a change, a repair

    private Workshop.Engine engineOrSay(boolean byVoice) {
        try {
            Workshop.Engine e = AppStudio.engine(p);
            String over = AppStudio.overLimit(this, p);
            if (over != null) { say0(over, byVoice); return null; }
            return e;
        } catch (Workshop.Problem x) {
            say0(x.getMessage(), byVoice);
            return null;
        }
    }

    private void startPlan(String what, String heard, boolean saidAlready) { startPlan(what, heard, saidAlready, null); }

    /** (with {@code photo}: a JPEG, base64, of an app's screen or a paper drawing: sent only with this plan, never kept) */
    private void startPlan(String what, String heard, boolean saidAlready, String photo) {
        if (job != null) { setStatus("ఒక పని ఇంకా జరుగుతోంది Sir."); return; }
        Workshop.Engine e = engineOrSay(true);
        if (e == null) return;
        clearAll();
        Job j = new Job("plan", what, heard, e);
        planWhat = what;
        planHeard = heard;
        begin(j, "🧠 " + WorkshopActivity.label(e.model) + " ప్లాన్ రాస్తోంది… · సుమారు " + Workshop.fine(AppStudio.planRupeesFor(e.model, what, photo != null)));
        String shown = what.isEmpty() && photo != null ? "📸 ఫోటో లాంటి యాప్" : what;
        titleView.setText(shown.length() > 34 ? shown.substring(0, 34) + "…" : shown);
        if (!saidAlready) speak("ముందు ప్లాన్ చూపిస్తాను Sir.", false);
        j.photo = photo;
        new Thread(() -> runPlan(j, AppStudio.planMessage(what, heard, photo != null)), "jarvis-app-plan").start();
    }

    private void startReplan(String change) {
        AppStudio.Plan pl = plan;
        if (pl == null || job != null) return;
        Workshop.Engine e = engineOrSay(true);
        if (e == null) return;
        Job j = new Job("replan", change, change, e);
        begin(j, "🧠 ప్లాన్ మారుస్తోంది… · " + AppStudio.planMoney(e.model));
        new Thread(() -> runPlan(j, AppStudio.replanMessage(pl, change)), "jarvis-app-plan").start();
    }

    private void begin(Job j, String line) {
        job = j;
        hideOverlay();
        refresh();
        setStatus(line);
        ticker(j);
    }

    /** While the AI thinks (no part yet): the seconds, and one short line about every ten seconds. */
    private void ticker(Job j) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (job != j || destroyed) return;
                long secs = (System.currentTimeMillis() - j.at) / 1000;
                if (rows.isEmpty()) {
                    String what = j.kind.endsWith("plan") ? "ప్లాన్ రాస్తోంది" : j.kind.equals("fix") ? "సరిచేస్తోంది" : j.kind.equals("change") ? "మార్పు రాస్తోంది" : "యాప్ ఆలోచిస్తోంది";
                    setStatus("🧠 " + WorkshopActivity.label(j.engine.model) + " " + what + "… " + secs + " సె");
                    if (!j.kind.endsWith("plan") && secs >= j.nextLine) { j.nextLine = secs + 10; Announcer.say(AppStudioActivity.this, WAITING[j.waitLine++ % WAITING.length]); }
                }
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void runPlan(Job j, String message) {
        Http.CANCEL.set(j.cancel);
        StringBuilder got = new StringBuilder();
        Workshop.Bill b = new Workshop.Bill();
        String failure = null;
        try {
            Workshop.stream(j.engine, AppStudio.PLAN_SYSTEM, message, j.photo, AppStudio.PLAN_OUT, new Workshop.Sink() {
                @Override public void text(String s) { got.append(s); }
            }, b);
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Exception x) {
            failure = Workshop.why(p, x, j.engine.provider);
        }
        add(j, b);
        Workshop.spendApp(this, j.rupees, false);
        AppStudio.Plan pl = failure == null ? AppStudio.parsePlan(got.toString()) : null;
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        String fail = failure;
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            if (pl != null && pl.refuse != null) { setStatus("🚫 " + pl.refuse); speak(pl.refuse, true); refresh(); return; }
            if (pl == null) {
                String s = "stopped".equals(fail) ? "ఆపేశాను Sir. ఖర్చు " + cost + "." : "ప్లాన్ రాలేదు Sir" + (fail != null ? ": " + fail : ", జవాబు అర్థం కాలేదు; మళ్ళీ అడగండి") + ". ఖర్చు " + cost + ".";
                setStatus(s);
                speak(s, true);
                if (plan != null) showPlan(plan, null); else refresh();
                return;
            }
            plan = pl;
            titleView.setText(pl.title);
            showPlan(pl, cost);
            String m = j.engine.model;
            String s = (pl.say.isEmpty() ? "ఇదీ ప్లాన్ Sir." : pl.say) + " తయారీకి సుమారు " + AppStudio.buildMoney(m, pl.size) + ". నచ్చితే ఇలాగే తయారు చేయి అనండి.";
            setStatus("📋 ప్లాన్ సిద్ధం · ఈ ప్లాన్‌కి " + cost + " · ✅ ఇలాగే తయారు చేయి / ✏️ మార్చు");
            speak(s, true);
        });
    }

    private void startMake() {
        AppStudio.Plan pl = plan;
        if (pl == null || job != null) return;
        Workshop.Engine e = engineOrSay(true);
        if (e == null) return;
        Job j = new Job("make", planWhat, planHeard, e);
        j.work = AppStudio.Made.from(pl, planWhat, e.model, AppStudio.slugs(this));
        app = null;
        rows.clear();
        list.removeAllViews();
        titleView.setText(pl.title);
        openTitle = pl.title;
        String money = AppStudio.buildMoney(e.model, pl.size);
        begin(j, "🧠 " + WorkshopActivity.label(e.model) + " యాప్ రాస్తోంది… · సుమారు " + money);
        speak("తయారు చేస్తున్నాను Sir, సుమారు " + money + " అవుతుంది.", false);
        new Thread(() -> runMake(j, pl), "jarvis-app-make").start();
    }

    private void startChange(String change, boolean saidAlready) {
        AppStudio.Made a = app;
        if (a == null || job != null) return;
        Workshop.Engine e = engineOrSay(true);
        if (e == null) return;
        Job j = new Job("change", change, change, e);
        j.work = copyOf(a);
        rows.clear();
        list.removeAllViews();
        String money = AppStudio.changeMoney(e.model, a.codeSize());
        begin(j, "🧠 మార్పు రాస్తోంది… · సుమారు " + money);
        if (!saidAlready) speak("మారుస్తున్నాను Sir, సుమారు " + money + ".", false);
        new Thread(() -> runChange(j), "jarvis-app-change").start();
    }

    /** 🔧 The part that failed (in the preview or the self-test) is written again; the app is tested again. */
    private void fix(boolean byVoice) {
        AppStudio.Made a = app;
        if (a == null || job != null) return;
        if (a.brokenId == null) { say0("ఈ యాప్‌లో తెలిసిన తప్పు ఏదీ లేదు Sir.", byVoice); return; }
        Runnable r = () -> {
            Workshop.Engine e = engineOrSay(true);
            if (e == null) return;
            Job j = new Job("fix", a.brokenMsg, a.brokenMsg, e);
            j.work = copyOf(a);
            j.badId = a.brokenId;
            rows.clear();
            list.removeAllViews();
            begin(j, "🔧 " + a.brokenId + " సరిచేస్తోంది… · సుమారు " + AppStudio.repairMoney(e.model, a.codeSize()));
            new Thread(() -> runFix(j, a.brokenId, a.brokenMsg), "jarvis-app-fix").start();
        };
        if (byVoice) waitThen("తప్పు సరిచేయి", r); else r.run();
    }

    private static AppStudio.Made copyOf(AppStudio.Made a) {
        AppStudio.Made c = AppStudio.Made.of(safeJson(a));
        return c == null ? a : c;
    }

    private static JSONObject safeJson(AppStudio.Made a) {
        try { return a.json(); } catch (Exception e) { return null; }
    }

    private AppStudio.Parser parserFor(Job j) {
        return new AppStudio.Parser(new AppStudio.Parser.Out() {
            @Override public void title(String t) { if (j.kind.equals("make") && !t.isEmpty()) { j.work.title = t; ui.post(() -> { if (job == j) titleView.setText(t); }); } }
            @Override public void partStart(String id, String st) {
                ui.post(() -> { row(id, "⏳ " + st, Ui.MUTED); if (job == j) setStatus("🔧 " + st); });
            }
            @Override public void part(AppStudio.Part pt, boolean cut) {
                if (cut) { j.cut = true; ui.post(() -> row(pt.id, "⚠️ " + pt.status + " (సగంలో ఆగింది)", Ui.GOLD)); }
                AppStudio.Part old = j.work.parts.get(pt.id);
                if (cut && old != null) return; // (a change cut half-way: the old part stays)
                j.work.parts.put(pt.id, pt);
                j.changed.add(pt.id);
                j.removed.remove(pt.id);
                if (!cut) {
                    String bad = AppStudio.unsafe(pt);
                    ui.post(() -> row(pt.id, (bad == null ? "✍️ " : "⚠️ ") + pt.status, bad == null ? Ui.TEXT : Ui.GOLD));
                }
            }
            @Override public void remove(String id) { if (j.work.parts.remove(id) != null) { j.removed.add(id); ui.post(() -> row(id, "🗑️ " + id + " తీసేశాను", Ui.MUTED)); } }
            @Override public void say(String s) { j.say = s; }
            @Override public void refuse(String s) { j.refused = s; }
            @Override public void icon(String json) {
                try {
                    JSONObject ic = AppStudio.cleanIcon(new JSONObject(json));
                    if (ic == null) return;
                    if (j.work.plan == null) j.work.plan = new JSONObject();
                    j.work.plan.put("icon", ic);
                    j.changed.add("(icon)");
                    ui.post(() -> row("(icon)", "🎨 కొత్త ఐకాన్", Ui.OK));
                } catch (Exception ignored) {}
            }
            @Override public void jarvis(String json) {
                try {
                    if (j.work.plan == null) j.work.plan = new JSONObject();
                    j.work.plan.put("jarvis", AppStudio.cleanJarvis(new org.json.JSONArray(json)));
                    j.changed.add("(jarvis)");
                } catch (Exception ignored) {}
            }
        });
    }

    private void stream(Job j, String system, String message, int maxOut, AppStudio.Parser parser) {
        Http.CANCEL.set(j.cancel);
        Workshop.Bill b = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, system, message, maxOut, new Workshop.Sink() { @Override public void text(String s) { parser.feed(s); } }, b);
            parser.finish();
        } catch (CancellationException x) {
            j.failure = "stopped";
        } catch (Exception x) {
            j.failure = Workshop.why(p, x, j.engine.provider);
            parser.finish();
        } finally {
            add(j, b);
        }
    }

    private void runMake(Job j, AppStudio.Plan pl) {
        stream(j, AppStudio.SYSTEM, AppStudio.buildMessage(pl, j.what), AppStudio.APP_OUT, parserFor(j));
        mendCut(j);
        checkAndRepair(j);
        finish(j);
    }

    /**
     * (job thread) An answer that stopped early is mended, not thrown away: the parts that came are tested and a half-written
     * one is repaired like any failing part; what never came is said plainly at the end.
     */
    private void mendCut(Job j) {
        boolean some = j.kind.equals("make") ? !j.work.parts.isEmpty() : !j.changed.isEmpty();
        if (j.failure != null && !"stopped".equals(j.failure) && some) { j.note = j.failure; j.failure = null; j.cut = true; }
        else if (j.cut && j.note == null) j.note = "జవాబు దాని హద్దు దగ్గర ఆగిపోయింది";
    }

    private void runChange(Job j) {
        AppStudio.Made before = app;
        stream(j, AppStudio.SYSTEM, AppStudio.changeMessage(before == null ? j.work : before, j.what), AppStudio.CHANGE_OUT, parserFor(j));
        if (j.failure == null && j.refused == null && j.changed.isEmpty() && j.removed.isEmpty()) { finish(j); return; } // (nothing changed: //@say tells why)
        mendCut(j);
        checkAndRepair(j);
        finish(j);
    }

    private void runFix(Job j, String id, String error) {
        Http.CANCEL.set(j.cancel);
        repairOne(j, id, error);
        checkAndRepair(j);
        finish(j);
    }

    /**
     * (job thread) Every part must pass the code check and load in a fresh preview, then the self-test must pass; a failing
     * part is written again (2 tries a part, 3 an app). Sets j.tested, or j.badId / j.problem.
     */
    private void checkAndRepair(Job j) {
        if (j.failure != null || j.refused != null || j.stopped) return;
        if (j.work.parts.isEmpty()) { j.problem = "no part came"; return; }
        for (int round = 0; round < 8 && !j.stopped && !destroyed; round++) {
            // 1 the code check (no network, no page tricks)
            String badId = null, err = null;
            for (AppStudio.Part pt : new ArrayList<>(j.work.parts.values())) {
                String bad = AppStudio.unsafe(pt);
                if (bad != null) { badId = pt.id; err = bad; break; }
            }
            // 2 loading every part in a fresh preview
            if (badId == null) {
                String[] r = loadAll(j.work, j);
                if (j.stopped) return;
                if (r != null) {
                    badId = r[0].isEmpty() ? lastPartId(j.work) : r[0];
                    err = r[1];
                    if (r[1].contains("did not open")) { j.problem = err; j.badId = null; j.stageTrouble = true; return; }
                }
            }
            // 3 the self-test
            if (badId == null) {
                String[] t = selfTest(j);
                if (j.stopped) return;
                if (t == null) { j.froze = true; j.badId = lastPartId(j.work); j.problem = "the app froze while it was being tested (an endless loop?)"; badId = j.badId; err = j.problem; }
                else if ("ok".equals(t[0])) { j.tested = true; j.badId = null; j.problem = null; return; }
                else { badId = t[1] == null || t[1].isEmpty() || !j.work.parts.containsKey(t[1]) ? guessPart(j.work, t[2]) : t[1]; err = t[2]; }
            }
            j.badId = badId;
            j.problem = err;
            final String bid = badId;
            AppStudio.Part bp = j.work.parts.get(bid);
            ui.post(() -> row(bid, "⚠️ " + (bp == null ? bid : bp.status), Ui.GOLD));
            int tries = j.tries.getOrDefault(bid, 0);
            if (tries >= AppStudio.MAX_REPAIRS_PART || j.repairs >= AppStudio.MAX_REPAIRS) return;
            repairOne(j, bid, err);
            if (j.failure != null) return;
        }
    }

    /** The part an error without a part's name most likely belongs to: a screen named in it, else the last part. */
    private static String guessPart(AppStudio.Made a, String msg) {
        String m = msg == null ? "" : msg;
        for (AppStudio.Part pt : a.parts.values()) if (!pt.html.isEmpty() && m.contains("\"" + pt.id + "\"")) return pt.id;
        for (AppStudio.Part pt : a.parts.values()) if (pt.html.contains("id=\"" + screenIn(m) + "\"")) return pt.id;
        return lastPartId(a);
    }

    private static String screenIn(String msg) {
        int a = msg.indexOf("screen \"");
        if (a < 0) return "\u0000";
        int b = msg.indexOf('"', a + 8);
        return b > a ? msg.substring(a + 8, b) : "\u0000";
    }

    /** (job thread) One part written again by the AI: only it, with its error and the others for reference. */
    private void repairOne(Job j, String id, String error) {
        j.tries.put(id, j.tries.getOrDefault(id, 0) + 1);
        j.repairs++;
        AppStudio.Part old = j.work.parts.get(id);
        String st = old == null ? id : old.status;
        ui.post(() -> { row(id, "🔁 " + st + " (సరిచేస్తోంది)", Ui.CYAN); setStatus("🔁 " + st + " — సరిచేస్తున్నాను"); });
        AppStudio.Part[] got = new AppStudio.Part[1];
        AppStudio.Parser rp = new AppStudio.Parser(new AppStudio.Parser.Out() {
            @Override public void title(String t) {}
            @Override public void partStart(String pid, String s) {}
            @Override public void part(AppStudio.Part pt, boolean cut) { if (got[0] == null && !cut) got[0] = pt; }
            @Override public void say(String s) {}
            @Override public void refuse(String s) {}
        });
        Workshop.Bill b = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, AppStudio.SYSTEM, AppStudio.repairMessage(j.work, id, error), AppStudio.REPAIR_OUT,
                    new Workshop.Sink() { @Override public void text(String s) { rp.feed(s); } }, b);
            rp.finish();
        } catch (CancellationException x) {
            j.failure = "stopped";
            return;
        } catch (Exception x) {
            j.failure = "repair request failed: " + Workshop.why(p, x, j.engine.provider);
            return;
        } finally {
            add(j, b);
        }
        if (got[0] == null) return; // (counted as a try; the loop decides)
        AppStudio.Part np = got[0];
        AppStudio.Part fixed = new AppStudio.Part(id, np.status == null || np.status.equals(np.id) ? st : np.status);
        fixed.html = np.html;
        fixed.css = np.css;
        fixed.js = np.js;
        j.work.parts.put(id, fixed); // (same place in the order, same id)
        j.changed.add(id);
    }

    private void add(Job j, Workshop.Bill b) {
        if (b == null) return;
        j.rupees += b.rupees;
        j.estimated |= b.estimated;
    }

    /** The end of making / changing / repairing an app: the money, the saved app, the words, the preview. */
    private void finish(Job j) {
        boolean stopped = "stopped".equals(j.failure) || j.stopped;
        boolean isNew = j.kind.equals("make");
        boolean nothing = !isNew && j.changed.isEmpty() && j.removed.isEmpty();
        // kept: an app that passed the self-test; or one whose every part came, with one error left (🔧 later); never a stopped one
        boolean whole = !stopped && j.refused == null && j.failure == null && !j.work.parts.isEmpty() && !nothing;
        boolean keep = whole && (j.tested || j.badId != null || j.stageTrouble);
        Workshop.spendApp(this, j.rupees, isNew && keep);
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        long secs = (System.currentTimeMillis() - j.at) / 1000;
        AppStudio.Made a = j.work;
        if (keep) {
            a.cost += j.rupees;
            a.estimated |= j.estimated;
            a.repairs += j.repairs;
            a.tested = j.tested;
            a.brokenId = j.tested ? null : j.badId;
            a.brokenMsg = j.tested ? null : j.problem;
            a.changedAt = System.currentTimeMillis();
            if (!isNew) a.version++;
            if (j.kind.equals("make") && a.what == null) a.what = j.what;
            AppStudio.save(this, a);
            AppStudio.saveVersion(this, a);
        }
        // (a cut answer: the plan's screens that never came, said plainly; "<name> స్క్రీన్ చేర్చు" adds one as a change)
        List<String> missing = new ArrayList<>();
        if (keep && isNew && a.plan != null && a.plan.optJSONArray("screens") != null) {
            org.json.JSONArray sc = a.plan.optJSONArray("screens");
            for (int i = 0; i < sc.length(); i++) {
                JSONObject s = sc.optJSONObject(i);
                if (s == null) continue;
                String sid = s.optString("id");
                boolean found = false;
                for (AppStudio.Part pt : a.parts.values()) if (pt.html.contains("id=\"" + sid + "\"")) { found = true; break; }
                if (!found) missing.add(s.optString("name", sid));
            }
        }
        String gap = missing.isEmpty() ? "" : " ప్లాన్‌లోని " + String.join(", ", missing) + " స్క్రీన్ రాలేదు; \"" + missing.get(0) + " స్క్రీన్ చేర్చు\" అంటే మార్పుగా చేరుస్తాను.";
        String said;
        if (j.refused != null) said = j.refused;
        else if (stopped) said = "ఆపేశాను Sir. ఇప్పటిదాకా ఖర్చు " + cost + (isNew ? "." : "; యాప్ ఇంతకుముందులాగే ఉంది.");
        else if (nothing && j.failure == null) said = (j.say != null && !j.say.isEmpty() ? j.say : "ఏమీ మారలేదు Sir.") + " ఖర్చు " + cost + ".";
        else if (!keep) said = (isNew ? "యాప్ తయారు చేయలేకపోయాను Sir" : "మార్పు చేయలేకపోయాను Sir, యాప్ ఇంతకుముందులాగే ఉంది")
                + (j.failure != null ? ": " + j.failure : j.cut ? ": జవాబు మధ్యలో ఆగిపోయింది" : "") + ". ఖర్చు " + cost + ".";
        else if (j.stageTrouble) said = (isNew ? "యాప్ తయారైంది" : "మార్చాను") + " Sir, కానీ ప్రివ్యూ పేజీ తెరుచుకోలేదు; ఇప్పుడు ఉచితంగా మళ్ళీ పరీక్షిస్తాను. ఖర్చు " + cost + "." + gap;
        else if (!j.tested) said = (isNew ? "యాప్ తయారైంది" : "మార్చాను") + " కానీ పరీక్షలో ఒక తప్పు మిగిలింది Sir. 🔧 సరిచేయి నొక్కితే మళ్ళీ ప్రయత్నిస్తాను. ఖర్చు " + cost + "." + gap;
        else said = (j.say != null && !j.say.isEmpty() && j.note == null ? j.say : isNew ? "మీ యాప్ రెడీ Sir." : "మార్చాను Sir.")
                + (j.note != null ? " (AI జవాబు మధ్యలో ఆగింది: " + Workshop.cap(j.note, 80) + "; వచ్చిన భాగాలతో చేశాను.)" : "")
                + " ఖర్చు " + cost + "." + gap + " ప్రివ్యూలో వాడి చూడండి; నచ్చితే ఫోన్‌లో పెట్టు అనండి.";
        int ok = a.parts.size();
        final String line = (j.refused != null ? "🚫 " + j.refused
                : (keep ? (j.tested ? "✓ " : "⚠️ ") : "✗ ") + ok + " భాగాలు · " + cost + " · " + (secs / 60 > 0 ? secs / 60 + " ని " : "") + secs % 60 + " సె"
                + (j.repairs > 0 ? " · " + j.repairs + " సార్లు సరిచేసింది" : "")
                + (j.failure != null && !"stopped".equals(j.failure) ? "\n" + j.failure : keep && !j.tested && j.problem != null ? "\n" + Workshop.cap(j.problem, 160) : ""));
        final String words = said;
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            if (keep) {
                if (isNew) plan = null;
                app = a;
                versionCount = AppStudio.versions(this, a.slug).size();
                openTitle = a.title;
                titleView.setText(a.title);
            }
            refresh();
            setStatus(line);
            speak(words, true);
            if (app != null) loadPreviewLater(); // (the app as it is now, ready to use by hand; an unchanged one too)
            else if (isNew && plan != null && j.refused == null) showPlan(plan, null); // (not made: the plan is still there)
        });
    }

    private void row(String id, String text, int color) {
        if (destroyed) return;
        TextView t = rows.get(id);
        if (t == null) {
            t = Ui.text(this, text, 13.5f, color);
            t.setPadding(dp(2), dp(3), dp(2), dp(3));
            rows.put(id, t);
            list.addView(t);
            listScroll.post(() -> listScroll.fullScroll(View.FOCUS_DOWN));
        }
        t.setText(text);
        t.setTextColor(color);
    }

    private void askStop() {
        Job j = job;
        if (j == null) return;
        new AlertDialog.Builder(this).setTitle("ఆపాలా?")
                .setMessage("ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది." + (j.kind.equals("change") || j.kind.equals("fix") ? " యాప్ ఇంతకుముందులాగే ఉంటుంది." : ""))
                .setPositiveButton("⏹ ఆపు", (d, w) -> stopJob(j)).setNegativeButton("కొనసాగించు", null).show();
    }

    private void stopJob(Job j) {
        j.stopped = true;
        j.cancel.cancel();
        synchronized (acks) { acks.notifyAll(); }
    }

    // ================================================================ 📲 onto his phone (GitHub builds the APK), ⬇️ install

    private void publish() {
        AppStudio.Made a = app;
        if (a == null) { setStatus("ముందు ఒక యాప్ తెరవండి Sir (📱 నా యాప్‌లు)."); return; }
        if (job != null || publishing) return;
        if (!a.tested || a.brokenId != null) { setStatus("ఈ యాప్‌లో ఒక తప్పు ఉంది Sir; ముందు 🔧 సరిచేయి, తర్వాత ఫోన్‌లో పెడతాను."); return; }
        if (p.githubToken().trim().isEmpty()) {
            String s = "ఫోన్‌లో పెట్టడానికి ఒక్కసారి GitHub token కావాలి Sir: ⚙️ → కోడింగ్, వెబ్‌సైట్లు, యాప్‌లు → GitHub token "
                    + "(github.com → Settings → Developer settings → Tokens (classic) → 'repo', 'workflow' టిక్). మీరే పెట్టుకోండి; నాకు చెప్పవద్దు.";
            setStatus(s);
            speak("ఫోన్‌లో పెట్టడానికి ఒక్కసారి GitHub token పెట్టాలి Sir. ఎలా పెట్టాలో స్క్రీన్ మీద రాశాను.", false);
            return;
        }
        String kitCss, kitJs, html;
        try {
            kitCss = asset("apps/kit.css");
            kitJs = asset("apps/kit.js");
            html = AppStudio.page(kitCss, kitJs, a);
        } catch (Exception e) {
            setStatus("యాప్ పేజీ సిద్ధం చేయలేకపోయాను: " + e.getMessage());
            return;
        }
        publishing = true;
        refresh();
        setStatus("📲 GitHub కి పంపుతున్నాను… (బిల్డ్ 3-5 నిమిషాలు; నోటిఫికేషన్ బార్‌లో ఎంత అయిందో కనిపిస్తుంది)");
        speak("ఫోన్ యాప్‌గా తయారు చేస్తున్నాను Sir, మూడు నాలుగు నిమిషాలు పడుతుంది. అయ్యాక నోటిఫికేషన్ వస్తుంది.", false);
        JobProgress jp = new JobProgress(this, 7916, a.title + " యాప్");
        Context c = getApplicationContext();
        String slug = a.slug;
        int version = a.version;
        new Thread(() -> {
            try {
                JSONObject r = AppMaker.publish(c, p, a, html, jp, ok -> {
                    AppStudio.Made now = AppStudio.bySlug(c, slug);
                    if (now == null) return;
                    now.buildState = ok ? "ok" : "failed";
                    now.buildAt = System.currentTimeMillis();
                    if (ok) now.builtVersion = version;
                    AppStudio.save(c, now);
                    ui.post(() -> {
                        if (destroyed) return;
                        if (app != null && app.slug.equals(slug)) { app.buildState = now.buildState; app.buildAt = now.buildAt; app.builtVersion = now.builtVersion; refresh(); }
                        String s = ok ? "✓ \"" + now.title + "\" యాప్ సిద్ధం Sir. ⬇️ ఇన్‌స్టాల్ నొక్కండి (లేదా నోటిఫికేషన్ నొక్కండి)."
                                : "❌ \"" + now.title + "\" బిల్డ్ కాలేదు Sir. నోటిఫికేషన్ నొక్కితే GitHub లో ఏమైందో కనిపిస్తుంది; మళ్ళీ 📲 నొక్కి చూడొచ్చు.";
                        if (app != null && app.slug.equals(slug)) setStatus(s);
                        Announcer.say(this, ok ? now.title + " యాప్ సిద్ధం Sir. ఇన్‌స్టాల్ నొక్కండి." : now.title + " యాప్ బిల్డ్ కాలేదు Sir.");
                    });
                });
                AppStudio.Made now = AppStudio.bySlug(c, slug);
                if (now != null) {
                    now.repo = r.optString("repo");
                    now.builtCommit = r.optString("commit");
                    now.buildState = "building";
                    AppStudio.save(c, now);
                }
                ui.post(() -> {
                    publishing = false;
                    if (app != null && app.slug.equals(slug) && now != null) { app.repo = now.repo; app.builtCommit = now.builtCommit; app.buildState = "building"; }
                    refresh();
                    if (!destroyed && app != null && app.slug.equals(slug))
                        setStatus("⏳ GitHub లో బిల్డ్ అవుతోంది (మీ ప్రైవేట్ repo: " + r.optString("repo") + "). అయ్యాక నోటిఫికేషన్ వస్తుంది.");
                });
            } catch (Exception e) {
                jp.end();
                String why = "no_token".equals(e.getMessage()) ? "GitHub token లేదు" : String.valueOf(e.getMessage());
                ui.post(() -> {
                    publishing = false;
                    refresh();
                    setStatus("❌ GitHub కి పంపలేకపోయాను Sir: " + why);
                });
            }
        }, "jarvis-app-publish").start();
    }

    private void install() {
        AppStudio.Made a = app;
        if (a == null || a.repo.isEmpty() || !"ok".equals(a.buildState)) { setStatus("ముందు 📲 ఫోన్‌లో పెట్టు నొక్కండి Sir; బిల్డ్ అయ్యాక ఇన్‌స్టాల్ చేస్తాను."); return; }
        AppMaker.install(this, a.repo);
        setStatus("⬇️ డౌన్‌లోడ్ అవుతోంది… Android \"Install?\" అని అడిగితే Install నొక్కండి (మీ డేటా అలాగే ఉంటుంది).");
    }

    private void installOld(String slug) {
        if (p.githubToken().trim().isEmpty()) { setStatus("GitHub token లేదు Sir (⚙️ → కోడింగ్, వెబ్‌సైట్లు, యాప్‌లు)."); return; }
        setStatus("⬇️ పాత యాప్ తెస్తున్నాను…");
        new Thread(() -> {
            String repo = AppMaker.repoOf(p, slug);
            ui.post(() -> {
                if (repo == null) { setStatus("GitHub లో ఆ యాప్ దొరకలేదు Sir."); return; }
                AppMaker.install(this, repo);
            });
        }, "jarvis-app-old").start();
    }

    private String asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] x = new byte[16384];
            int n;
            while ((n = in.read(x)) > 0) b.write(x, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void leave() {
        Job j = job;
        if (j == null) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("ఇంకా పని జరుగుతోంది")
                .setMessage("మూసేస్తే అది ఆగిపోతుంది (ఇప్పటిదాకా అయిన ఖర్చు పడుతుంది). మూసేయాలా?")
                .setPositiveButton("మూసేయి", (d, w) -> { stopJob(j); finish(); }).setNegativeButton("ఉండు", null).show();
    }

    @Override public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE && "list".equals(overlayKind)) { hideOverlay(); refresh(); return; } // (the preview has its own ← for its screens)
        leave();
    }

    // ================================================================ in front, behind, gone

    /** The app (or plan) on the studio screen while it is in front or was left within the last 10 minutes; "" = open with none; null = not open. */
    static String openTitle() {
        String t = openTitle;
        long s = stoppedAt;
        return t != null && s > 0 && System.currentTimeMillis() - s > 10 * 60_000L ? null : t;
    }

    @Override protected void onStart() {
        super.onStart();
        stoppedAt = 0;
        if (openTitle == null) openTitle = app == null ? "" : app.title;
    }

    @Override protected void onStop() {
        super.onStop();
        stoppedAt = System.currentTimeMillis();
    }

    @Override protected void onResume() {
        super.onResume();
        showChip();
        refresh();
        front = true;
        WakeService.workshopWake = wakeR; // (🎙️ "Jarvis" here: the studio hears him, no panel over it)
        JarvisAccessibility.bubbleAside(true);
        if (app != null && job == null) { AppStudio.Made now = AppStudio.bySlug(this, app.slug); if (now != null) { app.buildState = now.buildState; app.builtVersion = now.builtVersion; app.repo = now.repo; refresh(); } }
    }

    @Override protected void onPause() {
        super.onPause();
        front = false;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        JarvisAccessibility.bubbleAside(false);
        if (ear != null) ear.away();
        if (waiting != null) cancelWait(false); // (he left: nothing is spent on what was heard)
    }

    @Override protected void onDestroy() {
        destroyed = true;
        openTitle = null;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        if (front) { front = false; JarvisAccessibility.bubbleAside(false); }
        if (ear != null) ear.shutdown();
        Job j = job;
        if (j != null) stopJob(j);
        ui.removeCallbacksAndMessages(null);
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) {
            try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) {}
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    /** Opens the studio (from ⚙️, a tool, the brain): a new app to plan, or the studio as it was. */
    static void open(Context c, String what, String heard, boolean saidAlready) {
        Intent i = new Intent(c, AppStudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null && !what.trim().isEmpty()) i.putExtra(EXTRA_WHAT, what.trim()).putExtra(EXTRA_HEARD, heard).putExtra(EXTRA_SAID, saidAlready);
        else i.putExtra(EXTRA_ACTION, "open");
        c.startActivity(i);
    }
}
