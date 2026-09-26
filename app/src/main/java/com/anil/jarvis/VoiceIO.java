package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
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
        /** He started talking while Jarvis was speaking: speech is paused (not lost); listen to him now. */
        void onBargeIn();
        /** The word being spoken now: [start, end) in the spoken text (highlighted and scrolled into view). */
        default void onWord(String spoken, int start, int end) {}
    }

    // What he said while Jarvis's speech was paused (see pausedHeard).
    static final int NEW = 0, HELD = 1, RESUMED = 2, STOPPED = 3;
    private static final int CMD_NONE = 0, CMD_PAUSE = 1, CMD_RESUME = 2, CMD_STOP = 3;
    private static final String[] STOP_WORDS = {"చాలు", "వద్దు", "cancel", "క్యాన్సిల్", "enough"};
    private static final String[] RESUME_WORDS = {"కొనసాగించు", "కొనసాగించండి", "కొనసాగు", "కంటిన్యూ", "continue", "resume",
            "తర్వాత ఏమైంది", "గో ఆన్", "go on", "కానివ్వు"};
    /** Only when said on their own ("చెప్పు" inside "సినిమా గురించి చెప్పు" is a new question). */
    private static final String[] RESUME_ALONE = {"చెప్పు", "చెప్పండి", "ఇంకా చెప్పు", "తర్వాత చెప్పు", "ఆ తర్వాత", "తర్వాత", "ప్లే", "play",
            "ప్లే చెయ్", "ప్లే చేయి", "మళ్ళీ మొదలుపెట్టు", "మళ్లీ మొదలుపెట్టు", "ఓకే చెప్పు", "సరే చెప్పు"};
    private static final String[] PAUSE_WORDS = {"ఆపు", "ఆపండి", "ఆగు", "ఆగండి", "ఆపేయ్", "ఆపేయి", "ఆపెయ్", "ఆపవా", "ఆగవా", "స్టాప్", "stop",
            "pause", "పాజ్", "wait", "వెయిట్", "ఒక్క నిమిషం", "ఒక నిమిషం", "hold", "హోల్డ్"};

    /** A short spoken command about the speech itself ("ఆపు", "కొనసాగించు", "చాలు"), or CMD_NONE. */
    static int command(String heard) {
        String t = heard == null ? "" : heard.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}।]", " ")
                .replaceAll("జార్విస్|జార్వీస్|జార్విస|jarvis|ప్లీజ్|please|ఇక", " ")
                .replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.split(" ").length > 4) return CMD_NONE; // a real question, not a command
        for (String w : STOP_WORDS) if (t.contains(w)) return CMD_STOP;
        for (String w : RESUME_WORDS) if (t.contains(w)) return CMD_RESUME;
        for (String w : RESUME_ALONE) if (t.equals(w)) return CMD_RESUME;
        if (t.split(" ").length <= 3) for (String w : PAUSE_WORDS) if (t.contains(w)) return CMD_PAUSE;
        return CMD_NONE;
    }

    private final BargeIn barge;
    private final CallMode call;

    /** Talk-over on: send Jarvis's voice through the call path so the echo canceller removes it from the mic. */
    private void enterCall() {
        if (shut || !prefs.bargeIn()) { call.exit(); return; }
        call.enter(prefs.bargeCallVoice());
    }

    /** Jarvis's voice itself through the call stream (only with the "call voice" setting). */
    private boolean callVoice() { return call.active() && prefs.bargeCallVoice(); }

    /** Start watching for Anil talking over Jarvis (setting "మధ్యలో ఆపి మాట్లాడటం"). */
    private void watchBargeIn() {
        if (shut || paused || !prefs.bargeIn()) return;
        barge.start(() -> {
            if (!speaking || shut || paused) return;
            pause(false); // hold, don't lose it: "కొనసాగించు" (or silence) carries on from here
            l.onBargeIn();
        });
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
    private volatile String feeling = Emotion.CALM; // the tone of what is being said now
    private int utterance;
    /** ⏸ pressed: speech holds until ▶ (speaking stays true, so the screen waits). */
    private boolean paused;
    /** Paused on purpose (button or "ఆపు"), not just to hear him out: silence then does not resume it. */
    private boolean pausedByUser;
    /** Natural voice: the text being spoken and its reading-speed weights, for the word highlight. */
    private String naturalText = "";
    private float[] naturalWeight = new float[0];
    /** The natural (OpenAI) voice is the one speaking now (not the phone's voice). */
    private boolean naturalNow;
    /** Phone voice: what is being said, where it has got to, and how fast (to carry on after ▶). */
    private String googleText = "";
    private volatile int googlePos;
    /** Where the utterance now being spoken starts inside googleText (after ▶ it is the rest only). */
    private volatile int googleBase;
    private float googleRate = 1f;
    private SpeechRecognizer sr;
    boolean listening;
    boolean speaking;
    /** true when a Telugu voice is installed; false means speech falls back to the default voice. */
    boolean teluguVoice;
    /** true once the text-to-speech engine has answered (so teluguVoice is meaningful). */
    boolean ttsChecked;
    String voiceInfo = "వాయిస్ సిద్ధం అవుతోంది…";

    VoiceIO(Context c, Prefs prefs, Listener l) {
        this.barge = new BargeIn(c);
        this.call = new CallMode(c);
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
            @Override public void onStart(String id) { main.post(() -> { speaking = true; watchBargeIn(); l.onSpeakStart(); }); }
            @Override public void onDone(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onError(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> finishSpeaking(id)); }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                if (!("j" + utterance).equals(id)) return;
                final int a = googleBase + start, b = googleBase + end;
                googlePos = a;
                final String full = googleText;
                main.post(() -> { if (speaking && !paused && !naturalNow) l.onWord(full, a, b); });
            }
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
        barge.stop();
        call.exit();
        l.onSpeakDone();
    }

    void speak(String text, float rate) {
        if (shut || text == null || text.trim().isEmpty()) return;
        feeling = prefs.emotions() ? Emotion.forText(text) : Emotion.CALM;
        paused = false;
        pausedByUser = false;
        main.removeCallbacks(wordTicker);
        enterCall();
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
        naturalNow = true;
        String clean = text.replaceAll("[*_#`>]", "").replaceAll("https?://\\S+", "").trim();
        if (clean.length() > 3500) clean = clean.substring(0, 3500);
        speaking = true;
        final String said = clean;
        naturalText = said;
        naturalWeight = weights(said);
        natural.voiceCall = callVoice();
        natural.speak(key, prefs.naturalVoiceName(), said, feeling, new NaturalVoice.Callback() {
            @Override public void onStart() {
                naturalError = null;
                watchBargeIn();
                l.onSpeakStart();
                main.removeCallbacks(wordTicker);
                main.post(wordTicker);
            }
            @Override public void onDone() {
                barge.stop();
                main.removeCallbacks(wordTicker);
                if (!speaking) return;
                speaking = false;
                call.exit();
                l.onSpeakDone();
            }
            @Override public void onError(String message) {
                naturalError = message;
                naturalNow = false;
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
        speakGoogleFrom(clean, 0, rate);
    }

    /** Phone voice: says full from position from (0 = all of it; later = carrying on after ▶). */
    private void speakGoogleFrom(String full, int from, float rate) {
        String clean = full.substring(from);
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (clean.length() > max) clean = clean.substring(0, max);
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(callVoice() ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        } catch (Exception ignored) {}
        naturalNow = false;
        googleText = full;
        googleBase = from;
        googlePos = from;
        googleRate = rate;
        tts.setSpeechRate(rate * Emotion.pace(feeling));
        tts.setPitch(Emotion.pitch(feeling));
        try { tts.setLanguage(Lang.of(clean)); } catch (Exception ignored) {} // Hindi etc. for translations
        utterance++;
        speaking = true;
        int r;
        try { r = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, new Bundle(), "j" + utterance); }
        catch (Exception e) { r = TextToSpeech.ERROR; }
        if (r != TextToSpeech.SUCCESS) failSpeak(); // no progress callbacks will come for it
    }

    /** Natural voice has no word timings: estimate the word from how much sound has played. */
    private final Runnable wordTicker = new Runnable() {
        @Override public void run() {
            if (shut || !speaking || !naturalNow) return;
            if (!paused) {
                int at = naturalWordAt();
                if (at >= 0) {
                    String t = naturalText;
                    int a = at, b = at;
                    while (a > 0 && !Character.isWhitespace(t.charAt(a - 1))) a--;
                    while (b < t.length() && !Character.isWhitespace(t.charAt(b))) b++;
                    if (b > a) l.onWord(t, a, b);
                }
                main.postDelayed(this, 120);
            }
        }
    };

    private int naturalWordAt() {
        float[] w = naturalWeight;
        if (w.length == 0) return -1;
        long played = natural.playedFrames();
        if (played <= 0) return 0;
        float total = w[w.length - 1];
        double frames = natural.downloaded()
                ? natural.totalFrames()
                : Math.max(natural.totalFrames(), total * NaturalVoice.RATE / 12.0); // ~12 letters a second until known
        double goal = Math.min(1.0, played / Math.max(1.0, frames)) * total;
        int lo = 0, hi = w.length - 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (w[mid] < goal) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /** Cumulative reading time per character: letters 1, vowel signs less, pauses at commas and full stops. */
    private static float[] weights(String t) {
        float[] w = new float[t.length()];
        float sum = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            int type = Character.getType(ch);
            float x;
            if (ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '\n') x = 6f;
            else if (ch == ',' || ch == ';' || ch == ':') x = 3f;
            else if (Character.isWhitespace(ch)) x = 0.6f;
            else if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) x = 0.35f;
            else if (Character.isLetterOrDigit(ch)) x = 1f;
            else x = 0.5f;
            sum += x;
            w[i] = sum;
        }
        return w;
    }

    /** Speech could not start: end this turn as if it had been spoken, so the screen doesn't hang. */
    private void failSpeak() {
        pending = null;
        speaking = true;
        final int u = ++utterance;
        main.post(() -> {
            if (shut || u != utterance || !speaking) return; // stopped or replaced meanwhile
            speaking = false;
            call.exit();
            l.onSpeakDone();
        });
    }

    boolean isPaused() { return paused && speaking; }

    /** ⏸: hold Jarvis's speech right where it is. */
    void pause() { pause(true); }

    private void pause(boolean byUser) {
        if (!speaking || shut) return;
        if (paused) { pausedByUser |= byUser; return; }
        paused = true;
        pausedByUser = byUser;
        barge.stop();
        call.exit();
        if (naturalNow) {
            natural.pause();
        } else if (tts != null && ttsReady) {
            utterance++;            // the stop below must not count as "finished speaking"
            tts.stop();
        }
    }

    /** ▶: carry on (natural voice from the same word; phone voice from the start of the sentence). */
    void resume() {
        if (!paused || shut) return;
        paused = false;
        pausedByUser = false;
        if (!speaking) return;
        enterCall();
        if (naturalNow) {
            natural.resume();
            watchBargeIn();
            main.post(wordTicker);
        } else if (ttsReady) {
            String full = googleText;
            int from = sentenceStart(full, googlePos);
            while (from < full.length() && Character.isWhitespace(full.charAt(from))) from++;
            if (from >= full.length()) { speaking = false; call.exit(); l.onSpeakDone(); return; }
            speakGoogleFrom(full, from, googleRate);
        }
    }

    /**
     * He spoke while the speech was paused. Returns HELD (stays paused: he said "ఆపు", or nothing after
     * pausing on purpose), RESUMED ("కొనసాగించు", or nothing after a talk-over), STOPPED ("చాలు"), or
     * NEW: it is a new request, and the paused speech has been dropped.
     */
    int pausedHeard(String heard) {
        if (!isPaused()) return NEW;
        String t = heard == null ? "" : heard.trim();
        if (t.isEmpty()) {
            if (pausedByUser) return HELD;
            resume();
            return RESUMED;
        }
        switch (command(t)) {
            case CMD_PAUSE: pausedByUser = true; return HELD;
            case CMD_RESUME: resume(); return RESUMED;
            case CMD_STOP: stopSpeaking(); return STOPPED;
            default: stopSpeaking(); return NEW;
        }
    }

    /** Beginning of the sentence that contains pos. */
    private static int sentenceStart(String t, int pos) {
        int p = Math.max(0, Math.min(pos, t.length()));
        for (int i = p - 1; i >= 0; i--) {
            char ch = t.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '\n') return i + 1;
        }
        return 0;
    }

    void stopSpeaking() {
        paused = false;
        pausedByUser = false;
        main.removeCallbacks(wordTicker);
        barge.stop();
        call.exit();
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
        if (paused && speaking) barge.stop(); // keep the paused speech: he may say "కొనసాగించు"
        else stopSpeaking();
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
        // No minimum length: when he stops talking, answer right away. The listen window (waiting for him to START
        // talking) is kept by restartIfEarly(). A short pause of ~1 s ends his sentence.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
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
        paused = false;
        main.removeCallbacks(wordTicker);
        barge.stop();
        call.exit();
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
