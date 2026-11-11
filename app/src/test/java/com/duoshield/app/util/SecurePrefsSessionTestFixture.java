package com.duoshield.app.util;

/**
 * Test-only bridge that exposes the package-private SecurePrefs fake-injection seam
 * to worker tests in the security package.
 */
public final class SecurePrefsSessionTestFixture {

    private static FakeSharedPreferences preferences;

    private SecurePrefsSessionTestFixture() {}

    public static void install(String nonce) {
        preferences = new FakeSharedPreferences();
        preferences.edit()
                .putString("session_migration_subject", "synthetic-test-subject")
                .putString("session_migration_token", nonce)
                .putString("session_credential_token", nonce)
                .commit();
        SecurePrefs.setTestOverridesForUnitTests(null, null, preferences);
    }

    public static boolean hasPendingIntent() {
        return preferences != null
                && preferences.contains("session_migration_subject")
                && preferences.contains("session_migration_token");
    }

    public static boolean hasWarmToken() {
        return preferences != null
                && preferences.contains("session_credential_token");
    }

    public static void clear() {
        SecurePrefs.clearTestOverridesForUnitTests();
        preferences = null;
    }
}
