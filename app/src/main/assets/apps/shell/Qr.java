package __PKG__;

import android.app.Activity;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.util.function.Consumer;

/** ▦ A QR / bar code read by Google's own scanner screen (Play services; this app gets no camera permission for it). */
final class Qr {
    private Qr() {}

    static void scan(Activity a, Consumer<String> ok, Consumer<String> err) {
        GmsBarcodeScanning.getClient(a).startScan()
                .addOnSuccessListener(b -> ok.accept(b.getRawValue()))
                .addOnCanceledListener(() -> ok.accept(null))
                .addOnFailureListener(e -> err.accept("QR స్కాన్ కాలేదు: " + e.getMessage()));
    }
}
