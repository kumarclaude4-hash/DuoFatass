package com.duoshield.app.security;

/**
 * Fail-closed policy for deciding whether a fetched group-key document was
 * authored by the locally known group creator.
 *
 * <p>A missing creator or sender is not evidence of authorization. Do not
 * normalize or trim either identifier: the identity values must match exactly.
 */
public final class GroupKeyAuthorizationPolicy {

    private GroupKeyAuthorizationPolicy() {
        // Utility class.
    }

    /**
     * Returns true only when both identifiers are present and exactly equal.
     */
    public static boolean isAuthorizedCreator(String creatorUid, String senderUid) {
        return creatorUid != null
                && !creatorUid.isEmpty()
                && senderUid != null
                && !senderUid.isEmpty()
                && creatorUid.equals(senderUid);
    }
}
