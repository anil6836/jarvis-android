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
    static final String EXTRA_WHAT = "what", EXTRA_HEARD = "heard", EXTRA_SAID = "said", EXTRA_OPEN_LAST = "open_last";

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
        /** Written by the build's thread, read by the screen: one lock (iterate inside synchronized (parts)). */
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
            Toast.makeText(this, "🛠️ వర్క్‌షాప్ ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        build();
        newStage();
        Intent i = getIntent();
        // brought back after Android closed it, or opened again from recent apps: never build (and pay) a second time
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
            start(what.trim(), i.getStringExtra(EXTRA_HEARD), i.getBooleanExtra(EXTRA_SAID, false));
            i.removeExtra(EXTRA_WHAT); // (a rotation or a return to the screen must not build it again)
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
        bottom.addView(listScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        TextView saved = pill("📂 దాచినవి", v -> savedList());
        TextView cost = pill("💰 ఖర్చు", v -> { String s = Workshop.costSaid(this, p); setStatus(s); Announcer.say(this, s); });
        spinBtn = pill("⏸ తిప్పడం ఆపు", v -> {
            spinning = !spinning;
            try { send(json("spin").put("on", spinning)); } catch (Exception ignored) {}
            spinBtn.setText(spinning ? "⏸ తిప్పడం ఆపు" : "▶️ తిప్పు");
        });
        TextView view = pill("🎯 మధ్యలో", v -> send(json("frame")));
        stopBtn = pill("⏹ ఆపు", v -> askStop());
        stopBtn.setVisibility(View.GONE);
        actions.addView(stopBtn);
        actions.addView(saved);
        actions.addView(cost);
        actions.addView(spinBtn);
        actions.addView(view);
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
        TextView go = Ui.text(this, "🔨 తయారు చెయ్", 15, 0xFF04131A);
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
        // the stage frames the model in the space the bars leave (they are see-through at their soft edges: a little less)
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int h = root.getHeight();
            if (h <= 0 || topBar == null || bottomBar == null) return;
            float t = Math.max(0, topBar.getHeight() - dp(16)) / (float) h, bt = Math.max(0, bottomBar.getHeight() - dp(20)) / (float) h;
            if (Math.abs(t - sentTop) < 0.01f && Math.abs(bt - sentBottom) < 0.01f) return;
            sentTop = t;
            sentBottom = bt;
            try { send(json("inset").put("top", t).put("bottom", bt)); } catch (Exception ignored) {}
        });
        showChip();
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
        start(s, s, false);
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
        try { send(json("part").put("id", id).put("code", code)); } catch (Exception ignored) {}
    }

    /** "ok" / "err" for a part, from the stage (on the main thread). */
    private void fromStage(String data) {
        if (data == null) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        String t = m.optString("t"), id = m.optString("id");
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

    private void start(String what, String heard, boolean saidAlready) {
        if (job != null) {
            String s = "ఒక బొమ్మ ఇంకా తయారవుతోంది Sir. అది అయ్యాక చెప్పండి, లేదా ⏹ ఆపు నొక్కండి.";
            setStatus(s);
            Announcer.say(this, s);
            return;
        }
        Workshop.Engine e;
        try { e = Workshop.engine(p); }
        catch (Workshop.Problem x) { setStatus(x.getMessage()); Announcer.say(this, x.getMessage()); return; }
        String over = Workshop.overLimit(this, p);
        if (over != null) { setStatus(over); Announcer.say(this, over); return; }
        Job j = new Job(what, heard, e);
        job = j;
        onStage.clear();
        synchronized (acks) { acks.clear(); }
        rows.clear();
        list.removeAllViews();
        send(json("reset"));
        titleView.setText(what);
        showChip();
        stopBtn.setVisibility(View.VISIBLE);
        if (!saidAlready) Announcer.say(this, "బెంచ్ మీద పెడుతున్నాను Sir.");
        setStatus("🧠 " + label(e.model) + " డిజైన్ ఆలోచిస్తోంది…");
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (job != j || destroyed) return;
                long secs = (System.currentTimeMillis() - j.at) / 1000;
                if (j.parts.isEmpty()) {
                    setStatus("🧠 " + label(j.engine.model) + " డిజైన్ ఆలోచిస్తోంది… " + secs + " సె");
                    if (secs >= j.nextLine) { // (one short line about every ten seconds: thinking never looks like a hang)
                        j.nextLine = secs + 10;
                        Announcer.say(WorkshopActivity.this, WAITING[j.waitLine++ % WAITING.length]);
                    }
                }
                ui.postDelayed(this, 1000);
            }
        }, 1000);
        new Thread(() -> run(j), "jarvis-workshop").start();
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
            Workshop.stream(j.engine, Workshop.SYSTEM, Workshop.buildMessage(j.what, j.heard), Workshop.MAX_OUT, new Workshop.Sink() {
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
        if (ok > 0) {
            try {
                JSONArray ps = new JSONArray();
                List<Part> done;
                synchronized (j.parts) { done = new ArrayList<>(j.parts.values()); }
                for (Part pt : done) if (pt.ok) ps.put(new JSONObject().put("id", pt.id).put("status", pt.status).put("code", pt.code));
                JSONObject saved = new JSONObject().put("at", j.at).put("title", j.title != null ? j.title : j.what).put("what", j.what)
                        .put("model", j.engine.model).put("parts", ps).put("say", j.say == null ? "" : j.say).put("cost", j.rupees)
                        .put("estimated", j.estimated).put("repairs", j.repairs).put("seconds", secs)
                        .put("first_part_seconds", j.firstPartAt == 0 ? -1 : (j.firstPartAt - j.at) / 1000)
                        .put("stopped", j.stopped || failure != null);
                Workshop.save(this, saved);
            } catch (Exception ignored) {}
        }
        final String words = said;
        final String line = (j.refused != null ? "🚫 " : ok > 0 ? "✓ " : "✗ ") + (j.refused != null ? j.refused
                : ok + "/" + all + " భాగాలు · " + cost + " · " + (secs / 60 > 0 ? secs / 60 + " ని " : "") + secs % 60 + " సె"
                + (failure != null && !"stopped".equals(failure) ? "\n" + failure : ""));
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            stopBtn.setVisibility(View.GONE);
            setStatus(line);
            Announcer.say(this, words);
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
        JSONArray ps = b.optJSONArray("parts");
        if (ps == null) return;
        onStage.clear();
        rows.clear();
        list.removeAllViews();
        send(json("reset"));
        for (int i = 0; i < ps.length(); i++) {
            JSONObject pt = ps.optJSONObject(i);
            if (pt == null) continue;
            String id = pt.optString("id"), code = pt.optString("code");
            onStage.put(id, code);
            sendPart(id, code);
            row(id, "✓ " + pt.optString("status", id), Ui.OK);
        }
        titleView.setText(b.optString("title"));
        chip.setText(label(b.optString("model", "")));
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

    @Override protected void onResume() {
        super.onResume();
        showChip();
        send(json("resume"));
    }

    @Override protected void onPause() {
        super.onPause();
        send(json("pause"));
    }

    @Override protected void onDestroy() {
        destroyed = true;
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

    /** Opens the workshop (from ⚙️, a tool, the brain): a build to start, or the last saved one. */
    static void open(Context c, String what, String heard, boolean saidAlready) {
        Intent i = new Intent(c, WorkshopActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null && !what.trim().isEmpty()) i.putExtra(EXTRA_WHAT, what.trim()).putExtra(EXTRA_HEARD, heard).putExtra(EXTRA_SAID, saidAlready);
        else i.putExtra(EXTRA_OPEN_LAST, true);
        c.startActivity(i);
    }
}
