package com.duoshield.app.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;

import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.Curve;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Obtains a Firebase custom token from the DuoShield push server and signs
 * in with it.  The resulting Firebase UID equals the caller-supplied
 * {@code userId}, which is deterministically derived from the BIP39 seed
 * phrase — so the UID never changes across sign-outs.
 *
 * <p>All network I/O is performed on the calling thread.  The callback is
 * always delivered on the <strong>main thread</strong>.
 *
 * <h3>Security — proof of possession (audit finding S07-C1)</h3>
 * Minting is a two-step challenge/response:
 * <ol>
 *   <li>{@code POST /mintChallenge} with the userId returns a single-use,
 *       TTL'd 32-byte nonce.</li>
 *   <li>{@code POST /mintToken} sends that nonce plus an XEdDSA signature over
 *       {@link #buildMintTokenChallenge} made with the identity <em>private</em>
 *       key.  The server verifies it against the identity public key on file.</li>
 * </ol>
 *
 * <p>This replaced a broken scheme in which the server accepted
 * {@code sha256(identityPubKeyHex)} as proof of ownership.  That value is
 * derived purely from the identity <em>public</em> key, which is world-readable
 * by design (X3DH needs it), so anyone who could read a victim's public key
 * could mint a token for the victim's account without the seed phrase.  The
 * private-key signature is what actually proves ownership now — which is why
 * this class needs the whole {@link IdentityKeyPair}, not just the public half.
 *
 * <p><strong>The challenge byte layout must stay byte-identical to the
 * server's</strong> ({@code server/lib/identityVerify.js},
 * {@code buildMintTokenChallenge}).  Changing either side alone breaks sign-in;
 * bump the version tag in both together.
 */
public final class AuthTokenHelper {

    private static final String TAG = "AuthTokenHelper";

    /**
     * Connect timeout (ms).  Set to 30 s to handle Render free-tier cold starts
     * (spin-up can take 20–50 s after 15 min of inactivity).
     */
    private static final int CONNECT_TIMEOUT_MS = 30_000;

    /**
     * Read timeout (ms).  Server mints the token quickly once running;
     * 30 s gives ample headroom even on slow connections.
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Domain-separation tag for the /mintToken proof-of-possession signature.
     * Must equal CHALLENGE_CONTEXT in server/lib/identityVerify.js. Bumping this
     * is a breaking protocol change — change both sides in the same commit.
     */
    private static final String MINT_CHALLENGE_CONTEXT = "DuoShield-mintToken-v1";

    /** Expected challenge-nonce length, matching the server's NONCE_BYTES. */
    private static final int NONCE_BYTES = 32;

    public interface Callback {
        void onSuccess(String firebaseUid);
        void onFailure(Exception e);
    }

    /**
     * Derives a Firebase custom token for the given userId and signs in.
     * Must NOT be called on the main thread.
     *
     * @param userId          deterministic account ID (e.g. "ABCDE-FGHIJ-KLM")
     * @param identityKeyPair seed-derived Signal identity key pair. The private
     *                        half signs the server's challenge nonce and never
     *                        leaves the device; only the public half is sent.
     * @param cb              result callback, always invoked on the main thread
     */
    public static void signInWithSeed(String userId,
                                      IdentityKeyPair identityKeyPair,
                                      Callback cb) {
        signInWithSeed(userId, identityKeyPair, null, cb);
    }

    /**
     * Same as {@link #signInWithSeed(String, IdentityKeyPair, Callback)}, but also
     * passes a waitlist request id. Only meaningful for brand-new accounts — the
     * server ignores it entirely for accounts that already have an identity
     * record, so restoring an existing account should pass {@code null}.
     *
     * @param inviteToken approved access-request token from
     *                          {@code RequestAccessActivity}, or null
     */
    public static void signInWithSeed(String userId,
                                      IdentityKeyPair identityKeyPair,
                                      String inviteToken,
                                      Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                if (identityKeyPair == null) {
                    throw new Exception("Identity key pair unavailable — cannot prove account ownership.");
                }
                // Step 1: obtain a single-use challenge nonce (S07-C1).
                Log.i(TAG, "signInWithSeed: requesting challenge nonce…");
                String nonceHex = fetchChallengeNonce(userId);

                // Step 2: prove possession of the identity PRIVATE key by signing
                // the challenge. The byte layout must match the server's
                // buildMintTokenChallenge() exactly.
                byte[] challenge = buildMintTokenChallenge(userId, nonceHex);
                String signatureHex = toHex(
                        Curve.calculateSignature(identityKeyPair.getPrivateKey(), challenge));

                Log.i(TAG, "signInWithSeed: fetching custom token…");
                String customToken = fetchCustomToken(
                        userId,
                        toHex(identityKeyPair.getPublicKey().serialize()),
                        nonceHex,
                        signatureHex,
                        inviteToken);
                Log.i(TAG, "signInWithSeed: token received, signing in with Firebase…");
                String uid = doSignIn(customToken);
                Log.i(TAG, "signInWithSeed: Firebase sign-in SUCCESS");
                main.post(() -> cb.onSuccess(uid));
            } catch (Exception e) {
                Log.e(TAG, "signInWithSeed FAILED" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                main.post(() -> cb.onFailure(e));
            }
        }, "auth-token").start();
    }

    /**
     * Byte string the client signs and the server verifies. Must stay
     * byte-identical to {@code buildMintTokenChallenge} in
     * {@code server/lib/identityVerify.js}:
     *
     * <pre>"DuoShield-mintToken-v1" || 0x00 || utf8(userId) || 0x00 || nonceBytes</pre>
     *
     * <p>The context prefix domain-separates this signature from the other
     * signatures the same identity key makes (e.g. signed-prekey signatures in
     * {@code SignalKeyManager}), and the userId binds it to one account.
     */
    static byte[] buildMintTokenChallenge(String userId, String nonceHex) throws Exception {
        byte[] ctx    = MINT_CHALLENGE_CONTEXT.getBytes(StandardCharsets.UTF_8);
        byte[] uid    = userId.getBytes(StandardCharsets.UTF_8);
        byte[] nonce  = fromHex(nonceHex);
        if (nonce.length != NONCE_BYTES) {
            throw new Exception("Server returned a malformed challenge nonce.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                ctx.length + 1 + uid.length + 1 + nonce.length);
        out.write(ctx);
        out.write(0x00);
        out.write(uid);
        out.write(0x00);
        out.write(nonce);
        return out.toByteArray();
    }

    // ── internals ────────────────────────────────────────────��────────────────

    /** Resolves {@code path} against the configured push-server base URL. */
    private static String endpointFor(String path) throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new Exception("PUSH_SERVER_URL is not configured. "
                    + "Set push.server.url in local.properties or the PUSH_SERVER_URL env var.");
        }
        return serverUrl.endsWith("/") ? serverUrl + path : serverUrl + "/" + path;
    }

    /**
     * Step 1 of the S07-C1 challenge/response: fetch a single-use nonce from
     * {@code POST /mintChallenge}. The nonce is short-lived and is spent by the
     * very next {@code /mintToken} attempt, successful or not, so it must be
     * fetched fresh for every sign-in attempt (never cached or reused).
     */
    private static String fetchChallengeNonce(String userId) throws Exception {
        String endpoint = endpointFor("mintChallenge");
        Log.d(TAG, "fetchChallengeNonce: POST " + endpoint);

        JSONObject body = new JSONObject();
        body.put("userId", userId);
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "fetchChallengeNonce: HTTP " + code);
            if (code == 429) throw new Exception(
                    "Too many sign-in attempts. Please wait a moment and try again.");
            if (code != 200) throw new Exception("Auth server returned HTTP " + code
                    + " while requesting a sign-in challenge.");

            try (InputStream is = conn.getInputStream()) {
                JSONObject resp = new JSONObject(readFully(is));
                String nonce = resp.optString("nonce", null);
                if (nonce == null || nonce.length() != NONCE_BYTES * 2) {
                    throw new Exception("Server returned a malformed challenge nonce.");
                }
                return nonce;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String fetchCustomToken(String userId,
                                           String pubKeyHex,
                                           String nonceHex,
                                           String signatureHex,
                                           String inviteToken) throws Exception {
        String endpoint = endpointFor("mintToken");

        Log.d(TAG, "fetchCustomToken: POST " + endpoint);

        JSONObject body = new JSONObject();
        body.put("userId",            userId);
        body.put("identityPubKeyHex", pubKeyHex);
        body.put("nonce",             nonceHex);
        body.put("signatureHex",      signatureHex);
        if (inviteToken != null && !inviteToken.isEmpty()) {
            body.put("inviteToken", inviteToken);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "fetchCustomToken: HTTP " + code);

            if (code == 429) throw new Exception(
                    "Too many sign-in attempts. Please wait a moment and try again.");
            if (code == 403) {
                String serverMsg = readErrorBody(conn);
                // The server returns a specific reason string; new-account creation
                // gates on waitlist approval, restores gate on key mismatch — surface
                // whichever actually happened instead of assuming one or the other.
                if (serverMsg != null && serverMsg.toLowerCase(java.util.Locale.US).contains("access request")) {
                    throw new Exception(serverMsg);
                }
                throw new Exception(
                        "Recovery phrase does not match this Account ID. "
                        + "Please check both and try again.");
            }
            if (code != 200) throw new Exception("Auth server returned HTTP " + code);

            // Use manual byte-by-byte read for full API-level compatibility.
            // InputStream.readAllBytes() is Java 11 — while desugar_jdk_libs covers
            // it on Android, an explicit loop removes any desugaring dependency.
            try (InputStream is = conn.getInputStream()) {
                String json = readFully(is);
                Log.d(TAG, "fetchCustomToken: response received (" + json.length() + " chars)");
                JSONObject resp  = new JSONObject(json);
                String    token  = resp.getString("token");
                if (token == null || token.isEmpty())
                    throw new Exception("Server returned empty token.");
                return token;
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Best-effort read of the plain-text error body; returns null if unavailable. */
    private static String readErrorBody(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream()) {
            return es != null ? readFully(es) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String doSignIn(String customToken) throws Exception {
        final Object      lock      = new Object();
        final String[]    uidHolder = {null};
        final Exception[] errHolder = {null};

        FirebaseAuth.getInstance().signInWithCustomToken(customToken)
                .addOnSuccessListener(result -> {
                    synchronized (lock) {
                        uidHolder[0] = result.getUser() != null
                                ? result.getUser().getUid() : null;
                        lock.notifyAll();
                    }
                })
                .addOnFailureListener(e -> {
                    synchronized (lock) {
                        errHolder[0] = e;
                        lock.notifyAll();
                    }
                });

        synchronized (lock) {
            if (uidHolder[0] == null && errHolder[0] == null) {
                lock.wait(30_000);
            }
        }
        if (errHolder[0] != null) throw errHolder[0];
        if (uidHolder[0] == null) throw new Exception("Firebase sign-in timed out.");
        return uidHolder[0];
    }

    /**
     * Reads all bytes from {@code is} into a String.
     * Uses a manual buffer loop for full API-level compatibility (API 26+).
     */
    private static String readFully(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    /**
     * Decodes an even-length hex string. Strict: rejects odd lengths and any
     * non-hex character rather than silently decoding a prefix, so a corrupted
     * or hostile challenge value fails sign-in instead of producing a signature
     * over attacker-chosen bytes.
     */
    private static byte[] fromHex(String hex) throws Exception {
        if (hex == null || hex.length() % 2 != 0) {
            throw new Exception("Malformed hex value from server.");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new Exception("Malformed hex value from server.");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private AuthTokenHelper() {}
}
