#!/usr/bin/env python3
"""Refresh docs/stats.json from the GitHub Releases API.

Run by .github/workflows/stats.yml (daily + manual). The website never calls
the GitHub API or Supabase for stats; it only reads this same-origin file.

Output (docs/stats.json):
  {
    "github_downloads": <sum of download_count over all assets of all
                         published releases>,
    "apk_available":    <true if the latest release has APK_ASSET_NAME>,
    "updated":          "<ISO-8601 UTC timestamp of the last change>"
  }

The file is rewritten only when github_downloads or apk_available change, so
the workflow commits only on a real change. These are GitHub downloads only:
F-Droid publishes no download counts, so this is never a "total users" number.

Usage: python3 scripts/update_stats.py [owner/repo]
  Repo defaults to $GITHUB_REPOSITORY, then GITHUB_REPO in docs/config.js.
  Set $GITHUB_TOKEN to avoid the 60 requests/hour anonymous rate limit.
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATS = ROOT / "docs" / "stats.json"
CONFIG_JS = ROOT / "docs" / "config.js"


def config_value(key: str) -> str:
    m = re.search(rf'^\s*{key}\s*:\s*"([^"]*)"', CONFIG_JS.read_text(encoding="utf-8"), re.M)
    if not m:
        sys.exit(f"{key} not found in docs/config.js")
    return m.group(1)


def get(url: str):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "nudge-stats-action",
    })
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


def main() -> int:
    repo = (sys.argv[1] if len(sys.argv) > 1 else None) or os.environ.get("GITHUB_REPOSITORY") \
        or config_value("GITHUB_REPO")
    asset_name = config_value("APK_ASSET_NAME")
    api = f"https://api.github.com/repos/{repo}"

    total, page = 0, 1
    while True:
        status, releases = get(f"{api}/releases?per_page=100&page={page}")
        if status != 200 or not isinstance(releases, list):
            sys.exit(f"GitHub API error {status} listing releases for {repo}")
        for rel in releases:
            if rel.get("draft"):
                continue
            total += sum(int(a.get("download_count", 0)) for a in rel.get("assets", []))
        if len(releases) < 100:
            break
        page += 1

    status, latest = get(f"{api}/releases/latest")
    if status == 200:
        apk_available = any(a.get("name") == asset_name for a in latest.get("assets", []))
    elif status == 404:
        apk_available = False  # no published release yet
    else:
        sys.exit(f"GitHub API error {status} reading latest release")

    old = {}
    if STATS.exists():
        try:
            old = json.loads(STATS.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            old = {}

    changed = old.get("github_downloads") != total or old.get("apk_available") != apk_available
    if changed:
        data = {
            "github_downloads": total,
            "apk_available": apk_available,
            "updated": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        }
        STATS.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"github_downloads={total} apk_available={apk_available} changed={changed}")

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"changed={'true' if changed else 'false'}\n")
            f.write(f"apk_available={'true' if apk_available else 'false'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
