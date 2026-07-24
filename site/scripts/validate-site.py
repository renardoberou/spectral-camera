#!/usr/bin/env python3
"""Small dependency-free integrity check for the Spectral Camera site."""
from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "index.html"
REQUIRED_IDS = {"top", "story", "engine", "field-notes", "results", "honesty"}
REQUIRED_TEXT = {
    "Spectral Camera",
    "Rollei IR 400",
    "Minas Gerais",
    "synthetic NIR",
    "on the device",
}


class SiteParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.local_refs: list[tuple[str, str]] = []
        self.images_without_alt: list[str] = []
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(values["id"] or "")
        if tag == "img" and "alt" not in values:
            self.images_without_alt.append(values.get("src", "(missing src)"))
        for attribute in ("href", "src"):
            value = values.get(attribute)
            if not value:
                continue
            parsed = urlparse(value)
            if parsed.scheme or parsed.netloc or value.startswith("#"):
                continue
            self.local_refs.append((attribute, value.split("#", 1)[0]))

    def handle_data(self, data: str) -> None:
        self.text.append(data)


def main() -> int:
    if not INDEX.exists():
        print("FAIL: index.html is missing")
        return 1

    parser = SiteParser()
    parser.feed(INDEX.read_text(encoding="utf-8"))
    errors: list[str] = []

    missing_ids = REQUIRED_IDS - parser.ids
    if missing_ids:
        errors.append(f"missing section ids: {', '.join(sorted(missing_ids))}")
    if parser.images_without_alt:
        errors.append("images missing alt text: " + ", ".join(parser.images_without_alt))

    page_text = " ".join(parser.text)
    for marker in sorted(REQUIRED_TEXT):
        if marker not in page_text:
            errors.append(f"missing content marker: {marker}")

    for attribute, reference in parser.local_refs:
        target = (ROOT / reference).resolve()
        if not target.is_relative_to(ROOT.resolve()):
            errors.append(f"{attribute} escapes site root: {reference}")
        elif not target.exists():
            errors.append(f"broken local {attribute}: {reference}")

    expected_assets = [
        "assets/images/minas-gerais-original-capture.jpg",
        "assets/images/minas-gerais-colour-result.jpg",
        "assets/images/minas-gerais-monochrome-ir.jpg",
        "assets/brand/spectral-camera-mark.png",
    ]
    for asset in expected_assets:
        if not (ROOT / asset).exists():
            errors.append(f"missing required asset: {asset}")

    if errors:
        print("SITE VALIDATION FAILED")
        for error in errors:
            print(f"- {error}")
        return 1

    print("SITE VALIDATION PASSED")
    print(f"- sections: {len(parser.ids)} ids found")
    print(f"- local references: {len(parser.local_refs)} checked")
    print(f"- required images: {len(expected_assets)} present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
