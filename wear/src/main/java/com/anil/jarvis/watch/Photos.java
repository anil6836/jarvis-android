package com.anil.jarvis.watch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * W54: pictures the phone sent (the guard's photo from home, the parking spot, a list): the last six kept here, newest
 * first; the bezel or a tap goes to the next. A new one buzzes and shows as a card.
 */
public class Photos extends Activity {
    private static final int KEEP = 6, NOTE = 74;
    private ImageView img;
    private TextView title;
    private int at;

    static void open(Context c) { c.startActivity(new Intent(c, Photos.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    private static File dir(Context c) { File d = new File(c.getFilesDir(), "photos"); d.mkdirs(); return d; }

    private static JSONArray list(Context c) {
        try { return new JSONArray(Link.sp(c).getString("photos", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** From the phone: kept, and a card that opens it. */
    static void got(Context c, JSONObject o) {
        try {
            byte[] b = android.util.Base64.decode(o.optString("jpg"), android.util.Base64.DEFAULT);
            long t = o.optLong("t", System.currentTimeMillis());
            File f = new File(dir(c), t + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(b); }
            JSONArray a = list(c), keep = new JSONArray();
            keep.put(new JSONObject().put("f", f.getName()).put("title", o.optString("title")).put("t", t));
            for (int i = 0; i < a.length() && keep.length() < KEEP; i++) keep.put(a.getJSONObject(i));
            for (int i = KEEP; i < a.length(); i++) new File(dir(c), a.getJSONObject(i).optString("f")).delete();
            Link.sp(c).edit().putString("photos", keep.toString()).apply();
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_photos", "Jarvis ఫోటోలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, NOTE, new Intent(c, Photos.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
            Notification.Builder n = new Notification.Builder(c, "jarvis_photos").setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setContentTitle("🖼️ " + (o.optString("title").isEmpty() ? "ఫోటో" : o.optString("title"))).setContentIntent(open).setAutoCancel(true)
                    .setTimeoutAfter(6 * 3600_000L);
            if (bm != null) n.setStyle(new Notification.BigPictureStyle().bigPicture(bm));
            nm.notify(NOTE, n.build());
            Talk.buzz(c, 40, 80, 40);
        } catch (Exception ignored) {}
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.refresh(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = WUi.dp(this, 22);
        img.setPadding(pad, pad, pad, pad * 2);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        title = WUi.text(this, "", 11.5f, WUi.TEXT, true);
        bottom.addView(title);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = WUi.dp(this, 16);
        root.addView(bottom, lp);
        root.setOnClickListener(v -> { at++; show(); });
        setContentView(root);
        show();
    }

    private void show() {
        JSONArray a = list(this);
        if (a.length() == 0) { title.setText("ఫోటోలు ఏమీ లేవు. ఫోన్‌లో \"బండి ఫోటో వాచ్‌కి పంపు\" అనండి, లేదా ఫోటో షేర్ → ⌚ వాచ్‌కి పంపు."); img.setImageBitmap(null); return; }
        at = (at % a.length() + a.length()) % a.length();
        JSONObject o = a.optJSONObject(at);
        Bitmap bm = BitmapFactory.decodeFile(new File(dir(this), o.optString("f")).getAbsolutePath());
        img.setImageBitmap(bm);
        long mins = (System.currentTimeMillis() - o.optLong("t")) / 60_000L;
        String ago = mins < 60 ? mins + " ని క్రితం" : mins < 1440 ? mins / 60 + " గం క్రితం" : mins / 1440 + " రో క్రితం";
        title.setText((at + 1) + "/" + a.length() + " · " + o.optString("title") + " · " + ago);
    }

    private float rotary;

    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotary += ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (Math.abs(rotary) >= 1f) { at += rotary < 0 ? 1 : -1; rotary = 0; show(); Talk.buzz(this, 10); }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }
}
