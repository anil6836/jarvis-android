package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

/**
 * 🔌 C11: the car's engine watched the whole trip by the car's box, through the OBD adapter he connected once in the 🔌
 * OBD scanner (ELM327, classic Bluetooth). Read-only: the engine's heat, the battery, the check-engine light, the fuel
 * level (when the car gives it). Danger is said at once ("ఇంజిన్ చాలా వేడెక్కింది: సురక్షితంగా పక్కకి ఆపండి"); codes
 * are never cleared here (only in the scanner, on his yes). No adapter / no Bluetooth: it simply waits and tries again.
 */
final class ObdWatch {
    private ObdWatch() {}

    // ---- what the car said last (any thread)
    static volatile int coolant = Integer.MIN_VALUE, rpm = -1, fuelPct = -1;
    static volatile double volts = Double.NaN;
    /** The check-engine light: -1 not read yet, 0 off, 1 on. */
    static volatile int mil = -1;
    static volatile long readAt, fuelAt, voltsAt;
    /** A new trip began (the box restarted): the "once a trip" words may come again. */
    private static volatile boolean newTrip;

    static void newTrip() { newTrip = true; }
    static volatile String status = "";

    /** The scanner is open: it has the adapter (one Bluetooth line at a time). */
    static volatile boolean held;

    private static volatile Thread loop;

    /** The adapter he connected in the scanner (its address), or "". */
    static String adapter(Context c) { return new Prefs(c).sp.getString("obd_addr", ""); }

    /** The scanner connected this adapter: the car's box keeps it for the trips. */
    static void remember(Context c, String address, String name) {
        new Prefs(c).sp.edit().putString("obd_addr", address == null ? "" : address).putString("obd_name", name == null ? "" : name).apply();
    }

    /** Fresh numbers (under 30 s old). */
    static boolean live() { return readAt > 0 && System.currentTimeMillis() - readAt < 30_000; }

    /** The car screen's chip: "🌡️ 92° · 🔋 14.1V" ("" when nothing fresh). */
    static String chip() {
        if (!live()) return "";
        StringBuilder b = new StringBuilder();
        if (coolant != Integer.MIN_VALUE) b.append("🌡️ ").append(coolant).append("°");
        if (!Double.isNaN(volts) && System.currentTimeMillis() - voltsAt < 90_000) b.append(b.length() > 0 ? " · " : "").append("🔋 ").append(String.format(Locale.ROOT, "%.1fV", volts));
        if (mil == 1) b.append(b.length() > 0 ? " · " : "").append("⚠️");
        return b.toString();
    }

    /** Starts the box's watch when an adapter is known (one thread; it ends when car mode does). */
    static void start(Context c) {
        final Context app = c.getApplicationContext();
        if (!CarBox.on(app) || adapter(app).isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status = "Bluetooth అనుమతి లేదు (🔌 OBD స్కానర్ ఒక్కసారి తెరవండి)";
            return;
        }
        synchronized (ObdWatch.class) {
            if (loop != null && loop.isAlive()) return;
            loop = new Thread(() -> run(app), "obd-watch");
            loop.setDaemon(true);
            loop.start();
        }
    }

    private static void run(Context app) {
        Obd obd = new Obd();
        Rules rules = new Rules();
        int n = 0;
        while (CarBox.on(app) && !adapter(app).isEmpty()) {
            if (newTrip) { newTrip = false; rules = new Rules(); }
            if (held) { obd.close(); sleep(5000); continue; }
            if (!obd.connected()) {
                status = "🔌 కలుస్తున్నాను…";
                try { obd.connect(app, adapter(app)); status = "🔌 ✓"; }
                catch (Exception e) { obd.close(); status = "🔌 అడాప్టర్ దొరకలేదు"; sleep(60_000); continue; }
            }
            try {
                // (each pass's own answers only: a question the car didn't answer is "not known", never the old number)
                int[] cool = pid(obd, "05"), r = pid(obd, "0C");
                coolant = cool != null && cool.length >= 1 ? cool[0] - 40 : Integer.MIN_VALUE;
                rpm = r != null && r.length >= 2 ? (r[0] * 256 + r[1]) / 4 : -1;
                boolean newVolts = false;
                if (n % 6 == 0) { // (every half minute: the battery, the fuel; every minute: the light)
                    String v = obd.cmd("ATRV", 1500);
                    if (v.matches("(?s).*\\d+(\\.\\d+)?V.*")) try { volts = Double.parseDouble(v.replaceAll("(?s).*?(\\d+(?:\\.\\d+)?)V.*", "$1")); voltsAt = System.currentTimeMillis(); newVolts = true; } catch (Exception ignored) {}
                    int[] f = pid(obd, "2F");
                    if (f != null && f.length >= 1) {
                        int pct = Math.round(f[0] * 100 / 255f);
                        if (fuelPct >= 0 && pct >= fuelPct + 10) CarFuel.refuelSeen(app); // (the car's own level jumped: a fill)
                        fuelPct = pct;
                        fuelAt = System.currentTimeMillis();
                    }
                }
                if (n % 12 == 0) {
                    int[] m = obd.mil();
                    if (m != null) mil = m[0] == 1 ? 1 : 0;
                }
                if (cool != null || r != null) readAt = System.currentTimeMillis(); // (the engine computer answered this time)
                n++;
                String say = rules.check(coolant == Integer.MIN_VALUE ? Double.NaN : coolant, newVolts ? volts : Double.NaN, rpm, mil, System.currentTimeMillis());
                if (say != null) Announcer.say(app, say);
            } catch (Exception e) {
                obd.close(); // (the line dropped: the next round connects again)
            }
            sleep(5000);
        }
        obd.close();
        status = "";
    }

    private static int[] pid(Obd obd, String p) throws Exception {
        String want = "41" + p, hex = null;
        for (String line : obd.cmd("01" + p, 2500).toUpperCase(Locale.ROOT).split("\n")) {
            int at = line.indexOf(want);
            if (at >= 0) { hex = line.substring(at + 4); break; }
        }
        if (hex == null || hex.length() < 2) return null;
        int k = hex.length() / 2;
        int[] out = new int[k];
        for (int i = 0; i < k; i++) {
            try { out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); } catch (Exception e) { return i == 0 ? null : java.util.Arrays.copyOf(out, i); }
        }
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /**
     * When to speak (desk-tested): the engine at 110° or more → at once and every 2 minutes while it stays; 105° → once
     * in 15 minutes (not right after the 110° word); the battery not charging while the engine runs (under 12.6 V) or
     * charging too high (over 15.2 V) → once a trip, after two readings in a row; a weak battery with the engine off
     * (under 11.9 V, two readings) → once a trip; the check-engine light coming on (seen off first) → once.
     * v: a battery reading taken just now (NaN between readings); rpm: -1 when the engine computer didn't answer;
     * mil: -1 not read yet, 0 off, 1 on.
     */
    static final class Rules {
        private long hotAt = -1, warmAt = -1;
        private int lowRun, overRun, weakRun;
        private boolean lowCharge, overCharge, weak, milTold, milSeenOff;

        String check(double coolantC, double v, int rpm, int milState, long now) {
            if (!Double.isNaN(coolantC) && coolantC >= 110) {
                if (hotAt < 0 || now - hotAt >= 2 * 60_000L) {
                    hotAt = now;
                    warmAt = now; // (cooling down through 105–109 is not "rising")
                    return "జాగ్రత్త! ఇంజిన్ చాలా వేడెక్కింది (" + Math.round(coolantC) + " డిగ్రీలు). వెంటనే సురక్షితంగా పక్కకి ఆపి ఇంజిన్ ఆఫ్ చేయండి; రేడియేటర్ మూత వేడిగా ఉన్నప్పుడు తీయకండి.";
                }
                return null;
            }
            if (!Double.isNaN(coolantC) && coolantC >= 105 && (warmAt < 0 || now - warmAt >= 15 * 60_000L)) {
                warmAt = now;
                return "ఇంజిన్ వేడి పెరుగుతోంది (" + Math.round(coolantC) + " డిగ్రీలు). AC ఆపి నెమ్మదిగా వెళ్ళండి; ఇంకా పెరిగితే ఆపండి.";
            }
            boolean running = rpm > 500;
            if (!Double.isNaN(v)) { // (a fresh battery reading: counted with the engine's state of this same pass)
                lowRun = running && v < 12.6 ? lowRun + 1 : 0;
                overRun = running && v > 15.2 ? overRun + 1 : 0;
                weakRun = rpm == 0 && v < 11.9 ? weakRun + 1 : 0;
            }
            if (lowRun >= 2 && !lowCharge) {
                lowCharge = true;
                return String.format(Locale.ROOT, "బ్యాటరీ ఛార్జ్ అవ్వడం లేదు (%.1f వోల్ట్స్). దగ్గర్లో మెకానిక్‌కి ఆల్టర్నేటర్, బెల్ట్ చూపించండి.", v);
            }
            if (overRun >= 2 && !overCharge) {
                overCharge = true;
                return String.format(Locale.ROOT, "బ్యాటరీకి ఛార్జింగ్ ఎక్కువగా వస్తోంది (%.1f వోల్ట్స్). మెకానిక్‌కి చూపించండి.", v);
            }
            if (weakRun >= 2 && !weak) {
                weak = true;
                return String.format(Locale.ROOT, "బ్యాటరీ బలహీనంగా ఉంది (%.1f వోల్ట్స్). త్వరలో చెక్ చేయించండి.", v);
            }
            if (milState == 0) milSeenOff = true;
            if (milState == 1 && milSeenOff && !milTold) {
                milTold = true;
                return "చెక్-ఇంజిన్ లైట్ ఇప్పుడే వెలిగింది. కారు ఆపాక 🔌 OBD స్కానర్‌తో కోడ్ చూద్దాం; ఇంజిన్ వేడి, బ్రేక్ సమస్యలు ఉంటే వెంటనే ఆపండి.";
            }
            return null;
        }
    }
}
