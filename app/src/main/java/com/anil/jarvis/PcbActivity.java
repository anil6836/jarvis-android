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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🔌 PCB స్టూడియో (Anil, 23 Nov: "1 నుంచి 18 వరకు అన్ని చేయి"; build 1): real printed circuit boards on his phone — ready boards,
 * the schematic (each part explained in Telugu), the PCB (auto-route, ground fill), the factory checks, the board in 3D, the
 * factory files (Gerber .zip) and the numbers. All our own code in a sealed WebView (its own files only, no network, no keys);
 * ₹0, no AI in this build. The .zip is saved in Downloads/Jarvis/pcb; sharing it and opening a factory's site happen only on his
 * tap, and he logs in, uploads and pays there himself (Jarvis never orders or pays). Low-voltage DC boards only. Phone only.
 */
public class PcbActivity extends Activity {
    static final String EXTRA_TAB = "pcb_tab", EXTRA_PROJECT = "pcb_project";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/pcb/";
    private static final String PAGE = "https://" + HOST + PATH + "pcb.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("pcb.html", "pcb.js", "pcbparts.js", "pcbcore.js", "pcbfab.js", "pcbprojects.js", "pcbbuy.js", "pcb3d.js"));
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
    private static final Set<String> TABS = new HashSet<>(Arrays.asList("home", "sch", "pcb", "drc", "d3", "fab", "calc"));
    private static final Set<String> PROJECTS = new HashSet<>(Arrays.asList("flasher", "usb", "soil", "esp32", "irboard"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private Coder.Made lastZip;
    private Runnable backTimeout;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "🔌 PCB స్టూడియో ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF0E141C);
        getWindow().setNavigationBarColor(0xFF07080B);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        setContentView(root);
        // brought back after Android closed it: open what he had, never a second copy of a ready board
        newPage(b == null ? getIntent() : null);
        Intent i = getIntent();
        if (i != null) { i.removeExtra(EXTRA_TAB); i.removeExtra(EXTRA_PROJECT); }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String t = tabOf(i), pr = projectOf(i);
        i.removeExtra(EXTRA_TAB);
        i.removeExtra(EXTRA_PROJECT);
        try {
            if (pr != null) send(json("project").put("id", pr));
            else if (t != null) send(json("tab").put("tab", t));
        } catch (Exception ignored) {}
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
                    .putExtra(Intent.EXTRA_TEXT, "🔌 PCB (Gerber) ఫైల్స్").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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

    // ================================================================ in front, behind, gone

    @Override protected void onResume() {
        super.onResume();
        JarvisAccessibility.bubbleAside(true);
    }

    @Override protected void onPause() {
        super.onPause();
        JarvisAccessibility.bubbleAside(false);
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
