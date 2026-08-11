# FINAL SIGN-OFF

> Status: **PENDING** — this document is completed and signed only after Round 3
> (the final planned round) exits. It is the hard stop of the program.

Sign-off criteria — status as assessed by the FINAL VERIFICATION session, 2026-08-11:

- [x] Every audit finding has exactly one final disposition — **116/116, 0 open, 0 partial.**
- [x] Every Critical finding is fixed or accepted-with-justification — **4/4 fixed in code.**
- [x] Every High finding is fixed or accepted-with-justification — **27 High + 3 Med→High, all fixed.**
- [x] Every Medium/Low/Informational finding is fixed, accepted, or deferred-with-justification.
- [x] FINAL_SECURITY_REPORT.md written — see [`FINAL_SECURITY_REPORT.md`](./FINAL_SECURITY_REPORT.md).
- [x] **Every fix has source + test evidence.** Source review: yes, all. Executed tests, all three
      layers, re-run 2026-08-11 in a toolchain-provisioned environment:
      **server 153/153 pass** (`cd server && npm test`);
      **Firestore rules 155/155 pass** (`firebase emulators:exec --only firestore` + Jest, emulator
      15.26.0 on Corretto 21 — the `S03-H1` cases have now actually executed);
      **Android compiles** — `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` (Gradle 8.7,
      Corretto 17, SDK platform 34 / build-tools 34.0.0), producing `app-arm64-v8a-debug.apk` and
      `app-armeabi-v7a-debug.apk`. Scope caveat, stated rather than blurred: that is the **debug**
      variant built against `google-services.json.template` placeholder config. It proves every Java
      edit in this program compiles; it is **not** a signed release build and not a runtime test.
- [ ] Every trust boundary in TRUST_BOUNDARIES.md is revalidated (verified / accepted).
- [ ] No unreviewed trust-boundary change remains.
- [ ] **Operator actions complete.** 8 outstanding — `FINAL_SECURITY_REPORT.md` §3. Blocking ones:
      the **leaked GCP service-account key is still un-revoked** (it shipped inside published APKs, so
      it must be assumed compromised), remaining credential rotations, and `SC-12` branch protection
      (re-checked 2026-08-11: still `404 Branch not protected`).
- [ ] **Server and APK released together.** `/mintToken` hard-requires `nonce`+`signatureHex`; a
      server-only deploy breaks old clients, and making those fields optional to accommodate them
      would reintroduce the `S07-C1` account takeover.

## Why this is still PENDING

The **code** remediation is complete and is now test-verified on all three layers (server unit tests,
Firestore rules against the emulator, Android compilation). The **deployment** is still not
remediated: a live compromised admin credential is not a paperwork item, and no operator action in
`FINAL_SECURITY_REPORT.md` §3 has been closed — `main` was re-checked again this session and is still
`404 Branch not protected`.

Per `SESSION_PROTOCOL.md` §9 and this program's history of three false-progress incidents, signing
this document now would be the fourth. Sign it only when the unchecked boxes above are true — and
verify them from source and command output, not from this file.

Final tallies, per-finding dispositions, and signatures are written here at program close.
