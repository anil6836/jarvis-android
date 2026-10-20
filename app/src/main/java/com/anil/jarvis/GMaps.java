package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🗺️ Google Maps Platform for Jarvis Maps, with Anil's own Maps key (Settings → 🗺️ Jarvis Maps; kept on the phone,
 * never in a log, never in the chat). Every request also says it comes from this app on this phone (the package and
 * its signing fingerprint), so a key locked to "Android apps" in Google Cloud works. What it does: the map's pictures
 * (Map Tiles API, Telugu names, traffic / satellite), routes (Routes API: time with traffic, km, tolls, the line),
 * places near him or along his way (Places API (New) Text Search, with how far ahead each one is), and the road's
 * details from Gemini with Grounding with Google Maps (his Gemini key; English in and out, Jarvis says it in Telugu).
 * Each kind of call is counted per month on the phone, so he can see it against Google's free amounts.
 */
final class GMaps {
    private GMaps() {}

    /** An error from Google, said plainly (no key in it). */
    static final class Error extends Exception {
        final int status;
        final String reason;
        Error(int status, String reason, String message) { super(message); this.status = status; this.reason = reason == null ? "" : reason; }
    }

    static String key(Context c) { return new Prefs(c).sp.getString("maps_key", "").replaceAll("[^\\x21-\\x7E]", ""); }
    static boolean has(Context c) { return key(c).length() > 20; }

    static SharedPreferences store(Context c) { return c.getSharedPreferences("jarvis_maps", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- this app's fingerprint (for "Android apps" keys)

    private static volatile String certSha1;

    /** The signing certificate's SHA-1 (upper-case hex, no colons), as Google wants it in X-Android-Cert. */
    @SuppressWarnings("deprecation")
    static String cert(Context c) {
        String s = certSha1;
        if (s != null) return s;
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sig;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sig = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            } else {
                sig = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            if (sig == null || sig.length == 0) return "";
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sig[0].toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US, "%02X", x & 0xFF));
            certSha1 = s = b.toString();
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** "34:7C:CB:…": the same fingerprint the way Google Cloud shows it (for the setup guide / Settings). */
    static String certShown(Context c) {
        String s = cert(c);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < s.length(); i += 2) { if (b.length() > 0) b.append(':'); b.append(s, i, i + 2); }
        return b.toString();
    }

    private static String[] headers(Context c, String fieldMask) {
        List<String> h = new ArrayList<>();
        h.add("X-Goog-Api-Key"); h.add(key(c));
        h.add("X-Android-Package"); h.add(c.getPackageName());
        String cert = cert(c);
        if (!cert.isEmpty()) { h.add("X-Android-Cert"); h.add(cert); }
        if (fieldMask != null) { h.add("X-Goog-FieldMask"); h.add(fieldMask); }
        return h.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- the monthly count (Google's free amounts)

    /** The kinds of calls and Google's free amount a month for each (the price list of 2026). */
    static final String[][] KINDS = {
            {"tiles", "మ్యాప్ ముక్కలు", "100000"},
            {"routes", "దారి గీత (వెతకడానికి)", "5000"},
            {"routes_pro", "దారులు (ట్రాఫిక్, టోల్)", "1000"},
            {"places", "ప్లేస్ వెతకడం", "5000"},
            {"places_open", "ప్లేస్ + తెరిచి ఉందా", "1000"},
            {"gemini_maps", "Gemini + Google Maps", "5000"}};

    private static String month() { return new java.text.SimpleDateFormat("yyyyMM", Locale.US).format(new java.util.Date()); }

    private static final Object COUNT_LOCK = new Object(); // (its own lock: never waits on a session being made)

    static void count(Context c, String kind) {
        synchronized (COUNT_LOCK) { countNow(c, kind); }
    }

    private static void countNow(Context c, String kind) {
        try {
            SharedPreferences p = store(c);
            String k = "u_" + month() + "_" + kind;
            p.edit().putInt(k, p.getInt(k, 0) + 1).apply();
        } catch (Exception ignored) {}
    }

    static int used(Context c, String kind) { return store(c).getInt("u_" + month() + "_" + kind, 0); }

    /** "ఈ నెల: మ్యాప్ ముక్కలు 1,240 / 1,00,000 · దారులు 31 / 10,000 · …". */
    static String usageLine(Context c) {
        StringBuilder b = new StringBuilder("ఈ నెల (ఫోన్ లెక్క): ");
        for (int i = 0; i < KINDS.length; i++) {
            if (i > 0) b.append(" · ");
            b.append(KINDS[i][1]).append(' ').append(used(c, KINDS[i][0])).append(" / ").append(KINDS[i][2]);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- HTTP (short time limits: a map must not hang)

    private static JSONObject send(Context c, String method, String url, JSONObject body, String fieldMask) throws Exception {
        if (!has(c)) throw new Error(0, "NO_KEY", "No Google Maps key in Settings → 🗺️ Jarvis Maps.");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod(method);
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Accept", "application/json");
            String[] hd = headers(c, fieldMask);
            for (int i = 0; i + 1 < hd.length; i += 2) h.setRequestProperty(hd[i], hd[i + 1]);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream o = h.getOutputStream()) { o.write(bytes); }
            }
            int st = h.getResponseCode();
            InputStream in = st >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            if (st >= 400) throw error(st, text);
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            h.disconnect();
        }
    }

    /** Google's error body → a plain reason (PERMISSION_DENIED, API_KEY_ANDROID_APP_BLOCKED, …) and its message. */
    static Error error(int status, String body) {
        String reason = "", msg = "HTTP " + status;
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) {
                msg = e.optString("message", msg);
                reason = e.optString("status", "");
                JSONArray det = e.optJSONArray("details");
                for (int i = 0; det != null && i < det.length(); i++) {
                    String r = det.optJSONObject(i) == null ? "" : det.optJSONObject(i).optString("reason", "");
                    if (!r.isEmpty()) { reason = r; break; }
                }
            }
        } catch (Exception ignored) {}
        return new Error(status, reason, msg);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** What went wrong, in Telugu, with what to do (the setup guide's words). */
    static String telugu(Exception e) {
        if (!(e instanceof Error)) return "నెట్ / Google నుంచి జవాబు రాలేదు (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").";
        Error g = (Error) e;
        String r = g.reason;
        if ("NO_KEY".equals(r)) return "Google Maps key ఇంకా పెట్టలేదు (⚙️ → 🗺️ Jarvis Maps).";
        if ("NO_GEMINI_MODEL".equals(r)) return "Gemini మోడల్ ఎంచుకోలేదు (⚙️ → Jarvis మెదడు): Google Maps వివరాలు లేకుండా చెబుతున్నాను.";
        if ("NO_GEMINI_KEY".equals(r)) return "Gemini key లేదు: Google Maps వివరాలు లేకుండా చెబుతున్నాను.";
        if (g.status == 400 && g.getMessage().toLowerCase(Locale.ROOT).contains("google_maps") || g.getMessage().toLowerCase(Locale.ROOT).contains("googlemaps"))
            return "మీ Gemini మోడల్ Google Maps వివరాలు ఇవ్వదు (ఉదా: gemini-2.5-flash ఇస్తుంది; ⚙️ → Jarvis మెదడు లో మార్చొచ్చు).";
        if (r.contains("ANDROID_APP_BLOCKED")) return "key ని 'Android apps' కి లాక్ చేశారు కానీ Jarvis గుర్తు సరిపోలలేదు: package com.anil.jarvis, SHA-1 సరిగ్గా ఉందో చూడండి.";
        if (r.contains("API_KEY_SERVICE_BLOCKED") || r.contains("SERVICE_DISABLED") || g.getMessage().contains("has not been used") || g.getMessage().contains("is disabled"))
            return "ఈ Google సర్వీస్ మీ ప్రాజెక్ట్‌లో ఆన్ చేయలేదు (Map Tiles API / Routes API / Places API (New)). గైడ్‌లో 'APIs ఆన్ చేయడం' చూడండి.";
        if (r.contains("API_KEY_INVALID") || g.getMessage().toLowerCase(Locale.ROOT).contains("api key not valid")) return "Maps key తప్పు. మళ్ళీ కాపీ చేసి పెట్టండి.";
        if (r.contains("BILLING") || g.getMessage().toLowerCase(Locale.ROOT).contains("billing")) return "Google Cloud లో billing ఆన్ చేయాలి (ఉచిత పరిమితి లోపల ఖర్చు ₹0).";
        if (g.status == 429 || r.contains("RESOURCE_EXHAUSTED")) return "Google పరిమితి ఈ రోజుకి అయిపోయింది / మరీ వేగంగా అడిగాను. కాసేపు ఆగి మళ్ళీ.";
        if (g.status == 403) return "Google అనుమతి ఇవ్వలేదు (" + (r.isEmpty() ? "403" : r) + "). key పరిమితులు చూడండి.";
        return "Google తప్పు చెప్పింది (" + g.status + (r.isEmpty() ? "" : " " + r) + ").";
    }

    // ---------------------------------------------------------------- the map's pictures (Map Tiles API)

    /** A tile session (roadmap / satellite, traffic on or off), made once and kept until a day before Google's expiry. */
    static String session(Context c, boolean satellite, boolean traffic) throws Exception { return session(c, satellite, traffic, false); }

    /** force: ask Google for a new one even if one is kept (the key test: Google must really answer). */
    static synchronized String session(Context c, boolean satellite, boolean traffic, boolean force) throws Exception {
        String kind = (satellite ? "sat" : "road") + (traffic ? "_tr" : "");
        SharedPreferences p = store(c);
        String s = p.getString("ses_" + kind, "");
        long exp = p.getLong("ses_" + kind + "_exp", 0);
        String keyTag = Integer.toHexString(key(c).hashCode()); // (a new key: a new session)
        if (!force && !s.isEmpty() && exp - System.currentTimeMillis() / 1000 > 86400 && keyTag.equals(p.getString("ses_" + kind + "_k", ""))) return s;
        JSONObject body = new JSONObject().put("mapType", satellite ? "satellite" : "roadmap").put("language", "te-IN").put("region", "IN")
                .put("scale", "scaleFactor2x").put("highDpi", true);
        JSONArray layers = new JSONArray();
        if (satellite) layers.put("layerRoadmap"); // (names over the photo)
        if (traffic) layers.put("layerTraffic");
        if (layers.length() > 0) body.put("layerTypes", layers);
        JSONObject r = send(c, "POST", "https://tile.googleapis.com/v1/createSession", body, null);
        s = r.optString("session", "");
        if (s.isEmpty()) throw new Error(0, "NO_SESSION", "Map Tiles API gave no session");
        long e = 0;
        try { e = Long.parseLong(r.optString("expiry", "0")); } catch (Exception ignored) {}
        p.edit().putString("ses_" + kind, s).putLong("ses_" + kind + "_exp", e).putString("ses_" + kind + "_k", keyTag).apply();
        return s;
    }

    /** Google refused the map's pictures (key, billing, quota): the kept sessions are dropped so the next try asks afresh. */
    static synchronized void dropSessions(Context c) {
        SharedPreferences.Editor e = store(c).edit();
        for (String k : new String[]{"road", "road_tr", "sat", "sat_tr"}) e.remove("ses_" + k).remove("ses_" + k + "_exp").remove("ses_" + k + "_k");
        e.apply();
    }

    /** One map tile's picture (512 px for 256 px of map: crisp on his screen). Its cache time from Google's header (seconds, 0 = none). */
    static byte[] tile(Context c, String session, int z, int x, int y, long[] maxAge) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://tile.googleapis.com/v1/2dtiles/" + z + "/" + x + "/" + y + "?session=" + session).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            String[] hd = headers(c, null);
            for (int i = 0; i + 1 < hd.length; i += 2) h.setRequestProperty(hd[i], hd[i + 1]);
            int st = h.getResponseCode();
            if (st >= 400) {
                InputStream es = h.getErrorStream();
                throw error(st, es == null ? "" : new String(readAll(es), StandardCharsets.UTF_8));
            }
            count(c, "tiles");
            if (maxAge != null) maxAge[0] = cacheSeconds(h.getHeaderField("Cache-Control"));
            try (InputStream in = h.getInputStream()) { return readAll(in); }
        } finally {
            h.disconnect();
        }
    }

    /** "public, max-age=86400" → 86400; "no-store" / "no-cache" → 0. */
    static long cacheSeconds(String cc) {
        if (cc == null) return 0;
        String s = cc.toLowerCase(Locale.ROOT);
        if (s.contains("no-store") || s.contains("no-cache")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max-age=(\\d+)").matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** The map data's credit for what is on the screen ("Map data ©2026 Google"), shown at the corner. */
    static String copyright(Context c, String session, int zoom, double north, double south, double east, double west) throws Exception {
        String u = String.format(Locale.US, "https://tile.googleapis.com/tile/v1/viewport?session=%s&zoom=%d&north=%.5f&south=%.5f&east=%.5f&west=%.5f",
                session, zoom, north, south, east, west);
        return send(c, "GET", u, null, null).optString("copyright", "");
    }

    // ---------------------------------------------------------------- routes (Routes API)

    /** One way from A to B. */
    static final class Route {
        List<double[]> pts = new ArrayList<>();
        long seconds, staticSeconds;
        double meters;
        String via = "", polyline = "", toll = "";
        List<String> steps = new ArrayList<>();
    }

    /**
     * Routes from (lat, lon) — going `heading` degrees when known (-1 not) — to a place (lat, lon), by car or two-wheeler;
     * with traffic; the toll when Google knows it; up to 3 ways when alternatives.
     */
    static List<Route> routes(Context c, double lat, double lon, float heading, double toLat, double toLon, boolean bike, boolean alternatives) throws Exception {
        JSONObject origin = new JSONObject().put("location", latLng(lat, lon, heading));
        JSONObject dest = new JSONObject().put("location", latLng(toLat, toLon, -1));
        JSONObject body = new JSONObject().put("origin", origin).put("destination", dest)
                .put("travelMode", bike ? "TWO_WHEELER" : "DRIVE").put("routingPreference", "TRAFFIC_AWARE")
                .put("computeAlternativeRoutes", alternatives).put("languageCode", "en").put("units", "METRIC")
                .put("extraComputations", new JSONArray().put("TOLLS"));
        String mask = "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.description,"
                + "routes.travelAdvisory.tollInfo,routes.legs.steps.navigationInstruction.instructions";
        JSONObject r = send(c, "POST", "https://routes.googleapis.com/directions/v2:computeRoutes", body, mask);
        count(c, "routes_pro"); // (traffic + toll / two-wheeler: Google's dearer kind, fewer free)
        return parseRoutes(r);
    }

    /**
     * Just the line of the way (no traffic, no toll: the cheaper kind; with the way he faces it is Google's "Pro" kind,
     * 5,000 free a month), for searching places along it; the way he faces when known, so it never starts with a U-turn
     * on a divided highway. Its km and time are without traffic.
     */
    static List<Route> line(Context c, double lat, double lon, float heading, double toLat, double toLon) throws Exception {
        JSONObject body = new JSONObject().put("origin", new JSONObject().put("location", latLng(lat, lon, heading)))
                .put("destination", new JSONObject().put("location", latLng(toLat, toLon, -1)))
                .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_UNAWARE").put("languageCode", "en").put("units", "METRIC");
        JSONObject r = send(c, "POST", "https://routes.googleapis.com/directions/v2:computeRoutes", body,
                "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.description,routes.legs.steps.navigationInstruction.instructions");
        count(c, "routes");
        return parseRoutes(r);
    }

    private static JSONObject latLng(double lat, double lon, float heading) throws Exception {
        JSONObject l = new JSONObject().put("latLng", new JSONObject().put("latitude", lat).put("longitude", lon));
        if (heading >= 0) l.put("heading", Math.round(heading) % 360);
        return l;
    }

    /** The Routes API answer → routes (kept separate so the desk test can check it). */
    static List<Route> parseRoutes(JSONObject r) {
        List<Route> out = new ArrayList<>();
        JSONArray rs = r == null ? null : r.optJSONArray("routes");
        for (int i = 0; rs != null && i < rs.length(); i++) {
            JSONObject o = rs.optJSONObject(i);
            if (o == null) continue;
            Route t = new Route();
            t.seconds = MapMath.seconds(o.optString("duration"));
            t.staticSeconds = MapMath.seconds(o.optString("staticDuration"));
            t.meters = o.optDouble("distanceMeters", 0);
            t.via = o.optString("description", "");
            JSONObject pl = o.optJSONObject("polyline");
            t.polyline = pl == null ? "" : pl.optString("encodedPolyline", "");
            t.pts = MapMath.decode(t.polyline);
            JSONObject adv = o.optJSONObject("travelAdvisory");
            JSONObject toll = adv == null ? null : adv.optJSONObject("tollInfo");
            JSONArray prices = toll == null ? null : toll.optJSONArray("estimatedPrice");
            if (prices != null && prices.length() > 0) {
                JSONObject m = prices.optJSONObject(0);
                double v = m.optLong("units", 0) + m.optLong("nanos", 0) / 1e9;
                t.toll = ("INR".equals(m.optString("currencyCode")) ? "₹" : m.optString("currencyCode") + " ") + Math.round(v);
            } else if (toll != null) t.toll = "ఉంది (ధర తెలియదు)";
            JSONArray legs = o.optJSONArray("legs");
            for (int a = 0; legs != null && a < legs.length(); a++) {
                JSONArray st = legs.optJSONObject(a) == null ? null : legs.optJSONObject(a).optJSONArray("steps");
                for (int b = 0; st != null && b < st.length() && t.steps.size() < 60; b++) {
                    JSONObject ni = st.optJSONObject(b) == null ? null : st.optJSONObject(b).optJSONObject("navigationInstruction");
                    String ins = ni == null ? "" : ni.optString("instructions", "").replace('\n', ' ').trim();
                    if (!ins.isEmpty()) t.steps.add(ins);
                }
            }
            if (t.pts.size() >= 2) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- places (Places API (New) Text Search)

    /** A place found. aheadM / aheadS: how far (m) and how long (s) from him by road (-1 not known). */
    static final class Place {
        String id = "", name = "", address = "", mapsUri = "";
        double lat, lon, rating;
        int ratings;
        Boolean open; // null = not known
        double aheadM = -1;
        long aheadS = -1;
    }

    /**
     * Places for words like "tea stall", "petrol pump": near (lat, lon) within radius m, or — when `along` is a route
     * line — along it from where he is (each with its distance ahead by road). openNow: only the ones open now (Google's
     * open-now field is the dearer kind: counted separately).
     */
    static List<Place> search(Context c, String words, double lat, double lon, double radiusM, String along, boolean wantOpen, int max) throws Exception {
        return search(c, words, lat, lon, radiusM, along, wantOpen, max, false);
    }

    /** dropFar: only the ones really near (a "near me" kind search); a place he names ("Tirupati") is kept however far. */
    static List<Place> search(Context c, String words, double lat, double lon, double radiusM, String along, boolean wantOpen, int max, boolean dropFar) throws Exception {
        JSONObject body = new JSONObject().put("textQuery", words).put("languageCode", "en").put("regionCode", "IN")
                .put("pageSize", Math.max(1, Math.min(20, max)));
        if (along != null && !along.isEmpty()) {
            body.put("searchAlongRouteParameters", new JSONObject().put("polyline", new JSONObject().put("encodedPolyline", along)));
            body.put("routingParameters", new JSONObject().put("origin", new JSONObject().put("latitude", lat).put("longitude", lon)).put("travelMode", "DRIVE"));
        } else {
            body.put("locationBias", new JSONObject().put("circle", new JSONObject()
                    .put("center", new JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", Math.max(500, Math.min(50000, radiusM)))));
        }
        if (wantOpen) body.put("openNow", true);
        String mask = "places.id,places.displayName,places.formattedAddress,places.location,places.googleMapsUri"
                + (wantOpen ? ",places.currentOpeningHours.openNow,places.rating,places.userRatingCount" : "")
                + (along != null && !along.isEmpty() ? ",routingSummaries" : "");
        JSONObject r = send(c, "POST", "https://places.googleapis.com/v1/places:searchText", body, mask);
        count(c, wantOpen ? "places_open" : "places");
        List<Place> out = parsePlaces(r);
        if (along == null || along.isEmpty()) { // (straight-line km when not along a way; for a "near me" search Google's hint isn't a fence: far ones dropped)
            List<Place> near = new ArrayList<>();
            for (Place p : out) {
                p.aheadM = MapMath.meters(lat, lon, p.lat, p.lon);
                if (!dropFar || p.aheadM <= Math.max(radiusM * 1.5, 3000)) near.add(p);
            }
            return near;
        }
        return out;
    }

    /** The Text Search answer → places (with their distance ahead when routing summaries came). */
    static List<Place> parsePlaces(JSONObject r) {
        List<Place> out = new ArrayList<>();
        JSONArray ps = r == null ? null : r.optJSONArray("places");
        JSONArray sums = r == null ? null : r.optJSONArray("routingSummaries");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject o = ps.optJSONObject(i);
            if (o == null) continue;
            Place p = new Place();
            p.id = o.optString("id", "");
            JSONObject dn = o.optJSONObject("displayName");
            p.name = dn == null ? "" : dn.optString("text", "");
            p.address = o.optString("formattedAddress", "");
            p.mapsUri = o.optString("googleMapsUri", "");
            JSONObject loc = o.optJSONObject("location");
            if (loc == null) continue;
            p.lat = loc.optDouble("latitude");
            p.lon = loc.optDouble("longitude");
            p.rating = o.optDouble("rating", 0);
            p.ratings = o.optInt("userRatingCount", 0);
            JSONObject oh = o.optJSONObject("currentOpeningHours");
            if (oh != null && oh.has("openNow")) p.open = oh.optBoolean("openNow");
            JSONObject s = sums == null ? null : sums.optJSONObject(i);
            JSONArray legs = s == null ? null : s.optJSONArray("legs");
            if (legs != null && legs.length() > 0) {
                JSONObject l0 = legs.optJSONObject(0);
                p.aheadM = l0.optDouble("distanceMeters", -1);
                p.aheadS = MapMath.seconds(l0.optString("duration"));
            }
            out.add(p);
        }
        return out;
    }

    // ---------------------------------------------------------------- the road's details (Gemini + Grounding with Google Maps)

    /** Facts from Google Maps (English) and the places they came from ({title, uri}). */
    static final class Notes {
        String text = "";
        List<String[]> sources = new ArrayList<>();
    }

    /**
     * Asks Gemini, grounded on Google Maps at his position, what lies ahead on this road (English in and out: Google's
     * Maps grounding is English only; Jarvis says it in Telugu). Uses his Gemini key and his chosen Gemini model.
     */
    static Notes roadNotes(Context c, double lat, double lon, float heading, String road, String hint) throws Exception {
        Prefs p = new Prefs(c);
        String gk = p.geminiKey().trim();
        if (gk.isEmpty()) throw new Error(0, "NO_GEMINI_KEY", "No Gemini key");
        String model = p.geminiModel().trim();
        if (model.isEmpty()) throw new Error(0, "NO_GEMINI_MODEL", "No Gemini model chosen"); // (Jarvis never picks a model by himself)
        String q = String.format(Locale.US, "A driver in India is at latitude %.5f, longitude %.5f%s%s. %s"
                        + "Using Google Maps, tell in short plain facts: where this road leads (the next major towns or cities in order, with approximate km from here), "
                        + "the next toll plaza if any, and good places to stop for tea, coffee, food or fuel in the next 30 km. No greetings, at most 8 short lines.",
                lat, lon, heading >= 0 ? ", heading " + Math.round(heading) + " degrees (" + compass(heading) + ")" : "",
                road == null || road.isEmpty() ? "" : ", on " + road, hint == null ? "" : hint + " ");
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", q)))))
                .put("tools", new JSONArray().put(new JSONObject().put("googleMaps", new JSONObject())))
                .put("toolConfig", new JSONObject().put("retrievalConfig", new JSONObject().put("latLng", new JSONObject().put("latitude", lat).put("longitude", lon))));
        JSONObject r = gemini(model, body, gk); // (his own model; one without Google Maps grounding says so: see telugu())
        count(c, "gemini_maps");
        return parseNotes(r);
    }

    /** generateContent with short time limits (he is driving: 25 s at most), the cost meter kept. */
    private static JSONObject gemini(String model, JSONObject body, String key) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(10000);
            h.setReadTimeout(25000);
            h.setRequestProperty("x-goog-api-key", key);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = h.getOutputStream()) { o.write(bytes); }
            int st = h.getResponseCode();
            InputStream in = st >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            if (st >= 400) throw error(st, text);
            JSONObject json = new JSONObject(text);
            try { Usage.fromResponse(url, json); } catch (Throwable ignored) {}
            return json;
        } finally {
            h.disconnect();
        }
    }

    static Notes parseNotes(JSONObject r) {
        Notes n = new Notes();
        JSONArray cs = r == null ? null : r.optJSONArray("candidates");
        JSONObject c0 = cs == null || cs.length() == 0 ? null : cs.optJSONObject(0);
        if (c0 == null) return n;
        JSONObject content = c0.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder b = new StringBuilder();
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject pt = parts.optJSONObject(i);
            if (pt == null || pt.optBoolean("thought")) continue;
            b.append(pt.optString("text", ""));
        }
        n.text = b.toString().trim();
        JSONObject gm = c0.optJSONObject("groundingMetadata");
        JSONArray ch = gm == null ? null : gm.optJSONArray("groundingChunks");
        for (int i = 0; ch != null && i < ch.length() && n.sources.size() < 8; i++) {
            JSONObject m = ch.optJSONObject(i) == null ? null : ch.optJSONObject(i).optJSONObject("maps");
            if (m == null) continue;
            n.sources.add(new String[]{m.optString("title", ""), m.optString("uri", "")});
        }
        return n;
    }

    static String compass(float deg) {
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.floor(((deg % 360 + 360) % 360 + 22.5) / 45) % 8];
    }

    // ---------------------------------------------------------------- 🔑 key test (Settings)

    /** One line per service: ✓ / ✗ with the reason in Telugu (the key itself never shown). Call off the main thread. */
    static String test(Context c) {
        StringBuilder b = new StringBuilder();
        double lat = 17.385, lon = 78.4867; // (a fixed point in Hyderabad: nothing about him is sent for the test)
        try { session(c, false, false, true); b.append("✓ మ్యాప్ (Map Tiles API)\n"); } catch (Exception e) { b.append("✗ మ్యాప్ (Map Tiles API): ").append(telugu(e)).append('\n'); }
        try {
            List<Route> r = line(c, lat, lon, -1, 17.44, 78.50);
            b.append(r.isEmpty() ? "✗ దారులు (Routes API): జవాబులో దారి లేదు\n" : "✓ దారులు (Routes API)\n");
        } catch (Exception e) { b.append("✗ దారులు (Routes API): ").append(telugu(e)).append('\n'); }
        try {
            List<Place> ps = search(c, "tea stall", lat, lon, 3000, null, false, 1);
            b.append(ps.isEmpty() ? "✓ ప్లేస్ వెతకడం (Places API) (ఏమీ దొరకలేదు, కానీ పనిచేస్తోంది)\n" : "✓ ప్లేస్ వెతకడం (Places API)\n");
        } catch (Exception e) { b.append("✗ ప్లేస్ వెతకడం (Places API (New)): ").append(telugu(e)).append('\n'); }
        b.append("ℹ️ Jarvis గుర్తు: com.anil.jarvis · SHA-1 ").append(certShown(c));
        return b.toString().trim();
    }
}
