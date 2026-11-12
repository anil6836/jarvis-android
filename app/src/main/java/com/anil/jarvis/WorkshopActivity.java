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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
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
 * 🛠️ The workshop screen (Anil, 12 Nov): a 3D studio where the AI he chose builds what he asked for, part by part, live.
 * The stage is a WebView sealed off from everything: it loads only the workshop's own files from the app (every other
 * request answered "blocked" here, never sent), the AI's code runs inside a sandboxed frame with no network, and the page
 * has no bridge into Jarvis at all — only one message port carrying "run this part" in and "ok / error" out. Keys never
 * reach it. A build: his request → the AI's parts stream in → each part runs as soon as it is written (a tick in the list)
 * → a part that fails is sent back for repair (2 tries a part, 3 a build, only that part) → saved, and he hears the
 * finished line with what it cost. Saved builds open again without asking the AI (free). Phone only.
 */
public class WorkshopActivity extends Activity {
    static final String EXTRA_WHAT = "what", EXTRA_HEARD = "heard", EXTRA_SAID = "said", EXTRA_OPEN_LAST = "open_last", EXTRA_TALK = "talk";
    /** The name of the model on the stage while this screen is on (Jarvis's brain is told: what he says is about it); else null. */
    static volatile String openTitle; // (read through openTitle())
    private static volatile long stoppedAt; // (when the screen went behind another; 0 = in front)

    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/workshop/";
    private static final String HARNESS = "https://" + HOST + PATH + "harness.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("harness.html", "harness.js", "stage.html", "stage.js",
            "three.module.min.js", "OrbitControls.js", "RoundedBoxGeometry.js"));
    private static final long ACK_WAIT_MS = 20000;
    private static final String[] WAITING = {
            "డిజైన్ ఆలోచిస్తున్నాను Sir.", "కొలతలు సరిచూస్తున్నాను.", "ఏ భాగం ఏ లోహంతో చేయాలో ఎంచుకుంటున్నాను.",
            "ఇంకొంచెం సేపు Sir, మొదటి భాగం దగ్గర్లోనే ఉంది.", "భాగాలు వరుసగా పెట్టడానికి సిద్ధం చేస్తున్నాను."};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs p;
    private FrameLayout stageBox;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private TextView titleView, chip, status, stopBtn, spinBtn;
    private LinearLayout list;
    private ScrollView listScroll;
    private EditText input;
    private boolean spinning = true;
    private boolean destroyed;
    private View topBar, bottomBar;
    private float sentTop = -1, sentBottom = -1;
    private TextView go, explodeBtn, undoBtn, nightBtn;
    private boolean exploded, night;
    private boolean started;

    /** 🎙️ (A3) while this screen is in front, "Jarvis" opens no panel: the workshop's own ears and voice hear him. */
    static volatile boolean front;
    private WorkshopVoice ear;
    private final Runnable wakeR = () -> { if (ear != null && front && !destroyed) ear.wake(); };
    private TextView micBtn, cancelBtn;
    /** 👆 The part he tapped on the model (its id, the piece's name); null = none. */
    private String pickedId, pickedName;
    /** ✋ A paid job heard by voice waits 3 seconds for "వద్దు" (a misheard sentence costs nothing). */
    private String waiting;
    private Runnable waitGo;
    private long waitSince;
    /** 📖 The card with a detailed answer about a part. */
    private LinearLayout answerCard;
    private TextView answerTitle, answerBody;
    private final Runnable unglow = this::glowPicked;

    // (A4) 🔍 one part alone, 🩻 X-ray, 🔪 the cut, 🙈 hidden, 📱 tilt; 📸 / 🖨️ / 🖼️ what the stage hands over
    private TextView isoBtn, xrayBtn, cutBtn, hideBtn, allBtn, sizeBtn, snapBtn, studioBtn, stlBtn, replayBtn, tiltBtn, rotBtn;
    private LinearLayout cutRow;
    private boolean isolatedOn, xrayOn, cutOn, tiltOn, landscape;
    private int hiddenCount;
    /** The picture / print file asked of the stage (its one-time id) and what it is for: photo, stl, studio, mini. */
    private String fileReq, fileFor;
    private android.hardware.SensorManager sensors;
    private android.hardware.SensorEventListener tiltL;
    private float[] tilt0;
    private long tiltSent;
    private static final int REQ_CAM = 61, REQ_GAL = 62, REQ_CAM_PERM = 63;
    private int photoKind = Workshop.PHOTO;
    /** The last hide asked: "hide", "roof_off", "roof_on" (its answer says what happened). */
    private String lastHide;

    /** The model on the stage now (a finished build or a saved one): what a change is made to, and what is saved. */
    private Shown shown;
    /** Up to 5 models as they were before each change ("↩️ వెనక్కి" puts the last one back, with no AI). */
    private final java.util.ArrayDeque<Shown> undo = new java.util.ArrayDeque<>();

    private static final class Shown {
        long at;
        String title, what, model, say;
        double cost;
        boolean estimated;
        int changes, repairs;
        long seconds = -1, firstPart = -1;
        /** id → {status, code}, in order. */
        final LinkedHashMap<String, String[]> parts = new LinkedHashMap<>();

        Shown copy() {
            Shown c = new Shown();
            c.at = at; c.title = title; c.what = what; c.model = model; c.say = say; c.cost = cost; c.estimated = estimated;
            c.changes = changes; c.repairs = repairs; c.seconds = seconds; c.firstPart = firstPart;
            for (Map.Entry<String, String[]> e : parts.entrySet()) c.parts.put(e.getKey(), e.getValue().clone());
            return c;
        }

        List<String[]> list() {
            List<String[]> l = new ArrayList<>();
            for (Map.Entry<String, String[]> e : parts.entrySet()) l.add(new String[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
            return l;
        }

        JSONObject json() throws Exception {
            JSONArray ps = new JSONArray();
            for (Map.Entry<String, String[]> e : parts.entrySet())
                ps.put(new JSONObject().put("id", e.getKey()).put("status", e.getValue()[0]).put("code", e.getValue()[1]));
            return new JSONObject().put("at", at).put("title", title).put("what", what == null ? "" : what).put("model", model == null ? "" : model)
                    .put("parts", ps).put("say", say == null ? "" : say).put("cost", cost).put("estimated", estimated).put("repairs", repairs)
                    .put("changes", changes).put("seconds", seconds).put("first_part_seconds", firstPart);
        }

        static Shown of(JSONObject b) {
            Shown s = new Shown();
            s.at = b.optLong("at", System.currentTimeMillis());
            s.title = b.optString("title");
            s.what = b.optString("what");
            s.model = b.optString("model");
            s.say = b.optString("say");
            s.cost = b.optDouble("cost", 0);
            s.estimated = b.optBoolean("estimated");
            s.changes = b.optInt("changes", 0);
            s.repairs = b.optInt("repairs", 0);
            s.seconds = b.optLong("seconds", -1);
            s.firstPart = b.optLong("first_part_seconds", -1);
            JSONArray ps = b.optJSONArray("parts");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                JSONObject pt = ps.optJSONObject(i);
                if (pt != null) s.parts.put(pt.optString("id"), new String[]{pt.optString("status", pt.optString("id")), pt.optString("code")});
            }
            return s;
        }
    }

    /** What is on the stage now, in order (id → code): sent again if the stage has to start over. */
    private final LinkedHashMap<String, String> onStage = new LinkedHashMap<>();
    /** The answers the stage gave, by part id (under {@link #acks}). */
    private final Map<String, String[]> acks = new HashMap<>();
    private final Map<String, TextView> rows = new LinkedHashMap<>();

    private volatile Job job;

    /** One build being made. */
    private static final class Job {
        final String what, heard;
        final long at = System.currentTimeMillis();
        final Http.Cancel cancel = new Http.Cancel();
        final Workshop.Engine engine;
        volatile boolean stopped;
        String title, say, refused;
        /** (A2) a change / question about the model on the stage: what he said, the answer, a new object he asked for, parts taken off. */
        final String talk;
        String answer, newWhat;
        /** (A3) the long answer was asked; the part an answer is about; the part he had tapped. */
        boolean detail;
        String about, pickedId, pickedName;
        /** (A4) a build from his photo (JPEG, base64; never saved) and what it is: a thing or a floor plan. */
        String photo;
        int photoKind;
        final List<String> removed = new ArrayList<>();
        /** Written by the build's thread, read by the screen: one lock (iterate inside synchronized (parts)). */
        final Map<String, Part> parts = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        double rupees;
        boolean estimated;
        int repairs;
        long firstPartAt;
        int waitLine;
        long nextLine = 10;
        Job(String what, String heard, Workshop.Engine engine) { this(what, heard, engine, null); }
        Job(String what, String heard, Workshop.Engine engine, String talk) { this.what = what; this.heard = heard; this.engine = engine; this.talk = talk; }
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
            Toast.makeText(this, "🛠️ వర్క్‌షాప్ ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        landscape = getSharedPreferences(Workshop.PREFS, MODE_PRIVATE).getBoolean("landscape", false);
        if (landscape) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        build();
        fit(getResources().getConfiguration());
        newStage();
        ear = new WorkshopVoice(owner(), p);
        Intent i = getIntent();
        // brought back after Android closed it, or opened again from recent apps: never build (and pay) a second time
        if (i != null && (b != null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0)) { i.removeExtra(EXTRA_WHAT); i.removeExtra(EXTRA_TALK); }
        handle(i);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String talk = i.getStringExtra(EXTRA_TALK);
        if (talk != null && !talk.trim().isEmpty()) { // ("Jarvis, రెక్కలు నీలం చెయ్" said over this screen, through the brain)
            i.removeExtra(EXTRA_TALK);
            if (shown == null && job == null) { JSONObject last = Workshop.last(this); if (last != null) show(last); }
            said(talk.trim(), true);
            return;
        }
        String what = i.getStringExtra(EXTRA_WHAT);
        if (what != null && !what.trim().isEmpty()) {
            final String w0 = what.trim(), h0 = i.getStringExtra(EXTRA_HEARD);
            final boolean s0 = i.getBooleanExtra(EXTRA_SAID, false);
            i.removeExtra(EXTRA_WHAT); // (a rotation or a return to the screen must not build it again)
            if (front && ear != null && ear.voiceFlow) waitThen(w0, () -> start(w0, h0, s0)); // (asked by voice here: ✋ 3 seconds)
            else start(w0, h0, s0);
            return;
        }
        if (job != null) return;
        if (i.getBooleanExtra(EXTRA_OPEN_LAST, false) || onStage.isEmpty()) {
            JSONObject last = Workshop.last(this);
            if (last != null) { show(last); return; }
            if (i.getBooleanExtra(EXTRA_OPEN_LAST, false)) setStatus("ఇంకా దాచిన బొమ్మ ఏదీ లేదు Sir. కింద ఏం తయారు చేయాలో రాయండి, లేదా \"Jarvis, ఒక టేబుల్ ఫ్యాన్ తయారు చెయ్\" అనండి.");
            else setStatus("ఏం తయారు చేయాలో కింద రాయండి Sir, లేదా \"Jarvis, ఒక డ్రోన్ తయారు చెయ్\" అనండి.");
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        stageBox = new FrameLayout(this);
        root.addView(stageBox, new FrameLayout.LayoutParams(-1, -1));

        // the top: the name of what is on the stage, which AI makes it, close
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(10), dp(22));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE607080B, 0x0007080B}));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "🛠️ వర్క్‌షాప్", 13, Ui.MUTED));
        titleView = Ui.text(this, "", 19, Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(titleView);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        chip = Ui.text(this, "", 11.5f, Ui.CYAN);
        chip.setBackground(Ui.round(this, 0x2216303F, Ui.CYAN_DIM, 999));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        rotBtn = Ui.text(this, "🔄", 19, Ui.TEXT);
        rotBtn.setPadding(dp(14), dp(6), dp(4), dp(6));
        rotBtn.setContentDescription("అడ్డంగా / నిలువుగా");
        rotBtn.setOnClickListener(v -> turnScreen(!landscape));
        top.addView(rotBtn);
        TextView close = Ui.text(this, "✕", 22, Ui.TEXT);
        close.setPadding(dp(16), dp(6), dp(8), dp(6));
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> leave());
        top.addView(close);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        topBar = top;

        // the bottom: what Jarvis is doing, the parts with their ticks, the buttons, the request box
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(26), dp(14), dp(12));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF207080B, 0xCC07080B, 0x0007080B}));
        status = Ui.text(this, "", 15, Ui.TEXT);
        status.setPadding(dp(2), 0, dp(2), dp(6));
        bottom.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.24f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        listScroll.addView(list);
        listScroll.setVerticalFadingEdgeEnabled(true); // (the top row fades instead of looking cut)
        listScroll.setFadingEdgeLength(dp(26));
        bottom.addView(listScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        TextView saved = pill("📂 దాచినవి", v -> savedList());
        TextView cost = pill("💰 ఖర్చు", v -> { String s = Workshop.costSaid(this, p); setStatus(s); Announcer.say(this, s); });
        spinBtn = pill("⏸ తిప్పడం ఆపు", v -> command(spinning ? "spin_off" : "spin_on", false));
        TextView view = pill("🎯 మధ్యలో", v -> command("center", false));
        stopBtn = pill("⏹ ఆపు", v -> askStop());
        stopBtn.setVisibility(View.GONE);
        explodeBtn = pill("💥 విడదీయి", v -> command(exploded ? "assemble" : "explode", false));
        undoBtn = pill("↩️ వెనక్కి", v -> command("undo", false));
        nightBtn = pill("🌙 రాత్రి", v -> command(night ? "day" : "night", false));
        TextView fresh = pill("🆕 కొత్తది", v -> command("new", false));
        cancelBtn = pill("✋ వద్దు", v -> cancelWait(true));
        cancelBtn.setVisibility(View.GONE);
        isoBtn = pill("🔍 ఒక్కటే", v -> command(isolatedOn ? "showall" : "alone", false));
        xrayBtn = pill("🩻 ఎక్స్-రే", v -> command("xray", false));
        cutBtn = pill("🔪 లోపల", v -> command(cutOn ? "cut_off" : "cut", false));
        hideBtn = pill("🙈 దాచు", v -> command("hide", false));
        allBtn = pill("👁️ అన్నీ చూపించు", v -> command("showall", false));
        sizeBtn = pill("📏 కొలతలు", v -> command("measure", false));
        snapBtn = pill("📸 ఫోటో", v -> command("photo", false));
        studioBtn = pill("🖼️ స్టూడియో", v -> studioChoice());
        stlBtn = pill("🖨️ ప్రింట్ ఫైల్", v -> command("stl", false));
        replayBtn = pill("🎬 తయారీ", v -> command("replay", false));
        tiltBtn = pill("📱 వంచు", v -> command(tiltOn ? "tilt_off" : "tilt_on", false));
        TextView fromPhoto = pill("📷 ఫోటో నుంచి 3D", v -> photoChoice());
        actions.addView(cancelBtn);
        actions.addView(stopBtn);
        actions.addView(explodeBtn);
        actions.addView(undoBtn);
        actions.addView(isoBtn);
        actions.addView(hideBtn);
        actions.addView(allBtn);
        actions.addView(xrayBtn);
        actions.addView(cutBtn);
        actions.addView(sizeBtn);
        actions.addView(snapBtn);
        actions.addView(studioBtn);
        actions.addView(stlBtn);
        actions.addView(replayBtn);
        actions.addView(tiltBtn);
        actions.addView(fromPhoto);
        actions.addView(saved);
        actions.addView(cost);
        actions.addView(spinBtn);
        actions.addView(view);
        actions.addView(nightBtn);
        actions.addView(fresh);
        // 🔪 where the cut is: a slider while it is on
        cutRow = new LinearLayout(this);
        cutRow.setGravity(Gravity.CENTER_VERTICAL);
        cutRow.addView(Ui.text(this, "🔪 ముందు", 12.5f, Ui.MUTED));
        android.widget.SeekBar cutBar = new android.widget.SeekBar(this);
        cutBar.setMax(100);
        cutBar.setProgress(50);
        cutBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean user) {
                if (user) { try { send(json("cut").put("k", v / 100.0)); } catch (Exception ignored) {} }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        cutRow.addView(cutBar, new LinearLayout.LayoutParams(0, -2, 1));
        cutRow.addView(Ui.text(this, "వెనుక", 12.5f, Ui.MUTED));
        cutRow.setVisibility(View.GONE);
        bottom.addView(cutRow);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        bottom.addView(hs);

        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("ఏం తయారు చేయాలి? ఉదా: ఒక టేబుల్ ఫ్యాన్");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(15.5f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
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

        answerCard = new LinearLayout(this);
        answerCard.setOrientation(LinearLayout.VERTICAL);
        answerCard.setBackground(Ui.round(this, 0xEE0B1118, Ui.CYAN_DIM, 16));
        answerCard.setPadding(dp(14), dp(10), dp(10), dp(12));
        LinearLayout ah = new LinearLayout(this);
        ah.setGravity(Gravity.CENTER_VERTICAL);
        answerTitle = Ui.text(this, "", 15, Ui.CYAN);
        ah.addView(answerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ax = Ui.text(this, "✕", 18, Ui.TEXT);
        ax.setPadding(dp(12), dp(2), dp(6), dp(2));
        ax.setContentDescription("మూసేయి");
        ax.setOnClickListener(v -> closeAnswer());
        ah.addView(ax);
        answerCard.addView(ah);
        answerBody = Ui.text(this, "", 14.5f, Ui.TEXT);
        answerBody.setLineSpacing(0, 1.18f);
        ScrollView as = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.30f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        as.addView(answerBody);
        answerCard.addView(as, new LinearLayout.LayoutParams(-1, -2));
        answerCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams acl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        acl.leftMargin = acl.rightMargin = dp(12);
        acl.topMargin = dp(84);
        root.addView(answerCard, acl);
        setContentView(root);
        // the stage frames the model in the space the bars leave (they are see-through at their soft edges: a little less)
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int h = root.getHeight();
            if (h <= 0 || topBar == null || bottomBar == null) return;
            float t = Math.max(0, topBar.getHeight() - dp(16)) / (float) h, bt = Math.max(0, bottomBar.getHeight() - dp(20)) / (float) h;
            if (Math.abs(t - sentTop) < 0.01f && Math.abs(bt - sentBottom) < 0.01f) return;
            sentTop = t;
            sentBottom = bt;
            FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) answerCard.getLayoutParams();
            int want = Math.max(dp(40), topBar.getHeight() - dp(12));
            if (cl.topMargin != want) { cl.topMargin = want; answerCard.setLayoutParams(cl); }
            try { send(json("inset").put("top", t).put("bottom", bt)); } catch (Exception ignored) {}
        });
        showChip();
        refreshButtons();
    }

    /** The buttons and the request box follow what is on the stage (a model: "what to change"; nothing: "what to build"). */
    private void refreshButtons() {
        if (go == null) return;
        boolean has = shown != null && !shown.parts.isEmpty();
        go.setText(has ? "➤ పంపు" : "🔨 తయారు చెయ్");
        input.setHint(has && pickedName != null ? "👆 " + pickedName + ": ఇది ఏం చేస్తుంది? · ఇది ఎరుపు చెయ్"
                : has ? "ఏం మార్చాలి? ఉదా: రెక్కలు నీలం చెయ్ · విడదీయి · 👆 భాగం తాకండి" : "ఏం తయారు చేయాలి? ఉదా: ఒక టేబుల్ ఫ్యాన్");
        explodeBtn.setVisibility(has ? View.VISIBLE : View.GONE);
        explodeBtn.setText(exploded ? "🧩 కలుపు" : "💥 విడదీయి");
        if (isoBtn != null) {
            isoBtn.setVisibility(has && (pickedId != null || isolatedOn) ? View.VISIBLE : View.GONE);
            isoBtn.setText(isolatedOn ? "🧩 అన్నీ" : "🔍 ఒక్కటే");
            hideBtn.setVisibility(has && pickedId != null ? View.VISIBLE : View.GONE);
            allBtn.setVisibility(has && (isolatedOn || xrayOn || cutOn || hiddenCount > 0) ? View.VISIBLE : View.GONE);
            xrayBtn.setText(xrayOn ? "🩻 ఎక్స్-రే ఆపు" : "🩻 ఎక్స్-రే");
            cutBtn.setText(cutOn ? "🔪 కోత తీసేయి" : "🔪 లోపల");
            tiltBtn.setText(tiltOn ? "📱 వంచడం ఆపు" : "📱 వంచు");
            for (TextView t : new TextView[]{xrayBtn, cutBtn, sizeBtn, snapBtn, studioBtn, stlBtn, replayBtn, tiltBtn})
                t.setVisibility(has ? View.VISIBLE : View.GONE);
            cutRow.setVisibility(has && cutOn ? View.VISIBLE : View.GONE);
        }
        undoBtn.setVisibility(has && !undo.isEmpty() && job == null ? View.VISIBLE : View.GONE);
        nightBtn.setText(night ? "☀️ పగలు" : "🌙 రాత్రి");
        spinBtn.setText(spinning ? "⏸ తిప్పడం ఆపు" : "▶️ తిప్పు");
        openTitle = has ? shown.title : "";
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = Ui.pill(this, s);
        t.setTextSize(13.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private void showChip() {
        String m = p.workshopModel().trim();
        chip.setText(m.isEmpty() ? "AI ఎంచుకోలేదు" : label(m));
    }

    /** claude-fable-5-2 → "FABLE 5.2" (a hyphen between two digits is a version dot). */
    static String label(String model) {
        String m = model.replaceFirst("^models/", "").replaceFirst("^claude-", "");
        m = m.replaceAll("(\\d)-(\\d)", "$1.$2").replace('-', ' ');
        return m.toUpperCase(java.util.Locale.ROOT);
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

    /** A view / light / pull-apart / undo word: done at once, no AI. Anything else: a new build, or a change to the model on the stage. */
    private void said(String text, boolean byVoice) {
        String cmd = Workshop.command(text);
        if (cmd != null) { command(cmd, byVoice); return; }
        if (job != null) {
            String s = "ఒక పని ఇంకా జరుగుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        boolean has = shown != null && !shown.parts.isEmpty();
        Runnable job0 = has ? () -> talk(text) : () -> start(text, text, false);
        if (byVoice) waitThen(text, job0); // (heard by voice: ✋ 3 seconds to say "వద్దు" before any money is spent)
        else job0.run();
    }

    // ================================================================ ✋ a paid job heard by voice waits 3 seconds (A3)

    private void waitThen(String words, Runnable then) {
        cancelWait(false);
        waiting = words;
        waitSince = System.currentTimeMillis();
        waitGo = new Runnable() {
            @Override public void run() {
                if (waiting == null || destroyed) return;
                // (he is still saying something - maybe "వద్దు": wait for it, up to 8 seconds)
                if (ear != null && ear.hearing() && System.currentTimeMillis() - waitSince < 8000) { ui.postDelayed(this, 400); return; }
                waiting = null;
                cancelBtn.setVisibility(View.GONE);
                then.run();
            }
        };
        cancelBtn.setVisibility(View.VISIBLE);
        setStatus("✏️ “" + words + "” · 3 సెకన్లలో మొదలుపెడతాను · వద్దంటే ✋ వద్దు");
        ui.postDelayed(waitGo, 3000);
        if (ear != null && front) ui.postDelayed(() -> { if (waiting != null && ear != null) ear.listenShort(3); }, 300);
    }

    /** His words while a job waits: "వద్దు" stops it; anything else replaces it (false: they are routed as usual). */
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

    // ================================================================ 🎙️ the workshop's own ears (A3)

    private WorkshopVoice.Owner owner() {
        return new WorkshopVoice.Owner() {
            @Override public Activity screen() { return WorkshopActivity.this; }
            @Override public boolean take(String words) { return tookByVoice(words); }
            @Override public void line(String text) { setStatus(text); }
            @Override public void mic(int state) { micLook(state); }
            @Override public boolean front() { return front && !destroyed; }
            @Override public boolean waitWords(String words) { return waitHeard(words); }
        };
    }

    /** Heard by voice here: a ₹0 word, or plainly about the model (it names a part, a colour, "ఇది" with a part tapped). Else the brain. */
    private boolean tookByVoice(String words) {
        String cmd = Workshop.command(words);
        if (cmd != null) { command(cmd, true); return true; }
        boolean has = shown != null && !shown.parts.isEmpty();
        if (has && Workshop.aboutModel(words, names(), pickedId != null)) { said(words, true); return true; }
        return false;
    }

    /** The model's name and its parts' names (from their status lines). */
    private List<String> names() {
        List<String> n = new ArrayList<>();
        if (shown == null) return n;
        if (shown.title != null) for (String w : shown.title.split("[\\s\\-–]+")) if (w.length() >= 3) n.add(w);
        for (Map.Entry<String, String[]> e : shown.parts.entrySet()) {
            n.add(Workshop.partLabel(e.getValue()[0], e.getKey()));
            n.add(e.getKey().replace('-', ' '));
        }
        if (pickedName != null) n.add(pickedName);
        return n;
    }

    private void micLook(int state) {
        if (micBtn == null) return;
        int bg = state == WorkshopVoice.LISTENING ? Ui.CYAN : state == WorkshopVoice.THINKING ? 0x33F5C451 : state == WorkshopVoice.SPEAKING ? 0x334FD2FF : 0x1AFFFFFF;
        micBtn.setBackground(Ui.round(this, bg, state == WorkshopVoice.LISTENING ? Ui.CYAN : Ui.LINE2, 999));
        micBtn.setText(state == WorkshopVoice.LISTENING ? "👂" : "🎙️");
    }

    /** Said here: in Jarvis's voice with one more listen after it when he is talking by voice; else the usual announcement. */
    private void speak(String s, boolean thenListen) {
        if (s == null || s.trim().isEmpty()) return;
        if (ear != null && ear.voiceFlow && front && !destroyed) ear.say(s, thenListen);
        else Announcer.say(this, s);
    }

    // ================================================================ 👆 a tapped part, 📖 the answer card (A3)

    private void picked(String id, String name) {
        if (id == null || id.isEmpty()) {
            boolean had = pickedId != null;
            pickedId = null;
            pickedName = null;
            if (had && job == null && waiting == null) setStatus("👆 భాగం వదిలేశాను");
        } else {
            pickedId = id;
            pickedName = name == null || name.isEmpty() ? id.replace('-', ' ') : name;
            if (job == null && waiting == null) setStatus("👆 " + pickedName + " · \"ఇది ఏం చేస్తుంది?\" / \"ఇది ఎరుపు చెయ్\" అనండి లేదా రాయండి");
        }
        refreshButtons();
    }

    private void forgetPick() {
        pickedId = null;
        pickedName = null;
        closeAnswer();
    }

    /** The tapped part glows again (after an answer's part glowed), or nothing glows. */
    private void glowPicked() {
        try {
            if (pickedId != null) send(json("glow").put("id", pickedId).put("label", pickedName == null ? "" : pickedName));
            else send(json("unpick"));
        } catch (Exception ignored) {}
    }

    private void showAnswer(String title, String text, String aboutId) {
        ui.removeCallbacks(unglow);
        answerTitle.setText("📖 " + title);
        answerBody.setText(text);
        answerCard.setVisibility(View.VISIBLE);
        if (aboutId != null) { try { send(json("glow").put("id", aboutId).put("label", aboutId.equals(pickedId) && pickedName != null ? pickedName : "")); } catch (Exception ignored) {} }
    }

    private void closeAnswer() {
        if (answerCard == null || answerCard.getVisibility() != View.VISIBLE) return;
        answerCard.setVisibility(View.GONE);
        glowPicked();
    }

    /** The name of a part on the model (the tapped piece's name when it is that part). */
    private String nameOf(String id) {
        if (id == null) return null;
        if (id.equals(pickedId) && pickedName != null) return pickedName;
        String[] s = shown == null ? null : shown.parts.get(id);
        return s == null ? null : Workshop.partLabel(s[0], id);
    }

    private void command(String cmd, boolean byVoice) {
        boolean has = shown != null && !shown.parts.isEmpty();
        String said = null;
        switch (cmd) {
            case "explode":
            case "assemble":
                if (!has) { said = "ముందు ఒక బొమ్మ తయారు చేయనివ్వండి Sir."; break; }
                exploded = "explode".equals(cmd);
                try { send(json("explode").put("on", exploded)); } catch (Exception ignored) {}
                said = exploded ? "విడదీశాను Sir. ప్రతి భాగానికి పేరు ఉంది." : "మళ్ళీ కలిపాను Sir.";
                break;
            case "top": case "front": case "back": case "side":
                spinning = false;
                try { send(json("spin").put("on", false)); send(json("view").put("dir", cmd)); } catch (Exception ignored) {}
                said = "top".equals(cmd) ? "పై నుంచి." : "front".equals(cmd) ? "ముందు నుంచి." : "back".equals(cmd) ? "వెనుక నుంచి." : "పక్క నుంచి.";
                break;
            case "zoom_in": case "zoom_out":
                try { send(json("zoom").put("in", "zoom_in".equals(cmd))); } catch (Exception ignored) {}
                break;
            case "spin_off": case "spin_on":
                spinning = "spin_on".equals(cmd);
                try { send(json("spin").put("on", spinning)); } catch (Exception ignored) {}
                break;
            case "night": case "day":
                night = "night".equals(cmd);
                try { send(json("light").put("mode", night ? "night" : "day")); } catch (Exception ignored) {}
                break;
            case "center":
                send(json("frame"));
                break;
            case "undo":
                said = undoLast();
                break;
            case "cost":
                said = Workshop.costSaid(this, p);
                setStatus(said);
                break;
            case "saved":
                savedList();
                break;
            case "close":
                leave();
                break;
            case "new":
                if (job != null) { said = "ఒక పని ఇంకా జరుగుతోంది Sir."; break; }
                shown = null;
                undo.clear();
                exploded = false;
                onStage.clear();
                rows.clear();
                list.removeAllViews();
                send(json("reset"));
                titleView.setText("");
                forgetPick();
                viewReset();
                setStatus("ఏం తయారు చేయాలో కింద రాయండి Sir, లేదా \"Jarvis, ఒక డ్రోన్ తయారు చెయ్\" అనండి.");
                input.requestFocus();
                break;
            // ---- (A4) the view tools, ₹0
            case "alone": case "alone_hide":
                if (!has) { said = "ముందు ఒక బొమ్మ తయారు చేయనివ్వండి Sir."; break; }
                if (pickedId == null) { said = "ముందు బొమ్మలో ఒక భాగం తాకండి Sir (లేదా కింద జాబితాలో దాని పేరు)."; break; }
                try { send(json("isolate").put("on", true).put("mode", "alone_hide".equals(cmd) ? "hide" : "ghost")); } catch (Exception ignored) {}
                said = (pickedName != null ? pickedName : "ఈ భాగం") + " ఒక్కటే Sir." + ("alone_hide".equals(cmd) ? "" : " మిగతావి మసకగా ఉన్నాయి.");
                break;
            case "showall":
                send(json("showall"));
                isolatedOn = false; xrayOn = false; cutOn = false; hiddenCount = 0;
                if (has) said = "అన్నీ చూపిస్తున్నాను Sir.";
                break;
            case "xray":
                if (!has) break;
                xrayOn = !xrayOn;
                try { send(json("xray").put("on", xrayOn)); } catch (Exception ignored) {}
                said = xrayOn ? "ఎక్స్-రే Sir." : "ఎక్స్-రే ఆపాను.";
                break;
            case "cut": case "cut_off":
                if (!has) break;
                cutOn = "cut".equals(cmd);
                try { send(json("cut").put("on", cutOn).put("k", 0.5)); } catch (Exception ignored) {}
                said = cutOn ? "సగానికి కోసి చూపిస్తున్నాను Sir. కింద జారే గీతతో కోత జరపొచ్చు." : "కోత తీసేశాను.";
                break;
            case "hide":
                if (!has) break;
                if (pickedId == null) { said = "ఏ భాగం దాచాలో ముందు దాన్ని తాకండి Sir."; break; }
                lastHide = "hide";
                try { send(json("hide").put("on", true)); } catch (Exception ignored) {}
                break;
            case "roof_off": case "roof_on":
                if (!has) break;
                lastHide = cmd;
                try { send(json("hide").put("on", "roof_off".equals(cmd)).put("match", "roof")); } catch (Exception ignored) {}
                if ("roof_on".equals(cmd)) said = "పైకప్పు పెట్టాను Sir.";
                break;
            case "measure":
                if (!has) break;
                send(json("measure"));
                break;
            case "replay":
                if (!has) break;
                send(json("replay"));
                said = "ఎలా తయారైందో మళ్ళీ చూపిస్తున్నాను Sir.";
                break;
            case "tilt_on": case "tilt_off":
                if (!has) break;
                tilt("tilt_on".equals(cmd));
                said = tiltOn ? "ఫోన్ కొంచెం వంచితే బొమ్మ చుట్టూ చూడొచ్చు Sir." : "వంచడం ఆపాను.";
                break;
            case "photo": case "stl":
                if (!has) break;
                askFile("photo".equals(cmd) ? "photo" : "stl");
                said = "photo".equals(cmd) ? null : "ప్రింట్ ఫైల్ తయారు చేస్తున్నాను Sir.";
                break;
            case "studio": case "mini":
                if (!has) break;
                if (byVoice) { final String what = cmd; waitThen("studio".equals(cmd) ? "స్టూడియో ఫోటో" : "మినియేచర్ ఫోటో", () -> studio("mini".equals(what))); }
                else studio("mini".equals(cmd));
                break;
            case "fromphoto": photoChoice(); break;
            case "plan": photoKind = Workshop.PLAN; pickPhoto(Workshop.PLAN); break;
            case "landscape": case "portrait": turnScreen("landscape".equals(cmd)); break;
            default: break;
        }
        refreshButtons();
        if (said != null && (byVoice || "cost".equals(cmd) || "undo".equals(cmd))) speak(said, byVoice);
        if (said != null && !"cost".equals(cmd)) setStatus(said);
    }

    /** ↩️ The model as it was before the last change (no AI, no cost); saved like that. */
    private String undoLast() {
        if (job != null) return "ఒక పని ఇంకా జరుగుతోంది Sir.";
        Shown before = undo.pollFirst();
        if (before == null) return "వెనక్కి వెళ్ళడానికి ఏ మార్పూ లేదు Sir.";
        if (shown != null) before.cost = shown.cost; // (what was spent stays spent)
        shown = before;
        putOnStage(shown);
        save(shown);
        return "↩️ ముందులా చేశాను Sir (ఖర్చు లేదు).";
    }

    /** The whole model onto a cleared stage (a saved build, an undo): no AI. */
    private void putOnStage(Shown sh) {
        forgetPick();
        viewReset();
        onStage.clear();
        rows.clear();
        list.removeAllViews();
        send(json("reset"));
        exploded = false;
        for (Map.Entry<String, String[]> e : sh.parts.entrySet()) {
            onStage.put(e.getKey(), e.getValue()[1]);
            sendPart(e.getKey(), e.getValue()[1]);
            row(e.getKey(), "✓ " + e.getValue()[0], Ui.OK);
        }
        titleView.setText(sh.title);
        if (sh.model != null && !sh.model.isEmpty()) chip.setText(label(sh.model));
        refreshButtons();
    }

    private void save(Shown sh) {
        try { Workshop.save(this, sh.json()); } catch (Exception ignored) {}
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
                Uri u = r.getUrl(); // (only the stage frame's own page may load; nothing navigates anywhere else)
                return !(u != null && HOST.equals(u.getHost()) && (PATH + "stage.html").equals(u.getPath()));
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (v == web && url != null && url.startsWith(HARNESS)) connect(v);
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // the stage's page stopped (a part that never ended, or the phone took its memory): a fresh stage, the same model again
                if (v == web) ui.post(() -> restartStage("ఆగిపోయిన 3D పేజీని మళ్ళీ మొదలుపెట్టాను Sir."));
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

    /** Only the workshop's own files, from the app; anything else is answered "blocked" here and never leaves the phone. */
    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*"); // (the sealed frame has no origin of its own; it may still read these files)
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            if (FILES.contains(name)) {
                try {
                    InputStream in = getAssets().open("workshop/" + name);
                    String mime = name.endsWith(".html") ? "text/html" : "text/javascript";
                    return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
                } catch (Exception ignored) {}
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", h, new ByteArrayInputStream(new byte[0]));
    }

    /** Hands the outer page its one port to Jarvis (posted to that page's own origin only). */
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
            setStatus("3D స్టేజ్ తెరవలేకపోయాను Sir: " + e.getMessage());
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

    private void sendPart(String id, String code) {
        try { send(json("part").put("id", id).put("code", code).put("label", Workshop.partLabel(statusOf(id), id))); } catch (Exception ignored) {}
    }

    /** The part's Telugu status line ("బేస్ పెడుతున్నాను"): its name when pulled apart if the code gave its pieces none (builds before A2). */
    private String statusOf(String id) {
        Job j = job;
        Part pt = j == null ? null : j.parts.get(id);
        if (pt != null && pt.status != null) return pt.status;
        String[] s = shown == null ? null : shown.parts.get(id);
        return s == null ? null : s[0];
    }

    /** "ok" / "err" for a part, from the stage (on the main thread). */
    private void fromStage(String data) {
        if (data == null) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        String t = m.optString("t"), id = m.optString("id");
        if ("pick".equals(t)) { picked(id, m.optString("label")); return; }
        if ("view".equals(t)) {
            if (id.isEmpty()) { isolatedOn = false; if ("nothing-picked".equals(m.optString("label"))) setStatus("ముందు బొమ్మలో ఒక భాగం తాకండి Sir."); }
            else { isolatedOn = true; setStatus("🔍 " + m.optString("label") + " ఒక్కటే · \"అన్నీ చూపించు\" అంటే మొత్తం బొమ్మ"); }
            refreshButtons();
            return;
        }
        if ("size".equals(t)) {
            String nm = id.isEmpty() ? (shown != null ? shown.title : "బొమ్మ") : m.optString("label", id);
            String line = "📏 " + nm + ": వెడల్పు " + cm(m.optDouble("w")) + " · ఎత్తు " + cm(m.optDouble("h")) + " · లోతు " + cm(m.optDouble("d")) + " సెం.మీ";
            setStatus(line + " (AI డిజైన్ చేసిన కొలతలు)");
            speak(nm + ": వెడల్పు " + cm(m.optDouble("w")) + ", ఎత్తు " + cm(m.optDouble("h")) + ", లోతు " + cm(m.optDouble("d")) + " సెంటీమీటర్లు Sir.", true);
            return;
        }
        if ("hid".equals(t)) { // "newly hidden/hidden now"
            String[] nt = m.optString("label", "0/0").split("/");
            int n = 0, total = 0;
            try { n = Integer.parseInt(nt[0]); total = Integer.parseInt(nt.length > 1 ? nt[1] : nt[0]); } catch (Exception ignored) {}
            hiddenCount = total;
            String was = lastHide;
            lastHide = null;
            if ("roof_on".equals(was)) setStatus(total == 0 ? "🏠 పైకప్పు పెట్టాను" : "🏠 పైకప్పు పెట్టాను · ఇంకా " + total + " దాచి ఉన్నాయి");
            else if (n > 0) setStatus(("roof_off".equals(was) ? "🏠 పైకప్పు తీసేశాను" : "🙈 దాచాను") + " · \"అన్నీ చూపించు\" అంటే తిరిగి వస్తుంది");
            else if ("roof_off".equals(was)) { setStatus("ఈ బొమ్మలో పైకప్పు భాగం లేదు Sir."); speak("ఈ బొమ్మలో పైకప్పు భాగం లేదు Sir.", true); }
            refreshButtons();
            return;
        }
        if ("replay".equals(t)) { String nm = nameOf(id); setStatus("🎬 " + m.optString("label") + " · " + (nm != null ? nm : id)); return; }
        if ("file".equals(t)) { gotFile(m); return; }
        if ("ok".equals(t) || "err".equals(t)) {
            synchronized (acks) {
                acks.put(id, new String[]{t, m.optString("msg")});
                acks.notifyAll();
            }
            Job j = job;
            Part pt = j == null ? null : j.parts.get(id);
            if (pt == null || pt.status == null) return;
            if ("ok".equals(t)) { if (pt.code != null) onStage.put(id, pt.code); row(id, "✓ " + pt.status, Ui.OK); }
            else row(id, "⚠️ " + pt.status, Ui.GOLD);
        }
    }

    private void restartStage(String why) {
        if (destroyed) return;
        newStage();
        send(json("reset"));
        try { if (sentTop >= 0) send(json("inset").put("top", sentTop).put("bottom", sentBottom)); } catch (Exception ignored) {}
        for (Map.Entry<String, String> e : new ArrayList<>(onStage.entrySet())) sendPart(e.getKey(), e.getValue());
        try {
            if (exploded) send(json("explode").put("on", true).put("turn", false));
            if (night) send(json("light").put("mode", "night"));
            if (!spinning) send(json("spin").put("on", false));
        } catch (Exception ignored) {}
        if (pickedId != null) glowPicked();
        if (isolatedOn || xrayOn || cutOn || hiddenCount > 0) { viewReset(); refreshButtons(); } // (the new stage starts plain)
        synchronized (acks) { // (a part that was waiting when the stage stopped: it failed)
            Job j = job;
            if (j != null) synchronized (j.parts) {
                for (Part pt : j.parts.values()) if (!pt.ok && !acks.containsKey(pt.id) && pt.code != null)
                    acks.put(pt.id, new String[]{"err", "the stage stopped while running this part (an endless loop?)"});
            }
            acks.notifyAll();
        }
        setStatus(why);
    }

    // ================================================================ a build

    private void start(String what, String heard, boolean saidAlready) { start(what, heard, saidAlready, null, 0); }

    private void start(String what, String heard, boolean saidAlready, String photo, int kind) {
        if (job != null) {
            String s = "ఒక బొమ్మ ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        Workshop.Engine e;
        try { e = Workshop.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), false); return; }
        String over = Workshop.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, false); return; }
        Job j = new Job(what, heard, e);
        j.photo = photo;
        j.photoKind = kind;
        job = j;
        shown = null;
        undo.clear();
        exploded = false;
        forgetPick();
        viewReset();
        onStage.clear();
        synchronized (acks) { acks.clear(); }
        rows.clear();
        list.removeAllViews();
        send(json("reset"));
        titleView.setText(what);
        showChip();
        stopBtn.setVisibility(View.VISIBLE);
        refreshButtons();
        if (!saidAlready) speak("బెంచ్ మీద పెడుతున్నాను Sir.", false);
        setStatus("🧠 " + label(e.model) + " డిజైన్ ఆలోచిస్తోంది…");
        ticker(j, "డిజైన్ ఆలోచిస్తోంది");
        new Thread(() -> run(j), "jarvis-workshop").start();
    }

    /** While the AI thinks (nothing on the stage yet): the seconds, and one short line about every ten seconds. */
    private void ticker(Job j, String doing) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (job != j || destroyed) return;
                long secs = (System.currentTimeMillis() - j.at) / 1000;
                if (j.parts.isEmpty() && j.removed.isEmpty() && j.answer == null) {
                    setStatus("🧠 " + label(j.engine.model) + " " + doing + "… " + secs + " సె");
                    if (secs >= j.nextLine) { // (one short line about every ten seconds: thinking never looks like a hang)
                        j.nextLine = secs + 10;
                        Announcer.say(WorkshopActivity.this, WAITING[j.waitLine++ % WAITING.length]);
                    }
                }
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ================================================================ a change / a question about the model on the stage (A2)

    /** His words go to the AI with the model's parts; back come only the parts that change (or an answer, or "build X instead"). */
    private void talk(String words) {
        Workshop.Engine e;
        try { e = Workshop.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), false); return; }
        String over = Workshop.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, false); return; }
        final Shown before = shown.copy();
        Job j = new Job(before.what, words, e, words);
        j.title = before.title;
        j.detail = Workshop.wantsDetail(words);
        j.pickedId = pickedId;
        j.pickedName = pickedName;
        closeAnswer();
        job = j;
        stopBtn.setVisibility(View.VISIBLE);
        refreshButtons();
        setStatus("🧠 \"" + words + "\" — ఆలోచిస్తోంది…");
        ticker(j, "\"" + (words.length() > 30 ? words.substring(0, 30) + "…" : words) + "\" ఆలోచిస్తోంది");
        new Thread(() -> runTalk(j, before), "jarvis-workshop-change").start();
    }

    private void runTalk(Job j, Shown before) {
        Http.CANCEL.set(j.cancel);
        String failure = null;
        Workshop.Parser parser = new Workshop.Parser(new Workshop.Parser.Out() {
            @Override public void title(String t) {}
            @Override public void partStart(String id, String st) {
                Part pt = j.parts.get(id);
                if (pt == null) { pt = new Part(id); j.parts.put(id, pt); }
                pt.status = st;
                final boolean had = before.parts.containsKey(id);
                ui.post(() -> { row(id, (had ? "🔁 " : "⏳ ") + st, Ui.CYAN); if (job == j) setStatus("🔧 " + st); });
            }
            @Override public void part(String id, String st, String code, boolean cut) {
                Part pt = j.parts.get(id);
                if (pt == null) { pt = new Part(id); j.parts.put(id, pt); }
                pt.status = st;
                pt.code = code;
                if (cut) { pt.err = "the answer ended before this part was finished (it was cut off)"; return; }
                put(j, pt);
            }
            @Override public void say(String sx) { j.say = sx; }
            @Override public void refuse(String sx) { j.refused = sx; }
            @Override public void remove(String id) {
                if (!before.parts.containsKey(id)) return;
                j.removed.add(id);
                try { send(json("remove").put("id", id)); } catch (Exception ignored) {}
                ui.post(() -> { TextView t = rows.remove(id); if (t != null) list.removeView(t); onStage.remove(id); });
            }
            @Override public void answer(String sx) { j.answer = sx; }
            @Override public void newBuild(String what) { j.newWhat = what; }
            @Override public void about(String id) { if (before.parts.containsKey(id)) j.about = id; }
        });
        Workshop.Bill b = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, Workshop.SYSTEM, Workshop.changeMessage(before.title, before.list(), j.talk, j.pickedId, j.pickedName, j.detail), Workshop.CHANGE_OUT,
                    new Workshop.Sink() { @Override public void text(String sx) { parser.feed(sx); } }, b);
            parser.finish();
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Exception x) {
            failure = Workshop.why(p, x, j.engine.provider);
            parser.finish();
        } finally {
            add(j, b);
        }
        List<Part> all;
        synchronized (j.parts) { all = new ArrayList<>(j.parts.values()); }
        for (Part pt : all) if (pt.code != null && pt.err == null && !pt.ok) await(j, pt);
        if (failure == null && j.refused == null && !j.stopped) {
            for (Part pt : all) {
                while (!pt.ok && pt.err != null && pt.tries < Workshop.MAX_REPAIRS_CHANGE && j.repairs < Workshop.MAX_REPAIRS_CHANGE && !j.stopped) {
                    pt.tries++;
                    j.repairs++;
                    repair(j, pt);
                }
            }
        }
        finishTalk(j, before, failure, all);
    }

    private void finishTalk(Job j, Shown before, String failure, List<Part> all) {
        Workshop.spend(this, j.rupees, false);
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        int ok = 0, bad = 0;
        for (Part pt : all) { if (pt.ok) ok++; else if (pt.code != null || pt.err != null) bad++; }
        boolean changed = (ok > 0 || !j.removed.isEmpty()) && failure == null && j.refused == null && !j.stopped && j.answer == null && j.newWhat == null;
        Shown after = before.copy();
        String said, line;
        if (changed) {
            for (String id : j.removed) after.parts.remove(id);
            for (Part pt : all) if (pt.ok) after.parts.put(pt.id, new String[]{pt.status, pt.code});
            after.changes++;
            String end = j.say != null && !j.say.isEmpty() ? j.say : "మార్చాను Sir.";
            said = end + " ఈ మార్పుకి " + cost + " అయింది." + (bad > 0 ? " (" + bad + " భాగం రాలేదు, అది పాతదే ఉంచాను.)" : "");
            line = "✏️ " + (ok + j.removed.size()) + " భాగాలు మారాయి · " + cost + (bad > 0 ? " · " + bad + " రాలేదు" : "");
        } else if (j.answer != null && failure == null) {
            said = j.answer;
            boolean card = j.detail || j.answer.length() > 160;
            line = (card ? "📖 " + (j.about != null ? "వివరాలు పైన" : "జవాబు పైన") : "💬 " + j.answer) + " · " + cost;
        } else if (j.newWhat != null && failure == null) {
            said = null;
            line = null;
        } else if (j.refused != null) {
            said = j.refused;
            line = "🚫 " + j.refused;
        } else if ("stopped".equals(failure) || j.stopped) {
            said = "ఆపేశాను Sir. బొమ్మ ముందులాగే ఉంది; ఖర్చు " + cost + ".";
            line = "⏹ ఆపేశారు · " + cost;
        } else if (failure != null) {
            said = "మార్పు చేయలేకపోయాను Sir: " + failure;
            line = "✗ " + failure;
        } else {
            said = "ఏం మార్చాలో నాకు అర్థం కాలేదు Sir. ఇంకోలా చెప్పండి.";
            line = "? ఏమీ మారలేదు · " + cost;
        }
        after.cost = before.cost + j.rupees;
        after.estimated |= j.estimated;
        save(after);
        final String words = said, status = line;
        final boolean keep = changed;
        final String next = j.newWhat != null && failure == null && !j.stopped ? j.newWhat : null;
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            stopBtn.setVisibility(View.GONE);
            if (keep) {
                undo.addFirst(before);
                while (undo.size() > 5) undo.removeLast();
                shown = after;
                // the parts that failed even after a repair: their old version goes back on the stage
                for (Part pt : all) {
                    if (pt.ok) continue;
                    String[] old = before.parts.get(pt.id);
                    if (old != null) sendPart(pt.id, old[1]);
                    else { try { send(json("remove").put("id", pt.id)); } catch (Exception ignored) {} }
                }
                rows.clear();
                list.removeAllViews();
                for (Map.Entry<String, String[]> e : shown.parts.entrySet()) {
                    onStage.put(e.getKey(), e.getValue()[1]);
                    row(e.getKey(), "✓ " + e.getValue()[0], Ui.OK);
                }
            } else {
                shown = after; // (the same model; what was spent is added)
                if (j.parts.size() > 0 || !j.removed.isEmpty()) putOnStage(shown); // (half a change: the model as it was)
                else { rows.clear(); list.removeAllViews(); for (Map.Entry<String, String[]> e : shown.parts.entrySet()) row(e.getKey(), "✓ " + e.getValue()[0], Ui.OK); }
            }
            refreshButtons();
            if (status != null) setStatus(status);
            if (j.answer != null && failure == null && !keep) { // 📖 the part it is about glows; a long answer on its card
                String about = j.about != null ? j.about : j.pickedId;
                if (j.detail || j.answer.length() > 160) {
                    String nm = nameOf(about);
                    showAnswer(nm != null ? nm : (shown != null ? shown.title : ""), j.answer, about);
                } else if (about != null) {
                    try { send(json("glow").put("id", about).put("label", about.equals(pickedId) && pickedName != null ? pickedName : "")); } catch (Exception ignored) {}
                    ui.removeCallbacks(unglow);
                    ui.postDelayed(unglow, 7000);
                }
            }
            if (words != null) speak(words, true);
            if (next != null) start(next, j.talk, false);
        });
    }

    /** The build itself (its own thread): the AI's answer, part by part onto the stage, repairs, saved, said. */
    private void run(Job j) {
        Http.CANCEL.set(j.cancel);
        String failure = null;
        Workshop.Parser parser = new Workshop.Parser(new Workshop.Parser.Out() {
            @Override public void title(String t) { j.title = t; ui.post(() -> { if (job == j) titleView.setText(t); }); }
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
                if (cut) { pt.err = "the answer ended before this part was finished (it was cut off)"; return; }
                put(j, pt);
            }
            @Override public void say(String s) { j.say = s; }
            @Override public void refuse(String s) { j.refused = s; }
        });
        Workshop.Bill main = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, Workshop.SYSTEM, j.photo != null ? Workshop.photoMessage(j.heard, j.photoKind) : Workshop.buildMessage(j.what, j.heard),
                    j.photo, Workshop.MAX_OUT, new Workshop.Sink() {
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
        // every part that went to the stage has its answer (or failed waiting)
        List<Part> all;
        synchronized (j.parts) { all = new ArrayList<>(j.parts.values()); }
        for (Part pt : all) if (pt.code != null && pt.err == null && !pt.ok) await(j, pt);
        // the repairs: only a failed part, its error and its code; 2 tries a part, 3 a build
        if (failure == null && j.refused == null && !j.stopped) {
            for (Part pt : all) {
                while (!pt.ok && pt.err != null && pt.tries < Workshop.MAX_REPAIRS_PART && j.repairs < Workshop.MAX_REPAIRS && !j.stopped) {
                    pt.tries++;
                    j.repairs++;
                    repair(j, pt);
                }
            }
        }
        finish(j, failure);
    }

    /** One part onto the stage: first the check that it can't reach outside the stage. */
    private void put(Job j, Part pt) {
        String bad = Workshop.unsafe(pt.code);
        if (bad != null) {
            pt.err = "it uses " + bad + ", which the workshop does not allow (only THREE and W)";
            ui.post(() -> row(pt.id, "⚠️ " + pt.status, Ui.GOLD));
            return;
        }
        synchronized (acks) { acks.remove(pt.id); }
        pt.err = null;
        ui.post(() -> row(pt.id, "🔧 " + pt.status, Ui.TEXT));
        sendPart(pt.id, pt.code);
    }

    /** Waits for the stage's answer to one part; a stage that never answers is started again. */
    private void await(Job j, Part pt) {
        long until = System.currentTimeMillis() + ACK_WAIT_MS;
        String[] a;
        synchronized (acks) {
            while ((a = acks.get(pt.id)) == null && System.currentTimeMillis() < until && !destroyed) {
                try { acks.wait(Math.max(50, until - System.currentTimeMillis())); } catch (InterruptedException e) { return; }
            }
        }
        if (a == null) {
            pt.err = "the stage took too long running this part (an endless loop?)";
            ui.post(() -> {
                if (Build.VERSION.SDK_INT >= 29 && web != null) { // (stop the stuck page; onRenderProcessGone starts a fresh stage)
                    try {
                        Object rp = WebView.class.getMethod("getWebViewRenderProcess").invoke(web);
                        if (rp != null && Boolean.TRUE.equals(rp.getClass().getMethod("terminate").invoke(rp))) return;
                    } catch (Throwable ignored) {}
                }
                restartStage("3D స్టేజ్‌ని మళ్ళీ మొదలుపెట్టాను Sir.");
            });
        } else if ("ok".equals(a[0])) {
            pt.ok = true;
            pt.err = null;
            ui.post(() -> { onStage.put(pt.id, pt.code); row(pt.id, "✓ " + pt.status, Ui.OK); });
        } else {
            pt.err = a[1].isEmpty() ? "error" : a[1];
        }
        if (!pt.ok) ui.post(() -> row(pt.id, "⚠️ " + pt.status, Ui.GOLD));
    }

    private void repair(Job j, Part pt) {
        ui.post(() -> { row(pt.id, "🔁 " + pt.status + " (సరిచేస్తోంది)", Ui.CYAN); setStatus("🔁 " + pt.status + " — సరిచేస్తున్నాను"); });
        String[] got = new String[1];
        Workshop.Parser rp = new Workshop.Parser(new Workshop.Parser.Out() {
            @Override public void title(String t) {}
            @Override public void partStart(String id, String st) {}
            @Override public void part(String id, String st, String code, boolean cut) { if (got[0] == null && !cut) got[0] = code; }
            @Override public void say(String s) {}
            @Override public void refuse(String s) {}
        });
        Workshop.Bill b = new Workshop.Bill();
        try {
            Workshop.stream(j.engine, Workshop.SYSTEM, Workshop.repairMessage(j.title, pt.id, pt.status, pt.code, pt.err),
                    Workshop.REPAIR_OUT, new Workshop.Sink() { @Override public void text(String s) { rp.feed(s); } }, b);
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

    private void add(Job j, Workshop.Bill b) {
        if (b == null) return;
        j.rupees += b.rupees;
        j.estimated |= b.estimated;
    }

    /** The end of a build: the money, the saved copy, the words. */
    private void finish(Job j, String failure) {
        int ok = 0, all = 0;
        synchronized (j.parts) { for (Part pt : j.parts.values()) { all++; if (pt.ok) ok++; } }
        Workshop.spend(this, j.rupees, ok > 0);
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        long secs = (System.currentTimeMillis() - j.at) / 1000;
        String said;
        if (j.refused != null) {
            said = j.refused;
        } else if ("stopped".equals(failure) || j.stopped) {
            said = "ఆపేశాను Sir. ఇప్పటివరకు " + ok + " భాగాలు వచ్చాయి; ఖర్చు " + cost + ".";
        } else if (failure != null && ok == 0) {
            said = "బొమ్మ తయారు చేయలేకపోయాను Sir: " + failure;
        } else {
            String end = j.say != null && !j.say.isEmpty() ? j.say : "పూర్తయింది Sir.";
            said = end + " ఈ బొమ్మకి " + cost + " అయింది." + (ok < all ? " (" + (all - ok) + " భాగాలు రాలేదు.)" : "");
            if (failure != null) said += " (" + failure + ")";
        }
        Shown made = null;
        if (ok > 0) {
            made = new Shown();
            made.at = j.at;
            made.title = j.title != null ? j.title : j.what;
            made.what = j.what;
            made.model = j.engine.model;
            made.say = j.say;
            made.cost = j.rupees;
            made.estimated = j.estimated;
            made.repairs = j.repairs;
            made.seconds = secs;
            made.firstPart = j.firstPartAt == 0 ? -1 : (j.firstPartAt - j.at) / 1000;
            List<Part> done;
            synchronized (j.parts) { done = new ArrayList<>(j.parts.values()); }
            for (Part pt : done) if (pt.ok) made.parts.put(pt.id, new String[]{pt.status, pt.code});
            save(made);
        }
        final Shown result = made;
        final String words = said;
        final String line = (j.refused != null ? "🚫 " : ok > 0 ? "✓ " : "✗ ") + (j.refused != null ? j.refused
                : ok + "/" + all + " భాగాలు · " + cost + " · " + (secs / 60 > 0 ? secs / 60 + " ని " : "") + secs % 60 + " సె"
                + (failure != null && !"stopped".equals(failure) ? "\n" + failure : ""));
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            if (result != null) shown = result;
            stopBtn.setVisibility(View.GONE);
            refreshButtons();
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
            t.setOnClickListener(v -> { if (job == null && shown != null && shown.parts.containsKey(id)) { try { send(json("pickpart").put("id", id)); } catch (Exception ignored) {} } });
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
        new AlertDialog.Builder(this).setTitle("బొమ్మ తయారీ ఆపాలా?")
                .setMessage("ఇప్పటివరకు వచ్చిన భాగాలు ఉంటాయి. ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది.")
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
        new AlertDialog.Builder(this).setTitle("బొమ్మ ఇంకా తయారవుతోంది")
                .setMessage("మూసేస్తే తయారీ ఆగిపోతుంది (ఇప్పటిదాకా అయిన ఖర్చు పడుతుంది). మూసేయాలా?")
                .setPositiveButton("మూసేయి", (d, w) -> { stopJob(j); finish(); }).setNegativeButton("ఉండు", null).show();
    }

    @Override public void onBackPressed() { leave(); }

    // ================================================================ saved builds (no AI, no money)

    private void show(JSONObject b) {
        if (b.optJSONArray("parts") == null) return;
        shown = Shown.of(b);
        undo.clear();
        putOnStage(shown);
        setStatus("📂 దాచిన బొమ్మ (మళ్ళీ తెరవడానికి ఖర్చు లేదు) · తయారైనప్పుడు " + Workshop.money(b.optDouble("cost", 0))
                + (b.optBoolean("estimated") ? " (సుమారు)" : ""));
    }

    private void savedList() {
        if (job != null) { Toast.makeText(this, "ముందు ఈ బొమ్మ పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        List<File> fs = Workshop.files(this);
        if (fs.isEmpty()) { Toast.makeText(this, "ఇంకా దాచిన బొమ్మలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        List<JSONObject> bs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH);
        for (File x : fs) {
            JSONObject b = Workshop.read(x);
            if (b == null) continue;
            bs.add(b);
            names.add(b.optString("title") + "  ·  " + f.format(new java.util.Date(b.optLong("at"))));
        }
        new AlertDialog.Builder(this).setTitle("📂 దాచిన బొమ్మలు (తెరవడానికి ఖర్చు లేదు)")
                .setItems(names.toArray(new String[0]), (d, i) -> show(bs.get(i))).setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ lifecycle

    @Override protected void onStart() {
        super.onStart();
        started = true;
        stoppedAt = 0;
        refreshButtons();
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        stoppedAt = System.currentTimeMillis(); // (the full Jarvis screen over it: still "open" for a while, so his words reach it)
    }

    /** The model on the workshop screen while it is in front or was left within the last 10 minutes; "" = open with no model; null = not open. */
    static String openTitle() {
        String t = openTitle;
        long s = stoppedAt;
        return t != null && s > 0 && System.currentTimeMillis() - s > 10 * 60_000L ? null : t;
    }

    @Override protected void onResume() {
        super.onResume();
        showChip();
        send(json("resume"));
        front = true;
        WakeService.workshopWake = wakeR; // (🎙️ "Jarvis" here: the workshop hears him, no panel over it)
        JarvisAccessibility.bubbleAside(true);
    }

    @Override protected void onPause() {
        super.onPause();
        send(json("pause"));
        front = false;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        JarvisAccessibility.bubbleAside(false);
        if (ear != null) ear.away();
        if (waiting != null) cancelWait(false); // (he left: nothing is spent on what was heard)
        if (tiltOn) tilt(false);
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

    // ================================================================ (A4) the view tools, photos, print files, landscape

    /** The stage starts plain again (a new build, a saved one, a fresh stage): no isolation, X-ray, cut, hidden parts, tilt. */
    private void viewReset() {
        isolatedOn = false;
        xrayOn = false;
        cutOn = false;
        hiddenCount = 0;
        if (tiltOn) tilt(false);
    }

    private static String cm(double v) {
        return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ENGLISH, "%.1f", v);
    }

    /** 📱 Tilt the phone to look around the model (the phone's motion sensor, only while this screen is in front). */
    private void tilt(boolean on) {
        if (sensors == null) sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (tiltL != null && sensors != null) { try { sensors.unregisterListener(tiltL); } catch (Exception ignored) {} }
        tiltL = null;
        tilt0 = null;
        tiltOn = false;
        try { send(json("tilt").put("on", false)); } catch (Exception ignored) {}
        if (!on || sensors == null) return;
        android.hardware.Sensor sn = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sn == null) sn = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (sn == null) { setStatus("ఈ ఫోన్‌లో వంపు సెన్సార్ లేదు Sir."); return; }
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
                if (tilt0 == null) { tilt0 = new float[]{o[1], o[2]}; return; } // (how he holds it now = straight ahead)
                double x = (o[2] - tilt0[1]) * 1.3, y = -(o[1] - tilt0[0]);
                try { send(json("tilt").put("on", true).put("x", x).put("y", y)); } catch (Exception ignored) {}
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor s0, int a) {}
        };
        sensors.registerListener(tiltL, sn, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        tiltOn = true;
    }

    /** 📸 / 🖨️ / 🖼️: the stage is asked once for a picture or a print file; only that answer is taken. */
    private void askFile(String what) {
        fileReq = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
        fileFor = what;
        try {
            if ("stl".equals(what)) send(json("stl").put("req", fileReq));
            else send(json("snap").put("req", fileReq).put("clean", !"photo".equals(what)));
        } catch (Exception ignored) {}
        if ("photo".equals(what)) setStatus("📸 …");
    }

    private void gotFile(JSONObject m) {
        String req = m.optString("req"), what = fileFor;
        if (fileReq == null || !fileReq.equals(req) || what == null) return; // (only the one asked for)
        fileReq = null;
        fileFor = null;
        final String data = m.optString("data", ""), kind = m.optString("kind");
        final String title = shown != null ? shown.title : "model";
        final int w = m.optInt("w"), h = m.optInt("h"), d = m.optInt("d");
        new Thread(() -> {
            String line, said;
            try {
                byte[] b = data.isEmpty() ? null : android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                if ("stl".equals(what)) {
                    if (!"stl".equals(kind) || !Workshop.stlOk(b)) throw new IllegalStateException("ప్రింట్ ఫైల్ సరిగ్గా రాలేదు");
                    String name = Workshop.fileName(title, Math.max(w, Math.max(h, d)) + "mm.stl");
                    Coder.Made made = Coder.save(this, "Jarvis/workshop", name, "model/stl", b);
                    line = "🖨️ " + made.where + " · " + w + " × " + d + " × " + h + " మి.మీ";
                    said = "ప్రింట్ ఫైల్ Downloads లో Jarvis, workshop ఫోల్డర్‌లో దాచాను Sir. పొడవైన వైపు 10 సెంటీమీటర్లు. ప్రింట్ చేసే ముందు slicer లో ఒకసారి చూడండి.";
                } else if ("photo".equals(what)) {
                    if (!"jpg".equals(kind) || !Workshop.jpegOk(b)) throw new IllegalStateException("ఫోటో సరిగ్గా రాలేదు");
                    Coder.Made made = Workshop.savePicture(this, Workshop.fileName(title, System.currentTimeMillis() % 1000000 + ".jpg"), "image/jpeg", b);
                    line = "📸 గ్యాలరీలో దాచాను: " + made.where;
                    said = "ఫోటో గ్యాలరీలో దాచాను Sir.";
                } else { // studio / mini: the render goes to his image AI
                    if (!"jpg".equals(kind) || !Workshop.jpegOk(b)) throw new IllegalStateException("బొమ్మ ఫోటో సరిగ్గా రాలేదు");
                    studioRun(b, "mini".equals(what), title);
                    return;
                }
            } catch (Exception x) {
                line = "✗ " + x.getMessage();
                said = x.getMessage();
            }
            final String l0 = line, s0 = said;
            ui.post(() -> { if (destroyed) return; setStatus(l0); speak(s0, true); });
        }, "jarvis-workshop-file").start();
    }

    /** 🖼️ Which picture: a studio photo or a miniature (his image AI; the cost said before). */
    private void studioChoice() {
        new AlertDialog.Builder(this).setTitle("🖼️ బొమ్మని ఫోటోగా")
                .setMessage("మీరు ⚙️ లో ఎంచుకున్న బొమ్మల AI ఈ 3D బొమ్మని నిజమైన ఫోటోలా మారుస్తుంది. ఒక్కో ఫోటో సుమారు ₹1–15 (మోడల్‌ని బట్టి), వర్క్‌షాప్ నెల ఖర్చులో కలుస్తుంది.")
                .setPositiveButton("📸 స్టూడియో ఫోటో", (dd, ww) -> studio(false))
                .setNeutralButton("🏠 మినియేచర్", (dd, ww) -> studio(true))
                .setNegativeButton("వద్దు", null).show();
    }

    private void studio(boolean mini) {
        if (shown == null || shown.parts.isEmpty()) return;
        try { Workshop.imageEngine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), true); return; }
        String over = Workshop.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, true); return; }
        setStatus(mini ? "🏠 మినియేచర్ ఫోటో తయారవుతోంది…" : "🖼️ స్టూడియో ఫోటో తయారవుతోంది…");
        askFile(mini ? "mini" : "studio");
    }

    /** (a background thread) The clean render → his image AI → saved in the gallery, shown, the cost said. */
    private void studioRun(byte[] jpeg, boolean mini, String title) {
        String line, said;
        Workshop.Picture pic = null;
        Coder.Made made = null;
        try {
            Workshop.Engine e = Workshop.imageEngine(p);
            ui.post(() -> setStatus((mini ? "🏠 " : "🖼️ ") + label(e.model) + " ఫోటో గీస్తోంది… (ఒక నిమిషం వరకు)"));
            pic = Workshop.studio(e, jpeg, mini);
            Workshop.spend(this, pic.rupees, false);
            String ext = pic.mime.contains("jpeg") || pic.mime.contains("jpg") ? ".jpg" : pic.mime.contains("webp") ? ".webp" : ".png";
            made = Workshop.savePicture(this, Workshop.fileName(title, (mini ? "miniature-" : "studio-") + System.currentTimeMillis() % 1000000 + ext), pic.mime, pic.bytes);
            line = (mini ? "🏠 " : "🖼️ ") + "గ్యాలరీలో: " + made.where + " · సుమారు " + Workshop.money(pic.rupees);
            said = (mini ? "మినియేచర్" : "స్టూడియో") + " ఫోటో సిద్ధం Sir. గ్యాలరీలో దాచాను. సుమారు " + Workshop.money(pic.rupees) + " అయింది.";
        } catch (Workshop.Problem x) {
            line = x.getMessage();
            said = x.getMessage();
        } catch (Exception x) {
            String why = Workshop.why(p, x, Workshop.provider(p.workshopImageModel().trim()));
            line = "✗ ఫోటో రాలేదు: " + why;
            said = "ఫోటో రాలేదు Sir: " + why;
        }
        final String l0 = line, s0 = said;
        final Workshop.Picture pc = pic;
        final Coder.Made md = made;
        ui.post(() -> {
            if (destroyed) return;
            setStatus(l0);
            speak(s0, true);
            if (pc != null && md != null) showPicture(pc.bytes, md);
        });
    }

    /** The picture he got, on the screen: 📤 to share (his tap, his choice of app), or close. */
    private void showPicture(byte[] bytes, Coder.Made made) {
        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bm == null) return;
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bm);
        iv.setAdjustViewBounds(true);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        AlertDialog.Builder b = new AlertDialog.Builder(this).setView(iv).setPositiveButton("సరే", null);
        if (made.uri != null) b.setNeutralButton("📤 పంచుకో", (d, w) -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType(made.mime).putExtra(Intent.EXTRA_STREAM, made.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(s, "పంచుకోండి")); } catch (Exception ignored) {}
        });
        b.show();
    }

    /** 📷 A photo of a thing, or of a floor plan: from the camera or the gallery. */
    private void photoChoice() {
        String[] items = {"📷 వస్తువు ఫోటో తీయి", "🖼️ వస్తువు ఫోటో గ్యాలరీ నుంచి", "🏠 ఇంటి ప్లాన్ ఫోటో తీయి", "🏠 ఇంటి ప్లాన్ గ్యాలరీ నుంచి"};
        new AlertDialog.Builder(this).setTitle("📷 ఫోటో నుంచి 3D")
                .setItems(items, (d, w) -> { photoKind = w >= 2 ? Workshop.PLAN : Workshop.PHOTO; if (w % 2 == 0) openCam(); else openGallery(); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void pickPhoto(int kind) {
        photoKind = kind;
        new AlertDialog.Builder(this).setTitle(kind == Workshop.PLAN ? "🏠 ఇంటి ప్లాన్" : "📷 ఫోటో నుంచి 3D")
                .setItems(new String[]{"📷 ఫోటో తీయి", "🖼️ గ్యాలరీ నుంచి"}, (d, w) -> { if (w == 0) openCam(); else openGallery(); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void openCam() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        File f = PhotoProvider.file(this);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
        Uri out = PhotoProvider.uri();
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(android.content.ClipData.newRawUri("photo", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i, REQ_CAM); }
        catch (Exception e) { Toast.makeText(this, "కెమెరా యాప్ తెరవలేకపోయాను. గ్యాలరీ నుంచి ఎంచుకోండి.", Toast.LENGTH_LONG).show(); }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "ఫోటో ఎంచుకోండి"), REQ_GAL); }
        catch (Exception e) { Toast.makeText(this, "గ్యాలరీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAM_PERM && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) openCam();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        Uri u = req == REQ_CAM ? PhotoProvider.uri() : req == REQ_GAL && data != null ? data.getData() : null;
        if (u == null) return;
        final int kind = photoKind;
        new Thread(() -> {
            android.graphics.Bitmap bm = FacePhotos.upright(this, u, 1280);
            if (bm == null) { ui.post(() -> setStatus("ఫోటో తెరవలేకపోయాను Sir.")); return; }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            final String b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            final android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(bm, Math.max(1, bm.getWidth() * 480 / Math.max(bm.getWidth(), bm.getHeight())),
                    Math.max(1, bm.getHeight() * 480 / Math.max(bm.getWidth(), bm.getHeight())), true);
            if (req == REQ_CAM) { //noinspection ResultOfMethodCallIgnored
                PhotoProvider.file(this).delete(); } // (his photo is not kept here)
            ui.post(() -> { if (!destroyed) photoReady(b64, thumb, kind); });
        }, "jarvis-workshop-photo").start();
    }

    /** The photo, his words (optional), and his tap to build: the photo goes to his AI with the request, and is not kept. */
    private void photoReady(String b64, android.graphics.Bitmap thumb, int kind) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(0));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(dp(220));
        box.addView(iv);
        EditText words = new EditText(this);
        words.setHint(kind == Workshop.PLAN ? "ఏమైనా చెప్పాలా? ఉదా: రెండు అంతస్తులు (ఖాళీ అయినా సరే)" : "ఏమైనా చెప్పాలా? ఉదా: ఈ కుర్చీ, నీలం రంగులో (ఖాళీ అయినా సరే)");
        box.addView(words);
        TextView note = Ui.text(this, "ఈ ఫోటో మీరు ఎంచుకున్న AI కి ఈ ఒక్క పనికే వెళ్తుంది; ఫోన్‌లో దాచను. మనుషుల ముఖాలు 3D చేయను. ఖర్చు సుమారు ₹15–80.", 12.5f, Ui.MUTED);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle(kind == Workshop.PLAN ? "🏠 ఇంటి ప్లాన్ నుంచి 3D ఇల్లు" : "📷 ఫోటో నుంచి 3D")
                .setView(box)
                .setPositiveButton("🔨 తయారు చెయ్", (d, w) -> {
                    String said = words.getText().toString().trim();
                    String what = kind == Workshop.PLAN ? "ఇంటి ప్లాన్ నుంచి 3D ఇల్లు" + (said.isEmpty() ? "" : ": " + said) : said.isEmpty() ? "ఫోటోలోని వస్తువు" : said;
                    start(what, said, false, b64, kind);
                })
                .setNegativeButton("వద్దు", null).show();
    }

    /** 🔄 Across (landscape) or upright, remembered for next time. */
    private void turnScreen(boolean across) {
        landscape = across;
        getSharedPreferences(Workshop.PREFS, MODE_PRIVATE).edit().putBoolean("landscape", across).apply();
        setRequestedOrientation(across ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        fit(c);
    }

    private void fit(android.content.res.Configuration c) {
        boolean wide = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // across: the stage keeps most of the height (the part list folds away; a row of it still opens its part)
        if (listScroll != null) listScroll.setVisibility(wide ? View.GONE : View.VISIBLE);
        if (status != null) status.setMaxLines(wide ? 2 : 6);
        if (answerBody != null) answerBody.setMaxLines(wide ? 6 : 40);
    }

    /** Opens the workshop (from ⚙️, a tool, the brain): a build to start, or the last saved one. */
    static void open(Context c, String what, String heard, boolean saidAlready) {
        Intent i = new Intent(c, WorkshopActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null && !what.trim().isEmpty()) i.putExtra(EXTRA_WHAT, what.trim()).putExtra(EXTRA_HEARD, heard).putExtra(EXTRA_SAID, saidAlready);
        else i.putExtra(EXTRA_OPEN_LAST, true);
        c.startActivity(i);
    }
}
