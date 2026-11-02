#!/usr/bin/env python3
"""Open a GitLab merge request against fdroid/fdroiddata adding a Build entry
for the version that was just released on GitHub.

Run by .github/workflows/fdroid-mr.yml after each GitHub release is published.
Reads VERSION_NAME, VERSION_CODE, COMMIT from the environment (set by the
workflow from app/build.gradle.kts and the released tag's commit), and
GITLAB_TOKEN (a personal access token with `api` scope on the account that
owns the fdroiddata fork).
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

UPSTREAM_PATH = "fdroid/fdroiddata"
FORK_PATH = "nituls265/fdroiddata"
RECIPE_PATH = "metadata/com.nitulshah.nudge.yml"
GITLAB_API = "https://gitlab.com/api/v4"


def gitlab_api(method, path, token, body=None):
    url = f"{GITLAB_API}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("PRIVATE-TOKEN", token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        print(f"GitLab API {method} {path} failed: {e.code} {e.read().decode()}", file=sys.stderr)
        raise


def project_id(path, token):
    encoded = path.replace("/", "%2F")
    return gitlab_api("GET", f"/projects/{encoded}", token)["id"]


def run(cmd, **kwargs):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True, **kwargs)


def main():
    token = os.environ["GITLAB_TOKEN"]
    version_name = os.environ["VERSION_NAME"]
    version_code = os.environ["VERSION_CODE"]
    commit = os.environ["COMMIT"]
    branch = f"nudge-{version_code}"

    upstream_id = project_id(UPSTREAM_PATH, token)
    fork_id = project_id(FORK_PATH, token)

    # Skip if an MR for this branch is already open — keeps re-runs idempotent.
    existing = gitlab_api(
        "GET",
        f"/projects/{fork_id}/merge_requests?source_branch={branch}&state=opened",
        token,
    )
    if existing:
        print(f"MR already open for branch {branch}: {existing[0]['web_url']}")
        return

    workdir = "fdroiddata-fork"
    clone_url = f"https://oauth2:{token}@gitlab.com/{FORK_PATH}.git"
    upstream_url = f"https://gitlab.com/{UPSTREAM_PATH}.git"

    # Full clone/fetch, not shallow: GitLab rejects a push built on
    # truncated ("shallow") history with "shallow update not allowed".
    run(["git", "clone", clone_url, workdir])
    run(["git", "-C", workdir, "remote", "add", "upstream", upstream_url])
    run(["git", "-C", workdir, "fetch", "upstream", "master"])
    run(["git", "-C", workdir, "checkout", "-B", branch, "upstream/master"])

    recipe = os.path.join(workdir, RECIPE_PATH)
    with open(recipe) as f:
        text = f.read()

    new_entry = (
        f"  - versionName: '{version_name}'\n"
        f"    versionCode: {version_code}\n"
        f"    commit: {commit}\n"
        f"    subdir: app\n"
        f"    gradle:\n"
        f"      - foss\n\n"
    )

    marker = "AllowedAPKSigningKeys:"
    if marker not in text:
        print(f"Could not find {marker!r} in {RECIPE_PATH}; aborting.", file=sys.stderr)
        sys.exit(1)
    text = text.replace(marker, new_entry + marker, 1)

    text, n1 = re.subn(r"CurrentVersion:\s*'[^']*'", f"CurrentVersion: '{version_name}'", text)
    text, n2 = re.subn(r"CurrentVersionCode:\s*\d+", f"CurrentVersionCode: {version_code}", text)
    if n1 != 1 or n2 != 1:
        print("Could not update CurrentVersion/CurrentVersionCode; aborting.", file=sys.stderr)
        sys.exit(1)

    with open(recipe, "w") as f:
        f.write(text)

    run(["git", "-C", workdir, "config", "user.name", "github-actions[bot]"])
    run(["git", "-C", workdir, "config", "user.email", "github-actions[bot]@users.noreply.github.com"])
    run(["git", "-C", workdir, "add", RECIPE_PATH])
    run(["git", "-C", workdir, "commit", "-m", f"Update Nudge to {version_name} (versionCode {version_code})"])
    run(["git", "-C", workdir, "push", "origin", branch, "-f"])

    mr = gitlab_api(
        "POST",
        f"/projects/{fork_id}/merge_requests",
        token,
        {
            "source_branch": branch,
            "target_branch": "master",
            "target_project_id": upstream_id,
            "title": f"Update Nudge to {version_name} (versionCode {version_code})",
            "description": (
                f"Automated update from the app's release workflow: "
                f"https://github.com/nituls265/Nudge/releases/tag/v{version_name}\n\n"
                f"Built from commit {commit}."
            ),
        },
    )
    print(f"Opened MR: {mr['web_url']}")


if __name__ == "__main__":
    main()
