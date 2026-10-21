package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🗺️ Every kind of place Jarvis Maps can look for ("➕ అన్ని చోట్లు"): its Telugu name and emoji, its group, the words
 * Google's search knows, the free map's (OpenStreetMap) filter, how far around him it makes sense to look, and the
 * words he may say for it (Telugu and English). No Android here, so the desk tests can check the matching.
 */
final class PlaceKinds {
    private PlaceKinds() {}

    /** One kind of place. */
    static final class K {
        final String id, emoji, te, group, google, osm;
        final int radiusKm;
        final String[] words;
        K(String id, String emoji, String te, String group, String google, String osm, int radiusKm, String... words) {
            this.id = id; this.emoji = emoji; this.te = te; this.group = group; this.google = google; this.osm = osm; this.radiusKm = radiusKm; this.words = words;
        }
        String label() { return emoji + " " + te; }
    }

    /** The groups, in the order the sheet shows them. */
    static final String[][] GROUPS = {
            {"food", "🍛 తినడానికి"}, {"stay", "🛏️ ఉండడానికి"}, {"health", "🏥 ఆరోగ్యం"}, {"shop", "🛍️ షాపింగ్"},
            {"fun", "🎬 వినోదం"}, {"sights", "🏖️ చూడదగ్గవి"}, {"travel", "🚌 ప్రయాణం"}, {"service", "🔧 సేవలు"}, {"worship", "🙏 ప్రార్థన"}};

    /**
     * The order matters for matching: a more exact kind comes before a wider one ("ఎమర్జెన్సీ హాస్పిటల్" is the
     * emergency kind, not just a hospital; "బిర్యానీ" before the plain restaurant; rooms before the eatery, so
     * "హోటల్ రూమ్స్" is rooms; parking before the park). The sheet shows each group in this order too.
     */
    static final K[] ALL = {
            // 🛏️ stay
            new K("resort", "🏝️", "రిసార్ట్", "stay", "resort", "[leisure=resort]", 40, "resort", "రిసార్ట్"),
            new K("rooms", "🛏️", "హోటల్ రూమ్స్ / లాడ్జ్", "stay", "hotel rooms or lodge", "[tourism~\"^(hotel|motel|guest_house)$\"]", 10,
                    "room", "rooms", "lodge", "lodging", "stay", "రూమ్", "రూమ్స్", "లాడ్జ్", "లాడ్జి"),
            // 🍛 food
            new K("biryani", "🍗", "బిర్యానీ", "food", "biryani restaurant", "[amenity~\"^(restaurant|fast_food)$\"][cuisine~\"biryani|indian\",i]", 8, "biryani", "బిర్యానీ"),
            new K("tiffin", "🥞", "టిఫిన్ సెంటర్", "food", "breakfast tiffin center", "[amenity~\"^(restaurant|fast_food|cafe)$\"]", 5, "tiffin", "breakfast", "idli", "dosa", "టిఫిన్", "ఇడ్లీ", "దోశ", "టిఫిన్‌"),
            new K("veg", "🥗", "వెజ్ హోటల్", "food", "pure vegetarian restaurant", "[amenity=restaurant][diet:vegetarian~\"yes|only\"]", 8, "veg", "vegetarian", "వెజ్", "శాకాహార"),
            new K("restaurant", "🍛", "హోటల్ / దాబా", "food", "restaurant or dhaba", "[amenity~\"^(restaurant|fast_food|food_court)$\"]", 5,
                    "restaurant", "food", "dhaba", "meals", "lunch", "dinner", "హోటల్", "భోజనం", "దాబా", "ధాబా", "రెస్టారెంట్", "తినడానికి", "మీల్స్"),
            new K("tea", "☕", "టీ / కాఫీ", "food", "tea stall or coffee shop", "[amenity=cafe]", 5, "tea", "coffee", "cafe", "chai", "టీ", "కాఫీ", "చాయ్", "ఛాయ్"),
            new K("bakery", "🍰", "బేకరీ / స్వీట్స్", "food", "bakery or sweet shop", "[shop~\"^(bakery|confectionery|pastry)$\"]", 5, "bakery", "sweets", "sweet", "cake", "బేకరీ", "స్వీట్స్", "స్వీట్", "మిఠాయి", "కేక్"),
            new K("icecream", "🍦", "ఐస్ క్రీమ్ / జ్యూస్", "food", "ice cream or juice shop", "[amenity=ice_cream]", 5, "ice cream", "icecream", "juice", "ఐస్ క్రీమ్", "ఐస్‌క్రీమ్", "జ్యూస్"),
            // 🏥 health
            new K("emergency", "🚑", "24 గంటల ఎమర్జెన్సీ", "health", "hospital with 24 hour emergency", "[amenity=hospital][emergency=yes]", 20,
                    "emergency", "casualty", "trauma", "ఎమర్జెన్సీ", "అత్యవసర", "క్యాజువాలిటీ"),
            new K("hospital", "🏥", "హాస్పిటల్", "health", "hospital", "[amenity~\"^(hospital|clinic)$\"]", 10, "hospital", "ఆసుపత్రి", "హాస్పిటల్", "దవాఖానా"),
            new K("clinic", "🩺", "క్లినిక్ / డాక్టర్", "health", "clinic doctor", "[amenity~\"^(clinic|doctors)$\"]", 8, "clinic", "doctor", "క్లినిక్", "డాక్టర్"),
            new K("medical", "💊", "మెడికల్ షాప్", "health", "medical shop pharmacy", "[amenity=pharmacy]", 5, "medical", "pharmacy", "pharma", "chemist", "మెడికల్", "మందుల"),
            // 🛍️ shopping
            new K("mall", "🛍️", "షాపింగ్ మాల్", "shop", "shopping mall", "[shop=mall]", 25, "mall", "malls", "shopping", "మాల్", "మాల్స్", "షాపింగ్"),
            new K("supermarket", "🛒", "సూపర్ మార్కెట్", "shop", "supermarket", "[shop=supermarket]", 8, "supermarket", "grocery", "kirana", "సూపర్ మార్కెట్", "సూపర్‌మార్కెట్", "కిరాణా"),
            new K("clothes", "👕", "బట్టల షాప్", "shop", "clothing store", "[shop=clothes]", 10, "clothes", "dress", "saree", "textile", "బట్టలు", "బట్టల", "చీరలు", "చీర"),
            new K("mobile", "📱", "మొబైల్ షాప్", "shop", "mobile phone shop", "[shop=mobile_phone]", 8, "mobile shop", "phone shop", "మొబైల్"),
            // 🎬 fun
            new K("cinema", "🎬", "సినిమా థియేటర్", "fun", "movie theater", "[amenity=cinema]", 25,
                    "cinema", "theatre", "theater", "movie", "movies", "multiplex", "సినిమా", "థియేటర్", "థియేటర్లు", "సినిమాహాల్"),
            new K("gamezone", "🎳", "గేమ్ జోన్ / వాటర్ పార్క్", "fun", "amusement park or game zone", "[leisure~\"^(amusement_arcade|water_park)$\"]", 30,
                    "game zone", "gamezone", "amusement", "water park", "గేమ్ జోన్", "వాటర్ పార్క్"),
            new K("parking", "🅿️", "పార్కింగ్", "travel", "parking", "[amenity=parking]", 3, "parking", "పార్కింగ్"),
            new K("park", "🌳", "పార్క్", "fun", "park", "[leisure=park]", 10, "park", "garden", "పార్క్", "పార్కు"),
            // 🏖️ worth seeing
            new K("beach", "🏖️", "బీచ్", "sights", "beach", "[natural=beach]", 80, "beach", "beaches", "బీచ్", "బీచ్‌లు", "సముద్రం"),
            new K("waterfall", "💧", "జలపాతం", "sights", "waterfall", "[waterway=waterfall]", 80, "waterfall", "waterfalls", "falls", "జలపాతం", "జలపాతాలు"),
            new K("viewpoint", "🌄", "వ్యూ పాయింట్", "sights", "viewpoint", "[tourism=viewpoint]", 50, "viewpoint", "view point", "వ్యూ పాయింట్"),
            new K("dam", "🏞️", "డ్యామ్ / చెరువు", "sights", "dam or lake", "[waterway=dam]", 50, "dam", "lake", "reservoir", "డ్యామ్", "చెరువు", "సరస్సు", "జలాశయం"),
            new K("museum", "🏛️", "మ్యూజియం / కోట", "sights", "museum or fort", "[tourism=museum]", 50, "museum", "fort", "మ్యూజియం", "కోట"),
            new K("attraction", "📸", "చూడదగ్గ ప్రదేశాలు", "sights", "tourist attraction", "[tourism=attraction]", 50,
                    "tourist", "attraction", "attractions", "sightseeing", "చూడదగ్గ", "పర్యాటక", "టూరిస్ట్"),
            // 🚌 travel
            new K("busstand", "🚌", "బస్ స్టాండ్", "travel", "bus station", "[amenity=bus_station]", 15, "bus stand", "bus station", "busstand", "బస్ స్టాండ్", "బస్టాండ్", "బస్‌స్టాండ్"),
            new K("railway", "🚉", "రైల్వే స్టేషన్", "travel", "railway station", "[railway=station]", 25, "railway", "train", "రైల్వే", "రైలు", "రైల్వేస్టేషన్"),
            new K("airport", "✈️", "ఎయిర్‌పోర్ట్", "travel", "airport", "[aeroway=aerodrome]", 100, "airport", "ఎయిర్‌పోర్ట్", "విమానాశ్రయం"),
            new K("ev", "⚡", "EV ఛార్జర్", "travel", "EV charging station", "[amenity=charging_station]", 20, "ev", "charging", "charger", "ఛార్జర్", "ఛార్జింగ్", "ఛార్జ్"),
            new K("petrol", "⛽", "పెట్రోల్ బంక్", "travel", "petrol pump", "[amenity=fuel]", 10, "petrol", "diesel", "fuel", "bunk", "cng", "పెట్రోల్", "డీజిల్", "బంక్"),
            new K("toll", "🚧", "టోల్ గేట్", "travel", "toll plaza", "[barrier=toll_booth]", 50, "toll", "టోల్"),
            // 🔧 services
            new K("mechanic", "🔧", "మెకానిక్ / పంక్చర్", "service", "puncture shop or mechanic", "[shop~\"^(car_repair|tyres|motorcycle_repair)$\"]", 10,
                    "mechanic", "puncture", "garage", "tyre", "tire", "మెకానిక్", "పంక్చర్", "గ్యారేజ్", "టైర్"),
            new K("service", "🛠️", "సర్వీస్ సెంటర్", "service", "car and bike service center", "[shop~\"^(car_repair|motorcycle)$\"]", 15, "service center", "showroom", "సర్వీస్ సెంటర్", "షోరూమ్"),
            new K("carwash", "🚿", "కార్ వాష్", "service", "car wash", "[amenity=car_wash]", 10, "car wash", "carwash", "వాటర్ వాష్", "కార్ వాష్"),
            new K("atm", "🏧", "ATM", "service", "ATM", "[amenity=atm]", 5, "atm", "ఏటీఎం"),
            new K("bank", "🏦", "బ్యాంక్", "service", "bank", "[amenity=bank]", 8, "bank", "బ్యాంక్", "బ్యాంకు"),
            new K("police", "👮", "పోలీస్ స్టేషన్", "service", "police station", "[amenity=police]", 15, "police", "పోలీస్"),
            new K("post", "📮", "పోస్ట్ ఆఫీస్", "service", "post office", "[amenity=post_office]", 10, "post office", "పోస్ట్ ఆఫీస్", "పోస్టాఫీస్"),
            new K("toilet", "🚻", "టాయిలెట్", "service", "public toilet", "[amenity=toilets]", 5, "toilet", "washroom", "restroom", "టాయిలెట్", "బాత్రూమ్", "బాత్ రూమ్"),
            // 🙏 worship
            new K("church", "⛪", "చర్చి", "worship", "church", "[amenity=place_of_worship][religion=christian]", 10, "church", "చర్చి"),
            new K("temple", "🛕", "గుడి", "worship", "temple", "[amenity=place_of_worship][religion=hindu]", 10, "temple", "గుడి", "ఆలయం", "దేవాలయం"),
            new K("mosque", "🕌", "మసీదు", "worship", "mosque", "[amenity=place_of_worship][religion=muslim]", 10, "mosque", "మసీదు"),
    };

    static K byId(String id) {
        if (id == null) return null;
        for (K k : ALL) if (k.id.equals(id)) return k;
        return null;
    }

    static List<K> inGroup(String group) {
        List<K> out = new ArrayList<>();
        for (K k : ALL) if (k.group.equals(group)) out.add(k);
        return out;
    }

    /** Small words that may stand with a kind ("దారిలో షాపింగ్ మాల్", "petrol bunk near me"). */
    static final java.util.Set<String> FILL = new java.util.HashSet<>(java.util.Arrays.asList(
            "shop", "shops", "stall", "near", "nearby", "me", "any", "a", "the", "for", "best", "good", "open", "in", "on", "my", "way", "route", "ahead", "around",
            "షాప్", "షాపు", "కొట్టు", "దగ్గర", "దగ్గర్లో", "ఏదైనా", "మంచి", "ఎక్కడ", "ఉంది", "ఉన్నాయి", "ఉన్నాయా", "తెరిచి", "నా", "దారిలో", "చుట్టూ", "చుట్టుపక్కల",
            "ముందు", "కావాలి", "ఉన్న", "ఏమైనా", "చూపించు", "వెతుకు", "లో"));

    /**
     * The kind only when the whole ask is kinds and small words ("దారిలో షాపింగ్ మాల్", "bus stand near me"); a name
     * with a kind in it is his words, not the kind ("Inorbit mall", "PVR theatre", "Hussain Sagar lake").
     */
    static K matchWhole(String what) {
        K k = match(what);
        if (k == null) return null;
        String w = " " + what.toLowerCase(Locale.ROOT).trim() + " ";
        for (K x : ALL) for (String key : x.words) if (key.contains(" ")) w = w.replace(" " + key + " ", " ");
        for (String t : w.trim().split("[\\s,.!?;:()/]+")) {
            if (t.isEmpty() || FILL.contains(t)) continue;
            if (match(t) == null) return null;
        }
        return k;
    }

    /** The kind his words name ("దారిలో షాపింగ్ మాల్", "beaches"), or null when they name none (a place's own name). */
    static K match(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (w.isEmpty()) return null;
        String[] tokens = w.split("[\\s,.!?;:()/]+");
        for (K k : ALL) for (String key : k.words) if (has(w, tokens, key)) return k;
        return null;
    }

    /**
     * English: a whole word (short ones exactly; longer ones may start a word: "theatres"); a phrase: inside the text.
     * Telugu: the word itself or it with a short ending ("థియేటర్లో"), never inside another word ("సిటీ" is not "టీ").
     */
    static boolean has(String w, String[] tokens, String key) {
        if (key.contains(" ")) return w.contains(key);
        if (key.matches("[a-z]+")) {
            for (String t : tokens) if (t.equals(key) || t.equals(key + "s") || t.equals(key + "es")) return true; // ("theatres", "beaches"; never "training")
            return false;
        }
        for (String t : tokens) if (t.equals(key) || (key.length() >= 3 && t.startsWith(key) && t.length() - key.length() <= 4)) return true;
        return false;
    }
}
