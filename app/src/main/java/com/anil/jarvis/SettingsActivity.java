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
    private Prefs prefs;
    private EditText name, openAiKey, openAiModel, anthropicKey, anthropicModel;
    private RadioGroup provider, lang, wakeWhen;
    private Switch callVoice, readMessages, batteryWarn, voiceLock, proactive, sfx, shakeWake, faceDown, nightSummary;
    private TextView carInfo;
    private SeekBar lockSlider, listenWindow;
    private TextView listenWindowLabel;
    private TextView lockInfo, docsInfo, waInfo;
    private EditText sosContacts, smartUrls, smartApp, walletMax;
    private Switch walletPay;
    private Switch alexaSpeak;
    private Switch web, voice, followUp, wake, natural, liveMode, bargeIn, jarvisWord, announceCalls, briefing, briefingSpeak, listenOnOpen, compactPanel;
    private TextView briefingTime, screenInfo;
    private int briefHour, briefMinute;
    private Spinner voicePick;
    private EditText realtimeModel;
    private TextView voiceInfo, notifyInfo;
    private final NaturalVoice tester = new NaturalVoice();
    private SeekBar rate, sensitivity;
    private TextView rateLabel, sensitivityLabel, wakeInfo;
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.INK);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, pad, pad, Ui.dp(this, 40));
        scroll.addView(box);
        setContentView(scroll);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "సెట్టింగ్స్", 24, Ui.TEXT);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        IconView close = new IconView(this, IconView.CLOSE, Ui.CYAN);
        close.setOnClickListener(v -> finish());
        close.setContentDescription("మూసేయి");
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        box.addView(head);

        // ---- you
        section("మీరు");
        name = field("మీ పేరు (Jarvis మిమ్మల్ని ఇలా పిలుస్తాడు)", prefs.name(), false);

        // ---- brain
        section("Jarvis మెదడు");
        note("ఏ కంపెనీ API key వాడతారో ఎంచుకోండి. Key మీ ఫోన్‌లో మాత్రమే ఉంటుంది.");
        provider = new RadioGroup(this);
        provider.addView(radio(1, "OpenAI (GPT)"));
        provider.addView(radio(2, "Anthropic (Claude)"));
        provider.check(prefs.isOpenAi() ? 1 : 2);
        box.addView(provider);

        openAiKey = field("OpenAI API key (sk-…)", prefs.openAiKey(), true);
        openAiModel = field("OpenAI మోడల్", prefs.openAiModel(), false);
        link("OpenAI key ఇక్కడ తీసుకోండి", "https://platform.openai.com/api-keys");
        anthropicKey = field("Anthropic API key (sk-ant-…)", prefs.anthropicKey(), true);
        anthropicModel = field("Anthropic మోడల్", prefs.anthropicModel(), false);
        link("Anthropic key ఇక్కడ తీసుకోండి", "https://console.anthropic.com/settings/keys");
        web = toggle("ఇంటర్నెట్ సెర్చ్ (వార్తలు, స్కోర్లు, ధరలు)", prefs.webSearch());

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
        TextView vl = Ui.text(this, "గొంతు ఎంచుకోండి (cedar = లోతైన మగ గొంతు, సిఫార్సు)", 14, Ui.MUTED);
        vl.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(vl);
        voicePick = new Spinner(this);
        ArrayAdapter<String> va = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, NaturalVoice.VOICES);
        voicePick.setAdapter(va);
        voicePick.setBackground(Ui.round(this, Ui.DEEP, Ui.LINE2, 12));
        int current = 0;
        for (int i = 0; i < NaturalVoice.VOICES.length; i++) if (NaturalVoice.VOICES[i].equals(prefs.naturalVoiceName())) current = i;
        voicePick.setSelection(current);
        box.addView(voicePick, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        button("ఈ గొంతు వినిపించు", v -> testVoice());
        voiceInfo = Ui.text(this, "", 14, Ui.MUTED);
        box.addView(voiceInfo);

        // ---- live conversation
        section("Live సంభాషణ (Real-time)");
        note("మనిషితో ఫోన్‌లో మాట్లాడినట్టే: మీరు మాట్లాడుతుంటే వింటుంది, వెంటనే జవాబిస్తుంది, మధ్యలో ఆపి మాట్లాడొచ్చు. OpenAI key కావాలి. సాధారణ మోడ్ కంటే ఎక్కువ ఖర్చు అవుతుంది; 45 సెకన్లు ఎవరూ మాట్లాడకపోతే తనంతట తానే ఆగిపోతుంది.");
        liveMode = toggle("Live సంభాషణ ఆన్ (మైక్ బటన్, Hey Jarvis రెండింటికీ)", prefs.liveMode());
        bargeIn = toggle("మధ్యలో ఆపి మాట్లాడటం (Jarvis తనంతట తానే ఆగిపోతుంటే ఇది ఆఫ్ చేయండి)", prefs.bargeIn());
        realtimeModel = field("Live మోడల్", prefs.realtimeModel(), false);

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
        smartUrls.setBackground(Ui.round(this, Ui.DEEP, Ui.LINE2, 12));
        int sp = Ui.dp(this, 12);
        smartUrls.setPadding(sp, sp, sp, sp);
        smartUrls.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(smartUrls, new LinearLayout.LayoutParams(-1, -2));
        smartApp = field("మీ స్మార్ట్ హోమ్ యాప్ పేరు (ఉదా: Homemate, Zeb Home, Wipro Next)", prefs.smartApp(), false);
        alexaSpeak = toggle("దగ్గర్లో Echo ఉంది: అవసరమైతే Jarvis \"Alexa, …\" అని పైకి చెప్పనివ్వు", prefs.alexaSpeak());

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
        note("మీ కార్/బైక్ బ్లూటూత్ ఎంచుకుంటే: కనెక్ట్ అవ్వగానే డ్రైవింగ్ మోడ్ ఆన్, దిగగానే ఆఫ్, బండి పెట్టిన చోటు గుర్తుపెట్టుకుంటుంది.");
        button("కార్/బైక్ బ్లూటూత్ ఎంచుకోండి", v -> chooseCar());
        carInfo = Ui.text(this, prefs.carBluetooth().isEmpty() ? "ఇంకా ఎంచుకోలేదు" : "ఎంచుకున్నారు ✓", 14, Ui.MUTED);
        box.addView(carInfo);
        shakeWake = toggle("ఫోన్ రెండుసార్లు ఊపితే Jarvis రావాలి", prefs.shakeWake());
        faceDown = toggle("ఫోన్ బోర్లా పెడితే సైలెంట్ (ఎత్తితే మళ్లీ సౌండ్)", prefs.faceDownSilent());
        nightSummary = toggle("రోజూ రాత్రి 9:30 కి ఈరోజు, రేపటి సారాంశం చెప్పు", prefs.nightSummary());
        button("స్క్రీన్ టైమ్ కోసం \"Usage access\" ఇవ్వండి", v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {}
        });

        section("ఆరోగ్యం, ఇతరాలు");
        sfx = toggle("Iron Man సౌండ్ ఎఫెక్ట్ (పిలవగానే చిన్న శబ్దం)", prefs.sfx());
        button("అడుగుల లెక్కకి అనుమతి (Physical activity)", v -> requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 8));
        note("హోమ్ స్క్రీన్ విడ్జెట్: హోమ్ స్క్రీన్ మీద ఖాళీ చోట నొక్కి పట్టుకుని → Widgets → Jarvis.");
        note("బ్లూటూత్ ఇయర్‌ఫోన్: బటన్ నొక్కి పట్టుకుంటే Jarvis ప్యానెల్ వస్తుంది (మొదటిసారి ఏ యాప్ అని అడిగితే Jarvis ఎంచుకోండి).");

        section("అనుమతులు, డేటా");
        button("అన్ని అనుమతులు ఇవ్వండి", v -> requestPermissions(MainActivity.corePermissions(), 5));
        button("సంభాషణ చెరిపేయి (జ్ఞాపకాలు, మిషన్లు అలాగే ఉంటాయి)", v -> {
            Store.get(this).clearChat();
            Toast.makeText(this, "సంభాషణ చెరిపేశాను", Toast.LENGTH_SHORT).show();
        });

        Button save = new Button(this);
        save.setText("సేవ్ చేయి");
        save.setTextColor(Ui.GOLD_INK);
        save.setTextSize(17);
        save.setBackground(Ui.round(this, Ui.GOLD, 0, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        lp.topMargin = Ui.dp(this, 26);
        box.addView(save, lp);
        save.setOnClickListener(v -> { store(); finish(); });
    }

    @Override protected void onResume() {
        super.onResume();
        showLock();
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
    }

    private void store() {
        SharedPreferences.Editor e = prefs.sp.edit();
        String n = name.getText().toString().trim();
        e.putString("name", n.isEmpty() ? "Anil" : n);
        e.putString("provider", provider.getCheckedRadioButtonId() == 2 ? Prefs.ANTHROPIC : Prefs.OPENAI);
        e.putString("openai_key", openAiKey.getText().toString().trim());
        e.putString("openai_model", openAiModel.getText().toString().trim());
        e.putString("anthropic_key", anthropicKey.getText().toString().trim());
        e.putString("anthropic_model", anthropicModel.getText().toString().trim());
        e.putBoolean("web_search", web.isChecked());
        e.putBoolean("voice", voice.isChecked());
        e.putBoolean("follow_up", followUp.isChecked());
        e.putBoolean("natural_voice", natural.isChecked());
        e.putBoolean("wake_jarvis", jarvisWord.isChecked());
        e.putBoolean("listen_on_open", listenOnOpen.isChecked());
        e.putBoolean("compact_panel", compactPanel.isChecked());
        int ww = wakeWhen.getCheckedRadioButtonId();
        e.putString("wake_when", ww == 22 ? "charging" : ww == 21 ? "screen_on" : "always");
        e.putBoolean("announce_calls", announceCalls.isChecked());
        e.putBoolean("call_voice", callVoice.isChecked());
        e.putBoolean("read_messages", readMessages.isChecked());
        e.putBoolean("battery_warn", batteryWarn.isChecked());
        e.putBoolean("briefing", briefing.isChecked());
        e.putInt("briefing_hour", briefHour);
        e.putInt("briefing_minute", briefMinute);
        e.putBoolean("briefing_speak", briefingSpeak.isChecked());
        e.putString("natural_voice_name", NaturalVoice.VOICES[Math.max(0, voicePick.getSelectedItemPosition())]);
        e.putBoolean("live", liveMode.isChecked());
        e.putBoolean("barge_in", bargeIn.isChecked());
        e.putString("realtime_model", realtimeModel.getText().toString().trim());
        if ((liveMode.isChecked() || natural.isChecked()) && openAiKey.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "సహజ గొంతు, Live సంభాషణకి OpenAI key కావాలి", Toast.LENGTH_LONG).show();
        }
        e.putFloat("rate", 0.5f + rate.getProgress() / 100f);
        e.putString("lang", lang.getCheckedRadioButtonId() == 12 ? "en-IN" : "te-IN");
        e.putBoolean("wake", wake.isChecked());
        e.putBoolean("wake_paused", false);
        e.putBoolean("voice_lock", voiceLock.isChecked() && VoiceLock.print(this) != null);
        e.putFloat("voice_lock_max", 0.30f + lockSlider.getProgress() / 100f);
        e.putBoolean("proactive", proactive.isChecked());
        e.putBoolean("sfx", sfx.isChecked());
        e.putBoolean("shake_wake", shakeWake.isChecked());
        e.putBoolean("facedown_silent", faceDown.isChecked());
        e.putBoolean("night_summary", nightSummary.isChecked());
        e.putInt("listen_window", listenWindow.getProgress() + 3);
        e.putString("sos_contacts", sosContacts.getText().toString().trim());
        e.putString("smart_urls", smartUrls.getText().toString().trim());
        e.putString("smart_app", smartApp.getText().toString().trim());
        e.putBoolean("alexa_speak", alexaSpeak.isChecked());
        e.putBoolean("wallet_pay", walletPay.isChecked());
        int max = 1000;
        try { max = Integer.parseInt(walletMax.getText().toString().trim()); } catch (Exception ignored) {}
        e.putInt("wallet_pay_max", Math.max(0, Math.min(10000, max)));
        e.putFloat("wake_threshold", 0.75f - sensitivity.getProgress() / 100f);
        e.apply();
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

    private void section(String s) {
        TextView t = Ui.mono(this, s.toUpperCase(Locale.ROOT), 13, Ui.CYAN2);
        t.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 8));
        box.addView(t);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        box.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(t);
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
        e.setBackground(Ui.round(this, Ui.DEEP, Ui.LINE2, 12));
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

    private void link(String label, String url) {
        TextView t = Ui.text(this, label + " →", 14.5f, Ui.CYAN);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        t.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });
        box.addView(t);
    }

    private void button(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 15.5f, Ui.TEXT);
        t.setBackground(Ui.round(this, Ui.PANEL, Ui.LINE2, 12));
        int p = Ui.dp(this, 13);
        t.setPadding(p, p, p, p);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(t, lp);
    }
}
