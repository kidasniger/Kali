#!/usr/bin/env bash
set -euo pipefail

# Build and pin the Android PRoot backend from oonid/pr.
# PRoot derives from upstream PRoot + the Termux Android fork and
# includes Android W^X, loader and seccomp compatibility fixes.
SRC_COMMIT="fcf25cb2396361f0be2edfc96fdd61a6e738c9d9"
WORK="${RUNNER_TEMP:-/tmp}/kali-proot-src"
DEST="app/src/main/jniLibs/arm64-v8a"

rm -rf "$WORK"
mkdir -p "$WORK" "$DEST"

# The pinned source repository contains SSH submodule URLs. GitHub Actions
# has no SSH key, so rewrite the checked-out .gitmodules before initializing them.
git clone --depth 1 https://github.com/oonid/pr.git "$WORK"
git -C "$WORK" fetch --depth 1 origin "$SRC_COMMIT"
git -C "$WORK" checkout --detach "$SRC_COMMIT"

sed -i   -e 's#git@github.com:#https://github.com/#g'   -e 's#ssh://git@github.com/#https://github.com/#g'   "$WORK/.gitmodules"

git -C "$WORK" submodule sync --recursive
git -C "$WORK" submodule update --init --recursive

bash "$WORK/scripts/build.sh" --arch=arm64

PROOT="$WORK/build/out/arm64/proot"
LOADER="$WORK/build/out/arm64/loader"

test -f "$PROOT"
test -f "$LOADER"

# Delete every old proroot 1.2.8 runtime library from the build workspace.
rm -f "$DEST"/libproroot-runtime.so "$DEST"/libproroot-linker.so       "$DEST"/libproroot-bridge.so "$DEST"/libproroot-stub-loader.so

cp "$PROOT" "$DEST/libproot.so"
cp "$LOADER" "$DEST/libproot-loader.so"
chmod 755 "$DEST/libproot.so" "$DEST/libproot-loader.so"

file "$DEST/libproot.so"
file "$DEST/libproot-loader.so"
readelf -h "$DEST/libproot.so" | grep -E 'Class|Machine|Type'
readelf -h "$DEST/libproot-loader.so" | grep -E 'Class|Machine|Type'

if readelf -d "$DEST/libproot.so" 2>/dev/null | grep -q NEEDED; then
  echo "ERROR: libproot.so has dynamic dependencies."
  exit 1
fi

echo "Android PRoot backend built and verified."
