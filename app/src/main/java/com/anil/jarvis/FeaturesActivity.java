package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * All of Jarvis's features in category folders (bike, money, calls, day...). Tapping a folder shows its
 * options; tapping an option asks Jarvis, starts a request for him to finish, runs the action (scanner,
 * Live, bill photo...) or opens the right part of Settings. There is a search box across everything.
 */
public class FeaturesActivity extends Activity {
    static final String EXTRA_CATEGORY = "category";

    private static final int ASK = 0, FILL = 1, DO = 2, OPEN = 3, INFO = 4;

    static final class Opt {
        final String emoji, title, desc, payload;
        final int type;
        Opt(String emoji, String title, String desc, int type, String payload) {
            this.emoji = emoji; this.title = title; this.desc = desc; this.type = type; this.payload = payload;
        }
    }

    static final class Cat {
        final String id, emoji, name, blurb;
        final int color;
        final Opt[] opts;
        Cat(String id, String emoji, String name, String blurb, int color, Opt... opts) {
            this.id = id; this.emoji = emoji; this.name = name; this.blurb = blurb; this.color = color; this.opts = opts;
        }
    }

    private static Opt ask(String e, String t, String d, String prompt) { return new Opt(e, t, d, ASK, prompt); }
    private static Opt fill(String e, String t, String d, String start) { return new Opt(e, t, d, FILL, start); }
    private static Opt act(String e, String t, String d, String code) { return new Opt(e, t, d, DO, code); }
    private static Opt open(String e, String t, String d, String section) { return new Opt(e, t, d, OPEN, section); }
    private static Opt info(String e, String t, String d, String text) { return new Opt(e, t, d, INFO, text); }

    /** The folders. ASK sends the question to Jarvis; FILL starts it in the message box for him to finish. */
    static final Cat[] CATS = {
            new Cat("bike", "🏍️", "బైక్ మోడ్", "రేంజ్, ఛార్జింగ్, రైడ్స్", Ui.C_ORANGE,
                    ask("🔋", "ఎంత దూరం వెళ్లగలను?", "బ్యాటరీ % చెప్తే మిగిలిన రేంజ్", "నా బైక్ ఇంకా ఎంత దూరం వెళ్తుంది? బ్యాటరీ ఎంత % ఉందో నన్ను అడిగి bike_range తో చెప్పు."),
                    ask("⚡", "ఛార్జింగ్ రాసుకో", "ఎంత % నుంచి ఎంత % కి, ఖర్చు ఎంత", "బైక్ ఛార్జ్ చేశాను. ఎంత % నుంచి ఎంత % కి చేశానో, పబ్లిక్ ఛార్జర్‌లో డబ్బు కట్టానా అని నన్ను అడిగి bike_charge తో రాసుకో."),
                    ask("📈", "ఈ వారం రైడ్స్", "కి.మీ, టైమ్, ఛార్జింగ్ ఖర్చు", "ఈ వారం నా బైక్ రైడ్స్, మొత్తం కి.మీ, ఛార్జింగ్ ఖర్చు, కి.మీ కి ఖర్చు చెప్పు."),
                    ask("📅", "ఈ నెల రైడ్స్", "గత 30 రోజుల మొత్తం", "గత 30 రోజుల నా బైక్ రైడ్స్ మొత్తం చెప్పు (bike_rides days=30)."),
                    ask("🔌", "దగ్గర్లో ఛార్జింగ్ స్టేషన్", "దూరం, ప్లగ్‌లతో", "దగ్గర్లో EV ఛార్జింగ్ స్టేషన్లు చూపించు."),
                    ask("🅿️", "బండి ఎక్కడ పెట్టాను?", "పెట్టిన చోటుకి దారి", "నా బండి ఎక్కడ పెట్టాను? దారి చూపించు."),
                    ask("🚦", "డ్రైవింగ్ మోడ్ ఆన్", "మెసేజ్‌లు, కాల్స్ గొంతుతో", "డ్రైవింగ్ మోడ్ ఆన్ చెయ్."),
                    open("⚙️", "బైక్ సెట్టింగ్స్", "బ్లూటూత్, రేంజ్, యూనిట్ ధర", "కార్/బైక్")),
            new Cat("money", "💰", "డబ్బు, ఖర్చులు", "ఖర్చులు, బిల్లులు, బడ్జెట్", Ui.C_GREEN,
                    act("🧾", "బిల్లు ఫోటో → ఖర్చు", "ఫోటో తీస్తే ఖర్చుల్లో చేరుస్తుంది", "bill"),
                    fill("✍️", "ఖర్చు రాసుకో", "ఉదా: పెట్రోల్ 500", "ఖర్చు రాసుకో: "),
                    ask("📊", "ఈ నెల ఖర్చు", "ఎంత, దేనికి ఎక్కువ", "ఈ నెల నేను ఎంత ఖర్చు చేశాను? దేనికి ఎక్కువ అయింది?"),
                    ask("🏦", "బ్యాంక్ బ్యాలెన్స్", "బ్యాంక్ SMS నుంచి", "నా బ్యాంక్ బ్యాలెన్స్ ఎంత?"),
                    fill("🎯", "నెల బడ్జెట్ పెట్టు", "80%, 100% దాటితే చెప్తాను", "నా నెల బడ్జెట్ ₹"),
                    ask("📅", "కట్టాల్సిన బిల్లులు", "గడువు దగ్గర ఉన్నవి", "కట్టాల్సిన బిల్లులు ఏమైనా ఉన్నాయా?"),
                    fill("📉", "ధర తగ్గితే చెప్పు", "ఏదైనా వస్తువు ధర అలర్ట్", "ఈ వస్తువు ధర తగ్గితే చెప్పు: "),
                    fill("🛡️", "ఈ మెసేజ్ మోసమా?", "అనుమానం ఉన్న మెసేజ్ చెక్", "ఈ మెసేజ్ మోసమా చెక్ చేసి చెప్పు: "),
                    ask("📱", "మొబైల్ ప్లాన్", "ఎప్పటి వరకు ఉంది", "నా మొబైల్ రీఛార్జ్ ప్లాన్ ఎప్పటి వరకు ఉంది?")),
            new Cat("calls", "📞", "కాల్స్, మెసేజ్‌లు", "కాల్, WhatsApp, SMS, ఈమెయిల్", Ui.C_SKY,
                    fill("📞", "కాల్ చెయ్", "పేరు లేదా నంబర్", "కాల్ చెయ్: "),
                    fill("💬", "WhatsApp మెసేజ్", "మీరు 'పంపు' అంటేనే వెళ్తుంది", "WhatsApp లో మెసేజ్ పంపు: "),
                    fill("✉️", "SMS పంపు", "మీరు 'పంపు' అంటేనే వెళ్తుంది", "SMS పంపు: "),
                    ask("📨", "కొత్త మెసేజ్‌లు చదువు", "వచ్చిన మెసేజ్‌లు", "నాకు వచ్చిన కొత్త మెసేజ్‌లు చదివి చెప్పు (read_notifications వాడు)."),
                    ask("👥", "గ్రూప్ సారాంశం", "గ్రూప్‌లలో ఏం మాట్లాడారు", "నా WhatsApp గ్రూప్‌లలో ఏం మాట్లాడుకున్నారో సారాంశం చెప్పు."),
                    fill("📧", "ఈమెయిల్ రాయి", "Gmail లో draft", "ఈమెయిల్ రాయి: "),
                    open("🆘", "SOS కాంటాక్ట్స్", "అత్యవసరంలో ఎవరికి చెప్పాలి", "అత్యవసరం")),
            new Cat("day", "📅", "రోజు, రిమైండర్లు", "బ్రీఫింగ్, రిమైండర్, క్యాలెండర్", Ui.C_AMBER,
                    act("🌅", "శుభోదయం బ్రీఫింగ్", "వాతావరణం, క్యాలెండర్, పనులు", "brief"),
                    fill("⏰", "రిమైండర్ పెట్టు", "ఉదా: రేపు 9కి బ్యాంక్", "రిమైండర్ పెట్టు: "),
                    ask("📋", "రాబోయే రిమైండర్లు", "రిమైండర్లు, ఈరోజు క్యాలెండర్", "నా రాబోయే రిమైండర్లు, ఈరోజు క్యాలెండర్ చెప్పు."),
                    fill("📆", "క్యాలెండర్‌లో పెట్టు", "మీటింగ్, ఈవెంట్", "క్యాలెండర్‌లో పెట్టు: "),
                    fill("⏲️", "అలారం / టైమర్", "ఉదా: 6 గంటలకి అలారం", "అలారం పెట్టు: "),
                    ask("🌙", "ఈరోజు ఏం జరిగింది?", "కాల్స్, మెసేజ్‌లు, ఖర్చు", "ఈరోజు ఏం జరిగింది?"),
                    ask("📊", "వారపు రిపోర్ట్", "ఈ వారం ఎలా గడిచింది", "ఈ వారం రిపోర్ట్ చెప్పు."),
                    fill("🔁", "రొటీన్ పెట్టు", "ఉదా: రోజూ ఉదయం 7కి బ్రీఫింగ్", "ఒక రొటీన్ పెట్టు: ")),
            new Cat("missions", "🎯", "మిషన్లు, నోట్స్", "పనులు, నోట్స్, జ్ఞాపకాలు", Ui.C_VIOLET,
                    fill("➕", "కొత్త మిషన్", "చేయాల్సిన పని", "కొత్త మిషన్: "),
                    ask("🎯", "మిషన్ స్టేటస్", "ఏది ముందు చేయాలి", "నా మిషన్ల స్టేటస్ చెప్పు. ఎన్ని పెండింగ్‌లో ఉన్నాయి, ముందు ఏది చేయాలో ఒక్కటి సూచించు."),
                    ask("🧘", "ఫోకస్ మోడ్", "25 నిమిషాల ఫోకస్", "నేను ఇప్పుడు 25 నిమిషాలు ఫోకస్ చేయాలి. నా మిషన్ల నుంచి ఒకటి ఎంచుకుని మూడు చిన్న స్టెప్స్ చెప్పు, తర్వాత 25 నిమిషాల టైమర్ పెట్టు."),
                    act("📂", "మిషన్ల లిస్ట్", "అన్ని మిషన్లు చూడు", "missions"),
                    fill("📝", "నోట్ రాసుకో", "ఏదైనా గుర్తుగా", "నోట్ రాసుకో: "),
                    ask("📒", "నా నోట్స్", "రాసుకున్నవి చూపించు", "నా నోట్స్ చూపించు."),
                    fill("🧠", "గుర్తుపెట్టుకో", "Jarvis ఎప్పటికీ గుర్తుంచుకుంటుంది", "గుర్తుపెట్టుకో: "),
                    fill("🔎", "గతంలో ఏం చెప్పాను?", "పాత సంభాషణల్లో వెతుకు", "గతంలో నేను నీకు చెప్పింది వెతుకు: ")),
            new Cat("camera", "📷", "కెమెరా, డాక్యుమెంట్లు", "స్కాన్, PDF, ఫోటోలు", Ui.C_PINK,
                    act("📄", "Scan → PDF", "కాగితాలు స్కాన్ చేసి PDF", "scan"),
                    act("📷", "ఫోటో గురించి అడుగు", "ఫోటోలో ఏముంది", "photo"),
                    act("🎥", "Live కెమెరా", "కెమెరాలో చూసి చెప్తాను", "camera"),
                    act("📎", "ఫైల్ / PDF గురించి అడుగు", "సారాంశం, ప్రశ్నలు", "file"),
                    fill("🖼️", "ఫోటోలు వెతుకు", "ఉదా: బీచ్ ఫోటోలు", "నా ఫోటోల్లో వెతుకు: "),
                    fill("📚", "నా డాక్యుమెంట్లలో అడుగు", "ఫోల్డర్‌లోని ఫైల్స్‌లో", "నా డాక్యుమెంట్లలో వెతికి చెప్పు: "),
                    ask("🔍", "QR కోడ్ స్కాన్", "స్క్రీన్ లేదా కెమెరాలో", "స్క్రీన్ మీద లేదా కెమెరాలో ఉన్న QR కోడ్ స్కాన్ చేసి చెప్పు.")),
            new Cat("live", "🎙️", "Live, భాషలు", "Live మాటలు, English, అనువాదం", Ui.C_BLUE,
                    act("🎙️", "Live సంభాషణ", "ChatGPT లా మాట్లాడదాం", "live"),
                    act("🗣️", "English practice", "మాట్లాడుతూ English నేర్చుకో", "english"),
                    fill("🌐", "అనువాదకుడిగా ఉండు", "తెలుగు ↔ వేరే భాష, ఇద్దరి మధ్య", "అనువాదకుడిగా ఉండు, భాష: "),
                    fill("🔤", "ఒక వాక్యం అనువదించు", "ఏ భాషలోకైనా", "దీన్ని ఇంగ్లీష్‌లో చెప్పు: ")),
            new Cat("phone", "📱", "ఫోన్ కంట్రోల్", "స్క్రీన్, యాప్స్, స్క్రీన్ టైమ్", Ui.C_CYAN,
                    ask("📱", "స్క్రీన్ చూడు", "స్క్రీన్‌లో ఏముందో చెప్తాను", "నా స్క్రీన్‌లో ఏముందో చూసి చెప్పు (look_at_screen వాడు)."),
                    fill("🤖", "ఫోన్‌లో ఏదైనా పని", "Jarvis యాప్‌లు వాడి చేస్తుంది", "ఫోన్‌లో ఈ పని చేయి: "),
                    fill("📲", "యాప్ తెరువు", "ఉదా: YouTube", "యాప్ తెరువు: "),
                    ask("🔋", "ఫోన్ స్టేటస్", "బ్యాటరీ, స్టోరేజ్, నెట్", "నా ఫోన్ బ్యాటరీ, స్టోరేజ్, నెట్ ఎలా ఉన్నాయి?"),
                    ask("⏳", "స్క్రీన్ టైమ్", "ఈరోజు ఏ యాప్ ఎంత", "ఈరోజు ఫోన్ ఎంత సేపు వాడాను? ఏ యాప్ ఎక్కువ?"),
                    fill("🚫", "యాప్ లిమిట్", "రోజూ ఇంత సేపు మాత్రమే", "ఈ యాప్‌కి రోజూ లిమిట్ పెట్టు: "),
                    ask("🔦", "టార్చ్ ఆన్", "", "టార్చ్ ఆన్ చెయ్."),
                    ask("🌙", "నైట్ మోడ్", "నిశ్శబ్దం, ఉదయం దానంతట అదే ఆఫ్", "నైట్ మోడ్ ఆన్ చెయ్.")),
            new Cat("health", "❤️", "ఆరోగ్యం, ఇల్లు", "అడుగులు, నీళ్లు, గాలి, లైట్లు", 0xFFF43F5E,
                    ask("👣", "ఈరోజు అడుగులు", "ఎన్ని అడుగులు నడిచాను", "ఈరోజు ఎన్ని అడుగులు నడిచాను?"),
                    ask("💧", "నీళ్లు తాగే రిమైండర్", "ప్రతి 2 గంటలకి", "ఉదయం 9 నుంచి రాత్రి 9 వరకు ప్రతి 2 గంటలకి నీళ్లు తాగమని గుర్తు చెయ్."),
                    ask("🌬️", "గాలి నాణ్యత", "ఇక్కడ AQI", "ఇక్కడ గాలి నాణ్యత ఎలా ఉంది?"),
                    fill("💡", "లైట్లు / ఫ్యాన్", "స్మార్ట్ హోమ్ ఆన్/ఆఫ్", "స్మార్ట్ హోమ్: "),
                    open("🏠", "స్మార్ట్ హోమ్ సెట్టింగ్స్", "Alexa routine లింక్స్", "స్మార్ట్ హోమ్")),
            new Cat("places", "📍", "నా ప్రదేశాలు", "సేవ్ చేసిన లొకేషన్లు, దారి", 0xFFE879F9,
                    info("📤", "Google Maps నుంచి సేవ్", "Maps లో చోటు → Share → Jarvis",
                            "Google Maps లో ఆ చోటు తెరవండి → Share (షేర్) నొక్కండి → యాప్‌ల లిస్ట్‌లో Jarvis ఎంచుకోండి → పేరు పెట్టండి (ఉదా: సిస్టర్ ఇల్లు) → సేవ్.\n\n"
                            + "తర్వాత \"Jarvis, సిస్టర్ వాళ్ల లొకేషన్ చూపించు\" అంటే మ్యాప్‌లో చూపిస్తాను, \"అక్కడికి దారి చూపించు\" అంటే navigation మొదలుపెడతాను."),
                    fill("📍", "ఇప్పుడున్న చోటు సేవ్ చెయ్", "ఉదా: ఇల్లు, ఆఫీస్, జిమ్", "ఇప్పుడు నేను ఉన్న చోటుని ఈ పేరుతో సేవ్ చెయ్: "),
                    fill("🔗", "Maps లింక్ సేవ్ చెయ్", "లింక్ పేస్ట్ చేసి పేరు చెప్పండి", "ఈ Google Maps లింక్‌ని ఈ పేరుతో సేవ్ చెయ్ (పేరు, లింక్): "),
                    fill("🏠", "అడ్రస్‌తో సేవ్ చెయ్", "ఉదా: సిస్టర్ ఇల్లు - KPHB, Hyderabad", "ఈ అడ్రస్ సేవ్ చెయ్ (పేరు - అడ్రస్): "),
                    fill("🧭", "సేవ్ చేసిన చోటుకి దారి", "ఉదా: సిస్టర్ ఇల్లు", "సేవ్ చేసిన ఈ చోటుకి దారి చూపించు: "),
                    fill("📤", "లొకేషన్ ఎవరికైనా పంపు", "WhatsApp లో మ్యాప్ లింక్", "సేవ్ చేసిన ఈ లొకేషన్‌ని WhatsApp లో పంపు (చోటు, ఎవరికి): ")),
            new Cat("shopping", "🛒", "షాపింగ్ లిస్ట్", "కొనాల్సినవి, కొన్నవి, షేర్", 0xFF84CC16,
                    fill("➕", "లిస్ట్‌లో చేర్చు", "ఉదా: పాలు 2, గుడ్లు, బ్రెడ్", "షాపింగ్ లిస్ట్‌లో చేర్చు: "),
                    fill("✅", "కొన్నాను", "ఉదా: పాలు, బ్రెడ్", "షాపింగ్ లిస్ట్‌లో ఇవి కొన్నాను: "),
                    ask("📋", "లిస్ట్ చెప్పు", "ఇంకా కొనాల్సినవి", "నా షాపింగ్ లిస్ట్‌లో ఇంకా కొనాల్సినవి చెప్పు."),
                    fill("📤", "లిస్ట్ WhatsApp లో పంపు", "ఎవరికో చెప్పండి", "నా షాపింగ్ లిస్ట్‌ని WhatsApp లో పంపు, ఎవరికి: "),
                    ask("🧹", "కొన్నవి తీసేయి", "టిక్ చేసినవి లిస్ట్ నుంచి", "షాపింగ్ లిస్ట్‌లో కొన్నవి తీసేయి.")),
            new Cat("medicine", "💊", "మందులు", "టైమ్‌కి గుర్తు, మాత్రల లెక్క", 0xFF2DD4BF,
                    fill("➕", "మందు చేర్చు", "పేరు, టైమ్స్, ఎన్ని మాత్రలు ఉన్నాయి", "ఈ మందు రిమైండర్ పెట్టు (పేరు, టైమ్స్, డోస్, భోజనం ముందు/తర్వాత, ఎన్ని మాత్రలు ఉన్నాయి): "),
                    fill("✅", "మాత్ర వేసుకున్నాను", "ఉదా: BP మాత్ర", "ఈ మాత్ర ఇప్పుడు వేసుకున్నాను: "),
                    ask("📋", "నా మందులు", "టైమ్స్, ఈరోజు వేసుకున్నవి, మిగిలినవి", "నా మందులు, ఈరోజు వేసుకున్నవి, ఎన్ని మాత్రలు మిగిలాయో చెప్పు."),
                    fill("🔢", "మాత్రల లెక్క మార్చు", "కొత్తగా కొన్నాక", "ఈ మందు మాత్రలు ఇప్పుడు ఇన్ని ఉన్నాయి (పేరు, ఎన్ని): "),
                    ask("📊", "ఈ వారం వేసుకున్నది", "ఎన్ని సార్లు మర్చిపోయాను", "ఈ వారం నా మందులు ఎన్ని సార్లు వేసుకున్నానో చెప్పు (medicine history).")),
            new Cat("birthdays", "🎂", "పుట్టినరోజులు", "పుట్టినరోజులు, పెళ్లిరోజులు, విషెస్", 0xFFF59E0B,
                    fill("➕", "పుట్టినరోజు చేర్చు", "ఉదా: అమ్మ - మార్చి 5", "ఈ పుట్టినరోజు గుర్తుపెట్టుకో (పేరు, తేదీ): "),
                    fill("💍", "పెళ్లిరోజు చేర్చు", "ఉదా: అక్క బావ - మే 20", "ఈ పెళ్లిరోజు గుర్తుపెట్టుకో (పేరు, తేదీ): "),
                    ask("📅", "ఈ నెల పుట్టినరోజులు", "రాబోయే 30 రోజులు", "రాబోయే 30 రోజుల్లో పుట్టినరోజులు, పెళ్లిరోజులు చెప్పు."),
                    fill("💬", "విషెస్ పంపు", "WhatsApp లో, మీరు 'పంపు' అంటేనే", "ఈ వ్యక్తికి పుట్టినరోజు విషెస్ WhatsApp లో సిద్ధం చెయ్: ")),
            new Cat("travel", "🌍", "ప్రయాణం, బయటకు", "దారి, ట్రైన్, టికెట్లు, ఫుడ్", 0xFF34D399,
                    fill("🗺️", "దారి చూపించు", "Maps లో navigation", "దారి చూపించు: "),
                    fill("🚆", "ట్రైన్ స్టేటస్", "ట్రైన్ నంబర్ లేదా PNR", "ట్రైన్ స్టేటస్: "),
                    fill("✈️", "ఫ్లైట్ / బస్ వెతుకు", "ఎక్కడి నుంచి ఎక్కడికి, ఎప్పుడు", "ఫ్లైట్ లేదా బస్ వెతుకు: "),
                    ask("🎫", "నా టికెట్లు", "రాబోయే బుకింగ్స్", "నా రాబోయే టికెట్లు, బుకింగ్స్ చెప్పు."),
                    fill("🚕", "క్యాబ్ (Uber/Ola/Rapido)", "మీరే బుక్ చేస్తారు", "క్యాబ్ యాప్ తెరువు: "),
                    fill("🍔", "ఫుడ్ ఆర్డర్", "Swiggy / Zomato తెరుస్తాను", "ఫుడ్ ఆర్డర్ కోసం తెరువు: "),
                    ask("📦", "నా పార్శిల్స్", "ఎక్కడున్నాయి", "నా పార్శిల్స్ ఎక్కడున్నాయి?"),
                    fill("📍", "అక్కడికి వెళ్తే గుర్తు చెయ్", "చోటు చేరగానే రిమైండర్", "ఈ చోటికి వెళ్లినప్పుడు గుర్తు చెయ్: ")),
            new Cat("fun", "📰", "వార్తలు, వినోదం", "వార్తలు, క్రికెట్, పాటలు", 0xFFFB923C,
                    ask("📰", "వార్తలు", "ముఖ్యమైన 3 వార్తలు", "ఈరోజు ముఖ్యమైన 3 వార్తలు చెప్పు: ఒకటి భారతదేశం, ఒకటి తెలంగాణ లేదా ఆంధ్రప్రదేశ్, ఒకటి టెక్నాలజీ. ఇంటర్నెట్‌లో వెతికి, చిన్నగా చెప్పు."),
                    ask("⛅", "వాతావరణం", "ఇప్పుడు, రేపు వర్షం", "ఇప్పుడు ఇక్కడ వాతావరణం ఎలా ఉంది? రేపు వర్షం పడే అవకాశం ఉందా?"),
                    ask("🏏", "క్రికెట్ లైవ్", "ఇండియా మ్యాచ్ అప్‌డేట్స్", "ఇండియా మ్యాచ్ ఉంటే లైవ్ అప్‌డేట్స్ చెప్తూ ఉండు."),
                    fill("▶️", "YouTube లో ప్లే", "పాట, వీడియో", "YouTube లో ప్లే చెయ్: "),
                    ask("🎵", "ఇది ఏ పాట?", "ఇప్పుడు ప్లే అవుతున్నది", "ఇప్పుడు ప్లే అవుతున్న పాట ఏది?"),
                    ask("😄", "ఒక జోక్", "", "ఒక చిన్న తెలుగు జోక్ చెప్పు."),
                    ask("🦾", "సూట్ అప్", "ఈరోజుకి సిద్ధం", "Jarvis, సూట్ అప్! ఈరోజుని ఎదుర్కోవడానికి నన్ను సిద్ధం చేయి.")),
            new Cat("code", "💻", "కోడింగ్, క్రియేట్", "వెబ్‌సైట్, యాప్, Python", 0xFFA78BFA,
                    fill("🌐", "వెబ్‌సైట్ తయారు చెయ్", "preview, లింక్", "ఒక వెబ్‌సైట్ తయారు చెయ్: "),
                    fill("📲", "Android యాప్", "APK తయారు చేస్తాను", "ఒక Android యాప్ తయారు చెయ్: "),
                    fill("🐍", "Python లెక్కలు", "చార్ట్, Excel, PDF", "Python తో లెక్కించు: "),
                    fill("💻", "కోడ్ రాయి", "ఏ భాషలోనైనా", "కోడ్ రాయి: "),
                    ask("🚀", "వెబ్‌సైట్ ఆన్‌లైన్ పెట్టు", "చివరి సైట్‌కి లింక్", "నా చివరి వెబ్‌సైట్‌ని ఆన్‌లైన్‌లో పెట్టు.")),
            new Cat("jarvis", "🤖", "Jarvis", "ఖర్చు, చెక్, సెట్టింగ్స్", Ui.C_TEAL,
                    ask("💰", "API ఖర్చు", "ఈ నెల ఎంత అయింది", "ఈ నెల API ఖర్చు ఎంత? బ్యాలెన్స్ ఎంత మిగిలింది?"),
                    ask("🤖", "ఏ మోడల్ మీద ఉన్నావ్?", "ఇప్పటి AI, మోడల్", "నువ్వు ఇప్పుడు ఏ AI, ఏ మోడల్ మీద నడుస్తున్నావ్?"),
                    open("🩺", "Jarvis చెక్", "మెసేజ్‌లు, గొంతు పనిచేయకపోతే", "చెక్"),
                    open("⚙️", "సెట్టింగ్స్", "అన్ని సెట్టింగ్స్", "")),
    };

    static Cat find(String id) {
        if (id == null) return null;
        for (Cat c : CATS) if (c.id.equalsIgnoreCase(id.trim())) return c;
        return null;
    }

    /** From the show_features tool: the folders, or one folder straight away. */
    static void show(Context c, String category) {
        c.startActivity(new Intent(c, FeaturesActivity.class).putExtra(EXTRA_CATEGORY, category == null ? "" : category)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- screen

    private LinearLayout content;
    private ScrollView scroll;
    private EditText search;
    private TextView title, backBtn;
    private Cat current;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG_TOP);
        getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new Ui.Aurora());
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setFocusableInTouchMode(true); // the search box stays closed until he taps it

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        backBtn = Ui.text(this, "‹", 30, 0xFFFFFFFF);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(0, 0, 0, dp(4));
        backBtn.setBackground(Ui.glass(this, 20));
        backBtn.setOnClickListener(v -> showGrid());
        backBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(10);
        head.addView(backBtn, blp);
        title = Ui.text(this, "", 22, 0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        IconView close = new IconView(this, IconView.CLOSE, 0xFFFFFFFF);
        close.setBackground(Ui.glass(this, 20));
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(head);

        search = new EditText(this);
        search.setHint("🔍  ఫీచర్ వెతకండి (బైక్, బిల్లు, రిమైండర్…)");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Ui.glass(this, 16));
        search.setPadding(dp(14), dp(11), dp(14), dp(11));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (!q.isEmpty()) showSearch(q);
                else if (current != null) showCat(current);
                else showGrid();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        slp.bottomMargin = dp(6);
        root.addView(search, slp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        Cat start = find(getIntent().getStringExtra(EXTRA_CATEGORY));
        if (start != null) showCat(start); else showGrid();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Cat c = find(intent.getStringExtra(EXTRA_CATEGORY));
        if (c != null) showCat(c);
    }

    @Override public void onBackPressed() {
        if (!search.getText().toString().isEmpty()) { search.setText(""); return; }
        if (current != null) { showGrid(); return; }
        super.onBackPressed();
    }

    /** The folders, two in a row. */
    private void showGrid() {
        current = null;
        if (!search.getText().toString().isEmpty()) { search.setText(""); return; } // the box calls back here once it is empty
        backBtn.setVisibility(View.GONE);
        title.setText("📂 అన్ని ఫీచర్లు");
        Ui.gradientText(title, Ui.C_CYAN, Ui.C_VIOLET);
        content.removeAllViews();
        TextView hint = Ui.text(this, "ఫోల్డర్ నొక్కితే దాని ఆప్షన్లు వస్తాయి", 13, Ui.MUTED);
        hint.setPadding(dp(2), dp(4), 0, dp(4));
        content.addView(hint);
        LinearLayout row = null;
        for (int i = 0; i < CATS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(10);
                content.addView(row, rlp);
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(128), 1);
            if (i % 2 == 0) tlp.rightMargin = dp(5); else tlp.leftMargin = dp(5);
            row.addView(tile(CATS[i]), tlp);
        }
        if (CATS.length % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(128), 1));
        scroll.scrollTo(0, 0);
    }

    private View tile(Cat c) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(12), dp(12), dp(12));
        android.graphics.drawable.GradientDrawable bg = Ui.grad(this, new int[]{Ui.alpha(c.color, 0x46), Ui.alpha(c.color, 0x12)}, 20,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setStroke(dp(1), Ui.alpha(c.color, 0x70));
        t.setBackground(bg);
        t.setForeground(Ui.ripple(this, 20));
        TextView e = Ui.text(this, c.emoji, 28, 0xFFFFFFFF);
        t.addView(e);
        TextView n = Ui.text(this, c.name, 16, 0xFFFFFFFF);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n);
        TextView bl = Ui.text(this, c.blurb, 12.5f, 0xCCFFFFFF);
        bl.setMaxLines(1);
        bl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(bl);
        TextView cnt = Ui.text(this, c.opts.length + " ఆప్షన్లు ›", 12, c.color);
        cnt.setPadding(0, dp(4), 0, 0);
        t.addView(cnt);
        t.setOnClickListener(v -> showCat(c));
        t.setContentDescription(c.name);
        return t;
    }

    /** One folder: its options. */
    private void showCat(Cat c) {
        current = c;
        backBtn.setVisibility(View.VISIBLE);
        title.getPaint().setShader(null);
        title.setTextColor(0xFFFFFFFF);
        title.setText(c.emoji + " " + c.name);
        content.removeAllViews();
        TextView hint = Ui.text(this, c.blurb, 13, Ui.MUTED);
        hint.setPadding(dp(2), dp(4), 0, dp(2));
        content.addView(hint);
        for (Opt o : c.opts) content.addView(row(c, o, false), rowParams());
        if ("places".equals(c.id)) addSavedPlaces(c);
        if ("shopping".equals(c.id)) addShopping(c);
        if ("medicine".equals(c.id)) addMedicines(c);
        if ("birthdays".equals(c.id)) addBirthdays(c);
        scroll.scrollTo(0, 0);
    }

    /** The places he saved: tap = on the map; hold = directions, share, delete. */
    private void addSavedPlaces(Cat c) {
        java.util.List<org.json.JSONObject> places = Places.all(this);
        TextView h = Ui.text(this, "సేవ్ చేసిన ప్రదేశాలు (" + places.size() + ")", 14, c.color);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setPadding(dp(2), dp(18), 0, dp(2));
        content.addView(h);
        if (places.isEmpty()) {
            TextView none = Ui.text(this, "ఇంకా ఏదీ సేవ్ చేయలేదు. పైన ఉన్న వాటిలో ఒకటి వాడండి, లేదా \"Jarvis, ఇది ఇంటి లొకేషన్‌గా సేవ్ చెయ్\" అనండి.", 13, Ui.MUTED);
            none.setPadding(dp(2), dp(6), dp(2), 0);
            content.addView(none);
            return;
        }
        TextView tip = Ui.text(this, "నొక్కితే మ్యాప్‌లో · నొక్కి పట్టుకుంటే దారి / షేర్ / తీసేయి", 12, Ui.MUTED);
        tip.setPadding(dp(2), 0, 0, 0);
        content.addView(tip);
        for (org.json.JSONObject p : places) {
            String where = p.optString("address", "");
            if (where.isEmpty()) where = p.has("lat") ? String.format(Locale.ENGLISH, "%.5f, %.5f", p.optDouble("lat"), p.optDouble("lon")) : "మ్యాప్ లింక్";
            View r = row(c, new Opt("📌", p.optString("name"), where, INFO, ""), false);
            r.setOnClickListener(v -> Places.open(this, p, false));
            r.setOnLongClickListener(v -> { placeMenu(p); return true; });
            content.addView(r, rowParams());
        }
    }

    private TextView header(Cat c, String text) {
        TextView h = Ui.text(this, text, 14, c.color);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setPadding(dp(2), dp(18), 0, dp(2));
        content.addView(h);
        return h;
    }

    private void empty(String text) {
        TextView none = Ui.text(this, text, 13, Ui.MUTED);
        none.setPadding(dp(2), dp(6), dp(2), 0);
        content.addView(none);
    }

    /** The list itself: tap = bought / not yet; hold = remove; a share button for what is left to buy. */
    private void addShopping(Cat c) {
        java.util.List<org.json.JSONObject> items = Shopping.items(this);
        int left = 0;
        for (org.json.JSONObject o : items) if (!o.optBoolean("done")) left++;
        header(c, "లిస్ట్ (" + left + " కొనాలి, " + (items.size() - left) + " కొన్నవి)");
        if (items.isEmpty()) { empty("లిస్ట్ ఖాళీగా ఉంది. \"Jarvis, లిస్ట్‌లో పాలు, గుడ్లు చేర్చు\" అనండి."); return; }
        TextView tip = Ui.text(this, "నొక్కితే ✅ కొన్నట్టు · నొక్కి పట్టుకుంటే తీసేయి", 12, Ui.MUTED);
        tip.setPadding(dp(2), 0, 0, 0);
        content.addView(tip);
        for (org.json.JSONObject o : items) {
            boolean done = o.optBoolean("done");
            View r = row(c, new Opt(done ? "✅" : "⬜", o.optString("item"), done ? "కొన్నారు" : "", INFO, ""), false);
            if (done) r.setAlpha(0.55f);
            String id = o.optString("id");
            r.setOnClickListener(v -> { Shopping.toggle(this, id); showCat(c); });
            r.setOnLongClickListener(v -> { Shopping.removeId(this, id); showCat(c); return true; });
            content.addView(r, rowParams());
        }
        if (left > 0) {
            View share = row(c, new Opt("📤", "ఇప్పుడే షేర్ చెయ్", "WhatsApp లేదా ఏ యాప్‌లోనైనా", INFO, ""), false);
            share.setOnClickListener(v -> {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Shopping.shareText(this));
                try { startActivity(Intent.createChooser(s, "షాపింగ్ లిస్ట్ పంపండి")); } catch (Exception ignored) {}
            });
            content.addView(share, rowParams());
        }
    }

    /** Each medicine with today's doses (✅ taken / ⬜ not yet); tap = mark the nearest dose taken. */
    private void addMedicines(Cat c) {
        java.util.List<org.json.JSONObject> meds = Medicine.all(this);
        header(c, "నా మందులు (" + meds.size() + ")");
        if (meds.isEmpty()) { empty("ఇంకా ఏ మందూ లేదు. \"Jarvis, BP మాత్ర రోజూ ఉదయం 8కి, రాత్రి 8కి గుర్తు చెయ్, 30 మాత్రలు ఉన్నాయి\" అనండి."); return; }
        java.util.List<String> today = Medicine.todayLines(this);
        TextView tip = Ui.text(this, "ఈరోజు: ✅ వేసుకున్నవి · ⬜ ఇంకా · నొక్కితే దగ్గర టైమ్ డోస్ వేసుకున్నట్టు", 12, Ui.MUTED);
        tip.setPadding(dp(2), 0, 0, 0);
        content.addView(tip);
        for (int i = 0; i < meds.size(); i++) {
            org.json.JSONObject m = meds.get(i);
            String stock = m.optInt("stock", -1) >= 0 ? " · " + m.optInt("stock") + " మాత్రలు" : "";
            View r = row(c, new Opt("💊", m.optString("name") + stock, i < today.size() ? today.get(i) : "", INFO, ""), false);
            r.setOnClickListener(v -> {
                org.json.JSONObject res = Medicine.taken(this, m, null);
                android.widget.Toast.makeText(this, res.optBoolean("already") ? "ఈ డోస్ ఇప్పటికే వేసుకున్నారు ✓" : "✅ " + m.optString("name") + " వేసుకున్నారు", android.widget.Toast.LENGTH_SHORT).show();
                showCat(c);
            });
            r.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage(m.optString("name") + " రిమైండర్ తీసేయాలా?")
                        .setPositiveButton("తీసేయి", (d, w) -> { Medicine.remove(this, m.optString("name")); showCat(c); })
                        .setNegativeButton("వద్దు", null).show();
                return true;
            });
            content.addView(r, rowParams());
        }
    }

    /** The coming birthdays and anniversaries (next 60 days); tap = wishes on WhatsApp. */
    private void addBirthdays(Cat c) {
        java.util.List<org.json.JSONObject> list = Birthdays.upcoming(this, 60);
        header(c, "రాబోయే 60 రోజులు (" + list.size() + ")");
        if (list.isEmpty()) { empty("ఏవీ లేవు. కాంటాక్ట్స్‌లో పుట్టినరోజు సేవ్ చేసినవి తనంతట తానే వస్తాయి; లేదా పైన చేర్చండి."); return; }
        TextView tip = Ui.text(this, "నొక్కితే WhatsApp విషెస్ సిద్ధం (మీరు 'పంపు' అంటేనే వెళ్తుంది)", 12, Ui.MUTED);
        tip.setPadding(dp(2), 0, 0, 0);
        content.addView(tip);
        for (org.json.JSONObject b : list) {
            String emoji = "anniversary".equals(b.optString("kind")) ? "💍" : "🎂";
            View r = row(c, new Opt(emoji, Birthdays.label(b), Birthdays.when(b) + " · " + b.optString("date"), INFO, ""), false);
            r.setOnClickListener(v -> {
                String kind = "anniversary".equals(b.optString("kind")) ? "పెళ్లిరోజు" : "పుట్టినరోజు";
                Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_ASK, b.optString("name") + " కి " + Birthdays.when(b) + " " + kind + ". WhatsApp లో తెలుగులో చిన్న, ఆత్మీయమైన "
                                + kind + " విషెస్ సిద్ధం చెయ్ (whatsapp_message); పంపే ముందు నాకు చదివి వినిపించి అడుగు.")
                        .putExtra(MainActivity.EXTRA_LABEL, emoji + " " + b.optString("name") + " కి " + kind + " విషెస్");
                startActivity(i);
                finish();
            });
            content.addView(r, rowParams());
        }
    }

    private void placeMenu(org.json.JSONObject p) {
        String name = p.optString("name");
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📌 " + name)
                .setItems(new String[]{"🗺️ మ్యాప్‌లో చూపించు", "🧭 దారి చూపించు", "📤 లింక్ షేర్ చెయ్", "🗑️ తీసేయి"}, (d, w) -> {
                    if (w == 0) Places.open(this, p, false);
                    else if (w == 1) Places.open(this, p, true);
                    else if (w == 2) {
                        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, name + "\n" + Places.shareLink(p));
                        try { startActivity(Intent.createChooser(s, name + " లొకేషన్ పంపండి")); } catch (Exception ignored) {}
                    } else {
                        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setMessage(name + " తీసేయాలా?")
                                .setPositiveButton("తీసేయి", (d2, w2) -> { Places.remove(this, name); if (current != null) showCat(current); })
                                .setNegativeButton("వద్దు", null).show();
                    }
                })
                .show();
    }

    /** Options from every folder that match what he typed. */
    private void showSearch(String q) {
        backBtn.setVisibility(View.VISIBLE);
        title.getPaint().setShader(null);
        title.setTextColor(0xFFFFFFFF);
        title.setText("🔍 వెతుకుతున్నాను");
        content.removeAllViews();
        String l = q.toLowerCase(Locale.ROOT);
        int n = 0;
        for (Cat c : CATS) {
            boolean catHit = c.name.toLowerCase(Locale.ROOT).contains(l) || c.blurb.toLowerCase(Locale.ROOT).contains(l);
            for (Opt o : c.opts) {
                if (catHit || o.title.toLowerCase(Locale.ROOT).contains(l) || o.desc.toLowerCase(Locale.ROOT).contains(l)) {
                    content.addView(row(c, o, true), rowParams());
                    n++;
                }
            }
        }
        Cat places = find("places");
        for (org.json.JSONObject p : Places.all(this)) {
            if (places == null || !p.optString("name").toLowerCase(Locale.ROOT).contains(l)) continue;
            View r = row(places, new Opt("📌", p.optString("name"), "సేవ్ చేసిన చోటు · నొక్కితే మ్యాప్‌లో", INFO, ""), false);
            r.setOnClickListener(v -> Places.open(this, p, false));
            r.setOnLongClickListener(v -> { placeMenu(p); return true; });
            content.addView(r, rowParams());
            n++;
        }
        if (n == 0) {
            TextView none = Ui.text(this, "\"" + q + "\" కి ఏ ఫీచర్ దొరకలేదు. Jarvis ని నేరుగా అడగండి, చాలా పనులు చేయగలడు.", 14, Ui.MUTED);
            none.setPadding(dp(2), dp(12), dp(2), 0);
            content.addView(none);
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        return lp;
    }

    private View row(Cat c, Opt o, boolean showFolder) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(11), dp(12), dp(11));
        android.graphics.drawable.GradientDrawable bg = Ui.round(this, Ui.alpha(c.color, 0x1C), Ui.alpha(c.color, 0x55), 16);
        r.setBackground(bg);
        r.setForeground(Ui.ripple(this, 16));
        TextView e = Ui.text(this, o.emoji, 20, 0xFFFFFFFF);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.round(this, Ui.alpha(c.color, 0x40), 0, 20));
        r.addView(e, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        TextView t = Ui.text(this, o.title, 15.5f, 0xFFFFFFFF);
        col.addView(t);
        String sub = (showFolder ? c.emoji + " " + c.name + (o.desc.isEmpty() ? "" : " · ") : "") + o.desc;
        if (!sub.isEmpty()) col.addView(Ui.text(this, sub, 12.5f, Ui.MUTED));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        String mark = o.type == FILL ? "✎" : o.type == OPEN ? "⚙" : o.type == INFO ? (o.payload.isEmpty() ? "›" : "ⓘ") : "›";
        TextView m = Ui.text(this, mark, o.type == ASK || o.type == DO ? 24 : 17, c.color);
        r.addView(m);
        r.setOnClickListener(v -> run(o));
        return r;
    }

    /** ASK: Jarvis answers; FILL: the request waits in the message box; DO: the action; OPEN: that part of Settings. */
    private void run(Opt o) {
        if (o.type == INFO) {
            if (o.payload.isEmpty()) return;
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(o.emoji + " " + o.title).setMessage(o.payload).setPositiveButton("సరే", null).show();
            return;
        }
        if (o.type == OPEN) {
            startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SECTION, o.payload));
            return;
        }
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (o.type == ASK) i.putExtra(MainActivity.EXTRA_ASK, o.payload).putExtra(MainActivity.EXTRA_LABEL, o.emoji + " " + o.title);
        else if (o.type == FILL) i.putExtra(MainActivity.EXTRA_FILL, o.payload);
        else i.putExtra(MainActivity.EXTRA_DO, o.payload);
        startActivity(i);
        finish();
    }
}
