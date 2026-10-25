package com.anil.jarvis;

/**
 * 🎨 The car screen's ten looks (Anil, 24 Oct: "car launcher లాగా 10 థీమ్స్", 3D, modern). A theme is its colours
 * (day and night), its background pattern, its gauge style and its tile style; the screen's mode (driving / a song
 * playing / parked) picks the accent. Plain Java: {@link #look(int, boolean, int)} resolves everything to ints, so a
 * desk test can check the set without Android. Our own designs; the car drawing carries no maker's badge.
 */
final class CarTheme {
    static final int DRIVING = 0, MUSIC = 1, PARKED = 2;
    // background patterns (drawn by CarBackdrop)
    static final int P_NONE = 0, P_GRID = 1, P_CARBON = 2, P_HEX = 3, P_WIRE = 4, P_SCAN = 5, P_LEATHER = 6, P_HORIZON = 7, P_STARS = 8;
    // gauge styles
    static final int G_ARC = 0, G_NEEDLE = 1, G_DIGITS = 2;
    static final int COUNT = 10;

    final int id;
    final String name;
    final int bgTop, bgBottom, nightTop, nightBottom;
    final int accent, musicAccent, parkAccent, nightAccent;
    final int pattern, gauge;
    final boolean light;

    private CarTheme(int id, String name, int bgTop, int bgBottom, int nightTop, int nightBottom, int accent, int musicAccent, int parkAccent,
                     int nightAccent, int pattern, int gauge, boolean light) {
        this.id = id; this.name = name; this.bgTop = bgTop; this.bgBottom = bgBottom; this.nightTop = nightTop; this.nightBottom = nightBottom;
        this.accent = accent; this.musicAccent = musicAccent; this.parkAccent = parkAccent; this.nightAccent = nightAccent;
        this.pattern = pattern; this.gauge = gauge; this.light = light;
    }

    static final CarTheme[] ALL = {
            new CarTheme(1, "నీలి గాజు", 0xFF10304A, 0xFF04070D, 0xFF061421, 0xFF020407, 0xFF5FF3FF, 0xFFD58CFF, 0xFF7DFFC8, 0xFF3FC6E0, P_GRID, G_ARC, false),
            new CarTheme(2, "కార్బన్", 0xFF1C1C1C, 0xFF070707, 0xFF101010, 0xFF030303, 0xFFFF8A3D, 0xFFFF5EA8, 0xFF7DFFC8, 0xFFD9742F, P_CARBON, G_ARC, false),
            new CarTheme(3, "నియాన్ నైట్", 0xFF2A0F4A, 0xFF05020B, 0xFF150726, 0xFF030108, 0xFFFF4FD8, 0xFF9B5CFF, 0xFF5FF3FF, 0xFFD63FB6, P_GRID, G_ARC, false),
            new CarTheme(4, "రాత్రి ఎరుపు", 0xFF2A0D0D, 0xFF050202, 0xFF1A0707, 0xFF030101, 0xFFFF5A3C, 0xFFFF7A5C, 0xFFFFB02E, 0xFFD6482E, P_NONE, G_ARC, false),
            new CarTheme(5, "తెలుపు మినిమల్", 0xFFF7F9FC, 0xFFE3E9F2, 0xFF1B2430, 0xFF0B1118, 0xFF1F8FFF, 0xFF8A4DFF, 0xFF13A37A, 0xFF5FB3FF, P_NONE, G_ARC, true),
            new CarTheme(6, "క్లాసిక్ మీటర్లు", 0xFF2B2520, 0xFF15110E, 0xFF1B1613, 0xFF0A0806, 0xFFFF6B3D, 0xFFFFB02E, 0xFF9BCF7A, 0xFFD9582F, P_LEATHER, G_NEEDLE, false),
            new CarTheme(7, "హారిజన్", 0xFF1B1038, 0xFF0A0612, 0xFF0E0820, 0xFF04020A, 0xFFFFB02E, 0xFFFF6AD5, 0xFFFFD27A, 0xFFE0962A, P_HORIZON, G_ARC, false),
            new CarTheme(8, "అడవి ఆకుపచ్చ", 0xFF0F3A2E, 0xFF04070D, 0xFF07201A, 0xFF020504, 0xFF7DFFC8, 0xFFB8FF7A, 0xFF5FF3FF, 0xFF4FD6A3, P_HEX, G_ARC, false),
            new CarTheme(9, "బంగారు", 0xFF171310, 0xFF070605, 0xFF0F0C0A, 0xFF030202, 0xFFFFD166, 0xFFFF9F66, 0xFFE6FFB0, 0xFFD6AE4E, P_NONE, G_ARC, false),
            new CarTheme(10, "హోలోగ్రామ్", 0xFF0A2440, 0xFF03101F, 0xFF061728, 0xFF020810, 0xFF5FB3FF, 0xFFB08CFF, 0xFF7DFFC8, 0xFF4A96DB, P_WIRE, G_ARC, false),
    };

    static CarTheme of(int id) {
        for (CarTheme t : ALL) if (t.id == id) return t;
        return ALL[0];
    }

    /** The colours of the screen right now (a theme, night or day, the mode). */
    static final class Look {
        int bgTop, bgBottom, accent, glow, glassFill, glassStroke, tileFill, tileStroke, textMain, textSoft, textFaint, sheetFill, patternInk;
        int pattern, gauge;
        boolean light, night;
    }

    Look look(int mode, boolean night) { return look(mode, night, 0); }

    /** accentOverride: his own colour from the theme editor (0: the theme's). */
    Look look(int mode, boolean night, int accentOverride) { return look(mode, night, accentOverride, 0); }

    /** songAccent: 🎨 the song picture's colour while a song plays (0: the theme's music colour). */
    Look look(int mode, boolean night, int accentOverride, int songAccent) {
        Look l = new Look();
        l.night = night;
        l.light = light && !night; // (the light theme goes dark at night: no white screen in a dark car)
        l.bgTop = night ? nightTop : bgTop;
        l.bgBottom = night ? nightBottom : bgBottom;
        int a = mode == MUSIC ? musicAccent : mode == PARKED ? parkAccent : night ? nightAccent : accent;
        if (accentOverride != 0 && mode == DRIVING) a = accentOverride;
        if (songAccent != 0 && mode == MUSIC) a = night ? shade(songAccent, 0.8f) : l.light ? shade(songAccent, 0.55f) : songAccent; // (softer at night; darker on the white theme: readable)
        l.accent = a;
        l.glow = withAlpha(a, night ? 0x44 : 0x66);
        if (l.light) {
            l.glassFill = 0xCCFFFFFF; l.glassStroke = 0x33000000; l.tileFill = 0xE6FFFFFF; l.tileStroke = withAlpha(a, 0x66);
            l.textMain = 0xFF17232F; l.textSoft = 0xFF5D6C7A; l.textFaint = 0xFF9AA7B4; l.sheetFill = 0xF2FFFFFF; l.patternInk = 0x14000000;
        } else {
            l.glassFill = night ? 0x1AFFFFFF : 0x24FFFFFF; l.glassStroke = withAlpha(a, night ? 0x33 : 0x55);
            l.tileFill = night ? 0x14FFFFFF : 0x1FFFFFFF; l.tileStroke = withAlpha(a, night ? 0x40 : 0x66);
            l.textMain = night ? 0xFFC9D4DF : 0xFFEAF6FF; l.textSoft = night ? 0xFF7E8E9C : 0xFF9FB6C9; l.textFaint = night ? 0xFF4E5B68 : 0xFF6B7F93;
            l.sheetFill = night ? 0xF20B121B : 0xF213233A; l.patternInk = withAlpha(a, night ? 0x10 : 0x18);
        }
        l.pattern = pattern;
        l.gauge = gauge;
        return l;
    }

    static int withAlpha(int c, int alpha) { return (c & 0x00FFFFFF) | (alpha << 24); }

    /** A darker / lighter shade of a colour (f < 1 darker). */
    static int shade(int c, float f) {
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * f)), g = Math.min(255, Math.round(((c >> 8) & 0xFF) * f)), b = Math.min(255, Math.round((c & 0xFF) * f));
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** The mode's name for the state line. */
    static String modeWord(int mode) { return mode == MUSIC ? "పాట" : mode == PARKED ? "ఆగి ఉన్నారు" : "నడుపుతున్నారు"; }

    /** The car's own colours he can pick (his Nexon: blue). */
    static final int[] CAR_COLOURS = {0xFF2F6FE4, 0xFFE8EDF2, 0xFF1C1F24, 0xFFC0392B, 0xFF8E9BA8, 0xFFE0B043, 0xFF2E9B6A, 0xFF7A2A8A};
    static final String[] CAR_COLOUR_NAMES = {"నీలం", "తెలుపు", "నలుపు", "ఎరుపు", "బూడిద", "బంగారు", "ఆకుపచ్చ", "ఊదా"};
}
