# S08-H5 / S07-M1 — Legacy plaintext migration integrity and gate

Date: 2026-11-06
PR: #228

## Change

The legacy SharedPreferences migrator previously treated `target.contains(key)` as proof that a legacy secret had been migrated. That did not verify the stored value or its type. A pre-existing value under the same key could therefore be counted as a successful migration, after which the only correct plaintext copy could be removed.

The migrator now:
- compares the exact stored value and type before counting an existing target entry as migrated;
- compares StringSet values by set equality rather than implementation class;
- verifies newly written values by reading them back and comparing their content;
- retains the plaintext source whenever a value conflicts, a write cannot be verified, or the source cannot be opened/enumerated;
- reports an unknown migration state as `plaintextRemains=true`, including when `SecurePrefs.finish()` catches a migration exception.

The onboarding/restore gate now checks both storage durability and migration state. A durable Keystore tier no longer permits new onboarding or restore while legacy plaintext secrets remain unverified. Existing sign-in/data access is not routed through this gate. A dedicated message explains that the source data was retained and warns users not to uninstall or clear app data.

## Regression coverage

`LegacyPlaintextMigratorTest` covers:
1. existing target value conflict: plaintext is retained, then cleared only after the target value matches;
2. existing target type conflict: plaintext is retained;
3. migration and read-back of String, StringSet, Integer, Long, Float and Boolean values;
4. ephemeral-tier migration: memory copy succeeds but plaintext source remains;
5. inability to open the raw preferences file: fail closed;
6. inability to enumerate raw preferences: fail closed.

`DeviceSecurityGateTest` covers hardware/software tiers, no durable tier, and durable tiers with incomplete plaintext migration.

## Residual acceptance

- PR CI must pass Android compilation, the full JVM/Robolectric suite, lint, APK build and Espresso.
- Physical-device verification is still required for real Android Keystore/EncryptedSharedPreferences behavior, upgrade from an actual legacy install, recovery after a failed migration, and confirmation that existing sign-in remains available.
- Do not delete retained plaintext files manually. They may be the only recoverable copy of an existing SQLCipher passphrase or identity secret.
