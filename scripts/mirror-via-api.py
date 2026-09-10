#!/usr/bin/env python3
"""Mirror the local tracked tree to github.com/main via the git-database API.

Used when the git:// https transport is unreliable (TLS resets) and when the
incremental push would be fragile (renames, deletions, empty directories).

Unlike push-via-api.py this does NOT patch the remote tree incrementally: it
rebuilds the ENTIRE tree from `git ls-files`, so the resulting commit is exactly
the local tracked state. Precondition: the remote must not contain tracked files
that the local index lacks (verify with
`comm -13 <(git ls-files|sort) <(gh api .../git/trees/main?recursive=1 ...|sort)`)
— anything remote-only is deleted by a mirror.

Usage: python3 mirror-via-api.py --message MSG
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "pawpaw-agent/dsh-handheld"
API = f"https://api.github.com/repos/{REPO}"
BRANCH = "main"
TOKEN = subprocess.check_output(["gh", "auth", "token"], text=True).strip()


def gh(method, url, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "mirror-via-api",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} {method} {url}: {e.read().decode()}")


def main():
    msg = None
    args = sys.argv[1:]
    while args:
        a = args.pop(0)
        if a == "--message":
            msg = args.pop(0)
        else:
            raise SystemExit(f"unexpected argument: {a}")
    if msg is None:
        raise SystemExit("usage: mirror-via-api.py --message MSG")

    # Local index: path -> mode, straight from git (authoritative for exec bits).
    index = subprocess.check_output(
        ["git", "ls-files", "-s"], text=True
    ).splitlines()
    files = []
    for line in index:
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        files.append((path, mode))
    print(f"local tracked files: {len(files)}")

    head = gh("GET", f"{API}/git/ref/heads/{BRANCH}")["object"]["sha"]
    print(f"{BRANCH} @ {head}")

    # 1. one blob per file
    blobs = {}
    for path, _mode in files:
        with open(path, "rb") as fh:
            content = base64.b64encode(fh.read()).decode()
        blobs[path] = gh("POST", f"{API}/git/blobs",
                         {"content": content, "encoding": "base64"})["sha"]
    print(f"blobs created: {len(blobs)}")

    # 2. build every tree level bottom-up from the file list alone.
    #    tree[dir] = {child_name: entry}, built only from what we put there, so
    #    no duplicate entries and no stale/empty directories can survive.
    tree = {}
    for path, mode in files:
        d, name = os.path.split(path)
        tree.setdefault(d, {})[name] = {
            "path": name, "mode": mode, "type": "blob", "sha": blobs[path],
        }

    dirs = set(tree)
    for d in list(tree):
        cur = d
        while cur:
            cur = os.path.dirname(cur)
            dirs.add(cur)
    dirs.add("")

    def depth(d):
        return 0 if d == "" else d.count("/") + 1

    built = {}
    for d in sorted(dirs, key=depth, reverse=True):
        entries = list(tree.get(d, {}).values())
        # attach rebuilt child directories whose parent is d
        for sub_dir, sha in built.items():
            if os.path.dirname(sub_dir) == d:
                entries.append({
                    "path": os.path.basename(sub_dir), "mode": "040000",
                    "type": "tree", "sha": sha,
                })
        entries.sort(key=lambda e: e["path"])
        built[d] = gh("POST", f"{API}/git/trees", {"tree": entries})["sha"]
    print(f"trees created: {len(built)}")

    # 3. commit + fast-forward
    commit = gh("POST", f"{API}/git/commits", {
        "message": msg, "tree": built[""], "parents": [head],
    })
    print(f"commit {commit['sha']}")
    gh("PATCH", f"{API}/git/refs/heads/{BRANCH}",
       {"sha": commit["sha"], "force": False})
    print(f"{BRANCH} -> {commit['sha']}")


if __name__ == "__main__":
    main()
