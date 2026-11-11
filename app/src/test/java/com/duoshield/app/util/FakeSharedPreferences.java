package com.duoshield.app.util;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal in-memory SharedPreferences for plain JUnit tests. This project has
 * no Robolectric / instrumented test infrastructure, and SecurePrefs' real
 * implementation needs a working Android Keystore, so classes built on top of
 * it (PinManager, etc.) can't be exercised against the real store in a JVM
 * unit test. This fake, injected via SecurePrefs' test-only override seam,
 * lets tests exercise the real PinManager/SecurePrefs logic instead of
 * re-implementing it.
 *
 * Not thread-safety-hardened — fine for single-threaded test bodies.
 */
class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> data = new HashMap<>();

    private boolean commitSucceeds = true;

    void setCommitSucceeds(boolean succeeds) {
        commitSucceeds = succeeds;
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(data);
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = data.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = data.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = data.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = data.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = data.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = data.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // no-op — not needed by current tests
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // no-op — not needed by current tests
    }

    private class FakeEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clearAll = false;

        @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { pending.put(key, values); return this; }
        @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        @Override public Editor remove(String key) { removals.add(key); return this; }
        @Override public Editor clear() { clearAll = true; return this; }

        @Override
        public boolean commit() {
            // Android SharedPreferences updates its in-memory map before the
            // synchronous disk write completes; a failed commit can therefore
            // return false while the current process already sees the new values.
            apply();
            return commitSucceeds;
        }

        @Override
        public void apply() {
            if (clearAll) data.clear();
            for (String r : removals) data.remove(r);
            data.putAll(pending);
        }
    }
}
