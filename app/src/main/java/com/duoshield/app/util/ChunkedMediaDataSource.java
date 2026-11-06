package com.duoshield.app.util;

import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Arrays;

/**
 * ExoPlayer {@link DataSource} that serves plaintext out of a chunked (v2) encrypted object
 * without ever downloading the whole thing first.
 *
 * <p>This is what makes a large video start playing while it is still downloading, and seek
 * without re-reading from byte 0. The existing playback path had to stream the entire object to
 * disk and decrypt it end-to-end before the first frame could render, because the legacy
 * whole-blob format has one GCM tag that cannot be verified until the final byte arrives. The
 * chunked format replaces that with fixed 1 MiB chunks, each independently authenticated, so a
 * plaintext offset maps to a chunk index arithmetically and a seek costs one small range GET.
 *
 * <p>Only ever hands the player bytes from a chunk whose GCM tag has already verified —
 * {@link B2StorageHelper#fetchAndDecryptChunk} returns nothing until {@code doFinal} succeeds.
 * A tampered or truncated object therefore breaks playback rather than producing output, which
 * is the correct failure for this app.
 *
 * <p>Each instance is single-threaded from ExoPlayer's loader thread and holds exactly one
 * decrypted chunk (1 MiB) at a time, so playback memory is flat regardless of file size.
 */
@UnstableApi
public final class ChunkedMediaDataSource implements DataSource {

    private static final String TAG = "ChunkedMediaDS";

    /** URI scheme handed to {@link androidx.media3.common.MediaItem} — opaque, never fetched. */
    public static final String SCHEME = "duoshield-chunked";

    private final String b2Path;
    private final String mediaKey;

    private Uri uri;
    private B2StorageHelper.ChunkedHeader header;
    private boolean opened;

    /** Plaintext read position, in bytes from the start of the decrypted stream. */
    private long position;
    /** Plaintext bytes still owed for the current {@link DataSpec}. */
    private long bytesRemaining;

    private int    bufferedChunkIndex = -1;
    private byte[] bufferedChunk;

    public ChunkedMediaDataSource(String b2Path, String mediaKey) {
        this.b2Path   = b2Path;
        this.mediaKey = mediaKey;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        if (header == null) {
            try {
                header = B2StorageHelper.fetchChunkedHeader(b2Path);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to read chunked header for " + b2Path, e);
            }
        }
        if (dataSpec.position > header.plaintextLength) {
            throw new IOException("Seek position " + dataSpec.position
                    + " past end of media (" + header.plaintextLength + " B)");
        }
        position = dataSpec.position;
        long available = header.plaintextLength - position;
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? available
                : Math.min(dataSpec.length, available);
        opened = true;
        Log.d(TAG, "open " + b2Path + " at " + position + " (" + bytesRemaining + " B of "
                + header.plaintextLength + ", " + header.chunkCount + " chunks)");
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!opened) throw new IOException("read() before open()");
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT;

        int chunkIndex = (int) (position / B2StorageHelper.CHUNK_PLAINTEXT_SIZE);
        if (chunkIndex != bufferedChunkIndex) {
            try {
                bufferedChunk = B2StorageHelper.fetchAndDecryptChunk(
                        b2Path, mediaKey, header, chunkIndex);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to read chunk " + chunkIndex + " of " + b2Path, e);
            }
            bufferedChunkIndex = chunkIndex;
        }

        int withinChunk = (int) (position - (long) chunkIndex * B2StorageHelper.CHUNK_PLAINTEXT_SIZE);
        int available   = bufferedChunk.length - withinChunk;
        if (available <= 0) {
            // The header's length field and the chunk's real plaintext length disagree. Treat it
            // as end-of-input rather than looping forever on a chunk that can never satisfy the
            // request — a short read is recoverable for the player, a spin is not.
            Log.w(TAG, "chunk " + chunkIndex + " shorter than header implies for " + com.duoshield.app.util.LogRedact.metadata(b2Path));
            return C.RESULT_END_OF_INPUT;
        }

        int toCopy = (int) Math.min(Math.min(length, available), bytesRemaining);
        System.arraycopy(bufferedChunk, withinChunk, buffer, offset, toCopy);
        position       += toCopy;
        bytesRemaining -= toCopy;
        return toCopy;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        // Zero the decrypted chunk instead of just dropping the reference. This app's premise is
        // that plaintext does not linger, and a 1 MiB video buffer sitting in a reclaimable byte
        // array until the GC happens to reuse it is exactly the kind of residue that premise is
        // supposed to rule out.
        if (bufferedChunk != null) {
            Arrays.fill(bufferedChunk, (byte) 0);
            bufferedChunk = null;
        }
        bufferedChunkIndex = -1;
        opened = false;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // No bandwidth metering: this source has no adaptive variants to choose between, so the
        // only consumer of transfer events would be a bandwidth meter nothing reads.
    }

    /** Builds one {@link ChunkedMediaDataSource} per ExoPlayer load/seek. */
    public static final class Factory implements DataSource.Factory {
        private final String b2Path;
        private final String mediaKey;

        public Factory(String b2Path, String mediaKey) {
            this.b2Path   = b2Path;
            this.mediaKey = mediaKey;
        }

        @Override
        public DataSource createDataSource() {
            return new ChunkedMediaDataSource(b2Path, mediaKey);
        }
    }
}
