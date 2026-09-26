package com.anil.jarvis;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis the programmer: runs Python in the cloud (OpenAI code interpreter, or Claude's code execution),
 * builds websites (one self-contained HTML page, previewed in Jarvis, published on GitHub Pages with
 * his token) and writes code files. Everything it makes is saved in Downloads/Jarvis on the phone.
 */
final class Coder {
    private Coder() {}

    /** One file Jarvis made: its name, where it was saved, and how to open it. */
    static final class Made {
        String name, mime, where;
        Uri uri;
    }

    // ================================================================ which AI writes the code

    /** The AI used for code: his "coding model" (e.g. gpt-6-astra, claude-opus-5-5) if set, else his normal brain. */
    static final class Engine {
        final boolean openAi;
        final String key, model;
        Engine(boolean openAi, String key, String model) { this.openAi = openAi; this.key = key; this.model = model; }
    }

    static Engine engine(Prefs p) {
        String m = p.codeModel().trim();
        if (!m.isEmpty()) {
            boolean claude = m.toLowerCase(Locale.ROOT).startsWith("claude");
            String key = claude ? p.anthropicKey().trim() : p.openAiKey().trim();
            // an honest error, never a quiet switch to some other model
            if (key.isEmpty()) throw new IllegalStateException("కోడింగ్ మోడల్ " + m + " కి " + (claude ? "Anthropic (Claude)" : "OpenAI") + " API key లేదు (Jarvis settings).");
            return new Engine(!claude, key, m);
        }
        String key = p.apiKey().trim();
        if (key.isEmpty()) throw new IllegalStateException("API key లేదు (Jarvis settings).");
        return new Engine(p.isOpenAi(), key, p.model());
    }

    // ================================================================ Python

    private static final String PY_SYSTEM =
            "You are the coding engine of Jarvis, Anil's phone assistant. Solve the task by writing and RUNNING Python with the python tool "
            + "(never guess numbers you can compute). Charts: matplotlib, readable fonts, save as PNG. Tables for him: save as .xlsx (or .csv). "
            + "Documents: save as .pdf or .docx if asked. Save every file for Anil in /mnt/data with a short English file name. "
            + "Telugu text in charts may not render: use English labels in charts. "
            + "Final answer: 1-3 short sentences in simple Telugu with the key result (numbers), and the names of the files you made. No code in the final answer.";

    /** Runs a Python task; returns {answer, files[]} and saves the files. */
    static JSONObject runPython(Context c, Prefs prefs, String task) throws Exception {
        Engine e = engine(prefs);
        return e.openAi ? pythonOpenAi(c, e.key, e.model, task) : pythonClaude(e.key, e.model, task);
    }

    private static JSONObject pythonOpenAi(Context c, String key, String model, String task) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("instructions", PY_SYSTEM)
                .put("input", task)
                .put("tools", new JSONArray().put(new JSONObject().put("type", "code_interpreter")
                        .put("container", new JSONObject().put("type", "auto"))));
        JSONObject res = Http.post("https://api.openai.com/v1/responses", body, "Authorization", "Bearer " + key);
        StringBuilder said = new StringBuilder();
        String container = null;
        JSONArray out = res.optJSONArray("output");
        for (int i = 0; out != null && i < out.length(); i++) {
            JSONObject item = out.getJSONObject(i);
            String type = item.optString("type");
            if ("code_interpreter_call".equals(type) && item.has("container_id")) container = item.optString("container_id");
            if (!"message".equals(type)) continue;
            JSONArray parts = item.optJSONArray("content");
            for (int k = 0; parts != null && k < parts.length(); k++) {
                JSONObject p = parts.getJSONObject(k);
                if (!"output_text".equals(p.optString("type"))) continue;
                said.append(p.optString("text"));
                JSONArray ann = p.optJSONArray("annotations");
                for (int a = 0; ann != null && a < ann.length(); a++) {
                    JSONObject an = ann.getJSONObject(a);
                    if (an.has("container_id") && container == null) container = an.optString("container_id");
                }
            }
        }
        JSONObject result = new JSONObject().put("answer", cleanAnswer(said.toString()));
        JSONArray files = new JSONArray();
        if (container != null && !container.isEmpty()) {
            try {
                JSONObject list = request("GET", "https://api.openai.com/v1/containers/" + container + "/files", null,
                        "Authorization", "Bearer " + key);
                JSONArray data = list.optJSONArray("data");
                for (int i = 0; data != null && i < data.length() && files.length() < 6; i++) {
                    JSONObject f = data.getJSONObject(i);
                    if ("user".equals(f.optString("source"))) continue;
                    String path = f.optString("path", f.optString("filename", ""));
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if (name.isEmpty() || name.startsWith(".")) continue;
                    byte[] bytes = download("https://api.openai.com/v1/containers/" + container + "/files/" + f.optString("id") + "/content",
                            "Authorization", "Bearer " + key);
                    Made m = save(c, "Jarvis", name, mimeOf(name), bytes);
                    files.put(new JSONObject().put("name", m.name).put("saved", m.where).put("uri", String.valueOf(m.uri)).put("mime", m.mime));
                }
            } catch (Exception ignored) {} // the answer still counts even if a file could not be fetched
        }
        return result.put("files", files);
    }

    private static JSONObject pythonClaude(String key, String model, String task) throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", task));
        StringBuilder said = new StringBuilder();
        for (int round = 0; round < 4; round++) {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", 4000)
                    .put("system", PY_SYSTEM + " (Files cannot be sent to his phone from here: put the key numbers in the answer.)")
                    .put("messages", messages)
                    .put("tools", new JSONArray().put(new JSONObject().put("type", "code_execution_20250522").put("name", "code_execution")));
            JSONObject res = Http.post("https://api.anthropic.com/v1/messages", body, "x-api-key", key,
                    "anthropic-version", "2023-06-01", "anthropic-beta", "code-execution-2025-05-22");
            JSONArray content = res.optJSONArray("content");
            said.setLength(0);
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if ("text".equals(b.optString("type"))) said.append(b.optString("text"));
            }
            if (!"pause_turn".equals(res.optString("stop_reason"))) break;
            messages.put(new JSONObject().put("role", "assistant").put("content", content));
        }
        return new JSONObject().put("answer", cleanAnswer(said.toString())).put("files", new JSONArray());
    }

    private static String cleanAnswer(String s) {
        return s.replaceAll("\\[([^\\]]*)\\]\\(sandbox:[^)]*\\)", "$1").replaceAll("sandbox:/mnt/data/", "").trim();
    }

    // ================================================================ websites

    private static final String WEB_SYSTEM =
            "You are an expert web designer and developer. Make ONE complete, beautiful, modern, mobile-first web page as a single self-contained "
            + "HTML file: all CSS in <style> and all JavaScript in <script> inside the file; no external files, no CDNs, no frameworks, no web fonts "
            + "(use system fonts). Use real, sensible content (not lorem ipsum); Telugu text when he asks for Telugu. Make it work on a phone screen and on a computer. "
            + "Reply with ONLY the HTML, starting with <!DOCTYPE html>.";

    /** Builds (or changes) a website; returns the file and a slug for publishing. */
    static JSONObject makeWebsite(Context c, Prefs prefs, String description, String change) throws Exception {
        File dir = new File(c.getFilesDir(), "sites");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String slug = prefs.lastSite();
        File old = slug.isEmpty() ? null : new File(dir, slug + ".html");
        String prompt;
        if (change != null && !change.trim().isEmpty() && old != null && old.exists()) {
            prompt = "Here is the current page:\n\n" + read(old) + "\n\nChange it like this: " + change.trim()
                    + "\nKeep everything else. Reply with the whole new HTML.";
        } else {
            if (description == null || description.trim().isEmpty()) throw new IllegalStateException("ఎలాంటి వెబ్‌సైట్ కావాలి?");
            prompt = "Make this website: " + description.trim();
            slug = slug(description);
        }
        String html = ask(prefs, WEB_SYSTEM, prompt, 12000);
        html = stripFences(html);
        int start = html.toLowerCase(Locale.ROOT).indexOf("<!doctype");
        if (start < 0) start = html.toLowerCase(Locale.ROOT).indexOf("<html");
        if (start > 0) html = html.substring(start);
        if (!html.toLowerCase(Locale.ROOT).contains("<html")) throw new IllegalStateException("వెబ్‌సైట్ సరిగా రాలేదు, మళ్లీ అడగండి");
        File f = new File(dir, slug + ".html");
        try (OutputStream o = new FileOutputStream(f)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
        prefs.setLastSite(slug);
        Made m = save(c, "Jarvis/websites", slug + ".html", "text/html", html.getBytes(StandardCharsets.UTF_8));
        String title = "";
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("(?is)<title>(.*?)</title>").matcher(html);
        if (t.find()) title = t.group(1).trim();
        return new JSONObject().put("slug", slug).put("title", title).put("file", f.getPath()).put("saved", m.where).put("uri", String.valueOf(m.uri))
                .put("size_kb", html.length() / 1024);
    }

    static File siteFile(Context c, String slug) { return new File(new File(c.getFilesDir(), "sites"), slug + ".html"); }

    /** Puts the website on GitHub Pages (repo jarvis-sites in his account); returns the link. */
    static String publish(Context c, Prefs prefs, String slug) throws Exception {
        String token = prefs.githubToken().trim();
        if (token.isEmpty()) throw new IllegalStateException("no_token");
        if (slug == null || slug.isEmpty()) slug = prefs.lastSite();
        File f = siteFile(c, slug);
        if (!f.exists()) throw new IllegalStateException("ఇంకా ఏ వెబ్‌సైట్ తయారు చేయలేదు");
        String[] auth = {"Authorization", "Bearer " + token, "Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"};
        String login = request("GET", "https://api.github.com/user", null, auth).optString("login");
        if (login.isEmpty()) throw new IllegalStateException("GitHub token పనిచేయడం లేదు");
        String repo = "https://api.github.com/repos/" + login + "/jarvis-sites";
        try {
            request("GET", repo, null, auth);
        } catch (Http.ApiError e) {
            if (e.status != 404) throw e;
            request("POST", "https://api.github.com/user/repos", new JSONObject().put("name", "jarvis-sites")
                    .put("description", "Websites made by Jarvis").put("auto_init", true).put("homepage", "https://" + login + ".github.io/jarvis-sites/"), auth);
            Thread.sleep(2500);
        }
        String path = repo + "/contents/" + slug + "/index.html";
        String sha = null;
        try { sha = request("GET", path, null, auth).optString("sha", null); } catch (Http.ApiError e) { if (e.status != 404) throw e; }
        JSONObject put = new JSONObject().put("message", "Jarvis: " + slug)
                .put("content", Base64.encodeToString(read(f).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        if (sha != null && !sha.isEmpty()) put.put("sha", sha);
        request("PUT", path, put, auth);
        try {
            request("POST", repo + "/pages", new JSONObject().put("source", new JSONObject().put("branch", "main").put("path", "/")), auth);
        } catch (Http.ApiError e) {
            if (e.status != 409 && e.status != 422) throw e; // already on
        }
        return "https://" + login + ".github.io/jarvis-sites/" + slug + "/";
    }

    // ================================================================ code files

    private static final String CODE_SYSTEM =
            "You are an expert programmer. Write the complete, working, well-structured file he asked for, with short comments. "
            + "Reply with ONLY the file content (no explanation, no markdown fences).";

    /** Writes a code file; returns where it was saved. */
    static JSONObject writeCode(Context c, Prefs prefs, String filename, String description) throws Exception {
        String name = filename == null ? "" : filename.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = "jarvis_code.py";
        String code = stripFences(ask(prefs, CODE_SYSTEM, "File name: " + name + "\nWhat it must do: " + description, 12000));
        Made m = save(c, "Jarvis/code", name, mimeOf(name), code.getBytes(StandardCharsets.UTF_8));
        File local = new File(new File(c.getFilesDir(), "code"), name);
        //noinspection ResultOfMethodCallIgnored
        local.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(local)) { o.write(code.getBytes(StandardCharsets.UTF_8)); }
        int lines = code.split("\n", -1).length;
        return new JSONObject().put("name", m.name).put("saved", m.where).put("lines", lines).put("file", local.getPath()).put("uri", String.valueOf(m.uri));
    }

    // ================================================================ helpers

    /** One long answer from his AI (up to maxTokens), for code and web pages. */
    static String ask(Prefs prefs, String system, String prompt, int maxTokens) throws Exception {
        Engine e = engine(prefs);
        String key = e.key;
        if (e.openAi) {
            JSONObject body = new JSONObject().put("model", e.model).put("instructions", system).put("input", prompt)
                    .put("max_output_tokens", maxTokens);
            JSONObject res = Http.post("https://api.openai.com/v1/responses", body, "Authorization", "Bearer " + key);
            StringBuilder said = new StringBuilder();
            JSONArray out = res.optJSONArray("output");
            for (int i = 0; out != null && i < out.length(); i++) {
                JSONObject item = out.getJSONObject(i);
                if (!"message".equals(item.optString("type"))) continue;
                JSONArray parts = item.optJSONArray("content");
                for (int k = 0; parts != null && k < parts.length(); k++) {
                    JSONObject p = parts.getJSONObject(k);
                    if ("output_text".equals(p.optString("type"))) said.append(p.optString("text"));
                }
            }
            if (said.length() == 0) throw new Http.ApiError(0, "empty reply");
            return said.toString();
        }
        JSONObject body = new JSONObject().put("model", e.model).put("max_tokens", Math.min(maxTokens, 16000))
                .put("system", system)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
        JSONObject res = Http.post("https://api.anthropic.com/v1/messages", body, "x-api-key", key, "anthropic-version", "2023-06-01");
        StringBuilder said = new StringBuilder();
        JSONArray content = res.optJSONArray("content");
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject b = content.getJSONObject(i);
            if ("text".equals(b.optString("type"))) said.append(b.optString("text"));
        }
        if (said.length() == 0) throw new Http.ApiError(0, "empty reply");
        return said.toString();
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl > 0 ? t.substring(nl + 1) : t.substring(3);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim() + "\n";
    }

    private static String slug(String description) {
        String s = description.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 30) s = s.substring(0, 30).replaceAll("-+$", "");
        if (s.isEmpty()) s = "site";
        return s + "-" + Long.toString(System.currentTimeMillis() % 100000, 36);
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        return "text/plain"; // code: .py .java .kt .js .c .cpp .txt ...
    }

    /** Saves bytes in Downloads/<folder> (visible in the Files app). */
    static Made save(Context c, String folder, String name, String mime, byte[] bytes) throws Exception {
        Made m = new Made();
        m.name = name;
        m.mime = mime;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("ఫైల్ సేవ్ కాలేదు");
            try (OutputStream o = cr.openOutputStream(uri)) {
                if (o == null) throw new IllegalStateException("ఫైల్ సేవ్ కాలేదు");
                o.write(bytes);
            }
            m.uri = uri;
            m.where = "Downloads/" + folder + "/" + name;
        } else {
            File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), folder);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (OutputStream o = new FileOutputStream(f)) { o.write(bytes); }
            m.uri = null;
            m.where = f.getPath();
        }
        return m;
    }

    private static JSONObject request(String method, String url, JSONObject body, String... headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod(method);
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

    private static byte[] download(String url, String... headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            for (int i = 0; i + 1 < headers.length; i += 2) h.setRequestProperty(headers[i], headers[i + 1]);
            if (h.getResponseCode() >= 400) throw new IllegalStateException("file " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) { return readAll(in); }
        } finally {
            h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Files the user can see were made by Jarvis (for listing). */
    static List<String> savedSites(Context c) {
        List<String> out = new ArrayList<>();
        File[] fs = new File(c.getFilesDir(), "sites").listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".html")) out.add(f.getName().replace(".html", ""));
        return out;
    }
}
