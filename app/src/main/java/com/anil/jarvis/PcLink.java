package com.anil.jarvis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 💻 The phone's side of the messenger to Jarvis PC (jarvis-pc src/main/link.js; spec: jarvis-pc-plan.md "Phone ↔ PC link"):
 * the laptop's QR gives the key, words to the laptop go to its topic on ntfy.sh, its words come back on the phone's topic,
 * all locked with {@link PcCrypto}. Inside: {"v":1,"id":"16 hex","t":unix s,"k":kind,…}; a message is taken only once, and
 * only within 5 minutes of when it was sent (a bye at any age). Phase 1 kinds: hello / hello_ok, ping / pong, bye; phase 2:
 * cmd / ask → result (the whole words stay in Msg.json, read with org.json on the phone). Pure Java.
 */
final class PcLink {
    static String base = "https://ntfy.sh";
    static final String QR_PREFIX = "jarvispc:";
    private static final Pattern QR = Pattern.compile("^jarvispc:1:([A-Za-z0-9_-]{43}):(.*)$", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{16}$");
    static final int WINDOW_S = 300, KEEP_IDS = 200;

    final PcCrypto lock;

    PcLink(byte[] key) {
        lock = new PcCrypto(key);
    }

    // ================================================================ the laptop's QR

    /** Any Jarvis PC QR (even a newer version): it is a key, so it never goes to an AI or onto the screen. */
    static boolean isQr(String code) { return code != null && code.trim().toLowerCase(java.util.Locale.ROOT).startsWith(QR_PREFIX); }

    static final class Qr {
        final byte[] key;
        final String name;
        Qr(byte[] key, String name) { this.key = key; this.name = name; }
    }

    /** "jarvispc:1:<K base64url, 43>:<PC name, URI-encoded>" → the key and the laptop's name, or null. */
    static Qr parseQr(String code) {
        if (code == null) return null;
        Matcher m = QR.matcher(code.trim());
        if (!m.matches()) return null;
        byte[] k;
        try { k = Base64.getUrlDecoder().decode(m.group(1)); } catch (IllegalArgumentException e) { return null; }
        if (k.length != 32) return null;
        String name;
        try { name = URLDecoder.decode(m.group(2).replace("+", "%2B"), "UTF-8"); } catch (Exception e) { name = m.group(2); }
        return new Qr(k, clean(name, 40));
    }

    /** (for tests and for the laptop's side: the QR text of a key and a name) */
    static String qrText(byte[] key, String name) {
        String n;
        try { n = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"); } catch (Exception e) { n = ""; }
        return "jarvispc:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(key) + ":" + n;
    }

    /**
     * His words for the laptop as he said them: a leading "ల్యాప్‌టాప్‌కి:" (the watch's 💻 screen adds it for the phone's
     * brain) or "laptop:" is taken off, so his "అవును" reaches Jarvis PC as "అవును" (its yes check takes only a short yes).
     */
    static String forLaptop(String text) {
        if (text == null) return "";
        String t = text.trim();
        String[] heads = {"ల్యాప్‌టాప్‌కి:", "ల్యాప్‌టాప్ కి:", "ల్యాప్‌టాప్‌కి :", "లాప్‌టాప్‌కి:", "laptop:", "to laptop:", "to the laptop:"};
        boolean again = true;
        while (again) {
            again = false;
            for (String h : heads) {
                if (t.toLowerCase(java.util.Locale.ROOT).startsWith(h)) { t = t.substring(h.length()).trim(); again = true; }
            }
        }
        return t;
    }

    /** One line of plain text, at most max characters (no control characters). */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch >= ' ' && ch != 0x7F) b.append(ch);
        }
        String r = b.toString().trim();
        if (r.length() > max) r = r.substring(0, max);
        if (!r.isEmpty() && Character.isHighSurrogate(r.charAt(r.length() - 1))) r = r.substring(0, r.length() - 1);
        return r;
    }

    // ================================================================ the words inside

    /** {"v":1,"id":…,"t":…,"k":kind, then the given string fields} */
    static String message(String kind, String id, long t, String... fields) {
        StringBuilder s = new StringBuilder("{\"v\":1,\"id\":\"").append(id).append("\",\"t\":").append(t).append(",\"k\":\"").append(esc(kind)).append('"');
        for (int i = 0; i + 1 < fields.length; i += 2) s.append(",\"").append(esc(fields[i])).append("\":\"").append(esc(fields[i + 1])).append('"');
        return s.append('}').toString();
    }

    static String message(String kind, String... fields) {
        return message(kind, PcCrypto.newId(), System.currentTimeMillis() / 1000, fields);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.toString();
    }

    /** One message from the laptop, taken. */
    static final class Msg {
        final String kind, id, name, app, json;
        final long t;
        Msg(String kind, String id, long t, String name, String app, String json) { this.kind = kind; this.id = id; this.t = t; this.name = name; this.app = app; this.json = json; }
    }

    /**
     * The opened words → a message, or null when it must be dropped: not v 1, a bad or already seen id (seen gets this id),
     * or (except a bye) sent more than 5 minutes away from the relay's time or from now.
     */
    static Msg accept(String json, long relayTime, long now, Set<String> seen) {
        if (json == null) return null;
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return null;
        if (PlantLink.num(s, "v") != 1) return null;
        String id = PlantLink.str(s, "id"), kind = PlantLink.str(s, "k");
        if (id == null || !ID.matcher(id).matches() || kind == null || kind.isEmpty()) return null;
        long t = PlantLink.num(s, "t");
        if (t <= 0) return null;
        // a bye counts at any age (agreed with the PC side 28 Nov: an unpair on the laptop read late still unpairs here);
        // it is still locked and still taken only once
        if (!kind.equals("bye") && (Math.abs(t - relayTime) > WINDOW_S || Math.abs(now - t) > WINDOW_S)) return null;
        if (seen != null) {
            if (seen.contains(id)) return null;
            seen.add(id);
            while (seen.size() > KEEP_IDS) { // (the oldest go first: a LinkedHashSet keeps the order)
                java.util.Iterator<String> it = seen.iterator();
                it.next();
                it.remove();
            }
        }
        return new Msg(kind, id, t, clean(PlantLink.str(s, "name"), 40), clean(PlantLink.str(s, "app"), 24), s);
    }

    // ================================================================ the relay

    /** What came on the phone's topic and opened with the key: the words and the relay's time. */
    static final class Raw {
        final String json, relayId;
        final long time;
        Raw(String relayId, long time, String json) { this.relayId = relayId; this.time = time; this.json = json; }
    }

    void send(String json) throws IOException {
        HttpURLConnection c = open(base + "/" + lock.toPc, "POST");
        try {
            byte[] b = lock.seal(json, PcCrypto.TO_PC).getBytes(StandardCharsets.UTF_8);
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

    /** What the laptop said since this unix second (only what opens with the key and the laptop's direction). */
    List<Raw> poll(long since) throws IOException {
        HttpURLConnection c = open(base + "/" + lock.toPhone + "/json?poll=1&since=" + Math.max(0, since), "GET");
        List<Raw> out = new ArrayList<>();
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
                    if (!"message".equals(PlantLink.str(l, "event"))) continue;
                    String m = PlantLink.str(l, "message");
                    if (m == null) continue;
                    String j = lock.open(m, PcCrypto.TO_PHONE);
                    if (j != null) out.add(new Raw(PlantLink.str(l, "id"), PlantLink.num(l, "time"), j));
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
        c.setRequestProperty("User-Agent", "Jarvis-phone");
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
}
