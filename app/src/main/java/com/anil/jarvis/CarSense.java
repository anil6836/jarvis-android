package com.anil.jarvis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The car sleep alert's own reckoning, with nothing of Android in it (desk-tested): why and how often to ask
 * "మెలకువగా ఉన్నారా?", his spoken answer from the mic's loudness, his eyes from the camera's face finder, and whole
 * pictures out of a Wi-Fi camera's stream.
 */
final class CarSense {
    private CarSense() {}

    /** 10 pm to 5 am. */
    static boolean night(int hour) { return hour >= 22 || hour < 5; }

    /**
     * Why the checks run now, as Telugu words he hears ("రాత్రి డ్రైవింగ్", …); empty: no reason, no questions.
     * Each reason counts only when its switch is on; "నిద్ర వస్తోంది" (sleepy) always counts.
     */
    static List<String> reasons(boolean nightOn, int hour, boolean dutyOn, boolean dutyTired, boolean sleepOn, boolean shortSleep,
                                boolean longOn, long drivingMin, boolean sleepy, boolean tired) {
        List<String> r = new ArrayList<>();
        if (sleepy) r.add("నిద్ర వస్తోందని చెప్పారు");
        if (tired) r.add("కొద్దిసేపటి క్రితం అలసట కనిపించింది"); // (a missed answer / heavy or closed eyes: Jarvis saw it)
        if (nightOn && night(hour)) r.add("రాత్రి డ్రైవింగ్");
        if (dutyOn && dutyTired) r.add("డ్యూటీ తర్వాత నిద్రపోలేదు");
        if (sleepOn && shortSleep) r.add("రాత్రి నిద్ర తక్కువైంది");
        if (longOn && drivingMin >= 120) r.add("2 గంటలకు పైగా ఆపకుండా డ్రైవింగ్");
        return r;
    }

    /** Minutes to the next question: after a missed answer 2, sleepy / heavy eyes 5, else his choice (kept within 10–30). */
    static int nextMinutes(boolean sleepy, boolean missed, int every) {
        if (missed) return 2;
        if (sleepy) return 5;
        return Math.max(10, Math.min(30, every));
    }

    // ================================================================ his answer, heard as loudness

    /**
     * His spoken answer in the seconds after the question, from the mic's loudness in 80 ms pieces: a short spoken bit —
     * his voice well over the car's hum (3x the hum, and loud in itself) for 0.3 to 3 seconds (small dips inside a word
     * allowed), then quiet again. A click or a bump is too short; music, wind or a long talk is too long, and after one
     * the hum must fall quiet for a second before anything counts again. The hum is followed slowly while it is quiet.
     */
    static final class Answer {
        static final int MIN_RUN = 4, MAX_RUN = 38, END_QUIET = 4, CLEAR_QUIET = 12;
        private double floor;
        private int run, gap, quiet;
        private boolean tooLong;

        Answer(double floor) { this.floor = Math.max(0, floor); }

        /** One piece's loudness (RMS of 16-bit samples); true when a short spoken bit has just ended. */
        boolean feed(double rms) {
            double gate = Math.max(300, floor * 3.0);
            if (rms > gate) {
                quiet = 0;
                if (run > 0 && gap > 0) run += gap; // a dip inside a word
                gap = 0;
                run++;
                if (run > MAX_RUN) { run = 0; tooLong = true; } // music / wind / a long talk: not an answer
                return false;
            }
            floor = floor <= 0 ? rms : floor * 0.97 + rms * 0.03;
            quiet++;
            if (tooLong) {
                if (quiet >= CLEAR_QUIET) tooLong = false;
                run = 0;
                gap = 0;
                return false;
            }
            if (run == 0) return false;
            gap++;
            if (gap < END_QUIET) return false;
            boolean ok = run >= MIN_RUN;
            run = 0;
            gap = 0;
            return ok;
        }
    }

    // ================================================================ his eyes, from the face finder

    /**
     * Watches the face finder's numbers picture by picture (about 6 a second): eyes closed for 2 s or more, or the head
     * dropped well below (25°) where he usually holds it for 2 s, is an alarm; four long blinks (0.4–2 s) inside a minute
     * is "heavy eyes". "Closed" is judged against his own open eyes (learnt while he drives), so narrow eyes or glasses
     * don't read as sleep; a head turned well to the side (a mirror, a shoulder check) is not counted at all.
     */
    static final class Eyes {
        static final int NONE = 0, CLOSED = 1, HEAD_DOWN = 2, HEAVY = 3;
        static final long CLOSED_MS = 2000, DOWN_MS = 2000, BLINK_MS = 400, HEAVY_WINDOW = 60_000, ALARM_GAP = 15_000, HEAVY_GAP = 10 * 60_000;
        static final int HEAVY_BLINKS = 4;
        static final float DOWN_DEG = 25f, SIDE_DEG = 35f;

        private double open = 0.8;          // his eyes' usual "open" chance
        private double pitch = Double.NaN;  // his head's usual up/down angle
        private long closedSince = -1, downSince = -1, lastFace = -1, lastAlarm = -ALARM_GAP, lastHeavy = -HEAVY_GAP;
        private final ArrayDeque<Long> blinks = new ArrayDeque<>();
        private boolean closedNow;

        /** Below this chance both eyes count as closed. */
        double closeLevel() { return Math.min(0.35, open * 0.45); }

        boolean closedNow() { return closedNow; }

        /**
         * One picture: the two eyes' open chances (null: not known), the head's up/down angle (degrees, up positive), its
         * left/right turn, and whether a face was found. Returns NONE, CLOSED, HEAD_DOWN or HEAVY.
         */
        int look(long t, Float left, Float right, float headUp, float headSide, boolean face) {
            if (face && Math.abs(headSide) > SIDE_DEG) face = false; // looking at a mirror: not his eyes on the road, not counted
            if (!face) {
                closedNow = false;
                if (lastFace < 0 || t - lastFace > 1000) { closedSince = -1; downSince = -1; } // (a moment's loss keeps the count)
                return NONE;
            }
            lastFace = t;
            boolean known = left != null || right != null;
            float o = known ? Math.max(left == null ? 0f : left, right == null ? 0f : right) : 1f;
            boolean closed = known && o < closeLevel();
            closedNow = closed;
            int event = NONE;
            if (closed) {
                if (closedSince < 0) closedSince = t;
                if (t - closedSince >= CLOSED_MS && t - lastAlarm >= ALARM_GAP) { lastAlarm = t; event = CLOSED; }
            } else {
                if (closedSince >= 0) {
                    long d = t - closedSince;
                    if (d >= BLINK_MS && d < CLOSED_MS) blinks.addLast(t);
                }
                closedSince = -1;
                if (known && o > 0.5f) open = open * 0.98 + o * 0.02;
            }
            // the head: dropped well below his usual way of holding it (learnt only while the eyes are open)
            if (Double.isNaN(pitch)) pitch = headUp;
            boolean down = headUp < pitch - DOWN_DEG;
            if (down) {
                if (downSince < 0) downSince = t;
                if (event == NONE && t - downSince >= DOWN_MS && t - lastAlarm >= ALARM_GAP) { lastAlarm = t; event = HEAD_DOWN; }
            } else {
                downSince = -1;
                if (!closed) pitch = pitch * 0.97 + headUp * 0.03;
            }
            while (!blinks.isEmpty() && t - blinks.peekFirst() > HEAVY_WINDOW) blinks.removeFirst();
            if (event == NONE && blinks.size() >= HEAVY_BLINKS && t - lastHeavy >= HEAVY_GAP) {
                lastHeavy = t;
                blinks.clear();
                event = HEAVY;
            }
            return event;
        }
    }

    // ================================================================ a Wi-Fi camera's pictures

    /**
     * Pulls whole JPEG pictures out of a camera's byte stream (MJPEG: picture after picture, each from FF D8 to FF D9,
     * whatever headers lie between). Returns the newest whole picture each time (older ones are dropped: only now counts).
     */
    static final class Jpegs {
        private static final int MAX = 4 << 20;
        private byte[] buf = new byte[1 << 16];
        private int len;

        byte[] add(byte[] b, int n) {
            if (n <= 0) return null;
            if (len + n > MAX) len = 0; // a stream with no pictures in it: start over
            if (len + n > buf.length) {
                byte[] nb = new byte[Math.min(Math.max(buf.length * 2, len + n), Math.max(MAX, n))];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(b, 0, buf, len, n);
            len += n;
            byte[] newest = null;
            while (true) {
                int s = find(buf, 0, len, (byte) 0xD8);
                if (s < 0) { // no start yet: keep only the last byte (it may be the FF of FF D8)
                    if (len > 1) { buf[0] = buf[len - 1]; len = 1; }
                    break;
                }
                int e = find(buf, s + 2, len, (byte) 0xD9);
                if (e < 0) { // a picture still coming: drop what lay before it
                    if (s > 0) { System.arraycopy(buf, s, buf, 0, len - s); len -= s; }
                    break;
                }
                int end = e + 2;
                newest = new byte[end - s];
                System.arraycopy(buf, s, newest, 0, newest.length);
                System.arraycopy(buf, end, buf, 0, len - end);
                len -= end;
            }
            return newest;
        }

        /** Where FF <m> starts at or after from (-1: not there). */
        private static int find(byte[] a, int from, int to, byte m) {
            for (int i = Math.max(0, from); i + 1 < to; i++) if (a[i] == (byte) 0xFF && a[i + 1] == m) return i;
            return -1;
        }
    }

    /** The rotations to try, in turn, for a camera whose way up isn't known (an outside camera): 0, 90, 270, 180. */
    static int nextRotation(int r) {
        switch (r) {
            case 0: return 90;
            case 90: return 270;
            case 270: return 180;
            default: return 0;
        }
    }
}
