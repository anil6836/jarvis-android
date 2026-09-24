package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

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

    private Announcer() {}

    /** Speak text with the voice chosen in settings. Safe to call from any thread. */
    static void say(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        Context app = c.getApplicationContext();
        main.post(() -> {
            Prefs p = new Prefs(app);
            String key = p.openAiKey().trim();
            if (p.naturalVoice() && !key.isEmpty()) {
                natural.speak(key, p.naturalVoiceName(), text, new NaturalVoice.Callback() {
                    @Override public void onStart() {}
                    @Override public void onDone() {}
                    @Override public void onError(String message) { google(app, text); }
                });
            } else {
                google(app, text);
            }
        });
    }

    static void stop() {
        main.post(() -> {
            natural.stop();
            if (tts != null) tts.stop();
        });
    }

    private static void google(Context app, String text) {
        if (tts == null) {
            waiting.add(text);
            tts = new TextToSpeech(app, status -> main.post(() -> {
                ready = status == TextToSpeech.SUCCESS;
                if (ready) {
                    tts.setLanguage(Locale.forLanguageTag("te-IN"));
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    for (String w : waiting) tts.speak(w, TextToSpeech.QUEUE_ADD, null, "a" + (n++));
                }
                waiting.clear();
            }));
            return;
        }
        if (!ready) { waiting.add(text); return; }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "a" + (n++));
    }
}
