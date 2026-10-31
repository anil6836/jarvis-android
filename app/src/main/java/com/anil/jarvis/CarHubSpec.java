package com.anil.jarvis;

/**
 * 🎛️ "అన్ని ఎంపికలు" on the car screen (⑪, 26 Oct): every choice of the car screen in one place, in ten parts (Jarvis,
 * the map, songs, colour and theme, the meter and clock, his apps and buttons, the car and the trip, safety, sound and
 * words, more). Only data here (no Android: desk-tested); {@link CarHub} draws it and writes his choice. Every old look
 * stays: this is only one more way to reach them. Looks change only while parked; his words work any time.
 */
final class CarHubSpec {
    private CarHubSpec() {}

    static final int K_STR = 0, K_INT = 1, K_BOOL = 2, K_ACT = 3, K_INFO = 4;

    /** One choice: a setting (its key, its values and their names) or a button that does something. */
    static final class Opt {
        final String key, title;
        final int kind;
        final String[] values, names;
        final int[] ints;
        final String defStr;
        final int defInt;
        final boolean defBool, fresh;

        private Opt(String key, String title, int kind, String[] values, int[] ints, String[] names, String defStr, int defInt, boolean defBool, boolean fresh) {
            this.key = key; this.title = title; this.kind = kind; this.values = values; this.ints = ints; this.names = names;
            this.defStr = defStr; this.defInt = defInt; this.defBool = defBool; this.fresh = fresh;
        }

        int count() { return kind == K_INT ? ints.length : kind == K_BOOL ? 2 : values == null ? 0 : values.length; }
    }

    static Opt str(String key, String title, String[] values, String[] names, String def) { return new Opt(key, title, K_STR, values, null, names, def, 0, false, false); }
    static Opt strNew(String key, String title, String[] values, String[] names, String def) { return new Opt(key, title, K_STR, values, null, names, def, 0, false, true); }
    static Opt ints(String key, String title, int[] values, String[] names, int def) { return new Opt(key, title, K_INT, null, values, names, null, def, false, false); }
    static Opt intsNew(String key, String title, int[] values, String[] names, int def) { return new Opt(key, title, K_INT, null, values, names, null, def, false, true); }
    static Opt bool(String key, String title, boolean def) { return new Opt(key, title, K_BOOL, null, null, new String[]{"ఆన్", "ఆఫ్"}, null, 0, def, false); }
    static Opt boolNew(String key, String title, boolean def) { return new Opt(key, title, K_BOOL, null, null, new String[]{"ఆన్", "ఆఫ్"}, null, 0, def, true); }
    static Opt act(String id, String title) { return new Opt(id, title, K_ACT, null, null, null, null, 0, false, false); }
    static Opt info(String id, String title) { return new Opt(id, title, K_INFO, null, null, null, null, 0, false, false); }

    /** A part: its id, its sign and name, its choices. */
    static final class Cat {
        final String id, icon, name;
        final Opt[] opts;
        Cat(String id, String icon, String name, Opt... opts) { this.id = id; this.icon = icon; this.name = name; this.opts = opts; }
    }

    // the theme's light colours (the same list as ⚙️'s theme editor)
    static final int[] ACCENTS = {0, 0xFF5FF3FF, 0xFF4D8DFF, 0xFF34E89E, 0xFFFFC24B, 0xFFFF8A3D, 0xFFFF5A5A, 0xFFFF6AD5, 0xFFB28DFF, 0xFFF2F6FA};
    static final String[] ACCENT_NAMES = {"థీమ్‌దే", "సియాన్", "నీలం", "ఆకుపచ్చ", "బంగారు", "నారింజ", "ఎరుపు", "గులాబీ", "ఊదా", "తెలుపు"};
    static final String[] DANCES = {"always", "parked", "off"}, DANCE_NAMES = {"ఎప్పుడూ", "ఆగినప్పుడే", "వద్దు"};
    static final String[] OUTFITS = {"a", "b", "c"}, OUTFIT_NAMES = {"A · జాకెట్", "B · కుర్తా", "C · క్యాజువల్"};
    static final String[] SKINS = {"1", "2", "3"}, SKIN_NAMES = {"లేత", "మధ్యస్థం", "ముదురు"};
    /** 🎬 (31 Oct): the film Jarvis (his own clips) or the drawn one. */
    static final String[] JARVIS_KINDS = {"film", "2d"}, JARVIS_KIND_NAMES = {"🎬 కొత్త సినిమా Jarvis", "🧍 పాత 2D Jarvis"};

    private static int[] themeIds() {
        int[] out = new int[CarTheme.ALL.length];
        for (int i = 0; i < out.length; i++) out[i] = CarTheme.ALL[i].id;
        return out;
    }

    private static String[] themeNames() {
        String[] out = new String[CarTheme.ALL.length];
        for (int i = 0; i < out.length; i++) out[i] = CarTheme.ALL[i].name;
        return out;
    }

    private static Cat[] all;

    /** The eleven parts, in this order on the screen. */
    static Cat[] cats() {
        if (all != null) return all;
        all = new Cat[]{
                new Cat("jarvis", "🧍", "Jarvis",
                        strNew("car_jarvis_kind", "🎬 Jarvis ఎవరు", JARVIS_KINDS, JARVIS_KIND_NAMES, "film"),
                        act("film_pack", "📦 Jarvis క్లిప్స్ ప్యాక్ కలుపు (🎬 సినిమా Jarvis)"),
                        boolNew("car_film_sets", "💃 పాటలో హీరోయిన్లతో సెట్ (🎬, పాటకు ఒకసారి)", true),
                        info("film", "🎬 క్లిప్స్ ఈ box లోనే ఉంటాయి (APK, GitHub, బ్యాకప్‌లో ఉండవు). ప్యాక్ లేకపోతే పాత 2D Jarvis."),
                        str("car_jarvis", "📏 సైజు", CarDeck.SIZES, CarDeck.SIZE_NAMES, ""),
                        str("car_jarvis_look", "✨ రూపం", CarStyle.JARVIS_LOOKS, new String[]{"మామూలు", "🔷 హోలోగ్రామ్"}, ""),
                        str("body_look", "👕 బట్టలు", OUTFITS, OUTFIT_NAMES, "c"),
                        str("body_skin", "🎨 చర్మం రంగు", SKINS, SKIN_NAMES, "2"),
                        str("car_dance", "💃 పాట వస్తే డాన్స్", DANCES, DANCE_NAMES, "always"),
                        strNew("car_jarvis_ring", "💫 వెలిగే వలయాల వేదిక", CarDeck.RINGS, CarDeck.RING_NAMES, ""),
                        bool("car_welcome", "👋 స్వాగతం (కారు ఆన్ అయినప్పుడు)", true),
                        str("car_welcome_look", "🚗 స్వాగతం రూపం", CarStyle.WELCOMES, new String[]{"మామూలు", "నియాన్"}, ""),
                        boolNew("car_talk_bubble", "💬 మాటల బుడగ (⑪: Jarvis మాట్లాడినప్పుడే)", true),
                        strNew("car_jarvis_side", "↔️ Jarvis ఎటు వైపు (⑪)", CarDeck.SIDES, CarDeck.SIDE_NAMES, ""),
                        strNew("car_jarvis_glow", "🌟 Jarvis వెలుగు రంగు", CarDeck.GLOWS, CarDeck.GLOW_NAMES, ""),
                        info("voice", "🗣️ గొంతు: మీరు ఎంచుకున్నదే (ఇక్కడ మారదు)")),
                new Cat("map", "🗺️", "మ్యాప్",
                        strNew("car_map_src", "🗺️ మన మ్యాప్ బొమ్మలు", CarDeck.MAP_SRCS, CarDeck.MAP_SRC_NAMES, ""),
                        act("g2", "📱 Google Maps యాప్ పక్కన (G2)"),
                        strNew("car_map_car", "🚙 మ్యాప్ మీద కారు", CarDeck.MAP_CARS, CarDeck.MAP_CAR_NAMES, ""),
                        act("photo_top", "📷 నా కారు ఫోటో (పై నుంచి) ఎంచుకో"),
                        boolNew("car_map_rotate", "🧭 దారి పైకి (మ్యాప్ కారుతో తిరుగుతుంది)", false),
                        str("car_map_night", "🌗 మ్యాప్ రంగు", CarDeck.MAP_COLOURS, CarDeck.MAP_COLOUR_NAMES, "auto"),
                        str("car_map_zoom", "🔍 మ్యాప్ దగ్గర / దూరం", CarDeck.ZOOMS, CarDeck.ZOOM_NAMES, ""),
                        bool("car_route_glow", "✨ వెలిగే దారి, కదిలే బాణాలు", true),
                        bool("car_turn_arrows", "➡️ మలుపు బాణాలు", true),
                        bool("car_map_limit", "🚫 లిమిట్ గుర్తు", true),
                        bool("car_map_pills", "🏁 చేరే సమయం", true),
                        bool("car_nav_map", "🧭 దారిలో మ్యాప్ స్క్రీన్", true),
                        bool("car_map_tilt", "③ మ్యాప్ వెనక్కి వాలు", true)),
                new Cat("music", "🎵", "పాటలు",
                        str("car_eq_level", "🎚️ ఈక్వలైజర్", CarDeck.EQ_LEVELS, CarDeck.EQ_NAMES, "full"),
                        str("car_eq_style", "🎚️ ఈక్వలైజర్ రూపం", CarStyle.EQ_STYLES, CarStyle.EQ_STYLE_NAMES, ""),
                        bool("car_beat_lights", "💡 బీట్ లైట్లు", true),
                        bool("car_art_colour", "🎨 పాట బొమ్మ రంగు", true),
                        bool("car_vinyl", "💿 పాట బొమ్మ రికార్డులా", false),
                        bool("car_wave", "〰️ పాట కింద అలలు", false),
                        act("stage", "🕺 Jarvis స్టేజ్ (ఆన్ / ఆఫ్)"),
                        act("music_app", "🎵 మ్యూజిక్ యాప్ మార్చు")),
                new Cat("look", "🎨", "రంగు, థీమ్",
                        ints("car_theme", "🎨 థీమ్", themeIds(), themeNames(), 1),
                        ints("car_accent", "💡 వెలుగు రంగు", ACCENTS, ACCENT_NAMES, 0),
                        str("car_layout", "🧩 స్క్రీన్ ఆకారం", CarLayout.CHOICES, CarLayout.NAMES, CarLayout.CLASSIC),
                        strNew("car_glass_night", "🌙 ⑫ గ్లాస్ డాక్: రాత్రి రంగు", CarDeck.GLASS_NIGHTS, CarDeck.GLASS_NIGHT_NAMES, ""),
                        info("own_colours", "🎨 ⑫ తెల్లటి గాజు, ⑭ నియాన్ రంగులు వాటివే; ⑮ ⑯ లో తెల్ల థీమ్ బదులు నీలి గాజు: మీ థీమ్ మిగతా ఆకారాల్లో"),
                        str("car_card", "🃏 కార్డులు", CarStyle.CARDS, CarStyle.CARD_NAMES, ""),
                        str("car_icons", "✳️ ఐకాన్లు", CarStyle.ICONS, CarStyle.ICON_NAMES, ""),
                        str("car_bg_motion", "🌌 వెనక బొమ్మ", CarDeck.BG_MOTIONS, CarDeck.BG_MOTION_NAMES, ""),
                        str("car_font", "🔢 అంకెల ఫాంట్", CarStyle.FONTS, CarStyle.FONT_NAMES, ""),
                        str("car_night_dim", "🔆 రాత్రి వెలుతురు", CarDeck.NIGHT_DIMS, CarDeck.NIGHT_DIM_NAMES, "")),
                new Cat("3d", "🎮", "3D ⑮ ⑯",
                        strNew("car_3d_kind", "🚗 3D కారు", CarDeck.KINDS_3D, CarDeck.KIND_3D_NAMES, "suv"),
                        act("car3d_pick", "🔄 కారు ఎంచుకో (తిరిగే 3D బొమ్మతో)"),
                        ints("car_colour", "🎨 కారు రంగు", CarTheme.CAR_COLOURS, CarTheme.CAR_COLOUR_NAMES, CarTheme.CAR_COLOURS[0]),
                        strNew("car_3d_finish", "✨ రంగు మెరుపు", Car3D.FINISHES, Car3D.FINISH_NAMES, ""),
                        strNew("car_3d_head", "🔦 హెడ్‌లైట్", Car3D.HEADS, Car3D.HEAD_NAMES, ""),
                        strNew("car_3d_glow", "🌈 కారు కింద వెలుగు (పాట బీట్‌తో)", Car3D.GLOWS, Car3D.GLOW_NAMES, ""),
                        strNew("car_3d_sky", "🌅 ఆకాశం", CarDeck.SKIES_3D, CarDeck.SKY_3D_NAMES, ""),
                        strNew("car_3d_city", "🏙️ బిల్డింగ్‌లు", CarDeck.CITIES_3D, CarDeck.CITY_3D_NAMES, ""),
                        act("car3d_photos", "📷 నా కారు ఫోటోలు 3D కోసం (7 వైపులు)"),
                        strNew("car_plate", "🔤 ఫోటో కారు నంబర్ ప్లేట్", CarPhotos3D.PLATES, CarPhotos3D.PLATE_NAMES, ""),
                        strNew("car_15_3d", "🏙️ ⑮ మ్యాప్: 3D / 2D", CarDeck.D3_15, CarDeck.D3_15_NAMES, ""),
                        intsNew("car_15_eye", "👁️ ⑮ కెమెరా ఎత్తు", CarDeck.EYES_15, CarDeck.EYE_15_NAMES, 1),
                        strNew("car_15_look", "🌙 ⑮ మ్యాప్ రంగులు", CarDeck.LOOKS_15, CarDeck.LOOK_15_NAMES, ""),
                        strNew("car_15_north", "🧭 ⑮ మ్యాప్ తిరగడం", CarDeck.NORTH_15, CarDeck.NORTH_15_NAMES, ""),
                        strNew("car_15_game", "🎮 ⑮ లో ఏది", CarDeck.GAME_15, CarDeck.GAME_15_NAMES, ""),
                        info("osm3d", "🗺️ 3D రోడ్లు, బిల్డింగ్‌లు OpenStreetMap నుంచి (⑮: OpenFreeMap): కొన్ని చోట్ల బిల్డింగ్‌లు ఉండవు, ఎత్తులు తెలియనివి మావి. Google 3D వాడము."),
                        act("map3d_clear", "🗑️ 3D మ్యాప్ ముక్కలు తీసేయి (⑮ ⑯, బాక్స్‌లో దాచినవి)")),
                new Cat("meter", "🧭", "మీటర్, గడియారం",
                        str("car_gauge", "🧭 స్పీడ్ మీటర్", CarStyle.GAUGES, CarStyle.GAUGE_NAMES, ""),
                        str("car_clock", "🕐 గడియారం", CarDeck.CLOCKS, CarDeck.CLOCK_NAMES, ""),
                        str("car_cluster_dial", "🔵 ⑨ పెద్ద డయల్", CarDeck.DIALS, CarDeck.DIAL_NAMES, "compass")),
                new Cat("apps", "⭐", "నా యాప్స్, బటన్లు",
                        act("apps", "⭐ ఇష్టమైన యాప్స్ (ఎక్కువసేపు నొక్కితే చేరుతుంది / పోతుంది)"),
                        act("buttons", "🎛️ ఆరు బటన్లు మార్చు"),
                        str("car_btn_style", "🔘 బటన్ల రూపం", CarDeck.STYLES, CarDeck.STYLE_NAMES, ""),
                        str("car_text", "🔤 అక్షరాల సైజు", CarDeck.SIZES, CarDeck.SIZE_NAMES, "")),
                new Cat("car", "🚗", "కారు, ప్రయాణం",
                        ints("car_colour", "🚙 కారు రంగు", CarTheme.CAR_COLOURS, CarTheme.CAR_COLOUR_NAMES, CarTheme.CAR_COLOURS[0]),
                        bool("car_platform", "💫 కారు వెలిగే వేదిక మీద", false),
                        act("photos", "📷 నా కారు ఫోటోలు (⚙️)"),
                        bool("car_low_motion", "🐢 కదలికలు తక్కువ", false)),
                new Cat("safe", "🛡️", "భద్రత",
                        act("fatigue", "😴 నిద్ర అలర్ట్ (ఆన్ / ఆఫ్)"),
                        act("alarm_test", "🔔 అలారం శబ్దం టెస్ట్"),
                        act("eyes", "👁️ కళ్ళ చెక్ కెమెరా చూడు"),
                        info("danger", "⚠️ ప్రమాద హెచ్చరికలు (నిద్ర, స్పీడ్, ఇంజిన్ వేడి, దారిలో వాన) ఎప్పుడూ వినిపిస్తాయి")),
                new Cat("sound", "🔊", "శబ్దం, మాట",
                        act("quiet", "🔇 నిశ్శబ్దం (ఈ ప్రయాణం: పిలిస్తేనే మాట్లాడతాడు)"),
                        act("vol_down", "🔉 పాట శబ్దం తగ్గించు"),
                        act("vol_up", "🔊 పాట శబ్దం పెంచు"),
                        info("wake", "🎙️ కారులో 'Jarvis' పిలుపు ఆఫ్: స్టీరింగ్ బటన్ లేదా 🎙️ నొక్కండి")),
                new Cat("more", "⚙️", "ఇంకా",
                        bool("car_saver", "🌙 ఆగినప్పుడు చీకటి గడియారం", true),
                        bool("car_bubble", "🫧 వేరే యాప్స్ మీద చిన్న Jarvis", true),
                        act("mine", "💾 నా థీమ్ 1–3 (దాచు / పెట్టు)"),
                        act("settings", "⚙️ పూర్తి సెట్టింగ్స్"))};
        return all;
    }

    static Cat cat(String id) {
        for (Cat c : cats()) if (c.id.equals(id)) return c;
        return cats()[0];
    }

    /** Which part a card of the screen opens when held (parked): Jarvis, the map, the player, the meter, the apps, the road. */
    static String partOf(String card) {
        switch (card == null ? "" : card) {
            case "jarvis": return "jarvis";
            case "map": return "map";
            case "player": return "music";
            case "gauge": case "clock": case "info": return "meter";
            case "apps": case "buttons": return "apps";
            case "road": return "car";
            default: return "jarvis";
        }
    }

    /** The place of the value now among the choice's values (-1: none of them). */
    static int selected(Opt o, Object now) {
        switch (o.kind) {
            case K_STR: {
                String v = now instanceof String ? (String) now : o.defStr;
                for (int i = 0; i < o.values.length; i++) if (o.values[i].equals(v)) return i;
                for (int i = 0; i < o.values.length; i++) if (o.values[i].equals(o.defStr)) return i; // (an unknown one: the default's)
                return -1;
            }
            case K_INT: {
                int v = now instanceof Integer ? (Integer) now : o.defInt;
                for (int i = 0; i < o.ints.length; i++) if (o.ints[i] == v) return i;
                return -1;
            }
            case K_BOOL: {
                boolean v = now instanceof Boolean ? (Boolean) now : o.defBool;
                return v ? 0 : 1;
            }
            default: return -1;
        }
    }

    /** The keys a choice here may write: the car screen's looks only (no keys, no places, nothing private). */
    static boolean writable(String key) {
        if (key == null) return false;
        if (key.equals("body_look") || key.equals("body_skin")) return true;
        return key.startsWith("car_") && !key.startsWith("car_cam") && !key.equals("car_mode") && !key.startsWith("car_mine") && !key.equals("car_quiet_until");
    }
}
