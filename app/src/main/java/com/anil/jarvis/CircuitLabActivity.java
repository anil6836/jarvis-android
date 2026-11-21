package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * ⚡ The circuit lab screen (Anil, 16 Nov, G1): the board page (assets/circuit, our own code: parts, wires, live current,
 * 🔋 numbers, 🏠 the home's bill, 🧩 challenges, 📘 ready boards) in a WebView sealed like the game studio's (only the lab's
 * own files load, no network, no keys, one message port), and around it: what he types or says ("బల్బ్ 2 ఆపు" is done by
 * the page for ₹0; anything bigger goes to the workshop's AI, which answers with the board as data), 📷 a photo of a circuit
 * drawn on paper (sent to the AI for that one board, never kept), 📂 saved boards, 💾 save, 📤 a picture or a PDF report he
 * shares himself. Phone only. (G2.1) ⛶ full screen and 🔄 sideways by his tap; a photo becomes ⚡ a board, 📐 a drawing just
 * like it (a single-line diagram, live when linked to the board), or both: his choice, each with its cost said first.
 */
public class CircuitLabActivity extends Activity {
    static final String EXTRA_WHAT = "what", EXTRA_HEARD = "heard", EXTRA_SAID = "said", EXTRA_CHANGE = "change", EXTRA_SHELF = "shelf", EXTRA_TAB = "tab",
            EXTRA_SCAN_TITLE = "scan_title", EXTRA_SCAN_PARTS = "scan_parts";
    /** (G2) The camera's photo of a scanned board waits here (the app's private cache) only until the lab reads it, then it is deleted. */
    private static final String SCAN_PHOTO = "circuit_scan.jpg";
    /** The lab is in front: "Jarvis" opens no panel over it (its own ears hear him). */
    static volatile boolean front;
    private static volatile String openTitle; // (read through openTitle())
    private static volatile String openSummary = "";
    private static volatile long stoppedAt;

    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/circuit/";
    private static final String PAGE = "https://" + HOST + PATH + "lab.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("lab.html", "lab.js", "sim.js", "labkit.js", "view3d.js"));
    /** 🧊 The 3D view uses the workshop's bundled three.js (one copy in the app), served here under the lab's own path. */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList("three.module.min.js", "OrbitControls.js"));
    private static final int REQ_CAM = 71, REQ_GAL = 72, REQ_CAM_PERM = 73;
    /** Ready ideas for the AI: the chip, and the fuller request it puts in the box (he can change it before ⚡). */
    private static final String[][] IDEAS = {
            {"🏠 ఇన్వర్టర్ ఇల్లు", "ఇన్వర్టర్ 900VA, 12V 150Ah బ్యాటరీ, హాల్లో 2 LED బల్బులు, 2 ఫ్యాన్లు, ఒక TV, బెడ్‌రూమ్‌లో ఒక బల్బ్, ఒక ఫ్యాన్, ప్రతిదానికీ స్విచ్, 16A MCB తో"},
            {"☀️ సోలార్ + బ్యాటరీ", "2 సోలార్ ప్యానెల్స్ 330W తో 12V 150Ah బ్యాటరీ ఛార్జ్ అవ్వాలి, ఇన్వర్టర్ ద్వారా 3 LED బల్బులు, ఒక ఫ్యాన్, బ్యాటరీకి ఫ్యూజ్"},
            {"🏍️ బైక్ లైట్లు", "12V 9Ah బైక్ బ్యాటరీ, హెడ్‌లైట్, హారన్, 2 చిన్న 12V లైట్లు, ప్రతిదానికీ ఫ్యూజ్, స్విచ్"},
            {"🪜 మెట్ల లైట్", "మెట్ల లైట్: కింద ఒక టూ-వే స్విచ్, పైన ఒక టూ-వే స్విచ్, ఒక LED బల్బ్, రెండు చోట్ల నుంచి ఆన్ ఆఫ్, MCB తో"},
            {"🔔 డోర్ బెల్", "9V బ్యాటరీ, ఒక స్విచ్, ఒక బజర్, దానితో పాటు 470 ఓమ్ రెసిస్టర్ తో ఒక ఎరుపు LED"},
            {"💧 నీళ్ళ మోటార్", "ఇంటి కరెంట్ నుంచి 750W నీళ్ళ మోటార్, 16A MCB, స్విచ్, పక్కన ఒక చిన్న LED బల్బ్ ఇండికేటర్"}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs p;
    private FrameLayout pageBox;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private TextView titleView, chip, status, go, micBtn, cancelBtn, stopBtn;
    private HorizontalScrollView ideas;
    private EditText input;
    /** ⛶ Only the board / drawing on the screen, and 🔄 turned sideways: both by his tap, both undone the same way. */
    private boolean full;
    private LinearLayout topBar, bottomBar;
    private TextView turnBtn;

    private volatile Job job;
    /** The board as the page last reported it (with its switches and battery levels), its title, and how many changes since it came. */
    private JSONObject board;
    private String title = "";
    private int edits;
    /** The parts and wires of the board last saved to / opened from the shelf (an unchanged one is not saved again). */
    private String shelfShape = "";
    /** An AI board just sent to the page: saved to the shelf once the page has cleaned it. */
    private JSONObject pendingShelf;
    /** His words sent to the page (₹0 edit or not): id → {words, by voice}. */
    private final Map<String, Object[]> asked = new HashMap<>();
    /** Pictures and reports asked of the page: req → what for ("share", "thumb:<at>"). */
    private final Map<String, String> reqs = new HashMap<>();
    private int seq;
    private Runnable backTimeout;

    // 🎙️ the lab's own ears (the workshop's), ✋ a paid job heard by voice waits 3 seconds
    private WorkshopVoice ear;
    private final Runnable wakeR = () -> { if (ear != null && front && !destroyed) ear.wake(); };
    private String waiting;
    private Runnable waitGo;
    private long waitSince;

    /** One AI answer being made: a new board, a change to the one on the screen, or a board from a photo. */
    private static final class Job {
        final String what, heard;
        final long at = System.currentTimeMillis();
        final Http.Cancel cancel = new Http.Cancel();
        final Workshop.Engine engine;
        /** The board on the screen for a change (null = a new board); the photo (base64, never kept). */
        final String current;
        /** (G2) a message of its own (a camera scan), else made from the words. */
        String custom;
        /** 📐 (G2.1) board / both / sld (a photo made into a board, the drawing like it, or both); fromSld: a board for this saved drawing. */
        String mode = CircuitLab.MODE_BOARD;
        JSONObject sld;
        volatile String photo;
        final boolean withPhoto;
        volatile boolean stopped;
        Job(String what, String heard, Workshop.Engine engine, String current, String photo) {
            this.what = what; this.heard = heard; this.engine = engine; this.current = current; this.photo = photo; this.withPhoto = photo != null;
        }
    }

    // ================================================================ the screen

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        Usage.init(this);
        getWindow().setStatusBarColor(0xFF0E141C);
        getWindow().setNavigationBarColor(0xFF07080B);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "⚡ సర్క్యూట్ ల్యాబ్ ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        build();
        newPage();
        ear = new WorkshopVoice(owner(), p);
        Intent i = getIntent();
        // brought back after Android closed it, or opened again from recent apps: never ask (and pay) a second time
        if (i != null && (b != null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0)) { i.removeExtra(EXTRA_WHAT); i.removeExtra(EXTRA_CHANGE); i.removeExtra(EXTRA_SCAN_PARTS); }
        handle(i);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String what = i.getStringExtra(EXTRA_WHAT), change = i.getStringExtra(EXTRA_CHANGE), tab = i.getStringExtra(EXTRA_TAB);
        boolean shelf = i.getBooleanExtra(EXTRA_SHELF, false);
        i.removeExtra(EXTRA_WHAT);
        i.removeExtra(EXTRA_CHANGE);
        i.removeExtra(EXTRA_TAB);
        i.removeExtra(EXTRA_SHELF);
        if (what != null && !what.trim().isEmpty()) {
            final String w0 = what.trim(), h0 = i.getStringExtra(EXTRA_HEARD);
            final boolean s0 = i.getBooleanExtra(EXTRA_SAID, false);
            if (front && ear != null && ear.voiceFlow) waitThen(w0, () -> ask(w0, h0, null, null, s0)); // (asked by voice here: ✋ 3 seconds)
            else ask(w0, h0, null, null, s0);
            return;
        }
        if (change != null && !change.trim().isEmpty()) { toPage(change.trim(), front && ear != null && ear.voiceFlow, true); return; } // (from Jarvis's brain: never handed back to it)
        String scanParts = i.getStringExtra(EXTRA_SCAN_PARTS);
        if (scanParts != null) {
            String scanTitle = i.getStringExtra(EXTRA_SCAN_TITLE);
            i.removeExtra(EXTRA_SCAN_PARTS);
            i.removeExtra(EXTRA_SCAN_TITLE);
            fromScanAsk(scanTitle == null ? "" : scanTitle, scanParts);
            return;
        }
        if (tab != null && !tab.isEmpty()) tab(tab);
        if (shelf) ui.postDelayed(this::shelf, 350);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF07080B);

        // the top: the board's name, which AI makes boards, close
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(6), dp(6));
        top.setBackgroundColor(0xFF0E141C);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "⚡ సర్క్యూట్ ల్యాబ్", 12.5f, Ui.MUTED));
        titleView = Ui.text(this, "", 17, Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(titleView);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        chip = Ui.text(this, "", 11.5f, Ui.CYAN);
        chip.setBackground(Ui.round(this, 0x2216303F, Ui.CYAN_DIM, 999));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        TextView fullBtn = Ui.text(this, "⛶", 20, Ui.TEXT);
        fullBtn.setPadding(dp(12), dp(4), dp(6), dp(4));
        fullBtn.setContentDescription("పూర్తి స్క్రీన్");
        fullBtn.setOnClickListener(v -> setFull(true));
        top.addView(fullBtn);
        turnBtn = Ui.text(this, "🔄", 18, Ui.TEXT);
        turnBtn.setPadding(dp(8), dp(4), dp(6), dp(4));
        turnBtn.setOnClickListener(v -> turn());
        turnLook(false);
        top.addView(turnBtn);
        TextView close = Ui.text(this, "✕", 21, Ui.TEXT);
        close.setPadding(dp(14), dp(4), dp(8), dp(4));
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> leave());
        top.addView(close);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        topBar = top;

        pageBox = new FrameLayout(this);
        root.addView(pageBox, new LinearLayout.LayoutParams(-1, 0, 1));

        // the bottom: what Jarvis is doing, ideas, the buttons, the request box
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(10));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0E141C, 0xFF07080B}));
        status = Ui.text(this, "", 14, Ui.TEXT);
        status.setMaxLines(4);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(2), dp(2), dp(2), dp(4));
        bottom.addView(status);

        LinearLayout ideaRow = new LinearLayout(this);
        ideaRow.setOrientation(LinearLayout.HORIZONTAL);
        ideaRow.setPadding(0, dp(2), 0, dp(2));
        for (String[] idea : IDEAS)
            ideaRow.addView(pill(idea[0], v -> { input.setText(idea[1]); input.setSelection(input.getText().length()); input.requestFocus();
                setStatus("✏️ కావాలంటే మార్చి, ⚡ చెయ్ నొక్కండి · AI " + estimate(false, false)); }));
        ideas = new HorizontalScrollView(this);
        ideas.setHorizontalScrollBarEnabled(false);
        ideas.addView(ideaRow);
        bottom.addView(ideas);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        cancelBtn = pill("✋ వద్దు", v -> cancelWait(true));
        cancelBtn.setVisibility(View.GONE);
        stopBtn = pill("⏹ ఆపు", v -> askStop());
        stopBtn.setVisibility(View.GONE);
        actions.addView(cancelBtn);
        actions.addView(stopBtn);
        actions.addView(pill("📂 దాచినవి", v -> shelf()));
        actions.addView(pill("💾 దాచు", v -> saveAsk()));
        actions.addView(pill("📤 షేర్", v -> shareAsk()));
        actions.addView(pill("💰 ఖర్చు", v -> { String s = CircuitLab.costSaid(this, p); setStatus(s); speak(s, false); }));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        bottom.addView(hs);

        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("ఏ సర్క్యూట్? ఉదా: ఇన్వర్టర్, 4 బల్బులు, 2 ఫ్యాన్లు");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(15f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(3);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 14));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { typed(); return true; }
            return false;
        });
        ask.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView cam = Ui.text(this, "📷", 19, Ui.TEXT);
        cam.setGravity(Gravity.CENTER);
        cam.setContentDescription("కాగితం మీద సర్క్యూట్ ఫోటో");
        cam.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 999));
        cam.setOnClickListener(v -> photoAsk());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(44), dp(44));
        cl.leftMargin = dp(6);
        ask.addView(cam, cl);
        micBtn = Ui.text(this, "🎙️", 19, Ui.TEXT);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setContentDescription("మాట్లాడండి");
        micBtn.setOnClickListener(v -> { if (ear != null) ear.tap(); });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(44), dp(44));
        ml.leftMargin = dp(6);
        ask.addView(micBtn, ml);
        micLook(WorkshopVoice.IDLE);
        go = Ui.text(this, "⚡ చెయ్", 15, 0xFF04131A);
        go.setBackground(Ui.round(this, Ui.CYAN, Ui.CYAN, 14));
        go.setPadding(dp(13), dp(10), dp(13), dp(10));
        go.setOnClickListener(v -> typed());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.leftMargin = dp(6);
        ask.addView(go, gl);
        bottom.addView(ask);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        bottomBar = bottom;
        setContentView(root);
        showChip();
        setStatus("మాటల్లో చెప్పండి లేదా 📷 కాగితం మీద గీసిన సర్క్యూట్ చూపించండి (AI, ఖర్చు ముందే చెబుతాను); "
                + "లేదా బోర్డ్ మీద ➕ భాగాలు నుంచి మీరే కట్టండి (₹0). \"బల్బ్ 2 ఆపు\" లాంటి మాటలు కూడా ₹0.");
        refresh();
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 13.5f, Ui.TEXT);
        t.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 999));
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(7);
        t.setLayoutParams(lp);
        return t;
    }

    private void showChip() {
        String m = Workshop.modelFor(p, Workshop.LAB);
        chip.setText(m.isEmpty() ? "AI ఎంచుకోలేదు" : WorkshopActivity.label(m));
    }

    private String estimate(boolean change, boolean photo) {
        String m = Workshop.modelFor(p, Workshop.LAB);
        if (m.isEmpty()) return "ఎంచుకోలేదు (⚙️ → 🛠️)";
        int size = change && board != null ? forAi(board).length() : 0;
        return "సుమారు " + CircuitLab.aboutMoney(m, size, photo);
    }

    /** 📐 The estimate for one of the photo's choices, or for a board made from the saved drawing (chars = its size). */
    /** " (ఎక్కువలో ఎక్కువ ₹50)": the most one answer can cost, when a model is chosen. */
    private String most(String mode, int chars, boolean photo) {
        String m = Workshop.modelFor(p, Workshop.LAB);
        return m.isEmpty() ? "" : " (ఎక్కువలో ఎక్కువ " + CircuitLab.mostMoney(m, mode, chars, photo) + ")";
    }

    private String estimate(String mode, int chars, boolean photo) {
        String m = Workshop.modelFor(p, Workshop.LAB);
        if (m.isEmpty()) return "ఎంచుకోలేదు (⚙️ → 🛠️)";
        return "సుమారు " + CircuitLab.aboutMoney(m, mode, chars, photo);
    }

    private void refresh() {
        if (go == null) return;
        boolean busy = job != null, empty = CircuitLab.empty(board), wide = wide();
        ideas.setVisibility(!busy && empty && !wide ? View.VISIBLE : View.GONE);
        status.setMaxLines(wide ? 2 : 4);
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        input.setHint(empty ? "ఏ సర్క్యూట్? ఉదా: ఇన్వర్టర్, 4 బల్బులు, 2 ఫ్యాన్లు" : CircuitLab.partCount(board) == 0 ? "📐 బొమ్మ మాత్రమే · కొత్త సర్క్యూట్ చెప్పండి"
                : "మార్పు చెప్పండి: ఉదా: ఇంకో ఫ్యాన్ పెట్టు · బల్బ్ 2 ఆపు");
        titleView.setText(title == null || title.isEmpty() ? (empty ? "ఖాళీ బోర్డ్" : "నా సర్క్యూట్") : title);
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
        String cmd = CircuitLab.command(text);
        if (cmd != null) { command(cmd, byVoice); return; }
        if (job != null) {
            String s = "ఒక సర్క్యూట్ ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        toPage(text, byVoice, false);
    }

    /** His words to the page first: a small change it knows ("బల్బ్ 2 ఆపు") is done there for ₹0; else it sends them back for the AI. */
    private void toPage(String text, boolean byVoice, boolean fromBrain) {
        String id = "a" + (++seq);
        asked.put(id, new Object[]{text, byVoice, fromBrain});
        try { send(json("say").put("text", text).put("id", id)); } catch (Exception ignored) {}
    }

    private void command(String cmd, boolean byVoice) {
        switch (cmd) {
            case "shelf": shelf(); return;
            case "save": saveAsk(); return;
            case "share": shareAsk(); return;
            case "pdf": share("pdf"); return;
            case "cost": { String s = CircuitLab.costSaid(this, p); setStatus(s); speak(s, byVoice); return; }
            case "photo": photoAsk(); return;
            case "board": case "calc": case "home": case "games": case "learn": tab(cmd); return;
            case "close": leave(); return;
            default:
        }
    }

    private void tab(String name) {
        try { send(json("tab").put("name", name)); } catch (Exception ignored) {}
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
                cancelBtn.setVisibility(View.GONE);
                then.run();
            }
        };
        cancelBtn.setVisibility(View.VISIBLE);
        setStatus("✏️ “" + words + "” · AI " + estimate(CircuitLab.partCount(board) > 0, false) + " · 3 సెకన్లలో మొదలుపెడతాను · వద్దంటే ✋ వద్దు");
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

    // ================================================================ 🎙️ the lab's own ears

    private WorkshopVoice.Owner owner() {
        return new WorkshopVoice.Owner() {
            @Override public Activity screen() { return CircuitLabActivity.this; }
            @Override public boolean take(String words) { return tookByVoice(words); }
            @Override public void line(String text) { setStatus(text); }
            @Override public void mic(int state) { micLook(state); }
            @Override public boolean front() { return front && !destroyed; }
            @Override public boolean waitWords(String words) { return waitHeard(words); }
        };
    }

    /** Heard by voice here: a ₹0 screen word, or words about the circuit (to the page). Questions and anything else: the brain. */
    private boolean tookByVoice(String words) {
        String cmd = CircuitLab.command(words);
        if (cmd != null) { command(cmd, true); return true; }
        if (!CircuitLab.labWords(words)) return false;
        said(words, true);
        return true;
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

    // ================================================================ the sealed page

    private void newPage() {
        if (web != null) {
            pageBox.removeView(web);
            try { web.destroy(); } catch (Throwable ignored) {}
        }
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        port = null;
        outbox.clear();
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

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // (the lab never goes anywhere)

            @Override public void onPageFinished(WebView v, String url) {
                if (v == web && url != null && url.startsWith(PAGE)) connect(v);
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> { newPage(); setStatus("ఆగిపోయిన బోర్డ్ పేజీని మళ్ళీ తెరిచాను Sir (బోర్డ్ దాచి ఉంది)."); });
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
        pageBox.addView(w, new FrameLayout.LayoutParams(-1, -1));
        w.loadUrl(PAGE);
        // its start: his kept numbers and the board as he left it (sent as soon as the page is there)
        try {
            JSONObject init = json("init").put("store", CircuitLab.stored(this));
            JSONObject cur = board != null ? board : CircuitLab.current0(this);
            if (cur != null && !CircuitLab.empty(cur)) init.put("board", cur);
            send(init);
            if (full) send(json("full").put("on", true));
        } catch (Exception ignored) {}
    }

    /** Only the lab's own files, from the app; anything else is answered "blocked" here and never leaves the phone. */
    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            if (FILES.contains(name) || SHARED.contains(name)) {
                try {
                    InputStream in = getAssets().open((SHARED.contains(name) ? "workshop/" : "circuit/") + name);
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
                @Override public void onMessage(WebMessagePort pt, WebMessage m) { fromPage(m == null ? null : m.getData()); }
            }, ui);
            v.postWebMessage(new WebMessage("jarvis-port", new WebMessagePort[]{ch[1]}), Uri.parse("https://" + HOST));
            List<String> q = new ArrayList<>(outbox);
            outbox.clear();
            for (String s : q) port.postMessage(new WebMessage(s));
        } catch (Throwable e) {
            port = null;
            setStatus("బోర్డ్ పేజీ తెరవలేకపోయాను Sir: " + e.getMessage());
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

    /** What the page says (data only; every word it carries is shown as plain text). */
    private void fromPage(String data) {
        if (destroyed || data == null || data.length() > 12_000_000) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        switch (m.optString("t")) {
            case "changed": changed(m.optJSONObject("board"), m.optJSONObject("summary")); break;
            case "title": title = Workshop.cap(m.optString("title", ""), 60); openTitle = title; refresh(); break;
            case "store": CircuitLab.store(this, m.optString("key"), m.opt("value")); break;
            case "said": {
                Object[] q = asked.remove(m.optString("id"));
                String s = Workshop.cap(m.optString("text", ""), 200);
                setStatus("✏️ " + s + " · ₹0");
                if (q != null && (Boolean) q[1]) speak(s, true);
                break;
            }
            case "needai": {
                Object[] q = asked.remove(m.optString("id"));
                if (q == null) break;
                String text = (String) q[0];
                boolean voice = (Boolean) q[1];
                JSONObject b = m.optJSONObject("board");
                boolean has = CircuitLab.partCount(b) > 0;
                String cur = has && !CircuitLab.wantsNew(text) ? forAi(b) : null;
                boolean fromBrain = (Boolean) q[2];
                if (voice && !fromBrain && !CircuitLab.wantsChange(text) && ear != null) { ear.toBrain(text); break; } // (not a change to the board: Jarvis answers it, ₹0 here)
                if (voice || fromBrain) waitThen(text, () -> ask(text, text, null, cur, false)); // (paid work not typed here: ✋ 3 seconds, the cost shown)
                else ask(text, text, null, cur, false);
                break;
            }
            case "event": setStatus("🔥 " + Workshop.cap(m.optString("te", ""), 160) + " (బొమ్మలో మాత్రమే)"); break;
            case "won": {
                int st = Math.max(0, Math.min(3, m.optInt("stars", 0)));
                String s = "🧩 " + Workshop.cap(m.optString("te", ""), 60) + " అయింది! " + new String(new char[st]).replace("\0", "⭐") + " బాగా చేశారు Sir.";
                setStatus(s);
                if (ear != null && ear.voiceFlow) speak(s, false);
                break;
            }
            case "file": gotFile(m); break;
            case "report": gotReport(m); break;
            case "full": setFull(m.optBoolean("on")); break;
            case "rotate": turn(); break;
            case "makeboard": makeBoardAsk(); break;
            case "back": {
                if (backTimeout != null) { ui.removeCallbacks(backTimeout); backTimeout = null; }
                if (!m.optBoolean("used")) leave();
                break;
            }
            default:
        }
    }

    /** The board for the AI: title, parts, wires (its switches and battery levels stay on the phone). */
    private static String forAi(JSONObject b) {
        try {
            JSONObject o = new JSONObject().put("title", b.optString("title", ""));
            o.put("parts", b.optJSONArray("parts") == null ? new JSONArray() : b.optJSONArray("parts"));
            o.put("wires", b.optJSONArray("wires") == null ? new JSONArray() : b.optJSONArray("wires"));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** The page's board changed (kept as he left it); a board he worked on that is now replaced goes to the shelf first. */
    private void changed(JSONObject b, JSONObject sum) {
        if (b == null) return;
        JSONObject old = board;
        boolean replaced = old != null && !sharesParts(old, b);
        if (replaced && edits > 0 && CircuitLab.partCount(old) >= 3 && !CircuitLab.shape(old).equals(shelfShape)) {
            try {
                String t = old.optString("title", "");
                CircuitLab.save(this, new JSONObject().put("at", System.currentTimeMillis() - 1).put("title", t.isEmpty() ? "నా సర్క్యూట్" : t)
                        .put("board", old).put("ai", false).put("auto", true));
            } catch (Exception ignored) {}
        }
        edits = replaced || old == null ? 0 : edits + 1;
        board = b;
        if (b.has("title")) { title = Workshop.cap(b.optString("title", ""), 60); openTitle = title; }
        openSummary = summaryLine(sum);
        CircuitLab.keepCurrent(this, b);
        JSONObject ps = pendingShelf;
        if (ps != null && !CircuitLab.empty(b)) {
            pendingShelf = null;
            try {
                ps.put("board", b);
                CircuitLab.save(this, ps);
                shelfShape = CircuitLab.shape(b);
                edits = 0;
                thumbLater(ps.optLong("at"));
            } catch (Exception ignored) {}
        }
        refresh();
    }

    private static boolean sharesParts(JSONObject a, JSONObject b) {
        JSONArray x = a.optJSONArray("parts"), y = b.optJSONArray("parts");
        if (x == null || y == null || x.length() == 0 || y.length() == 0) return x != null && y != null && x.length() == 0 && y.length() == 0;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < x.length(); i++) { JSONObject q = x.optJSONObject(i); if (q != null) ids.add(q.optString("type") + ":" + q.optString("id")); }
        for (int i = 0; i < y.length(); i++) { JSONObject q = y.optJSONObject(i); if (q != null && ids.contains(q.optString("type") + ":" + q.optString("id"))) return true; }
        return false;
    }

    /** "12 parts, load 310 W, battery about 4.5 h, 38 units (₹270) a month" for Jarvis's brain. */
    private static String summaryLine(JSONObject s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        b.append(s.optInt("parts", 0)).append(" parts, load ").append(s.optInt("loadW", 0)).append(" W");
        if (!s.isNull("hours") && s.has("hours")) b.append(", the battery lasts about ").append(s.optDouble("hours", 0)).append(" h");
        if (s.optDouble("units", 0) > 0.05) b.append(", about ").append(s.optDouble("units", 0)).append(" units (₹").append(s.optInt("rupees", 0)).append(") a month");
        return b.toString();
    }

    // ================================================================ asking the AI (a new board, a change, a photo)

    /** current = the board on the screen for a change (null: a new board); photo = base64 JPEG (null: none; never kept). */
    private void ask(String what, String heard, String photo, String current, boolean saidAlready) {
        if (job != null) {
            String s = "ఒక సర్క్యూట్ ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            speak(s, false);
            return;
        }
        Workshop.Engine e;
        try { e = CircuitLab.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); speak(x.getMessage(), false); return; }
        String over = CircuitLab.overLimit(this, p);
        if (over != null) { setStatus(over); speak(over, false); return; }
        Job j = new Job(what, heard, e, current, photo);
        j.custom = nextCustom;
        if (nextMode != null) j.mode = nextMode;
        j.sld = nextSld;
        job = j;
        showChip();
        refresh();
        String money = "సుమారు " + CircuitLab.aboutMoney(e.model, j.mode, j.sld != null ? j.sld.toString().length() : current == null ? 0 : current.length(), photo != null);
        String start = CircuitLab.MODE_BOTH.equals(j.mode) ? "ఫోటో నుంచి బోర్డ్, 📐 బొమ్మ రెండూ గీస్తున్నాను Sir, " : CircuitLab.MODE_SLD.equals(j.mode) ? "ఫోటో లాగా బొమ్మ గీస్తున్నాను Sir, "
                : CircuitLab.MODE_FROM_SLD.equals(j.mode) ? "బొమ్మకి బోర్డ్ కడుతున్నాను Sir, " : photo != null ? "ఫోటోలోని సర్క్యూట్ గీస్తున్నాను Sir, " : "సర్క్యూట్ గీస్తున్నాను Sir, ";
        if (!saidAlready && current == null) speak(start + money + " అవుతుంది.", false);
        setStatus("🧠 " + WorkshopActivity.label(e.model) + doing(j) + "· " + money);
        ticker(j);
        new Thread(() -> run(j), "jarvis-circuit").start();
    }

    /** ⚡ (G2) A camera scan: asked first (an approximate board, the cost), then the AI draws it; the photo is read once and deleted. */
    private void fromScanAsk(String title, String parts) {
        File f = new File(getCacheDir(), SCAN_PHOTO);
        String b64 = null;
        try {
            if (f.isFile() && f.length() > 100 && f.length() < 8_000_000) {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                if (Workshop.jpegOk(b)) b64 = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
            }
        } catch (Exception ignored) {}
        //noinspection ResultOfMethodCallIgnored
        f.delete(); // (his photo is not kept)
        final String photo = b64;
        final String t = title.isEmpty() ? "కెమెరా స్కాన్" : Workshop.cap(title, 40);
        new AlertDialog.Builder(this).setTitle("⚡ \"" + t + "\" బోర్డ్‌గా గీయాలా?")
                .setMessage("కెమెరా చదివిన భాగాలతో" + (photo != null ? ", ఫోటోతో" : "") + " AI ఒక అంచనా బోర్డ్ గీస్తుంది (" + estimate(false, photo != null)
                        + "). ఫోటో నుంచి అన్ని కనెక్షన్లు తెలియవు: అసలు బోర్డ్‌తో సరిచూసుకోవాలి. చిప్స్ (IC) లాంటివి ల్యాబ్‌లో ఉండవు. ఫోటో ఎక్కడా దాచను.")
                .setPositiveButton("⚡ గీయి", (d, w) -> askCustom("📷 " + t, CircuitLab.scanMessage(t, parts), photo))
                .setNegativeButton("వద్దు", (d, w) -> setStatus("సరే Sir, గీయలేదు · ఖర్చు ₹0")).show();
    }

    private void askCustom(String what, String message, String photo) { askMode(what, null, photo, null, null, message); }

    /** A job with a mode of its own (📐) or a message of its own (a scan): taken by the job that ask() makes, before its thread starts. */
    private void askMode(String what, String heard, String photo, String mode, JSONObject sld, String message) {
        nextCustom = message;
        nextMode = mode;
        nextSld = sld;
        try { ask(what, heard, photo, null, false); }
        finally { nextCustom = null; nextMode = null; nextSld = null; }
    }
    private String nextCustom, nextMode;
    private JSONObject nextSld;

    /** What the AI is doing, for the line under the board. */
    private static String doing(Job j) {
        if (CircuitLab.MODE_BOTH.equals(j.mode)) return " బోర్డ్ + 📐 బొమ్మ గీస్తోంది… ";
        if (CircuitLab.MODE_SLD.equals(j.mode)) return " 📐 ఫోటో లాగా గీస్తోంది… ";
        if (CircuitLab.MODE_FROM_SLD.equals(j.mode)) return " 📐 బొమ్మకి బోర్డ్ కడుతోంది… ";
        return j.current != null ? " మార్పు చేస్తోంది… " : j.withPhoto ? " ఫోటో చూస్తోంది… " : " సర్క్యూట్ గీస్తోంది… ";
    }

    private void ticker(Job j) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (job != j || destroyed) return;
                long secs = (System.currentTimeMillis() - j.at) / 1000;
                setStatus("🧠 " + WorkshopActivity.label(j.engine.model) + doing(j) + secs + " సె");
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void run(Job j) {
        Http.CANCEL.set(j.cancel);
        StringBuilder text = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        String failure = null;
        String msg = j.custom != null ? j.custom
                : CircuitLab.MODE_BOTH.equals(j.mode) ? CircuitLab.bothMessage(j.heard)
                : CircuitLab.MODE_SLD.equals(j.mode) ? CircuitLab.sldMessage(j.heard)
                : CircuitLab.MODE_FROM_SLD.equals(j.mode) ? CircuitLab.fromSldMessage(j.what, j.sld)
                : j.photo != null ? CircuitLab.photoMessage(j.heard != null ? j.heard : "")
                : j.current != null ? CircuitLab.editMessage(j.current, j.what) : CircuitLab.buildMessage(j.what, j.heard);
        try {
            Workshop.stream(j.engine, CircuitLab.SYSTEM, msg, j.photo, CircuitLab.maxOut(j.mode), s -> { if (text.length() < 200_000) text.append(s); }, bill);
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Exception x) {
            failure = j.stopped ? "stopped" : Workshop.why(p, x, j.engine.provider);
        } finally {
            j.photo = null; // (his photo went only to this one request; nothing keeps it)
            Http.CANCEL.remove();
        }
        CircuitLab.Answer a = failure == null && !j.stopped ? CircuitLab.parse(text.toString()) : null;
        boolean made = a != null && a.refuse == null && (a.board != null || a.sld != null && (CircuitLab.MODE_BOTH.equals(j.mode) || CircuitLab.MODE_SLD.equals(j.mode)));
        Workshop.spendLab(this, bill.rupees, made && j.current == null);
        final String f = j.stopped ? "stopped" : failure;
        ui.post(() -> done(j, f, a, bill));
    }

    private void done(Job j, String failure, CircuitLab.Answer a, Workshop.Bill bill) {
        if (job == j) job = null;
        if (destroyed) return;
        String cost = Workshop.money(bill.rupees) + (bill.estimated ? " (సుమారు)" : "");
        boolean both = CircuitLab.MODE_BOTH.equals(j.mode), sldOnly = CircuitLab.MODE_SLD.equals(j.mode), fromSld = CircuitLab.MODE_FROM_SLD.equals(j.mode);
        // 📐 what goes on the screen: the board (not for "the drawing only"), and the drawing (the photo's, or the saved one with its links)
        JSONObject newBoard = a == null || sldOnly ? null : a.board;
        JSONObject drawing = a == null ? null : fromSld ? (a.board != null ? CircuitLab.linkSld(j.sld, a.links) : null) : both || sldOnly ? a.sld : null;
        boolean capped = bill.out >= CircuitLab.maxOut(j.mode) - 20; // (written to its limit: the answer's end is missing)
        String said, line;
        if ("stopped".equals(failure)) { said = "ఆపేశాను Sir. ఇప్పటిదాకా ఖర్చు " + cost + "."; line = "⏹ " + said; }
        else if (failure != null) { said = "సర్క్యూట్ రాలేదు Sir: " + failure; line = "✗ " + failure + " · " + cost; }
        else if (a == null && capped) { said = "AI జవాబు చాలా పొడవై హద్దు దగ్గర మధ్యలోనే ఆగిపోయింది Sir, పూర్తిగా ఏదీ రాలేదు. ఖర్చు " + cost + "."; line = "✗ " + said; }
        else if (a == null) { said = "AI జవాబు సరిగా రాలేదు Sir, ఇంకోసారి కొంచెం వేరేగా చెప్పండి. ఖర్చు " + cost + "."; line = "✗ " + said; }
        else if (a.refuse != null) { said = a.refuse; line = "🚫 " + a.refuse; }
        else if (a.cut && j.current != null) { said = "మార్పు జవాబు మధ్యలోనే ఆగిపోయింది Sir, అందుకే బోర్డ్ మార్చలేదు. ఖర్చు " + cost + "."; line = "✗ " + said; }
        else if (newBoard == null && drawing == null && (both || sldOnly || fromSld)) {
            said = (fromSld ? "బోర్డ్ రాలేదు Sir" : sldOnly ? "📐 బొమ్మ రాలేదు Sir" : "బోర్డ్, బొమ్మ రెండూ రాలేదు Sir") + ", ఇంకోసారి ప్రయత్నించండి. ఖర్చు " + cost + ".";
            line = "✗ " + said;
        }
        else if (newBoard == null && drawing == null) { said = a.say + " (" + cost + ")"; line = "💬 " + said; }
        else {
            try {
                if (j.current != null) {
                    send(json("aiedit").put("board", a.board).put("said", a.say));
                } else {
                    JSONObject b = newBoard != null ? new JSONObject(newBoard.toString()) : new JSONObject().put("parts", new JSONArray()).put("wires", new JSONArray());
                    if (drawing != null) b.put("sld", drawing);
                    String t = fromSld && j.what != null && !j.what.isEmpty() ? j.what : a.title.isEmpty() ? Workshop.cap(j.what, 30) : a.title;
                    pendingShelf = new JSONObject().put("at", j.at).put("title", t).put("what", j.what).put("model", j.engine.model)
                            .put("cost", bill.rupees).put("estimated", bill.estimated).put("ai", true).put("photo", j.withPhoto);
                    send(json("load").put("board", b).put("title", t).put("note", a.say).put("spread", true).put("view", drawing != null ? "sld" : "board"));
                    title = t;
                    openTitle = t;
                }
            } catch (Exception ignored) {}
            String part = both && drawing == null ? " (📐 బొమ్మ రాలేదు, బోర్డ్ మాత్రమే వచ్చింది.)"
                    : both && newBoard == null ? " (⚡ బోర్డ్ రాలేదు, 📐 బొమ్మ మాత్రమే వచ్చింది: పైన \"⚡ బోర్డ్ కూడా చెయ్\" తో తర్వాత చేయొచ్చు.)" : "";
            if (a.cut) part += " ⚠️ జవాబు హద్దు దగ్గర మధ్యలో ఆగిపోయింది: వచ్చినంత వరకు చూపిస్తున్నాను, కొన్ని భాగాలు లేకపోవచ్చు.";
            said = (a.say.isEmpty() ? (sldOnly ? "📐 బొమ్మ రెడీ Sir." : "బోర్డ్ రెడీ Sir.") : a.say) + part + " ఖర్చు " + cost + ".";
            line = "✓ " + said;
        }
        refresh();
        setStatus(line);
        if (full) Toast.makeText(this, line, Toast.LENGTH_LONG).show();
        speak(said, true);
    }

    private void askStop() {
        Job j = job;
        if (j == null) return;
        new AlertDialog.Builder(this).setTitle("సర్క్యూట్ తయారీ ఆపాలా?").setMessage("ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది.")
                .setPositiveButton("⏹ ఆపు", (d, w) -> stopJob(j)).setNegativeButton("కొనసాగించు", null).show();
    }

    private void stopJob(Job j) {
        j.stopped = true;
        j.cancel.cancel();
    }

    private void leave() {
        Job j = job;
        if (j == null) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("సర్క్యూట్ ఇంకా తయారవుతోంది")
                .setMessage("మూసేస్తే అది ఆగిపోతుంది (ఇప్పటిదాకా అయిన ఖర్చు పడుతుంది). మూసేయాలా?")
                .setPositiveButton("మూసేయి", (d, w) -> { stopJob(j); finish(); }).setNegativeButton("ఉండు", null).show();
    }

    @Override public void onBackPressed() {
        if (full) { setFull(false); return; }
        if (port == null) { leave(); return; }
        // the page first: its open card, the meter, another tab; it says back if there was nothing to close
        send(json("back"));
        if (backTimeout != null) ui.removeCallbacks(backTimeout);
        backTimeout = () -> { backTimeout = null; leave(); };
        ui.postDelayed(backTimeout, 700);
    }

    // ================================================================ ⛶ full screen, 🔄 sideways (his taps; back leaves full screen first)

    private void setFull(boolean on) {
        if (full == on || topBar == null) return;
        full = on;
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        immersive(on);
        try { send(json("full").put("on", on)); } catch (Exception ignored) {}
        fitSoon();
        if (on) Toast.makeText(this, "⛶ బయటికి రావడానికి: ⤡ లేదా వెనక్కి (back)", Toast.LENGTH_SHORT).show();
    }

    /** 🔄 Sideways and back: the screen follows the phone again when he taps it a second time. */
    private void turn() {
        boolean side = getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        setRequestedOrientation(side ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        turnLook(!side);
        fitSoon();
    }

    private void turnLook(boolean side) {
        if (turnBtn == null) return;
        turnBtn.setBackground(side ? Ui.round(this, 0x2216303F, Ui.CYAN_DIM, 999) : null);
        turnBtn.setContentDescription(side ? "మళ్ళీ మామూలుగా (నిలువుగా)" : "అడ్డంగా తిప్పు");
    }

    private boolean wide() { return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE; }

    /** The page fits the board / drawing to its new size once the screen has settled. */
    private void fitSoon() { ui.postDelayed(() -> { if (!destroyed) send(json("fit")); }, 350); }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (full) immersive(true);
        refresh();
        fitSoon();
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN : 0);
    }

    // ================================================================ 📐 → ⚡ a board for a saved drawing (no photo needed)

    private void makeBoardAsk() {
        if (job != null) { Toast.makeText(this, "ముందు ఇది పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        JSONObject b = board;
        if (!CircuitLab.hasSld(b) || CircuitLab.partCount(b) > 0) return;
        final JSONObject sld = b.optJSONObject("sld");
        final String t = title == null || title.isEmpty() ? "📐 బొమ్మ" : title;
        String est = estimate(CircuitLab.MODE_FROM_SLD, sld.toString().length(), false);
        new AlertDialog.Builder(this).setTitle("⚡ ఈ 📐 బొమ్మకి నడిచే బోర్డ్ చేయాలా?")
                .setMessage("దాచిన బొమ్మ నుంచే (ఫోటో మళ్ళీ అక్కర్లేదు) AI బోర్డ్ కట్టి, బొమ్మలోని VCB లు, మోటార్లు, మీటర్లు దానికి కలుపుతుంది. "
                        + "తర్వాత VCB తాకితే ఆన్ / ఆఫ్ అవుతుంది, రంగులు, A / kV మారతాయి. " + est + most(CircuitLab.MODE_FROM_SLD, sld.toString().length(), false) + ".")
                .setPositiveButton("⚡ చెయ్", (d, w) -> askMode(t, null, null, CircuitLab.MODE_FROM_SLD, sld, null))
                .setNegativeButton("వద్దు", (d, w) -> setStatus("సరే Sir, చేయలేదు · ఖర్చు ₹0")).show();
    }

    // ================================================================ 📷 a circuit drawn on paper, or a single-line diagram (SCADA) photo

    private void photoAsk() {
        if (job != null) { Toast.makeText(this, "ముందు ఇది పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("📷 సర్క్యూట్ ఫోటో")
                .setMessage("కాగితం మీద గీసిన సర్క్యూట్, వైరింగ్ బొమ్మ లేదా SCADA / సింగిల్ లైన్ డయాగ్రామ్ స్క్రీన్ ఫోటో AI కి వెళ్తుంది. "
                        + "ఫోటో తర్వాత ఏమి చేయాలో (⚡ బోర్డ్ / ⚡ + 📐 రెండూ / 📐 ఫోటో లాగా) మీరే ఎంచుకుంటారు; ఒక్కోదాని ఖర్చు అక్కడ చూపిస్తాను. ఫోటో ఎక్కడా దాచను.")
                .setPositiveButton("📷 ఫోటో తీయి", (d, w) -> openCam())
                .setNeutralButton("🖼️ గ్యాలరీ", (d, w) -> openGallery())
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
        if (req != REQ_CAM_PERM) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) openCam();
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
        }, "jarvis-circuit-photo").start();
    }

    /** The photo, his words (optional), and his tap: the photo goes to his AI with the request, and is not kept. */
    private void photoReady(String b64, android.graphics.Bitmap thumb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(dp(220));
        box.addView(iv);
        EditText words = new EditText(this);
        words.setHint("ఏమైనా చెప్పాలా? ఉదా: బ్యాటరీ 12V (ఖాళీ అయినా సరే)");
        words.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(words);
        final AlertDialog[] dlg = new AlertDialog[1];
        String[][] choices = {
                {CircuitLab.MODE_BOARD, "⚡ బోర్డ్ మాత్రమే", "నడిచే బోర్డ్ (తక్కువ ఖర్చు)"},
                {CircuitLab.MODE_BOTH, "⚡ + 📐 రెండూ", "బోర్డ్ + ఫోటో లాగా బొమ్మ, ఒకే ఖర్చులో: VCB తాకితే ఆన్ / ఆఫ్, రంగులు, A / kV మారతాయి"},
                {CircuitLab.MODE_SLD, "📐 ఫోటో లాగా మాత్రమే", "చూడటానికి; తర్వాత \"⚡ బోర్డ్ కూడా చెయ్\" తో నడిపించవచ్చు (ఫోటో మళ్ళీ అక్కర్లేదు)"}};
        for (String[] c : choices) {
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setPadding(dp(12), dp(9), dp(12), dp(9));
            b.setBackground(Ui.round(this, CircuitLab.MODE_BOTH.equals(c[0]) ? 0x2216303F : 0x0FFFFFFF, CircuitLab.MODE_BOTH.equals(c[0]) ? Ui.CYAN_DIM : Ui.LINE2, 12));
            b.addView(Ui.text(this, c[1] + " · " + estimate(c[0], 0, true) + most(c[0], 0, true), 15f, Ui.TEXT));
            b.addView(Ui.text(this, c[2], 12f, Ui.MUTED));
            b.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                String said = words.getText().toString().trim();
                if (CircuitLab.MODE_BOARD.equals(c[0])) ask(said.isEmpty() ? "ఫోటోలోని సర్క్యూట్" : said, said, b64, null, false);
                else askMode(said.isEmpty() ? (CircuitLab.MODE_SLD.equals(c[0]) ? "ఫోటో లాగా బొమ్మ" : "ఫోటోలోని సర్క్యూట్") : said, said, b64, c[0], null, null);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            box.addView(b, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("⚡ ఈ ఫోటోతో ఏమి చేయాలి?").setView(sv)
                .setNegativeButton("వద్దు", (d, w) -> setStatus("సరే Sir, ఫోటో పంపలేదు · ఖర్చు ₹0")).show();
    }

    // ================================================================ 📂 the shelf, 💾 save

    private void saveAsk() {
        if (CircuitLab.empty(board)) { Toast.makeText(this, "బోర్డ్ ఖాళీగా ఉంది", Toast.LENGTH_SHORT).show(); return; }
        EditText name = new EditText(this);
        name.setText(title == null || title.isEmpty() ? "నా సర్క్యూట్" : title);
        name.setSelectAllOnFocus(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(name);
        new AlertDialog.Builder(this).setTitle("💾 ఈ బోర్డ్ దాచు").setView(f)
                .setPositiveButton("దాచు", (d, w) -> save(name.getText().toString().trim())).setNegativeButton("వద్దు", null).show();
    }

    private void save(String name) {
        JSONObject b = board;
        if (b == null) return;
        try {
            long at = System.currentTimeMillis();
            String t = Workshop.cap(name.isEmpty() ? "నా సర్క్యూట్" : name, 40);
            JSONObject copy = new JSONObject(b.toString()).put("title", t);
            CircuitLab.save(this, new JSONObject().put("at", at).put("title", t).put("board", copy).put("ai", false));
            shelfShape = CircuitLab.shape(b);
            edits = 0;
            if (!t.equals(title)) { title = t; openTitle = t; try { send(json("load").put("board", copy).put("title", t)); } catch (Exception ignored) {} }
            thumbLater(at);
            setStatus("💾 దాచాను Sir: \"" + t + "\" · 📂 దాచినవి లో ఉంటుంది.");
        } catch (Exception e) {
            setStatus("దాచలేకపోయాను Sir: " + e.getMessage());
        }
    }

    private void shelf() {
        if (job != null) { Toast.makeText(this, "ముందు ఇది పూర్తవనివ్వండి (లేదా ⏹)", Toast.LENGTH_SHORT).show(); return; }
        List<File> fs = CircuitLab.files(this);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int per = wide ? 4 : 2, n = 0;
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout row = null;
        for (File x : fs) {
            JSONObject g = Workshop.read(x);
            if (g == null || g.optJSONObject("board") == null) continue;
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
            File th = CircuitLab.thumb(this, g.optLong("at"));
            if (th.isFile()) { try { bm = android.graphics.BitmapFactory.decodeFile(th.getPath()); } catch (Throwable ignored) {} }
            if (bm != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(bg);
                iv.setClipToOutline(true);
                pic = iv;
            } else {
                TextView ph = Ui.text(this, "⚡", 34f, Ui.MUTED);
                ph.setGravity(Gravity.CENTER);
                ph.setBackground(bg);
                pic = ph;
            }
            tile.addView(pic, new LinearLayout.LayoutParams(-1, dp(wide ? 100 : 120)));
            TextView tt = Ui.text(this, g.optString("title"), 13.5f, Ui.TEXT);
            tt.setMaxLines(2);
            tt.setPadding(dp(2), dp(4), dp(2), 0);
            tile.addView(tt);
            JSONObject gb = g.optJSONObject("board");
            int np = CircuitLab.partCount(gb);
            tile.addView(Ui.text(this, f.format(new java.util.Date(g.optLong("at"))) + " · " + (np == 0 && CircuitLab.hasSld(gb) ? "📐 బొమ్మ మాత్రమే" : np + " భాగాలు" + (CircuitLab.hasSld(gb) ? " · 📐" : ""))
                    + (g.optBoolean("ai") ? " · AI " + Workshop.money(g.optDouble("cost", 0)) : g.optBoolean("auto") ? " · ఆటో" : " · ✋"), 11f, Ui.MUTED));
            tile.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); open(g); });
            tile.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("🗑️ \"" + g.optString("title") + "\" తీసేయాలా?").setMessage("ఈ దాచిన బోర్డ్ పోతుంది.")
                        .setPositiveButton("తీసేయి", (d, w) -> { CircuitLab.delete(this, g.optLong("at")); if (dlg[0] != null) dlg[0].dismiss(); shelf(); })
                        .setNegativeButton("వద్దు", null).show();
                return true;
            });
            row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (n == 0) { Toast.makeText(this, "ఇంకా దాచిన బోర్డులు లేవు · 💾 దాచు తో దాచండి", Toast.LENGTH_LONG).show(); return; }
        while (row != null && row.getChildCount() < per) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = new AlertDialog.Builder(this).setTitle("📂 దాచిన సర్క్యూట్లు (తెరవడానికి ఖర్చు లేదు)").setView(sv)
                .setNegativeButton("మూసేయి", null).show();
    }

    private void open(JSONObject g) {
        JSONObject b = g.optJSONObject("board");
        if (b == null) return;
        String t = g.optString("title", "");
        shelfShape = CircuitLab.shape(b);
        try { send(json("load").put("board", b).put("title", t)); } catch (Exception ignored) {}
        title = t;
        openTitle = t;
        refresh();
        setStatus("📂 \"" + t + "\" తెరిచాను · ఖర్చు లేదు");
        if (!CircuitLab.thumb(this, g.optLong("at")).isFile()) thumbLater(g.optLong("at"));
    }

    /** 🖼️ The shelf's small picture of a board: the page draws it a moment after it is on the board. */
    private void thumbLater(long at) {
        ui.postDelayed(() -> {
            if (destroyed) return;
            String req = "t" + (++seq);
            reqs.put(req, "thumb:" + at);
            try { send(json("snap").put("req", req).put("w", 480)); } catch (Exception ignored) {}
        }, 700);
    }

    // ================================================================ 📤 a picture or a PDF report (he picks where it goes)

    private void shareAsk() {
        if (CircuitLab.empty(board)) { Toast.makeText(this, "బోర్డ్ ఖాళీగా ఉంది", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("📤 షేర్")
                .setItems(new String[]{"🖼️ బోర్డ్ బొమ్మ (PNG)", "📄 రిపోర్ట్ (PDF): బొమ్మ + లెక్కలు + సేఫ్టీ"}, (d, w) -> share(w == 0 ? "png" : "pdf"))
                .setNegativeButton("వద్దు", null).show();
    }

    private void share(String kind) {
        if (CircuitLab.empty(board)) { setStatus("బోర్డ్ ఖాళీగా ఉంది Sir."); return; }
        String req = "s" + (++seq);
        reqs.put(req, kind);
        setStatus(kind.equals("pdf") ? "📄 రిపోర్ట్ తయారు చేస్తున్నాను…" : "🖼️ బొమ్మ తయారు చేస్తున్నాను…");
        try {
            if (kind.equals("pdf")) send(json("report").put("req", req));
            else send(json("snap").put("req", req).put("w", 1400));
        } catch (Exception ignored) {}
    }

    private void gotFile(JSONObject m) {
        String why = reqs.remove(m.optString("req"));
        if (why == null) return;
        String data = m.optString("data", "");
        if (data.isEmpty()) { if (!why.startsWith("thumb:")) setStatus("బోర్డ్ ఖాళీగా ఉంది Sir."); return; }
        new Thread(() -> {
            byte[] b;
            try { b = android.util.Base64.decode(data, android.util.Base64.DEFAULT); } catch (Exception e) { return; }
            if (!png(b)) return;
            if (why.startsWith("thumb:")) {
                try {
                    long at = Long.parseLong(why.substring(6));
                    File d = CircuitLab.dir(this);
                    if (!d.isDirectory() && !d.mkdirs()) return;
                    if (CircuitLab.byAt(this, at) == null) return; // (deleted meanwhile)
                    try (FileOutputStream o = new FileOutputStream(CircuitLab.thumb(this, at))) { o.write(b); }
                } catch (Exception ignored) {}
                return;
            }
            try {
                Coder.Made made = Coder.save(this, "Jarvis/circuits", Workshop.fileName(title, System.currentTimeMillis() % 1000000 + ".png"), "image/png", b);
                ui.post(() -> shareOut(made, "image/png"));
            } catch (Exception e) {
                ui.post(() -> setStatus("బొమ్మ సేవ్ కాలేదు Sir: " + e.getMessage()));
            }
        }, "jarvis-circuit-file").start();
    }

    private void gotReport(JSONObject m) {
        if (reqs.remove(m.optString("req")) == null) return;
        String t = Workshop.cap(m.optString("title", "సర్క్యూట్"), 60);
        String pic = m.optString("png", "");
        JSONArray ls = m.optJSONArray("lines");
        List<String> lines = new ArrayList<>();
        for (int i = 0; ls != null && i < ls.length() && i < 80; i++) lines.add(Workshop.cap(ls.optString(i, ""), 300));
        new Thread(() -> {
            try {
                byte[] img = pic.isEmpty() ? null : android.util.Base64.decode(pic, android.util.Base64.DEFAULT);
                byte[] pdf = CircuitLab.pdf(t, img != null && png(img) ? img : null, lines);
                Coder.Made made = Coder.save(this, "Jarvis/circuits", Workshop.fileName(t, System.currentTimeMillis() % 1000000 + ".pdf"), "application/pdf", pdf);
                ui.post(() -> shareOut(made, "application/pdf"));
            } catch (Exception e) {
                ui.post(() -> setStatus("రిపోర్ట్ తయారు కాలేదు Sir: " + e.getMessage()));
            }
        }, "jarvis-circuit-pdf").start();
    }

    private static boolean png(byte[] b) {
        return b != null && b.length > 64 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /** Saved in Downloads/Jarvis/circuits; Android's share sheet opens and he picks where it goes (nothing is sent by itself). */
    private void shareOut(Coder.Made m, String mime) {
        if (destroyed) return;
        setStatus("💾 సేవ్ అయింది: " + m.where);
        if (m.uri == null) return;
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, m.uri)
                    .putExtra(Intent.EXTRA_TEXT, "⚡ " + (title == null || title.isEmpty() ? "సర్క్యూట్" : title))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
        } catch (Exception e) {
            setStatus("💾 సేవ్ అయింది: " + m.where + " (షేర్ తెరవలేకపోయాను)");
        }
    }

    // ================================================================ in front, behind, gone

    /** The board on the lab screen while it is in front or was left within the last 10 minutes; "" = open, empty; null = not open. */
    static String openTitle() {
        String t = openTitle;
        long s = stoppedAt;
        return t != null && s > 0 && System.currentTimeMillis() - s > 10 * 60_000L ? null : t;
    }

    static String openSummary() { return openSummary; }

    @Override protected void onStart() {
        super.onStart();
        stoppedAt = 0;
        if (openTitle == null) openTitle = title == null ? "" : title;
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
        if (full) immersive(true);
        WakeService.workshopWake = wakeR; // (🎙️ "Jarvis" here: the lab hears him, no panel over it)
        JarvisAccessibility.bubbleAside(true);
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
        //noinspection ResultOfMethodCallIgnored
        new File(getCacheDir(), SCAN_PHOTO).delete(); // (a scan photo never read stays nowhere)
        openTitle = null;
        openSummary = "";
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

    /** ⚡ (G2) From the Jarvis camera: a scanned board's title, parts and photo (kept in the private cache only until the lab reads it). */
    static void fromScan(Context c, String title, String parts, String jpegB64) {
        File f = new File(c.getCacheDir(), SCAN_PHOTO);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (jpegB64 != null && !jpegB64.isEmpty()) {
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(android.util.Base64.decode(jpegB64, android.util.Base64.DEFAULT)); } catch (Exception ignored) {}
        }
        Intent i = new Intent(c, CircuitLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_SCAN_TITLE, title == null ? "" : title).putExtra(EXTRA_SCAN_PARTS, parts == null ? "" : parts);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Opens the lab (from ⚙️, Features, a tool): a circuit to make, a change in words, the shelf, a tab; or just the board as he left it. */
    static void open(Context c, String what, String heard, boolean saidAlready) {
        Intent i = new Intent(c, CircuitLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null && !what.trim().isEmpty()) i.putExtra(EXTRA_WHAT, what.trim()).putExtra(EXTRA_HEARD, heard).putExtra(EXTRA_SAID, saidAlready);
        c.startActivity(i);
    }
}
