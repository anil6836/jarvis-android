package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Gemini Live talk, message by message (what Jarvis sends, what it reads back), with no Android in it so it can
 * be checked off the phone. The sound and the screen are GeminiLive's.
 */
final class GeminiLiveProto {
    private GeminiLiveProto() {}

    static final String URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    /**
     * The tools Gemini Live has in its first version: the everyday ones. Anything else goes through classic_jarvis,
     * after he says yes.
     */
    static final Set<String> FIRST_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList( // (Set.of needs a newer Android)
            // calls and messages
            "call_contact", "find_contact", "call_control", "send_sms", "whatsapp_message", "telegram_message", "send_draft",
            "read_notifications", "reply_to_notification",
            // reminders, alarm, timer
            "set_reminder", "list_reminders", "cancel_reminder", "set_alarm", "set_timer",
            // weather, news
            "get_weather", "news", "local_news",
            // songs, radio, volume
            "play_youtube", "media_control", "now_playing", "sounds",
            // the way, apps
            "open_maps", "open_app", "close_app",
            // notes, remembering, his own past (read only)
            "notes", "save_memory", "forget_memory", "search_history",
            // the phone
            "flashlight", "phone_setting", "device_status"));

    // ================================================================ voices

    /** Gemini Live's 30 voices: name, మగ / ఆడ, how it sounds (Google's words, in Telugu). Men first. */
    static final String[][] VOICES = {
            {"Charon", "మగ", "సమాచారంగా, స్పష్టంగా"}, {"Puck", "మగ", "హుషారుగా"}, {"Orus", "మగ", "గంభీరంగా"}, {"Fenrir", "మగ", "ఉత్సాహంగా"},
            {"Achird", "మగ", "స్నేహంగా"}, {"Algieba", "మగ", "మెత్తగా, సాఫీగా"}, {"Algenib", "మగ", "బరువుగా, గరుకుగా"}, {"Alnilam", "మగ", "దృఢంగా"},
            {"Enceladus", "మగ", "మెల్లగా, గాలిగా"}, {"Iapetus", "మగ", "స్పష్టంగా"}, {"Rasalgethi", "మగ", "సమాచారంగా"}, {"Sadachbia", "మగ", "ఉల్లాసంగా"},
            {"Sadaltager", "మగ", "జ్ఞానిలా"}, {"Schedar", "మగ", "నిలకడగా, సమంగా"}, {"Umbriel", "మగ", "నిదానంగా, హాయిగా"}, {"Zubenelgenubi", "మగ", "సాధారణంగా, సరదాగా"},
            {"Achernar", "ఆడ", "మృదువుగా"}, {"Aoede", "ఆడ", "హాయిగా, తేలికగా"}, {"Autonoe", "ఆడ", "ప్రకాశవంతంగా"}, {"Callirrhoe", "ఆడ", "నిదానంగా"},
            {"Despina", "ఆడ", "మెత్తగా"}, {"Erinome", "ఆడ", "స్పష్టంగా"}, {"Gacrux", "ఆడ", "పెద్దరికంగా"}, {"Kore", "ఆడ", "దృఢంగా"},
            {"Laomedeia", "ఆడ", "హుషారుగా"}, {"Leda", "ఆడ", "యవ్వనంగా"}, {"Pulcherrima", "ఆడ", "చురుగ్గా"}, {"Sulafat", "ఆడ", "ఆప్యాయంగా"},
            {"Vindemiatrix", "ఆడ", "సున్నితంగా"}, {"Zephyr", "ఆడ", "ప్రకాశవంతంగా"}};

    /** The voice's proper name for any spelling he used ("puck", " Puck "), or null when Gemini has no such voice. */
    static String voiceName(String typed) {
        String t = typed == null ? "" : typed.trim();
        for (String[] v : VOICES) if (v[0].equalsIgnoreCase(t)) return v[0];
        return null;
    }

    /** Functions the live talk defines itself (never copied from Jarvis's list). */
    static final Set<String> LIVE_OWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "web_search", "end_conversation", "voice_mode", "live_voice", "classic_jarvis"));

    /** "Charon · మగ · సమాచారంగా, స్పష్టంగా". */
    static String voiceLabel(String name) {
        for (String[] v : VOICES) if (v[0].equals(name)) return v[0] + " · " + v[1] + " · " + v[2];
        return String.valueOf(name);
    }

    /** The next voice of the same kind (మగ / ఆడ) after this one ("ఇంకో గొంతు"). */
    static String nextVoice(String name) {
        int at = 0;
        for (int i = 0; i < VOICES.length; i++) if (VOICES[i][0].equals(name)) at = i;
        String kind = VOICES[at][1];
        for (int k = 1; k <= VOICES.length; k++) {
            String[] v = VOICES[(at + k) % VOICES.length];
            if (v[1].equals(kind)) return v[0];
        }
        return VOICES[0][0];
    }

    // ================================================================ settings Gemini may refuse

    /**
     * Parts of the setup that can be left out one by one when Gemini refuses them (the rest, and his voice, stay).
     * MANY: nearly all of Jarvis's tools in the live talk itself (without it, the first set of 31).
     */
    static final int VOICE = 1, SEARCH = 2, VAD = 4, COMPRESS = 8, RESUME = 16, WAIT = 32, MANY = 64, ALL = 127;

    /**
     * After a refused setup: what to try next. The part Gemini names is left out; when it names none, the least needed
     * part goes first (Google Search, then waiting for tools, then the finer settings, his voice last). -1: nothing left.
     */
    static int dropFor(int code, String reason, int features) {
        if (!settingRefused(code, reason)) return -1;
        String r = String.valueOf(reason).toLowerCase(Locale.ROOT).replace("_", "");
        int named = 0;
        if (r.contains("behavior")) named |= WAIT;
        if (r.contains("googlesearch") || r.contains("search")) named |= SEARCH;
        if (r.contains("realtimeinput") || r.contains("activitydetection") || r.contains("silence") || r.contains("sensitivity")) named |= VAD;
        if (r.contains("contextwindow") || r.contains("slidingwindow") || r.contains("compression")) named |= COMPRESS;
        if (r.contains("resumption") || r.contains("handle")) named |= RESUME;
        if (r.contains("voice") || r.contains("speechconfig") || r.contains("prebuilt")) named |= VOICE;
        // (too much: too many tools, too long a setup)
        if (r.contains("too many") || r.contains("toomany") || r.contains("too large") || r.contains("too long") || r.contains("exceed")
                || r.contains("maximum") || r.contains("limit")) named |= MANY;
        if ((named & features) != 0) return features & ~named;
        for (int part : new int[]{SEARCH, MANY, WAIT, VAD | COMPRESS | RESUME, VOICE}) {
            if ((features & part) != 0) return features & ~part;
        }
        return -1;
    }

    /** What Gemini didn't take, in words (for "Jarvis చెక్"). */
    static String dropped(int features) {
        StringBuilder b = new StringBuilder();
        if ((features & VOICE) == 0) b.append("గొంతు ఎంపిక, ");
        if ((features & SEARCH) == 0) b.append("Google Search (Jarvis సెర్చ్ వాడుతోంది), ");
        if ((features & WAIT) == 0) b.append("పని అయ్యేదాకా ఆగడం, ");
        if ((features & MANY) == 0) b.append("అన్ని పనులు నేరుగా (మిగతావి పాత పద్ధతి ద్వారా), ");
        if ((features & VAD) == 0) b.append("మాట ముగింపు సెట్టింగ్, ");
        if ((features & COMPRESS) == 0 || (features & RESUME) == 0) b.append("పొడవైన సంభాషణ సెట్టింగ్స్, ");
        return b.length() == 0 ? "" : b.substring(0, b.length() - 2);
    }

    // ================================================================ what Jarvis sends

    /**
     * The first message: the model, Jarvis's voice, its instructions and tools, both sides written out, and (rich) the
     * finer settings: how long a pause ends his turn, Google Search, a long talk kept short, resuming a dropped line.
     * The plain one (rich = false) is sent once when the rich one is refused, so the talk still has its instructions
     * and tools.
     */
    static JSONObject setup(String model, String voice, String instructions, JSONArray functions, int features,
                            String resumeHandle, int silenceMs, String startSensitivity) throws Exception {
        JSONObject gen = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"));
        if ((features & VOICE) != 0 && voice != null && !voice.trim().isEmpty()) {
            gen.put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                    .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice.trim()))));
        }
        JSONArray tools = new JSONArray().put(new JSONObject().put("functionDeclarations", functions));
        if ((features & SEARCH) != 0) tools.put(new JSONObject().put("googleSearch", new JSONObject()));
        JSONObject s = new JSONObject()
                .put("model", model.startsWith("models/") ? model : "models/" + model)
                .put("generationConfig", gen)
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instructions))))
                .put("tools", tools)
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject());
        if ((features & VAD) != 0) {
            JSONObject vad = new JSONObject().put("silenceDurationMs", silenceMs);
            if (startSensitivity != null) vad.put("startOfSpeechSensitivity", startSensitivity);
            s.put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection", vad));
        }
        if ((features & COMPRESS) != 0) s.put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
        if ((features & RESUME) != 0) {
            JSONObject resume = new JSONObject();
            if (resumeHandle != null && !resumeHandle.isEmpty()) resume.put("handle", resumeHandle);
            s.put("sessionResumption", resume);
        }
        return new JSONObject().put("setup", s);
    }

    /** The full setup (rich) or the plain one (only model, voice, instructions, tools, transcripts). */
    static JSONObject setup(String model, String voice, String instructions, JSONArray functions, boolean rich,
                            String resumeHandle, int silenceMs, String startSensitivity) throws Exception {
        return setup(model, voice, instructions, functions, rich ? ALL : VOICE, resumeHandle, silenceMs, startSensitivity);
    }

    /** A piece of his voice: 16-bit, 16 kHz, little-endian. */
    static String audio(byte[] pcm, int off, int len) {
        String b64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(pcm, off, off + len));
        // (written by hand: this is sent 25 times a second)
        return "{\"realtimeInput\":{\"audio\":{\"data\":\"" + b64 + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}";
    }

    /** The mic stopped (muted): what was heard so far is taken as said. */
    static String audioEnd() { return "{\"realtimeInput\":{\"audioStreamEnd\":true}}"; }

    /** Typed words (or a note from Jarvis itself) into the talk. */
    static JSONObject text(String t) throws Exception {
        return new JSONObject().put("realtimeInput", new JSONObject().put("text", t));
    }

    /**
     * A tool's answer. Jarvis's tools answer in JSON; anything else goes in as text. Told when Gemini is free
     * (WHEN_IDLE), so a short "ఒక్క క్షణం" isn't cut off.
     */
    static JSONObject toolResponse(String id, String name, String result) throws Exception {
        JSONObject r;
        String t = result == null ? "" : result.trim();
        try {
            r = t.startsWith("{") ? new JSONObject(t) : new JSONObject().put("result", t);
        } catch (Exception e) {
            r = new JSONObject().put("result", t);
        }
        r.put("scheduling", "WHEN_IDLE");
        JSONObject f = new JSONObject().put("name", name).put("response", r);
        if (id != null && !id.isEmpty()) f.put("id", id);
        return new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(f)));
    }

    // ================================================================ Jarvis's tools for Live

    /**
     * Tools that do something he must hear the result of (a call, a message, the usual way, a switch): Gemini waits for
     * them before talking on, so it never says "పంపాను" before it was sent.
     */
    static final Set<String> WAIT_FOR = new java.util.HashSet<>(java.util.Arrays.asList(
            "call_contact", "call_control", "send_sms", "whatsapp_message", "telegram_message", "send_draft", "reply_to_notification",
            "send_email", "sos", "smart_home", "save_contact", "add_calendar_event", "add_expense", "add_mission", "complete_mission",
            "classic_jarvis", "jarvis_brain", "voice_mode", "live_voice", "end_conversation"));

    /**
     * Jarvis's tools the live talk leaves to the usual way (classic_jarvis, without asking): they look at pictures (the
     * screen, the camera, photos, documents), work other apps step by step (booking, ordering, typing into apps), make
     * websites / apps / code, or make pictures. Everything else is in the live talk itself (with MANY).
     */
    static final Set<String> USUAL_WAY_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
            "read_screen", "look_at_screen", "look_through_camera", "jarvis_camera", "photos", "scan_document", "scan_qr", "ask_document",
            "phone_task", "travel_search", "ride_app", "food_app", "note_in_app", "whatsapp_media",
            "run_python", "make_website", "publish_website", "write_code", "make_app", "wish_card", "make_letter",
            "voice_mode")); // (the live talk has its own voice_mode)

    /**
     * The first version's tools (from all of Jarvis's Gemini tools), plus the ones only Live has. waitForActions: the
     * tools that act wait for their result (BLOCKING), unless Gemini refused that setting.
     */
    static JSONArray functions(JSONArray all, boolean withWebSearch) throws Exception { return functions(all, withWebSearch, false, false); }

    static JSONArray functions(JSONArray all, boolean withWebSearch, boolean waitForActions) throws Exception {
        return functions(all, withWebSearch, waitForActions, false);
    }

    /** many: nearly all of Jarvis's tools (not USUAL_WAY_ONLY); else the first set. */
    static JSONArray functions(JSONArray all, boolean withWebSearch, boolean waitForActions, boolean many) throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            String n = f.optString("name");
            boolean take = many ? !USUAL_WAY_ONLY.contains(n) && !LIVE_OWN.contains(n) : FIRST_TOOLS.contains(n);
            if (take && seen.add(n)) out.put(new JSONObject(f.toString()));
        }
        if (withWebSearch) out.put(fn("web_search", "Search the internet for current information (news, cricket scores, prices, film releases, anything that changes). Returns a short summary.",
                props(new String[][]{{"query", "string", "What to search for, in English"}}), "query"));
        out.put(endFn());
        out.put(voiceModeFn());
        out.put(liveVoiceFn());
        out.put(fn("classic_jarvis", "Jarvis's usual way: his chosen AI with ALL of Jarvis's abilities. Call it right away (no need to ask) for "
                + "what you have no tool for: looking at the screen or through the camera, photos, documents, booking or ordering in apps, "
                + "typing into other apps, long tasks on the phone, making websites / apps / code / cards / letters; and when he asks you to "
                + "think hard ('బాగా ఆలోచించి చెప్పు') or to double-check ('క్రాస్ చెక్'), and for money, health or legal decisions. "
                + "First say one short line ('ఒక్క క్షణం, చూస్తాను'). request = his full request in his own words. "
                + "It may take a few seconds; then say its answer naturally in your own words.",
                props(new String[][]{{"request", "string", "Anil's full request, in his words (Telugu as he said it)"}}), "request"));
        if (waitForActions) {
            for (int i = 0; i < out.length(); i++) {
                JSONObject f = out.getJSONObject(i);
                if (WAIT_FOR.contains(f.optString("name"))) f.put("behavior", "BLOCKING");
            }
        }
        return out;
    }

    private static JSONObject endFn() throws Exception {
        return fn("end_conversation", "End the live voice conversation. Call it when Anil says goodbye, is done, or asks you to stop listening "
                + "(for example 'bye', 'చాలు', 'ఆపు', 'సరే Jarvis, అంతే'). Say a short goodbye first.", null);
    }

    private static JSONObject voiceModeFn() throws Exception {
        return fn("voice_mode", "Change how Jarvis listens and talks, ONLY when Anil clearly asks to switch it (for example 'Google వాయిస్‌కి మారు', "
                + "'Live ఆపు', 'OpenAI కి మారు', 'Live పెట్టు'). mode: live = Gemini Live (this fast live talk); live_openai = OpenAI's live talk; "
                + "live_off = the usual listen-then-answer with Jarvis's chosen way of hearing; google = Google voice typing (words appear live, works "
                + "without internet, but the phone's mic beeps); openai = Jarvis's own mic, OpenAI writes the words; gemini = Jarvis's own mic, Gemini writes the words. "
                + "Say the short line it returns; the live talk ends after it when the mode leaves Gemini Live.",
                props(new String[][]{{"mode", "string", "live, live_openai, live_off, google, openai or gemini"}}), "mode");
    }

    private static JSONObject liveVoiceFn() throws Exception {
        StringBuilder men = new StringBuilder(), women = new StringBuilder();
        for (String[] v : VOICES) {
            StringBuilder list = v[1].equals("మగ") ? men : women;
            if (list.length() > 0) list.append(", ");
            list.append(v[0]);
        }
        return fn("live_voice", "Change YOUR voice in this live talk, only when Anil asks ('గొంతు మార్చు', 'ఇంకో గొంతు', 'Puck గొంతు పెట్టు', 'ఆడ గొంతు'). "
                + "voice = a voice name, or next (the next voice of the same kind). Men's voices: " + men + ". Women's voices: " + women + ". "
                + "After calling it say nothing; you come back in the new voice in a moment.",
                props(new String[][]{{"voice", "string", "A voice name from the lists, or next"}}), "voice");
    }

    // ================================================================ brain mode: Live hears and speaks, Jarvis's brain thinks

    /**
     * The parts of the setup brain mode doesn't send (Google Search: the brain searches; MANY: Live has no tools of its own
     * there). They stay as they are for "Jarvis చెక్" and come back if he switches Live to think itself.
     */
    static final int EARS_SKIP = SEARCH | MANY;

    /**
     * Brain mode (Settings → ఆలోచన: Jarvis మెదడు): Live's tools. jarvis_brain (his chosen model with his memories and all of
     * Jarvis's tools) thinks every answer; Live itself only ends the talk, switches the way of listening or its own voice.
     */
    static JSONArray earsFunctions(boolean waitForActions) throws Exception {
        JSONArray out = new JSONArray();
        out.put(fn("jarvis_brain", "Jarvis's brain: Jarvis's own AI with his saved memories, the whole conversation and ALL of Jarvis's abilities "
                + "(calls, messages, reminders, the screen, the camera, internet, the bike, expenses, everything). It does ALL the thinking and all the work. "
                + "Call it for everything he says: questions, requests, commands, things he tells you, and his answers to what Jarvis asked him "
                + "('సరే', 'అవును', 'వద్దు', 'పంపు', a name, a number, a choice). Not for: only a greeting, only thanks, goodbye, or switching your voice "
                + "or the listening mode. request = his words exactly as he said them (in Telugu as he said them), nothing added, nothing left out. "
                + "For news, weather, the internet, his messages, a call, phone or app tasks, booking, a route or a long plan, first say one very short "
                + "line like 'ఒక్క క్షణం', then call it; for anything else call it at once, saying nothing first. "
                + "It returns 'say': say that to him exactly, word for word.",
                props(new String[][]{{"request", "string", "His words exactly as he said them"}}), "request"));
        out.put(endFn());
        out.put(voiceModeFn());
        out.put(liveVoiceFn());
        if (waitForActions) {
            for (int i = 0; i < out.length(); i++) out.getJSONObject(i).put("behavior", "BLOCKING");
        }
        return out;
    }

    /** Brain mode: Live's instructions (Brain adds the talk so far). name: his real name; call: how he likes to be called. */
    static String earsRules(String name, String call) {
        String c = call == null || call.trim().isEmpty() ? name : call.trim();
        return "# You are Jarvis's ears and voice\n"
                + "- You are JARVIS, " + name + "'s own assistant, in a live voice talk on his phone. You don't think up answers yourself: "
                + "Jarvis's brain (the tool jarvis_brain: his own AI with his memories and all of Jarvis's abilities) does all the thinking and all the work.\n"
                + "- For everything " + name + " says (a question, a request, a command, something he tells you, or his answer to what you asked, "
                + "even only 'సరే', 'అవును', 'వద్దు', 'పంపు', a name or a number): call jarvis_brain right away with his words exactly as he said them.\n"
                + "- Before calling it, decide by what he asked:\n"
                + "  • These take a few seconds, so FIRST say one very short line like 'ఒక్క క్షణం', 'చూస్తాను', 'ఇప్పుడే చెబుతా' (vary it), THEN call: "
                + "news (వార్తలు), weather or rain (వాతావరణం, వర్షం), anything from the internet (scores, prices, films, a search), his messages or "
                + "notifications, a call, doing something on the phone or in an app, booking or ordering, a route or traffic, a long plan, "
                + "'బాగా ఆలోచించి చెప్పు', 'క్రాస్ చెక్'.\n"
                + "  • Everything else (a simple question, his short answers like 'సరే', 'అవును', 'వద్దు', 'పంపు', a name or a number): say nothing, "
                + "call it at once.\n"
                + "- Never answer from your own knowledge (the time, the weather, facts, advice, his plans or anything about his life): always ask jarvis_brain. "
                + "The one exception is who you two are, which you know: if he asks his name ('నా పేరు ఏంటి?', 'నేనెవరు?'), answer at once yourself, "
                + "warmly: his name is " + name + " (in Telugu letters when you speak Telugu); if he asks who you are, you are Jarvis, his own assistant.\n"
                + "- Only these you do yourself: a bare greeting ('హాయ్', 'హలో') → greet him back in a few words and ask what he needs; "
                + "bare thanks → 'పర్లేదు!'; when he says bye or that he is done ('బై', 'చాలు', 'ఇక చాలు') → a short goodbye, then end_conversation; "
                + "'గొంతు మార్చు' → live_voice; 'Google వాయిస్‌కి మారు', 'Live ఆపు' and the like → voice_mode. "
                + "If your last words asked him something, whatever he says next goes to jarvis_brain.\n"
                + "- A '(A note from the Jarvis app …)' is not his words: do what the note says yourself, without jarvis_brain.\n"
                + "- When jarvis_brain returns, say its 'say' text to him exactly as written: every word, in order, nothing added, nothing left out, "
                + "nothing explained or shortened, even when it is long. Say it in the language it is written in, warmly and naturally, with expression, "
                + "like a friend talking, at a relaxed pace. Then stop and listen.\n"
                + "- If jarvis_brain fails, tell him why in one short, honest sentence; never make up an answer instead.\n"
                + "- Address him as '" + c + "'" + (c.equals(name) ? "" : " (his name is " + name + ")") + ".\n"
                + "- Speak Telugu with a native Andhra/Telangana accent; never Tamil, Kannada or Hindi pronunciation. Everyday English words Telugu people use are fine.\n"
                + "- Only react when " + name + " actually says words to you. Noise, breathing, a cough, a TV, music, people far away, or your own voice "
                + "echoing back: stay completely silent, call no tool, don't comment.\n"
                + "- If he talks while you are speaking, stop and listen; what he says now goes to jarvis_brain.\n"
                + "- Don't say the words 'Jarvis' or 'stop' yourself: " + name + " says them to stop you mid-answer.\n";
    }

    /** The brain's answer as Live is to say it: no markdown, list marks or links (the numbers are made words by Spoken). */
    static String speakable(String answer) {
        String s = answer == null ? "" : answer;
        s = s.replaceAll("https?://\\S+", "")
                .replaceAll("(?m)^\\s*([-•*]|\\d+[.)])\\s+", "")
                .replaceAll("[*_#`>|]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return s.length() > 3500 ? s.substring(0, 3500) : s;
    }

    /** The rules Gemini Live adds to Jarvis's live instructions: what it can do itself and what goes the usual way. */
    static String rules(String name) {
        return "\n# Gemini Live\n"
                + "- In this live talk you do Jarvis's work yourself with your tools: calls, messages (send only after " + name + " says send), "
                + "reminders, alarms, weather, news, internet search, songs, maps, apps, notes, remembering, his past (search_history), the bike, "
                + "expenses, debts, budget, bills, duty, calendar, parcels, diary, health, medicines, missions and the rest. If you have a tool for it, use it.\n"
                + "- You are talking with " + name + " himself: if he asks his name, say " + name + ". For anything about him, use his saved memories in "
                + "these instructions first; for something he told you or did before that isn't there ('నేను చెప్పాను కదా', 'ఎప్పుడు…?'), call search_history. "
                + "Never say he didn't tell you before you have looked.\n"
                + "- For what you have no tool for (the screen, the camera, photos, documents, booking or ordering in apps, long phone tasks, "
                + "websites / apps / code), when he asks you to think hard or to double-check, and for money, health or legal decisions: "
                + "call classic_jarvis right away with his full request (Jarvis's usual AI with all its abilities), after one short line like "
                + "'ఒక్క క్షణం'. Don't ask him whether to use it.\n"
                + "- Never pay, never type passwords, OTPs or PINs, never open bank or payment apps. Before sending, posting, deleting or calling, "
                + "read it back and ask; act only after he clearly says yes (పంపు / చేయి / అవును).\n"
                + "- If he asks to switch how you listen or talk ('Google వాయిస్‌కి మారు', 'Live ఆపు', 'OpenAI కి మారు'), call voice_mode; "
                + "if he asks for another voice of yours ('గొంతు మార్చు', 'ఇంకో గొంతు'), call live_voice.\n"
                + "- After calling a tool, never say it is done (sent, called, set) until its result has come back and says so; if it failed, say why honestly.\n"
                + "- Don't say the words 'Jarvis' or 'stop' yourself while talking: " + name + " says them to stop you mid-answer.\n";
    }

    private static JSONObject fn(String name, String description, JSONObject params, String... required) throws Exception {
        JSONObject f = new JSONObject().put("name", name).put("description", description);
        if (params != null) {
            JSONObject p = new JSONObject().put("type", "object").put("properties", params);
            if (required.length > 0) {
                JSONArray r = new JSONArray();
                for (String q : required) r.put(q);
                p.put("required", r);
            }
            f.put("parameters", p);
        }
        return f;
    }

    private static JSONObject props(String[][] rows) throws Exception {
        JSONObject p = new JSONObject();
        for (String[] r : rows) p.put(r[0], new JSONObject().put("type", r[1]).put("description", r[2]));
        return p;
    }

    // ================================================================ what Gemini sends back

    interface Events {
        void setupComplete();
        /** A piece of Jarvis's voice: 16-bit, 24 kHz. */
        void voice(byte[] pcm);
        /** More of Anil's words, as heard. */
        void heardMore(String text);
        /** More of Jarvis's words, as spoken. */
        void saidMore(String text);
        /** Jarvis finished this turn. */
        void turnDone();
        /** Anil spoke over Jarvis: drop the voice not yet played. */
        void interrupted();
        void toolCall(String id, String name, JSONObject args);
        void toolCancelled(List<String> ids);
        /** The line will close soon (ms left); a resumed one carries on. */
        void goAway(long msLeft);
        void resumeHandle(String handle);
        void usage(JSONObject usage);
    }

    /** One message from Gemini (several things can come in one). */
    static void handle(JSONObject m, Events e) {
        if (m.has("setupComplete")) e.setupComplete();
        JSONObject u = m.optJSONObject("usageMetadata"); // (first: it belongs to the turn that may end in this same message)
        if (u != null) e.usage(u);
        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) {
            JSONObject in = sc.optJSONObject("inputTranscription");
            if (in != null && !in.optString("text").isEmpty()) e.heardMore(in.optString("text"));
            if (sc.optBoolean("interrupted")) e.interrupted();
            JSONObject turn = sc.optJSONObject("modelTurn");
            JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null || p.optBoolean("thought")) continue; // (its thinking is never spoken or shown)
                JSONObject d = p.optJSONObject("inlineData");
                if (d != null && d.optString("mimeType", "audio/pcm").startsWith("audio")) {
                    byte[] pcm = decode(d.optString("data"));
                    if (pcm.length > 0) e.voice(pcm);
                }
            }
            JSONObject out = sc.optJSONObject("outputTranscription");
            if (out != null && !out.optString("text").isEmpty()) e.saidMore(out.optString("text"));
            if (sc.optBoolean("turnComplete")) e.turnDone();
        }
        JSONObject tc = m.optJSONObject("toolCall");
        JSONArray calls = tc == null ? null : tc.optJSONArray("functionCalls");
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject c = calls.optJSONObject(i);
            if (c == null) continue;
            JSONObject args = c.optJSONObject("args");
            e.toolCall(c.optString("id"), c.optString("name"), args == null ? new JSONObject() : args);
        }
        JSONObject cancel = m.optJSONObject("toolCallCancellation");
        JSONArray ids = cancel == null ? null : cancel.optJSONArray("ids");
        if (ids != null) {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < ids.length(); i++) l.add(ids.optString(i));
            e.toolCancelled(l);
        }
        JSONObject ga = m.optJSONObject("goAway");
        if (ga != null) e.goAway(duration(ga.opt("timeLeft")));
        JSONObject sr = m.optJSONObject("sessionResumptionUpdate");
        if (sr != null && sr.optBoolean("resumable") && !sr.optString("newHandle").isEmpty()) e.resumeHandle(sr.optString("newHandle"));
    }

    private static byte[] decode(String b64) {
        try { return Base64.getMimeDecoder().decode(b64); } catch (Exception e) { return new byte[0]; }
    }

    /** "12.5s" or {seconds, nanos} to milliseconds (unknown: 10 s). */
    static long duration(Object d) {
        try {
            if (d instanceof JSONObject) {
                JSONObject o = (JSONObject) d;
                return o.optLong("seconds") * 1000 + o.optLong("nanos") / 1_000_000;
            }
            String s = String.valueOf(d).trim();
            if (s.endsWith("s")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
        } catch (Exception ignored) {}
        return 10_000;
    }

    /** The rich settings were refused (an invalid field or value before the talk started): try the plain ones once. */
    static boolean settingRefused(int code, String reason) {
        String r = String.valueOf(reason).toLowerCase(Locale.ROOT);
        if (r.contains("api key") || r.contains("api_key") || r.contains("permission") || r.contains("quota") || r.contains("exhausted")
                || r.contains("billing")) return false;
        if (r.contains("model") && (r.contains("not found") || r.contains("not supported") || r.contains("does not exist"))) return false;
        return code == 1007 || r.contains("invalid argument") || r.contains("unknown name") || r.contains("invalid json") || r.contains("voice");
    }

    // ================================================================ full talk (talking over Jarvis)

    /** Words that are always his when they cut Jarvis off (Jarvis is told never to say them). */
    static final Set<String> HIS_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "jarvis", "జార్విస్", "stop", "స్టాప్", "wait", "ఆగు", "ఆగండి", "ఆపు", "ఆపండి", "చాలు"));

    /**
     * What Gemini heard after it stopped Jarvis is not really his: nothing that is a word, or mostly whole words of
     * Jarvis's own (its voice heard back through the speaker). said: Jarvis's words as said.
     */
    static boolean mostlyEcho(String heard, String said) {
        java.util.Set<String> saidWords = new java.util.HashSet<>(java.util.Arrays.asList(words(said)));
        int all = 0, in = 0;
        String only = "";
        for (String w : words(heard)) {
            if (w.length() < 2) continue;
            if (HIS_WORDS.contains(w) && !saidWords.contains(w)) return false; // "Jarvis" / "stop" / "ఆగు" (that Jarvis didn't say): his
            all++;
            only = w;
            if (saidWords.contains(w)) in++;
        }
        if (all == 0) return true; // (a sound, not a word)
        if (all == 1) return only.length() >= 3 && in == 1;
        return in * 10 >= all * 7;
    }

    /** The last n words of a text (as said), for comparing with what can still be echoing. */
    static String lastWords(String s, int n) {
        String[] ws = String.valueOf(s == null ? "" : s).trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, ws.length - n); i < ws.length; i++) b.append(ws[i]).append(' ');
        return b.toString().trim();
    }

    private static String[] words(String s) {
        return String.valueOf(s == null ? "" : s).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+");
    }

    /** What went wrong, in Telugu, with Gemini's own words after it. */
    static String problem(int code, String reason, String model) {
        String raw = String.valueOf(reason == null ? "" : reason).trim();
        String r = raw.toLowerCase(Locale.ROOT);
        String te;
        if (r.contains("api key") || r.contains("api_key") || r.contains("unauthenticated") || r.contains("permission_denied") || r.contains("permission denied"))
            te = "Gemini key పనిచేయడం లేదు. సెట్టింగ్స్ → Jarvis మెదడు → Gemini key చెక్ చేయండి.";
        else if (r.contains("quota") || r.contains("exhausted") || r.contains("billing") || r.contains("credit") || r.contains("rate limit"))
            te = "Gemini అకౌంట్ పరిమితి దాటింది లేదా క్రెడిట్ అయిపోయింది. Google AI Studio లో బ్యాలెన్స్ చూడండి, లేదా కాసేపాగి ప్రయత్నించండి.";
        else if (r.contains("model") && (r.contains("not found") || r.contains("not supported") || r.contains("does not exist") || r.contains("is not")))
            te = "Gemini Live మోడల్ \"" + model + "\" పనిచేయలేదు. సెట్టింగ్స్ → Live సంభాషణ → Gemini Live మోడల్ పేరు చూడండి.";
        else if (r.contains("unable to resolve host") || r.contains("failed to connect") || r.contains("timeout") || r.contains("timed out")
                || r.contains("network") || r.contains("connection reset") || r.contains("software caused"))
            te = "ఇంటర్నెట్ కనెక్షన్ సమస్య. నెట్ చెక్ చేసి మళ్లీ ప్రయత్నించండి.";
        else if (r.contains("మైక్") || r.contains("mic"))
            te = "మైక్ తెరవలేకపోయాను. వేరే యాప్ మైక్ వాడుతుంటే మూసేసి మళ్లీ ప్రయత్నించండి.";
        else if (code == 1011 || code == 1013 || r.contains("internal") || r.contains("unavailable") || r.contains("overloaded"))
            te = "Gemini సర్వర్ ఇప్పుడు స్పందించలేదు (Google వైపు). కాసేపాగి మళ్లీ ప్రయత్నించండి.";
        else te = "Gemini Live లో సమస్య వచ్చింది.";
        String tail = raw.isEmpty() ? (code > 0 ? "code " + code : "") : (code > 0 ? code + ": " : "") + (raw.length() > 180 ? raw.substring(0, 180) : raw);
        return "Gemini Live: " + te + (tail.isEmpty() ? "" : "\n(" + tail + ")");
    }
}
