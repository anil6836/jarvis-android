package com.anil.jarvis;

/**
 * The calm, dark sky behind Jarvis on the home tablet: the weather outside (and the day's occasion) as a slow
 * animation, kept to the top and the edges so the middle (Jarvis, his bubble) stays quiet. WeatherSky puts it on the
 * screen; this class is plain Java (no Android), so a desk test can draw it into a picture.
 *
 * <p>Scenes by Open-Meteo's WMO code: 0 clear (day: sun with slowly turning rays; night: moon and twinkling stars),
 * 1–2 a few clouds, 3 overcast, 45/48 fog, 51–57 drizzle, 61–67 rain (with small splashes), 71–77 snow, 80–86 showers
 * (rain, or snow for 85/86, in bursts), 95–99 thunderstorm (a soft glow in the clouds at most once every 10–20 s —
 * never a white flash); anything else: only the round light. Below 20 °C mist (and on a clear sky thin clouds) drifts
 * by; above 34 °C in the day the sun is bigger and the air low down shimmers. Occasions sit on top: Christmas, New
 * Year, Sankranti, Easter, a birthday. In the morning (setBirds) a few small birds slowly cross the upper sky — by day
 * under a clear, cloudy or grey sky only, never on the dimmed screen.
 *
 * <p>Everything is made in set / setDim / setOccasion / setBirds / resize; step and draw allocate nothing.
 */
final class SkyScene {

    /** What the scene draws with (a Canvas on the tablet, Java2D in the desk test). Colours are ARGB. */
    interface Brush {
        void rect(float l, float t, float r, float b, int argb);
        void circle(float cx, float cy, float r, int argb);
        void oval(float cx, float cy, float rx, float ry, int argb);
        /** An oval's outline. */
        void ring(float cx, float cy, float rx, float ry, float width, int argb);
        /** A line with round ends. */
        void line(float x0, float y0, float x1, float y1, float width, int argb);
        /** One joined line through the first n points of xy (x0, y0, x1, y1, …), round ends and joins. */
        void polyline(float[] xy, int n, float width, int argb);
        /** A filled four-cornered shape (a triangle when the last two corners are the same). */
        void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb);
        /**
         * The rectangle filled with a linear gradient from (x0, y0) to (x1, y1). The gradient (its points, colours and
         * stops) only changes when the scene or the size does, so it may be cached under its slot; alpha (0..1) fades it.
         */
        void linear(int slot, float l, float t, float r, float b, float x0, float y0, float x1, float y1,
                    int[] colors, float[] stops, float alpha);
        /**
         * A circle of radius r around (cx, cy), squashed to the height ry (an oval), filled with a radial gradient. The
         * gradient (r, colours, stops) only changes with the scene or the size: cache it under its slot (it may move);
         * alpha (0..1) fades it.
         */
        void radial(int slot, float cx, float cy, float r, float ry, int[] colors, float[] stops, float alpha);
    }

    /** Gradient cache slots (a Brush keeps one gradient per slot). */
    static final int SLOTS = 10;
    private static final int S_SKY = 0, S_GLOW = 1, S_HALO = 2, S_DISC = 3, S_MIST = 4, S_FLASH = 5, S_HAZE = 6,
            S_RISE = 7, S_LIGHT = 8, S_STAR = 9;

    static final int NONE = 0, CLEAR = 1, CLOUDS = 2, OVERCAST = 3, FOG = 4, DRIZZLE = 5, RAIN = 6, SNOW = 7,
            SHOWERS = 8, STORM = 9;
    static final int O_NONE = 0, O_CHRISTMAS = 1, O_NEWYEAR = 2, O_SANKRANTI = 3, O_EASTER = 4, O_BIRTHDAY = 5;

    /** The home screen's navy, and the old round light behind Jarvis. */
    static final int BG = 0xFF0E1A2C, SPOT = 0xFF15294A;

    /** The scene for a WMO weather code. */
    static int kindOf(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return CLOUDS;
        if (code == 3) return OVERCAST;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if (code >= 61 && code <= 67) return RAIN;
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 86) return SHOWERS;
        if (code >= 95 && code <= 99) return STORM;
        return NONE;
    }

    static int occasionOf(String o) {
        if (o == null) return O_NONE;
        switch (o.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "christmas": return O_CHRISTMAS;
            case "newyear": case "new year": case "new_year": return O_NEWYEAR;
            case "sankranti": return O_SANKRANTI;
            case "easter": return O_EASTER;
            case "birthday": return O_BIRTHDAY;
            default: return O_NONE;
        }
    }

    // ================================================================ state
    private int w, h;
    /** One unit: a thousandth of the shorter side (sizes and speeds are in units, so any screen looks the same). */
    private float u;
    private float gx, gy, gr;
    private int code = -1, kind = NONE, occ = O_NONE;
    private boolean day = true, cold, hot, dim;
    private float t;
    private int seed = 0x2545F491;

    // gradients (new arrays only when the scene changes, so a Brush's cache holds)
    private int[] skyC, glowC, haloC, discC, mistC, flashC, hazeC, riseC, lightC, starC;
    private static final float[] SKY_AT = {0f, 0.45f, 1f}, GLOW_AT = {0f, 0.84f, 1f}, DISC_AT = {0f, 0.72f, 0.93f, 1f},
            HALO_AT = {0f, 0.42f, 1f}, MIST_AT = {0f, 0.5f, 1f}, HAZE_AT = {0f, 1f}, RISE_AT = {0f, 0.4f, 0.7f, 1f},
            LIGHT_AT = {0f, 0.45f, 1f};

    // the sun or the moon
    private static final int ORB_NONE = 0, ORB_SUN = 1, ORB_MOON = 2, ORB_PALE = 3;
    private int orb;
    private boolean rays;
    private float oX, oY, oR, haloR;

    // stars
    private static final int MAX_STARS = 60;
    private final float[] stX = new float[MAX_STARS], stY = new float[MAX_STARS], stR = new float[MAX_STARS],
            stA = new float[MAX_STARS], stF = new float[MAX_STARS], stP = new float[MAX_STARS];
    private int nStars;

    // clouds: a base oval and a few round puffs; back ones (layer 0) are smaller, darker, slower
    private static final int MAX_CLOUDS = 7;
    private final float[] clX = new float[MAX_CLOUDS], clY = new float[MAX_CLOUDS], clS = new float[MAX_CLOUDS],
            clV = new float[MAX_CLOUDS];
    private final int[] clShape = new int[MAX_CLOUDS], clLayer = new int[MAX_CLOUDS];
    private int nClouds;
    private final int[] clBody = new int[2], clHi = new int[2];
    /** In cloud units: the base oval (x, y, rx, ry), then puffs (x, y, r)…; y = 0 is the cloud's bottom line. */
    private static final float[][] SHAPES = {
            {0f, -0.1f, 1f, 0.26f, -0.55f, -0.28f, 0.32f, -0.12f, -0.5f, 0.42f, 0.36f, -0.38f, 0.36f, 0.72f, -0.2f, 0.24f},
            {0f, -0.1f, 0.95f, 0.26f, -0.6f, -0.24f, 0.28f, -0.22f, -0.44f, 0.38f, 0.22f, -0.55f, 0.42f, 0.62f, -0.3f, 0.3f},
            {0f, -0.08f, 1.1f, 0.2f, -0.72f, -0.18f, 0.22f, -0.36f, -0.32f, 0.3f, 0.04f, -0.38f, 0.33f, 0.44f, -0.3f, 0.28f,
                    0.8f, -0.16f, 0.2f},
    };

    // mist / fog: wide soft ovals drifting sideways
    private static final int MAX_MIST = 12;
    private final float[] mX = new float[MAX_MIST], mY = new float[MAX_MIST], mRy = new float[MAX_MIST],
            mA = new float[MAX_MIST], mV = new float[MAX_MIST];
    private int nMist;
    private float mistR;

    // rain (and drizzle)
    private static final int MAX_DROPS = 120;
    private static final float SLANT = 0.12f;
    private final float[] rX = new float[MAX_DROPS], rY = new float[MAX_DROPS], rV = new float[MAX_DROPS],
            rL = new float[MAX_DROPS], rG = new float[MAX_DROPS];
    private final boolean[] rOn = new boolean[MAX_DROPS];
    private int nDrops, rainC;
    private boolean drizzle, splashes;

    private static final int MAX_SPLASH = 24;
    private static final float SPLASH_LIFE = 0.45f;
    private final float[] spX = new float[MAX_SPLASH], spY = new float[MAX_SPLASH], spT = new float[MAX_SPLASH];

    // snow
    private static final int MAX_FLAKES = 60;
    private final float[] snX = new float[MAX_FLAKES], snY = new float[MAX_FLAKES], snV = new float[MAX_FLAKES],
            snR = new float[MAX_FLAKES], snA = new float[MAX_FLAKES], snP = new float[MAX_FLAKES],
            snF = new float[MAX_FLAKES], snW = new float[MAX_FLAKES];
    private final boolean[] snOn = new boolean[MAX_FLAKES];
    private int nFlakes, snowC;
    private boolean grains;
    /** Soft snow banks in the bottom corners. */
    private boolean drifts;
    private int driftC, driftHi;
    /** A soft wash low down: warm (heat), grey (fog), cool (cold mist). */
    private boolean haze;

    // showers: the rain (or snow) comes and goes
    private boolean bursts;
    private float burst = 1f, burstTarget = 1f, burstTimer;

    // thunderstorm: a soft glow in the clouds now and then
    private boolean storm;
    private float nextFlash, flashT = -1f, flashX, flashY;

    // heat: faint wavy lines rising low down
    private static final int MAX_HEAT = 5;
    private final float[] hX = new float[MAX_HEAT], hY = new float[MAX_HEAT],
            hT = new float[MAX_HEAT], hL = new float[MAX_HEAT], hP = new float[MAX_HEAT];
    private int nHeat;

    /** Scratch points for polylines. */
    private final float[] pts = new float[2 * 64];

    // ---- occasions
    // Christmas: fairy lights along the top, one star
    private static final int MAX_LIGHTS = 24;
    private final float[] liX = new float[MAX_LIGHTS], liF = new float[MAX_LIGHTS], liP = new float[MAX_LIGHTS];
    private final int[] liC = new int[MAX_LIGHTS];
    private int nLights;
    private static final int[] LIGHT_COLS = {0xFFFFD27F, 0xFFFFE6B8, 0xFFF7A88C, 0xFFFFC46A, 0xFFF2A0A8, 0xFFFFE0A0};
    private float starX, starY;
    // New Year: slow soft bursts high up, and a few sparkles
    private static final int MAX_FW = 4, FW_PTS = 18;
    private static final float FW_LIFE = 4.2f;
    private final float[] fwX = new float[MAX_FW], fwY = new float[MAX_FW], fwT = new float[MAX_FW],
            fwR = new float[MAX_FW], fwRot = new float[MAX_FW];
    private final int[] fwC = new int[MAX_FW];
    private float fwNext;
    private static final int[] FW_COLS = {0xFFF4D58A, 0xFFF2A6B8, 0xFF94D8CF, 0xFFC4B4F0, 0xFFF7BC8E};
    private static final int MAX_SPARKS = 12;
    private final float[] skX = new float[MAX_SPARKS], skY = new float[MAX_SPARKS], skF = new float[MAX_SPARKS],
            skP = new float[MAX_SPARKS];
    private int nSparks;
    // Sankranti: kites
    private static final int KITES = 3;
    private final float[] kX = new float[KITES], kY = new float[KITES], kS = new float[KITES], kP = new float[KITES];
    private static final int[] KITE_A = {0xFFD2607C, 0xFF3E9F98, 0xFF6C7FD4}, KITE_B = {0xFFE8A24E, 0xFFE6CF78, 0xFFE4DCCD};
    // Easter: a sunrise glow and flowers along the bottom edges
    private static final int MAX_FLOWERS = 8;
    private final float[] fX = new float[MAX_FLOWERS], fH = new float[MAX_FLOWERS], fP = new float[MAX_FLOWERS],
            fR = new float[MAX_FLOWERS];
    private final int[] fC = new int[MAX_FLOWERS], fN = new int[MAX_FLOWERS];
    private int nFlowers;
    private static final int[] PETALS = {0xFFEDE7F2, 0xFFE597B2, 0xFFB7A2E2, 0xFFF0D27C};
    // birthday: balloons at the sides, bunting along the top
    private static final int BALLOONS = 4;
    private final float[] bX = new float[BALLOONS], bY = new float[BALLOONS], bV = new float[BALLOONS], bP = new float[BALLOONS];
    private final int[] bC = new int[BALLOONS];
    private static final int[] BALLOON_COLS = {0xFFD9667F, 0xFF5E98D6, 0xFFE3B653, 0xFF56B79A, 0xFF9A82D6};
    private static final int MAX_FLAGS = 24;
    private int nFlags;
    private static final int[] FLAG_COLS = {0xFFE07890, 0xFFEBC261, 0xFF5DBFB0, 0xFFA58BDA, 0xFF6AA6DE};

    // ---- morning birds: two loose flocks (three birds, and a pair) slowly crossing the upper sky. They use their own
    // random numbers, so the weather's own randomness is the same with or without them.
    private static final int MAX_BIRDS = 5, FLOCKS = 2;
    private static final int[] FLOCK_FIRST = {0, 3}, FLOCK_SIZE = {3, 2};
    private static final int[] BIRD_COLS = {0xFF0F1A2C, 0xFF172231, 0xFF121D34};
    private static final float TAU = 6.2831855f;
    private boolean birds;
    /** The birds' fade (0..1): in when turned on, out when turned off; 0 while the sky has no birds. */
    private float birdA;
    private int birdSeed = 0x6D2B79F5;
    // a flock: direction (+1 to the right), the leader's x, the height of its path, speed (px/s), the pause before it
    // comes again (> 0: away), a slow rise and fall of the path, and how far behind the leader its last bird is (px)
    private final float[] flockDir = new float[FLOCKS], flockX = new float[FLOCKS], flockY = new float[FLOCKS],
            flockV = new float[FLOCKS], flockWait = new float[FLOCKS], flockWave = new float[FLOCKS],
            flockWaveF = new float[FLOCKS], flockReach = new float[FLOCKS];
    // a bird: its place behind the leader (units), wingspan (units), wing beats a second, the beat's phase, a gentle
    // bob, how hard it flaps now (eases down while it glides) and how long until it switches
    private final float[] birdDx = new float[MAX_BIRDS], birdDy = new float[MAX_BIRDS], birdSpan = new float[MAX_BIRDS],
            birdHz = new float[MAX_BIRDS], birdPh = new float[MAX_BIRDS], birdBob = new float[MAX_BIRDS],
            birdBobF = new float[MAX_BIRDS], birdAmp = new float[MAX_BIRDS], birdGlide = new float[MAX_BIRDS];
    private final boolean[] birdFlaps = new boolean[MAX_BIRDS];
    private final int[] birdCol = new int[MAX_BIRDS];
    /** Scratch points for a bird's wings (tip to tip) and their thicker inner half. */
    private final float[] wingPts = new float[2 * 9], wingIn = new float[2 * 5];

    // ================================================================ settings
    /** WMO weather code (-1 unknown), temperature °C (NaN unknown), day or night. */
    void set(int wmoCode, float tempC, boolean isDay) {
        int k = kindOf(wmoCode);
        // (cold and heat only change the clear / cloudy skies, so the rain is not remade when it cools down)
        boolean c = !Float.isNaN(tempC) && tempC < 20f && (k == CLEAR || k == CLOUDS || k == OVERCAST);
        boolean hh = isDay && !Float.isNaN(tempC) && tempC > 34f && (k == CLEAR || k == CLOUDS);
        if (wmoCode == code && isDay == day && c == cold && hh == hot) return;
        code = wmoCode;
        kind = k;
        day = isDay;
        cold = c;
        hot = hh;
        build();
    }

    /** Night screen / dimmed: softer colours. */
    void setDim(boolean d) {
        if (d == dim) return;
        dim = d;
        colours();
    }

    /** "none", "christmas", "newyear", "sankranti", "easter" or "birthday". */
    void setOccasion(String o) {
        int n = occasionOf(o);
        if (n == occ) return;
        occ = n;
        buildOccasion();
        colours();
    }

    /**
     * Morning birds: a few small birds slowly crossing the upper sky. They show only by day under a clear, cloudy or
     * grey sky, and not on the dimmed (night) screen; they fade in when turned on and out when turned off.
     */
    void setBirds(boolean on) {
        if (on == birds) return;
        birds = on;
        if (on && birdA <= 0f) placeBirds();
    }

    void resize(int width, int height) {
        if (width == w && height == h) return;
        w = width;
        h = height;
        build();
        placeBirds();
    }

    /** True when something moves (else one frame is enough). */
    boolean moving() { return kind != NONE || occ != O_NONE || (birds || birdA > 0f) && birdSky(); }

    /** Desk test: a lightning glow now (only in a thunderstorm). */
    void flash() { if (storm) startFlash(); }

    // ================================================================ building a scene
    private void build() {
        if (w <= 0 || h <= 0) return;
        float m = Math.min(w, h);
        u = m / 1000f;
        gx = w / 2f;
        gy = h * 0.46f;
        gr = m * 0.36f;
        seed = 0x2545F491 ^ (code * 7919 + (day ? 1 : 2));
        nStars = nClouds = nMist = nDrops = nFlakes = nHeat = 0;
        orb = ORB_NONE;
        rays = drizzle = splashes = grains = bursts = storm = drifts = haze = false;
        burst = burstTarget = 1f;
        flashT = -1f;
        for (int i = 0; i < MAX_SPLASH; i++) spT[i] = -1f;
        boolean night = !day;
        switch (kind) {
            case CLEAR:
                orb(night ? ORB_MOON : ORB_SUN);
                if (night) stars(MAX_STARS);
                if (cold) mistLow(true);
                if (hot) heat();
                break;
            case CLOUDS:
                orb(night ? ORB_MOON : ORB_SUN);
                if (night) stars(code == 1 ? 40 : 28);
                clouds(code == 1 ? 2 : 4, false);
                if (cold) mistLow(false);
                if (hot) heat();
                break;
            case OVERCAST:
                clouds(7, true);
                if (cold) mistLow(false);
                break;
            case FOG:
                if (day) orb(ORB_PALE);
                fog();
                haze = true;
                break;
            case DRIZZLE:
                clouds(5, true);
                rain(code == 51 || code == 56 ? 32 : code == 53 ? 44 : 56, true);
                break;
            case RAIN:
                clouds(6, true);
                rain(code == 61 ? 65 : code == 63 ? 90 : code == 66 ? 70 : code == 67 ? 105 : MAX_DROPS, false);
                splashes = true;
                break;
            case SNOW:
                clouds(4, false);
                snow(code == 71 ? 34 : code == 73 ? 46 : code == 77 ? 44 : MAX_FLAKES, code == 77);
                drifts = true;
                break;
            case SHOWERS:
                if (day) orb(ORB_SUN);
                clouds(5, false);
                if (code == 85 || code == 86) { snow(code == 85 ? 40 : MAX_FLAKES, false); drifts = true; }
                else { rain(code == 80 ? 70 : code == 81 ? 95 : MAX_DROPS, false); splashes = true; }
                bursts = true;
                burstTimer = rnd(5f, 9f);
                break;
            case STORM:
                clouds(7, true);
                rain(code == 95 ? 100 : 115, false);
                splashes = true;
                storm = true;
                nextFlash = rnd(10f, 20f);
                break;
            default:
                break;
        }
        buildOccasion();
        colours();
    }

    private void orb(int o) {
        float m = Math.min(w, h);
        orb = o;
        rays = o == ORB_SUN;
        oX = w * 0.84f;
        oY = h * 0.125f;
        oR = o == ORB_MOON ? m * 0.042f : m * 0.05f;
        if (o == ORB_SUN && hot) oR *= 1.3f;
        haloR = oR * (o == ORB_SUN && hot ? 4.2f : 3.4f);
    }

    /** Stars in the top band and down the sides (not behind Jarvis's light, not on the moon). */
    private void stars(int n) {
        nStars = 0;
        for (int tries = 0; nStars < n && tries < n * 30; tries++) {
            float x = rnd(0.02f, 0.98f) * w, y = rnd(0.015f, 0.62f) * h;
            if (near(x, y, gx, gy, gr * 1.04f)) continue;
            if (orb != ORB_NONE && near(x, y, oX, oY, oR * 3.2f)) continue;
            int i = nStars++;
            stX[i] = x;
            stY[i] = y;
            float k = rnd();
            stR[i] = (k < 0.72f ? rnd(1.1f, 1.7f) : k < 0.92f ? rnd(2f, 2.4f) : rnd(2.6f, 3f)) * u;
            stA[i] = rnd(0.45f, 0.9f);
            stF[i] = rnd(0.4f, 1.2f);
            stP[i] = rnd(0f, 6.283f);
        }
    }

    private void clouds(int n, boolean heavy) {
        nClouds = Math.min(n, MAX_CLOUDS);
        float off = rnd();
        for (int i = 0; i < nClouds; i++) {
            int layer = heavy ? (i < (nClouds + 1) / 2 ? 0 : 1) : (i % 2 == 0 ? 1 : 0);
            clLayer[i] = layer;
            clShape[i] = i % 3;
            float s = (layer == 0 ? rnd(0.1f, 0.13f) : rnd(0.13f, 0.17f)) * w * (heavy ? 1.15f : 1f);
            clS[i] = s;
            float f = (off + i * 0.618034f) % 1f;
            clX[i] = (f * 1.3f - 0.15f) * w;
            clY[i] = (layer == 0 ? rnd(0.15f, 0.21f) : rnd(0.22f, 0.3f)) * h - (heavy ? 0.025f * h : 0f);
            clV[i] = (layer == 0 ? rnd(4f, 6.5f) : rnd(7f, 10f)) * u * (day ? 1f : 0.8f);
        }
    }

    /** Fog: big soft layers, thicker at the top and the bottom than in the middle. */
    private void fog() {
        mistR = w * 0.62f;
        float[] rows = {0.04f, 0.17f, 0.31f, 0.5f, 0.68f, 0.83f, 0.96f, 0.1f, 0.9f};
        nMist = rows.length;
        for (int i = 0; i < nMist; i++) {
            mY[i] = rows[i] * h;
            mRy[i] = rnd(0.08f, 0.12f) * h;
            mX[i] = ((i * 0.618034f) % 1f) * w;
            mV[i] = (i % 2 == 0 ? 1 : -1) * rnd(5f, 9f) * u;
            float edge = Math.abs(rows[i] - 0.5f) * 2f; // 0 in the middle .. 1 at the edges
            mA[i] = 0.3f + 0.7f * edge;
        }
    }

    /** Cold: thin mist streaks drifting high up (on a clear sky) and low mist along the bottom. */
    private void mistLow(boolean high) {
        mistR = w * 0.45f;
        nMist = 0;
        if (high) {
            float[] ys = {0.09f, 0.19f, 0.3f};
            for (int i = 0; i < ys.length; i++) {
                int k = nMist++;
                mY[k] = ys[i] * h;
                mRy[k] = rnd(0.016f, 0.024f) * h;
                mX[k] = (0.15f + i * 0.35f) * w;
                mV[k] = rnd(4f, 6.5f) * u;
                mA[k] = 0.75f;
            }
        }
        float[] ys = {0.86f, 0.93f, 0.98f, 0.9f};
        for (int i = 0; i < ys.length; i++) {
            int k = nMist++;
            mY[k] = ys[i] * h;
            mRy[k] = rnd(0.04f, 0.06f) * h;
            mX[k] = (i * 0.37f % 1f) * w;
            mV[k] = (i % 2 == 0 ? 1 : -1) * rnd(4f, 7f) * u;
            mA[k] = 1f;
        }
        haze = true;
    }

    private void rain(int n, boolean light) {
        nDrops = Math.min(n, MAX_DROPS);
        drizzle = light;
        for (int i = 0; i < nDrops; i++) {
            drop(i, true);
            rG[i] = h * (0.965f + 0.035f * ((i * 0.618034f) % 1f));
        }
    }

    private void drop(int i, boolean anywhere) {
        rL[i] = (drizzle ? rnd(0.016f, 0.026f) : rnd(0.026f, 0.044f)) * h;
        rV[i] = (drizzle ? rnd(0.3f, 0.4f) : rnd(0.52f, 0.72f)) * h;
        rX[i] = rnd(-0.02f, 1.12f) * w;
        rY[i] = anywhere ? rnd(-0.05f, 1f) * h : -rL[i] - rnd(0f, 0.3f) * h;
        rOn[i] = true;
    }

    private void snow(int n, boolean small) {
        nFlakes = Math.min(n, MAX_FLAKES);
        grains = small;
        for (int i = 0; i < nFlakes; i++) flake(i, true);
    }

    private void flake(int i, boolean anywhere) {
        float r = grains ? rnd(1.6f, 2.6f) : rnd() < 0.55f ? rnd(2.6f, 4f) : rnd(4.2f, 6.4f);
        snR[i] = r * u;
        snV[i] = (14f + r * 6.5f) * u * rnd(0.85f, 1.15f);
        snX[i] = rnd(0f, 1f) * w;
        snY[i] = anywhere ? rnd(-0.03f, 1f) * h : -snR[i] - rnd(0f, 0.15f) * h;
        snP[i] = rnd(0f, 6.283f);
        snF[i] = rnd(0.45f, 1f);
        snW[i] = rnd(8f, 22f) * u;
        snA[i] = 0.55f + 0.45f * Math.min(1f, r / 6.4f);
        snOn[i] = true;
    }

    private void heat() {
        haze = true;
        nHeat = MAX_HEAT;
        for (int i = 0; i < nHeat; i++) { heatLine(i); hT[i] = rnd(0f, hL[i]); }
    }

    private void heatLine(int i) {
        boolean left = i % 2 == 0;
        hX[i] = (left ? rnd(0.02f, 0.14f) : rnd(0.8f, 0.92f)) * w;
        hY[i] = rnd(0.94f, 1.0f) * h;
        hT[i] = 0f;
        hL[i] = rnd(5f, 8f);
        hP[i] = rnd(0f, 6.283f);
    }

    /** The birds' first places: the group of three already part-way across, the pair a few seconds away. */
    private void placeBirds() {
        if (w <= 0 || h <= 0) return;
        flockWait[1] = brnd(2f, 7f);
        launchFlock(0, true);
    }

    /** A flock sets off: from just off one side (or, at the start, part-way across), at its own height and pace. */
    private void launchFlock(int f, boolean partWay) {
        float dir = brnd() < 0.5f ? 1f : -1f;
        float y = brnd(0.11f, 0.26f) * h;
        int o = 1 - f; // (away from the other flock's path while it flies)
        for (int tries = 0; tries < 8 && flockWait[o] <= 0f && Math.abs(y - flockY[o]) < 0.07f * h; tries++)
            y = brnd(0.11f, 0.26f) * h;
        flockDir[f] = dir;
        flockY[f] = y;
        flockV[f] = w / brnd(25f, 45f); // across in 25-45 s
        flockWave[f] = brnd(0f, TAU);
        flockWaveF[f] = TAU / brnd(16f, 28f);
        float back = 0f;
        for (int i = FLOCK_FIRST[f], end = i + FLOCK_SIZE[f]; i < end; i++) {
            boolean lead = i == FLOCK_FIRST[f];
            back += lead ? 0f : f == 0 ? brnd(34f, 80f) : brnd(110f, 260f);
            birdDx[i] = back;
            birdDy[i] = lead ? 0f : brnd(-24f, 24f);
            birdSpan[i] = brnd(31f, 39f);
            birdHz[i] = brnd(1.5f, 2.5f);
            birdPh[i] = brnd(0f, TAU);
            birdBob[i] = brnd(0f, TAU);
            birdBobF[i] = TAU / brnd(2.6f, 4f);
            birdFlaps[i] = brnd() < 0.75f;
            birdAmp[i] = birdFlaps[i] ? 1f : 0.14f;
            birdGlide[i] = birdFlaps[i] ? brnd(3f, 7f) : brnd(1.5f, 3.5f);
            birdCol[i] = (int) (brnd() * BIRD_COLS.length) % BIRD_COLS.length;
        }
        flockReach[f] = back * u;
        float edge = 30f * u;
        flockX[f] = partWay ? (dir > 0f ? brnd(0.15f, 0.45f) * w : w - brnd(0.15f, 0.45f) * w)
                : dir > 0f ? -edge : w + edge;
        flockWait[f] = 0f;
    }

    private void buildOccasion() {
        if (w <= 0 || h <= 0) return;
        nLights = nSparks = nFlowers = nFlags = 0;
        for (int i = 0; i < MAX_FW; i++) fwT[i] = -1f;
        switch (occ) {
            case O_CHRISTMAS:
                nLights = 22;
                for (int i = 0; i < nLights; i++) {
                    liX[i] = w * (i + 0.5f) / nLights;
                    liF[i] = rnd(0.5f, 1.1f);
                    liP[i] = rnd(0f, 6.283f);
                    liC[i] = LIGHT_COLS[i % LIGHT_COLS.length];
                }
                starX = w * 0.13f;
                starY = h * 0.16f;
                break;
            case O_NEWYEAR:
                fwNext = 0.5f;
                nSparks = 0;
                for (int tries = 0; nSparks < 10 && tries < 300; tries++) {
                    float x = rnd(0.03f, 0.97f) * w, y = rnd(0.02f, 0.42f) * h;
                    if (near(x, y, gx, gy, gr * 1.05f) || orb != ORB_NONE && near(x, y, oX, oY, haloR)) continue;
                    skX[nSparks] = x;
                    skY[nSparks] = y;
                    skF[nSparks] = rnd(0.5f, 1.1f);
                    skP[nSparks] = rnd(0f, 6.283f);
                    nSparks++;
                }
                // two bursts already open, so it never starts empty
                spawnBurst(); fwT[0] = FW_LIFE * 0.3f;
                spawnBurst(); fwT[1] = FW_LIFE * 0.05f;
                fwNext = 1.6f;
                break;
            case O_SANKRANTI:
                float[][] at = {{0.15f, 0.2f, 46f}, {0.4f, 0.07f, 34f}, {0.86f, 0.4f, 50f}};
                for (int i = 0; i < KITES; i++) {
                    kX[i] = at[i][0] * w;
                    kY[i] = at[i][1] * h;
                    kS[i] = at[i][2] * u;
                    kP[i] = i * 2.1f;
                }
                break;
            case O_EASTER:
                float[] xs = {0.035f, 0.09f, 0.145f, 0.195f, 0.805f, 0.855f, 0.91f, 0.965f};
                float[] hs = {120f, 175f, 95f, 140f, 135f, 90f, 180f, 115f};
                nFlowers = MAX_FLOWERS;
                for (int i = 0; i < nFlowers; i++) {
                    fX[i] = xs[i] * w;
                    fH[i] = hs[i] * u;
                    fP[i] = rnd(0f, 6.283f);
                    fR[i] = rnd(8.5f, 11f) * u;
                    fC[i] = PETALS[(i * 3 + 1) % PETALS.length];
                    fN[i] = i % 2 == 0 ? 5 : 6;
                }
                break;
            case O_BIRTHDAY:
                float[] bx = {0.07f, 0.18f, 0.82f, 0.93f}, by = {0.72f, 0.3f, 0.5f, 0.92f};
                for (int i = 0; i < BALLOONS; i++) {
                    bX[i] = bx[i] * w;
                    bY[i] = by[i] * h;
                    bV[i] = rnd(15f, 21f) * u;
                    bP[i] = rnd(0f, 6.283f);
                    bC[i] = BALLOON_COLS[(i * 2) % BALLOON_COLS.length];
                }
                nFlags = Math.min(MAX_FLAGS, 17);
                break;
            default:
                break;
        }
    }

    /** The colours (softened when dim); new gradient arrays only here. */
    private void colours() {
        boolean n = !day;
        int top, mid;
        switch (kind) {
            case CLEAR:
                top = n ? 0xFF060E1B : hot ? 0xFF3A6894 : 0xFF2B5884;
                mid = n ? 0xFF0A1424 : hot ? 0xFF1F3C5C : 0xFF173353;
                break;
            case CLOUDS:
                top = n ? 0xFF08111E : 0xFF294F78;
                mid = n ? 0xFF0B1525 : 0xFF173150;
                break;
            case OVERCAST:
                top = n ? 0xFF121B28 : 0xFF2B394C;
                mid = n ? 0xFF0F1928 : 0xFF19263A;
                break;
            case FOG:
                top = n ? 0xFF1A2431 : 0xFF36424F;
                mid = n ? 0xFF141E2C : 0xFF212D3B;
                break;
            case DRIZZLE: case RAIN:
                top = n ? 0xFF0E1623 : 0xFF27364A;
                mid = n ? 0xFF0D1725 : 0xFF172537;
                break;
            case SNOW:
                top = n ? 0xFF161F2D : 0xFF35455B;
                mid = n ? 0xFF111B2A : 0xFF1D2C41;
                break;
            case SHOWERS:
                top = n ? 0xFF0E1623 : 0xFF2A4565;
                mid = n ? 0xFF0D1725 : 0xFF182C45;
                break;
            case STORM:
                top = n ? 0xFF0A0F18 : 0xFF1A2130;
                mid = n ? 0xFF0B121D : 0xFF131B29;
                break;
            default:
                top = mid = BG;
        }
        skyC = new int[]{sf(top), sf(mid), BG};
        int spot = kind == CLEAR && day ? 0xFF172D4F : SPOT;
        glowC = new int[]{sf(spot), sf(spot), sf(spot) & 0x00FFFFFF};

        // the sun / moon
        if (orb == ORB_SUN) {
            discC = new int[]{sf(0xFFFBE4A8), sf(0xFFF3C46A), sf(0xFFE7AC50), sf(0x00E7AC50)};
            haloC = hot ? new int[]{sf(0x78F7AE58), sf(0x2CF5A052), 0x00F5A052}
                    : new int[]{sf(0x50F6C66E), sf(0x1CF6C66E), 0x00F6C66E};
        } else if (orb == ORB_MOON) {
            discC = new int[]{sf(0xFFE6ECF4), sf(0xFFCDD6E3), sf(0xFFB7C2D2), sf(0x00B7C2D2)};
            haloC = new int[]{sf(0x2EC4D3EA), sf(0x10C4D3EA), 0x00C4D3EA};
        } else if (orb == ORB_PALE) {
            discC = new int[]{sf(0x66EDE7D8), sf(0x52E4DED0), sf(0x30E4DED0), 0x00E4DED0};
            haloC = new int[]{sf(0x1CE8E2D4), sf(0x0AE8E2D4), 0x00E8E2D4};
        }

        // clouds: [back, front] body and lit top
        int b0, h0, b1, h1;
        switch (kind) {
            case CLOUDS:
                if (n) { b0 = 0xFF16223A; h0 = 0xFF223250; b1 = 0xFF1C2A43; h1 = 0xFF2D405F; }
                else { b0 = 0xFF3A587A; h0 = 0xFF4B6C90; b1 = 0xFF4C6989; h1 = 0xFF6282A4; }
                break;
            case OVERCAST:
                if (n) { b0 = 0xFF161F2C; h0 = 0xFF1C2635; b1 = 0xFF1B2533; h1 = 0xFF232F40; }
                else { b0 = 0xFF2A3749; h0 = 0xFF34435A; b1 = 0xFF36455A; h1 = 0xFF45566E; }
                break;
            case SNOW:
                if (n) { b0 = 0xFF1A2333; h0 = 0xFF222D3F; b1 = 0xFF212C3D; h1 = 0xFF2B384C; }
                else { b0 = 0xFF33435A; h0 = 0xFF41536C; b1 = 0xFF41526A; h1 = 0xFF52657F; }
                break;
            case SHOWERS:
                if (n) { b0 = 0xFF131B27; h0 = 0xFF18212F; b1 = 0xFF18212E; h1 = 0xFF1F2A3A; }
                else { b0 = 0xFF2D4560; h0 = 0xFF3A5677; b1 = 0xFF3B5470; h1 = 0xFF4C6888; }
                break;
            case STORM:
                if (n) { b0 = 0xFF10151E; h0 = 0xFF151B26; b1 = 0xFF141A24; h1 = 0xFF1A212D; }
                else { b0 = 0xFF191F2B; h0 = 0xFF202838; b1 = 0xFF1F2634; h1 = 0xFF283143; }
                break;
            default: // drizzle, rain
                if (n) { b0 = 0xFF131B27; h0 = 0xFF18212F; b1 = 0xFF18212E; h1 = 0xFF1F2A3A; }
                else { b0 = 0xFF243042; h0 = 0xFF2D3A4E; b1 = 0xFF2E3B4F; h1 = 0xFF3A4960; }
        }
        clBody[0] = sf(b0); clHi[0] = sf(h0); clBody[1] = sf(b1); clHi[1] = sf(h1);

        int mist = kind == FOG ? (n ? 0xFF7C899B : 0xFFAEBBCA) : (n ? 0xFF56677F : 0xFF8597AE);
        int ma = kind == FOG ? 0x3C : n ? 0x38 : 0x44;
        mistC = new int[]{sf(ma << 24 | mist & 0xFFFFFF), sf((ma / 2) << 24 | mist & 0xFFFFFF), mist & 0xFFFFFF};
        int hz = nHeat > 0 ? 0xFFF0A050 : mist, ha = nHeat > 0 ? 0x18 : kind == FOG ? 0x34 : 0x1C;
        hazeC = new int[]{hz & 0xFFFFFF, sf(ha << 24 | hz & 0xFFFFFF)};
        rainC = sf(drizzle ? (n ? 0x58A0B4CE : 0x78A9C2DE) : (n ? 0x60A0B4CE : 0x80A9C2DE));
        snowC = sf(n ? 0xC0CBD6E6 : 0xF0EEF3FA);
        driftC = sf(n ? 0xFF3B4759 : 0xFF7D8DA3);
        driftHi = sf(n ? 0xFF4A586C : 0xFF9DABBE);
        flashC = new int[]{sf(0x8CCDD5F5), sf(0x36A9B6E8), 0x00A9B6E8};
        riseC = new int[]{sf(0x6CF5A86E), sf(0x38EE9278), sf(0x14D98AA8), 0x00D98AA8};
        lightC = occ == O_NEWYEAR ? new int[]{sf(0x34FFF0D6), sf(0x12FFF0D6), 0x00FFF0D6}
                : new int[]{sf(0x70FFD9A0), sf(0x24FFD9A0), 0x00FFD9A0};
        starC = new int[]{sf(0x48F6D98A), sf(0x16F6D98A), 0x00F6D98A};
    }

    // ================================================================ every frame
    void step(float dt) {
        if (!(dt > 0f)) return;
        if (dt > 0.25f) dt = 0.25f;
        t += dt;
        if (w <= 0 || h <= 0) return;

        for (int i = 0; i < nClouds; i++) {
            clX[i] += clV[i] * dt;
            float half = clS[i] * 1.2f;
            if (clX[i] - half > w) clX[i] = -half - rnd(0f, 0.12f) * w;
        }
        for (int i = 0; i < nMist; i++) {
            mX[i] += mV[i] * dt;
            if (mV[i] > 0 && mX[i] - mistR > w) mX[i] = -mistR;
            else if (mV[i] < 0 && mX[i] + mistR < 0) mX[i] = w + mistR;
        }

        if (bursts) {
            burstTimer -= dt;
            if (burstTimer <= 0f) {
                boolean on = burstTarget < 0.5f;
                burstTarget = on ? 1f : 0.12f;
                burstTimer = on ? rnd(7f, 12f) : rnd(5f, 9f);
            }
            burst += (burstTarget - burst) * Math.min(1f, dt * 0.6f);
        }

        int active = bursts ? Math.round(nDrops * burst) : nDrops;
        for (int i = 0; i < nDrops; i++) {
            if (!rOn[i]) {
                if (i < active) drop(i, false);
                continue;
            }
            rY[i] += rV[i] * dt;
            rX[i] -= SLANT * rV[i] * dt;
            if (rY[i] >= rG[i]) {
                if (splashes) splash(rX[i], rG[i]);
                if (i < active) drop(i, false); else rOn[i] = false;
            }
        }
        for (int i = 0; i < MAX_SPLASH; i++) if (spT[i] >= 0f && (spT[i] += dt) > SPLASH_LIFE) spT[i] = -1f;

        int flakes = bursts ? Math.round(nFlakes * burst) : nFlakes;
        for (int i = 0; i < nFlakes; i++) {
            if (!snOn[i]) {
                if (i < flakes) flake(i, false);
                continue;
            }
            snY[i] += snV[i] * dt;
            snX[i] -= 3f * u * dt;
            if (snX[i] < -0.05f * w) snX[i] += 1.1f * w;
            if (snY[i] - snR[i] > h) {
                if (i < flakes) flake(i, false); else snOn[i] = false;
            }
        }

        if (storm) { // (the next glow is counted from this one's start: 10-20 s apart)
            nextFlash -= dt;
            if (flashT >= 0f && (flashT += dt) > 1.8f) flashT = -1f;
            if (flashT < 0f && nextFlash <= 0f) startFlash();
        }

        for (int i = 0; i < nHeat; i++) if ((hT[i] += dt) > hL[i]) heatLine(i);

        if (occ == O_NEWYEAR) {
            for (int i = 0; i < MAX_FW; i++) if (fwT[i] >= 0f && (fwT[i] += dt) > FW_LIFE) fwT[i] = -1f;
            if ((fwNext -= dt) <= 0f) {
                spawnBurst();
                fwNext = rnd(1.8f, 3.2f);
            }
        } else if (occ == O_BIRTHDAY) {
            for (int i = 0; i < BALLOONS; i++) {
                bY[i] -= bV[i] * dt;
                if (bY[i] + 190f * u < 0f) {
                    bY[i] = h + rnd(40f, 200f) * u;
                    bC[i] = BALLOON_COLS[(int) (rnd() * BALLOON_COLS.length) % BALLOON_COLS.length];
                }
            }
        }

        if (birds || birdA > 0f) stepBirds(dt);
    }

    /** Birds fly by day under a clear, cloudy or grey sky, and not on the dimmed (night) screen. */
    private boolean birdSky() {
        return day && !dim && (kind == CLEAR || kind == CLOUDS || kind == OVERCAST) && w > 0 && h > 0;
    }

    private void stepBirds(float dt) {
        if (!birdSky()) { birdA = 0f; return; } // (they fade in again when the sky has birds again)
        float to = birds ? 1f : 0f, r = dt * 0.4f;
        birdA = birdA < to ? Math.min(to, birdA + r) : Math.max(to, birdA - r);
        if (birdA <= 0f) return;
        float edge = 30f * u;
        for (int f = 0; f < FLOCKS; f++) {
            if (flockWait[f] > 0f) {
                if ((flockWait[f] -= dt) <= 0f) launchFlock(f, false);
                continue;
            }
            float d = flockDir[f];
            flockX[f] += d * flockV[f] * dt;
            flockWave[f] = wrap(flockWave[f] + flockWaveF[f] * dt);
            float last = flockX[f] - d * flockReach[f];
            if (d > 0f ? last - edge > w : last + edge < 0f) flockWait[f] = brnd(3f, 8f); // all across: a pause
        }
        for (int i = 0; i < MAX_BIRDS; i++) {
            birdPh[i] = wrap(birdPh[i] + TAU * birdHz[i] * dt);
            birdBob[i] = wrap(birdBob[i] + birdBobF[i] * dt);
            if ((birdGlide[i] -= dt) <= 0f) { // flap a few seconds, glide a little, flap again
                birdFlaps[i] = !birdFlaps[i];
                birdGlide[i] = birdFlaps[i] ? brnd(3f, 7f) : brnd(1.5f, 3.5f);
            }
            float aim = birdFlaps[i] ? 1f : 0.14f, ease = Math.min(1f, dt * 1.6f);
            birdAmp[i] += (aim - birdAmp[i]) * ease;
        }
    }

    private void splash(float x, float y) {
        if (x < 0 || x > w) return;
        for (int i = 0; i < MAX_SPLASH; i++) {
            if (spT[i] < 0f) {
                spX[i] = x;
                spY[i] = y;
                spT[i] = 0f;
                return;
            }
        }
    }

    private void startFlash() {
        flashT = 0f;
        nextFlash = rnd(10f, 20f);
        flashX = (rnd() < 0.5f ? rnd(0.1f, 0.3f) : rnd(0.7f, 0.9f)) * w;
        flashY = rnd(0.1f, 0.17f) * h;
    }

    /** The lightning glow's strength (0..1): a soft rise, a fall, a smaller second glow, then gone. */
    private float flashLevel() {
        float f = flashT;
        if (f < 0f) return 0f;
        if (f < 0.12f) return f / 0.12f;
        if (f < 0.45f) return 1f - (f - 0.12f) / 0.33f * 0.75f;
        if (f < 0.6f) return 0.25f + (f - 0.45f) / 0.15f * 0.3f;
        if (f < 1.8f) return 0.55f * (1f - (f - 0.6f) / 1.2f);
        return 0f;
    }

    private void spawnBurst() {
        int j = -1, open = 0;
        for (int i = 0; i < MAX_FW; i++) {
            if (fwT[i] < 0f) { if (j < 0) j = i; } else open++;
        }
        if (j < 0 || open >= 3) return;
        float x = 0, y = 0, r = 0;
        for (int tries = 0; tries < 10; tries++) {
            float k = rnd();
            if (k < 0.38f) { x = rnd(0.08f, 0.22f) * w; y = rnd(0.1f, 0.42f) * h; r = rnd(55f, 80f); }
            else if (k < 0.76f) { x = rnd(0.78f, 0.92f) * w; y = rnd(0.1f, 0.42f) * h; r = rnd(55f, 80f); }
            else { x = rnd(0.3f, 0.7f) * w; y = 0.075f * h; r = rnd(45f, 55f); }
            if (orb != ORB_NONE && near(x, y, oX, oY, haloR + r * u)) continue;
            break;
        }
        fwX[j] = x;
        fwY[j] = y;
        fwR[j] = r * u;
        fwRot[j] = rnd(0f, 6.283f);
        fwC[j] = FW_COLS[(int) (rnd() * FW_COLS.length) % FW_COLS.length];
        fwT[j] = 0f;
    }

    // ================================================================ drawing
    void draw(Brush b) {
        if (w <= 0 || h <= 0) return;
        b.rect(0, 0, w, h, BG);
        if (kind != NONE) b.linear(S_SKY, 0, 0, w, h * 0.62f, 0, 0, 0, h * 0.62f, skyC, SKY_AT, 1f);
        drawStars(b);
        drawOrb(b);
        b.radial(S_GLOW, gx, gy, gr * 1.1f, gr * 1.1f, glowC, GLOW_AT, 1f);
        if (occ == O_EASTER)
            b.radial(S_RISE, w * 0.5f, h * 1.05f, w * 0.85f, h * 0.52f, riseC, RISE_AT,
                    (day ? 0.9f : 0.6f) + 0.1f * (float) Math.sin(t * 0.2f));
        if (haze) b.linear(S_HAZE, 0, h * 0.6f, w, h, 0, h * 0.6f, 0, h, hazeC, HAZE_AT, 1f);
        float fl = flashLevel() * (day ? 1f : 0.8f);
        for (int layer = 0; layer < 2; layer++)
            for (int i = 0; i < nClouds; i++) if (clLayer[i] == layer) drawCloud(b, i, fl);
        if (fl > 0f) b.radial(S_FLASH, flashX, flashY, gr * 1.0f, gr * 0.8f, flashC, MIST_AT, fl * (dim ? 0.5f : 0.9f));
        if (birdA > 0f && birdSky()) drawBirds(b); // (in front of the clouds, behind the mist and everything after)
        for (int i = 0; i < nMist; i++)
            b.radial(S_MIST, mX[i], mY[i], mistR, mRy[i], mistC, MIST_AT, mA[i]);

        switch (occ) {
            case O_CHRISTMAS: drawStar(b); break;
            case O_NEWYEAR: drawNewYear(b); break;
            case O_SANKRANTI: for (int i = 0; i < KITES; i++) drawKite(b, i); break;
            case O_BIRTHDAY: for (int i = 0; i < BALLOONS; i++) drawBalloon(b, i); break;
            default: break;
        }

        drawRain(b);
        drawSnow(b);
        if (drifts) drawDrifts(b);
        drawHeat(b);

        switch (occ) {
            case O_CHRISTMAS: drawLights(b); break;
            case O_EASTER: for (int i = 0; i < nFlowers; i++) drawFlower(b, i); break;
            case O_BIRTHDAY: drawBunting(b); break;
            default: break;
        }
    }

    private void drawStars(Brush b) {
        for (int i = 0; i < nStars; i++) {
            float tw = 0.68f + 0.32f * (float) Math.sin(t * stF[i] + stP[i]);
            float a = stA[i] * tw * (dim ? 0.6f : 1f);
            b.circle(stX[i], stY[i], stR[i], fade(0xFFDCE6F5, a));
            if (stR[i] > 2.5f * u) b.circle(stX[i], stY[i], stR[i] * 2.8f, fade(0xFFDCE6F5, a * 0.12f));
        }
    }

    private void drawOrb(Brush b) {
        if (orb == ORB_NONE) return;
        b.radial(S_HALO, oX, oY, haloR, haloR, haloC, HALO_AT, 1f);
        if (rays) { // soft tapering rays, turning once in about two minutes
            int rc = sf(hot ? 0x30FFBE5A : 0x2CFFCC6A), core = sf(hot ? 0x38FFC864 : 0x34FFD47A);
            float turn = t * 0.05f, half = 0.11f;
            for (int k = 0; k < 12; k++) {
                double a = turn + k * Math.PI / 6;
                float r0 = oR * 1.18f, r1 = oR * (k % 2 == 0 ? 2.45f : 1.95f);
                float c0 = (float) Math.cos(a - half), s0 = (float) Math.sin(a - half);
                float c1 = (float) Math.cos(a + half), s1 = (float) Math.sin(a + half);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float x0 = oX + c0 * r0, y0 = oY + s0 * r0, x1 = oX + c1 * r0, y1 = oY + s1 * r0;
                b.quad(x0, y0, oX + ca * r1, oY + sa * r1, x1, y1, x1, y1, rc);
                float rm = r0 + (r1 - r0) * 0.55f; // brighter near the sun: the ray fades outwards
                b.quad(x0, y0, oX + ca * rm, oY + sa * rm, x1, y1, x1, y1, core);
            }
        }
        b.radial(S_DISC, oX, oY, oR, oR, discC, DISC_AT, 1f);
        if (orb == ORB_MOON) {
            int cc = sf(0x1E5A6E8A);
            b.circle(oX - oR * 0.3f, oY - oR * 0.18f, oR * 0.24f, cc);
            b.circle(oX + oR * 0.28f, oY + oR * 0.16f, oR * 0.17f, cc);
            b.circle(oX - oR * 0.04f, oY + oR * 0.45f, oR * 0.12f, cc);
        }
    }

    private void drawCloud(Brush b, int i, float fl) {
        float[] p = SHAPES[clShape[i]];
        float s = clS[i], x = clX[i], y = clY[i], d = s * 0.055f;
        int layer = clLayer[i];
        int body = clBody[layer], hi = clHi[layer];
        if (fl > 0f) {
            float near = 1f - Math.min(1f, Math.abs(x - flashX) / (w * 0.6f));
            float k = fl * (0.18f + 0.22f * near);
            body = mix(body, 0xFF56627E, k);
            hi = mix(hi, 0xFF707D9C, k);
        }
        // the lit top first (shifted up), then the body over it: a soft rim on each puff
        b.oval(x + p[0] * s, y + p[1] * s - d, p[2] * s, p[3] * s, hi);
        for (int k = 4; k + 2 < p.length; k += 3) b.circle(x + p[k] * s, y + p[k + 1] * s - d, p[k + 2] * s, hi);
        b.oval(x + p[0] * s, y + p[1] * s, p[2] * s, p[3] * s, body);
        for (int k = 4; k + 2 < p.length; k += 3) b.circle(x + p[k] * s, y + p[k + 1] * s, p[k + 2] * s, body);
    }

    private void drawRain(Brush b) {
        if (nDrops == 0) return;
        float thin = (drizzle ? 1.4f : 1.8f) * u, thick = 2.3f * u;
        for (int i = 0; i < nDrops; i++) {
            if (!rOn[i]) continue;
            float x = rX[i], y = rY[i], l = rL[i];
            if (y < -l || y - l > h) continue;
            float tx = x + SLANT * l, ty = y - l;
            int c = fade(rainC, calm(x, y - l * 0.5f));
            b.line(tx, ty, x, y, l > 0.036f * h ? thick : thin, c);
        }
        for (int i = 0; i < MAX_SPLASH; i++) {
            if (spT[i] < 0f) continue;
            float p = spT[i] / SPLASH_LIFE, q = 1f - p;
            float rx = (4f + 15f * p) * u;
            b.ring(spX[i], spY[i], rx, rx * 0.3f, 1.6f * u, fade(rainC, q * 0.9f));
            float up = (34f * p - 38f * p * p) * u, side = (5f + 12f * p) * u;
            int dc = fade(rainC, q);
            b.circle(spX[i] - side, spY[i] - up, 1.7f * u, dc);
            b.circle(spX[i] + side * 0.8f, spY[i] - up * 0.8f, 1.5f * u, dc);
        }
    }

    private void drawSnow(Brush b) {
        for (int i = 0; i < nFlakes; i++) {
            if (!snOn[i]) continue;
            float x = snX[i] + (float) Math.sin(t * snF[i] + snP[i]) * snW[i], y = snY[i], r = snR[i];
            if (y < -r * 2 || y - r > h) continue;
            float a = snA[i] * calm(x, y);
            if (r > 2.8f * u) b.circle(x, y, r * 2f, fade(snowC, a * 0.15f));
            b.circle(x, y, r, fade(snowC, a));
        }
    }

    /** Soft snow banks in the bottom corners (beside Jarvis's shoulders). */
    private void drawDrifts(Brush b) {
        float lift = 4f * u;
        for (int pass = 0; pass < 2; pass++) {
            int c = pass == 0 ? driftHi : driftC;
            float up = pass == 0 ? lift : 0f;
            b.oval(w * 0.05f, h * 1.01f - up, w * 0.15f, h * 0.045f, c);
            b.oval(w * 0.17f, h * 1.02f - up, w * 0.1f, h * 0.03f, c);
            b.oval(w * 0.95f, h * 1.012f - up, w * 0.14f, h * 0.042f, c);
            b.oval(w * 0.83f, h * 1.022f - up, w * 0.09f, h * 0.028f, c);
        }
    }

    /** Heat: a few faint wavy lines rising from the bottom at the sides (three side by side, like warm air). */
    private void drawHeat(Brush b) {
        int seg = 14;
        for (int i = 0; i < nHeat; i++) {
            float p = hT[i] / hL[i];
            float a = (float) Math.sin(Math.PI * p) * 0.2f;
            if (a <= 0.01f) continue;
            float len = 80f * u, bottom = hY[i] - p * 0.12f * h;
            for (int line = 0; line < 3; line++) {
                float x0 = hX[i] + line * 20f * u;
                for (int k = 0; k <= seg; k++) {
                    float y = bottom - len * k / seg;
                    pts[2 * k] = x0 + (float) Math.sin(y / (13f * u) + t * 1.4f + hP[i] + line * 0.8f) * 4f * u;
                    pts[2 * k + 1] = y;
                }
                b.polyline(pts, seg + 1, 2.6f * u, sf(fade(0xFFFFCF94, a * (line == 1 ? 1f : 0.75f))));
            }
        }
    }

    /** The morning birds (they fade out as they pass the sun, as if behind its glare). */
    private void drawBirds(Brush b) {
        float lo = 0.075f * h, hi = 0.295f * h;
        for (int i = 0; i < MAX_BIRDS; i++) {
            int f = i < FLOCK_FIRST[1] ? 0 : 1;
            if (flockWait[f] > 0f) continue;
            float d = flockDir[f], half = birdSpan[i] * 0.5f * u;
            float flap = (float) Math.sin(birdPh[i]) * birdAmp[i];
            float x = flockX[f] - d * birdDx[i] * u + (float) Math.cos(birdBob[i]) * 3f * u;
            if (x + half < 0f || x - half > w) continue;
            float y = flockY[f] + (float) Math.sin(flockWave[f]) * 0.012f * h + birdDy[i] * u
                    + (float) Math.sin(birdBob[i]) * 4f * u + flap * 1.2f * u;
            if (y < lo) y = lo; else if (y > hi) y = hi;
            float a = birdA;
            if (orb != ORB_NONE) {
                float dx = x - oX, dy = y - oY, dd = (float) Math.sqrt(dx * dx + dy * dy);
                float r0 = oR * 1.05f + half * 0.6f, r1 = oR * 1.6f + half;
                if (dd < r1) {
                    float k = dd <= r0 ? 0f : (dd - r0) / (r1 - r0);
                    a *= k * k * (3f - 2f * k);
                }
            }
            if (a < 0.02f) continue;
            drawBird(b, x, y, d, half, flap, fade(BIRD_COLS[birdCol[i]], a));
        }
    }

    /**
     * One small bird seen from below: two curved wings from tip to tip (raised, level with the elbows up, or arched
     * down as they beat) and a little body with its head towards d (+1 right, -1 left) and a short tail.
     */
    private void drawBird(Brush b, float x, float y, float d, float half, float flap, int c) {
        float k = half / (17.5f * u); // 1 at a 35-unit wingspan
        float tipA = flap * (flap > 0f ? 0.58f : 0.38f), elbowA = 0.5f * tipA + 0.38f; // radians above level
        float sx = x + d * 1f * u * k, sy = y - 0.6f * u * k;   // the shoulders
        float sweep = -d * 0.14f * half;                         // the tips trail a little
        float ct = (float) Math.cos(tipA), st = (float) Math.sin(tipA);
        float ce = (float) Math.cos(elbowA), se = (float) Math.sin(elbowA);
        for (int s = -1; s <= 1; s += 2) {
            // a curve from the shoulder S through the elbow E (at its middle) to the tip T: control = 2E - (S + T) / 2
            float tx = sx + s * half * ct + sweep, ty = sy - half * st;
            float ex = sx + s * 0.5f * half * ce + sweep * 0.3f, ey = sy - 0.5f * half * se;
            float cx = 2f * ex - 0.5f * (sx + tx), cy = 2f * ey - 0.5f * (sy + ty);
            for (int j = s < 0 ? 0 : 1; j <= 4; j++) {
                float q = s < 0 ? 1f - j * 0.25f : j * 0.25f, p = 1f - q;
                int at = s < 0 ? j : 4 + j;
                wingPts[2 * at] = p * p * sx + 2f * q * p * cx + q * q * tx;
                wingPts[2 * at + 1] = p * p * sy + 2f * q * p * cy + q * q * ty;
            }
        }
        for (int j = 0; j < 10; j++) wingIn[j] = wingPts[4 + j]; // points 2..6: elbow to elbow
        b.polyline(wingPts, 9, 2.5f * u * k, c);
        b.polyline(wingIn, 5, 3.9f * u * k, c);
        b.oval(x, y, 4.4f * u * k, 2.1f * u * k, c);
        b.circle(x + d * 5.2f * u * k, y - 0.5f * u * k, 1.9f * u * k, c);
        b.quad(x - d * 2.6f * u * k, y - 0.9f * u * k, x - d * 7.6f * u * k, y - 1.7f * u * k,
                x - d * 7.2f * u * k, y + 1.8f * u * k, x - d * 2.6f * u * k, y + 1.2f * u * k, c);
    }

    // ---- occasions
    /** A string hung across the top in `spans` scallops (top: the hooks' height, sag: how far it hangs). */
    private float hangY(float x, float top, float sag, int spans) {
        float span = w * 1.04f / spans;
        float f = (x + w * 0.02f) / span;
        f -= (float) Math.floor(f);
        return top + sag * (float) Math.sin(Math.PI * f);
    }

    private void drawLights(Brush b) {
        float top = h * 0.008f, sag = h * 0.05f;
        int n = 56;
        for (int k = 0; k < n; k++) {
            float x = -0.02f * w + 1.04f * w * k / (n - 1);
            pts[2 * k] = x;
            pts[2 * k + 1] = hangY(x, top, sag, 4);
        }
        b.polyline(pts, n, 1.8f * u, sf(0xFF2E4A3A));
        for (int i = 0; i < nLights; i++) {
            float x = liX[i], wy = hangY(x, top, sag, 4), y = wy + 9f * u;
            float a = 0.72f + 0.28f * (float) Math.sin(t * liF[i] + liP[i]);
            b.line(x, wy, x, y - 3f * u, 2.6f * u, sf(0xFF3A4C40));
            b.radial(S_LIGHT, x, y, 20f * u, 20f * u, lightC, LIGHT_AT, a);
            b.oval(x, y, 3.9f * u, 5.2f * u, sf(mix(liC[i], BG, (1f - a) * 0.6f)));
            b.circle(x, y - 1f * u, 1.5f * u, sf(fade(0xFFFFF6E0, a * 0.85f)));
        }
    }

    private void drawStar(Brush b) {
        float pulse = 0.82f + 0.18f * (float) Math.sin(t * 0.7f);
        float x = starX, y = starY + (float) Math.sin(t * 0.4f) * 2f * u;
        b.radial(S_STAR, x, y, 80f * u, 80f * u, starC, LIGHT_AT, pulse);
        float ro = 25f * u, ri = 10.5f * u;
        int c = sf(mix(0xFFF2D27E, BG, (1f - pulse) * 0.5f));
        for (int k = 0; k < 5; k++) {
            double a = -Math.PI / 2 + k * 2 * Math.PI / 5, a1 = a - Math.PI / 5, a2 = a + Math.PI / 5;
            b.quad(x, y, x + (float) Math.cos(a1) * ri, y + (float) Math.sin(a1) * ri,
                    x + (float) Math.cos(a) * ro, y + (float) Math.sin(a) * ro,
                    x + (float) Math.cos(a2) * ri, y + (float) Math.sin(a2) * ri, c);
        }
        b.circle(x, y, 4.5f * u, sf(fade(0xFFFFF2CC, pulse)));
    }

    private void drawNewYear(Brush b) {
        for (int i = 0; i < nSparks; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * skF[i] + skP[i]);
            float len = (4f + 6f * tw) * u;
            int c = sf(fade(0xFFF6E3B0, 0.25f + 0.55f * tw));
            b.line(skX[i] - len, skY[i], skX[i] + len, skY[i], 1.6f * u, c);
            b.line(skX[i], skY[i] - len, skX[i], skY[i] + len, 1.6f * u, c);
            b.circle(skX[i], skY[i], 1.8f * u, c);
        }
        for (int j = 0; j < MAX_FW; j++) {
            if (fwT[j] < 0f) continue;
            float p = fwT[j] / FW_LIFE, q = 1f - p;
            float ease = 1f - q * q * q;
            float rho = fwR[j] * ease, droop = 22f * u * p * p;
            // a soft opening, bright while it spreads, then a slow fade (never a flash)
            float a = Math.min(1f, p / 0.1f) * (p < 0.45f ? 1f : (float) Math.pow((1f - p) / 0.55f, 1.3f)) * (dim ? 0.6f : 1f);
            b.radial(S_LIGHT, fwX[j], fwY[j] + droop * 0.5f, 100f * u, 100f * u, lightC, LIGHT_AT, a * (0.4f + 0.6f * ease));
            int dot = sf(fade(fwC[j], a)), trail = sf(fade(fwC[j], a * 0.42f));
            float dr = (3.4f - 1.4f * p) * u;
            for (int k = 0; k < FW_PTS; k++) {
                double ang = fwRot[j] + k * 2 * Math.PI / FW_PTS;
                float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                float x = fwX[j] + c * rho, y = fwY[j] + s * rho + droop;
                b.line(fwX[j] + c * rho * 0.6f, fwY[j] + s * rho * 0.6f + droop * 0.6f, x, y, 2f * u, trail);
                b.circle(x, y, dr, dot);
            }
        }
    }

    private void drawKite(Brush b, int i) {
        float S = kS[i];
        float x = kX[i] + (float) Math.sin(t * 0.45f + kP[i]) * 10f * u;
        float y = kY[i] + (float) Math.sin(t * 0.6f + kP[i] * 1.3f) * 7f * u;
        float ang = (float) Math.sin(t * 0.7f + kP[i]) * 0.1f + (x < w * 0.5f ? 0.12f : -0.12f);
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        // the string, behind: from the kite down to where Jarvis stands
        float ax = x + (-s) * 0.1f * S, ay = y + c * 0.1f * S;
        float ex = w * (0.5f + (i - 1) * 0.03f), ey = h * 0.8f;
        float cx = (ax + ex) * 0.5f + (ax - ex) * 0.12f, cy = Math.max(ay, ey) * 0.5f + (ay + ey) * 0.25f + 0.05f * h;
        int n = 22;
        for (int k = 0; k < n; k++) {
            float f = k / (float) (n - 1), g = 1f - f;
            pts[2 * k] = g * g * ax + 2 * g * f * cx + f * f * ex;
            pts[2 * k + 1] = g * g * ay + 2 * g * f * cy + f * f * ey;
        }
        b.polyline(pts, n, 1.3f * u, sf(mix(0xFFE8E0CC, BG, 0.5f)));
        // the kite: a diamond in two colours, a spine, a bow and a little tail
        float tx = x + s * S, ty = y - c * S;              // top    (0, -S)
        float bx = x - s * S, by = y + c * S;              // bottom (0,  S)
        float rx = x + c * 0.9f * S, ry = y + s * 0.9f * S;  // right  (0.9S, 0)
        float lx = x - c * 0.9f * S, ly = y - s * 0.9f * S;  // left
        int c1 = oc(KITE_A[i]), c2 = oc(KITE_B[i]), dk = oc(mix(KITE_A[i], 0xFF000000, 0.5f));
        b.quad(tx, ty, lx, ly, bx, by, bx, by, c1);
        b.quad(tx, ty, rx, ry, bx, by, bx, by, c2);
        b.line(tx, ty, bx, by, 1.6f * u, dk);
        float mx = x + s * 0.32f * S, my = y - c * 0.32f * S; // the bow bends up through (0, -0.32S)
        pts[0] = lx; pts[1] = ly; pts[2] = mx; pts[3] = my; pts[4] = rx; pts[5] = ry;
        b.polyline(pts, 3, 1.4f * u, dk);
        float t1x = x - c * 0.24f * S - s * 1.34f * S, t1y = y - s * 0.24f * S + c * 1.34f * S;
        float t2x = x + c * 0.24f * S - s * 1.34f * S, t2y = y + s * 0.24f * S + c * 1.34f * S;
        b.quad(bx, by, t1x, t1y, t2x, t2y, t2x, t2y, c1);
    }

    private void drawFlower(Brush b, int i) {
        float H = fH[i], baseX = fX[i], baseY = h + 4f * u;
        float sway = (float) Math.sin(t * 0.55f + fP[i]) * 4f * u * (H / (150f * u));
        float hx = baseX + sway, hy = baseY - H;
        int stem = oc(0xFF4C8A5A), leaf = oc(0xFF3F7A4E);
        for (int k = -1; k <= 1; k++) { // a little grass at its foot
            float gx0 = baseX + k * 9f * u, gh = (22f + 9f * ((i + k + 2) % 3)) * u;
            b.line(gx0, baseY, gx0 + k * 6f * u + sway * 0.2f, baseY - gh, 2.4f * u, leaf);
        }
        pts[0] = baseX; pts[1] = baseY;
        pts[2] = baseX + sway * 0.3f; pts[3] = baseY - H * 0.5f;
        pts[4] = hx; pts[5] = hy;
        b.polyline(pts, 3, 3f * u, stem);
        float dir = i % 2 == 0 ? 1f : -1f;
        leaf(b, baseX + sway * 0.1f, baseY - H * 0.28f, dir, leaf);
        leaf(b, baseX + sway * 0.2f, baseY - H * 0.5f, -dir, leaf);
        float pr = fR[i];
        int pc = oc(fC[i]), pd = oc(mix(fC[i], BG, 0.3f));
        for (int k = 0; k < fN[i]; k++) {
            double a = fP[i] + k * 2 * Math.PI / fN[i];
            float px = hx + (float) Math.cos(a) * pr * 1.05f, py = hy + (float) Math.sin(a) * pr * 1.05f;
            b.circle(px, py + 1.2f * u, pr, pd);
            b.circle(px, py, pr * 0.92f, pc);
        }
        b.circle(hx, hy, pr * 0.72f, oc(0xFFEFB94E));
        b.circle(hx - pr * 0.18f, hy - pr * 0.2f, pr * 0.28f, oc(0xFFF7D98A));
    }

    private void leaf(Brush b, float x, float y, float dir, int c) {
        float tx = x + dir * 24f * u, ty = y - 14f * u;
        float mx = (x + tx) * 0.5f, my = (y + ty) * 0.5f;
        float dx = tx - x, dy = ty - y, len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = -dy / len * 6f * u, ny = dx / len * 6f * u;
        b.quad(x, y, mx + nx, my + ny, tx, ty, mx - nx, my - ny, c);
    }

    private void drawBalloon(Brush b, int i) {
        float rx = 28f * u, ry = 34f * u;
        float x = bX[i] + (float) Math.sin(t * 0.5f + bP[i]) * 10f * u, y = bY[i];
        if (y - ry > h) return;
        int col = bC[i];
        // the string
        int n = 11;
        float y0 = y + ry + 7f * u;
        for (int k = 0; k < n; k++) {
            pts[2 * k] = x + (float) Math.sin(k * 0.9f + t * 1.4f + bP[i]) * 4f * u * (k / (float) n);
            pts[2 * k + 1] = y0 + k * 14f * u;
        }
        b.polyline(pts, n, 1.2f * u, sf(mix(0xFFC9D3E0, BG, 0.45f)));
        b.quad(x, y + ry - 2f * u, x - 5f * u, y + ry + 7f * u, x + 5f * u, y + ry + 7f * u, x + 5f * u, y + ry + 7f * u,
                oc(mix(col, 0xFF000000, 0.25f)));
        b.oval(x, y, rx, ry, oc(mix(col, BG, 0.35f)));
        b.oval(x - 2f * u, y - 3f * u, rx - 3f * u, ry - 3.5f * u, oc(mix(col, BG, 0.1f)));
        b.oval(x - rx * 0.36f, y - ry * 0.4f, rx * 0.2f, ry * 0.27f, sf(0x40FFFFFF));
    }

    private void drawBunting(Brush b) {
        float top = h * 0.006f, sag = h * 0.06f;
        int n = 56;
        for (int k = 0; k < n; k++) {
            float x = -0.02f * w + 1.04f * w * k / (n - 1);
            pts[2 * k] = x;
            pts[2 * k + 1] = hangY(x, top, sag, 2);
        }
        b.polyline(pts, n, 1.5f * u, sf(0xFF7D8AA0));
        float half = 15f * u, len = 32f * u;
        for (int i = 0; i < nFlags; i++) {
            float x = w * (i + 0.5f) / nFlags;
            float y1 = hangY(x - half, top, sag, 2), y2 = hangY(x + half, top, sag, 2);
            float swing = (float) Math.sin(t * 0.9f + i * 0.7f) * 2.5f * u;
            int c = oc(mix(FLAG_COLS[i % FLAG_COLS.length], BG, 0.15f));
            float tipX = x + swing, tipY = (y1 + y2) * 0.5f + len;
            b.quad(x - half, y1, x + half, y2, tipX, tipY, tipX, tipY, c);
        }
    }

    // ================================================================ helpers
    /** Weather over Jarvis's round light is fainter (the middle stays calm). */
    private float calm(float x, float y) {
        float dx = x - gx, dy = y - gy, d2 = dx * dx + dy * dy;
        float in = gr * 0.55f, out = gr * 1.05f;
        if (d2 >= out * out) return 1f;
        if (d2 <= in * in) return 0.35f;
        float f = ((float) Math.sqrt(d2) - in) / (out - in);
        return 0.35f + 0.65f * f * f * (3f - 2f * f);
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < r * r;
    }

    /** Softer when dim: towards the navy, and fainter if see-through. */
    private int sf(int c) {
        if (!dim) return c;
        int a = c >>> 24;
        int rgb = mix(c | 0xFF000000, BG, 0.38f) & 0xFFFFFF;
        return a == 255 ? 0xFF000000 | rgb : Math.round(a * 0.62f) << 24 | rgb;
    }

    /** An occasion's paint colour: a little quieter at night, softer when dim. */
    private int oc(int c) { return sf(day ? c : mix(c, BG, 0.2f)); }

    static int fade(int c, float f) {
        int a = Math.round((c >>> 24) * f);
        if (a < 0) a = 0; else if (a > 255) a = 255;
        return a << 24 | (c & 0xFFFFFF);
    }

    static int mix(int a, int b, float f) {
        if (f <= 0f) return a;
        if (f >= 1f) return b;
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16
                | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }

    private float rnd() {
        seed ^= seed << 13;
        seed ^= seed >>> 17;
        seed ^= seed << 5;
        return (seed >>> 8) / 16777216f;
    }

    private float rnd(float a, float b) { return a + (b - a) * rnd(); }

    /** The birds' own random numbers (the same xorshift on its own seed). */
    private float brnd() {
        birdSeed ^= birdSeed << 13;
        birdSeed ^= birdSeed >>> 17;
        birdSeed ^= birdSeed << 5;
        return (birdSeed >>> 8) / 16777216f;
    }

    private float brnd(float a, float b) { return a + (b - a) * brnd(); }

    /** A phase kept within one turn (phases add up per frame, so they stay exact however long the screen runs). */
    private static float wrap(float a) { return a >= TAU ? a - TAU : a; }
}
