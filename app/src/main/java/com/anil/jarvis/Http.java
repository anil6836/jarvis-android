package com.anil.jarvis;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal JSON over HTTPS using only the platform's HttpURLConnection. */
final class Http {
    /** An error answer from an API, with the HTTP status and its message. */
    static final class ApiError extends Exception {
        final int status;
        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private Http() {}

    static JSONObject post(String url, JSONObject body, String... headers) throws IOException, ApiError {
        return send("POST", url, body, headers);
    }

    /** Plain text GET (for web pages), with a browser-like User-Agent. */
    static String getText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
            int status = c.getResponseCode();
            if (status >= 400) throw new IOException("HTTP " + status);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Set on a thread whose next calls may take long (a question Jarvis thinks deeply about). */
    static final ThreadLocal<Boolean> LONG_WAIT = ThreadLocal.withInitial(() -> false);

    /**
     * Stop for the requests of one question: ⏹ / a new question / the screen closing calls {@link #cancel}, and the
     * request now on its way is cut at once (not left to run up to its 150 s), so the next question never waits behind
     * it. Requests made later on that thread for the same question end at once too.
     */
    static final class Cancel {
        private volatile HttpURLConnection conn;
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) new Thread(() -> { try { c.disconnect(); } catch (Throwable ignored) {} }, "jarvis-cancel").start();
        }

        boolean cancelled() { return cancelled; }
    }

    /** The Stop handle of the question this thread is answering (null: an ordinary request, never cut). */
    static final ThreadLocal<Cancel> CANCEL = new ThreadLocal<>();

    /** The question this thread is answering was stopped (⏹ / a new question): nothing more is started for it. */
    static boolean stopped() {
        Cancel k = CANCEL.get();
        return k != null && k.cancelled;
    }

    static JSONObject get(String url) throws IOException, ApiError {
        return send("GET", url, null);
    }

    static JSONObject get(String url, String... headers) throws IOException, ApiError {
        return send("GET", url, null, headers);
    }

    private static JSONObject send(String method, String url, JSONObject body, String... headers) throws IOException, ApiError {
        Cancel k = CANCEL.get();
        if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
        // (the body first: with the chat and a photo it takes a moment, and a stop in that moment must not be missed)
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (k != null) k.conn = c;
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(LONG_WAIT.get() ? 300000 : 150000);
            c.setRequestProperty("Accept", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            if (bytes != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
            }
            c.connect();
            // a stop before the line was open can't be cut by disconnect(): the flag (set first) is seen here instead
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
            if (bytes != null) {
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped"); // (a cut reply is no reply)
            if (status >= 400) throw new ApiError(status, errorMessage(text));
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (Exception e) {
                throw new ApiError(status, "Unexpected reply: " + text.substring(0, Math.min(200, text.length())));
            }
            try { Usage.fromResponse(url, json); } catch (Throwable ignored) {} // the cost meter never breaks a request
            return json;
        } catch (IOException e) {
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped"); // cut by ⏹: not an error
            throw e;
        } finally {
            if (k != null) k.conn = null;
            c.disconnect();
        }
    }

    /** One piece of a streamed answer (server-sent events: each "data:" line is a JSON object). */
    interface Sse { void data(JSONObject piece) throws Exception; }

    /**
     * ⚡ B: POST, and the answer read as it is written (server-sent events), each piece handed on at once. The same stop,
     * time limits and error messages as post(); the cost meter is the caller's (it puts the pieces together).
     */
    static void postSse(String url, JSONObject body, Sse sink, String... headers) throws Exception {
        Cancel k = CANCEL.get();
        if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (k != null) k.conn = c;
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(LONG_WAIT.get() ? 300000 : 150000);
            c.setRequestProperty("Accept", "text/event-stream");
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            c.connect();
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            if (status >= 400) {
                InputStream es = c.getErrorStream();
                String text = es == null ? "" : readAll(es);
                if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
                throw new ApiError(status, errorMessage(text));
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder event = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
                    if (line.startsWith("data:")) {
                        event.append(line.substring(5).trim());
                        continue;
                    }
                    if (!line.isEmpty() || event.length() == 0) continue; // (comments, other fields)
                    sseEvent(event.toString(), sink);
                    event.setLength(0);
                }
                if (event.length() > 0) sseEvent(event.toString(), sink); // (the last one without its empty line)
            }
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped");
        } catch (IOException e) {
            if (k != null && k.cancelled) throw new java.util.concurrent.CancellationException("stopped"); // cut by ⏹: not an error
            throw e;
        } finally {
            if (k != null) k.conn = null;
            c.disconnect();
        }
    }

    private static void sseEvent(String data, Sse sink) throws Exception {
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        JSONObject o;
        try { o = new JSONObject(data); }
        catch (Exception e) { throw new ApiError(0, "Unexpected reply: " + data.substring(0, Math.min(200, data.length()))); }
        sink.data(o);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }

    private static String errorMessage(String text) {
        try {
            JSONObject o = new JSONObject(text);
            JSONObject e = o.optJSONObject("error");
            if (e != null && e.has("message")) {
                String msg = e.optString("message");
                // Google: which limit ran out (per minute / per day, free tier) is only in the details
                StringBuilder q = new StringBuilder();
                org.json.JSONArray det = e.optJSONArray("details");
                for (int i = 0; det != null && i < det.length(); i++) {
                    org.json.JSONArray v = det.optJSONObject(i) == null ? null : det.optJSONObject(i).optJSONArray("violations");
                    for (int j = 0; v != null && j < v.length(); j++) {
                        String id = v.optJSONObject(j) == null ? "" : v.optJSONObject(j).optString("quotaId");
                        if (!id.isEmpty() && q.indexOf(id) < 0) q.append(q.length() == 0 ? "" : ", ").append(id);
                    }
                }
                String st = e.optString("status");
                if (!st.isEmpty() && !msg.contains(st)) msg += " (" + st + ")";
                if (q.length() > 0) msg += " [" + q + "]";
                return msg;
            }
            if (o.has("message")) return o.optString("message");
            if (o.has("reason")) return o.optString("reason");
        } catch (Exception ignored) {}
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
