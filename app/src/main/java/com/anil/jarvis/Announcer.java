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

    /** Speak text with the voice chosen in settings. Safe to call from any thread. */
    static void say(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (RecorderService.recording) return; // a sermon / meeting is being recorded: silence (notifications still come)
        Context app = c.getApplicationContext();
        String words = Spoken.say(text); // numbers as Telugu words
        final String said = words.length() > 3900 ? words.substring(0, 3900) : words; // the voices' limit (numbers as words are longer)
        MicQuiet.speaking(); // a sound muted for the mic's beeps comes back first
        busySince = System.currentTimeMillis();
        busy = true;
        main.post(() -> {
            queue.add(said);
            Duck.on(app); // radio / music goes quiet while Jarvis reads, and comes back after
            if (!talking) next(app);
        });
    }

    /** Speaks the next queued announcement (main thread). */
    private static void next(Context app) {
        String text = queue.poll();
        if (text == null) { talking = false; settle(); return; }
        Prefs p = new Prefs(app);
        String key = p.openAiKey().trim();
        if (p.naturalVoice() && !key.isEmpty()) {
            talking = true;
            natural.model = p.ttsModel();
            natural.speak(key, p.naturalVoiceName(), text, new NaturalVoice.Callback() {
                @Override public void onStart() {}
                @Override public void onDone() { next(app); }
                @Override public void onError(String message) {
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
            googlePending = 0;
            natural.stop();
            if (tts != null) tts.stop();
            Duck.off();
            busy = false;
            quietSince = System.currentTimeMillis();
        });
    }

    /** Everything said: the music comes back up. */
    private static void settle() {
        if (!talking && queue.isEmpty() && googlePending <= 0) {
            googlePending = 0;
            Duck.off();
            busy = false;
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
                        @Override public void onStart(String id) {}
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
        settle();
    }
}
