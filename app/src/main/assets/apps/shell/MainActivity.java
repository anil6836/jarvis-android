package __PKG__;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Made by Jarvis's app studio. The app is the page in assets/index.html (one page: the phone's back button asks it first).
 * window.JarvisPhone gives the page the phone's powers its code uses (this app's manifest has only their permissions):
 * reminders, a photo, where the phone is (only when asked), share, PDF, speak / listen, the widget, torch, keep awake,
 * landscape, copy, QR. Every answer goes back by App.__reply. Jarvis may open the app with a command (checked against this
 * app's own key) and a reminder may open a screen.
 */
public class MainActivity extends Activity {
    private static final int BG = __BG__;
    private static final boolean LIGHT = __LIGHT__;
    private static final int REQ_PERM = 11, REQ_CAMERA = 12, REQ_GALLERY = 13, REQ_LISTEN = 14;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web, printing;
    // a power waiting for a permission or another screen: its call id and what to do next
    private String waitId;
    private Runnable afterPerm;
    private File cameraFile;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<String> ttsQueue = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (LIGHT) {
            int ui0 = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) ui0 |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(ui0);
        }
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "JarvisPhone");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                return !(u != null && "file".equals(u.getScheme()) && String.valueOf(u.getPath()).startsWith("/android_asset/"));
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl(); // (the app's own photos, from inside the app only)
                if (u != null && "app.photos".equals(u.getHost())) {
                    String name = u.getLastPathSegment();
                    if (name != null && name.matches("p[a-z0-9]{2,40}\\.jpg")) {
                        try { return new WebResourceResponse("image/jpeg", null, new FileInputStream(new File(PhotoFiles.dir(MainActivity.this), name))); }
                        catch (Exception ignored) {}
                    }
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
                }
                return null;
            }
        });
        setContentView(web);
        if (state != null) web.restoreState(state); else web.loadUrl("file:///android_asset/index.html");
        if (state == null) handle(getIntent()); // (not again when Android brings the screen back: no command twice)
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    /** A reminder's screen, or a command from Jarvis (only with this app's own key). */
    private void handle(Intent i) {
        if (i == null) return;
        String screen = i.getStringExtra("screen");
        if (screen != null && !screen.isEmpty()) whenReady("App.__open(" + JSONObject.quote(screen) + ")", 0);
        String cmd = i.getStringExtra("jarvis_cmd"), key = i.getStringExtra("jarvis_key");
        if (cmd != null && key != null && key.equals(ownKey())) {
            String data = i.getStringExtra("jarvis_data");
            try { data = new JSONObject(data == null ? "{}" : data).toString(); } catch (Exception e) { data = "{}"; }
            whenReady("App.__jarvis(" + JSONObject.quote(cmd) + "," + data + ")", 0);
        }
        i.removeExtra("screen");
        i.removeExtra("jarvis_cmd");
    }

    private String ownKey() {
        try (InputStream in = getAssets().open("jarvis.key")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] x = new byte[256];
            int n;
            while ((n = in.read(x)) > 0) b.write(x, 0, n);
            String k = b.toString("UTF-8").trim();
            return k.length() >= 16 ? k : "\u0000";
        } catch (Exception e) {
            return "\u0000";
        }
    }

    /** Runs the script once the app has started (it may still be loading). */
    private void whenReady(String js, int tries) {
        web.evaluateJavascript("(function(){return !!(window.App&&App.__started)})()", v -> {
            if ("true".equals(v)) web.evaluateJavascript(js, null);
            else if (tries < 60) ui.postDelayed(() -> whenReady(js, tries + 1), 200);
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        web.evaluateJavascript("(function(){try{return !!(window.App&&App.back&&App.back())}catch(e){return false}})()", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack(); else finish();
        });
    }

    @Override protected void onDestroy() {
        if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    // ================================================================ the powers

    private final class Bridge {
        @JavascriptInterface public void call(String id, String name, String args) {
            ui.post(() -> {
                JSONObject a;
                try { a = new JSONObject(args == null ? "{}" : args); } catch (Exception e) { a = new JSONObject(); }
                try { run(id, name, a); } catch (Exception e) { reply(id, false, "పని కాలేదు: " + e.getMessage()); }
            });
        }
    }

    private void reply(String id, boolean ok, Object value) {
        String v;
        if (value == null) v = "null";
        else if (value instanceof String) v = JSONObject.quote((String) value);
        else v = value.toString();
        web.evaluateJavascript("App.__reply(" + JSONObject.quote(id) + "," + ok + "," + v + ")", null);
    }

    private void run(String id, String name, JSONObject a) throws Exception {
        switch (name) {
            case "remind": {
                Runnable go = () -> {
                    try { Reminders.add(this, a); reply(id, true, a.optString("id")); }
                    catch (Exception e) { reply(id, false, "రిమైండర్ పెట్టలేకపోయాను: " + e.getMessage()); }
                };
                if (Build.VERSION.SDK_INT >= 33) need(id, new String[]{"android.permission.POST_NOTIFICATIONS"}, go, "నోటిఫికేషన్లకు అనుమతి ఇవ్వలేదు");
                else go.run();
                return;
            }
            case "unremind": Reminders.remove(this, a.optString("id")); reply(id, true, true); return;
            case "reminders": reply(id, true, Reminders.list(this)); return;
            case "photo": photo(id, "gallery".equals(a.optString("from"))); return;
            case "unphoto": {
                String p = a.optString("id");
                if (p.matches("p[a-z0-9]{2,40}")) //noinspection ResultOfMethodCallIgnored
                    new File(PhotoFiles.dir(this), p + ".jpg").delete();
                reply(id, true, true);
                return;
            }
            case "where": need(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, () -> where(id), "లొకేషన్‌కి అనుమతి ఇవ్వలేదు"); return;
            case "openMap": {
                double lat = a.optDouble("lat"), lon = a.optDouble("lon");
                String label = Uri.encode(a.optString("label", ""));
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + (label.isEmpty() ? "" : "(" + label + ")"))));
                reply(id, true, true);
                return;
            }
            case "share": {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text"));
                startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
                reply(id, true, true);
                return;
            }
            case "sharePhoto": {
                String p = a.optString("id");
                if (!p.matches("p[a-z0-9]{2,40}") || !new File(PhotoFiles.dir(this), p + ".jpg").exists()) { reply(id, false, "ఆ ఫోటో లేదు"); return; }
                Uri u = PhotoFiles.uri(this, p + ".jpg");
                Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, a.optString("text"))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                s.setClipData(ClipData.newRawUri("photo", u));
                startActivity(Intent.createChooser(s, "ఎక్కడికి పంపాలి?"));
                reply(id, true, true);
                return;
            }
            case "pdf": pdf(id, a.optString("title", "Report"), a.optString("html")); return;
            case "say": say(a.optString("text")); reply(id, true, true); return;
            case "listen": {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, a.optString("prompt", "చెప్పండి"));
                waitId = id;
                try { startActivityForResult(i, REQ_LISTEN); } catch (Exception e) { waitId = null; reply(id, false, "ఈ ఫోన్‌లో మాట వినే సేవ లేదు"); }
                return;
            }
            case "widget": Widget.set(this, a.optString("title"), a.optJSONArray("lines")); reply(id, true, true); return;
            case "torch": {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String cam = null;
                for (String c : cm.getCameraIdList()) if (Boolean.TRUE.equals(cm.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) { cam = c; break; }
                if (cam == null) { reply(id, false, "ఈ ఫోన్‌లో టార్చ్ లేదు"); return; }
                cm.setTorchMode(cam, a.optBoolean("on", true));
                reply(id, true, true);
                return;
            }
            case "awake":
                if (a.optBoolean("on", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                reply(id, true, true);
                return;
            case "landscape":
                setRequestedOrientation(a.optBoolean("on", true) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                reply(id, true, true);
                return;
            case "copy": {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("text", a.optString("text")));
                reply(id, true, true);
                return;
            }
            case "scan": Qr.scan(this, t -> reply(id, true, t), e -> reply(id, false, e)); return;
            default: reply(id, false, "ఈ పని లేదు: " + name);
        }
    }

    /** Asks Android for the permissions once (only those in this app's manifest can ever be given). */
    private void need(String id, String[] perms, Runnable then, String refused) {
        boolean all = true;
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { all = false; break; }
        if (all) { then.run(); return; }
        waitId = id;
        afterPerm = () -> {
            boolean any = false;
            for (String p : perms) if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) { any = true; break; }
            if (any) then.run(); else reply(id, false, refused);
        };
        requestPermissions(perms, REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM || afterPerm == null) return;
        Runnable r = afterPerm;
        afterPerm = null;
        waitId = null;
        r.run();
    }

    // ---- 📷 a photo, kept in this app's own files (files/photos/<id>.jpg, at most 1600 px)

    private void photo(String id, boolean gallery) {
        waitId = id;
        try {
            if (gallery) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "ఫోటో ఎంచుకోండి"), REQ_GALLERY);
            } else {
                cameraFile = new File(PhotoFiles.dir(this), "camera.tmp");
                //noinspection ResultOfMethodCallIgnored
                cameraFile.delete();
                Uri out = PhotoFiles.uri(this, "camera.tmp");
                Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
                i.setClipData(ClipData.newRawUri("photo", out));
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            }
        } catch (Exception e) {
            waitId = null;
            reply(id, false, gallery ? "గ్యాలరీ తెరవలేకపోయాను" : "కెమెరా తెరవలేకపోయాను");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        String id = waitId;
        waitId = null;
        if (id == null) return;
        if (req == REQ_LISTEN) {
            ArrayList<String> got = result == RESULT_OK && data != null ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            reply(id, true, got == null || got.isEmpty() ? null : got.get(0));
            return;
        }
        if (req != REQ_CAMERA && req != REQ_GALLERY) return;
        if (result != RESULT_OK) { reply(id, true, null); return; } // (he went back: no photo)
        Uri src = req == REQ_CAMERA ? Uri.fromFile(cameraFile) : data != null ? data.getData() : null;
        if (src == null) { reply(id, true, null); return; }
        new Thread(() -> {
            String pid = "p" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
            boolean ok = keepPhoto(src, new File(PhotoFiles.dir(this), pid + ".jpg"));
            if (req == REQ_CAMERA && cameraFile != null) //noinspection ResultOfMethodCallIgnored
                cameraFile.delete();
            ui.post(() -> { if (ok) reply(id, true, pid); else reply(id, false, "ఫోటో తెరవలేకపోయాను"); });
        }, "app-photo").start();
    }

    private boolean keepPhoto(Uri src, File to) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(src)) { BitmapFactory.decodeStream(in, null, o); }
            int side = Math.max(o.outWidth, o.outHeight), sample = 1;
            while (side / (sample * 2) >= 1600) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bm;
            try (InputStream in = open(src)) { bm = BitmapFactory.decodeStream(in, null, o2); }
            if (bm == null) return false;
            int turn = 0;
            try (InputStream in = open(src)) {
                int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                turn = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {}
            float k = Math.min(1f, 1600f / Math.max(bm.getWidth(), bm.getHeight()));
            Matrix m = new Matrix();
            m.postScale(k, k);
            m.postRotate(turn);
            Bitmap out = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            try (FileOutputStream f = new FileOutputStream(to)) { out.compress(Bitmap.CompressFormat.JPEG, 82, f); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private InputStream open(Uri u) throws Exception {
        if ("file".equals(u.getScheme())) return new FileInputStream(new File(u.getPath()));
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new IllegalStateException("no photo");
        return in;
    }

    // ---- 📍 where the phone is now (only when the app asks, on his tap; never in the background)

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void where(String id) {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        if (prov == null) { reply(id, false, "ఫోన్‌లో లొకేషన్ ఆఫ్‌లో ఉంది"); return; }
        final boolean[] done = {false};
        java.util.function.Consumer<Location> got = loc -> {
            if (done[0]) return;
            done[0] = true;
            if (loc == null) { reply(id, false, "చోటు దొరకలేదు"); return; }
            try { reply(id, true, new JSONObject().put("lat", loc.getLatitude()).put("lon", loc.getLongitude()).put("accuracy", Math.round(loc.getAccuracy()))); }
            catch (Exception e) { reply(id, false, "చోటు దొరకలేదు"); }
        };
        ui.postDelayed(() -> got.accept(lm.getLastKnownLocation(prov)), 25000); // (no fix in time: the last one known, or none)
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(prov, null, getMainExecutor(), got::accept);
        else lm.requestSingleUpdate(prov, got::accept, Looper.getMainLooper());
    }

    // ---- 📄 a page as a PDF (Android's own print / "Save as PDF" screen)

    private void pdf(String id, String title, String html) {
        String page = "<!DOCTYPE html><html lang=\"te\"><head><meta charset=\"utf-8\"><style>"
                + "body{font-family:'Noto Sans Telugu',sans-serif;font-size:12pt;color:#111;margin:0}h1,h2,h3{margin:6px 0}"
                + "table{width:100%;border-collapse:collapse;margin:8px 0}th,td{border:1px solid #bbb;padding:5px 7px;text-align:left}th{background:#eee}"
                + ".n,.right{text-align:right}img{max-width:100%}</style></head><body>" + html + "</body></html>";
        WebView w = new WebView(this);
        printing = w;
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }

            @Override public void onPageFinished(WebView v, String url) {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                pm.print(title, v.createPrintDocumentAdapter(title), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                reply(id, true, true);
            }
        });
        w.loadDataWithBaseURL("file:///android_asset/", page, "text/html", "utf-8", null);
    }

    // ---- 🗣️ reading aloud (Telugu when the phone has it)

    private void say(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts == null) {
            ttsQueue.add(text);
            tts = new TextToSpeech(getApplicationContext(), st -> {
                if (st != TextToSpeech.SUCCESS) { ui.post(() -> Toast.makeText(this, "చదివే సేవ లేదు", Toast.LENGTH_SHORT).show()); return; }
                int r = tts.setLanguage(new Locale("te", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                ttsReady = true;
                for (String t : ttsQueue) tts.speak(t, TextToSpeech.QUEUE_ADD, null, "say");
                ttsQueue.clear();
            });
            return;
        }
        if (!ttsReady) { ttsQueue.add(text); return; }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
    }

    static JSONArray emptyList() { return new JSONArray(); }
}
