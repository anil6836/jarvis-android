package com.anil.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🏙️ Stage 3 (27 Oct): the 3D view's map pieces from OpenStreetMap (Overpass): squares of 0.01° (about a kilometre),
 * each asked once and kept in the app's folder on the box (never in a backup: they show where he drove; the oldest go
 * when there are more than 120), politely — one question at a time, a failed square asked again only after two
 * minutes, nothing asked without the internet. The view's world is built from the squares round the car, off the main
 * thread. Google content never comes here.
 */
final class World3DStore {
    static final String DIR = "world3d";
    static final double TILE = 0.01;
    static final int KEEP = 120;

    private final Context app;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(), cpu = Executors.newSingleThreadExecutor();
    /** The squares round him in metres (only the parsed shapes are kept: a square's JSON is big, read again only for a new middle). cpu thread. */
    private final Map<String, World3D.Area> parsed = new HashMap<>();
    private World3D.Proj parsedFor;
    private final Map<String, Long> failedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean fetching;
    private volatile boolean closed;

    World3DStore(Context c) {
        app = c.getApplicationContext();
        dir = new File(app.getFilesDir(), DIR);
    }

    /** A square's name for a place. Pure. */
    static String key(double lat, double lon) {
        return (int) Math.floor(lat / TILE) + "_" + (int) Math.floor(lon / TILE);
    }

    /** The squares within m metres of a place (the box round it). Pure. */
    static List<String> keysAround(double lat, double lon, double m) {
        double dLat = m / 110574.0, dLon = m / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        int i0 = (int) Math.floor((lat - dLat) / TILE), i1 = (int) Math.floor((lat + dLat) / TILE);
        int j0 = (int) Math.floor((lon - dLon) / TILE), j1 = (int) Math.floor((lon + dLon) / TILE);
        List<String> out = new ArrayList<>();
        // the nearest first (the square he is in is asked before the others)
        String here = key(lat, lon);
        out.add(here);
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) { String k = i + "_" + j; if (!k.equals(here)) out.add(k); }
        return out;
    }

    /** The box of a square: {south, west, north, east}. Pure. */
    static double[] bounds(String key) {
        String[] p = key.split("_");
        int i = Integer.parseInt(p[0]), j = Integer.parseInt(p[1]);
        return new double[]{i * TILE, j * TILE, (i + 1) * TILE, (j + 1) * TILE};
    }

    private File file(String key) { return new File(dir, key + ".json"); }

    void close() {
        closed = true;
        net.shutdownNow();
        cpu.shutdownNow();
    }

    /**
     * The world round (lat, lon) (r metres) built for the view, given on the main thread to done — with what is here
     * now (squares still coming: built again when they come, through more). Squares not yet kept are asked for.
     */
    void build(World3D.Proj p, double lat, double lon, float r, boolean toy, Done done, Runnable more) {
        if (closed) return;
        cpu.execute(() -> {
            try {
                if (parsedFor != p) { parsed.clear(); parsedFor = p; } // (a new middle: the squares' metres again)
                List<String> keys = keysAround(lat, lon, r + 150);
                List<World3D.Area> parts = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (String k : keys) {
                    World3D.Area a = parsed.get(k);
                    if (a == null) {
                        JSONArray el = read(k);
                        if (el != null) { a = World3D.parse(el, p); parsed.put(k, a); }
                    }
                    if (a != null) parts.add(a); else missing.add(k);
                }
                if (parsed.size() > 16) parsed.keySet().retainAll(keys); // (only the squares round him stay read)
                World3D.Area all = World3D.Area.merge(parts);
                float cx = p.x(lon), cz = p.z(lat);
                Mesh3D[] m = World3D.scene(all, cx, cz, r, toy);
                Car3DView.World w = new Car3DView.World(cx, cz, m[0], m[1], m[2], m[3]);
                final int miss = missing.size();
                main.post(() -> { if (!closed) done.built(w, all, miss); });
                if (!missing.isEmpty()) fetch(missing, more);
            } catch (Throwable e) { // (something broken: the world as it was, asked again later)
                main.post(() -> { if (!closed) done.failed(); });
            }
        });
    }

    interface Done {
        /** The world built; missing: squares not here yet (being asked for, or failed: asked again later). */
        void built(Car3DView.World w, World3D.Area area, int missing);
        void failed();
    }

    /** A kept square's Overpass answer read from its file (not kept in memory), or null. cpu thread. */
    private JSONArray read(String k) {
        File f = file(k);
        if (!f.exists()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            JSONArray el = new JSONArray(new String(b, StandardCharsets.UTF_8));
            if (!f.setLastModified(System.currentTimeMillis())) { /* (the oldest-used order only) */ }
            return el;
        } catch (Throwable e) {
            if (!f.delete()) f.deleteOnExit(); // (broken: asked again)
            return null;
        }
    }

    /** The missing squares asked for, one at a time (nearest first); when one comes, more runs (on the main thread). */
    private void fetch(List<String> keys, Runnable more) {
        if (fetching || closed || !Net.online(app)) return;
        long now = System.currentTimeMillis();
        String k = null;
        for (String q : keys) { Long f = failedAt.get(q); if (f == null || now - f > 120_000) { k = q; break; } }
        if (k == null) return;
        final String key = k;
        fetching = true;
        net.execute(() -> {
            boolean ok = false;
            try {
                double[] b = bounds(key);
                JSONArray el = Drive.overpass(World3D.query(b[0], b[1], b[2], b[3]));
                if (el != null) {
                    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no folder");
                    File tmp = new File(dir, key + ".json.part");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(el.toString().getBytes(StandardCharsets.UTF_8)); }
                    ok = tmp.renameTo(file(key));
                    if (!ok && !tmp.delete()) tmp.deleteOnExit();
                    prune();
                }
            } catch (Throwable ignored) {}
            final boolean got = ok;
            main.post(() -> {
                fetching = false;
                if (!got) failedAt.put(key, System.currentTimeMillis());
                if (!closed && more != null) more.run();
            });
        });
    }

    /** At most KEEP squares kept: the least lately used go. */
    private void prune() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null || fs.length <= KEEP) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - KEEP; i++) if (!fs[i].delete()) fs[i].deleteOnExit();
    }

    /** How many squares are kept (for the honest "map pieces" line in ⚙️). */
    int kept() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        return fs == null ? 0 : fs.length;
    }

    /** All kept squares removed (his "3D మ్యాప్ ముక్కలు తీసేయి"). */
    static void clear(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (!f.delete()) f.deleteOnExit();
    }

    static String sizeWords(Context c) {
        File[] fs = new File(c.getFilesDir(), DIR).listFiles();
        long b = 0;
        if (fs != null) for (File f : fs) b += f.length();
        return String.format(Locale.ROOT, "%.1f MB", b / 1048576f);
    }
}
