package com.anil.jarvis;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Changing how Jarvis listens and talks by voice (on the bike, without opening Settings): "Jarvis, Google వాయిస్‌కి
 * మారు", "Live పెట్టు", "Live ఆపు", "OpenAI కి మారు". The same choices as Settings → వాయిస్.
 */
final class VoiceSwitch {
    private VoiceSwitch() {}

    static final String LIVE = "live", LIVE_OPENAI = "live_openai", LIVE_OFF = "live_off", GOOGLE = "google", OPENAI = "openai", GEMINI = "gemini";

    private static final Pattern NOT_A_SWITCH = Pattern.compile(
            "(cricket|క్రికెట్|score|స్కోర్|match|మ్యాచ్|\\bipl\\b|ఐపీఎల్|commentary|కామెంటరీ|\\btv\\d*\\b|\\btv\\d|\\bntv|టీవీ|ఎన్టీవీ|news|వార్త|న్యూస్|"
                    + "video|వీడియో|youtube|యూట్యూబ్|దర్శనం|పాట|\\bsong|\\bpay\\b|పే |పేమెంట్|map|మ్యాప్|photo|ఫోటో|\\bapp\\b|యాప్|\\bmeet\\b|మీట్|\\bplay\\b|"
                    + "instagram|insta|ఇన్‌స్టా|ఇన్స్టా|facebook|ఫేస్‌బుక్|ఫేస్బుక్|search|వెతుకు|వెతికి|chrome|క్రోమ్|translate|అనువ|lens|లెన్స్|drive|డ్రైవ్|"
                    + "stream|స్ట్రీమ్|location|లొకేషన్|లోకేషన్|(google|గూగుల్)\\s*లో)");
    private static final Pattern LIVE_W = Pattern.compile("(\\blive\\b|లైవ్)");
    private static final Pattern OFF_W = Pattern.compile("(ఆపు|ఆపేయ్|ఆపెయ్|ఆపేసెయ్|ఆపండి|ఆఫ్|\\boff\\b|\\bstop\\b|తీసేయ్|తీసెయ్|తీసేయండి|వద్దు|ఆపేయండి)");
    private static final Pattern ON_W = Pattern.compile("(పెట్టు|పెట్టండి|పెట్టి|పెట్టేయ్|ఆన్|\\bon\\b|\\bstart\\b|మొదలు|మారు|మారండి|మార్చు|మార్చండి|మారిపో|\\bswitch\\b|\\bchange\\b)");
    private static final Pattern SWITCH_W = Pattern.compile("(మారు|మారండి|మారిపో|మార్చు|మార్చండి|మార్చేయ్|\\bswitch\\b|\\bchange\\b)");
    /** Leaving Live for another way: "Live ఆపి Google కి మారు", "Live నుంచి OpenAI కి", "switch from live to google". */
    private static final Pattern LEAVE_W = Pattern.compile("(ఆపి|ఆపేసి|తీసేసి|తీసి|నుంచి|నుండి|\\bfrom\\b|బదులు|instead)");
    private static final Pattern VOICE_W = Pattern.compile("(వాయిస్|\\bvoice\\b|మైక్|\\bmic\\b|వినడం|వినే|వినాలి|పద్ధతి)");
    private static final Pattern GOOGLE_W = Pattern.compile("(google|గూగుల్)");
    private static final Pattern OPENAI_W = Pattern.compile("(open\\s*ai|ఓపెన్\\s*ఏ\\s*ఐ|ఓపెన్\\s*ఎ\\s*ఐ|ఒపెన్\\s*ఏ\\s*ఐ|ఓపెన్‌ఏఐ|chat\\s*gpt|చాట్\\s*జీపీటీ|చాట్‌జీపీటీ)");
    private static final Pattern GEMINI_W = Pattern.compile("(gemini|జెమిని|జెమినీ)");
    private static final Pattern OLD_W = Pattern.compile("(పాత పద్ధతి|మామూలు పద్ధతి|సాధారణ పద్ధతి|normal mode|old mode)");
    /** Calling words that don't count as words of the command. */
    private static final Pattern NAMES = Pattern.compile("(\\bjarvis\\b|జార్విస్|\\bhey\\b|హే|\\bplease\\b|ప్లీజ్)");

    /** The mode he asked to switch to, or null when these words are not a switch (most of the time). */
    static String match(String said) {
        if (said == null) return null;
        String t = said.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]+", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 80 || NOT_A_SWITCH.matcher(t).find()) return null; // (a long sentence is a question, not a switch)
        String core = NAMES.matcher(t).replaceAll(" ").replaceAll("\\s+", " ").trim();
        int words = core.isEmpty() ? 0 : core.split(" ").length;
        String target = GOOGLE_W.matcher(t).find() ? GOOGLE : OPENAI_W.matcher(t).find() ? OPENAI : GEMINI_W.matcher(t).find() ? GEMINI : null;
        if (LIVE_W.matcher(t).find()) {
            java.util.regex.Matcher lm = LEAVE_W.matcher(core);
            if (lm.find()) {
                // leaving one way for another: what comes after "నుంచి / ఆపి / from" (after "to" in English) is where he goes
                if (!SWITCH_W.matcher(t).find()) return null;
                String after = core.substring(lm.end());
                int to = after.lastIndexOf(" to ");
                if (to >= 0) after = after.substring(to + 4);
                String goes = GOOGLE_W.matcher(after).find() ? GOOGLE : OPENAI_W.matcher(after).find() ? OPENAI : GEMINI_W.matcher(after).find() ? GEMINI : null;
                boolean toLive = LIVE_W.matcher(after).find();
                if (toLive) return OPENAI.equals(goes) ? LIVE_OPENAI : LIVE;
                if (goes != null) return goes;
                return OLD_W.matcher(after).find() || VOICE_W.matcher(after).find() ? LIVE_OFF : null;
            }
            if (words > 5 || !onlyLiveCommand(core)) return null; // Live itself must be what is switched, in a short command
            boolean on = ON_W.matcher(t).find(), off = OFF_W.matcher(t).find();
            if (off && !on) return LIVE_OFF;
            if (on && !off) return OPENAI.equals(target) ? LIVE_OPENAI : LIVE;
            return null;
        }
        if (!SWITCH_W.matcher(t).find()) return null;
        boolean aboutVoice = VOICE_W.matcher(t).find() || words <= 4; // "Google కి మారు"
        if (!aboutVoice) return null;
        if (target != null) return target;
        if (OLD_W.matcher(t).find()) return LIVE_OFF;
        return null;
    }

    /** Before "Live": nothing, whose Live, or an English verb ("switch to live"); after it: only words of turning it on / off. */
    private static final java.util.Set<String> LIVE_BEFORE = new java.util.HashSet<>(java.util.Arrays.asList(
            "gemini", "జెమిని", "జెమినీ", "openai", "open", "ai", "ఓపెన్", "ఒపెన్", "ఏ", "ఐ", "ఏఐ", "ఎఐ", "ఓపెన్‌ఏఐ", "chatgpt", "chat", "gpt",
            "చాట్", "జీపీటీ", "చాట్‌జీపీటీ", "switch", "to", "turn", "on", "off", "start", "stop", "the"));
    private static final java.util.Set<String> LIVE_AFTER = new java.util.HashSet<>(java.util.Arrays.asList(
            "మోడ్", "mode", "కి", "కు", "ని", "ను", "చేయి", "చెయ్", "చేయండి", "చేసెయ్", "ఆన్", "ఆఫ్", "on", "off", "start", "stop", "పెట్టు", "పెట్టండి",
            "పెట్టేయ్", "పెట్టి", "ఆపు", "ఆపండి", "ఆపేయ్", "ఆపెయ్", "ఆపేసెయ్", "ఆపేయండి", "తీసేయ్", "తీసెయ్", "తీసేయండి", "వద్దు", "మారు", "మారండి",
            "మార్చు", "మార్చండి", "మారిపో", "switch", "change", "మొదలు", "పెట్టు", "ఇక", "ఇప్పుడు", "ఇప్పుడే"));

    private static boolean onlyLiveCommand(String core) {
        java.util.regex.Matcher m = LIVE_W.matcher(core);
        if (!m.find()) return false;
        for (String w : core.substring(0, m.start()).trim().split(" ")) if (!w.isEmpty() && !LIVE_BEFORE.contains(w)) return false;
        String rest = core.substring(m.end()).replace("\u200c", " ").trim(); // ("లైవ్‌కి": the ending may be joined on)
        for (String w : rest.split(" ")) if (!w.isEmpty() && !LIVE_AFTER.contains(w)) return false;
        return true;
    }

    /** What his ears are now, in words. */
    static String earsName(String ears) {
        return "google".equals(ears) ? "Google వాయిస్ టైపింగ్" : "gemini".equals(ears) ? "Jarvis సొంత మైక్ (Gemini)" : "Jarvis సొంత మైక్ (OpenAI)";
    }

    /** Switches (when it can) and says so: {"ok", "say", "mode", "live": Live on now, "provider"}. */
    static JSONObject apply(Prefs p, String mode) {
        JSONObject r = new JSONObject();
        try {
            boolean live = p.liveMode();
            String prov = p.liveProvider(), ears = p.earsMode();
            String say;
            boolean ok = true;
            android.content.SharedPreferences.Editor e = p.sp.edit();
            switch (mode == null ? "" : mode) {
                case LIVE:
                    if (p.geminiKey().trim().isEmpty()) { ok = false; say = "Gemini key లేదు. సెట్టింగ్స్ → Jarvis మెదడు లో Gemini key పెట్టాక Live పెడతాను."; break; }
                    if (live && Prefs.GEMINI.equals(prov)) { say = "Gemini Live ఇప్పటికే ఆన్‌లో ఉంది."; break; }
                    e.putBoolean("live", true).putString("live_provider", Prefs.GEMINI);
                    say = "సరే, Gemini Live పెట్టాను. ఇక మాట్లాడితే వెంటనే జవాబిస్తాను.";
                    break;
                case LIVE_OPENAI:
                    if (p.openAiKey().trim().isEmpty()) { ok = false; say = "OpenAI key లేదు. సెట్టింగ్స్‌లో OpenAI key పెట్టాక OpenAI Live పెడతాను."; break; }
                    if (live && Prefs.OPENAI.equals(prov)) { say = "OpenAI Live ఇప్పటికే ఆన్‌లో ఉంది."; break; }
                    e.putBoolean("live", true).putString("live_provider", Prefs.OPENAI);
                    say = "సరే, OpenAI Live పెట్టాను.";
                    break;
                case LIVE_OFF:
                    if (!live) { say = "Live ఇప్పటికే ఆఫ్‌లో ఉంది. ఇప్పుడు " + earsName(ears) + " తో వింటున్నాను."; break; }
                    e.putBoolean("live", false);
                    say = "సరే, Live ఆపాను. ఇక " + earsName(ears) + " తో విని జవాబిస్తాను.";
                    break;
                case GOOGLE:
                    e.putBoolean("live", false).putString("ears_mode", "google");
                    say = "సరే, Google వాయిస్‌కి మారాను. మాటలు లైవ్‌గా కనిపిస్తాయి, కానీ మైక్ బీప్‌లు వస్తాయి.";
                    break;
                case OPENAI:
                    if (p.openAiKey().trim().isEmpty()) { ok = false; say = "OpenAI key లేదు. సెట్టింగ్స్‌లో OpenAI key పెట్టాక మారుస్తాను."; break; }
                    e.putBoolean("live", false).putString("ears_mode", "openai");
                    say = "సరే, Jarvis సొంత మైక్‌తో వింటాను, OpenAI మాటలు రాస్తుంది.";
                    break;
                case GEMINI:
                    if (p.geminiKey().trim().isEmpty()) { ok = false; say = "Gemini key లేదు. సెట్టింగ్స్‌లో Gemini key పెట్టాక మారుస్తాను."; break; }
                    e.putBoolean("live", false).putString("ears_mode", "gemini");
                    say = "సరే, Jarvis సొంత మైక్‌తో వింటాను, Gemini మాటలు రాస్తుంది. ఇది Live కాదు; Live కావాలంటే 'Live పెట్టు' అనండి.";
                    break;
                default:
                    ok = false;
                    say = "ఏ పద్ధతికి మారాలో అర్థం కాలేదు: Gemini Live, OpenAI, Gemini లేదా Google?";
            }
            if (ok) e.apply();
            r.put("ok", ok).put("say", say).put("mode", mode).put("live", p.liveMode()).put("provider", p.liveProvider());
        } catch (Exception ex) {
            try { r.put("ok", false).put("say", "మార్చలేకపోయాను: " + ex.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }
}
