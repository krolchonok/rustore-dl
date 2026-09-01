# RuStore API Notes

This project uses unofficial public endpoints used by the RuStore Android client.

## Endpoints

| Purpose | Method | URL |
|---------|--------|-----|
| Search | GET | `https://backapi.rustore.ru/applicationData/apps` |
| App info | GET | `https://backapi.rustore.ru/applicationData/overallInfo/{package}` |
| Download link | POST | `https://backapi.rustore.ru/v3/showcase/apps/download-link` |
| Secure nonce | POST | `https://api.rustore.ru/v1/secure/nonce` |

## Secure session

Since August 2026, most `backapi` requests require:

1. `POST /v1/secure/nonce` with device headers
2. `X-Client-Signature = base64(HMAC-SHA256(key, base64decode(nonce) || certSha256))`

The signing key is extracted from the official RuStore APK (same approach as [Obtainium](https://github.com/ImranR98/Obtainium)).

## Known limitations

- Some packages fail `overallInfo` with `android_app_ver`; search fallback is used
- Only the latest published version is available through these endpoints
- CDN download speed varies by network; mobile clients need long read timeouts
