package com.anil.jarvis;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
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
 * 🔌 PCB స్టూడియో (Anil, 23 Nov: "1 నుంచి 18 వరకు అన్ని చేయి"; build 1): real printed circuit boards on his phone — ready boards,
 * the schematic (each part explained in Telugu), the PCB (auto-route, ground fill), the factory checks, the board in 3D, the
 * factory files (Gerber .zip) and the numbers. All our own code in a sealed WebView (its own files only, no network, no keys);
 * ₹0, no AI in this build. The .zip is saved in Downloads/Jarvis/pcb; sharing it and opening a factory's site happen only on his
 * tap, and he logs in, uploads and pays there himself (Jarvis never orders or pays). Low-voltage DC boards only. Phone only.
 * <p>
 * Build 2 (24 Nov, "Build 2 చేయి"): 💡 his idea (typed / said) or a photo of a circuit drawn on paper → the parts and their joins
 * from the studio's AI (his pick; the cost shown first; his tap, or ✋ 3 seconds when asked by voice; the shared monthly limit);
 * the page places, routes and checks it with its own code. 🗣️ his words change the open board (small changes on the page, ₹0;
 * the rest by the AI, cost first). ✅ the checks' problems explained by the AI with part moves he applies himself. The photo goes
 * only with that one request and is never kept; the page never sees a key and never talks to the network.
 */
public class PcbActivity extends Activity {
    static final String EXTRA_TAB = "pcb_tab", EXTRA_PROJECT = "pcb_project";
    /** (from Jarvis's brain) a new board of these words (waits ✋ 3 seconds here); his words about the open board. */
    static final String EXTRA_WHAT = "pcb_what", EXTRA_HEARD = "pcb_heard", EXTRA_CHANGE = "pcb_change";
    private static final int REQ_CAM = 81, REQ_GAL = 82, REQ_CAM_PERM = 83;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/pcb/";
    private static final String PAGE = "https://" + HOST + PATH + "pcb.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("pcb.html", "pcb.js", "pcbparts.js", "pcbcore.js", "pcbfab.js", "pcbprojects.js", "pcbbuy.js", "pcbai.js", "pcb3d.js", "pcbsim.js", "pcbkicad.js"));
    /** 🧊 The 3D view uses the workshop's bundled three.js (one copy in the app), served here under the studio's own path. */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList("three.module.min.js", "OrbitControls.js"));
    /** The factories' own sites (opened in his browser only when he taps 🌐; nothing is sent to them by Jarvis). */
    private static final Map<String, String> SITES = new HashMap<>();
    static {
        SITES.put("jlc", "https://jlcpcb.com/");
        SITES.put("pcbway", "https://www.pcbway.com/");
        SITES.put("lion", "https://www.lioncircuits.com/");
        SITES.put("pcbpower", "https://www.pcbpower.com/");
    }
    /** 🛒 (24 Nov) Indian shops: a search there for a part (his tap only), and the part pages we looked up (only these two sites). */
    private static final Map<String, String> SEARCH = new HashMap<>();
    static {
        SEARCH.put("robu", "https://robu.in/?post_type=product&s=");
        SEARCH.put("robocraze", "https://robocraze.com/search?q=");
        SEARCH.put("amazon", "https://www.amazon.in/s?k=");
        SEARCH.put("flipkart", "https://www.flipkart.com/search?q=");
        SEARCH.put("probots", "https://probots.co.in/catalogsearch/result/?q=");
    }
    private static final Set<String> SHOP_HOSTS = new HashSet<>(Arrays.asList("robu.in", "robocraze.com"));
    private static final Set<String> TABS = new HashSet<>(Arrays.asList("home", "idea", "sch", "pcb", "drc", "sim", "d3", "fab", "calc"));
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(Pcb.NEW, Pcb.PHOTO, Pcb.EDIT, Pcb.HELP));
    private static final Set<String> PROJECTS = new HashSet<>(Arrays.asList("flasher", "usb", "soil", "esp32", "irboard"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private Coder.Made lastZip;
    private boolean lastKicad; // the last zip: KiCad files for the laptop (else the factory's Gerber files)
    private Runnable backTimeout;
    private Prefs p;
    private boolean front;

    /** One AI request at a time (a new board, a change, a photo, the checks' help). */
    private static final class Job {
        final String kind, words, design;
        final boolean voice;
        final Workshop.Engine engine;
        final long at = System.currentTimeMillis();
        final Http.Cancel cancel = new Http.Cancel();
        volatile String photo;
        volatile boolean stopped;
        Job(String kind, String words, String design, Workshop.Engine engine, boolean voice, String photo) {
            this.kind = kind; this.words = words; this.design = design; this.engine = engine; this.voice = voice; this.photo = photo;
        }
    }
    private Job job;
    /** 📷 his photo (JPEG, base64): in memory only, until it goes with the one request he taps (or ✕ / leaving drops it). */
    private volatile String photo;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "🔌 PCB స్టూడియో ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        p = new Prefs(this);
        Usage.init(this);
        getWindow().setStatusBarColor(0xFF0E141C);
        getWindow().setNavigationBarColor(0xFF07080B);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        setContentView(root);
        ear = new WorkshopVoice(owner(), p);
        // brought back after Android closed it, or opened again from recent apps: open what he had, never a second copy of a ready
        // board, never ask (and pay) a second time
        Intent i = getIntent();
        boolean again = b != null || (i != null && (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0);
        newPage(again ? null : i);
        if (i != null) { i.removeExtra(EXTRA_TAB); i.removeExtra(EXTRA_PROJECT); i.removeExtra(EXTRA_WHAT); i.removeExtra(EXTRA_HEARD); i.removeExtra(EXTRA_CHANGE); }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String t = tabOf(i), pr = projectOf(i);
        JSONObject ai = aiOf(i);
        i.removeExtra(EXTRA_TAB);
        i.removeExtra(EXTRA_PROJECT);
        i.removeExtra(EXTRA_WHAT);
        i.removeExtra(EXTRA_HEARD);
        i.removeExtra(EXTRA_CHANGE);
        try {
            if (pr != null) send(json("project").put("id", pr));
            else if (t != null) send(json("tab").put("tab", t));
            if (ai != null) send(ai);
        } catch (Exception ignored) {}
    }

    /** From Jarvis's brain: a new board of his words (the page shows the 💡 card and its cost; ✋ 3 seconds), or his words about the open board. */
    private static JSONObject aiOf(Intent i) {
        if (i == null) return null;
        String what = i.getStringExtra(EXTRA_WHAT), change = i.getStringExtra(EXTRA_CHANGE);
        try {
            if (what != null && !what.trim().isEmpty()) {
                String heard = i.getStringExtra(EXTRA_HEARD);
                return json("idea").put("s", Workshop.cap(what.trim(), 2000)).put("heard", heard == null ? "" : Workshop.cap(heard.trim(), 2000)).put("go", true);
            }
            if (change != null && !change.trim().isEmpty()) return json("change").put("s", Workshop.cap(change.trim(), 1000));
        } catch (Exception ignored) {}
        return null;
    }

    private static String tabOf(Intent i) { String t = i == null ? null : i.getStringExtra(EXTRA_TAB); return t != null && TABS.contains(t) ? t : null; }

    private static String projectOf(Intent i) { String p = i == null ? null : i.getStringExtra(EXTRA_PROJECT); return p != null && PROJECTS.contains(p) ? p : null; }

    // ================================================================ the page (only its own files; nothing goes out)

    private void newPage(Intent start) {
        if (web != null) { root.removeView(web); try { web.destroy(); } catch (Throwable ignored) {} }
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        port = null;
        outbox.clear();
        WebView w = new WebView(this);
        web = w;
        w.setBackgroundColor(Color.TRANSPARENT);
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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // (the studio never goes anywhere)
            @Override public void onPageFinished(WebView v, String url) { if (v == web && url != null && url.startsWith(PAGE)) connect(v); }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> { if (!destroyed) newPage(null); });
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
        root.addView(w, new FrameLayout.LayoutParams(-1, -1));
        w.loadUrl(PAGE);
        final String tab = tabOf(start), project = projectOf(start);
        final JSONObject ai = aiOf(start);
        // its start: his boards, and the one he had open (read off the main thread; sent when the page is there)
        new Thread(() -> {
            try {
                JSONObject init = json("init").put("list", Pcbs.listJson(this));
                String last = Pcbs.last(this);
                JSONObject cur = last == null ? null : Pcbs.load(this, last);
                if (cur != null) init.put("cur", cur);
                if (tab != null) init.put("tab", tab);
                send(init);
                if (project != null) send(json("project").put("id", project));
                if (ai != null) send(ai);
            } catch (Exception ignored) {}
        }, "jarvis-pcb-init").start();
    }

    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            if (FILES.contains(name) || SHARED.contains(name)) {
                try {
                    InputStream in = getAssets().open((SHARED.contains(name) ? "workshop/" : "pcb/") + name);
                    return new WebResourceResponse(name.endsWith(".html") ? "text/html" : "text/javascript", "utf-8", 200, "OK", h, in);
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
            Toast.makeText(this, "PCB పేజీ తెరవలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static JSONObject json(String t) {
        try { return new JSONObject().put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    /** (any thread) to the page; queued until it is there. */
    private void send(JSONObject m) {
        if (m == null) return;
        final String s = m.toString();
        Runnable r = () -> {
            if (destroyed) return;
            if (port == null) { outbox.add(s); return; }
            try { port.postMessage(new WebMessage(s)); } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    private void say(String s) { try { send(json("say").put("s", s)); } catch (Exception ignored) {} }

    /** What the page says (data only; every word it carries is shown as plain text). */
    private void fromPage(String data) {
        if (destroyed || data == null || data.length() > 30_000_000) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        switch (m.optString("t")) {
            case "save": {
                JSONObject d = m.optJSONObject("d");
                new Thread(() -> {
                    if (Pcbs.save(this, d)) Pcbs.setLast(this, d.optString("id"));
                    else ui.post(() -> say("బోర్డ్ దాచలేకపోయాను (చాలా పెద్దది / పాడైంది)."));
                    sendList();
                }, "jarvis-pcb-save").start();
                break;
            }
            case "remove": {
                String id = m.optString("id");
                new Thread(() -> { Pcbs.remove(this, id); if (id.equals(Pcbs.last(this))) Pcbs.setLast(this, null); sendList(); }, "jarvis-pcb-remove").start();
                break;
            }
            case "open": {
                String id = m.optString("id");
                new Thread(() -> {
                    JSONObject d = Pcbs.load(this, id);
                    if (d == null) { ui.post(() -> say("ఆ బోర్డ్ తెరవలేకపోయాను.")); return; }
                    Pcbs.setLast(this, id);
                    try { send(json("design").put("d", d)); } catch (Exception ignored) {}
                }, "jarvis-pcb-open").start();
                break;
            }
            case "zip": saveZip(m.optString("name"), m.optString("data")); break;
            case "share": shareZip(); break;
            case "site": openSite(m.optString("fab")); break;
            case "shopsearch": shopSearch(m.optString("shop"), m.optString("q")); break;
            case "shop": shopPage(m.optString("url")); break;
            case "sharetext": shareText(m.optString("text")); break;
            case "close": finish(); break;
            // ---- 🤖 the studio's AI: the cost first, then only on his tap (or ✋ 3 seconds when it was asked by voice)
            case "aiquote": quote(m.optString("kind"), m.optString("words"), m.optInt("chars")); break;
            case "aiask": ask(m.optString("kind"), m.optString("words"), m.optString("design", null), m.optBoolean("voice")); break;
            case "aistop": { Job j = job; if (j != null) { j.stopped = true; j.cancel.cancel(); } break; }
            case "aicancel": cancelWait(true); break;
            case "pickmodel": pickModel(m.optString("kind"), m.optString("words"), m.optInt("chars")); break;
            case "photo": if ("cam".equals(m.optString("from"))) openCam(); else openGallery(); break;
            case "photodrop": photo = null; break;
            // ---- 🎙️ his voice here
            case "mic": if (ear != null) ear.tap(); break;
            case "dictate": // 🎤 in the idea box: his words only written out (Telugu or English, the way Jarvis hears him)
                if (ear == null) break;
                if (ear.dictating()) { ear.tap(); break; }
                try { send(json("dictst").put("on", true)); } catch (Exception ignored) {}
                ear.dictate(w -> { try { send(json("dictated").put("s", w == null ? "" : w.trim())); } catch (Exception ignored) {} });
                break;
            case "vdone": {
                String w = m.optString("s", ""), said = m.optString("say", "");
                if (ear == null) break;
                if (m.optBoolean("brain") && !w.trim().isEmpty()) ear.toBrain(w); // (not about the board: the same Jarvis as the panel answers here)
                else if (!said.isEmpty() && (m.optBoolean("voice") || ear.voiceFlow) && front) ear.say(said, false);
                break;
            }
            case "back": {
                if (backTimeout != null) { ui.removeCallbacks(backTimeout); backTimeout = null; }
                if (!m.optBoolean("used")) finish();
                break;
            }
            default: break;
        }
    }

    private void sendList() {
        try { send(json("designs").put("list", Pcbs.listJson(this))); } catch (Exception ignored) {}
    }

    // ================================================================ 🏭 the factory files: saved in Downloads/Jarvis/pcb, shared only on his tap

    private void saveZip(String name, String b64) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "");
        if (!n.endsWith(".zip") || n.length() < 5 || n.length() > 80) n = "jarvis-pcb-" + (System.currentTimeMillis() % 1000000) + ".zip";
        final String fileName = n;
        if (b64 == null || b64.isEmpty() || b64.length() > 28_000_000) { say("ఫైల్స్ తయారు కాలేదు."); return; }
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (bytes.length < 22 || bytes[0] != 'P' || bytes[1] != 'K') throw new IllegalStateException("zip కాదు");
                Coder.Made made = Coder.save(this, "Jarvis/pcb", fileName, "application/zip", bytes);
                ui.post(() -> {
                    if (destroyed) return;
                    lastZip = made;
                    lastKicad = fileName.contains("-kicad-");
                    try { send(json("saved").put("where", made.where)); } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                ui.post(() -> say("సేవ్ కాలేదు: " + e.getMessage()));
            }
        }, "jarvis-pcb-zip").start();
    }

    private void shareZip() {
        Coder.Made m = lastZip;
        if (m == null) { say("ముందు 📦 ఫైల్స్ తయారు చేయండి."); return; }
        if (m.uri == null) { say("ఫైల్ ఇక్కడ ఉంది: " + m.where); return; }
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, m.uri)
                    .putExtra(Intent.EXTRA_TEXT, lastKicad ? "💻 KiCad ఫైల్స్ (PCB + స్కీమాటిక్)" : "🔌 PCB (Gerber) ఫైల్స్").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
        } catch (Exception e) {
            say("పంపలేకపోయాను: " + e.getMessage());
        }
    }

    /** The factory's own site in his browser (his tap only). He logs in, uploads and pays there himself. */
    private void openSite(String fab) {
        String url = SITES.get(fab);
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            say("బ్రౌజర్ తెరవలేకపోయాను. " + url + " ని బ్రౌజర్‌లో తెరవండి.");
        }
    }

    // ================================================================ 🛒 where to buy: his browser, his tap; nothing is bought by Jarvis

    /** A shop's own search for one part (the words are made by the page from the part's kind and value). */
    private void shopSearch(String shop, String q) {
        String base = SEARCH.get(shop);
        String words = q == null ? "" : q.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (base == null || words.isEmpty() || words.length() > 120) return;
        try {
            browse(base + java.net.URLEncoder.encode(words, "UTF-8"));
        } catch (Exception e) {
            say("వెతకలేకపోయాను: " + e.getMessage());
        }
    }

    /** A part page we looked up: only https pages on the two shops' own sites. */
    private void shopPage(String url) {
        Uri u;
        try { u = Uri.parse(url == null ? "" : url); } catch (Exception e) { return; }
        String host = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (u == null || !"https".equals(u.getScheme()) || !SHOP_HOSTS.contains(host) || url.length() > 400) return;
        browse(url);
    }

    private void browse(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            say("బ్రౌజర్ తెరవలేకపోయాను.");
        }
    }

    /** 📋 The parts list as text, sent where he chooses (his tap). */
    private void shareText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 6000) return;
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t);
            startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
        } catch (Exception e) {
            say("పంపలేకపోయాను: " + e.getMessage());
        }
    }

    // ================================================================ 🤖 the studio's AI (his pick; the cost first; the shared monthly limit)

    private void quote(String kind, String words, int chars) {
        if (!KINDS.contains(kind)) kind = Pcb.NEW;
        try {
            JSONObject r = json("aiquote").put("kind", kind);
            String m = Workshop.modelFor(p, Workshop.PCB);
            if (m.isEmpty()) {
                r.put("ok", false).put("model", "").put("why", "AI ఇంకా ఎంచుకోలేదు Sir: 🤖 మార్చు నొక్కి ఎంచుకోండి (Jarvis తనంతట తానే ఎంచుకోడు).");
            } else {
                r.put("model", WorkshopActivity.label(m)).put("money", Workshop.fine(Pcb.quote(m, kind, words, Math.max(0, chars)))).put("ok", true);
                try { Pcb.engine(p); } catch (Workshop.Problem x) { r.put("ok", false).put("why", x.getMessage()); }
                String over = Pcb.overLimit(this, p);
                if (over != null) r.put("ok", false).put("why", over);
            }
            r.put("month", "ఈ నెల " + Workshop.money(Workshop.spent(this)) + " / హద్దు ₹" + p.workshopLimit());
            send(r);
        } catch (Exception ignored) {}
    }

    private void pickModel(String kind, String words, int chars) {
        final String k = KINDS.contains(kind) ? kind : Pcb.NEW;
        String what = Pcb.HELP.equals(k) ? "✅ చెక్ లో తప్పులకి AI సహాయం ఖర్చు" : Pcb.EDIT.equals(k) ? "ఈ బోర్డ్‌లో ఒక మార్పుకి ఖర్చు" : Workshop.costWhat(Workshop.PCB);
        Models.pick(this, Workshop.studioName(Workshop.PCB) + " AI ఎంచుకోండి", new String[]{Prefs.OPENAI, Prefs.ANTHROPIC, Prefs.GEMINI},
                new String[]{p.openAiKey(), p.anthropicKey(), p.geminiKey()}, Workshop.modelFor(p, Workshop.PCB), what,
                mm -> Workshop.fine(Pcb.quote(mm, k, words, Math.max(0, chars))), it -> {
                    p.setStudioModel(Workshop.PCB, it.id);
                    Toast.makeText(this, "✓ " + Workshop.studioName(Workshop.PCB) + " AI: " + it.id + " (వర్క్‌షాప్ AI మారలేదు)", Toast.LENGTH_LONG).show();
                    quote(k, words, chars);
                });
    }

    private void aiFail(String kind, String why, boolean voice) {
        try { send(json("aidone").put("kind", kind).put("ok", false).put("fail", why).put("cost", "₹0").put("voice", voice)); } catch (Exception ignored) {}
        if (voice) speak(why);
    }

    private void ask(String kind, String words, String design, boolean voice) {
        if (!KINDS.contains(kind)) return;
        if (job != null || waiting != null) { aiFail(kind, "ఒక AI పని ఇంకా నడుస్తోంది Sir. అది అయ్యాక అడగండి, లేదా ⏹ ఆపు నొక్కండి.", voice); return; }
        String w = words == null ? "" : Workshop.cap(words.trim(), 3000);
        if ((Pcb.NEW.equals(kind) || Pcb.EDIT.equals(kind)) && w.isEmpty()) return;
        if ((Pcb.EDIT.equals(kind) || Pcb.HELP.equals(kind)) && (design == null || design.isEmpty() || design.length() > 120_000)) { aiFail(kind, "ఈ బోర్డ్ AI కి పంపలేనంత పెద్దది Sir.", voice); return; }
        if (Pcb.PHOTO.equals(kind) && photo == null) { aiFail(kind, "ఫోటో లేదు Sir: 📷 మళ్ళీ తీయండి / ఎంచుకోండి.", voice); return; }
        Workshop.Engine e;
        try { e = Pcb.engine(p); }
        catch (Workshop.Problem x) { aiFail(kind, x.getMessage(), voice); return; }
        String over = Pcb.overLimit(this, p);
        if (over != null) { aiFail(kind, over, voice); return; }
        if (voice) waitThen(kind, w, design, e);
        else start(kind, w, design, e, false);
    }

    private void start(String kind, String words, String design, Workshop.Engine e, boolean voice) {
        String ph = null;
        if (Pcb.PHOTO.equals(kind)) { ph = photo; photo = null; } // (his photo goes only with this one request)
        if (Pcb.PHOTO.equals(kind) && ph == null) { aiFail(kind, "ఫోటో లేదు Sir: 📷 మళ్ళీ తీయండి / ఎంచుకోండి.", voice); return; }
        Job j = new Job(kind, words, design, e, voice, ph);
        job = j;
        String money = Workshop.fine(Pcb.quote(e.model, kind, words, design == null ? 0 : design.length()));
        try { send(json("aistart").put("kind", kind).put("model", WorkshopActivity.label(e.model)).put("money", money)); } catch (Exception ignored) {}
        if (voice) speak((Pcb.HELP.equals(kind) ? "తప్పులు చూస్తున్నాను Sir, " : Pcb.EDIT.equals(kind) ? "బోర్డ్ మారుస్తున్నాను Sir, " : "బోర్డ్ గీస్తున్నాను Sir, ")
                + "సుమారు " + money + " అవుతుంది.");
        new Thread(() -> run(j), "jarvis-pcb-ai").start();
    }

    private void run(Job j) {
        Http.CANCEL.set(j.cancel);
        StringBuilder text = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        String failure = null;
        boolean help = Pcb.HELP.equals(j.kind);
        int max = help ? Pcb.HELP_OUT : Pcb.MAX_OUT;
        String msg = help ? Pcb.helpMessage(j.design) : Pcb.EDIT.equals(j.kind) ? Pcb.editMessage(j.design, j.words)
                : Pcb.PHOTO.equals(j.kind) ? Pcb.photoMessage(j.words) : Pcb.buildMessage(j.words);
        long[] told = {0};
        try {
            Workshop.stream(j.engine, help ? Pcb.HELP_SYSTEM : Pcb.SYSTEM, msg, j.photo, max, s -> {
                if (text.length() < 200_000) text.append(s);
                long now = System.currentTimeMillis();
                if (now - told[0] > 700) { told[0] = now; try { send(json("aiprog").put("n", text.length())); } catch (Exception ignored) {} }
            }, bill);
        } catch (CancellationException x) {
            failure = "stopped";
        } catch (Exception x) {
            failure = j.stopped ? "stopped" : Workshop.why(p, x, j.engine.provider);
        } finally {
            j.photo = null; // (nothing keeps his photo)
            Http.CANCEL.remove();
        }
        Pcb.Answer a = failure == null && !j.stopped ? Pcb.parse(text.toString()) : null;
        boolean made = a != null && a.refuse == null && a.json != null && a.json.optJSONArray("parts") != null;
        Workshop.spendPcb(this, bill.rupees, made && (Pcb.NEW.equals(j.kind) || Pcb.PHOTO.equals(j.kind)));
        String cost = Workshop.money(bill.rupees) + (bill.estimated ? " (సుమారు)" : "");
        boolean capped = bill.out >= max - 20;
        final String f = j.stopped ? "stopped" : failure;
        ui.post(() -> {
            if (job == j) job = null;
            if (destroyed) return;
            try {
                JSONObject r = json("aidone").put("kind", j.kind).put("cost", cost).put("voice", j.voice).put("model", WorkshopActivity.label(j.engine.model));
                if ("stopped".equals(f)) r.put("ok", false).put("stopped", true).put("fail", "ఆపేశాను Sir. ఇప్పటిదాకా ఖర్చు " + cost + ".");
                else if (f != null) r.put("ok", false).put("fail", "AI జవాబు రాలేదు Sir: " + f);
                else if (a == null) r.put("ok", false).put("fail", capped ? "AI జవాబు చాలా పొడవై హద్దు దగ్గర మధ్యలోనే ఆగిపోయింది Sir, పూర్తిగా ఏదీ రాలేదు."
                        : "AI జవాబు సరిగా రాలేదు Sir, ఇంకోసారి కొంచెం వేరేగా చెప్పండి.");
                else if (a.refuse != null) r.put("ok", false).put("refuse", a.refuse);
                else r.put("ok", true).put("ans", a.json).put("cut", a.cut);
                send(r);
            } catch (Exception ignored) {}
        });
    }

    // ================================================================ ✋ a paid job asked by voice waits 3 seconds

    private String waiting;
    private Runnable waitGo;
    private long waitSince;

    private void waitThen(String kind, String words, String design, Workshop.Engine e) {
        cancelWait(false);
        waiting = kind;
        waitSince = System.currentTimeMillis();
        String money = Workshop.fine(Pcb.quote(e.model, kind, words, design == null ? 0 : design.length()));
        waitGo = new Runnable() {
            @Override public void run() {
                if (waiting == null || destroyed) return;
                if (ear != null && ear.hearing() && System.currentTimeMillis() - waitSince < 8000) { ui.postDelayed(this, 400); return; }
                waiting = null;
                waitGo = null;
                start(kind, words, design, e, true);
            }
        };
        try { send(json("aiwait").put("kind", kind).put("words", words).put("model", WorkshopActivity.label(e.model)).put("money", money).put("secs", 3)); } catch (Exception ignored) {}
        ui.postDelayed(waitGo, 3000);
        if (ear != null && front) ui.postDelayed(() -> { if (waiting != null && ear != null) ear.listenShort(3); }, 300);
    }

    private boolean waitHeard(String words) {
        if (waiting == null) return false;
        if (Workshop.isCancel(words)) { cancelWait(true); return true; }
        return false;
    }

    private void cancelWait(boolean said) {
        if (waitGo != null) ui.removeCallbacks(waitGo);
        boolean was = waiting != null;
        String kind = waiting;
        waiting = null;
        waitGo = null;
        if (!was) return;
        try { send(json("aicancelled").put("kind", kind).put("said", said)); } catch (Exception ignored) {}
        if (said) speak("సరే Sir, చేయలేదు.");
    }

    // ================================================================ 📷 a photo of a circuit drawn on paper (never kept)

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
        catch (Exception e) { say("కెమెరా యాప్ తెరవలేకపోయాను. 🖼️ గ్యాలరీ నుంచి ఎంచుకోండి."); }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "ఫోటో ఎంచుకోండి"), REQ_GAL); }
        catch (Exception e) { say("గ్యాలరీ తెరవలేకపోయాను"); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_CAM_PERM) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) openCam();
        else say("📷 కెమెరా అనుమతి లేకుండా ఫోటో తీయలేను Sir. 🖼️ గ్యాలరీ నుంచి ఎంచుకోవచ్చు.");
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
            if (bm == null) { ui.post(() -> say("ఫోటో తెరవలేకపోయాను Sir.")); return; }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            final String b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            int side = Math.max(bm.getWidth(), bm.getHeight());
            android.graphics.Bitmap th = android.graphics.Bitmap.createScaledBitmap(bm, Math.max(1, bm.getWidth() * 360 / side), Math.max(1, bm.getHeight() * 360 / side), true);
            java.io.ByteArrayOutputStream small = new java.io.ByteArrayOutputStream();
            th.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, small);
            final String thumb = "data:image/jpeg;base64," + android.util.Base64.encodeToString(small.toByteArray(), android.util.Base64.NO_WRAP);
            ui.post(() -> {
                if (destroyed) return;
                photo = b64;
                try { send(json("photoready").put("thumb", thumb).put("kb", out.size() / 1024)); } catch (Exception ignored) {}
            });
        }, "jarvis-pcb-photo").start();
    }

    // ================================================================ 🎙️ the studio's own ears (the workshop's)

    private WorkshopVoice ear;
    private final Runnable wakeR = () -> { if (ear != null && front && !destroyed) ear.wake(); };

    private WorkshopVoice.Owner owner() {
        return new WorkshopVoice.Owner() {
            @Override public Activity screen() { return PcbActivity.this; }
            /** The page decides (a ₹0 change, a change for the AI, a new board); what it doesn't take comes back for the brain. */
            @Override public boolean take(String words) { try { send(json("vheard").put("s", words)); } catch (Exception ignored) {} return true; }
            @Override public void line(String text) { try { send(json("vline").put("s", text == null ? "" : text)); } catch (Exception ignored) {} }
            @Override public void mic(int state) { try { send(json("vmic").put("st", state)); } catch (Exception ignored) {} }
            @Override public boolean front() { return front && !destroyed; }
            @Override public boolean waitWords(String words) { return waitHeard(words); }
        };
    }

    /** Said only when he is talking with the studio (typed work stays quiet). */
    private void speak(String s) {
        if (s == null || s.trim().isEmpty() || ear == null || !front || destroyed) return;
        ear.say(s, false);
    }

    // ================================================================ in front, behind, gone

    @Override protected void onResume() {
        super.onResume();
        front = true;
        JarvisAccessibility.bubbleAside(true);
        WakeService.workshopWake = wakeR; // (🎙️ "Jarvis" here: the studio hears him, no panel over it)
    }

    @Override protected void onPause() {
        super.onPause();
        front = false;
        JarvisAccessibility.bubbleAside(false);
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        if (ear != null) ear.away();
        if (waiting != null) cancelWait(false); // (he left: nothing is spent on what was heard)
    }

    @Override public void onBackPressed() {
        if (port == null) { finish(); return; }
        send(json("back")); // (the page closes its own card / goes to its first tab first)
        if (backTimeout != null) ui.removeCallbacks(backTimeout);
        backTimeout = () -> { backTimeout = null; if (!destroyed) finish(); };
        ui.postDelayed(backTimeout, 800);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        photo = null;
        if (WakeService.workshopWake == wakeR) WakeService.workshopWake = null;
        if (ear != null) ear.shutdown();
        Job j = job;
        if (j != null) { j.stopped = true; j.cancel.cancel(); }
        ui.removeCallbacksAndMessages(null);
        if (backTimeout != null) ui.removeCallbacks(backTimeout);
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) { try { web.destroy(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    static void open(Context c, String tab, String project) {
        Intent i = new Intent(c, PcbActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tab != null) i.putExtra(EXTRA_TAB, tab);
        if (project != null) i.putExtra(EXTRA_PROJECT, project);
        c.startActivity(i);
    }
}
