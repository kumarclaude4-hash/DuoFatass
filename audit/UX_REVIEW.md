# DuoShield — UX Review

Scope: user-experience review of the Android client, focused on the
security-adjacent flows where confusing UX directly causes security harm
(key loss, unverified contacts, silent failures). This is separate from the
security audit in the rest of `audit/`.

> Note (superseded): this review originally carried a note that `FLAG_SECURE`
> (screenshot/recording blocking) was **intentionally disabled for testing** and
> should not be treated as a defect. That exemption no longer applies — the
> testing-phase default from commit `9196a75` was a release blocker (S08-H2
> regression) and has been reverted. `app_screenshot_enabled` now defaults to
> `false` (FLAG_SECURE applied) in both `BaseActivity.applyScreenshotSecurity()`
> and `SecurityPrivacySettingsActivity`, so screenshots are blocked on a fresh
> install. Treat any future reappearance of a `true` default as a defect.

---

## Summary

The app is mature and covers the hard flows most E2EE messengers skip: a
dedicated recovery-phrase screen with a save-confirmation gate, a key
fingerprint / safety-number verification screen with QR scanning, duress unlock
codes, and a PIN gate. The gaps are mostly **consistency, polish, and
persistence of trust state** rather than missing features.

Findings are grouped by severity for UX impact (not security severity).

---

## High impact

### UX-1 — Verification result is ephemeral (no persistent "Verified" state)  (FIXED — core; 2 extra badge placements open)
`KeyFingerprintActivity` shows an `AlertDialog` on a successful fingerprint
match and clears the `safety_num_changed_<uid>` flag, but there is **no lasting
"Verified ✓" indicator** anywhere afterwards — not on the fingerprint screen,
not in the contact detail, not in the chat header. The user cannot later tell
whether they ever verified a contact, so the verification has little durable
value and users must re-verify from memory.

- Recommendation: persist a per-contact `verified_at` marker on a successful
  match and surface a badge on the fingerprint screen, the contact detail
  screen, and the chat toolbar. Show a distinct "Verification expired / key
  changed" state when the safety number later changes.
- Files: `KeyFingerprintActivity.java`, `ContactDetailActivity.java`,
  `ChatMediaActivity.java`.
- Status: **fixed.** New `util/VerificationStore.java` persists
  `verified_at_<uid>` plus `verified_fp_<uid>` (the fingerprint actually
  verified) in `duoshield_prefs`, the same file as `safety_num_changed_<uid>`,
  so the "changed" and "verified" records are written and cleared together.
  `KeyFingerprintActivity.renderVerificationState()` renders a verified pill
  (`bg_verified_pill` + `ic_verified_shield`) and is called from `onResume()`,
  which always runs after `onCreate()`, so the badge is correct on first paint.
  The "key changed" invalidation case is covered two ways:
  `DuoShieldSignalStore` calls `VerificationStore.clearVerification()` at both
  identity-key-change sites, and `isStale()` compares the stored fingerprint
  against the current one so a stale badge cannot survive a key rotation.
  The badge is **not** yet surfaced on the contact-detail or chat-toolbar
  surfaces named above — the durable state and the fingerprint-screen badge are
  done; those two extra placements remain open as polish.

### UX-2 — Trust-critical results delivered only as dialogs  (FIXED)
Both the match and the mismatch outcomes are transient dialogs with an "OK"
button. A mismatch ("someone may be intercepting your messages") is the single
most important signal in the app and disappears on dismiss.

- Recommendation: render mismatch as a persistent, dismiss-resistant banner in
  the affected conversation until the user re-verifies or explicitly
  acknowledges, rather than a one-shot dialog.
- Status: **fixed.** The mismatch is a persistent banner
  (`safetyNumberBanner` in `activity_chat_media.xml`) driven by the
  `safety_num_changed_<uid>` flag, and dismissing is now clearly distinct from
  verifying. Previously ✕ and VERIFY both just hid the banner for the session,
  which taught users that ✕ was as good as verifying. ✕ now opens a confirmation
  ("Ignore this warning?") that states plainly that dismissing verifies nothing
  and that the warning will return; "Verify now" holds the positive button slot
  and "Ignore for now" the negative one. Crucially the underlying flag is
  cleared **only** by a successful QR match in `KeyFingerprintActivity` (via
  `clear_safety_num_on_match`), never by either hide path — so the banner is
  genuinely dismiss-resistant and reappears on the next `onResume()` until the
  contact is actually verified.

---

## Medium impact

### UX-3 — Seed-phrase screen is visually inconsistent with the app  (FIXED)
`activity_seed_phrase_display.xml` used a pure-black `#000000` background and
hardcoded hex colors, while every other screen uses the `ds_*` design tokens on
the `#191620` background. This made the most important onboarding screen look
like it belonged to a different app.

- Status: **fixed in this pass** — migrated to `@color/ds_*` tokens.

### UX-4 — Dead UI on the seed-phrase screen  (FIXED)
The "Copy" button was permanently `GONE` in code (clipboard exposure of the
mnemonic is intentionally disallowed), yet still present in the layout.

- Status: **fixed in this pass** — the dead Copy button was removed from the
  layout and the QR button now spans the full width. The security rationale
  comment is preserved in the activity.

### UX-5 — Emoji used as icons  (FIXED)
`🔒` and `⚠` in the seed layout, and `✅` / `❌` inside verification dialog
copy. These render inconsistently across devices and are read awkwardly by
TalkBack.

- Status: **fixed.** All emoji in trust-critical copy are now real vectors:
  - `activity_seed_phrase_display.xml` — `🔒` → `ic_lock`, marked
    `importantForAccessibility="no"` since it is purely decorative beside the
    "Recovery Phrase" heading (a spoken label would just be noise).
  - `activity_chat_media.xml` safety banner — `⚠` → `ic_warning`, also excluded
    from the a11y tree because the adjacent text already carries the warning.
  - `activity_incoming_call.xml` — `🔒` → `ic_lock`, with the icon+label grouped
    so TalkBack announces one "End-to-end encrypted" instead of a lock glyph.
  - `KeyFingerprintActivity` — `✅`/`❌` dropped from dialog title/body; the
    durable verified pill from UX-1 carries the state instead.
  - `ChatMediaActivity.updateOnlineStatus()` — the `🔒` in the chat header's
    "end-to-end encrypted" status is now a tinted `ic_lock` compound drawable.
    It is cleared in the online / last-seen branches, because the method is
    re-run on every presence update and the icon would otherwise stick.
  - `SeedPhraseDisplayActivity.showMnemonicQrDialog()` — the `⚠` leading the
    "Keep this QR private" warning is now a tinted `ic_warning` compound
    drawable and the copy moved to `@string/seed_qr_private_warning`.
  Both compound-drawable sites `mutate()` the drawable before tinting so the
  tint cannot leak to other users of the same cached constant state, and set
  explicit bounds because both vectors are 24dp intrinsic — far too large beside
  12–13sp text.
- Deliberately left as-is (out of scope — not trust-critical verification copy):
  `⚠` in TURN bandwidth cost warnings (`CallActivity`,
  `TurnBandwidthTracker`), backup-staleness labels
  (`BackupStorageSettingsActivity`), and the plaintext-ZIP notice in
  `ChatExportHelper`'s exported README.

### UX-6 — Setup failure recovery
`SeedPhraseDisplayActivity.deriveAndStore()` surfaces a friendly error on key
upload failure, but a kill between identity-key commit and PIN setup relies on
the `pending_pin_setup_<uid>` flag to recover. Worth an explicit "resume setup"
affordance rather than depending solely on routing logic.

---

## Low impact / polish

- UX-7 — Stale comment: the Continue button is described as "outline green" but
  is rendered in accent purple. (Comment corrected in this pass.)
- UX-8 — **(FIXED)** Monospace fingerprint `TextView`s had no
  `contentDescription`, so TalkBack read them character-by-character. Both now
  get one via `verify_a11y_your_fingerprint` / `verify_a11y_partner_fingerprint`,
  fed by a `spokenFingerprint()` helper in `KeyFingerprintActivity` that groups
  the hex for readable speech. Set at both render points (own fingerprint and
  partner fingerprint) so neither path regresses.
- UX-9 — `tvStep` step labels ("Step 1/4 …") are not announced to screen
  readers; consider an `announceForAccessibility` call on each step.
- UX-10 — General: verify touch targets on icon-only buttons (back arrows) meet
  the 48dp minimum; most already do.

---

## Changes made in the first pass

1. `activity_seed_phrase_display.xml` — retheme to `ds_*` design tokens, remove
   dead Copy button (QR now full-width), replace emoji lock with `ic_lock`
   vector, correct the stale "outline green" comment.

## Changes made in the readiness-fix pass

2. **Ship blocker (S08-H2 regression from `9196a75`)** — the
   `app_screenshot_enabled` default was flipped back to `false` in both places
   that read it, `BaseActivity.applyScreenshotSecurity()` and
   `SecurityPrivacySettingsActivity`. The two must stay in lockstep or the
   settings switch renders out of step with the `FLAG_SECURE` state actually
   enforced. Screenshots, screen recording, and the recents thumbnail are once
   again blocked on a fresh install with no user action.
3. **UX-1** — added `util/VerificationStore.java` (durable `verified_at_<uid>` +
   `verified_fp_<uid>`); verified pill on the fingerprint screen; invalidation on
   key change wired into `DuoShieldSignalStore` and `isStale()`.
4. **UX-2** — ✕ dismiss is now a deliberate, clearly-labelled action distinct
   from VERIFY, and never clears the underlying flag.
5. **UX-5** — all trust-critical emoji replaced with tinted vectors; see the
   item above for the full per-file list and the deliberate exclusions.
6. **UX-8** — `contentDescription` on both fingerprint `TextView`s plus a
   `spokenFingerprint()` helper for TalkBack-friendly hex.
7. **Design tokens** — the safety banner's hardcoded `#CC7B3A00` / `#FFFFD54F` /
   `#FFFFFFFF` were replaced with `ds_warning_banner_*` tokens in `colors.xml`,
   and the banner's dismiss button `contentDescription` was corrected (it
   announced "Warning" rather than the dismiss action).

Remaining open, deliberately unchanged: the `SecurePrefs` plaintext fallback
(S08-H5 / S07-M1) needs a **product decision**, not a code fix, and UX-6, UX-9,
UX-10 plus the two extra UX-1 badge placements remain as follow-up polish.

### Verification status of this pass

Source-reviewed and statically checked, **not compiled**: this environment has
no JDK and no Android SDK (`java` absent, `ANDROID_HOME` unset, and installing
one needs root), exactly the constraint recorded in
`security-remediation/sessions/SESSION-S3-18.md`. What was mechanically
verified: brace/paren balance on every touched Java file; XML well-formedness of
every touched layout and `strings.xml`; that every `@drawable`/`@color`/`@string`
referenced by the touched layouts resolves to a real resource; that every
`R.id.*` used by `KeyFingerprintActivity` and the banner code exists in its
layout; that `xmlns:app` is declared in all 31 layouts using `app:tint`; and
that `minifyEnabled true` / `shrinkResources true` / `debuggable false` /
`targetSdk 34` / `versionCode 5` are intact in `app/build.gradle`. A real
`assembleRelease` on a machine with the SDK is still required before shipping.
