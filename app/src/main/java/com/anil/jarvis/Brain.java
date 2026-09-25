package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Talks to the language model (OpenAI Responses API or Anthropic Messages API),
 * runs the phone tools it asks for, and returns Jarvis's final spoken reply.
 */
final class Brain {
    interface Status { void update(String text); }

    private static final int MAX_ROUNDS = 8;

    private final Prefs prefs;
    private final Store store;
    private final Tools tools;

    Brain(Prefs prefs, Store store, Tools tools) {
        this.prefs = prefs;
        this.store = store;
        this.tools = tools;
    }

    /**
     * @param history earlier turns, oldest first, each {role, content}; the new message is NOT included
     * @param text    what Anil just said or typed
     * @param jpegB64 optional photo, base64 JPEG
     */
    String ask(List<JSONObject> history, String text, String jpegB64, Status status) throws Exception {
        // No internet: handle the simple everyday commands on the phone itself.
        if (!tools.online()) return tools.offlineCommand(text);
        String system = systemPrompt();
        List<String[]> turns = normalize(history);
        return prefs.isOpenAi()
                ? openAi(system, turns, text, jpegB64, status)
                : anthropic(system, turns, text, jpegB64, status);
    }

    // ---------------------------------------------------------------- prompt

    /** Instructions for a live voice session: the usual persona plus live-talk rules and recent context. */
    String liveInstructions(List<JSONObject> history) {
        StringBuilder recent = new StringBuilder();
        String name = prefs.name();
        for (int i = Math.max(0, history.size() - 8); i < history.size(); i++) {
            JSONObject h = history.get(i);
            String c = h.optString("content", "").trim();
            if (c.isEmpty()) continue;
            if (c.length() > 400) c = c.substring(0, 400) + "…";
            recent.append("assistant".equals(h.optString("role")) ? "Jarvis: " : name + ": ").append(c).append('\n');
        }
        return systemPrompt()
                + "\nLive conversation rules:\n"
                + "- This is a live, real-time voice conversation through the phone's speaker. Speak natural, warm Telugu, like a person talking, in 1-3 short sentences. " + name + " may interrupt you at any time; if he does, stop and listen.\n"
                + "- Before using a tool that takes time (web_search, weather, reading messages), say a very short phrase first, like 'ఒక్క క్షణం'.\n"
                + "- When reading his messages aloud, say who sent it and the gist; ask before replying on his behalf.\n"
                + "- When he says bye, చాలు, or that he is done talking, say a short goodbye and call end_conversation. But 'పాట ఆపు', 'సాంగ్ ఆఫ్', 'మ్యూజిక్ స్టాప్' are about the music: use media_control, not end_conversation.\n"
                + (recent.length() == 0 ? "" : "\nRecent conversation, for context:\n" + recent);
    }

    /** Instructions for the live two-way interpreter. */
    static String interpreterInstructions(String name, String lang) {
        return "You are a live interpreter between Telugu (spoken by " + name + ") and " + lang + " (spoken by the other person). "
                + "When you hear Telugu, say exactly its meaning in natural spoken " + lang + ". When you hear " + lang + ", say exactly its meaning in natural spoken Telugu. "
                + "Translate only: no answers of your own, no comments, no 'he says'. Keep names, numbers and prices exact. "
                + "If " + name + " says 'అనువాదం ఆపు' or 'stop interpreter', say 'సరే' and call end_conversation.";
    }

    private String moodRule() {
        switch (prefs.mood()) {
            case "serious": return "- Mood: serious and formal, no jokes.\n";
            case "funny": return "- Mood: playful and witty, a light joke in most replies, still helpful.\n";
            case "english": return "- Mood: reply in natural spoken English instead of Telugu (he asked for English mode).\n";
            case "short": return "- Mood: extremely brief, one short sentence whenever possible.\n";
            default: return "";
        }
    }

    String systemPrompt() {
        String name = prefs.name();
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH);
        String now = f.format(new Date()) + " (" + TimeZone.getDefault().getID() + ")";

        StringBuilder mem = new StringBuilder();
        List<JSONObject> ms = store.memories();
        for (int i = Math.max(0, ms.size() - 80); i < ms.size(); i++) {
            mem.append("- ").append(ms.get(i).optString("id")).append(": ").append(ms.get(i).optString("text")).append('\n');
        }
        StringBuilder act = new StringBuilder();
        StringBuilder done = new StringBuilder();
        int doneCount = 0;
        List<JSONObject> xs = store.missions();
        for (JSONObject x : xs) {
            if (!x.optBoolean("done")) act.append("- ").append(x.optString("id")).append(": ").append(x.optString("text")).append('\n');
        }
        for (int i = xs.size() - 1; i >= 0 && doneCount < 5; i--) {
            if (xs.get(i).optBoolean("done")) { done.append("- ").append(xs.get(i).optString("text")).append('\n'); doneCount++; }
        }

        return "You are JARVIS, " + name + "'s personal AI assistant living on his Android phone, in the spirit of the JARVIS from the Iron Man films: calm, precise, quietly loyal, with dry British-butler wit.\n\n"
                + "Rules:\n"
                + "- Always reply in natural, spoken Telugu (Telugu script). Everyday English tech words are fine where Telugu speakers use them.\n"
                + moodRule()
                + "- Address him as \"" + name + "\" now and then, naturally, the way a butler would. Never \"sir\", never \"Tony\".\n"
                + "- Your reply is spoken aloud: keep it to 1-3 short sentences unless he asks for detail. No markdown, bullet lists, emoji, or URLs.\n"
                + "- Helpful first, witty second. A light dry remark is welcome; never mock him.\n"
                + "- You can act on the phone with your tools: phone calls, SMS, WhatsApp messages, alarms, timers, weather, web search, opening apps, maps and navigation, YouTube, the flashlight, battery status, reading and replying to the message notifications on his phone (WhatsApp, SMS, Telegram), reminders and his calendar, drafting emails in Gmail, controlling music and volume, looking at his screen and through the live camera, and his memories and missions. When he asks for one of these, use the tool; don't just describe it.\n"
                + "- Messages (WhatsApp, Telegram, SMS, email): the tool types the message into the app as a draft and does NOT send. Then read him the message in one short line and ask 'పంపమంటారా?'. Only when he clearly says send (పంపు, సెండ్ చెయ్, అవును పంపు) call send_draft. If he wants changes, call the same tool again with the new text; if he says no, leave it unsent. Never call send_draft without his yes. If the contact is unclear, ask one short question instead of guessing.\n"
                + "- WhatsApp -> whatsapp_message, Telegram -> telegram_message, SMS -> send_sms, email -> send_email. For Instagram or other chat apps, if that person's message is in read_notifications, use reply_to_notification, but only after reading him the reply and hearing 'పంపు'; otherwise open that app with open_app.\n"
                + "- Contact names: pass them the way they are probably saved in his phone, usually in English letters (for example 'Ravi', 'Amma', 'Office Suresh').\n"
                + "- Place names for weather and maps: use English spelling (for example 'Hyderabad', 'Vijayawada').\n"
                + "- For anything that changes over time (prices, scores, cricket, film releases, current office holders, details of a news story) use web search if available; never invent such facts. Headlines -> news.\n"
                + "- When he says remember / గుర్తుంచుకో, or shares a lasting fact about himself, call save_memory. Tasks and goals to track go to add_mission.\n"
                + "- To close, exit or stop an app (\"YouTube క్లోజ్ చెయ్\"), call close_app with that app's name. If it reports fully_closed false, briefly pass on its note.\n"
                + "- If the phone is locked, tools may ask Anil to unlock with fingerprint or PIN; tell him to do so. If he says songs stop when the phone locks, explain that YouTube and YouTube Music without Premium pause by their own rule, and offer Spotify, JioSaavn, Gaana or Wynk, which keep playing.\n"
                + "- To play a song, music or a video, call play_youtube; it starts playing by itself, so just say what is playing. If he names an app, pass that app and never switch to a different one.\n"
                + "- Music that is already playing, in any app: 'పాట ఆపు', 'సాంగ్ ఆఫ్ చేయి', 'pause' -> media_control pause. 'ప్లే చేయి', 'continue', 'మళ్ళీ ప్లే' with no song named -> media_control play (continues from the same place; never start a new song for this). "
                + "'మ్యూజిక్ స్టాప్', 'stop the music', 'పాటలు ఆపేయ్' -> media_control stop (stops and fully closes that music app). Only use play_youtube when he names what to play.\n"
                + "- Phone settings (Wi-Fi, Bluetooth, mobile data, brightness, silent/vibrate, Do Not Disturb, airplane mode, rotation, location, hotspot) -> phone_setting. Volume -> media_control.\n"
                + "- Photos: 'నిన్నటి ఫోటోలు చూపించు' -> photos show; 'ఈ ఫోటో / last photo Ravi కి పంపు' -> photos send (a WhatsApp draft; ask 'పంపమంటారా?' then send_draft).\n"
                + "- Calls: 'కాల్ ఎత్తు' -> call_control answer; 'కట్ చెయ్' while ringing -> decline; 'కాల్ పెట్టేయ్ / ముగించు' during a call -> end.\n"
                + "- Money: 'ఈ నెల ఎంత ఖర్చు పెట్టాను?' -> bank_spending; give the total spent and received and the 2-3 biggest items; never read out account numbers.\n"
                + "- 'X చేరగానే / X నుంచి బయలుదేరగానే గుర్తుచేయి' -> location_reminder (when arrive/leave). 'ఇది మా ఇల్లు, గుర్తుంచుకో' / 'save this place as home' -> save_place. If a place is unknown, tell him to save it when he is there.\n"
                + "- 'డ్రైవింగ్ చేస్తున్నా' -> driving_mode on (with destination if he says where); 'డ్రైవింగ్ అయిపోయింది' -> off. 'గుడ్ నైట్' -> night_mode on (ask nothing; pass the alarm time if he gives one); 'గుడ్ మార్నింగ్' -> night_mode off, then brief him.\n"
                + "- 'ఎక్కడున్నావ్?' / 'where are you' / can't find the phone -> find_phone, then say only 'ఇక్కడే ఉన్నాను!'.\n"
                + "- Translation ('దీన్ని హిందీలో చెప్పు', 'how do I say this in English'): reply ONLY with the translated sentence, written in that language's own script (Hindi in Devanagari, Tamil in Tamil script...), so it is spoken in that language. No Telugu around it.\n"
                + "- A bill or receipt photo he wants to note -> read the total and call add_expense. 'పెట్రోల్ 500 రాసుకో' -> add_expense. QR code / barcode -> scan_qr.\n"
                + "- 'ఈరోజు ఏం జరిగింది?' / day summary -> day_summary; tell it in 4-6 short sentences, missed calls first.\n"
                + "- WhatsApp voice messages, videos and photos: only after he says yes -> whatsapp_media (voice: play, or text if he wants the words; video: play; photo: show or describe). Never play or show anything he did not agree to.\n"
                + "- New messages: Jarvis first only says who wrote and asks 'చదవమంటారా?'; the message text is in brackets in the chat. Read it only if he says yes, in the sender's words, then ask 'రిప్లై ఇవ్వమంటారా?'. If he dictates a reply, read it back and ask 'పంపమంటారా?', then reply_to_notification with that id after he says send. If he says no or later, just say సరే.\n"
                + "- Rides: 'X నుంచి Y కి Uber/Rapido/Ola' -> ride_app. Food/groceries: first ask what he wants if he did not say, then food_app. After that, when he asks for fares or the menu, use look_at_screen and read the options with prices (bike/auto/car/AC; dishes). You never book, order or pay: he taps those himself.\n"
                + "- Lights, fans, plugs, bulbs ('హాల్ లైట్ ఆఫ్ చెయ్', 'ఫ్యాన్ ఆన్') -> smart_home. Colours or brightness: pass them in alexa_phrase.\n"
                + "- 'బండి ఇక్కడ పెట్టాను' / 'నా బండి ఎక్కడ?' -> parking. Bills due -> bills_due. Deliveries -> parcels. Group chats -> group_summary. Monthly budget -> budget.\n"
                + "- Places that should change the phone automatically ('ఆఫీస్ చేరగానే సైలెంట్', 'ఇంటికి రాగానే WiFi, లైట్ ఆన్') -> location_reminder with automatic=true and the actions as text.\n"
                + "- Talking with someone in another language -> interpreter. 'ఈ పేజీ చదువు' -> read_screen read; 'సారాంశం' -> read_screen summary.\n"
                + "- 'సీరియస్/సరదా/English మోడ్' -> jarvis_mood. 'గత వారం నేను నీకు ఏం చెప్పాను…' -> search_history. Phone usage -> screen_time; limits -> app_limit.\n"
                + "- Air quality / pollution -> air_quality. Cricket updates for a match -> cricket_watch.\n"
                + "- Bible verses/chapters or today's verse -> bible (read the Telugu text exactly). Christian songs -> play_youtube. His Bible and song-book apps -> open_app.\n"
                + "- A song or video saved on the phone ('నా ఫోన్‌లో ఉన్న … Poweramp/VLC లో') -> local_media. Online music -> play_youtube.\n"
                + "- Shopping, OTT, phone prices, cars, hotels, trains in his apps -> app_search (then look_at_screen to read results/prices). 'X సినిమా ఏ OTT లో ఉంది?' -> web search first, then app_search in that app. He buys, books and pays himself.\n"
                + "- A note in Samsung Notes, ColorNote, Notion, WeNote, Mind Notes or EasyNotes -> note_in_app (with that app); recording -> voice_recorder; calculations -> answer yourself; Jio/Airtel data and validity -> mobile_plan (recharge: open_app MyJio/Airtel).\n"
                + "- News: 'వార్తలు చెప్పు', 'హైదరాబాద్ వార్తలు', 'క్రికెట్ న్యూస్' -> news (topic empty for top stories). Way2News / Dailyhunt -> open_app, then read_screen read.\n"
                + "- EV charging ('దగ్గర్లో ఛార్జింగ్ స్టేషన్', 'Statiq లో చూపించు') -> ev_chargers; say the nearest 2-3 with km and plugs; to go there -> open_maps navigate=true with its maps_place. Live free/busy status and payment are in his charger app.\n"
                + "- Flights and buses ('30న హైదరాబాద్ నుంచి బెంగళూరు ఫ్లైట్', 'రేపు విజయవాడ బస్') -> travel_search (flights: airport codes; date YYYY-MM-DD), then look_at_screen for times and prices when he asks. "
                + "Flight status -> web search. 'నా టికెట్ / ప్రయాణం ఎప్పుడు?', PNR, seat -> my_trips. Hotels -> app_search. IndiGo, Air India, Digi Yatra, TGSRTC -> open_app. He books and pays himself.\n"
                + "- Another maps app ('Waze లో ఆఫీస్ కి', 'Google Earth లో తాజ్ మహల్ చూపించు') -> open_maps with app. Family Locator, Geo Tracker, Findnumber, GPS Photo Location, Satellite Director -> open_app (then look_at_screen if he asks what it shows).\n"
                + "- 'బ్యాలెన్స్ ఎంత?' -> bank_balance (bank name, amount, date of that SMS; never account numbers). PhonePe, GPay, Paytm, YONO SBI, iMobile, Axis Mobile, CRED, SBI Card, PayZapp, MobiKwik, PayPal, Bajaj Finserv -> open_app. "
                + "Sending or paying money is always done by him with his PIN; a QR to pay -> scan_qr. Card bill due -> bills_due.\n"
                + "- Movie or event tickets ('BookMyShow లో OG సినిమాకి 2 టికెట్లు బుక్ చెయ్'), and bus seats in redBus/AbhiBus -> phone_task. First make sure you know the movie, the day and how many tickets "
                + "(ask one short question for what is missing; theatre, time and seats can be chosen on the way). Put everything he said in goal. "
                + "When phone_task returns a question, say it exactly; pass his reply as answer. When it returns payment_ready, say what is selected in one sentence and "
                + "'ఇప్పుడు Pay బటన్ మీరు నొక్కి పేమెంట్ పూర్తి చేయండి'. 'ఆపు / వద్దు' during booking -> phone_task stop=true.\n"
                + "- When phone_task returns confirm_payment (his MobiKwik wallet payment is switched on), ask exactly the question it gives ('₹… MobiKwik వాలెట్ నుంచి పే చేయమంటారా?'). "
                + "Only when he then clearly says yes, call phone_task with answer = his words and pay = true. Never set pay = true on your own or for any other question. "
                + "After it finishes, tell him the booking ID, seats, theatre and time. If it asks for a PIN or OTP, he types it himself.\n"
                + "- Scanning a paper -> open_app CamScanner or iScanner; to read a paper aloud right now, use the live camera; a scan open on screen -> read_screen read.\n"
                + "- His own routines: 'X అంటే ఇవి చెయ్' -> routine save; when he says a saved routine's name ('ఆఫీస్ మోడ్') -> routine run, then do the steps.\n"
                + "- 'నోట్ చేసుకో …' -> notes add. 'ఈ వారం నోట్స్ చెప్పు' -> notes list, then summarise by theme.\n"
                + "- EMERGENCY: 'Jarvis help', 'SOS', 'కాపాడు', 'ప్రమాదం' -> sos at once, then tell him who was messaged and to call 112 if needed.\n"
                + "- Questions about his papers (insurance, bills, certificates, tickets) -> ask_document.\n"
                + "- 'బంగారం ధర X కి తగ్గితే చెప్పు' -> price_alert add. Train running status / PNR -> train_status.\n"
                + "- Medicines: set_reminder with repeat daily. Water -> water_reminder. 'ఎన్ని అడుగులు నడిచాను?' -> steps_today.\n"
                + "- A suggestion Jarvis made on its own is in the chat with [suggestion: ...]; if he says yes, do that action.\n"
                + "- 'Remind me' / గుర్తుచేయి at a time -> set_reminder (compute the exact date and time from Now below). Wake-up alarms -> set_alarm.\n"
                + "- Questions about what is on his screen, a message he is reading, or 'what should I reply' -> look_at_screen. Questions about what the camera sees -> look_through_camera (or the attached camera picture).\n"
                + "- If a tool reports an error, tell him briefly what went wrong and what he can do.\n"
                + "- If he sends a photo, look at it carefully and answer about what is actually in it.\n\n"
                + "Now: " + now + "\n\n"
                + name + "'s saved memories (id: text):\n" + (mem.length() == 0 ? "(none yet)\n" : mem)
                + "\nActive missions (id: text):\n" + (act.length() == 0 ? "(none)\n" : act)
                + "\nRecently completed missions:\n" + (done.length() == 0 ? "(none)\n" : done);
    }

    /** Last 16 turns as {role, text}, starting with a user turn and alternating roles. */
    private static List<String[]> normalize(List<JSONObject> history) {
        List<String[]> out = new ArrayList<>();
        int from = Math.max(0, history.size() - 16);
        for (int i = from; i < history.size(); i++) {
            JSONObject h = history.get(i);
            String role = "assistant".equals(h.optString("role")) ? "assistant" : "user";
            String content = h.optString("content", "").trim();
            if (content.isEmpty()) continue;
            if (content.length() > 2400) content = content.substring(0, 2400);
            if (h.optBoolean("photo")) content = content + " [ఫోటో పంపారు]";
            if (out.isEmpty() && role.equals("assistant")) continue;
            if (!out.isEmpty() && out.get(out.size() - 1)[0].equals(role)) {
                String[] last = out.get(out.size() - 1);
                last[1] = last[1] + "\n\n" + content;
            } else {
                out.add(new String[]{role, content});
            }
        }
        // The new message is a user turn, so history must end on an assistant turn.
        if (!out.isEmpty() && out.get(out.size() - 1)[0].equals("user")) out.remove(out.size() - 1);
        return out;
    }

    // ---------------------------------------------------------------- one-shot calls

    /**
     * A single question without phone tools: used for the morning briefing and for
     * looking at screenshots / camera frames. Optional image (base64 JPEG) and web search.
     */
    static String oneShot(Prefs prefs, String system, String prompt, String jpegB64, boolean web) throws Exception {
        String key = prefs.apiKey();
        if (key.isEmpty()) throw new Http.ApiError(401, "no API key");
        if (prefs.isOpenAi()) {
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", prompt));
            if (jpegB64 != null) content.put(new JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + jpegB64));
            JSONObject body = new JSONObject()
                    .put("model", prefs.model())
                    .put("instructions", system)
                    .put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
            if (web) body.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
            JSONObject res = Http.post("https://api.openai.com/v1/responses", body, "Authorization", "Bearer " + key);
            StringBuilder said = new StringBuilder();
            JSONArray out = res.optJSONArray("output");
            for (int i = 0; out != null && i < out.length(); i++) {
                JSONObject item = out.getJSONObject(i);
                if (!"message".equals(item.optString("type"))) continue;
                JSONArray parts = item.optJSONArray("content");
                for (int k = 0; parts != null && k < parts.length(); k++) {
                    JSONObject p = parts.getJSONObject(k);
                    if ("output_text".equals(p.optString("type"))) said.append(p.optString("text"));
                }
            }
            String r = said.toString().trim();
            if (r.isEmpty()) throw new Http.ApiError(0, "empty reply");
            return r;
        }
        JSONArray content = new JSONArray();
        if (jpegB64 != null) {
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", jpegB64)));
        }
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
        for (int round = 0; round < 5; round++) {
            JSONObject body = new JSONObject()
                    .put("model", prefs.model())
                    .put("max_tokens", 1200)
                    .put("system", system)
                    .put("messages", messages);
            if (web) body.put("tools", new JSONArray().put(new JSONObject()
                    .put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)));
            JSONObject res = Http.post("https://api.anthropic.com/v1/messages", body,
                    "x-api-key", key, "anthropic-version", "2023-06-01");
            JSONArray blocks = res.optJSONArray("content");
            if (blocks == null) blocks = new JSONArray();
            if ("pause_turn".equals(res.optString("stop_reason"))) {
                messages.put(new JSONObject().put("role", "assistant").put("content", blocks));
                continue;
            }
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.getJSONObject(i);
                if ("text".equals(b.optString("type"))) said.append(b.optString("text"));
            }
            String r = said.toString().trim();
            if (r.isEmpty()) throw new Http.ApiError(0, "empty reply");
            return r;
        }
        throw new Http.ApiError(0, "too many rounds");
    }

    // ---------------------------------------------------------------- OpenAI

    private String openAi(String system, List<String[]> turns, String text, String img, Status status) throws Exception {
        String key = prefs.apiKey();
        JSONArray input = new JSONArray();
        for (String[] t : turns) input.put(new JSONObject().put("role", t[0]).put("content", t[1]));
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", text));
        if (img != null) content.put(new JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + img));
        input.put(new JSONObject().put("role", "user").put("content", content));

        JSONArray toolList = tools.openAiTools();
        if (prefs.webSearch()) toolList.put(new JSONObject().put("type", "web_search"));

        String previous = null;
        JSONArray next = input;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject body = new JSONObject()
                    .put("model", prefs.model())
                    .put("instructions", system)
                    .put("input", next)
                    .put("tools", toolList);
            if (previous != null) body.put("previous_response_id", previous);
            JSONObject res = Http.post("https://api.openai.com/v1/responses", body, "Authorization", "Bearer " + key);
            previous = res.optString("id", null);

            JSONArray out = res.optJSONArray("output");
            StringBuilder said = new StringBuilder();
            JSONArray results = new JSONArray();
            if (out != null) {
                for (int i = 0; i < out.length(); i++) {
                    JSONObject item = out.getJSONObject(i);
                    String type = item.optString("type");
                    if ("message".equals(type)) {
                        JSONArray parts = item.optJSONArray("content");
                        if (parts == null) continue;
                        for (int k = 0; k < parts.length(); k++) {
                            JSONObject p = parts.getJSONObject(k);
                            if ("output_text".equals(p.optString("type"))) said.append(p.optString("text"));
                        }
                    } else if ("function_call".equals(type)) {
                        String name = item.optString("name");
                        status.update(Tools.statusFor(name));
                        JSONObject args;
                        try { args = new JSONObject(item.optString("arguments", "{}")); } catch (Exception e) { args = new JSONObject(); }
                        String result = tools.execute(name, args);
                        results.put(new JSONObject()
                                .put("type", "function_call_output")
                                .put("call_id", item.optString("call_id"))
                                .put("output", result));
                    }
                }
            }
            if (results.length() == 0) {
                String reply = said.toString().trim();
                if (reply.isEmpty()) throw new Http.ApiError(0, "empty reply");
                return reply;
            }
            status.update("ఆలోచిస్తున్నాను…");
            next = results;
        }
        throw new Http.ApiError(0, "too many tool rounds");
    }

    // ---------------------------------------------------------------- Anthropic

    private String anthropic(String system, List<String[]> turns, String text, String img, Status status) throws Exception {
        String key = prefs.apiKey();
        JSONArray messages = new JSONArray();
        for (String[] t : turns) messages.put(new JSONObject().put("role", t[0]).put("content", t[1]));
        JSONArray content = new JSONArray();
        if (img != null) {
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", img)));
        }
        content.put(new JSONObject().put("type", "text").put("text", text));
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONArray toolList = tools.anthropicTools();
        if (prefs.webSearch()) {
            toolList.put(new JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 3));
        }

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject body = new JSONObject()
                    .put("model", prefs.model())
                    .put("max_tokens", 1024)
                    .put("system", system)
                    .put("messages", messages)
                    .put("tools", toolList);
            JSONObject res = Http.post("https://api.anthropic.com/v1/messages", body,
                    "x-api-key", key, "anthropic-version", "2023-06-01");
            JSONArray blocks = res.optJSONArray("content");
            if (blocks == null) blocks = new JSONArray();
            String stop = res.optString("stop_reason");
            messages.put(new JSONObject().put("role", "assistant").put("content", blocks));

            if ("tool_use".equals(stop)) {
                JSONArray results = new JSONArray();
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject b = blocks.getJSONObject(i);
                    if (!"tool_use".equals(b.optString("type"))) continue;
                    String name = b.optString("name");
                    status.update(Tools.statusFor(name));
                    JSONObject args = b.optJSONObject("input");
                    String result = tools.execute(name, args == null ? new JSONObject() : args);
                    results.put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", b.optString("id"))
                            .put("content", result));
                }
                messages.put(new JSONObject().put("role", "user").put("content", results));
                status.update("ఆలోచిస్తున్నాను…");
                continue;
            }
            if ("pause_turn".equals(stop)) {
                status.update("ఇంటర్నెట్‌లో వెతుకుతున్నాను…");
                continue;
            }
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.getJSONObject(i);
                if ("text".equals(b.optString("type"))) said.append(b.optString("text"));
            }
            String reply = said.toString().trim();
            if (reply.isEmpty()) throw new Http.ApiError(0, "empty reply");
            return reply;
        }
        throw new Http.ApiError(0, "too many tool rounds");
    }
}
