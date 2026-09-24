package com.anil.jarvis;

import android.Manifest;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Keys, voice and wake-word settings. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText name, openAiKey, openAiModel, anthropicKey, anthropicModel, picoKey;
    private RadioGroup provider, lang;
    private Switch web, voice, followUp, wake;
    private SeekBar rate;
    private TextView rateLabel, wakeInfo;
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

        // ---- wake word
        section("\"Jarvis\" వేక్ వర్డ్");
        note("ఫోన్ లాక్‌లో ఉన్నా \"Jarvis\" అని పిలిస్తే తెరుచుకుంటుంది. Picovoice ఉచిత AccessKey కావాలి. వినడం అంతా ఫోన్‌లోనే జరుగుతుంది.");
        wake = toggle("వేక్ వర్డ్ ఆన్", prefs.wakeWord());
        picoKey = field("Picovoice AccessKey", prefs.picoKey(), true);
        link("Picovoice AccessKey ఇక్కడ తీసుకోండి", "https://console.picovoice.ai/");
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
        StringBuilder s = new StringBuilder();
        s.append(Settings.canDrawOverlays(this) ? "✓ Display over other apps: ఇచ్చారు\n" : "✗ Display over other apps: ఇవ్వలేదు (లేకపోతే పిలిచినప్పుడు నోటిఫికేషన్ మాత్రమే వస్తుంది)\n");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        s.append(exempt ? "✓ బ్యాటరీ సేవర్ మినహాయింపు: ఉంది" : "✗ బ్యాటరీ సేవర్ మినహాయింపు: లేదు (ఫోన్ వేక్ వర్డ్‌ని ఆపేయవచ్చు)");
        if (WakeService.lastError != null) s.append("\nచివరి సమస్య: ").append(WakeService.lastError);
        wakeInfo.setText(s.toString());
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
        e.putFloat("rate", 0.5f + rate.getProgress() / 100f);
        e.putString("lang", lang.getCheckedRadioButtonId() == 12 ? "en-IN" : "te-IN");
        e.putBoolean("wake", wake.isChecked());
        e.putString("pico_key", picoKey.getText().toString().trim());
        e.apply();
        if (wake.isChecked() && picoKey.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "వేక్ వర్డ్‌కి Picovoice AccessKey పెట్టండి", Toast.LENGTH_LONG).show();
        }
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
