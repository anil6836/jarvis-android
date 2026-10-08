package com.anil.jarvis.watch;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * The watch's own speech service (Google voice typing on the watch), when he chose it for the watch and the watch
 * has one: his words are written out on the watch and only the words go to the phone. Main thread.
 */
final class Hear {
    private Hear() {}

    interface Callback {
        void level(float l);
        void partial(String text);
        void heard(String text);
        void failed(String why, boolean nothing);
    }

    private static SpeechRecognizer rec;
    private static Callback cb;

    static boolean available(Context c) {
        try { return SpeechRecognizer.isRecognitionAvailable(c); } catch (Exception e) { return false; }
    }

    static boolean active() { return rec != null; }

    static void start(Context c, String lang, Callback callback) {
        stop();
        cb = callback;
        try {
            rec = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
        } catch (Exception e) {
            rec = null;
            callback.failed("వాచ్‌లో వాయిస్ టైపింగ్ తెరవలేకపోయాను", false);
            return;
        }
        final SpeechRecognizer mine = rec;
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float db) { if (rec == mine) cb.level(Math.max(0f, Math.min(1f, (db + 2f) / 12f))); }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (rec != mine) return;
                Callback k = cb;
                release();
                boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                k.failed(why(error), nothing);
            }
            @Override public void onResults(Bundle b) {
                if (rec != mine) return;
                Callback k = cb;
                release();
                k.heard(first(b));
            }
            @Override public void onPartialResults(Bundle b) { if (rec == mine) cb.partial(first(b)); }
            @Override public void onEvent(int type, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        try {
            rec.startListening(i);
        } catch (Exception e) {
            release();
            callback.failed("వాచ్‌లో వాయిస్ టైపింగ్ మొదలవలేదు", false);
        }
    }

    /** He tapped: what was said so far. */
    static void finish() {
        if (rec != null) try { rec.stopListening(); } catch (Exception ignored) {}
    }

    static void stop() {
        if (rec != null) try { rec.cancel(); } catch (Exception ignored) {}
        release();
    }

    private static void release() {
        SpeechRecognizer r = rec;
        rec = null;
        if (r != null) try { r.destroy(); } catch (Exception ignored) {}
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() || l.get(0) == null ? "" : l.get(0).trim();
    }

    private static String why(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "వాచ్ వాయిస్ టైపింగ్‌కి ఇంటర్నెట్ అందలేదు";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ఏమీ వినిపించలేదు";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "వాచ్ మైక్ అనుమతి లేదు";
            case SpeechRecognizer.ERROR_AUDIO: return "వాచ్ మైక్ దొరకలేదు";
            case 12: case 13: return "వాచ్ వాయిస్ టైపింగ్‌లో తెలుగు లేదు: ఫోన్ Settings → ⌚ వాచ్ లో OpenAI / Gemini ఎంచుకోండి";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "వాచ్ వాయిస్ టైపింగ్ బిజీగా ఉంది";
            default: return "వాచ్ వాయిస్ టైపింగ్ పనిచేయలేదు (" + e + ")";
        }
    }
}
