package com.anil.jarvis.watch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Jarvis's face: an original, drawn human head (hair, ears, brows, eyes, nose, lips, neck and shoulders in a suit)
 * that lives like a person. It blinks, looks around (or at Anil, when the front camera sees him), breathes, nods
 * while he talks, looks up and away while thinking, and moves its lips with Jarvis's voice: syllable by syllable from
 * the words being spoken (Telugu or English), opened by the real loudness of the voice when there is one. Its
 * expression follows the feeling of the reply (happy, laughing, sad, worried, surprised...).
 *
 * Plain Java only (no Android classes): it draws through a Pen, so the phone (FaceView) and a desktop test can both
 * draw it. (A copy of the phone app's FaceRig for the watch's face, W3: keep both the same.) Units: the face is about 2 wide; x grows to the right, y downwards; (0, 0) is between the eyes.
 */
final class FaceRig {
    // the same numbers as OrbView's IDLE, LISTENING, THINKING, SPEAKING, OFFLINE
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, OFFLINE = 4;

    /** Where the face is drawn. Coordinates are face units; the pen maps them with the frame it was given. */
    interface Pen {
        void frame(float ox, float oy, float unit);
        void begin();
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void quadTo(float x1, float y1, float x, float y);
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);
        void addOval(float cx, float cy, float rx, float ry);
        void close();
        void fill(int argb);
        void fillLinear(float x0, float y0, float x1, float y1, int c0, int c1);
        void fillRadial(float cx, float cy, float r, int[] colors, float[] stops);
        void stroke(int argb, float width);
        void save();
        void restore();
        void clip();
        void translate(float dx, float dy);
        void rotate(float degrees, float px, float py);
    }

    /** The colours of one look. */
    static final class Look {
        int glow, skin, skinLight, skinDark, shade, blush, hair, hairHi, brow, lipUp, lipLow, lipHi, mouth, teeth, tongue,
                sclera, iris, irisLight, pupil, lash, suit, suitLight, shirt, accent, edge, tear;
        boolean holo;
    }

    /** A person: warm brown skin, black hair, dark suit, with the theme colour as a small light on the jacket. */
    static Look human(int accent) {
        Look l = new Look();
        l.glow = withAlpha(accent, 0x38);
        l.skin = 0xFFC48A62; l.skinLight = 0xFFDDA780; l.skinDark = 0xFF94603F; l.shade = 0xFF5A3422; l.blush = 0xFFD2645A;
        l.hair = 0xFF16100C; l.hairHi = 0xFF4A382B; l.brow = 0xFF1C140F;
        l.lipUp = 0xFF8F4C43; l.lipLow = 0xFFA85E52; l.lipHi = 0xFFD99A8A; l.mouth = 0xFF3A1311; l.teeth = 0xFFF2EDE4; l.tongue = 0xFFB0525A;
        l.sclera = 0xFFF6F1EA; l.iris = 0xFF3E2516; l.irisLight = 0xFF8A5C3A; l.pupil = 0xFF0C0705; l.lash = 0xFF1A110C;
        l.suit = 0xFF101A2C; l.suitLight = 0xFF22324C; l.shirt = 0xFFE9EEF4; l.accent = accent; l.edge = 0; l.tear = 0xCCBFE4FF;
        return l;
    }

    /** A hologram in the theme colour: see-through light with glowing edges and scan lines. */
    static Look hologram(int c) {
        Look l = new Look();
        l.holo = true;
        int w = mix(c, 0xFFFFFFFF, 0.55f);
        l.glow = withAlpha(c, 0x48);
        l.skin = withAlpha(c, 0x3C); l.skinLight = withAlpha(w, 0x55); l.skinDark = withAlpha(c, 0x22); l.shade = withAlpha(mix(c, 0xFF000000, 0.6f), 0x90);
        l.blush = withAlpha(w, 0x40);
        l.hair = withAlpha(c, 0x70); l.hairHi = withAlpha(w, 0xA0); l.brow = withAlpha(w, 0xD0);
        l.lipUp = withAlpha(c, 0x90); l.lipLow = withAlpha(c, 0xA0); l.lipHi = withAlpha(w, 0x90); l.mouth = withAlpha(mix(c, 0xFF000000, 0.7f), 0xC0);
        l.teeth = withAlpha(w, 0xC0); l.tongue = withAlpha(c, 0x80);
        l.sclera = withAlpha(w, 0x70); l.iris = withAlpha(c, 0xFF); l.irisLight = 0xFFFFFFFF; l.pupil = withAlpha(mix(c, 0xFF000000, 0.75f), 0xFF);
        l.lash = withAlpha(w, 0xE0);
        l.suit = withAlpha(c, 0x30); l.suitLight = withAlpha(c, 0x50); l.shirt = withAlpha(w, 0x40); l.accent = w; l.edge = withAlpha(w, 0xC8);
        l.tear = withAlpha(w, 0xD0);
        return l;
    }

    // ================================================================ what is going on (set from outside)

    private final Random rnd = new Random();
    private float t;                       // seconds since the face started
    private int mode = IDLE;
    private String feeling = "calm";
    private float feelingLeft;             // seconds the feeling stays once Jarvis has finished talking
    private float mic;                     // his voice, 0..1 (while listening)
    private float voice = -1, voiceAge = 99f; // Jarvis's own voice loudness, 0..1, and how old that number is
    private boolean seen;                  // the front camera sees someone
    private float lookX, lookY, seenAge = 99f, userSmile;
    private float greetLeft;

    void setMode(int m) {
        if (m == mode) return;
        if (m == SPEAKING) { speakT = 0; wordsHeard = 0; wordAt = -1; syls.clear(); cur = null; }
        if (mode == SPEAKING) { syls.clear(); cur = null; }
        if (m == LISTENING) listenSide = rnd.nextBoolean() ? 1 : -1;
        if (m == THINKING) { thinkSide = rnd.nextBoolean() ? 1 : -1; nextThinkSwitch = t + 1.8f + rnd.nextFloat(); }
        mode = m;
    }

    int mode() { return mode; }

    void setMic(float l) { mic = clamp(l, 0, 1); }

    /** Jarvis's voice loudness now (0..1); without it the lips follow the syllables alone. */
    void setVoice(float l) { voice = clamp(l, 0, 1); voiceAge = 0; }

    /** The feeling of what Jarvis is saying (the reply's tag: happy, laugh, sad...). */
    void setFeeling(String f) {
        feeling = f == null || f.isEmpty() ? "calm" : f.toLowerCase(Locale.ROOT);
        feelingLeft = 7f;
    }

    /** Someone in front of the phone: where (-1..1, as he sees the screen), and how much he smiles (0..1). */
    void look(boolean present, float x, float y, float smile) {
        seen = present;
        if (present) { lookX = clamp(x, -1, 1); lookY = clamp(y, -1, 1); userSmile = clamp(smile, 0, 1); seenAge = 0; }
    }

    /** A person he knows came into view: eyebrows flash up, a big smile, a little nod. */
    void greet() { greetLeft = 2.6f; nodAt = t + 0.25f; }

    // ================================================================ speaking: syllables -> lip shapes

    private static final class Syl {
        final float open, wide, round, smile, len;
        final boolean shut;   // the lips meet first (ప బ మ, p b m)
        Syl(float open, float wide, float round, float smile, float len, boolean shut) {
            this.open = open; this.wide = wide; this.round = round; this.smile = smile; this.len = len; this.shut = shut;
        }
    }

    private static Syl shape(char v, boolean longV, boolean shut) {
        float len = longV ? 1.3f : 1f;
        switch (v) {
            case 'e': return new Syl(0.50f, 1.12f, 0f, 0.08f, len, shut);
            case 'i': return new Syl(0.32f, 1.16f, 0f, 0.15f, len, shut);
            case 'o': return new Syl(0.60f, 0.80f, 0.75f, 0f, len, shut);
            case 'u': return new Syl(0.30f, 0.72f, 1f, 0f, len, shut);
            default: return new Syl(0.80f, 1f, 0f, 0f, len, shut);
        }
    }

    private static final Syl GENERIC = new Syl(0.6f, 1f, 0f, 0f, 1f, false);

    private final ArrayDeque<Syl> syls = new ArrayDeque<>();
    private Syl cur;
    private float curT, sylDur = 0.145f, speakT, wordAt = -1;
    private int wordSyls, wordsHeard;

    /** The word Jarvis has just started saying. */
    void word(String w) {
        if (mode != SPEAKING || w == null) return;
        if (wordAt >= 0 && wordSyls > 0) { // learn how fast this voice talks from the last word
            float per = (t - wordAt) / wordSyls;
            if (per > 0.07f && per < 0.4f) sylDur += (clamp(per, 0.09f, 0.26f) - sylDur) * 0.3f;
        }
        List<Syl> list = syllables(w);
        syls.clear();
        syls.addAll(list);
        cur = null;
        curT = 0;
        wordAt = t;
        wordSyls = list.size();
        wordsHeard++;
        if (list.size() >= 3 && rnd.nextFloat() < 0.25f) browFlashAt = t; // a little emphasis now and then
        if (rnd.nextFloat() < 0.16f) nodAt = t;
    }

    /** The syllables of a Telugu or English word, each with the lip shape of its vowel. */
    static List<Syl> syllables(String word) {
        List<Syl> out = new ArrayList<>();
        String s = word.toLowerCase(Locale.ROOT);
        boolean shutNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (c >= 0x0C15 && c <= 0x0C39) {                 // Telugu consonant
                boolean shut = c == 0x0C2A || c == 0x0C2B || c == 0x0C2C || c == 0x0C2D || c == 0x0C2E; // ప ఫ బ భ మ
                if (n == 0x0C4D) { shutNext |= shut; i++; continue; } // ్ : joined to the next letter
                if (n >= 0x0C3E && n <= 0x0C4C) {             // vowel sign
                    out.add(telugu(n, shut || shutNext));
                    i++;
                } else {
                    out.add(shape('a', false, shut || shutNext)); // the letter's own 'a'
                }
                shutNext = false;
            } else if (c >= 0x0C05 && c <= 0x0C14) {          // a vowel letter
                out.add(telugu(c, shutNext));
                shutNext = false;
            } else if (c >= 'a' && c <= 'z') {
                if (isVowel(c)) {
                    // one syllable per group of vowels; a silent e at the end of a longer word is skipped
                    if (c == 'e' && i == s.length() - 1 && out.size() > 0 && i > 2 && !isVowel(s.charAt(i - 1))) continue;
                    char v = c;
                    if ((c == 'o' && (n == 'o' || n == 'u')) || (c == 'u' && n == 'e')) v = 'u';
                    else if (c == 'e' && n == 'e') v = 'i';
                    else if (c == 'y') v = 'i';
                    out.add(shape(v, false, shutNext));
                    shutNext = false;
                    while (i + 1 < s.length() && isVowel(s.charAt(i + 1))) i++;
                } else {
                    shutNext = c == 'm' || c == 'b' || c == 'p';
                }
            } else if (c >= '0' && c <= '9') {
                out.add(shape('a', false, false));
                out.add(shape('e', false, false));
            }
        }
        return out;
    }

    private static boolean isVowel(char c) { return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'; }

    private static Syl telugu(char v, boolean shut) {
        switch (v) {
            case 0x0C06: case 0x0C3E: return shape('a', true, shut);   // ఆ ా
            case 0x0C07: case 0x0C3F: return shape('i', false, shut);  // ఇ ి
            case 0x0C08: case 0x0C40: return shape('i', true, shut);   // ఈ ీ
            case 0x0C09: case 0x0C41: case 0x0C0B: case 0x0C43: return shape('u', false, shut); // ఉ ు ఋ ృ
            case 0x0C0A: case 0x0C42: case 0x0C44: return shape('u', true, shut);              // ఊ ూ ౄ
            case 0x0C0E: case 0x0C46: return shape('e', false, shut);  // ఎ ె
            case 0x0C0F: case 0x0C47: case 0x0C10: case 0x0C48: return shape('e', true, shut); // ఏ ే ఐ ై
            case 0x0C12: case 0x0C4A: return shape('o', false, shut);  // ఒ ొ
            case 0x0C13: case 0x0C4B: case 0x0C14: case 0x0C4C: return shape('o', true, shut); // ఓ ో ఔ ౌ
            default: return shape('a', false, shut);                   // అ and the rest
        }
    }

    private Syl babble() {
        char[] v = {'a', 'e', 'a', 'i', 'o', 'a', 'e', 'u'};
        return shape(v[rnd.nextInt(v.length)], rnd.nextFloat() < 0.25f, rnd.nextFloat() < 0.22f);
    }

    // ================================================================ the living motion

    // smoothed values that are drawn
    private float gazeX, gazeY, yaw, pitch, roll, brow, browL, browR, inner, frown, lid = 1f, squint, blink,
            smile = 0.12f, smirk, open, wide = 1f, round, cheek, tears, breath, env;
    // timers
    private float nextBlink = 1.5f, blinkAt = -1, nextSaccade, wanderX, wanderY, microX, microY,
            nextThinkSwitch, nodAt = -99, browFlashAt = -99, lastMicHigh = -99, nextNodOk;
    private int thinkSide = 1, listenSide = 1;
    private boolean doubleBlink;

    /** One step of life: dt seconds since the last frame. */
    void update(float dt) {
        dt = clamp(dt, 0f, 0.1f);
        t += dt;
        voiceAge += dt;
        seenAge += dt;
        if (greetLeft > 0) greetLeft -= dt;
        if (mode != SPEAKING && feelingLeft > 0) { feelingLeft -= dt; if (feelingLeft <= 0) feeling = "calm"; }
        boolean present = seen && seenAge < 1.2f;

        // ---- the feeling: {smile, brows up, inner brows up (sad), frown, eyes open, squint, head tilt (deg), head down, cheeks, tears}
        float[] f = feel(mode == OFFLINE ? "calm" : feeling);
        float tSmile = f[0], tBrow = f[1], tInner = f[2], tFrown = f[3], tLid = f[4], tSquint = f[5], tRoll = f[6], tPitch = f[7],
                tCheek = f[8], tTears = f[9];
        float tOpen = 0, tWide = 1, tRound = 0, tSmirk = 0, tBrowL = 0, tBrowR = 0;
        if ("surprised".equals(feeling) && mode != SPEAKING) { tOpen = 0.28f; tRound = 0.7f; tWide = 0.85f; }
        if (present && userSmile > 0.6f && (mode == IDLE || mode == LISTENING)) // he smiles: Jarvis smiles back
            tSmile = Math.max(tSmile, 0.35f + (userSmile - 0.6f));
        if (greetLeft > 0) {
            tSmile = Math.max(tSmile, 0.75f);
            tCheek = Math.max(tCheek, 0.6f);
            tSquint = Math.max(tSquint, 0.3f);
            if (greetLeft > 2.15f) tBrow += 0.6f;
        }

        // ---- what Jarvis is doing
        switch (mode) {
            case LISTENING:
                tLid += 0.1f;
                tBrow += 0.12f;
                tSmile = Math.max(tSmile, 0.18f);
                tRoll += 4f * listenSide;
                if (mic > 0.5f) lastMicHigh = t;
                if (mic < 0.2f && t - lastMicHigh < 0.6f && t > nextNodOk) { nodAt = t; nextNodOk = t + 2.4f; } // "mm-hmm"
                break;
            case THINKING:
                if (t > nextThinkSwitch) { thinkSide = -thinkSide; nextThinkSwitch = t + 2.2f + rnd.nextFloat(); }
                tBrow += 0.08f;
                if (thinkSide > 0) tBrowR += 0.35f; else tBrowL += 0.35f;
                tSmirk = 0.35f * thinkSide;
                tWide = 0.9f;
                tSmile = Math.max(tSmile * 0.5f, 0.05f);
                tRoll += 3f * thinkSide;
                break;
            case OFFLINE:
                tLid = 0f;
                tPitch += 0.15f;
                tSmile = 0.05f;
                break;
            default:
                break;
        }

        // ---- lips while speaking
        float e = 0;
        if (mode == SPEAKING) {
            speakT += dt;
            float amp = voiceAge < 0.35f ? voice : -1f;
            if (cur != null) {
                curT += dt;
                if (curT >= sylDur * cur.len) { cur = syls.poll(); curT = 0; }
            } else if (!syls.isEmpty()) {
                cur = syls.poll();
                curT = 0;
            }
            if (cur == null && wordsHeard == 0 && amp < 0 && speakT > 0.6f) { cur = babble(); curT = 0; } // this voice gives no word timings
            Syl s = cur != null ? cur : amp > 0.05f ? GENERIC : null;
            if (s != null) {
                float p = cur != null ? Math.min(1f, curT / (sylDur * cur.len)) : 0.5f;
                if (amp >= 0) {
                    e = (float) Math.pow(clamp(amp * 1.2f, 0, 1), 0.8);
                    if (cur != null && s.shut && p < 0.22f) e *= 0.15f; // the lips meet on p / b / m
                } else if (s.shut) {
                    e = p < 0.25f ? 0f : (float) Math.pow(Math.sin(Math.PI * (p - 0.25f) / 0.75f), 0.7);
                } else {
                    e = 0.35f + 0.65f * (float) Math.pow(Math.sin(Math.PI * p), 0.7);
                }
                tOpen = s.open * e;
                tWide = s.wide;
                tRound = s.round;
                tSmile += s.smile * e;
            }
            if ("laugh".equals(feeling) && speakT < 1.3f) { // "హ హ హ"
                tOpen = Math.max(tOpen, 0.45f + 0.35f * (float) Math.abs(Math.sin(speakT * 15f)));
                tSquint = 0.7f;
            }
            if (amp > 0.85f && t - browFlashAt > 1.3f && rnd.nextFloat() < 0.04f) browFlashAt = t;
            tPitch += 0.035f * e; // the head moves a little with the voice
        } else if (smile > 0.5f) {
            tOpen = Math.max(tOpen, 0.14f * (smile - 0.5f) / 0.5f); // a big smile shows the teeth
        }
        env = e;

        // ---- where the eyes look
        float gx, gy;
        if (mode == OFFLINE) {
            gx = 0; gy = 0.3f;
        } else if (mode == THINKING) {
            gx = 0.55f * thinkSide; gy = -0.6f;
        } else if (present) { // at him, with the tiny eye movements a person makes
            if (t > nextSaccade) {
                microX = (rnd.nextFloat() - 0.5f) * 0.08f;
                microY = (rnd.nextFloat() - 0.5f) * 0.06f;
                nextSaccade = t + 0.5f + rnd.nextFloat() * 0.9f;
                maybeBlink(0.08f);
            }
            gx = clamp(lookX * 1.1f + microX, -1, 1);
            gy = clamp(lookY * 0.9f + microY, -1, 1);
        } else if (mode == LISTENING || mode == SPEAKING) { // at him (out of the screen), glancing away now and then
            if (t > nextSaccade) {
                boolean away = rnd.nextFloat() < (mode == SPEAKING ? 0.22f : 0.08f);
                wanderX = away ? (rnd.nextBoolean() ? 1 : -1) * (0.4f + 0.3f * rnd.nextFloat()) : (rnd.nextFloat() - 0.5f) * 0.12f;
                wanderY = away ? (rnd.nextFloat() - 0.3f) * 0.4f : (rnd.nextFloat() - 0.5f) * 0.1f;
                nextSaccade = t + (away ? 0.6f + 0.6f * rnd.nextFloat() : 1f + 2f * rnd.nextFloat());
                if (away) maybeBlink(0.35f);
            }
            gx = wanderX; gy = wanderY;
        } else { // idle: looking around the room
            if (t > nextSaccade) {
                boolean far = rnd.nextFloat() < 0.2f;
                wanderX = (rnd.nextFloat() - 0.5f) * (far ? 1.5f : 0.6f);
                wanderY = (rnd.nextFloat() - 0.5f) * (far ? 0.8f : 0.35f);
                nextSaccade = t + 0.8f + rnd.nextFloat() * 2.8f;
                if (far) maybeBlink(0.4f);
            }
            gx = wanderX; gy = wanderY;
        }
        if (tInner > 0.5f) gy = Math.max(gy, 0.25f); // sad: eyes go down

        // ---- the head follows the eyes slowly, nods, never quite still
        float tYaw = gx * 0.4f + (mode == SPEAKING ? 0.12f * (float) Math.sin(t * 0.9f) : 0.05f * (float) Math.sin(t * 0.37f));
        tPitch += gy * 0.25f;
        float n = t - nodAt;
        if (n >= 0 && n < 0.55f) tPitch += 0.16f * (float) Math.sin(Math.PI * n / 0.55f);
        tRoll += 1.5f * (float) Math.sin(t * 0.45f);
        float flash = t - browFlashAt;
        float flashUp = flash >= 0 && flash < 0.45f ? 0.25f * (1 - flash / 0.45f) : 0;

        // ---- blinking
        if (blinkAt < 0 && t > nextBlink && mode != OFFLINE) blinkAt = t;
        float bl = 0;
        if (blinkAt >= 0) {
            float b = t - blinkAt;
            if (b < 0) bl = 0;
            else if (b < 0.07f) bl = b / 0.07f;
            else if (b < 0.10f) bl = 1;
            else if (b < 0.21f) bl = 1 - (b - 0.10f) / 0.11f;
            else if (doubleBlink) { doubleBlink = false; blinkAt = t + 0.06f; }
            else {
                blinkAt = -1;
                doubleBlink = rnd.nextFloat() < 0.12f;
                nextBlink = t + (1.8f + rnd.nextFloat() * 4.2f) * (mode == THINKING ? 1.5f : 1f);
            }
        }

        // ---- ease everything towards where it is going
        gazeX = ease(gazeX, gx, 26, dt);
        gazeY = ease(gazeY, gy, 26, dt);
        yaw = ease(yaw, tYaw, 3.5f, dt);
        pitch = ease(pitch, tPitch, 4f, dt);
        roll = ease(roll, tRoll, 2.5f, dt);
        brow = ease(brow, tBrow + flashUp, 9, dt);
        browL = ease(browL, tBrowL, 7, dt);
        browR = ease(browR, tBrowR, 7, dt);
        inner = ease(inner, tInner, 6, dt);
        frown = ease(frown, tFrown, 6, dt);
        lid = ease(lid, tLid - 0.25f * Math.max(0, gy) + 0.08f * Math.max(0, -gy), 12, dt);
        squint = ease(squint, tSquint, 8, dt);
        smile = ease(smile, tSmile, 6, dt);
        smirk = ease(smirk, tSmirk, 5, dt);
        open = ease(open, tOpen, 24, dt);
        wide = ease(wide, tWide, 16, dt);
        round = ease(round, tRound, 16, dt);
        cheek = ease(cheek, tCheek + 0.5f * Math.max(0, smile), 6, dt);
        tears = ease(tears, tTears, 1.2f, dt);
        blink = mode == OFFLINE ? 1f : bl;
        breath = (float) Math.sin(t * (mode == OFFLINE ? 0.9f : 1.4f));
    }

    private void maybeBlink(float chance) {
        if (blinkAt < 0 && mode != OFFLINE && rnd.nextFloat() < chance) blinkAt = t;
    }

    private static float[] feel(String f) {
        switch (f) {
            case "happy": return new float[]{0.65f, 0.15f, 0, 0, 1f, 0.35f, 0, 0, 0.6f, 0};
            case "laugh": return new float[]{0.95f, 0.2f, 0, 0, 0.9f, 0.6f, 0, -0.15f, 0.9f, 0};
            case "excited": return new float[]{0.8f, 0.45f, 0, 0, 1.2f, 0.2f, 0, 0, 0.7f, 0};
            case "proud": return new float[]{0.55f, 0.1f, 0, 0, 1f, 0.25f, 0, -0.2f, 0.5f, 0};
            case "love": return new float[]{0.5f, 0.05f, 0.25f, 0, 0.9f, 0.35f, 5f, 0, 0.5f, 0};
            case "surprised": return new float[]{0f, 0.9f, 0.2f, 0, 1.35f, 0, 0, -0.05f, 0, 0};
            case "serious": return new float[]{-0.05f, -0.1f, 0, 0.35f, 0.95f, 0.15f, 0, 0.05f, 0, 0};
            case "worried": return new float[]{-0.25f, 0.1f, 0.7f, 0.2f, 1.05f, 0, 4f, 0, 0, 0};
            case "sorry": return new float[]{-0.15f, 0, 0.6f, 0, 0.9f, 0, 6f, 0.1f, 0, 0};
            case "sad": return new float[]{-0.55f, -0.05f, 0.85f, 0, 0.75f, 0, 5f, 0.18f, 0, 0};
            case "cry": return new float[]{-0.7f, 0, 1f, 0.1f, 0.6f, 0.3f, 6f, 0.2f, 0, 1f};
            default: return new float[]{0.12f, 0, 0, 0, 1f, 0, 0, 0, 0.1f, 0}; // calm
        }
    }

    // ================================================================ drawing

    static final float TOP = -1.66f, BOTTOM = 2.35f, HALF_W = 2.45f;

    /** Draws the whole face into a w x h area (pixels), as big as fits, centred. */
    void draw(Pen p, float w, float h, Look L) {
        float u = Math.min(h / (BOTTOM - TOP), w / (2 * HALF_W));
        if (u <= 0.5f) return;
        float ox = w / 2f, oy = (h - (BOTTOM - TOP) * u) / 2f - TOP * u;
        p.frame(ox, oy, u);

        // ---- a soft light behind him
        p.begin();
        p.addOval(0, 0.1f, 2.3f, 2.3f);
        p.fillRadial(0, 0.1f, 2.3f, new int[]{L.glow, withAlpha(L.glow, alphaOf(L.glow) / 3), withAlpha(L.glow, 0)}, new float[]{0, 0.55f, 1});

        float by = breath * 0.012f;
        body(p, L, by);

        // ---- the head (tilts around the neck)
        p.save();
        p.rotate(roll, 0, 1.0f);
        p.translate(0, by * 0.6f);
        float jaw = 0.07f * clamp(open, 0, 1.1f);
        backHair(p, L);
        float earShift = yaw * 0.05f;
        ear(p, L, -1, earShift, 1f + 0.35f * Math.max(0, yaw) - 0.5f * Math.max(0, -yaw));
        ear(p, L, 1, earShift, 1f + 0.35f * Math.max(0, -yaw) - 0.5f * Math.max(0, yaw));
        faceSkin(p, L, jaw);
        cheeks(p, L);
        eye(p, L, -1);
        eye(p, L, 1);
        brow(p, L, -1, brow + browL, inner, frown);
        brow(p, L, 1, brow + browR, inner, frown);
        nose(p, L);
        mouth(p, L, jaw);
        frontHair(p, L);
        if (tears > 0.15f) tears(p, L);
        if (L.holo) holo(p, L, jaw);
        p.restore();
    }

    private float dx(float depth) { return yaw * 0.13f * depth; }

    private float dy(float depth) { return pitch * 0.10f * depth; }

    private void body(Pen p, Look L, float by) {
        // jacket
        p.begin();
        p.moveTo(-0.46f, 1.62f + by);
        p.cubicTo(-0.9f, 1.70f + by, -1.7f, 1.78f + by, -2.1f, 2.05f + by);
        p.cubicTo(-2.35f, 2.22f + by, -2.45f, 2.40f, -2.5f, 2.7f);
        p.lineTo(2.5f, 2.7f);
        p.cubicTo(2.45f, 2.40f, 2.35f, 2.22f + by, 2.1f, 2.05f + by);
        p.cubicTo(1.7f, 1.78f + by, 0.9f, 1.70f + by, 0.46f, 1.62f + by);
        p.close();
        p.fillLinear(0, 1.6f, 0, 2.6f, L.suitLight, L.suit);
        if (L.holo) p.stroke(L.edge, 0.014f);
        // shirt
        p.begin();
        p.moveTo(-0.50f, 1.66f + by);
        p.lineTo(0, 2.42f + by);
        p.lineTo(0.50f, 1.66f + by);
        p.close();
        p.fill(L.shirt);
        // neck
        p.begin();
        p.moveTo(-0.37f, 0.80f);
        p.cubicTo(-0.37f, 1.20f, -0.41f, 1.45f, -0.50f, 1.74f + by);
        p.lineTo(0.50f, 1.74f + by);
        p.cubicTo(0.41f, 1.45f, 0.37f, 1.20f, 0.37f, 0.80f);
        p.close();
        p.fillLinear(0, 0.95f, 0, 1.7f, L.skinDark, L.skin);
        // the chin's shadow on the neck
        p.begin();
        p.addOval(dx(0.2f), 1.10f, 0.44f, 0.16f);
        p.fillRadial(dx(0.2f), 1.10f, 0.44f, new int[]{withAlpha(L.shade, 0x70), withAlpha(L.shade, 0)}, new float[]{0, 1});
        // collar wings
        for (int sd = -1; sd <= 1; sd += 2) {
            p.begin();
            p.moveTo(sd * 0.36f, 1.56f + by);
            p.lineTo(sd * 0.56f, 1.70f + by);
            p.lineTo(sd * 0.50f, 1.96f + by);
            p.lineTo(sd * 0.10f, 2.02f + by);
            p.close();
            p.fill(L.holo ? L.shirt : mix(L.shirt, 0xFFFFFFFF, 0.5f));
            p.stroke(withAlpha(L.holo ? L.edge : 0xFF9AA6B5, 0x90), 0.012f);
        }
        // lapels
        for (int sd = -1; sd <= 1; sd += 2) {
            p.begin();
            p.moveTo(sd * 0.58f, 1.72f + by);
            p.lineTo(sd * 0.02f, 2.44f + by);
            p.stroke(L.holo ? L.edge : withAlpha(L.suitLight, 0xFF), 0.035f);
            p.begin();
            p.moveTo(sd * 0.62f, 1.74f + by);
            p.lineTo(sd * 0.85f, 2.05f + by);
            p.lineTo(sd * 0.55f, 2.12f + by);
            p.stroke(withAlpha(L.holo ? L.edge : 0xFF000000, 0x55), 0.02f);
        }
        // a small light on the jacket (the theme colour)
        float px = -1.1f, py = 2.12f + by;
        p.begin();
        p.addOval(px, py, 0.16f, 0.16f);
        p.fillRadial(px, py, 0.16f, new int[]{withAlpha(L.accent, 0xA0), withAlpha(L.accent, 0)}, new float[]{0, 1});
        p.begin();
        p.addOval(px, py, 0.05f, 0.05f);
        p.fill(mix(L.accent, 0xFFFFFFFF, 0.5f));
        p.begin();
        p.addOval(px, py, 0.075f, 0.075f);
        p.stroke(L.accent, 0.015f);
    }

    private void backHair(Pen p, Look L) {
        float x = dx(0.1f), y = dy(0.1f);
        p.begin();
        p.moveTo(x - 0.98f, y - 0.10f);
        p.cubicTo(x - 1.10f, y - 0.80f, x - 0.98f, y - 1.40f, x - 0.45f, y - 1.56f);
        p.cubicTo(x - 0.08f, y - 1.68f, x + 0.50f, y - 1.68f, x + 0.84f, y - 1.46f);
        p.cubicTo(x + 1.12f, y - 1.24f, x + 1.11f, y - 0.70f, x + 0.98f, y - 0.10f);
        p.lineTo(x + 0.86f, y - 0.10f);
        p.lineTo(x - 0.86f, y - 0.10f);
        p.close();
        p.fill(L.hair);
    }

    private void ear(Pen p, Look L, float side, float shift, float scale) {
        float w = 0.19f * clamp(scale, 0.35f, 1.4f);
        float bx = side * 0.90f + shift, top = -0.18f - pitch * 0.03f, bot = 0.40f - pitch * 0.03f;
        p.begin();
        p.moveTo(bx, top + 0.05f);
        p.cubicTo(bx + side * w * 0.9f, top - 0.07f, bx + side * w * 1.2f, top + 0.18f, bx + side * w * 1.0f, top + 0.34f);
        p.cubicTo(bx + side * w * 0.85f, top + 0.50f, bx + side * w * 0.55f, bot, bx + side * w * 0.2f, bot + 0.01f);
        p.cubicTo(bx, bot, bx - side * 0.03f, bot - 0.10f, bx - side * 0.01f, bot - 0.16f);
        p.close();
        p.fillLinear(bx, 0, bx + side * w, 0, L.skinDark, L.skin);
        if (L.holo) p.stroke(L.edge, 0.012f);
        // the fold inside
        p.begin();
        p.moveTo(bx + side * w * 0.25f, top + 0.10f);
        p.cubicTo(bx + side * w * 0.75f, top + 0.04f, bx + side * w * 0.85f, top + 0.30f, bx + side * w * 0.50f, bot - 0.10f);
        p.stroke(withAlpha(L.shade, 0x80), 0.022f);
        p.begin();
        p.addOval(bx + side * w * 0.38f, (top + bot) / 2f + 0.03f, w * 0.20f, 0.085f);
        p.fill(withAlpha(L.shade, 0x60));
    }

    private void facePath(Pen p, float jaw) {
        float x = dx(0.25f), y = dy(0.25f), j = jaw;
        p.begin();
        p.moveTo(x, y - 1.16f);
        p.cubicTo(x + 0.56f, y - 1.16f, x + 0.91f, y - 0.88f, x + 0.93f, y - 0.42f);
        p.cubicTo(x + 0.95f, y - 0.18f, x + 0.97f, y - 0.04f, x + 0.95f, y + 0.08f);
        p.cubicTo(x + 0.93f, y + 0.40f, x + 0.88f, y + 0.64f, x + 0.76f, y + 0.82f + j * 0.5f);
        p.cubicTo(x + 0.62f, y + 1.02f + j, x + 0.42f, y + 1.16f + j, x + 0.25f, y + 1.20f + j);
        p.cubicTo(x + 0.13f, y + 1.235f + j, x - 0.13f, y + 1.235f + j, x - 0.25f, y + 1.20f + j);
        p.cubicTo(x - 0.42f, y + 1.16f + j, x - 0.62f, y + 1.02f + j, x - 0.76f, y + 0.82f + j * 0.5f);
        p.cubicTo(x - 0.88f, y + 0.64f, x - 0.93f, y + 0.40f, x - 0.95f, y + 0.08f);
        p.cubicTo(x - 0.97f, y - 0.04f, x - 0.95f, y - 0.18f, x - 0.93f, y - 0.42f);
        p.cubicTo(x - 0.91f, y - 0.88f, x - 0.56f, y - 1.16f, x, y - 1.16f);
        p.close();
    }

    private void faceSkin(Pen p, Look L, float jaw) {
        facePath(p, jaw);
        float lx = -0.18f + dx(0.6f);
        p.fillRadial(lx, -0.15f, 1.55f, new int[]{L.skinLight, L.skin, L.skinDark}, new float[]{0, 0.55f, 1});
        // the side turned away is a little darker
        facePath(p, jaw);
        p.fillLinear(-1f, 0, 1f, 0, withAlpha(L.shade, (int) (40 * Math.max(0, yaw) + 18)), withAlpha(L.shade, (int) (40 * Math.max(0, -yaw) + 18)));
        if (L.holo) { facePath(p, jaw); p.stroke(L.edge, 0.016f); }
        // jaw line shadow and chin
        float x = dx(0.4f), y = dy(0.4f);
        p.begin();
        p.addOval(x, 1.03f + jaw + y, 0.18f, 0.10f);
        p.fillRadial(x, 1.03f + jaw + y, 0.18f, new int[]{withAlpha(L.skinLight, 0x22), withAlpha(L.skinLight, 0)}, new float[]{0, 1});
    }

    private void cheeks(Pen p, Look L) {
        for (int sd = -1; sd <= 1; sd += 2) {
            float cx = sd * 0.56f + dx(0.5f), cy = 0.30f - 0.05f * cheek + dy(0.5f);
            p.begin();
            p.addOval(cx, cy, 0.24f, 0.16f);
            int a = (int) (L.holo ? 30 : 26 + 60 * cheek);
            p.fillRadial(cx, cy, 0.24f, new int[]{withAlpha(L.blush, a), withAlpha(L.blush, 0)}, new float[]{0, 1});
            p.begin();
            p.addOval(cx - sd * 0.02f, cy - 0.05f, 0.14f, 0.08f);
            p.fillRadial(cx - sd * 0.02f, cy - 0.05f, 0.14f, new int[]{withAlpha(L.skinLight, (int) (30 + 50 * cheek)), withAlpha(L.skinLight, 0)}, new float[]{0, 1});
        }
    }

    private void eye(Pen p, Look L, float side) {
        float cx = side * 0.39f + dx(0.55f), cy = -0.05f + dy(0.55f);
        float ew = 0.20f;
        float inX = cx - side * ew, outX = cx + side * ew, inY = cy + 0.012f, outY = cy - 0.006f;
        float o = clamp(lid * (1 - blink), 0, 1.4f);
        float upCtrl = cy - 1.33f * (0.105f * o - 0.016f * (1 - Math.min(1f, o)));
        float low = Math.max(0.012f, 0.062f - 0.032f * squint - 0.012f * cheek);
        float lowCtrl = Math.max(upCtrl, cy + 1.33f * low);
        float span = outX - inX;

        // the socket above the eye, and a faint line below it
        p.begin();
        p.addOval(cx, cy - 0.11f, 0.22f, 0.08f);
        p.fillRadial(cx, cy - 0.11f, 0.22f, new int[]{withAlpha(L.shade, 0x38), withAlpha(L.shade, 0)}, new float[]{0, 1});
        p.begin();
        p.moveTo(inX + span * 0.15f, cy + 0.105f);
        p.quadTo(cx, cy + 0.14f, outX - span * 0.1f, cy + 0.09f);
        p.stroke(withAlpha(L.shade, 0x22), 0.02f);

        if (o > 0.05f && lowCtrl - upCtrl > 0.01f) {
            p.begin();
            p.moveTo(inX, inY);
            p.cubicTo(inX + span * 0.25f, upCtrl, inX + span * 0.70f, upCtrl - 0.005f, outX, outY);
            p.cubicTo(outX - span * 0.25f, lowCtrl, inX + span * 0.30f, lowCtrl, inX, inY);
            p.close();
            p.fillRadial(cx, cy, ew * 1.1f, new int[]{L.sclera, L.sclera, mix(L.sclera, 0xFFC9A79A, L.holo ? 0f : 0.5f)}, new float[]{0, 0.6f, 1});
            p.save();
            p.clip();
            float ix = cx + gazeX * 0.08f + dx(0.1f), iy = cy + gazeY * 0.04f + 0.006f, r = 0.088f;
            p.begin();
            p.addOval(ix, iy, r, r);
            p.fillRadial(ix, iy, r, new int[]{L.irisLight, L.iris, mix(L.iris, 0xFF000000, 0.5f)}, new float[]{0, 0.72f, 1});
            float pr = 0.036f + (mode == THINKING ? 0.004f : 0f);
            p.begin();
            p.addOval(ix, iy, pr, pr);
            p.fill(L.pupil);
            if (L.holo) { p.begin(); p.addOval(ix, iy, r, r); p.stroke(L.edge, 0.01f); }
            p.begin();
            p.addOval(ix + 0.026f, iy - 0.028f, 0.017f, 0.017f);
            p.fill(0xEEFFFFFF);
            p.begin();
            p.addOval(ix - 0.022f, iy + 0.022f, 0.007f, 0.007f);
            p.fill(0x88FFFFFF);
            // the upper lid's shadow on the eyeball
            float peak = cy - 0.75f * (cy - upCtrl);
            p.begin();
            p.moveTo(inX, peak - 0.02f);
            p.lineTo(outX, peak - 0.02f);
            p.lineTo(outX, peak + 0.07f);
            p.lineTo(inX, peak + 0.07f);
            p.close();
            p.fillLinear(0, peak, 0, peak + 0.07f, withAlpha(L.shade, 0x70), withAlpha(L.shade, 0));
            p.restore();
            // lower lid
            p.begin();
            p.moveTo(outX, outY);
            p.cubicTo(outX - span * 0.25f, lowCtrl, inX + span * 0.30f, lowCtrl, inX, inY);
            p.stroke(withAlpha(L.holo ? L.edge : L.skinDark, 0x80), 0.011f);
            if (tears > 0.3f) p.stroke(withAlpha(L.tear, (int) (120 * tears)), 0.018f);
            // lid crease
            float crease = cy - 1.33f * 0.105f * Math.max(o, 0.6f) - 0.075f;
            p.begin();
            p.moveTo(inX + side * 0.02f, inY - 0.045f);
            p.cubicTo(inX + span * 0.3f, crease, inX + span * 0.72f, crease, outX - side * 0.01f, outY - 0.04f);
            p.stroke(withAlpha(L.holo ? L.edge : L.skinDark, 0x80), 0.012f);
        }
        // lashes along the upper lid (also the closed eye)
        p.begin();
        p.moveTo(inX, inY);
        p.cubicTo(inX + span * 0.25f, upCtrl, inX + span * 0.70f, upCtrl - 0.005f, outX, outY);
        p.lineTo(outX + side * 0.028f, outY - 0.014f);
        p.stroke(L.lash, o > 0.05f ? 0.026f : 0.022f);
    }

    private void brow(Pen p, Look L, float side, float raise, float inner, float frown) {
        float bx = dx(0.55f), by = dy(0.55f);
        float ix = side * (0.16f - 0.03f * frown) + bx, iy = -0.265f - 0.09f * raise - 0.085f * inner + 0.055f * frown + by;
        float px = side * 0.44f + bx, py = -0.355f - 0.10f * raise - 0.02f * inner + 0.02f * frown + by;
        float ox = side * 0.66f + bx, oy = -0.29f - 0.07f * raise + 0.03f * inner + by;
        float tcx = 2 * px - (ix + ox) / 2, tcy = 2 * (py - 0.032f) - ((iy - 0.035f) + (oy - 0.006f)) / 2;
        float bcy = 2 * (py + 0.028f) - ((iy + 0.04f) + (oy + 0.008f)) / 2;
        p.begin();
        p.moveTo(ix, iy + 0.04f);
        p.quadTo(ix - side * 0.014f, iy, ix, iy - 0.035f);
        p.quadTo(tcx, tcy, ox, oy - 0.006f);
        p.quadTo(ox + side * 0.012f, oy, ox, oy + 0.008f);
        p.quadTo(tcx, bcy, ix, iy + 0.04f);
        p.close();
        p.stroke(withAlpha(L.brow, 0x50), 0.022f); // soft edge
        p.fill(L.brow);
        if (frown > 0.15f) { // the little lines between the brows
            p.begin();
            p.moveTo(side * 0.06f + bx, -0.20f + by);
            p.lineTo(side * 0.08f + bx, -0.12f + by);
            p.stroke(withAlpha(L.shade, (int) (90 * frown)), 0.012f);
        }
    }

    private void nose(Pen p, Look L) {
        float x = dx(1f), y = dy(0.9f);
        // the bridge, lit from the left: a shadow down its right side
        p.begin();
        p.moveTo(x + 0.08f, -0.04f + y);
        p.cubicTo(x + 0.10f, 0.08f + y, x + 0.11f, 0.20f + y, x + 0.12f, 0.29f + y);
        p.stroke(withAlpha(L.shade, 0x30), 0.05f);
        p.begin();
        p.moveTo(x - 0.06f, -0.02f + y);
        p.cubicTo(x - 0.07f, 0.10f + y, x - 0.08f, 0.20f + y, x - 0.09f, 0.27f + y);
        p.stroke(withAlpha(L.skinLight, 0x40), 0.035f);
        // tip
        p.begin();
        p.addOval(x, 0.33f + y, 0.085f, 0.065f);
        p.fillRadial(x - 0.015f, 0.32f + y, 0.085f, new int[]{withAlpha(L.skinLight, 0x90), withAlpha(L.skinLight, 0)}, new float[]{0, 1});
        // wings
        for (int sd = -1; sd <= 1; sd += 2) {
            p.begin();
            p.moveTo(x + sd * 0.085f, 0.27f + y);
            p.cubicTo(x + sd * 0.17f, 0.30f + y, x + sd * 0.17f, 0.42f + y, x + sd * 0.075f, 0.43f + y);
            p.stroke(withAlpha(L.holo ? L.edge : L.skinDark, 0xB0), 0.022f);
            p.begin();
            p.addOval(x + sd * 0.062f, 0.418f + y, 0.034f, 0.017f);
            p.fill(withAlpha(L.shade, 0xC0));
        }
        // under the nose, and the groove to the lip
        p.begin();
        p.addOval(x, 0.455f + y, 0.10f, 0.025f);
        p.fill(withAlpha(L.shade, 0x30));
        float mx = dx(0.65f), my = dy(0.6f);
        for (int sd = -1; sd <= 1; sd += 2) {
            p.begin();
            p.moveTo(x + sd * 0.032f, 0.48f + y);
            p.lineTo(mx + sd * 0.04f, 0.585f + my);
            p.stroke(withAlpha(L.shade, 0x16), 0.018f);
        }
    }

    private void mouth(Pen p, Look L, float jaw) {
        float cx = dx(0.65f), cy = 0.68f + dy(0.6f) + jaw * 0.25f;
        float o = clamp(open, 0, 1.1f), sm = smile;
        float hw = 0.29f * wide * (1 + 0.14f * Math.max(0, sm)) * (1 - 0.28f * round);
        float cyL = cy - 0.085f * sm + 0.04f * smirk + 0.01f * o, cyR = cy - 0.085f * sm - 0.04f * smirk + 0.01f * o;
        float lx = cx - hw, rx = cx + hw;
        float gap = 0.24f * o;
        float upTop = cy - 0.068f - 0.012f * round + 0.01f * Math.max(0, sm);
        float upIn = cy - 0.004f - 0.012f * o + 0.012f * round * o + 0.012f * Math.max(0, sm);
        float lowIn = cy + 0.004f + gap + 0.012f * Math.max(0, sm);
        float lowOut = cy + 0.085f + 0.015f * round + gap;
        float k = 0.55f - 0.15f * round; // rounder lips bow more

        // laugh lines from the nose to the corners of the mouth
        float nx = dx(1f), ny = dy(0.9f);
        for (int sd = -1; sd <= 1; sd += 2) {
            float ex = sd < 0 ? lx : rx, ey = sd < 0 ? cyL : cyR;
            p.begin();
            p.moveTo(nx + sd * 0.17f, 0.40f + ny);
            p.quadTo(ex + sd * 0.12f, ey - 0.16f, ex + sd * 0.05f, ey + 0.07f);
            if (sm > 0.2f || L.holo) p.stroke(withAlpha(L.holo ? L.edge : L.shade, (int) (L.holo ? 40 : 110 * (sm - 0.2f))), 0.02f);
        }

        if (o > 0.03f) {
            p.begin();
            p.moveTo(lx, cyL);
            p.cubicTo(cx - hw * k, upIn, cx + hw * k, upIn, rx, cyR);
            p.cubicTo(cx + hw * k, lowIn + 0.02f * round, cx - hw * k, lowIn + 0.02f * round, lx, cyL);
            p.close();
            p.fill(L.mouth);
            p.save();
            p.clip();
            p.begin();
            p.addOval(cx, upIn + 0.008f, hw * 0.78f, 0.05f);
            p.fill(L.teeth);
            if (o > 0.45f) {
                p.begin();
                p.addOval(cx, lowIn + 0.012f, hw * 0.62f, 0.03f);
                p.fill(withAlpha(L.teeth, 0xC0));
            }
            if (o > 0.2f) {
                p.begin();
                p.addOval(cx, lowIn - 0.015f, hw * 0.55f, 0.03f + 0.05f * o);
                p.fill(L.tongue);
            }
            p.restore();
        }
        // upper lip with the cupid's bow
        p.begin();
        p.moveTo(lx, cyL);
        p.cubicTo(lx + hw * 0.35f, cyL - 0.03f, cx - 0.13f, upTop, cx - 0.07f, upTop);
        p.quadTo(cx - 0.03f, upTop, cx, upTop + 0.014f);
        p.quadTo(cx + 0.03f, upTop, cx + 0.07f, upTop);
        p.cubicTo(cx + 0.13f, upTop, rx - hw * 0.35f, cyR - 0.03f, rx, cyR);
        p.cubicTo(cx + hw * k, upIn, cx - hw * k, upIn, lx, cyL);
        p.close();
        p.fill(L.lipUp);
        if (L.holo) p.stroke(L.edge, 0.01f);
        // lower lip
        p.begin();
        p.moveTo(lx, cyL);
        p.cubicTo(cx - hw * k, lowIn + 0.02f * round, cx + hw * k, lowIn + 0.02f * round, rx, cyR);
        p.cubicTo(rx - hw * 0.2f, lowOut - 0.01f, cx + hw * 0.45f, lowOut + 0.012f, cx, lowOut + 0.012f);
        p.cubicTo(cx - hw * 0.45f, lowOut + 0.012f, lx + hw * 0.2f, lowOut - 0.01f, lx, cyL);
        p.close();
        p.fill(L.lipLow);
        if (L.holo) p.stroke(L.edge, 0.01f);
        p.begin();
        p.addOval(cx - 0.02f, lowIn + (lowOut - lowIn) * 0.45f, hw * 0.32f, 0.016f);
        p.fill(withAlpha(L.lipHi, 0x70));
        if (o <= 0.03f) { // where the lips meet
            p.begin();
            p.moveTo(lx - 0.01f, cyL);
            p.cubicTo(cx - hw * k, upIn, cx + hw * k, upIn, rx + 0.01f, cyR);
            p.stroke(withAlpha(L.mouth, 0xB0), 0.014f);
        }
        // corners
        p.begin();
        p.addOval(lx, cyL, 0.014f, 0.011f);
        p.addOval(rx, cyR, 0.014f, 0.011f);
        p.fill(withAlpha(L.shade, 0x90));
        // the fold under the lower lip
        p.begin();
        p.moveTo(cx - 0.12f, lowOut + 0.075f);
        p.quadTo(cx, lowOut + 0.05f, cx + 0.12f, lowOut + 0.075f);
        p.stroke(withAlpha(L.shade, 0x1C), 0.02f);
    }

    private void frontHair(Pen p, Look L) {
        float x = dx(0.35f), y = dy(0.35f);
        p.begin();
        p.moveTo(x - 0.95f, y - 0.30f);
        p.cubicTo(x - 1.06f, y - 0.80f, x - 0.98f, y - 1.28f, x - 0.55f, y - 1.47f);
        p.cubicTo(x - 0.20f, y - 1.62f, x + 0.35f, y - 1.66f, x + 0.70f, y - 1.48f);
        p.cubicTo(x + 1.02f, y - 1.30f, x + 1.07f, y - 0.80f, x + 0.95f, y - 0.30f);
        p.cubicTo(x + 0.90f, y - 0.46f, x + 0.86f, y - 0.62f, x + 0.70f, y - 0.68f);
        p.cubicTo(x + 0.55f, y - 0.74f, x + 0.45f, y - 0.80f, x + 0.30f, y - 0.84f);
        p.cubicTo(x + 0.05f, y - 0.90f, x - 0.25f, y - 0.86f, x - 0.45f, y - 0.80f);
        p.cubicTo(x - 0.65f, y - 0.76f, x - 0.80f, y - 0.66f, x - 0.86f, y - 0.52f);
        p.cubicTo(x - 0.90f, y - 0.44f, x - 0.93f, y - 0.38f, x - 0.95f, y - 0.30f);
        p.close();
        p.fillLinear(0, y - 1.5f, 0, y - 0.7f, L.hair, mix(L.hair, L.hairHi, 0.2f));
        if (L.holo) p.stroke(L.edge, 0.014f);
        // the swept front (volume above the forehead)
        p.begin();
        p.moveTo(x - 0.42f, y - 0.84f);
        p.cubicTo(x - 0.28f, y - 1.30f, x + 0.28f, y - 1.64f, x + 0.76f, y - 1.44f);
        p.cubicTo(x + 0.56f, y - 1.30f, x + 0.12f, y - 1.06f, x - 0.04f, y - 0.88f);
        p.close();
        p.fillLinear(x - 0.4f, y - 0.9f, x + 0.6f, y - 1.5f, mix(L.hair, L.hairHi, 0.15f), mix(L.hair, L.hairHi, 0.45f));
        // sideburns, thinning down
        for (int sd = -1; sd <= 1; sd += 2) {
            p.begin();
            p.moveTo(x + sd * 0.95f, y - 0.32f);
            p.lineTo(x + sd * 0.94f, -0.06f);
            p.quadTo(x + sd * 0.915f, -0.01f, x + sd * 0.89f, -0.07f);
            p.lineTo(x + sd * 0.875f, y - 0.32f);
            p.close();
            p.fill(withAlpha(L.hair, 0xC8));
        }
        // shine along the strands
        p.begin();
        p.moveTo(x - 0.30f, y - 0.95f);
        p.cubicTo(x - 0.12f, y - 1.30f, x + 0.25f, y - 1.48f, x + 0.62f, y - 1.42f);
        p.stroke(withAlpha(L.hairHi, 0x70), 0.05f);
        p.begin();
        p.moveTo(x - 0.15f, y - 0.90f);
        p.cubicTo(x + 0.05f, y - 1.16f, x + 0.35f, y - 1.30f, x + 0.70f, y - 1.28f);
        p.stroke(withAlpha(L.hairHi, 0x50), 0.035f);
        p.begin();
        p.moveTo(x - 0.80f, y - 0.62f);
        p.cubicTo(x - 0.86f, y - 0.95f, x - 0.78f, y - 1.22f, x - 0.55f, y - 1.36f);
        p.stroke(withAlpha(L.hairHi, 0x50), 0.022f);
        p.begin();
        p.moveTo(x + 0.40f, y - 0.82f);
        p.cubicTo(x + 0.62f, y - 0.95f, x + 0.82f, y - 0.98f, x + 0.92f, y - 0.62f);
        p.stroke(withAlpha(L.hairHi, 0x45), 0.02f);
    }

    private void tears(Pen p, Look L) {
        for (int sd = -1; sd <= 1; sd += 2) {
            float ph = (t * 0.45f + (sd > 0 ? 0.5f : 0f)) % 1f;
            float x0 = sd * 0.43f + dx(0.55f), y0 = 0.04f + dy(0.55f);
            float x = x0 + sd * 0.05f * ph, y = y0 + 0.55f * ph;
            int a = (int) (alphaOf(L.tear) * tears * (1 - ph * 0.7f));
            p.begin();
            p.moveTo(x0, y0);
            p.quadTo(x0 + sd * 0.02f * ph, (y0 + y) / 2f, x, y - 0.02f);
            p.stroke(withAlpha(L.tear, a / 2), 0.016f);
            p.begin();
            p.moveTo(x, y - 0.045f);
            p.quadTo(x + 0.024f, y - 0.005f, x, y + 0.02f);
            p.quadTo(x - 0.024f, y - 0.005f, x, y - 0.045f);
            p.close();
            p.fill(withAlpha(L.tear, a));
            p.begin();
            p.addOval(x - 0.006f, y - 0.008f, 0.006f, 0.008f);
            p.fill(withAlpha(0xFFFFFFFF, a));
        }
    }

    /** Hologram look: scan lines over the face and a band of light sweeping down. */
    private void holo(Pen p, Look L, float jaw) {
        p.save();
        facePath(p, jaw);
        p.clip();
        for (float y = -1.2f; y < 1.3f; y += 0.04f) {
            p.begin();
            p.moveTo(-1.1f, y);
            p.lineTo(1.1f, y);
            p.stroke(withAlpha(L.edge, 0x22), 0.008f);
        }
        float band = -1.3f + ((t * 0.5f) % 1f) * 2.8f;
        p.begin();
        p.moveTo(-1.1f, band - 0.12f);
        p.lineTo(1.1f, band - 0.12f);
        p.lineTo(1.1f, band + 0.12f);
        p.lineTo(-1.1f, band + 0.12f);
        p.close();
        p.fillLinear(0, band - 0.12f, 0, band + 0.12f, withAlpha(L.edge, 0), withAlpha(L.edge, 0x40));
        p.restore();
    }

    // ================================================================ small helpers

    private static float ease(float c, float target, float rate, float dt) {
        return c + (target - c) * (1f - (float) Math.exp(-rate * dt));
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    static int alphaOf(int c) { return (c >>> 24) & 0xFF; }

    static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    static int mix(int a, int b, float f) {
        f = clamp(f, 0, 1);
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
