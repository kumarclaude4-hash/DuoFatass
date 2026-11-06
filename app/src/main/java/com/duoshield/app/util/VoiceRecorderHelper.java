package com.duoshield.app.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Records voice notes to .m4a (AAC-LC) using AudioRecord + MediaCodec + MediaMuxer.
 *
 * <p>By capturing raw PCM with AudioRecord instead of delegating to MediaRecorder,
 * we can compute a proper RMS amplitude every 100 ms and apply a three-stage
 * dynamic-range compression (DRC) pipeline before delivering values to the waveform:
 * <ol>
 *   <li><b>Adaptive peak tracking</b> — the running max decays slowly so a quiet
 *       speaker still fills the waveform instead of producing a near-flat line.</li>
 *   <li><b>Square-root gamma compression</b> — 95 % of speech sits at 0.01–0.08 on
 *       a linear scale; sqrt lifts that into the clearly visible 0.10–0.28 range.</li>
 *   <li><b>EMA temporal smoothing</b> (α = 0.25) — removes per-frame jitter so the
 *       waveform looks "alive" rather than twitchy.</li>
 * </ol>
 *
 * <p>Public API is intentionally identical to the old MediaRecorder-based helper:
 * {@link #start}, {@link #stop}, {@link #cancel} and the same three-method
 * {@link RecorderListener}.  {@code stop()} is now non-blocking; {@code onStopped}
 * is dispatched on the main thread once the encoder drains.
 */
public class VoiceRecorderHelper {

    private static final String TAG = "VoiceRecorderHelper";

    // ── Audio configuration ───────────────────────────────────────────────────
    /** 16 kHz is sufficient for speech and keeps file size small. */
    private static final int    SAMPLE_RATE  = 16_000;
    private static final int    CHANNELS     = AudioFormat.CHANNEL_IN_MONO;
    private static final int    PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int    BYTES_SAMPLE = 2;            // 16-bit = 2 bytes

    /** AAC-LC MIME type for MediaCodec / MediaMuxer. */
    private static final String MIME_AAC     = "audio/mp4a-latm";
    private static final int    AAC_PROFILE  = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    private static final int    BIT_RATE     = 64_000;       // 64 kbps — good voice quality

    // Read 20 ms of PCM per loop iteration; accumulate 5 chunks → 100 ms amplitude window.
    private static final int CHUNK_SAMPLES   = SAMPLE_RATE / 50;        //  320 samples
    private static final int CHUNK_BYTES     = CHUNK_SAMPLES * BYTES_SAMPLE; //  640 bytes
    private static final int AMP_WIN_SAMPLES = SAMPLE_RATE / 10;        // 1 600 samples

    // ── Listener ──────────────────────────────────────────────────────────────
    public interface RecorderListener {
        /** Normalised amplitude 0–100, fired on the main thread every ~100 ms. */
        void onAmplitude(int amplitude);
        /** Fired on the main thread when the .m4a file is complete and ready to upload. */
        void onStopped(String filePath, List<Integer> amplitudes);
        /** Fired on the main thread on unrecoverable error. */
        void onError(String msg);
    }

    // ── DRC pipeline state — reset at the start of every session ──────────────
    /**
     * Warm prior of 3 000 prevents the adaptive tracker from over-scaling the
     * very first RMS sample (which is often a transient noise burst).
     */
    private float runningMax = 3_000f;
    private float displayAmp = 0f;

    // ── Objects owned by the record thread ────────────────────────────────────
    private AudioRecord audioRecord;
    private MediaCodec  codec;
    private MediaMuxer  muxer;
    /** Index returned by {@code muxer.addTrack()} — negative until the codec emits format-changed. */
    private int         muxerTrack   = -1;
    private boolean     muxerStarted = false;

    // ── Shared state (main ↔ record thread) ───────────────────────────────────
    private Thread      recordThread;
    /**
     * Signals the record loop to wrap up.  Set by both {@link #stop()} and
     * {@link #cancel()}.
     */
    private volatile boolean stopRequested;
    /**
     * Set only by {@link #cancel()}.  When true the record thread skips the
     * EOS encoder flush, skips {@code onStopped}, and deletes the partial file.
     */
    private volatile boolean cancelRequested;

    private String                      outputPath;
    private final List<Integer>         amplitudes  = new ArrayList<>();
    private WeakReference<RecorderListener> listenerRef;

    /** All callbacks to the caller are posted here (main thread). */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises AudioRecord, MediaCodec, and MediaMuxer, then starts the
     * record thread.  Safe to call from the main thread.
     */
    public void start(Context ctx, RecorderListener cb) {
        // Wait for any previous tear-down to finish (e.g. rapid cancel → start).
        if (recordThread != null && recordThread.isAlive()) {
            try { recordThread.join(1_000); } catch (InterruptedException ignored) {}
        }

        listenerRef     = new WeakReference<>(cb);
        stopRequested   = false;
        cancelRequested = false;
        amplitudes.clear();
        runningMax   = 3_000f;
        displayAmp   = 0f;
        muxerTrack   = -1;
        muxerStarted = false;

        try {
            File out   = new File(ctx.getCacheDir(),
                    "voice_" + System.currentTimeMillis() + ".m4a");
            outputPath = out.getAbsolutePath();

            // ── AudioRecord ──────────────────────────────────────────────────
            // Use MIC source so our DRC operates on unmodified PCM.
            // VOICE_COMMUNICATION applies hardware AGC which would flatten the
            // dynamics our adaptive tracker is designed to handle.
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                cb.onError("RECORD_AUDIO permission not granted");
                return;
            }

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, PCM_ENCODING);
            int recBuf = Math.max(minBuf, CHUNK_BYTES * 16); // ring buffer ≥ 16 chunks
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNELS, PCM_ENCODING, recBuf);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                cb.onError("AudioRecord failed to initialise (check RECORD_AUDIO permission)");
                releaseAll();
                return;
            }

            // ── MediaCodec: AAC-LC encoder ────────────────────────────────────
            MediaFormat fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, /*channels=*/1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 4);

            codec = MediaCodec.createEncoderByType(MIME_AAC);
            codec.configure(fmt, /*surface=*/null, /*crypto=*/null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            // ── MediaMuxer: MPEG-4 (.m4a) container ──────────────────────────
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ── Start capture and record thread ───────────────────────────────
            audioRecord.startRecording();
            recordThread = new Thread(() -> runLoop(cb), "VoiceRecord");
            recordThread.setDaemon(true);
            recordThread.start();

        } catch (Exception e) {
            Log.e(TAG, "start() failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            String msg = e.getMessage() != null ? e.getMessage() : "start failed";
            cb.onError(msg);
            releaseAll();
        }
    }

    /**
     * Signals the record thread to stop capturing, flush the encoder, finalise
     * the .m4a file, and deliver {@link RecorderListener#onStopped} on the main
     * thread.  Returns immediately — do not block on a result here.
     */
    public void stop() {
        stopRequested = true;
        // onStopped dispatched asynchronously from the record thread.
    }

    /**
     * Signals the record thread to abort immediately.  No callback is fired;
     * the partial .m4a file is deleted.  Returns immediately.
     */
    public void cancel() {
        cancelRequested = true;
        stopRequested   = true;
        listenerRef     = null; // suppress any in-flight amplitude callbacks
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Record thread
    // ─────────────────────────────────────────────────────────────────────────

    private void runLoop(RecorderListener cb) {
        byte[]                buf        = new byte[CHUNK_BYTES];
        long                  sumSq      = 0L;    // accumulates sample² for RMS
        int                   accSamples = 0;     // samples accumulated in current window
        long                  totalPcm   = 0L;    // total PCM samples fed to encoder
        MediaCodec.BufferInfo outInfo    = new MediaCodec.BufferInfo();

        try {
            // ── Live capture loop ─────────────────────────────────────────────
            while (!stopRequested) {
                int bytesRead = audioRecord.read(buf, 0, CHUNK_BYTES);
                if (bytesRead <= 0) continue;
                int n = bytesRead / BYTES_SAMPLE; // samples in this chunk

                // ── RMS accumulation (little-endian PCM_16BIT) ────────────────
                for (int i = 0; i + 1 < bytesRead; i += 2) {
                    short s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
                    sumSq += (long) s * s;
                }
                accSamples += n;

                if (accSamples >= AMP_WIN_SAMPLES) {
                    float rms = (float) Math.sqrt((double) sumSq / accSamples);
                    dispatchAmplitude(rms);
                    sumSq      = 0L;
                    accSamples = 0;
                }

                // ── Feed PCM into encoder ─────────────────────────────────────
                long pts = totalPcm * 1_000_000L / SAMPLE_RATE; // µs
                totalPcm += n;
                feedEncoder(buf, bytesRead, pts, /*eos=*/false);

                // ── Non-blocking drain of any encoded output ──────────────────
                drainEncoder(outInfo, /*blockingTimeoutUs=*/0L);
            }

            // ── End-of-stream: only on clean stop (not cancel) ───────────────
            if (!cancelRequested) {
                // Signal EOS to the encoder
                int eosBuf = codec.dequeueInputBuffer(100_000L); // up to 100 ms
                if (eosBuf >= 0) {
                    codec.queueInputBuffer(eosBuf, 0, 0,
                            totalPcm * 1_000_000L / SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }

                // Drain until the encoder emits its own EOS flag (3 s safety cap)
                boolean eosSeen  = false;
                long    deadline = System.currentTimeMillis() + 3_000L;
                while (!eosSeen && System.currentTimeMillis() < deadline) {
                    eosSeen = drainEncoder(outInfo, /*blockingTimeoutUs=*/10_000L);
                }

                if (muxerStarted) {
                    muxer.stop();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "runLoop error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            if (!cancelRequested) {
                final String msg = e.getMessage() != null ? e.getMessage() : "record error";
                mainHandler.post(() -> {
                    RecorderListener l = safeRef();
                    if (l != null) { l.onError(msg); listenerRef = null; }
                });
            }
            return; // fall through to finally, then return without firing onStopped
        } finally {
            releaseAll();
            if (cancelRequested && outputPath != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(outputPath).delete();
            }
        }

        // ── Deliver result on main thread ─────────────────────────────────────
        if (!cancelRequested) {
            final List<Integer> finalAmps = new ArrayList<>(amplitudes);
            final String        finalPath = outputPath;
            mainHandler.post(() -> {
                RecorderListener l = safeRef();
                if (l != null) { l.onStopped(finalPath, finalAmps); listenerRef = null; }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DRC pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the three-stage DRC pipeline to the raw RMS value, stores the
     * result in {@link #amplitudes}, and posts {@code onAmplitude} to the main
     * thread.
     *
     * <p>The pipeline (all operating on a 0–1 normalised signal):
     * <pre>
     *   Stage 1 — adaptive peak:  runningMax = max(runningMax × 0.995, max(rms, 1))
     *   Stage 2 — sqrt gamma:     amp        = sqrt(rms / runningMax)
     *   Stage 3 — EMA smooth:     displayAmp = displayAmp × 0.55 + amp × 0.45
     * </pre>
     */
    private void dispatchAmplitude(float rms) {
        // Stage 1: adaptive peak tracker.
        // Decays 0.5 % per 100 ms tick (~40 dB/min) — slow enough that a quiet
        // speaker fills the waveform, fast enough not to linger on a loud transient.
        runningMax = Math.max(runningMax * 0.995f, Math.max(rms, 1f));

        // Stage 2: square-root gamma.
        // Linear rms/runningMax: 95 % of speech at 0.01–0.08 → near-flat bars.
        // sqrt maps that to 0.10–0.28 → clearly visible, proportional bars.
        float amp = (float) Math.sqrt(rms / runningMax);

        // Stage 3: exponential moving average (α = 0.45).
        // Was 0.25 — too heavy for short notes (1-5s = only 10-50 samples total),
        // it averaged real per-syllable variation into a near-flat line, so every
        // bar normalised to roughly the same height against that clip's own max
        // (compare a DuoShield waveform to WhatsApp's — WhatsApp's has visible
        // peaks/valleys and a taper at pauses; ours didn't). Raising α lets each
        // new sample influence the displayed value more, so real dynamics survive
        // normalization, while still smoothing enough to avoid per-frame jitter.
        displayAmp = displayAmp * 0.55f + amp * 0.45f;

        int normAmp = Math.max(0, Math.min(100, Math.round(displayAmp * 100f)));
        amplitudes.add(normAmp);

        mainHandler.post(() -> {
            RecorderListener l = safeRef();
            if (l != null) l.onAmplitude(normAmp);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Encoder helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes one PCM chunk into the encoder's input queue.  Uses a non-blocking
     * dequeue (timeout 0) — if the encoder is briefly full the chunk is skipped.
     * At 64 kbps AAC encoding 32 kbps PCM input this virtually never happens.
     */
    private void feedEncoder(byte[] data, int length, long ptsUs, boolean eos) {
        int idx = codec.dequeueInputBuffer(0L);
        if (idx < 0) return; // encoder busy; rare at these bitrates
        ByteBuffer inBuf = codec.getInputBuffer(idx);
        if (inBuf == null) return;
        inBuf.clear();
        inBuf.put(data, 0, length);
        codec.queueInputBuffer(idx, 0, length, ptsUs,
                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
    }

    /**
     * Drains all available encoded output from the codec and writes it to the
     * muxer.
     *
     * @param outInfo          reused {@link MediaCodec.BufferInfo} scratch object
     * @param blockingTimeoutUs microseconds to wait per {@code dequeueOutputBuffer}
     *                         call — pass 0 for non-blocking, >0 when flushing EOS
     * @return {@code true} if the {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} flag
     *         was seen, meaning the encoder has fully flushed
     */
    private boolean drainEncoder(MediaCodec.BufferInfo outInfo, long blockingTimeoutUs) {
        boolean eosSeen = false;

        while (true) {
            int outIdx = codec.dequeueOutputBuffer(outInfo, blockingTimeoutUs);

            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break; // nothing ready
            }

            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Fires exactly once — immediately after the first encoded frame is
                // queued.  The output format now contains the codec-specific data
                // (AudioSpecificConfig) needed by the muxer track header.
                if (!muxerStarted) {
                    muxerTrack   = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
                continue;
            }

            if (outIdx < 0) {
                break; // unexpected negative value; stop draining
            }

            // Codec-config buffers carry the AudioSpecificConfig blob; the muxer
            // already received this through the format-change path above, so we
            // just release without writing.
            if ((outInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(outIdx, false);
                continue;
            }

            if (muxerStarted && outInfo.size > 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null) {
                    outBuf.position(outInfo.offset);
                    outBuf.limit(outInfo.offset + outInfo.size);
                    muxer.writeSampleData(muxerTrack, outBuf, outInfo);
                }
            }

            codec.releaseOutputBuffer(outIdx, false);

            if ((outInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                eosSeen = true;
                break;
            }
        }

        return eosSeen;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Resource cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stops and releases AudioRecord, MediaCodec, and MediaMuxer.  Safe to call
     * multiple times — null guards protect against double-release.  Always called
     * from the record thread's {@code finally} block.
     */
    private void releaseAll() {
        if (audioRecord != null) {
            try { audioRecord.stop();    } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (codec != null) {
            try { codec.stop();    } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (muxer != null) {
            // release() can be called without stop() — it handles internal cleanup.
            try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Safely dereferences the weak listener ref. */
    private RecorderListener safeRef() {
        return listenerRef != null ? listenerRef.get() : null;
    }
}
