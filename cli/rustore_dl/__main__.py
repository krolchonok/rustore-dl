from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .client import RuStoreClient, RuStoreError, extract_package_name


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rustore-dl",
        description="Search and download APKs from RuStore without installing the store app.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    search_parser = subparsers.add_parser("search", help="Search apps by name or package")
    search_parser.add_argument("query", help="Search query")
    search_parser.add_argument("--page", type=int, default=0, help="Page number (0-based)")
    search_parser.add_argument("--limit", type=int, default=10, help="Results per page")

    resolve_parser = subparsers.add_parser(
        "resolve",
        help="Resolve package name, catalog URL, or search query to app metadata",
    )
    resolve_parser.add_argument("query", help="Package name, RuStore URL, or search text")

    info_parser = subparsers.add_parser("info", help="Show app metadata by package name")
    info_parser.add_argument("package", help="Android package name or RuStore catalog URL")

    links_parser = subparsers.add_parser("links", help="Print direct APK URLs")
    links_parser.add_argument("package", help="Android package name or RuStore catalog URL")

    download_parser = subparsers.add_parser("download", help="Download APK by package name")
    download_parser.add_argument("package", help="Android package name or RuStore catalog URL")
    download_parser.add_argument(
        "-o",
        "--output",
        help="Output APK file (single-file apps only)",
    )
    download_parser.add_argument(
        "--dir",
        help="Output directory for split APK bundles (downloads all parts)",
    )

    return parser


def _normalize_package(value: str) -> str:
    return extract_package_name(value) or value.strip()


def _print_app(info) -> None:
    version = info.version_name or info.version_code or "?"
    print(f"{info.package_name}")
    print(f"  name   : {info.app_name}")
    print(f"  version: {version}")
    if info.company_name:
        print(f"  company: {info.company_name}")
    if info.short_description:
        print(f"  about  : {info.short_description}")


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    client = RuStoreClient()

    try:
        if args.command == "search":
            results = client.search(args.query, page=args.page, page_size=args.limit)
            if not results:
                print("No results.")
                return 0
            for index, app in enumerate(results, start=1):
                version = app.version_name or app.version_code or "?"
                print(f"[{index}] {app.app_name} ({app.package_name}) v{version}")
                if app.short_description:
                    print(f"    {app.short_description}")
            return 0

        if args.command == "resolve":
            results = client.resolve_query(args.query)
            if not results:
                print("No apps found.")
                return 1
            for app in results:
                _print_app(app)
                print()
            return 0

        if args.command == "info":
            info = client.get_app_info(_normalize_package(args.package))
            _print_app(info)
            return 0

        if args.command == "links":
            info = client.get_app_info(_normalize_package(args.package))
            urls = client.get_download_urls(info.app_id)
            for url in urls:
                print(url)
            return 0

        if args.command == "download":
            package = _normalize_package(args.package)
            if args.dir:
                info, saved = client.download_artifacts(package, args.dir, allow_split=True)
                print(f"Saved {info.app_name} ({len(saved)} file(s)) to {Path(args.dir).resolve()}")
                for path in saved:
                    print(f"  - {path.name}")
                return 0

            output = args.output or f"{package}.apk"
            info, saved = client.download_artifacts(package, output, allow_split=False)
            print(f"Saved {info.app_name} to {saved[0].resolve()}")
            return 0

    except RuStoreError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
