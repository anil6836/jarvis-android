package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 👤 (Anil, 12 Nov "A,B చేయి"): two more ways to introduce someone by face, next to the front camera (FaceSight):
 * A — the 🎥 Live camera (the back camera) pointed at the person; B — a photo he picks from the gallery (several faces:
 * he taps the right one). Only the person he names is learned, on the phone, as face fingerprints (no picture kept);
 * the same checks as the front camera (a different face under a known name, or a known face under a new name, is refused).
 */
final class FaceLearn {
    private FaceLearn() {}

    /** The main thread (made when needed: the class also loads where there is none). */
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    private static FaceDetector detector() {
        return FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.08f).build());
    }

    /** As FaceSight.save: refuses a different face under a known name and a known face under a new name; else saved. */
    static String save(Context c, List<float[]> got, String name, boolean owner) {
        if (got.isEmpty()) return "no_face";
        float[] mean = People.mean(got);
        for (People.Person p : People.all(c)) {
            float s = People.best(p, mean);
            boolean same = p.name.equalsIgnoreCase(name) || (owner && p.owner);
            if (same && s < People.SAME - 0.1f) return owner && p.owner ? "not_owner" : "not_same:" + p.name;
            if (!same && s >= People.SAME) return "same_as:" + p.name;
        }
        People.add(c, name, owner, got);
        return "ok";
    }

    private static boolean frontal(Face f) { return Math.abs(f.getHeadEulerAngleY()) <= 30 && Math.abs(f.getHeadEulerAngleZ()) <= 28; }

    // ---------------------------------------------------------------- A: the live back camera (🎥)

    /** Background thread: a few camera pictures with one clear face (up to 12 s) → learned. "camera_off" when it closes. */
    static String fromLiveCamera(Context c, String name, boolean owner) throws Exception {
        if (!FaceNet.ready(c)) return "no_model";
        FaceDetector det = detector();
        List<float[]> got = new ArrayList<>();
        String why = "no_face", last = null;
        long until = System.currentTimeMillis() + 12_000;
        try (FaceNet net = new FaceNet(c)) {
            while (System.currentTimeMillis() < until && got.size() < 5) {
                String f = CameraPanel.latestFrame;
                if (f == null) return "camera_off";
                if (f.equals(last)) { Thread.sleep(200); continue; } // (a new picture about every second)
                last = f;
                byte[] jpg = android.util.Base64.decode(f, android.util.Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                if (b == null) continue;
                try {
                    List<Face> faces = Tasks.await(det.process(InputImage.fromBitmap(b, 0)), 8, TimeUnit.SECONDS);
                    if (faces.size() != 1) { why = faces.isEmpty() ? "no_face" : "many_faces"; got.clear(); continue; }
                    Face face = faces.get(0);
                    if (face.getBoundingBox().width() < b.getWidth() * 0.15f || !frontal(face)) { why = "not_frontal"; continue; }
                    Bitmap cut = FacePhotos.crop(b, face.getBoundingBox());
                    if (cut == null) continue;
                    got.add(net.embed(cut));
                    cut.recycle();
                } finally {
                    b.recycle();
                }
            }
        } finally {
            try { det.close(); } catch (Exception ignored) {}
        }
        return got.size() >= 3 ? save(c, got, name, owner) : why;
    }

    // ---------------------------------------------------------------- B: a photo from the gallery

    /** The clear faces in a photo (upright), left to right, at most 8, as squares ready for FaceNet; null when it can't be read. */
    static List<Bitmap> faces(Context c, Uri u) {
        Bitmap b = FacePhotos.upright(c, u, 1600);
        if (b == null) return null;
        FaceDetector det = detector();
        try {
            List<Face> fs = new ArrayList<>(Tasks.await(det.process(InputImage.fromBitmap(b, 0)), 15, TimeUnit.SECONDS));
            fs.sort((x, y) -> Integer.compare(x.getBoundingBox().left, y.getBoundingBox().left));
            List<Bitmap> out = new ArrayList<>();
            for (Face f : fs) {
                Rect box = f.getBoundingBox();
                if (Math.min(box.width(), box.height()) < 64 || !frontal(f)) continue; // (too small or turned away to learn well)
                Bitmap cut = FacePhotos.crop(b, box);
                if (cut != null) out.add(cut);
                if (out.size() >= 8) break;
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try { det.close(); } catch (Exception ignored) {}
            b.recycle();
        }
    }

    /** One face from a photo → learned (the face and its mirror image: two fingerprints). Background thread. */
    static String fromFace(Context c, Bitmap face, String name, boolean owner) {
        try (FaceNet net = new FaceNet(c)) {
            List<float[]> got = new ArrayList<>();
            got.add(net.embed(face));
            Matrix m = new Matrix();
            m.preScale(-1, 1);
            Bitmap mir = Bitmap.createBitmap(face, 0, 0, face.getWidth(), face.getHeight(), m, true);
            got.add(net.embed(mir));
            mir.recycle();
            return save(c, got, name, owner);
        } catch (Exception e) {
            return "failed";
        }
    }

    /**
     * B: the picked photo's faces found; one → learned; several → he taps the right one (a row of the faces); none → said.
     * done gets the result on the main thread ("ok", "no_face", "same_as:X", …, "cancelled").
     */
    static void choose(Activity a, Uri u, String name, boolean owner, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            try { if (!FaceNet.ready(a)) FaceNet.ensure(a, t -> {}); } catch (Exception e) { main().post(() -> done.accept("no_model")); return; }
            List<Bitmap> fs = faces(a, u);
            if (fs == null || fs.isEmpty()) { main().post(() -> done.accept(fs == null ? "unreadable" : "no_face")); return; }
            if (fs.size() == 1) { String r = fromFace(a, fs.get(0), name, owner); fs.get(0).recycle(); main().post(() -> done.accept(r)); return; }
            main().post(() -> {
                if (a.isFinishing() || a.isDestroyed()) { for (Bitmap f : fs) f.recycle(); return; }
                LinearLayout row = new LinearLayout(a);
                int p = Ui.dp(a, 12);
                row.setPadding(p, p, p, p);
                final AlertDialog[] dlg = new AlertDialog[1];
                for (Bitmap f : fs) {
                    ImageView iv = new ImageView(a);
                    iv.setImageBitmap(f);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 84), Ui.dp(a, 84));
                    lp.rightMargin = Ui.dp(a, 10);
                    iv.setOnClickListener(v -> {
                        if (dlg[0] != null) dlg[0].dismiss();
                        new Thread(() -> { String r = fromFace(a, f, name, owner); main().post(() -> done.accept(r)); }, "face-learn").start();
                    });
                    row.addView(iv, lp);
                }
                HorizontalScrollView hs = new HorizontalScrollView(a);
                hs.addView(row);
                dlg[0] = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("👤 ఈ ఫోటోలో " + name + " ఎవరు? ఆ ముఖం నొక్కండి")
                        .setView(hs)
                        .setNegativeButton("వద్దు", (d, w) -> done.accept("cancelled"))
                        .setOnCancelListener(d -> done.accept("cancelled"))
                        .create();
                dlg[0].show();
            });
        }, "face-photo").start();
    }

    /** What Jarvis says about the result (Telugu). */
    static String say(String r, String name) {
        if (r == null) r = "";
        if (r.equals("ok")) return "సరే Sir, " + name + " ముఖం గుర్తుపెట్టుకున్నాను. ఫోటో దాచలేదు, ముఖం గుర్తులు మాత్రమే ఫోన్‌లో ఉంటాయి.";
        if (r.startsWith("same_as:")) return "ఈ ముఖం ఇప్పటికే " + r.substring(8) + " గా గుర్తుంది. ఒకరినే రెండు పేర్లతో దాచను.";
        if (r.startsWith("not_same:")) return r.substring(9) + " పేరుతో వేరే ముఖం ఇప్పటికే ఉంది. వేరే పేరు (ఉదా: బంధుత్వంతో) చెప్పండి, లేదా పాతది మర్చిపోమనండి.";
        switch (r) {
            case "no_face": return "స్పష్టమైన ముఖం కనిపించలేదు. ముఖం పెద్దగా, నేరుగా చూస్తున్న ఫోటో / కెమెరా కావాలి.";
            case "many_faces": return "కెమెరాలో ఒకటి కంటే ఎక్కువ ముఖాలు ఉన్నాయి. " + name + " ఒక్కరే కనిపించేలా పట్టుకుని మళ్ళీ చెప్పండి.";
            case "not_frontal": return "ముఖం పక్కకి తిరిగింది లేదా దూరంగా ఉంది. ఇంకా దగ్గరగా, నేరుగా చూసేలా పట్టుకోండి.";
            case "camera_off": return "Live కెమెరా మూసుకుపోయింది. 🎥 Live కెమెరా తెరిచి మళ్ళీ చెప్పండి.";
            case "not_owner": return "ఈ ముఖం మీ ముఖంలా లేదు. నిజంగా మీరే అయితే, ముందు 'నన్ను మర్చిపో' అని, తర్వాత మళ్ళీ పరిచయం చేయండి.";
            case "no_model": return "ముఖాలు గుర్తుపట్టే ఫైల్ (సుమారు 23 MB) తేలేకపోయాను. ఇంటర్నెట్ ఆన్ చేసి మళ్ళీ ప్రయత్నించండి.";
            case "unreadable": return "ఆ ఫోటో తెరవలేకపోయాను.";
            case "cancelled": return "సరే, ఎవరినీ దాచలేదు.";
            default: return "ముఖం నేర్చుకోలేకపోయాను. మళ్ళీ ప్రయత్నించండి.";
        }
    }
}
