package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V13: 🎓 tutorial mode. Jarvis (the body character) walks him through a feature step by step, in his voice: one short
 * line per step, "తెరువు" opens the real screen for that step, "తర్వాత" moves on, ▶️ plays the whole tour. Scripts live
 * here (no AI, no internet). Opened from Features → 🎓, or by voice: "కెమెరా ఎలా వాడాలి", "ట్యుటోరియల్".
 */
public class TourActivity extends Activity {
    static final String EXTRA_ID = "tour";

    static final class Step {
        final String say, open, openLabel;
        Step(String say, String open, String openLabel) { this.say = say; this.open = open; this.openLabel = openLabel; }
    }

    static final class Tour {
        final String id, emoji, title, keys;
        final Step[] steps;
        Tour(String id, String emoji, String title, String keys, Step... steps) { this.id = id; this.emoji = emoji; this.title = title; this.keys = keys; this.steps = steps; }
    }

    private static Step s(String say) { return new Step(say, null, null); }
    private static Step s(String say, String open, String label) { return new Step(say, open, label); }

    static final Tour[] TOURS = {
            new Tour("camera", "📷", "Jarvis కెమెరా HUD", "కెమెరా|camera|hud|రింగ్|ring",
                    s("Jarvis కెమెరా తెరవగానే, కనిపించే ప్రతి వస్తువు చుట్టూ ఒక రింగ్ వస్తుంది. పక్కన దాని పేరు, సుమారు దూరం, రంగు.", "camera", "📷 కెమెరా తెరువు"),
                    s("ఒక రింగ్‌ని నొక్కండి: అది లాక్ అవుతుంది, కింద కార్డులో దాని వివరాలు వస్తాయి. లాక్ అయినది కదిలితే కెమెరా దాన్ని ఫాలో అవుతుంది."),
                    s("మాటతో కూడా: \"ఆ ఎర్ర బండిని లాక్ చేయి\", \"ముప్పై ఎక్స్ జూమ్\", \"ఏం చేస్తున్నాడు?\", \"వదిలేయ్\"."),
                    s("పై మూలలో ⋯ మెనూలో: లోతైన స్కాన్, రాత్రి చూపు, ఏమి మారింది, HUD సెట్టింగ్స్, శబ్దాలు ఆన్ ఆఫ్."),
                    s("గుర్తుంచుకోండి: ఏదీ తనంతట తానే సేవ్ అవ్వదు, ఫోటో మీరు నొక్కితేనే. ఎవరినీ గుర్తించదు; మీరు పరిచయం చేసిన వాళ్ళ పేరు మాత్రమే చెబుతుంది.")),
            new Tour("zoom", "🔍", "లాక్, జూమ్, ఫాలో", "జూమ్|zoom|లాక్|lock|ఫాలో|follow",
                    s("కింద 1x, 3x, 5x, 10x, 30x, 100x బటన్లు. 10x వరకు ఫోన్ లెన్స్; ఆ పైన Jarvis డిజిటల్ జూమ్, కాబట్టి కొంచెం మసకగా ఉంటుంది.", "camera", "📷 కెమెరా తెరువు"),
                    s("లాక్ చేసి జూమ్ అంటే, Jarvis లాక్ అయిన దాని మీదే జూమ్ చేస్తుంది; అది పక్కకి వెళ్ళినా పట్టు వదలదు."),
                    s("ఎక్కువ జూమ్‌లో చేయి వణికితే, జైరో తో స్థిరంగా ఉంచుతుంది. 30x పైన \"ఆపి చూడు\" నొక్కితే ఒక ఫ్రేమ్ నిలిపి పెద్దగా చూపిస్తుంది."),
                    s("మూడు వరకు లాక్‌లు పెట్టొచ్చు. \"అన్నీ వదిలేయ్\" అంటే అన్నీ పోతాయి. మూడు నిమిషాలు ఏమీ అడగకపోతే లాక్ దానంతట అదే వదులుతుంది.")),
            new Tour("cases", "📁", "కేస్ బోర్డులు", "కేస్|case|బోర్డ్|board",
                    s("ఒక సమస్య గురించి స్కాన్‌లు, ఫోటోలు, నోట్స్, PDF లు అన్నీ ఒకే బోర్డు మీద. ఉదాహరణ: బైక్ బ్రేక్ శబ్దం.", "cases", "📁 కేసులు తెరువు"),
                    s("కొత్త కేసు పెట్టడానికి పైన ➕ నొక్కండి; పేరు ఇవ్వండి. కార్డులను వేలితో లాగి ఎక్కడైనా పెట్టొచ్చు, రెండు వేళ్ళతో జూమ్."),
                    s("🧠 నొక్కితే Jarvis కార్డుల మధ్య లింకులు గీసి, ఒక సారాంశం, తర్వాత చేయాల్సినవి చెబుతుంది. అది మాటల మీదే ఆధారం; బొమ్మలు పంపదు."),
                    s("🎙️ తో \"గత వారం సమస్య ఉన్నవి చూపించు\" అంటే మిగతావి మసకవుతాయి. కెమెరా స్కాన్ అయ్యాక \"కేసులో పెట్టు\" అనండి.")),
            new Tour("map", "🗺️", "మ్యాప్ బోర్డ్", "మ్యాప్|map",
                    s("మ్యాప్ బోర్డ్ మీద: మీ స్కాన్‌లు ఎక్కడ చేశారో, బండి ఎక్కడ పార్క్ చేశారో, ప్రమాద చోట్లు, ఇల్లు, రైడ్‌ల వేడి.", "map", "🗺️ మ్యాప్ తెరువు"),
                    s("ఒక పిన్ నొక్కితే దాని కార్డు; 📍 నొక్కితే అక్కడికి దారి. మ్యాప్ ఓపెన్ స్ట్రీట్ మ్యాప్ నుంచి; చూసిన టైల్స్ ముప్పై రోజులు ఫోన్‌లో ఉంటాయి."),
                    s("ఇది మీ ఫోన్ లోపలి సమాచారమే. మీ చోటు ఎవరికీ పంపదు.")),
            new Tour("hazards", "📍", "ప్రమాద చోట్లు", "ప్రమాద|hazard|గుంత|pothole",
                    s("రోడ్డు మీద గుంత కనిపిస్తే, \"ఇక్కడ గుంత ఉంది\" అనండి. Jarvis ఆ చోటు గుర్తుపెడతాడు."),
                    s("మళ్ళీ ఆ దారిలో వచ్చినప్పుడు తొంభై మీటర్ల ముందే చెబుతాడు, వాచ్ వైబ్రేట్ అవుతుంది. ఒకసారి చెప్పాక ఇరవై నిమిషాలు మళ్ళీ చెప్పడు."),
                    s("\"ప్రమాద చోట్లు ఏవి\" అంటే దగ్గరివి చెబుతాడు. మ్యాప్ బోర్డ్ మీద కూడా కనిపిస్తాయి. లొకేషన్ అన్నివేళలా అనుమతి కావాలి.", "map", "🗺️ మ్యాప్ తెరువు")),
            new Tour("watch", "⌚", "వాచ్ టైల్", "వాచ్|watch|టైల్|tile",
                    s("వాచ్‌లో Jarvis టైల్: ఆరు చిన్న కార్డులు, స్టేటస్, డ్యూటీ, నోట్, అనువాదం, నడక, స్కాన్.", "settings", "⚙️ సెట్టింగ్స్ తెరువు"),
                    s("సెట్టింగ్స్‌లో ⌚ వాచ్ టైల్‌లో వాచ్ టెస్ట్ ఉంది: నొక్కితే వాచ్ వైబ్రేట్ అయి ఒక కార్డు చూపిస్తుంది. రాకపోతే Galaxy Wearable లో Jarvis వాచ్ యాప్ ఉందా చూడండి."),
                    s("కెమెరాలో లాక్ చేసిన దాన్ని వాచ్‌కి పంపాలంటే \"వాచ్‌కి పంపు\" అనండి; అడిగితేనే వెళ్తుంది.")),
            new Tour("amma", "📟", "అమ్మ టాబ్లెట్‌కి మెసేజ్", "అమ్మ|amma|టాబ్లెట్|tablet",
                    s("\"అమ్మకి చెప్పు, నేను రాత్రికి వస్తాను\" అంటే, ఆ మాట ఇంట్లో టాబ్లెట్ మీద Jarvis అమ్మకి చదివి చెబుతాడు."),
                    s("ఆమె విన్నదా అని మీకు టిక్ వస్తుంది. ఆమె మాటలు మీకు రావు; ఆమె కావాలని జవాబు పంపితేనే వస్తుంది."),
                    s("సెట్టింగ్స్‌లో 📟 అమ్మ టాబ్లెట్ కార్డులో ఆమె రోజు టిక్‌లు, టాబ్లెట్ బ్యాటరీ, నెట్ కనిపిస్తాయి.", "settings", "⚙️ సెట్టింగ్స్ తెరువు")),
            new Tour("firstaid", "🩹", "ప్రథమ చికిత్స, సురక్షితమేనా", "ప్రథమ|first aid|గాయ|సురక్షిత|safe",
                    s("ఎవరైనా పడిపోతే, కెమెరా తెరిచి \"ఎవరో పడిపోయారు\" అనండి. కనిపించే దాన్ని బట్టి ఒక్కో అడుగు చెబుతుంది.", "camera", "📷 కెమెరా తెరువు"),
                    s("తీవ్రమైతే ముందు 108 బటన్ వస్తుంది; కాల్ మీరు నొక్కితేనే వెళ్తుంది. Jarvis డాక్టర్ కాదు, మందులు చెప్పదు."),
                    s("\"ఇది సురక్షితమేనా\" అంటే చోటు చూసి ఆకుపచ్చ, నారింజ, ఎరుపు అని, ఏ జాగ్రత్త తీసుకోవాలో చెబుతుంది.")),
            new Tour("holo", "🧊", "3D మోడల్, వివరించు టూర్", "3d|హోలో|holo|మోడల్|model|వివరించు",
                    s("కెమెరాలో ◉ బటన్ నొక్కి పట్టుకుంటే ఒక పరికరం 3D లో పొరలుగా వస్తుంది. ఒక గది కోసం ⋯ మెనూలో 🧊 స్థలం మోడల్: నిలబడి నెమ్మదిగా తిరగండి.", "camera", "📷 కెమెరా తెరువు"),
                    s("3D తెరలో ▶️ వివరించు నొక్కితే Jarvis ఒక్కో పొర తీసి చూపిస్తూ చెబుతాడు. వేలితో తిప్పండి, రెండు వేళ్ళతో జూమ్."),
                    s("ఇది ఫోటోల నుంచి అంచనా: చిన్న భాగాలు మిస్ అవ్వొచ్చు.")),
            new Tour("forecast", "🔮", "ఎప్పుడు అవుతుంది, ఇలాగే కొనసాగితే", "ఎప్పుడు|forecast|కొనసాగితే|పవర్|power|సమీక్ష|review",
                    s("\"స్టేటస్\" అంటే ఇప్పుడు నడుస్తున్నవి ఎప్పుడు అవుతాయో కూడా చెబుతాడు: ఫోన్ ఛార్జ్, బైక్ ఛార్జ్, కుక్కర్ చివరి విజిల్, డ్యూటీ ముగింపు, తర్వాతి మందు."),
                    s("\"ఇలాగే కొనసాగితే\" అంటే బ్యాటరీ, మందుల స్టాక్, నెల ఖర్చు, నిద్ర, బైక్ సర్వీస్ కి.మీ ధోరణి చెబుతాడు. సాయంత్రం ఎరుపు ఉంటే తనే ఒక మాట చెబుతాడు."),
                    s("\"ఈరోజు పవర్ వాడకం\" అంటే కెమెరా, రైడ్ GPS, మైక్ ఎంత తిన్నాయో అంచనా. \"ఈ వారం వ్యాయామ సమీక్ష\", \"సర్వీస్ చెక్‌లిస్ట్\" కూడా అడగండి.", "settings", "⚙️ సెట్టింగ్స్ తెరువు"))};

    static Tour byId(String id) { for (Tour t : TOURS) if (t.id.equals(id)) return t; return null; }

    /** The tour his words point at ("కెమెరా ఎలా వాడాలి" → camera), or null. */
    static Tour find(String words) {
        String t = words == null ? "" : words.toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        for (Tour x : TOURS) if (t.matches(".*(" + x.keys + ").*")) return x;
        return null;
    }

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, TourActivity.class).putExtra(EXTRA_ID, id == null ? "" : id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- the screen

    private final Handler main = new Handler(Looper.getMainLooper());
    private Tour tour;
    private int at;
    private boolean auto, speaking;
    private long spokeAt;
    private BodyView body;
    private TextView stepText, counter, playBtn, openBtn;
    private final List<View> dots = new ArrayList<>();
    private final Runnable watch = this::watchVoice;
    private Runnable pendingSay;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        tour = byId(getIntent().getStringExtra(EXTRA_ID));
        if (tour == null) list(); else show();
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        return page;
    }

    private LinearLayout bar(String title, Runnable back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView x = Ui.text(this, back == null ? "✕" : "‹", 22, 0xFFFFFFFF);
        x.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        x.setOnClickListener(v -> { if (back == null) finish(); else back.run(); });
        bar.addView(x);
        TextView t = Ui.mono(this, title, 14, Ui.CYAN);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    /** The list of tours. */
    private void list() {
        stopTalk();
        LinearLayout page = page();
        page.addView(bar("🎓 ఈ ఫీచర్ ఎలా వాడాలి", null));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        l.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 24));
        l.addView(Ui.text(this, "ఒకటి ఎంచుకోండి: Jarvis ఒక్కో అడుగు చెబుతూ చూపిస్తాడు. మాటతో కూడా: \"కెమెరా ఎలా వాడాలి\".", 14, Ui.MUTED));
        for (Tour t : TOURS) {
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
            card.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Ui.dp(this, 8), 0, 0);
            card.setLayoutParams(lp);
            card.addView(Ui.text(this, t.emoji, 24, 0xFFFFFFFF));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(this, 12), 0, 0, 0);
            TextView tt = Ui.text(this, t.title, 16, 0xFFFFFFFF);
            tt.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(tt);
            col.addView(Ui.text(this, t.steps.length + " అడుగులు", 12.5f, Ui.MUTED));
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            card.setOnClickListener(v -> { tour = t; at = 0; auto = false; show(); });
            l.addView(card);
        }
        sv.addView(l);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    /** One tour: the character on top, the step card, the buttons. */
    private void show() {
        LinearLayout page = page();
        page.addView(bar(tour.emoji + " " + tour.title, () -> { tour = null; list(); }));
        FrameLayout stage = new FrameLayout(this);
        body = new BodyView(this);
        Prefs p = new Prefs(this);
        body.rig.setLook(p.bodyLook());
        body.rig.setSkin(p.bodySkin());
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1.1f));
        int pad = Ui.dp(this, 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 16));
        LinearLayout dotRow = new LinearLayout(this);
        dotRow.setGravity(Gravity.CENTER);
        dots.clear();
        for (int i = 0; i < tour.steps.length; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8));
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), Ui.dp(this, 6));
            d.setLayoutParams(lp);
            dots.add(d);
            dotRow.addView(d);
        }
        box.addView(dotRow);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        card.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x88), 16));
        counter = Ui.mono(this, "", 12, Ui.CYAN);
        card.addView(counter);
        stepText = Ui.text(this, "", 16, 0xFFFFFFFF);
        stepText.setLineSpacing(0, 1.25f);
        stepText.setPadding(0, Ui.dp(this, 6), 0, 0);
        stepText.setMinLines(3);
        card.addView(stepText);
        box.addView(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView prev = Ui.pill(this, "‹ ముందు");
        prev.setOnClickListener(v -> { auto = false; go(at - 1); });
        row.addView(prev);
        playBtn = Ui.pill(this, "▶️ అన్నీ");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        playBtn.setLayoutParams(plp);
        playBtn.setOnClickListener(v -> { if (auto) { auto = false; stopTalk(); playBtn.setText("▶️ అన్నీ"); } else { auto = true; playBtn.setText("⏸"); go(at); } });
        row.addView(playBtn);
        TextView next = Ui.pill(this, "తర్వాత ›");
        next.setOnClickListener(v -> { auto = false; playBtn.setText("▶️ అన్నీ"); go(at + 1); });
        row.addView(next);
        box.addView(row);
        openBtn = Ui.pill(this, "");
        openBtn.setBackground(Ui.round(this, 0xFF0E2F3A, Ui.alpha(Ui.CYAN, 0xCC), 999));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        olp.setMargins(0, Ui.dp(this, 10), 0, 0);
        openBtn.setLayoutParams(olp);
        box.addView(openBtn);
        page.addView(box, new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
        body.rig.greet();
        go(Math.max(0, Math.min(at, tour.steps.length - 1)));
    }

    private void go(int i) {
        if (tour == null) return;
        if (i < 0) i = 0;
        if (i >= tour.steps.length) { auto = false; if (playBtn != null) playBtn.setText("▶️ అన్నీ"); say("అంతే. ఇంకేదైనా ఫీచర్ కావాలంటే వెనక్కి వెళ్ళి ఎంచుకోండి."); return; }
        at = i;
        Step st = tour.steps[at];
        counter.setText((at + 1) + " / " + tour.steps.length);
        stepText.setText(st.say);
        for (int k = 0; k < dots.size(); k++) dots.get(k).setBackground(Ui.round(this, k == at ? Ui.CYAN : k < at ? Ui.alpha(Ui.CYAN, 0x88) : 0xFF1B3550, 0, 999));
        if (st.open == null) openBtn.setVisibility(View.GONE);
        else {
            openBtn.setVisibility(View.VISIBLE);
            openBtn.setText(st.openLabel);
            openBtn.setOnClickListener(v -> { auto = false; stopTalk(); openFor(st.open); });
        }
        say(st.say);
    }

    private void openFor(String code) {
        try {
            switch (code) {
                case "camera": JarvisCamera.open(this, null, null); break;
                case "cases": CaseActivity.open(this, null); break;
                case "map": MapActivity.open(this); break;
                case "settings": startActivity(new Intent(this, SettingsActivity.class)); break;
                default: FeaturesActivity.show(this, code.startsWith("features:") ? code.substring(9) : "");
            }
        } catch (Exception ignored) {}
    }

    private void say(String text) {
        Announcer.stop();
        body.rig.setMode(BodyRig.SPEAKING);
        body.rig.setFeeling("calm");
        speaking = true;
        spokeAt = SystemClock.uptimeMillis();
        if (pendingSay != null) main.removeCallbacks(pendingSay);
        pendingSay = () -> { pendingSay = null; if (!isFinishing() && speaking) Announcer.sayDirect(this, text); };
        main.postDelayed(pendingSay, 250); // (after the stop has gone through)
        main.removeCallbacks(watch);
        main.postDelayed(watch, 900);
    }

    /** Follows the voice: lips while it talks, idle after; in ▶️ mode the next step a moment later. */
    private void watchVoice() {
        if (!speaking || isFinishing()) return;
        boolean talking = Announcer.speaking();
        if (talking || SystemClock.uptimeMillis() - spokeAt < 1500) { main.postDelayed(watch, 400); return; }
        speaking = false;
        body.rig.setMode(BodyRig.IDLE);
        if (auto) main.postDelayed(() -> { if (auto && !isFinishing()) go(at + 1); }, 1200);
    }

    private void stopTalk() {
        speaking = false;
        if (pendingSay != null) { main.removeCallbacks(pendingSay); pendingSay = null; }
        main.removeCallbacks(watch);
        Announcer.stop();
        if (body != null) body.rig.setMode(BodyRig.IDLE);
    }

    @Override protected void onPause() {
        super.onPause();
        auto = false;
        if (playBtn != null) playBtn.setText("▶️ అన్నీ");
        stopTalk();
    }
}
