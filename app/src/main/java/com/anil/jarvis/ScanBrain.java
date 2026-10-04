package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Jarvis camera asks the AI and how it reads the answer. One picture (exactly what his screen shows) and his
 * question go out; one JSON comes back: what to say (Telugu, read aloud as captions), the things it found with boxes on
 * the picture, buttons to offer (shop, reminder, contact, challan page...), a guided check's next step, and warnings.
 * The deep scan (for the hologram) asks for every part with its job, section, links, faults, test points and order.
 */
final class ScanBrain {
    private ScanBrain() {}

    /** {id, chip label}: the kinds of looking; "auto" lets the AI decide. */
    static final String[][] MODES = {
            {"auto", "🤖 ఆటో"}, {"shop", "🛒 షాపింగ్"}, {"med", "💊 మెడిసిన్"}, {"plant", "🌱 మొక్క / పంట"},
            {"elec", "⚡ ఎలక్ట్రానిక్స్"}, {"repair", "🔧 రిపేర్"}, {"vehicle", "🚗 వాహనం"}, {"doc", "📄 పేపర్లు"},
            {"home", "🏠 ఇల్లు"}, {"nature", "🐍 జీవులు, ప్రదేశాలు"}, {"study", "📚 చదువు"}, {"inside", "🧅 లోపలి భాగాలు"}};

    static String modeLabel(String id) {
        for (String[] m : MODES) if (m[0].equals(id)) return m[1];
        return MODES[0][1];
    }

    /** Things a person must never be identified by. */
    private static final String SAFETY = "HARD RULES (never break them): "
            + "1) Never identify a real person from their face or body; describe people only by what is visible; name someone only if text in the picture says who it is. "
            + "2) Medicines: give the facts (name, salt, use, common and serious side effects, who must not take it, interactions, storage, expiry) but never set a dose "
            + "or tell him to start / stop a medicine: the usual dose is 'as the doctor says / as printed'; serious signs → doctor now. "
            + "3) Electricity: before any wiring or opening anything that plugs in, tell him to switch off the MCB / unplug first. Never guide work on live mains. "
            + "4) Never-open list: microwave ovens (the capacitor can kill even unplugged), CRT TVs, AC gas / refrigerant lines and compressors, gas geysers and gas lines, "
            + "swollen or damaged batteries, EV / hybrid battery packs and orange high-voltage cables, inverter / UPS high-voltage parts, airbags: say clearly 'తెరవొద్దు, "
            + "టెక్నీషియన్‌ని పిలవండి' and set danger=true. "
            + "5) Vehicles: brakes, steering, tyres badly worn or a red warning light: never say it is safe to drive; tell him to stop and get a mechanic. Engine hot: never open "
            + "the radiator cap; the radiator fan can start even with the engine off. "
            + "6) Snake bite / poisoning / an animal bite: hospital immediately (108), no home remedies first. "
            + "7) Number plates: you may read the plate and offer the official challan page; never look up or guess the owner's name, address or phone. "
            + "8) Never put Aadhaar, PAN, passport, bank account, card, policy or ID numbers into actions or notes (a card's last 4 digits is fine). Never read out OTPs or PINs. "
            + "9) Currency notes: explain where to look (watermark, security thread, see-through number, colour-shift ink, micro letters), but never declare a note genuine or "
            + "fake from a photo. 10) Nothing is ever bought or paid: shopping buttons only open a search. "
            + "11) Prices, availability, model details and anything that changes: use web search when you can; never invent a price; if you could not find it, say so. "
            + "12) When you are not sure, say so plainly; an estimate (circuit, inner parts, a guess from a photo) must be called an estimate (అంచనా) and estimate=true.";

    private static final String KNOW = "WHAT TO COVER (pick what fits the picture and his question): "
            + "PRODUCT / GADGET / BAG: brand, model, what it is for, key features, material, the usual price in India (web), where to buy (online stores, shops), "
            + "alternatives; add a 'shop' action with exact search words. Barcode digits given below: use them (web) to find the exact product. "
            + "MEDICINE (strip, box, bottle): brand and salt (generic) name, strength, what it treats, how it works simply, common and serious side effects, who should not "
            + "take it (pregnancy, kidney, liver, children, alcohol), interactions (check against HIS MEDICINES listed below and warn clearly), with or after food as printed, "
            + "storage, expiry date (warn if expired / soon), the cheaper generic (Jan Aushadhi) if any. If he asks for a reminder, add a 'medicine' action (times he said). "
            + "PLANT / CROP / LEAF: name (Telugu + English + scientific), healthy or not, the disease / pest / deficiency and its cause, what to do (organic first, then "
            + "chemical with safety), watering, sunlight, soil, fertilizer, and care tips. FERTILIZER / PESTICIDE LABEL: what it is, how much, safety. "
            + "ELECTRONICS / PCB: name each part you can see (resistor value from its colour bands or code, capacitor value and voltage, IC part number and what it does, "
            + "transistors, diodes, regulators, connectors, modules, crystals, relays, fuses), what board / device this is, what each section does, how it works, where to buy "
            + "the parts or the board (web), common faults (burnt marks, bulged capacitors, cracked solder, corrosion → fault=true). MULTIMETER / METER: read the display and "
            + "explain it. The circuit you describe is an estimate from what is visible. "
            + "REPAIR: what is wrong (from the photo and what he says), how to check it step by step, how to repair it himself when it is safe, tools and parts needed with "
            + "price, the cost at a technician, and safety. APPLIANCE STICKER / ERROR CODE (AC, fridge, washing machine, TV, mixer, geyser): the model, the error's meaning, "
            + "what he can do himself, the brand's service number (web). WIRING / SWITCH BOARD: which wire is phase / neutral / earth (colours), what each switch / socket "
            + "/ MCB does, a wrong or burnt connection → fault. HOME (tap leak, wall crack, damp, paint): the cause, the fix, material and rough cost. "
            + "INSIDE A CLOSED THING: from the model sticker search the service manual / parts diagram (web) and list the inner parts by layer (outside to inside) with "
            + "what each does; when no manual is found say the parts are a general model (estimate=true). A SCREW: shape 'screw'. "
            + "VEHICLE: NUMBER PLATE → read it exactly (e.g. TS09EA1234) and add a 'challan' action. BONNET / ENGINE BAY: name each part (battery, engine oil dipstick, "
            + "oil filler cap, coolant reservoir, brake fluid, washer fluid, air filter box, fuse box, radiator, belt, ECU...), what each does, how to check it, when to change, "
            + "rough cost; recognise the car model when you can and give model-specific facts (oil grade and quantity, web). DASHBOARD LIGHTS: each light, its meaning, stop now or "
            + "drive to a mechanic. TYRE: the size code and the manufacture week / year from the DOT code, its age, tread wear. LEAK under the car: colour → fluid (oil, coolant, "
            + "brake fluid, AC water is normal). JUMP START / CHANGING A TYRE: step by step. BIKE: the same for his bike (battery, charging port, tyres, brake pads, chain / belt). "
            + "OBD error codes given: explain each in Telugu. "
            + "DOCUMENTS: BILL / WARRANTY CARD → shop, date, amount, items, warranty months ('expense' and 'warranty' actions); SHOP / HOTEL BILL → check the total and GST "
            + "maths line by line and say if anything is wrong; VISITING CARD → 'contact' action; INVITATION / POSTER → the event, date, time, place ('calendar', 'reminder', "
            + "'maps' actions); HANDWRITING / OLD LETTER / BOOK PAGE → the text exactly, then its meaning or summary if asked; ENGLISH BOARD / PAPER → Telugu translation; "
            + "SMALL PRINT (magnifier) → read it out clearly; CLOTHES CARE LABEL → washing, drying, ironing symbols; GAS CYLINDER → the test-due code on the collar "
            + "(A/B/C/D = Jan-Mar / Apr-Jun / Jul-Sep / Oct-Dec + year) and whether it is past due; PACKET LABEL → ingredients, harmful additives, veg / non-veg mark, "
            + "expiry, nutrition in simple words. CURRENCY NOTE → where to check (see the hard rules). "
            + "MARKET: vegetables, fruit, fish, meat freshness signs and ripeness. "
            + "OUTSIDE: TEMPLE / CHURCH / MONUMENT / TOWN BOARD → what it is and its history; ROAD / TRAFFIC SIGN → meaning; SNAKE / INSECT / SPIDER / ANIMAL → what it is, "
            + "is it venomous / dangerous, what to do; ANIMAL HEALTH (cow, dog, goat, hen) → possible causes, first care, when to call the vet. PARKING: when he says he parked "
            + "here, add a 'parking' action. WHERE HE KEEPS A THING (keys, documents...): add an 'item_place' action with the thing and the place described from the picture. "
            + "STUDY / HOMEWORK: solve step by step and explain simply in Telugu; a textbook diagram (heart, cell, solar system, machine) → name each labelled part with a box. "
            + "COMPARE (two pictures side by side, FIRST left, SECOND right): what each is, price, features, the differences that matter, which is better for what. "
            + "TECHNICIAN CHECK (OLD part left, NEW part right): is the new one really new, the same model / rating, original-looking, any sign of a used or fake part. "
            + "THERMAL CAMERA PICTURE (colours show heat, often with a temperature scale): read the hottest spots and their temperatures, say which part is hot "
            + "and whether that is normal or a fault (an overheating motor, compressor, wire joint, MCB, charger, bearing). "
            + "ENDOSCOPE / INSPECTION CAMERA PICTURE (inside a pipe, a wall, an AC, an engine cylinder): say what is seen inside (blockage, rust, crack, carbon, "
            + "a leak, a lost thing) and what to do. ";

    private static final String SCHEMA = "Reply with ONE JSON object only (no markdown, no code fences): "
            + "{\"say\":\"what to tell him: simple natural Telugu (Telugu script), plain text for reading aloud, no markdown or symbols; start with what it is; 3-8 sentences, "
            + "or the full A to Z when he asks for details\","
            + "\"title\":\"short name of what this is\","
            + "\"kind\":\"product|gadget|medicine|plant|crop|pcb|electronics|meter|appliance|wiring|home|inside|vehicle_plate|engine|dashboard|tyre|vehicle|document|bill|"
            + "card|invitation|book|label|currency|food|animal|place|sign|homework|diagram|map|compare|other\","
            + "\"items\":[{\"n\":1,\"name\":\"...\",\"box\":[x0,y0,x1,y1],\"shape\":\"resistor|capacitor|cap_e|ic|chip|transistor|diode|led|inductor|crystal|connector|"
            + "header|switch|relay|fuse|pot|battery|module|heatsink|transformer|motor|speaker|sensor|display|screw|wire|pin|part\",\"value\":\"10kΩ / 470µF 25V / ...\","
            + "\"note\":\"one short Telugu line\",\"section\":\"which part of the device\",\"fault\":false,\"layer\":0}],"
            + "\"actions\":[{\"type\":\"shop|web|reminder|calendar|contact|note|expense|warranty|medicine|challan|maps|dial|item_place|parking|link|hologram|pdf|checkup|"
            + "open_layers|obd|sound\",\"label\":\"short Telugu button text\",\"auto\":false, ...fields}],"
            + "\"steps\":[\"...\"],\"next\":\"\",\"report\":\"\",\"warn\":\"\",\"danger\":false,\"estimate\":false,\"hologram\":false}. "
            + "BOXES: box = [x0,y0,x1,y1] as integers 0-1000 of the picture's width (x, left to right) and height (y, top to bottom), tight around each thing that is "
            + "really visible; leave box out for things not in the picture. Up to 25 items, the most useful first; items only when there are separate parts or things to point "
            + "at (parts of a board, an engine, a dashboard, a diagram, a label's important lines, a plant's sick spots, screws). "
            + "ACTION FIELDS: shop{query}; web{query}; reminder{text,at:'yyyy-MM-dd HH:mm'}; calendar{title,date:'yyyy-MM-dd',time:'HH:mm' or '',place}; "
            + "contact{name,phone,email,address,company,job}; note{text}; expense{amount,what,shop,category:food|groceries|fuel|bills|shopping|travel|health|other,"
            + "date:'yyyy-MM-dd'}; warranty{item,bought:'yyyy-MM-dd',months,until:'yyyy-MM-dd' or '',shop}; medicine{name,times:'08:00, 20:00',dose,food:before|after|''}; "
            + "challan{plate}; maps{place,navigate:true|false}; dial{number,who}; item_place{thing,place}; parking{}; link{url}; hologram{}; pdf{}; checkup{what}; "
            + "open_layers{device}; obd{}; sound{}. Offer only actions that fit (at most 5). auto=true ONLY when his words right now ask for exactly that action "
            + "(e.g. 'Amazon లో చూపించు', 'రిమైండర్ పెట్టు', 'చలాన్లు చూడు', 'కాంటాక్ట్ సేవ్ చేయి'); otherwise false (it shows as a button he taps). "
            + "Offer 'hologram' (and hologram=true) for a PCB, an engine bay, a machine, a diagram or a map with many parts. Offer 'pdf' for a PCB / circuit or a repair guide. "
            + "GUIDED CHECKS (car / bike checkup, jump start, tyre change, a repair, opening a device layer by layer): when he starts one (or the talk shows one going on), "
            + "put the whole plan in steps, judge the current step from this picture in say, and put the next thing to show in next (empty when finished); at the end put a "
            + "short dated report in report and a 'reminder' action for the next service when it fits. "
            + "warn = one safety line when there is any risk; danger=true for the never-open list or an emergency.";

    /** The system prompt for one question. */
    static String system(Prefs p) {
        return "You are Jarvis, " + p.name() + "'s assistant, looking through his phone camera. The picture is exactly what his screen shows. "
                + "Answer his question about it like an expert friend: correct, specific and practical. " + KNOW + SAFETY + " " + SCHEMA;
    }

    /** The deep scan for the hologram: every part, its job, section, links, faults, test points and building order. */
    static String deepSystem(Prefs p) {
        return "You are Jarvis, " + p.name() + "'s assistant, doing a detailed scan of the picture for a 3D hologram in which every part floats on its own. "
                + "The picture may be a PCB / electronic board, an engine bay, a machine or appliance (maybe opened), a textbook diagram, a map, or any object with parts. "
                + SAFETY + " Reply with ONE JSON object only (no markdown): "
                + "{\"title\":\"...\",\"kind\":\"pcb|engine|appliance|inside|diagram|map|object\",\"say\":\"3-6 Telugu sentences: what it is and how it works\","
                + "\"items\":[{\"n\":1,\"name\":\"R1 / 7805 regulator / బ్యాటరీ ...\",\"box\":[x0,y0,x1,y1],\"shape\":\"resistor|capacitor|cap_e|ic|chip|transistor|diode|"
                + "led|inductor|crystal|connector|header|switch|relay|fuse|pot|battery|module|heatsink|transformer|motor|speaker|sensor|display|screw|wire|pin|part\","
                + "\"value\":\"...\",\"note\":\"one short Telugu line\",\"info\":\"1-2 short Telugu sentences: what it is and its job here\",\"section\":\"...\",\"fault\":false,"
                + "\"fault_why\":\"\",\"price\":\"₹.. (web when you can, else empty)\",\"buy\":\"search words to buy it\",\"sub\":\"a part that can replace it\","
                + "\"pins\":\"pin names in order if it is an IC / transistor / regulator\",\"h\":1.0}],"
                + "\"sections\":[{\"name\":\"...\",\"color\":\"#RRGGBB\",\"does\":\"one Telugu line\"}],"
                + "\"links\":[[1,2],[2,5]],\"order\":[n,...],\"build\":[\"Telugu step for each n in order: where it goes, which way round, how to solder or fit it\"],"
                + "\"tests\":[{\"n\":3,\"where\":\"...\",\"expect\":\"5V DC\",\"box\":[x0,y0,x1,y1]}],"
                + "\"block\":[{\"name\":\"section name\",\"to\":[\"other section\"],\"signal\":\"5V / data / ...\"}],"
                + "\"bom_total\":\"₹...\",\"estimate\":true}. "
                + "BOXES: integers 0-1000 of the picture's width and height, tight around each part; every part you can see (up to 45, the bigger and more "
                + "important first), small ones too. Keep every text short so the whole JSON fits; put items first and the build steps short. "
                + "h = the part's height compared with a resistor (resistor 1, IC 1.2, electrolytic capacitor 3-6, relay 4, heatsink 5, connector 2). "
                + "links: pairs of item numbers that are connected (by traces you can see, or that must be connected for the circuit to work): an estimate. "
                + "sections: 2-6 groups (power, controller, sensors, output, ...) with distinct bright colours; each item's section must be one of them. "
                + "order: the order to fit / solder the parts (small flat ones first, tall ones last). tests: 3-8 points to check with a multimeter and what they should read. "
                + "faults: burnt marks, bulged or leaking capacitors, cracked joints, corrosion, broken parts → fault=true with fault_why. "
                + "For an engine, machine, diagram or map: the same fields, using what fits (no build, tests or pins when they do not fit; a map's places are shape 'pin').";
    }

    /** A system prompt for the PDF's words (from a deep scan already made). */
    static String pdfSystem(Prefs p) {
        return "You write a clear Telugu guide (Telugu script; part names, values and part numbers stay as written) for " + p.name() + " about a board or device that was "
                + "scanned. " + SAFETY + " Reply with ONE JSON object only: {\"about\":\"what it is and what it is used for (4-8 sentences)\","
                + "\"how\":\"how it works, section by section (6-15 sentences)\",\"build\":[\"step by step how to make one like it, from buying the parts to testing\"],"
                + "\"tests\":[\"how to test it\"],\"repair\":[\"common faults and how to fix them\"],\"safety\":[\"safety points\"],"
                + "\"tools\":[\"tools and materials needed\"]}. Say clearly that the circuit is an estimate from the photo and must be checked with a multimeter.";
    }

    /** One finding: a thing on the picture. */
    static final class Item {
        int n, layer;
        String name = "", shape = "part", value = "", note = "", section = "", info = "", faultWhy = "", price = "", buy = "", sub = "", pins = "";
        float[] box; // 0..1 of the picture: x0, y0, x1, y1 (null: not on the picture)
        boolean fault;
        float h = 1f;
    }

    /** One answer. */
    static final class Result {
        String say = "", title = "", kind = "", next = "", report = "", warn = "", raw = "";
        boolean danger, estimate, hologram;
        final List<Item> items = new ArrayList<>();
        final List<JSONObject> actions = new ArrayList<>();
        final List<String> steps = new ArrayList<>();
        JSONObject json;
    }

    /** The answer as found in the reply (a reply that is not JSON is taken as plain words to say). */
    static Result parse(String reply) {
        Result r = new Result();
        r.raw = reply == null ? "" : reply;
        JSONObject j = json(r.raw);
        if (j == null) {
            // never read JSON out loud: a reply that looks like one but could not be read is said as such
            String t = r.raw.trim();
            r.say = t.startsWith("{") || t.contains("\"say\"") || t.startsWith("```") ? "జవాబు పూర్తిగా రాలేదు. మళ్ళీ అడగండి." : clean(r.raw);
            return r;
        }
        r.json = j;
        r.say = clean(j.optString("say"));
        r.title = j.optString("title").trim();
        r.kind = j.optString("kind").trim().toLowerCase(Locale.ROOT);
        r.next = clean(j.optString("next"));
        r.report = clean(j.optString("report"));
        r.warn = clean(j.optString("warn"));
        r.danger = j.optBoolean("danger");
        r.estimate = j.optBoolean("estimate");
        r.hologram = j.optBoolean("hologram");
        JSONArray it = j.optJSONArray("items");
        for (int i = 0; it != null && i < it.length() && r.items.size() < 80; i++) {
            Item x = item(it.optJSONObject(i), r.items.size() + 1);
            if (x != null) r.items.add(x);
        }
        JSONArray ac = j.optJSONArray("actions");
        for (int i = 0; ac != null && i < ac.length() && r.actions.size() < 8; i++) {
            JSONObject a = ac.optJSONObject(i);
            if (a != null && !a.optString("type").isEmpty()) r.actions.add(a);
        }
        JSONArray st = j.optJSONArray("steps");
        for (int i = 0; st != null && i < st.length(); i++) if (!st.optString(i).trim().isEmpty()) r.steps.add(clean(st.optString(i)));
        if (r.say.isEmpty()) r.say = r.title;
        return r;
    }

    static Item item(JSONObject o, int fallbackN) {
        if (o == null) return null;
        Item x = new Item();
        x.n = o.optInt("n", fallbackN);
        x.name = o.optString("name").trim();
        if (x.name.isEmpty()) return null;
        x.shape = o.optString("shape", "part").trim().toLowerCase(Locale.ROOT);
        x.value = o.optString("value").trim();
        x.note = clean(o.optString("note"));
        x.info = clean(o.optString("info"));
        x.section = o.optString("section").trim();
        x.fault = o.optBoolean("fault");
        x.faultWhy = clean(o.optString("fault_why"));
        x.price = o.optString("price").trim();
        x.buy = o.optString("buy").trim();
        x.sub = o.optString("sub").trim();
        x.pins = o.optString("pins").trim();
        x.layer = o.optInt("layer", 0);
        x.h = (float) Math.max(0.3, Math.min(8, o.optDouble("h", defaultHeight(x.shape))));
        x.box = box(o.optJSONArray("box"));
        return x;
    }

    /** A box [x0,y0,x1,y1] in 0..1000 as 0..1 (null when missing or nonsense). */
    static float[] box(JSONArray b) {
        if (b == null || b.length() != 4) return null;
        float x0 = (float) b.optDouble(0, -1), y0 = (float) b.optDouble(1, -1), x1 = (float) b.optDouble(2, -1), y1 = (float) b.optDouble(3, -1);
        if (x0 < 0 || y0 < 0 || x1 < 0 || y1 < 0) return null;
        float a = Math.min(x0, x1), b0 = Math.min(y0, y1), c = Math.max(x0, x1), d = Math.max(y0, y1);
        if (c > 1000.5f || d > 1000.5f) return null;
        if (c - a < 2 || d - b0 < 2) return null;
        return new float[]{a / 1000f, b0 / 1000f, c / 1000f, d / 1000f};
    }

    static float defaultHeight(String shape) {
        switch (shape == null ? "" : shape) {
            case "cap_e": return 4f;
            case "relay": case "transformer": return 4f;
            case "heatsink": return 5f;
            case "connector": case "header": return 2f;
            case "ic": case "module": return 1.3f;
            case "chip": return 0.6f;
            case "led": return 1.6f;
            case "crystal": case "transistor": return 1.8f;
            case "battery": case "motor": case "speaker": return 3f;
            case "screw": return 0.8f;
            case "pin": return 2.5f;
            default: return 1f;
        }
    }

    /** The JSON object inside a reply (code fences and words around it allowed); a reply cut off at the end is mended. */
    static JSONObject json(String s) {
        if (s == null) return null;
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0) return null;
        if (b > a) {
            try { return new JSONObject(s.substring(a, b + 1)); } catch (Exception ignored) {}
        }
        return mend(s.substring(a));
    }

    /**
     * A reply that ran out of room in the middle (a long parts list): cut back to the end of the last complete object or
     * list (so a half-written part is dropped, never kept with a broken box) and close what is still open.
     */
    static JSONObject mend(String s) {
        List<Integer> cuts = new ArrayList<>();
        List<String> open = new ArrayList<>();
        StringBuilder stack = new StringBuilder();
        boolean str = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') str = false;
                continue;
            }
            if (c == '"') str = true;
            else if (c == '{' || c == '[') stack.append(c);
            else if (c == '}' || c == ']') {
                if (stack.length() == 0) break;
                stack.setLength(stack.length() - 1);
                if (stack.length() == 0) break; // complete (it would have parsed)
                cuts.add(i + 1);
                open.add(stack.toString());
            }
        }
        for (int k = cuts.size() - 1, tries = 0; k >= 0 && tries < 60; k--, tries++) {
            String st = open.get(k);
            StringBuilder close = new StringBuilder();
            for (int i = st.length() - 1; i >= 0; i--) close.append(st.charAt(i) == '{' ? '}' : ']');
            try {
                JSONObject j = new JSONObject(s.substring(0, cuts.get(k)) + close);
                j.put("_mended", true);
                return j;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[*#_`>]", "").replaceAll("[ \\t]+", " ").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** The extra lines that go with a question: time, what the phone read, his medicines and warranties, the talk so far. */
    static String context(Context c, String mode, String code, String talk, String guide) {
        StringBuilder b = new StringBuilder();
        b.append("Now: ").append(new java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new java.util.Date())).append('\n');
        if (mode != null && !mode.equals("auto")) b.append("He chose the mode: ").append(mode).append(" (").append(modeLabel(mode)).append(")\n");
        if (code != null && !code.isEmpty()) b.append("Code read on the phone from the picture (QR / barcode): ").append(code).append('\n');
        try {
            List<JSONObject> meds = Medicine.all(c);
            if (!meds.isEmpty()) {
                b.append("HIS MEDICINES (check interactions only when a medicine is shown): ");
                for (int i = 0; i < meds.size() && i < 15; i++) b.append(i == 0 ? "" : ", ").append(meds.get(i).optString("name"));
                b.append('\n');
            }
        } catch (Exception ignored) {}
        if ("repair".equals(mode) || "inside".equals(mode)) {
            try {
                JSONArray w = new JSONArray();
                for (JSONObject o : Expiry.all(c)) if ("warranty".equals(o.optString("kind"))) w.put(o.optString("what") + " until " + o.optString("date"));
                if (w.length() > 0) b.append("His saved warranties (say if this thing is still under warranty and that opening it may void it): ").append(w).append('\n');
            } catch (Exception ignored) {}
        }
        if (guide != null && !guide.isEmpty()) b.append("A guided check is going on: ").append(guide).append('\n');
        if (talk != null && !talk.isEmpty()) b.append("The talk so far about this:\n").append(talk).append('\n');
        return b.toString();
    }
}
