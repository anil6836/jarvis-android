package com.anil.jarvis;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * A short MP4 of the hologram turning (for "🎬 వీడియో", to send on WhatsApp): the frames the model draws are put on an
 * H.264 encoder's surface and written to a file. Frames come upside down from OpenGL; they are turned right here.
 * One thread (the recorder's own) calls frame() and finish().
 */
final class HoloVideo {
    private final MediaCodec codec;
    private final MediaMuxer mux;
    private final Surface input;
    private final int w, h;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int track = -1;
    private boolean started;
    final File file;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    HoloVideo(File out, int srcW, int srcH) throws Exception {
        file = out;
        float s = Math.min(1f, 720f / Math.max(1, Math.min(srcW, srcH)));
        w = Math.max(16, Math.round(srcW * s / 16f) * 16);
        h = Math.max(16, Math.round(srcH * s / 16f) * 16);
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = codec.createInputSurface();
        codec.start();
        mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** One frame (as read from OpenGL: upside down). */
    void frame(Bitmap b) {
        Canvas c = input.lockHardwareCanvas();
        try {
            Matrix m = new Matrix();
            m.setScale(w / (float) b.getWidth(), -h / (float) b.getHeight());
            m.postTranslate(0, h);
            c.drawColor(0xFF02070F);
            c.drawBitmap(b, m, paint);
        } finally {
            input.unlockCanvasAndPost(c);
        }
        drain(false);
    }

    void finish() {
        try {
            codec.signalEndOfInputStream();
            drain(true);
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
            input.release();
            try { if (started) mux.stop(); } catch (Exception ignored) {}
            mux.release();
        }
    }

    private void drain(boolean end) {
        long until = System.currentTimeMillis() + (end ? 3000 : 0);
        while (true) {
            int i = codec.dequeueOutputBuffer(info, end ? 10_000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || System.currentTimeMillis() > until) return;
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(codec.getOutputFormat());
                mux.start();
                started = true;
            } else if (i >= 0) {
                ByteBuffer out = codec.getOutputBuffer(i);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && started && out != null) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    mux.writeSampleData(track, out, info);
                }
                codec.releaseOutputBuffer(i, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }
}
