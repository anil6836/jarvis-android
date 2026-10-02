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
    /** Each card with its title, to jump to one. */
    private final java.util.List<Object[]> cardTitles = new java.util.ArrayList<>();

    private Prefs prefs;
    private EditText name, openAiKey, openAiModel, anthropicKey, anthropicModel;
    private RadioGroup provider, lang, wakeWhen, checkProvider;
    private EditText checkModel;
    private Switch checkAuto;
    private Switch callVoice, readMessages, batteryWarn, voiceLock, proactive, sfx, shakeWake, faceDown, nightSummary;
    private TextView carInfo;
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
    private Switch walletPay, emotions;
    private Switch alexaSpeak, livePatient, scamGuard, readNews;
    private Switch newsAuto;
    private EditText newsPlaces;
    private Switch diaryAsk, holidayRemind, findPhone, coughAsk, readGroups;
    private EditText priceCity, dailyPrices, findCode;
    private EditText balGemini, balOpenAi, balAnthropic;
    private EditText bikeRange, bikeKwh, powerRate;
    private Switch weeklyReport;
    private Switch bargeCallVoice;
    /** Opened from the "new version" notification / note: go to Updates and start the update. */
    static final String EXTRA_UPDATE_NOW = "update_now";
    private View updatesHeader;
    private TextView updateInfo, updateBtn;
    private ScrollView pageScroll;
    private Switch web, voice, followUp, wake, natural, liveMode, bargeIn, jarvisWord, announceCalls, briefing, briefingSpeak, listenOnOpen, compactPanel;
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
    private TextView voiceInfo, notifyInfo, checkInfo;
    private final NaturalVoice tester = new NaturalVoice();
    private SeekBar rate, sensitivity, bargeSens;
    private TextView rateLabel, sensitivityLabel, wakeInfo, bargeSensLabel;
    /** The card being filled (each section is its own coloured glass card); page holds the cards. */
    private LinearLayout box, page;
    private int accent = Ui.C_CYAN;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        titles.addView(Ui.text(this, "Jarvis ని మీకు నచ్చినట్టు మార్చుకోండి", 13.5f, Ui.MUTED));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        IconView close = new IconView(this, IconView.CLOSE, 0xFFFFFFFF);
        close.setBackground(Ui.glass(this, 22));
        close.setOnClickListener(v -> finish());
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
        button("సరిచేయి", v -> fixCheck());
        button("గొంతు టెస్ట్ (Jarvis మాట్లాడుతుందా?)", v -> Announcer.say(this, prefs.name() + ", నా గొంతు వినిపిస్తోందా? అంతా బాగుంది."));

        // ---- you
        section("మీరు");
        name = field("మీ పేరు (Jarvis మిమ్మల్ని ఇలా పిలుస్తాడు)", prefs.name(), false);

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
        note("మోడల్ Jarvis తనంతట తాను ఎంచుకోడు: మీరు ఎంచుకున్నదే వాడతాడు. 'అన్ని మోడల్స్ చూపించు' మీ key తో ఆ కంపెనీ దగ్గర నుంచి "
                + "ఇప్పుడున్న మోడల్స్ అన్నీ తెస్తుంది; ఒకటి నొక్కితే వెంటనే సేవ్ అవుతుంది. ఎప్పుడైనా మార్చుకోవచ్చు. పేరు మీరే టైప్ చేసినా సరే.");
        note("Gemini: ఉచిత ప్లాన్‌లో Flash, Flash-Lite మోడల్స్ మాత్రమే; Pro మోడల్స్‌కి billing కావాలి. ప్రతి మోడల్‌కి వేరే నిమిషం/రోజు లిమిట్ ఉంటుంది: "
                + "ఒక మోడల్ లిమిట్ అయిపోతే ఇంకో Flash మోడల్ ఎంచుకోండి. రోజు లిమిట్ మధ్యాహ్నం సుమారు 12:30–1:30 కి మళ్లీ వస్తుంది. "
                + "Jarvis ఒక్క ప్రశ్నకి (యాప్ తెరవడం, సెర్చ్ లాంటి పనులుంటే) చాలా requests పంపుతాడు, అందుకే ఉచిత లిమిట్ త్వరగా అయిపోవచ్చు. "
                + "Google AI Plus/Pro సబ్స్క్రిప్షన్ Gemini యాప్ కోసం; API లిమిట్‌కి దానితో సంబంధం లేదు. ఉచిత ప్లాన్‌లో మీరు పంపేవి (మాటలు, స్క్రీన్‌షాట్లు) "
                + "Google తమ మోడల్స్ మెరుగుపరచడానికి వాడుకోవచ్చు. పైన 'Google (Gemini)' ఎంచుకుంటేనే వాడుతుంది; వాయిస్ (సహజ గొంతు), Live మోడ్ OpenAI తోనే ఉంటాయి.");
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
        voice = toggle("సమాధానాలు పైకి చదివి వినిపించు", prefs.voiceReplies());
        followUp = toggle("సమాధానం తర్వాత మళ్లీ వినడం (సంభాషణ మోడ్)", prefs.followUp());
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
        section("సహజ గొంతు (OpenAI)");
        note("సినిమాలోలా మనిషి గొంతుతో మాట్లాడుతుంది. OpenAI key కావాలి, కొంచెం ఖర్చు అవుతుంది. తెలుగు ఉచ్చారణ నచ్చకపోతే ఇది ఆఫ్ చేస్తే Google గొంతుకి మారుతుంది.");
        natural = toggle("సహజ గొంతు వాడు", prefs.naturalVoice());
        emotions = toggle("భావాలతో మాట్లాడు (నవ్వు, సంతోషం, ఉత్సాహం, బాధ… సందర్భానికి తగ్గట్టు)", prefs.emotions());
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
        button("ఈ గొంతు వినిపించు", v -> testVoice());
        voiceInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(voiceInfo);

        // ---- live conversation
        section("Live సంభాషణ (Real-time)");
        note("ChatGPT వాయిస్ లాగా, ఫ్రెండ్‌తో మాట్లాడినట్టే: మీరు మాట్లాడుతుంటే వింటుంది, వెంటనే జవాబిస్తుంది, మధ్యలో ఆపి మాట్లాడొచ్చు. OpenAI key కావాలి. "
                + "సాధారణ మోడ్ కంటే ఎక్కువ ఖర్చు అవుతుంది. 2 నిమిషాలు ఎవరూ మాట్లాడకపోతే \"అవసరమైతే పిలవండి\" అని చెప్పి ఆగిపోతుంది.");
        liveMode = toggle("\"Hey Jarvis\" అన్నా Live సంభాషణే మొదలవ్వాలి (నీలం బటన్‌తో Live ఎప్పుడైనా వస్తుంది)", prefs.liveMode());
        livePatient = toggle("మీరు మాట పూర్తి చేసే వరకు ఆగి, తర్వాతే జవాబివ్వు (మధ్యలో ఆలోచిస్తూ ఆగినా కట్ చేయదు)", prefs.livePatient());
        bargeIn = toggle("Jarvis మాట్లాడుతుండగా మధ్యలో మాట్లాడితే ఆగి వినాలి", prefs.bargeIn());
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
        bargeCallVoice = toggle("Jarvis గొంతుని ఫోన్ కాల్ మార్గంలో పంపు (ప్రతిధ్వని ఇంకా బాగా తీసేస్తుంది, కానీ గొంతు కాల్ లాగా, తక్కువగా ఉంటుంది. మామూలుగా ఆఫ్ ఉంచండి; Jarvis తనంతట తానే ఆగిపోతుంటే లేదా మీ మాట అసలు వినకపోతే మాత్రమే ఆన్ చేయండి)", prefs.bargeCallVoice());
        realtimeModel = field("Live మోడల్", prefs.realtimeModel(), false);
        modelPicker(Models.REALTIME, openAiKey, realtimeModel, "realtime_model");
        note("mini మోడల్ చవక, వేగం. పేరులో mini లేని పెద్ద మోడల్ ఇంకా సహజంగా, భావంతో మాట్లాడుతుంది కానీ ఖర్చు ఎక్కువ.");

        // ---- wake word
        section("\"Hey Jarvis\" వేక్ వర్డ్");
        note("ఫోన్ లాక్‌లో ఉన్నా \"Hey Jarvis\" అని పిలిస్తే తెరుచుకుంటుంది. ఏ key అవసరం లేదు. వినడం అంతా ఫోన్‌లోనే జరుగుతుంది, ఏ ఆడియో బయటికి వెళ్లదు.");
        compactPanel = toggle("పిలిస్తే Google లాగా చిన్న ప్యానెల్ (\"చెప్పండి Anil?\")", prefs.compactPanel());
        listenOnOpen = toggle("యాప్ తెరవగానే వినడం మొదలుపెట్టు", prefs.listenOnOpen());
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

        // ---- permissions & data
        // ---- calls, reminders, morning briefing
        section("కాల్స్, ఉదయం బ్రీఫింగ్");
        announceCalls = toggle("కాల్ వస్తే ఎవరో పైకి చెప్పు (నోటిఫికేషన్ యాక్సెస్ కావాలి)", prefs.announceCalls());
        readMessages = toggle("కొత్త మెసేజ్ వస్తే (WhatsApp, SMS, Telegram, Instagram, Facebook, Snapchat...) ఎవరి నుంచో చెప్పి, \"చదవమంటారా?\" అని అడుగు", prefs.readMessages());
        readGroups = toggle("👥 గ్రూప్ మెసేజ్‌లు కూడా చెప్పు (WhatsApp గ్రూప్‌లు; ఆఫ్ = మనుషులు నేరుగా పంపినవి మాత్రమే)", prefs.readGroups());
        readNews = toggle("📰 Way2News వార్త వచ్చిన వెంటనే చదివి వినిపించు", prefs.readNews());
        newsPlaces = field("📍 లోకల్ వార్తల ప్రాంతాలు (కామాతో: రాష్ట్రాలు, జిల్లాలు, ఊర్లు)", prefs.newsPlaces(), false);
        newsPlaces.setHint("తెలంగాణ, ఆంధ్రప్రదేశ్, మీ జిల్లా, మీ ఊరు");
        newsAuto = toggle("📰 ఈ ప్రాంతాల కొత్త వార్తలు ఉదయం 8, మధ్యాహ్నం 1, సాయంత్రం 7 కి తనంతట తానే చదువు", prefs.newsAuto());
        batteryWarn = toggle("బ్యాటరీ 15%, 5% కి పడితే గొంతుతో చెప్పు", prefs.batteryWarn());
        callVoice = toggle("తర్వాత \"ఎత్తు\" అంటే కాల్ ఎత్తు, \"కట్\" అంటే కట్ చెయ్", prefs.callByVoice());
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
        Runnable guardState = () -> tgState.setText((Guard.chat(this).isEmpty() ? "Telegram చాట్: ఇంకా లేదు" : "Telegram చాట్: సిద్ధం ✓")
                + "   ·   కాపలా: " + (Guard.running(this) ? "ఆన్ 🛡️" : "ఆఫ్"));
        guardState.run();
        java.util.function.Consumer<Runnable> withToken = then -> {
            Guard.sp(this).edit().putString("tg_token", tgToken.getText().toString().trim()).apply();
            new Thread(() -> { then.run(); runOnUiThread(guardState); }).start();
        };
        button("Telegram చాట్ కనుక్కో", v -> withToken.accept(() -> {
            String r = Guard.findChat(this);
            runOnUiThread(() -> Toast.makeText(this, r.startsWith("!") ? r.substring(1) : "దొరికింది ✓", Toast.LENGTH_LONG).show());
        }));
        button("టెస్ట్ మెసేజ్ పంపు", v -> withToken.accept(() -> {
            boolean ok = Guard.send(this, "✅ Jarvis కాపలా మోడ్ టెస్ట్: ఈ మెసేజ్ వచ్చింది అంటే అంతా సిద్ధం.");
            runOnUiThread(() -> Toast.makeText(this, ok ? "పంపాను ✓ Telegram చూడండి" : "పంపలేకపోయాను: token / చాట్ చూడండి", Toast.LENGTH_LONG).show());
        }));
        button("▶ కాపలా మొదలుపెట్టు / ⏹ ఆపు", v -> {
            Guard.sp(this).edit().putString("tg_token", tgToken.getText().toString().trim()).apply();
            if (Guard.running(this)) Guard.stop(this);
            else if (Guard.token(this).isEmpty() || Guard.chat(this).isEmpty())
                Toast.makeText(this, "ముందు token పెట్టి 'Telegram చాట్ కనుక్కో' నొక్కండి", Toast.LENGTH_LONG).show();
            else if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 61);
            else { Guard.start(this); Toast.makeText(this, "🛡️ కాపలా మొదలైంది", Toast.LENGTH_SHORT).show(); }
            guardState.run();
        });

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
        button("కార్/బైక్ బ్లూటూత్ ఎంచుకోండి", v -> chooseCar());
        carInfo = Ui.text(this, prefs.carBluetooth().isEmpty() ? "ఇంకా ఎంచుకోలేదు" : "ఎంచుకున్నారు ✓", 14, Ui.MUTED);
        box.addView(carInfo);
        bikeRange = numberField("బైక్ పూర్తి ఛార్జ్‌కి నిజంగా వచ్చే దూరం (కి.మీ)", String.valueOf(Bike.fullRangeKm(prefs)));
        bikeKwh = numberField("బైక్ బ్యాటరీ (kWh) · Aera 5000+ = 5", trimZero(Bike.batteryKwh(prefs)));
        powerRate = numberField("ఇంట్లో కరెంట్ ఒక యూనిట్ ధర (₹)", trimZero(Bike.unitRate(prefs)));
        button("రైడ్ కి.మీ సరిగ్గా రావాలంటే: లొకేషన్ \"Allow all the time\"", v -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 10);
        });
        shakeWake = toggle("ఫోన్ రెండుసార్లు ఊపితే Jarvis రావాలి", prefs.shakeWake());
        faceDown = toggle("ఫోన్ బోర్లా పెడితే సైలెంట్ (ఎత్తితే మళ్లీ సౌండ్)", prefs.faceDownSilent());
        nightSummary = toggle("రోజూ రాత్రి 9:30 కి ఈరోజు, రేపటి సారాంశం చెప్పు", prefs.nightSummary());
        weeklyReport = toggle("ప్రతి ఆదివారం రాత్రి 8కి వారపు రిపోర్ట్ (ఖర్చు, అడుగులు, ఫోన్ టైమ్, బైక్)", prefs.weeklyReport());
        button("స్క్రీన్ టైమ్ కోసం \"Usage access\" ఇవ్వండి", v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {}
        });

        section("డైరీ, పండుగలు, ధరలు, ఫోన్ వెతుకు");
        diaryAsk = toggle("📔 రాత్రి " + prefs.diaryHour() + " గంటలకి \"ఈరోజు ఎలా గడిచింది?\" అని అడిగి డైరీ రాయి", prefs.diaryAsk());
        holidayRemind = toggle("🎉 పండుగలు, ప్రభుత్వ సెలవులు ముందు రోజు సాయంత్రం, ఆ రోజు ఉదయం చెప్పు (డ్యూటీ ఉందో లేదో కూడా)", prefs.holidayRemind());
        priceCity = field("💰 బంగారం, వెండి, పెట్రోల్ ధరలకి మీ సిటీ", prefs.priceCity(), false);
        dailyPrices = field("💰 రోజూ ఉదయం 10 కి ఈ ధరలు చెప్పు (ఉదా: బంగారం, వెండి, మిర్చి · ఖాళీ = వద్దు · రోజుకి ఒక చిన్న AI వెతుకులాట)", prefs.dailyPrices(), false);
        findPhone = toggle("📱 వేరే ఫోన్ నుంచి కింది కోడ్ SMS / WhatsApp లో పంపితే ఫోన్ గట్టిగా మోగాలి (సైలెంట్‌లో ఉన్నా)", prefs.findPhone());
        findCode = field("📱 ఫోన్ వెతుకు కోడ్ (ఇంట్లోవాళ్లకి చెప్పండి; కనీసం 6 అక్షరాలు/అంకెలు)", prefs.findCode(), false);
        button("🔔 ఇప్పుడు 5 సెకన్లు మోగించి చూడు", v -> FindPhone.start(this, 5000));

        section("రోజువారీ హెచ్చరికలు");
        stormAlert = toggle("⛈️ గంటలోపు పిడుగులు / భారీ వర్షం వచ్చేలా ఉంటే చెప్పు", prefs.stormAlert());
        chargeRemind = toggle("🔋 రాత్రి 9 కి బైక్ ఛార్జ్ తక్కువైతే (రేపు డ్యూటీ ఉంటే, లేదా 25% లోపు) \"ఛార్జ్ పెట్టండి\" అని చెప్పు", prefs.chargeRemind());
        weekPlan = toggle("🗓️ ప్రతి ఆదివారం సాయంత్రం వచ్చే వారం ప్లాన్ చెప్పు", prefs.weekPlan());
        dailyTip = toggle("📚 రోజూ ఒక Jarvis చిట్కా (తెలియని ఫీచర్) చెప్పు", prefs.dailyTip());
        habitHour = numberField("🎯 అలవాట్లు చేశారా అని రాత్రి ఇన్ని గంటలకి అడుగు (0 = వద్దు)", String.valueOf(Math.max(0, prefs.habitHour())));

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
        coughAsk = toggle("🤧 దగ్గు / తుమ్ములు వినిపిస్తే \"సర్, ఏమైంది?\" అని అడుగు (\"Hey Jarvis\" వినే మైక్‌తోనే, ఫోన్‌లోనే; ఏదీ రికార్డ్ చేయదు)", prefs.coughAsk());
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
                    .setTitle("దగ్గు గురించి మళ్లీ ఎప్పుడు అడగాలి?")
                    .setSingleChoiceItems(names, checked, (d, w) -> {
                        coughGap = gaps[w];
                        coughGapText.setText("⏱️ ఒకసారి అడిగాక మళ్లీ అడగడానికి: " + Prefs.gapText(coughGap) + "  (మార్చడానికి నొక్కండి)");
                        d.dismiss();
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        box.addView(coughGapText);
        if (!CoughDetector.status.isEmpty()) note(CoughDetector.status);
        sfx = toggle("Iron Man సౌండ్ ఎఫెక్ట్ (పిలవగానే చిన్న శబ్దం)", prefs.sfx());
        button("అడుగుల లెక్కకి అనుమతి (Physical activity)", v -> requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 8));
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

        section("అనుమతులు, డేటా");
        button("అన్ని అనుమతులు ఇవ్వండి", v -> requestPermissions(MainActivity.corePermissions(), 5));
        button("సంభాషణ చెరిపేయి (జ్ఞాపకాలు, మిషన్లు అలాగే ఉంటాయి)", v -> {
            Store.get(this).clearChat();
            Toast.makeText(this, "సంభాషణ చెరిపేశాను", Toast.LENGTH_SHORT).show();
        });

        box = page; // the save button sits under the cards
        Button save = new Button(this);
        save.setText("సేవ్ చేయి");
        save.setAllCaps(false);
        save.setTextColor(0xFFFFFFFF);
        save.setTextSize(17);
        save.setBackground(Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 18, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        lp.topMargin = Ui.dp(this, 26);
        box.addView(save, lp);
        save.setOnClickListener(v -> { store(); finish(); });
        for (int i = 0; i < page.getChildCount(); i++) { // switches, sliders, choices in their card's colour
            View card = page.getChildAt(i);
            if (card.getTag() instanceof Integer) tint(card, (Integer) card.getTag());
        }
        updateFromIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.loadTheme(this);
        if (builtTheme != Ui.themeVersion) { recreate(); return; } // the theme changed while this screen was open
        showLock();
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
        screenInfo.setText(JarvisAccessibility.enabled() ? "✓ స్క్రీన్ యాక్సెస్: ఆన్" : "✗ స్క్రీన్ యాక్సెస్: ఆఫ్");
        screenInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        if (WakeService.wordStatus != null) s.append("\n\"Jarvis\" పదం: ").append(WakeService.wordStatus);
        else if (VoskModel.ready(this)) s.append("\n✓ \"Jarvis\" పదం: సిద్ధం");
        wakeInfo.setText(s.toString());
        notifyInfo.setPadding(0, Ui.dp(this, 6), 0, 0);
        voiceInfo.setText(VoiceIO.naturalError == null ? "" : "చివరిసారి సహజ గొంతు పనిచేయలేదు: " + VoiceIO.naturalError);
        showCheck();
    }

    /** A talk flag left on with no Jarvis screen, panel or Live open. */
    private static boolean stuckTalking() {
        return MainActivity.inConversation && !MainActivity.visible && !SheetActivity.open && !MainActivity.liveOn;
    }

    /** The "Jarvis చెక్" card: everything that stops Jarvis from telling him about messages or from speaking. */
    private void showCheck() {
        if (checkInfo == null) return;
        StringBuilder s = new StringBuilder();
        boolean access = NotifyListener.enabled(this);
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
        PowerManager pm = getSystemService(PowerManager.class);
        s.append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()) ? "✓ బ్యాటరీ సేవర్ మినహాయింపు: ఉంది\n"
                : "✗ బ్యాటరీ సేవర్ మినహాయింపు లేదు: ఫోన్ Jarvis ని ఆపేయవచ్చు\n");
        if (prefs.wakeReady()) s.append(WakeService.running ? "✓ \"Jarvis\" వేక్ వర్డ్: నడుస్తోంది\n" : "✗ \"Jarvis\" వేక్ వర్డ్: ఆగి ఉంది\n");
        if (stuckTalking()) s.append("✗ Jarvis 'మాట్లాడుతున్నాను' అనే స్థితిలో ఇరుక్కుంది\n");
        if (prefs.naturalVoice() && prefs.openAiKey().trim().isEmpty()) s.append("• సహజ గొంతుకి OpenAI key లేదు: ఫోన్ గొంతుతో మాట్లాడతాను\n");
        else if (prefs.naturalVoice() && VoiceIO.naturalError != null)
            s.append("• సహజ గొంతు చివరిసారి పనిచేయలేదు (ఫోన్ గొంతుతో మాట్లాడాను): ").append(VoiceIO.naturalError).append("\n");
        String last = NotifyListener.lastMessageNote;
        s.append("\nచివరి మెసేజ్: ").append(last == null || last.isEmpty() ? "Jarvis మొదలయ్యాక ఇంకా ఏ మెసేజ్ రాలేదు" : last);
        checkInfo.setText(s.toString().trim());
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

    private void store() {
        SharedPreferences.Editor e = prefs.sp.edit();
        String n = name.getText().toString().trim();
        e.putString("name", n.isEmpty() ? "Anil" : n);
        int chosen = provider.getCheckedRadioButtonId();
        e.putString("provider", chosen == 3 ? Prefs.GEMINI : chosen == 2 ? Prefs.ANTHROPIC : Prefs.OPENAI);
        e.putString("gemini_key", geminiKey.getText().toString().trim());
        String gm = geminiModel.getText().toString().trim();
        e.putString("gemini_model", gm);
        e.remove("gemini_auto_model"); // older versions picked a Gemini model by themselves
        if (chosen == 3 && gm.isEmpty())
            Toast.makeText(this, "Gemini మోడల్ ఇంకా ఎంచుకోలేదు: 'అన్ని మోడల్స్ చూపించు' నొక్కి ఒకటి ఎంచుకోండి", Toast.LENGTH_LONG).show();
        e.putString("openai_key", openAiKey.getText().toString().trim());
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
        int ww = wakeWhen.getCheckedRadioButtonId();
        e.putString("wake_when", ww == 22 ? "charging" : ww == 21 ? "screen_on" : "always");
        e.putBoolean("announce_calls", announceCalls.isChecked());
        e.putBoolean("call_voice", callVoice.isChecked());
        e.putBoolean("read_messages", readMessages.isChecked());
        e.putBoolean("read_groups", readGroups.isChecked());
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
        e.putBoolean("live", liveMode.isChecked());
        e.putBoolean("live_patient", livePatient.isChecked());
        e.putBoolean("scam_guard", scamGuard.isChecked());
        saveBalance(balGemini, Usage.GEMINI);
        saveBalance(balOpenAi, Usage.OPENAI);
        saveBalance(balAnthropic, Usage.ANTHROPIC);
        e.putBoolean("barge_in", bargeIn.isChecked());
        e.putInt("barge_sens", bargeSens.getProgress());
        e.putBoolean("barge_call_voice", bargeCallVoice.isChecked());
        e.putString("realtime_model", realtimeModel.getText().toString().trim());
        if ((liveMode.isChecked() || natural.isChecked()) && openAiKey.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "సహజ గొంతు, Live సంభాషణకి OpenAI key కావాలి", Toast.LENGTH_LONG).show();
        }
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
        e.putInt("listen_window", listenWindow.getProgress() + 3);
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
        MedicalId.update(this);
        Reminders.scheduleBriefing(this);
        WakeService.stop(this); // restarts with the new settings when the main screen opens
        if (wake.isChecked() && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 6);
        }
        Toast.makeText(this, "సేవ్ చేశాను", Toast.LENGTH_SHORT).show();
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
        tester.speak(key, v, "నమస్కారం " + (n.isEmpty() ? "Anil" : n) + ". నేను Jarvis. మీ సేవలో ఎప్పుడూ సిద్ధంగా ఉంటాను.", new NaturalVoice.Callback() {
            @Override public void onStart() { voiceInfo.setText("గొంతు: " + v); }
            @Override public void onDone() { voiceInfo.setText("గొంతు: " + v + " ✓"); }
            @Override public void onError(String message) { voiceInfo.setText("పనిచేయలేదు: " + message); }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        tester.stop();
    }

    @Override protected void onDestroy() {
        try { prefs.sp.unregisterOnSharedPreferenceChangeListener(lockCalibrated); } catch (Exception ignored) {}
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

    @android.annotation.SuppressLint("MissingPermission")
    private void chooseCar() {
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
                .setTitle("మీ కార్/బైక్ ఏది?")
                .setItems(names, (d, which) -> {
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("car_bt", list.get(which).getAddress()).apply();
                    carInfo.setText("ఎంచుకున్నారు: " + names[which] + " ✓");
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private void showListenWindow() {
        if (listenWindowLabel != null) listenWindowLabel.setText("పిలిచాక మీరు మాట్లాడటం మొదలుపెట్టే దాకా వినే సమయం: " + (listenWindow.getProgress() + 3) + " సెకన్లు");
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

    private static final String[][] LOOKS = {
            {"మీరు", "👤"}, {"థీమ్", "🎨"}, {"Jarvis మెదడు", "🧠"}, {"కోడింగ్", "💻"}, {"వాయిస్", "🔊"}, {"సహజ గొంతు", "🗣️"},
            {"Live", "🎙️"}, {"వేక్ వర్డ్", "👂"}, {"కాల్స్", "📞"}, {"స్క్రీన్", "📱"}, {"పవర్ బటన్", "🔘"},
            {"మెసేజ్", "💬"}, {"తనంతట", "✨"}, {"స్మార్ట్ హోమ్", "🏠"}, {"కాపలా", "🛡️"}, {"అత్యవసరం", "🆘"}, {"టికెట్", "🎟️"},
            {"WhatsApp", "🖼️"}, {"డాక్యుమెంట్", "📄"}, {"కార్", "🏍️"}, {"ఆరోగ్యం", "❤️"}, {"అప్డేట్", "⬆️"}, {"అనుమతులు", "🔐"},
            {"API ఖర్చు", "💰"}, {"మోసం", "🛡️"}, {"చెక్", "🩺"}};
    /** Read when used, so they follow the theme. */
    private static int[] cardColors() { return new int[]{Ui.C_SKY, Ui.C_VIOLET, Ui.C_BLUE, Ui.C_CYAN, Ui.C_PINK, Ui.C_BLUE, Ui.C_TEAL,
            Ui.C_GREEN, Ui.C_SKY, Ui.C_AMBER, Ui.C_GREEN, Ui.C_VIOLET, Ui.C_AMBER, 0xFFF43F5E, Ui.C_PINK, Ui.C_GREEN, Ui.C_ORANGE,
            Ui.C_TEAL, 0xFFF43F5E, Ui.C_CYAN, Ui.C_AMBER, Ui.C_GREEN, 0xFFF43F5E, Ui.C_GREEN}; }
    private int cards;

    /** A new section: its own glass card in its own colour, with an emoji and the title. */
    private View section(String s) {
        String emoji = "✦";
        int[] colors = cardColors();
        int color = colors[cards % colors.length];
        for (int i = 0; i < LOOKS.length; i++) {
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
        TextView t = Ui.text(this, s, 17, color);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        page.addView(card, lp);
        box = card;
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
        if (want == null || want.isEmpty() || pageScroll == null) return;
        i.removeExtra(EXTRA_SECTION);
        for (Object[] t : cardTitles) {
            if (!((String) t[0]).contains(want)) continue;
            final View card = (View) t[1];
            pageScroll.post(() -> {
                pageScroll.smoothScrollTo(0, Math.max(0, card.getTop() - Ui.dp(this, 8)));
                card.animate().alpha(0.45f).setDuration(220).withEndAction(() -> card.animate().alpha(1f).setDuration(380).start()).start();
            });
            return;
        }
    }

    private void updateFromIntent(Intent i) {
        jumpToSection(i);
        if (i == null || !i.getBooleanExtra(EXTRA_UPDATE_NOW, false)) return;
        i.removeExtra(EXTRA_UPDATE_NOW);
        if (pageScroll != null && updatesHeader != null) {
            pageScroll.post(() -> pageScroll.smoothScrollTo(0, Math.max(0, updatesHeader.getTop() - Ui.dp(this, 8))));
        }
        if (!Updater.busy()) startUpdate();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateFromIntent(intent);
    }
}
