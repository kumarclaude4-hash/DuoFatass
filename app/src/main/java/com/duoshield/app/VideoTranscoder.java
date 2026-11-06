package com.duoshield.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Downscales an oversized video to 720p H.264 using only platform APIs
 * (MediaCodec / MediaExtractor / MediaMuxer). No Gradle dependencies.
 *
 * <p>Pipeline: MediaExtractor -> video decoder -> SurfaceTexture -> GL blit ->
 * encoder input Surface -> H.264 encoder -> MediaMuxer. The audio track is
 * copied through compressed (no re-encode), which is both faster and avoids a
 * second codec pair; MP4 already carries whatever the source AAC was.
 *
 * <p>Target bitrate is derived from the requested output ceiling and the source
 * duration, so the result lands under the cap for typical content. It is not a
 * guarantee — the caller must re-measure the output and reject if it is still
 * over, which is exactly what {@code ChatMediaActivity} does.
 */
public final class VideoTranscoder {

    private static final String TAG = "VideoTranscoder";

    /** Long-edge target. 1280x720 keeps the file small without looking degraded. */
    private static final int  TARGET_LONG_EDGE = 1280;
    private static final int  TARGET_FRAME_RATE = 30;
    private static final int  IFRAME_INTERVAL   = 2;
    private static final long DEQUEUE_TIMEOUT_US = 10_000;
    private static final String OUT_VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Assumed audio overhead when budgeting video bitrate. */
    private static final int ASSUMED_AUDIO_BPS = 128_000;
    private static final int MIN_VIDEO_BPS     = 300_000;
    private static final int MAX_VIDEO_BPS     = 6_000_000;

    public interface Listener {
        /** @param percent 0..100 */
        void onProgress(int percent);
    }

    /** Cooperative cancellation handle. */
    public static final class Cancel {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    public static final class Result {
        public final boolean success;
        public final File    output;
        public final String  error;
        private Result(boolean s, File o, String e) { success = s; output = o; error = e; }
        static Result ok(File f)       { return new Result(true, f, null); }
        static Result fail(String msg) { return new Result(false, null, msg); }
    }

    private VideoTranscoder() {}

    /** True when the device exposes a hardware-or-software H.264 encoder. */
    public static boolean isSupported() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (OUT_VIDEO_MIME.equalsIgnoreCase(type)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isSupported probe failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
        return false;
    }

    /**
     * Transcodes {@code source} into the app cache, aiming to land under
     * {@code targetBytes}.
     *
     * <p>Blocking — call from a background executor.
     */
    public static Result transcode(Context ctx, Uri source, long targetBytes,
                                   Listener listener, Cancel cancel) {
        if (!isSupported()) {
            return Result.fail("This device has no H.264 encoder available.");
        }

        long durationMs = probeDurationMs(ctx, source);
        if (durationMs <= 0) {
            return Result.fail("Could not read the video duration.");
        }

        File out;
        try {
            File dir = new File(ctx.getCacheDir(), "transcode");
            if (!dir.exists() && !dir.mkdirs()) {
                return Result.fail("Could not create a working directory.");
            }
            out = new File(dir, "tc_" + System.currentTimeMillis() + ".mp4");
        } catch (Exception e) {
            return Result.fail("Could not create the output file.");
        }

        MediaExtractor extractor = null;
        MediaCodec     decoder   = null;
        MediaCodec     encoder   = null;
        MediaMuxer     muxer     = null;
        InputSurface   inputSurface  = null;
        OutputSurface  outputSurface = null;
        boolean muxerStarted = false;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(ctx, source, null);

            int videoTrack = selectTrack(extractor, "video/");
            if (videoTrack < 0) return Result.fail("No video track found in this file.");
            int audioTrack = selectTrack(extractor, "audio/");

            MediaFormat inVideo = extractor.getTrackFormat(videoTrack);
            int srcW = inVideo.getInteger(MediaFormat.KEY_WIDTH);
            int srcH = inVideo.getInteger(MediaFormat.KEY_HEIGHT);

            // Rotation must be applied to the output dimensions, then re-declared
            // on the muxer track so players orient the result correctly.
            int rotation = 0;
            if (inVideo.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = inVideo.getInteger(MediaFormat.KEY_ROTATION);
            } else {
                rotation = probeRotation(ctx, source);
            }

            int[] dims = fitToLongEdge(srcW, srcH, TARGET_LONG_EDGE);
            int outW = dims[0], outH = dims[1];

            int bitrate = budgetVideoBitrate(targetBytes, durationMs, audioTrack >= 0);

            MediaFormat outFormat = MediaFormat.createVideoFormat(OUT_VIDEO_MIME, outW, outH);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAME_RATE);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            outFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

            encoder = MediaCodec.createEncoderByType(OUT_VIDEO_MIME);
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            encoder.start();

            outputSurface = new OutputSurface();
            String inMime = inVideo.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inVideo, outputSurface.getSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) muxer.setOrientationHint(rotation);

            extractor.selectTrack(videoTrack);

            int muxVideoTrack = -1;
            int muxAudioTrack = -1;
            MediaFormat audioFormat = null;
            if (audioTrack >= 0) {
                audioFormat = extractor.getTrackFormat(audioTrack);
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, decodeDone = false, encodeDone = false;
            int lastPercent = -1;

            while (!encodeDone) {
                if (cancel != null && cancel.isCancelled()) {
                    return Result.fail("Cancelled.");
                }

                // ---- feed the decoder ----
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIdx);
                        int sampleSize = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // ---- drain the decoder into the encoder surface ----
                boolean decodedFrame = false;
                if (!decodeDone) {
                    int outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // nothing yet
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignored for surface output
                    } else if (outIdx >= 0) {
                        boolean render = info.size > 0;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decodeDone = true;
                        }
                        decoder.releaseOutputBuffer(outIdx, render);
                        if (render) {
                            outputSurface.awaitNewImage();
                            outputSurface.drawImage();
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                            inputSurface.swapBuffers();
                            decodedFrame = true;

                            if (listener != null && durationMs > 0) {
                                int pct = (int) Math.min(99,
                                        (info.presentationTimeUs / 1000) * 100 / durationMs);
                                if (pct != lastPercent) {
                                    lastPercent = pct;
                                    listener.onProgress(pct);
                                }
                            }
                        }
                        if (decodeDone) encoder.signalEndOfInputStream();
                    }
                }

                // ---- drain the encoder into the muxer ----
                while (true) {
                    int encIdx = encoder.dequeueOutputBuffer(info, 0);
                    if (encIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IllegalStateException("format changed twice");
                        muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) {
                            muxAudioTrack = muxer.addTrack(audioFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (encIdx < 0) break;

                    ByteBuffer encoded = encoder.getOutputBuffer(encIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0; // csd already handed to the muxer via addTrack
                    }
                    if (info.size > 0 && muxerStarted && encoded != null) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(muxVideoTrack, encoded, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encIdx, false);
                    if (eos) { encodeDone = true; break; }
                }

                if (!decodedFrame && inputDone && decodeDone && !encodeDone) {
                    // keep looping to flush the encoder
                }
            }

            // ---- copy the audio track through, uncompressed-untouched ----
            if (audioTrack >= 0 && muxAudioTrack >= 0 && muxerStarted) {
                copyAudio(extractor, audioTrack, muxer, muxAudioTrack, cancel);
            }

            if (listener != null) listener.onProgress(100);
            return Result.ok(out);

        } catch (Exception e) {
            Log.e(TAG, "transcode failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            if (out.exists() && !out.delete()) {
                Log.w(TAG, "could not delete partial output " + com.duoshield.app.util.LogRedact.metadata(out.getAbsolutePath()));
            }
            return Result.fail("Could not convert this video (" + e.getClass().getSimpleName() + ").");
        } finally {
            // Order matters: stop the muxer before releasing the codecs feeding it.
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
            }
            if (outputSurface != null) { try { outputSurface.release(); } catch (Exception ignored) {} }
            if (inputSurface  != null) { try { inputSurface.release();  } catch (Exception ignored) {} }
            if (extractor != null)     { try { extractor.release();     } catch (Exception ignored) {} }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void copyAudio(MediaExtractor extractor, int audioTrack,
                                  MediaMuxer muxer, int muxAudioTrack, Cancel cancel) {
        try {
            extractor.unselectTrack(selectTrack(extractor, "video/"));
        } catch (Exception ignored) {}
        extractor.selectTrack(audioTrack);
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (cancel != null && cancel.isCancelled()) return;
            int size = extractor.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size   = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags  = extractor.getSampleFlags();
            try {
                muxer.writeSampleData(muxAudioTrack, buf, info);
            } catch (Exception e) {
                Log.w(TAG, "audio sample dropped (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
            extractor.advance();
        }
    }

    private static int selectTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Scales to the long edge, preserving aspect ratio, rounded to even numbers. */
    static int[] fitToLongEdge(int w, int h, int longEdge) {
        if (w <= 0 || h <= 0) return new int[]{longEdge, longEdge};
        if (Math.max(w, h) <= longEdge) {
            return new int[]{even(w), even(h)};
        }
        float scale = (float) longEdge / Math.max(w, h);
        return new int[]{even(Math.round(w * scale)), even(Math.round(h * scale))};
    }

    /** H.264 requires even dimensions; many encoders require multiples of 2. */
    private static int even(int v) {
        int r = (v / 2) * 2;
        return Math.max(2, r);
    }

    static int budgetVideoBitrate(long targetBytes, long durationMs, boolean hasAudio) {
        double seconds = Math.max(1.0, durationMs / 1000.0);
        // Aim for 90% of the ceiling to leave room for container overhead.
        double totalBits = targetBytes * 8 * 0.90;
        double videoBits = totalBits - (hasAudio ? ASSUMED_AUDIO_BPS * seconds : 0);
        int bps = (int) Math.round(videoBits / seconds);
        if (bps < MIN_VIDEO_BPS) bps = MIN_VIDEO_BPS;
        if (bps > MAX_VIDEO_BPS) bps = MAX_VIDEO_BPS;
        return bps;
    }

    private static long probeDurationMs(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static int probeRotation(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return rot != null ? Integer.parseInt(rot) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // EGL plumbing: encoder input surface
    // ------------------------------------------------------------------

    private static final class InputSurface {
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private Surface     nativeSurface;

        InputSurface(Surface s) {
            nativeSurface = s;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("no EGL display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new RuntimeException("eglInitialize failed");
            }
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) {
                throw new RuntimeException("eglChooseConfig failed");
            }
            int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    ctxAttribs, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("eglCreateContext failed");
            }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], s,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("eglCreateWindowSurface failed");
            }
            EGL14.eglMakeCurrent(display, surface, surface, context);
        }

        void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(display, surface, nsecs);
        }

        void swapBuffers() {
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, surface);
                EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
            if (nativeSurface != null) { nativeSurface.release(); nativeSurface = null; }
        }
    }

    // ------------------------------------------------------------------
    // EGL plumbing: decoder output surface (SurfaceTexture -> GL blit)
    // ------------------------------------------------------------------

    private static final class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private SurfaceTexture surfaceTexture;
        private Surface        surface;
        private TextureRender  render;
        private final Object   frameSyncObject = new Object();
        private boolean        frameAvailable;

        OutputSurface() {
            render = new TextureRender();
            render.surfaceCreated();
            surfaceTexture = new SurfaceTexture(render.getTextureId());
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
        }

        Surface getSurface() { return surface; }

        void awaitNewImage() {
            final int timeoutMs = 2500;
            synchronized (frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(timeoutMs);
                        if (!frameAvailable) {
                            // Treat as a dropped frame rather than hanging the transcode.
                            Log.w(TAG, "awaitNewImage timed out");
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frameAvailable = false;
            }
            surfaceTexture.updateTexImage();
        }

        void drawImage() { render.drawFrame(surfaceTexture); }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (frameSyncObject) {
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
        }

        void release() {
            if (surface != null) { surface.release(); surface = null; }
            if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
            render = null;
        }
    }

    /** Minimal external-texture -> full-quad renderer. */
    private static final class TextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int VERTEX_STRIDE = 5 * FLOAT_SIZE_BYTES;

        private final float[] triangleVerticesData = {
                -1.0f, -1.0f, 0f, 0.f, 0.f,
                 1.0f, -1.0f, 0f, 1.f, 0.f,
                -1.0f,  1.0f, 0f, 0.f, 1.f,
                 1.0f,  1.0f, 0f, 1.f, 1.f,
        };

        private static final String VERTEX_SHADER =
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        private final FloatBuffer triangleVertices;
        private final float[] stMatrix = new float[16];
        private int program;
        private int textureId = -12345;
        private int uSTMatrixHandle;
        private int aPositionHandle;
        private int aTextureHandle;

        TextureRender() {
            triangleVertices = ByteBuffer
                    .allocateDirect(triangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            triangleVertices.put(triangleVerticesData).position(0);
            android.opengl.Matrix.setIdentityM(stMatrix, 0);
        }

        int getTextureId() { return textureId; }

        void surfaceCreated() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) throw new RuntimeException("failed to create GL program");
            aPositionHandle  = GLES20.glGetAttribLocation(program, "aPosition");
            aTextureHandle   = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uSTMatrixHandle  = GLES20.glGetUniformLocation(program, "uSTMatrix");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        void drawFrame(SurfaceTexture st) {
            st.getTransformMatrix(stMatrix);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            triangleVertices.position(0);
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
                    VERTEX_STRIDE, triangleVertices);
            GLES20.glEnableVertexAttribArray(aPositionHandle);

            triangleVertices.position(3);
            GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false,
                    VERTEX_STRIDE, triangleVertices);
            GLES20.glEnableVertexAttribArray(aTextureHandle);

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glFinish();
        }

        private static int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static int createProgram(String vs, String fs) {
            int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
            if (v == 0) return 0;
            int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
            if (f == 0) return 0;
            int p = GLES20.glCreateProgram();
            if (p == 0) return 0;
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] status = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(p));
                GLES20.glDeleteProgram(p);
                return 0;
            }
            return p;
        }
    }
}
