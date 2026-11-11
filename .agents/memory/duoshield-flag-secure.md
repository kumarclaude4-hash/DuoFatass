---
name: DuoShield screenshot capture policy
description: Screenshots are allowed on every app screen until app finalization; do not re-enable FLAG_SECURE without explicit product direction
---

## Current product policy — 2026-11-11

Screenshots must remain allowed on every DuoShield app screen until the app is finalized. This is a user-directed product decision, not a configurable setting.

## Implementation contract

- BaseActivity.applyScreenshotSecurity(Activity) always calls clearFlags(FLAG_SECURE) and must never add that flag.
- DuoShieldApp applies the capture-allowed policy from Application Activity lifecycle callbacks on both Activity creation and resume. This covers BaseActivity subclasses and direct AppCompatActivity screens.
- The former app_screenshot_enabled preference is ignored, including stale values retained by existing installations.
- The Security & Privacy screen displays a fixed “Screenshots allowed” status. Do not restore the toggle unless the product owner explicitly changes the policy.
- New Activities are covered automatically by the Application lifecycle callback. Do not add per-screen FLAG_SECURE calls.

## Android platform consequence

Android's FLAG_SECURE suppresses screenshots, screen recording and recents-task thumbnails together. Clearing it to allow screenshots also allows screen recording and recents thumbnails. Do not claim that those other capture paths remain blocked.

LockScreenCover is an opaque, touch-consuming transition overlay for the lock flow. It is not a screenshot-protection mechanism and must not be described as one.

## Finding / QA handling

S08-H2 remains Partial and intentionally deferred while the user-directed capture policy is in effect. Revisit capture-blocking only after the app is finalized and the owner explicitly chooses a new policy. Do not promote the finding to Fixed merely because CI passes; do not run physical-device acceptance before the app is declared feature-complete.
