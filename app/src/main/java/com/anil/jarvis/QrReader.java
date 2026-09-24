package com.anil.jarvis;

import android.graphics.Bitmap;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;

/** Reads QR codes and barcodes from a picture, on the phone (ZXing). */
final class QrReader {
    private QrReader() {}

    static String read(Bitmap src) {
        Bitmap b = src;
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max > 1600) {
            float s = 1600f / max;
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
        }
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result r = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, px))), hints);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }
}
