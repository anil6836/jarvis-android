package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speaks short announcements when the Jarvis screen may not be open
 * (incoming calls, reminders, the morning briefing).
 */
final class Announcer {
    private static TextToSpeech tts;
    private static boolean ready;
    private static final List<String> waiting = new ArrayList<>();
    private static final NaturalVoice natural = new NaturalVoice();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int n;
    /**
     * Announcements waiting for the natural voice (main thread only). A new NaturalVoice.speak()
     * cuts off the one playing, so they are spoken one after another.
     */
    private static final ArrayDeque<String> queue = new ArrayDeque<>();
    /** A natural-voice announcement is playing (main thread only). */
    private static boolean talking;
    /** Announcements handed to the phone's engine and not finished yet (main thread only). */
    private static int googlePending;

    private Announcer() {}

    /** Something is being said now, or was until a moment ago (any thread): a voice the mic hears now may be Jarvis's own. */
    private static volatile boolean busy;
    private static volatile long quietSince, busySince;

    /** Nothing was being said at this moment (wall-clock ms), nor in the 1.5 s before it: a voice then is not Jarvis's own. */
    static boolean silentAt(long t) {
        if (busy && System.currentTimeMillis() - busySince < 60_000) return false; // something is being said now: can't tell
        return quietSince == 0 || t - quietSince >= 1500;
    }

    /** (A "done" that never came - a broken voice engine - stops counting after a minute.) */
    static boolean speaking() {
        long now = System.currentTimeMillis();
        return (busy && now - busySince < 60_000) || now - quietSince < 800;
    }

    /** A voice of Jarvis's is actually sounding now (not fetching in silence), or stopped a moment ago (any thread). */
    private static volatile boolean sounding, phoneSounding;
    private static volatile long soundingSince, phoneSince;

    static boolean audible() {
        long now = System.currentTimeMillis();
        return (sounding && now - soundingSince < 60_000) || (phoneSounding && now - phoneSince < 60_000) || now - quietSince < 800;
    }

    /** The natural voice. */
    private static void sounding(boolean on) {
        sounding = on;
        if (on) soundingSince = System.currentTimeMillis();
    }

    /** The phone's own voice (its engine's queue). */
    private static void phoneSounding(boolean on) {
        if (!on && phoneSounding) phoneEnd = System.currentTimeMillis();
        phoneSounding = on;
        if (on) phoneSince = System.currentTimeMillis();
    }

    /** A phone-voice word is being said: the queue waits for it (main thread), so nothing talks over it. */
    private static boolean phoneHold;
    private static int holdToken;
    private static Context appRef;

    /** The car alert's answer window is open: announcements other than its own wait (main thread). A lost "off": 3 minutes. */
    private static volatile long heldSince;
    private static Context heldApp;

    private static boolean held() { return heldSince > 0 && System.currentTimeMillis() - heldSince < 3 * 60_000; }

    /** The car alert's question is out (on) / its window ended (off: what waited is said now). Any thread. */
    static void hold(Context c, boolean on) {
        final Context app = c.getApplicationContext();
        main.post(() -> {
            if (on) { heldSince = System.currentTimeMillis(); return; }
            if (heldSince == 0) return;
            heldSince = 0;
            Context a = heldApp != null ? heldApp : app;
            heldApp = null;
            if (!talking && !queue.isEmpty()) {
                busySince = System.currentTimeMillis();
                busy = true;
                Duck.on(a);
                next(a);
            }
        });
    }

    /** The phone's voice (the car's words, which say "Jarvis") is sounding, or stopped a moment ago. */
    static boolean phoneAudible() {
        long now = System.currentTimeMillis();
        return (phoneSounding && now - phoneSince < 60_000) || now - phoneEnd < 1500;
    }
    private static volatile long phoneEnd;

    /** The natural-voice text being said now (main thread), so a phone-voice word can go first and this one after. */
    private static String current;

    /**
     * Speak text with the voice chosen in settings. Safe to call from any thread. On the home tablet Jarvis on the big
     * screen says it (his lips, face and the words in his bubble), not a voice from the background.
     */
    static void say(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (RecorderService.recording) return; // a sermon / meeting is being recorded: silence (notifications still come)
        if (HomeCare.on(c) && MainActivity.homeAsk(text.trim(), Emotion.forText(text), false)) return;
        sayDirect(c, text);
    }

    /**
     * Said unasked and not about safety (a message read out, the news, a traffic note, a fuel-log reply): as {@link #say},
     * except on the car box while he chose 🔇 quiet for this trip — then only the notification / the screen has it.
     */
    static void chat(Context c, String text) {
        if (CarBox.quiet(c)) return;
        say(c, text);
    }

    /** Spoken by the background voice itself (the home screen's own fallback; anything when there is no home screen). */
    static void sayDirect(Context c, String text) { sayDirect(c, text, false); }

    /**
     * Said at once in the phone's own voice (no waiting for the natural voice's internet): the car sleep alert's questions
     * and alarms, where a late word is no use.
     */
    static void sayPhone(Context c, String text) { sayDirect(c, text, true); }

    /** (A queued text that must go by the phone's voice starts with this.) */
    private static final char PHONE = '\u0001';

    private static void sayDirect(Context c, String text, boolean phone) {
        if (text == null || text.trim().isEmpty()) return;
        if (RecorderService.recording && !phone) return; // (the car's question / alarm words: always)
        Context app = c.getApplicationContext();
        String words = Spoken.say(text); // numbers as Telugu words
        final String said = words.length() > 3900 ? words.substring(0, 3900) : words; // the voices' limit (numbers as words are longer)
        MicQuiet.speaking(); // a sound muted for the mic's beeps comes back first
        busySince = System.currentTimeMillis();
        busy = true;
        main.post(() -> {
            busySince = System.currentTimeMillis(); // (again here: a settle() just before this must not leave it "quiet")
            busy = true;
            Duck.on(app); // radio / music goes quiet while Jarvis reads, and comes back after
            if (!phone) {
                queue.add(said);
                if (!talking) next(app);
                return;
            }
            // the phone's voice goes first, at once: a natural-voice announcement still on its way is said after it
            if (talking && current != null) {
                String cur = current;
                current = null;
                natural.stop();
                talking = false;
                sounding(false);
                queue.addFirst(cur);
            }
            // after any phone-voice words already waiting at the front (they keep their order)
            ArrayList<String> heads = new ArrayList<>();
            while (!queue.isEmpty() && !queue.peekFirst().isEmpty() && queue.peekFirst().charAt(0) == PHONE) heads.add(queue.pollFirst());
            heads.add(PHONE + said);
            for (int i = heads.size() - 1; i >= 0; i--) queue.addFirst(heads.get(i));
            if (!talking) next(app);
        });
    }

    /** Speaks the next queued announcement (main thread). */
    private static void next(Context app) {
        String polled = queue.peek();
        if (polled == null) { talking = false; settle(); return; }
        final boolean phone = !polled.isEmpty() && polled.charAt(0) == PHONE;
        if (!phone && held()) { // the car's question waits for his "Jarvis": other words wait for it (nothing talks over him)
            talking = false;
            heldApp = app;
            if (googlePending <= 0) {
                busy = false;
                sounding(false);
                phoneSounding(false);
                quietSince = System.currentTimeMillis();
            }
            return;
        }
        queue.poll();
        final String text = phone ? polled.substring(1) : polled;
        appRef = app;
        if (phone) { // the queue waits until the phone's voice has said it (oneDone goes on)
            talking = true;
            phoneHold = true;
            final int token = ++holdToken;
            google(app, text);
            // (an engine that never says "done" must not hold the queue for ever)
            main.postDelayed(() -> {
                if (!phoneHold || holdToken != token) return;
                googlePending = 0; // (the lost "done" must not leave Jarvis "sounding" for ever)
                phoneSounding(false);
                release(app);
            }, 20_000 + text.length() * 150L);
            return;
        }
        Prefs p = new Prefs(app);
        String key = p.openAiKey().trim();
        CloudVoice cv = CloudVoice.of(p); // the voice he chose (Gemini / Sarvam), or OpenAI
        if (!phone && p.naturalVoice() && (cv == null ? !key.isEmpty() : cv.ready())) {
            talking = true;
            current = text;
            natural.model = p.ttsModel();
            natural.cloud = cv;
            natural.speak(key, p.naturalVoiceName(), text, new NaturalVoice.Callback() {
                @Override public void onStart() { sounding(true); }
                @Override public void onDone() { current = null; sounding(false); next(app); }
                @Override public void onError(String message) {
                    current = null;
                    sounding(false);
                    google(app, text);
                    next(app);
                }
            });
        } else {
            google(app, text); // the phone's engine queues by itself (QUEUE_ADD)
            next(app);
        }
    }

    static void stop() {
        main.post(() -> {
            queue.clear();
            talking = false;
            phoneHold = false;
            phoneSounding(false);
            googlePending = 0;
            natural.stop();
            current = null;
            if (tts != null) tts.stop();
            Duck.off();
            busy = false;
            sounding(false);
            quietSince = System.currentTimeMillis();
        });
    }

    /** Everything said: the music comes back up. */
    private static void settle() {
        if (!talking && queue.isEmpty() && googlePending <= 0) {
            googlePending = 0;
            Duck.off();
            busy = false;
            sounding(false);
            phoneSounding(false);
            quietSince = System.currentTimeMillis();
        }
    }

    private static void google(Context app, String text) {
        googlePending++;
        if (tts == null) {
            waiting.add(text);
            tts = new TextToSpeech(app, status -> main.post(() -> {
                ready = status == TextToSpeech.SUCCESS && tts != null;
                if (ready) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { main.post(() -> phoneSounding(true)); }
                        @Override public void onDone(String id) { main.post(Announcer::oneDone); }
                        @Override public void onError(String id) { main.post(Announcer::oneDone); }
                        @Override public void onStop(String id, boolean interrupted) { main.post(Announcer::oneDone); }
                    });
                    tts.setLanguage(Locale.forLanguageTag("te-IN"));
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    for (String w : waiting) tts.speak(w, TextToSpeech.QUEUE_ADD, null, "a" + (n++));
                } else {
                    // Drop the broken engine so the next announcement tries again.
                    TextToSpeech dead = tts;
                    tts = null;
                    if (dead != null) try { dead.shutdown(); } catch (Exception ignored) {}
                    googlePending -= waiting.size(); // these will never be spoken
                    if (phoneHold) { waiting.clear(); release(app); return; }
                    settle();
                }
                waiting.clear();
            }));
            return;
        }
        if (!ready) { waiting.add(text); return; }
        try { tts.setLanguage(Lang.of(text)); } catch (Exception ignored) {}
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, "a" + (n++)) != TextToSpeech.SUCCESS) oneDone();
    }

    private static void oneDone() {
        googlePending--;
        if (googlePending <= 0) phoneSounding(false);
        if (phoneHold && googlePending <= 0 && appRef != null) { release(appRef); return; } // said: the queue goes on
        settle();
    }

    /** The phone-voice word is over (said, failed, or never reported): the queue goes on (main thread). */
    private static void release(Context app) {
        phoneHold = false;
        talking = false;
        if (googlePending < 0) googlePending = 0;
        phoneSounding(googlePending > 0);
        next(app);
    }
}
