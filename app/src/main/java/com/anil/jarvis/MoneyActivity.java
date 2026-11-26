package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 💰 డబ్బు సలహాదారు (Anil, 27 Nov: "నాకు పర్సనల్ గా ఫైనాన్షియల్ అడ్వైసర్ కావాలి… అన్ని రకాల ఫీచర్స్"; all three builds in one): his month
 * from bank / UPI / card SMS (read and counted on the phone only), where money leaks, subscriptions, the safety net (emergency fund,
 * health and term cover, costly debt) and the next steps in order, his goals with the monthly saving each needs, his own investments
 * (fund NAVs from AMFI, share prices) and net worth, loans and prepaying, the sums (SIP, FD, RD, PPF, EMI, retirement…), income tax
 * old vs new, "can I buy this?", a scheme / fraud check, and the AI's review (on his tap; the 1st of the month only if he switched it on).
 * A sealed WebView of our own files (no network, no keys); Jarvis never pays, buys, sells or opens a bank or investment app, and
 * never names a fund, share or company to buy. Phone only.
 */
public class MoneyActivity extends Activity {
    static final String EXTRA_TAB = "money_tab";
    private static final int REQ_SMS = 91;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PATH = "/assets/money/";
    private static final String PAGE = "https://" + HOST + PATH + "money.html";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("money.html", "money.js"));
    static final Set<String> TABS = new HashSet<>(Arrays.asList("month", "waste", "safe", "goals", "invest", "loans", "calc", "tax", "buy", "scheme", "ai", "me"));
    /** Official pages he can open from 🚨 (in his browser, only on his tap; nothing is sent to them). */
    private static final Map<String, String> SITES = new HashMap<>();
    static {
        SITES.put("sebi", "https://www.sebi.gov.in/intermediaries.html");
        SITES.put("sachet", "https://sachet.rbi.org.in/");
        SITES.put("cyber", "https://cybercrime.gov.in/");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebMessagePort port;
    private final List<String> outbox = new ArrayList<>();
    private boolean destroyed;
    private Runnable backTimeout;
    /** One slow job at a time (the AI's review, prices, the fund list). */
    private volatile boolean aiBusy, netBusy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Workshop.here(this)) {
            Toast.makeText(this, "💰 డబ్బు సలహాదారు ఫోన్‌లో మాత్రమే", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF0B1512);
        getWindow().setNavigationBarColor(0xFF060B09);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF060B09);
        setContentView(root);
        new Thread(() -> Finance.started(getApplicationContext()), "jarvis-money-start").start();
        newPage(tabOf(getIntent()));
        Intent i = getIntent();
        if (i != null) i.removeExtra(EXTRA_TAB);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String t = tabOf(i);
        i.removeExtra(EXTRA_TAB);
        if (t != null) try { send(json("tab").put("tab", t)); } catch (Exception ignored) {}
        refresh();
    }

    private static String tabOf(Intent i) { String t = i == null ? null : i.getStringExtra(EXTRA_TAB); return t != null && TABS.contains(t) ? t : null; }

    // ================================================================ the page (only its own two files; nothing goes out from it)

    private void newPage(String tab) {
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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // (the page never goes anywhere)
            @Override public void onPageFinished(WebView v, String url) { if (v == web && url != null && url.startsWith(PAGE)) connect(v); }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v == web) ui.post(() -> { if (!destroyed) newPage(null); });
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
        if (tab != null) try { send(json("tab").put("tab", tab)); } catch (Exception ignored) {}
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
                    InputStream in = getAssets().open("money/" + name);
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
            Toast.makeText(this, "💰 పేజీ తెరవలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static JSONObject json(String t) {
        try { return new JSONObject().put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    /** (any thread) to the page; queued until it is there. */
    private void send(JSONObject m) {
        if (m == null) return;
        sendRaw(m.toString());
    }

    private void sendRaw(final String s) {
        Runnable r = () -> {
            if (destroyed) return;
            if (port == null) { outbox.add(s); return; }
            try { port.postMessage(new WebMessage(s)); } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    private void say(String s) { try { send(json("say").put("s", s)); } catch (Exception ignored) {} }

    /** Everything again (off the main thread: the SMS are read and counted here). */
    private void refresh() {
        new Thread(() -> {
            try {
                String b = Finance.bundle(this);
                sendRaw("{\"t\":\"data\",\"d\":" + b + "}");
            } catch (Throwable e) {
                say("లెక్కలు తేలేకపోయాను: " + e.getClass().getSimpleName());
            }
        }, "jarvis-money-data").start();
    }

    /** A change from the page, then the fresh numbers. */
    private void work(String name, Runnable job) {
        new Thread(() -> {
            try { job.run(); }
            catch (Throwable e) { say("too many".equals(e.getMessage()) ? "ఇప్పటికే 60 ఉన్నాయి: పాతవి తీసేసి కొత్తది రాయండి." : "దాచలేకపోయాను: " + cap(e.getMessage(), 120)); }
            refresh();
        }, "jarvis-money-" + name).start();
    }

    private static double clamp(double v, double lo, double hi) { return Double.isNaN(v) || Double.isInfinite(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    private static String cap(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** What the page says (data only; every word the page shows goes in as plain text). */
    private void fromPage(String data) {
        if (destroyed || data == null || data.length() > 200_000) return;
        JSONObject m;
        try { m = new JSONObject(data); } catch (Exception e) { return; }
        switch (m.optString("t")) {
            case "ready": case "refresh": refresh(); break;
            case "profile": {
                JSONObject v = m.optJSONObject("v");
                if (v != null) work("profile", () -> Finance.saveProfile(this, v));
                break;
            }
            case "goal": {
                JSONObject v = m.optJSONObject("v");
                if (v != null) work("goal", () -> { try { Finance.upsert(this, "goals", Finance.cleanGoal(v)); } catch (Exception e) { throw new RuntimeException(e.getMessage()); } });
                break;
            }
            case "goal_rm": { String id = m.optString("id"); work("goal-rm", () -> Finance.remove(this, "goals", id)); break; }
            case "holding": {
                JSONObject v = m.optJSONObject("v");
                if (v != null) work("holding", () -> { try { Finance.upsert(this, "holdings", Finance.cleanHolding(v)); } catch (Exception e) { throw new RuntimeException(e.getMessage()); } });
                break;
            }
            case "holding_rm": { String id = m.optString("id"); work("holding-rm", () -> Finance.remove(this, "holdings", id)); break; }
            case "loan_rate": {
                String id = cap(m.optString("id"), 40);
                double r = clamp(m.optDouble("rate", 0), 0, 60);
                work("loan-rate", () -> Finance.mapPut(this, "loan_rates", id, r > 0 ? (Object) r : null));
                break;
            }
            case "loan_add": addLoan(m.optJSONObject("v")); break;
            case "sub_mark": {
                String name = cap(m.optString("name"), 60), mark = m.optString("mark");
                work("sub-mark", () -> Finance.mapPut(this, "sub_marks", name, "keep".equals(mark) || "cancel".equals(mark) ? mark : null));
                break;
            }
            case "payee_cat": {
                String payee = cap(m.optString("payee"), 60), cat = m.optString("cat");
                boolean ok = Arrays.asList(FinTxn.CATS).contains(cat);
                work("payee-cat", () -> Finance.mapPut(this, "payee_cats", payee, ok ? cat : null));
                break;
            }
            case "expense": {
                double amt = clamp(m.optDouble("amount", 0), 0, 1e7);
                String what = cap(m.optString("what"), 80), shop = cap(m.optString("shop"), 60), cat = m.optString("cat");
                if (amt < 1) { say("ఎంత ఖర్చో రాయండి."); break; }
                work("expense", () -> {
                    try { Money.add(this, amt, what.isEmpty() ? FinTxn.catTe(cat) : what, shop, Arrays.asList(FinTxn.CATS).contains(cat) ? cat : "", System.currentTimeMillis()); }
                    catch (Exception e) { throw new RuntimeException(e.getMessage()); }
                });
                break;
            }
            case "prices": prices(); break;
            case "fund_search": fundSearch(cap(m.optString("q"), 60)); break;
            case "calc": calc(m.optString("id"), m.optJSONObject("a")); break;
            case "pdf": pdf(m.optString("month")); break;
            case "ai": ai(); break;
            case "perm_sms": askSms(); break;
            case "site": openSite(m.optString("id")); break;
            case "dial1930": dial1930(); break;
            case "close": finish(); break;
            case "back": {
                if (backTimeout != null) { ui.removeCallbacks(backTimeout); backTimeout = null; }
                if (!m.optBoolean("used")) finish();
                break;
            }
            default: break;
        }
    }

    /** A loan he writes here goes into 🏦 అప్పులు (the same list Jarvis already keeps), with its rate kept beside it. */
    private void addLoan(JSONObject v) {
        if (v == null) return;
        String name = cap(v.optString("name"), 60);
        double emi = clamp(v.optDouble("emi", 0), 0, 1e7), rate = clamp(v.optDouble("rate", 0), 0, 60);
        int months = (int) clamp(v.optDouble("months", 0), 0, 600), done = (int) clamp(v.optDouble("done", 0), 0, 600), day = (int) clamp(v.optDouble("day", 0), 0, 31);
        if (name.isEmpty() || emi < 1 || months < 1 || day < 1) { say("లోన్ పేరు, EMI, మొత్తం నెలలు, EMI తేదీ రాయండి."); return; }
        work("loan-add", () -> {
            try {
                JSONObject o = Debts.add(this, "emi", name, emi, "", "", day, months, Math.min(done, months), "");
                if (rate > 0 && o != null) Finance.mapPut(this, "loan_rates", o.optString("id"), rate);
            } catch (Exception e) { throw new RuntimeException(e.getMessage()); }
        });
    }

    private void calc(String id, JSONObject a) {
        if (a == null) return;
        new Thread(() -> {
            try {
                String r = Finance.calc(this, a);
                sendRaw("{\"t\":\"calc\",\"id\":" + JSONObject.quote(cap(id, 20)) + ",\"r\":" + r + "}");
            } catch (Throwable e) {
                say("లెక్క కుదరలేదు: అంకెలు మళ్ళీ చూడండి.");
            }
        }, "jarvis-money-calc").start();
    }

    /** 🔄 his funds' NAVs (AMFI's public file) and shares' prices: only his own holdings' codes go out (no amounts, nothing else). */
    private void prices() {
        if (netBusy) return;
        netBusy = true;
        new Thread(() -> {
            try {
                int n = Finance.refreshPrices(this, true);
                say(n > 0 ? "🔄 " + n + " ధరలు తెచ్చాను." : "ధరలు రాలేదు (నెట్ / కోడ్ చూడండి). ధర మీరే రాయొచ్చు.");
            } catch (Throwable e) {
                say("ధరలు తేలేకపోయాను: నెట్ ఉందా చూడండి.");
            } finally {
                netBusy = false;
            }
            refresh();
        }, "jarvis-money-prices").start();
    }

    private void fundSearch(String q) {
        if (q.length() < 3) { say("ఫండ్ పేరులో కనీసం 3 అక్షరాలు రాయండి."); return; }
        new Thread(() -> {
            try {
                JSONArray l = Finance.searchFunds(this, q);
                send(json("funds").put("list", l).put("q", q));
            } catch (Throwable e) {
                say("ఫండ్ల లిస్ట్ తేలేకపోయాను: నెట్ ఉందా చూడండి.");
            }
        }, "jarvis-money-funds").start();
    }

    private void pdf(String month) {
        new Thread(() -> {
            try {
                YearMonth ym = YearMonth.parse(month);
                Coder.Made made = MoneyReport.pdf(this, ym);
                if (made == null) { say("ఆ నెలకి లెక్కలు లేవు."); return; }
                say("📄 " + made.where + " లో దాచాను.");
                ui.post(() -> { if (!destroyed) Cards.view(this, made); });
            } catch (Throwable e) {
                say("PDF చేయలేకపోయాను: " + e.getClass().getSimpleName());
            }
        }, "jarvis-money-pdf").start();
    }

    /** 🤖 His tap: the month's totals (no SMS text, no account digits, no person's name) to his own AI, and its review back. */
    private void ai() {
        if (aiBusy) return;
        if (!new Prefs(this).hasBrain()) { say("AI కీ లేదు: సెట్టింగ్స్‌లో మీ AI పెట్టిన తర్వాత అడగండి."); send(json("ai_done")); return; }
        aiBusy = true;
        new Thread(() -> {
            try {
                String r = Finance.aiReview(this);
                if (r == null) say("AI జవాబు రాలేదు. కాసేపాగి మళ్ళీ.");
                send(json("ai_done").put("ok", r != null));
            } catch (Throwable e) {
                say("AI జవాబు రాలేదు: " + cap(e.getMessage(), 160));
                send(json("ai_done"));
            } finally {
                aiBusy = false;
            }
            refresh();
        }, "jarvis-money-ai").start();
    }

    private void askSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { refresh(); return; }
        requestPermissions(new String[]{Manifest.permission.READ_SMS}, REQ_SMS);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_SMS) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) refresh();
        else say("SMS అనుమతి లేకుండా: మీరు రాసిన ఖర్చులు మాత్రమే లెక్కలో ఉంటాయి.");
    }

    private void openSite(String id) {
        String url = SITES.get(id);
        if (url == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { say("బ్రౌజర్ తెరవలేకపోయాను."); }
    }

    /** 🚨 money lost to a fraud: the cyber-crime helpline in the dialer (he presses call himself). */
    private void dial1930() {
        try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))); }
        catch (ActivityNotFoundException e) { say("డయలర్ తెరవలేకపోయాను: 1930 కి మీరే కాల్ చేయండి."); }
    }

    @Override public void onBackPressed() {
        if (port == null) { finish(); return; }
        send(json("back")); // (the page closes its own form / goes to its first tab first)
        if (backTimeout != null) ui.removeCallbacks(backTimeout);
        backTimeout = () -> { backTimeout = null; if (!destroyed) finish(); };
        ui.postDelayed(backTimeout, 800);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (port != null) { try { port.close(); } catch (Throwable ignored) {} }
        if (web != null) { try { web.destroy(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    static void open(Context c, String tab) {
        Intent i = new Intent(c, MoneyActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tab != null && TABS.contains(tab)) i.putExtra(EXTRA_TAB, tab);
        c.startActivity(i);
    }
}
