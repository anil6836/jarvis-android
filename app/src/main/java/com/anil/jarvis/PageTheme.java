package com.anil.jarvis;

/**
 * The page themes (Settings, All Features, Duty, photos): the old three (mix, blue, gold: the pages exactly as before) and
 * ten new ones (7 Nov, Anil: "B" — six from his pictures, four from the car box's looks). Each is a set of colours and a
 * card style; PageSkin paints a page with it. Pure Java (no Android here), so the desk tests can check that every text
 * colour stays readable on every theme's cards and background.
 * <p>
 * Rules: the Jarvis orb is never in a page theme (it keeps its own colours everywhere); a theme changes only on his tap;
 * each device keeps its own (the phone's theme never goes to the tablet or the car box).
 */
final class PageTheme {
    /** Card styles. */
    static final int GLASS = 0, NEU = 1, GLOW_EDGE = 2, CUT = 3, TILE = 4, OLD = 5;
    /** Background pictures (all drawn by Jarvis itself, standing still in the pages). */
    static final int BG_AURORA = 0, BG_SOFT = 1, BG_LEAVES = 2, BG_CLOUDS = 3, BG_DARK = 4, BG_NEON = 5, BG_LAMPS = 6,
            BG_WIRE = 7, BG_BLOBS = 8, BG_NAVY = 9, BG_TILES = 10;

    final String id, emoji, name, note;
    /** 0: the old three, 1: from his pictures, 2: from the car box. */
    final int from;
    /** The page background is light (dark letters on it). */
    boolean lightPage;
    /** The cards are light (dark letters in them). */
    boolean lightCard;
    int bgTop, bgBottom, bg = BG_AURORA, style = OLD;
    /** Card fill (gradient top-left → bottom-right), its edge (0: none), a second edge colour (glow edge), corner. */
    int card, card2, edge, edge2;
    float radius = 20;
    /** Neumorphic shadows: dark side and light side (0: none); TILE: the coloured glows under the cards. */
    int shadowDark, shadowLight;
    int[] glows = {};
    /** Letters: on the cards, on the page; the softer line; hints. */
    int ink, inkPage, muted, mutedPage, faint;
    /** The theme's colour (icons, links, chosen), its second colour, and the title's gradient. */
    int accent, accent2, title1, title2;
    /** Icon circle behind an emoji. */
    int iconBg;
    /** A typing box inside a card. */
    int field, fieldEdge;
    /** Switches: track when on, track when off, the knob. */
    int swOn, swOff, thumb, thumbOff;
    /** The small ⚠️ badge on a group, and its letters. */
    int badge, badgeInk;
    /** All well / look at this / a problem (letters on the cards). */
    int good, warn, bad;
    /** A row he searched for: its glow. */
    int glow;

    private PageTheme(String id, String emoji, String name, String note, int from) {
        this.id = id; this.emoji = emoji; this.name = name; this.note = note; this.from = from;
    }

    boolean isNew() { return from != 0; }

    /** "☁️ మెత్తని తెలుపు" */
    String label() { return emoji + " " + name; }

    // ================================================================ the thirteen

    static final PageTheme[] ALL;
    static {
        java.util.List<PageTheme> l = new java.util.ArrayList<>();
        // ---- the old three: the pages exactly as before (their own colours, the moving rings)
        l.add(old("mix", "🔷", "నీలం + బంగారం", "నీలం వలయాలు, బంగారు గుర్తులు", 0xFF061428, 0xFF02050C, 0xFF22D3EE, 0xFFF2B24C, 0xFF74E4FF));
        l.add(old("blue", "🧊", "ఐస్ బ్లూ", "చల్లని నీలం వెలుగు", 0xFF0A1030, 0xFF04060F, 0xFF22D3EE, 0xFF8B5CF6, 0xFF74E4FF));
        l.add(old("gold", "🟡", "బంగారు అంబర్", "వెచ్చని బంగారు వెలుగు", 0xFF1A1206, 0xFF060402, 0xFFFBBF24, 0xFFF97316, 0xFFFFD27A));

        // ---- six from his pictures
        PageTheme t = new PageTheme("soft", "☁️", "మెత్తని తెలుపు", "Soft UI: బటన్లు, కార్డులు ఉబ్బి ఉంటాయి", 1);
        t.lightPage = t.lightCard = true; t.bg = BG_SOFT; t.style = NEU; t.bgTop = 0xFFEDF0F5; t.bgBottom = 0xFFDFE4EC;
        t.card = 0xFFE9EDF3; t.card2 = 0xFFE0E5EC; t.edge = 0x99FFFFFF; t.radius = 18; t.shadowDark = 0xFFBAC1CC; t.shadowLight = 0xFFFFFFFF;
        t.ink = 0xFF2A3343; t.inkPage = 0xFF2A3343; t.muted = 0xFF667084; t.mutedPage = 0xFF667084; t.faint = 0xFF8A93A4;
        t.accent = 0xFF1F7AE8; t.accent2 = 0xFF22C3D9; t.title1 = 0xFF2A3343; t.title2 = 0xFF1F7AE8; t.iconBg = 0xFFDDE3EB;
        t.field = 0xFFDFE4EB; t.fieldEdge = 0x66FFFFFF; t.swOn = 0xFF2A8CFF; t.swOff = 0xFFC9D0DA; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFF7F8FA;
        t.badge = 0xFFFF9F43; t.badgeInk = 0xFF2A1A05; t.good = 0xFF13834F; t.warn = 0xFFB25E00; t.bad = 0xFFC62828; t.glow = 0x552A8CFF;
        l.add(t);

        t = new PageTheme("mint", "🌿", "పుదీనా", "పచ్చటి ఆకులు, నీటి చుక్కలు · Soft UI", 1);
        t.lightPage = t.lightCard = true; t.bg = BG_LEAVES; t.style = NEU; t.bgTop = 0xFFE8F6EF; t.bgBottom = 0xFFDCEFE5;
        t.card = 0xF0EAF7F0; t.card2 = 0xF0E0F1E8; t.edge = 0x99FFFFFF; t.radius = 18; t.shadowDark = 0xFFB4D3C4; t.shadowLight = 0xFFFBFFFD;
        t.ink = 0xFF163F31; t.inkPage = 0xFF163F31; t.muted = 0xFF4D7464; t.mutedPage = 0xFF4D7464; t.faint = 0xFF6F9686;
        t.accent = 0xFF1F8763; t.accent2 = 0xFF3FBF94; t.title1 = 0xFF163F31; t.title2 = 0xFF1F8763; t.iconBg = 0xFFD6ECE1;
        t.field = 0xFFDCEEE5; t.fieldEdge = 0x66FFFFFF; t.swOn = 0xFF3FBF94; t.swOff = 0xFFC2DDD0; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFF7FBF9;
        t.badge = 0xFFF0A24A; t.badgeInk = 0xFF2A1805; t.good = 0xFF13834F; t.warn = 0xFFA35A00; t.bad = 0xFFC62828; t.glow = 0x553FBF94;
        l.add(t);

        t = new PageTheme("sky", "🌤️", "గాజు ఆకాశం", "మేఘాల మీద మసక గాజు పలకలు", 1);
        t.bg = BG_CLOUDS; t.style = GLASS; t.bgTop = 0xFF4D6878; t.bgBottom = 0xFF2A2C34;
        t.card = 0xA8394049; t.card2 = 0xA831363E; t.edge = 0x70FFFFFF; t.radius = 18;
        t.ink = 0xFFFFFFFF; t.inkPage = 0xFFFFFFFF; t.muted = 0xD6FFFFFF; t.mutedPage = 0xE6FFFFFF; t.faint = 0xA6FFFFFF;
        t.accent = 0xFFFFC061; t.accent2 = 0xFFFF8A1A; t.title1 = 0xFFFFFFFF; t.title2 = 0xFFFFD27A; t.iconBg = 0xB8141E23;
        t.field = 0x8C141E23; t.fieldEdge = 0x4DFFFFFF; t.swOn = 0xFFFFA21A; t.swOff = 0xB8141E23; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFE6E8EB;
        t.badge = 0xFFFFA21A; t.badgeInk = 0xFF1A1A1A; t.good = 0xFFB9F6CA; t.warn = 0xFFFFD27A; t.bad = 0xFFFFB4B4; t.glow = 0x66FFA21A;
        l.add(t);

        t = new PageTheme("dsoft", "🌑", "చీకటి మెరుపు", "ముదురు Soft UI · ఊదా-నీలి మెరుపు", 1);
        t.bg = BG_DARK; t.style = NEU; t.bgTop = 0xFF2B2E36; t.bgBottom = 0xFF22252B;
        t.card = 0xFF2E3139; t.card2 = 0xFF25272E; t.edge = 0x14FFFFFF; t.radius = 18; t.shadowDark = 0xFF15161A; t.shadowLight = 0xFF393D47;
        t.ink = 0xFFEEF1F6; t.inkPage = 0xFFEEF1F6; t.muted = 0xFF9CA3B0; t.mutedPage = 0xFF9CA3B0; t.faint = 0xFF7A818E;
        t.accent = 0xFF5EE6FF; t.accent2 = 0xFF8B5CF6; t.title1 = 0xFFEEF1F6; t.title2 = 0xFF5EE6FF; t.iconBg = 0xFF212329;
        t.field = 0xFF212329; t.fieldEdge = 0x1AFFFFFF; t.swOn = 0xFF8B5CF6; t.swOff = 0xFF1C1E23; t.thumb = 0xFFF2F4F8; t.thumbOff = 0xFFB9BEC8;
        t.badge = 0xFFFF8A2A; t.badgeInk = 0xFF111111; t.good = 0xFF7BE8B0; t.warn = 0xFFFFC46B; t.bad = 0xFFFF9C94; t.glow = 0x668B5CF6;
        l.add(t);

        t = new PageTheme("glow", "✨", "వెలుగు అంచు", "నల్లటి కార్డులు, అంచుల్లో రంగుల వెలుగు", 1);
        t.bg = BG_NEON; t.style = GLOW_EDGE; t.bgTop = 0xFF05050C; t.bgBottom = 0xFF020205;
        t.card = 0xFF0A0A13; t.card2 = 0xFF07070E; t.edge = 0xCCFF3BD4; t.edge2 = 0xCC3B6BFF; t.radius = 18;
        t.ink = 0xFFF4F4FF; t.inkPage = 0xFFF4F4FF; t.muted = 0xFF9DA1BC; t.mutedPage = 0xFF9DA1BC; t.faint = 0xFF757A96;
        t.accent = 0xFFFF8BE8; t.accent2 = 0xFF7B9BFF; t.title1 = 0xFFFF6BE0; t.title2 = 0xFF6B8CFF; t.iconBg = 0xFF15152A;
        t.field = 0xFF101020; t.fieldEdge = 0x669B6BFF; t.swOn = 0xFF9B6BFF; t.swOff = 0xFF1A1A2C; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFB8BACC;
        t.badge = 0xFFFFD166; t.badgeInk = 0xFF111111; t.good = 0xFF9DFFB0; t.warn = 0xFFFFD166; t.bad = 0xFFFF9C94; t.glow = 0x669B6BFF;
        l.add(t);

        t = new PageTheme("warm", "🕯️", "వెచ్చని గది", "దీపాల వెలుగు గది మీద గాజు", 1);
        t.bg = BG_LAMPS; t.style = GLASS; t.bgTop = 0xFF2B170D; t.bgBottom = 0xFF0F0805;
        t.card = 0x52241208; t.card2 = 0x52180C06; t.edge = 0x45FFD2AA; t.radius = 18;
        t.ink = 0xFFFFF3E8; t.inkPage = 0xFFFFF3E8; t.muted = 0xD9FFE8D4; t.mutedPage = 0xD9FFE8D4; t.faint = 0x99FFE8D4;
        t.accent = 0xFFFFB27A; t.accent2 = 0xFFFF7A2E; t.title1 = 0xFFFFE3C8; t.title2 = 0xFFFFA95E; t.iconBg = 0x33FFBE8C;
        t.field = 0x66120804; t.fieldEdge = 0x40FFD2AA; t.swOn = 0xFFFF7A2E; t.swOff = 0x40FFE2C8; t.thumb = 0xFFFFF6EE; t.thumbOff = 0xFFE8D6C8;
        t.badge = 0xFFFF8A3D; t.badgeInk = 0xFF1B100B; t.good = 0xFFB9F6CA; t.warn = 0xFFFFD27A; t.bad = 0xFFFFB4A8; t.glow = 0x66FF8A3D;
        l.add(t);

        // ---- four from the car box (its colours and card styles; the box's own looks stay as they are)
        t = new PageTheme("hud", "🛰️", "HUD బ్లూ", "కారు box లోని \"D HUD బ్లూ\" రంగులు", 2);
        t.bg = BG_WIRE; t.style = CUT; t.bgTop = 0xFF0B2A50; t.bgBottom = 0xFF020A16;
        t.card = 0x2E5FC8FF; t.card2 = 0x1A2A6CB0; t.edge = 0x804FD2FF; t.radius = 12;
        t.ink = 0xFFEAF6FF; t.inkPage = 0xFFEAF6FF; t.muted = 0xFFA9C2D6; t.mutedPage = 0xFFA9C2D6; t.faint = 0xFF7F9AB0;
        t.accent = 0xFF4FD2FF; t.accent2 = 0xFF8FE6FF; t.title1 = 0xFF8FE6FF; t.title2 = 0xFF4FD2FF; t.iconBg = 0x334FD2FF;
        t.field = 0x66020A16; t.fieldEdge = 0x664FD2FF; t.swOn = 0xFF4FD2FF; t.swOff = 0x33FFFFFF; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFB8C8D6;
        t.badge = 0xFFFFB02E; t.badgeInk = 0xFF111111; t.good = 0xFF9DF5C4; t.warn = 0xFFFFD27A; t.bad = 0xFFFFB4B4; t.glow = 0x664FD2FF;
        l.add(t);

        t = new PageTheme("lglass", "🫧", "⑰ లిక్విడ్ గ్లాస్", "కారు box లోని \"⑰ లిక్విడ్ గ్లాస్\" రంగులు", 2);
        t.lightPage = t.lightCard = true; t.bg = BG_BLOBS; t.style = GLASS; t.bgTop = 0xFFEDEBFB; t.bgBottom = 0xFFDDF1F7;
        t.card = 0xB3FFFFFF; t.card2 = 0x99FFFFFF; t.edge = 0xE6FFFFFF; t.radius = 20; t.shadowDark = 0x246C5CFF;
        t.ink = 0xFF1F2140; t.inkPage = 0xFF1F2140; t.muted = 0xFF4F5878; t.mutedPage = 0xFF4F5878; t.faint = 0xFF7A8098;
        t.accent = 0xFF5B4BE8; t.accent2 = 0xFF6C5CFF; t.title1 = 0xFF1F2140; t.title2 = 0xFF5B4BE8; t.iconBg = 0x1F6C5CFF;
        t.field = 0x99FFFFFF; t.fieldEdge = 0x336C5CFF; t.swOn = 0xFF6C5CFF; t.swOff = 0x24000000; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFFFFFFF;
        t.badge = 0xFFE8862A; t.badgeInk = 0xFF2A1805; t.good = 0xFF13834F; t.warn = 0xFFA35A00; t.bad = 0xFFC62828; t.glow = 0x556C5CFF;
        l.add(t);

        t = new PageTheme("dglass", "🌌", "⑱ డార్క్ గ్లాస్", "కారు box లోని \"⑱ డార్క్ గ్లాస్\" రంగులు", 2);
        t.bg = BG_NAVY; t.style = GLASS; t.bgTop = 0xFF0C1C44; t.bgBottom = 0xFF040A1C;
        t.card = 0x423262C4; t.card2 = 0x2E1E3E90; t.edge = 0x666EA0FF; t.radius = 20;
        t.ink = 0xFFEAF6FF; t.inkPage = 0xFFEAF6FF; t.muted = 0xFFA6BCD0; t.mutedPage = 0xFFA6BCD0; t.faint = 0xFF7D93AA;
        t.accent = 0xFF6FA8FF; t.accent2 = 0xFF3B8BFF; t.title1 = 0xFFEAF6FF; t.title2 = 0xFF6FA8FF; t.iconBg = 0x333B8BFF;
        t.field = 0x66040A1C; t.fieldEdge = 0x596EA0FF; t.swOn = 0xFF3B8BFF; t.swOff = 0x24FFFFFF; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFB8C4D6;
        t.badge = 0xFFFFB02E; t.badgeInk = 0xFF111111; t.good = 0xFF9DF5C4; t.warn = 0xFFFFD27A; t.bad = 0xFFFFB4B4; t.glow = 0x663B8BFF;
        l.add(t);

        t = new PageTheme("tiles", "🟦", "⑳ గ్లో టైల్స్", "కారు box లోని \"⑳ గ్లో టైల్స్\" రంగులు", 2);
        t.lightCard = true; t.bg = BG_TILES; t.style = TILE; t.bgTop = 0xFF11141F; t.bgBottom = 0xFF06070D;
        t.card = 0xFFF5F6FA; t.card2 = 0xFFEDEFF5; t.edge = 0; t.radius = 20; t.glows = new int[]{0xCC3A6BFF, 0xCCE0408A, 0xCC16A34A};
        t.ink = 0xFF1B2030; t.inkPage = 0xFFEAF0FF; t.muted = 0xFF545B6E; t.mutedPage = 0xFFA7B0C8; t.faint = 0xFF7D8496;
        t.accent = 0xFF2E5BE6; t.accent2 = 0xFF3A6BFF; t.title1 = 0xFFEAF0FF; t.title2 = 0xFF7FA0FF; t.iconBg = 0x1F3A6BFF;
        t.field = 0xFFE6E9F1; t.fieldEdge = 0x333A6BFF; t.swOn = 0xFF3A6BFF; t.swOff = 0xFFD9DCE6; t.thumb = 0xFFFFFFFF; t.thumbOff = 0xFFFFFFFF;
        t.badge = 0xFFFFB02E; t.badgeInk = 0xFF111111; t.good = 0xFF13834F; t.warn = 0xFFA35A00; t.bad = 0xFFC62828; t.glow = 0x553A6BFF;
        l.add(t);
        ALL = l.toArray(new PageTheme[0]);
    }

    /** One of the old three: the old page colours (glass cards on the moving rings), nothing repainted. */
    private static PageTheme old(String id, String emoji, String name, String note, int top, int bottom, int c1, int c2, int cyan) {
        PageTheme t = new PageTheme(id, emoji, name, note, 0);
        t.bgTop = top; t.bgBottom = bottom; t.bg = BG_AURORA; t.style = OLD;
        t.card = 0x14FFFFFF; t.card2 = 0x14FFFFFF; t.edge = 0x2BFFFFFF; t.radius = 18;
        t.ink = 0xFFDCEEF5; t.inkPage = 0xFFFFFFFF; t.muted = 0xFF88A5B3; t.mutedPage = 0xFF88A5B3; t.faint = 0xFF557584;
        t.accent = cyan; t.accent2 = c2; t.title1 = c1; t.title2 = c2; t.iconBg = (c1 & 0x00FFFFFF) | 0x33000000;
        t.field = 0x47000000; t.fieldEdge = 0x2BFFFFFF; t.swOn = c1; t.swOff = 0x44FFFFFF; t.thumb = c1; t.thumbOff = 0xFFB8C4CC;
        t.badge = 0xFFFBBF24; t.badgeInk = 0xFF221502; t.good = 0xFFB9F6CA; t.warn = 0xFFFDE68A; t.bad = 0xFFFFB4B4; t.glow = (c1 & 0x00FFFFFF) | 0x55000000;
        return t;
    }

    /** The theme with this id; null for an unknown one. */
    static PageTheme of(String id) {
        if (id != null) for (PageTheme t : ALL) if (t.id.equals(id)) return t;
        return null;
    }

    /**
     * Which theme the pages use: his page theme if he chose one of the new ten, else the old theme (mix / blue / gold).
     * followLook + a new main-screen look with its own page style: the look's (stage 2; "old" has none, so not yet).
     */
    static PageTheme resolve(String pageTheme, String oldTheme, boolean followLook, String look) {
        PageTheme lookStyle = followLook ? forLook(look) : null;
        if (lookStyle != null) return lookStyle;
        PageTheme p = of(pageTheme);
        if (p != null && p.isNew()) return p;
        PageTheme o = of(oldTheme);
        return o != null && !o.isNew() ? o : ALL[0];
    }

    /** The page style a main-screen look brings (none yet: the looks come in stage 2; the old look has none). */
    static PageTheme forLook(String look) { return null; }

    // ================================================================ colours (for painting and the desk tests)

    static int a(int c) { return c >>> 24; }
    static int r(int c) { return (c >> 16) & 0xFF; }
    static int g(int c) { return (c >> 8) & 0xFF; }
    static int b(int c) { return c & 0xFF; }
    static int argb(int a, int r, int g, int b) { return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static int withAlpha(int c, int alpha) { return (c & 0x00FFFFFF) | (clamp(alpha) << 24); }

    /** fg (with its alpha) laid over an opaque bg. */
    static int over(int fg, int bg) {
        float t = a(fg) / 255f;
        return argb(255, Math.round(r(fg) * t + r(bg) * (1 - t)), Math.round(g(fg) * t + g(bg) * (1 - t)), Math.round(b(fg) * t + b(bg) * (1 - t)));
    }

    /** Half way between two colours (opaque). */
    static int mix(int x, int y, float t) {
        return argb(255, Math.round(r(x) + (r(y) - r(x)) * t), Math.round(g(x) + (g(y) - g(x)) * t), Math.round(b(x) + (b(y) - b(x)) * t));
    }

    private static double lin(int v) { double s = v / 255.0; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); }

    /** Relative luminance (WCAG). */
    static double lum(int c) { return 0.2126 * lin(r(c)) + 0.7152 * lin(g(c)) + 0.0722 * lin(b(c)); }

    /** Contrast ratio of two opaque colours (1..21). */
    static double contrast(int x, int y) {
        double a = lum(x), b = lum(y);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** How far apart its strongest and weakest colour are (0 grey .. 255). */
    static int chroma(int c) {
        return Math.max(r(c), Math.max(g(c), b(c))) - Math.min(r(c), Math.min(g(c), b(c)));
    }

    /** How colourful (0 grey .. 1 full colour). */
    static double sat(int c) {
        int mx = Math.max(r(c), Math.max(g(c), b(c))), mn = Math.min(r(c), Math.min(g(c), b(c)));
        return mx == 0 ? 0 : (mx - mn) / (double) mx;
    }

    /** The page's colour behind a card, as one opaque colour (the middle of its background). */
    int pageBase() { return mix(bgTop, bgBottom, 0.5f); }

    /** The brightest / darkest the background gets under a card (its pictures: clouds, lamps, leaves). */
    int pageWorst() {
        switch (bg) {
            case BG_CLOUDS: return 0xFFD2CBD0; // (the brightest cloud)
            case BG_LAMPS: return 0xFF8A4A22;  // (the lamp glow behind a card)
            default: return lightPage ? bgBottom : bgTop;
        }
    }

    /** A card's colour as one opaque colour, laid on the page (at its middle, or its worst point). */
    int cardOn(int pageColour) { return over(mix2(card, card2), pageColour); }

    /** The two card colours' middle (keeping alpha). */
    private static int mix2(int x, int y) {
        return argb((a(x) + a(y)) / 2, (r(x) + r(y)) / 2, (g(x) + g(y)) / 2, (b(x) + b(y)) / 2);
    }

    /**
     * A text colour the old pages used, made readable on this theme: light grey-white letters become the theme's ink,
     * grey ones its softer line, coloured ones keep their colour but darker on light cards (lighter on dark ones) until
     * they read well. onCard: inside a card (else straight on the page). The old three: unchanged.
     */
    int text(int c, boolean onCard) {
        if (!isNew()) return c;
        int al = a(c);
        int solid = c | 0xFF000000;
        boolean light = onCard ? lightCard : lightPage;
        int ink = onCard ? this.ink : inkPage, soft = onCard ? muted : mutedPage;
        double l = lum(solid), s = sat(solid);
        if (s < 0.28) { // grey family: white / TEXT → ink, MUTED → soft, FAINT → faint
            int base = l > 0.45 ? ink : l > 0.12 ? soft : (light ? ink : soft);
            if (l <= 0.03 && light) base = ink; // (dark letters meant for a light chip: still the ink)
            if (l <= 0.03 && !light) return c; // (dark letters on a coloured button the page drew: keep)
            return al < 0xFF ? withAlpha(base, Math.max(al, 0xE6)) : base;
        }
        // (on a card: readable where the card sits on the page's middle and on its brightest / darkest picture)
        return readable(solid, onCard ? cardOn(pageBase()) : pageBase(), onCard ? cardOn(pageWorst()) : pageBase(), 3.2);
    }

    /** c, made darker (on light) or lighter (on dark) in its own colour until its contrast with bg is at least min. */
    static int readable(int c, int bg, double min) { return readable(c, bg, bg, min); }

    /** ... with both backgrounds (the same side: both light or both dark). */
    static int readable(int c, int bg1, int bg2, double min) {
        c |= 0xFF000000;
        if (Math.min(contrast(c, bg1), contrast(c, bg2)) >= min) return c;
        boolean darker = lum(bg1) > 0.35;
        int target = darker ? 0xFF000000 : 0xFFFFFFFF;
        for (int i = 1; i <= 20; i++) {
            int m = mix(c, target, i / 20f);
            if (Math.min(contrast(m, bg1), contrast(m, bg2)) >= min) return m;
        }
        return target;
    }

    /** A background fill the old pages drew (glass, dark boxes, coloured chips), as this theme's. 0: keep it. */
    int fill(int c, boolean onCard) {
        if (!isNew()) return 0;
        int al = a(c);
        if (al == 0) return 0;
        int solid = c | 0xFF000000;
        double l = lum(solid), s = sat(solid);
        if (s < 0.25 && l > 0.6 && al < 0x70) return onCard ? inner() : card; // white glass → a box in a card / a card
        if (l < 0.08 && s < 0.5) return field;                                // a dark box (typing, a pill)
        return 0;                                                             // coloured chips and buttons: kept
    }

    /** A box inside a card (a shade of the card: the ink, very faint, on light cards; faint white on dark ones). */
    int inner() { return lightCard ? withAlpha(ink, 0x12) : 0x14FFFFFF; }

    /** Old three: Ui's own theme ids (mix / blue / gold), the ones the main screen and the watch know. */
    static boolean isOldId(String id) { return "mix".equals(id) || "blue".equals(id) || "gold".equals(id); }
}
