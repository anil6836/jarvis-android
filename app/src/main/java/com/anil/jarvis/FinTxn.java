package com.anil.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 💰 One money movement: read from a bank / UPI / card SMS on the phone, or an expense he wrote himself. The SMS text itself is
 * never kept or sent anywhere: only the amount, the way (UPI / card / ATM…), the payee's short name, the bank and the
 * account's last 4 digits (to tell his own accounts apart; never said or sent). Pure Java (tested on a PC).
 */
final class FinTxn {
    long t;
    double amt;
    boolean credit;
    String payee = "", channel = "other", bank = "", acct = "", cat = "other", src = "sms", note = "";
    double bal = -1;

    static final String[] CATS = {"food", "groceries", "fuel", "shopping", "bills", "recharge", "transport", "entertainment", "health", "education",
            "rent", "emi", "insurance", "invest", "cash", "people", "charges", "card_bill", "self", "salary", "refund", "other"};

    static String catTe(String c) {
        switch (c == null ? "" : c) {
            case "food": return "🍔 బయటి తిండి / డెలివరీ";
            case "groceries": return "🛒 సరుకులు";
            case "fuel": return "⛽ పెట్రోల్ / డీజిల్ / ఛార్జింగ్";
            case "shopping": return "🛍️ షాపింగ్";
            case "bills": return "💡 కరెంట్ / గ్యాస్ / నెట్ బిల్లులు";
            case "recharge": return "📱 రీఛార్జ్";
            case "transport": return "🚌 ప్రయాణం";
            case "entertainment": return "🎬 సినిమా / OTT";
            case "health": return "💊 ఆరోగ్యం";
            case "education": return "📚 చదువు";
            case "rent": return "🏠 అద్దె";
            case "emi": return "🏦 EMI / లోన్";
            case "insurance": return "🛡️ ఇన్సూరెన్స్";
            case "invest": return "📈 పెట్టుబడి";
            case "cash": return "💵 ATM క్యాష్";
            case "people": return "👥 వ్యక్తులు / చిన్న షాపులు (UPI)";
            case "charges": return "⚠️ బ్యాంక్ చార్జీలు / ఫైన్లు";
            case "card_bill": return "💳 క్రెడిట్ కార్డ్ బిల్లు";
            case "self": return "🔁 మీ ఖాతాల మధ్య";
            case "salary": return "💰 జీతం";
            case "refund": return "↩️ రీఫండ్";
            default: return "📦 ఇతరం";
        }
    }

    /** Money that is not "spent": paying the card bill (its spends came in their own SMS), putting money into investments, his own transfers. */
    boolean spending() {
        return !credit && !"card_bill".equals(cat) && !"invest".equals(cat) && !"self".equals(cat);
    }

    /** Money that came in for real (not a refund, his own transfer or a card-bill "payment received"). */
    boolean income() {
        return credit && !"card_bill".equals(cat) && !"self".equals(cat) && !"refund".equals(cat);
    }

    /** Fixed every month (planned, hard to cut quickly). */
    static boolean fixed(String c) {
        return "emi".equals(c) || "rent".equals(c) || "insurance".equals(c) || "bills".equals(c) || "recharge".equals(c) || "education".equals(c);
    }

    // ================================================================ reading a bank SMS

    private static final Pattern AMOUNT = Pattern.compile("(?:₹|(?<![a-z])(?:rs\\.?|inr))\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_BY = Pattern.compile("(?:debited|credited) (?:by|with|for)\\s*(?:rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT = Pattern.compile("\\b(debited|spent|withdrawn|withdrawal|paid|sent|purchased?|dr|debit|transferred to|trf to)\\b");
    private static final Pattern CREDIT = Pattern.compile("\\b(credited|received|deposited|refund(ed)?|cr)\\b");
    private static final Pattern CODE = Pattern.compile("\\botp|otp\\b|one-time password|one time password|verification code|secure code");
    /** Never a money movement: reminders, requests, mandates being set up, statements. */
    private static final Pattern HARD_NOT = Pattern.compile("will be debited|to be debited|is due|due on|due date|payment due|minimum amount due|total amount due|"
            + "requested|request of|collect request|\\bmandate\\b.*(created|registered|set up|successful)|e-?mandate.*(set|regist)|"
            + "statement|bill generated|has been generated|is scheduled|will be credited|to be credited");
    /** Offers and adverts, unless money really moved. */
    private static final Pattern SOFT_NOT = Pattern.compile("\\b(offer|cashback offer|pre-?approved|eligible|apply now|get up to|win|congratulations|limit increased|reward points?)\\b");
    private static final Pattern PROMO = Pattern.compile("\\b(prepaid|postpaid|cashback|offers?|recharge offers?|plan|validity|data pack)\\b");
    private static final Pattern REAL = Pattern.compile("debited|credited|paid to|sent to|\\ba/c\\b|\\bacct\\b|\\bac\\b|upi ref|\\btxn\\b|\\bref no|card .*ending|spent");
    private static final Pattern BAL = Pattern.compile("(?:avl\\.?|avail(?:able)?\\.?|a/?c|clr|clear|total|net)?\\s*bal(?:ance)?\\.?\\s*(?:is|:|-)?\\s*(?:₹|rs\\.?|inr)?\\s*:?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCT = Pattern.compile("(?:a/?c|acct|account|card|ac)\\s*(?:no\\.?|number|ending(?: with| in)?)?\\s*[:\\-]?\\s*[xX*.]*\\s*(\\d{3,6})\\b", Pattern.CASE_INSENSITIVE);

    // where it went / came from (the common Indian bank formats)
    private static final Pattern[] PAYEE = {
            Pattern.compile("(?im)^\\s*to\\s*:?\\s*(.+)$"),                                                    // HDFC: "To SWIGGY" on its own line
            Pattern.compile("(?i)\\b(?:trf to|transferred to|transfer to)\\s+(.+?)(?:\\s+ref(?:no| no)?\\b|\\s+on\\b|\\.|$)"),   // SBI
            Pattern.compile("(?i);\\s*(.+?)\\s+credited\\b"),                                                   // ICICI "; SWIGGY credited"
            Pattern.compile("(?i)\\bupi/p2[am]/\\d+/([^/\\n]+)"),                                               // Axis "UPI/P2M/123/SWIGGY"
            Pattern.compile("(?i)\\b(?:paid to|sent to|payment to|to vpa|to)\\s+([a-z0-9._\\-]+@[a-z0-9.\\-]+)"),   // a VPA
            Pattern.compile("(?i)\\bat\\s+(.+?)\\s+(?:on|dated|via|using|ref|txn|for|,|\\.)\\b"),               // card "at AMAZON on"
            Pattern.compile("(?i)\\b(?:paid to|sent to|payment to|towards|for)\\s+(.+?)(?:\\s+(?:on|ref|upi|via|from|using|thru|through|txn)\\b|\\.|,|$)"),
            Pattern.compile("(?i)\\b(?:from|by)\\s+(.+?)(?:\\s+(?:on|ref|upi|via|txn|info|to)\\b|\\.|,|$)"),     // credits "from RAVI"
            Pattern.compile("(?i)\\binfo[:\\s]+(.+?)(?:\\.|,|$)"),
    };

    private static final Pattern CARD_PAID = Pattern.compile("payment (of .{0,20})?(has been )?received.{0,40}card|received.{0,30}payment.{0,30}card|"
            + "thank you for (your|the) payment|towards your .{0,15}card|credit card.{0,40}(payment|paid)");

    static boolean isTxnSms(String low) {
        if (CODE.matcher(low).find()) return false;
        if (HARD_NOT.matcher(low).find()) return false;
        if (SOFT_NOT.matcher(low).find() && !low.contains("debited") && !low.contains("credited") && !low.contains("spent")) return false;
        if (PROMO.matcher(low).find() && !REAL.matcher(low).find()) return false;
        return DEBIT.matcher(low).find() || CREDIT.matcher(low).find();
    }

    /** A bank / UPI / card SMS → a money movement, or null when it is not one (OTP, offer, reminder, request…). */
    static FinTxn parse(String address, String body, long t) {
        if (body == null) return null;
        String low = body.toLowerCase(Locale.ROOT);
        if (!isTxnSms(low)) return null;
        Matcher m = AMOUNT_BY.matcher(body);
        if (!m.find()) {
            m = AMOUNT.matcher(body);
            if (!m.find()) return null;
        }
        double amt;
        try { amt = Double.parseDouble(m.group(1).replace(",", "").replaceAll("\\.+$", "")); } catch (Exception e) { return null; }
        if (amt <= 0 || amt > 50_000_000) return null;
        FinTxn x = new FinTxn();
        x.t = t;
        x.amt = amt;
        int d = firstOf(low, "debited", "spent", "withdrawn", "paid", "sent", "purchase", "dr ", "debit", "trf to", "transferred to");
        int c = firstOf(low, "credited", "received", "deposited", "refund", " cr ");
        x.credit = c >= 0 && (d < 0 || c < d);
        // ICICI style "Acct debited …; SWIGGY credited": the money went out
        if (x.credit && d >= 0 && low.indexOf("credited") > d) x.credit = false;
        if (low.contains("refund") && !low.contains("debited")) x.credit = true;
        x.bank = bankOf(address);
        Matcher a = ACCT.matcher(body);
        if (a.find()) {
            String g = a.group(1);
            x.acct = g.length() > 4 ? g.substring(g.length() - 4) : g;
        }
        Matcher b = BAL.matcher(body);
        if (b.find()) {
            try { x.bal = Double.parseDouble(b.group(1).replace(",", "").replaceAll("\\.+$", "")); } catch (Exception ignored) {}
        }
        x.channel = channel(low);
        x.payee = "atm".equals(x.channel) ? "ATM" : payee(body);
        x.cat = categorize(x.payee, low, x.credit, x.channel);
        if (x.credit && CARD_PAID.matcher(low).find()) x.cat = "card_bill"; // (the card's "payment received": neither income nor spending)
        return x;
    }

    private static int firstOf(String s, String... w) {
        int best = -1;
        for (String k : w) {
            int i = s.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    static String bankOf(String address) {
        String a = address == null ? "" : address.toUpperCase(Locale.ROOT);
        String[][] map = {{"HDFC", "HDFC"}, {"SBI", "SBI"}, {"ICICI", "ICICI"}, {"AXIS", "Axis"}, {"KOTAK", "Kotak"}, {"KOTAKB", "Kotak"}, {"PNB", "PNB"},
                {"BOB", "Bank of Baroda"}, {"CANARA", "Canara"}, {"CANBNK", "Canara"}, {"UNION", "Union Bank"}, {"UBI", "Union Bank"}, {"IDFC", "IDFC First"},
                {"INDUS", "IndusInd"}, {"YES", "Yes Bank"}, {"FED", "Federal"}, {"IOB", "IOB"}, {"INDIAN", "Indian Bank"}, {"INDBNK", "Indian Bank"},
                {"CBOI", "Central Bank"}, {"BOI", "Bank of India"}, {"AUBANK", "AU"}, {"PAYTM", "Paytm"}, {"AIRTEL", "Airtel Payments"}, {"IPPB", "India Post"},
                {"EQUITAS", "Equitas"}, {"RBL", "RBL"}, {"APGVB", "APGVB"}, {"TGB", "TGB"}, {"KVB", "KVB"}, {"TMB", "TMB"}, {"SIB", "South Indian"}};
        for (String[] m : map) if (a.contains(m[0])) return m[1];
        String best = "";
        for (String p : a.split("-")) if (p.length() > best.length()) best = p;
        return best.length() > 8 ? best.substring(0, 8) : best;
    }

    /** UPI references are 12 digits ("Ref 433012345678"). */
    private static final Pattern UPI_REF = Pattern.compile("\\bref(?:no| no|\\.)?\\s*:?\\s*\\d{12}\\b");

    static String channel(String low) {
        if (low.contains("atm") || low.contains("cash withdrawal") || low.contains("withdrawn at") || low.contains("cash wdl")) return "atm";
        if (low.contains("upi") || low.contains("vpa") || low.contains("@ok") || low.contains("@ybl") || low.contains("@paytm") || low.startsWith("sent rs")
                || UPI_REF.matcher(low).find()) return "upi";
        if (low.contains("nach") || low.contains("ach-dr") || low.contains("ach dr") || low.contains("ecs") || low.contains("auto debit")
                || low.contains("autopay") || low.contains("si ") || low.contains("standing instruction") || low.contains("mandate")) return "auto";
        if (low.contains("credit card") || low.contains("debit card") || low.contains("card ending") || low.contains("card no")
                || low.contains(" card ") || low.contains("pos ")) return "card";
        if (low.contains("neft") || low.contains("imps") || low.contains("rtgs")) return "transfer";
        if (low.contains("net banking") || low.contains("netbanking")) return "netbanking";
        if (low.contains("cheque") || low.contains("chq")) return "cheque";
        return "other";
    }

    /** The payee's short name, never a reference / account / phone number. */
    static String payee(String body) {
        for (Pattern p : PAYEE) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                String s = clean(m.group(1));
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    static String clean(String s) {
        if (s == null) return "";
        String v = s.trim();
        int at = v.indexOf('@');
        boolean vpa = at > 0;
        if (vpa) v = v.substring(0, at);
        v = v.replaceAll("(?i)\\b(ref(no)?|txn|utr|rrn|on|dated|via|avl|avail(able)?|incl)\\b.*$", "");
        v = v.replaceAll("(?i)\\b(neft|imps|rtgs|upi|nach|ach|a/?c|acct|account|mr|mrs|ms)\\b", " ");
        v = v.replaceAll("[._\\-]+", " ").replaceAll("[^\\p{L}\\p{N}&' ]", " ").replaceAll("\\s+", " ").trim();
        if (v.matches(".*\\d{6,}.*")) {
            if (vpa && v.replaceAll("\\D", "").length() >= 10) return "UPI వ్యక్తి";
            v = v.replaceAll("\\d{4,}", "").trim();
        }
        if (v.matches("(?i)(your|you|the|a|an|by|from|to|rs|inr|mr|mrs|ms)?")) return "";
        if (v.length() > 28) v = v.substring(0, 28).trim();
        return v.toUpperCase(Locale.ROOT);
    }

    // ================================================================ which kind of spending

    private static final String[][] RULES = {
            {"card_bill", "credit card bill|card bill|cc bill|payment received towards your credit card|creditcard|cred club|cred |bill desk.*card|towards your card"},
            {"salary", "salary|sal credit|payroll|\\bsal\\b|wages"},
            {"refund", "refund|reversal|reversed|cashback credited"},
            {"charges", "\\bcharges?\\b(?! back)|penalty|penal |late fee|late payment fee|annual fee|min(imum)? bal|non maintenance|sms alert chg|\\bamc\\b|finance charge|interest charged|overlimit|gst on"},
            {"invest", "mutual fund|\\bmf\\b|\\bsip\\b|zerodha|groww|upstox|kuvera|coin by|paytm money|angel one|nps|\\bppf\\b|recurring deposit|\\brd\\b|fixed deposit|\\bfd\\b|icclzerodha|bse star|nse clearing|cams|kfintech|\\bsgb\\b|digital gold|smallcase|indmoney|\\bdhan\\b"},
            {"emi", "\\bemi\\b|loan|bajaj fin|bajaj finance|home credit|tata capital|hdb fin|muthoot|manappuram|fullerton|kreditbee|moneyview|\\bnavi\\b|lending|\\bnach\\b|ach dr|ach-dr|ecs"},
            {"insurance", "insurance|\\blic\\b|policy premium|\\bpolicy\\b|star health|niva bupa|hdfc life|icici pru|sbi life|max life|tata aia|care health|acko|digit insur|policybazaar"},
            {"rent", "\\brent\\b|house rent|nobroker|landlord"},
            {"food", "swiggy|zomato|eatsure|domino|pizza|kfc|mcdonald|burger|subway|starbucks|cafe|coffee|restaurant|biryani|dhaba|bakery|hotel|\\btea\\b|chai|food|kitchen|tiffin|bawarchi|paradise|haldiram|chaayos|box8|faasos|behrouz"},
            {"groceries", "bigbasket|blinkit|zepto|instamart|dmart|d mart|avenue super|more retail|reliance fresh|smart bazaar|jiomart|ratnadeep|vijetha|spencer|nature s basket|kirana|grocery|supermarket|super market|vegetable|\\bmilk\\b|dairy|heritage|country delight|\\bfresh\\b"},
            {"fuel", "petrol|diesel|fuel|hpcl|iocl|bpcl|indian oil|bharat petro|hp petrol|shell|nayara|essar|reliance bp|jio bp|statiq|tata power ez|ez charge|chargezone|charge zone|ather grid|bolt earth|electric vehicle charg|ev charg"},
            {"recharge", "recharge|airtel(?! xstream| payments)|\\bjio\\b(?!\\s?(mart|hotstar|fiber|bp))|myjio|vodafone|\\bvi\\b|bsnl|prepaid|postpaid|tata play|dish tv|sun direct|d2h|airtel dth"},
            {"bills", "electricity|tgspdcl|tsspdcl|apspdcl|apepdcl|tgnpdcl|tsnpdcl|apcpdcl|bescom|power bill|water bill|hmwssb|gas bill|indane|hp gas|bharat gas|bharatgas|cylinder|broadband|act fibernet|hathway|airtel xstream|jiofiber|fibernet|bill ?pay|billdesk|municipal|property tax|maintenance"},
            {"transport", "uber|\\bola\\b(?! electric)|rapido|irctc|redbus|abhibus|tsrtc|apsrtc|tgsrtc|\\bmetro\\b|fastag|toll plaza|parking|indigo|air india|akasa|spicejet|makemytrip|goibibo|cleartrip|ixigo|yatra|namma yatri"},
            {"entertainment", "netflix|hotstar|jiohotstar|disney|prime video|amazon prime|spotify|youtube|zee5|sonyliv|sony liv|aha|gaana|wynk|bookmyshow|pvr|inox|cinema|movie|apple media|google play|playstation|steam|dream11|my11circle|mpl"},
            {"health", "apollo|medplus|pharma|pharmacy|medical|hospital|clinic|diagnostic|lab |1mg|pharmeasy|netmeds|tata 1mg|yashoda|care hospital|\\bkims\\b|\\baig\\b|doctor|dental|eye care|lenskart"},
            {"education", "school|college|tuition|fees|udemy|coursera|byju|unacademy|vedantu|physics wallah|exam|university|academy"},
            {"shopping", "amazon|flipkart|myntra|meesho|ajio|nykaa|tata cliq|croma|reliance digital|vijay sales|decathlon|ikea|lifestyle|pantaloons|max fashion|trends|westside|zudio|shoppers stop|\\bmall\\b|\\bstores?\\b|mart\\b|fashion|electronics"},
    };
    private static final Pattern[] RULE_P = new Pattern[RULES.length];
    static {
        for (int i = 0; i < RULES.length; i++) RULE_P[i] = Pattern.compile(RULES[i][1]);
    }

    static String categorize(String payee, String low, boolean credit, String channel) {
        String p = payee == null ? "" : payee.toLowerCase(Locale.ROOT);
        String s = p + " | " + (low == null ? "" : low);
        if (credit) {
            if (RULE_P[1].matcher(s).find()) return "salary";
            if (RULE_P[2].matcher(s).find()) return "refund";
            return "other";
        }
        if ("atm".equals(channel)) return "cash";
        for (int i = 0; i < RULES.length; i++) {
            String c = RULES[i][0];
            if ("salary".equals(c) || "refund".equals(c)) continue;
            // the payee's name decides first (a "SWIGGY" payee is food even when the SMS mentions a card)
            if (!p.isEmpty() && RULE_P[i].matcher(p).find()) return c;
        }
        for (int i = 0; i < RULES.length; i++) {
            String c = RULES[i][0];
            if ("salary".equals(c) || "refund".equals(c)) continue;
            if (RULE_P[i].matcher(s).find()) return c;
        }
        if ("auto".equals(channel)) return "emi";
        if ("upi".equals(channel) && (p.equals("upi వ్యక్తి") || p.matches("(mr |mrs |ms )?[a-z]+( [a-z]+){0,2}") && !p.contains(" store"))) return "people";
        if ("transfer".equals(channel)) return "people";
        return "other";
    }

    /** His own expense's category word (food, groceries, fuel…) to ours. */
    static String fromExpense(String what, String category) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT).trim();
        for (String k : CATS) if (k.equals(c)) return k;
        if (c.contains("grocer")) return "groceries";
        if (c.contains("food") || c.contains("restaurant")) return "food";
        if (c.contains("fuel") || c.contains("petrol") || c.contains("diesel")) return "fuel";
        if (c.contains("medic") || c.contains("health")) return "health";
        if (c.contains("travel") || c.contains("transport")) return "transport";
        if (c.contains("bill")) return "bills";
        String w = (what == null ? "" : what).toLowerCase(Locale.ROOT);
        String k = categorize(w, w, false, "other");
        return "people".equals(k) ? "other" : k;
    }
}
