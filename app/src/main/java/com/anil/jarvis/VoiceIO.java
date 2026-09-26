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
    private final Prefs prefs;
    private final NaturalVoice natural = new NaturalVoice();
    /** Last reason the natural voice failed (shown in settings); null when it worked. */
    static volatile String naturalError;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    /** The phone's text-to-speech could not start: speech is skipped (onSpeakDone still arrives). */
    private boolean ttsFailed;
    /** shutdown() was called: the screen is gone, so stay silent and call nobody back. */
    private boolean shut;
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

    VoiceIO(Context c, Prefs prefs, Listener l) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.l = l;
        tts = new TextToSpeech(ctx, status -> main.post(() -> onTtsInit(status)));
    }

    private void onTtsInit(int status) {
        if (shut || tts == null) return; // shut down before the engine answered
        ttsChecked = true;
        if (status != TextToSpeech.SUCCESS) {
            voiceInfo = "ఈ ఫోన్‌లో Text-to-speech పనిచేయడం లేదు.";
            ttsFailed = true;
            l.onVoiceReady();
            // A reply was waiting for the engine: it can't be spoken, so finish that turn anyway.
            if (pending != null) failSpeak();
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
            speakGoogle(p, pendingRate);
        }
    }

    private void finishSpeaking(String id) {
        if (!("j" + utterance).equals(id)) return; // an older utterance that was replaced
        if (!speaking) return;
        speaking = false;
        l.onSpeakDone();
    }

    void speak(String text, float rate) {
        if (shut || text == null || text.trim().isEmpty()) return;
        String key = prefs.openAiKey().trim();
        if (prefs.naturalVoice() && !key.isEmpty() && Net.online(ctx)) {
            speakNatural(key, text, rate);
            return;
        }
        speakGoogle(text, rate);
    }

    /** OpenAI voice; falls back to the phone's Google voice if it fails before any sound. */
    private void speakNatural(String key, String text, float rate) {
        stopGoogle();
        String clean = text.replaceAll("[*_#`>]", "").replaceAll("https?://\\S+", "").trim();
        if (clean.length() > 3500) clean = clean.substring(0, 3500);
        speaking = true;
        final String said = clean;
        natural.speak(key, prefs.naturalVoiceName(), said, new NaturalVoice.Callback() {
            @Override public void onStart() {
                naturalError = null;
                l.onSpeakStart();
            }
            @Override public void onDone() {
                if (!speaking) return;
                speaking = false;
                l.onSpeakDone();
            }
            @Override public void onError(String message) {
                naturalError = message;
                if (speaking) speakGoogle(said, rate);
            }
        });
    }

    private void stopGoogle() {
        pending = null; // a reply still waiting for the engine is replaced too
        if (tts != null) {
            utterance++;
            tts.stop();
        }
    }

    private void speakGoogle(String text, float rate) {
        if (shut || tts == null) { speaking = false; pending = null; return; }
        if (ttsFailed) { failSpeak(); return; }
        if (!ttsReady) {
            pending = text;
            pendingRate = rate;
            speaking = true; // waiting for the engine counts as speaking (onSpeakDone follows either way)
            return;
        }
        String clean = text.replaceAll("[*_#`>]", "").replaceAll("https?://\\S+", "").trim();
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (clean.length() > max) clean = clean.substring(0, max);
        tts.setSpeechRate(rate);
        try { tts.setLanguage(Lang.of(clean)); } catch (Exception ignored) {} // Hindi etc. for translations
        utterance++;
        speaking = true;
        int r;
        try { r = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, new Bundle(), "j" + utterance); }
        catch (Exception e) { r = TextToSpeech.ERROR; }
        if (r != TextToSpeech.SUCCESS) failSpeak(); // no progress callbacks will come for it
    }

    /** Speech could not start: end this turn as if it had been spoken, so the screen doesn't hang. */
    private void failSpeak() {
        pending = null;
        speaking = true;
        final int u = ++utterance;
        main.post(() -> {
            if (shut || u != utterance || !speaking) return; // stopped or replaced meanwhile
            speaking = false;
            l.onSpeakDone();
        });
    }

    void stopSpeaking() {
        pending = null;
        natural.stop();
        if (speaking) {
            speaking = false;
            if (tts != null) tts.stop();
        }
    }

    boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    // Keep the mic open for a few seconds after "Jarvis": the phone's recognizer gives up
    // quickly in silence (or on the tail of Jarvis's own greeting), so quietly start it again.
    private long windowUntil;
    private boolean heardSpeech;
    private Intent lastIntent;

    private static boolean retryable(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_AUDIO; // the wake-word mic may still be letting go
    }

    private boolean restartIfEarly(int error) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (heardSpeech || lastIntent == null || sr == null || now > windowUntil - 400 || !retryable(error)) return false;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!listening || sr == null || lastIntent == null) return;
            try { sr.cancel(); sr.startListening(lastIntent); } catch (Exception e) { listening = false; l.onListenFailed(error); }
        }, error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 350 : 150);
        return true;
    }

    void listen(String lang) {
        if (shut) return;
        stopSpeaking();
        windowUntil = android.os.SystemClock.elapsedRealtime() + prefs.listenWindowSeconds() * 1000L;
        heardSpeech = false;
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { l.onListening(); }
                @Override public void onBeginningOfSpeech() { heardSpeech = true; }
                @Override public void onRmsChanged(float rmsdB) { l.onLevel((rmsdB + 2f) / 12f); }
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) {
                    if (listening && restartIfEarly(error)) return; // still inside the listening window
                    listening = false;
                    l.onListenFailed(error);
                }
                @Override public void onResults(Bundle results) {
                    String heard = first(results);
                    if (heard.isEmpty() && listening && restartIfEarly(SpeechRecognizer.ERROR_NO_MATCH)) return;
                    listening = false;
                    l.onHeard(heard);
                }
                @Override public void onPartialResults(Bundle partial) {
                    String s = first(partial);
                    if (!s.isEmpty()) { heardSpeech = true; l.onPartial(s); }
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
        if (!Net.online(ctx)) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true); // Telugu offline pack, if downloaded
        // ask for a patient recognizer (some phones ignore these; the restart above covers them)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, prefs.listenWindowSeconds() * 1000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        lastIntent = i;
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
        windowUntil = 0;
        if (sr != null) sr.cancel();
        listening = false;
    }

    void shutdown() {
        shut = true;
        ttsReady = false;
        pending = null;
        speaking = false;
        listening = false;
        natural.stop();
        if (sr != null) { sr.destroy(); sr = null; }
        if (tts != null) {
            TextToSpeech t = tts;
            tts = null;
            try { t.stop(); t.shutdown(); } catch (Exception ignored) {}
        }
    }
}
