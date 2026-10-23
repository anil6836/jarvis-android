package com.anil.jarvis;

/**
 * What the home tablet's Jarvis does on his own (BodyRig drives this; plain Java, no Android classes, so the desk test
 * draws it too):
 * <ul>
 * <li>little plays when nobody has talked to him for 40 s — by day a kitten with a yarn ball, a mouse from its hole, juggling,
 * a paper plane, soap bubbles, a butterfly on his nose, a yo-yo, a book, a cup of tea, watering a plant, a balloon,
 * peek-a-boo, a spinning top, a stretch, the pet kitten's visit; at night calm ones — a firefly, the kitten asleep on
 * his shoulder, a dream, counting stars, snoring under a blanket, humming. Each has its own faces; 30–90 s rest between
 * them, never one of the last two again;</li>
 * <li>on demand: a meal ("eat") and a birthday cake ("cake");</li>
 * <li>touch reactions (a tickle's giggle, a high-five), waving bye, glancing at the door, dancing to her songs;</li>
 * <li>the weather's outfit (umbrella, sweater, hand fan) and the day's occasion (a woolly cap, a party hat, a kite
 * string, a flower in his pocket).</li>
 * </ul>
 * The kitten and the mouse are this app's own simple shapes (a cream kitten with ginger ears and tail, a small grey
 * mouse), not anyone's cartoon. Everything is drawn in BodyRig's 680 × 720 picture through its Pen, before or after the
 * body; things held sit at his hands (BodyRig tells where they are each frame). Nothing here allocates per frame.
 */
final class IdlePlay {
    // the layers BodyRig draws us in: behind him, on his clothes, on his head (head units), held (behind the fingers), in front
    static final int BACK = 0, BODY = 1, HEAD = 2, MID = 3, FRONT = 4;
    // the reactions
    static final int TICKLE = 0, HIGH5 = 1, BYE = 2;
    private static final float[] MOMENT_LEN = {2.5f, 2f, 3f};

    // ================================================================ the plays
    static final String[] DAY = {"kitten", "mouse", "juggle", "plane", "bubbles", "butterfly", "yoyo", "book", "tea", "plant",
            "balloon", "peekaboo", "top", "stretch", "visit"};
    static final String[] NIGHT = {"firefly", "catnap", "dream", "stars", "snore", "hum"};
    static final String[] DEMAND = {"eat", "cake"};
    private static final int KITTEN = 0, MOUSE = 1, JUGGLE = 2, PLANE = 3, BUBBLES = 4, BUTTERFLY = 5, YOYO = 6, BOOK = 7,
            TEA = 8, PLANT = 9, BALLOON = 10, PEEK = 11, TOP = 12, STRETCH = 13, VISIT = 14,
            FIREFLY = 15, CATNAP = 16, DREAM = 17, STARS = 18, SNORE = 19, HUM = 20, EAT = 21, CAKE = 22;
    // how long each lasts (s)
    private static final float[] LEN = {24, 18, 18, 16, 18, 16, 15, 22, 18, 20, 16, 12, 16, 12, 26, 22, 30, 25, 24, 30, 20, 20, 20};
    static final float WAIT = 40, EXIT = 0.5f;
    private static final float FLOOR = 716, PI = (float) Math.PI;

    static String[] ids(boolean night) { return (night ? NIGHT : DAY).clone(); }

    static float length(String id) { int i = index(id); return i < 0 ? 0 : LEN[i]; }

    private static int index(String id) {
        if (id == null) return -1;
        for (int i = 0; i < DAY.length; i++) if (DAY[i].equals(id)) return i;
        for (int i = 0; i < NIGHT.length; i++) if (NIGHT[i].equals(id)) return DAY.length + i;
        for (int i = 0; i < DEMAND.length; i++) if (DEMAND[i].equals(id)) return DAY.length + NIGHT.length + i;
        return -1;
    }

    private static String name(int i) {
        return i < DAY.length ? DAY[i] : i < DAY.length + NIGHT.length ? NIGHT[i - DAY.length] : DEMAND[i - DAY.length - NIGHT.length];
    }

    private static boolean calm(int i) { return i >= FIREFLY && i <= HUM; }

    // ================================================================ the schedule
    private boolean on, night, forced;
    private float idle, sinceEnd = 1e6f, gap, pt, exit = -1, pendingAge, dt, T;
    private int cur = -1, pending = -1, last1 = -1, last2 = -1;
    private final java.util.Random rnd = new java.util.Random();

    void setOn(boolean b) { on = b; if (!b) end(); }
    void setNight(boolean n) { night = n; }
    /** Someone is here: the play ends gracefully, the wait starts again (a play asked for with play() still comes). */
    void poke() { idle = 0; end(); }
    /** He talks, a word, a feeling, a mode change: the same as a poke. */
    void activity() { idle = 0; end(); }
    /** Someone else gave him a face or a gesture: that wins over everything here. */
    void override() { idle = 0; end(); moment = -1; }
    void play(String id) { int i = index(id); if (i < 0) return; pending = i; pendingAge = 0; end(); }
    String playing() { return cur >= 0 ? name(cur) : null; }
    void seed(long s) { rnd.setSeed(s); }

    private void end() { if (cur >= 0 && exit < 0) exit = 0; }
    private void start(int i, boolean f) { cur = i; pt = 0; exit = -1; forced = f; }

    private void finish() {
        last2 = last1; last1 = cur;
        cur = -1; exit = -1; sinceEnd = 0;
        gap = 30 + rnd.nextFloat() * 60;
    }

    private int pick() {
        int from = night ? DAY.length : 0, n = night ? NIGHT.length : DAY.length, i;
        do i = from + rnd.nextInt(n); while (i == last1 || i == last2);
        return i;
    }

    // ================================================================ reactions, dance, door, outfit, occasion
    private int moment = -1, outfit, occasion, itemNow;
    private float mt, itemA;
    private boolean dance, door, wipe;
    private static final int UMBRELLA = 1, SWEATER = 2, FAN = 3;            // outfits
    private static final int CHRISTMAS = 1, NEWYEAR = 2, SANKRANTI = 3, EASTER = 4, BIRTHDAY = 5; // occasions
    private static final int I_UMBRELLA = 1, I_KITE = 2, I_FAN = 3;          // what his free hand holds

    void moment(int m) { idle = 0; end(); moment = m; mt = 0; }
    void setDance(boolean b) { dance = b; if (b) end(); }
    void setDoor(boolean b) { door = b; if (b) end(); }
    void setOutfit(String o) { outfit = "umbrella".equals(o) ? UMBRELLA : "sweater".equals(o) ? SWEATER : "fan".equals(o) ? FAN : 0; }
    void setOccasion(String o) {
        occasion = "christmas".equals(o) ? CHRISTMAS : "newyear".equals(o) ? NEWYEAR : "sankranti".equals(o) ? SANKRANTI
                : "easter".equals(o) ? EASTER : "birthday".equals(o) ? BIRTHDAY : 0;
    }
    boolean knit() { return outfit == SWEATER; }

    /** Something of ours moves (BodyView: ~30 frames a second). */
    boolean moving(int mode) { return cur >= 0 && !calm(cur) || moment >= 0 || dance && mode == BodyRig.IDLE; }

    private int held() { return outfit == UMBRELLA ? I_UMBRELLA : occasion == SANKRANTI ? I_KITE : outfit == FAN ? I_FAN : 0; }

    // ================================================================ what BodyRig reads each frame
    BodyRig.Goal face;          // the face now (null: his own)
    String gest;                // a named gesture (null: his own)
    boolean gaze;               // eyes on (gx, gy) (-1..1)
    float gx, gy, cross, armK = 1;
    boolean offsets;            // the shake / bob below are on
    float bodyDx, bodyDy, headDx, headDy, headTilt;
    private final boolean[] custom = new boolean[2];
    private final float[] ax = new float[2], ay = new float[2], aa = new float[2];
    private final String[] as = {"rest", "rest"};
    private final boolean[] ad = new boolean[2];

    void frame(BodyRig r, float dt, boolean forcedFace, boolean forcedGest, boolean feeling) {
        this.dt = dt;
        T += dt;
        idle += dt;
        int mode = r.mode();
        boolean still = mode == BodyRig.IDLE;
        if (moment >= 0 && (mt += dt) >= MOMENT_LEN[moment]) moment = -1;
        if (pending >= 0 && (pendingAge += dt) > 60) pending = -1;
        if (cur >= 0) {
            pt += dt;
            if (exit < 0 && (!still || dance || door || moment >= 0 || !forced && (!on || night && !calm(cur)))) exit = 0;
            if (exit < 0) { if (pt >= LEN[cur]) finish(); }
            else if ((exit += dt) >= EXIT) finish();
        } else sinceEnd += dt;
        if (cur < 0 && still && moment < 0 && !dance && !door) {
            if (pending >= 0) { start(pending, true); pending = -1; }
            else if (on && idle >= WAIT && sinceEnd >= gap && !forcedFace && !forcedGest && !feeling) start(pick(), false);
        }

        // who leads this frame
        face = null; gest = null; gaze = false; cross = 0; armK = 1; offsets = false; wipe = false;
        bodyDx = bodyDy = headDx = headDy = headTilt = 0;
        custom[0] = custom[1] = false;
        if (moment >= 0) {
            momentPose(mode == BodyRig.LISTENING);
        } else if (cur >= 0 && exit < 0) {
            gest = "none";
            posePlay(cur, pt);
            if (forcedFace) face = null;
            if (forcedGest) { gest = null; custom[0] = custom[1] = false; }
        } else if (still && !forcedGest && dance) {
            dancePose(forcedFace || feeling);
        } else if (still && !forcedFace && !feeling && !forcedGest) {
            if (door) doorPose();
            else wearPose(r);
        }
    }

    void arms(BodyRig.Arm R, BodyRig.Arm L, boolean forcedGest) {
        if (moment >= 0 || !forcedGest) {
            if (custom[0]) R.set(ax[0], ay[0], aa[0], as[0], ad[0]);
            if (custom[1]) L.set(ax[1], ay[1], aa[1], as[1], ad[1]);
        }
        int it = held();
        boolean show = it != 0 && "rest".equals(L.s);
        if (show) holdPose(it, L);
        if (wipe && "rest".equals(R.s)) R.set(mix(150, 214, ss(mt2, 0.3f, 1.7f)), 170 - 6 * ss(mt2, 0.3f, 1.7f), 84, "open", false);
        itemA = show ? Math.min(1, itemA + dt * 4) : Math.max(0, itemA - dt * 6);
        if (show || itemA <= 0) itemNow = show ? it : 0;
    }

    private void arm(int side, float x, float y, float a, String s, boolean down) {
        custom[side] = true; ax[side] = x; ay[side] = y; aa[side] = a; as[side] = s; ad[side] = down;
    }

    /** Eyes on a point of the picture. */
    private void look(float px, float py) {
        gaze = true;
        gx = cl((px - 300) / 210f, -1.25f, 1.25f);
        gy = cl((py - 250) / 230f, -1.1f, 1.2f);
    }

    // ---- where things are (BodyRig tells, every frame, before drawing)
    private final float[] hx = new float[2], hy = new float[2], ha = new float[2];
    private final boolean[] hidden = new boolean[2];
    private final String[] hs = {"rest", "rest"};
    private float headX = 300, headY = 248, headRot, bodyY;
    private float px, py; // a point worked out by grip / headPt

    void seen(float rx, float ry, float ra, String rs, boolean rh, float lx, float ly, float la, String ls, boolean lh,
              float headX, float headY, float headRot, float bodyY) {
        hx[0] = rx; hy[0] = ry; ha[0] = ra; hs[0] = rs; hidden[0] = rh;
        hx[1] = lx; hy[1] = ly; ha[1] = la; hs[1] = ls; hidden[1] = lh;
        this.headX = headX; this.headY = headY; this.headRot = headRot; this.bodyY = bodyY;
    }

    /** A point of a hand (in hand units: wrist at 0,0, fingers up; x as for his right hand) into (px, py). */
    private void grip(int side, float lx, float ly) {
        double a = Math.toRadians(ha[side]);
        float c = (float) Math.cos(a), s = (float) Math.sin(a), x = side == 0 ? lx : -lx;
        px = hx[side] + x * c - ly * s;
        py = hy[side] + x * s + ly * c;
    }

    /** A point of his head (head units: 0,0 between the eyes' line and the nose; ×1.18 on screen) into (px, py). */
    private void headPt(float lx, float ly) {
        double a = Math.toRadians(headRot);
        float c = (float) Math.cos(a), s = (float) Math.sin(a), x = lx * 1.18f, y = ly * 1.18f;
        px = headX + x * c - y * s;
        py = headY + x * s + y * c;
    }

    // ================================================================ drawing
    private float fa = 1, pf = 1, out, dir = 1;
    private boolean muted;

    void draw(BodyRig.Pen p, int layer) {
        fa = 1; dir = 1; muted = false;
        if (layer == BODY) {
            if (outfit == SWEATER) sweater(p);
            if (occasion == EASTER) pocketFlower(p);
        }
        if (layer == HEAD && occasion != 0) hat(p);
        if (layer == MID && itemNow != 0 && itemA > 0) { fa = itemA; heldThing(p, itemNow); fa = 1; }
        if (cur >= 0) {
            pf = exit >= 0 ? Math.max(0, 1 - exit / EXIT) : 1;
            out = exit >= 0 ? Math.min(1, exit / EXIT) : 0;
            fa = pf;
            muted = calm(cur);
            drawPlay(p, cur, pt, layer);
            muted = false; fa = 1; pf = 1;
        }
        if (moment == HIGH5 && layer == FRONT) highFiveBurst(p);
    }

    // ---- the faces (made once)
    private static BodyRig.Goal f(String d) { return BodyRig.face(d); }
    private static final BodyRig.Goal
            SMILE = f("smile=.62 squint=.25"),
            HAPPY = f("smile=.85 open=.28 squint=.3 brow=.3 eye=1.05"),
            LAUGH = f("smile=1 open=.62 wide=.25 es=happy blush=.45 bounce=1 brow=.2"),
            GIGGLE = f("smile=1 open=.5 wide=.3 es=happy blush=.85 bounce=1 brow=.35"),
            SURPRISED = f("eye=1.3 brow=1 round=.8 open=.62 smile=0"),
            WOW = f("eye=1.22 brow=.8 round=.45 open=.38 smile=.45 sparkle=1"),
            CURIOUS = f("eye=1.08 brow=.45 browL=.25 smile=.3 tilt=-6"),
            FOCUS = f("eye=.92 squint=.3 brow=-.05 browAng=.35 smile=.12 mouthX=5"),
            CARING = f("smile=.58 eye=.86 tilt=7 browAng=.3 blush=.4 squint=.2"),
            SHEEPISH = f("smile=.55 squint=.35 eye=.85 blush=.9 sweat=1 brow=.3 browAng=.6 tilt=8"),
            EXCITED = f("eye=1.2 sparkle=1 smile=1 open=.62 brow=.6 bounce=1"),
            PROUD = f("smile=.75 squint=.45 eye=.85 nod=-5 brow=.15"),
            BLOW = f("round=1 open=.24 smile=0 eye=.9 brow=.25 blush=.3"),
            SIP = f("es=calm smile=.3 brow=.1"),
            AHH = f("es=happy smile=.9 open=.3 blush=.3 brow=.25"),
            CROSS = f("eye=1.15 brow=.6 smile=.2 round=.4 open=.14"),
            PRESNEEZE = f("eye=.5 brow=.75 browAng=.6 open=.42 round=.4 smile=-.1 nod=-6 tilt=-3"),
            SNEEZE = f("es=sleep open=.55 wide=.35 smile=0 nod=10 brow=-.2 browAng=.8 tilt=3"),
            YAWN = f("es=sleep open=1.2 round=.9 wide=-.25 smile=0 brow=.55 tilt=6 nod=-4"),
            READ = f("eye=.8 smile=.25 brow=.1"),
            READNOD = f("eye=.8 smile=.35 brow=.15 nod=5"),
            CHUCKLE = f("es=happy smile=.85 open=.3 blush=.3 bounce=1"),
            WINK = f("smile=.8 esL=happy squint=.3 brow=.2 browL=-.1 tilt=4"),
            SADSMALL = f("smile=-.25 eye=.85 browAng=.7 brow=.1"),
            UHOH = f("eye=1.1 brow=.6 browAng=.6 smile=-.1 open=.15 round=.4"),
            BOO = f("eye=1.3 brow=1 round=.5 open=.7 smile=.5 sparkle=1"),
            REFRESH = f("eye=1.15 smile=.75 brow=.35 sparkle=1"),
            CONTENT = f("es=calm smile=.55 brow=.15"),
            CHEW1 = f("es=happy smile=.55 open=.16 wide=.2 blush=.25"),
            CHEW2 = f("es=happy smile=.7 blush=.25"),
            EATOPEN = f("eye=1.05 open=.55 round=.5 smile=.2 brow=.3"),
            // night: calm and slow
            DROWSY = f("eye=.5 smile=.35 tilt=6 brow=-.1"),
            NWONDER = f("eye=.78 smile=.5 brow=.25 tilt=4"),
            NSMILE = f("eye=.68 smile=.62 blush=.3 tilt=5 squint=.2"),
            NCARING = f("eye=.62 smile=.6 tilt=9 blush=.3 browAng=.2"),
            NSLEEP = f("es=sleep smile=.4 tilt=10 nod=4"),
            DREAMY = f("es=sleep smile=.5 tilt=10 nod=4 blush=.2"),
            NYAWN = f("es=sleep open=.6 round=.65 smile=0 tilt=8"),
            COUNT1 = f("eye=.72 smile=.45 open=.12 round=.3 tilt=4"),
            COUNT2 = f("eye=.72 smile=.55 tilt=4"),
            SNORE1 = f("es=sleep open=.1 round=.5 smile=.05 tilt=12 nod=5 zzz=1"),
            SNORE2 = f("es=sleep open=.3 round=.85 tilt=12 nod=6 zzz=1"),
            HUM1 = f("es=calm smile=.3 round=.5 open=.12 tilt=7"),
            HUM2 = f("es=calm smile=.38 round=.3 open=.06 tilt=-5"),
            // the extras
            HOT = f("smile=.25 eye=.88 sweat=1 blush=.35 brow=.1 browAng=.3"),
            PHEW = f("es=calm smile=.4 sweat=1 blush=.3"),
            EXPECT = f("smile=.62 eye=1.12 brow=.45 tilt=4"),
            EXPECT0 = f("smile=.55 eye=1.05 brow=.3"),
            DANCE1 = f("smile=.85 squint=.3 brow=.25"),
            DANCE2 = f("es=happy smile=.9 open=.2 brow=.2"),
            WARM = f("smile=.7 squint=.3 eye=.95 tilt=5");

    // ================================================================ the plays: who does what, when
    private void posePlay(int id, float t) {
        switch (id) {
            case KITTEN: kittenPose(t); break;
            case MOUSE: mousePose(t); break;
            case JUGGLE: jugglePose(t); break;
            case PLANE: planePose(t); break;
            case BUBBLES: bubblesPose(t); break;
            case BUTTERFLY: butterflyPose(t); break;
            case YOYO: yoyoPose(t); break;
            case BOOK: bookPose(t); break;
            case TEA: teaPose(t); break;
            case PLANT: plantPose(t); break;
            case BALLOON: balloonPose(t); break;
            case PEEK: peekPose(t); break;
            case TOP: topPose(t); break;
            case STRETCH: stretchPose(t); break;
            case VISIT: visitPose(t); break;
            case FIREFLY: fireflyPose(t); break;
            case CATNAP: catnapPose(t); break;
            case DREAM: dreamPose(t); break;
            case STARS: starsPose(t); break;
            case SNORE: snorePose(t); break;
            case HUM: humPose(t); break;
            case EAT: eatPose(t); break;
            case CAKE: cakePose(t); break;
            default: break;
        }
    }

    private void drawPlay(BodyRig.Pen p, int id, float t, int layer) {
        switch (id) {
            case KITTEN: kittenDraw(p, t, layer); break;
            case MOUSE: mouseDraw(p, t, layer); break;
            case JUGGLE: juggleDraw(p, t, layer); break;
            case PLANE: planeDraw(p, t, layer); break;
            case BUBBLES: bubblesDraw(p, t, layer); break;
            case BUTTERFLY: butterflyDraw(p, t, layer); break;
            case YOYO: yoyoDraw(p, t, layer); break;
            case BOOK: bookDraw(p, t, layer); break;
            case TEA: teaDraw(p, t, layer); break;
            case PLANT: plantDraw(p, t, layer); break;
            case BALLOON: balloonDraw(p, t, layer); break;
            case PEEK: peekDraw(p, t, layer); break;
            case TOP: topDraw(p, t, layer); break;
            case STRETCH: stretchDraw(p, t, layer); break;
            case VISIT: visitDraw(p, t, layer); break;
            case FIREFLY: fireflyDraw(p, t, layer); break;
            case CATNAP: catnapDraw(p, t, layer); break;
            case DREAM: dreamDraw(p, t, layer); break;
            case STARS: starsDraw(p, t, layer); break;
            case SNORE: snoreDraw(p, t, layer); break;
            case HUM: humDraw(p, t, layer); break;
            case EAT: eatDraw(p, t, layer); break;
            case CAKE: cakeDraw(p, t, layer); break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- 1. the kitten and the yarn ball
    private static final float KS = 1.3f; // (the kitten's size)
    private static final float[] YK_X = {0, 770, 2.5f, 566, 6.0f, 566, 6.7f, 546, 7.9f, 546, 9.6f, 452, 12.2f, 452, 13.8f, 800,
            15.0f, 800, 17.0f, 566, 20.0f, 566, 22.8f, 800};
    private static final float[] YB_X = {4.8f, 436, 6.4f, 490, 6.7f, 490, 7.9f, 396, 10.2f, 396, 10.8f, 370};
    private int kPose, kEyes;
    private float kDir, kY, kRot, kX;
    private int bMode; // the yarn ball: 0 none, 1 on the floor, 2 in his hand, 3 in the kitten's mouth
    private float bX;

    private void yarnState(float t) {
        kX = key(YK_X, t); kDir = -1; kY = FLOOR; kRot = 0; kEyes = E_OPEN; kPose = K_SIT;
        if (t < 2.5f) kPose = K_WALK;
        else if (t < 5.0f) kPose = K_SIT;
        else if (t < 6.0f) kPose = K_CROUCH;
        else if (t < 6.7f) { kPose = K_POUNCE; kY = FLOOR - 44 * sin(PI * seg(t, 6.0f, 6.7f)); }
        else if (t < 7.9f) { kRot = 360 * ss(t, 6.75f, 7.55f); kEyes = t < 7.55f ? E_SHUT : E_HAPPY; }
        else if (t < 9.6f) kPose = K_WALK;
        else if (t < 12.2f) { if (t > 10.0f && t < 10.7f) kPose = K_BAT; }
        else if (t < 13.8f) { kPose = K_WALK; kDir = 1; }
        else if (t < 15.0f) kPose = -1;
        else if (t < 17.0f) kPose = K_WALK;
        else if (t < 20.0f) { if (t < 17.7f) kPose = K_MEW; if (t > 18.0f && t < 19.7f) kEyes = E_HAPPY; }
        else { kPose = K_WALK; kDir = 1; }
        // the ball
        if (t < 4.8f) bMode = 2;
        else if (t < 11.4f) { bMode = 1; bX = key(YB_X, t); }
        else if (t < 12.0f) bMode = 2;
        else if (t < 13.6f) { bMode = 1; bX = mix(402, 820, sq(seg(t, 12.0f, 13.6f))); }
        else if (t < 15.0f) bMode = 0;
        else if (t < 20.0f) { bMode = 1; bX = kX - 66; }
        else bMode = 3;
    }

    /** His left hand stroking the kitten's head (sitting at kx, facing left): flat, over its ears, from his side. */
    private void pet(float t, float kx) { arm(1, kx - 58 + 8 * sin(t * 4), 590, 95, "side", true); }

    private void kittenPose(float t) {
        yarnState(t);
        face = t < 1.2f ? SMILE : t < 2.6f ? WOW : t < 4.8f ? HAPPY : t < 6.7f ? CURIOUS : t < 7.2f ? SURPRISED : t < 10.4f ? LAUGH
                : t < 12.2f ? HAPPY : t < 13.8f ? LAUGH : t < 15.0f ? CURIOUS : t < 17.0f ? WOW : t < 20.0f ? CARING : SMILE;
        if (t >= 2.6f && t < 4.8f) look(440, 560);
        else if (t >= 4.8f && t < 6.0f || t >= 10.4f && t < 12.0f) look(bX, 690);
        else if (t >= 13.8f && t < 15.0f) look(640, 640);
        else if (t < 23) look(kX - 12 * kDir, 640);
        if (t > 0.3f && t < 3.6f) arm(1, 452, 590, -14, "open", false);
        else if (t >= 3.6f && t < 5.4f) arm(1, 438, 744, -10, "open", true);
        else if (t > 10.5f && t < 12.3f) arm(1, t < 11.6f ? 374 : 404, 744, 0, "open", true);
        else if (t > 17.5f && t < 19.8f) pet(t, kX);
        if (t > 20.6f && t < 23.2f) gest = "wave";
    }

    private void kittenDraw(BodyRig.Pen p, float t, int layer) {
        yarnState(t);
        float run = out * 360;
        if (layer == MID) {
            if (bMode == 1) yarn(p, bX + run, FLOOR - 22, 22, -bX * 2.6f);
            if (kPose >= 0) kitten(p, kX + run, kY, KS, kDir, kPose, t * 9, kEyes, kRot);
            if (bMode == 3) yarn(p, kX + run + 52, FLOOR - 62, 14, t * 300);
            if (t > 17.3f && t < 19.3f) { fa = pf * (1 - seg(t, 18.6f, 19.3f)); heart(p, kX - 14, 566 - 46 * seg(t, 17.3f, 19.3f), 1.4f, 0xFFFF7A9A); fa = pf; }
        } else if (layer == FRONT && bMode == 2 && !hidden[1]) {
            grip(1, 0, -50);
            yarn(p, px, py, 22, t * 40);
        }
    }

    // ---------------------------------------------------------------- 2. the mouse from its hole
    private static final float HOLE = 584;
    private float mX, mDir, mUp, mPeek;
    private boolean mOut;

    private static final float MS = 1.35f; // (the mouse's size)

    private void mouseState(float t) {
        mDir = -1; mUp = 0; mPeek = 0; mOut = false;
        if (t < 3.2f) mPeek = ss(t, 1.2f, 1.8f) * (1 - ss(t, 2.9f, 3.2f));
        else if (t < 5.0f) { mOut = true; mX = mix(HOLE - 6, 336, ss(t, 3.2f, 5.0f)); }
        else if (t < 8.2f) { mOut = true; mX = 336; mUp = t > 5.6f && t < 7.8f ? 5 + 3 * sin(t * 9) : 0; }
        else if (t < 8.45f) { mOut = true; mX = 336; mUp = 12; }
        else if (t < 9.3f) { mOut = true; mDir = 1; mX = mix(336, HOLE + 14, ss(t, 8.45f, 9.3f)); }
        else if (t < 14.6f) mPeek = ss(t, 11.0f, 11.6f) * (1 - ss(t, 14.0f, 14.6f));
    }

    private void mousePose(float t) {
        mouseState(t);
        face = t < 1.4f ? SMILE : t < 2.2f ? SURPRISED : t < 3.4f ? CURIOUS : t < 5.0f ? WOW : t < 6.2f ? HAPPY : t < 8.2f ? CARING
                : t < 9.4f ? SURPRISED : t < 11.0f ? LAUGH : t < 12.0f ? CURIOUS : t < 14.6f ? WINK : SMILE;
        if (t > 1.4f && t < 3.4f || t > 9.4f && t < 14.6f) look(HOLE, 690);
        else if (mOut) look(mX, 690);
        if (t > 6.0f && t < 8.5f) { arm(1, 432, 744, -34, "open", true); armK = 0.35f; }
        if (t > 12.2f && t < 14.6f) gest = "wave";
    }

    private void mouseDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        mouseState(t);
        fa = pf * ss(t, 0, 1.0f) * (1 - ss(t, 15.5f, 17.0f));
        hole(p, HOLE);
        fa = pf;
        if (mPeek > 0) {
            p.save();
            holePath(p, HOLE);
            p.clip();
            mousePeek(p, HOLE, 720, mPeek, t > 12.6f && t < 12.8f);
            p.restore();
            if (t > 12.0f && t < 13.6f) { fa = pf * (1 - seg(t, 13.0f, 13.6f)); heart(p, HOLE + 4, 630 - 44 * seg(t, 12.0f, 13.6f), 1.3f, 0xFFFF7A9A); fa = pf; }
        }
        if (mOut) {
            float x = mX + out * 300, a = cl((HOLE + 14 - x) / 26f, 0, 1);
            fa = pf * (mDir > 0 ? a : 1);
            mouseSide(p, x, FLOOR - mUp, mDir, t * (mDir > 0 ? 34 : 26), t > 5.0f && t < 8.2f ? 3 : 0, t);
            fa = pf;
        }
    }

    // ---------------------------------------------------------------- 3. juggling (one falls: a sheepish grin)
    private static final float BEAT = 0.42f, J0 = 1.6f;
    private static final int THROWS = 22;
    private static final int[] BALL = {0xFFFF7A6B, 0xFFFFD166, 0xFF5EC8F2};

    /** The wrist's place while juggling (side 0: his right, throws on even beats). */
    private void jHand(int side, float b) {
        float inX = side == 0 ? 244 : 356, outX = side == 0 ? 192 : 408, y = 682;
        float end = side == 0 ? THROWS - 1 : THROWS; // (its last catch)
        if (b >= end) {
            float u = ss(b, end, end + 1.5f);
            px = mix(outX, side == 0 ? 220 : 380, u); py = mix(y - 2, 690, u);
            return;
        }
        float v = b - (side == 0 ? 0 : 1);
        if (b < 0) v = side == 0 ? 0 : 1;
        v = v - 2 * (float) Math.floor(v / 2);
        if (v < 1) { px = mix(inX, outX, sm(v)); py = y - 2 - 16 * sin(PI * v); }
        else { px = mix(outX, inX, sm(v - 1)); py = y + 14 * sin(PI * (v - 1)); }
    }

    private void jugglePose(float t) {
        float b = (t - J0) / BEAT;
        face = t < 1.6f ? SMILE : t < 4 ? FOCUS : t < 7 ? HAPPY : t < 10.4f ? PROUD : t < 11.2f ? SURPRISED : t < 15 ? SHEEPISH : SMILE;
        if (t < 10.4f) look(300, 520);
        else if (t < 11.6f) look(170, 720);
        else if (t < 13) look(150, 700);
        if (t > 0.4f && t < 15.2f) {
            jHand(0, b); arm(0, px, py, 6, "open", true);
            jHand(1, b); arm(1, px, py, -6, "open", true);
            if (t > 1.2f && t < 12) armK = 3.5f;
        }
    }

    private void juggleDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        float b = (t - J0) / BEAT;
        float fade = pf * ss(t, 0.6f, 1.4f) * (1 - ss(t, 15.0f, 15.8f));
        for (int i = 0; i < 3; i++) {
            fa = fade;
            if (b < i) { // still in its first hand (0 and 2: his right; 1: his left)
                int side = i == 1 ? 1 : 0;
                if (hidden[side]) continue;
                grip(side, 0, -50);
                float o = i == 2 ? 1 - seg(b, 0, 1) : 0;
                ball(p, px - 14 * o, py - 26 * o, 20, BALL[i]);
                continue;
            }
            int n = i + 3 * (int) Math.floor((b - i) / 3);
            if (n >= THROWS) n -= 3 * ((n - THROWS) / 3 + 1);
            float u = b - n;
            int thrower = (n & 1) == 0 ? 0 : 1, catcher = 1 - thrower;
            jHand(thrower, n); float x0 = px, y0 = py - 50;
            jHand(catcher, n + 2); float x1 = px, y1 = py - 50;
            if (n == THROWS - 1 || u < 2) { // in the air (the last throw is never caught)
                float v = u / 2, x = mix(x0, x1, v), y = mix(y0, y1, v) - 4 * 175 * v * (1 - v);
                if (y > 770) continue;
                ball(p, x, y, 20, BALL[i]);
            } else {
                if (hidden[catcher]) continue;
                grip(catcher, 0, -50);
                ball(p, px, py, 20, BALL[i]);
            }
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- 4. a paper plane (a loop, he cheers; it comes back)
    // (it flies out on his right-hand side, the free sky beside his head: never across his face)
    private void planePath(float t) {
        if (t < 3.6f) { float u = ss(t, 2.7f, 3.6f); bez(204, 318, 150, 334, 92, 274, u); }
        else if (t < 5.2f) { float a = PI / 2 + 2 * PI * ss(t, 3.6f, 5.2f); px = 92 + 82 * (float) Math.cos(a); py = 192 + 82 * (float) Math.sin(a); }
        else { float u = seg(t, 5.2f, 6.6f); px = mix(92, -180, u); py = 274 + 30 * u; }
    }

    private void planePose(float t) {
        face = t < 1.2f ? CURIOUS : t < 2.4f ? FOCUS : t < 3.6f ? WOW : t < 5.2f ? WOW : t < 7.2f ? HAPPY : t < 10.5f ? EXCITED
                : t < 13.4f ? CURIOUS : PROUD;
        if (t < 2.7f) look(190, 360);
        else if (t < 6.8f) { planePath(t); look(px, py); }
        else if (t > 10.5f && t < 13.6f) { planeBack(t); look(px, py); }
        if (t > 0.2f && t < 2.75f) {
            if (t < 1.2f) arm(0, 140, 452, 16, "hold", true);
            else if (t < 2.4f) arm(0, 112, 470, 4, "hold", true);
            else { arm(0, 156, 380, 40, "hold", false); armK = 2.5f; }
        }
        if (t > 7.4f && t < 10.2f) gest = "clap";
        if (t > 10.5f && t < 15.0f) arm(0, 112, 372, -10, "open", false);
    }

    /** The plane gliding back onto his open hand (10.5–13.2 s). */
    private void planeBack(float t) {
        float u = ss(t, 10.5f, 13.2f);
        bez(-160, 70, 10, 120, 100, 296, u);
    }

    private void planeDraw(BodyRig.Pen p, float t, int layer) {
        if (t < 2.7f) {
            if (hidden[0] || layer != MID) return;
            fa = pf * ss(t, 0.2f, 0.8f);
            grip(0, 2, -80);
            plane(p, px, py, ha[0] - 64, 1.6f);
        } else if (t < 6.8f) {
            if (layer != MID) return;
            planePath(t); float x = px, y = py;
            planePath(t + 0.03f);
            plane(p, x, y, deg(px - x, py - y), 1.6f);
        } else if (t > 10.5f && t < 13.2f) {
            if (layer != FRONT) return;
            planeBack(t); float x = px, y = py;
            planeBack(t + 0.03f);
            plane(p, x, y, deg(px - x, py - y), 1.6f);
        } else if (t >= 13.2f && t < 15.6f && !hidden[0] && layer == FRONT) { // caught: on his palm
            fa = pf * (1 - ss(t, 14.8f, 15.6f));
            grip(0, 0, -78);
            plane(p, px, py, -14, 1.6f);
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- 5. soap bubbles (one lands on his nose)
    // per bubble: born, life, vx, vy, radius, rise (they drift out to the side first, then up)
    private static final float[][] BUB = {
            {1.8f, 4.2f, -70, -10, 15, 14}, {2.2f, 4.8f, -55, -16, 20, 12}, {2.6f, 3.8f, -85, -6, 12, 18}, {3.0f, 5.0f, -62, -12, 17, 10},
            {8.6f, 4.4f, -75, -12, 18, 15}, {9.0f, 4.6f, -58, -18, 14, 12}, {9.4f, 4.0f, -88, -8, 21, 16}, {9.8f, 5.0f, -66, -10, 13, 11},
            {10.2f, 4.0f, -52, -16, 16, 18}};
    private static final float RINGX = 262, RINGY = 362;

    private void bubblesPose(float t) {
        face = t < 1.6f ? SMILE : t < 3.2f ? BLOW : t < 6.0f ? HAPPY : t < 7.0f ? CROSS : t < 7.5f ? SURPRISED : t < 8.6f ? LAUGH
                : t < 10.6f ? BLOW : t < 15 ? HAPPY : SMILE;
        if (t < 3.2f) look(RINGX, RINGY);
        else if (t < 6.0f) { bubble(1, t); look(px, py); }
        else if (t < 7.2f) { look(300, 330); cross = 1.6f; }
        else if (t > 10.6f && t < 15) { bubble(6, t); look(px, py); }
        if (t > 0.3f && t < 15.6f) {
            boolean blowing = t < 3.2f || t > 8.0f && t < 10.8f;
            if (blowing) arm(0, 178, 524, 28, "hold", true); else arm(0, 160, 582, 12, "hold", true);
        }
    }

    /** Bubble i at time t into (px, py); false when not there. */
    private boolean bubble(int i, float t) {
        float[] b = BUB[i];
        float tau = t - b[0];
        if (tau < 0 || tau > b[1] + 0.3f) return false;
        float k = Math.min(tau, b[1]);
        px = RINGX - 14 + b[2] * k + 8 * sin(k * 1.7f + i);
        py = RINGY + b[3] * k - b[5] * k * k;
        return true;
    }

    private void bubblesDraw(BodyRig.Pen p, float t, int layer) {
        if (layer == MID && t > 0.3f && t < 16.2f && !hidden[0]) { // the wand
            fa = pf * ss(t, 0.3f, 0.9f) * (1 - ss(t, 15.4f, 16.0f));
            grip(0, 0, -6); float x0 = px, y0 = py;
            grip(0, 0, -162); float x1 = px, y1 = py;
            grip(0, 0, -186);
            p.begin(); p.moveTo(x0, y0); p.lineTo(x1, y1); line(p, 0xFF9E4A70, 7); line(p, 0xFFF06FA6, 4.5f);
            p.begin(); p.addCircle(px, py, 23); line(p, 0xFF9E4A70, 6.5f); line(p, 0xFFF06FA6, 4);
            p.begin(); p.addCircle(px, py, 20); fill(p, 0x228FD6FF);
        }
        if (layer != FRONT) return;
        for (int i = 0; i < BUB.length; i++) {
            if (!bubble(i, t)) continue;
            float tau = t - BUB[i][0], r = BUB[i][4] * Math.min(1, tau / 0.4f);
            fa = pf;
            if (tau > BUB[i][1]) pop(p, px, py, r, (tau - BUB[i][1]) / 0.3f);
            else bub(p, px, py, r);
        }
        // the one that lands on his nose
        if (t > 3.0f && t < 7.3f) {
            fa = pf;
            if (t < 7.0f) {
                float u = ss(t, 3.0f, 7.0f);
                headPt(0, 44);
                bez(RINGX, RINGY, 390, 120, px, py - 14, u);
                bub(p, px, py, 15 * Math.min(1, (t - 3.0f) / 0.4f));
            } else { headPt(0, 40); pop(p, px, py, 15, (t - 7.0f) / 0.3f); }
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- 6. a butterfly on his nose (cross-eyed, a sneeze)
    private static final float[] BF_X = {0, 720, 1.2f, 590, 2.2f, 470, 3.2f, 430, 4.2f, 350};
    private static final float[] BF_Y = {0, 40, 1.2f, 130, 2.2f, 60, 3.2f, 190, 4.2f, 236};
    private static final float[] BO_X = {9.7f, 300, 10.6f, 220, 11.6f, 80, 12.8f, -90};
    private static final float[] BO_Y = {9.7f, 300, 10.6f, 160, 11.6f, 70, 12.8f, -50};

    private void butterflyAt(float t) {
        if (t < 4.2f) { px = key(BF_X, t); py = key(BF_Y, t); }
        else if (t < 9.7f) {
            float u = ss(t, 4.2f, 5.0f);
            headPt(0, 42);
            px = mix(350, px, u); py = mix(236, py, u);
        } else { px = key(BO_X, t); py = key(BO_Y, t); }
    }

    private void butterflyPose(float t) {
        face = t < 2.0f ? CURIOUS : t < 5.0f ? WOW : t < 8.2f ? CROSS : t < 9.6f ? PRESNEEZE : t < 10.2f ? SNEEZE : t < 12.6f ? LAUGH : SMILE;
        if (t < 5.0f || t > 10.2f && t < 14) { butterflyAt(t); look(px, py); }
        else if (t < 9.6f) { look(300, 320); cross = t < 8.2f ? 1.6f : 0.8f; }
        if (t > 12.6f && t < 15.2f) gest = "wave";
    }

    private void butterflyDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        butterflyAt(t);
        boolean sitting = t > 5.0f && t < 9.7f;
        float flap = sitting ? 0.55f + 0.45f * (float) Math.cos(t * 2.4f) : Math.abs((float) Math.cos(t * 16));
        float ang = sitting ? headRot : 10 * sin(t * 3);
        butterfly(p, px, py, sitting ? 1.05f : 1.45f, flap, ang);
        if (t > 9.6f && t < 10.4f) { // the sneeze's puff
            fa = pf * (1 - seg(t, 9.6f, 10.4f));
            headPt(0, 60);
            float r = 30 + 40 * seg(t, 9.6f, 10.4f);
            p.begin();
            for (int i = 0; i < 5; i++) {
                float a = PI * (0.15f + 0.175f * i);
                p.moveTo(px + r * 0.6f * (float) Math.cos(a), py + r * 0.6f * (float) Math.sin(a));
                p.lineTo(px + r * (float) Math.cos(a), py + r * (float) Math.sin(a));
            }
            line(p, 0xCCE8F2FF, 3);
            fa = pf;
        }
    }

    // ---------------------------------------------------------------- 7. a yo-yo
    private float yoY(float t) { // how far down (0 = in the hand .. 1 = at the bottom)
        if (t < 1.4f || t > 13.0f) return 0;
        if (t < 7.8f) {
            float c = (t - 1.4f) % 1.6f;
            if (c < 0.55f) return sq(c / 0.55f);
            if (c < 0.9f) return 1;
            if (c < 1.5f) { float u = (c - 0.9f) / 0.6f; return 1 - (1 - (1 - u) * (1 - u)); }
            return 0;
        }
        if (t < 8.3f) return ss(t, 7.8f, 8.3f);
        if (t < 12.4f) return 1;
        return 1 - ss(t, 12.4f, 13.0f);
    }

    private void yoyoPose(float t) {
        face = t < 1.4f ? SMILE : t < 4.6f ? FOCUS : t < 7.8f ? HAPPY : t < 9.0f ? WOW : t < 11.0f ? LAUGH : t < 13.4f ? PROUD : SMILE;
        if (t > 0.2f && t < 13.6f) {
            float c = (t - 1.4f) % 1.6f, flick = t > 1.4f && t < 7.8f && c > 0.85f && c < 1.05f ? -14 : 0;
            arm(1, 482, 424 + flick, -4, "hold", true);
        }
        if (t > 11.0f && t < 13.0f) arm(0, 236, 600, -4, "thumb", true);
        if (t < 13.4f) { yoyoAt(t); look(px, py); }
    }

    private void yoyoAt(float t) {
        float d = yoY(t), x0 = 478, y0 = 400;
        if (t > 8.3f && t < 12.4f) { // walking the dog: rolls along the floor and back
            float u = (float) Math.sin(PI * seg(t, 8.3f, 12.4f));
            px = x0 + 120 * u; py = FLOOR - 26;
        } else { px = x0; py = mix(y0, FLOOR - 26, d); }
    }

    private void yoyoDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT || hidden[1]) return;
        fa = pf * ss(t, 0.2f, 0.8f) * (1 - ss(t, 13.6f, 14.4f));
        grip(1, 0, -30); float sx = px, sy = py;
        yoyoAt(t);
        float x = px, y = py;
        if (yoY(t) < 0.06f) { grip(1, 0, -38); x = px; y = py; } // (in his hand)
        else { p.begin(); p.moveTo(sx, sy); p.lineTo(x, y); line(p, 0xFFEDEDED, 2); }
        float spin = t > 1.4f && t < 13 ? t * 900 : 0;
        at(p, x, y, spin, 1.3f, 1);
        p.begin(); circ(p, 0, 0, 20); line(p, 0xFF2E8F75, 4); fill(p, 0xFF4FC3A1);
        p.begin(); mv(p, -14, -6); qu(p, 0, -16, 14, -6); mv(p, -14, 6); qu(p, 0, 16, 14, 6); line(p, 0xFF2E8F75, 2.4f);
        p.begin(); circ(p, 0, 0, 6); fill(p, 0xFFF4F7F2);
        end(p);
        fa = pf;
    }

    // ---------------------------------------------------------------- 8. reading a book (a page turns, he nods, he chuckles)
    private void bookPose(float t) {
        face = t < 2 ? SMILE : t < 7 ? READ : t < 10 ? (((int) (t * 1.2f)) % 2 == 0 ? READ : READNOD) : t < 12 ? CHUCKLE : t < 15 ? READ
                : t < 16 ? WOW : t < 18 ? LAUGH : t < 20 ? HAPPY : SMILE;
        if (t < 18) look(t < 2 ? 300 : 260 + 80 * (((t - 2) % 1.6f) / 1.6f), 560 + ((int) ((t - 2) / 1.6f) % 3) * 12);
        if (t > 0.2f && t < 20.2f) { arm(0, 192, 652, 20, "hold", true); arm(1, 408, 652, -20, "hold", true); }
    }

    private void bookDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        if (hidden[0] || hidden[1]) return;
        grip(0, 0, -38); float x0 = px, y0 = py;
        grip(1, 0, -38);
        float cx = (x0 + px) / 2, cy = (y0 + py) / 2 - 58;
        fa = pf * ss(t, 0.3f, 1.0f) * (1 - ss(t, 20.0f, 20.8f));
        float shut = ss(t, 18.0f, 19.0f);
        at(p, cx, cy, 0, 1.1f, 1);
        if (shut < 1) {
            float w = 92 * (1 - shut);
            // the cover, then the two pages
            p.begin(); rr(p, -w - 6, -60, 2 * w + 12, 122, 6); fill(p, 0xFFC8504E);
            p.begin(); mv(p, -w, -56); qu(p, -w * 0.5f, -62, 0, -54); ln(p, 0, 58); qu(p, -w * 0.5f, 52, -w, 56); p.close(); fill(p, 0xFFFBF6EA);
            p.begin(); mv(p, w, -56); qu(p, w * 0.5f, -62, 0, -54); ln(p, 0, 58); qu(p, w * 0.5f, 52, w, 56); p.close(); fill(p, 0xFFF4EDDC);
            p.begin(); mv(p, 0, -54); ln(p, 0, 58); line(p, 0xFFD9CDB2, 2);
            if (shut < 0.4f) {
                // a little picture and lines on the left; lines on the right
                p.begin(); circ(p, -62 * (1 - shut), -32, 9); fill(p, 0xFFF5B83D);
                p.begin(); mv(p, -84, -6); qu(p, -60, -26, -30, -8); ln(p, -30, -4); ln(p, -84, -4); p.close(); fill(p, 0xFF8CC08A);
                p.begin();
                for (int i = 0; i < 4; i++) { mv(p, -82, 10 + i * 11); ln(p, -14 - (i == 3 ? 26 : 0), 10 + i * 11); }
                for (int i = 0; i < 8; i++) { mv(p, 14, -42 + i * 11); ln(p, 82 - (i % 3 == 2 ? 22 : 0), -42 + i * 11); }
                line(p, 0xFFB9AE98, 2.2f);
            }
            // a page turning (6–7 s, 12.2–13.2 s)
            float u = t > 6 && t < 7 ? seg(t, 6, 7) : t > 12.2f && t < 13.2f ? seg(t, 12.2f, 13.2f) : -1;
            if (u >= 0) {
                float e = 88 * (float) Math.cos(PI * sm(u)), lift = 10 * sin(PI * u);
                p.begin(); mv(p, 0, -54); qu(p, e * 0.5f, -60 - lift, e, -56 - lift); ln(p, e, 56 - lift); qu(p, e * 0.5f, 52 - lift, 0, 58); p.close();
                fill(p, 0xFFFFFCF4); line(p, 0xFFD9CDB2, 1.5f);
            }
        }
        if (shut > 0) { // the closed book
            fa = pf * shut * (1 - ss(t, 20.0f, 20.8f));
            p.begin(); rr(p, -58, -60, 116, 122, 6); fill(p, 0xFFC8504E);
            p.begin(); rr(p, -44, -38, 88, 24, 4); fill(p, 0xFFF3D7A6);
            p.begin(); mv(p, -50, -60); ln(p, -50, 62); line(p, 0xFF8E3332, 3);
        }
        end(p);
        fa = pf;
    }

    // ---------------------------------------------------------------- 9. a cup of tea (blows, sips, "ahh")
    private void teaPose(float t) {
        boolean sip = t > 4.6f && t < 6.6f || t > 12.2f && t < 14;
        boolean up = t > 3.4f && t < 7.0f || t > 11.0f && t < 14.4f;
        face = t < 1.5f ? SMILE : t < 3.4f ? BLOW : sip ? SIP : t < 7.6f ? SMILE : t < 9.6f ? AHH : t < 11 ? SMILE : t < 12.2f ? CONTENT
                : t < 15 ? (t < 14.4f ? SMILE : HAPPY) : t < 16.4f ? HAPPY : SMILE;
        if (t > 0.2f && t < 16.6f) {
            if (up) arm(1, 364, 420, -6, "hold", true);
            else if (t > 1.5f && t < 3.4f) arm(1, 404, 562, 0, "hold", true);
            else arm(1, 410, 600, 0, "hold", true);
        }
        if (!sip && t < 11) look(350, 530);
    }

    private void teaDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID || hidden[1]) return;
        fa = pf * ss(t, 0.2f, 0.9f) * (1 - ss(t, 16.2f, 17.0f));
        grip(1, 0, -38);
        boolean up = t > 4.0f && t < 7.0f || t > 11.6f && t < 14.4f;
        float tilt = up ? -12 : 0, steam = up ? 0 : 1 - ss(t, 3.4f, 4.0f) * (1 - ss(t, 7.0f, 7.8f)) - ss(t, 11.0f, 11.6f) * (1 - ss(t, 14.4f, 15.2f));
        cup(p, px, py, tilt, t, t > 1.5f && t < 3.4f, steam);
        fa = pf;
    }

    /** The cup held by its handle at (hx0, hy0) (the fist); steam 0..1. */
    private void cup(BodyRig.Pen p, float hx0, float hy0, float tilt, float t, boolean blown, float steam) {
        at(p, hx0, hy0, tilt, 1.5f, 1);
        p.translate(-38, 2);
        // steam
        if (steam > 0.02f) {
            float bend = blown ? -14 : 0, k = fa;
            fa = k * steam;
            p.begin();
            for (int i = 0; i < 3; i++) {
                float x = -12 + 12 * i, ph = t * 2.2f + i * 2;
                mv(p, x, -32); cu(p, x + 8 * sin(ph), -46, x - 8 * sin(ph + 1) + bend * 0.5f, -62, x + 4 * sin(ph + 2) + bend, -80);
            }
            line(p, 0x80E8EEF5, 3.2f);
            fa = k;
        }
        // the handle, the cup, the tea
        p.begin(); circ(p, 36, -2, 12); line(p, 0xFF9C8F7C, 9); line(p, 0xFFF7EFE2, 5.5f);
        p.begin(); mv(p, -30, -26); ln(p, 30, -26); cu(p, 29, 8, 24, 26, 0, 27); cu(p, -24, 26, -29, 8, -30, -26); p.close();
        line(p, 0xFF9C8F7C, 4); fill(p, 0xFFF7EFE2);
        p.begin(); mv(p, -28.6f, -8); ln(p, 28.6f, -8); ln(p, 27.6f, 2); ln(p, -27.6f, 2); p.close(); fill(p, 0xFF6FA8DC);
        p.begin(); ov(p, 0, -26, 30, 6); fill(p, 0xFFF7EFE2); line(p, 0xFF9C8F7C, 2.5f);
        p.begin(); ov(p, 0, -25, 25, 4); fill(p, 0xFFB5733A);
        end(p);
    }

    // ---------------------------------------------------------------- 10. watering a plant until a flower opens
    private static final float POTX = 572, POTS = 1.3f;

    private void plantPose(float t) {
        face = t < 1.6f ? SMILE : t < 7.6f ? (t < 4 ? HAPPY : CARING) : t < 9.2f ? CURIOUS : t < 10.8f ? WOW : t < 13.2f ? EXCITED
                : t < 17 ? CARING : SMILE;
        look(POTX, t < 9 ? 600 : 520);
        if (t > 0.2f && t < 9.0f) arm(1, 424, 486, 6, "hold", false);
        if (t > 10.8f && t < 13.2f) gest = "clap";
    }

    private float canTilt(float t) { return 32 * ss(t, 1.6f, 2.4f) * (1 - ss(t, 7.6f, 8.4f)); }

    private void plantDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        float potA = ss(t, 0, 1.2f) * (1 - ss(t, 17.5f, 19.5f));
        fa = pf * potA;
        float grow = ss(t, 2.5f, 8.5f), bloom = ss(t, 9.2f, 10.8f);
        at(p, POTX, 720, 0, POTS, 1);
        pot(p, 0, 0, grow, bloom, t);
        end(p);
        if (t > 0.2f && t < 9.4f && !hidden[1]) {
            fa = pf * ss(t, 0.4f, 1.1f) * (1 - ss(t, 8.6f, 9.2f));
            float tilt = canTilt(t);
            grip(1, 0, -38);
            float cx = px, cy = py;
            if (tilt > 26) { // water from the rose
                double a = Math.toRadians(tilt);
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                float rx = cx + 160 * c + 26 * s, ry = cy + 160 * s - 26 * c, soil = 720 - 66 * POTS;
                p.begin();
                for (int i = 0; i < 9; i++) {
                    float u = ((t * 1.5f) + i / 9f) % 1f, x = rx + 4 * u + (i % 3 - 1) * 5, y = ry + (soil - ry) * u * u;
                    p.addOval(x, y, 3, 5);
                }
                fill(p, 0xCC9FD8FF);
            }
            can(p, cx, cy, tilt);
        }
        if (bloom > 0.95f && t < 12) { // a sparkle when it opens
            fa = pf * (1 - seg(t, 10.8f, 12));
            sparkle(p, POTX + 40, 500, 12 + 6 * seg(t, 10.8f, 12), 0xFFFFF3B0);
            sparkle(p, POTX - 44, 524, 8, 0xFFFFF3B0);
        }
        fa = pf;
    }

    /** The potted plant, its pot standing at (x, y) (local units). */
    private void pot(BodyRig.Pen p, float x, float y, float grow, float bloom, float t) {
        float h = 36 + 62 * grow, sway = 3 * sin(t * 1.3f), top = y - 66, tx = x + sway, ty = top - h;
        // the stem and leaves
        p.begin(); mv(p, x, top); qu(p, x - 4, top - h * 0.5f, tx, ty); line(p, 0xFF3F7F3B, 8); line(p, 0xFF5FAE5A, 5);
        float l1 = Math.min(1, 0.4f + grow), l2 = ss(grow, 0.3f, 1);
        leaf(p, x - 2, top - h * 0.35f, -1, 20 * l1);
        leaf(p, x - 1, top - h * 0.45f, 1, 18 * l1);
        if (l2 > 0) { leaf(p, x + sway * 0.6f, top - h * 0.7f, -1, 16 * l2); leaf(p, x + sway * 0.6f, top - h * 0.75f, 1, 14 * l2); }
        // the bud, then the flower
        if (bloom < 1) {
            float b = 0.7f + 0.3f * grow;
            p.begin(); mv(p, tx, ty - 18 * b); cu(p, tx + 10 * b, ty - 8 * b, tx + 8 * b, ty + 4, tx, ty + 4); cu(p, tx - 8 * b, ty + 4, tx - 10 * b, ty - 8 * b, tx, ty - 18 * b); p.close();
            fill(p, 0xFFF48FB1); line(p, 0xFFB65A7E, 1.6f);
        }
        if (bloom > 0) {
            float r = 16 * bloom;
            p.begin();
            for (int i = 0; i < 5; i++) {
                double a = -PI / 2 + i * 2 * PI / 5 + t * 0.2f;
                p.addCircle(tx + r * 0.9f * (float) Math.cos(a), ty - 6 + r * 0.9f * (float) Math.sin(a), r * 0.62f);
            }
            fill(p, 0xFFFF9EC0);
            p.begin(); p.addCircle(tx, ty - 6, r * 0.5f); fill(p, 0xFFFFD45C);
        }
        // the pot (in front of the stem's foot)
        p.begin(); mv(p, x - 44, y - 58); ln(p, x + 44, y - 58); ln(p, x + 33, y); ln(p, x - 33, y); p.close(); fill(p, 0xFFD7834F); line(p, 0xFF8F4E2A, 3);
        p.begin(); rr(p, x - 50, y - 72, 100, 16, 5); fill(p, 0xFFE89A66); line(p, 0xFF8F4E2A, 3);
        p.begin(); ov(p, x, y - 70, 40, 4); fill(p, 0xFF5A3A28);
    }

    private void leaf(BodyRig.Pen p, float x, float y, int side, float s) {
        if (s < 1) return;
        p.begin(); p.moveTo(x, y); p.cubicTo(x + side * s * 0.5f, y - s * 0.7f, x + side * s * 1.4f, y - s * 0.6f, x + side * s * 1.6f, y - s * 0.2f);
        p.cubicTo(x + side * s * 1.2f, y + s * 0.2f, x + side * s * 0.5f, y + s * 0.2f, x, y); p.close();
        fill(p, 0xFF7CC576); line(p, 0xFF3F7F3B, 1.6f);
    }

    /** The watering can held by its back handle at (hx0, hy0) (the fist), tilted by deg (positive: the spout goes down). */
    private void can(BodyRig.Pen p, float hx0, float hy0, float deg) {
        at(p, hx0, hy0, deg, 1, 1);
        p.begin(); mv(p, 92, 16); ln(p, 152, -22); line(p, 0xFF2F6EA8, 13); line(p, 0xFF5DA9E9, 8);   // the spout
        p.begin(); ov(p, 158, -26, 8, 13); fill(p, 0xFF3B7FC0); line(p, 0xFF2F6EA8, 2);               // its rose
        p.begin(); mv(p, 26, -26); cu(p, -14, -32, -14, 32, 26, 26); line(p, 0xFF2F6EA8, 11); line(p, 0xFF5DA9E9, 6); // the back handle
        p.begin(); rr(p, 20, -34, 82, 66, 14); fill(p, 0xFF5DA9E9); line(p, 0xFF2F6EA8, 3);           // the body
        p.begin(); rr(p, 20, -6, 82, 10, 2); fill(p, 0xFF3B7FC0);
        p.begin(); ov(p, 61, -34, 34, 6); fill(p, 0xFF8CC7F2); line(p, 0xFF2F6EA8, 2);
        end(p);
    }

    // ---------------------------------------------------------------- 11. a balloon floats away (he waves bye)
    private void balloonAt(float t) {
        if (t < 4.9f) { px = 72 + 8 * sin(t * 1.3f); py = 300 + 6 * sin(t * 1.7f) + (t > 1.5f && t < 4.6f ? 10 * sin(t * 5) : 0); }
        else { float k = t - 4.9f; px = 72 + 8 * sin(4.9f * 1.3f) + 22 * sin(k * 1.2f) - 14 * k; py = 300 - 34 * k - 13 * k * k; }
    }

    private void balloonPose(float t) {
        face = t < 1.5f ? HAPPY : t < 4.6f ? SMILE : t < 6.8f ? SURPRISED : t < 8.8f ? SADSMALL : SMILE;
        balloonAt(t);
        if (t < 12.2f) look(px, py);
        if (t > 0.2f && t < 4.95f) arm(0, 230, 606 + (t > 1.5f && t < 4.6f ? 10 * sin(t * 5) : 0), -14, "hold", true);
        else if (t >= 4.95f && t < 5.4f) arm(0, 230, 600, -14, "open", true);
        else if (t >= 5.4f && t < 7.0f) arm(0, 104, 356, -12, "open", false);
        if (t > 8.8f && t < 12.2f) gest = "wave";
    }

    private void balloonDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        balloonAt(t);
        float bx = px, by = py;
        fa = pf * ss(t, 0.1f, 0.8f);
        if (by < -120) return;
        float kx = bx, ky = by + 54;
        // the string: to his fist, or hanging free
        p.begin(); p.moveTo(kx, ky);
        if (t < 4.95f && !hidden[0]) { grip(0, 0, -74); p.quadTo(kx + 30, (ky + py) / 2, px, py); }
        else { float w = 8 * sin(t * 4); p.cubicTo(kx + w, ky + 40, kx - w, ky + 80, kx + w * 0.5f, ky + 118); }
        line(p, 0xFFE9EEF5, 2);
        p.begin(); mv(p, kx - 6, ky + 4); ln(p, kx + 6, ky + 4); ln(p, kx, ky - 4); p.close(); fill(p, 0xFFC94767);
        p.begin(); p.addOval(bx, by, 42, 52); fill(p, 0xFFFF6F91); line(p, 0xFFC94767, 2.5f);
        p.begin(); p.addOval(bx - 14, by - 20, 9, 15); fill(p, 0x66FFFFFF);
        fa = pf;
    }

    // ---------------------------------------------------------------- 12. peek-a-boo
    private void peekPose(float t) {
        boolean covered = t > 0.6f && t < 2.4f || t > 4.4f && t < 6.4f || t > 8.0f && t < 9.8f;
        face = covered ? SMILE : t < 0.6f ? SMILE : t < 3.2f ? BOO : t < 4.4f ? LAUGH : t < 8.0f ? WINK : t < 11 ? EXCITED : LAUGH;
        if (t > 11.0f) face = t < 11.6f ? LAUGH : SMILE;
        if (t < 11.0f) {
            if (covered || t < 0.6f) { arm(0, 250, 300, 8, "open", false); arm(1, 350, 300, -8, "open", false); }
            else { arm(0, 126, 322, -22, "open", false); arm(1, 474, 322, 22, "open", false); }
            armK = 1.6f;
        }
    }

    private void peekDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        float u = t > 2.4f && t < 3.0f ? seg(t, 2.4f, 3.0f) : t > 6.4f && t < 7.0f ? seg(t, 6.4f, 7.0f) : t > 9.8f && t < 10.4f ? seg(t, 9.8f, 10.4f) : -1;
        if (u < 0) return;
        fa = pf * (1 - u);
        sparkle(p, 70, 220 - 20 * u, 12, 0xFFFFF3B0);
        sparkle(p, 530, 210 - 20 * u, 14, 0xFFFFF3B0);
        sparkle(p, 560, 330 - 10 * u, 8, 0xFFFFF3B0);
        fa = pf;
    }

    // ---------------------------------------------------------------- 13. a spinning top (a lattu, thrown down with its string)
    private static final float TOPX = 44, TOPS = 1.4f, LAND = 2.15f;

    private void topPose(float t) {
        face = t < 1.6f ? FOCUS : t < 2.6f ? WOW : t < 9.0f ? (((int) (t / 2)) % 2 == 0 ? HAPPY : EXCITED) : t < 10.4f ? UHOH
                : t < 11.4f ? SURPRISED : t < 14 ? LAUGH : SMILE;
        if (t < 1.75f) look(130, 380);
        else { topAt(t); look(px, 660); }
        if (t > 0.1f && t < 3.2f) {
            if (t < 1.2f) arm(0, 132, 452, -6, "hold", true);
            else if (t < 1.65f) arm(0, 104, 352, -20, "hold", false);
            else { arm(0, 176, 590, 28, "hold", true); armK = 3; }
        }
    }

    private float topTilt, topY;

    private void topAt(float t) {
        topY = FLOOR;
        if (t < LAND) { // in the air: a fast drop, a turn and a half
            float u = sq(seg(t, 1.75f, LAND));
            bez(112, 316, 70, 430, TOPX, FLOOR, u);
            topTilt = 540 * u;
            return;
        }
        float k = t - LAND;
        px = TOPX + 14 * sin(k * 1.1f) * ss(t, LAND, 3.2f);
        float wob = 4 + 20 * ss(t, 9.0f, 10.4f);
        topTilt = wob * sin(t * 7);
        if (t > 10.4f) {
            float u = ss(t, 10.4f, 11.0f);
            topTilt = mix(topTilt, 80, u) + (t > 11.0f ? 6 * sin((t - 11) * 9) * (1 - seg(t, 11, 11.8f)) : 0);
            px += 12 * u; topY = FLOOR - 34 * u; // (on its side)
        }
    }

    private void topDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        fa = pf * ss(t, 0, 0.5f) * (1 - ss(t, 14.2f, 15.4f));
        if (t < 1.75f) { // in his fist, tip down
            if (hidden[0]) return;
            grip(0, 0, -34);
            lattu(p, px, py, ha[0], 0, 0.8f);
            fa = pf;
            return;
        }
        topAt(t);
        if (t < 3.2f && !hidden[0]) { // the string: to the top, then hanging from his fist
            float k = fa;
            fa = k * (1 - seg(t, 2.6f, 3.2f));
            grip(0, 0, -40);
            p.begin(); p.moveTo(px - 0, py);
            if (t < LAND) { topAt(t); p.lineTo(px, py - 40); }
            else { float w = 10 * sin(t * 9); p.cubicTo(px - 20 + w, py + 40, px + 10 - w, py + 80, px - 6 + w, py + 120); }
            line(p, 0xFFF2E6C8, 2);
            fa = k;
            topAt(t);
        }
        float spin = t < LAND ? 0 : t < 10.4f ? t * 14 : (10.4f * 14 + (t - 10.4f) * 14 * (1 - seg(t, 10.4f, 11.4f) * 0.5f));
        float spd = t < LAND ? 0 : 1 - ss(t, 9.5f, 11.0f);
        if (t >= LAND - 0.1f) { p.begin(); p.addOval(px, FLOOR + 1, 34, 6); fill(p, 0x55000000); }
        if (spd > 0.1f) { // spin marks
            float k = fa;
            fa = k * spd * 0.8f;
            p.begin(); mv(p, px - 52, FLOOR - 70); qu(p, px - 66, FLOOR - 44, px - 52, FLOOR - 20); mv(p, px + 52, FLOOR - 70); qu(p, px + 66, FLOOR - 44, px + 52, FLOOR - 20);
            line(p, 0xAAFFF3B0, 3.5f);
            fa = k;
        }
        lattu(p, px, topY, topTilt, spin, 1);
        fa = pf;
    }

    /** The lattu (red lacquer, yellow and green bands, a metal tip) standing on its tip at (x, y). */
    private void lattu(BodyRig.Pen p, float x, float y, float deg, float spin, float s) {
        at(p, x, y, deg, TOPS * s, 1);
        p.save();
        lattuPath(p); fill(p, 0xFFE0473B);
        p.clip();
        p.begin(); mv(p, -40, -44); ln(p, 40, -44); line(p, 0xFFFFC93C, 9);
        p.begin(); mv(p, -40, -26); ln(p, 40, -26); line(p, 0xFF3FAF6A, 5);
        p.begin();
        for (int i = 0; i < 3; i++) { float v = ((spin * 9 + i * 24) % 72) - 36; mv(p, v, -74); qu(p, v * 0.9f, -40, 0, -4); }
        line(p, 0x55FFFFFF, 4);
        p.restore();
        lattuPath(p); line(p, 0xFF7A2318, 2.5f);
        p.begin(); mv(p, -5, -9); ln(p, 0, 3); ln(p, 5, -9); p.close(); fill(p, 0xFFB0B7C0);
        p.begin(); circ(p, 0, -72, 6); fill(p, 0xFFFFC93C); line(p, 0xFF7A2318, 1.5f);
        end(p);
    }

    private void lattuPath(BodyRig.Pen p) {
        p.begin(); mv(p, 0, 0); ln(p, -5, -9); cu(p, -30, -16, -37, -46, -24, -62); cu(p, -14, -72, 14, -72, 24, -62); cu(p, 37, -46, 30, -16, 5, -9); p.close();
    }

    // ---------------------------------------------------------------- 14. a stretch and a yawn
    private void stretchPose(float t) {
        face = t < 1.0f ? SMILE : t < 4.5f ? YAWN : t < 5.5f ? CONTENT : t < 7 ? HAPPY : t < 9 ? REFRESH : SMILE;
        if (t > 1.0f && t < 5.4f) gest = "stretch";
    }

    private void stretchDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT || t < 7 || t > 9.2f) return;
        float u = seg(t, 7, 9.2f);
        fa = pf * sin(PI * u);
        sparkle(p, 128, 140 - 16 * u, 12, 0xFFFFF3B0);
        sparkle(p, 476, 120 - 16 * u, 15, 0xFFFFF3B0);
        sparkle(p, 506, 230 - 12 * u, 9, 0xFFFFF3B0);
        fa = pf;
    }

    // ---------------------------------------------------------------- 15. the pet kitten comes to sit with him
    private static final float[] VK_X = {0, 770, 3.0f, 566, 18.6f, 566, 21.8f, 800};

    private void visitState(float t) {
        kX = key(VK_X, t); kDir = -1; kY = FLOOR; kRot = 0; kEyes = E_OPEN; kPose = K_SIT;
        if (t < 3.0f) kPose = K_WALK;
        else if (t < 18.6f) {
            if (t > 5.0f && t < 8.0f) kPose = K_GROOM;
            else if (t > 9.0f && t < 9.8f) kPose = K_MEW;
            if (t > 5.0f && t < 8.0f || t > 12.4f && t < 15.4f || t > 16.4f && t < 16.9f) kEyes = E_HAPPY;
        } else { kPose = K_WALK; kDir = 1; }
    }

    private void visitPose(float t) {
        visitState(t);
        face = t < 1.6f ? WOW : t < 3 ? HAPPY : t < 5 ? CARING : t < 8.8f ? SMILE : t < 10.6f ? WINK : t < 12 ? HAPPY : t < 15.6f ? CARING : SMILE;
        if (t < 22) look(kX - 12 * kDir, 640);
        if (t > 12.0f && t < 15.6f) pet(t, kX);
        if (t > 19.2f && t < 22.0f) gest = "wave";
    }

    private void visitDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        visitState(t);
        kitten(p, kX + out * 360, kY, KS, kDir, kPose, t * 9, kEyes, 0);
        if (t > 9.4f && t < 11.0f) { fa = pf * (1 - seg(t, 10.4f, 11.0f)); heart(p, kX - 14, 566 - 44 * seg(t, 9.4f, 11.0f), 1.4f, 0xFFFF7A9A); fa = pf; }
    }

    // ---------------------------------------------------------------- night 1. a firefly lands on his finger
    private static final float[] FF_X = {0, -60, 2.5f, 30, 4.5f, 90, 6.5f, 40, 8.0f, 112};
    private static final float[] FF_Y = {0, 200, 2.5f, 120, 4.5f, 220, 6.5f, 150, 8.0f, 196};
    private static final float[] FO_X = {15.0f, 0, 17.0f, 300, 19.0f, 480, 21.5f, 720};
    private static final float[] FO_Y = {15.0f, 0, 17.0f, 110, 19.0f, 70, 21.5f, -30};

    private void fireflyAt(float t) {
        if (t < 8.0f) { px = key(FF_X, t) + 6 * sin(t * 3.1f); py = key(FF_Y, t) + 5 * sin(t * 2.3f); return; }
        float fx, fy;
        if (!hidden[0] && "point".equals(hs[0])) { grip(0, 14, -124); fx = px; fy = py; } else { fx = 130; fy = 236; }
        if (t < 15.0f) { float u = ss(t, 8.0f, 9.0f); px = mix(112, fx, u); py = mix(196, fy, u); }
        else { float u = ss(t, 15.0f, 16.0f); px = mix(fx, key(FO_X, t), u) + 6 * sin(t * 3.1f); py = mix(fy, key(FO_Y, t), u); }
    }

    private void fireflyPose(float t) {
        face = t < 2.5f ? DROWSY : t < 9.0f ? NWONDER : t < 15.0f ? NSMILE : t < 19.0f ? NWONDER : NSLEEP;
        if (t > 2.5f && t < 19) { fireflyAt(t); look(px, py); }
        if (t > 5.0f && t < 16.4f) { arm(0, 116, 366, -6, "point", false); armK = 0.4f; }
    }

    private void fireflyDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        fireflyAt(t);
        float glow = 0.6f + 0.4f * sin(t * 2.4f);
        fa = pf * ss(t, 0, 1.5f) * (1 - ss(t, 20.5f, 21.8f));
        muted = false; // (its own soft light: warm, small, not bright)
        float w = 3 * Math.abs(sin(t * 18)), gy = py + 4;
        p.begin(); p.addOval(px - 6, py - 7, 5 + w * 0.6f, 3); p.addOval(px + 6, py - 7, 5 + w * 0.6f, 3); fill(p, 0x66C9D3E0);
        p.begin(); p.addOval(px, py - 6, 3, 4.6f); fill(p, 0xFF3E3A2A);
        p.begin(); p.addCircle(px, gy, 28); fill(p, BodyRig.alpha(0xFFD8F07A, 0.10f + 0.08f * glow));
        p.begin(); p.addCircle(px, gy, 14); fill(p, BodyRig.alpha(0xFFE4F590, 0.26f + 0.18f * glow));
        p.begin(); p.addCircle(px, gy, 6); fill(p, BodyRig.alpha(0xFFF4FBC0, 0.8f + 0.2f * glow));
        muted = true;
        fa = pf;
    }

    // ---------------------------------------------------------------- night 2. the kitten curls up asleep on his shoulder
    private static final float SHX = 452, SHY = 494;

    private void catnapPose(float t) {
        face = t < 4 ? DROWSY : t < 6 ? NWONDER : t < 14 ? NCARING : NSLEEP;
        if (t < 14) look(t < 4 ? key(CN_X, t) : SHX, t < 4 ? 650 : 460);
        if (t > 9 && t < 14) arm(1, 566 + 6 * sin(t * 2.2f), 446, -84, "side", false);
        armK = 0.5f;
    }

    private static final float[] CN_X = {0, 770, 4.0f, 566};

    private void catnapDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        fa = pf * (1 - ss(t, 26, 29.5f));
        float x, y;
        int pose;
        if (t < 4.0f) { x = key(CN_X, t); y = FLOOR; pose = K_WALK; }
        else if (t < 5.6f) { float u = ss(t, 4.0f, 5.6f); x = mix(566, SHX, u); y = mix(FLOOR, SHY, u) - 70 * sin(PI * u); pose = K_POUNCE; }
        else { x = SHX; y = SHY + bodyY; pose = t < 6.6f ? K_SIT : K_CURL; }
        kitten(p, x, y, pose == K_CURL ? 1.15f : 1.2f, -1, pose, t * (t < 4 ? 6 : 1.6f), pose == K_CURL ? E_SHUT : E_OPEN, 0);
        if (t > 15 && t < 27) { // a tiny zz from the kitten
            float u = ((t - 15) % 3) / 3;
            fa = pf * sin(PI * u) * 0.8f;
            p.text("z", SHX - 20 + 10 * u, SHY - 64 - 26 * u, 20, 0xFFB9C4E0);
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- night 3. a dream (a moon and stars)
    private void dreamPose(float t) {
        face = t < 2 ? DROWSY : t < 10 ? NSLEEP : t < 15 ? DREAMY : NSLEEP;
    }

    private void dreamDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        float a = ss(t, 2, 4) * (1 - ss(t, 21, 24));
        if (a <= 0) return;
        fa = pf * a;
        float s1 = ss(t, 2.0f, 2.6f), s2 = ss(t, 2.6f, 3.2f), s3 = ss(t, 3.2f, 3.8f), big = ss(t, 3.8f, 5.0f);
        p.begin(); p.addCircle(438, 168, 7 * s1); p.addCircle(462, 140, 11 * s2); p.addCircle(486, 116, 15 * s3); fill(p, 0x40C8D3F0);
        if (big > 0) {
            at(p, 540, 98, 0, big * 1.25f, 1);
            p.begin();
            p.addCircle(-36, 6, 34); p.addCircle(0, -14, 40); p.addCircle(38, 2, 36); p.addCircle(10, 30, 34); p.addCircle(-26, 34, 26);
            fill(p, 0x40C8D3F0);
            // the moon, rocking; the stars, twinkling
            p.save(); p.rotate(8 * sin(t * 0.8f));
            p.begin(); mv(p, -6, -32); cu(p, -40, -26, -40, 26, -6, 30); cu(p, -24, 18, -24, -20, -6, -32); p.close(); fill(p, 0xFFE6DCA8);
            p.restore();
            star(p, 30, -20, 9 * (0.8f + 0.2f * sin(t * 2.0f)), 0xFFD8D0A0);
            star(p, 44, 22, 6 * (0.8f + 0.2f * sin(t * 2.6f + 1)), 0xFFD8D0A0);
            star(p, 8, 36, 5 * (0.8f + 0.2f * sin(t * 1.7f + 2)), 0xFFD8D0A0);
            end(p);
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- night 4. counting stars
    private static final float[][] STAR = {{-4, 130}, {84, 44}, {30, 262}, {524, 50}, {604, 170}};

    private void starsPose(float t) {
        int k = t < 3.4f ? -1 : (int) ((t - 3.4f) / 2.6f);
        face = t < 2 ? DROWSY : k >= 0 && k < 5 ? ((((int) (t * 2.5f)) % 2 == 0) ? COUNT1 : COUNT2) : t < 16.4f ? NWONDER : t < 19 ? NYAWN : NSLEEP;
        if (k >= 0 && k < 5) {
            float sx = STAR[k][0], sy = STAR[k][1];
            int side = k < 3 ? 0 : 1;
            float shx = side == 0 ? 158 : 442, dx = sx - shx, dy = sy - 530, d = (float) Math.sqrt(dx * dx + dy * dy);
            arm(side, shx + dx / d * 262, 530 + dy / d * 262, (float) Math.toDegrees(Math.atan2(dx, -dy)), "point", false);
            armK = 0.6f;
            look(sx, sy);
        } else if (t > 2 && t < 3.4f) look(STAR[0][0], STAR[0][1]);
    }

    private void starsDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != BACK) return;
        float a = 1 - ss(t, 20, 23.5f);
        int k = t < 3.4f ? -1 : (int) ((t - 3.4f) / 2.6f);
        for (int i = 0; i < 5; i++) {
            float on = ss(t, 0.5f + i * 0.5f, 1.5f + i * 0.5f);
            if (on <= 0) continue;
            fa = pf * a * on * (0.7f + 0.3f * sin(t * 1.5f + i));
            float big = i == k ? 1.35f : 1;
            star(p, STAR[i][0], STAR[i][1], 15 * big, 0xFFE2DAB0);
            if (k >= i && t > 3.4f + i * 2.6f + 0.6f) {
                fa = pf * a * 0.7f;
                p.text(DIGITS[i], STAR[i][0] + 16, STAR[i][1] + 32, 28, 0xFFB9C4E0);
            }
        }
        fa = pf;
    }

    private static final String[] DIGITS = {"1", "2", "3", "4", "5"};

    // ---------------------------------------------------------------- night 5. snoring under a blanket
    private void snorePose(float t) {
        face = t < 3 ? DROWSY : (((int) ((t - 3) / 2)) % 2 == 0 ? SNORE1 : SNORE2);
    }

    private void snoreDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != MID) return;
        float a = ss(t, 0.5f, 3.0f) * (1 - ss(t, 27, 29.5f));
        if (a <= 0) return;
        fa = pf * a;
        float y = bodyY + 80 * (1 - ss(t, 0.5f, 3.0f)); // (it rises with his breath)
        // the quilt, over his shoulders up to the neck
        quilt(p, y); fill(p, 0xFF4E5D80);
        p.save();
        p.clip();
        p.begin(); // patchwork squares, two muted colours
        for (int i = 0; i < 8; i++) for (int j = 0; j < 5; j++) if ((i + j) % 2 == 0) rr(p, 56 + i * 62, y + 470 + j * 62, 62, 62, 0);
        fill(p, 0xFF5E6E94);
        p.begin();
        for (int i = 0; i < 8; i++) for (int j = 0; j < 5; j++) if ((i + j) % 4 == 1) { p.addCircle(87 + i * 62, y + 501 + j * 62, 9); }
        fill(p, 0xFF7A6A92);
        p.begin(); // the stitching
        for (int i = 1; i < 8; i++) { mv(p, 56 + i * 62, y + 470); ln(p, 56 + i * 62, y + 760); }
        for (int j = 1; j < 5; j++) { mv(p, 40, y + 470 + j * 62); ln(p, 560, y + 470 + j * 62); }
        line(p, 0x33D6DCEB, 2);
        p.restore();
        quilt(p, y); line(p, 0xFF39445E, 3);
        // its folded top edge
        p.begin(); mv(p, 92, y + 592); cu(p, 110, y + 530, 170, y + 500, 238, y + 488); cu(p, 270, y + 484, 330, y + 484, 362, y + 488); cu(p, 430, y + 500, 490, y + 530, 508, y + 592);
        line(p, 0xFF8D9AC0, 16); line(p, 0xFF707FA6, 9);
        fa = pf;
    }

    private void quilt(BodyRig.Pen p, float y) {
        p.begin(); mv(p, 70, 726); ln(p, 80, y + 600); cu(p, 96, y + 530, 160, y + 494, 236, y + 482); cu(p, 270, y + 476, 330, y + 476, 364, y + 482);
        cu(p, 440, y + 494, 504, y + 530, 520, y + 600); ln(p, 530, 726); p.close();
    }

    // ---------------------------------------------------------------- night 6. humming (soft notes float up)
    private void humPose(float t) {
        face = t < 2 ? DROWSY : t < 18 ? (((int) ((t - 2) / 1.5f)) % 2 == 0 ? HUM1 : HUM2) : NSLEEP;
    }

    private void humDraw(BodyRig.Pen p, float t, int layer) {
        if (layer != FRONT) return;
        for (int i = 0; i < 7; i++) {
            float born = 2 + i * 2.3f, k = t - born;
            if (k < 0 || k > 5) continue;
            float u = k / 5;
            fa = pf * sin(PI * u) * 0.9f;
            note(p, 462 + 110 * u + 14 * sin(k * 2 + i), 360 - 240 * u, i % 2 == 0 ? 0xFFC9BAEA : 0xFFB0C9EA, i % 3 == 1);
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- on demand: a meal
    private void eatPose(float t) {
        int bite = t < 2 ? -1 : (int) ((t - 2) / 5);
        float c = t < 2 ? 0 : (t - 2) % 5;
        if (bite < 0 || bite > 2) face = t < 2 ? SMILE : t < 18.4f ? PROUD : SMILE;
        else face = c < 1.2f ? SMILE : c < 1.8f ? HAPPY : c < 2.4f ? EATOPEN : c < 4.6f ? (((int) (c / 0.35f)) % 2 == 0 ? CHEW1 : CHEW2) : HAPPY;
        if (t > 0.2f && t < 18.6f) arm(1, 444, 626, -80, "open", true);
        if (t > 0.2f && t < 17.2f) {
            if (bite >= 0 && bite < 3 && c < 1.2f) { arm(0, 210, 610, 96, "hold", true); look(350, 600); }
            else if (bite >= 0 && bite < 3 && c < 2.6f) arm(0, 186, 418, 58, "hold", true);
            else arm(0, 200, 600, 70, "hold", true);
        }
    }

    private void eatDraw(BodyRig.Pen p, float t, int layer) {
        int bite = t < 2 ? -1 : (int) ((t - 2) / 5);
        float c = t < 2 ? 0 : (t - 2) % 5;
        float food = bite < 0 ? 1 : bite > 2 ? 0 : 1 - (bite + (c > 2.0f ? 1 : 0)) / 3f;
        if (layer == MID && !hidden[0]) { // the spoon (rice in it from the plate to his mouth)
            fa = pf * ss(t, 0.2f, 0.8f) * (1 - ss(t, 17.2f, 18.0f));
            grip(0, 0, -6); float x0 = px, y0 = py;
            grip(0, 0, -128);
            p.begin(); p.moveTo(x0, y0); p.lineTo(px, py); line(p, 0xFF8A939E, 8); line(p, 0xFFD4D9E0, 5);
            grip(0, 0, -144);
            p.begin(); p.addOval(px, py, 13, 17); fill(p, 0xFFD4D9E0); line(p, 0xFF8A939E, 2);
            if (bite >= 0 && bite < 3 && c > 1.0f && c < 2.1f) { p.begin(); p.addOval(px, py - 3, 10, 9); fill(p, 0xFFFFFDF5); }
        }
        if (layer == FRONT && !hidden[1]) { // the plate on his fingers
            fa = pf * ss(t, 0.2f, 0.8f) * (1 - ss(t, 18.0f, 18.8f));
            grip(1, 0, -52);
            at(p, px - 22, py - 10, 0, 1.3f, 1);
            p.begin(); ov(p, 0, 0, 66, 17); fill(p, 0xFFF4F1EA); line(p, 0xFFC9C3B6, 2.5f);
            p.begin(); ov(p, 0, -1, 48, 11); line(p, 0xFFDCD6C9, 1.5f);
            if (food > 0.05f) {
                p.begin(); ov(p, -6, -6 * food, 30 * food + 6, 12 * food + 3); fill(p, 0xFFFFFDF5); line(p, 0xFFE4DED0, 1.2f);
                p.begin(); ov(p, 22, -3, 14 * food + 2, 6 * food + 2); fill(p, 0xFFF2B33D);
                p.begin(); circ(p, -14, -12 * food, 3); circ(p, 2, -10 * food, 2.6f); fill(p, 0xFF6DBE5B);
            }
            end(p);
        }
        fa = pf;
    }

    // ---------------------------------------------------------------- on demand: a birthday cake
    private void cakePose(float t) {
        face = t < 2 ? HAPPY : t < 6 ? (((int) (t / 1.2f)) % 2 == 0 ? EXCITED : HAPPY) : t < 7.8f ? BLOW : t < 9 ? EXCITED : t < 11 ? LAUGH
                : t < 16 ? CARING : SMILE;
        if (t < 8) look(300, 500);
        if (t > 0.1f && t < 17.4f) { arm(0, 180, 680, 22, "hold", true); arm(1, 420, 680, -22, "hold", true); }
    }

    private void cakeDraw(BodyRig.Pen p, float t, int layer) {
        float a = ss(t, 0.1f, 0.8f) * (1 - ss(t, 16.4f, 17.2f));
        if (layer == MID && a > 0 && !hidden[0] && !hidden[1]) {
            fa = pf * a;
            grip(0, 0, -38); float x0 = px, y0 = py;
            grip(1, 0, -38);
            at(p, (x0 + px) / 2, (y0 + py) / 2 - 68, 0, 1.25f, 1);
            float cx = 0, cy = 0;
            // the plate, the cake, the icing, three candles
            p.begin(); p.addOval(cx, cy + 40, 96, 13); fill(p, 0xFFE6E9EE); line(p, 0xFFAEB5BF, 2);
            p.begin(); mv(p, cx - 76, cy - 30); ln(p, cx - 76, cy + 30); cu(p, cx - 40, cy + 46, cx + 40, cy + 46, cx + 76, cy + 30); ln(p, cx + 76, cy - 30); p.close();
            fill(p, 0xFFF3C98B); line(p, 0xFFB8864D, 2.5f);
            p.begin(); mv(p, cx - 76, cy + 4); cu(p, cx - 40, cy + 18, cx + 40, cy + 18, cx + 76, cy + 4); line(p, 0xFFF59AB8, 6);
            p.begin(); mv(p, cx - 78, cy - 30); cu(p, cx - 78, cy - 10, cx - 66, cy - 6, cx - 62, cy - 16); cu(p, cx - 56, cy - 2, cx - 44, cy - 4, cx - 40, cy - 14);
            cu(p, cx - 32, cy - 2, cx - 16, cy - 2, cx - 10, cy - 14); cu(p, cx, cy, cx + 16, cy, cx + 22, cy - 14); cu(p, cx + 30, cy - 2, cx + 44, cy - 2, cx + 50, cy - 14);
            cu(p, cx + 58, cy - 4, cx + 76, cy - 6, cx + 78, cy - 30); p.close(); fill(p, 0xFFFFF0F5);
            p.begin(); p.addOval(cx, cy - 30, 78, 16); fill(p, 0xFFFFF0F5); line(p, 0xFFE8B9CB, 2);
            float out = ss(t, 7.0f, 7.6f);
            for (int i = 0; i < 3; i++) {
                float x = cx - 36 + 36 * i, y = cy - 36;
                p.begin(); rr(p, x - 5, y - 40, 10, 40, 3); fill(p, CANDLE[i]);
                p.begin(); mv(p, x - 5, y - 30); ln(p, x + 5, y - 34); mv(p, x - 5, y - 16); ln(p, x + 5, y - 20); line(p, 0xCCFFFFFF, 2.5f);
                if (out < 1) {
                    float lean = t > 6 && t < 7.6f ? -6 * ss(t, 6, 6.6f) : 2 * sin(t * 9 + i), fl = (1 - out) * (0.9f + 0.1f * sin(t * 13 + i * 2));
                    p.begin(); mv(p, x, y - 64 * fl - 40 + 40 * (1 - fl)); cu(p, x + 9 * fl + lean, y - 50, x + 7 * fl, y - 42, x, y - 42); cu(p, x - 7 * fl, y - 42, x - 9 * fl + lean, y - 50, x, y - 64 * fl - 40 + 40 * (1 - fl));
                    p.close(); fill(p, 0xFFFFC94D);
                    p.begin(); p.addOval(x + lean * 0.4f, y - 48, 3.4f * fl, 6 * fl); fill(p, 0xFFFFF4C0);
                }
                if (t > 7.4f && t < 10) { // smoke
                    float u = seg(t, 7.4f, 10);
                    fa = pf * a * (1 - u) * 0.8f;
                    p.begin(); mv(p, x, y - 44); cu(p, x + 10, y - 60 - 30 * u, x - 10, y - 74 - 40 * u, x + 4 * sin(t * 3 + i), y - 90 - 50 * u); line(p, 0x99D0D6DF, 3);
                    fa = pf * a;
                }
            }
            end(p);
            fa = pf;
        }
        if (layer == FRONT && t > 7.6f && t < 12) { // confetti
            for (int i = 0; i < 14; i++) {
                float u = seg(t, 7.6f + (i % 4) * 0.15f, 12), x = 120 + i * 29 + 20 * sin(t * 2 + i), y = 120 + 520 * u + (i % 3) * 30;
                fa = pf * (1 - u);
                p.begin(); p.addOval(x, y, 4, 7); fill(p, CONFETTI[i % 5]);
            }
            fa = pf;
        }
    }

    private static final int[] CANDLE = {0xFF8FD3FF, 0xFFFFD166, 0xFFB39DFF};
    private static final int[] CONFETTI = {0xFFFF7A9A, 0xFFFFD166, 0xFF5EC8F2, 0xFF8BD17C, 0xFFB39DFF};

    // ================================================================ reactions (tickle, high-five, bye), dance, the door
    private void momentPose(boolean listening) {
        float t = mt;
        switch (moment) {
            case TICKLE: {
                float env = ss(t, 0, 0.2f) * (1 - ss(t, 2.1f, 2.5f));
                offsets = true;
                bodyDx = 3.2f * env * sin(T * 48); bodyDy = 1.5f * env * Math.abs(sin(T * 31));
                headDx = 3 * env * sin(T * 48 + 0.8f); headTilt = 4 * env * sin(T * 24);
                if (!listening) face = GIGGLE;
                break;
            }
            case HIGH5:
                if (!listening) face = t < 0.5f ? HAPPY : EXCITED;
                if (t < 1.5f) { arm(0, t < 0.45f ? 104 : 112, t < 0.45f ? 352 : 338, -10, "open", false); armK = 1.8f; }
                break;
            case BYE:
                if (!listening) face = t < 1.2f ? SADSMALL : WARM;
                gest = "wave";
                break;
            default: break;
        }
    }

    private void highFiveBurst(BodyRig.Pen p) {
        if (mt < 0.45f || mt > 1.0f || hidden[0]) return;
        float u = seg(mt, 0.45f, 1.0f);
        grip(0, 0, -52);
        fa = 1 - u;
        p.begin();
        for (int i = 0; i < 8; i++) {
            double a = i * PI / 4 + 0.2;
            float c = (float) Math.cos(a), s = (float) Math.sin(a), r0 = 74 + 20 * u, r1 = 92 + 30 * u;
            p.moveTo(px + c * r0, py + s * r0); p.lineTo(px + c * r1, py + s * r1);
        }
        line(p, 0xFFFFE37A, 4);
        fa = 1;
    }

    private void dancePose(boolean otherFace) {
        float b = T / 0.6f; // 100 beats a minute
        float sw = sin(PI * b / 2), bob = Math.abs(sin(PI * b));
        offsets = true;
        headDy = 6 * bob; headTilt = 5 * sw;
        if (!otherFace) face = ((int) (b / 4)) % 2 == 0 ? DANCE1 : DANCE2;
        // hands low and loose, swaying together from side to side
        arm(0, 196 + 26 * sw, 676 - 10 * bob, 12 + 14 * sw, "open", true);
        arm(1, 404 + 26 * sw, 676 - 10 * bob, -12 + 14 * sw, "open", true);
    }

    private void doorPose() {
        float c = T % 5.5f;
        boolean glance = c < 2.6f;
        face = glance ? EXPECT : EXPECT0;
        if (glance) { gaze = true; gx = 1.25f; gy = 0.1f; }
    }

    private float mt2; // the brow wipe's clock

    private void wearPose(BodyRig r) {
        boolean day = !"sleepy".equals(r.base());
        if (outfit == FAN && day) {
            float c = T % 14f;
            if (c > 9 && c < 11.2f) { face = PHEW; wipe = true; mt2 = c - 9; } else face = HOT;
        }
        if (occasion == SANKRANTI && held() == I_KITE && day) { // a look up at the kite now and then
            float c = T % 9f;
            if (c < 3) { gaze = true; gx = 1.1f; gy = -1; }
        }
    }

    // ================================================================ outfits and occasions
    private void holdPose(int it, BodyRig.Arm L) {
        switch (it) {
            case I_UMBRELLA: L.set(470, 424, -24, "hold", true); break;
            case I_KITE: L.set(488, 474 + 6 * sin(T * 2.2f), 10 + 4 * sin(T * 1.7f), "hold", true); break;
            case I_FAN: L.set(484, 452, -16 + 16 * sin(T * 2.4f), "hold", true); break;
            default: break;
        }
    }

    private void heldThing(BodyRig.Pen p, int it) {
        if (hidden[1]) return;
        switch (it) {
            case I_UMBRELLA: {
                grip(1, 0, 6); float x0 = px, y0 = py;
                grip(1, 0, -400); float x1 = px, y1 = py;
                // the handle's hook below the fist, the shaft, the canopy
                grip(1, 0, 20);
                p.begin(); p.moveTo(x0, y0); p.quadTo(px, py, px - 14, py + 6); line(p, 0xFF6B4528, 9); line(p, 0xFF8A5A3B, 6);
                p.begin(); p.moveTo(x0, y0); p.lineTo(x1, y1); line(p, 0xFF4A4A52, 6);
                at(p, x1, y1, ha[1], 1, 1);
                p.begin(); mv(p, -186, 40); cu(p, -170, -64, 170, -64, 186, 40);
                for (int i = 0; i < 6; i++) { float a = 186 - i * 62f, b = a - 62; qu(p, (a + b) / 2, 22, b, 40); }
                p.close();
                fill(p, 0xFF3FA7D6); line(p, 0xFF1F5F7E, 3);
                p.begin(); mv(p, -62, 40); qu(p, -60, -20, 0, -38); qu(p, 60, -20, 62, 40); qu(p, 31, 22, 0, 40); qu(p, -31, 22, -62, 40); p.close();
                fill(p, 0xFFF2C14E);
                p.begin(); mv(p, 0, -38); ln(p, 0, -50); line(p, 0xFF4A4A52, 5);
                p.begin(); mv(p, -186, 40); cu(p, -170, -64, 170, -64, 186, 40); line(p, 0xFF1F5F7E, 3);
                end(p);
                break;
            }
            case I_KITE: {
                grip(1, 0, -74);
                p.begin(); p.moveTo(px, py); p.quadTo(px + 120, py - 120, 700, -60); line(p, 0xDDF2F2F2, 1.8f);
                break;
            }
            case I_FAN: {
                grip(1, 0, -4); float x0 = px, y0 = py;
                grip(1, 0, -84); float x1 = px, y1 = py;
                p.begin(); p.moveTo(x0, y0); p.lineTo(x1, y1); line(p, 0xFF6B4528, 8); line(p, 0xFF8A5A3B, 5);
                grip(1, 0, -122);
                at(p, px, py, ha[1], 1, 1);
                p.begin(); ov(p, 0, 0, 36, 42); fill(p, 0xFFE7C98F); line(p, 0xFF9C6B3C, 3.5f);
                p.begin(); for (int i = -3; i <= 3; i++) { mv(p, 0, 38); ln(p, i * 11, -38 + Math.abs(i) * 5); } line(p, 0xFFB8935A, 1.6f);
                p.begin(); ov(p, 0, 0, 30, 36); line(p, 0x66B8935A, 1.4f);
                end(p);
                break;
            }
            default: break;
        }
    }

    /** The cold-day sweater's pattern and a muffler (on the knit LOOK BodyRig draws). */
    private void sweater(BodyRig.Pen p) {
        // a cream band of little diamonds across the chest
        p.begin(); mv(p, 110, 560); qu(p, 300, 548, 490, 560); ln(p, 494, 588); qu(p, 300, 576, 106, 588); p.close(); fill(p, 0xFFF1E4CC);
        p.begin();
        for (int i = 0; i < 12; i++) { float x = 126 + i * 32; mv(p, x, 574 - (i == 0 || i == 11 ? 0 : 1)); ln(p, x + 8, 566); ln(p, x + 16, 574); ln(p, x + 8, 582); p.close(); }
        fill(p, 0xFF9C3D4A);
        // ribbing lines lower down
        p.begin(); for (int i = 0; i < 9; i++) { float x = 150 + i * 38; mv(p, x, 610); ln(p, x - 2, 720); } line(p, 0x336E2531, 3);
        // the muffler round the neck, one end hanging
        p.begin(); mv(p, 238, 456); cu(p, 270, 486, 330, 486, 362, 456); ln(p, 370, 482); cu(p, 334, 514, 266, 514, 230, 482); p.close();
        fill(p, 0xFFE0A33A); line(p, 0xFF9C6A1E, 2.5f);
        p.begin(); mv(p, 262, 496); ln(p, 252, 610); ln(p, 290, 612); ln(p, 292, 502); p.close(); fill(p, 0xFFE0A33A); line(p, 0xFF9C6A1E, 2.5f);
        p.begin(); mv(p, 258, 540); ln(p, 291, 542); mv(p, 255, 570); ln(p, 290, 572); line(p, 0xFFF6EBD5, 6);
        p.begin(); for (int i = 0; i < 5; i++) { mv(p, 255 + i * 8.5f, 612); ln(p, 254 + i * 8.5f, 628); } line(p, 0xFFE0A33A, 3);
        p.begin(); mv(p, 250, 468); cu(p, 280, 490, 320, 490, 350, 468); line(p, 0x66F6EBD5, 5);
    }

    private void pocketFlower(BodyRig.Pen p) {
        at(p, 370, 572, 0, 1.45f, 1);
        float x = 0, y = 0;
        p.begin(); mv(p, x, y + 30); qu(p, x - 4, y + 10, x + 2, y - 4); line(p, 0xFF3F7F3B, 5); line(p, 0xFF5FAE5A, 3);
        leaf(p, x - 1, y + 12, -1, 10);
        leaf(p, x + 1, y + 16, 1, 8);
        p.begin(); for (int i = 0; i < 5; i++) { double a = -PI / 2 + i * 2 * PI / 5; circ(p, x + 2 + 9 * (float) Math.cos(a), y - 8 + 9 * (float) Math.sin(a), 6.5f); }
        fill(p, 0xFFFFD45C); line(p, 0xFFC99A2E, 1.2f);
        p.begin(); circ(p, x + 2, y - 8, 5); fill(p, 0xFFF28C38);
        p.begin(); rr(p, x - 22, y + 22, 44, 28, 4); fill(p, 0x33000000); line(p, 0x66FFFFFF, 1.6f);
        end(p);
    }

    /** The day's hat, in head units (hair top at about y = -205). */
    private void hat(BodyRig.Pen p) {
        switch (occasion) {
            case CHRISTMAS: { // a red woolly cap with a cream band; its pom-pom flops to the side (the picture ends above his hair)
                p.begin(); mv(p, -114, -98); cu(p, -124, -170, -70, -206, 0, -206); cu(p, 70, -206, 124, -170, 114, -98); qu(p, 0, -126, -114, -98); p.close();
                fill(p, 0xFFD8434F); line(p, 0xFFA52A36, 3);
                p.begin(); for (int i = -3; i <= 3; i++) { mv(p, i * 30, -114 + Math.abs(i) * 3); qu(p, i * 32, -160, i * 20, -200); } line(p, 0x55A52A36, 3);
                p.begin(); mv(p, -118, -92); qu(p, 0, -124, 118, -92); ln(p, 116, -120); qu(p, 0, -152, -116, -120); p.close();
                fill(p, 0xFFF3E9DA); line(p, 0xFFCDBFA8, 2.5f);
                p.begin(); for (int i = -5; i <= 5; i++) { mv(p, i * 20, -110 + Math.abs(i) * 1.6f); ln(p, i * 20, -132 + Math.abs(i) * 2.2f); } line(p, 0x44A08F74, 2.5f);
                p.begin(); mv(p, 40, -198); qu(p, 96, -214, 112, -176); line(p, 0xFFA52A36, 14); line(p, 0xFFD8434F, 9);
                p.begin(); p.addCircle(116, -168, 21); fill(p, 0xFFF7F1E6); line(p, 0xFFCDBFA8, 2.5f);
                break;
            }
            case NEWYEAR: case BIRTHDAY: { // a small party hat, worn at a slant on one side
                boolean b = occasion == BIRTHDAY;
                p.save(); p.translate(80, -142); p.rotate(50);
                p.begin(); mv(p, -32, 0); ln(p, 0, -80); ln(p, 32, 0); qu(p, 0, 9, -32, 0); p.close();
                fill(p, b ? 0xFFFF7EB6 : 0xFF5BC0EB); line(p, b ? 0xFFC24C85 : 0xFF2E7FA6, 3);
                p.begin();
                if (b) { p.addCircle(-10, -16, 4.5f); p.addCircle(9, -34, 4.5f); p.addCircle(-2, -54, 3.6f); p.addCircle(15, -10, 3.6f); fill(p, 0xFFFFFFFF); }
                else { mv(p, -24, -18); ln(p, 23, -10); mv(p, -16, -42); ln(p, 14, -37); line(p, 0xFFFDE74C, 5); }
                p.begin(); p.addCircle(0, -84, 10); fill(p, b ? 0xFFFFD166 : 0xFFFF5E78);
                p.begin(); mv(p, -30, 2); qu(p, 0, 12, 30, 2); line(p, 0x66FFFFFF, 2);
                p.restore();
                break;
            }
            default: break;
        }
    }

    // ================================================================ the things, drawn (shapes in a local frame; mirrored by dir)
    private static final int K_SIT = 0, K_WALK = 1, K_CROUCH = 2, K_POUNCE = 3, K_CURL = 4, K_GROOM = 5, K_MEW = 6, K_BAT = 7;
    private static final int E_OPEN = 0, E_HAPPY = 1, E_SHUT = 2;
    // the kitten: cream with ginger ears, a patch and a ginger tail
    private static final int KB = 0xFFF6E2C0, KB2 = 0xFFE3C9A2, KL = 0xFF8C5A30, KP = 0xFFEFA04F, KW = 0xFFFFF4E2, KE = 0xFFF4AFA2,
            KI = 0xFF2B1B10, KN = 0xFFE9808A;

    /** The kitten standing at (x, y) (its feet), facing d (1: right), in a pose; ph: its walk / breath clock. */
    private void kitten(BodyRig.Pen p, float x, float y, float s, float d, int pose, float ph, int eyes, float rot) {
        at(p, x, y, 0, s, d);
        if (rot != 0) { p.translate(0, -34); p.rotate(rot * d); p.translate(0, 34); }
        float sw = sin(ph * 0.8f) * 6;
        if (pose == K_CURL) {
            float br = 1 + 0.035f * sin(ph * 0.9f);
            p.begin(); ov(p, 0, -24 * br, 40, 24 * br); line(p, KL, 5); fill(p, KB);
            p.begin(); ov(p, -14, -32 * br, 13, 9); fill(p, KP);
            p.begin(); mv(p, -36, -10); cu(p, -34, 6, 16, 8, 36, -4); line(p, KL, 12); line(p, KP, 7.5f);
            head(p, 22, -30 * br, 0.8f, E_SHUT, false);
        } else if (pose == K_SIT || pose == K_GROOM || pose == K_MEW || pose == K_BAT) {
            p.begin(); mv(p, -22, -10); cu(p, -46, -8, -58, -34, -50 + sw * 0.4f, -54 + sw); line(p, KL, 12); line(p, KP, 7.5f);
            leg(p, 17, -26, 18, -6, KB2);
            p.begin(); ov(p, -12, -22, 24, 21); ov(p, 2, -36, 19, 27); line(p, KL, 5);
            p.begin(); ov(p, -12, -22, 24, 21); ov(p, 2, -36, 19, 27); fill(p, KB);
            p.begin(); ov(p, -17, -30, 11, 9); fill(p, KP);
            p.begin(); ov(p, 9, -34, 10, 17); fill(p, KW);
            p.begin(); ov(p, -24, -5, 11, 5); line(p, KL, 4); fill(p, KB);
            if (pose == K_BAT) leg(p, 6, -28, 28, -40, KB); else if (pose != K_GROOM) leg(p, 5, -26, 5, -6, KB);
            head(p, 10, -70, 1, eyes, pose == K_MEW);
            if (pose == K_GROOM) leg(p, 6, -30, 12, -58, KB);
        } else {
            boolean po = pose == K_POUNCE, cr = pose == K_CROUCH;
            float by = cr ? -22 : po ? -36 : -34, bx = po ? 38 : 30, bry = cr ? 13 : po ? 14 : 16;
            float hx = po ? 44 : cr ? 32 : 34, hy = po ? -50 : cr ? -36 : -58;
            float swing = pose == K_WALK ? sin(ph) * 8 : 0;
            p.begin();
            if (po) { mv(p, -bx + 4, by - 2); cu(p, -bx - 14, by - 6, -bx - 28, by - 12, -bx - 42, by - 8); }
            else if (cr) { mv(p, -bx + 4, by - 4); cu(p, -bx - 8, by - 22, -bx + sw, by - 40, -bx + 8 + sw * 1.5f, by - 50); }
            else { mv(p, -bx + 4, by - 6); cu(p, -bx - 12, by - 14, -bx - 18, by - 36, -bx - 10 + sw * 0.4f, by - 48 + sw * 0.5f); }
            line(p, KL, 12); line(p, KP, 7.5f);
            float fx = bx * 0.55f, rx = -bx * 0.55f;
            if (po) { leg(p, fx - 4, by + 4, fx + 16, by + 20, KB2); leg(p, rx + 4, by + 4, rx - 18, by + 18, KB2); }
            else { leg(p, fx - 6, by + 6, fx - 6 - swing, -6, KB2); leg(p, rx - 6, by + 6, rx - 6 + swing, -6, KB2); }
            p.begin(); ov(p, 0, by, bx, bry); line(p, KL, 5); fill(p, KB);
            p.begin(); ov(p, -bx * 0.35f, by - 6, 11, 7); fill(p, KP);
            if (po) { leg(p, fx + 4, by + 4, fx + 24, by + 18, KB); leg(p, rx - 4, by + 4, rx - 26, by + 16, KB); }
            else { leg(p, fx + 2, by + 6, fx + 2 + swing, -6, KB); leg(p, rx + 2, by + 6, rx + 2 - swing, -6, KB); }
            head(p, hx, hy, 0.95f, eyes, false);
        }
        end(p);
    }

    private void leg(BodyRig.Pen p, float x1, float y1, float x2, float y2, int c) {
        p.begin(); mv(p, x1, y1); ln(p, x2, y2); line(p, KL, 13); line(p, c, 9);
        p.begin(); ov(p, x2 + 1.5f, y2 + 1, 6.5f, 4.5f); line(p, KL, 3); fill(p, KW);
    }

    private void head(BodyRig.Pen p, float hx, float hy, float k, int eyes, boolean mouth) {
        // ears and head: the outline first, then the fill
        earL(p, hx, hy, k); earR(p, hx, hy, k); line(p, KL, 5);
        p.begin(); ov(p, hx, hy, 27 * k, 23 * k); line(p, KL, 5);
        earL(p, hx, hy, k); fill(p, KB);
        earR(p, hx, hy, k); fill(p, KP);
        p.begin(); ov(p, hx, hy, 27 * k, 23 * k); fill(p, KB);
        p.begin();
        mv(p, hx - 18 * k, hy - 13 * k); ln(p, hx - 17 * k, hy - 30 * k); ln(p, hx - 8 * k, hy - 19 * k); p.close();
        mv(p, hx + 8 * k, hy - 19 * k); ln(p, hx + 17 * k, hy - 30 * k); ln(p, hx + 18 * k, hy - 13 * k); p.close();
        fill(p, KE);
        p.begin(); ov(p, hx + 9 * k, hy - 14 * k, 9 * k, 6 * k); fill(p, KP);
        p.begin(); ov(p, hx, hy + 8 * k, 12 * k, 8.5f * k); fill(p, KW);
        float ex = 9.5f * k, ey = hy - 3 * k;
        if (eyes == E_OPEN) {
            p.begin(); circ(p, hx - ex, ey, 5.2f * k); circ(p, hx + ex, ey, 5.2f * k); fill(p, KI);
            p.begin(); circ(p, hx - ex - 1.6f * k, ey - 2 * k, 1.8f * k); circ(p, hx + ex - 1.6f * k, ey - 2 * k, 1.8f * k); fill(p, 0xFFFFFFFF);
        } else {
            float b = eyes == E_HAPPY ? -5 : 3;
            p.begin();
            mv(p, hx - ex - 5 * k, ey); qu(p, hx - ex, ey + b * k, hx - ex + 5 * k, ey);
            mv(p, hx + ex - 5 * k, ey); qu(p, hx + ex, ey + b * k, hx + ex + 5 * k, ey);
            line(p, KI, 2.2f * k);
        }
        p.begin(); mv(p, hx - 3.5f * k, hy + 3 * k); ln(p, hx + 3.5f * k, hy + 3 * k); ln(p, hx, hy + 6.5f * k); p.close(); fill(p, KN);
        if (mouth) { p.begin(); ov(p, hx, hy + 12 * k, 4 * k, 3.6f * k); fill(p, 0xFF9C3F4A); }
        else {
            p.begin(); mv(p, hx, hy + 6.5f * k); qu(p, hx, hy + 10 * k, hx - 4.5f * k, hy + 10 * k);
            mv(p, hx, hy + 6.5f * k); qu(p, hx, hy + 10 * k, hx + 4.5f * k, hy + 10 * k); line(p, KL, 1.6f * k);
        }
        p.begin();
        mv(p, hx - 13 * k, hy + 5 * k); ln(p, hx - 31 * k, hy + 2 * k); mv(p, hx - 13 * k, hy + 9 * k); ln(p, hx - 30 * k, hy + 12 * k);
        mv(p, hx + 13 * k, hy + 5 * k); ln(p, hx + 31 * k, hy + 2 * k); mv(p, hx + 13 * k, hy + 9 * k); ln(p, hx + 30 * k, hy + 12 * k);
        line(p, 0xCCFFF6E8, 1.3f);
    }

    private void earL(BodyRig.Pen p, float hx, float hy, float k) {
        p.begin(); mv(p, hx - 24 * k, hy - 6 * k); ln(p, hx - 21 * k, hy - 38 * k); ln(p, hx - 4 * k, hy - 21 * k); p.close();
    }

    private void earR(BodyRig.Pen p, float hx, float hy, float k) {
        p.begin(); mv(p, hx + 4 * k, hy - 21 * k); ln(p, hx + 21 * k, hy - 38 * k); ln(p, hx + 24 * k, hy - 6 * k); p.close();
    }

    // the mouse: small and grey, pink ears and nose
    private static final int MB = 0xFFAAB3BE, MBL = 0xFFDCE1E7, ML = 0xFF5F6874, ME = 0xFFF5BDC8, MN = 0xFFF08A9C, MT = 0xFFD7A6B0, MI = 0xFF1E232B;

    private void mouseSide(BodyRig.Pen p, float x, float y, float d, float run, float headUp, float t) {
        at(p, x, y, 0, MS, d);
        float st = sin(run) * 3, hy = -14 - headUp, sn = sin(t * 18) * 0.8f;
        p.begin(); mv(p, -17, -8); cu(p, -32, -4, -40, -20 + st, -58, -14 + st); line(p, MT, 3);
        p.begin(); ov(p, 8 + st, -2, 5, 2.6f); ov(p, -8 - st, -2, 5, 2.6f); fill(p, 0xFFE9A9B4);
        p.begin(); ov(p, 0, -11, 18, 11); line(p, ML, 4); fill(p, MB);
        p.begin(); mv(p, 6, hy - 6); cu(p, 20, hy - 7, 28, hy + 1, 32, hy + 6); cu(p, 26, hy + 11, 14, hy + 11, 4, hy + 8); p.close(); line(p, ML, 3); fill(p, MB);
        p.begin(); ov(p, 2, -7, 12, 5); fill(p, MBL);
        p.begin(); circ(p, 9, hy - 9, 8); line(p, ML, 3); fill(p, MB);
        p.begin(); circ(p, 9.5f, hy - 9, 5); fill(p, ME);
        p.begin(); circ(p, 19, hy - 1, 2.4f); fill(p, MI);
        p.begin(); circ(p, 18.3f, hy - 1.9f, 0.8f); fill(p, 0xFFFFFFFF);
        p.begin(); circ(p, 32 + sn, hy + 6, 2.8f); fill(p, MN);
        p.begin(); mv(p, 30, hy + 5); ln(p, 43, hy + 1 + sn); mv(p, 30, hy + 7); ln(p, 43, hy + 10 - sn); line(p, 0xBBE6EAF0, 1.1f);
        end(p);
    }

    private void mousePeek(BodyRig.Pen p, float x, float y, float u, boolean blink) {
        at(p, x, y - 4 + (1 - u) * 70, 0, MS, 1);
        p.begin(); circ(p, -15, -42, 11); circ(p, 15, -42, 11); line(p, ML, 3); fill(p, MB);
        p.begin(); circ(p, -15, -42, 7); circ(p, 15, -42, 7); fill(p, ME);
        p.begin(); ov(p, 0, -26, 18, 16); line(p, ML, 3); fill(p, MB);
        p.begin(); ov(p, 0, -19, 10, 7); fill(p, MBL);
        if (blink) { p.begin(); mv(p, -9, -29); ln(p, -3, -29); mv(p, 3, -29); ln(p, 9, -29); line(p, MI, 2); }
        else {
            p.begin(); circ(p, -6, -29, 2.9f); circ(p, 6, -29, 2.9f); fill(p, MI);
            p.begin(); circ(p, -6.8f, -30, 1); circ(p, 5.2f, -30, 1); fill(p, 0xFFFFFFFF);
        }
        p.begin(); circ(p, 0, -20, 3.2f); fill(p, MN);
        p.begin(); mv(p, -6, -19); ln(p, -22, -22); mv(p, -6, -17); ln(p, -21, -12); mv(p, 6, -19); ln(p, 22, -22); mv(p, 6, -17); ln(p, 21, -12);
        line(p, 0xBBE6EAF0, 1.1f);
        end(p);
    }

    private void holePath(BodyRig.Pen p, float x) {
        p.begin(); p.moveTo(x - 46, 721); p.lineTo(x - 46, 690); p.cubicTo(x - 46, 646, x + 46, 646, x + 46, 690); p.lineTo(x + 46, 721); p.close();
    }

    private void hole(BodyRig.Pen p, float x) {
        holePath(p, x); fill(p, 0xFF060B13);
        p.begin(); p.moveTo(x - 46, 721); p.lineTo(x - 46, 690); p.cubicTo(x - 46, 646, x + 46, 646, x + 46, 690); p.lineTo(x + 46, 721); line(p, 0xFF3A4D68, 6);
    }

    private void yarn(BodyRig.Pen p, float x, float y, float r, float deg) {
        at(p, x, y, deg, r / 22f, 1);
        p.begin(); circ(p, 0, 0, 22); fill(p, 0xFFF28AB2); line(p, 0xFFB04D7C, 2.5f);
        p.begin(); mv(p, -18, -8); qu(p, 0, -20, 18, -6); mv(p, -20, 4); qu(p, 0, -6, 20, 8); mv(p, -12, 16); qu(p, 4, 4, 14, 18); mv(p, -6, -20); qu(p, -14, 0, -4, 20);
        line(p, 0xFFC9578A, 2);
        p.begin(); mv(p, 16, 14); qu(p, 30, 24, 40, 16); line(p, 0xFFC9578A, 2.4f);
        end(p);
    }

    private void heart(BodyRig.Pen p, float x, float y, float s, int c) {
        p.begin(); p.moveTo(x, y - 4 * s); p.cubicTo(x - 6 * s, y - 12 * s, x - 16 * s, y - 6 * s, x - 10 * s, y + 2 * s); p.lineTo(x, y + 10 * s);
        p.lineTo(x + 10 * s, y + 2 * s); p.cubicTo(x + 16 * s, y - 6 * s, x + 6 * s, y - 12 * s, x, y - 4 * s); p.close();
        fill(p, c);
    }

    private void ball(BodyRig.Pen p, float x, float y, float r, int c) {
        p.begin(); p.addCircle(x, y, r); fill(p, c); line(p, 0x66000000, 2);
        p.begin(); p.addCircle(x - r * 0.35f, y - r * 0.35f, r * 0.3f); fill(p, 0x88FFFFFF);
    }

    /** A paper plane at (x, y) heading deg (0: right). */
    private void plane(BodyRig.Pen p, float x, float y, float deg, float s) {
        float nd = ((deg % 360) + 360) % 360;
        boolean flip = nd > 90 && nd < 270; // (heading left: mirrored, so it stays the right way up)
        at(p, x, y, flip ? deg + 180 : deg, s, flip ? -1 : 1);
        p.begin(); mv(p, 40, 0); ln(p, -32, -17); ln(p, -18, 0); p.close(); fill(p, 0xFFF7FAFF); line(p, 0xFF7F93B3, 2);
        p.begin(); mv(p, 40, 0); ln(p, -18, 0); ln(p, -26, 12); p.close(); fill(p, 0xFFC9D6EA); line(p, 0xFF7F93B3, 2);
        end(p);
    }

    private void bub(BodyRig.Pen p, float x, float y, float r) {
        p.begin(); p.addCircle(x, y, r); fill(p, 0x2A9AD8FF); line(p, 0xDDBFE9FF, 2.2f);
        p.begin(); p.moveTo(x - r * 0.6f, y - r * 0.2f); p.quadTo(x - r * 0.55f, y - r * 0.6f, x - r * 0.15f, y - r * 0.65f); line(p, 0xCCFFFFFF, 2);
    }

    private void pop(BodyRig.Pen p, float x, float y, float r, float u) {
        float k = fa;
        fa = k * (1 - u);
        p.begin();
        for (int i = 0; i < 6; i++) {
            double a = i * PI / 3 + 0.3;
            float c = (float) Math.cos(a), s = (float) Math.sin(a), r0 = r * (0.7f + 0.6f * u), r1 = r * (1.1f + 0.8f * u);
            p.moveTo(x + c * r0, y + s * r0); p.lineTo(x + c * r1, y + s * r1);
        }
        line(p, 0xDDBFE9FF, 2.2f);
        fa = k;
    }

    /** A butterfly at (x, y): flap 0 (wings shut) .. 1 (open). */
    private void butterfly(BodyRig.Pen p, float x, float y, float s, float flap, float deg) {
        at(p, x, y, deg, s, 1);
        float w = 0.15f + 0.85f * flap;
        p.begin(); ov(p, -16 * w, -8, 16 * w, 13); ov(p, 16 * w, -8, 16 * w, 13); fill(p, 0xFFFFB24A); line(p, 0xFF8A4B2A, 1.6f);
        p.begin(); ov(p, -11 * w, 8, 11 * w, 9); ov(p, 11 * w, 8, 11 * w, 9); fill(p, 0xFFF58FBF); line(p, 0xFF8A4B2A, 1.6f);
        p.begin(); circ(p, -17 * w, -10, 4 * w + 0.5f); circ(p, 17 * w, -10, 4 * w + 0.5f); fill(p, 0xFFFFF2C8);
        p.begin(); ov(p, 0, 0, 3, 12); fill(p, 0xFF3D2F4F);
        p.begin(); mv(p, -1, -11); qu(p, -4, -20, -9, -22); mv(p, 1, -11); qu(p, 4, -20, 9, -22); line(p, 0xFF3D2F4F, 1.4f);
        end(p);
    }

    private void sparkle(BodyRig.Pen p, float x, float y, float r, int c) {
        p.begin(); p.moveTo(x, y - r); p.quadTo(x, y, x + r, y); p.quadTo(x, y, x, y + r); p.quadTo(x, y, x - r, y); p.quadTo(x, y, x, y - r); p.close();
        fill(p, c);
    }

    private void star(BodyRig.Pen p, float x, float y, float r, int c) {
        p.begin();
        for (int i = 0; i < 10; i++) {
            double a = -PI / 2 + i * PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f, sx = x + rr * (float) Math.cos(a), sy = y + rr * (float) Math.sin(a);
            if (i == 0) p.moveTo(sx * dir, sy); else p.lineTo(sx * dir, sy);
        }
        p.close();
        fill(p, c);
    }

    private void note(BodyRig.Pen p, float x, float y, int c, boolean two) {
        p.begin(); p.addOval(x, y, 11, 8); if (two) p.addOval(x + 28, y - 8, 11, 8); fill(p, c);
        p.begin(); p.moveTo(x + 10, y - 1); p.lineTo(x + 10, y - 44);
        if (two) { p.moveTo(x + 38, y - 9); p.lineTo(x + 38, y - 52); p.moveTo(x + 10, y - 44); p.lineTo(x + 38, y - 52); }
        else p.quadTo(x + 22, y - 36, x + 21, y - 22);
        line(p, c, 4);
    }

    // ================================================================ drawing helpers
    private int col(int c) {
        if (muted) c = mute(c);
        return fa >= 1 ? c : BodyRig.alpha(c, fa);
    }

    /** Night colours: greyer and darker. */
    private static int mute(int c) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, m = (r + g + b) / 3;
        r = (int) ((r + (m - r) * 0.35f) * 0.74f);
        g = (int) ((g + (m - g) * 0.35f) * 0.74f);
        b = (int) ((b + (m - b) * 0.35f) * 0.78f);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private void fill(BodyRig.Pen p, int c) { if (fa > 0.004f) p.fill(col(c)); }
    private void line(BodyRig.Pen p, int c, float w) { if (fa > 0.004f) p.stroke(col(c), w); }

    private void at(BodyRig.Pen p, float x, float y, float deg, float s, float d) {
        p.save(); p.translate(x, y);
        if (deg != 0) p.rotate(deg);
        if (s != 1) p.scale(s);
        dir = d;
    }

    private void end(BodyRig.Pen p) { p.restore(); dir = 1; }

    private void mv(BodyRig.Pen p, float x, float y) { p.moveTo(x * dir, y); }
    private void ln(BodyRig.Pen p, float x, float y) { p.lineTo(x * dir, y); }
    private void qu(BodyRig.Pen p, float x1, float y1, float x, float y) { p.quadTo(x1 * dir, y1, x * dir, y); }
    private void cu(BodyRig.Pen p, float x1, float y1, float x2, float y2, float x, float y) { p.cubicTo(x1 * dir, y1, x2 * dir, y2, x * dir, y); }
    private void ov(BodyRig.Pen p, float x, float y, float rx, float ry) { p.addOval(x * dir, y, rx, ry); }
    private void circ(BodyRig.Pen p, float x, float y, float r) { p.addCircle(x * dir, y, r); }

    /** A rounded rectangle. */
    private void rr(BodyRig.Pen p, float x, float y, float w, float h, float r) {
        mv(p, x + r, y); ln(p, x + w - r, y); qu(p, x + w, y, x + w, y + r); ln(p, x + w, y + h - r); qu(p, x + w, y + h, x + w - r, y + h);
        ln(p, x + r, y + h); qu(p, x, y + h, x, y + h - r); ln(p, x, y + r); qu(p, x, y, x + r, y); p.close();
    }

    /** A point on a quadratic curve into (px, py). */
    private void bez(float x0, float y0, float x1, float y1, float x2, float y2, float u) {
        float v = 1 - u;
        px = v * v * x0 + 2 * v * u * x1 + u * u * x2;
        py = v * v * y0 + 2 * v * u * y1 + u * u * y2;
    }

    private static float deg(float dx, float dy) { return (float) Math.toDegrees(Math.atan2(dy, dx)); }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float cl(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static float seg(float t, float a, float b) { return t <= a ? 0 : t >= b ? 1 : (t - a) / (b - a); }
    private static float sm(float u) { return u * u * (3 - 2 * u); }
    private static float ss(float t, float a, float b) { return sm(seg(t, a, b)); }
    private static float sq(float u) { return u * u; }
    private static float mix(float a, float b, float u) { return a + (b - a) * u; }

    /** Keyframes {t0, v0, t1, v1, ...}: eased from one to the next. */
    private static float key(float[] k, float t) {
        if (t <= k[0]) return k[1];
        for (int i = 2; i < k.length; i += 2) if (t < k[i]) return mix(k[i - 1], k[i + 1], sm((t - k[i - 2]) / (k[i] - k[i - 2])));
        return k[k.length - 1];
    }
}
