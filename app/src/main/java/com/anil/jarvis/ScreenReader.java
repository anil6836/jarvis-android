package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a page from his screen aloud (a web page in Chrome or any browser, an article, messages) with the phone's own
 * voice: free and offline, each part in its own language (English parts in English, Telugu in Telugu). Pause, go on
 * and stop from the floating Jarvis button or by voice; it pauses by itself while Jarvis talks or a call comes and
 * goes on after. Separate from the book reader, so his book's bookmark stays where it was.
 */
final class ScreenReader {
    interface Listener { void onReaderState(); }

    private static ScreenReader one;

    static synchronized ScreenReader get(Context c) {
        if (one == null) one = new ScreenReader(c.getApplicationContext());
        return one;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private List<String> parts = new ArrayList<>();
    private int at;
    private volatile boolean active, paused, focusPaused;
    private String title = "";
    private int utt;
    private AudioFocusRequest focusReq;
    Listener listener;

    private ScreenReader(Context app) { this.app = app; }

    /** Jarvis is about to listen: the page waits (the mic must not hear it); he can say "కొనసాగించు" or tap ▶. */
    static void pauseIfReading(Context c) {
        ScreenReader r = one;
        if (r != null && r.active && !r.paused) r.pause();
    }

    /** Lines first (a web page's lines may have no full stops; each keeps its own language), long ones by sentences. */
    private static List<String> parts(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.length() <= 300) out.add(l); else out.addAll(ReaderService.split(l));
        }
        return out;
    }

    boolean active() { return active; }
    boolean paused() { return paused; }
    String title() { return title; }

    /** Starts reading this text from the beginning (main thread or any thread). */
    void read(String name, String text) {
        main.post(() -> {
            stopNow(false);
            parts = parts(text);
            if (parts.isEmpty()) { changed(); Announcer.say(app, "చదవడానికి ఈ స్క్రీన్‌లో అక్షరాలు దొరకలేదు."); return; }
            title = name == null ? "" : name;
            at = 0;
            active = true;
            paused = false;
            focusPaused = false;
            changed();
            if (tts == null) {
                tts = new TextToSpeech(app, s -> main.post(() -> {
                    ready = s == TextToSpeech.SUCCESS;
                    if (!ready) { // a fresh engine next time
                        try { tts.shutdown(); } catch (Exception ignored) {}
                        tts = null;
                        stopNow(true);
                        Announcer.say(app, "ఈ ఫోన్ వాయిస్ ఇంజిన్ తెరవలేకపోయాను.");
                        return;
                    }
                    tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) { main.post(() -> spoken(id)); }
                        @Override public void onError(String id) { main.post(() -> spoken(id)); }
                    });
                    speakNext();
                }));
            } else if (ready) {
                speakNext();
            }
        });
    }

    void pause() { main.post(() -> { if (!active || paused) return; paused = true; utt++; try { tts.stop(); } catch (Exception ignored) {} dropFocus(); changed(); }); }

    void resume() { main.post(() -> { if (!active || !paused) return; paused = false; focusPaused = false; speakNext(); changed(); }); }

    void toggle() { if (paused) resume(); else pause(); }

    void stop() { main.post(() -> stopNow(true)); }

    private void stopNow(boolean tell) {
        boolean was = active;
        active = false;
        paused = false;
        utt++;
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        dropFocus();
        if (was && tell) changed();
    }

    private void spoken(String id) {
        if (!active || paused || !("s" + utt).equals(id)) return; // an old part, or stopped / paused
        at++;
        speakNext();
    }

    private void speakNext() {
        if (!active || paused || focusPaused || !ready) return;
        if (at >= parts.size()) { stopNow(true); return; }
        if (!takeFocus()) { stopNow(true); return; } // a call, or the system said no: not over it
        String p = parts.get(at);
        try { tts.setLanguage(Lang.of(p)); } catch (Exception ignored) {}
        tts.setSpeechRate(new Prefs(app).speechRate());
        String said = Spoken.say(p);
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (said.length() > max) said = said.substring(0, max);
        utt++;
        if (tts.speak(said, TextToSpeech.QUEUE_FLUSH, null, "s" + utt) != TextToSpeech.SUCCESS) stopNow(true);
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.onReaderState();
    }

    // ---- quiet while Jarvis talks or a call comes; on again after
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stopNow(true); // music / a video started: stop
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (active && !paused) { focusPaused = true; utt++; try { tts.stop(); } catch (Exception ignored) {} }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (active && !paused && focusPaused) { focusPaused = false; main.postDelayed(this::speakNext, 500); }
        }
    };

    private boolean takeFocus() {
        if (focusReq != null) return true;
        try {
            AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setWillPauseWhenDucked(true)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focus, main).build();
            if (app.getSystemService(AudioManager.class).requestAudioFocus(r) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
            focusReq = r;
        } catch (Exception ignored) {}
        return true;
    }

    private void dropFocus() {
        try { if (focusReq != null) app.getSystemService(AudioManager.class).abandonAudioFocusRequest(focusReq); } catch (Exception ignored) {}
        focusReq = null;
    }
}
