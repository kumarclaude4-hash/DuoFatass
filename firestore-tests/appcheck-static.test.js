/**
 * DuoShield — App Check / Firestore Rules boundary guard.
 *
 * Firestore Security Rules cannot inspect App Check token metadata. App Check
 * is enforced by the Firebase service layer when the operator enables it in the
 * Firebase Console. Referencing request.app in Rules causes evaluation errors
 * and denies access, including requests with otherwise valid App Check tokens.
 *
 * This source-only guard prevents the unsupported field (or a wrapper helper)
 * from being reintroduced. Actual App Check token enforcement is not emulated
 * here; it requires Firebase Console configuration and isolated staging.
 */

const { readFileSync } = require('fs');
const { resolve } = require('path');

const RULES_PATH = resolve(__dirname, '../firestore.rules');

function stripRulesComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '');
}

describe('App Check / Firestore Rules boundary', () => {
  const rules = readFileSync(RULES_PATH, 'utf8');
  const executableRules = stripRulesComments(rules);

  test('Firestore Rules do not reference unsupported request.app metadata', () => {
    expect(executableRules).not.toMatch(/\brequest\s*\.\s*app\b/);
  });

  test('Firestore Rules do not define an App Check wrapper around request metadata', () => {
    expect(executableRules).not.toMatch(/\bappCheckVerified\s*\(/);
  });

  test('existing Firebase Auth authorization checks remain in the ruleset', () => {
    expect(executableRules).toMatch(/\brequest\s*\.\s*auth\b/);
  });
});
