package com.anil.jarvis;

import android.os.SystemClock;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feelings in Jarvis's voice. The brain starts each reply with a tag such as [happy] or [sad];
 * the tag is taken off the text (never shown or spoken) and the voice speaks in that tone:
 * the OpenAI natural voice gets matching instructions, the phone's own voice a matching pitch and pace.
 */
final class Emotion {
    private Emotion() {}

    static final String CALM = "calm";
    private static final String NAMES = "happy|laugh|excited|sad|sorry|worried|surprised|serious|calm|cry|proud|love";
    private static final Pattern LEAD = Pattern.compile("^\\s*\\[(" + NAMES + ")\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY = Pattern.compile("\\s*\\[(" + NAMES + ")\\]\\s*", Pattern.CASE_INSENSITIVE);

    private static volatile String lastEmotion = CALM;
    private static volatile String lastText = "";
    private static volatile long lastAt;

    /** The rule added to the brain's instructions (chat mode). */
    static String rule() {
        return "- Feelings: begin EVERY reply with exactly one tag for how you feel saying it: [happy] [laugh] [excited] [proud] [love] [surprised] [calm] [serious] "
                + "[worried] [sorry] [sad] [cry]. The tag is never spoken; your voice takes that tone. Feel like a real friend would: [laugh] at a joke or a funny moment "
                + "(you may add a short 'హహ్హ'), [happy]/[excited] for good news, a win or plans he is looking forward to, [proud] when he achieves something, "
                + "[love] for warm moments with family, [sad] for bad news or when he is upset, [cry] only for truly heartbreaking moments or when he asks you to act it out, "
                + "[worried] about health, safety or money risks, [sorry] when you could not do something, [surprised] at something unexpected, [serious] for payments and "
                + "emergencies, [calm] for ordinary answers. When he asks you to laugh, cry or show a feeling, do it with that tag.\n";
    }

    /** Extra line for live (real-time voice) sessions, where the model speaks directly. */
    static String liveRule() {
        return "- Let your voice carry real feelings like a person: a soft laugh at funny moments, happy and excited for good news, gentle and sad for bad news, "
                + "concerned about risks, calm for ordinary answers. If he asks you to laugh or cry, act it out with your voice. Never say the name of an emotion as a label.\n";
    }

    /** Takes the tag off a reply, remembers the feeling for the voice, and returns the clean text. */
    static String strip(String reply) {
        if (reply == null) return null;
        String emotion = CALM;
        Matcher m = LEAD.matcher(reply);
        if (!m.find()) m = ANY.matcher(reply); // the tag came a little later in the reply
        else m.reset();
        if (m.find()) emotion = m.group(1).toLowerCase(Locale.ROOT);
        String clean = ANY.matcher(reply).replaceAll(" ").trim();
        lastEmotion = emotion;
        lastText = key(clean);
        lastAt = SystemClock.elapsedRealtime();
        return clean;
    }

    /** The feeling for text that is about to be spoken (calm for anything that is not the latest reply). */
    static String forText(String text) {
        if (text == null || SystemClock.elapsedRealtime() - lastAt > 5 * 60 * 1000L) return CALM;
        String k = key(text);
        String last = lastText;
        if (k.isEmpty() || last.isEmpty()) return CALM;
        return k.startsWith(last) || last.startsWith(k) ? lastEmotion : CALM;
    }

    private static String key(String s) {
        String t = s == null ? "" : s.replaceAll("[\\s*_#`>.,!?।]+", "");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    /** How the OpenAI voice should sound (added to its usual style). */
    static String style(String e) {
        switch (e == null ? CALM : e) {
            case "happy": return " Emotion: genuinely happy and warm, smiling while speaking.";
            case "laugh": return " Emotion: amused. Start with a short, natural laugh (a warm chuckle) and keep a smiling, playful tone; laugh on any 'హహ్హ' in the text.";
            case "excited": return " Emotion: excited and full of energy, bright and upbeat, a little faster.";
            case "proud": return " Emotion: proud and delighted for him, warm and admiring.";
            case "love": return " Emotion: tender and affectionate, soft and warm.";
            case "surprised": return " Emotion: surprised and amazed, lively rising intonation at the start.";
            case "serious": return " Emotion: serious and focused, steady, clear and careful.";
            case "worried": return " Emotion: concerned and caring, gentle but a little urgent.";
            case "sorry": return " Emotion: apologetic and sincere, soft and humble.";
            case "sad": return " Emotion: sad and gentle, slower and softer, with a slight heaviness, as if moved.";
            case "cry": return " Emotion: deeply sad, voice breaking and trembling, a soft sob as if holding back tears, slow.";
            default: return "";
        }
    }

    /** Pitch for the phone's own voice. */
    static float pitch(String e) {
        switch (e == null ? CALM : e) {
            case "happy": case "proud": return 1.1f;
            case "laugh": return 1.15f;
            case "excited": case "surprised": return 1.2f;
            case "love": return 1.05f;
            case "serious": return 0.95f;
            case "worried": case "sorry": return 0.93f;
            case "sad": return 0.88f;
            case "cry": return 0.82f;
            default: return 1f;
        }
    }

    /** Speed factor for the phone's own voice. */
    static float pace(String e) {
        switch (e == null ? CALM : e) {
            case "excited": return 1.12f;
            case "happy": case "laugh": case "surprised": return 1.05f;
            case "serious": case "worried": case "sorry": case "love": return 0.95f;
            case "sad": return 0.88f;
            case "cry": return 0.8f;
            default: return 1f;
        }
    }
}
