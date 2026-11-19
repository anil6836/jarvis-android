package __PKG__;

import android.app.Activity;

import java.util.function.Consumer;

/** (this app does not read QR codes: no scanner library in it) */
final class Qr {
    private Qr() {}

    static void scan(Activity a, Consumer<String> ok, Consumer<String> err) { err.accept("ఈ యాప్‌లో QR స్కాన్ లేదు"); }
}
