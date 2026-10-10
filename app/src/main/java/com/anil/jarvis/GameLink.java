package com.anil.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * A game between అమ్మగారు at the home tablet and Anil on his phone, through his own Telegram bot (the same mailbox as
 * HomeLink: each side writes a quiet message into his chat with the bot, pins it, and the other reads the pinned one).
 * Each move sends the whole game (small JSON); the side's older message is deleted so his chat doesn't fill up.
 * While a far game is open both sides look every 4 seconds.
 *
 *   "🎲 ఆట: {gid, ev, by, seq, g, all}"  ev: invite (a new game, with the board), yes / no (the answer), st (a move),
 *   bye (left the game), over (finished). by: home / phone. Only the bot's own messages count.
 */
final class GameLink {
    static final String TAG = "🎲 ఆట: ";

    /** What the game screen (the tablet's panel, or GameActivity on his phone) hears from the far side. */
    interface Listener {
        /** The far player's move (the whole game). */
        void farState(String all);
        /** The far side said yes to the invite. */
        void farYes();
        /** The far side said no / left the game. */
        void farGone(boolean no);
    }

    private final Context c;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String me;
    private String gid = "", gameId = "";
    private int seq;
    private long myMsg = -1, theirSeq = -1, sentAt, heardAt, coveredAt;
    private volatile boolean running;
    private volatile String pendingAll;
    private boolean answered;

    GameLink(Context c, Listener l) {
        this.c = c.getApplicationContext();
        this.l = l;
        this.me = Game.onPhone ? "phone" : "home";
    }

    /** The bot is set up on this device (token + his chat) and the internet is on. */
    static boolean ready(Context c) { return !Guard.token(c).isEmpty() && !Guard.chat(c).isEmpty() && Net.online(c); }

    // ================================================================ starting

    /** A new far game: the invite (with the board) goes out loud (his Telegram rings once), then the moves quietly. */
    void invite(String id, int option, String all) {
        gameId = id;
        gid = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        answered = false;
        sendAsync("invite", all, !Game.onPhone);
        startPolling();
    }

    /** She / he said yes to the other side's invite: join it. */
    void join(String gid, String id) {
        this.gid = gid;
        this.gameId = id;
        answered = true;
        sendAsync("yes", null, false);
        startPolling();
    }

    /** No to the other side's invite (nothing else follows). */
    static void decline(Context c, String gid, String id) {
        final Context a = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject().put("gid", gid).put("ev", "no").put("by", Game.onPhone ? "phone" : "home").put("seq", 0).put("g", id);
                long m = Guard.sendQuiet(a, TAG + j);
                if (m > 0) Guard.pin(a, m);
            } catch (Exception ignored) {}
        }, "game-no").start();
    }

    /** A saved far game opened again: the board is sent again and the other side is looked for. */
    void resume(String id, String all) {
        gameId = id;
        String old = Games.sp(c).getString("far_gid", "");
        gid = old.isEmpty() ? Long.toString(System.currentTimeMillis(), 36) : old;
        seq = Games.sp(c).getInt("far_seq", 0);
        answered = true;
        sendAsync("st", all, false);
        startPolling();
    }

    // ================================================================ during the game

    /** After every move here: the whole game to the other side. */
    void send(String all) { sendAsync("st", all, false); }

    void over() { /* the last state was sent by send(); the far side sees the end in it */ }

    /** Left the game (the panel closed / the activity finished). */
    void bye() { sendAsync("bye", null, false); }

    void stop() { running = false; main.removeCallbacksAndMessages(null); }

    private final Object sendLock = new Object();

    private void sendAsync(String ev, String all, boolean loud) {
        final int s = ++seq;
        Games.sp(c).edit().putString("far_gid", gid).putInt("far_seq", seq).apply();
        if ("st".equals(ev)) pendingAll = all;
        new Thread(() -> {
            synchronized (sendLock) {
                try {
                    if ("st".equals(ev) && s < seq && pendingAll != all) return; // (a newer move is going out anyway)
                    JSONObject j = new JSONObject().put("gid", gid).put("ev", ev).put("by", me).put("seq", s).put("g", gameId);
                    if (all != null) j.put("all", all);
                    String text = TAG + j;
                    long id = loud ? Guard.sendLoud(c, text) : Guard.sendQuiet(c, text);
                    if (id < 0) { id = loud ? Guard.sendLoud(c, text) : Guard.sendQuiet(c, text); } // (one more try)
                    if (id < 0) { main.post(() -> { if (running) l.farGone(false); }); return; }
                    Guard.pin(c, id);
                    long old = myMsg;
                    myMsg = id;
                    sentAt = System.currentTimeMillis();
                    if (old > 0 && !"invite".equals(ev)) Guard.delete(c, old); // (his chat keeps only the newest move)
                } catch (Exception ignored) {}
            }
        }, "game-send").start();
    }

    // ================================================================ looking for the other side

    private void startPolling() {
        if (running) return;
        running = true;
        heardAt = System.currentTimeMillis();
        new Thread(() -> {
            while (running) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
                if (!running) break;
                try { poll(); } catch (Exception ignored) {}
            }
        }, "game-poll").start();
    }

    private void poll() throws Exception {
        JSONObject m = Guard.pinned(c);
        if (m == null) return;
        JSONObject from = m.optJSONObject("from");
        String text = m.optString("text");
        if (from == null || !from.optBoolean("is_bot") || !text.startsWith(TAG)) {
            // something else (a status, a photo) covered our mailbox: after 20 s (time for its own reader), our latest
            // message is pinned again (no new message)
            long now = System.currentTimeMillis();
            if (coveredAt == 0) coveredAt = now;
            if (myMsg > 0 && now - coveredAt > 20_000L) { Guard.pin(c, myMsg); coveredAt = 0; }
            return;
        }
        coveredAt = 0;
        JSONObject j = new JSONObject(text.substring(TAG.length()));
        if (!gid.equals(j.optString("gid")) || me.equals(j.optString("by"))) return;
        int s = j.optInt("seq");
        if (s <= theirSeq) return;
        theirSeq = s;
        heardAt = System.currentTimeMillis();
        String ev = j.optString("ev");
        final String all = j.optString("all", null);
        main.post(() -> {
            if (!running) return;
            switch (ev) {
                case "yes": answered = true; l.farYes(); break;
                case "no": running = false; l.farGone(true); break;
                case "bye": running = false; l.farGone(false); break;
                case "st": case "invite": if (all != null) l.farState(all); break;
                default: break;
            }
        });
    }

    boolean answered() { return answered; }
    long quietFor() { return System.currentTimeMillis() - heardAt; }

    // ================================================================ the invite, read from the pinned message

    /** A far side's invite in this pinned message (for this device), or null: {gid, g, all, by}. */
    static JSONObject inviteIn(JSONObject pinned, boolean forPhone) {
        try {
            if (pinned == null) return null;
            JSONObject from = pinned.optJSONObject("from");
            String text = pinned.optString("text");
            if (from == null || !from.optBoolean("is_bot") || !text.startsWith(TAG)) return null;
            if (System.currentTimeMillis() / 1000 - pinned.optLong("date") > 30 * 60) return null; // (an old invite)
            JSONObject j = new JSONObject(text.substring(TAG.length()));
            if (!"invite".equals(j.optString("ev"))) return null;
            if (!(forPhone ? "home" : "phone").equals(j.optString("by"))) return null;
            if (!Games.isBoard(j.optString("g"))) return null;
            return j;
        } catch (Exception e) {
            return null;
        }
    }
}
