package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Jarvis camera's own eyes on the phone (no internet, no AI cost): a small object finder (EfficientDet-Lite0, COCO's
 * 80 kinds of things: people, vehicles, animals, everyday things) that says where each one is, many times a second.
 * The model comes inside the APK (assets/objdet.tflite, put there by the build); without it the camera simply has no
 * live rings and asks the AI as before.
 */
final class Detector {
    /** One thing found: its kind (COCO's English name), Telugu name, sureness 0..1 and box 0..1 of the picture (x0, y0, x1, y1). */
    static final class Det {
        final String en, te, plural;
        final float score;
        final float[] box;
        Det(String en, float score, float[] box) {
            this.en = en;
            this.score = score;
            this.box = box;
            String[] t = Labels.te(en);
            this.te = t[0];
            this.plural = t[1];
        }
    }

    private static final String ASSET = "objdet.tflite";
    private org.tensorflow.lite.Interpreter tfl;
    private int inW = 320, inH = 320;
    private boolean quant, signed;
    /** It failed several times in a row (a model this phone can't run): the camera stops using it and says so. */
    volatile boolean broken;
    private int failsInRow;
    private String[] labels;
    private int boxesAt = -1, classesAt = -1, scoresAt = -1, countAt = -1;
    private int maxN = 25;
    private ByteBuffer input; // (made once: a few pictures a second must not make new memory each time)
    private int[] px;
    /** Below this a thing isn't shown. */
    static final float MIN_SCORE = 0.42f;

    private Detector() {}

    /** The model is in this build. */
    static boolean available(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) { return in.read() >= 0; } catch (Exception e) { return false; }
    }

    /** Loaded once (null: no model in this build, or it would not open). Background thread. */
    static Detector open(Context c) {
        try {
            File f = new File(c.getCacheDir(), ASSET);
            long want = -1;
            try (android.content.res.AssetFileDescriptor fd = c.getAssets().openFd(ASSET)) { want = fd.getLength(); } catch (Exception ignored) {} // (stored uncompressed)
            if (!f.exists() || f.length() < 1000 || want > 0 && f.length() != want) { // a new model in an update, or a copy cut short: copied again
                File tmp = new File(c.getCacheDir(), ASSET + ".part");
                try (InputStream in = c.getAssets().open(ASSET); FileOutputStream o = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return null; }
            }
            Detector d = new Detector();
            org.tensorflow.lite.Interpreter.Options o = new org.tensorflow.lite.Interpreter.Options();
            o.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            d.tfl = new org.tensorflow.lite.Interpreter(f, o);
            int[] in = d.tfl.getInputTensor(0).shape(); // [1, h, w, 3]
            if (in.length == 4) { d.inH = in[1]; d.inW = in[2]; }
            org.tensorflow.lite.DataType dt = d.tfl.getInputTensor(0).dataType();
            d.signed = dt == org.tensorflow.lite.DataType.INT8;
            d.quant = dt == org.tensorflow.lite.DataType.UINT8 || d.signed;
            d.labels = Labels.fromModel(f);
            d.outputs();
            if (d.boxesAt < 0 || d.classesAt < 0) { d.tfl.allocateTensors(); d.outputs(); } // (some models know their shapes only then)
            if (d.boxesAt < 0 || d.classesAt < 0) { d.close(); return null; }               // not a model this can read
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which output is which: the [1, N, 4] one is the boxes, the [1] one the count; of the two [1, N] ones, the classes are whole numbers. */
    private void outputs() {
        boxesAt = classesAt = scoresAt = countAt = -1;
        int n = tfl.getOutputTensorCount();
        List<Integer> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] s = tfl.getOutputTensor(i).shape();
            if (s.length == 3 && s[2] == 4) { boxesAt = i; maxN = s[1]; }
            else if (s.length == 1 || s.length == 2 && s[1] == 1 && s[0] == 1) countAt = i;
            else if (s.length == 2) pairs.add(i);
        }
        if (pairs.size() >= 2) { classesAt = pairs.get(0); scoresAt = pairs.get(1); } // (sorted out on the first run: see detect)
    }

    private boolean orderKnown;

    synchronized void close() { try { if (tfl != null) tfl.close(); } catch (Exception ignored) {} tfl = null; }

    /** The things in this picture, surest first (background thread). */
    synchronized List<Det> detect(Bitmap pic) {
        List<Det> out = new ArrayList<>();
        if (tfl == null || pic == null || boxesAt < 0 || classesAt < 0) return out;
        Bitmap s = Bitmap.createScaledBitmap(pic, inW, inH, true);
        if (input == null) {
            input = ByteBuffer.allocateDirect(inW * inH * 3 * (quant ? 1 : 4)).order(ByteOrder.nativeOrder());
            px = new int[inW * inH];
        }
        input.clear();
        s.getPixels(px, 0, inW, 0, 0, inW, inH);
        if (s != pic) s.recycle();
        for (int c : px) {
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            if (quant) { int k = signed ? 128 : 0; input.put((byte) (r - k)); input.put((byte) (g - k)); input.put((byte) (b - k)); }
            else { input.putFloat((r - 127.5f) / 127.5f); input.putFloat((g - 127.5f) / 127.5f); input.putFloat((b - 127.5f) / 127.5f); } // (EfficientDet's float input)
        }
        input.rewind();
        float[][][] boxes = new float[1][maxN][4];
        float[][] a = new float[1][maxN], b = new float[1][maxN];
        float[] count = new float[1];
        java.util.Map<Integer, Object> outs = new java.util.HashMap<>();
        outs.put(boxesAt, boxes);
        outs.put(classesAt, a);
        outs.put(scoresAt, b);
        try {
            if (countAt >= 0) outs.put(countAt, tfl.getOutputTensor(countAt).shape().length == 2 ? new float[1][1] : count);
            tfl.runForMultipleInputsOutputs(new Object[]{input}, outs);
            failsInRow = 0;
        } catch (Throwable t) {
            if (++failsInRow >= 5) broken = true;
            return out;
        }
        float[] cls = a[0], sc = b[0];
        if (!orderKnown) { // the classes are whole numbers (0, 2, 17...); the scores are fractions
            if (!wholeNumbers(cls) && wholeNumbers(sc)) { int t = classesAt; classesAt = scoresAt; scoresAt = t; float[] x = cls; cls = sc; sc = x; }
            orderKnown = wholeNumbers(cls) && !wholeNumbers(sc) || orderKnown;
        }
        int n = maxN;
        Object cnt = outs.get(countAt);
        if (cnt instanceof float[]) n = Math.min(n, Math.round(((float[]) cnt)[0]));
        else if (cnt instanceof float[][]) n = Math.min(n, Math.round(((float[][]) cnt)[0][0]));
        for (int i = 0; i < n; i++) {
            if (sc[i] < MIN_SCORE) continue;
            String en = label(Math.round(cls[i]));
            if (en.isEmpty()) continue;
            float[] bx = boxes[0][i]; // ymin, xmin, ymax, xmax
            float x0 = clamp(bx[1]), y0 = clamp(bx[0]), x1 = clamp(bx[3]), y1 = clamp(bx[2]);
            if (x1 - x0 < 0.01f || y1 - y0 < 0.01f) continue;
            out.add(new Det(en, sc[i], new float[]{x0, y0, x1, y1}));
        }
        out.sort((p, q) -> Float.compare(q.score, p.score));
        return out;
    }

    private String label(int i) {
        String[] l = labels != null ? labels : Labels.COCO90;
        if (i < 0 || i >= l.length) return "";
        String s = l[i].trim();
        return s.equals("???") || s.equalsIgnoreCase("n/a") ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean wholeNumbers(float[] v) {
        boolean any = false;
        for (float x : v) { if (Math.abs(x - Math.round(x)) > 1e-4) return false; if (x != 0) any = true; }
        return any || v.length > 0;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    // ================================================================ names

    /** COCO's kinds in English and Telugu (singular, plural), and the label list a model carries inside it. */
    static final class Labels {
        private Labels() {}

        /** The 90-number COCO list the EfficientDet / SSD models use (index = COCO id - 1; the unused ids are "???"). */
        static final String[] COCO90 = {"person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
                "fire hydrant", "???", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear",
                "zebra", "giraffe", "???", "backpack", "umbrella", "???", "???", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
                "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "???", "wine glass",
                "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
                "cake", "chair", "couch", "potted plant", "bed", "???", "dining table", "???", "???", "toilet", "???", "tv", "laptop", "mouse",
                "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "???", "book", "clock", "vase",
                "scissors", "teddy bear", "hair drier", "toothbrush"};

        private static final String[][] TE = {
                {"person", "మనిషి", "మనుషులు"}, {"bicycle", "సైకిల్", "సైకిళ్లు"}, {"car", "కారు", "కార్లు"}, {"motorcycle", "బైక్", "బైక్‌లు"},
                {"airplane", "విమానం", "విమానాలు"}, {"bus", "బస్సు", "బస్సులు"}, {"train", "రైలు", "రైళ్లు"}, {"truck", "లారీ", "లారీలు"},
                {"boat", "పడవ", "పడవలు"}, {"traffic light", "ట్రాఫిక్ లైట్", "ట్రాఫిక్ లైట్లు"}, {"fire hydrant", "నీటి పైపు", "నీటి పైపులు"},
                {"stop sign", "స్టాప్ బోర్డు", "స్టాప్ బోర్డులు"}, {"parking meter", "పార్కింగ్ మీటర్", "పార్కింగ్ మీటర్లు"}, {"bench", "బెంచీ", "బెంచీలు"},
                {"bird", "పక్షి", "పక్షులు"}, {"cat", "పిల్లి", "పిల్లులు"}, {"dog", "కుక్క", "కుక్కలు"}, {"horse", "గుర్రం", "గుర్రాలు"},
                {"sheep", "గొర్రె", "గొర్రెలు"}, {"cow", "ఆవు", "ఆవులు"}, {"elephant", "ఏనుగు", "ఏనుగులు"}, {"bear", "ఎలుగుబంటి", "ఎలుగుబంట్లు"},
                {"zebra", "జీబ్రా", "జీబ్రాలు"}, {"giraffe", "జిరాఫీ", "జిరాఫీలు"}, {"backpack", "బ్యాగ్", "బ్యాగులు"}, {"umbrella", "గొడుగు", "గొడుగులు"},
                {"handbag", "హ్యాండ్‌బ్యాగ్", "హ్యాండ్‌బ్యాగులు"}, {"tie", "టై", "టైలు"}, {"suitcase", "సూట్‌కేస్", "సూట్‌కేసులు"},
                {"frisbee", "ఫ్రిస్బీ", "ఫ్రిస్బీలు"}, {"skis", "స్కీలు", "స్కీలు"}, {"snowboard", "స్నోబోర్డ్", "స్నోబోర్డులు"},
                {"sports ball", "బంతి", "బంతులు"}, {"kite", "గాలిపటం", "గాలిపటాలు"}, {"baseball bat", "బ్యాట్", "బ్యాట్లు"},
                {"baseball glove", "గ్లవ్", "గ్లవ్‌లు"}, {"skateboard", "స్కేట్‌బోర్డ్", "స్కేట్‌బోర్డులు"}, {"surfboard", "సర్ఫ్‌బోర్డ్", "సర్ఫ్‌బోర్డులు"},
                {"tennis racket", "రాకెట్", "రాకెట్లు"}, {"bottle", "సీసా", "సీసాలు"}, {"wine glass", "గ్లాసు", "గ్లాసులు"}, {"cup", "కప్పు", "కప్పులు"},
                {"fork", "ఫోర్క్", "ఫోర్కులు"}, {"knife", "కత్తి", "కత్తులు"}, {"spoon", "చెంచా", "చెంచాలు"}, {"bowl", "గిన్నె", "గిన్నెలు"},
                {"banana", "అరటిపండు", "అరటిపండ్లు"}, {"apple", "ఆపిల్", "ఆపిల్స్"}, {"sandwich", "శాండ్‌విచ్", "శాండ్‌విచ్‌లు"},
                {"orange", "నారింజ", "నారింజలు"}, {"broccoli", "బ్రోకలీ", "బ్రోకలీ"}, {"carrot", "క్యారెట్", "క్యారెట్లు"},
                {"hot dog", "హాట్‌డాగ్", "హాట్‌డాగ్‌లు"}, {"pizza", "పిజ్జా", "పిజ్జాలు"}, {"donut", "డోనట్", "డోనట్లు"}, {"cake", "కేక్", "కేకులు"},
                {"chair", "కుర్చీ", "కుర్చీలు"}, {"couch", "సోఫా", "సోఫాలు"}, {"potted plant", "మొక్క", "మొక్కలు"}, {"bed", "మంచం", "మంచాలు"},
                {"dining table", "టేబుల్", "టేబుళ్లు"}, {"toilet", "టాయిలెట్", "టాయిలెట్లు"}, {"tv", "టీవీ", "టీవీలు"},
                {"laptop", "ల్యాప్‌టాప్", "ల్యాప్‌టాప్‌లు"}, {"mouse", "మౌస్", "మౌస్‌లు"}, {"remote", "రిమోట్", "రిమోట్లు"},
                {"keyboard", "కీబోర్డ్", "కీబోర్డులు"}, {"cell phone", "ఫోన్", "ఫోన్లు"}, {"microwave", "మైక్రోవేవ్", "మైక్రోవేవ్‌లు"},
                {"oven", "ఓవెన్", "ఓవెన్లు"}, {"toaster", "టోస్టర్", "టోస్టర్లు"}, {"sink", "సింక్", "సింక్‌లు"}, {"refrigerator", "ఫ్రిజ్", "ఫ్రిజ్‌లు"},
                {"book", "పుస్తకం", "పుస్తకాలు"}, {"clock", "గడియారం", "గడియారాలు"}, {"vase", "పూలకుండీ", "పూలకుండీలు"},
                {"scissors", "కత్తెర", "కత్తెరలు"}, {"teddy bear", "టెడ్డీ బొమ్మ", "టెడ్డీ బొమ్మలు"}, {"hair drier", "హెయిర్ డ్రయర్", "హెయిర్ డ్రయర్లు"},
                {"toothbrush", "టూత్‌బ్రష్", "టూత్‌బ్రష్‌లు"}};

        /** {singular, plural} in Telugu (the English word itself when it is not in the list). */
        static String[] te(String en) {
            String e = en == null ? "" : en.toLowerCase(Locale.ROOT).trim();
            for (String[] t : TE) if (t[0].equals(e)) return new String[]{t[1], t[2]};
            return new String[]{e, e};
        }

        /** The kind's group, for colours and counting: person, vehicle, animal or thing. */
        static String group(String en) {
            switch (en == null ? "" : en) {
                case "person": return "person";
                case "bicycle": case "car": case "motorcycle": case "bus": case "train": case "truck": case "boat": case "airplane": return "vehicle";
                case "bird": case "cat": case "dog": case "horse": case "sheep": case "cow": case "elephant": case "bear": case "zebra": case "giraffe": return "animal";
                default: return "thing";
            }
        }

        /**
         * The labels a model carries inside it (TFLite metadata keeps them as a small zip at the end of the file); null when
         * it has none, then COCO90 is used.
         */
        static String[] fromModel(File model) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(model)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (!e.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
                    List<String> l = new ArrayList<>();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(z.getInputStream(e), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) l.add(line.trim());
                    }
                    while (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
                    if (!l.isEmpty() && l.get(0).matches("(?i)\\?\\?\\?|background|__background__")) l.remove(0); // (a list with "background" first: class 0 is the next one)
                    if (l.size() >= 80 && l.contains("person")) return l.toArray(new String[0]);
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
