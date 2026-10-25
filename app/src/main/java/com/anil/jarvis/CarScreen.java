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
        /** A request to Jarvis's brain as if he had said it (label: what the chat shows); the answer is spoken. False: not now (busy). */
        boolean request(String prompt, String label);
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
    private final CarArt.Hero hero;
    private final TextView apps;
    private final TextView clock, date, minis, stateText, subText, chips, gear, chat, driveChip, road1, road2, roadYes, roadNo, musicText, board;
    private final LinearLayout roadCard, musicStrip;
    private final CarArt.Icon roadIcon, prevKey, playKey, nextKey, artIcon;
    private final FrameLayout artBox;
    private final android.widget.ImageView artImg;
    private final TextView songSub;
    private int stripW, stripH;
    private final CarMap carMap; // 🗺️ G1: the map in the map shapes and on a trip
    private String shape = CarLayout.CLASSIC;
    // 🎛️ ① ② ③'s own cards: the wings lean in, the middle card leans back, ②'s three dials, the chips row under the middle
    private final CarKit.Tilt wingL, wingR, panel, mapTilt;
    private final LinearLayout wingLIn, wingRIn, panelIn, panelTop, centreCol, tileGrid2, dialCol, keyRow, songWords, alongRow;
    private final android.widget.HorizontalScrollView alongScroll;
    private final List<TextView> alongChips = new ArrayList<>();
    private final CarKit.Dial[] dials = new CarKit.Dial[3];
    private String[] sixKinds, dialKinds;
    private final java.util.Map<TextView, Integer> autoMax = new java.util.LinkedHashMap<>(); // (the words that grow with their box, and their biggest size)
    private float textScale = 1f;
    private String deckKey = "";
    // ⑧ the stage (parked with a song) and G2 (the Google Maps app beside Jarvis)
    private final LinearLayout stageLeft, stageMid, sideTop, sideText;
    private final List<View> stageBtns = new ArrayList<>();
    private final TextView stageExit, mapPill, mapGap;
    private boolean besideNow, settingSheet, rightOnly;
    // 💡 the beat lights over the cards, 🎚️ the equalizer behind the song strip
    private final CarKit.BeatLights lights;
    private final CarKit.Spectrum eqDraw;
    private final List<android.graphics.Path> pathPool = new ArrayList<>();
    // 🌌 the moving background, 🕰️ the clock with hands, 🧩 the shape button, 🎨 the song picture's colour
    private final CarKit.Ambient ambient;
    private final CarKit.ClockFace clockFace;
    private final TextView shapeBtn;
    private int songAccent, fadeSeq;
    private int pathUsed;
    private long besideAt;
    private final List<View> tiles = new ArrayList<>();
    private final List<CarArt.Icon> tileIcons = new ArrayList<>();
    private final FrameLayout sheet, ask, awake, saver;
    private final LinearLayout saverBox;
    private final TextView saverClock, saverLine;
    private int W, H;
    private boolean night, running, wasMoving, dancing;
    private int state = OrbView.IDLE, mode = CarTheme.DRIVING;
    private String said = "", heard = "";
    private long saidAt;
    private CarTheme.Look look;
    private final CarBeat beat;
    private final CarDance dj = new CarDance(); // 💃 the next step, with the song's beat and loudness
    private long danceTickAt;
    private int bpmShown;
    private final boolean[] cheerNow = new boolean[1];
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
        ambient = new CarKit.Ambient(a); // (🌌 over the theme's background, under everything else)
        addView(ambient, new LayoutParams(-1, -1));
        clockFace = new CarKit.ClockFace(a);
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
        chipRow.addView(chips, new LinearLayout.LayoutParams(0, -1, 42));
        shapeBtn = autoText(a, "🧩", false, 36); // (parked: each tap the next shape)
        shapeBtn.setGravity(Gravity.CENTER);
        shapeBtn.setOnClickListener(v -> tap(this::nextShape));
        chipRow.addView(shapeBtn, new LinearLayout.LayoutParams(0, -1, 13));
        apps = autoText(a, "▦", false, 36); // the box's other apps (Jarvis as the box's home: the way back to them)
        apps.setGravity(Gravity.CENTER);
        apps.setOnClickListener(v -> tap(this::showApps));
        chipRow.addView(apps, new LinearLayout.LayoutParams(0, -1, 15));
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
        midCol.addView(gauge, new LinearLayout.LayoutParams(-1, 0, 47));
        hero = new CarArt.Hero(a); // 🚙 his car, drawn: wheels with the speed, lights, indicators, rain
        midCol.addView(hero, new LinearLayout.LayoutParams(-1, 0, 19));
        clock = autoText(a, "", true, 70);
        clock.setGravity(Gravity.CENTER);
        midCol.addView(clock, new LinearLayout.LayoutParams(-1, 0, 13));
        date = autoText(a, "", false, 30);
        date.setGravity(Gravity.CENTER);
        midCol.addView(date, new LinearLayout.LayoutParams(-1, 0, 7));
        minis = autoText(a, "", false, 28);
        minis.setGravity(Gravity.CENTER);
        midCol.addView(minis, new LinearLayout.LayoutParams(-1, 0, 7));
        driveChip = autoText(a, "", true, 30); // ⏱ how long he has driven (amber near 2 hours: the sleep alert's long-drive clock)
        driveChip.setGravity(Gravity.CENTER);
        midCol.addView(driveChip, new LinearLayout.LayoutParams(-1, 0, 7));
        board = new TextView(a); // 🎲 parked, a game on: the question big, for the passengers (the gauge says 0 anyway)
        board.setGravity(Gravity.CENTER);
        board.setEllipsize(TextUtils.TruncateAt.END);
        board.setVisibility(GONE);
        board.setOnClickListener(v -> tap(host::talk));
        board.setOnLongClickListener(v -> { gameAt = 0; boardCheck(); toast("ఆట ఆపాను"); return true; }); // (a long press: the game ends, the gauge is back)
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
        roadYes.setOnClickListener(v -> tap(() -> {
            if (System.currentTimeMillis() - roadYesAt < 1500) return; // (it just changed what it does: a tap meant for the old one does nothing)
            if (acAt > 0) acYes(); else showGames();
        }));
        roadNo.setOnClickListener(v -> tap(() -> { acAt = 0; roadKey = ""; roadCheck(); }));
        roadCard.setOnClickListener(v -> tap(() -> { if (Drive.navState() != null) openMaps(getContext()); }));
        rightCol.addView(roadCard, new LinearLayout.LayoutParams(-1, 0, 25));
        rightCol.addView(tileGrid, new LinearLayout.LayoutParams(-1, 0, 54));
        tileGrid2 = new LinearLayout(a); // (② splits the six: three under Jarvis, three under the dials)
        tileGrid2.setOrientation(LinearLayout.VERTICAL);
        sixKinds = CarDeck.six(prefs.carButtons(), false);
        for (int i = 0; i < 6; i++) { // (which button sits where: his own choice — held while parked, ⚙️ — else the shape's six)
            final int slot = i;
            View tile = tile(a, CarArt.Icon.MIC, "");
            tile.setOnClickListener(v -> tap(() -> button(sixKinds[slot])));
            tile.setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseButton(slot); return true; }); // (moving: a firm press is still the tap)
            tiles.add(tile);
        }
        fillTiles(tileGrid, 0, 6, 3);
        setButtons();

        // the song strip at the bottom: the song's picture, its name (and singer · app), and ⏮ ⏯ ⏭ well apart
        musicStrip = new LinearLayout(a);
        musicStrip.setOrientation(LinearLayout.HORIZONTAL);
        musicStrip.setGravity(Gravity.CENTER_VERTICAL);
        artBox = new FrameLayout(a);
        artIcon = new CarArt.Icon(a, CarArt.Icon.MUSIC); // (no picture: a note)
        artIcon.setFill(0.72f);
        artBox.addView(artIcon, new FrameLayout.LayoutParams(-1, -1));
        artImg = new android.widget.ImageView(a);
        artImg.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artImg.setClipToOutline(true);
        artImg.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(v.getWidth(), v.getHeight()) * 0.18f);
            }
        });
        artImg.setVisibility(GONE);
        artBox.addView(artImg, new FrameLayout.LayoutParams(-1, -1));
        artBox.setOnClickListener(v -> tap(this::showMusic));
        artBox.setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseLights(); return true; }); // (held, parked: 🎚️ 💡)
        musicStrip.addView(artBox, new LinearLayout.LayoutParams(0, 0));
        LinearLayout words = songWords = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        musicText = new TextView(a); // (the song's name: one line, cut with "…" — never shrunk to a size nobody can read)
        musicText.setTypeface(Typeface.DEFAULT_BOLD);
        musicText.setMaxLines(1);
        musicText.setEllipsize(TextUtils.TruncateAt.END);
        musicText.setIncludeFontPadding(false);
        songSub = new TextView(a); // (the singer · the app)
        songSub.setMaxLines(1);
        songSub.setEllipsize(TextUtils.TruncateAt.END);
        songSub.setIncludeFontPadding(false);
        words.addView(musicText, new LinearLayout.LayoutParams(-1, -2));
        words.addView(songSub, new LinearLayout.LayoutParams(-1, -2));
        words.setOnClickListener(v -> tap(this::showMusic));
        words.setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseLights(); return true; });
        musicStrip.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        prevKey = new CarArt.Icon(a, CarArt.Icon.PREV);
        playKey = new CarArt.Icon(a, CarArt.Icon.PLAY);
        playKey.setRing(true);
        nextKey = new CarArt.Icon(a, CarArt.Icon.NEXT);
        String[] keyAct = {"⏮", "⏯", "⏭"};
        CarArt.Icon[] keys = {prevKey, playKey, nextKey};
        keyRow = new LinearLayout(a);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            final String k = keyAct[i];
            keys[i].setClickable(true);
            keys[i].setOnClickListener(v -> tap(() -> { musicKey(k); main.postDelayed(this::musicCheck, 700); }));
            keyRow.addView(keys[i], new LinearLayout.LayoutParams(0, 0));
        }
        musicStrip.addView(keyRow, new LinearLayout.LayoutParams(-2, -1));
        // the picture, the words and the keys are sized from the strip's own height (known only after layout)
        musicStrip.addOnLayoutChangeListener((v, l0, t0, r0, b0, ol, ot, or, ob) -> {
            int w = r0 - l0, h = b0 - t0;
            if (w > 0 && h > 0 && (w != stripW || h != stripH)) { stripW = w; stripH = h; main.post(() -> sizeStrip(w, h)); }
        });
        rightCol.addView(musicStrip, new LinearLayout.LayoutParams(-1, 0, 21)); // (a little taller: the song's picture and two lines)

        // ---- 🗺️ the map (the ④ / ⑦ shapes and a trip's map screen): not on the screen until a shape wants it
        carMap = new CarMap(a, prefs, new CarMap.Host() {
            @Override public void along(String what, String label) { JarvisMaps.openAlong(getContext(), what, label); }
            @Override public void menu() { JarvisMaps.open(getContext(), ""); }
            @Override public void bigMaps() { openMaps(getContext()); }
            @Override public void switchMap() { toast(mapMode("g2")); }
        });

        // ---- 🎛️ ① ② ③'s cards (not on the screen until a 3D shape wants them): the parts above are put into them
        wingL = new CarKit.Tilt(a);
        wingLIn = column(a);
        wingL.addView(wingLIn, new FrameLayout.LayoutParams(-1, -1));
        wingR = new CarKit.Tilt(a);
        wingRIn = column(a);
        wingR.addView(wingRIn, new FrameLayout.LayoutParams(-1, -1));
        panel = new CarKit.Tilt(a);
        panelIn = column(a);
        panel.addView(panelIn, new FrameLayout.LayoutParams(-1, -1));
        panelTop = new LinearLayout(a);
        panelTop.setOrientation(LinearLayout.HORIZONTAL);
        panelTop.setGravity(Gravity.CENTER_VERTICAL);
        centreCol = column(a);
        mapTilt = new CarKit.Tilt(a);
        dialCol = column(a);
        dialKinds = CarDeck.three(prefs.carDials());
        for (int i = 0; i < 3; i++) { // ② three small chrome dials in a zigzag; held (parked): what this one shows
            final int k = i;
            dials[i] = new CarKit.Dial(a);
            dials[i].setShift(i == 1 ? 0.6f : -0.6f);
            dials[i].setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseDial(k); return true; });
            dialCol.addView(dials[i], new LinearLayout.LayoutParams(-1, 0, 1));
        }
        alongScroll = new android.widget.HorizontalScrollView(a); // ☕ ⛽ 🍛 🏧 🚻 🔧 ➕: Jarvis Maps' "on my way" (① ②)
        alongScroll.setHorizontalScrollBarEnabled(false);
        alongScroll.setFillViewport(true);
        alongRow = new LinearLayout(a);
        alongRow.setOrientation(LinearLayout.HORIZONTAL);
        alongRow.setGravity(Gravity.CENTER);
        for (String[] ch : CarMap.CHIPS) chip(a, ch[0], () -> JarvisMaps.openAlong(getContext(), ch[1], ch[2]));
        chip(a, "➕ అన్నీ", () -> JarvisMaps.open(getContext(), ""));
        alongScroll.addView(alongRow, new FrameLayout.LayoutParams(-1, -1));
        gauge.setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseGauge(); return true; }); // (held, parked: the speed's look)

        // ---- ⑧ the stage: 🗺️ 🎲 🎤 down the left, Jarvis big under a light, ✕ to leave it for this song
        stageLeft = column(a);
        stageMid = column(a);
        int[] sk = {CarArt.Icon.MAP, CarArt.Icon.GAMES, CarArt.Icon.MIC};
        Runnable[] sr = {() -> JarvisMaps.open(getContext(), ""), this::showGames, host::talk};
        for (int i = 0; i < 3; i++) {
            CarArt.Icon b = new CarArt.Icon(a, sk[i]);
            b.setFill(0.48f);
            b.setClickable(true);
            final Runnable r = sr[i];
            b.setOnClickListener(v -> tap(r));
            stageBtns.add(b);
        }
        stageExit = autoText(a, "✕ స్టేజ్ ఆపు", true, 30);
        stageExit.setGravity(Gravity.CENTER);
        stageExit.setOnClickListener(v -> tap(() -> setStage(false))); // (off until he puts it on again; any time: it goes back to his screen)
        // ---- G2: Google Maps' own window on the left, Jarvis's column on the right with "🗺️ మ్యాప్ మార్చు"
        sideTop = new LinearLayout(a);
        sideTop.setOrientation(LinearLayout.HORIZONTAL);
        sideTop.setGravity(Gravity.CENTER_VERTICAL);
        sideText = column(a);
        mapPill = autoText(a, "🗺️ మ్యాప్ మార్చు", true, 30);
        mapPill.setGravity(Gravity.CENTER);
        mapPill.setOnClickListener(v -> tap(() -> toast(mapMode("g1", false))));
        mapGap = new TextView(a); // (under Google Maps' window; seen only when it isn't there: a tap brings it)
        mapGap.setText("🗺️ Google Maps ఇక్కడ వస్తుంది\n(రాకపోతే ఇక్కడ నొక్కండి)");
        mapGap.setGravity(Gravity.CENTER);
        mapGap.setOnClickListener(v -> tap(() -> toast(openBeside())));
        portal.setOnLongClickListener(v -> { if (CarBox.moving()) return false; chooseJarvis(); return true; }); // (held, parked: Jarvis's size; moving: the tap)

        // ---- over everything: a sheet of choices, a yes / no question, the sleep alert's ✅
        sheet = overlay(a);
        ask = overlay(a);
        awake = overlay(a);
        saver = new FrameLayout(a); // 🌙 parked and untouched 5 minutes: a dark clock (the sleep alert's question always comes over it)
        saver.setBackgroundColor(0xFF000000);
        saver.setVisibility(GONE);
        saverBox = new LinearLayout(a);
        saverBox.setOrientation(LinearLayout.VERTICAL);
        saverBox.setGravity(Gravity.CENTER);
        saverClock = new TextView(a);
        saverClock.setGravity(Gravity.CENTER);
        saverClock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        saverLine = new TextView(a);
        saverLine.setGravity(Gravity.CENTER);
        saverBox.addView(saverClock, new LinearLayout.LayoutParams(-2, -2));
        saverBox.addView(saverLine, new LinearLayout.LayoutParams(-2, -2));
        saver.addView(saverBox, new LayoutParams(-2, -2, Gravity.CENTER));
        lights = new CarKit.BeatLights(a, this::outlines); // (over the cards, under the screensaver and the questions; takes no touch)
        addView(lights, new LayoutParams(-1, -1));
        addView(saver, new LayoutParams(-1, -1));
        addView(sheet, new LayoutParams(-1, -1));
        addView(ask, new LayoutParams(-1, -1));
        addView(awake, new LayoutParams(-1, -1));

        Object[][] grow = {{stateText, 34}, {clock, 70}, {date, 30}, {minis, 28}, {driveChip, 30}, {chips, 26}, {road1, 46}, {road2, 30},
                {roadYes, 40}, {roadNo, 40}, {apps, 36}, {chat, 36}, {gear, 36}, {shapeBtn, 36}};
        for (Object[] g : grow) autoMax.put((TextView) g[0], (Integer) g[1]);
        for (View t : tiles) autoMax.put((TextView) ((LinearLayout) t).getChildAt(1), 60);
        deckKey = deckKey();
        textSizes();

        beat = new CarBeat(per -> { if (dancing) { portal.body.rig.setBeat(per); portal.beat(); } lights.beat(); });
        eqDraw = new CarKit.Spectrum(beat.eq);
        musicText.setShadowLayer(3, 0, 1, 0xCC000000); // (readable over the equalizer's bars)
        songSub.setShadowLayer(3, 0, 1, 0xCC000000);
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
        shapeCheck(); // (the shape for now first: a map shape left in ⚙️ never asks Google for nothing)
        carMap.setShown(CarLayout.hasMap(shape));
        main.removeCallbacks(quick);
        main.removeCallbacks(slow);
        main.post(quick);
        main.post(slow);
    }

    void stop() {
        running = false;
        carMap.setShown(false); // (the map's pictures let go while the screen is away)
        main.removeCallbacks(quick);
        main.removeCallbacks(slow);
        setDancing(false);
        lights.setActive(false);
        if (beatOn) { beatOn = false; beat.stop(); }
    }

    /** The activity is gone for good: the map's workers end. */
    void destroy() {
        stop();
        carMap.shutdown();
    }

    /** Settings were left: the theme, his colour, the dance choice, Jarvis's look may have changed. */
    void themeChanged() {
        portal.body.rig.setLook(prefs.bodyLook());
        portal.body.rig.setSkin(prefs.bodySkin());
        palette();
        showTalk();
        if (dayNightCheck()) { nightLight(); return; } // (his day / night theme just went on: all of it is fresh)
        nightLight();
        String k = deckKey();
        if (!k.equals(deckKey)) { // (his buttons, the dials, sizes, the map's lean changed in ⚙️: the shape laid out again)
            deckKey = k;
            dialKinds = CarDeck.three(prefs.carDials());
            textSizes();
            relayout();
        }
    }

    /** What the shape's layout depends on besides the shape (⚙️ choices). */
    private String deckKey() {
        return prefs.carButtons() + "|" + prefs.carBtnStyle() + "|" + prefs.carJarvis() + "|" + prefs.carText() + "|" + prefs.carDials() + "|" + prefs.carMapTilt()
                + "|" + prefs.carClock();
    }

    /** Every second: the speed, the sleep alert's question, moving / parked, the song and the dance. */
    private final Runnable quick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            speed();
            shapeCheck();
            if (carMap.shown()) {
                Location l = DriveService.last;
                boolean fresh = l != null && android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 60_000_000_000L;
                carMap.tick(fresh ? l : null, Drive.navState(), CarBox.speed(), DriveService.limitNowKmh);
            }
            awakeCheck();
            boolean m = CarBox.moving();
            if (m != wasMoving) {
                long now = System.currentTimeMillis();
                if (m && (stillSince == 0 || now - stillSince > 5 * 60_000L)) portal.body.rig.greet(); // 👋 off we go
                if (!m) stillSince = now;
                wasMoving = m; showTalk(); gear.setAlpha(m ? 0.35f : 1f); chat.setAlpha(m ? 0.35f : 1f); shapeBtn.setAlpha(m ? 0.35f : 1f);
                if (m && settingSheet) closeSheet(); // (a look chooser left open: gone when the car moves)
            }
            glances();
            portal.setBelted(m);
            tripCount();
            modeCheck();
            roadCheck();
            boardCheck();
            saverCheck();
            if (++ticks % 2 == 0) { musicCheck(); dialsCheck(); }
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
        clockFace.invalidate();
        date.setText(HomeScreen.partOfDay(h) + " · " + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]);
        boolean n = nightNow();
        if (n != night) { night = n; if (!dayNightCheck()) palette(); }
        nightLight();
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
        boolean parked = parkedNow();
        int m = songNow ? CarTheme.MUSIC : parked ? CarTheme.PARKED : CarTheme.DRIVING;
        if (m != mode) { int was = mode; mode = m; palette(); showTalk(); modeChanged(was, m); }
        rearCheck();
        String pref = prefs.carDance();
        boolean mayDance = songNow && !"off".equals(pref);
        boolean lightsOk = !prefs.carLowMotion() && (prefs.carEq() || prefs.carBeatLights()); // (🎚️ 💡 follow the beat too, even with the dance off)
        boolean wantBeat = (mayDance || songNow && lightsOk) && !CallControl.busyWithCall(); // (a call: nothing of its sound is looked at)
        if (wantBeat != beatOn) { beatOn = wantBeat; if (wantBeat) beat.start(); else beat.stop(); } // (the beat is followed for the whole song, not switched at every signal)
        lightsNow(); // (his choices only: never switched by moving / parked)
        boolean dance = mayDance && ("always".equals(pref) || !moving) && !(prefs.carLowMotion() && moving);
        setDancing(dance);
        if (dancing) danceTick();
    }

    /** While he dances (each second): the next step when the phrase is over or the song turns quiet / loud; the beat shown. */
    private void danceTick() {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = Math.min(3f, (now - danceTickAt) / 1000f);
        danceTickAt = now;
        BodyRig rig = portal.body.rig;
        if (rig.dancing()) { // (not while he listens or talks: the dance waits, and so do its phrases)
            int next = dj.tick(dt, beat.period(), beat.energy(), rnd, cheerNow);
            if (cheerNow[0]) rig.danceTap(); // 🙌 the song just got loud: a cheer first
            if (next >= 0) rig.queueDanceStyle(next);
        }
        int bpm = Math.round(beat.bpm() / 5f) * 5; // (to the nearest 5: the number doesn't flicker)
        if (bpm != bpmShown) { bpmShown = bpm; showTalk(); }
    }

    /** Stopped a minute and a half with the speed known (a song playing or not). */
    private boolean parkedNow() {
        return !CarBox.moving() && CarBox.speed() >= 0 && System.currentTimeMillis() - DriveService.lastMovingAt > PARKED_AFTER_MS;
    }

    private void setDancing(boolean on) {
        if (on == dancing) return;
        dancing = on;
        BodyRig rig = portal.body.rig;
        rig.setIdlePlays(mode == CarTheme.PARKED && !on); // (his own plays wait while he dances)
        if (on) {
            // a new dance: another step than the last song's; the slow sway to a slow song (then the steps change with it)
            rig.setDanceStyle(dj.start(beat.period(), beat.energy(), rnd));
            rig.setBeat(beat.period());
            rig.setDance(true);
            rig.poke();
            danceTickAt = android.os.SystemClock.uptimeMillis();
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
        if (s >= 0 && lastKmh >= 8 && lastKmh - s >= 4 && now - lastKmhAt < 1800) brakeUntil = now + 1500; // (slowing hard: the brake lights)
        if (s >= 0) { lastKmh = s; lastKmhAt = now; }
        hero.setSpeed(Math.max(0, s));
        hero.setBraking(now < brakeUntil);
        hero.setIndicator(now < indicatorUntil ? indicatorDir : 0);
        hero.setRain(rainNow);
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
        if (s != OrbView.IDLE && saverOn) saverShow(false); // (the steering button: the screen wakes with him)
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
            st = dancing ? (bpmShown > 0 ? "♪ Jarvis డాన్స్ · బీట్ " + bpmShown : "♪ Jarvis డాన్స్ చేస్తున్నాడు") : mode == CarTheme.MUSIC ? "♪ పాట వస్తోంది" : mode == CarTheme.PARKED ? "● ఆగి ఉన్నారు · నన్ను తాకండి 🙂" : "● నడుపుతున్నారు · అంతా బాగుంది";
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
    private long roadYesAt;
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
            boolean fresh = l != null && android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 60_000_000_000L; // (the box's own clock: a GPS time / box time mismatch doesn't matter)
            JSONObject h = !fresh ? null : Travel.home(c);
            if (h != null && h.has("lat")) {
                double km = MapMath.meters(l.getLatitude(), l.getLongitude(), h.optDouble("lat"), h.optDouble("lon")) / 1000;
                if (km < 0.3) { homeTrack.reset(); acOffered = false; } // (home: the next trip learns its own distances, and may ask again)
                else homeTrack.sample(now, km);
                if (km > 15) acOffered = false; // (far from home again: the next coming home may ask once more)
            }
        }
        homeMin = homeTrack.minutes(now); // (-1 as soon as no fresh GPS came for 30 s: never a stale "ఇంటికి N ని.")
        JSONObject nav = Drive.navState();
        int hm = homeMin;
        int navMin = nav == null ? -1 : CarRoad.minutesIn(nav.optString("trip"));
        if (hm > 0 && navMin >= 0) hm = navMin > hm + 15 ? -1 : navMin; // (the maps app goes past home: not home; else its minutes are better)
        // the AC question: once per coming home, only with his own AC's "on" link, never over the sleep alert's question
        if (!acOffered && hm > 0 && CarRoad.acTime(hm) && CarBox.moving() && acLink() && !awakeShown) { acOffered = true; acAt = now; } // (only on the way: never over the parked 🎲)
        boolean ac = acAt > 0 && now - acAt < 30_000;
        if (!ac) acAt = 0;
        int icon;
        String l1, l2;
        boolean navTurn = nav != null && !(nav.optString("next").isEmpty() && nav.optString("detail").isEmpty());
        boolean onMap = navTurn && carMap.shown(); // (the map shows the turn itself: this card shows the rest)
        if (onMap) turnCue(CarRoad.turnIcon(nav.optString("next") + " " + nav.optString("detail")), nav.optString("next"), nav.optString("detail"));
        if (ac) {
            icon = CarArt.Icon.SNOW;
            l1 = "ఇంట్లో " + acName + " ఆన్ చేయనా?";
            l2 = "🏠 ఇంటికి సుమారు " + hm + " ని.";
        } else if (navTurn && !onMap) {
            String next = nav.optString("next"), det = nav.optString("detail"), trip = nav.optString("trip");
            icon = CarRoad.turnIcon(next + " " + det);
            turnCue(icon, next, det);
            l1 = next.isEmpty() ? det : next;
            String rest = next.isEmpty() ? "" : det;
            l2 = trip.isEmpty() ? rest : rest.isEmpty() ? trip : rest + " · " + trip;
        } else if (mode == CarTheme.PARKED && !navTurn) { // 🅿️ the trip's numbers (no places), and the games for the passengers (not mid-trip)
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
        String yes = ac ? "✓ ఆన్" : "🎲 ఆటలు";
        if (!yes.contentEquals(roadYes.getText())) roadYesAt = System.currentTimeMillis();
        roadYes.setText(yes);
        roadYes.setVisibility(ac || games ? VISIBLE : GONE);
        roadNo.setVisibility(ac ? VISIBLE : GONE);
    }

    // ---- 🙂 P4: Jarvis with the road — he points at the turn, worries over the limit, a thumbs-up for an answered
    // sleep question, points at the diesel button when it runs low, stretches at two hours, waves at the start and the
    // end, dresses for the weather, wears sunglasses on a bright afternoon, keeps the day's festival, and glances at the
    // driver (on the right in an Indian car) or, in a parked game, at the passengers. While moving only the road's own.

    private String cueKey = "";
    private int lastKmh = -1, indicatorDir;
    private long lastKmhAt, brakeUntil, indicatorUntil;
    private boolean rainNow;
    private boolean overShown, fuelCued, stretchCued;
    private long underSince, stillSince, glanceAt;
    private String outfitNow = "", occasionNow = "";
    private final java.util.Random rnd = new java.util.Random();

    /** The maps app's turn is near (400 m or less): he points that way and looks, once per turn. */
    private void turnCue(int icon, String next, String det) {
        int at = CarRoad.metersIn(next);
        if (at < 0) at = CarRoad.metersIn(det);
        int dir = CarRoad.pointFor(icon, at);
        if (dir == 0) return;
        String key = icon + "|" + (next + " " + det).replaceAll("[0-9]+([.,][0-9]+)?\\s*(km|m|ft|మీ|కి\\.?\\s?మీ)?", "").trim();
        if (key.equals(cueKey)) return;
        cueKey = key;
        indicatorDir = dir; // (the car on the screen blinks that way for a few seconds)
        indicatorUntil = System.currentTimeMillis() + 8000;
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
        rainNow = at > 0 && now - at < 90 * 60_000L && ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95);
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
            acts.add(() -> { closeSheet(); if (host.request(ask, label)) { gameAt = System.currentTimeMillis(); boardCheck(); } });
        }
        openSheet("🎲 ప్రయాణ ఆటలు", CarBox.moving() ? null : "ఆగి ఉన్నప్పుడు ప్రశ్న మధ్యలో పెద్దగా కనిపిస్తుంది. 'ఆట ఆపు' అంటే ఆగుతుంది.", rows, acts, 2);
    }

    boolean gameOn() { return gameAt > 0 && System.currentTimeMillis() - gameAt < 10 * 60_000L; }

    /** Parked with a game on: the middle shows the question big (the gauge and clock come back the moment the car moves). */
    private void boardCheck() {
        boolean show = gameOn() && (mode == CarTheme.PARKED || CarLayout.STAGE.equals(shape)) && !CarBox.moving(); // (⑧'s 🎲: the question on the stage)
        if (show != (board.getVisibility() == VISIBLE)) {
            int v = show ? GONE : VISIBLE;
            for (View x : new View[]{gauge, hero, clock, clockFace, date, minis, driveChip, mapTilt}) x.setVisibility(v);
            board.setVisibility(show ? VISIBLE : GONE);
        }
        if (!show) return;
        String t = state == OrbView.LISTENING ? "🎙️ " + (heard.isEmpty() ? "మీ జవాబు చెప్పండి…" : heard)
                : state == OrbView.THINKING ? "💭 …" : said.isEmpty() || saidAt < gameAt ? "🎲 ఆట మొదలవుతోంది…" : said;
        if (!t.contentEquals(board.getText())) board.setText(t);
    }

    // ---- 🌙 P5: the parked screensaver (a big quiet clock, moved a little every minute), and the apps drawer

    private long lastTouch = System.currentTimeMillis(), saverMovedAt;
    private boolean saverOn, swallow;

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            lastTouch = System.currentTimeMillis();
            if (saverOn) { saverShow(false); swallow = true; return true; } // (the touch only wakes the screen)
        }
        if (swallow) { if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) swallow = false; return true; }
        return super.dispatchTouchEvent(ev);
    }

    private void saverCheck() {
        long now = System.currentTimeMillis();
        boolean want = prefs.carSaver() && mode == CarTheme.PARKED && !CarBox.moving() && now - lastTouch > 5 * 60_000L
                && state == OrbView.IDLE && now - idleSince > 60_000 && now - saidAt > 5 * 60_000L && !songNow && !awakeShown
                && sheet.getVisibility() != VISIBLE && ask.getVisibility() != VISIBLE && !gameOn() && acAt == 0;
        if (want != saverOn) saverShow(want);
        if (!saverOn) return;
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE), h12 = h % 12 == 0 ? 12 : h % 12;
        String t = String.format(Locale.ROOT, "%d:%02d", h12, m);
        if (!t.contentEquals(saverClock.getText())) saverClock.setText(t);
        String line = date.getText() + "   " + minis.getText();
        if (!line.contentEquals(saverLine.getText())) saverLine.setText(line);
        if (now - saverMovedAt > 60_000) { // (never the same pixels for long)
            saverMovedAt = now;
            saverBox.setTranslationX((rnd.nextFloat() - 0.5f) * W * 0.3f);
            saverBox.setTranslationY((rnd.nextFloat() - 0.5f) * H * 0.3f);
        }
    }

    private void saverShow(boolean on) {
        saverOn = on;
        if (on) {
            int ink = night ? 0xFF56677A : 0xFFB8C7D6;
            saverClock.setTextColor(ink);
            saverLine.setTextColor(CarTheme.shade(ink, 0.75f));
            saverClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(40, H / 3.2f));
            saverLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(14, H / 24f));
            saverMovedAt = 0;
            saver.setVisibility(VISIBLE);
            saver.bringToFront();
            if (awakeShown) awake.bringToFront();
            content.setVisibility(INVISIBLE); // (Jarvis and the rings rest under it: no frames drawn)
            ambient.setVisibility(INVISIBLE);
        } else {
            saver.setVisibility(GONE);
            content.setVisibility(VISIBLE);
            ambient.setVisibility(VISIBLE);
            lastTouch = System.currentTimeMillis();
        }
    }

    /**
     * ▦ The box's apps: his favourites first (Maps, the music app, …; a long press adds / removes one), and parked, all
     * of them. Moving: only the favourites (the rest wait for a stop).
     */
    private void showApps() {
        final Context c = getContext();
        sheet.removeAllViews();
        LinearLayout box = panel(tileBg, accent);
        TextView t = autoText(c, "▦ ఆప్స్", true, 50);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textMain);
        box.addView(t, new LinearLayout.LayoutParams(-1, Math.max(1, H / 11)));
        final boolean moving = CarBox.moving();
        TextView n = autoText(c, moving ? "నడుపుతుండగా ఇష్టమైనవే · అన్నీ: కారు ఆపాక" : "ఎక్కువసేపు నొక్కితే ⭐ ఇష్టమైనవిలో చేరుతుంది / తీసేస్తుంది", false, 26);
        n.setGravity(Gravity.CENTER);
        n.setTextColor(textSoft);
        box.addView(n, new LinearLayout.LayoutParams(-1, Math.max(1, H / 16)));
        final LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sc = new android.widget.ScrollView(c);
        sc.addView(grid, new android.widget.ScrollView.LayoutParams(-1, -2));
        box.addView(sc, new LinearLayout.LayoutParams(-1, Math.max(1, H * 55 / 100)));
        LayoutParams lp = panelParams();
        sheet.addView(box, lp);
        sheet.setVisibility(VISIBLE);
        sheet.bringToFront();
        if (awakeShown) awake.bringToFront();
        if (sheetGone != null) main.removeCallbacks(sheetGone);
        sheetGone = this::closeSheet;
        main.postDelayed(sheetGone, 40_000);
        final Context app = c.getApplicationContext();
        new Thread(() -> { // (the icons are read off the main thread)
            List<Object[]> list = CarApps.list(app, prefs, moving);
            main.post(() -> { if (sheet.getVisibility() == VISIBLE) fillApps(grid, list); });
        }, "car-apps").start();
    }

    private void fillApps(LinearLayout grid, List<Object[]> list) {
        Context c = getContext();
        int per = rightOnly ? 3 : 5, cell = Math.max(40, (roomW() * (rightOnly ? 94 : 80) / 100 - 40) / per);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % per == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final Object[] a = list.get(i); // {package, label, icon, favourite}
            LinearLayout cellBox = new LinearLayout(c);
            cellBox.setOrientation(LinearLayout.VERTICAL);
            cellBox.setGravity(Gravity.CENTER_HORIZONTAL);
            cellBox.setPadding(4, 8, 4, 8);
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageDrawable((android.graphics.drawable.Drawable) a[2]);
            cellBox.addView(iv, new LinearLayout.LayoutParams(cell * 55 / 100, cell * 55 / 100));
            TextView l = new TextView(c);
            l.setText(((Boolean) a[3] ? "⭐ " : "") + a[1]);
            l.setMaxLines(1);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            l.setTextColor(textMain);
            l.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11, cell / 7.5f));
            cellBox.addView(l, new LinearLayout.LayoutParams(-1, -2));
            cellBox.setOnClickListener(v -> tap(() -> {
                closeSheet();
                Intent it = getContext().getPackageManager().getLaunchIntentForPackage((String) a[0]);
                if (it == null) { toast("తెరవలేకపోయాను"); return; }
                getContext().startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }));
            cellBox.setOnLongClickListener(v -> {
                boolean fav = CarApps.toggleFavourite(prefs, (String) a[0]);
                toast(fav ? "⭐ ఇష్టమైనవిలో చేర్చాను" : "ఇష్టమైనవి నుంచి తీసేశాను");
                closeSheet();
                return true;
            });
            row.addView(cellBox, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (row != null) for (int k = list.size() % per; k != 0 && k < per; k++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        if (list.isEmpty()) {
            TextView e = autoText(c, "ఆప్స్ కనిపించలేదు", false, 30);
            e.setTextColor(textSoft);
            grid.addView(e, new LinearLayout.LayoutParams(-1, Math.max(1, H / 10)));
        }
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

    /**
     * Every 2 s: the song strip — the song's picture (the album art its app gives), its name, the singer · the app (never
     * more than that), and the keys while something plays: ▶ when it is paused, ⏸ while it plays.
     */
    private void musicCheck() {
        String t, sub, artKey = "";
        boolean keys, playing = false;
        try {
            JSONObject o = Music.panel(getContext());
            if (o.optBoolean("none")) {
                keys = false;
                boolean access = o.optBoolean("access", true);
                t = access ? "🎵 పాట లేదు" : "🎵 పాట పేరు కోసం";
                sub = access ? "ఇక్కడ నొక్కండి" : "నోటిఫికేషన్ యాక్సెస్ ఇవ్వండి";
            } else {
                keys = true;
                playing = o.optBoolean("playing");
                String title = o.optString("title"), artist = o.optString("artist"), app = o.optString("app");
                t = title.isEmpty() ? app : title;
                sub = title.isEmpty() ? (playing ? "ప్లే అవుతోంది" : "ఆగింది") : artist.isEmpty() ? app : artist + " · " + app;
                if (!o.optBoolean("jarvis")) artKey = app + "|" + title + "|" + artist; // (Jarvis's own radio has no picture)
                songCheck(app + "|" + title + "|" + artist);
            }
        } catch (Exception e) {
            keys = false;
            t = "🎵";
            sub = "";
        }
        artCheck(artKey);
        String key = t + "|" + sub + "|" + keys + playing;
        if (key.equals(musicKeyNow)) return;
        musicKeyNow = key;
        musicText.setText(t);
        songSub.setText(sub);
        playKey.setKind(playing ? CarArt.Icon.PAUSE : CarArt.Icon.PLAY);
        int v = keys ? VISIBLE : INVISIBLE;
        prevKey.setVisibility(v);
        playKey.setVisibility(v);
        nextKey.setVisibility(v);
    }

    private String songNowKey = "";

    /** Another song in the same run of music: its loudness measured afresh, and a new opening step to it. */
    private void songCheck(String key) {
        if (key.equals(songNowKey)) return;
        boolean had = !songNowKey.isEmpty();
        songNowKey = key;
        if (!had || !dancing) return;
        beat.newSong();
        portal.body.rig.queueDanceStyle(dj.start(beat.period(), 0, rnd));
    }

    private String artKeyNow = "", artMissKey = "";
    private int artMiss;

    /** The song changed: its picture, read off the main thread (asked again a few times: apps often send it a moment after the name). */
    private void artCheck(String key) {
        if (key.equals(artKeyNow)) return;
        artKeyNow = key;
        if (key.isEmpty()) { showArt(null); return; }
        final Context c = getContext().getApplicationContext();
        final int size = 256; // (sharp at any strip size: the strip may not be laid out yet when the first song is read)
        new Thread(() -> {
            android.graphics.Bitmap b = null;
            try { b = Music.art(c, size); } catch (Throwable ignored) {}
            final android.graphics.Bitmap got = b;
            main.post(() -> {
                if (!key.equals(artKeyNow)) return; // (another song meanwhile)
                showArt(got);
                if (got == null) {
                    if (!key.equals(artMissKey)) { artMissKey = key; artMiss = 0; }
                    if (++artMiss < 4) artKeyNow = "?" + key; // (the next look, 2 s later, asks again)
                }
            });
        }, "car-song-art").start();
    }

    private void showArt(android.graphics.Bitmap b) {
        artImg.setImageBitmap(b);
        artImg.setVisibility(b == null ? GONE : VISIBLE);
        artIcon.setVisibility(b == null ? VISIBLE : INVISIBLE);
        int col = 0;
        if (b != null) {
            try { // 🎨 the picture's own colour (a tiny copy is enough)
                android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(b, 24, 24, true);
                try {
                    int[] px = new int[24 * 24];
                    sm.getPixels(px, 0, 24, 0, 0, 24, 24);
                    col = CarDeck.artAccent(px);
                } finally {
                    if (sm != b) sm.recycle();
                }
            } catch (Exception ignored) {}
        }
        artColourTo(col);
    }

    /** 🎨 The screen's colour moves to the song picture's (0: back to the theme's) in a few soft steps. */
    private void artColourTo(int target) {
        final int seq = ++fadeSeq;
        if (target == 0 || !prefs.carArtColour()) {
            if (songAccent != 0) { songAccent = 0; palette(); }
            return;
        }
        final int from = songAccent != 0 ? songAccent : look != null ? look.accent : target;
        for (int k = 1; k <= 6; k++) {
            final float f = k / 6f;
            final boolean last = k == 6;
            main.postDelayed(() -> {
                if (seq != fadeSeq) return;
                songAccent = CarDeck.blend(from, target, f);
                if (mode != CarTheme.MUSIC) return;
                if (last) palette(); // (the whole screen once, at the end)
                else accentTo(songAccent); // (the steps: only what carries the colour)
            }, k * 140L);
        }
    }

    /** A step of the song colour's change: the colour on the parts that carry it (cheap), the rest at the end. */
    private void accentTo(int c) {
        if (look == null) return;
        look.accent = c;
        accent = c;
        stateText.setTextColor(c);
        for (CarArt.Icon i : tileIcons) i.setColour(c);
        for (CarArt.Icon k : new CarArt.Icon[]{prevKey, playKey, nextKey, artIcon, roadIcon}) k.setColour(c);
        gauge.invalidate();
    }

    /**
     * The strip's parts from its own size. Lying (one row): the picture a square, the words in the middle, ⏮ ⏯ ⏭ in their
     * own slots with room between. Standing (① ③'s wing): the picture big on top, the words under it, the keys across.
     */
    private void sizeStrip(int w, int h) {
        int inner = Math.max(1, h - musicStrip.getPaddingTop() - musicStrip.getPaddingBottom());
        int avail = Math.max(1, w - musicStrip.getPaddingLeft() - musicStrip.getPaddingRight());
        CarArt.Icon[] keys = {prevKey, playKey, nextKey};
        if (musicStrip.getOrientation() == LinearLayout.VERTICAL) {
            int keyH = Math.max(1, Math.round(inner * 0.22f));
            float tsz = Math.max(12, Math.min(avail * 0.085f, inner * 0.06f)) * textScale;
            int wordsH = Math.round(tsz * 2.8f);
            int art = Math.max(1, Math.min(Math.round(avail * 0.78f), inner - keyH - wordsH - Math.round(inner * 0.06f)));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(art, art);
            ap.gravity = Gravity.CENTER_HORIZONTAL;
            ap.topMargin = Math.round(inner * 0.03f);
            artBox.setLayoutParams(ap);
            songWords.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            songWords.setGravity(Gravity.CENTER);
            musicText.setGravity(Gravity.CENTER_HORIZONTAL);
            songSub.setGravity(Gravity.CENTER_HORIZONTAL);
            musicText.setTextSize(TypedValue.COMPLEX_UNIT_PX, tsz);
            songSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, tsz * 0.75f));
            songSub.setPadding(0, Math.round(tsz * 0.2f), 0, 0);
            keyRow.setLayoutParams(new LinearLayout.LayoutParams(-1, keyH));
            int slot = Math.max(1, avail / 3);
            int key = Math.max(1, Math.min(Math.round(slot * 0.5f), Math.round(keyH * 0.62f)));
            int play = Math.max(1, Math.min(Math.round(slot * 0.72f), Math.round(keyH * 0.95f)));
            for (int i = 0; i < 3; i++) {
                keys[i].setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
                keys[i].setFill((i == 1 ? play : key) / (float) Math.max(1, Math.min(slot, keyH)));
            }
            eqDraw.setReach(0.42f, 0); // (standing: the bars under the picture, behind the words and keys)
            return;
        }
        int art = Math.max(1, Math.min(Math.round(inner * 0.94f), Math.round(avail * 0.16f)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(art, art);
        ap.rightMargin = Math.round(art * 0.2f);
        ap.gravity = Gravity.CENTER_VERTICAL;
        artBox.setLayoutParams(ap);
        songWords.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        songWords.setGravity(Gravity.CENTER_VERTICAL);
        musicText.setGravity(Gravity.START);
        songSub.setGravity(Gravity.START);
        musicText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12, inner * 0.27f) * textScale);
        songSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10, inner * 0.20f) * textScale);
        songSub.setPadding(0, Math.round(inner * 0.06f), 0, 0);
        keyRow.setLayoutParams(new LinearLayout.LayoutParams(-2, -1));
        int slot = Math.max(1, Math.round(avail * 0.14f)); // (three slots: 42 % of the strip; each key is its slot's middle, room on both sides)
        int key = Math.max(1, Math.min(Math.round(slot * 0.62f), Math.round(inner * 0.55f)));
        int play = Math.max(1, Math.min(Math.round(slot * 0.86f), Math.round(inner * 0.85f)));
        for (int i = 0; i < 3; i++) { // (each key takes taps over its whole slot; the sign sits small in its middle)
            keys[i].setLayoutParams(new LinearLayout.LayoutParams(slot, -1));
            keys[i].setFill((i == 1 ? play : key) / (float) Math.max(1, Math.min(slot, inner)));
        }
        eqDraw.setReach(0.85f, musicStrip.getPaddingLeft() + art + ap.rightMargin); // (lying: the bars beside the picture, behind the words and keys)
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
            case "maps": JarvisMaps.open(getContext(), ""); break;
            case "games": showGames(); break;
            case "apps": showApps(); break;
            case "stage": if (!prefs.carStage() && !parkedOrSay()) return; setStage(!prefs.carStage()); break; // (on by a tap: parked; off any time)
            default: break;
        }
    }

    private static int iconOf(String k) {
        switch (k) {
            case "maps": return CarArt.Icon.MAP;
            case "way": return CarArt.Icon.WAY;
            case "music": return CarArt.Icon.MUSIC;
            case "fuel": return CarArt.Icon.FUEL;
            case "home": return CarArt.Icon.HOME;
            case "sleep": return CarArt.Icon.SLEEP;
            case "games": return CarArt.Icon.GAMES;
            case "apps": return CarArt.Icon.APPS;
            case "stage": return CarArt.Icon.STAR;
            default: return CarArt.Icon.MIC;
        }
    }

    /** The six buttons' signs and words for his six (or the shape's). */
    private void setButtons() {
        sixKinds = CarDeck.six(prefs.carButtons(), CarLayout.deep(shape));
        for (int i = 0; i < tiles.size() && i < 6; i++) {
            tileIcons.get(i).setKind(iconOf(sixKinds[i]));
            ((TextView) ((LinearLayout) tiles.get(i)).getChildAt(1)).setText(CarDeck.buttonLabel(sixKinds[i]));
        }
    }

    /** 🎛️ A button held (parked): which button sits in this place (one already elsewhere changes places with it). */
    private void chooseButton(int slot) {
        if (!parkedOrSay()) return;
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (String k : CarDeck.BUTTONS) {
            rows.add(new String[]{(k.equals(sixKinds[slot]) ? "✓ " : "") + CarDeck.buttonName(k)});
            acts.add(() -> {
                closeSheet();
                if (!parkedOrSay()) return; // (the car moved meanwhile)
                prefs.sp.edit().putString("car_buttons", CarDeck.join(CarDeck.put(sixKinds, slot, k))).apply();
                deckKey = deckKey();
                setButtons();
            });
        }
        rows.add(new String[]{"↺ మొదటి ఆరు బటన్లు"});
        acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().remove("car_buttons").apply(); deckKey = deckKey(); setButtons(); });
        openSheet("🎛️ ఈ స్థానంలో ఏ బటన్?", "ఇంకో చోట ఉన్న బటన్ ఎంచుకుంటే, రెండూ స్థానాలు మారతాయి.", rows, acts, 2);
        settingSheet = true;
    }

    /** 🔵 ②'s dial held (parked): what it shows. */
    private void chooseDial(int k) {
        if (!parkedOrSay()) return;
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (String d : CarDeck.DIALS) {
            rows.add(new String[]{(d.equals(dialKinds[k]) ? "✓ " : "") + CarDeck.dialName(d)});
            acts.add(() -> {
                closeSheet();
                if (!parkedOrSay()) return; // (the car moved meanwhile)
                dialKinds[k] = d;
                prefs.sp.edit().putString("car_dials", CarDeck.join(dialKinds)).apply();
                deckKey = deckKey();
                dialsCheck();
            });
        }
        openSheet("🔵 ఈ డయల్‌లో ఏం చూపించాలి?", null, rows, acts, 2);
        settingSheet = true;
    }

    /** 🧭 The speed held (parked): its look. */
    private void chooseGauge() {
        if (!parkedOrSay()) return;
        String[] ks = {"", "arc", "needle", "digits"}, ns = {"ఈ ఆకారానిదే / థీమ్‌దే", "వెలిగే వంపు (ఆర్క్)", "క్లాసిక్ సూది డయల్", "పెద్ద అంకెలు మాత్రమే"};
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (int i = 0; i < ks.length; i++) {
            final String g = ks[i];
            rows.add(new String[]{(g.equals(prefs.carGauge()) ? "✓ " : "") + ns[i]});
            acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().putString("car_gauge", g).apply(); gaugeStyle(); });
        }
        openSheet("🧭 స్పీడ్ మీటర్ ఎలా ఉండాలి?", null, rows, acts, 2);
        settingSheet = true;
    }

    /** 🧍 Jarvis held (parked): his size on this screen. */
    private void chooseJarvis() {
        if (!parkedOrSay()) return;
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (int i = 0; i < CarDeck.SIZES.length; i++) {
            final String z = CarDeck.SIZES[i];
            rows.add(new String[]{(z.equals(prefs.carJarvis()) ? "✓ " : "") + "🧍 " + CarDeck.SIZE_NAMES[i]});
            acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().putString("car_jarvis", z).apply(); deckKey = deckKey(); relayout(); });
        }
        openSheet("🧍 Jarvis ఎంత పెద్దగా?", null, rows, acts, 3);
        settingSheet = true;
    }

    private float lastBearing = -1;

    /** ②'s dials, every 2 s while they are on the screen (each from its own source: the diesel, the drive, OBD, the weather, GPS). */
    private void dialsCheck() {
        if (!CarLayout.CHROME.equals(shape)) return;
        Context c = getContext();
        CarDeck.Facts f = new CarDeck.Facts();
        try { f.rangeKm = CarFuel.rangeKm(c); } catch (Exception ignored) {}
        f.driveMin = lastDriveMin;
        if (ObdWatch.live() && ObdWatch.coolant != Integer.MIN_VALUE) f.coolant = ObdWatch.coolant;
        long now = System.currentTimeMillis();
        if (!Double.isNaN(ObdWatch.volts) && now - ObdWatch.voltsAt < 5 * 60_000L) f.volts = ObdWatch.volts;
        android.content.SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        if (now - sp.getLong("w_at", 0) < 3 * 3600_000L) { f.tempC = sp.getFloat("w_temp", 0); f.sky = HomeScreen.skyTe(sp.getInt("w_code", -1)); }
        Calendar cal = Calendar.getInstance();
        f.hour = cal.get(Calendar.HOUR_OF_DAY);
        f.minute = cal.get(Calendar.MINUTE);
        f.tripKm = DriveService.tripMeters / 1000.0;
        f.kmpl = kmpl();
        Location l = DriveService.last;
        if (l != null) {
            if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 2) lastBearing = l.getBearing();
            if (l.hasAltitude()) f.altitude = l.getAltitude();
        }
        f.bearing = lastBearing;
        f.limit = DriveService.limitNowKmh;
        f.speed = CarBox.speed();
        for (int i = 0; i < 3; i++) dials[i].set(CarDeck.dial(dialKinds[i], f));
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
        rows.add(new String[]{"🗺️ Jarvis Maps"});
        acts.add(() -> { closeSheet(); JarvisMaps.open(c, ""); });
        rows.add(new String[]{"🗺️ Google Maps తెరువు"});
        acts.add(() -> openMaps(c));
        openSheet("🧭 ఎక్కడికి?", home == null ? "ఇల్లు ఇంకా తెలియదు: ఇంటి దగ్గర 'ఇక్కడే మా ఇల్లు' నొక్కండి." : null, rows, acts, 2);
    }

    private void go(JSONObject p) {
        closeSheet();
        // where this trip goes (Drive's one place for it: the map draws its way once the maps app's trip shows)
        if (p.has("lat")) Drive.setDestAt(getContext(), p.optString("name"), p.optDouble("lat"), p.optDouble("lon"));
        else if (!p.optString("name").isEmpty()) Drive.setDest(getContext(), p.optString("name"));
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
        if (!asking && !alarm) { if (awakeShown) { awake.setVisibility(GONE); awakeShown = false; nightLight(); if (awakeKind == 1) thumbsUp(); } return; }
        int kind = alarm ? 2 : 1;
        if (awakeShown && kind == awakeKind || H == 0) return; // (shown already; not laid out yet: the next second)
        awakeShown = true;
        nightLight();
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
        settingSheet = false;
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

    // ================================================================ 🧩 the screen's shape: ⑥ classic, ④ map + cards, ⑦ half map, a trip's map screen

    /** Each second: his shape, or the map screen while a Google Maps trip runs (the classic shape, if he wants it). */
    private void shapeCheck() {
        JSONObject nav = Drive.navState();
        boolean trip = nav != null && !(nav.optString("next").isEmpty() && nav.optString("detail").isEmpty());
        boolean g2 = "g2".equals(prefs.carMapMode());
        String s = CarLayout.now(prefs.carLayout(), trip, prefs.carNavMap(), g2 && (besideNow || trip)); // (G2 on a trip: Google Maps' room on the left, a tap there brings it)
        if (g2 && besideNow) s = CarLayout.SIDE; // (he put Google Maps beside: Jarvis on the right whatever the shape)
        else if (!CarLayout.SIDE.equals(s) && prefs.carStage()) s = CarLayout.STAGE; // (⑧: only when he put it on, until he puts it off)
        arrange(s);
    }

    /** ⑧ on / off: his tap (✕, his button, the song strip, ⚙️) or his words. Nothing else turns it on or off. */
    void setStage(boolean on) {
        prefs.sp.edit().putBoolean("car_stage_on", on).apply();
        shapeCheck();
    }

    /**
     * 🗺️ G1 (Jarvis's map on this screen) or G2 (the Google Maps app beside Jarvis): his words ("Jarvis, Google Maps
     * పెట్టు" / "నీ మ్యాప్ పెట్టు") or the pill. What to say back.
     */
    String mapMode(String g) { return mapMode(g, true); }

    /** byVoice: "నీ మ్యాప్ పెట్టు" said on a screen without a map opens Jarvis Maps; the pill only goes back to his screen. */
    String mapMode(String g, boolean byVoice) {
        if (!"g1".equals(g)) return openBeside();
        boolean wasSide = CarLayout.SIDE.equals(shape);
        prefs.sp.edit().putString("car_map_mode", "g1").apply();
        besideNow = false;
        main.removeCallbacks(besideCheck);
        shapeCheck();
        if (!CarLayout.hasMap(shape)) {
            if (byVoice && !wasSide) { // (this shape has no map now: Jarvis Maps itself)
                JarvisMaps.open(getContext(), "");
                return "సరే, Jarvis Maps తెరిచాను.";
            }
            return act.isInMultiWindowMode() ? "సరే, మీ స్క్రీన్ పెట్టాను. పక్కన Google Maps కిటికీ వద్దంటే దాన్ని మూసేయండి." : "సరే, మీ స్క్రీన్ పెట్టాను.";
        }
        return act.isInMultiWindowMode() ? "సరే, మన మ్యాప్ పెట్టాను. పక్కన Google Maps కిటికీ వద్దంటే దాన్ని మూసేయండి." : "సరే, మన మ్యాప్ పెట్టాను.";
    }

    /**
     * G2: the Google Maps app in the box's own window on the left (split screen, or a freeform window there), Jarvis's
     * column on the right. Whether the box allowed it shows 2.5 s later: Jarvis still on the display, or not.
     */
    private String openBeside() {
        Context c = getContext();
        Intent i = c.getPackageManager().getLaunchIntentForPackage(CarSplit.MAPS);
        if (i == null) return "ఈ box లో Google Maps యాప్ లేదు: మన మ్యాప్‌లోనే ఉందాం.";
        prefs.sp.edit().putString("car_map_mode", "g2").apply();
        besideNow = true;
        shapeCheck(); // (Jarvis to the right first: the left is for Google Maps' window)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        android.os.Bundle opts = null;
        if (!act.isInMultiWindowMode() && W > 0 && H > 0) { // (a freeform window where the left room is; a box without them ignores this)
            int[] at = new int[2];
            getLocationOnScreen(at);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchBounds(new android.graphics.Rect(at[0], at[1], at[0] + Math.round(W * 0.62f), at[1] + H));
            opts = o.toBundle();
        }
        try {
            act.startActivity(i, opts);
        } catch (Exception e) {
            prefs.sp.edit().putString("car_map_mode", "g1").apply();
            besideNow = false;
            shapeCheck();
            return "Google Maps తెరవలేకపోయాను: మన మ్యాప్‌లోనే ఉందాం.";
        }
        besideAt = System.currentTimeMillis();
        main.removeCallbacks(besideCheck);
        main.postDelayed(besideCheck, 3000);
        return "సరే, Google Maps పక్కన పెడుతున్నాను.";
    }

    /** Jarvis still on the display: Google Maps is beside him. Not: the box gave Google Maps the whole screen (said, and G1 again). */
    private final Runnable besideCheck = this::besideSeen;

    /**
     * Looked at for 10 s after Google Maps was opened (a slow box draws it late): Jarvis's screens all went off the display
     * at any moment since → the box gave Google Maps the whole screen (said, and G1 again). Never covered → it is beside.
     */
    private void besideSeen() {
        if (!besideNow) return;
        if (CarBubble.allStoppedAt() < besideAt) {
            if (System.currentTimeMillis() - besideAt < 9000) main.postDelayed(besideCheck, 3500);
            return;
        }
        prefs.sp.edit().putString("car_map_mode", "g1").apply();
        besideNow = false;
        Toast.makeText(getContext().getApplicationContext(), "ఈ box Google Maps కి పక్క కిటికీ ఇవ్వలేదు: పూర్తి స్క్రీన్‌లో తెరిచాను. 🫧 బుడగ నొక్కితే Jarvis (మన మ్యాప్ G1). ⚙️ → 🧪 పక్క కిటికీ పరీక్ష చూడండి.",
                Toast.LENGTH_LONG).show();
    }

    /** The box's split screen began / ended: ended → Google Maps is no longer beside (his screen again); G2's room laid out again. */
    void windowChanged(boolean multi) {
        if (!multi) besideNow = false;
        String was = shape;
        shapeCheck();
        if (CarLayout.SIDE.equals(shape) && was.equals(shape)) relayout();
    }

    private static void detach(View v) {
        android.view.ViewParent g = v.getParent();
        if (g instanceof android.view.ViewGroup) ((android.view.ViewGroup) g).removeView(v);
    }

    /** The view into a column / row with its share of it. */
    private static void put(LinearLayout to, View v, float weight) {
        detach(v);
        to.addView(v, to.getOrientation() == LinearLayout.HORIZONTAL ? new LinearLayout.LayoutParams(0, -1, weight) : new LinearLayout.LayoutParams(-1, 0, weight));
    }

    /**
     * The same parts (Jarvis, the speed, the clock, the road card, the six buttons, the song) arranged for a shape; the
     * map comes in or goes; in ① ② ③ the parts sit on leaning cards. Every part keeps working as before: only where it
     * sits changes.
     */
    private void arrange(String s) {
        if (s.equals(shape)) return;
        shape = s;
        for (LinearLayout g : new LinearLayout[]{content, leftCol, midCol, rightCol, wingLIn, wingRIn, panelIn, panelTop, centreCol, stageLeft, stageMid, sideTop, sideText}) g.removeAllViews();
        mapTilt.removeAllViews();
        detach(carMap);
        boolean deep = CarLayout.deep(s);
        rightOnly = false;
        float j = CarDeck.jarvisScale(prefs.carJarvis()); // (Jarvis smaller / bigger: his window's share of its column)
        musicStrip.setOrientation(LinearLayout.HORIZONTAL);
        switch (s) {
            case CarLayout.COCKPIT: // ① Jarvis's wing, the middle card leaning back (the turn, the speed, his car on the road), the song's wing; chips and six round buttons under the middle
                put(content, wingL, 21); put(content, centreCol, 58); put(content, wingR, 21);
                put(wingLIn, portal, 46 * j); put(wingLIn, stateText, 7); put(wingLIn, clock, 15); put(wingLIn, minis, 7); put(wingLIn, driveChip, 6); put(wingLIn, chipRow, 10);
                put(centreCol, panel, 66); put(centreCol, alongScroll, 8); put(centreCol, tileGrid, 26);
                put(panelIn, panelTop, 30); put(panelIn, hero, 70); put(panelIn, board, 100);
                put(panelTop, roadCard, 46); put(panelTop, gauge, 54);
                musicStrip.setOrientation(LinearLayout.VERTICAL);
                put(wingRIn, musicStrip, 62); put(wingRIn, subText, 38);
                fillTiles(tileGrid, 0, 6, 6);
                break;
            case CarLayout.CHROME: // ② a big chrome speed dial in the middle, Jarvis on the left, three small dials on the right, three buttons each side
                put(content, leftCol, 25); put(content, centreCol, 47); put(content, rightCol, 28);
                put(leftCol, portal, 40 * j); put(leftCol, stateText, 6); put(leftCol, clock, 12); put(leftCol, minis, 6); put(leftCol, driveChip, 5); put(leftCol, subText, 11);
                put(leftCol, chipRow, 8); put(leftCol, tileGrid, 20);
                put(centreCol, alongScroll, 8); put(centreCol, gauge, 50); put(centreCol, hero, 16); put(centreCol, roadCard, 12); put(centreCol, musicStrip, 14);
                put(centreCol, board, 100);
                put(rightCol, dialCol, 80); put(rightCol, tileGrid2, 20);
                fillTiles(tileGrid, 0, 3, 3);
                fillTiles(tileGrid2, 3, 6, 3);
                break;
            case CarLayout.MAP3D: // ③ the map leaning back in the middle, six round buttons under it; Jarvis's wing; the speed, the road card and the song's wing
                put(content, wingL, 20); put(content, centreCol, 58); put(content, wingR, 22);
                put(wingLIn, portal, 44 * j); put(wingLIn, stateText, 7); put(wingLIn, clock, 14); put(wingLIn, minis, 7); put(wingLIn, driveChip, 6); put(wingLIn, subText, 12); put(wingLIn, chipRow, 10);
                mapTilt.addView(carMap, new FrameLayout.LayoutParams(-1, -1));
                put(centreCol, mapTilt, 74); put(centreCol, tileGrid, 26); put(centreCol, board, 100);
                musicStrip.setOrientation(LinearLayout.VERTICAL);
                put(wingRIn, gauge, 34); put(wingRIn, roadCard, 18); put(wingRIn, musicStrip, 48);
                fillTiles(tileGrid, 0, 6, 6);
                break;
            case CarLayout.STAGE: // ⑧ parked, a song on: Jarvis big under a light; 🗺️ 🎲 🎤 on the left; the clock, the beat and ✕ on the right; the song under him
                put(content, stageLeft, 10); put(content, stageMid, 60); put(content, rightCol, 30);
                for (View b : stageBtns) put(stageLeft, b, 1);
                put(stageMid, portal, 82); put(stageMid, musicStrip, 18); put(stageMid, board, 100);
                put(rightCol, clock, 13); put(rightCol, gauge, 14); put(rightCol, stateText, 6); put(rightCol, roadCard, 24); put(rightCol, subText, 17); // (the speed and a big road card: it may be on while he drives — turns, rain ahead, the AC question)
                put(rightCol, stageExit, 8); put(rightCol, chipRow, 9);
                break;
            case CarLayout.SIDE: // G2: Google Maps' own window on the left (the box's split / freeform window); Jarvis's column on the right
                boolean split = act.isInMultiWindowMode(); // (split screen: this window is already beside Google Maps — all of it is Jarvis's)
                rightOnly = !split; // (Google Maps' window may lie over the left: questions and the sleep alert come on the right)
                if (!split) put(content, mapGap, 62);
                put(content, rightCol, split ? 100 : 38);
                put(rightCol, sideTop, 24 * Math.max(1f, j)); put(rightCol, gauge, 14); put(rightCol, subText, 9); put(rightCol, roadCard, 11); put(rightCol, tileGrid, 25);
                put(rightCol, musicStrip, 12); put(rightCol, chipRow, 7); put(rightCol, board, 100);
                put(sideTop, portal, 42 * j); put(sideTop, sideText, 58);
                put(sideText, stateText, 18); put(sideText, clock, 34); put(sideText, minis, 18); put(sideText, mapPill, 30);
                fillTiles(tileGrid, 0, 6, 3);
                break;
            case CarLayout.MAPCARDS: // ④ the map, then a column of Jarvis + the speed + the clock, then the buttons
                put(content, carMap, 50); put(content, midCol, 21); put(content, rightCol, 29);
                put(midCol, portal, 30 * j); put(midCol, stateText, 6); put(midCol, gauge, 27); put(midCol, hero, 11);
                put(midCol, clock, 10); put(midCol, minis, 6); put(midCol, chipRow, 10); put(midCol, board, 100);
                put(rightCol, roadCard, 22); put(rightCol, tileGrid, 52); put(rightCol, musicStrip, 26);
                fillTiles(tileGrid, 0, 6, 3);
                break;
            case CarLayout.HALFMAP: // ⑦ half the screen the map, then Jarvis big, then the buttons (the speed is on the map)
            case CarLayout.NAV:     // the trip's map screen (G1): the same, the map a little wider
                boolean nav = CarLayout.NAV.equals(s);
                put(content, carMap, nav ? 56 : 52); put(content, leftCol, nav ? 18 : 19); put(content, rightCol, nav ? 26 : 29);
                put(leftCol, portal, 44 * j); put(leftCol, stateText, 8); put(leftCol, subText, 16); put(leftCol, clock, 13);
                put(leftCol, minis, 7); put(leftCol, chipRow, 12); put(leftCol, board, 100);
                put(rightCol, roadCard, 20); put(rightCol, tileGrid, 57); put(rightCol, musicStrip, 23);
                fillTiles(tileGrid, 0, 6, 3);
                break;
            default: // ⑥ the three columns as they always were
                put(content, leftCol, 27); put(content, midCol, 30); put(content, rightCol, 43);
                put(leftCol, portal, 58 * j); put(leftCol, stateText, 9); put(leftCol, subText, 22); put(leftCol, chipRow, 11);
                put(midCol, gauge, 47); put(midCol, hero, 19); put(midCol, clock, 13); put(midCol, date, 7); put(midCol, minis, 7);
                put(midCol, driveChip, 7); put(midCol, board, 100);
                put(rightCol, roadCard, 25); put(rightCol, tileGrid, 54); put(rightCol, musicStrip, 21);
                fillTiles(tileGrid, 0, 6, 3);
        }
        if ("analog".equals(prefs.carClock()) && clock.getParent() instanceof LinearLayout) { // 🕰️ the clock with hands where the clock sits
            LinearLayout par = (LinearLayout) clock.getParent();
            int idx = par.indexOfChild(clock);
            android.view.ViewGroup.LayoutParams lp = clock.getLayoutParams();
            par.removeView(clock);
            detach(clockFace);
            par.addView(clockFace, idx, lp);
        }
        wingL.setTilt(CarDeck.WING_L, deep ? 0.07f : 0);
        wingR.setTilt(CarDeck.WING_R, deep ? 0.07f : 0);
        panel.setTilt(CarDeck.BACK, 0.06f);
        mapTilt.setTilt(CarDeck.BACK, prefs.carMapTilt() ? 0.13f : 0);
        carMap.setShown(CarLayout.hasMap(s) && running);
        roadKey = ""; // (the road card again: the turn moved to the map, or back)
        stripW = stripH = 0; // (the song strip measured again: it may stand up now)
        setButtons();
        dressTiles();
        gaugeStyle();
        boxMargins();
        overlayRoom();
        dialsCheck();
        requestLayout();
        lights.invalidate(); // (the beat lights' edges where the cards are now)
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        nightLight(); // (the chat over it: the box's own light; back: his night light)
    }

    /** G2 with Google Maps' own window over the left: the choices, the questions and the sleep alert come in Jarvis's column, never under it. */
    private void overlayRoom() {
        int gap = W - roomW();
        for (View o : new View[]{sheet, ask, awake}) {
            if (!(o.getLayoutParams() instanceof LayoutParams)) continue;
            LayoutParams lp = (LayoutParams) o.getLayoutParams();
            if (lp.leftMargin == gap) continue;
            lp.leftMargin = gap;
            o.setLayoutParams(lp);
        }
    }

    /** The width the pop-ups have (all of it, or Jarvis's column in G2 beside Google Maps' window). */
    private int roomW() { return rightOnly && W > 0 ? W - Math.round(W * 0.62f) : W; }

    /** 🕐 The clock as he chose it: bold, thin, a glowing LED, or (in its place on the screen) the clock with hands. */
    private void clockStyle() {
        String cs = prefs.carClock();
        clock.setShadowLayer(0, 0, 0, 0);
        if ("thin".equals(cs)) clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        else if ("led".equals(cs)) {
            clock.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            clock.setTextColor(accent);
            clock.setShadowLayer(12, 0, 0, CarTheme.withAlpha(accent, 0xCC));
        } else clock.setTypeface(Typeface.DEFAULT_BOLD);
        clockFace.setColours(textMain, textSoft, accent);
    }

    /** 🧩 Parked: the next shape (his words do it any time: "Jarvis, ఆకారం మార్చు"). */
    private void nextShape() {
        if (CarBox.moving()) { toast("కారు ఆపాక నొక్కండి, లేదా \"Jarvis, ఆకారం మార్చు\" అనండి"); return; }
        toast(shapeSaid());
    }

    private String shapeSaid() {
        if (prefs.carStage()) { // (the stage covers the shape: it goes first, his shape shows)
            setStage(false);
            return "స్టేజ్ ఆపాను: " + CarLayout.name(prefs.carLayout());
        }
        if (besideNow && "g2".equals(prefs.carMapMode())) return "Google Maps పక్కన ఉంది: ముందు \"నీ మ్యాప్ పెట్టు\" అనండి";
        String next = CarDeck.nextShape(prefs.carLayout());
        prefs.sp.edit().putString("car_layout", next).apply();
        shapeCheck();
        boolean shown = CarLayout.valid(next).equals(shape);
        return CarLayout.name(next) + (shown ? "" : " (దారి అయిపోయాక కనిపిస్తుంది: ఇప్పుడు దారి మ్యాప్)");
    }

    /** 🚗 The box started with the car (a new trip): the cards come in turning, Jarvis waves. His switch; never with "fewer movements". */
    private long welcomeUntil;

    void welcome() {
        if (!prefs.carWelcome() || prefs.carLowMotion()) return;
        welcomeUntil = android.os.SystemClock.uptimeMillis() + 2000;
        portal.body.rig.greet();
        float d = getResources().getDisplayMetrics().density;
        int n = content.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = content.getChildAt(i);
            v.setCameraDistance(8000 * d);
            v.setAlpha(0f);
            v.setRotationY(i == 0 ? 55 : i == n - 1 ? -55 : 0);
            v.setRotationX(i == 0 || i == n - 1 ? 0 : 35);
            v.setTranslationY(30 * d);
            v.animate().alpha(1).rotationY(0).rotationX(0).translationY(0).setStartDelay(150 + i * 220L).setDuration(750)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
    }

    /** 🔆 At night Jarvis's screen as light as he chose (by day, and with "the box's", the box's own light). */
    private void nightLight() {
        try {
            float b = awakeShown || !isShown() ? -1f : CarDeck.nightLight(prefs.carNightDim(), night); // (the sleep alert and the chat: the box's own light)
            android.view.WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            if (lp.screenBrightness != b) { lp.screenBrightness = b; act.getWindow().setAttributes(lp); }
        } catch (Exception ignored) {}
    }

    /**
     * 🌗 His day and night themes (only when he set them): when the day / the night begins, that kept theme goes on once
     * (what he changes himself afterwards stays until the next change of day). True when one went on.
     */
    private boolean dayNightCheck() {
        int slot = CarDeck.dayNightSlot(prefs.carDayNight(), night);
        if (slot == 0) return false;
        String period = CarDeck.dayNightMark(night, slot, System.currentTimeMillis(), java.util.TimeZone.getDefault());
        if (period.equals(prefs.sp.getString("car_dn_applied", ""))) return false;
        java.util.Map<String, Object> m = CarDeck.back(prefs.sp.getString("car_mine_" + slot, ""));
        prefs.sp.edit().putString("car_dn_applied", period).apply();
        if (m == null) return false; // (that place is empty: nothing changes)
        prefs.applyLook(m);
        dialKinds = CarDeck.three(prefs.carDials());
        deckKey = deckKey();
        textSizes();
        palette();
        relayout();
        return true;
    }

    /** 🎚️ 💡 As he set them (⚙️, holding the song strip, or his words): on, dim, off — the same driving or parked. */
    private void lightsNow() {
        boolean low = prefs.carLowMotion();
        eqDraw.setOn(prefs.carEq() && !low);
        eqDraw.setCalm("dim".equals(prefs.carEqLevel()));
        lights.setActive(prefs.carBeatLights() && !low && songNow && !saverOn);
    }

    /** "Jarvis, ఈక్వలైజర్ ఆపు / మసకగా / పెట్టు", "బీట్ లైట్లు ఆపు / పెట్టు": done at once; what to say back. */
    String lightsSaid(String[] w) {
        if ("shape".equals(w[0])) return "సరే, " + shapeSaid() + ".";
        if ("stage".equals(w[0])) {
            boolean on = "on".equals(w[1]);
            setStage(on);
            return on ? "సరే, స్టేజ్ పెట్టాను." : "సరే, స్టేజ్ ఆపాను.";
        }
        if ("eq".equals(w[0])) {
            prefs.sp.edit().putString("car_eq_level", w[1]).apply();
            lightsNow();
            String r = "off".equals(w[1]) ? "సరే, ఈక్వలైజర్ ఆపాను." : "dim".equals(w[1]) ? "సరే, ఈక్వలైజర్ మసకగా పెట్టాను." : "సరే, ఈక్వలైజర్ పూర్తి రంగుల్లో పెట్టాను.";
            return !"off".equals(w[1]) && prefs.carLowMotion() ? r + " కానీ \"కదలికలు తక్కువ\" ఆన్‌లో ఉంది: అది ఆపితేనే కనిపిస్తుంది." : r;
        }
        boolean on = "on".equals(w[1]);
        prefs.sp.edit().putBoolean("car_beat_lights", on).apply();
        lightsNow();
        if (on && prefs.carLowMotion()) return "సరే, బీట్ లైట్లు పెట్టాను. కానీ \"కదలికలు తక్కువ\" ఆన్‌లో ఉంది: అది ఆపితేనే కనిపిస్తాయి.";
        return on ? "సరే, బీట్ లైట్లు పెట్టాను." : "సరే, బీట్ లైట్లు ఆపాను.";
    }

    /** 🎚️ 💡 The song strip held (parked): the equalizer and the beat lights, in one place. */
    private void chooseLights() {
        if (!parkedOrSay()) return;
        List<String[]> rows = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (int i = 0; i < CarDeck.EQ_LEVELS.length; i++) {
            final String lv = CarDeck.EQ_LEVELS[i];
            rows.add(new String[]{(lv.equals(prefs.carEqLevel()) ? "✓ " : "") + "🎚️ ఈక్వలైజర్: " + CarDeck.EQ_NAMES[i]});
            acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().putString("car_eq_level", lv).apply(); lightsNow(); });
        }
        for (boolean on : new boolean[]{true, false}) {
            rows.add(new String[]{(on == prefs.carBeatLights() ? "✓ " : "") + "💡 బీట్ లైట్లు: " + (on ? "ఆన్" : "ఆఫ్")});
            acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().putBoolean("car_beat_lights", on).apply(); lightsNow(); });
        }
        rows.add(new String[]{prefs.carStage() ? "🕺 స్టేజ్ ఆపు" : "🕺 Jarvis స్టేజ్ పెట్టు"});
        acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; setStage(!prefs.carStage()); });
        rows.add(new String[]{prefs.carArtColour() ? "🎨 పాట బొమ్మ రంగు ఆపు" : "🎨 పాట బొమ్మ రంగు పెట్టు"});
        acts.add(() -> { closeSheet(); if (!parkedOrSay()) return; prefs.sp.edit().putBoolean("car_art_colour", !prefs.carArtColour()).apply(); palette(); });
        openSheet("🎚️ ఈక్వలైజర్ · 💡 బీట్ లైట్లు · 🕺 స్టేజ్ · 🎨 రంగు", "నడుపుతున్నప్పుడు మాటతో: \"Jarvis, ఈక్వలైజర్ ఆపు / మసకగా / పెట్టు\", \"బీట్ లైట్లు ఆపు / పెట్టు\", \"స్టేజ్ పెట్టు / ఆపు\"", rows, acts, 3);
        settingSheet = true;
    }

    /** 💡 The cards' outlines on the screen now: leaning cards along their lean, round buttons and ②'s dials as circles. */
    private void outlines(List<android.graphics.Path> into) {
        pathUsed = 0;
        if (android.os.SystemClock.uptimeMillis() < welcomeUntil) return; // (the cards are still turning in)
        boolean roundTiles = CarDeck.round(prefs.carBtnStyle(), CarLayout.deep(shape));
        for (View t : tiles) addOutline(into, t, roundTiles ? 1 : 0);
        for (View v : new View[]{roadCard, musicStrip, carMap, wingLIn, wingRIn, panelIn, board, stageMid}) addOutline(into, v, 0);
        for (CarKit.Dial dl : dials) addOutline(into, dl, 2);
    }

    private android.graphics.Path nextPath() {
        if (pathUsed < pathPool.size()) { android.graphics.Path p = pathPool.get(pathUsed++); p.rewind(); return p; }
        android.graphics.Path p = new android.graphics.Path();
        pathPool.add(p);
        pathUsed++;
        return p;
    }

    /** round: 0 its rounded box, 1 a round button's circle (its top part), 2 a dial's circle. */
    private void addOutline(List<android.graphics.Path> into, View v, int round) {
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        float w = v.getWidth(), h = v.getHeight();
        float[] q;
        if (round == 1) {
            float r = Math.min(w * 0.47f, h * 0.64f * 0.47f);
            q = new float[]{w / 2f, h * 0.32f, w / 2f + r, h * 0.32f};
        } else if (round == 2) {
            float[] c = ((CarKit.Dial) v).circle();
            q = new float[]{c[0], c[1], c[0] + c[2], c[1]};
        } else {
            q = new float[]{0, 0, w, 0, w, h, 0, h};
        }
        int seen = toScreen(v, q);
        if (seen == 0) return;
        android.graphics.Path p = nextPath();
        if (round != 0) {
            p.addCircle(q[0], q[1], (float) Math.hypot(q[2] - q[0], q[3] - q[1]), android.graphics.Path.Direction.CW);
        } else if (seen == 1) { // (a flat card: its rounded corners)
            float rad = Math.max(10, Math.min(W, H) / 22f);
            p.addRoundRect(q[0], q[1], q[4], q[5], rad, rad, android.graphics.Path.Direction.CW);
        } else { // (a leaning card: its four corners as seen)
            p.moveTo(q[0], q[1]); p.lineTo(q[2], q[3]); p.lineTo(q[4], q[5]); p.lineTo(q[6], q[7]); p.close();
        }
        into.add(p);
    }

    /** Points in a view's own coordinates → this screen's, through any leaning card on the way. 0: not on this screen, 1: flat, 2: leaning. */
    private int toScreen(View v, float[] pts) {
        int res = 1;
        View cur = v;
        while (cur != this) {
            android.view.ViewParent par = cur.getParent();
            if (!(par instanceof View)) return 0;
            View pv = (View) par;
            float dx = cur.getLeft() + cur.getTranslationX() - pv.getScrollX(), dy = cur.getTop() + cur.getTranslationY() - pv.getScrollY();
            for (int i = 0; i + 1 < pts.length; i += 2) { pts[i] += dx; pts[i + 1] += dy; }
            if (pv instanceof CarKit.Tilt) {
                float[] m = ((CarKit.Tilt) pv).forward();
                if (m != null) {
                    res = 2;
                    for (int i = 0; i + 1 < pts.length; i += 2) { // (through the lean, in place)
                        float px = pts[i], py = pts[i + 1], z = m[6] * px + m[7] * py + m[8];
                        if (Math.abs(z) < 1e-6f) z = 1e-6f;
                        pts[i] = (m[0] * px + m[1] * py + m[2]) / z;
                        pts[i + 1] = (m[3] * px + m[4] * py + m[5]) / z;
                    }
                }
            }
            cur = pv;
        }
        return res;
    }

    /** The same shape laid out again (Jarvis's size, his buttons, the map's lean changed). */
    private void relayout() {
        String s = shape;
        shape = "";
        arrange(s);
    }

    /** The buttons from..to into a grid, perRow in each row. */
    private void fillTiles(LinearLayout grid, int from, int to, int perRow) {
        grid.removeAllViews();
        for (int i = from; i < to; i += perRow) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            for (int k = i; k < Math.min(to, i + perRow); k++) put(row, tiles.get(k), 1);
        }
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    /** One of ① ②'s "on my way" chips (Jarvis Maps finds it along the road). */
    private void chip(Context a, String label, Runnable r) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setOnClickListener(v -> tap(r));
        alongRow.addView(t, new LinearLayout.LayoutParams(-2, -1));
        alongChips.add(t);
    }

    /** The six buttons' look: glass squares, or chrome round buttons (the 3D shapes, or his choice). */
    private void dressTiles() {
        if (look == null) return;
        boolean roundNow = CarDeck.round(prefs.carBtnStyle(), CarLayout.deep(shape));
        float rad = Math.max(10, Math.min(Math.max(W, 600), Math.max(H, 300)) / 22f);
        for (int i = 0; i < tiles.size(); i++) {
            tiles.get(i).setBackground(roundNow ? CarKit.chromeButton(look, 0.64f) : CarArt.glass(look, rad, true));
            tileIcons.get(i).setFill(roundNow ? 0.5f : 1f);
        }
    }

    /** The speed's look: his choice, else the shape's (① big digits, ② the chrome needle, ③ the lit ring), else the theme's. */
    private void gaugeStyle() {
        if (look == null) return;
        String gs = prefs.carGauge();
        int g;
        if ("arc".equals(gs)) g = CarTheme.G_ARC;
        else if ("needle".equals(gs)) g = CarTheme.G_NEEDLE;
        else if ("digits".equals(gs)) g = CarTheme.G_DIGITS;
        else if (CarLayout.COCKPIT.equals(shape)) g = CarTheme.G_DIGITS;
        else if (CarLayout.CHROME.equals(shape)) g = CarTheme.G_NEEDLE;
        else if (CarLayout.MAP3D.equals(shape)) g = CarTheme.G_ARC;
        else if (CarLayout.SIDE.equals(shape) || CarLayout.STAGE.equals(shape)) g = CarTheme.G_DIGITS;
        else g = CarTheme.of(prefs.carTheme()).gauge;
        look.gauge = g;
        gauge.setLook(look);
    }

    /** The words that grow with their box, up to their biggest size × his words' size. */
    private void textSizes() {
        textScale = CarDeck.textScale(prefs.carText());
        for (java.util.Map.Entry<TextView, Integer> e : autoMax.entrySet()) {
            try { e.getKey().setAutoSizeTextTypeUniformWithConfiguration(9, Math.max(10, Math.round(e.getValue() * textScale)), 1, TypedValue.COMPLEX_UNIT_SP); }
            catch (Exception ignored) {}
        }
        stripW = stripH = 0;
        if (W > 0) subText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11, H / 24f) * textScale);
        musicStrip.requestLayout();
    }

    /** The cards' room around them (the six buttons, the road card, the song strip, the map, ① ② ③'s cards), from the screen's size. */
    private void boxMargins() {
        if (W <= 0 || H <= 0) return;
        int m = Math.max(3, Math.min(W, H) / 70);
        List<View> boxes = new ArrayList<>(tiles);
        boxes.add(roadCard);
        boxes.add(musicStrip);
        boxes.add(carMap);
        boxes.add(wingL);
        boxes.add(wingR);
        boxes.add(panel);
        boxes.add(mapTilt);
        boxes.add(mapGap);
        boxes.addAll(stageBtns);
        boxes.add(stageExit);
        boxes.add(mapPill);
        for (View t : boxes) {
            if (!(t.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue; // (not on the screen in this shape)
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            lp.setMargins(m, m, m, m);
            t.setLayoutParams(lp);
        }
    }

    // ================================================================ sizes and colours

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = w;
        H = h;
        int pad = Math.max(4, Math.min(w, h) / 34);
        content.setPadding(pad, pad, pad, pad);
        midCol.setPadding(pad / 2, 0, pad / 2, 0);
        boxMargins();
        overlayRoom();
        lights.invalidate();
        int cp = Math.max(4, Math.min(w, h) / 50);
        roadCard.setPadding(cp, cp / 2, cp, cp / 2);
        musicStrip.setPadding(cp, cp / 3, cp / 2, cp / 3);
        subText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11, h / 24f) * textScale);
        board.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(14, h / 15f));
        board.setMaxLines(7);
        board.setPadding(cp * 2, cp * 2, cp * 2, cp * 2);
        float rad = Math.max(10, Math.min(w, h) / 22f);
        dressTiles();
        roadCard.setBackground(CarArt.glass(look, rad, false));
        musicStrip.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{CarArt.glass(look, rad, false), eqDraw}));
        board.setBackground(CarArt.glass(look, rad, false));
        for (LinearLayout card : new LinearLayout[]{wingLIn, wingRIn, panelIn}) {
            card.setBackground(CarArt.glass(look, rad, false));
            card.setPadding(cp, cp, cp, cp);
        }
        for (TextView t : new TextView[]{stageExit, mapPill}) t.setPadding(cp, cp / 3, cp, cp / 3);
        mapGap.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(14, h / 26f));
        float ct = Math.max(12, h / 36f) * textScale; // ① ②'s chips row
        int cq = Math.max(3, h / 110);
        for (TextView t : alongChips) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, ct);
            t.setPadding(cq * 3, 0, cq * 3, 0);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            lp.setMargins(cq, cq, cq, cq);
            t.setLayoutParams(lp);
        }
        if (sheet.getVisibility() == VISIBLE) closeSheet(); // (its sizes were for the old screen)
    }

    private long wallAt = -1;
    private String heroKey = "";

    private String rearKey = "";
    private boolean fromBehind;

    /** 🚙 His own car's photos in place of the drawing (Settings → 🚙 కారు): read off the main thread, again only when changed. */
    private void heroPhoto() {
        final Context c = getContext();
        boolean on = CarPhoto.on(c, prefs);
        java.io.File f = CarPhoto.file(c);
        String key = on ? f.lastModified() + "|" + prefs.sp.getString("car_photo_marks", "") : "off";
        if (!key.equals(heroKey)) {
            heroKey = key;
            if (!on) hero.setPhoto(null, null, false);
            else new Thread(() -> {
                Object[] got = CarPhoto.load(c, prefs);
                main.post(() -> {
                    if (!key.equals(heroKey)) return; // (changed again meanwhile)
                    if (got == null) hero.setPhoto(null, null, false); // (unreadable: the drawing)
                    else hero.setPhoto((android.graphics.Bitmap) got[0], (float[]) got[1], (Boolean) got[2]);
                });
            }, "car-hero-photo").start();
        }
        // 🚗 the rear photo: the chase view while he drives
        fromBehind = CarPhoto.driveFromBehind(c, prefs);
        java.io.File rf = CarPhoto.rearFile(c);
        String rk = fromBehind ? rf.lastModified() + "|" + prefs.sp.getString("car_rear_marks", "") : "off";
        if (!rk.equals(rearKey)) {
            rearKey = rk;
            if (!fromBehind) hero.setRear(null, null, false);
            else new Thread(() -> {
                Object[] got = CarPhoto.loadRear(c, prefs);
                main.post(() -> {
                    if (!rk.equals(rearKey)) return;
                    if (got == null) hero.setRear(null, null, false);
                    else hero.setRear((android.graphics.Bitmap) got[0], (float[]) got[1], (Boolean) got[2]);
                });
            }, "car-hero-rear").start();
        }
        rearCheck();
    }

    /** From behind while he drives; parked, from the side (his side photo, or the drawing) — or still from behind when no side photo. */
    private void rearCheck() {
        boolean parked = parkedNow();
        hero.setRearNow(fromBehind && hero.hasRear() && (!parked || !hero.hasPhoto()));
    }

    /** 🖼️ His own photo (Settings → 🎨; kept only on this box, never in a backup): read again only when it changed. */
    private void wallpaper() {
        java.io.File f = new java.io.File(getContext().getFilesDir(), "car_wall.jpg");
        final long at = f.exists() ? f.lastModified() : 0;
        if (at == wallAt) return;
        wallAt = at;
        if (at == 0) { backdrop.setPhoto(null); return; }
        new Thread(() -> { // (read off the main thread)
            android.graphics.Bitmap b = null;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Throwable ignored) {}
            final android.graphics.Bitmap got = b;
            main.post(() -> { if (wallAt == at) backdrop.setPhoto(got); });
        }, "car-wall").start();
    }

    /** The theme's colours for the mode and the hour, put on everything. */
    private void palette() {
        CarTheme th = CarTheme.of(prefs.carTheme());
        look = th.look(mode, night, prefs.carAccent(), prefs.carArtColour() ? songAccent : 0);
        ambient.set(prefs.carBgMotion(), !prefs.carLowMotion(), look.accent, night ? th.nightAccent : th.musicAccent);
        gaugeStyle(); // (his own gauge style, else the shape's / the theme's)
        wallpaper();
        heroPhoto();
        tileBg = look.sheetFill;
        textMain = look.textMain;
        textSoft = look.textSoft;
        accent = look.accent;
        backdrop.setLook(look);
        carMap.setLook(look);
        gauge.setLook(look);
        hero.setLook(look);
        hero.setColour(prefs.carColour());
        hero.setNight(night);
        hero.setLowMotion(prefs.carLowMotion());
        portal.setLook(look);
        portal.setLowMotion(prefs.carLowMotion());
        portal.body.setSlow(night && !dancing);
        for (TextView t : new TextView[]{clock, subText}) t.setTextColor(textMain);
        clockStyle();
        shapeBtn.setTextColor(textSoft);
        stateText.setTextColor(accent);
        for (TextView t : new TextView[]{date, minis, chips}) t.setTextColor(textSoft);
        gear.setTextColor(textSoft);
        apps.setTextColor(textSoft);
        chat.setTextColor(textSoft);
        float rad = Math.max(10, Math.min(Math.max(W, 600), Math.max(H, 300)) / 22f);
        for (View t : tiles) ((TextView) ((LinearLayout) t).getChildAt(1)).setTextColor(textMain);
        dressTiles();
        for (LinearLayout card : new LinearLayout[]{wingLIn, wingRIn, panelIn}) card.setBackground(CarArt.glass(look, rad, false));
        for (CarKit.Dial d : dials) d.setLook(look);
        stageMid.setBackground(CarKit.spotlight(look));
        for (View b : stageBtns) { b.setBackground(CarKit.chromeButton(look, 1f)); ((CarArt.Icon) b).setColour(accent); }
        for (TextView t : new TextView[]{stageExit, mapPill}) {
            t.setTextColor(textMain);
            t.setBackground(round(look.light ? 0xCCFFFFFF : 0x99060A10, CarTheme.withAlpha(accent, 0xAA), 999));
        }
        mapGap.setTextColor(textSoft);
        mapGap.setBackground(round(CarTheme.withAlpha(accent, 0x10), CarTheme.withAlpha(accent, 0x55), 18));
        for (TextView t : alongChips) {
            t.setTextColor(textMain);
            t.setBackground(round(look.light ? 0xCCFFFFFF : 0x99060A10, CarTheme.withAlpha(accent, 0x99), 999));
        }
        for (CarArt.Icon i : tileIcons) i.setColour(accent);
        roadCard.setBackground(CarArt.glass(look, rad, false));
        musicStrip.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{CarArt.glass(look, rad, false), eqDraw}));
        board.setBackground(CarArt.glass(look, rad, false));
        board.setTextColor(textMain);
        portal.body.rig.setNightPlays(night);
        road1.setTextColor(textMain);
        road2.setTextColor(textSoft);
        musicText.setTextColor(textMain);
        songSub.setTextColor(textSoft);
        artIcon.setColour(accent);
        artBox.setBackground(round(CarTheme.withAlpha(accent, 0x22), CarTheme.withAlpha(accent, 0x55), 10));
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
        int rw = roomW();
        LayoutParams lp = new LayoutParams(rw > 0 ? rw * (rightOnly ? 94 : 80) / 100 : -1, -2, Gravity.CENTER);
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
