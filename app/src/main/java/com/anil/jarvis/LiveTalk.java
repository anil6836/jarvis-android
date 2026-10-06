package com.anil.jarvis;

import android.content.Context;

/** A live voice talk, whoever runs it: OpenAI Realtime (LiveSession) or Gemini Live (GeminiLive). The Live screen and panel use only this. */
interface LiveTalk {
    void start(String instructions);
    /** Ends it. Safe to call more than once, from any thread. */
    void stop(String reason);
    boolean isOpen();
    void setMuted(boolean muted);
    boolean isMuted();
    /** A typed message (a protocol button) into the talk. */
    void sendText(String text);
    /** The language to interpret next, when this talk ended to hand over to the live interpreter. */
    String interpreterLang();

    /** He tapped Jarvis while it talks: it stops this answer and listens. */
    default void interrupt() {}

    /** Which volume the volume keys should change during this talk (Jarvis's voice). */
    default int volumeStream() { return android.media.AudioManager.STREAM_MUSIC; }

    /**
     * The volume the screen must change by hand when a volume key is pressed during this talk (the phone would change
     * the call volume instead), or -1 when the phone's own handling is right.
     */
    default int keysStream() { return -1; }

    /**
     * A volume key on a screen with a live talk: steered to Jarvis's voice when the talk asks for it (with the phone's
     * volume panel shown). True when the key was handled here.
     */
    static boolean volumeKey(Context c, LiveTalk live, android.view.KeyEvent e) {
        if (live == null) return false;
        int code = e.getKeyCode();
        if (code != android.view.KeyEvent.KEYCODE_VOLUME_UP && code != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        int stream = live.keysStream();
        if (stream < 0) return false;
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true; // (the key's release: already handled)
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        if (am == null) return false;
        int dir = code == android.view.KeyEvent.KEYCODE_VOLUME_UP ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
        try {
            am.adjustStreamVolume(stream, dir, android.media.AudioManager.FLAG_SHOW_UI);
        } catch (Exception ex) { // (the phone refused that volume: media then, as Jarvis's voice follows media on such phones)
            try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI); } catch (Exception ignored) {}
        }
        return true;
    }

    /** The live talk he chose in Settings (Gemini Live or OpenAI's). brain: for what Live hands to the usual way (Gemini). */
    static LiveTalk create(Context c, Prefs prefs, Tools tools, Brain brain, LiveSession.Listener l) {
        return prefs.liveGemini() ? new GeminiLive(c, prefs, tools, brain, l) : new LiveSession(c, prefs, tools, l);
    }

    /** Which Live, for the screens ("Gemini Live" / "OpenAI Live"). */
    static String label(Prefs prefs) { return prefs.liveGemini() ? "Gemini Live" : "OpenAI Live"; }
}
