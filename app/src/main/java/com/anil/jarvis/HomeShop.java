package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🛒 అమ్మగారి సరుకుల లిస్ట్ on the home tablet (Anil, 24 Oct): "Jarvis, బియ్యం అయిపోయాయి" → బియ్యం goes on the list, and a
 * couple of minutes later (her time to say "వద్దు") the whole list reaches his phone through the home link (Telegram), in
 * one message. The list stays on the tablet (the 🛒 button shows it, big) until things are brought: she ticks them off, or
 * he clears it from his phone. When he is on his way home the list goes to him once more. Only the items travel, never
 * what else she said. The words are read here, offline too; the AI's home_care tool can add / read / clear as well.
 */
final class HomeShop {
    private HomeShop() {}

    static final int MAX_ITEMS = 30;
    private static final long KEEP_MS = 30L * 86_400_000L, SEND_AFTER = 2 * 60_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_shop", Context.MODE_PRIVATE); }

    // ================================================================ the list
    /** The items, oldest first ({"t": text, "at": millis, "sent": bool}); a month-old one drops by itself. */
    private static synchronized JSONArray load(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("t").trim().isEmpty() && now - o.optLong("at") < KEEP_MS) out.put(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void save(Context c, JSONArray a) {
        sp(c).edit().putString("list", a.toString()).putLong("changed", System.currentTimeMillis()).apply();
        HomeScreen.shopChanged();
    }

    static synchronized List<String> items(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i).optString("t"));
        return out;
    }

    /** Adds what is not on the list yet; returns those (the ones already there are not added twice). */
    static synchronized List<String> add(Context c, List<String> items) {
        List<String> added = new ArrayList<>();
        if (items == null || items.isEmpty()) return added;
        JSONArray a = load(c);
        try {
            for (String raw : items) {
                String t = tidy(raw);
                if (t.isEmpty() || a.length() >= MAX_ITEMS) continue;
                if (indexOf(a, t) >= 0 || added.contains(t)) continue;
                a.put(new JSONObject().put("t", t).put("at", System.currentTimeMillis()).put("sent", false));
                added.add(t);
            }
        } catch (Exception ignored) {}
        if (!added.isEmpty()) {
            save(c, a);
            sp(c).edit().putString("last_added", new JSONArray(added).toString()).putLong("last_added_at", System.currentTimeMillis()).apply();
            sendSoon(c);
        }
        return added;
    }

    /** Takes these off (bought); true when something went. */
    static synchronized boolean remove(Context c, List<String> items) {
        JSONArray a = load(c);
        boolean gone = false;
        for (String t : items) {
            int i = indexOf(a, tidy(t));
            if (i >= 0) { a.remove(i); gone = true; }
        }
        if (gone) save(c, a);
        return gone;
    }

    static synchronized void clear(Context c) {
        save(c, new JSONArray());
        sp(c).edit().remove("last_added").apply();
    }

    /** The same item: the same words, or one inside the other ("బియ్యం" / "రెండు కిలోల బియ్యం"). */
    private static int indexOf(JSONArray a, String t) {
        String k = key(t);
        if (k.isEmpty()) return -1;
        for (int i = 0; i < a.length(); i++) {
            String o = key(a.optJSONObject(i).optString("t"));
            if (o.equals(k) || o.length() >= 3 && k.contains(o) || k.length() >= 3 && o.contains(k)) return i;
        }
        return -1;
    }

    private static String key(String t) { return t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u200c\\u200d]+", ""); }

    // ================================================================ her words (pure: desk-tested)
    /** Things a house runs out of (the words she would use; "ఉల్లిపాయలు టమాటాలు" is split by these). */
    static final String[] VOCAB = {"బియ్యం", "నూక", "కందిపప్పు", "పెసరపప్పు", "మినపప్పు", "శనగపప్పు", "ఎర్రపప్పు", "పప్పు", "వంటనూనె",
            "కొబ్బరినూనె", "కొబ్బరి నూనె", "నూనె", "ఉప్పు", "చక్కెర", "పంచదార", "బెల్లం", "టీ పొడి", "టీపొడి", "కాఫీ పొడి", "కాఫీపొడి", "పాల ప్యాకెట్",
            "పాలు", "పెరుగు", "మజ్జిగ", "నెయ్యి", "వెన్న", "గుడ్లు", "బ్రెడ్", "బిస్కెట్లు", "బిస్కెట్", "ఉప్మా రవ్వ", "ఇడ్లీ రవ్వ", "రవ్వ", "గోధుమ పిండి",
            "గోధుమపిండి", "మైదా", "శనగపిండి", "బియ్యప్పిండి", "అటుకులు", "సేమ్యా", "కారం", "పసుపు", "మిరియాలు", "జీలకర్ర", "ఆవాలు", "మెంతులు",
            "చింతపండు", "అల్లం", "వెల్లుల్లి", "ఉల్లిపాయలు", "ఉల్లిగడ్డలు", "టమాటాలు", "టమోటాలు", "టమాటా", "బంగాళాదుంపలు", "ఆలుగడ్డలు",
            "పచ్చిమిర్చి", "మిరపకాయలు", "కొత్తిమీర", "కరివేపాకు", "పుదీనా", "నిమ్మకాయలు", "కూరగాయలు", "ఆకుకూరలు", "ఆకుకూర", "పాలకూర",
            "తోటకూర", "బెండకాయలు", "వంకాయలు", "దొండకాయలు", "బీరకాయలు", "సొరకాయ", "క్యారెట్", "బీన్స్", "క్యాబేజీ", "కాలీఫ్లవర్", "దోసకాయలు",
            "కొబ్బరికాయ", "అరటిపండ్లు", "పండ్లు", "యాపిల్", "ఆపిల్", "బొప్పాయి", "ద్రాక్ష", "నారింజ", "బట్టల సబ్బు", "సబ్బులు", "సబ్బు",
            "సర్ఫ్", "డిటర్జెంట్", "షాంపూ", "టూత్ పేస్ట్", "పేస్ట్", "బ్రష్", "అగరబత్తి", "అగరవత్తులు", "కొవ్వొత్తులు", "బల్బు", "బ్యాటరీలు",
            "గ్యాస్", "సిలిండర్", "మంచినీళ్లు", "మంచినీళ్ళు", "వాటర్ క్యాన్", "నీళ్ల క్యాన్", "నీళ్ళ క్యాన్", "ఫినాయిల్", "హార్పిక్", "విమ్",
            "టిష్యూ", "అగ్గిపెట్టె", "rice", "oil", "sugar", "milk", "bread", "soap", "eggs", "onions", "tomatoes"};

    /** "It ran out / is running low" (her words), not "the milk didn't come" (that is the milk account). */
    private static final Pattern SHORT = Pattern.compile("అయిపో(యా|యిం|యే|తు|తో|వ|వచ్చ|వస్త)|తక్కువ(గా|గ|)\\s*(ఉన్న|ఉంద|ఉన్నా)|తక్కువైపో|(^|\\s)(లేవు|లేదు|కావాలి)(\\s|$|[.!।,])");
    /** The milkman's account in her words (more / less / not tomorrow / paid). */
    private static final Pattern MILK_ACCOUNT = Pattern.compile("ఎక్కువ|తక్కువ|వచ్చా|వద్దు|పాలవాడ|పాలతను|పాలబ్బాయి|బిల్లు|డబ్బులు|రేపు|ఎల్లుండి|ప్యాకెట్లు");
    /** Eating, drinking, cooking: "…అయిపోయింది" is then done, not run out. */
    private static final Pattern EATING = Pattern.compile("తిన్న|తినడం|తినేశా|తిన్నా|భోజనం|టిఫిన్|వంట(?!\\s*నూనె)|వండ|తాగ|స్నానం");
    /** "Bring / buy / put it on the list" (then anything she names is an item). */
    private static final Pattern BRING = Pattern.compile("తేవాలి|తెమ్మను|తెమ్మని|తెచ్చిపెట్ట|తీసుకురమ్మను|తీసుకురమ్మని|తీసుకురావాలి|కొనాలి|కొనమను|కొనమని|"
            + "కొనుక్కురమ్మను|కొనుక్కురావాలి|కొనుక్కురా|లిస్ట్‌?లో|లిస్టులో|లిస్ట్ లో|సరుకుల్లో");
    /** The tablets she takes ran out ("మాత్రలు అయిపోయాయి", plural: "టాబ్లెట్ అయిపోయింది" is her dose taken). */
    private static final Pattern MEDS_OUT = Pattern.compile("(మాత్రలు|టాబ్లెట్లు|మందులు|టాబ్లెట్స్)\\s*(అన్నీ\\s*)?(అయిపోయా|అయిపోతున్నా|అయిపోవచ్చా|అయిపోవస్తున్నా|లేవు|కొనాలి|తేవాలి)");
    /** Words that are never items. */
    private static final String[] FILLER = {"జార్విస్", "jarvis", "జార్విస్,", "అబ్బాయిని", "అబ్బాయికి", "అబ్బాయి", "అనిల్‌ని", "అనిల్ని", "అనిల్‌కి",
            "అనిల్కి", "మన", "మనింట్లో", "ఇంట్లో", "కొంచెం", "కాస్త", "అన్నీ", "మొత్తం", "సరుకుల", "సరుకులు", "లిస్ట్", "లిస్టు", "కూడా", "ఇంకా",
            "చెప్పు", "చెప్పండి", "రాయి", "రాయండి", "పెట్టు", "పెట్టండి", "రాసుకో", "అని", "ఆ", "ఈ", "అవి", "అది", "నాకు", "మాకు", "ఒక", "ఇవాళ",
            "ఈరోజు", "ఈ రోజు", "రేపు", "please", "ప్లీజ్"};

    /** The things she says the house needs, or an empty list (not about shopping). Pure. */
    static List<String> wanted(String said) {
        List<String> out = new ArrayList<>();
        if (said == null) return out;
        String t = " " + said.trim().toLowerCase(Locale.ROOT).replaceAll("[?!।]", " ") + " ";
        if (t.contains("రాలేదు") || t.contains("వేసుకు") || t.contains("వేసుకో")) return out; // (the milk account / her dose)
        if (t.matches("(?s).*(అయిపోయాయా|అయిపోయిందా|అయిపోయాయే\\?|లేవా|లేదా|కావాలా|ఉన్నాయా|ఉందా).*")) return out; // (a question: Jarvis can't know)
        Matcher med = MEDS_OUT.matcher(t);
        if (med.find()) {
            String before = t.substring(0, med.start()).trim();
            String name = lastWords(clean(before), 2);
            out.add((name.isEmpty() ? "" : name + " ") + "మాత్రలు");
            return out;
        }
        Matcher bring = BRING.matcher(t), shortM = SHORT.matcher(t);
        boolean b = bring.find(), s = shortM.find();
        if (!b && !s) return out;
        // "పప్పు అన్నం తినడం అయిపోయింది", "వంట అయిపోయింది": a meal or the cooking done, not something to buy
        if (!b && EATING.matcher(t).find()) return out;
        int cut = b && s ? Math.min(bring.start(), shortM.start()) : b ? bring.start() : shortM.start();
        String head = t.substring(0, cut);
        List<String> vocab = vocabIn(head);
        if (!b && vocab.isEmpty()) return out; // ("ఏమీ లేదు", "నాకు ఓపిక లేదు": not shopping)
        // only milk, in the milk account's words ("రేపు పాలు ఎక్కువ కావాలి", "పాలు తక్కువ వచ్చాయి"): that is the milkman's
        // account (the AI's house_accounts), not the shopping list; "పాలు అయిపోయాయి" still is
        if (vocab.size() == 1 && vocab.get(0).startsWith("పాల") && !vocab.get(0).equals("పాలకూర")
                && (MILK_ACCOUNT.matcher(t).find() || !t.matches("(?s).*అయిపో(యా|యిం|తు|తో).*"))) return out;
        for (String seg : head.split(",|،|\\s(మరియు|ఇంకా|and)\\s|\\sకూడా\\s")) {
            String c = clean(seg);
            if (c.isEmpty()) continue;
            List<String> hits = vocabIn(c);
            int others = c.split("\\s+").length - wordsIn(hits);
            if (hits.size() >= 2 && others <= 1) out.addAll(hits); // "ఉల్లిపాయలు టమాటాలు" → two
            else if (hits.size() == 1 || b) out.add(lastWords(c, 4)); // "రెండు కిలోల బియ్యం", "ఫినాయిల్ బాటిల్"
        }
        List<String> seen = new ArrayList<>();
        for (String o : out) if (!o.isEmpty() && !seen.contains(o) && seen.size() < 8) seen.add(o);
        return seen;
    }

    /** Her words read the list ("లిస్ట్ చెప్పు", "ఏమేమి కావాలి?"). */
    static boolean asksList(String t) {
        if (t == null) return false;
        return t.matches("(?s).*(సరుకుల లిస్ట్|సరుకుల లిస్టు|షాపింగ్ లిస్ట్|లిస్ట్ చెప్పు|లిస్టు చెప్పు|లిస్ట్‌లో ఏమ|లిస్ట్లో ఏమ|లిస్టులో ఏమ|ఏమేమి కావాలి|ఏమేం కావాలి|ఏమి తేవాలి|ఏం తేవాలి).*")
                && !t.matches("(?s).*(ఖాళీ|తీసేయ|క్లియర్).*");
    }

    /** A question ("సరుకులు వచ్చాయా?", "బియ్యం తెచ్చాడా?", "లిస్ట్ ఖాళీగా ఉందా?"): never a change to the list. Pure. */
    static boolean question(String t) {
        return t != null && (t.contains("?") || QUESTION.matcher(t).find());
    }

    private static final Pattern QUESTION = Pattern.compile("(తెచ్చాడా|తెచ్చారా|తెచ్చావా|వచ్చాయా|వచ్చిందా|వచ్చాడా|ఉందా|ఉన్నాయా|లేదా|కొన్నాడా|కొన్నారా"
            + "|కొన్నావా|చేయాలా|చేశాడా|చేశారా|ఏమైనా|ఎప్పుడు|ఎక్కడ|ఎవరు|ఎందుకు|ఎలా|ఏంటి|ఏమిటి)(?![\\u0C00-\\u0C7F])");

    /** Everything was brought ("సరుకులు అన్నీ వచ్చేశాయి", "లిస్ట్ ఖాళీ చేయి"). */
    static boolean clears(String t) {
        if (t == null || question(t)) return false;
        return t.matches("(?s).*((లిస్ట్|లిస్టు|సరుకుల లిస్ట్).{0,12}(ఖాళీ|తీసేయ|క్లియర్|తుడిచేయ)|సరుకులు.{0,10}(వచ్చేశా|వచ్చా|తెచ్చా|తెచ్చేశా)|అన్నీ తెచ్చా|అన్నీ తెచ్చేశా).*");
    }

    /** The items on the list her words say were brought ("బియ్యం తెచ్చాడు", "ఉప్పు వచ్చింది"). */
    static List<String> brought(String t, List<String> list) {
        List<String> out = new ArrayList<>();
        if (t == null || list == null || question(t) || !t.matches("(?s).*(తెచ్చా|తెచ్చే|తెచ్చి|వచ్చింది|వచ్చాయి|వచ్చేశా|వచ్చేసి|కొన్నా|కొనేశా|తీసేయి|తీసేయండి|అక్కర్లేదు|అవసరం లేదు).*")) return out;
        String k = key(t);
        for (String item : list) {
            String ik = key(item);
            if (!ik.isEmpty() && k.contains(ik)) { out.add(item); continue; }
            for (String v : vocabIn(item)) if (k.contains(key(v))) { out.add(item); break; }
        }
        return out;
    }

    private static List<String> vocabIn(String s) {
        List<String> hits = new ArrayList<>();
        String rest = " " + s + " ";
        for (String v : VOCAB) { // (longer words come first in VOCAB where it matters: "కందిపప్పు" before "పప్పు")
            int i = rest.indexOf(v);
            if (i < 0) continue;
            hits.add(v.trim());
            rest = rest.substring(0, i) + " " + rest.substring(i + v.length()); // ("పప్పు" not found again inside "కందిపప్పు")
        }
        // in the order she said them
        final String src = s;
        hits.sort((x, y) -> Integer.compare(src.indexOf(x), src.indexOf(y)));
        return hits;
    }

    private static int wordsIn(List<String> hits) {
        int n = 0;
        for (String h : hits) n += h.split("\\s+").length;
        return n;
    }

    /** The words without the fillers ("Jarvis, మన ఇంట్లో బియ్యం" → "బియ్యం"). */
    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        outer:
        for (String w : s.trim().split("\\s+")) {
            String x = w.replaceAll("^[,.;:]+|[,.;:]+$", "");
            if (x.isEmpty()) continue;
            for (String f : FILLER) if (x.equals(f)) continue outer;
            if (b.length() > 0) b.append(' ');
            b.append(x);
        }
        return b.toString().trim();
    }

    private static String lastWords(String s, int n) {
        String[] w = s.trim().split("\\s+");
        if (w.length <= n) return s.trim();
        StringBuilder b = new StringBuilder();
        for (int i = w.length - n; i < w.length; i++) b.append(i == w.length - n ? "" : " ").append(w[i]);
        return b.toString();
    }

    /** One item as it is kept: short, one line. */
    static String tidy(String t) {
        if (t == null) return "";
        String s = t.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim().replaceAll("^[,.;:•\\-]+|[,.;:•]+$", "").trim();
        return s.length() > 40 ? s.substring(0, 40).trim() : s;
    }

    // ================================================================ her words on the tablet (HomeCare.heard)
    /** A short yes / no on its own ("అవును", "వద్దు బాబు"), else null: a long sentence is never taken as an answer. Pure. */
    static Boolean shortYesNo(String t) {
        if (t == null || t.trim().split("\\s+").length > 4) return null;
        return HomeCare.yesNo(t);
    }

    /**
     * Her answer to the list's own questions, before any other waiting question is looked at: "వద్దు" just after an item
     * was written (its 2 minutes), items after "🎤 ఇంకోటి చెప్పండి" (a minute), yes / no to "లిస్ట్ మొత్తం తీసేయనా?" (90 s).
     * Null: not for the list.
     */
    static String pendingAnswer(Context c, String text, String w) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        String p = HomeCare.pending();
        long age = HomeCare.pendingAge();
        if (p.equals("shop_undo") && age > SEND_AFTER + 30_000L || p.equals("shop") && age > 60_000L || p.equals("shop_clear") && age > 90_000L) {
            HomeCare.clearPending(); // (too late: her words are not this answer)
            return null;
        }
        if (p.equals("shop_undo") && t.matches("(?s)^\\s*(వద్దు|వద్దులే|తీసేయి|తీసేయండి|రాయకు|రాయొద్దు|పొరపాటు|no)[ .!।]*$")) {
            HomeCare.clearPending();
            List<String> last = lastAdded(c);
            if (remove(c, last)) return "సరే " + w + ", " + join(last) + " లిస్ట్ నుంచి తీసేశాను.";
            return "సరే " + w + ".";
        }
        if (p.equals("shop")) { // after "🎤 ఇంకోటి చెప్పండి": what she says now is items
            HomeCare.clearPending();
            if (t.matches("(?s)^\\s*(అంతే|ఏమీ లేదు|ఏం లేదు|వద్దు|లేదు|చాలు|no)[ .!।]*$")) return "సరే " + w + ".";
            List<String> items = wanted(t);
            if (items.isEmpty()) items = splitPlain(t);
            if (items.isEmpty()) return null; // (a question, a long sentence: not items; it goes on as usual)
            return added(c, items, w);
        }
        if (p.equals("shop_clear")) {
            Boolean yes = t.trim().split("\\s+").length <= 8 ? HomeCare.yesNo(t) : null;
            if (yes == null) return null;
            HomeCare.clearPending();
            if (!yes) return "సరే " + w + ", లిస్ట్ అలాగే ఉంచాను.";
            clear(c);
            return "సరే " + w + ", సరుకుల లిస్ట్ ఖాళీ చేశాను.";
        }
        return null;
    }

    /** What Jarvis says to her words about the list, or null (not about it). */
    static String heard(Context c, String text, String w) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (clears(t)) { // (everything off: asked first)
            List<String> l = items(c);
            if (l.isEmpty()) return "సరుకుల లిస్ట్ ఇప్పటికే ఖాళీగా ఉంది " + w + ".";
            HomeCare.setPending("shop_clear");
            return w + ", సరుకుల లిస్ట్ మొత్తం (" + join(l) + ") తీసేయనా?";
        }
        if (asksList(t)) return listLine(c, w);
        List<String> list = items(c);
        List<String> got = brought(t, list);
        if (!got.isEmpty()) {
            remove(c, got);
            List<String> left = items(c);
            return "మంచిది " + w + ", " + join(got) + " లిస్ట్ నుంచి తీసేశాను." + (left.isEmpty() ? " లిస్ట్ ఇప్పుడు ఖాళీ." : " ఇంకా కావలసినవి: " + join(left) + ".");
        }
        List<String> want = wanted(t);
        if (!want.isEmpty()) return added(c, want, w);
        return null;
    }

    /** "ఉల్లిపాయలు, టమాటాలు" said after "ఇంకోటి చెప్పండి" (no verb). */
    private static List<String> splitPlain(String t) {
        List<String> out = new ArrayList<>();
        if (question(t) || t.trim().split("\\s+").length > 8) return out; // (a question or a long sentence is not a list)
        List<String> v = vocabIn(t);
        String c = clean(t);
        if (v.size() >= 2 && c.split("\\s+").length - wordsIn(v) <= 1) return v;
        for (String seg : t.split(",|\\s(మరియు|ఇంకా|and)\\s")) {
            String s = clean(seg);
            if (s.isEmpty()) continue;
            if (s.split("\\s+").length > 4) return new ArrayList<>(); // (one long part: a sentence, not items)
            out.add(s);
        }
        return out;
    }

    private static String added(Context c, List<String> items, String w) {
        if (items.isEmpty()) return "ఏమి కావాలో చెప్పండి " + w + ", ఉదా: \"బియ్యం, ఉప్పు\".";
        List<String> added = add(c, items);
        if (added.isEmpty()) return join(items) + " ఇదివరకే లిస్ట్‌లో ఉంది " + w + ".";
        HomeCare.setPending("shop_undo");
        boolean linked = !Guard.token(c).isEmpty() && !Guard.chat(c).isEmpty();
        return join(added) + " సరుకుల లిస్ట్‌లో రాశాను " + w + "."
                + (linked ? " రెండు నిమిషాల్లో అబ్బాయి ఫోన్‌కి పంపుతాను. పొరపాటైతే \"వద్దు\" అనండి." : " (అబ్బాయి ఫోన్‌తో టాబ్లెట్ ఇంకా కలపలేదు: లిస్ట్ ఇక్కడే ఉంటుంది.)");
    }

    /** What is on the list, said to her. */
    static String listLine(Context c, String w) {
        List<String> l = items(c);
        if (l.isEmpty()) return w + ", సరుకుల లిస్ట్ ఖాళీగా ఉంది. ఏదైనా అయిపోతే \"Jarvis, బియ్యం అయిపోయాయి\" అని చెప్పండి.";
        return w + ", సరుకుల లిస్ట్‌లో " + l.size() + ": " + join(l) + ".";
    }

    private static List<String> lastAdded(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("last_added", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) {}
        return out;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : i == l.size() - 1 ? " మరియు " : ", ").append(l.get(i));
        return b.toString();
    }

    // ================================================================ to his phone
    private static volatile long sendToken;

    /** The list goes to him a little after the last change (one message for a few items; her "వద్దు" comes first). */
    static void sendSoon(Context c) {
        final Context a = c.getApplicationContext();
        final long token = ++sendToken;
        HomeCare.later(() -> { if (token == sendToken) sendNow(a, false); }, SEND_AFTER);
    }

    private static final Object SENDING = new Object();

    /**
     * Sends the whole list now (the 🛒 panel's button / the timer / the care tick). To his Telegram with its sound, and
     * pinned so his phone's Jarvis puts the items on his own shopping list (and watch). Only the items in this message are
     * marked sent, and only once it is pinned (a word of his still waiting here is never covered: the pin comes on a later
     * tick); no internet / not linked: nothing is marked, the care tick tries again. Background thread. False: not sent.
     */
    static boolean sendNow(Context c, boolean force) {
        synchronized (SENDING) {
            if (Guard.token(c).isEmpty() || Guard.chat(c).isEmpty() || !Net.online(c)) return false;
            if (HomeLink.commandWaiting(c)) return false; // (a word of his is waiting here: the list goes on a later tick, never over it)
            JSONArray a = load(c);
            if (a.length() == 0) return false;
            List<String> fresh = new ArrayList<>(), all = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                all.add(o.optString("t"));
                if (!o.optBoolean("sent")) fresh.add(o.optString("t"));
            }
            if (fresh.isEmpty() && !force) return false;
            sp(c).edit().putLong("try_at", System.currentTimeMillis()).apply();
            long id = Guard.sendLoud(c, "🏠 " + message(HomeCare.who(c), all, fresh, null));
            if (id <= 0) return false;
            // pinned at once (his phone reads it on the message's own notification); not pinned: taken back, sent again later
            if (!Guard.pin(c, id)) { Guard.delete(c, id); return false; }
            markSent(c, all);
            return true;
        }
    }

    /** These items reached his phone: marked sent (one added meanwhile is not: it goes next time). */
    private static void markSent(Context c, List<String> sent) {
        List<String> went = new ArrayList<>();
        for (String s : sent) went.add(key(s));
        synchronized (HomeShop.class) {
            JSONArray b = load(c);
            try {
                for (int i = 0; i < b.length(); i++) if (went.contains(key(b.optJSONObject(i).optString("t")))) b.optJSONObject(i).put("sent", true);
            } catch (Exception ignored) {}
            sp(c).edit().putString("list", b.toString()).putLong("sent_at", System.currentTimeMillis()).apply();
        }
    }

    /** The care tick (every minute, background): items not sent yet go (a failed send is tried again after 10 minutes). */
    static void tick(Context c) {
        try {
            if (System.currentTimeMillis() - sp(c).getLong("try_at", 0) < 10 * 60_000L) return;
            if (System.currentTimeMillis() - sp(c).getLong("changed", 0) < SEND_AFTER) return; // (her "వద్దు" time first)
            for (String ignored : unsent(c)) { sendNow(c, false); return; }
        } catch (Throwable ignored) {}
    }

    private static List<String> unsent(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optBoolean("sent")) out.add(a.optJSONObject(i).optString("t"));
        return out;
    }

    /** He is on his way home: the list once more (at most every 3 hours). */
    static void sonComing(Context c) {
        List<String> l = items(c);
        long now = System.currentTimeMillis();
        if (l.isEmpty() || now - sp(c).getLong("coming_at", 0) < 3 * 3600_000L) return;
        sp(c).edit().putLong("coming_at", now).apply();
        HomeCare.alert(c, message(HomeCare.who(c), l, new ArrayList<>(), "ఇంటికి వస్తున్నారు కదా"));
    }

    /** The Telegram line: "🛒 అమ్మగారి సరుకుల లిస్ట్ (కొత్తవి: బియ్యం): • బియ్యం • ఉప్పు". Pure. */
    static String message(String who, List<String> all, List<String> fresh, String lead) {
        StringBuilder b = new StringBuilder("🛒 ");
        if (lead != null) b.append(lead).append(": ");
        b.append(who).append(" సరుకుల లిస్ట్");
        if (!fresh.isEmpty() && fresh.size() < all.size()) b.append(" (కొత్తవి: ").append(String.join(", ", fresh)).append(")");
        b.append(":");
        for (String s : all) b.append("\n• ").append(s);
        b.append("\n(మీ ఫోన్ Jarvis షాపింగ్ లిస్ట్‌లోకి కూడా వస్తాయి. అన్నీ తెచ్చాక: సెట్టింగ్స్ → అమ్మకి మెసేజ్ కార్డ్ → \"🛒 లిస్ట్ ఖాళీ చేయి\"; లేదా అమ్మగారు టాబ్లెట్‌లో ✓ నొక్కుతారు)");
        return b.toString();
    }

    /** The pinned list on his phone (HomeLink): its items go on his own shopping list. Returns them (pure parse + add). */
    static List<String> itemsIn(String message) {
        List<String> out = new ArrayList<>();
        if (message == null || !message.startsWith("🏠 🛒")) return out;
        for (String line : message.split("\n")) if (line.startsWith("• ") && !tidy(line.substring(2)).isEmpty()) out.add(tidy(line.substring(2)));
        return out;
    }

    /** His phone: అమ్మగారు's list came (pinned): the items on his shopping list ("to buy"), once per message. */
    static void toPhone(Context c, String message) {
        List<String> items = itemsIn(message);
        if (items.isEmpty()) return;
        try { Shopping.add(c, String.join(", ", items)); } catch (Exception ignored) {}
    }

    /** For his phone's tablet tile: "🛒 కావలసినవి: బియ్యం, ఉప్పు", or "". */
    static String statusLine(Context c) {
        List<String> l = items(c);
        return l.isEmpty() ? "" : "🛒 కావలసినవి: " + String.join(", ", l);
    }

    // ================================================================ the AI's home_care tool
    static String tool(Context c, String action, String itemsText) throws Exception {
        List<String> given = new ArrayList<>();
        for (String s : (itemsText == null ? "" : itemsText).split("[,\\n،]")) if (!tidy(s).isEmpty()) given.add(tidy(s));
        switch (action) {
            case "shop_add": {
                if (given.isEmpty()) return new JSONObject().put("ok", false).put("error", "no items").toString();
                List<String> added = add(c, given);
                if (!added.isEmpty()) HomeCare.setPending("shop_undo");
                return new JSONObject().put("ok", true).put("added", new JSONArray(added)).put("list", new JSONArray(items(c)))
                        .put("note", "The list goes to Anil's phone in about 2 minutes by itself; tell her she can say \"వద్దు\" if it was a mistake.").toString();
            }
            case "shop_remove": remove(c, given); return new JSONObject().put("ok", true).put("list", new JSONArray(items(c))).toString();
            case "shop_clear": clear(c); return new JSONObject().put("ok", true).put("list", new JSONArray()).toString();
            default: return new JSONObject().put("ok", true).put("list", new JSONArray(items(c))).toString();
        }
    }
}
