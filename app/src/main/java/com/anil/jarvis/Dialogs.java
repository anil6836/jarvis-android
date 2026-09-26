package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Confirmation dialogs shared by the full screen and the small panel. */
final class Dialogs {
    private Dialogs() {}

    /**
     * Shows a Yes/No dialog and waits (call from a background thread).
     * With autoSeconds > 0 it counts down on the Yes button and confirms by itself,
     * but only while the Jarvis window is actually on screen; otherwise it waits for a tap.
     */
    static boolean confirm(Activity a, String title, String message, String yes, int autoSeconds) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return false;
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        a.runOnUiThread(() -> {
            if (a.isFinishing() || a.isDestroyed()) { done.countDown(); return; }
            AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(yes, (x, w) -> ok.set(true))
                    .setNegativeButton("వద్దు", (x, w) -> ok.set(false))
                    .setOnDismissListener(x -> done.countDown())
                    .create();
            try {
                d.show();
            } catch (Exception e) { // window token gone (BadTokenException)
                done.countDown();
                return;
            }
            shown.set(d);
            if (autoSeconds > 0) {
                final int[] left = {autoSeconds};
                Runnable[] step = new Runnable[1];
                step[0] = () -> {
                    if (a.isDestroyed() || !d.isShowing()) return;
                    Button b = d.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (!onScreen(a)) {
                        // Another app (e.g. the call screen) is in front: never confirm unseen.
                        // Start the countdown again from the top once Jarvis is visible.
                        left[0] = autoSeconds;
                        if (b != null) b.setText(yes);
                        main.postDelayed(step[0], 1000);
                        return;
                    }
                    if (left[0] <= 0) { ok.set(true); d.dismiss(); return; }
                    if (b != null) b.setText(yes + " (" + left[0] + ")");
                    left[0]--;
                    main.postDelayed(step[0], 1000);
                };
                step[0].run();
            }
        });
        try {
            if (!done.await(90, TimeUnit.SECONDS)) {
                ok.set(false);
                dismiss(a, shown.get());
                return false;
            }
        } catch (InterruptedException e) {
            ok.set(false);
            dismiss(a, shown.get());
            return false;
        }
        return ok.get();
    }

    /** True while the activity's window is visible to Anil (not behind another app, screen not off). */
    private static boolean onScreen(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return false;
        try {
            View decor = a.getWindow().getDecorView();
            return decor.getWindowVisibility() == View.VISIBLE && decor.isShown();
        } catch (Exception e) {
            return false;
        }
    }

    private static void dismiss(Activity a, AlertDialog d) {
        if (d == null) return;
        a.runOnUiThread(() -> {
            if (a.isDestroyed() || !d.isShowing()) return;
            try { d.dismiss(); } catch (Exception ignored) {}
        });
    }
}
