package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The home Jarvis reads the Bible aloud to అమ్మగారు, chapter after chapter, and remembers where she stopped (the book,
 * the chapter and the part). The text is the Telugu Bible kept on the tablet (fetched slowly while online), so it reads
 * without internet too. "📖 బైబిల్ చదువు" (or her words) starts or goes on; a tap on Jarvis or "ఆపు" stops it there.
 */
final class HomeBible {
    private HomeBible() {}

    /** A chapter is read in parts of about this many letters (the bubble follows each part). */
    private static final int PART = 1100;
    /** Where reading begins the first time: John (the Gospel of John, book 43 of 66, index 42). */
    private static final int FIRST_BOOK = 42;

    static volatile boolean reading;
    private static List<String> parts = new ArrayList<>();
    private static int partAt;
    /** When the part being said was handed out: a reading left waiting far longer than a part takes (an unanswered
     *  question came in between, or the part was never said on the screen) has ended, and doesn't start up hours later. */
    private static volatile long partGivenAt;
    private static final long STALE_MS = 10 * 60_000L;

    private static SharedPreferences sp(Context c) { return HomeCare.sp(c); }
    private static int book(Context c) { return sp(c).getInt("br_book", FIRST_BOOK); }
    private static int chapter(Context c) { return sp(c).getInt("br_ch", 1); }

    /** Her words asking to read ("బైబిల్ చదువు", "బైబిల్ చదివి వినిపించు", "యోహాను సువార్త చదువు"), or not. */
    static boolean asks(String t) {
        return t.matches("(?s).*(బైబిల్|బైబిలు|సువార్త|అధ్యాయం|కీర్తన).*") && t.matches("(?s).*(చదువు|చదవండి|చదివి|వినిపించు|కొనసాగించు).*");
    }

    /** Starts reading (from the book she named, else where she stopped); background thread. Returns what to say first, or null. */
    static String start(Context c, String said) {
        String w = HomeCare.who(c);
        int b = -1, ch = 1;
        if (said != null) { // a book she named: from its first chapter (or the chapter she said)
            String t = said.toLowerCase(java.util.Locale.ROOT);
            for (String word : t.split("[\\s,.]+")) {
                if (word.isEmpty() || !Bible.isBook(word)) continue;
                b = Bible.index(word);
                if (b >= 0) break;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(వ|వది|వ అధ్యాయం|అధ్యాయం|chapter)").matcher(t);
            if (b >= 0 && m.find()) ch = Math.max(1, Integer.parseInt(m.group(1)));
        }
        if (b >= 0) sp(c).edit().putInt("br_book", b).putInt("br_ch", ch).putInt("br_part", 0).apply();
        return load(c, w, true);
    }

    /** Loads the chapter at the bookmark into parts; says the chapter's name (and, when going on, where it is). */
    private static String load(Context c, String w, boolean first) {
        int b = book(c), ch = chapter(c);
        String text;
        try {
            text = Bible.chapterText(b, ch);
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            boolean none = why.contains("not found") || why.contains("404") || (e instanceof Http.ApiError && ((Http.ApiError) e).status == 404);
            if (ch > 1 && none) { // past the last chapter: the next book
                int nb = (b + 1) % 66;
                sp(c).edit().putInt("br_book", nb).putInt("br_ch", 1).putInt("br_part", 0).apply();
                return load(c, w, first);
            }
            reading = false;
            return Net.online(c) ? w + ", ఇప్పుడు బైబిల్ తెరవలేకపోయాను. కాసేపాగి మళ్లీ అడగండి."
                    : w + ", ఈ అధ్యాయం ఇంకా టాబ్లెట్‌లో లేదు. నెట్ రాగానే చదువుతాను.";
        }
        parts = split(text);
        partAt = Math.min(sp(c).getInt("br_part", 0), Math.max(0, parts.size() - 1));
        reading = !parts.isEmpty();
        String where = Bible.nameOf(b) + " " + ch + "వ అధ్యాయం";
        if (!reading) return null;
        partGivenAt = System.currentTimeMillis();
        return (first && partAt > 0 ? w + ", " + where + " ఆపిన చోటు నుంచి చదువుతాను. " : w + ", " + where + ". ") + parts.get(partAt);
    }

    /** The next part after one was said (MainActivity, main thread): true when reading goes on. */
    static boolean next(Context c) {
        if (!reading) return false;
        if (System.currentTimeMillis() - partGivenAt > STALE_MS) { reading = false; return false; } // (📖 goes on from this part)
        partAt++;
        sp(c).edit().putInt("br_part", partAt).apply();
        if (partAt < parts.size()) {
            say(c, parts.get(partAt));
            return true;
        }
        // the chapter is done: the bookmark moves on, and she is asked
        reading = false;
        sp(c).edit().putInt("br_ch", chapter(c) + 1).putInt("br_part", 0).apply();
        String w = HomeCare.who(c);
        HomeCare.ask(c, "ఈ అధ్యాయం అయిపోయింది " + w + ". తర్వాతి అధ్యాయం చదవమంటారా?", "bible_next", "calm");
        return true;
    }

    /** She said yes to the next chapter (background thread). */
    static String goOn(Context c) { return load(c, HomeCare.who(c), false); }

    /** A tap on Jarvis or "ఆపు": stops at this part (it starts here again next time). */
    static void stop() { reading = false; }

    private static void say(Context c, String text) {
        partGivenAt = System.currentTimeMillis();
        new Thread(() -> HomeCare.sayReading(c, text), "home-bible").start();
    }

    /** A chapter in parts of ~PART letters, cut after a sentence. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text.trim();
        while (t.length() > PART) {
            int cut = -1;
            for (int i = Math.min(t.length() - 1, PART + 200); i >= PART / 2; i--) {
                char ch = t.charAt(i);
                if (ch == '.' || ch == '।' || ch == '?' || ch == '!' || ch == ';') { cut = i + 1; if (i <= PART) break; }
            }
            if (cut <= 0) { cut = t.lastIndexOf(' ', PART); if (cut <= 0) cut = PART; }
            out.add(t.substring(0, cut).trim());
            t = t.substring(cut).trim();
        }
        if (!t.isEmpty()) out.add(t);
        return out;
    }
}
