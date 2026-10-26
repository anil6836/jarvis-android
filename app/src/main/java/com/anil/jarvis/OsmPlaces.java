package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 📍 Stage 4 (27 Oct): ⑮'s chips — ☕ ⛽ 🍛 🏧 🚻 🔧 🅿️ — from OpenStreetMap (Overpass): the places of that kind round him
 * (3 km), the nearest first, as standing pins on the 3D map and a list; his tap on one starts our free way there. The
 * query and its answer are plain maths here (desk-tested); the asking happens off the main thread.
 */
final class OsmPlaces {
    private OsmPlaces() {}

    static final String[] KINDS = {"tea", "petrol", "restaurant", "atm", "toilet", "puncture", "parking"};
    static final String[] SIGNS = {"☕", "⛽", "🍛", "🏧", "🚻", "🔧", "🅿️"};
    static final String[] NAMES = {"టీ / కాఫీ", "పెట్రోల్ బంక్", "హోటల్", "ATM", "టాయిలెట్", "పంక్చర్ / మెకానిక్", "పార్కింగ్"};

    static final class Place {
        final String name;
        final double lat, lon;
        final float metres;
        Place(String name, double lat, double lon, float metres) { this.name = name; this.lat = lat; this.lon = lon; this.metres = metres; }
    }

    static int index(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
        return -1;
    }

    /** The OSM tags of a chip's kind (Overpass filters). Pure. */
    static String[] filters(String kind) {
        switch (kind == null ? "" : kind) {
            case "tea": return new String[]{"[amenity=cafe]", "[shop=tea]", "[shop=coffee]"};
            case "petrol": return new String[]{"[amenity=fuel]"};
            case "restaurant": return new String[]{"[amenity=restaurant]", "[amenity=fast_food]", "[amenity=food_court]"};
            case "atm": return new String[]{"[amenity=atm]", "[amenity=bank][atm=yes]"};
            case "toilet": return new String[]{"[amenity=toilets]"};
            case "puncture": return new String[]{"[shop=tyres]", "[shop=car_repair]", "[craft=car_repair]"};
            case "parking": return new String[]{"[amenity=parking]"};
            default: return new String[0];
        }
    }

    /** The Overpass question: that kind's nodes and ways (their middle) within r metres of (lat, lon). Pure. */
    static String query(String kind, double lat, double lon, int r) {
        StringBuilder b = new StringBuilder("[out:json][timeout:20];(");
        String at = String.format(Locale.ROOT, "(around:%d,%.5f,%.5f)", r, lat, lon);
        for (String f : filters(kind)) b.append("node").append(f).append(at).append(";way").append(f).append(at).append(";");
        return b.append(");out center 60;").toString();
    }

    /** Overpass's answer as places, the nearest first, at most max (no name: the kind's own name). Pure. */
    static List<Place> parse(JSONArray el, String kind, double lat, double lon, int max) {
        List<Place> out = new ArrayList<>();
        int k = index(kind);
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.optJSONObject(i);
            if (e == null) continue;
            JSONObject c = e.has("center") ? e.optJSONObject("center") : e;
            if (c == null || !c.has("lat")) continue;
            double la = c.optDouble("lat"), lo = c.optDouble("lon");
            JSONObject t = e.optJSONObject("tags");
            String name = t == null ? "" : t.optString("name:en", t.optString("name", t.optString("brand", "")));
            if (name.isEmpty()) name = k >= 0 ? NAMES[k] : kind;
            out.add(new Place(name, la, lo, (float) MapMath.meters(lat, lon, la, lo)));
        }
        out.sort((a, b) -> Float.compare(a.metres, b.metres));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** "1.2 కి.మీ" / "350 మీ". Pure. */
    static String dist(float m) {
        return m < 1000 ? (Math.round(m / 10) * 10) + " మీ" : String.format(Locale.ROOT, "%.1f కి.మీ", m / 1000f);
    }
}
