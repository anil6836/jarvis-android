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

    /** The live talk he chose in Settings (Gemini Live or OpenAI's). brain: for what Live hands to the usual way (Gemini). */
    static LiveTalk create(Context c, Prefs prefs, Tools tools, Brain brain, LiveSession.Listener l) {
        return prefs.liveGemini() ? new GeminiLive(c, prefs, tools, brain, l) : new LiveSession(c, prefs, tools, l);
    }

    /** Which Live, for the screens ("Gemini Live" / "OpenAI Live"). */
    static String label(Prefs prefs) { return prefs.liveGemini() ? "Gemini Live" : "OpenAI Live"; }
}
