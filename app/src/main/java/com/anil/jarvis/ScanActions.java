package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The buttons under a Jarvis camera answer, and what each does. Nothing is bought or paid: a shop button opens a search,
 * a call button opens the dialer with the number, a date or a person opens the Calendar / Contacts app to save there.
 * Reminders, notes, expenses, warranties, medicine reminders, where a thing is kept and the parking spot are saved on
 * the phone. A QR link is checked for scams first and opened only when he taps "తెరువు".
 */
final class ScanActions {
    private ScanActions() {}

    /** What the camera screen does for the actions that are its own. */
    interface Host {
        void hologram();
        void pdf();
        void checkup(String what);
        void layers(String device);
        void obd();
        void sound();
        /** The photo being talked about, as base64 JPEG (or null). */
        String photoJpeg();
        /** A line in the captions (and spoken when speak). */
        void tell(String text, boolean speak);
        void needLocation();
    }

    static String label(JSONObject a) {
        String l = a.optString("label").trim();
        if (!l.isEmpty()) return l.length() > 22 ? l.substring(0, 21) + "…" : l;
        switch (a.optString("type")) {
            case "shop": return "🛒 ఆన్‌లైన్‌లో చూడు";
            case "web": return "🔎 వెబ్‌లో వెతుకు";
            case "reminder": return "⏰ రిమైండర్";
            case "calendar": return "📅 క్యాలెండర్";
            case "contact": return "👤 కాంటాక్ట్ సేవ్";
            case "note": return "📝 నోట్";
            case "expense": return "💰 ఖర్చుల్లో";
            case "warranty": return "🛡️ వారంటీ సేవ్";
            case "medicine": return "💊 మందుల రిమైండర్";
            case "challan": return "🚔 చలాన్లు చూడు";
            case "maps": return "🧭 దారి";
            case "dial": return "📞 కాల్";
            case "item_place": return "🔑 గుర్తుపెట్టు";
            case "parking": return "🅿️ బండి ఇక్కడ";
            case "link": return "🔗 లింక్";
            case "hologram": return "🧊 3D హోలోగ్రామ్";
            case "pdf": return "📄 తెలుగు PDF";
            case "checkup": return "✅ చెకప్ మొదలు";
            case "open_layers": return "🧅 తెరుస్తూ స్కాన్";
            case "obd": return "🔌 OBD స్కానర్";
            case "sound": return "🎤 శబ్దం వినిపించు";
            default: return "▶ " + a.optString("type");
        }
    }

    static void run(Activity act, JSONObject a, Host h) {
        try {
            switch (a.optString("type")) {
                case "shop": shop(act, a.optString("query"), a.optString("store")); break;
                case "web": view(act, "https://www.google.com/search?q=" + Uri.encode(a.optString("query"))); break;
                case "reminder": reminder(act, a, h); break;
                case "calendar": calendar(act, a); break;
                case "contact": contact(act, a); break;
                case "note": {
                    String t = FloatBubble.noIds(a.optString("text"));
                    if (t.isEmpty()) return;
                    Notes.add(act, "notes", new JSONObject().put("id", Notes.id("n")).put("text", t).put("t", System.currentTimeMillis()), 2000);
                    h.tell("📝 నోట్ రాసుకున్నాను.", true);
                    break;
                }
                case "expense": expense(act, a, h); break;
                case "warranty": warranty(act, a, h); break;
                case "medicine": medicine(act, a, h); break;
                case "challan": challan(act, a.optString("plate"), h); break;
                case "maps": {
                    String place = a.optString("place");
                    if (place.isEmpty()) return;
                    if (a.optBoolean("navigate")) {
                        try {
                            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps"));
                            break;
                        } catch (Exception ignored) {}
                    }
                    view(act, "geo:0,0?q=" + Uri.encode(place));
                    break;
                }
                case "dial": {
                    String n = a.optString("number").replaceAll("[^0-9+]", "");
                    if (n.replace("+", "").length() < 3) return;
                    act.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n))); // he presses call himself
                    break;
                }
                case "item_place": {
                    String thing = FloatBubble.noIds(a.optString("thing")), place = FloatBubble.noIds(a.optString("place"));
                    if (thing.isEmpty() || place.isEmpty()) return;
                    JSONObject o = Everyday.put(act, thing, place + " (📷 Jarvis కెమెరా)");
                    h.tell(o == null ? "గుర్తుపెట్టుకోలేకపోయాను." : "🔑 " + thing + " → " + place + ". తర్వాత '" + thing + " ఎక్కడ?' అంటే చెబుతాను.", true);
                    break;
                }
                case "parking": parking(act, h); break;
                case "link": link(act, a.optString("url"), h); break;
                case "hologram": h.hologram(); break;
                case "pdf": h.pdf(); break;
                case "checkup": h.checkup(a.optString("what")); break;
                case "open_layers": h.layers(a.optString("device")); break;
                case "obd": h.obd(); break;
                case "sound": h.sound(); break;
                default: break;
            }
        } catch (Exception e) {
            Toast.makeText(act, "చేయలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private static void view(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(act, "తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private static final String[][] STORES = {
            {"Amazon", "https://www.amazon.in/s?k=", "in.amazon.mShop.android.shopping"},
            {"Flipkart", "https://www.flipkart.com/search?q=", "com.flipkart.android"},
            {"Meesho", "https://www.meesho.com/search?q=", "com.meesho.supply"},
            {"Google Shopping", "https://www.google.com/search?tbm=shop&q=", null},
            {"ఎలక్ట్రానిక్ parts (Google)", "https://www.google.com/search?q=buy+online+india+", null}};

    /** A shop's search for these words (in its app when installed). Buying is his. */
    static void shop(Activity act, String query, String store) {
        if (query == null || query.trim().isEmpty()) return;
        String s = store == null ? "" : store.toLowerCase(Locale.ROOT);
        for (String[] st : STORES) {
            if (!s.isEmpty() && st[0].toLowerCase(Locale.ROOT).startsWith(s.split(" ")[0])) { openStore(act, st, query); return; }
        }
        String[] names = new String[STORES.length];
        for (int i = 0; i < STORES.length; i++) names[i] = STORES[i][0];
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🛒 " + query)
                .setItems(names, (d, w) -> openStore(act, STORES[w], query))
                .setNegativeButton("వద్దు", null).show();
    }

    private static void openStore(Activity act, String[] st, String q) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(st[1] + Uri.encode(q.trim())));
        if (st[2] != null) {
            try { act.startActivity(new Intent(i).setPackage(st[2])); return; } catch (Exception ignored) {}
        }
        try { act.startActivity(i); } catch (Exception e) { Toast.makeText(act, "తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); }
    }

    private static void reminder(Activity act, JSONObject a, Host h) throws Exception {
        String text = FloatBubble.noIds(a.optString("text"));
        long at = Tools.parseLocal(a.optString("at"));
        if (text.isEmpty() || at <= System.currentTimeMillis()) { h.tell("రిమైండర్‌కి సరైన తేదీ, టైమ్ దొరకలేదు. ఎప్పుడు గుర్తు చేయాలో చెప్పండి.", true); return; }
        JSONObject r = Store.get(act).addReminder(text, at);
        if (r != null) Reminders.schedule(act, r);
        JarvisWidget.refresh(act);
        h.tell("⏰ రిమైండర్ పెట్టాను: " + new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new java.util.Date(at)), true);
    }

    private static void calendar(Activity act, JSONObject a) {
        String date = a.optString("date"), time = a.optString("time");
        boolean timed = time.matches("\\d{1,2}:\\d{2}");
        long begin = Tools.parseLocal(date + " " + (timed ? time : "00:00"));
        Intent i = new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE, FloatBubble.noIds(a.optString("title")));
        if (begin > 0) i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + (timed ? 3600_000L : 86_400_000L))
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, !timed);
        if (!a.optString("place").isEmpty()) i.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, a.optString("place"));
        act.startActivity(i); // he presses Save there
    }

    private static void contact(Activity act, JSONObject a) {
        Intent i = new Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI);
        put(i, android.provider.ContactsContract.Intents.Insert.NAME, a.optString("name"));
        String ph = a.optString("phone").replaceAll("[^0-9+]", "");
        if (ph.replace("+", "").length() <= 13) put(i, android.provider.ContactsContract.Intents.Insert.PHONE, ph);
        put(i, android.provider.ContactsContract.Intents.Insert.EMAIL, a.optString("email"));
        put(i, android.provider.ContactsContract.Intents.Insert.COMPANY, a.optString("company"));
        put(i, android.provider.ContactsContract.Intents.Insert.JOB_TITLE, a.optString("job"));
        put(i, android.provider.ContactsContract.Intents.Insert.POSTAL, FloatBubble.noIds(a.optString("address")));
        act.startActivity(i); // he presses Save there
    }

    private static void put(Intent i, String k, String v) { if (v != null && !v.trim().isEmpty()) i.putExtra(k, v.trim()); }

    private static void expense(Activity act, JSONObject a, Host h) throws Exception {
        double amt = a.optDouble("amount", 0);
        if (!(amt > 0) || amt > 10_000_000) { h.tell("ఖర్చు మొత్తం సరిగ్గా దొరకలేదు.", true); return; }
        String cat = a.optString("category").toLowerCase(Locale.ROOT);
        if (!cat.matches("food|groceries|fuel|bills|shopping|travel|health|other")) cat = "other";
        String d = a.optString("date");
        long when = d.matches("\\d{4}-\\d{2}-\\d{2}") ? Tools.parseLocal(d + " 12:00") : System.currentTimeMillis();
        Money.add(act, amt, FloatBubble.noIds(a.optString("what")), FloatBubble.noIds(a.optString("shop")), cat, when > 0 ? when : System.currentTimeMillis());
        h.tell("💰 ₹" + (amt == Math.rint(amt) ? String.valueOf((long) amt) : String.format(Locale.ENGLISH, "%.2f", amt)) + " ఖర్చుల్లో రాశాను.", true);
    }

    private static void warranty(Activity act, JSONObject a, Host h) throws Exception {
        String jpeg = h.photoJpeg();
        if (jpeg != null) { Expiry.billPhoto = jpeg; Expiry.billPhotoAt = System.currentTimeMillis(); } // kept with the warranty
        JSONObject o = Expiry.addWarranty(act, FloatBubble.noIds(a.optString("item")), a.optString("bought"), a.optInt("months", 0),
                a.optString("until"), FloatBubble.noIds(a.optString("shop")));
        h.tell(o == null ? "వారంటీ తేదీలు సరిగ్గా దొరకలేదు. కొన్న తేదీ, ఎన్ని నెలలో చెప్పండి."
                : "🛡️ వారంటీ సేవ్ చేశాను: " + o.optString("date") + " వరకు. అయిపోయే 30 రోజుల ముందు గుర్తు చేస్తాను.", true);
    }

    private static void medicine(Activity act, JSONObject a, Host h) throws Exception {
        String name = FloatBubble.noIds(a.optString("name"));
        if (name.isEmpty()) return;
        JSONObject m = Medicine.add(act, name, a.optString("times"), FloatBubble.noIds(a.optString("dose")), a.optString("food"), 0, 1);
        h.tell(m == null ? "ఏ టైమ్‌లకి వేసుకోవాలో చెప్పండి (డాక్టర్ చెప్పినట్టు), అప్పుడు రిమైండర్ పెడతాను."
                : "💊 " + name + " కి రిమైండర్లు పెట్టాను: " + m.optJSONArray("times"), true);
    }

    /** The official challan page for the plate's state (Telangana's own, else the central Parivahan site); the number is copied. */
    static void challan(Activity act, String plate, Host h) {
        String p = plate == null ? "" : plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (p.length() < 6) { h.tell("నంబర్ ప్లేట్ సరిగ్గా చదవలేకపోయాను. దగ్గరగా, వెలుతురులో చూపించండి.", true); return; }
        ClipboardManager cm = act.getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("plate", p));
        boolean ts = p.startsWith("TS") || p.startsWith("TG");
        String url = ts ? "https://echallan.tspolice.gov.in/publicview/" : "https://echallan.parivahan.gov.in/index/accused-challan";
        h.tell("🚔 " + p + " నంబర్ కాపీ చేశాను. " + (ts ? "తెలంగాణ పోలీస్" : "Parivahan") + " అధికారిక పేజీ తెరుస్తున్నాను: అక్కడ నంబర్ పేస్ట్ చేసి, captcha మీరే టైప్ చేయండి.", true);
        view(act, url);
    }

    private static void parking(Activity act, Host h) {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { h.needLocation(); return; }
        h.tell("📍 చోటు చూస్తున్నాను…", false);
        LocationManager lm = act.getSystemService(LocationManager.class);
        if (lm == null) { h.tell("లొకేషన్ దొరకలేదు.", true); return; }
        java.util.function.Consumer<Location> done = l -> act.runOnUiThread(() -> {
            if (l == null) { h.tell("లొకేషన్ దొరకలేదు. Location ఆన్ ఉందా చూడండి.", true); return; }
            GeoReminders.savePlace(act, "parking", l.getLatitude(), l.getLongitude());
            Life.markParked(act);
            h.tell("🅿️ బండి పెట్టిన చోటు గుర్తుపెట్టుకున్నాను (ఫోటోతో). తర్వాత 'నా బండి ఎక్కడ?' అంటే దారి చూపిస్తాను.", true);
        });
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, act.getMainExecutor(), l -> {
                    if (l != null) { done.accept(l); return; }
                    done.accept(last(lm));
                });
            } else {
                Location l = last(lm);
                if (l != null && System.currentTimeMillis() - l.getTime() < 120_000L) { done.accept(l); return; }
                //noinspection deprecation
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new android.location.LocationListener() {
                    @Override public void onLocationChanged(Location x) { done.accept(x); }
                    @Override public void onProviderDisabled(String p) { done.accept(last(lm)); }
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onStatusChanged(String p, int s, android.os.Bundle b) {}
                }, android.os.Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            done.accept(last(lm));
        }
    }

    private static Location last(LocationManager lm) {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                @SuppressWarnings("MissingPermission") Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {}
        return best;
    }

    /**
     * A QR link: only a web page can be opened, after the scam check and his tap. A payment code (UPI or any payment app's
     * link) only shows who it pays: Jarvis never pays and never opens a payment screen.
     */
    static void link(Activity act, String url, Host h) {
        try {
            if (url == null || url.trim().isEmpty()) return;
            String u = url.trim(), low = u.toLowerCase(Locale.ROOT);
            boolean web = low.matches("^(https?://|www\\.)\\S+$");
            boolean pay = low.startsWith("upi:") || low.matches("^(phonepe|paytmmp|paytm|tez|gpay|bhim|credpay|mobikwik|amazonpay|whatsapp-pay)[a-z]*:.*")
                    || low.matches(".*[?&]pa=[^&]+.*");
            if (pay || !web) {
                String pa = param(u, "pa"), pn = param(u, "pn"), am = param(u, "am");
                if (pay) {
                    h.tell("💳 ఇది పేమెంట్ QR: " + (pn == null ? "" : pn + " ") + (pa == null ? "" : "(" + pa + ")") + (am == null ? "" : ", ₹" + am)
                            + ". చెల్లింపు నేను చేయను, ఆ స్క్రీన్ కూడా తెరవను; పేరు సరైనదేనా చూసుకుని మీ UPI యాప్‌లో మీరే స్కాన్ చేయండి.", true);
                } else {
                    h.tell("🔳 QR లో ఉన్నది: " + u, true); // not a web page: shown, not opened
                }
                return;
            }
            ScamGuard.Verdict v = ScamGuard.judge("QR", u);
            String verdict = v.scam ? "⚠️ ఈ లింక్ మోసం లాగా ఉంది: " + android.text.TextUtils.join(", ", v.reasons) : "లింక్: " + u;
            AlertDialog.Builder b = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("🔗 QR లింక్").setMessage(verdict + (v.scam ? "\n\nతెరవకపోవడమే మంచిది." : "\n\nతెరవనా?"))
                    .setNegativeButton("వద్దు", null);
            b.setPositiveButton(v.scam ? "అయినా తెరువు" : "తెరువు", (d, w) -> view(act, low.startsWith("www.") ? "https://" + u : u));
            b.show();
        } catch (Exception e) {
            Toast.makeText(act, "ఈ QR చదవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    /** A query value (pa=, pn=, am=) from any link, also an opaque one like upi:pay?pa=... */
    private static String param(String u, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]" + key + "=([^&#]*)").matcher(u);
        if (!m.find()) return null;
        try { return java.net.URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception e) { return m.group(1); }
    }
}
