package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The words of Jarvis's own Telugu ears (TeluguEars) without internet. Its small Telugu model hears any Telugu; a second
 * listener on the same sound knows only what Jarvis can do offline (the phrases below: Vosk learns from them which word
 * follows which), with [unk] where his own words go (a name, a note). The two are put together by pick(). Pure (tested
 * on the desk).
 */
final class TeluguWords {
    private TeluguWords() {}

    static final String UNK = "[unk]";

    /** One word heard: when (seconds from the start of the listen; -1 not known) and how sure (0..1). */
    static final class W {
        final String word;
        final double start, end, conf;

        W(String word, double start, double end, double conf) {
            this.word = word;
            this.start = start;
            this.end = end;
            this.conf = conf;
        }

        double mid() { return (start + end) / 2; }

        @Override public String toString() { return word; }
    }

    static boolean unk(String w) { return w == null || w.isEmpty() || w.equals(UNK) || w.equals("<unk>"); }

    /** A Vosk result with word times ({"result":[{"word","start","end","conf"}],"text"}); the text alone when it has none. */
    static List<W> parse(String json) {
        List<W> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray r = o.optJSONArray("result");
            if (r != null) {
                for (int i = 0; i < r.length(); i++) {
                    JSONObject x = r.optJSONObject(i);
                    if (x == null) continue;
                    String w = x.optString("word", "").trim();
                    if (!w.isEmpty()) out.add(new W(w, x.optDouble("start", -1), x.optDouble("end", -1), x.optDouble("conf", 1)));
                }
            } else {
                String t = o.optString("text", "").trim();
                if (!t.isEmpty()) for (String w : t.split("\\s+")) out.add(new W(w, -1, -1, 1));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** The words so far of a Vosk partial result ({"partial": "..."}), without [unk]. */
    static String partial(String json) {
        String p;
        try { p = json == null ? "" : new JSONObject(json).optString("partial", "").trim(); } catch (Exception e) { return ""; }
        StringBuilder b = new StringBuilder();
        for (String w : p.split("\\s+")) if (!unk(w)) b.append(b.length() == 0 ? "" : " ").append(w);
        return b.toString();
    }

    /** The words, without [unk]. */
    static String text(List<W> l) {
        StringBuilder b = new StringBuilder();
        for (W w : l) if (!unk(w.word)) b.append(b.length() == 0 ? "" : " ").append(w.word);
        return b.toString();
    }

    /** A command word below this sureness, where the free listener clearly heard something else: the free listener's words. */
    static final double UNSURE = 0.55, CLEAR = 0.75;

    /**
     * What he said, from the two listeners: free (the model's own Telugu) and cmd (the same sound against Jarvis's offline
     * phrases). The command listener's words are used; where it heard a word it doesn't know ([unk]), a person (a call or a
     * message must never go to someone he didn't say: the free listener's word then goes to the contact lookup, which
     * asks when unsure), or a word it wasn't sure of that the free listener heard clearly, the free listener's words for
     * that moment go in. Words only the free listener heard (in a gap) are kept in their place, unless unsure at the edges.
     */
    static String pick(List<W> free, List<W> cmd) {
        List<W> f = new ArrayList<>();
        for (W w : free) if (!unk(w.word)) f.add(w);
        boolean anyCmd = false;
        for (W w : cmd) if (!unk(w.word)) anyCmd = true;
        if (!anyCmd) return text(f);
        if (f.isEmpty()) return text(cmd);
        boolean timed = true;
        for (W w : f) if (w.start < 0 || w.end < w.start) timed = false;
        for (W w : cmd) if (w.start < 0 || w.end < w.start) timed = false;
        if (!timed) { // (no word times: the two can't be lined up) the command words, unless it missed some
            for (W w : cmd) if (unk(w.word) || PEOPLE_WORDS.contains(w.word)) return text(f);
            return text(cmd);
        }
        // each free word goes with the command word it overlaps most (the same word, if it touches one), when the
        // command words cover most of it; -1: a word of its own, in a gap
        int[] owner = new int[f.size()];
        for (int i = 0; i < f.size(); i++) {
            W fi = f.get(i);
            owner[i] = -1;
            double best = 0, total = 0, same = 0;
            int sameAt = -1;
            for (int c = 0; c < cmd.size(); c++) {
                double o = Math.min(fi.end, cmd.get(c).end) - Math.max(fi.start, cmd.get(c).start);
                if (o <= 1e-9) continue;
                total += o;
                if (o > best) { best = o; owner[i] = c; }
                if (cmd.get(c).word.equals(fi.word) && o > same) { same = o; sameAt = c; }
            }
            if (sameAt >= 0) owner[i] = sameAt; // "కి" heard by both: one word
            else if (owner[i] >= 0 && total < 0.5 * (fi.end - fi.start)) owner[i] = -1;
        }
        // the pieces in time order: {start, words, from the free listener, its sureness}
        List<Object[]> pieces = new ArrayList<>();
        double firstCmd = Double.MAX_VALUE, lastCmd = -1;
        for (int c = 0; c < cmd.size(); c++) {
            W g = cmd.get(c);
            List<W> mine = new ArrayList<>();
            for (int i = 0; i < f.size(); i++) if (owner[i] == c) mine.add(f.get(i));
            String heard = text(mine), said;
            boolean fromFree;
            if (unk(g.word)) {
                said = heard;
                fromFree = true;
            } else if (!mine.isEmpty() && !heard.equals(g.word) && !has(mine, g.word) && (PEOPLE_WORDS.contains(g.word)
                    || NUMBER_WORDS.contains(g.word) && sure(mine) > g.conf + 0.1
                    || g.conf < UNSURE && sure(mine) >= Math.max(CLEAR, g.conf + 0.2))) {
                // a person (never forced), a number the free listener was surer of, or a forced fit: its word here
                said = heard;
                fromFree = true;
            } else {
                said = g.word;
                fromFree = false;
            }
            if (said.isEmpty()) continue;
            pieces.add(new Object[]{g.start, said, fromFree, 1.0});
            firstCmd = Math.min(firstCmd, g.start);
            lastCmd = Math.max(lastCmd, g.end);
        }
        for (int i = 0; i < f.size(); i++) {
            if (owner[i] >= 0) continue;
            W fi = f.get(i);
            boolean edge = fi.end <= firstCmd || fi.start >= lastCmd;
            if (edge && fi.conf < EDGE_UNSURE) continue; // a stray sound before or after his command ("… చెయ్యి ఆ")
            pieces.add(new Object[]{fi.start, fi.word, Boolean.TRUE, fi.conf});
        }
        pieces.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0])); // (stable: equal starts keep their order)
        StringBuilder b = new StringBuilder();
        String lastFree = null;
        for (Object[] p : pieces) {
            String s = (String) p[1];
            boolean fromFree = (Boolean) p[2];
            // "అమ్మకి" from the free listener and then the command listener's own "కి" for the same sound: once
            if (!fromFree && lastFree != null && lastFree.endsWith(s) && s.length() <= 3) { lastFree = null; continue; }
            b.append(b.length() == 0 ? "" : " ").append(s);
            String[] ws = s.split("\\s+");
            lastFree = fromFree ? ws[ws.length - 1] : null;
        }
        return b.toString();
    }

    /** A word only the free listener heard, before or after his command, below this sureness: a stray sound. */
    static final double EDGE_UNSURE = 0.5;

    private static boolean has(List<W> l, String word) {
        for (W w : l) if (w.word.equals(word)) return true;
        return false;
    }

    private static double sure(List<W> l) {
        double s = 0;
        for (W w : l) s += w.conf;
        return l.isEmpty() ? 0 : s / l.size();
    }

    // ================================================================ what the command listener knows

    private static final String U = UNK;

    /** Hours (and the "కి" forms said for a time). */
    private static final String[] HOURS = {"ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది", "పదకొండు", "పన్నెండు"};
    private static final String[] HOURS_KI = {"ఒకటికి", "రెండుకి", "మూడుకి", "నాలుగుకి", "ఐదుకి", "ఆరుకి", "ఏడుకి", "ఎనిమిదికి", "తొమ్మిదికి", "పదికి",
            "పదకొండుకి", "పన్నెండుకి"};
    private static final String[] HALVES = {"ఒకటిన్నర", "రెండున్నర", "మూడున్నర", "నాలుగున్నర", "ఐదున్నర", "ఆరున్నర", "ఏడున్నర", "ఎనిమిదిన్నర",
            "తొమ్మిదిన్నర", "పదిన్నర", "పదకొండున్నర", "పన్నెండున్నర"};
    private static final String[] NUMBERS = {"సున్నా", "ఒకటి", "ఒక", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది",
            "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు", "పద్దెనిమిది", "పందొమ్మిది", "ఇరవై", "ముప్పై", "నలభై",
            "యాభై", "అరవై", "డెబ్బై", "ఎనభై", "తొంభై", "వంద", "వందలు", "వందల", "నూట", "వెయ్యి", "వేలు", "వేల", "లక్ష", "లక్షలు", "రూపాయలు", "రూపాయి",
            "శాతం", "అర", "అరగంట", "పావుగంట", "గంటన్నర"};
    private static final String[] MINUTES = {"ఐదు", "పది", "పదిహేను", "ఇరవై", "ఇరవై ఐదు", "ముప్పై", "ముప్పై ఐదు", "నలభై", "నలభై ఐదు", "యాభై", "యాభై ఐదు"};
    private static final String[] DAY_PARTS = {"ఉదయం", "పొద్దున", "మధ్యాహ్నం", "సాయంత్రం", "రాత్రి"};
    private static final String[] DAYS = {"ఈరోజు", "ఇవాళ", "రేపు", "ఎల్లుండి", "నిన్న", "మొన్న", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం",
            "శుక్రవారం", "శనివారం", "ఆదివారం", "ప్రతిరోజూ", "రోజూ"};
    private static final String[] PEOPLE = {"అమ్మ", "నాన్న", "అక్క", "అన్న", "అన్నయ్య", "తమ్ముడు", "చెల్లి", "చెల్లెలు", "మామయ్య", "అత్తయ్య",
            "బాబాయ్", "పిన్ని", "తాతయ్య", "అమ్మమ్మ", "నానమ్మ", "బావ", "వదిన", "ఆవిడ", "భార్య", "ఫ్రెండ్"};
    private static final String[] PEOPLE_KI = {"అమ్మకి", "నాన్నకి", "అక్కకి", "అన్నకి", "అన్నయ్యకి", "తమ్ముడికి", "చెల్లికి", "మామయ్యకి",
            "అత్తయ్యకి", "బాబాయ్ కి", "బావకి", "వదినకి", "ఆవిడకి"};
    /** The people words (a call or a message goes to them). */
    private static final Set<String> PEOPLE_WORDS = new java.util.HashSet<>();
    /** The number words (a time, an amount). */
    private static final Set<String> NUMBER_WORDS = new java.util.HashSet<>();
    static {
        for (String p : PEOPLE) PEOPLE_WORDS.add(p);
        for (String p : PEOPLE_KI) PEOPLE_WORDS.add(p.split(" ")[0]);
        for (String[] l : new String[][]{HOURS, HOURS_KI, HALVES, NUMBERS, MINUTES}) for (String n : l) for (String x : n.split(" ")) NUMBER_WORDS.add(x);
    }
    private static final String[] THINGS = {"పాలు", "పెరుగు", "బియ్యం", "నూనె", "పప్పు", "ఉప్పు", "చక్కెర", "కూరగాయలు", "ఉల్లిపాయలు", "టమాటా",
            "గుడ్లు", "బ్రెడ్", "సబ్బు", "టీ", "కాఫీ", "పండ్లు", "మందులు", "పెట్రోల్", "డీజిల్", "భోజనం", "టిఫిన్", "బస్సు", "ఆటో", "రీఛార్జ్",
            "కరెంట్ బిల్లు", "అద్దె"};

    /** Whole phrases as he says them (which word follows which). */
    private static final String[] PHRASES = {
            // switches
            "టార్చ్ ఆన్ చెయ్యి", "టార్చ్ ఆఫ్ చెయ్యి", "టార్చ్ ఆన్ చేయి", "టార్చ్ ఆఫ్ చేయి", "టార్చ్ వెయ్యి", "టార్చ్ ఆపు", "టార్చ్ లైట్ ఆన్ చెయ్యండి",
            "ఫ్లాష్ లైట్ ఆన్ చెయ్యి", "లైట్ ఆఫ్ చెయ్యి", "బ్లూటూత్ ఆన్ చెయ్యి", "బ్లూటూత్ ఆఫ్ చెయ్యి", "వైఫై ఆన్ చెయ్యి", "వైఫై ఆఫ్ చెయ్యి",
            "మొబైల్ డేటా ఆన్ చెయ్యి", "మొబైల్ డేటా ఆఫ్ చెయ్యి", "నెట్ ఆన్ చెయ్యి", "లొకేషన్ ఆన్ చెయ్యి", "లొకేషన్ ఆఫ్ చెయ్యి", "జీపీఎస్ ఆన్ చెయ్యి",
            "హాట్ స్పాట్ ఆన్ చెయ్యి", "హాట్ స్పాట్ ఆఫ్ చెయ్యి", "ఫ్లైట్ మోడ్ ఆన్ చెయ్యి", "ఫ్లైట్ మోడ్ ఆఫ్ చెయ్యి", "ఆటో రొటేట్ ఆన్ చెయ్యి",
            "ఆటో రొటేట్ ఆఫ్ చెయ్యి", "డు నాట్ డిస్టర్బ్ ఆన్ చెయ్యి", "డిస్టర్బ్ ఆఫ్ చెయ్యి", "బ్యాటరీ సేవర్ ఆన్ చెయ్యి", "బ్రైట్నెస్ తగ్గించు",
            "బ్రైట్నెస్ పెంచు", "బ్రైట్నెస్ ఫుల్ చెయ్యి", "బ్రైట్నెస్ యాభై చెయ్యి", "వెలుతురు తగ్గించు", "వాల్యూమ్ పెంచు", "వాల్యూమ్ తగ్గించు",
            "సౌండ్ పెంచు", "సౌండ్ తగ్గించు", "ఫోన్ సైలెంట్ లో పెట్టు", "సైలెంట్ చెయ్యి", "వైబ్రేట్ లో పెట్టు", "వైబ్రేట్ చెయ్యి",
            // battery, time
            "బ్యాటరీ ఎంత ఉంది", "బ్యాటరీ ఎంత", "ఛార్జ్ ఎంత ఉంది", "ఫోన్ ఛార్జ్ ఎంత", "బండి బ్యాటరీ ఎంత", "టైమ్ ఎంత", "టైం ఎంత", "టైమ్ ఎంత అయింది",
            "ఇప్పుడు టైమ్ ఎంత", "సమయం ఎంత", "ఎన్ని గంటలు అయింది", "టైమ్ చెప్పు", "ఈరోజు తేదీ ఎంత", "తేదీ ఏంటి", "ఈరోజు ఏం వారం",
            // alarm, timer
            "అలారం పెట్టు", "ఉదయం ఆరు గంటలకి అలారం పెట్టు", "రేపు ఉదయం ఐదు గంటలకి అలారం పెట్టు", "ఐదున్నరకి అలారం పెట్టు", "ఆరుకి అలారం పెట్టండి",
            "ఉదయం ఐదు ముప్పైకి అలారం", "ఏడు గంటలకు అలారం సెట్ చెయ్యి", "ఒంటి గంటకి అలారం పెట్టు", "ఒంటిగంటకి", "పది నిమిషాల టైమర్ పెట్టు", "ఐదు నిమిషాలు టైమర్ పెట్టు", "అరగంట టైమర్ పెట్టు",
            "ఒక గంట టైమర్ పెట్టు", "ముప్పై సెకన్ల టైమర్", "పదిహేను నిమిషాల టైమర్",
            // reminders
            "రేపు ఉదయం ఆరు గంటలకి " + U + " అని గుర్తు చెయ్యి", "సాయంత్రం ఆరు గంటలకి " + U + " " + U + " గుర్తు చేయి",
            "పది నిమిషాల తర్వాత " + U + " అని గుర్తు చెయ్యి", "అరగంట తర్వాత గుర్తు చెయ్యి", "గంట తర్వాత " + U + " గుర్తు చెయ్యి",
            "రేపు " + U + " " + U + " అని గుర్తు చెయ్యండి", "రాత్రి తొమ్మిది గంటలకి " + U + " గుర్తు చెయ్యి", "పాలు తేవాలని గుర్తు చెయ్యి",
            "బ్యాంక్ వెళ్ళాలని గుర్తు చెయ్యి", "కరెంట్ బిల్లు కట్టాలని గుర్తు చెయ్యి",
            "రోజూ ఉదయం ఎనిమిది గంటలకి " + U + " మాత్ర వేసుకోవాలని గుర్తు చెయ్యి", "ప్రతిరోజూ రాత్రి తొమ్మిది గంటలకి మందు వేసుకోవాలని గుర్తు చెయ్యి",
            "బీపీ మాత్ర", "షుగర్ మాత్ర", "రిమైండర్లు చెప్పు", "రిమైండర్ పెట్టు",
            // calls
            U + " కి కాల్ చెయ్యి", U + " కి ఫోన్ చెయ్యి", U + " కి కాల్ చేయి", U + " కి ఫోన్ చేయండి", U + " " + U + " కి కాల్ కలుపు",
            "అమ్మకి కాల్ చెయ్యి", "నాన్నకి ఫోన్ చెయ్యి", "అక్కకి కాల్ చెయ్యి", "అన్నకి కాల్ చెయ్యి", "అన్నయ్యకి ఫోన్ చెయ్యి", "తమ్ముడికి ఫోన్ చెయ్యి",
            "చెల్లికి కాల్ చేయి", "మామయ్యకి కాల్ చెయ్యి", "బావకి కాల్ చెయ్యి", "వదినకి కాల్ చెయ్యి", "ఆవిడకి కాల్ చెయ్యి",
            "అవును", "అవును చెయ్యి", "కాదు", "వద్దు", "సరే", "పంపు", "పంపండి", "పంపించు", "క్యాన్సిల్",
            // emergencies, his location
            "ఆపద", "ఆపదలో ఉన్నాను", "కాపాడు", "కాపాడండి", "సహాయం కావాలి", "హెల్ప్", "ఎమర్జెన్సీ", "అంబులెన్స్", "అంబులెన్స్ కి కాల్ చెయ్యి",
            "అంబులెన్స్ పిలువు", "పోలీస్", "పోలీస్ కి కాల్ చెయ్యి", "నూట ఎనిమిది కి కాల్ చెయ్యి", "నూట పన్నెండు కి కాల్ చెయ్యి",
            "నా లొకేషన్ పంపు", "అమ్మకి నా లొకేషన్ పంపు", U + " కి నా లొకేషన్ పంపు", "నా లొకేషన్ " + U + " కి పంపు", "లొకేషన్ షేర్ చెయ్యి",
            "నేను బాగున్నాను", "బాగున్నాను",
            // messages he sends
            U + " కి " + U + " " + U + " అని మెసేజ్ పంపు", "అమ్మకి నేను వస్తున్నా అని మెసేజ్ పంపు", U + " కి " + U + " అని ఎస్ఎంఎస్ పంపు",
            U + " కి " + U + " " + U + " " + U + " అని మెసేజ్ పెట్టు", "నేను వస్తున్నాను", "ఇంటికి వస్తున్నా", "లేట్ అవుతుంది",
            // expenses, money
            "పెట్రోల్ కి రెండు వందలు ఖర్చు రాయి", U + " కి ఐదు వందలు ఖర్చు రాయి", "పాలు కి యాభై రూపాయలు ఖర్చు రాయి", "కూరగాయలకి మూడు వందలు ఖర్చు పెట్టాను",
            "భోజనం కి నూట యాభై ఖర్చు", "ఈ నెల ఖర్చు ఎంత", "ఈరోజు ఖర్చు ఎంత", "ఈ వారం ఖర్చు ఎంత", "నిన్న ఖర్చు ఎంత", "ఖర్చులు చెప్పు",
            U + " కి ఐదు వందలు ఇచ్చాను", U + " దగ్గర వెయ్యి తీసుకున్నాను", U + " రెండు వేలు తిరిగి ఇచ్చాడు", U + " కి అప్పు ఇచ్చాను",
            "నాకు ఎవరు ఎంత ఇవ్వాలి", "నేను ఎవరికి ఎంత ఇవ్వాలి", "అప్పులు చెప్పు", "బాకీ ఎంత",
            // sums
            "రెండు వందల యాభై ని పన్నెండు తో గుణిస్తే", "వెయ్యి ని నాలుగు తో భాగిస్తే", "ఐదు వందలు ప్లస్ మూడు వందలు", "వెయ్యి లో రెండు వందలు తీసేస్తే",
            "వెయ్యి లో పది శాతం", "ఐదు ఇంటూ ఆరు", "పది కలిపితే",
            // notes, diary, shopping list
            "నోట్ రాసుకో " + U + " " + U + " " + U, U + " " + U + " అని నోట్ రాసుకో", "నోట్స్ చదువు", "నా నోట్స్ చెప్పు", "నోట్స్ లో రాయి",
            "డైరీలో రాయి " + U + " " + U + " " + U, "డైరీ లో రాసుకో", "ఈరోజు డైరీ చదువు", "నిన్న డైరీ చెప్పు",
            "లిస్ట్ లో పాలు పెట్టు", "లిస్ట్ లో " + U + " పెట్టు", "షాపింగ్ లిస్ట్ లో " + U + " " + U + " యాడ్ చెయ్యి", "షాపింగ్ లిస్ట్ చెప్పు",
            "లిస్ట్ చదువు", "ఏం కొనాలి", "ఏమి కొనాలి", U + " కొన్నాను", "పాలు కొన్నాను", "కూరగాయలు తెచ్చాను",
            // duty, parking, calendar, birthdays, festivals
            "రేపు డ్యూటీ ఉందా", "ఈరోజు డ్యూటీ ఉందా", "డ్యూటీ ఎప్పుడు", "నెక్స్ట్ డ్యూటీ ఎప్పుడు", "ఈ వారం డ్యూటీ",
            "బండి ఇక్కడ పెట్టాను", "బండి ఎక్కడ పెట్టాను", "బండి ఎక్కడ ఉంది", "బైక్ ఇక్కడ పార్క్ చేశాను", "కారు ఎక్కడ పార్క్ చేశాను",
            "బండి ఇక్కడ పెట్టాను గుర్తు పెట్టుకో", "రేపు క్యాలెండర్ లో ఏమున్నాయి", "ఈరోజు ప్రోగ్రామ్స్ ఏమున్నాయి", "రేపు మీటింగ్ ఉందా",
            "ఎల్లుండి ఏమైనా ఉన్నాయా", "పుట్టినరోజులు చెప్పు", "పుట్టిన రోజు ఎప్పుడు", "పెళ్లి రోజు ఎప్పుడు", "పండుగలు చెప్పు", "సెలవులు ఎప్పుడు",
            "దీపావళి ఎప్పుడు", "సంక్రాంతి ఎప్పుడు", "దసరా ఎప్పుడు", "ఉగాది ఎప్పుడు", "క్రిస్మస్ ఎప్పుడు", "ఈస్టర్ ఎప్పుడు", "వినాయక చవితి ఎప్పుడు",
            "శివరాత్రి ఎప్పుడు",
            // medicines
            "బీపీ మాత్ర వేసుకున్నాను", "మాత్ర వేసుకున్నాను", "మందు వేసుకున్నాను", "మందులు ఏమున్నాయి", "మాత్రలు చెప్పు", "టాబ్లెట్ వేసుకున్నాను",
            // messages that came
            "మెసేజ్లు చదువు", "కొత్త మెసేజ్లు వచ్చాయా", "మెసేజెస్ చదువు", "నోటిఫికేషన్లు చదువు", "ఏమైనా మెసేజ్లు వచ్చాయా",
            // songs
            "ఫోన్లో పాటలు పెట్టు", "ఫోన్ లో ఉన్న పాటలు ప్లే చెయ్యి", "పాట పెట్టు", U + " పాట పెట్టు", U + " " + U + " పాట ప్లే చెయ్యి", "పాట ఆపు",
            "తర్వాత పాట", "నెక్స్ట్ సాంగ్", "ముందు పాట", "మ్యూజిక్ ఆపు", "సాంగ్ ప్లే చెయ్యి", "ఏదైనా పాట పెట్టు",
            // the phone, apps
            "ఎక్కడున్నావ్", "ఎక్కడ ఉన్నావ్", U + " ఓపెన్ చెయ్యి", "కెమెరా ఓపెన్ చెయ్యి", "కెమెరా తెరువు", "గ్యాలరీ తెరువు",
            "కాలిక్యులేటర్ ఓపెన్ చెయ్యి", "క్యాలెండర్ ఓపెన్ చెయ్యి", "సెట్టింగ్స్ తెరువు", "వాట్సాప్ ఓపెన్ చెయ్యి", "యూట్యూబ్ ఓపెన్ చెయ్యి",
            // offline help, questions for later, talk
            "ఆఫ్ లైన్ లో ఏం చేయగలవు", "నెట్ లేకుండా ఏమి చేయగలవు", "నెట్ లేనప్పుడు ఏం చేస్తావ్", "వార్తలు చెప్పు", "వాతావరణం ఎలా ఉంది",
            "రేపు వర్షం పడుతుందా", U + " " + U + " " + U + " " + U, U + " ఏంటి", U + " ఎవరు", U + " ఎలా", U, U, U,
            "హలో", "జార్విస్", "థాంక్యూ", "ధన్యవాదాలు", "ఆగు", "కొనసాగించు", "ఏం లేదు", "మళ్లీ చెప్పు",
    };

    /** All the phrases: the ones above, and numbers, times, days, people and things in the ways he says them. */
    static List<String> phrases() {
        List<String> l = new ArrayList<>(java.util.Arrays.asList(PHRASES));
        for (String h : HOURS) {
            for (String p : DAY_PARTS) l.add(p + " " + h + " గంటలకి");
            l.add(h + " గంటలకు");
        }
        for (String h : HOURS_KI) l.add(h + " అలారం పెట్టు");
        for (String h : HALVES) { l.add(h); l.add(h + "కి"); }
        for (String m : MINUTES) { l.add(m + " నిమిషాల తర్వాత"); l.add(m + " నిమిషాలు"); }
        for (String n : NUMBERS) l.add(n);
        for (int i = 1; i < 10; i++) { l.add(HOURS[i - 1] + " వందలు"); l.add(HOURS[i - 1] + " వేలు"); }
        for (String d : DAYS) l.add(d);
        for (String p : PEOPLE) { l.add(p); l.add(p + " కి"); }
        for (String p : PEOPLE_KI) l.add(p);
        for (String t : THINGS) { l.add(t); l.add("లిస్ట్ లో " + t + " పెట్టు"); l.add(t + " కి వంద ఖర్చు రాయి"); }
        return l;
    }

    /** Every word in the phrases. */
    static Set<String> words() {
        Set<String> s = new LinkedHashSet<>();
        for (String p : phrases()) for (String w : p.split(" ")) if (!w.isEmpty()) s.add(w);
        return s;
    }

    /**
     * The command listener's phrases for Vosk (a JSON list), with the words this model doesn't know taken out (known: the
     * model's own word list); missing gets those words. Null when nothing is left.
     */
    static String grammar(Predicate<String> known, List<String> missing) {
        Set<String> bad = new LinkedHashSet<>();
        for (String w : words()) if (!known.test(w)) bad.add(w);
        if (missing != null) missing.addAll(bad);
        JSONArray a = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : phrases()) {
            StringBuilder b = new StringBuilder();
            for (String w : p.split(" ")) if (!w.isEmpty() && !bad.contains(w)) b.append(b.length() == 0 ? "" : " ").append(w);
            String q = b.toString();
            if (!q.isEmpty() && (seen.add(q) || q.equals(UNK))) a.put(q); // ([unk] on its own more than once: his own words are common)
        }
        return a.length() == 0 ? null : a.toString();
    }
}
