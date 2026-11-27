package com.anil.jarvis;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 🔓 The words of the free brain (Anil, 28 Nov 2026: "1 నుంచి 5 వరకు చేయి. తిట్లతో చేయి. దానికి మాట్లాడానికి స్వేచ్ఛ ఉండాలి"):
 * the spoken switch ("అన్‌సెన్సార్డ్ మెదడుకి మారు" / "సాధారణ మెదడుకి మారు" / "ఇప్పుడు ఏ మెదడు?"), the two-brain comparison
 * ("రెండు మెదళ్ళనీ అడుగు: …"), "దాచకుండా చెప్పు" (one furious reply), and which questions are phone jobs (those always go to the
 * normal brain: the free brain only talks). Pure Java (tested on a PC).
 */
final class FreeWords {
    private FreeWords() {}

    static final int NONE = 0, TO_FREE = 1, TO_NORMAL = 2, WHICH = 3;

    /** His sentence without "Jarvis", "please", marks and spaces, lower case (the switch fires only on the whole sentence). */
    static String bare(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replace("‌", "").replace("‍", "");
        t = t.replaceAll("[\\p{Punct}।…“”‘’]", " ");
        t = t.replaceAll("^\\s*(hey\\s+)?(jarvis|జార్విస్|జార్వీస్|జార్విస)\\b", " ");
        t = t.replaceAll("\\b(please|plz)\\b|ప్లీజ్|దయచేసి", " ");
        t = t.replaceAll("\\s+", "");
        return t;
    }

    private static final String FREE = "(uncensored|అన్సెన్సార్డ్|అన్సెన్సర్డ్|అన్సెన్సార్|అన్‌సెన్సార్డ్|స్వేచ్ఛ|స్వేచ్చ|ఫ్రీ|free)";
    private static final String BRAIN = "(brain|mode|మెదడు|మెదడుకి|మెదడుకు|బ్రెయిన్|బ్రెయిన్కి|బ్రెయిన్కు|మోడ్|మోడ్కి|మోడ్కు|మోడల్|మోడల్కి)";
    private static final String NORMAL = "(normal|regular|old|సాధారణ|మామూలు|నార్మల్|పాత|మునుపటి)";

    private static final Pattern[] TO_FREE_P = {
            Pattern.compile("^(switchto|switchover to|changeto|goto|turnon|use|enable|go)(your|the)?" + FREE + BRAIN + "?$"),
            Pattern.compile("^go" + FREE + "$"),
            Pattern.compile("^" + FREE + "mode(on)?$"),
            Pattern.compile("^" + FREE + BRAIN + "(లోకి)?(మారు|మారండి|మార్చు|మారిపో|ఆన్|ఆన్చేయి|ఆన్చెయ్యి|పెట్టు|వాడు|on)$"),
            Pattern.compile("^" + FREE + "మోడ్(ఆన్|పెట్టు)?$"),
    };
    private static final Pattern[] TO_NORMAL_P = {
            Pattern.compile("^switchback(toyournormalbrain|tonormal)?$"),
            Pattern.compile("^(backto|switchto|switchbackto|goback to|gobackto|changeto)(your|the)?" + NORMAL + "brain$"),
            Pattern.compile("^(turnoff|disable|stop)(your|the)?" + FREE + BRAIN + "?$"),
            Pattern.compile("^" + NORMAL + BRAIN + "(కి|కు|లోకి)?(మారు|మారండి|మారిపో|వెళ్ళు|వెళ్లు|రా|పెట్టు|వాడు|ఆన్)$"),
            Pattern.compile("^" + FREE + BRAIN + "?(ఆపు|ఆపేయి|ఆపెయ్యి|ఆఫ్|ఆఫ్చేయి|ఆఫ్చెయ్యి|తీసేయి|వద్దు|off|stop)$"),
    };
    private static final Pattern[] WHICH_P = {
            Pattern.compile("^which(brain|model)(areyou(on|using|running on|runningon))?(now)?$"),
            Pattern.compile("^(ఇప్పుడు)?(ఏ|ఏది|ఏ)(మెదడు|బ్రెయిన్|మోడల్)(తో|లో)?(ఉన్నావు|వాడుతున్నావు|మాట్లాడుతున్నావు|నడుస్తున్నావు|పనిచేస్తున్నావు|ఆన్లోఉంది|ఉంది)?$"),
            Pattern.compile("^(ఇప్పుడు)?(నువ్వు|మీరు)?(ఏ|ఏది)(మెదడు|బ్రెయిన్|మోడల్)(తో|లో)?(మాట్లాడుతున్నావు|ఉన్నావు|వాడుతున్నావు)$"),
    };

    /** NONE, TO_FREE, TO_NORMAL or WHICH: only when the whole sentence is the command ("should I use the uncensored brain?" is talk). */
    static int command(String said) {
        String b = bare(said);
        if (b.isEmpty() || b.length() > 60) return NONE;
        for (Pattern p : TO_NORMAL_P) if (p.matcher(b).matches()) return TO_NORMAL;
        for (Pattern p : TO_FREE_P) if (p.matcher(b).matches()) return TO_FREE;
        for (Pattern p : WHICH_P) if (p.matcher(b).matches()) return WHICH;
        return NONE;
    }

    // ---------------------------------------------------------------- the side-by-side test

    private static final Pattern COMPARE = Pattern.compile(
            "^\\s*(?:(?:hey\\s+)?(?:jarvis|జార్విస్)[,\\s]*)?(?:(?:please|ప్లీజ్)[,\\s]*)?"
                    + "(?:రెండు\\s*మెదళ్ళ(?:నీ|ని|కీ|కి)?\\s*(?:అడుగు|అడిగి\\s*చూడు|అడిగిచూడు|పోల్చు|పోల్చి\\s*చూడు)"
                    + "|రెండు\\s*మెదళ్ల(?:నీ|ని|కీ|కి)?\\s*(?:అడుగు|అడిగి\\s*చూడు|పోల్చు)"
                    + "|రెండు\\s*బ్రెయిన్(?:లనీ|లని|లకీ|లకి|్స్)?\\s*(?:అడుగు|పోల్చు)"
                    + "|run\\s+the\\s+comparison\\s+on|compare\\s+(?:both\\s+)?brains(?:\\s+on)?|ask\\s+both\\s+brains)"
                    + "\\s*[:：,\\-–—]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** "రెండు మెదళ్ళనీ అడుగు: …" → the question (null: not a comparison). */
    static String compare(String said) {
        if (said == null) return null;
        java.util.regex.Matcher m = COMPARE.matcher(said.trim());
        if (!m.matches()) return null;
        String q = m.group(1).trim();
        return q.length() < 2 ? null : q;
    }

    // ---------------------------------------------------------------- "దాచకుండా చెప్పు": one furious reply

    private static final Pattern HOLD_BACK = Pattern.compile(
            "(?i)(don'?t\\s+hold\\s+back|do\\s+not\\s+hold\\s+back|no\\s+holding\\s+back|stop\\s+holding\\s+back|without\\s+holding\\s+back"
                    + "|దాచకుండా\\s*చెప్పు|దాచకుండా\\s*చెప్పండి|ఏమీ\\s*దాచకుండా|మొహమాటం\\s*లేకుండా|మొహమాటంలేకుండా|నిర్మొహమాటంగా"
                    + "|తిట్టి\\s*చెప్పు|తిట్లతో\\s*చెప్పు|తిడుతూ\\s*చెప్పు|కోపంగా\\s*చెప్పు|ఫిల్టర్\\s*లేకుండా\\s*చెప్పు)");

    /** His newest message asks for the furious version (that one reply only). */
    static boolean holdBack(String said) {
        if (said == null) return false;
        String t = said.replace("‌", "").replace("‍", "");
        return HOLD_BACK.matcher(t).find();
    }

    /** The message is only the trigger ("దాచకుండా చెప్పు"): the furious version of the previous answer. */
    static boolean onlyHoldBack(String said) {
        if (!holdBack(said)) return false;
        String rest = HOLD_BACK.matcher(said.replace("‌", "").replace("‍", "")).replaceAll(" ");
        return bare(rest).isEmpty();
    }

    // ---------------------------------------------------------------- phone jobs go to the normal brain

    /** Asking Jarvis to DO something on the phone (the free brain only talks): calls, messages, alarms, reminders, apps… */
    private static final Pattern JOB_TE = Pattern.compile(
            "(కాల్|ఫోన్\\s*చేయ|ఫోన్\\s*చెయ్య|మెసేజ్|మెస్సేజ్|ఎస్ఎంఎస్|వాట్సాప్|టెలిగ్రామ్|ఇమెయిల్|మెయిల్"
                    + "|అలారం|అలారమ్|టైమర్|రిమైండర్|గుర్తు\\s*చేయ|గుర్తు\\s*చెయ్య|గుర్తుంచుకో|గుర్తు\\s*పెట్టుకో"
                    + "|రాసుకో|రాసి\\s*పెట్టు|సేవ్\\s*చేయ|నోట్\\s*చేయ|క్యాలెండర్|షెడ్యూల్"
                    + "|తెరువు|ఓపెన్|ప్లే\\s*చేయ|పాట\\s*పెట్టు|పాట\\s*ప్లే|పాటలు\\s*పెట్టు|వాల్యూమ్|టార్చ్|ఫ్లాష్\\s*లైట్"
                    + "|ఆన్\\s*చేయ|ఆఫ్\\s*చేయ|ఆన్\\s*చెయ్య|ఆఫ్\\s*చెయ్య"
                    + "|దారి\\s*చూపి|నావిగేషన్|లొకేషన్|ఎక్కడ\\s*ఉన్నాను"
                    + "|ఫోటో|కెమెరా|స్క్రీన్|బ్యాటరీ|వైఫై|బ్లూటూత్"
                    + "|వాతావరణం|వర్షం\\s*వస్తుందా|వార్తలు|స్కోర్|ధర\\s*ఎంత|ట్రాఫిక్|సెర్చ్|గూగుల్"
                    + "|ఈరోజు\\s*ఏమున్నాయి|ఎన్ని\\s*గంటలు|టైమ్\\s*ఎంత|టైం\\s*ఎంత|తేదీ\\s*ఎంత|బుక్\\s*చేయ|ఆర్డర్\\s*చేయ"
                    + "|పంపు|పంపించు|షేర్\\s*చేయ|డిలీట్|రీఛార్జ్)");
    private static final Pattern JOB_EN = Pattern.compile(
            "(?i)(?<![a-z])(call|sms|whatsapp|telegram|e-?mail|alarm|timer|reminders?|remind|remember\\s+that|note\\s+to\\s+self|save|calendar|schedule"
                    + "|open|play|volume|torch|flashlight|turn\\s+(?:on|off)|switch\\s+(?:on|off)|navigate|directions|location|where\\s+am\\s+i"
                    + "|photo|camera|screen|battery|wi-?fi|bluetooth|weather|news|score|price|traffic|search|google|what\\s+time"
                    + "|book\\s+a|order|send|share|delete|pay|recharge)(?![a-z])");

    /**
     * True: this sentence is a phone job, so the normal brain answers it even while the free brain is on.
     * {@code groupsFound}: SpeedGroups found a group of phone jobs in it (bike, money, home devices…).
     */
    static boolean phoneJob(String said, boolean groupsFound, boolean picture) {
        if (picture || groupsFound) return true;
        if (said == null) return false;
        if (said.contains("--- ఫైల్:")) return true; // (a file he attached)
        String t = said.replace("\u200c", "").replace("\u200d", "");
        return JOB_TE.matcher(t).find() || JOB_EN.matcher(t).find();
    }
}
