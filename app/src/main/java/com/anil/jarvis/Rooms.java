package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 📐 ఇంటి కొలతలు: his rooms' plans and kept lengths, one small file each in the app's own folder (numbers only: no camera
 * picture is ever kept). The page's entries are checked here before they are written.
 */
final class Rooms {
    private Rooms() {}

    static final int MAX = 200;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    static File dir(Context c) { return new File(c.getFilesDir(), "rooms"); }

    /** An entry from the page made safe: a room (corners in metres + his paint / tile numbers) or a list of lengths; null = not one. */
    static JSONObject clean(JSONObject in) {
        if (in == null) return null;
        try {
            String id = in.optString("id", ""), kind = in.optString("kind", "");
            if (!ID.matcher(id).matches() || !("room".equals(kind) || "measures".equals(kind))) return null;
            JSONObject o = new JSONObject().put("id", id).put("kind", kind)
                    .put("name", Workshop.cap(in.optString("name", "room".equals(kind) ? "గది" : "కొలతలు").replaceAll("[\\p{Cntrl}]", " ").trim(), 40))
                    .put("at", Math.max(0, Math.min(System.currentTimeMillis() + 86_400_000L, in.optLong("at", System.currentTimeMillis()))));
            if ("room".equals(kind)) {
                JSONArray cs = in.optJSONArray("corners"), out = new JSONArray();
                for (int i = 0; cs != null && i < cs.length() && i < 64; i++) {
                    JSONArray q = cs.optJSONArray(i);
                    if (q == null || q.length() < 2) continue;
                    double x = q.optDouble(0, Double.NaN), z = q.optDouble(1, Double.NaN);
                    if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 100 || Math.abs(z) > 100) continue;
                    out.put(new JSONArray().put(x).put(z));
                }
                if (out.length() < 3) return null;
                o.put("corners", out);
                o.put("h_ft", clamp(in.optDouble("h_ft", 10), 6, 30, 10));
                o.put("doors", (int) clamp(in.optDouble("doors", 1), 0, 20, 1));
                o.put("windows", (int) clamp(in.optDouble("windows", 1), 0, 30, 1));
                o.put("cover", clamp(in.optDouble("cover", 100), 20, 400, 100));
                String t = in.optString("tile", "600x600");
                o.put("tile", t.matches("600x600|800x800|600x1200|300x300") ? t : "600x600");
                o.put("waste", clamp(in.optDouble("waste", 10), 0, 40, 10));
                o.put("perBox", (int) clamp(in.optDouble("perBox", 4), 0, 50, 4));
                o.put("ceil", in.optBoolean("ceil", false));
            } else {
                JSONArray ls = in.optJSONArray("list"), out = new JSONArray();
                for (int i = 0; ls != null && i < ls.length() && i < 100; i++) {
                    double m = ls.optJSONObject(i) == null ? Double.NaN : ls.optJSONObject(i).optDouble("m", Double.NaN);
                    if (Double.isFinite(m) && m >= 0 && m <= 100) out.put(new JSONObject().put("m", m));
                }
                if (out.length() == 0) return null;
                o.put("list", out);
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double x, double lo, double hi, double d) { return Double.isFinite(x) ? Math.max(lo, Math.min(hi, x)) : d; }

    /** Writes it (replacing the same id); the oldest go when there are more than MAX. */
    static boolean save(Context c, JSONObject in) {
        JSONObject o = clean(in);
        if (o == null) return false;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        try (FileOutputStream f = new FileOutputStream(new File(d, o.getString("id") + ".json"))) {
            f.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        List<JSONObject> all = list(c);
        for (int i = MAX; i < all.size(); i++) new File(d, all.get(i).optString("id") + ".json").delete();
        return true;
    }

    static void remove(Context c, String id) {
        if (id != null && ID.matcher(id).matches()) new File(dir(c), id + ".json").delete();
    }

    /** All kept entries, newest first. */
    static List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] fs = dir(c).listFiles((x, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                JSONObject o = clean(new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
                if (o != null) out.add(o);
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        return out;
    }

    static JSONArray listJson(Context c) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list(c)) a.put(o);
        return a;
    }
}
