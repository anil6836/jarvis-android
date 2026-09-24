package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Confirmation dialogs shared by the full screen and the small panel. */
final class Dialogs {
    private Dialogs() {}

    /**
     * Shows a Yes/No dialog and waits (call from a background thread).
     * With autoSeconds > 0 it counts down on the Yes button and confirms by itself.
     */
    static boolean confirm(Activity a, String title, String message, String yes, int autoSeconds) {
        if (a == null || a.isFinishing()) return false;
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        a.runOnUiThread(() -> {
            AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(yes, (x, w) -> ok.set(true))
                    .setNegativeButton("వద్దు", (x, w) -> ok.set(false))
                    .setOnDismissListener(x -> done.countDown())
                    .create();
            d.show();
            if (autoSeconds > 0) {
                final int[] left = {autoSeconds};
                Runnable[] step = new Runnable[1];
                step[0] = () -> {
                    if (!d.isShowing()) return;
                    if (left[0] <= 0) { ok.set(true); d.dismiss(); return; }
                    Button b = d.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (b != null) b.setText(yes + " (" + left[0] + ")");
                    left[0]--;
                    main.postDelayed(step[0], 1000);
                };
                step[0].run();
            }
        });
        try {
            if (!done.await(90, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException e) {
            return false;
        }
        return ok.get();
    }
}
