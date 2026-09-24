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

    boolean wakeWord() {
        if (!sp.getBoolean("wake_v3", false)) {
            // Older versions switched the wake word off for good with the notification's "ఆపు";
            // now that is only a pause, so switch it back on once for anyone who has set it up before.
            SharedPreferences.Editor e = sp.edit().putBoolean("wake_v3", true);
            if (sp.contains("wake_threshold")) e.putBoolean("wake", true);
            e.apply();
            if (sp.contains("wake_threshold")) return true;
        }
        return sp.getBoolean("wake", false);
    }
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
    /**
     * When the wake word may use the mic: "always" (default, so "Jarvis" also wakes a dark screen),
     * "screen_on" or "charging". Older versions defaulted to "screen_on"; move them over once.
     */
    String wakeWhen() {
        if (!sp.getBoolean("wake_when_v2", false)) {
            sp.edit().putString("wake_when", "always").putBoolean("wake_when_v2", true).apply();
        }
        return sp.getString("wake_when", "always");
    }
    /** Start listening as soon as Jarvis is opened (e.g. "Hey Google, open Jarvis"). */
    boolean listenOnOpen() { return sp.getBoolean("listen_on_open", true); }
    /** "Jarvis" opens a small Google-style panel over the current app instead of the full screen. */
    boolean compactPanel() { return sp.getBoolean("compact_panel", true); }

    // ---- incoming calls
    boolean announceCalls() { return sp.getBoolean("announce_calls", true); }
    /** After saying who is calling, listen for "ఎత్తు" / "కట్" and answer or decline. */
    boolean callByVoice() { return sp.getBoolean("call_voice", true); }
    /** Read new WhatsApp / SMS / Telegram messages aloud and offer to reply. */
    boolean readMessages() { return sp.getBoolean("read_messages", true); }
    /** Warn by voice when the battery gets low. */
    boolean batteryWarn() { return sp.getBoolean("battery_warn", true); }
    /** Driving: everything by voice, messages always read out. */
    boolean driving() { return sp.getBoolean("driving", false); }
    /** Night: quiet, nothing is read out until "good morning". */
    boolean night() { return sp.getBoolean("night", false); }
    void set(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    // ---- daily morning briefing
    boolean briefingOn() { return sp.getBoolean("briefing", false); }
    int briefingHour() { return sp.getInt("briefing_hour", 7); }
    int briefingMinute() { return sp.getInt("briefing_minute", 0); }
    boolean briefingSpeak() { return sp.getBoolean("briefing_speak", true); }
    /** The wake word is on and not paused from the notification's "ఆపు" button. */
    boolean wakeReady() { return wakeWord() && !wakePaused(); }
    /** "ఆపు" in the notification pauses the mic only until Jarvis is opened again. */
    boolean wakePaused() { return sp.getBoolean("wake_paused", false); }
    void setWakePaused(boolean paused) { sp.edit().putBoolean("wake_paused", paused).apply(); }
}
