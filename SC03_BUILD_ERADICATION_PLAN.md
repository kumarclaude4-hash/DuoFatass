# SC-03 / S3-19b — Gradle Dependency Verification: Findings Report & Eradication Plan

**Current disposition (2026-11-12): FIXED — this historical plan is superseded.**
**Current evidence:** `gradle/verification-metadata.xml` has `verify-metadata=true`, 493 component records and includes the platform-specific `com.android.tools.build:aapt2:8.6.0-11315950` Linux artifact checksum. Current main commit `4341ecf84d8579b651d6853c480f0c1a6129a324` passed GitHub CI [42983530186](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42983530186) 10/10, including lint, unit tests, debug APK, Espresso, API 35 predictive-back and minified R8; Security [42983530183](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42983530183) passed 3/3. The SC-03 code/CI dependency-verification gap is closed. Signing secrets and signed release publication are separate gates; no current signed release is claimed.

The original investigation below is preserved for audit history only. Do not execute its old Phase A–D steps or repeat the missing-aapt2 recovery work.


This file exists at the repo root so it survives sandbox recycling (unlike `/tmp` logs from prior sessions, which did not).

---

## 1. Historical background (state before SC-03 closure)

`SC-03` (tracked in `BUG_TRACKER.md` line 84, status **Partial**, session `S3-03`) is the finding that the app shipped with **no Gradle dependency verification at all** — no `gradle/verification-metadata.xml` existed, so a compromised or swapped dependency (malicious `.jar`/`.aar`, or a JitPack-served artifact built from a mutable Git ref) could enter the build undetected.

The fix scaffolded `gradle/verification-metadata.xml` with `<verify-metadata>true</verify-metadata>` but — critically — the component hash list was populated from a `--dry-run` metadata generation, which has a structural blind spot described in §3 below. `S3-19b` (per `security-remediation/ROUND3_REMEDIATION_PLAN.md`, lines 216–220) is the verification gate that requires an actual `./gradlew :app:assembleDebug` + tests run to promote SC-03 from **Partial** to **Fixed**. That gate has not passed.

---

## 2. Historical repo state (captured before SC-03 closure)

**Citation:** `git log --oneline -10`, `git status`, direct inspection of `gradle/verification-metadata.xml`

```
24162c7 Merge pull request #104 from kumarclaude4-hash/gradle-dependency-verification
b710d87 feat: add Gradle dependency verification metadata file
```

- Branch is clean, no local diff.
- `gradle/verification-metadata.xml`: **435 components**, `<verify-metadata>true</verify-metadata>` confirmed present (line 71).
- Every one of the 435 components has a `.pom` or `.module` descriptor hash — the file is internally consistent.
- **Missing:** the native `com.android.tools.build:aapt2:8.6.0-11315950` artifact (Linux classifier build of Android's resource-packaging binary). Only the unrelated `aapt2-proto` component (a Java `.jar`, different artifact entirely) is present, at line 981.

---

## 3. Root Cause — why the missing hash exists and why it wasn't caught

**Citation:** `.github/workflows/ci.yml`, `regen-verification-metadata` job (lines 223–279), job comment lines 216–222

The job that generated the current metadata runs:

```
./gradlew --write-verification-metadata sha256 --dry-run \
  :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  --no-daemon --continue || true
```

`--dry-run` is documented Gradle behavior that **resolves the dependency graph but never executes any task**. Most dependencies (Java/Kotlin libraries, AARs) are resolved at configuration time, so dry-run mode captures them correctly — which is why 435 components got real hashes.

The native `aapt2` binary is different: the Android Gradle Plugin resolves the *platform-specific* aapt2 artifact (`aapt2-<version>-linux`/`-osx`/`-windows`) **lazily, during actual task execution** (specifically when `assembleDebug`/`assembleRelease`/`lintDebug` really runs resource processing). A dry-run pass, by construction, never reaches that point — so this one artifact was invisible to the exact process used to populate the file, regardless of how many times it's rerun in dry-run mode.

This was confirmed empirically in a prior session: a genuine (non-dry-run) `--write-verification-metadata sha256` run that executed real tasks surfaced exactly this one missing entry and let `lintDebug` / `assembleDebug` / `assembleDebugAndroidTest` complete successfully once it was captured. That session's environment (and its logs) no longer exist — see §4.

---

## 4. Recovery Attempt — exhausted, no durable copy exists

Before proposing any new build, all existing evidence sources were checked and ruled out (per prior investigation, re-confirmed this session):

| Source | Result |
|---|---|
| `git log --all -p -- gradle/verification-metadata.xml gradle/verification-metadata.dryrun.xml` | Only `aapt2-proto` ever committed; native `aapt2` never appears in any historical revision. |
| GitHub Actions artifact `verification-metadata-dryrun` (most recent run, `31695610994`) | Downloaded and inspected — 435 components, same gap. This artifact is dry-run output by construction and structurally cannot contain a lazily-resolved artifact (see §3). |
| CI job logs across 6 most recent runs | Every real (non-dry-run) `lint`/`build-debug` job on record fails earlier, at the **configuration phase** (`Dependency verification failed for configuration ':classpath'`), against the *older* 330-component baseline — before Gradle's execution phase would ever reach aapt2 resolution. No log contains the hash. |

**Conclusion:** the missing hash was generated exactly once, in a now-destroyed ephemeral sandbox, and was never persisted anywhere durable. It must be regenerated by actually running the build — there is no shortcut via existing artifacts.

---

## 5. Historical task evidence (pre-closure)

Confirmed in a prior session via a real, non-dry-run, task-executing Gradle run against a corrected (436-component) metadata file:

- `:app:lintDebug` — **PASS**
- `:app:assembleDebug` — **PASS**, debug APK produced
- `:app:assembleDebugAndroidTest` — **PASS**, test APK produced
- `:app:assembleRelease` — task graph runs cleanly through compilation, resource processing, and R8 minification; it fails **only** at the final `packageRelease` step because `signingConfigs.release` (see §6) has no keystore in that unsigned local sandbox. This is a **separate, expected, unrelated failure** — not a dependency-verification failure. Zero checksum/verification errors appeared anywhere in that run's log.

This means the actual fix (adding the aapt2 hash) is small, mechanical, and previously validated — the remaining work is (a) regenerating it, and (b) making sure CI's own runners produce a real APK, which requires the signing question below to be resolved.

---

## 6. Second, independent gap: release signing

**Citation:** `app/build.gradle` lines 98–114, `.github/workflows/release.yml` lines 61–92 (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secret injection)

`app/build.gradle`'s `signingConfigs.release` block only configures signing `if (ksFile.exists() && ksPwd != null)` — i.e. it silently no-ops when secrets are absent, rather than failing loudly. `release.yml` (the workflow that actually produces a *signed* release APK) does fail loudly and immediately if `secrets.KEYSTORE_BASE64` is unset:

```
if [ -z "$KEYSTORE_BASE64" ]; then
  echo "::error::KEYSTORE_BASE64 secret is missing — cannot sign release APKs."
  exit 1
fi
```

This is a **separate concern from SC-03**: it's a release-signing/secrets-provisioning gap, not a dependency-integrity gap. Whether it blocks "get an actual APK from GitHub Actions" depends on which workflow/APK you need:

- `ci.yml` → `build-debug` job uploads an **unsigned debug APK** (`DuoShield-debug-${{ github.sha }}`) on every push/PR. This does **not** require `KEYSTORE_BASE64` at all — it only needs the SC-03 fix in §7 to stop failing at dependency verification.
- `release.yml` → produces a **signed release APK** on push to `main` or manual dispatch. This requires `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to exist as GitHub Actions repo secrets. I was unable to check whether these are currently set (`gh secret list` returned `403 Resource not accessible by integration` under this session's token) — **this needs manual confirmation in GitHub repo settings.**

---

## 7. Historical eradication plan — superseded by current status at top

### Phase A — Close the SC-03 dependency-verification gap (blocks *all* APKs, debug and release)

1. **Provision a toolchain** (JDK 17, Android SDK platform 34 + build-tools 34.0.0, `local.properties`, `app/google-services.json` from `.template`) — ephemeral, must be redone each fresh sandbox; not a code change.
2. **Run a real, non-dry-run metadata write** that reaches task execution:
   ```
   ./gradlew --write-verification-metadata sha256 \
     :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
     --no-daemon --continue
   ```
   Gradle appends any newly-encountered artifact hash (the native `aapt2` entry) directly into `gradle/verification-metadata.xml` as tasks execute for real.
3. **Diff-verify** the result against the current 435-component file:
   - All 435 existing hashes must be byte-identical (0 mismatches) — proves nothing was corrupted or hand-edited.
   - Exactly one new component should appear: `com.android.tools.build:aapt2:8.6.0-11315950` (Linux classifier).
4. **Confirm the four target tasks pass** under the corrected, fully-enforced metadata (`verify-metadata=true` unchanged throughout):
   - `:app:lintDebug` → PASS
   - `:app:assembleDebug` → PASS, debug APK present under `app/build/outputs/apk/debug/`
   - `:app:assembleDebugAndroidTest` → PASS, test APK present
   - `:app:assembleRelease` → task graph completes through R8; final packaging failure, if any, must be **exclusively** the missing-keystore case from §6 — any checksum/verification error at this step means Phase A is not actually done.
5. **Persist the evidence this time** — this is the step skipped last session:
   - Commit the corrected `gradle/verification-metadata.xml` (435 → 436 components) with a message that states it was generated by a real, non-dry-run, task-executing run (not hand-edited).
   - Retire or repoint `.github/workflows/ci.yml`'s `regen-verification-metadata` job comment (lines 216–222 currently say "Remove this job once the dryrun output has been reviewed and merged" — that condition will now be true) — **only touch this after the commit above is verified green in CI**, not before, and only as a follow-up cleanup, not blocking APK production.
   - Upload the real (non-dry-run) build log as a workflow artifact in `ci.yml` going forward, so this evidence doesn't get lost to sandbox recycling again.

### Phase B — Confirm CI itself is green (proves this generalizes beyond the local sandbox)

6. Push the Phase A commit and let `.github/workflows/ci.yml` run on GitHub's own runners (which already have JDK/Android SDK preinstalled — no toolchain-provisioning step needed there, unlike the local sandbox).
7. Confirm in the Actions run:
   - `verify-libsignal-jar` (SC-01) — PASS (unrelated gate, should already pass)
   - `validate-gradle-wrapper` (SC-07) — PASS (unrelated gate, should already pass)
   - `lint` — PASS, specifically watch for **zero** `Dependency verification failed` errors in the log (this is the SC-03 proof point)
   - `build-debug` — PASS, and the **`DuoShield-debug-${{ github.sha }}`** artifact must appear in the run's Artifacts list — **this is the literal "actual APK from GitHub Actions" deliverable requested.**
8. Download that artifact from the Actions run UI (or `gh run download <run-id> --name DuoShield-debug-<sha>`) and confirm it's a valid installable APK (non-zero size, `unzip -l` shows `AndroidManifest.xml`/`classes.dex`).

### Phase C — Release-signed APK (only if a signed release, not just a debug APK, is required)

9. Confirm whether `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` already exist as repo secrets (Settings → Secrets and variables → Actions — I could not check this via API in this session).
10. If missing: generate or obtain the release keystore, base64-encode it (`base64 -w0 duoshield-release.keystore`), and add all four secrets.
11. Trigger `.github/workflows/release.yml` (push to `main`, or `workflow_dispatch`) and confirm the signed release APK is produced and attached to the GitHub Release, with `SHA256SUMS` (SC-04, already fixed per `BUG_TRACKER.md` line 82) and provenance attestation succeeding.

### Phase D — Close the loop on tracking docs (only after B, and C if applicable, are green)

12. Update `BUG_TRACKER.md` line 84: promote SC-03 from **Partial (S3-03)** to **Fixed**, citing the real CI run URL/SHA as evidence (matching the citation style already used for SC-04/SC-05/SC-06 on adjacent lines).
13. Update `security-remediation/ROUND3_REMEDIATION_PLAN.md` lines 216–220/233–236 to remove S3-19b from the "outstanding" list, citing the same CI run.

---

## 8. Historical immediate next action — no longer current

**Phase A, steps 1–4** is the smallest unit of work that unblocks everything else — it requires no secrets, no repo-settings access, and produces the debug-APK-from-CI deliverable once merged (Phase B). Phase C (signed release) is a separate, independent decision gated on keystore secret availability, which needs your confirmation since this session's GitHub token could not list repo secrets.
