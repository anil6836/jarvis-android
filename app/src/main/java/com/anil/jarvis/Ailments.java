package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Common illnesses in Telugu: home remedies first, then the usual over-the-counter tablet (adult dose), which doctor
 * to see if it doesn't settle, and the danger signs that mean hospital now. General information, not a prescription.
 * Anything not in the list is answered by the AI in the same shape (health_advice tells it how).
 */
final class Ailments {
    private Ailments() {}

    static final class A {
        final String id, name, doctor, seeIf, emergency;
        final String[] words, home, tablets;
        A(String id, String name, String[] words, String[] home, String[] tablets, String doctor, String seeIf, String emergency) {
            this.id = id; this.name = name; this.words = words; this.home = home; this.tablets = tablets;
            this.doctor = doctor; this.seeIf = seeIf; this.emergency = emergency;
        }
    }

    private static final String GP = "జనరల్ ఫిజిషియన్ (MBBS / MD డాక్టర్)";

    static final A[] ALL = {
            new A("fever", "జ్వరం", new String[]{"జ్వరం", "జొరం", "fever", "ఒళ్ళు వేడి", "ఒళ్లు వేడి", "temperature", "చలి జ్వరం"},
                    new String[]{"బాగా విశ్రాంతి తీసుకోండి; నీళ్లు, కొబ్బరి నీళ్లు, ORS, సూప్ ఎక్కువగా తాగండి",
                            "గోరువెచ్చని నీళ్లతో తడి గుడ్డతో ఒళ్ళు తుడవండి (చల్లని నీళ్లు కాదు)",
                            "తులసి, అల్లం, మిరియాలతో కషాయం; తేలికైన ఆహారం (జావ, ఇడ్లీ, రసం అన్నం)"},
                    new String[]{"Paracetamol 650 mg (Dolo 650 / Calpol 650 / Crocin): జ్వరం ఉంటేనే, 6 గంటలకి ఒకటి, రోజుకి 4 మాత్రలు మించకూడదు, తిన్న తర్వాత; "
                            + "మద్యం అలవాటు / లివర్ సమస్య ఉంటే రోజుకి 3 మించకూడదు",
                            "జ్వరంలో Brufen / Combiflam / Aspirin సొంతంగా వద్దు: డెంగ్యూ అయితే రక్తస్రావం ప్రమాదం",
                            "Sinarest, Vicks Action 500, D-Cold, Crocin Cold & Flu లాంటి జలుబు మాత్రల్లో ఇప్పటికే Paracetamol ఉంటుంది: వాటితో Dolo వేరుగా వేసుకోకండి"},
                    GP, "2-3 రోజుల్లో తగ్గకపోతే, లేదా 102°F దాటితే: డెంగ్యూ, మలేరియా, టైఫాయిడ్ పరీక్షలు చేయించుకోవాలి",
                    "104°F దాటడం, మెడ బిగుసుకుపోవడం, అయోమయం, ఊపిరి ఆడకపోవడం, ఆగని వాంతులు; జ్వరం తగ్గుతున్నప్పుడు తీవ్రమైన కడుపు నొప్పి, "
                            + "చిగుళ్ల / ముక్కు నుంచి రక్తం, ఒంటిపై ఎర్రటి మచ్చలు, నల్లటి మలం, బాగా మత్తు (డెంగ్యూ హెచ్చరిక: వెంటనే ఆసుపత్రి)"),
            new A("cough", "దగ్గు", new String[]{"దగ్గు", "cough", "కఫం", "కళ్ళె", "కళ్లె", "phlegm", "గళ్ళ"},
                    new String[]{"ఒక చెంచా తేనెలో కొద్దిగా అల్లం రసం కలిపి రోజుకి 2-3 సార్లు",
                            "గోరువెచ్చని ఉప్పు నీళ్లతో పుక్కిలించండి; పడుకునే ముందు పసుపు పాలు",
                            "వేడి నీళ్ల ఆవిరి పట్టండి; గోరువెచ్చని నీళ్లు తాగండి, చల్లని డ్రింక్స్, ఐస్ క్రీమ్ వద్దు",
                            "తులసి ఆకులు, మిరియాలు, లవంగం వేసిన కషాయం"},
                    new String[]{"పొడి దగ్గు (కఫం లేకుండా): Dextromethorphan ఉన్న సిరప్ (ఉదా: Benadryl DR), సీసా మీద రాసిన మోతాదు ప్రకారం; మత్తుగా అనిపిస్తే బైక్ నడపకండి",
                            "కఫంతో దగ్గు: Ambroxol సిరప్ (ఉదా: Mucolite / Ambrodil), సీసా మీద రాసిన మోతాదు ప్రకారం",
                            "గొంతు గరగరకి: Strepsils / Vicks లాజెంజెస్ నోట్లో చప్పరించండి"},
                    GP + "; 3 వారాలు దాటితే ఛాతి వైద్యుడు (Pulmonologist)",
                    "ఒక వారంలో తగ్గకపోతే; 3 వారాలు దాటితే TB పరీక్ష, ఛాతి ఎక్స్-రే అవసరం కావచ్చు",
                    "ఊపిరి ఆడకపోవడం, ఛాతి నొప్పి, కఫంలో రక్తం, పిల్లికూతలు (వీజింగ్), ఎక్కువ జ్వరం"),
            new A("cold", "జలుబు", new String[]{"జలుబు", "cold", "ముక్కు కారడం", "ముక్కు కారుతుంది", "ముక్కు దిబ్బడ", "ముక్కు మూసుకుపోయింది",
                    "తుమ్ము", "sneez", "runny nose", "blocked nose", "సైనస్", "sinus", "పడిశం"},
                    new String[]{"వేడి నీళ్ల ఆవిరి పట్టండి (కావాలంటే 2 చుక్కల యూకలిప్టస్ / Vicks వేసి)",
                            "అల్లం, తులసి, తేనె టీ; గోరువెచ్చని నీళ్లు ఎక్కువగా తాగండి",
                            "గోరువెచ్చని ఉప్పు నీళ్లతో పుక్కిలించండి; బాగా నిద్రపోండి"},
                    new String[]{"Cetirizine 10 mg (Okacet / Cetzine): రాత్రి పడుకునే ముందు ఒకటి, రోజుకి ఒక్కటే; మత్తుగా ఉంటుంది, వేసుకున్నాక బైక్ నడపకండి",
                            "ముక్కు దిబ్బడకి: Saline నాసల్ డ్రాప్స్ / స్ప్రే (ఉదా: Nasoclear); Otrivin లాంటివి 3 రోజులకి మించి వాడకూడదు",
                            "జలుబుతో ఒళ్ళు నొప్పులు, జ్వరం ఉంటే: Paracetamol 650 mg (పై మోతాదు ప్రకారం)",
                            "Sinarest / Vicks Action 500 / D-Cold లాంటి కాంబినేషన్ మాత్రల్లో Paracetamol ఇప్పటికే ఉంటుంది (Dolo వేరుగా వద్దు); "
                                    + "వాటిలోని Phenylephrine BP పెంచుతుంది (BP ఉంటే వద్దు), మత్తు కూడా వస్తుంది"},
                    GP + "; సైనస్ / చెవి నొప్పి ఉంటే ENT (చెవి-ముక్కు-గొంతు) డాక్టర్",
                    "7-10 రోజుల్లో తగ్గకపోతే, ముఖం / నుదురు నొప్పి (సైనస్), చెవి నొప్పి, పచ్చ / పసుపు రంగు కఫం, ఎక్కువ జ్వరం",
                    "ఊపిరి ఆడకపోవడం, ఎక్కువ జ్వరంతో మత్తుగా ఉండటం"),
            new A("headache", "తలనొప్పి", new String[]{"తలనొప్పి", "తల నొప్పి", "headache", "migraine", "మైగ్రేన్", "తల పట్టేసింది"},
                    new String[]{"ఒక పెద్ద గ్లాసు నీళ్లు తాగండి (నీళ్లు తక్కువైనా తలనొప్పి వస్తుంది)",
                            "చీకటి, నిశ్శబ్దంగా ఉన్న గదిలో కాసేపు పడుకోండి; ఫోన్ / స్క్రీన్ ఆపండి",
                            "నుదుటి మీద చల్లని / గోరువెచ్చని గుడ్డ; మెడ, భుజాలు నెమ్మదిగా మసాజ్; సమయానికి భోజనం, నిద్ర"},
                    new String[]{"Paracetamol 650 mg (Dolo 650 / Crocin): తిన్న తర్వాత ఒకటి, అవసరమైతే 6 గంటల తర్వాత మళ్లీ, రోజుకి 4 మించకూడదు"},
                    GP + "; తరచూ / మైగ్రేన్ అయితే నరాల డాక్టర్ (Neurologist); కళ్ళు లాగితే కంటి డాక్టర్",
                    "వారంలో చాలాసార్లు వస్తే, రోజూ మాత్ర అవసరం అయితే, కళ్ళజోడు / BP చెక్ చేయించుకోవాలి",
                    "హఠాత్తుగా జీవితంలో ఎప్పుడూ లేనంత తీవ్రమైన తలనొప్పి, దెబ్బ (బైక్ పడిపోవడం) తర్వాత తలనొప్పి, చూపు మసకబారడం, కాలు-చేయి బలహీనత, మాట తడబడటం, జ్వరంతో మెడ బిగుసుకుపోవడం"),
            new A("throat", "గొంతు నొప్పి", new String[]{"గొంతు నొప్పి", "గొంతు", "throat", "గొంతు గరగర", "మింగలేకపోతున్నా", "tonsil", "టాన్సిల్"},
                    new String[]{"గోరువెచ్చని ఉప్పు నీళ్లతో రోజుకి 3-4 సార్లు పుక్కిలించండి",
                            "తేనె, అల్లం టీ; పసుపు పాలు; గోరువెచ్చని నీళ్లు; గట్టిగా మాట్లాడకుండా గొంతుకి విశ్రాంతి"},
                    new String[]{"Strepsils / Vicks లాజెంజెస్: 2-3 గంటలకి ఒకటి చప్పరించండి (రోజుకి 8 మించకుండా)",
                            "నొప్పికి, జ్వరానికి: Paracetamol 650 mg (పై మోతాదు ప్రకారం)"},
                    "ENT (చెవి-ముక్కు-గొంతు) డాక్టర్ లేదా " + GP,
                    "5 రోజుల్లో తగ్గకపోతే, టాన్సిల్స్ మీద తెల్లటి మచ్చలు / చీము, ఎక్కువ జ్వరం",
                    "నోరు తెరవలేకపోవడం, ఉమ్ము మింగలేకపోవడం, ఊపిరి ఆడకపోవడం"),
            new A("acidity", "ఎసిడిటీ, గ్యాస్", new String[]{"ఎసిడిటీ", "acidity", "గ్యాస్", "gas", "కడుపు మంట", "గుండెల్లో మంట", "heartburn",
                    "పుల్లటి త్రేన్పులు", "త్రేన్పులు", "అజీర్ణం", "indigestion", "కడుపు ఉబ్బరం", "bloating"},
                    new String[]{"చల్లని మజ్జిగ; జీలకర్ర / సోంపు నీళ్లు; అరటి పండు",
                            "కారం, నూనె, మసాలా, టీ-కాఫీ, కూల్ డ్రింక్స్ తగ్గించండి; కొంచెం కొంచెం తరచూ తినండి",
                            "తిన్న వెంటనే పడుకోకండి (కనీసం 2 గంటలు); రాత్రి భోజనం త్వరగా, తేలికగా"},
                    new String[]{"Antacid సిరప్ (Digene / Gelusil): తిన్న తర్వాత లేదా మంట ఉన్నప్పుడు 2 చెంచాలు (10 ml)",
                            "Eno: ఒక ప్యాకెట్ ఒక గ్లాసు నీళ్లలో (ఉప్పు ఎక్కువ: BP ఉంటే వద్దు)",
                            "తరచూ వస్తే Pantoprazole 40 mg (Pan 40, ప్రిస్క్రిప్షన్ మందు) ఉదయం టిఫిన్‌కి అరగంట ముందు, 14 రోజులకి మించకూడదు; డాక్టర్ సలహాతో"},
                    GP + "; తరచూ ఉంటే కడుపు / జీర్ణకోశ డాక్టర్ (Gastroenterologist)",
                    "2 వారాలకి మించి ఉంటే, బరువు తగ్గుతుంటే, మింగడం కష్టంగా ఉంటే",
                    "ఛాతి నొప్పి చెమటలతో, ఎడమ చేయి / దవడకి పాకే నొప్పి (ఇది గుండెపోటు కావచ్చు: వెంటనే 108), నల్లటి మలం, రక్తం వాంతి"),
            new A("loose", "విరేచనాలు", new String[]{"విరేచనాలు", "loose motion", "diarrh", "మోషన్స్", "నీళ్ల విరేచనాలు", "విరోచనాలు", "బేదులు"},
                    new String[]{"ప్రతి విరేచనం తర్వాత ఒక గ్లాసు ORS / ఉప్పు-చక్కెర నీళ్లు",
                            "కొబ్బరి నీళ్లు, గంజి, పెరుగు అన్నం, అరటి పండు, ఇడ్లీ",
                            "పాలు, నూనె వస్తువులు, బయటి ఆహారం, కారం కొన్ని రోజులు వద్దు"},
                    new String[]{"ORS (Electral): ఒక ప్యాకెట్ ఒక లీటర్ కాచి చల్లార్చిన నీళ్లలో కలిపి, రోజంతా కొంచెం కొంచెం",
                            "విరేచనాలు ఆపే మాత్రలు (Loperamide / Racecadotril) జ్వరం, రక్తం లేనప్పుడు మాత్రమే, ఫార్మసిస్ట్ / డాక్టర్ సలహాతో"},
                    GP + "; తరచూ ఉంటే Gastroenterologist",
                    "2 రోజుల్లో తగ్గకపోతే, జ్వరం ఉంటే, మలంలో రక్తం / జిగురు ఉంటే",
                    "బాగా నీరసం, మూత్రం రాకపోవడం, నోరు ఎండిపోవడం, కళ్ళు తిరగడం (డీహైడ్రేషన్), ఆగని వాంతులు"),
            new A("vomit", "వాంతులు, వికారం", new String[]{"వాంతి", "వాంతులు", "vomit", "వికారం", "nausea", "కడుపులో తిప్పుతుంది"},
                    new String[]{"ORS / నిమ్మకాయ నీళ్లు చిన్న చిన్న గుటకలుగా (ఒకేసారి ఎక్కువ కాదు)",
                            "అల్లం ముక్క చప్పరించండి / అల్లం టీ; కొన్ని గంటలు ఘన ఆహారం వద్దు, తర్వాత తేలికగా (బిస్కెట్, గంజి)"},
                    new String[]{"ORS (Electral): కొంచెం కొంచెం తరచూ",
                            "వాంతులు ఆపే మాత్రలు (Ondansetron / Domperidone) డాక్టర్ చెప్పిన తర్వాతే"},
                    GP, "ఒక రోజులో తగ్గకపోతే, ఏమీ లోపల ఉండకపోతే",
                    "రక్తం వాంతి, తీవ్రమైన కడుపు నొప్పి, తల దెబ్బ తర్వాత వాంతులు, మూత్రం రాకపోవడం, బాగా నీరసం"),
            new A("pain", "ఒళ్ళు నొప్పులు, కీళ్ల / నడుము నొప్పి", new String[]{"ఒళ్ళు నొప్పులు", "ఒళ్లు నొప్పులు", "body pain", "కండరాల నొప్పి", "muscle",
                    "నడుము నొప్పి", "back pain", "మెడ నొప్పి", "కాలు నొప్పి", "కీళ్ల నొప్పి", "మోకాలి నొప్పి", "joint", "బెణుకు", "sprain", "భుజం నొప్పి"},
                    new String[]{"విశ్రాంతి; కొత్త దెబ్బ / బెణుకు అయితే మొదటి 2 రోజులు ఐస్ ప్యాక్, తర్వాత గోరువెచ్చని కాపడం",
                            "నెమ్మదిగా స్ట్రెచింగ్; బైక్ మీద నిటారుగా కూర్చోండి, ఎక్కువసేపు ఒకేలా కూర్చోకండి",
                            "గోరువెచ్చని నూనెతో మసాజ్ (బెణుకు కొత్తగా అయితే మసాజ్ వద్దు)"},
                    new String[]{"నొప్పి ఉన్న చోట జెల్ (Volini / Moov / Iodex) రోజుకి 2-3 సార్లు రాయండి",
                            "Paracetamol 650 mg: తిన్న తర్వాత, 6 గంటలకి ఒకటి, రోజుకి 4 మించకూడదు",
                            "Ibuprofen 400 mg (Brufen): తిన్న తర్వాతే, రోజుకి 3 మించకూడదు; జ్వరం ఉంటే వద్దు (డెంగ్యూ కావచ్చు), కడుపు అల్సర్, కిడ్నీ సమస్య, BP ఉంటే వద్దు"},
                    GP + "; కీళ్లు / నడుము / దెబ్బ నొప్పి అయితే ఎముకలు, కీళ్ల డాక్టర్ (Orthopedic); నరాలు లాగితే Neurologist",
                    "ఒక వారంలో తగ్గకపోతే, వాపు, నడవలేకపోతే",
                    "బైక్ పడిపోయిన తర్వాత ఎముక వంకరగా ఉండటం / కదపలేకపోవడం, కాళ్ళు తిమ్మిరి / మూత్రం ఆపుకోలేకపోవడం (నడుము నొప్పితో)"),
            new A("constipation", "మలబద్ధకం", new String[]{"మలబద్ధకం", "constipation", "మోషన్ రావట్లేదు", "మోషన్ సరిగ్గా రావట్లేదు"},
                    new String[]{"రోజూ 8-10 గ్లాసుల నీళ్లు; ఉదయం లేవగానే గోరువెచ్చని నీళ్లు",
                            "బొప్పాయి, అరటి, జామ, ఆకుకూరలు, పీచు ఉన్న ఆహారం; రోజూ నడక",
                            "రాత్రి నానబెట్టిన ఎండు ద్రాక్ష / అంజీర"},
                    new String[]{"Isabgol (Sat Isabgol): రాత్రి 1-2 చెంచాలు ఒక గ్లాసు నీళ్లు / పాలలో కలిపి, తర్వాత ఇంకో గ్లాసు నీళ్లు",
                            "Lactulose సిరప్ (Duphalac): సీసా మీద రాసిన మోతాదు ప్రకారం, కొన్ని రోజులు మాత్రమే"},
                    GP + "; తరచూ అయితే Gastroenterologist", "2 వారాలకి మించి ఉంటే, మలంలో రక్తం ఉంటే, బరువు తగ్గుతుంటే",
                    "తీవ్రమైన కడుపు నొప్పి, కడుపు ఉబ్బి వాంతులు, గ్యాస్ కూడా పోకపోవడం"),
            new A("allergy", "దురద, అలర్జీ, దద్దుర్లు", new String[]{"దురద", "itch", "అలర్జీ", "allergy", "దద్దుర్లు", "rash", "చర్మం ఎర్రబడింది"},
                    new String[]{"చల్లని నీళ్ల కాపడం; గోకకండి", "కొత్తగా వాడిన సబ్బు / ఆహారం / మందు వల్ల అయితే అది ఆపేయండి",
                            "వదులుగా ఉండే కాటన్ బట్టలు; కొబ్బరి నూనె / అలోవెరా జెల్"},
                    new String[]{"Cetirizine 10 mg: రాత్రి ఒకటి (మత్తు వస్తుంది, బైక్ నడపకండి)",
                            "Calamine లోషన్ (Lacto Calamine / Caladryl): దురద ఉన్న చోట రాయండి",
                            "Panderm, Quadriderm, Betnovate-N లాంటి స్టెరాయిడ్ కలిసిన క్రీములు సొంతంగా వద్దు (ఫంగస్ అయితే ఇంకా పెరుగుతుంది)"},
                    "చర్మ వ్యాధుల డాక్టర్ (Dermatologist)", "3-4 రోజుల్లో తగ్గకపోతే, ఒళ్ళంతా పాకితే, పుండ్లు / చీము ఉంటే",
                    "పెదాలు, కళ్ళు, నాలుక, గొంతు వాపు, ఊపిరి ఆడకపోవడం (వెంటనే 108)"),
            new A("tooth", "పంటి నొప్పి", new String[]{"పంటి నొప్పి", "పంటి", "పన్ను", "tooth", "చిగుళ్లు", "చిగుళ్ళు", "gums"},
                    new String[]{"గోరువెచ్చని ఉప్పు నీళ్లతో నోరు పుక్కిలించండి", "నొప్పి ఉన్న పంటి దగ్గర లవంగం / లవంగ నూనె దూదితో పెట్టండి",
                            "చల్లని / తీపి / బాగా వేడి పదార్థాలు ఆ వైపు నమలకండి"},
                    new String[]{"Paracetamol 650 mg (రోజుకి 4 మించకూడదు) లేదా Ibuprofen 400 mg (తిన్న తర్వాత, రోజుకి 3 మించకూడదు; జ్వరం, అల్సర్, కిడ్నీ సమస్య, BP ఉంటే వద్దు): "
                            + "నొప్పి తగ్గడానికి మాత్రమే, కారణం డాక్టర్ చూడాలి"},
                    "దంత వైద్యుడు (Dentist)", "నొప్పి 2 రోజుల్లో తగ్గకపోతే (పుచ్చు / ఇన్ఫెక్షన్‌కి చికిత్స కావాలి)",
                    "ముఖం / దవడ వాపు జ్వరంతో, నోరు తెరవలేకపోవడం"),
            new A("stomach", "కడుపు నొప్పి", new String[]{"కడుపు నొప్పి", "కడుపులో నొప్పి", "stomach pain", "stomach ache", "abdominal pain"},
                    new String[]{"గోరువెచ్చని నీళ్లు; వాము (అజ్వైన్) నీళ్లు; తేలికైన ఆహారం", "కడుపు మీద గోరువెచ్చని కాపడం"},
                    new String[]{"మంట / గ్యాస్ వల్ల అయితే: Antacid (Digene / Gelusil) 2 చెంచాలు",
                            "నొప్పి కారణం తెలియకుండా పెయిన్ కిల్లర్ వద్దు; మెలితిప్పే నొప్పికి మాత్ర (Dicyclomine) డాక్టర్ సలహాతో"},
                    GP + "; తరచూ అయితే Gastroenterologist", "కొన్ని గంటల్లో తగ్గకపోతే, జ్వరం / వాంతులతో ఉంటే",
                    "తీవ్రమైన నొప్పి, కుడి వైపు కింద నొప్పి (అపెండిసైటిస్ కావచ్చు), కడుపు గట్టిగా మారడం, రక్తం వాంతి / మలం"),
            new A("ear", "చెవి నొప్పి", new String[]{"చెవి నొప్పి", "చెవి", "ear pain", "earache"},
                    new String[]{"చెవి బయట గోరువెచ్చని కాపడం", "చెవిలో నూనె, ఇయర్ బడ్స్, ఏమీ పెట్టకండి; నీళ్లు పోనివ్వకండి"},
                    new String[]{"నొప్పికి: Paracetamol 650 mg (పై మోతాదు ప్రకారం); చెవి డ్రాప్స్ డాక్టర్ చూసిన తర్వాతే"},
                    "ENT (చెవి-ముక్కు-గొంతు) డాక్టర్", "ఒక రోజులో తగ్గకపోతే, చెవి నుంచి నీరు / చీము, వినపడటం తగ్గితే",
                    "హఠాత్తుగా వినపడకపోవడం, తల దెబ్బ తర్వాత చెవి నుంచి రక్తం / నీరు, తీవ్రమైన కళ్ళు తిరగడం"),
            new A("eye", "కళ్ళు ఎర్రబడటం, కండ్ల కలక", new String[]{"కండ్ల కలక", "కళ్ళు ఎర్ర", "కన్ను ఎర్ర", "కంటి", "కళ్ళు", "కన్ను", "eye pain", "red eye", "conjunctivitis"},
                    new String[]{"శుభ్రమైన చల్లని నీళ్లతో కళ్ళు కడగండి; కళ్ళు రుద్దకండి", "మీ టవల్, దిండు వేరుగా; చేతులు తరచూ కడగండి",
                            "బైక్ మీద కళ్ళజోడు / విజర్ వాడండి (దుమ్ము, గాలి)"},
                    new String[]{"Lubricant కంటి చుక్కలు (Refresh Tears / Tears Naturale) మంట, పొడిబారడానికి",
                            "స్టెరాయిడ్ / యాంటీబయాటిక్ కంటి చుక్కలు డాక్టర్ చెప్తేనే (సొంతంగా వేస్తే ప్రమాదం)"},
                    "కంటి డాక్టర్ (Ophthalmologist)", "2-3 రోజుల్లో తగ్గకపోతే, చీము, వెలుతురు చూడలేకపోతే",
                    "చూపు తగ్గడం, తీవ్రమైన కంటి నొప్పి, కంట్లో ఏదైనా గుచ్చుకోవడం / కెమికల్ పడటం (వెంటనే చాలా నీళ్లతో కడిగి ఆసుపత్రికి)"),
            new A("wound", "గాయం, దెబ్బ", new String[]{"గాయం", "దెబ్బ", "wound", "cut", "తెగింది", "రక్తం కారుతుంది", "గీసుకుపోయింది", "పడిపోయాను"},
                    new String[]{"శుభ్రమైన నీళ్లతో గాయం కడగండి; రక్తం కారితే శుభ్రమైన గుడ్డతో 10 నిమిషాలు గట్టిగా నొక్కి పట్టండి",
                            "పసుపు, మట్టి, కాఫీ పొడి లాంటివి పెట్టకండి"},
                    new String[]{"Antiseptic (Betadine / Savlon / Dettol) తో శుభ్రం చేసి, శుభ్రమైన బ్యాండేజ్ / Band-Aid",
                            "తుప్పు పట్టిన వస్తువు / రోడ్డు మీద పడి గాయం అయితే TT (టెటనస్) ఇంజెక్షన్ (5 ఏళ్లలో వేయించుకోకపోతే)"},
                    GP + " / దగ్గర్లోని ఆసుపత్రి", "గాయం లోతుగా / వెడల్పుగా ఉంటే (కుట్లు కావాలి), ఎర్రబడి వాపు / చీము వస్తే",
                    "రక్తం ఆగకపోవడం, తల దెబ్బ (వాంతులు, మత్తు, మరిచిపోవడం), ఎముక విరిగినట్టు ఉంటే, పాము కాటు (వెంటనే ఆసుపత్రి); "
                            + "కుక్క / పిల్లి కాటు: వెంటనే సబ్బు, పారే నీళ్లతో 15 నిమిషాలు కడిగి అదే రోజు రేబిస్ టీకా"),
            new A("burn", "కాలిన గాయం", new String[]{"కాలింది", "కాలిన", "burn", "వేడి నీళ్లు పడ్డాయి", "సైలెన్సర్"},
                    new String[]{"వెంటనే 20 నిమిషాలు చల్లని కుళాయి నీళ్ల కింద ఉంచండి (ఐస్ కాదు)",
                            "టూత్‌పేస్ట్, నూనె, వెన్న పెట్టకండి; బొబ్బలు చిదపకండి; ఉంగరాలు, వాచీ తీసేయండి"},
                    new String[]{"చిన్న కాలిన గాయానికి: చల్లార్చిన తర్వాత పెట్రోలియం జెల్లీ (Vaseline) పలుచగా, అంటుకోని శుభ్రమైన డ్రెస్సింగ్; Silverex లాంటి క్రీమ్ డాక్టర్ చెప్తేనే",
                            "నొప్పికి: Paracetamol 650 mg"},
                    GP + " / ఆసుపత్రి", "బొబ్బలు పెద్దవిగా ఉంటే, అరచేయి కంటే పెద్ద ప్రాంతం, 2-3 రోజుల్లో తగ్గకపోతే",
                    "ముఖం, చేతులు, మర్మాంగాలు కాలితే, పెద్ద ప్రాంతం, కరెంట్ / కెమికల్ వల్ల కాలితే (వెంటనే ఆసుపత్రి / 108)"),
            new A("weak", "నీరసం, కళ్ళు తిరగడం", new String[]{"నీరసం", "weak", "కళ్ళు తిరుగు", "కళ్ళు తిరగ", "dizz", "అలసట", "fatigue", "తల తిరుగుతుంది", "నిస్సత్తువ"},
                    new String[]{"కూర్చోండి / పడుకోండి; నీళ్లు, ORS, నిమ్మకాయ-ఉప్పు-చక్కెర నీళ్లు",
                            "సమయానికి భోజనం; ఎండలో ఎక్కువ తిరగకండి; సరిపడా నిద్ర (డ్యూటీ తర్వాత బాగా నిద్రపోండి)"},
                    new String[]{"ORS (Electral) ఒక గ్లాసు; రక్తహీనత / విటమిన్ మాత్రలు (Iron, B12) పరీక్ష చేసిన తర్వాతే"},
                    GP, "తరచూ వస్తే: BP, షుగర్, హీమోగ్లోబిన్, థైరాయిడ్ పరీక్షలు",
                    "స్పృహ తప్పడం, ఛాతి నొప్పి, గుండె దడ, మాట తడబడటం, ఒక వైపు బలహీనత (వెంటనే 108)"),
            new A("urine", "మూత్రంలో మంట", new String[]{"మూత్రంలో మంట", "మూత్రం", "urine", "యూరిన్", "మూత్రం మంట", "urinary"},
                    new String[]{"నీళ్లు బాగా తాగండి (రోజుకి 3 లీటర్లు); కొబ్బరి నీళ్లు, బార్లీ నీళ్లు", "మూత్రం ఆపుకోకండి"},
                    new String[]{"మూత్రం మంట తగ్గడానికి సిరప్ (Citralka / Alkasol) సీసా మీద రాసిన మోతాదు ప్రకారం",
                            "ఇన్ఫెక్షన్ అయితే యాంటీబయాటిక్ కావాలి: అది డాక్టర్ మాత్రమే ఇవ్వాలి (మూత్ర పరీక్ష చేసి)"},
                    GP + "; తరచూ అయితే కిడ్నీ / మూత్ర డాక్టర్ (Urologist)", "ఒకటి రెండు రోజుల్లో తగ్గకపోతే, జ్వరం ఉంటే",
                    "మూత్రంలో రక్తం, నడుము పక్క తీవ్రమైన నొప్పి, జ్వరం చలితో, మూత్రం పూర్తిగా ఆగిపోవడం"),
            new A("sleep", "నిద్ర పట్టకపోవడం", new String[]{"నిద్ర పట్టట్లేదు", "నిద్ర పట్టడం లేదు", "నిద్ర", "sleep", "insomnia"},
                    new String[]{"రోజూ ఒకే టైమ్‌కి పడుకోండి; పడుకునే గంట ముందు ఫోన్ వద్దు", "సాయంత్రం తర్వాత టీ, కాఫీ వద్దు; రాత్రి తేలికైన భోజనం",
                            "గోరువెచ్చని పాలు; నెమ్మదిగా లోతైన శ్వాస; డ్యూటీ తర్వాత గది చీకటిగా, చల్లగా ఉంచి నిద్రపోండి"},
                    new String[]{"నిద్ర మాత్రలు సొంతంగా వేసుకోకూడదు: అలవాటు అవుతాయి, డాక్టర్ సలహాతోనే"},
                    GP + "; ఎక్కువ రోజులు అయితే మానసిక వైద్యుడు (Psychiatrist) / Sleep specialist",
                    "2-3 వారాలకి మించి ఉంటే, పగలు పనిలో ఇబ్బంది అయితే, బాగా గురక పెడుతుంటే",
                    "బాధ, నిరాశ ఎక్కువగా ఉంటే వెంటనే ఎవరితోనైనా మాట్లాడండి; Tele-MANAS ఉచిత హెల్ప్‌లైన్ 14416"),
            new A("chest", "ఛాతి నొప్పి", new String[]{"ఛాతి నొప్పి", "ఛాతీ నొప్పి", "chest pain", "గుండె నొప్పి", "గుండె పట్టేసింది"},
                    new String[]{"వెంటనే కూర్చోండి, ఏ పనీ చేయకండి; ఒంటరిగా బైక్ నడిపి వెళ్లకండి"},
                    new String[]{"వెంటనే 108 కి ఫోన్ చేయండి / దగ్గర్లోని ఆసుపత్రికి (ఎవరైనా తీసుకెళ్లాలి)",
                            "108 వాళ్లు / డాక్టర్ చెప్తే Aspirin 325 mg (Disprin) ఒకటి నమలండి (అలర్జీ, రక్తం కారే అల్సర్ లేకపోతే)"},
                    "వెంటనే ఆసుపత్రి ఎమర్జెన్సీ; తర్వాత గుండె డాక్టర్ (Cardiologist)", "ఏ ఛాతి నొప్పి అయినా ఒకసారి డాక్టర్‌కి చూపించాలి",
                    "ఛాతి నొప్పి చెమటలతో, ఎడమ చేయి / దవడ / వీపుకి పాకడం, ఊపిరి ఆడకపోవడం (ఇది ఎమర్జెన్సీ: వెంటనే 108)"),
            new A("ulcer", "నోటి పూత", new String[]{"నోటి పూత", "నోట్లో పుండు", "mouth ulcer", "పూత"},
                    new String[]{"తేనె / కొబ్బరి నూనె పూత మీద రాయండి", "గోరువెచ్చని ఉప్పు నీళ్లతో పుక్కిలించండి; కారం, పులుపు తగ్గించండి; నీళ్లు ఎక్కువగా"},
                    new String[]{"నోటి పూత జెల్ (Dologel CT / Zytee) పూత మీద రోజుకి 3 సార్లు",
                            "తరచూ వస్తే B-complex మాత్ర (Becosules) రోజుకి ఒకటి, కొన్ని వారాలు"},
                    "దంత వైద్యుడు / " + GP, "2 వారాల్లో తగ్గకపోతే (తప్పకుండా చూపించాలి), తరచూ వస్తే", "పుండు పెరుగుతుంటే, రక్తం కారుతుంటే, నొప్పి లేని గట్టి పుండు"),
            new A("fungal", "తామర, ఫంగస్", new String[]{"తామర", "ఫంగస్", "fungal", "ringworm", "రింగ్ లాగా దురద", "గజ్జల్లో దురద", "తామర దురద", "ఫంగస్ దురద", "ఫంగల్ ఇన్ఫెక్షన్"},
                    new String[]{"ఆ చోటు పొడిగా ఉంచండి; చెమట బట్టలు వెంటనే మార్చండి (హెల్మెట్, జాకెట్ లోపల చెమట)", "వదులుగా ఉండే కాటన్ బట్టలు; టవల్ ఎవరితోనూ పంచుకోకండి"},
                    new String[]{"Clotrimazole 1% క్రీమ్ (Candid): రోజుకి 2 సార్లు, 2-4 వారాలు; తగ్గిన తర్వాత కూడా ఒక వారం",
                            "Panderm, Quadriderm, Betnovate-N లాంటి స్టెరాయిడ్ మిక్స్ క్రీములు వద్దు: ఫంగస్ పెరుగుతుంది"},
                    "చర్మ వ్యాధుల డాక్టర్ (Dermatologist)", "2 వారాల్లో తగ్గకపోతే, ఒళ్ళంతా పాకితే, షుగర్ ఉంటే", "చీము, జ్వరం, వేగంగా పెరుగుతున్న ఎర్రటి వాపు"),
            new A("scabies", "గజ్జి", new String[]{"గజ్జి", "scabies", "రాత్రి దురద ఎక్కువ"},
                    new String[]{"బట్టలు, దుప్పట్లు వేడి నీళ్లలో ఉతికి ఎండలో ఆరబెట్టండి", "గోళ్లు చిన్నగా కత్తిరించండి; గోకకండి"},
                    new String[]{"Permethrin 5% క్రీమ్ డాక్టర్ సలహాతో; ఇంట్లో అందరూ ఒకేసారి వాడాలి (లేకపోతే మళ్లీ వస్తుంది)", "దురదకి Cetirizine 10 mg రాత్రి (బైక్ నడపకండి)"},
                    "చర్మ వ్యాధుల డాక్టర్ (Dermatologist)", "గజ్జి అనుమానం ఉంటే డాక్టర్‌కి చూపించాలి (ప్రిస్క్రిప్షన్ క్రీమ్ కావాలి)", "పుండ్లు చీము పట్టడం, జ్వరం"),
            new A("heat", "వడదెబ్బ", new String[]{"వడదెబ్బ", "ఎండ దెబ్బ", "heat stroke", "sunstroke", "ఎండకి"},
                    new String[]{"వెంటనే నీడ / చల్లని చోటుకి; తడి గుడ్డతో ఒళ్ళు తుడవండి, ఫ్యాన్ గాలి", "ORS, మజ్జిగ, కొబ్బరి నీళ్లు, నిమ్మకాయ నీళ్లు"},
                    new String[]{"ORS (Electral): కొంచెం కొంచెం తరచూ; జ్వరం మాత్ర వడదెబ్బకి పనిచేయదు, చల్లబరచడమే ముఖ్యం"},
                    GP + " / ఆసుపత్రి", "గంటలో నీరసం తగ్గకపోతే",
                    "అయోమయం, స్పృహ తప్పడం, ఒళ్ళు బాగా వేడిగా పొడిగా (చెమట లేకుండా), ఫిట్స్ (వెంటనే 108)"),
    };

    /** The illness he means: the longest name / word from the list found in what he said. */
    static A find(String said) {
        if (said == null) return null;
        String s = said.toLowerCase(Locale.ROOT);
        A best = null;
        int bestLen = 0;
        for (A a : ALL) {
            for (String w : a.words) {
                String k = w.toLowerCase(Locale.ROOT);
                if (k.length() > bestLen && s.contains(k)) { best = a; bestLen = k.length(); }
            }
            if (a.name.length() > bestLen && s.contains(a.name)) { best = a; bestLen = a.name.length(); }
        }
        // fever with body pains is a fever (dengue, flu...), not a muscle ache: never the ibuprofen answer
        if (best != null && best.id.equals("pain")) {
            for (String w : ALL[0].words) if (s.contains(w.toLowerCase(Locale.ROOT))) return ALL[0];
        }
        return best;
    }

    private static JSONArray arr(String[] x) {
        JSONArray a = new JSONArray();
        for (String s : x) a.put(s);
        return a;
    }

    static final String SAFETY = "Adult doses. For a child, a pregnant woman, someone elderly, with kidney / liver / heart disease, or taking other medicines: "
            + "confirm with a pharmacist or doctor first. Never suggest antibiotics, steroids or sleeping pills on your own: only a doctor gives those.";

    /** For the health_advice tool. want: home (default), tablet, doctor or all. */
    static JSONObject advice(Context c, String symptom, String want) throws Exception {
        String w = want == null ? "" : want.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) w = "home";
        A a = find(symptom);
        JSONObject o = new JSONObject().put("ok", true).put("asked", symptom == null ? "" : symptom);
        JSONArray his = new JSONArray();
        try { for (JSONObject m : Medicine.all(c)) if (!m.optString("name").isEmpty()) his.put(m.optString("name")); } catch (Exception ignored) {}
        if (his.length() > 0) o.put("his_regular_medicines", his).put("check", "He takes these regularly: mention if a suggested tablet does not go with them (e.g. cold tablets with phenylephrine and BP medicines), or say to ask the pharmacist.");
        if (a == null) {
            return o.put("matched", "none").put("safety", SAFETY)
                    .put("next", "Not in Jarvis's list. Answer from general medical knowledge in the same order, in short Telugu: "
                            + (w.startsWith("tab") ? "the usual over-the-counter medicine (generic name + a common Indian brand, adult dose, how to take, main caution) if there is a safe one; if it needs a prescription, say the doctor decides. "
                            : w.startsWith("doc") ? "which doctor (specialist) to see and when to go now. "
                            : "a line of care, 2-3 home remedies first; then ask if he wants the tablet name. ")
                            + "Always say which doctor if it does not settle, and the danger signs that mean hospital / 108 now. "
                            + "For long-term diseases (sugar, BP, thyroid, TB...) medicines are only what his doctor prescribes: say which specialist.");
        }
        o.put("matched", a.name).put("emergency_signs", a.emergency);
        if (w.startsWith("home") || w.startsWith("all")) o.put("home_remedies", arr(a.home));
        if (w.startsWith("tab") || w.startsWith("med") || w.startsWith("all")) o.put("tablets", arr(a.tablets)).put("safety", SAFETY);
        if (w.startsWith("doc") || w.startsWith("tab") || w.startsWith("med") || w.startsWith("all")) o.put("doctor", a.doctor).put("see_doctor_if", a.seeIf);
        String next;
        if (w.startsWith("home")) next = "One caring line, then the home remedies in short Telugu (2-3 of them, spoken naturally). Then ask: 'టాబ్లెట్ పేరు కావాలా?' "
                + "If anything he said matches the emergency signs, say that first: go to hospital now / call 108 (offer to call).";
        else if (w.startsWith("tab") || w.startsWith("med")) next = "Say the tablet name(s) and how to take it (dose, when, how many a day), the main caution in one line, "
                + "then: if it doesn't settle (see_doctor_if), which doctor to see. Offer to set a medicine reminder (medicine tool) if he will take it for some days. "
                + "It is general information, not a doctor's prescription (say this once, briefly).";
        else if (w.startsWith("doc")) next = "Say which doctor, when to go, and the danger signs. If he wants one nearby: open_maps with place like '"
                + "ENT doctor near me' / 'hospital near me'.";
        else next = "Home remedies first, then the tablet with dose and caution, then which doctor and when, in short Telugu.";
        return o.put("next", next);
    }
}
