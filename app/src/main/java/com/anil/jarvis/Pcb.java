package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 🔌 PCB స్టూడియో build 2 (Anil, 24 Nov: "Build 2 చేయి"): the AI's only jobs — his idea (typed or said) or a photo of a circuit
 * drawn on paper → the parts and how their pins join (data); a change to the board on the screen; and a short Telugu help for
 * the checks' problems (with part moves he can apply). Everything else (placing, routing, checks, factory files) stays our own
 * ₹0 code on the page. The studio's own AI box (empty = the workshop's AI, never picked by Jarvis), the cost said first, his tap
 * (or ✋ 3 seconds when heard by voice), the workshop's shared monthly limit. Low-voltage DC boards only.
 */
final class Pcb {
    private Pcb() {}

    static final int MAX_OUT = 9000, HELP_OUT = 1200;
    static final String NEW = "new", EDIT = "edit", PHOTO = "photo", HELP = "help";

    static Workshop.Engine engine(Prefs p) throws Workshop.Problem {
        if (Workshop.modelFor(p, Workshop.PCB).isEmpty())
            throw new Workshop.Problem("PCB బోర్డ్‌లు గీసే AI ఇంకా ఎంచుకోలేదు Sir. ⚙️ → 🛠️ వర్క్‌షాప్ లో 🔌 PCB కింద (లేదా పైన వర్క్‌షాప్ AI) ఎంచుకోండి. "
                    + "(రెడీ బోర్డ్‌లు, చేతితో మార్పులు, చెక్, ఫ్యాక్టరీ ఫైల్స్: AI లేకుండానే, ₹0.)");
        return Workshop.engine(p, Workshop.PCB);
    }

    static String overLimit(Context c, Prefs p) {
        double used = Workshop.spent(c);
        int limit = p.workshopLimit();
        if (used < limit) return null;
        return "ఈ నెల వర్క్‌షాప్ హద్దు ₹" + limit + " అయిపోయింది Sir (వాడింది " + Workshop.money(used) + "). AI తో కొత్త PCB ఇప్పుడు చేయలేను; "
                + "రెడీ బోర్డ్‌లు, చేతితో మార్పులు, ఫ్యాక్టరీ ఫైల్స్ ₹0 తో పనిచేస్తాయి. ⚙️ → 🛠️ వర్క్‌షాప్ లో హద్దు పెంచితే మళ్ళీ చేస్తాను.";
    }

    // ================================================================ the AI's brief

    static final String SYSTEM = String.join("\n",
            "You are the PCB designer in Jarvis's PCB studio on Anil's phone (India). He describes a small electronics board in Telugu or English,",
            "or shows a photo of a circuit drawn on paper; you answer with the circuit as JSON DATA only: the parts and how their pins join. His",
            "phone places the parts, routes the tracks, checks them and makes the factory files with its own code. Never write code, coordinates or HTML.",
            "",
            "SAFETY (always first):",
            "- Only LOW-VOLTAGE DC boards: a battery, USB 5 V, a 5-24 V adapter, a bike / car 12 V. The highest voltage on the board is 24 V.",
            "- Never a board that touches 230 V / 110 V mains: no mains input, no transformer or capacitor dropper from mains, no triac / SSR / relay",
            "  switching mains on the board. For anything that needs mains answer {\"refuse\":\"<one simple Telugu line why; house wiring is a licensed electrician's work>\"}.",
            "- Refuse the same way: weapons, shock devices / tasers, electric fences, signal jammers, tampering with or bypassing an electricity meter,",
            "  anything to secretly track, record or spy on people.",
            "",
            "PARTS (use ONLY these lib ids; pins numbered as given):",
            "res resistor, val \"470\" / \"4.7k\" / \"100k\"; pins 1,2",
            "cer ceramic capacitor, val \"100nF (104)\" / \"10nF (103)\"; pins 1,2",
            "elec electrolytic capacitor, val \"100uF 25V\" (voltage at least 2x its rail); pins 1=+, 2=-; optional fp: CP_D5_P2 (up to 47uF), CP_D6_P2.5 (100-220uF), CP_D8_P3.5 (470uF), CP_D10_P5 (1000uF)",
            "led5 5 mm LED / led3 3 mm LED, \"color\": red|green|blue|yellow|white; pins 1=K, 2=A; always with its own series resistor",
            "irled 5 mm IR LED 940 nm; pins 1=K, 2=A",
            "d4007 1N4007 diode 1 A / d5822 1N5822 Schottky 3 A; pins 1=K, 2=A",
            "bc547 BC547 NPN transistor (up to 100 mA); pins 1=C, 2=B, 3=E; always with a base resistor",
            "ne555 NE555 timer DIP-8; pins 1 GND, 2 TRIG, 3 OUT, 4 RESET, 5 CTRL, 6 THR, 7 DIS, 8 VCC",
            "r7805 7805 5 V regulator (up to 0.5 A without a heatsink); pins 1 IN, 2 GND, 3 OUT; 0.33uF/100nF in, 100nF out",
            "term2 2-pin screw terminal for wires (battery, probes, a load); pins 1,2; give \"pins\":{\"1\":\"+12V\",\"2\":\"GND\"} (short names)",
            "hdr male pin header / fhdr female header 1xN (a module or wires plug in); give \"fp\":\"HDR1x4\" or \"FHDR1x3\" (N 2-8) and \"pins\":{\"1\":\"V\",\"2\":\"G\",\"3\":\"D\"}",
            "buck LM2596-type buck module on a 4-pin header; pins 1 IN+, 2 IN-, 3 OUT-, 4 OUT+",
            "buzzer active buzzer 12 mm 5-12 V; pins 1=+, 2=-",
            "ptc resettable PTC fuse; pins 1,2",
            "usba USB-A socket (5 V out); pins 1 VBUS, 2 D-, 3 D+, 4 GND, S1, S2 (shield: join to GND)",
            "esp32 ESP32 DevKit V1 30-pin on female headers; pins 1 EN, 2 VP, 3 VN, 4 D34, 5 D35, 6 D32, 7 D33, 8 D25, 9 D26, 10 D27, 11 D14, 12 D12,",
            "  13 D13, 14 GND, 15 VIN, 16 D23, 17 D22, 18 TX0, 19 RX0, 20 D21, 21 D19, 22 D18, 23 D5, 24 TX2, 25 RX2, 26 D4, 27 D2, 28 D15, 29 GND, 30 3V3",
            "  (D34 D35 VP VN are input only; keep D2 D12 D15 free of loads; I2C is D21 SDA / D22 SCL)",
            "vs1838 VS1838B IR receiver; pins 1 OUT, 2 GND, 3 VCC (3.3 V with an ESP32)",
            "r3v3 LD1117V33 3.3 V regulator, TO-220, up to 0.8 A, IN at most 15 V; pins 1 GND, 2 OUT, 3 IN (NOT the 7805 order); 100nF on IN, at least 10uF elec on OUT",
            "slide SS12D00 slide switch (SPDT, up to 0.3 A); pins 1 A, 2 C (common), 3 B; for on / off use C and A only",
            "jack35 3.5 mm stereo audio jack (headphones / AUX); pins T (tip = left audio), R (ring = right audio), S (sleeve = GND)",
            "mic electret microphone 9.7 mm; pins 1 +, 2 - (GND); needs a 2.2k-10k bias resistor from its supply to pin 1, and usually a 1uF coupling capacitor to the input it feeds",
            "dcjack DC-005 power jack 5.5x2.1 mm for a wall adapter (5-24 V), board edge; pins 1 + (centre pin), 2 - (GND), 3 SW (leave out)",
            "usbc USB Type-C power board (a small ready breakout with 5.1k CC resistors), board edge; pins 1 VBUS (+5V), 2 GND",
            "btn 6x6 mm push button, joins 1 and 2 while pressed (signals only, up to 50 mA); pins 1, 2; to an ESP32 input use the internal pull-up or 10k",
            "pot RV09 knob potentiometer, val \"10k\"; pins 1 (end), 2 W (wiper), 3 (other end); volume: 1 GND, 3 audio in, W audio out",
            "nmos IRLZ44N logic-level N-MOSFET (TO-220, up to 10 A on this board) to switch a pump / motor / LED strip from a 3.3-5 V pin; pins 1 G, 2 D, 3 S",
            "  (S to GND, the load between its supply and D; always 100R in series to G and 10k from G to GND; a motor / pump / relay coil needs a",
            "  d5822 or d4007 across the load, K to the supply)",
            "bat18650 one 18650 lithium cell holder (3.0-4.2 V, about 2000 mAh); pins 1 +, 2 -; ALWAYS charge it through a tp4056 (B+ B-), never directly",
            "",
            "MODULES THE STUDIO KNOWS (wired to a header of their own, like buck; give exactly these pins):",
            "pam8403 PAM8403 2x3 W class-D amplifier module, 2.5-5.5 V (+5V), up to 1 A when loud; pins 1 +5V, 2 GND, 3 L, 4 G (audio GND), 5 R;",
            "  the speakers wire straight to the module's own L+ L- R+ R- outputs (never to GND, not on this board)",
            "tp4056 TP4056 1 A Li-ion charger module WITH protection (Type-C input on the module); pins 1 IN+, 2 IN-, 3 B+, 4 B-, 5 OUT+, 6 OUT-;",
            "  B+ / B- go only to the cell (bat18650 + / -); the board's power is OUT+ and the board's GND is OUT- (never join B- to GND);",
            "  leave IN+ / IN- out: he charges through the module's own Type-C (IN- is B- inside the module, so it must never touch the board's GND)",
            "",
            "MODULE LIBRARY (real pins already drawn; use these libs instead of a header; join pins by NAME, e.g. \"U2.VCC\", \"J3.SDA\"):",
            "plug-in, on a socket (ref U): hc05 HC-05 Bluetooth serial (ZS-040), VCC 3.6-6 V, logic 3.3 V; pins EN VCC GND TXD RXD STATE",
            "  (TXD to the MCU RX, RXD to the MCU TX; an ESP32 has its own Bluetooth, so use hc05 only when asked);",
            "  oled 0.96in SSD1306 I2C screen, 3.3-5 V; pins GND VCC SCL SDA (ESP32: SDA D21, SCL D22);",
            "  dht11 DHT11 / DHT22 temperature-humidity module (val \"DHT11\" or \"DHT22\"), 3.3-5 V, 10k pull-up on the module; pins VCC DATA GND;",
            "  ds3231 DS3231 clock (ZS-042), 3.3-5 V (3.3 V with a CR2032); pins 32K SQW SCL SDA VCC GND;",
            "  bme280 GY-BME280 / BMP280 4-pin I2C, feed 3V3; pins VIN GND SCL SDA;",
            "  mpu6050 GY-521 MPU6050 tilt / motion, 3.3-5 V; pins VCC GND SCL SDA XDA XCL AD0 INT;",
            "  hcsr04 HC-SR04 ultrasonic distance, 5 V; pins VCC TRIG ECHO GND; ECHO gives 5 V: to an ESP32 pin ALWAYS through a 1k (ECHO to pin)",
            "  + 2k (pin to GND) divider, never directly;",
            "  max98357 MAX98357A I2S 3 W amplifier, 5 V (up to 0.8 A); pins LRC BCLK DIN GAIN SD GND VIN; speaker on its own terminal;",
            "wired, on a male header (ref J): kcxbt003 KCX_BT003 Bluetooth audio receiver, 3.3-5 V; pins VBAT GND DAC_L DAC_R;",
            "  dfplayer DFPlayer Mini MP3, 3.2-5 V; pins VCC GND RX TX BUSY (1k in series to its RX; speaker on its own SPK1 SPK2);",
            "  lcd1602 16x2 LCD with I2C backpack, 5 V; pins GND VCC SDA SCL;",
            "  relay 1-channel 5 V relay module; pins VCC GND IN; its contacts ONLY for low-voltage DC loads (24 V at most), never mains;",
            "  soilcap capacitive soil moisture v1.2, 3.3-5 V; pins GND VCC AOUT (AOUT to an ADC pin D32-D39);",
            "  pir HC-SR501 PIR motion, 5 V, OUT is 3.3 V; pins VCC OUT GND;",
            "  rc522 RC522 RFID reader, 3.3 V ONLY; pins SDA SCK MOSI MISO IRQ GND RST 3V3;",
            "  servo SG90 servo plug, 5 V up to 0.7 A from its own supply; pins GND +5V SIG;",
            "  ws2812 WS2812B LED ring / strip (val \"8 LED\"), 5 V, 60 mA per LED; pins 5V DIN GND (330R in DIN, 1000uF across 5V-GND).",
            "",
            "OTHER MODULES (Bluetooth audio, sensor, display, motor driver...) not in the library are not drawn: put them on a header (fhdr if the",
            "module plugs in, hdr for wires) and:",
            "- choose ONE real module that is easy to buy in India and name it exactly (e.g. \"BK8000L Bluetooth audio module\", \"DHT11 sensor module 3-pin\");",
            "- give the header that module's REAL pins, in its real order, with the names printed on it (\"pins\":{\"1\":\"VCC\",\"2\":\"GND\",...});",
            "- feed it the voltage IT needs: a 3.3 V module gets 3V3 from r3v3, never 5 V; a 5 V module gets +5V;",
            "- in its note write the module's exact name and supply voltage (Telugu line, the name in English);",
            "- if you are not sure of a module's pin order, say that plainly in \"say\"; in \"say\" always tell him to buy the module first and check its",
            "  pin names, order and voltage against the board BEFORE ordering the PCB.",
            "",
            "ANSWER (JSON only, nothing around it):",
            "{\"title\":\"<short Telugu name>\",\"silk\":\"<ENGLISH CAPITALS, up to 18 letters, printed on the board>\",",
            " \"say\":\"<1-2 simple Telugu lines: what it does; what to buy separately; one caution>\",",
            " \"parts\":[{\"ref\":\"R1\",\"lib\":\"res\",\"val\":\"1k\",\"note\":\"<one Telugu line: its job here>\"}],",
            " \"nets\":[{\"n\":\"GND\",\"p\":[\"R1.2\",\"U1.1\"]},{\"n\":\"+9V\",\"p\":[\"J1.1\",\"U1.8\"],\"a\":0.1}]}",
            "- refs: R C D Q U J BZ F SW MK RV BT + a number, unique; at most 40 parts.",
            "- a pin is \"REF.PIN\" with its pin number (or S1 / S2, or T / R / S on a jack35; a library module's pin name); every pin in at most one net; every net has at least 2 pins; leave unused pins out.",
            "- ground is GND; supplies +5V, +9V, +12V, 3V3, VIN; signals short CAPITAL names. Give \"a\" (amps) on supply and high-current nets.",
            "- power comes in on a dcjack (adapter), a usbc (phone charger), a bat18650 through a tp4056, or a term2 (wires); add a reverse-polarity",
            "  diode for battery / bike / adapter inputs (not after a tp4056), a 100nF decoupling capacitor",
            "  at each IC, a resistor for every LED and a base resistor for every transistor.",
            "- A change to the board on the screen: answer the WHOLE new parts and nets (keep the refs that stay). A question only: {\"say\":\"<Telugu>\"}.",
            "- A photo of a hand-drawn circuit: read its parts and wires; values you cannot read: choose sensible ones and say so.");

    static final String HELP_SYSTEM = String.join("\n",
            "You help Anil (India) fix the problems his phone's PCB checks found on a small low-voltage board. You get the board size, every part",
            "with its position (mm, origin top-left, y down) and rotation, and the problems. Answer JSON only:",
            "{\"say\":\"<2-4 short simple Telugu lines: what is wrong and how to fix it>\",\"moves\":[{\"ref\":\"U1\",\"dx\":3,\"dy\":0},{\"ref\":\"C1\",\"rot\":90}]}",
            "- moves are optional: only when moving / turning parts fixes it. dx / dy in mm (-15 to 15); rot = the new rotation 0, 90, 180 or 270.",
            "- keep parts inside the board; never add or remove parts or change connections; if the board must be bigger, say so.");

    static String buildMessage(String words) { return "Make this board: " + (words == null ? "" : words.trim()); }

    static String photoMessage(String words) {
        String w = words == null ? "" : words.trim();
        return "Make a board of the circuit in this photo." + (w.isEmpty() ? "" : "\nAnil says: " + w);
    }

    static String editMessage(String design, String words) {
        return "The board on the screen now (parts and nets):\n" + (design == null ? "{}" : design) + "\n\nAnil says: " + (words == null ? "" : words.trim())
                + "\nChange it as he says and answer the whole new parts and nets; if it is only a question, answer only \"say\".";
    }

    static String helpMessage(String design) { return "The board and its problems:\n" + (design == null ? "{}" : design); }

    // ================================================================ the money (said before; the same price list as the workshop)

    /** {least, most} rupees of one answer: a new board of these words, a change to a design of designChars, or one from a photo. */
    static double[] rupees(String model, String words, int designChars, boolean photo) {
        int a = Math.max(1, Workshop.asks(words)), len = words == null ? 0 : words.trim().length();
        long in = Math.round(SYSTEM.length() / 3.2) + 150 + Math.round(len / 1.5) + Math.round(designChars / 3.0) + (photo ? 1600 : 0);
        int lo = designChars > 0 ? 900 : 700, hi = Math.min(MAX_OUT, (designChars > 0 ? 4500 : 3500) + 200 * (a - 1));
        return new double[]{Workshop.rupees(model, in, 0, lo), Workshop.rupees(model, in, 0, hi)};
    }

    static double[] helpRupees(String model, int designChars) {
        long in = Math.round(HELP_SYSTEM.length() / 3.2) + Math.round(designChars / 3.0);
        return new double[]{Workshop.rupees(model, in, 0, 150), Workshop.rupees(model, in, 0, 700)};
    }

    /** The most one answer of this kind can cost (written to its limit). */
    static double most(String model, String kind, String words, int designChars) {
        if (HELP.equals(kind)) return Workshop.rupees(model, Math.round(HELP_SYSTEM.length() / 3.2) + Math.round(designChars / 3.0), 0, HELP_OUT);
        int len = words == null ? 0 : words.trim().length();
        long in = Math.round(SYSTEM.length() / 3.2) + 150 + Math.round(len / 1.5) + Math.round(designChars / 3.0) + (PHOTO.equals(kind) ? 1600 : 0);
        return Workshop.rupees(model, in, 0, MAX_OUT);
    }

    static double[] quote(String model, String kind, String words, int designChars) {
        if (HELP.equals(kind)) return helpRupees(model, designChars);
        return rupees(model, words, EDIT.equals(kind) ? designChars : 0, PHOTO.equals(kind));
    }

    // ================================================================ the answer

    static final class Answer {
        JSONObject json;
        String refuse;
        boolean cut;
    }

    /** The one JSON object in the AI's answer (cut off part-way: closed after its last whole element); null when there is none. */
    static Answer parse(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), z = text.lastIndexOf('}');
        if (a < 0) return null;
        JSONObject o = null;
        boolean cut = false;
        if (z > a) { try { o = new JSONObject(text.substring(a, z + 1)); } catch (Exception ignored) {} }
        if (o == null) {
            String m = CircuitLab.mend(text.substring(a));
            if (m != null) { try { o = new JSONObject(m); cut = true; } catch (Exception ignored) {} }
        }
        if (o == null) return null;
        Answer r = new Answer();
        r.cut = cut;
        String ref = o.optString("refuse", "").trim();
        if (!ref.isEmpty()) { r.refuse = Workshop.cap(ref, 240); return r; }
        JSONArray parts = o.optJSONArray("parts"), moves = o.optJSONArray("moves");
        boolean said = !o.optString("say", "").trim().isEmpty();
        if ((parts == null || parts.length() == 0) && (moves == null || moves.length() == 0) && !said) return null;
        r.json = o;
        return r;
    }
}
