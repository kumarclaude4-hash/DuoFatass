package com.duoshield.app.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small bounded LRU cache that can also remember a failed lookup.
 *
 * <p>{@code null} values are represented internally by {@link Optional#empty()},
 * so callers can distinguish a cache miss from a cached negative result without
 * inserting null into a map. All operations are synchronized because the cache
 * is shared by link-preview fetch threads and the UI thread.
 */
final class LinkPreviewCache<T> {

    static final class Lookup<T> {
        final boolean found;
        final T value;

        private Lookup(boolean found, T value) {
            this.found = found;
            this.value = value;
        }
    }

    private final int maxEntries;
    private final LinkedHashMap<String, Optional<T>> entries =
            new LinkedHashMap<>(16, 0.75f, true);

    LinkPreviewCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    synchronized Lookup<T> get(String key) {
        Optional<T> cached = entries.get(key);
        return cached == null
                ? new Lookup<>(false, null)
                : new Lookup<>(true, cached.orElse(null));
    }

    synchronized void put(String key, T value) {
        entries.put(key, Optional.ofNullable(value));
        while (entries.size() > maxEntries) {
            String leastRecentlyUsed = entries.keySet().iterator().next();
            entries.remove(leastRecentlyUsed);
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }
}
