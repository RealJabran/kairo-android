# Changelog

Notable user-facing changes are recorded here.

## 2.2.0 — 2026-08-15

### Fixed

- Preserve an existing offline episode until its replacement completes successfully.
- Handle HLS byte-range segments through Media3 instead of duplicating complete resources.
- Combine separate HLS audio and video renditions into one progressive MP4 export.
- Validate all required Kairo-compatible source endpoints before saving a source.

### Repository

- Added an install-focused README, project map, contribution guide, issue template, and Android CI.
- Added installable APK distribution through GitHub Releases.

## 2.1.1 — 2026-08-09

- Added the Stremio-compatible add-on manager and direct install-link handling.
- Fixed Anime Kitsu search catalogs using the `anime` content type.
- Added add-on capability detection, ordering, caching, and cross-add-on stream resolution.

## 2.0.0 — 2026-08-07

- Added instant playback, download statistics, watch progress, and watchlist support.
- Introduced the redesigned Media3 player with gestures, captions, quality switching, display modes, PiP, and episode navigation.
