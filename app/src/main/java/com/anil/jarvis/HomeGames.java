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

    /** Her answer to the waiting game question: what Jarvis says (never null). */
    static String answer(Context c, String pending, String said) {
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
