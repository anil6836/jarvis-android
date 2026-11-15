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
    // (A5) 🏷️ names on/off; 🗑️ what is in the bin (from the stage)
    private TextView labelBtn, binBtn;
    private boolean labelsOn = true;
    private int binCount;
    private final List<String[]> binItems = new ArrayList<>(); // {id, label, name, whole}
    private android.os.Vibrator vib;
    // (E) 🎓 the how-it-works show; (F) 🛠️ the build guide; (G) 📷 the model in his room
    private TextView tourBtn, tourStopBtn, tourNextBtn, guideBtn, arBtn;
    /** The show playing now (null = none), the step it is on, when that step began and how long it stays at least. */
    private JSONArray tourSteps;
    private int tourIdx;
    private long tourStepAt, tourMin, tourQuietAt;
    private boolean tourVoice;
    /** (a show just made) what it cost, said at its end. */
    private String tourCost;
    /** The stage's description asked for a show / a guide: its one-time id and the job waiting for it. */
    private String descReq;
    private Job descJob;
    private FrameLayout rootView;
    private CamView cam;
    private boolean arOn;
    private android.hardware.display.DisplayManager.DisplayListener turnL;
    private static final int REQ_AR_PERM = 64;
    // (1–9) 🎨 colours, 📏 tape, ⚖️ weight, 📐 drawing, 🎞️ show video, 🖼️ pictures, 🔀 second model, ✋ hand signs, 📏 real size
    private TextView paintBtn, tapeBtn, weightBtn, drawBtn, videoBtn, guestBtn, guestTopBtn, guestOffBtn, handsBtn, realBtn;
    private boolean tapeOn, guestTop, recordNext, recording, massVoice, drawVoice, paintVoice, handsOn, realOn;
    private long recordAt;
    private String guestTitle, massReq, drawReq, drawDesc, drawViews, paintReq, thumbReq;
    private long thumbAt;
    private Workshop.PaintSay paintAsk;

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
        /** (A5) pieces he emptied from the bin for good: {part id, piece name}; they stay hidden on this model. */
        final List<String[]> gone = new ArrayList<>();
        /** (E, F) the 🎓 show (checked JSON) and the 🛠️ build guide made for this model, and at which change (-1: none). */
        String tour, guide;
        int tourAt = -1, guideAt = -1;
        /** (1) 🎨 his own colours / materials: {part id, piece name ("" = the whole part), kind, colour}; the newest last. */
        final List<String[]> paints = new ArrayList<>();

        Shown copy() {
            Shown c = new Shown();
            c.at = at; c.title = title; c.what = what; c.model = model; c.say = say; c.cost = cost; c.estimated = estimated;
            c.changes = changes; c.repairs = repairs; c.seconds = seconds; c.firstPart = firstPart;
            c.tour = tour; c.tourAt = tourAt; c.guide = guide; c.guideAt = guideAt;
            for (String[] x : paints) c.paints.add(x.clone());
            for (Map.Entry<String, String[]> e : parts.entrySet()) c.parts.put(e.getKey(), e.getValue().clone());
            for (String[] g : gone) c.gone.add(g.clone());
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
            JSONArray gs = new JSONArray();
            for (String[] g : gone) gs.put(new JSONObject().put("id", g[0]).put("label", g[1]));
            return new JSONObject().put("at", at).put("title", title).put("what", what == null ? "" : what).put("model", model == null ? "" : model)
                    .put("parts", ps).put("say", say == null ? "" : say).put("cost", cost).put("estimated", estimated).put("repairs", repairs)
                    .put("changes", changes).put("seconds", seconds).put("first_part_seconds", firstPart).put("gone", gs)
                    .put("tour", tour == null ? "" : tour).put("tour_at", tourAt).put("guide", guide == null ? "" : guide).put("guide_at", guideAt)
                    .put("paints", paintsJson());
        }

        JSONArray paintsJson() {
            JSONArray l = new JSONArray();
            try { for (String[] x : paints) l.put(new JSONObject().put("id", x[0]).put("label", x[1]).put("kind", x[2]).put("color", x[3])); } catch (Exception ignored) {}
            return l;
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
            JSONArray gs = b.optJSONArray("gone");
            for (int i = 0; gs != null && i < gs.length(); i++) {
                JSONObject g = gs.optJSONObject(i);
                if (g != null && s.parts.containsKey(g.optString("id"))) s.gone.add(new String[]{g.optString("id"), g.optString("label")});
            }
            String t = b.optString("tour", ""), gd = b.optString("guide", "");
            s.tour = t.isEmpty() ? null : t;
            s.tourAt = s.tour == null ? -1 : b.optInt("tour_at", -1);
            s.guide = gd.isEmpty() ? null : gd;
            s.guideAt = s.guide == null ? -1 : b.optInt("guide_at", -1);
            JSONArray pl = b.optJSONArray("paints");
            for (int i = 0; pl != null && i < pl.length() && i < 300; i++) {
                JSONObject x = pl.optJSONObject(i);
                if (x != null && s.parts.containsKey(x.optString("id")))
                    s.paints.add(new String[]{x.optString("id"), x.optString("label"), x.optString("kind"), x.optString("color")});
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
        /** (E, F) "tour" / "guide" (null: a build or a change); asked by voice; no "thinking" lines spoken while it waits. */
        String kind;
        boolean byVoice, quiet;
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
        rootView = root;
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
        labelBtn = pill("🏷️ పేర్లు దాచు", v -> command(labelsOn ? "labels_off" : "labels_on", false));
        binBtn = pill("🗑️ బిన్", v -> binTray());
        tourBtn = pill("🎓 ఎలా పనిచేస్తుంది", v -> command("show_how", false));
        tourStopBtn = pill("⏹ షో ఆపు", v -> command("show_stop", false));
        tourNextBtn = pill("⏭ తర్వాత", v -> command("show_next", false));
        guideBtn = pill("🛠️ తయారీ విధానం", v -> command("guide", false));
        arBtn = pill("📷 గదిలో చూడు", v -> command(arOn ? "ar_off" : "ar_on", false));
        paintBtn = pill("🎨 రంగు", v -> paintCard());
        tapeBtn = pill("📏 టేప్", v -> command(tapeOn ? "tape_off" : "tape_on", false));
        weightBtn = pill("⚖️ బరువు", v -> command("weight", false));
        drawBtn = pill("📐 డ్రాయింగ్", v -> command("drawing", false));
        videoBtn = pill("🎞️ షో వీడియో", v -> command("show_video", false));
        guestBtn = pill("🔀 కలిపి చూడు", v -> command("guest_add", false));
        guestTopBtn = pill("🔝 పైన పెట్టు", v -> command(guestTop ? "guest_side" : "guest_top", false));
        guestOffBtn = pill("✕ రెండో బొమ్మ", v -> command("guest_off", false));
        handsBtn = pill("✋ సైగలు", v -> command(handsOn ? "hands_off" : "hands_on", false));
        realBtn = pill("📏 నిజమైన సైజు", v -> command(realOn ? "real_off" : "real_on", false));
        actions.addView(cancelBtn);
        actions.addView(stopBtn);
        actions.addView(tourStopBtn);
        actions.addView(tourNextBtn);
        actions.addView(explodeBtn);
        actions.addView(undoBtn);
        actions.addView(tourBtn);
        actions.addView(guideBtn);
        actions.addView(arBtn);
        actions.addView(realBtn);
        actions.addView(handsBtn);
        actions.addView(guestTopBtn);
        actions.addView(guestOffBtn);
        actions.addView(binBtn);
        actions.addView(labelBtn);
        actions.addView(isoBtn);
        actions.addView(paintBtn);
        actions.addView(hideBtn);
        actions.addView(allBtn);
        actions.addView(xrayBtn);
        actions.addView(cutBtn);
        actions.addView(sizeBtn);
        actions.addView(tapeBtn);
        actions.addView(weightBtn);
        actions.addView(drawBtn);
        actions.addView(videoBtn);
        actions.addView(guestBtn);
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
            labelBtn.setVisibility(has && (exploded || isolatedOn) ? View.VISIBLE : View.GONE);
            labelBtn.setText(labelsOn ? "🏷️ పేర్లు దాచు" : "🏷️ పేర్లు చూపించు");
            binBtn.setVisibility(has && binCount > 0 ? View.VISIBLE : View.GONE);
            binBtn.setText("🗑️ బిన్ " + binCount);
        }
        if (tourBtn != null) { // (E, F, G)
            boolean playing = tourSteps != null;
            tourBtn.setVisibility(has && !playing ? View.VISIBLE : View.GONE);
            tourStopBtn.setVisibility(playing ? View.VISIBLE : View.GONE);
            tourNextBtn.setVisibility(playing ? View.VISIBLE : View.GONE);
            guideBtn.setVisibility(has ? View.VISIBLE : View.GONE);
            arBtn.setVisibility(has || arOn ? View.VISIBLE : View.GONE);
            arBtn.setText(arOn ? "📷 గది ఆపు" : "📷 గదిలో చూడు");
        }
        if (paintBtn != null) { // (1–9)
            paintBtn.setVisibility(has && pickedId != null && !tapeOn ? View.VISIBLE : View.GONE);
            paintBtn.setText("🎨 " + (pickedName != null ? Workshop.cap(pickedName, 14) + " " : "") + "రంగు");
            for (TextView t : new TextView[]{tapeBtn, weightBtn, drawBtn}) t.setVisibility(has ? View.VISIBLE : View.GONE);
            tapeBtn.setText(tapeOn ? "📏 టేప్ ఆపు" : "📏 టేప్");
            videoBtn.setVisibility(has && tourSteps == null && !arOn && !realOn ? View.VISIBLE : View.GONE);
            guestBtn.setVisibility(has && guestTitle == null && job == null && !realOn ? View.VISIBLE : View.GONE);
            guestTopBtn.setVisibility(guestTitle != null ? View.VISIBLE : View.GONE);
            guestTopBtn.setText(guestTop ? "↔️ పక్కన పెట్టు" : "🔝 పైన పెట్టు");
            guestOffBtn.setVisibility(guestTitle != null ? View.VISIBLE : View.GONE);
            handsBtn.setVisibility(handsOn ? View.VISIBLE : View.GONE); // (✋ / 📏 real size: the next update)
            handsBtn.setText(handsOn ? "✋ సైగలు ఆపు" : "✋ సైగలు");
            realBtn.setVisibility(realOn ? View.VISIBLE : View.GONE);
            realBtn.setText(realOn ? "📏 నిజమైన సైజు ఆపు" : "📏 నిజమైన సైజు");
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
        if (tourSteps != null && Workshop.isCancel(text)) { stopTourSaid(byVoice); return; } // (🎓 "ఆపు" during the show: the show stops, nothing is asked)
        String cmd = Workshop.command(text);
        if (cmd != null) { command(cmd, byVoice); return; }
        if (tourSteps != null) endTour(false); // (a question or a change: the show makes way)
        if (job == null && shown != null && !shown.parts.isEmpty()) { // 🎨 "నీలం చెయ్": his own colour, ₹0, no AI
            Workshop.PaintSay ps = Workshop.paintWords(text);
            if (ps != null) { paintSaid(ps, byVoice); return; }
        }
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
        if (tourSteps != null && Workshop.isCancel(words)) { stopTourSaid(true); return true; }
        if (readState == READ_PLAYING && Workshop.isCancel(words)) { readPause(); return true; } // (🔊 "ఆపు" while the guide is read: ⏸)
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
        if (tourSteps != null && !TOUR_KEEPS.contains(cmd)) endTour(false); // (🎓 anything else he asks: the show makes way)
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
                if ("photo".equals(cmd) && recording) { said = "వీడియో అవుతోంది Sir, అయ్యాక ఫోటో తీయండి."; break; }
                if ("photo".equals(cmd) && (arOn || realOn)) { arPhoto(); break; } // (📷 in the room: the room and the model, as he sees them)
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
            // ---- (A5) the names, the bin
            case "labels_off": case "labels_on":
                labelsOn = "labels_on".equals(cmd);
                try { send(json("labels").put("on", labelsOn)); } catch (Exception ignored) {}
                said = labelsOn ? "పేర్లు చూపిస్తున్నాను Sir." : "పేర్లు దాచాను Sir.";
                break;
            case "bin":
                if (!has) break;
                if (pickedId == null) { said = "ఏది బిన్‌లో వేయాలో ముందు దాన్ని తాకండి Sir, లేదా దాని మీద వేలు అర సెకను నొక్కి 🗑️ వైపు లాగండి."; break; }
                send(json("bin"));
                break;
            case "bin_others":
                if (!has) break;
                if (pickedId == null) { said = "ఉంచుకోవాల్సిన భాగాన్ని ముందు తాకండి Sir, తర్వాత \"మిగతావి బిన్‌లో వేయి\" అనండి."; break; }
                send(json("binothers"));
                said = (pickedName != null ? pickedName : "ఆ భాగం") + " తప్ప మిగతావి బిన్‌లో వేస్తున్నాను Sir.";
                break;
            case "bin_open": binTray(); break;
            case "unbin_all":
                if (binCount == 0) { said = "బిన్ ఖాళీగా ఉంది Sir."; break; }
                try { send(json("unbin").put("all", true)); } catch (Exception ignored) {}
                said = "అన్నీ వాటి చోటికి వస్తున్నాయి Sir.";
                break;
            case "bin_empty": emptyBin(); break;
            // ---- (E, F, G) 🎓 the show, 🛠️ the build guide, 📷 the room
            case "show_how":
                if (!has) { said = "ముందు ఒక బొమ్మ తయారు చేయనివ్వండి Sir."; break; }
                recordNext = false; // (only 🎞️ records)
                tourAsk(byVoice);
                break;
            case "show_stop":
                if (tourSteps == null) break;
                endTour(false);
                said = "షో ఆపాను Sir.";
                break;
            case "show_next":
                if (tourSteps != null) tourAdvance();
                break;
            case "guide":
                if (!has) { said = "ముందు ఒక బొమ్మ తయారు చేయనివ్వండి Sir."; break; }
                guideAsk(byVoice);
                break;
            case "ar_on": case "ar_off":
                said = ar("ar_on".equals(cmd));
                break;
            // ---- (1–9)
            case "tape_on": case "tape_off":
                if (!has && "tape_on".equals(cmd)) break;
                tapeOn = "tape_on".equals(cmd);
                try { send(json("tape").put("on", tapeOn)); } catch (Exception ignored) {}
                if (tapeOn) { pickedId = null; pickedName = null; closeAnswer(); }
                said = tapeOn ? "📏 టేప్: బొమ్మ మీద మొదటి చోటు తాకండి Sir." : "📏 టేప్ ఆపాను.";
                break;
            case "weight":
                if (!has) break;
                massReq = "m" + Long.toHexString(System.nanoTime());
                massVoice = byVoice;
                try { send(json("mass").put("req", massReq)); } catch (Exception ignored) {}
                setStatus("⚖️ లెక్కపెడుతోంది…");
                break;
            case "drawing":
                if (!has) break;
                drawReq = "d" + Long.toHexString(System.nanoTime());
                drawDesc = null;
                drawViews = null;
                drawVoice = byVoice;
                try { send(json("describe").put("req", drawReq)); send(json("ortho").put("req", drawReq)); } catch (Exception ignored) {}
                setStatus("📐 డ్రాయింగ్ గీస్తోంది… (ఖర్చు లేదు)");
                break;
            case "show_video":
                if (!has) break;
                if (arOn || realOn) { said = "గదిలో చూస్తున్నప్పుడు వీడియో రాదు Sir. ముందు గది ఆపండి."; break; }
                recordNext = true;
                recordAt = System.currentTimeMillis();
                tourAsk(byVoice); // (free when the show is ready for this model; else its cost is said first)
                break;
            case "guest_add":
                if (!has) break;
                if (realOn) { said = "నిజమైన సైజు చూస్తున్నప్పుడు కుదరదు Sir. ముందు అది ఆపండి."; break; }
                if (job != null) { said = "ఒక పని ఇంకా జరుగుతోంది Sir."; break; }
                gallery(true);
                break;
            case "guest_top": case "guest_side":
                if (guestTitle == null) { said = "ముందు 🔀 కలిపి చూడు తో రెండో బొమ్మ పెట్టండి Sir."; break; }
                guestTop = "guest_top".equals(cmd);
                try { send(json("guestpos").put("where", guestTop ? "top" : "side")); } catch (Exception ignored) {}
                said = guestTop ? "🔝 " + guestTitle + " ని దాని మీద పెట్టాను." : "↔️ " + guestTitle + " ని పక్కన పెట్టాను.";
                break;
            case "guest_off":
                if (guestTitle == null) break;
                send(json("guestoff"));
                said = "✕ " + guestTitle + " తీసేశాను.";
                guestTitle = null;
                break;
            case "hands_on": case "hands_off":
                said = hands("hands_on".equals(cmd));
                break;
            case "real_on": case "real_off":
                said = real("real_on".equals(cmd));
                break;
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
        thumbLater();
        return "↩️ ముందులా చేశాను Sir (ఖర్చు లేదు).";
    }

    /** The whole model onto a cleared stage (a saved build, an undo): no AI. */
    private void putOnStage(Shown sh) {
        if (tourSteps != null) endTour(false);
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
        sendGone(sh);
        sendPaints(sh);
        titleView.setText(sh.title);
        if (sh.model != null && !sh.model.isEmpty()) chip.setText(label(sh.model));
        refreshButtons();
    }

    /** The pieces he took off for good stay hidden on the stage. */
    private void sendGone(Shown sh) {
        try {
            JSONArray l = new JSONArray();
            if (sh != null) for (String[] g : sh.gone) l.put(new JSONObject().put("id", g[0]).put("label", g[1]));
            send(json("gone").put("list", l));
        } catch (Exception ignored) {}
    }

    private void save(Shown sh) {
        try { Workshop.save(this, sh.json()); } catch (Exception ignored) {}
    }

    /** 🎨 His colours onto the stage (all of them; the stage dresses each part as it is made). */
    private void sendPaints(Shown sh) {
        try { send(json("paint").put("list", sh == null ? new JSONArray() : sh.paintsJson())); } catch (Exception ignored) {}
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
        w.setBackgroundColor(arOn ? android.graphics.Color.TRANSPARENT : 0xFF07080B); // (📷 the room view: the camera shows through)
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
        if ("grab".equals(t) && "guest".equals(id)) { feel("grab", 18); setStatus("✋ " + guestTitle + " · వదిలిన చోట ఉంటుంది"); return; }
        if ("moved".equals(t) && "guest".equals(id)) { guestTop = false; setStatus("✋ " + guestTitle + " అక్కడే పెట్టాను · 🔝 పైన పెట్టు / ↔️ పక్కన పెట్టు"); refreshButtons(); return; }
        if ("grab".equals(t)) { // ✋ a piece in his hand
            feel("grab", 18);
            setStatus("✋ " + m.optString("label") + " · 🗑️ మీదకి లాగి వదిలితే బిన్‌లో; వేరే చోట వదిలితే అక్కడే" + (exploded ? "; దాని చోటికి తెస్తే అతుక్కుంటుంది" : ""));
            return;
        }
        if ("moved".equals(t)) { setStatus("✋ " + m.optString("label") + " అక్కడే పెట్టాను · \"కలుపు\" అంటే అన్నీ వాటి చోటికి"); return; }
        if ("snap".equals(t)) { // 🧩 back in its place by his hand
            String[] dn = m.optString("label", "0/0").split("/");
            boolean all = dn.length == 2 && dn[0].equals(dn[1]);
            feel(all ? "done" : "snap", all ? 60 : 30);
            String nm = nameOf(id);
            setStatus(all ? "🎉 " + m.optString("label") + " · మొత్తం బొమ్మ మీరే అమర్చారు Sir!" : "🧩 " + (nm != null ? nm + " అతుక్కుంది · " : "") + m.optString("label") + " అమర్చారు");
            if (all) speak("మొత్తం బొమ్మ మీరే అమర్చారు Sir!", true);
            return;
        }
        if ("bin".equals(t)) { // 🗑️ what is in the bin now
            if ("nothing-picked".equals(m.optString("label"))) { setStatus("ఉంచుకోవాల్సిన భాగాన్ని ముందు తాకండి Sir."); return; }
            JSONArray l = m.optJSONArray("list");
            int before = binCount;
            binItems.clear();
            for (int i = 0; l != null && i < l.length(); i++) {
                JSONObject b = l.optJSONObject(i);
                if (b != null) binItems.add(new String[]{b.optString("id"), b.optString("label"), b.optString("name", b.optString("label")), String.valueOf(b.optBoolean("whole"))});
            }
            binCount = binItems.size();
            String last = m.optString("label");
            if (binCount > before) { feel("bin", 22); setStatus("🗑️ " + (last.isEmpty() ? "" : last + " ") + "బిన్‌లో వేశాను · బిన్‌లో " + binCount + " · 🗑️ నొక్కితే తిరిగి తీసుకోవచ్చు"); }
            else if (binCount < before) { feel("back", 15); setStatus(binCount == 0 ? "↩️ అన్నీ వాటి చోటికి వచ్చాయి" : "↩️ తిరిగి తెచ్చాను · బిన్‌లో ఇంకా " + binCount); }
            refreshButtons();
            return;
        }
        if ("bintap".equals(t)) { binTray(); return; }
        if ("painthit".equals(t)) { // 🎨 the part his words named
            Workshop.PaintSay ps = paintAsk;
            if (ps == null || paintReq == null || !paintReq.equals(m.optString("req"))) return;
            paintAsk = null;
            paintReq = null;
            if (id.isEmpty() || shown == null || !shown.parts.containsKey(id)) {
                String s0 = "ఏ భాగమో తెలియలేదు Sir. ఆ భాగాన్ని తాకి మళ్ళీ చెప్పండి (ఖర్చు లేదు).";
                setStatus(s0);
                speak(s0, paintVoice);
                return;
            }
            String lb = m.optString("label");
            paint(id, lb.isEmpty() ? shown.parts.get(id)[0] : lb, ps.kind, ps.color, paintVoice);
            return;
        }
        if ("tape".equals(t)) { // 📏
            if (m.optInt("n") < 2) { setStatus("📏 ఇప్పుడు రెండో చోటు తాకండి"); return; }
            double d = m.optDouble("d", 0);
            String len = d >= 100 ? String.format(java.util.Locale.ENGLISH, "%.2f", d / 100) + " మీటర్లు" : cm(d) + " సెం.మీ";
            setStatus("📏 ఆ రెండు చోట్ల మధ్య " + len + " (AI డిజైన్ కొలత) · మళ్ళీ తాకితే కొత్త కొలత");
            speak(len.replace("సెం.మీ", "సెంటీమీటర్లు") + " Sir.", false);
            feel("snap", 15);
            return;
        }
        if ("mass".equals(t)) { // ⚖️
            if (massReq == null || !massReq.equals(m.optString("req"))) return;
            massReq = null;
            String[] card = Workshop.massCard(m.optString("data"), shown != null ? shown.title : "");
            if (card == null) { setStatus("⚖️ బరువు లెక్కపెట్టలేకపోయాను Sir."); return; }
            setStatus("⚖️ " + card[2]);
            textCard(card[0], card[1]);
            if (massVoice) speak(card[2], true);
            return;
        }
        if ("ortho".equals(t)) { // 📐 the three views
            if (drawReq == null || !drawReq.equals(m.optString("req"))) return;
            drawViews = m.optString("data", "");
            maybeDraw();
            return;
        }
        if ("guest".equals(t)) { // 🔀 the second model is on the stage
            if (guestTitle == null) return;
            if (m.optInt("n") <= 0) { setStatus("🔀 ఆ బొమ్మ రాలేదు Sir."); guestTitle = null; refreshButtons(); return; }
            setStatus("🔀 " + guestTitle + " పక్కన పెట్టాను · వేలు అర సెకను నొక్కి జరపండి · 🔝 పైన పెట్టు · ఖర్చు లేదు");
            return;
        }
        if ("xrtap".equals(t)) { realTap(m.optDouble("x", -1), m.optDouble("y", -1)); return; }
        if ("desc".equals(t) && drawReq != null && drawReq.equals(m.optString("req"))) { drawDesc = m.optString("data", "[]"); maybeDraw(); return; } // (📐 the parts list)
        if ("desc".equals(t)) { // (E, F) the model's description, asked for a show / a guide: now the AI writes it
            Job j = descJob;
            if (j == null || descReq == null || !descReq.equals(m.optString("req")) || job != j) return;
            descJob = null;
            descReq = null;
            final String model = m.optString("data", "[]");
            new Thread(() -> runMake(j, model), "jarvis-workshop-" + j.kind).start();
            return;
        }
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
        if (tourSteps != null) endTour(false);
        if (descJob != null) { Job dj = descJob; descJob = null; descReq = null; failMake(dj, "3D స్టేజ్ మళ్ళీ మొదలైంది, మళ్ళీ అడగండి"); }
        newStage();
        send(json("reset"));
        if (arOn) { try { send(json("ar").put("on", true)); } catch (Exception ignored) {} }
        try { if (sentTop >= 0) send(json("inset").put("top", sentTop).put("bottom", sentBottom)); } catch (Exception ignored) {}
        for (Map.Entry<String, String> e : new ArrayList<>(onStage.entrySet())) sendPart(e.getKey(), e.getValue());
        try {
            if (exploded) send(json("explode").put("on", true).put("turn", false));
            if (night) send(json("light").put("mode", "night"));
            if (!spinning) send(json("spin").put("on", false));
        } catch (Exception ignored) {}
        if (pickedId != null) glowPicked();
        if (shown != null && !shown.gone.isEmpty()) sendGone(shown);
        if (shown != null && !shown.paints.isEmpty()) sendPaints(shown);
        tapeOn = false;
        guestTitle = null;
        if (!labelsOn) { try { send(json("labels").put("on", false)); } catch (Exception ignored) {} }
        binCount = 0;
        binItems.clear();
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
        if (tourSteps != null) endTour(false);
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
                    if (!j.quiet && secs >= j.nextLine) { // (one short line about every ten seconds: thinking never looks like a hang)
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
            Workshop.stream(j.engine, Workshop.SYSTEM, Workshop.changeMessage(before.title, before.list(), j.talk, j.pickedId, j.pickedName, j.detail)
                    + Workshop.goneNote(before.gone), Workshop.CHANGE_OUT,
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
            boolean looks = Workshop.mentionsLook(j.heard);
            after.paints.removeIf(x -> !after.parts.containsKey(x[0]) || (looks && all.stream().anyMatch(pt -> pt.ok && pt.id.equals(x[0]))));
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
                sendPaints(shown);
                thumbLater();
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
            if (result != null) { shown = result; thumbLater(); }
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
        boolean other = j.kind != null; // (🎓 a show / 🛠️ a guide being written)
        new AlertDialog.Builder(this).setTitle(!other ? "బొమ్మ తయారీ ఆపాలా?" : "tour".equals(j.kind) ? "షో తయారీ ఆపాలా?" : "తయారీ విధానం రాయడం ఆపాలా?")
                .setMessage(other ? "ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది." : "ఇప్పటివరకు వచ్చిన భాగాలు ఉంటాయి. ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది.")
                .setPositiveButton("⏹ ఆపు", (d, w) -> stopJob(j)).setNegativeButton("కొనసాగించు", null).show();
    }

    private void stopJob(Job j) {
        j.stopped = true;
        j.cancel.cancel();
        synchronized (acks) { acks.notifyAll(); }
        if (descJob == j) { descJob = null; descReq = null; ui.post(() -> failMake(j, null)); } // (stopped before the AI was asked: ₹0)
    }

    private void leave() {
        Job j = job;
        if (j == null) { finish(); return; }
        new AlertDialog.Builder(this).setTitle(j.kind == null ? "బొమ్మ ఇంకా తయారవుతోంది" : "tour".equals(j.kind) ? "షో ఇంకా తయారవుతోంది" : "తయారీ విధానం ఇంకా రాస్తోంది")
                .setMessage("మూసేస్తే అది ఆగిపోతుంది (ఇప్పటిదాకా అయిన ఖర్చు పడుతుంది). మూసేయాలా?")
                .setPositiveButton("మూసేయి", (d, w) -> { stopJob(j); finish(); }).setNegativeButton("ఉండు", null).show();
    }

    @Override public void onBackPressed() { leave(); }

    // ================================================================ saved builds (no AI, no money)

    private void show(JSONObject b) {
        if (b.optJSONArray("parts") == null) return;
        shown = Shown.of(b);
        undo.clear();
        putOnStage(shown);
        if (!Workshop.thumb(this, shown.at).isFile()) { ui.removeCallbacks(thumbR); ui.postDelayed(thumbR, 4500); } // (🖼️ an older save gets its picture)
        setStatus("📂 దాచిన బొమ్మ (మళ్ళీ తెరవడానికి ఖర్చు లేదు) · తయారైనప్పుడు " + Workshop.money(b.optDouble("cost", 0))
                + (b.optBoolean("estimated") ? " (సుమారు)" : ""));
    }

    private void savedList() { gallery(false); }

    /** 📂 The saved models as small pictures (₹0): a tap opens one, or (🔀) puts it beside the one on the stage. */
    private void gallery(boolean asGuest) {
        if (job != null) { Toast.makeText(this, "ముందు ఈ బొమ్మ పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        List<File> fs = Workshop.files(this);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int per = wide ? 4 : 2, n = 0;
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout row = null;
        for (File x : fs) {
            JSONObject b = Workshop.read(x);
            if (b == null || b.optJSONArray("parts") == null) continue;
            if (asGuest && shown != null && b.optLong("at") == shown.at) continue; // (not itself)
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
            File th = Workshop.thumb(this, b.optLong("at"));
            if (th.isFile()) { try { bm = android.graphics.BitmapFactory.decodeFile(th.getPath()); } catch (Throwable ignored) {} }
            if (bm != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(bg);
                iv.setClipToOutline(true);
                pic = iv;
            } else {
                TextView ph = Ui.text(this, "🧊", 34f, Ui.MUTED);
                ph.setGravity(Gravity.CENTER);
                ph.setBackground(bg);
                pic = ph;
            }
            tile.addView(pic, new LinearLayout.LayoutParams(-1, dp(wide ? 96 : 124)));
            TextView tt = Ui.text(this, b.optString("title"), 13.5f, Ui.TEXT);
            tt.setMaxLines(2);
            tt.setPadding(dp(2), dp(4), dp(2), 0);
            tile.addView(tt);
            tile.addView(Ui.text(this, f.format(new java.util.Date(b.optLong("at"))), 11f, Ui.MUTED));
            tile.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (asGuest) addGuest(b); else show(b);
            });
            row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (n == 0) { Toast.makeText(this, asGuest ? "పక్కన పెట్టడానికి ఇంకో దాచిన బొమ్మ లేదు" : "ఇంకా దాచిన బొమ్మలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        while (row != null && row.getChildCount() < per) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f)); // (the last row keeps the tiles' width)
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = new AlertDialog.Builder(this).setTitle(asGuest ? "🔀 ఏ బొమ్మని పక్కన పెట్టాలి? (ఖర్చు లేదు)" : "📂 దాచిన బొమ్మలు (తెరవడానికి ఖర్చు లేదు)")
                .setView(sv).setNegativeButton("వద్దు", null).show();
    }

    /** 🔀 A saved model beside this one (₹0; to look at only: it is not added to this model). */
    private void addGuest(JSONObject b) {
        if (shown == null || shown.parts.isEmpty()) return;
        Shown g = Shown.of(b);
        try {
            JSONArray ps = new JSONArray(), gone = new JSONArray();
            for (Map.Entry<String, String[]> e : g.parts.entrySet()) ps.put(new JSONObject().put("id", e.getKey()).put("code", e.getValue()[1]));
            for (String[] x : g.gone) gone.put(new JSONObject().put("id", x[0]).put("label", x[1]));
            guestTitle = g.title == null || g.title.isEmpty() ? "రెండో బొమ్మ" : g.title;
            guestTop = false;
            send(json("guest").put("title", guestTitle).put("parts", ps).put("gone", gone).put("paints", g.paintsJson()));
            setStatus("🔀 " + guestTitle + " పెడుతున్నాను…");
        } catch (Exception ignored) {}
        refreshButtons();
    }

    // ================================================================ (1) 🎨 his own colours and materials (₹0, no AI)

    /** The 🎨 card for the tapped piece: colours, materials, its own colour back. */
    private void paintCard() {
        if (shown == null || pickedId == null) { setStatus("ముందు బొమ్మలో ఒక భాగం తాకండి Sir."); return; }
        final String id = pickedId, name = pickedName != null ? pickedName : pickedId;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        box.addView(Ui.text(this, "రంగు నొక్కితే వెంటనే మారుతుంది · ఖర్చు లేదు · ↩️ వెనక్కి తో ముందులా", 12.5f, Ui.MUTED));
        box.addView(Ui.text(this, "\nరంగు", 13f, Ui.TEXT));
        LinearLayout r = null;
        for (int i = 0; i < Workshop.SWATCHES.length; i++) {
            if (i % 7 == 0) { r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); box.addView(r); }
            final String[] sw = Workshop.SWATCHES[i];
            TextView dot = new TextView(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(android.graphics.Color.parseColor(sw[0]));
            g.setStroke(dp(1), 0x66FFFFFF);
            dot.setBackground(g);
            dot.setContentDescription(sw[1]);
            dot.setOnClickListener(v -> paint(id, name, "", sw[0], false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(dp(3), dp(4), dp(5), dp(4));
            r.addView(dot, lp);
        }
        box.addView(Ui.text(this, "\nపదార్థం", 13f, Ui.TEXT));
        LinearLayout r2 = null;
        for (int i = 0; i < Workshop.MATERIAL_BUTTONS.length; i++) {
            if (i % 3 == 0) {
                r2 = new LinearLayout(this);
                r2.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                box.addView(r2, lp);
            }
            final String[] mb = Workshop.MATERIAL_BUTTONS[i];
            r2.addView(pill(mb[1], v -> paint(id, name, mb[0], "", false)));
        }
        LinearLayout.LayoutParams back = new LinearLayout.LayoutParams(-2, -2);
        back.topMargin = dp(12);
        box.addView(pill("↩️ అసలు రంగు", v -> unpaint(id, name)), back);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("🎨 " + name).setView(sv).setPositiveButton("సరే", null).show();
    }

    /** "" for the whole part (its own name), else the piece's name. */
    private String paintLabel(String id, String label) {
        String[] pt = shown == null ? null : shown.parts.get(id);
        return label == null || label.isEmpty() || label.equals(id) || (pt != null && label.equals(pt[0])) ? "" : label;
    }

    private void paint(String id, String label, String kind, String color, boolean byVoice) {
        if (shown == null || job != null || !shown.parts.containsKey(id)) return;
        String lb = paintLabel(id, label), k = kind == null ? "" : kind, c = color == null ? "" : color;
        undo.addFirst(shown.copy());
        while (undo.size() > 5) undo.removeLast();
        Shown after = shown.copy();
        String[] was = null;
        for (java.util.Iterator<String[]> it = after.paints.iterator(); it.hasNext(); ) {
            String[] x = it.next();
            if (!x[0].equals(id)) continue;
            if (x[1].equals(lb)) { was = x; it.remove(); }
            else if (lb.isEmpty()) it.remove(); // (the whole part: its pieces' own colours make way)
        }
        if (was != null) { // (a colour after a material keeps the material, and the other way round; a metal shows its own colour)
            if (k.isEmpty()) k = was[2];
            if (c.isEmpty() && !(kind != null && kind.startsWith("metal "))) c = was[3];
        }
        after.paints.add(new String[]{id, lb, k, c});
        if (!k.equals(was == null ? "" : was[2])) after.changes++; // (another material: the guide is for the old one)
        shown = after;
        save(shown);
        sendPaints(shown);
        thumbLater();
        refreshButtons();
        String what = (c.isEmpty() ? "" : colourTe(c)) + (k.isEmpty() ? "" : (c.isEmpty() ? "" : " ") + Workshop.kindTe(k));
        setStatus("🎨 " + (label == null || label.isEmpty() ? nameOf(id) : label) + ": " + what + " · ఖర్చు ₹0 · ↩️ వెనక్కి తో ముందులా");
        if (byVoice) speak("మార్చాను Sir. ఖర్చు లేదు.", true);
    }

    private void unpaint(String id, String label) {
        if (shown == null || job != null) return;
        String lb = paintLabel(id, label);
        boolean any = false;
        for (String[] x : shown.paints) if (x[0].equals(id) && (lb.isEmpty() || x[1].equals(lb))) any = true;
        if (!any) { setStatus("🎨 దీనికి మీ రంగు లేదు, ఇది దాని అసలు రంగే Sir."); return; }
        undo.addFirst(shown.copy());
        while (undo.size() > 5) undo.removeLast();
        Shown after = shown.copy();
        after.paints.removeIf(x -> x[0].equals(id) && (lb.isEmpty() || x[1].equals(lb)));
        after.changes++;
        shown = after;
        save(shown);
        sendPaints(shown);
        thumbLater();
        refreshButtons();
        setStatus("↩️ అసలు రంగు · ఖర్చు ₹0");
    }

    /** "మొత్తం తెల్లగా చెయ్": every part, whole. */
    private void paintAll(String kind, String color, boolean byVoice) {
        if (shown == null || job != null || shown.parts.isEmpty()) return;
        undo.addFirst(shown.copy());
        while (undo.size() > 5) undo.removeLast();
        Shown after = shown.copy();
        after.paints.clear();
        for (String id : after.parts.keySet()) after.paints.add(new String[]{id, "", kind == null ? "" : kind, color == null ? "" : color});
        if (kind != null && !kind.isEmpty()) after.changes++;
        shown = after;
        save(shown);
        sendPaints(shown);
        thumbLater();
        refreshButtons();
        setStatus("🎨 మొత్తం బొమ్మ: " + (color == null || color.isEmpty() ? "" : colourTe(color) + " ") + Workshop.kindTe(kind) + " · ఖర్చు ₹0 · ↩️ వెనక్కి తో ముందులా");
        if (byVoice) speak("మార్చాను Sir. ఖర్చు లేదు.", true);
    }

    /** His words were a colour / material (and maybe a name): the tapped piece, every part, or the part his words name. */
    private void paintSaid(Workshop.PaintSay ps, boolean byVoice) {
        if (ps.all) { paintAll(ps.kind, ps.color, byVoice); return; }
        if (ps.rest.isEmpty()) {
            if (pickedId != null) { paint(pickedId, pickedName, ps.kind, ps.color, byVoice); return; }
            String s0 = "ఏ భాగానికో ముందు తాకండి Sir, లేదా ఆ భాగం పేరుతో చెప్పండి (ఉదా: రెక్కలు నీలం చెయ్).";
            setStatus(s0);
            speak(s0, byVoice);
            return;
        }
        paintAsk = ps;
        paintVoice = byVoice;
        paintReq = "p" + Long.toHexString(System.nanoTime());
        try { send(json("paintname").put("req", paintReq).put("words", new JSONArray(ps.rest))); } catch (Exception ignored) {}
    }

    private static String colourTe(String hex) {
        for (String[] sw : Workshop.SWATCHES) if (sw[0].equalsIgnoreCase(hex)) return sw[1];
        for (String[] c : Workshop.PAINT_COLOURS) if (c[0].equalsIgnoreCase(hex)) return c[1];
        return hex;
    }

    // ================================================================ ⚖️ / 📐 cards and the drawing's PDF (₹0)

    /** A card with text that scrolls (the weight). */
    private void textCard(String title, String text) {
        TextView body = Ui.text(this, text, 14f, Ui.TEXT);
        body.setLineSpacing(0, 1.18f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(8), dp(18), dp(6));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("సరే", null).show();
    }

    /** 📐 When both the three views and the parts list are here: the PDF, into Downloads/Jarvis/workshop. */
    private void maybeDraw() {
        if (drawViews == null || drawDesc == null) return;
        final String views = drawViews, desc = drawDesc, title = shown != null ? shown.title : "model";
        final boolean voice = drawVoice;
        drawReq = null;
        drawViews = null;
        drawDesc = null;
        new Thread(() -> {
            Coder.Made made = null;
            String err = null;
            try {
                if (views.isEmpty()) throw new IllegalStateException("డ్రాయింగ్ రాలేదు");
                byte[] pdf = drawingPdf(title, views, desc);
                made = Coder.save(this, "Jarvis/workshop", Workshop.fileName(title, "డ్రాయింగ్.pdf"), "application/pdf", pdf);
            } catch (Throwable x) { err = x.getMessage(); }
            final Coder.Made m0 = made;
            final String e0 = err;
            ui.post(() -> {
                if (destroyed) return;
                if (m0 == null) { setStatus("✗ డ్రాయింగ్ కాలేదు Sir" + (e0 != null ? ": " + e0 : "")); return; }
                setStatus("📐 " + m0.where + " · ఖర్చు ₹0");
                if (voice) speak("డ్రాయింగ్ Downloads లో దాచాను Sir.", true);
                new AlertDialog.Builder(this).setTitle("📐 డ్రాయింగ్ సిద్ధం")
                        .setMessage(m0.where + "\n\nముందు, పై, పక్క నుంచి బొమ్మలు, కొలతలతో; రెండో పేజీలో భాగాల జాబితా. ఇవి AI డిజైన్ చేసిన కొలతలు.")
                        .setPositiveButton("📄 తెరువు", (d, w) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(m0.uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                            catch (Exception x) { Toast.makeText(this, "PDF తెరిచే యాప్ దొరకలేదు", Toast.LENGTH_SHORT).show(); }
                        })
                        .setNeutralButton("📤 పంచుకో", (d, w) -> {
                            Intent sh = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, m0.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try { startActivity(Intent.createChooser(sh, "పంచుకోండి")); } catch (Exception ignored) {}
                        })
                        .setNegativeButton("సరే", null).show();
            });
        }, "jarvis-workshop-drawing").start();
    }

    /** A4 across: the three views at one scale (front, top above it, right side beside it), then the parts list. */
    private byte[] drawingPdf(String title, String viewsJson, String descJson) throws Exception {
        JSONArray vs = new JSONObject(viewsJson).getJSONArray("views");
        java.util.Map<String, android.graphics.Bitmap> bm = new java.util.HashMap<>();
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.getJSONObject(i);
            byte[] b = android.util.Base64.decode(v.optString("img"), android.util.Base64.DEFAULT);
            if (!Workshop.jpegOk(b)) throw new IllegalStateException("డ్రాయింగ్ బొమ్మ సరిగ్గా రాలేదు");
            bm.put(v.optString("name"), android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length));
        }
        android.graphics.Bitmap F = bm.get("front"), T = bm.get("top"), S = bm.get("side");
        if (F == null || T == null || S == null) throw new IllegalStateException("డ్రాయింగ్ బొమ్మలు రాలేదు");
        android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.Paint img = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        String date = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(new java.util.Date());
        int pageNo = 1;
        android.graphics.pdf.PdfDocument.Page pg = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pageNo).create());
        android.graphics.Canvas c = pg.getCanvas();
        drawingHead(c, ink, "📐 " + title + " · 2D డ్రాయింగ్", "కొలతలు సెంటీమీటర్లలో · మూడు బొమ్మలూ ఒకే స్కేల్ · Jarvis 3D వర్క్‌షాప్ · " + date);
        float gap = 10, x0 = 28, y0 = 64, aw = 786, ah = 476;
        float sc = Math.min((aw - gap) / (F.getWidth() + S.getWidth()), (ah - gap) / (T.getHeight() + F.getHeight()));
        android.graphics.RectF rt = new android.graphics.RectF(x0, y0, x0 + T.getWidth() * sc, y0 + T.getHeight() * sc);
        android.graphics.RectF rf = new android.graphics.RectF(x0, rt.bottom + gap, x0 + F.getWidth() * sc, rt.bottom + gap + F.getHeight() * sc);
        android.graphics.RectF rs = new android.graphics.RectF(rf.right + gap, rf.top, rf.right + gap + S.getWidth() * sc, rf.top + S.getHeight() * sc);
        c.drawBitmap(T, null, rt, img);
        c.drawBitmap(F, null, rf, img);
        c.drawBitmap(S, null, rs, img);
        ink.setStyle(android.graphics.Paint.Style.STROKE);
        ink.setStrokeWidth(0.6f);
        ink.setColor(0xFFC9D3DE);
        for (android.graphics.RectF r : new android.graphics.RectF[]{rt, rf, rs}) c.drawRoundRect(r, 4, 4, ink);
        ink.setStyle(android.graphics.Paint.Style.FILL);
        ink.setColor(0xFF2A6FD6);
        ink.setTextSize(10f);
        ink.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        c.drawText("పై నుంచి", rt.left + 8, rt.top + 16, ink);
        c.drawText("ముందు నుంచి", rf.left + 8, rf.top + 16, ink);
        c.drawText("కుడి పక్క నుంచి", rs.left + 8, rs.top + 16, ink);
        drawingFoot(c, ink, pageNo);
        doc.finishPage(pg);
        // the parts list
        List<String[]> rows = Workshop.drawingRows(descJson);
        int i = 0;
        while (i < rows.size() || pageNo == 1) {
            pageNo++;
            pg = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pageNo).create());
            c = pg.getCanvas();
            drawingHead(c, ink, "🔩 " + title + " · భాగాల జాబితా", "సైజు = వెడల్పు × ఎత్తు × లోతు (సెం.మీ) · పదార్థం బొమ్మలో వాడినది");
            float y = 84;
            ink.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
            ink.setTextSize(10f);
            ink.setColor(0xFF15202B);
            float[] cx = {30, 68, 420, 470, 640};
            String[] head = {"#", "భాగం", "ఎన్ని", "సైజు (సెం.మీ)", "పదార్థం"};
            for (int k = 0; k < head.length; k++) c.drawText(head[k], cx[k], y, ink);
            ink.setColor(0xFFC9D3DE);
            c.drawRect(28, y + 5, 814, y + 5.8f, ink);
            y += 22;
            ink.setTypeface(android.graphics.Typeface.DEFAULT);
            while (i < rows.size() && y < 548) {
                String[] r = rows.get(i++);
                boolean piece = "p".equals(r[5]);
                ink.setColor(piece ? 0xFF4A5868 : 0xFF15202B);
                ink.setTypeface(piece ? android.graphics.Typeface.DEFAULT : android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                c.drawText(r[0], cx[0], y, ink);
                c.drawText((piece ? "   " : "") + r[1], cx[1], y, ink);
                c.drawText(r[2], cx[2], y, ink);
                c.drawText(r[3], cx[3], y, ink);
                c.drawText(r[4], cx[4], y, ink);
                y += piece ? 18 : 21;
            }
            if (rows.isEmpty()) { ink.setColor(0xFF4A5868); c.drawText("భాగాల వివరాలు రాలేదు.", 30, y, ink); }
            drawingFoot(c, ink, pageNo);
            doc.finishPage(pg);
            if (rows.isEmpty()) break;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        for (android.graphics.Bitmap b : bm.values()) if (b != null) b.recycle();
        return out.toByteArray();
    }

    private static void drawingHead(android.graphics.Canvas c, android.graphics.Paint ink, String title, String sub) {
        ink.setStyle(android.graphics.Paint.Style.FILL);
        ink.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, 842, 595, ink);
        ink.setColor(0xFF15202B);
        ink.setTextSize(16f);
        ink.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        c.drawText(title, 28, 36, ink);
        ink.setTextSize(9.5f);
        ink.setTypeface(android.graphics.Typeface.DEFAULT);
        ink.setColor(0xFF5B6876);
        c.drawText(sub, 28, 52, ink);
        ink.setStyle(android.graphics.Paint.Style.STROKE);
        ink.setStrokeWidth(0.8f);
        ink.setColor(0xFF9FB0C2);
        c.drawRect(18, 16, 824, 579, ink);
        ink.setStyle(android.graphics.Paint.Style.FILL);
    }

    private static void drawingFoot(android.graphics.Canvas c, android.graphics.Paint ink, int page) {
        ink.setStyle(android.graphics.Paint.Style.FILL);
        ink.setTypeface(android.graphics.Typeface.DEFAULT);
        ink.setTextSize(8.5f);
        ink.setColor(0xFF5B6876);
        c.drawText("ఇవి AI డిజైన్ చేసిన కొలతలు; తయారు చేసే ముందు అసలు సామానుతో సరిచూడండి.", 28, 570, ink);
        c.drawText("పేజీ " + page, 780, 570, ink);
    }

    // ================================================================ 🖼️ a saved model's small picture

    private final Runnable thumbR = () -> {
        if (destroyed || !front || shown == null || shown.parts.isEmpty() || job != null || tourSteps != null || arOn || realOn || guestTitle != null) return;
        thumbReq = "t" + Long.toHexString(System.nanoTime());
        thumbAt = shown.at;
        try { send(json("snap").put("req", thumbReq).put("clean", true).put("most", 360)); } catch (Exception ignored) {}
    };

    private void thumbLater() {
        ui.removeCallbacks(thumbR);
        ui.postDelayed(thumbR, 3000);
    }

    // ================================================================ (7) ✋ hand signs, (8) 📏 real size on the floor (filled in below)

    private String hands(boolean on) { return on ? "✋ చేతి సైగలు తర్వాతి అప్డేట్‌లో వస్తాయి Sir." : null; }

    private String real(boolean on) { return on ? "📏 నిజమైన సైజు తర్వాతి అప్డేట్‌లో వస్తుంది Sir." : null; }

    private void realTap(double x, double y) {}

    /** Opens the workshop (from ⚙️, a tool, the brain): a build to start, or the last saved one. */

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
        if (arOn && cam != null) { // (📷 the camera again, only now that the screen is in front)
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cam.setScreenTurn(turnNow());
                cam.open();
                watchTurn(true);
            } else { String s = ar(false); if (s != null) setStatus(s); refreshButtons(); }
        }
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
        if (tourSteps != null) endTour(false); // (🎓 he left: the show stops)
        if (readState == READ_PLAYING) readPause(); // (🔊 the guide's reading waits for him: ▶️ goes on)
        if (cam != null) cam.close(); // (📷 never the camera behind his back; it opens again when he is back)
        watchTurn(false);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        openTitle = null;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        if (front) { front = false; JarvisAccessibility.bubbleAside(false); }
        if (ear != null) ear.shutdown();
        Job j = job;
        if (j != null) stopJob(j);
        if (cam != null) cam.release();
        watchTurn(false);
        ui.removeCallbacksAndMessages(null);
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) {
            try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) {}
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ================================================================ (A5) the bin, a sound and a little buzz

    /** 🔊 + 📳 for his hand (his "Jarvis శబ్దాలు" setting decides the sound; nothing during a call). */
    private void feel(String kind, int ms) {
        if (CallControl.busyWithCall()) return;
        Sfx.workshop(this, p, kind);
        try {
            if (vib == null) vib = getSystemService(android.os.Vibrator.class);
            if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    /** 🗑️ What is in the bin: a tap brings one back; all back; or take them off the model for good. */
    private void binTray() {
        if (binItems.isEmpty()) {
            setStatus("🗑️ బిన్ ఖాళీగా ఉంది · విడదీశాక భాగం మీద వేలు అర సెకను నొక్కి 🗑️ వైపు లాగండి");
            return;
        }
        final List<String[]> items = new ArrayList<>(binItems);
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = "↩️ " + items.get(i)[2];
        new AlertDialog.Builder(this).setTitle("🗑️ బిన్‌లో " + items.size())
                .setItems(names, (d, w) -> {
                    String[] b = items.get(w);
                    try { send(json("unbin").put("id", b[0]).put("label", b[1])); } catch (Exception ignored) {}
                })
                .setPositiveButton("↩️ అన్నీ వెనక్కి", (d, w) -> command("unbin_all", false))
                .setNeutralButton("🗑️ శాశ్వతంగా తీసేయి", (d, w) -> emptyBin())
                .setNegativeButton("మూసేయి", null).show();
    }

    /** 🗑️ Empty the bin: those pieces come off this model for good (₹0; ↩️ వెనక్కి brings them back). */
    private void emptyBin() {
        if (binItems.isEmpty() || shown == null) { setStatus("🗑️ బిన్ ఖాళీగా ఉంది"); return; }
        final List<String[]> items = new ArrayList<>(binItems);
        StringBuilder names = new StringBuilder();
        for (String[] b : items) names.append(names.length() > 0 ? ", " : "").append(b[2]);
        new AlertDialog.Builder(this).setTitle("🗑️ శాశ్వతంగా తీసేయాలా?")
                .setMessage(names + "\n\nఈ బొమ్మ నుంచి తీసేస్తాను (ఖర్చు ₹0). మనసు మారితే ↩️ వెనక్కి నొక్కితే మళ్ళీ వస్తాయి.")
                .setPositiveButton("తీసేయి", (d, w) -> {
                    if (shown == null || job != null) return;
                    undo.addFirst(shown.copy());
                    while (undo.size() > 5) undo.removeLast();
                    Shown after = shown.copy();
                    for (String[] b : items) {
                        if (!after.parts.containsKey(b[0])) continue;
                        if (Boolean.parseBoolean(b[3])) { // (a whole part: out of the model)
                            after.parts.remove(b[0]);
                            try { send(json("remove").put("id", b[0])); } catch (Exception ignored) {}
                            TextView tv = rows.remove(b[0]);
                            if (tv != null) list.removeView(tv);
                            onStage.remove(b[0]);
                        } else after.gone.add(new String[]{b[0], b[1]});
                    }
                    after.changes++;
                    shown = after;
                    save(shown);
                    sendGone(shown);
                    send(json("binclear"));
                    binItems.clear();
                    binCount = 0;
                    refreshButtons();
                    setStatus("🗑️ తీసేశాను · ఖర్చు ₹0 · ↩️ వెనక్కి నొక్కితే మళ్ళీ వస్తాయి");
                    thumbLater();
                    speak("తీసేశాను Sir.", true);
                })
                .setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ (A4) the view tools, photos, print files, landscape

    /** The stage starts plain again (a new build, a saved one, a fresh stage): no isolation, X-ray, cut, hidden parts, tilt. */
    private void viewReset() {
        tapeOn = false; // (the stage's reset takes the tape and the second model away too)
        guestTitle = null;
        guestTop = false;
        binCount = 0;
        binItems.clear();
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
        if (thumbReq != null && thumbReq.equals(req)) { // 🖼️ a saved model's small picture
            thumbReq = null;
            final String data = m.optString("data", "");
            final long at = thumbAt;
            new Thread(() -> {
                try {
                    byte[] b = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                    if (!"jpg".equals(m.optString("kind")) || !Workshop.jpegOk(b)) return;
                    java.io.File f = Workshop.thumb(this, at), tmp = new java.io.File(f.getPath() + ".part");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(b); }
                    if (!tmp.renameTo(f)) tmp.delete();
                } catch (Exception ignored) {}
            }, "jarvis-workshop-thumb").start();
            return;
        }
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
                } else if ("video".equals(what)) { // 🎞️ the show, as he saw it, with its lines written on
                    if ("none".equals(kind) || b == null) throw new IllegalStateException("ఈ ఫోన్‌లో వీడియో రికార్డ్ కుదరలేదు Sir");
                    if (!Workshop.videoOk(b, kind)) throw new IllegalStateException("వీడియో సరిగ్గా రాలేదు Sir");
                    boolean mp4 = "mp4".equals(kind);
                    Coder.Made made = Workshop.saveVideo(this, Workshop.fileName(title, "show-" + System.currentTimeMillis() % 1000000 + (mp4 ? ".mp4" : ".webm")), mp4 ? "video/mp4" : "video/webm", b);
                    line = "🎞️ గ్యాలరీలో దాచాను: " + made.where + " · మాటలు అక్షరాలుగా (గొంతు లేదు)";
                    said = "షో వీడియో గ్యాలరీలో దాచాను Sir.";
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
        if (req == REQ_AR_PERM) {
            boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String s = ok ? ar(true) : "📷 కెమెరా అనుమతి లేకుండా గదిలో చూపించలేను Sir. కావాలంటే Android సెట్టింగ్స్ → యాప్స్ → Jarvis → అనుమతులు లో కెమెరా ఇవ్వండి.";
            if (s != null) setStatus(s);
            refreshButtons();
        }
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
        if (cam != null && arOn) cam.setScreenTurn(turnNow()); // (📷 the room's picture turns with the screen)
    }

    private void fit(android.content.res.Configuration c) {
        boolean wide = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // across: the stage keeps most of the height (the part list folds away; a row of it still opens its part)
        if (listScroll != null) listScroll.setVisibility(wide ? View.GONE : View.VISIBLE);
        if (status != null) status.setMaxLines(wide ? 2 : 6);
        if (answerBody != null) answerBody.setMaxLines(wide ? 6 : 40);
    }

    // ================================================================ (E) 🎓 how it works: a show written once by his AI, then free

    /** What he may ask while a show plays without stopping it. */
    private static final Set<String> TOUR_KEEPS = new HashSet<>(Arrays.asList("zoom_in", "zoom_out", "night", "day", "cost", "center",
            "show_how", "show_next", "show_stop", "labels_on", "labels_off", "ar_on", "ar_off", "photo", "spin_on", "spin_off",
            "hands_on", "hands_off", "show_video"));

    /** 🎓 The show for this model: free when it was made for it; else (said first what it costs) his AI writes one. */
    private void tourAsk(boolean byVoice) {
        if (job != null) { String s = "ఒక పని ఇంకా జరుగుతోంది Sir. అది అయ్యాక అడగండి."; setStatus(s); speak(s, byVoice); return; }
        if (tourSteps != null) endTour(false);
        final Shown sh = shown;
        if (sh.tour != null && sh.tourAt == sh.changes) { // (made for this very model: free)
            try { playTour(new JSONObject(sh.tour), byVoice, null); return; } catch (Exception ignored) {} // (unreadable: a new one)
        }
        String model = p.workshopModel().trim();
        String about = model.isEmpty() ? "" : "సుమారు " + Workshop.aboutMoney(model);
        boolean old = sh.tour != null;
        if (byVoice) { // (✋ 3 seconds to say "వద్దు" before any money is spent)
            waitThen("🎓 " + (old ? "బొమ్మ మారింది: కొత్త షో" : "ఎలా పనిచేస్తుందో షో") + (about.isEmpty() ? "" : " (" + about + ")"), () -> makeAsk("tour", true));
            return;
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("🎓 ఎలా పనిచేస్తుందో చూపించు")
                .setMessage((old ? "షో తయారు చేశాక ఈ బొమ్మ మారింది. " : "")
                        + "మీరు ఎంచుకున్న AI ఈ బొమ్మకి ఒక చిన్న కదిలే పాఠం తయారు చేస్తుంది: ఏ భాగం ఏం చేస్తుందో అది వెలుగుతూ, తిరుగుతూ, గాలి / కరెంట్ బాణాలతో, తెలుగులో చెబుతూ."
                        + (about.isEmpty() ? "" : " ఒక్కసారికే " + about + ".") + " మళ్ళీ చూడటం ఉచితం.")
                .setPositiveButton(old ? "🆕 కొత్తది తయారు చెయ్" : "▶ తయారు చెయ్", (x, w) -> makeAsk("tour", false))
                .setNegativeButton("వద్దు", null);
        if (old) d.setNeutralButton("▶ పాతదే (ఉచితం)", (x, w) -> { try { playTour(new JSONObject(sh.tour), false, null); } catch (Exception ignored) {} });
        d.show();
    }

    /** (E, F) A show or a guide: the stage first describes the model (names, sizes, materials); then the AI writes. */
    private void makeAsk(String kind, boolean byVoice) {
        if (shown == null || shown.parts.isEmpty() || destroyed) return;
        if (job != null) { String s = "ఒక పని ఇంకా జరుగుతోంది Sir."; setStatus(s); speak(s, byVoice); return; }
        Workshop.Engine e;
        try { e = Workshop.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), byVoice); return; }
        String over = Workshop.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, byVoice); return; }
        if (tourSteps != null) endTour(false);
        Job j = new Job(shown.what, kind, e);
        j.kind = kind;
        j.title = shown.title;
        j.byVoice = byVoice;
        j.quiet = true;
        job = j;
        closeAnswer();
        stopBtn.setVisibility(View.VISIBLE);
        refreshButtons();
        final String req = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
        descJob = j;
        descReq = req;
        try { send(json("describe").put("req", req)); } catch (Exception ignored) {}
        String doing = "tour".equals(kind) ? "షో తయారు చేస్తోంది" : "తయారీ విధానం రాస్తోంది";
        setStatus("🧠 " + label(e.model) + " " + doing + "…");
        ticker(j, doing);
        ui.postDelayed(() -> { if (descJob == j && req.equals(descReq)) { descJob = null; descReq = null; failMake(j, "3D స్టేజ్ బొమ్మ వివరాలు ఇవ్వలేదు"); } }, 10000);
    }

    /** (its own thread) The AI writes the show / the guide from the model's description; checked; then kept with the model. */
    private void runMake(Job j, String data) {
        Http.CANCEL.set(j.cancel);
        final boolean tour = "tour".equals(j.kind);
        String desc = Workshop.describeText(data);
        StringBuilder out = new StringBuilder();
        Workshop.Bill b = new Workshop.Bill();
        String failure = null;
        try {
            if (desc.trim().isEmpty()) throw new Workshop.Problem("బొమ్మలో భాగాలు కనిపించలేదు");
            Workshop.stream(j.engine, tour ? Workshop.TOUR_SYSTEM : Workshop.GUIDE_SYSTEM,
                    tour ? Workshop.tourMessage(j.title, j.what, desc) : Workshop.guideMessage(j.title, j.what, desc),
                    tour ? Workshop.TOUR_OUT : Workshop.GUIDE_OUT, new Workshop.Sink() { @Override public void text(String s) { out.append(s); } }, b);
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Workshop.Problem x) {
            failure = x.getMessage();
        } catch (Exception x) {
            failure = Workshop.why(p, x, j.engine.provider);
        } finally {
            add(j, b);
        }
        Workshop.spend(this, j.rupees, false);
        JSONObject show = null;
        String guide = null, refused = null;
        if (failure == null && !j.stopped) {
            if (tour) {
                try {
                    show = Workshop.parseTour(out.toString(), Workshop.describedIds(data));
                    if (show.has("refuse")) { refused = show.optString("refuse"); show = null; }
                } catch (Workshop.Problem x) { failure = x.getMessage(); }
            } else {
                guide = Workshop.cleanGuide(out.toString());
                if (guide.isEmpty()) { failure = "AI ఏమీ రాయలేదు"; guide = null; }
                else if (Workshop.guideRefused(guide)) { refused = Workshop.oneLine(guide); guide = null; }
            }
        }
        final JSONObject s0 = show;
        final String g0 = guide, r0 = refused, f0 = failure;
        ui.post(() -> finishMake(j, s0, g0, r0, f0));
    }

    private void finishMake(Job j, JSONObject show, String guide, String refused, String failure) {
        if (job == j) job = null;
        if (destroyed) return;
        stopBtn.setVisibility(View.GONE);
        String cost = Workshop.money(j.rupees) + (j.estimated ? " (సుమారు)" : "");
        if (shown != null && (show != null || guide != null || j.rupees > 0)) { // (what it cost goes on the model's bill; kept with it)
            Shown after = shown.copy();
            if (show != null) { after.tour = show.toString(); after.tourAt = after.changes; }
            if (guide != null) { after.guide = guide; after.guideAt = after.changes; }
            after.cost += j.rupees;
            after.estimated |= j.estimated;
            shown = after;
            save(after);
        }
        refreshButtons();
        boolean tour = "tour".equals(j.kind);
        if (show != null) {
            playTour(show, j.byVoice, cost);
        } else if (guide != null) {
            setStatus("🛠️ తయారీ విధానం సిద్ధం · " + cost + " · మళ్ళీ తెరవడానికి ఖర్చు లేదు");
            showGuide(guide, false);
            speak("తయారీ విధానం సిద్ధం Sir. దీనికి " + cost + " అయింది. ఖర్చులు సుమారు అంచనా మాత్రమే.", j.byVoice);
        } else if (refused != null) {
            setStatus("🚫 " + refused + " · " + cost);
            speak(refused, j.byVoice);
        } else if ("stopped".equals(failure) || j.stopped) {
            setStatus("⏹ ఆపేశారు · " + cost);
            speak("ఆపేశాను Sir. ఖర్చు " + cost + ".", j.byVoice);
        } else {
            setStatus("✗ " + failure + " · " + cost);
            speak((tour ? "షో తయారు చేయలేకపోయాను Sir: " : "తయారీ విధానం రాయలేకపోయాను Sir: ") + failure, j.byVoice);
        }
    }

    /** A show / guide that ended before the AI was asked (stopped, or the stage did not answer): nothing spent. */
    private void failMake(Job j, String why) {
        if (job == j) job = null;
        if (destroyed) return;
        stopBtn.setVisibility(View.GONE);
        refreshButtons();
        String s = why == null ? "⏹ ఆపేశారు · ఖర్చు ₹0" : "✗ " + why + " · ఖర్చు ₹0";
        setStatus(s);
        if (why != null) speak(why + " Sir. ఖర్చు లేదు.", j.byVoice);
    }

    /** The whole model in plain view before a show: nothing alone, see-through, cut or hidden; the bin's pieces back; no tap. */
    private void plainView() {
        if (isolatedOn || xrayOn || cutOn || hiddenCount > 0) { send(json("showall")); isolatedOn = false; xrayOn = false; cutOn = false; hiddenCount = 0; }
        if (binCount > 0) { try { send(json("unbin").put("all", true)); } catch (Exception ignored) {} }
        if (tiltOn) tilt(false);
        if (pickedId != null) { send(json("unpick")); pickedId = null; pickedName = null; }
        closeAnswer();
    }

    private void playTour(JSONObject t, boolean byVoice, String cost) {
        JSONArray steps = t.optJSONArray("steps");
        if (steps == null || steps.length() == 0) { setStatus("ఈ షోలో అడుగులు లేవు Sir."); return; }
        if (tourSteps != null) endTour(false);
        plainView();
        recording = false;
        if (recordNext && System.currentTimeMillis() - recordAt < 300_000 && !arOn && !realOn) { // 🎞️ this time it is recorded too
            recording = true;
            fileReq = "v" + Long.toHexString(System.nanoTime());
            fileFor = "video";
            try { send(json("rec").put("on", true).put("req", fileReq).put("title", shown != null ? shown.title : "")); } catch (Exception ignored) {}
        }
        recordNext = false;
        tourSteps = steps;
        tourIdx = -1;
        tourVoice = byVoice;
        tourCost = cost;
        refreshButtons();
        tourAdvance();
    }

    /** The next step when its time is up and Jarvis has finished saying its line (and a breath after). */
    private final Runnable tourTick = new Runnable() {
        @Override public void run() {
            if (tourSteps == null || destroyed) return;
            long now = System.currentTimeMillis();
            boolean talking = (ear != null && ear.speaking()) || Announcer.speaking();
            if (talking) tourQuietAt = 0;
            else if (tourQuietAt == 0) tourQuietAt = now;
            boolean timeUp = now - tourStepAt >= tourMin, said = tourQuietAt > 0 && now - tourQuietAt >= 600;
            if (timeUp && (said || now - tourStepAt > tourMin + 20000)) { tourAdvance(); return; }
            ui.postDelayed(this, 250);
        }
    };

    private void tourAdvance() {
        ui.removeCallbacks(tourTick);
        if (tourSteps == null) return;
        tourIdx++;
        if (tourIdx >= tourSteps.length()) { endTour(true); return; }
        JSONObject s = tourSteps.optJSONObject(tourIdx);
        if (s == null) { tourAdvance(); return; }
        try { send(json("tour").put("step", s)); } catch (Exception ignored) {}
        String say = s.optString("say");
        setStatus((recording ? "⏺ " : "🎓 ") + (tourIdx + 1) + "/" + tourSteps.length() + (say.isEmpty() ? "" : " · " + say));
        tourStepAt = System.currentTimeMillis();
        tourQuietAt = 0;
        tourMin = Math.round(Workshop.stepSeconds(s) * 1000);
        if (!say.isEmpty()) speak(say, false);
        ui.postDelayed(tourTick, 700);
    }

    /** The show ends (finished: a last line, and what it cost when it was just made); every piece back as it was. */
    private void endTour(boolean finished) {
        if (tourSteps == null) return;
        ui.removeCallbacks(tourTick);
        tourSteps = null;
        send(json("tourend"));
        if (recording) { // 🎞️ the video ends with the show (kept only when the show was seen to the end)
            recording = false;
            if (finished) { try { send(json("rec").put("stop", true)); } catch (Exception ignored) {} }
            else { send(json("rec")); fileReq = null; fileFor = null; ui.post(() -> setStatus("🎞️ వీడియో ఆపాను (దాచలేదు)")); }
        }
        refreshButtons();
        if (finished) {
            setStatus("🎓 షో అయిపోయింది" + (tourCost != null ? " · " + tourCost : "") + (fileFor != null && "video".equals(fileFor) ? " · 🎞️ వీడియో దాచుతోంది…" : " · మళ్ళీ చూడాలంటే 🎓 (ఉచితం)"));
            speak("అంతే Sir, ఇది ఇలా పనిచేస్తుంది." + (tourCost != null ? " ఈ షో తయారీకి " + tourCost + " అయింది; మళ్ళీ చూడటం ఉచితం." : ""), tourVoice);
        }
        tourCost = null;
    }

    private void stopTourSaid(boolean byVoice) {
        endTour(false);
        setStatus("🎓 షో ఆపాను · మళ్ళీ చూడాలంటే 🎓 (ఉచితం)");
        speak("సరే Sir, ఆపాను.", byVoice);
    }

    // ================================================================ (F) 🛠️ how to really make it: written once by his AI, kept

    private void guideAsk(boolean byVoice) {
        if (job != null) { String s = "ఒక పని ఇంకా జరుగుతోంది Sir. అది అయ్యాక అడగండి."; setStatus(s); speak(s, byVoice); return; }
        final Shown sh = shown;
        if (sh.guide != null && sh.guideAt == sh.changes) { // (written for this very model: free)
            showGuide(sh.guide, true);
            setStatus("🛠️ తయారీ విధానం · మళ్ళీ తెరవడానికి ఖర్చు లేదు");
            if (byVoice) speak("తయారీ విధానం తెరిచాను Sir.", true);
            return;
        }
        String model = p.workshopModel().trim();
        String about = model.isEmpty() ? "" : "సుమారు " + Workshop.aboutMoney(model);
        boolean old = sh.guide != null;
        if (byVoice) {
            waitThen("🛠️ " + (old ? "బొమ్మ మారింది: కొత్త తయారీ విధానం" : "నిజంగా ఎలా తయారు చేయాలి") + (about.isEmpty() ? "" : " (" + about + ")"), () -> makeAsk("guide", true));
            return;
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("🛠️ నిజంగా ఎలా తయారు చేయాలి")
                .setMessage((old ? "ఇది రాశాక ఈ బొమ్మ మారింది. " : "")
                        + "మీరు ఎంచుకున్న AI ఈ బొమ్మని నిజంగా తయారు చేయడానికి కావలసిన సామాను, పనిముట్లు, అడుగులు, సుమారు ఖర్చు, జాగ్రత్తలు తెలుగులో రాస్తుంది."
                        + (about.isEmpty() ? "" : " ఒక్కసారికే " + about + ".") + " ధరలు అంచనా మాత్రమే.")
                .setPositiveButton(old ? "🆕 కొత్తది రాయి" : "✍️ రాయి", (x, w) -> makeAsk("guide", false))
                .setNegativeButton("వద్దు", null);
        if (old) d.setNeutralButton("📄 పాతదే (ఉచితం)", (x, w) -> showGuide(sh.guide, true));
        d.show();
    }

    /** The guide on the screen: 🔊 read aloud, 💾 into Downloads, 📤 share (each only on his tap). */
    private void showGuide(String text, boolean saved) {
        final String title = shown != null ? shown.title : "";
        readStop(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView note = Ui.text(this, "💰 ఖర్చులు సుమారు అంచనా మాత్రమే (షాపు, ఊరుని బట్టి మారతాయి)" + (saved ? " · మళ్ళీ తెరవడానికి ఖర్చు లేదు" : ""), 12.5f, Ui.MUTED);
        box.addView(note);
        // 🔊 read aloud: ▶️ play, ⏸ pause, ⏹ stop (a line at a time: after a pause it goes on from that line)
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        readPlay = pill("▶️ చదువు", v -> readPlay());
        readPause = pill("⏸ పాజ్", v -> readPause());
        readStopBtn = pill("⏹ ఆపు", v -> readStop(true));
        ctl.addView(readPlay);
        ctl.addView(readPause);
        ctl.addView(readStopBtn);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = dp(8);
        box.addView(ctl, rl);
        readLine = Ui.text(this, "🔊 ▶️ నొక్కితే Jarvis చదివి వినిపిస్తాడు", 12.5f, Ui.MUTED);
        readLine.setPadding(0, dp(4), 0, dp(8));
        box.addView(readLine);
        TextView body = Ui.text(this, "", 14.5f, Ui.TEXT);
        body.setText(text, TextView.BufferType.SPANNABLE);
        body.setLineSpacing(0, 1.2f);
        body.setTextIsSelectable(true);
        box.addView(body);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        readText = text;
        readParts.clear();
        readParts.addAll(Workshop.readParts(text));
        readIdx = 0;
        readBody = body;
        readScroll = sv;
        readLook();
        AlertDialog d = new AlertDialog.Builder(this).setTitle("🛠️ " + title + " · నిజంగా ఎలా తయారు చేయాలి").setView(sv)
                .setPositiveButton("💾 దాచు", null)
                .setNeutralButton("📤 పంచుకో", null)
                .setNegativeButton("మూసేయి", null).show();
        d.setOnDismissListener(x -> { if (readBody == body) { readStop(false); readBody = null; readScroll = null; readPlay = null; readPause = null; readStopBtn = null; readLine = null; } });
        // (💾 and 📤 leave the card open: the reading goes on)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> saveGuide(title, text));
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title + " · ఎలా తయారు చేయాలి").putExtra(Intent.EXTRA_TEXT, "🛠️ " + title + "\n\n" + text);
            try { startActivity(Intent.createChooser(s, "పంచుకోండి")); } catch (Exception ignored) {}
        });
    }

    // ---- 🔊 the guide read aloud (his ▶️ / ⏸ / ⏹)

    private static final int READ_STOPPED = 0, READ_PLAYING = 1, READ_PAUSED = 2;
    private int readState = READ_STOPPED, readIdx;
    private String readText;
    private final List<int[]> readParts = new ArrayList<>(); // {start, end} of each line in the guide
    private long readAt, readQuietAt;
    private TextView readPlay, readPause, readStopBtn, readLine, readBody;
    private ScrollView readScroll;
    private final Object readMark = new android.text.style.BackgroundColorSpan(0x553AA0FF);

    private void readPlay() {
        if (readText == null || readParts.isEmpty()) return;
        if (readState == READ_STOPPED) readIdx = 0;
        readState = READ_PLAYING;
        readSay();
    }

    /** ⏸ The voice stops; ▶️ goes on from the start of this line. */
    private void readPause() {
        if (readState != READ_PLAYING) return;
        readState = READ_PAUSED;
        ui.removeCallbacks(readTick);
        hushVoice();
        readLook();
    }

    /** ⏹ The voice stops; ▶️ starts again from the top. */
    private void readStop(boolean tapped) {
        boolean was = readState != READ_STOPPED;
        readState = READ_STOPPED;
        readIdx = 0;
        ui.removeCallbacks(readTick);
        if (was) hushVoice();
        readLook();
        if (tapped && readLine != null) readLine.setText("⏹ ఆపాను · ▶️ నొక్కితే మొదటి నుంచి");
    }

    private void hushVoice() {
        if (ear != null) ear.hush();
        Announcer.stop();
    }

    private void readSay() {
        ui.removeCallbacks(readTick);
        if (readState != READ_PLAYING || readText == null) return;
        String line = "";
        while (readIdx < readParts.size() && (line = Workshop.speakable(readText.substring(readParts.get(readIdx)[0], readParts.get(readIdx)[1]))).isEmpty()) readIdx++;
        if (readIdx >= readParts.size()) { // (all of it read)
            readState = READ_STOPPED;
            readIdx = 0;
            readLook();
            if (readLine != null) readLine.setText("✓ పూర్తిగా చదివాను · ▶️ నొక్కితే మళ్ళీ");
            return;
        }
        speak(line, false);
        readAt = System.currentTimeMillis();
        readQuietAt = 0;
        readLook();
        ui.postDelayed(readTick, 700);
    }

    /** The next line once this one has been said (and a short breath). */
    private final Runnable readTick = new Runnable() {
        @Override public void run() {
            if (readState != READ_PLAYING || destroyed) return;
            long now = System.currentTimeMillis();
            boolean talking = (ear != null && ear.speaking()) || Announcer.speaking();
            if (talking) readQuietAt = 0;
            else if (readQuietAt == 0) readQuietAt = now;
            if ((readQuietAt > 0 && now - readQuietAt >= 350) || now - readAt > 60000) { readIdx++; readSay(); return; }
            ui.postDelayed(this, 200);
        }
    };

    /** The buttons, the line under them and the mark on the line being read follow the state. */
    private void readLook() {
        if (readPlay == null) return;
        boolean playing = readState == READ_PLAYING, paused = readState == READ_PAUSED;
        readPlay.setText(paused ? "▶️ కొనసాగించు" : "▶️ చదువు");
        readPlay.setEnabled(!playing);
        readPlay.setAlpha(playing ? 0.35f : 1f);
        readPause.setEnabled(playing);
        readPause.setAlpha(playing ? 1f : 0.35f);
        readStopBtn.setEnabled(playing || paused);
        readStopBtn.setAlpha(playing || paused ? 1f : 0.35f);
        int n = readParts.size(), at = Math.min(readIdx + 1, n);
        if (playing) readLine.setText("🔊 చదువుతోంది · " + at + "/" + n);
        else if (paused) readLine.setText("⏸ " + at + "/" + n + " దగ్గర ఆపి ఉంచాను · ▶️ నొక్కితే ఇక్కడి నుంచే");
        CharSequence cs = readBody == null ? null : readBody.getText();
        if (!(cs instanceof android.text.Spannable)) return;
        android.text.Spannable sp = (android.text.Spannable) cs;
        sp.removeSpan(readMark);
        if ((playing || paused) && readIdx < n) {
            int[] r = readParts.get(readIdx);
            if (r[1] <= sp.length()) sp.setSpan(readMark, r[0], r[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            android.text.Layout l = readBody.getLayout();
            if (l != null && readScroll != null) readScroll.smoothScrollTo(0, Math.max(0, readBody.getTop() + l.getLineTop(l.getLineForOffset(r[0])) - dp(70)));
        }
    }

    private void saveGuide(String title, String text) {
        new Thread(() -> {
            String line;
            try {
                String head = "🛠️ " + title + " · నిజంగా ఎలా తయారు చేయాలి\n(Jarvis 3D వర్క్‌షాప్ · ఖర్చులు సుమారు అంచనా మాత్రమే)\n\n";
                Coder.Made m = Coder.save(this, "Jarvis/workshop", Workshop.fileName(title, "తయారీ-విధానం.txt"), "text/plain",
                        (head + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                line = "💾 " + m.where;
            } catch (Exception x) {
                line = "✗ దాచలేకపోయాను: " + x.getMessage();
            }
            final String l0 = line;
            ui.post(() -> { if (destroyed) return; setStatus(l0); Toast.makeText(this, l0, Toast.LENGTH_LONG).show(); }); // (the card stays open over the screen)
        }, "jarvis-workshop-guide").start();
    }

    // ================================================================ (G) 📷 the model in his room: the camera under the stage (₹0)

    /** On: the phone's camera behind the see-through stage, a soft shadow under the model. Nothing is saved or sent. */
    private String ar(boolean on) {
        boolean has = shown != null && !shown.parts.isEmpty();
        if (!on) {
            if (!arOn) return null;
            arOn = false;
            if (cam != null) { cam.close(); cam.setVisibility(View.GONE); }
            if (web != null) web.setBackgroundColor(0xFF07080B);
            try { send(json("ar").put("on", false)); } catch (Exception ignored) {}
            watchTurn(false);
            return "📷 గది ఆపాను Sir.";
        }
        if (!has) return "ముందు ఒక బొమ్మ తయారు చేయనివ్వండి Sir.";
        if (arOn) return null;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_AR_PERM);
            return "📷 కెమెరా అనుమతి అడుగుతున్నాను Sir.";
        }
        if (cam == null) {
            CamView c = new CamView(this);
            if (!c.hasCamera()) return "ఈ ఫోన్‌లో కెమెరా దొరకలేదు Sir.";
            c.listener = (open, problem) -> { if (!open && problem != null && arOn && !destroyed) setStatus("📷 " + problem); };
            cam = c;
            rootView.addView(c, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        arOn = true;
        cam.setVisibility(View.VISIBLE);
        cam.setScreenTurn(turnNow());
        cam.open();
        if (web != null) web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        try { send(json("ar").put("on", true)); } catch (Exception ignored) {}
        if (spinning) { spinning = false; try { send(json("spin").put("on", false)); } catch (Exception ignored) {} } // (a model in a room stands still)
        watchTurn(true);
        return "📷 బొమ్మ మీ గదిలో Sir. ఒక వేలితో తిప్పండి, రెండు వేళ్లతో చిన్నది / పెద్దది చేయండి. ఇది నేల మీద అతుక్కోదు, సైజు నిజమైనది కాదు.";
    }

    private int turnNow() {
        try { return getWindowManager().getDefaultDisplay().getRotation(); } catch (Exception e) { return 0; }
    }

    /** (a turn from one side straight to the other is no configuration change: the display itself is watched while the room shows) */
    private void watchTurn(boolean on) {
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) return;
        if (turnL != null) { try { dm.unregisterDisplayListener(turnL); } catch (Exception ignored) {} turnL = null; }
        if (!on || !arOn) return;
        turnL = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) { if (cam != null && arOn) cam.setScreenTurn(turnNow()); }
        };
        dm.registerDisplayListener(turnL, ui);
    }

    /** 📸 in the room: exactly what he sees (the room and the model) between the bars, into his gallery. Never sent anywhere. */
    private void arPhoto() {
        View root = rootView;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        int top = topBar == null ? 0 : topBar.getHeight(), bottom = h - (bottomBar == null ? 0 : bottomBar.getHeight());
        if (bottom - top < h / 4) { top = 0; bottom = h; }
        int[] at = new int[2];
        root.getLocationInWindow(at);
        android.graphics.Rect src = new android.graphics.Rect(at[0], at[1] + top, at[0] + w, at[1] + bottom);
        final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(src.width(), src.height(), android.graphics.Bitmap.Config.ARGB_8888);
        final String title = shown != null ? shown.title : "model";
        setStatus("📸 …");
        try {
            android.view.PixelCopy.request(getWindow(), src, bm, result -> {
                if (destroyed) return;
                if (result != android.view.PixelCopy.SUCCESS) { setStatus("✗ ఫోటో రాలేదు Sir."); return; }
                new Thread(() -> {
                    String line, said;
                    try {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
                        Coder.Made made = Workshop.savePicture(this, Workshop.fileName(title, "room-" + System.currentTimeMillis() % 1000000 + ".jpg"), "image/jpeg", out.toByteArray());
                        line = "📸 గ్యాలరీలో దాచాను: " + made.where;
                        said = "గదిలో బొమ్మ ఫోటో గ్యాలరీలో దాచాను Sir.";
                    } catch (Exception x) {
                        line = "✗ " + x.getMessage();
                        said = "ఫోటో దాచలేకపోయాను Sir.";
                    }
                    final String l0 = line, s0 = said;
                    ui.post(() -> { if (destroyed) return; setStatus(l0); speak(s0, true); });
                }, "jarvis-workshop-room-photo").start();
            }, ui);
        } catch (Exception x) {
            setStatus("✗ ఫోటో రాలేదు Sir.");
        }
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
