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

    /** Follows the reading on the screen (main thread): highlight, scroll, and more text when it runs out. */
    interface Follow {
        /** A part starts: its line (in the text) and where it is in the text. */
        void onPart(int line, int start, int end);
        /** The word being said now (where in the text). */
        default void onWord(int start, int end) {}
        /** Paused or finished: take the highlight away. */
        void onQuiet();
        /** The text ran out: more lines to read (a worker thread), or null at the end. Its lines carry on the line numbers. */
        default String more() { return null; }
    }

    private static final class Part {
        final String text;
        final int start, line;
        Part(String text, int start, int line) { this.text = text; this.start = start; this.line = line; }
    }

    private static ScreenReader one;

    static synchronized ScreenReader get(Context c) {
        if (one == null) one = new ScreenReader(c.getApplicationContext());
        return one;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private List<Part> parts = new ArrayList<>();
    private int at, lines, mores;
    private Follow follow;
    private boolean fetching;
    private Spoken.Out said;     // the part being spoken, as said (numbers as words), to map words back
    private volatile boolean active, paused, focusPaused;
    private String title = "";
    private int utt;
    /** A page read from the floating button: where it stopped is kept (ReadPlaces) to go on from there next time. */
    private String rememberPkg, firstText;
    private boolean endedNaturally;
    /** The sleep timer: reading stops at this time (0 = off). */
    private long sleepAt;
    private final Runnable sleepStop = () -> { sleepAt = 0; stopNow(true); };
    private AudioFocusRequest focusReq;
    Listener listener;

    private ScreenReader(Context app) { this.app = app; }

    /** Jarvis is about to listen: the page waits (the mic must not hear it); he can say "కొనసాగించు" or tap ▶. */
    static void pauseIfReading(Context c) {
        ScreenReader r = one;
        if (r != null && r.active && !r.paused) r.pause();
    }

    /**
     * Lines first (a web page's lines may have no full stops; each keeps its own language), long ones by sentences;
     * each part knows its line and where it starts in the text (lineBase: the first line's number).
     */
    private static List<Part> parts(String text, int lineBase) {
        List<Part> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\n", -1);
        int offset = 0;
        for (int li = 0; li < lines.length; li++) {
            String raw = lines[li], l = raw.trim();
            if (!l.isEmpty()) {
                int ls = offset + raw.indexOf(l);
                if (l.length() <= 300) out.add(new Part(l, ls, lineBase + li));
                else {
                    int pos = ls;
                    for (String sp : ReaderService.split(l)) {
                        int at = text.indexOf(sp.substring(0, Math.min(40, sp.length())), pos);
                        if (at < 0) at = pos;
                        out.add(new Part(sp, at, lineBase + li));
                        pos = at + Math.max(1, sp.length() - 5); // the next sentence is after this one (repeated words must not pull it back)
                    }
                }
            }
            offset += raw.length() + 1;
        }
        return out;
    }

    boolean active() { return active; }
    boolean paused() { return paused; }
    String title() { return title; }

    /** Starts reading this text from the beginning (main thread or any thread). */
    void read(String name, String text) { read(name, text, null); }

    /** ... with something following it on the screen (highlight and scroll). */
    void read(String name, String text, Follow f) { readAt(name, text, f, 0, null, null); }

    /**
     * ... from the part of line fromLine (a paragraph he tapped), or from the part that starts with fromSnippet (where he
     * stopped last time); rememberPkg: keep where it stops (a page from the floating button), else null.
     */
    void readAt(String name, String text, Follow f, int fromLine, String fromSnippet, String rememberPkg) {
        main.post(() -> {
            stopNow(false);
            follow = f;
            mores = 0;
            fetching = false;
            parts = parts(text, 0);
            lines = text == null ? 0 : text.split("\n", -1).length;
            if (parts.isEmpty()) { changed(); Announcer.say(app, "చదవడానికి ఈ స్క్రీన్‌లో అక్షరాలు దొరకలేదు."); return; }
            title = name == null ? "" : name;
            this.rememberPkg = rememberPkg;
            keptParts.clear();
            firstText = text;
            endedNaturally = false;
            at = 0;
            if (fromSnippet != null) {
                for (int i = 0; i < parts.size(); i++) if (ReadPlaces.snippet(parts.get(i).text).equals(fromSnippet)) { at = i; break; }
            } else if (fromLine > 0) {
                for (int i = 0; i < parts.size(); i++) if (parts.get(i).line >= fromLine) { at = i; break; }
            }
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
                        @Override public void onRangeStart(String id, int start, int end, int frame) {
                            main.post(() -> word(id, start, end));
                        }
                    });
                    speakNext();
                }));
            } else if (ready) {
                speakNext();
            }
        });
    }

    void pause() { main.post(() -> { if (!active || paused) return; paused = true; utt++; try { tts.stop(); } catch (Exception ignored) {} dropFocus(); quiet(); keepPlace(); changed(); }); }

    /** Where a page read from the floating button stopped (or paused), for next time. */
    private void keepPlace() {
        if (rememberPkg == null || at >= parts.size()) return;
        try {
            keptParts.add(parts.get(at).text);
            ReadPlaces.save(app, rememberPkg, title, parts.get(at).text);
        } catch (Exception ignored) {}
    }

    /** The places kept during this reading (forgotten when it is read to the end). */
    private final java.util.List<String> keptParts = new java.util.ArrayList<>();

    /** The sleep timer: stop reading in this many minutes (0 = off). */
    void sleepIn(int minutes) {
        main.post(() -> {
            main.removeCallbacks(sleepStop);
            sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
            if (minutes > 0) main.postDelayed(sleepStop, minutes * 60_000L);
            changed();
        });
    }

    /** Minutes left on the sleep timer (0 = off). */
    int sleepLeft() { return sleepAt <= 0 ? 0 : (int) Math.max(1, Math.round((sleepAt - System.currentTimeMillis()) / 60_000.0)); }

    void resume() { main.post(() -> { if (!active || !paused) return; paused = false; focusPaused = false; speakNext(); changed(); }); }

    void toggle() { if (paused) resume(); else pause(); }

    private float speed = 1f;

    float speed() { return speed; }

    /** Faster (+) or slower (-): the paragraph being read starts again at the new speed. */
    void faster(boolean up) {
        main.post(() -> {
            speed = Math.max(0.6f, Math.min(2.2f, speed + (up ? 0.2f : -0.2f)));
            restartPart();
            changed();
        });
    }

    /** A paragraph forward (n > 0) or back (n < 0). */
    void skip(int n) {
        main.post(() -> {
            if (!active) return;
            at = Math.max(0, Math.min(parts.size(), at + n)); // past the last paragraph: the page's next part, or done
            restartPart();
        });
    }

    private void restartPart() {
        if (!active || paused) return;
        utt++;
        try { tts.stop(); } catch (Exception ignored) {}
        speakNext();
    }

    void stop() { main.post(() -> stopNow(true)); }

    private void stopNow(boolean tell) {
        boolean was = active;
        if (was && rememberPkg != null) {
            if (endedNaturally) { // read to the end: no place to go back to (also of the parts that came by scrolling)
                try { ReadPlaces.forget(app, rememberPkg, firstText); ReadPlaces.forgetAll(app, rememberPkg, keptParts); } catch (Exception ignored) {}
            }
            else keepPlace();
        }
        main.removeCallbacks(sleepStop); // the sleep timer is for this reading
        sleepAt = 0;
        rememberPkg = null;
        active = false;
        paused = false;
        utt++;
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        dropFocus();
        quiet();
        if (was && tell) changed();
    }

    private void quiet() {
        Follow f = follow;
        if (f != null) try { f.onQuiet(); } catch (Exception ignored) {}
    }

    private void word(String id, int start, int end) {
        Follow f = follow;
        Spoken.Out o = said;
        if (f == null || o == null || !active || paused || !("s" + utt).equals(id) || at >= parts.size()) return;
        try {
            int[] r = o.range(start, end);
            Part p = parts.get(at);
            f.onWord(p.start + Math.max(0, r[0]), p.start + Math.min(p.text.length(), r[1]));
        } catch (Exception ignored) {}
    }

    private void spoken(String id) {
        if (!active || paused || !("s" + utt).equals(id)) return; // an old part, or stopped / paused
        at++;
        speakNext();
    }

    private void speakNext() {
        if (!active || paused || focusPaused || !ready) return;
        if (at >= parts.size()) {
            final Follow f = follow;
            if (f != null && !fetching && mores < 40) { // the page goes on below: scroll and read on
                fetching = true;
                mores++;
                final int myUtt = utt;
                new Thread(() -> {
                    String more = null;
                    try { more = f.more(); } catch (Exception ignored) {}
                    final String m = more;
                    main.post(() -> {
                        fetching = false;
                        if (!active || follow != f || utt != myUtt) return;
                        if (m == null || m.trim().isEmpty()) { endedNaturally = true; stopNow(true); return; }
                        parts.addAll(parts(m, lines));
                        lines += m.split("\n", -1).length;
                        speakNext();
                    });
                }, "jarvis-read-more").start();
                return;
            }
            if (!fetching) { endedNaturally = true; stopNow(true); }
            return;
        }
        if (!takeFocus()) { stopNow(true); return; } // a call, or the system said no: not over it
        Part part = parts.get(at);
        String p = part.text;
        try { tts.setLanguage(Lang.of(p)); } catch (Exception ignored) {}
        tts.setSpeechRate(new Prefs(app).speechRate() * speed);
        Spoken.Out o = Spoken.of(p);
        String words = o.text;
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (words.length() > max) words = words.substring(0, max);
        said = o;
        utt++;
        Follow f = follow;
        if (f != null) try { f.onPart(part.line, part.start, part.start + p.length()); } catch (Exception ignored) {}
        MicQuiet.speaking(); // a sound muted for the mic's beeps comes back first
        if (tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "s" + utt) != TextToSpeech.SUCCESS) stopNow(true);
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
