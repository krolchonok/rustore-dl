# RuStore DL

Download APKs from [RuStore](https://www.rustore.ru) **without installing the RuStore app** — no background workers, no auto-updates, no store telemetry.

[![CI](https://github.com/krolchonok/rustore-dl/actions/workflows/ci.yml/badge.svg)](https://github.com/krolchonok/rustore-dl/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Features

| | CLI | Android |
|---|:---:|:---:|
| Search by name / package / catalog URL | ✅ | ✅ |
| RuStore-style app cards (icon, rating, screenshots) | — | ✅ |
| Single & split APK download | ✅ | ✅ |
| Download history | — | ✅ |
| Install from app | — | ✅ |
| Secure-session API signing | ✅ | ✅ |

## Quick start

### Android

Download the latest APK from [Releases](https://github.com/krolchonok/rustore-dl/releases) or build locally:

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### CLI

```bash
cd cli
python3 -m venv .venv && source .venv/bin/activate
pip install -e .

rustore-dl search telegram
rustore-dl resolve https://www.rustore.ru/catalog/app/ru.sberbankmobile
rustore-dl download org.telegram.messenger.web -o telegram.apk
rustore-dl download com.example.app --dir ./splits/
```

## Project layout

```
rustore-dl/
├── android/          # Kotlin + Jetpack Compose app
├── cli/              # Python package (rustore-dl)
├── docs/             # API notes, release process
└── .github/workflows # CI + release automation
```

## How it works

RuStore public API requires a **secure session**:

1. Request a nonce from `api.rustore.ru`
2. Sign it with HMAC-SHA256 (key from official RuStore APK)
3. Use `X-Client-Signature` header for `backapi.rustore.ru` requests

See [docs/API.md](docs/API.md) for endpoint details.

## Disclaimer

This is an **unofficial** third-party client. Not affiliated with RuStore, VK, or any app developers. Use at your own risk. Respect app licenses and terms of service.

## License

[MIT](LICENSE)
