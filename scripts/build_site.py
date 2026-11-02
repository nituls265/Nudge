#!/usr/bin/env python3
"""Build step for the static landing page in docs/.

Reads the single config block in docs/config.js and:
  * generates docs/qr.svg (QR code for DOWNLOAD_URL) locally with `segno`,
    so no external QR service or tracking is ever involved;
  * writes config values into docs/index.html wherever an element carries a
    data-*-tpl attribute, so links work with JavaScript disabled;
  * rebuilds the Content-Security-Policy so the Supabase URL is the only
    allowed outside origin.

Usage (from the repo root):
    pip install -r scripts/requirements.txt
    python3 scripts/build_site.py           # rewrite files
    python3 scripts/build_site.py --check   # exit 1 if files are out of date (CI)
"""
from __future__ import annotations

import html
import io
import re
import sys
from pathlib import Path

import segno

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
CONFIG_JS = DOCS / "config.js"
INDEX = DOCS / "index.html"
QR_SVG = DOCS / "qr.svg"


def load_config() -> dict:
    src = CONFIG_JS.read_text(encoding="utf-8")
    cfg: dict = {}
    for key, raw in re.findall(r'^\s*([A-Z_]+)\s*:\s*("(?:[^"\\]|\\.)*"|true|false)\s*,', src, re.M):
        cfg[key] = raw == "true" if raw in ("true", "false") else raw[1:-1]
    required = ["GITHUB_REPO", "DOWNLOAD_URL", "APK_ASSET_NAME", "FDROID_URL", "SHOW_COUNTERS",
                "SUPABASE_URL", "SUPABASE_ANON_KEY", "CONTACT_EMAIL"]
    missing = [k for k in required if k not in cfg]
    if missing:
        sys.exit(f"config.js is missing: {', '.join(missing)}")
    if not cfg["DOWNLOAD_URL"].endswith("/" + cfg["APK_ASSET_NAME"]):
        sys.exit("DOWNLOAD_URL must end with APK_ASSET_NAME")
    key = cfg["SUPABASE_ANON_KEY"]
    if key.startswith("sb_secret_") or '"role":"service_role"' in key:
        sys.exit("SUPABASE_ANON_KEY looks like a secret/service_role key. Use the publishable key.")
    owner, repo = cfg["GITHUB_REPO"].split("/", 1)
    cfg["PAGES_URL"] = f"https://{owner.lower()}.github.io/{repo}/"
    return cfg


def fill(tpl: str, cfg: dict) -> str:
    return re.sub(r"\{([A-Z_]+)\}", lambda m: str(cfg[m.group(1)]), tpl)


def set_attr(tag: str, name: str, value: str) -> str:
    value = html.escape(value, quote=False).replace('"', "&quot;")
    pattern = re.compile(rf'(\s{name}=")[^"]*(")')
    if pattern.search(tag):
        return pattern.sub(lambda m: m.group(1) + value + m.group(2), tag, count=1)
    return tag[:-1] + f' {name}="{value}">'


def render_html(src: str, cfg: dict) -> str:
    def per_tag(m: re.Match) -> str:
        tag = m.group(0)
        tpl = re.search(r'data-href-tpl="([^"]*)"', tag)
        if tpl:
            tag = set_attr(tag, "href", fill(html.unescape(tpl.group(1)), cfg))
        tpl = re.search(r'data-content-tpl="([^"]*)"', tag)
        if tpl:
            tag = set_attr(tag, "content", fill(html.unescape(tpl.group(1)), cfg))
        if "data-csp-tpl" in tag:
            csp = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                   f"media-src 'self'; connect-src 'self' {cfg['SUPABASE_URL'].rstrip('/')}; "
                   "base-uri 'none'; form-action 'none'")
            tag = set_attr(tag, "content", csp)
        hide = re.search(r'data-hide-if-empty="([A-Z_]+)"', tag)
        if hide:
            empty = not cfg[hide.group(1)]
            tag = re.sub(r"\shidden(?=[\s>])", "", tag)
            if empty:
                tag = tag[:-1] + " hidden>"
        return tag

    out = re.sub(r"<(?:a|link|meta)\b[^>]*>", per_tag, src)
    # Inner text for elements marked data-text-tpl (simple, flat elements only).
    out = re.sub(
        r'(<(\w+)\b[^>]*data-text-tpl="([^"]*)"[^>]*>)(.*?)(</\2>)',
        lambda m: m.group(1) + html.escape(fill(m.group(3), cfg)) + m.group(5),
        out, flags=re.S,
    )
    return out


def render_qr(cfg: dict) -> str:
    qr = segno.make(cfg["DOWNLOAD_URL"], error="m", micro=False)
    buf = io.BytesIO()
    qr.save(buf, kind="svg", border=2, dark="#0F172A", light="#FFFFFF", omitsize=True,
            xmldecl=False, title="QR code: Nudge Android download", nl=True)
    return buf.getvalue().decode("utf-8")


def main() -> int:
    check = "--check" in sys.argv
    cfg = load_config()
    targets = {INDEX: render_html(INDEX.read_text(encoding="utf-8"), cfg), QR_SVG: render_qr(cfg)}
    stale = []
    for path, new in targets.items():
        old = path.read_text(encoding="utf-8") if path.exists() else None
        if old != new:
            stale.append(path.relative_to(ROOT))
            if not check:
                path.write_text(new, encoding="utf-8")
    if check and stale:
        print("Out of date (run python3 scripts/build_site.py):", *stale, sep="\n  ")
        return 1
    print("Updated:" if stale else "Up to date.", *stale, sep="\n  " if stale else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
