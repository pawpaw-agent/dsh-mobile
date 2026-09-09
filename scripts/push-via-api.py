#!/usr/bin/env python3
"""Push a small set of file updates to github.com via the git-database API.

Used when the git:// https transport is unreliable (TLS resets).
Creates one commit with the given file contents on top of current main.
Usage: python3 push-via-api.py FILE1 [FILE2 ...]
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "pawpaw-agent/dsh-mobile"
API = f"https://api.github.com/repos/{REPO}"
TOKEN = subprocess.check_output(["gh", "auth", "token"], text=True).strip()


def gh(method, url, payload=None):
    if payload is not None:
        data = json.dumps(payload).encode()
    else:
        data = None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "push-via-api",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise SystemExit(f"HTTP {e.code} {method} {url}: {body}")


def tree_of(commit_sha):
    """Return {path: (mode, type, sha)} for all entries under the commit tree."""
    t = gh("GET", f"{API}/git/trees/{commit_sha}?recursive=1")
    out = {}
    for e in t.get("tree", []):
        out[e["path"]] = (e["mode"], e["type"], e["sha"])
    return out


def main():
    files = sys.argv[1:]
    if not files:
        raise SystemExit("usage: push-via-api.py FILE...")

    ref = gh("GET", f"{API}/git/ref/heads/main")
    head_sha = ref["object"]["sha"]
    print(f"main @ {head_sha}")

    all_entries = tree_of(head_sha)

    # 1. create blobs for the changed files
    new_sha = {}
    for f in files:
        with open(f, "rb") as fh:
            content = base64.b64encode(fh.read()).decode()
        b = gh("POST", f"{API}/git/blobs", {"content": content, "encoding": "base64"})
        new_sha[f] = b["sha"]
        print(f"blob {f} -> {b['sha'][:8]}")

    # 2. rebuild trees bottom-up along the changed paths
    by_dir = {}
    for f in files:
        d, name = os.path.split(f)
        by_dir.setdefault(d, []).append((name, new_sha[f]))

    # directories to rebuild: dirs of changed files plus all ancestors, deepest first
    dirs = set()
    for d in by_dir:
        cur = d
        while cur:
            dirs.add(cur)
            cur = os.path.dirname(cur)
    dirs.add("")
    # depth = number of path segments ("/" -> 0 segments? no: "" -> 1 as root sentinel)
    def depth(d):
        return 0 if d == "" else d.count("/") + 1
    order = sorted(dirs, key=depth, reverse=True)

    build = {}
    for d in order:
        entries = []
        prefix = d + "/" if d else ""
        for path, (mode, typ, sha) in all_entries.items():
            if os.path.dirname(path) == d:
                base = os.path.basename(path)
                sub = os.path.join(d, base) if d else base
                # replace with rebuilt subtree if needed
                if sub in build:
                    sha = build[sub]
                    typ = "tree"
                    mode = "040000"
                entries.append({"path": base, "mode": mode, "type": typ, "sha": sha})
        for name, sha in by_dir.get(d, []):
            entries.append({"path": name, "mode": "100644", "type": "blob", "sha": sha})
        t = gh("POST", f"{API}/git/trees", {"tree": entries})
        build[d] = t["sha"]
        print(f"tree {'/' if d == '' else d} -> {t['sha'][:8]}")

    root = build[""]

    # 3. create commit
    msg = "security: allowBackup=false — P1: SSH password/token must not be exfiltrated via backup\n\nCo-Authored-By: push-via-api (git transport unreliable on this host)"
    c = gh("POST", f"{API}/git/commits", {
        "message": msg,
        "tree": root,
        "parents": [head_sha],
    })
    print(f"commit {c['sha'][:12]}")

    # 4. fast-forward main
    gh("PATCH", f"{API}/git/refs/heads/main", {"sha": c["sha"], "force": False})
    print(f"main -> {c['sha']}")


if __name__ == "__main__":
    main()
