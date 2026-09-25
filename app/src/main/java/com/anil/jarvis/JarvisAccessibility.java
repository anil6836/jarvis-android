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

    static boolean enabled() { return instance != null; }

    /** The app that was last in front (not Jarvis itself), or "" if unknown. */
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
            if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)) return "typed";
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
            "(?i)(\\bupi\\b|card|net ?banking|pay ?later|\\bemi\\b|simpl|lazypay|gpay|google pay|phonepe|paytm|amazon ?pay|cred\\b|freecharge|airtel|jio|bhim|olamoney|ola money"
                    + "|zomato (money|credits?|pay)|district (money|credits?|cash)|sodexo|pluxee|zeta)");
    private static final java.util.regex.Pattern RUPEES = java.util.regex.Pattern.compile(
            "(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", java.util.regex.Pattern.CASE_INSENSITIVE);

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
    private static String payCheck(Screen sc, AccessibilityNodeInfo n) {
        String pkg = sc.pkg;
        String commit = commitLabel(n);
        PayAllowance a = pay;
        boolean allowed = a != null && a.pkg.equals(pkg) && SystemClock.elapsedRealtime() < a.until && a.taps > 0;
        if (allowed) {
            String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
            AccessibilityNodeInfo c = clickable(n);
            if (c != null && c != n && c.isClickable() && buttonSized(c)) words += " " + allText(c, 0);
            double payable = pagePayable(sc);
            if (words.toLowerCase(Locale.ROOT).contains("mobikwik")) {
                // Choosing the MobiKwik wallet. Its row can say "Pay using Mobikwik" and show the wallet
                // balance (₹1000): that is not the price. The price is the page's "Amount Payable".
                if (payable > a.amount + 1) return "blocked:over:" + payable + "|" + words.trim();
                a.walletChosen = true;
                if (commit != null) a.taps--;
                return null;
            }
            // never another way of paying, only the MobiKwik wallet
            if (OTHER_METHOD.matcher(words).find()) return "blocked:" + words.trim() + " (only the MobiKwik wallet is allowed)";
            if (commit == null) return null;
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

    private static String extraCheck(AccessibilityNodeInfo n) {
        if (extrasAllowed) return null;
        String words = label(n) + " " + (buttonSized(n) ? allText(n, 0) : "");
        AccessibilityNodeInfo c = clickable(n);
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
        n.refresh();
        String extra = extraCheck(n);
        if (extra != null) return extra;
        String refused = payCheck(sc, n);
        if (refused != null) return refused;
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
        if (hit != null) {
            String extra = extraCheck(hit);
            if (extra != null) return extra;
            String refused = payCheck(sc, hit);
            if (refused != null) return refused;
            if (hit.isPassword()) return "password";
        }
        return gesture(x, y, x, y, 60) ? "ok" : "failed";
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
            AccessibilityNodeInfo c = clickable(n);
            if (c != null) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            SystemClock.sleep(600);
            return "not_a_field";
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT) ? "ok" : "failed";
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

    @Override protected void onServiceConnected() { instance = this; }

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
            if (!p.equals(getPackageName())) currentPkg = p;
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
        c.pkg = currentPkg;
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
