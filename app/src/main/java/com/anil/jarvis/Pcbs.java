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
 * 🔌 PCB స్టూడియో: his boards, one small file each in the app's own folder (parts, how they join, where they sit, the tracks:
 * numbers and short words only). The page cleans a design before it sends it; the shape is checked again here.
 */
final class Pcbs {
    private Pcbs() {}

    static final int MAX = 100;
    private static final int MAX_BYTES = 1_500_000;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    static File dir(Context c) { return new File(c.getFilesDir(), "pcb"); }

    /** A design from the page, if it has the shape of one (else null). */
    static JSONObject check(JSONObject d) {
        if (d == null) return null;
        try {
            if (!ID.matcher(d.optString("id", "")).matches()) return null;
            JSONObject b = d.optJSONObject("board");
            JSONArray parts = d.optJSONArray("parts"), nets = d.optJSONArray("nets");
            if (b == null || parts == null || nets == null || parts.length() > 120 || nets.length() > 200) return null;
            double w = b.optDouble("w", -1), h = b.optDouble("h", -1);
            if (!(w >= 10 && w <= 200 && h >= 10 && h <= 200)) return null;
            JSONArray tr = d.optJSONArray("tracks"), vi = d.optJSONArray("vias");
            if ((tr != null && tr.length() > 3000) || (vi != null && vi.length() > 1000)) return null;
            d.put("name", Workshop.cap(d.optString("name", "నా బోర్డ్").replaceAll("[\\p{Cntrl}]", " ").trim(), 60));
            d.put("at", Math.max(0, Math.min(System.currentTimeMillis() + 86_400_000L, d.optLong("at", System.currentTimeMillis()))));
            if (d.toString().length() > MAX_BYTES) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes it (replacing the same id); the oldest go when there are more than MAX. */
    static boolean save(Context c, JSONObject in) {
        JSONObject d = check(in);
        if (d == null) return false;
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File f = new File(dir, d.optString("id") + ".json"), part = new File(dir, d.optString("id") + ".json.part");
        try (FileOutputStream o = new FileOutputStream(part)) {
            o.write(d.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        if (!part.renameTo(f)) { //noinspection ResultOfMethodCallIgnored
            part.delete(); return false; }
        List<JSONObject> all = summaries(c);
        for (int i = MAX; i < all.size(); i++) //noinspection ResultOfMethodCallIgnored
            new File(dir, all.get(i).optString("id") + ".json").delete();
        return true;
    }

    static JSONObject load(Context c, String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        File f = new File(dir(c), id + ".json");
        if (!f.isFile() || f.length() > MAX_BYTES * 2L) return null;
        try {
            return check(new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    static void remove(Context c, String id) {
        if (id != null && ID.matcher(id).matches()) //noinspection ResultOfMethodCallIgnored
            new File(dir(c), id + ".json").delete();
    }

    /** What the list shows: id, name, the ready board it came from, when, size, how many parts (newest first). */
    static List<JSONObject> summaries(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] fs = dir(c).listFiles((x, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                JSONObject d = check(new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
                if (d == null) continue;
                JSONObject b = d.optJSONObject("board");
                out.add(new JSONObject().put("id", d.optString("id")).put("name", d.optString("name")).put("proj", d.optString("proj", ""))
                        .put("at", d.optLong("at")).put("w", b == null ? 0 : b.optDouble("w")).put("h", b == null ? 0 : b.optDouble("h"))
                        .put("n", d.optJSONArray("parts") == null ? 0 : d.optJSONArray("parts").length()));
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        return out;
    }

    static JSONArray listJson(Context c) {
        JSONArray a = new JSONArray();
        for (JSONObject o : summaries(c)) a.put(o);
        return a;
    }

    // the board he had open last (opened again when the studio opens)
    static String last(Context c) { return c.getSharedPreferences("pcb_studio", Context.MODE_PRIVATE).getString("last", null); }

    static void setLast(Context c, String id) {
        c.getSharedPreferences("pcb_studio", Context.MODE_PRIVATE).edit().putString("last", id).apply();
    }
}
