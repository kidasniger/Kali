#!/usr/bin/env bash
set -euo pipefail

VERSION="1.2.8"
BASE="https://github.com/coderredlab/proroot/releases/download/v${VERSION}"
DEST="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

declare -A SHA=(
  [libproroot.so]="a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01"
  [libproroot-runtime.so]="8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34"
  [libproroot-bridge.so]="1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f"
  [libproroot-linker.so]="51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee"
  [libproroot-stub-loader.so]="06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0"
)

for name in "${!SHA[@]}"; do
  echo "Downloading proroot $name..."
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 120 "$BASE/$name" -o "$DEST/$name"
  echo "${SHA[$name]}  $DEST/$name" | sha256sum -c -
  chmod 755 "$DEST/$name"
done

echo "Android PRoot engine ${VERSION} verified."