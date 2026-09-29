package com.anil.jarvis;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The app's labels in Telugu (as written in the code) or English (Settings → App language, or the
 * తె / EN button on the main screen). English comes from assets/ui_en.tsv: "Telugu label<TAB>English".
 * Only what is shown on screen changes; Jarvis's voice and answers keep their own language.
 */
final class Tr {
    private Tr() {}

    private static volatile boolean english;
    private static volatile Map<String, String> en;
    private static Context app;

    static void init(Context c) {
        if (app != null || c == null) return;
        app = c.getApplicationContext();
        english = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("ui_english", false);
    }

    static boolean english() { return english; }

    static void setEnglish(Context c, boolean on) {
        init(c);
        english = on;
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("ui_english", on).apply();
    }

    /** The label in the chosen app language (unchanged when it is Telugu or has no English yet). */
    static String t(String s) {
        if (!english || s == null || s.isEmpty()) return s;
        Map<String, String> m = map();
        String v = m.get(s);
        if (v != null) return v;
        String core = s.trim(); // the same label with spaces or line breaks around it
        if (!core.isEmpty() && core.length() != s.length()) {
            v = m.get(core);
            if (v != null) {
                int at = s.indexOf(core);
                return s.substring(0, at) + v + s.substring(at + core.length());
            }
        }
        return s;
    }

    private static Map<String, String> map() {
        Map<String, String> m = en;
        if (m != null) return m;
        synchronized (Tr.class) {
            if (en != null) return en;
            Map<String, String> load = new HashMap<>(1200);
            if (app != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(app.getAssets().open("ui_en.tsv"), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        load.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
                    }
                } catch (Exception ignored) {}
            }
            en = load;
            return load;
        }
    }

    /** The labels are written the way they are in the Java code (\n, \", \\). */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }
}
