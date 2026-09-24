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

    static JSONObject get(String url) throws IOException, ApiError {
        return send("GET", url, null);
    }

    private static JSONObject send(String method, String url, JSONObject body, String... headers) throws IOException, ApiError {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(150000);
            c.setRequestProperty("Accept", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status >= 400) throw new ApiError(status, errorMessage(text));
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new ApiError(status, "Unexpected reply: " + text.substring(0, Math.min(200, text.length())));
            }
        } finally {
            c.disconnect();
        }
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
            if (e != null && e.has("message")) return e.optString("message");
            if (o.has("message")) return o.optString("message");
            if (o.has("reason")) return o.optString("reason");
        } catch (Exception ignored) {}
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
