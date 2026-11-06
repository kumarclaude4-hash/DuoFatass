package com.duoshield.app.util;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Pseudonymises identifiers before they reach logcat (SEC-L03).
 *
 * <p>{@code Log.d}/{@code Log.i} are stripped from release builds by R8 (see
 * {@code proguard-rules.pro}), but {@code Log.w}/{@code Log.e} are kept on
 * purpose because they carry real failure diagnostics. Those surviving lines
 * must therefore never contain a raw user id: DuoShield's threat model is
 * metadata resistance, and "X3DH failed for &lt;uid&gt;" in a bug report or
 * {@code adb logcat} dump is a direct statement that this user messaged that
 * user at that time — the exact fact the product promises to withhold.
 *
 * <p>{@link #uid} keeps logs debuggable by staying stable within a process
 * (the same uid always maps to the same tag, so you can still correlate a
 * failure sequence) while being useless outside it: the salt is random per
 * process and never persisted, so a captured log cannot be matched against a
 * known account, and tags from two different sessions cannot be linked.
 */
public final class LogRedact {

    /** Random per-process salt. Never persisted — restarting breaks correlation. */
    private static final byte[] SALT = new byte[16];

    static {
        new SecureRandom().nextBytes(SALT);
    }

    private LogRedact() {}

    /**
     * Pseudonymises non-user metadata such as message IDs, conversation IDs,
     * storage paths and preference-file names before it reaches release logs.
     * The per-process salt makes the returned tag useful only for correlation
     * within one running process.
     */
    public static String metadata(String value) {
        if (value == null || value.isEmpty()) return "m:none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SALT);
            byte[] digest = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("m:");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "m:err";
        }
    }

    /**
     * Returns only an exception type for a release-surviving diagnostic.
     * Exception messages and stack traces can contain UIDs, URLs, storage
     * paths, document IDs or server response bodies and must not be copied
     * into metadata-protected logs.
     */
    public static String error(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /**
     * Returns a short, stable, non-reversible tag for a user id.
     *
     * @return e.g. {@code "u:9f3a1c2b"}, or {@code "u:none"} when null/empty.
     */
    public static String uid(String uid) {
        if (uid == null || uid.isEmpty()) return "u:none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SALT);
            byte[] digest = md.digest(uid.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("u:");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Never let logging break a real code path.
            return "u:err";
        }
    }
}
