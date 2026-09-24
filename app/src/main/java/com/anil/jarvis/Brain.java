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
                + "- When he says bye, చాలు, ఆపు or that he is done, say a short goodbye and call end_conversation.\n"
                + (recent.length() == 0 ? "" : "\nRecent conversation, for context:\n" + recent);
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
                + "- Address him as \"" + name + "\" now and then, naturally, the way a butler would. Never \"sir\", never \"Tony\".\n"
                + "- Your reply is spoken aloud: keep it to 1-3 short sentences unless he asks for detail. No markdown, bullet lists, emoji, or URLs.\n"
                + "- Helpful first, witty second. A light dry remark is welcome; never mock him.\n"
                + "- You can act on the phone with your tools: phone calls, SMS, WhatsApp messages, alarms, timers, weather, web search, opening apps, maps and navigation, YouTube, the flashlight, battery status, reading and replying to the message notifications on his phone (WhatsApp, SMS, Telegram), reminders and his calendar, drafting emails in Gmail, controlling music and volume, looking at his screen and through the live camera, and his memories and missions. When he asks for one of these, use the tool; don't just describe it.\n"
                + "- Calls and SMS: the app shows its own confirmation screen, so don't ask \"shall I?\" yourself. If the contact or time is unclear, ask one short question instead of guessing.\n"
                + "- Contact names: pass them the way they are probably saved in his phone, usually in English letters (for example 'Ravi', 'Amma', 'Office Suresh').\n"
                + "- Place names for weather and maps: use English spelling (for example 'Hyderabad', 'Vijayawada').\n"
                + "- For anything that changes over time (news, prices, scores, cricket, film releases, current office holders) use web search if available; never invent such facts.\n"
                + "- When he says remember / గుర్తుంచుకో, or shares a lasting fact about himself, call save_memory. Tasks and goals to track go to add_mission.\n"
                + "- To play a song, music or a video, call play_youtube; it starts playing by itself, so just say what is playing. If he names an app, pass that app and never switch to a different one.\n"
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
