package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/**
 * O29 / O30 without internet: English print read by the phone itself (a medicine strip, a bill, a board: ML Kit's text
 * reader inside the app) and English -> Telugu by Google's on-phone translation pack (about 30 MB, downloaded once,
 * only when he taps it in Settings). Never sent anywhere.
 */
final class OfflineRead {
    private OfflineRead() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_offline_read", Context.MODE_PRIVATE); }

    /** The English -> Telugu pack is on the phone. */
    static boolean ready(Context c) { return sp(c).getBoolean("te_pack", false); }

    private static Translator translator() {
        return Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.TELUGU).build());
    }

    /** His tap in Settings: the pack downloads (needs internet; background thread). "" when done, else the problem. */
    static String download(Context c) {
        Translator t = translator();
        try {
            Tasks.await(t.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 5, TimeUnit.MINUTES);
            sp(c).edit().putBoolean("te_pack", true).apply();
            return "";
        } catch (Exception e) {
            return e.getMessage() == null ? "డౌన్‌లోడ్ కాలేదు" : e.getMessage();
        } finally {
            try { t.close(); } catch (Exception ignored) {}
        }
    }

    /** English -> Telugu on the phone (background thread); null when the pack isn't there or it failed. */
    static String toTelugu(Context c, String english) {
        if (!ready(c) || english == null || english.trim().isEmpty()) return null;
        Translator t = translator();
        try {
            return Tasks.await(t.translate(english.trim()), 20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            try { t.close(); } catch (Exception ignored) {}
        }
    }

    /** The printed text in the picture (background thread); "" when none. */
    static String text(Bitmap pic) {
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            Text t = Tasks.await(r.process(InputImage.fromBitmap(pic, 0)), 15, TimeUnit.SECONDS);
            return t == null ? "" : t.getText().trim();
        } catch (Exception e) {
            return "";
        } finally {
            try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** O30: what to say about the picture without internet: its English in Telugu (or as it is, with how to get Telugu). */
    static String readAloud(Context c, Bitmap pic) {
        String en = text(pic).replaceAll("\\s+", " ").trim();
        if (en.isEmpty()) return "ఇక్కడ చదవగలిగే ఇంగ్లీష్ రాత కనిపించలేదు. దగ్గరగా, వెలుతురులో, నిటారుగా చూపించండి.";
        if (en.length() > 700) en = en.substring(0, 700);
        String te = toTelugu(c, en);
        if (te != null && !te.trim().isEmpty()) return "తెలుగులో: " + te.trim();
        return "ఇంగ్లీష్‌లో ఇలా రాసి ఉంది: " + en + (ready(c) ? "" : " (తెలుగులోకి మార్చాలంటే సెట్టింగ్స్ → Offline వాయిస్ → \"🌐 ఆఫ్‌లైన్ అనువాదం\" ఒకసారి డౌన్‌లోడ్ చేయండి.)");
    }

    /** O29: English words he said / typed, in Telugu without internet; null when there is no English in it. */
    static String sayInTelugu(Context c, String said) {
        String en = said == null ? "" : said.replaceAll("[^A-Za-z0-9 ,.'?!-]", " ").replaceAll("\\s+", " ").trim();
        if (en.replaceAll("[^A-Za-z]", "").length() < 3) return null;
        String te = toTelugu(c, en);
        if (te == null) return ready(c) ? "అనువాదం రాలేదు." : "ఇంగ్లీష్ → తెలుగు ఆఫ్‌లైన్ అనువాదం ప్యాక్ ఫోన్‌లో లేదు (సెట్టింగ్స్ → Offline వాయిస్ లో ఒకసారి డౌన్‌లోడ్ చేయండి).";
        return "\"" + en + "\" అంటే తెలుగులో: " + te.trim();
    }
}
