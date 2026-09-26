package com.anil.jarvis;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Telugu Bible (Indian Revised Version, 2019) from the open wldeh/bible-api data,
 * so Jarvis can read any chapter or verse aloud, and a verse for each day.
 */
final class Bible {
    private Bible() {}

    private static final String BASE = "https://cdn.jsdelivr.net/gh/wldeh/bible-api/bibles/te-IN-irvtel/books/";
    private static final String BASE2 = "https://raw.githubusercontent.com/wldeh/bible-api/main/bibles/te-IN-irvtel/books/";

    /** English name -> folder name used by the data set (66 books). */
    private static final String[][] BOOKS = {
            {"genesis", "ఆది"}, {"exodus", "నిర్గమ"}, {"leviticus", "లేవీ"}, {"numbers", "సంఖ్యా"}, {"deuteronomy", "ద్వితీ"},
            {"joshua", "యెహో"}, {"judges", "న్యాయాధి"}, {"ruth", "రూతు"}, {"1 samuel", "1సమూ"}, {"2 samuel", "2సమూ"},
            {"1 kings", "1రాజులు"}, {"2 kings", "2రాజులు"}, {"1 chronicles", "1దిన"}, {"2 chronicles", "2దిన"}, {"ezra", "ఎజ్రా"},
            {"nehemiah", "నెహెమ్యా"}, {"esther", "ఎస్తేరు"}, {"job", "యోబు"}, {"psalms", "కీర్తన"}, {"proverbs", "సామెత"},
            {"ecclesiastes", "ప్రసంగి"}, {"song of solomon", "పరమ"}, {"isaiah", "యెషయా"}, {"jeremiah", "యిర్మీయా"}, {"lamentations", "విలాప"},
            {"ezekiel", "యెహె"}, {"daniel", "దాని"}, {"hosea", "హోషే"}, {"joel", "యోవే"}, {"amos", "ఆమోసు"}, {"obadiah", "ఓబద్యా"},
            {"jonah", "యోనా"}, {"micah", "మీకా"}, {"nahum", "నహూ"}, {"habakkuk", "హబ"}, {"zephaniah", "జెఫన్యా"}, {"haggai", "హగ్గయి"},
            {"zechariah", "జెకర్యా"}, {"malachi", "మలాకీ"}, {"matthew", "మత్తయి"}, {"mark", "మార్కు"}, {"luke", "లూకా"}, {"john", "యోహాను"},
            {"acts", "అపొస్తలులకార్యములు"}, {"romans", "రోమాపత్రిక"}, {"1 corinthians", "1కొరింతీపత్రిక"}, {"2 corinthians", "2కొరింతీపత్రిక"},
            {"galatians", "గలతీపత్రిక"}, {"ephesians", "ఎఫెసీపత్రిక"}, {"philippians", "ఫిలిప్పీపత్రిక"}, {"colossians", "కొలస్సీపత్రిక"},
            {"1 thessalonians", "1తెస్సలోనికపత్రిక"}, {"2 thessalonians", "2తెస్సలోనికపత్రిక"}, {"1 timothy", "1తిమోతిపత్రిక"},
            {"2 timothy", "2తిమోతిపత్రిక"}, {"titus", "తీతుపత్రిక"}, {"philemon", "ఫిలేమోనుపత్రిక"}, {"hebrews", "హెబ్రీపత్రిక"},
            {"james", "యాకోబుపత్రిక"}, {"1 peter", "1పేతురుపత్రిక"}, {"2 peter", "2పేతురుపత్రిక"}, {"1 john", "1యోహానుపత్రిక"},
            {"2 john", "2యోహానుపత్రిక"}, {"3 john", "3యోహానుపత్రిక"}, {"jude", "యూదాపత్రిక"}, {"revelation", "ప్రకటనగ్రంథం"}};

    /** Book names (normalized with norm(), so "1 samuel", "1samuel" and "1సమూ" all match) -> folder; in book order. */
    private static final Map<String, String> ALIAS = new LinkedHashMap<>();
    static {
        for (String[] b : BOOKS) { put(b[0], b[1]); put(b[1], b[1]); }
        String[][] more = {{"psalm", "కీర్తన"}, {"కీర్తనలు", "కీర్తన"}, {"సామెతలు", "సామెత"}, {"ఆదికాండము", "ఆది"}, {"నిర్గమకాండము", "నిర్గమ"},
                {"యోహాను సువార్త", "యోహాను"}, {"మత్తయి సువార్త", "మత్తయి"}, {"మార్కు సువార్త", "మార్కు"}, {"లూకా సువార్త", "లూకా"},
                {"రోమా", "రోమాపత్రిక"}, {"హెబ్రీ", "హెబ్రీపత్రిక"}, {"ప్రకటన", "ప్రకటనగ్రంథం"}, {"అపొస్తలుల కార్యములు", "అపొస్తలులకార్యములు"},
                {"songs", "పరమ"}, {"song of songs", "పరమ"}, {"యెషయా గ్రంథము", "యెషయా"}, {"revelations", "ప్రకటనగ్రంథం"}};
        for (String[] m : more) put(m[0], m[1]);
    }

    private static void put(String name, String folder) {
        String k = norm(name);
        if (!ALIAS.containsKey(k)) ALIAS.put(k, folder);
    }

    /** Lower case, "first/i/1st" -> "1" etc., and no spaces at all, the same for the question and the book list. */
    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT)
                .replaceAll("^(first|i|1st)\\s+", "1").replaceAll("^(second|ii|2nd)\\s+", "2").replaceAll("^(third|iii|3rd)\\s+", "3")
                .replaceAll("[\\s\\u200c\\u200d]+", "");
    }

    static String folder(String book) {
        if (book == null) return null;
        String b = norm(book);
        if (b.isEmpty()) return null;
        String f = ALIAS.get(b);
        if (f != null) return f;
        // a partial name: only when there is enough of it ("" or "1" would match every book)
        if (b.length() < 3) return null;
        for (Map.Entry<String, String> e : ALIAS.entrySet()) if (e.getKey().startsWith(b) || b.startsWith(e.getKey())) return e.getValue();
        return null;
    }

    private static JSONObject get(String path) throws Exception {
        try {
            return Http.get(BASE + path);
        } catch (Exception e) {
            return Http.get(BASE2 + path);
        }
    }

    /** Verses from..to of a chapter (to = 0: up to 12 verses from 'from'). */
    static JSONObject read(String book, int chapter, int from, int to) throws Exception {
        String f = folder(book);
        if (f == null) throw new IllegalArgumentException("unknown book " + book);
        String enc = Uri.encode(f);
        JSONArray data = get(enc + "/chapters/" + chapter + ".json").optJSONArray("data");
        if (data == null) throw new IllegalStateException("chapter not found");
        int a = Math.max(1, from), z = to > 0 ? to : (from > 0 && to == 0 ? from : a + 11);
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject v = data.getJSONObject(i);
            int num = v.optInt("verse");
            if (num < a || num > z) continue;
            b.append(num).append(". ").append(v.optString("text").replace("\"", "")).append('\n');
            if (++n >= 40) break;
        }
        return new JSONObject().put("book", f).put("chapter", chapter).put("verses", a + "-" + Math.min(z, a + n - 1))
                .put("text", b.toString().trim()).put("chapter_verses", data.length())
                .put("version", "ఇండియన్ రివైజ్డ్ వెర్షన్ (IRV) తెలుగు 2019");
    }

    private static final String[][] DAILY = {
            {"john", "3", "16"}, {"psalms", "23", "1"}, {"philippians", "4", "13"}, {"jeremiah", "29", "11"}, {"proverbs", "3", "5"},
            {"isaiah", "41", "10"}, {"romans", "8", "28"}, {"matthew", "11", "28"}, {"joshua", "1", "9"}, {"psalms", "46", "1"},
            {"2 corinthians", "5", "17"}, {"galatians", "5", "22"}, {"hebrews", "11", "1"}, {"1 corinthians", "13", "4"}, {"psalms", "91", "1"},
            {"matthew", "6", "33"}, {"john", "14", "6"}, {"romans", "12", "2"}, {"isaiah", "40", "31"}, {"psalms", "121", "1"},
            {"ephesians", "2", "8"}, {"1 john", "4", "19"}, {"lamentations", "3", "22"}, {"psalms", "119", "105"}, {"matthew", "5", "14"},
            {"john", "16", "33"}, {"romans", "5", "8"}, {"james", "1", "5"}, {"1 peter", "5", "7"}, {"psalms", "37", "4"}, {"micah", "6", "8"}};

    static JSONObject daily() throws Exception {
        String[] r = DAILY[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % DAILY.length];
        return read(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[2]));
    }
}
