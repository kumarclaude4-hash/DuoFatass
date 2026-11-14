# Current-phase signed testing build

This branch-only marker triggers the signed testing prerelease workflow.

- Mainline base: 0b4bdbd4bbf57bf983b1d996456844692888b095
- Purpose: produce the signed minified release APKs for device testing.
- Distribution: GitHub Actions artifact plus a public GitHub prerelease.
- Release channel: prerelease only; it must not replace the latest stable release.
- App source and version metadata are unchanged by this marker commit.

This file is intentionally limited to this testing branch and is not intended for merge into main.
