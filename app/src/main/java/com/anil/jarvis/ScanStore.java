package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the Jarvis camera looked at, kept on the phone: each scan is a folder (files/scans/<id>) with its photo(s)
 * and what Jarvis found (data.json). The list (newest 300) is in its own preferences, for "స్కాన్ చరిత్ర", "నా బోర్డులు"
 * and for questions like "పోయిన వారం చూపించిన మెడిసిన్ పేరేంటి?". Kept out of Google's backup (photos are big); his own
 * Drive backup takes them.
 */
final class ScanStore {
    private ScanStore() {}

    private static final int KEEP = 300;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_scans", Context.MODE_PRIVATE); }

    static String newId() { return "s" + Long.toString(System.currentTimeMillis(), 36); }

    static File dir(Context c, String id) {
        File d = new File(new File(c.getFilesDir(), "scans"), id.replaceAll("[^A-Za-z0-9_-]", ""));
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** All scans, newest first (only the boards / opened things when boards). */
    static synchronized List<JSONObject> list(Context c, boolean boards) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                if (!boards || o.optBoolean("board")) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Scans whose title, kind or answer has these words (newest first). */
    static List<JSONObject> search(Context c, String words, int max) {
        List<JSONObject> out = new ArrayList<>();
        String[] w = (words == null ? "" : words).toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (JSONObject o : list(c, false)) {
            String hay = (o.optString("title") + " " + o.optString("kind") + " " + o.optString("brief") + " " + o.optString("mode")).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String x : w) if (!x.isEmpty() && !hay.contains(x)) { all = false; break; }
            if (all) out.add(o);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Saves (or replaces) a scan's data, and its photo when given (name: photo.jpg, layer2.jpg...). */
    static synchronized void save(Context c, JSONObject given, Bitmap photo, String photoName) throws Exception {
        String id = given.optString("id");
        if (id.isEmpty()) { id = newId(); given.put("id", id); }
        if (!given.has("t")) given.put("t", System.currentTimeMillis());
        JSONObject data = new JSONObject(given.toString()); // what is written: a copy, with ID-like numbers masked in its words
        scrub(data, false);
        File d = dir(c, id);
        if (photo != null) {
            try (FileOutputStream o = new FileOutputStream(new File(d, photoName == null ? "photo.jpg" : photoName))) {
                photo.compress(Bitmap.CompressFormat.JPEG, 88, o);
            }
        }
        try (FileOutputStream o = new FileOutputStream(new File(d, "data.json"))) {
            o.write(data.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject row = new JSONObject().put("id", id).put("t", data.optLong("t")).put("title", data.optString("title"))
                .put("kind", data.optString("kind")).put("mode", data.optString("mode")).put("board", data.optBoolean("board"))
                .put("brief", cut(data.optString("say"), 160));
        JSONArray a;
        try { a = new JSONArray(sp(c).getString("list", "[]")); } catch (Exception e) { a = new JSONArray(); }
        JSONArray b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optJSONObject(i) == null ? "" : a.optJSONObject(i).optString("id"))) b.put(a.get(i));
        b.put(row);
        while (b.length() > KEEP) { // the oldest go, with their photos
            JSONObject old = b.optJSONObject(0);
            b.remove(0);
            if (old != null) deleteDir(new File(new File(c.getFilesDir(), "scans"), old.optString("id")));
        }
        sp(c).edit().putString("list", b.toString()).apply();
    }

    /** The words (not dates, numbers to call, links, search words or plates) in which ID-like numbers are masked. */
    private static final java.util.Set<String> WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "say", "title", "report", "q", "note", "info", "text", "warn", "next", "brief", "fault_why", "talk", "steps", "build", "does", "where", "what"));

    /** ID, policy, account and card numbers in the saved words are masked (FloatBubble.noIds); words: inside one of WORDS. */
    static void scrub(Object o, boolean words) {
        try {
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                java.util.Iterator<String> k = j.keys();
                List<String> keys = new ArrayList<>();
                while (k.hasNext()) keys.add(k.next());
                for (String key : keys) {
                    Object v = j.get(key);
                    boolean w = WORDS.contains(key);
                    if (v instanceof String) { if (w) j.put(key, FloatBubble.noIds((String) v)); }
                    else scrub(v, w);
                }
            } else if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.get(i);
                    if (v instanceof String) { if (words) a.put(i, FloatBubble.noIds((String) v)); }
                    else scrub(v, words);
                }
            }
        } catch (Exception ignored) {}
    }

    static JSONObject load(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(new File(new File(c.getFilesDir(), "scans"), id), "data.json");
        if (!f.exists()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** A photo of the scan, at most maxDim on its long side (null when missing). */
    static Bitmap photo(Context c, String id, String name, int maxDim) {
        File f = new File(new File(new File(c.getFilesDir(), "scans"), id), name == null ? "photo.jpg" : name);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static File file(Context c, String id, String name) { return new File(new File(new File(c.getFilesDir(), "scans"), id), name); }

    static synchronized void delete(Context c, String id) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]")), b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) b.put(a.get(i));
            sp(c).edit().putString("list", b.toString()).apply();
        } catch (Exception ignored) {}
        deleteDir(new File(new File(c.getFilesDir(), "scans"), id));
    }

    private static void deleteDir(File d) {
        File[] k = d.listFiles();
        if (k != null) for (File f : k) { if (f.isDirectory()) deleteDir(f); else //noinspection ResultOfMethodCallIgnored
            f.delete(); }
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** For Jarvis's voice tool: the scans that match, as short lines. */
    static JSONArray lines(Context c, String words, int max) throws Exception {
        JSONArray out = new JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH);
        for (JSONObject o : words == null || words.trim().isEmpty() ? list(c, false) : search(c, words, max)) {
            out.put(new JSONObject().put("id", o.optString("id")).put("when", f.format(new java.util.Date(o.optLong("t"))))
                    .put("what", o.optString("title")).put("kind", o.optString("kind")).put("said", o.optString("brief")));
            if (out.length() >= max) break;
        }
        return out;
    }
}
