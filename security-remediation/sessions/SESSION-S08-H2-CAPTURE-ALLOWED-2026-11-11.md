# S08-H2 — All-screen screenshot allowance

**Date:** 2026-11-11  
**Branch:** fix/screenshots-allowed-globally  
**Finding:** S08-H2  
**Disposition:** Partial — screenshot blocking intentionally deferred by explicit product direction

## User-directed product requirement

The user directed that screenshots remain allowed on every possible app screen until the app is finalized. This is an intentional product policy. Do not treat the absence of screenshot blocking as an implementation regression during this period, and do not re-enable FLAG_SECURE without a new explicit product decision.

## Implementation

- BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT is true.
- BaseActivity.applyScreenshotSecurity(Activity) always clears WindowManager.LayoutParams.FLAG_SECURE; it does not inspect SharedPreferences.
- Existing app_screenshot_enabled values are ignored, so an upgrade from a version where the user disabled screenshots cannot silently restore blocking.
- DuoShieldApp installs an Application lifecycle policy that clears the flag on every Activity creation and every resume. This includes direct AppCompatActivity screens, not only BaseActivity descendants.
- The screenshot switch and its PIN-gated mutation code were removed from Security & Privacy. A fixed “Screenshots allowed” status row communicates the policy.
- Comments in GroupChatActivity and ChatMediaActivity were corrected to describe the current policy.
- ScreenshotProtectionLifecycleTest now guards lifecycle coverage, unconditional clearing, ignored legacy preferences, absence of the old toggle and absence of known app-side FLAG_SECURE re-enable calls.
- LockScreenCover remains unchanged. It is a temporary opaque, touch-consuming lock-transition cover, not a screenshot control.

## Platform consequence

Android FLAG_SECURE is the standard window-level mechanism that blocks screenshots, screen recording and recents-task thumbnails together. Clearing it to allow screenshots also makes screen recording and recents thumbnails available. The app does not claim to block those paths while screenshots are permitted.

## Verification

Source updates and regression-test updates are committed to this branch. PR CI is pending and must be inspected before claiming the Android tests pass. No physical-device test was run. Physical-device acceptance remains deferred until the app is declared feature-complete.

## Revisit gate

After feature completion, obtain an explicit product decision on whether screenshot/screen-recording/recents blocking should be introduced. Only then design the final policy and run the relevant API/OEM/device matrix. Until that decision, keep all app screens capture-allowed.
