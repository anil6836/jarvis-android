package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Only my voice": a voice print of Anil saying "Jarvis", and the distance check used when the
 * wake word is heard. Uses Vosk's on-phone speaker model; nothing leaves the phone.
 */
final class VoiceLock {
    private VoiceLock() {}

    /** Last wake attempt: how far the voice was from Anil's print (0 = same), for the settings screen. */
    static volatile double lastDistance = -1;
    static volatile boolean lastAccepted = true;

    static float[] print(Context c) {
        String s = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("voice_print", "");
        if (s.isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(s);
            float[] v = new float[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) a.getDouble(i);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, float[] v, float max) {
        JSONArray a = new JSONArray();
        try { for (float x : v) a.put((double) x); } catch (Exception ignored) {}
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                .putString("voice_print", a.toString()).putFloat("voice_lock_max", max).apply();
    }

    /** The "spk" vector of a Vosk result, or null. */
    static float[] vector(String voskJson) {
        try {
            JSONArray a = new JSONObject(voskJson).optJSONArray("spk");
            if (a == null || a.length() == 0) return null;
            float[] v = new float[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) a.getDouble(i);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        float[] o = new float[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (float) (n == 0 ? 0 : v[i] / n);
        return o;
    }

    /** Cosine distance: 0 = same voice direction, 1 = unrelated, 2 = opposite. */
    static double distance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 2;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na == 0 || nb == 0) return 2;
        return 1 - dot / Math.sqrt(na * nb);
    }
}
