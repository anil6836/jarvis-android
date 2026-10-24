package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * 🚗 ⛽ C5: diesel told in the car goes to the car account on his PHONE (one account, never two). Through his own bot, the
 * way the home tablet talks to the phone: the box writes "🚗 కారు నుంచి: …" into his chat and pins it; the phone's Jarvis
 * sees the bot's notification, writes the fill / meter / service into its car account (Car.tool, the same as by voice on the
 * phone) and pins "🚗 ఫోన్ లెక్క: …" with what it wrote; the box reads that and says it.
 * Only fill, meter, service and status travel (never undo / remove / setup). Each request has an id: sent again (the phone
 * was offline, the answer was missed), the phone writes it once and only answers again. Not answered: kept on the box and
 * sent again every few minutes while the box is on (up to 3 days), then he is told to write it on the phone himself.
 */
final class CarLink {
    private CarLink() {}

    static final String OUT = "🚗 కారు నుంచి: ", BACK = "🚗 ఫోన్ లెక్క: ";
    private static final String[] ACTIONS = {"fill", "meter", "service", "status", "km"};
    private static final long WAIT_ANSWER = 35_000, WAIT_FREE = 15_000, RETRY_EVERY = 3 * 60_000L, GIVE_UP = 3 * 86_400_000L;
    private static final Object LOCK = new Object();

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_car_link", Context.MODE_PRIVATE); }

    // ================================================================ pure (desk-tested: CarLinkTest)

    /** The car_account action he meant (as Car.tool reads it): fill / meter / service / status / …. Pure. */
    static String action(JSONObject a) {
        String act = a == null ? "" : a.optString("action", "").trim().toLowerCase(Locale.ROOT);
        if (act.isEmpty() && a != null) {
            boolean money = !Double.isNaN(Car.number(a, "rupees")) || !Double.isNaN(Car.number(a, "litres"));
            double odo = Car.number(a, "odometer");
            act = money ? "fill" : odo > 0 ? "meter" : "status";
        }
        if (act.equals("diesel") || act.equals("petrol") || act.equals("add")) act = "fill";
        if (act.equals("odometer") || act.equals("reading")) act = "meter";
        if (act.equals("service_done") || act.equals("serviced")) act = "service";
        return act;
    }

    /** Only these travel from the car (the rest is done on the phone itself). Pure. */
    static boolean allowed(String action) {
        for (String x : ACTIONS) if (x.equals(action)) return true;
        return false;
    }

    /** Only these wait in the box for a later try (a status question is no use later). Pure. */
    static boolean keepsForLater(String action) { return "fill".equals(action) || "meter".equals(action) || "service".equals(action) || "km".equals(action); }

    /** The request's own words for his chat ("⛽ ₹3,000 · 32 లీటర్లు · మీటర్ 45,230"). Pure. */
    static String human(JSONObject a) {
        String act = action(a);
        StringBuilder b = new StringBuilder();
        double rs = Car.number(a, "rupees"), l = Car.number(a, "litres"), odo = Car.number(a, "odometer");
        String fuel = CarBook.fuelKey(a.optString("fuel", ""));
        if ("fill".equals(act)) b.append("⛽ ").append(fuel == null ? "ఇంధనం" : CarBook.fuelWord(fuel));
        else if ("meter".equals(act)) b.append("🔢 మీటర్");
        else if ("service".equals(act)) b.append("🔧 సర్వీస్");
        else if ("km".equals(act)) {
            JSONObject h = a.optJSONObject("hours");
            double km = 0;
            int n = h == null || h.names() == null ? 0 : h.names().length();
            for (int i = 0; i < n; i++) km += h.optDouble(h.names().optString(i), 0);
            return "📏 కారు box కి.మీ: " + String.format(Locale.ROOT, "%.1f", km) + " (" + n + " గంటలు)";
        }
        else b.append("❓ కారు లెక్క అడిగింది");
        if (!Double.isNaN(rs)) b.append(" · ₹").append(Math.round(rs));
        if (!Double.isNaN(l)) b.append(" · ").append(trim(l)).append(" లీటర్లు");
        if (!Double.isNaN(odo) && odo > 0) b.append(" · మీటర్ ").append(Math.round(odo));
        if (a.optBoolean("full")) b.append(" · ఫుల్");
        return b.toString();
    }

    private static final java.util.regex.Pattern RS = java.util.regex.Pattern.compile("(?:₹|rs\\.?|రూ\\.?)\\s*(\\d[\\d,]*(?:\\.\\d+)?)|(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:రూ|rupee|rs\\b|₹)"),
            LITRES = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:లీటర్|లీటర్లు|litre|liter|ltr|lit\\b|l\\b)"),
            ODO = java.util.regex.Pattern.compile("(?:మీటర్|meter|odo|odometer|రీడింగ్)\\s*(?:రీడింగ్|reading)?\\s*(\\d[\\d,]*)|(\\d[\\d,]{3,})\\s*(?:కి\\.?\\s*మీ|కిలోమీటర్|km\\b|kms\\b)");

    /**
     * The car without internet: "3000 రూపాయలు డీజిల్, 32 లీటర్లు, మీటర్ 45230" read on the box itself (the AI does it when
     * online). A fill only with a fuel word, or right after the ⛽ button's question (asked); rupees or litres must be said
     * with their word. Null: not a fill. Pure.
     */
    static JSONObject parseSpoken(String said, boolean asked) {
        if (said == null) return null;
        String t = said.toLowerCase(Locale.ROOT);
        String fuel = CarBook.fuelKey(t);
        if (fuel == null && !asked && !t.contains("ఇంధనం") && !t.contains("fuel")) return null;
        try {
            JSONObject o = new JSONObject().put("action", "fill");
            java.util.regex.Matcher m = RS.matcher(t);
            if (m.find()) o.put("rupees", (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", ""));
            m = LITRES.matcher(t);
            if (m.find()) o.put("litres", m.group(1));
            m = ODO.matcher(t);
            if (m.find()) o.put("odometer", (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", ""));
            if (!o.has("rupees") && !o.has("litres")) return null;
            if (fuel != null) o.put("fuel", fuel);
            if (t.contains("ఫుల్") || t.matches("(?s).*\\bfull\\b.*") || t.contains("నిండా")) o.put("full", true);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** The same, to be said: no picture, commas ("డీజిల్, ₹3000, 32 లీటర్లు"). Pure. */
    static String spoken(JSONObject a) { return human(a).replaceFirst("^\\S+\\s+", "").replace(" · ", ", "); }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", ""); }

    /** The box's message: its words on the first line, the request itself (JSON) on the last. Pure. */
    static String outText(String id, JSONObject a) {
        try { return OUT + human(a) + "\n" + new JSONObject().put("id", id).put("a", a).toString(); } catch (Exception e) { return OUT; }
    }

    /** The phone's answer: what it wrote, then the result (JSON). Pure. */
    static String backText(String id, JSONObject r) {
        String said = r == null ? "" : r.optBoolean("ok") ? "✓ " + r.optString("said", r.optString("ask", "")) : "✗ " + r.optString("say", r.optString("error", ""));
        try { return BACK + said.trim() + "\n" + new JSONObject().put("id", id).put("r", r == null ? new JSONObject() : r).toString(); } catch (Exception e) { return BACK; }
    }

    /** {id, a} out of the box's message, or null (not one, or broken). Pure. */
    static JSONObject parseOut(String text) { return parse(text, OUT, "a"); }

    /** {id, r} out of the phone's answer, or null. Pure. */
    static JSONObject parseBack(String text) { return parse(text, BACK, "r"); }

    private static JSONObject parse(String text, String head, String field) {
        if (text == null || !text.startsWith(head)) return null;
        int nl = text.lastIndexOf('\n');
        if (nl < 0) return null;
        try {
            JSONObject o = new JSONObject(text.substring(nl + 1).trim());
            if (o.optString("id").isEmpty() || o.optJSONObject(field) == null) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ the box: asked by the AI (car_account in the car)

    /** The car_account job in the car (background thread): goes to the phone and waits up to 40 s for its answer. */
    static JSONObject request(Context c, JSONObject a) throws Exception {
        Context app = c.getApplicationContext();
        String act = action(a);
        if (!allowed(act)) return err("phone_only", "ఆ పని (" + act + ") మీ ఫోన్‌లోని కారు లెక్కలోనే చేయాలి: కారు ఆపాక ఫోన్‌లో అడగండి.");
        if (!HomeLink.linked(app)) return err("not_linked", "ఈ box ఇంకా మీ ఫోన్‌తో కలవలేదు: box సెట్టింగ్స్ → 🚗 → 📮 ఫోన్ ↔ కారు box లింక్ లో మీ bot token పెట్టి 'Telegram చాట్ కనుక్కో' నొక్కండి.");
        JSONObject req = new JSONObject(a.toString()).put("action", act);
        // the day as a date (the phone may read it days later: "నిన్న" / today must mean the box's day, not the phone's)
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate day = HouseBook.date(req.optString("date"), today);
        if (day == null) return err("bad_date", "ఏ రోజో అర్థం కాలేదు.");
        req.put("date", day.toString());
        String id = "c" + System.currentTimeMillis();
        boolean later = keepsForLater(act);
        if ("fill".equals(act)) CarFuel.onFill(app, req); // ⛽ the box's own tank guess (C12), at once
        if (later) keep(app, id, req); // (kept first: the car switched off mid-way never loses it)
        if (!Net.online(app)) {
            if (!later) return err("offline", "ఇప్పుడు నెట్ లేదు: ఫోన్ లెక్క అడగలేను.");
            return queued(act, "ఇప్పుడు నెట్ లేదు: box లో దాచాను, నెట్ రాగానే ఫోన్ లెక్కకి పంపుతాను.");
        }
        Sent r;
        synchronized (LOCK) { r = trySend(app, id, req); }
        if (r.answer != null) {
            drop(app, id);
            if ("fill".equals(act) && r.answer.optBoolean("ok")) CarFuel.boxFillWritten(app, r.answer.optString("id")); // (not counted again from the phone's word)
            CarKm.fromPhone(app, r.answer); // (its mileage and the service's state, kept on the box)
            return r.answer.put("via", "his phone's car account").put("next", r.answer.optString("next", "")
                    + " (This came from his phone; never pay or open a payment app.)");
        }
        if (!later) return err(r.sent ? "no_answer" : "not_sent", r.sent ? "ఫోన్ నిమిషంలో జవాబివ్వలేదు (ఫోన్‌కి నెట్ ఉందా?)." : "ఫోన్‌కి పంపలేకపోయాను (Telegram చేరలేదు).");
        return queued(act, r.sent ? "ఫోన్‌కి పంపాను, ఫోన్ జవాబు ఇంకా రాలేదు (ఫోన్‌కి నెట్ / Telegram నోటిఫికేషన్లు?). జవాబు వచ్చే వరకు box మళ్లీ మళ్లీ పంపుతుంది."
                : "ఇప్పుడు ఫోన్‌కి పంపలేకపోయాను (Telegram చేరలేదు): box లో దాచాను, కాసేపట్లో మళ్లీ పంపుతాను.");
    }

    private static JSONObject err(String code, String say) throws Exception { return new JSONObject().put("ok", false).put("error", code).put("say", say); }

    private static JSONObject queued(String act, String say) throws Exception {
        return new JSONObject().put("ok", true).put("queued", true).put("said", say)
                .put("next", "Say this in one short Telugu line. Do not call car_account again for the same " + act + ".");
    }

    /** What happened to one send: it went (pinned) or not, and the phone's answer (null: none yet). */
    private static final class Sent {
        final boolean sent;
        final JSONObject answer;
        Sent(boolean sent, JSONObject answer) { this.sent = sent; this.answer = answer; }
    }

    /**
     * Sends it and waits for the phone's answer (under LOCK: one at a time). The mailbox is shared: what was pinned
     * before (a word of his to the tablet, the milk account…) is pinned again right after, so nothing waiting for the
     * tablet or the phone is lost (the tablet passes over the car's messages without marking anything read).
     */
    private static Sent trySend(Context app, String id, JSONObject req) {
        long until = System.currentTimeMillis() + WAIT_FREE;
        JSONObject pm;
        while (true) { // (a word just pinned is given a moment to be read first)
            pm = Guard.pinned(app);
            if (pm == null || mine(pm) || System.currentTimeMillis() / 1000 - pm.optLong("date") >= 45) break;
            if (System.currentTimeMillis() > until) break;
            sleep(5000);
        }
        if (pm != null && !mine(pm) && pm.optLong("message_id") > 0) sp(app).edit().putLong("restore", pm.optLong("message_id")).apply();
        try {
            long mid = Guard.sendLoud(app, outText(id, req)); // (with its sound: the phone reads it on that notification)
            if (mid <= 0) return new Sent(false, null);
            if (!Guard.pin(app, mid)) { Guard.delete(app, mid); return new Sent(false, null); }
            long end = System.currentTimeMillis() + WAIT_ANSWER;
            while (System.currentTimeMillis() < end) {
                sleep(4000);
                JSONObject now = Guard.pinned(app);
                JSONObject back = now == null ? null : parseBack(now.optString("text"));
                if (back != null && id.equals(back.optString("id"))) return new Sent(true, back.optJSONObject("r"));
            }
            return new Sent(true, null);
        } finally {
            restore(app);
        }
    }

    /** The message pinned before the car's request goes back on top (only while a car message is still the pinned one). */
    static void restore(Context app) {
        long prev = sp(app).getLong("restore", 0);
        if (prev <= 0) return;
        JSONObject now = Guard.pinned(app);
        if (now == null || mine(now)) { if (!Guard.pin(app, prev) && now != null) return; } // (no net: tried again next time)
        sp(app).edit().remove("restore").apply();
    }

    /** Our own request or the phone's answer to one (the box may pin over those). */
    private static boolean mine(JSONObject pm) {
        String t = pm.optString("text");
        return t.startsWith(OUT) || t.startsWith(BACK);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---- kept for later (no net / no answer): sent again every few minutes

    private static synchronized void keep(Context c, String id, JSONObject req) {
        try {
            JSONObject all = new JSONObject(sp(c).getString("queue", "{}"));
            all.put(id, new JSONObject().put("a", req).put("at", System.currentTimeMillis()));
            sp(c).edit().putString("queue", all.toString()).apply();
        } catch (Exception ignored) {}
        retrySoon(c);
    }

    private static synchronized JSONObject queue(Context c) {
        try { return new JSONObject(sp(c).getString("queue", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static synchronized void drop(Context c, String id) {
        JSONObject all = queue(c);
        all.remove(id);
        sp(c).edit().putString("queue", all.toString()).apply();
    }

    private static volatile Thread retrier;

    /** Starts the box's background sender when something waits (one thread; it ends when nothing is left). */
    static void retrySoon(Context c) {
        final Context app = c.getApplicationContext();
        if (!new Prefs(app).carMode()) return;
        if (sp(app).getLong("restore", 0) > 0) new Thread(() -> { try { restore(app); } catch (Throwable ignored) {} }, "car-link-pin").start(); // (a pin left by a power cut)
        if (queue(app).length() == 0) return;
        synchronized (CarLink.class) {
            if (retrier != null && retrier.isAlive()) return;
            retrier = new Thread(() -> {
                while (queue(app).length() > 0) {
                    sleep(RETRY_EVERY);
                    try { retryAll(app); } catch (Throwable ignored) {}
                }
            }, "car-link");
            retrier.setDaemon(true);
            retrier.start();
        }
    }

    private static void retryAll(Context app) throws Exception {
        JSONObject all = queue(app);
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject e = all.optJSONObject(id);
            if (e == null) { drop(app, id); continue; }
            JSONObject a = e.optJSONObject("a");
            if (a == null) { drop(app, id); continue; }
            if (System.currentTimeMillis() - e.optLong("at") > GIVE_UP) {
                drop(app, id);
                if ("km".equals(action(a))) continue; // (the day's km: dropped quietly; the box keeps the hours and sends them again)
                Announcer.say(app, "ఇందాక చెప్పిన " + spoken(a) + " మూడు రోజులైనా ఫోన్‌కి చేరలేదు. ఫోన్ Jarvis కి మీరే ఒకసారి చెప్పండి.");
                continue;
            }
            if (!Net.online(app) || !HomeLink.linked(app)) return;
            if (!queue(app).has(id)) continue; // (answered meanwhile)
            Sent sent;
            synchronized (LOCK) { sent = trySend(app, id, a); }
            JSONObject r = sent.answer;
            if (r == null) return; // (the phone still isn't answering: the next round)
            drop(app, id);
            if ("fill".equals(action(a)) && r.optBoolean("ok")) CarFuel.boxFillWritten(app, r.optString("id")); // (first: its own fill is not counted again)
            CarKm.fromPhone(app, r);
            if ("km".equals(action(a))) { if (r.optBoolean("ok")) CarKm.delivered(app, a.optJSONObject("hours")); continue; } // (the day's km: nothing to say)
            if (r.optBoolean("ok")) Announcer.say(app, "ఇందాక చెప్పిన " + spoken(a) + " ఫోన్ కారు లెక్కలో చేరింది.");
            else Announcer.say(app, "ఇందాక చెప్పినది ఫోన్ లెక్కలో చేరలేదు: " + r.optString("say", "ఫోన్ ఒప్పుకోలేదు") + " ఫోన్‌లో ఒకసారి చూడండి.");
        }
    }

    /** What waits in the box for the phone (Jarvis చెక్ / the car screen): "" when nothing. */
    static String waitingLine(Context c) {
        int n = queue(c).length();
        return n == 0 ? "" : "⏳ ఫోన్‌కి వెళ్లాల్సినవి " + n;
    }

    // ================================================================ his phone: the box's request arrived (HomeLink.onBotNote)

    /** The box's request on his phone (background thread): written once into the car account, the answer pinned back. */
    static void fromCar(Context c, String text) {
        Context app = c.getApplicationContext();
        Prefs p = new Prefs(app);
        if (p.carMode() || p.homeMode()) return; // (only his phone keeps the car account)
        JSONObject o = parseOut(text);
        if (o == null) return;
        String id = o.optString("id");
        JSONObject a = o.optJSONObject("a");
        String act = action(a);
        if (!allowed(act)) return;
        JSONObject r = done(app, id);
        boolean fresh = r == null;
        if (fresh) {
            try { r = "km".equals(act) ? CarKm.toPhone(app, a.optJSONObject("hours")) : Car.tool(app, a); } catch (Exception e) {
                try { r = new JSONObject().put("ok", false).put("error", "failed").put("say", "ఫోన్ కారు లెక్కలో రాయలేకపోయాను."); } catch (Exception ignored) { return; }
            }
            remember(app, id, r);
            if (r.optBoolean("ok") && keepsForLater(act) && !"km".equals(act))
                Reminders.notify(app, "🚗 కారు నుంచి: " + human(a), r.optString("said", "కారు లెక్కలో రాశాను."), 8601);
        }
        try { // the usual mileage and the service's state go back with every answer (C12 / C14)
            JSONObject x = CarKm.extras(app);
            r = new JSONObject(r.toString());
            java.util.Iterator<String> it = x.keys();
            while (it.hasNext()) { String k = it.next(); r.put(k, x.get(k)); }
        } catch (Exception ignored) {}
        // pinned only over the box's own request: anything else pinned meanwhile is never covered (the box then asks
        // again in a few minutes and gets the same answer)
        JSONObject pm = Guard.pinned(app);
        boolean free = pm == null || pm.optString("text").startsWith(OUT);
        long sid = Guard.sendQuiet(app, backText(id, r));
        if (sid > 0 && free) Guard.pin(app, sid);
    }

    /** The phone's answer to this id, if it already wrote it (the box sent it again). */
    private static synchronized JSONObject done(Context c, String id) {
        try {
            JSONObject all = new JSONObject(sp(c).getString("done", "{}"));
            JSONObject e = all.optJSONObject(id);
            return e == null ? null : e.optJSONObject("r");
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void remember(Context c, String id, JSONObject r) {
        try {
            JSONObject all = new JSONObject(sp(c).getString("done", "{}"));
            all.put(id, new JSONObject().put("r", r).put("at", System.currentTimeMillis()));
            // (only the last 30 and the last week: enough for any re-send)
            JSONArray names = all.names();
            if (names != null && names.length() > 30) {
                String oldest = null;
                long t = Long.MAX_VALUE;
                for (int i = 0; i < names.length(); i++) {
                    long at = all.optJSONObject(names.getString(i)).optLong("at");
                    if (at < t) { t = at; oldest = names.getString(i); }
                }
                if (oldest != null) all.remove(oldest);
            }
            sp(c).edit().putString("done", all.toString()).apply();
        } catch (Exception ignored) {}
    }
}
