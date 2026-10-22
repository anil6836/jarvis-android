package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⚡ A (Round 2, build 2): the brain gets a smaller packet. Every question carries Jarvis's general and safety rules and
 * the everyday phone jobs ("core"); the other jobs come in groups (bike, duty, money…) only when the question needs
 * them: by its words, the talk just before, or the mode he is in (car). Anything missed, the AI fetches itself with
 * more_tools (one more round, never a "can't" first). The rules that are only about a group's jobs travel with that
 * group, after the rules that are always the same (so ⚡ 5's remembered part stays the same).
 */
final class SpeedGroups {
    private SpeedGroups() {}

    /** Group → its phone jobs. A tool in no group is core (sent every time); a new tool is core until put in one. */
    static final Map<String, String[]> TOOLS = new LinkedHashMap<>();
    /** Group → what it holds, for the AI (more_tools). */
    static final Map<String, String> ABOUT = new LinkedHashMap<>();
    /** Group → words in his question (Telugu, English) that bring it. */
    static final Map<String, String[]> WORDS = new LinkedHashMap<>();

    private static void group(String id, String about, String[] tools, String[] words) {
        TOOLS.put(id, tools);
        ABOUT.put(id, about);
        WORDS.put(id, words);
    }

    static {
        group("bike", "his electric bike: range, charging, rides, challans, ride plans, EV chargers, parking",
                new String[]{"bike_range", "bike_charge", "bike_rides", "bike_challan", "ride_plan", "ev_chargers", "parking"},
                new String[]{"బైక్", "బండి", "బండ", "ఛార్జ్", "చార్జ్", "ఛార్జింగ్", "రేంజ్", "కి.మీ", "కిలోమీటర్", "చలాన్", "పార్క్", "రైడ్", "స్కూటర్",
                        "ఏరా", "పెట్రోల్", "స్టేషన్", "బ్యాటరీ", "ట్రిప్", "bike", "battery", "trip", "charge", "charging", "range", "km", "matter", "aera", "challan", "park", "parking",
                        "ride", "rides", "ev", "scooter", "statiq"});
        group("duty", "his shift duty: roster, duty mode, countdown, report, duty bag, night chime, trip check, handover notes, protocols",
                new String[]{"duty", "handover", "protocol"},
                new String[]{"డ్యూటీ", "డ్యూటి", "షిఫ్ట్", "హ్యాండోవర్", "ప్రోటోకాల్", "యూనిఫాం", "రోస్టర్", "ఎక్స్‌ట్రా", "duty", "shift", "handover",
                        "protocol", "roster", "uniform"});
        group("drive", "driving and car mode: where am I, roads, routes, tolls, speed cameras, stops on the way, ETA, crash check, "
                        + "journey guard, car sleep alert, travel speed and distance",
                new String[]{"drive", "driving_mode", "journey_guard", "travel", "car_care", "hazard"},
                new String[]{"డ్రైవ్", "డ్రైవింగ్", "కారు", "కార్", "రోడ్", "దారి", "టోల్", "స్పీడ్", "వేగంగా", "ప్రయాణ", "చేరగానే", "చేరుకున్న", "బయలుదేర",
                        "దూరం", "లొకేషన్", "ఎక్కడ ఉన్నాను", "ఎక్కడున్నాను", "అడ్రస్", "నాతో ఉండు", "చూసుకో", "లేట్ అవుతాను", "ఆలస్యం", "నావిగేషన్",
                        "drive", "driving", "car", "road", "route", "toll", "speed", "journey", "location", "address", "eta", "navigate",
                        "navigation", "place", "places"});
        group("travel", "buses, trains and PNR, flights, hotels, trips, Uber / Ola / Rapido, food and grocery apps, nearby open shops, ATMs, hospitals",
                new String[]{"travel_search", "train_status", "my_trips", "ride_app", "food_app", "nearby_open"},
                new String[]{"బస్", "బస్సు", "ట్రైన్", "రైలు", "టికెట్", "ఫ్లైట్", "విమాన", "హోటల్", "ట్రిప్", "క్యాబ్", "ఆటో", "ఫుడ్", "ఆర్డర్", "ఆర్టీసీ",
                        "దగ్గర్లో", "మెడికల్ షాప్", "ఆసుపత్రి", "హాస్పిటల్", "బంక్", "కిరాణా", "bus", "train", "pnr", "ticket", "flight", "hotel", "trip",
                        "uber", "ola", "rapido", "cab", "auto", "swiggy", "zomato", "blinkit", "zepto", "food", "order", "rtc", "nearby", "atm",
                        "hospital"});
        group("money", "money: spending, bank balance, budget and savings, debts / EMI / chits, expenses, the house's milk account, the car's diesel / mileage / service account, "
                        + "bill split, bills due, gold and market prices, "
                        + "price alerts and comparison, mobile data plan, expiry and warranty dates",
                new String[]{"bank_spending", "bank_balance", "budget", "debts", "add_expense", "split_bill", "bills_due", "market_prices", "price_alert",
                        "compare_prices", "mobile_plan", "expiry", "house_accounts", "car_account"},
                new String[]{"ఖర్చు", "డబ్బు", "డబ్బులు", "బ్యాలెన్స్", "బ్యాంక్", "అప్పు", "లోన్", "ఈఎంఐ", "చిట్టీ", "చిట్", "జీతం", "బిల్", "రూపాయ", "₹",
                        "ధర", "రేటు", "బంగారం", "వెండి", "మిర్చి", "పత్తి", "వరి", "చౌక", "ఇన్సూరెన్స్", "గడువు", "వారంటీ", "లైసెన్స్", "సర్వీస్", "గ్యాస్",
                        "రీఛార్జ్", "డేటా", "అద్దె", "పొదుపు", "దాచాలి", "బడ్జెట్", "కట్టాను", "కట్టాలి", "ఇచ్చాను", "తీసుకున్నా", "రాసుకో", "సిలిండర్", "money", "cylinder", "spent",
                        "పాలు", "పాల", "పాలతను", "పాలవాడు", "లీటర్", "ప్యాకెట్", "milk",
                        "డీజిల్", "డీజల్", "మైలేజ్", "మైలేజీ", "మీటర్ రీడింగ్", "ఓడోమీటర్", "ట్యాంక్", "కొట్టించ", "బంక్", "కారు లెక్క", "కారు సర్వీస్", "diesel", "mileage", "odometer",
                        "expense", "balance", "bank", "loan", "emi", "chit", "salary", "bill", "bills", "rupee", "rupees", "price", "prices", "rate",
                        "gold", "silver", "cheap", "amazon", "flipkart", "insurance", "expiry", "warranty", "licence", "license", "service", "gas",
                        "recharge", "data", "rent", "savings", "budget", "phonepe", "gpay", "paytm"});
        group("health", "health: medicine reminders and stock, BP / sugar / weight log and medical reports, watch health (sleep, steps, energy, "
                        + "stress, heart), cough log, water reminder, exercise, habits",
                new String[]{"medicine", "health_log", "health_watch", "cough_log", "steps_today", "water_reminder", "exercise", "habit_track"},
                new String[]{"మందు", "మాత్ర", "టాబ్లెట్", "సిరప్", "బీపీ", "షుగర్", "బరువు", "జ్వరం", "దగ్గు", "జలుబు", "తుమ్ము", "నొప్పి", "కడుపు",
                        "ఆరోగ్య", "డాక్టర్", "ఆసుపత్రి", "నిద్ర", "అడుగులు", "నడ", "వ్యాయామ", "యోగా", "నీళ్ళు", "నీళ్లు", "ఎనర్జీ", "స్ట్రెస్", "ఒత్తిడి",
                        "గుండె", "ఆక్సిజన్", "ఫిట్‌నెస్", "అలవాటు", "కొవ్వు", "ఆహారం", "కేలరీ", "మంట", "వాంతి", "విరేచ", "విరోచ", "మెడిసిన్", "ఒంట్లో",
                        "ఒళ్ళు", "ఒళ్లు", "నొప్ప", "జబ్బు", "బాగాలేదు", "బాలేదు", "నీరసం", "రిపోర్ట్", "medicine", "report", "tablet", "pill",
                        "syrup", "bp", "sugar", "weight", "fever", "cough", "cold", "pain", "health", "doctor", "sleep", "steps", "walk", "exercise",
                        "yoga", "water", "energy", "stress", "heart", "oxygen", "fitness", "habit", "calorie"});
        group("home", "home: smart lights / fans / plugs / AC / TV, the home tablet and Amma (messages, looking in), home sounds (cooker whistles, "
                        + "door bell), automations by place and time, his routines",
                new String[]{"smart_home", "home_care", "home_link", "home_sounds", "automation", "routine"},
                new String[]{"లైట్", "ఫ్యాన్", "ఏసీ", "ప్లగ్", "బల్బ్", "టీవీ", "ఇంట్లో", "ఇంటికి", "ఇల్లు", "అమ్మ", "టాబ్లెట్", "కుక్కర్", "విజిల్", "బెల్",
                        "డోర్", "తలుపు", "ఆటోమేషన్", "రొటీన్", "మోడ్", "అలెక్సా", "కాపలా", "light", "lights", "fan", "ac", "plug", "bulb", "tv",
                        "home", "house", "tablet", "cooker", "whistle", "bell", "door", "automation", "routine", "mode", "alexa", "guard"});
        group("audio", "Bible and faith (verses, plan, prayer list, memorizing, church), radio and sleep sounds, prayer timer, naps, song alarms, "
                        + "songs saved on the phone, voice recorder (sermons, meetings), stories",
                new String[]{"bible", "sounds", "song_alarm", "local_media", "voice_recorder", "story"},
                new String[]{"బైబిల్", "వచనం", "ప్రార్థన", "ధ్యానం", "చర్చి", "యేసు", "దేవుడ", "కీర్తన", "రేడియో", "శబ్దం", "కునుకు", "లేపు", "ఫేవరేట్",
                        "స్టేషన్", "రికార్డ్", "ప్రసంగం", "మీటింగ్", "క్లాస్", "కథ", "నా ఫోన్‌లో", "నా ఫోన్లో", "ఫోన్‌లో ఉన్న", "bible", "verse", "prayer",
                        "church", "jesus", "god", "radio", "sound", "sounds", "nap", "station", "favourite", "favorite", "record", "recording",
                        "sermon", "meeting", "class", "story", "poweramp", "vlc", "mp3"});
        group("life", "everyday life: diary, festivals and holidays, birthdays, shopping list, where he kept things and what he lent, weekly / monthly "
                        + "report, wish cards, letters, cooking steps, English / Hindi practice, interpreter, daily fact and tips",
                new String[]{"diary", "holidays", "birthdays", "shopping_list", "item_place", "weekly_report", "wish_card", "make_letter", "cook",
                        "english_practice", "interpreter", "daily_fact"},
                new String[]{"డైరీ", "పండుగ", "సెలవు", "పుట్టినరోజు", "పుట్టిన రోజు", "పెళ్లిరోజు", "పెళ్లి రోజు", "లిస్ట్", "షాపింగ్", "కొన్నాను", "తాళాలు", "ఎక్కడ", "పెట్టాను",
                        "ఇచ్చాను", "తిరిగి ఇచ్చ", "రిపోర్ట్", "వచ్చే వారం", "కార్డ్", "లెటర్", "ఉత్తరం", "ఫిర్యాదు", "వండ", "వంట", "హిందీ", "ఇంగ్లీష్",
                        "అనువాద", "భాషలో", "చిట్కా", "కొత్త విషయం", "పదం", "ట్రిప్", "diary", "trip", "festival", "holiday", "birthday", "anniversary", "list",
                        "shopping", "keys", "report", "card", "letter", "complaint", "cook", "recipe", "hindi", "english", "translate",
                        "translation", "tip", "fact", "word"});
        group("info", "cricket updates, new movies and OTT, air quality, local news, WhatsApp group summary, parcels and deliveries",
                new String[]{"cricket_watch", "new_movies", "air_quality", "local_news", "group_summary", "parcels"},
                new String[]{"క్రికెట్", "స్కోర్", "మ్యాచ్", "సినిమా", "కాలుష్య", "గాలి", "గ్రూప్", "పార్సిల్", "డెలివరీ", "వార్తలు", "cricket",
                        "score", "match", "movie", "movies", "ott", "pollution", "air", "aqi", "group", "parcel", "delivery", "news"});
        group("docs", "his documents and books (ask, read aloud), scanning papers, visiting cards to contacts, shopping and searching inside apps, "
                        + "notes apps, screen time and app limits",
                new String[]{"ask_document", "scan_document", "save_contact", "app_search", "note_in_app", "screen_time", "app_limit"},
                new String[]{"డాక్యుమెంట్", "పేపర్", "పాలసీ", "సర్టిఫికెట్", "పుస్తకం", "చదివి వినిపించు", "కార్డ్", "కాంటాక్ట్", "షాపింగ్",
                        "నోట్", "స్క్రీన్ టైమ్", "వాడకం", "లిమిట్", "కళ్ల", "ఇన్సూరెన్స్", "ఫైల్", "document", "paper", "policy", "certificate",
                        "pdf", "book", "contact", "notes", "screen time", "usage", "limit", "amazon", "flipkart", "file"});
        group("camera", "Jarvis camera and what it sees: live camera, case board, map board, hazards ahead, forecast, review, tutorial",
                new String[]{"jarvis_camera", "case_board", "map_board", "forecast", "review", "tutorial"},
                new String[]{"కెమెరా", "ఫోటో తీ", "స్కాన్", "జూమ్", "లెన్స్", "కేసు", "మ్యాప్", "ప్రమాదం", "ఫోర్‌కాస్ట్", "రివ్యూ", "ట్యుటోరియల్",
                        "camera", "scan", "zoom", "lens", "case", "map", "hazard", "forecast", "review", "tutorial"});
        group("watch", "his Galaxy Watch: find it, silent, wake with the watch, watch tools",
                new String[]{"watch_tools"},
                new String[]{"వాచ్", "watch"});
        group("coding", "making websites and apps, writing code, API usage",
                new String[]{"make_website", "publish_website", "write_code", "make_app", "api_usage"},
                new String[]{"కోడ్", "వెబ్‌సైట్", "వెబ్ సైట్", "యాప్ తయారు", "ప్రోగ్రామ్", "code", "website", "app", "program", "html", "github", "api"});
    }

    /** Tool → its group (null: core). */
    private static final Map<String, String> GROUP_OF = new HashMap<>();
    static {
        for (Map.Entry<String, String[]> e : TOOLS.entrySet()) for (String t : e.getValue()) GROUP_OF.put(t, e.getKey());
    }

    static String groupOf(String tool) { return GROUP_OF.get(tool); }

    /** A question longer than this (a plan with many parts, a pasted text) gets every group. */
    static final int LONG_QUESTION = 220;

    /**
     * The groups for this question, or null for all of them (a long or "think hard" question). Recent: his last words
     * and Jarvis's last answer (a follow-up keeps its group). Mode: in the car / driving; a picture or a file.
     */
    static Set<String> pick(String text, List<String> recent, boolean picture, boolean file, boolean car, boolean deep) {
        String t = text == null ? "" : text;
        if (deep || t.length() > LONG_QUESTION) return null;
        Set<String> g = new LinkedHashSet<>(matches(t));
        if (recent != null) for (String r : recent) g.addAll(matches(r));
        if (car) { g.add("drive"); g.add("bike"); }
        if (picture) { g.add("money"); g.add("docs"); g.add("camera"); }
        if (file) g.add("docs");
        return g;
    }

    /** The groups whose words are in this text. */
    static Set<String> matches(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return out;
        String low = text.toLowerCase(Locale.ROOT);
        String te = squeeze(low);
        for (Map.Entry<String, String[]> e : WORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (has(low, te, w)) { out.add(e.getKey()); break; }
            }
        }
        return out;
    }

    /** Telugu typed in different ways: the invisible joiners don't count. */
    private static String squeeze(String s) { return s.replace("\u200c", "").replace("\u200d", ""); }

    private static final Map<String, Pattern> ASCII = new HashMap<>();

    private static boolean has(String low, String te, String w) {
        boolean ascii = true;
        for (int i = 0; i < w.length(); i++) if (w.charAt(i) > 127) { ascii = false; break; }
        if (!ascii) { // a word ending in ్ also matches its spoken -ు / -ి form ("లైట్" = "లైటు", "బైక్" = "బైకు", "ఛార్జ్" = "ఛార్జి")
            String k = squeeze(w);
            if (!k.endsWith("\u0c4d")) return te.contains(k);
            String stem = k.substring(0, k.length() - 1); // (only those endings: "కార్" is "కారు", not "కారణం")
            return te.contains(k) || te.contains(stem + "\u0c41") || te.contains(stem + "\u0c3f") || te.contains(stem + "\u0c42") || te.contains(stem + "\u0c40");
        }
        Pattern p;
        synchronized (ASCII) {
            p = ASCII.get(w);
            if (p == null) { p = Pattern.compile("(?<![a-z])" + Pattern.quote(w) + "(?![a-z])"); ASCII.put(w, p); }
        }
        return p.matcher(low).find();
    }

    /** The phone jobs this question may use: every core job plus the jobs of these groups (null groups: all). */
    static Set<String> allowed(List<String> allTools, Set<String> groups) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : allTools) {
            String g = GROUP_OF.get(t);
            if (g == null || groups == null || groups.contains(g)) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- the rule book, split

    private static Pattern toolWords;

    private static synchronized Pattern toolWords(List<String> allTools) {
        if (toolWords == null) {
            List<String> names = new ArrayList<>(allTools);
            Collections.sort(names, (a, b) -> b.length() - a.length()); // (longest first: "bike_rides" before "bike")
            StringBuilder b = new StringBuilder("(?<![A-Za-z_])(");
            for (int i = 0; i < names.size(); i++) b.append(i == 0 ? "" : "|").append(Pattern.quote(names.get(i)));
            toolWords = Pattern.compile(b.append(")(?![A-Za-z_])").toString());
        }
        return toolWords;
    }

    /** One rule split into sentences: a new sentence starts after ". " / ") " when a quote, a capital or "(" follows. */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.)!?])(?<!e\\.g\\.)(?<!i\\.e\\.) (?=['\"A-Z(])");

    /** A rule about safety, privacy or his consent: it always stays, whatever the question (even if it names a group's job). */
    static final Pattern SAFETY = Pattern.compile("(?i)(\\bnever\\b|his yes|only after|ask (him )?first|account numbers?|\\bdoses?\\b|im_ok"
            + "|journey_guard ok|prescription|\\b108\\b|\\b112\\b|emergency|\\bsos\\b|\\botp\\b|\\bpin\\b|password|consent|countdown"
            + "|pays himself|after he says|first ask)");

    /**
     * The rule book for this question: { the rules that are always the same (general, safety, everyday jobs), the rules
     * about this question's groups (put after everything that stays the same) }. A sentence goes with a group only when
     * every phone job it names is in groups; a sentence naming no job (a safety rule) or an everyday job always stays.
     * Only the "Rules:" part is looked at; who Jarvis is and how he thinks / feels are never touched.
     */
    static String[] split(String head, List<String> allTools, Set<String> allowed) {
        int at = head.indexOf("\nRules:\n");
        if (at < 0) return new String[]{head, ""};
        at += "\nRules:\n".length();
        StringBuilder core = new StringBuilder(head.substring(0, at));
        StringBuilder extra = new StringBuilder();
        Pattern tw = toolWords(allTools);
        String[] lines = head.substring(at).split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String nl = li < lines.length - 1 ? "\n" : "";
            if (!line.startsWith("- ")) { core.append(line).append(nl); continue; }
            StringBuilder keep = new StringBuilder(), grp = new StringBuilder();
            int prev = 0; // where the sentence before went: 0 always, 1 with its group, 2 left out
            for (String s : SENTENCE.split(line.substring(2))) {
                int k = kind(s, tw); // 0 always, 1 group, -1 names no job
                int place = k == 0 ? 0 : k == 1 ? (inAllowed(s, tw, allowed) ? 1 : 2) : prev; // ("Say only the step." goes with cook)
                if (place == 0) keep.append(keep.length() == 0 ? "" : " ").append(s);
                else if (place == 1) grp.append(grp.length() == 0 ? "" : " ").append(s);
                prev = place;
            }
            if (keep.length() > 0) core.append("- ").append(keep).append(nl);
            if (grp.length() > 0) extra.append("- ").append(grp).append('\n');
        }
        return new String[]{core.toString(), extra.toString()};
    }

    /** 0: always kept (an everyday job, or safety); 1: only with its group; -1: names no job (goes where the one before went). */
    private static int kind(String sentence, Pattern tw) {
        if (SAFETY.matcher(sentence).find()) return 0;
        Matcher m = tw.matcher(sentence);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (GROUP_OF.get(m.group(1)) == null) return 0;
        }
        return any ? 1 : -1;
    }

    private static boolean inAllowed(String sentence, Pattern tw, Set<String> allowed) {
        Matcher m = tw.matcher(sentence);
        while (m.find()) if (allowed.contains(m.group(1))) return true;
        return false;
    }

    /** The how-to sentences of these groups (sent back by more_tools, so the rule book itself stays the same). */
    static String howTo(String head, List<String> allTools, Set<String> groups) {
        Set<String> only = new LinkedHashSet<>();
        for (String g : groups) { String[] ts = TOOLS.get(g); if (ts != null) Collections.addAll(only, ts); }
        return split(head, allTools, only)[1];
    }

    /** The one rule about the groups (the same words every time). */
    static final String RULE = "- Some phone jobs are sent only when a question needs them, in groups (see more_tools). If he asks for a job whose tool "
            + "you don't have, call more_tools with that group first, then do it; never tell him you can't do a phone job before that.\n";

    static String moreToolsAbout() {
        StringBuilder b = new StringBuilder("Load more phone jobs for this question when the tool you need isn't in your list. Groups: ");
        int i = 0;
        for (Map.Entry<String, String> e : ABOUT.entrySet()) b.append(i++ == 0 ? "" : "; ").append(e.getKey()).append(" = ").append(e.getValue());
        return b.append(". Then use the new tools (their how-to comes back with them).").toString();
    }

    /** more_tools: the groups asked for are added (unknown names ignored); the reply lists the new jobs and their how-to. */
    static String load(JSONObject args, Set<String> groups, String head, List<String> allTools) {
        try {
            Set<String> asked = new LinkedHashSet<>();
            String raw = args == null ? "" : args.optString("groups", args.optString("group", ""));
            for (String g : raw.toLowerCase(Locale.ROOT).split("[^a-z_]+")) if (TOOLS.containsKey(g)) asked.add(g); // (also ["bike"])
            if (asked.isEmpty()) return new JSONObject().put("ok", false).put("error", "no such group").put("groups", new JSONArray(ABOUT.keySet())).toString();
            groups.addAll(asked);
            JSONArray tools = new JSONArray();
            for (String g : asked) for (String t : TOOLS.get(g)) if (allTools.contains(t) && !"home_care".equals(t)) tools.put(t); // (home_care: the tablet only)
            return new JSONObject().put("ok", true).put("added", new JSONArray(asked)).put("now_available", tools)
                    .put("how_to", howTo(head, allTools, asked)).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
