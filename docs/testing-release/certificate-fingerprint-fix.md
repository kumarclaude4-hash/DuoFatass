# Corrected signed testing build

This branch-only marker triggers the release workflow after PR #307 merged.

- Mainline base: 6f78df314d3a3e940bf0a98755a566dce619f4c2
- Purpose: regenerate signed ABI APKs with strict signing-certificate SHA-256 validation.
- Distribution: 30-day Actions artifact and non-latest GitHub prerelease.
- No app runtime source or version metadata changes.

This file is intentionally limited to this testing branch and is not intended for merge into main.
