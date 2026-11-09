# S10-N2 — Same-owner method-reference callback parameter taint

**Date:** 2026-11-09  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Branch:** `security/s10-n2-method-reference-callback-taint-2026-11-10`  
**Finding:** S10-N2 — Sensitive values in release logcat  
**Status:** Partial pending exact-head CI/Security and broader residual acceptance

## Source review

The existing bounded AST analyzer treated lambda callback parameters as opaque/tainted within
`visitLambdaExpression`. It did not connect app-owned method-reference registrations such as
`this::callbackLog` with the target method's parameters. A callback API could therefore receive
a method reference and invoke the target with app data that the method-local pass had not marked
as callback-supplied.

## Implementation

- Added the JDK compiler-tree `MemberReferenceTree` import.
- Added a method-reference target registry to each unambiguous app-owned class summary.
- The index scans each direct class member's own AST for same-owner method references and records
  only `this::method`, `Owner::method`, and `Owner.this::method` forms. Nested classes are
  skipped in the parent pass and indexed under their own class summary.
- The method-local pass checks whether the analyzed method is a registered callback target. Its
  parameters are marked tainted/opaque, except for the already narrow
  `VoiceMessagePlayer` `what`/`extra` integer error-code exception.
- Did not add method-reference parameters to the separate throwable-class-name projection set;
  this avoids broadening the safe Throwable diagnostic exception beyond explicitly modeled
  lambda callback parameters.
- Added a synthetic AST fixture: `CallbackApi.register(this::callbackLog)` registers a method
  whose ordinary parameter `callbackArg` reaches `Log.w`. The expected AST self-test count is
  increased by exactly one sink and one violation.

## Scope and limitations

This is a bounded source-AST improvement, not symbol-resolved whole-program or bytecode analysis.
It recognizes only same-owner forms listed above. Other receiver/target shapes, external/library
callback contracts, method-reference adaptation/overload resolution, dynamic proxies, native code,
arbitrary generic/container semantics and unmodeled dispatch remain residual. The analyzer must
not be described as proving all Java callback paths safe.

Feature-complete physical release/minified APK logcat acceptance remains deferred until the owner
declares the application feature-complete. No physical-device testing or production deployment is
claimed in this session.

## Documentation updated in this PR

- `BUG_TRACKER.md`
- `security-remediation/START_HERE.md`
- `security-remediation/NEXT_WORK_REPORT_2026-11-08.md`
- `security-remediation/FINAL_SIGNOFF.md`
- `security-remediation/INDEPENDENT_REVERIFICATION_2026-11-08.md`
- `security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md`
- `security-remediation/SESSION_INDEX.md`
- This session record.

## Verification

The AST self-test and full production-source scan run in the exact-head CI workflow. CI,
Security and Firestore workflow results are pending at preparation time. Do not merge until the
exact-head required workflows finish successfully. S10-N2 remains Partial; do not promote based
on the synthetic regression alone.
