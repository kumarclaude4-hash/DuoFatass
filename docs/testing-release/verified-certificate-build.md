# Verified certificate test build

Branch-only workflow trigger. This file is not intended for main.

- Base mainline: 6f78df314d3a3e940bf0a98755a566dce619f4c2
- Purpose: rebuild signed, minified ABI APKs with corrected certificate fingerprint verification.
- Expected output: a commit-scoped test prerelease and a 30-day Actions artifact.
