# rustore-dl CLI

Download APKs from RuStore without installing the store app.

## Install

```bash
cd cli
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
```

## Usage

```bash
# Search
rustore-dl search telegram

# Resolve package / URL / text query
rustore-dl resolve ru.sberbankmobile
rustore-dl resolve https://www.rustore.ru/catalog/app/ru.sberbankmobile

# App info
rustore-dl info ru.sberbankmobile

# Direct links
rustore-dl links org.telegram.messenger.web

# Download single APK
rustore-dl download org.telegram.messenger.web -o telegram.apk

# Download split bundle into a directory
rustore-dl download com.example.app --dir ./example-app/
```

## Notes

- Uses RuStore secure-session signing (same approach as Obtainium).
- Accepts package names and `https://www.rustore.ru/catalog/app/<package>` URLs.
- Split APK bundles require `--dir`; single-file downloads can use `-o`.
