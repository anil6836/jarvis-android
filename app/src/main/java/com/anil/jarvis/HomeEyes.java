package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayDeque;
import java.util.Calendar;

/**
 * The home Jarvis reads the faces in front of him (the tablet's front camera, on the tablet itself; nothing is saved):
 * smiles back when someone smiles, winks back, waves back, comforts అమ్మగారు when she cries (the sound watch hears it),
 * asks her about a person he doesn't know (and only then tells Anil with a picture), and at night, when she gets up,
 * lights the screen softly and asks her to walk carefully. The camera runs in the day while the screen is on, only after
 * she said yes once.
 */
final class HomeEyes {
    private HomeEyes() {}

    private static SharedPreferences sp(Context c) { return HomeCare.sp(c); }

    // ---- the camera (on by Anil's choice, 10 Oct 2026: "అడగకుండా ఆన్ అవ్వాలి"; a switch in Settings turns it off);
    // Anil's switch for strangers' pictures
    static boolean camOn(Context c) { return sp(c).getBoolean("cam_on", true); }
    static void setCam(Context c, boolean on) { sp(c).edit().putBoolean("cam_on", on).apply(); }
    /** Where the tablet stands: "hall" (by the TV: the TV goes quiet while Jarvis talks) or "bedroom". */
    static String room(Context c) { return sp(c).getString("room", "hall"); }
    static boolean inHall(Context c) { return !"bedroom".equals(room(c)); }
    static boolean strangerPhoto(Context c) { return sp(c).getBoolean("stranger_photo", true); }

    // ================================================================ what the face does (main thread)
    private static float lastLeft = 1, lastRight = 1;
    private static int smileRun;
    private static long smileAt, winkAt, waveAt, waveSaidAt, moodAt, seenAt;
    private static volatile String mood = "";

    /** What the character should do for this face now: a mood to show ("laugh", "happy", "wink") or null. */
    static String face(float smile, float leftOpen, float rightOpen, float yaw, boolean frontal, long now) {
        seenAt = now;
        String show = null;
        // a smile back (a real one: twice in a row)
        smileRun = smile >= 0.75f ? smileRun + 1 : 0;
        if (smileRun >= 2 && now - smileAt > 8000) { smileAt = now; show = smile >= 0.92f ? "laugh" : "happy"; }
        // a wink: one eye closes while the other stays open (a blink closes both)
        if (frontal && leftOpen >= 0 && rightOpen >= 0) {
            boolean wasOpen = lastLeft >= 0.6f && lastRight >= 0.6f;
            boolean wink = (leftOpen < 0.25f && rightOpen > 0.7f) || (rightOpen < 0.25f && leftOpen > 0.7f);
            if (wink && wasOpen && now - winkAt > 5000) { winkAt = now; show = "wink"; }
        }
        lastLeft = leftOpen < 0 ? 1 : leftOpen;
        lastRight = rightOpen < 0 ? 1 : rightOpen;
        // for the AI: how she looks (only words, never the picture)
        if (frontal) {
            mood = smile >= 0.6f ? "smiling" : smile <= 0.12f ? "not smiling (serious or sad)" : "calm";
            moodAt = now;
        }
        return show;
    }

    /** A wave: true when the character should wave back (and say hello now and then). */
    static boolean wave(long now) {
        if (now - waveAt < 6000) return false;
        waveAt = now;
        return true;
    }

    /** Say "హాయ్" for this wave too (not more than once in 3 minutes). */
    static boolean sayHi(long now) {
        if (now - waveSaidAt < 3 * 60_000L) return false;
        waveSaidAt = now;
        return true;
    }

    /** For the brain: how the person in front looks now (from the camera, on the tablet), or "". */
    static String moodLine(long now) {
        if (now - moodAt > 15_000 || mood.isEmpty()) return "";
        return "Right now the tablet's camera (on the tablet, no picture is sent) sees the person in front " + mood
                + ". Let it gently shape your words (comfort if sad, share the joy if smiling); don't mention the camera unless asked.";
    }

    // ================================================================ someone not introduced
    private static int strangerHits;
    private static long strangerFirst, strangerAskedAt, strangerOkUntil;
    private static byte[] strangerPic;

    /** A clear face he doesn't know: after a few seconds of it, she is asked ("మీకు తెలిసినవాళ్లేనా?"). */
    static void stranger(Context c, byte[] jpeg, long now) {
        if (!HomeCare.on(c) || HomeCare.sonHome(c) || HomeCare.night() || now < strangerOkUntil || !herKnown(c)) return;
        if (now - strangerFirst > 12_000) { strangerFirst = now; strangerHits = 0; }
        strangerHits++;
        strangerPic = jpeg;
        if (strangerHits < 3 || now - strangerAskedAt < 15 * 60_000L) return;
        strangerAskedAt = now;
        String w = HomeCare.who(c);
        HomeCare.ask(c, w + ", కొత్తవాళ్లు ఎవరో కనిపిస్తున్నారు. మీకు తెలిసినవాళ్లేనా?", "visitor", "surprised");
        // no answer: Anil is told (with the picture, when he allowed that) — 3 minutes after the question is really said
        // (it may wait behind a talk on the screen: MainActivity.homeQueue)
        noAnswer(c, jpeg, w, 0);
    }

    private static void noAnswer(Context c, byte[] pic, String w, int tries) {
        HomeCare.later(() -> {
            if (!"visitor".equals(HomeCare.pending())) return;
            if (HomeCare.pendingAge() < 3 * 60_000L - 1000 && tries < 8) { noAnswer(c, pic, w, tries + 1); return; }
            HomeCare.clearPending();
            tellStranger(c, pic, w + " జవాబు ఇవ్వలేదు");
        }, 3 * 60_000L);
    }

    /** Her own face is known on the tablet (introduced as అమ్మగారు / అమ్మ / the name Anil set): only then is a face it
     *  doesn't know someone else, not her. */
    static boolean herKnown(Context c) {
        String w = HomeCare.who(c).toLowerCase(java.util.Locale.ROOT).replace("గారు", "").trim();
        for (People.Person p : People.all(c)) {
            if (p.owner) continue;
            String n = p.name == null ? "" : p.name.toLowerCase(java.util.Locale.ROOT);
            if (n.contains("అమ్మ") || n.contains("amma") || n.contains("mother") || n.contains("mom") || (!w.isEmpty() && n.contains(w))) return true;
        }
        return false;
    }

    /** Her answer to "మీకు తెలిసినవాళ్లేనా?": what Jarvis says. */
    static String strangerAnswer(Context c, boolean known) {
        String w = HomeCare.who(c);
        if (known) {
            strangerOkUntil = System.currentTimeMillis() + 2 * 3600_000L; // (a visit: not asked again for 2 hours)
            return "సరే " + w + ". మంచిది.";
        }
        boolean sent = tellStranger(c, strangerPic, w + " \"తెలియదు\" అన్నారు");
        return (sent ? "సరే " + w + ", అబ్బాయికి చెప్పాను. " : "సరే " + w + ". ")
                + "తెలియనివాళ్లని లోపలికి రానివ్వకండి, తలుపు దగ్గరే మాట్లాడండి.";
    }

    private static boolean tellStranger(Context c, byte[] pic, String why) {
        String text = "🚪 ఇంట్లో తెలియని మనిషి కనిపించారు (" + why + ", " + new java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date()) + ").";
        if (pic != null && strangerPhoto(c) && Net.online(c)) {
            final byte[] p = pic;
            new Thread(() -> { if (!Guard.sendPhoto(c, p, text)) HomeCare.alert(c, text); }, "home-stranger").start();
            return true;
        }
        return HomeCare.alert(c, text);
    }

    // ================================================================ crying (the sound watch) and night
    private static int cryHits;
    private static long cryFirst, cryAt, stirAt, stepFirst;
    private static int stepHits;

    /** The sound watch's scores (YAMNet) for a moment: crying without a TV / song → comfort her (not more than every 30 min). */
    static void sounds(Context c, float[] s, long now) {
        if (s == null || s.length <= SafetySounds.TV) return;
        boolean tv = s[SafetySounds.TV] >= 0.2f || (s.length > SafetySounds.MUSIC && s[SafetySounds.MUSIC] >= 0.3f)
                || (s.length > SafetySounds.RADIO && s[SafetySounds.RADIO] >= 0.2f);
        float cry = Math.max(s[SafetySounds.CRYING], Math.max(s[SafetySounds.WHIMPER], s[SafetySounds.WAIL]));
        if (cry >= 0.45f && !tv) {
            if (now - cryFirst > 25_000) { cryFirst = now; cryHits = 0; }
            if (++cryHits >= 2 && now - cryAt > 30 * 60_000L) {
                cryAt = now;
                comfort(c);
            }
        }
        // at night: her steps or a door (heard twice within 20 s, not one odd sound) → a soft light and "walk carefully"
        if (HomeCare.night() && !tv && (s[SafetySounds.WALK] >= 0.4f || (s.length > SafetySounds.DOOR && s[SafetySounds.DOOR] >= 0.4f))) {
            if (now - stepFirst > 20_000) { stepFirst = now; stepHits = 0; }
            if (++stepHits >= 2) nightStir(c, now);
        }
    }

    static void comfort(Context c) {
        String w = HomeCare.who(c);
        MainActivity.homeMood("caring");
        HomeCare.ask(c, w + ", బాధపడకండి. నేను మీ దగ్గరే ఉన్నాను. ఏమైంది? అబ్బాయికి చెప్పమంటారా?", "cry", "caring");
    }

    /** Her answer to "అబ్బాయికి చెప్పమంటారా?" after crying. */
    static String cryAnswer(Context c, boolean tell) {
        String w = HomeCare.who(c);
        if (!tell) return "సరే " + w + ". నేను ఇక్కడే ఉన్నాను. కావాలంటే ఒక మంచి బైబిల్ వాక్యం చెబుతాను, \"Jarvis, వాక్యం చెప్పు\" అనండి.";
        boolean ok = HomeCare.alert(c, "💙 " + w + " బాధగా ఉన్నట్టు వినిపించింది (" + new java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date())
                + "). మీతో మాట్లాడాలనుకుంటున్నారు, ఒకసారి ఫోన్ చేయండి.");
        return ok ? "అబ్బాయికి చెప్పాను " + w + ". వీలవ్వగానే ఫోన్ చేస్తాడు. మీరు ఒంటరి కాదు." : "ఇప్పుడు నెట్ లేదు " + w + ", నెట్ రాగానే అబ్బాయికి చెబుతాను.";
    }

    /** Someone up at night in the hall (steps, a door, a touch): a soft light and "జాగ్రత్తగా నడవండి" (every 20 min at most). */
    static void nightStir(Context c, long now) {
        if (!HomeCare.on(c) || HomeCare.out(c) || now - stirAt < 20 * 60_000L) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 6 && h < 22) return;
        stirAt = now;
        MainActivity.homeNightLamp();
        HomeCare.say(c, HomeCare.who(c) + ", జాగ్రత్తగా నడవండి. లైట్ వేసుకోండి.", "caring");
    }

    // ================================================================ hand waves (plain Java: tested on the desk)

    /**
     * A hand waving beside the face: from a small grey picture (gw x gh cells) of each camera frame, the cells that differ
     * from the room's usual look (a slowly learnt background) in the strips left and right of the face; the centre of
     * those cells swinging side to side (two turns, wide enough) within ~2 s while the face stays put is a wave.
     */
    static final class Wave {
        private final int gw, gh;
        private float[] bg;
        private final ArrayDeque<float[]> hist = new ArrayDeque<>(); // {t, centre x (in face widths from the face centre), face centre x}
        private long lastWave = Long.MIN_VALUE / 2;

        Wave(int gw, int gh) { this.gw = gw; this.gh = gh; }

        /** One frame (grey cells, the face box in cells or null, time ms): true at the moment a wave is seen. */
        boolean add(int[] g, int[] face, long now) {
            if (bg == null) {
                bg = new float[g.length];
                for (int i = 0; i < g.length; i++) bg[i] = g[i];
                return false;
            }
            boolean[] fg = new boolean[g.length];
            for (int i = 0; i < g.length; i++) {
                fg[i] = Math.abs(g[i] - bg[i]) > 25;
                bg[i] += (g[i] - bg[i]) * (fg[i] ? 0.01f : 0.08f); // (the room is learnt; a moving hand hardly is)
            }
            if (face == null) { hist.clear(); return false; }
            int fw = Math.max(1, face[2] - face[0]), fh = Math.max(1, face[3] - face[1]);
            float fc = (face[0] + face[2]) / 2f;
            int y0 = Math.max(0, face[1] - fh * 6 / 10), y1 = Math.min(gh, face[3] + fh * 4 / 10);
            int gap = Math.max(1, fw * 15 / 100), reach = fw * 5 / 2;
            int lx0 = Math.max(0, face[0] - reach), lx1 = Math.max(0, face[0] - gap);
            int rx0 = Math.min(gw, face[2] + gap), rx1 = Math.min(gw, face[2] + reach);
            int n = 0;
            float sum = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = lx0; x < lx1; x++) if (fg[y * gw + x]) { n++; sum += x; }
                for (int x = rx0; x < rx1; x++) if (fg[y * gw + x]) { n++; sum += x; }
            }
            while (!hist.isEmpty() && now - hist.peekFirst()[0] > 2200) hist.pollFirst();
            if (n >= 4) hist.addLast(new float[]{now, (sum / n - fc) / fw, fc});
            if (hist.size() < 5 || now - lastWave < 4000) return false;
            // the face stays put (not someone walking by)
            float fMin = Float.MAX_VALUE, fMax = -Float.MAX_VALUE;
            for (float[] h : hist) { fMin = Math.min(fMin, h[2]); fMax = Math.max(fMax, h[2]); }
            if (fMax - fMin > fw * 0.4f) return false;
            // the movement's centre turns back at least twice, each swing a third of a face wide or more
            int turns = 0, dir = 0;
            float from = Float.NaN, last = Float.NaN;
            for (float[] h : hist) {
                float x = h[1];
                if (Float.isNaN(last)) { last = x; from = x; continue; }
                float d = x - last;
                if (Math.abs(d) < 0.08f) continue;
                int nd = d > 0 ? 1 : -1;
                if (dir != 0 && nd != dir) {
                    if (Math.abs(last - from) >= 0.33f) turns++;
                    from = last;
                }
                dir = nd;
                last = x;
            }
            if (turns >= 2) { lastWave = now; hist.clear(); return true; }
            return false;
        }
    }
}
