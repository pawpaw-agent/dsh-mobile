#!/bin/bash -e
# Build Dropbear dbclient for Android (arm64) using the NDK.
#
# Based on the MIT-licensed build script from ribbons/android-dropbear
# (https://github.com/ribbons/android-dropbear, SPDX-License-Identifier: MIT)
# — adjusted for the dsh-mobile project (single arm64 target, dbclient only).
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-dropbear.sh
#   [DROPBEAR_VERSION=DROPBEAR_2026.94] [BUILD_ONLY=dbclient]
#
# Output: ./build-dropbear-output/{dbclient,LICENSE.txt}

TARGET=${TARGET:-aarch64-linux-android}
PLATFORM=21
DROPBEAR_VERSION=${DROPBEAR_VERSION:-DROPBEAR_2026.94}
BUILD_ONLY=${BUILD_ONLY:-dbclient}
OUTDIR=${OUTDIR:-build-dropbear-output}

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME is not set" >&2
    exit 1
fi

toolchain=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$toolchain/bin/$TARGET$PLATFORM-clang" ] || {
    echo "toolchain clang not found: $toolchain/bin/$TARGET$PLATFORM-clang" >&2
    exit 1
}

# Clone upstream dropbear at the pinned tag (official mkj/dropbear source).
rm -rf dropbear
git clone -q -b "$DROPBEAR_VERSION" --depth 1 https://github.com/mkj/dropbear.git
cd dropbear

./configure --host="$TARGET" --disable-lastlog --disable-utmp --disable-wtmp \
    AR="$toolchain/bin/llvm-ar" \
    CC="$toolchain/bin/$TARGET$PLATFORM-clang" \
    RANLIB="$toolchain/bin/llvm-ranlib" \
    STRIP="$toolchain/bin/llvm-strip"

# Apply the Android-specific options (disables server password auth: crypt()
# is unavailable on Android; keeps client DROPBEAR_PASSWORD env auth).
cp ../localoptions.h .

make PROGRAMS="$BUILD_ONLY"

# Collect outputs.
cd ..
mkdir -p "$OUTDIR"
for prog in $BUILD_ONLY; do
    [ -f "dropbear/$prog" ] && install -m 0755 "dropbear/$prog" "$OUTDIR/$prog"
done
install -m 0644 dropbear/LICENSE "$OUTDIR/LICENSE.txt"

echo "built $BUILD_ONLY (dropbear $DROPBEAR_VERSION) -> $OUTDIR"
ls -la "$OUTDIR"
