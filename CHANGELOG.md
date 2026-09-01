# Changelog

All notable changes to this project are documented in this file.

## [0.3.1] - 2026-09-01

### Fixed
- Android APK downloads now stream to disk instead of loading into memory
- Increased OkHttp timeouts for slow RuStore CDN connections
- Added download progress feedback (percent / MB)

## [0.3.0] - 2026-09-01

### Added
- RuStore-style catalog cards with icons, ratings, screenshots, and metadata
- Lazy enrichment of search results via `overallInfo`
- Coil image loading

## [0.2.0] - 2026-09-01

### Added
- Download history with install and delete
- Split APK support via `PackageInstaller`
- Install permission flow for unknown sources
- CLI `resolve` command and catalog URL support

## [0.1.0] - 2026-08-31

### Added
- Initial Python CLI (`rustore-dl`)
- Initial Android app with search and download
- RuStore secure-session signing (nonce + `X-Client-Signature`)
