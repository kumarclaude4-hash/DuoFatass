/**
 * DuoShield — App Check Android provider wiring guards.
 *
 * Firebase App Check enforcement is a Firebase service-layer control, not a
 * Firestore Security Rules expression. The Firestore emulator cannot model the
 * Console enforcement toggle or mint real App Check tokens. These source checks
 * protect the Android provider packaging and installation order only.
 */

const { readFileSync } = require('fs');
const { resolve } = require('path');

const GRADLE_PATH = resolve(__dirname, '../app/build.gradle');
const APP_PATH = resolve(
  __dirname,
  '../app/src/main/java/com/duoshield/app/DuoShieldApp.java'
);

describe('App Check Android provider wiring', () => {
  const gradle = readFileSync(GRADLE_PATH, 'utf8');
  const app = readFileSync(APP_PATH, 'utf8');

  test('Play Integrity provider is included in app builds', () => {
    expect(gradle).toMatch(
      /^\s*implementation\s+['"]com\.google\.firebase:firebase-appcheck-playintegrity['"]/m
    );
  });

  test('debug App Check provider remains debug-only', () => {
    expect(gradle).toMatch(
      /^\s*debugImplementation\s+['"]com\.google\.firebase:firebase-appcheck-debug['"]/m
    );
    expect(gradle).not.toMatch(
      /^\s*implementation\s+['"]com\.google\.firebase:firebase-appcheck-debug['"]/m
    );
  });

  test('Play Integrity provider is installed before Firestore is initialized', () => {
    const installIndex = app.indexOf('installAppCheckProviderFactory(');
    const integrityIndex = app.indexOf('PlayIntegrityAppCheckProviderFactory.getInstance()');
    const firestoreIndex = app.indexOf('FirebaseFirestore.getInstance(');

    expect(installIndex).toBeGreaterThanOrEqual(0);
    expect(integrityIndex).toBeGreaterThanOrEqual(0);
    expect(firestoreIndex).toBeGreaterThanOrEqual(0);
    expect(installIndex).toBeLessThan(firestoreIndex);
    expect(integrityIndex).toBeLessThan(firestoreIndex);
  });

  test('debug provider installation is guarded by BuildConfig.DEBUG', () => {
    expect(app).toMatch(
      /if\s*\(BuildConfig\.DEBUG\)\s*\{\s*AppCheckDebugProvider\.install\(appCheck\);\s*\}/
    );
  });
});
