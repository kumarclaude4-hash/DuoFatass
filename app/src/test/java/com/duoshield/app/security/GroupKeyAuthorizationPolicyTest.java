package com.duoshield.app.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression tests for the fail-closed S07-L1 group-key creator check. */
public class GroupKeyAuthorizationPolicyTest {

    @Test
    public void exactCreatorMatchIsAuthorized() {
        assertTrue(GroupKeyAuthorizationPolicy.isAuthorizedCreator("creator-uid", "creator-uid"));
    }

    @Test
    public void missingCreatorIsRejected() {
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator(null, "creator-uid"));
    }

    @Test
    public void missingSenderIsRejected() {
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("creator-uid", null));
    }

    @Test
    public void emptyIdentifiersAreRejected() {
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("", ""));
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("creator-uid", ""));
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("", "creator-uid"));
    }

    @Test
    public void differentIdentifiersAreRejected() {
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("creator-uid", "member-uid"));
    }

    @Test
    public void comparisonIsExactAndDoesNotNormalizeIdentifiers() {
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("Creator-uid", "creator-uid"));
        assertFalse(GroupKeyAuthorizationPolicy.isAuthorizedCreator("creator-uid ", "creator-uid"));
    }
}
