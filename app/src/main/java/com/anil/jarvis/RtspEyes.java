package com.anil.jarvis;

import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 👁️ A Wi-Fi camera's RTSP live picture for the car eye check: asks the camera for its video (over the RTSP connection
 * itself; over UDP when the camera allows only that), puts the H.264 / H.265 pieces together, decodes them on the
 * device (MediaCodec) and hands a small NV21 picture to {@link CarEyes} only when its face finder is free. Nothing is
 * saved or sent anywhere: the picture goes from the camera to the face finder and is let go. A picture with a lost piece
 * is never decoded (a half picture could look like closed eyes): it waits for the next whole key picture.
 * The camera's network is the one given (its own Wi-Fi, often without internet); null: the Wi-Fi this device is on.
 */
final class RtspEyes {
    interface Sink {
        /** The face finder is free for a new picture now. */
        boolean wants();

        /** A small picture (NV21, w x h) for the face finder. */
        void nv21(byte[] nv, int w, int h);

        void status(String s);
    }

    private static final int MAX_W = 1280; // (a wide car camera's face stays big enough for the eyes)
    /** Links whose camera answered a TCP ask with UDP: asked over UDP straight away next time. */
    private static final java.util.Set<String> UDP_ONLY = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final Rtsp.Link link;
    private final android.net.Network net;
    private final Sink sink;
    private volatile boolean alive = true, closed;
    private volatile Socket sock;
    private volatile DatagramSocket udp;
    private InputStream in;
    private volatile OutputStream out;
    private int cseq = 1, nc;
    private volatile String session, playUrl;
    private String challenge;
    private MediaCodec codec;
    private boolean hevc, waitKey = true;
    private final byte[][] params = new byte[3][]; // VPS (H.265 only), SPS, PPS
    private long pts;
    private int nullImages;

    /** user / pass: the camera's login from Settings, used when the link carries none. */
    RtspEyes(String url, String user, String pass, android.net.Network net, Sink sink) {
        this.link = Rtsp.link(url);
        if (link.user == null && user != null && !user.isEmpty()) { link.user = user; link.pass = pass == null ? "" : pass; }
        this.net = net;
        this.sink = sink;
    }

    /** Ends the session: TEARDOWN said to the camera (a one-viewer dashcam frees itself at once), then the sockets closed. */
    void stop() {
        alive = false;
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        Thread bye = new Thread(() -> { // (never on the caller's thread: it may be the main one)
            try {
                String s = session, u = playUrl;
                if (s != null && u != null && out != null) send("TEARDOWN", u, null);
            } catch (Exception ignored) {
            } finally {
                try { Socket so = sock; if (so != null) so.close(); } catch (Exception ignored) {}
                try { DatagramSocket d = udp; if (d != null) d.close(); } catch (Exception ignored) {}
            }
        }, "jarvis-rtsp-bye");
        bye.setDaemon(true);
        bye.start();
    }

    /** One session with the camera, until it stops sending or {@link #stop}. Throws why it ended. */
    void run() throws Exception {
        try {
            open();
        } finally {
            MediaCodec c = codec;
            codec = null;
            if (c != null) {
                try { c.stop(); } catch (Exception ignored) {}
                try { c.release(); } catch (Exception ignored) {} // (on its own: a failed stop must not keep the decoder)
            }
            stop();
        }
    }

    private void open() throws Exception {
        Socket s = net != null ? net.getSocketFactory().createSocket() : new Socket();
        sock = s;
        if (closed) throw new IOException("ఆగింది");
        InetAddress addr = net != null ? net.getByName(link.host) : InetAddress.getByName(link.host); // (a name: asked on the camera's network)
        s.connect(new InetSocketAddress(addr, link.port), 4000);
        s.setSoTimeout(8000);
        s.setTcpNoDelay(true);
        in = new BufferedInputStream(s.getInputStream(), 65536);
        out = s.getOutputStream();
        sink.status("📶 RTSP కెమెరాతో మాట్లాడుతున్నాను…");

        Rtsp.Answer d = ask("DESCRIBE", link.clean, "Accept: application/sdp\r\n");
        if (d.code != 200) throw refused("DESCRIBE", d);
        Rtsp.Track t = Rtsp.video(d.body);
        if (t == null) throw new IllegalArgumentException("ఈ కెమెరా బొమ్మ H.264 / H.265 లో లేదు: దాని MJPEG (http://…) లింక్ ఉంటే అది పెట్టండి");
        hevc = t.hevc();
        depack = new Rtsp.Nals(hevc);
        for (byte[] p : t.params) keepParam(p);
        String base = d.get("content-base");
        if (base == null) base = d.get("content-location");
        if (base == null || !base.regionMatches(true, 0, "rtsp://", 0, 7)) base = link.clean;
        String track = Rtsp.trackUrl(base, t.control);
        String play = t.sessionControl != null && !t.sessionControl.equals("*") ? Rtsp.trackUrl(base, t.sessionControl) : base;

        int channel = 0;
        DatagramSocket ds = null;
        Rtsp.Answer su = null;
        if (!UDP_ONLY.contains(link.clean)) {
            su = ask("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            String tr = su.get("transport");
            if (su.code == 200 && tr != null && !tr.toUpperCase(Locale.ROOT).contains("/TCP") && Rtsp.serverPort(tr) > 0) {
                // (asked for TCP, it set up UDP without saying no: that session is let go, UDP asked properly — and next time at once)
                UDP_ONLY.add(link.clean);
                session = Rtsp.session(su.get("session"));
                try { if (session != null) { send("TEARDOWN", play, null); read(-1, -1); } } catch (SocketTimeoutException ignored) {}
                session = null;
                su = null;
            } else if (su.code == 461 || su.code == 400 || su.code == 501) { // (this camera sends only over UDP)
                UDP_ONLY.add(link.clean);
                su = null;
            } else if (su.code != 200) throw refused("SETUP", su);
            else { int ch = Rtsp.interleaved(tr); if (ch >= 0) channel = ch; }
        }
        if (su == null) {
            ds = udpPair();
            udp = ds; // (closed by stop() whatever happens next)
            if (closed) throw new IOException("ఆగింది");
            su = ask("SETUP", track, "Transport: RTP/AVP;unicast;client_port=" + ds.getLocalPort() + "-" + (ds.getLocalPort() + 1) + "\r\n");
            if (su.code != 200) throw refused("SETUP", su);
        }
        session = Rtsp.session(su.get("session"));
        int timeout = Rtsp.timeout(su.get("session"));
        playUrl = play;
        Rtsp.Answer pl = ask("PLAY", play, "Range: npt=0.000-\r\n");
        if (pl.code != 200) throw refused("PLAY", pl);
        sink.status("👁️ చూస్తున్నాను: మీ ముఖం కోసం…");
        long keepEvery = Math.max(10, timeout / 2 - 5) * 1000L;
        if (ds != null) udpLoop(ds, t.pt, keepEvery);
        else tcpLoop(channel, t.pt, keepEvery);
    }

    private static IOException refused(String step, Rtsp.Answer a) {
        if (a.code == 401) return new IOException("కెమెరా user / password ఒప్పుకోలేదు (సెట్టింగ్స్‌లో సరిచూడండి)");
        if (a.code == 404) return new IOException("కెమెరాలో ఈ లింక్ లేదు (RTSP 404): దాని యాప్‌లో సరైన లింక్ చూడండి");
        return new IOException("కెమెరా జవాబు (" + step + "): RTSP " + a.code + " " + a.reason);
    }

    // ---------------------------------------------------------------- asking

    /** One ask; a 401 (first login, or the camera's nonce went stale) is answered once with the login. */
    private Rtsp.Answer ask(String method, String uri, String extra) throws IOException {
        send(method, uri, extra);
        Rtsp.Answer a = read(-1, -1);
        if (a.code == 401) {
            String ch = a.get("www-authenticate");
            if (link.user == null) throw new IllegalArgumentException("కెమెరా user / password అడుగుతోంది: సెట్టింగ్స్‌లో కెమెరా user, password పెట్టండి");
            if (Rtsp.authorize(ch, link.user, link.pass, method, uri, "x", 1) == null) throw new IOException("కెమెరా అడిగే login రకం Jarvis కి తెలియదు");
            synchronized (this) { challenge = ch; nc = 0; }
            send(method, uri, extra);
            a = read(-1, -1);
        }
        return a;
    }

    private void send(String method, String uri, String extra) throws IOException {
        synchronized (this) { // (the TEARDOWN may come from another thread)
            StringBuilder b = new StringBuilder(method).append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(cseq++)
                    .append("\r\nUser-Agent: Jarvis\r\n");
            if (session != null) b.append("Session: ").append(session).append("\r\n");
            if (challenge != null && link.user != null) {
                String a = Rtsp.authorize(challenge, link.user, link.pass, method, uri, cnonce(), ++nc);
                if (a != null) b.append("Authorization: ").append(a).append("\r\n");
            }
            if (extra != null) b.append(extra);
            b.append("\r\n");
            OutputStream o = out;
            if (o == null) throw new IOException("ఆగింది");
            o.write(b.toString().getBytes(StandardCharsets.UTF_8));
            o.flush();
        }
    }

    private static String cnonce() {
        byte[] r = new byte[8];
        new SecureRandom().nextBytes(r);
        StringBuilder b = new StringBuilder();
        for (byte x : r) b.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return b.toString();
    }

    /**
     * The next answer on the connection. Picture pieces met on the way ('$' frames) go to the picture when channel >= 0,
     * else are passed over; returns null after one picture piece when channel >= 0 (the caller's loop goes on).
     */
    private Rtsp.Answer read(int channel, int pt) throws IOException {
        while (alive) {
            in.mark(1);
            int c = in.read();
            if (c < 0) throw new EOFException("కెమెరా కనెక్షన్ మూసేసింది");
            if (c == '$') {
                int ch = in.read();
                int hi = in.read(), lo = in.read();
                if (ch < 0 || lo < 0) throw new EOFException("కెమెరా కనెక్షన్ మూసేసింది");
                int n = (hi << 8) | lo;
                byte[] b = new byte[n];
                full(b, n);
                if (channel >= 0) {
                    if (ch == channel) rtp(b, n, pt);
                    return null;
                }
                continue;
            }
            in.reset();
            List<String> lines = new ArrayList<>();
            String l;
            while (!(l = line()).isEmpty()) { lines.add(l); if (lines.size() > 100) break; }
            if (lines.isEmpty()) continue; // (a stray empty line)
            Rtsp.Answer a = Rtsp.answer(lines);
            if (a == null) throw new IOException("కెమెరా జవాబు అర్థం కాలేదు: " + lines.get(0));
            int len = a.length();
            if (len > 1 << 20) throw new IOException("కెమెరా జవాబు చాలా పెద్దది");
            if (len > 0) {
                byte[] body = new byte[len];
                full(body, len);
                a.body = new String(body, StandardCharsets.UTF_8);
            }
            return a;
        }
        throw new IOException("ఆగింది");
    }

    private void full(byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new EOFException("కెమెరా కనెక్షన్ మూసేసింది");
            got += r;
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') { if (c != '\r') b.write(c); if (b.size() > 8192) break; }
        if (c < 0 && b.size() == 0) throw new EOFException("కెమెరా కనెక్షన్ మూసేసింది");
        return b.toString("UTF-8");
    }

    // ---------------------------------------------------------------- the picture's way in

    private void tcpLoop(int channel, int pt, long keepEvery) throws IOException {
        long keptAt = SystemClock.elapsedRealtime(), lastPic = keptAt;
        int had = 0;
        while (alive) {
            read(channel, pt);
            long now = SystemClock.elapsedRealtime();
            if (pics != had) { had = pics; lastPic = now; }
            else if (now - lastPic > 10_000) throw new IOException(noPicture());
            if (now - keptAt > keepEvery) { keptAt = now; send("OPTIONS", link.clean, null); } // (its answer is passed over in read)
        }
    }

    private void udpLoop(DatagramSocket ds, int pt, long keepEvery) throws IOException {
        // (the RTSP connection now carries only answers to the keep-alives: read and let go on their own thread, so a camera
        // that is slow to answer never holds up the picture)
        Thread answers = new Thread(() -> {
            byte[] junk = new byte[4096];
            try {
                Socket so = sock;
                if (so != null) so.setSoTimeout(0);
                while (alive && in.read(junk) >= 0) { /* passed over */ }
            } catch (Exception ignored) {}
        }, "jarvis-rtsp-answers");
        answers.setDaemon(true);
        answers.start();
        ds.setSoTimeout(1000);
        byte[] b = new byte[65536];
        DatagramPacket p = new DatagramPacket(b, b.length);
        long keptAt = SystemClock.elapsedRealtime(), lastPic = keptAt;
        int had = 0;
        while (alive) {
            try {
                p.setLength(b.length);
                ds.receive(p);
                rtp(b, p.getLength(), pt);
            } catch (SocketTimeoutException ignored) {}
            long now = SystemClock.elapsedRealtime();
            if (pics != had) { had = pics; lastPic = now; }
            else if (now - lastPic > 10_000) throw new IOException(noPicture() + " (UDP)");
            if (now - keptAt > keepEvery) { keptAt = now; send("OPTIONS", link.clean, null); }
        }
    }

    private String noPicture() {
        return codec == null && (params[1] == null || params[2] == null) ? "కెమెరా బొమ్మ వివరాలు (SPS / PPS) పంపలేదు" : "కెమెరా నుంచి బొమ్మ రావడం లేదు";
    }

    /** Two UDP sockets on an even and the next port (the picture, its RTCP), on the camera's network. */
    private DatagramSocket udpPair() throws IOException {
        for (int i = 0; i < 20; i++) {
            DatagramSocket a = null;
            try {
                a = new DatagramSocket(null);
                if (net != null) net.bindSocket(a);
                a.bind(new InetSocketAddress(0));
                int port = a.getLocalPort();
                if (port % 2 != 0 || port > 65534) { a.close(); continue; }
                DatagramSocket rtcp = new DatagramSocket(null);
                if (net != null) net.bindSocket(rtcp);
                try { rtcp.bind(new InetSocketAddress(port + 1)); } catch (IOException e) { rtcp.close(); a.close(); continue; }
                rtcp.close(); // (Jarvis needs no RTCP; the port was only checked free)
                return a;
            } catch (IOException e) {
                if (a != null) a.close();
            }
        }
        throw new IOException("UDP port దొరకలేదు");
    }

    private Rtsp.Nals depack;
    private final List<byte[]> got = new ArrayList<>(), unit = new ArrayList<>();
    private long unitStamp = -1, badStamp;
    private boolean bad;
    private int lastSeq = -1, pics;

    /** One RTP packet of the picture. */
    private void rtp(byte[] b, int n, int pt) throws IOException {
        int[] pl = Rtsp.payload(b, 0, n);
        if (pl == null || Rtsp.pt(b, 0) != pt) return;
        long stamp = Rtsp.stamp(b, 0);
        int seq = Rtsp.seq(b, 0);
        if (lastSeq >= 0 && ((lastSeq + 1) & 0xFFFF) != seq) {
            // a lost piece: it was the end of the picture being put together or a part of this one — both are let go, and
            // nothing is decoded again until a whole key picture
            unit.clear();
            bad = true;
            badStamp = stamp;
            waitKey = true;
        }
        lastSeq = seq;
        if (unitStamp >= 0 && stamp != unitStamp && !unit.isEmpty()) unit(); // (the last piece's mark was lost: a new picture)
        unitStamp = stamp;
        got.clear();
        depack.add(b, pl[0], pl[1], seq, got);
        depack.lost = false; // (seen here already)
        if (bad && stamp == badStamp) { // (the picture with a hole: never decoded)
            if (Rtsp.marker(b, 0)) { bad = false; unit.clear(); }
            return;
        }
        bad = false;
        unit.addAll(got);
        if (Rtsp.marker(b, 0)) unit();
    }

    /** A whole picture's NAL units: kept parameter sets, then to the decoder (from a key picture on). */
    private void unit() throws IOException {
        List<byte[]> u = new ArrayList<>(unit);
        unit.clear();
        boolean key = false;
        for (byte[] nal : u) {
            keepParam(nal);
            if (Rtsp.key(hevc, nal)) key = true;
        }
        if (codec == null && !startCodec()) return; // (its parameter sets haven't come yet)
        if (waitKey && !key) return;
        waitKey = false;
        feed(u, key);
    }

    private void keepParam(byte[] nal) {
        int k = Rtsp.paramKind(hevc, nal);
        if (k >= 0) params[k] = nal;
    }

    private boolean startCodec() throws IOException {
        if (params[1] == null || params[2] == null || hevc && params[0] == null) return false;
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, 1920, 1080);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20); // (a 1440p / 4K camera's key picture fits)
        if (hevc) f.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(params[0], params[1], params[2])));
        else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(params[1])));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(annexB(params[2])));
        }
        MediaCodec c = null;
        try {
            c = MediaCodec.createDecoderByType(mime);
            c.configure(f, null, null, 0);
            c.start();
            codec = c;
        } catch (Exception e) {
            try { if (c != null) c.release(); } catch (Exception ignored) {}
            if (!hasDecoder(mime)) throw new IllegalArgumentException("ఈ పరికరానికి " + (hevc ? "H.265" : "H.264") + " బొమ్మ డీకోడర్ లేదు");
            throw new IOException("డీకోడర్ మొదలవ్వలేదు (" + e.getClass().getSimpleName() + "): మళ్ళీ ప్రయత్నిస్తాను"); // (e.g. another app holds it now)
        }
        return true;
    }

    private static boolean hasDecoder(String mime) {
        try {
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder()) continue;
                for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // (can't tell: treated as a passing trouble, tried again)
        }
    }

    private static byte[] annexB(byte[]... nals) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] n : nals) { b.write(0); b.write(0); b.write(0); b.write(1); b.write(n, 0, n.length); }
        return b.toByteArray();
    }

    private void feed(List<byte[]> u, boolean key) throws IOException {
        int size = 0;
        for (byte[] n : u) size += 4 + n.length;
        if (size == 0) return;
        try {
            int ix = codec.dequeueInputBuffer(20_000);
            if (ix < 0) { drain(); ix = codec.dequeueInputBuffer(50_000); }
            if (ix < 0) { waitKey = true; return; } // (the decoder is behind: this picture is dropped, the next key one restarts)
            ByteBuffer ib = codec.getInputBuffer(ix);
            if (ib == null || ib.capacity() < size) { codec.queueInputBuffer(ix, 0, 0, pts, 0); waitKey = true; return; }
            ib.clear();
            for (byte[] n : u) { ib.put((byte) 0); ib.put((byte) 0); ib.put((byte) 0); ib.put((byte) 1); ib.put(n); }
            pts += 33_333;
            codec.queueInputBuffer(ix, 0, size, pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            drain();
        } catch (IllegalStateException e) { // (MediaCodec.CodecException too: Android took the decoder back, …) -> a new session
            throw new IOException("బొమ్మ డీకోడ్ ఆగిపోయింది (" + e.getClass().getSimpleName() + "): మళ్ళీ మొదలుపెడతాను");
        }
    }

    private MediaCodec.BufferInfo info;

    private void drain() {
        if (info == null) info = new MediaCodec.BufferInfo();
        while (true) {
            int ox = codec.dequeueOutputBuffer(info, 0);
            if (ox == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (ox < 0) continue; // (its format / buffers changed: the next picture says its own size)
            pics++;
            try {
                if (sink.wants()) {
                    Image img = codec.getOutputImage(ox);
                    if (img == null) { if (++nullImages == 30) sink.status("ఈ పరికరం డీకోడ్ చేసిన బొమ్మని ఇవ్వడం లేదు"); }
                    else try { picture(img); } finally { img.close(); }
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception ignored) {
            } finally {
                codec.releaseOutputBuffer(ox, false);
            }
        }
    }

    private final int[] wh = new int[2];

    /** The decoded picture -> a small NV21 (at most MAX_W wide), to the face finder. */
    private void picture(Image img) {
        Image.Plane[] p = img.getPlanes();
        if (p.length < 3) return;
        Rect crop = img.getCropRect();
        int x0 = crop.left & ~1, y0 = crop.top & ~1, w = crop.width(), h = crop.height();
        byte[] nv = Rtsp.nv21(p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(), p[1].getBuffer(), p[2].getBuffer(),
                p[1].getRowStride(), p[1].getPixelStride(), x0, y0, w, h, Rtsp.step(w, MAX_W), wh);
        if (nv != null) sink.nv21(nv, wh[0], wh[1]);
    }
}
