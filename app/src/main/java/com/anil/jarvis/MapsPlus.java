package com.anil.jarvis;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 🗺️ Jarvis Maps build 2 — the answers behind the new buttons and words (for the Maps screen and for the brain):
 * every kind of place near him / on his way / near where he is going / in a town he names; which theatres show a
 * movie (his own AI looks up the show times on the web, Google Maps puts the theatres on the map); a trip plan for a
 * later time (traffic then, toll, a meal stop open when he gets there, rain on the way, the bike's battery); the best
 * time to leave; rain on the way; sights on the way; help on the road; his arrival time on amma's tablet.
 * Google with his Maps key (each answer names its source); the free map / router / weather otherwise. Nothing here
 * sets a way, calls or sends by itself: the answers say what to offer, and the brain asks him first.
 * Every call here is network: never on the main thread.
 */
final class MapsPlus {
    private MapsPlus() {}

    private static JSONObject fail(String error, String message) throws Exception {
        return new JSONObject().put("ok", false).put("error", error).put("message", message);
    }

    private static JSONObject noLocation() throws Exception {
        return fail("no_location", "Could not get the phone's location. Is Location on?");
    }

    private static double r5(double v) { return Math.round(v * 100000) / 100000.0; }

    private static String clock(long ms) { return new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(ms)); }

    private static String dayClock(long ms) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(ms);
        boolean today = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
        return (today ? "today " : new java.text.SimpleDateFormat("EEE d MMM ", Locale.ENGLISH).format(new java.util.Date(ms))) + clock(ms);
    }

    // ================================================================ ➕ every kind of place

    /**
     * Places of a kind (or his own words): where = "near" (around him), "along" (on his way ahead), "dest" (near where
     * he is going) or a town / city name. good: only the well-rated (4★ and up, 30+ ratings). freeOnly: the free map
     * even with a key (the Maps screen when Google's map isn't showing).
     */
    static JSONObject places(Context c, String what, String where, boolean good, double km, boolean freeOnly) throws Exception {
        String w = what == null ? "" : what.trim();
        if (w.isEmpty()) return fail("missing", "What kind of place?");
        PlaceKinds.K k = PlaceKinds.byId(w);
        if (k == null) k = PlaceKinds.matchWhole(w);
        String google = k != null ? k.google : Drive.googleWords(w);
        String[] free = k != null ? new String[]{k.osm, k.te} : Drive.kind(w);
        String label = k != null ? k.label() : w;
        int radiusKm = k != null ? k.radiusKm : 10;
        String wh = where == null || where.trim().isEmpty() ? "near" : where.trim();
        String whl = wh.toLowerCase(Locale.ROOT);
        boolean google_ = GMaps.has(c) && !freeOnly;
        JSONObject o;
        if (whl.equals("along") || whl.equals("on_the_way") || whl.contains("దారి")) {
            Location l = Drive.here(c);
            if (l == null) return noLocation();
            double far = km > 0 ? km : Math.max(30, Math.min(150, radiusKm));
            String googleErr = null;
            o = null;
            if (google_) {
                try { o = Drive.googleAlong(c, l, google, label, far, false); } catch (Exception e) { googleErr = GMaps.telugu(e); }
            }
            if (o == null) {
                o = Drive.alongFree(c, l, free, far);
                if (googleErr != null) o.put("google_error", googleErr);
            }
        } else {
            double lat, lon;
            String desc;
            Location me = Drive.here(c);
            if (whl.equals("dest") || whl.equals("destination") || whl.contains("గమ్యం")) {
                JSONObject d = Drive.dest(c);
                if (d == null || !d.has("lat")) return fail("no_destination", "No destination is set now. Ask where (a town name), or search near him.");
                lat = d.optDouble("lat");
                lon = d.optDouble("lon");
                desc = "near his destination " + d.optString("name");
            } else if (whl.equals("near") || whl.equals("here") || whl.equals("me") || whl.startsWith("near") || whl.equals("nearby") || whl.equals("around")
                    || whl.equals("around me") || whl.contains("దగ్గర") || whl.contains("చుట్టూ") || whl.contains("ఇక్కడ")) {
                if (me == null) return noLocation();
                lat = me.getLatitude();
                lon = me.getLongitude();
                desc = "around him (" + radiusKm + " km)";
            } else { // a town or city he named
                double[] ll = Drive.latLon(c, wh);
                if (ll == null && !google_) return fail("not_found", "Could not find the town '" + wh + "'.");
                lat = ll == null ? Double.NaN : ll[0];
                lon = ll == null ? Double.NaN : ll[1];
                desc = "in " + wh;
            }
            o = null;
            String googleErr = null;
            if (google_) {
                try {
                    boolean town = desc.startsWith("in ");
                    List<GMaps.Place> ps = GMaps.search(c, town ? google + " in " + wh : google, lat, lon, radiusKm * 1000.0, null, false, 20, !town, true);
                    List<JSONObject> list = new ArrayList<>();
                    for (GMaps.Place p : ps) {
                        JSONObject it = Drive.item(p);
                        if (me != null) it.put("km", Math.round(MapMath.meters(me.getLatitude(), me.getLongitude(), p.lat, p.lon) / 100) / 10.0);
                        list.add(it);
                    }
                    if (!town) Collections.sort(list, (a, b) -> Double.compare(a.optDouble("km", 1e9), b.optDouble("km", 1e9)));
                    o = new JSONObject().put("ok", true).put("looking_for", label).put("source", "Google Maps").put("searched", desc).put("places", top(list, 10));
                } catch (Exception e) { googleErr = GMaps.telugu(e); }
            }
            if (o == null) {
                if (Double.isNaN(lat)) return fail("not_found", googleErr != null ? googleErr : "Could not find the town '" + wh + "'.");
                o = freeAround(free, lat, lon, Math.min(50, radiusKm) * 1000, me);
                o.put("searched", desc).put("looking_for", label);
                if (googleErr != null) o.put("google_error", googleErr);
            }
        }
        if (good && o.optJSONArray("places") != null) { // ⭐ only the well-rated (Google's ratings; the free map has none)
            JSONArray all = o.optJSONArray("places"), keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.optJSONObject(i);
                if (it != null && it.optDouble("rating", 0) >= 4.0 && it.optInt("ratings", 0) >= 30) keep.put(it);
            }
            o.put("places", keep).put("filter", "only 4★ and up with 30+ ratings");
            if (keep.length() == 0) o.put("note", all.length() == 0 ? o.optString("note", "None found.") : "None of the " + all.length() + " found has 4★ with 30+ ratings (the free map has no ratings).");
        }
        if (k != null && k.id.equals("rooms")) o.put("prices", "Google does not give room prices: say so; the rating, phone and website are here, prices are in his booking app.");
        return o.put("kind_id", k == null ? "" : k.id).put("next", "Say the nearest 3 in short Telugu: name, km (ahead / away), open now (or open when he gets there) if known, "
                + "the rating if there. Offer the way to one (drive navigate with its 'lat,lon') or to call one (its phone, call tool) — only after he says yes.");
    }

    private static JSONArray top(List<JSONObject> list, int n) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && out.length() < n; i++) out.put(list.get(i));
        return out;
    }

    /** The free map's places of a kind around a point (OpenStreetMap), nearest first; km from him when known. */
    static JSONObject freeAround(String[] k, double lat, double lon, double radiusM, Location me) throws Exception {
        if (k == null) return fail("missing", "What kind of place?");
        // (the map's answer is in no order: a small circle first, so the nearest are never cut off; the whole circle only when it has few)
        JSONArray el = radiusM > 4000 ? Drive.overpass(String.format(Locale.ENGLISH, "[out:json][timeout:25];nwr(around:%d,%.6f,%.6f)%s;out center 120;", 4000, lat, lon, k[0])) : null;
        if (el == null || el.length() < 5) el = Drive.overpass(String.format(Locale.ENGLISH, "[out:json][timeout:25];nwr(around:%d,%.6f,%.6f)%s;out center 200;", (int) radiusM, lat, lon, k[0]));
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.getJSONObject(i);
            JSONObject cc = e.has("lat") ? e : e.optJSONObject("center");
            if (cc == null) continue;
            JSONObject t = e.optJSONObject("tags");
            if (t == null) t = new JSONObject();
            String name = t.optString("name:te", t.optString("name", t.optString("brand", "")));
            double la = cc.optDouble("lat"), lo = cc.optDouble("lon");
            JSONObject it = new JSONObject().put("name", name.isEmpty() ? "(పేరు లేదు)" : name).put("lat", r5(la)).put("lon", r5(lo));
            double fromLat = me != null ? me.getLatitude() : lat, fromLon = me != null ? me.getLongitude() : lon;
            it.put("km", Math.round(MapMath.meters(fromLat, fromLon, la, lo) / 100) / 10.0);
            String ph = t.optString("phone", t.optString("contact:phone", ""));
            if (!ph.isEmpty()) it.put("phone", ph);
            if (!t.optString("opening_hours").isEmpty()) it.put("hours", t.optString("opening_hours"));
            if ("24/7".equals(t.optString("opening_hours"))) it.put("open_24h", true);
            if (!t.optString("website").isEmpty()) it.put("website", t.optString("website"));
            list.add(it);
        }
        Collections.sort(list, (a, b) -> Double.compare(a.optDouble("km"), b.optDouble("km")));
        JSONObject o = new JSONObject().put("ok", true).put("source", "free map (OpenStreetMap)").put("places", top(list, 10));
        if (list.isEmpty()) o.put("note", "Nothing of this kind on the free map here (rural places are often missing). Say so honestly.");
        return o;
    }

    // ================================================================ 🎬 which theatres show a movie

    private static final String[] MOVIE_WORDS = {"సినిమా", "సినిమాకి", "సినిమాకు", "movie", "film", "చిత్రం", "picture"};
    private static final String[] MOVIE_FILL = {"ఎక్కడ", "ఆడుతోంది", "ఆడుతుంది", "ఏ", "ఏఏ", "థియేటర్‌లో", "థియేటర్లో", "థియేటర్లలో", "థియేటర్స్", "థియేటర్లు", "ఉంది", "ఉందో",
            "showing", "where", "is", "playing", "shows", "show", "timings", "times", "టైమింగ్స్", "షో", "షోలు", "షోస్", "ఈ", "రోజు", "today", "tomorrow", "రేపు",
            "near", "me", "దగ్గర", "దగ్గర్లో", "నా", "దారిలో", "చెప్పు", "చూపించు", "the", "in", "at", "of", "for", "కి", "లో", "టికెట్లు", "tickets",
            "హాల్", "హాల్స్", "hall", "halls", "cinema", "theatre", "theater", "theatres", "theaters", "multiplex", "సినిమాహాల్", "థియేటర్"};

    /** Theatre chains: "PVR movie timings" asks for theatres, not a movie called PVR. */
    private static final java.util.Set<String> CHAINS = new java.util.HashSet<>(java.util.Arrays.asList(
            "pvr", "inox", "cinepolis", "asian", "miraj", "carnival", "prasads", "aaa", "amb", "allu", "imax", "పీవీఆర్", "ఐనాక్స్"));

    /** "OG సినిమా ఎక్కడ ఆడుతోంది?" → "OG"; "సినిమా థియేటర్లు" (no movie named) → null. */
    static String movieName(String q) {
        if (q == null) return null;
        String w = q.trim();
        if (w.isEmpty()) return null;
        String low = w.toLowerCase(Locale.ROOT);
        boolean movie = false;
        for (String m : MOVIE_WORDS) if ((" " + low + " ").contains(" " + m + " ") || low.contains(m + " ") || low.endsWith(m)) { movie = true; break; }
        if (!movie) return null;
        StringBuilder b = new StringBuilder();
        for (String t : w.split("[\\s,.!?;:()]+")) {
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.isEmpty()) continue;
            boolean skip = false;
            for (String m : MOVIE_WORDS) if (tl.equals(m) || tl.startsWith(m) && m.length() >= 4) { skip = true; break; }
            for (String f : MOVIE_FILL) if (tl.equals(f)) { skip = true; break; }
            if (skip) continue;
            if (b.length() > 0) b.append(' ');
            b.append(t);
        }
        String name = b.toString().trim();
        if (name.isEmpty()) return null;
        for (String t : name.toLowerCase(Locale.ROOT).split("\\s+")) if (!CHAINS.contains(t)) return name;
        return null; // (only a chain's name: theatres, not a movie)
    }

    /** The AI's answer → its JSON (code fences and words around it tolerated); null when there is none. */
    static JSONObject parseShows(String ai) {
        if (ai == null) return null;
        int a = ai.indexOf('{'), b = ai.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return new JSONObject(ai.substring(a, b + 1)); } catch (Exception e) { return null; }
    }

    private static final java.util.Set<String> NAME_FILL = new java.util.HashSet<>(java.util.Arrays.asList(
            "cinema", "cinemas", "theatre", "theater", "theatres", "theaters", "multiplex", "screen", "screens", "the", "a/c", "ac", "dts", "4k", "2k",
            "laser", "dolby", "atmos", "imax", "70mm", "35mm", "and", "&", "mall", "complex", "pictures", "movies",
            "sri", "sree", "shri", "sai", "lakshmi", "venkateswara", "durga", "new", "old", "big"));

    /** How alike two theatre names are, 0–100, against the longer name ("PVR Inorbit" ~ "PVR: Inorbit Mall, Cyberabad" 67; "Sri Sai Ram" ~ "Sri Sai Krishna" 0). */
    static int nameScore(String a, String b) {
        List<String> x = tokens(a), y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int common = 0;
        for (String t : x) if (y.contains(t)) common++;
        return Math.round(100f * common / Math.max(x.size(), y.size()));
    }

    /** Every word of the shorter name is in the longer one, and the theatre's area is in the place's name / address ("Cinepolis" in Vijayawada). */
    static boolean sameWithArea(String a, String b, String area, String address) {
        List<String> x = tokens(a), y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        List<String> s = x.size() <= y.size() ? x : y, l = x.size() <= y.size() ? y : x;
        for (String t : s) if (!l.contains(t)) return false;
        List<String> ar = tokens(area);
        if (ar.isEmpty()) return false;
        String where = ((b == null ? "" : b) + " " + (address == null ? "" : address)).toLowerCase(Locale.ROOT);
        for (String t : ar) if (t.length() >= 4 && where.contains(t)) return true;
        return false;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!t.isEmpty() && !NAME_FILL.contains(t)) out.add(t);
        return out;
    }

    /**
     * 🎬 Which theatres show this movie today / tomorrow near him (or on his way first): his own AI (the brain he chose)
     * looks up the show times on the web — there is no official show-times service to ask — and Google Maps (or the
     * free map) puts those theatres on the map with their km. Times from the web can be out of date: the answer says so.
     */
    static JSONObject movie(Context c, String name, boolean along, boolean freeOnly) throws Exception {
        String movie = name == null ? "" : name.trim();
        if (movie.isEmpty()) return fail("missing", "Which movie?");
        Prefs p = new Prefs(c);
        if (!p.hasBrain()) return fail("no_brain", "No AI is set in Jarvis (⚙️ → Jarvis మెదడు), so I cannot look up the show times.");
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        String town = "";
        try {
            List<android.location.Address> a = new android.location.Geocoder(c, Locale.ENGLISH).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (a != null && !a.isEmpty()) {
                android.location.Address ad = a.get(0);
                town = String.join(", ", nz(ad.getLocality()), nz(ad.getSubAdminArea()), nz(ad.getAdminArea())).replaceAll("(, )+", ", ").replaceAll("^, |, $", "");
            }
        } catch (Exception ignored) {}
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(new java.util.Date());
        String prompt = String.format(Locale.US, "Now it is %s. Find which cinema theatres are showing the movie \"%s\" today and tomorrow within about 40 km of %s "
                        + "(latitude %.4f, longitude %.4f), India. Search the web (BookMyShow, District, Paytm, theatre websites, Google show times). "
                        + "Reply ONLY with JSON, nothing else: {\"movie\":\"its official title\",\"language\":\"\",\"theatres\":[{\"name\":\"theatre name as listed\","
                        + "\"area\":\"area / town\",\"date\":\"today or tomorrow\",\"shows\":[\"10:30 AM\"],\"format\":\"2D / 3D / IMAX\"}],\"note\":\"anything uncertain\"}. "
                        + "At most 10 theatres, nearest first. If you cannot find it, give theatres: [] and say why in note. Never invent show times.",
                today, movie, town.isEmpty() ? "this place" : town, l.getLatitude(), l.getLongitude());
        String ai;
        try {
            ai = Brain.oneShot(p, "You find cinema show times on the web for Anil in India. Answer only with the JSON asked. Never guess times.", prompt, null, true, 1800);
        } catch (Exception e) {
            return fail("ai_failed", "His AI could not look it up now (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").");
        }
        JSONObject shows = parseShows(ai);
        if (shows == null) return fail("no_answer", "His AI did not give a clear list of theatres. Say so; he can check BookMyShow / District.");
        JSONArray ths = shows.optJSONArray("theatres");
        if (ths == null) ths = new JSONArray();
        // the theatres near him (or on his way) from the map, to put the AI's ones on it
        List<GMaps.Place> cands = new ArrayList<>();
        List<double[]> way = null;
        String placesFrom = "", googleErr = null;
        if (GMaps.has(c) && !freeOnly) {
            try {
                String line = null;
                if (along) {
                    way = Drive.aheadPts(c, l, 60);
                    if (way != null && way.size() >= 2) line = MapMath.encode(way);
                    else way = null;
                }
                cands = GMaps.search(c, "movie theater", l.getLatitude(), l.getLongitude(), 40000, line, false, 20, false, true);
                placesFrom = "Google Maps";
            } catch (Exception e) { googleErr = GMaps.telugu(e); }
        }
        if (placesFrom.isEmpty()) {
            try {
                JSONObject f = freeAround(new String[]{"[amenity=cinema]", "సినిమా థియేటర్"}, l.getLatitude(), l.getLongitude(), 40000, l);
                JSONArray ps = f.optJSONArray("places");
                for (int i = 0; ps != null && i < ps.length(); i++) {
                    JSONObject o = ps.optJSONObject(i);
                    GMaps.Place gp = new GMaps.Place();
                    gp.name = o.optString("name");
                    gp.lat = o.optDouble("lat");
                    gp.lon = o.optDouble("lon");
                    gp.aheadM = o.optDouble("km") * 1000;
                    cands.add(gp);
                }
                placesFrom = "free map (OpenStreetMap)";
            } catch (Exception ignored) {}
        }
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < ths.length() && i < 12; i++) {
            JSONObject t = ths.optJSONObject(i);
            if (t == null || t.optString("name").trim().isEmpty()) continue;
            JSONObject it = new JSONObject().put("name", t.optString("name").trim()).put("area", t.optString("area")).put("date", t.optString("date", "today"))
                    .put("shows", t.optJSONArray("shows") == null ? new JSONArray() : t.optJSONArray("shows")).put("format", t.optString("format"));
            GMaps.Place best = null;
            int bestScore = 0;
            for (GMaps.Place cp : cands) {
                int sc = nameScore(t.optString("name"), cp.name);
                if (sc < 60 && sameWithArea(t.optString("name"), cp.name, t.optString("area"), cp.address)) sc = 60; // (a one-word chain name, in the right area)
                if (sc > bestScore) { bestScore = sc; best = cp; }
            }
            if (best != null && bestScore >= 60) {
                it.put("lat", r5(best.lat)).put("lon", r5(best.lon)).put("map_name", best.name);
                if (best.rating > 0) it.put("rating", best.rating);
                if (!best.mapsUri.isEmpty()) it.put("maps_link", best.mapsUri);
                if (!best.phone.isEmpty()) it.put("phone", best.phone);
                if (way != null) {
                    double[] at = Drive.place(way, best.lat, best.lon);
                    if (at[0] > 0.1 && at[1] < 3000) it.put("on_his_way", true).put("km_ahead", Math.round(at[0] * 10) / 10.0);
                }
                it.put("km", Math.round(MapMath.meters(l.getLatitude(), l.getLongitude(), best.lat, best.lon) / 100) / 10.0);
            }
            out.add(it);
        }
        Collections.sort(out, (a, b) -> {
            boolean wa = a.optBoolean("on_his_way"), wb = b.optBoolean("on_his_way");
            if (wa != wb) return wa ? -1 : 1;
            return Double.compare(a.optDouble("km", 1e9), b.optDouble("km", 1e9));
        });
        JSONObject o = new JSONObject().put("ok", true).put("movie", shows.optString("movie", movie)).put("language", shows.optString("language"))
                .put("theatres", top(out, 10)).put("time_now", clock(System.currentTimeMillis()))
                .put("source", "show times: his AI's web search (can be out of date); theatre places: " + (placesFrom.isEmpty() ? "none" : placesFrom));
        if (!shows.optString("note").isEmpty()) o.put("ai_note", shows.optString("note"));
        if (googleErr != null) o.put("google_error", googleErr);
        if (out.isEmpty()) o.put("note", "No theatre found showing it near him. Say so honestly; it may not be released here, or the web had no list.");
        return o.put("next", "Say in short Telugu: the movie and the nearest 3 theatres with their next shows after now (and km; the ones on his way first). "
                + "Say once that the times are from the web and to check BookMyShow / District before going. Offer the way (drive navigate with its lat,lon) "
                + "or booking (phone_task in BookMyShow / District — he pays himself) only if he asks.");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ================================================================ 🗓️ a trip plan, ↩️ the best time to leave

    /** What to eat when he passes the middle of the trip at this minute of the day: {Google's words, Telugu}. */
    static String[] meal(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        if (m >= 330 && m < 630) return new String[]{"breakfast tiffin center", "టిఫిన్"};
        if (m >= 690 && m < 930) return new String[]{"restaurant or dhaba for lunch", "మధ్యాహ్న భోజనం"};
        if (m >= 1110 && m < 1350) return new String[]{"restaurant or dhaba for dinner", "రాత్రి భోజనం"};
        if (m >= 1350 || m < 330) return new String[]{"dhaba or tea stall open at night", "రాత్రి టీ / దాబా"};
        return new String[]{"tea stall or coffee shop", "టీ / స్నాక్స్"};
    }

    /** n points spread evenly along a path (the last one at its end): {lat, lon, km from the start}. */
    static List<double[]> samples(List<double[]> pts, int n) {
        List<double[]> out = new ArrayList<>();
        if (pts == null || pts.size() < 2 || n < 1) return out;
        double total = MapMath.km(pts) * 1000, run = 0;
        int next = 1;
        for (int i = 1; i < pts.size() && next <= n; i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double seg = MapMath.meters(a[0], a[1], b[0], b[1]);
            while (next <= n && run + seg >= total * next / n - 1e-6) {
                double want = total * next / n, t = seg <= 0 ? 0 : Math.max(0, Math.min(1, (want - run) / seg));
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, want / 1000});
                next++;
            }
            run += seg;
        }
        double[] end = pts.get(pts.size() - 1);
        while (out.size() < n) out.add(new double[]{end[0], end[1], total / 1000}); // (rounding never leaves the last one out)
        return out;
    }

    /** The trip's way and time: Google (traffic expected then; the toll) with his key, else the free router (no traffic). */
    private static final class Way {
        List<double[]> pts = new ArrayList<>();
        double km;
        long seconds, staticSeconds;
        String via = "", toll = "", polyline = "", source = "", googleError;
    }

    private static Way way(Context c, double lat, double lon, double toLat, double toLon, boolean bike, long departAt, boolean tolls, boolean freeOnly) throws Exception {
        Way w = new Way();
        String googleErr = null;
        if (GMaps.has(c) && !freeOnly) {
            try {
                List<GMaps.Route> rs = GMaps.routesAt(c, lat, lon, toLat, toLon, bike, departAt, tolls, false);
                if (!rs.isEmpty()) {
                    GMaps.Route r = rs.get(0);
                    w.pts = r.pts; w.km = r.meters / 1000; w.seconds = r.seconds; w.staticSeconds = r.staticSeconds; w.via = r.via; w.toll = r.toll;
                    w.polyline = r.polyline; w.source = "Google Maps";
                    return w;
                }
            } catch (Exception e) { googleErr = GMaps.telugu(e); } // (the day's limit, no net: the free router below, and said so)
        }
        Drive.Path p = Drive.freeRoute(lat, lon, toLat, toLon);
        if (p == null) return null;
        w.googleError = googleErr;
        w.pts = p.pts; w.km = p.totalKm; w.staticSeconds = Math.round(p.minutes * 60); w.seconds = Math.round(p.minutes * 60 * 1.15); // (no traffic: a little more)
        w.via = p.via; w.polyline = MapMath.encode(p.pts); w.source = "free router (OpenStreetMap), no live traffic";
        return w;
    }

    /** Where he is going: {lat, lon}, from his saved places, the address finder, else Google's search. */
    private static double[] whereTo(Context c, String to, Location from, boolean freeOnly) {
        double[] ll = Drive.latLon(c, to);
        if (ll != null || !GMaps.has(c) || freeOnly) return ll;
        try {
            List<GMaps.Place> ps = GMaps.search(c, to, from == null ? Double.NaN : from.getLatitude(), from == null ? Double.NaN : from.getLongitude(), 50000, null, false, 1, false, false);
            if (!ps.isEmpty()) return new double[]{ps.get(0).lat, ps.get(0).lon};
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 🗓️ "రేపు ఉదయం 6 కి హైదరాబాద్": the time with the traffic expected then, km, toll, when to leave (arriveBy: leave
     * so as to be there by then, 15 minutes spare), a meal stop near the middle that is open when he gets there,
     * rain on the way, and on the bike whether the battery reaches. A reminder only if he says yes (the brain asks).
     */
    static JSONObject plan(Context c, String to, long departAt, long arriveBy, boolean bike, boolean freeOnly) throws Exception {
        if (to == null || to.trim().isEmpty()) return fail("missing", "Where to?");
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        double[] dl = whereTo(c, to.trim(), l, freeOnly);
        if (dl == null) return fail("not_found", "Could not find '" + to + "' on the map. Ask for the town / place name again.");
        long now = System.currentTimeMillis();
        long leave;
        Way w;
        if (arriveBy > now) {
            double straightKm = MapMath.meters(l.getLatitude(), l.getLongitude(), dl[0], dl[1]) / 1000;
            long guess = arriveBy - (long) (straightKm * 1.3 / 45 * 3600_000) - 15 * 60_000L;
            w = way(c, l.getLatitude(), l.getLongitude(), dl[0], dl[1], bike, Math.max(now, guess), true, freeOnly);
            if (w == null) return fail("no_route", "No road route found to there.");
            leave = arriveBy - w.seconds * 1000 - 15 * 60_000L;
            if (leave > now + 30 * 60_000L && Math.abs(leave - Math.max(now, guess)) > 20 * 60_000L && w.source.startsWith("Google")) {
                Way w2 = way(c, l.getLatitude(), l.getLongitude(), dl[0], dl[1], bike, leave, false, freeOnly); // (the traffic at the real leaving time; no toll asked again)
                if (w2 != null && w2.source.startsWith("Google")) { w.seconds = w2.seconds; leave = arriveBy - w.seconds * 1000 - 15 * 60_000L; }
            }
            if (leave < now) leave = now;
        } else {
            leave = departAt > now ? departAt : now;
            w = way(c, l.getLatitude(), l.getLongitude(), dl[0], dl[1], bike, leave, true, freeOnly);
            if (w == null) return fail("no_route", "No road route found to there.");
        }
        long arrive = leave + w.seconds * 1000;
        JSONObject o = new JSONObject().put("ok", true).put("to", to.trim());
        if (w.googleError != null) o.put("google_error", w.googleError);
        if (arriveBy > now && arrive > arriveBy) o.put("late_minutes", Math.round((arrive - arriveBy) / 60_000.0)); // (even leaving now he is late: said, never hidden)
        o.put("to_lat", r5(dl[0])).put("to_lon", r5(dl[1])).put("by", bike ? "bike" : "car")
                .put("km", Math.round(w.km)).put("minutes", Math.round(w.seconds / 60.0)).put("leave_at", dayClock(leave)).put("arrive_at", dayClock(arrive))
                .put("leave_at_ms", leave).put("arrive_at_ms", arrive).put("source", w.source).put("polyline", w.polyline);
        if (w.staticSeconds > 0 && w.seconds - w.staticSeconds > 300) o.put("traffic_extra_minutes", Math.round((w.seconds - w.staticSeconds) / 60.0));
        if (!w.via.isEmpty()) o.put("via", w.via);
        if (!w.toll.isEmpty()) o.put("toll", w.toll);
        if (arriveBy > now) o.put("arrive_by", dayClock(arriveBy)).put("spare_minutes", o.has("late_minutes") ? 0 : 15);
        // a meal / tea stop near the middle, open when he gets there (Google, trips over 60 km)
        if (w.km >= 60 && GMaps.has(c) && !freeOnly) {
            try {
                java.util.Calendar mid = java.util.Calendar.getInstance();
                mid.setTimeInMillis(leave + w.seconds * 500);
                String[] m = meal(mid.get(java.util.Calendar.HOUR_OF_DAY) * 60 + mid.get(java.util.Calendar.MINUTE));
                List<GMaps.Place> ps = GMaps.search(c, m[0], l.getLatitude(), l.getLongitude(), 5000, w.polyline, false, 20, false, true);
                List<GMaps.Place> ok = new ArrayList<>();
                for (GMaps.Place p : ps) {
                    if (p.aheadM < w.km * 1000 * 0.3 || p.aheadM > w.km * 1000 * 0.75) continue; // (near the middle)
                    p.openThen = GMaps.openAt(p.periods, leave + Math.max(0, p.aheadS) * 1000);
                    if (Boolean.FALSE.equals(p.openThen)) continue;
                    ok.add(p);
                }
                final double half = w.km * 500;
                Collections.sort(ok, (a, b) -> Double.compare(score(a, half), score(b, half)));
                JSONArray stops = new JSONArray();
                for (int i = 0; i < ok.size() && stops.length() < 3; i++) {
                    GMaps.Place p = ok.get(i);
                    JSONObject it = Drive.item(p).put("km_from_start", Math.round(p.aheadM / 1000));
                    if (p.aheadS > 0) it.put("reach_at", clock(leave + p.aheadS * 1000));
                    if (p.openThen != null) it.put("open_when_he_gets_there", p.openThen);
                    stops.put(it);
                }
                o.put("meal", m[1]).put("stops", stops);
            } catch (Exception e) { o.put("stops_error", GMaps.telugu(e)); }
        }
        try { o.put("rain", rainAlong(w.pts, leave, w.seconds)); } catch (Exception ignored) {}
        if (bike) { try { o.put("bike", rangeFor(c, w.km, 0)); } catch (Exception ignored) {} }
        return o.put("next", "Say in short Telugu: " + (o.has("late_minutes") ? "first that even leaving now he will be about late_minutes late; " : "")
                + "when to leave (and when he reaches), the time with the traffic expected then, km, the toll, one "
                + (o.has("stops") ? "meal stop from 'stops' (name, km from start, open when he gets there), " : "")
                + "rain on the way if 'rain' shows a chance of 50% or more (where and when), and the battery from 'bike' if there. "
                + "Then ask if he wants a reminder 15 minutes before leave_at (set_reminder) — set it only if he says yes.");
    }

    private static double score(GMaps.Place p, double half) {
        double off = Math.abs(p.aheadM - half) / 1000; // km from the middle
        double r = p.rating > 0 ? p.rating : 3.5;
        return off - (r - 3.5) * 10 - (p.ratings > 100 ? 3 : 0);
    }

    /** ↩️ The best time to leave: the time with traffic for 4 leaving times from `from` (now, +30, +60, +120 min). */
    static JSONObject bestTime(Context c, String to, long from, boolean bike, boolean freeOnly) throws Exception {
        if (to == null || to.trim().isEmpty()) return fail("missing", "Where to?");
        if (!GMaps.has(c) || freeOnly) return fail("needs_google", "Comparing leaving times needs Google's traffic (the Maps key in ⚙️ → 🗺️ Jarvis Maps).");
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        double[] dl = whereTo(c, to.trim(), l, false);
        if (dl == null) return fail("not_found", "Could not find '" + to + "' on the map.");
        long now = System.currentTimeMillis(), start = Math.max(now, from);
        long[] adds = {0, 30, 60, 120};
        JSONArray slots = new JSONArray();
        long bestAt = 0, bestSec = Long.MAX_VALUE;
        for (long add : adds) {
            long at = start + add * 60_000L;
            List<GMaps.Route> rs = GMaps.routesAt(c, l.getLatitude(), l.getLongitude(), dl[0], dl[1], bike, at, false, false);
            if (rs.isEmpty()) continue;
            GMaps.Route r = rs.get(0);
            slots.put(new JSONObject().put("leave", dayClock(at)).put("minutes", Math.round(r.seconds / 60.0)).put("arrive", clock(at + r.seconds * 1000)).put("km", Math.round(r.meters / 1000)));
            if (r.seconds < bestSec) { bestSec = r.seconds; bestAt = at; }
        }
        if (slots.length() == 0) return fail("no_route", "Google gave no route to there.");
        return new JSONObject().put("ok", true).put("to", to.trim()).put("slots", slots).put("best_leave", dayClock(bestAt)).put("best_minutes", Math.round(bestSec / 60.0))
                .put("source", "Google Maps (the traffic Google expects)").put("next", "Say in short Telugu the 4 leaving times with minutes, and which is quickest. "
                        + "These are Google's expectations, not certain.");
    }

    // ================================================================ 🌧️ rain on the way

    /** Rain along the way at the time he passes each part (free weather, Open-Meteo): {km, at, chance %, mm}. */
    static JSONArray rainAlong(List<double[]> pts, long startMs, long totalSec) throws Exception {
        JSONArray out = new JSONArray();
        double totalKm = MapMath.km(pts);
        if (totalKm < 1) return out;
        int n = (int) Math.max(2, Math.min(7, Math.ceil(totalKm / 25)));
        List<double[]> sm = samples(pts, n);
        sm.add(0, new double[]{pts.get(0)[0], pts.get(0)[1], 0});
        StringBuilder la = new StringBuilder(), lo = new StringBuilder();
        for (double[] s : sm) {
            if (la.length() > 0) { la.append(','); lo.append(','); }
            la.append(String.format(Locale.US, "%.3f", s[0]));
            lo.append(String.format(Locale.US, "%.3f", s[1]));
        }
        long last = startMs + totalSec * 1000;
        int days = (int) Math.max(1, Math.min(16, Math.ceil((last - System.currentTimeMillis()) / 86_400_000.0) + 1));
        String body = Http.getText("https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                + "&hourly=precipitation_probability,precipitation&forecast_days=" + days + "&timezone=auto");
        String t = body.trim();
        List<JSONObject> locs = new ArrayList<>();
        if (t.startsWith("[")) { JSONArray a = new JSONArray(t); for (int i = 0; i < a.length(); i++) locs.add(a.optJSONObject(i)); }
        else locs.add(new JSONObject(t));
        for (int i = 0; i < sm.size() && i < locs.size(); i++) {
            double[] s = sm.get(i);
            long when = startMs + (long) (totalSec * 1000 * (s[2] / totalKm));
            int[] pick = rainPick(locs.get(i), when);
            if (pick == null) continue;
            out.put(new JSONObject().put("km", Math.round(s[2])).put("at", clock(when)).put("chance", pick[0]).put("mm_tenths", pick[1]));
        }
        return out;
    }

    /** From one place's hourly forecast, the hour at `when`: {chance %, rain in tenths of a mm}; null if not in it. */
    static int[] rainPick(JSONObject loc, long when) {
        if (loc == null) return null;
        JSONObject h = loc.optJSONObject("hourly");
        if (h == null) return null;
        JSONArray times = h.optJSONArray("time"), pr = h.optJSONArray("precipitation_probability"), mm = h.optJSONArray("precipitation");
        if (times == null || pr == null) return null;
        long off = loc.optLong("utc_offset_seconds", 0) * 1000;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < times.length(); i++) {
            try {
                long start = f.parse(times.optString(i)).getTime() - off; // (the place's own time → real time)
                if (when >= start && when < start + 3600_000) {
                    return new int[]{pr.optInt(i, 0), mm == null ? 0 : (int) Math.round(mm.optDouble(i, 0) * 10)};
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 🌧️ "దారిలో వాన పడుతుందా?": on the way to his destination (or `to`), else 100 km straight on. Free (no Google). */
    static JSONObject rain(Context c, String to) throws Exception {
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        List<double[]> pts;
        long seconds;
        String desc;
        boolean named = to != null && !to.trim().isEmpty();
        double[] dl = named ? Drive.latLon(c, to.trim()) : null;
        if (named && dl == null) return fail("not_found", "Could not find '" + to.trim() + "' on the map.");
        JSONObject d = Drive.dest(c);
        if (dl == null && d != null && d.has("lat")) dl = new double[]{d.optDouble("lat"), d.optDouble("lon")};
        if (dl != null) {
            Drive.Path p = Drive.freeRoute(l.getLatitude(), l.getLongitude(), dl[0], dl[1]);
            if (p == null) return fail("no_route", "No road route found to there.");
            pts = p.pts;
            seconds = Math.round(p.minutes * 60 * 1.15);
            desc = "on the way to " + (to != null && !to.trim().isEmpty() ? to.trim() : d.optString("name"));
        } else {
            Drive.Path p = Drive.ahead(c, l, 100);
            if (p == null) return fail("no_direction", "No destination and he is not moving: ask where he is going.");
            pts = p.pts;
            seconds = Math.round(MapMath.km(pts) / 55 * 3600);
            desc = "straight on in his direction for 100 km";
        }
        JSONArray r = rainAlong(pts, System.currentTimeMillis(), seconds);
        int max = 0;
        for (int i = 0; i < r.length(); i++) max = Math.max(max, r.optJSONObject(i).optInt("chance"));
        return new JSONObject().put("ok", true).put("searched", desc).put("rain", r).put("max_chance", max).put("source", "Open-Meteo weather (free)")
                .put("next", "Say in short Telugu whether rain is likely on the way (chance 50% or more): where (km) and when. If not, say the way looks dry. "
                        + (Bike.riding(c) ? "He is on the bike: if rain is likely, suggest the raincoat." : ""));
    }

    // ================================================================ 🔋 the bike's battery for a trip

    /** The battery against km: range now, whether it reaches (10% spare), what is left there. */
    private static JSONObject rangeFor(Context c, double km, int pctGiven) throws Exception {
        Prefs p = new Prefs(c);
        int pct = pctGiven > 0 ? Math.min(100, pctGiven) : Bike.estimatePct(c);
        JSONObject o = new JSONObject();
        if (pct < 0) return o.put("battery_known", false).put("ask", "Jarvis does not know the battery now: ask him the % (or he tells Jarvis after charging).");
        int full = Bike.fullRangeKm(p);
        double range = full * pct / 100.0;
        o.put("battery_known", true).put("battery_pct", pct).put("range_km", Math.round(range)).put("full_range_km", full);
        if (km > 0) {
            boolean reaches = range >= km * 1.1;
            o.put("trip_km", Math.round(km)).put("reaches", reaches).put("left_pct_there", (int) Math.max(0, Math.round((range - km) * 100 / full)));
        }
        return o;
    }

    /** 🔋 "ఈ ఛార్జ్‌తో అక్కడికి చేరుతానా?": the battery against the way; when short, chargers near where it runs low. */
    static JSONObject bikeRange(Context c, String to, int pct, boolean freeOnly) throws Exception {
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        boolean named = to != null && !to.trim().isEmpty();
        double[] dl = named ? whereTo(c, to.trim(), l, freeOnly) : null;
        if (named && dl == null) return fail("not_found", "Could not find '" + to.trim() + "' on the map.");
        JSONObject d = Drive.dest(c);
        if (dl == null && d != null && d.has("lat")) dl = new double[]{d.optDouble("lat"), d.optDouble("lon")};
        if (dl == null) {
            JSONObject r = rangeFor(c, 0, pct);
            return new JSONObject().put("ok", true).put("bike", r).put("next", "Say the battery % and about how many km it still goes. To check a trip, ask where to.");
        }
        Drive.Path p = Drive.freeRoute(l.getLatitude(), l.getLongitude(), dl[0], dl[1]); // (the free router's road km: no Google cost)
        if (p == null) return fail("no_route", "No road route found to there.");
        JSONObject r = rangeFor(c, p.totalKm, pct);
        JSONObject o = new JSONObject().put("ok", true).put("bike", r).put("to", to != null && !to.trim().isEmpty() ? to.trim() : d.optString("name"));
        if (r.optBoolean("battery_known") && !r.optBoolean("reaches")) { // short: chargers before it runs low (15 km spare)
            double at = Math.max(5, r.optDouble("range_km") - 15);
            List<double[]> cut = MapMath.cut(p.pts, at);
            double[] pt = cut.get(cut.size() - 1);
            JSONObject ch = null;
            if (GMaps.has(c) && !freeOnly) {
                try {
                    List<GMaps.Place> ps = GMaps.search(c, "EV charging station", pt[0], pt[1], 12000, null, false, 8, true, true);
                    List<JSONObject> list = new ArrayList<>();
                    for (GMaps.Place gp : ps) {
                        double[] on = Drive.place(p.pts, gp.lat, gp.lon);
                        list.add(Drive.item(gp).put("km_on_the_way", Math.round(on[0])).put("off_road_km", Math.round(on[1] / 100) / 10.0));
                    }
                    ch = new JSONObject().put("source", "Google Maps").put("places", top(list, 5));
                } catch (Exception e) { o.put("google_error", GMaps.telugu(e)); }
            }
            if (ch == null) { try { ch = freeAround(new String[]{"[amenity=charging_station]", "EV ఛార్జర్"}, pt[0], pt[1], 12000, l); } catch (Exception ignored) {} }
            if (ch != null) o.put("chargers_before_it_runs_low", ch.optJSONArray("places")).put("chargers_source", ch.optString("source"));
        }
        return o.put("next", "Say in short Telugu: battery %, km it goes, the trip km, and whether it reaches (with % left there). If not, the nearest charger "
                + "before it runs low (km on the way). If the battery is unknown, ask him the % first.");
    }

    // ================================================================ 🏞️ worth seeing on the way

    static JSONObject sights(Context c, double km, boolean freeOnly) throws Exception {
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        boolean moving = Drive.heading(l) >= 0 || Drive.dest(c) != null;
        JSONObject o = places(c, "attraction", moving ? "along" : "near", false, km > 0 ? km : 100, freeOnly);
        JSONArray ps = o.optJSONArray("places");
        if (moving && ps != null && GMaps.has(c) && !freeOnly) { // how far off the road each one is (the detour)
            List<double[]> way = MapMath.decode(Drive.settings(c).getString("g_ahead", ""));
            for (int i = 0; way.size() >= 2 && i < ps.length(); i++) {
                JSONObject it = ps.optJSONObject(i);
                double[] at = Drive.place(way, it.optDouble("lat"), it.optDouble("lon"));
                it.put("off_road_km", Math.round(at[1] / 100) / 10.0);
            }
        }
        return o.put("next", "Say in short Telugu the 3 best worth seeing: name, what it is (type), km ahead, how far off the road, rating. "
                + "Offer to add one as a stop (drive add_stop with its lat,lon) only if he wants.");
    }

    // ================================================================ 🆘 help on the road

    /**
     * 🆘 "బండి ఆగిపోయింది / పంక్చర్ / ప్రమాదం": where he is in words (to tell 108, police or family), the nearest open
     * mechanic / puncture / towing, the nearest 24-hour hospital. Calls and messages only after his yes; 108 = the dialer.
     */
    static JSONObject help(Context c, boolean freeOnly) throws Exception {
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        JSONObject o = new JSONObject().put("ok", true).put("lat", r5(l.getLatitude())).put("lon", r5(l.getLongitude()))
                .put("map_link", String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", l.getLatitude(), l.getLongitude()));
        try {
            List<android.location.Address> a = new android.location.Geocoder(c, Locale.ENGLISH).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (a != null && !a.isEmpty()) {
                android.location.Address ad = a.get(0);
                o.put("address", nz(ad.getAddressLine(0))).put("area", nz(ad.getSubLocality())).put("town", nz(ad.getLocality()))
                        .put("district", nz(ad.getSubAdminArea())).put("state", nz(ad.getAdminArea()));
            }
        } catch (Exception ignored) {}
        String road = DriveService.running && !DriveService.roadName.isEmpty() ? DriveService.roadName : "";
        if (road.isEmpty()) { try { JSONObject r = Drive.road(l.getLatitude(), l.getLongitude()); road = r.optString("ref", r.optString("name", "")); } catch (Exception ignored) {} }
        if (!road.isEmpty()) o.put("road", road);
        boolean g = GMaps.has(c) && !freeOnly;
        String googleErr = null;
        if (g) {
            try {
                o.put("mechanics_open_now", list(GMaps.search(c, "puncture shop or vehicle mechanic or towing service", l.getLatitude(), l.getLongitude(), 15000, null, true, 6, true, true), l));
                o.put("hospitals_open_now", list(GMaps.search(c, "hospital with 24 hour emergency", l.getLatitude(), l.getLongitude(), 25000, null, true, 4, true, true), l));
                o.put("source", "Google Maps");
            } catch (Exception e) { googleErr = GMaps.telugu(e); g = false; }
        }
        if (!g) {
            try { o.put("mechanics", freeAround(PlaceKinds.byId("mechanic") == null ? null : new String[]{PlaceKinds.byId("mechanic").osm, "మెకానిక్"}, l.getLatitude(), l.getLongitude(), 15000, l).optJSONArray("places")); } catch (Exception ignored) {}
            try { o.put("hospitals", freeAround(new String[]{"[amenity=hospital]", "హాస్పిటల్"}, l.getLatitude(), l.getLongitude(), 25000, l).optJSONArray("places")); } catch (Exception ignored) {}
            o.put("source", "free map (OpenStreetMap; open hours mostly unknown)");
            if (googleErr != null) o.put("google_error", googleErr);
        }
        return o.put("next", "Speak calmly, short Telugu. First ask if anyone is hurt: if yes, 108 (open the dialer only, never call by itself). "
                + "Say where he is in words (road, area / town, district) so he can tell 108 / police / family. Then the nearest open mechanic / puncture / "
                + "towing (km, phone) and the nearest 24-hour hospital. Offer to call one (call tool, only after his yes) and to send his location "
                + "(map_link) to family (message tool, only after he says send). Remind him: hazard lights on, stand away from the road.");
    }

    private static JSONArray list(List<GMaps.Place> ps, Location l) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (GMaps.Place p : ps) out.add(Drive.item(p).put("km", Math.round(MapMath.meters(l.getLatitude(), l.getLongitude(), p.lat, p.lon) / 100) / 10.0));
        Collections.sort(out, (a, b) -> Double.compare(a.optDouble("km"), b.optDouble("km")));
        return top(out, 5);
    }

    // ================================================================ 👵 his arrival time on amma's tablet

    /**
     * Tells amma's tablet when he will reach home (minutes and the clock): only when he asks, or — with the
     * "ఇంటికి వస్తున్నప్పుడు అమ్మగారికి చెప్పు" switch on — by itself on a trip home. Only the time goes, not where he is.
     */
    static JSONObject homeEta(Context c, boolean freeOnly) throws Exception {
        if (!HomeLink.linked(c)) return fail("not_linked", "The home tablet is not linked to this phone (⚙️ → 🏠 టాబ్లెట్).");
        JSONObject home = Travel.home(c);
        if (home == null || !home.has("lat")) return fail("no_home", "Jarvis does not know home's place yet: at home say 'ఇది మా ఇల్లు'.");
        Location l = Drive.here(c);
        if (l == null) return noLocation();
        double hLat = home.optDouble("lat"), hLon = home.optDouble("lon");
        if (MapMath.meters(l.getLatitude(), l.getLongitude(), hLat, hLon) < 1000) return fail("near_home", "He is already near home (under 1 km).");
        Way w = way(c, l.getLatitude(), l.getLongitude(), hLat, hLon, Bike.riding(c), 0, false, freeOnly);
        if (w == null) return fail("no_route", "No road route found home.");
        if (w.googleError != null && !w.source.startsWith("Google")) w.source += " (Google: " + w.googleError + ")";
        int min = (int) Math.max(1, Math.round(w.seconds / 60.0));
        long at = System.currentTimeMillis() + w.seconds * 1000;
        String r = HomeLink.eta(c, min, clock(at));
        if (r != null) return fail("not_sent", r);
        Drive.settings(c).edit().putLong("eta_sent_at", System.currentTimeMillis()).putInt("eta_sent_min", min).putLong("eta_sent_arrive", at).apply();
        return new JSONObject().put("ok", true).put("minutes", min).put("arrive_at", clock(at)).put("source", w.source)
                .put("next", "Say in one line that amma's tablet will say he reaches in about " + min + " minutes (" + clock(at) + ").");
    }
}
