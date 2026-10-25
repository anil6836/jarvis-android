package com.anil.jarvis;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Keys, voice and wake-word settings. */
public class SettingsActivity extends Activity {
    /** The theme this screen was built with (a change in Settings rebuilds it). */
    private int builtTheme;
    /** Open Settings at the card whose title contains this text (from the features screen). */
    static final String EXTRA_SECTION = "jarvis_section";
    /** Open Settings with these words in the search box (from All Features' search). */
    static final String EXTRA_SEARCH = "jarvis_search";
    /** Open Settings and start something there at once ("frame": the tablet's photo frame picker). */
    static final String EXTRA_ACTION = "jarvis_action";
    /** Each card with its title, to jump to one. */
    private final java.util.List<Object[]> cardTitles = new java.util.ArrayList<>();

    private Prefs prefs;
    private EditText name, openAiKey, openAiModel, anthropicKey, anthropicModel;
    private RadioGroup provider, lang, wakeWhen, checkProvider;
    private EditText checkModel;
    private Switch checkAuto;
    private Switch callVoice, readMessages, batteryWarn, voiceLock, proactive, sfx, shakeWake, faceDown, nightSummary;
    private TextView carInfo, car2Info;
    private SeekBar lockSlider, listenWindow;
    /** Where the voice-lock slider started: Save only writes it if Anil moved it (enrolling saves its own calibrated value). */
    private int lockStart = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener lockCalibrated = (sp, key) -> {
        if (!"voice_lock_max".equals(key) || lockSlider == null) return;
        lockSlider.setProgress(Math.round((prefs.voiceLockMax() - 0.30f) * 100));
        lockStart = lockSlider.getProgress();
        showLock();
    };
    private TextView listenWindowLabel;
    private TextView lockInfo, docsInfo, waInfo;
    private EditText sosContacts, smartUrls, smartApp, walletMax;
    private Switch walletPay, emotions, micOnce;
    private Switch alexaSpeak, livePatient, scamGuard, readNews;
    private Switch newsAuto;
    private EditText newsPlaces;
    private Switch diaryAsk, holidayRemind, findPhone, coughAsk;
    private RadioGroup groupMode, typingMode, earsMode, liveProvider, earsOther, liveBarge, liveThink;
    private View earsOtherBox;
    /** The voice choice before the last tap (only leaving "⚡ Gemini Live" turns Live off). */
    private int earsWas;
    private EditText geminiLiveModel;
    /** Gemini Live's voice as chosen in the list (saved with the rest). */
    private String liveVoiceChoice;
    private TextView liveVoiceLabel;
    /** The voice choice and the Live switches are being put in step (their listeners wait). */
    private boolean syncingVoice;
    private EditText earsModel, ttsModel;
    /** His Sarvam key, and the Gemini / Sarvam voice and ears boxes ("Jarvis గొంతు ఎవరిది", Saaras). */
    private EditText sarvamKey, geminiTtsModel, sarvamTtsModel, sarvamEarsModel;
    private RadioGroup voiceProv;
    private String geminiVoiceChoice, sarvamVoiceChoice;
    private TextView geminiVoiceLabel, sarvamVoiceLabel, geminiInfo, sarvamInfo;
    private EditText myNames;
    private Switch bubbleAll;
    private EditText priceCity, dailyPrices, findCode;
    private EditText balGemini, balOpenAi, balAnthropic;
    private EditText bikeRange, bikeKwh, powerRate;
    private Switch weeklyReport;
    private Switch bargeCallVoice, liveAec, echoRecord, offlineAuto;
    /** The offline voice card: what the phone has, and its download buttons. */
    private TextView offlineInfo, offlineHearBtn, offlineEnBtn, offlineVoiceBtn;
    /** Jarvis's own Telugu ears are downloading (one at a time, also across this screen opening again). */
    /** Opened from the "new version" notification / note: go to Updates and start the update. */
    static final String EXTRA_UPDATE_NOW = "update_now";
    private View updatesHeader;
    private TextView updateInfo, updateBtn;
    private ScrollView pageScroll;
    private Switch web, voice, followUp, wake, natural, liveMode, bargeIn, jarvisWord, announceCalls, briefing, briefingSpeak, listenOnOpen, compactPanel, camAlways;
    /** ⚡ వేగం: each speed change on its own (Prefs.speedG / K / F / E). */
    private Switch speedG, speedK, speedF, speedE, speedA, speed5, speedB, voiceGuard;
    private TextView briefingTime, screenInfo;
    private int briefHour, briefMinute;
    private int coughGap;
    private Switch callNote, restMode, medIdOn, moviesWeekly, dailyFact;
    private EditText medBlood, medAllergy, medNotes, medContact, bikeNumber, stepGoal, exerciseHour, factHour;
    private TextView alarmSongInfo;
    private Switch chargeRemind, weekPlan, dailyTip, stormAlert;
    private EditText habitHour;
    private TextView coughGapText;
    private Spinner voicePick;
    private EditText realtimeModel, codeModel, githubToken, geminiKey, geminiModel;
    private TextView voiceInfo, notifyInfo, checkInfo, crashInfo, crashCopy, crashClear;
    private TextView backupInfo, backupSetBtn;
    private final NaturalVoice tester = new NaturalVoice();
    private SeekBar rate, sensitivity, bargeSens;
    private TextView rateLabel, sensitivityLabel, wakeInfo, bargeSensLabel;
    /** The card being filled (each section is its own coloured glass card); page holds the cards. */
    private LinearLayout box, page;
    private LinearLayout peopleBox;   // the faces Jarvis knows (Jarvis ముఖం)
    private Runnable bubbleMark;      // the floating button's line (back from Accessibility settings)

    // ---- the page's layout: 8 groups on the first screen, search, folded cards, saving by itself
    /** One settings card: its title, group, the folded part, the one-line state, and its long explanations. */
    private static final class Card {
        String title, group;
        LinearLayout view, body;
        TextView summary, chevron, info;
        final java.util.List<TextView> notes = new java.util.ArrayList<>();
        boolean open, notesShown;
    }
    /** {id, emoji, name, what is inside}; a card goes to the first group whose words its title has (see groupOf). */
    static final String[][] GROUPS = {
            {"me", "👤", "నేను, లుక్", "పేరు, థీమ్, ముఖం, ఫ్లోటింగ్ బటన్"},
            {"brain", "🧠", "మెదడు, ఖర్చు", "AI, మోడల్స్, API keys, కోడింగ్"},
            {"voice", "🔊", "గొంతు, వినడం", "వాయిస్, సహజ గొంతు, Live, ⚡ వేగం, వేక్ వర్డ్, పవర్ బటన్"},
            {"watch", "⌚", "వాచ్", "వినడం, అలర్ట్స్, రోజువారీ, ఆరోగ్యం, రోడ్డు, రూపం"},
            {"msg", "💬", "మెసేజ్‌లు, కాల్స్", "కాల్స్, బ్రీఫింగ్, నోటిఫికేషన్లు, WhatsApp, స్క్రీన్, మోసం గార్డ్"},
            {"bike", "🏍️", "బైక్, ఇల్లు", "బైక్, కారు, కదలికలు, స్మార్ట్ హోమ్, కాపలా మోడ్"},
            {"tab", "🏠", "ఇంటి టాబ్లెట్", "టాబ్లెట్ గొంతు, అమ్మగారి టైమ్‌లు, టాబ్లెట్లు, ఇంటి Jarvis"},
            {"daily", "❤️", "రోజువారీ, ఆరోగ్యం", "అలారం, డైరీ, హెచ్చరికలు, ఆరోగ్యం, తనంతట తానే"},
            {"safe", "🛡️", "భద్రత", "SOS, టికెట్ పేమెంట్, డాక్యుమెంట్లు"},
            {"data", "☁️", "బ్యాకప్, అప్డేట్లు", "Drive బ్యాకప్, అప్డేట్లు, అనుమతులు"}};
    private final java.util.List<Card> cardList = new java.util.ArrayList<>();
    private Card cur;
    private LinearLayout grid, groupBar;
    private TextView groupTitle, checkLine, subtitle;
    private EditText search;
    private String mode = "home";
    /** What the page's switches, boxes and sliders said at the last save: only a real change is saved. */
    private String lastFp = "";
    /** Something was saved since the screen opened (the wake word restarts with it when he leaves). */
    private boolean changed, askedWakePerms;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autosaveSoon = this::autosave;

    private void openScreenAccess() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        Toast.makeText(this, "Accessibility లో 'Jarvis స్క్రీన్' ఆన్ చేయండి (గ్రే అయితే: App info → ⋮ → Allow restricted settings)", Toast.LENGTH_LONG).show();
    }
    private int accent = Ui.C_CYAN;

    /** The home tablet with a Settings PIN, not given yet: nothing is built (TabletLock). */
    private boolean gated;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TabletLock.active(this) && !TabletLock.isOpen()) { // the home tablet: Settings only after the PIN (Anil, 24 Oct)
            gated = true;
            android.view.View blank = new android.view.View(this);
            blank.setBackgroundColor(0xFF0E1A2C);
            setContentView(blank);
            PinPad.check(this, "సెట్టింగ్స్ PIN", ok -> {
                if (ok) { TabletLock.opened(); recreate(); } else finish();
            });
            return;
        }
        Ui.loadTheme(this); // the chosen colours, before anything is built
        builtTheme = Ui.themeVersion;
        prefs = new Prefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new Ui.Aurora());
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 40));
        scroll.addView(box);
        page = box;
        setContentView(scroll);
        getWindow().setStatusBarColor(Ui.BG_TOP);
        getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        pageScroll = scroll;

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "సెట్టింగ్స్", 27, 0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        Ui.gradientText(title, Ui.C_CYAN, Ui.C_VIOLET);
        titles.addView(title);
        subtitle = Ui.text(this, "Jarvis ని మీకు నచ్చినట్టు మార్చుకోండి", 13.5f, Ui.MUTED);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        IconView close = new IconView(this, IconView.CLOSE, 0xFFFFFFFF);
        close.setBackground(Ui.glass(this, 22));
        close.setOnClickListener(v -> leave());
        close.setContentDescription("మూసేయి");
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        box.addView(head);

        // ---- a quick check for "messages aren't read out" / "Jarvis is silent"
        section("Jarvis చెక్");
        note("మెసేజ్ వచ్చినా Jarvis చెప్పకపోతే, లేదా మాట్లాడకపోతే: ఇక్కడ ✗ ఉన్నది చూసి 'సరిచేయి' నొక్కండి.");
        checkInfo = Ui.text(this, "", 14, 0xFFFFFFFF);
        checkInfo.setLineSpacing(0, 1.25f);
        checkInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(checkInfo);
        // the last time the app itself closed with an error (AppCrash): its trace in small letters, to copy and send
        crashInfo = Ui.text(this, "", 11.5f, Ui.MUTED);
        crashInfo.setTypeface(android.graphics.Typeface.MONOSPACE);
        crashInfo.setTextIsSelectable(true);
        crashInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(crashInfo);
        crashCopy = button("📋 ఆగిపోయిన వివరాలు కాపీ చేయి (పంపడానికి)", v -> {
            try {
                getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Jarvis error", AppCrash.details(this)));
                Toast.makeText(this, "కాపీ అయింది: WhatsApp / మెయిల్‌లో పేస్ట్ చేసి పంపండి", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "కాపీ కాలేదు: స్క్రీన్‌షాట్ తీసి పంపండి", Toast.LENGTH_SHORT).show();
            }
        });
        crashClear = button("🗑 ఆగిపోయిన గుర్తు తీసేయి", v -> { AppCrash.clear(this); showCheck(); });
        for (View x : new View[]{crashInfo, crashCopy, crashClear}) x.setVisibility(View.GONE); // (shown by showCheck when there is one)
        button("సరిచేయి", v -> fixCheck());
        button("గొంతు టెస్ట్ (Jarvis మాట్లాడుతుందా?)", v -> Announcer.say(this, prefs.name() + ", నా గొంతు వినిపిస్తోందా? అంతా బాగుంది."));

        // ---- you
        section("మీరు");
        name = field("మీ పేరు (Jarvis మిమ్మల్ని ఇలా పిలుస్తాడు; 'Sir' అని పెట్టినా పేరు అడిగితే మీ అసలు పేరు చెబుతాడు)", prefs.name(), false);

        // ---- theme: applies at once (no Save needed); the other screens take it when they open
        section("థీమ్ (రంగులు)");
        note("Jarvis రంగులు ఎంచుకోండి. నొక్కగానే మారుతుంది; ఈ పేజీ మూసి తెరిస్తే ఇక్కడ కూడా కొత్త రంగులు.");
        TextView[] themeBtns = new TextView[Ui.THEMES.length];
        TextView[] motionBtn = new TextView[1];
        Runnable marks = () -> {
            String cur = Ui.theme(this);
            for (int i = 0; i < Ui.THEMES.length; i++)
                themeBtns[i].setText((Ui.THEMES[i][0].equals(cur) ? "✓  " : "     ") + Ui.THEMES[i][1] + "  ·  " + Ui.THEMES[i][2]);
            motionBtn[0].setText(Ui.hudMotion(this) ? "🌀  వలయాల కదలిక: ఆన్ (ఆపడానికి నొక్కండి)" : "⏸  వలయాల కదలిక: ఆఫ్ (ఆన్ చేయడానికి నొక్కండి)");
        };
        for (int i = 0; i < Ui.THEMES.length; i++) {
            final String id = Ui.THEMES[i][0];
            themeBtns[i] = button("", v -> {
                Ui.setTheme(this, id, Ui.hudMotion(this));
                builtTheme = Ui.themeVersion; // this page keeps its look until reopened (unsaved fields stay)
                marks.run();
                Toast.makeText(this, "థీమ్ మారింది", Toast.LENGTH_SHORT).show();
            });
        }
        motionBtn[0] = button("", v -> { Ui.setTheme(this, Ui.theme(this), !Ui.hudMotion(this)); marks.run(); });
        marks.run();
        note("బ్యాటరీ సేవర్ ఆన్‌లో ఉంటే వలయాలు తమంతట తామే ఆగుతాయి.");

        // ---- Jarvis's face on the home screen (applies at once)
        section("Jarvis ముఖం");
        note("హోమ్ స్క్రీన్ మీద Jarvis కి మనిషి ముఖం: మాట్లాడేటప్పుడు పెదాలు కదులుతాయి, జవాబులోని భావం (సంతోషం, బాధ, ఆశ్చర్యం…) ముఖంలో కనిపిస్తుంది. "
                + "ముఖం మీద నొక్కితే మాట్లాడొచ్చు; ఎక్కువసేపు నొక్కితే ఈ పేజీ వస్తుంది.");
        TextView[] fb = new TextView[6];
        Runnable faceMarks = () -> {
            fb[0].setText(FaceSight.faceOn(this) ? "😊  ముఖం: ఆన్ (తీసేయడానికి నొక్కండి)" : "⭕  ముఖం: ఆఫ్ (చూపించడానికి నొక్కండి)");
            fb[1].setText(FaceSight.bodyFace(this) ? "🧑  స్టైల్: కొత్త Jarvis, ఇంటి టాబ్లెట్‌లోని బొమ్మ (మార్చడానికి నొక్కండి)"
                    : FaceSight.holo(this) ? "✨  స్టైల్: హోలోగ్రామ్ ముఖం (మార్చడానికి నొక్కండి)" : "🙂  స్టైల్: పాత మనిషి ముఖం (మార్చడానికి నొక్కండి)");
            fb[2].setText(FaceSight.big(this) ? "🔍  పరిమాణం: పెద్దది (చిన్నదికి నొక్కండి)" : "🔍  పరిమాణం: చిన్నది (పెద్దదికి నొక్కండి)");
            fb[3].setText(FaceSight.camOn(this) ? "👁  ముందు కెమెరాతో మిమ్మల్ని చూడటం: ఆన్ (ఆపడానికి నొక్కండి)" : "🚫  ముందు కెమెరాతో చూడటం: ఆఫ్ (ఆన్ చేయడానికి నొక్కండి)");
            fb[4].setText(FaceSight.seeMe(this) ? "📷  ప్రశ్నతో మీ ఫోటో AI కి: ఆన్ (ఆపడానికి నొక్కండి)" : "📷  ప్రశ్నతో మీ ఫోటో AI కి: ఆఫ్ (ఆన్ చేయడానికి నొక్కండి)");
            fb[5].setText(FaceSight.greetOn(this) ? "👋  తెలిసినవాళ్లను పలకరించడం: ఆన్" : "👋  తెలిసినవాళ్లను పలకరించడం: ఆఫ్");
        };
        fb[0] = button("", v -> { FaceSight.set(this, "face_on", !FaceSight.faceOn(this)); faceMarks.run(); });
        fb[1] = button("", v -> { // new Jarvis → old face → hologram → new Jarvis
            if (FaceSight.bodyFace(this)) { FaceSight.set(this, "face_body", false); FaceSight.set(this, "face_holo", false); }
            else if (!FaceSight.holo(this)) FaceSight.set(this, "face_holo", true);
            else { FaceSight.set(this, "face_body", true); FaceSight.set(this, "face_holo", false); }
            faceMarks.run();
        });
        fb[2] = button("", v -> { FaceSight.set(this, "face_big", !FaceSight.big(this)); faceMarks.run(); });
        fb[3] = button("", v -> {
            boolean on = !FaceSight.camOn(this);
            FaceSight.set(this, "face_cam", on);
            FaceSight.set(this, "face_asked", true);
            if (on && !FaceSight.allowed(this)) requestPermissions(new String[]{Manifest.permission.CAMERA}, 62);
            faceMarks.run();
        });
        fb[4] = button("", v -> { FaceSight.set(this, "face_seeme", !FaceSight.seeMe(this)); FaceSight.set(this, "face_asked", true); faceMarks.run(); });
        fb[5] = button("", v -> { FaceSight.set(this, "face_greet", !FaceSight.greetOn(this)); faceMarks.run(); });
        faceMarks.run();
        note("ముందు కెమెరా Jarvis హోమ్ స్క్రీన్ తెరిచి ఉన్నప్పుడే చూస్తుంది (ముఖం పక్కన 👁 గుర్తు); ఫోటోలు ఎక్కడా సేవ్ అవ్వవు; 10 నిమిషాలు ఎవరూ కనిపించకపోతే తనంతట తానే ఆగుతుంది (💤, ముఖం మీద నొక్కితే మళ్ళీ చూస్తుంది). "
                + "'ప్రశ్నతో మీ ఫోటో' ఆన్‌లో ఉంటే, మీ ముఖం కనిపిస్తున్నప్పుడు అడిగే ప్రతి ప్రశ్నతో ఒక చిన్న ఫోటో మీరు ఎంచుకున్న AI కి వెళ్తుంది (మిమ్మల్ని చూసి మాట్లాడటానికి): API ఖర్చు కొంచెం పెరుగుతుంది.");
        TextView ph = Ui.text(this, "ముఖాలు గుర్తుపెట్టుకున్నవాళ్లు", 15.5f, 0xFFFFFFFF);
        ph.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ph.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(ph);
        peopleBox = new LinearLayout(this);
        peopleBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(peopleBox);
        renderPeople();
        note("కొత్తవాళ్లను పరిచయం చేయడానికి: వాళ్లు ఒక్కరే ఫోన్ వైపు చూస్తుండగా \"Jarvis, ఇతను రాము, గుర్తుపెట్టుకో\" అనండి; మిమ్మల్ని మీరు: \"నన్ను గుర్తుపెట్టుకో\". "
                + "మీరు పరిచయం చేసినవాళ్లనే పేరుతో గుర్తుపడతాడు, తెలియనివాళ్లను గుర్తుపెట్టుకోడు. ముఖాల గుర్తులు (ఫోటోలు కాదు) ఈ ఫోన్‌లో, మీ పాస్‌వర్డ్‌తో లాక్ అయిన Drive బ్యాకప్‌లో మాత్రమే ఉంటాయి; ఎక్కడికీ పంపబడవు. "
                + "మొదటిసారి సుమారు 23 MB మోడల్ ఒక్కసారి డౌన్‌లోడ్ అవుతుంది (FaceNet, Apache-2.0 / MIT). ఇంట్లోవాళ్లకి ఈ విషయం చెప్పండి.");

        // ---- the floating Jarvis button over every app (applies at once)
        section("ఫ్లోటింగ్ బటన్");
        note("ఏ యాప్‌లో ఉన్నా (Chrome, ఇంకో బ్రౌజర్, WhatsApp, వార్తల యాప్…) స్క్రీన్ పక్కన చిన్న రౌండ్ Jarvis గ్లోబ్ ఉంటుంది. నొక్కితే చిన్న ఆప్షన్లు: "
                + "📖 చదువు (పేజీ మొత్తం, మీరు ఉన్న చోటు నుంచి; ఫోటో అయితే అందులోని అక్షరాలు), 🧠 అర్థం చెప్పు (మ్యాటర్ అర్థం చేసుకుని తెలుగులో), 💡 దీని గురించి, "
                + "🌐 తెలుగులో, 🖼️ ఫోటో చదువు, 💬 జవాబు (✍️ బాక్సులో టైప్ చేస్తుంది, పంపేది మీరే), ⏰ గుర్తుపెట్టు (స్క్రీన్‌లో తేదీలకి రిమైండర్), 🛡️ మోసమా?, "
                + "🛒 ధర పోలిక, 🎬 వీడియో, 📚 కష్టమైన పదాలు, 💾 తర్వాత చదువు, 🎙️ అడుగు. "
                + "ఇంకా: 👆 ఇక్కడి నుంచి చదువు, ✍️ రాసిపెట్టు (మీ మెసేజ్‌ని బాగా రాసి బాక్సులో పెడుతుంది, పంపేది మీరే), 📥 దీన్ని సేవ్ చేయి (తేదీ, బిల్లు, కాంటాక్ట్, పార్సెల్), "
                + "📍 దారి / కాల్, ✅ నిజమా?, ✂️ ఈ భాగం మాత్రమే, ⚖️ రెండు పోల్చు, 📋 ఫారమ్ సహాయం, 🎧 వాయిస్ మెసేజ్ (OpenAI key కావాలి), 👥 గ్రూప్ సారాంశం, "
                + "🕶️ దాచి షేర్ (నంబర్లు, ఈమెయిల్, చిరునామా దాచి), 🔎 ఇది ఏంటి? వెతుకు, 🔬 Jarvis స్కాన్ (స్క్రీన్ మీద ఉన్నది 3D / PDF కి; ఎండోస్కోప్, థర్మల్ కెమెరా యాప్‌లు కూడా), 📷 Jarvis కెమెరా. ఆ యాప్‌కి సరిపోయే 5 (మీరు ఎక్కువ వాడేవి) పైన, మిగతావి '▾ ఇంకా' లో. "
                + "చదువుతున్నప్పుడు ⏮ ⏸ ⏭ 🐢 ⏩ ⏹, ⏲️ నిద్ర టైమర్ (15 / 30 / 60 ని.); ఆపిన పేజీ మళ్ళీ చదవమంటే ఆపిన చోటు నుంచి అడుగుతుంది. జవాబు బాక్సులో 🎙️ అడుగు, 🔊 మళ్ళీ, ⏹ ఆపు, 📋 కాపీ, 💾 సేవ్, 📤 షేర్. ఎక్కువసేపు నొక్కి పట్టుకుంటే Jarvis వింటాడు.");
        TextView[] bb = new TextView[1];
        bubbleMark = () -> bb[0].setText(!FloatBubble.on(this) ? "⚪  ఫ్లోటింగ్ బటన్: ఆఫ్ (ఆన్ చేయడానికి నొక్కండి)"
                : JarvisAccessibility.enabled() ? "🔵  ఫ్లోటింగ్ బటన్: ఆన్ (ఆపడానికి నొక్కండి)"
                : "⚠️  ఫ్లోటింగ్ బటన్ ఆన్, కానీ 'Jarvis స్క్రీన్' స్విచ్ ఆఫ్‌లో ఉంది (ఆన్ చేయడానికి నొక్కండి)");
        bb[0] = button("", v -> {
            if (FloatBubble.on(this) && !JarvisAccessibility.enabled()) { openScreenAccess(); return; }
            boolean on = !FloatBubble.on(this);
            FloatBubble.set(this, on);
            if (on && !JarvisAccessibility.enabled()) openScreenAccess();
            bubbleMark.run();
        });
        bubbleMark.run();
        bubbleAll = toggle("మెనూలో అన్ని ఆప్షన్లు ఒకేసారి చూపించు (ఆఫ్ = ఆ యాప్‌కి సరిపోయే 5, తర్వాత '▾ ఇంకా')", prefs.bubbleShowAll());
        button("☑️ మెనూలో ఏ ఆప్షన్లు ఉండాలి", v -> chooseBubbleOptions());
        button("🙈 బటన్ దాచిన యాప్‌లు", v -> chooseHiddenApps());
        note("బటన్ మీద: ఒక్కసారి నొక్కితే మెనూ, రెండుసార్లు నొక్కితే చదువు / ఆపు, నొక్కి పట్టుకుంటే మాటలతో అడగడం. "
                + "Full screen వీడియో / గేమ్‌లో తనంతట తానే దాక్కుంటుంది. మెనూలో '🙈 ఈ యాప్‌లో దాచు' నొక్కితే ఆ యాప్‌లో ఇక కనిపించదు.");
        note("ఇది 'Jarvis స్క్రీన్' (Accessibility) స్విచ్ ద్వారా పనిచేస్తుంది, వేరే అనుమతి అక్కర్లేదు. పాస్‌వర్డ్‌లు చదవదు; బ్యాంకింగ్ / పేమెంట్ యాప్‌ల స్క్రీన్ AI కి పంపదు. "
                + "లాక్ స్క్రీన్ మీద, Jarvis స్క్రీన్ మీద, Jarvis ఫోన్ వాడుతున్నప్పుడు కనిపించదు. 'చదువు' ఫోన్ గొంతుతో (ఉచితం, ఆఫ్‌లైన్).");

        // ---- brain
        section("Jarvis మెదడు");
        note("ఏ కంపెనీ API key వాడతారో ఎంచుకోండి. Key మీ ఫోన్‌లో మాత్రమే ఉంటుంది.");
        provider = new RadioGroup(this);
        provider.addView(radio(1, "OpenAI (GPT)"));
        provider.addView(radio(2, "Anthropic (Claude)"));
        provider.addView(radio(3, "Google (Gemini)"));
        provider.check(prefs.isGemini() ? 3 : prefs.isOpenAi() ? 1 : 2);
        box.addView(provider);

        openAiKey = field("OpenAI API key (sk-…)", prefs.openAiKey(), true);
        openAiModel = field("OpenAI మోడల్", prefs.openAiModel(), false);
        modelPicker(Prefs.OPENAI, openAiKey, openAiModel, "openai_model");
        link("OpenAI key ఇక్కడ తీసుకోండి", "https://platform.openai.com/api-keys");
        anthropicKey = field("Anthropic API key (sk-ant-…)", prefs.anthropicKey(), true);
        anthropicModel = field("Anthropic మోడల్", prefs.anthropicModel(), false);
        modelPicker(Prefs.ANTHROPIC, anthropicKey, anthropicModel, "anthropic_model");
        link("Anthropic key ఇక్కడ తీసుకోండి", "https://console.anthropic.com/settings/keys");
        geminiKey = field("Gemini API key (AIza…)", prefs.geminiKey(), true);
        geminiModel = field("Gemini మోడల్", prefs.geminiModel(), false);
        geminiModel.setHint("కింద 'అన్ని మోడల్స్ చూపించు' నొక్కి ఎంచుకోండి");
        modelPicker(Prefs.GEMINI, geminiKey, geminiModel, "gemini_model");
        link("Gemini key ఇక్కడ తీసుకోండి", "https://aistudio.google.com/apikey");
        sarvamKey = field("Sarvam API key (Jarvis గొంతు / వినడం కోసం మాత్రమే, మెదడు కాదు)", prefs.sarvamKey(), true);
        link("Sarvam key ఇక్కడ తీసుకోండి (API Keys)", "https://dashboard.sarvam.ai");
        note("మోడల్ Jarvis తనంతట తాను ఎంచుకోడు: మీరు ఎంచుకున్నదే వాడతాడు. 'అన్ని మోడల్స్ చూపించు' మీ key తో ఆ కంపెనీ దగ్గర నుంచి "
                + "ఇప్పుడున్న మోడల్స్ అన్నీ తెస్తుంది; ఒకటి నొక్కితే వెంటనే సేవ్ అవుతుంది. ఎప్పుడైనా మార్చుకోవచ్చు. పేరు మీరే టైప్ చేసినా సరే.");
        note("Gemini: ఉచిత ప్లాన్‌లో Flash, Flash-Lite మోడల్స్ మాత్రమే; Pro మోడల్స్‌కి billing కావాలి. ప్రతి మోడల్‌కి వేరే నిమిషం/రోజు లిమిట్ ఉంటుంది: "
                + "ఒక మోడల్ లిమిట్ అయిపోతే ఇంకో Flash మోడల్ ఎంచుకోండి. రోజు లిమిట్ మధ్యాహ్నం సుమారు 12:30–1:30 కి మళ్లీ వస్తుంది. "
                + "Jarvis ఒక్క ప్రశ్నకి (యాప్ తెరవడం, సెర్చ్ లాంటి పనులుంటే) చాలా requests పంపుతాడు, అందుకే ఉచిత లిమిట్ త్వరగా అయిపోవచ్చు. "
                + "Google AI Plus/Pro సబ్స్క్రిప్షన్ Gemini యాప్ కోసం; API లిమిట్‌కి దానితో సంబంధం లేదు. ఉచిత ప్లాన్‌లో మీరు పంపేవి (మాటలు, స్క్రీన్‌షాట్లు) "
                + "Google తమ మోడల్స్ మెరుగుపరచడానికి వాడుకోవచ్చు. పైన 'Google (Gemini)' ఎంచుకుంటేనే మెదడుగా వాడుతుంది (గొంతు, వినడం, Live కి వాటి సొంత ఎంపికలు 🔊 లో).");
        link("Gemini లిమిట్లు / billing (AI Studio)", "https://aistudio.google.com/usage");
        web = toggle("ఇంటర్నెట్ సెర్చ్ (వార్తలు, స్కోర్లు, ధరలు)", prefs.webSearch());
        note("🔁 క్రాస్ చెక్ (రెండో AI): డబ్బు, ఆరోగ్యం, చట్టం, పెద్ద నిర్ణయాల జవాబులను మీరు ఎంచుకున్న రెండో AI కూడా చెక్ చేస్తుంది; "
                + "తేడా ఉంటే ఆ తేడా చెబుతుంది. 'క్రాస్ చెక్ చేయి' అంటే ఏ ప్రశ్నకైనా చేస్తుంది. ఆ కంపెనీ key పైన పెట్టి ఉండాలి. Jarvis తనంతట తాను మోడల్ మార్చడు.");
        checkProvider = new RadioGroup(this);
        checkProvider.addView(radio(41, "రెండో AI వద్దు"));
        checkProvider.addView(radio(42, "OpenAI"));
        checkProvider.addView(radio(43, "Anthropic (Claude)"));
        checkProvider.addView(radio(44, "Google (Gemini)"));
        String cpv = prefs.sp.getString("check_provider", "");
        checkProvider.check(cpv.equals(Prefs.OPENAI) ? 42 : cpv.equals(Prefs.ANTHROPIC) ? 43 : cpv.equals(Prefs.GEMINI) ? 44 : 41);
        box.addView(checkProvider);
        checkModel = field("రెండో AI మోడల్ పేరు (పైన ఆ కంపెనీ మోడల్స్ లిస్ట్‌లో చూడొచ్చు)", prefs.sp.getString("check_model", ""), false);
        checkAuto = toggle("ముఖ్యమైన ప్రశ్నలకు తనంతట తానే క్రాస్ చెక్", prefs.sp.getBoolean("check_auto", true));
        note("Jarvis ఎంత తెలివిగా ఆలోచిస్తాడో పై మోడల్‌ని బట్టి ఉంటుంది. అత్యంత శక్తివంతమైనవి: OpenAI లో gpt-6-astra, Anthropic లో claude-opus-5-5 "
                + "(ఇవి నెమ్మదిగా, ఖరీదుగా ఉంటాయి). రోజువారీ మాటలకి వేగమైన మోడల్ ఉంచి, కోడింగ్‌కి మాత్రమే శక్తివంతమైనది కింద 'కోడింగ్ మోడల్' లో పెట్టొచ్చు.");

        // ---- API cost meter
        section("API ఖర్చు (ఈ నెల)");
        note("Jarvis ఒక్కో ప్రశ్నకి ఎన్ని tokens వాడాడో లెక్కించి, ఆ కంపెనీ ధరలతో సుమారు ఖర్చు చూపిస్తాడు. Live మోడ్, సహజ గొంతు OpenAI ఖాతా నుంచే కట్ అవుతాయి.");
        for (String p : Usage.PROVIDERS) {
            TextView t = Ui.text(this, Usage.line(p), 14.5f, Usage.color(p));
            t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
            box.addView(t);
        }
        balGemini = moneyField("Gemini credit (₹) · AI Studio → Billing లో ఉన్నది", Usage.GEMINI);
        balOpenAi = moneyField("OpenAI balance ($)", Usage.OPENAI);
        balAnthropic = moneyField("Anthropic balance ($)", Usage.ANTHROPIC);
        note("మీ ఇప్పటి balance ఒక్కసారి ఇక్కడ పెట్టండి (ఖాళీ = లెక్కించకు). Jarvis అక్కడి నుంచి తగ్గిస్తూ, 20%, 5% మిగిలినప్పుడు notification ఇస్తాడు. "
                + "Credit కొన్నాక కొత్త balance మళ్లీ ఇక్కడ పెట్టండి. ఇవి అంచనాలు; ఖచ్చితమైన లెక్క కంపెనీ billing పేజీల్లో ఉంటుంది. \"API ఖర్చు ఎంత?\" అని Jarvis ని కూడా అడగొచ్చు.");
        link("Gemini billing (AI Studio)", "https://aistudio.google.com/usage");
        link("OpenAI billing", "https://platform.openai.com/settings/organization/billing/overview");
        link("Anthropic billing", "https://console.anthropic.com/settings/billing");

        // ---- coding, websites, apps
        section("కోడింగ్, వెబ్‌సైట్లు, యాప్‌లు");
        note("\"Python తో … లెక్కించు\", \"… వెబ్‌సైట్ తయారు చెయ్\", \"… యాప్ తయారు చెయ్\", \"… కోడ్ రాయి\" అని అడగండి. తయారైనవన్నీ Downloads/Jarvis లో సేవ్ అవుతాయి.");
        codeModel = field("కోడింగ్ మోడల్ (ఖాళీ = పై మెదడే)", prefs.codeModel(), false);
        LinearLayout presets = new LinearLayout(this);
        TextView astra = Ui.text(this, "  GPT-6 Astra  ", 14, Ui.CYAN);
        astra.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        astra.setOnClickListener(v -> codeModel.setText("gpt-6-astra"));
        TextView opus = Ui.text(this, "  Claude Opus 5.5  ", 14, Ui.CYAN);
        opus.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        opus.setOnClickListener(v -> codeModel.setText("claude-opus-5-5"));
        presets.addView(astra);
        presets.addView(opus);
        box.addView(presets);
        TextView allCode = Ui.text(this, "📋 అన్ని కంపెనీల మోడల్స్ చూపించు, ఎంచుకో", 14.5f, Ui.CYAN);
        allCode.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        allCode.setOnClickListener(v -> Models.pick(this, "కోడింగ్ మోడల్ ఎంచుకోండి",
                new String[]{Prefs.OPENAI, Prefs.ANTHROPIC, Prefs.GEMINI},
                new String[]{openAiKey.getText().toString(), anthropicKey.getText().toString(), geminiKey.getText().toString()},
                codeModel.getText().toString(), it -> chose(codeModel, "code_model", it.id)));
        box.addView(allCode);
        note("gpt-6-astra కి పైన OpenAI key, claude-opus-5-5 కి Anthropic key ఉండాలి. మోడల్ పేరు తప్పైతే Jarvis వేరే మోడల్‌కి మారకుండా తప్పు అని చెబుతుంది.");
        githubToken = field("GitHub token (వెబ్‌సైట్ ఆన్‌లైన్, యాప్‌లు తయారీ కోసం)", prefs.githubToken(), true);
        note("ఒక్కసారి: github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token → "
                + "'repo', 'workflow' టిక్ చేసి Generate → వచ్చిన token ఇక్కడ పెట్టండి. ఇది మీ ఫోన్‌లో మాత్రమే ఉంటుంది. "
                + "Jarvis మీ GitHub లో jarvis-sites (వెబ్‌సైట్లు), jarvis-app-… (యాప్‌లు) అనే పబ్లిక్ repos తయారు చేస్తుంది.");

        // ---- voice
        section("వాయిస్");
        TextView el = Ui.text(this, "🎙️ మీ మాటలు వినే పద్ధతి:", 15, Ui.TEXT);
        el.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        box.addView(el);
        earsMode = new RadioGroup(this);
        earsMode.addView(radio(84, "⚡ Gemini Live · చాలా వేగం, మనిషితో మాట్లాడినట్టు, బీప్ ఉండదు (Gemini key, నెట్ కావాలి)"));
        earsMode.addView(radio(81, "OpenAI · Jarvis సొంత మైక్, బీప్ ఉండదు (నెట్ కావాలి, చాలా కొద్ది ఖర్చు)"));
        earsMode.addView(radio(82, "Gemini · Jarvis సొంత మైక్, బీప్ ఉండదు (నెట్ కావాలి, మీ Gemini మోడల్)"));
        earsMode.addView(radio(88, "Sarvam Saaras · Jarvis సొంత మైక్, బీప్ ఉండదు (నెట్ కావాలి, Sarvam key, భారతీయ భాషలకే ప్రత్యేకం)"));
        earsMode.addView(radio(83, "Google వాయిస్ టైపింగ్ · మాటలు లైవ్‌గా కనిపిస్తాయి, నెట్ లేకుండా కూడా; కానీ మైక్ ఆన్/ఆఫ్ బీప్‌లు వస్తాయి"));
        String em0 = prefs.earsMode();
        earsMode.check(prefs.liveMode() && prefs.liveGemini() ? 84 : "gemini".equals(em0) ? 82 : "google".equals(em0) ? 83 : "sarvam".equals(em0) ? 88 : 81);
        earsWas = earsMode.getCheckedRadioButtonId();
        box.addView(earsMode);
        // with Gemini Live: how the message card, the camera and the floating button hear him
        LinearLayout other = new LinearLayout(this);
        other.setOrientation(LinearLayout.VERTICAL);
        other.setPadding(Ui.dp(this, 24), 0, 0, 0);
        other.addView(Ui.text(this, "మెసేజ్ కార్డ్, కెమెరా, ఫ్లోటింగ్ బటన్ ఎలా వినాలి:", 14, Ui.MUTED));
        earsOther = new RadioGroup(this);
        earsOther.addView(radio(85, "OpenAI (సొంత మైక్)"));
        earsOther.addView(radio(86, "Gemini (సొంత మైక్)"));
        earsOther.addView(radio(89, "Sarvam Saaras (సొంత మైక్)"));
        earsOther.addView(radio(87, "Google వాయిస్ టైపింగ్ (బీప్‌లతో)"));
        earsOther.check("gemini".equals(em0) ? 86 : "google".equals(em0) ? 87 : "sarvam".equals(em0) ? 89 : 85);
        other.addView(earsOther);
        box.addView(other);
        earsOtherBox = other;
        other.setVisibility(earsWas == 84 ? View.VISIBLE : View.GONE);
        earsModel = field("OpenAI మాటల మోడల్ (OpenAI ఎంచుకుంటే)", prefs.earsModel(), false);
        sarvamEarsModel = field("Sarvam మాటల మోడల్ (Sarvam Saaras ఎంచుకుంటే)", prefs.sarvamEarsModel(), false);
        modelPicker(Models.SARVAM_STT, sarvamKey, sarvamEarsModel, "sarvam_ears_model");
        note("OpenAI / Gemini / Sarvam: మీరు మాట్లాడటం ఆపాక మాటలు ఒకేసారి వస్తాయి (Sarvam: Jarvis మెదడు సెక్షన్‌లోని Sarvam key; 30 సెకన్ల కంటే పొడవైన మెసేజ్ ముక్కలుగా వెళ్తుంది). ఆ AI అందకపోతే (నెట్/key) Jarvis వేరే దానికి మారదు, ఎందుకో చెబుతుంది. "
                + "Gemini Live: మీ మాట వింటూనే Gemini గొంతుతో వెంటనే జవాబిస్తుంది (కింద 'Live సంభాషణ' లో దాని సెట్టింగ్స్). మెసేజ్ కార్డ్, కెమెరా, ఫ్లోటింగ్ బటన్ "
                + "మాత్రం దాని కింద ఎంచుకున్న పద్ధతితో వింటాయి. బైక్ మీద గొంతుతో మార్చొచ్చు: \"Jarvis, Google వాయిస్‌కి మారు\", \"Live పెట్టు\", \"Live ఆపు\", \"OpenAI కి మారు\".");
        voice = toggle("సమాధానాలు పైకి చదివి వినిపించు", prefs.voiceReplies());
        followUp = toggle("సమాధానం తర్వాత మళ్లీ వినడం (సంభాషణ మోడ్)", prefs.followUp());
        micOnce = toggle("Google వాయిస్ టైపింగ్: మైక్ ఒక్కసారే ఆన్ (మళ్లీ మళ్లీ ఆన్/ఆఫ్ కాదు, బీప్‌లు తక్కువ, పాటలు మ్యూట్ కాకుండా తగ్గుతాయి; "
                + "ఆఫ్: పిలిచాక వినే సమయం అంతా మైక్ తెరిచి ఉంచడానికి ప్రయత్నిస్తుంది)", prefs.micOnce());
        if (prefs.homeMode()) note("ఈ ఇంటి టాబ్లెట్‌లో: Jarvis సొంత మైక్‌తో పిలిచాక 6 సెకన్లు ఎదురుచూస్తాడు (బీప్ లేదు), మాట్లాడటం ఆపిన సుమారు 2 సెకన్లకి ఆగుతాడు, "
                + "ఒక ప్రశ్న గరిష్టంగా 15 సెకన్లు; గదిలో TV / ఫ్యాన్ శబ్దం లెక్కలోకి రాదు. Google వాయిస్ టైపింగ్‌తో కనీసం 12 సెకన్లు (మధ్యలో మూసుకుంటే మళ్ళీ తెరుస్తుంది). "
                + "సొంత మైక్‌తో కింది సమయం ఇక్కడ దీన్ని మార్చదు (Google తో పెంచొచ్చు). ఫోన్ Jarvis మారదు.");
        listenWindowLabel = Ui.text(this, "", 15, Ui.MUTED);
        box.addView(listenWindowLabel);
        listenWindow = new SeekBar(this);
        listenWindow.setMax(7); // 3 .. 10 seconds
        listenWindow.setProgress(Math.max(0, Math.min(7, prefs.listenWindowSeconds() - 3)));
        listenWindow.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { showListenWindow(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(listenWindow);
        showListenWindow();
        rateLabel = Ui.text(this, "", 15, Ui.MUTED);
        box.addView(rateLabel);
        rate = new SeekBar(this);
        rate.setMax(100);
        rate.setProgress(Math.round((prefs.speechRate() - 0.5f) * 100));
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { showRate(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(rate);
        showRate();
        TextView langLabel = Ui.text(this, "మీరు మాట్లాడే భాష", 15, Ui.MUTED);
        langLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(langLabel);
        lang = new RadioGroup(this);
        lang.addView(radio(11, "తెలుగు"));
        lang.addView(radio(12, "English"));
        lang.check("en-IN".equals(prefs.listenLang()) ? 12 : 11);
        box.addView(lang);
        note("Jarvis తెలుగులో మాట్లాడకపోతే: ఫోన్ Settings → Text-to-speech → Google → తెలుగు వాయిస్ డౌన్‌లోడ్ చేయండి.");

        // ---- natural voice
        section("సహజ గొంతు (OpenAI · Gemini · Sarvam)");
        note("సినిమాలోలా మనిషి గొంతుతో మాట్లాడుతుంది. కింద OpenAI, Google Gemini, Sarvam లో ఒకటి ఎంచుకోండి; దాని key కావాలి, కొంచెం ఖర్చు అవుతుంది. "
                + "ఇది ఆఫ్ చేస్తే ఫోన్ Google గొంతుతో మాట్లాడుతుంది.");
        natural = toggle("సహజ గొంతు వాడు", prefs.naturalVoice());
        emotions = toggle("భావాలతో మాట్లాడు (నవ్వు, సంతోషం, ఉత్సాహం, బాధ… సందర్భానికి తగ్గట్టు)", prefs.emotions());
        // whose voice: OpenAI (as always) or the Google Gemini / Sarvam one; only the chosen one speaks, never switched by itself
        TextView wl = Ui.text(this, "🗣️ Jarvis గొంతు ఎవరిది:", 15, Ui.TEXT);
        wl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        box.addView(wl);
        voiceProv = new RadioGroup(this);
        voiceProv.addView(radio(101, "OpenAI · కింద ఉన్న గొంతు, మోడల్ (ఇప్పుడున్నదే)"));
        voiceProv.addView(radio(102, "Google Gemini · మీ Gemini key, 30 గొంతులు (Fenrir…)"));
        voiceProv.addView(radio(103, "Sarvam Bulbul · Sarvam key, భారతీయ తెలుగు గొంతులు"));
        String vp0 = prefs.voiceProvider();
        voiceProv.check(CloudVoice.GEMINI.equals(vp0) ? 102 : CloudVoice.SARVAM.equals(vp0) ? 103 : 101);
        box.addView(voiceProv);
        note("ఎంచుకున్న గొంతే మాట్లాడుతుంది; ఒకదాని నుంచి ఇంకోదానికి తనంతట తానే మారదు. అది పనిచేయకపోతే ఫోన్ గొంతుతో చెబుతుంది, "
                + "ఎందుకో \"Jarvis చెక్\" లో ఉంటుంది. ⏸/▶, మధ్యలో ఆపడం, నోరు కదలడం మూడింటికీ ఒకేలా. వాచ్, ఇంటి టాబ్లెట్ OpenAI తోనే.");
        subHead("▸ OpenAI");
        TextView vl = Ui.text(this, "గొంతు ఎంచుకోండి (cedar = లోతైన మగ గొంతు, సిఫార్సు)", 14, Ui.MUTED);
        vl.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(vl);
        voicePick = new Spinner(this);
        ArrayAdapter<String> va = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, NaturalVoice.VOICES);
        voicePick.setAdapter(va);
        voicePick.setBackground(fieldBg());
        int current = 0;
        for (int i = 0; i < NaturalVoice.VOICES.length; i++) if (NaturalVoice.VOICES[i].equals(prefs.naturalVoiceName())) current = i;
        voicePick.setSelection(current);
        box.addView(voicePick, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        ttsModel = field("OpenAI గొంతు మోడల్ (మాట్లాడే మోడల్)", prefs.ttsModel(), false);
        modelPicker(Models.TTS, openAiKey, ttsModel, "tts_model");
        note("మొదట gpt-4o-mini-tts (భావాలతో మాట్లాడుతుంది, అన్ని గొంతులూ ఉంటాయి). tts-1 / tts-1-hd పాత మోడల్స్: భావాలు ఉండవు, "
                + "cedar, marin, ballad, verse గొంతులు ఉండవు. మార్చాక \"ఈ గొంతు వినిపించు\" తో వినండి.");
        button("ఈ గొంతు వినిపించు", v -> testVoice());
        voiceInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(voiceInfo);

        // Google Gemini's voice (his Gemini key)
        subHead("▸ Google Gemini");
        geminiVoiceChoice = prefs.geminiTtsVoice();
        geminiVoiceLabel = Ui.text(this, "", 15, accent);
        geminiVoiceLabel.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        geminiVoiceLabel.setOnClickListener(v -> pickGeminiVoice());
        box.addView(geminiVoiceLabel);
        showGeminiVoice();
        geminiTtsModel = field("Gemini గొంతు మోడల్ (మాట్లాడే మోడల్)", prefs.geminiTtsModel(), false);
        modelPicker(Models.GEMINI_TTS, geminiKey, geminiTtsModel, "gemini_tts_model");
        note("మొదట " + CloudVoice.GEMINI_DEFAULT_MODEL + " (వేగం, తక్కువ ఖర్చు). మాటలు ఉన్నది ఉన్నట్టే చదువుతుంది; భావాలు (నవ్వు, ఉత్సాహం) OpenAI లో మాత్రమే. "
                + "key: పైన Jarvis మెదడు లోని Gemini key.");
        button("ఈ గొంతు వినిపించు", v -> testCloud(CloudVoice.GEMINI));
        geminiInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(geminiInfo);

        // Sarvam Bulbul's voice (his Sarvam key)
        subHead("▸ Sarvam Bulbul");
        sarvamVoiceChoice = prefs.sarvamVoice();
        sarvamVoiceLabel = Ui.text(this, "", 15, accent);
        sarvamVoiceLabel.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        sarvamVoiceLabel.setOnClickListener(v -> pickSarvamVoice());
        box.addView(sarvamVoiceLabel);
        sarvamTtsModel = field("Sarvam గొంతు మోడల్ (మాట్లాడే మోడల్)", prefs.sarvamTtsModel(), false);
        modelPicker(Models.SARVAM_TTS, sarvamKey, sarvamTtsModel, "sarvam_tts_model");
        sarvamTtsModel.addTextChangedListener(new android.text.TextWatcher() { // (a new model: its own voices)
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { showSarvamVoice(); }
        });
        showSarvamVoice();
        note("మొదట " + CloudVoice.SARVAM_DEFAULT_MODEL + ". ప్రతి మోడల్‌కి దాని గొంతులే (v4-flash కి తెలుగు గొంతులు 4, మగ: tarun). భావాలు ఉండవు. "
                + "key: పైన Jarvis మెదడు లో Sarvam API key. ఖర్చు: 10,000 అక్షరాలకి సుమారు ₹30 (కొత్త అకౌంట్‌కి ₹100 ఉచితం).");
        button("ఈ గొంతు వినిపించు", v -> testCloud(CloudVoice.SARVAM));
        sarvamInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(sarvamInfo);

        // ---- live conversation
        section("Live సంభాషణ (Real-time)");
        note("ChatGPT వాయిస్ లాగా, ఫ్రెండ్‌తో మాట్లాడినట్టే: మీరు మాట్లాడుతుంటే వింటుంది, వెంటనే జవాబిస్తుంది, మధ్యలో ఆపి మాట్లాడొచ్చు. Gemini Live కి Gemini key, OpenAI Live కి OpenAI key కావాలి. "
                + "సాధారణ మోడ్ కంటే ఎక్కువ ఖర్చు అవుతుంది. ఎవరూ మాట్లాడకపోతే పైన 'వినే సమయం' (మొదట 8 సెకన్లు) తర్వాత మైక్ ఆపి నిశ్శబ్దంగా ఆగిపోతుంది.");
        liveMode = toggle("\"Hey Jarvis\" అన్నా Live సంభాషణే మొదలవ్వాలి (నీలం బటన్‌తో Live ఎప్పుడైనా వస్తుంది)", prefs.liveMode());
        TextView lp = Ui.text(this, "Live ఎవరితో:", 15, Ui.TEXT);
        lp.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        box.addView(lp);
        liveProvider = new RadioGroup(this);
        liveProvider.addView(radio(91, "⚡ Gemini Live (Google · మీ Gemini key)"));
        liveProvider.addView(radio(92, "OpenAI Live (Realtime · మీ OpenAI key)"));
        liveProvider.check(prefs.liveGemini() ? 91 : 92);
        box.addView(liveProvider);
        geminiLiveModel = field("Gemini Live మోడల్", prefs.sp.getString("gemini_live_model", "").trim().isEmpty() ? Prefs.DEFAULT_GEMINI_LIVE_MODEL
                : prefs.sp.getString("gemini_live_model", "").trim(), false);
        liveVoiceChoice = prefs.geminiLiveVoice();
        liveVoiceLabel = Ui.text(this, "", 15, Ui.TEXT);
        liveVoiceLabel.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        box.addView(liveVoiceLabel);
        showLiveVoice();
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        TextView pick = Ui.pill(this, "🎙️ గొంతు మార్చు");
        TextView hear = Ui.pill(this, "▶ విను");
        pick.setOnClickListener(v -> pickLiveVoice());
        hear.setOnClickListener(v -> previewVoice(liveVoiceChoice));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.rightMargin = Ui.dp(this, 10);
        vr.addView(pick, vp);
        vr.addView(hear, new LinearLayout.LayoutParams(-2, -2));
        box.addView(vr);
        TextView lt = Ui.text(this, "🧠 Gemini Live లో ఆలోచించేది:", 15, Ui.TEXT);
        lt.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        box.addView(lt);
        liveThink = new RadioGroup(this);
        liveThink.addView(radio(98, "Jarvis మెదడు (సెట్టింగ్స్ → Jarvis మెదడు లో మీరు ఎంచుకున్న మోడల్): Live వింటుంది, మాట్లాడుతుంది; ప్రతి జవాబూ మీ మెదడే ఆలోచిస్తుంది, పాత పద్ధతి అంత తెలివి. జవాబుకు పాత పద్ధతి అంత టైమ్ (సిఫార్సు)"));
        liveThink.addView(radio(99, "Gemini Live తనే: చాలా వేగం, కానీ లోతైన ఆలోచన తక్కువ. స్క్రీన్ / కెమెరా / ఫోటోలు / బుకింగ్ / 'బాగా ఆలోచించి చెప్పు' లాంటివి మెదడుకు ఇస్తుంది"));
        liveThink.check(prefs.liveBrainThinks() ? 98 : 99);
        box.addView(liveThink);
        note("'Jarvis మెదడు' లో: Live మీ మాట విని మీ మెదడుకి ఇస్తుంది, మెదడు చెప్పిందే మాట మార్చకుండా చెబుతుంది (వార్తలు, వాతావరణం, సెర్చ్, మెసేజ్‌లు, ఫోన్ పనుల లాంటి టైమ్ పట్టేవాటికి మాత్రమే \"ఒక్క క్షణం\" అంటుంది; మీ పేరు అడిగితే వెంటనే చెబుతుంది). "
                + "'హాయ్', 'థాంక్స్', 'బై', గొంతు మార్చడం మాత్రమే Live తనే చేస్తుంది. English practice, అనువాదకుడు ఎప్పుడూ Live తోనే (వేగం కోసం). "
                + "మెసేజ్‌లు పంపడం, కాల్స్ మీరు 'పంపు' / 'అవును' అన్నాకే. Live తనే జవాబిస్తే అది \"Jarvis చెక్\" లో కనిపిస్తుంది. "
                + "గొంతులు 30 (16 మగ, 14 ఆడ): 'గొంతు మార్చు' లో ఒకటి నొక్కితే ఆ గొంతులో ఒక మాట వినిపిస్తుంది. Live లో 'Jarvis, గొంతు మార్చు' / 'ఇంకో గొంతు' / 'Puck గొంతు పెట్టు' అన్నా మారుతుంది. "
                + "మోడల్ పేరు తప్పైతే ఎందుకో చెబుతుంది.");
        livePatient = toggle("మీరు మాట పూర్తి చేసే వరకు ఆగి, తర్వాతే జవాబివ్వు (మధ్యలో ఆలోచిస్తూ ఆగినా కట్ చేయదు)", prefs.livePatient());
        bargeIn = toggle("Jarvis మాట్లాడుతుండగా మధ్యలో మాట్లాడితే ఆగి వినాలి", prefs.bargeIn());
        TextView lb = Ui.text(this, "Gemini Live లో Jarvis మాట్లాడుతుండగా ఆపడం (ఫోన్ స్పీకర్‌లో):", 15, Ui.TEXT);
        lb.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        box.addView(lb);
        liveBarge = new RadioGroup(this);
        liveBarge.addView(radio(95, "మీరు మాట్లాడగానే ఆగి వింటుంది, Gemini లా (Jarvis తన గొంతుని మైక్ నుంచి బాగా తీసేయగలిగినప్పుడు); లేకపోతే \"Jarvis\" లేదా \"stop\" అంటే ఆగుతుంది (సిఫార్సు)"));
        liveBarge.addView(radio(96, "మీరు మాట్లాడగానే ఆగుతుంది, Gemini లా (Jarvis తన గొంతుని బాగా తీసేయగలిగినప్పుడు); లేకపోతే శబ్దం బట్టి (గట్టిగా మాట్లాడాలి)"));
        liveBarge.addView(radio(97, "మధ్యలో ఆగదు: పూర్తయ్యాక వింటుంది"));
        String lb0 = prefs.liveBarge();
        liveBarge.check("voice".equals(lb0) ? 96 : "off".equals(lb0) ? 97 : 95);
        box.addView(liveBarge);
        liveAec = toggle("Jarvis సొంత echo తీసివేత: Jarvis తాను ప్లే చేసే గొంతుని తానే మైక్ నుంచి తీసేస్తుంది (Gemini యాప్ లాగా), అప్పుడు మీరు మాట్లాడగానే ఆగుతుంది "
                + "(Live లో, మామూలు మాటల్లో OpenAI సహజ గొంతుతో కూడా; ఫోన్ గొంతుతో మాట్లాడుతున్నప్పుడు \"Jarvis\" / \"stop\" అంటే ఆగుతుంది)", prefs.liveAec());
        note("Jarvis మాట్లాడుతుండగా తన గొంతుని నేర్చుకుని (మొదటిసారి కొన్ని సెకన్లు, తర్వాత వెంటనే), సరిగ్గా తీసేయగలిగితేనే Gemini లా మీ మాటకి ఆగుతుంది; లేకపోతే పై పద్ధతి. ఏది పనిచేస్తోందో \"Jarvis చెక్\" లో కనిపిస్తుంది. ఏ పద్ధతిలోనైనా: Jarvis మాట్లాడుతుండగా స్క్రీన్‌పై Jarvis (గుండ్రటి గుర్తు) నొక్కితే వెంటనే ఆగి వింటుంది. ఇయర్‌ఫోన్స్ / హెల్మెట్‌తో మీరు మాట్లాడగానే ఆగుతుంది.");
        echoRecord = toggle("Echo పరీక్ష రికార్డింగ్: ప్రతి Gemini Live తర్వాత చివరి 30 సెకన్లు (మైక్, Jarvis గొంతు, శుభ్రం చేసిన మైక్) Downloads/Jarvis లో సేవ్ (సరిచేయడానికి నాకు పంపవచ్చు; మామూలుగా ఆఫ్)", prefs.echoRecord());
        bargeSensLabel = Ui.text(this, "", 15, Ui.MUTED);
        box.addView(bargeSensLabel);
        bargeSens = new SeekBar(this);
        bargeSens.setMax(4);
        bargeSens.setProgress(Math.max(0, Math.min(4, prefs.bargeSens())));
        bargeSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { showBargeSens(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(bargeSens);
        showBargeSens();
        note("మీ మాట విని ఆగకపోతే స్లైడర్ కుడివైపు జరపండి; Jarvis తన గొంతుకే తానే ఆగిపోతుంటే ఎడమవైపు జరపండి. ఇయర్‌ఫోన్స్/బ్లూటూత్‌తో ఇంకా బాగా పనిచేస్తుంది.");
        bargeCallVoice = toggle("ఫోన్ కాల్ పద్ధతి (పాతది, Live లో, మామూలు మాటల్లో కూడా): Jarvis మాట్లాడేటప్పుడు ఫోన్ కాల్ మోడ్. మధ్యలో మాట్లాడితే ఫోన్ బాగా వింటుంది, "
                + "కానీ గొంతు కాల్ లాగా ఉంటుంది, volume buttons కాల్ volume కి వెళ్తాయి. మామూలుగా ఆఫ్ ఉంచండి (గొంతు క్లియర్‌గా, AI assistant volume లో); "
                + "Jarvis తనంతట తానే ఆగిపోతుంటే లేదా మీ మాట అసలు వినకపోతే మాత్రమే ఆన్ చేయండి", prefs.bargeCallVoice());
        realtimeModel = field("OpenAI Live మోడల్", prefs.realtimeModel(), false);
        modelPicker(Models.REALTIME, openAiKey, realtimeModel, "realtime_model");
        // "⚡ Gemini Live" in the voice choice and the Live switches above stay in step
        earsMode.setOnCheckedChangeListener((rg, id) -> {
            earsOtherBox.setVisibility(id == 84 ? View.VISIBLE : View.GONE);
            if (id == 81) earsOther.check(85); else if (id == 82) earsOther.check(86); else if (id == 83) earsOther.check(87); // (the same choice below)
            else if (id == 88) earsOther.check(89);
            if (syncingVoice) { earsWas = id; return; }
            syncingVoice = true;
            if (id == 84) { liveMode.setChecked(true); liveProvider.check(91); }
            else if (earsWas == 84 && liveMode.isChecked()) liveMode.setChecked(false); // from Gemini Live to the usual listen-then-answer
            earsWas = id;
            syncingVoice = false;
        });
        liveMode.setOnCheckedChangeListener((sw, on) -> syncLiveChoice());
        liveProvider.setOnCheckedChangeListener((rg, which) -> syncLiveChoice());
        note("mini మోడల్ చవక, వేగం. పేరులో mini లేని పెద్ద మోడల్ ఇంకా సహజంగా, భావంతో మాట్లాడుతుంది కానీ ఖర్చు ఎక్కువ.");

        speedCard(); // ⚡ వేగం

        // ---- without internet
        section("Offline వాయిస్ (నెట్ లేనప్పుడు)");
        offlineAuto = toggle("నెట్ లేనప్పుడు Jarvis తనంతట తానే offline కి మారాలి: ఫోన్‌లోనే విని, ఫోన్‌లో ఉన్న పనులు చేస్తుంది; నెట్ రాగానే మళ్లీ మామూలుగా", prefs.offlineAuto());
        offlineInfo = Ui.text(this, "ఫోన్‌లో ఏమున్నాయో చూస్తున్నాను…", 15, Ui.TEXT);
        offlineInfo.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        box.addView(offlineInfo);
        LinearLayout orow = new LinearLayout(this);
        orow.setOrientation(LinearLayout.VERTICAL);
        offlineHearBtn = Ui.pill(this, "⬇ ఫోన్ తెలుగు offline వినడం డౌన్‌లోడ్");
        offlineEnBtn = Ui.pill(this, "⬇ English offline వినడం డౌన్‌లోడ్");
        offlineVoiceBtn = Ui.pill(this, "⬇ తెలుగు offline గొంతు డౌన్‌లోడ్");
        TextView orecheck = Ui.pill(this, "🔄 మళ్లీ చూడు");
        offlineHearBtn.setOnClickListener(v -> offlineDownload(OfflineKit.TE));
        offlineEnBtn.setOnClickListener(v -> offlineDownload(OfflineKit.EN));
        offlineVoiceBtn.setOnClickListener(v -> {
            if (!OfflineKit.downloadVoice(this)) Toast.makeText(this, "ఫోన్ గొంతు డౌన్‌లోడ్ పేజీ తెరవలేకపోయాను. ఫోన్ Settings → Text-to-speech లో చూడండి.", Toast.LENGTH_LONG).show();
        });
        orecheck.setOnClickListener(v -> checkOffline());
        for (TextView ob : new TextView[]{offlineHearBtn, offlineEnBtn, offlineVoiceBtn, orecheck}) {
            LinearLayout.LayoutParams obp = new LinearLayout.LayoutParams(-2, -2);
            obp.topMargin = Ui.dp(this, 8);
            orow.addView(ob, obp);
        }
        box.addView(orow);
        note("నెట్ లేనప్పుడు ఇవి పనిచేస్తాయి (ఇలా చెప్పండి): టార్చ్ ఆన్ · అమ్మకి కాల్ చేయి · అమ్మకి వస్తున్నా అని SMS పంపు (చదివి వినిపించి, మీరు \"పంపు\" అన్నాకే) · "
                + "రేపు ఉదయం 6 కి పాలు తేవాలని గుర్తు చేయి · ఉదయం 5:30 కి అలారం · 10 నిమిషాల టైమర్ · పెట్రోల్‌కి 200 ఖర్చు రాయి · ఈ నెల ఖర్చు ఎంత · "
                + "250 ని 12 తో గుణిస్తే · రాముకి 500 ఇచ్చాను · నాకు ఎవరు ఎంత ఇవ్వాలి · రేపు డ్యూటీ ఉందా · నోట్ రాసుకో … · డైరీలో రాయి … · లిస్ట్‌లో పాలు పెట్టు · "
                + "బండి ఇక్కడ పెట్టాను / బండి ఎక్కడ · రేపు క్యాలెండర్‌లో ఏమున్నాయి · పుట్టినరోజులు · పండుగలు · BP మాత్ర వేసుకున్నాను · మెసేజ్‌లు చదువు · "
                + "బ్లూటూత్ ఆన్ · బ్రైట్‌నెస్ తగ్గించు · ఫోన్‌లో పాటలు పెట్టు · బ్యాటరీ ఎంత. "
                + "వార్తలు, వాతావరణం లాంటి నెట్ కావాల్సిన ప్రశ్నలు గుర్తుంచుకుని, నెట్ రాగానే మెదడుతో జవాబు చెబుతుంది. "
                + "మీ మెదడు (AI) నెట్ లేకుండా పనిచేయదు, అందుకే offline లో పై లాంటి పనులు మాత్రమే. "
                + "నెట్ లేకుండా తెలుగులో వినడానికి \"Jarvis తెలుగు వినడం\" ఒక్కసారి డౌన్‌లోడ్ చేయాలి (నెట్ ఉన్నప్పుడు, సుమారు 58 MB). "
                + "వినడం అంతా ఫోన్‌లోనే, ఏదీ బయటికి వెళ్లదు. ఇది చిన్న మోడల్: పై లాంటి పనుల మాటలు బాగా వింటుంది; పొడవైన వాక్యాలు, పేర్లు "
                + "కొన్నిసార్లు తప్పుగా వినొచ్చు (ఏం విన్నదో స్క్రీన్‌పై కనిపిస్తుంది). జవాబులు ఫోన్ తెలుగు గొంతుతో.");

        offlineReadPack(); // (the camera's offline English → Telugu; it was in the watch card)

        // ---- wake word
        section("\"Hey Jarvis\" వేక్ వర్డ్");
        note("ఫోన్ లాక్‌లో ఉన్నా \"Hey Jarvis\" అని పిలిస్తే తెరుచుకుంటుంది. ఏ key అవసరం లేదు. వినడం అంతా ఫోన్‌లోనే జరుగుతుంది, ఏ ఆడియో బయటికి వెళ్లదు.");
        compactPanel = toggle("పిలిస్తే Google లాగా చిన్న ప్యానెల్ (\"చెప్పండి Anil?\")", prefs.compactPanel());
        listenOnOpen = toggle("యాప్ తెరవగానే వినడం మొదలుపెట్టు", prefs.listenOnOpen());
        camAlways = toggle("📷 Jarvis కెమెరాలో ఎప్పుడూ వింటూ ఉండు (ఆఫ్: \"Jarvis\" అన్నప్పుడు / 🎙️ నొక్కినప్పుడు మాత్రమే; ఆన్ చేస్తే ఫోన్ మైక్ బీప్ తరచుగా వస్తుంది)", prefs.camAlwaysListen());
        note("దీనితో \"Hey Google, open Jarvis\" అంటే Google తన చిప్‌తో విని Jarvis ని తెరుస్తుంది, Jarvis వెంటనే మీ మాట వింటుంది. ఈ పద్ధతిలో Jarvis మైక్ బ్యాక్‌గ్రౌండ్‌లో అసలు ఆన్ అవ్వదు. అప్పుడు కింది వేక్ వర్డ్ ఆఫ్ చేయవచ్చు.");
        wake = toggle("వేక్ వర్డ్ ఆన్", prefs.wakeWord());
        TextView ww = Ui.text(this, "మైక్ ఎప్పుడు వినాలి?", 15, Ui.MUTED);
        ww.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(ww);
        wakeWhen = new RadioGroup(this);
        wakeWhen.addView(radio(23, "ఎప్పుడూ: స్క్రీన్ ఆఫ్‌లో ఉన్నా \"Jarvis\" అంటే స్క్రీన్ ఆన్ అయి వింటుంది (సిఫార్సు)"));
        wakeWhen.addView(radio(21, "స్క్రీన్ ఆన్‌లో ఉన్నప్పుడు మాత్రమే (బ్యాటరీ ఆదా)"));
        wakeWhen.addView(radio(22, "ఛార్జింగ్‌లో ఉన్నప్పుడు మాత్రమే"));
        String when = prefs.wakeWhen();
        wakeWhen.check("charging".equals(when) ? 22 : "screen_on".equals(when) ? 21 : 23);
        box.addView(wakeWhen);
        Switch annWake = toggle("Jarvis ఏదైనా చెబుతుండగా (రిమైండర్, ప్రకటన) \"Jarvis\" అంటే ఆగి వినాలి", prefs.announceWake());
        annWake.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("announce_wake", v).apply());
        note("ఆఫ్ (మొదట్లో): Jarvis తన మాటల్లో \"Jarvis\" అన్నా తనంతట తానే మేల్కోడు. ఆన్: ప్రకటన మధ్యలో మీరు పిలిస్తే ఆగి వింటాడు "
                + "(కానీ అప్పుడప్పుడు తన మాటకే మేల్కోవచ్చు).");
        note("నోటిఫికేషన్‌లో \"ఆపు\" నొక్కితే మైక్ కాసేపు ఆగుతుంది; Jarvis యాప్ మళ్లీ తెరవగానే తనంతట తానే ఆన్ అవుతుంది.");
        note("\"Hey Google\" కోసం ఫోన్‌లో ఒక ప్రత్యేక చిన్న చిప్ ఉంటుంది, అది Google కి మాత్రమే అందుబాటులో ఉంటుంది. అందుకే \"Jarvis\" అని పిలవడం వినాలంటే మైక్ ఆన్‌లో ఉండాలి. మైక్ పూర్తిగా ఆఫ్ ఉండాలంటే వేక్ వర్డ్ ఆఫ్ చేసి, కింది మార్గాల్లో పిలవండి: పవర్ బటన్ నొక్కి పట్టుకోవడం, పైనుంచి కిందికి స్వైప్ చేసి \"Jarvis\" టైల్ నొక్కడం, లేదా Jarvis ఐకాన్ నొక్కి పట్టుకుని \"Jarvis తో మాట్లాడు\" షార్ట్‌కట్.");
        jarvisWord = toggle("\"Jarvis\" ఒక్క పదంతో కూడా మేల్కొను (ప్రధానం; \"Hey Jarvis\" ఎప్పుడూ పనిచేస్తుంది)", prefs.jarvisWord());
        note("\"Jarvis\" పదం కోసం మొదటిసారి సుమారు 40 MB ఫైల్ ఒక్కసారి డౌన్‌లోడ్ అవుతుంది (Wi-Fi లో ఉంటే మంచిది). టీవీ, మాటల్లో \"Jarvis\" వినిపించి తప్పుగా మేల్కొంటుంటే ఇది ఆఫ్ చేయండి.");
        sensitivityLabel = Ui.text(this, "", 15, Ui.MUTED);
        box.addView(sensitivityLabel);
        sensitivity = new SeekBar(this);
        sensitivity.setMax(50);
        // left = strict (0.75), right = sensitive (0.25)
        sensitivity.setProgress(Math.round((0.75f - prefs.wakeThreshold()) * 100));
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { showSensitivity(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sensitivity);
        showSensitivity();
        voiceLock = toggle("నా గొంతుకి మాత్రమే పలుకు (ప్రయోగాత్మకం)", prefs.voiceLock());
        note("టీవీలో, వేరేవాళ్లు \"Jarvis\" అంటే పలకదు. ముందు కింది బటన్‌తో మీ గొంతు నేర్పించండి (సుమారు 13 MB ఒక్కసారి డౌన్‌లోడ్). మీరు పిలిచినా పలకకపోతే స్లైడర్ కుడివైపు జరపండి.");
        button("మీ గొంతు నేర్పించండి (5 సార్లు \"Jarvis\" అనండి)", v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                return;
            }
            VoiceEnroll.start(this);
        });
        lockInfo = Ui.text(this, "", 14, Ui.MUTED);
        lockInfo.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(lockInfo);
        lockSlider = new SeekBar(this);
        lockSlider.setMax(60); // 0.30 .. 0.90
        lockSlider.setProgress(Math.round((prefs.voiceLockMax() - 0.30f) * 100));
        lockStart = lockSlider.getProgress();
        prefs.sp.registerOnSharedPreferenceChangeListener(lockCalibrated);
        lockSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { showLock(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(lockSlider);
        showLock();
        button("\"Display over other apps\" అనుమతి ఇవ్వండి", v -> {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        });
        button("బ్యాటరీ సేవర్ నుంచి మినహాయించండి", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        wakeInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(wakeInfo);

        watchSection();

        // ---- permissions & data
        // ---- 📞 calls (their own card), then messages, reminders, morning briefing
        callsSection();
        section("మెసేజ్‌లు, ఉదయం బ్రీఫింగ్");
        readMessages = toggle("కొత్త మెసేజ్ వస్తే (WhatsApp, SMS, Telegram, Instagram, Facebook, Snapchat...) ఎవరి నుంచో చెప్పి, \"చదవమంటారా?\" అని అడుగు", prefs.readMessages());
        TextView gl = Ui.text(this, "👥 గ్రూప్ మెసేజ్‌లు (WhatsApp గ్రూప్‌లు) చెప్పడం:", 15, Ui.TEXT);
        gl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        box.addView(gl);
        groupMode = new RadioGroup(this);
        groupMode.addView(radio(65, "నా పేరు ఉంటేనే (గ్రూప్‌లో మిమ్మల్ని పిలిస్తేనే చెప్పు) · సిఫార్సు"));
        groupMode.addView(radio(64, "అన్నీ చెప్పు"));
        groupMode.addView(radio(66, "వద్దు (గ్రూప్ మెసేజ్‌లు ఎప్పుడూ చెప్పకు)"));
        String gm0 = prefs.groupMode();
        groupMode.check("all".equals(gm0) ? 64 : "none".equals(gm0) ? 66 : 65);
        box.addView(groupMode);
        myNames = field("గ్రూప్‌లో మిమ్మల్ని పిలిచే పేర్లు (కామాతో)", prefs.myNames(), false);
        myNames.setHint("Anil, అనిల్, @Anil");
        note("మీరు వేరే యాప్‌లో ఉన్నప్పుడు మెసేజ్ వస్తే కింది నుంచి పెద్ద ప్యానెల్ రాదు: పైన చిన్న కార్డ్ వస్తుంది. Jarvis అక్కడే ఎవరి నుంచో చెప్పి "
                + "\"చదవమంటారా?\" అని అడుగుతాడు, చదివి, \"రిప్లై ఇవ్వమంటారా?\" అని అడిగి, మీ జవాబు చదివి వినిపించి \"పంపమంటారా?\" అన్నాకే పంపుతాడు. "
                + "అంతా background‌లోనే: మీరు వాడుతున్న యాప్ ఆగదు, కీబోర్డ్ మూసుకోదు. ప్రతి స్టెప్ కార్డ్‌లోని బటన్‌తోనూ చేయొచ్చు. "
                + "⏰ తర్వాత అంటే అది ఫ్లోటింగ్ బటన్ మీద చుక్కగా (📬) ఉంటుంది: ఫ్లోటింగ్ బటన్ → 📬 లిస్ట్‌లో ఒక్కో చాట్ వినొచ్చు, రిప్లై ఇవ్వొచ్చు. కార్డ్ అడ్డం వస్తే పైకి స్వైప్ చేయండి: అది కిందకి లాగే నోటిఫికేషన్ బార్‌లోకి వెళ్ళి "
                + "అక్కడే నడుస్తుంది (దాని బటన్లు అక్కడే; నొక్కితే కార్డ్ మళ్ళీ పైన; పక్కకి తోసేస్తే మూసుకుంటుంది). చదివేటప్పుడు ⏸ / ▶ / ⏪. "
                + "కింది పెద్ద ప్యానెల్ మీరు \"Jarvis\" అని పిలిచినప్పుడు మాత్రమే. దగ్గు, నీళ్లు, డైరీ లాంటి Jarvis సొంత ప్రశ్నలు కూడా పైన కార్డ్‌లోనే. "
                + "ఫోన్ లాక్‌లో / స్క్రీన్ ఆఫ్‌లో ఉంటే స్క్రీన్ వెలగదు: Jarvis మాటతో చెప్పి మీ జవాబు వింటాడు. OTP అంకెలు ఎప్పుడూ గట్టిగా చదవడు.");
        TextView cl = Ui.text(this, "🗂️ పైన కార్డ్ (ఎవరూ నొక్కకపోతే ఎంతసేపు ఉండాలి):", 15, Ui.TEXT);
        cl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        box.addView(cl);
        RadioGroup cardSecs = new RadioGroup(this);
        cardSecs.setOrientation(RadioGroup.HORIZONTAL);
        int[] secs = {5, 10, 20};
        for (int sc : secs) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sc + " సెకన్లు");
            rb.setTextColor(Ui.TEXT);
            rb.setId(View.generateViewId());
            cardSecs.addView(rb);
            if (sc == prefs.cardSecs()) cardSecs.check(rb.getId());
            rb.setOnCheckedChangeListener((bt, on) -> { if (on) prefs.sp.edit().putInt("card_secs", sc).apply(); });
        }
        box.addView(cardSecs);
        Switch cardBig = toggle("🔠 కార్డ్‌లో పెద్ద అక్షరాలు", prefs.cardBig());
        cardBig.setOnCheckedChangeListener((bt, v) -> prefs.sp.edit().putBoolean("card_big", v).apply());
        Switch cardLow = toggle("⬇️ కార్డ్ కొంచెం కిందికి (యాప్ పై భాగానికి అడ్డం రాకుండా)", prefs.cardLow());
        cardLow.setOnCheckedChangeListener((bt, v) -> prefs.sp.edit().putBoolean("card_low", v).apply());
        // 📬 the later-messages box (Anil, 23 Oct)
        TextView lk = Ui.text(this, "📬 తర్వాత మెసేజ్‌లు ఎంతసేపు ఉంచాలి:", 15, Ui.TEXT);
        lk.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        box.addView(lk);
        RadioGroup keepH = new RadioGroup(this);
        keepH.setOrientation(RadioGroup.HORIZONTAL);
        int[] hrs = {12, 24};
        for (int h : hrs) {
            RadioButton rb = new RadioButton(this);
            rb.setText(h + " గంటలు");
            rb.setTextColor(Ui.TEXT);
            rb.setId(View.generateViewId());
            keepH.addView(rb);
            if (h == LaterMessages.keepHours(this)) keepH.check(rb.getId());
            rb.setOnCheckedChangeListener((bt, on) -> { if (on) prefs.sp.edit().putInt("later_keep_h", h).apply(); });
        }
        box.addView(keepH);
        Switch laterNote = toggle("📲 📬 నోటిఫికేషన్ బార్‌లో కూడా చూపించు (శబ్దం లేకుండా)", prefs.sp.getBoolean("later_note", true));
        laterNote.setOnCheckedChangeListener((bt, v) -> { prefs.sp.edit().putBoolean("later_note", v).apply(); LaterBox.noteChanged(this); });
        Switch laterRemind = toggle("⏰ 📬 లో మెసేజ్‌లు గంట నుంచి ఉంటే, మీరు ఖాళీగా ఉన్నప్పుడు ఒక్కసారి గుర్తుచేయి", prefs.sp.getBoolean("later_remind", true));
        laterRemind.setOnCheckedChangeListener((bt, v) -> prefs.sp.edit().putBoolean("later_remind", v).apply());
        final TextView quietInfo = Ui.text(this, "", 14, Ui.MUTED);
        quietInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(quietInfo);
        Runnable showQuiet = () -> {
            java.util.List<String> q = LaterMessages.quietNames(this);
            quietInfo.setText(q.isEmpty() ? "🔕 కార్డ్‌లో చెప్పని చాట్‌లు: ఏవీ లేవు (📬 లిస్ట్‌లో ఒక చాట్ పక్కన 🔕 నొక్కితే ఇక్కడ వస్తుంది)"
                    : "🔕 కార్డ్‌లో చెప్పని చాట్‌లు (నేరుగా 📬 లోకి): " + String.join(", ", q));
        };
        showQuiet.run();
        button("🔔 అన్ని చాట్‌లు మళ్ళీ కార్డ్‌లో చెప్పు", v -> { LaterMessages.clearQuiet(this); showQuiet.run(); });
        TextView tl = Ui.text(this, "⌨️ మీరు టైప్ చేస్తున్నప్పుడు మెసేజ్ వస్తే:", 15, Ui.TEXT);
        tl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        box.addView(tl);
        typingMode = new RadioGroup(this);
        typingMode.addView(radio(71, "మామూలుగానే చెప్పి చదువు (కార్డ్‌లో, background‌లో)"));
        typingMode.addView(radio(72, "ఎవరి నుంచో మాత్రమే చెప్పు"));
        typingMode.addView(radio(73, "నిశ్శబ్దం: కార్డ్ మాత్రమే (🔊 నొక్కితే చదువుతాను)"));
        String tm0 = prefs.typingMode();
        typingMode.check("name".equals(tm0) ? 72 : "silent".equals(tm0) ? 73 : 71);
        box.addView(typingMode);
        readNews = toggle("📰 Way2News వార్త వచ్చిన వెంటనే చదివి వినిపించు", prefs.readNews());
        newsPlaces = field("📍 లోకల్ వార్తల ప్రాంతాలు (కామాతో: రాష్ట్రాలు, జిల్లాలు, ఊర్లు)", prefs.newsPlaces(), false);
        newsPlaces.setHint("తెలంగాణ, ఆంధ్రప్రదేశ్, మీ జిల్లా, మీ ఊరు");
        newsAuto = toggle("📰 ఈ ప్రాంతాల కొత్త వార్తలు ఉదయం 8, మధ్యాహ్నం 1, సాయంత్రం 7 కి తనంతట తానే చదువు", prefs.newsAuto());
        batteryWarn = toggle("బ్యాటరీ 15%, 5% కి పడితే గొంతుతో చెప్పు", prefs.batteryWarn());
        briefing = toggle("రోజూ ఉదయం బ్రీఫింగ్ తనంతట తానే", prefs.briefingOn());
        briefHour = prefs.briefingHour();
        briefMinute = prefs.briefingMinute();
        briefingTime = Ui.text(this, "", 15.5f, Ui.CYAN);
        briefingTime.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        briefingTime.setOnClickListener(v -> new android.app.TimePickerDialog(this, (tp, h, m) -> {
            briefHour = h;
            briefMinute = m;
            showBriefingTime();
        }, briefHour, briefMinute, true).show());
        box.addView(briefingTime);
        showBriefingTime();
        briefingSpeak = toggle("బ్రీఫింగ్‌ని పైకి వినిపించు", prefs.briefingSpeak());

        // ---- screen
        section("స్క్రీన్ చూడటం");
        note("\"నా స్క్రీన్‌లో ఏముంది?\", \"ఈ మెసేజ్‌కి ఏం రిప్లై ఇవ్వాలి?\" అని అడగాలంటే Accessibility లో \"Jarvis స్క్రీన్\" ఆన్ చేయండి. మీరు Jarvis ని పిలిచిన క్షణంలో ఉన్న స్క్రీన్‌ని మాత్రమే చూస్తుంది, మీరు అడిగినప్పుడే AI కి పంపుతుంది.");
        button("Accessibility తెరవండి", v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        screenInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(screenInfo);
        note("Android 13 పైన స్విచ్ బూడిద రంగులో ఉండి నొక్కలేకపోతే: కింది బటన్ → పైన కుడివైపు ⋮ → \"Allow restricted settings\" → మళ్లీ ప్రయత్నించండి. నోటిఫికేషన్ యాక్సెస్‌కి కూడా ఇదే.");
        button("Jarvis App info తెరవండి", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        });

        // ---- power button
        section("పవర్ బటన్ అసిస్టెంట్");
        note("పవర్/హోమ్ బటన్ నొక్కి పట్టుకుంటే Jarvis రావాలంటే: కింది బటన్ → Digital assistant app → Jarvis ఎంచుకోండి. Samsung లో: Settings → Advanced features → Side button → \"Press and hold\" → Digital assistant. లేదా \"Double press\" → Open app → Jarvis.");
        button("Default apps తెరవండి", v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
            catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); } catch (Exception ignored) {}
            }
        });

        // ---- notifications
        section("మెసేజ్ నోటిఫికేషన్లు");
        note("\"WhatsApp లో ఎవరు మెసేజ్ చేశారు?\", \"రవికి సరే అని రిప్లై ఇవ్వు\" లాంటివి అడగాలంటే ఈ అనుమతి ఇవ్వండి. Jarvis మీరు అడిగినప్పుడే మెసేజ్‌లు చదువుతుంది; అప్పుడు ఆ టెక్స్ట్ సమాధానం కోసం AI కి వెళ్తుంది. రిప్లై పంపే ముందు మిమ్మల్ని అడుగుతుంది.");
        button("నోటిఫికేషన్ యాక్సెస్ ఇవ్వండి", v -> {
            try { startActivity(NotifyListener.settingsIntent()); } catch (Exception ignored) {}
        });
        notifyInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(notifyInfo);

        section("మోసం గార్డ్");
        scamGuard = toggle("మోసం మెసేజ్‌లు, కొత్త autopay లు వస్తే హెచ్చరించు", prefs.scamGuard());
        note("SMS, WhatsApp, Telegram, Gmail లో వచ్చే కొత్త మెసేజ్‌లను Jarvis ఫోన్‌లోనే చెక్ చేస్తాడు (ఏదీ బయటికి పంపడు, ఖర్చు లేదు): fake KYC, "
                + "\"OTP / PIN చెప్పండి\", అనుమానపు links, లాటరీ, కరెంట్ కట్ బెదిరింపు, APK ఫైల్స్, డబ్బు రావడానికి UPI PIN అడగడం… అనుమానం వస్తే వెంటనే notification. "
                + "మీ card మీద కొత్త autopay / mandate పెట్టినట్టు SMS వస్తే కూడా చెప్తాడు. దీనికి పై 'నోటిఫికేషన్ యాక్సెస్' కావాలి.");

        section("Jarvis తనంతట తానే");
        proactive = toggle("అడగకుండానే ముఖ్యమైనవి చెప్పు: మీటింగ్ దగ్గర పడితే, వర్షం వస్తే, ఇష్టమైనవాళ్లకి చాలా రోజులుగా ఫోన్ చేయకపోతే, మీ అలవాట్లు, నీళ్లు, ధర హెచ్చరికలు", prefs.proactive());
        note("రాత్రి మోడ్‌లో, కాల్ మాట్లాడుతున్నప్పుడు, Do Not Disturb లో మాట్లాడదు. \"ఇష్టమైనవాళ్లు\" = Contacts లో ⭐ పెట్టినవాళ్లు.");

        section("స్మార్ట్ హోమ్ (లైట్లు, ఫ్యాన్లు)");
        note("మూడు మార్గాలు, Jarvis వరుసగా ప్రయత్నిస్తుంది:\n1) Alexa రొటీన్ లింక్‌లు (అన్నింటికన్నా నమ్మకమైనది): Voice Monkey లేదా URL Routine Trigger అనే Alexa skill లో ఒక్కో పనికి ఒక trigger చేసి, Alexa యాప్‌లో ఆ trigger తో రొటీన్ (ఉదా: హాల్ లైట్ ఆఫ్) పెట్టండి. ఆ trigger లింక్‌ను కింద \"పేరు = లింక్\" గా ఒక్కో లైన్‌లో పెట్టండి.\n2) మీ స్మార్ట్ హోమ్ యాప్ తెరిచి ఆ లైట్ స్విచ్ Jarvis నొక్కుతుంది (Accessibility కావాలి).\n3) దగ్గర్లో Echo ఉంటే Jarvis \"Alexa, …\" అని పైకి చెబుతుంది.");
        TextView su = Ui.text(this, "Alexa రొటీన్ లింక్‌లు (ఉదా: hall light off = https://…)", 14, Ui.MUTED);
        su.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        box.addView(su);
        smartUrls = new EditText(this);
        smartUrls.setText(prefs.smartUrls());
        smartUrls.setTextColor(Ui.TEXT);
        smartUrls.setTextSize(14);
        smartUrls.setSingleLine(false);
        smartUrls.setMinLines(4);
        smartUrls.setGravity(Gravity.TOP | Gravity.START);
        smartUrls.setBackground(fieldBg());
        int sp = Ui.dp(this, 12);
        smartUrls.setPadding(sp, sp, sp, sp);
        smartUrls.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(smartUrls, new LinearLayout.LayoutParams(-1, -2));
        smartApp = field("మీ స్మార్ట్ హోమ్ యాప్ పేరు (ఉదా: Homemate, Zeb Home, Wipro Next)", prefs.smartApp(), false);
        alexaSpeak = toggle("దగ్గర్లో Echo ఉంది: అవసరమైతే Jarvis \"Alexa, …\" అని పైకి చెప్పనివ్వు", prefs.alexaSpeak());

        if (!prefs.homeMode()) tabletSection(); // (his phone: her five cards first in the 🏠 group)
        homeSection();
        linkSection();

        // ---- guard mode: on an old phone at home (buttons act at once; no Save needed)
        section("కాపలా మోడ్ (ఇంట్లో పాత ఫోన్)");
        note("ఇంట్లో ఉంచిన పాత ఫోన్‌లో Jarvis వేసి ఇది ఆన్ చేస్తే, కెమెరాలో కదలిక కనిపించినప్పుడు (మనిషి, జంతువు, వాహనం) ఫోటో, ఒక లైన్ మీ Telegram కి వస్తాయి. "
                + "ఎలా: 1) Telegram లో @BotFather తెరిచి /newbot తో మీ bot చేసి, అది ఇచ్చే token కింద పెట్టండి. 2) Telegram లో మీ కొత్త bot తెరిచి 'hi' పంపండి. "
                + "3) 'Telegram చాట్ కనుక్కో' నొక్కండి. 4) ఫోన్‌ని తలుపు / గేట్ వైపు, ఛార్జర్‌కి పెట్టి 'కాపలా మొదలుపెట్టు' నొక్కండి. "
                + "Token ఈ ఫోన్‌లోనే ఉంటుంది. ఫోటోలు మీ Telegram కి, చూడటానికి మీరు ఎంచుకున్న AI కి మాత్రమే వెళ్తాయి.");
        EditText tgToken = field("Telegram bot token", Guard.token(this), true);
        TextView tgState = Ui.text(this, "", 14, Ui.MUTED);
        tgState.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(tgState);
        Runnable guardState = () -> {
            String chat = Guard.chat(this).isEmpty() ? "Telegram చాట్: ఇంకా లేదు"
                    : "Telegram చాట్: " + Guard.sp(this).getString("tg_name", "సిద్ధం") + " ✓";
            String state = !Guard.running(this) ? "ఆఫ్" : Guard.watching(this) ? "ఆన్, చూస్తోంది 🛡️" : "ఆన్ అని ఉంది కానీ కెమెరా పనిచేయడం లేదు (మళ్ళీ మొదలుపెట్టండి)";
            tgState.setText(chat + "\nకాపలా: " + state + (Guard.dutyOnly(this) ? "\nడ్యూటీ రోజుల్లో మాత్రమే అలర్ట్" : ""));
        };
        guardState.run();
        java.util.function.Consumer<Runnable> withToken = then -> {
            Guard.setToken(this, tgToken.getText().toString());
            new Thread(() -> { then.run(); runOnUiThread(guardState); }).start();
        };
        button("Telegram చాట్ కనుక్కో", v -> withToken.accept(() -> {
            String r = Guard.findChat(this);
            runOnUiThread(() -> Toast.makeText(this, r.startsWith("!") ? r.substring(1) : "దొరికింది: " + r + " ✓ (ఇది మీరేనా చూసుకోండి)", Toast.LENGTH_LONG).show());
        }));
        button("టెస్ట్ మెసేజ్ పంపు", v -> withToken.accept(() -> {
            boolean ok = Guard.send(this, "✅ Jarvis కాపలా మోడ్ టెస్ట్: ఈ మెసేజ్ వచ్చింది అంటే అంతా సిద్ధం.");
            runOnUiThread(() -> Toast.makeText(this, ok ? "పంపాను ✓ Telegram చూడండి" : "పంపలేకపోయాను: token / చాట్ చూడండి", Toast.LENGTH_LONG).show());
        }));
        button("▶ కాపలా మొదలుపెట్టు / ⏹ ఆపు", v -> {
            Guard.setToken(this, tgToken.getText().toString());
            if (Guard.running(this) && Guard.watching(this)) Guard.stop(this);
            else if (Guard.token(this).isEmpty() || Guard.chat(this).isEmpty())
                Toast.makeText(this, "ముందు token పెట్టి 'Telegram చాట్ కనుక్కో' నొక్కండి", Toast.LENGTH_LONG).show();
            else if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 61);
            else {
                Guard.start(this);
                if (HomeGuard.soundsOn(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 62); // the house sounds start once it is given
                Toast.makeText(this, "🛡️ కాపలా మొదలైంది", Toast.LENGTH_SHORT).show();
            }
            tgState.postDelayed(guardState, 4000);
            guardState.run();
        });
        button("డ్యూటీ రోజుల్లో మాత్రమే అలర్ట్: ఆన్ / ఆఫ్", v -> {
            Guard.sp(this).edit().putBoolean("duty_only", !Guard.dutyOnly(this)).apply();
            guardState.run();
        });
        note("'డ్యూటీ రోజుల్లో మాత్రమే' కి ఈ ఫోన్‌లో కూడా డ్యూటీ క్యాలెండర్ సెట్ చేయాలి; లేకపోతే ఎప్పుడూ అలర్ట్ పంపుతుంది. ఇంట్లో వాళ్లు ఉన్నప్పుడు వాళ్ల ఫోటోలు కూడా వెళ్తాయి, అవసరం లేనప్పుడు ఆపండి.");
        Switch guardSounds = toggle("🔊 ఇంట్లో శబ్దాలు కూడా విను (స్మోక్ అలారం, గాజు పగలడం, అరుపు, తలుపు / బెల్, అడుగులు / మాటలు, రాత్రి కుక్క మొరుగుడు) → Telegram", HomeGuard.soundsOn(this));
        guardSounds.setOnCheckedChangeListener((sw, isOn) -> {
            HomeGuard.setSounds(this, isOn);
            if (isOn) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 62);
                Guard.listen(this);
            } else if (Guard.running(this)) {
                if (!prefs.wakeReady() && !Sounds.holdMic(this)) WakeService.stop(this); else WakeService.recheck(this);
            }
        });
        note("కాపలా ఆన్‌లో ఉన్నంత సేపు ఈ ఫోన్ మైక్ వింటుంది (ఛార్జర్‌కి పెట్టి ఉంచండి). శబ్దం వినగానే ఆ క్షణం ఫోటోతో ఒక లైన్ మీ Telegram కి వస్తుంది; "
                + "స్మోక్ అలారం అయితే అది మోగుతున్నంత సేపు ప్రతి నిమిషం (మీరు Telegram లో ఏదైనా జవాబిస్తే ఆపుతుంది). శబ్దం రికార్డ్ చేయదు, పంపదు. "
                + "ఈ ఫోన్‌లో దగ్గు ప్రశ్నలు లాంటివి అడగదు.");
        note("🏠 మీ మెయిన్ ఫోన్‌లో కూడా ఇదే token పెట్టి 'Telegram చాట్ కనుక్కో' నొక్కితే (అక్కడ కాపలా ఆన్ చేయకుండా), \"ఇంట్లో ఎలా ఉంది?\" అంటే ఇంటి ఫోన్ ఫోటో తీసి పంపుతుంది, "
                + "\"ఇంటికి చెప్పు …\" అంటే ఇంట్లో గొంతుతో వినిపిస్తుంది, \"ఇంట్లో కాపలా ఆపు / పెట్టు\" పనిచేస్తాయి. ఇంటి ఫోన్ కాపలా నోటిఫికేషన్‌లో 🎤 బటన్‌తో ఇంట్లో వాళ్లు మీకు వాయిస్ పంపొచ్చు.");
        homePhoneNote();

        section("అత్యవసరం (SOS)");
        note("\"Jarvis help\" / \"కాపాడు\" అంటే 5 సెకన్ల తర్వాత (మధ్యలో ఆపొచ్చు) మీ లొకేషన్ వీళ్లకి SMS వెళ్తుంది, మొదటివాళ్లకి కాల్ వెళ్తుంది.");
        sosContacts = field("కాంటాక్ట్ పేర్లు లేదా నంబర్లు, కామాతో (ఉదా: Amma, Ravi)", prefs.sosContacts(), false);

        section("టికెట్ పేమెంట్ (BookMyShow, District)");
        note("ఆన్ చేస్తే, BookMyShow లేదా District లో సీట్లు సెలెక్ట్ చేశాక Jarvis \"₹___ MobiKwik వాలెట్ నుంచి పే చేయమంటారా?\" అని అడుగుతుంది. మీరు \"అవును, పే చేయి\" అంటేనే "
                + "MobiKwik వాలెట్ నుంచి పే చేస్తుంది. UPI, కార్డ్, నెట్ బ్యాంకింగ్ ఎప్పుడూ వాడదు; PIN, OTP ఎప్పుడూ టైప్ చేయదు (అడిగితే మీరే ఎంటర్ చేయాలి). "
                + "కింద పెట్టిన అమౌంట్ కంటే ఎక్కువైతే పే చేయదు. వాలెట్‌లో ఎంత ఉంచాలో మీ ఇష్టం. వేరేవాళ్ల గొంతుకి పలకకుండా \"నా గొంతుకి మాత్రమే పలుకు\" కూడా ఆన్ చేయడం మంచిది.");
        walletPay = toggle("BookMyShow, District లో MobiKwik వాలెట్ నుంచి Jarvis పే చేయాలి (మీ \"అవును\" తర్వాతే)", prefs.walletPay());
        walletMax = field("ఒక్క బుకింగ్‌కి గరిష్ఠంగా ఎంత వరకు (₹)", String.valueOf(prefs.walletPayMax()), false);
        walletMax.setInputType(InputType.TYPE_CLASS_NUMBER);

        section("WhatsApp మీడియా");
        note("WhatsApp వాయిస్ మెసేజ్‌లు వినిపించడానికి, వీడియోలు, ఫోటోలు చూపించడానికి Jarvis కి WhatsApp మీడియా ఫోల్డర్ అనుమతి ఒక్కసారి ఇవ్వాలి. తెరుచుకునే పేజీలో కింద \"Use this folder\" → \"Allow\" నొక్కండి (ఫోల్డర్ మార్చకండి).");
        button("WhatsApp మీడియా ఫోల్డర్‌కి అనుమతి ఇవ్వండి", v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, WaMedia.pickerStart()), 42);
            } catch (Exception e) {
                Toast.makeText(this, "ఫోల్డర్ పేజీ తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
            }
        });
        waInfo = Ui.text(this, WaMedia.tree(this).isEmpty() ? "ఇంకా అనుమతి ఇవ్వలేదు" : "అనుమతి ఉంది ✓", 14, Ui.MUTED);
        box.addView(waInfo);

        section("డాక్యుమెంట్లు");
        note("ఒక ఫోల్డర్ ఎంచుకుంటే అందులోని PDF లు, ఫోటోలు చదివి \"నా బైక్ ఇన్సూరెన్స్ ఎప్పుడు అయిపోతుంది?\" లాంటివి చెబుతుంది. ఆ పేజీలు జవాబు కోసం AI కి వెళ్తాయి.");
        button("డాక్యుమెంట్ల ఫోల్డర్ ఎంచుకోండి", v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 41);
            } catch (Exception e) {
                Toast.makeText(this, "ఫోల్డర్ ఎంచుకునే పేజీ తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
            }
        });
        docsInfo = Ui.text(this, prefs.docsTree().isEmpty() ? "ఇంకా ఎంచుకోలేదు" : "ఎంచుకున్నారు ✓", 14, Ui.MUTED);
        box.addView(docsInfo);

        section("కార్/బైక్, కదలికలు");
        note("మీ కార్/బైక్ బ్లూటూత్ (లేదా హెల్మెట్ బ్లూటూత్) ఎంచుకుంటే: కనెక్ట్ అవ్వగానే డ్రైవింగ్ మోడ్ ఆన్, ప్రతి రైడ్ కి.మీ, టైమ్ తనంతట తానే రాసుకుంటుంది; "
                + "దిగగానే ఆఫ్, బండి పెట్టిన చోటు గుర్తుపెట్టుకుంటుంది.");
        button("🏍️ బైక్ బ్లూటూత్ ఎంచుకోండి", v -> chooseCar(false));
        carInfo = Ui.text(this, btLine("🏍️ బైక్", prefs.carBluetooth()), 14, Ui.MUTED);
        box.addView(carInfo);
        button("🚗 కారు బ్లూటూత్ ఎంచుకోండి", v -> chooseCar(true));
        car2Info = Ui.text(this, btLine("🚗 కారు", prefs.carOnlyBluetooth()), 14, Ui.MUTED);
        box.addView(car2Info);
        note("బైక్: రైడ్ కి.మీ, ఛార్జింగ్, బ్రేక్ గుర్తు. కారు: కారు మోడ్, నిద్ర అలర్ట్ (కింద 🚗 కార్డ్). ఇంతకుముందు ఒక్కటే చోటు ఉండేది: "
                + "అక్కడ కారుని ఎంచుకుని ఉంటే, ఇప్పుడు కారుని 🚗 లో ఎంచుకుని, బైక్‌ని 🏍️ లో మళ్ళీ ఎంచుకోండి.");
        bikeRange = numberField("బైక్ పూర్తి ఛార్జ్‌కి నిజంగా వచ్చే దూరం (కి.మీ)", String.valueOf(Bike.fullRangeKm(prefs)));
        bikeKwh = numberField("బైక్ బ్యాటరీ (kWh) · Aera 5000+ = 5", trimZero(Bike.batteryKwh(prefs)));
        powerRate = numberField("ఇంట్లో కరెంట్ ఒక యూనిట్ ధర (₹)", trimZero(Bike.unitRate(prefs)));
        button("రైడ్ కి.మీ సరిగ్గా రావాలంటే: లొకేషన్ \"Allow all the time\"", v -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
            else if (Build.VERSION.SDK_INT < 29) Toast.makeText(this, "✓ లొకేషన్ అనుమతి ఉంది (ఈ Android లో అదే \"అన్ని వేళలా\")", Toast.LENGTH_SHORT).show();
            else requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 10);
        });
        shakeWake = toggle("ఫోన్ రెండుసార్లు ఊపితే Jarvis రావాలి", prefs.shakeWake());
        faceDown = toggle("ఫోన్ బోర్లా పెడితే సైలెంట్ (ఎత్తితే మళ్లీ సౌండ్)", prefs.faceDownSilent());
        nightSummary = toggle("రోజూ రాత్రి 9:30 కి ఈరోజు, రేపటి సారాంశం చెప్పు", prefs.nightSummary());
        weeklyReport = toggle("ప్రతి ఆదివారం రాత్రి 8కి వారపు రిపోర్ట్ (ఖర్చు, అడుగులు, ఫోన్ టైమ్, బైక్)", prefs.weeklyReport());
        button("స్క్రీన్ టైమ్ కోసం \"Usage access\" ఇవ్వండి", v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {}
        });
        homeArrivalSwitch(); // (it was in the watch card)
        boxSection();
        carSleepSection();
        jarvisMapsSection();

        section("డైరీ, పండుగలు, ధరలు, ఫోన్ వెతుకు");
        diaryAsk = toggle("📔 రాత్రి " + prefs.diaryHour() + " గంటలకి \"ఈరోజు ఎలా గడిచింది?\" అని అడిగి డైరీ రాయి", prefs.diaryAsk());
        holidayRemind = toggle("🎉 పండుగలు, ప్రభుత్వ సెలవులు ముందు రోజు సాయంత్రం, ఆ రోజు ఉదయం చెప్పు (డ్యూటీ ఉందో లేదో కూడా)", prefs.holidayRemind());
        priceCity = field("💰 బంగారం, వెండి, పెట్రోల్ ధరలకి మీ సిటీ", prefs.priceCity(), false);
        dailyPrices = field("💰 రోజూ ఉదయం 10 కి ఈ ధరలు చెప్పు (ఉదా: బంగారం, వెండి, మిర్చి · ఖాళీ = వద్దు · రోజుకి ఒక చిన్న AI వెతుకులాట)", prefs.dailyPrices(), false);
        findPhone = toggle("📱 వేరే ఫోన్ నుంచి కింది కోడ్ SMS / WhatsApp లో పంపితే ఫోన్ గట్టిగా మోగాలి (సైలెంట్‌లో ఉన్నా)", prefs.findPhone());
        findCode = field("📱 ఫోన్ వెతుకు కోడ్ (ఇంట్లోవాళ్లకి చెప్పండి; కనీసం 6 అక్షరాలు/అంకెలు)", prefs.findCode(), false);
        button("🔔 ఇప్పుడు 5 సెకన్లు మోగించి చూడు", v -> FindPhone.start(this, 5000));

        if (!prefs.homeMode()) houseSection(); // 🥛 the milk account (his phone keeps it; the tablet only takes her words)
        if (!prefs.homeMode()) carSection(); // 🚗 the car's diesel, mileage and service (his phone only)

        section("రోజువారీ హెచ్చరికలు");
        stormAlert = toggle("⛈️ గంటలోపు పిడుగులు / భారీ వర్షం వచ్చేలా ఉంటే చెప్పు", prefs.stormAlert());
        chargeRemind = toggle("🔋 రాత్రి 9 కి బైక్ ఛార్జ్ తక్కువైతే (రేపు డ్యూటీ ఉంటే, లేదా 25% లోపు) \"ఛార్జ్ పెట్టండి\" అని చెప్పు", prefs.chargeRemind());
        weekPlan = toggle("🗓️ ప్రతి ఆదివారం సాయంత్రం వచ్చే వారం ప్లాన్ చెప్పు", prefs.weekPlan());
        dailyTip = toggle("📚 రోజూ ఒక Jarvis చిట్కా (తెలియని ఫీచర్) చెప్పు", prefs.dailyTip());
        habitHour = numberField("🎯 అలవాట్లు చేశారా అని రాత్రి ఇన్ని గంటలకి అడుగు (0 = వద్దు)", String.valueOf(Math.max(0, prefs.habitHour())));
        heatAndBattery(); // (they were in the watch card)
        companionAndForecast(); // V14 / V15: a caring word after a duty / long ride; the evening "ఇలాగే కొనసాగితే"

        section("అలారం, వ్యాయామం, అత్యవసర సమాచారం");
        alarmSongInfo = Ui.text(this, "🎵 అలారం పాట: " + (prefs.alarmSong().isEmpty() ? "అలారం టోన్ (పాట ఎంచుకోలేదు)" : prefs.alarmSongName()), 14.5f, Ui.MUTED);
        alarmSongInfo.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(alarmSongInfo);
        button("🎵 అలారం పాట ఎంచుకో (ఫోన్‌లోని పాట)", v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 43);
            } catch (Exception ex) {
                Toast.makeText(this, "ఫైల్స్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
            }
        });
        button("🔔 పాట వద్దు, అలారం టోన్ చాలు", v -> {
            prefs.sp.edit().remove("alarm_song").remove("alarm_song_name").apply();
            alarmSongInfo.setText("🎵 అలారం పాట: అలారం టోన్");
        });
        note("అలారం పెట్టడానికి Jarvis కి చెప్పండి: \"రోజూ ఉదయం 6 కి పాటతో లేపు\", \"డ్యూటీ రోజుల్లో 7 కి లేపు\". లేచి ఆపాక వాతావరణం, ఈరోజు డ్యూటీ చెప్తుంది.");
        stepGoal = numberField("👣 రోజూ అడుగుల లక్ష్యం (0 = వద్దు; సాయంత్రం బాగా తక్కువైతే చెప్తుంది)", String.valueOf(prefs.stepGoal()));
        exerciseHour = numberField("🧘 సెలవు రోజుల్లో ఉదయం ఇన్ని గంటలకి వ్యాయామం గుర్తుచేయి (0 = వద్దు)", String.valueOf(Math.max(0, prefs.exerciseHour())));
        restMode = toggle("😴 48 గంటల డ్యూటీ అయ్యాక: ఫోన్ ఎక్కువ వాడితే \"పడుకోండి\" అని చెప్పు; పడుకున్నప్పుడు నిశ్శబ్దంగా ఉండి, మెసేజ్‌లు లేచాక చెప్పు", prefs.restMode());
        callNote = toggle("📞 ఒక నిమిషం దాటిన కాల్ అయ్యాక \"ఏమైనా గుర్తుపెట్టుకోవాలా?\" అని అడుగు", prefs.callNote());
        moviesWeekly = toggle("🎬 ప్రతి శుక్రవారం సాయంత్రం కొత్త తెలుగు సినిమాలు, OTT రిలీజ్‌లు చెప్పు", prefs.moviesWeekly());
        dailyFact = toggle("💡 రోజూ ఉదయం ఒక కొత్త విషయం, ఒక ఇంగ్లీష్ పదం చెప్పు", prefs.dailyFact());
        factHour = numberField("💡 ఎన్ని గంటలకి (6-21)", String.valueOf(prefs.factHour()));
        bikeNumber = field("🏍️ బండి నంబర్ (చలాన్ చెక్ కోసం; ఫోన్‌లోనే ఉంటుంది)", prefs.bikeNumber(), false);
        medIdOn = toggle("🆘 లాక్ స్క్రీన్ మీద అత్యవసర సమాచారం చూపించు (ప్రమాదం జరిగితే సహాయం చేసేవాళ్లకి)", prefs.medIdOn());
        medBlood = field("🩸 బ్లడ్ గ్రూప్ (ఉదా: O+)", prefs.medBlood(), false);
        medAllergy = field("⚠️ మందుల / ఇతర అలర్జీలు (లేకపోతే ఖాళీ)", prefs.medAllergy(), false);
        medNotes = field("💊 ముఖ్యమైన ఆరోగ్య విషయాలు, వాడే మందులు (ఉదా: BP మాత్రలు; లేకపోతే ఖాళీ)", prefs.medNotes(), false);
        medContact = field("📞 ఎమర్జెన్సీ కాంటాక్ట్ (పేరు, నంబర్)", prefs.medContact(), false);
        note("ఇవి మీ ఫోన్‌లోనే ఉంటాయి. లాక్ స్క్రీన్ మీద కనిపించాలంటే ఫోన్ Settings → Notifications → Lock screen లో \"Show content\" ఆన్ ఉండాలి.");

        section("ఆరోగ్యం, ఇతరాలు");
        coughAsk = toggle("🤧 దగ్గినా, తుమ్మినా (చిన్నగా అయినా) వెంటనే \"" + prefs.name() + ", ఏమైంది?\" అని అడుగు (\"Hey Jarvis\" వినే మైక్‌తోనే, ఫోన్‌లోనే; ఏదీ రికార్డ్ చేయదు)", prefs.coughAsk());
        coughGap = prefs.coughGapMinutes();
        coughGapText = Ui.text(this, "", 15.5f, Ui.CYAN);
        coughGapText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        coughGapText.setText("⏱️ ఒకసారి అడిగాక మళ్లీ అడగడానికి: " + Prefs.gapText(coughGap) + "  (మార్చడానికి నొక్కండి)");
        final int[] gaps = {15, 30, 60, 120, 180, 360, 24 * 60};
        coughGapText.setOnClickListener(v -> {
            String[] names = new String[gaps.length];
            int checked = 2;
            for (int i = 0; i < gaps.length; i++) { names[i] = Prefs.gapText(gaps[i]); if (gaps[i] == coughGap) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("దగ్గు / తుమ్ము గురించి మళ్లీ ఎప్పుడు అడగాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        coughGap = gaps[w];
                        coughGapText.setText("⏱️ ఒకసారి అడిగాక మళ్లీ అడగడానికి: " + Prefs.gapText(coughGap) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(coughGapText);
        if (!CoughDetector.status.isEmpty()) note(CoughDetector.status);
        if (!CoughDetector.lastHeard.isEmpty()) note("చివరగా విన్న శబ్దం: " + CoughDetector.lastHeard);
        String week = CoughLog.weekLine(this);
        if (!week.isEmpty()) note(week);
        button("🎙️ దగ్గు / శబ్దం టెస్ట్ (30 సెకన్లు): దగ్గి చూడండి, Jarvis ఏం విన్నాడో చూపిస్తుంది", v -> soundTest());
        button("📄 డాక్టర్ కోసం దగ్గు రిపోర్ట్ (PDF)", v -> {
            Toast.makeText(this, "రిపోర్ట్ తయారవుతోంది…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Coder.Made m = CoughLog.report(getApplicationContext());
                    runOnUiThread(() -> { if (m.uri != null) Cards.view(this, m); else Toast.makeText(this, "సేవ్ అయింది: " + m.where, Toast.LENGTH_LONG).show(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "రిపోర్ట్ తయారు కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "jarvis-cough-report").start();
        });
        Switch remedies = toggle("🍯 దగ్గు ఉన్న రోజుల్లో ఇంటి చిట్కాలు గుర్తుచేయి (గోరువెచ్చని నీళ్లు, పుక్కిలించడం, ఆవిరి; డ్యూటీ రోజు నోటిఫికేషన్ మాత్రమే)", CoughLog.remediesOn(this));
        remedies.setOnCheckedChangeListener((sw, isOn) -> CoughLog.setRemedies(this, isOn));
        Switch nightListen = toggle("🌙 రాత్రి ఛార్జింగ్‌లో ఉన్నప్పుడు దగ్గు, గురక విను (10 pm - 7 am; ఉదయం చిన్న రిపోర్ట్)", Sounds.nightListen(this));
        nightListen.setOnCheckedChangeListener((sw, isOn) -> { Sounds.setNightListen(this, isOn); Sounds.armNightEdge(this); WakeService.recheck(this); });
        Switch bodyAsks = toggle("👃 ముక్కు ఎగబీల్చడం, గొంతు సవరణ, ఆయాసం, ఎక్కిళ్లు, త్రేన్పులు వరుసగా వస్తే అడుగు / చిట్కా చెప్పు", BodySounds.asksOn(this));
        bodyAsks.setOnCheckedChangeListener((sw, isOn) -> BodySounds.setAsks(this, isOn));
        Switch clapCall = toggle("👏 రెండు చప్పట్లు కొడితే Jarvis పలకాలి", BodySounds.clapOn(this));
        clapCall.setOnCheckedChangeListener((sw, isOn) -> BodySounds.setClap(this, isOn));
        Switch whistleCall = toggle("😗 చిన్న ఈల వేస్తే Jarvis పలకాలి", BodySounds.whistleOn(this));
        whistleCall.setOnCheckedChangeListener((sw, isOn) -> BodySounds.setWhistle(this, isOn));
        Switch whisper = toggle("🤫 గుసగుసగా \"Jarvis\" అంటే మెల్లగా జవాబివ్వు" + (Whisper.lastPct >= 0 ? " (చివరి పిలుపు గుసగుస: " + Whisper.lastPct + "%)" : ""), Whisper.enabled(this));
        whisper.setOnCheckedChangeListener((sw, isOn) -> Whisper.setEnabled(this, isOn));
        Switch rain = toggle("🌧️ వర్షం మొదలైనా, ఉరుములు వినిపించినా చెప్పు (బట్టలు, బైక్ కవర్, ఛార్జింగ్)", Sounds.rainOn(this));
        rain.setOnCheckedChangeListener((sw, isOn) -> Sounds.setRain(this, isOn));
        TextView door = Ui.text(this, "", 15.5f, Ui.CYAN);
        door.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        door.setText("🚪 తలుపు కొట్టినా, బెల్ మోగినా చెప్పు: " + Sounds.doorModeText(Sounds.doorMode(this)) + "  (మార్చడానికి నొక్కండి)");
        door.setOnClickListener(v -> {
            final String[] modes = {"music", "always", "off"};
            String[] names = new String[modes.length];
            int checked = 0;
            for (int i = 0; i < modes.length; i++) { names[i] = Sounds.doorModeText(modes[i]); if (modes[i].equals(Sounds.doorMode(this))) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("తలుపు / కాలింగ్ బెల్ ఎప్పుడు చెప్పాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        Sounds.setDoorMode(this, modes[w]);
                        door.setText("🚪 తలుపు కొట్టినా, బెల్ మోగినా చెప్పు: " + Sounds.doorModeText(modes[w]) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(door);
        button("🔔 నా కాలింగ్ బెల్ నేర్పించు" + (Sounds.taughtCount(this, "bell") > 0 ? " (నేర్చుకున్నవి: " + Sounds.taughtCount(this, "bell") + ")" : ""), v -> {
            Sounds.teach(this, "bell");
            Toast.makeText(this, "ఇప్పుడు 30 సెకన్లలో బెల్ మోగించండి (ఫోన్ దగ్గరగా)", Toast.LENGTH_LONG).show();
        });
        button("🍲 నా కుక్కర్ విజిల్ నేర్పించు" + (Sounds.taughtCount(this, "cooker") > 0 ? " (నేర్చుకున్నవి: " + Sounds.taughtCount(this, "cooker") + ")" : ""), v -> {
            Sounds.teach(this, "cooker");
            Toast.makeText(this, "కుక్కర్ విజిల్ వచ్చే ముందు నొక్కండి: తర్వాతి 30 సెకన్లలో వచ్చే శబ్దం నేర్చుకుంటాను", Toast.LENGTH_LONG).show();
        });
        note("కుక్కర్ విజిల్స్ లెక్కపెట్టాలంటే \"Jarvis, 3 విజిల్స్ లెక్కపెట్టు\" అనండి. లెక్కపెడుతున్నంత సేపు స్క్రీన్ ఆఫ్ అయినా మైక్ వింటుంది; "
                + "ప్రతి విజిల్ చెప్తాను, చివరిదానికి \"స్టవ్ ఆపండి\" అంటాను. తలుపు అలర్ట్ పాట ఆపి చెప్తుంది. ఏదీ రికార్డ్ చేయదు, ఫోన్‌లోనే.");
        int taught = CoughDetector.taughtCount(this);
        note("తప్పుగా అడిగితే (దగ్గితే తుమ్ము అంటే) \"అది దగ్గు, తుమ్ము కాదు\" అని చెప్పండి: మీ శబ్దం గుర్తుంచుకుంటాను, "
                + "ఇకపై తుమ్ము అనడానికి ఇంకా స్పష్టంగా ఉండాలని నేర్చుకుంటాను."
                + (taught > 0 ? " ఇప్పటివరకు నేర్పినవి: " + taught + "." : "")
                + (CoughDetector.sneezeBar(this) > 1.41 ? " (తుమ్ము అనడానికి: దగ్గు కంటే " + String.format(Locale.ENGLISH, "%.1f", CoughDetector.sneezeBar(this)) + " రెట్లు)" : ""));
        safetySounds();
        sfx = toggle("Iron Man సౌండ్ ఎఫెక్ట్ (పిలవగానే చిన్న శబ్దం)", prefs.sfx());
        button("అడుగుల లెక్కకి అనుమతి (Physical activity)", v -> {
            if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "✓ ఈ Android లో అడుగుల లెక్కకి అనుమతి అక్కర్లేదు", Toast.LENGTH_SHORT).show(); return; }
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 8);
        });
        note("హోమ్ స్క్రీన్ విడ్జెట్: హోమ్ స్క్రీన్ మీద ఖాళీ చోట నొక్కి పట్టుకుని → Widgets → Jarvis.");
        note("బ్లూటూత్ ఇయర్‌ఫోన్: బటన్ నొక్కి పట్టుకుంటే Jarvis ప్యానెల్ వస్తుంది (మొదటిసారి ఏ యాప్ అని అడిగితే Jarvis ఎంచుకోండి).");

        updatesHeader = section("అప్డేట్లు");
        note("ఇప్పుడున్న వెర్షన్: 1.0." + Updater.currentBuild(this)
                + ". కొత్త వెర్షన్ వస్తే ఫోన్‌కి నోటిఫికేషన్, యాప్‌లో సందేశం వస్తాయి. Jarvis తనంతట తాను ఏదీ డౌన్‌లోడ్ చేయదు, ఇన్‌స్టాల్ చేయదు: మీరు కింది బటన్ నొక్కితేనే డౌన్‌లోడ్ అయి ఇన్‌స్టాల్ అవుతుంది (మొదటిసారి మాత్రమే Android ఒకసారి అడుగుతుంది).");
        updateInfo = Ui.text(this, "", 15, Ui.CYAN);
        updateInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(updateInfo);
        updateBtn = button("", v -> startUpdate());
        showUpdateState();
        new Thread(() -> { Updater.backgroundCheck(getApplicationContext()); runOnUiThread(this::showUpdateState); }, "jarvis-update-look").start();

        section("బ్యాకప్ & రీస్టోర్ (Google Drive)");
        note("Jarvis డేటా అంతా మీ Google Drive లో ఒక ఫైల్‌గా, మీ పాస్‌వర్డ్‌తో లాక్ అయి ఉంటుంది: సెట్టింగ్స్, API keys, మోడల్స్, "
                + "జ్ఞాపకాలు, సంభాషణలు, రిమైండర్లు, మిషన్లు, నోట్స్, డైరీ, ఖర్చులు, అప్పులు, బైక్ రైడ్స్, డ్యూటీ, ఆరోగ్యం, మీ గొంతు గుర్తింపు, "
                + "పరిచయం చేసిన ముఖాలు, సేవ్ చేసిన పేజీలు, రికార్డింగ్స్, Jarvis చేసిన సైట్లు, యాప్‌లు. రోజూ తనంతట తానే అప్డేట్ అవుతుంది. "
                + "కొత్త ఫోన్‌లో Jarvis వేసి ఇక్కడ \"తిరిగి తెచ్చు\" నొక్కితే అన్నీ వస్తాయి. పాస్‌వర్డ్ మర్చిపోతే ఆ ఫైల్ ఎవరూ తెరవలేరు: రాసి పెట్టుకోండి.");
        backupInfo = Ui.text(this, Backup.status(this), 15, Ui.CYAN);
        backupInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(backupInfo);
        backupSetBtn = button(Backup.configured(this) ? "🔐 పాస్‌వర్డ్ / Drive ఫైల్ మార్చు" : "🔐 బ్యాకప్ సెట్ చేయి", v -> startBackupSetup());
        button("⬆️ ఇప్పుడే బ్యాకప్ చేయి", v -> backupNow());
        button("♻️ బ్యాకప్ నుంచి తిరిగి తెచ్చు", v -> pickRestore());

        section("అనుమతులు, డేటా");
        button("అన్ని అనుమతులు ఇవ్వండి", v -> requestPermissions(MainActivity.corePermissions(), 5));
        button("సంభాషణ చెరిపేయి (జ్ఞాపకాలు, మిషన్లు అలాగే ఉంటాయి)", v -> {
            Store.get(this).clearChat();
            Toast.makeText(this, "సంభాషణ చెరిపేశాను", Toast.LENGTH_SHORT).show();
        });

        box = page;
        cur = null;
        TextView auto = Ui.text(this, "✓ మార్చిన వెంటనే తనంతట తానే సేవ్ అవుతుంది: సేవ్ బటన్ అక్కర్లేదు.", 13, Ui.MUTED);
        auto.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(this, 22);
        page.addView(auto, alp);
        for (int i = 0; i < page.getChildCount(); i++) { // switches, sliders, choices in their card's colour
            View card = page.getChildAt(i);
            if (card.getTag() instanceof Integer) tint(card, (Integer) card.getTag());
        }
        buildHome();
        watchTyping(page);
        lastFp = fingerprint();
        showHome();
        updateFromIntent(getIntent());
    }

    // ================================================================ groups, search, folding, saving by itself

    static String groupOf(String title) {
        String t = title;
        if (t.contains("Jarvis చెక్")) return "check";
        if (t.endsWith("(టాబ్లెట్)")) return "tab"; // (the tablet's cards, whatever their words: "మెసేజ్", "ఆరోగ్యం"…)
        if (t.contains("వాచ్")) return "watch"; // (the watch's six cards: "ఆరోగ్యం: వాచ్…", "రోడ్డు, భద్రత (వాచ్)"…)
        if (t.contains("మీరు") || t.contains("థీమ్") || t.contains("Jarvis ముఖం") || t.contains("ఫ్లోటింగ్")) return "me";
        if (t.contains("మెదడు") || t.contains("API ఖర్చు") || t.contains("కోడింగ్")) return "brain";
        if (t.contains("వాయిస్") || t.contains("సహజ గొంతు") || t.contains("Live") || t.contains("వేక్ వర్డ్") || t.contains("పవర్ బటన్")
                || t.startsWith("వేగం")) return "voice";
        if (t.contains("కాల్స్") || t.contains("మెసేజ్") || t.contains("WhatsApp") || t.contains("స్క్రీన్ చూడటం") || t.contains("మోసం")) return "msg";
        if (t.contains("టాబ్లెట్") || t.contains("ఇంటి Jarvis")) return "tab";
        if (t.contains("బైక్") || t.contains("కారు") || t.contains("స్మార్ట్ హోమ్") || t.contains("కాపలా")) return "bike";
        if (t.contains("అత్యవసరం (SOS)") || t.contains("టికెట్") || t.contains("డాక్యుమెంట్")) return "safe";
        if (t.contains("బ్యాకప్") || t.contains("అప్డేట్") || t.contains("అనుమతులు")) return "data";
        return "daily"; // డైరీ, హెచ్చరికలు, అలారం, ఆరోగ్యం, తనంతట తానే
    }

    private static String groupName(String id) {
        if ("check".equals(id)) return "🩺 Jarvis చెక్";
        for (String[] g : GROUPS) if (g[0].equals(id)) return g[1] + " " + g[2];
        return "";
    }

    /** The first screen: search, the one-line check, the 8 groups; and the bar shown inside a group. */
    private void buildHome() {
        int at = 1; // under the title
        search = new EditText(this);
        search.setHint("🔍  వెతుకు: అమ్మ, మాత్ర, గొంతు, బ్యాకప్, బైక్…");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.FAINT);
        search.setTextSize(15.5f);
        search.setBackground(fieldBg());
        int p = Ui.dp(this, 12);
        search.setPadding(p, p, p, p);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { runSearch(e.toString()); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 14);
        page.addView(search, at++, slp);

        whatsNew = buildWhatsNew();
        if (whatsNew != null) {
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
            wlp.topMargin = Ui.dp(this, 12);
            page.addView(whatsNew, at++, wlp);
        }

        checkLine = Ui.text(this, "", 14, 0xFFFFFFFF);
        checkLine.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        checkLine.setBackground(Ui.glass(this, 14));
        checkLine.setOnClickListener(v -> openGroup("check"));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 12);
        page.addView(checkLine, at++, clp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int[] colors = cardColors();
        LinearLayout row = null;
        for (int i = 0; i < GROUPS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = Ui.dp(this, 10);
                grid.addView(row, rlp);
            }
            final String id = GROUPS[i][0];
            int color = colors[(i * 3) % colors.length];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            android.graphics.drawable.GradientDrawable bg = Ui.grad(this, new int[]{Ui.alpha(color, 0x30), Ui.alpha(color, 0x0C)}, 18,
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
            bg.setStroke(Ui.dp(this, 1), Ui.alpha(color, 0x66));
            tile.setBackground(bg);
            tile.addView(Ui.text(this, GROUPS[i][1], 22, 0xFFFFFFFF));
            TextView name = Ui.text(this, GROUPS[i][2], 15.5f, color);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setPadding(0, Ui.dp(this, 4), 0, 0);
            tile.addView(name);
            TextView what = Ui.text(this, GROUPS[i][3], 12, Ui.MUTED);
            what.setMaxLines(1);
            what.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(what);
            TextView state = Ui.text(this, "", 12.5f, Ui.MUTED); // (how it stands now: refreshTiles)
            state.setMaxLines(2);
            state.setEllipsize(android.text.TextUtils.TruncateAt.END);
            state.setPadding(0, Ui.dp(this, 5), 0, 0);
            state.setVisibility(View.GONE);
            tile.addView(state);
            tileState.put(id, state);
            tile.setOnClickListener(v -> openGroup(id));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i % 2 == 0) tlp.rightMargin = Ui.dp(this, 5); else tlp.leftMargin = Ui.dp(this, 5);
            row.addView(tile, tlp);
        }
        if (GROUPS.length % 2 == 1 && row != null) { // (an odd last tile keeps its half width)
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, 1);
            sp.leftMargin = Ui.dp(this, 5);
            row.addView(new View(this), sp);
        }
        page.addView(grid, at++, new LinearLayout.LayoutParams(-1, -2));

        groupBar = new LinearLayout(this);
        groupBar.setGravity(Gravity.CENTER_VERTICAL);
        groupBar.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView back = Ui.text(this, "←", 24, 0xFFFFFFFF);
        back.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        groupBar.addView(back);
        groupTitle = Ui.text(this, "", 20, 0xFFFFFFFF);
        groupTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        groupBar.addView(groupTitle, new LinearLayout.LayoutParams(0, -2, 1));
        groupBar.setOnClickListener(v -> goHome());
        page.addView(groupBar, at++, new LinearLayout.LayoutParams(-1, -2));

        featHit = Ui.text(this, "", 14.5f, Ui.C_CYAN); // (in a search: the same words in All Features)
        featHit.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 2));
        featHit.setVisibility(View.GONE);
        page.addView(featHit, at, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView featHit;
    private LinearLayout whatsNew;

    // ---- "🆕 ఈ వెర్షన్‌లో కొత్తవి": once after this update, on top of Settings, until he closes it (✕)
    /** The newest list below (each update with news has the next number; he sees only what came after the one he closed). */
    private static final int WHATS_NEW_LEVEL = 2;
    /** {line, where "చూపించు" goes: "group:<id>" / "features:<folder>" / "search:<words>", the update's number}. */
    private static final String[][] WHATS_NEW = {
            {"🎯 Jarvis కెమెరాలో లైవ్ రింగ్స్: ప్రతి మనిషి, బండి, వస్తువు మీద; \"ఎంత మంది ఉన్నారు?\" వెంటనే లెక్క (నెట్ అక్కర్లేదు)", "features:camera", "2"},
            {"🔭 \"ఆ … ని జూమ్ చేయి\": వెతికి, లాక్ చేసి, జూమ్ చేసి, ఏం చేస్తున్నారో చెబుతుంది; 1× నుంచి 100× వరకు జూమ్", "features:camera", "2"},
            {"⌚ వాచ్‌కి సొంత టైల్: 6 చిన్న కార్డులు (కనెక్షన్, వినడం, అలర్ట్స్, రోజువారీ, ఆరోగ్యం, రోడ్డు-భద్రత) + 📳 వాచ్ టెస్ట్", "group:watch", "2"},
            {"🏠 అమ్మగారి టాబ్లెట్ సెట్టింగ్స్ ఇప్పుడు 5 చిన్న కార్డులు: టైమ్‌లు, మాత్రలు, మెసేజ్, ఆటలు, గొంతు", "group:tab", "1"},
            {"● టాబ్లెట్‌కి పంపని మార్పులు ఉంటే చూపిస్తాను; పంపకుండా బయటికి వెళ్తే అడుగుతాను", "group:tab", "1"},
            {"⚠️ టాబ్లెట్ సమస్యలు (లొకేషన్ ఆఫ్, బ్యాటరీ…) ఇక్కడే 🏠 టైల్ మీద; ప్రతి టైల్ మీద ఇప్పటి స్థితి", "home:", "1"},
            {"👵 అన్ని ఫీచర్స్‌లో కొత్త ఫోల్డర్: అమ్మగారి టాబ్లెట్ (మెసేజ్, ఆట, రిమైండర్, ఫోటోలు, స్థితి)", "features:amma", "1"},
            {"📌 ఫీచర్స్‌లో ఏ ఆప్షన్ మీదైనా వేలు నొక్కి పట్టుకుంటే పైన పెట్టుకోవచ్చు", "features:", "1"},
            {"🔍 వెతకడంలో మామూలు మాటలు: \"అమ్మ\", \"ఆట\", \"మాత్ర\"; ఒకే సెర్చ్ రెండు చోట్లా", "search:అమ్మ", "1"}};

    /** The last list he closed (✕): 1 for the first one (closed before numbers were kept), 0 none. */
    private int whatsNewSeen() {
        android.content.SharedPreferences s = getSharedPreferences("jarvis_settings_ui", MODE_PRIVATE);
        return s.getInt("whats_new_level", "settings-features-1".equals(s.getString("whats_new_seen", "")) ? 1 : 0);
    }

    private boolean whatsNewDue() {
        return !prefs.homeMode() && whatsNewSeen() < WHATS_NEW_LEVEL;
    }

    private LinearLayout buildWhatsNew() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        w.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        android.graphics.drawable.GradientDrawable bg = Ui.grad(this, new int[]{Ui.alpha(0xFFFBBF24, 0x30), Ui.alpha(0xFFFBBF24, 0x0C)}, 18,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setStroke(Ui.dp(this, 1), Ui.alpha(0xFFFBBF24, 0x88));
        w.setBackground(bg);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, "🆕 ఈ వెర్షన్‌లో కొత్తవి (1.0." + Updater.currentBuild(this) + ")", 16, 0xFFFDE68A);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        x.setContentDescription("కొత్తవి మూసేయి");
        x.setOnClickListener(v -> {
            getSharedPreferences("jarvis_settings_ui", MODE_PRIVATE).edit().putInt("whats_new_level", WHATS_NEW_LEVEL).apply();
            w.setVisibility(View.GONE);
        });
        head.addView(x);
        w.addView(head);
        int seen = whatsNewSeen();
        for (String[] item : WHATS_NEW) {
            if (Integer.parseInt(item[2]) <= seen) continue; // (already seen in an earlier update)
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            row.addView(Ui.text(this, item[0], 14, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            TextView go = Ui.text(this, "చూపించు ›", 13.5f, 0xFFFDE68A);
            go.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), 0, Ui.dp(this, 4));
            final String where = item[1];
            go.setOnClickListener(v -> showWhatsNew(where));
            row.addView(go);
            w.addView(row);
        }
        TextView note = Ui.text(this, "✕ నొక్కితే ఈ కార్డ్ పోతుంది (ఇవన్నీ అలాగే ఉంటాయి).", 12.5f, Ui.MUTED);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        w.addView(note);
        w.setVisibility(whatsNewDue() ? View.VISIBLE : View.GONE);
        return w;
    }

    private void showWhatsNew(String where) {
        if (where.startsWith("group:")) openGroup(where.substring(6));
        else if (where.startsWith("features:")) {
            Intent i = new Intent(this, FeaturesActivity.class).addFlags(FeaturesActivity.ONE_SCREEN);
            if (where.length() > 9) i.putExtra(FeaturesActivity.EXTRA_CATEGORY, where.substring(9));
            startActivity(i);
        } else if (where.startsWith("search:")) search.setText(where.substring(7));
        else if (pageScroll != null) pageScroll.post(() -> pageScroll.smoothScrollTo(0, grid.getTop()));
    }

    private void goHome() {
        if (search != null && search.getText().length() > 0) search.setText(""); // (runSearch shows the first screen)
        else showHome();
    }

    // ---- each group's tile says how it stands now (a problem in red, something to look at in amber, all well in green)
    private final java.util.Map<String, TextView> tileState = new java.util.HashMap<>();

    private void refreshTiles() {
        for (java.util.Map.Entry<String, TextView> e : tileState.entrySet()) {
            Object[] s;
            try { s = tileLine(e.getKey()); } catch (Exception ex) { s = new Object[]{"", -1}; }
            String text = (String) s[0];
            int kind = (Integer) s[1];
            TextView t = e.getValue();
            t.setText(text);
            t.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            t.setTextColor(kind == 2 ? 0xFFFFB4B4 : kind == 1 ? 0xFFFDE68A : kind == 0 ? 0xFFB9F6CA : Ui.MUTED);
        }
    }

    private Card cardTitled(String words) {
        for (Card c : cardList) if (c.title.contains(words)) return c;
        return null;
    }

    /** {line, kind} for a group's tile: kind 2 = a problem, 1 = look at this, 0 = all well, -1 = just how it is. */
    private Object[] tileLine(String id) {
        switch (id) {
            case "me": {
                Card c = cardTitled("థీమ్");
                return new Object[]{"పేరు: " + name.getText().toString().trim() + (c == null ? "" : " · " + summaryOf(c)), -1};
            }
            case "brain": {
                int ch = provider.getCheckedRadioButtonId();
                EditText key = ch == 3 ? geminiKey : ch == 2 ? anthropicKey : openAiKey;
                Card c = cardTitled("Jarvis మెదడు");
                String line = c == null ? "" : summaryOf(c);
                if (key != null && key.getText().toString().trim().isEmpty()) return new Object[]{"✗ " + line + " · key లేదు", 2};
                return new Object[]{line, -1};
            }
            case "voice": {
                if (prefs.wakeReady() && !WakeService.running) return new Object[]{"✗ \"Jarvis\" వేక్ వర్డ్ ఆగి ఉంది", 2};
                return new Object[]{"వేక్ వర్డ్: " + (wake.isChecked() ? "ఆన్" : "ఆఫ్") + " · సహజ గొంతు: " + (natural.isChecked() ? "ఆన్" : "ఆఫ్"), -1};
            }
            case "watch": {
                org.json.JSONObject i = WatchHub.info;
                long wall = WatchHub.sp(this).getLong("seen_wall", 0), now = System.currentTimeMillis();
                if (i == null) return wall == 0 ? new Object[]{"✗ వాచ్ ఇంకా ఫోన్‌తో కలవలేదు: వాచ్‌లో Jarvis ఒకసారి తెరవండి", 2}
                        : new Object[]{"వాచ్: చివరిసారి " + TabletWatch.ago(wall, now) + " (ఇప్పుడు అడుగుతున్నాను…)", -1};
                if (!i.optBoolean("mic", true)) return new Object[]{"✗ వాచ్ మైక్ అనుమతి లేదు: వాచ్‌లో Jarvis తెరిచి Allow", 2};
                int bat = i.optInt("bat", -1);
                String line = "✓ " + i.optString("model", "Galaxy Watch") + (bat >= 0 ? " · 🔋 " + bat + "%" : "") + (wall > 0 ? " · " + TabletWatch.ago(wall, now) : "");
                return new Object[]{line, bat >= 0 && bat <= 15 ? 1 : 0};
            }
            case "msg":
                if (!NotifyListener.enabled(this)) return new Object[]{"✗ నోటిఫికేషన్ యాక్సెస్ లేదు: మెసేజ్‌లు అందవు", 2};
                if (!prefs.readMessages()) return new Object[]{"మెసేజ్‌లు చెప్పడం: ఆఫ్", 1};
                return new Object[]{"✓ కొత్త మెసేజ్‌లు చెబుతాను", 0};
            case "bike": {
                int n = 0;
                for (String l : smartUrls.getText().toString().split("\n")) if (l.indexOf('=') > 0 && l.contains("http")) n++;
                return new Object[]{"స్మార్ట్ హోమ్: " + n + " బటన్లు" + (Guard.running(this) ? " · 🛡️ కాపలా ఆన్" : ""), -1};
            }
            case "tab": {
                if (prefs.homeMode()) { // (this is the tablet: its own problems)
                    java.util.List<String> bad = HomeCare.problems(this);
                    return bad.isEmpty() ? new Object[]{"✓ ఈ టాబ్లెట్‌లో సమస్యలు లేవు", 0}
                            : new Object[]{"⚠️ " + android.text.TextUtils.join(" · ", bad), 2};
                }
                Object[] w = TabletWatch.tile(this);
                java.util.List<String> ch = tabChangedCards();
                if (ch.isEmpty()) return w;
                int kind = Math.max(1, (Integer) w[1]);
                return new Object[]{"● ఇంకా పంపలేదు (" + tabShorts(ch) + ")" + ((Integer) w[1] == 2 ? "\n" + w[0] : ""), kind};
            }
            case "daily": {
                int[] onOff = new int[2];
                for (Card c : cardList) if ("daily".equals(c.group)) countSwitches(c.body, onOff);
                return new Object[]{onOff[0] + " ఆన్ · " + onOff[1] + " ఆఫ్", -1};
            }
            case "safe": {
                int n = 0;
                for (String s : sosContacts.getText().toString().split("[,\n;]")) if (!s.trim().isEmpty()) n++; // (names or numbers)
                return n == 0 ? new Object[]{"✗ SOS కాంటాక్ట్స్ లేరు", 2} : new Object[]{"✓ SOS: " + n + " మంది", 0};
            }
            case "data": {
                String ver = " · 1.0." + Updater.currentBuild(this) + (Updater.newAvailable(this) ? " · ⬆ కొత్త వెర్షన్ ఉంది" : "");
                long ok = Backup.lastOk(this);
                if (!Backup.configured(this)) return new Object[]{"✗ బ్యాకప్ సెట్ కాలేదు" + ver, 2};
                if (System.currentTimeMillis() - ok > 3 * 86_400_000L)
                    return new Object[]{"చివరి బ్యాకప్ పాతది" + (ok == 0 ? "" : ": " + Backup.when(ok)) + ver, 1};
                return new Object[]{"✓ బ్యాకప్: " + Backup.when(ok) + ver, Updater.newAvailable(this) ? 1 : 0};
            }
        }
        return new Object[]{"", -1};
    }

    private void showHome() {
        autosave();
        mode = "home";
        search.setVisibility(View.VISIBLE);
        checkLine.setVisibility(View.VISIBLE);
        grid.setVisibility(View.VISIBLE);
        groupBar.setVisibility(View.GONE);
        if (featHit != null) featHit.setVisibility(View.GONE);
        if (whatsNew != null) whatsNew.setVisibility(whatsNewDue() ? View.VISIBLE : View.GONE);
        for (Card c : cardList) c.view.setVisibility(View.GONE);
        refreshTiles();
        hideKeyboard();
        if (pageScroll != null) pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    /** One group's cards (folded, each with its state in one line). */
    private void openGroup(String id) {
        autosave();
        mode = "group";
        search.setVisibility(View.GONE);
        checkLine.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        if (featHit != null) featHit.setVisibility(View.GONE);
        if (whatsNew != null) whatsNew.setVisibility(View.GONE);
        groupBar.setVisibility(View.VISIBLE);
        groupTitle.setText(groupName(id));
        int shown = 0;
        Card only = null;
        for (Card c : cardList) {
            boolean in = c.group.equals(id);
            c.view.setVisibility(in ? View.VISIBLE : View.GONE);
            if (in) { shown++; only = c; summarize(c); }
        }
        if (shown == 1 && !only.open) toggle(only); // one card (Jarvis చెక్): open at once
        hideKeyboard();
        if (pageScroll != null) pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    /** Typing in the search box: the cards that have those words (in their title, settings or explanations). */
    private void runSearch(String q) {
        String w = q.trim().toLowerCase(Locale.ROOT);
        if (w.length() < 2) { if (!"home".equals(mode)) showHome(); return; }
        mode = "search";
        checkLine.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        groupBar.setVisibility(View.VISIBLE);
        if (whatsNew != null) whatsNew.setVisibility(View.GONE);
        java.util.List<Card> hits = new java.util.ArrayList<>();
        for (Card c : cardList) {
            // every word he typed, anywhere in the card (or an everyday word for it: "అమ్మ" → the tablet's cards, "మాత్ర" → tablets…)
            boolean hit = !"check".equals(c.group) && SearchWords.matches(words(c), w);
            c.view.setVisibility(hit ? View.VISIBLE : View.GONE);
            if (hit) { hits.add(c); summarize(c); }
        }
        groupTitle.setText(hits.isEmpty() ? "🔍 \"" + q.trim() + "\": సెట్టింగ్స్‌లో ఏమీ దొరకలేదు" : "🔍 \"" + q.trim() + "\": " + hits.size() + " కార్డులు");
        if (hits.size() <= 2) for (Card c : hits) if (!c.open) toggle(c);
        // the same words in All Features (one search for both)
        int f = prefs.homeMode() ? 0 : FeaturesActivity.countMatches(q.trim());
        if (featHit != null) {
            featHit.setText(f == 0 ? "📂 అన్ని ఫీచర్స్‌లో \"" + q.trim() + "\" కి ఏమీ లేదు" : "📂 అన్ని ఫీచర్స్‌లో " + f + " దొరికాయి: చూడటానికి నొక్కండి ›");
            featHit.setTextColor(f == 0 ? Ui.MUTED : Ui.C_CYAN);
            featHit.setOnClickListener(f == 0 ? null : v -> startActivity(new Intent(this, FeaturesActivity.class).putExtra(FeaturesActivity.EXTRA_SEARCH, q.trim()).addFlags(FeaturesActivity.ONE_SCREEN)));
            featHit.setVisibility(prefs.homeMode() ? View.GONE : View.VISIBLE);
        }
    }

    /** All the words of a card, for the search. */
    private String words(Card c) {
        StringBuilder b = new StringBuilder(c.title).append(' ');
        collectWords(c.body, b);
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static void collectWords(View v, StringBuilder b) {
        if (v instanceof EditText) {
            CharSequence h = ((EditText) v).getHint();
            if (h != null) b.append(h).append(' ');
        } else if (v instanceof TextView && v.getTag() instanceof String[]) { // (a long note shown short: all its words)
            b.append(((String[]) v.getTag())[1]).append(' ');
        } else if (v instanceof TextView) {
            b.append(((TextView) v).getText()).append(' ');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectWords(g.getChildAt(i), b);
        }
    }

    private void toggle(Card c) {
        c.open = !c.open;
        c.body.setVisibility(c.open ? View.VISIBLE : View.GONE);
        c.chevron.setText(c.open ? "⌃" : "⌄");
        if (!c.open) { autosave(); hideKeyboard(); }
        summarize(c);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            View f = getCurrentFocus();
            if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    /** The card's state in one line, under its title (hidden while the card is open). */
    private void summarize(Card c) {
        String s = "";
        try { s = summaryOf(c); } catch (Exception ignored) {}
        c.summary.setText(s);
        c.summary.setVisibility(s.isEmpty() || c.open ? View.GONE : View.VISIBLE);
    }

    private String summaryOf(Card c) {
        String t = c.title;
        String tabLine = tabSummary(t);
        if (tabLine != null) return tabLine;
        if (t.equals(W_LINK)) return WatchHub.status(this).split("\n")[0]; // (is the watch there)
        if (t.contains("మీరు")) return "పేరు: " + name.getText().toString().trim();
        if (t.contains("థీమ్")) {
            String id = Ui.theme(this);
            for (String[] th : Ui.THEMES) if (th[0].equals(id)) return th[1];
            return "";
        }
        if (t.contains("Jarvis ముఖం")) return (FaceSight.faceOn(this) ? "ముఖం ఆన్" : "ముఖం ఆఫ్") + (FaceSight.camOn(this) ? " · కెమెరా చూపు ఆన్" : "");
        if (t.contains("ఫ్లోటింగ్")) return FloatBubble.on(this) ? "బటన్ ఆన్" : "బటన్ ఆఫ్";
        if (t.contains("మెదడు")) {
            int ch = provider.getCheckedRadioButtonId();
            return ch == 3 ? "Gemini · " + geminiModel.getText().toString().trim()
                    : ch == 2 ? "Claude · " + anthropicModel.getText().toString().trim()
                    : "OpenAI · " + openAiModel.getText().toString().trim();
        }
        if (t.contains("సహజ గొంతు")) {
            if (!natural.isChecked()) return "ఆఫ్";
            int vp = voiceProv.getCheckedRadioButtonId();
            return vp == 102 ? "ఆన్ · Google Gemini · " + geminiVoiceChoice
                    : vp == 103 ? "ఆన్ · Sarvam · " + CloudVoice.sarvamVoiceFor(sarvamTtsModel.getText().toString().trim(), sarvamVoiceChoice)
                    : "ఆన్ · గొంతు: " + NaturalVoice.VOICES[Math.max(0, voicePick.getSelectedItemPosition())];
        }
        if (t.contains("Live")) return liveMode.isChecked() ? "\"Hey Jarvis\" తో Live: ఆన్" : "\"Hey Jarvis\" తో Live: ఆఫ్";
        if (t.contains("వేక్ వర్డ్")) {
            int ww = wakeWhen.getCheckedRadioButtonId();
            return !wake.isChecked() ? "ఆఫ్" : "ఆన్ · " + (ww == 22 ? "ఛార్జింగ్‌లో మాత్రమే" : ww == 21 ? "స్క్రీన్ ఆన్‌లో మాత్రమే" : "ఎప్పుడూ");
        }
        if (t.equals("వాయిస్"))
            return String.format(Locale.ENGLISH, "వేగం %.2fx", 0.5f + rate.getProgress() / 100f) + " · "
                    + (lang.getCheckedRadioButtonId() == 12 ? "English" : "తెలుగు") + " · వినే సమయం " + (listenWindow.getProgress() + 3) + " సె";
        if (t.contains("బ్యాకప్")) return Backup.configured(this)
                ? (Backup.lastOk(this) > 0 ? "చివరి బ్యాకప్: " + Backup.when(Backup.lastOk(this)) : "సెట్ అయింది") : "✗ సెట్ అయి లేదు";
        if (t.contains("అప్డేట్")) return "వెర్షన్ 1.0." + Updater.currentBuild(this);
        int[] onOff = new int[2];
        countSwitches(c.body, onOff);
        if (onOff[0] + onOff[1] == 0) return "";
        return onOff[0] + " ఆన్ · " + onOff[1] + " ఆఫ్";
    }

    private static void countSwitches(View v, int[] onOff) {
        if (v instanceof Switch) { onOff[((Switch) v).isChecked() ? 0 : 1]++; return; }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countSwitches(g.getChildAt(i), onOff);
        }
    }

    private void summarizeShown() {
        for (Card c : cardList) if (c.view.getVisibility() == View.VISIBLE) summarize(c);
    }

    /** Every box he types in saves a moment after he stops typing. */
    private void watchTyping(View v) {
        if (v instanceof EditText && v != search) {
            ((EditText) v).addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    ui.removeCallbacks(autosaveSoon);
                    ui.postDelayed(autosaveSoon, 1200);
                }
            });
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) watchTyping(g.getChildAt(i));
        }
    }

    /** Any tap on the page (a switch, a choice, a slider): save a moment later, if something really changed. */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            ui.removeCallbacks(autosaveSoon);
            ui.postDelayed(autosaveSoon, 700);
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Everything the page's controls say now, to see whether anything changed since the last save. */
    private String fingerprint() {
        StringBuilder b = new StringBuilder();
        fp(page, b);
        b.append(coughGap).append('|').append(briefHour).append(':').append(briefMinute);
        return b.toString();
    }

    private void fp(View v, StringBuilder b) {
        if (v == search) return;
        if (v instanceof android.widget.CompoundButton) b.append(((android.widget.CompoundButton) v).isChecked() ? '1' : '0');
        else if (v instanceof EditText) b.append(((EditText) v).getText()).append('\u0001');
        else if (v instanceof SeekBar) b.append(((SeekBar) v).getProgress()).append(',');
        else if (v instanceof Spinner) b.append(((Spinner) v).getSelectedItemPosition()).append(',');
        if (v instanceof android.view.ViewGroup && !(v instanceof Spinner)) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fp(g.getChildAt(i), b);
        }
    }

    /** Saves what changed (right away, quietly); what has to follow a change runs when he leaves the screen. */
    private void autosave() {
        ui.removeCallbacks(autosaveSoon);
        if (name == null || page == null || isDestroyed()) return;
        tabChanged(); // (the tablet's boxes: kept as unsent until 📤, never lost)
        String f = fingerprint();
        if (f.equals(lastFp)) return;
        lastFp = f;
        writePrefs();
        changed = true;
        BackupJob.soon(this); // into the Drive backup in a little while
        summarizeShown();
        if (subtitle != null) {
            subtitle.setText("✓ సేవ్ అయింది");
            subtitle.setTextColor(Ui.C_GREEN);
            ui.postDelayed(() -> { subtitle.setText("Jarvis ని మీకు నచ్చినట్టు మార్చుకోండి"); subtitle.setTextColor(Ui.MUTED); }, 1500);
        }
        if (!askedWakePerms && wake.isChecked() && Build.VERSION.SDK_INT >= 33
                && (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            askedWakePerms = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 6);
        }
    }

    @Override public void onBackPressed() {
        if (gated) { finish(); return; }
        if (!"home".equals(mode)) { goHome(); return; }
        leave();
    }

    /** What the phone has for Jarvis without internet (its offline speech pack and Telugu voice), with the right download buttons. */
    private void checkOffline() {
        if (offlineInfo == null) return;
        showOffline();
        OfflineKit.check(this, () -> { if (!isFinishing()) showOffline(); });
    }

    private void showOffline() {
        String te = OfflineKit.hearing(this, OfflineKit.TE), en = OfflineKit.hearing(this, OfflineKit.EN), v = OfflineKit.voice(this);
        offlineInfo.setText(OfflineKit.line(this));
        offlineHearBtn.setVisibility(OfflineKit.AVAILABLE.equals(te) ? View.VISIBLE : View.GONE);
        offlineEnBtn.setVisibility(prefs.listenLang().startsWith("en") && OfflineKit.AVAILABLE.equals(en) ? View.VISIBLE : View.GONE);
        offlineVoiceBtn.setVisibility(OfflineKit.INSTALLED.equals(v) ? View.GONE : View.VISIBLE);
    }

    /** The Settings screen showing now (a download started on an earlier one reports here). */
    private static SettingsActivity showing;


    private void offlineDownload(String lang) {
        if (!Net.online(this)) { Toast.makeText(this, "డౌన్‌లోడ్‌కి నెట్ కావాలి.", Toast.LENGTH_LONG).show(); return; }
        if (OfflineKit.downloadHearing(this, lang)) {
            Toast.makeText(this, "డౌన్‌లోడ్ మొదలైంది (ఫోన్ అడిగితే ఒప్పుకోండి). కాసేపటి తర్వాత \"మళ్లీ చూడు\" నొక్కండి.", Toast.LENGTH_LONG).show();
            showOffline();
        } else {
            Toast.makeText(this, "ఈ ఫోన్ డౌన్‌లోడ్ అడగనివ్వలేదు.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (gated) return;
        if (TabletLock.active(this)) TabletLock.opened(); // (he is inside: a rebuild of this screen doesn't ask again)
        Ui.loadTheme(this);
        if (builtTheme != Ui.themeVersion) { recreate(); return; } // the theme changed while this screen was open
        if (wallShow != null) wallShow.run(); // (the car's photo was marked / the wallpaper picked meanwhile)
        showLock();
        if (bubbleMark != null) bubbleMark.run();
        showing = this;
        checkOffline(); // (back from a download screen too)
        // back from "Install unknown apps" after pressing update: carry on installing
        int pendingBuild = Updater.installWhenAllowed;
        if (pendingBuild > 0 && getPackageManager().canRequestPackageInstalls()) {
            Updater.install(this, pendingBuild, (text, done) -> { if (!isFinishing()) updateInfo.setText(text); });
        } else {
            showUpdateState();
        }
        StringBuilder s = new StringBuilder();
        s.append(Settings.canDrawOverlays(this) ? "✓ Display over other apps: ఇచ్చారు\n" : "✗ Display over other apps: ఇవ్వలేదు (లేకపోతే పిలిచినప్పుడు నోటిఫికేషన్ మాత్రమే వస్తుంది)\n");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        s.append(exempt ? "✓ బ్యాటరీ సేవర్ మినహాయింపు: ఉంది" : "✗ బ్యాటరీ సేవర్ మినహాయింపు: లేదు (ఫోన్ వేక్ వర్డ్‌ని ఆపేయవచ్చు)");
        if (WakeService.lastError != null) s.append("\nచివరి సమస్య: ").append(WakeService.lastError);
        wakeInfo.setText(s.toString());
        notifyInfo.setText(NotifyListener.enabled(this) ? "✓ నోటిఫికేషన్ యాక్సెస్: ఇచ్చారు" : "✗ నోటిఫికేషన్ యాక్సెస్: ఇవ్వలేదు");
        showCallChecks(); // 📞 (back from a permission screen: ticks afresh)
        showHouse(); // 🥛 (a day said meanwhile)
        showCar(); // 🚗 (a fill said meanwhile)
        screenInfo.setText(JarvisAccessibility.enabled() ? "✓ స్క్రీన్ యాక్సెస్: ఆన్" : "✗ స్క్రీన్ యాక్సెస్: ఆఫ్");
        screenInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        if (WakeService.wordStatus != null) s.append("\n\"Jarvis\" పదం: ").append(WakeService.wordStatus);
        else if (VoskModel.ready(this)) s.append("\n✓ \"Jarvis\" పదం: సిద్ధం");
        wakeInfo.setText(s.toString());
        notifyInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        voiceInfo.setText(VoiceIO.naturalError == null ? "" : "చివరిసారి సహజ గొంతు పనిచేయలేదు: " + VoiceIO.naturalError);
        showCheck();
        if (cardList.size() > 0) summarizeShown();
        if (!tileState.isEmpty() && "home".equals(mode)) refreshTiles();
        if (!prefs.homeMode()) TabletWatch.refreshIfOld(this, () -> { if (!isDestroyed()) { refreshTiles(); showTabStatus(); } }); // (the 🏠 tile)
        tabReloadIfSentElsewhere();
    }

    /** A talk flag left on with no Jarvis screen, panel or Live open. */
    private static boolean stuckTalking() {
        return MainActivity.inConversation && !MainActivity.visible && !SheetActivity.open && !MainActivity.liveOn;
    }

    /** The "Jarvis చెక్" card: everything that stops Jarvis from telling him about messages or from speaking. */
    private void showCheck() {
        if (checkInfo == null) return;
        StringBuilder s = new StringBuilder();
        String crash = AppCrash.line(this); // (Jarvis itself closed with an error last time: shown first)
        if (crash != null) s.append(crash).append("\n");
        if (crashInfo != null) {
            String trace = crash == null ? "" : AppCrash.details(this);
            crashInfo.setText(trace);
            crashInfo.setVisibility(crash == null ? View.GONE : View.VISIBLE);
            crashCopy.setVisibility(crash == null ? View.GONE : View.VISIBLE);
            crashClear.setVisibility(crash == null ? View.GONE : View.VISIBLE);
        }
        boolean access = NotifyListener.enabled(this);
        boolean carBox = prefs.carMode();
        if (carBox) { // 🚗 the car's box: its own lines (no messages here: those are his phone's)
            String wait = CarLink.waitingLine(this);
            s.append("🚗 కారు box: ").append(CarBox.speedWords()).append(" · ").append(CarBox.linkLine(this)).append(wait.isEmpty() ? "" : " · " + wait).append("\n");
            String alert = CarCare.statusWords(this);
            if (!alert.isEmpty()) s.append("• ").append(alert).append("\n");
            s.append(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ? "✓ లొకేషన్: ఉంది (స్పీడ్, దారి)\n"
                    : "✗ లొకేషన్ అనుమతి లేదు: స్పీడ్, నిద్ర అలర్ట్, దారి పనిచేయవు\n");
            s.append(Settings.canDrawOverlays(this) ? "✓ Display over other apps: ఉంది (కారు స్టార్ట్‌తో Jarvis వస్తాడు)\n"
                    : "✗ Display over other apps లేదు: కారు స్టార్ట్ అయినప్పుడు Jarvis తనంతట తానే రాడు\n");
            s.append(!access ? "• నోటిఫికేషన్ యాక్సెస్ లేదు: Maps మలుపులు, పాట పేరు Jarvis కి తెలియవు\n" : "✓ నోటిఫికేషన్లు: Maps, పాటలు Jarvis కి తెలుస్తాయి\n");
        } else {
        s.append(!access ? "✗ నోటిఫికేషన్ యాక్సెస్ లేదు: మెసేజ్‌లు Jarvis కి అందవు\n"
                : NotifyListener.connected ? "✓ నోటిఫికేషన్లు Jarvis కి అందుతున్నాయి\n"
                : "✗ యాక్సెస్ ఉంది, కానీ Android నోటిఫికేషన్లు పంపడం లేదు\n");
        s.append(Settings.canDrawOverlays(this) ? "✓ Display over other apps: ఉంది\n"
                : "✗ Display over other apps లేదు: panel తెరవలేను, గొంతుతో మాత్రమే చెప్తాను\n");
        s.append(prefs.readMessages() ? "✓ కొత్త మెసేజ్ వస్తే చెప్పు: ఆన్\n" : "✗ కొత్త మెసేజ్ వస్తే చెప్పు: ఆఫ్\n");
        s.append(prefs.night() ? "✗ నైట్ మోడ్ ఆన్: ఏ మెసేజ్ చదవను\n" : "✓ నైట్ మోడ్: ఆఫ్\n");
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        boolean dnd = nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        s.append(dnd ? "✗ Do Not Disturb ఆన్: మెసేజ్‌లు చదవను (ఫోన్ quick settings లో ఆఫ్ చేయండి)\n" : "✓ Do Not Disturb: ఆఫ్\n");
        }
        PowerManager pm = getSystemService(PowerManager.class);
        s.append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()) ? "✓ బ్యాటరీ సేవర్ మినహాయింపు: ఉంది\n"
                : "✗ బ్యాటరీ సేవర్ మినహాయింపు లేదు: ఫోన్ Jarvis ని ఆపేయవచ్చు\n");
        if (prefs.wakeReady()) s.append(WakeService.running ? "✓ \"Jarvis\" వేక్ వర్డ్: నడుస్తోంది\n" : "✗ \"Jarvis\" వేక్ వర్డ్: ఆగి ఉంది\n");
        if (stuckTalking()) s.append("✗ Jarvis 'మాట్లాడుతున్నాను' అనే స్థితిలో ఇరుక్కుంది\n");
        CloudVoice cvNow = CloudVoice.of(prefs);
        if (prefs.naturalVoice()) s.append("• మాట్లాడే గొంతు: ").append(cvNow != null ? cvNow.label() : "OpenAI " + prefs.ttsModel() + " · " + prefs.naturalVoiceName()).append("\n");
        if (prefs.naturalVoice() && (cvNow == null ? prefs.openAiKey().trim().isEmpty() : !cvNow.ready()))
            s.append("• సహజ గొంతుకి ").append(cvNow == null ? "OpenAI key లేదు" : cvNow.noKey()).append(": ఫోన్ గొంతుతో మాట్లాడతాను\n");
        else if (prefs.naturalVoice() && VoiceIO.naturalError != null)
            s.append("• సహజ గొంతు చివరిసారి పనిచేయలేదు (ఫోన్ గొంతుతో మాట్లాడాను): ").append(VoiceIO.naturalError).append("\n");
        if (Brain.emptyAt > 0 && System.currentTimeMillis() - Brain.emptyAt < 7 * 86_400_000L)
            s.append("• AI చివరిసారి ఖాళీ జవాబు ఇచ్చింది (").append(Backup.when(Brain.emptyAt)).append("): \"మళ్ళీ అడగండి\" అని చెప్పాను\n");
        if (VoiceIO.voiceStuckAt > 0 && System.currentTimeMillis() - VoiceIO.voiceStuckAt < 7 * 86_400_000L)
            s.append("• ఫోన్ గొంతు \"అయిపోయింది\" అని చెప్పలేదు (").append(Backup.when(VoiceIO.voiceStuckAt)).append("): జవాబు నేనే ముగించాను\n");
        if (prefs.naturalVoice() && VoiceIO.naturalCut != null)
            s.append("• సహజ గొంతు చివరి జవాబు మధ్యలో ఆగింది (మిగతాది టెక్స్ట్‌లో ఉంది): ").append(VoiceIO.naturalCut).append("\n");
        long backedUp = Backup.lastOk(this);
        if (carBox && !Backup.configured(this)) s.append("• బ్యాకప్: box లో వద్దు (మీ డేటా ఫోన్‌లోనే)\n");
        else if (!Backup.configured(this)) s.append("✗ బ్యాకప్ సెట్ చేయలేదు: ఫోన్ పోతే Jarvis డేటా పోతుంది (కింద \"బ్యాకప్\" చూడండి)\n");
        else if (System.currentTimeMillis() - backedUp > 3 * 86_400_000L)
            s.append("✗ చివరి బ్యాకప్ పాతది").append(backedUp == 0 ? "" : " (" + Backup.when(backedUp) + ")").append(": \"ఇప్పుడే బ్యాకప్ చేయి\" నొక్కండి\n");
        else s.append("✓ బ్యాకప్: ").append(Backup.when(backedUp)).append("\n");
        String heard = VoiceIO.lastListen;
        if (!heard.isEmpty()) s.append("• చివరి వినడం: ").append(heard)
                .append("\n   (▶ మైక్ మొదలు · 🎙 తెరిచింది · ■ మీరు మాట్లాడటం ఆపారు · ✗ ఫోన్ ఆపింది)\n");
        if (!MicQuiet.info.isEmpty()) s.append("• మైక్ బీప్ ఆపడానికి క్షణం పాటు మ్యూట్ చేసేవి: ").append(MicQuiet.info).append("\n");
        s.append("• మాటలు వినే పద్ధతి: ").append("gemini".equals(prefs.earsMode()) ? "Gemini (Jarvis సొంత మైక్)"
                : "sarvam".equals(prefs.earsMode()) ? "Sarvam " + prefs.sarvamEarsModel() + " (Jarvis సొంత మైక్)" : "google".equals(prefs.earsMode())
                ? "Google వాయిస్ టైపింగ్ (బీప్‌లతో" + (prefs.micOnce() ? ", మైక్ ఒక్కసారే)" : ", మైక్ ఎక్కువ సేపు)") : "OpenAI " + prefs.earsModel() + " (Jarvis సొంత మైక్)").append("\n");
        if (prefs.liveMode()) s.append("• Live: ").append(LiveTalk.label(prefs)).append(prefs.liveGemini() ? " (" + prefs.geminiLiveModel() + ", గొంతు " + prefs.geminiLiveVoice() + ")" : "")
                .append(prefs.liveKeyReady() ? "" : " ✗ key లేదు").append("\n");
        s.append("• నెట్ లేనప్పుడు: ").append(prefs.offlineAuto() ? "ఆటోమేటిక్‌గా offline (" + OfflineKit.line(this) + ")" : "offline కి మారదు (ఆఫ్)")
                .append(Net.online(this) ? "" : " · ఇప్పుడు నెట్ లేదు").append(Offline.anyWaiting(this) ? " · నెట్ కోసం ఆగిన ప్రశ్నలు ఉన్నాయి" : "").append("\n");
        if (prefs.liveMode() && prefs.liveGemini()) {
            s.append("• Gemini Live లో ఆలోచన: ").append(prefs.liveBrainThinks() ? "Jarvis మెదడు (" + prefs.model() + ")" : "Gemini Live తనే");
            if (GeminiLive.brainAnswers + GeminiLive.selfAnswers > 0)
                s.append(" · మెదడు జవాబులు ").append(GeminiLive.brainAnswers).append(", మెదడుని అడగకుండా Live తనే ఇచ్చినవి ").append(GeminiLive.selfAnswers);
            s.append("\n");
        }
        if (!GeminiLive.lastInfo.isEmpty()) s.append("• చివరి Gemini Live: ").append(GeminiLive.lastInfo).append("\n");
        if (!GeminiLive.bargeInfo.isEmpty()) s.append("• Gemini Live లో మధ్యలో మాట్లాడితే: ").append(GeminiLive.bargeInfo).append("\n");
        if (!GeminiLive.echoRecording.isEmpty()) s.append("• చివరి echo పరీక్ష రికార్డింగ్: ").append(GeminiLive.echoRecording).append("\n");
        if ("openai".equals(prefs.earsMode()) && !prefs.earsStream())
            s.append("• మీరు మాట్లాడుతుండగానే పంపడం: ఆగింది (OpenAI ఒప్పుకోలేదు); మీరు ఆపాక మొత్తం పంపుతుంది\n");
        String turnTimes = VoiceIO.timingLines(this); // (the answers' time in four parts, kept over restarts)
        if (!turnTimes.isEmpty()) s.append(turnTimes);
        else if (!Ears.lastTimes.isEmpty()) s.append("• చివరి వినడం సమయం: ").append(Ears.lastTimes).append("\n");
        if (!prefs.homeMode()) s.append(speedLine()).append("\n"); // ⚡ which speed switches are on
        if (!prefs.homeMode()) s.append(VoiceIO.guardLine(this)); // ♻️ how often the voice had to be asked again
        s.append(Brain.taskLines(this));     // (the camera / screen / document jobs' time)
        if (!Ears.lastError.isEmpty()) s.append("• మాటలు వినే AI చివరి తప్పు: ").append(Ears.lastError).append("\n");
        if (prefs.coughAsk()) {
            s.append("• దగ్గు / తుమ్ము: ").append(CoughDetector.status.isEmpty() ? (WakeService.running ? "ఇంకా ఏ శబ్దం రాలేదు" : "\"Jarvis\" మైక్ ఆగి ఉంది, వినలేను") : CoughDetector.status);
            if (!CoughDetector.lastHeard.isEmpty()) s.append(" · చివరగా: ").append(CoughDetector.lastHeard);
            s.append("\n");
        }
        String carNow = CarCare.statusWords(this);
        if (!carNow.isEmpty()) s.append("• ").append(carNow).append("\n");
        if (SafetySounds.fallOn(this) && !"none".equals(SafetySounds.sosMode(this)) && prefs.sosContacts().trim().isEmpty())
            s.append("✗ అరుపు / పడిపోతే SOS: SOS కాంటాక్ట్స్ లేరు (సెట్టింగ్స్ → అత్యవసరం (SOS) లో పెట్టండి)\n");
        if (NameCall.on(this) && !NameCall.status.isEmpty()) s.append("• ఇయర్‌ఫోన్‌లో పేరు పిలుపు: ").append(NameCall.status).append("\n");
        if (WatchHub.info != null) s.append("• ⌚ వాచ్: ").append(WatchHub.status(this).replace("\n", " · ")).append("\n");
        if (Guard.running(this) && HomeGuard.soundsOn(this))
            s.append(HomeGuard.listening(this) && WakeService.running ? "• కాపలా శబ్దాలు: వింటోంది\n" : "✗ కాపలా శబ్దాలు: మైక్ వినడం లేదు (మైక్ అనుమతి / కాపలా మళ్లీ మొదలుపెట్టండి)\n");
        String last = NotifyListener.lastMessageNote;
        s.append("\nచివరి మెసేజ్: ").append(last == null || last.isEmpty() ? "Jarvis మొదలయ్యాక ఇంకా ఏ మెసేజ్ రాలేదు" : last);
        checkInfo.setText(s.toString().trim());
        if (checkLine != null) {
            int bad = 0;
            for (String line : s.toString().split("\n")) if (line.startsWith("✗")) bad++;
            if (crash != null) bad++; // (until he clears it)
            long ok = Backup.lastOk(this);
            checkLine.setText(bad == 0
                    ? "🩺 ✓ Jarvis చెక్: అంతా సరిగ్గా ఉంది" + (ok > 0 ? " · బ్యాకప్ " + Backup.when(ok) : "")
                    : "🩺 ✗ Jarvis చెక్: " + bad + " సమస్య" + (bad > 1 ? "లు" : "") + " · చూడటానికి నొక్కండి");
            checkLine.setTextColor(bad == 0 ? 0xFFB9F6CA : 0xFFFFB4B4);
        }
    }

    /** "సరిచేయి": fixes what Jarvis can fix itself, then opens the first permission only Anil can give. */
    private void fixCheck() {
        StringBuilder done = new StringBuilder();
        if (prefs.night()) { Life.endNight(this); done.append("నైట్ మోడ్ ఆఫ్ చేశాను. "); }
        if (!prefs.readMessages()) {
            prefs.set("read_messages", true);
            if (readMessages != null) readMessages.setChecked(true);
            done.append("మెసేజ్‌లు చెప్పడం ఆన్ చేశాను. ");
        }
        if (stuckTalking()) { MainActivity.talking(false); done.append("ఇరుక్కున్న స్థితి తీసేశాను. "); }
        NotifyListener.ensureBound(this);
        if (prefs.wakeReady() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            WakeService.start(this, false);
        Intent open = null;
        PowerManager pm = getSystemService(PowerManager.class);
        if (!NotifyListener.enabled(this)) open = NotifyListener.settingsIntent();
        else if (!Settings.canDrawOverlays(this)) open = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        else if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
            open = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
        if (open != null) {
            done.append("ఈ అనుమతి మీరే ఇవ్వాలి: తెరుస్తున్నాను.");
            try { startActivity(open); } catch (Exception ignored) {}
        }
        Toast.makeText(this, done.length() == 0 ? "అంతా సరిచేశాను ✓" : done.toString().trim(), Toast.LENGTH_LONG).show();
        checkInfo.postDelayed(this::showCheck, 1500);
    }

    /** Writes every setting on the page (quietly; autosave() calls it when something changed). */
    private void writePrefs() {
        SharedPreferences.Editor e = prefs.sp.edit();
        String n = name.getText().toString().trim();
        e.putString("name", n.isEmpty() ? "Anil" : n);
        int chosen = provider.getCheckedRadioButtonId();
        e.putString("provider", chosen == 3 ? Prefs.GEMINI : chosen == 2 ? Prefs.ANTHROPIC : Prefs.OPENAI);
        e.putString("gemini_key", geminiKey.getText().toString().trim());
        String gm = geminiModel.getText().toString().trim();
        e.putString("gemini_model", gm);
        e.remove("gemini_auto_model"); // older versions picked a Gemini model by themselves
        e.putString("openai_key", openAiKey.getText().toString().trim());
        e.putString("sarvam_key", sarvamKey.getText().toString().trim());
        if (mapsKey != null) e.putString("maps_key", mapsKey.getText().toString().trim());
        e.putString("openai_model", openAiModel.getText().toString().trim());
        e.putString("code_model", codeModel.getText().toString().trim());
        e.putString("github_token", githubToken.getText().toString().trim());
        e.putString("anthropic_key", anthropicKey.getText().toString().trim());
        e.putString("anthropic_model", anthropicModel.getText().toString().trim());
        e.putBoolean("web_search", web.isChecked());
        int cpr = checkProvider.getCheckedRadioButtonId();
        e.putString("check_provider", cpr == 42 ? Prefs.OPENAI : cpr == 43 ? Prefs.ANTHROPIC : cpr == 44 ? Prefs.GEMINI : "");
        e.putString("check_model", checkModel.getText().toString().trim());
        e.putBoolean("check_auto", checkAuto.isChecked());
        e.putBoolean("voice", voice.isChecked());
        e.putBoolean("follow_up", followUp.isChecked());
        e.putBoolean("natural_voice", natural.isChecked());
        e.putBoolean("emotions", emotions.isChecked());
        e.putBoolean("wake_jarvis", jarvisWord.isChecked());
        e.putBoolean("listen_on_open", listenOnOpen.isChecked());
        e.putBoolean("compact_panel", compactPanel.isChecked());
        e.putBoolean("cam_always_listen", camAlways.isChecked());
        int ww = wakeWhen.getCheckedRadioButtonId();
        e.putString("wake_when", ww == 22 ? "charging" : ww == 21 ? "screen_on" : "always");
        ui.postDelayed(() -> Sounds.armNightEdge(getApplicationContext()), 1500); // the night timer follows the listen mode
        e.putBoolean("announce_calls", announceCalls.isChecked());
        e.putBoolean("call_voice", callVoice.isChecked());
        e.putBoolean("read_messages", readMessages.isChecked());
        int gmc = groupMode.getCheckedRadioButtonId();
        e.putString("group_mode", gmc == 64 ? "all" : gmc == 66 ? "none" : "mine");
        int emc = earsMode.getCheckedRadioButtonId();
        int eoc = earsOther.getCheckedRadioButtonId();
        e.putString("ears_mode", emc == 84 ? (eoc == 86 ? "gemini" : eoc == 87 ? "google" : eoc == 89 ? "sarvam" : "openai") // (Gemini Live: the other places' choice)
                : emc == 82 ? "gemini" : emc == 83 ? "google" : emc == 88 ? "sarvam" : "openai");
        e.putString("sarvam_ears_model", sarvamEarsModel.getText().toString().trim());
        e.putString("live_provider", liveProvider.getCheckedRadioButtonId() == 91 ? Prefs.GEMINI : Prefs.OPENAI);
        e.putString("live_think", liveThink.getCheckedRadioButtonId() == 99 ? "live" : "brain");
        e.putBoolean("offline_auto", offlineAuto.isChecked());
        e.putString("gemini_live_model", geminiLiveModel.getText().toString().trim());
        e.putString("gemini_live_voice", liveVoiceChoice);
        String earsModelNow = earsModel.getText().toString().trim();
        if (!earsModelNow.equals(prefs.sp.getString("ears_model", "").trim())) e.remove("ears_stream"); // a new model: sending while he talks is tried again
        e.putString("ears_model", earsModelNow);
        int tmc = typingMode.getCheckedRadioButtonId();
        e.putString("typing_mode", tmc == 72 ? "name" : tmc == 73 ? "silent" : "read");
        e.putString("my_names", myNames.getText().toString().trim());
        e.putBoolean("bubble_show_all", bubbleAll.isChecked());
        e.putBoolean("read_news", readNews.isChecked());
        e.putBoolean("news_auto", newsAuto.isChecked());
        e.putBoolean("diary_ask", diaryAsk.isChecked());
        e.putBoolean("cough_ask", coughAsk.isChecked());
        e.putInt("cough_gap_min", coughGap);
        e.putBoolean("holiday_remind", holidayRemind.isChecked());
        if (!priceCity.getText().toString().trim().isEmpty()) e.putString("price_city", priceCity.getText().toString().trim());
        e.putString("daily_prices", dailyPrices.getText().toString().trim());
        e.putBoolean("find_phone", findPhone.isChecked());
        String code = findCode.getText().toString().trim().replaceAll("\\s+", " ");
        if (code.replaceAll("[\\s\\p{Punct}]+", "").length() >= 6) e.putString("find_code", code);
        e.putString("news_places", newsPlaces.getText().toString().trim());
        e.putBoolean("battery_warn", batteryWarn.isChecked());
        e.putBoolean("briefing", briefing.isChecked());
        e.putInt("briefing_hour", briefHour);
        e.putInt("briefing_minute", briefMinute);
        e.putBoolean("briefing_speak", briefingSpeak.isChecked());
        e.putString("natural_voice_name", NaturalVoice.VOICES[Math.max(0, voicePick.getSelectedItemPosition())]);
        e.putString("tts_model", ttsModel.getText().toString().trim());
        int vpc = voiceProv.getCheckedRadioButtonId();
        e.putString("voice_provider", vpc == 102 ? CloudVoice.GEMINI : vpc == 103 ? CloudVoice.SARVAM : CloudVoice.OPENAI);
        e.putString("gemini_tts_model", geminiTtsModel.getText().toString().trim());
        e.putString("sarvam_tts_model", sarvamTtsModel.getText().toString().trim());
        e.putBoolean("live", liveMode.isChecked());
        e.putBoolean("live_patient", livePatient.isChecked());
        e.putBoolean("scam_guard", scamGuard.isChecked());
        saveBalance(balGemini, Usage.GEMINI);
        saveBalance(balOpenAi, Usage.OPENAI);
        saveBalance(balAnthropic, Usage.ANTHROPIC);
        e.putBoolean("barge_in", bargeIn.isChecked());
        e.putBoolean("speed_g", speedG.isChecked());
        e.putBoolean("speed_k", speedK.isChecked());
        e.putBoolean("speed_f", speedF.isChecked());
        e.putBoolean("speed_e", speedE.isChecked());
        e.putBoolean("speed_a", speedA.isChecked());
        e.putBoolean("speed_5", speed5.isChecked());
        e.putBoolean("speed_b", speedB.isChecked());
        e.putBoolean("voice_guard", voiceGuard.isChecked());
        int lbc = liveBarge.getCheckedRadioButtonId();
        e.putString("live_barge", lbc == 96 ? "voice" : lbc == 97 ? "off" : "word");
        e.putInt("barge_sens", bargeSens.getProgress());
        e.putBoolean("barge_call_voice", bargeCallVoice.isChecked());
        e.putBoolean("live_aec", liveAec.isChecked());
        e.putBoolean("echo_record", echoRecord.isChecked());
        e.putString("realtime_model", realtimeModel.getText().toString().trim());
        e.putFloat("rate", 0.5f + rate.getProgress() / 100f);
        e.putString("lang", lang.getCheckedRadioButtonId() == 12 ? "en-IN" : "te-IN");
        e.putBoolean("wake", wake.isChecked());
        e.putBoolean("wake_paused", false);
        e.putBoolean("voice_lock", voiceLock.isChecked() && VoiceLock.print(this) != null);
        if (lockSlider.getProgress() != lockStart) e.putFloat("voice_lock_max", 0.30f + lockSlider.getProgress() / 100f);
        e.putBoolean("proactive", proactive.isChecked());
        e.putBoolean("sfx", sfx.isChecked());
        e.putBoolean("shake_wake", shakeWake.isChecked());
        e.putBoolean("facedown_silent", faceDown.isChecked());
        e.putBoolean("night_summary", nightSummary.isChecked());
        e.putBoolean("weekly_report", weeklyReport.isChecked());
        try { e.putInt("bike_range_km", Math.max(20, Math.min(500, Integer.parseInt(bikeRange.getText().toString().trim())))); } catch (Exception ignored) {}
        try { e.putFloat("bike_kwh", Math.max(0.5f, Math.min(50f, Float.parseFloat(bikeKwh.getText().toString().trim())))); } catch (Exception ignored) {}
        try { e.putFloat("power_rate", Math.max(0f, Math.min(100f, Float.parseFloat(powerRate.getText().toString().trim())))); } catch (Exception ignored) {}
        e.putInt("listen_window", listenWindow.getProgress() + 3).putBoolean("listen_window_set", true);
        e.putBoolean("mic_once", micOnce.isChecked());
        e.putString("sos_contacts", sosContacts.getText().toString().trim());
        e.putString("smart_urls", smartUrls.getText().toString().trim());
        e.putString("smart_app", smartApp.getText().toString().trim());
        e.putBoolean("alexa_speak", alexaSpeak.isChecked());
        e.putBoolean("wallet_pay", walletPay.isChecked());
        int max = prefs.walletPayMax(); // an empty or bad field keeps the saved limit (never raises it)
        try { max = Integer.parseInt(walletMax.getText().toString().trim()); } catch (Exception ignored) {}
        e.putInt("wallet_pay_max", Math.max(0, Math.min(10000, max)));
        e.putFloat("wake_threshold", 0.75f - sensitivity.getProgress() / 100f);
        e.putBoolean("call_note", callNote.isChecked());
        e.putBoolean("storm_alert", stormAlert.isChecked());
        e.putBoolean("charge_remind", chargeRemind.isChecked());
        e.putBoolean("week_plan", weekPlan.isChecked());
        e.putBoolean("daily_tip", dailyTip.isChecked());
        try {
            int hh = Integer.parseInt(habitHour.getText().toString().trim());
            if (hh >= 1 && hh <= 11) hh += 12; // "9" means 9 pm
            e.putInt("habit_hour", hh <= 0 ? -1 : Math.max(18, Math.min(23, hh)));
        } catch (Exception ignored) {}
        e.putBoolean("rest_mode", restMode.isChecked());
        e.putBoolean("movies_weekly", moviesWeekly.isChecked());
        e.putBoolean("daily_fact", dailyFact.isChecked());
        e.putBoolean("medid_on", medIdOn.isChecked());
        e.putString("medid_blood", medBlood.getText().toString().trim());
        e.putString("medid_allergy", medAllergy.getText().toString().trim());
        e.putString("medid_notes", medNotes.getText().toString().trim());
        e.putString("medid_contact", medContact.getText().toString().trim());
        e.putString("bike_number", bikeNumber.getText().toString().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
        try { e.putInt("step_goal", Math.max(0, Math.min(40000, Integer.parseInt(stepGoal.getText().toString().trim())))); } catch (Exception ignored) {}
        try { int h = Integer.parseInt(exerciseHour.getText().toString().trim()); e.putInt("exercise_hour", h <= 0 ? -1 : Math.max(5, Math.min(12, h))); } catch (Exception ignored) {}
        try { e.putInt("fact_hour", Math.max(6, Math.min(21, Integer.parseInt(factHour.getText().toString().trim())))); } catch (Exception ignored) {}
        e.apply();
    }

    private void showLiveVoice() {
        liveVoiceLabel.setText("Gemini Live గొంతు: " + GeminiLiveProto.voiceLabel(liveVoiceChoice));
    }

    /** The 30 voices (men first): tapping one plays a line in it; "ఇది పెట్టు" keeps it. */
    private void pickLiveVoice() {
        String[] labels = new String[GeminiLiveProto.VOICES.length];
        int at = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = GeminiLiveProto.voiceLabel(GeminiLiveProto.VOICES[i][0]);
            if (GeminiLiveProto.VOICES[i][0].equals(liveVoiceChoice)) at = i;
        }
        final String[] chosen = {liveVoiceChoice};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gemini Live గొంతు (నొక్కితే వినిపిస్తుంది)")
                .setSingleChoiceItems(labels, at, (d, which) -> {
                    chosen[0] = GeminiLiveProto.VOICES[which][0];
                    previewVoice(chosen[0]);
                })
                .setPositiveButton("ఇది పెట్టు", (d, w) -> {
                    VoicePreview.stop();
                    liveVoiceChoice = chosen[0];
                    prefs.sp.edit().putString("gemini_live_voice", liveVoiceChoice).apply(); // (a dialog tap doesn't reach the page's autosave)
                    showLiveVoice();
                    Toast.makeText(this, "తర్వాతి Live నుంచి ఈ గొంతు: " + liveVoiceChoice, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("వద్దు", (d, w) -> VoicePreview.stop())
                .setOnCancelListener(d -> VoicePreview.stop())
                .show();
    }

    /** A small heading inside a card ("▸ Google Gemini"). */
    private void subHead(String s) {
        TextView h = Ui.text(this, s, 16, Ui.TEXT);
        h.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
        box.addView(h);
    }

    private void showGeminiVoice() {
        geminiVoiceLabel.setText("🎙️ గొంతు: " + GeminiLiveProto.voiceLabel(geminiVoiceChoice) + "  (మార్చడానికి నొక్కండి)");
    }

    /** Google Gemini's 30 voices (the same as Gemini Live's); a tap lets him hear it with the model in the box. */
    private void pickGeminiVoice() {
        String[] labels = new String[GeminiLiveProto.VOICES.length];
        int at = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = GeminiLiveProto.voiceLabel(GeminiLiveProto.VOICES[i][0]);
            if (GeminiLiveProto.VOICES[i][0].equals(geminiVoiceChoice)) at = i;
        }
        final String[] chosen = {geminiVoiceChoice};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Google Gemini గొంతు (నొక్కితే వినిపిస్తుంది)")
                .setSingleChoiceItems(labels, at, (d, which) -> {
                    chosen[0] = GeminiLiveProto.VOICES[which][0];
                    testCloud(CloudVoice.GEMINI, chosen[0]);
                })
                .setPositiveButton("ఇది పెట్టు", (d, w) -> {
                    tester.stop();
                    geminiVoiceChoice = chosen[0];
                    prefs.sp.edit().putString("gemini_tts_voice", geminiVoiceChoice).apply(); // (a dialog tap doesn't reach the page's autosave)
                    showGeminiVoice();
                })
                .setNegativeButton("వద్దు", (d, w) -> tester.stop())
                .setOnCancelListener(d -> tester.stop())
                .show();
    }

    private void showSarvamVoice() {
        String model = sarvamTtsModel.getText().toString().trim();
        String v = CloudVoice.sarvamVoiceFor(model, sarvamVoiceChoice);
        String kind = "";
        for (String[] x : CloudVoice.sarvamVoices(model)) if (x[0].equals(v)) kind = " · " + x[1];
        sarvamVoiceLabel.setText("🎙️ గొంతు: " + v + kind + "  (మార్చడానికి నొక్కండి)");
    }

    /** The voices of the Sarvam model in the box; a tap lets him hear it. */
    private void pickSarvamVoice() {
        String model = sarvamTtsModel.getText().toString().trim();
        String[][] list = CloudVoice.sarvamVoices(model);
        String[] labels = new String[list.length];
        String now = CloudVoice.sarvamVoiceFor(model, sarvamVoiceChoice);
        int at = 0;
        for (int i = 0; i < list.length; i++) {
            labels[i] = list[i][0] + " · " + list[i][1];
            if (list[i][0].equals(now)) at = i;
        }
        final String[] chosen = {now};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sarvam గొంతు (" + (model.isEmpty() ? CloudVoice.SARVAM_DEFAULT_MODEL : model) + "; నొక్కితే వినిపిస్తుంది)")
                .setSingleChoiceItems(labels, at, (d, which) -> {
                    chosen[0] = list[which][0];
                    testCloud(CloudVoice.SARVAM, chosen[0]);
                })
                .setPositiveButton("ఇది పెట్టు", (d, w) -> {
                    tester.stop();
                    sarvamVoiceChoice = chosen[0];
                    prefs.sp.edit().putString("sarvam_voice", sarvamVoiceChoice).apply();
                    showSarvamVoice();
                })
                .setNegativeButton("వద్దు", (d, w) -> tester.stop())
                .setOnCancelListener(d -> tester.stop())
                .show();
    }

    private void testCloud(String which) {
        testCloud(which, CloudVoice.GEMINI.equals(which) ? geminiVoiceChoice : sarvamVoiceChoice);
    }

    /** "ఈ గొంతు వినిపించు" for Google Gemini / Sarvam: with the key, model and voice on this page now (saved or not). */
    private void testCloud(String which, String voice) {
        boolean gem = CloudVoice.GEMINI.equals(which);
        TextView info = gem ? geminiInfo : sarvamInfo;
        String m = (gem ? geminiTtsModel : sarvamTtsModel).getText().toString().trim();
        if (m.isEmpty()) m = gem ? CloudVoice.GEMINI_DEFAULT_MODEL : CloudVoice.SARVAM_DEFAULT_MODEL;
        CloudVoice cv = gem ? new CloudVoice(CloudVoice.GEMINI, geminiKey.getText().toString(), m, voice)
                : new CloudVoice(CloudVoice.SARVAM, sarvamKey.getText().toString(), m, CloudVoice.sarvamVoiceFor(m, voice));
        if (!cv.ready()) { info.setText(cv.noKey()); return; }
        String n = name.getText().toString().trim();
        info.setText("వినిపిస్తున్నాను… (" + cv.label() + ")");
        tester.cloud = cv;
        tester.speak("", cv.voice, "నమస్కారం " + (n.isEmpty() ? "Anil" : n) + ". నేను Jarvis. మీ సేవలో ఎప్పుడూ సిద్ధంగా ఉంటాను.", new NaturalVoice.Callback() {
            private String cut;
            @Override public void onStart() { info.setText("గొంతు: " + cv.label()); }
            @Override public void onDone() { info.setText("గొంతు: " + cv.label() + (cut == null ? " ✓" : " · మధ్యలో ఆగింది: " + cut)); }
            @Override public void onError(String message) { info.setText("పనిచేయలేదు: " + message); }
            @Override public void onCut(String message) { cut = message; }
        });
    }

    private void previewVoice(String voice) {
        Toast.makeText(this, voice + " గొంతు తయారవుతోంది…", Toast.LENGTH_SHORT).show();
        VoicePreview.play(prefs, voice, problem -> {
            if (problem != null && !isFinishing()) Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
        });
    }

    /** The Live switches changed: the voice choice shows "⚡ Gemini Live" exactly when Live is on with Gemini. */
    private void syncLiveChoice() {
        if (syncingVoice) return;
        syncingVoice = true;
        if (liveMode.isChecked() && liveProvider.getCheckedRadioButtonId() == 91) earsMode.check(84);
        else if (earsMode.getCheckedRadioButtonId() == 84) {
            int o = earsOther.getCheckedRadioButtonId();
            earsMode.check(o == 86 ? 82 : o == 87 ? 83 : o == 89 ? 88 : 81);
        }
        syncingVoice = false;
    }

    /** He leaves the screen after changing something: what has to follow a change (once, not on every tap). */
    private void afterChanges(boolean leaving) {
        MedicalId.update(this);
        Reminders.scheduleBriefing(this);
        if (!leaving) return;
        if (provider.getCheckedRadioButtonId() == 3 && geminiModel.getText().toString().trim().isEmpty())
            Toast.makeText(this, "Gemini మోడల్ ఇంకా ఎంచుకోలేదు: 'అన్ని మోడల్స్ చూపించు' నొక్కి ఒకటి ఎంచుకోండి", Toast.LENGTH_LONG).show();
        boolean geminiLive = liveProvider.getCheckedRadioButtonId() == 91;
        int vpw = voiceProv.getCheckedRadioButtonId();
        if (((liveMode.isChecked() && !geminiLive) || natural.isChecked() && vpw == 101) && openAiKey.getText().toString().trim().isEmpty())
            Toast.makeText(this, "సహజ గొంతు, OpenAI Live కి OpenAI key కావాలి", Toast.LENGTH_LONG).show();
        if (natural.isChecked() && vpw == 102 && geminiKey.getText().toString().trim().isEmpty())
            Toast.makeText(this, "Google Gemini గొంతుకి Gemini key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
        if (natural.isChecked() && vpw == 103 && sarvamKey.getText().toString().trim().isEmpty())
            Toast.makeText(this, "Sarvam గొంతుకి Sarvam key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
        if ("sarvam".equals(prefs.earsMode()) && sarvamKey.getText().toString().trim().isEmpty())
            Toast.makeText(this, "Sarvam Saaras తో వినడానికి Sarvam key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
        if (liveMode.isChecked() && geminiLive && geminiKey.getText().toString().trim().isEmpty())
            Toast.makeText(this, "Gemini Live కి Gemini key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
        // the wake word starts again with the new settings
        WakeService.stop(this);
        if ((prefs.wakeReady() || Sounds.holdMic(this)) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            WakeService.start(this, MainActivity.inConversation);
    }

    // ---------- little builders ----------

    private void showRate() {
        float r = 0.5f + rate.getProgress() / 100f;
        rateLabel.setText(String.format(Locale.ENGLISH, "మాట్లాడే వేగం: %.2fx", r));
        rateLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
    }

    private void showBriefingTime() {
        briefingTime.setText(String.format(Locale.ENGLISH, "బ్రీఫింగ్ సమయం: %02d:%02d  (మార్చడానికి నొక్కండి)", briefHour, briefMinute));
    }

    private void testVoice() {
        String key = openAiKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "ముందు OpenAI key పెట్టండి", Toast.LENGTH_SHORT).show();
            return;
        }
        String v = NaturalVoice.VOICES[Math.max(0, voicePick.getSelectedItemPosition())];
        String n = name.getText().toString().trim();
        voiceInfo.setText("వినిపిస్తున్నాను…");
        String tm = ttsModel.getText().toString().trim();
        tester.model = tm.isEmpty() ? NaturalVoice.DEFAULT_MODEL : tm;
        tester.cloud = null; // (OpenAI's own test)
        tester.speak(key, v, "నమస్కారం " + (n.isEmpty() ? "Anil" : n) + ". నేను Jarvis. మీ సేవలో ఎప్పుడూ సిద్ధంగా ఉంటాను.", new NaturalVoice.Callback() {
            @Override public void onStart() { voiceInfo.setText("గొంతు: " + v + " · " + tester.model); }
            @Override public void onDone() { voiceInfo.setText("గొంతు: " + v + " · " + tester.model + " ✓"); }
            @Override public void onError(String message) { voiceInfo.setText("పనిచేయలేదు: " + message); }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        if (gated) return;
        tester.stop();
        autosave(); // nothing he changed is lost, however he leaves
        if (changed) {
            boolean leaving = isFinishing() || isChangingConfigurations(); // (a theme change rebuilds the screen: also "leaving")
            afterChanges(leaving);
            if (leaving) changed = false;
        }
    }

    @Override protected void onDestroy() {
        if (isFinishing()) TabletLock.close(); // (left Settings: the PIN is asked again next time)
        if (gated) { super.onDestroy(); return; }
        if (showing == this) showing = null;
        try { prefs.sp.unregisterOnSharedPreferenceChangeListener(lockCalibrated); } catch (Exception ignored) {}
        if (pendingRestore != null) { Backup.discard(getApplicationContext(), pendingRestore); pendingRestore = null; } // its question went with the screen
        super.onDestroy();
    }

    private void showBargeSens() {
        String[] names = {"చాలా తక్కువ (గట్టిగా మాట్లాడితేనే ఆగుతుంది)", "తక్కువ", "మధ్యస్థం", "ఎక్కువ", "చాలా ఎక్కువ (మెల్లగా మాట్లాడినా ఆగుతుంది)"};
        String last = BargeIn.lastInfo;
        bargeSensLabel.setText("మధ్యలో మాట్లాడితే వినే సున్నితత్వం: " + names[Math.max(0, Math.min(4, bargeSens.getProgress()))]
                + (last == null || last.isEmpty() ? "" : "\nచివరిసారి: " + last));
        bargeSensLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
    }

    private void showSensitivity() {
        int p = sensitivity.getProgress();
        String level = p < 17 ? "తక్కువ (తప్పుగా మేల్కొనదు, కానీ గట్టిగా పిలవాలి)"
                : p < 34 ? "మధ్యస్థం" : "ఎక్కువ (సులువుగా మేల్కొంటుంది, అప్పుడప్పుడు పొరపాటున కూడా)";
        sensitivityLabel.setText("సున్నితత్వం: " + level);
        sensitivityLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
    }

    private EditText mapsKey;

    /** 📞 the permission ticks and the last calls (drawn again on resume). */
    private LinearLayout callChecks;

    /** 📞 Calls (Anil, 21 Oct): names said with his ringtone, voice answer / cut / silent / message, speaker, important people, ready messages, the checks. */
    private void callsSection() {
        section("📞 కాల్స్", "📞");
        note("కాల్ వస్తే మీ రింగ్‌టోన్ మోగుతూనే ఉంటుంది: Jarvis ఎవరో చెప్పి మీ మాట వినే కొన్ని సెకన్లు మాత్రమే రింగ్ కొంచెం తగ్గుతుంది (ఆగదు). "
                + "'ఎత్తు' = ఎత్తి లౌడ్‌స్పీకర్ · 'కట్ చేయి' = కట్ · 'సైలెంట్' = ఈ కాల్ రింగ్ మాత్రమే ఆపు · 'కట్ చేసి … అని మెసేజ్ పెట్టు' = కట్ చేసి, చదివి వినిపించి, 'పంపు' అన్నాకే SMS · "
                + "'తర్వాత చేస్తా' = కట్ చేసి 'గుర్తు చేయనా?' అని అడుగుతుంది. ఫోన్ సైలెంట్ / వైబ్రేట్ / Do Not Disturb / రాత్రి మోడ్‌లో ఉంటే పేరు చెప్పదు: "
                + "⭐ ముఖ్యమైన వాళ్ళు, 🔁 5 నిమిషాల్లో మళ్ళీ చేసినవాళ్ళు తప్ప.");
        announceCalls = toggle("📢 కాల్ వస్తే ఎవరో పైకి చెప్పు", prefs.announceCalls());
        callVoice = toggle("🗣️ మాటతో: ఎత్తు / కట్ / సైలెంట్ / మెసేజ్ (\"Jarvis\" అనక్కర్లేదు)", prefs.callByVoice());
        String[][] sw = {
                {"call_speaker", "1", "🔊 మాటతో (లేదా వాచ్ నుంచి) ఎత్తితే లౌడ్‌స్పీకర్ (ఇయర్‌ఫోన్స్ / కార్ బ్లూటూత్ ఉంటే వాటిలోనే)"},
                {"call_watch_voice", "1", "⌚ వాచ్‌లో కూడా పేరు చెప్పు (వాచ్ బటన్లు: ఎత్తు / కట్ / సైలెంట్)"},
                {"call_repeat", "1", "🔁 ఒకరే 5 నిమిషాల్లో మళ్ళీ చేస్తే 'అర్జెంట్ కావచ్చు' అని చెప్పు (ఫోన్ సైలెంట్‌లో ఉన్నా)"},
                {"call_scam", "1", "⚠️ తెలియని / విదేశీ / 140 (ప్రకటనలు) / 1600 (బ్యాంక్ సర్వీస్) / మీరు స్పామ్ అన్న నంబర్ అని చెప్పు"},
                {"call_drive_offer", "1", "🚗 డ్రైవ్ / బైక్‌లో 'కట్' అంటే 'డ్రైవింగ్' మెసేజ్ పంపనా? అని అడుగు"},
                {"call_missed_unlock", "1", "📵 చూడని మిస్డ్ కాల్స్: ఫోన్ అన్‌లాక్ చేసినప్పుడు ఒకసారి చెప్పు (ఉదయం 7 – రాత్రి 10; ఫోన్ సైలెంట్ / వైబ్రేట్‌లో ఉంటే చెప్పదు: అప్పుడు 'మిస్డ్ కాల్స్ ఏమున్నాయి?' అని అడగండి)"}};
        for (String[] t : sw) {
            Switch x = toggle(t[2], prefs.sp.getBoolean(t[0], "1".equals(t[1])));
            x.setOnCheckedChangeListener((b, v) -> prefs.sp.edit().putBoolean(t[0], v).apply());
        }
        EditText vip = field("⭐ ముఖ్యమైన వాళ్ళు (కాంటాక్ట్‌లో సేవ్ చేసిన పేర్లు, కామాతో): ఫోన్ సైలెంట్‌లో ఉన్నా, రాత్రి అయినా వీళ్ళ కాల్ చెబుతాను", CallCare.vipList(this), false);
        vip.setHint("అమ్మ, నాన్న");
        onText(vip, t -> CallCare.vipSet(this, t));
        TextView rl = Ui.text(this, "⚡ రెడీ మెసేజ్‌లు: కాల్ వస్తున్నప్పుడు 'మీటింగ్ మెసేజ్ పెట్టు' అనండి, లేదా ప్యానెల్‌లో 💬 నొక్కండి (నొక్కడమే మీ 'పంపు').", 15, Ui.TEXT);
        rl.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        box.addView(rl);
        for (String[] q : CallCare.READY) {
            EditText f = field(q[1], prefs.sp.getString("call_q_" + q[0], q[2]), false);
            onText(f, t -> prefs.sp.edit().putString("call_q_" + q[0], t.trim().isEmpty() ? q[2] : t.trim()).apply());
            Switch ok = toggle("↳ " + q[1].replaceFirst("^\\S+\\s", "") + " మెసేజ్ మాటతో అడిగినప్పుడు మళ్ళీ అడగకుండా పంపు", prefs.sp.getBoolean("call_q_" + q[0] + "_ok", false));
            ok.setOnCheckedChangeListener((b, v) -> prefs.sp.edit().putBoolean("call_q_" + q[0] + "_ok", v).apply());
        }
        note("మీ సొంత మాటలు అయితే ఎప్పుడూ చదివి వినిపించి 'పంపు' అన్నాకే పంపుతాను. SMS ఆ కాల్ చేసిన వాళ్ళకి మాత్రమే: ఆ కాల్ నంబర్ ఖచ్చితంగా దొరక్కపోతే పంపను "
                + "(WhatsApp కాల్ అయితే ఆ పేరుతో సేవ్ చేసిన ఒకే ఒక కాంటాక్ట్ నంబర్‌కి). దాచిన / విదేశీ / 140 / 1600 / స్పామ్ నంబర్లకి పంపను. "
                + "రెండు SIM లు ఉంటే: ఫోన్ Settings → Connections → SIM manager → Messages కి ఒక SIM ఎంచుకోండి ('Ask every time' ఉంటే Jarvis SIM తానుగా ఎంచుకోదు, SMS పంపదు).");
        int spamN = CallCare.spamSet(this).size();
        if (spamN > 0) button("🚫 మీరు స్పామ్ అన్న నంబర్లు: " + spamN + " · అన్నీ తీసేయ్", v -> {
            CallCare.priv(this).edit().remove("call_spam").apply();
            Toast.makeText(this, "స్పామ్ గుర్తులు తీసేశాను.", Toast.LENGTH_SHORT).show();
            ((TextView) v).setText("🚫 స్పామ్ గుర్తులు లేవు");
        });
        callChecks = new LinearLayout(this);
        callChecks.setOrientation(LinearLayout.VERTICAL);
        box.addView(callChecks);
        showCallChecks();
    }

    /** ✓ / ✗ for each thing the calls need (a ✗ opens its screen), and what Jarvis did with the last calls. */
    private void showCallChecks() {
        if (callChecks == null) return;
        callChecks.removeAllViews();
        TextView h = Ui.text(this, "🔍 చెక్ (✗ ఉన్నదాన్ని నొక్కండి):", 15, Ui.TEXT);
        h.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        callChecks.addView(h);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        callCheck("🔔 నోటిఫికేషన్ యాక్సెస్ (కాల్ వచ్చిందని Jarvis కి తెలియడానికి)", NotifyListener.enabled(this), () -> startActivity(NotifyListener.settingsIntent()));
        callCheck("🪟 ఇతర యాప్‌ల పైన చూపడం (ప్యానెల్ వచ్చి మీ మాట వినడానికి)", Settings.canDrawOverlays(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        String[][] perms = {{Manifest.permission.READ_PHONE_STATE, "📶 ఫోన్ స్థితి (SIM కాల్ మోగుతోందని Jarvis కి తెలియడానికి)"},
                {Manifest.permission.ANSWER_PHONE_CALLS, "📞 కాల్స్ ఎత్తడం / కట్ చేయడం"}, {Manifest.permission.READ_CALL_LOG, "📜 కాల్ లాగ్ (మిస్డ్ కాల్స్, ఎవరు చేశారు)"},
                {Manifest.permission.READ_CONTACTS, "👥 కాంటాక్ట్స్ (నంబర్‌కి పేరు)"}, {Manifest.permission.SEND_SMS, "💬 SMS పంపడం (బిజీ మెసేజ్)"}};
        for (String[] pm : perms)
            callCheck(pm[1], checkSelfPermission(pm[0]) == PackageManager.PERMISSION_GRANTED, () -> requestPermissions(new String[]{pm[0]}, 77));
        callCheck("📶 మెసేజ్‌లకి SIM ఎంచుకున్నారు (బిజీ SMS ఏ SIM నుంచి వెళ్ళాలో)", CallCare.smsSimSet(), () -> startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        callCheck("🔕 Do Not Disturb అనుమతి ('సైలెంట్' కోసం; కొన్ని ఫోన్లకి మాత్రమే కావాలి)", nm != null && nm.isNotificationPolicyAccessGranted(),
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        callCheck("♿ Jarvis యాక్సెసిబిలిటీ (కాల్ స్క్రీన్‌లో 'స్పీకర్' నొక్కడానికి)", JarvisAccessibility.enabled(),
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        TextView lh = Ui.text(this, "🧾 చివరి కాల్స్‌కి Jarvis ఏం చేసింది:", 15, Ui.TEXT);
        lh.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        callChecks.addView(lh);
        java.util.List<String> ev = CallCare.events(this);
        TextView evs = Ui.text(this, ev.isEmpty() ? "ఇంకా ఏ కాల్ రాలేదు (కాల్ వచ్చినా ఇక్కడ ఏమీ లేకపోతే: పైన 🔔 నోటిఫికేషన్ యాక్సెస్ చూడండి)."
                : android.text.TextUtils.join("\n\n", ev), 13.5f, Ui.MUTED);
        callChecks.addView(evs);
        java.util.List<String> seen = CallCare.seenLines(this);
        if (!seen.isEmpty()) {
            TextView sh = Ui.text(this, "👀 Jarvis చూసిన కాల్ సంకేతాలు (సాంకేతికం: సమస్య ఉంటే దీని స్క్రీన్‌షాట్ పంపండి):", 14, Ui.TEXT);
            sh.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
            callChecks.addView(sh);
            callChecks.addView(Ui.text(this, android.text.TextUtils.join("\n", seen), 12.5f, Ui.MUTED));
        }
    }

    private void callCheck(String label, boolean ok, Runnable fix) {
        TextView t = Ui.text(this, (ok ? "✓ " : "✗ ") + label + (ok ? "" : " · నొక్కండి"), 14, ok ? Ui.C_GREEN : Ui.C_ORANGE);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        if (!ok) t.setOnClickListener(v -> { try { fix.run(); } catch (Exception ignored) {} });
        callChecks.addView(t);
    }

    // ================================================================ 🥛 the milk account

    private TextView houseInfo;

    /** 🥛 What is set, a dialog to set / change it, the three switches, the month's pages. */
    private void houseSection() {
        section("🥛 ఇంటి లెక్కలు (పాలు)", "🥛");
        note("రోజూ వచ్చే పాలు Jarvis తానే లెక్క వేస్తాడు: వేరేగా ఉన్న రోజు మాత్రమే చెప్పండి: 'ఈ రోజు పాలు రాలేదు', 'ఈ రోజు 2 లీటర్లు', "
                + "'రేపటి నుంచి 3 రోజులు పాలు వద్దు', 'పాల డబ్బులు ఇచ్చాను', 'పాల బిల్లు ఎంత?'. అమ్మగారు ఇంటి టాబ్లెట్‌కి చెప్పినా ఇక్కడికే వస్తుంది "
                + "(ఆ లెక్క మాత్రమే, ఆమె మాటలు కాదు). నెల 1న గత నెల బిల్లు చెబుతాను.");
        houseInfo = Ui.text(this, "", 15, Ui.TEXT);
        houseInfo.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(houseInfo);
        showHouse();
        button("✏️ పాల లెక్క సెట్ చేయి / మార్చు (ధర, రోజూ ఎన్ని, ఎప్పటి నుంచి)", v -> houseDialog());
        String[][] sw = {{House.BILL_FIRST, "📊 ప్రతి నెల 1న గత నెల పాల బిల్లు చెప్పు (5, 10 తేదీల్లో ఇంకా ఇవ్వకపోతే గుర్తుచేయి)"},
                {House.TELL_AMMA, "🏠 అమ్మగారికి కూడా ఇంటి టాబ్లెట్‌లో బిల్లు చెప్పు"},
                {House.TO_EXPENSE, "💰 ఇచ్చిన పాల డబ్బులు నెల ఖర్చుల్లో కలుపు (ఇంటి ఖర్చుగా)"}};
        for (String[] t : sw) {
            Switch x = toggle(t[1], House.on(this, t[0]));
            x.setOnCheckedChangeListener((b, v) -> House.set(this, t[0], v));
        }
        button("📄 ఈ నెల పాల లెక్క PDF (క్యాలెండర్)", v -> housePdf(java.time.YearMonth.now(), false));
        button("📄 గత నెల పాల లెక్క PDF", v -> housePdf(java.time.YearMonth.now().minusMonths(1), false));
        button("📤 ఈ నెల PDF WhatsApp లో (చాట్ మీరే ఎంచుకుని పంపాలి)", v -> housePdf(java.time.YearMonth.now(), true));
    }

    private void showHouse() {
        if (houseInfo == null) return;
        try {
            org.json.JSONObject st = House.load(this);
            org.json.JSONObject it = HouseBook.item(st, HouseBook.MILK);
            if (it == null) {
                houseInfo.setText("ఇంకా సెట్ చేయలేదు: కింద ✏️ నొక్కండి, లేదా Jarvis కి చెప్పండి: \"పాలు రోజూ 1 లీటర్, లీటర్ 60, ఈ నెల 1 నుంచి లెక్క వెయ్యి\".");
                return;
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.YearMonth now = java.time.YearMonth.from(today);
            StringBuilder s = new StringBuilder("✓ ").append(HouseBook.setupLine(it, today)).append("\n\n")
                    .append(HouseBook.line(HouseBook.bill(st, HouseBook.MILK, now, today)));
            org.json.JSONObject last = HouseBook.bill(st, HouseBook.MILK, now.minusMonths(1), today);
            if (last.optBoolean("started")) s.append("\n").append(HouseBook.line(last));
            if (!HomeLink.linked(this)) s.append("\n\n(ఇంటి టాబ్లెట్‌తో కలపలేదు: అమ్మగారు చెప్పినవి రావు, ఆమెకి బిల్లు చెప్పలేను. కాపలా మోడ్ కార్డ్‌లో bot token పెట్టండి.)");
            houseInfo.setText(s);
        } catch (Exception e) {
            houseInfo.setText("పాల లెక్క చూపించలేకపోయాను: " + e.getMessage());
        }
    }

    /** Set up the milk (first time), or change the rate / the usual amount / the start / unit / the milkman's name. */
    private void houseDialog() {
        org.json.JSONObject st = House.load(this);
        org.json.JSONObject it = HouseBook.item(st, HouseBook.MILK);
        java.time.LocalDate today = java.time.LocalDate.now();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        l.setPadding(pad, Ui.dp(this, 8), pad, 0);
        String unit0 = it == null ? "లీటర్" : it.optString("unit", "లీటర్");
        RadioGroup unit = new RadioGroup(this);
        unit.setOrientation(RadioGroup.HORIZONTAL);
        java.util.List<String> units = new java.util.ArrayList<>(java.util.Arrays.asList("లీటర్", "ప్యాకెట్"));
        if (!unit0.isEmpty() && !units.contains(unit0)) units.add(unit0); // (one he said by voice, e.g. a can)
        java.util.Map<Integer, String> unitOf = new java.util.HashMap<>();
        for (String un : units) {
            RadioButton rb = new RadioButton(this);
            rb.setText(un);
            rb.setId(View.generateViewId());
            unit.addView(rb);
            unitOf.put(rb.getId(), un);
            if (un.equals(unit0)) unit.check(rb.getId());
        }
        l.addView(Ui.text(this, "కొలత:", 14, Ui.MUTED));
        l.addView(unit);
        l.addView(Ui.text(this, "💰 ఒక లీటర్ / ప్యాకెట్ ధర (₹):", 14, Ui.MUTED));
        EditText price = new EditText(this);
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        price.setHint("ఉదా: 60");
        final double p0 = it == null ? Double.NaN : HouseBook.priceOn(it, today), q0 = it == null ? Double.NaN : HouseBook.usualOn(it, today);
        if (it != null) price.setText(HouseBook.plain(p0));
        l.addView(price);
        l.addView(Ui.text(this, "🥛 రోజూ ఎన్ని (0.5 = అర):", 14, Ui.MUTED));
        EditText daily = new EditText(this);
        daily.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        daily.setText(it == null ? "1" : HouseBook.plain(q0));
        l.addView(daily);
        l.addView(Ui.text(this, "👤 పాలతని పేరు (అవసరం లేదు):", 14, Ui.MUTED));
        EditText vendor = new EditText(this);
        vendor.setText(it == null ? "" : it.optString("vendor"));
        l.addView(vendor);
        final java.time.LocalDate[] since = {it == null ? today.withDayOfMonth(1) : java.time.LocalDate.parse(it.optString("since"))};
        TextView sinceText = Ui.text(this, "", 15, Ui.CYAN);
        sinceText.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        Runnable showSince = () -> sinceText.setText("📅 లెక్క మొదలు: " + HouseBook.MONTHS[since[0].getMonthValue() - 1] + " " + since[0].getDayOfMonth() + ", " + since[0].getYear() + "  (మార్చడానికి నొక్కండి)");
        showSince.run();
        sinceText.setOnClickListener(v -> new android.app.DatePickerDialog(this, (dp, y, m, d) -> { since[0] = java.time.LocalDate.of(y, m + 1, d); showSince.run(); },
                since[0].getYear(), since[0].getMonthValue() - 1, since[0].getDayOfMonth()).show());
        l.addView(sinceText);
        RadioGroup when = new RadioGroup(this);
        RadioButton fromToday = new RadioButton(this), fromStart = new RadioButton(this);
        fromToday.setText("ధర / రోజూ ఎన్ని మారితే: ఈ రోజు నుంచి (ముందు రోజులు పాత ధరకే)");
        fromStart.setText("లెక్క మొదలు నుంచే (ముందు చెప్పింది తప్పు; తర్వాత మార్చిన తేదీలు అలాగే)");
        fromToday.setId(View.generateViewId());
        fromStart.setId(View.generateViewId());
        when.addView(fromToday);
        when.addView(fromStart);
        when.check(fromToday.getId());
        if (it != null) l.addView(when);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it == null ? "🥛 పాల లెక్క సెట్ చేయి" : "🥛 పాల లెక్క మార్చు")
                .setView(sc)
                .setPositiveButton("సేవ్", null) // (set below: a wrong number keeps the box open with what he typed)
                .setNegativeButton("వద్దు", null).create();
        dlg.setOnShowListener(x -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double p = num(price), q = num(daily);
            String u = unitOf.containsKey(unit.getCheckedRadioButtonId()) ? unitOf.get(unit.getCheckedRadioButtonId()) : unit0;
            if (!(p > 0) || p > 100_000) { Toast.makeText(this, "ధర సరిగ్గా రాయండి (ఉదా: 60).", Toast.LENGTH_LONG).show(); return; }
            if (!(q >= 0) || q > 50) { Toast.makeText(this, "రోజూ ఎన్ని: 0 నుంచి 50 లోపు రాయండి.", Toast.LENGTH_LONG).show(); return; }
            try {
                org.json.JSONObject r;
                String vend = vendor.getText().toString().trim();
                boolean priceChanged = it == null || Math.abs(p - p0) > 1e-9, dailyChanged = it == null || Math.abs(q - q0) > 1e-9;
                if (it == null) { // the first time
                    r = House.tool(this, new org.json.JSONObject().put("action", "setup").put("price", p).put("daily", q).put("unit", u)
                            .put("from", since[0].toString()).put("vendor", vend));
                } else if (when.getCheckedRadioButtonId() == fromStart.getId()) { // wrong from the beginning: only what he changed, as the first value
                    org.json.JSONObject a0 = new org.json.JSONObject().put("action", "setup").put("from_start", true).put("unit", u)
                            .put("from", since[0].toString()).put("vendor", vend);
                    if (priceChanged) a0.put("price", p);
                    if (dailyChanged) a0.put("daily", q);
                    r = House.tool(this, a0);
                } else { // the start (if moved), the unit, the name; a new rate / amount from today on
                    org.json.JSONObject a0 = new org.json.JSONObject().put("action", "setup").put("unit", u).put("vendor", vend);
                    if (!since[0].toString().equals(it.optString("since"))) a0.put("from_start", true).put("from", since[0].toString());
                    r = House.tool(this, a0);
                    if (r.optBoolean("ok") && priceChanged)
                        r = House.tool(this, new org.json.JSONObject().put("action", "price").put("price", p).put("from", today.toString()));
                    if (r.optBoolean("ok") && dailyChanged)
                        r = House.tool(this, new org.json.JSONObject().put("action", "daily").put("daily", q).put("from", today.toString()));
                }
                Toast.makeText(this, r.optBoolean("ok") ? "✓ సేవ్ అయింది" : "సేవ్ కాలేదు, మళ్ళీ చూడండి.", Toast.LENGTH_SHORT).show();
                if (r.optBoolean("ok")) dlg.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "సేవ్ కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            showHouse();
        }));
        dlg.show();
    }

    private static double num(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim().replace(',', '.')); } catch (Exception x) { return Double.NaN; }
    }

    /** The month's calendar page: opened, or into WhatsApp (he picks the chat and sends). */
    private void housePdf(java.time.YearMonth ym, boolean share) {
        Toast.makeText(this, "PDF తయారవుతోంది…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Coder.Made m = House.pdf(getApplicationContext(), HouseBook.MILK, ym);
                runOnUiThread(() -> {
                    if (m == null) Toast.makeText(this, "పాల లెక్క ఇంకా సెట్ చేయలేదు.", Toast.LENGTH_LONG).show();
                    else if (m.uri == null) Toast.makeText(this, "సేవ్ అయింది: " + m.where, Toast.LENGTH_LONG).show();
                    else if (share) Cards.share(this, m, HouseBook.MONTHS[ym.getMonthValue() - 1] + " పాల లెక్క");
                    else Cards.view(this, m);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "PDF కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "house-pdf").start();
    }

    // ================================================================ 🚗 the car's diesel, mileage and service

    private TextView carAcctInfo;

    /** 🚗 What is kept, typing a fill / service / meter reading, the car's details, the switches, the month's pages, undo. */
    private void carSection() {
        section("🚗 కారు లెక్కలు (ఇంధనం, మైలేజ్, సర్వీస్)", "🚗");
        note("బంక్‌లో కొట్టించాక Jarvis కి చెప్పండి: 'కారుకి ₹3000 డీజిల్, 32 లీటర్లు, మీటర్ 45,230, ఫుల్'. మీటర్ రీడింగ్ చెబితే మైలేజ్ కచ్చితంగా వస్తుంది "
                + "(ట్యాంక్ ఫుల్ చేయించినప్పుడు 'ఫుల్' అంటే ఇంకా కచ్చితం); చెప్పకపోతే GPS కారు ట్రిప్పుల కి.మీ తో సుమారు లెక్క. అడగొచ్చు: 'మైలేజ్ ఎంత వస్తోంది?', "
                + "'ఈ నెల డీజిల్‌కి ఎంత?', 'కారు సర్వీస్ ఎప్పుడు?'. Jarvis డబ్బులు కట్టడు; అన్నీ ఈ ఫోన్‌లోనే.");
        carAcctInfo = Ui.text(this, "", 15, Ui.TEXT);
        carAcctInfo.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(carAcctInfo);
        showCar();
        String fw = CarBook.fuelWord(CarBook.fuel(Car.load(this)));
        button("⛽ " + fw + " కొట్టించినది రాయి (టైప్ చేసి)", v -> carFillDialog());
        button("🔧 కారు సర్వీస్ అయింది", v -> carServiceDialog());
        button("📏 మీటర్ రీడింగ్ మాత్రమే రాయి", v -> carOdoDialog());
        button("✏️ కారు వివరాలు (పేరు, ఇంధనం, సర్వీస్ ఎప్పుడు)", v -> carSetupDialog());
        String[][] sw = {{Car.SERVICE, "🔧 సర్వీస్ దగ్గర పడితే ముందే గుర్తుచేయి (15 రోజులు / 500 కి.మీ ముందు)"},
                {Car.TO_EXPENSE, "💰 ఇంధనం, సర్వీస్ ఖర్చు నెల ఖర్చుల్లో కలుపు"},
                {Car.DROP_TELL, "📉 మైలేజ్ మామూలు కంటే బాగా తగ్గితే చెప్పు (మీటర్ రీడింగ్ లెక్క నుంచే)"}};
        for (String[] t : sw) {
            Switch x = toggle(t[1], Car.on(this, t[0]));
            x.setOnCheckedChangeListener((b, v) -> Car.set(this, t[0], v));
        }
        button("📄 ఈ నెల కారు లెక్క PDF", v -> carPdf(java.time.YearMonth.now(), false));
        button("📄 గత నెల కారు లెక్క PDF", v -> carPdf(java.time.YearMonth.now().minusMonths(1), false));
        button("📤 ఈ నెల PDF WhatsApp లో (చాట్ మీరే ఎంచుకుని పంపాలి)", v -> carPdf(java.time.YearMonth.now(), true));
        button("↩️ చివరిగా రాసింది తీసేయి (తప్పుగా రాస్తే)", v -> carUndo());
    }

    private void showCar() {
        if (carAcctInfo == null) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> { // (the trip diary is read from a file)
            String text;
            try {
                org.json.JSONObject st = Car.load(app);
                if (!CarBook.isSet(st)) {
                    text = "ఇంకా మొదలవలేదు: మొదటిసారి డీజిల్ కొట్టించినప్పుడు Jarvis కి చెప్పండి, లేదా కింద ⛽ నొక్కి రాయండి. ✏️ లో చివరి సర్వీస్ తేదీ పెడితే సర్వీస్ ముందే గుర్తుచేస్తాను.";
                } else {
                    java.util.List<double[]> tr = Car.trips(app);
                    long now = System.currentTimeMillis();
                    java.time.ZoneId z = java.time.ZoneId.systemDefault();
                    java.time.LocalDate today = java.time.LocalDate.now(z);
                    StringBuilder b = new StringBuilder("✓ ").append(CarBook.name(st)).append(" · ").append(CarBook.fuelWord(CarBook.fuel(st)))
                            .append(" · సర్వీస్ ప్రతి ").append(CarBook.km(CarBook.everyKm(st))).append(" కి.మీ / ").append(CarBook.everyMonths(st)).append(" నెలలు\n\n")
                            .append(CarBook.mileageLine(st, tr));
                    String m = CarBook.monthLine(st, CarBook.month(st, tr, java.time.YearMonth.from(today), today, now, z));
                    if (!m.isEmpty()) b.append("\n").append(m);
                    java.util.List<org.json.JSONObject> fs = CarBook.fills(st);
                    if (!fs.isEmpty()) b.append("\nచివరిసారి: ").append(CarBook.dayName(fs.get(fs.size() - 1).optString("d"))).append(" · ")
                            .append(CarBook.fillText(fs.get(fs.size() - 1), CarBook.fuel(st)));
                    b.append("\n").append(CarBook.serviceLine(st, CarBook.service(st, tr, today, now, z)));
                    text = b.toString();
                }
            } catch (Exception e) {
                text = "కారు లెక్క చూపించలేకపోయాను: " + e.getMessage();
            }
            final String t = text;
            runOnUiThread(() -> { if (carAcctInfo != null) carAcctInfo.setText(t); });
        }, "car-info").start();
    }

    private EditText carNumber(LinearLayout l, String label, String hint, String value) {
        l.addView(Ui.text(this, label, 14, Ui.MUTED));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint);
        if (value != null) e.setText(value);
        l.addView(e);
        return e;
    }

    /** A date line he taps to change (today unless he picks another day). */
    private TextView carDay(LinearLayout l, java.time.LocalDate[] day, String label) {
        TextView t = Ui.text(this, "", 15, Ui.CYAN);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        Runnable show = () -> t.setText("📅 " + label + ": " + (day[0] == null ? "పెట్టలేదు" : CarBook.dayName(day[0].toString()) + ", " + day[0].getYear()) + "  (మార్చడానికి నొక్కండి)");
        show.run();
        t.setOnClickListener(v -> {
            java.time.LocalDate d0 = day[0] == null ? java.time.LocalDate.now() : day[0];
            android.app.DatePickerDialog dp = new android.app.DatePickerDialog(this, (x, y, m, d) -> { day[0] = java.time.LocalDate.of(y, m + 1, d); show.run(); },
                    d0.getYear(), d0.getMonthValue() - 1, d0.getDayOfMonth());
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });
        l.addView(t);
        return t;
    }

    private LinearLayout carBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        l.setPadding(pad, Ui.dp(this, 8), pad, 0);
        return l;
    }

    /** A dialog's values as the car tool's words (null: a message was shown, the box stays open); meterOk: he said a big jump is right. */
    private interface CarArgs { org.json.JSONObject make(boolean meterOk) throws Exception; }

    /** The dialog stays open on a wrong number (what he typed stays); a good save shows what was written. */
    private void carSave(android.app.AlertDialog dlg, CarArgs args) {
        dlg.setOnShowListener(x -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> carSend(dlg, args, false)));
        dlg.show();
    }

    /** Saved off the main thread (the trip diary is read for the mileage); the answer is shown if the screen is still open. */
    private void carSend(android.app.AlertDialog dlg, CarArgs args, boolean meterOk) {
        org.json.JSONObject a;
        try { a = args.make(meterOk); } catch (Exception e) { Toast.makeText(this, "సేవ్ కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        if (a == null) return;
        android.view.View save = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        save.setEnabled(false);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            org.json.JSONObject r;
            String fail = "";
            try { r = Car.tool(app, a); } catch (Exception e) { r = null; fail = String.valueOf(e.getMessage()); }
            final org.json.JSONObject res = r;
            final String why = fail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                save.setEnabled(true);
                if (res == null) { Toast.makeText(this, "సేవ్ కాలేదు: " + why, Toast.LENGTH_LONG).show(); return; }
                if (!res.optBoolean("ok")) {
                    if ("big_jump".equals(res.optString("error")))
                        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("📏 మీటర్ సరైనదేనా?").setMessage(res.optString("say"))
                                .setPositiveButton("సరైనదే, రాయి", (d, w) -> carSend(dlg, args, true)).setNegativeButton("మార్చుతాను", null).show();
                    else Toast.makeText(this, res.optString("say", "సేవ్ కాలేదు, మళ్ళీ చూడండి."), Toast.LENGTH_LONG).show();
                    return;
                }
                dlg.dismiss();
                StringBuilder b = new StringBuilder(res.optString("said"));
                if (res.has("mileage")) b.append("\n\n").append(res.optString("mileage"));
                if (res.has("mileage_drop")) b.append("\n\n📉 ").append(res.optString("mileage_drop"));
                if (res.has("service")) b.append("\n\n🔧 ").append(res.optString("service"));
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("✓ సేవ్ అయింది").setMessage(b.toString())
                        .setPositiveButton("సరే", null).show();
                showCar();
            });
        }, "car-save").start();
    }

    private android.app.AlertDialog carDialog(String title, LinearLayout l) {
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        return new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(sc)
                .setPositiveButton("సేవ్", null).setNegativeButton("వద్దు", null).create();
    }

    private static void putNum(org.json.JSONObject o, String k, EditText e) throws Exception {
        double v = num(e);
        if (!Double.isNaN(v)) o.put(k, v);
    }

    /** ⛽ A fill, typed: rupees, litres (or the rate), the meter, full tank, the day. */
    private void carFillDialog() {
        String fw = CarBook.fuelWord(CarBook.fuel(Car.load(this)));
        LinearLayout l = carBox();
        EditText rs = carNumber(l, "💰 ఎన్ని రూపాయలు:", "ఉదా: 3000", null);
        EditText lt = carNumber(l, "⛽ ఎన్ని లీటర్లు:", "ఉదా: 32", null);
        EditText rate = carNumber(l, "🏷️ లీటర్ రేటు (లీటర్లు రాయకపోతే):", "ఉదా: 93.75", null);
        EditText odo = carNumber(l, "📏 మీటర్ రీడింగ్ (మొత్తం ODO కి.మీ, ట్రిప్ మీటర్ కాదు; వీలైతే):", "ఉదా: 45230", null);
        android.widget.CheckBox full = new android.widget.CheckBox(this);
        full.setText("ట్యాంక్ ఫుల్ చేయించాను");
        full.setTextColor(Ui.TEXT);
        l.addView(full);
        final java.time.LocalDate[] day = {java.time.LocalDate.now()};
        carDay(l, day, "ఏ రోజు");
        android.app.AlertDialog dlg = carDialog("⛽ " + fw + " రాయి", l);
        carSave(dlg, ok -> {
            org.json.JSONObject a = new org.json.JSONObject().put("action", "fill").put("full", full.isChecked()).put("date", day[0].toString()).put("meter_ok", ok);
            putNum(a, "rupees", rs);
            putNum(a, "litres", lt);
            putNum(a, "rate", rate);
            putNum(a, "odometer", odo);
            return a;
        });
    }

    /** 🔧 A service done: the cost, the meter, what was done, the day (each one optional). */
    private void carServiceDialog() {
        LinearLayout l = carBox();
        EditText rs = carNumber(l, "💰 సర్వీస్ ఖర్చు (రూపాయలు):", "ఉదా: 4500", null);
        EditText odo = carNumber(l, "📏 మీటర్ రీడింగ్ (కి.మీ):", "ఉదా: 50150", null);
        l.addView(Ui.text(this, "🛠️ ఏం చేశారు (అవసరం లేదు):", 14, Ui.MUTED));
        EditText what = new EditText(this);
        what.setHint("ఉదా: ఆయిల్ మార్పు, ఫిల్టర్");
        l.addView(what);
        final java.time.LocalDate[] day = {java.time.LocalDate.now()};
        carDay(l, day, "ఏ రోజు");
        carSave(carDialog("🔧 కారు సర్వీస్ అయింది", l), ok -> {
            org.json.JSONObject a = new org.json.JSONObject().put("action", "service").put("date", day[0].toString()).put("what", what.getText().toString().trim())
                    .put("meter_ok", ok);
            putNum(a, "rupees", rs);
            putNum(a, "odometer", odo);
            return a;
        });
    }

    /** 📏 Only the meter reading (helps the km and the service). */
    private void carOdoDialog() {
        LinearLayout l = carBox();
        EditText odo = carNumber(l, "📏 మీటర్ రీడింగ్ (కి.మీ):", "ఉదా: 45500", null);
        final java.time.LocalDate[] day = {java.time.LocalDate.now()};
        carDay(l, day, "ఏ రోజు");
        carSave(carDialog("📏 మీటర్ రీడింగ్", l), ok -> {
            if (!(num(odo) > 0)) { Toast.makeText(this, "మీటర్ రీడింగ్ రాయండి (మొత్తం ODO).", Toast.LENGTH_LONG).show(); return null; }
            return new org.json.JSONObject().put("action", "meter").put("odometer", num(odo)).put("date", day[0].toString()).put("meter_ok", ok);
        });
    }

    /** ✏️ The car's name, its fuel, the service (every N km / months, the last one before Jarvis kept the account). */
    private void carSetupDialog() {
        org.json.JSONObject st = Car.load(this);
        org.json.JSONObject car = CarBook.car(st);
        LinearLayout l = carBox();
        l.addView(Ui.text(this, "🚗 కారు పేరు:", 14, Ui.MUTED));
        EditText name = new EditText(this);
        name.setText(CarBook.name(st));
        l.addView(name);
        l.addView(Ui.text(this, "⛽ ఇంధనం:", 14, Ui.MUTED));
        RadioGroup fuel = new RadioGroup(this);
        fuel.setOrientation(RadioGroup.HORIZONTAL);
        String[][] fuels = {{"diesel", "డీజిల్"}, {"petrol", "పెట్రోల్"}, {"cng", "CNG"}};
        java.util.Map<Integer, String> fuelOf = new java.util.HashMap<>();
        for (String[] f : fuels) {
            RadioButton rb = new RadioButton(this);
            rb.setText(f[1]);
            rb.setId(View.generateViewId());
            fuel.addView(rb);
            fuelOf.put(rb.getId(), f[0]);
            if (f[0].equals(CarBook.fuel(st))) fuel.check(rb.getId());
        }
        l.addView(fuel);
        EditText km = carNumber(l, "🔧 సర్వీస్ ప్రతి ఎన్ని కి.మీ కి (సర్వీస్ బుక్‌లో ఉన్నట్టు):", "ఉదా: 10000", String.valueOf(CarBook.everyKm(st)));
        EditText months = carNumber(l, "🔧 లేదా ఎన్ని నెలలకి:", "ఉదా: 12", String.valueOf(CarBook.everyMonths(st)));
        final java.time.LocalDate[] last = {car == null || car.optString("svc0_date").isEmpty() ? null : java.time.LocalDate.parse(car.optString("svc0_date"))};
        final java.time.LocalDate last0 = last[0];
        carDay(l, last, "Jarvis లెక్క మొదలవక ముందు చివరి సర్వీస్");
        final String km0 = car != null && car.has("svc0_odo") ? String.valueOf(car.optLong("svc0_odo")) : "";
        EditText lastKm = carNumber(l, "📏 ఆ సర్వీస్ అప్పుడు మీటర్ (కి.మీ, తెలిస్తే):", "ఉదా: 40000", km0.isEmpty() ? null : km0);
        carSave(carDialog(car == null ? "🚗 కారు లెక్క మొదలుపెట్టు" : "🚗 కారు వివరాలు మార్చు", l), ok -> {
            double k = num(km), mo = num(months);
            if (!(k >= 1000 && k <= 60_000)) { Toast.makeText(this, "సర్వీస్ కి.మీ: 1,000 నుంచి 60,000 మధ్య రాయండి.", Toast.LENGTH_LONG).show(); return null; }
            if (!(mo >= 1 && mo <= 36)) { Toast.makeText(this, "సర్వీస్ నెలలు: 1 నుంచి 36 మధ్య రాయండి.", Toast.LENGTH_LONG).show(); return null; }
            String f = fuelOf.containsKey(fuel.getCheckedRadioButtonId()) ? fuelOf.get(fuel.getCheckedRadioButtonId()) : CarBook.fuel(st);
            org.json.JSONObject a = new org.json.JSONObject().put("action", "setup").put("name", name.getText().toString().trim()).put("fuel", f)
                    .put("every_km", Math.round(k)).put("every_months", Math.round(mo));
            boolean newDay = last[0] != null && !last[0].equals(last0); // (only what he changed: the same date keeps its time, a new one drops the old reading)
            if (newDay) a.put("last_service_date", last[0].toString());
            if (!lastKm.getText().toString().trim().equals(km0)) putNum(a, "last_service_km", lastKm); // (a new date with the old km left in the box: that reading is dropped)
            return a;
        });
    }

    /** ↩️ The newest entry, shown first; taken out only after his yes. */
    private void carUndo() {
        try {
            org.json.JSONObject r = Car.tool(this, new org.json.JSONObject().put("action", "undo"));
            if (!r.optBoolean("ok")) { Toast.makeText(this, r.optString("say", "తీసేయడానికి ఏమీ లేదు."), Toast.LENGTH_LONG).show(); return; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("↩️ తీసేయనా?").setMessage(r.optString("ask"))
                    .setPositiveButton("అవును, తీసేయి", (d, w) -> {
                        try {
                            org.json.JSONObject x = Car.tool(this, new org.json.JSONObject().put("action", "undo").put("confirmed", true).put("id", r.optString("id")));
                            Toast.makeText(this, x.optBoolean("ok") ? x.optString("said") : x.optString("say"), Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "తీసేయలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        showCar();
                    })
                    .setNegativeButton("వద్దు", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "చూడలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** The month's page: opened, or into WhatsApp (he picks the chat and sends). */
    private void carPdf(java.time.YearMonth ym, boolean share) {
        Toast.makeText(this, "PDF తయారవుతోంది…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Coder.Made m = Car.pdf(getApplicationContext(), ym);
                runOnUiThread(() -> {
                    if (m == null) Toast.makeText(this, "కారు లెక్క ఇంకా మొదలవలేదు.", Toast.LENGTH_LONG).show();
                    else if (m.uri == null) Toast.makeText(this, "సేవ్ అయింది: " + m.where, Toast.LENGTH_LONG).show();
                    else if (share) Cards.share(this, m, CarBook.MONTHS[ym.getMonthValue() - 1] + " కారు లెక్క");
                    else Cards.view(this, m);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "PDF కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "car-pdf").start();
    }

    /** Saves a text box as he types (no Save button needed). */
    private static void onText(EditText e, java.util.function.Consumer<String> save) {
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { save.accept(s.toString()); }
        });
    }

    /** 🗺️ Jarvis Maps: his Google Maps key (kept on the phone only), its test, this month's count, the Gemini + Google Maps notes. */
    private void jarvisMapsSection() {
        section("🗺️ Jarvis Maps", "🗺️");
        note("Jarvis హోమ్‌లో '🗺️ Jarvis Maps' నొక్కండి, లేదా 'Jarvis, Jarvis Maps తెరువు' అనండి. Google Maps key పెడితే: Google మ్యాప్ (తెలుగు పేర్లు, ట్రాఫిక్, శాటిలైట్), "
                + "దారిలో టీ / పెట్రోల్ / హోటల్ ఎన్ని కి.మీ. ముందు, తెరిచి ఉందా, దారులు (ట్రాఫిక్‌తో టైమ్, టోల్). key లేకపోతే ఉచిత OpenStreetMap మ్యాప్. "
                + "key ఈ ఫోన్‌లోనే ఉంటుంది: చాట్‌లో, స్క్రీన్‌షాట్‌లో ఎప్పుడూ పంపకండి. Google ఉచిత పరిమితి దాటితేనే ఖర్చు (కింద ఈ నెల లెక్క).");
        mapsKey = field("Google Maps API key (AIza…; Jarvis Maps కోసం మాత్రమే, Gemini key కాదు)", prefs.sp.getString("maps_key", ""), true);
        button("🔑 Maps key పరీక్ష (మ్యాప్, దారులు, ప్లేస్‌లు)", v -> {
            prefs.sp.edit().putString("maps_key", mapsKey.getText().toString().trim()).apply();
            Toast.makeText(this, "పరీక్షిస్తున్నాను… (10–20 సెకన్లు)", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String r = GMaps.test(getApplicationContext());
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🔑 Maps key పరీక్ష").setMessage(r).setPositiveButton("సరే", null).show(); });
            }, "jarvis-maps-test").start();
        });
        TextView use = Ui.text(this, GMaps.usageLine(this), 13, Ui.MUTED);
        use.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(use);
        Switch notes = toggle("🛣️ 'ఈ రోడ్డు ఎటు?' లో Gemini + Google Maps వివరాలు (మీ Gemini key; Google ధరల పేజీ ప్రకారం billing తో కొత్త మోడల్స్‌కి నెలకి 5,000 ఉచితం)", prefs.sp.getBoolean("maps_notes", true));
        notes.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("maps_notes", v).apply());
        // build 2: while driving to a place Jarvis knows (each its own switch)
        String[][] sw = {
                {"maps_traffic_watch", "1", "🚦 ప్రయాణంలో ట్రాఫిక్ ఆలస్యం / తక్కువ టైమ్ దారి తెలుగులో చెప్పు (20 నిమిషాలకి ఒకసారి చూస్తాను, ప్రయాణానికి 8 సార్ల వరకు)"},
                {"maps_parking", "1", "🅿️ గమ్యానికి 2 కి.మీ ముందు దగ్గర్లో పార్కింగ్ చెప్పు"},
                {"maps_drive_screen", "1", "🚗 కారు / బైక్ కదలగానే Jarvis Maps లో పెద్ద అక్షరాల డ్రైవ్ స్క్రీన్"},
                {"maps_watch_trip", "1", "⌚ ప్రయాణంలో వాచ్‌లో చిన్న కార్డ్ (ఎంత టైమ్, ఎప్పుడు చేరతారు; బజ్ ఉండదు)"},
                {"maps_trip_log", "1", "🧾 ప్రయాణాల డైరీ (ఫోన్‌లోనే; బ్యాకప్‌లో కూడా వెళ్ళదు)"},
                {"maps_home_eta", "0", "👵 ఇంటికి వస్తున్నప్పుడు అమ్మగారి టాబ్లెట్‌కి 'ఎప్పుడు వస్తాడు' చెప్పు (టైమ్ మాత్రమే, మీరు ఎక్కడ ఉన్నారో కాదు)"}};
        for (String[] t : sw) {
            Switch x = toggle(t[2], prefs.sp.getBoolean(t[0], "1".equals(t[1])));
            x.setOnCheckedChangeListener((b, v) -> prefs.sp.edit().putBoolean(t[0], v).apply());
        }
        note("Google Cloud లో key ని 'Android apps' కి లాక్ చేయడానికి Jarvis గుర్తు: package com.anil.jarvis · SHA-1 " + GMaps.certShown(this));
        button("🗺️ Jarvis Maps తెరువు", v -> JarvisMaps.open(this, ""));
    }

    /** 🎨 The car screen's look (inside the car Jarvis section, the box only): one of ten themes, his car's colour, the dance, Jarvis's look. */
    private void carLookSection() {
        TextView head = Ui.text(this, "🎨 కారు స్క్రీన్ రూపం", 15.5f, Ui.TEXT);
        head.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(head);
        final String[] themeNames = new String[CarTheme.COUNT];
        for (int i = 0; i < CarTheme.COUNT; i++) themeNames[i] = (i + 1) + " · " + CarTheme.ALL[i].name;
        TextView theme = Ui.text(this, "", 15.5f, accent);
        theme.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView colour = Ui.text(this, "", 15.5f, accent);
        colour.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView dance = Ui.text(this, "", 15.5f, accent);
        dance.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        final TextView accentRow = Ui.text(this, "", 15.5f, accent), gaugeRow = Ui.text(this, "", 15.5f, accent);
        wallRow = Ui.text(this, "", 15.5f, accent);
        heroRow = Ui.text(this, "", 15.5f, accent);
        heroRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        for (TextView t : new TextView[]{accentRow, gaugeRow, wallRow}) t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        final String[] dances = {"always", "parked", "off"}, danceNames = {"ఎప్పుడూ (పాట వస్తే, రోడ్డు మీద కూడా)", "కారు ఆగినప్పుడే", "వద్దు"};
        Runnable show = () -> {
            theme.setText("🖼️ థీమ్: " + CarTheme.of(prefs.carTheme()).name + "  (మార్చడానికి నొక్కండి)");
            int ci = 0;
            for (int i = 0; i < CarTheme.CAR_COLOURS.length; i++) if (CarTheme.CAR_COLOURS[i] == prefs.carColour()) ci = i;
            colour.setText("🚙 స్క్రీన్ మీద కారు రంగు: " + CarTheme.CAR_COLOUR_NAMES[ci] + "  (మార్చడానికి నొక్కండి)");
            int ai = 0;
            for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i] == prefs.carAccent()) ai = i;
            accentRow.setText("✨ నడిపేటప్పుడు రంగు: " + ACCENT_NAMES[ai] + "  (మార్చడానికి నొక్కండి)");
            int gi = Math.max(0, java.util.Arrays.asList(GAUGES).indexOf(prefs.carGauge()));
            gaugeRow.setText("🧭 స్పీడ్ మీటర్: " + GAUGE_NAMES[gi] + "  (మార్చడానికి నొక్కండి)");
            boolean has = new java.io.File(getFilesDir(), "car_wall.jpg").exists();
            wallRow.setText(has ? "🖼️ వాల్‌పేపర్: మీ ఫోటో  (మార్చడానికి / తీసేయడానికి నొక్కండి)" : "🖼️ వాల్‌పేపర్: థీమ్‌దే  (మీ ఫోటో పెట్టడానికి నొక్కండి)");
            heroRow.setText("🚙 కారు: " + (CarPhoto.on(this, prefs) ? "నా ఫోటో" : "మన బొమ్మ")
                    + (CarPhoto.driveFromBehind(this, prefs) ? " · నడిపేటప్పుడు వెనుక నుంచి" : "") + "  (మార్చడానికి నొక్కండి)");
            int di = Math.max(0, java.util.Arrays.asList(dances).indexOf(prefs.carDance()));
            dance.setText("💃 Jarvis డాన్స్: " + danceNames[di] + "  (మార్చడానికి నొక్కండి)");
        };
        show.run();
        theme.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు స్క్రీన్ థీమ్")
                .setSingleChoiceItems(themeNames, Math.max(0, prefs.carTheme() - 1), (d, w) -> {
                    prefs.sp.edit().putInt("car_theme", w + 1).apply();
                    show.run();
                    d.dismiss();
                }).show());
        colour.setOnClickListener(v -> {
            int ci = 0;
            for (int i = 0; i < CarTheme.CAR_COLOURS.length; i++) if (CarTheme.CAR_COLOURS[i] == prefs.carColour()) ci = i;
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("మీ కారు రంగు (స్క్రీన్ మీద కారు బొమ్మకి)")
                    .setSingleChoiceItems(CarTheme.CAR_COLOUR_NAMES, ci, (d, w) -> {
                        prefs.sp.edit().putInt("car_colour", CarTheme.CAR_COLOURS[w]).apply();
                        show.run();
                        d.dismiss();
                    }).show();
        });
        dance.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("పాట వస్తే Jarvis డాన్స్")
                .setSingleChoiceItems(danceNames, Math.max(0, java.util.Arrays.asList(dances).indexOf(prefs.carDance())), (d, w) -> {
                    prefs.sp.edit().putString("car_dance", dances[w]).apply();
                    show.run();
                    d.dismiss();
                }).show());
        accentRow.setOnClickListener(v -> {
            int ai = 0;
            for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i] == prefs.carAccent()) ai = i;
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("నడిపేటప్పుడు స్క్రీన్ రంగు (పాట / ఆగినప్పుడు థీమ్‌దే)")
                    .setSingleChoiceItems(ACCENT_NAMES, ai, (d, w) -> { prefs.sp.edit().putInt("car_accent", ACCENTS[w]).apply(); show.run(); d.dismiss(); }).show();
        });
        gaugeRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("స్పీడ్ మీటర్ స్టైల్")
                .setSingleChoiceItems(GAUGE_NAMES, Math.max(0, java.util.Arrays.asList(GAUGES).indexOf(prefs.carGauge())), (d, w) -> {
                    prefs.sp.edit().putString("car_gauge", GAUGES[w]).apply();
                    show.run();
                    d.dismiss();
                }).show());
        wallShow = show;
        wallRow.setOnClickListener(v -> {
            final java.io.File f = new java.io.File(getFilesDir(), "car_wall.jpg");
            if (!f.exists()) { pickWall(); return; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("వాల్‌పేపర్")
                    .setItems(new String[]{"🖼️ వేరే ఫోటో", "🗑️ ఫోటో తీసేయి (థీమ్‌దే)"}, (d, w) -> {
                        if (w == 0) pickWall();
                        else { if (!f.delete()) f.deleteOnExit(); show.run(); }
                    }).show();
        });
        heroRow.setOnClickListener(v -> {
            final boolean has = CarPhoto.file(this).exists();
            java.util.List<String> items = new java.util.ArrayList<>();
            items.add("🎨 మన బొమ్మ");
            items.add(has ? "📷 నా ఫోటో" : "📷 నా కారు ఫోటో ఎంచుకో");
            if (has) { items.add("🖼️ వేరే ఫోటో ఎంచుకో"); items.add("📍 చక్రాలు, లైట్లు మళ్ళీ గుర్తించు"); items.add("🗑️ ఫోటో తీసేయి"); }
            final boolean hasRear = CarPhoto.rearFile(this).exists();
            if (!hasRear) items.add("🚗 వెనుక ఫోటో ఎంచుకో (నడిపేటప్పుడు వెనుక నుంచి)");
            else {
                items.add(CarPhoto.driveFromBehind(this, prefs) ? "🚗 నడిపేటప్పుడు: వెనుక నుంచి ✓ (పక్కకి మార్చు)" : "🚗 నడిపేటప్పుడు: పక్క నుంచి (వెనుకకి మార్చు)");
                items.add("🚗 వేరే వెనుక ఫోటో ఎంచుకో");
                items.add("🚗📍 వెనుక లైట్లు మళ్ళీ గుర్తించు");
                items.add("🚗🗑️ వెనుక ఫోటో తీసేయి");
            }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("స్క్రీన్ మీద కారు (మీ ఫోటో: కారు పక్క నుంచి నేరుగా తీసింది, రెండు చక్రాలు గుండ్రంగా)")
                    .setItems(items.toArray(new String[0]), (d, w) -> {
                        String it = items.get(w);
                        if (it.startsWith("🚗📍")) startActivity(new Intent(this, CarPhotoActivity.class).putExtra(CarPhotoActivity.EXTRA_REAR, true));
                        else if (it.startsWith("🚗🗑️")) {
                            if (!CarPhoto.rearFile(this).delete()) CarPhoto.rearFile(this).deleteOnExit();
                            prefs.sp.edit().remove("car_rear_marks").apply();
                        }
                        else if (it.startsWith("🚗 నడిపేటప్పుడు")) {
                            if (CarPhoto.rearMarks(prefs) == null) startActivity(new Intent(this, CarPhotoActivity.class).putExtra(CarPhotoActivity.EXTRA_REAR, true));
                            else prefs.sp.edit().putString("car_drive_view", CarPhoto.driveFromBehind(this, prefs) ? "side" : "rear").apply();
                        }
                        else if (it.startsWith("🚗")) pickCarRear();
                        else if (it.startsWith("🎨")) prefs.sp.edit().putString("car_hero", "drawing").apply();
                        else if (it.startsWith("📷") && has) {
                            if (CarPhoto.marks(prefs) == null) startActivity(new Intent(this, CarPhotoActivity.class)); // (not marked yet: mark first)
                            else prefs.sp.edit().putString("car_hero", "photo").apply();
                        }
                        else if (it.startsWith("📷") || it.startsWith("🖼️")) pickCarPhoto();
                        else if (it.startsWith("📍")) startActivity(new Intent(this, CarPhotoActivity.class));
                        else if (it.startsWith("🗑️")) {
                            if (!CarPhoto.file(this).delete()) CarPhoto.file(this).deleteOnExit();
                            prefs.sp.edit().putString("car_hero", "drawing").remove("car_photo_marks").apply();
                        }
                        show.run();
                    }).show();
        });
        // 🧩 the screen's shape (⑥ classic, ④ map + cards, ⑦ half map) and the map screen on a trip
        final TextView shapeRow = Ui.text(this, "", 15.5f, accent);
        shapeRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        Runnable showShape = () -> shapeRow.setText("🧩 స్క్రీన్ ఆకారం: " + CarLayout.name(prefs.carLayout()) + "  (మార్చడానికి నొక్కండి)");
        showShape.run();
        shapeRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు స్క్రీన్ ఆకారం")
                .setSingleChoiceItems(CarLayout.NAMES, Math.max(0, java.util.Arrays.asList(CarLayout.CHOICES).indexOf(CarLayout.valid(prefs.carLayout()))), (d, w) -> {
                    prefs.sp.edit().putString("car_layout", CarLayout.CHOICES[w]).apply();
                    showShape.run();
                    d.dismiss();
                    if (CarLayout.hasMap(CarLayout.CHOICES[w]) && !GMaps.has(this))
                        Toast.makeText(this, "మ్యాప్ ఇప్పుడు ఉచిత మ్యాప్‌తో వస్తుంది. Google మ్యాప్, ట్రాఫిక్ కోసం కింద 🗺️ Jarvis Maps లో Maps key పెట్టండి.", Toast.LENGTH_LONG).show();
                }).show());
        box.addView(shapeRow);
        Switch navMap = toggle("🗺️ Google Maps లో దారి మొదలైతే మ్యాప్ స్క్రీన్‌కి మారు (⑥ అయితే సగం మ్యాప్, ① ② అయితే ③ 3D మ్యాప్; మలుపు, చేరే టైమ్, స్పీడ్ ఒకే చోట; దారి అయిపోగానే మీ స్క్రీన్)", prefs.carNavMap());
        navMap.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("car_nav_map", v).apply());
        // 💾 నా థీమ్ 1–3, the six buttons, the dials, the buttons' look, sizes
        final TextView mineRow = Ui.text(this, "💾 నా థీమ్‌లు 1–3: ఇప్పటి రూపం దాచుకోండి, తర్వాత ఒక్క నొక్కుతో మళ్లీ పెట్టుకోండి", 15.5f, accent);
        final TextView buttonsRow = Ui.text(this, "", 15.5f, accent), styleRow = Ui.text(this, "", 15.5f, accent), dialsRow = Ui.text(this, "", 15.5f, accent);
        final TextView jarvisRow = Ui.text(this, "", 15.5f, accent), textRow = Ui.text(this, "", 15.5f, accent);
        final TextView mapColourRow = Ui.text(this, "", 15.5f, accent), zoomRow = Ui.text(this, "", 15.5f, accent);
        for (TextView t : new TextView[]{mineRow, buttonsRow, styleRow, dialsRow, jarvisRow, textRow, mapColourRow, zoomRow}) t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        Runnable showDeck = () -> {
            boolean deep = CarLayout.deep(prefs.carLayout());
            StringBuilder b = new StringBuilder();
            for (String k : CarDeck.six(prefs.carButtons(), deep)) b.append(b.length() == 0 ? "" : " · ").append(CarDeck.buttonLabel(k));
            buttonsRow.setText("🎛️ ఆరు బటన్లు: " + b + "  (మార్చడానికి నొక్కండి)");
            int si = Math.max(0, java.util.Arrays.asList(CarDeck.STYLES).indexOf(prefs.carBtnStyle()));
            styleRow.setText("🔘 బటన్లు ఎలా: " + CarDeck.STYLE_NAMES[si] + "  (మార్చడానికి నొక్కండి)");
            StringBuilder d = new StringBuilder();
            for (String k : CarDeck.three(prefs.carDials())) d.append(d.length() == 0 ? "" : " · ").append(CarDeck.dialName(k));
            dialsRow.setText("🔵 ② లో చిన్న డయల్స్: " + d + "  (మార్చడానికి నొక్కండి)");
            jarvisRow.setText("🧍 Jarvis సైజు: " + CarDeck.sizeName(prefs.carJarvis()) + "  (మార్చడానికి నొక్కండి)");
            textRow.setText("🔠 అక్షరాలు: " + CarDeck.sizeName(prefs.carText()) + "  (మార్చడానికి నొక్కండి)");
            int mi = Math.max(0, java.util.Arrays.asList(CarDeck.MAP_COLOURS).indexOf(prefs.carMapNight()));
            mapColourRow.setText("🌗 కారు మ్యాప్ రంగు: " + CarDeck.MAP_COLOUR_NAMES[mi] + "  (మార్చడానికి నొక్కండి)");
            int zi = Math.max(0, java.util.Arrays.asList(CarDeck.ZOOMS).indexOf(prefs.carMapZoom()));
            zoomRow.setText("🔍 కారు మ్యాప్ జూమ్: " + CarDeck.ZOOM_NAMES[zi] + "  (మార్చడానికి నొక్కండి)");
        };
        showDeck.run();
        box.addView(mineRow);
        box.addView(buttonsRow);
        box.addView(styleRow);
        box.addView(dialsRow);
        box.addView(jarvisRow);
        box.addView(textRow);
        buttonsRow.setOnClickListener(v -> {
            final String[] six = CarDeck.six(prefs.carButtons(), CarLayout.deep(prefs.carLayout()));
            String[] slots = new String[7];
            for (int i = 0; i < 6; i++) slots[i] = (i + 1) + "వ బటన్: " + CarDeck.buttonName(six[i]);
            slots[6] = "↺ మొదటి ఆరు బటన్లు (ఆకారానివి)";
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("ఏ స్థానంలో బటన్ మార్చాలి? (ఇంకో చోట ఉన్నది ఎంచుకుంటే రెండూ స్థానాలు మారతాయి)")
                    .setItems(slots, (d, slot) -> {
                        if (slot == 6) { prefs.sp.edit().remove("car_buttons").apply(); showDeck.run(); return; }
                        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle((slot + 1) + "వ బటన్")
                                .setSingleChoiceItems(CarDeck.BUTTON_NAMES, Math.max(0, CarDeck.buttonIndex(six[slot])), (d2, w) -> {
                                    prefs.sp.edit().putString("car_buttons", CarDeck.join(CarDeck.put(six, slot, CarDeck.BUTTONS[w]))).apply();
                                    showDeck.run();
                                    d2.dismiss();
                                }).show();
                    }).show();
        });
        styleRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("ఆరు బటన్లు ఎలా ఉండాలి?")
                .setSingleChoiceItems(CarDeck.STYLE_NAMES, Math.max(0, java.util.Arrays.asList(CarDeck.STYLES).indexOf(prefs.carBtnStyle())), (d, w) -> {
                    prefs.sp.edit().putString("car_btn_style", CarDeck.STYLES[w]).apply();
                    showDeck.run();
                    d.dismiss();
                }).show());
        dialsRow.setOnClickListener(v -> {
            final String[] three = CarDeck.three(prefs.carDials());
            String[] slots = new String[3];
            for (int i = 0; i < 3; i++) slots[i] = (i + 1) + "వ డయల్: " + CarDeck.dialName(three[i]);
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("② క్రోమ్ డయల్స్‌లో ఏ డయల్ మార్చాలి?")
                    .setItems(slots, (d, slot) -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle((slot + 1) + "వ డయల్‌లో ఏం చూపించాలి?")
                            .setSingleChoiceItems(CarDeck.DIAL_NAMES, Math.max(0, CarDeck.dialIndex(three[slot])), (d2, w) -> {
                                three[slot] = CarDeck.DIALS[w];
                                prefs.sp.edit().putString("car_dials", CarDeck.join(three)).apply();
                                showDeck.run();
                                d2.dismiss();
                            }).show()).show();
        });
        jarvisRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు స్క్రీన్ మీద Jarvis ఎంత పెద్దగా?")
                .setSingleChoiceItems(CarDeck.SIZE_NAMES, Math.max(0, java.util.Arrays.asList(CarDeck.SIZES).indexOf(prefs.carJarvis())), (d, w) -> {
                    prefs.sp.edit().putString("car_jarvis", CarDeck.SIZES[w]).apply();
                    showDeck.run();
                    d.dismiss();
                }).show());
        textRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు స్క్రీన్ అక్షరాల సైజు")
                .setSingleChoiceItems(CarDeck.SIZE_NAMES, Math.max(0, java.util.Arrays.asList(CarDeck.SIZES).indexOf(prefs.carText())), (d, w) -> {
                    prefs.sp.edit().putString("car_text", CarDeck.SIZES[w]).apply();
                    showDeck.run();
                    d.dismiss();
                }).show());
        mapColourRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు మ్యాప్ రంగు (Google మ్యాప్: Google ఇచ్చే ముదురు రంగు)")
                .setSingleChoiceItems(CarDeck.MAP_COLOUR_NAMES, Math.max(0, java.util.Arrays.asList(CarDeck.MAP_COLOURS).indexOf(prefs.carMapNight())), (d, w) -> {
                    prefs.sp.edit().putString("car_map_night", CarDeck.MAP_COLOURS[w]).apply();
                    showDeck.run();
                    d.dismiss();
                }).show());
        zoomRow.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("కారు మ్యాప్ జూమ్ (＋ － నొక్కితే ఒక నిమిషం మీదే)")
                .setSingleChoiceItems(CarDeck.ZOOM_NAMES, Math.max(0, java.util.Arrays.asList(CarDeck.ZOOMS).indexOf(prefs.carMapZoom())), (d, w) -> {
                    prefs.sp.edit().putString("car_map_zoom", CarDeck.ZOOMS[w]).apply();
                    showDeck.run();
                    d.dismiss();
                }).show());
        box.addView(theme);
        box.addView(heroRow);
        box.addView(colour);
        box.addView(accentRow);
        box.addView(gaugeRow);
        box.addView(wallRow);
        box.addView(dance);
        box.addView(mapColourRow);
        box.addView(zoomRow);
        Switch tilt = toggle("📐 ③ 3D మ్యాప్ డాష్‌లో మ్యాప్ వెనక్కి వంగి ఉండాలి (3D లాగా)", prefs.carMapTilt());
        tilt.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("car_map_tilt", v).apply());
        Switch low = toggle("🌙 కదలికలు తక్కువ (రింగులు, కారు బొమ్మ ఆగుతాయి, నడుపుతుండగా డాన్స్ లేదు: ఒంటరిగా రాత్రి డ్రైవ్‌కి)", prefs.carLowMotion());
        low.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("car_low_motion", v).apply());
        Switch saver = toggle("🕰️ ఆగి 5 నిమిషాలు ఎవరూ తాకకపోతే నల్లని స్క్రీన్‌సేవర్ గడియారం (తాకితే లేస్తుంది)", prefs.carSaver());
        saver.setOnCheckedChangeListener((sw, v) -> prefs.sp.edit().putBoolean("car_saver", v).apply());
        Switch home = toggle("🏠 Jarvis ని ఈ box హోమ్‌గా పెట్టు (Home బటన్ → Jarvis; మిగతా ఆప్స్: స్క్రీన్‌పై ▦)", CarApps.homeOn(this));
        home.setOnCheckedChangeListener((sw, v) -> {
            CarApps.setHome(this, v);
            if (!v) { Toast.makeText(this, "సరే: box పాత హోమ్ మళ్లీ", Toast.LENGTH_SHORT).show(); return; }
            Toast.makeText(this, "ఇప్పుడు వచ్చే లిస్ట్‌లో 'Jarvis కారు' ఎంచుకుని 'ఎప్పుడూ' నొక్కండి", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) {
                try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            }
        });
        // 💾 a kept look back: every look setting at once (the rows and switches here show it at once too)
        final Switch[] sws = {navMap, tilt, low};
        mineRow.setOnClickListener(v -> {
            String[] items = new String[CarDeck.MINE];
            for (int i = 0; i < CarDeck.MINE; i++) {
                String sum = CarDeck.summary(prefs.sp.getString("car_mine_" + (i + 1), ""));
                items[i] = "💾 నా థీమ్ " + (i + 1) + ": " + (sum.isEmpty() ? "ఖాళీ" : sum);
            }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("నా థీమ్‌లు")
                    .setItems(items, (d, w) -> mineSlot(w + 1, () -> {
                        show.run(); showShape.run(); showDeck.run();
                        sws[0].setChecked(prefs.carNavMap()); sws[1].setChecked(prefs.carMapTilt()); sws[2].setChecked(prefs.carLowMotion());
                    })).show();
        });
        note("కారు ఆగి ఉన్నప్పుడు కారు స్క్రీన్ మీద: ఒక బటన్‌ని కాసేపు నొక్కి పట్టుకుంటే దాని స్థానంలో వేరే బటన్; ② చిన్న డయల్‌ని పట్టుకుంటే అందులో ఏం చూపించాలో; "
                + "స్పీడ్ మీటర్‌ని పట్టుకుంటే దాని రూపం; Jarvis ని పట్టుకుంటే అతని సైజు. నడుపుతున్నప్పుడు ఇవి మారవు (భద్రత కోసం).");
        note("Jarvis బొమ్మ, చర్మం రంగు: కింద '🏠 ఇంటి Jarvis' భాగంలోని 🧑 Jarvis రూపం / 🎨 చర్మం రంగు ఎంపికలే కారుకీ. "
                + "పాట వస్తే Jarvis బీట్‌కి డాన్స్ చేస్తాడు (ప్రతి 16 బీట్లకి కొత్త స్టెప్, కోరస్‌కి పెద్ద స్టెప్పులు; బొమ్మను తాకితే ఒక ప్రత్యేక స్టెప్). "
                + "స్టీరింగ్ బటన్ నొక్కగానే ఆగి వింటాడు. బీట్ ఈ box ఇవ్వకపోతే మామూలు వేగంతో ఊగుతాడు. "
                + "మీ ఫోటో ఈ box లోనే ఉంటుంది (బ్యాకప్‌కి వెళ్లదు).");
    }

    /** 💾 నా థీమ్ n: put it on, keep the look now in it, or empty it. */
    private void mineSlot(int n, Runnable after) {
        final String key = "car_mine_" + n, kept = prefs.sp.getString(key, "");
        java.util.List<String> items = new java.util.ArrayList<>();
        if (!kept.isEmpty()) items.add("📥 ఈ థీమ్ పెట్టు (" + CarDeck.summary(kept) + ")");
        items.add("💾 ఇప్పటి రూపం ఇక్కడ దాచు");
        if (!kept.isEmpty()) items.add("🗑️ ఈ థీమ్ తీసేయి");
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("💾 నా థీమ్ " + n)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String it = items.get(w);
                    if (it.startsWith("📥")) {
                        java.util.Map<String, Object> m = CarDeck.back(kept);
                        if (m == null) { Toast.makeText(this, "ఈ థీమ్ చదవలేకపోయాను", Toast.LENGTH_SHORT).show(); return; }
                        android.content.SharedPreferences.Editor e = prefs.sp.edit();
                        for (java.util.Map.Entry<String, Object> x : m.entrySet()) {
                            Object val = x.getValue();
                            if (val == null) e.remove(x.getKey());
                            else if (val instanceof Integer) e.putInt(x.getKey(), (Integer) val);
                            else if (val instanceof Boolean) e.putBoolean(x.getKey(), (Boolean) val);
                            else e.putString(x.getKey(), (String) val);
                        }
                        e.apply();
                        Toast.makeText(this, "నా థీమ్ " + n + " పెట్టాను", Toast.LENGTH_SHORT).show();
                    } else if (it.startsWith("💾")) {
                        prefs.sp.edit().putString(key, CarDeck.keep(prefs.sp.getAll())).apply();
                        Toast.makeText(this, "ఇప్పటి రూపం 'నా థీమ్ " + n + "' లో దాచాను", Toast.LENGTH_SHORT).show();
                    } else {
                        prefs.sp.edit().remove(key).apply();
                    }
                    after.run();
                }).show();
    }

    // the theme editor's choices
    private static final int[] ACCENTS = {0, 0xFF5FF3FF, 0xFF4D8DFF, 0xFF34E89E, 0xFFFFC24B, 0xFFFF8A3D, 0xFFFF5A5A, 0xFFFF6AD5, 0xFFB28DFF, 0xFFF2F6FA};
    private static final String[] ACCENT_NAMES = {"థీమ్‌దే", "సియాన్", "నీలం", "ఆకుపచ్చ", "బంగారు", "నారింజ", "ఎరుపు", "గులాబీ", "ఊదా", "తెలుపు"};
    private static final String[] GAUGES = {"", "arc", "needle", "digits"},
            GAUGE_NAMES = {"థీమ్‌దే (①②③ లో ఆకారానిదే)", "వెలిగే వంపు (ఆర్క్)", "క్లాసిక్ సూది డయల్", "పెద్ద అంకెలు మాత్రమే"};
    private static final int REQ_WALL = 47, REQ_CARPHOTO = 48, REQ_CARREAR = 49;

    private void pickCarRear() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_CARREAR);
        } catch (Exception e) {
            Toast.makeText(this, "ఫోటో ఎంచుకునే యాప్ లేదు", Toast.LENGTH_LONG).show();
        }
    }
    private TextView heroRow;

    private void pickCarPhoto() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_CARPHOTO);
        } catch (Exception e) {
            Toast.makeText(this, "ఫోటో ఎంచుకునే యాప్ లేదు", Toast.LENGTH_LONG).show();
        }
    }
    private TextView wallRow;
    private Runnable wallShow;

    private void pickWall() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_WALL);
        } catch (Exception e) {
            Toast.makeText(this, "ఫోటో ఎంచుకునే యాప్ లేదు", Toast.LENGTH_LONG).show();
        }
    }

    /** The photo he picked: made at most 1600 px and kept only in this app's own folder on this box (never in a backup). */
    private void wallPicked(Uri uri) {
        final Uri u = uri;
        new Thread(() -> {
            final boolean done = CarPhoto.keep(this, u, "car_wall.jpg", 1600); // (upright, ≤1600 px, only on this box)
            runOnUiThread(() -> {
                Toast.makeText(this, done ? "సరే: కారు స్క్రీన్ వెనుక మీ ఫోటో" : "ఆ ఫోటో తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
                if (wallShow != null) wallShow.run();
            });
        }, "car-wall").start();
    }

    /** 🚗 The car sleep alert (Anil, 18 Oct): the car only (its Bluetooth or "కారు మోడ్"), never the bike. */
    private void carSleepSection() {
        section("🚗 కారు నిద్ర అలర్ట్", "🚗");
        note("కారులో మాత్రమే (బైక్‌లో కాదు): కారు బ్లూటూత్ కనెక్ట్ అయినప్పుడు, లేదా 'Jarvis, కారు మోడ్' అన్నప్పుడు. కింద ఎంచుకున్న సమయాల్లో "
                + "'మెలకువగా ఉన్నారా?' అని ఫోన్ గొంతులో అడుగుతుంది: జవాబుగా 'Jarvis' అని పిలవండి, లేదా నోటిఫికేషన్ / వాచ్‌లో ✅ నొక్కండి. "
                + "జవాబు రాకపోతే గట్టిగా అలారం, వాచ్ వైబ్రేషన్, 'కారు ఆపండి'. నిద్ర వస్తే 'Jarvis, నిద్ర వస్తోంది' అనండి. "
                + "('Jarvis' వినాలంటే పైన వేక్ వర్డ్ 'ఎప్పుడూ' లో ఉండాలి; వాచ్ ✅, అలారానికి ⌚ వాచ్ → '🔔 Jarvis అలర్ట్స్' ఆన్‌లో ఉండాలి.)");
        final android.content.SharedPreferences ds = CarCare.settings(this);
        Switch on = toggle("కారు నిద్ర అలర్ట్ ఆన్", CarCare.enabled(this));
        on.setOnCheckedChangeListener((sw, v) -> ds.edit().putBoolean("drive_fatigue", v).apply());
        if (!prefs.carMode()) { // his phone: the car has its own Jarvis (the box) — then the phone stays quiet in the car
            Switch quiet = toggle("🚗 కారులో box Jarvis ఉంది: కారు మోడ్‌లో ఫోన్ \"Jarvis\" వినదు, నిద్ర ప్రశ్నలు అడగదు (box అడుగుతుంది)", prefs.boxInCar());
            quiet.setOnCheckedChangeListener((sw, v) -> { prefs.sp.edit().putBoolean("box_in_car", v).apply(); WakeService.recheck(this); });
        }
        TextView w = Ui.text(this, "ఎప్పుడు అడగాలి?", 15, Ui.MUTED);
        w.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(w);
        String[][] when = {{"car_night", "🌙 రాత్రి 10 నుంచి ఉదయం 5 వరకు"}, {"car_duty", "🕘 డ్యూటీ తర్వాత నిద్రపోకుండా నడిపితే"},
                {"car_sleep", "😴 గత 24 గంటల్లో నిద్ర 5 గంటల కంటే తక్కువైతే"}, {"car_long", "⏱️ 2 గంటలకు మించి ఆపకుండా నడిపితే"}};
        for (String[] k : when) {
            Switch s = toggle(k[1], ds.getBoolean(k[0], true));
            s.setOnCheckedChangeListener((sw, v) -> ds.edit().putBoolean(k[0], v).apply());
        }
        TextView ev = Ui.text(this, "ఎన్ని నిమిషాలకి ఒకసారి అడగాలి?", 15, Ui.MUTED);
        ev.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(ev);
        RadioGroup every = new RadioGroup(this);
        every.setOrientation(LinearLayout.HORIZONTAL);
        every.addView(radio(131, "10"));
        every.addView(radio(132, "15"));
        every.addView(radio(133, "20"));
        int e = CarCare.every(this);
        every.check(e <= 10 ? 131 : e >= 20 ? 133 : 132);
        every.setOnCheckedChangeListener((g, id) -> ds.edit().putInt("car_every", id == 131 ? 10 : id == 133 ? 20 : 15).apply());
        box.addView(every);
        TextView ey = Ui.text(this, "👁️ కళ్ళ చెక్ (కెమెరా మీ కళ్ళు చూస్తుంది; ఫోన్‌లోనే, ఏదీ సేవ్ / పంపదు)", 15, Ui.MUTED);
        ey.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(ey);
        RadioGroup eyes = new RadioGroup(this);
        eyes.addView(radio(134, "పైన ఎంచుకున్న సమయాల్లో మాత్రమే (సిఫార్సు)"));
        eyes.addView(radio(135, "కారులో ఎప్పుడూ"));
        eyes.addView(radio(136, "వద్దు"));
        String em = CarCare.eyesMode(this);
        eyes.check("always".equals(em) ? 135 : "off".equals(em) ? 136 : 134);
        eyes.setOnCheckedChangeListener((g, id) -> ds.edit().putString("car_eyes", id == 135 ? "always" : id == 136 ? "off" : "night").apply());
        box.addView(eyes);
        TextView src = Ui.text(this, "కెమెరా ఏది?", 15, Ui.MUTED);
        src.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(src);
        boolean carBox = prefs.carMode();
        RadioGroup cams = new RadioGroup(this);
        cams.addView(radio(137, carBox ? "📱 ముందు కెమెరా (box కి ఉండదు: ఫోన్‌లో మాత్రమే — ఫోన్ హోల్డర్‌లో)"
                : "📱 ఫోన్ ముందు కెమెరా — ఫోన్ హోల్డర్‌లో మీ ముఖం వైపు (పగలు బాగా; చీకట్లో కళ్ళు కనబడవు)"));
        cams.addView(radio(138, "🔌 బయటి USB కెమెరా (" + (carBox ? "box" : "ఫోన్") + " దాన్ని కెమెరాగా ఇస్తేనే)"));
        cams.addView(radio(139, "📶 Wi-Fi కెమెరా (night vision కెమెరా: MJPEG / JPEG లేదా RTSP లింక్)"));
        String cs = CarCare.eyesSource(this);
        cams.check("usb".equals(cs) ? 138 : "url".equals(cs) ? 139 : 137);
        cams.setOnCheckedChangeListener((g, id) -> ds.edit().putString("car_eyes_src", id == 138 ? "usb" : id == 139 ? "url" : "front").apply());
        box.addView(cams);
        // (the camera's link, login and Wi-Fi: kept in a file no backup takes — they stay on this device)
        camField("📶 Wi-Fi కెమెరా లింక్ (ఉదా: rtsp://192.168.1.254/live లేదా http://192.168.1.254:8192)", CarCare.eyesUrl(this), false,
                v -> CarCare.setEyesUrl(this, v));
        camField("👤 కెమెరా user (అడిగితేనే; లేకపోతే ఖాళీ)", CarCare.camUser(this), false, v -> CarCare.setCam(this, "user", v.trim()));
        camField("🔑 కెమెరా password (అడిగితేనే)", CarCare.camPass(this), true, v -> CarCare.setCam(this, "pass", v));
        camField("📡 కెమెరా తన Wi-Fi ఇస్తే దాని పేరు (ఈ " + (carBox ? "box" : "ఫోన్") + " ఇప్పటికే ఆ Wi-Fi లో ఉంటే ఖాళీ)", CarCare.camSsid(this), false,
                v -> CarCare.setCam(this, "ssid", v.trim()));
        camField("🔑 కెమెరా Wi-Fi password", CarCare.camWifiPass(this), true, v -> CarCare.setCam(this, "wifi_pass", v));
        note("ఈ లింక్, user, password లు ఈ " + (carBox ? "box" : "ఫోన్") + " లోనే ఉంటాయి: ఏ backup కీ, ఎక్కడికీ వెళ్ళవు. వీటిని chat లో / స్క్రీన్‌షాట్‌లో పంపకండి.");
        TextView two = Ui.text(this, twoWifiLine(carBox) + (CarCare.camSsid(this).isEmpty() ? ""
                : CarCare.camApproved(this) ? "\n📶 కెమెరా Wi-Fi కి Android ఒప్పుకుంది ✓ (ఇక అడగకుండా తనే కలుస్తుంది)"
                : "\n📶 కెమెరా Wi-Fi: కారు ఆపి ఒకసారి '▶ కళ్ళ చెక్ టెస్ట్' చేయాలి (Android అడిగితే 'కనెక్ట్')"), 14, Ui.TEXT);
        two.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(two);
        if (carBox) note("box లో SIM లేనప్పుడు box internet ఫోన్ hotspot నుంచే. ✅ అయితే కెమెరా Wi-Fi తో పాటు hotspot కూడా ఉంటుంది: ఏదీ ఆగదు. "
                + "⚠️ అయితే కళ్ళ చెక్ నడిచినంత సేపు box కెమెరా Wi-Fi లోనే ఉంటుంది: అప్పుడు AI జవాబులు, 📮 ఫోన్ లింక్, ముందు వాతావరణం ఆగుతాయి; "
                + "నిద్ర ప్రశ్నలు ('Jarvis' అని జవాబు), అలారం, కళ్ళ చెక్ internet లేకుండానే పనిచేస్తాయి. కళ్ళ చెక్ ఆగగానే box మళ్ళీ hotspot కి వస్తుంది. "
                + "SIM వేశాక ఈ ఇబ్బంది ఉండదు.");
        note("రాత్రి చీకట్లో ఫోన్ కెమెరాకి కళ్ళు కనబడవు; అప్పుడు మాటల చెక్ పనిచేస్తుంది. రాత్రికి IR (night vision) Wi-Fi కెమెరా పెడితే ఇదే కళ్ళ చెక్ పనిచేస్తుంది. "
                + "రెండూ కావాలంటే: ఫోన్‌లో '📱 ముందు కెమెరా' (హోల్డర్‌లో, 'కారులో ఎప్పుడూ'), box లో '📶 Wi-Fi కెమెరా'. "
                + "కెమెరా కొన్నాక / కొనే ముందు కింది టెస్ట్‌తో ఆ కెమెరా బొమ్మ ఇక్కడ కనబడుతుందో చూద్దాం (దాని యాప్‌లో RTSP / MJPEG లింక్ ఉండాలి). "
                + "మొదటి టెస్ట్‌లో Android 'కెమెరా Wi-Fi కి కలవాలా?' అని అడుగుతుంది: ఆ పేరు ఎంచుకుని 'కనెక్ట్' నొక్కండి; తర్వాత అడగదు.");
        button("▶ కళ్ళ చెక్ టెస్ట్", v -> startActivity(new Intent(this, CarEyesActivity.class)));
        button("🔔 అలారం శబ్దం టెస్ట్ (2 సెకన్లు)", v -> CarCare.testTone(this));
        String st = CarCare.statusWords(this);
        TextView now = Ui.text(this, st.isEmpty() ? "ఇప్పుడు కారు మోడ్ ఆఫ్." : st, 14, Ui.MUTED);
        now.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(now);
    }

    /** A Wi-Fi camera box: saved as he types (secret: shown as dots). */
    private void camField(String label, String value, boolean secret, java.util.function.Consumer<String> save) {
        EditText e = field(label, value, secret);
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { save.accept(s.toString()); }
        });
    }

    /** Can this device keep its own Wi-Fi (its internet) and join the camera's Wi-Fi at the same time? */
    private String twoWifiLine(boolean carBox) {
        if (Build.VERSION.SDK_INT < 31) return "📶 రెండు Wi-Fi లు ఒకేసారి: తెలియదు (Android 12 కంటే పాతది) — టెస్ట్‌లో చూద్దాం";
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return "📶 రెండు Wi-Fi లు ఒకేసారి: తెలియదు";
            String s = wm.isStaConcurrencyForLocalOnlyConnectionsSupported()
                    ? "📶 ఈ " + (carBox ? "box" : "ఫోన్") + " రెండు Wi-Fi లు ఒకేసారి పెట్టగలదు ✅ (కెమెరా Wi-Fi తో పాటు internet Wi-Fi కూడా ఉంటుంది)"
                    : "📶 ఈ " + (carBox ? "box" : "ఫోన్") + " ఒకసారి ఒక Wi-Fi కే కలుస్తుంది ⚠️ (కెమెరా Wi-Fi లో ఉన్నంత సేపు Wi-Fi internet ఉండదు)";
            if (!carBox) s += wm.isStaApConcurrencySupported() ? "\n📶 hotspot ఆన్‌లో ఉండగానే కెమెరా Wi-Fi కి కలవగలదు ✅"
                    : "\n📶 hotspot ఆన్‌లో ఉండగా కెమెరా Wi-Fi కి కలవలేకపోవచ్చు ⚠️";
            return s;
        } catch (Exception e) {
            return "📶 రెండు Wi-Fi లు ఒకేసారి: తెలియదు";
        }
    }

    /** "🏍️ బైక్: <name> ✓" (the paired device's name when Android lets Jarvis see it). */
    private String btLine(String what, String address) {
        if (address == null || address.isEmpty()) return what + ": ఇంకా ఎంచుకోలేదు";
        String name = null;
        try {
            if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                android.bluetooth.BluetoothManager bm = getSystemService(android.bluetooth.BluetoothManager.class);
                android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad != null) for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) if (address.equalsIgnoreCase(d.getAddress())) name = d.getName();
            }
        } catch (Exception ignored) {}
        return what + ": " + (name == null ? "ఎంచుకున్నారు" : name) + " ✓";
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void chooseCar(boolean car) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 9);
            Toast.makeText(this, "Allow చేసి మళ్లీ నొక్కండి", Toast.LENGTH_SHORT).show();
            return;
        }
        android.bluetooth.BluetoothManager bm = getSystemService(android.bluetooth.BluetoothManager.class);
        android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { Toast.makeText(this, "ఈ ఫోన్‌లో బ్లూటూత్ లేదు", Toast.LENGTH_LONG).show(); return; }
        java.util.List<android.bluetooth.BluetoothDevice> list = new java.util.ArrayList<>(ad.getBondedDevices());
        if (list.isEmpty()) { Toast.makeText(this, "ముందు కార్/బైక్‌ని ఫోన్‌తో బ్లూటూత్ జత (pair) చేయండి", Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName() == null ? list.get(i).getAddress() : list.get(i).getName();
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(car ? "మీ కారు బ్లూటూత్ ఏది?" : "మీ బైక్ బ్లూటూత్ ఏది?")
                .setItems(names, (d, which) -> {
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString(car ? "car2_bt" : "car_bt", list.get(which).getAddress()).apply();
                    if (car) car2Info.setText("🚗 కారు: " + names[which] + " ✓"); else carInfo.setText("🏍️ బైక్: " + names[which] + " ✓");
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private void showListenWindow() {
        if (listenWindowLabel != null) listenWindowLabel.setText("పిలిచాక మీరు మాట్లాడటం మొదలుపెట్టే దాకా వినే సమయం: " + (listenWindow.getProgress() + 3)
                + " సెకన్లు (Live లో కూడా: ఇంతసేపు ఎవరూ మాట్లాడకపోతే Live ఆగిపోతుంది)");
    }

    private void showLock() {
        if (lockInfo == null || lockSlider == null) return;
        float max = 0.30f + lockSlider.getProgress() / 100f;
        StringBuilder b = new StringBuilder();
        b.append(VoiceLock.print(this) == null ? "గొంతు ఇంకా నేర్పించలేదు." : "గొంతు నేర్చుకున్నాను ✓");
        b.append("  పరిమితి: ").append(String.format(Locale.ROOT, "%.2f", max)).append(" (ఎడమ = కఠినం, కుడి = సులభం)");
        if (VoiceLock.lastDistance >= 0) {
            b.append("\nచివరి పిలుపు దూరం: ").append(String.format(Locale.ROOT, "%.2f", VoiceLock.lastDistance))
                    .append(VoiceLock.lastAccepted ? " → పలికాను" : " → మీ గొంతు కాదనుకుని పలకలేదు");
        }
        lockInfo.setText(b.toString());
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (gated) return;
        if (code == REQ_WALL && result == RESULT_OK && data != null && data.getData() != null) wallPicked(data.getData()); // 🖼️ the car screen's wallpaper
        if (code == REQ_CARREAR && result == RESULT_OK && data != null && data.getData() != null) { // 🚗 his car from behind: kept, then its lights marked
            final Uri u = data.getData();
            Toast.makeText(this, "ఫోటో తీసుకుంటున్నాను…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean ok = CarPhoto.keep(this, u, CarPhoto.REAR, 1400);
                runOnUiThread(() -> {
                    if (!ok) { Toast.makeText(this, "ఆ ఫోటో తెరవలేకపోయాను", Toast.LENGTH_LONG).show(); return; }
                    prefs.sp.edit().remove("car_rear_marks").apply();
                    startActivity(new Intent(this, CarPhotoActivity.class).putExtra(CarPhotoActivity.EXTRA_REAR, true));
                });
            }, "car-rear").start();
        }
        if (code == REQ_CARPHOTO && result == RESULT_OK && data != null && data.getData() != null) { // 🚙 his own car's photo: kept, then marked
            final Uri u = data.getData();
            Toast.makeText(this, "ఫోటో తీసుకుంటున్నాను…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean ok = CarPhoto.keep(this, u, CarPhoto.FILE, 1600);
                runOnUiThread(() -> {
                    if (!ok) { Toast.makeText(this, "ఆ ఫోటో తెరవలేకపోయాను", Toast.LENGTH_LONG).show(); return; }
                    prefs.sp.edit().remove("car_photo_marks").apply(); // (a new photo: new marks)
                    startActivity(new Intent(this, CarPhotoActivity.class));
                });
            }, "car-photo").start();
        }
        if (code == REQ_FRAME && result == RESULT_OK && data != null) { // photos for the tablet's frame
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) sendFramePhotos(uris);
        }
        if (code == 42 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("wa_tree", data.getData().toString()).apply();
                waInfo.setText("అనుమతి ఉంది ✓");
            } catch (Exception e) {
                Toast.makeText(this, "ఆ ఫోల్డర్‌కి అనుమతి రాలేదు", Toast.LENGTH_LONG).show();
            }
        }
        if (code == 43 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String name = "పాట";
                try (android.database.Cursor cur = getContentResolver().query(data.getData(), new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) name = cur.getString(0);
                } catch (Exception ignored) {}
                prefs.sp.edit().putString("alarm_song", data.getData().toString()).putString("alarm_song_name", name).apply();
                alarmSongInfo.setText("🎵 అలారం పాట: " + name + " ✓");
            } catch (Exception ex) {
                Toast.makeText(this, "ఆ పాటకి అనుమతి రాలేదు", Toast.LENGTH_LONG).show();
            }
        }
        if (code == 44 && result == RESULT_OK && data != null && data.getData() != null) backupFilePicked(data.getData(), data.getFlags());
        if (code == 45 && result == RESULT_OK && data != null && data.getData() != null) restoreFilePicked(data.getData(), data.getFlags(), null);
        if (code == 41 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("docs_tree", data.getData().toString()).apply();
                docsInfo.setText("ఎంచుకున్నారు ✓");
            } catch (Exception e) {
                Toast.makeText(this, "ఆ ఫోల్డర్‌కి అనుమతి రాలేదు", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- the floating button's options and hidden apps

    private void chooseBubbleOptions() {
        Object[][] all = FloatBubble.choosable();
        String[] names = new String[all.length];
        boolean[] on = new boolean[all.length];
        java.util.Set<String> off = prefs.bubbleOff();
        for (int i = 0; i < all.length; i++) {
            names[i] = (String) all[i][1];
            on[i] = !off.contains(String.valueOf(all[i][0]));
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("మెనూలో ఉండాల్సిన ఆప్షన్లు")
                .setMultiChoiceItems(names, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("సరే", (d, w) -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < all.length; i++) if (!on[i]) b.append(b.length() == 0 ? "" : ",").append(all[i][0]);
                    prefs.sp.edit().putString("bubble_off", b.toString()).apply();
                    Toast.makeText(this, "సేవ్ అయింది ✓", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private void chooseHiddenApps() {
        java.util.List<String> apps = new java.util.ArrayList<>(prefs.bubbleHiddenApps());
        if (apps.isEmpty()) {
            Toast.makeText(this, "ఏ యాప్‌లోనూ దాచలేదు. ఆ యాప్‌లో బటన్ నొక్కి '🙈 ఈ యాప్‌లో దాచు' అంటే దాక్కుంటుంది.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[apps.size()];
        boolean[] keep = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) { names[i] = JarvisAccessibility.label(this, apps.get(i)); keep[i] = true; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ఈ యాప్‌లలో బటన్ దాగి ఉంది (టిక్ తీస్తే మళ్లీ కనిపిస్తుంది)")
                .setMultiChoiceItems(names, keep, (d, w, checked) -> keep[w] = checked)
                .setPositiveButton("సరే", (d, w) -> {
                    java.util.Set<String> left = new java.util.HashSet<>();
                    for (int i = 0; i < apps.size(); i++) if (keep[i]) left.add(apps.get(i));
                    prefs.setBubbleHiddenApps(left);
                    Toast.makeText(this, "సేవ్ అయింది ✓", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    // ---------- backup & restore (his Google Drive, his password)

    /** An opened backup waiting for his "yes" (the unpacked copy is let go if this screen goes away). */
    private Backup.Opened pendingRestore;

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private android.app.AlertDialog.Builder dialog() {
        return new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private EditText passwordBox(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private LinearLayout dialogBox(View... views) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        l.setPadding(p, Ui.dp(this, 8), p, 0);
        for (View v : views) l.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    private static char[] chars(EditText e) {
        android.text.Editable t = e.getText();
        char[] c = new char[t.length()];
        t.getChars(0, t.length(), c, 0);
        return c;
    }

    private void showBackup(String text) {
        runOnUiThread(() -> { if (alive() && backupInfo != null) backupInfo.setText(text); });
    }

    private boolean backupBusy() {
        if (!Backup.busy()) return false;
        Toast.makeText(this, "బ్యాకప్ పని ఒకటి జరుగుతోంది: అది అయ్యాక నొక్కండి", Toast.LENGTH_LONG).show();
        return true;
    }

    /** "బ్యాకప్ సెట్ చేయి": first the Drive file (so nothing is lost if the screen turns meanwhile), then his password. */
    private void startBackupSetup() {
        if (backupBusy()) return;
        dialog().setTitle("🔐 బ్యాకప్ సెట్ చేయడం")
                .setMessage("1. తర్వాత వచ్చే స్క్రీన్‌లో పైన ఎడమవైపు ☰ నొక్కి \"Drive\" ఎంచుకుని, Save నొక్కండి.\n"
                        + "2. తర్వాత బ్యాకప్‌కి ఒక పాస్‌వర్డ్ పెట్టండి. కొత్త ఫోన్‌లో అదే పాస్‌వర్డ్‌తో తెరవాలి: మర్చిపోతే ఎవరూ తెరవలేరు, రాసి పెట్టుకోండి.")
                .setPositiveButton("సరే", (x, w) -> pickBackupFile())
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private void pickBackupFile() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "Jarvis-backup.jarvis"), 44);
        } catch (Exception e) {
            Toast.makeText(this, "ఫైల్ ఎంచుకునే స్క్రీన్ తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
        }
    }

    private static String fileName(android.content.Context c, Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignored) {}
        return "Jarvis-backup.jarvis";
    }

    private void backupFilePicked(Uri uri, int grantFlags) {
        int keep = grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, keep); } catch (Exception ignored) {}
        if (!Backup.inDrive(uri)) { // the phone itself: lost with the phone
            dialog().setTitle("ఇది Google Drive కాదు")
                    .setMessage("ఈ ఫైల్ ఫోన్‌లోనే ఉంటుంది: ఫోన్ పోతే బ్యాకప్ కూడా పోతుంది. మళ్లీ ఎంచుకునేటప్పుడు ☰ నొక్కి \"Drive\" ఎంచుకోండి.")
                    .setPositiveButton("Drive ఎంచుకుంటాను", (x, w) -> {
                        try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) {}
                        pickBackupFile();
                    })
                    .setNegativeButton("అయినా ఇక్కడే", (x, w) -> askNewPassword(uri))
                    .show();
            return;
        }
        askNewPassword(uri);
    }

    private void askNewPassword(Uri uri) {
        if (!alive()) return;
        EditText one = passwordBox("పాస్‌వర్డ్ (కనీసం 8 అక్షరాలు)"), two = passwordBox("మళ్లీ అదే పాస్‌వర్డ్");
        android.app.AlertDialog d = dialog()
                .setTitle("🔐 బ్యాకప్ పాస్‌వర్డ్")
                .setMessage("కొత్త ఫోన్‌లో ఈ పాస్‌వర్డ్‌తోనే బ్యాకప్ తెరవాలి. మర్చిపోతే ఎవరూ తెరవలేరు: రాసి పెట్టుకోండి.")
                .setView(dialogBox(one, two))
                .setPositiveButton("సెట్ చేయి", null)
                .setNegativeButton("వద్దు", null)
                .create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] a = chars(one), b = chars(two);
            boolean same = java.util.Arrays.equals(a, b);
            java.util.Arrays.fill(b, '\0');
            if (a.length < 8) { java.util.Arrays.fill(a, '\0'); one.setError("కనీసం 8 అక్షరాలు"); return; }
            if (!same) { java.util.Arrays.fill(a, '\0'); two.setError("రెండూ ఒకటి కాదు"); return; }
            one.setText("");
            two.setText("");
            d.dismiss();
            setUpBackup(uri, a);
        }));
        d.show();
    }

    /** Key from his password, kept locked on the phone; then the first backup at once. */
    private void setUpBackup(Uri uri, char[] pw) {
        showBackup("బ్యాకప్ సెట్ చేస్తున్నాను… (పాస్‌వర్డ్‌ నుంచి తాళం చెవి తయారవడానికి కొన్ని సెకన్లు పడుతుంది)");
        String where = (Backup.inDrive(uri) ? "Google Drive → " : "ఫోన్ → ") + fileName(this, uri);
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            byte[] key = null;
            try {
                byte[] salt = Backup.salt();
                key = Backup.derive(pw, salt, Backup.iterations());
                Backup.remember(app, key, salt, Backup.iterations(), uri, where, "");
                BackupJob.schedule(app);
                runOnUiThread(() -> { if (alive() && backupSetBtn != null) backupSetBtn.setText("🔐 పాస్‌వర్డ్ / Drive ఫైల్ మార్చు"); });
                Backup.runWhenFree(app, this::showBackup);
                showBackup(Backup.status(app));
            } catch (Exception e) {
                showBackup("✗ బ్యాకప్ కాలేదు: " + e.getMessage() + "\n" + Backup.status(app));
            } finally {
                java.util.Arrays.fill(pw, '\0');
                if (key != null) java.util.Arrays.fill(key, (byte) 0);
            }
        }, "jarvis-backup-setup").start();
    }

    private void backupNow() {
        if (!Backup.configured(this)) { Toast.makeText(this, "ముందు \"బ్యాకప్ సెట్ చేయి\" నొక్కండి", Toast.LENGTH_LONG).show(); return; }
        if (backupBusy()) return;
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (!Backup.run(app, this::showBackup)) { showBackup("బ్యాకప్ ఇప్పటికే జరుగుతోంది, ఒక్క నిమిషం ఆగండి…"); return; }
                showBackup(Backup.status(app));
            } catch (Exception e) {
                showBackup("✗ బ్యాకప్ కాలేదు: " + e.getMessage() + "\n" + Backup.status(app));
            }
        }, "jarvis-backup-now").start();
    }

    private void pickRestore() {
        if (backupBusy()) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 45);
            Toast.makeText(this, "☰ నొక్కి Drive ఎంచుకుని, Jarvis-backup ఫైల్ నొక్కండి", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "ఫైల్ ఎంచుకునే స్క్రీన్ తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
        }
    }

    /** The backup file to bring back: his password, then it is opened and checked in full before anything changes. */
    private void restoreFilePicked(Uri uri, int grantFlags, String again) {
        if (!alive()) return;
        EditText pw = passwordBox("బ్యాకప్ పాస్‌వర్డ్");
        android.app.AlertDialog d = dialog()
                .setTitle("♻️ బ్యాకప్ తెరవడం")
                .setMessage((again == null ? "" : again + "\n\n") + "బ్యాకప్ సెట్ చేసినప్పుడు పెట్టిన పాస్‌వర్డ్ ఇవ్వండి.")
                .setView(dialogBox(pw))
                .setPositiveButton("తెరువు", null)
                .setNegativeButton("వద్దు", null)
                .create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] p = chars(pw);
            if (p.length == 0) { pw.setError("పాస్‌వర్డ్ ఇవ్వండి"); return; }
            pw.setText("");
            d.dismiss();
            openBackup(uri, grantFlags, p);
        }));
        d.show();
    }

    private void openBackup(Uri uri, int grantFlags, char[] pw) {
        if (backupBusy()) { java.util.Arrays.fill(pw, '\0'); return; }
        showBackup("బ్యాకప్ తెరుస్తున్నాను… (పాస్‌వర్డ్ చెక్‌కి కొన్ని సెకన్లు పడుతుంది)");
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Backup.Opened o = Backup.open(app, uri, pw, this::showBackup);
                runOnUiThread(() -> confirmRestore(uri, grantFlags, o));
            } catch (Backup.WrongPassword e) {
                showBackup(Backup.status(app));
                runOnUiThread(() -> restoreFilePicked(uri, grantFlags, "✗ ఈ పాస్‌వర్డ్‌తో తెరుచుకోలేదు (పాస్‌వర్డ్ తప్పు, లేదా ఫైల్ పాడైంది)."));
            } catch (Exception e) {
                showBackup("✗ బ్యాకప్ తెరవలేకపోయాను: " + e.getMessage() + "\n" + Backup.status(app));
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        }, "jarvis-restore-open").start();
    }

    private void confirmRestore(Uri uri, int grantFlags, Backup.Opened o) {
        if (!alive()) { Backup.discard(getApplicationContext(), o); return; }
        pendingRestore = o;
        long made = o.meta.optLong("made");
        dialog().setTitle("♻️ ఈ బ్యాకప్ తిరిగి తెమ్మంటారా?")
                .setMessage("బ్యాకప్: " + (made > 0 ? Backup.when(made) : "?") + " · " + o.meta.optString("phone") + " · " + Backup.size(o.bytes)
                        + "\n\nఇప్పుడు ఈ ఫోన్‌లో ఉన్న Jarvis డేటా అంతా ఈ బ్యాకప్‌తో మారిపోతుంది. Jarvis వెంటనే మళ్లీ మొదలవుతుంది, "
                        + "మొదలయ్యేటప్పుడే బ్యాకప్ లోపలికి వస్తుంది.")
                .setCancelable(false)
                .setPositiveButton("తిరిగి తెచ్చు", (x, w) -> applyRestore(uri, grantFlags, o))
                .setNegativeButton("వద్దు", (x, w) -> {
                    pendingRestore = null;
                    Backup.discard(getApplicationContext(), o);
                    showBackup(Backup.status(this));
                })
                .show();
    }

    private void applyRestore(Uri uri, int grantFlags, Backup.Opened o) {
        pendingRestore = null;
        showBackup("తిరిగి తెస్తున్నాను…");
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                // the same Drive file goes on as this phone's backup when Android let Jarvis write to it (taken over only
                // once the restore is in, at the next start); the old phone stops writing to it after that
                boolean canWrite = (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
                int keep = grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(uri, keep); } catch (Exception e) { canWrite = false; }
                String where = (Backup.inDrive(uri) ? "Google Drive → " : "ఫోన్ → ") + fileName(app, uri);
                try {
                    Backup.markReady(app, o, uri, where, canWrite);
                } catch (Exception e) {
                    if (!canWrite) throw e;
                    Backup.markReady(app, o, uri, where, false); // the phone keystore failed: restore anyway, backup set up again later
                }
                runOnUiThread(this::restartJarvis);
            } catch (Exception e) {
                Backup.discard(app, o);
                showBackup("✗ తిరిగి తేవడం కుదరలేదు: " + e.getMessage() + ". మళ్లీ ప్రయత్నించండి (ఫోన్‌లో ఉన్నది ఏమీ మారలేదు).");
            }
        }, "jarvis-restore").start();
    }

    /** The backup goes in at Jarvis's next start (before anything reads the old data): start it fresh now. */
    private void restartJarvis() {
        try {
            startActivity(Intent.makeRestartActivityTask(new android.content.ComponentName(this, MainActivity.class)));
        } catch (Exception ignored) {}
        Runtime.getRuntime().exit(0);
    }

    private static final String[][] LOOKS = {
            {"మీరు", "👤"}, {"థీమ్", "🎨"}, {"Jarvis మెదడు", "🧠"}, {"కోడింగ్", "💻"}, {"వాయిస్", "🔊"}, {"సహజ గొంతు", "🗣️"},
            {"Live", "🎙️"}, {"వేక్ వర్డ్", "👂"}, {"కాల్స్", "📞"}, {"స్క్రీన్", "📱"}, {"పవర్ బటన్", "🔘"},
            {"మెసేజ్", "💬"}, {"తనంతట", "✨"}, {"స్మార్ట్ హోమ్", "🏠"}, {"కాపలా", "🛡️"}, {"అత్యవసరం", "🆘"}, {"టికెట్", "🎟️"},
            {"WhatsApp", "🖼️"}, {"డాక్యుమెంట్", "📄"}, {"కార్", "🏍️"}, {"ఆరోగ్యం", "❤️"}, {"అప్డేట్", "⬆️"}, {"అనుమతులు", "🔐"},
            {"API ఖర్చు", "💰"}, {"మోసం", "🛡️"}, {"చెక్", "🩺"}, {"Jarvis ముఖం", "🙂"}, {"ఫ్లోటింగ్", "🔵"}, {"బ్యాకప్", "☁️"}, {"వాచ్", "⌚"}};
    /** Read when used, so they follow the theme. */
    private static int[] cardColors() { return new int[]{Ui.C_SKY, Ui.C_VIOLET, Ui.C_BLUE, Ui.C_CYAN, Ui.C_PINK, Ui.C_BLUE, Ui.C_TEAL,
            Ui.C_GREEN, Ui.C_SKY, Ui.C_AMBER, Ui.C_GREEN, Ui.C_VIOLET, Ui.C_AMBER, 0xFFF43F5E, Ui.C_PINK, Ui.C_GREEN, Ui.C_ORANGE,
            Ui.C_TEAL, 0xFFF43F5E, Ui.C_CYAN, Ui.C_AMBER, Ui.C_GREEN, 0xFFF43F5E, Ui.C_GREEN}; }
    private int cards;

    /** A new section: its own glass card in its own colour, with an emoji and the title. */
    private View section(String s) { return section(s, null); }

    /** A new section with its own emoji (else the one its title's words pick from LOOKS). */
    private View section(String s, String ownEmoji) {
        String emoji = "✦";
        int[] colors = cardColors();
        int color = colors[cards % colors.length];
        if (ownEmoji != null) emoji = ownEmoji;
        else for (int i = 0; i < LOOKS.length; i++) {
            if (s.contains(LOOKS[i][0])) { emoji = LOOKS[i][1]; color = colors[i % colors.length]; break; }
        }
        cards++;
        accent = color;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 16));
        android.graphics.drawable.GradientDrawable bg = Ui.grad(this, new int[]{Ui.alpha(color, 0x24), Ui.alpha(color, 0x08)}, 20,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setStroke(Ui.dp(this, 1), Ui.alpha(color, 0x55));
        card.setBackground(bg);
        card.setTag(color);
        cardTitles.add(new Object[]{s, card});
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView e = Ui.text(this, emoji, 18, 0xFFFFFFFF);
        e.setBackground(Ui.round(this, Ui.alpha(color, 0x33), 0, 12));
        e.setGravity(Gravity.CENTER);
        head.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView t = Ui.text(this, s, 17, color);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(t);
        TextView sum = Ui.text(this, "", 13, Ui.MUTED);
        sum.setSingleLine(true);
        sum.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sum.setVisibility(View.GONE);
        col.addView(sum);
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chev = Ui.text(this, "⌄", 20, color);
        chev.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 2), 0);
        head.addView(chev);
        card.addView(head);
        LinearLayout body = new LinearLayout(this); // folded until he taps the title
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        Card c = new Card();
        c.title = s;
        c.group = groupOf(s);
        c.view = card;
        c.body = body;
        c.summary = sum;
        c.chevron = chev;
        head.setOnClickListener(v -> toggle(c));
        cardList.add(c);
        cur = c;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        page.addView(card, lp);
        box = body;
        return card;
    }

    /** A number box for a balance (empty when none is set). */
    private EditText moneyField(String label, String provider) {
        float b = Usage.balance(provider);
        String v = b <= 0 ? "" : (b == Math.rint(b) ? String.valueOf((long) b) : String.format(Locale.ENGLISH, "%.2f", b));
        EditText e = field(label, v, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    /** A changed balance starts a new count-down; an unchanged one keeps counting. */
    private void saveBalance(EditText e, String provider) {
        float v = 0;
        try { v = Float.parseFloat(e.getText().toString().trim().replace(",", "")); } catch (Exception ignored) {}
        if (Math.abs(v - Usage.balance(provider)) > 0.001f) Usage.setBalance(this, provider, v);
    }

    /** A darker inset box for typing, inside the glass cards. */
    private android.graphics.drawable.GradientDrawable fieldBg() { return Ui.round(this, 0x47000000, 0x2BFFFFFF, 14); }

    /** Switches, sliders and choices inside a card take the card's colour. */
    private void tint(View v, int color) {
        android.content.res.ColorStateList on = android.content.res.ColorStateList.valueOf(color);
        if (v instanceof SeekBar) {
            SeekBar sb = (SeekBar) v;
            sb.setProgressTintList(on);
            sb.setThumbTintList(on);
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        } else if (v instanceof Switch) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            ((Switch) v).setThumbTintList(new android.content.res.ColorStateList(states, new int[]{color, 0xFFB8C4CC}));
            ((Switch) v).setTrackTintList(new android.content.res.ColorStateList(states, new int[]{Ui.alpha(color, 0x88), 0x44FFFFFF}));
        } else if (v instanceof RadioButton) {
            ((RadioButton) v).setButtonTintList(on);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i), color);
        }
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(t);
        if (cur == null || s.length() <= 90 || box != cur.body) return;
        // a long explanation: its first sentence stays in sight, the rest until he asks ("ⓘ పూర్తి వివరణ")
        String head = firstPart(s);
        if (head.length() >= s.length() - 2) return; // (one long sentence: all of it)
        final String[] both = {head + " …", s};
        t.setText(both[0]);
        t.setTag(both); // (the search still finds every word: collectWords)
        final Card c = cur;
        c.notes.add(t);
        if (c.info == null) {
            c.info = Ui.text(this, "ⓘ  పూర్తి వివరణ చూపించు", 14, accent);
            c.info.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
            c.info.setOnClickListener(v -> {
                c.notesShown = !c.notesShown;
                for (TextView n : c.notes) if (n.getTag() instanceof String[]) n.setText(((String[]) n.getTag())[c.notesShown ? 1 : 0]);
                c.info.setText(c.notesShown ? "ⓘ  వివరణ తగ్గించు" : "ⓘ  పూర్తి వివరణ చూపించు");
            });
            c.body.addView(c.info, 0);
        }
    }

    /** A long explanation's first sentence (what is always shown of it). */
    static String firstPart(String s) {
        int best = -1;
        for (String end : new String[]{". ", "; ", "! ", "? ", ".\n"}) {
            int i = s.indexOf(end, 20);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        if (best > 0 && best <= 170) return s.substring(0, best + 1).trim();
        int cut = s.lastIndexOf(' ', 120);
        return s.substring(0, cut > 40 ? cut : Math.min(s.length(), 120)).trim();
    }

    /** Examples for boxes whose label has none (shown while the box is empty). */
    private static final String[][] EXAMPLES = {
            {"గ్రూప్‌లో మిమ్మల్ని పిలిచే పేర్లు", "ఉదా: Anil, అనిల్, అన్నా"}, {"📍 లోకల్ వార్తల ప్రాంతాలు", "ఉదా: తెలంగాణ, హైదరాబాద్"},
            {"ఒక్క బుకింగ్‌కి గరిష్ఠంగా", "ఉదా: 1000"}, {"💰 బంగారం, వెండి, పెట్రోల్ ధరలకి మీ సిటీ", "ఉదా: Hyderabad"},
            {"🏍️ బండి నంబర్", "ఉదా: TS09AB1234"}, {"📞 ఎమర్జెన్సీ కాంటాక్ట్", "ఉదా: రవి, 98xxxxxxxx"},
            {"🎧 మిమ్మల్ని పిలిచే పేర్లు", "ఉదా: Anil, అనిల్"}, {"Telegram bot token", "BotFather ఇచ్చిన token ఇక్కడ పేస్ట్ చేయండి"},
            {"OpenAI API key", "sk-… (ఇక్కడ పేస్ట్ చేయండి)"}, {"Anthropic API key", "sk-ant-… (ఇక్కడ పేస్ట్ చేయండి)"},
            {"Gemini API key", "AIza… (ఇక్కడ పేస్ట్ చేయండి)"}};

    /** The example a box shows while it is empty: from its label's "(ఉదా: …)", else from EXAMPLES. */
    static String exampleFor(String label) {
        if (label == null) return null;
        int u = label.indexOf("(ఉదా: ");
        if (u >= 0) {
            int end = label.indexOf(')', u);
            return end > u ? label.substring(u + 1, end).trim() : null;
        }
        for (String[] e : EXAMPLES) if (label.startsWith(e[0])) return e[1];
        return null;
    }

    /** A box for a number (decimals allowed). */
    private EditText numberField(String label, String value) {
        EditText e = field(label, value, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private static String trimZero(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(v);
    }

    private EditText field(String label, String value, boolean secret) {
        TextView l = Ui.text(this, label, 14, Ui.MUTED);
        l.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.FAINT);
        String ex = exampleFor(label); // (an empty box shows how to fill it)
        if (ex != null) e.setHint(ex);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setBackground(fieldBg());
        int p = Ui.dp(this, 12);
        e.setPadding(p, p, p, p);
        e.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (gated) return;
        // the microphone for the guard's house sounds was just given: start listening
        if (code == 62 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) Guard.listen(this);
        if (code == 64 && hcStatus != null) hcStatus.setText("🔗 Health Connect: " + HealthData.status(this)); // (phase 4)
        // older data (his height set long ago): asked on its own, once, after the others
        if (code == 64 && HealthData.connected(this) && HealthData.historyToAsk(this)
                && !getSharedPreferences("jarvis_height", MODE_PRIVATE).getBoolean("asked_history", false)) {
            try { requestPermissions(new String[]{HealthData.HISTORY}, 65); } catch (Exception ignored) {}
        }
        if (code == 65) { // (answered: not asked again by itself; the 🔗 button asks the data ones only)
            getSharedPreferences("jarvis_height", MODE_PRIVATE).edit().putBoolean("asked_history", true).apply();
            new Thread(() -> { try { Wellness.refreshHeight(getApplicationContext(), true); } catch (Exception ignored) {} }, "jarvis-height").start();
        }
    }

    private TextView hcStatus;

    private String heightText() {
        double cm = Wellness.heightCm(this);
        if (cm <= 0) return "📏 మీ ఎత్తు: తెలియదు (నొక్కి రాయండి; అప్పుడు ప్రతి నడక దూరం సరిగ్గా, BMI కూడా)";
        return "📏 మీ ఎత్తు: " + Math.round(cm) + " సెం.మీ (" + ("samsung".equals(Wellness.heightFrom(this)) ? "Samsung Health నుంచి" : "మీరు రాసింది")
                + ") · ఒక అడుగు సుమారు " + Wellness.strideFor(cm) + " మీ  (మార్చడానికి నొక్కండి)";
    }

    /** Phase 4: health from the watch and Samsung Health (Health Connect). Each choice acts at once; no Save needed. */
    private void healthSection() {
        hcStatus = Ui.text(this, "🔗 Health Connect: " + HealthData.status(this), 14, Ui.MUTED);
        hcStatus.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(hcStatus);
        button("🔗 Health Connect కలపండి (నిద్ర, అడుగులు, గుండె వేగం, ఆక్సిజన్, బరువు, BP, ఎత్తు, వ్యాయామం, షుగర్, ఆహారం… చదవడానికి)", v -> {
            if (Build.VERSION.SDK_INT < 34 || !HealthData.available(this)) { Toast.makeText(this, HealthData.status(this), Toast.LENGTH_LONG).show(); return; }
            String[] need = HealthData.toAsk(this);
            if (need.length == 0) { Toast.makeText(this, "Health Connect ఇప్పటికే కలిసింది ✓", Toast.LENGTH_SHORT).show(); return; }
            try { requestPermissions(need, 64); } catch (Exception e) { Toast.makeText(this, "తెరవలేకపోయాను: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        note("Samsung Health → Settings → Health Connect లో కూడా \"షేర్\" ఆన్ చేయాలి (అప్పుడే వాచ్ నిద్ర, ఆక్సిజన్, బరువు ఇక్కడికి వస్తాయి). Jarvis చదువుతుంది మాత్రమే, "
                + "ఏమీ రాయదు. Samsung తన స్ట్రెస్ నంబర్, ECG, ఎనర్జీ స్కోర్ వేరే యాప్‌లకు ఇవ్వదు: అందుకే ఒత్తిడి, ఎనర్జీ Jarvis తన అంచనాతో చెబుతుంది. "
                + "కొన్నింటికి మీరు చేస్తేనే డేటా వస్తుంది: వ్యాయామం / దూరం / కేలరీలు (వాచ్‌లో Walk మొదలుపెడితే, లేదా Samsung గుర్తించిన పెద్ద నడకలు), "
                + "VO2 max (బయట పరుగు), షుగర్ / ఆహారం (Samsung Health లో రాస్తే), BMR (Body composition కొలిస్తే): ఇవన్నీ Samsung పంపితేనే.");
        // his height: the step length for every walk, and BMI
        TextView height = Ui.text(this, heightText(), 15.5f, Ui.CYAN);
        height.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        height.setOnClickListener(v -> {
            android.widget.EditText in = new android.widget.EditText(this);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            in.setHint("ఉదా: 170");
            double now = Wellness.heightCm(this);
            if (now > 0) in.setText(String.valueOf(Math.round(now)));
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("మీ ఎత్తు (సెంటీమీటర్లలో)").setView(in)
                    .setPositiveButton("సేవ్", (d, w) -> {
                        double cm;
                        try { cm = Double.parseDouble(in.getText().toString().trim()); } catch (Exception e) { cm = 0; }
                        if (cm < 120 || cm > 220) { Toast.makeText(this, "120 నుంచి 220 మధ్య సెం.మీ రాయండి", Toast.LENGTH_LONG).show(); return; }
                        Wellness.setHeight(this, cm, "said");
                        height.setText(heightText());
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(height);
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> { // (Samsung Health's height, if it has one; what he typed himself is kept)
            try { Wellness.refreshHeight(appCtx, false); } catch (Exception ignored) {}
            runOnUiThread(() -> height.setText(heightText()));
        }, "jarvis-height").start();
        Switch walk = toggle("🚶 నడక కోచ్: ప్రతి నడక అయ్యాక అడుగులు, మీటర్లు / కి.మీ, నిమిషాలు వాచ్‌లో చెప్పు; ప్రతి కి.మీకి ఒక మాట", WatchHub.walkOn(this));
        walk.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "walk", on));
        note("వాచ్ తనంతట తానే నడక గుర్తిస్తుంది: 2 నిమిషాలు, 150 అడుగులు దాటిన నడక (ఇంట్లో అటూ ఇటూ తిరగడం లెక్క కాదు). దూరం సుమారుగా (మీ ఎత్తు నుంచి అడుగు పొడవు; ఎత్తు తెలియకపోతే 0.72 మీ). "
                + "బైక్ మీద, డ్యూటీ మోడ్‌లో, రాత్రి గొంతుతో కాకుండా వాచ్‌పై కార్డ్‌గా. \"ఈరోజు ఎంత నడిచాను?\" అని అడగొచ్చు, వాచ్‌లో 🚶 నడక బటన్ కూడా.");
        Switch hr = toggle("💓 కూర్చున్నప్పుడు 15 నిమిషాలకోసారి వాచ్ గుండె వేగం చూడాలి (ఒత్తిడి, జ్వరం సూచన, ఎనర్జీ, కునుకు)", WatchHub.hrOn(this));
        hr.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "hr", on));
        Switch stress = toggle("😮‍💨 ఒత్తిడిగా ఉన్నట్టు అనిపిస్తే వాచ్‌పై మెల్లగా తట్టి 2 నిమిషాల శ్వాస వ్యాయామం చూపించు (రోజుకు 3 సార్లు మించి కాదు)", WatchHub.stressOn(this));
        stress.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "stress", on));
        note("మీ మామూలు గుండె వేగం ఒక రోజు వాచ్ పెట్టుకున్నాక నేర్చుకుంటుంది (14 రోజుల లెక్క). కూర్చున్నప్పుడు మామూలు కంటే 12+ ఎక్కువగా వరుసగా వస్తే \"ఒత్తిడిగా ఉందా?\". "
                + "దగ్గు రోజు గుండె వేగం ఎక్కువగా ఉంటే \"జ్వరం ఉందేమో చూడండి\" (రోజుకు ఒకసారి). మధ్యాహ్నం / సాయంత్రం వాచ్ నిద్ర గుర్తిస్తే మెసేజ్‌లు లేచాక చెబుతాను "
                + "(\"విశ్రాంతి\" ఆన్‌లో ఉంటే). వాచ్ గుండె లయ హెచ్చరిక ఇస్తే తేదీతో రాసుకుని డాక్టర్‌ని చూడమని గుర్తు చేస్తాను. ఇది వైద్య పరీక్ష కాదు.");
        Switch week = toggle("📊 ప్రతి ఆదివారం సాయంత్రం ఈ వారం ఆరోగ్యం గ్రాఫ్ (అడుగులు, నిద్ర, గుండె వేగం, ఒత్తిడి, దగ్గు)", WatchHub.healthWeekOn(this));
        week.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "health_week", on));
        button("📊 ఈ వారం ఆరోగ్యం గ్రాఫ్ ఇప్పుడే చూపించు", v -> {
            Toast.makeText(this, "గ్రాఫ్ తయారవుతోంది…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Coder.Made m = (Coder.Made) HealthWeek.make(getApplicationContext())[0];
                    runOnUiThread(() -> { if (m.uri != null) Cards.view(this, m); else Toast.makeText(this, "సేవ్ అయింది: " + m.where, Toast.LENGTH_LONG).show(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "గ్రాఫ్ తయారు కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "jarvis-health-week").start();
        });
        note("వాచ్ Jarvis స్క్రీన్‌లో: 🚶 నడక (ఈరోజు), 🩺 స్కాన్ (20 సెకన్లు కదలకుండా: గుండె వేగం + మీ రోజు), 🌬️ శ్వాస (4 సెకన్లు పీల్చి 6 సెకన్లు వదలడం, "
                + "చేతికి వైబ్రేషన్‌తో, 2 నిమిషాలు). మొదటిసారి వాచ్ \"శరీర సెన్సార్లు\" అనుమతి అడుగుతుంది: Allow. ECG: Samsung Health Monitor లో ECG తీసి Share → "
                + "\"Jarvis ఆరోగ్య రిపోర్ట్\" ఎంచుకుంటే Downloads/Jarvis/health లో తేదీతో దాచి, డాక్టర్ PDF లో చూపిస్తాను. నెల మొదట్లో బరువు, కొవ్వు శాతం రిపోర్ట్.");
    }

    // ================================================================ the home tablet, from his phone: five small cards

    /** The five cards (the "(టాబ్లెట్)" end puts each in the 🏠 group). */
    static final String TAB_TIMES = "అమ్మగారి టైమ్‌లు (టాబ్లెట్)", TAB_MEDS = "అమ్మగారి మాత్రలు, ఆరోగ్యం (టాబ్లెట్)",
            TAB_MSG = "అమ్మకి మెసేజ్, రిమైండర్ (టాబ్లెట్)", TAB_FUN = "అమ్మగారితో ఆటలు, ఫోటోలు, రిపోర్ట్ (టాబ్లెట్)",
            TAB_DEVICE = "గొంతు, గది, కెమెరా, స్థితి (టాబ్లెట్)";
    /** What "📤 టాబ్లెట్‌కి పంపు" sends: {key, default, its card}. */
    private static final String[][] TAB_KEYS = {
            {"who", "అమ్మగారు", TAB_TIMES}, {"tiffin", "08:30", TAB_TIMES}, {"lunch", "13:00", TAB_TIMES}, {"snack", "16:30", TAB_TIMES},
            {"dinner", "20:00", TAB_TIMES}, {"water", "10:30,12:30,15:30,18:00", TAB_TIMES}, {"chat", "10:00,17:30", TAB_TIMES},
            {"rest", "13:30-15:30", TAB_TIMES}, {"bed", "21:30", TAB_TIMES}, {"report", "21:45", TAB_TIMES}, {"silent_hours", "3", TAB_TIMES},
            {"meds_text", "", TAB_MEDS}, {"sugar_low", "70", TAB_MEDS}, {"sugar_high", "250", TAB_MEDS}, {"doctor_note", "", TAB_MEDS},
            {"voice", "", TAB_DEVICE}, {"tts_model", "", TAB_DEVICE}, {"room", "hall", TAB_DEVICE}, {"camera", "true", TAB_DEVICE},
            {"stranger_photo", "true", TAB_DEVICE}, {"ai_limit", "200", TAB_DEVICE}, {"birthdays", "", TAB_TIMES},
            {"video_number", "", TAB_DEVICE}};
    private static final java.util.Set<String> TAB_INTS = new java.util.HashSet<>(java.util.Arrays.asList("silent_hours", "sugar_low", "sugar_high", "ai_limit"));
    private static final java.util.Set<String> TAB_BOOLS = new java.util.HashSet<>(java.util.Arrays.asList("camera", "stranger_photo"));

    /** The form's boxes and switches by key (voice and room are picked from a list). Empty on the tablet itself. */
    private final java.util.Map<String, View> tab = new java.util.HashMap<>();
    private final String[] tabVoice = {""}, tabRoom = {"hall"};
    /** In each card with boxes: the amber "not sent yet" line and the 📤 answer line. */
    private final java.util.List<TextView> tabUnsent = new java.util.ArrayList<>(), tabSent = new java.util.ArrayList<>();
    private TextView tabStatus;
    private boolean askedLeave, tabAsking;
    /** The last send to the tablet this screen knows of (another Settings screen may send too: then the boxes are read again). */
    private long tabSeenSent;
    private Runnable tabShowVoice, tabShowRoom;

    private android.content.SharedPreferences ts() { return getSharedPreferences("jarvis_tabset", MODE_PRIVATE); }

    private String tabDef(String key, String def) {
        if ("voice".equals(key)) return prefs.naturalVoiceName();
        if ("tts_model".equals(key)) return prefs.ttsModel();
        return def;
    }

    /** What the tablet has now (the values last sent to it). */
    private String tabStored(String key, String def) {
        android.content.SharedPreferences s = ts();
        String d = tabDef(key, def);
        try {
            if (TAB_INTS.contains(key)) return String.valueOf(s.getInt(key, Integer.parseInt(d)));
            if (TAB_BOOLS.contains(key)) return String.valueOf(s.getBoolean(key, Boolean.parseBoolean(d)));
            return s.getString(key, d).trim();
        } catch (Exception e) {
            return d;
        }
    }

    /** What the form says now. */
    private String tabValue(String key) {
        if ("voice".equals(key)) return tabVoice[0];
        if ("room".equals(key)) return tabRoom[0];
        View v = tab.get(key);
        if (v instanceof Switch) return String.valueOf(((Switch) v).isChecked());
        if (v instanceof EditText) return ((EditText) v).getText().toString().trim();
        return "";
    }

    /** What he changed and did not send yet (kept when he left), or null. */
    private org.json.JSONObject tabDraft() {
        try {
            String d = ts().getString("draft", "");
            return d.isEmpty() ? null : new org.json.JSONObject(d);
        } catch (Exception e) {
            return null;
        }
    }

    /** A box starts with his unsent change, else with what the tablet has. */
    private String tabStart(String key, String def, org.json.JSONObject draft) {
        return draft != null && draft.has(key) ? draft.optString(key) : tabStored(key, def);
    }

    /** The cards with something not sent to the tablet yet (empty: all sent). */
    private java.util.List<String> tabChangedCards() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (tab.isEmpty()) return out;
        for (String[] k : TAB_KEYS) if (!tabValue(k[0]).equals(tabStored(k[0], k[1])) && !out.contains(k[2])) out.add(k[2]);
        return out;
    }

    private static String tabShort(String card) {
        return TAB_TIMES.equals(card) ? "టైమ్‌లు" : TAB_MEDS.equals(card) ? "మాత్రలు, ఆరోగ్యం" : TAB_DEVICE.equals(card) ? "గొంతు, గది, కెమెరా" : card;
    }

    private static String tabShorts(java.util.List<String> cards) {
        StringBuilder b = new StringBuilder();
        for (String c : cards) b.append(b.length() == 0 ? "" : ", ").append(tabShort(c));
        return b.toString();
    }

    /** After any change: the unsent draft is kept (or dropped when all is sent), and "● ఇంకా పంపలేదు" shows where it matters. */
    private void tabChanged() {
        if (tab.isEmpty()) return;
        java.util.List<String> ch = tabChangedCards();
        try {
            if (ch.isEmpty()) {
                if (ts().contains("draft")) ts().edit().remove("draft").apply();
            } else {
                org.json.JSONObject d = new org.json.JSONObject();
                for (String[] k : TAB_KEYS) d.put(k[0], tabValue(k[0]));
                if (!d.toString().equals(ts().getString("draft", ""))) ts().edit().putString("draft", d.toString()).apply();
            }
        } catch (Exception ignored) {}
        for (TextView t : tabUnsent) {
            t.setText("● ఈ మార్పులు ఇంకా టాబ్లెట్‌కి వెళ్లలేదు (" + tabShorts(ch) + "): కింద \"📤 టాబ్లెట్‌కి పంపు\" నొక్కండి.");
            t.setVisibility(ch.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** "● పంపలేదు" for a card's one line, or "". */
    private String tabUnsentMark(String card) {
        return tabChangedCards().contains(card) ? "● ఇంకా పంపలేదు · " : "";
    }

    /** A box for the tablet (its example stays in the label; numbers get the number keys). */
    private EditText tabField(String key, String label, String def, org.json.JSONObject draft) {
        EditText e = field(label, tabStart(key, def, draft), false);
        if (TAB_INTS.contains(key)) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        tab.put(key, e);
        return e;
    }

    /** In a card with boxes for the tablet: the amber "not sent yet" line, the 📤 button and its answer. */
    private void tabSendButton() {
        TextView un = Ui.text(this, "", 14, 0xFFFDE68A);
        un.setPadding(0, Ui.dp(this, 12), 0, 0);
        un.setVisibility(View.GONE);
        box.addView(un);
        tabUnsent.add(un);
        button("📤 టాబ్లెట్‌కి పంపు", v -> sendTablet(false));
        TextView sent = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(sent);
        tabSent.add(sent);
    }

    private void setTabSent(String s) { for (TextView t : tabSent) t.setText(s); }

    /**
     * On his phone: the home tablet's settings, sent to it through his Telegram bot (the home link), in five small cards:
     * her day's times; her tablets, sugar limits and the doctor's advice; a message / reminder said at home; games, the
     * photo frame and the week's report; the tablet's voice, room, camera, AI limit and its status now. One 📤 sends all
     * of them; what he changed and did not send is kept (and shown as "● ఇంకా పంపలేదు", and asked about when he leaves).
     */
    private void tabletSection() {
        android.content.SharedPreferences.Editor once = ts().edit(); // (never sent yet: the tablet speaks with the phone's voice of today)
        if (!ts().contains("voice")) once.putString("voice", prefs.naturalVoiceName());
        if (!ts().contains("tts_model")) once.putString("tts_model", prefs.ttsModel());
        once.apply();
        tabSeenSent = ts().getLong("sent_at", 0);
        final org.json.JSONObject draft = tabDraft();
        tabVoice[0] = tabStart("voice", "", draft);
        tabRoom[0] = "bedroom".equals(tabStart("room", "hall", draft)) ? "bedroom" : "hall";

        // ---- 1. her day's times
        section(TAB_TIMES, "🍽️");
        note("అమ్మగారి రోజు: ఈ టైమ్‌లకి Jarvis టిఫిన్, భోజనం, నీళ్లు అడుగుతాడు, మాట్లాడతాడు. మార్చాక \"📤 టాబ్లెట్‌కి పంపు\" నొక్కండి. "
                + "అది మీ Telegram bot ద్వారా టాబ్లెట్‌కి వెళ్తుంది (రెండింటికీ నెట్ కావాలి).");
        tabField("who", "అమ్మని Jarvis ఏమని పిలవాలి (ఉదా: అమ్మగారు)", "అమ్మగారు", draft);
        tabField("tiffin", "🍽️ టిఫిన్ టైమ్ (ఉదా: 08:30)", "08:30", draft);
        tabField("lunch", "🍽️ మధ్యాహ్నం భోజనం (ఉదా: 13:00)", "13:00", draft);
        tabField("snack", "🍪 సాయంత్రం స్నాక్స్ (ఉదా: 16:30)", "16:30", draft);
        tabField("dinner", "🍽️ రాత్రి భోజనం (ఉదా: 20:00)", "20:00", draft);
        tabField("water", "💧 నీళ్లు అడిగే టైమ్‌లు, కామాతో (ఉదా: 10:30,12:30,15:30,18:00)", "10:30,12:30,15:30,18:00", draft);
        tabField("chat", "🗣️ Jarvis తనంతట తానే మాట్లాడే టైమ్‌లు (ఉదా: 10:00,17:30)", "10:00,17:30", draft);
        tabField("rest", "😴 మధ్యాహ్నం విశ్రాంతి, ఆ టైమ్‌లో నిశ్శబ్దం (ఉదా: 13:30-15:30)", "13:30-15:30", draft);
        tabField("bed", "🌙 పడుకునే ముందు చెక్ (ఉదా: 21:30)", "21:30", draft);
        tabField("report", "📋 మీకు రోజు రిపోర్ట్, Telegram లో (ఉదా: 21:45)", "21:45", draft);
        tabField("silent_hours", "⏳ ఇన్ని గంటలు అలికిడి లేకపోతే \"బాగున్నారా?\" (ఉదా: 3)", "3", draft);
        EditText bd = tabField("birthdays", "🎂 పుట్టినరోజులు: పేరు = రోజు-నెల, కామాతో (ఆ రోజు టాబ్లెట్ Jarvis కేక్‌తో శుభాకాంక్షలు చెబుతాడు)", "", draft);
        bd.setHint("ఉదా: అమ్మగారు = 12-03, అనిల్ = 05-07");
        tabSendButton();

        // ---- 2. her tablets, sugar, the doctor's advice
        section(TAB_MEDS, "💊");
        note("💊 అమ్మగారి టాబ్లెట్లు: ఒక్కో లైన్‌లో \"పేరు = టైమ్‌లు | తిన్న తర్వాత | మాత్రల సంఖ్య\" (ఉదా: షుగర్ టాబ్లెట్ = 08:00, 20:00 | తిన్న తర్వాత | 30 మాత్రలు). "
                + "పేరు అమ్మగారికి అర్థమయ్యేది పెట్టండి (Jarvis అదే పలుకుతాడు). మాత్రల సంఖ్య రాస్తే, 5 రోజులకే మిగిలినప్పుడు మీకు Telegram; కొత్త స్ట్రిప్ కొన్నప్పుడు సంఖ్య మార్చి 📤 నొక్కండి. "
                + "డాక్టర్ / మీరు చెప్పినవే పెట్టండి; Jarvis సొంతంగా మందులు చెప్పడు. టాబ్లెట్ టైమ్‌కి అడుగుతాడు, 10 నిమిషాలకోసారి మళ్లీ అడుగుతాడు, 3 సార్లు జవాబు లేకపోతే మీకు Telegram.");
        // (the box's own label and example, always in sight)
        TextView medsLabel = Ui.text(this, "💊 అమ్మగారి టాబ్లెట్లు (ఒక్కో లైన్‌లో ఒకటి: పేరు = టైమ్‌లు | తిన్న తర్వాత | మాత్రల సంఖ్య)", 14.5f, accent);
        medsLabel.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(medsLabel);
        EditText meds = new EditText(this);
        meds.setHint("ఉదా: షుగర్ టాబ్లెట్ = 08:00, 20:00 | తిన్న తర్వాత | 30 మాత్రలు");
        meds.setHintTextColor(Ui.MUTED);
        meds.setText(tabStart("meds_text", "", draft));
        meds.setTextColor(Ui.TEXT);
        meds.setTextSize(15);
        meds.setSingleLine(false);
        meds.setMinLines(3);
        meds.setGravity(Gravity.TOP | Gravity.START);
        meds.setBackground(fieldBg());
        int pd = Ui.dp(this, 12);
        meds.setPadding(pd, pd, pd, pd);
        meds.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(meds, new LinearLayout.LayoutParams(-1, -2));
        tab.put("meds_text", meds);
        tabField("sugar_low", "🩸 షుగర్ ఇంతకంటే తక్కువైతే మీకు చెప్పు (ఉదా: 70)", "70", draft);
        tabField("sugar_high", "🩸 షుగర్ ఇంతకంటే ఎక్కువైతే మీకు చెప్పు (ఉదా: 250)", "250", draft);
        EditText doc = tabField("doctor_note", "👨‍⚕️ ఒంట్లో బాగోలేనప్పుడు డాక్టర్ చెప్పింది (Jarvis అమ్మకి చదువుతాడు)", "", draft);
        doc.setHint("డాక్టర్ చెప్పిన మాటలే రాయండి");
        tabSendButton();

        // ---- 3. a message to her, read aloud by the tablet's Jarvis (she can answer by voice; her answer comes to his Telegram)
        section(TAB_MSG, "💬");
        EditText mText = field("💬 అమ్మకి మెసేజ్ (టాబ్లెట్ Jarvis చదివి వినిపిస్తాడు)", "", false);
        mText.setHint("ఉదా: పడుకున్నావా? భోజనం చేశావా?");
        TextView mInfo = Ui.text(this, "టాబ్లెట్ \"అబ్బాయి మెసేజ్ పంపాడు: …\" అని చదివి, \"జవాబు ఏమైనా చెప్పాలా?\" అని అడుగుతుంది. అమ్మగారి జవాబు మీ Telegram కి వస్తుంది. "
                + "\"Jarvis, ఇంటికి చెప్పు: …\" అని గొంతుతో కూడా పంపొచ్చు.", 13.5f, Ui.MUTED);
        button("📢 టాబ్లెట్‌లో వినిపించు", v -> {
            String text = mText.getText().toString().trim();
            if (text.isEmpty()) { mInfo.setText("ఏమి చెప్పాలో రాయండి."); return; }
            mInfo.setText("పంపుతున్నాను…");
            new Thread(() -> {
                String r = HomeLink.say(getApplicationContext(), text);
                runOnUiThread(() -> { mInfo.setText(r); if (r.startsWith("పంపాను")) mText.setText(""); });
            }, "tab-say").start();
        });
        box.addView(mInfo);
        // ---- a reminder said at home
        EditText rText = field("⏰ ఇంట్లో ఏమి గుర్తుచేయాలి (ఉదా: డాక్టర్ దగ్గరికి వెళ్లాలి)", "", false);
        EditText rWhen = field("ఎప్పుడు (ఉదా: రేపు 10:00 · ఈరోజు 18:30 · 2026-10-12 09:00)", "", false);
        TextView rInfo = Ui.text(this, "\"Jarvis, రేపు 10 కి అమ్మకి డాక్టర్ దగ్గరికి వెళ్లాలని ఇంట్లో గుర్తుచేయి\" అని గొంతుతో కూడా చెప్పొచ్చు.", 13.5f, Ui.MUTED);
        button("⏰ ఇంటికి రిమైండర్ పంపు", v -> {
            String text = rText.getText().toString().trim();
            long at = whenOf(rWhen.getText().toString(), System.currentTimeMillis());
            if (text.isEmpty()) { rInfo.setText("ఏమి గుర్తుచేయాలో రాయండి."); return; }
            if (at < 0) { rInfo.setText("టైమ్ అర్థం కాలేదు: \"రేపు 10:00\" లేదా \"2026-10-12 09:00\" లాగా రాయండి."); return; }
            rInfo.setText("పంపుతున్నాను…");
            new Thread(() -> {
                String r = HomeLink.remindHome(getApplicationContext(), text, at);
                runOnUiThread(() -> { rInfo.setText(r); if (r.startsWith("పంపాను")) { rText.setText(""); rWhen.setText(""); } });
            }, "tab-remind").start();
        });
        box.addView(rInfo);
        // ---- 🛒 her shopping list (it comes to his Telegram by itself; cleared here once he brought everything)
        String shopNow = "";
        for (String l : TabletWatch.text(this).split("\n")) if (l.startsWith("🛒")) shopNow = l;
        TextView shopInfo = Ui.text(this, (shopNow.isEmpty() ? "🛒 అమ్మగారి సరుకుల లిస్ట్: అమ్మగారు \"Jarvis, బియ్యం అయిపోయాయి\" అంటే లిస్ట్‌లో పడి మీ Telegram కి వస్తుంది."
                : shopNow + " (టాబ్లెట్ స్థితి నుంచి)"), 13.5f, Ui.MUTED);
        button("🛒 లిస్ట్ ఖాళీ చేయి (సరుకులు అన్నీ తెచ్చాను)", v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("టాబ్లెట్‌లో అమ్మగారి సరుకుల లిస్ట్ మొత్తం ఖాళీ చేయనా?")
                .setPositiveButton("ఖాళీ చేయి", (d, w) -> {
                    shopInfo.setText("పంపుతున్నాను…");
                    new Thread(() -> {
                        String r;
                        try { r = HomeLink.sendSettings(getApplicationContext(), new org.json.JSONObject().put("shop_clear", true)); }
                        catch (Exception e) { r = "పంపలేకపోయాను."; }
                        final String out = r.startsWith("పంపాను") ? "పంపాను ✓ సుమారు 20 సెకన్లలో టాబ్లెట్ లిస్ట్ ఖాళీ అవుతుంది." : r;
                        runOnUiThread(() -> shopInfo.setText(out));
                    }, "tab-shop").start();
                })
                .setNegativeButton("వద్దు", null).show());
        box.addView(shopInfo);

        // ---- 4. a game with her from here, the photo frame, the week's report for her doctor
        section(TAB_FUN, "🎲");
        button("🎲 అమ్మగారితో ఆట ఆడు (మీ ఫోన్ నుంచి)", v -> GameActivity.pickFar(this));
        box.addView(Ui.text(this, "చదరంగం, లూడో, వైకుంఠపాళి… మీరు ఎంచుకుంటే టాబ్లెట్‌లో Jarvis ఆమెను అడుగుతాడు. ఆమె సరే అంటే ఇద్దరూ ఆడొచ్చు (ఒక్కో కదలిక చేరడానికి కొన్ని సెకన్లు).", 13.5f, Ui.MUTED));
        button("🖼️ టాబ్లెట్ ఫోటో ఫ్రేమ్‌కి ఫోటోలు పంపు", v -> pickFramePhotos());
        frameInfo = Ui.text(this, "ఎవరూ లేనప్పుడు టాబ్లెట్‌లో ఈ ఫోటోలు ఒక్కొక్కటిగా కనిపిస్తాయి (ఒక్కో ఫోటోకి ~20 సెకన్లు పడుతుంది).", 13.5f, Ui.MUTED);
        box.addView(frameInfo);
        TextView repInfo = Ui.text(this, "ప్రతి ఆదివారం రాత్రి రిపోర్ట్ తర్వాత ఈ PDF మీ Telegram కి తనంతట తానే వస్తుంది.", 13.5f, Ui.MUTED);
        button("📊 అమ్మగారి వారపు రిపోర్ట్ (PDF) ఇప్పుడే", v -> {
            repInfo.setText("అడుగుతున్నాను…");
            new Thread(() -> { String r = HomeLink.askReport(getApplicationContext()); runOnUiThread(() -> repInfo.setText(r)); }, "tab-report").start();
        });
        box.addView(repInfo);

        // ---- 5. the tablet itself: its status now, voice, room, camera, the day's AI limit
        section(TAB_DEVICE, "🔊");
        note("ఇంట్లో ఉన్న టాబ్లెట్ Jarvis సెట్టింగ్స్ ఇక్కడ నుంచే మార్చొచ్చు; \"📤 టాబ్లెట్‌కి పంపు\" నొక్కితే మీ Telegram bot ద్వారా టాబ్లెట్‌కి వెళ్తాయి (రెండింటికీ నెట్ కావాలి). "
                + "ముందు ఒక్కసారి: టాబ్లెట్‌లో, ఈ ఫోన్‌లో ఒకే bot token పెట్టి \"Telegram చాట్ కనుక్కో\" నొక్కాలి (కాపలా మోడ్ కార్డ్‌లో; కాపలా ఆన్ చేయనక్కర్లేదు).");
        tabStatus = Ui.text(this, "", 14.5f, Ui.MUTED);
        tabStatus.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.addView(tabStatus);
        showTabStatus();
        button("🔄 టాబ్లెట్ స్థితి చూడు", v -> {
            tabAsking = true;
            tabStatus.setText("టాబ్లెట్‌ని అడుగుతున్నాను… (నిమిషం వరకు)");
            new Thread(() -> {
                String r = HomeLink.tabletStatus(getApplicationContext());
                runOnUiThread(() -> { tabAsking = false; refreshTiles(); tabStatus.setText(r); }); // (its own answer: errors and the link steps too)
            }, "tab-status").start();
        });
        // the tablet's voice: the phone's by default (he can pick another, like the phone's own)
        TextView vt = Ui.text(this, "", 15.5f, accent);
        vt.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        Runnable showV = tabShowVoice = () -> vt.setText("🔊 టాబ్లెట్ గొంతు: " + tabVoice[0] + (tabVoice[0].equals(prefs.naturalVoiceName()) ? " (ఫోన్ గొంతే)" : "") + "  (మార్చడానికి నొక్కండి)");
        showV.run();
        vt.setOnClickListener(v -> {
            int at = java.util.Arrays.asList(NaturalVoice.VOICES).indexOf(tabVoice[0]);
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("టాబ్లెట్ గొంతు (cedar = లోతైన మగ గొంతు)")
                    .setSingleChoiceItems(NaturalVoice.VOICES, Math.max(0, at), (d, w) -> {
                        tabVoice[0] = NaturalVoice.VOICES[w];
                        showV.run();
                        d.dismiss();
                        tabChanged();
                        summarizeShown();
                    })
                    .show();
        });
        box.addView(vt);
        EditText model = tabField("tts_model", "టాబ్లెట్ గొంతు మోడల్ (ఫోన్‌లోది: " + prefs.ttsModel() + ")", "", draft);
        TextView vInfo = Ui.text(this, "", 13.5f, Ui.MUTED);
        button("▶ ఈ గొంతు ఫోన్‌లో వినిపించు", v -> {
            String key = prefs.openAiKey().trim();
            if (key.isEmpty()) { Toast.makeText(this, "ముందు OpenAI key పెట్టండి", Toast.LENGTH_SHORT).show(); return; }
            String m = model.getText().toString().trim();
            tester.model = m.isEmpty() ? NaturalVoice.DEFAULT_MODEL : m;
            tester.cloud = null; // (the tablet speaks with OpenAI)
            vInfo.setText("వినిపిస్తున్నాను…");
            tester.speak(key, tabVoice[0], "నమస్కారం అమ్మగారు. నేను Jarvis. టిఫిన్ చేశారా?", new NaturalVoice.Callback() {
                @Override public void onStart() { vInfo.setText("గొంతు: " + tabVoice[0] + " · " + tester.model); }
                @Override public void onDone() { vInfo.setText("గొంతు: " + tabVoice[0] + " · " + tester.model + " ✓"); }
                @Override public void onError(String message) { vInfo.setText("పనిచేయలేదు: " + message); }
            });
        });
        box.addView(vInfo);
        // where the tablet stands, its camera, strangers' pictures
        TextView roomT = Ui.text(this, "", 15.5f, accent);
        roomT.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        Runnable showRoom = tabShowRoom = () -> roomT.setText("📍 టాబ్లెట్ ఉన్న చోటు: " + roomLabel(tabRoom[0]) + "  (మార్చడానికి నొక్కండి)");
        showRoom.run();
        roomT.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("టాబ్లెట్ ఎక్కడ ఉంది?")
                .setSingleChoiceItems(ROOM_NAMES, "bedroom".equals(tabRoom[0]) ? 1 : 0, (d, w) -> {
                    tabRoom[0] = w == 0 ? "hall" : "bedroom";
                    showRoom.run();
                    d.dismiss();
                    tabChanged();
                    summarizeShown();
                })
                .show());
        box.addView(roomT);
        tab.put("camera", toggle("📷 టాబ్లెట్ కెమెరా: ముఖం చూసి ప్రవర్తించడం (పగలు మాత్రమే, ఏదీ సేవ్ అవ్వదు)", Boolean.parseBoolean(tabStart("camera", "true", draft))));
        tab.put("stranger_photo", toggle("🚪 తెలియని మనిషి కనిపిస్తే (అమ్మగారు \"తెలియదు\" అంటే / జవాబు లేకపోతే) ఫోటో నాకు పంపు",
                Boolean.parseBoolean(tabStart("stranger_photo", "true", draft))));
        tabField("ai_limit", "🧠 టాబ్లెట్ రోజు AI పరిమితి, ప్రశ్నలు; మీ key ఖర్చు (ఉదా: 200)", "200", draft);
        EditText vn = tabField("video_number", "📹 అమ్మగారి \"వీడియో కాల్\" బటన్‌కి మీ WhatsApp నంబర్ (టాబ్లెట్‌లో WhatsApp ఉండాలి)", "", draft);
        vn.setInputType(InputType.TYPE_CLASS_PHONE);
        vn.setHint("ఉదా: +91 98xxxxxxxx");
        tabSendButton();
        tabChanged(); // (a draft from last time: its "● ఇంకా పంపలేదు" shows at once)
    }

    /** The tablet's last status (TabletWatch) in the 🔊 card. */
    private void showTabStatus() {
        if (tabStatus == null || tabAsking) return; // (his own 🔄 is waiting: its answer shows when it comes)
        long at = TabletWatch.at(this);
        tabStatus.setText(at == 0 ? "టాబ్లెట్ స్థితి: (కింద నొక్కండి)"
                : "టాబ్లెట్ స్థితి (" + TabletWatch.ago(at, System.currentTimeMillis()) + "):\n" + TabletWatch.text(this));
    }

    /** Another Settings screen sent to the tablet meanwhile: these boxes show what the tablet has now (no stale values sent back). */
    private void tabReloadIfSentElsewhere() {
        if (tab.isEmpty()) return;
        long s = ts().getLong("sent_at", 0);
        if (s <= tabSeenSent) return;
        tabSeenSent = s;
        org.json.JSONObject draft = tabDraft();
        for (String[] k : TAB_KEYS) {
            String v = tabStart(k[0], k[1], draft);
            if ("voice".equals(k[0])) tabVoice[0] = v;
            else if ("room".equals(k[0])) tabRoom[0] = "bedroom".equals(v) ? "bedroom" : "hall";
            else if (tab.get(k[0]) instanceof Switch) ((Switch) tab.get(k[0])).setChecked(Boolean.parseBoolean(v));
            else if (tab.get(k[0]) instanceof EditText) ((EditText) tab.get(k[0])).setText(v);
        }
        if (tabShowVoice != null) tabShowVoice.run();
        if (tabShowRoom != null) tabShowRoom.run();
        lastFp = fingerprint(); // (not a change of his: nothing to save)
        tabChanged();
        summarizeShown();
    }

    /** "📤 టాబ్లెట్‌కి పంపు" (any of the cards): everything goes; kept as "what the tablet has" only once it went. */
    private void sendTablet(boolean leaving) {
        if (tab.isEmpty()) return;
        final android.content.SharedPreferences ts = ts();
        final org.json.JSONObject j = new org.json.JSONObject();
        final java.util.Map<String, String> sentNow = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Integer> stockNow = new java.util.HashMap<>();
        try {
            for (String[] k : TAB_KEYS) {
                String key = k[0], v = tabValue(key);
                if (TAB_INTS.contains(key)) {
                    int n;
                    try { n = Integer.parseInt(v); } catch (Exception ex) { n = Integer.parseInt(tabStored(key, k[1])); }
                    if (!String.valueOf(n).equals(v) && tab.get(key) instanceof EditText) ((EditText) tab.get(key)).setText(String.valueOf(n));
                    j.put(key, n);
                    v = String.valueOf(n);
                } else if (TAB_BOOLS.contains(key)) {
                    j.put(key, Boolean.parseBoolean(v));
                } else if (!"meds_text".equals(key)) {
                    j.put(key, v);
                }
                sentNow.put(key, v);
            }
            org.json.JSONArray ma = new org.json.JSONArray();
            for (String line : tabValue("meds_text").split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String nm = line.substring(0, eq).trim();
                String[] seg = line.substring(eq + 1).split("\\|");
                String rest = seg[0], food = "";
                int stock = -1;
                for (int i = 1; i < seg.length; i++) { // "| తిన్న తర్వాత", "| 30 మాత్రలు"
                    String g = seg[i].trim();
                    java.util.regex.Matcher num = java.util.regex.Pattern.compile("(\\d{1,4})").matcher(g);
                    if (num.find() && (g.matches("(?s).*(మాత్ర|టాబ్లెట్|స్టాక్|tablet|stock).*") || g.matches("\\d{1,4}"))) stock = Integer.parseInt(num.group(1));
                    else if (!g.isEmpty()) food = g;
                }
                if (nm.isEmpty() || Medicine.times(rest).length() == 0) continue;
                org.json.JSONObject mj = new org.json.JSONObject().put("name", nm).put("times", rest.trim()).put("food", food);
                // a count goes only when he wrote a new one (the tablet counts down as she takes them)
                String sk = "stock_sent_" + nm.toLowerCase(java.util.Locale.ROOT);
                if (stock >= 0 && ts.getInt(sk, -1) != stock) { mj.put("stock", stock); stockNow.put(sk, stock); }
                ma.put(mj);
            }
            j.put("meds", ma);
        } catch (Exception ex) {
            setTabSent("పంపలేకపోయాను: " + ex.getMessage());
            return;
        }
        setTabSent("పంపుతున్నాను…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String r = HomeLink.sendSettings(app, j);
            if (r.startsWith("పంపాను")) { // now the tablet has these
                android.content.SharedPreferences.Editor e = ts.edit();
                for (java.util.Map.Entry<String, String> s : sentNow.entrySet()) {
                    String key = s.getKey(), v = s.getValue();
                    if (TAB_INTS.contains(key)) e.putInt(key, Integer.parseInt(v));
                    else if (TAB_BOOLS.contains(key)) e.putBoolean(key, Boolean.parseBoolean(v));
                    else e.putString(key, v);
                }
                for (java.util.Map.Entry<String, Integer> s : stockNow.entrySet()) e.putInt(s.getKey(), s.getValue());
                boolean same = true; // (the draft goes when it is just what went)
                try {
                    org.json.JSONObject d = new org.json.JSONObject(ts.getString("draft", "{}"));
                    for (java.util.Map.Entry<String, String> s : sentNow.entrySet()) if (d.has(s.getKey()) && !d.optString(s.getKey()).equals(s.getValue())) same = false;
                } catch (Exception ignored) {}
                if (same || leaving) e.remove("draft"); // (leaving: what went is what he left with, cleaned)
                long sentAt = System.currentTimeMillis();
                e.putLong("sent_at", sentAt);
                e.apply();
                runOnUiThread(() -> tabSeenSent = sentAt);
            }
            runOnUiThread(() -> {
                if (leaving || isFinishing() || isDestroyed()) Toast.makeText(app, "🏠 టాబ్లెట్: " + r, Toast.LENGTH_LONG).show();
                if (isDestroyed()) return;
                setTabSent(r);
                tabChanged();
                summarizeShown();
                refreshTiles();
            });
        }, "tab-send").start();
    }

    /** Leaving Settings (✕ or back on the first screen): changes for the tablet not sent yet are asked about once. */
    private void leave() {
        autosave();
        java.util.List<String> ch = tabChangedCards();
        if (ch.isEmpty() || askedLeave) { finish(); return; }
        askedLeave = true;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📤 టాబ్లెట్‌కి ఇంకా పంపలేదు")
                .setMessage("అమ్మగారి టాబ్లెట్ సెట్టింగ్స్‌లో మీరు మార్చినవి (" + tabShorts(ch) + ") ఇంకా టాబ్లెట్‌కి వెళ్లలేదు. ఇప్పుడే పంపమంటారా?\n\n"
                        + "\"తర్వాత\" అంటే మీరు రాసినవి ఇక్కడే ఉంటాయి; తర్వాత పంపొచ్చు.")
                .setPositiveButton("📤 పంపు", (d, w) -> { sendTablet(true); finish(); })
                .setNegativeButton("తర్వాత", (d, w) -> finish())
                .setOnCancelListener(d -> askedLeave = false)
                .show();
    }

    /** The one-line state of the tablet cards (with "● ఇంకా పంపలేదు" first when that card has changes not sent). */
    private String tabSummary(String t) {
        if (t.equals(TAB_TIMES)) return tabUnsentMark(t) + "టిఫిన్ " + tabValue("tiffin") + " · భోజనం " + tabValue("lunch") + " · రాత్రి " + tabValue("dinner");
        if (t.equals(TAB_MEDS)) {
            int n = 0;
            for (String line : tabValue("meds_text").split("\n")) if (line.indexOf('=') > 0) n++;
            return tabUnsentMark(t) + (n == 0 ? "మాత్రల లిస్ట్ ఖాళీ" : n + " మాత్రలు") + " · షుగర్ " + tabValue("sugar_low") + "–" + tabValue("sugar_high");
        }
        if (t.equals(TAB_MSG)) return "టాబ్లెట్ Jarvis అమ్మకి చదివి వినిపిస్తాడు";
        if (t.equals(TAB_FUN)) return "చదరంగం, లూడో… · ఫోటో ఫ్రేమ్ · వారపు PDF";
        if (t.equals(TAB_DEVICE)) return tabUnsentMark(t) + "గొంతు: " + tabVoice[0] + " · " + roomLabel(tabRoom[0]);
        return null;
    }

    /** The tablet's photo frame: his gallery opens (photos are sent when he picks them). */
    private void pickFramePhotos() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FRAME);
        } catch (Exception ex) {
            Toast.makeText(this, "ఫోటోలు ఎంచుకునే యాప్ తెరవలేకపోయాను", Toast.LENGTH_LONG).show();
        }
    }

    private static final int REQ_FRAME = 46; // (44 is the backup file's; 41-45 are taken)
    private TextView frameInfo;

    /** "రేపు 10:00", "ఈరోజు 18:30", "10:00" (today, else tomorrow), "2026-10-12 09:00" → millis; -1 when it can't be read. */
    static long whenOf(String s, long now) {
        String t = s == null ? "" : s.trim().replace('.', ':');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})").matcher(t);
        java.util.Calendar k = java.util.Calendar.getInstance();
        k.setTimeInMillis(now);
        k.set(java.util.Calendar.SECOND, 0);
        k.set(java.util.Calendar.MILLISECOND, 0);
        if (m.find()) {
            k.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            return k.getTimeInMillis() > now ? k.getTimeInMillis() : -1;
        }
        m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return -1;
        k.set(java.util.Calendar.HOUR_OF_DAY, h);
        k.set(java.util.Calendar.MINUTE, min);
        if (t.contains("ఎల్లుండి")) k.add(java.util.Calendar.DAY_OF_MONTH, 2);
        else if (t.contains("రేపు")) k.add(java.util.Calendar.DAY_OF_MONTH, 1);
        else if (!t.contains("ఈరోజు") && !t.contains("ఈ రోజు") && k.getTimeInMillis() <= now) k.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis() > now ? k.getTimeInMillis() : -1;
    }

    /** The photos he picked for the tablet's frame: made small (1600 px) and sent one by one (background). */
    private void sendFramePhotos(java.util.List<Uri> uris) {
        if (frameInfo != null) frameInfo.setText("ఫోటోలు సిద్ధం చేస్తున్నాను…");
        new Thread(() -> {
            java.util.List<byte[]> jpgs = new java.util.ArrayList<>();
            for (Uri u : uris) {
                byte[] b = smallJpeg(u);
                if (b != null) jpgs.add(b);
                if (jpgs.size() >= 20) break; // (twenty at a time)
            }
            if (jpgs.isEmpty()) { runOnUiThread(() -> frameInfo.setText("ఫోటోలు తెరవలేకపోయాను.")); return; }
            String r = HomeLink.sendFrame(getApplicationContext(), jpgs, line -> runOnUiThread(() -> frameInfo.setText(line)));
            runOnUiThread(() -> frameInfo.setText(r));
        }, "tab-frame").start();
    }

    private byte[] smallJpeg(Uri u) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            int side = Math.max(o.outWidth, o.outHeight), sample = 1;
            while (side / (sample * 2) >= 1600) sample *= 2;
            android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
            d.inSampleSize = sample;
            android.graphics.Bitmap b;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { b = android.graphics.BitmapFactory.decodeStream(in, null, d); }
            if (b == null) return null;
            int rot = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                int or = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            } catch (Exception ignored) {}
            float sc = Math.min(1f, 1600f / Math.max(b.getWidth(), b.getHeight()));
            android.graphics.Matrix mx = new android.graphics.Matrix();
            mx.postScale(sc, sc);
            mx.postRotate(rot);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bo);
            if (out != b) out.recycle();
            b.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The home Jarvis: this device (a tablet at home) shows the big home screen with the new Jarvis. Acts at once. */
    private void homeSection() {
        section("🏠 ఇంటి Jarvis (టాబ్లెట్)");
        note("ఇంట్లో ఉంచే టాబ్లెట్‌లో మాత్రమే ఆన్ చేయండి (మీ ఫోన్‌లో కాదు). ఆన్ చేస్తే: కొత్త Jarvis బొమ్మతో పెద్ద ఇంటి స్క్రీన్ (అడ్డంగా), "
                + "గడియారం, తెలుగు తేదీ, వాతావరణం, తర్వాతి రిమైండర్, మీ AC / ఫ్యాన్ బటన్లు (స్మార్ట్ హోమ్ లింక్‌లు). ఇంట్లో ఎవరు మాట్లాడినా జవాబిస్తాడు. "
                + "పగలు స్క్రీన్ ఆన్‌లోనే ఉంటుంది; రాత్రి 10 నుంచి ఉదయం 6 వరకు డిమ్, తర్వాత ఆరిపోవచ్చు ('Jarvis' అంటే / తాకితే వెలుగుతుంది). "
                + "టాబ్లెట్ ఆన్ అయినప్పుడు Jarvis తనంతట తానే తెరుచుకుంటుంది. 💬 నొక్కితే చాట్, 3 నిమిషాల తర్వాత మళ్లీ ఇంటి స్క్రీన్.");
        note("🔋 బ్యాటరీ కేర్: టాబ్లెట్ ఛార్జర్‌ని మీ Alexa స్మార్ట్ ప్లగ్‌లో పెట్టి, స్మార్ట్ హోమ్ బాక్స్‌లో \"charger on = లింక్\", \"charger off = లింక్\" అని రెండు లైన్లు పెట్టండి "
                + "(URL Routine Trigger లింక్‌లు, Alexa రొటీన్ → ఆ ప్లగ్ ఆన్ / ఆఫ్). 80% అయితే Jarvis ప్లగ్ ఆఫ్ చేస్తాడు, 40% కి వస్తే ఆన్. "
                + "నెట్ లేక 25% కి పడితే అమ్మగారికి \"ప్లగ్ బటన్ నొక్కండి\" అని చెబుతాడు. బ్యాకప్‌గా Alexa లో రోజూ ఒక టైమ్‌కి ప్లగ్ ఆన్ చేసే రొటీన్ కూడా పెట్టండి.");
        Switch on = toggle("ఈ డివైస్ ఇంటి Jarvis (టాబ్లెట్)", prefs.homeMode());
        final boolean[] asking = {false};
        on.setOnCheckedChangeListener((sw, v) -> {
            if (asking[0]) return;
            if (!v) {
                prefs.setHomeMode(false);
                Toast.makeText(this, "ఇంటి Jarvis ఆఫ్", Toast.LENGTH_SHORT).show();
                return;
            }
            // (on: asked first, so a tap by mistake on the phone doesn't turn the phone into the home tablet)
            asking[0] = true;
            sw.setChecked(false);
            asking[0] = false;
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("ఈ డివైస్ ఇంటి Jarvis అవ్వాలా?")
                    .setMessage("ఇది ఇంట్లో అమ్మగారి టాబ్లెట్‌కి మాత్రమే. ఆన్ చేస్తే ఈ డివైస్ పెద్ద ఇంటి స్క్రీన్, భోజనం / టాబ్లెట్ల ప్రశ్నలు, "
                            + "బ్యాటరీ కేర్ మొదలుపెడుతుంది; ఫోన్ కాపలా లింక్ ఆగిపోతుంది. మీ ఫోన్‌లో అయితే వద్దు నొక్కండి.")
                    .setPositiveButton("అవును, ఇది టాబ్లెట్", (d, w) -> {
                        prefs.setHomeMode(true);
                        asking[0] = true;
                        sw.setChecked(true);
                        asking[0] = false;
                        Toast.makeText(this, "ఇంటి Jarvis ఆన్: వెనక్కి వెళ్తే ఇంటి స్క్రీన్ వస్తుంది", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("వద్దు", null)
                    .show();
        });
        final String[] looks = {"a", "b", "c"}, lookNames = {"A · స్మార్ట్ జాకెట్", "B · కుర్తా", "C · క్యాజువల్ (మీ ఎంపిక)"};
        final String[] skins = {"1", "2", "3"}, skinNames = {"లేత", "మధ్యస్థం", "ముదురు"};
        TextView look = Ui.text(this, "", 15.5f, accent);
        look.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        TextView skin = Ui.text(this, "", 15.5f, accent);
        skin.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        Runnable show = () -> {
            int li = java.util.Arrays.asList(looks).indexOf(prefs.bodyLook()), si = java.util.Arrays.asList(skins).indexOf(prefs.bodySkin());
            look.setText("🧑 Jarvis రూపం: " + lookNames[li < 0 ? 2 : li] + "  (మార్చడానికి నొక్కండి)");
            skin.setText("🎨 చర్మం రంగు: " + skinNames[si < 0 ? 1 : si] + "  (మార్చడానికి నొక్కండి)");
        };
        show.run();
        look.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Jarvis రూపం")
                .setSingleChoiceItems(lookNames, Math.max(0, java.util.Arrays.asList(looks).indexOf(prefs.bodyLook())), (d, w) -> {
                    prefs.sp.edit().putString("body_look", looks[w]).apply();
                    show.run();
                    d.dismiss();
                }).show());
        skin.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("చర్మం రంగు")
                .setSingleChoiceItems(skinNames, Math.max(0, java.util.Arrays.asList(skins).indexOf(prefs.bodySkin())), (d, w) -> {
                    prefs.sp.edit().putString("body_skin", skins[w]).apply();
                    show.run();
                    d.dismiss();
                }).show());
        box.addView(look);
        box.addView(skin);
        if (!prefs.homeMode()) return;
        // (the home tablet itself: where it stands, its camera, the photo frame)
        android.content.SharedPreferences hc = HomeCare.sp(this);
        TextView room = Ui.text(this, "", 15.5f, accent);
        room.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        Runnable showRoom = () -> room.setText("📍 టాబ్లెట్ ఉన్న చోటు: " + roomLabel(HomeEyes.room(this)) + "  (మార్చడానికి నొక్కండి)");
        showRoom.run();
        room.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("టాబ్లెట్ ఎక్కడ ఉంది?")
                .setSingleChoiceItems(ROOM_NAMES, HomeEyes.inHall(this) ? 0 : 1, (d, w) -> {
                    hc.edit().putString("room", w == 0 ? "hall" : "bedroom").apply();
                    showRoom.run();
                    d.dismiss();
                }).show());
        box.addView(room);
        Switch cam = toggle("📷 కెమెరాతో చూడటం: నవ్వితే నవ్వు, కన్ను కొడితే కన్ను, చేయి ఊపితే హాయ్, కొత్తవాళ్లు (పగలు మాత్రమే)", HomeEyes.camOn(this));
        cam.setOnCheckedChangeListener((sw, v) -> HomeEyes.setCam(this, v));
        Switch frameOn = toggle("🖼️ ఎవరూ లేనప్పుడు ఫోటో ఫ్రేమ్ (" + HomeFrame.count(this) + " ఫోటోలు)", hc.getBoolean("frame_on", true));
        frameOn.setOnCheckedChangeListener((sw, v) -> hc.edit().putBoolean("frame_on", v).apply());
        note("హాల్‌లో ఉంటే: Jarvis మాట్లాడేటప్పుడు TV సౌండ్ తగ్గుతుంది (స్మార్ట్ హోమ్ బాక్స్‌లో \"tv quiet = లింక్\", \"tv back = లింక్\" లైన్లు పెడితే). "
                + "బెడ్‌రూమ్‌లో ఉంటే TV కంట్రోల్ ఆఫ్. స్క్రీన్ ఎప్పుడూ ఆరిపోదు: ఎవరూ లేనప్పుడు వెలుతురు తగ్గుతుంది, రాత్రి కంటికి ఇబ్బంది లేకుండా చాలా తక్కువగా, వెచ్చని రంగుతో ఉంటుంది.");
        pinSection();
    }

    /** 🚗 The car Jarvis: this device is the Android box in his car (CarBox, CarScreen). Acts at once. */
    private void boxSection() {
        section("🚗 కారు Jarvis (కారులోని Android box)");
        note("కారులో పెట్టిన Android box (Carlinkit లాంటిది) లో మాత్రమే ఆన్ చేయండి, మీ ఫోన్‌లో కాదు. ఆన్ చేస్తే: కారు స్క్రీన్‌కి పెద్ద బటన్ల Jarvis "
                + "(🎤 మాట్లాడు, 🧭 దారి, 🎵 పాటలు, ⛽ డీజిల్, 🏠 ఇల్లు, 😴 నిద్ర), కారు mic తో \"Jarvis\" ఎప్పుడూ వింటాడు, కారు speakers లో మాట్లాడతాడు, "
                + "కారు స్టార్ట్ అవ్వగానే తనంతట తానే వచ్చి \"శుభ ప్రయాణం\" అంటాడు, ప్రయాణం అంతా నిద్ర అలర్ట్, స్పీడ్. "
                + "కాల్స్, మెసేజ్‌లు, డబ్బు, రిమైండర్లు, ఆరోగ్యం box లో ఉండవు (మీ ఫోన్‌లోనే). డీజిల్ చెబితే మీ ఫోన్‌లోని కారు లెక్కకి వెళ్తుంది (కింద 📮 లింక్ కావాలి).");
        Switch on = toggle("ఈ డివైస్ కారు Jarvis (కారులోని box)", prefs.carMode());
        final boolean[] asking = {false};
        on.setOnCheckedChangeListener((sw, v) -> {
            if (asking[0]) return;
            if (!v) {
                prefs.setCarMode(false);
                try { CarCare.stop(this, "box"); } catch (Exception ignored) {} // (its sleep alert's car mode ends with it)
                Toast.makeText(this, "కారు Jarvis ఆఫ్", Toast.LENGTH_SHORT).show();
                return;
            }
            asking[0] = true;
            sw.setChecked(false);
            asking[0] = false;
            if (prefs.homeMode()) { Toast.makeText(this, "ఇది ఇంటి టాబ్లెట్: ముందు ఇంటి Jarvis ఆఫ్ చేయండి", Toast.LENGTH_LONG).show(); return; }
            // (asked first: a tap by mistake on his phone must not turn the phone into the car's box)
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("ఈ డివైస్ కారు Jarvis అవ్వాలా?")
                    .setMessage("ఇది కారులో పెట్టిన Android box కి మాత్రమే. ఆన్ చేస్తే ఇక్కడ కాల్స్, మెసేజ్‌లు, డబ్బు, రిమైండర్ల పనులు ఆగిపోతాయి, "
                            + "\"Jarvis\" ఎప్పుడూ వింటాడు. మీ ఫోన్‌లో అయితే వద్దు నొక్కండి.")
                    .setPositiveButton("అవును, ఇది కారు box", (d, w) -> {
                        prefs.setCarMode(true);
                        prefs.sp.edit().putBoolean("wake", true).putString("wake_when", "always").apply(); // (the car's mic hears "Jarvis" the whole trip)
                        if (wake != null) wake.setChecked(true); // (this screen saves its own switches when left: they must say the same)
                        if (wakeWhen != null) wakeWhen.check(23);
                        asking[0] = true;
                        sw.setChecked(true);
                        asking[0] = false;
                        Toast.makeText(this, "కారు Jarvis ఆన్: వెనక్కి వెళ్తే కారు స్క్రీన్ వస్తుంది", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("వద్దు", null)
                    .show();
        });
        if (!prefs.carMode()) return;
        carLookSection(); // 🎨 the car screen's theme, his car's colour, the dance, Jarvis's look
        TextView st = Ui.text(this, "", 14, Ui.MUTED);
        st.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.addView(st);
        String wait = CarLink.waitingLine(this);
        st.setText("🚗 " + CarBox.speedWords() + " · " + CarBox.linkLine(this) + (wait.isEmpty() ? "" : " · " + wait));
        button("🖥️ \"ఇతర యాప్‌ల మీద చూపించు\" అనుమతి (కారు స్టార్ట్‌తో Jarvis రావడానికి)", v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { Toast.makeText(this, "ఈ box లో ఆ పేజీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); }
        });
        button("📍 లొకేషన్ అనుమతి (స్పీడ్, దారి, నిద్ర అలర్ట్)", v -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
            else Toast.makeText(this, "✓ లొకేషన్ అనుమతి ఉంది", Toast.LENGTH_SHORT).show();
        });
        button("🔊 నిద్ర అలర్ట్ గొంతు టెస్ట్", v -> Announcer.sayPhone(this, prefs.name() + ", మెలకువగా ఉన్నారా అని ఇలా అడుగుతాను."));
        note("ఈ గొంతు వినిపించకపోతే: box సెట్టింగ్స్ → System → Languages → Text-to-speech → Google ఎంచుకుని, తెలుగు వాయిస్ డేటా install చేయండి. "
                + "(నిద్ర అలర్ట్ నెట్ లేకపోయినా అడగాలని ఈ గొంతు.)");
        button("🔔 అలారం శబ్దం టెస్ట్ (కారు speakers లో)", v -> CarCare.testTone(this));
        // 🔌 C11: the OBD adapter (connected once in the scanner: then every trip by itself)
        String obdName = prefs.sp.getString("obd_name", "");
        TextView obdLine = Ui.text(this, ObdWatch.adapter(this).isEmpty() ? "🔌 OBD అడాప్టర్: ఇంకా కలపలేదు"
                : "🔌 OBD అడాప్టర్: " + obdName + (ObdWatch.live() ? " · " + ObdWatch.chip() : ObdWatch.status.isEmpty() ? "" : " · " + ObdWatch.status), 14, Ui.MUTED);
        obdLine.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(obdLine);
        button("🔌 OBD అడాప్టర్ కలుపు (ఇంజిన్ వేడి, బ్యాటరీ, చెక్-ఇంజిన్, డీజిల్ లెవెల్)", v -> startActivity(new Intent(this, ObdActivity.class)));
        note("ELM327 Bluetooth (classic, PIN 1234) అడాప్టర్‌ని స్టీరింగ్ కింద OBD పోర్ట్‌లో పెట్టి, box Bluetooth లో ఒక్కసారి జత చేసి, ఇక్కడ \"కనెక్ట్\" నొక్కండి. "
                + "తర్వాత ప్రతి ప్రయాణంలో కారు Jarvis దాన్ని తనే కలిపి చూస్తుంటాడు: ఇంజిన్ 105° దాటితే, బ్యాటరీ ఛార్జ్ అవ్వకపోతే, చెక్-ఇంజిన్ లైట్ వెలిగితే చెబుతాడు. కోడ్‌లు ఇక్కడ ఎప్పుడూ తీసేయడు.");
        // ⛽ C12: the tank and the mileage for the range
        TextView fuelLine = Ui.text(this, CarFuel.settingsLine(this), 14, Ui.MUTED);
        fuelLine.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(fuelLine);
        button("⛽ ట్యాంక్ లీటర్లు / మైలేజ్ మార్చు", v -> {
            LinearLayout f = new LinearLayout(this);
            f.setOrientation(LinearLayout.VERTICAL);
            int pd = Ui.dp(this, 16);
            f.setPadding(pd, pd / 2, pd, 0);
            EditText tankBox = new EditText(this), kmplBox = new EditText(this);
            tankBox.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            kmplBox.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            tankBox.setHint("ట్యాంక్ లీటర్లు (Nexon డీజిల్: 44)");
            kmplBox.setHint("మైలేజ్ కి.మీ/లీ (ఫోన్ లెక్క వచ్చే వరకు)");
            tankBox.setText(trimZero(CarFuel.tank(this)));
            f.addView(tankBox);
            f.addView(kmplBox);
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("⛽ కారు ట్యాంక్").setView(f)
                    .setPositiveButton("సేవ్", (d, w) -> {
                        try { float t = Float.parseFloat(tankBox.getText().toString().trim()); if (t >= 20 && t <= 90) CarFuel.setTank(this, t); } catch (Exception ignored) {}
                        try { float k = Float.parseFloat(kmplBox.getText().toString().trim()); if (k > 3 && k < 40) CarFuel.setKmpl(this, k); } catch (Exception ignored) {}
                        fuelLine.setText(CarFuel.settingsLine(this));
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        note("డీజిల్ ఎంత వరకు వస్తుందో: OBD adapter ఉంటే కారు చెప్పే లెవెల్‌తో; లేకపోతే box కి చెప్పిన ఫిల్ (ఫుల్ = ట్యాంక్ నిండా) నుంచి box లెక్కించిన కి.మీ తీసేసి. "
                + "ఎప్పుడూ \"సుమారు\" మాత్రమే: కారు డాష్‌బోర్డ్‌లో చూపేదే నమ్మండి. box లెక్కించిన రోజు కి.మీ రోజుకి ఒకసారి ఫోన్ కారు లెక్కకి వెళ్తాయి (కి.మీ మాత్రమే, ఎక్కడికి వెళ్ళారో కాదు).");
        note("🔑 Keys ఈ box లోనే మీరే పెట్టండి (chat లో, ఫోటోలో ఎప్పుడూ వద్దు). ఈ box కోసం OpenAI లో వేరే key తీసి, నెల limit పెట్టడం మంచిది. "
                + "మీ ఫోన్ బ్యాకప్ ఈ box లో restore చేయకండి, box లో బ్యాకప్ కూడా పెట్టకండి. "
                + "ఫోన్‌లో: ⚙️ → 🚗 కారు నిద్ర అలర్ట్ → \"కారులో box Jarvis ఉంది\" ఆన్ చేస్తే, కారులో ఫోన్, box రెండూ ఒకేసారి జవాబివ్వవు.");
    }

    /** The home tablet: the 4-digit PIN in front of Settings (TabletLock). */
    private void pinSection() {
        section("🔐 సెట్టింగ్స్ లాక్ (ఈ టాబ్లెట్)");
        TextView st = Ui.text(this, "", 15.5f, Ui.TEXT);
        st.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.addView(st);
        Runnable show = () -> st.setText(TabletLock.has(this) ? "🔐 PIN ఉంది: ⚙️ నొక్కితే PIN అడుగుతుంది" : "🔓 PIN లేదు: ⚙️ ని నొక్కి పట్టుకుంటేనే సెట్టింగ్స్ తెరుచుకుంటుంది");
        show.run();
        button("🔐 PIN పెట్టు / మార్చు", v -> PinPad.set(this, ok -> {
            show.run();
            if (ok) { TabletLock.opened(); Toast.makeText(this, "PIN పెట్టాను ✓ గుర్తుంచుకోండి", Toast.LENGTH_LONG).show(); }
        }));
        button("🔓 PIN తీసేయి", v -> {
            if (!TabletLock.has(this)) { Toast.makeText(this, "PIN లేదు", Toast.LENGTH_SHORT).show(); return; }
            PinPad.check(this, "ఇప్పటి PIN", ok -> {
                if (ok) { TabletLock.clear(this); TabletLock.opened(); show.run(); Toast.makeText(this, "PIN తీసేశాను", Toast.LENGTH_SHORT).show(); }
            });
        });
        note("4 అంకెల PIN: అమ్మగారు పొరపాటున సెట్టింగ్స్‌లోకి (మీ keys ఉన్న చోటికి) వెళ్ళకుండా. PIN నంబర్ ఎక్కడా దాచబడదు (దాని గుర్తు మాత్రమే). "
                + "5 సార్లు తప్పు అయితే కాసేపు ఆగాలి. మర్చిపోతే: మీ ఫోన్ Jarvis → ⚙️ → 📮 ఫోన్ ↔ టాబ్లెట్ లింక్ → \"🔐 టాబ్లెట్ PIN తీసేయి\".");
    }

    private static final String[] ROOM_NAMES = {"హాల్ (TV దగ్గర)", "అమ్మగారి బెడ్‌రూమ్"};

    private static String roomLabel(String room) {
        return "bedroom".equals(room) ? "అమ్మగారి బెడ్‌రూమ్ (TV కంట్రోల్ ఆఫ్)" : "హాల్, TV దగ్గర (Jarvis మాట్లాడేటప్పుడు TV సౌండ్ తగ్గుతుంది)";
    }

    /** Both devices: joining his phone and the home tablet through his bot (token, chat, QR). Acts at once. */
    private void linkSection() {
        section(prefs.carMode() ? "📮 ఫోన్ ↔ కారు box లింక్" : "📮 ఫోన్ ↔ టాబ్లెట్ లింక్"); // (the box: in the car group, by its car card)
        note(prefs.homeMode()
                ? "అబ్బాయి ఫోన్‌తో కలపడానికి సులువైన దారి: \"📷 ఫోన్ QR స్కాన్ చేయి\" నొక్కి, అతని ఫోన్‌లో వచ్చే QR ని కెమెరాకి చూపించండి. లేదా అదే bot token కింద పెట్టి \"Telegram చాట్ కనుక్కో\" నొక్కండి."
                : prefs.carMode()
                ? "కారు box ని మీ ఫోన్‌తో కలపడానికి: మీ ఫోన్‌లో ఉన్న అదే bot token కింద పెట్టి (box లోనే టైప్ / paste చేయండి), \"Telegram చాట్ కనుక్కో\" నొక్కండి. "
                        + "అప్పుడు డీజిల్ ఫోన్ కారు లెక్కకి, మీ రాక టైమ్ అమ్మగారి టాబ్లెట్‌కి వెళ్తాయి."
                : "ఇంటి టాబ్లెట్‌తో కలపడానికి: ముందు ఇక్కడ మీ bot token పెట్టి \"Telegram చాట్ కనుక్కో\" నొక్కండి. తర్వాత \"🔲 QR చూపించు\" నొక్కి, టాబ్లెట్‌లో \"📷 ఫోన్ QR స్కాన్ చేయి\" తో స్కాన్ చేయించండి: టాబ్లెట్‌లో ఏమీ టైప్ చేయక్కర్లేదు. "
                        + "(పాత ఫోన్ కాపలా అవసరం లేదు; ఇది అదే bot.)");
        EditText tok = field("Telegram bot token", Guard.token(this), true);
        TextView st = Ui.text(this, "", 14, Ui.MUTED);
        st.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(st);
        Runnable show = () -> st.setText(Guard.chat(this).isEmpty() ? "Telegram చాట్: ఇంకా లేదు" : "Telegram చాట్: " + Guard.sp(this).getString("tg_name", "సిద్ధం") + " ✓");
        show.run();
        button("Telegram చాట్ కనుక్కో", v -> {
            Guard.setToken(this, tok.getText().toString());
            new Thread(() -> {
                String r = Guard.findChat(this);
                runOnUiThread(() -> { show.run(); Toast.makeText(this, r.startsWith("!") ? r.substring(1) : "దొరికింది: " + r + " ✓", Toast.LENGTH_LONG).show(); });
            }, "link-find").start();
        });
        button("టెస్ట్ మెసేజ్ పంపు", v -> {
            Guard.setToken(this, tok.getText().toString());
            new Thread(() -> {
                boolean ok = Guard.send(this, prefs.homeMode() ? "✅ ఇంటి టాబ్లెట్ నుంచి టెస్ట్: లింక్ పనిచేస్తోంది."
                        : prefs.carMode() ? "✅ కారు box నుంచి టెస్ట్: లింక్ పనిచేస్తోంది." : "✅ ఫోన్ నుంచి టెస్ట్: లింక్ పనిచేస్తోంది.");
                runOnUiThread(() -> Toast.makeText(this, ok ? "పంపాను ✓ Telegram చూడండి" : "పంపలేకపోయాను: token / చాట్ చూడండి", Toast.LENGTH_LONG).show());
            }, "link-test").start();
        });
        if (prefs.homeMode()) button("📷 ఫోన్ QR స్కాన్ చేయి", v -> JarvisCamera.open(this, "auto", null));
        else if (!prefs.carMode()) button("🔲 QR చూపించు (టాబ్లెట్ స్కాన్ చేయడానికి)", v -> QrPair.show(this));
        if (!prefs.homeMode() && !prefs.carMode()) button("🔐 టాబ్లెట్ PIN తీసేయి (మర్చిపోతే)", v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("ఇంటి టాబ్లెట్ సెట్టింగ్స్ PIN తీసేయమంటారా? తర్వాత టాబ్లెట్‌లో కొత్త PIN పెట్టుకోవచ్చు.")
                .setPositiveButton("తీసేయి", (d, w) -> {
                    final android.content.Context app = getApplicationContext();
                    new Thread(() -> {
                        String r;
                        try { r = HomeLink.sendSettings(app, new org.json.JSONObject().put("settings_pin_clear", true)); }
                        catch (Exception e) { r = "పంపలేకపోయాను"; }
                        final String said = r;
                        runOnUiThread(() -> Toast.makeText(this, said, Toast.LENGTH_LONG).show());
                    }, "tab-pin-clear").start();
                })
                .setNegativeButton("వద్దు", null).show());
    }

    // ================================================================ ⌚ the watch: its own group, six small cards

    /** The watch's cards (every title has "వాచ్": groupOf puts them in the ⌚ group). */
    static final String W_LINK = "వాచ్ కనెక్షన్, రూపం, వాచ్‌లో పెట్టడం", W_TALK = "వాచ్‌లో వినడం, మాట్లాడటం",
            W_ALERTS = "వాచ్ అలర్ట్స్, వైబ్రేషన్", W_DAY = "వాచ్‌లో రోజువారీ పనులు", W_HEALTH = "ఆరోగ్యం: వాచ్, Samsung Health",
            W_ROAD = "రోడ్డు, భద్రత (వాచ్)";

    /** Phase 2a onwards: the Jarvis watch app (Galaxy Watch). Each choice acts at once and goes to the watch; no Save needed. */
    private void watchSection() {
        // ---- 1. the watch itself: is it there, a test, its look, putting Jarvis on it
        section(W_LINK, "⌚");
        TextView st = Ui.text(this, WatchHub.status(this), 14, Ui.MUTED);
        st.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(st);
        WatchHub.send(this, WatchHub.P_PING, new byte[0]); // the watch answers with its news
        st.postDelayed(() -> {
            if (isDestroyed()) return;
            st.setText(WatchHub.status(this));
            if ("home".equals(mode)) refreshTiles();
        }, 3000);
        TextView testInfo = Ui.text(this, "", 14, Ui.MUTED);
        button("📳 వాచ్ టెస్ట్ (వాచ్ వైబ్రేట్ అయి కార్డ్ చూపిస్తుంది)", v -> {
            testInfo.setText("వాచ్‌కి పంపుతున్నాను…");
            WatchAlerts.test(this,
                    () -> { if (!isDestroyed()) testInfo.setText("✗ వాచ్ దగ్గర్లో కనెక్ట్ అయి లేదు: వాచ్ Bluetooth, Galaxy Wearable యాప్ చూడండి."); },
                    () -> {
                        if (isDestroyed()) return;
                        testInfo.setText("✓ వాచ్‌కి వెళ్లింది: వాచ్ వైబ్రేట్ అయి \"📳 Jarvis టెస్ట్\" కార్డ్ కనిపించాలి (20 సెకన్లలో పోతుంది). కనిపించకపోతే వాచ్‌లో Jarvis ఒకసారి తెరవండి.");
                        WatchHub.send(this, WatchHub.P_PING, new byte[0]);
                        st.postDelayed(() -> { if (!isDestroyed()) { st.setText(WatchHub.status(this)); if ("home".equals(mode)) refreshTiles(); } }, 3000);
                    });
        });
        testInfo.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(testInfo);
        TextView look = Ui.text(this, "", 15.5f, Ui.CYAN);
        look.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        look.setText("🙂 వాచ్‌లో Jarvis: " + WatchHub.lookText(WatchHub.look(this)) + "  (మార్చడానికి నొక్కండి)");
        look.setOnClickListener(v -> {
            final String[] looks = {"orb", "holo", "human"};
            String[] names = new String[looks.length];
            int checked = 0;
            for (int i = 0; i < looks.length; i++) { names[i] = WatchHub.lookText(looks[i]); if (looks[i].equals(WatchHub.look(this))) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("వాచ్‌లో Jarvis ఎలా కనిపించాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        WatchHub.set(this, "look", looks[w]);
                        look.setText("🙂 వాచ్‌లో Jarvis: " + WatchHub.lookText(looks[w]) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(look);
        note("వాచ్ రంగులు ఫోన్ థీమ్‌నే అనుసరిస్తాయి (👤 నేను, లుక్ → 'థీమ్ (రంగులు)'). Jarvis వాచ్ ఫేస్ (Jarvis-face.apk): సమయం, బ్యాటరీ, అడుగులు, గుండె వేగం వలయాలు, "
                + "తదుపరి డ్యూటీ, వాతావరణం; ఎప్పుడూ ఆన్ (AOD) లో మసకగా. దాన్ని కూడా Jarvis-watch.apk లాగే Bugjaeger తో ఇన్‌స్టాల్ చేసి, వాచ్ ఫేస్ మీద నొక్కి పట్టుకుని "
                + "Jarvis ఎంచుకోండి; రంగు ఫేస్ Customize లో. డ్యూటీ, వాతావరణం ఫోన్ నుంచి అరగంటకి ఒకసారి వస్తాయి.");
        link("⬇ Jarvis-face.apk (వాచ్ ఫేస్, తాజా వెర్షన్)", "https://github.com/anil6836/jarvis-android/releases/latest/download/Jarvis-face.apk");
        note("వాచ్ Jarvis స్క్రీన్‌లో కింద బటన్లు: ☀️ ఈరోజు, 📊 స్టేటస్, 🏍️ డ్యూటీ (కౌంట్‌డౌన్, బయలుదేరే టైమ్, బ్యాగ్ టిక్, హ్యాండోవర్ నోట్), "
                + "✅ పనులు (అలవాట్లు, మిషన్లు, షాపింగ్ లిస్ట్ ఒక్క నొక్కుతో), 💡 ఇల్లు (మీ స్మార్ట్ హోమ్ లింక్‌లు), ⏱️ టైమర్, 🍲 కుక్కర్, "
                + "📝 నోట్ (ఒక్క మాటలో: ఖర్చు, ఎక్కడ పెట్టారో, ఎవరికి ఇచ్చారో, రిమైండర్, నోట్), 🌐 అనువాదం (ఎవరైనా హిందీ / ఇంగ్లీష్‌లో మాట్లాడితే వాచ్‌లో తెలుగులో).");
        note("వాచ్ Jarvis స్క్రీన్‌లో కొత్త బటన్లు: 🧭 దారి, 🎵 పాటలు, 📻 రేడియో, 🖼️ ఫోటోలు, 📷 కెమెరా, 🎙️ రికార్డ్, 💧 నీళ్లు, ⚡ ప్రోటోకాల్, 🛡️ నాతో ఉండు, "
                + "🎯 ఫోకస్, 🏋️ రెప్స్, 📔 డైరీ, 💰 ఖర్చు, 😴 నిద్ర, 📱 ఫోన్ ఎక్కడ?, 🩸 మెడికల్, 🆘 SOS (నొక్కి పట్టుకోండి). ఆర్బ్‌ని నొక్కి పట్టుకుంటే చుట్టూ బటన్లు (బెజెల్ తిప్పి ఎంచుకోండి). "
                + "కొత్త కాంప్లికేషన్లు: Jarvis ఒత్తిడి, Jarvis ఫోన్ బ్యాటరీ.");
        note("Jarvis టైల్: వాచ్ ఫేస్ మీద కుడివైపు స్వైప్ చేసి చివర \"+\" (Add tiles) → Jarvis. అందులో తర్వాతి డ్యూటీ, రిమైండర్, నిద్ర, "
                + "🎙️ / ☀️ / ⏱️ / 🍲 / 📊 బటన్లు.");
        note("బటన్‌తో పిలవడానికి: వాచ్ Settings → Advanced features → Customize keys → Home key \"Double press\" → Jarvis. "
                + "వాచ్ ఫేస్ మీద నొక్కి పట్టుకుని → Customize → షార్ట్‌కట్ ఉన్న చోట Jarvis ఎంచుకోండి.");
        note("వాచ్‌లో Jarvis పెట్టడం (ఒక్కసారి, Bugjaeger యాప్‌తో): 1) కింది లింక్‌తో ఫోన్‌లో Jarvis-watch.apk డౌన్‌లోడ్ చేయండి. "
                + "2) వాచ్: Settings → About watch → Software information → Software version మీద \"Developer mode turned on\" వచ్చే వరకు చాలా సార్లు నొక్కండి. "
                + "3) వాచ్: Settings → Developer options → ADB debugging ఆన్, Wireless debugging ఆన్ → Pair new device (కోడ్ కనిపిస్తుంది). "
                + "4) ఫోన్‌లో Bugjaeger: ఆ కోడ్‌తో pair చేసి connect చేయండి → Install APK → Jarvis-watch.apk. "
                + "5) వాచ్‌లో Jarvis తెరిచి మైక్‌కి Allow నొక్కండి. 6) అయ్యాక వాచ్‌లో ADB debugging, Wireless debugging ఆఫ్ చేయండి. "
                + "కొత్త వెర్షన్ వచ్చినప్పుడు ఇదే విధంగా పాతదాని మీదే ఇన్‌స్టాల్ చేయండి (ఫోన్ యాప్, వాచ్ యాప్ రెండూ ఒకే వెర్షన్ ఉండటం మంచిది).");
        link("⬇ Jarvis-watch.apk (తాజా వెర్షన్)", "https://github.com/anil6836/jarvis-android/releases/latest/download/Jarvis-watch.apk");

        // ---- 2. listening and talking on the watch
        section(W_TALK, "🎙️");
        Switch raise = toggle("✋ చేయి ఎత్తి \"Hey Jarvis\" / \"Jarvis\" అంటే వాచ్ వింటుంది", WatchHub.raiseOn(this));
        raise.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "raise", on));
        note("వాచ్ స్క్రీన్ వెలిగిన ప్రతిసారి (చేయి ఎత్తినప్పుడు) కొన్ని సెకన్లు మాత్రమే వింటుంది. ఆ శబ్దం Bluetooth ద్వారా మీ ఫోన్‌కే వెళ్తుంది; "
                + "\"Jarvis\" అన్నారా అని ఫోన్‌లోనే చూస్తుంది (బయటికి ఏదీ వెళ్లదు). \"Hey Jarvis\" ఎప్పుడూ పనిచేస్తుంది; \"Jarvis\" ఒక్క పదం ఫోన్‌లో ఆ పదం మోడల్ ఉంటేనే. "
                + "వాచ్‌కి చేయి ఎత్తి పిలిస్తే వాచ్ మాత్రమే జవాబిస్తుంది, ఫోన్ ఆగుతుంది.");
        Switch hours = toggle("", WatchHub.hoursOn(this));
        hours.setText("🕐 ఈ సమయంలో వాచ్ ఎప్పుడూ వింటూ ఉండు: " + hourText(WatchHub.fromHour(this)) + " నుంచి " + hourText(WatchHub.toHour(this)) + " వరకు (బ్యాటరీ ఎక్కువ)");
        hours.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "hours", on));
        TextView hoursPick = Ui.text(this, "  ⏱️ సమయం మార్చడానికి నొక్కండి", 14.5f, accent);
        hoursPick.setPadding(0, 0, 0, Ui.dp(this, 8));
        hoursPick.setOnClickListener(v -> pickHours(hours));
        box.addView(hoursPick);
        TextView ears = Ui.text(this, "", 15.5f, Ui.CYAN);
        ears.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        ears.setText("🎧 వాచ్‌లో మీ మాటలు రాసేది: " + WatchHub.earsText(WatchHub.ears(this)) + "  (మార్చడానికి నొక్కండి)");
        ears.setOnClickListener(v -> {
            final String[] modes = {"openai", "gemini", "watch"};
            String[] names = new String[modes.length];
            int checked = 0;
            for (int i = 0; i < modes.length; i++) { names[i] = WatchHub.earsText(modes[i]); if (modes[i].equals(WatchHub.ears(this))) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("వాచ్‌లో మీరు చెప్పింది ఎవరు రాయాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        WatchHub.set(this, "ears", modes[w]);
                        ears.setText("🎧 వాచ్‌లో మీ మాటలు రాసేది: " + WatchHub.earsText(modes[w]) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(ears);
        note("OpenAI / Gemini: మీ శబ్దం ఫోన్‌కి వచ్చి, ఫోన్ నుంచి మీరు ఎంచుకున్న AI కి వెళ్తుంది (మీ key, ఫోన్ వాయిస్ సెక్షన్‌లోని మోడల్). "
                + "వాచ్ Google: వాచ్‌లోనే Google వాయిస్ టైపింగ్ (ఉచితం; వాచ్‌లో అది ఉంటేనే, తెలుగు దానికి వస్తేనే). "
                + "నెట్ లేనప్పుడు ఏది ఎంచుకున్నా ఫోన్‌లోని Offline తెలుగు చెవులతో వింటాను (ఫోన్‌లో ఆ మోడల్ ఉంటే), జవాబులు offline పనులకే. "
                + "ఎప్పుడూ మీకు చెప్పకుండా వేరే AI కి మారను.");
        Switch lock = toggle("🔒 వాచ్‌లో కూడా నా గొంతుకి మాత్రమే పలుకు", WatchHub.lockOn(this));
        lock.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "lock", on));
        note("🔊 గొంతు, వినడం → వేక్ వర్డ్ సెక్షన్‌లో నేర్పిన మీ గొంతునే వాడుతుంది (వాచ్ మైక్ వేరు కాబట్టి కొంచెం సడలింపుతో). మీరు పిలిచినా వాచ్ పలకకపోతే ఇది ఆఫ్ చేయండి. "
                + "వాచ్‌పై నొక్కి మాట్లాడితే ఎప్పుడూ వింటుంది (మీ చేతిలోనే ఉంది కాబట్టి).");
        Switch speak = toggle("🔊 జవాబు వాచ్‌లో గొంతుతో చెప్పు (ఆఫ్: వాచ్‌పై రాత, వైబ్రేషన్ మాత్రమే)", WatchHub.speakOn(this));
        speak.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "speak", on));
        note("గొంతు ఫోన్‌లోని గొంతే (సహజ గొంతు ఆన్ అయితే OpenAI, లేకపోతే ఫోన్ తెలుగు గొంతు). వాచ్‌కి ఇయర్‌బడ్స్ ఉంటే వాటిలో, లేకపోతే వాచ్ స్పీకర్‌లో. "
                + "మీ ఇయర్‌ఫోన్స్ ఫోన్‌కి కనెక్ట్ అయి ఉంటే జవాబు ఫోన్ ఇయర్‌ఫోన్స్‌లోనే చెబుతాను (రాత వాచ్‌పై).");
        Switch stopV = toggle("✋ జవాబు చెబుతుండగా \"Jarvis\" అంటే ఆపి మీ మాట విను", WatchHub.stopVoice(this));
        stopV.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "stop_voice", on));
        note("అరచేతితో వాచ్ మూసినా (స్క్రీన్ ఆఫ్ అయితే), స్క్రీన్ నొక్కినా జవాబు ఆగుతుంది.");
        Switch openL = toggle("⌚ వాచ్‌లో Jarvis తెరవగానే వినడం మొదలుపెట్టు", WatchHub.openListen(this));
        openL.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "open_listen", on));

        // ---- 3. alerts and vibration on the wrist
        section(W_ALERTS, "🔔");
        Switch msgs = toggle("📲 ఫోన్ లాక్‌లో ఉన్నప్పుడు మెసేజ్‌లు, కాల్స్ వాచ్‌లో చెప్పి, అక్కడే జవాబు తీసుకో", WatchHub.msgsOn(this));
        msgs.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "msgs", on));
        note("మెసేజ్: \"Ravi నుంచి WhatsApp లో మెసేజ్ వచ్చింది. చదవమంటారా?\" అని వాచ్‌లో చెప్పి వింటుంది; \"చదువు\" అంటే చదువుతుంది, రిప్లై చెబితే చదివి వినిపించి "
                + "\"పంపమంటారా?\" అని అడుగుతుంది, మీరు \"పంపు\" అన్నాకే పంపుతుంది. కాల్: ఎవరి నుంచో చెప్పి \"ఎత్తు\" / \"కట్\" వింటుంది (కార్డ్ బటన్లు కూడా). "
                + "ఫోన్ మీ చేతిలో ఉన్నప్పుడు (అన్‌లాక్) ఎప్పటిలాగే ఫోన్‌లోనే. బైక్ నడుపుతున్నప్పుడు ఫోన్ / హెల్మెట్‌లోనే; వాచ్ వైబ్రేట్ మాత్రమే అవుతుంది.");
        Switch lockedOk = toggle("🔓 వాచ్ నుంచి అడిగితే ఫోన్ లాక్‌లో ఉన్నా కాల్ చేయడం, మెసేజ్‌కి రిప్లై (మీరు \"అవును\" / \"పంపు\" అన్నాకే)", WatchHub.lockedOk(this));
        lockedOk.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "locked_ok", on));
        note("ఈ రెండు మాత్రమే, వాచ్ మీ చేతికి ఉన్నప్పుడే (వాచ్ చెబుతుంది). మిగతావన్నీ (యాప్‌లు, ఫోటోలు, మెయిల్…) ఫోన్ అన్‌లాక్ చేశాకే. "
                + "ఆఫ్ చేస్తే కాల్, రిప్లై కూడా అన్‌లాక్ చేశాకే. \"నా గొంతుకి మాత్రమే\" కూడా ఆన్ చేస్తే ఇంకా సురక్షితం.");
        Switch alerts = toggle("🔔 Jarvis అలర్ట్స్ (కుక్కర్, తలుపు, మందులు, రిమైండర్లు, కాపలా…) వాచ్‌లో, ఒక్కోదానికి వేరే వైబ్రేషన్", WatchHub.alertsOn(this));
        alerts.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "alerts", on));
        note("వైబ్రేషన్ భాష: కాల్ = 3 పొడవు · మెసేజ్ = 2 చిన్న · కుక్కర్ = 3 వేగంగా · తలుపు = టక్-టక్ … టక్-టక్ · మందులు = 2 మెల్లగా · "
                + "రిమైండర్ = ఒక మధ్యస్థం, ఒక చిన్న · ప్రమాదం / కాపలా = 6 వేగంగా · ఫోన్ మర్చిపోతే = 3 బలంగా. వాచ్‌లో Jarvis స్క్రీన్ కిందకి తిప్పితే "
                + "\"వైబ్రేషన్ భాష\" లో ఒక్కోటి నొక్కి చూడొచ్చు. వాచ్‌లో Jarvis నోటిఫికేషన్లు రెండుసార్లు వస్తుంటే: ఫోన్‌లో Galaxy Wearable → వాచ్ సెట్టింగ్స్ → "
                + "నోటిఫికేషన్లు లో Jarvis ఆఫ్ చేయండి (Jarvis వాచ్ యాప్ వాటిని చూపిస్తుంది).");
        Switch alarm = toggle("⏰ Jarvis అలారం వాచ్‌లో వైబ్రేషన్‌తో మాత్రమే (మీకు మాత్రమే; 3 నిమిషాల్లో ఆపకపోతే ఫోన్ మోగుతుంది)", WatchHub.alarmOn(this));
        alarm.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "alarm", on));
        note("వాచ్ మీ చేతికి ఉండి, ఫోన్‌తో కనెక్ట్ అయి ఉంటేనే. డ్యూటీ తర్వాత పగలు పడుకున్నా అలాగే. \"ఆపు\" నొక్కాక శుభోదయం, వాతావరణం, డ్యూటీ వాచ్‌లోనే చెబుతుంది.");
        Switch lost = toggle("📱 ఫోన్ లేకుండా దూరం వెళ్తే వాచ్ వెంటనే చెప్పాలి (ఫోన్ చివరిగా ఎక్కడ ఉందో కూడా)", WatchHub.lostOn(this));
        lost.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "lost", on));
        Switch silentAuto = toggle("🔕 డ్యూటీ, మీటింగ్, Do Not Disturb లో వాచ్ జవాబులు రాతగా మాత్రమే", Day5.silentAuto(this));
        silentAuto.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "silent_auto", on));
        Switch silent = toggle("🔕 వాచ్ ఎప్పుడూ సైలెంట్ (రాత + వైబ్రేషన్ మాత్రమే)", Day5.silentOn(this));
        silent.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "silent", on));

        // ---- 4. the day on the watch
        section(W_DAY, "📅");
        Switch morning = toggle("☀️ ఉదయం వాచ్‌లో అలారం ఆపాక 30 సెకన్లలో ఈరోజు సంగతులు (డ్యూటీ, బ్యాగ్, రిమైండర్లు, మందులు, నిద్ర)", WatchHub.morningOn(this));
        morning.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "morning", on));
        Switch smart = toggle("⏰ స్మార్ట్ అలారం: అలారానికి 25 ని ముందు తేలిక నిద్రలో కదిలితే వాచ్ కొంచెం ముందే లేపుతుంది (అంచనా)", Day5.smartOn(this));
        smart.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "smart_alarm", on));
        Switch wTimer = toggle("⏱️ వాచ్‌లో అడిగిన టైమర్ వాచ్‌లోనే (అయిపోగానే చేతికి వైబ్రేషన్)", WatchHub.watchTimerOn(this));
        wTimer.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "watch_timer", on));
        Switch dutyAuto = toggle("🛡️ డ్యూటీ మొదలవగానే డ్యూటీ మోడ్ దానంతట అదే (ఫోన్ వైబ్రేట్, Jarvis మాటలు నోటిఫికేషన్లుగా మాత్రమే; డ్యూటీ అయ్యాక ఆఫ్)", DutyMode.auto(this));
        dutyAuto.setOnCheckedChangeListener((sw, on) -> DutyMode.setAuto(this, on));
        watchCalendar();
        Switch diary = toggle("📔 రాత్రి 9:30 కి వాచ్‌లో “ఈరోజు ఎలా గడిచింది?” (మూడ్ + 1 నిమిషం మాట)", WatchHub.sp(this).getBoolean("night_diary", true));
        diary.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "night_diary", on));
        Switch gest = toggle("👋 మణికట్టు సైగలు (స్క్రీన్ ఆన్‌లో ఉన్నప్పుడే): రెండుసార్లు తిప్పితే Jarvis వింటుంది; అలారంపై విదిలిస్తే 5 నిమిషాలు", WatchHub.sp(this).getBoolean("gestures", true));
        gest.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "gestures", on));
        Switch spend = toggle("💰 వాచ్‌లో ఈ నెల ఖర్చుల రింగ్ (బ్యాంక్ / UPI SMS నుంచి ఫోన్‌లోనే లెక్క; SMS ఏ AI కీ వెళ్లదు, అకౌంట్ నంబర్లు దాచను)", Day5.spendOn(this));
        spend.setOnCheckedChangeListener((sw, on) -> { WatchHub.set(this, "spend_ring", on); WatchAlerts.pushInfoSoon(this); });
        Switch med = toggle("🩸 మెడికల్ కార్డ్ వాచ్‌లో (బ్లడ్ గ్రూప్, అలర్జీ, కాంటాక్ట్; సహాయం చేసేవాళ్లకి)", Day5.medOn(this));
        med.setOnCheckedChangeListener((sw, on) -> { WatchHub.set(this, "med_watch", on); WatchAlerts.pushInfoSoon(this); });

        // ---- 5. health from the watch and Samsung Health
        section(W_HEALTH, "❤️");
        healthSection();

        // ---- 6. on the road, and safety
        section(W_ROAD, "🏍️");
        Switch rain = toggle("🌧️ అరగంటలో వర్షం వచ్చేలా ఉంటే చెప్పు (వాచ్‌కి కూడా; అంచనా)", Travel.rainOn(this));
        rain.setOnCheckedChangeListener((sw, on) -> Travel.set(this, "rain_soon", on));
        Switch storm = toggle("⛈️ వాచ్ బారోమీటర్‌తో గాలివాన హెచ్చరిక (నెట్ లేకపోయినా; అంచనా)", WatchHub.sp(this).getBoolean("storm", true));
        storm.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "storm", on));
        Switch turns = toggle("↪️ Maps తో నడిచేటప్పుడు మలుపులు వాచ్‌లో వైబ్రేషన్‌తో (ఎడమ = 2 చిన్నవి, కుడి = 1 పొడవు)", Travel.turnsOn(this));
        turns.setOnCheckedChangeListener((sw, on) -> Travel.set(this, "turn_buzz", on));
        Switch brk = toggle("🛑 పెద్ద రైడ్‌లో (75 ని; నిద్ర తక్కువైతే 45 ని) “5 నిమిషాలు ఆగండి” వాచ్ వైబ్రేషన్", Travel.rideBreakOn(this));
        brk.setOnCheckedChangeListener((sw, on) -> Travel.set(this, "ride_break", on));
        Switch dbl = toggle("💥 బైక్ దెబ్బ డబుల్ చెక్: వాచ్‌కి దెబ్బ తగలకపోతే ముందు చేతి మీద నెమ్మదిగా అడుగు (తర్వాత పూర్తి చెక్)", CrashAlert.doubleCheck(this));
        dbl.setOnCheckedChangeListener((sw, on) -> Travel.set(this, "crash_double", on));
        Switch fall = toggle("🆘 వాచ్‌కి గట్టిగా పడిపోయినట్టు అనిపిస్తే “బాగున్నారా?” (జవాబు లేకపోతే SOS; బ్యాటరీ కొంచెం ఎక్కువ)", WatchHub.fallOn(this));
        fall.setOnCheckedChangeListener((sw, on) -> WatchHub.set(this, "fall", on));
    }

    /** "📅 డ్యూటీ రోజులు ఫోన్ క్యాలెండర్‌లో" (the watch's calendar shows them too). */
    private void watchCalendar() {
        Switch cal = toggle("📅 డ్యూటీ రోజులు ఫోన్ క్యాలెండర్‌లో పెట్టు (వాచ్ క్యాలెండర్‌లో కూడా వస్తాయి; Jarvis తను పెట్టినవే మారుస్తుంది)", WatchExtras.calOn(this));
        cal.setOnCheckedChangeListener((sw, on) -> {
            if (Boolean.TRUE.equals(sw.getTag())) return; // (put back by Jarvis, not his tap)
            if (on && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, 66);
                sw.setTag(true); sw.setChecked(false); sw.setTag(null);
                Toast.makeText(this, "క్యాలెండర్ అనుమతి ఇచ్చి మళ్లీ ఆన్ చేయండి.", Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                String r = WatchExtras.calSet(getApplicationContext(), on);
                runOnUiThread(() -> {
                    Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                    if (on != WatchExtras.calOn(this)) { sw.setTag(true); sw.setChecked(WatchExtras.calOn(this)); sw.setTag(null); }
                });
            }).start();
        });
    }

    // ---- the phase-5 switches that are not the watch's, in their own cards now

    /** In "రోజువారీ హెచ్చరికలు": great heat outside, the phone's battery before / on duty. */
    private void heatAndBattery() {
        Switch heat = toggle("🥵 బయట ఎండ చాలా ఎక్కువైతే (40°+) నీళ్లు / నీడ గుర్తు చేయి", Travel.heatOn(this));
        heat.setOnCheckedChangeListener((sw, on) -> Travel.set(this, "heat", on));
        Switch bat = toggle("🔋 డ్యూటీ ముందు / డ్యూటీలో / బయట ఫోన్ బ్యాటరీ తక్కువైతే, డ్యూటీ వరకు సరిపోదంటే చెప్పు", Power.on(this));
        bat.setOnCheckedChangeListener((sw, on) -> getSharedPreferences("jarvis_power", MODE_PRIVATE).edit().putBoolean("on", on).apply());
    }

    /** In "రోజువారీ హెచ్చరికలు": the companion word, the evening forecast, today's power report. */
    private void companionAndForecast() {
        Switch comp = toggle("🤝 డ్యూటీ అయ్యాక / గంట దాటిన రైడ్ తర్వాత Jarvis ఒక చిన్న మాట (\"జాగ్రత్తగా వెళ్ళండి\", \"నీళ్లు తాగండి\"; ప్రశ్న ఉండదు)", Companion.on(this));
        comp.setOnCheckedChangeListener((sw, on) -> prefs.sp.edit().putBoolean("companion", on).apply());
        Switch fc = toggle("🔮 సాయంత్రం 6–9 మధ్య \"ఇలాగే కొనసాగితే\" హెచ్చరిక, ఎరుపు ఉంటేనే (బ్యాటరీ, మందుల స్టాక్, నెల ఖర్చు, నిద్ర, సర్వీస్ కి.మీ)", Forecast.eveningOn(this));
        fc.setOnCheckedChangeListener((sw, on) -> prefs.sp.edit().putBoolean("forecast_evening", on).apply());
        button("🔋 ఈరోజు పవర్ వాడకం చూపించు (కెమెరా, రైడ్ GPS, మైక్… అంచనా)", v -> {
            new Thread(() -> {
                String r = Power.report(getApplicationContext());
                runOnUiThread(() -> { if (!isFinishing()) dialog().setTitle("🔋 పవర్ వాడకం").setMessage(r).setPositiveButton("సరే", null).show(); });
            }).start();
        });
    }

    /** In "కార్/బైక్, కదలికలు": the card when he reaches home / leaves it. */
    private void homeArrivalSwitch() {
        Switch home = toggle("🏠 ఇంటికి రాగానే / బయల్దేరగానే కార్డ్ (లైట్లు, కాపలా, “తాళం వేశారా?”; మీ నొక్కుతోనే)", HomeArrival.on(this));
        home.setOnCheckedChangeListener((sw, on) -> { Travel.set(this, "home_welcome", on); new Thread(() -> HomeArrival.arm(getApplicationContext())).start(); });
        note("ఇల్లు సేవ్ అయి ఉండాలి (ఇంట్లో ఉన్నప్పుడు “ఈ చోటు ఇల్లు గా సేవ్ చెయ్”), లొకేషన్ “అన్నివేళలా” అనుమతి కావాలి.");
    }

    /** In "Offline వాయిస్": the offline English → Telugu pack for the camera. */
    private void offlineReadPack() {
        TextView tr = Ui.text(this, OfflineRead.ready(this) ? "🌐 ఆఫ్‌లైన్ అనువాదం (ఇంగ్లీష్ → తెలుగు): ✓ ఫోన్‌లో ఉంది" : "🌐 ఆఫ్‌లైన్ అనువాదం (ఇంగ్లీష్ → తెలుగు): ఇంకా లేదు", 15f, Ui.CYAN);
        tr.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(tr);
        button("⬇️ ఆఫ్‌లైన్ అనువాదం ప్యాక్ డౌన్‌లోడ్ (సుమారు 30 MB, ఒక్కసారి)", v -> {
            tr.setText("🌐 డౌన్‌లోడ్ అవుతోంది…");
            new Thread(() -> {
                String r = OfflineRead.download(getApplicationContext());
                runOnUiThread(() -> tr.setText(r.isEmpty() ? "🌐 ఆఫ్‌లైన్ అనువాదం: ✓ ఫోన్‌లో ఉంది" : "🌐 డౌన్‌లోడ్ కాలేదు: " + r));
            }).start();
        });
        note("నెట్ లేనప్పుడు Jarvis కెమెరాలో ఇంగ్లీష్ రాత (మందుల స్ట్రిప్, బిల్లు, బోర్డు) ఫోన్‌లోనే చదివి, ఈ ప్యాక్ ఉంటే తెలుగులో చెబుతుంది.");
    }

    /** In "కాపలా మోడ్": joining his main phone with the old phone at home. */
    private void homePhoneNote() {
        note("🏠 ఇంటి ఫోన్‌తో కలపడానికి (“ఇంట్లో ఎలా ఉంది?” ఫోటో, “ఇంటికి చెప్పు”, కాపలా ఆపు / పెట్టు): ఈ ఫోన్‌లో కాపలా మోడ్ సెక్షన్‌లో ఇంటి ఫోన్‌లో పెట్టిన అదే Telegram bot token పెట్టి "
                + "“Telegram చాట్ కనుక్కో” నొక్కండి (Telegram లో bot కి hi పంపి). కాపలా మోడ్ ఇక్కడ ఆన్ చేయకండి: అది ఇంటి ఫోన్ పని. Token ఈ రెండు ఫోన్లలోనే ఉంటుంది. "
                + "Bot చాట్‌లో Jarvis మెసేజ్‌లు పిన్ అవుతాయి (రెండు ఫోన్లు అలా మాట్లాడుకుంటాయి).");
    }

    private static String hourText(int h) {
        String part = h < 4 ? "రాత్రి" : h < 12 ? "ఉదయం" : h < 16 ? "మధ్యాహ్నం" : h < 20 ? "సాయంత్రం" : "రాత్రి";
        int twelve = h % 12 == 0 ? 12 : h % 12;
        return part + " " + twelve;
    }

    /** From which hour to which the watch always listens. */
    private void pickHours(Switch hours) {
        String[] names = new String[24];
        for (int h = 0; h < 24; h++) names[h] = hourText(h) + " (" + h + ":00)";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("ఎప్పటి నుంచి?")
                .setItems(names, (d, from) -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("ఎప్పటి వరకు?")
                        .setItems(names, (d2, to) -> {
                            if (to == from) { Toast.makeText(this, "మొదలు, ముగింపు ఒకటే: వేరే సమయం ఎంచుకోండి", Toast.LENGTH_SHORT).show(); return; }
                            WatchHub.sp(this).edit().putInt("from", from).putInt("to", to).apply();
                            WatchHub.set(this, "hours", true);
                            hours.setChecked(true);
                            hours.setText("🕐 ఈ సమయంలో వాచ్ ఎప్పుడూ వింటూ ఉండు: " + hourText(from) + " నుంచి " + hourText(to) + " వరకు (బ్యాటరీ ఎక్కువ)");
                        }).show())
                .setNegativeButton("వద్దు", null).show();
    }

    /** Sounds 1c (S15-S19): a scream / a fall, his name on earphones, groans and laughs, crying (each acts at once; no Save needed). */
    private void safetySounds() {
        TextView head = Ui.text(this, "🆘 భద్రత, మనసు (శబ్దాలతో)", 16.5f, Ui.CYAN);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        box.addView(head);
        Switch fall = toggle("😱 అరుపు, భారీగా పడిన శబ్దం వింటే \"బాగున్నారా?\" అని అడుగు; జవాబు రాకపోతే SOS", SafetySounds.fallOn(this));
        fall.setOnCheckedChangeListener((sw, isOn) -> SafetySounds.setFall(this, isOn));
        TextView wait = Ui.text(this, "", 15.5f, Ui.CYAN);
        wait.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        wait.setText("⏱️ జవాబు కోసం ఆగే సమయం: " + SafetySounds.waitText(SafetySounds.waitSeconds(this)) + "  (మార్చడానికి నొక్కండి)");
        wait.setOnClickListener(v -> {
            final int[] secs = {15, 30, 60};
            String[] names = new String[secs.length];
            int checked = 1;
            for (int i = 0; i < secs.length; i++) { names[i] = SafetySounds.waitText(secs[i]); if (secs[i] == SafetySounds.waitSeconds(this)) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("\"బాగున్నారా?\" తర్వాత జవాబు కోసం ఎంతసేపు ఆగాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        SafetySounds.setWait(this, secs[w]);
                        wait.setText("⏱️ జవాబు కోసం ఆగే సమయం: " + SafetySounds.waitText(secs[w]) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(wait);
        TextView sos = Ui.text(this, "", 15.5f, Ui.CYAN);
        sos.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        sos.setText("🆘 జవాబు రాకపోతే: " + SafetySounds.modeText(SafetySounds.sosMode(this)) + "  (మార్చడానికి నొక్కండి)");
        sos.setOnClickListener(v -> {
            final String[] modes = {"sms_call", "sms", "none"};
            String[] names = new String[modes.length];
            int checked = 0;
            for (int i = 0; i < modes.length; i++) { names[i] = SafetySounds.modeText(modes[i]); if (modes[i].equals(SafetySounds.sosMode(this))) checked = i; }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("జవాబు రాకపోతే ఏం చేయాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        SafetySounds.setSosMode(this, modes[w]);
                        sos.setText("🆘 జవాబు రాకపోతే: " + SafetySounds.modeText(modes[w]) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(sos);
        note((prefs.sosContacts().trim().isEmpty() ? "⚠️ SOS కాంటాక్ట్స్ ఇంకా పెట్టలేదు: పైన 'అత్యవసరం (SOS)' లో పెట్టండి (లేకపోతే \"బాగున్నారా?\" అని అడగడం మాత్రమే). "
                : "SOS పైన 'అత్యవసరం (SOS)' లోని కాంటాక్ట్స్‌కి వెళ్తుంది (మొదటివాళ్లకి కాల్). ")
                + "బాగుంటే \"నేను బాగున్నాను, ఏమీ కాలేదు\" అని చెప్పినా, 'నేను బాగున్నాను' నొక్కినా, \"Jarvis\" అని పిలిచినా ఆగిపోతుంది. ఒక వస్తువు పడితే "
                + "సాధారణంగా అడగదు (తర్వాత మాటలు, అడుగులు వినిపిస్తాయి; ఫోన్ కదిలితే అది ఫోన్ శబ్దమే); పడిన తర్వాత నిశ్శబ్దంగా ఉంటే, లేదా మూలుగు / అరుపు వస్తే అడుగుతుంది. "
                + "రాత్రి 10 నుంచి ఉదయం 7 వరకు ఒక్క ధబ్ శబ్దానికి అడగదు: తర్వాత మూలుగు / అరుపు వస్తేనే. ఫోన్‌లో వీడియో, పాటల శబ్దాలు పట్టించుకోదు.");
        Switch name = toggle("🎧 ఇయర్‌ఫోన్స్ పెట్టుకున్నప్పుడు ఎవరైనా మీ పేరు పిలిస్తే పాట తగ్గించి చెప్పు", NameCall.on(this));
        name.setOnCheckedChangeListener((sw, isOn) -> NameCall.setOn(this, isOn));
        EditText callNames = field("🎧 మిమ్మల్ని పిలిచే పేర్లు, కామాతో (ఖాళీ = " + prefs.name() + ")", SafetySounds.sp(this).getString("call_names", ""), false);
        callNames.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { NameCall.setNames(SettingsActivity.this, s.toString()); }
        });
        note(NameCall.status.isEmpty() ? "పేరు వినడం Jarvis మైక్ మొదలైనప్పుడు సిద్ధమవుతుంది (\"Jarvis\" పదం మోడల్‌తోనే)." : NameCall.status);
        Switch tired = toggle("😮‍💨 మూలుగు, పెద్ద నిట్టూర్పులు వరుసగా వస్తే రోజుకి ఒక్కసారి మెల్లగా \"అలసిపోయారా?\" అని అడుగు; మీరు నవ్విన జోక్స్ / కథలు గుర్తుపెట్టుకో"
                + (Laughs.count(this) > 0 ? " (నవ్వించినవి: " + Laughs.count(this) + ")" : ""), BodySounds.tiredOn(this));
        tired.setOnCheckedChangeListener((sw, isOn) -> BodySounds.setTired(this, isOn));
        Switch care = toggle("💙 మీరు ఏడుస్తున్నట్టు వినిపిస్తే మాట్లాడకుండా \"నేను ఉన్నాను, మాట్లాడతారా?\" అని చిన్న నిశ్శబ్ద కార్డ్ మాత్రమే చూపించు (ఎక్కడా రాయదు)", SafetySounds.careOn(this));
        care.setOnCheckedChangeListener((sw, isOn) -> SafetySounds.setCare(this, isOn));
        Switch child = toggle("👶 ఇయర్‌ఫోన్స్ పెట్టుకున్నప్పుడు పిల్లలు ఏడిస్తే చెప్పు (ఇంట్లో పిల్లలు ఉంటేనే ఆన్ చేయండి)", SafetySounds.childOn(this));
        child.setOnCheckedChangeListener((sw, isOn) -> SafetySounds.setChild(this, isOn));
    }

    /** "దగ్గు / శబ్దం టెస్ట్": 30 seconds of what the microphone hears and what Jarvis makes of it (nothing asked or counted). */
    private void soundTest() {
        if (!WakeService.running || !WakeService.hearing) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Jarvis మైక్ ఇప్పుడు వినడం లేదు")
                    .setMessage("దగ్గు, తలుపు, కుక్కర్ శబ్దాలు \"Jarvis\" వేక్ వర్డ్ మైక్‌తోనే వింటాను. పైన వేక్ వర్డ్ ఆన్ చేసి, Jarvis తో మాట్లాడటం ఆపాక మళ్లీ నొక్కండి.")
                    .setPositiveButton("సరే", null).show();
            return;
        }
        CoughDetector.startTest(30);
        WakeService.recheck(this);
        TextView t = Ui.text(this, "వింటున్నాను… ఫోన్‌కి మీటర్ దూరంలో దగ్గండి / తుమ్మండి / బెల్ మోగించండి.", 15, Ui.TEXT);
        int pad = Ui.dp(this, 20);
        t.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🎙️ శబ్దం టెస్ట్")
                .setView(sv)
                .setPositiveButton("ఆపు", null)
                .setNeutralButton("చివరిది నా బెల్", null)
                .setNegativeButton("చివరిది కుక్కర్", null)
                .create();
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                long left = CoughDetector.testLeftMs();
                String lines = CoughDetector.testText();
                t.setText((left > 0 ? "⏱ ఇంకా " + (left / 1000 + 1) + " సెకన్లు · " : "✓ టెస్ట్ అయిపోయింది · ")
                        + "ఫోన్‌కి మీటర్ దూరంలో దగ్గండి / తుమ్మండి / బెల్ మోగించండి.\n\n"
                        + (lines.isEmpty() ? "ఇంకా ఏ గట్టి శబ్దం వినలేదు." : lines));
                if (left > 0) h.postDelayed(this, 400);
            }
        };
        d.setOnShowListener(x -> {
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    Toast.makeText(this, Sounds.teachLast(this, "bell") ? "సరే, ఆ శబ్దం మీ బెల్ అని గుర్తుపెట్టుకున్నాను" : "ఇంకా ఏ శబ్దం వినలేదు", Toast.LENGTH_SHORT).show());
            d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    Toast.makeText(this, Sounds.teachLast(this, "cooker") ? "సరే, ఆ శబ్దం మీ కుక్కర్ విజిల్ అని గుర్తుపెట్టుకున్నాను" : "ఇంకా ఏ శబ్దం వినలేదు", Toast.LENGTH_SHORT).show());
            h.post(tick);
        });
        d.setOnDismissListener(x -> { CoughDetector.stopTest(); WakeService.recheck(this); });
        d.show();
    }

    private Switch toggle(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(16);
        s.setChecked(on);
        s.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private RadioButton radio(int id, String label) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(label);
        r.setTextColor(Ui.TEXT);
        r.setTextSize(16);
        r.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return r;
    }

    /** "Show all models" under a model box: the live list for that key; a tap fills the box and saves it. */
    private void modelPicker(String provider, EditText keyField, EditText into, String pref) {
        TextView t = Ui.text(this, "📋 అన్ని మోడల్స్ చూపించు, ఎంచుకో", 14.5f, accent);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        t.setOnClickListener(v -> Models.pick(this, Models.company(provider) + " మోడల్ ఎంచుకోండి",
                new String[]{provider}, new String[]{keyField.getText().toString()},
                into.getText().toString(), it -> chose(into, pref, it.id)));
        box.addView(t);
    }

    private void chose(EditText into, String pref, String model) {
        into.setText(model);
        prefs.sp.edit().putString(pref, model).apply();
        Toast.makeText(this, "✓ " + model + " పెట్టాను (సేవ్ అయింది)", Toast.LENGTH_SHORT).show();
    }

    private void link(String label, String url) {
        TextView t = Ui.text(this, label + " →", 14.5f, accent);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        t.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });
        box.addView(t);
    }

    /** The people Jarvis knows by face, each with a "forget" button. */
    private void renderPeople() {
        if (peopleBox == null) return;
        peopleBox.removeAllViews();
        java.util.List<String> names = People.names(this);
        if (names.isEmpty()) {
            TextView t = Ui.text(this, "ఇంకా ఎవరూ లేరు.", 14, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 6), 0, 0);
            peopleBox.addView(t);
        }
        for (String n : names) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            row.addView(Ui.text(this, "🙂  " + n, 15, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -2, 1));
            TextView forget = Ui.text(this, "మర్చిపో", 14, Ui.RED);
            forget.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            forget.setBackground(Ui.round(this, 0x14FF6B5E, 0x73FF6B5E, 10));
            forget.setOnClickListener(v -> {
                People.remove(this, n);
                renderPeople();
                Toast.makeText(this, n + " ముఖం మర్చిపోయాను", Toast.LENGTH_SHORT).show();
            });
            row.addView(forget);
            peopleBox.addView(row);
        }
        String sc = FaceSight.lastScore;
        if (!names.isEmpty() && !sc.isEmpty()) {
            TextView t = Ui.text(this, "చివరిసారి చూసిన ముఖం పోలిక: " + sc + " (" + People.SAME + " పైన ఉంటే గుర్తుపడతాడు)", 12.5f, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            peopleBox.addView(t);
        }
    }

    /**
     * ⚡ వేగం (Round 2, build 1): each speed change with its own switch, on from the start; off = exactly the way before
     * (from the next question, no restart). Never on the home tablet.
     */
    private void speedCard() {
        section("వేగం: జవాబు తొందరగా", "⚡");
        note("ఒక్కో మార్పుకి సొంత స్విచ్. ఏదైనా నచ్చకపోతే అది ఆఫ్ చేయండి: తర్వాతి ప్రశ్న నుంచే పాత పద్ధతి. "
                + "ఏ జవాబుకి ఎంత టైమ్ పట్టిందో, ఎందుకో 'Jarvis చెక్' లో ⏱️ చివరి జవాబుల లిస్ట్‌లో చూడండి.");
        speedG = toggle("G · గొంతు ముందే సిద్ధం: మెదడు ఆలోచిస్తుండగానే గొంతు లైన్ తెరుస్తుంది, మొదటి మాట ముందున్న నిశ్శబ్దం దాటేస్తుంది",
                prefs.sp.getBoolean("speed_g", true));
        speedK = toggle("K · Google చెవులు తొందరగా: మీరు మాట ఆపారని ఫోన్ చెప్పగానే, అప్పటివరకు విన్న మాటలు మెదడుకి (Google వాయిస్ టైపింగ్‌లో మాత్రమే)",
                prefs.sp.getBoolean("speed_k", true));
        speedF = toggle("F · జవాబులో ఫోన్ పని (వాతావరణం, మెసేజ్‌లు, డ్యూటీ…) జరిగేటప్పుడు \"ఒక్క క్షణం, చూస్తున్నాను\" (మీరు ఎంచుకున్న గొంతులోనే)",
                prefs.sp.getBoolean("speed_f", true));
        speedE = toggle("E · జవాబు అక్షరాలు, గొంతు ఒకేసారి (అక్షరాలు గొంతు మొదలయ్యాక వస్తాయి; 2.5 సెకన్లకి మించి ఆగవు)",
                prefs.sp.getBoolean("speed_e", true));
        speedA = toggle("A · మెదడుకి చిన్న పొట్లం: ప్రశ్నకి కావాల్సిన ఫోన్ పనులు, వాటి రూల్స్ మాత్రమే AI కి (మిగతావి అవసరమైతే AI తనే తెప్పించుకుంటుంది). "
                + "భద్రత రూల్స్ ఎప్పుడూ వెళ్తాయి", prefs.sp.getBoolean("speed_a", true));
        speed5 = toggle("5 · రూల్ బుక్ గుర్తుండేలా: టైమ్, ఫోన్ వివరాలు లాంటి మారేవి ప్రశ్నతో పాటు వెళ్తాయి; రూల్ బుక్ మొదలు ప్రతిసారీ ఒకేలా ఉండి AI దాన్ని గుర్తుంచుకుంటుంది (ఖర్చు తక్కువ)",
                prefs.sp.getBoolean("speed_5", true));
        speedB = toggle("B · రాస్తుండగానే మాట్లాడు: మెదడు జవాబు రాస్తుండగానే మొదటి వాక్యం చెబుతుంది, మిగతాది వెనకే "
                + "(Gemini మెదడు, OpenAI గొంతుతో; రెండో AI చెక్ చేసే డబ్బు / ఆరోగ్యం / చట్టం ప్రశ్నలకి కాదు)", prefs.sp.getBoolean("speed_b", true));
        voiceGuard = toggle("♻️ గొంతు కాపలా: OpenAI గొంతు 6 సెకన్లలో వినిపించకపోతే, లేదా 4 సెకన్లు ఆగిపోతే, అదే గొంతుని కొత్త లైన్‌లో "
                + "ఇంకొక్కసారి అడుగుతుంది (మధ్యలో అయితే ఆ వాక్యం మొదటి నుంచి). అప్పుడూ రాకపోతే ఫోన్ గొంతు చెబుతుంది. "
                + "జవాబు నిజంగా వినిపించాకే మైక్ తెరుస్తుంది", prefs.sp.getBoolean("voice_guard", true));
        button("అన్నీ పాత పద్ధతికి (అన్నీ ఆఫ్)", v -> {
            for (Switch w : new Switch[]{speedG, speedK, speedF, speedE, speedA, speed5, speedB, voiceGuard}) w.setChecked(false);
            autosave();
            Toast.makeText(this, "⚡ అన్నీ ఆఫ్: ఇంతకుముందు పద్ధతిలోనే", Toast.LENGTH_SHORT).show();
        });
        if (prefs.homeMode()) note("ఇంటి టాబ్లెట్‌లో ఇవి పనిచేయవు: అమ్మగారి Jarvis ఎప్పటిలాగే ఉంటుంది.");
    }

    /** Jarvis చెక్'s line: which speed switches are on. */
    private String speedLine() {
        StringBuilder b = new StringBuilder("• ⚡ వేగం: ");
        String[][] all = {{"G", "speed_g"}, {"K", "speed_k"}, {"F", "speed_f"}, {"E", "speed_e"}, {"A", "speed_a"}, {"5", "speed_5"},
                {"B", "speed_b"}, {"♻️", "voice_guard"}};
        for (int i = 0; i < all.length; i++) {
            if (i > 0) b.append(" · ");
            b.append(all[i][0]).append(prefs.sp.getBoolean(all[i][1], true) ? " ✓" : " ఆఫ్");
        }
        return b.toString();
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 15.5f, 0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g = Ui.grad(this, new int[]{Ui.alpha(accent, 0x4D), Ui.alpha(accent, 0x22)}, 14, null);
        g.setStroke(Ui.dp(this, 1), Ui.alpha(accent, 0x88));
        t.setBackground(g);
        int p = Ui.dp(this, 13);
        t.setPadding(p, p, p, p);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(t, lp);
        return t;
    }

    // ---------------------------------------------------------------- updates (only when he presses)

    private void showUpdateState() {
        if (updateInfo == null || isFinishing()) return;
        if (Updater.busy()) { updateInfo.setText(Updater.status); updateBtn.setText("అప్డేట్ అవుతోంది…"); return; }
        if (Updater.newAvailable(this)) {
            int v = Updater.knownLatest(this);
            updateInfo.setText("⬆ కొత్త వెర్షన్ 1.0." + v + " వచ్చింది");
            updateBtn.setText("1.0." + v + " కి అప్డేట్ చేయి (డౌన్‌లోడ్ సుమారు 55 MB)");
        } else {
            updateInfo.setText(Updater.status.isEmpty() ? "✓ ఇదే తాజా వెర్షన్" : Updater.status);
            updateBtn.setText("కొత్త వెర్షన్ ఉందేమో చూడు");
        }
    }

    private void startUpdate() {
        updateBtn.setText("అప్డేట్ అవుతోంది…");
        Updater.updateNow(this, (text, done) -> {
            if (isFinishing()) return;
            updateInfo.setText(text);
            if (done) {
                if (Updater.newAvailable(this)) updateBtn.setText("మళ్లీ ప్రయత్నించు");
                else updateBtn.setText("కొత్త వెర్షన్ ఉందేమో చూడు");
            }
        });
    }

    /** From the notification: scroll to Updates and start right away. */
    /** Scrolls to the card named in EXTRA_SECTION and makes it glow for a moment. */
    private void jumpToSection(Intent i) {
        String want = i == null ? null : i.getStringExtra(EXTRA_SECTION);
        if (want == null || want.trim().isEmpty() || pageScroll == null) return;
        i.removeExtra(EXTRA_SECTION);
        if (want.startsWith("group:")) { openGroup(want.substring(6).trim()); return; } // (a whole group: "group:tab")
        for (String[] g : GROUPS) if (want.trim().equalsIgnoreCase(g[2]) || want.trim().equalsIgnoreCase(g[0])) { openGroup(g[0]); return; } // ("వాచ్")
        Card c = findCard(want.trim());
        if (c == null) { search.setText(want.trim()); return; } // no card by that name: show what the search finds
        showCard(c);
    }

    /** The card whose title has these words; else the one with most of his words (in its title most of all). */
    private Card findCard(String want) {
        for (Card c : cardList) if (c.title.contains(want)) return c;
        String w = want.toLowerCase(Locale.ROOT);
        Card best = null;
        int bestScore = 0;
        for (Card c : cardList) {
            String title = c.title.toLowerCase(Locale.ROOT), all = words(c);
            int score = title.contains(w) ? 100 : all.contains(w) ? 50 : 0;
            for (String part : w.split("[\\s,]+")) {
                if (part.length() < 2 || part.equals("సెట్టింగ్స్") || part.equals("settings")) continue;
                if (title.contains(part)) score += 3; else if (all.contains(part)) score += 1;
            }
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Opens the card's group, unfolds the card, scrolls to it and makes it glow for a moment. */
    private void showCard(Card c) {
        openGroup(c.group);
        if (!c.open) toggle(c);
        final View card = c.view;
        pageScroll.post(() -> {
            pageScroll.smoothScrollTo(0, Math.max(0, card.getTop() - Ui.dp(this, 8)));
            card.animate().alpha(0.45f).setDuration(220).withEndAction(() -> card.animate().alpha(1f).setDuration(380).start()).start();
        });
    }

    private void updateFromIntent(Intent i) {
        jumpToSection(i);
        String q = i == null ? null : i.getStringExtra(EXTRA_SEARCH); // (from All Features: the same words here)
        if (q != null && !q.trim().isEmpty()) { i.removeExtra(EXTRA_SEARCH); search.setText(q.trim()); }
        if (i != null && "frame".equals(i.getStringExtra(EXTRA_ACTION)) && !tab.isEmpty()) { // (All Features → 🖼️ photos for her frame)
            i.removeExtra(EXTRA_ACTION);
            Card c = cardTitled(TAB_FUN);
            if (c != null) showCard(c);
            pickFramePhotos();
        }
        if (i == null || !i.getBooleanExtra(EXTRA_UPDATE_NOW, false)) return;
        i.removeExtra(EXTRA_UPDATE_NOW);
        if (pageScroll != null && updatesHeader != null) {
            for (Card c : cardList) if (c.view == updatesHeader) showCard(c);
        }
        if (!Updater.busy()) startUpdate();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (gated) return;
        updateFromIntent(intent);
    }
}
