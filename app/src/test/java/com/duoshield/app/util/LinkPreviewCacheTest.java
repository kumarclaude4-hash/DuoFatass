package com.duoshield.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression tests for successful and negative link-preview cache entries. */
public class LinkPreviewCacheTest {

    @Test
    public void distinguishesCacheMissFromRememberedFailure() {
        LinkPreviewCache<String> cache = new LinkPreviewCache<>(4);

        LinkPreviewCache.Lookup<String> miss = cache.get("https://example.com");
        assertFalse(miss.found);
        assertNull(miss.value);

        cache.put("https://example.com", null);
        LinkPreviewCache.Lookup<String> negative = cache.get("https://example.com");
        assertTrue(negative.found);
        assertNull(negative.value);
    }

    @Test
    public void cachesSuccessfulValues() {
        LinkPreviewCache<String> cache = new LinkPreviewCache<>(4);
        cache.put("https://example.com", "Example");

        LinkPreviewCache.Lookup<String> hit = cache.get("https://example.com");
        assertTrue(hit.found);
        assertEquals("Example", hit.value);
    }

    @Test
    public void evictsLeastRecentlyUsedEntryAtCapacity() {
        LinkPreviewCache<String> cache = new LinkPreviewCache<>(2);
        cache.put("first", "one");
        cache.put("second", "two");

        // Accessing first makes second the least-recently-used entry.
        assertTrue(cache.get("first").found);
        cache.put("third", "three");

        assertTrue(cache.get("first").found);
        assertFalse(cache.get("second").found);
        assertTrue(cache.get("third").found);
        assertEquals(2, cache.size());
    }

    @Test
    public void clearRemovesPositiveAndNegativeEntries() {
        LinkPreviewCache<String> cache = new LinkPreviewCache<>(2);
        cache.put("success", "preview");
        cache.put("failure", null);
        cache.clear();

        assertFalse(cache.get("success").found);
        assertFalse(cache.get("failure").found);
        assertEquals(0, cache.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveCapacity() {
        new LinkPreviewCache<>(0);
    }
}
