package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets Jarvis see the screen Anil was looking at: the text on it and a screenshot.
 * A capture is taken the moment the wake word is heard, before Jarvis opens on top.
 */
public class JarvisAccessibility extends AccessibilityService {

    static final class Capture {
        String jpeg;      // base64, may be null (Android 10 and older, or when the system refuses)
        String text = "";
        String pkg = "";
        long time;
    }

    private static volatile JarvisAccessibility instance;
    private static volatile Capture last;
    private static volatile String currentPkg = "";
    private static volatile String frontPkg = "";  // any window last in front (also home screen, keyboard, Settings), for captures

    static boolean enabled() { return instance != null; }

    /**
     * The app that was last in front (not Jarvis itself, and not the status bar, home screen, keyboard
     * or Settings), or "" if unknown.
     */
    static String currentPackage() { return currentPkg; }

    /** Presses the Home button, like Anil would. */
    static boolean goHome() {
        JarvisAccessibility s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ------------------------------------------------------------------ force stop

    /** How the "Force stop" button reads on the phone, in the languages Anil's phone may use. */
    private static final String[] FORCE_STOP = {
            "force stop", "బలవంతంగా ఆపు", "బలవంతంగా ఆపివేయి", "ఫోర్స్ స్టాప్",
            "बलपूर्वक रोकें", "ज़बरदस्ती रोकें", "फ़ोर्स स्टॉप", "फोर्स स्टॉप"};
    /** The "OK" button of the "Force stop?" question. */
    private static final String[] CONFIRM = {"ok", "force stop", "సరే", "బలవంతంగా ఆపు", "ठीक है", "हां"};

    /**
     * Closes an app completely, the way Anil would by hand: opens its "App info" page,
     * presses "Force stop", answers OK, and goes back. Call from a background thread.
     * Returns "stopped", "already_stopped", "no_accessibility", "no_button" or "no_confirm".
     */
    static String forceStop(String pkg) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | android.content.Intent.FLAG_ACTIVITY_NO_HISTORY | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try { s.startActivity(i); } catch (Exception e) { return "no_button"; }

        // 1) wait for the App info page and find its "Force stop" button
        AccessibilityNodeInfo button = null;
        long end = SystemClock.uptimeMillis() + 7000;
        while (button == null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(300);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null || s.getPackageName().contentEquals(String.valueOf(root.getPackageName()))) continue;
            button = find(root, FORCE_STOP, false);
        }
        if (button == null) { s.performGlobalAction(GLOBAL_ACTION_BACK); return "no_button"; }
        AccessibilityNodeInfo target = clickable(button);
        if (!button.isEnabled() || (target != null && !target.isEnabled())) {
            s.performGlobalAction(GLOBAL_ACTION_BACK); // greyed out: the app is not running any more
            return "already_stopped";
        }
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            s.performGlobalAction(GLOBAL_ACTION_BACK);
            return "no_button";
        }

        // 2) answer the "Force stop?" question with OK
        boolean confirmed = false;
        end = SystemClock.uptimeMillis() + 5000;
        while (!confirmed && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(300);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) continue;
            AccessibilityNodeInfo ok = null;
            List<AccessibilityNodeInfo> b1 = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (b1 != null && !b1.isEmpty()) ok = b1.get(0);
            if (ok == null) {
                // Only a dialog has a "cancel" button next to it; don't press the page's own button again.
                List<AccessibilityNodeInfo> b2 = root.findAccessibilityNodeInfosByViewId("android:id/button2");
                if (b2 != null && !b2.isEmpty()) ok = find(root, CONFIRM, true);
            }
            if (ok == null) continue;
            AccessibilityNodeInfo t = clickable(ok);
            confirmed = t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        SystemClock.sleep(700);
        s.performGlobalAction(GLOBAL_ACTION_BACK); // leave the App info page
        return confirmed ? "stopped" : "no_confirm";
    }

    /** A short label (a button, not a sentence) matching one of the words. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String[] words, boolean exact) {
        for (String w : words) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                CharSequence t = n.getText();
                if (t == null) t = n.getContentDescription();
                if (t == null) continue;
                String label = t.toString().trim().toLowerCase(Locale.ROOT);
                if (label.length() > 24) continue; // a sentence such as the warning text
                if (exact ? label.equals(w) : label.contains(w)) return n;
            }
        }
        return null;
    }

    private static boolean tappable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickable(n);
        return c != null && c.isClickable();
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        int hops = 0;
        while (n != null && !n.isClickable() && hops++ < 5) n = n.getParent();
        return n;
    }

    // ------------------------------------------------------------------ tap "Send"

    /** How the send button reads / is named in the messaging and mail apps. */
    private static final String[] SEND_DESC = {"send", "పంపు", "పంపించు", "मैसेज भेजें", "भेजें", "पेजें", "send message", "send sms"};

    /**
     * Types are already filled in by the app (WhatsApp/Telegram share link, Gmail draft). This taps
     * that app's Send button once the compose screen is up. Call from a background thread.
     * Returns "sent", "no_accessibility", or "no_button".
     */
    static String clickSend(String pkg, long timeoutMs) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(350);
            AccessibilityNodeInfo root = s.windowRoot(pkg);
            if (root == null) continue;
            AccessibilityNodeInfo btn = findSend(root, pkg);
            if (btn == null) continue;
            AccessibilityNodeInfo target = clickable(btn);
            if (target != null && target.isEnabled() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "sent";
        }
        return "no_button";
    }

    /** The visible window that belongs to pkg (its compose screen), or the active one if it matches. */
    /**
     * Flips an on/off switch on the settings page or quick panel that is on screen now.
     * labels: words of the row to use ("Mobile data"); null = the first switch (e.g. "Use Wi-Fi").
     * Returns "done", "already", "no_switch" or "no_accessibility".
     */
    static String setSwitch(String[] labels, boolean on, long timeoutMs) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(400);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null || s.getPackageName().contentEquals(String.valueOf(root.getPackageName()))) continue;
            AccessibilityNodeInfo sw = null;
            if (labels != null) {
                outer:
                for (String w : labels) {
                    List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
                    if (list == null) continue;
                    for (AccessibilityNodeInfo n : list) {
                        AccessibilityNodeInfo row = n;
                        for (int up = 0; up < 4 && row != null; up++) {
                            AccessibilityNodeInfo found = firstCheckable(row, 0);
                            if (found != null) { sw = found; break outer; }
                            row = row.getParent();
                        }
                    }
                }
            } else {
                sw = firstCheckable(root, 0);
            }
            if (sw == null) continue;
            if (sw.isChecked() == on) return "already";
            AccessibilityNodeInfo t = clickable(sw);
            if (t == null || !t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) continue;
            SystemClock.sleep(700);
            return "done";
        }
        return "no_switch";
    }

    private static AccessibilityNodeInfo firstCheckable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isCheckable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstCheckable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static void back() {
        JarvisAccessibility s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Makes sure the message is in the app's message box: if the app did not fill it in from the
     * link, types it there. Returns "typed", "already", "no_box" or "no_accessibility".
     */
    static String typeInto(String pkg, String text, long timeoutMs) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        String want = text == null ? "" : text.trim();
        String head = want.substring(0, Math.min(12, want.length()));
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(400);
            AccessibilityNodeInfo root = s.windowRoot(pkg);
            if (root == null) continue;
            AccessibilityNodeInfo box = messageBox(root);
            if (box == null) continue;
            CharSequence cur = box.getText();
            boolean hint = Build.VERSION.SDK_INT >= 26 && box.isShowingHintText();
            if (!hint && cur != null && cur.toString().contains(head)) return "already";
            SystemClock.sleep(300); // give the app a moment to fill it in itself
            cur = box.getText();
            hint = Build.VERSION.SDK_INT >= 26 && box.isShowingHintText();
            if (!hint && cur != null && cur.toString().contains(head)) return "already";
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, want);
            box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "typed";
        }
        return "no_box";
    }

    /** The message box: the focused text field, otherwise the lowest one on the screen. */
    private static AccessibilityNodeInfo messageBox(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> boxes = new java.util.ArrayList<>();
        collectEditable(root, boxes, 0);
        for (AccessibilityNodeInfo n : boxes) if (n.isFocused()) return n;
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityNodeInfo n : boxes) {
            n.getBoundsInScreen(r);
            if (r.bottom > bestY) { bestY = r.bottom; best = n; }
        }
        return best;
    }

    private static void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 50) return;
        if (n.isEditable() && n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out, depth + 1);
    }

    private AccessibilityNodeInfo windowRoot(String pkg) {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && pkg.contentEquals(String.valueOf(r.getPackageName()))) return r;
            }
        } catch (Exception ignored) {}
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && pkg.contentEquals(String.valueOf(active.getPackageName()))) return active;
        return null;
    }

    /** The Send button: first by a resource id ending in /send, then by its "Send" label. */
    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, String pkg) {
        AccessibilityNodeInfo byId = findBySendId(root);
        if (byId != null) return byId;
        // by label, but never an editable text box (some apps label the box "Message")
        for (String w : SEND_DESC) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                if (n.isEditable()) continue;
                CharSequence d = n.getContentDescription();
                if (d == null) d = n.getText();
                if (d == null) continue;
                String label = d.toString().trim().toLowerCase(Locale.ROOT);
                if (label.length() > 16) continue; // a sentence, not a button
                if (label.equals(w) || label.startsWith(w)) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findBySendId(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String id = n.getViewIdResourceName();
        if (id != null && n.isVisibleToUser() && !n.isEditable()) {
            String last = id.substring(id.indexOf('/') + 1);
            // WhatsApp "send", Gmail "send", Google Messages "send_message_button_icon", Samsung "send_button1"...
            if (last.equals("send") || last.equals("fab_send") || last.startsWith("send_button")
                    || last.startsWith("send_message_button") || last.equals("send_icon")) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findBySendId(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ operating an app step by step (tickets etc.)

    /**
     * Buttons that pay, place an order or confirm a booking/ride. Jarvis never taps these: Anil does
     * the payment himself. Checked in code on every tap, whatever the model asks for.
     */
    static final java.util.regex.Pattern COMMIT = java.util.regex.Pattern.compile(
            "(?i)(\\bpay\\b|payment|proceed to pay|place (your )?order|buy now|check ?out|make payment|confirm (and|&) pay"
                    + "|confirm (booking|order|ride|pickup|purchase)|request (ride|uber|ola)|book (ride|bike|auto|cab|uber|ola|rapido)"
                    + "|slide to (pay|book|confirm)|upi pin|చెల్లించ|చెల్లింపు|భుగతాన|भुगतान)");

    /**
     * A one-time permission to press payment buttons: Anil said "yes" to this exact amount by voice,
     * wallet payment is switched on in Settings, and the amount is within his limit. Only in that app,
     * only for a few minutes and a few taps, never above the amount, and only the MobiKwik wallet.
     */
    private static final class PayAllowance {
        String pkg;
        double amount;
        long until;
        int taps;
        boolean walletChosen;  // Jarvis tapped "MobiKwik" on the payment options page
    }

    private static volatile PayAllowance pay;

    /** Other ways to pay that Jarvis must never choose (he agreed to the MobiKwik wallet only). */
    static final java.util.regex.Pattern OTHER_METHOD = java.util.regex.Pattern.compile(
            "(?i)(\\bupi\\b|card|net ?banking|pay ?later|\\bemi\\b|\\bsimpl\\b|lazypay|gpay|google pay|phonepe|paytm|amazon ?pay|cred\\b|freecharge|airtel|jio|bhim|olamoney|ola money"
                    + "|payzapp|bajaj|samsung ?pay|flexipay|zestmoney|paypal|mobikwik ?zip\\b"
                    + "|zomato (money|credits?|pay)|district (money|credits?|cash)|sodexo|pluxee|zeta)");
    /** ₹ / Rs / INR followed by an amount; "rs" only as its own word (not "Offers 50", "Users 1,00,000"). */
    private static final java.util.regex.Pattern RUPEES = java.util.regex.Pattern.compile(
            "(?:₹|(?<![a-z])(?:rs\\.?|inr))\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    static void allowPayment(String pkg, double amount, long ms) {
        PayAllowance a = new PayAllowance();
        a.pkg = pkg;
        a.amount = amount;
        a.until = SystemClock.elapsedRealtime() + ms;
        a.taps = 5;
        pay = a;
    }

    static void clearPayment() { pay = null; }

    static boolean paymentAllowed() {
        PayAllowance a = pay;
        return a != null && SystemClock.elapsedRealtime() < a.until && a.taps > 0;
    }

    /** The biggest rupee amount in a label, or -1. */
    static double rupees(String words) {
        double best = -1;
        java.util.regex.Matcher m = RUPEES.matcher(words == null ? "" : words);
        while (m.find()) {
            try { best = Math.max(best, Double.parseDouble(m.group(1).replace(",", ""))); } catch (Exception ignored) {}
        }
        return best;
    }

    private static final java.util.regex.Pattern PAY_AMOUNT = java.util.regex.Pattern.compile(
            "(?i)pay(?:ing)?\\s*(?:now\\s*)?(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    /** "Pay ₹229.50" → 229.50; -1 when the words have no amount right after "Pay". */
    static double payAmount(String words) {
        java.util.regex.Matcher m = PAY_AMOUNT.matcher(words == null ? "" : words);
        if (!m.find()) return -1;
        try { return Double.parseDouble(m.group(1).replace(",", "")); } catch (Exception e) { return -1; }
    }

    /** The amount the page says he has to pay ("Amount Payable ₹229.50", "Total ₹…"), or -1. */
    static double pagePayable(Screen sc) {
        if (sc == null) return -1;
        String[] lines = sc.list.toString().split("\n");
        for (String key : new String[]{"payable", "amount to pay", "to be paid", "grand total", "total amount", "order total", "total"}) {
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].toLowerCase(Locale.ROOT).contains(key)) continue;
                for (int k = i; k < Math.min(lines.length, i + 3); k++) {
                    double v = rupees(lines[k]);
                    if (v > 0) return v;
                }
            }
        }
        return -1;
    }

    /** The amount on the Pay button on this screen ("Pay ₹472.00"), or -1. */
    static double payButtonAmount(Screen sc) {
        if (sc == null) return -1;
        for (AccessibilityNodeInfo n : sc.nodes) {
            String l = label(n);
            if (!COMMIT.matcher(l).find() || l.toLowerCase(Locale.ROOT).contains("mobikwik")) continue; // a wallet row shows its balance, not the price
            double amt = payAmount(l);
            if (amt > 0) return amt;
            AccessibilityNodeInfo c = clickable(n);
            if (c != null && c.isClickable() && buttonSized(c)) {
                amt = payAmount(allText(c, 0));
                if (amt > 0) return amt;
            }
        }
        return -1;
    }

    /** null = this tap is fine; otherwise why it is refused ("blocked:<label>"). */
    /** In Rapido, Uber and Ola any Book / Confirm / Request / Choose button orders a ride ("Book Mini", "Choose UberGo", "Confirm UberX"). */
    private static final java.util.regex.Pattern RIDE_COMMIT = java.util.regex.Pattern.compile("(?i)\\b(book|confirm|request|choose)\\b|బుక్");

    private static boolean rideApp(String pkg) {
        return pkg != null && (pkg.startsWith("com.rapido") || pkg.startsWith("com.ubercab") || pkg.startsWith("com.olacabs"));
    }

    private static String payCheck(Screen sc, AccessibilityNodeInfo n) {
        String pkg = sc.pkg;
        String commit = commitLabel(n);
        if (commit == null && rideApp(pkg)) {
            String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
            AccessibilityNodeInfo c = clickable(n);
            if (c != null && c != n && c.isClickable() && buttonSized(c)) words += " " + allText(c, 0);
            if (RIDE_COMMIT.matcher(words).find()) commit = words.trim();
        }
        PayAllowance a = pay;
        boolean allowed = a != null && a.pkg.equals(pkg) && SystemClock.elapsedRealtime() < a.until && a.taps > 0;
        if (allowed) {
            String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
            AccessibilityNodeInfo c = clickable(n);
            if (c != null && c != n && c.isClickable() && buttonSized(c)) words += " " + allText(c, 0);
            double payable = pagePayable(sc);
            boolean other = OTHER_METHOD.matcher(words).find();
            if (words.toLowerCase(Locale.ROOT).contains("mobikwik")) {
                // A "MobiKwik" tile in a list of UPI apps, MobiKwik ZIP (pay later) etc. is not the wallet.
                if (other) return "blocked:" + words.trim() + " (only the MobiKwik wallet is allowed, not UPI / card / pay later)";
                // Choosing the MobiKwik wallet. Its row can say "Pay using Mobikwik" and show the wallet
                // balance (₹1000): that is not the price. The price is the page's "Amount Payable".
                double amt = payable > 0 ? payable : payAmount(words);
                if (amt > a.amount + 1) return "blocked:over:" + amt + "|" + words.trim();
                a.walletChosen = true;
                if (commit != null) a.taps--;
                return null;
            }
            // never another way of paying, only the MobiKwik wallet
            if (other) return "blocked:" + words.trim() + " (only the MobiKwik wallet is allowed)";
            if (commit == null) {
                // Choosing something else on the payment options page after MobiKwik (another wallet / bank the
                // patterns do not know) may change the way of paying: MobiKwik must be chosen again before Pay.
                // Plain "Continue" / "Proceed" buttons do not choose a way of paying.
                if (a.walletChosen && methodPage(sc) && !NAV_ONLY.matcher(shortLabel(n)).matches()) a.walletChosen = false;
                return null;
            }
            // on the page that lists ways to pay, MobiKwik must have been chosen first
            if (methodPage(sc) && !a.walletChosen) return "blocked:" + words.trim() + " (tap the MobiKwik row first)";
            double amt = payAmount(words);                 // "Pay ₹229.50"
            if (amt <= 0) amt = payable;                   // "Amount Payable ₹229.50" on the page
            if (amt <= 0) amt = rupees(words);
            if (amt > a.amount + 1) return "blocked:over:" + amt + "|" + words.trim(); // more than he agreed: Tools asks him again
            a.taps--;
            return null;
        }
        if (commit != null) return "blocked:" + commit;
        // Before his "yes": on a payment page, choosing a way to pay can itself start the payment
        // (a linked wallet pays in one tap), so it counts as the Pay step and Jarvis asks him first.
        String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
        String low = words.toLowerCase(Locale.ROOT);
        boolean method = low.contains("mobikwik") || low.contains("wallet") || OTHER_METHOD.matcher(words).find();
        if (method && methodPage(sc)) return "blocked:" + words.trim();
        return null;
    }

    /** A button that only moves on ("Continue", "Proceed →"), without choosing anything. */
    private static final java.util.regex.Pattern NAV_ONLY = java.util.regex.Pattern.compile(
            "(?i)(continue|proceed|next|ok|okay|done|got it)\\W*");

    /** The node's own label, or its clickable parent's when the node has none. */
    private static String shortLabel(AccessibilityNodeInfo n) {
        String l = label(n);
        if (!l.isEmpty()) return l;
        AccessibilityNodeInfo c = clickable(n);
        return c == null ? "" : label(c);
    }

    /** A page that lists ways to pay (UPI, cards, wallets, net banking). */
    static boolean methodPage(Screen sc) {
        String page = sc.list.toString().toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String k : new String[]{"upi", "net banking", "netbanking", "wallet", "credit card", "debit card", "debit/credit", "credit/debit", "pay later"}) {
            if (page.contains(k)) hits++;
        }
        return hits >= 2;
    }

    /** Paid extras Jarvis never adds by itself: memberships (BMS Club), ₹1 donations, insurance. */
    static final java.util.regex.Pattern EXTRAS = java.util.regex.Pattern.compile(
            "(?i)(add ?(₹|rs\\.?) ?\\d|add club|club purchase|club membership|book a smile|donat|insurance|protect your (ticket|booking)"
                    + "|feeding india|contribute ₹|add tip|district pass|zomato gold|gold membership)");
    /** He asked for an extra himself (e.g. "Club కూడా తీసుకో"). Set per task by Tools. */
    static volatile boolean extrasAllowed;

    /** Buttons that leave an extra out ("Remove", "No thanks", "Skip"): always fine. */
    private static final java.util.regex.Pattern OPT_OUT = java.util.regex.Pattern.compile(
            "(?i)^(remove|no|no thanks|not now|skip|don[’']?t|do not)\\b.*");

    private static String extraCheck(AccessibilityNodeInfo n) {
        if (extrasAllowed) return null;
        AccessibilityNodeInfo c = clickable(n);
        // Unticking a ticked box (e.g. a pre-ticked donation) takes the extra out: allowed.
        if ((n.isCheckable() && n.isChecked()) || (c != null && c.isCheckable() && c.isChecked())) return null;
        if (OPT_OUT.matcher(label(n)).matches() || (c != null && OPT_OUT.matcher(label(c)).matches())) return null;
        String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
        if (c != null && c != n && c.isClickable() && buttonSized(c)) words += " " + allText(c, 0);
        return EXTRAS.matcher(words).find() ? "blocked:extra:" + words.trim() : null;
    }

    /** What is on the app's screen now: numbered elements, a screenshot, and the nodes behind the numbers. */
    static final class Screen {
        String pkg = "";
        final List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        final StringBuilder list = new StringBuilder();
        Bitmap shot;  // full resolution, software bitmap; may be null
        int w, h;
    }

    /** Reads the app's window (pkg) and takes a screenshot. Background thread only. null = the app is not on screen. */
    static Screen screen(String pkg) {
        JarvisAccessibility s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = null;
        for (int i = 0; i < 12 && root == null; i++) {
            root = s.windowRoot(pkg);
            if (root == null) SystemClock.sleep(400);
        }
        if (root == null) return null;
        return build(s, root, pkg);
    }

    /** Whatever app is in front (home screen included, never Jarvis itself), with a screenshot. Background thread only. */
    static Screen frontScreen() {
        JarvisAccessibility s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = null;
        for (int i = 0; i < 10 && root == null; i++) {
            root = s.frontRoot();
            if (root == null) SystemClock.sleep(400);
        }
        if (root == null) return null;
        return build(s, root, String.valueOf(root.getPackageName()));
    }

    /** The top app window that is not Jarvis's own (its screens or its overlay). */
    private AccessibilityNodeInfo frontRoot() {
        String mine = getPackageName();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !mine.contentEquals(String.valueOf(active.getPackageName()))) return active;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && !mine.contentEquals(String.valueOf(r.getPackageName()))) return r;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Screen build(JarvisAccessibility s, AccessibilityNodeInfo root, String pkg) {
        Screen sc = new Screen();
        sc.pkg = pkg;
        android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
        sc.w = dm.widthPixels;
        sc.h = dm.heightPixels;
        collect(root, sc, 0);
        if (Build.VERSION.SDK_INT >= 30) {
            CountDownLatch done = new CountDownLatch(1);
            try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        try {
                            HardwareBuffer hb = r.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                            if (hw != null) {
                                sc.shot = hw.copy(Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                            }
                            hb.close();
                        } catch (Exception ignored) {}
                        done.countDown();
                    }
                    @Override public void onFailure(int errorCode) { done.countDown(); }
                });
                done.await(4, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            if (sc.shot != null) { sc.w = sc.shot.getWidth(); sc.h = sc.shot.getHeight(); }
        }
        return sc;
    }

    private static void collect(AccessibilityNodeInfo n, Screen sc, int depth) {
        if (n == null || depth > 45 || sc.nodes.size() >= 260) return;
        if (n.isVisibleToUser()) {
            String label = label(n);
            boolean act = n.isClickable() || n.isEditable() || n.isCheckable();
            if (!label.isEmpty() || n.isEditable() || (act && n.getChildCount() == 0)) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 2 && r.height() > 2) {
                    int idx = sc.nodes.size();
                    sc.nodes.add(n);
                    String kind = n.isEditable() ? "field" : n.isCheckable() ? (n.isChecked() ? "checked" : "unchecked")
                            : tappable(n) ? "button" : "text";
                    if (!n.isEnabled()) kind += ",disabled";
                    if (n.isSelected()) kind += ",selected";
                    sc.list.append('[').append(idx).append("] ").append(kind).append(" \"")
                            .append(label.length() > 70 ? label.substring(0, 70) + "…" : label)
                            .append("\" at ").append(r.centerX()).append(',').append(r.centerY()).append('\n');
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sc, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        String a = t == null ? "" : t.toString().trim();
        CharSequence d = n.getContentDescription();
        String b = d == null ? "" : d.toString().trim();
        String l = a.isEmpty() ? b : b.isEmpty() || b.equals(a) ? a : a + " (" + b + ")";
        return l.replaceAll("\\s+", " ");
    }

    /** All words on a node and inside it (for the payment check). */
    private static String allText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 4) return "";
        StringBuilder sb = new StringBuilder(label(n));
        for (int i = 0; i < n.getChildCount() && sb.length() < 200; i++) sb.append(' ').append(allText(n.getChild(i), depth + 1));
        return sb.toString();
    }

    /** The label of a button that pays / orders / books, or null when the tap is fine. */
    private static String commitLabel(AccessibilityNodeInfo n) {
        StringBuilder words = new StringBuilder(label(n));
        if (buttonSized(n)) words.append(' ').append(allText(n, 0));
        AccessibilityNodeInfo c = clickable(n);
        if (c != null && c != n && c.isClickable() && buttonSized(c)) words.append(' ').append(allText(c, 0));
        java.util.regex.Matcher m = COMMIT.matcher(words);
        return m.find() ? words.toString().trim() : null;
    }

    /** A button or a row, not a whole page or seat map (whose text would include the Pay bar). */
    private static boolean buttonSized(AccessibilityNodeInfo n) {
        JarvisAccessibility s = instance;
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (s == null) return r.height() < 400;
        android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
        return r.height() < dm.heightPixels / 5 && (long) r.width() * r.height() < (long) dm.widthPixels * dm.heightPixels / 8;
    }

    /** Taps element idx. Returns "ok", "blocked:<label>", "password", "no_element" or "failed". */
    static String tapElement(Screen sc, int idx) {
        if (sc == null || idx < 0 || idx >= sc.nodes.size()) return "no_element";
        AccessibilityNodeInfo n = sc.nodes.get(idx);
        if (!n.refresh()) return "no_element"; // gone from the screen: never act on a stale node or its old position
        String extra = extraCheck(n);
        if (extra != null) return extra;
        String refused = payCheck(sc, n);
        if (refused != null) return refused;
        String ask = confirmCheck(n);
        if (ask != null) return ask;
        if (n.isPassword()) return "password";
        AccessibilityNodeInfo c = clickable(n);
        if (c != null && c.isClickable() && c.isEnabled() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok";
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        return gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 60) ? "ok" : "failed";
    }

    /** Taps a point on the screen (seat maps and other drawings that have no elements). */
    static String tapPoint(Screen sc, int x, int y) {
        JarvisAccessibility s = instance;
        if (s == null) return "failed";
        AccessibilityNodeInfo root = s.windowRoot(sc.pkg);
        AccessibilityNodeInfo hit = root == null ? null : deepestAt(root, x, y, 0);
        // No node of the app there (its window is gone, or another app is in front): the tap cannot be checked.
        if (hit == null) return "no_element";
        String extra = extraCheck(hit);
        if (extra != null) return extra;
        String refused = payCheck(sc, hit);
        if (refused != null) return refused;
        String ask = confirmCheck(hit);
        if (ask != null) return ask;
        if (hit.isPassword()) return "password";
        return gesture(x, y, x, y, 60) ? "ok" : "failed";
    }

    /** Long-presses an element or a point (menus that open on a long press). Same checks as a tap. */
    static String longPress(Screen sc, int idx, int x, int y) {
        AccessibilityNodeInfo n = null;
        if (idx >= 0 && idx < sc.nodes.size()) {
            n = sc.nodes.get(idx);
            if (!n.refresh()) return "no_element";
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            x = r.centerX();
            y = r.centerY();
        } else {
            JarvisAccessibility s = instance;
            AccessibilityNodeInfo root = s == null ? null : s.windowRoot(sc.pkg);
            n = root == null ? null : deepestAt(root, x, y, 0);
            if (n == null) return "no_element";
        }
        if (payCheck(sc, n) != null) return "blocked:" + label(n);
        return gesture(x, y, x, y, 800) ? "ok" : "failed";
    }

    // ------------------------------------------------------------------ ask first: send, post, delete, call

    /** Buttons that send, post, delete, call or submit: the agent presses one only right after Anil said yes. */
    private static final java.util.regex.Pattern CONFIRM_FIRST = java.util.regex.Pattern.compile(
            "(?i)(send|send message|send now|send sms|post|publish|share|tweet|delete|delete for everyone|delete for me|delete all|delete chat"
                    + "|remove account|uninstall|erase|reset|factory reset|clear data|clear storage|call|voice call|video call|audio call|dial|submit"
                    + "|పంపు|పంపించు|పంపండి|డిలీట్|తొలగించు|తొలగించండి|కాల్|కాల్ చేయి|వీడియో కాల్|షేర్|అన్‌ఇన్‌స్టాల్)[.!]?");
    private static volatile int confirmTaps;
    private static volatile long confirmUntil;

    /** Anil said yes to the thing Jarvis asked about: one such button may be pressed in the next minutes. */
    static void allowConfirmed(long ms) { confirmTaps = 1; confirmUntil = SystemClock.elapsedRealtime() + ms; }

    static void clearConfirmed() { confirmTaps = 0; }

    private static String confirmCheck(AccessibilityNodeInfo n) {
        String hit = null;
        String l = label(n).trim();
        if (l.length() <= 30 && CONFIRM_FIRST.matcher(l).matches()) hit = l;
        AccessibilityNodeInfo c = clickable(n);
        if (hit == null && c != null && c != n && buttonSized(c)) {
            String cl = label(c).trim();
            if (cl.length() <= 30 && CONFIRM_FIRST.matcher(cl).matches()) hit = cl;
        }
        if (hit == null) return null;
        if (confirmTaps > 0 && SystemClock.elapsedRealtime() < confirmUntil) { confirmTaps--; return null; }
        return "blocked:confirm:" + hit;
    }

    // ------------------------------------------------------------------ "Jarvis is using your phone" overlay

    private android.view.View controlBar, controlGlow;
    private android.widget.TextView controlText;

    /** A thin glowing border and a small bar at the top ("Jarvis మీ ఫోన్ వాడుతోంది" + ⏹ ఆపు) while the agent works. */
    static void showControl(String text, Runnable onStop) {
        JarvisAccessibility s = instance;
        if (s == null) return;
        s.getMainExecutor().execute(() -> s.addControl(text, onStop));
    }

    static void updateControl(String text) {
        JarvisAccessibility s = instance;
        if (s == null) return;
        s.getMainExecutor().execute(() -> { if (s.controlText != null) s.controlText.setText("Jarvis · " + text); });
    }

    static void hideControl() {
        JarvisAccessibility s = instance;
        if (s == null) return;
        s.getMainExecutor().execute(s::removeControl);
    }

    private void addControl(String text, Runnable onStop) {
        if (controlBar != null) { if (controlText != null) controlText.setText("Jarvis · " + text); return; }
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            float d = getResources().getDisplayMetrics().density;
            int barH = (int) (30 * d);
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) barH = Math.max((int) (22 * d), Math.min(barH, getResources().getDimensionPixelSize(id)));

            android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
            border.setColor(0x00000000);
            border.setStroke((int) (3 * d), 0xCC48D1FF);
            border.setCornerRadius(18 * d);
            controlGlow = new android.view.View(this);
            controlGlow.setBackground(border);
            android.view.WindowManager.LayoutParams gp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            wm.addView(controlGlow, gp);

            android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xEE071A24);
            bg.setStroke((int) (1 * d), 0xFF48D1FF);
            bg.setCornerRadius(barH / 2f);
            bar.setBackground(bg);
            bar.setPadding((int) (12 * d), 0, (int) (4 * d), 0);
            controlText = new android.widget.TextView(this);
            controlText.setText("Jarvis · " + text);
            controlText.setTextColor(0xFF9BE8FF);
            controlText.setTextSize(11.5f);
            controlText.setSingleLine(true);
            controlText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            controlText.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.62f));
            bar.addView(controlText);
            android.widget.TextView stop = new android.widget.TextView(this);
            stop.setText("  ⏹ ఆపు  ");
            stop.setTextColor(0xFFFF8A80);
            stop.setTextSize(12f);
            stop.setGravity(android.view.Gravity.CENTER);
            stop.setOnClickListener(v -> { removeControl(); if (onStop != null) onStop.run(); });
            bar.addView(stop, new android.widget.LinearLayout.LayoutParams(-2, -1));
            android.view.WindowManager.LayoutParams bp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT, barH,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            bp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            wm.addView(bar, bp);
            controlBar = bar;
        } catch (Exception e) {
            removeControl();
        }
    }

    private void removeControl() {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        try { if (controlBar != null) wm.removeView(controlBar); } catch (Exception ignored) {}
        try { if (controlGlow != null) wm.removeView(controlGlow); } catch (Exception ignored) {}
        controlBar = null;
        controlGlow = null;
        controlText = null;
    }

    private static AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 45 || !n.isVisibleToUser()) return null;
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo d = deepestAt(n.getChild(i), x, y, depth + 1);
            if (d != null) return d;
        }
        return n;
    }

    /** Types into a text field. Never into a password field. */
    static String typeElement(Screen sc, int idx, String text) {
        if (sc == null || idx < 0 || idx >= sc.nodes.size()) return "no_element";
        AccessibilityNodeInfo n = sc.nodes.get(idx);
        if (n.isPassword()) return "password";
        if (!n.isEditable()) {
            // Not a text box: tapping it goes through the same pay / extras checks as any tap.
            String r = tapElement(sc, idx);
            if (!"ok".equals(r)) return r;
            SystemClock.sleep(600);
            return "not_a_field";
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok" : "failed";
    }

    /** Scrolls the page (down = show what is below). */
    static String scroll(Screen sc, String dir) {
        String d = dir == null ? "down" : dir.toLowerCase(Locale.ROOT);
        int w = sc.w, h = sc.h;
        boolean ok;
        switch (d) {
            case "up": ok = gesture(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 350); break;
            case "left": ok = gesture(w * 3 / 10, h / 2, w * 8 / 10, h / 2, 300); break;   // show what is on the left
            case "right": ok = gesture(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300); break;  // show what is on the right
            default: ok = gesture(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 350);
        }
        return ok ? "ok" : "failed";
    }

    /** A tap (same start and end) or a swipe, and waits for it to finish. */
    private static boolean gesture(int x1, int y1, int x2, int y2, long ms) {
        JarvisAccessibility s = instance;
        if (s == null) return false;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        if (x1 != x2 || y1 != y2) p.lineTo(Math.max(0, x2), Math.max(0, y2));
        android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, ms)).build();
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            boolean sent = s.dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription gd) { ok[0] = true; done.countDown(); }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription gd) { done.countDown(); }
            }, null);
            if (!sent) done.countDown();
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    /**
     * The screenshot (or a part of it) as JPEG base64, with a light grid labelled in screen pixels so
     * the model can say where to tap. crop null = whole screen.
     */
    static String gridJpeg(Screen sc, android.graphics.Rect crop, int maxDim) {
        if (sc == null || sc.shot == null) return null;
        android.graphics.Rect c = crop == null ? new android.graphics.Rect(0, 0, sc.shot.getWidth(), sc.shot.getHeight()) : new android.graphics.Rect(crop);
        if (!c.intersect(0, 0, sc.shot.getWidth(), sc.shot.getHeight()) || c.width() < 20 || c.height() < 20) return null;
        float scale = Math.min(1f, (float) maxDim / Math.max(c.width(), c.height()));
        if (crop != null) scale = Math.min(2.5f, (float) maxDim / Math.max(c.width(), c.height())); // zoom in on a part
        int ow = Math.max(1, Math.round(c.width() * scale)), oh = Math.max(1, Math.round(c.height() * scale));
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawBitmap(sc.shot, c, new android.graphics.Rect(0, 0, ow, oh), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        int step = crop == null ? 100 : (c.width() > 700 ? 100 : 50);
        android.graphics.Paint line = new android.graphics.Paint();
        line.setColor(0x66FF00FF);
        line.setStrokeWidth(1);
        android.graphics.Paint txt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFF00FF);
        txt.setTextSize(Math.max(11, 13 * Math.min(1.6f, scale * 2)));
        txt.setShadowLayer(2, 0, 0, 0xFFFFFFFF);
        for (int x = (c.left / step + 1) * step; x < c.right; x += step) {
            float px = (x - c.left) * scale;
            cv.drawLine(px, 0, px, oh, line);
            cv.drawText(String.valueOf(x), px + 2, txt.getTextSize(), txt);
        }
        for (int y = (c.top / step + 1) * step; y < c.bottom; y += step) {
            float py = (y - c.top) * scale;
            cv.drawLine(0, py, ow, py, line);
            cv.drawText(String.valueOf(y), 2, py - 2, txt);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        out.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    @Override protected void onServiceConnected() {
        instance = this;
        loadNotApps();
    }

    /** Windows that are not "the app Anil is in": the status bar / panels, the home screen, the keyboard, Settings. */
    private static volatile java.util.Set<String> notApps = java.util.Collections.emptySet();

    private void loadNotApps() {
        java.util.Set<String> s = new java.util.HashSet<>();
        s.add("com.android.systemui");
        s.add("com.android.settings");
        try {
            android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            // "android" is the chooser shown when no home app is the default
            if (home != null && home.activityInfo != null && !"android".equals(home.activityInfo.packageName)) s.add(home.activityInfo.packageName);
        } catch (Exception e) {}
        try {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) for (android.view.inputmethod.InputMethodInfo im : imm.getEnabledInputMethodList()) s.add(im.getPackageName());
        } catch (Exception e) {}
        notApps = s;
    }

    private static boolean notAnApp(String p) {
        if (notApps.contains(p)) return true;
        String l = p.toLowerCase(Locale.ROOT);
        return l.contains("inputmethod") || l.contains("keyboard") || l.contains("honeyboard") || l.contains("launcher");
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            if (!p.equals(getPackageName())) {
                frontPkg = p;
                if (!notAnApp(p)) currentPkg = p; // "close this app" must not pick the keyboard, home screen, Settings...
            }
        }
    }

    @Override public void onInterrupt() {}

    /** The last capture if it is newer than maxAgeMs. */
    static Capture recent(long maxAgeMs) {
        Capture c = last;
        return c != null && SystemClock.elapsedRealtime() - c.time < maxAgeMs ? c : null;
    }

    /** Captures now and runs {@code then} on the main thread when done (or right away if unavailable). */
    static void capture(Runnable then) {
        JarvisAccessibility s = instance;
        if (s == null) { then.run(); return; }
        Capture c = new Capture();
        c.time = SystemClock.elapsedRealtime();
        c.pkg = frontPkg;
        try { c.text = s.screenText(); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT < 30) { last = c; then.run(); return; }
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            c.jpeg = encode(hw);
                            hw.recycle();
                        }
                        hb.close();
                    } catch (Exception ignored) {}
                    last = c;
                    then.run();
                }
                @Override public void onFailure(int errorCode) {
                    last = c;
                    then.run();
                }
            });
        } catch (Exception e) {
            last = c;
            then.run();
        }
    }

    /** Capture from a background thread, waiting up to timeoutMs. */
    static Capture captureBlocking(long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> capture(done::countDown));
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return recent(timeoutMs + 2000);
    }

    private static String encode(Bitmap hw) {
        int w = hw.getWidth(), h = hw.getHeight();
        float scale = Math.min(1f, 1100f / Math.max(w, h));
        int sw = Math.max(1, Math.round(w * scale)), sh = Math.max(1, Math.round(h * scale));
        Bitmap soft = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(soft);
        cv.scale(scale, scale);
        Bitmap copy = hw.copy(Bitmap.Config.ARGB_8888, false);
        cv.drawBitmap(copy, 0, 0, null);
        copy.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soft.compress(Bitmap.CompressFormat.JPEG, 80, out);
        soft.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String screenText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        return sb.toString().trim();
    }

    private static void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || sb.length() > 4000 || depth > 40) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) walk(ch, sb, depth + 1);
        }
    }
}
