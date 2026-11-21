package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📚 Shelves by subject (Anil, 22 Nov: "A చేయి" — one subject shelf holds every free site's books, Telugu first;
 * the shelves by site stay too). Pure: which words each site is asked with, and reading the sites' answers.
 * Only free, legal sources: Telugu / English Wikisource, Project Gutenberg, Internet Archive items marked free by
 * their uploader, Standard Ebooks, and teluguthesis.com's posts (only their archive.org copies, licence checked).
 */
final class LibShelves {
    private LibShelves() {}

    /** One subject: its key and, per site, how it is asked for (null / empty: that site has none). */
    static final class Cat {
        final String key;
        String[] ws;     // Wikisource category words (found at run time: categories whose name holds one)
        String pg;       // Gutendex "topic" (bookshelves / subjects), "" = every book of the language
        String[] ia;     // Internet Archive subject / title words (Telugu only)
        String[] tt;     // teluguthesis.com labels
        String se;       // Standard Ebooks subject ("" = every book), English only
        String[] wsLinks; // Wikisource pages whose links are the shelf (featured lists)
        String km;        // Koumudi library section (its PDFs), Telugu only
        Cat(String key) { this.key = key; }
        Cat km(String section) { km = section; return this; }
        Cat ws(String... w) { ws = w; return this; }
        Cat pg(String t) { pg = t; return this; }
        Cat ia(String... w) { ia = w; return this; }
        Cat tt(String... w) { tt = w; return this; }
        Cat se(String t) { se = t; return this; }
        Cat links(String... p) { wsLinks = p; return this; }
    }

    static final Cat[] TE = {
        new Cat("novel").ws("నవలలు", "నవల").pg("fiction").ia("నవల", "నవలలు", "novel", "novels", "fiction").tt("Novel", "నవల").km("novel"),
        new Cat("story").ws("కథలు", "కథా").pg("stories").ia("కథలు", "కథ", "కథానికలు", "stories", "katha", "kathalu").tt("కథలు", "Stories", "Telugu Story", "కథానిక"),
        new Cat("poetry").ws("శతకములు", "శతకాలు", "కావ్యములు", "కవిత్వం", "పద్యాలు").pg("poetry")
                .ia("శతకము", "శతకం", "పద్యాలు", "కవిత్వం", "కవితలు", "poetry", "satakam").tt("Shatakam", "Kavya-Prabandham", "ప్రబంధం", "కావ్యం", "Adhunika Kavyam"),
        new Cat("bhakti").ws("భక్తి", "పురాణములు", "పురాణాలు", "స్తోత్రములు", "రామాయణము", "భాగవతము")
                .ia("భక్తి", "రామాయణం", "రామాయణము", "భాగవతం", "భాగవతము", "భారతం", "పురాణం", "స్తోత్రం", "bhakti", "ramayanam", "bhagavatam", "puranam")
                .tt("Bhakthi", "భక్తి", "Puranam", "రామాయణం", "భాగవతం", "Stotra"),
        new Cat("history").ws("చరిత్ర").pg("history").ia("చరిత్ర", "చరిత్రము", "history", "charitra").tt("History", "చరిత్ర", "Research in History"),
        new Cat("bio").ws("జీవిత చరిత్రలు", "జీవితచరిత్రలు", "ఆత్మకథలు").pg("biography").ia("జీవిత చరిత్ర", "జీవితచరిత్ర", "ఆత్మకథ", "biography", "autobiography"),
        new Cat("kids").ws("బాల సాహిత్యం", "పిల్లల").pg("children").ia("పిల్లల", "బాలల", "చందమామ", "children", "chandamama").tt("బాలానందం", "Chandamama", "చందమామ పత్రికలు", "Bala bodhini"),
        new Cat("drama").ws("నాటకములు", "నాటకాలు").pg("drama").ia("నాటకం", "నాటకము", "నాటకాలు", "drama", "natakam").tt("Drama", "నాటకం", "Natakam"),
        new Cat("science").ws("విజ్ఞాన శాస్త్రము", "వైద్యము", "ఆయుర్వేదము").pg("science").ia("విజ్ఞాన", "శాస్త్రం", "వైద్యం", "ఆయుర్వేదం", "ఆరోగ్యం", "science", "health", "ayurveda"),
        new Cat("classic").links("వికీసోర్స్:విశేష గ్రంథాలు", "వేదిక:ఆంధ్ర గ్రంథమాల").tt("Telugu Classic literature"),
        new Cat("lang").ws("వ్యాకరణము", "నిఘంటువులు", "భాషాశాస్త్రము").ia("వ్యాకరణం", "వ్యాకరణము", "నిఘంటువు", "grammar", "dictionary")
                .tt("Grammar", "వ్యాకరణం", "Dictionary", "నిఘంటువు", "Linguistics"),
        new Cat("thesis").ia("thesis", "సిద్ధాంత గ్రంథం").tt("Ph.D.", "Thesis", "M.Phil", "సిద్ధాంత గ్రంథం"),
    };

    static final Cat[] EN = {
        new Cat("novel").pg("fiction").se("fiction").ws("Novels"),
        new Cat("story").pg("short stories").se("shorts").ws("Short stories"),
        new Cat("poetry").pg("poetry").se("poetry").ws("Poems", "Poetry"),
        new Cat("bhakti").pg("religion").se("spirituality").ws("Religion", "Mythology"),
        new Cat("history").pg("history").ws("History"),
        new Cat("bio").pg("biography").se("biography").ws("Biographies", "Autobiographies"),
        new Cat("kids").pg("children").se("childrens").ws("Children's books"),
        new Cat("drama").pg("drama").se("drama").ws("Plays"),
        new Cat("science").pg("science").ws("Science"),
        new Cat("classic").pg("").se(""),
    };

    static Cat find(String lang, String key) {
        for (Cat c : "en".equals(lang) ? EN : TE) if (c.key.equals(key)) return c;
        return null;
    }

    private static boolean has(String[] a) { return a != null && a.length > 0; }

    /** The sites a subject asks, in the order their books are shown (Telugu: Wikisource first). */
    static List<String> sources(String lang, Cat c) {
        List<String> s = new ArrayList<>();
        if (c == null) return s;
        boolean en = "en".equals(lang);
        if (has(c.ws) || has(c.wsLinks)) s.add(en ? "wse" : "ws");
        if (c.pg != null) s.add("pg");
        if (en && c.se != null) s.add("se");
        if (!en && has(c.ia)) s.add("ia");
        if (!en && has(c.tt)) s.add("tt");
        if (!en && c.km != null) s.add("km");
        return s;
    }

    /** "cat:te:novel:all" → {te, novel, all}; null when it is not a subject shelf. */
    static String[] kind(String kind) {
        if (kind == null || !kind.startsWith("cat:")) return null;
        String[] p = kind.split(":");
        if (p.length != 4 || !p[1].matches("te|en") || !p[2].matches("[a-z]{2,12}") || !p[3].matches("all|ws|wse|pg|ia|tt|se|km")) return null;
        return new String[]{p[1], p[2], p[3]};
    }

    /** A Wikisource category name holds the word (and is not a maintenance category). */
    static boolean catTitleMatch(String title, String word) {
        if (title == null || word == null || word.isEmpty()) return false;
        String t = title.contains(":") ? title.substring(title.indexOf(':') + 1) : title;
        String tl = t.toLowerCase(Locale.ROOT);
        if (tl.matches(".*(మూస|వికీసోర్స్|template|wikisource|maintenance|proofread|అచ్చుదిద్దని|అచ్చుదిద్దిన|stub|pages|పుటలు|index|సూచిక|ఫైళ్ళు|files|authors|రచయితలు).*")) return false;
        return tl.contains(word.toLowerCase(Locale.ROOT));
    }

    /** One quoted IA search word (Telugu letters, Latin letters, digits, spaces, dots). */
    private static String iaWord(String w) {
        String x = w.replaceAll("[^\\p{L}\\p{M}\\p{N} .\\-]", " ").replaceAll("\\s+", " ").trim();
        return x.isEmpty() ? null : (x.contains(" ") ? "\"" + x + "\"" : x);
    }

    /** Internet Archive's Telugu books about these words (the caller adds the free-licence part). */
    static String iaQuery(String[] words) {
        List<String> w = new ArrayList<>();
        if (words != null) for (String s : words) { String q = iaWord(s); if (q != null) w.add(q); }
        String any = String.join(" OR ", w);
        return "(collection:booksbylanguage_telugu OR language:tel OR language:Telugu) AND mediatype:texts"
                + (w.isEmpty() ? "" : " AND (subject:(" + any + ") OR title:(" + any + "))")
                + " AND NOT title:(newspaper OR \"news paper\" OR epaper)";
    }

    /** Newspapers are not books (they come in the Telugu items too). */
    static boolean newspaper(String title) {
        return title != null && title.toLowerCase(Locale.ROOT).matches(".*(news ?paper|e-?paper|దినపత్రిక).*");
    }

    // ---- teluguthesis.com (a Blogger site): its posts, and the archive.org copies they point to

    private static final Pattern IA_LINK = Pattern.compile("https?://(?:www\\.)?archive\\.org/(?:details|download)/([A-Za-z0-9._-]{3,100})");

    /** The archive.org items a post links to, in order, once each. */
    static List<String> archiveIds(String html) {
        Set<String> s = new LinkedHashSet<>();
        if (html == null) return new ArrayList<>(s);
        Matcher m = IA_LINK.matcher(html.replace("&amp;", "&"));
        while (m.find()) { String id = m.group(1).replaceAll("[.]+$", ""); if (id.length() >= 3) s.add(id); }
        return new ArrayList<>(s);
    }

    /** A Blogger JSON feed's posts: {title, link, published, content}. */
    static List<String[]> bloggerPosts(JSONObject j) {
        List<String[]> out = new ArrayList<>();
        JSONObject feed = j == null ? null : j.optJSONObject("feed");
        JSONArray es = feed == null ? null : feed.optJSONArray("entry");
        for (int i = 0; es != null && i < es.length(); i++) {
            JSONObject e = es.optJSONObject(i);
            if (e == null) continue;
            String title = t(e, "title"), pub = t(e, "published");
            String content = e.optJSONObject("content") != null ? t(e, "content") : t(e, "summary");
            String link = "";
            JSONArray ls = e.optJSONArray("link");
            for (int k = 0; ls != null && k < ls.length(); k++) {
                JSONObject l = ls.optJSONObject(k);
                if (l != null && "alternate".equals(l.optString("rel"))) link = l.optString("href", "");
            }
            if (title.trim().isEmpty() || !link.startsWith("https://www.teluguthesis.com/")) continue;
            out.add(new String[]{title.trim(), link, pub, content});
        }
        return out;
    }

    /** Blogger's {"$t": text} field. */
    private static String t(JSONObject e, String k) { JSONObject o = e.optJSONObject(k); return o == null ? "" : o.optString("$t", ""); }

    /** A post title made short for a card ("తెలుగు పేరు English name" keeps the Telugu part when long). */
    static String postTitle(String t) {
        String x = t == null ? "" : t.replaceAll("\\s+", " ").trim();
        if (x.length() > 90) {
            Matcher m = Pattern.compile("^([^A-Za-z]{8,}?)\\s+[A-Za-z].*$").matcher(x);
            if (m.find()) x = m.group(1).trim();
        }
        return LibText.cut(x, 120);
    }

    // ---- Standard Ebooks: a subject's list page

    private static final Pattern SE_ITEM = Pattern.compile("<li([^>]*)>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern SE_ABOUT = Pattern.compile("about=\"/ebooks/([a-z0-9\\-]+/[a-z0-9\\-/]+)\"");
    private static final Pattern SE_NAME = Pattern.compile("property=\"schema:name\"[^>]*>([^<]+)<");
    private static final Pattern SE_LINK = Pattern.compile("href=\"(?:https://standardebooks\\.org)?/ebooks/([a-z0-9\\-]+/[a-z0-9\\-]+(?:/[a-z0-9\\-]+)?)\"");

    /** {sid, title, author} of the books on a Standard Ebooks list page (first by their book items, else by their links). */
    static List<String[]> seList(String html) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (html == null) return out;
        Matcher m = SE_ITEM.matcher(html);
        while (m.find()) {
            if (!m.group(1).contains("schema:Book")) continue;
            Matcher ab = SE_ABOUT.matcher(m.group(1));
            if (!ab.find()) continue;
            String sid = ab.group(1).replaceAll("/$", "");
            if (!seen.add(sid)) continue;
            List<String> names = new ArrayList<>();
            Matcher n = SE_NAME.matcher(m.group(2));
            while (n.find()) names.add(unescape(n.group(1).trim()));
            String title = names.isEmpty() ? slugTitle(sid.split("/")[1]) : names.get(0);
            String author = names.size() > 1 ? names.get(1) : slugTitle(sid.split("/")[0]);
            out.add(new String[]{sid, title, author});
        }
        if (!out.isEmpty()) return out;
        Matcher l = SE_LINK.matcher(html);
        while (l.find()) {
            String sid = l.group(1);
            String[] p = sid.split("/");
            if (p.length < 2 || sid.matches(".*/(downloads|text|images)$") || !seen.add(sid)) continue;
            out.add(new String[]{sid, slugTitle(p[1]), slugTitle(p[0])});
        }
        return out;
    }

    /** There is a next page on a Standard Ebooks list. */
    static boolean seMore(String html, int page) {
        return html != null && html.contains("page=" + (page + 1) + "\"");
    }

    // ---- Koumudi (a Telugu web monthly): its own free book library, PDFs under koumudi.net/books/

    private static final Pattern KM_LINK = Pattern.compile("href\\s*=\\s*[\"']?((?:https?://(?:www\\.)?koumudi\\.net)?/?books/([A-Za-z0-9_\\-.%]+?)\\.pdf)[\"'\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern KM_IMG = Pattern.compile("src\\s*=\\s*[\"']?((?:https?://(?:www\\.)?koumudi\\.net)?/?images/[A-Za-z0-9_/\\-.%]+\\.(?:jpe?g|png|gif))", Pattern.CASE_INSENSITIVE);
    /** Koumudi's section headings → {key, author}. */
    static final String[][] KM_SECTIONS = {
        {"తాజాగా విడుదలైన", "new", ""}, {"కౌముది ప్రత్యేక", "special", ""}, {"కొమ్మూరి సాంబశివరావు", "kommuri", "కొమ్మూరి సాంబశివరావు"},
        {"గొల్లపూడి మారుతి", "gollapudi", "గొల్లపూడి మారుతీరావు"}, {"నవలలు", "novel", ""},
    };

    /** The library page's books: {pdf url, title (from the file name), section key, cover url or "", author or ""}. */
    static List<String[]> koumudi(String html) {
        List<String[]> out = new ArrayList<>();
        if (html == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = KM_LINK.matcher(html);
        while (m.find()) {
            String name = m.group(2);
            if (name.contains("..") || name.contains("/")) continue;
            String url = "https://www.koumudi.net/books/" + name + ".pdf";
            if (!seen.add(url)) continue;
            String[] sec = {"", "other", ""};
            int best = -1;
            for (String[] h : KM_SECTIONS) { int at = html.lastIndexOf(h[0], m.start()); if (at > best) { best = at; sec = h; } }
            String cover = "";
            Matcher im = KM_IMG.matcher(html.substring(m.end(), Math.min(html.length(), m.end() + 600)));
            if (im.find()) {
                String c = im.group(1);
                cover = c.startsWith("http") ? c.replaceFirst("^http://", "https://") : "https://www.koumudi.net/" + c.replaceFirst("^/", "");
            }
            out.add(new String[]{url, kmTitle(name), sec[1], cover, sec[2]});
        }
        return out;
    }

    /** "KOUMUDI_SUBHA_MANGALAM" → "Subha Mangalam". */
    static String kmTitle(String file) {
        String x = file.replace("%20", " ").replaceFirst("(?i)^koumudi[_\\- ]*", "").replaceAll("[_\\-]+", " ").trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (String w : x.split(" ")) { if (w.isEmpty()) continue; if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return b.length() == 0 ? "Koumudi" : b.toString();
    }

    // ---- Marxists Internet Archive, Telugu section (public domain; the archive's own work under Creative Commons)

    private static final Pattern MIA_LINK = Pattern.compile("<a[^>]+href\\s*=\\s*\"([^\"#]+\\.(?:pdf|htm))\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The Telugu section's works: {url, title, "pdf" | "htm"} (only under marxists.org/telugu/, Telugu titles). */
    static List<String[]> mia(String html, String base) {
        List<String[]> out = new ArrayList<>();
        if (html == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = MIA_LINK.matcher(html);
        while (m.find()) {
            String url;
            try { url = new java.net.URL(new java.net.URL(base), m.group(1).trim()).toString().replaceFirst("^http://", "https://"); } catch (Exception e) { continue; }
            String title = unescape(m.group(2).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
            if (!url.startsWith("https://www.marxists.org/telugu/") || url.endsWith("/telugu/index.htm") || !title.matches("(?s).*[\\u0C00-\\u0C7F].*") || !seen.add(url)) continue;
            out.add(new String[]{url, LibText.cut(title, 140), url.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "pdf" : "htm"});
        }
        return out;
    }

    /** Only these publishers' PDFs come onto the phone (https, their own library folders). */
    static boolean pdfAllowed(String url) {
        return url != null && url.length() < 400 && !url.contains("..")
                && (url.matches("https://www\\.koumudi\\.net/books/[A-Za-z0-9_\\-.%]+\\.pdf") || url.matches("https://www\\.marxists\\.org/telugu/[A-Za-z0-9_\\-./%]+\\.pdf"));
    }

    static String slugTitle(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : (s == null ? "" : s).split("[-_]")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#039;", "'").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&rsquo;", "’");
    }
}
