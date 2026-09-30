package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Places around a point from OpenStreetMap (free, no key): chargers, medical shops, ATMs, hospitals, fuel, food. */
final class Nearby {
    private Nearby() {}

    static final String[][] KINDS = {
            {"pharmacy", "మెడికల్ షాప్", "amenity=pharmacy"}, {"atm", "ATM", "amenity=atm"}, {"hospital", "ఆసుపత్రి", "amenity=hospital"},
            {"clinic", "క్లినిక్", "amenity=clinic"}, {"fuel", "పెట్రోల్ బంక్", "amenity=fuel"}, {"restaurant", "హోటల్", "amenity=restaurant"},
            {"fast_food", "టిఫిన్ / ఫాస్ట్ ఫుడ్", "amenity=fast_food"}, {"cafe", "టీ / కాఫీ", "amenity=cafe"}, {"police", "పోలీస్ స్టేషన్", "amenity=police"},
            {"bank", "బ్యాంక్", "amenity=bank"}, {"charging", "EV ఛార్జింగ్", "amenity=charging_station"},
    };

    /** The OSM tag for what he asked ("మెడికల్ షాప్", "ATM", "hospital"...), or null. */
    static String[] kind(String said) {
        String s = said == null ? "" : said.toLowerCase(Locale.ROOT);
        if (s.contains("మెడికల్") || s.contains("మందుల") || s.contains("pharma") || s.contains("medical")) return KINDS[0];
        if (s.contains("atm") || s.contains("ఏటీఎం") || s.contains("డబ్బులు తీయ")) return KINDS[1];
        if (s.contains("ఆసుపత్రి") || s.contains("hospital") || s.contains("హాస్పిటల్")) return KINDS[2];
        if (s.contains("క్లినిక్") || s.contains("clinic") || s.contains("డాక్టర్")) return KINDS[3];
        if (s.contains("పెట్రోల్") || s.contains("బంక్") || s.contains("fuel") || s.contains("petrol") || s.contains("డీజిల్")) return KINDS[4];
        if (s.contains("టిఫిన్") || s.contains("fast")) return KINDS[6];
        if (s.contains("టీ") || s.contains("కాఫీ") || s.contains("cafe") || s.contains("tea")) return KINDS[7];
        if (s.contains("పోలీస్") || s.contains("police")) return KINDS[8];
        if (s.contains("బ్యాంక్") || s.contains("bank")) return KINDS[9];
        if (s.contains("ఛార్జ") || s.contains("charg")) return KINDS[10];
        if (s.contains("హోటల్") || s.contains("భోజనం") || s.contains("restaurant") || s.contains("hotel") || s.contains("food")) return KINDS[5];
        return null;
    }

    /** Up to max places of that kind within radius metres, nearest first: {name, km, open_24h, hours, maps_place, phone}. */
    static List<JSONObject> find(double lat, double lon, String tag, int radius, int max) throws Exception {
        String[] kv = tag.split("=");
        String f = "[" + kv[0] + "=" + kv[1] + "]";
        // close by first (the server cuts long lists in its own order, not by distance), wider only if too few
        JSONArray el = null;
        for (int r : new int[]{Math.min(1500, radius), radius}) {
            String q = "[out:json][timeout:20];(node(around:" + r + "," + lat + "," + lon + ")" + f + ";"
                    + "way(around:" + r + "," + lat + "," + lon + ")" + f + ";);out center 150;";
            el = Http.get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")).optJSONArray("elements");
            if (r == radius || (el != null && el.length() >= 3)) break;
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.getJSONObject(i);
            JSONObject c = e.has("lat") ? e : e.optJSONObject("center");
            if (c == null) continue;
            JSONObject t = e.optJSONObject("tags");
            if (t == null) t = new JSONObject();
            double la = c.optDouble("lat"), lo = c.optDouble("lon");
            String hours = t.optString("opening_hours", "");
            String name = t.optString("name:te", t.optString("name", t.optString("brand", t.optString("operator", ""))));
            if (name.isEmpty()) name = kv[1].replace('_', ' ');
            list.add(new JSONObject().put("name", name).put("km", Math.round(GeoReminders.distance(lat, lon, la, lo) / 100f) / 10.0)
                    .put("open_24h", hours.contains("24/7")).put("hours", hours).put("phone", t.optString("phone", t.optString("contact:phone", "")))
                    .put("maps_place", la + "," + lo));
        }
        list.sort((x, y) -> Double.compare(x.optDouble("km"), y.optDouble("km")));
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }
}
