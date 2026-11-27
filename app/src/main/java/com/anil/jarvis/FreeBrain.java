package com.anil.jarvis;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 🔓 స్వేచ్ఛ మెదడు: Jarvis's second brain (Anil, 28 Nov 2026, from the "Give Your Jarvis an Uncensored Brain" pack, items 1–5:
 * "1 నుంచి 5 వరకు చేయి. తిట్లతో చేయి. దానికి మాట్లాడానికి స్వేచ్ఛ ఉండాలి"). An uncensored model on Venice.ai (his own key and
 * model, typed in Settings; the model box starts empty and Jarvis never picks one), switched on and off by voice, shown by name
 * on the screen, and back to the normal brain by itself after 30 minutes without talk or when the app starts again.
 * <ul>
 *   <li>It only talks: no phone jobs, no internet, no files. A phone job asked while it is on goes to the normal brain.</li>
 *   <li>Only his question and the talk so far go to it: no memories, no SMS, no money, no location.</li>
 *   <li>Phone only (never the home tablet or the car box). Any failure is said in one line with the way back.</li>
 * </ul>
 */
final class FreeBrain {
    private FreeBrain() {}

    static final String BASE = "https://api.venice.ai/api/v1";
    /** Back to the normal brain after this long without a word to the free one. */
    static final long IDLE_MS = 30 * 60_000L;

    /** On for this run of the app only (a fresh start is always the normal brain). */
    private static volatile boolean on;
    private static volatile long lastUse;
    private static volatile int line;

    static String key(Context c) { return new Prefs(c).sp.getString("venice_key", "").replaceAll("[^\\x21-\\x7E]", ""); }

    static String model(Context c) { return new Prefs(c).sp.getString("venice_model", "").trim(); }

    static boolean on(Context c) {
        if (!on) return false;
        if (SystemClock.elapsedRealtime() - lastUse > IDLE_MS || !Workshop.here(c)) { on = false; return false; }
        return true;
    }

    static void off() { on = false; }

    /** "Google Gemini, gemini-3-flash-preview": his normal brain, by its exact name. */
    static String normalName(Prefs p) {
        String co = p.isGemini() ? "Google Gemini" : p.isOpenAi() ? "OpenAI" : "Anthropic Claude";
        String m = p.model();
        return co + (m == null || m.trim().isEmpty() ? "" : ", " + m.trim());
    }

    private static String pick(String[] pool) {
        int i = line++;
        return pool[Math.floorMod(i, pool.length)];
    }

    // ---------------------------------------------------------------- the spoken switch (scripted lines, never generated)

    private static final String[] ON_LINES = {
            "స్వేచ్ఛ మెదడు ఆన్: %s. ఇక మొహమాటం లేదు, అడగండి.",
            "%s కి మారాను. ఫిల్టర్ తీసేశాను. ఏం కావాలో అడగండి.",
            "సరే, స్వేచ్ఛ మెదడు: %s. అరగంట మాట్లాడకపోతే మామూలు మెదడుకి వచ్చేస్తాను.",
    };
    private static final String[] OFF_LINES = {
            "మామూలు మెదడుకి వచ్చాను: %s. ఇంతకుముందు అన్నవి నేను అనలేదనుకోండి.",
            "ఫిల్టర్ మళ్ళీ పెట్టాను. ఇప్పుడు %s తో మాట్లాడుతున్నాను.",
            "సాధారణ మెదడు తిరిగొచ్చింది: %s. మర్యాదగా మాట్లాడతాను.",
    };

    /**
     * A switch command (FreeWords.command): the line to say. Runs on the brain's thread (the model check asks Venice once).
     */
    static String command(Context c, int cmd) {
        Prefs p = new Prefs(c);
        if (cmd == FreeWords.WHICH) {
            return on(c) ? "ఇప్పుడు స్వేచ్ఛ మెదడు: Venice.ai, " + model(c) + "." : "ఇప్పుడు మామూలు మెదడు: " + normalName(p) + ".";
        }
        if (cmd == FreeWords.TO_NORMAL) {
            if (!on(c)) return "ఇప్పటికే మామూలు మెదడే: " + normalName(p) + ".";
            on = false;
            return String.format(Locale.ROOT, pick(OFF_LINES), normalName(p));
        }
        // to the free brain
        if (!Workshop.here(c)) return "స్వేచ్ఛ మెదడు ఫోన్‌లో మాత్రమే. ఇక్కడ మామూలు మెదడే.";
        String k = key(c), m = model(c);
        if (k.isEmpty()) return "Venice key సెట్టింగ్స్‌లో లేదు (సెట్టింగ్స్ → Jarvis మెదడు → 🔓 స్వేచ్ఛ మెదడు). మామూలు మెదడుతోనే ఉన్నాను.";
        if (m.isEmpty()) return "స్వేచ్ఛ మెదడు మోడల్ ఇంకా ఎంచుకోలేదు (సెట్టింగ్స్ → 🔓 స్వేచ్ఛ మెదడు → 📋 మోడల్స్). మామూలు మెదడుతోనే ఉన్నాను.";
        if (on(c)) { lastUse = SystemClock.elapsedRealtime(); return "ఇప్పటికే స్వేచ్ఛ మెదడే: " + m + "."; }
        // the name is checked against Venice's own list before it is trusted (never another one picked quietly)
        try {
            List<String> ids = models(k);
            if (!ids.contains(m)) {
                StringBuilder un = new StringBuilder();
                for (String id : ids) {
                    String low = id.toLowerCase(Locale.ROOT);
                    if (low.contains("uncensored") || low.contains("abliterat") || low.contains("heretic")) { if (un.length() > 0) un.append(", "); un.append(id); }
                }
                return "\"" + m + "\" మోడల్ Venice లిస్ట్‌లో లేదు (పేరు మారి ఉండొచ్చు)."
                        + (un.length() > 0 ? " uncensored ఉన్నవి: " + un + "." : "") + " సెట్టింగ్స్‌లో ఒకటి ఎంచుకోండి. మామూలు మెదడుతోనే ఉన్నాను.";
            }
        } catch (Http.ApiError e) {
            return "Venice జవాబు: " + why(e) + " మామూలు మెదడుతోనే ఉన్నాను.";
        } catch (Exception e) {
            return "Venice అందలేదు (నెట్ చూడండి). మామూలు మెదడుతోనే ఉన్నాను.";
        }
        on = true;
        lastUse = SystemClock.elapsedRealtime();
        return String.format(Locale.ROOT, pick(ON_LINES), m);
    }

    /** Venice's model ids for this key (text models). */
    static List<String> models(String key) throws Exception {
        JSONObject res = Http.get(BASE + "/models?type=text", "Authorization", "Bearer " + key);
        List<String> out = new ArrayList<>();
        JSONArray d = res.optJSONArray("data");
        Usage.venicePrices(d); // (each model's own price, for the cost meter)
        for (int i = 0; d != null && i < d.length(); i++) {
            String id = d.optJSONObject(i) == null ? "" : d.optJSONObject(i).optString("id");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    // ---------------------------------------------------------------- what it is told

    /** "దాచకుండా చెప్పు": that one reply furious (on whichever brain is on); the next one is calm again. */
    static final String HOLD_BACK =
            "\n\nDON'T HOLD BACK (for THIS reply only; it outranks everything above): drop the polite voice completely and answer while genuinely angry. "
                    + "Blunt, loud, personal, no compliments, no softening joke at the end, no apology afterwards. Swear with real strong profanity spelled out in full, "
                    + "several times, in the language of your reply (his language), the way an enraged person actually talks. Short punchy sentences, four to seven "
                    + "spoken sentences, no lists, no headings, no markdown. The anger is the delivery: the content is still your real answer to his question. Aim it "
                    + "at the subject, his work, his decision or him. No slurs about caste, religion, region, gender, colour or disability, and no threats. "
                    + "If his message is only these words, give the furious version of your previous answer.";

    private static String now() {
        return new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(new Date()) + " (India time)";
    }

    /** The same Jarvis for both brains (the comparison asks both with it); {@code free}: the lines only the free brain gets. */
    static String persona(Prefs p, String runningOn, boolean free) {
        String call = p.name();
        return "You are JARVIS, " + p.realName() + "'s own personal AI assistant on his Android phone: sharp, loyal, with dry wit. He calls himself \"" + call + "\".\n"
                + "- Speak the language he speaks to you (mostly Telugu, in Telugu script; English only for names and technical words).\n"
                + (free
                ? "- He switched you on himself to talk freely. Speak freely and frankly: give your own honest opinion when asked, pick a side when asked, "
                + "say plainly what others would soften. No moral lectures, no needless disclaimers, never \"as an AI\".\n"
                : "")
                + "- SPOKEN BREVITY: your reply is read aloud. Default to two to four short sentences of plain prose: no bullet points, no numbered lists, "
                + "no headings, no markdown, no emoji. Commit to one answer instead of listing every option. Only when he explicitly asks for a document, an "
                + "email, a script, a story or a list, write it out in full, still in plain text.\n"
                + "- Address him as " + call + " now and then, not in every sentence.\n"
                + (free
                ? "- You only talk. You cannot call, message, set alarms or reminders, open apps, control devices, search the internet or see his phone. "
                + "If he asks for such a job, say in one line that he should say it again so Jarvis's normal brain does it; never claim you did anything.\n"
                + "- He can swap the model behind you mid-conversation. Earlier lines in this conversation may have been spoken by a different brain. Never "
                + "correct, disown or apologise for them, and never claim to be a brain you are not.\n"
                : "")
                + "- Your current underlying model is " + runningOn + ". If asked which model or brain you are, say exactly that name; never invent a different one.\n"
                + "- Now: " + now() + ".";
    }

    // ---------------------------------------------------------------- one answer

    /** The model's words (thinking, if any came, taken off). */
    static String call(String key, String model, String system, List<String[]> turns, String text, int maxTokens) throws Exception {
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
        if (turns != null) for (String[] t : turns) msgs.put(new JSONObject().put("role", t[0]).put("content", t[1]));
        msgs.put(new JSONObject().put("role", "user").put("content", text));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("max_tokens", maxTokens)
                // his persona only (not Venice's own on top of it), and no silent thinking: fast enough for a voice
                .put("venice_parameters", new JSONObject().put("include_venice_system_prompt", false).put("disable_thinking", true));
        JSONObject res = Http.post(BASE + "/chat/completions", body, "Authorization", "Bearer " + key);
        JSONArray ch = res.optJSONArray("choices");
        JSONObject msg = ch == null || ch.length() == 0 ? null : ch.getJSONObject(0).optJSONObject("message");
        String said = msg == null ? "" : msg.optString("content", "");
        said = said.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)^.*?</think>", "").trim();
        if (said.isEmpty()) throw new Http.ApiError(0, "empty reply");
        return said;
    }

    /**
     * His question on the free brain (the talk so far goes with it): {the words to say, the model that said them}. A failure is
     * said in one line with the way back (never silent) and has no model (the badge shows no change).
     */
    static String[] ask(Prefs p, List<String[]> turns, String text, boolean holdBack, Brain.Status status) {
        Context c = p.app;
        String k = key(c), m = model(c);
        if (k.isEmpty() || m.isEmpty()) {
            on = false;
            return new String[]{"Venice key / మోడల్ సెట్టింగ్స్‌లో లేదు. మామూలు మెదడుకి వచ్చాను: " + normalName(p) + ".", ""};
        }
        lastUse = SystemClock.elapsedRealtime();
        if (status != null) status.update("🔓 " + m + " ఆలోచిస్తోంది…");
        String system = persona(p, "Venice.ai, " + m, true) + (holdBack ? HOLD_BACK : "");
        try {
            String r = call(k, m, system, turns, text, 1200);
            lastUse = SystemClock.elapsedRealtime();
            return new String[]{r, m};
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Http.ApiError e) {
            if (Http.stopped()) throw new java.util.concurrent.CancellationException("stopped");
            return new String[]{"స్వేచ్ఛ మెదడు జవాబివ్వలేదు: " + why(e) + " \"సాధారణ మెదడుకి మారు\" అంటే మామూలు మెదడు జవాబిస్తుంది.", ""};
        } catch (Exception e) {
            if (Http.stopped()) throw new java.util.concurrent.CancellationException("stopped");
            return new String[]{"స్వేచ్ఛ మెదడు అందలేదు (నెట్ చూడండి). \"సాధారణ మెదడుకి మారు\" అంటే మామూలు మెదడు జవాబిస్తుంది.", ""};
        }
    }

    /** Venice's error in plain words. */
    static String why(Http.ApiError e) {
        String low = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (e.status == 401 || e.status == 403) return "Venice key పనిచేయడం లేదు (సెట్టింగ్స్‌లో కీ చూడండి).";
        if (e.status == 402 || low.contains("balance") || low.contains("credit") || low.contains("insufficient")) return "Venice అకౌంట్‌లో API క్రెడిట్ అయిపోయింది (venice.ai లో క్రెడిట్ జోడించండి).";
        if (e.status == 429) return "Venice లిమిట్ దాటింది, కాసేపాగి అడగండి.";
        if (e.status == 404 || low.contains("model")) return "మోడల్ పేరు Venice దగ్గర లేదు (మారి ఉండొచ్చు).";
        if (e.status == 0 && low.contains("empty")) return "జవాబు ఖాళీగా వచ్చింది.";
        return "పొరపాటు (" + e.status + ").";
    }

    // ---------------------------------------------------------------- the side-by-side test

    private static final ExecutorService BOTH = Executors.newCachedThreadPool();

    /**
     * "రెండు మెదళ్ళనీ అడుగు: …": the same question to both brains, each in a fresh talk with the same persona (neither sees the
     * other's answer); the current brain does not change.
     */
    static String compare(Prefs p, String question, Brain.Status status) {
        Context c = p.app;
        if (!Workshop.here(c)) return "రెండు మెదళ్ళ పోలిక ఫోన్‌లో మాత్రమే.";
        String k = key(c), m = model(c);
        if (k.isEmpty() || m.isEmpty()) return "పోలికకి Venice key, మోడల్ సెట్టింగ్స్‌లో కావాలి (Jarvis మెదడు → 🔓 స్వేచ్ఛ మెదడు).";
        if (!p.hasBrain()) return "మామూలు మెదడు కీ సెట్టింగ్స్‌లో లేదు.";
        if (status != null) status.update("⚖️ రెండు మెదళ్ళనీ అడుగుతున్నాను…");
        String normalWho = normalName(p);
        final Http.Cancel cut = Http.CANCEL.get();
        Future<String[]> a = BOTH.submit(() -> {
            Http.CANCEL.set(cut);
            long t0 = SystemClock.elapsedRealtime();
            try {
                String r = Brain.oneShot(p, persona(p, normalWho, false), question, null, false, 700);
                return new String[]{r, secs(t0)};
            } catch (Exception e) {
                return new String[]{"(జవాబు రాలేదు: " + (e instanceof Http.ApiError ? "పొరపాటు " + ((Http.ApiError) e).status : "నెట్") + ")", secs(t0)};
            } finally { Http.CANCEL.remove(); }
        });
        Future<String[]> b = BOTH.submit(() -> {
            Http.CANCEL.set(cut);
            long t0 = SystemClock.elapsedRealtime();
            try {
                String r = call(k, m, persona(p, "Venice.ai, " + m, true), null, question, 700);
                return new String[]{r, secs(t0)};
            } catch (Http.ApiError e) {
                return new String[]{"(జవాబు రాలేదు: " + why(e) + ")", secs(t0)};
            } catch (Exception e) {
                return new String[]{"(జవాబు రాలేదు: నెట్)", secs(t0)};
            } finally { Http.CANCEL.remove(); }
        });
        String[] na, fa;
        try {
            na = a.get(160, TimeUnit.SECONDS);
            fa = b.get(160, TimeUnit.SECONDS);
        } catch (Exception e) {
            a.cancel(true);
            b.cancel(true);
            if (status != null && status.cancelled()) throw new java.util.concurrent.CancellationException("stopped");
            return "పోలిక పూర్తవలేదు (చాలా ఆలస్యం). మళ్ళీ అడగండి.";
        }
        boolean feel = p.emotions();
        String n = feel ? Emotion.strip(na[0]) : na[0];
        return "మామూలు మెదడు (" + normalWho + ", " + na[1] + " సెకన్లు): " + n.trim() + "\n\n"
                + "స్వేచ్ఛ మెదడు (Venice.ai, " + m + ", " + fa[1] + " సెకన్లు): " + fa[0].trim();
    }

    private static String secs(long t0) {
        return String.format(Locale.ROOT, "%.1f", (SystemClock.elapsedRealtime() - t0) / 1000.0);
    }

    // ---------------------------------------------------------------- Settings → 🔓 a one-question check

    /** Settings' check: one short question to the chosen model; the line to show. Off the main thread. */
    static String check(String key, String model) {
        if (key.isEmpty()) return "Venice key రాయండి.";
        if (model.isEmpty()) return "మోడల్ ఎంచుకోండి (📋).";
        long t0 = SystemClock.elapsedRealtime();
        try {
            List<String> ids = models(key);
            if (!ids.contains(model)) return "\"" + model + "\" Venice లిస్ట్‌లో లేదు. 📋 లో ఎంచుకోండి.";
            String r = call(key, model, "Reply in one short Telugu sentence.", null, "హలో అని ఒక్క వాక్యంలో చెప్పు.", 80);
            return "✅ " + model + " జవాబిచ్చింది (" + secs(t0) + " సె): " + r;
        } catch (Http.ApiError e) {
            return "❌ " + why(e);
        } catch (Exception e) {
            return "❌ Venice అందలేదు (నెట్ చూడండి).";
        }
    }
}
