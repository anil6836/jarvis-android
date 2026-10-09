package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * W54: a picture shown on his watch (the guard's photo from home, where the bike is parked, a list he photographed, any
 * picture shared to "⌚ వాచ్‌కి పంపు"): made small here (under ~60 KB) and kept on the watch (its last six).
 */
final class WatchPhoto {
    private WatchPhoto() {}

    static final String P_PHOTO = "/jarvis/photo";

    /** False when the watch isn't near or the picture couldn't be read. */
    static boolean send(Context c, byte[] image, String title) {
        if (image == null || !WatchHub.known(c) || !WatchHub.watchHere(c)) return false;
        try {
            byte[] small = shrink(image);
            if (small == null) return false;
            JSONObject o = new JSONObject().put("title", title == null ? "" : title).put("t", System.currentTimeMillis())
                    .put("jpg", android.util.Base64.encodeToString(small, android.util.Base64.NO_WRAP));
            WatchHub.send(c, P_PHOTO, o);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** At most 360 px a side, JPEG quality stepped down until under 60 KB. */
    static byte[] shrink(byte[] image) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, o);
        int side = Math.max(o.outWidth, o.outHeight);
        if (side <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (side / (o.inSampleSize * 2) >= 360) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(image, 0, image.length, o);
        if (b == null) return null;
        float k = 360f / Math.max(b.getWidth(), b.getHeight());
        if (k < 1) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        for (int q = 80; q >= 35; q -= 15) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, q, out);
            if (out.size() <= 60_000 || q <= 35) return out.toByteArray();
        }
        return null;
    }
}
