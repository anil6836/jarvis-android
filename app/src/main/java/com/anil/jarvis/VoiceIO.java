package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
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
        /** Another Jarvis screen started listening (the panel over the app): this listen was stopped quietly. */
        default void onMicTaken() {}
        /** He finished talking and Jarvis's own ears are writing out the words (a moment). */
        default void onUnderstanding() {}
    }

    // What he said while Jarvis's speech was paused (see pausedHeard).
    static final int NEW = 0, HELD = 1, RESUMED = 2, STOPPED = 3;
    static final int CMD_NONE = 0, CMD_PAUSE = 1, CMD_RESUME = 2, CMD_STOP = 3;
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

    /**
     * The phone's call mode while Jarvis speaks: only with "call voice" on (his old way: the phone's echo canceller at full
     * strength, but Jarvis then sounds like a phone call and the volume keys work on the call volume). Otherwise Jarvis's
     * voice stays its clear assistant voice and the talk-over takes Jarvis's voice out of the mic itself (BargeIn.startOwn).
     */
    private void enterCall() {
        if (shut || !prefs.bargeIn() || !prefs.bargeCallVoice()) { call.exit(); return; }
        call.enter(true);
    }

    /** The volume the keys should work on while Jarvis speaks: the AI assistant volume (the call volume in call mode). */
    int volumeStream() {
        if (call.active()) return android.media.AudioManager.USE_DEFAULT_STREAM_TYPE;
        return Build.VERSION.SDK_INT >= 29 ? 11 : android.media.AudioManager.STREAM_MUSIC; // (11 = STREAM_ASSISTANT)
    }

    /** Jarvis's voice itself through the call stream (only with the "call voice" setting). */
    private boolean callVoice() { return call.active() && prefs.bargeCallVoice(); }

    /** Start watching for Anil talking over Jarvis (setting "మధ్యలో ఆపి మాట్లాడటం"). */
    private void watchBargeIn() {
        // (the home tablet: no talk-over mic while he speaks - on Android 8 it fought Jarvis's ears and his own voice
        // set it off; she stops him with a tap on him or 🎤)
        if (shut || paused || !prefs.bargeIn() || prefs.homeMode()) return;
        BargeIn.Callback cb = () -> {
            if (!speaking || shut || paused) return;
            pause(false); // hold, don't lose it: "కొనసాగించు" (or silence) carries on from here
            l.onBargeIn();
        };
        // the call path (his old way) or headphones: by loudness, as before; on the speaker: Jarvis's own echo removal
        if (call.active() || CallMode.headset(ctx.getSystemService(android.media.AudioManager.class))) barge.start(cb);
        else barge.startOwn(cb, naturalNow ? natural.echo : null);
    }

    /** Jarvis's own echo removal for the natural voice on the phone's speaker (talk-over without call mode), or null. */
    private EchoGuard ownEcho() {
        if (!prefs.bargeIn() || prefs.bargeCallVoice() || !prefs.liveAec()) return null;
        if (CallMode.headset(ctx.getSystemService(android.media.AudioManager.class))) return null;
        return BargeIn.Echo.get(ctx);
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
    /** The reply as shown (digits) and its spoken form (numbers as Telugu words), mapped onto each other for the highlight. */
    private String naturalShown = "";
    private Spoken.Out naturalSaid = Spoken.of("");
    private volatile String googleShown = "";
    private volatile Spoken.Out googleSaid = Spoken.of("");
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
            @Override public void onStart(String id) { main.post(() -> { if (shut) return; speaking = true; turnSounded(); Duck.on(ctx); watchBargeIn(); l.onSpeakStart(); }); }
            @Override public void onDone(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onError(String id) { main.post(() -> finishSpeaking(id)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> finishSpeaking(id)); }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                if (!("j" + utterance).equals(id)) return;
                final int a = googleBase + start, b = googleBase + end;
                googlePos = a;
                final Spoken.Out said = googleSaid; // written last in speakGoogle: read first
                final String shown = googleShown;
                main.post(() -> {
                    if (!speaking || paused || naturalNow) return;
                    int[] r = said.range(a, b); // back onto the text on screen (numbers there are digits)
                    l.onWord(shown, r[0], r[1]);
                });
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
        Duck.off();
        barge.stop();
        call.exit();
        l.onSpeakDone();
    }

    /** The feeling of what is being said now (for the face). */
    String feeling() { return feeling; }

    void speak(String text, float rate) {
        if (shut || text == null || text.trim().isEmpty()) return;
        MicQuiet.speaking(); // the media sound muted for the mic's beeps comes back before he must hear Jarvis
        if (turnHeardAt > 0 && turnSpeakAt == 0) turnSpeakAt = android.os.SystemClock.elapsedRealtime();
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
        final String shown = clean;
        Spoken.Out spoken = Spoken.of(clean).cut(3900); // numbers as Telugu words ("₹1,200" -> "వెయ్యి రెండు వందల రూపాయలు")
        final String said = spoken.text;
        naturalShown = shown;
        naturalSaid = spoken;
        naturalText = said;
        naturalWeight = weights(said);
        naturalBounds = bounds(said, naturalWeight);
        matched = new long[0][];
        matchedFor = -1;
        natural.voiceCall = callVoice();
        natural.echo = callVoice() ? null : ownEcho();
        natural.model = prefs.ttsModel();
        natural.speak(key, prefs.naturalVoiceName(), said, feeling, new NaturalVoice.Callback() {
            @Override public void onStart() {
                naturalError = null;
                turnSounded();
                Duck.on(ctx); // radio / music goes quiet while Jarvis talks
                watchBargeIn();
                l.onSpeakStart();
                main.removeCallbacks(wordTicker);
                main.post(wordTicker);
            }
            @Override public void onDone() {
                barge.stop();
                main.removeCallbacks(wordTicker);
                learnPace();
                if (!speaking) return;
                speaking = false;
                Duck.off();
                call.exit();
                l.onSpeakDone();
            }
            @Override public void onError(String message) {
                naturalError = message;
                naturalNow = false;
                if (speaking) speakGoogle(shown, rate);
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
        Spoken.Out said = Spoken.of(clean); // numbers as Telugu words; the highlight maps back onto the digits shown
        googleShown = clean;
        googleSaid = said; // after googleShown (onRangeStart reads them the other way round)
        speakGoogleFrom(said.text, 0, rate);
    }

    /** Phone voice: says full from position from (0 = all of it; later = carrying on after ▶). */
    private void speakGoogleFrom(String full, int from, float rate) {
        String clean = full.substring(from);
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (clean.length() > max) clean = clean.substring(0, max);
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(callVoice() ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT) // (the AI assistant volume)
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
        if (!Net.online(ctx)) localVoice(); // no internet: a voice that needs it would stay silent
        utterance++;
        speaking = true;
        int r;
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Whisper.gain()); // he whispered "Jarvis": answer softly
        try { r = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "j" + utterance); }
        catch (Exception e) { r = TextToSpeech.ERROR; }
        if (r != TextToSpeech.SUCCESS) failSpeak(); // no progress callbacks will come for it
    }

    /** The phone's voice for this language that is on the phone itself (when the one chosen needs the internet). */
    private void localVoice() {
        try {
            Voice v = tts.getVoice();
            if (v == null || v.getLocale() == null || !v.isNetworkConnectionRequired()) return;
            Locale want = v.getLocale();
            if (localPick != null && want.equals(localFor)) { tts.setVoice(localPick); return; }
            Voice pick = null;
            java.util.Set<Voice> vs = tts.getVoices();
            if (vs != null) for (Voice x : vs) {
                if (x == null || x.getLocale() == null || x.isNetworkConnectionRequired()) continue;
                java.util.Set<String> f = x.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (!x.getLocale().getLanguage().equals(want.getLanguage())) continue;
                boolean sameCountry = x.getLocale().getCountry().equals(want.getCountry());
                if (pick == null || sameCountry && !pick.getLocale().getCountry().equals(want.getCountry())) pick = x;
            }
            if (pick != null) { tts.setVoice(pick); localFor = want; localPick = pick; }
        } catch (Exception ignored) {}
    }

    /** The on-phone voice chosen for this language (looked up once). */
    private Locale localFor;
    private Voice localPick;

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
                    if (b > a) {
                        int[] r = naturalSaid.range(a, b);
                        l.onWord(naturalShown, r[0], r[1]);
                    }
                }
                main.postDelayed(this, 120);
            }
        }
    };

    /** Sound frames per unit of reading weight for this voice (learned from finished replies). */
    private static volatile double framesPerWeight = NaturalVoice.RATE / 8.0; // Telugu: ~8 letters a second
    /** Pauses in the text (commas, full stops): {weight where speech stops, weight where it starts again}. */
    private float[][] naturalBounds = new float[0][];

    private void learnPace() {
        float[] w = naturalWeight;
        if (w.length == 0 || !natural.downloaded() || natural.totalFrames() <= 0) return;
        double fpw = natural.totalFrames() / (double) w[w.length - 1];
        if (fpw > NaturalVoice.RATE / 40.0 && fpw < NaturalVoice.RATE / 3.0) framesPerWeight = framesPerWeight * 0.6 + fpw * 0.4;
    }

    // Pauses matched to the text (recomputed only when a new pause has arrived): {pauseStart, pauseEnd, boundary}.
    private long[][] matched = new long[0][];
    private double matchedFpw;
    private int matchedFor = -1;
    private boolean matchedDone;

    /**
     * The character being spoken now. The voice's own pauses are matched to the commas and full stops
     * of the text (trying a range of speaking speeds and keeping the one that lines up best), so within
     * each sentence the highlight moves from where it really started to where it really ends.
     */
    private int naturalWordAt() {
        float[] w = naturalWeight;
        if (w.length == 0) return -1;
        long played = natural.playedFrames();
        float total = w[w.length - 1];
        boolean done = natural.downloaded() && natural.totalFrames() > 0;
        long first = Math.max(0, natural.firstSound());
        if (played <= first) return 0;

        java.util.List<long[]> pz = natural.pauses();
        if (pz.size() != matchedFor || done != matchedDone) {
            double[] grid;
            if (done) grid = new double[]{natural.totalFrames() / (double) total};
            else {
                // the usual pace first, then further away (half to double): with no pauses to go by, the usual pace wins
                int[] order = {10, 9, 11, 8, 12, 7, 13, 6, 14, 15, 5, 16, 17, 18, 19, 20};
                grid = new double[order.length];
                for (int k = 0; k < order.length; k++) grid[k] = framesPerWeight * order[k] / 10.0;
            }
            double bestScore = -1e9;
            for (double f : grid) {
                double[] outFpw = new double[1];
                java.util.ArrayList<long[]> m = new java.util.ArrayList<>();
                double score = match(naturalBounds, pz, first, f, m, outFpw)
                        - 0.3 * Math.abs(Math.log(f / framesPerWeight)); // a tie goes to the usual pace
                if (score > bestScore) { bestScore = score; matched = m.toArray(new long[0][]); matchedFpw = outFpw[0]; }
            }
            matchedFor = pz.size();
            matchedDone = done;
        }
        float[][] bs = naturalBounds;
        long aF = first;
        double aW = 0, goal = -1;
        for (long[] m : matched) {
            long ps = m[0], pe = m[1];
            float[] b = bs[(int) m[2]];
            if (played < ps) { goal = aW + (played - aF) * (b[0] - aW) / (double) Math.max(1, ps - aF); break; }
            if (played < pe) { goal = b[0]; break; } // in the pause: stay on the last word
            aF = pe;
            aW = b[1];
        }
        if (goal < 0) {
            if (done && natural.totalFrames() > aF) goal = aW + (played - aF) * (total - aW) / (double) (natural.totalFrames() - aF);
            else goal = aW + (played - aF) / matchedFpw;
        }
        goal = Math.max(0, Math.min(total, goal));
        int lo = 0, hi = w.length - 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (w[mid] < goal) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /** Lines the voice's pauses up with the text's commas/full stops at pace fpw; returns how well it fits. */
    private static double match(float[][] bs, java.util.List<long[]> pz, long first, double fpw, java.util.List<long[]> out, double[] outFpw) {
        long aF = first;
        double aW = 0, dev = 0, f = fpw;
        int lastB = -1;
        for (long[] p : pz) {
            long ps = p[0], pe = p[1];
            if (ps <= aF) continue;
            boolean longPause = pe - ps >= NaturalVoice.RATE * 3 / 10; // 300 ms+: a full stop, not a comma
            double pred = aW + (ps - aF) / f;
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = lastB + 1; i < bs.length; i++) {
                if (longPause && bs[i][2] == 0) continue;
                double d = Math.abs(bs[i][0] - pred);
                if (d < bd) { bd = d; best = i; }
                if (bs[i][0] > pred + bd) break; // further ones only get worse
            }
            if (best < 0 || bd > Math.max(3.0, 0.25 * (pred - aW))) continue; // a pause the text has no mark for
            double seg = bs[best][0] - aW;
            if (seg > 5) f = f * 0.5 + ((ps - aF) / seg) * 0.5; // follow the voice's real pace
            dev += bd / Math.max(1.0, pred - aW);
            out.add(new long[]{ps, pe, best});
            aF = pe;
            aW = bs[best][1];
            lastB = best;
        }
        outFpw[0] = f;
        return out.size() - dev * 0.5;
    }

    /** Commas and full stops: {weight just before the mark, weight at the next word, 1 if a full stop}. */
    private static float[][] bounds(String t, float[] w) {
        java.util.ArrayList<float[]> out = new java.util.ArrayList<>();
        int n = t.length();
        for (int i = 0; i < n; i++) {
            if (!isMark(t.charAt(i))) continue;
            int j = i;
            while (j + 1 < n && (isMark(t.charAt(j + 1)) || Character.isWhitespace(t.charAt(j + 1)))) j++;
            int next = j + 1;
            if (next >= n) break;
            float end = i > 0 ? w[i - 1] : 0, start = w[next - 1];
            boolean stop = false;
            for (int k = i; k <= j; k++) { char c = t.charAt(k); if (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') stop = true; }
            out.add(new float[]{end, start, stop ? 1 : 0});
            i = j;
        }
        return out.toArray(new float[0][]);
    }

    private static boolean isMark(char c) {
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' || c == '।' || c == '\n';
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
            Duck.off();
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
        if (listening) cancelListening(); // ▶ while the mic listens for him: he chose to hear the rest
        MicQuiet.speaking();
        enterCall();
        if (naturalNow) {
            natural.resume();
            watchBargeIn();
            main.post(wordTicker);
        } else if (ttsReady) {
            String full = googleText;
            int from = sentenceStart(full, googlePos);
            while (from < full.length() && Character.isWhitespace(full.charAt(from))) from++;
            if (from >= full.length()) { speaking = false; Duck.off(); call.exit(); l.onSpeakDone(); return; }
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
            Duck.off();
            if (tts != null) tts.stop();
        }
    }

    boolean canListen() {
        return Ears.chosen(prefs) || SpeechRecognizer.isRecognitionAvailable(ctx)
                || !Net.online(ctx) && prefs.offlineAuto() && TeluguEars.use(ctx, prefs.listenLang());
    }

    // ---- listening
    // After "Jarvis" the mic stays open for the whole listen window, counted from the moment the mic is really open (the
    // greeting, the wake-word mic letting go and the recognizer starting up don't use it up). The phone's recognizer is
    // asked to keep the mic open that long (minimum length) instead of giving up in the first silence and being started
    // again (each start and stop switched the mic off and on, with the phone's beeps). Left alone it would then also
    // wait that long after he finishes, so Jarvis notices itself when he has finished (no new words, and the sound has
    // gone quiet) and asks for the result straight away. Where the recognizer still stops early (some phones, offline
    // voice typing) it is started again within the window, a fresh one each time, so a late "end" from the last try can
    // never end the new one; tries that fail before the mic even opens give up after a few instead of switching the mic
    // on and off for long. The phone's own mic beeps are kept quiet (MicQuiet): his media sound is muted while the mic
    // opens and just before it closes; while it is held open and listening, his sound is on.
    /** The recognizer stopped answering (no result, no error): see failText. */
    static final int ERROR_STUCK = 100;
    /** No internet, and the phone has no offline speech pack for this language (see OfflineKit). */
    static final int ERROR_NO_OFFLINE = 120;
    private static final int MAX_TRIES = 6;            // recognizer tries in one listen
    private static final int MAX_QUICK = 4;            // tries in a row that failed before the mic opened: give up
    private static final long READY_WAIT_MS = 4000;    // startListening .. the mic is open
    private static final long QUIET_WAIT_MS = 10000;   // after the last sign of words, waiting for the result
    private static final long HOLD_MIN_MS = 1500;      // less of the window left than this: no point holding the mic open
    private static final long DONE_WORDS_MS = 1300;    // no new words for this long ...
    private static final long DONE_QUIET_MS = 700;     // ... and quiet for this long: he has finished
    private static final long DONE_NOISY_MS = 4000;    // a loud place (never quiet): no new words for this long is enough
    private static final long DONE_BLIND_MS = 2500;    // no usable sound level from the phone: no new words for this long
    private static final long DONE_SOUND_MS = 1300;    // no words shown, but he spoke and then was quiet this long
    private static final long RESULT_WAIT_MS = 1500;   // after asking for the result: then the words heard so far are used
    private Intent lastIntent;
    private int session;              // each listen; retries and timers of an older one do nothing
    private long windowMs, windowUntil, hardUntil;
    private boolean ready;            // the mic is open in the current try
    private boolean recBusy;          // the recognizer is in a try
    private boolean wrapUp;           // he tapped "done": take what was said, no more tries
    private boolean ending;           // Jarvis noticed he finished and asked for the result
    private boolean holdOpen;         // this try asked the recognizer to keep the mic open (minimum length)
    private String partial = "";      // words heard so far in this listen
    private int tries, quick;         // tries in this listen; tries in a row that failed before the mic opened
    private boolean offlineTried;
    private long wordsAt;             // this try: when the words last changed (0: no words yet)
    private long begunAt;             // this try: when the recognizer last heard speech begin (0: not yet)
    private long minEnd;              // this try: when the recognizer's held-open time is over (it may close the mic then)
    private long tryKeep;             // this try: how long it asked the recognizer to hold the mic open
    private final Level level = new Level(); // this try: how loud, and when it was last loud
    /** This phone's recognizer shows words while he speaks (so no words means he hasn't spoken words). */
    private static volatile boolean givesWords;
    /**
     * Tries in a row in which the recognizer closed the mic early though asked to hold it open ([0] online, [1] offline
     * voice typing). From two on, its beeps are kept muted for the whole try; a try that held as asked clears it.
     */
    private static final int[] earlyEnds = new int[2];
    private boolean offlineTry() { return lastIntent != null && lastIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false); }
    private boolean holdIgnored() { return earlyEnds[offlineTry() ? 1 : 0] >= 2; }
    /** The held-open time is nearly over: mute for the closing beep. */
    private final Runnable nearEnd = this::nearEnd;

    private void nearEnd() { if (listening && recBusy && !shut) quiet(); }

    /** The window is over and no words came: Jarvis closes the mic itself (the phone may keep it open on a noise). */
    private final Runnable windowEnd = this::windowEnd;

    private void windowEnd() {
        // words, or the result already asked for (by Jarvis or his "done"): those finish it
        if (shut || !listening || !partial.isEmpty() || ending || wrapUp) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean talking = (level.useful() && level.loudAt > 0 && now - level.loudAt < DONE_SOUND_MS) || now - begunAt < 2000;
        if (talking && now < hardUntil) { // he may have just started (his first words can take a moment to show)
            main.postDelayed(windowEnd, 300);
            return;
        }
        trace("⏱");
        letGo(); // (muted for its closing beep)
        fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
    }
    private final Runnable watchdog = this::stuck;
    private final Runnable endCheck = this::checkEnd;
    /** The screen listening last (main thread): when another one starts, it lets go of the phone's voice service. */
    private static VoiceIO holder;
    /** The last listen, step by step, for "Jarvis చెక్" (▶ try, 🎙 mic open, ■ he finished, ✗n the phone stopped it). */
    static volatile String lastListen = "";
    /** The last answer's time, step by step, from when he stopped talking until Jarvis's voice was heard (for "Jarvis చెక్"). */
    static volatile String lastTurn = "";
    private static volatile long turnHeardAt, turnSpeakAt, turnVoiceEnd;
    private static volatile String turnEars = "";

    /** Jarvis's voice is heard now: if it is the answer to what he just said, how long it all took is kept. */
    private static void turnSounded() {
        long h = turnHeardAt, s = turnSpeakAt;
        if (h == 0 || s == 0) return;
        turnHeardAt = 0;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - h > 120_000) return;
        long from = turnVoiceEnd > 0 && turnVoiceEnd <= h && h - turnVoiceEnd < 60_000 ? turnVoiceEnd : h;
        String ears = turnEars;
        lastTurn = (ears.isEmpty() ? "" : ears.replace(" సె", "") + " · ") + "జవాబు ఆలోచన " + Ears.sec(s - h) + " · గొంతు " + Ears.sec(now - s)
                + " → మొత్తం " + Ears.sec(now - from) + " సె";
    }
    private final StringBuilder trace = new StringBuilder();
    private long traceStart;

    void listen(String lang) { listen(lang, 0); }

    /** A message he dictates (a reply): Jarvis's own ears let him talk up to a minute. */
    void listenLong(String lang) { longTalk = true; listen(lang, 0); }

    private boolean longTalk;

    /**
     * waitSeconds: how long to wait for him to start talking, when longer than the setting (the camera's always-on
     * listening waits longer, so the mic is opened and closed less often).
     */
    void listen(String lang, int waitSeconds) {
        if (shut) return;
        if (ears != null) { ears.cancel(); ears = null; } // an earlier listen still recording: let it go
        boolean dictation = longTalk;
        longTalk = false;
        if (paused && speaking) barge.stop(); // keep the paused speech: he may say "కొనసాగించు"
        else stopSpeaking();
        session++;
        main.removeCallbacks(watchdog);
        main.removeCallbacks(endCheck);
        main.removeCallbacks(windowEnd);
        windowMs = Math.max(Math.max(3, prefs.listenWindowSeconds()), waitSeconds) * 1000L;
        windowUntil = 0; // set when the mic is open
        hardUntil = android.os.SystemClock.elapsedRealtime() + windowMs + 10_000; // however it goes, the tries end by then
        partial = "";
        tries = 0;
        quick = 0;
        wrapUp = false;
        offlineTried = false;
        level.forget();
        trace.setLength(0);
        traceStart = android.os.SystemClock.elapsedRealtime();
        // no internet (and he left "offline" on): in Telugu, Jarvis's own Telugu ears (TeluguEars), or the phone's offline
        // voice typing where it has Telugu; whichever way of hearing is chosen (the AI's ears need the internet)
        boolean offlineNow = !Net.online(ctx) && prefs.offlineAuto();
        if (offlineNow) lang = OfflineKit.hearIn(ctx, lang);
        offlineLang = offlineNow ? lang : null;
        teluguNow = offlineNow && TeluguEars.use(ctx, lang);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        if (!Net.online(ctx)) { // Telugu offline pack, if downloaded
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            offlineTried = true;
        }
        // A pause of ~1 s ends his sentence (where the recognizer decides that; with the mic held open, checkEnd does).
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
        lastIntent = i;
        listening = true;
        VoiceIO other = holder;
        holder = this;
        if (other != null && other != this && other.listening && !other.shut) other.micTaken(); // (after listening = true here)
        once = prefs.micOnce();
        if (teluguNow) { trace("📴"); listenTelugu(dictation); return; }
        if (Ears.chosen(prefs) && !offlineNow) { listenEars(dictation); return; } // Jarvis's own mic: no beeps
        if (once && musicDown == null) musicDown = Duck.hold(ctx); // songs go quieter while the phone listens (not muted)
        if (offlineNow) {
            trace("📴");
            if (lang.startsWith("te") && !OfflineKit.useOnDevice(ctx, lang)
                    && (Build.VERSION.SDK_INT >= 33 || OfflineKit.NONE.equals(OfflineKit.hearing(ctx, OfflineKit.TE)))) {
                // nothing on this phone hears Telugu without internet yet: say how to get Jarvis's own (no English instead)
                final int s = session;
                main.post(() -> { if (s == session && listening && !shut) fail(TeluguEars.ERROR_NO_MODEL); });
                return;
            }
        }
        start();
    }

    /** This listen is Jarvis's own Telugu ears without internet. */
    private boolean teluguNow;
    /** This listen opens the phone's mic once (Prefs.micOnce). */
    private boolean once;
    /** Songs kept quieter while the phone's mic listens (mic once), let go when the listen ends. */
    private android.media.AudioFocusRequest musicDown;

    /** The phone's mic beep is about to sound: muted, unless the mic is opened only once (then songs are only quieter). */
    private void quiet() { if (!once) MicQuiet.hold(ctx, this); }

    private void musicUp() {
        android.media.AudioFocusRequest r = musicDown;
        musicDown = null;
        if (r != null) Duck.release(ctx, r);
    }

    /** Without internet, Jarvis's own Telugu ears: like listenEars, and the words show while he talks. */
    private void listenTelugu(boolean dictation) {
        final int s = session;
        final TeluguEars e = new TeluguEars(ctx);
        ears = e;
        e.start(windowMs, dictation, new Ears.Callback() {
            private boolean mine() { return s == session && ears == e && listening && !shut; }
            @Override public void opened() { if (mine()) { trace("🎙"); l.onListening(); } }
            @Override public void level(float v) { if (mine()) l.onLevel(v); }
            @Override public void partial(String text) { if (mine()) l.onPartial(text == null ? "" : text); } // ("": it was only a noise)
            @Override public void understanding() { if (mine()) { trace("■"); l.onUnderstanding(); } }
            @Override public void heard(String text) {
                if (!mine()) return;
                ears = null;
                if (text == null || text.trim().isEmpty()) { if (wrapUp) VoiceIO.this.heard(""); else fail(SpeechRecognizer.ERROR_NO_MATCH); }
                else VoiceIO.this.heard(text.trim());
            }
            @Override public void failed(int error) {
                if (!mine()) return;
                ears = null;
                fail(error);
            }
        });
    }

    /** This listen is without internet (the phone's offline voice typing), in this language; null otherwise. */
    private String offlineLang;

    /** Jarvis's own ears (Ears, or TeluguEars without internet): listening without the phone's speech service, so without its beeps. */
    private ListenMic ears;

    private void listenEars(boolean dictation) {
        final int s = session;
        final Ears e = new Ears(ctx, prefs);
        ears = e;
        trace("🎧");
        e.start(windowMs, dictation, new Ears.Callback() {
            private boolean mine() { return s == session && ears == e && listening && !shut; }
            @Override public void opened() { if (mine()) { trace("🎙"); l.onListening(); } }
            @Override public void level(float v) { if (mine()) l.onLevel(v); }
            @Override public void understanding() { if (mine()) { trace("■"); l.onUnderstanding(); } }
            @Override public void heard(String text) {
                if (!mine()) return;
                ears = null;
                if (text == null || text.trim().isEmpty()) { if (wrapUp) VoiceIO.this.heard(""); else fail(SpeechRecognizer.ERROR_NO_MATCH); }
                else VoiceIO.this.heard(text.trim());
            }
            @Override public void failed(int error) {
                if (!mine()) return;
                ears = null;
                fail(error);
            }
        });
    }

    /** A new Jarvis screen came up over another that is listening (main thread): that one lets go of the mic now. */
    static void yieldOthers(VoiceIO mine) {
        VoiceIO other = holder;
        if (other != null && other != mine && other.listening && !other.shut) other.micTaken();
    }

    /** The panel (or the app) started listening: this one stops without retrying and says so quietly. */
    private void micTaken() {
        cancelListening();
        l.onMicTaken();
    }

    /** One try of the recognizer: a fresh one each time, asked to keep the mic open for what is left of the window. */
    private void start() {
        if (shut || !listening || lastIntent == null) return;
        if (sr != null) { retry(150); return; } // the last try's recognizer is let go first
        tries++;
        long now = android.os.SystemClock.elapsedRealtime();
        long keep = windowUntil == 0 ? windowMs : windowUntil - now;
        tryKeep = keep;
        holdOpen = !once && keep >= HOLD_MIN_MS; // (mic once: the phone's own end of talk, nothing held open)
        if (holdOpen) lastIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, keep);
        else lastIntent.removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS);
        ending = false;
        wordsAt = 0;
        begunAt = 0;
        minEnd = holdOpen ? now + keep : 0;
        level.newTry();
        main.removeCallbacks(nearEnd);
        quiet(); // the phone's beep as the mic opens
        try {
            sr = newRecognizer();
            ready = false;
            recBusy = true;
            sr.startListening(lastIntent);
        } catch (Exception e) {
            letGo();
            trace("✗!");
            if (++quick < MAX_QUICK && tries < MAX_TRIES) retry(400); else fail(SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        trace("▶");
        alive(READY_WAIT_MS);
    }

    private SpeechRecognizer newRecognizer() {
        // without internet, the phone's own recognizer when its pack for this language is on the phone (Android 12+)
        String ol = offlineLang;
        final SpeechRecognizer r = ol != null && offlineTry() && OfflineKit.useOnDevice(ctx, ol) && Build.VERSION.SDK_INT >= 31
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx);
        r.setRecognitionListener(new RecognitionListener() {
            /** Only the recognizer in use, during a try: a let-go one, or a second "end" of the same try, is ignored. */
            private boolean mine() { return sr == r && listening && recBusy && !shut; }
            @Override public void onReadyForSpeech(Bundle params) { if (mine() && !ready) opened(); }
            @Override public void onBeginningOfSpeech() { // maybe only a noise: real words come as partial results
                if (!mine()) return;
                if (!ready) opened();
                long now = android.os.SystemClock.elapsedRealtime();
                begunAt = now;
                // a long sentence (some phones give no partial results); never shorter than the window still to wait
                if (!ending) alive(Math.max(20_000, windowUntil > 0 ? windowUntil - now + QUIET_WAIT_MS : 0));
            }
            @Override public void onRmsChanged(float rmsdB) {
                if (!mine()) return;
                if (!ready) opened();
                level.add(rmsdB, android.os.SystemClock.elapsedRealtime());
                l.onLevel((rmsdB + 2f) / 12f);
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { if (mine()) alive(ending ? RESULT_WAIT_MS : QUIET_WAIT_MS); }
            @Override public void onError(int error) {
                if (!mine()) return;
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) endedEarly();
                recBusy = false;
                failed(error);
            }
            @Override public void onResults(Bundle results) {
                if (!mine()) return;
                endedEarly();
                recBusy = false;
                String heard = first(results);
                if (heard.isEmpty()) failed(SpeechRecognizer.ERROR_NO_MATCH); else heard(heard);
            }
            @Override public void onPartialResults(Bundle b) {
                if (!mine()) return;
                String s = first(b);
                if (s.isEmpty()) return;
                if (!ready) opened();
                givesWords = true;
                if (wordsAt == 0 || !s.equals(partial)) wordsAt = android.os.SystemClock.elapsedRealtime();
                partial = s;
                if (!ending) alive(QUIET_WAIT_MS);
                l.onPartial(s);
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        return r;
    }

    /** The mic is really open: the listen window starts now (once per listen). */
    private void opened() {
        ready = true;
        quick = 0;
        if (ending || wrapUp) return; // it is closing already (he tapped "done"): nothing more to open
        long now = android.os.SystemClock.elapsedRealtime();
        if (windowUntil == 0) {
            windowUntil = now + windowMs;
            main.removeCallbacks(windowEnd);
            main.postDelayed(windowEnd, windowMs + 1000);
        }
        hardUntil = Math.max(hardUntil, windowUntil + 10_000);
        alive(Math.max(0, windowUntil - now) + QUIET_WAIT_MS);
        trace("🎙");
        if (holdOpen) {
            main.removeCallbacks(endCheck);
            main.postDelayed(endCheck, 200);
            if (!holdIgnored()) { // the opening beep has passed: his sound is on while the mic is held open
                MicQuiet.release(this);
                main.removeCallbacks(nearEnd);
                main.postDelayed(nearEnd, Math.max(0, minEnd - 400 - now));
            }
        }
        l.onListening();
    }

    /**
     * The recognizer ended this try by itself: before its held-open time means it doesn't hold the mic open here (two
     * in a row: its beeps stay muted for whole tries); at the end of that time means it does.
     */
    private void endedEarly() {
        if (!holdOpen || !ready || ending || wrapUp || minEnd == 0) return;
        int k = offlineTry() ? 1 : 0;
        if (android.os.SystemClock.elapsedRealtime() < minEnd - 1000) {
            if (tryKeep > 10_000) return; // a long wait (the camera) cut short says little about the usual listen
            earlyEnds[k]++;
            trace("≠");
        } else {
            earlyEnds[k] = 0;
        }
    }

    /** The window has (nearly) run out for another try: the mic was open, and too little of it is left to hold it open. */
    private boolean spent(long now) {
        return ready && windowUntil > 0 && (windowUntil - now < HOLD_MIN_MS || (holdOpen && minEnd > 0 && now >= minEnd - 1000));
    }

    /** The sound level while listening: learns the room's quiet level and notes when it is clearly louder (his voice). */
    static final class Level {
        private float floorDb = Float.NaN; // the room's quiet level (kept over the tries of one listen)
        private float minDb = Float.NaN, maxDb = Float.NaN; // the range seen in this listen
        long loudAt;                       // when the sound was last clearly louder than the room (0: not yet)
        private long runMs;                // the loud stretch going on now (gaps under 250 ms joined)
        long longestRunMs;                 // the longest loud stretch in this try

        void forget() { floorDb = Float.NaN; minDb = Float.NaN; maxDb = Float.NaN; newTry(); }

        void newTry() { loudAt = 0; runMs = 0; longestRunMs = 0; }

        void add(float db, long now) {
            minDb = Float.isNaN(minDb) ? db : Math.min(minDb, db);
            maxDb = Float.isNaN(maxDb) ? db : Math.max(maxDb, db);
            if (Float.isNaN(floorDb)) floorDb = db;
            else if (db < floorDb) floorDb = floorDb * 0.7f + db * 0.3f;
            else floorDb += (db - floorDb) * 0.004f; // a steady noise slowly becomes the quiet level
            if (db > floorDb + 4f) {
                runMs = loudAt > 0 && now - loudAt <= 250 ? runMs + (now - loudAt) : 0;
                longestRunMs = Math.max(longestRunMs, runMs);
                loudAt = now;
            }
        }

        /** The phone gives a sound level that tells voice from quiet (some give none, or a flat one). */
        boolean useful() { return !Float.isNaN(maxDb) && maxDb - minDb >= 6f; }
    }

    /**
     * Has he finished speaking? wordsAt: when the words last changed (0: none in this try). With words: no new words
     * for a while and quiet, or no new words for long in a loud place (or, without a usable sound level, no new words
     * for longer). Without words: only on a phone that shows no words while he speaks, after one stretch of his voice
     * and quiet since (a noise or the road alone does not end it).
     */
    static boolean finished(long now, long wordsAt, Level l, boolean wordsExpected) {
        if (wordsAt > 0) {
            long noWords = now - wordsAt;
            if (!l.useful()) return noWords >= DONE_BLIND_MS;
            return (noWords >= DONE_WORDS_MS && now - l.loudAt >= DONE_QUIET_MS) || noWords >= DONE_NOISY_MS;
        }
        if (wordsExpected || !l.useful()) return false;
        return l.longestRunMs >= 700 && now - l.loudAt >= DONE_SOUND_MS;
    }

    /** The mic is held open: has he finished? Then the recognizer is asked for its result now, not at the window's end. */
    private void checkEnd() {
        if (shut || !listening || !recBusy || !holdOpen || ending || wrapUp || sr == null) return;
        if (!finished(android.os.SystemClock.elapsedRealtime(), wordsAt, level, givesWords)) {
            main.postDelayed(endCheck, 150);
            return;
        }
        ending = true;
        earlyEnds[offlineTry() ? 1 : 0] = 0; // the mic stayed open until he finished: holding works here
        trace("■");
        main.removeCallbacks(nearEnd);
        quiet(); // the phone's beep as the mic closes
        try {
            sr.stopListening(); // what was said so far is recognized as if he had stopped here
        } catch (Exception e) {
            if (!partial.isEmpty()) { heard(partial); return; }
        }
        alive(wordsAt > 0 ? RESULT_WAIT_MS : RESULT_WAIT_MS * 2);
    }

    /** The watchdog fires this long from now unless the recognizer shows life again. */
    private void alive(long ms) {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, ms);
    }

    /** A try ended without words: start again (within the window), or tell why. */
    private void failed(int error) {
        main.removeCallbacks(watchdog);
        main.removeCallbacks(endCheck);
        trace("✗" + error);
        long now = android.os.SystemClock.elapsedRealtime();
        // He spoke and words were heard, but the end went wrong: those words are what he said.
        if (!partial.isEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == 11 || wrapUp || ending)) {
            heard(partial);
            return;
        }
        if (wrapUp) { fail(error); return; }
        quick = !ready ? quick + 1 : 0; // failed before the mic even opened
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                fail(error);
                return;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                // no internet: once more with the phone's offline voice typing (when its pack is on the phone)
                if (!offlineTried && now < hardUntil && tries < MAX_TRIES) {
                    offlineTried = true;
                    lastIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                    retry(200);
                    return;
                }
                fail(!Net.online(ctx) ? ERROR_NO_OFFLINE : error); // (offline too: its pack isn't on the phone)
                return;
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                if (lastIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)) {
                    // the offline pack for this language is not on the phone: online, if there is internet
                    if (!Net.online(ctx)) { fail(ERROR_NO_OFFLINE); return; }
                    if (now < hardUntil && tries < MAX_TRIES) {
                        lastIntent.removeExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE);
                        retry(200);
                        return;
                    }
                }
                fail(error);
                return;
            default: {
                if (once && ready) { fail(error); return; } // mic once: it opened, he gets no second beep (he says "Jarvis" again)
                boolean inWindow = windowUntil == 0 ? now < hardUntil : now < windowUntil && now < hardUntil;
                // out of time (or too little left), too many tries, or it keeps failing before the mic opens: stop (and say why)
                if (!inWindow || spent(now) || tries >= MAX_TRIES || quick >= MAX_QUICK) { fail(error); return; }
                long delay = error == 10 ? 1000 // ERROR_TOO_MANY_REQUESTS
                        : error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 500
                        : quick > 0 ? 300L * quick : 150;
                retry(delay);
            }
        }
    }

    /** The next try, after a short gap (the recognizer of this try is let go at once). */
    private void retry(long delay) {
        final int s = session;
        main.removeCallbacks(watchdog);
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        letGo();
        main.postDelayed(() -> {
            if (shut || !listening || s != session) return;
            start();
        }, delay);
    }

    /** No result and no error for too long: the recognizer is let go (and a fresh one tried while there is time). */
    private void stuck() {
        if (shut || !listening) return;
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        boolean wasOpen = ready;
        trace("⌛");
        letGo();
        if (!partial.isEmpty()) { heard(partial); return; }
        if (!wasOpen) quick++;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean inWindow = windowUntil == 0 ? now < hardUntil : now < windowUntil && now < hardUntil;
        boolean tooLate = wasOpen && windowUntil > 0 && windowUntil - now < HOLD_MIN_MS || once && wasOpen;
        if (!wrapUp && inWindow && !tooLate && tries < MAX_TRIES && quick < MAX_QUICK) {
            retry(150);
            return;
        }
        fail(wasOpen ? SpeechRecognizer.ERROR_SPEECH_TIMEOUT : ERROR_STUCK); // the mic was open and nothing came / it never opened
    }

    private void heard(String text) {
        main.removeCallbacks(watchdog);
        main.removeCallbacks(windowEnd);
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        listening = false;
        session++;
        letGo();
        musicUp();
        MicQuiet.release(this);
        done("✓ విన్నాను");
        boolean own = Ears.chosen(prefs) && offlineLang == null;
        turnEars = teluguNow ? TeluguEars.lastTimes : own ? Ears.lastTimes : "";
        turnVoiceEnd = teluguNow ? TeluguEars.lastVoiceEnd : own ? Ears.lastVoiceEnd : 0;
        turnSpeakAt = 0;
        turnHeardAt = text == null || text.trim().isEmpty() ? 0 : android.os.SystemClock.elapsedRealtime();
        l.onHeard(text);
    }

    private void fail(int error) {
        main.removeCallbacks(watchdog);
        main.removeCallbacks(windowEnd);
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        listening = false;
        session++;
        letGo();
        musicUp();
        MicQuiet.release(this);
        done("✗ " + failText(error));
        l.onListenFailed(error);
    }

    /**
     * Lets go of the recognizer in use: cancelled in a moment (not from inside its own call), freed a second later, so
     * the phone's voice service stays connected for the next try and doesn't start up again each time. One still in a
     * try is cancelled with the sound muted for its closing beep (the caller's MicQuiet.release brings it back).
     */
    private void letGo() {
        final SpeechRecognizer r = sr;
        boolean open = recBusy;
        sr = null;
        recBusy = false;
        ready = false;
        if (r == null) return;
        if (open) quiet();
        main.post(() -> { try { r.cancel(); } catch (Exception ignored) {} });
        main.postDelayed(() -> { try { r.destroy(); } catch (Exception ignored) {} }, 1000);
    }

    private void trace(String step) {
        if (trace.length() < 200) trace.append(step).append(' ');
    }

    /** The listen is over: its steps, how it ended and how long it took, for "Jarvis చెక్". */
    private void done(String how) {
        long ms = android.os.SystemClock.elapsedRealtime() - traceStart;
        String when = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date());
        lastListen = when + " · " + trace.toString().trim() + " → " + how + " (" + String.format(Locale.ROOT, "%.1f", ms / 1000.0) + " సె.)";
    }

    /** Why listening ended without words, for the screen (null: nothing to say). */
    static String failText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "వాయిస్‌కి ఇంటర్నెట్ కావాలి. నెట్ చెక్ చేయండి.";
            case ERROR_NO_OFFLINE:
                return "నెట్ లేదు, ఈ భాషలో offline వినడం ఫోన్‌లో లేదు. తెలుగుకి: నెట్ ఉన్నప్పుడు Settings → Offline వాయిస్ లో \"Jarvis తెలుగు వినడం\" డౌన్‌లోడ్ చేయండి.";
            case TeluguEars.ERROR_NO_MODEL:
                return "నెట్ లేదు, తెలుగు offline వినడం ఇంకా ఫోన్‌లో లేదు. నెట్ ఉన్నప్పుడు Settings → Offline వాయిస్ లో \"Jarvis తెలుగు వినడం\" డౌన్‌లోడ్ చేయండి (ఒక్కసారే).";
            case TeluguEars.ERROR_BROKEN:
                return "Jarvis తెలుగు offline వినడం తెరవలేకపోయాను. నెట్ ఉన్నప్పుడు Settings → Offline వాయిస్ లో మళ్లీ డౌన్‌లోడ్ చేయండి.";
            case TeluguEars.ERROR_FAILED:
                return "తెలుగు వినడంలో సమస్య వచ్చింది, మళ్లీ చెప్పండి.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "మైక్ దొరకలేదు: వేరే యాప్ మైక్ వాడుతోందేమో.";
            case SpeechRecognizer.ERROR_SERVER:
            case 11: // ERROR_SERVER_DISCONNECTED
                return "మాటలు అర్థం చేసుకునే సేవ స్పందించలేదు.";
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case ERROR_STUCK:
                return "మైక్ సేవ ఇరుక్కుపోయింది.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "ఏమీ వినిపించలేదు.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "మైక్ అనుమతి లేదు.";
            case 10: // ERROR_TOO_MANY_REQUESTS
                return "వాయిస్ సేవ బిజీగా ఉంది, ఒక్క క్షణం ఆగండి.";
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                return "తెలుగు వాయిస్ టైపింగ్ లేదు. Google యాప్ → Settings → Voice → Languages లో తెలుగు జోడించండి.";
            case Ears.ERROR_NO_KEY:
                return "మాటలు వినడానికి ఎంచుకున్న AI (Settings → వాయిస్ → మాటలు వినే పద్ధతి) కి key లేదు. key పెట్టండి, లేదా వేరే పద్ధతి ఎంచుకోండి.";
            case Ears.ERROR_BAD_KEY:
                return "మాటలు వినే AI key పనిచేయడం లేదు (Settings → వాయిస్ చూడండి).";
            case Ears.ERROR_MODEL:
                return "మాటలు వినే మోడల్ పేరు తప్పుగా ఉంది (Settings → వాయిస్ → మోడల్ చూడండి).";
            case Ears.ERROR_QUOTA:
                return "మాటలు వినే AI ఖాతాలో బ్యాలెన్స్/లిమిట్ అయిపోయింది. రీఛార్జ్ చేయండి, లేదా Settings → వాయిస్‌లో వేరే పద్ధతి ఎంచుకోండి.";
            default:
                return "వినడంలో సమస్య (" + error + ").";
        }
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
    }

    /** "Done": take what he said (between two tries, the words heard so far). */
    void stopListening() {
        if (!listening) return;
        wrapUp = true;
        if (ears != null) { ears.finishNow(); return; } // what he said so far is written out
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        if (sr != null && recBusy) {
            quiet(); // the phone's beep as the mic closes
            try { sr.stopListening(); alive(ending ? RESULT_WAIT_MS : QUIET_WAIT_MS); return; } catch (Exception ignored) {}
        }
        if (!partial.isEmpty()) { heard(partial); return; }
        main.removeCallbacks(watchdog);
        main.removeCallbacks(windowEnd);
        listening = false;
        session++;
        letGo();
        musicUp();
        MicQuiet.release(this);
        done("ఆపారు");
        l.onHeard("");
    }

    void cancelListening() {
        boolean was = listening;
        if (ears != null) { ears.cancel(); ears = null; }
        session++;
        main.removeCallbacks(watchdog);
        main.removeCallbacks(windowEnd);
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        windowUntil = 0;
        letGo();
        listening = false;
        musicUp();
        MicQuiet.release(this);
        if (was) done("ఆపాను");
    }

    void shutdown() {
        shut = true;
        if (holder == this) holder = null;
        session++;
        main.removeCallbacks(watchdog);
        paused = false;
        main.removeCallbacks(wordTicker);
        barge.stop();
        call.exit();
        ttsReady = false;
        pending = null;
        speaking = false;
        Duck.off();
        listening = false;
        if (ears != null) { ears.cancel(); ears = null; }
        natural.stop();
        main.removeCallbacks(endCheck);
        main.removeCallbacks(nearEnd);
        main.removeCallbacks(windowEnd);
        letGo();
        MicQuiet.release(this);
        musicUp();
        if (tts != null) {
            TextToSpeech t = tts;
            tts = null;
            try { t.stop(); t.shutdown(); } catch (Exception ignored) {}
        }
    }
}
