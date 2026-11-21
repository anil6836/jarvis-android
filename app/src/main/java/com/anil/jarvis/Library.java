package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.graphics.pdf.PdfRenderer;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.vision.text.TextRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 📚 Jarvis గ్రంథాలయం (stage 1, 20 Nov): his books on wooden racks (LibraryActivity draws them). Here: the shelf on the phone
 * (files/library), the free books that can be fetched legally — Project Gutenberg (through Gutendex's search), Standard
 * Ebooks, Telugu Wikisource — and the details of any book from Open Library; books he adds from the phone (TXT / EPUB /
 * PDF / page photos: English read by the phone itself, Telugu print by his AI only after the cost is shown); English read
 * out in Telugu by the phone's own translation (₹0) or by his AI (cost said first, chapter by chapter, kept so it is never
 * paid twice). No piracy sites, ever; books with copyright show their details only. Phone only.
 */
final class Library {
    private Library() {}

    static final String DIR = "library";
    static final String UA = "JarvisLibrary/1.0 (a personal reading app on Android)";
    private static final int MAX_BOOK = 14_000_000, MAX_COVER = 1_500_000;

    // ================================================================ where things are

    static File root(Context c) { File f = new File(c.getFilesDir(), DIR); f.mkdirs(); return f; }

    static File bookDir(Context c, String id) { return new File(root(c), "b-" + id); }

    static File coverFile(Context c, String id) { File d = new File(root(c), "covers"); d.mkdirs(); return new File(d, id + ".jpg"); }

    // ================================================================ the shelf: his books (shelf.json)

    private static JSONArray shelfCache;

    static synchronized JSONArray shelf(Context c) {
        if (shelfCache != null) return shelfCache;
        JSONArray a = new JSONArray();
        try {
            File f = new File(root(c), "shelf.json");
            if (f.exists()) a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optJSONArray("books");
        } catch (Exception ignored) {}
        shelfCache = a == null ? new JSONArray() : a;
        return shelfCache;
    }

    private static synchronized void saveShelf(Context c, JSONArray a) {
        shelfCache = a;
        try {
            File f = new File(root(c), "shelf.json"), t = new File(root(c), "shelf.json.tmp");
            Files.write(t.toPath(), new JSONObject().put("v", 1).put("books", a).toString().getBytes(StandardCharsets.UTF_8));
            if (!t.renameTo(f)) { Files.write(f.toPath(), Files.readAllBytes(t.toPath())); t.delete(); }
        } catch (Exception ignored) {}
    }

    static synchronized JSONObject book(Context c, String id) {
        JSONArray a = shelf(c);
        for (int i = 0; i < a.length(); i++) { JSONObject b = a.optJSONObject(i); if (b != null && id.equals(b.optString("id"))) return b; }
        return null;
    }

    static synchronized void put(Context c, JSONObject b) {
        JSONArray a = shelf(c), out = new JSONArray();
        String id = b.optString("id");
        boolean done = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            if (id.equals(x.optString("id"))) { if (!done) out.put(b); done = true; } else out.put(x);
        }
        if (!done) out.put(b);
        saveShelf(c, out);
    }

    /** Takes a book off the phone (its text, translations, cover); a starter book's spine stays on its rack, not downloaded. */
    static synchronized void remove(Context c, String id) {
        if (!LibText.goodId(id)) return;
        JSONArray a = shelf(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !id.equals(x.optString("id"))) out.put(x); }
        saveShelf(c, out);
        wipe(bookDir(c, id));
        coverFile(c, id).delete();
        pos(c).edit().remove(id).apply();
    }

    private static void wipe(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) wipe(x);
        f.delete();
    }

    static synchronized void flag(Context c, String id, String key, boolean on) {
        JSONObject b = book(c, id);
        if (b == null) {
            JSONObject s = starterEntry(c, id);
            if (s == null) return;
            try { b = new JSONObject(s.toString()).put("state", "none"); } catch (Exception e) { return; }
        }
        try { b.put(key, on); } catch (Exception ignored) {}
        put(c, b);
    }

    /** A book from a search kept on the 🔖 wishes shelf: its details only (no text). */
    static void wish(Context c, JSONObject item) throws Exception {
        String id = item.optString("id");
        if (!LibText.goodId(id) || book(c, id) != null) return;
        JSONObject b = new JSONObject();
        for (String k : new String[]{"id", "t", "a", "y", "lang", "sec", "era", "ol", "mark", "cover_url", "src", "src_id", "summary", "free"})
            if (item.has(k)) b.put(k, item.get(k));
        b.put("t", LibText.cut(b.optString("t"), 160)).put("a", LibText.cut(b.optString("a"), 100)).put("wish", true).put("state", "none").put("at", System.currentTimeMillis());
        put(c, b);
    }

    // ================================================================ the starter racks (inside the app)

    private static JSONArray starterCache;

    static synchronized JSONArray starter(Context c) {
        if (starterCache != null) return starterCache;
        try (InputStream in = c.getAssets().open("library/starter.json")) {
            starterCache = new JSONObject(new String(readAll(in, 2_000_000), StandardCharsets.UTF_8)).getJSONArray("books");
        } catch (Exception e) { starterCache = new JSONArray(); }
        return starterCache;
    }

    static JSONObject starterEntry(Context c, String id) {
        JSONArray a = starter(c);
        for (int i = 0; i < a.length(); i++) { JSONObject b = a.optJSONObject(i); if (b != null && id.equals(b.optString("id"))) return b; }
        return null;
    }

    // ================================================================ a book's chapters

    static int count(Context c, String id) { JSONObject b = book(c, id); return b == null ? 0 : b.optInt("n", 0); }

    static String chapter(Context c, String id, int unit) throws IOException {
        File f = new File(bookDir(c, id), String.format(Locale.ENGLISH, "c%03d.txt", unit));
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static JSONArray titles(Context c, String id) {
        try { return new JSONArray(new String(Files.readAllBytes(new File(bookDir(c, id), "titles.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** Writes the chapters; returns {chapters, letters}. */
    static long[] saveChapters(Context c, String id, List<String[]> ch) throws IOException {
        File d = bookDir(c, id);
        wipe(d);
        d.mkdirs();
        JSONArray t = new JSONArray();
        long chars = 0;
        for (int i = 0; i < ch.size(); i++) {
            Files.write(new File(d, String.format(Locale.ENGLISH, "c%03d.txt", i)).toPath(), ch.get(i)[1].getBytes(StandardCharsets.UTF_8));
            t.put(LibText.cut(ch.get(i)[0], 90));
            chars += ch.get(i)[1].length();
        }
        Files.write(new File(d, "titles.json").toPath(), t.toString().getBytes(StandardCharsets.UTF_8));
        return new long[]{ch.size(), chars};
    }

    // ================================================================ where he stopped in each book

    static SharedPreferences pos(Context c) { return c.getSharedPreferences("jarvis_library_pos", Context.MODE_PRIVATE); }

    static void savePos(Context c, String id, int unit, int chunk) {
        pos(c).edit().putString(id, unit + ":" + chunk + ":" + System.currentTimeMillis()).apply();
    }

    /** {unit, chunk} where he stopped (0, 0 when never). */
    static int[] posOf(Context c, String id) {
        String s = pos(c).getString(id, "");
        try { String[] p = s.split(":"); return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; } catch (Exception e) { return new int[]{0, 0}; }
    }

    static JSONObject positions(Context c) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : pos(c).getAll().entrySet()) {
            try { String[] p = String.valueOf(e.getValue()).split(":"); o.put(e.getKey(), new JSONArray().put(Integer.parseInt(p[0])).put(Integer.parseInt(p[1])).put(Long.parseLong(p[2]))); }
            catch (Exception ignored) {}
        }
        return o;
    }

    /** Read to the end: on the "పూర్తయినవి" shelf. */
    static void finished(Context c, String id) {
        JSONObject b = book(c, id);
        if (b == null) return;
        try { b.put("done", true); } catch (Exception ignored) {}
        put(c, b);
        pos(c).edit().remove(id).apply();
    }

    // ================================================================ the internet (only these free, keyless sites; nothing of his is sent)

    static byte[] get(String url, int max) throws IOException {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!u.startsWith("https://")) throw new IOException("https కాని లింక్");
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Encoding", "identity");
                int s = c.getResponseCode();
                if (s >= 300 && s < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("HTTP " + s + " (" + host(u) + ")");
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (s == 429) throw new IOException(host(u) + " కొంచెం ఆగమంది (చాలా అడిగాం). కాసేపాగి మళ్ళీ ప్రయత్నించండి");
                if (s >= 500) throw new IOException(host(u) + " సైట్ ఇప్పుడు పనిచేయడం లేదు (HTTP " + s + ")");
                if (s >= 400) throw new IOException("HTTP " + s + " (" + host(u) + ")");
                try (InputStream in = c.getInputStream()) { return readAll(in, max); }
            } finally { c.disconnect(); }
        }
        throw new IOException("చాలా redirects");
    }

    private static String host(String u) { try { return new URL(u).getHost().replaceFirst("^www\\.", ""); } catch (Exception e) { return "సైట్"; } }

    static JSONObject getJson(String url) throws Exception {
        return new JSONObject(new String(get(url, 4_000_000), StandardCharsets.UTF_8));
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int r; (r = in.read(buf)) > 0; ) { b.write(buf, 0, r); if (b.size() > max) throw new IOException("ఫైల్ చాలా పెద్దది"); }
        return b.toByteArray();
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; } }

    private static boolean telugu(String s) { return s != null && s.matches("(?s).*[\\u0C00-\\u0C7F].*"); }

    // ================================================================ 🔍 search: the racks, the free sites, the world's catalogue

    /** {results: [...], problems: [...]} — starter rack matches first, then free books, then details-only books. */
    static JSONObject search(Context c, String q) {
        JSONObject out = new JSONObject();
        JSONArray res = new JSONArray(), probs = new JSONArray();
        Map<String, JSONObject> seen = new LinkedHashMap<>();
        String query = q == null ? "" : q.trim();
        try {
            if (query.isEmpty()) return out.put("results", res);
            // (🎤 said in Telugu about an English book: the phone's Telugu pack gives the English words too, ₹0, offline)
            String en = telugu(query) ? OfflineRead.toEnglish(c, query) : null;
            if (en != null && (en.equalsIgnoreCase(query) || telugu(en))) en = null;
            if (en != null) out.put("en", en);
            // 1. on our racks already
            JSONArray st = starter(c);
            for (int i = 0; i < st.length(); i++) {
                JSONObject b = st.optJSONObject(i);
                if (b == null) continue;
                double s = Math.max(LibText.score(query, b.optString("t") + " " + b.optString("tt"), b.optString("a")), LibText.score(query, b.optString("tt"), ""));
                if (en != null) s = Math.max(s, LibText.score(en, b.optString("t"), b.optString("a")));
                if (s >= 0.6) seen.put(b.optString("id"), new JSONObject(b.toString()).put("rack", true).put("free", true));
            }
            final String world = en != null ? en : query; // (Gutenberg / Open Library are searched in English when he said it in Telugu)
            // 2. Telugu Wikisource (Telugu words): its books and its typed scans; Gutenberg's Telugu books
            if (telugu(query)) {
                try { for (JSONObject o : wsSearch(WS_TE, query, 10, true)) if (!seen.containsKey(o.optString("id"))) seen.put(o.optString("id"), o); }
                catch (Exception e) { probs.put("తెలుగు వికీసోర్స్ చేరలేదు"); }
                try {
                    JSONArray r = getJson("https://gutendex.com/books?languages=te&search=" + enc(query)).optJSONArray("results");
                    for (int i = 0; r != null && i < r.length() && i < 8; i++) { JSONObject o = LibText.fromGutendex(r.getJSONObject(i)); if (!seen.containsKey(o.optString("id"))) seen.put(o.optString("id"), o); }
                } catch (Exception ignored) {} // (Gutenberg's English search below says if it is down)
            }
            // 3. Project Gutenberg's free books (through Gutendex)
            Map<String, JSONObject> pgById = new HashMap<>();
            List<JSONObject> pgHits = new ArrayList<>();
            try {
                JSONArray r = getJson("https://gutendex.com/books?search=" + enc(world)).optJSONArray("results");
                for (int i = 0; r != null && i < r.length() && i < 10; i++) pgHits.add(LibText.fromGutendex(r.getJSONObject(i)));
            } catch (Exception e) {
                try { pgHits = LibText.fromOpds(new String(get("https://www.gutenberg.org/ebooks/search.opds/?query=" + enc(world), 3_000_000), StandardCharsets.UTF_8)); }
                catch (Exception x) { probs.put("Project Gutenberg చేరలేదు"); }
            }
            for (int i = 0; i < pgHits.size() && i < 10; i++) {
                JSONObject g = pgHits.get(i);
                pgById.put(g.optString("src_id"), g);
                if (!seen.containsKey(g.optString("id"))) seen.put(g.optString("id"), g);
            }
            // 4. every other book: its details (Open Library)
            try {
                JSONArray docs = getJson("https://openlibrary.org/search.json?limit=12&fields=key,title,author_name,first_publish_year,cover_i,ebook_access,"
                        + "id_project_gutenberg,id_standard_ebooks,language,subject&q=" + enc(world)).optJSONArray("docs");
                for (int i = 0; docs != null && i < docs.length(); i++) {
                    JSONObject o = LibText.fromOpenLibrary(docs.getJSONObject(i));
                    if ("pg".equals(o.optString("src"))) {
                        JSONObject g = pgById.get(o.optString("src_id"));
                        if (g != null) { if (!g.has("cover_url") && o.has("cover_url")) g.put("cover_url", o.get("cover_url")); if (o.has("y")) g.put("y", o.get("y")); continue; }
                        o.put("id", "g" + o.optString("src_id"));
                    } else if ("se".equals(o.optString("src"))) o.put("id", LibText.idFrom("se", o.optString("src_id")));
                    if (!seen.containsKey(o.optString("id"))) seen.put(o.optString("id"), o);
                }
            } catch (Exception e) { probs.put("Open Library చేరలేదు"); }
            // 5. English Wikisource (English words), 6. Internet Archive books marked free by their uploader
            if (!telugu(world)) {
                try { for (JSONObject o : wsSearch(WS_EN, world, 5, false)) if (!seen.containsKey(o.optString("id")) && LibText.score(world, o.optString("t"), "") >= 0.45) seen.put(o.optString("id"), o); }
                catch (Exception ignored) {}
            }
            for (JSONObject o : iaSearch(telugu(query) && en == null ? query : world, telugu(query), 6)) if (!seen.containsKey(o.optString("id"))) seen.put(o.optString("id"), o);
            // (already on his phone: say so)
            for (JSONObject o : seen.values()) {
                JSONObject b = book(c, o.optString("id"));
                if (b != null && "ready".equals(b.optString("state"))) o.put("have", true);
                res.put(o);
                if (res.length() >= 30) break;
            }
            return out.put("results", res).put("problems", probs).put("q", query);
        } catch (Exception e) {
            try { return out.put("results", res).put("problems", probs.put(String.valueOf(e.getMessage()))); } catch (Exception x) { return out; }
        }
    }

    // ================================================================ 📜 more free shelves (Anil, 21 Nov: "Inka కలుపు… telugu పుస్తకాలు అన్ని కలుపు")
    // Telugu Wikisource's scanned books (సూచిక / పుట: the pages people typed from free scans on Wikimedia Commons),
    // English Wikisource, Project Gutenberg's Telugu books, and Internet Archive books whose uploader marked them free
    // (a Creative Commons licence or the public-domain mark). Nothing else: no site without a clear free licence.

    static final String WS_TE = "te.wikisource.org", WS_EN = "en.wikisource.org";

    /** A Wikisource's scan namespaces: {page ns, its name ("పుట"), index ns, its name ("సూచిక")}; -1 when it has none. */
    private static final Map<String, Object[]> WS_NS = new HashMap<>();

    static Object[] wsNs(String host) throws Exception {
        synchronized (WS_NS) { Object[] k = WS_NS.get(host); if (k != null) return k; }
        JSONObject j = getJson("https://" + host + "/w/api.php?action=query&meta=siteinfo&siprop=namespaces&format=json&formatversion=2");
        JSONObject ns = j.getJSONObject("query").getJSONObject("namespaces");
        int pn = -1, in = -1;
        String pp = "Page", ip = "Index";
        for (java.util.Iterator<String> it = ns.keys(); it.hasNext(); ) {
            JSONObject o = ns.optJSONObject(it.next());
            if (o == null) continue;
            String can = o.optString("canonical", "");
            if ("Page".equals(can)) { pn = o.optInt("id", -1); pp = o.optString("name", "Page"); }
            if ("Index".equals(can)) { in = o.optInt("id", -1); ip = o.optString("name", "Index"); }
        }
        Object[] k = new Object[]{pn, pp, in, ip};
        synchronized (WS_NS) { WS_NS.put(host, k); }
        return k;
    }

    /** One book's card from a Wikisource title (a main page, or a scan's సూచిక page with its fields). */
    private static JSONObject wsEntry(String host, String title, JSONObject meta, boolean scan) throws Exception {
        boolean te = WS_TE.equals(host);
        String base = !scan && title.contains("/") ? title.substring(0, title.indexOf('/')) : title;
        String t = scan ? (meta != null && !meta.optString("t").isEmpty() ? meta.optString("t") : LibText.fileTitle(base)) : base;
        JSONObject o = new JSONObject().put("id", LibText.idFrom(scan ? "wsi" : te ? "ws" : "wse", base)).put("t", t)
                .put("a", meta == null ? "" : meta.optString("a", "")).put("src", scan ? "wsi" : te ? "ws" : "wse").put("src_id", base)
                .put("host", host).put("lang", te ? "te" : "en").put("sec", te ? "telugu" : "fic").put("era", "modern").put("free", true);
        if (meta != null && meta.has("y")) o.put("y", meta.optInt("y")).put("era", LibText.eraOfYear(meta.optInt("y")));
        if (scan) o.put("scan", true);
        return o;
    }

    /** The Title / Author / Year of these సూచిక pages, in one call ({title → meta}). */
    private static Map<String, JSONObject> wsIndexMeta(String host, List<String> titles) {
        Map<String, JSONObject> out = new HashMap<>();
        if (titles.isEmpty()) return out;
        try {
            JSONObject r = getJson("https://" + host + "/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&titles="
                    + enc(String.join("|", titles.subList(0, Math.min(40, titles.size())))));
            JSONArray pages = r.getJSONObject("query").optJSONArray("pages");
            for (int i = 0; pages != null && i < pages.length(); i++) {
                JSONObject pg = pages.getJSONObject(i);
                JSONArray rv = pg.optJSONArray("revisions");
                String w = rv == null || rv.length() == 0 ? "" : rv.getJSONObject(0).optJSONObject("slots") == null ? "" : rv.getJSONObject(0).getJSONObject("slots").getJSONObject("main").optString("content", "");
                out.put(pg.optString("title"), LibText.indexMeta(w));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Wikisource search: its books (main pages) and, on Telugu, its scanned books too. */
    static List<JSONObject> wsSearch(String host, String q, int max, boolean scans) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Object[] ns = scans ? wsNs(host) : new Object[]{-1, "", -1, ""};
        int in = (Integer) ns[2];
        String nss = in >= 0 ? "0|" + in : "0";
        JSONObject w = getJson("https://" + host + "/w/api.php?action=query&list=search&srnamespace=" + enc(nss) + "&srlimit=" + max + "&format=json&formatversion=2&srsearch=" + enc(q));
        JSONArray hits = w.optJSONObject("query") == null ? null : w.getJSONObject("query").optJSONArray("search");
        List<String> idx = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            String title = h.optString("title", "");
            if (title.isEmpty()) continue;
            if (h.optInt("ns", 0) == in && in >= 0) { idx.add(title); continue; }
            String base = title.contains("/") ? title.substring(0, title.indexOf('/')) : title;
            if (seen.put(base, true) != null) continue;
            out.add(wsEntry(host, base, null, false));
        }
        Map<String, JSONObject> meta = wsIndexMeta(host, idx);
        for (String t : idx) out.add(wsEntry(host, t, meta.get(t), true));
        return out;
    }

    /** The scan (సూచిక) that best matches a book's name; null when none is close. */
    static String bestIndex(String host, String q) throws Exception {
        String best = null;
        double bs = 0;
        for (JSONObject o : wsSearch(host, q, 8, true)) {
            if (!o.optBoolean("scan")) continue;
            double sc = LibText.score(q, o.optString("t"), o.optString("a"));
            if (sc > bs) { bs = sc; best = o.optString("src_id"); }
        }
        return bs >= 0.5 ? best : null;
    }

    /** A scanned book's typed pages (పుట:file/1, /2, …), 50 at a time, as chapters of 8 pages; honest when too few are typed yet. */
    static List<String[]> wsScan(String host, String index, Progress p) throws Exception {
        Object[] ns = wsNs(host);
        String file = index.contains(":") ? index.substring(index.indexOf(':') + 1) : index;
        String prefix = (String) ns[1];
        int count = 0;
        try {
            JSONObject j = getJson("https://" + host + "/w/api.php?action=query&prop=imageinfo&iiprop=size&format=json&formatversion=2&titles=" + enc("File:" + file));
            JSONArray pages = j.getJSONObject("query").optJSONArray("pages");
            JSONArray ii = pages == null || pages.length() == 0 ? null : pages.getJSONObject(0).optJSONArray("imageinfo");
            if (ii != null && ii.length() > 0) count = ii.getJSONObject(0).optInt("pagecount", 0);
        } catch (Exception ignored) {}
        if (count <= 0) count = 400; // (not told: read on until the typed pages end)
        count = Math.min(count, 1200);
        List<String> texts = new ArrayList<>();
        int typed = 0, emptyRun = 0;
        for (int from = 1; from <= count; from += 50) {
            int to = Math.min(count, from + 49);
            p.at("⬇️ " + (WS_TE.equals(host) ? "తెలుగు వికీసోర్స్" : "Wikisource") + ": పుటలు " + from + "–" + to + " / " + count + "…");
            List<String> titles = new ArrayList<>();
            for (int n = from; n <= to; n++) titles.add(prefix + ":" + file + "/" + n);
            JSONObject r = getJson("https://" + host + "/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&titles=" + enc(String.join("|", titles)));
            Map<String, String> got = new HashMap<>();
            JSONArray pages = r.getJSONObject("query").optJSONArray("pages");
            for (int i = 0; pages != null && i < pages.length(); i++) {
                JSONObject pg = pages.getJSONObject(i);
                if (pg.optBoolean("missing")) continue;
                JSONArray rv = pg.optJSONArray("revisions");
                if (rv == null || rv.length() == 0) continue;
                JSONObject slots = rv.getJSONObject(0).optJSONObject("slots");
                got.put(pg.optString("title"), slots == null ? "" : LibText.wikiText(slots.getJSONObject("main").optString("content", "")));
            }
            for (String t : titles) {
                String x = got.get(t.replace('_', ' '));
                if (x == null) x = got.get(t);
                texts.add(x == null ? "" : x);
                if (x != null && x.length() > 40) { typed++; emptyRun = 0; } else emptyRun++;
            }
            if (count == 400 && emptyRun >= 60) break; // (an unknown length: 60 empty pages in a row is the end)
            Thread.sleep(300); // (gently)
        }
        String all = String.join("\n", texts);
        if (typed < 3 || all.length() < 1500)
            throw new IOException("ఈ స్కాన్ పుస్తకం పుటలు వికీసోర్స్‌లో ఇంకా అక్షరాలుగా టైప్ కాలేదు (" + typed + " పుటలే ఉన్నాయి). వేరే కాపీ వెతకండి.");
        if (LibText.textQuality(all, WS_TE.equals(host)) < 0.7) throw new IOException("ఈ స్కాన్ పుస్తకం అక్షరాలు సరిగా రాలేదు (స్కాన్ నుంచి తప్పులు). వేరే కాపీ వెతకండి.");
        return LibText.pageParts(texts, 8);
    }

    // ---- Internet Archive (only books marked free by their uploader)

    static List<JSONObject> iaSearch(String q, boolean te, int rows) {
        String words = q.replaceAll("[\"():\\[\\]{}^~*?\\\\/]", " ").replaceAll("\\s+", " ").trim();
        if (words.isEmpty() && !te) return new ArrayList<>();
        String base = (words.isEmpty() ? "" : "(title:(" + words + ") OR creator:(" + words + ")) AND ") + "mediatype:texts" + (te ? " AND (language:tel OR language:Telugu)" : "");
        try { return iaEntries(iaDocs(base, rows, 1), rows); } catch (Exception e) { return new ArrayList<>(); }
    }

    /** Internet Archive's answer for a query, only items marked free by their uploader (asked there; else filtered here). */
    static JSONArray iaDocs(String base, int rows, int page) throws Exception {
        JSONArray docs = null;
        Exception last = null;
        for (int k = 0; k < 2 && docs == null; k++) {
            String query = k == 0 ? base + " AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*)" : base;
            try {
                docs = getJson("https://archive.org/advancedsearch.php?q=" + enc(query) + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=date"
                        + "&fl%5B%5D=language&fl%5B%5D=licenseurl&sort%5B%5D=downloads+desc&rows=" + (k == 0 ? rows : rows * 4) + "&page=" + Math.max(1, page) + "&output=json")
                        .getJSONObject("response").optJSONArray("docs");
                if (docs != null && docs.length() == 0 && k == 0 && page == 1) docs = null; // (the licence words not understood there: filter here instead)
            } catch (Exception e) { last = e; }
        }
        if (docs == null && last != null) throw last;
        return docs == null ? new JSONArray() : docs;
    }

    /** IA answers → cards (free-marked only; no newspapers). */
    static List<JSONObject> iaEntries(JSONArray docs, int rows) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; docs != null && i < docs.length() && out.size() < rows; i++) {
            JSONObject d = docs.optJSONObject(i);
            if (d == null || !LibText.iaFree(one(d, "licenseurl")) || LibShelves.newspaper(one(d, "title"))) continue;
            String id = d.optString("identifier", "");
            if (!id.matches("[A-Za-z0-9._-]{3,100}")) continue;
            String lang = one(d, "language").toLowerCase(Locale.ROOT);
            boolean tel = lang.startsWith("tel") || telugu(one(d, "title"));
            try {
                JSONObject o = new JSONObject().put("id", LibText.idFrom("ia", id)).put("t", LibText.cut(one(d, "title"), 160)).put("a", one(d, "creator"))
                        .put("src", "ia").put("src_id", id).put("lang", tel ? "te" : "en").put("sec", tel ? "telugu" : "fic").put("era", "modern")
                        .put("free", true).put("cover_url", "https://archive.org/services/img/" + id);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1[5-9]\\d\\d|20\\d\\d)").matcher(one(d, "date"));
                if (m.find()) { int y = Integer.parseInt(m.group(1)); o.put("y", y).put("era", LibText.eraOfYear(y)); }
                out.add(o);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** A field that may be one value or a list: its first value. */
    private static String one(JSONObject d, String k) {
        Object v = d.opt(k);
        if (v instanceof JSONArray) return ((JSONArray) v).optString(0, "");
        return v == null ? "" : String.valueOf(v);
    }

    static List<String[]> iaBook(String id, String wanted, Progress p) throws Exception {
        if (!id.matches("[A-Za-z0-9._-]{3,100}")) throw new IOException("Internet Archive గుర్తు సరిగా లేదు");
        p.at("Internet Archive లో చూస్తున్నాను…");
        JSONObject meta = getJson("https://archive.org/metadata/" + id);
        JSONObject md = meta.optJSONObject("metadata");
        if (md == null || !LibText.iaFree(one(md, "licenseurl")))
            throw new IOException("ఈ పుస్తకానికి ఉచితంగా పంచుకోవచ్చనే గుర్తు లేదు; దించను");
        String[] f = LibText.iaFile(meta);
        if (f == null) throw new IOException("ఈ పుస్తకానికి చదవగలిగే అక్షరాల ఫైల్ లేదు (స్కాన్ బొమ్మలు మాత్రమే)");
        StringBuilder path = new StringBuilder();
        for (String seg : f[0].split("/")) { if (path.length() > 0) path.append('/'); path.append(enc(seg).replace("+", "%20")); }
        p.at("⬇️ Internet Archive నుంచి దించుతున్నాను…");
        byte[] data = get("https://archive.org/download/" + id + "/" + path, MAX_BOOK);
        List<String[]> ch;
        boolean te = one(md, "language").toLowerCase(Locale.ROOT).startsWith("tel");
        if ("epub".equals(f[1])) ch = epubChapters(data, null);
        else {
            String text = new String(data, StandardCharsets.UTF_8);
            if (LibText.textQuality(text, te || telugu(wanted)) < 0.7) throw new IOException("ఈ పుస్తకం అక్షరాలు స్కాన్ నుంచి సరిగా రాలేదు; వేరే కాపీ వెతకండి");
            ch = LibText.chapters(text);
            if (ch == null || ch.isEmpty()) ch = LibText.bound(LibText.parts(text, ""));
        }
        return ch;
    }

    // ---- 📚 shelves to browse (no typing): Telugu first

    /** kind: ws_featured, ws_satakam, ws_kandukuri, ws_granthamala, pg_te, ia_te, ws_en_featured. → {results, problems, browse, page, more}. */
    static JSONObject browse(Context c, String kind, int page) {
        if (LibShelves.kind(kind) != null) return catBrowse(c, kind, page);
        if (kind != null && kind.matches("tt_all|pg_en|se_all|km_all|mia_te")) return siteBrowse(c, kind, page);
        JSONObject out = new JSONObject();
        JSONArray res = new JSONArray(), probs = new JSONArray();
        boolean more = false;
        try {
            List<JSONObject> list = new ArrayList<>();
            switch (kind) {
                case "pg_te": {
                    try {
                        JSONObject g = getJson("https://gutendex.com/books?languages=te&page=" + Math.max(1, page));
                        JSONArray r = g.optJSONArray("results");
                        for (int i = 0; r != null && i < r.length(); i++) list.add(LibText.fromGutendex(r.getJSONObject(i)));
                        more = !g.isNull("next") && g.has("next");
                    } catch (Exception e) { probs.put("Project Gutenberg చేరలేదు"); }
                    break;
                }
                case "ia_te": list.addAll(iaSearch("", true, 30)); if (list.isEmpty()) probs.put("Internet Archive లో ఉచిత లైసెన్స్ ఉన్న తెలుగు పుస్తకాలు ఇప్పుడు రాలేదు"); break;
                case "ws_satakam": list.addAll(wsCategory(WS_TE, "వర్గం:శతకములు")); break;
                case "ws_kandukuri": list.addAll(wsCategory(WS_TE, "వర్గం:కందుకూరి వీరేశలింగం రచనలు")); break;
                case "ws_featured": list.addAll(wsLinks(WS_TE, "వికీసోర్స్:విశేష గ్రంథాలు")); break;
                case "ws_granthamala": list.addAll(wsLinks(WS_TE, "వేదిక:ఆంధ్ర గ్రంథమాల")); break;
                default:
            }
            for (JSONObject o : list) {
                JSONObject b = book(c, o.optString("id"));
                if (b != null && "ready".equals(b.optString("state"))) o.put("have", true);
                res.put(o);
            }
        } catch (Exception e) { probs.put(e.getMessage() == null ? "చేరలేదు" : e.getMessage()); }
        try { return out.put("results", res).put("problems", probs).put("browse", kind).put("page", page).put("more", more); } catch (Exception e) { return out; }
    }

    /** A Wikisource category's books (main pages and scans). */
    static List<JSONObject> wsCategory(String host, String cat) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Object[] ns = wsNs(host);
        int in = (Integer) ns[2];
        JSONObject j = getJson("https://" + host + "/w/api.php?action=query&list=categorymembers&cmlimit=150&format=json&formatversion=2&cmnamespace="
                + enc(in >= 0 ? "0|" + in : "0") + "&cmtitle=" + enc(cat));
        JSONArray m = j.getJSONObject("query").optJSONArray("categorymembers");
        List<String> titles = new ArrayList<>();
        for (int i = 0; m != null && i < m.length(); i++) titles.add(m.getJSONObject(i).optString("title"));
        return wsEntries(host, titles, in);
    }

    /** The books a Wikisource page links to (its featured list, a portal). */
    static List<JSONObject> wsLinks(String host, String page) throws Exception {
        return wsEntries(host, wsLinkTitles(host, page), (Integer) wsNs(host)[2]);
    }

    static List<String> wsLinkTitles(String host, String page) throws Exception {
        Object[] ns = wsNs(host);
        int in = (Integer) ns[2];
        JSONObject j = getJson("https://" + host + "/w/api.php?action=parse&prop=links&format=json&formatversion=2&page=" + enc(page));
        JSONArray l = j.getJSONObject("parse").optJSONArray("links");
        List<String> titles = new ArrayList<>();
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject o = l.getJSONObject(i);
            int n = o.optInt("ns", -99);
            if (!o.optBoolean("exists", true) || (n != 0 && n != in)) continue;
            titles.add(o.optString("title"));
        }
        return titles;
    }

    private static List<JSONObject> wsEntries(String host, List<String> titles, int indexNs) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        List<String> idx = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        Object[] ns = wsNs(host);
        String ip = ns[3] + ":";
        for (String t : titles) {
            if (t == null || t.isEmpty()) continue;
            if (indexNs >= 0 && t.startsWith(ip)) { if (idx.size() < 40) idx.add(t); continue; }
            String base = t.contains("/") ? t.substring(0, t.indexOf('/')) : t;
            if (seen.put(base, true) != null) continue;
            out.add(wsEntry(host, base, null, false));
            if (out.size() >= 120) break;
        }
        Map<String, JSONObject> meta = wsIndexMeta(host, idx);
        for (String t : idx) out.add(wsEntry(host, t, meta.get(t), true));
        return out;
    }

    // ================================================================ 📚 shelves by subject (Anil, 22 Nov: "A చేయి")
    // One subject shelf ("📖 నవలలు") asks every free site that has such books and shows them together, a page at a time;
    // a chip shows one site only. Each site's list is kept in memory a while so the next page is quick.

    /** One site's list behind a shelf, filled as he pages. */
    static final class Feed { final List<JSONObject> items = new ArrayList<>(); boolean done; int next = 1; List<?> todo; int at; final long made = System.currentTimeMillis(); }
    interface Fill { void more(Feed f) throws Exception; }
    private static final Map<String, Feed> FEEDS = new LinkedHashMap<String, Feed>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Feed> e) { return size() > 40; }
    };

    /** Items [from, from + n) of a site's list (filling it as needed); more[0]: there is more after them. */
    static List<JSONObject> feedPage(String key, Fill fill, int from, int n, boolean[] more) throws Exception {
        Feed f;
        synchronized (FEEDS) {
            f = FEEDS.get(key);
            if (f == null || System.currentTimeMillis() - f.made > 30 * 60_000L) { f = new Feed(); FEEDS.put(key, f); }
        }
        synchronized (f) {
            for (int tries = 0; !f.done && f.items.size() < from + n && tries < 6; tries++) fill.more(f);
            more[0] = !f.done || f.items.size() > from + n;
            return new ArrayList<>(f.items.subList(Math.min(from, f.items.size()), Math.min(from + n, f.items.size())));
        }
    }

    static final Map<String, String> SITE = new HashMap<>();
    static {
        SITE.put("ws", "తెలుగు వికీసోర్స్"); SITE.put("wse", "English Wikisource"); SITE.put("pg", "Project Gutenberg");
        SITE.put("ia", "Internet Archive"); SITE.put("tt", "తెలుగు థీసిస్"); SITE.put("se", "Standard Ebooks"); SITE.put("km", "కౌముది");
    }

    /** kind "cat:te:novel:all" → {results, problems, browse, page, more, sources}. */
    static JSONObject catBrowse(Context c, String kind, int page) {
        JSONObject out = new JSONObject();
        JSONArray res = new JSONArray(), probs = new JSONArray();
        String[] k = LibShelves.kind(kind);
        LibShelves.Cat cat = LibShelves.find(k[0], k[1]);
        List<String> all = LibShelves.sources(k[0], cat), srcs = new ArrayList<>();
        for (String s : all) if ("all".equals(k[2]) || s.equals(k[2])) srcs.add(s);
        int n = srcs.size() > 1 ? 8 : 24, from = (Math.max(1, page) - 1) * n;
        boolean more = false;
        List<List<JSONObject>> parts = new ArrayList<>();
        for (String s : srcs) {
            try {
                boolean[] m = {false};
                parts.add(feedPage(k[0] + "|" + k[1] + "|" + s, fill(k[0], cat, s), from, n, m));
                more |= m[0];
            } catch (Exception e) { probs.put(SITE.get(s) + " చేరలేదు"); }
        }
        Map<String, Boolean> seen = new HashMap<>();
        for (int i = 0; ; i++) { // (one from each site in turn)
            boolean any = false;
            for (List<JSONObject> l : parts) {
                if (i >= l.size()) continue;
                any = true;
                JSONObject o = l.get(i);
                if (seen.put(o.optString("id"), true) != null) continue;
                JSONObject b = book(c, o.optString("id"));
                try { if (b != null && "ready".equals(b.optString("state"))) o.put("have", true); } catch (Exception ignored) {}
                res.put(o);
            }
            if (!any) break;
        }
        JSONArray sa = new JSONArray();
        for (String s : all) sa.put(s);
        try { return out.put("results", res).put("problems", probs).put("browse", kind).put("page", page).put("more", more).put("sources", sa); } catch (Exception e) { return out; }
    }

    /** How one site fills a subject's list. */
    static Fill fill(String lang, LibShelves.Cat cat, String src) {
        boolean en = "en".equals(lang);
        switch (src) {
            case "ws": case "wse": {
                String host = "wse".equals(src) ? WS_EN : WS_TE;
                return f -> {
                    if (f.todo == null) f.todo = wsCatTitles(host, cat.ws, cat.wsLinks);
                    @SuppressWarnings("unchecked") List<String> t = (List<String>) f.todo;
                    int to = Math.min(t.size(), f.at + 24);
                    f.items.addAll(wsEntries(host, t.subList(f.at, to), (Integer) wsNs(host)[2]));
                    f.at = to;
                    if (f.at >= t.size()) f.done = true;
                };
            }
            case "pg": return f -> {
                JSONObject g = getJson("https://gutendex.com/books?languages=" + (en ? "en" : "te") + (cat.pg == null || cat.pg.isEmpty() ? "" : "&topic=" + enc(cat.pg)) + "&page=" + f.next);
                JSONArray r = g.optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) f.items.add(LibText.fromGutendex(r.getJSONObject(i)));
                f.next++;
                if (r == null || r.length() == 0 || g.isNull("next") || !g.has("next")) f.done = true;
            };
            case "ia": return f -> {
                JSONArray d = iaDocs(LibShelves.iaQuery(cat.ia), 30, f.next);
                f.items.addAll(iaEntries(d, 30));
                f.next++;
                if (d.length() < 30) f.done = true;
            };
            case "tt": return f -> ttMore(f, cat.tt);
            case "km": return f -> kmMore(f, cat.km);
            case "se": return f -> seMore(f, cat.se);
            default: return f -> f.done = true;
        }
    }

    /** A Wikisource's books for these category words (categories found by name there) and featured pages. */
    static List<String> wsCatTitles(String host, String[] words, String[] linkPages) throws Exception {
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        if (linkPages != null) for (String p : linkPages) try { titles.addAll(wsLinkTitles(host, p)); } catch (Exception ignored) {}
        if (words != null && words.length > 0) {
            java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
            for (String w : words) {
                if (cats.size() >= 4) break;
                JSONObject j = getJson("https://" + host + "/w/api.php?action=query&list=search&srnamespace=14&srlimit=10&format=json&formatversion=2&srsearch=" + enc("intitle:" + w));
                JSONArray h = j.optJSONObject("query") == null ? null : j.getJSONObject("query").optJSONArray("search");
                for (int i = 0; h != null && i < h.length() && cats.size() < 4; i++) {
                    String t = h.getJSONObject(i).optString("title", "");
                    if (LibShelves.catTitleMatch(t, w)) cats.add(t);
                }
            }
            int in = (Integer) wsNs(host)[2];
            for (String cat : cats) {
                if (titles.size() >= 600) break;
                JSONObject j = getJson("https://" + host + "/w/api.php?action=query&list=categorymembers&cmlimit=200&format=json&formatversion=2&cmnamespace="
                        + enc(in >= 0 ? "0|" + in : "0") + "&cmtitle=" + enc(cat));
                JSONArray m = j.getJSONObject("query").optJSONArray("categorymembers");
                for (int i = 0; m != null && i < m.length(); i++) titles.add(m.getJSONObject(i).optString("title"));
            }
        }
        return new ArrayList<>(titles);
    }

    // ---- teluguthesis.com: the posts under these labels; each post's archive.org copies (free-marked ones download)

    static final String TT = "https://www.teluguthesis.com";

    static void ttMore(Feed f, String[] labels) throws Exception {
        boolean all = labels == null || labels.length == 0;
        if (f.todo == null) {
            Map<String, String[]> posts = new LinkedHashMap<>();
            Exception last = null;
            if (all) {
                for (String[] p : LibShelves.bloggerPosts(getJson(TT + "/feeds/posts/default?alt=json&max-results=50&start-index=1"))) posts.put(p[1], p);
                f.next = 51;
            } else for (String l : labels) {
                try { for (String[] p : LibShelves.bloggerPosts(getJson(TT + "/feeds/posts/default/-/" + enc(l).replace("+", "%20") + "?alt=json&max-results=60"))) posts.put(p[1], p); }
                catch (Exception e) { last = e; }
            }
            if (posts.isEmpty() && last != null) throw last;
            List<String[]> l = new ArrayList<>(posts.values());
            if (!all) java.util.Collections.sort(l, (x, y) -> y[2].compareTo(x[2]));
            f.todo = l;
        }
        @SuppressWarnings("unchecked") List<String[]> l = (List<String[]>) f.todo;
        if (all && f.next > 0 && f.at + 10 > l.size()) { // (every post, newest first: 50 more when these run out)
            List<String[]> got = LibShelves.bloggerPosts(getJson(TT + "/feeds/posts/default?alt=json&max-results=50&start-index=" + f.next));
            l.addAll(got);
            f.next = got.size() < 50 ? -1 : f.next + 50;
        }
        int to = Math.min(l.size(), f.at + 10);
        List<String[]> batch = l.subList(f.at, to);
        List<String> ids = new ArrayList<>();
        for (String[] p : batch) for (String id : LibShelves.archiveIds(p[3])) if (ids.size() < 60) ids.add(id);
        Map<String, JSONObject> lic = iaById(ids);
        for (String[] p : batch) {
            List<String> pid = LibShelves.archiveIds(p[3]);
            String title = LibShelves.postTitle(p[0]);
            if (pid.isEmpty()) { f.items.add(ttCard(title, p[1], null, null, 0, 0)); continue; }
            for (int i = 0; i < pid.size() && i < 12; i++) f.items.add(ttCard(title, p[1], pid.get(i), lic.get(pid.get(i)), i + 1, pid.size()));
        }
        f.at = to;
        if (f.at >= l.size() && (!all || f.next < 0)) f.done = true;
    }

    /** These archive.org items' details (licence, title, author), in one call. */
    static Map<String, JSONObject> iaById(List<String> ids) {
        Map<String, JSONObject> out = new HashMap<>();
        List<String> ok = new ArrayList<>();
        for (String id : ids) if (id.matches("[A-Za-z0-9._-]{3,100}")) ok.add("\"" + id + "\"");
        if (ok.isEmpty()) return out;
        try {
            JSONArray d = getJson("https://archive.org/advancedsearch.php?q=" + enc("identifier:(" + String.join(" OR ", ok) + ")")
                    + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl&fl%5B%5D=date&rows=" + ok.size() + "&output=json")
                    .getJSONObject("response").optJSONArray("docs");
            for (int i = 0; d != null && i < d.length(); i++) { JSONObject o = d.optJSONObject(i); if (o != null) out.put(o.optString("identifier"), o); }
        } catch (Exception ignored) {} // (not known: shown as details only; the download checks again anyway)
        return out;
    }

    /** One teluguthesis book: free-marked on archive.org → it downloads from there; otherwise details and the post's link only. */
    static JSONObject ttCard(String title, String post, String iaId, JSONObject doc, int part, int parts) throws Exception {
        String t = title + (parts > 1 ? " · భాగం " + part : "");
        boolean free = iaId != null && doc != null && LibText.iaFree(one(doc, "licenseurl"));
        JSONObject o = new JSONObject().put("t", t).put("a", doc == null ? "" : one(doc, "creator")).put("lang", "te").put("sec", "telugu").put("era", "modern")
                .put("via", "tt").put("url", post);
        if (free) o.put("id", LibText.idFrom("ia", iaId)).put("src", "ia").put("src_id", iaId).put("free", true).put("cover_url", "https://archive.org/services/img/" + iaId);
        else o.put("id", LibText.idFrom("tt", post + "#" + part)).put("src", "tt").put("free", false).put("mark", "nolicence");
        return o;
    }

    // ---- Standard Ebooks: a subject's books (their own list pages; free, no login)

    static void seMore(Feed f, String subject) throws Exception {
        String url = subject == null || subject.isEmpty() ? "https://standardebooks.org/ebooks?page=" + f.next : "https://standardebooks.org/subjects/" + subject + "?page=" + f.next;
        String html = new String(get(url, 3_000_000), StandardCharsets.UTF_8);
        List<String[]> l = LibShelves.seList(html);
        for (String[] b : l)
            f.items.add(new JSONObject().put("id", LibText.idFrom("se", b[0])).put("t", b[1]).put("a", b[2]).put("src", "se").put("src_id", b[0])
                    .put("lang", "en").put("sec", "fic").put("era", "modern").put("free", true));
        if (l.isEmpty() || !LibShelves.seMore(html, f.next)) f.done = true;
        f.next++;
    }

    // ---- PDF books from publishers' own free libraries (Anil, 22 Nov: "A"): 🌐 read on their site (₹0), or 📄 the PDF onto his
    // rack through ➕'s own form (English pages read by the phone ₹0; Telugu pages by his AI, the cost shown first, his tap).

    static final String KOUMUDI = "https://www.koumudi.net/library.html", MIA_TE = "https://www.marxists.org/telugu/index.htm";

    /** Koumudi's library (one page of 261 PDFs); section null = every book. */
    static void kmMore(Feed f, String section) throws Exception {
        for (String[] b : LibShelves.koumudi(new String(get(KOUMUDI, 4_000_000), StandardCharsets.UTF_8))) {
            if (section != null && !section.equals(b[2])) continue;
            JSONObject o = new JSONObject().put("id", LibText.idFrom("km", b[0])).put("t", b[1]).put("a", b[4]).put("src", "km").put("lang", "te").put("sec", "telugu")
                    .put("era", "now").put("free", false).put("mark", "pdf").put("pdf", b[0]);
            if (!b[3].isEmpty()) o.put("cover_url", b[3]);
            f.items.add(o);
        }
        f.done = true;
    }

    /** Marxists Internet Archive's Telugu section: PDFs (📄) and its pages to read on the site (🌐). */
    static void miaMore(Feed f) throws Exception {
        for (String[] b : LibShelves.mia(new String(get(MIA_TE, 2_000_000), StandardCharsets.UTF_8), MIA_TE)) {
            JSONObject o = new JSONObject().put("id", LibText.idFrom("mia", b[0])).put("t", b[1]).put("a", "").put("src", "mia").put("lang", "te").put("sec", "telugu")
                    .put("era", "modern").put("free", false);
            if ("pdf".equals(b[2])) o.put("mark", "pdf").put("pdf", b[0]); else o.put("mark", "site").put("url", b[0]);
            f.items.add(o);
        }
        f.done = true;
    }

    static boolean pdfAllowed(String url) { return LibShelves.pdfAllowed(url); }

    /** A PDF onto a file (streamed; at most max bytes), telling how far it got. */
    static void getTo(String url, File out, long max, Progress p) throws IOException {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!u.startsWith("https://")) throw new IOException("https కాని లింక్");
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", UA);
                int s = c.getResponseCode();
                if (s >= 300 && s < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("HTTP " + s + " (" + host(u) + ")");
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (s >= 400) throw new IOException("HTTP " + s + " (" + host(u) + ")");
                long all = c.getContentLengthLong();
                if (all > max) throw new IOException("PDF చాలా పెద్దది (" + (all >> 20) + " MB)");
                try (InputStream in = c.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    long got = 0, told = 0;
                    for (int r; (r = in.read(buf)) > 0; ) {
                        o.write(buf, 0, r);
                        got += r;
                        if (got > max) throw new IOException("PDF చాలా పెద్దది");
                        if (got - told > 1_000_000) { told = got; p.at("⬇️ PDF దించుతున్నాను… " + (got >> 20) + (all > 0 ? " / " + (all >> 20) : "") + " MB"); }
                    }
                }
                return;
            } finally { c.disconnect(); }
        }
        throw new IOException("చాలా redirects");
    }

    /** The shelves by site that are new on 22 Nov: all of teluguthesis, Gutenberg's most-read English books, Standard Ebooks. */
    static JSONObject siteBrowse(Context c, String kind, int page) {
        JSONObject out = new JSONObject();
        JSONArray res = new JSONArray(), probs = new JSONArray();
        boolean[] m = {false};
        try {
            Fill fl = "tt_all".equals(kind) ? (f -> ttMore(f, null)) : "se_all".equals(kind) ? (f -> seMore(f, "")) : "km_all".equals(kind) ? (f -> kmMore(f, null))
                    : "mia_te".equals(kind) ? Library::miaMore : fill("en", LibShelves.find("en", "classic"), "pg");
            for (JSONObject o : feedPage("site|" + kind, fl, (Math.max(1, page) - 1) * 24, 24, m)) {
                JSONObject b = book(c, o.optString("id"));
                if (b != null && "ready".equals(b.optString("state"))) o.put("have", true);
                res.put(o);
            }
        } catch (Exception e) { probs.put(e.getMessage() == null ? "చేరలేదు" : e.getMessage()); }
        try { return out.put("results", res).put("problems", probs).put("browse", kind).put("page", page).put("more", m[0]); } catch (Exception e) { return out; }
    }

    // ================================================================ ⬇️ a free book onto the phone

    interface Progress { void at(String what); }

    /** Fetches a free book (a starter / search entry), cuts it into chapters, keeps it; returns its shelf entry. */
    static JSONObject download(Context c, JSONObject entry, Progress p) throws Exception {
        String src = entry.optString("src", "pg");
        String id = entry.optString("id");
        if (!LibText.goodId(id)) throw new IOException("పుస్తకం గుర్తు సరిగా లేదు");
        JSONObject b = new JSONObject(entry.toString());
        b.remove("rack");
        b.remove("have");
        List<String[]> ch;
        String coverUrl = entry.optString("cover_url", "");
        if ("pg".equals(src)) {
            JSONObject g = entry.has("txt") || entry.has("epub") ? entry : null;
            String pid = entry.optString("src_id", "");
            if (!pid.matches("\\d{1,6}")) pid = entry.optString("pg", ""); // (a rack book: its Project Gutenberg number)
            if (g == null && pid.matches("\\d{1,6}")) g = LibText.pgLinks(pid);
            ch = null;
            String problem = null;
            if (g != null) {
                try { ch = pgText(g, entry.optString("t"), p); }
                catch (Exception e) { problem = e.getMessage(); }
            }
            if (ch == null || ch.isEmpty()) { // (no number, or the file wasn't this book: find a free copy another way)
                p.at("Project Gutenberg లో వెతుకుతున్నాను…");
                JSONObject f = bestFree(entry.optString("q", entry.optString("t")), entry.optString("t"), entry.optString("a"));
                if (f == null && problem != null) throw new IOException(problem);
                if (f == null) throw new IOException("ఈ పుస్తకం ఉచిత కాపీ ఇప్పుడు దొరకలేదు");
                if (!f.optString("src_id").equals(g == null ? "" : g.optString("src_id"))) { g = f; ch = pgText(g, entry.optString("t"), p); }
                else if (problem != null) throw new IOException(problem);
            }
            b.put("src_id", g.optString("src_id"));
            if (coverUrl.isEmpty()) coverUrl = g.optString("cover_url", "");
            if (!b.has("summary") && g.has("summary")) b.put("summary", g.optString("summary"));
            b.put("credit", "Project Gutenberg (ఉచితం, కాపీరైట్ లేదు)");
        } else if ("se".equals(src)) {
            String sid = entry.optString("src_id", "");
            if (!sid.matches("[a-z0-9\\-]+/[a-z0-9\\-/]+")) throw new IOException("Standard Ebooks గుర్తు సరిగా లేదు");
            p.at("⬇️ దించుతున్నాను (Standard Ebooks)…");
            ch = epubChapters(get("https://standardebooks.org/ebooks/" + sid + "/downloads/" + sid.replace('/', '_') + ".epub", MAX_BOOK), null);
            b.put("credit", "Standard Ebooks (ఉచితం, కాపీరైట్ లేదు)");
        } else if ("ws".equals(src)) {
            String title = entry.optString("src_id", "");
            if (title.isEmpty()) {
                p.at("తెలుగు వికీసోర్స్‌లో వెతుకుతున్నాను…");
                title = bestWikisource(entry.optString("q", entry.optString("t")));
            }
            if (title != null) {
                b.put("src_id", title);
                ch = wikisource(title, p);
                b.put("credit", "తెలుగు వికీసోర్స్ (ఉచితం)");
            } else { // (not typed as a book page yet: its scan's typed pages)
                p.at("తెలుగు వికీసోర్స్ స్కాన్ పుస్తకాల్లో వెతుకుతున్నాను…");
                String idx = bestIndex(WS_TE, entry.optString("q", entry.optString("t")));
                if (idx == null) throw new IOException("ఈ పుస్తకం తెలుగు వికీసోర్స్‌లో ఇప్పుడు దొరకలేదు");
                b.put("src", "wsi").put("src_id", idx).put("host", WS_TE);
                ch = wsScan(WS_TE, idx, p);
                b.put("credit", "తెలుగు వికీసోర్స్ · స్కాన్ నుంచి టైప్ చేసిన పుటలు (ఉచితం)");
            }
        } else if ("wsi".equals(src)) {
            String host = WS_EN.equals(entry.optString("host")) ? WS_EN : WS_TE;
            ch = wsScan(host, entry.optString("src_id", ""), p);
            b.put("credit", (WS_TE.equals(host) ? "తెలుగు వికీసోర్స్" : "Wikisource") + " · స్కాన్ నుంచి టైప్ చేసిన పుటలు (ఉచితం)");
        } else if ("wse".equals(src)) {
            String title = entry.optString("src_id", "");
            if (title.isEmpty()) throw new IOException("Wikisource పుస్తకం గుర్తు సరిగా లేదు");
            ch = wikisource(WS_EN, title, p);
            b.put("credit", "English Wikisource (ఉచితం, కాపీరైట్ లేదు)");
        } else if ("ia".equals(src)) {
            ch = iaBook(entry.optString("src_id", ""), entry.optString("t"), p);
            b.put("credit", "Internet Archive · ఉచిత లైసెన్స్ ఉన్న పుస్తకం");
        } else throw new IOException("ఈ పుస్తకం ఉచితంగా దించుకునేది కాదు");
        if (ch == null || ch.isEmpty()) throw new IOException("పుస్తకంలో చదవగలిగే అక్షరాలు దొరకలేదు");
        p.at("రాక్‌లో పెడుతున్నాను…");
        long[] n = saveChapters(c, id, ch);
        b.put("n", (int) n[0]).put("chars", n[1]).put("state", "ready").put("wish", false).put("at", System.currentTimeMillis());
        if (!coverUrl.isEmpty() && coverUrl.startsWith("https://")) {
            try { byte[] img = getCover(coverUrl, MAX_COVER); if (img.length > 500) { Files.write(coverFile(c, id).toPath(), img); b.put("cover", true); } } catch (Exception ignored) {}
        }
        put(c, b);
        return b;
    }

    /** One Project Gutenberg book's chapters (plain text, else its EPUB); its own "Title:" must be the book wanted. */
    static List<String[]> pgText(JSONObject g, String wanted, Progress p) throws Exception {
        List<String[]> ch = null;
        String txt = g.optString("txt", "");
        if (!txt.isEmpty()) {
            p.at("⬇️ Project Gutenberg నుంచి దించుతున్నాను…");
            // (its own address, the same file on Gutenberg's second site, then the other standard address)
            String text = new String(pgGet(LibText.pgAddresses(txt, g.optString("txt2", "")), MAX_BOOK, p), StandardCharsets.UTF_8);
            String own = LibText.pgTitle(text);
            if (wanted != null && !wanted.isEmpty() && !own.isEmpty() && !LibText.sameBook(wanted, own))
                throw new IOException("Project Gutenberg లో ఆ సంఖ్యలో వేరే పుస్తకం ఉంది (" + LibText.cut(own, 60) + ")");
            ch = LibText.chapters(LibText.stripGutenberg(text));
        }
        if ((ch == null || ch.isEmpty()) && !g.optString("epub").isEmpty()) {
            p.at("⬇️ దించుతున్నాను (EPUB)…");
            ch = epubChapters(pgGet(LibText.pgAddresses(g.optString("epub"), ""), MAX_BOOK, p), null);
        }
        return ch;
    }

    /** Until this time www.gutenberg.org is taken as down (it failed a moment ago): its second site is tried first. */
    private static volatile long pgDownUntil = 0;

    /** One file from the first address that answers; when Gutenberg's main site fails, the same file from gutenberg.pglaf.org. */
    static byte[] pgGet(List<String> urls, int max, Progress p) throws IOException {
        List<String> order = new ArrayList<>(urls);
        if (System.currentTimeMillis() < pgDownUntil) { // (the main site just failed: its second site first, the main one after)
            List<String> mirrors = new ArrayList<>();
            for (String u : urls) if (LibText.isPgMirror(u)) mirrors.add(u);
            order.removeAll(mirrors);
            order.addAll(0, mirrors);
        }
        IOException own = null, other = null; // (own: an address that is not the second site — its problem is the one told)
        boolean triedMirror = false;
        for (String u : order) {
            boolean mirror = LibText.isPgMirror(u);
            if (mirror && p != null) p.at("⬇️ Gutenberg రెండో సైట్ (gutenberg.pglaf.org) నుంచి దించుతున్నాను…");
            try { return get(u, max); }
            catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (LibText.isPgMain(u) && !m.startsWith("HTTP 4")) pgDownUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
                if (mirror) { triedMirror = true; if (other == null) other = e; }
                else if (own == null) own = e;
            }
        }
        IOException told = own != null ? own : other;
        if (told == null) throw new IOException("దించే లింక్ లేదు");
        if (triedMirror && own != null) throw new IOException(own.getMessage() + " · రెండో సైట్ (gutenberg.pglaf.org) నుంచి కూడా రాలేదు");
        throw told;
    }

    /** A cover picture: its own address, else (a Gutenberg cover) the same picture from the second site. */
    static byte[] getCover(String url, int max) throws IOException {
        return pgGet(LibText.pgAddresses(url, ""), max, null);
    }

    /** A free copy of this book: Gutendex (Gutenberg's own feed when it is down), else Open Library's link to Project Gutenberg. */
    static JSONObject bestFree(String q, String title, String author) throws Exception {
        JSONObject g = null;
        Exception first = null;
        try { g = bestGutendex(q, title, author); } catch (Exception e) { first = e; }
        if (g != null) return g;
        try {
            JSONArray docs = getJson("https://openlibrary.org/search.json?limit=10&fields=key,title,author_name,first_publish_year,id_project_gutenberg&q="
                    + enc(title + (author == null || author.isEmpty() ? "" : " " + author))).optJSONArray("docs");
            JSONObject best = null;
            double bs = 0;
            for (int i = 0; docs != null && i < docs.length(); i++) {
                JSONObject o = LibText.fromOpenLibrary(docs.getJSONObject(i));
                if (!"pg".equals(o.optString("src"))) continue;
                double s = LibText.score(title, o.optString("t"), "") + 0.3 * LibText.score(author == null ? "" : author, o.optString("a"), "");
                if (s > bs) { bs = s; best = o; }
            }
            if (best != null && bs >= 0.6) {
                JSONObject l = LibText.pgLinks(best.optString("src_id"));
                l.put("t", best.optString("t")).put("a", best.optString("a"));
                return l;
            }
        } catch (Exception e) { if (first == null) first = e; }
        if (first != null) throw first;
        return null;
    }

    /** The Gutendex hit that is this book (title words, then the author), or null; Project Gutenberg's own search when Gutendex is down. */
    static JSONObject bestGutendex(String q, String title, String author) throws Exception {
        List<JSONObject> hits = new ArrayList<>();
        try {
            JSONArray r = getJson("https://gutendex.com/books?search=" + enc(q)).optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) hits.add(LibText.fromGutendex(r.getJSONObject(i)));
        } catch (Exception e) {
            hits = LibText.fromOpds(new String(get("https://www.gutenberg.org/ebooks/search.opds/?query=" + enc(q), 3_000_000), StandardCharsets.UTF_8));
            if (hits.isEmpty()) throw e;
        }
        JSONObject best = null;
        double bestScore = 0;
        for (JSONObject g : hits) {
            double s = LibText.score(title, g.optString("t"), "") + 0.3 * LibText.score(author == null ? "" : author, g.optString("a"), "")
                    + ("en".equals(g.optString("lang")) ? 0.05 : 0) + Math.min(0.05, g.optInt("downloads") / 400000.0);
            if (s > bestScore) { bestScore = s; best = g; }
        }
        return bestScore >= 0.6 ? best : null;
    }

    static String bestWikisource(String q) throws Exception {
        JSONObject w = getJson("https://te.wikisource.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=8&format=json&formatversion=2&srsearch=" + enc(q));
        JSONArray hits = w.optJSONObject("query") == null ? null : w.getJSONObject("query").optJSONArray("search");
        String best = null;
        double bs = 0;
        for (int i = 0; hits != null && i < hits.length(); i++) {
            String t = hits.getJSONObject(i).optString("title", "");
            String base = t.contains("/") ? t.substring(0, t.indexOf('/')) : t;
            double s = LibText.score(q, base, "") - (t.contains("/") ? 0.05 : 0);
            if (s > bs) { bs = s; best = base; }
        }
        return bs >= 0.6 ? best : null;
    }

    /** A Telugu Wikisource book: its own page, and its sub-pages in the order the page lists them (≤ 150). */
    static List<String[]> wikisource(String title, Progress p) throws Exception { return wikisource(WS_TE, title, p); }

    static List<String[]> wikisource(String host, String title, Progress p) throws Exception {
        p.at("⬇️ " + (WS_TE.equals(host) ? "తెలుగు వికీసోర్స్" : "English Wikisource") + " నుంచి దించుతున్నాను…");
        String html = wsPage(host, title);
        List<String> subs = LibText.subpages(html, title);
        String prefix = title.replace(' ', '_') + "/";
        List<String[]> out = new ArrayList<>();
        String own = LibText.htmlToText(html);
        if (subs.size() >= 2) {
            if (own.length() > 1500) out.add(new String[]{title, own});
            for (int i = 0; i < subs.size(); i++) {
                p.at("⬇️ " + (i + 1) + " / " + subs.size() + " భాగాలు…");
                try {
                    String t = LibText.htmlToText(wsPage(host, subs.get(i)));
                    if (t.length() > 100) out.add(new String[]{subs.get(i).substring(prefix.length()).replace('_', ' '), t});
                } catch (Exception ignored) {}
                Thread.sleep(250); // (gently: one page at a time)
            }
            return LibText.bound(out);
        }
        return LibText.bound(LibText.parts(own, ""));
    }

    private static String wsPage(String host, String title) throws Exception {
        JSONObject j = getJson("https://" + host + "/w/api.php?action=parse&prop=text&redirects=1&format=json&formatversion=2&page=" + enc(title));
        JSONObject p = j.optJSONObject("parse");
        if (p == null) throw new IOException("వికీసోర్స్ పేజీ దొరకలేదు");
        Object t = p.opt("text");
        return t instanceof JSONObject ? ((JSONObject) t).optString("*", "") : String.valueOf(t);
    }

    /** An EPUB's chapters in reading order ({heading, text}); its title / author into meta when given. */
    static List<String[]> epubChapters(byte[] epub, JSONObject meta) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(epub))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName(), low = n.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".opf") || low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm"))) continue;
                files.put(n, new String(readAll(z, 8_000_000), StandardCharsets.UTF_8));
            }
        }
        String opfPath = "";
        for (String n : files.keySet()) if (n.toLowerCase(Locale.ROOT).endsWith(".opf")) { opfPath = n; break; }
        List<String> order = new ArrayList<>();
        if (!opfPath.isEmpty()) {
            String opf = files.get(opfPath), base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            if (meta != null) {
                java.util.regex.Matcher t = java.util.regex.Pattern.compile("(?is)<dc:title[^>]*>(.*?)</dc:title>").matcher(opf);
                if (t.find()) meta.put("t", LibText.entities(t.group(1)).trim());
                java.util.regex.Matcher a = java.util.regex.Pattern.compile("(?is)<dc:creator[^>]*>(.*?)</dc:creator>").matcher(opf);
                if (a.find()) meta.put("a", LibText.entities(a.group(1)).trim());
            }
            Map<String, String> href = new HashMap<>();
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("<item\\b[^>]*>").matcher(opf);
            while (im.find()) {
                String tag = im.group();
                String id = attr(tag, "id"), h = attr(tag, "href");
                if (!id.isEmpty() && !h.isEmpty()) href.put(id, base + LibText.pct(h));
            }
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("<itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
            while (sm.find()) { String h = href.get(sm.group(1)); if (h != null && files.containsKey(h)) order.add(h); }
        }
        if (order.isEmpty()) { order.addAll(files.keySet()); order.removeIf(n -> !n.toLowerCase(Locale.ROOT).matches(".*\\.(x?html?)$")); java.util.Collections.sort(order); }
        List<String[]> out = new ArrayList<>();
        StringBuilder carry = new StringBuilder();
        for (String n : order) {
            String low = n.toLowerCase(Locale.ROOT);
            if (low.matches(".*(imprint|colophon|uncopyright|titlepage|halftitle|toc|nav|loi|endnotes)\\.x?html?$")) continue;
            String html = files.get(n), text = LibText.htmlToText(html);
            if (text.length() < 300) { if (!text.isEmpty()) carry.append(text).append('\n'); continue; }
            out.add(new String[]{LibText.firstHeading(html), carry + text});
            carry.setLength(0);
        }
        if (out.isEmpty() && carry.length() > 0) out.add(new String[]{"", carry.toString()});
        return LibText.bound(out);
    }

    private static String attr(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    // ================================================================ ➕ his own books from the phone

    /** What he picked: {kind: txt|epub|pdf, pages, t, a} (the slow part runs later in add). */
    static JSONObject inspect(Context c, Uri uri, String name) throws Exception {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String mime = c.getContentResolver().getType(uri);
        String kind = low.endsWith(".epub") || "application/epub+zip".equals(mime) ? "epub" : low.endsWith(".pdf") || "application/pdf".equals(mime) ? "pdf" : "txt";
        JSONObject o = new JSONObject().put("kind", kind).put("t", name == null ? "" : name.replaceAll("\\.[A-Za-z0-9]{2,5}$", "").replace('_', ' ')).put("a", "");
        if ("pdf".equals(kind)) {
            try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "r"); PdfRenderer r = new PdfRenderer(fd)) { o.put("pages", r.getPageCount()); }
        } else if ("epub".equals(kind)) {
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { epubChapters(readAll(in, 30_000_000), o); } catch (Exception ignored) {}
        }
        return o;
    }

    /**
     * Adds his file / page photos as a book. pages: page pictures (a PDF's pages or the scanner's photos); lang "en" read by
     * the phone (₹0), "te" by his AI (asked first on the page). Photos and the PDF are not kept: only the text.
     */
    static JSONObject add(Context c, JSONObject form, Uri file, List<Uri> photos, Progress p, AiJob job) throws Exception {
        String id = "f-" + Long.toString(System.currentTimeMillis(), 36);
        String kind = form.optString("kind", "txt"), lang = "te".equals(form.optString("lang")) ? "te" : "en";
        JSONObject b = new JSONObject().put("id", id).put("t", LibText.cut(form.optString("t", "నా పుస్తకం").trim(), 160)).put("a", LibText.cut(form.optString("a", "").trim(), 100))
                .put("src", photos != null ? "scan" : "file").put("lang", lang).put("sec", form.optString("sec", "mine")).put("era", form.optString("era", "now"))
                .put("at", System.currentTimeMillis()).put("credit", photos != null ? "మీ ఫోటోల నుంచి" : form.optString("credit", "").isEmpty() ? "మీ ఫోన్ నుంచి" : LibText.cut(form.optString("credit"), 80));
        if (b.optString("t").isEmpty()) b.put("t", "నా పుస్తకం");
        if (!java.util.Arrays.asList(LibText.SECTIONS).contains(b.optString("sec"))) b.put("sec", "mine");
        if (!java.util.Arrays.asList(LibText.ERAS).contains(b.optString("era"))) b.put("era", "now");
        List<String[]> ch;
        if (photos == null && !"pdf".equals(kind)) {
            p.at("చదువుతున్నాను…");
            byte[] bytes;
            try (InputStream in = c.getContentResolver().openInputStream(file)) { bytes = readAll(in, 30_000_000); }
            if ("epub".equals(kind)) ch = epubChapters(bytes, null);
            else {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.contains("�")) text = new String(bytes, "windows-1252");
                if (text.contains("PROJECT GUTENBERG")) text = LibText.stripGutenberg(text);
                ch = LibText.chapters(text);
            }
        } else ch = pages(c, file, photos, lang, p, job);
        if (ch == null || ch.isEmpty()) throw new IOException("ఇందులో చదవగలిగే అక్షరాలు దొరకలేదు");
        long[] n = saveChapters(c, id, ch);
        b.put("n", (int) n[0]).put("chars", n[1]).put("state", "ready");
        put(c, b);
        return b;
    }

    /** Page pictures → text: English by the phone's own reader (₹0), Telugu by his AI; 10 pages a chapter. */
    private static List<String[]> pages(Context c, Uri pdf, List<Uri> photos, String lang, Progress p, AiJob job) throws Exception {
        List<String> texts = new ArrayList<>();
        ParcelFileDescriptor fd = null;
        PdfRenderer r = null;
        TextRecognizer rec = "en".equals(lang) ? OfflineRead.recognizer() : null;
        Workshop.Engine e = "te".equals(lang) ? engine(new Prefs(c)) : null;
        try {
            int n;
            if (photos == null) { fd = c.getContentResolver().openFileDescriptor(pdf, "r"); r = new PdfRenderer(fd); n = r.getPageCount(); }
            else n = photos.size();
            n = Math.min(n, 600);
            for (int i = 0; i < n; i++) {
                if (job != null && job.stop) throw new IOException("ఆపేశాను");
                p.at("📄 పేజీ " + (i + 1) + " / " + n + ("te".equals(lang) ? " (AI)" : "") + "…");
                Bitmap bm;
                if (r != null) {
                    try (PdfRenderer.Page pg = r.openPage(i)) {
                        int w = 1400, h = Math.round(w * (pg.getHeight() / (float) Math.max(1, pg.getWidth())));
                        bm = Bitmap.createBitmap(w, Math.min(h, 4000), Bitmap.Config.ARGB_8888);
                        bm.eraseColor(android.graphics.Color.WHITE);
                        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                } else bm = decode(c, photos.get(i));
                if (bm == null) { texts.add(""); continue; }
                String t;
                if (rec != null) { t = OfflineRead.text(rec, bm); if (t == null) t = ""; }
                else {
                    String over = Workshop.overLimit(c, new Prefs(c)); // (this month's limit, checked before every paid page)
                    if (over != null) throw new IOException(over);
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    t = aiPage(c, e, android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP), job);
                }
                bm.recycle();
                texts.add(t.trim());
            }
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
            try { if (fd != null) fd.close(); } catch (Exception ignored) {}
            try { if (rec != null) rec.close(); } catch (Exception ignored) {}
        }
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 10) {
            StringBuilder b = new StringBuilder();
            for (int k = i; k < Math.min(texts.size(), i + 10); k++) if (!texts.get(k).isEmpty()) b.append(LibText.unwrap(texts.get(k))).append('\n');
            if (b.toString().trim().length() > 20) out.add(new String[]{"పేజీలు " + (i + 1) + "–" + Math.min(texts.size(), i + 10), b.toString().trim()});
        }
        return out;
    }

    private static Bitmap decode(Context c, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 2200) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (Exception e) { return null; }
    }

    private static final String PAGE_SYS = "You read a printed book page for a reading app. Output only the page's running text exactly as written "
            + "(Telugu or English), in reading order, as plain paragraphs. Leave out page numbers, headers, footers and figure labels. "
            + "No comments. If the page has no text, output nothing.";

    private static String aiPage(Context c, Workshop.Engine e, String jpeg, AiJob job) throws Exception {
        StringBuilder sb = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        try { Workshop.stream(e, PAGE_SYS, "Read this page.", jpeg, 3000, sb::append, bill); }
        finally { Workshop.spendBooks(c, bill.rupees, false); if (job != null) job.rupees += bill.rupees; }
        return sb.toString();
    }

    /** ₹ for reading that many Telugu page photos with his AI (approximate). */
    static double pagesQuote(Prefs p, int pages) {
        String m = model(p);
        return m.isEmpty() ? 0 : pages * Workshop.rupees(m, 1750, 0, 900);
    }

    // ================================================================ 🇬🇧 → తెలుగు on the phone (₹0, Google's on-phone pack)

    private static Translator phoneTr;

    static synchronized Translator phoneTranslator(Context c) {
        if (!OfflineRead.ready(c)) return null;
        if (phoneTr == null) phoneTr = OfflineRead.translator();
        return phoneTr;
    }

    static synchronized void closeTranslator() {
        try { if (phoneTr != null) phoneTr.close(); } catch (Exception ignored) {}
        phoneTr = null;
    }

    /** Where a book's (or a folder file's) translations are kept: its own folder, or library/tr/<key>. */
    static File trDir(Context c, String key) {
        File d = LibText.goodId(key) && bookDir(c, key).exists() ? new File(bookDir(c, key), "tr") : new File(new File(root(c), "tr"), key.replaceAll("[^a-z0-9\\-]", ""));
        d.mkdirs();
        return d;
    }

    private static final Map<String, String[]> trMem = new LinkedHashMap<String, String[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> e) { return size() > 6; }
    };

    /** Kept translations of one chapter ("te" phone / "ai"), n parts; null when none were kept. */
    static synchronized String[] kept(Context c, String key, String how, int unit, int n) {
        String k = key + "|" + how + "|" + unit;
        String[] m = trMem.get(k);
        if (m != null && m.length == n) return m;
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(new File(trDir(c, key), how + "_" + unit + ".json").toPath()), StandardCharsets.UTF_8));
            JSONArray a = j.getJSONArray("a");
            if (a.length() != n) return null; // (the chapter was cut differently: start again)
            String[] s = new String[n];
            for (int i = 0; i < n; i++) s[i] = a.isNull(i) ? null : a.optString(i, null);
            trMem.put(k, s);
            return s;
        } catch (Exception e) { return null; }
    }

    static synchronized void keep(Context c, String key, String how, int unit, String[] s) {
        trMem.put(key + "|" + how + "|" + unit, s);
        try {
            JSONArray a = new JSONArray();
            for (String x : s) a.put(x == null ? JSONObject.NULL : x);
            Files.write(new File(trDir(c, key), how + "_" + unit + ".json").toPath(), new JSONObject().put("a", a).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** One part in Telugu by the phone (kept); null when the pack isn't on the phone or it failed. Background thread. */
    static String tePart(Context c, String key, int unit, List<String> parts, int k) {
        String[] s = kept(c, key, "te", unit, parts.size());
        if (s != null && s[k] != null) return s[k];
        Translator t = phoneTranslator(c);
        if (t == null) return null;
        String te;
        try { te = Tasks.await(t.translate(parts.get(k)), 30, TimeUnit.SECONDS); } catch (Exception e) { return null; }
        if (te == null || te.trim().isEmpty()) return null;
        if (s == null) s = new String[parts.size()];
        s[k] = te.trim();
        keep(c, key, "te", unit, s);
        return s[k];
    }

    /** Is every part of this chapter in AI Telugu? */
    static boolean aiDone(Context c, String key, int unit, int n) {
        String[] s = kept(c, key, "ai", unit, n);
        if (s == null) return false;
        for (String x : s) if (x == null) return false;
        return true;
    }

    // ================================================================ ✨ తెలుగు by his AI: the cost said first, chapter by chapter

    static final class AiJob {
        final String id;
        final List<Integer> units;
        volatile boolean stop;
        volatile double rupees;
        volatile int done;
        /** Where it is now (for the live bar and the notification): the chapter, its lines done / all, which chapter of the job. */
        volatile int unit = -1, k, n, ci;
        volatile String title = "";
        /** "tr" ✨ AI Telugu, "map" 🗺️ the book's people (chapter by chapter). */
        volatile String kind = "tr";
        AiJob(String id, List<Integer> units) { this.id = id; this.units = units; }
    }

    static volatile AiJob running;

    interface AiWatch { void progress(AiJob j, String line); void finished(AiJob j, String said); }

    static volatile AiWatch aiWatch;

    private static final String TR_SYS = "You translate English book text into natural, simple, flowing Telugu that reads well aloud to a Telugu "
            + "listener. Keep the meaning, the names, the story and the tone; do not add, explain or leave out anything. "
            + "Write each numbered line's Telugu on one line, in the same form: [n] Telugu text. Output nothing else.";

    /** The library's AI: his choice in the library, else the workshop's AI (also his choice); "" when neither is picked (Jarvis never picks). */
    static String model(Prefs p) { return Workshop.modelFor(p, Workshop.BOOKS); }

    /** The library's AI with its key; an honest reason when it can't be used (never another model). */
    static Workshop.Engine engine(Prefs p) throws Workshop.Problem { return Workshop.engine(p, Workshop.BOOKS); }

    /** A model name he can pick (the companies' own ids: letters, digits, . - _ : /). */
    static boolean goodModel(String m) { return m != null && m.matches("[A-Za-z0-9._:\\-/]{1,100}"); }

    /** Letters in one chapter (for the cost of each model). */
    static long chars(Context c, String id, int unit) throws IOException { return chapter(c, id, unit).length(); }

    /** The cost before AI Telugu: {model, lib, ws, chapter: {chars, rupees, done, ws}, book: {chapters, left, chars, rupees}, used, limit}. */
    static JSONObject quote(Context c, String id, int unit) throws Exception {
        Prefs p = new Prefs(c);
        String m = model(p), ws = p.workshopModel().trim().replaceFirst("^models/", "");
        JSONObject o = new JSONObject().put("t", "quote").put("id", id).put("unit", unit).put("model", m)
                .put("lib", p.libraryModel().trim()).put("ws", ws).put("known", !m.isEmpty() && Usage.knownPrice(m))
                .put("used", Math.round(Workshop.spent(c))).put("limit", p.workshopLimit());
        int n = count(c, id);
        long all = 0, left = 0, here = 0;
        int leftN = 0;
        boolean hereDone = false;
        for (int u = 0; u < n; u++) {
            String text = chapter(c, id, u);
            int parts = ReaderService.split(text).size();
            boolean d = aiDone(c, id, u, parts);
            all += text.length();
            if (!d) { left += text.length(); leftN++; }
            if (u == unit) { here = text.length(); hereDone = d; }
        }
        o.put("chapter", new JSONObject().put("chars", here).put("done", hereDone).put("rupees", m.isEmpty() ? 0 : cost(m, here))
                .put("ws", ws.isEmpty() || ws.equals(m) ? 0 : cost(ws, here)));
        o.put("book", new JSONObject().put("chapters", n).put("left", leftN).put("chars", left).put("rupees", m.isEmpty() ? 0 : cost(m, left)));
        if (m.isEmpty()) o.put("why", "లైబ్రరీకి AI ఇంకా ఎంచుకోలేదు Sir. కింద 🤖 మోడల్ ఎంచుకోండి (చౌక మోడల్ అయితే ఖర్చు తక్కువ).");
        String over = Workshop.overLimit(c, p);
        if (over != null) o.put("why", over);
        return o;
    }

    static double cost(String model, long chars) {
        return chars <= 0 ? 0 : Workshop.rupees(model, LibText.tokensIn(chars) + 300 * (chars / 5000 + 1), 0, LibText.tokensOutTelugu(chars));
    }

    /** Starts AI Telugu for those chapters (one job at a time); false when one is running. */
    static synchronized boolean aiStart(Context c, String id, List<Integer> units) { return aiStart(c, id, units, "tr"); }

    static synchronized boolean aiStart(Context c, String id, List<Integer> units, String kind) {
        if (running != null) return false;
        AiJob j = new AiJob(id, units);
        j.kind = "map".equals(kind) ? "map" : "tr";
        running = j;
        Context app = c.getApplicationContext();
        new Thread(() -> { if ("map".equals(j.kind)) aiRunMap(app, j); else aiRun(app, j); }, "jarvis-library-ai").start();
        return true;
    }

    /** 🗺️ The book's people, chapter by chapter (each kept as it comes), then one map; the cost said at the end. */
    private static void aiRunMap(Context c, AiJob j) {
        String said;
        try {
            Prefs p = new Prefs(c);
            Workshop.Engine e = engine(p);
            JSONObject bk = book(c, j.id);
            j.title = bk == null ? "" : bk.optString("t");
            j.n = 0;
            for (int u : j.units) {
                if (j.stop) break;
                j.unit = u; j.ci++;
                String over = Workshop.overLimit(c, p);
                if (over != null) throw new Workshop.Problem(over);
                aiNotice(c, j);
                AiWatch w = aiWatch;
                if (w != null) w.progress(j, "🗺️ అధ్యాయం " + (u + 1) + " (" + j.ci + " / " + j.units.size() + ") · " + Workshop.money(j.rupees));
                j.rupees += LibTools.mapChapter(c, e, j.id, u);
                j.done++;
            }
            JSONObject m = LibTools.mapMerge(c, j.id);
            Workshop.spendBooks(c, 0, true);
            said = (j.stop ? "🗺️ ఆపేశాను Sir. అయిన అధ్యాయాల పాత్రలు దాచాను" : "🗺️ పాత్రల మ్యాప్ అయింది Sir: " + (m.optJSONArray("c") == null ? 0 : m.optJSONArray("c").length()) + " పాత్రలు")
                    + ", ఖర్చు " + Workshop.money(j.rupees) + ".";
        } catch (Workshop.Problem e) {
            LibTools.mapMerge(c, j.id);
            said = e.getMessage() + (j.rupees > 0 ? " (ఇప్పటిదాకా " + Workshop.money(j.rupees) + ")" : "");
        } catch (Exception e) {
            LibTools.mapMerge(c, j.id);
            said = "🗺️ ఆగిపోయింది Sir: " + (e.getMessage() == null ? "సమస్య" : e.getMessage()) + ". ఖర్చు " + Workshop.money(j.rupees) + "; అయినవి దాచాను.";
        }
        running = null;
        try { c.getSystemService(NotificationManager.class).cancel(195); } catch (Exception ignored) {}
        AiWatch w = aiWatch;
        if (w != null) w.finished(j, said);
        if (w == null || !LibraryActivity.front) notifyDone(c, said);
    }

    private static void aiRun(Context c, AiJob j) {
        String said;
        int missing = 0;
        double noted = 0;
        try {
            Prefs p = new Prefs(c);
            Workshop.Engine e = engine(p);
            JSONObject bk = book(c, j.id);
            j.title = bk == null ? "" : bk.optString("t");
            for (int u : j.units) {
                if (j.stop) break;
                String text = chapter(c, j.id, u);
                List<String> parts = ReaderService.split(text);
                String[] s = kept(c, j.id, "ai", u, parts.size());
                if (s == null) s = new String[parts.size()];
                j.unit = u; j.ci++; j.n = parts.size(); j.k = doneCount(s);
                aiNotice(c, j);
                int k = 0;
                while (k < parts.size() && !j.stop) {
                    if (s[k] != null) { k++; continue; }
                    String over = Workshop.overLimit(c, p);
                    if (over != null) throw new Workshop.Problem(over);
                    // a batch: up to 40 parts / about 5,000 letters, skipping ones already done
                    List<Integer> idx = new ArrayList<>();
                    int chars = 0;
                    for (int q = k; q < parts.size() && idx.size() < 40 && chars < 5000; q++) if (s[q] == null) { idx.add(q); chars += parts.get(q).length(); }
                    String[] got = translateBatch(c, e, parts, idx, j);
                    String[] again = null;
                    List<Integer> miss = new ArrayList<>();
                    for (int q = 0; q < idx.size(); q++) if (got[q] == null) miss.add(q);
                    if (!miss.isEmpty() && !j.stop) { // (one more try for the lines that didn't come back, same model)
                        List<Integer> idx2 = new ArrayList<>();
                        for (int q : miss) idx2.add(idx.get(q));
                        again = translateBatch(c, e, parts, idx2, j);
                        for (int q = 0; q < miss.size(); q++) got[miss.get(q)] = again[q];
                    }
                    for (int q = 0; q < idx.size(); q++) { s[idx.get(q)] = got[q]; if (got[q] == null) missing++; }
                    keep(c, j.id, "ai", u, s);
                    k = idx.get(idx.size() - 1) + 1;
                    j.k = doneCount(s);
                    noted = aiTally(c, j, u, s, parts, noted); // (the book's ✨ count, letters and ₹, kept on its shelf entry)
                    aiNotice(c, j);
                    AiWatch w = aiWatch;
                    if (w != null) w.progress(j, "✨ అధ్యాయం " + (u + 1) + ": " + k + " / " + parts.size() + " · " + Workshop.money(j.rupees));
                }
                if (j.stop) break;
                j.done++;
            }
            said = j.stop ? "✨ ఆపేశాను Sir. ఇప్పటిదాకా ఖర్చు " + Workshop.money(j.rupees) + ". అయిన భాగాలు దాచాను."
                    : "✨ AI తెలుగు అయింది Sir: " + j.done + (j.done == 1 ? " అధ్యాయం" : " అధ్యాయాలు") + ", ఖర్చు " + Workshop.money(j.rupees) + "."
                    + (missing > 0 ? " " + missing + " వాక్యాలు అనువాదం కాలేదు (అవి ఇంగ్లీషులోనే చదువుతాను)." : "");
            Workshop.spendBooks(c, 0, true);
        } catch (Workshop.Problem e) {
            said = e.getMessage() + (j.rupees > 0 ? " (ఇప్పటిదాకా " + Workshop.money(j.rupees) + ")" : "");
        } catch (Exception e) {
            said = "✨ AI తెలుగు ఆగిపోయింది Sir: " + (e.getMessage() == null ? "సమస్య" : e.getMessage()) + ". ఖర్చు " + Workshop.money(j.rupees) + "; అయిన భాగాలు దాచాను.";
        }
        if (j.rupees > noted) aiNote(c, j.id, -1, 0, 0, j.rupees - noted); // (a batch that cost and then failed: still in the book's ₹)
        running = null;
        try { c.getSystemService(NotificationManager.class).cancel(195); } catch (Exception ignored) {}
        AiWatch w = aiWatch;
        if (w != null) w.finished(j, said);
        if (w == null || !LibraryActivity.front) notifyDone(c, said); // (the library not in front: the end is told in a notification too)
    }

    // ---- ✨ how far each book's AI Telugu is: kept on its shelf entry, shown on the card, the spine, the reader, the ✨ page

    private static int doneCount(String[] s) { int d = 0; for (String x : s) if (x != null) d++; return d; }

    /** One chapter's state after a batch → the book's entry; returns the job's rupees noted so far. */
    private static double aiTally(Context c, AiJob j, int unit, String[] s, List<String> parts, double noted) {
        int d = 0;
        long ch = 0;
        for (int q = 0; q < s.length; q++) if (s[q] != null) { d++; ch += parts.get(q).length(); }
        int pct = s.length == 0 ? 0 : d == s.length ? 100 : Math.min(99, d * 100 / s.length);
        aiNote(c, j.id, unit, pct, ch, Math.max(0, j.rupees - noted));
        return j.rupees;
    }

    /** ai_u: chapter → % of its lines in AI Telugu; ai_cc: chapter → letters done; ai_rs: ₹ spent on this book (unit < 0: only ₹). */
    static synchronized void aiNote(Context c, String id, int unit, int pct, long chars, double rupees) {
        JSONObject b = book(c, id);
        if (b == null) return;
        try {
            if (unit >= 0) {
                JSONObject u = b.optJSONObject("ai_u"), cc = b.optJSONObject("ai_cc");
                if (u == null) u = new JSONObject();
                if (cc == null) cc = new JSONObject();
                u.put(String.valueOf(unit), pct);
                cc.put(String.valueOf(unit), chars);
                b.put("ai_u", u).put("ai_cc", cc);
            }
            if (rupees > 0) b.put("ai_rs", Math.round((b.optDouble("ai_rs", 0) + rupees) * 100) / 100.0);
            b.put("ai_v", 1);
        } catch (Exception ignored) {}
        put(c, b);
    }

    /** Books whose AI Telugu was made before this tally existed: counted once from their kept files (₹ unknown for those). */
    static void aiScan(Context c) {
        JSONArray sh = shelf(c);
        for (int i = 0; i < sh.length(); i++) {
            JSONObject b = sh.optJSONObject(i);
            if (b == null || b.has("ai_v") || !"ready".equals(b.optString("state"))) continue;
            String id = b.optString("id");
            File[] fs = LibText.goodId(id) ? new File(bookDir(c, id), "tr").listFiles() : null;
            boolean any = false;
            for (int q = 0; fs != null && q < fs.length; q++) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^ai_(\\d{1,4})\\.json$").matcher(fs[q].getName());
                if (!m.find()) continue;
                try {
                    int u = Integer.parseInt(m.group(1));
                    List<String> parts = ReaderService.split(chapter(c, id, u));
                    String[] s = kept(c, id, "ai", u, parts.size());
                    if (s == null) continue;
                    int d = 0;
                    long ch = 0;
                    for (int k = 0; k < s.length; k++) if (s[k] != null) { d++; ch += parts.get(k).length(); }
                    aiNote(c, id, u, s.length == 0 ? 0 : d == s.length ? 100 : Math.min(99, d * 100 / s.length), ch, 0);
                    any = true;
                } catch (Exception ignored) {}
            }
            if (!any) { JSONObject x = book(c, id); if (x != null) { try { x.put("ai_v", 1); put(c, x); } catch (Exception ignored) {} } }
        }
    }

    /** ₹ for one letter of AI Telugu on the library's AI (the page works out "the rest ≈ ₹…" from it); 0 without an AI. */
    static double rate(Prefs p) {
        String m = model(p);
        return m.isEmpty() ? 0 : cost(m, 100_000) / 100_000.0;
    }

    /** The running job, for the live bar: {id, title, unit, k, n, ci, cn, rs, pct (of the whole book), ai fields}; null when none. */
    static JSONObject aiNow(Context c) {
        AiJob j = running;
        if (j == null) return null;
        try {
            JSONObject b = book(c, j.id), o = new JSONObject().put("id", j.id).put("kind", j.kind).put("title", j.title).put("unit", j.unit).put("k", j.k).put("n", j.n)
                    .put("ci", j.ci).put("cn", j.units.size()).put("rs", Math.round(j.rupees * 100) / 100.0);
            if (b != null) {
                long all = b.optLong("chars", 0), done = 0;
                JSONObject cc = b.optJSONObject("ai_cc");
                if (cc != null) for (java.util.Iterator<String> it = cc.keys(); it.hasNext(); ) done += cc.optLong(it.next());
                o.put("pct", all > 0 ? Math.min(100, (int) (done * 100 / all)) : 0);
                JSONObject ai = new JSONObject();
                for (String k : new String[]{"ai_u", "ai_cc", "ai_rs"}) if (b.has(k)) ai.put(k, b.get(k));
                o.put("ai", ai);
            }
            return o;
        } catch (Exception e) { return null; }
    }

    /** The phone's notification while AI Telugu runs: a % bar; a tap opens the ✨ page, ⏹ stops it (its done lines stay). */
    private static void aiNotice(Context c, AiJob j) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_library", "📚 లైబ్రరీ", NotificationManager.IMPORTANCE_LOW));
            boolean map = "map".equals(j.kind);
            int pct = map ? Math.max(0, j.ci - 1) * 100 / Math.max(1, j.units.size()) : j.n == 0 ? 0 : j.k * 100 / j.n;
            android.content.Intent open = new android.content.Intent(c, LibraryActivity.class).putExtra(LibraryActivity.EXTRA_VIEW, "tr")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            android.content.Intent stop = new android.content.Intent(c, LibraryActivity.class).putExtra(LibraryActivity.EXTRA_STOP_AI, true).putExtra(LibraryActivity.EXTRA_VIEW, "tr")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            int fl = android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            String line = "అధ్యాయం " + (j.unit + 1) + (j.units.size() > 1 ? " (" + j.ci + " / " + j.units.size() + ")" : "") + (map ? "" : " · " + j.k + " / " + j.n + " వాక్యాలు") + " · " + Workshop.money(j.rupees);
            nm.notify(195, new Notification.Builder(c, "jarvis_library").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle((map ? "🗺️ పాత్రల మ్యాప్ · " : "✨ AI తెలుగు · ") + LibText.cut(j.title, 60)).setContentText(line)
                    .setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(c, 195, open, fl))
                    .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", android.app.PendingIntent.getActivity(c, 196, stop, fl)).build())
                    .build());
        } catch (Exception ignored) {}
    }

    private static String[] translateBatch(Context c, Workshop.Engine e, List<String> parts, List<Integer> idx, AiJob j) throws Exception {
        List<String> batch = new ArrayList<>();
        int chars = 0;
        for (int q : idx) { batch.add(parts.get(q)); chars += parts.get(q).length(); }
        StringBuilder sb = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        int maxOut = (int) Math.min(16000, LibText.tokensOutTelugu(chars) * 2 + 600);
        try { Workshop.stream(e, TR_SYS, LibText.numberedRequest(batch, 0), maxOut, sb::append, bill); }
        finally { Workshop.spendBooks(c, bill.rupees, false); j.rupees += bill.rupees; }
        return LibText.numbered(sb.toString(), batch.size());
    }

    private static void notifyDone(Context c, String said) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_library", "📚 లైబ్రరీ", NotificationManager.IMPORTANCE_LOW));
            nm.notify(194, new Notification.Builder(c, "jarvis_library").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("📚 Jarvis లైబ్రరీ").setContentText(said).setStyle(new Notification.BigTextStyle().bigText(said)).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ 🗣️ "… పుస్తకం చదువు": which book he means

    /** The best match among his books and the racks: {entry, have}; null when none is close. */
    static JSONObject find(Context c, String name) throws Exception {
        String q = name == null ? "" : name.trim();
        if (q.isEmpty()) return null;
        JSONObject best = null;
        double bs = 0;
        JSONArray sh = shelf(c);
        for (int i = 0; i < sh.length(); i++) {
            JSONObject b = sh.optJSONObject(i);
            if (b == null || !"ready".equals(b.optString("state"))) continue;
            double s = Math.max(LibText.score(q, b.optString("t") + " " + b.optString("tt"), b.optString("a")), LibText.score(q, b.optString("tt"), "")) + 0.02;
            if (s > bs) { bs = s; best = b; }
        }
        JSONArray st = starter(c);
        for (int i = 0; i < st.length(); i++) {
            JSONObject b = st.optJSONObject(i);
            if (b == null) continue;
            double s = Math.max(LibText.score(q, b.optString("t") + " " + b.optString("tt"), b.optString("a")), LibText.score(q, b.optString("tt"), ""));
            if (s > bs) { bs = s; best = b; }
        }
        if (best == null || bs < 0.5) return null;
        JSONObject have = book(c, best.optString("id"));
        boolean ready = have != null && "ready".equals(have.optString("state"));
        return new JSONObject().put("entry", ready ? have : best).put("have", ready);
    }

    /** Library books for the voice: names on his phone. */
    static JSONArray names(Context c) {
        JSONArray a = new JSONArray(), sh = shelf(c);
        for (int i = 0; i < sh.length() && a.length() < 60; i++) {
            JSONObject b = sh.optJSONObject(i);
            if (b != null && "ready".equals(b.optString("state"))) a.put(b.optString("t"));
        }
        return a;
    }
}
