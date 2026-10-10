package com.anil.jarvis;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Little games for అమ్మగారు's mind (all work without internet): a memory game (Jarvis says four things, she says them
 * back), a Bible quiz, and riddles (Sayings). "🎲 ఆట" or "ఆట ఆడదాం" plays the next one; her answer is checked here.
 */
final class HomeGames {
    private HomeGames() {}

    /** {what is said, words that count as it}. */
    private static final String[][] THINGS = {
            {"మామిడి పండు", "మామిడి"}, {"గంట", "గంట"}, {"కుర్చీ", "కుర్చీ"}, {"గులాబీ పువ్వు", "గులాబ"}, {"గొడుగు", "గొడుగు"},
            {"చెప్పులు", "చెప్పు"}, {"పాలు", "పాలు"}, {"అరటిపండు", "అరటి"}, {"పుస్తకం", "పుస్తక"}, {"దీపం", "దీప"},
            {"కిటికీ", "కిటికీ"}, {"తాళం", "తాళ"}, {"చెంచా", "చెంచా"}, {"గ్లాసు", "గ్లాసు"}, {"అద్దం", "అద్ద"},
            {"దువ్వెన", "దువ్వెన"}, {"సైకిల్", "సైకిల్"}, {"ఆవు", "ఆవు"}, {"చిలుక", "చిలుక"}, {"చంద్రుడు", "చంద్ర"},
            {"నక్షత్రం", "నక్షత్ర"}, {"బియ్యం", "బియ్య"}, {"ఉప్పు", "ఉప్పు"}, {"కొబ్బరికాయ", "కొబ్బరి"}, {"బెల్లం", "బెల్ల"},
            {"మల్లెపూలు", "మల్లె"}, {"గాజులు", "గాజు"}, {"చీర", "చీర"}, {"టోపీ", "టోపీ"}, {"కుండ", "కుండ"}};

    /** {question, answer, words that count}. */
    private static final String[][] QUIZ = {
            {"నోవహు ఓడను ఎందుకు కట్టాడు?", "రాబోయే జలప్రళయం నుంచి తప్పించుకోవడానికి", "జలప్రళయ|ప్రళయ|వరద|నీళ్ల"},
            {"యేసు ప్రభువు ఏ ఊరిలో పుట్టారు?", "బేత్లెహేము", "బేత్లెహే|బెత్లెహే|bethlehem"},
            {"దావీదు రాయితో ఓడించిన పెద్ద మనిషి పేరు ఏమిటి?", "గొల్యాతు", "గొల్యా|goliath"},
            {"దానియేలును ఏ గుహలో వేశారు?", "సింహాల గుహ", "సింహ"},
            {"యోనాను ఏమి మింగింది?", "ఒక పెద్ద చేప", "చేప|తిమింగల"},
            {"యేసు ప్రభువుకి ఎంతమంది శిష్యులు?", "పన్నెండు మంది", "12|పన్నెండు"},
            {"మోషే ఇశ్రాయేలు ప్రజలను ఏ దేశం నుంచి బయటికి తీసుకొచ్చాడు?", "ఐగుప్తు", "ఐగుప్త|ఈజిప్ట్|egypt"},
            {"యేసు ఐదు రొట్టెలు, ఎన్ని చేపలతో ఐదు వేల మందికి తినిపించారు?", "రెండు చేపలు", "2|రెండు"},
            {"యేసు నీళ్లను ద్రాక్షారసంగా మార్చింది ఏ ఊరి పెళ్లిలో?", "కానా", "కానా"},
            {"రూతు అత్తగారి పేరు ఏమిటి?", "నయోమి", "నయోమి|naomi"},
            {"యేసు ప్రభువు ఎన్నవ రోజు మృతులలోనుండి లేచారు?", "మూడవ రోజు", "3|మూడ"},
            {"అబ్రాహాము కుమారుడి పేరు ఏమిటి?", "ఇస్సాకు", "ఇస్సా|isaac"},
            {"జక్కయ్య యేసును చూడటానికి ఏ చెట్టు ఎక్కాడు?", "మేడిచెట్టు", "మేడి"},
            {"యేసు ప్రభువు తల్లి పేరు ఏమిటి?", "మరియ", "మరియ|mary"},
            {"బైబిల్‌లో మొదటి పుస్తకం ఏది?", "ఆదికాండము", "ఆది|genesis"},
            {"లాజరును మళ్లీ బ్రతికించింది ఎవరు?", "యేసు ప్రభువు", "యేసు|ప్రభువు|jesus"},
            {"పది ఆజ్ఞలు దేవుడు ఎవరికి ఇచ్చారు?", "మోషేకి", "మోషే|moses"},
            {"ఆదాము భార్య పేరు ఏమిటి?", "హవ్వ", "హవ్వ|eve"},
            {"యోసేపుకి తండ్రి ఇచ్చిన ప్రత్యేకమైన బహుమతి ఏమిటి?", "రంగుల అంగీ", "అంగీ|చొక్కా|coat"},
            {"ఏలీయాకు అడవిలో రొట్టె, మాంసం ఏ పక్షులు తెచ్చాయి?", "కాకులు", "కాకి|కాకుల"}};

    /** Her words asking for a game, or not. */
    static boolean asks(String t) {
        return t.matches("(?s).*(ఆట ఆడ|ఆట ఆడదాం|ఆడుకుందాం|గేమ్|game|క్విజ్|quiz|బైబిల్ ప్రశ్న|జ్ఞాపకశక్తి|గుర్తుపెట్టుకునే ఆట).*")
                || t.trim().equals("ఆట");
    }

    /** The next game (or the one she named): what Jarvis says; sets the question waiting for her answer. */
    static String start(Context c, String said) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT);
        String w = HomeCare.who(c);
        int kind;
        if (t.matches("(?s).*(క్విజ్|quiz|బైబిల్ ప్రశ్న).*")) kind = 1;
        else if (t.matches("(?s).*(పొడుపు).*")) kind = 2;
        else if (t.matches("(?s).*(జ్ఞాపక|గుర్తుపెట్టుకునే).*")) kind = 0;
        else if (t.matches("(?s).*(పదాల|మాటల ఆట|అక్షరాల ఆట|పదాలాట).*")) return wordsStart(c);
        else kind = HomeCare.sp(c).getInt("game_next", 0) % 3;
        HomeCare.sp(c).edit().putInt("game_next", kind + 1).apply();
        if (kind == 2) return w + ", ఒక పొడుపు కథ. " + Sayings.riddle(c) + " జవాబు ఏమిటి?";
        if (kind == 1) {
            int i = HomeCare.sp(c).getInt("quiz_at", 0) % QUIZ.length;
            HomeCare.sp(c).edit().putInt("quiz_at", i + 1).apply();
            HomeCare.setPending("game:quiz:" + i);
            return w + ", బైబిల్ ప్రశ్న: " + QUIZ[i][0];
        }
        List<Integer> pick = new ArrayList<>();
        for (int i = 0; i < THINGS.length; i++) pick.add(i);
        Collections.shuffle(pick);
        StringBuilder say = new StringBuilder(), keep = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int i = pick.get(k);
            say.append(k == 0 ? "" : k == 3 ? ", ఇంకా " : ", ").append(THINGS[i][0]);
            keep.append(k == 0 ? "" : ",").append(i);
        }
        HomeCare.setPending("game:mem:" + keep);
        return w + ", గుర్తుపెట్టుకునే ఆట! ఈ నాలుగు గుర్తుపెట్టుకోండి: " + say + ". ఇప్పుడు చెప్పండి, నేను ఏమేమి చెప్పాను?";
    }

    // ================================================================ పదాల ఆట (a word from the last letter)

    /** Jarvis's words by their first letter (common things she knows). */
    private static final String[] WORDS = {
            "కమలం", "కలం", "కాకి", "కుర్చీ", "కోడి", "కొబ్బరి", "కిటికీ", "కంచం", "కత్తి", "కప్ప", "ఖర్జూరం",
            "గంట", "గులాబీ", "గుర్రం", "గాజు", "గొడుగు", "గడియారం", "గుడి", "గోధుమలు", "చెట్టు", "చిలుక", "చేప", "చీర",
            "చంద్రుడు", "చెంచా", "చెప్పులు", "చపాతీ", "జడ", "జామకాయ", "జింక", "జెండా", "జున్ను", "జాబిల్లి", "టమాటా",
            "టోపీ", "టపాసు", "డబ్బు", "డప్పు", "డబ్బా", "తాబేలు", "తామర", "తల", "తేనె", "తోట", "తాళం", "తబలా", "దీపం",
            "దువ్వెన", "దానిమ్మ", "దారం", "దోసకాయ", "దోశ", "ధనుస్సు", "ధాన్యం", "నక్షత్రం", "నెమలి", "నది", "నారింజ",
            "నిచ్చెన", "నాగలి", "పువ్వు", "పాలు", "పుస్తకం", "పిల్లి", "పండు", "పడవ", "పావురం", "పక్షి", "ఫలం",
            "బంతి", "బియ్యం", "బెల్లం", "బస్సు", "బల్ల", "బాతు", "బండి", "భూమి", "భోజనం", "భవనం", "మామిడి", "మల్లెపువ్వు",
            "మేక", "మంచం", "మిరపకాయ", "ముత్యం", "మట్టి", "యంత్రం", "రాయి", "రైలు", "రొట్టె", "రంగు", "రథం", "లడ్డు",
            "లంగా", "లోటా", "లవంగం", "వంకాయ", "వల", "వెన్న", "వేప", "వర్షం", "వీణ", "శంఖం", "శనగలు", "సైకిల్",
            "సూర్యుడు", "సముద్రం", "సబ్బు", "సంచి", "సీతాకోకచిలుక", "హంస", "హారం", "హల్వా"};
    /** Letters few words start with: then any word will do. */
    private static final String RARE = "ఙఞణళఱఠఢఝఛథషఴ";
    private static final java.util.Set<String> used = new java.util.HashSet<>();
    private static int wordsSaid, wordsMissed;

    static boolean teluguConsonant(char ch) { return ch >= 0x0C15 && ch <= 0x0C39; }

    private static boolean sign(char ch) {
        return (ch >= 0x0C3E && ch <= 0x0C4C) || ch == 0x0C01 || ch == 0x0C02 || ch == 0x0C03 || ch == 0x0C4D || ch == 0x0C55
                || ch == 0x0C56 || ch == 0x200C || ch == 0x200D;
    }

    /** The letter the next word must start with: the first consonant of the word's last syllable ("కమలం" → ల,
     *  "అమ్మ" → మ, "పక్షి" → క); 0 when the word ends in a vowel letter. */
    static char lastLetter(String word) {
        String w = word == null ? "" : word.trim();
        int i = w.length() - 1;
        while (i >= 0 && sign(w.charAt(i))) i--;
        if (i < 0 || !teluguConsonant(w.charAt(i))) return 0;
        while (i >= 2 && w.charAt(i - 1) == 0x0C4D && teluguConsonant(w.charAt(i - 2))) i -= 2; // (a joined letter: its first one)
        return w.charAt(i);
    }

    /** The first word in her answer (Telugu letters only), or "". */
    static String firstWord(String said) {
        if (said == null) return "";
        for (String p : said.trim().split("[\\s,.!?।]+")) {
            String q = p.replaceAll("[^\\u0C00-\\u0C7F\\u200C\\u200D]", "");
            if (!q.isEmpty()) return q;
        }
        return "";
    }

    /** Jarvis's word starting with this letter (not used yet in this game), or null. */
    static String wordFor(char letter, java.util.Random r) {
        List<String> ok = new ArrayList<>();
        for (String x : WORDS) if (x.charAt(0) == letter && !used.contains(x)) ok.add(x);
        return ok.isEmpty() ? null : ok.get(r.nextInt(ok.size()));
    }

    private static String ask(char letter) {
        if (letter == 0 || RARE.indexOf(letter) >= 0) {
            HomeCare.setPending("game:word:*");
            return (letter == 0 ? "" : "'" + letter + "' తో పదాలు తక్కువ, ") + "ఈసారి ఏ పదమైనా చెప్పండి.";
        }
        HomeCare.setPending("game:word:" + letter);
        return "'" + letter + "' తో మొదలయ్యే పదం చెప్పండి.";
    }

    static String wordsStart(Context c) {
        used.clear();
        wordsSaid = wordsMissed = 0;
        String w = HomeCare.who(c), first = WORDS[new java.util.Random().nextInt(WORDS.length)];
        used.add(first);
        return w + ", పదాల ఆట! నేను ఒక పదం చెబుతాను, దాని చివరి అక్షరంతో మొదలయ్యే పదం మీరు చెప్పాలి. నేను మొదలుపెడతాను: "
                + first + ". " + ask(lastLetter(first));
    }

    /** Her word in the words game. */
    private static String wordsAnswer(Context c, String pending, String said) {
        String w = HomeCare.who(c);
        String t = said == null ? "" : said.trim();
        if (t.matches("(?s).*(చాలు|ఆపు|ఆపేద్దాం|ఇక వద్దు|ముగిద్దాం).*")) {
            String r = wordsSaid > 0 ? "సరే " + w + "! ఈరోజు మీరు " + Game.num(Math.min(12, wordsSaid)) + (wordsSaid > 12 ? " కంటే ఎక్కువ" : "") + " పదాలు చెప్పారు. చాలా బాగుంది!"
                    : "సరే " + w + ", తర్వాత మళ్లీ ఆడదాం.";
            used.clear();
            return r;
        }
        String need = pending.substring("game:word:".length());
        char letter = need.equals("*") || need.isEmpty() ? 0 : need.charAt(0);
        java.util.Random rnd = new java.util.Random();
        boolean giveUp = t.matches("(?s).*(తెలియదు|తెలీదు|రావడం లేదు|గుర్తు రావడం లేదు|నువ్వే చెప్పు|నువ్వు చెప్పు).*");
        String hers = firstWord(t);
        if (!giveUp && hers.isEmpty()) { HomeCare.setPending(pending); return "వినిపించలేదు " + w + ". " + (letter == 0 ? "ఏదైనా ఒక పదం చెప్పండి." : "'" + letter + "' తో ఒక పదం చెప్పండి."); }
        if (!giveUp && letter != 0 && hers.charAt(0) != letter) {
            wordsMissed++;
            if (wordsMissed < 2) {
                HomeCare.setPending(pending);
                String eg = wordFor(letter, rnd);
                return "అయ్యో, '" + letter + "' తో మొదలవ్వాలి " + w + "." + (eg != null ? " ఉదాహరణకి " + eg + "." : "") + " మళ్లీ ప్రయత్నించండి.";
            }
            giveUp = true;
        }
        if (giveUp) { // Jarvis says one for her and goes on from it
            wordsMissed = 0;
            String eg = letter == 0 ? WORDS[rnd.nextInt(WORDS.length)] : wordFor(letter, rnd);
            if (eg == null) { used.clear(); return "నాకూ '" + letter + "' తో పదం గుర్తు రావడం లేదు! కొత్తగా మొదలుపెడదాం. " + wordsStart(c); }
            used.add(eg);
            char nx = lastLetter(eg);
            String mine = nx == 0 || RARE.indexOf(nx) >= 0 ? null : wordFor(nx, rnd);
            if (mine == null) return "పర్వాలేదు " + w + ", " + eg + " అనొచ్చు. " + ask(nx);
            used.add(mine);
            return "పర్వాలేదు " + w + ", " + eg + " అనొచ్చు. ఇప్పుడు నేను: " + mine + ". " + ask(lastLetter(mine));
        }
        wordsMissed = 0;
        if (used.contains(hers)) { HomeCare.setPending(pending); return hers + " ఇప్పటికే వచ్చింది " + w + ", వేరే పదం చెప్పండి."; }
        used.add(hers);
        wordsSaid++;
        char nx = lastLetter(hers);
        String praise = wordsSaid % 5 == 0 ? "అద్భుతం! మీరు ఇప్పటికే " + Game.num(Math.min(12, wordsSaid)) + " పదాలు చెప్పారు! " : new String[]{"సరిగ్గా! ", "భలే! ", "బాగుంది! ", "చాలా బాగుంది! "}[rnd.nextInt(4)];
        if (nx == 0 || RARE.indexOf(nx) >= 0) {
            String any = WORDS[rnd.nextInt(WORDS.length)];
            used.add(any);
            return praise + hers + ". దీని చివరి అక్షరంతో పదాలు తక్కువ, నేను " + any + " అంటాను. " + ask(lastLetter(any));
        }
        String mine = wordFor(nx, rnd);
        if (mine == null) {
            HomeCare.setPending("game:word:*");
            return praise + hers + ". అయ్యో, నాకు '" + nx + "' తో పదం గుర్తు రావడం లేదు! ఈ రౌండ్ మీరే గెలిచారు " + w + "! ఇప్పుడు ఏ పదమైనా చెప్పండి, మళ్లీ మొదలుపెడదాం.";
        }
        used.add(mine);
        return praise + hers + ". ఇప్పుడు నా వంతు: '" + nx + "' తో… " + mine + ". " + ask(lastLetter(mine));
    }

    /** Her answer to the waiting game question: what Jarvis says (never null). */
    static String answer(Context c, String pending, String said) {
        if (pending.startsWith("game:word:")) return wordsAnswer(c, pending, said);
        String w = HomeCare.who(c), t = said == null ? "" : said.toLowerCase(Locale.ROOT).replace("‌", "");
        if (pending.startsWith("game:quiz:")) {
            int i;
            try { i = Integer.parseInt(pending.substring(10)); } catch (Exception e) { return "సరే " + w + "."; }
            if (i < 0 || i >= QUIZ.length) return "సరే " + w + ".";
            boolean right = false;
            for (String k : QUIZ[i][2].split("\\|")) if (!k.isEmpty() && t.contains(k.toLowerCase(Locale.ROOT))) right = true;
            return (right ? "సరిగ్గా చెప్పారు " + w + "! జవాబు: " : "పర్వాలేదు " + w + ". జవాబు: ") + QUIZ[i][1]
                    + ". ఇంకో ప్రశ్న కావాలంటే \"క్విజ్\" అనండి.";
        }
        if (pending.startsWith("game:mem:")) {
            String[] idx = pending.substring(9).split(",");
            int got = 0;
            StringBuilder missed = new StringBuilder();
            for (String s : idx) {
                int i;
                try { i = Integer.parseInt(s.trim()); } catch (Exception e) { continue; }
                if (i < 0 || i >= THINGS.length) continue;
                if (t.contains(THINGS[i][1])) got++;
                else missed.append(missed.length() == 0 ? "" : ", ").append(THINGS[i][0]);
            }
            if (got == idx.length) return "అద్భుతం " + w + "! నాలుగూ సరిగ్గా చెప్పారు! మీ జ్ఞాపకశక్తి చాలా బాగుంది.";
            if (got == 0) return "పర్వాలేదు " + w + ", మళ్లీ ఆడదాం. నేను చెప్పినవి: " + missed + ".";
            return idx.length + " లో " + got + " చెప్పారు " + w + ", చాలా బాగుంది! మర్చిపోయినవి: " + missed + ".";
        }
        return "సరే " + w + ".";
    }

    /** (desk tests) the memory game's things. */
    static String thing(int i) { return THINGS[i][0]; }
    static int things() { return THINGS.length; }
    static int quizzes() { return QUIZ.length; }
}
