package com.anil.jarvis;

import java.util.Locale;

/**
 * Everyday words for the Settings and Features search boxes: "అమ్మ" also finds the home tablet's options, "ఆట" the games,
 * "మాత్ర" the tablets / medicines, "పెట్రోల్" the bike... Every word he types must be found (itself, or one of its friends).
 */
final class SearchWords {
    private SearchWords() {}

    /** Words that mean the same thing for the search (lower case). */
    static final String[][] GROUPS = {
            {"అమ్మ", "అమ్మగారు", "అమ్మగారి", "అమ్మకి", "అమ్మతో", "ఇంటి టాబ్లెట్", "ఇంటి jarvis", "టాబ్లెట్‌కి", "(టాబ్లెట్)", "amma", "mother"},
            {"ఆట", "ఆటలు", "ఆడు", "గేమ్", "గేమ్స్", "game", "games", "చదరంగం", "చెస్", "chess", "లూడో", "ludo", "వైకుంఠపాళి"},
            {"మాత్ర", "మాత్రలు", "మందు", "మందులు", "టాబ్లెట్లు", "టాబ్లెట్ వేసు", "medicine", "medicines", "pill", "pills"},
            {"పెట్రోల్", "బైక్", "బండి", "ఛార్జ్", "ఛార్జింగ్", "రేంజ్", "bike", "petrol", "ev"},
            {"డబ్బు", "ఖర్చు", "ఖర్చులు", "బిల్లు", "money", "expense"},
            {"గొంతు", "వాయిస్", "మాట్లాడు", "voice", "speak"},
            {"వినడం", "మైక్", "వేక్ వర్డ్", "mic", "listen", "wake"},
            {"బ్యాకప్", "డ్రైవ్", "రీస్టోర్", "backup", "drive", "restore"},
            {"మెసేజ్", "మెసేజ్‌లు", "sms", "whatsapp", "వాట్సాప్", "నోటిఫికేషన్"},
            {"అలారం", "నిద్ర లేపు", "alarm"},
            {"వాతావరణం", "వర్షం", "weather", "rain"},
            {"కెమెరా", "ఫోటో", "ఫోటోలు", "camera", "photo", "photos"},
            {"ఆరోగ్యం", "షుగర్", "బీపీ", "health", "sugar", "bp"},
            {"key", "keys", "కీ", "api", "మెదడు", "ai"},
            {"రిమైండర్", "గుర్తు", "గుర్తుచేయి", "reminder", "remind"},
            {"రంగు", "రంగులు", "థీమ్", "theme", "color", "colour"},
            {"లైట్", "ఫ్యాన్", "ఏసీ", "ac", "tv", "టీవీ", "స్మార్ట్ హోమ్", "alexa"},
            {"అప్డేట్", "వెర్షన్", "update", "version"},
            {"sos", "అత్యవసరం", "ఎమర్జెన్సీ", "emergency"},
            {"వాచ్", "watch"}};

    /**
     * The word itself and its friends: a group is joined when the word is one of it, starts with one of it (of 3+ letters:
     * "ఆటో" is not "ఆట"), or (3+ letters itself) begins one ("ఫోట" → ఫోటో).
     */
    static java.util.List<String> forms(String word) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return out;
        out.add(w);
        for (String[] g : GROUPS) {
            boolean in = false;
            for (String m : g) {
                if (w.equals(m) || w.startsWith(m) && m.length() >= 3 || w.length() >= 3 && m.startsWith(w)) { in = true; break; }
            }
            if (in) for (String m : g) if (!out.contains(m)) out.add(m);
        }
        return out;
    }

    /** Every word of the query is in the text (itself or a friend). The text should be lower case already. */
    static boolean matches(String text, String query) {
        if (text == null || query == null) return false;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return false;
        if (text.contains(q)) return true; // (the whole thing as typed)
        for (String part : q.split("\\s+")) {
            if (part.isEmpty()) continue;
            boolean found = false;
            for (String f : forms(part)) if (f.equals(part) ? text.contains(f) : has(text, f)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    /** A friend word in the text; a short one (ac, tv, ai, ev, bp, ఆట…) only as a word of its own, not inside "backup" / "ఆటో". */
    static boolean has(String text, String f) {
        if (f.length() >= 3) return text.contains(f);
        for (int i = text.indexOf(f); i >= 0; i = text.indexOf(f, i + 1)) {
            boolean before = i == 0 || !wordChar(text.charAt(i - 1)), after = i + f.length() >= text.length() || !wordChar(text.charAt(i + f.length()));
            if (before && after) return true;
        }
        return false;
    }

    private static boolean wordChar(char ch) {
        int t = Character.getType(ch);
        return Character.isLetterOrDigit(ch) || t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK;
    }
}
