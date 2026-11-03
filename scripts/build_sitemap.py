#!/usr/bin/env python3
"""Generate docs/sitemap.xml from every public page under docs/.

Walks docs/**/*.html, skips files whose name starts with "_" (templates,
never indexed — e.g. docs/_template.html) and anything carrying
<meta name="robots" content="noindex">, and lists the rest as absolute
URLs under SITE_URL (read from docs/config.js), with a lastmod date taken
from `git log` (falling back to the file's mtime if git has no history for
it, e.g. a file staged but not yet committed).

Usage (from the repo root):
    python3 scripts/build_sitemap.py           # write docs/sitemap.xml
    python3 scripts/build_sitemap.py --check    # exit 1 if it's out of date (CI)
"""
from __future__ import annotations

import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
CONFIG_JS = DOCS / "config.js"
SITEMAP = DOCS / "sitemap.xml"


def site_url() -> str:
    src = CONFIG_JS.read_text(encoding="utf-8")
    m = re.search(r'GITHUB_REPO:\s*"([^"]+)"', src)
    if not m:
        sys.exit("config.js: GITHUB_REPO not found")
    owner, repo = m.group(1).split("/", 1)
    return f"https://{owner.lower()}.github.io/{repo}/"


def lastmod(path: Path) -> str:
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%cI", "--", str(path)],
            cwd=ROOT, capture_output=True, text=True, timeout=10,
        )
        iso = out.stdout.strip()
        if iso:
            return iso[:10]
    except Exception:
        pass
    return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).date().isoformat()


def public_pages() -> list[Path]:
    pages = []
    for path in sorted(DOCS.rglob("*.html")):
        if path.name.startswith("_") or any(p.startswith("_") for p in path.relative_to(DOCS).parts):
            continue
        text = path.read_text(encoding="utf-8")
        if re.search(r'<meta\s+name="robots"\s+content="[^"]*noindex', text, re.I):
            continue
        pages.append(path)
    return pages


def url_for(path: Path, base: str) -> str:
    rel = path.relative_to(DOCS).as_posix()
    if rel == "index.html":
        return base
    if rel.endswith("/index.html"):
        return base + rel[: -len("index.html")]
    return base + rel


def render(base: str) -> str:
    entries = []
    for path in public_pages():
        entries.append(
            "  <url>\n"
            f"    <loc>{url_for(path, base)}</loc>\n"
            f"    <lastmod>{lastmod(path)}</lastmod>\n"
            "  </url>"
        )
    body = "\n".join(entries)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        f"{body}\n"
        "</urlset>\n"
    )


def main() -> int:
    check = "--check" in sys.argv
    new = render(site_url())
    old = SITEMAP.read_text(encoding="utf-8") if SITEMAP.exists() else None
    if old == new:
        print("Up to date.")
        return 0
    if check:
        print("docs/sitemap.xml is out of date (run python3 scripts/build_sitemap.py)")
        return 1
    SITEMAP.write_text(new, encoding="utf-8")
    print("Updated docs/sitemap.xml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
