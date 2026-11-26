package com.anil.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * 🌱 Puts MicroPython and the plant board's program on an ESP32 straight from the phone (USB OTG, no laptop): the ESP32's
 * own ROM bootloader protocol (the way Espressif's esptool talks to it, without its stub) and MicroPython's raw REPL to write
 * the program files. Pure Java over a {@link Line}, so it is tested on a PC against a pretend ESP32.
 */
final class EspFlash {
    /** The USB serial line to the board (DTR / RTS work its EN and BOOT pins through the board's two transistors). */
    interface Line {
        void write(byte[] b) throws IOException;
        /** Up to buf.length bytes; 0 when nothing came within timeoutMs. */
        int read(byte[] buf, int timeoutMs) throws IOException;
        void dtr(boolean on) throws IOException;
        void rts(boolean on) throws IOException;
        void baud(int baud) throws IOException;
        void flushIn() throws IOException;
    }

    interface Progress { void at(String te, int pct); }

    /** A step that didn't work: {@code te} is what to tell him (Telugu, with what to try). */
    static final class Failed extends Exception {
        final String te;
        Failed(String te, String why) { super(why); this.te = te; }
    }

    static final int ESP32_MAGIC = 0x00F01D83, MAGIC_REG = 0x40001000;
    static final int ROM_BAUD = 115200, FAST_BAUD = 460800, OFFSET = 0x1000, FLASH_4MB = 4 << 20;
    private static final int BLOCK = 0x400;
    private static final int OP_SYNC = 0x08, OP_READ_REG = 0x0A, OP_SET_PARAMS = 0x0B, OP_ATTACH = 0x0D, OP_BAUD = 0x0F,
            OP_DEFL_BEGIN = 0x10, OP_DEFL_DATA = 0x11, OP_MD5 = 0x13;

    static final String TE_NO_BOOT = "బోర్డ్ ప్రోగ్రామ్ తీసుకునే స్థితిలోకి రాలేదు. బోర్డ్ మీద BOOT బటన్ నొక్కి పట్టుకుని, మళ్ళీ “ప్రోగ్రామ్ ఎక్కించు” నొక్కండి; "
            + "“ఎక్కిస్తున్నాను” అని వచ్చాక వదలండి. (కేబుల్ చార్జింగ్‌ది మాత్రమే అయితే కూడా ఇలా అవుతుంది: డేటా కేబుల్ వాడండి.)";
    static final String TE_NOT_ESP32 = "ఇది మామూలు ESP32 కాదు (ESP32-S2 / S3 / C3 లాంటిది). మన బోర్డ్‌కి “ESP32 30-pin (CP2102)” DevKit కావాలి.";
    static final String TE_LOST = "బోర్డ్ మధ్యలో జవాబు ఆపేసింది. కేబుల్ గట్టిగా పెట్టి, బోర్డ్‌కి వేరే ఏమీ (పంప్) కలపకుండా మళ్ళీ ప్రయత్నించండి.";
    static final String TE_MD5 = "రాసింది చెక్ చేస్తే సరిగా లేదు. కేబుల్ గట్టిగా పెట్టి మళ్ళీ ప్రయత్నించండి.";
    static final String TE_NO_REPL = "MicroPython మొదలవ్వలేదు. బోర్డ్ USB తీసి మళ్ళీ పెట్టి, “ప్రోగ్రామ్ ఎక్కించు” మళ్ళీ నొక్కండి.";
    static final String TE_NO_FW = "ఈ బోర్డ్‌లో MicroPython లేదు, ఈ Jarvis లో దాని ఫైల్ రాలేదు. Jarvis అప్‌డేట్ అయ్యాక మళ్ళీ ప్రయత్నించండి.";
    static final String TE_BAD_FW = "Jarvis లో ఉన్న MicroPython ఫైల్ సరిగా లేదు (ESP32 ది కాదు). ఏమీ రాయలేదు.";
    static final String TE_FILE = "ప్రోగ్రామ్ ఫైల్ బోర్డ్‌లో సరిగా రాలేదు. మళ్ళీ ప్రయత్నించండి.";
    static final String TE_BOARD = "బోర్డ్ ఒక తప్పు చెప్పింది. మళ్ళీ ప్రయత్నించండి; మళ్ళీ వస్తే “పూర్తిగా మళ్ళీ” తో ప్రయత్నించండి.";

    private final Line ln;
    private final Progress pr;

    EspFlash(Line ln, Progress pr) {
        this.ln = ln;
        this.pr = pr == null ? (t, p) -> {} : pr;
    }

    // ================================================================ the whole job

    /**
     * Finds MicroPython on the board (or puts it there), then writes the program files and restarts the board.
     *
     * @param fw     the MicroPython image for offset 0x1000 (null when this Jarvis has none)
     * @param fwVer  its version as 100 * major + minor (e.g. 129 for 1.29)
     * @param files  name → contents, written in this order
     * @param fresh  his "పూర్తిగా మళ్ళీ": put MicroPython on again even if it is there
     * @return "flashed" (MicroPython and the program) or "updated" (the program only)
     */
    String run(byte[] fw, int fwVer, Map<String, byte[]> files, boolean fresh) throws Exception {
        pr.at("బోర్డ్‌తో మాట్లాడుతున్నాను…", 1);
        int ver = 0;
        if (!fresh && findRepl(true, 5000, 8000)) {
            raw();
            try { ver = Integer.parseInt(exec("import sys\nv=sys.implementation.version\nprint(v[0]*100+v[1])", 4000).trim()); }
            catch (NumberFormatException e) { ver = 0; }
        }
        boolean flashIt = fresh || ver == 0 || (fw != null && ver < fwVer);
        if (flashIt) {
            if (fw == null) {
                if (ver >= 122) flashIt = false; // (an older MicroPython that still runs the program)
                else throw new Failed(TE_NO_FW, "no firmware");
            }
        }
        if (flashIt) {
            checkImage(fw);
            pr.at("ESP32 ని ప్రోగ్రామ్ తీసుకునే స్థితిలోకి…", 3);
            connect();
            chip();
            pr.at("చిప్: ESP32 ✓", 4);
            fast();
            attach();
            flash(fw);
            hardReset();
            pr.at("బోర్డ్ మొదటిసారి మొదలవుతోంది… (20 సెకన్ల వరకు)", 93);
            if (!findRepl(false, 20000, 30000)) throw new Failed(TE_NO_REPL, "no repl after flashing");
            raw();
        } else {
            pr.at("MicroPython ఉంది ✓ — ప్రోగ్రామ్ మాత్రమే మారుస్తాను", 10);
        }
        int total = 0, done = 0;
        for (byte[] b : files.values()) total += b.length;
        int from = flashIt ? 94 : 12;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            final int base = done;
            final int tot = Math.max(1, total);
            putFile(e.getKey(), e.getValue(), n -> pr.at("ప్రోగ్రామ్ రాస్తున్నాను… " + e.getKey(), from + (99 - from) * (base + n) / tot));
            done += e.getValue().length;
        }
        pr.at("బోర్డ్‌ని మళ్ళీ మొదలుపెడుతున్నాను…", 99);
        execNoWait("import machine\nmachine.reset()");
        sleep(400);
        return flashIt ? "flashed" : "updated";
    }

    /** The MicroPython file is an ESP32 image (magic 0xE9, chip id 0) of a sensible size. */
    static void checkImage(byte[] fw) throws Failed {
        if (fw == null || fw.length < 600_000 || fw.length > 3_500_000 || (fw[0] & 0xFF) != 0xE9 || fw[12] != 0 || fw[13] != 0)
            throw new Failed(TE_BAD_FW, "not an ESP32 image");
    }

    // ================================================================ the ROM bootloader

    /** Into the bootloader with the board's auto-reset (DTR = BOOT, RTS = EN), then SYNC. */
    void connect() throws Exception {
        ln.baud(ROM_BAUD);
        for (int a = 0; a < 6; a++) {
            ln.dtr(false);
            ln.rts(true);   // EN low: the chip stops
            sleep(100);
            ln.dtr(true);   // BOOT low
            ln.rts(false);  // EN high: it starts, sees BOOT low → bootloader
            sleep(a % 2 == 0 ? 60 : 550);
            ln.dtr(false);
            sleep(20);
            drop();
            if (sync()) return;
        }
        throw new Failed(TE_NO_BOOT, "no sync");
    }

    private boolean sync() throws IOException {
        byte[] d = new byte[36];
        d[0] = 7; d[1] = 7; d[2] = 0x12; d[3] = 0x20;
        for (int i = 4; i < 36; i++) d[i] = 0x55;
        for (int i = 0; i < 5; i++) {
            Resp r = command(OP_SYNC, d, 0, 120);
            if (r != null) {
                long end = System.currentTimeMillis() + 250;
                while (readPacket(end) != null) { /* (the ROM answers a SYNC eight times) */ }
                return true;
            }
        }
        return false;
    }

    int readReg(int addr) throws Exception {
        return check("read register", OP_READ_REG, le32s(addr), 0, 3000).val;
    }

    void chip() throws Exception {
        int m;
        try { m = readReg(MAGIC_REG); } catch (Failed f) { throw new Failed(TE_NOT_ESP32, "read magic: " + f.getMessage()); }
        if (m != ESP32_MAGIC) throw new Failed(TE_NOT_ESP32, String.format("chip magic 0x%08x", m));
    }

    /** 460800 baud if the chip and the cable keep up, else back to 115200. */
    boolean fast() throws Exception {
        try {
            check("change baud", OP_BAUD, le32s(FAST_BAUD, 0), 0, 3000);
        } catch (Failed f) {
            return false;
        }
        ln.baud(FAST_BAUD);
        sleep(60);
        drop();
        try {
            if (readReg(MAGIC_REG) == ESP32_MAGIC) return true;
        } catch (Failed ignored) {}
        ln.baud(ROM_BAUD);
        connect();
        return false;
    }

    void attach() throws Exception {
        check("attach flash", OP_ATTACH, new byte[8], 0, 3000);
        check("flash size", OP_SET_PARAMS, le32s(0, FLASH_4MB, 64 << 10, 4 << 10, 256, 0xFFFF), 0, 3000);
    }

    void flash(byte[] image) throws Exception {
        byte[] img = image.length % 4 == 0 ? image : Arrays.copyOf(image, (image.length + 3) & ~3);
        if (img.length != image.length) Arrays.fill(img, image.length, img.length, (byte) 0xFF);
        String md5 = hex(MessageDigest.getInstance("MD5").digest(img));
        byte[] z = zlib(img);
        int blocks = (z.length + BLOCK - 1) / BLOCK;
        int erase = ((img.length + BLOCK - 1) / BLOCK) * BLOCK;
        pr.at("పాత ప్రోగ్రామ్ చెరిపేస్తున్నాను… (30 సెకన్ల వరకు)", 5);
        check("erase", OP_DEFL_BEGIN, le32s(erase, blocks, BLOCK, OFFSET), 0, perMb(30, erase));
        for (int seq = 0; seq < blocks; seq++) {
            byte[] blk = Arrays.copyOfRange(z, seq * BLOCK, Math.min(z.length, (seq + 1) * BLOCK));
            byte[] d = new byte[16 + blk.length];
            System.arraycopy(le32s(blk.length, seq, 0, 0), 0, d, 0, 16);
            System.arraycopy(blk, 0, d, 16, blk.length);
            int chk = checksum(blk);
            Failed last = null;
            for (int t = 0; t < 3; t++) {
                try {
                    check("block " + seq, OP_DEFL_DATA, d, chk, 15000);
                    last = null;
                    break;
                } catch (Failed f) {
                    last = f;
                }
            }
            if (last != null) throw new Failed(TE_LOST, last.getMessage());
            pr.at("MicroPython ఎక్కిస్తున్నాను… " + (100 * (seq + 1) / blocks) + "%", 6 + 84 * (seq + 1) / blocks);
        }
        pr.at("రాసింది చెక్ చేస్తున్నాను…", 91);
        Resp r = check("md5", OP_MD5, le32s(OFFSET, img.length, 0, 0), 0, perMb(8, img.length));
        String got = r.data.length == 32 ? new String(r.data, StandardCharsets.US_ASCII) : r.data.length == 16 ? hex(r.data) : "?";
        if (!got.equalsIgnoreCase(md5)) throw new Failed(TE_MD5, "md5 " + got + " != " + md5);
    }

    /** EN low and high again with BOOT left high: the board starts its new program. */
    void hardReset() throws Exception {
        ln.dtr(false);
        ln.rts(true);
        sleep(120);
        ln.rts(false);
        ln.baud(ROM_BAUD);
        bl = bp = 0;
    }

    // ---- packets

    private static final class Resp { int val; byte[] data; }

    private Resp command(int op, byte[] data, int chk, int timeoutMs) throws IOException {
        byte[] p = new byte[8 + data.length];
        p[1] = (byte) op;
        p[2] = (byte) data.length;
        p[3] = (byte) (data.length >> 8);
        System.arraycopy(le32s(chk), 0, p, 4, 4);
        System.arraycopy(data, 0, p, 8, data.length);
        sendPacket(p);
        long end = System.currentTimeMillis() + timeoutMs;
        for (int i = 0; i < 100; i++) {
            byte[] r = readPacket(end);
            if (r == null) return null;
            if (r.length < 8 || r[0] != 1 || (r[1] & 0xFF) != op) continue;
            Resp x = new Resp();
            x.val = le32(r, 4);
            x.data = Arrays.copyOfRange(r, 8, r.length);
            return x;
        }
        return null;
    }

    /** The answer, with the status bytes checked and taken off (the ESP32 ROM sends 4 of them). */
    private Resp check(String what, int op, byte[] data, int chk, int timeoutMs) throws Exception {
        Resp r = command(op, data, chk, timeoutMs);
        if (r == null) throw new Failed(TE_LOST, what + ": no answer");
        int n = r.data.length;
        int sl = (n == 2 || n == 18 || n == 34) ? 2 : 4;
        if (n < sl) throw new Failed(TE_LOST, what + ": short answer");
        if (r.data[n - sl] != 0) throw new Failed(TE_LOST, what + String.format(": error 0x%02x", r.data[n - sl + 1] & 0xFF));
        r.data = Arrays.copyOf(r.data, n - sl);
        return r;
    }

    private void sendPacket(byte[] p) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(p.length + 16);
        o.write(0xC0);
        for (byte b : p) {
            int v = b & 0xFF;
            if (v == 0xC0) { o.write(0xDB); o.write(0xDC); }
            else if (v == 0xDB) { o.write(0xDB); o.write(0xDD); }
            else o.write(v);
        }
        o.write(0xC0);
        ln.write(o.toByteArray());
    }

    private byte[] readPacket(long deadline) throws IOException {
        int c;
        do {
            c = next(deadline);
            if (c < 0) return null;
        } while (c != 0xC0);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (true) {
            c = next(deadline);
            if (c < 0) return null;
            if (c == 0xC0) {
                if (o.size() == 0) continue; // (an end and a start back to back)
                return o.toByteArray();
            }
            if (c == 0xDB) {
                int d = next(deadline);
                if (d < 0) return null;
                o.write(d == 0xDC ? 0xC0 : d == 0xDD ? 0xDB : d);
            } else {
                o.write(c);
            }
        }
    }

    // ================================================================ MicroPython's REPL

    /** The line main.py prints just before its 2-second wait: a Ctrl-C now stops only main.py. */
    static final String MARK = "jarvis-plant: ctrl-c now";

    /**
     * Restarts the board (if asked) and finds MicroPython's prompt. For the first {@code quietMs} it only listens (a Ctrl-C
     * while MicroPython is still starting can stop it making its file store): the prompt itself, or main.py's line (then
     * Ctrl-C at once, in its 2-second wait). After that, Ctrl-C every 150 ms (a board running some other MicroPython program).
     */
    boolean findRepl(boolean reset, int quietMs, int ms) throws Exception {
        ln.baud(ROM_BAUD);
        if (reset) {
            ln.dtr(false);
            ln.rts(true);
            sleep(120);
            ln.rts(false);
        }
        bl = bp = 0;
        StringBuilder seen = new StringBuilder();
        long start = System.currentTimeMillis(), end = start + ms, nextC = 0;
        boolean marked = false;
        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            if ((marked || now - start >= quietMs) && now >= nextC) {
                ln.write(new byte[]{'\r', 3});
                nextC = now + (marked ? 100 : 150);
            }
            int c = next(now + 60);
            while (c >= 0) {
                seen.append((char) c);
                if (seen.length() > 4000) seen.delete(0, 3000);
                if (seen.indexOf(">>> ") >= 0 || seen.indexOf("raw REPL") >= 0) {
                    sleep(150);
                    drop();
                    return true;
                }
                if (!marked && seen.indexOf(MARK) >= 0) {
                    marked = true;
                    ln.write(new byte[]{'\r', 3});
                    nextC = System.currentTimeMillis() + 100;
                }
                c = next(System.currentTimeMillis() + 5);
            }
        }
        return false;
    }

    private static final byte[] RAW_BANNER = "raw REPL; CTRL-B to exit\r\n>".getBytes(StandardCharsets.US_ASCII);

    void raw() throws Exception {
        for (int t = 0; t < 2; t++) {
            ln.write(new byte[]{'\r', 3});
            sleep(100);
            ln.write(new byte[]{3});
            sleep(100);
            drop();
            ln.write(new byte[]{'\r', 1});
            if (until(RAW_BANNER, 3000)) return;
        }
        throw new Failed(TE_NO_REPL, "raw repl");
    }

    String exec(String code, int ms) throws Exception {
        send(code);
        if (!until(new byte[]{'O', 'K'}, 3000)) throw new Failed(TE_NO_REPL, "exec: no OK");
        byte[] out = readTo(4, ms);
        if (out == null) throw new Failed(TE_NO_REPL, "exec: no end");
        byte[] err = readTo(4, 3000);
        if (err == null) throw new Failed(TE_NO_REPL, "exec: no error end");
        until(new byte[]{'>'}, 2000);
        String e = new String(err, StandardCharsets.UTF_8).trim();
        if (!e.isEmpty()) throw new Failed(TE_BOARD, e);
        return new String(out, StandardCharsets.UTF_8);
    }

    private void execNoWait(String code) throws Exception {
        send(code);
    }

    private void send(String code) throws Exception {
        byte[] b = code.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i += 256) {
            ln.write(Arrays.copyOfRange(b, i, Math.min(b.length, i + 256)));
            sleep(10); // (MicroPython reads the REPL as it comes; small pieces keep it from overflowing)
        }
        ln.write(new byte[]{4});
    }

    interface Step { void at(int bytes); }

    /** Writes one file in base64 pieces and checks it by its SHA-256 read back on the board. */
    void putFile(String name, byte[] data, Step step) throws Exception {
        if (!name.matches("[a-z][a-z0-9_]{0,20}\\.(py|json)")) throw new IllegalArgumentException(name);
        exec("f=open('" + name + "','wb')\nw=f.write\nfrom binascii import a2b_base64 as a", 5000);
        for (int i = 0; i < data.length; i += 512) {
            byte[] part = Arrays.copyOfRange(data, i, Math.min(data.length, i + 512));
            exec("w(a('" + Base64.getEncoder().encodeToString(part) + "'))", 5000);
            if (step != null) step.at(i + part.length);
        }
        exec("f.close()", 5000);
        String h = exec("import hashlib,binascii\nh=hashlib.sha256()\nf=open('" + name + "','rb')\nwhile 1:\n b=f.read(512)\n if not b:break\n h.update(b)\n"
                + "f.close()\nprint(binascii.hexlify(h.digest()).decode())", 8000).trim();
        if (!h.equals(hex(MessageDigest.getInstance("SHA-256").digest(data)))) throw new Failed(TE_FILE, name + ": sha " + h);
    }

    // ================================================================ bytes in

    private final byte[] buf = new byte[4096];
    private int bl, bp;

    private int next(long deadline) throws IOException {
        while (bp >= bl) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return -1;
            int n = ln.read(buf, (int) Math.max(1, Math.min(left, 200)));
            if (n > 0) {
                bl = n;
                bp = 0;
            }
        }
        return buf[bp++] & 0xFF;
    }

    private void drop() throws IOException {
        bl = bp = 0;
        ln.flushIn();
    }

    /** Reads until the bytes just read end with {@code tail}. */
    private boolean until(byte[] tail, int ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        byte[] last = new byte[tail.length];
        int n = 0;
        while (true) {
            int c = next(end);
            if (c < 0) return false;
            System.arraycopy(last, 1, last, 0, last.length - 1);
            last[last.length - 1] = (byte) c;
            if (++n >= tail.length && Arrays.equals(last, tail)) return true;
        }
    }

    private byte[] readTo(int stop, int ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (true) {
            int c = next(end);
            if (c < 0) return null;
            if (c == stop) return o.toByteArray();
            o.write(c);
        }
    }

    // ================================================================ small helpers

    static int checksum(byte[] b) {
        int s = 0xEF;
        for (byte x : b) s ^= (x & 0xFF);
        return s;
    }

    static byte[] zlib(byte[] b) {
        Deflater d = new Deflater(9);
        d.setInput(b);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length / 2 + 64);
        byte[] t = new byte[65536];
        while (!d.finished()) o.write(t, 0, d.deflate(t));
        d.end();
        return o.toByteArray();
    }

    private static int perMb(int secPerMb, int size) {
        return (int) Math.max(3000, secPerMb * 1000L * size / 1_000_000L);
    }

    static byte[] le32s(int... v) {
        byte[] b = new byte[4 * v.length];
        for (int i = 0; i < v.length; i++) {
            b[4 * i] = (byte) v[i];
            b[4 * i + 1] = (byte) (v[i] >> 8);
            b[4 * i + 2] = (byte) (v[i] >> 16);
            b[4 * i + 3] = (byte) (v[i] >> 24);
        }
        return b;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
