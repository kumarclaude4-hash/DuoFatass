package com.duoshield.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wire-contract tests for the server-side typed link-preview response (S04-I3). */
public class LinkPreviewStatusTest {

    @Test
    public void mapsEveryDocumentedWireStatus() {
        assertEquals(LinkPreviewFetcher.Status.OK,
                LinkPreviewFetcher.Status.fromWire("ok", true));
        assertEquals(LinkPreviewFetcher.Status.NO_METADATA,
                LinkPreviewFetcher.Status.fromWire("no_metadata", false));
        assertEquals(LinkPreviewFetcher.Status.UNSUPPORTED_CONTENT,
                LinkPreviewFetcher.Status.fromWire("unsupported_content", false));
        assertEquals(LinkPreviewFetcher.Status.BLOCKED,
                LinkPreviewFetcher.Status.fromWire("blocked", false));
        assertEquals(LinkPreviewFetcher.Status.TIMEOUT,
                LinkPreviewFetcher.Status.fromWire("timeout", false));
        assertEquals(LinkPreviewFetcher.Status.UPSTREAM_ERROR,
                LinkPreviewFetcher.Status.fromWire("upstream_error", false));
        assertEquals(LinkPreviewFetcher.Status.UNAVAILABLE,
                LinkPreviewFetcher.Status.fromWire("unavailable", false));
    }

    @Test
    public void unknownOrMalformedWireValuesFailClosed() {
        assertEquals(LinkPreviewFetcher.Status.UNAVAILABLE,
                LinkPreviewFetcher.Status.fromWire("internal-hostname-and-stack", true));
        assertEquals(LinkPreviewFetcher.Status.UNAVAILABLE,
                LinkPreviewFetcher.Status.fromWire(" ", false));
    }

    @Test
    public void absentStatusRemainsCompatibleWithOlderServerResponses() {
        assertEquals(LinkPreviewFetcher.Status.OK,
                LinkPreviewFetcher.Status.fromWire(null, true));
        assertEquals(LinkPreviewFetcher.Status.NO_METADATA,
                LinkPreviewFetcher.Status.fromWire(null, false));
    }

    @Test
    public void onlySafeMetadataOutcomesAreRenderedAsCards() {
        assertTrue(LinkPreviewFetcher.Status.OK.isDisplayable());
        assertTrue(LinkPreviewFetcher.Status.NO_METADATA.isDisplayable());
        assertTrue(LinkPreviewFetcher.Status.UNSUPPORTED_CONTENT.isDisplayable());

        assertFalse(LinkPreviewFetcher.Status.BLOCKED.isDisplayable());
        assertFalse(LinkPreviewFetcher.Status.TIMEOUT.isDisplayable());
        assertFalse(LinkPreviewFetcher.Status.UPSTREAM_ERROR.isDisplayable());
        assertFalse(LinkPreviewFetcher.Status.UNAVAILABLE.isDisplayable());
    }

    @Test
    public void PreviewUsesExplicitStatusAndNeverTreatsNullAsSuccess() {
        LinkPreviewFetcher.Preview blocked = new LinkPreviewFetcher.Preview(
                "https://example.com", null, "example.com", null,
                LinkPreviewFetcher.Status.BLOCKED);
        LinkPreviewFetcher.Preview success = new LinkPreviewFetcher.Preview(
                "https://example.com", "Example", "example.com", null,
                LinkPreviewFetcher.Status.OK);

        assertEquals(LinkPreviewFetcher.Status.BLOCKED, blocked.status);
        assertFalse(blocked.isDisplayable());
        assertEquals(LinkPreviewFetcher.Status.OK, success.status);
        assertTrue(success.isDisplayable());
    }
}
