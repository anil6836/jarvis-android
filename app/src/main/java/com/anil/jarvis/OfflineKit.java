package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Jarvis needs to hear and talk Telugu without internet: hearing (the phone's own Telugu offline pack where it has
 * one, else Jarvis's own Telugu ears, TeluguEars, downloaded once) and the phone's offline Telugu voice. Checked when
 * Settings opens (kept for "Jarvis చెక్"); the download buttons ask Android for the phone's parts.
 */
final class OfflineKit {
    private OfflineKit() {}

    static final String TE = "te-IN", EN = "en-IN";
    /** installed / pending (downloading) / available (can be downloaded) / none (this phone has none) / "" (not known). */
    static final String INSTALLED = "installed", PENDING = "pending", AVAILABLE = "available", NONE = "none";

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_offline_kit", Context.MODE_PRIVATE); }

    static String hearing(Context c, String lang) { return sp(c).getString("stt_" + lang, ""); }

    static String voice(Context c) { return sp(c).getString("tts_te", ""); }

    /** The phone's own recognizer can be asked for (Android 12+). */
    static boolean onDevice(Context c) {
        try { return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c); } catch (Throwable e) { return false; }
    }

    /** Without internet, the language to hear in: his own (Telugu stays Telugu: Jarvis's own Telugu ears hear it). */
    static String hearIn(Context c, String wanted) {
        String w = wanted == null || wanted.isEmpty() ? TE : wanted;
        if (w.startsWith("en") && !INSTALLED.equals(hearing(c, w)) && INSTALLED.equals(hearing(c, EN))) return englishTag(c);
        return w;
    }

    /** The English the phone has offline (en-IN, or another English such as en-US). */
    static String englishTag(Context c) { return sp(c).getString("stt_en_tag", EN); }

    /** Jarvis can listen with the phone's own recognizer in this language now (its pack is on the phone). */
    static boolean useOnDevice(Context c, String lang) {
        if (lang != null && lang.startsWith("en") && INSTALLED.equals(hearing(c, EN))) return onDevice(c);
        return onDevice(c) && INSTALLED.equals(hearing(c, lang));
    }


    /** Looks at both (main thread); done runs on the main thread when the answers are in. */
    static void check(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        final int[] left = {2};
        Runnable one = () -> { if (--left[0] == 0 && done != null) done.run(); };
        checkHearing(app, () -> main.post(one));
        checkVoice(app, () -> main.post(one));
    }

    private static void checkHearing(Context app, Runnable done) {
        if (Build.VERSION.SDK_INT < 33 || !onDevice(app)) {
            if (Build.VERSION.SDK_INT >= 31 && !onDevice(app)) sp(app).edit().putString("stt_" + TE, NONE).putString("stt_" + EN, NONE).apply();
            done.run();
            return;
        }
        final boolean[] over = {false};
        SpeechRecognizer made = null;
        try {
            final SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
            made = r;
            Runnable finish = () -> {
                if (over[0]) return;
                over[0] = true;
                main.postDelayed(() -> { try { r.destroy(); } catch (Throwable ignored) {} }, 300);
                done.run();
            };
            main.postDelayed(finish, 6000); // (no answer: what was known stays)
            r.checkRecognitionSupport(intent(TE), app.getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override public void onSupportResult(android.speech.RecognitionSupport s) {
                    SharedPreferences.Editor e = sp(app).edit();
                    e.putString("stt_" + TE, state(s, TE));
                    // English: en-IN, or another English the phone has (en-US...)
                    String en = state(s, EN), tag = EN;
                    if (!INSTALLED.equals(en)) {
                        String other = firstEnglish(s.getInstalledOnDeviceLanguages());
                        if (other != null) { en = INSTALLED; tag = other; }
                    }
                    e.putString("stt_" + EN, en).putString("stt_en_tag", tag);
                    e.apply();
                    finish.run();
                }
                @Override public void onError(int error) { finish.run(); }
            });
        } catch (Throwable e) {
            if (made != null) try { made.destroy(); } catch (Throwable ignored) {}
            if (!over[0]) { over[0] = true; done.run(); }
        }
    }

    private static String firstEnglish(List<String> l) {
        if (l == null) return null;
        for (String x : l) if (x != null && x.toLowerCase(Locale.ROOT).startsWith("en")) return x.replace('_', '-');
        return null;
    }

    @android.annotation.TargetApi(33)
    private static String state(android.speech.RecognitionSupport s, String lang) {
        if (has(s.getInstalledOnDeviceLanguages(), lang)) return INSTALLED;
        if (has(s.getPendingOnDeviceLanguages(), lang)) return PENDING;
        if (has(s.getSupportedOnDeviceLanguages(), lang)) return AVAILABLE;
        return NONE;
    }

    private static boolean has(List<String> l, String lang) {
        if (l == null) return false;
        String base = lang.substring(0, 2);
        for (String x : l) {
            if (x == null) continue;
            String y = x.replace('_', '-');
            if (y.equalsIgnoreCase(lang) || y.equalsIgnoreCase(base)) return true;
        }
        return false;
    }

    private static Intent intent(String lang) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
    }

    /** Asks Android to download the offline speech pack (it may ask him to confirm). False when this phone can't. */
    static boolean downloadHearing(Context c, String lang) {
        if (Build.VERSION.SDK_INT < 33 || !onDevice(c)) return false;
        try {
            Context app = c.getApplicationContext();
            SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
            r.triggerModelDownload(intent(lang));
            sp(app).edit().putString("stt_" + lang, PENDING).apply();
            main.postDelayed(() -> { try { r.destroy(); } catch (Throwable ignored) {} }, 15000);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void checkVoice(Context app, Runnable done) {
        final TextToSpeech[] h = new TextToSpeech[1];
        final boolean[] over = {false};
        Runnable finish = () -> {
            if (over[0]) return;
            over[0] = true;
            TextToSpeech t = h[0];
            if (t != null) main.post(() -> { try { t.shutdown(); } catch (Throwable ignored) {} });
            done.run();
        };
        main.postDelayed(finish, 6000);
        try {
            h[0] = new TextToSpeech(app, status -> main.post(() -> {
                if (over[0]) return;
                String st = "";
                try {
                    TextToSpeech t = h[0];
                    if (status == TextToSpeech.SUCCESS && t != null) {
                        boolean offline = false, any = false;
                        Set<Voice> vs = t.getVoices();
                        if (vs != null) {
                            for (Voice v : vs) {
                                if (v == null || v.getLocale() == null || !"te".equals(v.getLocale().getLanguage())) continue;
                                any = true;
                                Set<String> f = v.getFeatures();
                                boolean notThere = f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                                if (!notThere && !v.isNetworkConnectionRequired()) offline = true;
                            }
                        }
                        int avail = t.isLanguageAvailable(new Locale("te", "IN"));
                        st = offline ? INSTALLED : any || avail == TextToSpeech.LANG_MISSING_DATA || avail >= TextToSpeech.LANG_AVAILABLE ? AVAILABLE : NONE;
                        sp(app).edit().putString("tts_engine", t.getDefaultEngine() == null ? "" : t.getDefaultEngine()).apply();
                    }
                } catch (Throwable ignored) {}
                if (!st.isEmpty()) sp(app).edit().putString("tts_te", st).apply();
                finish.run();
            }));
        } catch (Throwable e) {
            finish.run();
        }
    }

    /** Opens the phone's voice download screen (its speech engine's "Install voice data"). */
    static boolean downloadVoice(Context c) {
        String engine = sp(c).getString("tts_engine", "");
        Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!engine.isEmpty()) i.setPackage(engine);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                c.startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /** One line for Settings and "Jarvis చెక్". */
    static String line(Context c) {
        String te = hearing(c, TE), v = voice(c);
        String hear = INSTALLED.equals(te) ? "తెలుగు (ఫోన్‌ది) ఉంది ✓"
                : TeluguEars.ready(c) ? "తెలుగు (Jarvis సొంతం) ఉంది ✓"
                : PENDING.equals(te) ? "ఫోన్ తెలుగు డౌన్‌లోడ్ అవుతోంది…"
                : AVAILABLE.equals(te) ? "తెలుగు లేదు (ఫోన్‌ది లేదా Jarvis సొంతం డౌన్‌లోడ్ చేయొచ్చు)"
                : "తెలుగు లేదు: క్రింద \"Jarvis తెలుగు వినడం\" డౌన్‌లోడ్ చేయండి";
        String say = INSTALLED.equals(v) ? "తెలుగు గొంతు ఉంది ✓" : AVAILABLE.equals(v) ? "తెలుగు గొంతు లేదు (డౌన్‌లోడ్ చేయొచ్చు)"
                : NONE.equals(v) ? "ఫోన్ గొంతుకి తెలుగు లేదు" : "గొంతు ఇంకా తెలియదు";
        return "offline వినడం: " + hear + " · " + say;
    }
}
