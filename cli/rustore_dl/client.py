from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote

import requests

HMAC_KEY = base64.b64decode("K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU=")
APK_CERT_SHA256 = base64.b64decode("Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ=")

BASE_URL = "https://backapi.rustore.ru"
NONCE_URL = "https://api.rustore.ru/v1/secure/nonce"
MAX_SEARCH_PAGES = 5

PACKAGE_NAME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
CATALOG_URL_RE = re.compile(r"(?:https?://)?(?:www\.)?rustore\.ru/catalog/app/([^/?#]+)")

DEVICE_MANUFACTURER = "Google"
DEVICE_MODEL = "Pixel 8 Pro"
DEVICE_HARDWARE = "husky"
FIRMWARE_VER = "16"
ANDROID_SDK_VER = "36"
FIRMWARE_LANG = "ru"
RU_STORE_VER_CODE = "1105002"
USER_AGENT = (
    f"RuStore/1.105.0.2 (Android {FIRMWARE_VER}; SDK {ANDROID_SDK_VER}; "
    f"arm64-v8a; {DEVICE_MANUFACTURER} {DEVICE_MODEL}; {FIRMWARE_LANG})"
)


class RuStoreError(RuntimeError):
    pass


@dataclass(frozen=True)
class AppInfo:
    app_id: int
    package_name: str
    app_name: str
    version_code: int | None = None
    version_name: str | None = None
    short_description: str | None = None
    icon_url: str | None = None
    company_name: str | None = None

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> AppInfo:
        return cls(
            app_id=int(payload["appId"]),
            package_name=str(payload["packageName"]),
            app_name=str(payload.get("appName") or payload["packageName"]),
            version_code=payload.get("versionCode"),
            version_name=payload.get("versionName"),
            short_description=payload.get("shortDescription"),
            icon_url=payload.get("iconUrl"),
            company_name=payload.get("companyName"),
        )


@dataclass(frozen=True)
class DownloadArtifact:
    url: str
    file_name: str


def extract_package_name(value: str) -> str | None:
    match = CATALOG_URL_RE.search(value.strip())
    if match:
        return unquote(match.group(1))
    trimmed = value.strip()
    if PACKAGE_NAME_RE.match(trimmed):
        return trimmed
    return None


def normalize_download_url(raw_url: str) -> str | None:
    url = raw_url.strip()
    if not url:
        return None
    if "/apk/" not in url and not url.endswith(".apk") and not url.endswith(".zip"):
        return None
    if url.endswith(".zip"):
        return url[:-4] + ".apk"
    return url


def file_name_from_url(url: str) -> str:
    name = url.rsplit("/", maxsplit=1)[-1]
    if name.endswith(".apk"):
        return name
    if name.endswith(".zip"):
        return name[:-4] + ".apk"
    return f"{name}.apk"


def _java_string_hash(value: str) -> int:
    hash_code = 0
    for char in value:
        hash_code = (31 * hash_code + ord(char)) & 0xFFFFFFFF
    if hash_code >= 0x80000000:
        hash_code -= 0x100000000
    return hash_code


def _device_id_suffix() -> str:
    manufacturer = _java_string_hash(DEVICE_MANUFACTURER)
    model = _java_string_hash(DEVICE_MODEL)
    hardware = _java_string_hash(DEVICE_HARDWARE)
    device = _java_string_hash(DEVICE_HARDWARE)
    combined = device + ((hardware + ((model + (manufacturer * 31)) * 31)) * 31)
    combined = (combined + 0x80000000) & 0xFFFFFFFF
    if combined >= 0x80000000:
        combined -= 0x100000000
    return str(combined)


def _random_device_id() -> str:
    android_id = secrets.token_hex(8)
    return f"{android_id}-{_device_id_suffix()}"


class RuStoreClient:
    def __init__(self, session: requests.Session | None = None, timeout: float = 30.0) -> None:
        self._session = session or requests.Session()
        self._timeout = timeout
        self._device_id = _random_device_id()
        self._signature: str | None = None

    def _base_headers(self, signature: str | None = None) -> dict[str, str]:
        headers = {
            "deviceId": self._device_id,
            "firmwareVer": FIRMWARE_VER,
            "androidSdkVer": ANDROID_SDK_VER,
            "deviceManufacturerName": DEVICE_MANUFACTURER,
            "deviceModelName": DEVICE_MODEL,
            "deviceModel": f"{DEVICE_MANUFACTURER} {DEVICE_MODEL}",
            "firmwareLang": FIRMWARE_LANG,
            "ruStoreVerCode": RU_STORE_VER_CODE,
            "deviceType": "mobile",
            "User-Agent": USER_AGENT,
        }
        if signature:
            headers["X-Client-Signature"] = signature
        return headers

    def _ensure_signature(self, force: bool = False) -> str:
        if self._signature is not None and not force:
            return self._signature

        response = self._session.post(
            NONCE_URL,
            headers=self._base_headers(),
            timeout=self._timeout,
        )
        if response.status_code != 200:
            raise RuStoreError(f"Nonce request failed: HTTP {response.status_code}")

        nonce = response.json().get("nonce")
        if not nonce:
            raise RuStoreError("Nonce response did not contain nonce")

        digest = hmac.new(
            HMAC_KEY,
            base64.b64decode(nonce) + APK_CERT_SHA256,
            hashlib.sha256,
        ).digest()
        self._signature = base64.b64encode(digest).decode()
        return self._signature

    def _request(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
        signed: bool = True,
    ) -> requests.Response:
        signature = self._ensure_signature() if signed else None
        headers = self._base_headers(signature)
        if json_body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"

        response = self._session.request(
            method,
            url,
            headers=headers,
            params=params,
            json=json_body,
            timeout=self._timeout,
        )

        if response.status_code == 419 and signed:
            self._ensure_signature(force=True)
            headers = self._base_headers(self._signature)
            if json_body is not None:
                headers["Content-Type"] = "application/json; charset=utf-8"
            response = self._session.request(
                method,
                url,
                headers=headers,
                params=params,
                json=json_body,
                timeout=self._timeout,
            )

        return response

    def resolve_query(self, query: str, *, page_size: int = 20) -> list[AppInfo]:
        trimmed = query.strip()
        if not trimmed:
            return []

        package_name = extract_package_name(trimmed)
        if package_name:
            try:
                return [self.get_app_info(package_name)]
            except RuStoreError:
                pass

        return self.search(trimmed, page_size=page_size)

    def search(self, query: str, *, page: int = 0, page_size: int = 20) -> list[AppInfo]:
        response = self._request(
            "GET",
            f"{BASE_URL}/applicationData/apps",
            params={
                "pageNumber": page,
                "pageSize": page_size,
                "query": query,
                "buyeruid": "null",
            },
        )
        if response.status_code != 200:
            raise RuStoreError(f"Search failed: HTTP {response.status_code}")

        payload = response.json()
        content = payload.get("body", {}).get("content", [])
        return [AppInfo.from_payload(item) for item in content]

    def get_app_info(self, package_name: str) -> AppInfo:
        response = self._request(
            "GET",
            f"{BASE_URL}/applicationData/overallInfo/{package_name}",
        )
        if response.status_code == 200:
            payload = response.json()
            if payload.get("code") == "OK" and payload.get("body"):
                return AppInfo.from_payload(payload["body"])

        for page in range(MAX_SEARCH_PAGES):
            matches = self.search(package_name, page=page, page_size=50)
            for app in matches:
                if app.package_name == package_name:
                    return app
            if len(matches) < 50:
                break

        message = (
            response.json().get("message")
            if response.headers.get("content-type", "").startswith("application/json")
            else response.text
        )
        raise RuStoreError(f"App not found: {package_name} ({message})")

    def get_download_artifacts(self, app_id: int) -> list[DownloadArtifact]:
        response = self._request(
            "POST",
            f"{BASE_URL}/v3/showcase/apps/download-link",
            json_body={"appId": app_id, "firstInstall": True},
        )
        if response.status_code != 200:
            raise RuStoreError(f"Download link request failed: HTTP {response.status_code}")

        payload = response.json()
        download_urls = payload.get("downloadUrls")
        if download_urls is None:
            download_urls = payload.get("body", {}).get("downloadUrls", [])

        artifacts: list[DownloadArtifact] = []
        for item in download_urls:
            normalized = normalize_download_url(str(item.get("url", "")))
            if normalized:
                artifacts.append(
                    DownloadArtifact(url=normalized, file_name=file_name_from_url(normalized)),
                )

        if not artifacts:
            raise RuStoreError("RuStore returned no download URLs")

        return artifacts

    def get_download_urls(self, app_id: int) -> list[str]:
        return [artifact.url for artifact in self.get_download_artifacts(app_id)]

    def download_artifacts(
        self,
        package_name: str,
        destination: str | Path,
        *,
        allow_split: bool = True,
    ) -> tuple[AppInfo, list[Path]]:
        info = self.get_app_info(package_name)
        artifacts = self.get_download_artifacts(info.app_id)

        if len(artifacts) > 1 and not allow_split:
            raise RuStoreError(
                f"Split APK bundle detected ({len(artifacts)} files). "
                "Use --dir to download all parts.",
            )

        destination_path = Path(destination)
        if len(artifacts) == 1 and destination_path.suffix.lower() == ".apk":
            saved = [self._download_to_file(artifacts[0].url, destination_path)]
            return info, saved

        destination_path.mkdir(parents=True, exist_ok=True)
        saved_files: list[Path] = []
        for artifact in artifacts:
            target = destination_path / artifact.file_name
            saved_files.append(self._download_to_file(artifact.url, target))
        return info, saved_files

    def download_apk(self, package_name: str, destination: str) -> AppInfo:
        info, _ = self.download_artifacts(package_name, destination, allow_split=False)
        return info

    def _download_to_file(self, url: str, destination: Path) -> Path:
        response = self._session.get(url, stream=True, timeout=self._timeout)
        if response.status_code != 200:
            raise RuStoreError(f"APK download failed: HTTP {response.status_code}")

        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as output:
            for chunk in response.iter_content(chunk_size=1024 * 256):
                if chunk:
                    output.write(chunk)
        return destination
