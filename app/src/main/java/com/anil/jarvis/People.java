package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The people Jarvis knows by face: only those Anil introduced by name ("ఇతను రాము, గుర్తుపెట్టుకో"), never strangers.
 * Each is kept as a few face fingerprints (128 numbers from FaceNet; no photos), only on this phone and out of
 * backups. Anil can list them and make Jarvis forget anyone.
 */
final class People {
    private People() {}

    /** Two pictures of the same person are at least this alike (cosine of the fingerprints). */
    static final float SAME = 0.60f;
    /** ...and clearly more alike than the next best person. */
    static final float MARGIN = 0.07f;
    private static final int MAX_SAMPLES = 12;

    static final class Person {
        final String name;
        final boolean owner;
        final List<float[]> samples;
        Person(String name, boolean owner, List<float[]> samples) { this.name = name; this.owner = owner; this.samples = samples; }
    }

    static final class Match {
        final String name;
        final boolean owner;
        final float score, next;
        Match(String name, boolean owner, float score, float next) { this.name = name; this.owner = owner; this.score = score; this.next = next; }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_people", Context.MODE_PRIVATE); }

    private static volatile List<Person> cache;

    static synchronized List<Person> all(Context c) {
        if (cache != null) return cache;
        List<Person> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                List<float[]> s = new ArrayList<>();
                JSONArray e = o.optJSONArray("e");
                for (int k = 0; e != null && k < e.length(); k++) {
                    float[] v = decode(e.getString(k));
                    if (v != null) s.add(v);
                }
                if (!s.isEmpty()) out.add(new Person(o.optString("name"), o.optBoolean("owner"), s));
            }
        } catch (Exception ignored) {}
        cache = out;
        return out;
    }

    static int count(Context c) { return all(c).size(); }

    static List<String> names(Context c) {
        List<String> n = new ArrayList<>();
        for (Person p : all(c)) n.add(p.owner ? p.name + " (మీరు)" : p.name);
        return n;
    }

    /** Adds a person (or more pictures of one already known by that name). */
    static synchronized void add(Context c, String name, boolean owner, List<float[]> samples) {
        List<Person> list = new ArrayList<>(all(c));
        List<float[]> merged = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            Person p = list.get(i);
            if (p.name.equalsIgnoreCase(name) || (owner && p.owner)) { merged.addAll(p.samples); owner |= p.owner; list.remove(i); }
        }
        merged.addAll(samples);
        while (merged.size() > MAX_SAMPLES) merged.remove(0); // the newest pictures count most
        list.add(new Person(name, owner, merged));
        save(c, list);
    }

    /** Forgets a person by name; false when no one by that name is known. */
    static synchronized boolean remove(Context c, String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(" (మీరు)", "");
        List<Person> list = new ArrayList<>(all(c));
        boolean gone = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).name.toLowerCase(Locale.ROOT).equals(n)) { list.remove(i); gone = true; }
        }
        if (gone) {
            save(c, list);
            sp(c).edit().remove("greeted_" + n).apply();
            FaceSight.forgetSeen(n);
        }
        return gone;
    }

    static synchronized void clear(Context c) { save(c, new ArrayList<>()); }

    private static void save(Context c, List<Person> list) {
        JSONArray a = new JSONArray();
        try {
            for (Person p : list) {
                JSONArray e = new JSONArray();
                for (float[] v : p.samples) e.put(encode(v));
                a.put(new JSONObject().put("name", p.name).put("owner", p.owner).put("e", e));
            }
        } catch (Exception ignored) {}
        sp(c).edit().putString("list", a.toString()).apply();
        cache = list;
    }

    /** The best known person for this fingerprint, or a Match with a null name when it is no one he introduced. */
    static Match match(List<Person> people, float[] e) {
        String best = null;
        boolean owner = false;
        float b = -1, second = -1;
        for (Person p : people) {
            float s = -1;
            for (float[] v : p.samples) s = Math.max(s, cos(e, v));
            if (s > b) { second = b; b = s; best = p.name; owner = p.owner; }
            else if (s > second) second = s;
        }
        boolean ok = best != null && b >= SAME && b - Math.max(second, 0) >= MARGIN;
        return new Match(ok ? best : null, ok && owner, b, second);
    }

    /** How alike this fingerprint is to a person's closest picture. */
    static float best(Person p, float[] e) {
        float s = -1;
        for (float[] v : p.samples) s = Math.max(s, cos(e, v));
        return s;
    }

    /** The average of several fingerprints (unit length). */
    static float[] mean(List<float[]> v) {
        float[] m = new float[v.get(0).length];
        for (float[] x : v) for (int i = 0; i < m.length; i++) m[i] += x[i];
        return unit(m);
    }

    static float cos(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length && i < b.length; i++) d += a[i] * b[i];
        return (float) d; // both are unit length
    }

    static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n < 1e-9) return v;
        for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }

    private static String encode(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) b.putFloat(x);
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String s) {
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN);
            float[] v = new float[b.remaining() / 4];
            for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
            return v.length == 0 ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
