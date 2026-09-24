package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the user sets on the settings screen. Keys stay on the phone only. */
final class Prefs {
    static final String OPENAI = "openai";
    static final String ANTHROPIC = "anthropic";
    static final String DEFAULT_OPENAI_MODEL = "gpt-6-luna";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001";

    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    String name() { return sp.getString("name", "Anil"); }
    String provider() { return sp.getString("provider", OPENAI); }
    boolean isOpenAi() { return OPENAI.equals(provider()); }

    String apiKey() { return sp.getString(isOpenAi() ? "openai_key" : "anthropic_key", "").trim(); }
    String openAiKey() { return sp.getString("openai_key", ""); }
    String anthropicKey() { return sp.getString("anthropic_key", ""); }

    String model() {
        String m = isOpenAi()
                ? sp.getString("openai_model", DEFAULT_OPENAI_MODEL)
                : sp.getString("anthropic_model", DEFAULT_ANTHROPIC_MODEL);
        m = m == null ? "" : m.trim();
        if (m.isEmpty()) m = isOpenAi() ? DEFAULT_OPENAI_MODEL : DEFAULT_ANTHROPIC_MODEL;
        return m;
    }
    String openAiModel() { return sp.getString("openai_model", DEFAULT_OPENAI_MODEL); }
    String anthropicModel() { return sp.getString("anthropic_model", DEFAULT_ANTHROPIC_MODEL); }

    boolean wakeWord() { return sp.getBoolean("wake", false); }
    /** Score needed to wake (lower = wakes more easily). */
    float wakeThreshold() { return sp.getFloat("wake_threshold", 0.5f); }
    boolean voiceReplies() { return sp.getBoolean("voice", true); }
    boolean followUp() { return sp.getBoolean("follow_up", true); }
    boolean webSearch() { return sp.getBoolean("web_search", true); }
    float speechRate() { return sp.getFloat("rate", 1.0f); }
    String listenLang() { return sp.getString("lang", "te-IN"); }

    boolean hasBrain() { return !apiKey().isEmpty(); }

    // ---- natural voice (OpenAI text-to-speech)
    boolean naturalVoice() { return sp.getBoolean("natural_voice", true); }
    String naturalVoiceName() { return sp.getString("natural_voice_name", "cedar"); }

    // ---- live (real-time) conversation
    static final String DEFAULT_REALTIME_MODEL = "gpt-realtime-2.1-mini";
    boolean liveMode() { return sp.getBoolean("live", false); }
    String realtimeModel() {
        String m = sp.getString("realtime_model", DEFAULT_REALTIME_MODEL);
        return m == null || m.trim().isEmpty() ? DEFAULT_REALTIME_MODEL : m.trim();
    }
    /** Let Anil interrupt Jarvis mid-sentence. Turn off if Jarvis keeps interrupting itself. */
    boolean bargeIn() { return sp.getBoolean("barge_in", true); }
    boolean liveReady() { return liveMode() && !openAiKey().trim().isEmpty(); }

    // ---- wake words: "Jarvis" is the main one, "Hey Jarvis" the second
    boolean jarvisWord() { return sp.getBoolean("wake_jarvis", true); }
    /** When the wake word may use the mic: "screen_on" (default), "charging" or "always". */
    String wakeWhen() { return sp.getString("wake_when", "screen_on"); }
    /** Start listening as soon as Jarvis is opened (e.g. "Hey Google, open Jarvis"). */
    boolean listenOnOpen() { return sp.getBoolean("listen_on_open", true); }

    // ---- incoming calls
    boolean announceCalls() { return sp.getBoolean("announce_calls", true); }

    // ---- daily morning briefing
    boolean briefingOn() { return sp.getBoolean("briefing", false); }
    int briefingHour() { return sp.getInt("briefing_hour", 7); }
    int briefingMinute() { return sp.getInt("briefing_minute", 0); }
    boolean briefingSpeak() { return sp.getBoolean("briefing_speak", true); }
    boolean wakeReady() { return wakeWord(); }
}
