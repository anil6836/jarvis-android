package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Locale;

/** Telugu listening (Android speech recognizer) and speaking (Android text-to-speech). */
final class VoiceIO {
    interface Listener {
        void onListening();
        void onPartial(String text);
        void onHeard(String text);
        void onListenFailed(int error);
        void onLevel(float level);
        void onSpeakStart();
        void onSpeakDone();
        void onVoiceReady();
    }

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pending;
    private float pendingRate = 1f;
    private int utterance;
    private SpeechRecognizer sr;
    boolean listening;
    boolean speaking;
    /** true when a Telugu voice is installed; false means speech falls back to the default voice. */
    boolean teluguVoice;
    /** true once the text-to-speech engine has answered (so teluguVoice is meaningful). */
    boolean ttsChecked;
    String voiceInfo = "వాయిస్ సిద్ధం అవుతోంది…";

    VoiceIO(Context c, Listener l) {
        this.ctx = c.getApplicationContext();
        this.l = l;
        tts = new TextToSpeech(ctx, status -> main.post(() -> onTtsInit(status)));
    }

    private void onTtsInit(int status) {
        ttsChecked = true;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            voiceInfo = "ఈ ఫోన్‌లో Text-to-speech పనిచేయడం లేదు.";
            l.onVoiceReady();
            return;
        }
        int r = tts.setLanguage(Locale.forLanguageTag("te-IN"));
        teluguVoice = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        if (teluguVoice) {
            Voice v = tts.getVoice();
            voiceInfo = "తెలుగు వాయిస్ సిద్ధం" + (v != null ? " (" + v.getName() + ")" : "");
        } else {
            voiceInfo = "తెలుగు వాయిస్ లేదు. Settings → Text-to-speech → Google → తెలుగు డౌన్‌లోడ్ చేయండి.";
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> { speaking = true; l.onSpeakStart(); }); }
            @Override public void onDone(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onError(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> finishSpeaking(id)); }
        });
        ttsReady = true;
        l.onVoiceReady();
        if (pending != null) {
            String p = pending;
            pending = null;
            speak(p, pendingRate);
        }
    }

    private void finishSpeaking(String id) {
        if (!("j" + utterance).equals(id)) return; // an older utterance that was replaced
        if (!speaking) return;
        speaking = false;
        l.onSpeakDone();
    }

    void speak(String text, float rate) {
        if (text == null || text.trim().isEmpty()) return;
        if (!ttsReady) {
            pending = text;
            pendingRate = rate;
            return;
        }
        String clean = text.replaceAll("[*_#`>]", "").replaceAll("https?://\\S+", "").trim();
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (clean.length() > max) clean = clean.substring(0, max);
        tts.setSpeechRate(rate);
        utterance++;
        speaking = true;
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, new Bundle(), "j" + utterance);
    }

    void stopSpeaking() {
        pending = null;
        if (tts != null && speaking) {
            speaking = false;
            tts.stop();
        }
    }

    boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    void listen(String lang) {
        stopSpeaking();
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { l.onListening(); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) { l.onLevel((rmsdB + 2f) / 12f); }
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) { listening = false; l.onListenFailed(error); }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    l.onHeard(first(results));
                }
                @Override public void onPartialResults(Bundle partial) {
                    String s = first(partial);
                    if (!s.isEmpty()) l.onPartial(s);
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        listening = true;
        sr.startListening(i);
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
    }

    void stopListening() {
        if (sr != null && listening) sr.stopListening();
    }

    void cancelListening() {
        if (sr != null) sr.cancel();
        listening = false;
    }

    void shutdown() {
        if (sr != null) { sr.destroy(); sr = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    }
}
