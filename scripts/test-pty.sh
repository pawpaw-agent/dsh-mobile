#!/bin/bash
# Compile and run the native pty layer's host test.
#
# dsh_pty.c is plain POSIX, so it runs on any Linux box — which is the point: fd
# leaks, window-size propagation, controlling-terminal acquisition and exit-status
# encoding are *behavioural* facts that an Android cross-build cannot check and a
# phone checks slowly. Asserting them here makes the phone step a confirmation
# rather than the first time anyone finds out.
#
# The JNI half (dsh_pty_jni.c) is not exercised — it needs a JVM — and is covered by
# compiling against the NDK in the app build.
#
# Usage: scripts/test-pty.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/android/app/src/main/cpp"
CC="${CC:-cc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "compiling the pty core with $($CC --version | head -1)"
# -Werror so a warning introduced later cannot quietly become a defect on a platform
# whose compiler is stricter than this one.
"$CC" -std=gnu11 -Wall -Wextra -Werror -O1 \
    -o "$OUT/dsh-pty-test" \
    "$SRC/dsh_pty.c" "$SRC/dsh_pty_test.c"

echo
"$OUT/dsh-pty-test"
