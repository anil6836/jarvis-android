package com.anil.jarvis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 🌱 Jarvis's side of the plant board's locked messenger: orders go to the board's order topic on ntfy.sh, the board's
 * words come back on its own topic, both locked with {@link PlantCrypto} (ntfy.sh only carries locked text; the topic
 * names come from the key and are never shown). The board talks to the phone this way from anywhere: no home tablet, no
 * Telegram. Pure Java (tests on a PC against a pretend ntfy).
 */
final class PlantLink {
    static String base = "https://ntfy.sh";

    final PlantCrypto lock;

    PlantLink(byte[] key) {
        lock = new PlantCrypto(key);
    }

    /** One thing the board said (opened), with the relay's id and time. */
    static final class Said {
        final String id, json;
        final long time;
        Said(String id, long time, String json) { this.id = id; this.time = time; this.json = json; }
    }

    void send(String json) throws IOException {
        HttpURLConnection c = open(base + "/" + lock.down, "POST");
        try {
            byte[] b = lock.seal(json, PlantCrypto.DOWN).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            int code = c.getResponseCode();
            drain(c);
            if (code == 429) throw new IOException("ntfy.sh: too many messages today");
            if (code / 100 != 2) throw new IOException("ntfy.sh answered " + code);
        } finally {
            c.disconnect();
        }
    }

    /** What the board said since this unix second (only what opens with the key; anything else is dropped). */
    List<Said> poll(long since) throws IOException {
        HttpURLConnection c = open(base + "/" + lock.up + "/json?poll=1&since=" + Math.max(0, since), "GET");
        List<Said> out = new ArrayList<>();
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                drain(c);
                throw new IOException("ntfy.sh answered " + code);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                int n = 0;
                while ((l = r.readLine()) != null && n++ < 500) {
                    if (!"message".equals(str(l, "event"))) continue;
                    String m = str(l, "message");
                    if (m == null) continue;
                    String j = lock.open(m, PlantCrypto.UP);
                    if (j != null) out.add(new Said(str(l, "id"), num(l, "time"), j));
                }
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Jarvis-plant");
        return c;
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return;
            byte[] t = new byte[1024];
            while (in.read(t) > 0) { /* (the relay's own reply: not needed) */ }
            in.close();
        } catch (IOException ignored) {}
    }

    // ---- the relay's JSON lines: a few top-level fields, read without a JSON library (so this runs on a PC too)

    static String str(String line, String key) {
        int i = field(line, key);
        if (i < 0 || i >= line.length() || line.charAt(i) != '"') return null;
        StringBuilder s = new StringBuilder();
        for (i++; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') return s.toString();
            if (ch != '\\') { s.append(ch); continue; }
            if (++i >= line.length()) return null;
            char e = line.charAt(i);
            switch (e) {
                case 'n': s.append('\n'); break;
                case 't': s.append('\t'); break;
                case 'r': s.append('\r'); break;
                case 'b': s.append('\b'); break;
                case 'f': s.append('\f'); break;
                case 'u':
                    if (i + 4 >= line.length()) return null;
                    try { s.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16)); }
                    catch (NumberFormatException x) { return null; }
                    i += 4;
                    break;
                default: s.append(e); // (\" \\ \/)
            }
        }
        return null;
    }

    static long num(String line, String key) {
        int i = field(line, key);
        if (i < 0) return 0;
        int j = i;
        while (j < line.length() && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '-')) j++;
        try { return Long.parseLong(line.substring(i, j)); } catch (NumberFormatException e) { return 0; }
    }

    /** Where the value of "key" starts (after the colon and spaces), or -1. */
    private static int field(String line, String key) {
        String k = "\"" + key + "\"";
        int i = 0;
        while ((i = line.indexOf(k, i)) >= 0) {
            int j = i + k.length();
            while (j < line.length() && line.charAt(j) == ' ') j++;
            if (j < line.length() && line.charAt(j) == ':') {
                j++;
                while (j < line.length() && line.charAt(j) == ' ') j++;
                return j;
            }
            i = j;
        }
        return -1;
    }
}
