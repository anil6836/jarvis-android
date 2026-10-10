package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One board game on the home tablet, with Jarvis as a player who talks like a person sitting across the board
 * ("మీరు ఏనుగుని జరిపారు… నేను గుర్రాన్ని ఇక్కడ పెట్టాను. ఇప్పుడు మీ వంతు అమ్మగారు.").
 *
 * A game keeps its rules in plain Java (its own *Rules class, tested on the desk) and here it draws the board, takes
 * taps and talks. The GamePanel around it shows Jarvis, the status line and the buttons, speaks what the game says
 * (only the newest waiting line), saves after every move, and sends the state to Anil's phone in a far game.
 *
 * Seats are roles: "her" (the person at the tablet: అమ్మగారు), "her2" / "her3" (others at the tablet), "son" (Anil on
 * his phone, a far game), "jarvis". Each device turns a role into HERE (plays by touch on this screen), JARVIS
 * (the computer chooses) or FAR (the move comes over the link).
 */
abstract class Game extends View {
    static final int HERE = 0, JARVIS = 1, FAR = 2;

    /** This copy runs on Anil's phone (GameActivity): "son" plays here, "her" is far. */
    static boolean onPhone;

    interface Host {
        /**
         * Jarvis says it (as a player, or narrating a far move). feeling: happy, laugh, excited, sad, caring, worried,
         * surprised, proud or null; gesture: clap, thumb, chin, point, wave, namaste or null. Only the newest line waiting
         * to be said is said (a quick player never makes him lag behind).
         */
        void say(String text, String feeling, String gesture);
        /** The short line under the board ("మీ వంతు", "Jarvis ఆలోచిస్తున్నాడు…"). */
        void status(String text);
        /** Jarvis is choosing his move: hand on chin, the thinking face (false: done). */
        void thinking(boolean on);
        /** A move was made (by anyone, after it is shown): save it; in a far game, send it. */
        void moved();
        /** The game is over: the winner's seat, -1 for a draw (the game has said its own words already). */
        void over(int winner);
        /** How well Jarvis plays: 0 easy, 1 medium, 2 hard. */
        int level();
        /** What to call her ("అమ్మగారు"). */
        String her();
    }

    protected Host host;
    /** Seat roles (see above); seat i plays the game's side i. */
    protected String[] roles = new String[0];
    protected boolean done;
    protected int winner = -1;
    /** Changes on every new game / load / undo: late work for an older position is dropped. */
    protected int gen;
    protected final Random rnd = new Random();
    protected final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private static ExecutorService brain;
    private boolean thinking;
    private long animStart, animMs;
    private Runnable animEnd;
    protected final float dp;
    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);

    Game(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void attach(Host h) { host = h; }

    // ================================================================ what each game gives

    /** "chess", "ludo", … (Games.LIST). */
    abstract String id();
    /** Telugu name ("చదరంగం"). */
    abstract String title();
    /** How many players can play: {min, max} (the players chooser shows only these). */
    int[] players() { return new int[]{2, 2}; }
    /** Can be played with Anil on his phone (false: pairs, its cards are this tablet's photos). */
    boolean farOk() { return true; }
    /** Choices before the game starts (side / colour / size), shown as big buttons; null: none. */
    String[] options() { return null; }
    /**
     * A new game. who: the roles in the order the players were chosen (who[0] is always "her" at the tablet, or "son"
     * when Anil starts from his phone); option: the index of options() picked (0 when none). Sets roles, the board,
     * then calls start().
     */
    abstract void newGame(String[] who, int option);
    /** The game's own state as JSON text (the board, whose turn, the dice, the last move…). */
    abstract String save();
    /** Back to a saved state; false when it can't be read. */
    abstract boolean load(String state);
    /** The seat whose turn it is now. */
    abstract int turn();
    /** Jarvis's move for the seat whose turn it is (use think(…) for the search, then make it, talk, moved()). */
    abstract void jarvisMove();
    /** Words for the last move made, for a far player's move ("గుర్రాన్ని ముందుకు జరిపాడు"): whole sentence, or "". */
    String lastWords() { return ""; }
    /** A tap on the board by a person at this screen whose turn it is (x, y in view pixels). */
    abstract void tap(float x, float y);
    /** "💡 సలహా": shows (and says) a good move for the person whose turn it is; false when there is none. */
    boolean hint() { return false; }
    /** Her words while the game is open ("పాచిక వేయి", "నువ్వే జరుపు"…): true when the game took them. */
    boolean heard(String t) { return false; }
    /** The status line for a person at this screen whose turn it is. */
    String yourTurn(int seat) { return (sole(seat) ? "మీ వంతు" : name(seat) + " వంతు") + " 👇"; }

    // ================================================================ seats and words

    int kind(int seat) {
        String r = role(seat);
        if (r.equals("jarvis")) return JARVIS;
        if (r.equals("son")) return onPhone ? HERE : FAR;
        return onPhone ? FAR : HERE;
    }

    String role(int seat) { return seat >= 0 && seat < roles.length && roles[seat] != null ? roles[seat] : "her"; }

    /** The seat's name in a sentence ("అమ్మగారు", "అబ్బాయి", "రెండో ఆటగాడు", "Jarvis"). */
    String name(int seat) {
        String r = role(seat);
        switch (r) {
            case "jarvis": return "Jarvis";
            case "son": return onPhone ? "మీరు" : "అబ్బాయి";
            case "her2": return "రెండో ఆటగాడు";
            case "her3": return "మూడో ఆటగాడు";
            default: return onPhone ? "అమ్మగారు" : host != null ? host.her() : "అమ్మగారు";
        }
    }

    /** The only person playing at this screen (then Jarvis says "మీరు" to them). */
    boolean sole(int seat) {
        if (kind(seat) != HERE) return false;
        int n = 0;
        for (int i = 0; i < roles.length; i++) if (kind(i) == HERE) n++;
        return n == 1;
    }

    /** Who did it, as the subject: "నేను" (Jarvis), "మీరు" (the only person here), else the name. */
    String who(int seat) { return role(seat).equals("jarvis") ? "నేను" : sole(seat) ? "మీరు" : name(seat); }

    /** The verb for the seat: me (Jarvis: "జరిపాను"), polite (her, people here, Anil on his own phone: "జరిపారు"),
     *  he (Anil far, on the tablet: "జరిపాడు"). */
    String verb(int seat, String polite, String me, String he) {
        String r = role(seat);
        if (r.equals("jarvis")) return me;
        if (r.equals("son") && !onPhone) return he;
        return polite;
    }

    /** "who + verb": "మీరు జరిపారు", "నేను జరిపాను", "అబ్బాయి జరిపాడు". */
    String did(int seat, String polite, String me, String he) { return who(seat) + " " + verb(seat, polite, me, he); }

    /** A far game (no undo, no hint for the far side, no Jarvis). */
    boolean far() { for (int i = 0; i < roles.length; i++) if (kind(i) == FAR) return true; return false; }

    boolean hasJarvis() { for (String r : roles) if ("jarvis".equals(r)) return true; return false; }

    /** One of these, at random (Jarvis doesn't repeat himself every time). */
    String pick(String... s) { return s[rnd.nextInt(s.length)]; }

    /** Jarvis says it (see Host.say). */
    void say(String text, String feeling, String gesture) { if (host != null && text != null && !text.trim().isEmpty()) host.say(text.trim(), feeling, gesture); }
    void say(String text) { say(text, null, null); }

    // ================================================================ the turn flow

    /** Call at the end of newGame / load: the board is shown and whoever's turn it is plays. */
    protected void start() {
        done = false;
        winner = -1;
        gen++;
        invalidate();
        turnNow();
    }

    /** After a move is made and shown: save / send it, then the next player (or the end, when the game called finish). */
    protected void moved() {
        invalidate();
        if (done) return; // (finish has saved / sent it already)
        if (host != null) host.moved();
        turnNow();
    }

    /** Whoever's turn it is now plays (Jarvis thinks; a far player is waited for; a person here is asked). */
    protected void turnNow() {
        if (done || host == null) return;
        int s = turn();
        int k = kind(s);
        if (k == JARVIS) {
            jarvisAskedAt = SystemClock.uptimeMillis();
            host.status("Jarvis ఆలోచిస్తున్నాడు…");
            final int g = gen;
            // a little pause first, like a person looking at the board (also lets her see her own move)
            later(700 + rnd.nextInt(600), () -> { if (g == gen && !done && kind(turn()) == JARVIS) jarvisMove(); });
        } else if (k == FAR) {
            host.status(name(s) + " వంతు… (ఫోన్ నుంచి వస్తుంది)");
        } else {
            host.status(yourTurn(s));
        }
    }

    private long jarvisAskedAt;

    /** Jarvis's turn came long ago and nothing is going on (his search failed): the panel asks him again. */
    boolean jarvisStuck() {
        return !done && kind(turn()) == JARVIS && !busy() && SystemClock.uptimeMillis() - jarvisAskedAt > 25_000L;
    }

    /** The game is over: says the words, marks the winner (-1 draw). */
    protected void finish(int win, String words, String feeling, String gesture) {
        if (done) return;
        done = true;
        winner = win;
        thinkingOff();
        invalidate();
        say(words, feeling, gesture);
        if (host != null) { host.moved(); host.over(win); }
    }

    /** A far player's move arrived (the whole game, saveAll): shown and narrated, then the turn goes on. */
    void farMoved(String all) {
        boolean wasDone = done;
        try {
            JSONObject j = new JSONObject(all);
            if (!id().equals(j.optString("id"))) return;
            gen++;
            thinkingOff();
            if (!load(j.getString("st"))) return;
            done = j.optBoolean("done");
            winner = j.optInt("win", -1);
        } catch (Exception e) {
            return;
        }
        undo.clear();
        invalidate();
        String w = lastWords();
        if (done) {
            if (wasDone) return;
            String end = winner < 0 ? "ఆట సమానంగా ముగిసింది! ఇద్దరూ బాగా ఆడారు."
                    : kind(winner) == HERE ? (sole(winner) ? "మీరు గెలిచారు! చాలా బాగా ఆడారు!" : name(winner) + " గెలిచారు!")
                    : name(winner) + " " + verb(winner, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! పర్వాలేదు, మళ్లీ ఆడదాం.";
            say((w.isEmpty() ? "" : w + " ") + end, winner >= 0 && kind(winner) == HERE ? "excited" : "happy", winner >= 0 && kind(winner) == HERE ? "clap" : null);
            if (host != null) host.over(winner);
            return;
        }
        if (!w.isEmpty()) say(w + " " + (kind(turn()) == HERE ? (sole(turn()) ? "ఇప్పుడు మీ వంతు." : "ఇప్పుడు " + name(turn()) + " వంతు.") : ""));
        turnNow();
    }

    // ================================================================ Jarvis thinking (a background thread)

    /** Work off the main thread (a search); done() runs on the main thread, only if the game hasn't changed since. */
    @SuppressWarnings("unchecked")
    protected <T> void think(Callable<T> work, Consumer<T> then) {
        final int g = gen;
        thinking = true;
        if (host != null) host.thinking(true);
        final long t0 = SystemClock.uptimeMillis();
        synchronized (Game.class) { if (brain == null) brain = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "game-brain"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; }); }
        brain.execute(() -> {
            T r = null;
            try { r = work.call(); } catch (Throwable ignored) {}
            final T res = r;
            long wait = Math.max(0, 900 - (SystemClock.uptimeMillis() - t0)); // (never an instant answer: it looks like thinking)
            main.postDelayed(() -> {
                if (g != gen) return;
                thinkingOff();
                then.accept(res);
            }, wait);
        });
    }

    private void thinkingOff() {
        if (!thinking) return;
        thinking = false;
        if (host != null) host.thinking(false);
    }

    boolean busy() { return thinking || animating(); }

    // ================================================================ animation and timers

    /** Animates for ms (draw with anim()); end runs after it (main thread). */
    protected void animate(long ms, Runnable end) {
        animStart = SystemClock.uptimeMillis();
        animMs = Math.max(1, ms);
        animEnd = end;
        postInvalidateOnAnimation();
    }

    protected boolean animating() { return animEnd != null || (animMs > 0 && SystemClock.uptimeMillis() - animStart < animMs); }

    /** 0..1 progress of the running animation, eased (1 when none). */
    protected float anim() {
        if (animMs <= 0) return 1f;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) animMs);
        return t * t * (3 - 2 * t);
    }

    /** Raw 0..1 progress (not eased). */
    protected float animRaw() { return animMs <= 0 ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) animMs); }

    @Override protected final void onDraw(Canvas c) {
        draw2(c);
        if (animMs > 0) {
            if (SystemClock.uptimeMillis() - animStart < animMs) postInvalidateOnAnimation();
            else {
                animMs = 0;
                Runnable e = animEnd;
                animEnd = null;
                if (e != null) main.post(e);
            }
        }
    }

    /** The game's drawing (onDraw is the base's). */
    protected abstract void draw2(Canvas c);

    /** Runs after ms on the main thread, only if the game hasn't changed (new game / undo / load) since. */
    protected void later(long ms, Runnable r) {
        final int g = gen;
        main.postDelayed(() -> { if (g == gen) r.run(); }, ms);
    }

    /** The panel closes: no more timers or talk from this game. */
    void stop() {
        gen++;
        main.removeCallbacksAndMessages(null);
        animMs = 0;
        animEnd = null;
        thinkingOff();
    }

    // ================================================================ touches

    private float downX, downY;

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); return true;
            case MotionEvent.ACTION_UP:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 40 * dp) return true; // (a swipe, not a tap)
                performClick();
                if (done || busy() || host == null) return true;
                if (kind(turn()) != HERE) {
                    if (kind(turn()) == JARVIS) host.status("ఒక్క క్షణం, Jarvis వంతు…");
                    return true;
                }
                tap(e.getX(), e.getY());
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    // ================================================================ undo (her last move and Jarvis's answer)

    /** Before a move of a person here: kept so "↩️ వెనక్కి" can take it back. */
    protected void keep() {
        undo.push(save());
        while (undo.size() > 40) undo.removeLast();
    }

    boolean canUndo() { return !far() && !undo.isEmpty(); }

    /** Back to before the last move of a person here. */
    boolean undo() {
        if (!canUndo()) return false;
        String s = undo.pop();
        gen++;
        thinkingOff();
        animMs = 0;
        animEnd = null;
        if (!load(s)) return false;
        done = false;
        winner = -1;
        invalidate();
        if (host != null) host.moved();
        turnNow();
        return true;
    }

    // ================================================================ the whole game (roles + state), for saving / the link

    String saveAll() {
        try {
            JSONArray r = new JSONArray();
            for (String s : roles) r.put(s);
            return new JSONObject().put("id", id()).put("roles", r).put("done", done).put("win", winner).put("st", save()).toString();
        } catch (Exception e) {
            return "";
        }
    }

    boolean loadAll(String all) {
        try {
            JSONObject j = new JSONObject(all);
            if (!id().equals(j.optString("id"))) return false;
            JSONArray r = j.getJSONArray("roles");
            String[] ro = new String[r.length()];
            for (int i = 0; i < ro.length; i++) ro[i] = r.getString(i);
            roles = ro;
            if (!load(j.getString("st"))) return false;
            done = j.optBoolean("done");
            winner = j.optInt("win", -1);
            undo.clear();
            gen++;
            invalidate();
            if (!done) turnNow();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Roles of a saved whole game (to know whether it was a far game), or null. */
    static String[] rolesOf(String all) {
        try {
            JSONArray r = new JSONObject(all).getJSONArray("roles");
            String[] ro = new String[r.length()];
            for (int i = 0; i < ro.length; i++) ro[i] = r.getString(i);
            return ro;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ drawing helpers

    /** Player colours (big and clear): red, green, yellow, blue. */
    static final int[] COLORS = {0xFFE53935, 0xFF2E9E44, 0xFFF9C80E, 0xFF1E73D8};
    static final String[] COLOR_NAMES = {"ఎరుపు", "పచ్చ", "పసుపు", "నీలం"};

    /** The biggest centred square in this view (with a margin). */
    protected RectF square(float margin) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h) - 2 * margin;
        return new RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f);
    }

    /** Text centred at (x, y) (y is the middle of the text). */
    protected void centerText(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
    }

    /** A die: a rounded white square with black pips (value 1..6) centred at (cx, cy); glow: a coloured ring (whose die). */
    protected void drawDie(Canvas c, float cx, float cy, float size, int value, int glow) {
        float h = size / 2f, r = size * 0.18f;
        RectF b = new RectF(cx - h, cy - h, cx + h, cy + h);
        if (glow != 0) {
            line.setColor(glow);
            line.setStrokeWidth(size * 0.08f);
            c.drawRoundRect(new RectF(b.left - size * 0.09f, b.top - size * 0.09f, b.right + size * 0.09f, b.bottom + size * 0.09f), r * 1.3f, r * 1.3f, line);
        }
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(b, r, r, fill);
        line.setColor(0xFF222222);
        line.setStrokeWidth(Math.max(2, size * 0.03f));
        c.drawRoundRect(b, r, r, line);
        fill.setColor(0xFF111111);
        float p = size * 0.25f, pr = size * 0.09f;
        boolean[] at = PIPS[Math.max(1, Math.min(6, value)) - 1];
        float[][] xy = {{-p, -p}, {0, -p}, {p, -p}, {-p, 0}, {0, 0}, {p, 0}, {-p, p}, {0, p}, {p, p}};
        for (int i = 0; i < 9; i++) if (at[i]) c.drawCircle(cx + xy[i][0], cy + xy[i][1], pr, fill);
    }

    private static final boolean[][] PIPS = {
            {false, false, false, false, true, false, false, false, false},
            {true, false, false, false, false, false, false, false, true},
            {true, false, false, false, true, false, false, false, true},
            {true, false, true, false, false, false, true, false, true},
            {true, false, true, false, true, false, true, false, true},
            {true, false, true, true, false, true, true, false, true}};

    /** Telugu words for small numbers (dice, steps): Jarvis says them, never digits. */
    static String num(int n) {
        String[] w = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది",
                "పదకొండు", "పన్నెండు"};
        return n >= 0 && n < w.length ? w[n] : String.valueOf(n);
    }
}
