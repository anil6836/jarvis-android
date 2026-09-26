package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Shows what Jarvis made: a website or app preview (live), or a code file, with share / put online. */
public class WebActivity extends Activity {
    static final String EXTRA_FILE = "file", EXTRA_KIND = "kind", EXTRA_TITLE = "title", EXTRA_URI = "uri", EXTRA_SLUG = "slug";
    static final String KIND_SITE = "site", KIND_APP = "app", KIND_CODE = "code";

    static void show(Context c, String kind, String title, String file, String uri, String slug) {
        c.startActivity(new Intent(c, WebActivity.class).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FILE, file).putExtra(EXTRA_URI, uri).putExtra(EXTRA_SLUG, slug)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String kind = i.getStringExtra(EXTRA_KIND), title = i.getStringExtra(EXTRA_TITLE), file = i.getStringExtra(EXTRA_FILE);
        String uri = i.getStringExtra(EXTRA_URI), slug = i.getStringExtra(EXTRA_SLUG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.INK);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        TextView t = Ui.text(this, title == null || title.isEmpty() ? "Jarvis" : title, 16, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (KIND_SITE.equals(kind)) bar.addView(barButton("🌐 ఆన్‌లైన్", v -> publish(slug)));
        if (uri != null && !uri.isEmpty() && !"null".equals(uri)) bar.addView(barButton("షేర్", v -> share(uri, kind)));
        bar.addView(barButton("✕", v -> finish()));
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(!KIND_CODE.equals(kind));
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setBuiltInZoomControls(KIND_CODE.equals(kind));
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        String text = read(file);
        if (KIND_CODE.equals(kind)) {
            String esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String page = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width'><style>"
                    + "body{margin:0;background:#0b1219;color:#d6e7f2;font:13px/1.5 monospace}pre{margin:0;padding:14px;white-space:pre;overflow-x:auto}"
                    + "</style></head><body><pre>" + esc + "</pre></body></html>";
            web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null);
        } else {
            web.loadDataWithBaseURL("https://jarvis.local/", text, "text/html", "utf-8", null);
        }
    }

    private TextView barButton(String label, android.view.View.OnClickListener l) {
        TextView v = Ui.text(this, label, 14, Ui.CYAN);
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        v.setOnClickListener(l);
        return v;
    }

    private static String read(String path) {
        if (path == null) return "";
        try (FileInputStream in = new FileInputStream(new File(path))) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private void share(String uri, String kind) {
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType(KIND_CODE.equals(kind) ? "text/plain" : "text/html")
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "షేర్ చేయండి"));
        } catch (Exception e) {
            Toast.makeText(this, "షేర్ చేయలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private void publish(String slug) {
        Prefs prefs = new Prefs(this);
        if (prefs.githubToken().trim().isEmpty()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("GitHub token కావాలి")
                    .setMessage("వెబ్‌సైట్‌ని ఆన్‌లైన్‌లో పెట్టడానికి Jarvis settings → 'కోడింగ్, వెబ్‌సైట్లు, యాప్‌లు' లో GitHub token ఒక్కసారి పెట్టండి.")
                    .setPositiveButton("సరే", null).show();
            return;
        }
        Toast.makeText(this, "ఆన్‌లైన్‌లో పెడుతున్నాను…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String link = Coder.publish(getApplicationContext(), prefs, slug);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    ClipboardManager cm = getSystemService(ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("website", link));
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("ఆన్‌లైన్‌లో పెట్టాను ✓")
                            .setMessage(link + "\n\n(లింక్ కాపీ అయింది. మొదటిసారి తెరుచుకోవడానికి 1-2 నిమిషాలు పట్టొచ్చు.)")
                            .setPositiveButton("తెరువు", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); } catch (Exception ignored) {} })
                            .setNegativeButton("సరే", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "ఆన్‌లైన్ పెట్టలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "jarvis-publish").start();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
