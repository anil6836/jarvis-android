package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The photos Jarvis found for "… ఫోటోలు చూపించు": a grid; tap one to open it big, or share them all. */
public class PhotoGridActivity extends Activity {
    private static final String EXTRA_URIS = "uris", EXTRA_TITLE = "title";

    static void show(Context c, String title, List<Uri> uris) {
        ArrayList<String> s = new ArrayList<>();
        for (Uri u : uris) s.add(u.toString());
        c.startActivity(new Intent(c, PhotoGridActivity.class).putStringArrayListExtra(EXTRA_URIS, s)
                .putExtra(EXTRA_TITLE, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final ExecutorService loader = Executors.newFixedThreadPool(3);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG_TOP);
        getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        List<String> raw = getIntent().getStringArrayListExtra(EXTRA_URIS);
        final List<Uri> uris = new ArrayList<>();
        if (raw != null) for (String s : raw) uris.add(Uri.parse(s));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new Ui.Aurora());
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "📸 \"" + getIntent().getStringExtra(EXTRA_TITLE) + "\" · " + uris.size() + " ఫోటోలు", 17, 0xFFFFFFFF);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView share = Ui.text(this, "షేర్", 15, Ui.C_CYAN);
        share.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        share.setOnClickListener(v -> shareAll(uris));
        head.addView(share);
        IconView close = new IconView(this, IconView.CLOSE, 0xFFFFFFFF);
        close.setBackground(Ui.glass(this, 20));
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        root.addView(head);
        TextView hint = Ui.text(this, "ఒకటి నొక్కితే పెద్దగా తెరుచుకుంటుంది", 13, Ui.MUTED);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        root.addView(hint);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setVerticalSpacing(Ui.dp(this, 6));
        final int cell = (getResources().getDisplayMetrics().widthPixels - 2 * p - 2 * Ui.dp(this, 6)) / 3;
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return uris.size(); }
            @Override public Object getItem(int i) { return uris.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View reuse, ViewGroup parent) {
                ImageView iv = reuse instanceof ImageView ? (ImageView) reuse : new ImageView(PhotoGridActivity.this);
                iv.setLayoutParams(new GridView.LayoutParams(cell, cell));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.round(PhotoGridActivity.this, 0x22FFFFFF, 0, 10));
                iv.setClipToOutline(true);
                Uri u = uris.get(i);
                iv.setTag(u);
                iv.setImageDrawable(null);
                loader.submit(() -> {
                    Bitmap bm = thumb(PhotoGridActivity.this, u, 320);
                    runOnUiThread(() -> { if (u.equals(iv.getTag()) && bm != null) iv.setImageBitmap(bm); });
                });
                return iv;
            }
        });
        grid.setOnItemClickListener((parent, v, i, id) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uris.get(i), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception ignored) {}
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void shareAll(List<Uri> uris) {
        if (uris.isEmpty()) return;
        Intent s = uris.size() == 1 ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0))
                : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        s.setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(s, "ఫోటోలు షేర్ చేయండి")); } catch (Exception ignored) {}
    }

    /** A small copy of a gallery photo. */
    static Bitmap thumb(Context c, Uri u, int size) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return c.getContentResolver().loadThumbnail(u, new Size(size, size), null);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o2); }
        } catch (Exception e) {
            return null;
        }
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }
}
