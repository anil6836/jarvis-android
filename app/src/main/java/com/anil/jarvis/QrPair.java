package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.WindowManager;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * Joining his phone and the home tablet without typing the bot token: his phone shows a QR code (the bot token and his
 * chat), the tablet's Jarvis camera reads it and keeps them. The QR is a key: shown only on his tap, never photographed
 * (the dialog blocks screenshots), never sent to any AI (the camera hands it over before anything else).
 */
final class QrPair {
    private QrPair() {}

    static final String PREFIX = "JARVISLINK1|";

    /** The pairing text (empty when this phone isn't joined to its bot yet). */
    static String code(Context c) {
        String t = Guard.token(c), id = Guard.chat(c);
        if (t.isEmpty() || id.isEmpty()) return "";
        String name = Guard.sp(c).getString("tg_name", "").replace("|", " ");
        return PREFIX + t + "|" + id + "|" + name;
    }

    static boolean is(String code) { return code != null && code.startsWith(PREFIX); }

    /** On the tablet: the code read by the camera → the same bot here. Returns the line to show / say. */
    static String apply(Context c, String code) {
        if (!is(code)) return "ఇది Jarvis QR కాదు.";
        String[] p = code.substring(PREFIX.length()).split("\\|", -1);
        if (p.length < 2 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) return "QR సరిగ్గా చదవలేకపోయాను. మళ్లీ చూపించండి.";
        Guard.setToken(c, p[0].trim());
        Guard.sp(c).edit().putString("tg_chat", p[1].trim()).putString("tg_name", p.length > 2 ? p[2].trim() : "").apply();
        new Thread(() -> {
            Guard.botName(c); // (his bot's name, for his phone's notifications)
            Guard.send(c, "✅ ఇంటి టాబ్లెట్ ఈ bot తో కలిసింది (QR).");
        }, "qr-pair").start();
        return "✓ ఫోన్‌తో కలిపాను. మీ Telegram లో టెస్ట్ మెసేజ్ వస్తుంది.";
    }

    /** The code as a black-and-white picture (size px), or null. */
    static Bitmap bitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            return null;
        }
    }

    /** On his phone: the QR on the screen (no screenshots of it), with how to use it. */
    static void show(Activity a) {
        String code = code(a);
        if (code.isEmpty()) {
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("ముందు ఈ ఫోన్‌ని bot తో కలపండి")
                    .setMessage("పైన bot token పెట్టి \"Telegram చాట్ కనుక్కో\" నొక్కండి. తర్వాత QR వస్తుంది.").setPositiveButton("సరే", null).show();
            return;
        }
        int size = Math.round(Math.min(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels) * 0.7f);
        Bitmap b = bitmap(code, size);
        if (b == null) return;
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(b);
        iv.setBackgroundColor(0xFFFFFFFF);
        int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
        iv.setPadding(pad, pad, pad, pad);
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🔲 టాబ్లెట్‌తో కలపడానికి")
                .setMessage("టాబ్లెట్‌లో ⚙️ → 🏠 ఇంటి టాబ్లెట్ → 📮 ఫోన్ ↔ టాబ్లెట్ లింక్ → \"📷 ఫోన్ QR స్కాన్ చేయి\" నొక్కి, ఈ QR ని టాబ్లెట్ కెమెరాకి చూపించండి. "
                        + "⚠️ ఇది మీ bot తాళం: ఫోటో తీయకండి, ఎవరికీ చూపించకండి.")
                .setView(iv)
                .setPositiveButton("అయింది", null)
                .create();
        if (d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.show();
    }
}
