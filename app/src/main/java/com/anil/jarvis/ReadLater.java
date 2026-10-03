package com.anil.jarvis;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pages (or Jarvis's explanations of them) kept to read later: from the floating button's 💾, read aloud when he
 * asks ("సేవ్ చేసినవి చదువు"), on a duty break or on the bike. The text is kept in files on the phone; the newest 40.
 */
final class ReadLater {
    private ReadLater() {}

    private static final int MAX = 40;

    private static File dir(Context c) { return new File(c.getFilesDir(), "read_later"); }

    /** Its own list (not with the other notes), so it can stay out of backups with the folder. */
    private static android.content.SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_read_later", Context.MODE_PRIVATE); }

    private static synchronized List<JSONObject> index(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void write(Context c, List<JSONObject> l) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (JSONObject o : l) a.put(o);
        sp(c).edit().putString("list", a.toString()).commit();
    }

    /** Keeps it; returns the entry {id, title, app, t, chars}. */
    static synchronized JSONObject save(Context c, String title, String app, String text) throws Exception {
        String id = Notes.id("rl");
        File d = dir(c);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String t = text.length() > 60000 ? text.substring(0, 60000) : text;
        java.nio.file.Files.write(new File(d, id + ".txt").toPath(), t.getBytes(StandardCharsets.UTF_8));
        String name = title == null || title.trim().isEmpty() ? firstLine(t) : title.trim();
        JSONObject o = new JSONObject().put("id", id).put("title", name.length() > 80 ? name.substring(0, 80) + "…" : name)
                .put("app", app == null ? "" : app).put("t", System.currentTimeMillis()).put("chars", t.length());
        List<JSONObject> l = index(c);
        l.add(o);
        while (l.size() > MAX) l.remove(0);
        write(c, l);
        java.util.Set<String> keep = new java.util.HashSet<>(); // files no longer in the list go (also ones left by an old crash)
        for (JSONObject x : l) keep.add(x.optString("id") + ".txt");
        File[] files = d.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }
        return o;
    }

    /** Newest first. */
    static List<JSONObject> list(Context c) {
        List<JSONObject> l = index(c);
        Collections.reverse(l);
        return l;
    }

    static String text(Context c, String id) {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File(dir(c), id + ".txt").toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized boolean remove(Context c, String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), id + ".txt").delete();
        List<JSONObject> l = index(c);
        boolean found = l.removeIf(o -> o.optString("id").equals(id));
        if (found) write(c, l);
        return found;
    }

    /** By its number in the newest-first list (1 = newest) or by words of its title; null when not found. */
    static JSONObject find(Context c, String which) {
        List<JSONObject> l = list(c);
        if (l.isEmpty()) return null;
        String w = which == null ? "" : which.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return l.get(0);
        if (w.matches("\\d{1,2}")) { // only a bare number is a number ("5G phones" is a title)
            int n = Integer.parseInt(w);
            return n >= 1 && n <= l.size() ? l.get(n - 1) : null;
        }
        for (JSONObject o : l) if ((o.optString("title") + " " + o.optString("app")).toLowerCase(Locale.ROOT).contains(w)) return o;
        return null;
    }

    private static String firstLine(String t) {
        for (String line : t.split("\n")) { String s = line.trim(); if (s.length() > 3) return s; }
        return "పేజీ";
    }

    static List<String> titles(Context c) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (JSONObject o : list(c)) out.add((i++) + ". " + o.optString("title") + (o.optString("app").isEmpty() ? "" : " (" + o.optString("app") + ")"));
        return out;
    }
}
