package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
 * 🎮 The game studio screen (Anil, 16 Nov, G1): he says or types the game he wants; the workshop's AI (his choice) writes it
 * part by part (a tick for each), the sealed game stage plays it by itself for a few seconds and a failing part is sent
 * back for repair (2 tries a part, 3 a game, only that part), then "▶️ ఆడు": the game full screen, played by touch, swipes,
 * on-screen buttons and tilt, the score bar, ⏸ and the end card drawn by the stage. Games are kept on 📂 the shelf with a
 * small picture and the best score; playing is offline and free. The stage is a WebView sealed off from everything like the
 * workshop's: only the studio's own files load, the game runs in a sandboxed frame with no network, keys never reach it,
 * and one message port carries "run this part / test / start" in and "ok / error / score" out. Phone only.
 */
public class GameStudioActivity extends Activity {
    static final String EXTRA_WHAT = "what", EXTRA_HEARD = "heard", EXTRA_SAID = "said", EXTRA_OPEN_LAST = "open_last",
            EXTRA_SHELF = "shelf", EXTRA_PLAY_AT = "play_at";
    /** The studio is in front: "Jarvis" opens no panel over it (its own ears hear him), and a game is not interrupted. */
    static volatile boolean front;
    private static volatile String openTitle; // (read through openTitle())
    private static volatile long stoppedAt;

    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/games/";
    private static final String HARNESS = "https://" + HOST + PATH + "harness.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("harness.html", "harness.js", "stage.html", "stage.js"));
    private static final long ACK_WAIT_MS = 20000, TEST_WAIT_MS = 45000;
    private static final String[] WAITING = {
            "ఆట ఎలా ఉండాలో ఆలోచిస్తున్నాను Sir.", "ఆట నియమాలు సిద్ధం చేస్తున్నాను.", "రంగులు, శబ్దాలు ఎంచుకుంటున్నాను.",
            "ఇంకొంచెం సేపు Sir, మొదటి భాగం దగ్గర్లోనే ఉంది.", "కష్టం ఎంత పెంచాలో చూస్తున్నాను."};
    /** Ready ideas: the chip's name and the fuller request it puts in the box (he can change it before 🔨). */
    private static final String[][] IDEAS = {
            {"🏍️ బైక్ రేస్", "బైక్ రేస్: ఫోన్ వంచితే లేదా ◀ ▶ నొక్కితే బైక్ తిరగాలి, రోడ్డు మీద కార్లని తప్పించుకుంటూ నాణేలు ఏరుకోవాలి, వేగం పెరుగుతూ ఉండాలి"},
            {"🧱 ఇటుకలు", "ఇటుకలు పగలగొట్టే ఆట: కింద పలకని వేలుతో జరిపి బంతిని కొట్టాలి, పైన రంగుల ఇటుకలు పగలాలి, లెవెల్స్ తో"},
            {"🔢 నంబర్ల పజిల్", "నంబర్ల పజిల్: 4x4 గడుల్లో వేలుతో జరిపితే ఒకే నంబర్లు కలిసి రెట్టింపు అవ్వాలి, పెద్ద నంబర్ చేరడమే లక్ష్యం"},
            {"🐍 పాము ఆట", "పాము ఆట: వేలుతో జరిపి పామును తిప్పాలి, పండ్లు తింటే పొడవు పెరగాలి, గోడకి లేదా తనకే తగిలితే అయిపోవాలి"},
            {"🎈 బుడగలు", "బుడగలు పగలగొట్టే ఆట: కింద నుంచి పైకి వచ్చే రంగుల బుడగల్ని తాకి పగలగొట్టాలి, వేగం పెరుగుతూ ఉండాలి"},
            {"🐦 ఎగిరే పక్షి", "ఎగిరే పక్షి ఆట: తాకితే పక్షి పైకి ఎగరాలి, స్తంభాల మధ్య ఖాళీల్లోంచి దాటాలి, ఒక్కో దాటుకి ఒక పాయింట్"},
            {"🃏 జతలు", "జతల ఆట: బోర్లా ఉన్న కార్డులు తిప్పి ఒకే బొమ్మ జతల్ని కనిపెట్టాలి, తక్కువ ప్రయత్నాల్లో అయితే ఎక్కువ పాయింట్లు"},
            {"❓ క్విజ్", "తెలుగు క్విజ్: సాధారణ జ్ఞానం ప్రశ్నలు, 4 జవాబుల్లో సరైనది తాకాలి, ఒక్కో ప్రశ్నకి టైమర్, 10 ప్రశ్నలు"}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs p;
    private FrameLayout stageBox;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private View topBar, bottomBar;
    private TextView titleView, chip, status, go, micBtn, cancelBtn, stopBtn, shelfBtn, newBtn;
    private LinearLayout list;
    private ScrollView listScroll;
    private HorizontalScrollView ideas;
    private EditText input;
    // the card over the stage when a game is ready
    private LinearLayout playCard;
    private TextView cardTitle, cardHow, cardBest, playBtn, fixBtn;

    /** The game on the stage now (null = none): what ▶️ plays, what 🔧 repairs. */
    private Game game;
    private volatile Job job;
    private boolean playing;
    /** What the stage said it is doing: play / paused (from its own ⏸) . */
    private String stageState = "";
    private final LinkedHashMap<String, String> onStage = new LinkedHashMap<>(); // (sent again if the stage starts over)
    private final Map<String, String[]> acks = new HashMap<>();
    /** The self-test's answer: {"ok"} or {"err", part id, message} (under {@link #acks}). */
    private String[] testAns;
    private final Map<String, TextView> rows = new LinkedHashMap<>();
    private String snapReq;
    private long snapAt;
    private boolean muted;

    // 🎙️ the studio's own ears (the workshop's), ✋ a paid job heard by voice waits 3 seconds
    private WorkshopVoice ear;
    private final Runnable wakeR = () -> { if (ear != null && front && !destroyed) ear.wake(); };
    private String waiting;
    private Runnable waitGo;
    private long waitSince;

    // 📱 tilt while playing
    private android.hardware.SensorManager sensors;
    private android.hardware.SensorEventListener tiltL;
    private float[] tilt0;
    private long tiltSent;
    private android.os.Vibrator vib;

    private static final class Game {
        long at;
        String title, what, model, how, say;
        boolean landscape, estimated;
        double cost;
        int repairs, best, plays;
        long seconds = -1;
        /** A part that failed while he played, and its error (🔧 repairs it); null = none. */
        String brokenId, brokenMsg;
        /** id → {status, code}, in order. */
        final LinkedHashMap<String, String[]> parts = new LinkedHashMap<>();

        List<String[]> list() {
            List<String[]> l = new ArrayList<>();
            for (Map.Entry<String, String[]> e : parts.entrySet()) l.add(new String[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
            return l;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("at", at).put("title", title).put("what", what == null ? "" : what).put("model", model == null ? "" : model)
                    .put("how", how == null ? "" : how).put("say", say == null ? "" : say).put("screen", landscape ? "landscape" : "portrait")
                    .put("parts", GameStudio.partsJson(parts)).put("cost", cost).put("estimated", estimated).put("repairs", repairs)
                    .put("seconds", seconds).put("best", best).put("plays", plays)
                    .put("broken_id", brokenId == null ? "" : brokenId).put("broken_msg", brokenMsg == null ? "" : brokenMsg);
        }

        static Game of(JSONObject g) {
            Game x = new Game();
            x.at = g.optLong("at", System.currentTimeMillis());
            x.title = g.optString("title");
            x.what = g.optString("what");
            x.model = g.optString("model");
            x.how = g.optString("how");
            x.say = g.optString("say");
            x.landscape = "landscape".equals(g.optString("screen"));
            x.cost = g.optDouble("cost", 0);
            x.estimated = g.optBoolean("estimated");
            x.repairs = g.optInt("repairs", 0);
            x.seconds = g.optLong("seconds", -1);
            x.best = g.optInt("best", 0);
            x.plays = g.optInt("plays", 0);
            x.parts.putAll(GameStudio.parts(g));
            String b = g.optString("broken_id", "");
            x.brokenId = b.isEmpty() || !x.parts.containsKey(b) ? null : b;
            x.brokenMsg = x.brokenId == null ? null : g.optString("broken_msg");
            return x;
        }
    }

    /** One game being made (or 🔧 one being repaired). */
    private static final class Job {
        final String what, heard;
        final long at = System.currentTimeMillis();
        final Http.Cancel cancel = new Http.Cancel();
        final Workshop.Engine engine;
        volatile boolean stopped;
        String title, how, say, refused, badId;
        /** All parts came and loaded; it passed the self-test; the stage froze while testing (an endless loop). */
        boolean landscape, complete, tested, froze;
        /** (🔧) the saved game being repaired. */
        Game fixing;
        /** Written by the job's thread, read by the screen: one lock (iterate inside synchronized (parts)). */
        final Map<String, Part> parts = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        double rupees;
        boolean estimated;
        int repairs;
        long firstPartAt;
        int waitLine;
        long nextLine = 10;
        Job(String what, String heard, Workshop.Engine engine) { this.what = what; this.heard = heard; this.engine = engine; }
    }

    private static final class Part {
        final String id;
        String status, code, err;
        boolean ok;
        int tries;
        Part(String id) { this.id = id; }
    }

    // ================================================================ the screen

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        Usage.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF07080B);
        getWindow().setNavigationBarColor(0xFF07080B);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "🎮 గేమ్ స్టూడియో ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        build();
        newStage();
        ear = new WorkshopVoice(owner(), p);
        Intent i = getIntent();
        // brought back after Android closed it, or opened again from recent apps: never make (and pay for) a game a second time
        if (i != null && (b != null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0)) i.removeExtra(EXTRA_WHAT);
        handle(i);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String what = i.getStringExtra(EXTRA_WHAT);
        if (what != null && !what.trim().isEmpty()) {
            final String w0 = what.trim(), h0 = i.getStringExtra(EXTRA_HEARD);
            final boolean s0 = i.getBooleanExtra(EXTRA_SAID, false);
            i.removeExtra(EXTRA_WHAT); // (a rotation or a return to the screen must not make it again)
            if (playing) exitPlay();
            if (front && ear != null && ear.voiceFlow) waitThen(w0, () -> start(w0, h0, s0)); // (asked by voice here: ✋ 3 seconds)
            else start(w0, h0, s0);
            return;
        }
        if (job != null) return;
        long playAt = i.getLongExtra(EXTRA_PLAY_AT, 0);
        if (playAt > 0) {
            i.removeExtra(EXTRA_PLAY_AT);
            JSONObject g = GameStudio.byAt(this, playAt);
            if (g != null) { if (game == null || game.at != playAt) show(g); else if (playing) return; ui.postDelayed(this::play, 600); return; }
        }
        boolean shelf = i.getBooleanExtra(EXTRA_SHELF, false), openLast = i.getBooleanExtra(EXTRA_OPEN_LAST, false);
        i.removeExtra(EXTRA_SHELF);
        i.removeExtra(EXTRA_OPEN_LAST);
        if (playing) return;
        if (openLast || game == null) {
            JSONObject last = GameStudio.last(this);
            if (last != null && (openLast || game == null)) show(last);
            else if (game == null) setStatus("ఏ ఆట కావాలో కింద రాయండి Sir, లేదా ఒక ఐడియా తాకండి. \"Jarvis, బైక్ రేస్ గేమ్ తయారు చెయ్\" అన్నా చాలు.");
        }
        if (shelf) shelf();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        stageBox = new FrameLayout(this);
        root.addView(stageBox, new FrameLayout.LayoutParams(-1, -1));

        // the top: the game's name, which AI makes it, close
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(10), dp(22));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF207080B, 0x0007080B}));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "🎮 గేమ్ స్టూడియో", 13, Ui.MUTED));
        titleView = Ui.text(this, "", 19, Ui.TEXT);
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
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        topBar = top;

        // the card over the stage: the game's name, how to play, the best score, ▶️ play, 🔧 repair
        playCard = new LinearLayout(this);
        playCard.setOrientation(LinearLayout.VERTICAL);
        playCard.setGravity(Gravity.CENTER_HORIZONTAL);
        playCard.setBackground(Ui.round(this, 0xE60B1118, Ui.CYAN_DIM, 20));
        playCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        cardTitle = Ui.text(this, "", 21, Ui.TEXT);
        cardTitle.setGravity(Gravity.CENTER);
        playCard.addView(cardTitle);
        cardHow = Ui.text(this, "", 14.5f, Ui.MUTED);
        cardHow.setGravity(Gravity.CENTER);
        cardHow.setPadding(0, dp(6), 0, dp(4));
        cardHow.setLineSpacing(0, 1.15f);
        playCard.addView(cardHow);
        cardBest = Ui.text(this, "", 13.5f, Ui.GOLD);
        cardBest.setGravity(Gravity.CENTER);
        cardBest.setPadding(0, 0, 0, dp(10));
        playCard.addView(cardBest);
        playBtn = Ui.text(this, "▶️ ఆడు", 20, 0xFF04131A);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(Ui.round(this, Ui.CYAN, Ui.CYAN, 16));
        playBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        playBtn.setOnClickListener(v -> play());
        playCard.addView(playBtn, new LinearLayout.LayoutParams(-1, -2));
        fixBtn = Ui.text(this, "", 15, Ui.GOLD_INK);
        fixBtn.setGravity(Gravity.CENTER);
        fixBtn.setBackground(Ui.round(this, Ui.GOLD, Ui.GOLD, 14));
        fixBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        fixBtn.setOnClickListener(v -> fix());
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(10);
        playCard.addView(fixBtn, fl);
        FrameLayout.LayoutParams pcl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        pcl.leftMargin = pcl.rightMargin = dp(30);
        pcl.bottomMargin = dp(120);
        root.addView(playCard, pcl);

        // the bottom: what Jarvis is doing, the parts with their ticks, ideas, the buttons, the request box
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(26), dp(14), dp(12));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF507080B, 0xD907080B, 0x0007080B}));
        status = Ui.text(this, "", 15, Ui.TEXT);
        status.setPadding(dp(2), 0, dp(2), dp(6));
        bottom.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.22f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        listScroll.addView(list);
        listScroll.setVerticalFadingEdgeEnabled(true);
        listScroll.setFadingEdgeLength(dp(26));
        bottom.addView(listScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ideaRow = new LinearLayout(this);
        ideaRow.setOrientation(LinearLayout.HORIZONTAL);
        ideaRow.setPadding(0, dp(6), 0, dp(2));
        for (String[] idea : IDEAS) {
            TextView t = pill(idea[0], v -> { input.setText(idea[1]); input.setSelection(input.getText().length()); input.requestFocus(); setStatus("✏️ కావాలంటే మార్చి, 🔨 తయారు చెయ్ నొక్కండి · " + estimate()); });
            ideaRow.addView(t);
        }
        ideas = new HorizontalScrollView(this);
        ideas.setHorizontalScrollBarEnabled(false);
        ideas.addView(ideaRow);
        bottom.addView(ideas);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        cancelBtn = pill("✋ వద్దు", v -> cancelWait(true));
        cancelBtn.setVisibility(View.GONE);
        stopBtn = pill("⏹ ఆపు", v -> askStop());
        stopBtn.setVisibility(View.GONE);
        shelfBtn = pill("📂 నా గేమ్స్", v -> shelf());
        TextView cost = pill("💰 ఖర్చు", v -> { String s = GameStudio.costSaid(this, p); setStatus(s); speak(s, false); });
        newBtn = pill("🆕 కొత్తది", v -> command("new", false));
        actions.addView(cancelBtn);
        actions.addView(stopBtn);
        actions.addView(shelfBtn);
        actions.addView(cost);
        actions.addView(newBtn);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        bottom.addView(hs);

        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("ఏ ఆట కావాలి? ఉదా: బైక్ రేస్");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(15.5f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false); // (a long idea wraps onto up to 3 lines; Enter still means 🔨)
        input.setMaxLines(3);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 14));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
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
        go = Ui.text(this, "🔨 తయారు చెయ్", 15, 0xFF04131A);
        go.setBackground(Ui.round(this, Ui.CYAN, Ui.CYAN, 14));
        go.setPadding(dp(14), dp(11), dp(14), dp(11));
        go.setOnClickListener(v -> typed());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.leftMargin = dp(8);
        ask.addView(go, gl);
        bottom.addView(ask);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        bottomBar = bottom;
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

    /** 💰 The AI for this studio, picked by his tap from every model his keys can use, each with its cost for one job. */
    private void pickModel() {
        Models.pick(this, Workshop.studioName(Workshop.GAMES) + " AI ఎంచుకోండి", new String[]{Prefs.OPENAI, Prefs.ANTHROPIC, Prefs.GEMINI},
                new String[]{p.openAiKey(), p.anthropicKey(), p.geminiKey()}, Workshop.modelFor(p, Workshop.GAMES), Workshop.costWhat(Workshop.GAMES), Workshop.costOf(Workshop.GAMES), it -> {
                    p.setStudioModel(Workshop.GAMES, it.id);
                    showChip();
                    refresh();
                    android.widget.Toast.makeText(this, "✓ " + Workshop.studioName(Workshop.GAMES) + " AI: " + it.id + " (వర్క్‌షాప్ AI మారలేదు)", android.widget.Toast.LENGTH_LONG).show();
                });
    }

    private void showChip() {
        String m = Workshop.modelFor(p, Workshop.GAMES);
        chip.setText(m.isEmpty() ? "AI ఎంచుకోలేదు" : WorkshopActivity.label(m));
    }

    private String estimate() {
        String m = Workshop.modelFor(p, Workshop.GAMES);
        return m.isEmpty() ? "AI ఎంచుకోలేదు" : "సుమారు " + GameStudio.aboutMoney(m);
    }

    /** The buttons, the ideas and the card follow what is happening: making, a game ready, nothing yet. */
    private void refresh() {
        if (go == null) return;
        boolean busy = job != null, has = game != null;
        ideas.setVisibility(!busy && !has ? View.VISIBLE : View.GONE);
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        newBtn.setVisibility(!busy && has ? View.VISIBLE : View.GONE);
        go.setText(has ? "🔨 కొత్త గేమ్" : "🔨 తయారు చెయ్");
        input.setHint(has ? "ఇంకో కొత్త ఆట? ఉదా: పాము ఆట (ఈ ఆట షెల్ఫ్‌లో ఉంటుంది)" : "ఏ ఆట కావాలి? ఉదా: బైక్ రేస్");
        if (playing) { playCard.setVisibility(View.GONE); return; }
        playCard.setVisibility(has && !busy ? View.VISIBLE : View.GONE);
        if (has) {
            cardTitle.setText(game.title);
            cardHow.setText(game.how == null || game.how.isEmpty() ? "" : "👆 " + game.how);
            cardHow.setVisibility(game.how == null || game.how.isEmpty() ? View.GONE : View.VISIBLE);
            cardBest.setText(game.best > 0 ? "🏆 బెస్ట్ " + game.best + " · " + game.plays + " సార్లు ఆడారు" : game.plays > 0 ? game.plays + " సార్లు ఆడారు" : "ఆడటానికి ఖర్చు లేదు · నెట్ అక్కర్లేదు");
            String m = Workshop.modelFor(p, Workshop.GAMES);
            fixBtn.setText("🔧 తప్పు సరిచేయి" + (m.isEmpty() ? "" : " (సుమారు " + GameStudio.fixMoney(m) + ")"));
            fixBtn.setVisibility(game.brokenId != null ? View.VISIBLE : View.GONE);
        }
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
        String cmd = GameStudio.command(text);
        if (cmd != null) { command(cmd, byVoice); return; }
        if (job != null) {
            String s = "ఒక ఆట ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        Runnable r = () -> start(text, text, false);
        if (byVoice) waitThen(text, r); // (heard by voice: ✋ 3 seconds to say "వద్దు" before any money is spent)
        else r.run();
    }

    /** A ₹0 word: done at once, no AI. */
    private void command(String cmd, boolean byVoice) {
        switch (cmd) {
            case "play":
                if (game == null) { say0("ముందు ఒక ఆట తయారు చేద్దాం Sir: ఏ ఆట కావాలో చెప్పండి.", byVoice); return; }
                if (!playing) play();
                else if ("paused".equals(stageState)) send(json("resume"));
                else if (!"play".equals(stageState)) send(startMsg());
                return;
            case "restart":
                if (game == null) return;
                if (!playing) play(); else send(startMsg());
                return;
            case "pause":
                if (playing) send(json("pause"));
                return;
            case "shelf": shelf(); return;
            case "cost": { String s = GameStudio.costSaid(this, p); setStatus(s); speak(s, byVoice); return; }
            case "new":
                if (job != null) return;
                if (playing) exitPlay();
                game = null;
                openTitle = "";
                onStage.clear();
                rows.clear();
                list.removeAllViews();
                titleView.setText("");
                sendReset(false, 0, "", "");
                refresh();
                say0("ఏ కొత్త ఆట కావాలి Sir? కింద రాయండి, లేదా ఒక ఐడియా తాకండి.", byVoice);
                return;
            case "close": leave(); return;
            default:
        }
    }

    private void say0(String s, boolean byVoice) { setStatus(s); if (byVoice) speak(s, true); }

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
                cancelBtn.setVisibility(View.GONE);
                then.run();
            }
        };
        cancelBtn.setVisibility(View.VISIBLE);
        setStatus("✏️ “" + words + "” · " + estimate() + " · 3 సెకన్లలో మొదలుపెడతాను · వద్దంటే ✋ వద్దు");
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
        if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
        if (said && was) {
            setStatus("✋ సరే Sir, చేయలేదు · ఖర్చు ₹0");
            speak("సరే Sir, చేయలేదు.", true);
        }
    }

    // ================================================================ 🎙️ the studio's own ears

    private WorkshopVoice.Owner owner() {
        return new WorkshopVoice.Owner() {
            @Override public Activity screen() { return GameStudioActivity.this; }
            @Override public boolean take(String words) { return tookByVoice(words); }
            @Override public void line(String text) { setStatus(text); }
            @Override public void mic(int state) { micLook(state); }
            @Override public boolean front() { return front && !destroyed; }
            @Override public boolean waitWords(String words) { return waitHeard(words); }
        };
    }

    /** Heard by voice here: a ₹0 word, or plainly a game to make. Anything else goes to Jarvis's brain. */
    private boolean tookByVoice(String words) {
        String cmd = GameStudio.command(words);
        if (cmd != null) { command(cmd, true); return true; }
        if (job == null && GameStudio.wantsGame(words, game == null)) { said(words, true); return true; }
        return false;
    }

    private void micLook(int state) {
        if (state == WorkshopVoice.LISTENING && playing && "play".equals(stageState)) send(json("pause")); // (he talks: the game waits for him)
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

    // ================================================================ the sealed stage

    private void newStage() {
        if (web != null) {
            stageBox.removeView(web);
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        port = null;
        WebView w = new WebView(this);
        web = w;
        w.setBackgroundColor(0xFF07080B);
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
                Uri u = r.getUrl(); // (only the game frame's own page may load; nothing navigates anywhere else)
                return !(u != null && HOST.equals(u.getHost()) && (PATH + "stage.html").equals(u.getPath()));
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (v == web && url != null && url.startsWith(HARNESS)) connect(v);
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> restartStage("ఆగిపోయిన ఆట పేజీని మళ్ళీ మొదలుపెట్టాను Sir."));
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
                    InputStream in = getAssets().open("games/" + name);
                    String mime = name.endsWith(".html") ? "text/html" : "text/javascript";
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
            setStatus("ఆట పేజీ తెరవలేకపోయాను Sir: " + e.getMessage());
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

    private void sendReset(boolean landscape, int best, String how, String title) {
        try {
            send(json("reset").put("screen", landscape ? "landscape" : "portrait").put("aspect", GameStudio.aspect(this)).put("best", best)
                    .put("how", how == null ? "" : how).put("title", title == null ? "" : title));
        } catch (Exception ignored) {}
    }

    private void sendPart(String id, String code) {
        try { send(json("part").put("id", id).put("code", code)); } catch (Exception ignored) {}
    }

    private JSONObject startMsg() {
        try { return json("start").put("best", game == null ? 0 : game.best); } catch (Exception e) { return json("start"); }
    }

    /** What the stage says (on the main thread). */
    private void fromStage(String data) {
        if (data == null) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        String t = m.optString("t"), id = m.optString("id");
        switch (t) {
            case "ok":
                synchronized (acks) { acks.put(id, new String[]{"ok", ""}); acks.notifyAll(); }
                return;
            case "err": {
                String st = m.optString("state"), msg = m.optString("msg", "error");
                if ("test".equals(st)) { synchronized (acks) { testAns = new String[]{"err", id, msg}; acks.notifyAll(); } }
                else if ("play".equals(st)) playErr(id, msg);
                else synchronized (acks) { acks.put(id, new String[]{"err", msg}); acks.notifyAll(); }
                return;
            }
            case "tested":
                synchronized (acks) { testAns = new String[]{"ok", "", ""}; acks.notifyAll(); }
                return;
            case "over": over(m.optInt("score", 0), m.optBoolean("won")); return;
            case "buzz": feel(m.optInt("ms", 40)); return;
            case "exit": exitPlay(); return;
            case "state": stageState = m.optString("state"); return;
            case "file": gotSnap(m); return;
            default:
        }
    }

    private void restartStage(String why) {
        if (destroyed) return;
        newStage();
        if (game != null && job == null) {
            sendReset(game.landscape, game.best, game.how, game.title);
            for (Map.Entry<String, String> e : new ArrayList<>(onStage.entrySet())) sendPart(e.getKey(), e.getValue());
            send(json("preview"));
            if (playing) exitPlay();
        } else if (job != null) {
            Job j = job;
            sendReset(j.landscape, 0, j.how, j.title);
            for (Map.Entry<String, String> e : new ArrayList<>(onStage.entrySet())) sendPart(e.getKey(), e.getValue());
            synchronized (acks) { // (a part that was waiting when the stage stopped: it failed)
                synchronized (j.parts) {
                    for (Part pt : j.parts.values()) if (!pt.ok && !acks.containsKey(pt.id) && pt.code != null)
                        acks.put(pt.id, new String[]{"err", "the game page stopped while running this part (an endless loop?)"});
                }
                acks.notifyAll();
            }
        } else sendReset(false, 0, "", "");
        setStatus(why);
    }

    // ================================================================ making a game

    private void start(String what, String heard, boolean saidAlready) {
        if (job != null) {
            String s = "ఒక ఆట ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        Workshop.Engine e;
        try { e = GameStudio.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), false); return; }
        String over = GameStudio.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, false); return; }
        if (playing) exitPlay();
        Job j = new Job(what, heard, e);
        job = j;
        game = null;
        openTitle = "";
        onStage.clear();
        synchronized (acks) { acks.clear(); testAns = null; }
        rows.clear();
        list.removeAllViews();
        sendReset(false, 0, "", what.length() > 30 ? what.substring(0, 30) + "…" : what);
        titleView.setText(what);
        showChip();
        refresh();
        String money = "సుమారు " + GameStudio.aboutMoney(e.model);
        if (!saidAlready) speak("ఆట తయారు చేస్తున్నాను Sir, " + money + " అవుతుంది.", false);
        setStatus("🧠 " + WorkshopActivity.label(e.model) + " ఆట ఆలోచిస్తోంది… · " + money);
        ticker(j);
        new Thread(() -> run(j), "jarvis-games").start();
    }

    /** While the AI thinks (no part yet): the seconds, and one short line about every ten seconds. */
    private void ticker(Job j) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (job != j || destroyed) return;
                long secs = (System.currentTimeMillis() - j.at) / 1000;
                if (j.parts.isEmpty()) {
                    setStatus("🧠 " + WorkshopActivity.label(j.engine.model) + " ఆట ఆలోచిస్తోంది… " + secs + " సె");
                    if (secs >= j.nextLine) { j.nextLine = secs + 10; Announcer.say(GameStudioActivity.this, WAITING[j.waitLine++ % WAITING.length]); }
                }
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void run(Job j) {
        Http.CANCEL.set(j.cancel);
        String failure = null;
        GameStudio.Parser parser = new GameStudio.Parser(new GameStudio.Parser.Out() {
            @Override public void title(String t) {
                j.title = t;
                ui.post(() -> { if (job == j) titleView.setText(t); });
                try { send(json("info").put("title", t)); } catch (Exception ignored) {}
            }
            @Override public void screen(boolean landscape) {
                j.landscape = landscape;
                try { send(json("screen").put("screen", landscape ? "landscape" : "portrait")); } catch (Exception ignored) {}
            }
            @Override public void how(String h) {
                j.how = h;
                try { send(json("info").put("how", h)); } catch (Exception ignored) {}
            }
            @Override public void partStart(String id, String st) {
                Part pt = j.parts.get(id);
                if (pt == null) { pt = new Part(id); j.parts.put(id, pt); }
                pt.status = st;
                if (j.firstPartAt == 0) j.firstPartAt = System.currentTimeMillis();
                ui.post(() -> { row(id, "⏳ " + st, Ui.MUTED); if (job == j) setStatus("🔧 " + st); });
            }
            @Override public void part(String id, String st, String code, boolean cut) {
                Part pt = j.parts.get(id);
                if (pt == null) { pt = new Part(id); j.parts.put(id, pt); }
                pt.status = st;
                pt.code = code;
                if (cut) { pt.err = "the answer ended before this part was finished (it was cut off)"; ui.post(() -> row(id, "⚠️ " + st, Ui.GOLD)); return; }
                put(j, pt);
            }
            @Override public void say(String s) { j.say = s; }
            @Override public void refuse(String s) { j.refused = s; }
        });
        Workshop.Bill main = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, GameStudio.SYSTEM, GameStudio.buildMessage(j.what, j.heard), GameStudio.GAME_OUT, new Workshop.Sink() {
                @Override public void text(String s) { parser.feed(s); }
            }, main);
            parser.finish();
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Exception x) {
            failure = Workshop.why(p, x, j.engine.provider);
            parser.finish();
        } finally {
            add(j, main);
        }
        List<Part> all;
        synchronized (j.parts) { all = new ArrayList<>(j.parts.values()); }
        for (Part pt : all) if (pt.code != null && pt.err == null && !pt.ok) await(j, pt);
        // the repairs of parts that did not even load: only that part, its error and the others for reference
        if (failure == null && j.refused == null && !j.stopped) {
            for (Part pt : all) {
                while (!pt.ok && pt.err != null && pt.tries < GameStudio.MAX_REPAIRS_PART && j.repairs < GameStudio.MAX_REPAIRS && !j.stopped) {
                    pt.tries++;
                    j.repairs++;
                    repair(j, pt);
                }
            }
        }
        String problem = null;
        boolean complete = !all.isEmpty();
        for (Part pt : all) if (!pt.ok) { complete = false; if (j.badId == null) j.badId = pt.id; if (problem == null) problem = pt.err; }
        j.complete = complete;
        if (failure == null && j.refused == null && !j.stopped && complete) problem = testAndRepair(j);
        finish(j, failure, problem);
    }

    /** One part onto the stage: first the check that it can't reach outside the game. */
    private void put(Job j, Part pt) {
        String bad = GameStudio.unsafe(pt.code);
        if (bad != null) {
            pt.err = bad;
            ui.post(() -> row(pt.id, "⚠️ " + pt.status, Ui.GOLD));
            return;
        }
        synchronized (acks) { acks.remove(pt.id); }
        pt.err = null;
        pt.ok = false;
        ui.post(() -> row(pt.id, "🔧 " + pt.status, Ui.TEXT));
        sendPart(pt.id, pt.code);
    }

    /** Waits for the stage's answer to one part; a stage that never answers is started again. */
    private void await(Job j, Part pt) {
        long until = System.currentTimeMillis() + ACK_WAIT_MS;
        String[] a;
        synchronized (acks) {
            while ((a = acks.get(pt.id)) == null && System.currentTimeMillis() < until && !destroyed && !j.stopped) {
                try { acks.wait(Math.max(50, until - System.currentTimeMillis())); } catch (InterruptedException e) { return; }
            }
        }
        if (a == null) {
            pt.err = j.stopped ? "stopped" : "the game page took too long running this part (an endless loop?)";
            if (!j.stopped) ui.post(this::killStage);
        } else if ("ok".equals(a[0])) {
            pt.ok = true;
            pt.err = null;
            ui.post(() -> { onStage.put(pt.id, pt.code); row(pt.id, "✓ " + pt.status, Ui.OK); });
            return;
        } else {
            pt.err = a[1].isEmpty() ? "error" : a[1];
        }
        ui.post(() -> row(pt.id, "⚠️ " + pt.status, Ui.GOLD));
    }

    /** A stuck page: stopped (onRenderProcessGone then starts a fresh stage), or started again. */
    private void killStage() {
        if (Build.VERSION.SDK_INT >= 29 && web != null) {
            try {
                Object rp = WebView.class.getMethod("getWebViewRenderProcess").invoke(web);
                if (rp != null && Boolean.TRUE.equals(rp.getClass().getMethod("terminate").invoke(rp))) return;
            } catch (Throwable ignored) {}
        }
        restartStage("ఆట పేజీని మళ్ళీ మొదలుపెట్టాను Sir.");
    }

    private void repair(Job j, Part pt) {
        ui.post(() -> { row(pt.id, "🔁 " + pt.status + " (సరిచేస్తోంది)", Ui.CYAN); setStatus("🔁 " + pt.status + " — సరిచేస్తున్నాను"); });
        String[] got = new String[1];
        GameStudio.Parser rp = new GameStudio.Parser(new GameStudio.Parser.Out() {
            @Override public void title(String t) {}
            @Override public void screen(boolean landscape) {}
            @Override public void how(String h) {}
            @Override public void partStart(String id, String st) {}
            @Override public void part(String id, String st, String code, boolean cut) { if (got[0] == null && !cut) got[0] = code; }
            @Override public void say(String s) {}
            @Override public void refuse(String s) {}
        });
        List<String[]> others = new ArrayList<>();
        synchronized (j.parts) { for (Part x : j.parts.values()) if (x.code != null) others.add(new String[]{x.id, x.status, x.code}); }
        Workshop.Bill b = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, GameStudio.SYSTEM, GameStudio.repairMessage(j.title != null ? j.title : j.what, others, pt.id, pt.status, pt.code, pt.err),
                    GameStudio.REPAIR_OUT, new Workshop.Sink() { @Override public void text(String s) { rp.feed(s); } }, b);
            rp.finish();
        } catch (Exception x) {
            if (!(x instanceof CancellationException)) pt.err = "repair request failed: " + x.getMessage();
            return;
        } finally {
            add(j, b);
        }
        if (got[0] == null) { pt.err = "the repair came back without the part"; return; }
        pt.code = got[0];
        put(j, pt);
        if (pt.err == null) await(j, pt);
    }

    /**
     * 🧪 The stage plays the game by itself for a few seconds; a part that fails is repaired (within the caps) and the game is
     * tested again. Null = it plays; else what is still wrong (j.badId = the part).
     */
    private String testAndRepair(Job j) {
        for (int round = 0; round < 6 && !j.stopped && !destroyed; round++) {
            String[] a = test(j);
            if (j.stopped) return "stopped";
            if (a == null) { j.froze = true; return "the game froze while it was being tested (an endless loop?)"; }
            if ("ok".equals(a[0])) { j.tested = true; j.badId = null; return null; }
            Part pt = j.parts.get(a[1]);
            if (pt == null) { synchronized (j.parts) { for (Part x : j.parts.values()) pt = x; } } // (the game as a whole: its last part)
            if (pt == null) return a[2];
            final Part bad = pt;
            bad.ok = false;
            bad.err = a[2];
            j.badId = bad.id;
            ui.post(() -> row(bad.id, "⚠️ " + bad.status, Ui.GOLD));
            if (bad.tries >= GameStudio.MAX_REPAIRS_PART || j.repairs >= GameStudio.MAX_REPAIRS) return a[2];
            do {
                bad.tries++;
                j.repairs++;
                repair(j, bad);
            } while (!bad.ok && bad.err != null && bad.tries < GameStudio.MAX_REPAIRS_PART && j.repairs < GameStudio.MAX_REPAIRS && !j.stopped);
            if (!bad.ok) return bad.err;
        }
        return "the game still has an error";
    }

    /** One self-test: {"ok"}, {"err", part, message}, or null when the stage did not answer (it is started again). */
    private String[] test(Job j) {
        synchronized (acks) { testAns = null; }
        ui.post(() -> setStatus("🧪 ఆట తనంతట తానే ఆడి చూస్తోంది (తప్పులు ఉంటే ఇప్పుడే సరిచేస్తాను)…"));
        send(json("test"));
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

    private void add(Job j, Workshop.Bill b) {
        if (b == null) return;
        j.rupees += b.rupees;
        j.estimated |= b.estimated;
    }

    /** The end of making (or repairing) a game: the money, the saved game, the words. */
    private void finish(Job j, String failure, String problem) {
        int ok = 0, all = 0;
        synchronized (j.parts) { for (Part pt : j.parts.values()) { all++; if (pt.ok) ok++; } }
        boolean stopped = "stopped".equals(failure) || "stopped".equals(problem) || j.stopped;
        // (kept: a game that plays; or a whole game with one error left, which 🔧 can repair later; never a stopped or frozen one)
        boolean whole = !stopped && j.refused == null && failure == null && j.complete && !j.froze;
        boolean keep = j.fixing != null || j.tested || (whole && problem != null);
        Workshop.spendGame(this, j.rupees, j.fixing == null && keep);
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        long secs = (System.currentTimeMillis() - j.at) / 1000;
        String said;
        if (j.refused != null) said = j.refused;
        else if (stopped) said = "ఆపేశాను Sir. ఇప్పటిదాకా ఖర్చు " + cost + ".";
        else if (j.fixing != null) said = j.tested ? "సరిచేశాను Sir, ఇప్పుడు ఆట బాగానే ఆడుతోంది. ఖర్చు " + cost + "." : "ఇంకా పూర్తిగా సరిచేయలేకపోయాను Sir. ఖర్చు " + cost + ".";
        else if (!keep) said = "ఆట తయారు చేయలేకపోయాను Sir" + (failure != null ? ": " + failure : j.froze ? ", ఆట ఆగిపోతోంది" : ", కొన్ని భాగాలు సరిగా రాలేదు") + ". ఖర్చు " + cost + ".";
        else if (!j.tested) said = "ఆట తయారైంది కానీ ఆడి చూస్తే ఒక తప్పు మిగిలింది Sir. 🔧 సరిచేయి నొక్కితే మళ్ళీ ప్రయత్నిస్తాను. ఖర్చు " + cost + ".";
        else said = (j.say != null && !j.say.isEmpty() ? j.say : "మీ ఆట రెడీ Sir.") + " ఈ ఆటకి " + cost + " అయింది. ▶️ ఆడు నొక్కండి.";
        Game made = null;
        if (keep && !stopped) {
            Game g = j.fixing != null ? j.fixing : new Game();
            if (j.fixing == null) {
                g.at = j.at;
                g.title = j.title != null ? j.title : j.what;
                g.what = j.what;
                g.model = j.engine.model;
                g.how = j.how;
                g.say = j.say;
                g.landscape = j.landscape;
                g.seconds = secs;
                g.parts.clear();
            }
            g.cost += j.rupees;
            g.estimated |= j.estimated;
            g.repairs += j.repairs;
            synchronized (j.parts) { for (Part pt : j.parts.values()) if (pt.code != null) g.parts.put(pt.id, new String[]{pt.status, pt.code}); }
            g.brokenId = j.tested ? null : (j.badId != null && g.parts.containsKey(j.badId) ? j.badId : g.brokenId);
            g.brokenMsg = g.brokenId == null ? null : (problem != null ? problem : g.brokenMsg);
            try { GameStudio.save(this, g.json()); } catch (Exception ignored) {}
            made = g;
        } else if (stopped && j.fixing != null) made = j.fixing; // (a stopped repair: the game as it was)
        final Game result = made;
        final String words = said;
        final String line = (j.refused != null ? "🚫 " + j.refused
                : (keep && !stopped ? (j.tested ? "✓ " : "⚠️ ") : "✗ ") + ok + "/" + all + " భాగాలు · " + cost + " · "
                + (secs / 60 > 0 ? secs / 60 + " ని " : "") + secs % 60 + " సె"
                + (failure != null && !"stopped".equals(failure) ? "\n" + failure : !j.tested && problem != null && !stopped ? "\n" + Workshop.cap(problem, 160) : ""));
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            stopBtn.setVisibility(View.GONE);
            if (result != null) {
                game = result;
                openTitle = result.title;
                titleView.setText(result.title);
                onStage.clear();
                for (Map.Entry<String, String[]> e : result.parts.entrySet()) onStage.put(e.getKey(), e.getValue()[1]);
                if (stopped) { sendReset(result.landscape, result.best, result.how, result.title); for (Map.Entry<String, String> e : onStage.entrySet()) sendPart(e.getKey(), e.getValue()); }
                try { send(json("info").put("how", result.how == null ? "" : result.how).put("title", result.title)); } catch (Exception ignored) {}
                send(json("preview"));
                snapLater(result.at, 900);
            }
            refresh();
            setStatus(line);
            speak(words, true);
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
        new AlertDialog.Builder(this).setTitle(j.fixing != null ? "సరిచేయడం ఆపాలా?" : "ఆట తయారీ ఆపాలా?")
                .setMessage("ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది.")
                .setPositiveButton("⏹ ఆపు", (d, w) -> stopJob(j)).setNegativeButton("కొనసాగించు", null).show();
    }

    private void stopJob(Job j) {
        j.stopped = true;
        j.cancel.cancel();
        synchronized (acks) { acks.notifyAll(); }
    }

    private void leave() {
        Job j = job;
        if (j == null) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("ఆట ఇంకా తయారవుతోంది")
                .setMessage("మూసేస్తే అది ఆగిపోతుంది (ఇప్పటిదాకా అయిన ఖర్చు పడుతుంది). మూసేయాలా?")
                .setPositiveButton("మూసేయి", (d, w) -> { stopJob(j); finish(); }).setNegativeButton("ఉండు", null).show();
    }

    @Override public void onBackPressed() {
        if (playing) {
            if ("play".equals(stageState)) send(json("pause")); // (the first back: the game's ⏸ card; the second: the studio)
            else exitPlay();
            return;
        }
        leave();
    }

    // ================================================================ 🔧 repairing a saved game (a part that failed while he played)

    private void playErr(String id, String msg) {
        stageState = "broken";
        if (game == null) return;
        game.brokenId = game.parts.containsKey(id) ? id : lastPartId(game);
        game.brokenMsg = msg;
        try { GameStudio.save(this, game.json()); } catch (Exception ignored) {}
        setStatus("⚠️ ఆటలో ఒక తప్పు వచ్చింది Sir. 🚪 స్టూడియోకి వచ్చి 🔧 సరిచేయి నొక్కండి.");
    }

    private static String lastPartId(Game g) {
        String last = null;
        for (String k : g.parts.keySet()) last = k;
        return last;
    }

    private void fix() {
        if (game == null || game.brokenId == null || job != null) return;
        Workshop.Engine e;
        try { e = GameStudio.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), false); return; }
        String over = GameStudio.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, false); return; }
        Game g = game;
        Job j = new Job(g.what, g.what, e);
        j.fixing = g;
        j.title = g.title;
        j.how = g.how;
        j.landscape = g.landscape;
        Part bad = null;
        rows.clear();
        list.removeAllViews();
        for (Map.Entry<String, String[]> en : g.parts.entrySet()) {
            Part pt = new Part(en.getKey());
            pt.status = en.getValue()[0];
            pt.code = en.getValue()[1];
            pt.ok = true;
            j.parts.put(pt.id, pt);
            if (pt.id.equals(g.brokenId)) bad = pt;
            row(pt.id, "✓ " + pt.status, Ui.OK);
        }
        if (bad == null) return;
        bad.ok = false;
        bad.err = g.brokenMsg == null || g.brokenMsg.isEmpty() ? "it stopped the game with an error" : g.brokenMsg;
        row(bad.id, "⚠️ " + bad.status, Ui.GOLD);
        job = j;
        refresh();
        setStatus("🔧 \"" + bad.status + "\" సరిచేస్తున్నాను · సుమారు " + GameStudio.fixMoney(e.model));
        final Part b0 = bad;
        new Thread(() -> {
            Http.CANCEL.set(j.cancel);
            b0.tries++;
            j.repairs++;
            j.badId = b0.id;
            repair(j, b0);
            String problem = b0.ok ? testAndRepair(j) : b0.err;
            finish(j, j.stopped ? "stopped" : null, problem);
        }, "jarvis-games-fix").start();
    }

    // ================================================================ playing (full screen)

    private void play() {
        if (game == null || job != null || destroyed) return;
        if (waiting != null) cancelWait(false);
        if (ear != null) ear.hush();
        playing = true;
        stageState = "";
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        playCard.setVisibility(View.GONE);
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        immersive(true);
        setRequestedOrientation(game.landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        tilt(true);
        mute(CallControl.busyWithCall());
        send(startMsg());
        ui.removeCallbacks(callWatch);
        ui.postDelayed(callWatch, 1500);
    }

    private void exitPlay() {
        if (!playing) return;
        playing = false;
        stageState = "";
        tilt(false);
        ui.removeCallbacks(callWatch);
        immersive(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        send(json("preview")); // (the game's first still frame behind the card)
        refresh();
    }

    /** 📞 A call: the game waits (⏸) and makes no sound; after it, the sound is back (the game stays paused for his tap). */
    private final Runnable callWatch = new Runnable() {
        @Override public void run() {
            if (!playing || destroyed) return;
            boolean call = CallControl.busyWithCall();
            if (call && "play".equals(stageState)) send(json("pause"));
            if (call != muted) mute(call);
            ui.postDelayed(this, 1500);
        }
    };

    private void mute(boolean on) {
        muted = on;
        try { send(json("mute").put("on", on)); } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN : 0);
    }

    /** A game ended: one more play, and a new best said when it is one. */
    private void over(int score, boolean won) {
        stageState = "over";
        if (game == null) return;
        boolean better = GameStudio.played(this, game.at, score);
        game.plays++;
        if (better) {
            game.best = score;
            if (!CallControl.busyWithCall()) Announcer.say(this, "కొత్త రికార్డ్ Sir! " + score + ".");
        }
    }

    private void feel(int ms) {
        if (CallControl.busyWithCall()) return;
        try {
            if (vib == null) vib = getSystemService(android.os.Vibrator.class);
            if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(Math.max(10, Math.min(300, ms)), android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    /** 📱 The phone's tilt while he plays: how he holds it when the game starts is "straight". */
    private void tilt(boolean on) {
        if (sensors == null) sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (tiltL != null && sensors != null) { try { sensors.unregisterListener(tiltL); } catch (Exception ignored) {} }
        tiltL = null;
        tilt0 = null;
        if (!on || sensors == null) return;
        android.hardware.Sensor sn = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sn == null) sn = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (sn == null) return; // (no tilt on this phone: the game's touch / buttons still work)
        final float[] r = new float[9], rr = new float[9], o = new float[3];
        tiltL = new android.hardware.SensorEventListener() {
            @Override public void onSensorChanged(android.hardware.SensorEvent ev) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - tiltSent < 33) return;
                tiltSent = now;
                android.hardware.SensorManager.getRotationMatrixFromVector(r, ev.values);
                int rot = getWindowManager().getDefaultDisplay().getRotation();
                if (rot == android.view.Surface.ROTATION_90) android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_Y, android.hardware.SensorManager.AXIS_MINUS_X, rr);
                else if (rot == android.view.Surface.ROTATION_270) android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_MINUS_Y, android.hardware.SensorManager.AXIS_X, rr);
                else System.arraycopy(r, 0, rr, 0, 9);
                android.hardware.SensorManager.getOrientation(rr, o);
                if (tilt0 == null) { tilt0 = new float[]{o[1], o[2]}; return; }
                double x = tiltAxis(o[2] - tilt0[1]), y = tiltAxis(-(o[1] - tilt0[0]));
                try { send(json("tilt").put("x", x).put("y", y)); } catch (Exception ignored) {}
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor s0, int a) {}
        };
        sensors.registerListener(tiltL, sn, android.hardware.SensorManager.SENSOR_DELAY_GAME);
    }

    /** About 30° of tilt = full (−1…1), with a small still zone. */
    static double tiltAxis(double rad) {
        double v = rad / 0.52;
        if (Math.abs(v) < 0.04) return 0;
        return Math.max(-1, Math.min(1, v));
    }

    // ================================================================ 📂 the shelf (no AI, no money)

    private void show(JSONObject g) {
        if (g == null || g.optJSONArray("parts") == null) return;
        if (playing) exitPlay();
        game = Game.of(g);
        openTitle = game.title;
        onStage.clear();
        for (Map.Entry<String, String[]> e : game.parts.entrySet()) onStage.put(e.getKey(), e.getValue()[1]);
        rows.clear();
        list.removeAllViews();
        sendReset(game.landscape, game.best, game.how, game.title);
        for (Map.Entry<String, String> e : onStage.entrySet()) sendPart(e.getKey(), e.getValue());
        send(json("preview"));
        titleView.setText(game.title);
        refresh();
        setStatus("📂 దాచిన ఆట · ఆడటానికి ఖర్చు లేదు, నెట్ అక్కర్లేదు · తయారైనప్పుడు " + Workshop.money(game.cost) + (game.estimated ? " (సుమారు)" : ""));
        if (!GameStudio.thumb(this, game.at).isFile()) snapLater(game.at, 1500);
    }

    private void shelf() {
        if (job != null) { Toast.makeText(this, "ముందు ఈ ఆట పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        if (playing) exitPlay();
        List<File> fs = GameStudio.files(this);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int per = wide ? 4 : 2, n = 0;
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout row = null;
        for (File x : fs) {
            JSONObject g = GameStudio.read(x);
            if (g == null || g.optJSONArray("parts") == null) continue;
            if (n % per == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); col.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            n++;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(4), dp(4), dp(4), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF111720);
            bg.setCornerRadius(dp(10));
            View pic;
            android.graphics.Bitmap bm = null;
            File th = GameStudio.thumb(this, g.optLong("at"));
            if (th.isFile()) { try { bm = android.graphics.BitmapFactory.decodeFile(th.getPath()); } catch (Throwable ignored) {} }
            if (bm != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(bg);
                iv.setClipToOutline(true);
                pic = iv;
            } else {
                TextView ph = Ui.text(this, "🎮", 34f, Ui.MUTED);
                ph.setGravity(Gravity.CENTER);
                ph.setBackground(bg);
                pic = ph;
            }
            tile.addView(pic, new LinearLayout.LayoutParams(-1, dp(wide ? 110 : 150)));
            TextView tt = Ui.text(this, g.optString("title"), 13.5f, Ui.TEXT);
            tt.setMaxLines(2);
            tt.setPadding(dp(2), dp(4), dp(2), 0);
            tile.addView(tt);
            int best = g.optInt("best", 0);
            tile.addView(Ui.text(this, (best > 0 ? "🏆 " + best + " · " : "") + f.format(new java.util.Date(g.optLong("at")))
                    + (g.optString("broken_id", "").isEmpty() ? "" : " · ⚠️"), 11f, Ui.MUTED));
            tile.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                show(g);
            });
            row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (n == 0) { Toast.makeText(this, "ఇంకా దాచిన ఆటలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        while (row != null && row.getChildCount() < per) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = new AlertDialog.Builder(this).setTitle("📂 నా గేమ్స్ (ఆడటానికి ఖర్చు లేదు)").setView(sv).setNegativeButton("మూసేయి", null).show();
    }

    /** 🖼️ The shelf's small picture of this game: the stage's still frame, a moment after it is drawn. */
    private void snapLater(long at, long ms) {
        ui.postDelayed(() -> {
            if (destroyed || game == null || game.at != at || playing) return;
            snapReq = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
            snapAt = at;
            try { send(json("snap").put("req", snapReq)); } catch (Exception ignored) {}
        }, ms);
    }

    private void gotSnap(JSONObject m) {
        if (snapReq == null || !snapReq.equals(m.optString("req"))) return;
        snapReq = null;
        final long at = snapAt;
        final String data = m.optString("data", "");
        if (data.isEmpty()) return;
        new Thread(() -> {
            try {
                byte[] b = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                if (!Workshop.jpegOk(b)) return;
                File d = GameStudio.dir(this);
                if (!d.isDirectory() && !d.mkdirs()) return;
                try (FileOutputStream o = new FileOutputStream(GameStudio.thumb(this, at))) { o.write(b); }
            } catch (Exception ignored) {}
        }, "jarvis-games-thumb").start();
    }

    // ================================================================ in front, behind, gone

    /** The game on the studio screen while it is in front or was left within the last 10 minutes; "" = open with no game; null = not open. */
    static String openTitle() {
        String t = openTitle;
        long s = stoppedAt;
        return t != null && s > 0 && System.currentTimeMillis() - s > 10 * 60_000L ? null : t;
    }

    @Override protected void onStart() {
        super.onStart();
        stoppedAt = 0;
        if (openTitle == null) openTitle = game == null ? "" : game.title;
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
        if (playing) { immersive(true); tilt(true); ui.removeCallbacks(callWatch); ui.postDelayed(callWatch, 1500); }
    }

    @Override protected void onPause() {
        super.onPause();
        if (playing) send(json("pause")); // (he left: the game waits on its ⏸ card)
        front = false;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        JarvisAccessibility.bubbleAside(false);
        if (ear != null) ear.away();
        if (waiting != null) cancelWait(false); // (he left: nothing is spent on what was heard)
        tilt(false);
        ui.removeCallbacks(callWatch);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        openTitle = null;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        if (front) { front = false; JarvisAccessibility.bubbleAside(false); }
        if (ear != null) ear.shutdown();
        Job j = job;
        if (j != null) stopJob(j);
        tilt(false);
        ui.removeCallbacksAndMessages(null);
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) {
            try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) {}
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    /** Opens the studio (from ⚙️, a tool, the brain): a game to make, the last one, the shelf, or a saved game to play. */
    static void open(Context c, String what, String heard, boolean saidAlready) {
        Intent i = new Intent(c, GameStudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null && !what.trim().isEmpty()) i.putExtra(EXTRA_WHAT, what.trim()).putExtra(EXTRA_HEARD, heard).putExtra(EXTRA_SAID, saidAlready);
        else i.putExtra(EXTRA_OPEN_LAST, true);
        c.startActivity(i);
    }
}
