# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.3.x   | ✅ Active |
| < 1.3   | ❌ End of life |

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

To report a vulnerability, e-mail **kumarclaude4@gmail.com** with:

1. A clear description of the issue
2. Steps to reproduce (or a proof-of-concept)
3. The impact and affected versions
4. Any suggested mitigations

You will receive an acknowledgement within **48 hours** and a full response within **7 days**.

If the issue is confirmed, a patch will be released as soon as possible. Credit will be given in the release notes unless you prefer to remain anonymous.

---

## Security design

### Encryption

- All messages are encrypted end-to-end using the **Signal Protocol** (Double Ratchet + X3DH).
- Media files (images, video, voice notes) are encrypted with **AES-256-GCM** before upload to Backblaze B2. The server stores only ciphertext and never holds plaintext keys.
- The local Room database is encrypted with **SQLCipher**. The database key is derived per-UID via HKDF-SHA256 and stored in `EncryptedSharedPreferences`.
- Backup files are encrypted with **AES-256-GCM**; the key is derived from the user's seed phrase via PBKDF2-SHA256 (310,000 iterations).

### Authentication

- Users authenticate with Firebase Auth using a **custom token** minted server-side. The UID is deterministically derived from a seed phrase using HKDF-SHA256, so account identity is fully controlled by the seed phrase — not by Firebase.
- A **duress PIN** triggers silent wipe of all local data, Signal keys, and Firestore content without any indication to an observer.

### Transport

- All Firestore and Firebase Auth traffic uses TLS enforced by the Firebase SDK.
- B2 upload/download requests are signed with **AWS Signature Version 4** over HTTPS.
- The FCM push server is a stateless relay: it never reads, stores, or logs message content.

### Firestore security rules

- All reads and writes are gated on `request.auth.uid == userId`.
- Users can only read messages in conversations they participate in (`participants` array membership check).
- Firestore rules are tested with the Firebase emulator suite on every change (see `.github/workflows/firestore.yml`).

### Known limitations / deferred hardening

- Android activities apply `FLAG_SECURE` by default to block screenshots, screen recording, and recents thumbnails. The Security & Privacy setting is an explicit opt-in that persists the user's choice; a fresh install defaults to protection. Verify behavior on supported devices before release.
- Cloud Functions are not used for key operations; all sensitive logic is on-device.
