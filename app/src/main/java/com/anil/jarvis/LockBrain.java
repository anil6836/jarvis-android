package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Jarvis camera's "lock on": the AI finds the one person or thing Anil describes ("ఆ ఎర్ర చొక్కా అబ్బాయి"), gives
 * its box, a short Telugu name and what it is / they are doing, with a few details for the card. People: only what can
 * be seen (doing, wearing, where); never who they are. The prompts and the reading of the answer (no Android here).
 */
final class LockBrain {
    private LockBrain() {}

    static final class Found {
        boolean found;
        float[] box;          // 0..1 of the picture (x0, y0, x1, y1), null when not found
        String name = "", say = "", kind = "thing";
        final List<String> details = new ArrayList<>();
    }

    static String system() {
        return "You are JARVIS, the eyes of Anil's camera (Telangana, India). Answer only in simple, natural Telugu. "
                + "Reply with ONLY this JSON, nothing else: "
                + "{\"found\":true,\"box\":[x0,y0,x1,y1],\"name\":\"short Telugu name, e.g. ఎర్ర చొక్కా అబ్బాయి / జెయింట్ వీల్ / తెల్ల కారు\","
                + "\"kind\":\"person|vehicle|animal|place|thing\",\"say\":\"1-2 short Telugu sentences: what it is, or what they are doing right now\","
                + "\"details\":[\"up to 4 short Telugu lines with the useful details\"]}. "
                + "box: integers 0-1000 of the picture's width (x, left to right) and height (y, top to bottom), tight around it. "
                + "PEOPLE: say only what can be seen (what they are doing, holding, wearing, where they are, how they seem: happy, busy); "
                + "never say who they are, never guess a name, and nothing about religion, caste, health or anything private. "
                + "A child: describe kindly, nothing more. If it is not in the picture: found=false, no box, and in say where he might point the camera.";
    }

    /** "ఆ ఎర్ర చొక్కా అబ్బాయిని జూమ్ చేయి, ఏం చేస్తున్నాడు?" on the wide picture. */
    static String findPrompt(String asked) {
        return "Find in this picture what Anil means and tell about it: \"" + asked.trim() + "\". If several match, take the clearest / nearest one.";
    }

    /** The close-up after the zoom: the same one, nearer now. */
    static String closePrompt(String name, String asked) {
        return "This is a close-up of " + (name.isEmpty() ? "what Anil asked about" : name) + " (his words: \"" + asked.trim() + "\"). "
                + "It is the one in the middle. Tell what it is / they are doing now and the useful details he may want; box of it in this picture.";
    }

    /** A ring tapped: the thing inside this box (0..1). */
    static String boxPrompt(float[] b, String guess) {
        return String.format(Locale.US, "Tell Anil about the thing inside the box x %d-%d, y %d-%d (0-1000 of the picture)%s: what it is, what it is doing if alive, "
                + "and the useful details (for a vehicle: kind, make if visible; a place / building: what it is; an animal: kind and how it seems). Box of it.",
                Math.round(b[0] * 1000), Math.round(b[2] * 1000), Math.round(b[1] * 1000), Math.round(b[3] * 1000),
                guess == null || guess.isEmpty() ? "" : " (the phone thinks it is: " + guess + ")");
    }

    /** The AI's reply read (its JSON anywhere in the reply; plain words are taken as what to say). */
    static Found parse(String reply) {
        Found f = new Found();
        String s = reply == null ? "" : reply.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        JSONObject o = null;
        if (a >= 0 && b > a) { try { o = new JSONObject(s.substring(a, b + 1)); } catch (Exception ignored) {} }
        if (o == null) {
            f.say = s.replaceAll("```[a-z]*", "").trim();
            return f;
        }
        f.found = o.optBoolean("found", o.has("box"));
        f.name = o.optString("name", "").trim();
        f.say = o.optString("say", "").trim();
        f.kind = o.optString("kind", "thing").trim();
        JSONArray d = o.optJSONArray("details");
        if (d != null) for (int i = 0; i < d.length() && f.details.size() < 4; i++) { String x = d.optString(i, "").trim(); if (!x.isEmpty()) f.details.add(x); }
        JSONArray bx = o.optJSONArray("box");
        if (bx != null && bx.length() == 4) {
            float[] v = new float[4];
            boolean ok = true;
            for (int i = 0; i < 4; i++) { v[i] = (float) bx.optDouble(i, -1); if (v[i] < 0) ok = false; }
            if (ok) {
                float scale = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])) > 1.5f ? 1000f : 1f; // (0-1000, or already 0-1)
                for (int i = 0; i < 4; i++) v[i] = Math.max(0f, Math.min(1f, v[i] / scale));
                if (v[2] < v[0]) { float t = v[0]; v[0] = v[2]; v[2] = t; }
                if (v[3] < v[1]) { float t = v[1]; v[1] = v[3]; v[3] = t; }
                if (v[2] - v[0] > 0.005f && v[3] - v[1] > 0.005f) f.box = v;
            }
        }
        if (f.box == null) f.found = false;
        return f;
    }

    /** The card's lines and what is said: the name, then the sentence, then the details. */
    static String spoken(Found f) {
        StringBuilder b = new StringBuilder(f.say);
        for (String d : f.details) b.append(b.length() == 0 ? "" : " ").append(d.endsWith(".") ? d : d + ".");
        return b.toString().trim();
    }

    /** He asks to lock on / zoom on someone or something (more than the bare "జూమ్" command; never the zoom itself). */
    static boolean isLockAsk(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!t.matches(".*(జూమ్|zoom|దగ్గరగా చూపించు|దగ్గరగా చూపు|ఫోకస్|focus|లాక్ చేయి|లాక్ చెయ్|lock on|గురి పెట్టు|గురిపెట్టు).*")) return false;
        // out / less / more / a number / a question about the camera's zoom / stop: the zoom itself, not a lock-on
        if (t.matches(".*(తగ్గించు|తగ్గించి|అవుట్|zoom out|పెంచు|\\d+\\s*(x|×|ఎక్స్|రెట్లు)|ఎంత|ఎన్ని|how much|optical|ఆప్టికల్|ఆపు|వదులు|unlock|రెట్లు|times|టైమ్స్).*")) return false;
        String rest = t.replaceAll("జూమ్ చేయి|జూమ్ చెయ్|జూమ్ చేసి|జూమ్|zoom in|zoom|దగ్గరగా చూపించు|దగ్గరగా చూపు|ఫోకస్ చేయి|ఫోకస్|focus|లాక్ చేయి|లాక్ చెయ్|lock on|గురి పెట్టు|గురిపెట్టు|జార్విస్|jarvis|ప్లీజ్|please", " ")
                .replaceAll("[\\p{Punct}।]", " ").replaceAll("\\s+", " ").trim();
        int named = 0; // what is left must name someone / something, not only small words
        for (String w : rest.split(" ")) {
            if (w.length() >= 2 && !w.matches("ఇంకా|మళ్ళీ|మరింత|ఇంకొంచెం|కొంచెం|చేయి|చెయ్|చెయ్యి|చేసి|దాన్ని|దీన్ని|దాని|దీని|ఇక్కడ|అక్కడ|మీద|పైన|in|on|the|to|at|that|this|it|an|more|again|bit|little")) named++;
        }
        return named >= 1;
    }

    /** "జూమ్ ఆపు", "లాక్ వదులు": back to the wide view. */
    static boolean isUnlock(String t) {
        return t.matches(".*(లాక్ వదులు|లాక్ తీసేయి|లాక్ ఆపు|జూమ్ ఆపు|జూమ్ వదులు|unlock|ఫోకస్ ఆపు|వదిలేయ్|వదిలెయ్).*");
    }
}
