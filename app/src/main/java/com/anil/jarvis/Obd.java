package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A car's OBD-II port through a small ELM327 Bluetooth adapter (plugged in under the steering, paired once in the phone's
 * Bluetooth settings, usually PIN 1234 or 0000). Reads the error codes (with the check-engine light), live engine numbers
 * (RPM, speed, coolant temperature, load, throttle, battery voltage) and clears the codes only when asked. Worker threads.
 */
final class Obd {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket sock;
    private InputStream in;
    private OutputStream out;

    /** Paired Bluetooth devices: {name, address} (adapters are usually named OBDII, OBD2, V-LINK, ELM327...). */
    @SuppressLint("MissingPermission")
    static List<String[]> paired(Context c) {
        List<String[]> l = new ArrayList<>();
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null) return l;
            for (BluetoothDevice d : a.getBondedDevices()) {
                String n = d.getName() == null ? d.getAddress() : d.getName();
                boolean obd = n.toUpperCase(Locale.ROOT).matches(".*(OBD|ELM|V-?LINK|VEEPEAK|KONNWEI|SCAN|CAR).*");
                if (obd) l.add(0, new String[]{n, d.getAddress()}); else l.add(new String[]{n, d.getAddress()});
            }
        } catch (Exception ignored) {}
        return l;
    }

    @SuppressLint("MissingPermission")
    void connect(Context c, String address) throws Exception {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) throw new IOException("bluetooth off");
        BluetoothDevice d = a.getRemoteDevice(address);
        try { a.cancelDiscovery(); } catch (Exception ignored) {}
        BluetoothSocket s;
        try {
            s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (IOException e) {
            s = d.createInsecureRfcommSocketToServiceRecord(SPP); // some cheap adapters only take this
            s.connect();
        }
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        cmd("ATZ", 2500);  // reset
        cmd("ATE0", 1000); // no echo
        cmd("ATL0", 1000); // no line feeds
        cmd("ATS0", 1000); // no spaces
        cmd("ATH0", 1000); // no headers
        cmd("ATSP0", 1500); // find the car's protocol by itself
        cmd("0100", 8000);  // the first question takes a while ("SEARCHING...")
    }

    boolean connected() { return sock != null && sock.isConnected(); }

    void close() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
    }

    /** Sends one command and reads the answer up to the '>' prompt. */
    synchronized String cmd(String c, long timeoutMs) throws IOException {
        if (out == null) throw new IOException("not connected");
        while (in.available() > 0) in.read(); // anything left over
        out.write((c + "\r").getBytes());
        out.flush();
        StringBuilder b = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int ch = in.read();
                if (ch < 0) break;
                if (ch == '>') break;
                b.append((char) ch);
            } else {
                try { Thread.sleep(15); } catch (InterruptedException e) { break; }
            }
        }
        // one line per answer (several control units can answer); spaces gone
        return b.toString().replace("SEARCHING...", "").replace('\r', '\n').replaceAll("[ \\t]+", "").replaceAll("\n+", "\n").trim();
    }

    /** The data bytes of a mode-01 answer (after "41 PID"), or null. */
    private int[] pid(String p) throws IOException {
        String want = "41" + p.toUpperCase(Locale.ROOT), hex = null;
        for (String line : cmd("01" + p, 2500).toUpperCase(Locale.ROOT).split("\n")) {
            int at = line.indexOf(want);
            if (at >= 0) { hex = line.substring(at + 4); break; }
        }
        if (hex == null) return null;
        int n = hex.length() / 2;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            try { out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); } catch (Exception e) { return i == 0 ? null : java.util.Arrays.copyOf(out, i); }
        }
        return out;
    }

    /** The live numbers, in words ("" when the car does not give one). */
    String[] live() throws IOException {
        int[] rpm = pid("0C"), spd = pid("0D"), cool = pid("05"), load = pid("04"), thr = pid("11");
        String volt = cmd("ATRV", 1500);
        return new String[]{
                rpm != null && rpm.length >= 2 ? ((rpm[0] * 256 + rpm[1]) / 4) + " RPM" : "",
                spd != null && spd.length >= 1 ? spd[0] + " km/h" : "",
                cool != null && cool.length >= 1 ? (cool[0] - 40) + " °C" : "",
                load != null && load.length >= 1 ? Math.round(load[0] * 100 / 255f) + " %" : "",
                thr != null && thr.length >= 1 ? Math.round(thr[0] * 100 / 255f) + " %" : "",
                volt.matches(".*\\d+(\\.\\d+)?V.*") ? volt.replaceAll(".*?(\\d+(?:\\.\\d+)?V).*", "$1") : ""};
    }

    /** Is the check-engine light on, and how many codes the car says it has: {on (0/1), count}, or null. */
    int[] mil() throws IOException {
        int[] a = pid("01");
        if (a == null || a.length < 1) return null;
        return new int[]{(a[0] & 0x80) != 0 ? 1 : 0, a[0] & 0x7F};
    }

    /** The stored error codes (P0300...). */
    List<String> codes() throws IOException {
        List<String> l = new ArrayList<>();
        for (String mode : new String[]{"03", "07"}) { // stored, then pending
            String key = mode.equals("03") ? "43" : "47";
            for (String line : frames(cmd(mode, 5000).toUpperCase(Locale.ROOT))) {
                if (!line.startsWith(key) || !line.matches("[0-9A-F]+")) continue;
                String part = line.substring(2);
                if ((part.length() / 2) % 2 == 1) part = part.substring(2); // CAN cars put the number of codes first
                for (int i = 0; i + 4 <= part.length(); i += 4) {
                    String code = decode(part.substring(i, i + 4));
                    String shown = code == null ? null : code + (mode.equals("07") ? " (pending)" : "");
                    if (shown != null && !l.contains(code) && !l.contains(shown)) l.add(shown);
                }
            }
        }
        return l;
    }

    /**
     * The answer's lines, with a long CAN answer's numbered frames ("0:4303…", "1:…", after a byte-count line like "00A")
     * joined back into one line.
     */
    static List<String> frames(String r) {
        List<String> out = new ArrayList<>();
        StringBuilder joined = null;
        int declared = -1; // the byte count line: the frames' padding after it is cut off
        for (String line : r.split("\n")) {
            String l = line.trim();
            if (l.matches("[0-9A-F]:[0-9A-F]*")) {
                if (l.startsWith("0:") && joined != null) { out.add(cut(joined, declared)); joined = null; declared = -1; }
                if (joined == null) joined = new StringBuilder();
                joined.append(l.substring(2));
            } else if (l.matches("[0-9A-F]{3}")) {
                if (joined != null) { out.add(cut(joined, declared)); joined = null; }
                declared = Integer.parseInt(l, 16);
            } else {
                if (joined != null) { out.add(cut(joined, declared)); joined = null; declared = -1; }
                if (!l.isEmpty()) out.add(l);
            }
        }
        if (joined != null) out.add(cut(joined, declared));
        return out;
    }

    private static String cut(StringBuilder b, int bytes) {
        return bytes > 0 && b.length() > bytes * 2 ? b.substring(0, bytes * 2) : b.toString();
    }

    /** Two bytes (4 hex digits) → P0300; 0000 → null. */
    static String decode(String h) {
        if (h == null || h.length() != 4 || h.equals("0000")) return null;
        try {
            int v = Integer.parseInt(h, 16);
            char sys = "PCBU".charAt((v >> 14) & 3);
            return sys + String.valueOf((v >> 12) & 3) + String.format(Locale.ENGLISH, "%03X", v & 0xFFF);
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears the codes and the check-engine light (only after he confirms). */
    boolean clear() throws IOException {
        return cmd("04", 5000).toUpperCase(Locale.ROOT).contains("44");
    }
}
