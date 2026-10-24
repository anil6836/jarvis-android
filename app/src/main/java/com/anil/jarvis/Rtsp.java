package com.anil.jarvis;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pieces of a Wi-Fi camera's RTSP live picture that need no Android (desk-tested): the link and its login, the
 * camera's answers, the video track in its SDP, Basic / Digest login, RTP packets -> whole H.264 / H.265 NAL units, and
 * a decoded YUV picture -> a small NV21 one for the face finder. {@link RtspEyes} does the talking and the decoding.
 */
final class Rtsp {
    private Rtsp() {}

    // ---------------------------------------------------------------- the link

    /** rtsp://user:password@host:port/path?query, split; {@link #clean} is the link without the login (what is asked for). */
    static final class Link {
        String host = "", user, pass, clean = "";
        int port = 554;
    }

    static Link link(String url) {
        if (url == null) throw new IllegalArgumentException("లింక్ లేదు");
        String u = url.trim();
        if (!u.regionMatches(true, 0, "rtsp://", 0, 7)) throw new IllegalArgumentException("లింక్ rtsp://… తో మొదలవ్వాలి");
        String rest = u.substring(7);
        int slash = rest.indexOf('/');
        String auth = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        Link l = new Link();
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            String ui = auth.substring(0, at);
            auth = auth.substring(at + 1);
            int c = ui.indexOf(':');
            l.user = dec(c < 0 ? ui : ui.substring(0, c));
            l.pass = c < 0 ? "" : dec(ui.substring(c + 1));
        }
        String host = auth;
        int c = host.lastIndexOf(':');
        if (c >= 0 && host.indexOf(']') < c) {
            try { l.port = Integer.parseInt(host.substring(c + 1)); } catch (NumberFormatException e) { throw new IllegalArgumentException("లింక్‌లో port సరిగ్గా లేదు"); }
            host = host.substring(0, c);
        }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || l.port <= 0 || l.port > 65535) throw new IllegalArgumentException("లింక్ సరిగ్గా లేదు");
        l.host = host;
        l.clean = "rtsp://" + auth + path;
        return l;
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    // ---------------------------------------------------------------- the camera's answers

    /** One RTSP answer: its code, its header lines (lower-case names), its body. */
    static final class Answer {
        int code;
        String reason = "";
        final Map<String, String> head = new HashMap<>();
        String body = "";

        String get(String name) { return head.get(name.toLowerCase(Locale.ROOT)); }

        int length() {
            try { String v = get("content-length"); return v == null ? 0 : Math.max(0, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 0; }
        }
    }

    /** The status line and header lines of an answer (the body is added by the reader). Null when it isn't RTSP. */
    static Answer answer(List<String> lines) {
        if (lines.isEmpty()) return null;
        String first = lines.get(0).trim();
        if (!first.startsWith("RTSP/")) return null;
        String[] p = first.split(" ", 3);
        Answer a = new Answer();
        try { a.code = Integer.parseInt(p.length > 1 ? p[1].trim() : ""); } catch (NumberFormatException e) { return null; }
        a.reason = p.length > 2 ? p[2].trim() : "";
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim().toLowerCase(Locale.ROOT), v = l.substring(c + 1).trim();
            String had = a.head.get(k);
            // (a camera may offer Basic and Digest: Digest is the one kept)
            if (had == null || k.equals("www-authenticate") && v.regionMatches(true, 0, "Digest", 0, 6) && !had.regionMatches(true, 0, "Digest", 0, 6))
                a.head.put(k, v);
        }
        return a;
    }

    /** "12345678;timeout=60" -> "12345678". */
    static String session(String h) {
        if (h == null) return null;
        int s = h.indexOf(';');
        String id = (s < 0 ? h : h.substring(0, s)).trim();
        return id.isEmpty() ? null : id;
    }

    /** The session's timeout in seconds ("…;timeout=60"), 60 when not said. */
    static int timeout(String h) {
        if (h == null) return 60;
        int t = h.toLowerCase(Locale.ROOT).indexOf("timeout=");
        if (t < 0) return 60;
        int e = t + 8;
        while (e < h.length() && Character.isDigit(h.charAt(e))) e++;
        try { int v = Integer.parseInt(h.substring(t + 8, e)); return v >= 5 ? v : 60; } catch (NumberFormatException ex) { return 60; }
    }

    /** "RTP/AVP/TCP;unicast;interleaved=2-3" -> 2 (the picture's channel); -1 when it doesn't say. */
    static int interleaved(String transport) {
        String n = param(transport, "interleaved");
        if (n == null) return -1;
        try { return Integer.parseInt(n.split("-")[0].trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** "…;server_port=6970-6971" -> 6970; -1 when it doesn't say. */
    static int serverPort(String transport) {
        String n = param(transport, "server_port");
        if (n == null) return -1;
        try { return Integer.parseInt(n.split("-")[0].trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static String param(String transport, String name) {
        if (transport == null) return null;
        for (String part : transport.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, name + "=", 0, name.length() + 1)) return p.substring(name.length() + 1);
        }
        return null;
    }

    // ---------------------------------------------------------------- the SDP: which picture, which codec

    /** The camera's video track: H.264 or H.265, its RTP payload number, its control link, its parameter sets (may be none). */
    static final class Track {
        String codec;
        int pt = -1;
        String control, sessionControl;
        final List<byte[]> params = new ArrayList<>();

        boolean hevc() { return "H265".equals(codec); }
    }

    /** The first H.264 / H.265 video track in the SDP; null when there is none (a camera that sends only MJPEG / other). */
    static Track video(String sdp) {
        if (sdp == null) return null;
        String sessionControl = null;
        boolean seenMedia = false, video = false;
        List<Integer> pts = new ArrayList<>();
        Map<Integer, String> codecs = new HashMap<>(), fmtps = new HashMap<>();
        String control = null;
        String[] lines = sdp.split("\r?\n");
        for (int i = 0; i <= lines.length; i++) {
            String l = i < lines.length ? lines[i].trim() : "m=end";
            if (l.startsWith("m=")) {
                if (video) { // the section that ends here
                    for (int pt : pts) {
                        String c = codecs.get(pt);
                        if (c == null) continue;
                        Track t = new Track();
                        t.codec = c;
                        t.pt = pt;
                        t.control = control;
                        t.sessionControl = sessionControl;
                        params(t, fmtps.get(pt));
                        return t;
                    }
                }
                seenMedia = true;
                video = l.startsWith("m=video");
                pts.clear();
                codecs.clear();
                fmtps.clear();
                control = null;
                if (video) {
                    String[] p = l.split("\\s+");
                    for (int k = 3; k < p.length; k++) try { pts.add(Integer.parseInt(p[k])); } catch (NumberFormatException ignored) {}
                }
                continue;
            }
            if (l.startsWith("a=control:")) {
                if (!seenMedia) sessionControl = l.substring(10).trim();
                else if (video) control = l.substring(10).trim();
                continue;
            }
            if (!video) continue;
            if (l.startsWith("a=rtpmap:")) {
                String v = l.substring(9).trim();
                int sp = v.indexOf(' ');
                if (sp <= 0) continue;
                try {
                    int pt = Integer.parseInt(v.substring(0, sp).trim());
                    String name = v.substring(sp + 1).trim().split("/")[0].toUpperCase(Locale.ROOT);
                    if (name.equals("H264")) codecs.put(pt, "H264");
                    else if (name.equals("H265") || name.equals("HEVC")) codecs.put(pt, "H265");
                } catch (NumberFormatException ignored) {}
            } else if (l.startsWith("a=fmtp:")) {
                String v = l.substring(7).trim();
                int sp = v.indexOf(' ');
                if (sp <= 0) continue;
                try { fmtps.put(Integer.parseInt(v.substring(0, sp).trim()), v.substring(sp + 1).trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /** sprop-parameter-sets (H.264: SPS, PPS) or sprop-vps / -sps / -pps (H.265), as NAL units without start codes. */
    private static void params(Track t, String fmtp) {
        if (fmtp == null) return;
        Map<String, String> m = new HashMap<>();
        for (String part : fmtp.split(";")) {
            String p = part.trim();
            int e = p.indexOf('=');
            if (e > 0) m.put(p.substring(0, e).trim().toLowerCase(Locale.ROOT), p.substring(e + 1).trim());
        }
        List<String> sets = new ArrayList<>();
        if (t.hevc()) {
            for (String k : new String[]{"sprop-vps", "sprop-sps", "sprop-pps"}) if (m.containsKey(k)) for (String s : m.get(k).split(",")) sets.add(s);
        } else if (m.containsKey("sprop-parameter-sets")) {
            for (String s : m.get("sprop-parameter-sets").split(",")) sets.add(s);
        }
        for (String s : sets) {
            try {
                byte[] b = Base64.getDecoder().decode(s.trim());
                if (b.length > 0) t.params.add(b);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Where to SETUP the track: its own absolute link, or the base (Content-Base / the asked link) with its name added. */
    static String trackUrl(String base, String control) {
        if (control == null || control.isEmpty() || control.equals("*")) return base;
        if (control.regionMatches(true, 0, "rtsp://", 0, 7)) return control;
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    // ---------------------------------------------------------------- the login (Basic / Digest)

    /**
     * The Authorization line's value for the camera's challenge; null when it asks for something else. cnonce: random;
     * nc: how many times this nonce has been used (1, 2, …).
     */
    static String authorize(String challenge, String user, String pass, String method, String uri, String cnonce, int nc) {
        if (challenge == null || user == null) return null;
        String c = challenge.trim();
        if (pass == null) pass = "";
        if (c.regionMatches(true, 0, "Basic", 0, 5))
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        if (!c.regionMatches(true, 0, "Digest", 0, 6)) return null;
        Map<String, String> p = authParams(c.substring(6));
        String realm = p.get("realm"), nonce = p.get("nonce");
        if (realm == null || nonce == null) return null;
        String alg = p.get("algorithm");
        if (alg != null && !alg.equalsIgnoreCase("MD5")) return null;
        String ha1 = md5(user + ":" + realm + ":" + pass), ha2 = md5(method + ":" + uri);
        String qop = p.get("qop");
        boolean auth = false;
        if (qop != null) for (String q : qop.split(",")) if (q.trim().equalsIgnoreCase("auth")) auth = true;
        StringBuilder b = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", response=\"");
        if (auth) {
            String n = String.format(Locale.ROOT, "%08x", Math.max(1, nc));
            b.append(md5(ha1 + ":" + nonce + ":" + n + ":" + cnonce + ":auth:" + ha2)).append("\", qop=auth, nc=").append(n)
                    .append(", cnonce=\"").append(cnonce).append('"');
        } else {
            b.append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (p.containsKey("opaque")) b.append(", opaque=\"").append(p.get("opaque")).append('"');
        if (alg != null) b.append(", algorithm=").append(alg);
        return b.toString();
    }

    /** realm="a b", nonce="x", stale=FALSE -> a map (lower-case names). */
    static Map<String, String> authParams(String s) {
        Map<String, String> m = new HashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
            int e = s.indexOf('=', i);
            if (e < 0) break;
            String k = s.substring(i, e).trim().toLowerCase(Locale.ROOT);
            i = e + 1;
            String v;
            if (i < n && s.charAt(i) == '"') {
                int q = s.indexOf('"', i + 1);
                if (q < 0) q = n;
                v = s.substring(i + 1, q);
                i = q + 1;
            } else {
                int q = s.indexOf(',', i);
                if (q < 0) q = n;
                v = s.substring(i, q).trim();
                i = q;
            }
            m.put(k, v);
        }
        return m;
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- RTP

    /** The RTP payload's [start, end) inside b[off, off + len); null when it isn't an RTP v2 packet. */
    static int[] payload(byte[] b, int off, int len) {
        if (len < 12 || (b[off] & 0xC0) != 0x80) return null;
        int cc = b[off] & 0x0F;
        boolean ext = (b[off] & 0x10) != 0, pad = (b[off] & 0x20) != 0;
        int p = off + 12 + 4 * cc, end = off + len;
        if (ext) {
            if (p + 4 > end) return null;
            p += 4 + 4 * u16(b, p + 2);
        }
        if (pad) {
            int k = b[end - 1] & 0xFF;
            if (k == 0) return null;
            end -= k;
        }
        if (p > end) return null;
        return new int[]{p, end};
    }

    static int pt(byte[] b, int off) { return b[off + 1] & 0x7F; }

    static boolean marker(byte[] b, int off) { return (b[off + 1] & 0x80) != 0; }

    static int seq(byte[] b, int off) { return u16(b, off + 2); }

    static long stamp(byte[] b, int off) {
        return ((long) (b[off + 4] & 0xFF) << 24) | ((b[off + 5] & 0xFF) << 16) | ((b[off + 6] & 0xFF) << 8) | (b[off + 7] & 0xFF);
    }

    static int u16(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }

    /**
     * RTP payloads -> whole NAL units (H.264: RFC 6184 single / STAP-A / FU-A; H.265: RFC 7798 single / AP / FU). A lost
     * packet in the middle of a split NAL unit drops that unit (the decoder then waits for the next key picture).
     */
    static final class Nals {
        final boolean hevc;
        private ByteArrayOutputStream fu;
        private int lastSeq = -1;
        /** A packet was lost since the last key picture was handed on: true until the decoder is told. */
        boolean lost;

        Nals(boolean hevc) { this.hevc = hevc; }

        void add(byte[] b, int s, int e, int seq, List<byte[]> out) {
            if (lastSeq >= 0 && ((lastSeq + 1) & 0xFFFF) != seq) { fu = null; lost = true; }
            lastSeq = seq;
            if (!hevc) {
                if (e - s < 1) return;
                int type = b[s] & 0x1F;
                if (type >= 1 && type <= 23) out.add(copy(b, s, e));
                else if (type == 24) aggregate(b, s + 1, e, out);
                else if (type == 28) {
                    if (e - s < 2) return;
                    int fh = b[s + 1] & 0xFF;
                    if ((fh & 0x80) != 0) { fu = new ByteArrayOutputStream(); fu.write((b[s] & 0xE0) | (fh & 0x1F)); }
                    if (fu == null) return; // (its start was lost)
                    fu.write(b, s + 2, e - s - 2);
                    if ((fh & 0x40) != 0) { out.add(fu.toByteArray()); fu = null; }
                }
            } else {
                if (e - s < 2) return;
                int type = (b[s] >> 1) & 0x3F;
                if (type == 48) aggregate(b, s + 2, e, out);
                else if (type == 49) {
                    if (e - s < 3) return;
                    int fh = b[s + 2] & 0xFF;
                    if ((fh & 0x80) != 0) { fu = new ByteArrayOutputStream(); fu.write((b[s] & 0x81) | ((fh & 0x3F) << 1)); fu.write(b[s + 1]); }
                    if (fu == null) return;
                    fu.write(b, s + 3, e - s - 3);
                    if ((fh & 0x40) != 0) { out.add(fu.toByteArray()); fu = null; }
                } else if (type < 48) out.add(copy(b, s, e));
            }
        }

        private static void aggregate(byte[] b, int p, int e, List<byte[]> out) {
            while (p + 2 <= e) {
                int n = u16(b, p);
                p += 2;
                if (n == 0 || p + n > e) return;
                out.add(copy(b, p, p + n));
                p += n;
            }
        }
    }

    static byte[] copy(byte[] b, int s, int e) {
        byte[] r = new byte[e - s];
        System.arraycopy(b, s, r, 0, r.length);
        return r;
    }

    static int type(boolean hevc, byte[] nal) { return nal.length == 0 ? -1 : hevc ? (nal[0] >> 1) & 0x3F : nal[0] & 0x1F; }

    /** A key picture (H.264 IDR; H.265 IRAP 16–21): decoding can start here. */
    static boolean key(boolean hevc, byte[] nal) {
        int t = type(hevc, nal);
        return hevc ? t >= 16 && t <= 21 : t == 5;
    }

    /** Which parameter set it is: 0 VPS, 1 SPS, 2 PPS (H.264 has no VPS); -1 when it isn't one. */
    static int paramKind(boolean hevc, byte[] nal) {
        int t = type(hevc, nal);
        if (hevc) return t == 32 ? 0 : t == 33 ? 1 : t == 34 ? 2 : -1;
        return t == 7 ? 1 : t == 8 ? 2 : -1;
    }

    // ---------------------------------------------------------------- the decoded picture -> a small NV21

    /**
     * A YUV 4:2:0 picture (any strides, its visible part from x0, y0, w x h) -> NV21 taking every step-th pixel. wh gets
     * the small picture's width and height (even numbers).
     */
    static byte[] nv21(ByteBuffer y, int yRow, int yPix, ByteBuffer u, ByteBuffer v, int cRow, int cPix,
                       int x0, int y0, int w, int h, int step, int[] wh) {
        int ow = (w / step) & ~1, oh = (h / step) & ~1;
        if (ow <= 0 || oh <= 0) return null;
        byte[] out = new byte[ow * oh * 3 / 2];
        int o = 0;
        for (int r = 0; r < oh; r++) {
            int base = (y0 + r * step) * yRow + x0 * yPix;
            for (int c = 0; c < ow; c++) out[o++] = y.get(base + c * step * yPix);
        }
        int cx0 = x0 / 2, cy0 = y0 / 2;
        for (int r = 0; r < oh / 2; r++) {
            int base = (cy0 + r * step) * cRow + cx0 * cPix;
            for (int c = 0; c < ow / 2; c++) {
                int at = base + c * step * cPix;
                out[o++] = v.get(at);
                out[o++] = u.get(at);
            }
        }
        wh[0] = ow;
        wh[1] = oh;
        return out;
    }

    /** How many pixels to step so the small picture is at most `max` wide (1, 2, 3, …). */
    static int step(int w, int max) {
        int s = 1;
        while (w / s > max) s++;
        return s;
    }
}
