package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A new main-screen look on the phone (🛸 హోలో స్టేజ్, 💎 లిక్విడ్ గాజు, 🪐 కక్ష్య): the same parts the old screen has
 * (header, 📷 ▦ ⚙️, the three tabs, the face, the chat, all the chips, the message box) arranged again over the look's
 * moving background, with the orb in the middle of the stage and the status cards round it. Nothing is drawn over the
 * orb and nothing restyles it. Only on the phone (never the home tablet or the car box), only after his "✓ ఇదే పెట్టు".
 * The extras of his list come with it: a tap on a status opens its page (H1), a long press 📌 chooses which status sits
 * there (H2), the bike's liquid while it charges (H3), a long press on a chip puts it first (H4), the rain line (H5),
 * 📬 with counts only (H6).
 */
final class LookScreen implements SensorEventListener {
    /** What the look asks of the main screen: a question to Jarvis, like a chip. */
    interface Host { void ask(String prompt, String label); }

    /** The old screen's parts (built by MainActivity.buildUi, or samples for the chooser's preview). */
    static final class Parts {
        LinearLayout root, hud, ident, tabs, dock, chips;
        TextView brand, greeting, status;
        HoloOrb orb;
        HudDashboard dash;
        View faceBox, cameraBox, stage;
        View[] icons = new View[0];
        FrameLayout top;
    }

    static final String SP = "jarvis";

    final String look;
    final boolean compact;
    private final Activity a;
    private final Parts p;
    private final Host host;
    private final boolean preview;
    final LookBg bg;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View hero;
    private final List<LookCard> heroCards = new ArrayList<>();
    private HorizontalScrollView smallScroll;
    private LinearLayout smallRow;
    private final List<LookCard> smallCards = new ArrayList<>();
    private String smallKeys = "";
    private TextView heroStatus, clock, date, heroGreeting;
    private WaveBars wave;
    private int tab;
    private boolean keyboard, started, motion = true, covered, tooShort;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutWatch = this::placeLight;
    private SensorManager sensors;
    private float tx, ty;
    private final List<View> chipOrder = new ArrayList<>();
    /** 🛸 🎬 the car Jarvis on the projector (holo look only; shown only when he chose it and his pack is on the phone). */
    private HoloJarvisView film;
    private boolean filmOn;
    private int filmGen = -1;
    /** When the main screen last went away with the car Jarvis on it (his 👋 again only after a while). */
    private static long holoAway;

    LookScreen(Activity a, String look, Parts p, Host host, boolean preview) {
        this.a = a;
        this.look = look;
        this.p = p;
        this.host = host;
        this.preview = preview;
        this.sp = a.getSharedPreferences(SP, Context.MODE_PRIVATE);
        this.compact = !preview && FaceSight.faceOn(a);
        bg = new LookBg(look);
        p.top.setBackground(bg);
        motion = motionAllowed(a);
        bg.animate = motion;

        // the orb leaves the header for the stage (the same orb, never restyled)
        if (p.orb.getParent() != null) ((ViewGroup) p.orb.getParent()).removeView(p.orb);
        List<View> was = new ArrayList<>();
        for (int i = 0; i < p.root.getChildCount(); i++) was.add(p.root.getChildAt(i));
        p.root.removeAllViews();

        if (MainLook.LIQUID.equals(look)) buildLiquid(); else buildStage();
        buildSmallRow();
        if (MainLook.LIQUID.equals(look)) liquidHeader();

        // the old parts again, in this look's order (the old status tiles stay, hidden: they feed the cards)
        add(p.hud);
        if (MainLook.ORBIT.equals(look)) { add(p.tabs); add(hero, heroLp()); }
        else if (MainLook.HOLO.equals(look)) { add(hero, heroLp()); add(p.tabs); }
        else add(hero, heroLp());
        add(smallScroll, new LinearLayout.LayoutParams(-1, -2));
        for (View v : was) {
            if (v == p.hud || v == p.tabs) continue;
            if (v == p.dash) v.setVisibility(View.GONE);
            add(v);
        }
        if (MainLook.LIQUID.equals(look)) { // 💎 the tabs as a glass bar at the bottom
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.bottomMargin = Ui.dp(a, 10);
            add(p.tabs, tl);
            p.tabs.setBackground(Ui.round(a, 0x33FFFFFF, 0x66FFFFFF, 26));
        }
        if (p.dash != null) p.dash.onChange = this::refresh;

        // what Jarvis is doing: the header's line, shown under the orb too
        mirror(p.status, heroStatus);
        if (heroGreeting != null) mirror(p.greeting, heroGreeting);
        if (!preview) arrangeChips();
        p.top.getViewTreeObserver().addOnGlobalLayoutListener(layoutWatch);
        showTab(0);
        refresh();
    }

    private int dp(float v) { return Ui.dp(a, v); }

    private void add(View v) { add(v, null); }

    private void add(View v, ViewGroup.LayoutParams lp) {
        if (v == null) return;
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        if (lp != null) p.root.addView(v, lp); else p.root.addView(v);
    }

    private LinearLayout.LayoutParams heroLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, MainLook.LIQUID.equals(look) ? -2 : dp(heroHeight()));
        lp.topMargin = dp(MainLook.ORBIT.equals(look) ? 6 : 2);
        return lp;
    }

    private int heroHeight() {
        if (MainLook.HOLO.equals(look)) return compact ? 200 : 300;
        return compact ? 214 : 318;
    }

    private static void mirror(TextView from, TextView to) {
        if (from == null || to == null) return;
        to.setText(from.getText());
        from.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { to.setText(e); }
        });
    }

    // ================================================================ 🛸 / 🪐 the stage with the orb in the middle

    private void buildStage() {
        boolean holo = MainLook.HOLO.equals(look);
        FrameLayout f = new FrameLayout(a);
        f.setClipChildren(false);
        int orbS = dp(compact ? 108 : 150);
        int orbTop = holo ? dp(compact ? 4 : 22) : dp(compact ? 34 : 70);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(orbS, orbS, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        olp.topMargin = orbTop;
        f.addView(p.orb, olp);
        int cw = dp(compact ? 92 : 98);
        if (holo) { // 🎬 the car Jarvis's window on the projector, between the columns (hidden until he chose him and his pack is here)
            film = new HoloJarvisView(a);
            film.setVisibility(View.GONE);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, dp(compact ? 138 : 212), Gravity.TOP);
            flp.topMargin = dp(compact ? 0 : 4);
            flp.leftMargin = flp.rightMargin = cw;
            f.addView(film, flp);
            film.setListener(on -> p.orb.setVisibility(on ? View.INVISIBLE : View.VISIBLE)); // (the orb itself never changes: only hidden while he stands there)
            if (!preview) film.setOnClickListener(v -> p.orb.performClick()); // (a tap on him = a tap on the orb)
            else film.setClickable(false);
        }
        if (holo) { // two tilted glass columns beside the projector's light
            LinearLayout left = column(), right = column();
            for (int i = 0; i < 4; i++) {
                LookCard c = card(LookCard.KIND_HERO, 0, i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i >= 2) lp.topMargin = dp(compact ? 6 : 12);
                (i % 2 == 0 ? left : right).addView(c, lp);
            }
            float cam = 9000 * a.getResources().getDisplayMetrics().density;
            left.setCameraDistance(cam);
            right.setCameraDistance(cam);
            left.setPivotX(0);
            left.setRotationY(14);
            right.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setPivotX(r - l));
            right.setRotationY(-14);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(cw, -2, Gravity.START | Gravity.TOP);
            llp.topMargin = dp(compact ? 0 : 16);
            f.addView(left, llp);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(cw, -2, Gravity.END | Gravity.TOP);
            rlp.topMargin = dp(compact ? 0 : 16);
            f.addView(right, rlp);
        } else { // 🪐 the four cards round the orb's orbit
            int[][] at = compact ? new int[][]{{0, 10}, {1, 0}, {0, 112}, {1, 104}} : new int[][]{{0, 18}, {1, 0}, {0, 168}, {1, 156}};
            for (int i = 0; i < 4; i++) {
                LookCard c = card(LookCard.KIND_HERO, 0, i);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, -2, (at[i][0] == 0 ? Gravity.START : Gravity.END) | Gravity.TOP);
                lp.topMargin = dp(at[i][1]);
                f.addView(c, lp);
            }
        }
        LinearLayout sw = new LinearLayout(a);
        sw.setOrientation(LinearLayout.VERTICAL);
        sw.setGravity(Gravity.CENTER_HORIZONTAL);
        heroStatus = Ui.text(a, "", 13, holo ? 0xFFBFEFFF : 0xFFD6DAFF);
        heroStatus.setSingleLine(true);
        heroStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroStatus.setGravity(Gravity.CENTER);
        sw.addView(heroStatus, new LinearLayout.LayoutParams(-1, -2));
        wave = new WaveBars(a, holo ? 0xFF22D3EE : 0xFF8FA0FF, holo ? 0xFF9FF4FF : 0xFFD9C4FF);
        wave.motion = motion;
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(176), dp(compact ? 14 : 22));
        wl.topMargin = dp(4);
        sw.addView(wave, wl);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = holo ? dp(compact ? 22 : 40) : dp(compact ? 2 : 6);
        slp.leftMargin = slp.rightMargin = cw;
        f.addView(sw, slp);
        hero = f;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LookCard card(int kind, int tint, int slot) {
        LookCard c = new LookCard(a, look, kind, tint);
        c.slot = slot;
        c.compact(compact);
        if (kind != LookCard.KIND_SMALL) heroCards.add(c);
        if (!preview) {
            c.setOnClickListener(v -> open(c.key));
            c.setOnLongClickListener(v -> { if (c.slot >= 0) pin(c.slot); return true; });
        }
        return c;
    }

    // ================================================================ 💎 the clock, the orb in glass, three big tiles

    private void buildLiquid() {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        date = Ui.text(a, "", 13, 0xF2FFFFFF);
        date.setShadowLayer(dp(3), 0, dp(1), 0x55000000);
        txt.addView(date);
        clock = Ui.text(a, "", compact ? 44 : 62, 0xFFFFFFFF);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(dp(6), 0, dp(1), 0x44000000);
        txt.addView(clock);
        heroGreeting = Ui.text(a, "", 17, 0xFFFFFFFF);
        heroGreeting.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heroGreeting.setSingleLine(true);
        heroGreeting.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroGreeting.setShadowLayer(dp(3), 0, dp(1), 0x55000000);
        txt.addView(heroGreeting);
        heroStatus = Ui.text(a, "", 12.5f, 0xE6FFFFFF);
        heroStatus.setSingleLine(true);
        heroStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroStatus.setShadowLayer(dp(3), 0, dp(1), 0x55000000);
        txt.addView(heroStatus);
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout glass = new FrameLayout(a); // the orb in a round glass
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0x40FFFFFF, 0x14FFFFFF});
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setStroke(dp(2), 0x88FFFFFF);
        glass.setBackground(g);
        int gs = dp(compact ? 96 : 126);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(-1, -1);
        olp.setMargins(dp(7), dp(7), dp(7), dp(7));
        glass.addView(p.orb, olp);
        top.addView(glass, new LinearLayout.LayoutParams(gs, gs));
        v.addView(top);
        LinearLayout tiles = new LinearLayout(a);
        int[] tint = {0xFFFF4FA0, 0xFFFF7AB8, 0xFF7B5CFF};
        for (int i = 0; i < 3; i++) {
            LookCard c = card(LookCard.KIND_TILE, tint[i], i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(compact ? 84 : 104), 1);
            if (i > 0) lp.leftMargin = dp(8);
            tiles.addView(c, lp);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(compact ? 8 : 16);
        v.addView(tiles, tl);
        hero = v;
    }

    /** 💎: 📷 ▦ ⚙️ in one glass bar at the top right. */
    private void liquidHeader() {
        if (p.icons.length == 0) return;
        LinearLayout pill = new LinearLayout(a);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(a, 0x33FFFFFF, 0x77FFFFFF, 24));
        pill.setPadding(dp(4), dp(2), dp(4), dp(2));
        for (View ic : p.icons) {
            if (ic.getParent() != null) ((ViewGroup) ic.getParent()).removeView(ic);
            ic.setBackground(null);
            pill.addView(ic, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        p.hud.addView(pill);
    }

    // ================================================================ the small row (rain first), H5 / H6

    private void buildSmallRow() {
        smallScroll = new HorizontalScrollView(a);
        smallScroll.setHorizontalScrollBarEnabled(false);
        smallScroll.setClipToPadding(false);
        smallRow = new LinearLayout(a);
        smallRow.setPadding(0, dp(8), 0, dp(2));
        smallScroll.addView(smallRow);
    }

    /** The cards again from the dashboard's statuses (called after each of its refreshes). */
    void refresh() {
        if (p.dash == null) return;
        boolean m = motionAllowed(a) && !covered;
        if (m != motion) setMotion(m);
        List<String> order = MainLook.order(sp.getString("status_order", ""));
        int n = heroCards.size();
        for (int i = 0; i < n && i < order.size(); i++) heroCards.get(i).show(order.get(i), p.dash.stat(order.get(i)), motion);
        String[] rain = MainLook.rainWords(p.dash.rain());
        StringBuilder want = new StringBuilder(rain != null ? "rain" : "");
        for (int i = n; i < order.size(); i++) want.append(',').append(order.get(i));
        if (!want.toString().equals(smallKeys)) { // which small cards: built again only when that changes
            smallKeys = want.toString();
            smallRow.removeAllViews();
            smallCards.clear();
            if (rain != null) addSmall(-1);
            for (int i = n; i < order.size(); i++) addSmall(i);
        }
        for (LookCard c : smallCards) {
            if (c.slot < 0) { if (rain != null) c.set("🌧️ వర్షం", rain[0].replace("🌧️ ", ""), rain[1], Ui.GOLD, true); c.key = "rain"; }
            else if (c.slot < order.size()) c.show(order.get(c.slot), p.dash.stat(order.get(c.slot)), motion);
        }
    }

    private void addSmall(int slot) {
        LookCard c = card(LookCard.KIND_SMALL, MainLook.LIQUID.equals(look) ? 0xFFB06CFF : 0, slot);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        c.setMinimumWidth(dp(112));
        smallRow.addView(c, lp);
        smallCards.add(c);
    }

    // ================================================================ H1: a tap opens its page

    private void open(String key) {
        if (key == null) return;
        try {
            switch (key) {
                case "battery": startOr(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)); break;
                case "weather": case "rain": host.ask("ఇప్పుడు ఇక్కడ వాతావరణం ఎలా ఉంది? రాబోయే కొన్ని గంటల్లో వర్షం పడే అవకాశం ఉందా?", "⛅ వాతావరణం"); break;
                case "bike": host.ask("నా బైక్ బ్యాటరీ ఎంత ఉంది, ఇంకా ఎంత దూరం వెళ్తుంది? ఛార్జింగ్‌లో ఉంటే ఎప్పటికి పూర్తవుతుంది?", "🏍️ బైక్"); break;
                case "next": host.ask("నా రాబోయే రిమైండర్లు, ఈరోజు క్యాలెండర్ చెప్పు.", "⏰ రిమైండర్లు"); break;
                case "steps": host.ask("ఈరోజు నేను ఎన్ని అడుగులు నడిచాను?", "👣 అడుగులు"); break;
                case "money": host.ask("ఈ నెల ఇప్పటివరకు నా ఖర్చు ఎంత? ఎక్కువగా దేనికి అయింది?", "💰 ఈ నెల ఖర్చు"); break;
                case "duty": a.startActivity(new Intent(a, DutyActivity.class)); break;
                case "net": a.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)); break;
                case "wake": settings("వేక్ వర్డ్"); break;
                case "time": startOr(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), null); break;
                case "watch": settings("వాచ్"); break;
                case "car": settings("కారు"); break;
                case "milk": settings("లెక్కలు"); break;
                case "tablet": settings("ఇల్లు"); break;
                case "mail": { // H6: the 📬 box over the screen (it reads them when he taps); none there: the calls list
                    JarvisAccessibility s = JarvisAccessibility.service();
                    if (s != null && LaterMessages.count(a) > 0) LaterBox.show(s);
                    else startOr(new Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE), null);
                    break;
                }
                default: break;
            }
        } catch (Exception e) {
            Toast.makeText(a, "ఇది తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private void settings(String where) {
        a.startActivity(new Intent(a, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SECTION, where));
    }

    private void startOr(Intent first, Intent second) {
        try { a.startActivity(first); }
        catch (Exception e) {
            if (second == null) throw e;
            a.startActivity(second);
        }
    }

    // ================================================================ H2: 📌 which status sits here

    private void pin(int slot) {
        List<String> order = MainLook.order(sp.getString("status_order", ""));
        String now = slot < order.size() ? order.get(slot) : "";
        String[] names = new String[MainLook.STATUS.length + 1];
        for (int i = 0; i < MainLook.STATUS.length; i++) {
            String k = MainLook.STATUS[i][0];
            names[i] = (k.equals(now) ? "✓ " : "") + MainLook.statusName(k);
        }
        names[MainLook.STATUS.length] = "↩️ మొదటి క్రమం (అన్నీ ఇంతకుముందు లాగే)";
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("📌 ఇక్కడ ఏది చూపించాలి?")
                .setItems(names, (d, w) -> {
                    String saved = w == MainLook.STATUS.length ? "" : MainLook.put(sp.getString("status_order", ""), slot, MainLook.STATUS[w][0]);
                    sp.edit().putString("status_order", saved).apply();
                    refresh();
                })
                .setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ H4: a long press on a chip puts it first

    private void arrangeChips() {
        if (p.chips == null) return;
        for (int i = 0; i < p.chips.getChildCount(); i++) chipOrder.add(p.chips.getChildAt(i));
        for (View v : chipOrder) {
            if (!(v instanceof TextView)) continue;
            v.setOnLongClickListener(x -> {
                String key = String.valueOf(((TextView) x).getText());
                List<String> pins = pinnedChips();
                boolean was = pins.remove(key);
                if (!was) pins.add(0, key);
                sp.edit().putString("look_chips", String.join("\n", pins)).apply();
                placeChips();
                Toast.makeText(a, was ? "📌 తీసేశాను: మళ్ళీ దాని చోటికే" : "📌 ముందుకు పెట్టాను (మళ్ళీ నొక్కి పట్టుకుంటే తీసేస్తాను)", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        placeChips();
    }

    private List<String> pinnedChips() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("look_chips", "").split("\n")) if (!s.trim().isEmpty() && !out.contains(s)) out.add(s);
        return out;
    }

    /** His pinned chips first (in his order), the rest in their old order. */
    private void placeChips() {
        List<String> pins = pinnedChips();
        List<View> first = new ArrayList<>(), rest = new ArrayList<>();
        for (String k : pins) for (View v : chipOrder) if (v instanceof TextView && k.contentEquals(((TextView) v).getText()) && !first.contains(v)) first.add(v);
        for (View v : chipOrder) if (!first.contains(v)) rest.add(v);
        List<ViewGroup.LayoutParams> lps = new ArrayList<>();
        first.addAll(rest);
        for (View v : first) lps.add(v.getLayoutParams());
        p.chips.removeAllViews();
        for (int i = 0; i < first.size(); i++) p.chips.addView(first.get(i), lps.get(i));
    }

    // ================================================================ the screen's moments

    void showTab(int idx) {
        tab = idx;
        update();
    }

    void keyboard(boolean up) {
        if (up == keyboard) return;
        keyboard = up;
        update();
    }

    /** The stage and the small row: chat tab only, never while he types (the chat keeps its room). */
    private void update() {
        boolean on = tab == 0 && !keyboard && !tooShort;
        hero.setVisibility(on ? View.VISIBLE : View.GONE);
        smallScroll.setVisibility(on && !compact ? View.VISIBLE : View.GONE); // (the face on: its room; the statuses stay in the stage)
        if (p.status != null) p.status.setVisibility(on ? View.GONE : View.VISIBLE);
        if (MainLook.LIQUID.equals(look)) {
            if (p.brand != null) p.brand.setVisibility(on ? View.GONE : View.VISIBLE);
            if (p.greeting != null) p.greeting.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (!on) bg.setAnchor(-1, -1, 0);
        filmRun();
    }

    // ================================================================ 🛸 🎬 the car Jarvis on the projector

    /** His choice in 🎨: the car Jarvis when he chose him and his pack is on the phone (else the orb, as always); his colours. */
    private void syncFilm() {
        if (film == null) return;
        film.setBlue(!"own".equals(sp.getString("holo_colour", "blue")));
        if (!"film".equals(sp.getString("holo_who", "orb"))) {
            filmOn = false;
            filmGen = -1;
            film.setPack(null);
            film.setVisibility(View.GONE);
            p.orb.setVisibility(View.VISIBLE);
            return;
        }
        filmOn = true;
        film.setVisibility(View.VISIBLE);
        final int g = HoloPack.gen();
        if (g == filmGen && film.pack() != null) return;
        filmGen = g;
        film.holdForPack(); // (nothing starts from the old pack while the new one is read)
        final java.io.File dir = a.getFilesDir();
        new Thread(() -> {
            final HoloPack pk = HoloPack.load(dir);
            main.post(() -> { if (film != null && filmOn && filmGen == g) film.setPack(pk); });
        }, "holo-pack").start();
    }

    /** The clips play only while the stage is on the screen (chat tab, not typing, not behind Live, the app in front). */
    private void filmRun() {
        if (film == null) return;
        film.setMotion(motion);
        film.setRunning(filmOn && started && !covered && hero != null && hero.getVisibility() == View.VISIBLE);
    }

    /** Where the orb sits on the background (the projector's light, the orbits go to it). */
    private void placeLight() {
        int rh = p.root.getHeight(); // (a short screen, e.g. the phone on its side: the stage would leave no room for the chat)
        boolean shortNow = rh > 0 && rh < dp((MainLook.LIQUID.equals(look) ? (compact ? 200 : 250) : heroHeight()) + 400);
        if (shortNow != tooShort) { tooShort = shortNow; main.post(this::update); }
        if (hero == null || !hero.isShown() || p.orb.getVisibility() == View.GONE || p.orb.getWidth() == 0) { bg.setAnchor(-1, -1, 0); return; } // (the orb hidden for the car Jarvis: the light still goes to its place)
        int[] o = new int[2], t = new int[2];
        p.orb.getLocationInWindow(o);
        p.top.getLocationInWindow(t);
        float r = Math.min(p.orb.getWidth(), p.orb.getHeight()) * 0.42f;
        bg.setAnchor(o[0] - t[0] + p.orb.getWidth() / 2f, o[1] - t[1] + p.orb.getHeight() / 2f, r);
    }

    void level(float l) {
        if (!sp.getBoolean("look_voice", true)) return;
        bg.setLevel(l);
        if (wave != null) wave.level(l);
    }

    void state(int s) {
        if (wave != null) wave.awake = s == OrbView.LISTENING || s == OrbView.SPEAKING;
        if (s != OrbView.LISTENING && s != OrbView.SPEAKING) bg.setLevel(0);
        if (film != null) film.setState(s);
    }

    /** The full-screen Live view is over the look: nothing moves behind it. */
    void covered(boolean c) {
        if (c == covered) return;
        covered = c;
        setMotion(motionAllowed(a) && !c);
    }

    /** The screen goes: its layout watch goes too (the chooser's preview opens and closes many times). */
    void destroy() {
        stop();
        try { p.top.getViewTreeObserver().removeOnGlobalLayoutListener(layoutWatch); } catch (Exception ignored) {}
        if (p.dash != null) p.dash.onChange = null;
        if (film != null) { film.release(); film = null; }
    }

    void start() {
        boolean wasAway = !started;
        started = true;
        syncFilm();
        if (film != null && filmOn && wasAway && (preview || HoloPack.helloDue(System.currentTimeMillis(), holoAway))) film.hello(); // 👋 (the preview: always, to show him)
        setMotion(motionAllowed(a) && !covered);
        tickClock();
        if (motion && sp.getBoolean("look_tilt", true)) {
            try {
                sensors = a.getSystemService(SensorManager.class);
                Sensor g = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (g == null && sensors != null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_UI);
            } catch (Exception ignored) {}
        }
        refresh();
    }

    void stop() {
        if (started && filmOn && !preview) holoAway = System.currentTimeMillis();
        started = false;
        main.removeCallbacks(clockTick);
        try { if (sensors != null) sensors.unregisterListener(this); } catch (Exception ignored) {}
        sensors = null;
        filmRun();
    }

    private void setMotion(boolean m) {
        motion = m;
        bg.animate = m;
        bg.invalidateSelf();
        if (wave != null) { wave.motion = m; wave.invalidate(); }
        filmRun();
    }

    private final Runnable clockTick = this::tickClock;

    private void tickClock() {
        main.removeCallbacks(clockTick);
        if (clock == null) return;
        Date now = new Date();
        clock.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(a) ? "H:mm" : "h:mm", Locale.ENGLISH).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", new Locale("te", "IN")).format(now));
        if (started) main.postDelayed(clockTick, 60_000L - System.currentTimeMillis() % 60_000L + 200);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float x = Math.max(-1, Math.min(1, -e.values[0] / 5f)), y = Math.max(-1, Math.min(1, (e.values[1] - 6.5f) / 5f));
        tx += (x - tx) * 0.12f;
        ty += (y - ty) * 0.12f;
        bg.setTilt(tx, ty);
    }

    @Override public void onAccuracyChanged(Sensor s, int acc) {}

    /** The status bar's and navigation bar's colours for this look. */
    int barTop() { return MainLook.HOLO.equals(look) ? 0xFF06142A : MainLook.LIQUID.equals(look) ? 0xFF1C0838 : 0xFF0D0A2A; }
    int barBottom() { return MainLook.HOLO.equals(look) ? 0xFF01050C : MainLook.LIQUID.equals(look) ? 0xFF081C34 : 0xFF02010A; }

    /** Motion is allowed: not in battery saver (when his 🔋 switch is on) and not with Android's animations off. */
    static boolean motionAllowed(Context c) {
        try {
            if (android.provider.Settings.Global.getFloat(c.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return false;
        } catch (Exception ignored) {}
        if (!c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("look_saver", true)) return true;
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            return pm == null || !pm.isPowerSaveMode();
        } catch (Exception e) { return true; }
    }

    /** The phone's look now (the home tablet and the car box: always the old screen, theirs is their own). */
    static String current(Context c, Prefs prefs) {
        if (prefs.homeMode() || prefs.carMode()) return MainLook.OLD;
        return MainLook.valid(c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("main_look", MainLook.OLD));
    }
}
