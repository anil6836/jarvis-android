package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anil's own radio list: the Telugu film stations of teluguradios.com ("Online Radio") and the Telugu Christian
 * stations he picked. "రేడియో పెట్టు" shows the list and asks which one; he answers by name or number, or taps it.
 * A station without a known link is looked up on radio-browser.info when he picks it (Telugu matches only);
 * he can also give a link himself ("add"). Links are tried in order; a dead one moves on to the next.
 */
final class Radio {
    private Radio() {}

    static final String FILM = "film", CHRISTIAN = "christian";

    /** {name, links separated by spaces (empty = none found yet), look-up word for radio-browser (empty = the name)}. */
    private static final String[][] FILM_LIST = {
            {"Telugu One Radio", "https://stream.zeno.fm/7kbt507d3qzuv", ""},
            {"Radio Ala 90.8 FM", "https://mahi.radioca.st/stream", ""},
            {"London Telugu Radio", "https://c8.radioboss.fm/stream/33", ""},
            {"Radio Surabhi", "https://radiosurabhi.streamguys1.com/live1-web", ""},
            {"Radio Nyra", "https://s3.radio.co/sdc509ad63/listen", ""},
            {"Telugu Folk", "", ""},
            {"Telugu Time Machine", "", "Time Machine"},
            {"Prema FM", "", ""},
            {"DJ Radio - Suneel Sirthali", "", "Suneel"},
            {"ClubHouse Telugu Radio", "", "ClubHouse Telugu"},
            {"Telugu Vibe Radio", "", "Telugu Vibe"},
            {"Telangana FM", "https://skystream.skonlinedeals.in:8030/radio.mp3", ""},
            {"Telangana NRI Radio", "https://play.radioking.io/telangananriradio", ""},
            {"Radio Sangam", "https://stream.voxx.pro/listen/radio_sangam/radio.mp3", ""},
            {"Swara Radio", "https://s3.radio.co/s162c52c51/listen", ""},
            {"Kushi FM", "https://fm.thamizhinifm.com/listen/kushifm/radio.mp3", ""},
            {"Austin Telugu Radio", "", "Austin Telugu"},
            {"Radio Sphoorty", "", "Sphoorty"},
            {"Radio Manjeera", "", "Manjeera"},
            {"Devi Sri Prasad Hits", "https://stream.zeno.fm/xb1sm55skm0uv", ""},
            {"Mani Sharma Hits", "https://stream.zeno.fm/ypgf9wtshm8uv", ""},
            {"Thaman Hits", "https://stream.zeno.fm/de06kewa5p8uv", ""},
            {"M M Keeravani Hits", "http://cast6.my-control-panel.com:8564/stream", ""},
            {"SPB Hits", "http://cast6.my-control-panel.com:9295/live", ""},
            {"Ilayaraja Hits", "https://stream.zeno.fm/5bc5hwbsqg8uv", ""},
            {"Ghantasala Hits", "http://cast6.my-control-panel.com:7426/live", ""},
            {"AR Rahman Hits", "https://stream.zeno.fm/z78dpahfewzuv", ""},
            {"Telugu Top 40", "https://stream.zeno.fm/rfrzu1lxw8atv", ""},
            {"Madhu Priya Hits", "http://cast6.my-control-panel.com:8245/live", ""},
            {"Mangli Folk Songs", "http://cast6.my-control-panel.com:7134/live", ""},
            {"Telugu Indie Radio", "", "Telugu Indie"},
            {"TALRadio", "http://143.110.156.80:8080/api/v1/talradio/play/telugu", ""},
            {"Venkatesh Comedy", "", "Venkatesh"},
            {"Telugu Comedy", "http://cast6.my-control-panel.com:9287/live", ""},
            {"Geet Radio", "https://server.geetradio.com:8010/stream/1/", ""},
            {"AP 9 FM", "https://stream.ap9fm.in/radio/8000/radio.mp3", ""},
            {"Virijallu Radio", "https://playerservices.streamtheworld.com/api/livestream-redirect/SAM02AAC10_SC", ""},
            {"Radio Kokila", "https://radio.sodhini.com:8000/radio.mp3 https://stream.zeno.fm/suv4q1m26qzuv", ""},
            {"Telugu Melodies", "https://azuracast.vibesounds.in:8020/radio.mp3", ""},
            {"Telugu Melody Radio", "https://a1.asurahosting.com:9580/radio.mp3", ""},
            {"Telugu Radio", "https://stream.zeno.fm/phztcgv6zoxtv", ""},
            {"Telugu Big FM", "https://stream.zeno.fm/zuu89d1pgzzuv", ""},
            {"Telugu Junction", "https://stream.zeno.fm/ige35g4gzhhtv", ""},
            {"Godavari Radio", "https://stream.zeno.fm/xlgwsdncsdptv", ""},
            {"Sakath Radio", "https://stream.zeno.fm/fcsk9ryerd0uv https://stream.zeno.fm/3e6k4ryerd0uv", ""},
            {"Fidaa FM", "https://stream.zeno.fm/kvd13ixo31vvv", ""},
            {"Indra Music Masti", "https://stream.zeno.fm/bv9ccpufh5zuv", ""},
            {"AMR Radio", "https://stream.zeno.fm/vzkbutvwhd0uv https://stream.zeno.fm/ved8mtvwhd0uv", ""},
            {"Golden Hits - Hadassah", "https://stream.zeno.fm/w6yuqq776p8uv", ""},
            {"Anbu FM", "https://stream.zeno.fm/hynsx46sgy8uv", ""},
            {"Isha FM", "https://stream.zeno.fm/rs7nybzazaetv", ""},
            {"Hello Telugu Radio", "https://play.radioking.io/hello-telugu https://listen.radioking.com/radio/503281/stream/560701", ""},
    };

    private static final String[][] CHRISTIAN_LIST = {
            {"Shalom Beats Radios", "", "Shalom"},
            {"Shalom Beats Instrumental", "", "Shalom Beats Instrumental"},
            {"Shalom Beats Radio", "http://rd.shalombeatsradio.com:9190/stream", ""},
            {"Daivamargam Radio", "", "Daivamargam"},
            {"Jayashali Radio", "https://stream.zeno.fm/0zkyt42c8e9uv", ""},
            {"Subhavaartha", "", ""},
            {"Christian Music Radio - Hadassah", "https://stream.zeno.fm/2crqd8y17p8uv", ""},
            {"Sunday School - Hadassah", "https://stream.zeno.fm/hrn4w6m86p8uv", ""},
            {"Telugu Bible Radio", "https://gains.reviveradio.net/proxy/teluguradio?mp=%2Fstream", ""},
            {"Telugu Christian Radio", "https://centova71.instainternet.com/proxy/teluguchristainradio?mp=/stream https://eu2.fastcast4u.com/proxy/teluguchristainradio?mp=/1", ""},
            {"Suvarthamaanam Radio", "https://stream.zeno.fm/xet2s5b8u7zuv https://stream.zeno.fm/h27dx5b8u7zuv", ""},
            {"Telugu Holy Bible", "https://gains.reviveradio.net/proxy/telugubible?mp=/stream", ""},
            {"Voice of Truth Radio", "https://radio.truth.fm:8000/telugu", ""},
            {"Sweety FM", "https://stream.zeno.fm/3fb1379tn7zuv", ""},
            {"Radio Veritas Radio", "", "Veritas"},
            {"TWR Radio", "", "TWR"},
            {"Hand Of Jesus", "https://dc1.serverse.com/proxy/hojtelugu/stream", ""},
    };

    /** Streams that exist only as plain http; network_security_config.xml allows exactly these hosts. */
    private static final String[] HTTP_HOSTS = {"cast6.my-control-panel.com", "rd.shalombeatsradio.com", "143.110.156.80"};

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_radio", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- the list

    /** Every station in order, numbered from 1: {n, name, group, urls[], key, mine}. */
    static JSONArray list(Context c) { return list(c, false); }

    /** withHidden: also the stations he took off (n = 0, hidden = true), to bring one back or give it a link. */
    private static JSONArray list(Context c, boolean withHidden) {
        SharedPreferences s = st(c);
        JSONArray mine = mine(s);
        JSONArray hidden = arr(s.getString("hidden", "[]"));
        JSONArray out = new JSONArray();
        int[] n = {0};
        addGroup(out, n, FILM, FILM_LIST, mine, hidden, s, withHidden);
        addGroup(out, n, CHRISTIAN, CHRISTIAN_LIST, mine, hidden, s, withHidden);
        return out;
    }

    private static void addGroup(JSONArray out, int[] n, String group, String[][] rows, JSONArray mine, JSONArray hidden, SharedPreferences s, boolean withHidden) {
        try {
            List<String> taken = new ArrayList<>();
            for (String[] r : rows) {
                String key = norm(r[0]);
                taken.add(key);
                boolean off = has(hidden, key);
                if (off && !withHidden) continue;
                JSONObject own = byName(mine, key);
                JSONArray urls = own != null ? own.optJSONArray("urls") : split(r[1]);
                if ((urls == null || urls.length() == 0)) urls = split(s.getString("found_" + key, ""));
                out.put(new JSONObject().put("n", off ? 0 : ++n[0]).put("name", r[0]).put("group", group).put("urls", urls == null ? new JSONArray() : urls)
                        .put("key", r[2].isEmpty() ? r[0] : r[2]).put("mine", own != null).put("hidden", off));
            }
            for (int i = 0; i < mine.length(); i++) { // stations he added himself
                JSONObject o = mine.getJSONObject(i);
                String key = norm(o.optString("name"));
                if (!group.equals(o.optString("group", FILM)) || taken.contains(key)) continue;
                boolean off = has(hidden, key);
                if (off && !withHidden) continue;
                taken.add(key);
                out.put(new JSONObject().put("n", off ? 0 : ++n[0]).put("name", o.optString("name")).put("group", group)
                        .put("urls", o.optJSONArray("urls") == null ? new JSONArray() : o.optJSONArray("urls")).put("key", o.optString("name"))
                        .put("mine", true).put("hidden", off));
            }
        } catch (Exception ignored) {}
    }

    static boolean ready(JSONObject st) {
        JSONArray u = st.optJSONArray("urls");
        return u != null && u.length() > 0;
    }

    /** "12 SPB Hits" lines per group, for Jarvis to know the names (not to read them all out). */
    static JSONArray names(JSONArray all, String group) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !group.equals(o.optString("group"))) continue;
            out.put(o.optInt("n") + " " + o.optString("name") + (ready(o) ? "" : " (no link yet)"));
        }
        return out;
    }

    static int count(JSONArray all, String group) {
        int k = 0;
        for (int i = 0; i < all.length(); i++) if (group.equals(all.optJSONObject(i).optString("group"))) k++;
        return k;
    }

    // ---------------------------------------------------------------- finding what he named

    private static final Pattern NUMBER = Pattern.compile("^(?:no\\.?|number|నంబర్|నెంబర్|నం\\.?)?\\s*(\\d{1,3})\\s*(?:వ|వది|వ స్టేషన్|th|st|nd|rd)?$", Pattern.CASE_INSENSITIVE);
    private static final String[] GENERIC = {"radio", "fm", "hits", "hit", "songs", "song", "the", "రేడియో", "ఎఫ్ఎం", "హిట్స్", "పాటలు", "స్టేషన్", "station"};

    /** The station he named: its number, its full name, or a clear part of it ("SPB", "Keeravani", "Radio Ala"). */
    static JSONObject find(Context c, String want) {
        return find(list(c), want);
    }

    static JSONObject find(JSONArray all, String want) {
        String w = want == null ? "" : want.trim();
        if (w.isEmpty()) return null;
        Matcher m = NUMBER.matcher(w);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            for (int i = 0; n > 0 && i < all.length(); i++) if (all.optJSONObject(i).optInt("n") == n) return all.optJSONObject(i);
            return null;
        }
        String nw = norm(w), kw = key(w);
        for (int i = 0; i < all.length(); i++) if (norm(all.optJSONObject(i).optString("name")).equals(nw)) return all.optJSONObject(i);
        if (kw.length() < 2 || kw.equals("telugu") || kw.equals("తెలుగు")) return null; // too general to pick one
        for (int i = 0; i < all.length(); i++) if (key(all.optJSONObject(i).optString("name")).equals(kw)) return all.optJSONObject(i);
        JSONObject best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            String k = key(o.optString("name"));
            if (k.length() < 3 || k.equals("telugu")) continue;
            boolean hit = (kw.length() >= 3 && k.contains(kw)) || nw.contains(k);
            if (hit && Math.abs(k.length() - kw.length()) < bestDiff) { best = o; bestDiff = Math.abs(k.length() - kw.length()); }
        }
        return best;
    }

    /** He said this station's exact name or its number (not just a part of the name). */
    static boolean exact(JSONObject st, String want) {
        if (st == null || want == null) return false;
        return norm(st.optString("name")).equals(norm(want)) || NUMBER.matcher(want.trim()).matches();
    }

    /** "క్రిస్టియన్", "సినిమా పాటలు": he named a group, not a station. */
    static String groupWord(String want) {
        String w = want == null ? "" : want.toLowerCase(Locale.ROOT);
        for (String k : new String[]{"christian", "jesus", "bible", "gospel", "క్రిస్టియన్", "క్రైస్తవ", "యేసు", "యేసయ్య", "బైబిల్", "ఆరాధన"}) if (w.contains(k)) return CHRISTIAN;
        for (String k : new String[]{"film", "cinema", "movie", "సినిమా", "మూవీ"}) if (w.contains(k)) return FILM;
        return "";
    }

    private static final java.util.Set<String> GROUP_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "christian", "jesus", "gospel", "devotional", "film", "films", "cinema", "movie", "movies", "songs", "song", "telugu", "radio", "station", "stations", "fm",
            "క్రిస్టియన్", "క్రైస్తవ", "యేసు", "యేసయ్య", "భక్తి", "సినిమా", "సినిమాల", "మూవీ", "పాటలు", "పాట", "తెలుగు", "రేడియో", "స్టేషన్", "స్టేషన్లు"));

    /** He named only a group ("క్రిస్టియన్ పాటలు", "film songs"), nothing that picks one station: that group, else "". */
    static String onlyGroup(String want) {
        String g = groupWord(want);
        if (g.isEmpty()) return "";
        for (String t : want.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) if (!t.isEmpty() && !GROUP_WORDS.contains(t)) return "";
        return g;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]", "");
    }

    private static String key(String s) {
        String t = " " + (s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ")) + " ";
        for (String g : GENERIC) t = t.replace(" " + g + " ", " ");
        return t.replace(" ", "");
    }

    // ---------------------------------------------------------------- links

    /** https, or plain http from the few hosts allowed in network_security_config.xml (Android blocks the rest). */
    static boolean playable(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.startsWith("https://")) return u.length() > 10;
        if (!u.startsWith("http://")) return false;
        String host = u.substring(7).split("[/:?#]", 2)[0].toLowerCase(Locale.ROOT);
        for (String h : HTTP_HOSTS) if (h.equals(host)) return true;
        return false;
    }

    /** The links already known for a station (no network). */
    static String[] known(JSONObject st) {
        JSONArray u = st.optJSONArray("urls");
        String[] out = new String[u == null ? 0 : u.length()];
        for (int i = 0; i < out.length; i++) out[i] = u.optString(i);
        return out;
    }

    /** The links to try for a station; one without a link is looked up (network: call off the main thread). */
    static String[] urls(Context c, JSONObject st) {
        String[] k = known(st);
        if (k.length > 0) return k;
        String found = lookup(st.optString("key", st.optString("name")));
        if (found.isEmpty()) return new String[0];
        remember(c, st.optString("name"), found);
        return new String[]{found};
    }

    static void remember(Context c, String name, String url) {
        try { st(c).edit().putString("found_" + norm(name), url).apply(); } catch (Exception ignored) {}
    }

    /** A Telugu station of that name on radio-browser.info with a stream the phone can play; "" if none. */
    static String lookup(String name) {
        String enc;
        try { enc = URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
        for (String h : new String[]{"de1", "fi1"}) {
            try {
                JSONArray a = new JSONArray(fetch("https://" + h + ".api.radio-browser.info/json/stations/byname/" + enc
                        + "?hidebroken=true&order=clickcount&reverse=true&limit=30"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject s = a.getJSONObject(i);
                    String meta = (s.optString("name") + " " + s.optString("language") + " " + s.optString("tags")).toLowerCase(Locale.ROOT);
                    if (!meta.contains("telugu") || s.optInt("hls") == 1) continue;
                    String u = s.optString("url_resolved");
                    if (u.isEmpty()) u = s.optString("url");
                    if (u.contains(".m3u8") || u.endsWith(".pls") || u.endsWith(".m3u") || !playable(u)) continue;
                    String codec = s.optString("codec").toUpperCase(Locale.ROOT);
                    if (!codec.isEmpty() && !codec.equals("UNKNOWN") && !codec.contains("MP3") && !codec.contains("AAC")) continue;
                    return u.trim();
                }
                return ""; // the server answered: nothing suitable
            } catch (Exception e) { /* try the next server */ }
        }
        return "";
    }

    /** A short GET (the look-up shouldn't keep him waiting long). */
    private static String fetch(String url) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", "JarvisAndroid/1.0");
            if (h.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + h.getResponseCode());
            java.io.InputStream in = h.getInputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) { b.write(buf, 0, r); if (b.size() > 2_000_000) break; }
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /** A looked-up link that didn't play is dropped, so the next time it is looked up again. */
    static void forget(Context c, String name) {
        try { st(c).edit().remove("found_" + norm(name)).apply(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- his own changes

    /** Adds a station, or gives a listed one (also one without a link) his link. Returns an error text, or "" when done. */
    static String add(Context c, String name, String url, String group) throws Exception {
        String nm = name == null ? "" : name.trim();
        String u = url == null ? "" : url.trim();
        if (nm.isEmpty()) return "Need the station name.";
        SharedPreferences s = st(c);
        JSONArray hidden = arr(s.getString("hidden", "[]"));
        // the station he means, also one he took off: by number, full name or a clear part of it
        JSONObject hit = NUMBER.matcher(nm).matches() ? find(list(c), nm) : find(list(c, true), nm);
        if (u.isEmpty()) { // bring a station he took off back
            if (hit == null || !hit.optBoolean("hidden")) return "Need the stream link (https://...) for this station.";
            s.edit().putString("hidden", without(hidden, norm(hit.optString("name"))).toString()).apply();
            return "";
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "That is not a web link. Need the stream link starting with https://";
        if (u.contains(".m3u8")) return "That is an HLS (.m3u8) playlist; need a direct MP3/AAC stream link.";
        if (!playable(u)) return "Android lets apps play only https links (plain http is blocked). Need the https link of this stream.";
        String title = hit != null ? hit.optString("name") : nm;
        String g = hit != null ? hit.optString("group", FILM) : (CHRISTIAN.equals(group) || CHRISTIAN.equals(groupWord(group + " " + nm)) ? CHRISTIAN : FILM);
        JSONArray mine = mine(s), keep = new JSONArray();
        for (int i = 0; i < mine.length(); i++) if (!norm(mine.getJSONObject(i).optString("name")).equals(norm(title))) keep.put(mine.getJSONObject(i));
        keep.put(new JSONObject().put("name", title).put("group", g).put("urls", new JSONArray().put(u)));
        s.edit().putString("mine", keep.toString()).putString("hidden", without(hidden, norm(title)).toString()).remove("found_" + norm(title)).apply();
        return "";
    }

    private static JSONArray without(JSONArray a, String v) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!v.equals(a.optString(i))) out.put(a.optString(i));
        return out;
    }

    /** Takes a station off his list (his own is deleted; a listed one is hidden and can come back with "add"). */
    static String remove(Context c, String want) throws Exception {
        JSONObject stn = find(c, want);
        if (stn == null) return "";
        String name = stn.optString("name");
        SharedPreferences s = st(c);
        JSONArray mine = mine(s), keep = new JSONArray();
        for (int i = 0; i < mine.length(); i++) if (!norm(mine.getJSONObject(i).optString("name")).equals(norm(name))) keep.put(mine.getJSONObject(i));
        JSONArray hidden = arr(s.getString("hidden", "[]"));
        if (!stn.optBoolean("mine") || isListed(name)) { if (!has(hidden, norm(name))) hidden.put(norm(name)); }
        s.edit().putString("mine", keep.toString()).putString("hidden", hidden.toString()).apply();
        return name;
    }

    private static boolean isListed(String name) {
        for (String[][] l : new String[][][]{FILM_LIST, CHRISTIAN_LIST}) for (String[] r : l) if (norm(r[0]).equals(norm(name))) return true;
        return false;
    }

    // ---------------------------------------------------------------- playing, asking

    /** Plays a station; with no links yet, the radio service looks one up itself (so a tap never waits on the network). */
    static void play(Context c, JSONObject st, String[] urls, int minutes) {
        st(c).edit().putString("last", st.optString("name")).apply();
        answered();
        dismissPicker();
        SoundService.radio(c, urls, st.optString("name"), st.optString("key", st.optString("name")), minutes);
    }

    static String last(Context c) { return st(c).getString("last", ""); }

    private static volatile long askedAt;

    /** Jarvis asked "which station?": listen for his answer even with conversation mode off. */
    static void asked() { askedAt = android.os.SystemClock.elapsedRealtime(); }

    static void answered() { askedAt = 0; }

    /** True once after the question (so a stray later reply doesn't keep the mic open). */
    static boolean awaiting() {
        long t = askedAt;
        if (t == 0 || android.os.SystemClock.elapsedRealtime() - t > 3 * 60 * 1000L) return false;
        askedAt = 0;
        return true;
    }

    private static volatile WeakReference<AlertDialog> picker;
    private static volatile long pickerAt;

    static void dismissPicker() {
        WeakReference<AlertDialog> w = picker;
        AlertDialog d = w == null ? null : w.get();
        picker = null;
        if (d == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { if (d.isShowing()) d.dismiss(); } catch (Exception ignored) {} // its window may be gone already
        });
    }

    /** The list is on screen (for at most 2 minutes, so a forgotten list doesn't keep the panel open). */
    static boolean pickerShowing() {
        WeakReference<AlertDialog> w = picker;
        AlertDialog d = w == null ? null : w.get();
        try { return d != null && d.isShowing() && android.os.SystemClock.elapsedRealtime() - pickerAt < 2 * 60 * 1000L; } catch (Exception e) { return false; }
    }

    /** His stations on screen, grouped; a tap plays one. Voice answers work as well. */
    static void showPicker(Activity a, int minutes) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        final Context app = a.getApplicationContext();
        final JSONArray all = list(app);
        a.runOnUiThread(() -> {
            try {
                if (a.isFinishing() || a.isDestroyed()) return;
                dismissPicker();
                final List<Object> rows = new ArrayList<>();
                String group = "";
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    if (!o.optString("group").equals(group)) {
                        group = o.optString("group");
                        rows.add(CHRISTIAN.equals(group) ? "✝️ క్రిస్టియన్ (" + count(all, CHRISTIAN) + ")" : "🎬 సినిమా పాటలు (" + count(all, FILM) + ")");
                    }
                    rows.add(o);
                }
                ArrayAdapter<Object> ad = new ArrayAdapter<Object>(a, android.R.layout.simple_list_item_1, rows) {
                    @Override public boolean areAllItemsEnabled() { return false; }
                    @Override public boolean isEnabled(int p) { return !(getItem(p) instanceof String); }
                    @Override public View getView(int p, View v, ViewGroup parent) {
                        TextView t = (TextView) super.getView(p, v, parent);
                        Object o = getItem(p);
                        if (o instanceof String) {
                            t.setText((String) o);
                            t.setTypeface(null, Typeface.BOLD);
                            t.setTextColor(0xFF60A5FA);
                            t.setTextSize(15);
                        } else {
                            JSONObject s = (JSONObject) o;
                            boolean ok = ready(s);
                            t.setText(s.optInt("n") + ".  " + s.optString("name") + (ok ? "" : "  · లింక్ లేదు"));
                            t.setTypeface(null, Typeface.NORMAL);
                            t.setTextColor(ok ? 0xFFE5E7EB : 0xFF9CA3AF);
                            t.setTextSize(16);
                        }
                        return t;
                    }
                };
                AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("📻 ఏ స్టేషన్ ప్లే చేయమంటారు?")
                        .setAdapter(ad, (x, w) -> {
                            Object o = rows.get(w);
                            if (o instanceof JSONObject) tapped(a, app, (JSONObject) o, minutes);
                        })
                        .setNegativeButton("వద్దు", (x, w) -> answered())
                        .setOnCancelListener(x -> answered()) // back / tap outside: he doesn't want one now
                        .create();
                d.show();
                picker = new WeakReference<>(d);
                pickerAt = android.os.SystemClock.elapsedRealtime();
            } catch (Exception ignored) {}
        });
    }

    /** On the main thread (the list's click): start it now, while Jarvis is on screen; a missing link is looked up by the service. */
    private static void tapped(Activity a, Context app, JSONObject st, int minutes) {
        answered();
        picker = null;
        try {
            play(app, st, known(st), minutes);
        } catch (Exception e) {
            Announcer.say(app, "రేడియో మొదలుపెట్టలేకపోయాను. ఇంకోసారి ప్రయత్నించండి.");
        }
        // Jarvis may be listening for a spoken answer: stop, so the mic doesn't take the radio for his voice.
        if (a instanceof MainActivity) ((MainActivity) a).radioPicked();
        else if (a instanceof SheetActivity) ((SheetActivity) a).radioPicked();
    }

    // ---------------------------------------------------------------- small helpers

    private static JSONArray mine(SharedPreferences s) { return arr(s.getString("mine", "[]")); }

    private static JSONArray arr(String s) {
        try { return new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }

    private static boolean has(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    private static JSONObject byName(JSONArray a, String key) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && norm(o.optString("name")).equals(key)) return o;
        }
        return null;
    }

    private static JSONArray split(String s) {
        JSONArray out = new JSONArray();
        if (s == null) return out;
        for (String p : s.trim().split("\\s+")) if (!p.isEmpty()) out.put(p);
        return out;
    }
}
