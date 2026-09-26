package com.anil.jarvis;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Makes Android apps: the AI writes the app (an HTML/JavaScript app inside a native Android shell),
 * Jarvis puts it in a new repo in Anil's GitHub (one commit) where GitHub Actions builds the APK, watches
 * the build, and notifies him; tapping the notification downloads and installs it (Android asks him).
 */
final class AppMaker {
    private AppMaker() {}

    static final String EXTRA_INSTALL = "install_app_repo";
    private static final String CHANNEL = "jarvis_apps";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final String APP_SYSTEM =
            "You are an expert mobile app developer. Make ONE complete Android phone app as a single self-contained HTML file that runs inside "
            + "a full-screen WebView: all CSS in <style>, all JavaScript in <script>, no external files, no CDNs, no frameworks, no web fonts. "
            + "It must feel like a real native app: full-screen, touch-friendly big buttons, no page zoom (viewport user-scalable=no), "
            + "a top app bar with the app name, smooth animations, dark or light theme that looks polished. Save the user's data with localStorage "
            + "so it is still there next time. Work offline unless the app needs the internet. Use real content, Telugu text when he asks for Telugu. "
            + "Reply with ONLY the HTML, starting with <!DOCTYPE html>.";

    /** Starts building an app; returns at once (the build runs on GitHub, a notification comes when it is ready). */
    static JSONObject make(Context ctx, Prefs prefs, String name, String description, String change) throws Exception {
        Context c = ctx.getApplicationContext();
        String token = prefs.githubToken().trim();
        if (token.isEmpty()) throw new IllegalStateException("no_token");
        String[] auth = auth(token);
        String login = request("GET", "https://api.github.com/user", null, auth).optString("login");
        if (login.isEmpty()) throw new IllegalStateException("GitHub token పనిచేయడం లేదు");

        boolean changing = change != null && !change.trim().isEmpty() && !prefs.lastAppRepo().isEmpty();
        String slug, appName, html;
        if (changing) {
            slug = prefs.lastAppRepo().replaceFirst("^jarvis-app-", "");
            appName = prefs.lastAppName();
            File old = htmlFile(c, slug);
            String prev = old.exists() ? read(old) : "";
            html = Coder.ask(prefs, APP_SYSTEM, "Here is the current app:\n\n" + prev + "\n\nChange it like this: " + change.trim()
                    + "\nKeep everything else. Reply with the whole new HTML.", 14000);
        } else {
            if (description == null || description.trim().isEmpty()) throw new IllegalStateException("ఎలాంటి యాప్ కావాలి?");
            appName = name == null || name.trim().isEmpty() ? "My App" : name.trim();
            slug = slug(appName);
            html = Coder.ask(prefs, APP_SYSTEM, "App name: " + appName + "\nWhat the app must do: " + description.trim(), 14000);
        }
        html = cleanHtml(html);
        File local = htmlFile(c, slug);
        //noinspection ResultOfMethodCallIgnored
        local.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(local)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }

        String repo = "jarvis-app-" + slug;
        String api = "https://api.github.com/repos/" + login + "/" + repo;
        boolean exists;
        try { request("GET", api, null, auth); exists = true; } catch (Http.ApiError e) { if (e.status != 404) throw e; exists = false; }
        if (!exists) {
            request("POST", "https://api.github.com/user/repos", new JSONObject().put("name", repo)
                    .put("description", appName + " — Android app made by Jarvis").put("auto_init", true), auth);
            for (int i = 0; i < 10; i++) { // the first commit appears a moment later
                Thread.sleep(1500);
                try { request("GET", api + "/git/ref/heads/main", null, auth); break; } catch (Http.ApiError ignored) {}
            }
        }
        String pkg = "com.jarvis.apps." + slug.replaceAll("[^a-z0-9]", "");
        if (!Character.isLetter(pkg.charAt(pkg.lastIndexOf('.') + 1))) pkg = pkg.substring(0, pkg.lastIndexOf('.') + 1) + "a" + pkg.substring(pkg.lastIndexOf('.') + 1);

        // One commit with the whole project (one build).
        JSONObject ref = request("GET", api + "/git/ref/heads/main", null, auth);
        String parent = ref.getJSONObject("object").getString("sha");
        String baseTree = request("GET", api + "/git/commits/" + parent, null, auth).getJSONObject("tree").getString("sha");
        JSONArray tree = new JSONArray();
        for (String[] f : files(pkg, appName, html)) tree.put(new JSONObject().put("path", f[0]).put("mode", "100644").put("type", "blob").put("content", f[1]));
        byte[] ks = readAsset(c, "jarvis-apps.keystore");
        String ksBlob = request("POST", api + "/git/blobs", new JSONObject().put("content", Base64.encodeToString(ks, Base64.NO_WRAP)).put("encoding", "base64"), auth).getString("sha");
        tree.put(new JSONObject().put("path", "app/jarvis-apps.keystore").put("mode", "100644").put("type", "blob").put("sha", ksBlob));
        String treeSha = request("POST", api + "/git/trees", new JSONObject().put("base_tree", baseTree).put("tree", tree), auth).getString("sha");
        String commit = request("POST", api + "/git/commits", new JSONObject().put("message", (changing ? "Jarvis: change " : "Jarvis: new app ") + appName)
                .put("tree", treeSha).put("parents", new JSONArray().put(parent)), auth).getString("sha");
        request("PATCH", api + "/git/refs/heads/main", new JSONObject().put("sha", commit), auth);

        prefs.setLastApp(repo, appName);
        watch(c, token, login + "/" + repo, commit, appName);
        return new JSONObject().put("app", appName).put("repo", login + "/" + repo).put("package", pkg).put("preview", local.getPath())
                .put("page", "https://github.com/" + login + "/" + repo);
    }

    static File htmlFile(Context c, String slug) { return new File(new File(c.getFilesDir(), "apps"), slug + ".html"); }

    /** Watches the GitHub build of that commit; a notification when the APK is ready (or the build failed). */
    private static void watch(Context c, String token, String repo, String commit, String appName) {
        new Thread(() -> {
            String[] auth = auth(token);
            long end = System.currentTimeMillis() + 15 * 60 * 1000L;
            try {
                Thread.sleep(20000);
                while (System.currentTimeMillis() < end) {
                    JSONObject runs = request("GET", "https://api.github.com/repos/" + repo + "/actions/runs?head_sha=" + commit + "&per_page=1", null, auth);
                    JSONArray list = runs.optJSONArray("workflow_runs");
                    if (list != null && list.length() > 0) {
                        JSONObject r = list.getJSONObject(0);
                        if ("completed".equals(r.optString("status"))) {
                            boolean ok = "success".equals(r.optString("conclusion"));
                            if (ok) Thread.sleep(8000); // the release upload finishes right after
                            notify(c, repo, appName, ok);
                            return;
                        }
                    }
                    Thread.sleep(15000);
                }
                notify(c, repo, appName, false);
            } catch (Exception ignored) {}
        }, "jarvis-app-build").start();
    }

    private static void notify(Context c, String repo, String appName, boolean ok) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis తయారు చేసిన యాప్‌లు", NotificationManager.IMPORTANCE_HIGH));
            Intent open = ok
                    ? new Intent(c, MainActivity.class).putExtra(EXTRA_INSTALL, repo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + repo + "/actions")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, repo.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(repo.hashCode(), new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                    .setContentTitle(ok ? appName + " యాప్ సిద్ధం ✓" : appName + " యాప్ బిల్డ్ కాలేదు")
                    .setContentText(ok ? "ఇన్‌స్టాల్ చేయడానికి నొక్కండి" : "GitHub లో ఏమైందో చూడటానికి నొక్కండి; Jarvis కి 'మళ్లీ ప్రయత్నించు' అని చెప్పండి")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {}
    }

    /** Tapped "యాప్ సిద్ధం": download the APK and install it (Android shows its own "Install?"). */
    static void install(Activity a, String repo) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "ఒక్కసారి: Jarvis కి \"Allow from this source\" ఆన్ చేసి, మళ్లీ నోటిఫికేషన్ నొక్కండి", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            }
            return;
        }
        Toast.makeText(a, "యాప్ డౌన్‌లోడ్ అవుతోంది…", Toast.LENGTH_SHORT).show();
        Context c = a.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session s = null;
            try {
                File apk = new File(c.getCacheDir(), "made-app.apk");
                download("https://github.com/" + repo + "/releases/latest/download/app.apk", apk);
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setSize(apk.length());
                s = pi.openSession(pi.createSession(params));
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION_APP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, 76, done, flags).getIntentSender());
                s.close();
                s = null;
            } catch (Exception e) {
                if (s != null) try { s.abandon(); } catch (Exception ignored) {}
                String msg = "యాప్ ఇన్‌స్టాల్ కాలేదు: " + e.getMessage();
                main.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
            }
        }, "jarvis-app-install").start();
    }

    // ------------------------------------------------------------------ the project

    private static String[][] files(String pkg, String appName, String html) {
        String path = pkg.replace('.', '/');
        String xmlName = appName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace("\"", "\\\"");
        if (xmlName.startsWith("@") || xmlName.startsWith("?")) xmlName = "\\" + xmlName; // not a resource reference
        int hue = Math.abs(appName.hashCode()) % 360;
        String color = String.format(Locale.ROOT, "#%06X", android.graphics.Color.HSVToColor(new float[]{hue, 0.65f, 0.85f}) & 0xFFFFFF);
        return new String[][]{
                {"settings.gradle", "pluginManagement {\n    repositories {\n        google()\n        mavenCentral()\n        gradlePluginPortal()\n    }\n}\n"
                        + "dependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {\n        google()\n        mavenCentral()\n    }\n}\n"
                        + "rootProject.name = \"App\"\ninclude ':app'\n"},
                {"build.gradle", "plugins {\n    id 'com.android.application' version '8.7.3' apply false\n}\n"},
                {"gradle.properties", "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.nonTransitiveRClass=true\n"},
                {"app/build.gradle", "plugins {\n    id 'com.android.application'\n}\n\n"
                        + "def runNumber = (System.getenv('GITHUB_RUN_NUMBER') ?: '1').toInteger()\n\n"
                        + "android {\n    namespace '" + pkg + "'\n    compileSdk 34\n\n"
                        + "    defaultConfig {\n        applicationId '" + pkg + "'\n        minSdk 24\n        targetSdk 34\n        versionCode runNumber\n        versionName \"1.${runNumber}\"\n    }\n\n"
                        + "    signingConfigs {\n        jarvis {\n            storeFile file('jarvis-apps.keystore')\n            storePassword 'jarvisapps'\n            keyAlias 'jarvisapps'\n            keyPassword 'jarvisapps'\n        }\n    }\n\n"
                        + "    buildTypes {\n        release {\n            minifyEnabled false\n            signingConfig signingConfigs.jarvis\n        }\n    }\n\n"
                        + "    compileOptions {\n        sourceCompatibility JavaVersion.VERSION_17\n        targetCompatibility JavaVersion.VERSION_17\n    }\n\n"
                        + "    lint {\n        abortOnError false\n        checkReleaseBuilds false\n    }\n}\n"},
                {"app/src/main/AndroidManifest.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <uses-permission android:name=\"android.permission.INTERNET\" />\n"
                        + "    <application\n        android:label=\"@string/app_name\"\n        android:icon=\"@drawable/ic_launcher\"\n"
                        + "        android:roundIcon=\"@drawable/ic_launcher\"\n        android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\"\n"
                        + "        android:hardwareAccelerated=\"true\">\n"
                        + "        <activity\n            android:name=\".MainActivity\"\n            android:exported=\"true\"\n"
                        + "            android:configChanges=\"orientation|screenSize|keyboardHidden|screenLayout|uiMode\">\n"
                        + "            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n"
                        + "        </activity>\n    </application>\n</manifest>\n"},
                {"app/src/main/java/" + path + "/MainActivity.java", "package " + pkg + ";\n\n"
                        + "import android.app.Activity;\nimport android.os.Bundle;\nimport android.webkit.WebChromeClient;\nimport android.webkit.WebSettings;\n"
                        + "import android.webkit.WebView;\nimport android.webkit.WebViewClient;\n\n"
                        + "/** Made by Jarvis: the app is the HTML page in assets/index.html. */\n"
                        + "public class MainActivity extends Activity {\n    private WebView web;\n\n"
                        + "    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        web = new WebView(this);\n"
                        + "        WebSettings s = web.getSettings();\n        s.setJavaScriptEnabled(true);\n        s.setDomStorageEnabled(true);\n"
                        + "        s.setDatabaseEnabled(true);\n        s.setMediaPlaybackRequiresUserGesture(false);\n"
                        + "        web.setWebChromeClient(new WebChromeClient());\n        web.setWebViewClient(new WebViewClient());\n        setContentView(web);\n"
                        + "        if (state != null) web.restoreState(state); else web.loadUrl(\"file:///android_asset/index.html\");\n    }\n\n"
                        + "    @Override protected void onSaveInstanceState(Bundle out) {\n        super.onSaveInstanceState(out);\n        web.saveState(out);\n    }\n\n"
                        + "    @Override @SuppressWarnings(\"deprecation\") public void onBackPressed() {\n        if (web.canGoBack()) web.goBack(); else super.onBackPressed();\n    }\n}\n"},
                {"app/src/main/res/values/strings.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">" + xmlName + "</string>\n</resources>\n"},
                {"app/src/main/res/drawable/ic_launcher.xml", "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:width=\"108dp\" android:height=\"108dp\" android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n"
                        + "    <path android:fillColor=\"" + color + "\" android:pathData=\"M54,4a50,50 0,1 1,0 100a50,50 0,1 1,0 -100z\"/>\n"
                        + "    <path android:fillColor=\"#FFFFFF\" android:pathData=\"M54,26l7.6,17.4l18.9,1.6l-14.4,12.4l4.4,18.5l-16.5,-10l-16.5,10l4.4,-18.5l-14.4,-12.4l18.9,-1.6z\"/>\n"
                        + "</vector>\n"},
                {"app/src/main/assets/index.html", html},
                {".github/workflows/build.yml", "name: Build APK\n\non:\n  push:\n  workflow_dispatch:\n\npermissions:\n  contents: write\n\n"
                        + "jobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n\n"
                        + "      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '17'\n\n"
                        + "      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: '8.9'\n\n"
                        + "      - name: Build APK\n        run: gradle assembleRelease --no-daemon --stacktrace\n\n"
                        + "      - name: Rename APK\n        run: cp app/build/outputs/apk/release/app-release.apk app.apk\n\n"
                        + "      - name: Publish release\n        uses: softprops/action-gh-release@v2\n        with:\n"
                        + "          tag_name: v${{ github.run_number }}\n          name: Build ${{ github.run_number }}\n          files: app.apk\n          make_latest: true\n"},
                {"README.md", "# " + appName + "\n\nAndroid app made by Jarvis. Download: [app.apk](../../releases/latest/download/app.apk)\n"},
        };
    }

    // ------------------------------------------------------------------ helpers

    private static String[] auth(String token) {
        return new String[]{"Authorization", "Bearer " + token, "Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"};
    }

    private static String cleanHtml(String html) {
        String t = html.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl > 0 ? t.substring(nl + 1) : t;
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        int start = t.toLowerCase(Locale.ROOT).indexOf("<!doctype");
        if (start < 0) start = t.toLowerCase(Locale.ROOT).indexOf("<html");
        if (start > 0) t = t.substring(start);
        if (!t.toLowerCase(Locale.ROOT).contains("<html")) throw new IllegalStateException("యాప్ సరిగా రాలేదు, మళ్లీ అడగండి");
        return t.trim() + "\n";
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 24) s = s.substring(0, 24).replaceAll("-+$", "");
        if (s.isEmpty()) s = "app";
        return s + "-" + Long.toString(System.currentTimeMillis() % 100000, 36);
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    private static byte[] readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) { return readAll(in); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static JSONObject request(String method, String url, JSONObject body, String... headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            try {
                h.setRequestMethod(method); // Android's connection supports PATCH
            } catch (java.net.ProtocolException e) {
                h.setRequestMethod("POST"); // GitHub also accepts POST for its PATCH endpoints
                h.setRequestProperty("X-HTTP-Method-Override", method);
            }
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            for (int i = 0; i + 1 < headers.length; i += 2) h.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = h.getOutputStream()) { o.write(b); }
            }
            int status = h.getResponseCode();
            InputStream in = status >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            if (status >= 400) {
                String msg = text;
                try { msg = new JSONObject(text).optString("message", text); } catch (Exception ignored) {}
                throw new Http.ApiError(status, msg.length() > 200 ? msg.substring(0, 200) : msg);
            }
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { url = h.getHeaderField("Location"); h.disconnect(); if (url == null) break; continue; }
            if (code != 200) { h.disconnect(); throw new IllegalStateException("డౌన్‌లోడ్ " + code); }
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                h.disconnect();
            }
            return;
        }
        throw new IllegalStateException("డౌన్‌లోడ్ లింక్ దొరకలేదు");
    }
}
