package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The games on the home tablet's screen: the board on the left, Jarvis (the same Jarvis, moved here from the home
 * screen while a game is open) on the right with what he says, whose turn it is, the score, and big buttons
 * (↩️ వెనక్కి, 💡 సలహా, 🔄 కొత్త ఆట, 🎲 ఆటలు, ✕). First a picker of all the games, then who plays (Jarvis, people at
 * home, or Anil from his phone), then the game. Every move is saved, so a game can go on later.
 */
final class GamePanel extends FrameLayout implements Game.Host, GameLink.Listener {
    interface Outer {
        /** Jarvis says it (only the newest waiting line is said). */
        void gameSay(String text, String feeling);
        /** A talking game (memory, quiz, riddle, words): played in the conversation, not on a board. */
        void talkGame(String id);
        /** The panel closed: Jarvis goes back to the home screen. */
        void gamesClosed();
        BodyRig rig();
    }

    /** The panel while it is on the screen (main thread). */
    static volatile GamePanel shown;

    private final Activity act;
    private final Outer outer;
    /** Jarvis and his bubble are put here while the panel is open. */
    final FrameLayout jarvisSlot;
    private final FrameLayout area;
    private final TextView title, levelChip, status, score;
    private final TextView undoB, hintB, newB, listB;
    private final LinearLayout gameButtons;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Game game;
    private String gameId;
    private String[] lastWho;
    private int lastOption;
    private int herWins, otherWins, draws;
    private long playingSince, lastMoveAt, restSaidAt, touchedAt = System.currentTimeMillis();
    private GameLink link;

    GamePanel(Activity a, Outer outer) {
        super(a);
        this.act = a;
        this.outer = outer;
        setBackgroundColor(0xFF0E1A2C);
        setClickable(true);
        LinearLayout row = new LinearLayout(a);
        addView(row, new LayoutParams(-1, -1));

        area = new FrameLayout(a);
        area.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(area, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(0xFF13233A);
        side.setPadding(dp(16), dp(12), dp(16), dp(14));
        row.addView(side, new LinearLayout.LayoutParams(dp(410), -1));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = text(a, "🎲 ఆటలు", 24, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        levelChip = text(a, "", 15, 0xFF10213A, true);
        levelChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        levelChip.setBackground(round(0xFFF2B544, 0, 18));
        levelChip.setOnClickListener(v -> cycleLevel());
        head.addView(levelChip);
        side.addView(head);

        jarvisSlot = new FrameLayout(a);
        LinearLayout.LayoutParams js = new LinearLayout.LayoutParams(-1, 0, 1);
        js.topMargin = dp(4);
        side.addView(jarvisSlot, js);

        status = text(a, "", 23, 0xFFFFD166, true);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        side.addView(status, new LinearLayout.LayoutParams(-1, -2));
        score = text(a, "", 17, 0xFFB4C5D8, false);
        score.setGravity(Gravity.CENTER);
        side.addView(score, new LinearLayout.LayoutParams(-1, -2));

        gameButtons = new LinearLayout(a);
        undoB = button(a, "↩️ వెనక్కి", 0xFF1F3B5C);
        undoB.setOnClickListener(v -> doUndo());
        hintB = button(a, "💡 సలహా", 0xFF1F3B5C);
        hintB.setOnClickListener(v -> doHint());
        gameButtons.addView(undoB, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(58), 1);
        hl.leftMargin = dp(8);
        gameButtons.addView(hintB, hl);
        LinearLayout.LayoutParams gb = new LinearLayout.LayoutParams(-1, -2);
        gb.topMargin = dp(8);
        side.addView(gameButtons, gb);

        LinearLayout r2 = new LinearLayout(a);
        newB = button(a, "🔄 కొత్త ఆట", 0xFF1F3B5C);
        newB.setOnClickListener(v -> again());
        listB = button(a, "🎲 ఆటలు", 0xFF1F3B5C);
        listB.setOnClickListener(v -> showPicker(false));
        TextView close = button(a, "✕", 0xFF5A2A2A);
        close.setContentDescription("ఆటలు మూసేయి");
        close.setOnClickListener(v -> close(true));
        r2.addView(newB, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(58), 1);
        l2.leftMargin = dp(8);
        r2.addView(listB, l2);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(dp(64), dp(58));
        l3.leftMargin = dp(8);
        r2.addView(close, l3);
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-1, -2);
        r2l.topMargin = dp(8);
        side.addView(r2, r2l);
    }

    // ================================================================ opening

    /** The list of games (Jarvis asks which one, unless quiet). */
    void showPicker(boolean quiet) {
        endGame();
        shown = this;
        title.setText("🎲 ఆటలు");
        levelChip.setVisibility(GONE);
        gameButtons.setVisibility(GONE);
        newB.setVisibility(GONE);
        listB.setVisibility(GONE);
        status.setText("ఏ ఆట ఆడదాం? 👈");
        score.setText("");
        area.removeAllViews();
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col, new LayoutParams(-1, -2));
        String sid = Games.savedId(act);
        if (sid != null && Games.isBoard(sid)) {
            TextView go = button(act, "▶  ఆపిన ఆట కొనసాగించు: " + Games.emoji(sid) + " " + Games.name(sid), 0xFF2E7D4F);
            go.setTextSize(21);
            go.setOnClickListener(v -> resume());
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(72));
            gl.bottomMargin = dp(12);
            col.addView(go, gl);
        }
        LinearLayout r = null;
        int n = 0;
        for (String[] g : Games.LIST) {
            if (!Games.talking(g[0]) && !Games.isBoard(g[0])) continue; // (a board game not in this build)
            if (n % 4 == 0) {
                r = new LinearLayout(act);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(150));
                rl.bottomMargin = dp(12);
                col.addView(r, rl);
            }
            final String id = g[0];
            LinearLayout tile = new LinearLayout(act);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(round(Games.talking(id) ? 0xFF233A5E : 0xFF1F3B5C, 0xFF2E4C74, 20));
            tile.addView(text(act, g[1], 46, 0xFFFFFFFF, false));
            TextView nm = text(act, g[2], 19, 0xFFFFFFFF, true);
            nm.setGravity(Gravity.CENTER);
            nm.setMaxLines(2);
            tile.addView(nm);
            if (Games.talking(id)) tile.addView(text(act, "🗣️ మాటలతో", 13, 0xFF8FB4D9, false));
            tile.setOnClickListener(v -> choose(id));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1);
            if (n % 4 != 0) tl.leftMargin = dp(12);
            r.addView(tile, tl);
            n++;
        }
        while (r != null && n % 4 != 0) { // (the last row keeps the tiles' size)
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1);
            tl.leftMargin = dp(12);
            r.addView(new View(act), tl);
            n++;
        }
        area.addView(sv, new LayoutParams(-1, -1));
        if (!quiet) say(pick("ఏ ఆట ఆడదాం " + her() + "? నచ్చిన ఆట మీద నొక్కండి, లేదా పేరు చెప్పండి.",
                her() + ", ఆడుకుందామా? చదరంగం, లూడో, వైకుంఠపాళి… ఏది కావాలంటే అది నొక్కండి."), "happy", "point");
    }

    /** A game picked (from the list, or by her words). */
    void choose(String id) {
        touched();
        if (Games.talking(id)) { close(false); outer.talkGame(id); return; }
        Game probe = Games.make(act, id);
        if (probe == null) { showPicker(true); say("ఆ ఆట ఇంకా లేదు " + her() + ", వేరేది ఎంచుకోండి.", null, null); return; }
        int[] pl = probe.players();
        List<String[]> who = new ArrayList<>();
        List<String> label = new ArrayList<>();
        if (pl[0] <= 1) { who.add(new String[]{"her"}); label.add("🙋 నేను ఒక్కదాన్నే"); }
        if (pl[0] <= 2 && pl[1] >= 2) { who.add(new String[]{"her", "jarvis"}); label.add("🤖 Jarvis తో ఆడతాను"); }
        if (pl[1] >= 3) { who.add(new String[]{"her", "her2", "jarvis"}); label.add("👥 నేను + ఇంకొకరు + Jarvis"); }
        if (pl[1] >= 4) { who.add(new String[]{"her", "her2", "her3", "jarvis"}); label.add("👨‍👩‍👦 నేను + ఇద్దరు + Jarvis"); }
        if (pl[0] <= 2 && pl[1] >= 2) { who.add(new String[]{"her", "her2"}); label.add("👥 ఇంట్లో ఇద్దరం (Jarvis చూస్తాడు)"); }
        if (pl[0] <= 2 && pl[1] >= 2 && probe.farOk() && GameLink.ready(act)) { who.add(new String[]{"her", "son"}); label.add("📱 అబ్బాయితో (ఆయన ఫోన్ నుంచి)"); }
        String[] opts = probe.options();
        if (who.size() == 1) { pickOption(id, who.get(0), opts); return; }
        choices(Games.emoji(id) + " " + Games.name(id), "ఎవరెవరు ఆడతారు?", label, i -> pickOption(id, who.get(i), opts));
        say(pick("సరే, " + Games.name(id) + "! ఎవరెవరు ఆడతారు?", Games.name(id) + " ఆడదాం! నాతో ఆడతారా, ఇంకెవరైనా ఉన్నారా?"), "happy", null);
    }

    private void pickOption(String id, String[] who, String[] opts) {
        if (opts == null || opts.length == 0) { start(id, who, 0); return; }
        List<String> l = new ArrayList<>();
        for (String o : opts) l.add(o);
        choices(Games.emoji(id) + " " + Games.name(id), "ఏది?", l, i -> start(id, who, i));
    }

    private interface Pick { void on(int i); }

    /** Big buttons in the board's place (who plays / which side). */
    private void choices(String head, String ask, List<String> labels, Pick p) {
        endGame();
        title.setText(head);
        levelChip.setVisibility(GONE);
        gameButtons.setVisibility(GONE);
        newB.setVisibility(GONE);
        listB.setVisibility(VISIBLE);
        status.setText(ask + " 👈");
        area.removeAllViews();
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView q = text(act, ask, 30, 0xFFFFFFFF, true);
        q.setGravity(Gravity.CENTER);
        col.addView(q, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < labels.size(); i++) {
            final int k = i;
            TextView b = button(act, labels.get(i), 0xFF1F3B5C);
            b.setTextSize(23);
            b.setOnClickListener(v -> { touched(); p.on(k); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(78));
            bl.topMargin = dp(14);
            bl.leftMargin = bl.rightMargin = dp(60);
            col.addView(b, bl);
        }
        area.addView(col, new LayoutParams(-1, -1));
    }

    /** A new game with these players. */
    void start(String id, String[] who, int option) {
        Game g = Games.make(act, id);
        if (g == null) { showPicker(true); return; }
        endGame();
        shown = this;
        lastWho = who;
        lastOption = option;
        if (!id.equals(gameId)) { herWins = otherWins = draws = 0; }
        show(id, g);
        boolean far = false;
        for (String r : who) if ("son".equals(r)) far = true;
        Games.forget(act);
        Games.keepOption(act, option);
        if (far) {
            link = new GameLink(act, this);
            g.newGame(who, option);
            link.invite(id, option, g.saveAll());
        } else {
            g.newGame(who, option);
        }
        Games.keep(act, id, g.saveAll());
        playingSince = lastMoveAt = System.currentTimeMillis();
    }

    /** The saved game, from where it was left. */
    void resume() {
        String id = Games.savedId(act), all = Games.saved(act);
        Game g = id == null ? null : Games.make(act, id);
        if (g == null) { showPicker(true); return; }
        endGame();
        shown = this;
        show(id, g);
        String[] ro = Game.rolesOf(all);
        boolean far = false;
        if (ro != null) for (String r : ro) if ("son".equals(r)) far = true;
        if (far) link = new GameLink(act, this);
        if (!g.loadAll(all)) { Games.forget(act); showPicker(true); say("ఆ ఆట తెరవలేకపోయాను " + her() + ", కొత్తది ఆడదాం.", null, null); return; }
        lastWho = ro;
        lastOption = Games.savedOption(act);
        if (link != null) link.resume(id, g.saveAll());
        if (!far) say(pick("సరే, ఆపిన చోటు నుంచే ఆడదాం!", "ఇదిగో, మన ఆట అలాగే ఉంది. కొనసాగిద్దాం!"), "happy", null);
        playingSince = lastMoveAt = System.currentTimeMillis();
    }

    private org.json.JSONObject invite;

    /** Anil asks from his phone to play: two big buttons (she may also answer by voice). */
    void invited(org.json.JSONObject j) {
        invite = j;
        String id = j.optString("g");
        List<String> l = new ArrayList<>();
        l.add("✅ ఆడదాం");
        l.add("❌ ఇప్పుడు కాదు");
        choices(Games.emoji(id) + " " + Games.name(id), "అబ్బాయి ఆడదాం అంటున్నాడు!", l, i -> inviteAnswer(i == 0));
    }

    /** Her answer to Anil's invite; false when there is no invite waiting. */
    boolean inviteAnswer(boolean yes) {
        org.json.JSONObject j = invite;
        invite = null;
        if (j == null) return false;
        String id = j.optString("g"), gid = j.optString("gid"), all = j.optString("all");
        if (!yes) {
            GameLink.decline(act, gid, id);
            close(false);
            say("సరే " + her() + ", అబ్బాయికి తర్వాత ఆడదాం అని చెప్పాను.", null, null);
            return true;
        }
        GameLink l = new GameLink(act, this);
        l.join(gid, id);
        startFar(l, id, all);
        say(pick("సరే! అబ్బాయితో " + Games.name(id) + " మొదలు. బాగా ఆడండి " + her() + "!", "భలే! అబ్బాయితో ఆట మొదలైంది!"), "excited", "clap");
        return true;
    }

    /** Anil's phone asked her to play (a far game): she said yes. */
    void startFar(GameLink l, String id, String all) {
        Game g = Games.make(act, id);
        if (g == null) return;
        endGame();
        shown = this;
        link = l;
        show(id, g);
        if (!g.loadAll(all)) { close(false); return; }
        lastWho = Game.rolesOf(all);
        Games.keep(act, id, g.saveAll());
        playingSince = lastMoveAt = System.currentTimeMillis();
    }

    private void show(String id, Game g) {
        game = g;
        gameId = id;
        g.attach(this);
        title.setText(Games.emoji(id) + " " + Games.name(id));
        gameButtons.setVisibility(VISIBLE);
        newB.setVisibility(VISIBLE);
        listB.setVisibility(VISIBLE);
        area.removeAllViews();
        area.addView(g, new LayoutParams(-1, -1));
        showScore();
    }

    private void showScore() {
        Game g = game;
        boolean jarvis = g != null && g.hasJarvis();
        levelChip.setVisibility(jarvis ? VISIBLE : GONE);
        levelChip.setText("స్థాయి: " + Games.LEVELS[Games.level(act, gameId)]);
        if (herWins + otherWins + draws == 0) { score.setText(""); return; }
        String other = jarvis ? "Jarvis" : g != null && g.far() ? "అబ్బాయి" : "ఇతరులు";
        score.setText("మీరు " + herWins + "  ·  " + other + " " + otherWins + (draws > 0 ? "  ·  సమానం " + draws : ""));
        boolean undoOk = g != null && !g.far();
        undoB.setEnabled(undoOk);
        undoB.setAlpha(undoOk ? 1f : 0.4f);
    }

    // ================================================================ buttons

    private void doUndo() {
        touched();
        if (game == null) return;
        if (game.far()) { say("అబ్బాయితో ఆటలో వెనక్కి తీసుకోలేం " + her() + ".", null, null); return; }
        if (game.undo()) say(pick("సరే, వెనక్కి తీసుకున్నాను. మళ్లీ ఆలోచించి పెట్టండి.", "సరే, ముందు ఉన్నట్టే పెట్టాను."), "happy", null);
        else say("వెనక్కి తీసుకోవడానికి ఇంకా ఏమీ లేదు " + her() + ".", null, null);
    }

    private void doHint() {
        touched();
        if (game == null || game.done) return;
        if (game.kind(game.turn()) != Game.HERE) { say("ఇప్పుడు మీ వంతు కాదు " + her() + ", కొంచెం ఆగండి.", null, null); return; }
        if (!game.hint()) say("ఇప్పుడు నా దగ్గర సలహా లేదు " + her() + ", మీకు నచ్చింది పెట్టండి.", null, null);
    }

    /** 🔄: the same game again with the same players. */
    void again() {
        touched();
        if (gameId == null || lastWho == null) { showPicker(true); return; }
        if (game != null && !game.done && game.far()) { say("అబ్బాయితో ఆట ఇంకా అవ్వలేదు " + her() + ".", null, null); return; }
        start(gameId, lastWho, lastOption);
    }

    private void cycleLevel() {
        touched();
        if (gameId == null) return;
        int l = (Games.level(act, gameId) + 1) % 3;
        Games.setLevel(act, gameId, l);
        showScore();
        say(l == 0 ? "సరే, సులభంగా ఆడతాను." : l == 1 ? "సరే, కొంచెం ఆలోచించి ఆడతాను." : "సరే, ఇక పూర్తిగా ఆలోచించి ఆడతాను!", "happy", null);
    }

    // ================================================================ her words while a game is open

    /** Her words: true when they were for the game (it may answer by itself). */
    boolean heard(String t) {
        touched();
        if (t.matches("(?s).*(ఆట ఆపు|ఆట చాలు|ఆటలు చాలు|ఆట మూసేయ్|ఆట మూసేయి|ఆట ముగించు|బయటికి వెళ్ళు|ఆట వద్దు).*")) {
            close(false);
            say("సరే " + her() + ", ఆట ఆపాను. మళ్లీ ఆడాలంటే 🎲 నొక్కండి, మన ఆట అలాగే ఉంటుంది.", "happy", null);
            return true;
        }
        if (game != null && !game.done && game.kind(game.turn()) == Game.HERE) {
            if (t.matches("(?s).*(సలహా|ఏది మంచిది|ఎక్కడ పెట్టాలి|ఏం చేయాలి|ఏమి చేయాలి|నువ్వే చెప్పు|సహాయం చేయి).*")) { doHint(); return true; }
        }
        if (game != null && t.matches("(?s).*(వెనక్కి|undo|తప్పు పెట్టాను|మళ్లీ పెడతాను).*")) { doUndo(); return true; }
        if (t.matches("(?s).*(కొత్త ఆట|మళ్లీ ఆడదాం|ఇంకో ఆట|ఇంకొకటి ఆడదాం|మళ్ళీ ఆడదాం).*") && gameId != null) { again(); return true; }
        if (game != null && game.heard(t)) return true;
        String id = Games.named(t);
        if (id != null) { choose(id); return true; }
        if (game == null && t.matches("(?s).*(లేదు|వద్దు|తర్వాత).*")) { close(false); say("సరే " + her() + ", తర్వాత ఆడదాం.", null, null); return true; }
        return false;
    }

    // ================================================================ Game.Host

    @Override public void say(String text, String feeling, String gesture) {
        if (text == null || text.trim().isEmpty()) return;
        if (gesture != null) outer.rig().gesture(gesture, 2600);
        outer.gameSay(text.trim(), feeling);
    }

    @Override public void status(String text) { status.setText(text); }

    @Override public void thinking(boolean on) {
        BodyRig r = outer.rig();
        if (on) { r.gesture("chin", 30_000L); r.show("thinking", 30_000L); }
        else { r.gesture("none", 0); r.show("smile", 0); }
    }

    @Override public void moved() {
        Game g = game;
        if (g == null) return;
        long now = System.currentTimeMillis();
        if (now - lastMoveAt > 10 * 60_000L) playingSince = now; // (a long break: a new sitting)
        lastMoveAt = now;
        String all = g.saveAll();
        if (!g.done) Games.keep(act, gameId, all);
        if (link != null) link.send(all);
        showScore();
        restCheck(now);
    }

    @Override public void over(int win) {
        Game g = game;
        if (g == null) return;
        Games.forget(act);
        String r = win < 0 ? "draw" : "her".equals(g.role(win)) ? "won" : "lost";
        if ("won".equals(r)) herWins++; else if ("lost".equals(r)) otherWins++; else draws++;
        if (link != null) link.over();
        String more = Games.result(act, gameId, r, g.hasJarvis(), her());
        showScore();
        if (!more.isEmpty()) main.postDelayed(() -> { if (game == g) say(more, "happy", null); }, 1500);
        status.setText(win < 0 ? "సమానం! 🤝" : "her".equals(g.role(win)) ? "మీరు గెలిచారు! 🎉" : g.name(win) + " " + g.verb(win, "గెలిచారు", "గెలిచాడు", "గెలిచాడు") + "!");
        // the big "again" over the board
        TextView againB = button(act, "🔄 మళ్లీ ఆడదాం", 0xFF2E7D4F);
        againB.setTextSize(24);
        againB.setOnClickListener(v -> again());
        LayoutParams al = new LayoutParams(dp(330), dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        al.bottomMargin = dp(18);
        if (g.far()) againB.setVisibility(GONE);
        area.addView(againB, al);
    }

    @Override public int level() { return gameId == null ? 0 : Games.level(act, gameId); }

    @Override public String her() { return HomeCare.who(act); }

    // ================================================================ GameLink.Listener (a game with Anil on his phone)

    @Override public void farState(String all) {
        Game g = game;
        if (g == null) return;
        touched();
        farWaitSaid = false;
        g.farMoved(all);
        if (!g.done) Games.keep(act, gameId, g.saveAll());
        showScore();
    }

    @Override public void farYes() {
        say(pick("అబ్బాయి వచ్చాడు " + her() + "! ఆట మొదలు.", "అబ్బాయి సరే అన్నాడు! ఇక ఆడదాం " + her() + "."), "excited", "clap");
        if (game != null && link != null) link.send(game.saveAll()); // (his phone gets the board as it is now)
    }

    @Override public void farGone(boolean no) {
        Game g = game;
        if (g == null || g.done) return;
        if (no) {
            say("అబ్బాయి ఇప్పుడు ఆడలేనన్నాడు " + her() + ". పర్వాలేదు, ఈ ఆట నేను ఆడతాను!", "happy", null);
        } else {
            say("అబ్బాయి ఆట నుంచి వెళ్లిపోయాడు " + her() + ". ఆయన బదులు నేను ఆడతాను, కొనసాగిద్దాం!", "happy", null);
        }
        jarvisTakesOver();
    }

    /** Anil can't play (no answer / no / left): Jarvis takes his seat and the game goes on here. */
    private void jarvisTakesOver() {
        Game g = game;
        if (g == null) return;
        if (link != null) { link.stop(); link = null; }
        try {
            org.json.JSONObject j = new org.json.JSONObject(g.saveAll());
            org.json.JSONArray r = j.getJSONArray("roles");
            for (int i = 0; i < r.length(); i++) if ("son".equals(r.getString(i))) r.put(i, "jarvis");
            for (int i = 0; lastWho != null && i < lastWho.length; i++) if ("son".equals(lastWho[i])) lastWho[i] = "jarvis";
            g.loadAll(j.toString());
            Games.keep(act, gameId, g.saveAll());
            showScore();
        } catch (Exception ignored) {}
    }

    private boolean farWaitSaid;

    /** Every minute while a far game is open: no answer to the invite in 5 minutes → Jarvis plays; a long wait → said once. */
    void farTick() {
        Game g = game;
        GameLink l = link;
        if (g == null || l == null || g.done) return;
        if (!l.answered() && l.quietFor() > 5 * 60_000L) {
            l.bye();
            say("అబ్బాయి ఫోన్ చూసినట్టు లేదు " + her() + ", డ్యూటీలో బిజీ అనుకుంటా. ఈ ఆట నేను ఆడతాను!", "happy", null);
            jarvisTakesOver();
        } else if (l.answered() && g.kind(g.turn()) == Game.FAR && l.quietFor() > 10 * 60_000L && !farWaitSaid) {
            farWaitSaid = true;
            say("అబ్బాయి ఇంకా పెట్టలేదు " + her() + ". ఆయన వచ్చేదాకా ఆగుదాం. కావాలంటే ✕ నొక్కండి, ఆట దాచి ఉంచుతాను.", "caring", null);
        }
    }

    // ================================================================ rest, idle, closing

    /** 45 minutes of play: water and rest for the eyes; at night after 20 minutes: time to sleep. */
    private void restCheck(long now) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean night = h >= 22 || h < 5;
        long limit = night ? 20 * 60_000L : 45 * 60_000L;
        if (now - playingSince < limit || now - restSaidAt < limit) return;
        restSaidAt = now;
        final String line = night ? her() + ", రాత్రి అయింది. ఆట ఇక్కడే ఆపి, రేపు మళ్లీ ఆడదాం. మన ఆట అలాగే ఉంటుంది, పడుకుందామా?"
                : her() + ", చాలాసేపు ఆడాం! కొంచెం నీళ్లు తాగి, కళ్లకు కాసేపు విశ్రాంతి ఇవ్వండి. తర్వాత కొనసాగిద్దాం.";
        main.postDelayed(() -> say(line, "caring", null), 2500);
    }

    void touched() { touchedAt = System.currentTimeMillis(); }

    /** No touch / word for 15 minutes: the panel closes by itself (the game is kept to go on with). */
    boolean idleTooLong() { return System.currentTimeMillis() - touchedAt > 15 * 60_000L && (game == null || game.kind(game.turn()) != Game.FAR); }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) touched();
        return super.dispatchTouchEvent(e);
    }

    private void endGame() {
        if (game != null) game.stop();
        game = null;
        if (link != null) { link.stop(); link = null; }
        main.removeCallbacksAndMessages(null);
    }

    /** Closes the panel (the unfinished game stays saved). */
    void close(boolean byButton) {
        Game g = game;
        if (g != null && !g.done && g.far() && link != null) link.bye();
        endGame();
        gameId = null;
        area.removeAllViews();
        if (shown == this) shown = null;
        thinking(false);
        outer.gamesClosed();
        if (byButton && g != null && !g.done) say("సరే " + her() + ", ఆట దాచి పెట్టాను. 🎲 నొక్కితే అక్కడి నుంచే ఆడొచ్చు.", null, null);
    }

    Game game() { return game; }
    String gameId() { return gameId; }

    // ================================================================ small helpers

    private String pick(String... s) { return s[(int) (Math.random() * s.length)]; }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(Context c, String s, int bg) {
        TextView b = text(c, s, 19, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, 0, 16));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        return b;
    }

    private GradientDrawable round(int fill, int stroke, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(r));
        if (stroke != 0) d.setStroke(dp(2), stroke);
        return d;
    }
}
