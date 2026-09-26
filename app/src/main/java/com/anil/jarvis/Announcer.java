package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

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

    private Announcer() {}

    /** Speak text with the voice chosen in settings. Safe to call from any thread. */
    static void say(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        Context app = c.getApplicationContext();
        main.post(() -> {
            queue.add(text);
            if (!talking) next(app);
        });
    }

    /** Speaks the next queued announcement (main thread). */
    private static void next(Context app) {
        String text = queue.poll();
        if (text == null) { talking = false; return; }
        Prefs p = new Prefs(app);
        String key = p.openAiKey().trim();
        if (p.naturalVoice() && !key.isEmpty()) {
            talking = true;
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
            natural.stop();
            if (tts != null) tts.stop();
        });
    }

    private static void google(Context app, String text) {
        if (tts == null) {
            waiting.add(text);
            tts = new TextToSpeech(app, status -> main.post(() -> {
                ready = status == TextToSpeech.SUCCESS && tts != null;
                if (ready) {
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
                }
                waiting.clear();
            }));
            return;
        }
        if (!ready) { waiting.add(text); return; }
        try { tts.setLanguage(Lang.of(text)); } catch (Exception ignored) {}
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "a" + (n++));
    }
}
