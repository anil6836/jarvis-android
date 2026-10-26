package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 🚗 The car Jarvis (Anil, 24 Oct): this device is the Android box in his car (a Carlinkit box on the car's USB: its screen
 * shows on the car's display, its sound comes out of the car's speakers, the car's mic hears him). The box is the car: when
 * the car starts the box starts, Jarvis comes up by himself and says "శుభ ప్రయాణం"; the sleep alert, the GPS speed and the
 * drive alerts run the whole trip. Calls, messages, money, reminders and health stay on his phone (C8): the box has none of
 * those jobs. Diesel told here goes to his phone's car account through his own bot (CarLink). Keys are typed on the box
 * itself, never sent to it (C9). His phone can stay quiet in the car meanwhile (Settings → 🚗 కారు నిద్ర అలర్ట్), so the
 * two never answer "Jarvis" together.
 */
final class CarBox {
    private CarBox() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_car_box", Context.MODE_PRIVATE); }

    /** This device is the car's box. */
    static boolean on(Context c) { return c != null && new Prefs(c).carMode(); }

    // ================================================================ moving or parked (the box's own GPS: DriveService)

    /** At or over this the car is moving: short words on the screen, no typing, no Settings. */
    static final int MOVING_KMH = 8;

    /** The speed now (km/h), or -1 when the GPS hasn't said anything for 20 seconds. */
    static int speed() {
        long at = DriveService.lastKmhAt;
        if (!DriveService.running || at == 0 || System.currentTimeMillis() - at > 20_000) return -1;
        return (int) Math.round(Math.max(0, DriveService.lastKmh));
    }

    /** Moving now (or a moment ago: a signal's few seconds don't count as parked). */
    static boolean moving() {
        int s = speed();
        return s >= MOVING_KMH || DriveService.lastMovingAt > 0 && System.currentTimeMillis() - DriveService.lastMovingAt < 15_000;
    }

    /**
     * The words on the screen while the car moves: the first sentence only, at most max letters ("…" when cut). Pure.
     * (Parked, the whole answer is shown.)
     */
    static String shortLine(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        int end = -1;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if ((ch == '.' || ch == '?' || ch == '!' || ch == '।' || ch == '\n') && i >= 6) { end = i + 1; break; }
        }
        String first = end > 0 ? t.substring(0, end).trim() : t;
        if (first.length() <= max) return first;
        int cut = first.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return first.substring(0, cut).trim() + "…";
    }

    // ================================================================ C8 / C9: only the car's jobs on the box

    /**
     * The jobs the AI may use in the car. Not here: calls, SMS, WhatsApp / Telegram, notifications, money, reminders and
     * alarms, health, diary, shopping list, the phone's camera and screen, SOS (all on his phone), and anything that pays.
     */
    private static final Set<String> CAR_TOOLS = new HashSet<>(Arrays.asList(
            "get_weather", "set_timer", "stopwatch", "device_status", "open_app", "close_app", "open_maps", "my_places",
            "drive", "travel", "nearby_open", "hazard", "car_care", "car_account", "smart_home", "home_link",
            "media_control", "now_playing", "local_media", "play_youtube", "sounds", "phone_setting",
            "air_quality", "news", "local_news", "cricket_watch", "bible", "story", "daily_fact", "holidays", "market_prices", "new_movies",
            "train_status", "interpreter", "english_practice", "jarvis_mood", "voice_mode", "save_memory", "forget_memory", "api_usage",
            "show_features", "more_tools"));

    /** A bank / payment / wallet app (never opened on the car's box). Pure. */
    static boolean payApp(String pkg, String label) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT), l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return p.matches(".*(phonepe|paytm|nbu\\.paisa|walletnfcrel|bhim|\\bupi|mobikwik|freecharge|dreamplug|\\.cred|\\bsbi|hdfc|icici|\\baxis|kotak|bank|yono|fampay|\\bslice|amazon\\.pay|wallet).*")
                || l.matches(".*\\b(pay|bank|upi|wallet)\\b.*|.*(paytm|phonepe|బ్యాంక్).*");
    }

    /** The AI may have this job in the car. Pure. */
    static boolean toolHere(String name) { return name != null && CAR_TOOLS.contains(name); }

    /** What Jarvis says for a phone job asked in the car. */
    static String phoneOnly(String name) {
        return "అది మీ ఫోన్‌లోనే ఉంటుంది " + name + ": కాల్స్, మెసేజ్‌లు, డబ్బు, రిమైండర్లు, ఆరోగ్యం ఫోన్ Jarvis చూసుకుంటాడు. కారు ఆపాక ఫోన్‌లో అడగండి.";
    }

    // ================================================================ the AI's words for the car

    /** "CAR MODE" lines for the AI (Brain puts them first). */
    static String persona(Context c) {
        Prefs p = new Prefs(c);
        String name = p.name();
        int s = speed();
        String now = s < 0 ? "speed unknown (no GPS fix yet)" : moving() ? "MOVING at about " + s + " km/h" : "parked / stopped";
        return "You are JARVIS on the Android box in " + name + "'s car (a Tata Nexon): your words show on the car's dashboard screen and are heard "
                + "through the car's speakers; he is usually DRIVING (now: " + now + "). Keep every answer to one or two short spoken Telugu sentences; "
                + "lists at most three items, said, not read out; never ask him to read or type anything while the car moves. "
                + "This box has NO calls, SMS, WhatsApp, money, reminders, alarms, health, diary or shopping list: those stay on his phone; if he asks "
                + "for one, say in one short line that it is on his phone (he can ask the phone Jarvis when the car is parked). "
                + "Diesel / petrol / meter reading / service: car_account (it goes to his phone's car account through his bot; say what the phone answered, "
                + "or that it will reach the phone when it is online). Where to go: open_maps or my_places navigate, only after his clear yes. "
                + "Home: smart_home for the AC / fan (the lines on this box); home_link say / remind only after his yes; his arrival time on amma's tablet: "
                + "drive with action home_eta, only when he asks. "
                + "Sleepy, tired or yawning: car_care sleepy at once. Music: media_control / sounds (his radio). Road help: drive, nearby_open (petrol, tea, hospital). "
                + "How far the diesel goes: car_account action range (worked out, always 'సుమారు'); the engine now (heat, battery, check-engine "
                + "light from the OBD adapter): car_account action engine. Trip games when he or the family asks (riddles / పొడుపు కథలు, a quiz "
                + "about places on the way, antakshari with movie song TITLES only — never sing or quote lyrics —, 20 questions, a short story): "
                + "one short turn at a time, by voice, end your turn with the question; stop at once if he says so or the road needs him. "
                + "Safety first: if he sounds sleepy, unwell or angry, suggest a short safe stop.";
    }

    // ================================================================ C10: the trip starts (the box started with the car)

    /** Once per box start: Jarvis came up by himself. Process-wide (a new screen in the same start is not a new trip). */
    private static volatile boolean started;

    /**
     * The box is on (Jarvis's car screen came up): the sleep alert's car mode and the GPS / drive alerts start, and the
     * greeting is returned once per trip (null: already greeted, or the car stopped only a few minutes).
     */
    static String startTrip(Context c) {
        Context app = c.getApplicationContext();
        boolean fresh = !started;
        try {
            if (!CarCare.on(app)) CarCare.start(app, "box", false);
            else if (fresh) CarCare.rearm(app); // (a restart: Android forgot the questions' timer; car mode was still marked on)
        } catch (Exception ignored) {}
        try { if (!DriveService.running) DriveService.start(app); } catch (Exception ignored) {}
        try { CarLink.retrySoon(app); } catch (Exception ignored) {}
        try { ObdWatch.start(app); } catch (Exception ignored) {} // 🔌 C11 (only when an adapter was connected once)
        try { CarKm.sendDaily(app); } catch (Exception ignored) {} // 📏 C13 (once a day)
        if (!fresh) return null;
        started = true;
        ObdWatch.newTrip(); // (the engine watch's "once a trip" words: a new trip)
        long now = System.currentTimeMillis(), last = lastAlive(app);
        sp(app).edit().putLong("greet_at", now).putLong("alive_at", now).apply();
        if (now - last < 20 * 60_000L) return null; // (the box was on until a few minutes ago: a short stop, no second "శుభ ప్రయాణం")
        Prefs p = new Prefs(app);
        int hour = java.time.LocalTime.now().getHour();
        boolean alert = CarCare.enabled(app) && CarCare.opt(app, "car_night");
        boolean gps = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String g = greeting(p.name(), hour, alert, Net.online(app), HomeLink.linked(app), gps);
        String svc = CarKm.serviceWord(app); // C14: the service near / due, once a day
        if (svc != null) g += " " + svc + ".";
        String fuel = CarFuel.rangeKm(app) < 80 ? CarFuel.chip(app) : ""; // (a low tank at the start too)
        if (!fuel.isEmpty()) g += " డీజిల్ " + fuel.replace("⛽ ~", "సుమారు ") + " కే.";
        return g;
    }

    /**
     * The box restarted by itself (the car started; a quick-boot box may keep Jarvis's process): the next car screen is
     * a new trip's (its greeting, the questions' timer).
     */
    static void newBoot() { started = false; ObdWatch.newTrip(); }

    private static volatile long aliveWritten;

    /** The box is on now (the GPS / the car screen, at most once a minute): a restart soon after is the same trip. */
    static void alive(Context c) {
        long now = System.currentTimeMillis();
        if (now - aliveWritten < 60_000) return;
        aliveWritten = now;
        sp(c).edit().putLong("alive_at", now).apply();
    }

    private static long lastAlive(Context c) { return Math.max(sp(c).getLong("alive_at", 0), sp(c).getLong("greet_at", 0)); }

    /** Every little while from the car screen: car mode and the GPS stay on for the whole trip (however long). */
    static void keep(Context c) {
        if (!started) return; // (the trip's greeting comes first: startTrip begins all this)
        alive(c);
        Context app = c.getApplicationContext();
        try { ObdWatch.start(app); } catch (Exception ignored) {}
        try { CarKm.sendDaily(app); } catch (Exception ignored) {}
        try { if (!CarCare.on(app)) CarCare.start(app, "box", false); } catch (Exception ignored) {}
        try { if (!DriveService.running) DriveService.start(app); } catch (Exception ignored) {}
    }

    /** "శుభ ప్రయాణం Sir." + the night's word + what is missing (location, internet, the phone link). Pure. */
    static String greeting(String name, int hour, boolean nightAlert, boolean online, boolean linked, boolean gps) {
        StringBuilder b = new StringBuilder();
        if (hour >= 5 && hour < 11) b.append("శుభోదయం ").append(name).append(", శుభ ప్రయాణం.");
        else b.append("శుభ ప్రయాణం ").append(name).append(".");
        if (!gps) b.append(" లొకేషన్ అనుమతి లేదు: స్పీడ్, నిద్ర అలర్ట్ పనిచేయవు. కారు ఆపాక సెట్టింగ్స్‌లో ఇవ్వండి.");
        else if (nightAlert && (hour >= 22 || hour < 5)) b.append(" రాత్రి కదా, మధ్యమధ్యలో మెలకువగా ఉన్నారా అని అడుగుతాను; 'Jarvis' అని పిలిస్తే చాలు.");
        if (!online) b.append(" ఇప్పుడు నెట్ లేదు: నెట్ వచ్చే వరకు దారి, పాటలు, నిద్ర అలర్ట్ మాత్రమే.");
        else if (!linked) b.append(" ఫోన్‌తో లింక్ ఇంకా లేదు: డీజిల్ లెక్క కోసం సెట్టింగ్స్‌లో bot token పెట్టండి.");
        return b.toString();
    }

    // ================================================================ his phone: quiet while the car's own Jarvis is there

    /**
     * His PHONE in the car (its car mode is on: the car's Bluetooth, or "కారు మోడ్") and he said the car has its own Jarvis:
     * the phone doesn't listen for "Jarvis" and doesn't ask the sleep questions (the box does both). Never on the box.
     */
    static boolean phoneQuiet(Context c) {
        if (c == null) return false;
        Prefs p = new Prefs(c);
        if (p.carMode() || p.homeMode() || !p.boxInCar()) return false;
        try { return CarCare.on(c); } catch (Exception e) { return false; }
    }

    // ================================================================ 🔇 quiet for this trip (⑪, 26 Oct)

    /**
     * 🔇 On the box, his "quiet" for this trip: Jarvis speaks only when called (his answers, his taps' results); the
     * safety alerts (sleep, speed, engine heat, rain or fog ahead, low diesel, long drive) always speak. Ends by his word,
     * or by itself when the trip ends (parked half an hour) or after 12 hours.
     */
    static boolean quiet(Context c) {
        if (c == null) return false;
        try {
            Prefs p = new Prefs(c);
            return p.carMode() && System.currentTimeMillis() < p.carQuietUntil();
        } catch (Exception e) { return false; }
    }

    static void setQuiet(Context c, boolean on) {
        long now = System.currentTimeMillis();
        new Prefs(c).sp.edit().putLong("car_quiet_until", on ? now + 12 * 3600_000L : 0).putLong("car_quiet_at", on ? now : 0).apply();
    }

    // ================================================================ the screen's small lines

    /** "📮 ఫోన్ లింక్ ✓" / "📮 ఫోన్ లింక్ లేదు" for the car screen. */
    static String linkLine(Context c) {
        return HomeLink.linked(c) ? "📮 ఫోన్ ✓" : "📮 ఫోన్ లింక్ లేదు";
    }

    /** "45 km/h" words for Jarvis చెక్ and the tool. */
    static String speedWords() {
        int s = speed();
        return s < 0 ? "GPS స్పీడ్ ఇంకా రాలేదు" : String.format(Locale.ROOT, "%d km/h", s) + (moving() ? " (కదులుతోంది)" : " (ఆగి ఉంది)");
    }
}
