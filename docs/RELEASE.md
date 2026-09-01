# Release Process

## Versioning

- **Android**: `versionName` + `versionCode` in `android/app/build.gradle.kts`
- **CLI**: `version` in `cli/pyproject.toml`
- **Changelog**: update `CHANGELOG.md`

## Tag format

```
v0.3.1
```

## GitHub Release

1. Update versions and changelog
2. Commit and push to `main`
3. Create and push tag:

```bash
git tag v0.3.1
git push origin v0.3.1
```

4. GitHub Actions `release.yml` builds the debug APK and attaches it to the release

## Android signing (production)

Current CI produces an **unsigned debug APK**. For Play Store or sideload distribution:

1. Generate a release keystore
2. Add signing config to `android/app/build.gradle.kts` via `local.properties` or CI secrets
3. Build with `./gradlew assembleRelease`

Never commit keystore files or passwords to the repository.
