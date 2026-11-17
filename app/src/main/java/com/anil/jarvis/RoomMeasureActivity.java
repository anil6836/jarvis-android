package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONArray;
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
 * 📐 ఇంటి కొలతలు (studio idea 3, 17 Nov): the back camera with ARCore measures a length (📏), a room's floor (🏠: corners →
 * plan, area, paint, tiles, PDF) and shows a bed / almirah at its real size on his floor (🛏️: does it fit?). Phone only, ₹0,
 * no AI: the camera picture never leaves the phone and is never kept; only the numbers he keeps are saved (📂).
 */
public class RoomMeasureActivity extends Activity {
    static final String EXTRA_TAB = "room_tab";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/room/";
    private static final String PAGE = "https://" + HOST + PATH + "room.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("room.html", "room.js", "roomkit.js"));
    private static final int REQ_CAM = 41;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed, busy, front;
    private RoomAr ar;
    private boolean camWanted, installAsked;
    private String camMode = "measure";
    private int tries;
    private String startTab;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        startTab = tabOf(getIntent());
        newPage();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String t = tabOf(i);
        if (t != null) try { send(json("tab").put("tab", t)); } catch (Exception ignored) {}
    }

    private static String tabOf(Intent i) {
        String t = i == null ? null : i.getStringExtra(EXTRA_TAB);
        return t != null && t.matches("measure|room|fit|plan|saved") ? t : null;
    }

    // ================================================================ the page (only its own three files; nothing goes out)

    private void newPage() {
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
        try {
            JSONObject init = json("init").put("rooms", Rooms.listJson(this));
            if (startTab != null) init.put("tab", startTab);
            send(init);
        } catch (Exception ignored) {}
    }

    private WebResourceResponse serve(Uri u) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        String path = u == null ? null : u.getPath();
        if (u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && path != null && path.startsWith(PATH)) {
            String name = path.substring(PATH.length());
            if (FILES.contains(name)) {
                try {
                    InputStream in = getAssets().open("room/" + name);
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
            Toast.makeText(this, "కొలతల పేజీ తెరవలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static JSONObject json(String t) {
        try { return new JSONObject().put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    private void send(JSONObject m) { if (m != null) sendRaw(m.toString()); }

    /** (any thread) to the page; queued until it is there. */
    private void sendRaw(String s) {
        Runnable r = () -> {
            if (destroyed) return;
            if (port == null) { if (outbox.size() < 50 && !s.startsWith("{\"t\":\"f\"")) outbox.add(s); return; }
            try { port.postMessage(new WebMessage(s)); } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    private void fromPage(String data) {
        if (data == null || destroyed) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        switch (m.optString("t")) {
            case "ar":
                camMode = "floor".equals(m.optString("mode")) ? "floor" : "measure";
                if (m.optBoolean("on")) camStart(); else camStop(false);
                break;
            case "add": if (ar != null) ar.add(); else send(json("noaim")); break;
            case "del": {
                JSONArray ids = m.optJSONArray("ids");
                for (int i = 0; ar != null && ids != null && i < ids.length() && i < 200; i++) ar.remove(ids.optInt(i, -1));
                break;
            }
            case "save":
                if (!Rooms.save(this, m.optJSONObject("item"))) Toast.makeText(this, "దాచలేకపోయాను", Toast.LENGTH_SHORT).show();
                sendRooms();
                break;
            case "remove": Rooms.remove(this, m.optString("id")); sendRooms(); break;
            case "report": report(m); break;
            case "busy": busy = m.optBoolean("on"); break;
            case "close": leave(); break;
            default: break;
        }
    }

    private void sendRooms() {
        try { send(json("rooms").put("list", Rooms.listJson(this))); } catch (Exception ignored) {}
    }

    // ================================================================ the camera (ARCore): on while a measuring tab is on the screen

    private void camStart() {
        camWanted = true;
        if (ar != null) { ar.mode(camMode); return; }
        if (!front) return; // (started when he is back)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM);
            xr(false, "📷 కెమెరా అనుమతి అడుగుతున్నాను…");
            return;
        }
        String r = RealFloor.ready(this, !installAsked);
        if ("checking".equals(r)) {
            if (tries++ < 8) { xr(false, "📐 AR సిద్ధమవుతోంది…"); ui.postDelayed(() -> { if (!destroyed && camWanted && ar == null) camStart(); }, 600); return; }
            tries = 0;
            xr(false, "📐 AR ఇంకా సిద్ధం కాలేదు. కొంచెం సేపు ఆగి మళ్ళీ ఈ ట్యాబ్ నొక్కండి.");
            return;
        }
        tries = 0;
        if ("install".equals(r)) {
            installAsked = true;
            xr(false, "📐 దీనికి Google \"Play Services for AR\" కావాలి. Play Store లో Install / Update మీరే నొక్కి, ఇక్కడికి తిరిగి రండి.");
            return;
        }
        if ("no".equals(r)) { xr(false, "ఈ ఫోన్‌లో AR (నేల, గోడలు కనుక్కోవడం) లేదు. 📂 దాచినవి, 📄 ప్లాన్ మాత్రం పనిచేస్తాయి."); return; }
        if (!"ok".equals(r)) { xr(false, "📐 " + r); return; }
        RoomAr a = new RoomAr(this, new RoomAr.Listener() {
            @Override public void onFrame(String json) { sendRaw(json); }
            @Override public void onState(String s) { try { send(json("st").put("s", s)); } catch (Exception ignored) {} }
            @Override public void onAdded(int id, String kind, float[] at) {
                try {
                    if (id < 0) { send(json("noaim")); return; }
                    send(json("added").put("id", id).put("k", kind).put("p", new JSONArray().put((double) at[0]).put((double) at[1]).put((double) at[2])));
                } catch (Exception ignored) {}
            }
            @Override public void onProblem(String problem) { ui.post(() -> { if (destroyed) return; camStop(true); xr(false, "📐 " + problem + ". మళ్ళీ ట్యాబ్ నొక్కి చూడండి."); }); }
        });
        String bad = a.begin();
        if (bad != null) { xr(false, "📐 " + bad); return; }
        a.mode(camMode);
        ar = a;
        root.addView(a.view, 0, new FrameLayout.LayoutParams(-1, -1));
        a.view.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        xr(true, null);
    }

    /** The camera off (the plan / kept tabs, or a problem); the points he put go with it. */
    private void camStop(boolean problem) {
        if (!problem) camWanted = false;
        RoomAr a = ar;
        ar = null;
        if (a != null) {
            a.end();
            try { root.removeView(a.view); } catch (Throwable ignored) {}
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!problem) xr(false, null);
    }

    private void xr(boolean on, String why) {
        try { JSONObject m = json("xr").put("on", on); if (why != null) m.put("why", why); send(m); } catch (Exception ignored) {}
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_CAM) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { if (camWanted) camStart(); }
        else { camWanted = false; xr(false, "📷 కెమెరా అనుమతి లేకుండా కొలవలేను. 📄 ప్లాన్, 📂 దాచినవి పనిచేస్తాయి. (మళ్ళీ అడగాలంటే ట్యాబ్ నొక్కండి.)"); }
    }

    // ================================================================ the PDF: saved in Downloads/Jarvis/rooms, shared only on his choice

    private void report(JSONObject m) {
        String t = Workshop.cap(m.optString("title", "గది"), 60);
        String pic = m.optString("png", "");
        JSONArray ls = m.optJSONArray("lines");
        List<String> lines = new ArrayList<>();
        for (int i = 0; ls != null && i < ls.length() && i < 80; i++) lines.add(Workshop.cap(ls.optString(i, ""), 300));
        new Thread(() -> {
            try {
                byte[] img = pic.isEmpty() ? null : android.util.Base64.decode(pic, android.util.Base64.DEFAULT);
                boolean png = img != null && img.length > 64 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G';
                byte[] pdf = CircuitLab.pdf("📐 ", t, png ? img : null, 0xFFFFFFFF, lines,
                        "Jarvis 📐 ఇంటి కొలతలు · ఫోన్ కెమెరా (AR) తో తీసిన సుమారు కొలతలు; కొన్ని సెం.మీ. తేడా రావచ్చు. కొనే / కట్టే ముందు టేపుతో సరిచూసుకోండి.");
                Coder.Made made = Coder.save(this, "Jarvis/rooms", Workshop.fileName(t, System.currentTimeMillis() % 1000000 + ".pdf"), "application/pdf", pdf);
                ui.post(() -> shareOut(made, t));
            } catch (Exception e) {
                ui.post(() -> { try { send(json("say").put("s", "PDF తయారు కాలేదు: " + e.getMessage())); } catch (Exception ignored) {} });
            }
        }, "jarvis-room-pdf").start();
    }

    private void shareOut(Coder.Made m, String title) {
        if (destroyed) return;
        try { send(json("say").put("s", "💾 సేవ్ అయింది: " + m.where)); } catch (Exception ignored) {}
        if (m.uri == null) return;
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, m.uri)
                    .putExtra(Intent.EXTRA_TEXT, "📐 " + title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
        } catch (Exception ignored) {}
    }

    // ================================================================ in front, behind, gone

    @Override protected void onResume() {
        super.onResume();
        front = true;
        JarvisAccessibility.bubbleAside(true);
        if (ar != null) {
            String bad = ar.resume();
            if (bad != null) { camStop(true); xr(false, "📐 " + bad); }
        } else if (camWanted) camStart(); // (back from the permission / Play Store page)
    }

    @Override protected void onPause() {
        super.onPause();
        front = false;
        JarvisAccessibility.bubbleAside(false);
        if (ar != null) ar.pause();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (ar != null) ar.turned();
    }

    @Override public void onBackPressed() { leave(); }

    private void leave() {
        if (!busy) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("📐 కొలతలు దాచలేదు")
                .setMessage("ఇప్పుడు పెట్టిన కొలతలు ఇంకా దాచలేదు (📏 లో 💾, లేదా 🏠 లో ✓). వదిలేసి బయటికి వెళ్ళాలా?")
                .setPositiveButton("వదిలేయి", (d, w) -> finish())
                .setNegativeButton("ఉండు", null).show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        RoomAr a = ar;
        ar = null;
        if (a != null) a.end();
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) { try { web.destroy(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    static void open(Context c, String tab) {
        Intent i = new Intent(c, RoomMeasureActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tab != null) i.putExtra(EXTRA_TAB, tab);
        c.startActivity(i);
    }
}
