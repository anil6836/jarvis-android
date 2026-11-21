package com.anil.jarvis;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.WindowManager;
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

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📚 Jarvis గ్రంథాలయం: the wooden racks (our own 3D drawing on the bundled three.js) in a sealed page that has no internet of
 * its own — searching and fetching free books happen here on the phone side, only on his taps, and the page gets the
 * results as data. Reading aloud is ReaderService (the page lights the line being read); Telugu by the phone's own pack
 * (₹0) or by his AI after the cost is shown. Phone only.
 */
public class LibraryActivity extends Activity {
    static final String EXTRA_BOOK = "lib_book", EXTRA_SEARCH = "lib_search", EXTRA_VIEW = "lib_view";
    /** From the AI Telugu notification's ⏹: stop the running job (the lines done stay kept). */
    static final String EXTRA_STOP_AI = "lib_stop_ai";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/library/";
    private static final String PAGE = "https://" + HOST + PATH + "library.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("library.html", "library.js", "libkit.js", "starter.json"));
    /** The workshop's bundled three.js (one copy in the app), served under the library's own path. */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList("three.module.min.js"));
    private static final int REQ_FILE = 51, REQ_SCAN = 52, REQ_PHOTOS = 53;

    /** How many library screens are open (the reader keeps the phone's translator while one is). */
    static volatile int open;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "jarvis-library"));
    private FrameLayout root;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private Intent first;
    /** What he picked to add, waiting for the form. */
    private Uri pickedFile;
    private List<Uri> pickedPhotos;
    private volatile Library.AiJob addJob;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        open++;
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF120c08);
        setContentView(root);
        first = getIntent();
        newPage();
        watchReader();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        goTo(i);
    }

    private void goTo(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra(EXTRA_STOP_AI, false)) {
            i.removeExtra(EXTRA_STOP_AI);
            Library.AiJob j = Library.running;
            if (j != null) { j.stop = true; say("⏹ AI తెలుగు ఆపుతున్నాను… అయిన వాక్యాలు దాచుకుంటాను."); }
        }
        try {
            if (i.getStringExtra(EXTRA_BOOK) != null) send(json("show").put("id", i.getStringExtra(EXTRA_BOOK)));
            else if (i.getStringExtra(EXTRA_SEARCH) != null) send(json("search_open").put("q", Workshop.cap(i.getStringExtra(EXTRA_SEARCH), 120)));
            else if (i.getStringExtra(EXTRA_VIEW) != null) send(json("view").put("v", i.getStringExtra(EXTRA_VIEW)));
        } catch (Exception ignored) {}
    }

    // ================================================================ the page (its own files only; no network inside it)

    private void newPage() {
        if (web != null) { root.removeView(web); try { web.destroy(); } catch (Throwable ignored) {} }
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        port = null;
        outbox.clear();
        WebView w = new WebView(this);
        web = w;
        w.setBackgroundColor(0xFF120c08);
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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String url) { if (v == web && url != null && url.startsWith(PAGE)) connect(v); }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> { if (!destroyed) newPage(); });
                return true;
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
        sendInit();
        goTo(first);
        first = null;
    }

    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            try {
                if (FILES.contains(name)) {
                    InputStream in = getAssets().open("library/" + name);
                    return new WebResourceResponse(name.endsWith(".html") ? "text/html" : name.endsWith(".json") ? "application/json" : "text/javascript", "utf-8", 200, "OK", h, in);
                }
                if (SHARED.contains(name)) return new WebResourceResponse("text/javascript", "utf-8", 200, "OK", h, getAssets().open("workshop/" + name));
                // a book's cover kept on the phone: covers/<id>.jpg (ids are our own: letters, digits, -)
                if (name.startsWith("covers/") && name.endsWith(".jpg")) {
                    String id = name.substring(7, name.length() - 4);
                    File f = Library.coverFile(this, id);
                    if (LibText.goodId(id) && f.exists()) return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new FileInputStream(f));
                }
            } catch (Exception ignored) {}
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
            Toast.makeText(this, "లైబ్రరీ పేజీ తెరవలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static JSONObject json(String t) {
        try { return new JSONObject().put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    private void send(JSONObject m) { if (m != null) sendRaw(m.toString()); }

    private void sendRaw(String s) {
        Runnable r = () -> {
            if (destroyed) return;
            if (port == null) { if (outbox.size() < 80) outbox.add(s); return; }
            try { port.postMessage(new WebMessage(s)); } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    private void say(String s) { try { send(json("say").put("s", s)); } catch (Exception ignored) {} }

    private void sendInit() {
        io.execute(() -> {
            try {
                Prefs p = new Prefs(this);
                try { Library.aiScan(this); } catch (Exception ignored) {} // (books translated before the ✨ tally: counted once)
                JSONObject now = Library.aiNow(this);
                JSONObject m = json("init").put("starter", Library.starter(this)).put("shelf", Library.shelf(this)).put("pos", Library.positions(this))
                        .put("pack", OfflineRead.ready(this)).put("model", Library.model(p)).put("rate", Library.rate(p)).put("ainow", now == null ? JSONObject.NULL : now)
                        .put("prefs", prefs()).put("now", nowLine()).put("ai", Library.running != null ? Library.running.id : "");
                send(m);
            } catch (Exception ignored) {}
        });
    }

    private JSONObject prefs() throws Exception {
        android.content.SharedPreferences s = getSharedPreferences("jarvis_library_ui", MODE_PRIVATE);
        return new JSONObject().put("font", s.getInt("font", 19)).put("night", s.getBoolean("night", false)).put("view", s.getString("view", "sec"))
                .put("mode", s.getString("mode", "en")).put("pair", s.getString("pair", "te")).put("rate", ReaderService.mark(this).getFloat("rate", 0f));
    }

    private void sendShelf() {
        io.execute(() -> { try { send(json("shelf").put("shelf", Library.shelf(this)).put("pos", Library.positions(this))); } catch (Exception ignored) {} });
    }

    // ================================================================ what the page asks

    private void fromPage(String data) {
        if (data == null || destroyed) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        String id = m.optString("id", "");
        switch (m.optString("t")) {
            case "search": search(m.optString("q", "")); break;
            case "download": download(m.optJSONObject("item"), m.optString("then", "")); break;
            case "wish": io.execute(() -> { try { Library.wish(this, m.getJSONObject("item")); say("🔖 కోరికల రాక్‌లో పెట్టాను."); sendShelf(); } catch (Exception e) { say("పెట్టలేకపోయాను"); } }); break;
            case "chapter": chapter(id, m.optInt("unit", 0)); break;
            case "te_phone": tePhone(id, m.optInt("unit", 0)); break;
            case "read": read(id, m.optString("lang", "en"), m.has("unit") ? m.optInt("unit") : -1, m.optInt("part", 0)); break;
            case "pause": ReaderService.control(this, ReaderService.ACTION_TOGGLE); break;
            case "stop": ReaderService.control(this, ReaderService.ACTION_STOP); break;
            case "sleep": {
                int min = Math.max(0, Math.min(180, m.optInt("min", 0)));
                ReaderService.sleepIn(min);
                say(min == 0 ? "🌙 నిద్ర టైమర్ తీసేశాను" : "🌙 " + min + " నిమిషాల తర్వాత చదవడం ఆపుతాను (చోటు గుర్తుంటుంది)");
                break;
            }
            case "rate": {
                float r = (float) Math.max(0, Math.min(2.5, m.optDouble("r", 0)));
                ReaderService.mark(this).edit().putFloat("rate", r).apply();
                break;
            }
            case "word": word(m.optString("w", "")); break;
            case "pack": pack(); break;
            case "quote": quote(id, m.optInt("unit", 0)); break;
            case "models": listModels(id, m.optInt("unit", 0)); break;
            case "trs": io.execute(() -> { // ✨ the translations page: this month's money (the books' tallies are in the shelf)
                try {
                    Prefs p = new Prefs(this);
                    try { Library.aiScan(this); } catch (Exception ignored) {}
                    JSONObject now = Library.aiNow(this);
                    send(json("trs").put("used", Math.round(Workshop.spent(this))).put("limit", p.workshopLimit()).put("books", Math.round(Workshop.booksSpent(this) * 100) / 100.0)
                            .put("model", Library.model(p)).put("rate", Library.rate(p)).put("shelf", Library.shelf(this)).put("ainow", now == null ? JSONObject.NULL : now));
                } catch (Exception e) { say("లెక్క రాలేదు: " + e.getMessage()); }
            }); break;
            case "model_set": setModel(m.optString("model", ""), id, m.optInt("unit", 0)); break;
            case "ai_go": aiGo(id, m.optString("scope", "chapter"), m.optInt("unit", 0)); break;
            case "ai_stop": { Library.AiJob j = Library.running; if (j != null) j.stop = true; Library.AiJob a = addJob; if (a != null) a.stop = true; break; }
            case "fav": io.execute(() -> { Library.flag(this, id, "fav", m.optBoolean("on")); sendShelf(); }); break;
            case "done": io.execute(() -> { Library.flag(this, id, "done", m.optBoolean("on")); sendShelf(); }); break;
            case "remove": io.execute(() -> { Library.remove(this, id); say("🗑️ ఫోన్ నుంచి తీసేశాను"); sendShelf(); }); break;
            case "add": addStart(m.optString("kind", "file")); break;
            case "add_go": addGo(m.optJSONObject("form")); break;
            case "add_cancel": pickedFile = null; pickedPhotos = null; break;
            case "site": site(m.optString("url", "")); break;
            case "prefs": {
                android.content.SharedPreferences.Editor e = getSharedPreferences("jarvis_library_ui", MODE_PRIVATE).edit();
                if (m.has("font")) e.putInt("font", Math.max(13, Math.min(34, m.optInt("font", 19))));
                if (m.has("night")) e.putBoolean("night", m.optBoolean("night"));
                if (m.has("view")) e.putString("view", "era".equals(m.optString("view")) ? "era" : "sec");
                if (m.has("mode")) e.putString("mode", m.optString("mode", "en").matches("en|te|ai|both") ? m.optString("mode") : "en");
                if (m.has("pair")) e.putString("pair", "ai".equals(m.optString("pair")) ? "ai" : "te"); // (రెండూ: English with the phone's Telugu or the AI's)
                e.apply();
                break;
            }
            case "close": finish(); break;
            default: break;
        }
    }

    private void search(String q) {
        String query = Workshop.cap(q.trim(), 120);
        if (query.isEmpty()) return;
        io.execute(() -> {
            JSONObject r = Library.search(this, query);
            try { send(r.put("t", "results")); } catch (Exception ignored) {}
            // small covers for the first results (the page itself has no internet): sent as pictures
            JSONArray res = r.optJSONArray("results");
            for (int i = 0; res != null && i < res.length() && i < 14 && !destroyed; i++) {
                JSONObject o = res.optJSONObject(i);
                String u = o == null ? "" : o.optString("cover_url", "");
                if (u.isEmpty()) continue;
                u = u.replace(".cover.medium.jpg", ".cover.small.jpg").replace("-M.jpg", "-S.jpg");
                try {
                    byte[] img = Library.getCover(u, 300_000);
                    if (img.length > 300) send(json("thumb").put("id", o.optString("id")).put("d", "data:image/jpeg;base64," + android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP)));
                } catch (Exception ignored) {}
            }
        });
    }

    private void download(JSONObject item, String then) {
        if (item == null) return;
        io.execute(() -> {
            String id = item.optString("id");
            try {
                JSONObject b = Library.download(this, item, s -> { try { send(json("prog").put("id", id).put("s", s)); } catch (Exception ignored) {} });
                send(json("got").put("book", b).put("then", then));
                sendShelf();
            } catch (Exception e) {
                try { send(json("fail").put("id", id).put("s", "⬇️ రాలేదు: " + (e.getMessage() == null ? "ఇంటర్నెట్ చూడండి" : e.getMessage()))); } catch (Exception ignored) {}
            }
        });
    }

    /** A chapter for the reading screen: its parts (as the reader cuts them) and any Telugu kept for it. */
    private void chapter(String id, int unit) {
        io.execute(() -> {
            try {
                int n = Library.count(this, id);
                if (n <= 0) { say("ఈ పుస్తకం ఇంకా ఫోన్‌లో లేదు"); return; }
                int u = Math.max(0, Math.min(n - 1, unit));
                List<String> parts = ReaderService.split(Library.chapter(this, id, u));
                JSONArray ps = new JSONArray();
                for (String s : parts) ps.put(s);
                JSONObject m = json("chapter").put("id", id).put("unit", u).put("n", n).put("parts", ps).put("titles", Library.titles(this, id));
                String[] te = Library.kept(this, id, "te", u, parts.size()), ai = Library.kept(this, id, "ai", u, parts.size());
                if (te != null) m.put("te", arr(te));
                if (ai != null) m.put("ai", arr(ai));
                send(m);
            } catch (Exception e) { say("అధ్యాయం తెరవలేకపోయాను: " + e.getMessage()); }
        });
    }

    private static JSONArray arr(String[] s) {
        JSONArray a = new JSONArray();
        for (String x : s) a.put(x == null ? JSONObject.NULL : x);
        return a;
    }

    /** The chapter in Telugu by the phone (₹0), sent a few lines at a time. */
    private void tePhone(String id, int unit) {
        if (!OfflineRead.ready(this)) { send(json("need_pack")); return; }
        io.execute(() -> {
            try {
                List<String> parts = ReaderService.split(Library.chapter(this, id, unit));
                JSONArray got = new JSONArray();
                int from = 0;
                for (int k = 0; k < parts.size() && !destroyed; k++) {
                    String te = Library.tePart(this, id, unit, parts, k);
                    got.put(te == null ? JSONObject.NULL : te);
                    if (got.length() >= 6 || k == parts.size() - 1) {
                        send(json("te").put("id", id).put("unit", unit).put("from", from).put("list", got));
                        from = k + 1;
                        got = new JSONArray();
                    }
                }
            } catch (Exception e) { say("అనువాదం రాలేదు: " + e.getMessage()); }
        });
    }

    private void read(String id, String lang, int unit, int part) {
        io.execute(() -> {
            try {
                JSONObject b = Library.book(this, id);
                if (b == null || !"ready".equals(b.optString("state"))) { say("ముందు ⬇️ దించుకోండి"); return; }
                String l = "te".equals(lang) || "ai".equals(lang) ? lang : "en";
                if ("te".equals(l) && !OfflineRead.ready(this)) { send(json("need_pack")); return; }
                int[] at = unit >= 0 ? new int[]{unit, part} : Library.posOf(this, id);
                if (at[0] >= b.optInt("n", 0)) at = new int[]{0, 0}; // (read to the end before: from the start)
                if ("ai".equals(l)) {
                    int parts = ReaderService.split(Library.chapter(this, id, at[0])).size();
                    if (Library.kept(this, id, "ai", at[0], parts) == null) { quote(id, at[0]); return; }
                }
                ReaderService.startLib(this, id, b.optString("t"), l, at[0], at[1]);
            } catch (Exception e) { say("చదవడం మొదలుకాలేదు: " + e.getMessage()); }
        });
    }

    private void word(String w) {
        String s = w.replaceAll("[^A-Za-z'\\- ]", " ").trim();
        if (s.isEmpty() || s.length() > 40) return;
        if (!OfflineRead.ready(this)) { send(json("need_pack")); return; }
        io.execute(() -> {
            String te = null;
            try {
                com.google.mlkit.nl.translate.Translator t = Library.phoneTranslator(this);
                if (t != null) te = com.google.android.gms.tasks.Tasks.await(t.translate(s), 15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try { send(json("word").put("w", s).put("te", te == null ? "అర్థం రాలేదు" : te)); } catch (Exception ignored) {}
        });
    }

    private void pack() {
        say("⬇️ తెలుగు ప్యాక్ దించుతున్నాను (సుమారు 30 MB, ఒకసారే)…");
        new Thread(() -> {
            String r = OfflineRead.download(this);
            try { send(json("pack").put("ok", r.isEmpty()).put("s", r.isEmpty() ? "✅ తెలుగు ప్యాక్ రెడీ. ఇక ఫోన్‌లోనే తెలుగులో చదువుతాను (₹0)." : "ప్యాక్ రాలేదు: " + r)); } catch (Exception ignored) {}
        }, "jarvis-te-pack").start();
    }

    private void quote(String id, int unit) {
        io.execute(() -> { try { send(Library.quote(this, id, unit)); } catch (Exception e) { say("ఖర్చు లెక్క రాలేదు: " + e.getMessage()); } });
    }

    /** The models his keys can use, each with this chapter's AI Telugu cost (the keys stay here; the page gets names and ₹ only). */
    private volatile List<String> offered = new ArrayList<>();

    private void listModels(String id, int unit) {
        new Thread(() -> {
            try {
                Prefs p = new Prefs(this);
                long chars = 0;
                try { chars = Library.chars(this, id, unit); } catch (Exception ignored) {}
                String[] provs = {Prefs.ANTHROPIC, Prefs.GEMINI, Prefs.OPENAI};
                String[] keys = {p.anthropicKey(), p.geminiKey(), p.openAiKey()};
                JSONArray list = new JSONArray();
                List<String> ids = new ArrayList<>();
                StringBuilder problems = new StringBuilder();
                int n = 0;
                for (int i = 0; i < provs.length; i++) {
                    if (keys[i].trim().isEmpty()) continue;
                    try {
                        for (Models.Item it : Models.list(provs[i], keys[i].trim())) {
                            String mid = it.id.replaceFirst("^models/", "").trim();
                            if (!Library.goodModel(mid) || ids.contains(mid) || !provs[i].equals(Workshop.provider(mid))) continue;
                            ids.add(mid);
                            list.put(new JSONObject().put("id", mid).put("co", Models.company(provs[i])).put("name", it.name == null || it.name.equals(it.id) ? "" : it.name)
                                    .put("r", Library.cost(mid, chars)).put("known", Usage.knownPrice(mid)).put("n", n++));
                        }
                    } catch (Exception e) { problems.append(Models.company(provs[i])).append(": ").append(e.getMessage() == null ? "లిస్ట్ రాలేదు" : e.getMessage()).append('\n'); }
                }
                offered = ids;
                if (ids.isEmpty() && problems.length() == 0) problems.append("⚙️ → Jarvis మెదడు లో ఒక్క AI key కూడా లేదు.");
                send(json("models").put("id", id).put("unit", unit).put("chars", chars).put("list", list).put("cur", Library.model(p))
                        .put("lib", p.libraryModel().trim()).put("ws", p.workshopModel().trim()).put("problems", problems.toString().trim()));
            } catch (Exception e) { say("మోడల్స్ లిస్ట్ రాలేదు: " + e.getMessage()); }
        }, "jarvis-library-models").start();
    }

    /** His pick for the library only ("" = back to the workshop's AI, exactly as before); the workshop's choice never changes here. */
    private void setModel(String mod, String id, int unit) {
        io.execute(() -> {
            try {
                String m2 = mod == null ? "" : mod.trim();
                if (!m2.isEmpty() && !offered.contains(m2)) { say("ఆ మోడల్ లిస్ట్‌లో లేదు. మళ్ళీ 🤖 మోడల్ మార్చు నొక్కండి."); return; }
                Prefs p = new Prefs(this);
                p.setLibraryModel(m2);
                send(json("model").put("model", Library.model(p)).put("lib", m2).put("rate", Library.rate(p)));
                String ws = p.workshopModel().trim();
                say(m2.isEmpty() ? "↩ లైబ్రరీ ఇక వర్క్‌షాప్ AI నే వాడుతుంది" + (ws.isEmpty() ? "" : " (" + ws + ")") + "."
                        : "🤖 లైబ్రరీ AI: " + m2 + ". వర్క్‌షాప్ AI మారలేదు.");
                try { send(Library.quote(this, id, unit)); } catch (Exception ignored) {}
            } catch (Exception e) { say("మార్చలేకపోయాను: " + e.getMessage()); }
        });
    }

    private void aiGo(String id, String scope, int unit) {
        io.execute(() -> {
            try {
                JSONObject q = Library.quote(this, id, unit);
                if (q.has("why")) { say(q.optString("why")); return; }
                List<Integer> units = new ArrayList<>();
                if ("book".equals(scope)) for (int u = 0; u < Library.count(this, id); u++) units.add(u);
                else units.add(unit);
                if (!Library.aiStart(this, id, units)) { say("ఇంకో ✨ పని నడుస్తోంది. అది అయ్యాక నొక్కండి."); return; }
                send(json("ai_on").put("id", id));
            } catch (Exception e) { say("✨ మొదలుకాలేదు: " + e.getMessage()); }
        });
    }

    /** Only these free sites open in his browser, on his tap. */
    private void site(String url) {
        if (!(url.startsWith("https://openlibrary.org/") || url.startsWith("https://www.gutenberg.org/") || url.startsWith("https://te.wikisource.org/")
                || url.startsWith("https://standardebooks.org/"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    // ================================================================ ➕ his own books

    private void addStart(String kind) {
        if ("scan".equals(kind)) {
            try {
                GmsDocumentScannerOptions o = new GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(100)
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build();
                GmsDocumentScanner sc = GmsDocumentScanning.getClient(o);
                sc.getStartScanIntent(this)
                        .addOnSuccessListener(sender -> { try { startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0); } catch (Exception e) { pickPhotos(); } })
                        .addOnFailureListener(e -> pickPhotos());
            } catch (Throwable t) { pickPhotos(); }
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/epub+zip", "text/plain"});
        try { startActivityForResult(i, REQ_FILE); } catch (Exception e) { say("ఫైల్ ఎంచుకునే స్క్రీన్ తెరవలేకపోయాను"); }
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(Intent.createChooser(i, "పుస్తకం పేజీల ఫోటోలు ఎంచుకోండి"), REQ_PHOTOS); } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FILE && data.getData() != null) {
            Uri u = data.getData();
            pickedFile = u;
            pickedPhotos = null;
            String name = nameOf(u);
            io.execute(() -> {
                try { send(json("addinfo").put("info", Library.inspect(this, u, name)).put("quote1", Library.pagesQuote(new Prefs(this), 1))); }
                catch (Exception e) { say("ఈ ఫైల్ తెరవలేకపోయాను: " + e.getMessage()); }
            });
        } else if (req == REQ_SCAN || req == REQ_PHOTOS) {
            List<Uri> list = new ArrayList<>();
            if (req == REQ_SCAN) {
                GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
                if (r != null && r.getPages() != null) for (GmsDocumentScanningResult.Page p : r.getPages()) list.add(p.getImageUri());
            } else {
                ClipData clip = data.getClipData();
                if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) list.add(clip.getItemAt(k).getUri());
                else if (data.getData() != null) list.add(data.getData());
            }
            if (list.isEmpty()) return;
            pickedPhotos = list;
            pickedFile = null;
            try { send(json("addinfo").put("info", new JSONObject().put("kind", "photos").put("pages", list.size()).put("t", "").put("a", ""))
                    .put("quote1", Library.pagesQuote(new Prefs(this), 1))); } catch (Exception ignored) {}
        }
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "";
    }

    private void addGo(JSONObject form) {
        if (form == null || (pickedFile == null && pickedPhotos == null)) return;
        Uri f = pickedFile;
        List<Uri> ph = pickedPhotos;
        pickedFile = null;
        pickedPhotos = null;
        Library.AiJob job = new Library.AiJob("add", new ArrayList<>());
        addJob = job;
        if ("te".equals(form.optString("lang")) && ("pdf".equals(form.optString("kind")) || ph != null)) {
            String over = Workshop.overLimit(this, new Prefs(this));
            if (over != null) { say(over); return; }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            try {
                JSONObject b = Library.add(this, form, f, ph, s -> { try { send(json("prog").put("id", "add").put("s", s)); } catch (Exception ignored) {} }, job);
                if (job.rupees > 0) Workshop.spendBooks(this, 0, true);
                send(json("got").put("book", b).put("then", "show").put("cost", job.rupees));
                sendShelf();
            } catch (Exception e) {
                try { send(json("fail").put("id", "add").put("s", "➕ కాలేదు: " + e.getMessage() + (job.rupees > 0 ? " (ఖర్చు " + Workshop.money(job.rupees) + ")" : ""))); } catch (Exception ignored) {}
            } finally {
                addJob = null;
                ui.post(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        }, "jarvis-library-add").start();
    }

    // ================================================================ the reader and the AI job, told to the page

    private JSONObject nowLine() throws Exception {
        android.content.SharedPreferences m = ReaderService.mark(this);
        String uri = m.getString("uri", "");
        if (ReaderService.reading.isEmpty() || !uri.startsWith("lib:")) return null;
        return new JSONObject().put("id", uri.substring(4)).put("unit", m.getInt("unit", 0)).put("part", m.getInt("chunk", 0))
                .put("lang", ReaderService.lang(this)).put("on", !ReaderService.paused);
    }

    private void watchReader() {
        ReaderService.watch = new ReaderService.Watch() {
            @Override public void at(String uri, int unit, int part, String lang) {
                if (!uri.startsWith("lib:")) return;
                try { send(json("now").put("id", uri.substring(4)).put("unit", unit).put("part", part).put("lang", lang).put("on", true)); } catch (Exception ignored) {}
            }
            @Override public void stopped(String uri) {
                try { send(json("now").put("id", uri.startsWith("lib:") ? uri.substring(4) : "").put("on", false)); } catch (Exception ignored) {}
            }
        };
        Library.aiWatch = new Library.AiWatch() {
            @Override public void progress(Library.AiJob j, String line) {
                try {
                    JSONObject o = Library.aiNow(getApplicationContext());
                    if (o == null) o = new JSONObject().put("id", j.id);
                    send(o.put("t", "aiprog").put("s", line));
                } catch (Exception ignored) {}
            }
            @Override public void finished(Library.AiJob j, String said) {
                try { send(json("aidone").put("id", j.id).put("s", said)); } catch (Exception ignored) {}
                sendShelf(); // (the ✨ on its spine, the counts on its card)
            }
        };
    }

    // ================================================================ in front, behind, gone

    /** The library is on the screen now (an AI Telugu job that ends while it isn't also says so in a notification). */
    static volatile boolean front;

    @Override protected void onResume() { super.onResume(); front = true; JarvisAccessibility.bubbleAside(true); }

    @Override protected void onPause() { super.onPause(); front = false; JarvisAccessibility.bubbleAside(false); }

    @Override public void onBackPressed() { send(json("back")); }

    @Override protected void onDestroy() {
        destroyed = true;
        open = Math.max(0, open - 1);
        if (open == 0) { ReaderService.watch = null; Library.aiWatch = null; if (ReaderService.reading.isEmpty()) Library.closeTranslator(); }
        Library.AiJob a = addJob;
        if (a != null) a.stop = true;
        io.shutdownNow();
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) { try { web.destroy(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    static void open(Context c, String book, String search) {
        Intent i = new Intent(c, LibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (book != null) i.putExtra(EXTRA_BOOK, book);
        if (search != null) i.putExtra(EXTRA_SEARCH, search);
        c.startActivity(i);
    }
}
