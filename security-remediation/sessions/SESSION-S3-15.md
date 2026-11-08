# SESSION S3-15 — App Check + client provider wiring

Lane: AND/RULES (verify BLOCKED → S3-15b/S3-19b). Finding: `S10-N1`.

## 0. Start-of-session verification (protocol §3)

Read `BUG_TRACKER.md`'s `S10-N1` row: `Open`/`Carried`, description "Firebase App Check absent."
Falsification check before touching anything:

```
grep -rni "appcheck\|app-check\|app check\|play.integrity\|playintegrity" \
  --include=*.gradle --include=*.java --include=*.kt --include=*.rules --include=*.xml -l .
```

→ **zero matches** (excluding this session's own future edits). Confirms the tracker's `Open`
disposition was accurate — nothing had been done yet. `git status --short` was clean; `git log -3`
showed S3-14's close-out as the last work (commit `03f1fcf` merge, `78b45f7`, `3ef65c2`).

## 1. Scope

`ROUND3_REMEDIATION_PLAN.md`'s S3-15 entry: "Firebase App Check provider wiring in client + rules
enforcement scaffold; enforcement enable = operator runbook; sideloaded-APK caveat = accepted."
Exit condition: "client wiring + rules scaffold source-reviewed; enable step is operator." No
compile/emulator promotion in this environment — `START_HERE.md`'s chain-state line explicitly
says "Do NOT promote it to fixed here."

## 2. Implementation

**`app/build.gradle`** — added two dependency lines inside the existing Firebase block:
- `implementation 'com.google.firebase:firebase-appcheck-playintegrity'` (all build types —
  Play Integrity is the real-device attestor used in release).
- `debugImplementation 'com.google.firebase:firebase-appcheck-debug'` (debug builds only, so the
  debug-token provider — which mints a bypass token instead of a real attestation — can never end
  up in a release APK).

**`app/src/main/java/com/duoshield/app/DuoShieldApp.java`** — in `onCreate()`, before the existing
libsignal native-load block (so App Check is installed before any other Firebase SDK call in this
method, matching the SDK's own "install before first token request" requirement):
- `FirebaseAppCheck.getInstance(FirebaseApp.getInstance())`, then
  `installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())`.
- If `BuildConfig.DEBUG`, additionally installs `DebugAppCheckProviderFactory.getInstance()`.
- Wrapped in try/catch that logs and continues (fail-open) — App Check is an attestation layer,
  not the app's only control; a provider-install failure on an unsupported device/Play-Services
  combo must not crash startup for that user.

**`firestore.rules`** — added an `appCheckVerified()` helper function
(`return request.app != null;`) at the top of the ruleset, with an in-rule comment explaining
exactly why it is not yet referenced by any `allow` clause (see BUG_TRACKER row for the full
reasoning — monitoring-first rollout, existing installs need the new APK first, and the emulator
test harness doesn't mint App Check tokens yet).

No other files touched. No existing guard removed or weakened — this is additive only.

## 3. Verification (protocol §4)

Commands run this session, output pasted (not remembered):

```
$ which java firebase gradle
which: no java in (...)
which: no firebase in (...)
which: no gradle in (...)
```
→ AND (Gradle compile) and RULES (firebase emulator) lanes are both confirmed unavailable in this
environment, matching every prior Round-3 session's finding. Source-review is the only lane that
can execute here, per the plan's own lane table.

```
$ grep -n "appcheck\|AppCheck" app/build.gradle
241:    implementation 'com.google.firebase:firebase-appcheck-playintegrity'
242:    debugImplementation 'com.google.firebase:firebase-appcheck-debug'

$ grep -n "AppCheck\|BuildConfig.DEBUG" app/src/main/java/com/duoshield/app/DuoShieldApp.java
23:import com.google.firebase.appcheck.FirebaseAppCheck;
24:import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
25:import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
... (install calls at :73-81)
```

```
$ python3 -c "s=open('firestore.rules').read(); print('open',s.count('{'),'close',s.count('}'))"
open 79 close 79
```
→ Balanced; no syntax damage to the ruleset from the inserted helper.

```
$ cd server && npm test
ℹ tests 230
ℹ pass 229
ℹ fail 1   (lib/identityVerify.test.js — pre-existing missing-native-module failure,
             documented since S3-05; server code was NOT touched this session, so this
             run only confirms no collateral breakage, not the fix itself)
```

No new automated tests were written this session: the change is a client dependency declaration +
provider install call + an unused rules helper, none of which are exercised by the `server/`
Node test suite (no server code changed) or by `firestore-tests/rules.test.js` (the helper is not
referenced by any rule yet, so there is nothing behavioral to assert — a test asserting
"`appCheckVerified()` returns true when `request.app != null`" would be a tautological wrapper
around the emulator's own `request.app` semantics, not a regression guard for anything this repo
controls, and the emulator to run it doesn't exist here anyway). The meaningful regression coverage
gap — "does enforcement actually block a request without a valid App Check token" — cannot be
written until enforcement is wired into an `allow` clause, which this session deliberately does not
do. That test is the right scope for the session that adds the `&& appCheckVerified()` clauses.

## 4. Disposition

`S10-N1`: **Partial** (was `Open`). Client provider wiring and rules scaffold are source-complete
and additive; verified this session. Not `Fixed`:
- Android compile unverified (no JDK/Gradle/SDK) → promotes via **S3-19b**.
- Rules helper unreferenced by any `allow` clause (deliberate, monitoring-first) — if/when a later
  session wires it in, that change's emulator verification routes via **S3-15b**.
- Enforcement enable (Firebase console) and the sideloaded-APK caveat are operator/accepted,
  per the plan — not code tasks.

## 5. Files changed

- `app/build.gradle`
- `app/src/main/java/com/duoshield/app/DuoShieldApp.java`
- `firestore.rules`

## 6. Commit

`9b58b3a402eb05d95f8704010c6b86eeb1099ec7` — "S3-15: App Check client provider wiring + rules
scaffold (S10-N1)".

## 7. Chain state

`NEXT SESSION` advances to **S3-16** (Android crypto storage — `S08-H5`/`S07-M1`, `S07-L2`,
`S07-L3`; lane AND, verify BLOCKED → S3-19b), per `ROUND3_REMEDIATION_PLAN.md`'s ordering. S3-15b
and S3-19b remain open catch-up gates, unchanged — this session did not run them and does not claim
to have.

---

## Retrospective correction — 2026-11-08 (not a new numbered Round 3 session)

A later change wired `appCheckVerified()` into sensitive Firestore `allow` clauses. That design assumption was incorrect and is superseded by this correction. Firestore Security Rules do not expose App Check token metadata through `request.app`; the field is unsupported and its use causes rule-evaluation failure/denial. A Firebase Firestore SDK maintainer clarified that App Check enforcement is performed by the Firebase service layer before Rules evaluation, with no App Check token visibility inside Rules ([Firebase Android SDK issue #8393](https://github.com/firebase/firebase-android-sdk/issues/8393)).

The former emulator Suite B treated denials from those invalid Rules expressions as proof that App Check was working. Suite A hid the invalid expression by rewriting the helper body. Neither was a valid App Check enforcement test. The corrective work removes `request.app` and the helper from executable Firestore Rules, loads the exact committed rules verbatim in emulator tests, and adds source guards against reintroducing unsupported expressions. Provider packaging/registration remains an Android source-level concern and is retained.

`S10-N1` remains **Partial**. Real App Check enforcement must be enabled at the Firebase service layer by an authorized operator only after reviewing monitoring metrics, compatible-client adoption and rollback readiness. Valid/missing/invalid token behavior must then be verified with synthetic identities in isolated staging and console state read back. No production Console setting or rules deployment is changed by the corrective PR.
