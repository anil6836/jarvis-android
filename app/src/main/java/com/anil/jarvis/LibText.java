package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📚 The library's text work that needs no phone (desk-tested): a free book's text cut into chapters, a web page made
 * plain, which rack and which time a book belongs on, how well a search hit matches the book he asked for, the AI's
 * numbered Telugu lines read back, and the rough sizes behind a cost said before any AI work.
 */
final class LibText {
    private LibText() {}

    /** Racks by subject (the page shows these names), in rack order. "mine" = books he added from the phone. */
    static final String[] SECTIONS = {"fic", "kids", "hist", "sci", "cosmos", "spirit", "poetry", "telugu", "mine"};
    /** Racks by time (the time the book takes you to), oldest first. */
    static final String[] ERAS = {"universe", "life", "ancient", "medieval", "modern", "now"};

    // ================================================================ Project Gutenberg's own lines around a book

    private static final Pattern PG_START = Pattern.compile("\\*\\*\\*\\s*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK[^*\\n]*\\*\\*\\*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_END = Pattern.compile("\\*\\*\\*\\s*END OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);

    /** The book's own text: Project Gutenberg's header and licence lines left out (kept as they are for his own reading). */
    static String stripGutenberg(String t) {
        if (t == null) return "";
        String s = t.replace("\r\n", "\n").replace('\r', '\n');
        if (s.startsWith("﻿")) s = s.substring(1);
        Matcher a = PG_START.matcher(s);
        if (a.find()) s = s.substring(a.end());
        Matcher b = PG_END.matcher(s);
        if (b.find()) s = s.substring(0, b.start());
        // (older files: "End of the Project Gutenberg EBook of ..." without stars)
        int e = s.toLowerCase(Locale.ROOT).lastIndexOf("end of the project gutenberg");
        if (e > s.length() * 0.8) s = s.substring(0, e);
        return s.trim();
    }

    /** Paragraphs: the hard-wrapped lines of a plain text joined, one paragraph a line, blank lines dropped. */
    static String unwrap(String t) {
        if (t == null) return "";
        String[] blocks = t.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n");
        StringBuilder b = new StringBuilder();
        for (String block : blocks) {
            String p = block.replaceAll("[ \\t]*\\n[ \\t]*", " ").replaceAll("[ \\t]{2,}", " ").replace('_', ' ').trim();
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(p);
        }
        return b.toString();
    }

    private static final Pattern HEAD = Pattern.compile(
            "^(?:(?:CHAPTER|Chapter|CHAP\\.|BOOK|Book|PART|Part|STAVE|Stave|ACT|Act|LETTER|Letter|CANTO|Canto|ADVENTURE|Adventure)\\s+[\\w\\-.]+[\\s\\S]{0,70}"
                    + "|[IVXLC]{1,7}\\.(?:\\s+[\\s\\S]{0,70})?"
                    + "|(?:CHAPTER|Chapter)\\s+the\\s+\\w+[\\s\\S]{0,60})$");

    /** Whether one paragraph is a chapter heading ("CHAPTER IV.", "Chapter 12. The Ball", "BOOK II", "XIV."). */
    static boolean heading(String p) {
        String s = p == null ? "" : p.trim();
        return s.length() >= 2 && s.length() <= 90 && HEAD.matcher(s).matches();
    }

    /** {title, text} chapters of a plain text: at its headings when it has some, else parts of about 12,000 letters. */
    static List<String[]> chapters(String plain) {
        String t = unwrap(plain);
        List<String[]> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        String[] ps = t.split("\\n");
        List<String[]> found = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        int heads = 0;
        for (String p : ps) {
            if (heading(p)) {
                if (title != null || body.length() > 0) found.add(new String[]{title, body.toString().trim()});
                title = p.trim();
                body.setLength(0);
                heads++;
            } else {
                if (body.length() > 0) body.append('\n');
                body.append(p);
            }
        }
        found.add(new String[]{title, body.toString().trim()});
        if (heads >= 3) {
            // a heading with (almost) nothing under it is a contents-list line: only that line is left out; a short piece
            // (a title page, a short poem) is kept by joining the next chapter, so no words are lost
            StringBuilder carry = new StringBuilder();
            for (String[] c : found) {
                if (c[1].length() < 80 && c[0] != null) continue;
                if (c[1].length() < 400) {
                    if (c[0] != null) carry.append(c[0]).append('\n');
                    if (!c[1].isEmpty()) carry.append(c[1]).append('\n');
                    continue;
                }
                out.add(new String[]{c[0] == null ? "" : c[0], carry + c[1]});
                carry.setLength(0);
            }
            if (carry.length() > 0) {
                if (out.isEmpty()) out.add(new String[]{"", carry.toString().trim()});
                else { String[] l = out.get(out.size() - 1); l[1] = l[1] + "\n" + carry.toString().trim(); }
            }
            if (out.size() >= 2) return bound(out);
            out.clear();
        }
        return bound(parts(t, ""));
    }

    /** Text cut into parts of about 12,000 letters at paragraph ends. */
    static List<String[]> parts(String t, String prefix) {
        List<String[]> out = new ArrayList<>();
        String[] ps = t.split("\\n");
        StringBuilder b = new StringBuilder();
        for (String p : ps) {
            if (b.length() > 0 && b.length() + p.length() > 12000) { out.add(new String[]{prefix, b.toString()}); b.setLength(0); }
            if (b.length() > 0) b.append('\n');
            b.append(p);
            while (b.length() > 16000) { // (one huge paragraph: cut at a space)
                int cut = b.lastIndexOf(" ", 12000);
                if (cut < 6000) cut = 12000;
                out.add(new String[]{prefix, b.substring(0, cut)});
                b.delete(0, cut + 1);
            }
        }
        if (b.length() > 0) out.add(new String[]{prefix, b.toString()});
        return out;
    }

    /** Very long chapters cut into halves, thirds…; empty titles named "భాగం N"; at most 600 chapters. */
    static List<String[]> bound(List<String[]> in) {
        List<String[]> out = new ArrayList<>();
        for (String[] c : in) {
            if (c[1].trim().isEmpty()) continue;
            if (c[1].length() <= 30000) { out.add(c); continue; }
            List<String[]> ps = parts(c[1], c[0]);
            for (int i = 0; i < ps.size(); i++) out.add(new String[]{(c[0].isEmpty() ? "" : c[0] + " ") + "(" + (i + 1) + "/" + ps.size() + ")", ps.get(i)[1]});
        }
        for (int i = 0; i < out.size(); i++) if (out.get(i)[0] == null || out.get(i)[0].trim().isEmpty()) out.get(i)[0] = "భాగం " + (i + 1);
        return out.size() > 600 ? new ArrayList<>(out.subList(0, 600)) : out;
    }

    // ================================================================ a web page / an EPUB chapter as plain text

    /** HTML → plain paragraphs (scripts, styles, tables of links, edit links and references left out). */
    static String htmlToText(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?is)<(script|style|head|noscript)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<table[^>]*(navbox|metadata|toc|ambox|infobox|noprint)[^>]*>.*?</table>", " ")
                .replaceAll("(?is)<div[^>]*(navbox|noprint|mw-editsection|catlinks|printfooter|reference)[^>]*>.*?</div>", " ")
                .replaceAll("(?is)<span[^>]*mw-editsection[^>]*>.*?</span>", " ")
                .replaceAll("(?is)<sup[^>]*>.*?</sup>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|h\\d|div|li|tr|dd|dt|blockquote|pre|center)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        t = entities(t);
        StringBuilder b = new StringBuilder();
        for (String line : t.split("\\n")) {
            String s = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (s.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(s);
        }
        return b.toString();
    }

    static String entities(String t) {
        String s = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"")
                .replace("&mdash;", " - ").replace("&ndash;", "-").replace("&hellip;", "...");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String ch;
            try { ch = new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))); } catch (Exception e) { ch = " "; }
            m.appendReplacement(b, Matcher.quoteReplacement(ch));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&");
    }

    /** The first heading in a page ("<h1>…</h1>" / "<title>"), for an EPUB chapter's name. */
    static String firstHeading(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").matcher(html);
        String h = m.find() ? m.group(1) : "";
        if (h.trim().isEmpty()) { Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html); h = t.find() ? t.group(1) : ""; }
        h = entities(h.replaceAll("(?s)<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
        return h.length() > 80 ? h.substring(0, 80) : h;
    }

    // ================================================================ which rack, which time

    /** The subject rack for a free book from its subjects / shelves (Project Gutenberg's words) and its language. */
    static String sectionOf(List<String> subjects, String lang) {
        if ("te".equals(lang)) return "telugu";
        String s = join(subjects);
        if (has(s, "juvenile", "children", "fairy tales", "nursery", "fables")) return "kids";
        if (has(s, "poetry", "poems", "drama", "plays", "epic", "verse", "tragedies", "comedies")) return "poetry";
        if (has(s, "astronomy", "cosmology", "universe", "planets", "solar system", "relativity", "geology", "earth (planet)", "science fiction", "space")) return "cosmos";
        if (has(s, "religion", "bible", "philosophy", "ethics", "buddhism", "hinduism", "islam", "christianity", "taoism", "confucian", "spiritual", "theology", "sacred")) return "spirit";
        if (has(s, "history", "biography", "autobiography", "travel", "voyages", "memoir", "war", "civilization")) return "hist";
        if (has(s, "science", "natural history", "physics", "chemistry", "biology", "evolution", "mathematics", "geometry", "medicine", "botany", "zoology", "technology")) return "sci";
        return "fic";
    }

    /** The time rack: the time the book takes you to (its subjects), else the time it was written. */
    static String eraOf(List<String> subjects, Integer year) {
        String s = join(subjects);
        if (has(s, "cosmology", "astronomy", "universe", "big bang", "stars")) return "universe";
        if (has(s, "prehistoric", "dinosaur", "fossil", "paleontology", "evolution", "geology", "origin of life")) return "life";
        if (has(s, "antiquities", "ancient", "greece", "rome --", "roman empire", "egypt --", "classical", "mythology", "vedas", "bible")) return "ancient";
        if (has(s, "middle ages", "medieval", "crusades", "knights")) return "medieval";
        return eraOfYear(year);
    }

    static String eraOfYear(Integer y) {
        if (y == null) return "modern";
        if (y < 500) return "ancient";
        if (y < 1500) return "medieval";
        if (y < 1900) return "modern";
        return "now";
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder(" ");
        if (l != null) for (String x : l) if (x != null) b.append(x.toLowerCase(Locale.ROOT)).append(" | ");
        return b.toString();
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    // ================================================================ does a search hit match the book he asked for?

    /** Words of a title, lowercased, without "the / a / of" and marks. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        // (accents off Latin letters only: "Misérables" = "miserables", "Gîtâ" = "gita"; Telugu keeps its vowel signs)
        String plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("(?<=[A-Za-z])\\p{M}+", "");
        for (String w : plain.toLowerCase(Locale.ROOT).replace("‌", "").replace("‍", "").split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.isEmpty() || w.equals("the") || w.equals("a") || w.equals("an") || w.equals("of") || w.equals("and") || w.equals("in")) continue;
            out.add(w);
        }
        return out;
    }

    /** 0..1: how many of the wanted words are in the found title / author (all the title's words = 1). */
    static double score(String wanted, String foundTitle, String foundAuthor) {
        List<String> want = words(wanted);
        if (want.isEmpty()) return 0;
        List<String> got = words(foundTitle + " " + (foundAuthor == null ? "" : foundAuthor));
        int hit = 0;
        for (String w : want) {
            for (String g : got) if (g.equals(w) || (w.length() >= 5 && g.startsWith(w.substring(0, w.length() - 1)))) { hit++; break; }
        }
        return hit / (double) want.size();
    }

    // ================================================================ the AI's numbered Telugu lines

    /** "[1] …\n[2] …" read back into n lines (index 0 = [1]); a missing / empty one stays null. */
    static String[] numbered(String reply, int n) {
        String[] out = new String[Math.max(0, n)];
        if (reply == null) return out;
        Matcher m = Pattern.compile("(?m)^\\s*\\[(\\d{1,4})\\]\\s*(.*)$").matcher(reply);
        int last = -1;
        StringBuilder more = null;
        while (m.find()) {
            if (last >= 0 && more != null) out[last] = clean(more.toString());
            int k;
            try { k = Integer.parseInt(m.group(1)) - 1; } catch (Exception e) { k = -1; }
            if (k < 0 || k >= n) { last = -1; more = null; continue; }
            last = k;
            more = new StringBuilder(m.group(2));
            // (a line that runs on below its number joins it)
            int end = m.end();
            int next = reply.indexOf("\n[", end);
            String tail = next < 0 ? reply.substring(end) : reply.substring(end, next);
            if (!tail.trim().isEmpty()) more.append(' ').append(tail.trim());
        }
        if (last >= 0 && more != null) out[last] = clean(more.toString());
        return out;
    }

    private static String clean(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** The numbered request: "[1] …" a line each (line breaks inside a part made spaces). */
    static String numberedRequest(List<String> parts, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) b.append('[').append(from + i + 1).append("] ").append(parts.get(i).replaceAll("\\s+", " ").trim()).append('\n');
        return b.toString();
    }

    // ================================================================ sizes for a cost said first (approximate)

    /** Tokens sent for English text of that many letters (with the instructions). */
    static long tokensIn(long chars) { return Math.round(chars / 3.8) + 350; }

    /** Tokens written back for its Telugu (Telugu takes more tokens than English: about 0.8 a letter of the English). */
    static long tokensOutTelugu(long chars) { return Math.round(chars * 0.8) + 40; }

    // ================================================================ small checks

    /** A book id we make: letters, digits and - only, ≤ 60. */
    static boolean goodId(String id) { return id != null && id.matches("[a-z0-9][a-z0-9\\-]{0,59}"); }

    /** A short safe id from any text (a Wikisource title, an Open Library key). */
    static String idFrom(String prefix, String any) {
        String s = any == null ? "" : any;
        String ascii = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (ascii.length() > 24) ascii = ascii.substring(0, 24).replaceAll("-+$", "");
        String h = Long.toString(s.hashCode() & 0xffffffffL, 36);
        return prefix + "-" + (ascii.isEmpty() ? "" : ascii + "-") + h;
    }

    /** A year as words for the card: "క్రీ.పూ. 430" / "1813". */
    static String yearSaid(Integer y) {
        if (y == null) return "";
        return y < 0 ? "క్రీ.పూ. " + (-y) : String.valueOf(y);
    }

    /** Gutendex's book object → our card fields (title, author, year guess, subjects, section, era, cover, text links). */
    static JSONObject fromGutendex(JSONObject g) throws Exception {
        JSONObject o = new JSONObject();
        int id = g.optInt("id", 0);
        o.put("id", "g" + id).put("src", "pg").put("src_id", String.valueOf(id)).put("t", g.optString("title", "").replaceAll("\\s+", " ").trim());
        JSONArray au = g.optJSONArray("authors");
        Integer year = null;
        if (au != null && au.length() > 0) {
            JSONObject a = au.getJSONObject(0);
            o.put("a", flipName(a.optString("name", "")));
            if (!a.isNull("death_year")) { int d = a.optInt("death_year"); year = d - 10; }
            else if (!a.isNull("birth_year")) { int b = a.optInt("birth_year"); year = b + 40; }
        } else o.put("a", "");
        List<String> subj = new ArrayList<>();
        JSONArray s = g.optJSONArray("subjects"), sh = g.optJSONArray("bookshelves");
        for (int i = 0; s != null && i < s.length(); i++) subj.add(s.optString(i));
        for (int i = 0; sh != null && i < sh.length(); i++) subj.add(sh.optString(i));
        JSONArray langs = g.optJSONArray("languages");
        String lang = langs != null && langs.length() > 0 ? langs.optString(0, "en") : "en";
        o.put("lang", lang).put("sec", sectionOf(subj, lang)).put("era", eraOf(subj, year));
        if (year != null) o.put("yw", year); // (when it was written, roughly: from the author's years)
        JSONObject f = g.optJSONObject("formats");
        if (f != null) {
            for (java.util.Iterator<String> it = f.keys(); it.hasNext(); ) {
                String k = it.next(), v = f.optString(k, "");
                String low = k.toLowerCase(Locale.ROOT);
                if (low.startsWith("text/plain") && (low.contains("utf-8") || !o.has("txt"))) o.put("txt", v);
                else if (low.startsWith("application/epub")) o.put("epub", v);
                else if (low.startsWith("image/jpeg")) o.put("cover_url", v);
            }
        }
        JSONArray sum = g.optJSONArray("summaries");
        if (sum != null && sum.length() > 0) o.put("summary", cut(sum.optString(0, ""), 600));
        o.put("free", true).put("downloads", g.optInt("download_count", 0));
        return o;
    }

    /** Project Gutenberg's own search feed (OPDS / Atom) → book entries with the standard text and cover links. */
    static List<JSONObject> fromOpds(String xml) {
        List<JSONObject> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher m = Pattern.compile("(?s)<entry>(.*?)</entry>").matcher(xml);
        while (m.find() && out.size() < 25) {
            String e = m.group(1);
            Matcher id = Pattern.compile("/ebooks/(\\d{1,6})").matcher(e);
            Matcher t = Pattern.compile("(?s)<title[^>]*>(.*?)</title>").matcher(e);
            if (!id.find() || !t.find()) continue;
            String n = id.group(1);
            Matcher au = Pattern.compile("(?s)<content[^>]*>(.*?)</content>").matcher(e);
            try {
                JSONObject o = new JSONObject().put("id", "g" + n).put("src", "pg").put("src_id", n).put("t", entities(t.group(1)).replaceAll("\\s+", " ").trim())
                        .put("a", au.find() ? flipName(entities(au.group(1)).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()) : "")
                        .put("lang", "en").put("sec", "fic").put("era", "modern").put("free", true)
                        .put("txt", "https://www.gutenberg.org/ebooks/" + n + ".txt.utf-8")
                        .put("epub", "https://www.gutenberg.org/ebooks/" + n + ".epub3.images")
                        .put("cover_url", "https://www.gutenberg.org/cache/epub/" + n + "/pg" + n + ".cover.medium.jpg");
                out.add(o);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Project Gutenberg's standard addresses for book number n (plain text, its second address, EPUB, cover). */
    static JSONObject pgLinks(String n) throws Exception {
        return new JSONObject().put("id", "g" + n).put("src", "pg").put("src_id", n).put("free", true)
                .put("txt", "https://www.gutenberg.org/cache/epub/" + n + "/pg" + n + ".txt")
                .put("txt2", "https://www.gutenberg.org/ebooks/" + n + ".txt.utf-8")
                .put("epub", "https://www.gutenberg.org/ebooks/" + n + ".epub3.images")
                .put("cover_url", "https://www.gutenberg.org/cache/epub/" + n + "/pg" + n + ".cover.medium.jpg");
    }

    /** Project Gutenberg's foundation (PGLAF) keeps the same files on a second site: used when www.gutenberg.org is down. */
    static final String PG_MIRROR = "https://gutenberg.pglaf.org";

    /** The same Project Gutenberg file on its second site (gutenberg.pglaf.org/cache/epub/<n>/…), or "" (not a Gutenberg file). */
    static String pgMirror(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("^https?://(?:www\\.)?gutenberg\\.org(/[^?#\\s]*)$").matcher(url.trim());
        if (!m.find()) return "";
        String path = m.group(1);
        Matcher x = Pattern.compile("^/cache/epub/(\\d{1,6})/(pg\\d{1,6}[A-Za-z0-9._\\-]*)$").matcher(path);
        if (x.find()) return PG_MIRROR + "/cache/epub/" + x.group(1) + "/" + x.group(2);
        String n;
        if ((n = num(path, "^/ebooks/(\\d{1,6})\\.txt(?:\\.utf-8)?$")) != null) return pgCache(n, ".txt");
        if ((n = num(path, "^/files/(\\d{1,6})/[A-Za-z0-9._\\-]+\\.txt$")) != null) return pgCache(n, ".txt");
        if ((n = num(path, "^/ebooks/(\\d{1,6})\\.epub3\\.images$")) != null) return pgCache(n, "-images-3.epub");
        if ((n = num(path, "^/ebooks/(\\d{1,6})\\.epub\\.images$")) != null) return pgCache(n, "-images.epub");
        if ((n = num(path, "^/ebooks/(\\d{1,6})\\.epub(?:\\.noimages)?$")) != null) return pgCache(n, ".epub");
        return "";
    }

    private static String num(String path, String re) { Matcher x = Pattern.compile(re).matcher(path); return x.find() ? x.group(1) : null; }

    private static String pgCache(String n, String end) { return PG_MIRROR + "/cache/epub/" + n + "/pg" + n + end; }

    static boolean isPgMirror(String url) { return url != null && url.startsWith(PG_MIRROR + "/"); }

    static boolean isPgMain(String url) { return url != null && url.matches("^https://(?:www\\.)?gutenberg\\.org/.*"); }

    /** The addresses to try for one file, in order: its own address, the same file on the second site, then the other address and its copy. */
    static List<String> pgAddresses(String a, String b) {
        List<String> out = new ArrayList<>();
        for (String u : new String[]{a, b}) {
            if (u == null || u.trim().isEmpty()) continue;
            u = u.trim();
            if (u.startsWith("http://") && pgMirror(u).length() > 0) u = "https://" + u.substring(7); // (Gutenberg's own links, always https here)
            if (!out.contains(u)) out.add(u);
            String mm = pgMirror(u);
            if (!mm.isEmpty() && !out.contains(mm)) out.add(mm);
        }
        return out;
    }

    /** The book's own title in a Project Gutenberg file ("Title: …", or its first line "The Project Gutenberg eBook of …"). */
    static String pgTitle(String text) {
        if (text == null) return "";
        String head = text.length() > 6000 ? text.substring(0, 6000) : text;
        Matcher m = Pattern.compile("(?m)^\\s*Title:\\s*(.+)$").matcher(head);
        if (m.find()) {
            StringBuilder t = new StringBuilder(m.group(1).trim());
            // (a long title goes on, indented, on the next line)
            Matcher more = Pattern.compile("\\G\\r?\\n[ \\t]{2,}(\\S.*)").matcher(head);
            int at = m.end();
            while (more.find(at) && more.start() == at) { t.append(' ').append(more.group(1).trim()); at = more.end(); }
            return t.toString();
        }
        Matcher e = Pattern.compile("(?i)The Project Gutenberg e-?book,? of (.+?)(?:,? by |\\r?\\n)").matcher(head);
        return e.find() ? e.group(1).trim() : "";
    }

    /** Is the downloaded book the one wanted? (Most of the wanted title's words are in its own title.) */
    static boolean sameBook(String wanted, String own) {
        return score(wanted, own, "") >= 0.5 || score(own, wanted, "") >= 0.6;
    }

    /** "Austen, Jane" → "Jane Austen". */
    static String flipName(String n) {
        String s = n == null ? "" : n.replaceAll("\\s*\\(.*?\\)", "").trim();
        int c = s.indexOf(", ");
        if (c > 0 && s.indexOf(", ", c + 2) < 0) s = s.substring(c + 2) + " " + s.substring(0, c);
        return s.trim();
    }

    /** An Open Library search hit → our card fields; marks: free (a free text we can fetch), web (read on their site), old / buy. */
    static JSONObject fromOpenLibrary(JSONObject d) throws Exception {
        JSONObject o = new JSONObject();
        String key = d.optString("key", "");
        o.put("id", idFrom("ol", key)).put("ol", key).put("t", d.optString("title", "").trim());
        JSONArray an = d.optJSONArray("author_name");
        o.put("a", an != null && an.length() > 0 ? an.optString(0, "") : "");
        Integer year = d.has("first_publish_year") ? d.optInt("first_publish_year") : null;
        if (year != null) o.put("y", year);
        if (d.has("cover_i")) o.put("cover_url", "https://covers.openlibrary.org/b/id/" + d.optLong("cover_i") + "-M.jpg");
        JSONArray pg = d.optJSONArray("id_project_gutenberg"), se = d.optJSONArray("id_standard_ebooks");
        String access = d.optString("ebook_access", "");
        if (pg != null && pg.length() > 0 && pg.optString(0, "").matches("\\d{1,6}")) o.put("src", "pg").put("src_id", pg.optString(0)).put("free", true);
        else if (se != null && se.length() > 0 && se.optString(0, "").matches("[a-z0-9\\-]+/[a-z0-9\\-/]+")) o.put("src", "se").put("src_id", se.optString(0)).put("free", true);
        else if ("public".equals(access)) o.put("mark", "web");
        else if ("borrowable".equals(access) || "printdisabled".equals(access)) o.put("mark", "borrow");
        else o.put("mark", year != null && year >= 1930 ? "buy" : "info");
        JSONArray subj = d.optJSONArray("subject");
        List<String> sl = new ArrayList<>();
        for (int i = 0; subj != null && i < subj.length() && i < 30; i++) sl.add(subj.optString(i));
        JSONArray lg = d.optJSONArray("language");
        String lang = lg != null && lg.length() > 0 ? ("tel".equals(lg.optString(0)) ? "te" : "eng".equals(lg.optString(0)) ? "en" : lg.optString(0)) : "en";
        o.put("lang", lang).put("sec", sectionOf(sl, lang)).put("era", eraOf(sl, year));
        return o;
    }

    static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    /** %XX decoded as UTF-8 (a '+' stays a '+'); bad escapes left as they are. */
    static String pct(String s) {
        if (s == null || s.indexOf('%') < 0) return s == null ? "" : s;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                try { b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 3; continue; } catch (Exception ignored) {}
            }
            int cp = s.codePointAt(i);
            byte[] by = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            b.write(by, 0, by.length);
            i += Character.charCount(cp);
        }
        return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A Wikisource book page's sub-pages ("Title/…"), in the order the page links them, ≤ 150. */
    static List<String> subpages(String html, String title) {
        List<String> subs = new ArrayList<>();
        if (html == null || title == null) return subs;
        Matcher m = Pattern.compile("href=\"/wiki/([^\"#?]+)(?:[#?][^\"]*)?\"").matcher(html);
        String prefix = title.replace(' ', '_') + "/";
        while (m.find() && subs.size() < 150) {
            String t = pct(m.group(1));
            if (t.startsWith(prefix) && t.length() > prefix.length() && !subs.contains(t)) subs.add(t);
        }
        return subs;
    }

    // ================================================================ 💬 the book's own words for a question (₹0: found on the phone)

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "and", "for", "that", "with", "this", "from", "was", "were", "are", "his", "her", "him", "she", "they", "them", "what",
            "who", "whom", "why", "how", "when", "where", "which", "did", "does", "has", "have", "had", "not", "but", "you", "your",
            "into", "about", "there", "their", "then", "than", "been", "being", "will", "would", "could", "should", "book", "story"));

    /** Search words of a question (English letters, 3+, no common words), lower case, each once. */
    static List<String> searchWords(String q) {
        List<String> out = new ArrayList<>();
        if (q == null) return out;
        Matcher m = Pattern.compile("[A-Za-z][A-Za-z'\\-]{2,}").matcher(q);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT).replaceAll("'s$", "");
            if (w.length() >= 3 && !STOP.contains(w) && !out.contains(w)) out.add(w);
        }
        return out;
    }

    /**
     * The parts of the book that best answer these words (chapters cut into pieces of about {@code size} letters, each piece
     * scored by the words it holds, rarer words weighing more), up to {@code maxChars} in book order, each with
     * "[అధ్యాయం N]" before it. "" when no word is in the book.
     */
    static String topPassages(List<String> chapters, List<String> words, int size, int maxChars) {
        if (chapters == null || words == null || words.isEmpty()) return "";
        List<int[]> at = new ArrayList<>(); // {unit, start, end}
        for (int u = 0; u < chapters.size(); u++) {
            String t = chapters.get(u) == null ? "" : chapters.get(u);
            int i = 0;
            while (i < t.length()) {
                int e = Math.min(t.length(), i + size);
                if (e < t.length()) { int dot = t.lastIndexOf(". ", e); if (dot > i + size / 2) e = dot + 1; }
                at.add(new int[]{u, i, e});
                i = e;
            }
        }
        int n = at.size();
        double[] score = new double[n];
        for (String w : words) {
            Pattern pw = Pattern.compile("\\b" + Pattern.quote(w), Pattern.CASE_INSENSITIVE);
            int[] tf = new int[n];
            int df = 0;
            for (int k = 0; k < n; k++) {
                int[] a = at.get(k);
                Matcher m = pw.matcher(chapters.get(a[0]).substring(a[1], a[2]));
                while (m.find()) tf[k]++;
                if (tf[k] > 0) df++;
            }
            if (df == 0) continue;
            double idf = Math.log(1 + (double) n / df);
            for (int k = 0; k < n; k++) if (tf[k] > 0) score[k] += idf * (1 + Math.log(tf[k]));
        }
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(score[y], score[x]));
        List<Integer> pick = new ArrayList<>();
        int used = 0;
        for (Integer k : order) {
            if (score[k] <= 0) break;
            int len = at.get(k)[2] - at.get(k)[1];
            if (used + len > maxChars && !pick.isEmpty()) continue;
            pick.add(k);
            used += len;
            if (used >= maxChars) break;
        }
        if (pick.isEmpty()) return "";
        java.util.Collections.sort(pick);
        StringBuilder b = new StringBuilder();
        for (int k : pick) {
            int[] a = at.get(k);
            b.append("[అధ్యాయం ").append(a[0] + 1).append("]\n").append(chapters.get(a[0]).substring(a[1], a[2]).trim()).append("\n\n");
        }
        return b.toString().trim();
    }

    // ================================================================ the AI's answers in a form the page can use

    /** The first whole {...} in the AI's answer (it may say a word before or after), or null. */
    static JSONObject jsonBlock(String s) {
        if (s == null) return null;
        int i = s.indexOf('{');
        while (i >= 0) {
            int depth = 0;
            boolean str = false, esc = false;
            for (int k = i; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (str) { if (esc) esc = false; else if (ch == '\\') esc = true; else if (ch == '"') str = false; continue; }
                if (ch == '"') str = true;
                else if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) {
                    try { return new JSONObject(s.substring(i, k + 1)); } catch (Exception e) { break; }
                }
            }
            i = s.indexOf('{', i + 1);
        }
        return null;
    }

    /** ❓ The quiz the AI wrote: up to 5 questions, each with 4 choices, the right one's number and why; bad ones left out. */
    static JSONArray quiz(String ai) {
        JSONArray out = new JSONArray();
        JSONObject j = jsonBlock(ai);
        JSONArray q = j == null ? null : j.optJSONArray("q");
        for (int i = 0; q != null && i < q.length() && out.length() < 5; i++) {
            JSONObject x = q.optJSONObject(i);
            if (x == null) continue;
            JSONArray o = x.optJSONArray("o");
            int a = x.optInt("a", -1);
            String qq = x.optString("q", "").trim();
            if (qq.isEmpty() || o == null || o.length() < 2 || o.length() > 5 || a < 0 || a >= o.length()) continue;
            try {
                JSONArray oo = new JSONArray();
                for (int k = 0; k < o.length(); k++) oo.put(cut(o.optString(k, "").trim(), 200));
                out.put(new JSONObject().put("q", cut(qq, 400)).put("o", oo).put("a", a).put("why", cut(x.optString("why", "").trim(), 500)));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** A name as one key ("Mr. Sherlock Holmes" = "sherlock holmes"). */
    static String nameKey(String n) {
        if (n == null) return "";
        String k = n.toLowerCase(Locale.ROOT).replaceAll("\\b(mr|mrs|miss|ms|dr|sir|lady|lord|captain|colonel|professor)\\.?\\s+", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        return k;
    }

    /**
     * 🗺️ The chapters' characters as one map: each person once (by name; a shorter name of the same person joins the
     * longer, "Holmes" → "Sherlock Holmes"), the chapters they are in, how they are linked, and each chapter's event.
     * Input: one {u, c:[{n, te, w}], r:[[a, b, how]], e} per chapter.
     */
    static JSONObject mergeMap(List<JSONObject> chapters) {
        java.util.LinkedHashMap<String, JSONObject> people = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, JSONArray> links = new java.util.LinkedHashMap<>();
        JSONArray events = new JSONArray();
        List<JSONArray> raw = new ArrayList<>();
        try {
            for (JSONObject ch : chapters) {
                if (ch == null) continue;
                int u = ch.optInt("u", 0);
                JSONArray c = ch.optJSONArray("c");
                for (int i = 0; c != null && i < c.length(); i++) {
                    JSONObject x = c.optJSONObject(i);
                    if (x == null) continue;
                    String key = nameKey(x.optString("n"));
                    if (key.isEmpty()) continue;
                    String same = sameAs(people, key);
                    if (same != null && key.length() > same.length()) { // (the longer name wins: the entry moves to it)
                        JSONObject old = people.remove(same);
                        old.put("n", x.optString("n").trim());
                        people.put(key, old);
                        same = key;
                    }
                    JSONObject p = same != null ? people.get(same) : null;
                    if (p == null) {
                        p = new JSONObject().put("n", cut(x.optString("n").trim(), 80)).put("te", cut(x.optString("te").trim(), 80))
                                .put("w", cut(x.optString("w").trim(), 200)).put("u", new JSONArray());
                        people.put(key, p);
                    }
                    if (p.optString("te").isEmpty()) p.put("te", cut(x.optString("te").trim(), 80));
                    if (p.optString("w").isEmpty()) p.put("w", cut(x.optString("w").trim(), 200));
                    JSONArray us = p.getJSONArray("u");
                    boolean has = false;
                    for (int k = 0; k < us.length(); k++) if (us.optInt(k) == u) has = true;
                    if (!has) us.put(u);
                }
                JSONArray r = ch.optJSONArray("r");
                for (int i = 0; r != null && i < r.length(); i++) { JSONArray x = r.optJSONArray(i); if (x != null && x.length() >= 3) raw.add(x); }
                String e = ch.optString("e", "").trim();
                if (!e.isEmpty()) events.put(new JSONObject().put("u", u).put("e", cut(e, 300)));
            }
            // (links after everyone is known: a person's name may have grown in a later chapter)
            for (JSONArray x : raw) {
                String a = sameAs(people, nameKey(x.optString(0))), b = sameAs(people, nameKey(x.optString(1)));
                if (a == null || b == null || a.equals(b)) continue;
                String lk = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                if (!links.containsKey(lk)) links.put(lk, new JSONArray().put(people.get(a).optString("n")).put(people.get(b).optString("n")).put(cut(x.optString(2).trim(), 60)));
            }
            JSONArray cs = new JSONArray(), rs = new JSONArray();
            for (JSONObject p : people.values()) cs.put(p);
            for (JSONArray l : links.values()) rs.put(l);
            return new JSONObject().put("c", cs).put("r", rs).put("e", events);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The key of the same person already there: the same name, or one name inside the other as whole words. */
    private static String sameAs(java.util.Map<String, JSONObject> people, String key) {
        if (key.isEmpty()) return null;
        if (people.containsKey(key)) return key;
        for (String k : people.keySet()) {
            if ((" " + k + " ").contains(" " + key + " ") || (" " + key + " ").contains(" " + k + " ")) return k;
        }
        return null;
    }

    // ================================================================ 🎧 the phone voice's sound files

    /** A WAV file's {sample rate, channels, bits, where the sound starts, its length}; null when it isn't plain PCM WAV. */
    static int[] wavInfo(byte[] b) {
        if (b == null || b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F' || b[8] != 'W' || b[9] != 'A') return null;
        int i = 12, rate = 0, ch = 0, bits = 0;
        while (i + 8 <= b.length) {
            String id = new String(b, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int len = (b[i + 4] & 0xFF) | (b[i + 5] & 0xFF) << 8 | (b[i + 6] & 0xFF) << 16 | (b[i + 7] & 0xFF) << 24;
            if (id.equals("fmt ") && i + 24 <= b.length) {
                int fmt = (b[i + 8] & 0xFF) | (b[i + 9] & 0xFF) << 8;
                if (fmt != 1) return null;
                ch = (b[i + 10] & 0xFF) | (b[i + 11] & 0xFF) << 8;
                rate = (b[i + 12] & 0xFF) | (b[i + 13] & 0xFF) << 8 | (b[i + 14] & 0xFF) << 16 | (b[i + 15] & 0xFF) << 24;
                bits = (b[i + 22] & 0xFF) | (b[i + 23] & 0xFF) << 8;
            } else if (id.equals("data")) {
                int start = i + 8, n = len < 0 || start + len > b.length ? b.length - start : len;
                return rate > 0 && ch > 0 && (bits == 16 || bits == 8) ? new int[]{rate, ch, bits, start, n} : null;
            }
            i += 8 + Math.max(0, len) + (len & 1);
        }
        return null;
    }

    /** The sound as one channel of 16-bit samples, at the rate wanted (a simple straight-line resampling). */
    static short[] mono16(byte[] b, int[] info, int toRate) {
        int ch = info[1], bits = info[2], start = info[3], n = info[4];
        int frame = ch * bits / 8, frames = frame == 0 ? 0 : n / frame;
        short[] mono = new short[frames];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = start + f * frame + c * bits / 8;
                sum += bits == 16 ? (short) ((b[o] & 0xFF) | b[o + 1] << 8) : ((b[o] & 0xFF) - 128) << 8;
            }
            mono[f] = (short) (sum / ch);
        }
        int from = info[0];
        if (from == toRate || frames == 0) return mono;
        int outN = (int) ((long) frames * toRate / from);
        short[] out = new short[outN];
        for (int k = 0; k < outN; k++) {
            double x = (double) k * from / toRate;
            int a = (int) x;
            double t = x - a;
            int s0 = mono[Math.min(a, frames - 1)], s1 = mono[Math.min(a + 1, frames - 1)];
            out[k] = (short) Math.round(s0 + (s1 - s0) * t);
        }
        return out;
    }

    // ================================================================ 📊 days

    /** Days in a row (up to today, or up to yesterday when today has nothing yet) with at least a minute of reading. */
    static int streak(JSONObject days, java.util.Calendar today) {
        if (days == null) return 0;
        java.util.Calendar d = (java.util.Calendar) today.clone();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        if (days.optDouble(f.format(d.getTime()), 0) < 60) d.add(java.util.Calendar.DAY_OF_MONTH, -1);
        int n = 0;
        while (days.optDouble(f.format(d.getTime()), 0) >= 60 && n < 3650) { n++; d.add(java.util.Calendar.DAY_OF_MONTH, -1); }
        return n;
    }

    // ================================================================ 📜 more free shelves: Wikisource scans (సూచిక / పుట), Internet Archive

    /** One Wikisource page's wikitext → plain text (header / footer, references, templates' markup and link brackets left out). */
    static String wikiText(String w) {
        if (w == null) return "";
        String t = w.replaceAll("(?is)<noinclude>.*?</noinclude>", " ")
                .replaceAll("(?is)<ref[^>]*/>", "").replaceAll("(?is)<ref[^>]*>.*?</ref>", "")
                .replaceAll("(?s)<!--.*?-->", "");
        // templates: the innermost first; a few that only wrap words keep their words ({{center|…}}, {{larger|…}}, {{bold|…}})
        for (int k = 0; k < 10 && t.contains("{{"); k++) {
            String n = t.replaceAll("(?i)\\{\\{\\s*(?:c|center|centre|larger|large|x-larger|x-large|smaller|small|bold|b|italic|i|u|underline|big|font|nop|right|left|block center)\\s*\\|([^{}|]*)(?:\\|[^{}]*)?\\}\\}", "$1")
                    .replaceAll("\\{\\{[^{}]*\\}\\}", " ");
            if (n.equals(t)) break;
            t = n;
        }
        t = t.replaceAll("(?i)\\[\\[\\s*(?:file|image|దస్త్రం|బొమ్మ|category|వర్గం)\\s*:[^\\]]*\\]\\]", " ")
                .replaceAll("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]", "$1")
                .replaceAll("\\[https?://\\S+\\s+([^\\]]*)\\]", "$1")
                .replaceAll("'{2,5}", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?(?:div|p|center|poem|section|table|tr|td|th|blockquote)\\b[^>]*>", "\n")
                .replaceAll("(?i)</?(?:span|big|small|font|sup|sub|b|i|u|s|em|strong)\\b[^>]*>", "")
                .replaceAll("(?m)^\\s*\\{\\|.*$|^\\s*\\|[-}].*$", "")
                .replaceAll("(?m)^\\s*(=+)\\s*(.*?)\\s*\\1\\s*$", "$2")
                .replaceAll("(?m)^[:;*#]+\\s*", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    /** A scan's సూచిక (Index) page wikitext → {t, a, y} from its Title / Author / Year fields (Telugu names too); empty when missing. */
    static JSONObject indexMeta(String w) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", field(w, "Title|శీర్షిక|పేరు")).put("a", field(w, "Author|రచయిత|కర్త|రచయితలు"));
            String y = field(w, "Year|సంవత్సరం|ప్రచురణ సంవత్సరం");
            Matcher m = Pattern.compile("(1[5-9]\\d\\d|20\\d\\d)").matcher(y);
            if (m.find()) o.put("y", Integer.parseInt(m.group(1)));
        } catch (Exception ignored) {}
        return o;
    }

    private static String field(String w, String names) {
        if (w == null) return "";
        Matcher m = Pattern.compile("(?im)^\\s*\\|\\s*(?:" + names + ")\\s*=\\s*(.*)$").matcher(w);
        if (!m.find()) return "";
        return cut(wikiText(m.group(1)).replaceAll("\\s+", " ").trim(), 120);
    }

    /** A scan file's name as a book name when its index says nothing ("Kanyashulkamu020647mbp.pdf" → "Kanyashulkamu"). */
    static String fileTitle(String file) {
        String s = file == null ? "" : file.replaceAll("^[^:]*:", "");
        s = s.replaceAll("(?i)\\.(pdf|djvu|tiff?)$", "").replace('_', ' ');
        s = s.replaceAll("(?i)\\d{4,}mbp$", "").replaceAll("(?i)^in\\.ernet\\.dli\\.\\d+\\.\\d+$", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? (file == null ? "" : file) : s;
    }

    /** How clean a scan's text is, 0–1: letters, digits, spaces and plain punctuation out of all; Telugu books also need Telugu letters. */
    static double textQuality(String s, boolean telugu) {
        if (s == null || s.length() < 200) return 0;
        int good = 0, letters = 0, te = 0, n = 0;
        for (int i = 0; i < s.length() && n < 200_000; i++, n++) {
            char c = s.charAt(i);
            boolean t = c >= 0x0C00 && c <= 0x0C7F;
            boolean l = Character.isLetter(c) || t;
            if (l) { letters++; if (t) te++; }
            if (l || Character.isDigit(c) || Character.isWhitespace(c) || ".,;:!?'\"()-–—".indexOf(c) >= 0) good++;
        }
        double q = (double) good / Math.max(1, n);
        if (telugu) q = Math.min(q, letters == 0 ? 0 : (double) te / letters + 0.15);
        return Math.max(0, Math.min(1, q));
    }

    /** Internet Archive: only a book its uploader marked free to share (a Creative Commons licence or the public-domain mark). */
    static boolean iaFree(String licenseUrl) {
        String l = licenseUrl == null ? "" : licenseUrl.toLowerCase(Locale.ROOT);
        return l.contains("creativecommons.org") || l.contains("publicdomain");
    }

    /** Internet Archive item metadata → the file to read: its plain text ("…_djvu.txt"), else an EPUB, else a .txt; null when none. */
    static String[] iaFile(JSONObject meta) {
        JSONArray files = meta == null ? null : meta.optJSONArray("files");
        String txt = null, epub = null, other = null;
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String n = f.optString("name", ""), fmt = f.optString("format", ""), low = n.toLowerCase(Locale.ROOT);
            if (low.endsWith("_djvu.txt") || "DjVuTXT".equals(fmt)) { if (txt == null) txt = n; }
            else if (low.endsWith(".epub") && !low.contains("_lcp")) { if (epub == null) epub = n; }
            else if (low.endsWith(".txt") && !low.endsWith("_meta.txt") && !low.contains("_page_numbers") && other == null) other = n;
        }
        if (txt != null) return new String[]{txt, "txt"};
        if (epub != null) return new String[]{epub, "epub"};
        if (other != null) return new String[]{other, "txt"};
        return null;
    }

    /** A scan's pages as chapters: every `per` pages one "పుటలు a–b" part (empty pages skipped). */
    static List<String[]> pageParts(List<String> pages, int per) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i += per) {
            StringBuilder b = new StringBuilder();
            int to = Math.min(pages.size(), i + per);
            for (int k = i; k < to; k++) { String t = pages.get(k); if (t != null && !t.trim().isEmpty()) { if (b.length() > 0) b.append("\n\n"); b.append(t.trim()); } }
            if (b.length() > 0) out.add(new String[]{"పుటలు " + (i + 1) + "–" + to, b.toString()});
        }
        return bound(out);
    }
}
