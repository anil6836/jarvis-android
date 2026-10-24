package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 🚗 C1: the car Jarvis's own screen on the car's display (the Android box's screen, landscape). Three columns: Jarvis
 * himself in a glowing window ({@link CarPortal}: the home tablet's young man, with a seat belt while the car moves, who
 * dances when a song plays — Anil's wish, the passengers watch), the speed gauge with depth ({@link CarArt.Gauge}),
 * the clock and small facts, and six big glass buttons with thin lit icons — 🎤 మాట్లాడు, 🧭 దారి, 🎵 పాటలు, ⛽ డీజిల్,
 * 🏠 ఇల్లు, 😴 నిద్ర. Ten themes ({@link CarTheme}); the mode (driving / a song / parked) colours the screen. While the
 * car moves the words are one short line (the rest is spoken), Settings and typing wait for a stop (C9). The sleep
 * alert's question comes over everything with one giant ✅ (C4). Every size follows the screen (any box, any display).
 */
final class CarScreen extends FrameLayout {

    interface Host {
        /** 🎤: Jarvis listens. */
        void talk();
        /** Jarvis says this and listens for the answer (⛽, "ఎక్కడికి?"). */
        void ask(String text);
        /** Jarvis says this (a button's result). */
        void speak(String text);
        /** A request to Jarvis's brain as if he had said it (label: what the chat shows); the answer is spoken. */
        void request(String prompt, String label);
        void openSettings();
        void openChat();
    }

    private static final String[] DAYS = {"ఆదివారం", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం"};
    private static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు",
            "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};
    private static final String SP = "car_screen";
    private static final long PARKED_AFTER_MS = 90_000;
    private static final int SONG_IN = 4, SONG_OUT = 4; // (seconds in a row before a song counts as begun / ended)

    private final Activity act;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final CarArt.Backdrop backdrop;
    private final LinearLayout content, leftCol, midCol, rightCol, tileGrid, chipRow;
    private final CarPortal portal;
    private final CarArt.Gauge gauge;
    private final TextView clock, date, minis, stateText, subText, chips, gear, chat, driveChip, road1, road2, roadYes, roadNo, musicText, board;
    private final LinearLayout roadCard, musicStrip;
    private final CarArt.Icon roadIcon, prevKey, playKey, nextKey;
    private final List<View> tiles = new ArrayList<>();
    private final List<CarArt.Icon> tileIcons = new ArrayList<>();
    private final FrameLayout sheet, ask, awake;
    private int W, H;
    private boolean night, running, wasMoving, dancing;
    private int state = OrbView.IDLE, mode = CarTheme.DRIVING;
    private String said = "", heard = "";
    private long saidAt;
    private CarTheme.Look look;
    private final CarBeat beat;
    private int danceStyle = -1;
    private boolean songNow, beatOn;
    private int ticks;
    private int songOn, songOff;
    private long idleSince;

    // the palette in use (kept as fields: the sheets and buttons read them)
    private int tileBg, textMain, textSoft, accent;

    CarScreen(Activity a, Prefs prefs, Host host) {
        super(a);
        this.act = a;
        this.prefs = prefs;
        this.host = host;
        setClickable(true); // (taps never fall through to the chat under it)
        backdrop = new CarArt.Backdrop(a);
        addView(backdrop, new LayoutParams(-1, -1));
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.HORIZONTAL);
        addView(content, new LayoutParams(-1, -1));

        // ---- left: Jarvis's window, what he is doing, the last words, small chips
        leftCol = new LinearLayout(a);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(leftCol, new LinearLayout.LayoutParams(0, -1, 27));
        portal = new CarPortal(a);
        portal.setOnClickListener(v -> tap(this::portalTap));
        leftCol.addView(portal, new LinearLayout.LayoutParams(-1, 0, 58));
        stateText = autoText(a, "", true, 34);
        stateText.setGravity(Gravity.CENTER);
        leftCol.addView(stateText, new LinearLayout.LayoutParams(-1, 0, 9));
        subText = new TextView(a);
        subText.setGravity(Gravity.CENTER);
        subText.setEllipsize(TextUtils.TruncateAt.END);
        subText.setOnClickListener(v -> tap(host::talk));
        leftCol.addView(subText, new LinearLayout.LayoutParams(-1, 0, 22));
        chipRow = new LinearLayout(a);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        leftCol.addView(chipRow, new LinearLayout.LayoutParams(-1, 0, 11));
        chips = autoText(a, "", false, 26);
        chipRow.addView(chips, new LinearLayout.LayoutParams(0, -1, 70));
        chat = autoText(a, "💬", false, 36);
        chat.setGravity(Gravity.CENTER);
        chat.setOnClickListener(v -> tap(() -> { if (parkedOrSay()) host.openChat(); }));
        chipRow.addView(chat, new LinearLayout.LayoutParams(0, -1, 15));
        gear = autoText(a, "⚙️", false, 36);
        gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v -> tap(() -> { if (parkedOrSay()) host.openSettings(); }));
        chipRow.addView(gear, new LinearLayout.LayoutParams(0, -1, 15));

        // ---- middle: the gauge, the clock, small facts (weather, diesel), later the car and the road's cards
        midCol = new LinearLayout(a);
        midCol.setOrientation(LinearLayout.VERTICAL);
        midCol.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(midCol, new LinearLayout.LayoutParams(0, -1, 30));
        gauge = new CarArt.Gauge(a);
        midCol.addView(gauge, new LinearLayout.LayoutParams(-1, 0, 56));
        clock = autoText(a, "", true, 70);
        clock.setGravity(Gravity.CENTER);
        midCol.addView(clock, new LinearLayout.LayoutParams(-1, 0, 16));
        date = autoText(a, "", false, 30);
        date.setGravity(Gravity.CENTER);
        midCol.addView(date, new LinearLayout.LayoutParams(-1, 0, 8));
        minis = autoText(a, "", false, 28);
        minis.setGravity(Gravity.CENTER);
        midCol.addView(minis, new LinearLayout.LayoutParams(-1, 0, 9));
        driveChip = autoText(a, "", true, 30); // ⏱ how long he has driven (amber near 2 hours: the sleep alert's long-drive clock)
        driveChip.setGravity(Gravity.CENTER);
        midCol.addView(driveChip, new LinearLayout.LayoutParams(-1, 0, 11));
        board = new TextView(a); // 🎲 parked, a game on: the question big, for the passengers (the gauge says 0 anyway)
        board.setGravity(Gravity.CENTER);
        board.setEllipsize(TextUtils.TruncateAt.END);
        board.setVisibility(GONE);
        board.setOnClickListener(v -> tap(host::talk));
        midCol.addView(board, new LinearLayout.LayoutParams(-1, 0, 100));

        // ---- right: the six big buttons (glass, thin lit icons)
        rightCol = new LinearLayout(a);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        content.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 43));
        tileGrid = new LinearLayout(a);
        tileGrid.setOrientation(LinearLayout.VERTICAL);
        // the road card on top: the maps app's next turn, the weather ahead, coming home (and the AC question), the road
        roadCard = new LinearLayout(a);
        roadCard.setOrientation(LinearLayout.HORIZONTAL);
        roadCard.setGravity(Gravity.CENTER_VERTICAL);
        roadIcon = new CarArt.Icon(a, CarArt.Icon.ROAD);
        roadCard.addView(roadIcon, new LinearLayout.LayoutParams(0, -1, 20));
        LinearLayout roadText = new LinearLayout(a);
        roadText.setOrientation(LinearLayout.VERTICAL);
        roadCard.addView(roadText, new LinearLayout.LayoutParams(0, -1, 80));
        road1 = autoText(a, "", true, 46);
        road1.setGravity(Gravity.CENTER_VERTICAL);
        roadText.addView(road1, new LinearLayout.LayoutParams(-1, 0, 58));
        road2 = autoText(a, "", false, 30);
        road2.setGravity(Gravity.CENTER_VERTICAL);
        roadText.addView(road2, new LinearLayout.LayoutParams(-1, 0, 42));
        roadYes = autoText(a, "✓ ఆన్", true, 40);
        roadNo = autoText(a, "✕", true, 40);
        for (TextView b : new TextView[]{roadYes, roadNo}) {
            b.setGravity(Gravity.CENTER);
            b.setVisibility(GONE);
            roadCard.addView(b, new LinearLayout.LayoutParams(0, -1, b == roadYes ? 30 : 18));
        }
        roadYes.setOnClickListener(v -> tap(() -> { if (acAt > 0) acYes(); else showGames(); }));
        roadNo.setOnClickListener(v -> tap(() -> { acAt = 0; roadKey = ""; roadCheck(); }));
        roadCard.setOnClickListener(v -> tap(() -> { if (Drive.navState() != null) openMaps(getContext()); }));
        rightCol.addView(roadCard, new LinearLayout.LayoutParams(-1, 0, 25));
        rightCol.addView(tileGrid, new LinearLayout.LayoutParams(-1, 0, 57));
        Object[][] t = {{CarArt.Icon.MIC, "మాట్లాడు", "talk"}, {CarArt.Icon.WAY, "దారి", "way"}, {CarArt.Icon.MUSIC, "పాటలు", "music"},
                {CarArt.Icon.FUEL, "డీజిల్", "fuel"}, {CarArt.Icon.HOME, "ఇల్లు", "home"}, {CarArt.Icon.SLEEP, "నిద్ర", "sleep"}};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            tileGrid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            for (int i = 0; i < 3; i++) {
                Object[] x = t[r * 3 + i];
                View tile = tile(a, (Integer) x[0], (String) x[1]);
                final String what = (String) x[2];
                tile.setOnClickListener(v -> tap(() -> button(what)));
                row.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
                tiles.add(tile);
            }
        }

        // the song strip at the bottom: its name and ⏮ ⏯ ⏭ (the music app's own keys)
        musicStrip = new LinearLayout(a);
        musicStrip.setOrientation(LinearLayout.HORIZONTAL);
        musicStrip.setGravity(Gravity.CENTER_VERTICAL);
        musicText = autoText(a, "", false, 32);
        musicText.setGravity(Gravity.CENTER_VERTICAL);
        musicText.setOnClickListener(v -> tap(this::showMusic));
        musicStrip.addView(musicText, new LinearLayout.LayoutParams(0, -1, 52));
        prevKey = new CarArt.Icon(a, CarArt.Icon.PREV);
        playKey = new CarArt.Icon(a, CarArt.Icon.PLAY);
        nextKey = new CarArt.Icon(a, CarArt.Icon.NEXT);
        String[] keyAct = {"⏮", "⏯", "⏭"};
        CarArt.Icon[] keys = {prevKey, playKey, nextKey};
        for (int i = 0; i < 3; i++) {
            final String k = keyAct[i];
            keys[i].setClickable(true);
            keys[i].setOnClickListener(v -> tap(() -> { musicKey(k); main.postDelayed(this::musicCheck, 700); }));
            musicStrip.addView(keys[i], new LinearLayout.LayoutParams(0, -1, 16));
        }
        rightCol.addView(musicStrip, new LinearLayout.LayoutParams(-1, 0, 18));

        // ---- over everything: a sheet of choices, a yes / no question, the sleep alert's ✅
        sheet = overlay(a);
        ask = overlay(a);
        awake = overlay(a);
        addView(sheet, new LayoutParams(-1, -1));
        addView(ask, new LayoutParams(-1, -1));
        addView(awake, new LayoutParams(-1, -1));

        beat = new CarBeat(per -> { if (dancing) { portal.body.rig.setBeat(per); portal.beat(); } });
        portal.body.rig.setLook(prefs.bodyLook());
        portal.body.rig.setSkin(prefs.bodySkin());
        portal.body.rig.setIdlePlays(false); // (the car: no kitten / bubbles on the road; the parked plays come later)
        night = nightNow();
        palette();
        update();
        showTalk();
        roadCheck();
        musicCheck();
        driveCheck();
    }

    // ================================================================ the screen's life

    void start() {
        running = true;
        main.removeCallbacks(quick);
        main.removeCallbacks(slow);
        main.post(quick);
        main.post(slow);
    }

    void stop() {
        running = false;
        main.removeCallbacks(quick);
        main.removeCallbacks(slow);
        setDancing(false);
        if (beatOn) { beatOn = false; beat.stop(); }
    }

    /** Settings were left: the theme, his colour, the dance choice, Jarvis's look may have changed. */
    void themeChanged() {
        portal.body.rig.setLook(prefs.bodyLook());
        portal.body.rig.setSkin(prefs.bodySkin());
        palette();
        showTalk();
    }

    /** Every second: the speed, the sleep alert's question, moving / parked, the song and the dance. */
    private final Runnable quick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            speed();
            awakeCheck();
            boolean m = CarBox.moving();
            if (m != wasMoving) {
                long now = System.currentTimeMillis();
                if (m && (stillSince == 0 || now - stillSince > 5 * 60_000L)) portal.body.rig.greet(); // 👋 off we go
                if (!m) stillSince = now;
                wasMoving = m; showTalk(); gear.setAlpha(m ? 0.35f : 1f); chat.setAlpha(m ? 0.35f : 1f);
            }
            glances();
            portal.setBelted(m);
            tripCount();
            modeCheck();
            roadCheck();
            boardCheck();
            if (++ticks % 2 == 0) musicCheck();
            if (ticks % 10 == 1) driveCheck();
            main.postDelayed(this, 1000);
        }
    };

    /** Every 20 seconds: the clock, the day / night colours, the chips, the weather (every half hour). */
    private final Runnable slow = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            main.postDelayed(this, 20_000);
        }
    };

    private void update() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        clock.setText(String.format(Locale.ROOT, "%d:%02d", h12, m));
        date.setText(HomeScreen.partOfDay(h) + " · " + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]);
        boolean n = nightNow();
        if (n != night) { night = n; palette(); }
        StringBuilder b = new StringBuilder(Net.online(getContext()) ? "📶 ✓" : "📶 నెట్ లేదు");
        b.append("  ").append(CarBox.linkLine(getContext()));
        try { if (CarCare.on(getContext()) && CarCare.enabled(getContext())) b.append("  😴 ✓"); } catch (Exception ignored) {}
        String wait = CarLink.waitingLine(getContext());
        if (!wait.isEmpty()) b.append("  ").append(wait);
        chips.setText(b.toString());
        showWeather();
        fetchWeather();
        CarBox.keep(getContext());
        occasion();
        if (state == OrbView.IDLE) showTalk(); // (an old answer gives way to the hint)
    }

    private static boolean nightNow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6;
    }

    // ================================================================ the mode: driving, a song, parked — and the dance

    /**
     * Once a second. A song is "on" only after it was heard {@link #SONG_IN} seconds in a row, and "off" only after
     * {@link #SONG_OUT} quiet seconds: a map direction, a buffering pause between two songs or Jarvis's own voice never
     * recolours the screen or starts a dance. Sound on the box (no music app session) counts only while Jarvis has been
     * quiet for a moment.
     */
    private void modeCheck() {
        Context c = getContext();
        long now = System.currentTimeMillis();
        boolean quiet = state == OrbView.IDLE && now - idleSince > 1500 && now - saidAt > 1500;
        boolean heard = CarBeat.songPlaying(c, quiet);
        if (heard) { songOn++; songOff = 0; } else { songOff++; songOn = 0; }
        if (!songNow && songOn >= SONG_IN) songNow = true;
        else if (songNow && songOff >= SONG_OUT) songNow = false;
        boolean moving = CarBox.moving();
        boolean parked = !moving && CarBox.speed() >= 0 && now - DriveService.lastMovingAt > PARKED_AFTER_MS;
        int m = songNow ? CarTheme.MUSIC : parked ? CarTheme.PARKED : CarTheme.DRIVING;
        if (m != mode) { int was = mode; mode = m; palette(); showTalk(); modeChanged(was, m); }
        String pref = prefs.carDance();
        boolean mayDance = songNow && !"off".equals(pref);
        if (mayDance != beatOn) { beatOn = mayDance; if (mayDance) beat.start(); else beat.stop(); } // (the beat is followed for the whole song, not switched at every signal)
        boolean dance = mayDance && ("always".equals(pref) || !moving) && !(prefs.carLowMotion() && moving);
        setDancing(dance);
    }

    private void setDancing(boolean on) {
        if (on == dancing) return;
        dancing = on;
        BodyRig rig = portal.body.rig;
        rig.setIdlePlays(mode == CarTheme.PARKED && !on); // (his own plays wait while he dances)
        if (on) {
            // a new dance: another style than the last one; the slow sway only to a slow song
            int pick;
            if (beat.period() > 0.75f && danceStyle != IdlePlay.D_SLOW) pick = IdlePlay.D_SLOW;
            else { java.util.Random rnd = new java.util.Random(); do { pick = rnd.nextInt(IdlePlay.D_STYLES); } while (pick == IdlePlay.D_SLOW || pick == danceStyle); }
            danceStyle = pick;
            rig.setDanceStyle(danceStyle);
            rig.setBeat(beat.period());
            rig.setDance(true);
            rig.poke();
        } else {
            rig.setDance(false);
        }
        portal.body.setSlow(night && !dancing); // (the night's slower frames would make the dance choppy)
        showTalk();
    }

    // ================================================================ speed

    private void speed() {
        int s = CarBox.speed();
        int lim = s < 0 ? 0 : DriveService.limitNowKmh;
        boolean over = s >= 0 && lim > 0 && s > lim + 5;
        boolean hot = ObdWatch.live() && ObdWatch.coolant != Integer.MIN_VALUE && ObdWatch.coolant >= 105;
        gauge.set(s, lim, over, hot, ObdWatch.chip()); // 🔌 the engine's heat / battery under the speed (C11)
        long now = System.currentTimeMillis();
        if (over) { // 😟 once per stretch over the limit: a worried face, a look at the gauge beside him
            underSince = 0;
            if (!overShown) { overShown = true; BodyRig rig = portal.body.rig; rig.show("worried", 3000); rig.glance(1f, 0.35f, 2600); }
        } else if (overShown) {
            if (underSince == 0) underSince = now;
            else if (now - underSince > 30_000) overShown = false;
        }
    }

    // ================================================================ what Jarvis is doing

    void setState(int s) {
        if (s == OrbView.IDLE && state != OrbView.IDLE) idleSince = System.currentTimeMillis();
        if (state == OrbView.LISTENING && s != OrbView.LISTENING && gameOn() && heard.matches("(?s).*(ఆట ఆపు|ఆట చాలు|ఆట వద్దు|ఆపేయ్|stop the game).*")) gameAt = 0; // (he / they said stop)
        state = s;
        BodyRig rig = portal.body.rig;
        if (s != BodyRig.IDLE) rig.poke();
        rig.setMode(s);
        portal.setState(s);
        if (s == OrbView.LISTENING) heard = "";
        showTalk();
    }

    void setMic(float level) { portal.body.rig.setMic(level); }

    void setVoice(float level) { portal.body.rig.setVoice(level); portal.voice(level); }

    void setFeeling(String f) { portal.body.rig.setFeeling(f); }

    /** The word Jarvis says now (his lips follow it). */
    void word(String w) { portal.body.rig.word(w); }

    /** His words while he says them. */
    void partial(String text) {
        heard = text == null ? "" : text.trim();
        if (state == OrbView.LISTENING) showTalk();
    }

    /** What Jarvis says (shown with his voice). */
    void say(String text) {
        said = text == null ? "" : text.trim();
        saidAt = System.currentTimeMillis();
        if (gameOn() && mode == CarTheme.PARKED && !CarBox.moving() && CarRoad.praise(said)) portal.body.rig.cheer(); // 👏 a right answer
        showTalk();
        boardCheck();
    }

    private void showTalk() {
        boolean moving = CarBox.moving();
        String st, sub;
        if (state == OrbView.LISTENING) { st = "🎙️ వింటున్నాను…"; sub = heard.isEmpty() ? "" : (moving ? CarBox.shortLine(heard, 50) : heard); }
        else if (state == OrbView.THINKING) { st = "💭 ఆలోచిస్తున్నాను…"; sub = ""; }
        else if (state == OrbView.SPEAKING) { st = "◉ Jarvis చెబుతున్నాడు…"; sub = said.isEmpty() ? "" : moving ? CarBox.shortLine(said, 40) : said; }
        else {
            st = dancing ? "♪ Jarvis డాన్స్ చేస్తున్నాడు" : mode == CarTheme.MUSIC ? "♪ పాట వస్తోంది" : mode == CarTheme.PARKED ? "● ఆగి ఉన్నారు · నన్ను తాకండి 🙂" : "● నడుపుతున్నారు · అంతా బాగుంది";
            if (!said.isEmpty() && System.currentTimeMillis() - saidAt < 30_000) sub = moving ? CarBox.shortLine(said, 40) : said;
            else sub = hint();
        }
        stateText.setText(st);
        subText.setText(sub);
        subText.setMaxLines(moving ? 2 : 3);
    }

    /** How to call Jarvis right now: by name while the wake word's mic is open, else the steering wheel's button / 🎤. */
    private String hint() {
        if (!prefs.hasBrain()) return "మెదడుకి API key కావాలి (⚙️ → Jarvis మెదడు)";
        if (WakeService.hearing) return "'Jarvis' అని పిలవండి, లేదా 🎤 నొక్కండి";
        return "🎙️ స్టీరింగ్ బటన్ నొక్కి అడగండి (లేదా 🎤)";
    }

    // ================================================================ the road card, the song strip, the drive time (P2)

    private final CarRoad.Home homeTrack = new CarRoad.Home();
    private long homeSampleAt, acAt;
    private int homeMin = -1;
    private long lastDriveMin;
    private boolean acOffered;
    private String roadKey = "", musicKeyNow = "";
    private String acUrl, acName;

    /**
     * Once a second, the card on top of the buttons — the most useful one now: the AC question when home is ~10 minutes
     * away (30 s, only on his ✓), the maps app's next turn (its own words; a tap opens the maps app), rain / fog ahead,
     * coming home (a guess from the GPS: shown here only, nothing is sent), else the road and the trip's km. Redrawn
     * only when its words change.
     */
    private void roadCheck() {
        Context c = getContext();
        long now = System.currentTimeMillis();
        if (now - homeSampleAt >= 10_000) {
            homeSampleAt = now;
            Location l = DriveService.last;
            JSONObject h = l == null || now - l.getTime() > 60_000 ? null : Travel.home(c);
            if (h != null && h.has("lat")) {
                double km = MapMath.meters(l.getLatitude(), l.getLongitude(), h.optDouble("lat"), h.optDouble("lon")) / 1000;
                if (km < 0.3) homeTrack.reset(); // (home: the next trip learns its own distances)
                else homeTrack.sample(now, km);
                if (km > 15) acOffered = false; // (far from home again: the next coming home may ask once more)
            }
            homeMin = homeTrack.minutes();
        }
        JSONObject nav = Drive.navState();
        int hm = homeMin;
        int navMin = nav == null ? -1 : CarRoad.minutesIn(nav.optString("trip"));
        if (hm > 0 && navMin >= 0) hm = navMin > hm + 15 ? -1 : navMin; // (the maps app goes past home: not home; else its minutes are better)
        // the AC question: once per coming home, only with his own AC's "on" link, never over the sleep alert's question
        if (!acOffered && hm > 0 && CarRoad.acTime(hm) && acLink() && !awakeShown) { acOffered = true; acAt = now; }
        boolean ac = acAt > 0 && now - acAt < 30_000;
        if (!ac) acAt = 0;
        int icon;
        String l1, l2;
        if (ac) {
            icon = CarArt.Icon.SNOW;
            l1 = "ఇంట్లో " + acName + " ఆన్ చేయనా?";
            l2 = "🏠 ఇంటికి సుమారు " + hm + " ని.";
        } else if (nav != null && !(nav.optString("next").isEmpty() && nav.optString("detail").isEmpty())) {
            String next = nav.optString("next"), det = nav.optString("detail"), trip = nav.optString("trip");
            icon = CarRoad.turnIcon(next + " " + det);
            turnCue(icon, next, det);
            l1 = next.isEmpty() ? det : next;
            String rest = next.isEmpty() ? "" : det;
            l2 = trip.isEmpty() ? rest : rest.isEmpty() ? trip : rest + " · " + trip;
        } else if (mode == CarTheme.PARKED) { // 🅿️ the trip's numbers (no places), and the games for the passengers
            icon = CarArt.Icon.STAR;
            String[] tl = CarRoad.tripLines(DriveService.tripMeters / 1000, movingSecs, tripStartAt == 0 ? 0 : Math.max(0, DriveService.lastMovingAt - tripStartAt) / 60_000L, kmpl());
            if (tl != null) { l1 = tl[0]; l2 = tl[1].isEmpty() ? "పిల్లలకి ఆటలు, క్విజ్, కథ →" : tl[1]; }
            else { icon = CarArt.Icon.PARK; l1 = "🅿️ ఆగి ఉన్నారు"; l2 = "పిల్లలకి ఆటలు, క్విజ్, కథ →"; }
        } else if (!CarAhead.card.isEmpty() && now - CarAhead.cardAt < 20 * 60_000L) {
            icon = CarAhead.cardRain ? CarArt.Icon.RAIN : CarArt.Icon.FOG;
            l1 = CarAhead.card;
            l2 = CarAhead.cardRain ? "నెమ్మదిగా, దూరం పెట్టి, లైట్లు వేసుకోండి" : "ఫాగ్ లైట్లు, నెమ్మదిగా";
        } else if (hm > 0) {
            icon = CarArt.Icon.HOME;
            l1 = "ఇంటికి సుమారు " + hm + " ని.";
            l2 = "సుమారు " + new java.text.SimpleDateFormat("h:mm", Locale.ROOT).format(new java.util.Date(now + hm * 60_000L)) + " కి చేరుకుంటారు";
        } else {
            icon = CarArt.Icon.ROAD;
            String rd = DriveService.roadName == null ? "" : DriveService.roadName.trim();
            l1 = rd.isEmpty() ? "🛣️ శుభ ప్రయాణం" : rd;
            double km = DriveService.tripMeters / 1000;
            l2 = CarBox.speed() < 0 ? "GPS కోసం చూస్తున్నాను…" : km >= 0.5 ? "ఈ ప్రయాణం " + (km < 10 ? String.format(Locale.ROOT, "%.1f", km) : String.valueOf(Math.round(km))) + " కి.మీ" : "";
        }
        boolean games = !ac && mode == CarTheme.PARKED && (nav == null || icon == CarArt.Icon.STAR || icon == CarArt.Icon.PARK);
        String key = icon + "|" + l1 + "|" + l2 + "|" + ac + games;
        if (key.equals(roadKey)) return;
        roadKey = key;
        roadIcon.setKind(icon);
        roadIcon.setColour(ac ? 0xFF7FD8FF : accent);
        road1.setText(l1);
        road2.setText(l2);
        roadYes.setText(ac ? "✓ ఆన్" : "🎲 ఆటలు");
        roadYes.setVisibility(ac || games ? VISIBLE : GONE);
        roadNo.setVisibility(ac ? VISIBLE : GONE);
    }

    // ---- 🙂 P4: Jarvis with the road — he points at the turn, worries over the limit, a thumbs-up for an answered
    // sleep question, points at the diesel button when it runs low, stretches at two hours, waves at the start and the
    // end, dresses for the weather, wears sunglasses on a bright afternoon, keeps the day's festival, and glances at the
    // driver (on the right in an Indian car) or, in a parked game, at the passengers. While moving only the road's own.

    private String cueKey = "";
    private boolean overShown, fuelCued, stretchCued;
    private long underSince, stillSince, glanceAt;
    private String outfitNow = "", occasionNow = "";
    private final java.util.Random rnd = new java.util.Random();

    /** The maps app's turn is near (400 m or less): he points that way and looks, once per turn. */
    private void turnCue(int icon, String next, String det) {
        int dir = CarRoad.pointFor(icon, CarRoad.metersIn(next + " " + det));
        if (dir == 0) return;
        String key = icon + "|" + (next + " " + det).replaceAll("[0-9]+([.,][0-9]+)?\\s*(km|m|ft|మీ|కి\\.?\\s?మీ)?", "").trim();
        if (key.equals(cueKey)) return;
        cueKey = key;
        if (state != OrbView.IDLE) return; // (talking: his hands are his words')
        BodyRig rig = portal.body.rig;
        rig.gesture(dir < 0 ? "point_l" : "point_r", 2600);
        rig.glance(dir < 0 ? -0.95f : 0.95f, 0, 2600);
    }

    private void thumbsUp() {
        BodyRig rig = portal.body.rig;
        rig.gesture("thumb", 2600);
        rig.show("proud", 2600);
    }

    /** Where he looks between words: at the driver while he talks or listens; now and then while moving; the passengers in a game. */
    private void glances() {
        BodyRig rig = portal.body.rig;
        if (state == OrbView.SPEAKING || state == OrbView.LISTENING) { rig.glance(0.55f, 0.05f, 1300); return; }
        long now = System.currentTimeMillis();
        if (state != OrbView.IDLE || dancing || now < glanceAt) return;
        boolean game = gameOn() && mode == CarTheme.PARKED;
        if (CarBox.moving() || game) {
            boolean driver = !game || rnd.nextBoolean();
            rig.glance(driver ? 0.8f : -0.75f, 0.05f, 1400 + rnd.nextInt(1200));
        }
        glanceAt = now + 8000 + rnd.nextInt(8000);
    }

    /** The weather's clothes (the box's own weather): an umbrella in the rain, a sweater when it's cold; 🕶️ on a bright hot afternoon. */
    private void dress(android.content.SharedPreferences sp, long at, long now) {
        boolean fresh = at > 0 && now - at < 3 * 3600_000L;
        int code = fresh ? sp.getInt("w_code", -1) : -1;
        float temp = fresh ? sp.getFloat("w_temp", Float.NaN) : Float.NaN;
        String outfit = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95 ? "umbrella"
                : !Float.isNaN(temp) && temp < 20 ? "sweater" : "none";
        BodyRig rig = portal.body.rig;
        if (!outfit.equals(outfitNow)) { outfitNow = outfit; rig.setOutfit(outfit); }
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        rig.setShades(!night && hr >= 11 && hr < 16 && code >= 0 && code <= 2 && !Float.isNaN(temp) && temp >= 26);
    }

    /** The day's festival (the same days as the home tablet's: New Year, Sankranti, Easter, the Christmas month, a birthday). */
    private void occasion() {
        Calendar c = Calendar.getInstance();
        String o;
        try {
            int mo = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
            o = !HomeScreen.birthdaysToday(getContext(), c).isEmpty() ? "birthday"
                    : mo == Calendar.JANUARY && d == 1 ? "newyear"
                    : mo == Calendar.JANUARY && d >= 13 && d <= 15 ? "sankranti"
                    : HomeScreen.isEaster(c) ? "easter"
                    : mo == Calendar.DECEMBER ? "christmas" : "none";
        } catch (Exception e) { o = "none"; }
        if (!o.equals(occasionNow)) { occasionNow = o; portal.body.rig.setOccasion(o); }
    }

    // ---- 🅿️ P3: parked — the trip's numbers, Jarvis's own little plays, games on the screen for the passengers

    private long tripStartAt, movingSecs, gameAt;
    private double tripMetersSeen;
    private boolean tickleNext;

    /** Once a second: when this trip began (the first move since the drive alerts' count began) and its moving seconds. */
    private void tripCount() {
        double tm = DriveService.tripMeters;
        if (tm + 1 < tripMetersSeen) { tripStartAt = 0; movingSecs = 0; } // (a new count: a new trip)
        tripMetersSeen = tm;
        int sp = CarBox.speed();
        if (sp >= 5) { movingSecs++; if (tripStartAt == 0) tripStartAt = System.currentTimeMillis(); }
    }

    private double kmpl() {
        try { return CarFuel.kmpl(getContext()); } catch (Exception e) { return 0; }
    }

    /** The mode changed: parked — his plays may come (never while moving), a wave at the trip's end. */
    private void modeChanged(int was, int now) {
        BodyRig rig = portal.body.rig;
        boolean parked = now == CarTheme.PARKED;
        rig.setIdlePlays(parked && !dancing);
        rig.setNightPlays(night);
        if (parked && was == CarTheme.DRIVING && DriveService.tripMeters >= 1000) rig.waveBye(); // 👋 the trip is over
        if (!parked) { gameAt = 0; boardCheck(); }
    }

    /** A tap on Jarvis: dancing → a cheer move; parked → he plays (a giggle, a high five); else he listens. */
    private void portalTap() {
        BodyRig rig = portal.body.rig;
        if (dancing) { rig.danceTap(); return; }
        if (mode == CarTheme.PARKED && !CarBox.moving() && state == OrbView.IDLE) {
            if (tickleNext) rig.tickle(); else rig.highFive();
            tickleNext = !tickleNext;
            rig.poke();
            return;
        }
        host.talk();
    }

    /** 🎲 The trip games (any time by voice; parked, the question also shows big in the middle). */
    private void showGames() {
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        String[][] g = {
                {"❓ క్విజ్", "🎲 కారులో అందరికీ ఒక క్విజ్ ఆడదాం: పిల్లలకి కూడా అర్థమయ్యే సులభమైన ప్రశ్నలు, ఒక్కోసారి ఒక్క ప్రశ్న, నాలుగు ఆప్షన్లతో (A, B, C, D). జవాబు చెప్పాక సరైనదో కాదో చెప్పి, తర్వాతి ప్రశ్న అడుగు."},
                {"🧩 పొడుపు కథలు", "🎲 కారులో అందరికీ తెలుగు పొడుపు కథలు అడుగు: ఒక్కోసారి ఒక్కటి. జవాబు చెప్పాక సరైనదో కాదో చెప్పి, తర్వాతిది అడుగు."},
                {"🎬 అంత్యాక్షరి", "🎲 సినిమా పాటల పేర్లతో అంత్యాక్షరి ఆడదాం (పాట పేరు మాత్రమే, పాట పదాలు వద్దు). నువ్వు ఒక పాట పేరుతో మొదలుపెట్టు, దాని చివరి అక్షరంతో మేము చెబుతాం."},
                {"🤔 20 ప్రశ్నలు", "🎲 20 ప్రశ్నల ఆట: నేను ఒక వస్తువు / జంతువు అనుకుంటాను, నువ్వు అవును / కాదు ప్రశ్నలతో కనుక్కో. ఒక్కోసారి ఒక్క ప్రశ్న అడుగు."},
                {"📖 చిన్న కథ", "🎲 పిల్లలకి ఒక చిన్న మంచి కథ చెప్పు (ఒక నిమిషం), చివర్లో ఒక చిన్న నీతి."},
                {"🔢 లెక్కల ఆట", "🎲 పిల్లలకి సులభమైన మనసులో లెక్కల ప్రశ్నలు అడుగు: ఒక్కోసారి ఒక్కటి, జవాబు చెప్పాక సరైనదో కాదో చెప్పి తర్వాతిది అడుగు."}};
        for (String[] x : g) {
            rows.add(new String[]{x[0]});
            final String ask = x[1];
            final String label = x[0];
            acts.add(() -> { closeSheet(); gameAt = System.currentTimeMillis(); boardCheck(); host.request(ask, label); });
        }
        openSheet("🎲 ప్రయాణ ఆటలు", CarBox.moving() ? null : "ఆగి ఉన్నప్పుడు ప్రశ్న మధ్యలో పెద్దగా కనిపిస్తుంది. 'ఆట ఆపు' అంటే ఆగుతుంది.", rows, acts, 2);
    }

    boolean gameOn() { return gameAt > 0 && System.currentTimeMillis() - gameAt < 10 * 60_000L; }

    /** Parked with a game on: the middle shows the question big (the gauge and clock come back the moment the car moves). */
    private void boardCheck() {
        boolean show = gameOn() && mode == CarTheme.PARKED && !CarBox.moving();
        if (show != (board.getVisibility() == VISIBLE)) {
            int v = show ? GONE : VISIBLE;
            for (View x : new View[]{gauge, clock, date, minis, driveChip}) x.setVisibility(v);
            board.setVisibility(show ? VISIBLE : GONE);
        }
        if (!show) return;
        String t = state == OrbView.LISTENING ? "🎙️ " + (heard.isEmpty() ? "మీ జవాబు చెప్పండి…" : heard)
                : state == OrbView.THINKING ? "💭 …" : said.isEmpty() || saidAt < gameAt ? "🎲 ఆట మొదలవుతోంది…" : said;
        if (!t.contentEquals(board.getText())) board.setText(t);
    }

    /** His own AC's "on" link among this box's smart-home lines (never shown anywhere): found → true. */
    private boolean acLink() {
        LinkedHashMap<String, String[]> devs = devices(prefs.smartUrls());
        String base = CarRoad.myAc(devs.keySet());
        String[] u = base == null ? null : devs.get(base);
        if (u == null || u[0] == null) return false;
        acUrl = u[0];
        acName = deviceName(base);
        return true;
    }

    /** ✓ on the AC question: his tap is the yes — the link is pressed, the answer said. */
    private void acYes() {
        final String url = acUrl, name = acName;
        acAt = 0;
        roadKey = "";
        roadCheck();
        if (url == null) return;
        new Thread(() -> {
            boolean ok;
            try { Http.getText(url); ok = true; } catch (Exception e) { ok = false; }
            final boolean done = ok;
            main.post(() -> host.speak(done ? "సరే, ఇంట్లో " + name + " ఆన్ చేశాను. మీరు వచ్చేసరికి చల్లగా ఉంటుంది." : name + " ఆన్ కాలేదు: నెట్ చూడండి."));
        }, "car-ac").start();
    }

    /** Every 2 s: the song strip — its name and app (never more than the title), and the keys while something plays. */
    private void musicCheck() {
        String t;
        boolean keys;
        try {
            JSONObject o = Music.panel(getContext());
            if (o.optBoolean("none")) {
                keys = false;
                t = o.optBoolean("access", true) ? "🎵 పాట లేదు · ఇక్కడ నొక్కండి" : "🎵 పాట పేరు కోసం: నోటిఫికేషన్ యాక్సెస్ ఇవ్వండి";
            } else {
                keys = true;
                String title = o.optString("title");
                t = (o.optBoolean("playing") ? "♪ " : "⏸ ") + (title.isEmpty() ? o.optString("app") : title + " · " + o.optString("app"));
            }
        } catch (Exception e) {
            keys = false;
            t = "🎵";
        }
        String key = t + keys;
        if (key.equals(musicKeyNow)) return;
        musicKeyNow = key;
        musicText.setText(t);
        int v = keys ? VISIBLE : INVISIBLE;
        prevKey.setVisibility(v);
        playKey.setVisibility(v);
        nextKey.setVisibility(v);
    }

    /** Every 10 s: how long he has driven without a real stop; amber near 2 hours, the tea cup after. */
    private void driveCheck() {
        long min = 0;
        try { min = CarCare.drivingMinutes(getContext()); } catch (Exception ignored) {}
        if (!CarBox.moving() && System.currentTimeMillis() - DriveService.lastMovingAt > 15 * 60_000L) min = 0; // (a real stop: a fresh count)
        lastDriveMin = min;
        driveChip.setText(CarRoad.driveLine(min));
        int lvl = CarRoad.driveLevel(min);
        BodyRig rig = portal.body.rig;
        if (lvl == 2 && !stretchCued) { stretchCued = true; rig.gesture("stretch", 3200); rig.show("caring", 3200); } // 🙆 two hours: a stretch (the tea word is the chip's)
        if (min == 0) stretchCued = false;
        double range = Double.NaN;
        try { range = CarFuel.rangeKm(getContext()); } catch (Exception ignored) {}
        if (!Double.isNaN(range)) {
            if (range < 80 && !fuelCued && CarBox.moving()) { fuelCued = true; rig.show("worried", 2600); rig.gesture("point_r", 2600); } // ⛽ towards the diesel button
            else if (range > 120) fuelCued = false;
        }
        driveChip.setTextColor(lvl == 0 ? textSoft : look != null && look.light ? 0xFFB26A00 : 0xFFFFB74D);
    }

    // ================================================================ the six buttons

    private void button(String what) {
        switch (what) {
            case "talk": host.talk(); break;
            case "way": showWay(); break;
            case "music": showMusic(); break;
            case "fuel":
                host.ask("⛽ ఎంత కొట్టించారు " + prefs.name() + "? రూపాయలు, లీటర్లు, మీటర్ రీడింగ్ చెప్పండి.");
                break;
            case "home": showHome(); break;
            case "sleep": showSleep(); break;
            default: break;
        }
    }

    // ---- 🧭 దారి (C3): home, his saved places, or say where; navigation only on his tap
    private void showWay() {
        Context c = getContext();
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        JSONObject home = Travel.home(c);
        if (home != null) {
            rows.add(new String[]{"🏠 ఇంటికి"});
            final JSONObject h = home;
            acts.add(() -> go(h));
        }
        int n = 0;
        for (JSONObject p : Places.all(c)) {
            if (n >= (home == null ? 5 : 4)) break;
            if (home != null && p.optString("name").equals(home.optString("name"))) continue;
            rows.add(new String[]{"📍 " + p.optString("name")});
            acts.add(() -> go(p));
            n++;
        }
        rows.add(new String[]{"🎤 వేరే చోటు చెప్పండి"});
        acts.add(() -> host.ask("ఎక్కడికి వెళ్లాలి " + prefs.name() + "?"));
        if (home == null && !CarBox.moving()) {
            rows.add(new String[]{"📌 ఇక్కడే మా ఇల్లు"});
            acts.add(this::saveHome);
        }
        rows.add(new String[]{"🗺️ మ్యాప్ తెరువు"});
        acts.add(() -> openMaps(c));
        openSheet("🧭 ఎక్కడికి?", home == null ? "ఇల్లు ఇంకా తెలియదు: ఇంటి దగ్గర 'ఇక్కడే మా ఇల్లు' నొక్కండి." : null, rows, acts, 2);
    }

    private void go(JSONObject p) {
        closeSheet();
        try {
            Places.open(getContext(), p, true);
            host.speak(p.optString("name") + " కి దారి మొదలుపెట్టాను.");
        } catch (Exception e) {
            host.speak("మ్యాప్ యాప్ తెరవలేకపోయాను: box లో Google Maps ఉందో చూడండి.");
        }
    }

    private void saveHome() {
        closeSheet();
        confirm("ఈ చోటుని 'ఇల్లు' అని గుర్తుపెట్టుకోనా?", () -> {
            final Context app = getContext().getApplicationContext();
            new Thread(() -> {
                Location l = Drive.here(app);
                String r;
                if (l == null) r = "ఇక్కడ GPS దొరకలేదు: కాసేపాగి మళ్లీ నొక్కండి.";
                else {
                    try { Places.save(app, "ఇల్లు", l.getLatitude(), l.getLongitude(), null, null); r = "సరే, ఇది ఇల్లు అని గుర్తుపెట్టుకున్నాను."; }
                    catch (Exception e) { r = "సేవ్ చేయలేకపోయాను."; }
                }
                final String said = r;
                main.post(() -> host.speak(said));
            }, "car-home").start();
        });
    }

    private static void openMaps(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
        if (i == null) i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0"));
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { Toast.makeText(c, "మ్యాప్ యాప్ లేదు", Toast.LENGTH_SHORT).show(); }
    }

    // ---- 🎵 పాటలు (C7): the song in any app, the volume, his radio
    private void showMusic() {
        Context c = getContext();
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        rows.add(new String[]{"⏮", "⏯", "⏭"});
        acts.add(null);
        rows.add(new String[]{"🔉 తగ్గించు", "🔊 పెంచు"});
        acts.add(null);
        List<String> stations = new ArrayList<>();
        try {
            JSONArray st = Music.radioPanel(c).optJSONArray("stations");
            for (int i = 0; st != null && i < st.length() && stations.size() < 2; i++) stations.add(st.optJSONObject(i).optString("name"));
        } catch (Exception ignored) {}
        for (String s : stations) { rows.add(new String[]{"📻 " + s}); acts.add(() -> radio(s)); }
        if (SoundService.radioOn) { rows.add(new String[]{"⏹ రేడియో ఆపు"}); acts.add(() -> { SoundService.stop(getContext()); closeSheet(); }); }
        rows.add(new String[]{"🎵 మ్యూజిక్ యాప్"});
        acts.add(() -> musicApp(getContext()));
        rows.add(new String[]{"🎲 ప్రయాణ ఆటలు"});
        acts.add(this::showGames);
        String now;
        try {
            JSONObject o = Music.panel(c);
            now = o.optBoolean("none") ? "ఇప్పుడు ఏ పాటా లేదు" : "▶ " + (o.optString("title").isEmpty() ? o.optString("app") : o.optString("title") + " · " + o.optString("app"));
        } catch (Exception e) { now = null; }
        openSheet("🎵 పాటలు", now, rows, acts, 3);
    }

    /** A row of several buttons in the music sheet: the media keys. */
    private void musicKey(String label) {
        String a = label.startsWith("⏮") ? "previous" : label.startsWith("⏯") ? "toggle" : label.startsWith("⏭") ? "next"
                : label.startsWith("🔉") ? "volume_down" : label.startsWith("🔊") ? "volume_up" : "";
        if (a.isEmpty()) return;
        String r = Music.control(getContext(), a);
        if (r != null && !r.isEmpty()) toast(r);
    }

    private void radio(String name) {
        closeSheet();
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            String r = Music.radioOnPhone(app, name);
            if (r != null && !r.startsWith("📱")) main.post(() -> host.speak(r));
        }, "car-radio").start();
    }

    /** 🎲 C16: a trip game by voice (the AI plays it; one short turn at a time). */
    private void games() {
        closeSheet();
        host.ask("🎲 ఏ ఆట ఆడదాం " + prefs.name() + "? పొడుపు కథలు, దారిలో ఊర్ల క్విజ్, సినిమా పాటల పేర్ల అంత్యాక్షరి, 20 ప్రశ్నలు, లేదా ఒక చిన్న కథ?");
    }

    private static final String[] MUSIC_APPS = {"com.google.android.apps.youtube.music", "com.spotify.music", "com.jio.media.jiobeats",
            "com.gaana", "com.bsbportal.music", "com.amazon.mp3", "com.google.android.youtube"};

    private void musicApp(Context c) {
        closeSheet();
        String pkg = null;
        try { android.media.session.MediaController mc = Music.active(c); if (mc != null) pkg = mc.getPackageName(); } catch (Exception ignored) {}
        Intent i = pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
        for (int k = 0; i == null && k < MUSIC_APPS.length; k++) i = c.getPackageManager().getLaunchIntentForPackage(MUSIC_APPS[k]);
        if (i == null) { host.speak("ఈ box లో మ్యూజిక్ యాప్ కనిపించలేదు."); return; }
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { toast("తెరవలేకపోయాను"); }
    }

    // ---- 🏠 ఇల్లు (C6): the AC / fan at home (this box's own links), his arrival time to amma's tablet, a word home
    private void showHome() {
        Context c = getContext();
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        tagLinks.clear();
        LinkedHashMap<String, String[]> devs = devices(prefs.smartUrls());
        int shown = 0;
        for (String base : devs.keySet()) {
            if (shown >= 3) break;
            String[] u = devs.get(base);
            String name = deviceName(base);
            if (u[0] != null || u[1] != null) {
                List<String> labels = new ArrayList<>();
                if (u[0] != null) labels.add(icon(base) + " " + name + " ఆన్");
                if (u[1] != null) labels.add(name + " ఆఫ్");
                rows.add(labels.toArray(new String[0]));
                acts.add(null);
                tagLinks.put(icon(base) + " " + name + " ఆన్", u[0]);
                tagLinks.put(name + " ఆఫ్", u[1]);
            } else {
                rows.add(new String[]{"▶ " + name});
                acts.add(() -> pressLink(name, u[2]));
            }
            shown++;
        }
        boolean linked = HomeLink.linked(c);
        rows.add(new String[]{"🕒 అమ్మగారికి నా రాక టైమ్"});
        acts.add(() -> { if (!linked) host.speak(notLinked()); else confirm("అమ్మగారి టాబ్లెట్‌కి మీరు వచ్చే టైమ్ చెప్పనా?", this::eta); });
        rows.add(new String[]{"📢 ఇంటికి ఒక మాట"});
        acts.add(() -> { if (!linked) host.speak(notLinked()); else host.ask("ఇంట్లో ఏమని వినిపించమంటారు " + prefs.name() + "?"); });
        openSheet("🏠 ఇల్లు", devs.isEmpty() ? "AC / ఫ్యాన్ బటన్ల కోసం: ⚙️ → స్మార్ట్ హోమ్ లో 'anil ac on = లింక్' లాంటి లైన్లు పెట్టండి." : null, rows, acts, 2);
    }

    private final java.util.Map<String, String> tagLinks = new java.util.HashMap<>();

    private static String notLinked() { return "ఈ box ఇంకా ఇంటి లింక్‌తో కలవలేదు: ⚙️ → 🚗 → 📮 ఫోన్ ↔ కారు box లింక్ లో మీ bot token పెట్టండి."; }

    /** His smart-home lines grouped per device ("anil ac on" + "anil ac off" = one, with both links). Pure. */
    static LinkedHashMap<String, String[]> devices(String lines) {
        LinkedHashMap<String, String[]> devs = new LinkedHashMap<>();
        for (String line : (lines == null ? "" : lines).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim(), url = line.substring(eq + 1).trim(), low = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (low.startsWith("charger") || low.startsWith("tv quiet") || low.startsWith("tv back")) continue; // (the tablet's own links)
            if (name.isEmpty() || !url.startsWith("http")) continue;
            int kind = low.endsWith(" on") ? 0 : low.endsWith(" off") ? 1 : 2;
            String base = kind == 0 ? low.substring(0, low.length() - 3).trim() : kind == 1 ? low.substring(0, low.length() - 4).trim() : low;
            if (base.isEmpty()) continue;
            String[] d = devs.get(base);
            if (d == null) devs.put(base, d = new String[3]);
            if (d[kind] == null) d[kind] = url;
        }
        return devs;
    }

    /** The device's name as he says it in the car: "anil ac" → "నా AC", "amma fan" → "అమ్మ ఫ్యాన్". Pure. */
    static String deviceName(String base) {
        StringBuilder out = new StringBuilder();
        for (String w : base.trim().split("\\s+")) {
            String l = w.toLowerCase(Locale.ROOT), s;
            switch (l) {
                case "anil": case "son": case "my": s = "నా"; break;
                case "amma": case "mom": case "mother": s = "అమ్మ"; break;
                case "ac": s = "AC"; break;
                case "tv": s = "TV"; break;
                case "fan": s = "ఫ్యాన్"; break;
                case "light": case "lamp": s = "లైట్"; break;
                case "hall": s = "హాల్"; break;
                case "bedroom": s = "బెడ్‌రూమ్"; break;
                case "kitchen": s = "వంటగది"; break;
                default: s = l.length() <= 2 ? w.toUpperCase(Locale.ROOT) : Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            if (out.length() > 0) out.append(' ');
            out.append(s);
        }
        return out.toString();
    }

    private static String icon(String base) {
        return base.matches(".*\\bac\\b.*") ? "❄️" : base.matches(".*\\bfan\\b.*") ? "🌀" : base.matches(".*\\b(light|lamp)\\b.*") ? "💡" : "🔌";
    }

    private void pressLink(String label, String url) {
        if (url == null) return;
        closeSheet();
        confirm("ఇంట్లో " + label + " చేయనా?", () -> {
            new Thread(() -> {
                boolean ok;
                try { Http.getText(url); ok = true; } catch (Exception e) { ok = false; }
                final boolean done = ok;
                main.post(() -> host.speak(done ? "సరే, ఇంట్లో " + label + " చేశాను." : label + " కాలేదు: నెట్ చూడండి."));
            }, "car-smart").start();
        });
    }

    private void eta() {
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            String r;
            try {
                JSONObject o = MapsPlus.homeEta(app, false);
                if (o.optBoolean("ok")) r = "అమ్మగారి టాబ్లెట్ మీరు సుమారు " + o.optInt("minutes") + " నిమిషాల్లో (" + o.optString("arrive_at") + " కి) వస్తారని చెబుతుంది.";
                else r = etaProblem(o.optString("error"), o.optString("say", o.optString("message", "")));
            } catch (Exception e) { r = "టాబ్లెట్‌కి చెప్పలేకపోయాను."; }
            final String said = r;
            main.post(() -> host.speak(said));
        }, "car-eta").start();
    }

    private static String etaProblem(String code, String why) {
        switch (code) {
            case "no_home": return "ఇల్లు ఎక్కడో box కి ఇంకా తెలియదు: ఇంటి దగ్గర 🧭 → 'ఇక్కడే మా ఇల్లు' నొక్కండి.";
            case "near_home": return "మీరు ఇప్పటికే ఇంటి దగ్గర ఉన్నారు.";
            case "no_route": return "ఇంటికి దారి దొరకలేదు.";
            case "not_linked": return notLinked();
            default: return "టాబ్లెట్‌కి చెప్పలేకపోయాను" + (why == null || why.isEmpty() ? "." : ": " + why);
        }
    }

    // ---- 😴 నిద్ర (C4): "నిద్ర వస్తోంది", the alarm's test, the alert on / off
    private void showSleep() {
        Context c = getContext();
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        rows.add(new String[]{"😴 నిద్ర వస్తోంది"});
        acts.add(() -> {
            closeSheet();
            String r = CarCare.sleepy(getContext(), false);
            host.speak(r != null ? r : "జాగ్రత్త " + prefs.name() + ": సురక్షితమైన చోట ఆపి కాసేపు కునుకు తీయండి.");
        });
        rows.add(new String[]{"🎲 మెలకువకి ఒక ఆట"});
        acts.add(this::games);
        if (!CarBox.moving()) {
            boolean on = CarCare.enabled(c);
            rows.add(new String[]{on ? "✅ నిద్ర అలర్ట్ ఆన్ (ఆఫ్ చేయి)" : "⭕ నిద్ర అలర్ట్ ఆఫ్ (ఆన్ చేయి)"});
            acts.add(() -> { CarCare.settings(getContext()).edit().putBoolean("drive_fatigue", !on).apply(); closeSheet(); toast(on ? "నిద్ర అలర్ట్ ఆఫ్" : "నిద్ర అలర్ట్ ఆన్"); });
            rows.add(new String[]{"🔔 అలారం శబ్దం టెస్ట్"});
            acts.add(() -> CarCare.testTone(getContext()));
        }
        String st = "";
        try { st = CarCare.statusWords(c); } catch (Exception ignored) {}
        openSheet("😴 నిద్ర అలర్ట్", st.isEmpty() ? null : st, rows, acts, 1);
    }

    // ================================================================ the sleep alert's question (C4)

    private boolean awakeShown;
    private long dismissedAt;
    private int awakeKind;

    private void awakeCheck() {
        Context c = getContext();
        boolean asking = false, alarm = false;
        try {
            asking = CarCare.asking(c);
            long ago = CarCare.alarmAgoMs(); // (an alarm he already answered with ✅ stays gone; a new one comes back)
            alarm = CarCare.on(c) && ago < 60_000 && (dismissedAt == 0 || ago < android.os.SystemClock.elapsedRealtime() - dismissedAt);
        } catch (Exception ignored) {}
        if (!asking && !alarm) { if (awakeShown) { awake.setVisibility(GONE); awakeShown = false; if (awakeKind == 1) thumbsUp(); } return; }
        int kind = alarm ? 2 : 1;
        if (awakeShown && kind == awakeKind || H == 0) return; // (shown already; not laid out yet: the next second)
        awakeShown = true;
        awakeKind = kind; // (the question became the alarm: the red one now)
        awake.removeAllViews();
        LinearLayout box = panel(alarm ? 0xFF5A1010 : 0xFF13233A, alarm ? 0xFFFF6B5E : accent);
        TextView q = autoText(getContext(), alarm ? "🚨 కారు పక్కకి ఆపండి" : "😴 మెలకువగా ఉన్నారా?", true, 80);
        q.setGravity(Gravity.CENTER);
        q.setTextColor(Color.WHITE);
        box.addView(q, new LinearLayout.LayoutParams(-1, Math.max(1, H / 6)));
        TextView y = bigButton("✅ ఉన్నాను", 0xFF1E6B4A);
        y.setOnClickListener(v -> {
            try { CarCare.answered(getContext(), "tap"); } catch (Exception ignored) {}
            dismissedAt = android.os.SystemClock.elapsedRealtime();
            awake.setVisibility(GONE);
            awakeShown = false;
            if (awakeKind == 1) thumbsUp();
        });
        LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(-1, Math.max(1, H * 30 / 100));
        yl.topMargin = H / 30;
        box.addView(y, yl);
        awake.addView(box, panelParams());
        awake.setVisibility(VISIBLE);
        awake.bringToFront();
    }

    // ================================================================ sheets and questions

    private Runnable sheetGone;

    /**
     * A sheet of big buttons (rows: one row = one or more labels side by side; acts: one action per row, or null for a
     * row of several (their labels decide: the media keys, the AC's ఆన్ / ఆఫ్)). Closes by itself after 25 s untouched.
     */
    private void openSheet(String title, String note, List<String[]> rows, List<Runnable> acts, int perRow) {
        sheet.removeAllViews();
        LinearLayout box = panel(tileBg, accent);
        TextView t = autoText(getContext(), title, true, 60);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textMain); // (dark on the white theme's sheet)
        box.addView(t, new LinearLayout.LayoutParams(-1, Math.max(1, H / 9)));
        if (note != null && !note.isEmpty() && !CarBox.moving()) {
            TextView n = autoText(getContext(), note, false, 30);
            n.setGravity(Gravity.CENTER);
            n.setMaxLines(2);
            n.setTextColor(textSoft);
            box.addView(n, new LinearLayout.LayoutParams(-1, Math.max(1, H / 11)));
        }
        // single buttons go perRow to a row; a row with several labels stays one row
        List<Object[]> lines = new ArrayList<>(); // {String[] labels, Runnable[] actions}
        List<String> singles = new ArrayList<>();
        List<Runnable> singleActs = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            Runnable a = acts.get(i);
            if (a != null && r.length == 1) {
                singles.add(r[0]);
                singleActs.add(a);
                if (singles.size() == perRow) {
                    lines.add(new Object[]{singles.toArray(new String[0]), singleActs.toArray(new Runnable[0])});
                    singles.clear();
                    singleActs.clear();
                }
            } else {
                if (!singles.isEmpty()) {
                    lines.add(new Object[]{singles.toArray(new String[0]), singleActs.toArray(new Runnable[0])});
                    singles.clear();
                    singleActs.clear();
                }
                Runnable[] ra = new Runnable[r.length];
                for (int k = 0; k < r.length; k++) {
                    final String label = r[k];
                    ra[k] = () -> rowKey(label);
                }
                lines.add(new Object[]{r, ra});
            }
        }
        if (!singles.isEmpty()) lines.add(new Object[]{singles.toArray(new String[0]), singleActs.toArray(new Runnable[0])});
        int rowH = Math.max(1, Math.min(H * 17 / 100, (H * 70 / 100) / Math.max(1, lines.size() + 1)));
        for (Object[] line : lines) {
            String[] labels = (String[]) line[0];
            Runnable[] ra = (Runnable[]) line[1];
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < labels.length; k++) {
                TextView b = bigButton(labels[k], CarTheme.withAlpha(accent, 0x55));
                final Runnable go = ra[k];
                b.setOnClickListener(v -> { sheetTouched(); go.run(); });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1);
                if (k > 0) bl.leftMargin = W / 80;
                row.addView(b, bl);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, rowH);
            rl.topMargin = H / 60;
            box.addView(row, rl);
        }
        TextView close = bigButton("✕ మూసేయి", 0x663A4658);
        close.setOnClickListener(v -> closeSheet());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, rowH);
        cl.topMargin = H / 40;
        box.addView(close, cl);
        sheet.addView(box, panelParams());
        sheet.setVisibility(VISIBLE);
        sheet.setOnClickListener(v -> closeSheet()); // (a tap outside the panel)
        sheetTouched();
        if (awakeShown) awake.bringToFront();
    }

    /** A button in a row of several: the media keys, or an AC's ఆన్ / ఆఫ్. */
    private void rowKey(String label) {
        String url = tagLinks.get(label);
        if (url != null) { pressLink(label.replaceFirst("^[^\\p{L}\\p{N}]+\\s*", ""), url); return; }
        musicKey(label);
    }

    private void sheetTouched() {
        if (sheetGone != null) main.removeCallbacks(sheetGone);
        sheetGone = this::closeSheet;
        main.postDelayed(sheetGone, 25_000);
    }

    void closeSheet() {
        if (sheetGone != null) main.removeCallbacks(sheetGone);
        sheet.setVisibility(GONE);
        sheet.removeAllViews();
    }

    private Runnable askGone;

    /** A yes / no over the screen: ✓ → yes; ✕ or 15 s untouched → nothing is done. */
    private void confirm(String question, Runnable yes) {
        ask.removeAllViews();
        LinearLayout box = panel(tileBg, accent);
        TextView q = autoText(getContext(), question, true, 60);
        q.setGravity(Gravity.CENTER);
        q.setMaxLines(2);
        q.setTextColor(look != null && look.light ? 0xFF17232F : Color.WHITE);
        box.addView(q, new LinearLayout.LayoutParams(-1, Math.max(1, H / 5)));
        LinearLayout row = new LinearLayout(getContext());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Math.max(1, H / 4));
        rl.topMargin = H / 30;
        box.addView(row, rl);
        if (askGone != null) main.removeCallbacks(askGone);
        final Runnable hide = askGone = () -> ask.setVisibility(GONE);
        TextView y = bigButton("✓ అవును", 0xFF1E6B4A);
        y.setOnClickListener(v -> { main.removeCallbacks(hide); hide.run(); yes.run(); });
        row.addView(y, new LinearLayout.LayoutParams(0, -1, 1));
        TextView n = bigButton("✕ వద్దు", 0x663A4658);
        n.setOnClickListener(v -> { main.removeCallbacks(hide); hide.run(); toast("సరే, ఏమీ చేయలేదు"); });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -1, 1);
        nl.leftMargin = W / 60;
        row.addView(n, nl);
        ask.addView(box, panelParams());
        ask.setVisibility(VISIBLE);
        ask.bringToFront();
        if (awakeShown) awake.bringToFront();
        main.postDelayed(hide, 15_000);
    }

    /** Settings and the chat: only when the car stands (C9: nothing to read or type while it moves). */
    private boolean parkedOrSay() {
        if (!CarBox.moving()) return true;
        toast("కారు ఆపాక తెరవండి (భద్రత కోసం)");
        return false;
    }

    // ================================================================ weather (the box's own GPS place)

    private volatile boolean weatherBusy;
    private long weatherTry;

    private void showWeather() {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long at = sp.getLong("w_at", 0);
        String fuel = CarFuel.chip(getContext()); // ⛽ how far the diesel goes, beside the weather (C12)
        long now = System.currentTimeMillis();
        dress(sp, at, now);
        if (Drive.navState() != null && !CarAhead.card.isEmpty() && now - CarAhead.cardAt < 20 * 60_000L) { // (the turn has the card: the warning goes here)
            minis.setText((CarAhead.cardRain ? "🌧️ " : "🌫️ ") + CarAhead.card);
            return;
        }
        if (at == 0 || now - at > 3 * 3600_000L) { minis.setText(fuel); return; }
        minis.setText("🌡️ " + Math.round(sp.getFloat("w_temp", 0)) + "° · " + HomeScreen.skyTe(sp.getInt("w_code", -1)) + (fuel.isEmpty() ? "" : "   " + fuel));
    }

    private void fetchWeather() {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (weatherBusy || now - sp.getLong("w_at", 0) < 30 * 60_000L || now - weatherTry < 10 * 60_000L || !Net.online(getContext())) return;
        weatherBusy = true;
        weatherTry = now;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                Location l = DriveService.last != null ? DriveService.last : Devices.freshFix(app);
                if (l == null) return;
                JSONObject all = Http.get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&timezone=auto",
                        l.getLatitude(), l.getLongitude()));
                JSONObject cur = all.optJSONObject("current");
                if (cur == null || !cur.has("temperature_2m")) return;
                app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putFloat("w_temp", (float) cur.optDouble("temperature_2m"))
                        .putInt("w_code", cur.optInt("weather_code", -1)).putLong("w_at", System.currentTimeMillis()).apply();
                main.post(() -> { if (running) showWeather(); });
            } catch (Throwable ignored) {
                // (no net / no place: the last weather stays a while)
            } finally {
                weatherBusy = false;
            }
        }, "car-weather").start();
    }

    // ================================================================ sizes and colours

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = w;
        H = h;
        int pad = Math.max(4, Math.min(w, h) / 34);
        content.setPadding(pad, pad, pad, pad);
        midCol.setPadding(pad / 2, 0, pad / 2, 0);
        int m = Math.max(3, Math.min(w, h) / 70);
        List<View> boxes = new ArrayList<>(tiles);
        boxes.add(roadCard);
        boxes.add(musicStrip);
        for (View t : boxes) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            lp.setMargins(m, m, m, m);
            t.setLayoutParams(lp);
        }
        int cp = Math.max(4, Math.min(w, h) / 50);
        roadCard.setPadding(cp, cp / 2, cp, cp / 2);
        musicStrip.setPadding(cp * 2, cp / 3, cp, cp / 3);
        subText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11, h / 24f));
        board.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(14, h / 15f));
        board.setMaxLines(7);
        board.setPadding(cp * 2, cp * 2, cp * 2, cp * 2);
        float rad = Math.max(10, Math.min(w, h) / 22f);
        for (View t : tiles) t.setBackground(CarArt.glass(look, rad, true));
        roadCard.setBackground(CarArt.glass(look, rad, false));
        musicStrip.setBackground(CarArt.glass(look, rad, false));
        board.setBackground(CarArt.glass(look, rad, false));
        if (sheet.getVisibility() == VISIBLE) closeSheet(); // (its sizes were for the old screen)
    }

    /** The theme's colours for the mode and the hour, put on everything. */
    private void palette() {
        look = CarTheme.of(prefs.carTheme()).look(mode, night, prefs.carAccent());
        tileBg = look.sheetFill;
        textMain = look.textMain;
        textSoft = look.textSoft;
        accent = look.accent;
        backdrop.setLook(look);
        gauge.setLook(look);
        portal.setLook(look);
        portal.setLowMotion(prefs.carLowMotion());
        portal.body.setSlow(night && !dancing);
        for (TextView t : new TextView[]{clock, subText}) t.setTextColor(textMain);
        stateText.setTextColor(accent);
        for (TextView t : new TextView[]{date, minis, chips}) t.setTextColor(textSoft);
        gear.setTextColor(textSoft);
        chat.setTextColor(textSoft);
        float rad = Math.max(10, Math.min(Math.max(W, 600), Math.max(H, 300)) / 22f);
        for (View t : tiles) {
            t.setBackground(CarArt.glass(look, rad, true));
            LinearLayout l = (LinearLayout) t;
            ((TextView) l.getChildAt(1)).setTextColor(textMain);
        }
        for (CarArt.Icon i : tileIcons) i.setColour(accent);
        roadCard.setBackground(CarArt.glass(look, rad, false));
        musicStrip.setBackground(CarArt.glass(look, rad, false));
        board.setBackground(CarArt.glass(look, rad, false));
        board.setTextColor(textMain);
        portal.body.rig.setNightPlays(night);
        road1.setTextColor(textMain);
        road2.setTextColor(textSoft);
        musicText.setTextColor(textMain);
        roadYes.setTextColor(Color.WHITE);
        roadYes.setBackground(round(0xFF1E6B4A, CarTheme.withAlpha(accent, 0x88), 14));
        roadNo.setTextColor(textSoft);
        roadIcon.setColour(acAt > 0 ? 0xFF7FD8FF : accent);
        for (CarArt.Icon k : new CarArt.Icon[]{prevKey, playKey, nextKey}) k.setColour(accent);
        driveChip.setTextColor(CarRoad.driveLevel(lastDriveMin) == 0 ? textSoft : look.light ? 0xFFB26A00 : 0xFFFFB74D);
        GradientDrawable sub = new GradientDrawable();
        sub.setColor(look.light ? 0x66FFFFFF : 0x14FFFFFF);
        sub.setCornerRadius(rad);
        sub.setStroke(1, look.light ? 0x33000000 : 0x1AFFFFFF);
        subText.setBackground(sub);
        int px = Math.max(6, Math.min(Math.max(W, 600), Math.max(H, 300)) / 60);
        subText.setPadding(px * 2, px / 2, px * 2, px / 2);
    }

    private View tile(Context c, int icon, String label) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        CarArt.Icon e = new CarArt.Icon(c, icon);
        tileIcons.add(e);
        t.addView(e, new LinearLayout.LayoutParams(-1, 0, 56));
        TextView l = autoText(c, label, true, 60);
        l.setGravity(Gravity.CENTER);
        t.addView(l, new LinearLayout.LayoutParams(-1, 0, 30));
        t.setPadding(4, 8, 4, 8);
        t.setOnTouchListener((v, ev) -> { // (a press shows at once: he sees it took)
            if (ev.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.6f);
            else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return t;
    }

    private FrameLayout overlay(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(0xCC000000);
        f.setClickable(true);
        f.setVisibility(GONE);
        return f;
    }

    private LinearLayout panel(int fill, int stroke) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClickable(true); // (taps on the panel stay in it)
        int p = Math.max(6, Math.min(W, H) / 30);
        box.setPadding(p, p, p, p);
        box.setBackground(round(fill, stroke, 24));
        return box;
    }

    private LayoutParams panelParams() {
        LayoutParams lp = new LayoutParams(W > 0 ? W * 80 / 100 : -1, -2, Gravity.CENTER);
        return lp;
    }

    private TextView bigButton(String s, int bg) {
        TextView t = autoText(getContext(), s, true, 70);
        t.setGravity(Gravity.CENTER);
        t.setTextColor((bg >>> 24) == 0xFF || look == null || !look.light ? Color.WHITE : 0xFF17232F); // (a solid button: white words)
        t.setBackground(round(bg, CarTheme.withAlpha(accent, 0x88), 18));
        t.setClickable(true);
        t.setFocusable(true);
        int p = Math.max(4, H / 60);
        t.setPadding(p, p / 2, p, p / 2);
        return t;
    }

    /** One line that grows / shrinks with its box (any car display). */
    private static TextView autoText(Context c, String s, boolean bold, int maxSp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setMaxLines(1); // (not setSingleLine: that scrolls sideways, and the auto size then never shrinks a long Telugu word)
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        try { t.setAutoSizeTextTypeUniformWithConfiguration(9, maxSp, 1, TypedValue.COMPLEX_UNIT_SP); } catch (Exception ignored) {}
        return t;
    }

    private GradientDrawable round(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(Ui.dp(getContext(), r));
        g.setStroke(Math.max(1, Ui.dp(getContext(), 1)), stroke);
        return g;
    }

    private void tap(Runnable r) {
        try { r.run(); } catch (Exception e) { toast("అది ఇప్పుడు కాలేదు"); }
    }

    private void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }
}
