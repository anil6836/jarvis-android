package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the user sets on the settings screen. Keys stay on the phone only. */
final class Prefs {
    static final String OPENAI = "openai";
    static final String ANTHROPIC = "anthropic";
    static final String GEMINI = "gemini";
    static final String DEFAULT_OPENAI_MODEL = "gpt-6-luna";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001";

    final SharedPreferences sp;
    /** The app (for parts that read the phone's state, like the situation snapshot). */
    final Context app;

    Prefs(Context c) {
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Usage.init(c); // the API cost meter needs somewhere to keep its totals
    }

    /** Set only on the "second AI" prefs (cross-check): the provider and model he chose for checking. */
    private String overProvider, overModel;

    /** The second AI he chose for cross-checking answers, or null when none is chosen. */
    static Prefs checker(Context c) {
        Prefs p = new Prefs(c);
        String pr = p.sp.getString("check_provider", "").trim(), m = p.sp.getString("check_model", "").trim();
        if (pr.isEmpty() || m.isEmpty()) return null;
        p.overProvider = pr;
        p.overModel = m;
        return p;
    }

    String name() { return sp.getString("name", "Anil"); }
    String provider() { return overProvider != null ? overProvider : sp.getString("provider", OPENAI); }
    boolean isOpenAi() { return OPENAI.equals(provider()); }
    boolean isGemini() { return GEMINI.equals(provider()); }

    String apiKey() { return sp.getString(isGemini() ? "gemini_key" : isOpenAi() ? "openai_key" : "anthropic_key", "").trim(); }
    String geminiKey() { return sp.getString("gemini_key", ""); }
    /** The Gemini model he chose in Settings (empty = not chosen yet: Jarvis asks him to choose, never picks one itself). */
    String geminiModel() { return sp.getString("gemini_model", ""); }
    String openAiKey() { return sp.getString("openai_key", ""); }
    String anthropicKey() { return sp.getString("anthropic_key", ""); }

    String model() {
        if (overModel != null) return overModel;
        if (isGemini()) {
            return geminiModel().trim(); // may be empty: the brain then asks him to choose one
        }
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
    /** Speak with feelings: laugh, happy, sad, excited... (on by default). */
    boolean emotions() { return sp.getBoolean("emotions", true); }
    String naturalVoiceName() { return sp.getString("natural_voice_name", "cedar"); }
    /** The OpenAI model that speaks in the natural voice (he can change it in Settings). */
    String ttsModel() { String m = sp.getString("tts_model", "").trim(); return m.isEmpty() ? NaturalVoice.DEFAULT_MODEL : m; }

    // ---- live (real-time) conversation
    static final String DEFAULT_REALTIME_MODEL = "gpt-realtime-2.1-mini";
    boolean liveMode() { return sp.getBoolean("live", false); }
    String realtimeModel() {
        String m = sp.getString("realtime_model", DEFAULT_REALTIME_MODEL);
        return m == null || m.trim().isEmpty() ? DEFAULT_REALTIME_MODEL : m.trim();
    }
    /** Let Anil interrupt Jarvis mid-sentence. Turn off if Jarvis keeps interrupting itself. */
    boolean bargeIn() { return sp.getBoolean("barge_in", true); }
    /**
     * Gemini Live on the phone's speaker, talking over Jarvis: "word" = it stops when he says "Jarvis" or "stop"
     * (heard on the phone, so Jarvis's own loud voice can't do it); "voice" = any loud talk stops it; "off" = it finishes.
     */
    String liveBarge() {
        String m = sp.getString("live_barge", "word");
        return "voice".equals(m) || "off".equals(m) ? m : "word";
    }
    int bargeSens() { return sp.getInt("barge_sens", 2); }
    /** Talk-over: Jarvis's voice through the phone-call path (strongest echo cancelling, but sounds like a call). */
    boolean bargeCallVoice() { return sp.getBoolean("barge_call_voice", false); }
    /** Gemini Live on the phone's speaker: Jarvis takes its own voice out of the mic (so he can talk over it, as in the Gemini app). */
    boolean liveAec() { return sp.getBoolean("live_aec", true); }
    /** Gemini Live: the last 30 s of the mic, Jarvis's voice and the cleaned mic are saved after each talk (for an echo check). */
    boolean echoRecord() { return sp.getBoolean("echo_record", false); }
    /** Whose live talk: "openai" (OpenAI Realtime) or "gemini" (Gemini Live). */
    String liveProvider() { return GEMINI.equals(sp.getString("live_provider", OPENAI)) ? GEMINI : OPENAI; }
    boolean liveGemini() { return GEMINI.equals(liveProvider()); }
    static final String DEFAULT_GEMINI_LIVE_MODEL = "gemini-3.8-live";
    /** The Gemini Live model he set (Gemini's own name for it). */
    String geminiLiveModel() {
        String m = sp.getString("gemini_live_model", "").trim();
        return m.isEmpty() ? DEFAULT_GEMINI_LIVE_MODEL : m;
    }
    /** Gemini Live's voice (one of Google's voice names). */
    String geminiLiveVoice() {
        String v = GeminiLiveProto.voiceName(sp.getString("gemini_live_voice", "")); // (any spelling; a name Gemini doesn't have: Charon)
        return v == null ? "Charon" : v;
    }
    /** The key the chosen live talk needs is in Settings. */
    boolean liveKeyReady() { return !(liveGemini() ? geminiKey() : openAiKey()).trim().isEmpty(); }
    boolean liveReady() { return liveMode() && liveKeyReady(); }
    /** Live: wait until he has finished his thought (not just a short pause) before answering, like ChatGPT's voice mode. */
    boolean livePatient() { return sp.getBoolean("live_patient", true); }
    /**
     * Gemini Live: who thinks. True (the default): Jarvis's brain (his chosen model, Settings → Jarvis మెదడు) thinks
     * every answer and Live only hears and speaks; false: Live thinks itself (faster, less deep).
     */
    boolean liveBrainThinks() { return !"live".equals(sp.getString("live_think", "brain")); }
    /**
     * No internet: Jarvis listens with the phone's own offline voice typing (whatever way of hearing is chosen) and does
     * the everyday tasks on the phone; back to the usual way when the internet is back. On by default.
     */
    boolean offlineAuto() { return sp.getBoolean("offline_auto", true); }
    /** Warn about scam-looking messages and new autopay mandates (checked on the phone only). */
    boolean scamGuard() { return sp.getBoolean("scam_guard", true); }

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
    /** How Jarvis hears him: "openai" / "gemini" (Jarvis's own mic, no beeps; the AI writes the words) or "google" (the phone's speech service, with its beeps). */
    String earsMode() { return sp.getString("ears_mode", "openai"); }
    /** The OpenAI speech-to-text model for "openai" (he sets it; the voice-message "మాటలు" model by default). */
    String earsModel() { String m = sp.getString("ears_model", "").trim(); return m.isEmpty() ? "gpt-4o-mini-transcribe" : m; }
    /**
     * OpenAI: his voice is sent while he is still talking (the answer comes sooner). Off by itself only if OpenAI ever
     * refuses a recording sent that way (then the whole recording is sent after he stops, as before); a new model name
     * in Settings tries it again.
     */
    boolean earsStream() { return sp.getBoolean("ears_stream", true); }
    void earsStreamOff() { sp.edit().putBoolean("ears_stream", false).apply(); }
    /** The Jarvis camera listens all the time (the phone's mic beeps each time it reopens), not only after "Jarvis" / 🎙️. */
    boolean camAlwaysListen() { return sp.getBoolean("cam_always_listen", false); }

    // ---- incoming calls
    boolean announceCalls() { return sp.getBoolean("announce_calls", true); }
    /** After saying who is calling, listen for "ఎత్తు" / "కట్" and answer or decline. */
    boolean callByVoice() { return sp.getBoolean("call_voice", true); }
    /** Read new WhatsApp / SMS / Telegram messages aloud and offer to reply. */
    boolean readMessages() { return sp.getBoolean("read_messages", true); }
    /** Group chat messages too (off: groups are chatty). */
    boolean readGroups() { return sp.getBoolean("read_groups", false); }
    /** Group chat messages: "all", "mine" (only when one of his names is in it) or "none". Before this choice existed, "read group messages" on meant all. */
    /** A message while he types (top card): "read" (told and asked like any time), "name" (who wrote only), "silent" (card only). */
    String typingMode() { return sp.getString("typing_mode", "read"); }
    String groupMode() { return sp.getString("group_mode", sp.getBoolean("read_groups", false) ? "all" : "mine"); }
    /** The names people call him by in groups (his name first; comma separated in settings). */
    String myNames() {
        String n = sp.getString("my_names", "").trim();
        if (!n.isEmpty()) return n;
        String name = name().trim();
        return name.isEmpty() || isAddress(name) ? "Anil" : name;
    }

    /** A way of addressing him rather than a name ("Sir", "సార్", "Boss" ...), chosen in Settings as what Jarvis calls him. */
    static boolean isAddress(String n) {
        String s = n == null ? "" : n.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[.!,\\s]+", "");
        return s.isEmpty() || ADDRESS.contains(s);
    }

    private static final java.util.Set<String> ADDRESS = new java.util.HashSet<>(java.util.Arrays.asList(
            "sir", "sirji", "సర్", "సార్", "సారు", "boss", "బాస్", "బాసు", "master", "మాస్టర్", "యజమాని", "anna", "అన్నా", "అన్న",
            "babu", "బాబు", "dear", "garu", "గారు", "bossgaru", "బాస్గారు", "sirgaru", "సార్గారు", "mr", "శ్రీ"));

    /**
     * His own name: the name in Settings without any "Sir" / "గారు" around it; when it is only that, the first of the
     * names people call him by.
     */
    String realName() {
        StringBuilder b = new StringBuilder();
        for (String w : name().trim().split("\\s+")) {
            if (w.isEmpty() || isAddress(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        if (b.length() > 0) return b.toString();
        String first = myNames().split("[,،]")[0].trim();
        return first.isEmpty() || isAddress(first) ? "Anil" : first;
    }
    /** The floating button's menu with every option at once (else the 5 that fit the app, then "ఇంకా"). */
    boolean bubbleShowAll() { return sp.getBoolean("bubble_show_all", false); }
    /** Floating button options he switched off (their numbers, comma separated). */
    java.util.Set<String> bubbleOff() { return new java.util.HashSet<>(java.util.Arrays.asList(sp.getString("bubble_off", "").split(","))); }
    /** Apps where the floating button stays hidden. */
    java.util.Set<String> bubbleHiddenApps() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String x : sp.getString("bubble_hide_apps", "").split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }
    void setBubbleHiddenApps(java.util.Set<String> apps) { sp.edit().putString("bubble_hide_apps", android.text.TextUtils.join(",", apps)).apply(); }
    /** Way2News: read each news notification aloud as it comes. */
    boolean readNews() { return sp.getBoolean("read_news", true); }
    /** Places whose news he wants in Telugu (states, districts, towns), comma separated. He adds his own towns. */
    String newsPlaces() { return sp.getString("news_places", "తెలంగాణ, ఆంధ్రప్రదేశ్"); }
    void setNewsPlaces(String s) { sp.edit().putString("news_places", s == null ? "" : s.trim()).apply(); }
    /** Read the new local headlines by themselves at 8 am, 1 pm and 7 pm. */
    boolean newsAuto() { return sp.getBoolean("news_auto", true); }
    /** City for gold / silver / fuel prices. */
    String priceCity() { return sp.getString("price_city", "హైదరాబాద్"); }
    /** Prices said every morning at 10 (e.g. "బంగారం, వెండి, మిర్చి"); empty = off. */
    String dailyPrices() { return sp.getString("daily_prices", "").trim(); }
    /** Ask "ఈరోజు ఎలా గడిచింది?" at night and write the diary. */
    boolean diaryAsk() { return sp.getBoolean("diary_ask", true); }
    int diaryHour() { return Math.max(18, Math.min(23, sp.getInt("diary_hour", 22))); }
    /** Festivals and government holidays: said the evening before and on the morning. */
    boolean holidayRemind() { return sp.getBoolean("holiday_remind", true); }
    /** A message with this code (SMS / WhatsApp) makes the phone ring loud even on silent. */
    boolean findPhone() { return sp.getBoolean("find_phone", true); }
    /** Hear coughing / sneezing on the wake-word microphone and ask "<name>, ఏమైంది?". */
    boolean coughAsk() { return sp.getBoolean("cough_ask", true); }
    /** After a call of at least this long, ask "anything to remember?". */
    boolean callNote() { return sp.getBoolean("call_note", true); }
    int callNoteSeconds() { return Math.max(0, sp.getInt("call_note_sec", 60)); }
    /** After a 48-hour duty: remind to sleep, and keep quiet while he sleeps. */
    boolean restMode() { return sp.getBoolean("rest_mode", true); }
    /** Lock-screen emergency card. */
    boolean medIdOn() { return sp.getBoolean("medid_on", true); }
    String medBlood() { return sp.getString("medid_blood", "").trim(); }
    String medAllergy() { return sp.getString("medid_allergy", "").trim(); }
    String medNotes() { return sp.getString("medid_notes", "").trim(); }
    String medContact() { return sp.getString("medid_contact", "").trim(); }
    /** His bike's registration number (for the e-challan check). */
    String bikeNumber() { return sp.getString("bike_number", "").trim(); }
    /** New Telugu films and OTT releases every Friday evening. */
    boolean moviesWeekly() { return sp.getBoolean("movies_weekly", true); }
    /** A new fact and an English word every morning. */
    boolean dailyFact() { return sp.getBoolean("daily_fact", true); }
    int factHour() { return Math.max(6, Math.min(21, sp.getInt("fact_hour", 9))); }
    /** Steps a day; evening nudge when far short. 0 = no goal. */
    int stepGoal() { return Math.max(0, sp.getInt("step_goal", 6000)); }
    /** Morning stretches reminder on days off (hour, -1 = off). */
    int exerciseHour() { return sp.getInt("exercise_hour", 8); }
    /** Alarm song picked from the phone (content uri), or empty = the alarm tone. */
    String alarmSong() { return sp.getString("alarm_song", ""); }
    String alarmSongName() { return sp.getString("alarm_song_name", ""); }
    /** 9 pm: charge the bike tonight if it's low (before a duty, or under 25%). */
    boolean chargeRemind() { return sp.getBoolean("charge_remind", true); }
    /** Sunday evening: the coming week. */
    boolean weekPlan() { return sp.getBoolean("week_plan", true); }
    /** A Jarvis tip every day. */
    boolean dailyTip() { return sp.getBoolean("daily_tip", true); }
    /** Thunderstorm within the hour: warn. */
    boolean stormAlert() { return sp.getBoolean("storm_alert", true); }
    /** Monthly income and savings goal (rupees; 0 = not set). */
    int income() { return Math.max(0, sp.getInt("income", 0)); }
    int savingsGoal() { return Math.max(0, sp.getInt("savings_goal", 0)); }
    /** Habits: ask at night whether he did them (hour, -1 = off). */
    int habitHour() { return sp.getInt("habit_hour", 21); }
    /** After asking about a cough, how long before asking again (minutes; default 1 hour). */
    int coughGapMinutes() { return Math.max(10, Math.min(24 * 60, sp.getInt("cough_gap_min", 60))); }
    static String gapText(int min) {
        if (min >= 24 * 60) return "రోజుకి ఒకసారి";
        if (min % 60 == 0) return (min / 60) + (min == 60 ? " గంట" : " గంటలు");
        return min + " నిమిషాలు";
    }
    String findCode() {
        String c = sp.getString("find_code", "");
        if (c.isEmpty()) { // a different code on every phone, so strangers can't guess it
            c = "JARVIS " + (1000 + new java.security.SecureRandom().nextInt(9000));
            sp.edit().putString("find_code", c).apply();
        }
        return c;
    }
    /** Warn by voice when the battery gets low. */
    boolean batteryWarn() { return sp.getBoolean("battery_warn", true); }
    /** Driving: everything by voice, messages always read out. */
    boolean driving() { return sp.getBoolean("driving", false); }
    /** Night: quiet, nothing is read out until "good morning". */
    boolean night() { return sp.getBoolean("night", false); }
    void set(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    /** Jarvis speaks up on his own (meetings, rain, habits...). */
    boolean proactive() { return sp.getBoolean("proactive", true); }
    /** Iron Man style sound effects. */
    boolean sfx() { return sp.getBoolean("sfx", true); }
    /** The newest build he was already told about (one notification per build). */
    int notifiedBuild() { return sp.getInt("notified_build", 0); }
    void setNotifiedBuild(int b) { sp.edit().putInt("notified_build", b).apply(); }
    /** Newest Jarvis build seen on GitHub (the app shows "new version" when it is newer than this one). */
    int latestBuild() { return sp.getInt("latest_build", 0); }
    void setLatestBuild(int b) { sp.edit().putInt("latest_build", b).apply(); }
    /** The AI for code, websites and apps (e.g. gpt-6-astra, claude-opus-5-5); empty = his normal brain. */
    String codeModel() { return sp.getString("code_model", ""); }
    /** GitHub token (on the phone only): puts websites online and builds his apps. */
    String githubToken() { return sp.getString("github_token", ""); }
    String lastSite() { return sp.getString("last_site", ""); }
    void setLastSite(String s) { sp.edit().putString("last_site", s).apply(); }
    String lastAppRepo() { return sp.getString("last_app_repo", ""); }
    String lastAppName() { return sp.getString("last_app_name", ""); }
    void setLastApp(String repo, String name) { sp.edit().putString("last_app_repo", repo).putString("last_app_name", name).apply(); }
    /** When he last started an update from Settings (to reopen Jarvis after it). */
    long updateStartedAt() { return sp.getLong("update_started_at", 0); }
    void setUpdateStartedAt(long t) { sp.edit().putLong("update_started_at", t).apply(); }
    /** After "Jarvis", keep listening at least this long for Anil to start speaking. */
    /**
     * The phone's voice typing (Google): the mic is opened once per listen and never reopened (one beep in, one out; songs
     * go quieter instead of being muted). Off: it is held open for the whole listen window and reopened when the phone
     * closes it early (his old way; on some phones that means the mic going on and off).
     */
    boolean micOnce() { return sp.getBoolean("mic_once", true); }
    int listenWindowSeconds() {
        int v = sp.getInt("listen_window", 8);
        // 5 s (the old default) was too short on some phones: 8 s unless he set it himself in this version's settings
        return sp.getBoolean("listen_window_set", false) ? v : Math.max(v, 8);
    }
    /** Monthly budget in rupees (0 = none). */
    int budget() { return sp.getInt("budget", 0); }
    /** Bluetooth address of his car/bike; connecting turns on driving mode. */
    String carBluetooth() { return sp.getString("car_bt", ""); }
    /** How Jarvis talks: normal, serious, funny, english, short. */
    String mood() { return sp.getString("mood", "normal"); }
    boolean shakeWake() { return sp.getBoolean("shake_wake", true); }
    boolean faceDownSilent() { return sp.getBoolean("facedown_silent", true); }
    /** Every night around 9:30 Jarvis sums up the day and tomorrow. */
    boolean nightSummary() { return sp.getBoolean("night_summary", true); }
    /** Every Sunday evening: the week's report as a notification. */
    boolean weeklyReport() { return sp.getBoolean("weekly_report", true); }
    /** Smart home: one command per line, "name = URL" (Voice Monkey / URL Routine Trigger links). */
    String smartUrls() { return sp.getString("smart_urls", ""); }
    /** The app that controls the lights (Homemate, Zeb Home, Wipro Next...), used as a fallback. */
    String smartApp() { return sp.getString("smart_app", "Homemate"); }
    /** An Echo is near the phone: Jarvis may say "Alexa, ..." aloud. */
    boolean alexaSpeak() { return sp.getBoolean("alexa_speak", false); }
    /** Comma-separated contact names or numbers for SOS. */
    String sosContacts() { return sp.getString("sos_contacts", ""); }
    /** Folder picked for "read my documents" (a content:// tree), or "". */
    String docsTree() { return sp.getString("docs_tree", ""); }
    /** Only Anil's own voice wakes Jarvis. */
    boolean voiceLock() { return sp.getBoolean("voice_lock", false); }
    /** BookMyShow: Jarvis may pay from the MobiKwik wallet after Anil's spoken "yes" to the exact amount. Off by default. */
    boolean walletPay() { return sp.getBoolean("wallet_pay", false); }
    /** Most Jarvis may pay in one booking, in rupees. */
    int walletPayMax() { return sp.getInt("wallet_pay_max", 1000); }
    float voiceLockMax() { return sp.getFloat("voice_lock_max", 0.55f); }

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
