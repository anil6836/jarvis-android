package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 🎨 మెయిన్ స్క్రీన్ లుక్ (⚙️ → 🎨, or "Jarvis, లుక్ మార్చు"): the looks as moving pictures, ✓ on the one he has; a tap
 * shows it full size first (his own statuses, a sample chat), and only "✓ ఇదే పెట్టు" changes his screen. The old
 * screen is always the first tile (the way back). Below: 📄 pages follow the look, 📱 tilt, 🎙️ voice, 🔋 battery saver.
 * Phone only: the home tablet and the car box keep their own screens.
 */
public class LookChooserActivity extends Activity {
    private SharedPreferences sp;
    private Prefs prefs;
    private PageSkin skin;
    private String builtPage = "";
    private int builtTheme;
    private FrameLayout frame;
    private View page, preview;
    private HudDashboard previewDash;
    private LookScreen previewLook;

    /** The page's own parts the page theme paints (never the look pictures: they show the looks as they are). */
    private final java.util.List<View> skinMe = new java.util.ArrayList<>();

    private int dp(float v) { return Ui.dp(this, v); }

    static void open(Context c) {
        c.startActivity(new Intent(c, LookChooserActivity.class).addFlags(c instanceof Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.loadTheme(this);
        builtTheme = Ui.themeVersion;
        sp = getSharedPreferences(LookScreen.SP, MODE_PRIVATE);
        prefs = new Prefs(this);
        builtPage = PageSkin.stamp(this);
        skin = PageSkin.forPage(this);
        bars(skin != null ? skin.t.bgTop : Ui.BG_TOP, skin != null ? skin.t.bgBottom : Ui.BG_BOTTOM);
        if (skin != null && skin.t.lightPage) SettingsActivity.lightBars(getWindow());
        frame = new FrameLayout(this);
        page = buildPage();
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.loadTheme(this);
        if (preview == null && (builtTheme != Ui.themeVersion || !builtPage.equals(PageSkin.stamp(this)))) recreate();
        if (previewDash != null) previewDash.start();
        if (previewLook != null) previewLook.start();
    }

    @Override protected void onPause() {
        super.onPause();
        if (previewDash != null) previewDash.stop();
        if (previewLook != null) previewLook.stop();
    }

    @Override public void onBackPressed() {
        if (preview != null) { closePreview(); return; }
        super.onBackPressed();
    }

    private void bars(int top, int bottom) {
        getWindow().setStatusBarColor(top);
        getWindow().setNavigationBarColor(bottom);
    }

    private boolean phone() { return !prefs.homeMode() && !prefs.carMode(); }

    // ================================================================ the page of looks

    private View buildPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(skin != null ? skin.background() : new Ui.Aurora());
        root.setPadding(dp(16), dp(12), dp(16), 0);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 30, 0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, dp(4));
        back.setBackground(Ui.glass(this, 20));
        back.setContentDescription("వెనక్కి");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(12);
        head.addView(back, blp);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "🎨 మెయిన్ స్క్రీన్ లుక్", 21, 0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tt.addView(title);
        tt.addView(Ui.text(this, (MainLook.LOOKS.length - 1) + " కొత్త లుక్స్ + పాత రూపం · నొక్కితే ముందుగా చూపిస్తుంది", 13, Ui.MUTED));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);
        skinMe.add(head);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(28));
        if (!phone()) {
            TextView n = Ui.text(this, "ఈ లుక్స్ ఫోన్ కోసమే. ఈ పరికరం (" + (prefs.homeMode() ? "అమ్మగారి టాబ్లెట్" : "కారు box") + ") తన స్క్రీన్ తనదే: ఇక్కడ ఏది నొక్కినా ఏమీ మారదు.", 14, Ui.GOLD);
            n.setBackground(Ui.round(this, 0x14F2B24C, 0x66F2B24C, 12));
            n.setPadding(dp(12), dp(10), dp(12), dp(10));
            box.addView(n);
            skinMe.add(n);
        }
        String now = LookScreen.current(this, prefs);
        LinearLayout row = null;
        for (int i = 0; i < MainLook.LOOKS.length; i++) {
            String[] l = MainLook.LOOKS[i];
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = dp(i == 0 ? 8 : 14);
                box.addView(row, rl);
            }
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) tl.leftMargin = dp(12);
            row.addView(tile(l, i, l[0].equals(now)), tl);
        }
        // the seven of the next stage, shown as coming
        TextView later = Ui.text(this, "🔜 త్వరలో (తర్వాతి దశ): " + laterNames(), 13.5f, Ui.MUTED);
        later.setPadding(dp(2), dp(16), dp(2), dp(4));
        box.addView(later);
        skinMe.add(later);
        // the switches
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.VERTICAL);
        sw.setBackground(skin != null ? skin.card(20) : Ui.glass(this, 20));
        sw.setPadding(dp(14), dp(8), dp(14), dp(10));
        toggle(sw, "📄 పేజీలు కూడా ఈ లుక్ లోనే (సెట్టింగ్స్, అన్ని ఫీచర్లు, డ్యూటీ, ఫోటోలు)", "pages_follow_look");
        toggle(sw, "📱 ఫోన్ వంచితే వెనక భాగం 3D గా కదులుతుంది", "look_tilt");
        toggle(sw, "🎙️ Jarvis మాటకి, మీ గొంతుకి వెనక భాగం స్పందిస్తుంది", "look_voice");
        toggle(sw, "🔋 బ్యాటరీ సేవర్ లో కదలికలు ఆగుతాయి", "look_saver");
        TextView note = Ui.text(this, "Jarvis గోళం ఏ లుక్‌లోనూ మారదు. పాత రూపం ఎప్పుడూ మొదటి బొమ్మ: నొక్కి \"✓ ఇదే పెట్టు\" అంటే ఇప్పుడు ఉన్నట్టే మళ్ళీ.", 12.5f, Ui.MUTED);
        note.setPadding(0, dp(8), 0, 0);
        sw.addView(note);
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(-1, -2);
        swl.topMargin = dp(12);
        box.addView(sw, swl);
        skinMe.add(sw);
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (skin != null) for (View v : skinMe) skin.apply(v);
        return root;
    }

    private static String laterNames() {
        StringBuilder b = new StringBuilder();
        for (String[] l : MainLook.LATER) { if (b.length() > 0) b.append(", "); b.append(l[0]).append(' ').append(l[1]); }
        return b.toString();
    }

    private void toggle(LinearLayout box, String label, String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(14.5f);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setChecked(sp.getBoolean(key, true));
        s.setOnCheckedChangeListener((v, on) -> sp.edit().putBoolean(key, on).apply());
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One look as a small moving picture: its background, the orb, ✓ on his. */
    private View tile(String[] l, int i, boolean on) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout pic = new FrameLayout(this);
        pic.setClipToOutline(true);
        pic.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18)); }
        });
        HoloOrb orb = new HoloOrb(this, 50);
        orb.setClickable(false);
        int os = dp(MainLook.OLD.equals(l[0]) ? 52 : 70);
        FrameLayout.LayoutParams olp;
        if (MainLook.OLD.equals(l[0])) { // the old screen: the orb in the header, the tiles under it
            pic.setBackground(new Ui.Aurora());
            olp = new FrameLayout.LayoutParams(os, os, Gravity.TOP | Gravity.START);
            olp.setMargins(dp(10), dp(12), 0, 0);
            View tiles = new View(this);
            tiles.setBackground(Ui.glass(this, 8));
            FrameLayout.LayoutParams g = new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP);
            g.setMargins(dp(10), dp(90), dp(10), 0);
            pic.addView(tiles, g);
            TextView chat = Ui.text(this, "💬 ...", 11, Ui.MUTED);
            chat.setBackground(Ui.glass(this, 8));
            chat.setPadding(dp(8), dp(6), dp(8), dp(6));
            FrameLayout.LayoutParams c = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            c.setMargins(dp(10), 0, dp(10), dp(14));
            pic.addView(chat, c);
        } else {
            LookBg bg = new LookBg(l[0]);
            bg.animate = LookScreen.motionAllowed(this);
            pic.setBackground(bg);
            olp = new FrameLayout.LayoutParams(os, os, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            olp.topMargin = dp(MainLook.ORBIT.equals(l[0]) ? 58 : MainLook.LIQUID.equals(l[0]) ? 26 : 30);
            orb.addOnLayoutChangeListener((v, a, b2, c2, d, e, f, g, h) ->
                    bg.setAnchor(v.getLeft() + v.getWidth() / 2f, v.getTop() + v.getHeight() / 2f, v.getWidth() * 0.42f));
            if (MainLook.LIQUID.equals(l[0])) { // a glass circle round the orb, three glass tiles
                olp.gravity = Gravity.TOP | Gravity.END;
                olp.rightMargin = dp(14);
                for (int k = 0; k < 3; k++) {
                    View g = new View(this);
                    g.setBackground(Ui.round(this, 0x40FFFFFF, 0x88FFFFFF, 12));
                    FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(40), dp(46), Gravity.TOP | Gravity.START);
                    gl.setMargins(dp(12 + k * 46), dp(112), 0, 0);
                    pic.addView(g, gl);
                }
                TextView clock = Ui.text(this, new java.text.SimpleDateFormat("h:mm", java.util.Locale.ENGLISH).format(new java.util.Date()), 28, 0xFFFFFFFF);
                clock.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                cl.setMargins(dp(12), dp(36), 0, 0);
                pic.addView(clock, cl);
            } else { // small glass cards beside / round the orb
                boolean holo = MainLook.HOLO.equals(l[0]);
                int[][] at = holo ? new int[][]{{0, 26}, {1, 26}, {0, 80}, {1, 80}} : new int[][]{{0, 30}, {1, 18}, {0, 124}, {1, 116}};
                for (int[] p : at) {
                    View g = new View(this);
                    g.setBackground(holo ? Ui.round(this, 0x99103A55, 0xAA22D3EE, 4) : Ui.round(this, 0xA61E1A48, 0x669FA8FF, 8));
                    FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(34), dp(44), (p[0] == 0 ? Gravity.START : Gravity.END) | Gravity.TOP);
                    gl.setMargins(dp(8), dp(p[1]), dp(8), 0);
                    pic.addView(g, gl);
                }
            }
        }
        pic.addView(orb, olp);
        if (on) {
            TextView badge = Ui.text(this, "✓ ఇప్పుడు", 12, 0xFF041018);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setBackground(Ui.round(this, 0xFF5FE9FF, 0, 10));
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bl.setMargins(0, dp(8), dp(8), 0);
            pic.addView(badge, bl);
        }
        TextView see = Ui.text(this, "▶ చూడు", 12.5f, 0xFFFFFFFF);
        see.setBackground(Ui.round(this, 0x99000000, 0x66FFFFFF, 12));
        see.setPadding(dp(10), dp(3), dp(10), dp(4));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(8);
        pic.addView(see, sl);
        if (on) pic.setForeground(Ui.round(this, 0, 0xFF5FE9FF, 18));
        t.addView(pic, new LinearLayout.LayoutParams(-1, dp(196)));
        TextView name = Ui.text(this, (i == 0 ? "" : i + " · ") + l[1] + " " + l[2], 13.5f, Ui.TEXT);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, 0);
        if (on) name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.addView(name);
        skinMe.add(name);
        t.setContentDescription(l[2] + (on ? " (ఇప్పుడు ఇదే)" : "") + ": " + l[3]);
        t.setOnClickListener(v -> showPreview(l));
        return t;
    }

    // ================================================================ the full preview (nothing changes until ✓)

    private void showPreview(String[] l) {
        if (preview != null) closePreview();
        FrameLayout top = new FrameLayout(this);
        top.setClickable(true); // (taps stay on the preview)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), 0, dp(16), dp(150)); // (room for the panel at the bottom)
        LookScreen.Parts p = sampleParts(root, top);
        top.addView(root, new FrameLayout.LayoutParams(-1, -1));
        previewDash = p.dash;
        if (MainLook.OLD.equals(l[0])) {
            top.setBackground(new Ui.Aurora());
            bars(Ui.BG_TOP, Ui.BG_BOTTOM);
        } else {
            previewLook = new LookScreen(this, l[0], p, (q, w) -> {}, true);
            bars(previewLook.barTop(), previewLook.barBottom());
        }
        // the chip at the top and the panel at the bottom
        TextView chip = Ui.text(this, "👀 ముందుగా చూస్తున్నారు · " + l[1] + " " + l[2], 13, 0xFFFFFFFF);
        chip.setBackground(Ui.round(this, 0xCC0A1020, 0x88FFFFFF, 16));
        chip.setPadding(dp(12), dp(6), dp(12), dp(7));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = dp(64);
        top.addView(chip, cl);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xF00B1222, 0x66FFFFFF, 22));
        panel.setPadding(dp(16), dp(12), dp(16), dp(14));
        TextView n = Ui.text(this, l[1] + " " + l[2], 17, 0xFFFFFFFF);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(n);
        panel.addView(Ui.text(this, l[3] + " · ఇప్పుడు ఉన్నది: " + MainLook.label(LookScreen.current(this, prefs)), 13, 0xFFB8C4D6));
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(12), 0, 0);
        TextView backB = Ui.text(this, "‹ వెనక్కి", 16, 0xFFFFFFFF);
        backB.setGravity(Gravity.CENTER);
        backB.setBackground(Ui.round(this, 0x33FFFFFF, 0x66FFFFFF, 16));
        backB.setOnClickListener(v -> closePreview());
        btns.addView(backB, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView put = Ui.text(this, "✓ ఇదే పెట్టు", 16, 0xFFFFFFFF);
        put.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        put.setGravity(Gravity.CENTER);
        put.setBackground(Ui.grad(this, new int[]{0xFF22D3EE, 0xFF8B5CF6}, 16, android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT));
        put.setOnClickListener(v -> choose(l));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(50), 1);
        pl.leftMargin = dp(10);
        btns.addView(put, pl);
        panel.addView(btns);
        FrameLayout.LayoutParams pnl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pnl.setMargins(dp(12), 0, dp(12), dp(14));
        top.addView(panel, pnl);
        preview = top;
        frame.addView(top, new FrameLayout.LayoutParams(-1, -1));
        if (previewDash != null) previewDash.start();
        if (previewLook != null) previewLook.start();
    }

    private void closePreview() {
        if (previewLook != null) previewLook.destroy();
        if (previewDash != null) { previewDash.stop(); previewDash.onChange = null; }
        previewLook = null;
        previewDash = null;
        if (preview != null) frame.removeView(preview);
        preview = null;
        bars(skin != null ? skin.t.bgTop : Ui.BG_TOP, skin != null ? skin.t.bgBottom : Ui.BG_BOTTOM);
    }

    /** "✓ ఇదే పెట్టు": his main screen changes (the main screen builds itself again when he goes back to it). */
    private void choose(String[] l) {
        if (!phone()) { Toast.makeText(this, "ఈ లుక్స్ ఫోన్ కోసమే: ఈ పరికరం తన స్క్రీన్ తనదే", Toast.LENGTH_LONG).show(); return; }
        String was = LookScreen.current(this, prefs);
        sp.edit().putString("main_look", l[0]).putString("main_look_before", was).apply();
        Toast.makeText(this, "🎨 " + l[1] + " " + l[2] + (MainLook.OLD.equals(l[0]) ? ": ఇంతకుముందు లాగే" : ""), Toast.LENGTH_SHORT).show();
        finish();
    }

    /** A sample of the main screen's parts for the preview: the real orb and statuses, a sample chat. */
    private LookScreen.Parts sampleParts(LinearLayout root, FrameLayout top) {
        LookScreen.Parts p = new LookScreen.Parts();
        p.root = root;
        p.top = top;
        LinearLayout hud = new LinearLayout(this);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(0, dp(10), 0, dp(10));
        p.orb = new HoloOrb(this, 70);
        hud.addView(p.orb, new LinearLayout.LayoutParams(dp(78), dp(78)));
        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.VERTICAL);
        ident.setPadding(dp(10), 0, dp(8), 0);
        p.brand = Ui.mono(this, "JARVIS", 12.5f, Ui.C_CYAN);
        p.brand.setLetterSpacing(0.42f);
        ident.addView(p.brand);
        p.greeting = Ui.text(this, dayPart() + ", " + prefs.name(), 21, 0xFFFFFFFF);
        p.greeting.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.greeting.setSingleLine(true);
        ident.addView(p.greeting);
        p.status = Ui.text(this, "సిద్ధంగా ఉన్నాను, " + prefs.name(), 13.5f, Ui.MUTED);
        p.status.setSingleLine(true);
        ident.addView(p.status);
        hud.addView(ident, new LinearLayout.LayoutParams(0, -2, 1));
        p.ident = ident;
        int[] icons = {IconView.CAMERA, IconView.GRID, IconView.GEAR};
        p.icons = new View[3];
        for (int i = 0; i < 3; i++) {
            IconView ic = new IconView(this, icons[i], 0xFFFFFFFF);
            ic.setBackground(Ui.glass(this, 22));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            if (i < 2) lp.rightMargin = dp(8);
            hud.addView(ic, lp);
            p.icons[i] = ic;
        }
        p.hud = hud;
        root.addView(hud);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(Ui.glass(this, 24));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"💬 సంభాషణ", "🎯 మిషన్లు", "🧠 జ్ఞాపకాలు"};
        for (int i = 0; i < 3; i++) {
            TextView tb = Ui.text(this, names[i], 14.5f, i == 0 ? 0xFFFFFFFF : Ui.MUTED);
            tb.setGravity(Gravity.CENTER);
            tb.setSingleLine(true);
            tb.setPadding(0, dp(8), 0, dp(8));
            if (i == 0) tb.setBackground(Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 20, null));
            tabs.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        }
        p.tabs = tabs;
        root.addView(tabs);
        p.dash = new HudDashboard(this, prefs);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = dp(10);
        root.addView(p.dash, dl);
        FrameLayout stage = new FrameLayout(this);
        LinearLayout chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(0, dp(12), 0, dp(12));
        TextView you = Ui.text(this, "మీరు · ఈ లుక్ ఎలా ఉంది?", 13, 0xCCFFFFFF);
        chat.addView(you);
        TextView jr = Ui.text(this, "Jarvis › " + prefs.name() + ", ఇది ముందుగా చూపిస్తున్నది మాత్రమే. \"✓ ఇదే పెట్టు\" అంటేనే మారుతుంది.", 15, 0xFFFFFFFF);
        jr.setBackground(Ui.round(this, 0x661A2238, 0x55FFFFFF, 14));
        jr.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(-1, -2);
        jl.topMargin = dp(4);
        chat.addView(jr, jl);
        stage.addView(chat, new FrameLayout.LayoutParams(-1, -2));
        p.stage = stage;
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(0, dp(2), 0, dp(12));
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(8), 0, dp(10));
        for (String c : new String[]{"📷  Jarvis కెమెరా", "🗺️  Jarvis Maps", "🌅  శుభోదయం"}) {
            TextView ch = Ui.text(this, c, 14, 0xFFFFFFFF);
            ch.setSingleLine(true);
            ch.setBackground(Ui.round(this, 0x3322D3EE, 0x9922D3EE, 999));
            ch.setPadding(dp(13), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(ch, lp);
        }
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(chips);
        dock.addView(cs);
        TextView input = Ui.text(this, "＋   Jarvis ని అడగండి", 16, Ui.FAINT);
        input.setBackground(Ui.round(this, 0x1AFFFFFF, 0x33FFFFFF, 28));
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        dock.addView(input);
        p.dock = dock;
        p.chips = chips;
        root.addView(dock);
        return p;
    }

    private static String dayPart() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 4 && h < 12 ? "శుభోదయం" : h >= 12 && h < 17 ? "శుభ మధ్యాహ్నం" : h >= 17 && h < 21 ? "శుభ సాయంత్రం" : "శుభ రాత్రి";
    }

    @Override protected void onDestroy() {
        closePreview();
        super.onDestroy();
    }
}
