package com.anil.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Health Connect's "why does Jarvis read this?" page (Android shows it from its permission screen): what is read,
 * what for, and where it stays.
 */
public class HealthPrivacyActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF05080F);
        TextView t = new TextView(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        t.setTextColor(0xFFDCEEF5);
        t.setTextSize(16);
        t.setLineSpacing(0, 1.2f);
        t.setText("Jarvis ఆరోగ్య వివరాలు ఎందుకు చదువుతుంది?\n\n"
                + "Samsung Health (మీ వాచ్, ఫోన్) Health Connect లో పెట్టేవి Jarvis చదువుతుంది, రాయదు, మార్చదు: నిద్ర (దశలతో), అడుగులు, "
                + "గుండె వేగం, విశ్రాంతి గుండె వేగం, ఆక్సిజన్ (SpO2), బరువు, కొవ్వు శాతం, BP.\n\n"
                + "ఎందుకు: ఉదయం సంగతుల్లో రాత్రి నిద్ర, ఆక్సిజన్; ఎనర్జీ అంచనా; బాడీ స్కాన్; ఈరోజు ఎంత నడిచారో; నెల బాడీ రిపోర్ట్; "
                + "ఆదివారం ఆరోగ్య గ్రాఫ్; డాక్టర్ కోసం PDF.\n\n"
                + "ఎక్కడ ఉంటాయి: Health Connect నుంచి చదివినవి Jarvis దాచదు (అవసరమైనప్పుడు చూసి చెబుతుంది). వాచ్ సొంత గుండె వేగం, నడకలు ఫోన్‌లోనే ఉంటాయి "
                + "(మీ ఫోన్ Google బ్యాకప్ ఆన్‌లో ఉంటే మిగతా Jarvis డేటాతో పాటు అందులో కూడా). వారం గ్రాఫ్, ECG లు Downloads/Jarvis/health లో. "
                + "ఎవరికీ పంపను, అమ్మను. మీరు అడిగిన ప్రశ్నకు జవాబు చెప్పడానికి మాత్రమే ఆ జవాబులోని వివరాలు మీరు ఎంచుకున్న AI కి వెళ్తాయి (మిగతా పనుల్లాగే); "
                + "గొంతుతో చెప్పే మాటలు మీరు ఎంచుకున్న గొంతుకి (OpenAI సహజ గొంతు అయితే దానికి) వెళ్తాయి. ఇది వైద్య పరీక్ష కాదు, సలహా మాత్రమే.\n\n"
                + "ఆపడానికి: ఫోన్ Settings → Health Connect → App permissions → Jarvis.");
        sv.addView(t);
        setContentView(sv);
    }
}
