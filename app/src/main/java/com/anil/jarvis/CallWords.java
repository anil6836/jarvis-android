package com.anil.jarvis;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📞 The words of a ringing call (Anil, 21 Oct): "ఎత్తు", "కట్ చేయి", "సైలెంట్", "కట్ చేసి … అని మెసేజ్ పెట్టు",
 * "తర్వాత చేస్తా"; the message itself out of his words; the minutes in "అరగంటలో"; a caller's number (unknown,
 * foreign, 140 ads, 1600 bank series, his own spam marks); his important people; and the talk after a cut call
 * (read the message back, "పంపనా?", "గుర్తు చేయనా?"). Plain Java (no Android): the phone's panel and the watch
 * use the same rules, and they are checked on the desk.
 */
final class CallWords {
    private CallWords() {}

    static final int NONE = 0, ANSWER = 1, DECLINE = 2, SILENT = 3, MESSAGE = 4;

    /** What he said to the ringing call. */
    static final class Word {
        int kind = NONE;
        /** "తర్వాత చేస్తా", "అరగంటలో": he will call back (a reminder may be offered). */
        boolean later;
        int minutes = 30;
        /** The message he wants sent ("" = ask him what). */
        String text = "";
        /** One of his ready messages by its name ("మీటింగ్ మెసేజ్"): meeting / driving / busy, else "". */
        String quick = "";
        /** His words (the announcement / names taken off): the message is taken from them again with the caller's name. */
        String raw = "";
    }

    static final String[] NO = {"కట్", "cut", "reject", "వద్దు", "decline", "తర్వాత", "తరువాత", "later", "busy", "బిజీ", "no", "నో", "తీయకు",
            "ఎత్తకు", "ఎత్తొద్దు", "ఎత్తకండి", "తీయొద్దు", "మాట్లాడలేను", "మాట్లాడను", "మాట్లాడలేం", "రిజెక్ట్", "డిక్లైన్"};
    static final String[] YES = {"ఎత్తు", "ఎత్తండి", "ఎత్తి", "ఎత్తేయ్", "లిఫ్ట్", "lift", "answer", "attend", "pick", "receive", "yes", "అవును", "ఓకే",
            "ok", "okay", "సరే", "మాట్లాడతా", "మాట్లాడుతా", "ఆన్సర్", "రిసీవ్", "అటెండ్"};
    static final String[] SILENT_W = {"సైలెంట్", "silent", "silence", "mute", "మ్యూట్", "quiet", "నిశ్శబ్ద", "సైలెన్స్", "సైలెంటు"};
    static final String[] SILENT_PHRASES = {"సౌండ్ ఆపు", "సౌండ్ ఆపేయ్", "శబ్దం ఆపు", "రింగ్ ఆపు", "రింగ్ ఆపేయ్", "రింగ్ తగ్గించు", "సౌండ్ తీసేయ్",
            "సౌండ్ కట్", "stop ringing", "stop the ring"};
    static final String[] MSG_W = {"మెసేజ్", "మెస్సేజ్", "మెసెజ్", "మేసేజ్", "మెసేజి", "message", "sms", "ఎస్ఎంఎస్", "msg"};
    /** The words that send ("మెసేజ్ పెట్టు"), matched exactly ("పెట్టొద్దు" is no send). */
    static final String[] SEND_V = {"పెట్టు", "పెట్టండి", "పెట్టేయ్", "పెట్టేయి", "పంపు", "పంపండి", "పంపించు", "పంపించండి", "పంపేయ్", "పంపేయి", "చేయి",
            "చెయ్యి", "చెయ్", "చేయండి", "ఇవ్వు", "send"}; // ("మెసేజ్ చూసి చెప్పు" is no send: "చెప్పు" only with "అని")
    /** "తర్వాత" / "later" say he will call back; a time ("అరగంటలో", "పది నిమిషాల్లో") only with "చేస్తా" ("ఒక్క నిమిషం" is no cut). */
    static final String[] LATER_W = {"తర్వాత", "తరువాత", "later"};
    static final String[] TIME_W = {"అరగంట", "గంట", "నిమిష", "minutes", "minute", "mins", "hour", "hours"};
    static final String[] CALLBACK_V = {"చేస్తా", "చేస్తాం", "మాట్లాడతా", "మాట్లాడుతా", "call", "కాల్"};
    /** "ఆగు": he will see to it himself. */
    static final String[] HOLD = {"ఆగు", "ఆగండి", "wait", "hold"};
    /** A "no" next to another word ("కట్ చేయొద్దు", "మాట్లాడే టైం లేదు"): unclear, asked again. */
    static final String[] NEG = {"లేదు", "లేను", "కాదు", "వద్దు", "వద్దులే", "వద్దండి", "don't", "dont", "not", "చేయకు", "చెయ్యకు", "చేయకండి", "ఎత్తకు", "ఎత్తకండి",
            "పెట్టకు", "పెట్టకండి", "పంపకు"};
    /**
     * The only other words a command to a ringing call may have ("కాల్ ఎత్తు", "సైలెంట్ లో పెట్టు", "పది నిమిషాల తర్వాత చేస్తా");
     * any other word ("ఆ తర్వాత ఏమైంది", "no problem", "ఎత్తుగా ఉన్నాడు", a song's line) = not a command: asked again.
     */
    static final String[] CMD_FILL = {"చేయి", "చెయ్యి", "చెయ్", "చేసేయ్", "చేసెయ్", "చేయండి", "చేసి", "కాల్", "call", "the", "it", "up", "ఇప్పుడు", "ఇప్పుడే", "వెంటనే",
            "ప్లీజ్", "please", "అది", "ఆ", "దాన్ని", "ఫోన్", "phone", "జార్విస్", "jarvis", "ఒకసారి", "నేను", "నాకు", "లో", "పెట్టు", "పెట్టేయ్", "స్పీకర్", "speaker",
            "అండి", "సార్", "ఇంక", "ఇక"};
    /** Question words: "ఎవరు అని చెప్పు" is a question, no message. */
    static final String[] ASKING = {"ఎవరు", "ఎవరో", "ఎవరి", "ఏంటి", "ఏమిటి", "ఎక్కడ", "ఎందుకు", "ఎప్పుడు", "పేరు", "ఏం", "ఏమి"};

    /** "పంపు" / "అవును" after the message is read back: matched exactly ("హాస్పిటల్" is no "హా"). */
    static final String[] SEND_YES = {"పంపు", "పంపండి", "పంపించు", "పంపించండి", "పంపేయ్", "పంపేయి", "పంపెయ్", "పంపొచ్చు", "పంపవచ్చు", "send", "yes", "yeah",
            "అవును", "సరే", "ok", "okay", "ఓకే"}; // (no "హా": a laugh is no yes)
    /** Small words around a yes ("సరే పంపేయ్ ఇప్పుడే"). */
    static final String[] FILLER = {"అది", "ఇది", "అలాగే", "ప్లీజ్", "please", "ఇప్పుడే", "వెంటనే", "it", "now", "అయితే", "ఇక", "మెసేజ్", "sms", "ఆ"};
    /** A yes only when said alone ("పెట్టు"; "బస్సులో ఉన్నానని పెట్టు" is a new message). */
    static final String[] ALONE_YES = {"పెట్టు", "పెట్టేయ్", "పెట్టండి", "చేయి", "చెయ్యి", "చేయండి", "కానివ్వు", "చేసేయ్"};
    static final String[] SEND_NO = {"వద్దు", "వద్దులే", "no", "నో", "cancel", "క్యాన్సిల్", "ఆపు", "పంపకు", "పంపొద్దు", "పంపకండి", "వదిలేయ్", "వద్దండి", "ఏమీవద్దు"};
    /** "కాదు" / "మార్చు": he is changing the message. */
    static final String[] CHANGE = {"కాదు", "మార్చు", "మార్చి", "change"};

    // ---------------------------------------------------------------- what he said to the ringing call

    /**
     * Jarvis's own question heard by the other device's mic (the phone and the watch both talk while it rings):
     * "ఎత్తమంటారా?" (even split by the voice typing: "ఎత్తు మంటారా") and the announcement come off, so they never
     * answer or cut the call by themselves. (The questions never say "కట్".)
     */
    private static final Pattern ECHO = Pattern.compile("(?:\\S+\\s+)?\\S*మంటారా|కాల్ వస్తోంది|కాల్ చేస్తున్నారు|అర్జెంట్ కావచ్చు");

    static String unEcho(String heard) {
        if (heard == null) return "";
        String h = heard.replaceFirst("(?s)^.*?(?:నుంచి|నుండి)\\s+(?:\\S+\\s+)?కాల్", " ") // ("Anil, <name> నుంచి [WhatsApp] కాల్ …": the name and all before it; "ఆఫీస్ నుంచి వస్తున్నా" stays)
                .replaceAll("(?:\\S+\\s+){0,2}\\S+\\s+కాల్ చేస్తున్నారు", " "); // ("<name> కాల్ చేస్తున్నారు")
        return ECHO.matcher(h).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    static String[] tokens(String low) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : low.split("[^\\p{L}\\p{M}\\p{N}']+")) if (!t.isEmpty()) out.add(t);
        return out.toArray(new String[0]);
    }

    /** A token is one of these words (English whole word; Telugu with any ending). */
    static boolean is(String tok, String[] words) {
        for (String w : words) {
            if (tok.equals(w)) return true;
            if (w.charAt(0) > 0x7F && tok.startsWith(w) && !exact(w, SHORT)) return true;
        }
        return false;
    }

    /** Short words matched only whole ("కట్టా", "నోముల", "సరేష్" are names, not కట్ / నో / సరే). */
    private static final String[] SHORT = {"కట్", "నో", "ఓకే", "సరే", "హా", "ఆగు", "బిజీ"};

    private static boolean exact(String tok, String[] words) {
        for (String w : words) if (tok.equals(w)) return true;
        return false;
    }

    private static boolean negWord(String tok) {
        return exact(tok, NEG) || tok.endsWith("ొద్దు") || tok.endsWith("కూడదు");
    }

    static Word classify(String heard) { return classify(heard, null); }

    /**
     * His words to the ringing call. strip: the caller's name and his own (said in the announcement the other device's mic
     * may hear: "కట్టా శ్రీనివాస్" must not cut a call). Unclear (two kinds, a "no" beside a word, "ఆగు", a long sentence such as
     * a song's words) = NONE: asked again, never a guess.
     */
    static Word classify(String heard, String[] strip) {
        Word w = new Word();
        if (heard == null) return w;
        heard = unEcho(heard);
        if (strip != null) {
            for (String n : strip) {
                if (n == null) continue;
                for (String part : n.trim().split("[^\\p{L}\\p{M}\\p{N}]+")) {
                    if (part.length() < 2) continue;
                    heard = heard.replaceAll("(?iu)(?<![\\p{L}\\p{M}])" + Pattern.quote(part) + "(?![\\p{L}\\p{M}])", " ");
                }
            }
            heard = heard.replaceAll("\\s+", " ").trim();
        }
        w.raw = heard;
        String low = heard.toLowerCase(Locale.ROOT).trim();
        if (low.isEmpty()) return w;
        String[] tok = tokens(low);
        for (String t : tok) if (is(t, HOLD)) return w;
        boolean msgWord = false, sendVerb = false;
        for (String t : tok) { if (is(t, MSG_W)) msgWord = true; if (exact(t, SEND_V)) sendVerb = true; }
        if (msgWord || saysTell(low)) {
            String text = extract(heard, "");
            String q = quick(text);
            if (!(msgWord && (sendVerb || !q.isEmpty()) || !msgWord && saysTell(low))) return w; // ("మెసేజ్ వద్దు ఎత్తు": unclear)
            w.kind = MESSAGE;
            w.text = text;
            w.quick = q;
            w.later = laterIn(tok);
            if (w.later) w.minutes = minutes(low);
            return w;
        }
        // the silence words first ("సౌండ్ కట్" is quiet, not a cut)
        String rest = " " + low.replaceAll("[\\s,.!?]+", " ") + " ";
        boolean silent = false;
        for (String ph : SILENT_PHRASES) if (rest.contains(" " + ph)) { silent = true; rest = rest.replace(" " + ph, " "); }
        String[] rt = tokens(rest);
        boolean no = false, yes = false;
        boolean later = laterIn(rt);
        java.util.List<String> kindToks = new java.util.ArrayList<>();
        for (String t : rt) {
            if (is(t, SILENT_W)) { silent = true; kindToks.add(t); }
            else if (is(t, NO)) { no = true; kindToks.add(t); }
            else if (later && is(t, CALLBACK_V)) continue; // ("తర్వాత మాట్లాడతా": a call back, not an answer)
            else if (is(t, YES)) { yes = true; kindToks.add(t); }
        }
        for (String t : rt) { // every other word a command's own ("ఆ తర్వాత ఏమైంది" is talk, not a command)
            if (kindToks.contains(t) || exact(t, CMD_FILL) || is(t, TIME_W) || is(t, CALLBACK_V) || t.matches("\\d{1,3}") || numWord(t)) continue;
            return w;
        }
        int kinds = (silent ? 1 : 0) + (no || later ? 1 : 0) + (yes ? 1 : 0);
        if (kinds != 1) return w; // both, or none
        for (String t : rt) if (negWord(t) && !(kindToks.size() == 1 && kindToks.get(0).equals(t))) return w; // "కట్ చేయొద్దు"
        if (silent) { if (tok.length <= 4) w.kind = SILENT; return w; }
        if (yes) { if (tok.length <= 4) w.kind = ANSWER; return w; }
        if (tok.length > 6) return w;
        w.kind = DECLINE;
        w.later = later;
        if (later) w.minutes = minutes(low);
        return w;
    }

    private static boolean numWord(String t) {
        for (String[] n : NUM) if (t.equals(n[0])) return true;
        return t.equals("పదిహేను") || t.equals("ఇరవై") || t.equals("ముప్పై");
    }

    private static boolean laterIn(String[] tok) {
        boolean time = false, back = false;
        for (String t : tok) {
            if (is(t, LATER_W)) return true;
            if (is(t, TIME_W)) time = true;
            if (is(t, CALLBACK_V)) back = true;
        }
        return time && back;
    }

    /** "…అని చెప్పు", "…నని చెప్పు", "…అని పంపు": a message without the word "మెసేజ్" (never a question: "ఎవరు అని చెప్పు"). */
    private static boolean saysTell(String low) {
        if (!low.matches("(?s).*(\\sఅని|నని)[\\s,]+(చెప్పు|చెప్పండి|పంపు|పంపండి|పంపించు|పెట్టు).*")) return false;
        for (String t : tokens(low)) if (exact(t, ASKING)) return false;
        return true;
    }

    /**
     * Whole-word match, so "no" doesn't match "now". English words must match a whole word; Telugu words may carry an
     * ending (ఎత్తండి, వద్దులే, అరగంటలో), so they match the word's start.
     */
    static boolean hasWord(String low, String[] words) {
        for (String tok : tokens(low)) if (is(tok, words)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the message out of his words

    private static final String MSG = "(?:మెసేజ్|మెస్సేజ్|మెసెజ్|మేసేజ్|మెసేజి|ఎస్ఎంఎస్|sms|message|msg)";
    private static final String DO = "(?:పెట్టు|పెట్టండి|పెట్టేయ్|పెట్టేయి|పంపు|పంపండి|పంపించు|పంపించండి|పంపేయ్|పంపేయి|చేయి|చెయ్యి|చెయ్|చేయండి|ఇవ్వు|చెప్పు|చెప్పండి|send(?: it)?)";
    private static final Pattern START_CUT = Pattern.compile("(?i)^\\s*(?:(?:కాల్\\s*)?కట్\\s*(?:చేసి|చేయి|చెయ్యి|చెయ్|చేసేసి|చేయండి|చేస్తూ)?"
            + "|(?:cut|reject|decline)(?:\\s+(?:the\\s+call|it))?(?:\\s+and)?|ఎత్తకుండా|ఎత్తకు|ఎత్తొద్దు)[,\\s]+");
    private static final Pattern END_MSG = Pattern.compile("(?i)[,\\s]+(?:అని\\s+)?(?:ఒక\\s+)?" + MSG + "(?:\\s*(?:లో|గా))?(?:\\s+" + DO + ")?[.!?,\\s]*$");
    private static final Pattern END_TELL = Pattern.compile("(?i)[,\\s]+అని\\s+(?:చెప్పు|చెప్పండి|పంపు|పంపండి|పంపించు|పెట్టు)[.!?,\\s]*$");
    private static final Pattern END_FUSED = Pattern.compile("(నని)[,\\s]+(?:చెప్పు|చెప్పండి|పంపు|పంపండి|పంపించు|పెట్టు)[.!?,\\s]*$");
    /** "… అని కట్ చేసి" left at the end once the message words are off. */
    private static final Pattern END_CUT = Pattern.compile("(?i)[,\\s]+(?:అని\\s+)?(?:కాల్\\s+)?కట్\\s*(?:చేసి|చేయి|చెయ్యి|చెయ్|చేసేసి)?[.!?,\\s]*$");
    private static final Pattern START_MSG = Pattern.compile("(?i)^\\s*(?:ఒక\\s+)?" + MSG + "(?:\\s+" + DO + ")?[,:\\s]+");
    private static final Pattern TO_THEM = Pattern.compile("(?i)^\\s*(?:వాళ్ళకి|వాళ్లకి|వాళ్ళకు|వాళ్లకు|అతనికి|ఆమెకి|ఆయనకి|ఆవిడకి|తనకి|తనకు|వారికి|him|her|them)[,\\s]+");

    /**
     * The message in his words: "కట్ చేసి మీటింగ్‌లో ఉన్నాను అని మెసేజ్ పెట్టు" -> "మీటింగ్‌లో ఉన్నాను"; "బిజీగా ఉన్నానని చెప్పు" ->
     * "బిజీగా ఉన్నాను" (the "అని" joined to the word comes off). who: the caller ("రవికి …" at the start comes off).
     */
    static String extract(String heard, String who) {
        if (heard == null) return "";
        String s = " " + heard.replaceAll("\\s+", " ").trim() + " ";
        for (int i = 0; i < 2; i++) s = START_CUT.matcher(s).replaceFirst(" ");
        s = END_MSG.matcher(s).replaceFirst(" ");
        s = END_CUT.matcher(s).replaceFirst(" ");
        s = END_TELL.matcher(s).replaceFirst(" ");
        s = END_FUSED.matcher(s).replaceFirst("$1 ");
        s = START_MSG.matcher(s).replaceFirst(" ");
        s = START_CUT.matcher(s).replaceFirst(" "); // ("మెసేజ్ పెట్టు, కట్ చేసి …")
        s = TO_THEM.matcher(s).replaceFirst(" ");
        s = s.trim();
        // "రవికి …": the caller's own name at the start
        String first = s.split("\\s+")[0];
        String whoFirst = who == null ? "" : who.trim().split("\\s+")[0];
        if (whoFirst.length() >= 2 && first.length() > whoFirst.length() && first.toLowerCase(Locale.ROOT).startsWith(whoFirst.toLowerCase(Locale.ROOT))
                && first.matches(".*(కి|కు|ki|ku)$")) s = s.substring(first.length()).trim();
        s = s.replaceFirst("[,\\s]+అని$", "").replaceFirst("^అని\\s+", "").trim();
        if (s.endsWith("నని")) s = s.substring(0, s.length() - 2) + "ు"; // ఉన్నానని -> ఉన్నాను
        s = s.replaceAll("^[,.:;!?\\s]+|[,;:\\s]+$", "");
        if (!s.contains(" ") && s.matches(".*(కి|కు)$")) return ""; // ("రవికి" alone: only whom, no message yet)
        return s.length() < 2 ? "" : s;
    }

    /** His ready message by its name: "మీటింగ్" / "డ్రైవింగ్" / "బిజీ" alone (his own sentence stays his). */
    static String quick(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?,\\u200C]+", "");
        if (t.isEmpty() || t.contains(" ")) return "";
        if (t.startsWith("మీటింగ్") && t.length() <= 9 || t.equals("meeting")) return "meeting";
        if (t.startsWith("డ్రైవింగ్") && t.length() <= 11 || t.startsWith("డ్రైవ్") && t.length() <= 8 || t.equals("driving") || t.equals("drive")) return "driving";
        if (t.startsWith("బిజీ") && t.length() <= 6 || t.equals("busy")) return "busy";
        return "";
    }

    private static final String[][] NUM = {{"ఒకటి", "1"}, {"ఒక్క", "1"}, {"ఒక", "1"}, {"రెండు", "2"}, {"మూడు", "3"}, {"నాలుగు", "4"}, {"అయిదు", "5"}, {"ఐదు", "5"},
            {"పదిహేను", "15"}, {"పది", "10"}, {"ఇరవై", "20"}, {"పాతిక", "25"}, {"ముప్పై", "30"}, {"ముప్ఫై", "30"}, {"నలభై", "40"}, {"యాభై", "50"}};

    /** Minutes in his words: "అరగంటలో" 30, "పది నిమిషాల తర్వాత" 10, "రెండు గంటల" 120, "20 minutes" 20; else 30. */
    static int minutes(String low) {
        String s = low == null ? "" : low.toLowerCase(Locale.ROOT);
        if (s.contains("పావు గంట") || s.contains("quarter")) return s.contains("ముప్పావు") || s.contains("three quarter") ? 45 : 15;
        if (s.contains("ముప్పావు")) return 45;
        if (s.contains("రెండున్నర")) return 150;
        if (s.contains("గంటన్నర") || s.contains("ఒకటిన్నర")) return 90;
        if (s.contains("అరగంట") || s.contains("half an hour") || s.contains("half hour")) return 30;
        Matcher hm = Pattern.compile("(\\d{1,2})\\s*(?:గంట|hour|hours|hr)").matcher(s), mm = Pattern.compile("(\\d{1,3})\\s*(?:నిమిష|ని\\.|min|mins|minute|minutes)").matcher(s);
        boolean h = hm.find(), mi = mm.find();
        if (h && mi) return clamp(Integer.parseInt(hm.group(1)) * 60 + Integer.parseInt(mm.group(1))); // ("1 hour 30 minutes")
        Matcher m = Pattern.compile("(\\d{1,3})\\s*(నిమిష|ని\\.|min|mins|minute|minutes)").matcher(s);
        if (m.find()) return clamp(Integer.parseInt(m.group(1)));
        m = Pattern.compile("(\\d{1,2})\\s*(గంట|hour|hours|hr)").matcher(s);
        if (m.find()) return clamp(Integer.parseInt(m.group(1)) * 60);
        String[] tok = s.split("[^\\p{L}\\p{M}\\p{N}]+");
        for (int i = 1; i < tok.length; i++) {
            boolean min = tok[i].startsWith("నిమిష"), hour = tok[i].startsWith("గంట");
            if (!min && !hour) continue;
            for (String[] n : NUM) {
                if (tok[i - 1].equals(n[0])) return clamp(Integer.parseInt(n[1]) * (hour ? 60 : 1));
            }
        }
        if (s.matches("(?s).*(గంట|an hour|one hour).*")) return 60;
        return 30;
    }

    private static int clamp(int m) { return Math.max(5, Math.min(12 * 60, m)); }

    /** "30 నిమిషాల్లో", "గంటలో", "2 గంటల్లో". */
    static String inMinutes(int m) {
        if (m == 60) return "గంటలో";
        if (m % 60 == 0) return (m / 60) + " గంటల్లో";
        if (m == 30) return "అరగంటలో";
        return m + " నిమిషాల్లో";
    }

    // ---------------------------------------------------------------- the caller's number

    /** The name on the call is only a number (not in his contacts): digits, spaces, + and - only. */
    static boolean isNumber(String who) {
        if (who == null) return false;
        String w = who.trim();
        if (w.isEmpty() || w.matches("(?s).*\\p{L}.*")) return false;
        return w.replaceAll("\\D", "").length() >= 5;
    }

    /** Digits only (with a leading + kept). */
    static String digits(String s) {
        if (s == null) return "";
        String t = s.trim();
        return (t.startsWith("+") ? "+" : "") + t.replaceAll("\\D", "");
    }

    /** The same number however written: the last 10 digits of an Indian number, all digits of a foreign one. */
    static String key(String number) {
        String d = digits(number);
        boolean plus = d.startsWith("+");
        String n = plus ? d.substring(1) : d;
        if (plus && !n.startsWith("91")) return "+" + n;
        if (n.startsWith("0091")) n = n.substring(4);
        else if (n.startsWith("00")) return "+" + n.substring(2);
        if (n.length() == 12 && n.startsWith("91")) n = n.substring(2);
        if (n.length() == 11 && n.startsWith("0")) n = n.substring(1);
        if (n.length() > 10 && !n.startsWith("0")) return "+" + n; // (a foreign number written without its +)
        return n;
    }

    /**
     * What kind of number: "spam" (he marked it), "foreign" (a +code that isn't India's), "promo" (140…, telemarketing),
     * "service" (1600…, banks and other regulated companies' service calls), else "".
     */
    static String kind(String number, Set<String> spam) {
        String k = key(number);
        if (k.isEmpty()) return "";
        if (spam != null && spam.contains(k)) return "spam";
        if (k.startsWith("+")) return "foreign";
        if (k.startsWith("140")) return "promo";
        if (k.startsWith("1600")) return "service";
        return "";
    }

    /** "4 5 6 7": the last four digits, said one by one. */
    static String lastFour(String number) {
        String d = digits(number).replace("+", "");
        if (d.length() < 4) return d;
        String f = d.substring(d.length() - 4);
        return f.charAt(0) + " " + f.charAt(1) + " " + f.charAt(2) + " " + f.charAt(3);
    }

    /** The warning said with the call (Telugu), "" for none. */
    static String warning(String kind) {
        switch (kind) {
            case "spam": return "మీరు స్పామ్ అన్న నంబర్.";
            case "foreign": return "విదేశీ నంబర్, జాగ్రత్త: OTP, PIN ఎవరికీ చెప్పకండి.";
            case "promo": return "ఇది ప్రకటనల 140 నంబర్.";
            case "service": return "1600 నంబర్: బ్యాంక్ లాంటి సంస్థల సర్వీస్ కాల్.";
            case "phone_spam": return "ఫోన్ ఇది స్పామ్ కావచ్చు అంటోంది.";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- his important people

    /** The caller is one of his important people (names, comma / new line apart): the same name, or a whole word of it. */
    static boolean vip(String who, String list) {
        String w = norm(who);
        if (w.isEmpty() || list == null) return false;
        String[] whoWords = who.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+");
        for (String v : list.split("[,\\n;]+")) {
            String n = norm(v);
            if (n.length() < 2) continue;
            if (n.equals(w)) return true;
            for (String t : whoWords) if (!t.isEmpty() && t.equals(n)) return true;
        }
        return false;
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", ""); }

    // ---------------------------------------------------------------- the talk after a cut call

    static final int ASK_TEXT = 1, CONFIRM = 2, REMIND = 3, OVER = 0;

    /** One message to the caller of the call he just cut (and a reminder to call back). */
    static final class Reply {
        final String who;
        String text = "";
        int stage = OVER, tries, fixes;
        boolean later, remind, send;
        int minutes = 30;
        /** The message is one of his ready ones he allowed to go without asking. */
        boolean ready;
        /** The caller's number when the call showed only a number; "" for a saved contact (found at sending). */
        String number = "";
        /** A phone call (else WhatsApp / another app's call: the SMS goes to the contact's saved number). */
        boolean phone = true;
        /** When the call rang (the message goes to that call's caller only). */
        long ringAt;
        /** The phone itself called it spam ("Suspected spam"): no SMS to it. */
        boolean phoneSpam;

        Reply(String who) { this.who = who == null ? "" : who.trim(); }

        String to() {
            if (who.isEmpty() || isNumber(who)) return "ఆ నంబర్‌కి";
            char last = who.charAt(who.length() - 1);
            return who + (last >= 0x0C00 && last <= 0x0C7F ? "కి" : " కి");
        }
    }

    /** What to do now: say this, then listen (or not); send the message; set the reminder; the talk is over. */
    static final class Step {
        String say = "";
        boolean listen, send, remind, over;
        static Step say(String s, boolean listen) { Step t = new Step(); t.say = s; t.listen = listen; return t; }
    }

    /**
     * The reply begins (the call is already cut). w: his words; readyText / readyOk: his ready message for w.quick (its
     * text, and whether he allowed it to go without asking).
     */
    static Step start(Reply r, Word w, String readyText, boolean readyOk) {
        r.later = w.later;
        r.minutes = w.minutes;
        if (!w.quick.isEmpty() && readyText != null && !readyText.trim().isEmpty()) {
            r.text = readyText.trim();
            if (readyOk) { r.ready = true; r.send = true; Step s = new Step(); s.send = true; return s; } // his own rule: no asking
            r.stage = CONFIRM;
            return readBack(r);
        }
        String text = w.raw.isEmpty() ? w.text : extract(w.raw, r.who); // (again, with the caller's name: "రవికి …" off)
        if (text.isEmpty()) {
            r.stage = ASK_TEXT;
            return Step.say("కట్ చేశాను. " + r.to() + " ఏమని మెసేజ్ పంపాలి?", true);
        }
        r.text = text;
        r.stage = CONFIRM;
        return readBack(r);
    }

    /** "రవికి: 'మీటింగ్‌లో ఉన్నాను'. పంపనా?" */
    private static Step readBack(Reply r) { return Step.say(r.to() + ": '" + bare(r.text) + "'. పంపనా?", true); }

    private static String bare(String t) { return t == null ? "" : t.trim().replaceAll("[.!।]+$", ""); }

    /** He only cut it while driving: his driving message offered (one "పంపు"). */
    static Step offerDriving(Reply r, String drivingText, boolean later, int minutes) {
        r.later = later;
        r.minutes = minutes;
        r.text = drivingText == null ? "" : drivingText.trim();
        if (r.text.isEmpty()) return later ? askRemind(r, "కట్ చేశాను. ") : over("");
        r.stage = CONFIRM;
        return Step.say("కట్ చేశాను. '" + bare(r.text) + "' అని " + r.to() + " మెసేజ్ పంపనా?", true);
    }

    /** He cut it saying "తర్వాత చేస్తా": the reminder offered. */
    static Step askRemind(Reply r, String before) {
        r.stage = REMIND;
        r.tries = 0;
        return Step.say(before + r.to() + " " + inMinutes(r.minutes) + " తిరిగి కాల్ చేయమని గుర్తు చేయనా?", true);
    }

    static Step over(String say) { Step s = Step.say(say, false); s.over = true; return s; }

    /** Every word is a yes (exact), with small words around it; or one "పెట్టు"-like word alone. remind: "గుర్తు చేయి" too. */
    static boolean allYes(String[] tok, boolean remind) {
        if (tok.length == 0) return false;
        if (tok.length == 1 && exact(tok[0], ALONE_YES)) return true;
        boolean any = false;
        for (String t : tok) {
            if (exact(t, SEND_YES)) any = true;
            else if (remind && (t.equals("గుర్తు") || exact(t, ALONE_YES))) any = any || t.equals("గుర్తు");
            else if (!exact(t, FILLER)) return false;
        }
        return any;
    }

    private static boolean saysNo(String[] tok) {
        if (tok.length == 0 || tok.length > 4) return false;
        for (String t : tok) if (exact(t, SEND_NO)) return true;
        return false;
    }

    /** "కాదు, బిజీగా ఉన్నానని పెట్టు" -> "బిజీగా ఉన్నాను" (the change words and a last lone "పెట్టు" off). */
    private static String corrected(String t, String who) {
        String x = t.trim().replaceFirst("(?i)^(?:కాదు|వద్దు|మార్చు|మార్చి|change it|change)[,.\\s]+", "");
        x = extract(x, who);
        x = x.replaceFirst("[,\\s]+(?:పెట్టు|పంపు|పంపించు|పెట్టండి|పంపండి)$", "").trim();
        return x;
    }

    /** His words while the reply talks ("" = nothing heard). */
    static Step heard(Reply r, String words) {
        String t = words == null ? "" : words.trim();
        String[] tok = tokens(t.toLowerCase(Locale.ROOT));
        switch (r.stage) {
            case ASK_TEXT: {
                if (t.isEmpty()) {
                    if (++r.tries < 2) return Step.say("ఏమని పంపాలి?", true);
                    r.stage = OVER;
                    return r.later ? askRemind(r, "సరే, మెసేజ్ పంపలేదు. ") : over("సరే, మెసేజ్ పంపలేదు.");
                }
                if (saysNo(tok)) { r.stage = OVER; return r.later ? askRemind(r, "సరే, మెసేజ్ పంపలేదు. ") : over("సరే, మెసేజ్ పంపలేదు."); }
                String x = corrected(t, r.who);
                if (x.isEmpty()) return Step.say("ఏమని పంపాలి?", true);
                r.text = x;
                r.stage = CONFIRM;
                r.tries = 0;
                return readBack(r);
            }
            case CONFIRM: {
                if (t.isEmpty()) {
                    if (++r.tries < 2) return Step.say("పంపనా? 'పంపు' లేదా 'వద్దు' అనండి.", true);
                    r.stage = OVER;
                    return over("సరే, పంపలేదు.");
                }
                if (saysNo(tok) && !(tok.length > 1 && exact(tok[0], CHANGE))) {
                    r.stage = OVER;
                    return r.later ? askRemind(r, "సరే, పంపలేదు. ") : over("సరే, పంపలేదు.");
                }
                if (allYes(tok, false)) {
                    r.stage = OVER;
                    r.send = true;
                    Step s = new Step();
                    s.send = true;
                    return s;
                }
                // anything else: his corrected message, read back again (twice at most); never the old one sent
                if (++r.fixes > 2) { r.stage = OVER; return over("సరే, పంపలేదు. కావాలంటే Jarvis ని పిలిచి మళ్ళీ చెప్పండి."); }
                String x = tok.length == 1 && exact(tok[0], CHANGE) ? "" : corrected(t, r.who);
                r.tries = 0;
                if (x.isEmpty()) { r.stage = ASK_TEXT; return Step.say("ఏమని పంపాలి?", true); }
                r.text = x;
                return readBack(r);
            }
            case REMIND: {
                if (saysNo(tok)) { r.stage = OVER; return over("సరే."); }
                if (allYes(tok, true)) {
                    r.stage = OVER;
                    r.remind = true;
                    Step s = over("సరే, " + inMinutes(r.minutes) + " గుర్తు చేస్తాను.");
                    s.remind = true;
                    return s;
                }
                if (t.isEmpty() && ++r.tries < 2) return Step.say("గుర్తు చేయనా? 'సరే' లేదా 'వద్దు' అనండి.", true);
                r.stage = OVER;
                return over(t.isEmpty() ? "సరే." : "సరే, గుర్తు పెట్టలేదు.");
            }
            default:
                return over("");
        }
    }

    /** The SMS went (error null) or not: said, then the reminder offered if he will call back. */
    static Step sent(Reply r, String error) {
        String first = error == null ? (r.ready ? r.to() + " '" + bare(r.text) + "' పంపాను. " : "పంపాను. ") : "మెసేజ్ వెళ్ళలేదు: " + error + ". ";
        if (r.later) return askRemind(r, first);
        return over(first.trim());
    }
}
