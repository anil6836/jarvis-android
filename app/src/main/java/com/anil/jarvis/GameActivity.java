package com.anil.jarvis;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * On Anil's phone: a game with అమ్మగారు at the home tablet (the same board as hers). He starts it from Settings →
 * 🏠 ఇంటి టాబ్లెట్ → "🎲 అమ్మగారితో ఆట" (she is asked on the tablet), or opens her invite from the notification.
 * Her moves come every few seconds through his Telegram bot (GameLink); a notification tells him when it's his turn
 * while he is elsewhere. Nothing is spoken here: what Jarvis says to her is shown as a line on the screen.
 */
public final class GameActivity extends Activity implements Game.Host, GameLink.Listener {
    static final String EXTRA_GAME = "game", EXTRA_OPTION = "option", EXTRA_INVITE = "invite";
    private static final int NOTE = 271;

    private Game game;
    private GameLink link;
    private TextView status, line, waiting;
    private boolean resumed, started;

    /** His phone: start a new game with her (she is asked on the tablet). */
    static void invite(Context c, String id, int option) {
        c.startActivity(new Intent(c, GameActivity.class).putExtra(EXTRA_GAME, id).putExtra(EXTRA_OPTION, option)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Game.onPhone = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1A2C);
        root.setPadding(dp(12), dp(16), dp(12), dp(12));
        setContentView(root);

        TextView title = text("🎲 అమ్మగారితో ఆట", 22, 0xFFFFFFFF, true);
        root.addView(title);
        status = text("", 19, 0xFFFFD166, true);
        status.setPadding(0, dp(4), 0, dp(6));
        root.addView(status);

        FrameLayout boardBox = new FrameLayout(this);
        root.addView(boardBox, new LinearLayout.LayoutParams(-1, 0, 1));

        line = text("", 16, 0xFFDCE7F3, false);
        line.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1B3050);
        g.setCornerRadius(dp(14));
        line.setBackground(g);
        line.setVisibility(View.GONE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(8);
        root.addView(line, ll);

        LinearLayout buttons = new LinearLayout(this);
        TextView hint = button("💡 సలహా");
        hint.setOnClickListener(v -> { if (game != null && !game.done && game.kind(game.turn()) == Game.HERE && !game.hint()) say("ఇప్పుడు సలహా లేదు.", null, null); });
        TextView leave = button("✕ ఆట వదిలేయి");
        leave.setOnClickListener(v -> finish());
        buttons.addView(hint, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams lv = new LinearLayout.LayoutParams(0, dp(52), 1);
        lv.leftMargin = dp(8);
        buttons.addView(leave, lv);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(8);
        root.addView(buttons, bl);

        if (!GameLink.ready(this)) {
            status.setText("ముందు ఈ ఫోన్‌ని Telegram bot తో కలపండి (సెట్టింగ్స్ → 📮 ఫోన్ ↔ టాబ్లెట్ లింక్), నెట్ ఆన్ చేయండి.");
            return;
        }
        Intent in = getIntent();
        String inv = in.getStringExtra(EXTRA_INVITE);
        try {
            if (inv != null) {
                JSONObject j = new JSONObject(inv);
                String id = j.optString("g");
                game = Games.make(this, id);
                if (game == null) { status.setText("ఈ ఆట ఈ వెర్షన్‌లో లేదు. Jarvis ని అప్‌డేట్ చేయండి."); return; }
                title.setText(Games.emoji(id) + " అమ్మగారితో " + Games.name(id));
                game.attach(this);
                boardBox.addView(game, new FrameLayout.LayoutParams(-1, -1));
                link = new GameLink(this, this);
                link.join(j.optString("gid"), id);
                if (!game.loadAll(j.optString("all"))) { status.setText("ఆట తెరవలేకపోయాను."); return; }
                started = true;
                say("అమ్మగారి ఆటలో చేరారు! " + (game.kind(game.turn()) == Game.HERE ? "మీ వంతు." : "అమ్మగారి వంతు."), null, null);
                cancelNote(this);
            } else {
                String id = in.getStringExtra(EXTRA_GAME);
                int opt = in.getIntExtra(EXTRA_OPTION, 0);
                game = id == null ? null : Games.make(this, id);
                if (game == null) { finish(); return; }
                title.setText(Games.emoji(id) + " అమ్మగారితో " + Games.name(id));
                game.attach(this);
                boardBox.addView(game, new FrameLayout.LayoutParams(-1, -1));
                link = new GameLink(this, this);
                game.newGame(new String[]{"son", "her"}, opt);
                link.invite(id, opt, game.saveAll());
                waiting = text("అమ్మగారిని అడిగాను… టాబ్లెట్‌లో Jarvis ఆమెను అడుగుతాడు (సుమారు 20 సెకన్లు).", 19, 0xFFFFFFFF, true);
                waiting.setGravity(Gravity.CENTER);
                waiting.setBackgroundColor(0xCC0E1A2C);
                waiting.setPadding(dp(20), 0, dp(20), 0);
                waiting.setClickable(true);
                boardBox.addView(waiting, new FrameLayout.LayoutParams(-1, -1));
            }
        } catch (Exception e) {
            status.setText("ఆట తెరవలేకపోయాను.");
        }
    }

    // ================================================================ Game.Host (nothing spoken on his phone)

    @Override public void say(String text, String feeling, String gesture) {
        if (text == null || text.trim().isEmpty()) return;
        line.setText(text.trim());
        line.setVisibility(View.VISIBLE);
    }

    @Override public void status(String text) { status.setText(text); }
    @Override public void thinking(boolean on) {}

    @Override public void moved() {
        if (game != null && link != null && started) link.send(game.saveAll());
    }

    @Override public void over(int win) {
        if (game == null) return;
        status.setText(win < 0 ? "సమానం! 🤝" : game.kind(win) == Game.HERE ? "మీరు గెలిచారు! 🎉" : "అమ్మగారు గెలిచారు! 🎉");
    }

    @Override public int level() { return 1; }
    @Override public String her() { return "అమ్మగారు"; }

    // ================================================================ GameLink.Listener

    @Override public void farState(String all) {
        if (game == null) return;
        if (!started) farYes();
        game.farMoved(all);
        buzz();
        if (!resumed && !game.done && game.kind(game.turn()) == Game.HERE) note(this, "♟️ అమ్మగారు పెట్టారు, మీ వంతు", null);
        if (!resumed && game.done) note(this, "🎲 అమ్మగారితో ఆట అయిపోయింది", null);
    }

    @Override public void farYes() {
        if (started) return;
        started = true;
        if (waiting != null) waiting.setVisibility(View.GONE);
        say("అమ్మగారు సరే అన్నారు! ఆట మొదలు.", null, null);
        buzz();
        if (game != null) { link.send(game.saveAll()); game.turnNow(); }
        if (!resumed) note(this, "🎲 అమ్మగారు ఆటకి సరే అన్నారు!", null);
    }

    @Override public void farGone(boolean no) {
        if (waiting != null) { waiting.setVisibility(View.VISIBLE); waiting.setText(no ? "అమ్మగారు ఇప్పుడు ఆడలేనన్నారు. తర్వాత ఆడొచ్చు." : "ఆట ఆగింది (టాబ్లెట్ / నెట్)."); }
        else status.setText(no ? "అమ్మగారు ఇప్పుడు ఆడలేనన్నారు." : "అమ్మగారి వైపు ఆట ఆగింది (Jarvis ఆమెతో కొనసాగిస్తాడు).");
        if (link != null) link.stop();
        link = null;
    }

    // ================================================================ life

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        cancelNote(this);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (link != null) {
            if (game != null && !game.done) link.bye();
            link.stop();
        }
        if (game != null) game.stop();
    }

    private void buzz() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    /** A notification that brings the game back (her move while he is elsewhere / her invite). */
    static void note(Context c, String text, String invite) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_game", "ఆటలు", NotificationManager.IMPORTANCE_HIGH));
            Intent i = new Intent(c, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (invite != null) i.putExtra(EXTRA_INVITE, invite).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, NOTE, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_game").setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(text).setContentText("నొక్కి ఆట తెరవండి").setContentIntent(pi).setAutoCancel(true)
                    .setTimeoutAfter(30 * 60_000L).build());
        } catch (Exception ignored) {}
    }

    static void cancelNote(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
    }

    /** HomeLink on his phone: the bot's pinned message is her invite → a notification to join. */
    static void inviteNote(Context c, JSONObject inv) {
        note(c, "🎲 అమ్మగారు " + Games.name(inv.optString("g")) + " ఆడదాం అంటున్నారు!", inv.toString());
    }

    // ================================================================ small helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s) {
        TextView b = text(s, 17, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1F3B5C);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        return b;
    }
}
