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
}
