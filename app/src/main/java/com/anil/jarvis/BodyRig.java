package com.anil.jarvis;

/**
 * The new Jarvis (home screen of the home tablet; later the phone too): an original, cute young man drawn by code up
 * to the waist — face with big eyes, brows, lips, hair and ears; neck, clothes and two arms with hands. He blinks,
 * breathes, looks around, moves his lips with the voice (syllable by syllable, opened by the voice's loudness), shows
 * 16 feelings (smile, laugh, caring, sad, worried, surprised, thinking, listening, shy, proud, sleepy, wink, calm,
 * excited…) and hand gestures (hello, namaste, thinking finger, thumbs-up, pointing, clapping, morning stretch).
 * Three looks (a: navy jacket, b: kurta, c: zip jacket — his pick) and three skin tones; the design was chosen by
 * Anil on the "Jarvis రూపం" canvas.
 *
 * Plain Java (no Android classes): it draws through a Pen, so the tablet (BodyView) and a desk test can both draw it.
 * Units: the picture is 680 × 720 (x from -40 to 640, y from 0 down to 720); the neck's centre is x = 300.
 * The home tablet's extras (idle plays, outfits, occasions, dancing, tickles…) live in IdlePlay; until one of them is
 * used, none of that runs and he behaves exactly as before. update / draw allocate nothing per frame.
 */
final class BodyRig {
    // the same numbers as FaceRig / OrbView: IDLE, LISTENING, THINKING, SPEAKING, OFFLINE
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, OFFLINE = 4;
    static final float LEFT = -40, TOP = 0, W = 680, H = 720;

    interface Pen {
        void begin();
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void quadTo(float x1, float y1, float x, float y);
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);
        void close();
        void addCircle(float cx, float cy, float r);
        void addOval(float cx, float cy, float rx, float ry);
        void fill(int argb);
        void fillLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops);
        void fillRadial(float cx, float cy, float r, int[] colors, float[] stops);
        void stroke(int argb, float width);
        void strokeLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops, float width);
        void save();
        void restore();
        void clip();
        void translate(float dx, float dy);
        void rotate(float degrees);
        void scale(float s);
        void text(String s, float x, float y, float size, int argb);
    }

    // ================================================================ looks and skins
    private static final class Look {
        final char hair; final int jacket, jacketHi, jacketDk, shirt, shirtDk, cuff, trim;
        final int[] torsoG; // (the torso's gradient, made once)
        Look(char hair, int jacket, int jacketHi, int jacketDk, int shirt, int shirtDk, int cuff, int trim) {
            this.hair = hair; this.jacket = jacket; this.jacketHi = jacketHi; this.jacketDk = jacketDk;
            this.shirt = shirt; this.shirtDk = shirtDk; this.cuff = cuff; this.trim = trim;
            torsoG = new int[]{jacketHi, jacket, jacketDk};
        }
    }
    private static final Look LOOK_A = new Look('a', 0xFF22314F, 0xFF30446B, 0xFF151F35, 0xFFF4F6F8, 0xFFCDD5DF, 0xFFF4F6F8, 0xFF22314F);
    private static final Look LOOK_B = new Look('b', 0xFF9FCDEB, 0xFFBEDDF2, 0xFF78ADD2, 0xFF9FCDEB, 0xFF78ADD2, 0xFF9FCDEB, 0xFFC9973F);
    private static final Look LOOK_C = new Look('c', 0xFF3B4252, 0xFF4C5568, 0xFF272D39, 0xFF2E7F7B, 0xFF21605D, 0xFF3B4252, 0xFF3B4252);
    // the cold-day sweater (IdlePlay's "sweater" outfit draws its knit pattern and muffler; the hair stays his own look's)
    private static final Look LOOK_KNIT = new Look('k', 0xFF9C3D4A, 0xFFB65564, 0xFF6E2531, 0xFFF1E4CC, 0xFFCDBB9C, 0xFF84303C, 0xFFF1E4CC);
    private static final float[] TORSO_AT = {0, 0.45f, 1}, FACE_AT = {0, 0.55f, 1};

    private static final class Skin {
        final int base, light, dark, line, blush, lip, lipDk;
        final int[] faceG; // (the face's gradient, made once)
        Skin(int base, int light, int dark, int line, int blush, int lip, int lipDk) {
            this.base = base; this.light = light; this.dark = dark; this.line = line; this.blush = blush; this.lip = lip; this.lipDk = lipDk;
            faceG = new int[]{light, base, dark};
        }
    }
    private static final Skin SKIN_1 = new Skin(0xFFE6B48B, 0xFFF3CDA9, 0xFFC99068, 0xFF9C6744, 0xFFEC8A7A, 0xFFBE7466, 0xFF8E4A3F);
    private static final Skin SKIN_2 = new Skin(0xFFCC9266, 0xFFDFAD83, 0xFFAC734B, 0xFF7E4F31, 0xFFE07D6C, 0xFFA6604F, 0xFF743A2E);
    private static final Skin SKIN_3 = new Skin(0xFFA06C45, 0xFFB9855D, 0xFF7F5233, 0xFF5B3820, 0xFFC86C5A, 0xFF86493B, 0xFF5C2E24);

    private Look look = LOOK_C;
    private Skin skin = SKIN_2;

    void setLook(String l) { look = "a".equals(l) ? LOOK_A : "b".equals(l) ? LOOK_B : LOOK_C; }
    void setSkin(String s) { skin = "1".equals(s) ? SKIN_1 : "3".equals(s) ? SKIN_3 : SKIN_2; }

    // ================================================================ feelings (eased numbers)
    // indices into the parameter arrays
    private static final int SMILE = 0, OPEN = 1, WIDE = 2, ROUND = 3, SQUINT = 4, EYE = 5, EYE_L = 6, EYE_R = 7, BROW = 8,
            BROW_L = 9, BROW_R = 10, BROW_ANG = 11, LOOK_X = 12, LOOK_Y = 13, TILT = 14, NOD = 15, BLUSH = 16, TEAR = 17,
            SWEAT = 18, SPARKLE = 19, ZZZ = 20, MOUTH_X = 21, BOUNCE = 22, N = 23;
    private static final String[] KEYS = {"smile", "open", "wide", "round", "squint", "eye", "eyeL", "eyeR", "brow", "browL",
            "browR", "browAng", "lookX", "lookY", "tilt", "nod", "blush", "tear", "sweat", "sparkle", "zzz", "mouthX", "bounce"};

    static final String[] MOODS = {"neutral", "smile", "laugh", "happy", "caring", "sad", "worried", "surprised", "thinking",
            "listening", "shy", "proud", "sleepy", "wink", "calm", "excited"};
    // "key=value ..." per mood; eye shapes: es (both), esL (viewer-left eye only): happy / sleep / calm; g = its own gesture
    private static final String[] MOOD_DEF = {
            "smile=.15",
            "smile=.62 squint=.25",
            "smile=1 open=.62 wide=.25 es=happy blush=.45 bounce=1 brow=.2",
            "smile=.85 open=.28 squint=.3 brow=.3 eye=1.05",
            "smile=.58 eye=.86 tilt=7 browAng=.3 blush=.4 squint=.2",
            "smile=-.6 eye=.72 lookY=.65 browAng=.9 nod=6 tear=1",
            "smile=-.3 open=.1 wide=.4 eye=1.1 browAng=1 brow=.25 sweat=1",
            "eye=1.3 brow=1 round=.8 open=.62 smile=0",
            "eye=.92 lookX=-.6 lookY=-.8 browL=.65 browR=-.15 smile=.05 mouthX=7 g=chin tilt=-5",
            "eye=1.05 brow=.35 smile=.3 tilt=-7",
            "eye=.7 lookX=.55 lookY=.55 smile=.45 blush=1 tilt=10",
            "smile=.75 squint=.45 eye=.85 nod=-5 g=thumb brow=.15",
            "es=sleep open=.16 round=.6 smile=0 tilt=12 nod=5 zzz=1",
            "smile=.8 esL=happy squint=.3 brow=.2 browL=-.1 tilt=4",
            "es=calm smile=.32 g=namaste nod=3",
            "eye=1.2 sparkle=1 smile=1 open=.62 brow=.6 g=clap bounce=1"
    };
    private static final float[] BASE = new float[N];
    static {
        BASE[SMILE] = 0.15f;
        BASE[EYE] = 1f;
    }

    /** A face to ease towards (never changed once made: the same ones are used frame after frame). */
    static final class Goal {
        final float[] v = BASE.clone();
        String eyeL = "open", eyeR = "open", gesture = "none";
    }

    static Goal goal(String mood) {
        int i = 0;
        for (int k = 0; k < MOODS.length; k++) if (MOODS[k].equals(mood)) i = k;
        return face(MOOD_DEF[i]);
    }

    /** A face from "key=value ..." words (as in MOOD_DEF; es / esL / esR: eye shapes; g: its own gesture). */
    static Goal face(String def) {
        Goal g = new Goal();
        for (String kv : def.split(" ")) {
            int e = kv.indexOf('=');
            String k = kv.substring(0, e), v = kv.substring(e + 1);
            if (k.equals("es")) { g.eyeL = v; g.eyeR = v; }
            else if (k.equals("esL")) g.eyeL = v;
            else if (k.equals("esR")) g.eyeR = v;
            else if (k.equals("g")) g.gesture = v;
            else for (int j = 0; j < N; j++) if (KEYS[j].equals(k)) g.v[j] = Float.parseFloat(v);
        }
        return g;
    }

    // goal(mood) read once per mood: every frame asks for one
    private static final Goal[] GOALS = new Goal[MOODS.length];
    private static Goal goalOf(String mood) {
        int i = 0;
        for (int k = 0; k < MOODS.length; k++) if (MOODS[k].equals(mood)) i = k;
        Goal g = GOALS[i];
        if (g == null) GOALS[i] = g = goal(MOODS[i]);
        return g;
    }

    /** Jarvis's feeling words (Emotion) -> a face. */
    static String moodFor(String feeling) {
        if (feeling == null) return "smile";
        switch (feeling) {
            case "happy": return "happy";
            case "laugh": return "laugh";
            case "excited": return "excited";
            case "sad": case "cry": return "sad";
            case "sorry": case "caring": return "caring"; // ("caring": HomeCare's questions to అమ్మగారు)
            case "worried": return "worried";
            case "surprised": return "surprised";
            case "serious": return "neutral";
            case "proud": return "proud";
            case "love": return "caring";
            default: return "smile"; // calm talk
        }
    }

    // ================================================================ state
    private final float[] cur = BASE.clone();
    private String eyeL = "open", eyeR = "open";
    private int mode = IDLE;
    private String feeling = "smile", base = "smile", forced, forcedGesture;
    private long forcedUntil, gestureUntil, feelingUntil;
    private double t;                       // seconds since the start
    private float blinkIn = 1.8f, blink = -1, lookIn = 1.5f, saccX, saccY, saccGx, saccGy;
    private boolean present;
    private float seenX, seenY;
    private float mic, voice;
    // the arms: wrist positions (eased) and the hand shapes shown
    private final float[] wR = {152, 860}, wL = {448, 860};
    private final Hand hR = new Hand("rest", 0, true), hL = new Hand("rest", 0, true);
    // speech: one mouth shape per syllable of the word being spoken
    private float[][] vis = new float[0][];
    private double visAt;
    private final float[] mouth = new float[3];

    private static final class Hand {
        String s; float a; boolean down;
        Hand(String s, float a, boolean down) { this.s = s; this.a = a; this.down = down; }
    }
    // reused every frame (nothing is made per frame)
    private final Arm tgR = new Arm(152, 860, 0, "rest", true), tgL = new Arm(448, 860, 0, "rest", true);
    private final float[] want = new float[3], qd = new float[N];
    private final float[] jR = new float[2], jL = new float[2]; // the shoulder joints this frame (they follow the arms)
    private final float[][] armR = {jR, new float[2], new float[2]}, armL = {jL, new float[2], new float[2]};
    // the extras (idle plays, outfits…): null until one is used
    private IdlePlay extra;
    private float cross; // cross-eyed (a butterfly on his nose): 0 = never

    void setMode(int m) {
        if (m != mode && m == SPEAKING) vis = new float[0][];
        if (m != mode && extra != null) extra.activity();
        mode = m;
    }
    int mode() { return mode; }
    void setMic(float l) { mic = l; }
    void setVoice(float l) { voice = l; }
    /** The reply's feeling (Emotion's words); it stays a few seconds after he stops talking. */
    void setFeeling(String f) { feeling = moodFor(f); feelingUntil = ms() + 6000; if (extra != null) extra.activity(); }
    /** The resting face (e.g. "sleepy" at night). */
    void setBase(String mood) { base = mood == null ? "smile" : mood; }
    /** A face for a while (ms), over everything but listening. */
    void show(String mood, long forMs) { forced = mood; forcedUntil = ms() + forMs; if (extra != null) extra.override(); }
    /** A hand gesture for a while (ms): wave, namaste, chin, thumb, point, clap, stretch. */
    void gesture(String g, long forMs) { forcedGesture = g; gestureUntil = ms() + forMs; if (extra != null) extra.override(); }
    void greet() { gesture("wave", 2600); show("happy", 2600); }
    /** Someone seen by the front camera (x, y: -1..1 in the picture), or nobody. */
    void look(boolean someone, float x, float y) {
        if (someone && !present) greet();
        present = someone; seenX = x; seenY = y;
    }

    /** The word being spoken now: its syllables become mouth shapes. */
    void word(String w) {
        vis = visemes(w);
        visAt = t;
        if (extra != null) extra.activity();
    }

    private long ms() { return (long) (t * 1000); }

    // ================================================================ the home tablet's extras (IdlePlay)
    private IdlePlay x() { if (extra == null) extra = new IdlePlay(); return extra; }

    /** Little plays of his own when nobody has talked to him for 40 s (off by default; the home screen turns them on). */
    void setIdlePlays(boolean on) { if (on || extra != null) x().setOn(on); }
    /** Night: only the calm plays (a firefly, the kitten asleep, a dream, stars, snoring, humming). */
    void setNightPlays(boolean night) { if (night || extra != null) x().setNight(night); }
    /** Someone touched or talked: a play ends gracefully (within ~0.5 s) and the 40 s wait starts again. */
    void poke() { if (extra != null) extra.poke(); }
    /** Starts a play now (or as soon as he is idle again, within a minute): one of playIds, or "eat" / "cake". */
    void play(String id) { x().play(id); }
    /** The plays of the day (or night) that come by themselves. */
    static String[] playIds(boolean night) { return IdlePlay.ids(night); }
    /** The play going on (until it has fully ended), or null. */
    String playing() { return extra == null ? null : extra.playing(); }
    /** The weather's outfit: "none", "umbrella", "sweater" or "fan" (stays until changed). */
    void setOutfit(String o) { if (o != null && !"none".equals(o) || extra != null) x().setOutfit(o); }
    /** The day's occasion: "none", "christmas", "newyear", "sankranti", "easter" or "birthday". */
    void setOccasion(String o) { if (o != null && !"none".equals(o) || extra != null) x().setOccasion(o); }
    /** Touched and held: a 2.5 s giggle. */
    void tickle() { x().moment(IdlePlay.TICKLE); }
    /** An open palm raised towards her, happy (2 s). */
    void highFive() { x().moment(IdlePlay.HIGH5); }
    /** Waves goodbye, a little sad, then smiles (3 s). */
    void waveBye() { x().moment(IdlePlay.BYE); }
    /** Claps with a big smile (2.5 s): she ate, she took her tablet. */
    void cheer() { gesture("clap", 2500); show("excited", 2500); }
    /** While her songs play: a gentle head bob and swaying arms (~100 bpm). */
    void setDance(boolean on) { if (on || extra != null) x().setDance(on); }
    /** While on: he keeps glancing to the screen's right edge (the door), happy and expectant. */
    void waitDoor(boolean on) { if (on || extra != null) x().setDoor(on); }
    /** Something of his own is moving (a day play, a dance, a reaction): BodyView keeps ~30 frames a second. */
    boolean busy() { return extra != null && extra.moving(mode); }
    String base() { return base; }
    double time() { return t; }

    // ================================================================ every frame
    void update(float dt) {
        if (dt > 0.2f) dt = 0.2f;
        t += dt;
        long now = ms();
        String mood;
        if (mode == LISTENING) mood = "listening";
        else if (forced != null && now < forcedUntil) mood = forced;
        else if (mode == THINKING) mood = "thinking";
        else if (mode == SPEAKING) mood = feeling;
        else if (mode == OFFLINE) mood = "neutral";
        else mood = now < feelingUntil ? feeling : base;
        Goal g = goalOf(mood);
        boolean gestOn = forcedGesture != null && now < gestureUntil;
        String gest = gestOn ? forcedGesture
                : mode == SPEAKING && !"excited".equals(mood) && !"calm".equals(mood) && !"proud".equals(mood) ? "none" : g.gesture;
        // the extras: a play, a reaction, a dance… may lead the face, the eyes and the arms
        IdlePlay x = extra;
        if (x != null) {
            x.frame(this, dt, forced != null && now < forcedUntil, gestOn, now < feelingUntil);
            if (x.face != null) g = x.face;
            if (x.gest != null) gest = x.gest;
        }
        float k = 1 - (float) Math.pow(0.8, dt * 30); // ~0.2 a frame at 30 fps
        for (int i = 0; i < N; i++) cur[i] += (g.v[i] - cur[i]) * k;
        eyeL = g.eyeL; eyeR = g.eyeR;

        // where he looks: at the person the camera sees, else little glances around
        lookIn -= dt;
        if (lookIn <= 0) { saccGx = (float) (Math.random() - 0.5) * 0.5f; saccGy = (float) (Math.random() - 0.5) * 0.3f; lookIn = 1.5f + (float) Math.random() * 2.5f; }
        float gx = present ? clamp(seenX, -1, 1) * 0.8f : saccGx, gy = present ? clamp(seenY, -1, 1) * 0.5f : saccGy;
        if (x != null && x.gaze) { gx = x.gx; gy = x.gy; }
        saccX += (gx - saccX) * k * 1.2f; saccY += (gy - saccY) * k * 1.2f;
        if (x != null) cross += (x.cross - cross) * Math.min(1, k * 1.5f);

        // blinking
        blinkIn -= dt;
        if (blinkIn <= 0) { blink = 0; blinkIn = 2.4f + (float) Math.random() * 3.2f; }
        if (blink >= 0) { blink += dt / 0.17f; if (blink > 1) blink = -1; }

        // the arms
        targets(gest, t, tgR, tgL);
        if (x != null) {
            x.arms(tgR, tgL, gestOn);
            float ka = x.armK == 1 ? k : Math.min(k * x.armK, 1.1f);
            step(wR, hR, tgR, ka);
            step(wL, hL, tgL, ka);
        } else {
            step(wR, hR, tgR, k);
            step(wL, hL, tgL, k);
        }

        // the mouth while speaking: the syllable's shape, opened by the voice's loudness when known
        want[0] = 0; want[1] = 0; want[2] = 0;
        if (mode == SPEAKING) {
            double el = (t - visAt) / 0.13;
            int ix = (int) Math.floor(el);
            if (vis.length > 0 && ix < vis.length) {
                float[] a = vis[ix], b = vis[Math.min(ix + 1, vis.length - 1)];
                float fr = (float) (el - ix), sm = fr * fr * (3 - 2 * fr) * 0.5f;
                for (int i = 0; i < 3; i++) want[i] = a[i] + (b[i] - a[i]) * sm;
                if (voice > 0) want[0] *= 0.35f + 0.9f * Math.min(1, voice);
            } else if (voice > 0.05f) {
                want[0] = 0.15f + 0.55f * Math.min(1, voice); want[1] = 0.15f;
            }
        }
        float km = 1 - (float) Math.pow(0.5, dt * 30);
        for (int i = 0; i < 3; i++) mouth[i] += (want[i] - mouth[i]) * km;
    }

    /** Where a wrist goes: x, y, the hand's angle, its shape (rest, open, side, point, thumb, hold) and the elbow (down / out). */
    static final class Arm {
        float x, y, a; String s; boolean down;
        Arm(float x, float y, float a, String s, boolean down) { set(x, y, a, s, down); }
        void set(float x, float y, float a, String s, boolean down) { this.x = x; this.y = y; this.a = a; this.s = s; this.down = down; }
    }

    private static void targets(String g, double t, Arm R, Arm L) {
        R.set(REST_RX, 860, 0, "rest", true); L.set(REST_LX, 860, 0, "rest", true); // (arms hanging, a little out from the body)
        switch (g) {
            case "wave": R.set(84, 292, (float) (-8 + 16 * Math.sin(t * Math.PI * 2 * 1.6)), "open", false); break;
            case "namaste": R.set(287, 618, 2, "side", true); L.set(313, 618, -2, "side", true); break;
            case "chin": R.set(250, 502, 14, "point", true); break;
            case "thumb": R.set(236, 600, -4, "thumb", true); break;
            case "point": L.set(530, 432, 42, "point", true); break;
            case "clap": {
                float d = (float) (4 + 24 * (0.5 + 0.5 * Math.sin(t * Math.PI * 2 * 2.2)));
                R.set(268 - d, 604, 4, "side", true); L.set(332 + d, 604, -4, "side", true); break;
            }
            case "stretch": R.set(120, 218, -18, "open", false); L.set(480, 218, 18, "open", false); break;
            default: break;
        }
    }

    private static void step(float[] w, Hand h, Arm g, float k) {
        float e = k * 0.9f;
        w[0] += (g.x - w[0]) * e; w[1] += (g.y - w[1]) * e;
        float d = Math.abs(g.x - w[0]) + Math.abs(g.y - w[1]);
        // (the old hand, and its elbow, stay while the arm is on its way: the elbow doesn't jump to the other side mid-air)
        if (d < 60 || "rest".equals(h.s)) { h.s = g.s; h.down = g.down; }
        h.a = d < 60 ? g.a : h.a + (g.a - h.a) * 0.3f;
    }

    // ================================================================ speech -> mouth shapes
    static float[][] visemes(String text) {
        if (text == null) return new float[0][];
        java.util.ArrayList<String> syl = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // (vowel signs, virama, candrabindu / anusvara / visarga, nukta, length marks, and the joiners in "టైమ్‌లు")
            boolean mark = (c >= 0x0C3E && c <= 0x0C4D) || (c >= 0x0C00 && c <= 0x0C04) || c == 0x0C3C || c == 0x0C55 || c == 0x0C56
                    || c == 0x0C62 || c == 0x0C63 || c == 0x200C || c == 0x200D;
            boolean afterVirama = cur.length() > 0 && cur.charAt(cur.length() - 1) == 0x0C4D && c >= 0x0C15 && c <= 0x0C39; // (a conjunct: one syllable)
            if ((mark || afterVirama) && cur.length() > 0) cur.append(c);
            else { if (cur.length() > 0) syl.add(cur.toString()); cur.setLength(0); cur.append(c); }
        }
        if (cur.length() > 0) syl.add(cur.toString());
        float[][] out = new float[syl.size()][];
        for (int i = 0; i < out.length; i++) out[i] = shape(syl.get(i));
        return out;
    }

    private static float[] shape(String s) {
        if (s.trim().isEmpty() || s.matches("[\\s,.!?:;।\\-]+")) return new float[]{0.04f, 0, 0};
        if (has(s, "ాఆaA")) return new float[]{0.72f, 0.2f, 0};
        if (has(s, "ిీఇఈiIyY")) return new float[]{0.3f, 0.85f, 0};
        if (has(s, "ుూఉఊuUwW")) return new float[]{0.36f, 0, 0.9f};
        if (has(s, "ెేైఎఏఐeE")) return new float[]{0.44f, 0.55f, 0};
        if (has(s, "ొోౌఒఓఔoO")) return new float[]{0.5f, 0, 0.72f};
        if (s.replace("\u200C", "").replace("\u200D", "").endsWith("్")) return new float[]{0.14f, 0.2f, 0}; // (a closing consonant; "మ్మ" is an "a")
        if (has(s, "మబభపఫmbp")) return new float[]{0.3f, 0.1f, 0};
        return new float[]{0.56f, 0.15f, 0};
    }

    private static boolean has(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return true;
        return false;
    }

    // ================================================================ drawing
    // the shoulder joints at rest (they move with the arms: see shoulder()), the arm's bones and its thickness
    private static final float[] SH_R = {158, 530}, SH_L = {442, 530};
    private static final float L1 = 170, L2 = 160, REST_RX = 104, REST_LX = 496;
    private static final float R_SHOULDER = 35, R_ELBOW = 31, R_WRIST = 24;
    private static final int[] IRIS = {0xFF8A5A36, 0xFF5A3820, 0xFF2E1A0E};
    private static final float[] IRIS_AT = {0, 0.6f, 1};
    private static final int[] HAIR_G = {0xFF3A2B22, 0xFF1E1612, 0xFF120C09};
    private static final float[] HAIR_AT = {0, 0.55f, 1};
    private static final int[] RIM = {0x008FD0FF, 0xBF8FD0FF};
    private static final float[] RIM_AT = {0.62f, 1};

    /** Draws one frame (in picture units; the Pen's owner maps the 680 × 720 picture onto the screen). */
    void draw(Pen p) {
        float[] q = qd;
        System.arraycopy(cur, 0, q, 0, N);
        float breath = (float) Math.sin(t * Math.PI * 2 / 4.2) * 2.2f;
        float bob = q[BOUNCE] * (float) Math.abs(Math.sin(t * Math.PI * 2 * 1.4)) * -5;
        float sway = (float) Math.sin(t * Math.PI * 2 / 7) * 1.6f;
        boolean talking = mode == SPEAKING && mouth[0] > 0.02f;
        if (talking) { q[OPEN] = Math.max(q[OPEN] * 0.4f, mouth[0]); q[WIDE] = mouth[1]; q[ROUND] = mouth[2]; }
        q[LOOK_X] += saccX; q[LOOK_Y] += saccY;
        String shL = eyeL, shR = eyeR;
        float bl = blink < 0 ? 0 : 1 - Math.abs(2 * blink - 1);
        if (bl > 0.5f && "open".equals(shL)) shL = "sleep";
        if (bl > 0.5f && "open".equals(shR)) shR = "sleep";
        float oL = q[EYE] + q[EYE_L], oR = q[EYE] + q[EYE_R];
        if (bl > 0 && bl <= 0.5f) { oL *= 1 - bl; oR *= 1 - bl; }

        // the shoulders follow the hands (up and in when a hand is high, rolled in when it reaches across, a bounce in a laugh)
        float laugh = q[BOUNCE] * (float) Math.abs(Math.sin(t * Math.PI * 2 * 1.4)) * 5;
        shoulder(jR, SH_R[0], SH_R[1], wR, 1, laugh);
        shoulder(jL, SH_L[0], SH_L[1], wL, -1, laugh);
        float[][] aR = ik(jR, wR, hR.down, true, armR), aL = ik(jL, wL, hL.down, false, armL);
        IdlePlay x = extra;
        boolean xo = x != null && x.offsets;
        Look lk = x != null && x.knit() ? LOOK_KNIT : look; Skin sk = skin;
        float bodyY = breath * 0.5f + bob * 0.5f, bodyX = 0;
        float headX = 300 + sway * 0.6f, headY = 248 + q[NOD] + breath * 0.6f + bob, headR = q[TILT] + sway * 0.5f;
        if (xo) { bodyX = x.bodyDx; bodyY += x.bodyDy; headX += x.headDx; headY += x.headDy; headR += x.headTilt; }
        if (x != null) {
            // (the arms move with the body: where the hands and the left shoulder are on the screen)
            x.seen(aR[2][0] + bodyX, aR[2][1] + bodyY, hR.a, hR.s, wR[1] > 760, aL[2][0] + bodyX, aL[2][1] + bodyY, hL.a, hL.s, wL[1] > 760,
                    headX, headY, headR, bodyY, jL[0] + bodyX, jL[1] + bodyY);
            x.draw(p, IdlePlay.BACK);
        }

        // ---- body (moves with the breath): the trunk, the shoulders and the upper arms are one shape
        p.save();
        p.translate(bodyX, bodyY);
        if (lk == LOOK_C) { path(p, HOOD_C); p.fill(lk.jacketDk); }
        path(p, NECK); p.fill(sk.base); p.stroke(alpha(sk.line, 0.5f), 2);
        path(p, NECK_SH); p.fill(alpha(sk.dark, 0.55f));
        // (outlined together, then filled together: the outline is only round the outside, no seam at the shoulders)
        for (int pass = 0; pass < 3; pass++) {
            trunk(p); paint(p, pass, lk);
            cap(p, aR); paint(p, pass, lk);
            tube(p, aR[0], aR[1], R_SHOULDER, R_ELBOW); paint(p, pass, lk);
            cap(p, aL); paint(p, pass, lk);
            tube(p, aL[0], aL[1], R_SHOULDER, R_ELBOW); paint(p, pass, lk);
        }
        clothes(p, lk);
        if (x != null) { p.save(); trunk(p); p.clip(); x.draw(p, IdlePlay.BODY); p.restore(); }
        // (the sleeves again, fill only: the clothes' details and a sweater's pattern stay under his arms)
        cap(p, aR); paint(p, 2, lk); tube(p, aR[0], aR[1], R_SHOULDER, R_ELBOW); paint(p, 2, lk);
        cap(p, aL); paint(p, 2, lk); tube(p, aL[0], aL[1], R_SHOULDER, R_ELBOW); paint(p, 2, lk);
        armLine(p, aR, lk, 0.24f, 1); armLine(p, aL, lk, 0.24f, 1);
        // an arm at rest hangs by his side, behind anything in front of him (a kitten walking by)
        if (wR[1] > 760) arm(p, aR, hR, 1, lk, sk);
        if (wL[1] > 760) arm(p, aL, hL, -1, lk, sk);
        p.restore();

        // ---- head
        p.save();
        if (xo) p.translate(headX, headY); else p.translate(300 + sway * 0.6f, 248 + q[NOD] + breath * 0.6f + bob);
        if (xo) p.rotate(headR); else p.rotate(q[TILT] + sway * 0.5f);
        p.scale(1.18f);
        Look hl = look; // (the hair is always his own look's)
        String[] hair = hl.hair == 'a' ? HAIR_A : hl.hair == 'b' ? HAIR_B : HAIR_C;
        path(p, hair[0]); p.fill(0xFF120C09);
        path(p, EAR_L); p.fill(sk.base); p.stroke(sk.line, 2);
        path(p, EAR_R); p.fill(sk.dark); p.stroke(sk.line, 2);
        path(p, EAR_IN); p.stroke(alpha(sk.line, 0.55f), 2);
        path(p, FACE); p.fillLinear(-115, -154, 115, -96, sk.faceG, FACE_AT); p.stroke(alpha(sk.line, 0.55f), 2.2f);
        path(p, FACE); p.strokeLinear(-115, 0, 115, 0, RIM, RIM_AT, 3.2f);
        p.begin(); p.addOval(-46, 18, 20, 5); p.addOval(46, 18, 20, 5); p.fill(alpha(sk.dark, 0.1f));
        path(p, JAW_SH); p.fill(alpha(sk.dark, 0.35f));
        p.begin(); p.addOval(-60, 44, 20, 11); p.addOval(60, 44, 20, 11); p.fill(alpha(sk.blush, 0.18f + 0.45f * q[BLUSH]));
        path(p, NOSE_SH); p.fill(alpha(sk.dark, 0.28f));
        path(p, NOSE); p.stroke(alpha(sk.line, 0.35f), 2.2f);
        path(p, NOSE_TIP); p.stroke(alpha(sk.dark, 0.55f), 2.4f);
        p.begin(); p.addOval(1, 44, 6, 4); p.fill(0x2EFFFFFF);
        path(p, NOSTRIL); p.stroke(alpha(sk.line, 0.75f), 2.4f);
        eye(p, -46, -1, oL, shL, q, sk, cross);
        eye(p, 46, 1, oR, shR, q, sk, cross);
        brow(p, -46, -1, q[BROW], q[BROW_ANG], q[BROW_L]); p.fill(0xFF1E1612);
        brow(p, 46, 1, q[BROW], q[BROW_ANG], q[BROW_R]); p.fill(0xFF1E1612);
        mouth(p, q, sk);
        p.save(); p.translate(0, 6); path(p, hair[1]); p.fill(alpha(sk.dark, 0.3f)); p.restore();
        path(p, hair[1]); p.fillLinear(0, -214, 0, 0, HAIR_G, HAIR_AT); p.stroke(0xFF120C09, 1.5f);
        path(p, hair[1]); p.strokeLinear(-131, 0, 131, 0, RIM, RIM_AT, 3);
        path(p, hair[2]); p.stroke(0xFF120C09, 3);
        path(p, hair[3]); p.stroke(0x994A3A30, 5);
        if (q[TEAR] > 0.3f) { path(p, "M -52 18 C -58 30 -60 38 -54 42 C -48 44 -44 38 -48 30 Z"); p.fill(0xFF9FD3F5); p.stroke(0xFF5FA8D8, 1.5f); }
        if (q[SWEAT] > 0.3f) { path(p, "M 92 -70 C 84 -56 82 -46 90 -42 C 98 -40 102 -48 96 -60 Z"); p.fill(0xFFBFE3FA); p.stroke(0xFF6CB2DE, 1.5f); }
        if (x != null) x.draw(p, IdlePlay.HEAD); // (hats: in the head's own units)
        p.restore();
        if (x != null) x.draw(p, IdlePlay.MID); // (things held: behind the fingers)

        // ---- forearms and hands (in front; with the body's breath)
        p.save();
        p.translate(bodyX, bodyY);
        if (wR[1] <= 760) arm(p, aR, hR, 1, lk, sk);
        if (wL[1] <= 760) arm(p, aL, hL, -1, lk, sk);
        p.restore();

        // ---- zzz while asleep
        if (q[ZZZ] > 0.3f) {
            float ph = (float) ((t % 3) / 3);
            p.text("z", 430, 150 - ph * 40, 40, alpha(0xFF8EC9FF, 1 - ph));
            p.text("z", 462, 112 - ph * 40, 30, alpha(0xFF8EC9FF, (1 - ph) * 0.8f));
        }
        if (x != null) x.draw(p, IdlePlay.FRONT);
    }

    private void clothes(Pen p, Look lk) {
        if (lk == LOOK_A) {
            path(p, "M 252 462 L 300 600 L 348 462 Z"); p.fill(lk.shirt); p.stroke(lk.shirtDk, 2);
            path(p, "M 236 480 L 214 560 L 250 556 L 232 600 L 300 690 L 300 640 L 262 520 Z M 364 480 L 386 560 L 350 556 L 368 600 L 300 690 L 300 640 L 338 520 Z");
            p.fill(lk.jacketHi); p.stroke(lk.jacketDk, 2.4f);
            path(p, "M 256 450 L 236 498 L 282 488 L 300 520 L 318 488 L 364 498 L 344 450 C 330 462 270 462 256 450 Z"); p.fill(lk.shirt); p.stroke(lk.shirtDk, 2.4f);
            path(p, "M 300 690 L 300 720 M 214 560 L 250 556 M 386 560 L 350 556"); p.stroke(lk.jacketDk, 2.4f);
            p.begin(); p.addCircle(300, 540, 3.2f); p.addCircle(300, 576, 3.2f); p.addCircle(300, 704, 6); p.fill(lk.jacketDk);
            p.begin(); p.addCircle(356, 590, 7); p.fill(0xFF3BA7FF);
            p.begin(); p.addCircle(354, 588, 2.2f); p.fill(0xCCFFFFFF);
        } else if (lk == LOOK_B) {
            path(p, "M 290 474 L 310 474 L 310 640 L 290 640 Z"); p.fill(lk.jacketHi); p.stroke(lk.jacketDk, 2.4f);
            path(p, "M 252 446 C 270 462 330 462 348 446 L 352 470 C 330 484 270 484 248 470 Z"); p.fill(lk.shirt); p.stroke(lk.shirtDk, 2.4f);
            path(p, "M 300 474 L 300 640"); p.stroke(lk.jacketDk, 2.4f);
            path(p, "M 288 474 L 288 640 M 312 474 L 312 640 M 250 470 C 272 484 328 484 350 470"); p.stroke(lk.trim, 2.6f);
            p.begin(); p.addCircle(300, 500, 3.6f); p.addCircle(300, 540, 3.6f); p.addCircle(300, 580, 3.6f); p.fill(lk.jacketDk);
        } else if (lk == LOOK_KNIT) {
            // the sweater's knit pattern and muffler: IdlePlay (its BODY layer)
        } else {
            path(p, "M 236 466 C 260 474 340 474 364 466 L 344 720 L 256 720 Z"); p.fill(lk.shirt); p.stroke(lk.shirtDk, 2);
            path(p, "M 236 466 L 256 720 L 246 720 L 226 474 Z M 364 466 L 344 720 L 354 720 L 374 474 Z"); p.fill(lk.jacketHi); p.stroke(lk.jacketDk, 2.4f);
            path(p, "M 260 458 C 276 476 324 476 340 458 L 344 466 C 326 486 274 486 256 466 Z"); p.fill(lk.shirt); p.stroke(lk.shirtDk, 2.4f);
            path(p, "M 252 500 L 262 720 M 348 500 L 338 720"); p.stroke(lk.jacketDk, 2.4f);
        }
    }

    private static void eye(Pen p, float cx, int side, float open, String shape, float[] q, Skin sk, float cross) {
        float hw = 26, oc = cx + side * hw, ic = cx - side * hw;
        if ("happy".equals(shape)) {
            p.begin(); p.moveTo(ic, 4); p.quadTo(cx, -16, oc, 2); p.stroke(0xFF2A1912, 4);
            return;
        }
        if ("sleep".equals(shape) || "calm".equals(shape) || open < 0.08f) {
            float sag = "calm".equals(shape) ? 6 : 8;
            p.begin(); p.moveTo(ic, 1); p.quadTo(cx, sag, oc, -1); p.stroke(0xFF2A1912, 4);
            p.begin(); p.moveTo(ic + side * 2, -8); p.quadTo(cx, -6, oc - side * 2, -10); p.stroke(alpha(sk.line, 0.5f), 1.7f);
            return;
        }
        float h = 38 * open, up = -h * 0.8f, lo = h * 0.44f - q[SQUINT] * 9, ocy = -3, icy = 2;
        // the white
        p.begin(); p.moveTo(ic, icy); p.cubicTo(ic + side * 6, up, oc - side * 10, up - 1, oc, ocy);
        p.cubicTo(oc - side * 8, lo + 2, ic + side * 8, lo + 4, ic, icy); p.close();
        p.fill(0xFFFBF7F2);
        p.save();
        p.clip();
        float ix = cx + q[LOOK_X] * 8, iy = -3 + q[LOOK_Y] * 6 + (open > 1 ? 0 : (1 - open) * 5), hs = 1 + q[SPARKLE] * 0.5f;
        if (cross != 0) ix -= side * cross * 8; // (each eye towards the nose)
        p.begin(); p.addCircle(ix, iy, 16.5f); p.fillRadial(ix - 1.6f, iy - 3.3f, 9.9f * 2, IRIS, IRIS_AT);
        p.begin(); p.addCircle(ix, iy, 15.7f); p.stroke(0xFF24140A, 1.8f);
        p.begin(); p.addCircle(ix, iy, 7.8f + (open > 1.15f ? 1.2f : 0)); p.fill(0xFF120A06);
        p.begin(); p.addCircle(ix - 5.5f, iy - 6, 5 * hs); p.fill(0xFFFFFFFF);
        p.begin(); p.addCircle(ix + 5.5f, iy + 5.5f, 2.3f * hs); p.fill(0xD9FFFFFF);
        p.begin(); p.moveTo(ic, icy); p.cubicTo(ic + side * 6, up, oc - side * 10, up - 1, oc, ocy);
        p.cubicTo(oc - side * 8, lo + 2, ic + side * 8, lo + 4, ic, icy); p.close();
        p.stroke(alpha(sk.dark, 0.35f), 5);
        p.restore();
        p.begin(); p.moveTo(oc - side * 4, ocy + 3); p.cubicTo(oc - side * 9, lo + 2, ic + side * 9, lo + 4, ic + side * 2, icy + 2);
        p.stroke(alpha(sk.line, 0.55f), 1.6f);
        p.begin(); p.moveTo(ic - side, icy); p.cubicTo(ic + side * 6, up, oc - side * 10, up - 1, oc + side * 3, ocy - 2);
        p.stroke(0xFF2A1912, 4.4f);
        p.begin(); p.moveTo(ic + side * 4, up - 3); p.cubicTo(ic + side * 10, up - 11, oc - side * 10, up - 12, oc - side * 2, ocy - 9);
        p.stroke(alpha(sk.line, 0.5f), 1.7f);
    }

    private static void brow(Pen p, float cx, int side, float raise, float ang, float own) {
        float r = raise + own, y = -46 - r * 10, inner = cx - side * 26, outer = cx + side * 29;
        float iy = y + 4 - ang * 9, oy = y + 4 + ang * 2, my = y - 5 + ang;
        p.begin();
        p.moveTo(inner, iy + 5);
        p.cubicTo(inner + side * 2, iy - 4, cx - side * 4, my - 4, cx + side * 6, my - 2);
        p.cubicTo(cx + side * 16, my, outer - side * 4, oy - 4, outer, oy);
        p.cubicTo(outer - side * 6, oy + 2, cx + side * 14, my + 6, cx + side * 4, my + 7);
        p.cubicTo(cx - side * 6, my + 9, inner + side * 6, iy + 10, inner, iy + 7);
        p.close();
    }

    private static void mouth(Pen p, float[] q, Skin sk) {
        float s = q[SMILE], o = q[OPEN], cx = q[MOUTH_X], cy = 81;
        float hw = 24 * (1 + 0.3f * q[WIDE] - 0.28f * q[ROUND]) + 5 * Math.max(0, s);
        float cyC = cy - 7 * s, upY = cy - 1 - o * 3 + (s > 0 ? 1.5f * s * (1 - o) : 0);
        float loY = cy + 2 + o * 28 * (1 - 0.2f * q[WIDE]) + (s > 0 ? 3 * s : 0) + q[ROUND] * o * 14;
        float L = cx - hw, R = cx + hw;
        if (o > 0.05f) {
            p.begin(); p.moveTo(L, cyC); p.cubicTo(cx - hw * 0.5f, upY, cx + hw * 0.5f, upY, R, cyC);
            p.cubicTo(cx + hw * 0.6f, loY, cx - hw * 0.6f, loY, L, cyC); p.close();
            p.fill(0xFF5A2320);
            p.save();
            p.clip();
            if (o > 0.12f && s > -0.2f) {
                p.begin(); p.moveTo(L + 4, cyC); p.cubicTo(cx - hw * 0.5f, upY - 1, cx + hw * 0.5f, upY - 1, R - 4, cyC);
                p.lineTo(R - 6, upY + 8 + 2 * s); p.cubicTo(cx + hw * 0.4f, upY + 10, cx - hw * 0.4f, upY + 10, L + 6, upY + 8 + 2 * s); p.close();
                p.fill(0xFFFFFBF4);
            }
            if (o > 0.32f) { p.begin(); p.addOval(cx, loY - 7, hw * 0.52f, 7 + o * 4); p.fill(0xFFD9736A); }
            p.restore();
            p.begin(); p.moveTo(cx - hw * 0.66f, loY - 1); p.cubicTo(cx - hw * 0.4f, loY + 9, cx + hw * 0.4f, loY + 9, cx + hw * 0.66f, loY - 1);
            p.cubicTo(cx + hw * 0.3f, loY + 3, cx - hw * 0.3f, loY + 3, cx - hw * 0.66f, loY - 1); p.close();
        } else {
            float lcy = cyC + 2 + 3 * Math.max(0, s);
            p.begin(); p.moveTo(cx - hw * 0.62f, lcy); p.cubicTo(cx - hw * 0.4f, cy + 12, cx + hw * 0.4f, cy + 12, cx + hw * 0.62f, lcy);
            p.cubicTo(cx + hw * 0.3f, upY + 3, cx - hw * 0.3f, upY + 3, cx - hw * 0.62f, lcy); p.close();
        }
        p.fill(alpha(sk.lip, 0.55f));
        p.begin(); p.moveTo(L + 2, cyC); p.cubicTo(cx - hw * 0.5f, upY - 6, cx - 4, upY - 7, cx, upY - 4);
        p.cubicTo(cx + 4, upY - 7, cx + hw * 0.5f, upY - 6, R - 2, cyC); p.cubicTo(cx + hw * 0.5f, upY, cx - hw * 0.5f, upY, L + 2, cyC); p.close();
        p.fill(alpha(sk.lipDk, 0.35f));
        p.begin(); p.moveTo(L - 1, cyC - 0.5f); p.cubicTo(cx - hw * 0.5f, upY, cx + hw * 0.5f, upY, R + 1, cyC - 0.5f);
        p.stroke(sk.lipDk, 2.8f);
        if (s > 0.3f && o < 0.2f) {
            p.begin(); p.moveTo(L - 2, cyC + 4); p.quadTo(L - 4, cyC, L - 1, cyC - 3);
            p.moveTo(R + 2, cyC + 4); p.quadTo(R + 4, cyC, R + 1, cyC - 3);
            p.stroke(alpha(sk.lipDk, 0.55f), 2);
        }
    }

    // ---- arms
    /**
     * The shoulder joint J for a hand at w (side in: +1 his right, -1 his left; the way to his neck). A hand above the chest
     * lifts the shoulder and draws it in towards the neck; a hand reaching across rolls it in; a laugh bounces it. All of it
     * follows the eased hand, so it never jumps; at most ~22 px up and ~17 px in.
     */
    private static void shoulder(float[] J, float bx, float by, float[] w, int in, float laugh) {
        float hi = clamp((560 - w[1]) / 260f, 0, 1);
        hi = hi * hi * (3 - 2 * hi);
        float across = clamp((in > 0 ? w[0] - 250 : 350 - w[0]) / 160f, 0, 1);
        float lift = 17 * hi + laugh;
        J[0] = bx + in * (lift * 0.45f + 7 * across);
        J[1] = by - lift + 3 * across;
    }

    /**
     * The arm's joints {shoulder, elbow, wrist} into out (out[0] is the shoulder; elbow and wrist are written). A two-bone
     * arm in 3-D seen from the front: a hand held in front of him ("down" hands) sits forward of the body at a relaxed reach,
     * so its forearm comes towards us (drawn shorter) and the elbow hangs down by his side, a little out and back; raised
     * "out" hands stay in the picture's plane with the elbow out to the side. Nothing is allocated.
     */
    private float[][] ik(float[] S, float[] W, boolean down, boolean right, float[][] out) {
        float dx = W[0] - S[0], dy = W[1] - S[1], d2 = (float) Math.sqrt(dx * dx + dy * dy);
        float reach = (L1 + L2) * 0.78f, dz = down && d2 < reach ? (float) Math.sqrt(reach * reach - d2 * d2) : 0;
        float d = (float) Math.sqrt(d2 * d2 + dz * dz), maxd = L1 + L2 - 1, mind = Math.abs(L1 - L2) + 1;
        float k = d > maxd ? maxd / d : 1; // (out of reach: the arm straight towards it)
        float wx = dx * k, wy = dy * k, wz = dz * k;
        d = d * k;
        if (d < mind) { // (folded right up: keep the bones' triangle)
            if (d < 1e-3f) { wx = 0; wy = mind; wz = 0; } else { float f = mind / d; wx *= f; wy *= f; wz *= f; }
            d = mind;
        }
        float ux = wx / d, uy = wy / d, uz = wz / d;
        float a = (L1 * L1 - L2 * L2 + d * d) / (2 * d), h = (float) Math.sqrt(Math.max(0, L1 * L1 - a * a));
        // the elbow points: down, a little out and back (a hand in front) / out to the side and a little down (a raised hand)
        float side = right ? -1 : 1;
        float px = side * (down ? 0.4f : 1f), py = down ? 1f : 0.55f, pz = down ? -0.5f : -0.2f;
        float dot = px * ux + py * uy + pz * uz;
        px -= dot * ux; py -= dot * uy; pz -= dot * uz;
        float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (pl < 1e-4f) { px = side; py = 0; pl = 1; }
        float[] Eo = out[1], Wf = out[2];
        Eo[0] = S[0] + a * ux + h * px / pl; Eo[1] = S[1] + a * uy + h * py / pl;
        Wf[0] = S[0] + wx; Wf[1] = S[1] + wy;
        return out;
    }

    /** The trunk (no arms): its shoulders run to the shoulder joints, so it changes with them. */
    private void trunk(Pen p) {
        float rx = jR[0], ry = jR[1], lx = jL[0], ly = jL[1];
        p.begin();
        p.moveTo(146, 720);
        p.cubicTo(144, 668, 140, 624, 142, 592);                                   // the side, up to the armpit
        p.cubicTo(138, 566, rx - 38, ry + 12, rx - 37, ry - 4);                    // the shoulder's outside
        p.cubicTo(rx - 35, ry - 26, rx - 18, ry - 40, rx + 6, ry - 42);            // rounding over its top
        p.cubicTo(rx + 44, ry - 46, 244, 470, 262, 468);                           // the slope up to the neck
        p.cubicTo(286, 464, 314, 464, 338, 468);
        p.cubicTo(356, 470, lx - 44, ly - 46, lx - 6, ly - 42);
        p.cubicTo(lx + 18, ly - 40, lx + 35, ly - 26, lx + 37, ly - 4);
        p.cubicTo(lx + 38, ly + 12, 462, 566, 458, 592);
        p.cubicTo(460, 624, 456, 668, 454, 720);
        p.close();
    }

    /** The shoulder's round cap (the deltoid), stretched along the upper arm so it turns with it. */
    private static void cap(Pen p, float[][] a) {
        float[] S = a[0], E = a[1];
        float ux = E[0] - S[0], uy = E[1] - S[1], ul = (float) Math.sqrt(ux * ux + uy * uy);
        if (ul < 1e-3f) { ux = 0; uy = 1; } else { ux /= ul; uy /= ul; }
        p.begin();
        ellipse(p, S[0] + ux * 5, S[1] + uy * 5, ux, uy, R_SHOULDER + 6, R_SHOULDER + 3);
    }

    /** The clothing: pass 0 its outline, 1 its rim light, 2 its fill (the same gradient everywhere: no colour seams). */
    private static void paint(Pen p, int pass, Look lk) {
        if (pass == 0) p.stroke(lk.jacketDk, 5.5f);
        else if (pass == 1) p.strokeLinear(96, 0, 504, 0, RIM, RIM_AT, 3.4f);
        else p.fillLinear(0, 464, 0, 720, lk.torsoG, TORSO_AT);
    }

    /** The upper arm hangs down (its inner edge lies over his body, drawn as a line, not as the outline). */
    private static boolean hangs(float[][] a) {
        float ux = a[1][0] - a[0][0], uy = a[1][1] - a[0][1], ul = (float) Math.sqrt(ux * ux + uy * uy);
        return ul >= 20 && uy >= 0.35f * ul;
    }

    /** +1 when the upper arm's normal (-uy, ux) points to his middle, else -1. */
    private static int inward(float[][] a) {
        float ux = a[1][0] - a[0][0], uy = a[1][1] - a[0][1];
        return (300 - a[0][0]) * -uy >= 0 ? 1 : -1;
    }

    /** The sleeve's inner edge against his body, from a quarter down the upper arm to f1 of the way (1 = the elbow). */
    private static void armLine(Pen p, float[][] a, Look lk, float f0, float f1) {
        if (!hangs(a)) return;
        float[] S = a[0], E = a[1];
        float ux = E[0] - S[0], uy = E[1] - S[1], ul = (float) Math.sqrt(ux * ux + uy * uy);
        int in = inward(a);
        float nx = -uy / ul * in, ny = ux / ul * in;
        float r0 = R_SHOULDER + (R_ELBOW - R_SHOULDER) * f0, r1 = R_SHOULDER + (R_ELBOW - R_SHOULDER) * f1;
        p.begin();
        p.moveTo(S[0] + ux * f0 + nx * r0, S[1] + uy * f0 + ny * r0);
        p.lineTo(S[0] + ux * f1 + nx * r1, S[1] + uy * f1 + ny * r1);
        p.stroke(lk.jacketDk, 2.6f);
    }

    /** An ellipse with its first axis along (ux, uy) (a unit vector), radii ra (along) and rb (across), as cubics. */
    private static void ellipse(Pen p, float cx, float cy, float ux, float uy, float ra, float rb) {
        float k = 0.5523f, ax = ux * ra, ay = uy * ra, bx = -uy * rb, by = ux * rb;
        p.moveTo(cx + ax, cy + ay);
        p.cubicTo(cx + ax + bx * k, cy + ay + by * k, cx + bx + ax * k, cy + by + ay * k, cx + bx, cy + by);
        p.cubicTo(cx + bx - ax * k, cy + by - ay * k, cx - ax + bx * k, cy - ay + by * k, cx - ax, cy - ay);
        p.cubicTo(cx - ax - bx * k, cy - ay - by * k, cx - bx - ax * k, cy - by - ay * k, cx - bx, cy - by);
        p.cubicTo(cx - bx + ax * k, cy - by + ay * k, cx + ax - bx * k, cy + ay - by * k, cx + ax, cy + ay);
        p.close();
    }

    /** The forearm (with the upper arm's lower half again, so the elbow has no seam), the cuff and the hand. */
    private void arm(Pen p, float[][] a, Hand h, int sx, Look lk, Skin sk) {
        float[] S = a[0], E = a[1], Wr = a[2];
        float ux = E[0] - S[0], uy = E[1] - S[1], ul = (float) Math.sqrt(ux * ux + uy * uy);
        // the upper arm's lower half again (cut square at its middle, by a clip, so the cut leaves no mark), then the forearm:
        // outlined together, filled together, so the elbow has no seam. While the arm hangs, its inner edge is a line.
        boolean upper = ul > 8, hang = upper && hangs(a);
        int in = upper ? inward(a) : 1;
        float nx = 0, ny = 0, mx = 0, my = 0, f = 0.45f, rm = R_SHOULDER + (R_ELBOW - R_SHOULDER) * f;
        if (upper) { nx = -uy / ul; ny = ux / ul; mx = S[0] + ux * f; my = S[1] + uy * f; }
        for (int pass = 0; pass < 3; pass++) {
            if (upper) {
                p.save();
                p.begin(); // (everything on the elbow's side of the line across the arm's middle)
                p.moveTo(mx + nx * 400, my + ny * 400); p.lineTo(mx - nx * 400, my - ny * 400);
                p.lineTo(mx - nx * 400 + ux / ul * 600, my - ny * 400 + uy / ul * 600); p.lineTo(mx + nx * 400 + ux / ul * 600, my + ny * 400 + uy / ul * 600);
                p.close();
                p.clip();
                float sx0 = S[0] + ux * 0.3f, sy0 = S[1] + uy * 0.3f, r0 = R_SHOULDER + (R_ELBOW - R_SHOULDER) * 0.3f;
                p.begin();
                if (pass == 2) {
                    p.moveTo(sx0 + nx * r0, sy0 + ny * r0); p.lineTo(E[0] + nx * R_ELBOW, E[1] + ny * R_ELBOW);
                    p.lineTo(E[0] - nx * R_ELBOW, E[1] - ny * R_ELBOW); p.lineTo(sx0 - nx * r0, sy0 - ny * r0); p.close();
                } else {
                    if (!hang || in < 0) { p.moveTo(sx0 + nx * r0, sy0 + ny * r0); p.lineTo(E[0] + nx * R_ELBOW, E[1] + ny * R_ELBOW); }
                    if (!hang || in > 0) { p.moveTo(sx0 - nx * r0, sy0 - ny * r0); p.lineTo(E[0] - nx * R_ELBOW, E[1] - ny * R_ELBOW); }
                }
                paint(p, pass, lk);
                p.restore();
            }
            tube(p, E[0], E[1], Wr[0], Wr[1], R_ELBOW, R_WRIST); paint(p, pass, lk);
        }
        if (hang) {
            p.save();
            p.begin();
            p.moveTo(mx + nx * 400, my + ny * 400); p.lineTo(mx - nx * 400, my - ny * 400);
            p.lineTo(mx - nx * 400 + ux / ul * 600, my - ny * 400 + uy / ul * 600); p.lineTo(mx + nx * 400 + ux / ul * 600, my + ny * 400 + uy / ul * 600);
            p.close();
            p.clip();
            armLine(p, a, lk, 0.3f, 1);
            p.restore();
        }
        float dx = Wr[0] - E[0], dy = Wr[1] - E[1], L = (float) Math.sqrt(dx * dx + dy * dy);
        if (L < 1) L = 1;
        float cl = Math.min(18, L * 0.5f);
        band(p, Wr[0] - dx / L * cl, Wr[1] - dy / L * cl, Wr[0] - dx / L * 4, Wr[1] - dy / L * 4, 26);
        p.fill(lk.cuff); p.stroke(lk.jacketDk, 2);
        if ("rest".equals(h.s)) return;
        float[][] caps = capsOf(h.s, sx);
        float an = (float) Math.toRadians(h.a), c = (float) Math.cos(an), s = (float) Math.sin(an);
        // outlines first (palm + fingers), then the skin, then the creases
        p.save(); p.translate(Wr[0], Wr[1]); p.rotate(h.a); palm(p, h.s, sx); p.stroke(sk.line, 3.6f); p.restore();
        for (float[] k : caps) { capTube(p, k, Wr, c, s, k[4] + 1.8f); p.fill(sk.line); }
        p.save(); p.translate(Wr[0], Wr[1]); p.rotate(h.a); palm(p, h.s, sx); p.fill(sk.base); p.restore();
        for (float[] k : caps) { capTube(p, k, Wr, c, s, k[4]); p.fill(sk.base); }
        p.save(); p.translate(Wr[0], Wr[1]); p.rotate(h.a); creases(p, h.s, sx); p.stroke(alpha(sk.line, 0.55f), 1.8f); p.restore();
    }

    private static void capTube(Pen p, float[] k, float[] W, float c, float s, float r) {
        float ax = W[0] + k[0] * c - k[1] * s, ay = W[1] + k[0] * s + k[1] * c;
        float bx = W[0] + k[2] * c - k[3] * s, by = W[1] + k[2] * s + k[3] * c;
        tube(p, ax, ay, bx, by, r, r);
    }

    /** Fingers and thumb as capsules {x1, y1, x2, y2, r} in hand units (wrist at 0,0, fingers up). */
    private static float[][] caps(String shape, int sx) {
        float[][] c;
        switch (shape) {
            case "open": c = new float[][]{{16, -52, 22, -96, 7}, {5, -54, 6, -104, 7.2f}, {-7, -53, -10, -99, 7}, {-19, -49, -27, -84, 6}, {20, -16, 34, -38, 7.6f}, {34, -38, 40, -58, 7}}; break;
            case "thumb": c = new float[][]{{10, -70, 12, -116, 9.5f}}; break;
            case "point": c = new float[][]{{12, -58, 14, -112, 7.2f}, {-16, -38, 10, -32, 7.6f}}; break;
            case "side": c = new float[][]{{8, -22, 22, -50, 7}}; break;
            case "hold": c = new float[][]{{16, -30, -6, -52, 8.4f}}; break; // (a fist round a handle: the thumb across)
            default: c = new float[0][];
        }
        for (float[] k : c) { k[0] *= sx; k[2] *= sx; }
        return c;
    }

    // caps(shape, sx) made once per shape and side
    private static final java.util.HashMap<String, float[][]> CAPS_R = new java.util.HashMap<>(), CAPS_L = new java.util.HashMap<>();
    private static float[][] capsOf(String shape, int sx) {
        java.util.HashMap<String, float[][]> m = sx > 0 ? CAPS_R : CAPS_L;
        synchronized (m) {
            float[][] c = m.get(shape);
            if (c == null) m.put(shape, c = caps(shape, sx));
            return c;
        }
    }

    private static void palm(Pen p, String shape, int sx) {
        p.begin();
        switch (shape) {
            case "open": p.moveTo(-20 * sx, 2); p.cubicTo(-25 * sx, -16, -28 * sx, -36, -26 * sx, -54); p.lineTo(24 * sx, -54); p.cubicTo(26 * sx, -36, 25 * sx, -14, 20 * sx, 2); break;
            case "thumb": case "hold": p.moveTo(-24 * sx, -4); p.cubicTo(-29 * sx, -24, -29 * sx, -58, -24 * sx, -74); p.cubicTo(-14 * sx, -82, 14 * sx, -82, 24 * sx, -72); p.cubicTo(29 * sx, -54, 29 * sx, -22, 22 * sx, -4); break;
            case "point": p.moveTo(-22 * sx, -2); p.cubicTo(-27 * sx, -20, -27 * sx, -46, -20 * sx, -60); p.cubicTo(-8 * sx, -66, 14 * sx, -66, 22 * sx, -56); p.cubicTo(27 * sx, -40, 26 * sx, -18, 20 * sx, -2); break;
            case "side": p.moveTo(-9 * sx, 2); p.cubicTo(-14 * sx, -30, -15 * sx, -72, -9 * sx, -100); p.cubicTo(-5 * sx, -112, 7 * sx, -112, 10 * sx, -100); p.cubicTo(15 * sx, -72, 15 * sx, -30, 12 * sx, 2); break;
            default: return;
        }
        p.close();
    }

    private static void creases(Pen p, String shape, int sx) {
        p.begin();
        switch (shape) {
            case "open": p.moveTo(-14 * sx, -26); p.quadTo(0, -18, 14 * sx, -30); break;
            case "thumb":
                p.moveTo(-24 * sx, -58); p.quadTo(-4 * sx, -54, 12 * sx, -58);
                p.moveTo(-24 * sx, -42); p.quadTo(-4 * sx, -38, 16 * sx, -42);
                p.moveTo(-24 * sx, -26); p.quadTo(-4 * sx, -22, 16 * sx, -26); break;
            case "point":
                p.moveTo(-22 * sx, -50); p.quadTo(-6 * sx, -48, 4 * sx, -54);
                p.moveTo(-24 * sx, -22); p.quadTo(-8 * sx, -18, 8 * sx, -20); break;
            case "side":
                p.moveTo(-3 * sx, -62); p.lineTo(-3 * sx, -104); p.moveTo(3 * sx, -64); p.lineTo(3 * sx, -104);
                p.moveTo(-8 * sx, -60); p.quadTo(-2 * sx, -56, 8 * sx, -60); break;
            case "hold": // (the curled fingers)
                p.moveTo(-25 * sx, -60); p.quadTo(-14 * sx, -57, -4 * sx, -61);
                p.moveTo(-26 * sx, -44); p.quadTo(-14 * sx, -41, -2 * sx, -45);
                p.moveTo(-25 * sx, -28); p.quadTo(-14 * sx, -25, -3 * sx, -29); break;
            default: break;
        }
    }

    private static void band(Pen p, float ax, float ay, float bx, float by, float r) {
        float dx = bx - ax, dy = by - ay, L = (float) Math.sqrt(dx * dx + dy * dy);
        if (L < 1e-3f) L = 1;
        float nx = -dy / L * r, ny = dx / L * r;
        p.begin(); p.moveTo(ax + nx, ay + ny); p.lineTo(bx + nx, by + ny); p.lineTo(bx - nx, by - ny); p.lineTo(ax - nx, ay - ny); p.close();
    }

    /** A capsule from a to b (radius ra at a, rb at b). */
    private static void tube(Pen p, float[] a, float[] b, float ra, float rb) { tube(p, a[0], a[1], b[0], b[1], ra, rb); }

    static void tube(Pen p, float ax, float ay, float bx, float by, float ra, float rb) {
        float dx = bx - ax, dy = by - ay, L = (float) Math.sqrt(dx * dx + dy * dy);
        if (L < 1e-3f) L = 1;
        float nx = -dy / L, ny = dx / L, ux = dx / L, uy = dy / L, k = 0.552f;
        p.begin();
        p.moveTo(ax + nx * ra, ay + ny * ra);
        p.lineTo(bx + nx * rb, by + ny * rb);
        p.cubicTo(bx + nx * rb + ux * rb * k, by + ny * rb + uy * rb * k, bx + ux * rb + nx * rb * k, by + uy * rb + ny * rb * k, bx + ux * rb, by + uy * rb);
        p.cubicTo(bx + ux * rb - nx * rb * k, by + uy * rb - ny * rb * k, bx - nx * rb + ux * rb * k, by - ny * rb + uy * rb * k, bx - nx * rb, by - ny * rb);
        p.lineTo(ax - nx * ra, ay - ny * ra);
        p.cubicTo(ax - nx * ra - ux * ra * k, ay - ny * ra - uy * ra * k, ax - ux * ra - nx * ra * k, ay - uy * ra - ny * ra * k, ax - ux * ra, ay - uy * ra);
        p.cubicTo(ax - ux * ra + nx * ra * k, ay - uy * ra + ny * ra * k, ax + nx * ra - ux * ra * k, ay + ny * ra - uy * ra * k, ax + nx * ra, ay + ny * ra);
        p.close();
    }

    // ================================================================ fixed shapes (SVG path data: M L C Q Z only)
    private static final String FACE = "M -108 -74 C -115 -10 -110 36 -90 76 C -72 110 -38 130 0 132 C 38 130 72 110 90 76 C 110 36 115 -10 108 -74 C 100 -154 -100 -154 -108 -74 Z";
    private static final String EAR_L = "M -102 -4 C -122 -18 -134 18 -120 40 C -112 54 -100 52 -95 40 Z";
    private static final String EAR_R = "M 102 -4 C 122 -18 134 18 120 40 C 112 54 100 52 95 40 Z";
    private static final String EAR_IN = "M -104 8 C -116 4 -120 22 -112 32 M 104 8 C 116 4 120 22 112 32";
    private static final String JAW_SH = "M -80 86 C -62 116 -32 130 0 132 C 32 130 62 116 80 86 C 60 118 30 126 0 126 C -30 126 -60 118 -80 86 Z";
    private static final String NOSE = "M -5 6 C -6 22 -9 34 -12 42";
    private static final String NOSE_SH = "M 3 2 C 8 20 12 34 15 44 C 10 47 6 47 2 45 C 4 32 4 16 3 2 Z";
    private static final String NOSTRIL = "M -16 46 C -21 52 -12 58 -5 54 M 16 46 C 21 52 12 58 5 54";
    private static final String NOSE_TIP = "M -9 52 C -4 57 4 57 9 52";
    private static final String NECK = "M 262 330 L 260 470 C 280 486 320 486 340 470 L 338 330 Z";
    private static final String NECK_SH = "M 262 396 C 280 436 320 436 338 396 L 338 448 C 318 464 282 464 262 448 Z";
    private static final String HOOD_C = "M 196 470 C 210 432 250 420 300 422 C 350 420 390 432 404 470 C 380 452 340 446 300 446 C 260 446 220 452 196 470 Z";
    // hair: {back, top, part, strands}
    private static final String[] HAIR_A = {
            "M -120 6 C -138 -70 -128 -152 -70 -182 C -20 -206 52 -202 94 -172 C 134 -142 138 -66 120 6 Z",
            "M -108 2 C -106 4 -104 2 -103 -6 C -100 -32 -98 -52 -92 -66 C -80 -84 -62 -94 -44 -98 C -10 -114 52 -106 86 -86 C 99 -78 105 -66 106 -50 C 106 -30 106 -14 104 -6 C 105 2 107 4 109 2 C 129 -60 125 -130 78 -166 C 40 -192 -40 -196 -86 -166 C -124 -138 -131 -64 -113 0 Z",
            "M -44 -99 C -46 -126 -42 -152 -32 -178",
            "M -40 -104 C -6 -140 58 -142 100 -100 M -30 -118 C 4 -150 56 -160 92 -134 M -60 -110 C -76 -126 -88 -140 -94 -150 M -76 -96 C -90 -110 -100 -124 -106 -136 M 0 -108 C 36 -116 70 -104 96 -76"};
    private static final String[] HAIR_B = {
            "M -122 10 C -140 -70 -130 -156 -70 -186 C -20 -210 52 -206 96 -176 C 136 -146 140 -66 122 10 Z",
            "M -108 6 C -106 8 -104 6 -103 -2 C -100 -28 -98 -48 -94 -60 C -90 -66 -86 -66 -82 -60 C -78 -72 -70 -76 -62 -70 C -58 -84 -46 -88 -36 -80 C -30 -94 -16 -96 -6 -86 C 2 -98 18 -98 26 -88 C 34 -98 50 -96 56 -84 C 66 -92 80 -88 84 -76 C 94 -80 104 -70 104 -56 C 106 -36 106 -14 104 -2 C 105 6 107 8 109 6 C 133 -64 129 -136 80 -174 C 40 -200 -40 -202 -86 -172 C -127 -142 -135 -64 -113 4 Z",
            "M -6 -92 C 0 -120 6 -150 4 -184",
            "M -70 -84 C -78 -110 -90 -130 -100 -140 M -40 -92 C -46 -126 -54 -152 -62 -170 M 20 -96 C 30 -130 38 -156 40 -180 M 54 -92 C 72 -118 90 -136 104 -146 M -20 -150 C 0 -164 30 -168 56 -160"};
    private static final String[] HAIR_C = {
            "M -118 0 C -134 -70 -126 -148 -70 -176 C -20 -200 52 -196 92 -168 C 130 -140 134 -66 118 0 Z",
            "M -107 -4 C -105 -2 -103 -4 -102 -12 C -100 -36 -98 -56 -90 -72 C -70 -92 -30 -98 0 -98 C 30 -98 70 -94 90 -74 C 98 -60 104 -40 103 -12 C 104 -4 106 -2 108 -4 C 124 -60 122 -110 96 -140 C 106 -162 92 -186 66 -192 C 56 -210 18 -214 -6 -198 C -32 -206 -66 -196 -80 -174 C -112 -158 -127 -96 -111 -6 Z",
            "M 2 -100 C 10 -130 22 -160 36 -190",
            "M -50 -112 C -44 -140 -30 -168 -10 -190 M -16 -110 C -6 -142 10 -172 30 -196 M 24 -108 C 36 -138 54 -162 72 -180 M 56 -100 C 72 -122 88 -138 100 -146 M -84 -100 C -88 -124 -84 -148 -74 -166"};

    // the fixed shapes are read once into numbers
    private static final java.util.Map<String, float[]> parsed = new java.util.HashMap<>();

    /** An SVG path (absolute M L C Q Z) into the pen, starting a new path. */
    static void path(Pen p, String d) {
        float[] ops;
        synchronized (parsed) {
            ops = parsed.get(d);
            if (ops == null) { ops = parse(d); parsed.put(d, ops); }
        }
        p.begin();
        int i = 0;
        while (i < ops.length) {
            int op = (int) ops[i++];
            switch (op) {
                case 'M': p.moveTo(ops[i], ops[i + 1]); i += 2; break;
                case 'L': p.lineTo(ops[i], ops[i + 1]); i += 2; break;
                case 'Q': p.quadTo(ops[i], ops[i + 1], ops[i + 2], ops[i + 3]); i += 4; break;
                case 'C': p.cubicTo(ops[i], ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4], ops[i + 5]); i += 6; break;
                case 'Z': p.close(); break;
                default: return;
            }
        }
    }

    static float[] parse(String d) {
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        String[] tok = d.trim().split("[\\s,]+");
        char cmd = 0;
        int need = 0, got = 0;
        for (String t : tok) {
            if (t.isEmpty()) continue;
            char c0 = t.charAt(0);
            if (Character.isLetter(c0)) {
                cmd = c0;
                need = cmd == 'M' || cmd == 'L' ? 2 : cmd == 'Q' ? 4 : cmd == 'C' ? 6 : 0;
                got = 0;
                out.add((float) cmd);
                if (need == 0) continue;
                if (t.length() > 1) { out.add(Float.parseFloat(t.substring(1))); got++; }
                continue;
            }
            if (got == need) { out.add((float) (cmd == 'M' ? 'L' : cmd)); got = 0; } // repeated coordinates
            out.add(Float.parseFloat(t));
            got++;
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    // ================================================================ small helpers
    static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * clamp(a, 0, 1));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }

    // ---- for the desk test: a fixed face at a fixed time
    void pose(String mood, String gest) {
        Goal g = goal(mood);
        System.arraycopy(g.v, 0, cur, 0, N);
        eyeL = g.eyeL; eyeR = g.eyeR;
        Arm aR = tgR, aL = tgL;
        targets(gest != null ? gest : g.gesture, 1.2, aR, aL);
        wR[0] = aR.x; wR[1] = aR.y; hR.s = aR.s; hR.a = aR.a; hR.down = aR.down;
        wL[0] = aL.x; wL[1] = aL.y; hL.s = aL.s; hL.a = aL.a; hL.down = aL.down;
        t = 1.2; blink = -1; saccX = saccY = 0;
    }
}
